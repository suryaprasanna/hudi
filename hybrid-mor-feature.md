# [FEATURE] Workload-aware hybrid CoW/MoR: write a new base file directly (instead of a log file) for "hot" file groups during ingestion

## Problem / Motivation

All of our critical datasets are currently **Copy-on-Write (CoW)**. The pain point is
**write amplification**: even when only a handful of records in a file group change, CoW
rewrites the *entire* base (parquet) file for that file group. For large tables with small,
scattered updates this wastes a lot of write I/O and compute on every commit.

The obvious fix is to move to **Merge-on-Read (MoR)**, where updates are appended to delta
log files and merged later via compaction. But MoR introduces its own problems for our
workload:

- **Reader cost** – The snapshot / real-time view merges base + log files on the fly, so
  readers spend more resources and queries run longer.
- **Freshness is the top priority** – These datasets feed many downstream/derived datasets.
  Data must stay fresh and immediately queryable; we cannot afford a stale window waiting on
  compaction.

### Key observation about our update pattern

When we profiled the update patterns of these datasets, we found the updates are **highly
skewed**: most updates land in **recent partitions**, and within those, a relatively small
set of **file groups receive the vast majority of updates**. Older partitions get very few
updates.

This skew is what motivates the proposal below.

## Approaches we considered (and why they don't fully work)

| Approach | Outcome |
|---|---|
| **MoR + Read-Optimized (RO) view + aggressive compaction** | Aggressive compaction rewrites base files so frequently that we're back to **CoW-level write amplification** — no net improvement. ❌ |
| **MoR + compact only the heavy file groups + RO view** | The RO view becomes **inconsistent**: compacted file groups expose their latest updates, but file groups whose log files aren't compacted yet **silently omit** their recent updates. A query returns a partial/incorrect picture. ❌ |
| **MoR + compact only the heavy file groups + Real-Time (snapshot) view** | **Operationally viable.** ✅ The hot/frequently-updated file groups are compacted, so reading them is cheap (few/no log files to merge). Older file groups have only small/sparse log files, so on-the-fly merge there is cheap anyway. We avoid full-table write amplification while keeping reads cheap **and** data fresh. |

So the viable model is: **selectively compact the hot file groups, and serve readers from
the snapshot/real-time view.** This works because the expensive-to-merge file groups are the
ones we've already materialized.

## Proposed feature

The viable approach above still relies on a **separate compaction step**: ingestion writes a
log file for a hot file group, and then — almost immediately — compaction rewrites it into a
base file. That's two operations to schedule, run, and reason about, and it adds latency
between "data ingested" and "data efficiently readable."

**Proposal:** identify, *during ingestion*, the file groups that are about to receive a large
volume of updates, and for those file groups **write a new base file directly (updates merged
in) instead of writing a log file.** Skip the log-then-compact round trip entirely.

This effectively makes the table a **per-file-group hybrid of CoW and MoR**, decided
dynamically per commit:

- **Cold file groups** (sparse updates, e.g. older partitions) → **write log files** (MoR
  behavior — cheap writes, cheap enough merge-on-read).
- **Hot file groups** (heavy updates, e.g. recent partitions) → **write a new base file
  directly** (CoW-style merge), because they'd be compacted immediately anyway.

### Benefits

- **No separate compaction to schedule/run** for the hot groups — there's no log → compact
  churn for the data that would only get compacted right away.
- **Freshness preserved** – the merged data is materialized into a base file *at delta-commit
  time*, so it's immediately and efficiently readable; there is no compaction-lag window and
  no async compaction backlog to fall behind.
- **Write amplification avoided where it matters** – only the genuinely hot file groups pay
  the rewrite cost (which they'd pay under CoW or under aggressive compaction anyway). Cold
  file groups stay log-based and cheap.
- **Cheaper, more consistent reads** – snapshot reads on hot groups touch a fresh base file
  with few/no logs to merge; cold groups merge only small logs.

## Why this is implementable

The core building block already exists. Hudi's **small-file handling** in the
`UpsertPartitioner` already routes new inserts into existing small base files, which produces
a **new base file** (rather than a log append) for that bucket. So the write path already
knows how to produce a base file for an incoming batch in a file group.

Crucially, the decision of **"log file vs. new base file"** happens **after the workload
profiling stage**. By that point we already have, per partition / per bucket / per file group:

- the number of incoming records (insert vs. update counts),
- the current size of the file group / bucket,
- enough information to estimate the size of the update batch.

So the routing decision is a natural extension at the point where the write handle is chosen:

- Today (MoR update to an existing file group) → `HoodieAppendHandle` (log append).
- Proposed (hot file group) → take the `HoodieMergeHandle` / create-new-base path instead,
  merging the updates into a new base file — reusing the same machinery that small-file
  handling and CoW updates already use.

In other words, this is a **workload-aware switch between the existing append handle and the
existing merge/create handle, per file group**, driven by data we already compute in the
workload profile.

## Proposed configuration (open to discussion)

- A toggle to enable adaptive base-file writing for MoR tables.
- A threshold to classify a file group as "hot," e.g. based on:
  - estimated update/log size vs. base file size (similar in spirit to
    `LogFileSizeBasedCompactionStrategy`), or
  - update record count / update ratio for the file group.
- Optional bounds (e.g. max base files rewritten per commit) to cap ingestion latency.

## Design considerations / open questions

- **Timeline & file-slice semantics:** In MoR, base files normally originate from a commit /
  compaction instant, while delta commits produce log files. Producing a base file **within a
  delta commit** needs care so the timeline, file-slice resolution, and readers interpret it
  correctly. This is the main design question.
- **Read-before-write cost:** the merge path reads the existing base file (unlike a log
  append). This is the same I/O that compaction would incur — just done earlier and once —
  but it raises per-commit ingestion latency for hot groups, so bounded I/O may be desirable.
- **Interaction with metadata table / record-level index / file listing** when base files
  appear in delta commits.
- **Threshold tuning:** how to choose / auto-tune the "hot" threshold so we don't accidentally
  recreate CoW-level amplification.

## Alternatives considered

See the table above — aggressive full compaction (reverts to CoW amplification) and selective
compaction with the RO view (inconsistent reads) were both rejected. The selective-compaction
+ snapshot-view model works but keeps the extra compaction round trip that this proposal aims
to eliminate.

## Environment

- Hudi version: <fill in>
- Engine(s): Spark <version> (writer); readers: <Spark / Presto / Trino / Hive>
- Table type today: Copy-on-Write
- Storage: <HDFS / S3 / GCS>

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.common.table.view;

import org.apache.hudi.avro.model.HoodieCleanMetadata;
import org.apache.hudi.avro.model.HoodieClusteringGroup;
import org.apache.hudi.avro.model.HoodieClusteringPlan;
import org.apache.hudi.avro.model.HoodieCompactionPlan;
import org.apache.hudi.avro.model.HoodieRequestedReplaceMetadata;
import org.apache.hudi.avro.model.HoodieRestoreMetadata;
import org.apache.hudi.avro.model.HoodieRollbackMetadata;
import org.apache.hudi.avro.model.HoodieSliceInfo;
import org.apache.hudi.common.HoodieCleanStat;
import org.apache.hudi.common.HoodieRollbackStat;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.CompactionOperation;
import org.apache.hudi.common.model.FileSlice;
import org.apache.hudi.common.model.HoodieBaseFile;
import org.apache.hudi.common.model.HoodieCleaningPolicy;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieFileGroup;
import org.apache.hudi.common.model.HoodieFileGroupId;
import org.apache.hudi.common.model.HoodieReplaceCommitMetadata;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.model.HoodieWriteStat;
import org.apache.hudi.common.model.WriteOperationType;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieInstant.State;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.TimelineMetadataUtils;
import org.apache.hudi.common.testutils.HoodieCommonTestHarness;
import org.apache.hudi.common.util.CleanerUtils;
import org.apache.hudi.common.util.CompactionUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.ValidationUtils;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;

import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.hudi.common.table.timeline.HoodieTimeline.COMPACTION_ACTION;
import static org.apache.hudi.common.table.timeline.HoodieTimeline.LOG_COMPACTION_ACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests incremental file system view sync.
 */
public class TestIncrementalFSViewSync extends HoodieCommonTestHarness {

  private static final Logger LOG = LoggerFactory.getLogger(TestIncrementalFSViewSync.class);
  private static final int NUM_FILE_IDS_PER_PARTITION = 10;
  private static final String TEST_WRITE_TOKEN = "1-0-1";
  private static final List<String> PARTITIONS = Arrays.asList("2018/01/01", "2018/01/02", "2019/03/01");
  private static final List<String> FILE_IDS_PER_PARTITION =
      IntStream.range(0, NUM_FILE_IDS_PER_PARTITION).mapToObj(x -> UUID.randomUUID().toString()).collect(Collectors.toList());
  private Function<Integer, List<String>> fileIdGeneratorFunc =
      (fileIdCount) -> IntStream.range(0, fileIdCount).mapToObj(x -> UUID.randomUUID().toString()).collect(Collectors.toList());
  private final Map<String, List<String>> partitionToFileIds = new HashMap<>();
  private static final Set<HoodieFileGroupId> EMPTY_FGID_SET = Collections.emptySet();

  @BeforeEach
  public void init() throws IOException {
    initMetaClient();
    for (String p : PARTITIONS) {
      Files.createDirectories(Paths.get(basePath, p));
    }
    refreshFsView();
  }

  @AfterEach
  public void tearDown() throws Exception {
    cleanMetaClient();
  }

  private void generateNewFileIdsByPartition() {
    PARTITIONS.forEach(partition -> {
      partitionToFileIds.put(partition, fileIdGeneratorFunc.apply(NUM_FILE_IDS_PER_PARTITION));
    });
  }

  @Test
  public void testEmptyPartitionsAndTimeline() throws IOException {
    SyncableFileSystemView view = getFileSystemView(metaClient);
    assertFalse(view.getLastInstant().isPresent());
    PARTITIONS.forEach(p -> assertEquals(0, view.getLatestFileSlices(p).count()));
    view.close();
  }

  @Test
  public void testAsyncCompaction() throws IOException {
    SyncableFileSystemView view = getFileSystemView(metaClient);
    view.sync();

    // Run 3 ingestion on MOR table (3 delta commits)
    Map<String, List<String>> instantsToFiles =
        testMultipleWriteSteps(view, Arrays.asList("11", "12", "13"), true, "11");

    // Schedule Compaction
    scheduleCompaction(view, "14");

    // Restore pending compaction
    unscheduleCompaction(view, "14", "13", "11");

    // Add one more delta instant
    instantsToFiles.putAll(testMultipleWriteSteps(view, Collections.singletonList("15"), true, "11"));

    // Schedule Compaction again
    scheduleCompaction(view, "16");

    // Run Compaction - This will be the second file-slice
    testMultipleWriteSteps(view, Collections.singletonList("16"), false, "16", 2);

    // Run 2 more ingest
    instantsToFiles.putAll(testMultipleWriteSteps(view, Arrays.asList("17", "18"), true, "16", 2));

    // Schedule Compaction again
    scheduleCompaction(view, "19");

    // Run one more ingestion after pending compaction. THis will be 3rd slice
    instantsToFiles.putAll(testMultipleWriteSteps(view, Collections.singletonList("20"), true, "19", 3));

    // Clean first slice
    testCleans(view, Collections.singletonList("21"),
        new HashMap<String, List<String>>() {
          {
            put("11", Arrays.asList("12", "13", "15"));
          }
        }, instantsToFiles, Collections.singletonList("11"), 0, 0);

    // Add one more ingestion instant. This should be 2nd slice now
    instantsToFiles.putAll(testMultipleWriteSteps(view, Collections.singletonList("22"), true, "19", 2));

    // Restore last ingestion
    testRestore(view, Collections.singletonList("23"), new HashMap<>(), Collections.singletonList(getHoodieCommitInstant("22", true)), "24", false);

    // Run one more ingestion. THis is still 2nd slice
    instantsToFiles.putAll(testMultipleWriteSteps(view, Collections.singletonList("24"), true, "19", 2));

    // Finish Compaction
    instantsToFiles.putAll(testMultipleWriteSteps(view, Collections.singletonList("19"), false, "19", 2,
        Collections.singletonList(new HoodieInstant(State.COMPLETED, HoodieTimeline.DELTA_COMMIT_ACTION, "24"))));
    view.close();
  }

  @Test
  public void testAsyncMajorAndMinorCompaction() throws IOException {
    SyncableFileSystemView view = getFileSystemView(metaClient);
    view.sync();

    // Run 3 ingestion on MOR table (3 delta commits)
    Map<String, List<String>> instantsToFiles =
        testMultipleWriteSteps(view, Arrays.asList("11", "12", "13"), true, "11");

    view.sync();
    // Schedule log Compaction
    scheduleLogCompaction(view, "14", "11");

    // Schedule Compaction
    scheduleCompaction(view, "15");

    view.sync();
    // Restore pending compaction
    unscheduleLogCompaction(view, "14", "15", "15");

    // Add one more delta instant - This will be added to second file-slice
    instantsToFiles.putAll(testMultipleWriteSteps(view, Collections.singletonList("16"), true, "15", 2));

    view.sync();
    // Run Compaction
    testMultipleWriteSteps(view, Collections.singletonList("15"), false, "15", 2,
        Collections.singletonList(new HoodieInstant(State.COMPLETED, HoodieTimeline.DELTA_COMMIT_ACTION, "16")));

    // Run 2 more ingest
    instantsToFiles.putAll(testMultipleWriteSteps(view, Arrays.asList("17", "18"), true, "15", 2));

    // Schedule Compaction again
    scheduleLogCompaction(view, "19", "15");

    // Clean first slice
    testCleans(view, Collections.singletonList("20"),
        new HashMap<String, List<String>>() {
          {
            put("11", Arrays.asList("12", "13"));
          }
        }, instantsToFiles, Collections.singletonList("11"), 0, 0);

    // Add one more ingestion instant. This will be added to 1st slice now since cleaner removed the older file slice.
    instantsToFiles.putAll(testMultipleWriteSteps(view, Collections.singletonList("21"), true, "15", 1));

    // Restore last ingestion
    testRestore(view, Collections.singletonList("22"), new HashMap<>(), Collections.singletonList(getHoodieCommitInstant("21", true)), "24", false);

    // Run one more ingestion. This is still on the 1st slice
    instantsToFiles.putAll(testMultipleWriteSteps(view, Collections.singletonList("23"), true, "15", 1));

    // Finish Log Compaction
    instantsToFiles.putAll(testMultipleWriteSteps(view, Collections.singletonList("24"), true, "15", 1,
        Collections.singletonList(new HoodieInstant(State.COMPLETED, HoodieTimeline.DELTA_COMMIT_ACTION, "24"))));

    // Schedule Compaction again
    scheduleCompaction(view, "25");

    // Finish Compaction
    instantsToFiles.putAll(testMultipleWriteSteps(view, Collections.singletonList("25"), false, "25", 2,
        Collections.singletonList(new HoodieInstant(State.COMPLETED, HoodieTimeline.COMMIT_ACTION, "25"))));

    view.close();
  }

  @Test
  public void testIngestion() throws IOException {
    SyncableFileSystemView view = getFileSystemView(metaClient);

    // Add an empty ingestion
    String firstEmptyInstantTs = "11";
    HoodieCommitMetadata metadata = new HoodieCommitMetadata();
    metaClient.getActiveTimeline().createNewInstant(
        new HoodieInstant(true, HoodieTimeline.COMMIT_ACTION, firstEmptyInstantTs));
    metaClient.getActiveTimeline().saveAsComplete(
        new HoodieInstant(true, HoodieTimeline.COMMIT_ACTION, firstEmptyInstantTs),
        Option.of(metadata.toJsonString().getBytes(StandardCharsets.UTF_8)));

    view.sync();
    assertTrue(view.getLastInstant().isPresent());
    assertEquals("11", view.getLastInstant().get().getTimestamp());
    assertEquals(State.COMPLETED, view.getLastInstant().get().getState());
    assertEquals(HoodieTimeline.COMMIT_ACTION, view.getLastInstant().get().getAction());
    PARTITIONS.forEach(p -> assertEquals(0, view.getLatestFileSlices(p).count()));

    metaClient.reloadActiveTimeline();
    SyncableFileSystemView newView = getFileSystemView(metaClient);

    areViewsConsistent(view, newView, 0L, EMPTY_FGID_SET);

    // Add 3 non-empty ingestions to COW table
    Map<String, List<String>> instantsToFiles = testMultipleWriteSteps(view, Arrays.asList("12", "13", "14"));

    // restore instants in reverse order till we rollback all
    testRestore(view, Arrays.asList("15", "16", "17"), instantsToFiles,
        Arrays.asList(getHoodieCommitInstant("14", false), getHoodieCommitInstant("13", false), getHoodieCommitInstant("12", false)),
        "17", true);

    // Add 5 non-empty ingestions back-to-back
    instantsToFiles = testMultipleWriteSteps(view, Arrays.asList("18", "19", "20"));

    // Clean instants.
    testCleans(view, Arrays.asList("21", "22"), instantsToFiles, Arrays.asList("18", "19"), 0, 0);

    newView.close();
    view.close();
  }

  @Test
  public void testReplaceCommits() throws IOException {
    SyncableFileSystemView view = getFileSystemView(metaClient);

    // Add an empty ingestion
    String firstEmptyInstantTs = "11";
    HoodieCommitMetadata metadata = new HoodieCommitMetadata();
    metaClient.getActiveTimeline().createNewInstant(
        new HoodieInstant(true, HoodieTimeline.COMMIT_ACTION, firstEmptyInstantTs));
    metaClient.getActiveTimeline().saveAsComplete(
        new HoodieInstant(true, HoodieTimeline.COMMIT_ACTION, firstEmptyInstantTs),
        Option.of(metadata.toJsonString().getBytes(StandardCharsets.UTF_8)));

    view.sync();
    assertTrue(view.getLastInstant().isPresent());
    assertEquals("11", view.getLastInstant().get().getTimestamp());
    assertEquals(State.COMPLETED, view.getLastInstant().get().getState());
    assertEquals(HoodieTimeline.COMMIT_ACTION, view.getLastInstant().get().getAction());
    PARTITIONS.forEach(p -> assertEquals(0, view.getLatestFileSlices(p).count()));

    metaClient.reloadActiveTimeline();
    SyncableFileSystemView newView = getFileSystemView(metaClient);

    areViewsConsistent(view, newView, 0L, EMPTY_FGID_SET);

    // Add 1 non-empty ingestions to COW table
    Map<String, List<String>> instantsToFiles = testMultipleWriteSteps(view, Arrays.asList("12"));

    // ADD replace instants
    testMultipleReplaceSteps(instantsToFiles, view, Arrays.asList("13", "14"), NUM_FILE_IDS_PER_PARTITION);

    // restore instants in reverse order till we rollback all replace instants
    testRestore(view, Arrays.asList("15", "16"), instantsToFiles,
         Arrays.asList(getHoodieReplaceInstant("14"), getHoodieReplaceInstant("13")),
         "17", true, 1, FILE_IDS_PER_PARTITION.size());

    // clear files from inmemory view for replaced instants
    instantsToFiles.remove("14");
    instantsToFiles.remove("13");

    // add few more replace instants
    testMultipleReplaceSteps(instantsToFiles, view, Arrays.asList("18", "19", "20"), NUM_FILE_IDS_PER_PARTITION);

    // Clean instants.
    testCleans(view, Arrays.asList("21", "22"), instantsToFiles, Arrays.asList("18", "19"), NUM_FILE_IDS_PER_PARTITION, 1);

    newView.close();
    view.close();
  }

  /**
   * Test incremental sync operation on clustering transitions from requested to complete, inflight to rollback etc
   */
  @Test
  public void testClusteringCommits() throws IOException {
    Map<String, List<String>> instantsToFiles = new HashMap<>();

    // Create first commit with 10 file groups
    int instantCount = 1;
    int expectedNoOfFileSlices = 0;
    // Insert operation
    String commitTimestamp = String.format("%03d", instantCount++);
    List<String> writtenFiles = writeBatch(metaClient, commitTimestamp, false, "", true);
    instantsToFiles.put(commitTimestamp, writtenFiles);
    expectedNoOfFileSlices += NUM_FILE_IDS_PER_PARTITION * PARTITIONS.size();
    SyncableFileSystemView view = getFileSystemView(metaClient);
    view.sync();

    // Do a series of write operations and clustering operations on the dataset.
    // Fail half of clustering commits, to test incremental sync
    for (int i = 0; i < 4; i++) {
      // Insert operation
      commitTimestamp = String.format("%03d", instantCount++);
      writtenFiles = writeBatch(metaClient, commitTimestamp, false, "", true);
      instantsToFiles.put(commitTimestamp, writtenFiles);
      expectedNoOfFileSlices += NUM_FILE_IDS_PER_PARTITION * PARTITIONS.size();

      // Validate incremental sync after an insert
      validateIncrementalSync(view, expectedNoOfFileSlices, EMPTY_FGID_SET);

      // Update operation
      commitTimestamp = String.format("%03d", instantCount++);
      writtenFiles = writeBatch(metaClient, commitTimestamp, false, "", false);
      instantsToFiles.put(commitTimestamp, writtenFiles);
      expectedNoOfFileSlices += NUM_FILE_IDS_PER_PARTITION * PARTITIONS.size();

      // Validate incremental sync after upsert commits
      validateIncrementalSync(view, expectedNoOfFileSlices, EMPTY_FGID_SET);

      // do clustering
      String clusteringCommitTimestamp = String.format("%03d", instantCount++);
      // Fail clustering commit half the times
      if (i % 2 == 0) {
        // Create completed clustering
        Map<String, List<String>> partitionToReplacedFileIds = doClusteringCommit(clusteringCommitTimestamp, instantsToFiles, true);
        Set<String> replacedFileIds = partitionToReplacedFileIds.values().stream()
            .flatMap(Collection::stream)
            .collect(Collectors.toSet());
        long totalReplacedFiles = instantsToFiles.values().stream().flatMap(Collection::stream)
            .filter(file -> {
              int beginIndex = file.lastIndexOf("/");
              int lastIndex = file.indexOf("_");
              String fileId = file.substring(beginIndex + 1, lastIndex);
              return replacedFileIds.contains(fileId);
            }).count();
        expectedNoOfFileSlices += NUM_FILE_IDS_PER_PARTITION * PARTITIONS.size() - totalReplacedFiles;

        // Validate incremental sync when timeline has completed replacecommit
        validateIncrementalSync(view, expectedNoOfFileSlices, EMPTY_FGID_SET);
      } else {
        // fail clustering and do a rollback
        doClusteringCommit(clusteringCommitTimestamp, instantsToFiles, false);
        Set<HoodieFileGroupId> newlyWrittenPendingFgIds = instantsToFiles.get(clusteringCommitTimestamp).stream()
            .map(file -> {
              int beginIndex = file.lastIndexOf("/");
              int lastIndex = file.indexOf("_");
              String partition = file.substring(0, beginIndex);
              String fileId = file.substring(beginIndex + 1, lastIndex);
              return new HoodieFileGroupId(partition, fileId);
            }).collect(Collectors.toSet());
        // Validate incremental sync when timeline has inflight instant.
        validateIncrementalSync(view, expectedNoOfFileSlices, newlyWrittenPendingFgIds);

        // Execute rollback on failed clustering instant
        String rollbackCommitTimestamp = String.format("%03d", instantCount++);
        doRollback(instantsToFiles, new HoodieInstant(true, HoodieActiveTimeline.REPLACE_COMMIT_ACTION,
            clusteringCommitTimestamp), rollbackCommitTimestamp);

        // Validate incremental sync when clustering instant has been rolledback.
        validateIncrementalSync(view, expectedNoOfFileSlices, EMPTY_FGID_SET);
      }
    }
  }

  /**
   * Util method to validate incremental sync
   * @param view previous view
   * @param numberOfFileSlicesExpected total no. of files slices expected
   * @throws IOException
   */
  private void validateIncrementalSync(SyncableFileSystemView view, int numberOfFileSlicesExpected,
                                       Set<HoodieFileGroupId> newlyWrittenPendingFgIds) throws IOException {
    metaClient.reloadActiveTimeline();
    view.sync();
    IncrementalTimelineSyncFileSystemView incrementalView =
        (IncrementalTimelineSyncFileSystemView) view;
    assertTrue(incrementalView.isLastIncrementalSyncSuccessful());
    SyncableFileSystemView newView = getFileSystemView(metaClient);
    areViewsConsistent(view, newView, numberOfFileSlicesExpected, newlyWrittenPendingFgIds);
  }

  private void testMultipleReplaceSteps(Map<String, List<String>> instantsToFiles, SyncableFileSystemView view, List<String> instants,
                                        int initialExpectedSlicesPerPartition) {
    int expectedSlicesPerPartition = initialExpectedSlicesPerPartition;
    for (int i = 0; i < instants.size(); i++) {
      try {
        generateReplaceInstant(instants.get(i), instantsToFiles, false, true);
        view.sync();

        metaClient.reloadActiveTimeline();
        SyncableFileSystemView newView = getFileSystemView(metaClient);
        // 1 fileId is replaced for every partition, so subtract partitions.size()
        expectedSlicesPerPartition = expectedSlicesPerPartition + FILE_IDS_PER_PARTITION.size()  -  1;
        areViewsConsistent(view, newView, expectedSlicesPerPartition * PARTITIONS.size(), EMPTY_FGID_SET);
        newView.close();
      } catch (IOException e) {
        throw new HoodieIOException("unable to test replace", e);
      }
    }
  }

  private Map<String, List<String>> doClusteringCommit(String instant, Map<String, List<String>> instantsToFiles, boolean completeClustering) throws IOException {
    return generateReplaceInstant(instant, instantsToFiles, true, completeClustering);
  }

  private Map<String, List<String>> generateReplaceInstant(String replaceInstant, Map<String, List<String>> instantsToFiles,
                                                           boolean isClustering, boolean completeCommit) throws IOException {
    Map<String, List<String>> partitionToReplacedFileIds = pickFilesToReplace(instantsToFiles);
    // generate new fileIds for replace
    List<String> newFileIdsToUse = IntStream.range(0, NUM_FILE_IDS_PER_PARTITION).mapToObj(x -> UUID.randomUUID().toString()).collect(Collectors.toList());
    List<String> replacedFiles = addReplaceInstant(metaClient, replaceInstant,
        generateDataForInstant(replaceInstant, replaceInstant, false, newFileIdsToUse),
        partitionToReplacedFileIds, isClustering, completeCommit);
    instantsToFiles.put(replaceInstant, replacedFiles);
    return partitionToReplacedFileIds;
  }

  // pick one fileId from each partition to replace and remove it from 'instantsToFiles'
  private Map<String, List<String>> pickFilesToReplace(Map<String, List<String>> instantsToFiles) {
    if (instantsToFiles.isEmpty()) {
      return Collections.emptyMap();
    }

    String maxInstant = instantsToFiles.keySet().stream().max(Comparator.naturalOrder()).get();
    Map<String, List<String>> partitionToFileIdsList = instantsToFiles.get(maxInstant).stream().map(file -> {
      int lastPartition = file.lastIndexOf("/");
      return Pair.of(file.substring(0, lastPartition), file.substring(lastPartition + 1));
    }).collect(Collectors.groupingBy(Pair::getKey, Collectors.mapping(Pair::getValue, Collectors.toList())));
    return PARTITIONS.stream()
        .map(p -> Pair.of(p, FSUtils.getFileId(partitionToFileIdsList.get(p).get(0))))
        .collect(Collectors.groupingBy(Pair::getKey, Collectors.mapping(Pair::getValue, Collectors.toList())));
  }

  private HoodieInstant getHoodieReplaceInstant(String timestamp) {
    return new HoodieInstant(false, HoodieTimeline.REPLACE_COMMIT_ACTION, timestamp);
  }

  private HoodieInstant getHoodieCommitInstant(String timestamp, boolean isDeltaCommit) {
    String action = isDeltaCommit ? HoodieTimeline.DELTA_COMMIT_ACTION : HoodieTimeline.COMMIT_ACTION;
    return new HoodieInstant(false, action, timestamp);
  }

  /**
   * Tests FS View incremental syncing behavior when multiple instants gets committed.
   */
  @Test
  public void testMultipleTransitions() throws IOException {

    SyncableFileSystemView view1 = getFileSystemView(metaClient);
    view1.sync();
    Map<String, List<String>> instantsToFiles;

    /*
     * Case where incremental syncing is catching up on more than one ingestion at a time
     */
    // Run 1 ingestion on MOR table (1 delta commits). View1 is now sync up to this point
    instantsToFiles = testMultipleWriteSteps(view1, Collections.singletonList("11"), true, "11");

    SyncableFileSystemView view2 =
        getFileSystemView(HoodieTableMetaClient.builder().setConf(metaClient.getHadoopConf()).setBasePath(metaClient.getBasePathV2().toString()).build());

    // Run 2 more ingestion on MOR table. View1 is not yet synced but View2 is
    instantsToFiles.putAll(testMultipleWriteSteps(view2, Arrays.asList("12", "13"), true, "11"));

    // Now Sync view1 and add 1 more ingestion. Check if view1 is able to catchup correctly
    instantsToFiles.putAll(testMultipleWriteSteps(view1, Collections.singletonList("14"), true, "11"));

    view2.sync();
    SyncableFileSystemView view3 =
        getFileSystemView(HoodieTableMetaClient.builder().setConf(metaClient.getHadoopConf()).setBasePath(metaClient.getBasePathV2().toString()).build());
    view3.sync();
    areViewsConsistent(view1, view2, PARTITIONS.size() * FILE_IDS_PER_PARTITION.size(), EMPTY_FGID_SET);

    /*
     * Case where a compaction is scheduled and then unscheduled
     */
    scheduleCompaction(view2, "15");
    unscheduleCompaction(view2, "15", "14", "11");
    view1.sync();
    areViewsConsistent(view1, view2, PARTITIONS.size() * FILE_IDS_PER_PARTITION.size(), EMPTY_FGID_SET);
    SyncableFileSystemView view4 =
        getFileSystemView(HoodieTableMetaClient.builder().setConf(metaClient.getHadoopConf()).setBasePath(metaClient.getBasePathV2().toString()).build());
    view4.sync();

    /*
     * Case where a compaction is scheduled, 2 ingestion happens and then a compaction happens
     */
    scheduleCompaction(view2, "16");
    instantsToFiles.putAll(testMultipleWriteSteps(view2, Arrays.asList("17", "18"), true, "16", 2));
    // Compaction
    testMultipleWriteSteps(view2, Collections.singletonList("16"), false, "16", 2,
        Collections.singletonList(new HoodieInstant(State.COMPLETED, HoodieTimeline.DELTA_COMMIT_ACTION, "18")));
    view1.sync();
    areViewsConsistent(view1, view2, PARTITIONS.size() * FILE_IDS_PER_PARTITION.size() * 2, EMPTY_FGID_SET);
    SyncableFileSystemView view5 =
        getFileSystemView(HoodieTableMetaClient.builder().setConf(metaClient.getHadoopConf()).setBasePath(metaClient.getBasePathV2().toString()).build());
    view5.sync();

    /*
     * Case where a clean happened and then rounds of ingestion and compaction happened
     */
    testCleans(view2, Collections.singletonList("19"),
        new HashMap<String, List<String>>() {
            {
              put("11", Arrays.asList("12", "13", "14"));
            }
        },
        instantsToFiles, Collections.singletonList("11"), 0, 0);
    scheduleCompaction(view2, "20");
    instantsToFiles.putAll(testMultipleWriteSteps(view2, Arrays.asList("21", "22"), true, "20", 2));
    // Compaction
    testMultipleWriteSteps(view2, Collections.singletonList("20"), false, "20", 2,
        Collections.singletonList(new HoodieInstant(State.COMPLETED, HoodieTimeline.DELTA_COMMIT_ACTION, "22")));
    // Run one more round of ingestion
    instantsToFiles.putAll(testMultipleWriteSteps(view2, Arrays.asList("23", "24"), true, "20", 2));
    view1.sync();
    areViewsConsistent(view1, view2, PARTITIONS.size() * FILE_IDS_PER_PARTITION.size() * 2, EMPTY_FGID_SET);
    SyncableFileSystemView view6 =
        getFileSystemView(HoodieTableMetaClient.builder().setConf(metaClient.getHadoopConf()).setBasePath(metaClient.getBasePathV2().toString()).build());
    view6.sync();

    /*
     * Case where multiple restores and ingestions happened
     */
    testRestore(view2, Collections.singletonList("25"), new HashMap<>(), Collections.singletonList(getHoodieCommitInstant("24", true)), "29", true);
    testRestore(view2, Collections.singletonList("26"), new HashMap<>(), Collections.singletonList(getHoodieCommitInstant("23", true)), "29", false);
    instantsToFiles.putAll(testMultipleWriteSteps(view2, Collections.singletonList("27"), true, "20", 2));
    scheduleCompaction(view2, "28");
    instantsToFiles.putAll(testMultipleWriteSteps(view2, Collections.singletonList("29"), true, "28", 3));
    // Compaction
    testMultipleWriteSteps(view2, Collections.singletonList("28"), false, "28", 3,
        Collections.singletonList(new HoodieInstant(State.COMPLETED, HoodieTimeline.DELTA_COMMIT_ACTION, "29")));

    Arrays.asList(view1, view2, view3, view4, view5, view6).forEach(v -> {
      v.sync();
      areViewsConsistent(v, view1, PARTITIONS.size() * FILE_IDS_PER_PARTITION.size() * 3, EMPTY_FGID_SET);
    });

    view1.close();
    view2.close();
    view3.close();
    view4.close();
    view5.close();
    view6.close();
  }

  /*
   ********************************************************************************************************
   * HELPER METHODS
   *********************************************************************************************************
   */
  /**
   * Helper to run one or more rounds of cleaning, incrementally syncing the view and then validate.
   */
  private void testCleans(SyncableFileSystemView view, List<String> newCleanerInstants,
      Map<String, List<String>> instantsToFiles, List<String> cleanedInstants, int numberOfFilesAddedPerInstant,
      int numberOfFilesReplacedPerInstant) {
    Map<String, List<String>> deltaInstantMap = cleanedInstants.stream().map(e -> Pair.of(e, new ArrayList()))
        .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
    testCleans(view, newCleanerInstants, deltaInstantMap, instantsToFiles, cleanedInstants, numberOfFilesAddedPerInstant, numberOfFilesReplacedPerInstant);
  }

  /**
   * Simulates one of more cleaning, incrementally sync the view and validate the view.
   *
   * @param view Hoodie View
   * @param newCleanerInstants Cleaner Instants
   * @param deltaInstantMap File-Slice Base Instants to Delta Instants
   * @param instantsToFiles List of files associated with each instant
   * @param cleanedInstants List of cleaned instants
   */
  private void testCleans(SyncableFileSystemView view, List<String> newCleanerInstants,
      Map<String, List<String>> deltaInstantMap, Map<String, List<String>> instantsToFiles,
      List<String> cleanedInstants, int numFilesAddedPerInstant, int numFilesReplacedPerInstant) {
    final int netFilesAddedPerInstant = numFilesAddedPerInstant - numFilesReplacedPerInstant;
    assertEquals(newCleanerInstants.size(), cleanedInstants.size());
    long exp = PARTITIONS.stream().mapToLong(p1 -> view.getAllFileSlices(p1).count()).findAny().getAsLong();
    LOG.info("Initial File Slices :" + exp);
    for (int idx = 0; idx < newCleanerInstants.size(); idx++) {
      String instant = cleanedInstants.get(idx);
      try {
        List<String> filesToDelete = new ArrayList<>(instantsToFiles.get(instant));
        deltaInstantMap.get(instant).forEach(n -> filesToDelete.addAll(instantsToFiles.get(n)));

        performClean(instant, filesToDelete, newCleanerInstants.get(idx));

        exp -= FILE_IDS_PER_PARTITION.size() - numFilesReplacedPerInstant;
        final long expTotalFileSlicesPerPartition = exp;
        view.sync();
        assertTrue(view.getLastInstant().isPresent());
        assertEquals(newCleanerInstants.get(idx), view.getLastInstant().get().getTimestamp());
        assertEquals(State.COMPLETED, view.getLastInstant().get().getState());
        assertEquals(HoodieTimeline.CLEAN_ACTION, view.getLastInstant().get().getAction());
        PARTITIONS.forEach(p -> {
          LOG.info("PARTITION : " + p);
          LOG.info("\tFileSlices :" + view.getAllFileSlices(p).collect(Collectors.toList()));
        });

        final int instantIdx = newCleanerInstants.size() - idx;
        PARTITIONS.forEach(p -> assertEquals(FILE_IDS_PER_PARTITION.size() + instantIdx * netFilesAddedPerInstant, view.getLatestFileSlices(p).count()));
        PARTITIONS.forEach(p -> assertEquals(expTotalFileSlicesPerPartition, view.getAllFileSlices(p).count()));

        metaClient.reloadActiveTimeline();
        SyncableFileSystemView newView = getFileSystemView(metaClient);
        areViewsConsistent(view, newView, expTotalFileSlicesPerPartition * PARTITIONS.size(), EMPTY_FGID_SET);
        newView.close();
      } catch (IOException e) {
        throw new HoodieException(e);
      }
    }
  }

  /**
   * Simulates one of more restores, incrementally sync the view and validate the view.
   *
   * @param view Hoodie View
   * @param newRestoreInstants Restore Instants
   * @param instantsToFiles List of files associated with each instant
   * @param rolledBackInstants List of rolled-back instants
   * @param emptyRestoreInstant Restore instant at which table becomes empty
   */
  private void testRestore(SyncableFileSystemView view, List<String> newRestoreInstants,
                           Map<String, List<String>> instantsToFiles, List<HoodieInstant> rolledBackInstants, String emptyRestoreInstant,
                           boolean isRestore) {
    testRestore(view, newRestoreInstants, instantsToFiles, rolledBackInstants, emptyRestoreInstant, isRestore, 0, 0);
  }

  private void doRollback(Map<String, List<String>> instantsToFiles, HoodieInstant instantToRollback,
                          String rollbackInstantTimestamp) throws IOException {
    performRestore(instantToRollback, instantsToFiles.get(instantToRollback.getTimestamp()), rollbackInstantTimestamp, false);
    instantsToFiles.remove(instantToRollback.getTimestamp());
  }

  private void testRestore(SyncableFileSystemView view, List<String> newRestoreInstants,
      Map<String, List<String>> instantsToFiles, List<HoodieInstant> rolledBackInstants, String emptyRestoreInstant,
      boolean isRestore, int totalReplacedFileSlicesPerPartition, int totalFilesAddedPerPartitionPerInstant) {
    assertEquals(newRestoreInstants.size(), rolledBackInstants.size());
    long initialFileSlices = PARTITIONS.stream().mapToLong(p -> view.getAllFileSlices(p).count()).findAny().getAsLong();
    final int numFileSlicesAddedPerInstant = (totalFilesAddedPerPartitionPerInstant - totalReplacedFileSlicesPerPartition);
    final long expectedLatestFileSlices = FILE_IDS_PER_PARTITION.size() + (rolledBackInstants.size()) * numFileSlicesAddedPerInstant;
    IntStream.range(0, newRestoreInstants.size()).forEach(idx -> {
      HoodieInstant instant = rolledBackInstants.get(idx);
      try {
        boolean isDeltaCommit = HoodieTimeline.DELTA_COMMIT_ACTION.equalsIgnoreCase(instant.getAction());
        performRestore(instant, instantsToFiles.getOrDefault(instant.getTimestamp(), Collections.emptyList()), newRestoreInstants.get(idx), isRestore);
        final long expTotalFileSlicesPerPartition =
            isDeltaCommit ? initialFileSlices : initialFileSlices - ((idx + 1) * (FILE_IDS_PER_PARTITION.size() - totalReplacedFileSlicesPerPartition));
        view.sync();
        assertTrue(view.getLastInstant().isPresent());
        LOG.info("Last Instant is :" + view.getLastInstant().get());
        if (isRestore) {
          assertEquals(newRestoreInstants.get(idx), view.getLastInstant().get().getTimestamp());
          assertEquals(HoodieTimeline.RESTORE_ACTION, view.getLastInstant().get().getAction());
        }
        assertEquals(State.COMPLETED, view.getLastInstant().get().getState());

        if (HoodieTimeline.compareTimestamps(newRestoreInstants.get(idx), HoodieTimeline.GREATER_THAN_OR_EQUALS, emptyRestoreInstant
        )) {
          PARTITIONS.forEach(p -> assertEquals(0, view.getLatestFileSlices(p).count()));
        } else {
          PARTITIONS.forEach(p -> assertEquals(expectedLatestFileSlices - (idx + 1) * numFileSlicesAddedPerInstant, view.getLatestFileSlices(p).count()));
        }
        PARTITIONS.forEach(p -> assertEquals(expTotalFileSlicesPerPartition, view.getAllFileSlices(p).count()));

        metaClient.reloadActiveTimeline();
        SyncableFileSystemView newView = getFileSystemView(metaClient);
        areViewsConsistent(view, newView, expTotalFileSlicesPerPartition * PARTITIONS.size(), EMPTY_FGID_SET);
        newView.close();
      } catch (IOException e) {
        throw new HoodieException(e);
      }
    });
  }

  /**
   * Simulate a Cleaner operation cleaning up an instant.
   *
   * @param instant Instant to be cleaner
   * @param files List of files to be deleted
   * @param cleanInstant Cleaner Instant
   */
  private void performClean(String instant, List<String> files, String cleanInstant)
      throws IOException {
    Map<String, List<String>> partitionToFiles = deleteFiles(files);
    List<HoodieCleanStat> cleanStats = partitionToFiles.entrySet().stream().map(e ->
        new HoodieCleanStat(HoodieCleaningPolicy.KEEP_LATEST_COMMITS, e.getKey(), e.getValue(), e.getValue(),
            new ArrayList<>(), Integer.toString(Integer.parseInt(instant) + 1), "")).collect(Collectors.toList());

    HoodieInstant cleanInflightInstant = new HoodieInstant(true, HoodieTimeline.CLEAN_ACTION, cleanInstant);
    metaClient.getActiveTimeline().createNewInstant(cleanInflightInstant);
    HoodieCleanMetadata cleanMetadata = CleanerUtils.convertCleanMetadata(cleanInstant, Option.empty(), cleanStats);
    metaClient.getActiveTimeline().saveAsComplete(cleanInflightInstant,
        TimelineMetadataUtils.serializeCleanMetadata(cleanMetadata));
  }

  /**
   * Simulate Restore of an instant in timeline and fsview.
   *
   * @param instant Instant to be rolled-back
   * @param files List of files to be deleted as part of rollback
   * @param rollbackInstant Restore Instant
   */
  private void performRestore(HoodieInstant instant, List<String> files, String rollbackInstant,
      boolean isRestore) throws IOException {
    Map<String, List<String>> partitionToFiles = deleteFiles(files);
    List<HoodieRollbackStat> rollbackStats = partitionToFiles.entrySet().stream().map(e ->
        new HoodieRollbackStat(e.getKey(), e.getValue(), new ArrayList<>(), new HashMap<>())
    ).collect(Collectors.toList());

    List<HoodieInstant> rollbacks = new ArrayList<>();
    rollbacks.add(instant);

    HoodieRollbackMetadata rollbackMetadata =
        TimelineMetadataUtils.convertRollbackMetadata(rollbackInstant, Option.empty(), rollbacks, rollbackStats);
    if (isRestore) {
      List<HoodieRollbackMetadata> rollbackM = new ArrayList<>();
      rollbackM.add(rollbackMetadata);
      HoodieRestoreMetadata metadata = TimelineMetadataUtils.convertRestoreMetadata(rollbackInstant,
          100, Collections.singletonList(instant), Collections.singletonMap(rollbackInstant, rollbackM));

      HoodieInstant restoreInstant = new HoodieInstant(true, HoodieTimeline.RESTORE_ACTION, rollbackInstant);
      metaClient.getActiveTimeline().createNewInstant(restoreInstant);
      metaClient.getActiveTimeline().saveAsComplete(restoreInstant, TimelineMetadataUtils.serializeRestoreMetadata(metadata));
    } else {
      metaClient.getActiveTimeline().createNewInstant(
          new HoodieInstant(true, HoodieTimeline.ROLLBACK_ACTION, rollbackInstant));
      metaClient.getActiveTimeline().saveAsComplete(
          new HoodieInstant(true, HoodieTimeline.ROLLBACK_ACTION, rollbackInstant),
          TimelineMetadataUtils.serializeRollbackMetadata(rollbackMetadata));
    }
    boolean deleted = metaClient.getFs().delete(new Path(metaClient.getMetaPath(), instant.getFileName()), false);
    assertTrue(deleted);
    if (instant.isInflight()) {
      HoodieInstant requestedInstant = new HoodieInstant(State.REQUESTED, instant.getAction(), instant.getTimestamp());
      deleted = metaClient.getFs().delete(new Path(metaClient.getMetaPath(), requestedInstant.getFileName()), false);
      assertTrue(deleted);
    }
  }

  /**
   * Utility to delete a list of files and group the deleted files by partitions.
   *
   * @param files List of files to be deleted
   */
  private Map<String, List<String>> deleteFiles(List<String> files) throws IOException {
    Map<String, List<String>> partitionToFiles = new HashMap<>();
    PARTITIONS.forEach(p -> partitionToFiles.put(p, new ArrayList<>()));

    for (String f : files) {
      java.nio.file.Path fullPath = Paths.get(metaClient.getBasePathV2().toString(), f);
      Files.delete(fullPath);
      String partition = PARTITIONS.stream().filter(f::startsWith).findAny().get();
      partitionToFiles.get(partition).add(fullPath.toUri().toString());
    }
    return partitionToFiles;
  }

  /**
   * Schedule a pending compaction and validate.
   *
   * @param view Hoodie View
   * @param instantTime Compaction Instant Time
   */
  private void scheduleCompaction(SyncableFileSystemView view, String  instantTime) throws IOException {
    List<Pair<String, FileSlice>> slices = PARTITIONS.stream()
        .flatMap(p -> view.getLatestFileSlices(p).map(s -> Pair.of(p, s))).collect(Collectors.toList());

    long initialExpTotalFileSlices = PARTITIONS.stream().mapToLong(p -> view.getAllFileSlices(p).count()).sum();
    HoodieInstant compactionRequestedInstant = new HoodieInstant(State.REQUESTED, COMPACTION_ACTION, instantTime);
    HoodieCompactionPlan plan = CompactionUtils.buildFromFileSlices(slices, Option.empty(), Option.empty());
    metaClient.getActiveTimeline().saveToCompactionRequested(compactionRequestedInstant,
        TimelineMetadataUtils.serializeCompactionPlan(plan));

    view.sync();
    PARTITIONS.forEach(p -> {
      view.getLatestFileSlices(p).forEach(fs -> {
        assertEquals(instantTime, fs.getBaseInstantTime());
        assertEquals(p, fs.getPartitionPath());
        assertFalse(fs.getBaseFile().isPresent());
      });
      view.getLatestMergedFileSlicesBeforeOrOn(p, instantTime).forEach(fs -> {
        assertTrue(HoodieTimeline.compareTimestamps(instantTime, HoodieTimeline.GREATER_THAN, fs.getBaseInstantTime()));
        assertEquals(p, fs.getPartitionPath());
      });
    });

    metaClient.reloadActiveTimeline();
    SyncableFileSystemView newView = getFileSystemView(metaClient);
    areViewsConsistent(view, newView,
        initialExpTotalFileSlices + PARTITIONS.size() * FILE_IDS_PER_PARTITION.size(), EMPTY_FGID_SET);
    newView.close();
  }

  /**
   * Schedule a pending Log compaction and validate.
   *
   * @param view Hoodie View
   * @param instantTime Log Compaction Instant Time
   */
  private void scheduleLogCompaction(SyncableFileSystemView view, String instantTime, String baseInstantTime) throws IOException {
    List<Pair<String, FileSlice>> slices = PARTITIONS.stream()
        .flatMap(p -> view.getLatestFileSlices(p).map(s -> Pair.of(p, s))).collect(Collectors.toList());

    long initialExpTotalFileSlices = PARTITIONS.stream().mapToLong(p -> view.getAllFileSlices(p).count()).sum();
    HoodieInstant logCompactionRequestedInstant = new HoodieInstant(State.REQUESTED, LOG_COMPACTION_ACTION, instantTime);
    HoodieCompactionPlan plan = CompactionUtils.buildFromFileSlices(slices, Option.empty(), Option.empty());
    metaClient.getActiveTimeline().saveToLogCompactionRequested(logCompactionRequestedInstant,
        TimelineMetadataUtils.serializeCompactionPlan(plan));

    view.sync();
    PARTITIONS.forEach(p -> {
      view.getLatestFileSlices(p).forEach(fs -> {
        assertEquals(baseInstantTime, fs.getBaseInstantTime());
        assertEquals(p, fs.getPartitionPath());
      });
    });

    metaClient.reloadActiveTimeline();
    SyncableFileSystemView newView = getFileSystemView(metaClient);
    areViewsConsistent(view, newView, initialExpTotalFileSlices, EMPTY_FGID_SET);
    newView.close();
  }

  /**
   * Unschedule a compaction instant and validate incremental fs view.
   *
   * @param view Hoodie View
   * @param compactionInstantTime Compaction Instant to be removed
   * @param newLastInstant New Last instant
   * @param newBaseInstant New Base instant of last file-slice
   */
  private void unscheduleCompaction(SyncableFileSystemView view, String compactionInstantTime, String newLastInstant,
      String newBaseInstant) throws IOException {
    HoodieInstant instant = new HoodieInstant(State.REQUESTED, COMPACTION_ACTION, compactionInstantTime);
    boolean deleted = metaClient.getFs().delete(new Path(metaClient.getMetaPath(), instant.getFileName()), false);
    ValidationUtils.checkArgument(deleted, "Unable to delete compaction instant.");

    view.sync();
    assertEquals(newLastInstant, view.getLastInstant().get().getTimestamp());
    PARTITIONS.forEach(p -> view.getLatestFileSlices(p).forEach(fs -> assertEquals(newBaseInstant, fs.getBaseInstantTime())));
  }

  /**
   * Unschedule a log compaction instant and validate incremental fs view.
   *
   * @param view Hoodie View
   * @param logCompactionInstantTime Log Compaction Instant to be removed
   * @param newLastInstant New Last instant
   * @param newBaseInstant New Base instant of last file-slice
   */
  private void unscheduleLogCompaction(SyncableFileSystemView view, String logCompactionInstantTime, String newLastInstant,
                                    String newBaseInstant) throws IOException {
    HoodieInstant instant = new HoodieInstant(State.REQUESTED, LOG_COMPACTION_ACTION, logCompactionInstantTime);
    boolean deleted = metaClient.getFs().delete(new Path(metaClient.getMetaPath(), instant.getFileName()), false);
    ValidationUtils.checkArgument(deleted, "Unable to delete log compaction instant.");

    view.sync();
    assertEquals(newLastInstant, view.getLastInstant().get().getTimestamp());
    PARTITIONS.forEach(p -> view.getLatestFileSlices(p).forEach(fs -> assertEquals(newBaseInstant, fs.getBaseInstantTime())));
  }

  /**
   * Perform one or more rounds of ingestion/compaction and validate incremental timeline syncing.
   *
   * @param view                      Hoodie View
   * @param instants                  Ingestion/Commit Instants
   * @param deltaCommit               Delta Commit
   * @param baseInstantForDeltaCommit Base Instant to be used in case of delta-commit
   * @return List of new file created
   */
  private Map<String, List<String>> testMultipleWriteSteps(SyncableFileSystemView view, List<String> instants,
      boolean deltaCommit, String baseInstantForDeltaCommit) throws IOException {
    return testMultipleWriteSteps(view, instants, deltaCommit, baseInstantForDeltaCommit, 1);
  }

  /**
   * Perform one or more rounds of ingestion/compaction and validate incremental timeline syncing.
   *
   * @param view Hoodie View
   * @param instants Ingestion/Commit Instants
   * @param deltaCommit Delta Commit ?
   * @param baseInstantForDeltaCommit Base Instant to be used in case of delta-commit
   * @param begin initial file-slice offset
   * @return List of new file created
   */
  private Map<String, List<String>> testMultipleWriteSteps(SyncableFileSystemView view, List<String> instants,
      boolean deltaCommit, String baseInstantForDeltaCommit, int begin) throws IOException {
    return testMultipleWriteSteps(view, instants, deltaCommit, baseInstantForDeltaCommit, begin,
        instants.stream()
            .map(i -> new HoodieInstant(State.COMPLETED,
                deltaCommit ? HoodieTimeline.DELTA_COMMIT_ACTION : HoodieTimeline.COMMIT_ACTION, i))
            .collect(Collectors.toList()));
  }

  /**
   * Perform one or more rounds of ingestion/compaction and validate incremental timeline syncing.
   *
   * @param view Hoodie View
   * @param instants Ingestion/Commit INstants
   * @return List of new file created
   */
  private Map<String, List<String>> testMultipleWriteSteps(SyncableFileSystemView view, List<String> instants)
      throws IOException {
    return testMultipleWriteSteps(view, instants, false, null, 1);
  }

  /**
   * Perform one or more rounds of ingestion/compaction and validate incremental timeline syncing.
   *
   * @param view Hoodie View
   * @param instants Ingestion/Commit INstants
   * @param deltaCommit Delta COmmit ?
   * @param baseInstantForDeltaCommit Base Instant to be used in case of delta-commit
   * @param begin initial file-slice offset
   * @param lastInstants List of Last Instants at each time we ingest/compact
   * @return List of new file created
   */
  private Map<String, List<String>> testMultipleWriteSteps(SyncableFileSystemView view, List<String> instants,
      boolean deltaCommit, String baseInstantForDeltaCommit, int begin, List<HoodieInstant> lastInstants)
      throws IOException {
    Map<String, List<String>> instantToFiles = new HashMap<>();

    int multiple = begin;
    for (int idx = 0; idx < instants.size(); idx++) {
      String instant = instants.get(idx);
      LOG.info("Adding instant=" + instant);
      HoodieInstant lastInstant = lastInstants.get(idx);
      // Add a non-empty ingestion to COW table
      List<String> filePaths =
          addInstant(metaClient, instant, deltaCommit, deltaCommit ? baseInstantForDeltaCommit : instant);
      view.sync();
      assertTrue(view.getLastInstant().isPresent());
      assertEquals(lastInstant.getTimestamp(), view.getLastInstant().get().getTimestamp());
      assertEquals(State.COMPLETED, view.getLastInstant().get().getState());
      assertEquals(lastInstant.getAction(), view.getLastInstant().get().getAction(),
          "Expected Last=" + lastInstant + ", Found Instants="
              + view.getTimeline().getInstants());
      PARTITIONS.forEach(p -> assertEquals(FILE_IDS_PER_PARTITION.size(), view.getLatestFileSlices(p).count()));
      final long expTotalFileSlicesPerPartition = FILE_IDS_PER_PARTITION.size() * multiple;
      PARTITIONS.forEach(p -> assertEquals(expTotalFileSlicesPerPartition, view.getAllFileSlices(p).count()));
      if (deltaCommit) {
        PARTITIONS.forEach(p ->
            view.getLatestFileSlices(p).forEach(f -> assertEquals(baseInstantForDeltaCommit, f.getBaseInstantTime()))
        );
      } else {
        PARTITIONS.forEach(p -> view.getLatestBaseFiles(p).forEach(f -> assertEquals(instant, f.getCommitTime())));
      }

      metaClient.reloadActiveTimeline();
      SyncableFileSystemView newView = getFileSystemView(metaClient);
      areViewsConsistent(view, newView,
          FILE_IDS_PER_PARTITION.size() * PARTITIONS.size() * multiple, EMPTY_FGID_SET);
      newView.close();
      instantToFiles.put(instant, filePaths);
      if (!deltaCommit) {
        multiple++;
      }
    }
    return instantToFiles;
  }

  /**
   * Check for equality of views.
   *
   * @param view1 View1
   * @param view2 View2
   */
  private void areViewsConsistent(SyncableFileSystemView view1, SyncableFileSystemView view2,
                                  long expectedTotalFileSlices, Set<HoodieFileGroupId> newlyWrittenPendingFgIds) {
    // Timeline check
    assertEquals(view1.getLastInstant(), view2.getLastInstant());

    // Validation needs to be done only for file groups that are committed.
    // View Checks
    Map<HoodieFileGroupId, HoodieFileGroup> fileGroupsMap1 = PARTITIONS.stream()
        .flatMap(partition -> view1
            .getAllFileGroups(partition)
            .filter(fileGroup -> fileGroup.getAllFileSlices().count() > 0)
            .filter(fileGroup -> !newlyWrittenPendingFgIds.contains(fileGroup.getFileGroupId())))
        .collect(Collectors.toMap(HoodieFileGroup::getFileGroupId, fg -> fg));
    Map<HoodieFileGroupId, HoodieFileGroup> fileGroupsMap2 = PARTITIONS.stream()
        .flatMap(partition -> view2
            .getAllFileGroups(partition)
            .filter(fileGroup -> fileGroup.getAllFileSlices().count() > 0)
            .filter(fileGroup -> !newlyWrittenPendingFgIds.contains(fileGroup.getFileGroupId())))
        .collect(Collectors.toMap(HoodieFileGroup::getFileGroupId, fg -> fg));
    assertEquals(fileGroupsMap1.keySet(), fileGroupsMap2.keySet());
    long gotSlicesCount = fileGroupsMap1.keySet().stream()
        .map(k -> Pair.of(fileGroupsMap1.get(k), fileGroupsMap2.get(k))).mapToLong(e -> {
          HoodieFileGroup fg1 = e.getKey();
          HoodieFileGroup fg2 = e.getValue();
          assertEquals(fg1.getFileGroupId(), fg2.getFileGroupId());
          List<FileSlice> slices1 = fg1.getAllRawFileSlices().collect(Collectors.toList());
          List<FileSlice> slices2 = fg2.getAllRawFileSlices().collect(Collectors.toList());
          assertEquals(slices1.size(), slices2.size());
          IntStream.range(0, slices1.size()).mapToObj(idx -> Pair.of(slices1.get(idx), slices2.get(idx)))
              .forEach(e2 -> {
                FileSlice slice1 = e2.getKey();
                FileSlice slice2 = e2.getValue();
                assertEquals(slice1.getBaseInstantTime(), slice2.getBaseInstantTime());
                assertEquals(slice1.getFileId(), slice2.getFileId());
                assertEquals(slice1.getBaseFile().isPresent(), slice2.getBaseFile().isPresent());
                if (slice1.getBaseFile().isPresent()) {
                  HoodieBaseFile df1 = slice1.getBaseFile().get();
                  HoodieBaseFile df2 = slice2.getBaseFile().get();
                  assertEquals(df1.getCommitTime(), df2.getCommitTime());
                  assertEquals(df1.getFileId(), df2.getFileId());
                  assertEquals(df1.getFileName(), df2.getFileName());
                  assertEquals(Path.getPathWithoutSchemeAndAuthority(new Path(df1.getPath())),
                      Path.getPathWithoutSchemeAndAuthority(new Path(df2.getPath())));
                }
                List<Path> logPaths1 = slice1.getLogFiles()
                    .map(lf -> Path.getPathWithoutSchemeAndAuthority(lf.getPath())).collect(Collectors.toList());
                List<Path> logPaths2 = slice2.getLogFiles()
                    .map(lf -> Path.getPathWithoutSchemeAndAuthority(lf.getPath())).collect(Collectors.toList());
                assertEquals(logPaths1, logPaths2);
              });
          return slices1.size();
        }).sum();
    assertEquals(expectedTotalFileSlices, gotSlicesCount);

    // Pending Compaction Operations Check
    Set<Pair<String, CompactionOperation>> ops1 = view1.getPendingCompactionOperations().collect(Collectors.toSet());
    Set<Pair<String, CompactionOperation>> ops2 = view2.getPendingCompactionOperations().collect(Collectors.toSet());
    assertEquals(ops1, ops2);

    // Pending Log Compaction Operations Check
    ops1 = view1.getPendingLogCompactionOperations().collect(Collectors.toSet());
    ops2 = view2.getPendingLogCompactionOperations().collect(Collectors.toSet());
    assertEquals(ops1, ops2);

    // Pending Clustering operations check
    Set<Pair<HoodieFileGroupId, HoodieInstant>> fileGroups1 = view1.getFileGroupsInPendingClustering().collect(Collectors.toSet());
    Set<Pair<HoodieFileGroupId, HoodieInstant>> fileGroups2 = view2.getFileGroupsInPendingClustering().collect(Collectors.toSet());
    assertEquals(fileGroups1, fileGroups2);
  }

  private List<Pair<String, HoodieWriteStat>> generateDataForInstant(String baseInstant, String instant, boolean deltaCommit) {
    return generateDataForInstant(baseInstant, instant, deltaCommit, FILE_IDS_PER_PARTITION);
  }

  private List<Pair<String, HoodieWriteStat>> generateDataForInstant(String baseInstant, String instant, boolean deltaCommit, boolean isInsert) {
    if (isInsert) {
      generateNewFileIdsByPartition();
    }
    return PARTITIONS.stream().flatMap(p -> partitionToFileIds.get(p).stream().map(f -> {
      try {
        File file = new File(basePath + "/" + p + "/"
            + (deltaCommit
            ? FSUtils.makeLogFileName(f, ".log", baseInstant, Integer.parseInt(instant), TEST_WRITE_TOKEN)
            : FSUtils.makeBaseFileName(instant, TEST_WRITE_TOKEN, f)));
        file.createNewFile();
        HoodieWriteStat w = new HoodieWriteStat();
        w.setFileId(f);
        w.setPath(String.format("%s/%s", p, file.getName()));
        return Pair.of(p, w);
      } catch (IOException e) {
        throw new HoodieException(e);
      }
    })).collect(Collectors.toList());
  }

  private List<Pair<String, HoodieWriteStat>> generateDataForInstant(String baseInstant, String instant, boolean deltaCommit, List<String> fileIds) {
    return PARTITIONS.stream().flatMap(p -> fileIds.stream().map(f -> {
      try {
        java.nio.file.Path filePath = Paths.get(basePath, p, deltaCommit
            ? FSUtils.makeLogFileName(f, ".log", baseInstant, Integer.parseInt(instant), TEST_WRITE_TOKEN)
            : FSUtils.makeBaseFileName(instant, TEST_WRITE_TOKEN, f));
        Files.createFile(filePath);
        HoodieWriteStat w = new HoodieWriteStat();
        w.setFileId(f);
        w.setPath(String.format("%s/%s", p, filePath.getFileName()));
        return Pair.of(p, w);
      } catch (IOException e) {
        throw new HoodieException(e);
      }
    })).collect(Collectors.toList());
  }

  /**
   * Util method used to write bunch of files used by both inserts and updates
   */
  private List<String> writeBatch(HoodieTableMetaClient metaClient, String instant, boolean deltaCommit,
                                  String baseInstant, boolean isInsert) throws IOException {
    HoodieInstant newRequestedInstant = new HoodieInstant(HoodieInstant.State.REQUESTED, HoodieTimeline.COMMIT_ACTION, instant);
    metaClient.getActiveTimeline().createNewInstant(newRequestedInstant);
    List<Pair<String, HoodieWriteStat>> writeStats = generateDataForInstant(baseInstant, instant, deltaCommit, isInsert);
    HoodieCommitMetadata metadata = new HoodieCommitMetadata();
    writeStats.forEach(e -> metadata.addWriteStat(e.getKey(), e.getValue()));
    HoodieInstant inflightInstant = new HoodieInstant(true,
        deltaCommit ? HoodieTimeline.DELTA_COMMIT_ACTION : HoodieTimeline.COMMIT_ACTION, instant);
    metaClient.getActiveTimeline().createNewInstant(inflightInstant);
    metaClient.getActiveTimeline().saveAsComplete(inflightInstant,
        Option.of(metadata.toJsonString().getBytes(StandardCharsets.UTF_8)));
    return writeStats.stream().map(e -> e.getValue().getPath()).collect(Collectors.toList());
  }

  private List<String> addInstant(HoodieTableMetaClient metaClient, String instant, boolean deltaCommit,
      String baseInstant) throws IOException {
    List<Pair<String, HoodieWriteStat>> writeStats = generateDataForInstant(baseInstant, instant, deltaCommit);
    HoodieCommitMetadata metadata = new HoodieCommitMetadata();
    writeStats.forEach(e -> metadata.addWriteStat(e.getKey(), e.getValue()));
    HoodieInstant inflightInstant = new HoodieInstant(true,
        deltaCommit ? HoodieTimeline.DELTA_COMMIT_ACTION : HoodieTimeline.COMMIT_ACTION, instant);
    metaClient.getActiveTimeline().createNewInstant(inflightInstant);
    metaClient.getActiveTimeline().saveAsComplete(inflightInstant,
        Option.of(metadata.toJsonString().getBytes(StandardCharsets.UTF_8)));
    /*
    // Delete pending compaction if present
    metaClient.getFs().delete(new Path(metaClient.getMetaPath(),
        new HoodieInstant(State.REQUESTED, HoodieTimeline.COMPACTION_ACTION, instant).getFileName()));
     */
    return writeStats.stream().map(e -> e.getValue().getPath()).collect(Collectors.toList());
  }

  private List<String> addReplaceInstant(HoodieTableMetaClient metaClient, String instant,
                                         List<Pair<String, HoodieWriteStat>> writeStats,
                                         Map<String, List<String>> partitionToReplaceFileIds,
                                         boolean isClusteringInstant, boolean completeCommit) throws IOException {
    // created requested
    HoodieInstant newRequestedInstant = new HoodieInstant(State.REQUESTED, HoodieTimeline.REPLACE_COMMIT_ACTION, instant);
    WriteOperationType operationType = isClusteringInstant ? WriteOperationType.CLUSTER : WriteOperationType.UNKNOWN;
    HoodieClusteringPlan clusteringPlan = null;
    if (isClusteringInstant) {
      clusteringPlan = new HoodieClusteringPlan();
      HoodieClusteringGroup clusteringGroup = new HoodieClusteringGroup();
      List<HoodieSliceInfo> slices = new ArrayList<>();
      for (Map.Entry<String, List<String>> entry : partitionToReplaceFileIds.entrySet()) {
        String partitionPath = entry.getKey();
        entry.getValue().forEach(fileId -> {
          HoodieSliceInfo sliceInfo = new HoodieSliceInfo();
          sliceInfo.setFileId(fileId);
          sliceInfo.setPartitionPath(partitionPath);
          slices.add(sliceInfo);
        });
      }
      clusteringGroup.setSlices(slices);
      clusteringPlan.setInputGroups(Collections.singletonList(clusteringGroup));
    }
    HoodieRequestedReplaceMetadata requestedReplaceMetadata = HoodieRequestedReplaceMetadata.newBuilder()
        .setOperationType(operationType.name())
        .setClusteringPlan(isClusteringInstant ? clusteringPlan : null)
        .build();
    metaClient.getActiveTimeline().saveToPendingReplaceCommit(newRequestedInstant,
        TimelineMetadataUtils.serializeRequestedReplaceMetadata(requestedReplaceMetadata));
    
    metaClient.reloadActiveTimeline();
    // transition to inflight
    HoodieInstant inflightInstant = metaClient.getActiveTimeline().transitionReplaceRequestedToInflight(newRequestedInstant, Option.empty());

    if (completeCommit) {
      // transition to replacecommit
      HoodieReplaceCommitMetadata replaceCommitMetadata = new HoodieReplaceCommitMetadata();
      writeStats.forEach(e -> replaceCommitMetadata.addWriteStat(e.getKey(), e.getValue()));
      replaceCommitMetadata.setPartitionToReplaceFileIds(partitionToReplaceFileIds);
      replaceCommitMetadata.setOperationType(operationType);
      metaClient.getActiveTimeline().saveAsComplete(inflightInstant,
          Option.of(replaceCommitMetadata.toJsonString().getBytes(StandardCharsets.UTF_8)));
    }
    return writeStats.stream().map(e -> e.getValue().getPath()).collect(Collectors.toList());
  }

  @Override
  protected HoodieTableType getTableType() {
    return HoodieTableType.MERGE_ON_READ;
  }

}

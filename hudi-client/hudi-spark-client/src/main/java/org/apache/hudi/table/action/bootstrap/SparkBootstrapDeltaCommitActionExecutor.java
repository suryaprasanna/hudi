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

package org.apache.hudi.table.action.bootstrap;

import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.client.common.HoodieSparkEngineContext;
import org.apache.hudi.common.data.HoodieData;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.table.HoodieTable;
import org.apache.hudi.table.action.HoodieWriteMetadata;
import org.apache.hudi.table.action.commit.SparkAutoCommitExecutor;
import org.apache.hudi.table.action.deltacommit.SparkBulkInsertDeltaCommitActionExecutor;

import java.util.Map;

public class SparkBootstrapDeltaCommitActionExecutor<T>
    extends SparkBootstrapCommitActionExecutor<T> {

  public SparkBootstrapDeltaCommitActionExecutor(HoodieSparkEngineContext context,
                                                 HoodieWriteConfig config, HoodieTable table,
                                                 Option<Map<String, String>> extraMetadata) {
    super(context, config, table, extraMetadata);
  }

  @Override
  protected HoodieWriteMetadata<HoodieData<WriteStatus>> doBulkInsertAndCommit(HoodieData<HoodieRecord> inputRecordsRDD, HoodieWriteConfig writeConfig) {
    return new SparkAutoCommitExecutor(new SparkBulkInsertDeltaCommitActionExecutor(
        (HoodieSparkEngineContext) context,
        writeConfig,
        table,
        HoodieTimeline.FULL_BOOTSTRAP_INSTANT_TS,
        inputRecordsRDD,
        Option.empty(),
        extraMetadata)).execute();
  }
}

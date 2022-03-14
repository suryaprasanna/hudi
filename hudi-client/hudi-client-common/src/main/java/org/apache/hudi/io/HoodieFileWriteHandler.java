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

package org.apache.hudi.io;

import org.apache.hadoop.fs.Path;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.engine.TaskContextSupplier;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.model.IOType;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieInsertException;
import org.apache.hudi.execution.ParquetFileMetaToWriteStatusConvertor;
import org.apache.hudi.table.HoodieTable;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Write handle that is used to work on top of files rather than on individual records.
 */
public class HoodieFileWriteHandler<T extends HoodieRecordPayload, I, K, O> extends HoodieWriteHandle<T, I, K, O> {

  private static final Logger LOG = LogManager.getLogger(HoodieFileWriteHandler.class);
  private final Path path;
  private String prevCommit;

  public HoodieFileWriteHandler(HoodieWriteConfig config, String instantTime, HoodieTable<T, I, K, O> hoodieTable,
                                String partitionPath, String fileId, TaskContextSupplier taskContextSupplier,
                                Path srcPath) {
    super(config, instantTime, partitionPath, fileId, hoodieTable, taskContextSupplier);

    // Output file path.
    this.path = makeNewPath(partitionPath);
    this.prevCommit = srcPath.getName().split("_")[2].split("\\.")[0];

    // Create inProgress marker file
    createInProgressMarkerFile(partitionPath,FSUtils.makeDataFileName(this.instantTime, this.writeToken, this.fileId, hoodieTable.getBaseFileExtension()));
    LOG.info("New CreateHandle for partition :" + partitionPath + " with fileId " + fileId);
  }

  /**
   * Complete writing of the file by creating the success marker file.
   * @return WriteStatuses, ideally it will be only one object.
   */
  @Override
  public List<WriteStatus> close() {
    LOG.info("Closing the file " + this.fileId + " as we are done with the file.");
    try {
      Map<String, Object> executionConfigs = new HashMap<>();
      executionConfigs.put("prevCommit", prevCommit);
      executionConfigs.put("timeTaken", timer.endTimer());

      this.writeStatus = generateWriteStatus(path.toString(), partitionPath, executionConfigs);

      // createCompleteMarkerFile throws hoodieException, if marker directory is not present.
      createCompletedMarkerFile(partitionPath);
      LOG.info(String.format("CreateHandle for partitionPath %s fileID %s, took %d ms.",
          writeStatus.getStat().getPartitionPath(), writeStatus.getStat().getFileId(),
          writeStatus.getStat().getRuntimeStats().getTotalCreateTime()));

      return Collections.singletonList(writeStatus);
    } catch (IOException e) {
      throw new HoodieInsertException("Failed to close the Insert Handle for path " + path, e);
    }
  }

  /**
   * Given a parquet file it generates WriteStatus object for a parquet file.
   * @param outputFile parquet file
   * @param partitionPath partition information of the parquet file
   * @param executionConfigs Some configs collected during execution.
   * @return WriteStatus object.
   * @throws IOException
   */
  protected WriteStatus generateWriteStatus(
      String outputFile, String partitionPath, Map<String, Object> executionConfigs) throws IOException {
    ParquetFileMetaToWriteStatusConvertor convertor =
        new ParquetFileMetaToWriteStatusConvertor(hoodieTable, config);
    return convertor.convert(outputFile, partitionPath, executionConfigs);
  }

  @Override
  public IOType getIOType() {
    return IOType.CREATE;
  }

  public Path getPath() {
    return path;
  }
}

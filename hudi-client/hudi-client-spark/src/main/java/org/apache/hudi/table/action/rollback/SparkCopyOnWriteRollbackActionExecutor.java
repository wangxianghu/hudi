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

package org.apache.hudi.table.action.rollback;

import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.HoodieEngineContext;
import org.apache.hudi.common.HoodieRollbackStat;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.util.HoodieTimer;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.table.BaseHoodieTable;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SparkCopyOnWriteRollbackActionExecutor<T extends HoodieRecordPayload> extends BaseCopyOnWriteRollbackActionExecutor<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> {
  private static final Logger LOG = LogManager.getLogger(SparkCopyOnWriteRollbackActionExecutor.class);

  public SparkCopyOnWriteRollbackActionExecutor(HoodieEngineContext context,
                                                HoodieWriteConfig config,
                                                BaseHoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> table,
                                                String instantTime,
                                                HoodieInstant commitInstant,
                                                boolean deleteInstants) {
    super(context, config, table, instantTime, commitInstant, deleteInstants);
  }

  public SparkCopyOnWriteRollbackActionExecutor(HoodieEngineContext context,
                                                HoodieWriteConfig config,
                                                BaseHoodieTable<T, JavaRDD<HoodieRecord<T>>, JavaRDD<HoodieKey>, JavaRDD<WriteStatus>, JavaPairRDD<HoodieKey, Option<Pair<String, String>>>> table,
                                                String instantTime,
                                                HoodieInstant commitInstant,
                                                boolean deleteInstants,
                                                boolean skipTimelinePublish,
                                                boolean useMarkerBasedStrategy) {
    super(context, config, table, instantTime, commitInstant, deleteInstants, skipTimelinePublish, useMarkerBasedStrategy);
  }

  @Override
  protected RollbackStrategy getRollbackStrategy() {
    if (useMarkerBasedStrategy) {
      return new SparkMarkerBasedRollbackStrategy(table, context, config, instantTime);
    } else {
      return this::executeRollbackUsingFileListing;
    }
  }

  @Override
  protected List<HoodieRollbackStat> executeRollback() {
    HoodieTimer rollbackTimer = new HoodieTimer();
    rollbackTimer.startTimer();

    List<HoodieRollbackStat> stats = new ArrayList<>();
    HoodieActiveTimeline activeTimeline = table.getActiveTimeline();
    HoodieInstant resolvedInstant = instantToRollback;

    if (instantToRollback.isCompleted()) {
      LOG.info("Unpublishing instant " + instantToRollback);
      resolvedInstant = activeTimeline.revertToInflight(instantToRollback);
      // reload meta-client to reflect latest timeline status
      table.getMetaClient().reloadActiveTimeline();
    }

    // For Requested State (like failure during index lookup), there is nothing to do rollback other than
    // deleting the timeline file
    if (!resolvedInstant.isRequested()) {
      // delete all the data files for this commit
      LOG.info("Clean out all base files generated for commit: " + resolvedInstant);
      stats = getRollbackStrategy().execute(resolvedInstant);
    }
    // Delete Inflight instant if enabled
    deleteInflightAndRequestedInstant(deleteInstants, activeTimeline, resolvedInstant);
    LOG.info("Time(in ms) taken to finish rollback " + rollbackTimer.endTimer());
    return stats;
  }

  private List<ListingBasedRollbackRequest> generateRollbackRequestsByListing() {
    try {
      return FSUtils.getAllPartitionPaths(table.getMetaClient().getFs(), table.getMetaClient().getBasePath(),
          config.shouldAssumeDatePartitioning()).stream()
          .map(ListingBasedRollbackRequest::createRollbackRequestWithDeleteDataAndLogFilesAction)
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new HoodieIOException("Error generating rollback requests", e);
    }
  }

  @Override
  protected List<HoodieRollbackStat> executeRollbackUsingFileListing(HoodieInstant instantToRollback) {
    List<ListingBasedRollbackRequest> rollbackRequests = generateRollbackRequestsByListing();
    return new SparkListingBasedRollbackHelper(table.getMetaClient(), config).performRollback(context, instantToRollback, rollbackRequests);
  }
}

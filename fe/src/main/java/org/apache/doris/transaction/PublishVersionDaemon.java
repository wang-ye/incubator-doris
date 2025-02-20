// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.transaction;

import org.apache.doris.catalog.Catalog;
import org.apache.doris.catalog.Replica;
import org.apache.doris.catalog.TabletInvertedIndex;
import org.apache.doris.common.Config;
import org.apache.doris.common.UserException;
import org.apache.doris.common.util.Daemon;
import org.apache.doris.task.AgentBatchTask;
import org.apache.doris.task.AgentTaskExecutor;
import org.apache.doris.task.AgentTaskQueue;
import org.apache.doris.task.PublishVersionTask;
import org.apache.doris.thrift.TPartitionVersionInfo;
import org.apache.doris.thrift.TTaskType;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PublishVersionDaemon extends Daemon {
    
    private static final Logger LOG = LogManager.getLogger(PublishVersionDaemon.class);
    
    public PublishVersionDaemon() {
        super("PUBLISH_VERSION", Config.publish_version_interval_ms);
    }
    
    protected void runOneCycle() {
        try {
            publishVersion();
        } catch (Throwable t) {
            LOG.error("errors while publish version to all backends, {}", t);
        }
    }
    
    private void publishVersion() throws UserException {
        GlobalTransactionMgr globalTransactionMgr = Catalog.getCurrentGlobalTransactionMgr();
        List<TransactionState> readyTransactionStates = globalTransactionMgr.getReadyToPublishTransactions();
        if (readyTransactionStates == null || readyTransactionStates.isEmpty()) {
            return;
        }
        // TODO yiguolei: could publish transaction state according to multi-tenant cluster info
        // but should do more work. for example, if a table is migrate from one cluster to another cluster
        // should publish to two clusters.
        // attention here, we publish transaction state to all backends including dead backend, if not publish to dead backend
        // then transaction manager will treat it as success
        List<Long> allBackends = Catalog.getCurrentSystemInfo().getBackendIds(false);
        if (allBackends.isEmpty()) {
            LOG.warn("some transaction state need to publish, but no backend exists");
            return;
        }
        // every backend-transaction identified a single task
        AgentBatchTask batchTask = new AgentBatchTask();
        // traverse all ready transactions and dispatch the publish version task to all backends
        for (TransactionState transactionState : readyTransactionStates) {
            if (transactionState.hasSendTask()) {
                continue;
            }
            List<PartitionCommitInfo> partitionCommitInfos = new ArrayList<>();
            for (TableCommitInfo tableCommitInfo : transactionState.getIdToTableCommitInfos().values()) {
                partitionCommitInfos.addAll(tableCommitInfo.getIdToPartitionCommitInfo().values());
            }
            List<TPartitionVersionInfo> partitionVersionInfos = new ArrayList<>(partitionCommitInfos.size());
            for (PartitionCommitInfo commitInfo : partitionCommitInfos) {
                TPartitionVersionInfo versionInfo = new TPartitionVersionInfo(commitInfo.getPartitionId(), 
                        commitInfo.getVersion(), 
                        commitInfo.getVersionHash());
                partitionVersionInfos.add(versionInfo);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("try to publish version info partitionid [{}], version [{}], version hash [{}]", 
                            commitInfo.getPartitionId(), 
                            commitInfo.getVersion(), 
                            commitInfo.getVersionHash());
                }
            }
            Set<Long> publishBackends = transactionState.getPublishVersionTasks().keySet();
            // public version tasks are not persisted in catalog, so publishBackends may be empty.
            // so we have to try publish to all backends;
            if (publishBackends.isEmpty()) {
                // could not just add to it, should new a new object, or the back map will destroyed
                publishBackends = Sets.newHashSet();
                publishBackends.addAll(allBackends);
            }

            for (long backendId : publishBackends) {
                PublishVersionTask task = new PublishVersionTask(backendId,
                        transactionState.getTransactionId(),
                        transactionState.getDbId(),
                        partitionVersionInfos);
                // add to AgentTaskQueue for handling finish report.
                // not check return value, because the add will success
                AgentTaskQueue.addTask(task);
                batchTask.addTask(task);
                transactionState.addPublishVersionTask(backendId, task);
            }
            transactionState.setHasSendTask(true);
            LOG.info("send publish tasks for transaction: {}", transactionState.getTransactionId());
        }
        if (!batchTask.getAllTasks().isEmpty()) {
            AgentTaskExecutor.submit(batchTask);
        }
        
        TabletInvertedIndex tabletInvertedIndex = Catalog.getCurrentInvertedIndex();
        // try to finish the transaction, if failed just retry in next loop
        long currentTime = System.currentTimeMillis();
        for (TransactionState transactionState : readyTransactionStates) {
            if (currentTime - transactionState.getPublishVersionTime() < Config.publish_version_interval_ms * 2) {
                // wait 2 rounds before handling publish result
                continue;
            }
            Map<Long, PublishVersionTask> transTasks = transactionState.getPublishVersionTasks();
            Set<Replica> transErrorReplicas = Sets.newHashSet();
            List<PublishVersionTask> unfinishedTasks = Lists.newArrayList();
            for (PublishVersionTask publishVersionTask : transTasks.values()) {
                if (publishVersionTask.isFinished()) {
                    // sometimes backend finish publish version task, but it maybe failed to change transactionid to version for some tablets
                    // and it will upload the failed tabletinfo to fe and fe will deal with them
                    List<Long> errorTablets = publishVersionTask.getErrorTablets();
                    if (errorTablets == null || errorTablets.isEmpty()) {
                        continue;
                    } else {
                        for (long tabletId : errorTablets) {
                            // tablet inverted index also contains rollingup index
                            Replica replica = tabletInvertedIndex.getReplica(tabletId, publishVersionTask.getBackendId());
                            transErrorReplicas.add(replica);
                        }
                    }
                } else {
                    unfinishedTasks.add(publishVersionTask);
                }
            }

            boolean shouldFinishTxn = false;
            if (!unfinishedTasks.isEmpty()) {
                if (transactionState.isPublishTimeout()) {
                    // transaction's publish is timeout, but there still has unfinished tasks.
                    // we need to collect all error replicas, and try to finish this txn.
                    for (PublishVersionTask unfinishedTask : unfinishedTasks) {
                        // set all replica in the backend to error state
                        List<TPartitionVersionInfo> versionInfos = unfinishedTask.getPartitionVersionInfos();
                        Set<Long> errorPartitionIds = Sets.newHashSet();
                        for (TPartitionVersionInfo versionInfo : versionInfos) {
                            errorPartitionIds.add(versionInfo.getPartition_id());
                        }
                        if (errorPartitionIds.isEmpty()) {
                            continue;
                        }

                        // TODO(cmy): this is inefficient, but just keep it simple. will change it later.
                        List<Long> tabletIds = tabletInvertedIndex.getTabletIdsByBackendId(unfinishedTask.getBackendId());
                        for (long tabletId : tabletIds) {
                            long partitionId = tabletInvertedIndex.getPartitionId(tabletId);
                            if (errorPartitionIds.contains(partitionId)) {
                                Replica replica = tabletInvertedIndex.getReplica(tabletId,
                                                                                 unfinishedTask.getBackendId());
                                transErrorReplicas.add(replica);
                            }
                        }
                    }

                    shouldFinishTxn = true;
                }
                // transaction's publish is not timeout, waiting next round.
            } else {
                // all publish tasks are finished, try to finish this txn.
                shouldFinishTxn = true;
            }
            
            if (shouldFinishTxn) {
                Set<Long> allErrorReplicas = transErrorReplicas.stream().map(v -> v.getId()).collect(Collectors.toSet());
                globalTransactionMgr.finishTransaction(transactionState.getTransactionId(), allErrorReplicas);
                if (transactionState.getTransactionStatus() != TransactionStatus.VISIBLE) {
                    // if finish transaction state failed, then update publish version time, should check 
                    // to finish after some interval
                    transactionState.updateSendTaskTime();
                    LOG.debug("publish version for transation {} failed, has {} error replicas during publish", 
                            transactionState, transErrorReplicas.size());
                }
            }

            if (transactionState.getTransactionStatus() == TransactionStatus.VISIBLE) {
                for (PublishVersionTask task : transactionState.getPublishVersionTasks().values()) {
                    AgentTaskQueue.removeTask(task.getBackendId(), TTaskType.PUBLISH_VERSION, task.getSignature());
                }
            }
        } // end for readyTransactionStates
    }
}

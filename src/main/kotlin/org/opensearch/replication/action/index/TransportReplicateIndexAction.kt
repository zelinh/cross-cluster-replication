/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.replication.action.index

import org.opensearch.replication.ReplicationPlugin
import org.opensearch.replication.action.setup.SetupChecksAction
import org.opensearch.replication.action.setup.SetupChecksRequest
import org.opensearch.replication.metadata.store.ReplicationContext
import org.opensearch.replication.util.SecurityContext
import org.opensearch.replication.util.ValidationUtil
import org.opensearch.replication.util.completeWith
import org.opensearch.replication.util.coroutineContext
import org.opensearch.replication.util.overrideFgacRole
import org.opensearch.replication.util.suspendExecute
import org.opensearch.replication.util.suspending
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import org.opensearch.action.ActionListener
import org.opensearch.action.admin.indices.settings.get.GetSettingsRequest
import org.opensearch.action.support.ActionFilters
import org.opensearch.action.support.HandledTransportAction
import org.opensearch.action.support.IndicesOptions
import org.opensearch.client.Client
import org.opensearch.cluster.metadata.IndexMetadata
import org.opensearch.common.inject.Inject
import org.opensearch.common.settings.Settings
import org.opensearch.env.Environment
import org.opensearch.index.IndexNotFoundException
import org.opensearch.index.IndexSettings
import org.opensearch.replication.ReplicationPlugin.Companion.KNN_INDEX_SETTING
import org.opensearch.tasks.Task
import org.opensearch.threadpool.ThreadPool
import org.opensearch.transport.TransportService

class TransportReplicateIndexAction @Inject constructor(transportService: TransportService,
                                                        val threadPool: ThreadPool,
                                                        actionFilters: ActionFilters,
                                                        private val client : Client,
                                                        private val environment: Environment) :
        HandledTransportAction<ReplicateIndexRequest, ReplicateIndexResponse>(ReplicateIndexAction.NAME,
                transportService, actionFilters, ::ReplicateIndexRequest),
    CoroutineScope by GlobalScope {

    companion object {
        private val log = LogManager.getLogger(TransportReplicateIndexAction::class.java)
    }

    override fun doExecute(task: Task, request: ReplicateIndexRequest, listener: ActionListener<ReplicateIndexResponse>) {
        launch(threadPool.coroutineContext()) {
            listener.completeWith {
                log.info("Setting-up replication for ${request.leaderAlias}:${request.leaderIndex} -> ${request.followerIndex}")
                val user = SecurityContext.fromSecurityThreadContext(threadPool.threadContext)

                val followerReplContext = ReplicationContext(request.followerIndex,
                        user?.overrideFgacRole(request.useRoles?.get(ReplicateIndexRequest.FOLLOWER_CLUSTER_ROLE)))
                val leaderReplContext = ReplicationContext(request.leaderIndex,
                        user?.overrideFgacRole(request.useRoles?.get(ReplicateIndexRequest.LEADER_CLUSTER_ROLE)))

                // For autofollow request, setup checks are already made during addition of the pattern with
                // original user
                if(!request.isAutoFollowRequest) {
                    val setupChecksReq = SetupChecksRequest(followerReplContext, leaderReplContext, request.leaderAlias)
                    val setupChecksRes = client.suspendExecute(SetupChecksAction.INSTANCE, setupChecksReq)
                    if(!setupChecksRes.isAcknowledged) {
                        log.error("Setup checks failed while triggering replication for ${request.leaderAlias}:${request.leaderIndex} -> " +
                                "${request.followerIndex}")
                        throw org.opensearch.replication.ReplicationException("Setup checks failed while setting-up replication for ${request.followerIndex}")
                    }
                }

                // Any checks on the settings is followed by setup checks to ensure all relevant changes are
                // present across the plugins
                // validate index metadata on the leader cluster
                val leaderIndexMetadata = getLeaderIndexMetadata(request.leaderAlias, request.leaderIndex)
                ValidationUtil.validateLeaderIndexMetadata(leaderIndexMetadata)

                val leaderSettings = getLeaderIndexSettings(request.leaderAlias, request.leaderIndex)

                if (leaderSettings.keySet().contains(ReplicationPlugin.REPLICATED_INDEX_SETTING.key) and
                        !leaderSettings.get(ReplicationPlugin.REPLICATED_INDEX_SETTING.key).isNullOrBlank()) {
                    throw IllegalArgumentException("Cannot Replicate a Replicated Index ${request.leaderIndex}")
                }
                if (!leaderSettings.getAsBoolean(IndexSettings.INDEX_SOFT_DELETES_SETTING.key, true)) {
                    throw IllegalArgumentException("Cannot Replicate an index where the setting ${IndexSettings.INDEX_SOFT_DELETES_SETTING.key} is disabled")
                }

                // For k-NN indices, k-NN loads its own engine and this conflicts with the replication follower engine
                // Blocking k-NN indices for replication
                if(leaderSettings.getAsBoolean(KNN_INDEX_SETTING, false)) {
                    throw IllegalArgumentException("Cannot replicate k-NN index - ${request.leaderIndex}")
                }

                ValidationUtil.validateAnalyzerSettings(environment, leaderSettings, request.settings)

                // Setup checks are successful and trigger replication for the index
                // permissions evaluation to trigger replication is based on the current security context set
                val internalReq = ReplicateIndexClusterManagerNodeRequest(user, request)
                client.suspendExecute(ReplicateIndexClusterManagerNodeAction.INSTANCE, internalReq)
                ReplicateIndexResponse(true)
            }
        }
    }

    private suspend fun getLeaderIndexMetadata(leaderAlias: String, leaderIndex: String): IndexMetadata {
        val remoteClusterClient = client.getRemoteClusterClient(leaderAlias)
        val clusterStateRequest = remoteClusterClient.admin().cluster().prepareState()
                .clear()
                .setIndices(leaderIndex)
                .setMetadata(true)
                .setIndicesOptions(IndicesOptions.strictSingleIndexNoExpandForbidClosed())
                .request()
        val remoteState = remoteClusterClient.suspending(remoteClusterClient.admin().cluster()::state,
                injectSecurityContext = true, defaultContext = true)(clusterStateRequest).state
        return remoteState.metadata.index(leaderIndex) ?: throw IndexNotFoundException("${leaderAlias}:${leaderIndex}")
    }

    private suspend fun getLeaderIndexSettings(leaderAlias: String, leaderIndex: String): Settings {
        val remoteClient = client.getRemoteClusterClient(leaderAlias)
        val getSettingsRequest = GetSettingsRequest().includeDefaults(false).indices(leaderIndex)
        val settingsResponse = remoteClient.suspending(remoteClient.admin().indices()::getSettings,
                injectSecurityContext = true)(getSettingsRequest)
        return settingsResponse.indexToSettings.get(leaderIndex) ?: throw IndexNotFoundException("${leaderAlias}:${leaderIndex}")
    }
}

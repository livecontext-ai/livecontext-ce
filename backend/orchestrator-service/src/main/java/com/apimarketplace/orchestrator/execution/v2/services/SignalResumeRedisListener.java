package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.config.OrchestratorInstanceRegistrar;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Subscribes to Redis {@code signal:resolved:*} channels so that signal
 * resolutions originating on OTHER orchestrator instances still trigger
 * local workflow resumption when this instance owns the RunContext.
 *
 * <p>This is the cross-instance complement to the JVM-local
 * {@link SignalResumeService#onSignalResolved} Spring event listener.
 * With run-level affinity, ~95% of signals resolve on the correct instance
 * (and use the fast local Spring Event path). This Redis listener is a
 * safety net for the remaining cases (timer expiry on wrong instance,
 * failover, external API callbacks).
 *
 * <p>Duplicate processing is prevented by the Redis SETNX dedup in
 * {@link SignalResumeService#resumeAfterSignal}.
 */
@Component
@ConditionalOnBean(RedisMessageListenerContainer.class)
public class SignalResumeRedisListener implements MessageListener {

    private static final Logger logger = LoggerFactory.getLogger(SignalResumeRedisListener.class);

    private final RedisMessageListenerContainer listenerContainer;
    private final RunContextRegistry runContextRegistry;
    private final SignalWaitRepository signalWaitRepository;
    private final SignalResumeService signalResumeService;
    private final WorkflowRunRepository workflowRunRepository;

    @Nullable
    @Autowired(required = false)
    private OrchestratorInstanceRegistrar registrar;

    public SignalResumeRedisListener(RedisMessageListenerContainer listenerContainer,
                                     RunContextRegistry runContextRegistry,
                                     SignalWaitRepository signalWaitRepository,
                                     SignalResumeService signalResumeService,
                                     WorkflowRunRepository workflowRunRepository) {
        this.listenerContainer = listenerContainer;
        this.runContextRegistry = runContextRegistry;
        this.signalWaitRepository = signalWaitRepository;
        this.signalResumeService = signalResumeService;
        this.workflowRunRepository = workflowRunRepository;
    }

    @PostConstruct
    public void subscribe() {
        listenerContainer.addMessageListener(this,
                new PatternTopic(SignalResumeRedisPublisher.CHANNEL_PREFIX + "*"));
        logger.info("[SignalRedisListener] Subscribed to signal:resolved:* channels");
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String payload = new String(message.getBody());
            // Payload format: signalId|nodeId|resolution|runId|sourceInstanceId
            String[] parts = payload.split("\\|", 5);
            if (parts.length < 4) {
                logger.warn("[SignalRedisListener] Malformed payload: {}", payload);
                return;
            }

            long signalId = Long.parseLong(parts[0]);
            String runId = parts[3];

            // Skip messages from this instance (local Spring Event already handles it)
            if (parts.length >= 5 && registrar != null
                    && parts[4].equals(registrar.getInstanceId())) {
                logger.debug("[SignalRedisListener] Skipping self-originated signal {}", signalId);
                return;
            }

            // Only process if this instance owns the RunContext for this run
            if (!runContextRegistry.exists(runId)) {
                logger.debug("[SignalRedisListener] Ignoring signal {} - runId {} not on this instance",
                        signalId, runId);
                return;
            }

            logger.info("[SignalRedisListener] Cross-instance signal resolution: signalId={}, runId={}",
                    signalId, runId);

            // Reload the full entity from DB (it was already resolved by the originating instance)
            SignalWaitEntity entity = signalWaitRepository.findById(signalId).orElse(null);
            if (entity == null) {
                logger.warn("[SignalRedisListener] Signal entity not found: signalId={}", signalId);
                return;
            }

            // Bind the run's organizationId on this redisMessageListenerContainer-N thread
            // before delegating. Unlike the local @TransactionalEventListener path which
            // restores RequestContextHolder via SignalResumeRequestAttributes (see
            // SignalResumeService.onSignalResolved:212), this cross-instance pub/sub
            // listener has NO request context - every downstream OrgScopedEntity persist
            // (storage rows via persistSignalResolutionOutput, step_data, etc.) would trip
            // the @PrePersist fail-loud listener post-V263. Same prod-fire shape as the
            // 2026-05-20 16:57 UTC AgentResultSubscriber incident.
            String orgId = workflowRunRepository.findByRunIdPublic(entity.getRunId())
                .map(WorkflowRunEntity::getOrganizationId)
                .orElse(null);
            Runnable resume = () -> signalResumeService.resumeAfterSignal(entity);
            if (orgId == null || orgId.isBlank()) {
                resume.run();
            } else {
                com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgId, resume);
            }

        } catch (Exception e) {
            logger.warn("[SignalRedisListener] Error processing signal message: {}",
                    e.getMessage(), e);
        }
    }
}

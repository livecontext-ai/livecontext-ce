package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.config.OrchestratorInstanceRegistrar;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Publishes signal resolution events to Redis pub/sub so that ALL orchestrator
 * instances are notified, not just the one that resolved the signal.
 *
 * <p>This complements the JVM-local Spring {@code @TransactionalEventListener}.
 * With run-level affinity, the local path handles ~95% of cases. The Redis path
 * is a safety net for cross-instance scenarios (timer expiry, failover, etc.).
 *
 * <p>Duplicate processing is prevented by the Redis SETNX dedup in
 * {@link SignalResumeService#resumeAfterSignal}.
 */
@Component
public class SignalResumeRedisPublisher {

    private static final Logger logger = LoggerFactory.getLogger(SignalResumeRedisPublisher.class);
    static final String CHANNEL_PREFIX = "signal:resolved:";

    private final StringRedisTemplate redis;

    @Nullable
    @Autowired(required = false)
    private OrchestratorInstanceRegistrar registrar;

    public SignalResumeRedisPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Publish a signal resolution event to Redis pub/sub.
     * Called AFTER the transaction commits (same point as the Spring event).
     *
     * @param entity the resolved signal entity
     */
    public void publish(SignalWaitEntity entity) {
        try {
            String sourceInstanceId = registrar != null ? registrar.getInstanceId() : "unknown";
            // Payload: signalId|nodeId|resolution|runId|sourceInstanceId
            String payload = entity.getId() + "|" + entity.getNodeId() + "|"
                    + entity.getResolution() + "|" + entity.getRunId()
                    + "|" + sourceInstanceId;
            String channel = CHANNEL_PREFIX + entity.getRunId();
            redis.convertAndSend(channel, payload);
            logger.debug("[SignalRedis] Published signal resolution: channel={}, signalId={}",
                    channel, entity.getId());
        } catch (Exception e) {
            logger.warn("[SignalRedis] Failed to publish signal resolution to Redis: signalId={}, error={}",
                    entity.getId(), e.getMessage());
            // Non-fatal: the local Spring Event path is the primary mechanism.
            // Redis pub/sub is a best-effort cross-instance notification.
        }
    }
}

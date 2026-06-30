package com.apimarketplace.agent.client.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sync-await pattern for callers that {@link AgentQueueProducer#enqueue(AgentExecutionRequestMessage)
 * enqueue} a task and need the result back on the same thread (typical for HTTP req/resp
 * paths like chat conversation).
 *
 * <p>This is distinct from the orchestrator's async pattern
 * ({@code AgentResultSubscriber} + {@code PendingAgentRegistry}) which fires a global
 * pub/sub listener and routes results via correlationId lookup. The sync-await pattern
 * here is purpose-built for one caller waiting on one result.</p>
 *
 * <h2>Race-free wait sequence</h2>
 * <pre>
 * 1. Subscribe to {@code agent:result:channel:{correlationId}} (one-shot listener)
 * 2. GETDEL {@code agent:result:{correlationId}} key - if a worker published the
 *    result between enqueue and step 1, we catch it here
 * 3. Block on the CompletableFuture for up to {@code timeout}
 * 4. Unsubscribe in {@code finally} (always cleans up the listener)
 * </pre>
 *
 * <p>Step 2 closes the race where the worker is fast enough to publish before the
 * caller subscribes. Without it, the pub/sub message would be lost (Redis pub/sub
 * has no persistence) and the caller would time out despite the result being ready.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>Timeout:</b> throws {@link AgentResultTimeoutException}. The result key will
 *       still appear in Redis with its TTL (1h, set by {@code AgentQueueWorkerService}),
 *       so a separate recovery mechanism could pick it up later if needed.</li>
 *   <li><b>Pub/sub delivery + GETDEL both fire:</b> the {@link CompletableFuture#complete}
 *       contract makes only the first one effective; the second is a no-op.</li>
 *   <li><b>Deserialization error:</b> propagated as {@link RuntimeException} wrapping
 *       the original exception.</li>
 * </ul>
 *
 * <p>Activated only when {@code scaling.agent.queue.enabled=true}; without the queue
 * there's nothing to wait for.</p>
 */
@Component
@ConditionalOnProperty(name = "scaling.agent.queue.enabled", havingValue = "true")
public class RedisResultWaiter {

    private static final Logger logger = LoggerFactory.getLogger(RedisResultWaiter.class);

    /** Must match {@code AgentQueueWorkerService.RESULT_KEY_PREFIX} in agent-service */
    public static final String RESULT_KEY_PREFIX = "agent:result:";
    /** Must match {@code AgentQueueWorkerService.RESULT_CHANNEL_PREFIX} in agent-service */
    public static final String RESULT_CHANNEL_PREFIX = "agent:result:channel:";

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;

    public RedisResultWaiter(StringRedisTemplate redisTemplate,
                              RedisMessageListenerContainer listenerContainer,
                              ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
    }

    /**
     * Wait for a result published by the worker on the channel matching
     * {@code correlationId}, then deserialize it into the requested type.
     *
     * @param correlationId  must match the {@code correlationId} used at enqueue time
     * @param responseType   target type for JSON deserialization
     * @param timeout        maximum time to wait; throws {@link AgentResultTimeoutException}
     *                       on expiry
     * @param <T>            response type
     * @return the deserialized result
     * @throws AgentResultTimeoutException if no result arrives within {@code timeout}
     * @throws RuntimeException            on Redis or deserialization failure
     */
    public <T> T await(String correlationId, Class<T> responseType, Duration timeout) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be null/blank");
        }
        if (responseType == null) {
            throw new IllegalArgumentException("responseType must not be null");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        String channel = RESULT_CHANNEL_PREFIX + correlationId;
        String key = RESULT_KEY_PREFIX + correlationId;

        CompletableFuture<String> future = new CompletableFuture<>();
        MessageListener listener = (message, pattern) -> {
            try {
                String json = new String(message.getBody(), StandardCharsets.UTF_8);
                future.complete(json);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        };

        ChannelTopic topic = new ChannelTopic(channel);
        listenerContainer.addMessageListener(listener, topic);

        try {
            // Race-closer: if the worker already published before subscribe took effect,
            // the key is present in Redis. GETDEL returns it and prevents stale reads.
            String existing = redisTemplate.opsForValue().getAndDelete(key);
            if (existing != null) {
                future.complete(existing); // first complete() wins; pub/sub callback becomes a no-op
            }

            String json;
            try {
                json = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                logger.warn("[RedisResultWaiter] Timeout waiting for result: correlationId={}, timeout={}ms",
                    correlationId, timeout.toMillis());
                throw new AgentResultTimeoutException(correlationId, timeout, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for agent result: correlationId=" + correlationId, e);
            } catch (Exception e) {
                throw new RuntimeException("Failed waiting for agent result: correlationId=" + correlationId, e);
            }

            try {
                return objectMapper.readValue(json, responseType);
            } catch (Exception e) {
                logger.error("[RedisResultWaiter] Deserialization failed: correlationId={}, responseType={}",
                    correlationId, responseType.getSimpleName(), e);
                throw new RuntimeException("Failed to deserialize agent result", e);
            }
        } finally {
            try {
                listenerContainer.removeMessageListener(listener, topic);
            } catch (Exception e) {
                logger.debug("[RedisResultWaiter] removeMessageListener failed (non-fatal): {}", e.getMessage());
            }
        }
    }

    /**
     * Thrown when {@link #await} expires without receiving a result.
     */
    public static class AgentResultTimeoutException extends RuntimeException {
        private final String correlationId;
        private final Duration timeout;

        public AgentResultTimeoutException(String correlationId, Duration timeout, Throwable cause) {
            super("Timed out after " + timeout.toMillis() + "ms waiting for agent result: correlationId=" + correlationId, cause);
            this.correlationId = correlationId;
            this.timeout = timeout;
        }

        public String getCorrelationId() { return correlationId; }
        public Duration getTimeout() { return timeout; }
    }
}

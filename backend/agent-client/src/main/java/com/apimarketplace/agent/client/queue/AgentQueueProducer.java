package com.apimarketplace.agent.client.queue;

import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pushes agent execution tasks to Redis lists for async processing by agent-service workers.
 *
 * <p>Activated only when {@code scaling.agent.queue.enabled=true}. Converts the caller's
 * {@link AgentExecutionRequestMessage} to the agent-service's {@code AgentExecutionTask} JSON
 * format and LPUSHes it to the appropriate queue ({@code agent:queue:agent},
 * {@code agent:queue:classify}, or {@code agent:queue:guardrail}).</p>
 *
 * <p>The consumer side ({@code AgentQueueWorkerService} in agent-service) uses BRPOP,
 * so LPUSH + BRPOP = FIFO ordering.</p>
 *
 * <p>Shared between orchestrator-service (workflow agent path) and conversation-service
 * (chat path). Producers correlate results via {@link RedisResultWaiter} for sync paths
 * or via the orchestrator's pub/sub subscriber for async paths.</p>
 */
@Component
@ConditionalOnProperty(name = "scaling.agent.queue.enabled", havingValue = "true")
public class AgentQueueProducer {

    private static final Logger logger = LoggerFactory.getLogger(AgentQueueProducer.class);

    /** Must match {@code AgentQueueWorkerService.QUEUE_PREFIX} in agent-service */
    public static final String QUEUE_PREFIX = "agent:queue:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AgentQueueProducer(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueue an agent execution request for async processing.
     *
     * @param message the execution request
     */
    public void enqueue(AgentExecutionRequestMessage message) {
        String queueKey = QUEUE_PREFIX + message.agentType();

        try {
            // Serialize requestPayload Map to JSON string (agent-service expects String, not Map)
            String requestPayloadJson = objectMapper.writeValueAsString(message.requestPayload());

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("runId", message.runId() != null ? message.runId() : "");
            metadata.put("nodeId", message.nodeId() != null ? message.nodeId() : "");
            metadata.put("tenantId", message.tenantId() != null ? message.tenantId() : "");
            String userRoles = resolveUserRoles(message);
            if (userRoles != null) {
                metadata.put("userRoles", userRoles);
            }

            // Build AgentExecutionTask-compatible JSON
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("correlationId", message.correlationId());
            task.put("agentType", message.agentType());
            task.put("requestPayload", requestPayloadJson);
            task.put("priority", 0);
            task.put("metadata", metadata);

            String taskJson = objectMapper.writeValueAsString(task);

            redisTemplate.opsForList().leftPush(queueKey, taskJson);

            logger.info("[AgentQueueProducer] Enqueued task: queue={}, correlationId={}, agentType={}, runId={}",
                queueKey, message.correlationId(), message.agentType(), message.runId());

        } catch (Exception e) {
            logger.error("[AgentQueueProducer] Failed to enqueue task: correlationId={}, agentType={}, error={}",
                message.correlationId(), message.agentType(), e.getMessage(), e);
            throw new RuntimeException("Failed to enqueue agent execution task", e);
        }
    }

    private String resolveUserRoles(AgentExecutionRequestMessage message) {
        if (message.userRoles() != null && !message.userRoles().isBlank()) {
            return message.userRoles().trim();
        }
        HttpHeaders forwarded = new HttpHeaders();
        OrgContextHeaderForwarder.forward(forwarded);
        String userRoles = forwarded.getFirst("X-User-Roles");
        return userRoles != null && !userRoles.isBlank() ? userRoles.trim() : null;
    }
}

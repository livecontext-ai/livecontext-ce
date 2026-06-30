package com.apimarketplace.agent.service.execution;

import com.apimarketplace.common.event.EventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Publishes agent execution activity events to Redis for real-time Fleet UI updates.
 *
 * Channel: ws:agent:activity:{agentEntityId}
 * Events are picked up by the gateway WebSocket bridge and forwarded to subscribed clients.
 *
 * Event types:
 * - execution_started: agent begins executing
 * - tool_call_started: tool call initiated
 * - tool_call_completed: tool call finished (success or failure)
 * - execution_completed: agent execution finished
 *
 * Every payload carries an optional {@code taskId}. When the execution was started
 * to process a delegated task (sub-agent path), the caller forwards the task's UUID
 * so the UI can scope agent-activity to the SPECIFIC task being worked on - not just
 * the agent. Without this, any card assigned to a running agent would flash, even
 * the cards in other columns. When the execution has no task context (direct chat,
 * webhook, standalone), taskId stays null and the payload key is omitted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentActivityPublisher {

    private static final String CHANNEL_PREFIX = "ws:agent:activity:";

    private final EventBus eventBus;
    private final ObjectMapper objectMapper;

    public void publishExecutionStarted(String agentEntityId, String executionId,
                                         String model, String source, String taskId) {
        if (agentEntityId == null) return;
        Map<String, Object> payload = basePayload("execution_started", agentEntityId, executionId, taskId);
        payload.put("model", model);
        payload.put("source", source);
        publish(agentEntityId, payload);
    }

    public void publishToolCallStarted(String agentEntityId, String executionId,
                                        String toolName, String toolCallId, String taskId) {
        if (agentEntityId == null) return;
        Map<String, Object> payload = basePayload("tool_call_started", agentEntityId, executionId, taskId);
        payload.put("toolName", toolName);
        payload.put("toolCallId", toolCallId);
        publish(agentEntityId, payload);
    }

    public void publishToolCallCompleted(String agentEntityId, String executionId,
                                          String toolName, String toolCallId,
                                          boolean success, Long durationMs, String taskId) {
        if (agentEntityId == null) return;
        Map<String, Object> payload = basePayload("tool_call_completed", agentEntityId, executionId, taskId);
        payload.put("toolName", toolName);
        payload.put("toolCallId", toolCallId);
        payload.put("success", success);
        if (durationMs != null) {
            payload.put("durationMs", durationMs);
        }
        publish(agentEntityId, payload);
    }

    public void publishExecutionCompleted(String agentEntityId, String executionId,
                                           String status, int totalTokens,
                                           int totalToolCalls, long durationMs, String taskId) {
        if (agentEntityId == null) return;
        Map<String, Object> payload = basePayload("execution_completed", agentEntityId, executionId, taskId);
        payload.put("status", status);
        payload.put("totalTokens", totalTokens);
        payload.put("totalToolCalls", totalToolCalls);
        payload.put("durationMs", durationMs);
        publish(agentEntityId, payload);
    }

    /**
     * Build the common fields shared by all activity events.
     * Capacity 12 avoids rehashing for the largest event (execution_completed: 9 entries).
     * taskId is included only when non-null; callers without task context pass null.
     */
    private Map<String, Object> basePayload(String event, String agentEntityId, String executionId, String taskId) {
        Map<String, Object> payload = new HashMap<>(12);
        payload.put("event", event);
        payload.put("executionId", executionId);
        payload.put("agentEntityId", agentEntityId);
        payload.put("timestamp", Instant.now().toString());
        if (taskId != null) {
            payload.put("taskId", taskId);
        }
        return payload;
    }

    private void publish(String agentEntityId, Map<String, Object> payload) {
        try {
            String channel = CHANNEL_PREFIX + agentEntityId;
            String json = objectMapper.writeValueAsString(payload);
            eventBus.publish(channel, json);
            log.debug("[FLEET_ACTIVITY] Published {} to {}", payload.get("event"), channel);
        } catch (Exception e) {
            log.warn("[FLEET_ACTIVITY] Failed to publish event (non-critical): {}", e.getMessage());
        }
    }
}

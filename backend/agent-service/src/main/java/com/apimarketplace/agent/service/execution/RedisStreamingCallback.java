package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.tools.common.ToolMediaMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * StreamingCallback implementation that publishes agent execution events to Redis pub/sub.
 *
 * Events are published to the same channels and with the same JSON format as
 * WorkflowRedisPublisher in orchestrator-service, so the gateway WebSocket bridge
 * picks them up and delivers them to the frontend SSE stream.
 *
 * Channel: ws:workflow:run:{runId}
 * Envelope: { v: 1, type: "...", id: "...", ts: ..., payload: { ... } }
 *
 * This bean is a prototype-like factory - call forExecution() to get a per-execution callback.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamingCallback {

    private static final String CHANNEL_PREFIX = "ws:workflow:run:";
    private static final String CANCEL_KEY_PREFIX = "workflow:cancel:";
    private static final int PROTOCOL_VERSION = 1;

    private final StringRedisTemplate redisTemplate;
    private final com.apimarketplace.common.event.EventBus eventBus;
    private final ObjectMapper objectMapper;

    /**
     * Create a callback for a specific agent execution.
     *
     * @param runId        workflow run ID (Redis channel key)
     * @param nodeId       agent node ID
     * @param itemIndex    item index (for split contexts)
     * @param iteration    loop iteration (nullable)
     */
    public StreamingCallback forExecution(String runId, String nodeId,
                                           Integer itemIndex, Integer iteration) {
        return new ExecutionCallback(runId, nodeId, itemIndex, iteration);
    }

    /**
     * Per-execution streaming callback that publishes to Redis.
     */
    private class ExecutionCallback implements StreamingCallback {

        private final String runId;
        private final String nodeId;
        private final int itemIndex;
        private final Integer iteration;
        private final String channel;

        ExecutionCallback(String runId, String nodeId, Integer itemIndex, Integer iteration) {
            this.runId = runId;
            this.nodeId = nodeId;
            this.itemIndex = itemIndex != null ? itemIndex : 0;
            this.iteration = iteration;
            this.channel = CHANNEL_PREFIX + runId;
        }

        @Override
        public void onChunk(String content) {
            // Content chunks are primarily for conversation display
            // No-op for workflow channel - handled by conversation events
        }

        @Override
        public void onThinking(String thinking) {
            // Thinking is primarily for conversation display
            // No-op for workflow channel
        }

        @Override
        public void onToolCall(ToolCall toolCall) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "AgentToolCallEvent");
            payload.put("runId", runId);
            payload.put("nodeId", nodeId);
            payload.put("toolName", toolCall.toolName());
            payload.put("toolCallId", toolCall.id());
            payload.put("phase", "CALLING");
            payload.put("itemIndex", itemIndex);
            if (iteration != null) {
                payload.put("iteration", iteration);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("arguments", toolCall.arguments());
            if (toolCall.index() != null) {
                data.put("toolIndex", toolCall.index());
            }
            payload.put("payload", data);
            payload.put("timestamp", System.currentTimeMillis());

            publishEvent("AgentToolCallEvent", payload);
        }

        @Override
        public void onToolResult(ToolResult result) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "AgentToolCallEvent");
            payload.put("runId", runId);
            payload.put("nodeId", nodeId);
            payload.put("toolName", result.toolCall() != null ? result.toolCall().toolName() : null);
            payload.put("toolCallId", result.toolCall() != null ? result.toolCall().id() : null);
            payload.put("phase", result.success() ? "COMPLETED" : "FAILED");
            payload.put("itemIndex", itemIndex);
            if (iteration != null) {
                payload.put("iteration", iteration);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", result.success());
            if (result.durationMs() != null) {
                data.put("durationMs", result.durationMs());
            }
            if (result.content() != null) {
                data.put("contentPreview", result.content().length() > 200
                    ? result.content().substring(0, 200) + "..."
                    : result.content());
                data.put("contentLength", result.content().length());
            }
            if (result.error() != null) {
                data.put("error", result.error());
            }
            if (result.metadata() != null) {
                // Strip any heavy vision-media bytes: the frontend has the file URL/card and
                // never needs the base64, which would bloat the Redis message.
                data.put("metadata", ToolMediaMetadata.withoutHeavyMedia(result.metadata()));
            }
            payload.put("payload", data);
            payload.put("timestamp", System.currentTimeMillis());

            publishEvent("AgentToolCallEvent", payload);
        }

        @Override
        public void onComplete(CompletionResponse response) {
            // Completion is handled by orchestrator after the HTTP response returns
        }

        @Override
        public void onError(String error) {
            // Errors are handled by orchestrator
        }

        // Stop control: cached locally once detected to avoid repeated Redis calls
        private volatile boolean stopped = false;

        @Override
        public boolean shouldStop() {
            if (stopped) return true;

            // Check Redis cancel key (set by orchestrator on cancel/stop)
            try {
                Boolean exists = redisTemplate.hasKey(CANCEL_KEY_PREFIX + runId);
                if (Boolean.TRUE.equals(exists)) {
                    stopped = true;
                    log.info("[WORKFLOW_STREAM] Cancel signal detected for runId={}, nodeId={}", runId, nodeId);
                    return true;
                }
            } catch (Exception e) {
                log.debug("[WORKFLOW_STREAM] Error checking cancel key for runId={}: {}", runId, e.getMessage());
            }

            return false;
        }

        /**
         * Publish a Redis event with the standard envelope format.
         */
        private void publishEvent(String eventType, Map<String, Object> payload) {
            try {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("v", PROTOCOL_VERSION);
                envelope.put("type", eventType);
                envelope.put("id", UUID.randomUUID().toString());
                envelope.put("ts", System.currentTimeMillis());
                envelope.put("payload", payload);

                String json = objectMapper.writeValueAsString(envelope);
                eventBus.publish(channel, json);

                log.debug("Published {} to Redis channel {}", eventType, channel);

            } catch (Exception e) {
                log.warn("Failed to publish event to Redis (non-critical): {}", e.getMessage());
            }
        }
    }
}

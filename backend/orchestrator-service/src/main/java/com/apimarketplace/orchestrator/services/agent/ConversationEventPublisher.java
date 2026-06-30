package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.conversation.client.StreamRedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes conversation streaming events to Redis for live WebSocket delivery.
 * <p>
 * When a workflow agent executes, this publisher sends events to the
 * {@code ws:conversation:{conversationId}} Redis channel - the same channel that
 * conversation-service's {@code StreamPubSubService} uses for chat agents.
 * <p>
 * Stream lifecycle is centralized in conversation-service:
 * <ul>
 *   <li>{@code registerStream()} - creates metadata + conv index via ConversationClient</li>
 *   <li>{@code finalizeStream()} - marks terminal state via ConversationClient</li>
 * </ul>
 * <p>
 * Content/tool buffering writes directly to Redis using shared key schema
 * from {@link StreamRedisKeys} for snapshot replay performance.
 */
@Component
public class ConversationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ConversationEventPublisher.class);
    private static final String WS_CHANNEL_PREFIX = "ws:conversation:";
    private static final String STREAM_CHANNEL_PREFIX = "stream:events:";

    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final ConversationClient conversationClient;

    public ConversationEventPublisher(EventBus eventBus, ObjectMapper objectMapper,
                                       StringRedisTemplate redisTemplate,
                                       ConversationClient conversationClient) {
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.conversationClient = conversationClient;
    }

    /**
     * Publish a stream_started event (matches StreamEvent.StreamStarted).
     * Also registers the stream via conversation-service for snapshot replay.
     */
    public void publishStreamStarted(String conversationId, String streamId, String model) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("conversationId", conversationId);
        event.put("model", model != null ? model : "unknown");
        event.put("timestamp", Instant.now().toString());
        publish(conversationId, event, "stream_started");

        // Register stream in conversation-service (creates metadata + conv index)
        registerStream(streamId, conversationId, model);
    }

    /**
     * Publish a content chunk event (matches StreamEvent.ContentChunk).
     */
    public void publishContent(String conversationId, String streamId, String content) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("content", content);
        event.put("timestamp", Instant.now().toString());
        publish(conversationId, event, "content");

        // Buffer content for snapshot replay (direct Redis for performance)
        appendContent(streamId, content);
    }

    /**
     * Publish a tool_call event (matches StreamEvent.ToolCall).
     */
    public void publishToolCall(String conversationId, String streamId,
                                String toolName, String callId, Map<String, Object> arguments) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("toolName", toolName);
        event.put("toolId", callId);
        event.put("arguments", arguments != null ? arguments : Map.of());
        event.put("timestamp", Instant.now().toString());
        publish(conversationId, event, "tool_call");

        // Buffer tool event for snapshot replay
        appendToolEvent(streamId, event);
    }

    /**
     * Publish a tool_result event (matches StreamEvent.ToolResult).
     */
    public void publishToolResult(String conversationId, String streamId,
                                  String callId, String toolName,
                                  boolean success, Long durationMs, String content) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("toolId", callId);
        event.put("toolName", toolName);
        event.put("success", success);
        if (durationMs != null) {
            event.put("durationMs", durationMs);
        }
        // resultId is null for workflow agent tool results (no DB storage from orchestrator)
        event.put("resultId", null);
        event.put("error", success ? null : content);
        event.put("timestamp", Instant.now().toString());
        publish(conversationId, event, "tool_result");

        // Buffer tool event for snapshot replay
        appendToolEvent(streamId, event);
    }

    /**
     * Publish a completed event (matches StreamEvent.StreamCompleted).
     */
    public void publishCompleted(String conversationId, String streamId, String fullContent) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("fullContent", fullContent != null ? fullContent : "");
        event.put("totalTokens", fullContent != null ? fullContent.length() / 4 : 0);
        event.put("timestamp", Instant.now().toString());
        publish(conversationId, event, "completed");

        // Finalize stream via conversation-service
        finalizeStream(streamId, "COMPLETED");
    }

    /**
     * Publish a thinking chunk event (for thinking/reasoning models).
     */
    public void publishThinking(String conversationId, String streamId, String thinking) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("thinking", thinking);
        event.put("timestamp", Instant.now().toString());
        publish(conversationId, event, "thinking");
    }

    // ==================== Sub-agent forwarding to parent conversation ====================

    public void publishSubAgentContent(String parentConversationId,
                                        String subAgentName, String subAgentAvatarUrl, String subAgentId,
                                        String content) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("subAgent", buildSubAgentMeta(subAgentName, subAgentAvatarUrl, subAgentId));
        event.put("content", content);
        event.put("timestamp", Instant.now().toString());
        publish(parentConversationId, event, "sub_agent_content");
    }

    public void publishSubAgentThinking(String parentConversationId,
                                         String subAgentName, String subAgentAvatarUrl, String subAgentId,
                                         String thinking) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("subAgent", buildSubAgentMeta(subAgentName, subAgentAvatarUrl, subAgentId));
        event.put("thinking", thinking);
        event.put("timestamp", Instant.now().toString());
        publish(parentConversationId, event, "sub_agent_thinking");
    }

    public void publishSubAgentStarted(String parentConversationId,
                                        String subAgentName, String subAgentAvatarUrl, String subAgentId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("subAgent", buildSubAgentMeta(subAgentName, subAgentAvatarUrl, subAgentId));
        event.put("timestamp", Instant.now().toString());
        publish(parentConversationId, event, "sub_agent_started");
    }

    public void publishSubAgentToolCall(String parentConversationId,
                                         String subAgentName, String subAgentAvatarUrl, String subAgentId,
                                         String toolName, String callId, Map<String, Object> arguments) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("subAgent", buildSubAgentMeta(subAgentName, subAgentAvatarUrl, subAgentId));
        event.put("toolName", toolName);
        event.put("toolId", callId);
        event.put("arguments", arguments != null ? arguments : Map.of());
        event.put("timestamp", Instant.now().toString());
        publish(parentConversationId, event, "sub_agent_tool_call");
    }

    public void publishSubAgentToolResult(String parentConversationId,
                                           String subAgentName, String subAgentAvatarUrl, String subAgentId,
                                           String callId, String toolName,
                                           boolean success, Long durationMs) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("subAgent", buildSubAgentMeta(subAgentName, subAgentAvatarUrl, subAgentId));
        event.put("toolId", callId);
        event.put("toolName", toolName);
        event.put("success", success);
        if (durationMs != null) {
            event.put("durationMs", durationMs);
        }
        event.put("timestamp", Instant.now().toString());
        publish(parentConversationId, event, "sub_agent_tool_result");
    }

    public void publishSubAgentCompleted(String parentConversationId,
                                          String subAgentName, String subAgentAvatarUrl, String subAgentId,
                                          boolean success) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("subAgent", buildSubAgentMeta(subAgentName, subAgentAvatarUrl, subAgentId));
        event.put("success", success);
        event.put("timestamp", Instant.now().toString());
        publish(parentConversationId, event, "sub_agent_completed");
    }

    private Map<String, Object> buildSubAgentMeta(String name, String avatarUrl, String agentId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        if (avatarUrl != null) meta.put("avatarUrl", avatarUrl);
        if (agentId != null) meta.put("agentId", agentId);
        return meta;
    }

    /**
     * Publish an error event (matches StreamEvent.StreamError).
     */
    public void publishError(String conversationId, String streamId, String error) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("error", error);
        event.put("errorCode", "AGENT_ERROR");
        event.put("retryable", false);
        event.put("timestamp", Instant.now().toString());
        publish(conversationId, event, "error");

        finalizeStream(streamId, "ERROR");
    }

    /**
     * Publish an early visualization_ready event so the frontend side panel opens
     * instantly, before the blocking fire() call completes.
     * Dual-publishes to SSE + WS channels. Fire-and-forget: never throws.
     */
    public void publishVisualizationReady(String streamId, String conversationId,
                                           String type, String id, String title, String runId) {
        if (conversationId == null || conversationId.isBlank()) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("visualizationType", type);
            payload.put("visualizationId", id);
            if (title != null) payload.put("visualizationTitle", title);
            if (runId != null) payload.put("runId", runId);
            payload.put("timestamp", Instant.now().toString());
            String json = objectMapper.writeValueAsString(payload);

            if (streamId != null && !streamId.isBlank()) {
                eventBus.publish(STREAM_CHANNEL_PREFIX + streamId, json);
            }
            eventBus.publish(WS_CHANNEL_PREFIX + conversationId, json);
        } catch (Exception e) {
            log.warn("publishVisualizationReady failed (type={}, id={}): {}", type, id, e.getMessage());
        }
    }

    private void publish(String conversationId, Map<String, Object> event, String eventType) {
        if (conversationId == null) {
            log.debug("[ConversationEventPublisher] No conversationId, skipping {} event", eventType);
            return;
        }

        // Include type in the payload for consistent event routing
        event.put("type", eventType);

        String channel = WS_CHANNEL_PREFIX + conversationId;
        try {
            String json = objectMapper.writeValueAsString(event);
            eventBus.publish(channel, json);
            log.info("[ConversationEventPublisher] Published {} to {} ({}b)", eventType, channel, json.length());
        } catch (JsonProcessingException e) {
            log.warn("[ConversationEventPublisher] Failed to serialize {} event: {}", eventType, e.getMessage());
        } catch (Exception e) {
            log.warn("[ConversationEventPublisher] Failed to publish {} to {}: {}", eventType, channel, e.getMessage());
        }
    }

    // ==================== Centralized stream lifecycle (via conversation-service) ====================

    private void registerStream(String streamId, String conversationId, String model) {
        if (conversationId == null || streamId == null) return;
        try {
            conversationClient.registerStream(streamId, conversationId, model, "workflow");
        } catch (Exception e) {
            log.warn("[ConversationEventPublisher] Failed to register stream: streamId={}, conv={}, error={}", streamId, conversationId, e.getMessage());
        }
    }

    private void finalizeStream(String streamId, String terminalState) {
        if (streamId == null) return;
        try {
            conversationClient.finalizeStream(streamId, terminalState);
        } catch (Exception e) {
            log.warn("[ConversationEventPublisher] Failed to finalize stream {}: {}", streamId, e.getMessage());
        }
    }

    // ==================== Direct Redis buffering (shared key schema) ====================

    private void appendContent(String streamId, String content) {
        if (streamId == null || content == null) return;
        try {
            String key = StreamRedisKeys.contentKey(streamId);
            redisTemplate.opsForList().rightPush(key, content);
            redisTemplate.opsForList().trim(key, -StreamRedisKeys.MAX_CONTENT_CHUNKS, -1);
            redisTemplate.expire(key, StreamRedisKeys.STREAM_TTL);
        } catch (Exception e) {
            log.warn("[ConversationEventPublisher] Failed to append content: {}", e.getMessage());
        }
    }

    private void appendToolEvent(String streamId, Map<String, Object> event) {
        if (streamId == null) return;
        try {
            String key = StreamRedisKeys.toolsKey(streamId);
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.opsForList().trim(key, -StreamRedisKeys.MAX_TOOL_EVENTS, -1);
            redisTemplate.expire(key, StreamRedisKeys.STREAM_TTL);
        } catch (Exception e) {
            log.warn("[ConversationEventPublisher] Failed to append tool event: {}", e.getMessage());
        }
    }
}

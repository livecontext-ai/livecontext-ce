package com.apimarketplace.monolith.chat;

import com.apimarketplace.conversation.domain.stream.StreamEvent.ServiceApprovalInfo;
import com.apimarketplace.conversation.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.*;

/**
 * StreamingOutput implementation for CE monolith mode.
 * Publishes streaming events to Redis, matching the cloud WebSocket channel shape.
 *
 * The MonolithWsHandler has one Redis pattern bridge for ws:* channels. Keeping a
 * single transport source prevents duplicate delivery in CE.
 */
public class MonolithStreamingOutput implements StreamingOutput {

    private static final Logger log = LoggerFactory.getLogger(MonolithStreamingOutput.class);
    private static final String WS_CHANNEL_PREFIX = "ws:conversation:";

    private final String streamId;
    private String conversationId;
    private final String model;
    private final String provider;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private volatile boolean active = true;
    private volatile boolean processing = true;
    private final StringBuilder contentBuffer = new StringBuilder();

    public MonolithStreamingOutput(String streamId, String conversationId, String model,
                                   String provider, StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper) {
        this.streamId = streamId;
        this.conversationId = conversationId;
        this.model = model;
        this.provider = provider;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override public boolean isStreamProcessing() { return processing; }
    @Override public boolean isActive() { return active; }
    @Override public String getCurrentStreamId() { return streamId; }
    @Override public String getCurrentContent() { return contentBuffer.toString(); }

    @Override
    public void updateConversationId(String newConversationId) {
        this.conversationId = newConversationId;
    }

    @Override
    public void sendContent(String content, String model, String provider, String userId, String conversationId) {
        contentBuffer.append(content);
        publish(Map.of("streamId", streamId, "content", content, "timestamp", now()), "content");
    }

    @Override
    public void sendThinking(String thinking, String model, String provider, String userId, String conversationId) {
        publish(Map.of("streamId", streamId, "thinking", thinking, "timestamp", now()), "thinking");
    }

    @Override
    public void sendThinkingSection(String title, String content) {
        publish(Map.of("streamId", streamId, "title", title, "content", content, "timestamp", now()), "thinking_section");
    }

    @Override public void sendStreamId(String streamId) {
        publish(Map.of("streamId", streamId, "timestamp", now()), "stream_id");
    }

    @Override public void sendConversationIdReady(String conversationId) {
        publish(Map.of("streamId", streamId, "conversationId", conversationId, "timestamp", now()), "conversation_id_ready");
    }

    @Override public void sendConversationCreated(String conversationId, String title, boolean isTemporary) {
        publish(Map.of("streamId", streamId, "conversationId", conversationId, "title", title, "isTemporary", isTemporary, "timestamp", now()), "conversation_created");
    }

    @Override public void sendTitleUpdated(String conversationId, String title, boolean isTemporary) {
        publish(Map.of("streamId", streamId, "conversationId", conversationId, "title", title, "isTemporary", isTemporary, "timestamp", now()), "title_updated");
    }

    @Override public void sendUserMessage(String conversationId, Map<String, Object> userMessage) {
        var event = new LinkedHashMap<String, Object>();
        event.put("streamId", streamId);
        event.put("conversationId", conversationId);
        if (userMessage != null) {
            event.putAll(userMessage);
        }
        event.put("timestamp", now());
        publish(event, "user_message");
    }

    @Override
    public void sendToolCall(String toolName, String toolCallId, String arguments,
                             String thinkingMessage, String model, String provider, String userId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("toolName", toolName);
        event.put("toolCallId", toolCallId);
        event.put("arguments", arguments);
        if (thinkingMessage != null) event.put("thinkingMessage", thinkingMessage);
        event.put("timestamp", now());
        publish(event, "tool_call");
    }

    @Override
    public void sendToolResult(String toolName, String toolId, boolean success, Long durationMs,
                               String resultId, String error,
                               String visualizationType, String visualizationId, String visualizationTitle,
                               String visualizationRunId, Integer visualizationRunIndex,
                               String iconSlug, String displayToolName, String label,
                               Map<String, Object> serviceApproval,
                               String model, String provider, String userId, String conversationId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("streamId", streamId);
        event.put("toolName", toolName);
        event.put("toolId", toolId);
        event.put("success", success);
        if (durationMs != null) event.put("durationMs", durationMs);
        if (resultId != null) event.put("resultId", resultId);
        if (error != null) event.put("error", error);
        if (visualizationType != null) event.put("visualizationType", visualizationType);
        if (visualizationId != null) event.put("visualizationId", visualizationId);
        if (visualizationTitle != null) event.put("visualizationTitle", visualizationTitle);
        if (visualizationRunId != null) event.put("visualizationRunId", visualizationRunId);
        if (visualizationRunIndex != null) event.put("visualizationRunIndex", visualizationRunIndex);
        if (iconSlug != null) event.put("iconSlug", iconSlug);
        if (displayToolName != null) event.put("displayToolName", displayToolName);
        if (label != null) event.put("label", label);
        if (serviceApproval != null) event.put("serviceApproval", serviceApproval);
        event.put("timestamp", now());
        publish(event, "tool_result");
    }

    @Override public void sendCredentialRequired(String credentialType, String toolName, String toolId) {
        publish(Map.of("streamId", streamId, "credentialType", credentialType, "toolName", toolName, "toolId", toolId, "timestamp", now()), "credential_required");
    }

    @Override public void sendServiceApprovalRequired(List<ServiceApprovalInfo> services, String reason, boolean needsAttention) {
        publish(Map.of("streamId", streamId, "services", services, "reason", reason, "needsAttention", needsAttention, "timestamp", now()), "service_approval_required");
    }

    @Override public void sendAwaitingApproval(List<ServiceApprovalInfo> services) {
        processing = false;
        publish(Map.of("streamId", streamId, "services", services, "timestamp", now()), "awaiting_approval");
    }

    @Override public void sendPendingActionCancelled(String reason, String message) {
        publish(Map.of("streamId", streamId, "reason", reason, "message", message, "timestamp", now()), "pending_action_cancelled");
    }

    @Override
    public void sendDone(String fullResponse, String model, String provider, String userId, String conversationId) {
        processing = false;
        active = false;
        var event = new LinkedHashMap<String, Object>();
        event.put("streamId", streamId);
        event.put("conversationId", conversationId != null ? conversationId : this.conversationId);
        event.put("model", model != null ? model : "");
        if (fullResponse != null) event.put("fullContent", fullResponse);
        event.put("totalTokens", 0);
        event.put("timestamp", now());
        publish(event, "done");
    }

    @Override
    public void sendDoneSimple(String model, String provider, String userId, String conversationId, String streamId) {
        sendDone(null, model, provider, userId, conversationId);
    }

    @Override public void sendError(String errorMessage) {
        processing = false;
        publish(Map.of("streamId", streamId, "error", errorMessage != null ? errorMessage : "Unknown error",
                "code", "STREAM_ERROR", "isFatal", true, "timestamp", now()), "error");
    }

    @Override public void sendError(String errorMessage, String model, String provider, String userId, String conversationId) {
        sendError(errorMessage);
    }

    @Override public void stop() {
        processing = false;
        active = false;
        publish(Map.of("streamId", streamId, "timestamp", now()), "stopped");
    }

    @Override public void handleNaturalEnd(String fullContent, String conversationId) {
        sendDone(fullContent, model, provider, null, conversationId);
    }

    @Override public void handleStreamEnd(String errorMessage, String conversationId) {
        if (errorMessage != null) {
            sendError(errorMessage);
        } else {
            sendDone(contentBuffer.toString(), model, provider, null, conversationId);
        }
    }

    private void publish(Map<String, Object> event, String type) {
        try {
            var fullEvent = new LinkedHashMap<>(event);
            fullEvent.putIfAbsent("type", type);
            String json = objectMapper.writeValueAsString(fullEvent);
            String channel = WS_CHANNEL_PREFIX + conversationId;
            // Publish to Redis (same as cloud RedisStreamingOutput) - MonolithWsHandler picks it up
            redisTemplate.convertAndSend(channel, json);
        } catch (Exception e) {
            log.warn("[MonolithStream] Failed to publish {} for stream {}: {}", type, streamId, e.getMessage());
        }
    }

    private static String now() { return Instant.now().toString(); }
}

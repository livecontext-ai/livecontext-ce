package com.apimarketplace.monolith.ws;

import com.apimarketplace.agent.service.execution.AgentActivitySnapshotService;
import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamState;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.apimarketplace.orchestrator.controllers.internal.InternalAccessController;
import com.apimarketplace.orchestrator.controllers.internal.InternalSbsController;
import com.apimarketplace.orchestrator.controllers.internal.InternalSignalController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles WebSocket "action" messages in monolith mode by calling
 * internal services directly (same JVM - no HTTP round-trip needed).
 *
 * Supports:
 * - signal.resolve → UnifiedSignalService
 * - sbs.execute → InternalSbsController
 * - Snapshot triggers → InternalAccessController
 */
@Component
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class MonolithWsActionHandler {

    private static final Logger log = LoggerFactory.getLogger(MonolithWsActionHandler.class);

    private static final String WS_CONVERSATION_PREFIX = "ws:conversation:";

    private final InternalSignalController signalController;
    private final InternalSbsController sbsController;
    private final InternalAccessController accessController;
    private final ObjectMapper objectMapper;
    private final StreamStateService streamStateService;
    private final StringRedisTemplate redisTemplate;
    private final AgentActivitySnapshotService agentActivitySnapshotService;

    public MonolithWsActionHandler(InternalSignalController signalController,
                                   InternalSbsController sbsController,
                                   InternalAccessController accessController,
                                   ObjectMapper objectMapper,
                                   StreamStateService streamStateService,
                                   StringRedisTemplate redisTemplate,
                                   AgentActivitySnapshotService agentActivitySnapshotService) {
        this.signalController = signalController;
        this.sbsController = sbsController;
        this.accessController = accessController;
        this.objectMapper = objectMapper;
        this.streamStateService = streamStateService;
        this.redisTemplate = redisTemplate;
        this.agentActivitySnapshotService = agentActivitySnapshotService;
    }

    /**
     * Handle an action message from a WebSocket client.
     * Runs in a virtual thread to avoid blocking the WS handler.
     *
     * @param organizationId the session's active workspace org (resolved and
     *        membership-validated at handshake time) - forwarded to the internal
     *        controllers exactly like the gateway WsActionHandler forwards
     *        X-Organization-ID, so their run-scope guards see the caller's
     *        workspace instead of a null scope.
     */
    @SuppressWarnings("unchecked")
    public void handle(String userId, String organizationId, WebSocketSession session,
                       String messageId, String action, Object data) {
        Thread.ofVirtual().name("ws-action").start(() -> {
            try {
                Map<String, Object> dataMap = data instanceof Map ? (Map<String, Object>) data : Map.of();

                Object result = switch (action) {
                    case "signal.resolve" -> handleSignalResolve(userId, organizationId, dataMap);
                    case "sbs.execute" -> handleSbsExecute(userId, organizationId, dataMap);
                    default -> {
                        log.warn("[MonolithWS] Unknown action: {}", action);
                        yield Map.of("error", "Unknown action: " + action);
                    }
                };

                var ack = new MonolithWsHandler.Envelope(1, "action.ack",
                        UUID.randomUUID().toString(), messageId, null, null,
                        System.currentTimeMillis(),
                        Map.of("action", action, "result", result));
                sendJson(session, ack);

            } catch (Exception e) {
                log.error("[MonolithWS] Action '{}' failed: {}", action, e.getMessage(), e);
                try {
                    var error = new MonolithWsHandler.Envelope(1, "action.error",
                            UUID.randomUUID().toString(), messageId, null, null,
                            System.currentTimeMillis(),
                            Map.of("action", action, "error", e.getMessage()));
                    sendJson(session, error);
                } catch (IOException ignored) {}
            }
        });
    }

    /**
     * Trigger a state snapshot re-publish for a channel.
     * Called when a client subscribes with requestSnapshot=true.
     */
    public void triggerSnapshot(String channel, String userId) {
        Thread.ofVirtual().name("ws-snapshot").start(() -> {
            try {
                if (channel.startsWith("workflow:run:")) {
                    String runIdPart = channel.substring("workflow:run:".length());
                    String runId = runIdPart.contains(":") ? runIdPart.substring(0, runIdPart.indexOf(':')) : runIdPart;
                    accessController.triggerSnapshot(runId);
                    log.debug("[MonolithWS] Snapshot triggered for workflow run: {}", runId);
                }
                // Conversation snapshots - replay the buffered stream state so a client that
                // subscribed AFTER the live events were published (the send-from-Home → navigate
                // timing gap) still renders the reply instead of an empty conversation. Mirrors the
                // cloud InternalAccessController#triggerSnapshot, but publishes directly to the WS
                // Redis channel since the monolith's StreamPubSubService is a no-op.
                if (channel.startsWith("conversation:")) {
                    publishConversationSnapshot(channel.substring("conversation:".length()));
                }
                // agent:activity:{agentId} - re-publish execution_started for any RUNNING execution
                // so a client that subscribed mid-run (esp. a bridge/CLI agent, whose only activity
                // events are started/completed) learns the agent is busy. Mirrors the cloud gateway's
                // WsSnapshotTrigger agent:activity branch via the SAME shared service.
                if (channel.startsWith("agent:activity:")) {
                    String agentId = channel.substring("agent:activity:".length());
                    int count = agentActivitySnapshotService.publishRunningSnapshot(UUID.fromString(agentId));
                    log.debug("[MonolithWS] Activity snapshot for agent {} re-published {} running execution(s)",
                            agentId, count);
                }
            } catch (Exception e) {
                log.warn("[MonolithWS] Snapshot trigger failed for channel {}: {}", channel, e.getMessage());
            }
        });
    }

    /**
     * Replay a conversation stream's buffered state to its WS channel so a client that subscribed
     * AFTER the live events were published (the send-from-Home → navigate timing gap) still renders
     * the reply instead of an empty conversation. Reads the (real) {@link StreamStateService} - whose
     * content/tool/index keys are already populated by the shared ConversationRedisStreamingCallback -
     * and re-publishes to {@code ws:conversation:{id}} in the same event shape MonolithStreamingOutput
     * uses for the live stream. Best-effort + idempotent: a terminal {@code done} carries
     * {@code fullContent} which the frontend replaces with, so a duplicate replay is harmless.
     */
    private void publishConversationSnapshot(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        try {
            StreamMetadata meta = streamStateService.getByConversationId(conversationId)
                    .block(Duration.ofSeconds(2));
            if (meta == null) {
                log.debug("[MonolithWS] No stream state for conversation snapshot: {}", conversationId);
                return;
            }
            String streamId = meta.streamId();
            StreamState state = meta.state();
            String channel = WS_CONVERSATION_PREFIX + conversationId;

            // Tool events are stored already in WS event shape by the callback - replay verbatim first.
            List<String> toolEvents = streamStateService.getToolEvents(streamId)
                    .collectList().block(Duration.ofSeconds(2));
            if (toolEvents != null) {
                for (String toolJson : toolEvents) {
                    if (toolJson != null && !toolJson.isBlank()) {
                        redisTemplate.convertAndSend(channel, toolJson);
                    }
                }
            }

            String content = streamStateService.getFullContent(streamId).block(Duration.ofSeconds(2));
            if (content == null) content = "";
            boolean terminal = state != StreamState.STREAMING && state != StreamState.CREATED;

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("streamId", streamId);
            event.put("conversationId", conversationId);
            if (terminal) {
                event.put("model", meta.model() != null ? meta.model() : "");
                event.put("fullContent", content);
                event.put("totalTokens", 0);
                event.put("type", "done");
            } else {
                event.put("content", content);
                // Snapshot content is the FULL accumulated buffer, not an incremental chunk; mark it as a
                // replay so the frontend REPLACES the streamed content instead of appending (which would
                // duplicate text on every reconnect/snapshot).
                event.put("replay", true);
                event.put("type", "content");
            }
            event.put("timestamp", Instant.now().toString());
            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(event));
            log.debug("[MonolithWS] Replayed conversation snapshot: conv={}, stream={}, state={}, contentLen={}",
                    conversationId, streamId, state, content.length());
        } catch (Exception e) {
            log.warn("[MonolithWS] Conversation snapshot failed for {}: {}", conversationId, e.getMessage());
        }
    }

    private Map<String, Object> handleSignalResolve(String userId, String organizationId, Map<String, Object> data) {
        Object signalIdObj = data.get("signalId");
        if (signalIdObj == null) {
            throw new IllegalArgumentException("Missing signalId");
        }
        Long signalId = Long.valueOf(String.valueOf(signalIdObj));

        var response = signalController.resolveSignal(signalId, userId, organizationId, data);
        return response.getBody() != null ? response.getBody() : Map.of("status", "ok");
    }

    private Map<String, Object> handleSbsExecute(String userId, String organizationId, Map<String, Object> data) {
        String runId = String.valueOf(data.get("runId"));
        String nodeId = String.valueOf(data.get("nodeId"));

        if ("null".equals(runId) || "null".equals(nodeId)) {
            throw new IllegalArgumentException("Missing runId or nodeId");
        }

        var response = sbsController.executeNode(runId, nodeId, userId, organizationId, data);
        return response.getBody() != null ? response.getBody() : Map.of("status", "ok");
    }

    private void sendJson(WebSocketSession session, Object obj) throws IOException {
        String json = objectMapper.writeValueAsString(obj);
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }
}

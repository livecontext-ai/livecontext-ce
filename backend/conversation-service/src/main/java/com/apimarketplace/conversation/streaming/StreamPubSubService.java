package com.apimarketplace.conversation.streaming;

import com.apimarketplace.conversation.domain.stream.StreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Redis Pub/Sub service for real-time stream event distribution.
 *
 * This is the SINGLE mechanism for distributing stream events.
 * All clients (initial and reconnecting) subscribe to the same Redis channel.
 *
 * Architecture:
 * - Agent loop calls publish() → Redis Pub/Sub channel
 * - All streaming subscribers call subscribe() → receive events in real-time
 * - Works across multiple backend instances (scalable)
 * - No in-memory state needed (stateless)
 */
@Slf4j
@Service
public class StreamPubSubService {

    private static final String CHANNEL_PREFIX = "stream:events:";
    private static final String WS_CHANNEL_PREFIX = "ws:conversation:";

    /**
     * In-memory cache: streamId → conversationId.
     * Populated on StreamStarted/TitleUpdated events, used for all subsequent events
     * to avoid blocking Redis lookups on the reactive thread.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> streamConversationCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String EVENT_TYPE_CONTENT = "content";
    private static final String EVENT_TYPE_THINKING = "thinking";
    private static final String EVENT_TYPE_DONE = "done";
    private static final String EVENT_TYPE_ERROR = "error";
    private static final String EVENT_TYPE_STOPPED = "stopped";
    private static final String EVENT_TYPE_HEARTBEAT = "heartbeat";
    private static final String EVENT_TYPE_TOOL_CALL = "tool_call";
    private static final String EVENT_TYPE_TOOL_RESULT = "tool_result";
    private static final String EVENT_TYPE_TITLE = "title_updated";
    private static final String EVENT_TYPE_STARTED = "stream_started";
    private static final String EVENT_TYPE_COMPACTION_DONE = "compaction_done";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ReactiveRedisMessageListenerContainer messageListenerContainer;
    private final ObjectMapper objectMapper;
    private final StreamStateService stateService;

    public StreamPubSubService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            ReactiveRedisMessageListenerContainer messageListenerContainer,
            ObjectMapper objectMapper,
            StreamStateService stateService) {
        this.redisTemplate = redisTemplate;
        this.messageListenerContainer = messageListenerContainer;
        this.objectMapper = objectMapper;
        this.stateService = stateService;
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLISH - Called by agent loop to emit events
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Publishes a StreamEvent to the Redis channel for a stream.
     * All subscribers will receive this event in real-time.
     */
    public Mono<Long> publish(String streamId, StreamEvent event) {
        String channel = channelKey(streamId);
        try {
            String json = objectMapper.writeValueAsString(event);
            Mono<Long> ssePublish = redisTemplate.convertAndSend(channel, json)
                    .doOnSuccess(receivers -> log.debug("📢 [PUBSUB] Published {} to {} receivers for stream: {}",
                            getEventType(event), receivers, streamId))
                    .doOnError(e -> log.error("❌ [PUBSUB] Failed to publish event: {}", e.getMessage()));

            // Also publish to WebSocket channel (keyed by conversationId for gateway bridge)
            String conversationId = extractConversationId(event);
            if (conversationId != null) {
                String wsChannel = WS_CHANNEL_PREFIX + conversationId;
                Mono<Long> wsPublish = redisTemplate.convertAndSend(wsChannel, json)
                        .doOnSuccess(receivers -> log.info("🔌 [WS PUBSUB] Published {} to ws channel {} ({} receivers)",
                                getEventType(event), wsChannel, receivers))
                        .onErrorResume(e -> {
                            log.warn("[WS PUBSUB] WS channel publish failed (non-fatal): {}", e.getMessage());
                            return Mono.just(0L);
                        });

                // Clean up cache on terminal events
                if (isTerminalEvent(event)) {
                    evictStreamCache(streamId);
                }

                return ssePublish.then(wsPublish);
            } else {
                log.debug("[WS PUBSUB] No conversationId for stream {}, WS channel not published", streamId);
            }
            return ssePublish;
        } catch (JsonProcessingException e) {
            log.error("❌ [PUBSUB] Failed to serialize event: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    /**
     * Publishes a tool event AND stores it for reconnection replay.
     * Used for tool_call and tool_result events that need to be replayed on reconnect.
     */
    public Mono<Long> publishAndStoreToolEvent(String streamId, StreamEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            // Store for reconnection FIRST, then publish
            return stateService.appendToolEvent(streamId, json)
                    .then(publish(streamId, event))
                    .doOnSuccess(receivers -> log.debug("📢 [PUBSUB] Published AND stored {} for stream: {}",
                            getEventType(event), streamId));
        } catch (JsonProcessingException e) {
            log.error("❌ [PUBSUB] Failed to serialize tool event: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    /**
     * Publishes a content chunk event.
     */
    public Mono<Long> publishContent(String streamId, String content) {
        return publish(streamId, StreamEvent.content(streamId, content));
    }

    /**
     * Publishes a replay content chunk with {@code "replay": true} marker.
     * Used by snapshot replay so the frontend can distinguish replay from real-time content.
     */
    public Mono<Long> publishReplayContent(String streamId, String content) {
        StreamEvent event = StreamEvent.content(streamId, content);
        try {
            ObjectNode node = objectMapper.valueToTree(event);
            node.put("replay", true);
            String replayJson = objectMapper.writeValueAsString(node);
            return publishRawJson(streamId, replayJson, "content(replay)");
        } catch (Exception e) {
            log.error("[PUBSUB] Failed to serialize replay content: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    /**
     * Publishes raw JSON to both SSE and WS channels for a stream.
     */
    private Mono<Long> publishRawJson(String streamId, String json, String eventType) {
        String channel = channelKey(streamId);
        Mono<Long> ssePublish = redisTemplate.convertAndSend(channel, json)
                .doOnSuccess(receivers -> log.debug("[PUBSUB] Published {} to {} receivers for stream: {}",
                        eventType, receivers, streamId));

        String conversationId = streamConversationCache.get(streamId);
        if (conversationId == null) {
            // Fallback: lookup from Redis
            try {
                StreamMetadata metadata = stateService.getMetadata(streamId).block();
                if (metadata != null && metadata.conversationId() != null) {
                    conversationId = metadata.conversationId();
                    streamConversationCache.put(streamId, conversationId);
                }
            } catch (Exception e) {
                log.debug("[PUBSUB] Failed to resolve conversationId for replay: {}", e.getMessage());
            }
        }

        if (conversationId != null) {
            String wsChannel = WS_CHANNEL_PREFIX + conversationId;
            Mono<Long> wsPublish = redisTemplate.convertAndSend(wsChannel, json)
                    .onErrorResume(e -> Mono.just(0L));
            return ssePublish.then(wsPublish);
        }
        return ssePublish;
    }

    /**
     * Publishes thinking/reasoning content for thinking models (Gemini 2.5+/3, o1, etc.).
     */
    public Mono<Long> publishThinking(String streamId, String thinking) {
        return publish(streamId, StreamEvent.thinking(streamId, thinking));
    }

    /**
     * Publishes a single thinking section (like a tool call).
     */
    public Mono<Long> publishThinkingSection(String streamId, String title, String content) {
        return publish(streamId, StreamEvent.thinkingSection(streamId, title, content));
    }

    /**
     * Publishes a stream completion event.
     */
    public Mono<Long> publishComplete(String streamId, String fullContent, int totalTokens) {
        return publish(streamId, StreamEvent.completed(streamId, fullContent, totalTokens));
    }

    /**
     * Publishes an error event.
     */
    public Mono<Long> publishError(String streamId, String error, String errorCode, boolean retryable) {
        return publish(streamId, StreamEvent.error(streamId, error, errorCode, retryable));
    }

    /**
     * Publishes a stream stopped event.
     */
    public Mono<Long> publishStopped(String streamId, String partialContent) {
        return publish(streamId, StreamEvent.stopped(streamId, partialContent));
    }

    /**
     * Publishes a heartbeat event.
     */
    public Mono<Long> publishHeartbeat(String streamId) {
        return publish(streamId, StreamEvent.heartbeat(streamId));
    }

    /**
     * Publishes a title update event.
     */
    public Mono<Long> publishTitleUpdated(String streamId, String conversationId, String title) {
        return publish(streamId, StreamEvent.titleUpdated(streamId, conversationId, title));
    }

    /**
     * Publishes a compaction-done event. Emitted by
     * {@code ChatCompactionOrchestrator} after a COLD summary envelope
     * persists, so the UI can flash a "prior context summarised" banner.
     * Because compaction runs async, this may be published after the
     * stream's terminal event - Redis pub/sub simply drops events with no
     * subscriber, so no special handling is required.
     */
    public Mono<Long> publishCompactionDone(String streamId, String conversationId,
                                            int turnsCoveredCount, String summarizerModel,
                                            java.time.Instant generatedAt) {
        return publish(streamId, StreamEvent.compactionDone(
                streamId, conversationId, turnsCoveredCount, summarizerModel, generatedAt));
    }

    // ══════════════════════════════════════════════════════════════════════
    // SUBSCRIBE - Called by streaming connections to receive events
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Subscribes to stream events for a specific stream.
     * Returns a Flux of StreamEvents that emits in real-time as events are published.
     *
     * The Flux completes when:
     * - A terminal event is received (completed, error, stopped)
     * - The subscription is cancelled (client disconnect)
     */
    public Flux<StreamEvent> subscribe(String streamId) {
        String channel = channelKey(streamId);
        log.info("🔔 [PUBSUB] Subscribing to channel: {}", channel);

        return messageListenerContainer.receive(ChannelTopic.of(channel))
                .map(ReactiveSubscription.Message::getMessage)
                .flatMap(json -> {
                    try {
                        StreamEvent event = deserializeEvent(json);
                        return Mono.just(event);
                    } catch (Exception e) {
                        log.warn("⚠️ [PUBSUB] Failed to deserialize event: {}", e.getMessage());
                        return Mono.empty();
                    }
                })
                .doOnNext(event -> log.info("📥 [PUBSUB] Received {} for stream: {}",
                        getEventType(event), streamId))
                .takeUntil(this::isTerminalEvent)
                .doOnSubscribe(s -> log.info("✅ [PUBSUB] Subscribed to stream: {}", streamId))
                .doOnCancel(() -> log.info("🚫 [PUBSUB] Subscription cancelled for stream: {}", streamId))
                .doOnComplete(() -> log.info("✅ [PUBSUB] Subscription completed for stream: {}", streamId));
    }

    /**
     * Bridge events from stream:events:{streamId} to ws:conversation:{conversationId}.
     * Used in remote execution (Phase 6): agent-service publishes to the SSE channel,
     * this bridge forwards raw JSON to the WS channel for the frontend.
     * No feedback loop because we only publish to the WS channel, not back to SSE.
     *
     * @return a Disposable to cancel the bridge when execution completes
     */
    public reactor.core.Disposable bridgeToWs(String streamId, String conversationId) {
        String sseChannel = channelKey(streamId);
        String wsChannel = WS_CHANNEL_PREFIX + conversationId;

        log.info("[BRIDGE] Bridging {} -> {}", sseChannel, wsChannel);

        return messageListenerContainer.receive(ChannelTopic.of(sseChannel))
                .map(ReactiveSubscription.Message::getMessage)
                .doOnNext(json -> {
                    // Forward raw JSON to WS channel (no deserialization needed)
                    redisTemplate.convertAndSend(wsChannel, json)
                            .subscribe(
                                receivers -> log.debug("[BRIDGE] Forwarded event to {} ({} receivers)", wsChannel, receivers),
                                err -> log.warn("[BRIDGE] Failed to forward to WS: {}", err.getMessage())
                            );
                })
                .takeUntil(json -> {
                    // Stop on terminal events (done, error, stopped)
                    try {
                        return json.contains("\"fullContent\"") || json.contains("\"error\"")
                                && json.contains("\"errorCode\"");
                    } catch (Exception e) {
                        return false;
                    }
                })
                .doOnSubscribe(s -> log.info("[BRIDGE] Active: {} -> {}", sseChannel, wsChannel))
                .doOnCancel(() -> log.info("[BRIDGE] Cancelled: {} -> {}", sseChannel, wsChannel))
                .doOnComplete(() -> log.info("[BRIDGE] Completed: {} -> {}", sseChannel, wsChannel))
                .subscribe();
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════

    private String channelKey(String streamId) {
        return CHANNEL_PREFIX + streamId;
    }

    /**
     * Extract conversationId from a StreamEvent.
     * Uses in-memory cache to avoid blocking Redis lookups on the reactive thread.
     * Cache is populated when StreamStarted/TitleUpdated events carry the conversationId directly.
     * Falls back to Redis lookup only if the cache misses.
     */
    private String extractConversationId(StreamEvent event) {
        String streamId = getStreamId(event);

        // StreamStarted and TitleUpdated carry conversationId directly - cache it
        if (event instanceof StreamEvent.StreamStarted started) {
            if (started.conversationId() != null && streamId != null) {
                streamConversationCache.put(streamId, started.conversationId());
            }
            return started.conversationId();
        }
        if (event instanceof StreamEvent.TitleUpdated updated) {
            if (updated.conversationId() != null && streamId != null) {
                streamConversationCache.put(streamId, updated.conversationId());
            }
            return updated.conversationId();
        }
        // CompactionDone also carries conversationId - and it almost always
        // publishes AFTER StreamCompleted has evicted the cache. Read it off
        // the event rather than paying a blocking Redis round-trip on every
        // compaction notification.
        if (event instanceof StreamEvent.CompactionDone done) {
            return done.conversationId();
        }

        // Check in-memory cache first (fast, no I/O)
        if (streamId != null) {
            String cached = streamConversationCache.get(streamId);
            if (cached != null) {
                return cached;
            }
        }

        // Fallback: Redis lookup (only needed if cache missed, e.g. after service restart)
        if (streamId != null) {
            try {
                StreamMetadata metadata = stateService.getMetadata(streamId).block();
                if (metadata != null && metadata.conversationId() != null) {
                    streamConversationCache.put(streamId, metadata.conversationId());
                    return metadata.conversationId();
                }
            } catch (Exception e) {
                log.debug("[PUBSUB] Failed to resolve conversationId for stream {}: {}", streamId, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * Removes the stream from the in-memory conversationId cache.
     * Should be called when a stream completes or is cleaned up.
     */
    public void evictStreamCache(String streamId) {
        if (streamId != null) {
            streamConversationCache.remove(streamId);
        }
    }

    private String getStreamId(StreamEvent event) {
        return switch (event) {
            case StreamEvent.ContentChunk e -> e.streamId();
            case StreamEvent.ThinkingChunk e -> e.streamId();
            case StreamEvent.ThinkingSection e -> e.streamId();
            case StreamEvent.StreamStarted e -> e.streamId();
            case StreamEvent.StreamCompleted e -> e.streamId();
            case StreamEvent.StreamError e -> e.streamId();
            case StreamEvent.StreamStopped e -> e.streamId();
            case StreamEvent.StreamAwaitingApproval e -> e.streamId();
            case StreamEvent.PendingActionCancelled e -> e.streamId();
            case StreamEvent.ToolCall e -> e.streamId();
            case StreamEvent.ToolResult e -> e.streamId();
            case StreamEvent.FetchScreenshot e -> e.streamId();
            case StreamEvent.AgentBrowseStep e -> e.streamId();
            case StreamEvent.CredentialRequired e -> e.streamId();
            case StreamEvent.ServiceApprovalRequired e -> e.streamId();
            case StreamEvent.Heartbeat e -> e.streamId();
            case StreamEvent.TitleUpdated e -> e.streamId();
            case StreamEvent.CompactionDone e -> e.streamId();
        };
    }

    // CompactionDone is intentionally NOT a terminal event: compaction runs on
    // the @Async executor and can publish AFTER StreamCompleted has already
    // closed the stream. If it ever got added here, adding to the subscriber
    // stream mid-flight would truncate in-flight content/tool events and the
    // frontend would lose trailing chunks. A regression pin in the
    // StreamPubSubService test (`compactionDoneDoesNotTerminateSubscriber`)
    // guards against accidental inclusion.
    private boolean isTerminalEvent(StreamEvent event) {
        return event instanceof StreamEvent.StreamCompleted ||
               event instanceof StreamEvent.StreamError ||
               event instanceof StreamEvent.StreamStopped ||
               event instanceof StreamEvent.StreamAwaitingApproval;
    }

    private String getEventType(StreamEvent event) {
        return switch (event) {
            case StreamEvent.StreamStarted ignored -> EVENT_TYPE_STARTED;
            case StreamEvent.ContentChunk ignored -> EVENT_TYPE_CONTENT;
            case StreamEvent.ThinkingChunk ignored -> EVENT_TYPE_THINKING;
            case StreamEvent.ThinkingSection ignored -> "thinking_section";
            case StreamEvent.ToolCall ignored -> EVENT_TYPE_TOOL_CALL;
            case StreamEvent.ToolResult ignored -> EVENT_TYPE_TOOL_RESULT;
            case StreamEvent.FetchScreenshot ignored -> "fetch_screenshot";
            case StreamEvent.AgentBrowseStep ignored -> "agent_browse_step";
            case StreamEvent.CredentialRequired ignored -> "credential_required";
            case StreamEvent.ServiceApprovalRequired ignored -> "service_approval_required";
            case StreamEvent.StreamAwaitingApproval ignored -> "awaiting_approval";
            case StreamEvent.PendingActionCancelled ignored -> "pending_action_cancelled";
            case StreamEvent.StreamCompleted ignored -> EVENT_TYPE_DONE;
            case StreamEvent.StreamError ignored -> EVENT_TYPE_ERROR;
            case StreamEvent.StreamStopped ignored -> EVENT_TYPE_STOPPED;
            case StreamEvent.Heartbeat ignored -> EVENT_TYPE_HEARTBEAT;
            case StreamEvent.TitleUpdated ignored -> EVENT_TYPE_TITLE;
            case StreamEvent.CompactionDone ignored -> EVENT_TYPE_COMPACTION_DONE;
        };
    }

    /**
     * Deserializes a JSON string to a StreamEvent.
     * Uses the sealed interface type information to reconstruct the correct subtype.
     * Public so it can be reused by StreamControllerV3 for tool event replay.
     */
    public StreamEvent deserializeEvent(String json) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(json);

        // Determine event type from the JSON structure
        if (node.has("fullContent") && node.has("totalTokens")) {
            return objectMapper.treeToValue(node, StreamEvent.StreamCompleted.class);
        } else if (node.has("error") && node.has("errorCode")) {
            return objectMapper.treeToValue(node, StreamEvent.StreamError.class);
        } else if (node.has("partialContent") && node.has("services")) {
            // StreamAwaitingApproval: has partialContent + services (paused for approval)
            return objectMapper.treeToValue(node, StreamEvent.StreamAwaitingApproval.class);
        } else if (node.has("partialContent")) {
            return objectMapper.treeToValue(node, StreamEvent.StreamStopped.class);
        } else if (node.has("success") && node.has("resultId")) {
            // ToolResult: has success + resultId (content fetched on demand)
            return objectMapper.treeToValue(node, StreamEvent.ToolResult.class);
        } else if (node.has("toolName") && node.has("toolId") && node.has("arguments")) {
            // ToolCall: has toolName + toolId + arguments
            return objectMapper.treeToValue(node, StreamEvent.ToolCall.class);
        } else if (node.has("screenshotIndex") && node.has("screenshotKey")) {
            // FetchScreenshot: has screenshotIndex + screenshotKey
            return objectMapper.treeToValue(node, StreamEvent.FetchScreenshot.class);
        } else if (node.has("cdpToken") && node.has("cdpWsUrl")) {
            // AgentBrowseStep: has cdpToken + cdpWsUrl (live-view bootstrap)
            return objectMapper.treeToValue(node, StreamEvent.AgentBrowseStep.class);
        } else if (node.has("credentialType")) {
            return objectMapper.treeToValue(node, StreamEvent.CredentialRequired.class);
        } else if (node.has("services") && node.has("reason")) {
            return objectMapper.treeToValue(node, StreamEvent.ServiceApprovalRequired.class);
        } else if (node.has("turnsCoveredCount") && node.has("summarizerModel")) {
            // CompactionDone: carries turnsCoveredCount + summarizerModel fields
            return objectMapper.treeToValue(node, StreamEvent.CompactionDone.class);
        } else if (node.has("title") && node.has("conversationId")) {
            return objectMapper.treeToValue(node, StreamEvent.TitleUpdated.class);
        } else if (node.has("title") && node.has("content") && !node.has("conversationId")) {
            // ThinkingSection: has title + content but NO conversationId (unlike TitleUpdated)
            return objectMapper.treeToValue(node, StreamEvent.ThinkingSection.class);
        } else if (node.has("model") && node.has("conversationId")) {
            return objectMapper.treeToValue(node, StreamEvent.StreamStarted.class);
        } else if (node.has("reason") && node.has("message")) {
            // PendingActionCancelled: has reason + message
            return objectMapper.treeToValue(node, StreamEvent.PendingActionCancelled.class);
        } else if (node.has("thinking")) {
            return objectMapper.treeToValue(node, StreamEvent.ThinkingChunk.class);
        } else if (node.has("content")) {
            return objectMapper.treeToValue(node, StreamEvent.ContentChunk.class);
        } else {
            // Default to heartbeat for minimal events
            String streamId = node.has("streamId") ? node.get("streamId").asText() : "";
            return StreamEvent.heartbeat(streamId);
        }
    }
}

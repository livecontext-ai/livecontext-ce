package com.apimarketplace.conversation.controller.internal;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.scope.TolerantScope;
import com.apimarketplace.conversation.client.StreamRedisKeys;
import com.apimarketplace.conversation.domain.stream.StreamEvent;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.service.StreamService;
import com.apimarketplace.conversation.streaming.StreamInterruptionService;
import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamState;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

import reactor.core.publisher.Mono;

/**
 * Internal endpoints for the Gateway to verify user access to conversations
 * and trigger state snapshots for WebSocket reconnection.
 * Protected by X-Gateway-Secret header.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalAccessController {

    private static final Logger log = LoggerFactory.getLogger(InternalAccessController.class);

    private final ConversationRepository conversationRepository;
    private final StreamStateService streamStateService;
    private final StreamPubSubService streamPubSubService;
    private final StreamService streamService;
    private final StreamInterruptionService streamInterruptionService;

    public InternalAccessController(ConversationRepository conversationRepository,
                                    StreamStateService streamStateService,
                                    StreamPubSubService streamPubSubService,
                                    StreamService streamService,
                                    StreamInterruptionService streamInterruptionService) {
        this.conversationRepository = conversationRepository;
        this.streamStateService = streamStateService;
        this.streamPubSubService = streamPubSubService;
        this.streamService = streamService;
        this.streamInterruptionService = streamInterruptionService;
    }

    // ==================== Stream Lifecycle (for orchestrator/agent-service) ====================

    /**
     * Register an externally-created stream. Creates metadata hash and conv index in Redis
     * using the caller's streamId (so content/tool lists written under that ID are found by snapshot).
     * Called by orchestrator/agent-service via ConversationClient.registerStream().
     */
    @PostMapping("/streams/register")
    public ResponseEntity<Void> registerStream(@RequestBody Map<String, String> body) {
        String streamId = body.get("streamId");
        String conversationId = body.get("conversationId");
        String model = body.getOrDefault("model", "unknown");
        String provider = body.getOrDefault("provider", "workflow");

        if (streamId == null || conversationId == null) {
            return ResponseEntity.badRequest().build();
        }

        // Resolve the conversation OWNER once: it attributes the Redis stream (so the
        // owner's /streams/active reconnect probe sees this externally-driven run and
        // the main chat page auto-attaches mid-flight) AND stamps the DB row below.
        String ownerUserId = null;
        try {
            ownerUserId = conversationRepository.findById(conversationId)
                    .map(Conversation::getUserId)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Failed to resolve owner of conversation {}: {}", conversationId, e.getMessage());
        }

        try {
            streamStateService.registerExternalStream(streamId, conversationId, model, provider, ownerUserId)
                    .block();
            log.debug("Registered external stream {} for conversation {} (owner {})",
                    streamId, conversationId, ownerUserId);
        } catch (Exception e) {
            log.warn("Failed to register stream for conversation {}: {}", conversationId, e.getMessage());
        }

        // Also create the DB row so the TTL scan can see this stream. Without it, streams
        // created by agent-service/orchestrator only exist in Redis and are invisible to
        // StreamTTLService - if the producer pod dies, the partial content is silently lost.
        // Best-effort: a DB hiccup must not fail the registration (Redis is the live path).
        // Note: StreamService.createStream first stops any existing ACTIVE streams for the
        // conversation. This is the assumed invariant "one active stream per conversation":
        // if two executions overlap on the same conversation, the most recent registration
        // wins and the previous stream's DB row is stopped.
        try {
            streamService.createStream(conversationId, streamId, ownerUserId != null ? ownerUserId : "system");
        } catch (Exception e) {
            log.warn("Failed to create DB row for external stream {} (conversation {}): {}",
                    streamId, conversationId, e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Finalize a stream (mark as terminal state + set cleanup TTL).
     * Called by orchestrator/agent-service via ConversationClient.finalizeStream().
     */
    @PostMapping("/streams/{streamId}/finalize")
    public ResponseEntity<Void> finalizeStream(@PathVariable String streamId,
                                                @RequestBody Map<String, String> body) {
        String state = body.getOrDefault("state", "COMPLETED");
        log.debug("Finalizing stream {} with state {}", streamId, state);

        try {
            if ("COMPLETED".equals(state)) {
                streamStateService.complete(streamId).block();
                // Also close the DB row - without this, every completed remote stream stays
                // ACTIVE until the 30-min TTL scan flags it ERROR. Best-effort: a DB hiccup
                // must not fail the finalization (Redis is the live path).
                try {
                    streamService.markStreamAsCompleted(streamId);
                } catch (Exception e) {
                    log.warn("Failed to mark DB row completed for stream {}: {}", streamId, e.getMessage());
                }
            } else if ("ERROR".equals(state)) {
                streamStateService.error(streamId, "Agent execution error").block();
                try {
                    streamService.markStreamAsError(streamId, "Agent execution error");
                } catch (Exception e) {
                    log.warn("Failed to mark DB row errored for stream {}: {}", streamId, e.getMessage());
                }
            } else if ("INTERRUPTED".equals(state)) {
                // Producer is going away (pod drain/shutdown) - rescue the partial content
                // before the Redis trace is gone, instead of just flipping the state.
                streamInterruptionService.interrupt(streamId, "Agent execution interrupted (drain/shutdown)");
            }
            log.debug("Finalized stream {} → {}", streamId, state);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.warn("Failed to finalize stream {}: {}", streamId, e.getMessage());
            return ResponseEntity.ok().build(); // Best-effort
        }
    }

    // ==================== Snapshot Helpers ====================

    /**
     * Replays the accumulated state of an active stream (STREAMING/CREATED).
     * Publishes: stream_started → tool events → accumulated content.
     * This allows late-joining WebSocket subscribers to catch up with the current state.
     */
    private Mono<Long> replayActiveStream(String streamId, String conversationId, StreamMetadata metadata) {
        log.debug("Replaying active stream: streamId={}, conversationId={}", streamId, conversationId);

        // 1. Publish stream_started so the frontend enters streaming mode
        Mono<Long> publishStarted = streamPubSubService.publish(streamId,
                StreamEvent.started(streamId, conversationId, metadata.model()));

        // 2. Replay stored tool events (tool_call/tool_result)
        Mono<Void> replayTools = streamStateService.getToolEvents(streamId)
                .flatMap(json -> {
                    try {
                        StreamEvent event = streamPubSubService.deserializeEvent(json);
                        return streamPubSubService.publish(streamId, event);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to deserialize tool event during replay: {}", e.getMessage());
                        return Mono.empty();
                    }
                })
                .then();

        // 3. Publish accumulated content as a single chunk
        Mono<Long> replayContent = streamStateService.getFullContent(streamId)
                .defaultIfEmpty("")
                .flatMap(content -> {
                    if (content.isEmpty()) {
                        return Mono.just(0L);
                    }
                    return streamPubSubService.publishReplayContent(streamId, content);
                });

        return publishStarted.then(replayTools).then(replayContent)
                .doOnSuccess(receivers -> log.info("Snapshot replay completed: streamId={}, conversationId={}", streamId, conversationId));
    }

    /**
     * Check if a user has access to a conversation.
     * Used by the Gateway's ChannelAuthorizer for WebSocket channel subscriptions.
     */
    @GetMapping("/conversations/{convId}/access")
    @TolerantScope(reason = "Gateway ChannelAuthorizer for WS subscriptions - gateway has already validated session.organizationId is a real membership for userId, so owner-OR-org access matches the user's authority across workspaces")
    public ResponseEntity<Boolean> checkAccess(@PathVariable String convId,
                                               @RequestParam String userId,
                                               @RequestParam(required = false) String orgId) {
        Optional<Conversation> conv = conversationRepository.findById(convId);
        if (conv.isEmpty()) {
            return ResponseEntity.ok(false);
        }
        Conversation c = conv.get();
        boolean hasAccess = ScopeGuard.isInOwnerOrOrgScope(
                userId, orgId, c.getUserId(), c.getOrganizationId());
        log.debug("Conversation access check: convId={}, userId={}, orgId={}, hasAccess={}",
                convId, userId, orgId, hasAccess);
        return ResponseEntity.ok(hasAccess);
    }

    /**
     * Trigger a state snapshot re-publish to Redis for a conversation stream.
     * Called by the Gateway when a WebSocket client subscribes with requestSnapshot=true.
     * The snapshot is published to Redis channel ws:conversation:{conversationId} and forwarded
     * to the subscribing client via the RedisChannelBridge.
     *
     * <p>This solves the timing gap between the REST state fetch and WS subscription:
     * any terminal event (COMPLETED, STOPPED, ERROR) that was published during that
     * window is replayed by this snapshot.</p>
     */
    @PostMapping("/streams/{conversationId}/snapshot")
    public ResponseEntity<Void> triggerSnapshot(@PathVariable String conversationId) {
        log.debug("Snapshot trigger requested for conversationId={}", conversationId);
        try {
            streamStateService.getByConversationId(conversationId)
                    .switchIfEmpty(Mono.defer(() -> {
                        log.info("No active stream found for snapshot: conversationId={}", conversationId);
                        return Mono.empty();
                    }))
                    .flatMap(metadata -> {
                        StreamState state = metadata.state();
                        String streamId = metadata.streamId();
                        log.debug("Snapshot for conversationId={}: streamId={}, state={}", conversationId, streamId, state);

                        return switch (state) {
                            case COMPLETED -> streamStateService.getFullContent(streamId)
                                    .defaultIfEmpty("")
                                    .flatMap(content -> streamPubSubService.publishComplete(streamId, content, 0));
                            // INTERRUPTED behaves like STOPPED_BY_USER for snapshot purposes:
                            // the partial content was already saved, just replay it as stopped.
                            case STOPPED_BY_USER, INTERRUPTED -> streamStateService.getFullContent(streamId)
                                    .defaultIfEmpty("")
                                    .flatMap(content -> streamPubSubService.publishStopped(streamId, content));
                            case ERROR -> streamPubSubService.publishError(streamId, "Stream ended with error", "SNAPSHOT_REPLAY", false);
                            case STREAMING, CREATED -> replayActiveStream(streamId, conversationId, metadata);
                            case AWAITING_APPROVAL -> streamPubSubService.publishHeartbeat(streamId);
                        };
                    })
                    .subscribe(
                            receivers -> log.debug("Snapshot published for conversationId={}, receivers={}", conversationId, receivers),
                            error -> log.warn("Snapshot publish failed for conversationId={}: {}", conversationId, error.getMessage())
                    );
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.warn("Snapshot trigger failed for conversationId={}: {}", conversationId, e.getMessage());
            return ResponseEntity.ok().build(); // Don't fail - snapshot is best-effort
        }
    }
}

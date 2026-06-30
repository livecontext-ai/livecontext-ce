package com.apimarketplace.conversation.controller.v3;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.conversation.dto.StreamStatusResponse;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V3 Stream Controller - REST endpoints for stream management.
 *
 * Architecture:
 * - Real-time events flow via WebSocket (conversation:{conversationId} channel)
 * - This controller provides REST endpoints for:
 *   1. Stream state queries (status, active streams)
 *   2. Reconnection state recovery (buffered content + tool events)
 *   3. Stream lifecycle management (stop)
 *   4. Monitoring (metrics)
 */
@Slf4j
@RestController
@RequestMapping("/api/v3/streams")
@RequiredArgsConstructor
public class StreamControllerV3 {

    private final StreamStateService stateService;
    private final StreamPubSubService pubSubService;
    private final AgentClient agentClient;
    private final ConversationRepository conversationRepository;

    /**
     * Strict-isolation access gate for the conversation-scoped stream endpoints.
     *
     * <p>The {@code /by-conversation/...} reads return the live buffered
     * assistant content + tool events, exactly the data the WebSocket channel
     * exposes - and the WS path is already gated by the gateway's
     * {@code ChannelAuthorizer}. These REST routes carry no per-conversation
     * gateway filter (see {@code SimpleGatewayConfig} "streams-v3"), so without
     * this check any authenticated user could read another user's stream by
     * conversation id. We mirror the canonical strict scope used by
     * {@code ConversationQueryService#getConversationById}: the row must be in
     * the caller's currently-active workspace. Fails closed on any lookup error
     * so a hiccup can never widen access.
     */
    private Mono<Boolean> hasConversationAccess(String conversationId, String userId, String organizationId) {
        return Mono.fromCallable(() -> {
                    try {
                        return conversationRepository.findById(conversationId)
                                .map(c -> ScopeGuard.isInStrictScope(
                                        userId, organizationId, c.getUserId(), c.getOrganizationId()))
                                .orElse(false);
                    } catch (Exception e) {
                        log.warn("[ACCESS] Conversation access check errored for {} (user {}): {} - denying",
                                conversationId, userId, e.getMessage());
                        return false;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> noStreamBody(String conversationId) {
        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", conversationId);
        result.put("hasActiveStream", false);
        // Empty content + tool events so this shape is identical to a genuine
        // "no active stream" response (the reconnection contract clients expect)
        // - and so a denied/out-of-scope read is indistinguishable from one with
        // no stream. Empty values leak nothing; the real buffered content and
        // streamId/model are simply never read for these responses.
        result.put("content", "");
        result.put("toolEvents", List.of());
        return result;
    }

    /**
     * F2.1 + F3.4 - fan out the conversation STOP to any workflow runs and
     * background tasks the agent loop spawned for this conversation. The chat
     * panel STOP button (`/v3/streams/{id}/stop`) and the by-conversation STOP
     * (`/v3/by-conversation/{conv}/stop`) both go through here, mirroring the
     * cascade already wired into {@link
     * com.apimarketplace.conversation.controller.v3.chat.StreamStopHandler}.
     *
     * <p>Ran on the boundedElastic scheduler so the reactive chain that owns
     * the HTTP response isn't blocked by AgentClient's RestTemplate calls. We
     * intentionally do NOT chain it into the response - best-effort, errors
     * already swallowed and logged inside AgentClient.</p>
     */
    private void cascadeStopAsync(String conversationId, String userId, String organizationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        Mono.fromRunnable(() -> {
            try {
                int runs = agentClient.cancelWorkflowsForConversation(conversationId, organizationId);
                if (runs > 0) {
                    log.info("[STOP] Cascaded conversation {} STOP to {} workflow run(s)", conversationId, runs);
                }
            } catch (Exception e) {
                log.warn("[STOP] Workflow cascade failed (non-critical) for conv={}: {}", conversationId, e.getMessage());
            }
            try {
                int tasks = agentClient.cancelTasksForConversation(conversationId, userId, organizationId);
                if (tasks > 0) {
                    log.info("[STOP] Cascaded conversation {} STOP to {} task row(s)", conversationId, tasks);
                }
            } catch (Exception e) {
                log.warn("[STOP] Task cascade failed (non-critical) for conv={}: {}", conversationId, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    // ══════════════════════════════════════════════════════════════════════
    // RECONNECTION STATE (for WebSocket clients)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns buffered content + tool events for WebSocket reconnection recovery.
     * Called by frontend when re-subscribing to a conversation channel after page reload.
     */
    @GetMapping("/by-conversation/{conversationId}/state")
    public Mono<ResponseEntity<Map<String, Object>>> getStreamState(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        log.info("[STATE] Reconnection state request for conversation: {} by user: {}", conversationId, userId);

        return hasConversationAccess(conversationId, userId, organizationId)
                .flatMap(allowed -> {
                    if (!allowed) {
                        // Out-of-scope caller: never leak buffered content - or even
                        // the stream's existence - respond exactly as the no-stream
                        // branch does. (The live WS channel for this same data is
                        // already gated by the gateway ChannelAuthorizer.)
                        log.warn("[STATE] Conversation {} out of scope for user {} - returning no-stream",
                                conversationId, userId);
                        return Mono.<ResponseEntity<Map<String, Object>>>just(
                                ResponseEntity.ok(noStreamBody(conversationId)));
                    }
                    return stateService.getByConversationId(conversationId)
                            .flatMap(metadata -> {
                                String streamId = metadata.streamId();
                                Mono<String> contentMono = stateService.getFullContent(streamId).defaultIfEmpty("");
                                Mono<List<String>> toolsMono = stateService.getToolEvents(streamId).collectList();

                                return Mono.zip(contentMono, toolsMono)
                                        .map(tuple -> {
                                            Map<String, Object> result = new HashMap<>();
                                            result.put("streamId", streamId);
                                            result.put("conversationId", conversationId);
                                            result.put("model", metadata.model());
                                            result.put("state", metadata.state().name());
                                            result.put("content", tuple.getT1());
                                            result.put("toolEvents", tuple.getT2());
                                            result.put("hasActiveStream", metadata.state().isReconnectable());
                                            return ResponseEntity.ok(result);
                                        });
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                log.info("[STATE] No stream found for conversation: {}", conversationId);
                                return Mono.just(ResponseEntity.ok(noStreamBody(conversationId)));
                            }));
                })
                .onErrorResume(e -> {
                    log.error("[STATE] Error getting stream state for conversation: {}", conversationId, e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    // ══════════════════════════════════════════════════════════════════════
    // STATUS & STOP ENDPOINTS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Gets the status of an active stream by conversationId.
     */
    @GetMapping("/by-conversation/{conversationId}/status")
    public Mono<ResponseEntity<StreamStatusResponse>> getStreamStatusByConversation(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        log.info("[STATUS] Looking for active stream for conversation: {} (user: {})", conversationId, userId);

        return hasConversationAccess(conversationId, userId, organizationId)
                .flatMap(allowed -> {
                    if (!allowed) {
                        // Out-of-scope: report no active stream rather than reveal
                        // another workspace's stream state.
                        log.warn("[STATUS] Conversation {} out of scope for user {} - reporting no active stream",
                                conversationId, userId);
                        return Mono.<ResponseEntity<StreamStatusResponse>>just(
                                ResponseEntity.ok(StreamStatusResponse.noActiveStream(conversationId)));
                    }
                    return stateService.getByConversationId(conversationId)
                            .doOnNext(metadata -> log.info("[STATUS] Found stream: {} state: {} isReconnectable: {} for conversation: {}",
                                    metadata.streamId(), metadata.state(), metadata.state().isReconnectable(), conversationId))
                            .map(metadata -> ResponseEntity.ok(StreamStatusResponse.from(metadata)))
                            .switchIfEmpty(Mono.defer(() -> {
                                log.info("[STATUS] No active stream for conversation: {}", conversationId);
                                return Mono.just(ResponseEntity.ok(StreamStatusResponse.noActiveStream(conversationId)));
                            }));
                })
                .onErrorResume(e -> {
                    log.error("[STATUS] Error getting stream status for conversation: {}", conversationId, e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    /**
     * Stops an active stream by stream ID.
     */
    @PostMapping("/{streamId}/stop")
    public Mono<ResponseEntity<Void>> stopStream(
            @PathVariable String streamId,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        log.info("[STOP] Stop request for stream: {} by user: {}", streamId, userId);

        return stateService.getMetadata(streamId)
                .flatMap(metadata -> hasConversationAccess(metadata.conversationId(), userId, organizationId)
                        .flatMap(allowed -> {
                            if (!allowed) {
                                // streamId is an unguessable server-minted UUID, but gate it
                                // anyway so the whole stream-stop class is consistently scoped.
                                log.warn("[STOP] Stream {} (conversation {}) out of scope for user {} - refusing stop",
                                        streamId, metadata.conversationId(), userId);
                                return Mono.just(ResponseEntity.notFound().<Void>build());
                            }
                            if (!metadata.state().isActive()) {
                                log.info("[STOP] Stream already stopped: {}", streamId);
                                // Run is already stopped on this side, but a workflow or task
                                // spawned earlier may still be live. Cascade defensively.
                                cascadeStopAsync(metadata.conversationId(), userId, organizationId);
                                return Mono.just(ResponseEntity.ok().<Void>build());
                            }

                            String conversationId = metadata.conversationId();
                            log.info("[STOP] Stopping stream: {} for conversation: {}", streamId, conversationId);

                            return stateService.stop(streamId)
                                    .then(stateService.setCancelKey(streamId))
                                    .then(stateService.getFullContent(streamId))
                                    .flatMap(content -> {
                                        log.info("[STOP] Publishing stopped event, content: {} chars", content != null ? content.length() : 0);
                                        return pubSubService.publishStopped(streamId, content != null ? content : "");
                                    })
                                    .doOnSuccess(v -> cascadeStopAsync(conversationId, userId, organizationId))
                                    .then(Mono.just(ResponseEntity.ok().<Void>build()));
                        }))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[STOP] Stream not found: {}", streamId);
                    return Mono.just(ResponseEntity.notFound().build());
                }))
                .onErrorResume(e -> {
                    log.error("[STOP] Error stopping stream: {}", streamId, e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    /**
     * Stops an active stream by conversation ID.
     */
    @PostMapping("/by-conversation/{conversationId}/stop")
    public Mono<ResponseEntity<Void>> stopStreamByConversation(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        log.info("[STOP] Stop request for conversation: {} by user: {}", conversationId, userId);

        return hasConversationAccess(conversationId, userId, organizationId)
                .flatMap(allowed -> {
                    if (!allowed) {
                        // Never stop or cascade-cancel a conversation the caller
                        // cannot see - that would be a cross-user denial of service.
                        log.warn("[STOP] Conversation {} out of scope for user {} - refusing stop",
                                conversationId, userId);
                        return Mono.<ResponseEntity<Void>>just(ResponseEntity.notFound().build());
                    }
                    return stateService.getByConversationId(conversationId)
                            .flatMap(metadata -> {
                                String streamId = metadata.streamId();
                                log.info("[STOP] Stopping stream: {}", streamId);

                                return stateService.stop(streamId)
                                        .then(stateService.setCancelKey(streamId))
                                        .then(stateService.getFullContent(streamId))
                                        .flatMap(content -> pubSubService.publishStopped(streamId, content))
                                        .doOnSuccess(v -> cascadeStopAsync(conversationId, userId, organizationId))
                                        .then(Mono.just(ResponseEntity.ok().<Void>build()));
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                // No active stream - but tasks/workflows the user spawned in
                                // this conversation may still be live. Cascade anyway so the
                                // STOP click is honored even if the chat finished naturally.
                                cascadeStopAsync(conversationId, userId, organizationId);
                                return Mono.just(ResponseEntity.notFound().<Void>build());
                            }));
                })
                .onErrorResume(e -> {
                    log.error("[STOP] Error stopping stream for conversation: {}", conversationId, e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    /**
     * Gets the status of a stream by streamId.
     */
    @GetMapping("/{streamId}/status")
    public Mono<ResponseEntity<StreamStatusResponse>> getStreamStatus(
            @PathVariable String streamId,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        return stateService.getMetadata(streamId)
                .flatMap(metadata -> hasConversationAccess(metadata.conversationId(), userId, organizationId)
                        .map(allowed -> allowed
                                ? ResponseEntity.ok(StreamStatusResponse.from(metadata))
                                // Out of scope: respond as if the stream id did not exist.
                                : ResponseEntity.notFound().<StreamStatusResponse>build()))
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(e -> {
                    log.error("Error getting stream status: {}", streamId, e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    /**
     * Gets all active streaming conversation IDs for the current user.
     */
    @GetMapping("/active")
    public Mono<ResponseEntity<java.util.List<String>>> getActiveStreamingConversations(
            @RequestHeader(value = "X-User-ID") String userId) {

        log.info("[ACTIVE] Getting active streaming conversations for user: {}", userId);

        return stateService.getStreamingConversationIds(userId)
                .collectList()
                .doOnNext(ids -> log.info("[ACTIVE] Found {} active streams for user: {}", ids.size(), userId))
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("[ACTIVE] Error getting active streams for user: {}", userId, e);
                    return Mono.just(ResponseEntity.ok(java.util.List.of()));
                });
    }

    /**
     * Gets metrics for monitoring.
     */
    @GetMapping("/metrics")
    public Mono<ResponseEntity<StreamMetrics>> getMetrics() {
        return Mono.just(ResponseEntity.ok(new StreamMetrics(0, "Redis-based, check Redis for active count")));
    }

    public record StreamMetrics(int localActiveStreams, String note) {}
}

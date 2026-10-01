package com.apimarketplace.conversation.streaming;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Stream;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.StreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Rescues streams whose producer died (agent-service pod drain/shutdown, lost heartbeat,
 * TTL timeout) by persisting the partial content accumulated in Redis BEFORE the stream
 * trace is discarded.
 * <p>
 * Mirrors the user-initiated path in {@code StreamStopHandler.stopStream()}: read the full
 * buffered content, save it as an assistant message, notify connected UIs via pub/sub, then
 * mark the stream INTERRUPTED and free the Redis keys.
 * <p>
 * Every step is best-effort: a failure in one step (e.g. pub/sub down) must not prevent the
 * following steps - the whole point is to lose as little as possible when infrastructure
 * is already degraded.
 * <p>
 * The other direction has the same loss and the same remedy: a producer whose answer never
 * reached the service that persists it. See {@link #claimUnsavedReply}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamInterruptionService {

    /**
     * Atomic claim key: several rescuers can race on the same stream (agent-service drain via
     * finalizeStream, the TTL reconciliation scan, and multiple conversation-service replicas).
     * SETNX on this key elects exactly one of them; the 5-minute TTL self-heals if the winner
     * dies mid-rescue.
     */
    private static final String INTERRUPT_CLAIM_KEY_PREFIX = "stream:interrupt:claim:";

    private static final Duration INTERRUPT_CLAIM_TTL = Duration.ofMinutes(5);

    private final StreamStateService stateService;
    private final StreamPubSubService pubSubService;
    private final ConversationHistoryService conversationHistoryService;
    private final StreamService streamService;
    private final ConversationRepository conversationRepository;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Interrupts a stream: saves the partial content as an assistant message, publishes a
     * stopped event so any connected UI updates, marks the stream INTERRUPTED and cleans up.
     *
     * @param streamId the Redis stream id
     * @param reason   human-readable interruption reason (stored on the DB row)
     * @return true if the stream was processed (partial rescued + state finalized),
     *         false if there was nothing to do (no Redis trace, already terminal, or another
     *         rescuer holds the interrupt claim)
     */
    public boolean interrupt(String streamId, String reason) {
        // Atomic claim - interrupt() is check-then-act and can be raced by the agent-service
        // drain, the TTL scan, and sibling replicas, each saving the partial a second time.
        // SETNX makes exactly one caller proceed; losers back off.
        if (!claimRescue(streamId)) {
            log.debug("[INTERRUPT] Stream {} already claimed by another rescuer - skipping", streamId);
            return false;
        }

        StreamMetadata metadata;
        try {
            metadata = stateService.getMetadata(streamId).block();
        } catch (Exception e) {
            log.warn("[INTERRUPT] Failed to read metadata for stream {}: {}", streamId, e.getMessage());
            return false;
        }

        if (metadata == null) {
            log.debug("[INTERRUPT] No Redis trace for stream {} - nothing to rescue", streamId);
            return false;
        }
        if (metadata.state() != null && metadata.state().isTerminal()) {
            log.debug("[INTERRUPT] Stream {} already terminal ({}) - skipping", streamId, metadata.state());
            return false;
        }

        String conversationId = metadata.conversationId();
        String userId = resolveUserId(streamId, conversationId);

        // 1. Rescue the partial content accumulated in Redis (same pattern as StreamStopHandler)
        String partialContent = null;
        try {
            partialContent = stateService.getFullContent(streamId).block();
            if (partialContent != null && !partialContent.trim().isEmpty()) {
                conversationHistoryService.addMessage(
                        conversationId, "assistant", partialContent, metadata.model(),
                        Instant.now().toString(), null, userId
                );
                log.info("[INTERRUPT] Partial content saved for stream {}: {} chars (reason: {})",
                        streamId, partialContent.length(), reason);
            }
        } catch (Exception e) {
            log.error("[INTERRUPT] Failed to save partial content for stream {}: {}", streamId, e.getMessage());
        }

        // 2-4. Stopped event, INTERRUPTED + key cleanup, DB row.
        endInterrupted(streamId, partialContent, reason);
        return true;
    }

    /**
     * Takes the single rescue claim of a stream: whoever holds it is the one caller allowed to
     * save that stream's partial. Every saver takes it (the drain / TTL {@link #interrupt}, the
     * lost-answer {@link #claimUnsavedReply}, and the user's Stop), because the stream STATE
     * cannot arbitrate: a producer's own finalize overwrites it (a stopped loop still reports
     * COMPLETED), so a stop and a later rescue both saw a stream they could save.
     *
     * @return true when this caller may save (claim taken, or Redis could not arbitrate: a
     *         duplicated save beats a lost reply); false when another saver holds the claim
     */
    public boolean claimRescue(String streamId) {
        try {
            Boolean claimed = stringRedisTemplate.opsForValue()
                    .setIfAbsent(INTERRUPT_CLAIM_KEY_PREFIX + streamId, "1", INTERRUPT_CLAIM_TTL);
            return !Boolean.FALSE.equals(claimed);
        } catch (Exception e) {
            log.warn("[RESCUE] Rescue claim failed for stream {} - proceeding without claim: {}",
                    streamId, e.getMessage());
            return true;
        }
    }

    /**
     * Ends a stream whose producer is gone, or is being stopped, AFTER its partial content has
     * been saved: publishes the stopped event carrying the partial (so a connected UI renders it
     * as a cut-off turn, never as a complete one), marks the stream INTERRUPTED and frees its
     * Redis keys, then reflects the interruption on the DB row. Every step is best-effort, for
     * the reason given on the class.
     *
     * @param partialContent what was saved (null or empty publishes an empty partial)
     * @param reason         stored on the DB row
     */
    public void endInterrupted(String streamId, String partialContent, String reason) {
        endInterrupted(streamId, partialContent, reason, false);
    }

    /**
     * Same, and when {@code stopProducer} is set, also tells a producer that may still be
     * running (a live agent-service loop, a CLI behind the bridge) to stop, through the cancel
     * key the Stop button uses. The key is written AFTER the key cleanup: {@code delete} removes
     * {@code agent:cancel:{streamId}} too, and both producers only POLL for it, so a key written
     * before the cleanup was gone before either could see it, and the producer kept streaming
     * and billing onto a closed turn.
     */
    public void endInterrupted(String streamId, String partialContent, String reason, boolean stopProducer) {
        // Notify any connected UI so it stops showing a spinner and renders the partial
        try {
            pubSubService.publishStopped(streamId, partialContent != null ? partialContent : "").block();
        } catch (Exception e) {
            log.warn("[INTERRUPT] Failed to publish stopped event for stream {}: {}", streamId, e.getMessage());
        }

        // Mark INTERRUPTED in Redis then delete - the content is saved, free the keys.
        // Delete is best-effort: Redis TTL will reclaim the keys anyway if it fails.
        try {
            stateService.updateState(streamId, StreamState.INTERRUPTED).block();
            Mono<?> cleanup = stateService.delete(streamId)
                    .onErrorResume(e -> {
                        log.warn("[INTERRUPT] Redis cleanup failed for stream {}: {}", streamId, e.getMessage());
                        return Mono.empty();
                    });
            if (stopProducer) {
                cleanup = cleanup.then(Mono.defer(() -> stateService.setCancelKey(streamId)));
            }
            cleanup.subscribe(
                    done -> {},
                    e -> log.warn("[INTERRUPT] Failed to set the cancel key of stream {}: {}", streamId, e.getMessage())
            );
        } catch (Exception e) {
            log.warn("[INTERRUPT] Failed to finalize Redis state for stream {}: {}", streamId, e.getMessage());
            if (stopProducer) {
                // Nothing was deleted: the key written now survives.
                try {
                    stateService.setCancelKey(streamId).block();
                } catch (Exception cancelFailure) {
                    log.warn("[INTERRUPT] Failed to set the cancel key of stream {}: {}", streamId, cancelFailure.getMessage());
                }
            }
        }

        // Reflect the interruption on the DB row if one exists (streams registered via
        // registerStream now have one; legacy external streams may not)
        try {
            streamService.markStreamAsInterrupted(streamId, reason);
        } catch (Exception e) {
            log.warn("[INTERRUPT] Failed to mark DB row interrupted for stream {}: {}", streamId, e.getMessage());
        }
    }

    /**
     * The reply a stream buffered in Redis, and the state its producer left the stream in.
     *
     * @param content the buffered text, never blank
     * @param state   the stream state read before the claim; null when it could not be read
     */
    public record BufferedReply(String content, StreamState state) {

        /** The producer finished and said so: the reply is complete, only its answer was lost. */
        public boolean producerCompleted() {
            return state == StreamState.COMPLETED;
        }

        /** The producer ended the run as a failure: what was buffered is the partial of a failed turn. */
        public boolean producerFailed() {
            return state == StreamState.ERROR;
        }
    }

    /**
     * Claims the reply a stream buffered in Redis so the caller can save it, when the service
     * that owns its persistence never received the producer's answer (the agent-service or
     * bridge call came back empty) or failed to write it. The streamed text then exists only in
     * Redis, whose keys are dropped 30 seconds after the producer finalizes the stream, so the
     * user watched a reply stream that never reached the history.
     * <p>
     * The same rescue as {@link #interrupt}, split where the caller knows more: it writes the
     * message itself (with the turn's execution and agent attribution, which this service does
     * not have) and chooses the terminal event from the returned state. Unlike interrupt() it
     * accepts a COMPLETED stream (the direct-API producer finalizes before it returns). Shares
     * interrupt()'s claim, so this and a drain / TTL rescue never both save the reply, and skips
     * a stream that a stop or an interruption already settled: each of those saved its own copy.
     *
     * @return the buffered reply and the state read; empty when nothing was buffered, the stream
     *         was settled by a stop or an interruption, or another rescuer holds the claim
     */
    public Optional<BufferedReply> claimUnsavedReply(String streamId) {
        if (streamId == null) {
            return Optional.empty();
        }
        StreamState state = null;
        try {
            StreamMetadata metadata = stateService.getMetadata(streamId).block();
            state = metadata != null ? metadata.state() : null;
        } catch (Exception e) {
            // Unknown state is not a settled one: the claim below still keeps the save single.
            log.warn("[RESCUE] Failed to read metadata for stream {}: {}", streamId, e.getMessage());
        }
        if (state == StreamState.STOPPED_BY_USER || state == StreamState.INTERRUPTED) {
            log.debug("[RESCUE] Stream {} already settled ({}) - its partial was saved there", streamId, state);
            return Optional.empty();
        }

        String content;
        try {
            content = stateService.getFullContent(streamId).block();
        } catch (Exception e) {
            log.warn("[RESCUE] Failed to read buffered content for stream {}: {}", streamId, e.getMessage());
            return Optional.empty();
        }
        if (content == null || content.trim().isEmpty()) {
            return Optional.empty();
        }

        // Claimed only once there is something to save: an empty claim would hold off the
        // TTL rescue of this stream for nothing.
        if (!claimRescue(streamId)) {
            log.info("[RESCUE] Stream {} already claimed by another rescuer - not saving it twice", streamId);
            return Optional.empty();
        }
        return Optional.of(new BufferedReply(content, state));
    }

    /**
     * Resolves the userId to attribute the rescued assistant message to.
     * Preference order: DB stream row → conversation owner → "system".
     * Externally-registered streams store userId="internal" in Redis, which is not a real
     * user - so the Redis metadata userId is deliberately NOT used here.
     */
    private String resolveUserId(String streamId, String conversationId) {
        try {
            Optional<Stream> dbStream = streamService.getStreamByStreamId(streamId);
            if (dbStream.isPresent() && dbStream.get().getUserId() != null) {
                return dbStream.get().getUserId();
            }
        } catch (Exception e) {
            log.debug("[INTERRUPT] DB stream lookup failed for {}: {}", streamId, e.getMessage());
        }
        try {
            if (conversationId != null) {
                return conversationRepository.findById(conversationId)
                        .map(Conversation::getUserId)
                        .orElse("system");
            }
        } catch (Exception e) {
            log.debug("[INTERRUPT] Conversation lookup failed for {}: {}", conversationId, e.getMessage());
        }
        return "system";
    }
}

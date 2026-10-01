package com.apimarketplace.conversation.streaming;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link StreamStateService} that keeps the KEY semantics of
 * {@link RedisStreamStateService} that a cancel-key ordering bug depends on, for tests that must
 * see the real order of writes rather than a mock's recorded calls:
 * <ul>
 *   <li>{@link #delete} removes the stream, its content AND {@code agent:cancel:{streamId}}, as
 *       the real delete does;</li>
 *   <li>{@link #complete} / {@link #stop} / {@link #error} schedule the 30 s cleanup, which
 *       also puts a 30 s expiry on an EXISTING cancel key;</li>
 *   <li>{@link #setCancelKey} writes the key with the Stop path's 5-minute TTL
 *       ({@code RedisStreamStateService.CANCEL_KEY_TTL}).</li>
 * </ul>
 * Every Mono is lazy, like a reactive Redis command: nothing happens before subscription.
 * Methods no test needs throw, so a new dependency on them is noticed, not faked.
 */
public class InMemoryStreamStateService implements StreamStateService {

    /** Mirrors {@code RedisStreamStateService.CANCEL_KEY_TTL}. */
    public static final Duration STOP_PATH_CANCEL_TTL = Duration.ofMinutes(5);
    /** Mirrors the cleanup delay of complete / stop / error. */
    public static final Duration CLEANUP_TTL = Duration.ofSeconds(30);

    private final Map<String, StreamMetadata> streams = new HashMap<>();
    private final Map<String, StringBuilder> contents = new HashMap<>();
    private final Map<String, Duration> cancelKeys = new HashMap<>();

    /** Seed a stream as a running turn would have left it. */
    public void seed(String streamId, String conversationId, StreamState state, String content) {
        streams.put(streamId, new StreamMetadata(streamId, "user-1", conversationId, "m", "p",
                state, Instant.now(), Instant.now(), content.length()));
        contents.put(streamId, new StringBuilder(content));
    }

    public boolean hasCancelKey(String streamId) {
        return cancelKeys.containsKey(streamId);
    }

    public Duration cancelKeyTtl(String streamId) {
        return cancelKeys.get(streamId);
    }

    public boolean hasStream(String streamId) {
        return streams.containsKey(streamId);
    }

    public StreamState state(String streamId) {
        StreamMetadata m = streams.get(streamId);
        return m != null ? m.state() : null;
    }

    @Override
    public Mono<StreamMetadata> getMetadata(String streamId) {
        return Mono.fromCallable(() -> streams.get(streamId));
    }

    @Override
    public Mono<Boolean> updateState(String streamId, StreamState newState) {
        return Mono.fromCallable(() -> {
            StreamMetadata m = streams.get(streamId);
            // Like HSET on a missing hash: the field is written anyway.
            streams.put(streamId, new StreamMetadata(streamId, m != null ? m.userId() : "", m != null
                    ? m.conversationId() : "", m != null ? m.model() : "", m != null ? m.provider() : "",
                    newState, Instant.now(), Instant.now(), m != null ? m.contentLength() : 0));
            return true;
        });
    }

    @Override
    public Mono<Boolean> complete(String streamId) {
        return updateState(streamId, StreamState.COMPLETED).flatMap(ok -> scheduleCleanup(streamId));
    }

    @Override
    public Mono<Boolean> stop(String streamId) {
        return updateState(streamId, StreamState.STOPPED_BY_USER).flatMap(ok -> scheduleCleanup(streamId));
    }

    @Override
    public Mono<Boolean> error(String streamId, String errorMessage) {
        return updateState(streamId, StreamState.ERROR).flatMap(ok -> scheduleCleanup(streamId));
    }

    private Mono<Boolean> scheduleCleanup(String streamId) {
        return Mono.fromCallable(() -> {
            // EXPIRE on a missing key does nothing; on the cancel key it shortens its life.
            cancelKeys.computeIfPresent(streamId, (k, ttl) -> CLEANUP_TTL);
            return true;
        });
    }

    @Override
    public Mono<Boolean> isActive(String streamId) {
        return Mono.fromCallable(() -> {
            StreamMetadata m = streams.get(streamId);
            return m != null && m.state().isActive();
        });
    }

    @Override
    public Mono<Long> delete(String streamId) {
        return Mono.fromCallable(() -> {
            long removed = 0;
            if (streams.remove(streamId) != null) removed++;
            if (contents.remove(streamId) != null) removed++;
            // The real delete names agent:cancel:{streamId} among its keys.
            if (cancelKeys.remove(streamId) != null) removed++;
            return removed;
        });
    }

    @Override
    public Mono<String> getFullContent(String streamId) {
        return Mono.fromCallable(() -> {
            StringBuilder content = contents.get(streamId);
            return content != null ? content.toString() : "";
        });
    }

    @Override
    public Mono<Boolean> setCancelKey(String streamId) {
        return Mono.fromCallable(() -> {
            cancelKeys.put(streamId, STOP_PATH_CANCEL_TTL);
            return true;
        });
    }

    @Override
    public Mono<Long> appendContent(String streamId, String chunk) {
        return Mono.fromCallable(() -> {
            contents.computeIfAbsent(streamId, k -> new StringBuilder()).append(chunk);
            return 1L;
        });
    }

    // ─── Not needed by the tests that use this fake ─────────────────────────

    @Override
    public Mono<StreamMetadata> createStream(String userId, String conversationId, String model, String provider) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Mono<StreamMetadata> registerExternalStream(String streamId, String conversationId, String model,
                                                       String provider, String ownerUserId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Mono<StreamMetadata> getByConversationId(String conversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Flux<String> getStreamingConversationIds(String userId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Mono<Boolean> setAwaitingApproval(String streamId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Mono<StreamState> getState(String streamId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Flux<String> getContentChunks(String streamId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Mono<Long> appendToolEvent(String streamId, String toolEventJson) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Flux<String> getToolEvents(String streamId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Mono<Long> publishStop(String streamId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Flux<String> subscribeToStop(String streamId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Mono<Boolean> touch(String streamId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Mono<Boolean> updateConversationId(String streamId, String newConversationId) {
        throw new UnsupportedOperationException();
    }
}

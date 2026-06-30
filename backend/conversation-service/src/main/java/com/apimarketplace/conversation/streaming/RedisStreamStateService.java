package com.apimarketplace.conversation.streaming;

import com.apimarketplace.conversation.client.StreamRedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Redis-based implementation of StreamStateService.
 * Single source of truth for all stream state and content.
 * <p>
 * Key schema defined in {@link StreamRedisKeys} (shared with orchestrator/agent-service).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStreamStateService implements StreamStateService {

    private static final String STOP_CHANNEL = "stream:stop";

    // User index (not in StreamRedisKeys - only used by conversation-service)
    private static final String USER_INDEX_PREFIX = "stream:user:";


    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ReactiveRedisMessageListenerContainer messageListenerContainer;

    // ==================== Stream Lifecycle ====================

    @Override
    public Mono<StreamMetadata> createStream(String userId, String conversationId, String model, String provider) {
        String streamId = UUID.randomUUID().toString();
        StreamMetadata metadata = StreamMetadata.create(streamId, userId, conversationId, model, provider);

        String key = streamKey(streamId);
        // Use HashMap instead of Map.of() to handle null values
        Map<String, String> fields = new HashMap<>();
        fields.put("streamId", metadata.streamId());
        fields.put("userId", metadata.userId());
        fields.put("conversationId", metadata.conversationId() != null ? metadata.conversationId() : "");
        fields.put("model", metadata.model() != null ? metadata.model() : "");
        fields.put("provider", metadata.provider() != null ? metadata.provider() : "");
        fields.put("state", metadata.state().name());
        fields.put("createdAt", metadata.createdAt().toString());
        fields.put("lastActivity", metadata.lastActivity().toString());
        fields.put("contentLength", String.valueOf(metadata.contentLength()));

        return redisTemplate.opsForHash().putAll(key, fields)
                .then(redisTemplate.expire(key, StreamRedisKeys.STREAM_TTL))
                // Maintain secondary indexes for O(1) lookup
                .then(indexByConversationId(conversationId, streamId))
                .then(indexByUserId(userId, streamId))
                .thenReturn(metadata)
                .doOnSuccess(m -> log.info("✅ [STREAM] Created stream: {} for conversation: {}", m.streamId(), conversationId))
                .doOnError(e -> log.error("❌ [STREAM] Failed to create stream: {}", e.getMessage()));
    }

    @Override
    public Mono<StreamMetadata> registerExternalStream(String streamId, String conversationId, String model, String provider) {
        StreamMetadata metadata = StreamMetadata.create(streamId, "internal", conversationId, model, provider);

        String key = streamKey(streamId);
        Map<String, String> fields = new HashMap<>();
        fields.put("streamId", streamId);
        fields.put("userId", "internal");
        fields.put("conversationId", conversationId != null ? conversationId : "");
        fields.put("model", model != null ? model : "");
        fields.put("provider", provider != null ? provider : "");
        fields.put("state", "STREAMING");
        fields.put("createdAt", metadata.createdAt().toString());
        fields.put("lastActivity", metadata.lastActivity().toString());
        fields.put("contentLength", "0");

        return redisTemplate.opsForHash().putAll(key, fields)
                .then(redisTemplate.expire(key, StreamRedisKeys.STREAM_TTL))
                .then(indexByConversationId(conversationId, streamId))
                .thenReturn(metadata)
                .doOnSuccess(m -> log.info("✅ [STREAM] Registered external stream: {} for conversation: {}", streamId, conversationId))
                .doOnError(e -> log.error("❌ [STREAM] Failed to register external stream: {}", e.getMessage()));
    }

    @Override
    public Mono<StreamMetadata> getMetadata(String streamId) {
        String key = streamKey(streamId);

        return redisTemplate.opsForHash().entries(key)
                .collectMap(entry -> entry.getKey().toString(), entry -> entry.getValue().toString())
                .filter(map -> !map.isEmpty())
                .map(this::parseMetadata)
                .doOnError(e -> log.error("❌ [STREAM] Failed to get metadata: {}", streamId, e));
    }

    @Override
    public Mono<StreamMetadata> getByConversationId(String conversationId) {
        log.info("🔍 [STREAM] Searching for stream by conversationId: {}", conversationId);

        String convIndexKey = convIndexKey(conversationId);

        // O(1) lookup via secondary index: stream:conv:{conversationId} → streamId
        return redisTemplate.opsForValue().get(convIndexKey)
                .flatMap(streamId -> getMetadata(streamId)
                        // Validate the metadata still matches this conversationId (guard against stale index)
                        .filter(m -> conversationId.equals(m.conversationId())))
                .doOnNext(m -> log.info("✅ [STREAM] Found stream: {} state: {} for conversation: {}",
                        m.streamId(), m.state(), conversationId))
                .doOnTerminate(() -> log.debug("🔍 [STREAM] Search completed for conversationId: {}", conversationId));
    }

    @Override
    public Flux<String> getStreamingConversationIds(String userId) {
        log.debug("🔍 [STREAM] Getting streaming conversations for user: {}", userId);

        String userIndexKey = userIndexKey(userId);

        // O(1) lookup via secondary index: stream:user:{userId} → SET of streamIds
        return redisTemplate.opsForSet().members(userIndexKey)
                .flatMap(streamId -> getMetadata(streamId)
                        .filter(m -> m.state().isActive())
                        .map(StreamMetadata::conversationId))
                .distinct();
    }

    @Override
    public Mono<Boolean> updateState(String streamId, StreamState newState) {
        String key = streamKey(streamId);

        return redisTemplate.opsForHash()
                .put(key, "state", newState.name())
                .flatMap(success -> redisTemplate.opsForHash()
                        .put(key, "lastActivity", Instant.now().toString()))
                .doOnSuccess(s -> log.debug("🔄 [STREAM] Updated state: {} -> {}", streamId, newState));
    }

    @Override
    public Mono<Boolean> complete(String streamId) {
        return updateState(streamId, StreamState.COMPLETED)
                .doOnSuccess(s -> log.info("✅ [STREAM] Completed: {}", streamId))
                // Schedule cleanup after 30 seconds (gives frontend time to receive final content)
                .flatMap(success -> scheduleCleanup(streamId, Duration.ofSeconds(30)).thenReturn(success));
    }

    @Override
    public Mono<Boolean> stop(String streamId) {
        return updateState(streamId, StreamState.STOPPED_BY_USER)
                .flatMap(success -> publishStop(streamId).thenReturn(success))
                .doOnSuccess(s -> log.info("🛑 [STREAM] Stopped: {}", streamId))
                // Schedule cleanup after 30 seconds
                .flatMap(success -> scheduleCleanup(streamId, Duration.ofSeconds(30)).thenReturn(success));
    }

    @Override
    public Mono<Boolean> setAwaitingApproval(String streamId) {
        return updateState(streamId, StreamState.AWAITING_APPROVAL)
                .doOnSuccess(s -> log.info("⏸️ [STREAM] Awaiting approval: {}", streamId))
                // No cleanup scheduled - user needs time to approve/deny
                // Longer TTL will be set to prevent premature expiration
                .flatMap(success -> redisTemplate.expire(streamKey(streamId), Duration.ofHours(1)).thenReturn(success));
    }

    @Override
    public Mono<Boolean> error(String streamId, String errorMessage) {
        String key = streamKey(streamId);

        return updateState(streamId, StreamState.ERROR)
                .flatMap(success -> redisTemplate.opsForHash()
                        .put(key, "errorMessage", errorMessage != null ? errorMessage : "Unknown error"))
                .doOnSuccess(s -> log.error("❌ [STREAM] Error: {} - {}", streamId, errorMessage))
                // Schedule cleanup after 30 seconds
                .flatMap(success -> scheduleCleanup(streamId, Duration.ofSeconds(30)).thenReturn(success));
    }

    /**
     * Schedule cleanup of stream keys after a delay.
     * This gives frontend time to receive final content before deletion.
     * Covers ALL keys: stream hash, content list, tools list, and cancel key.
     */
    private Mono<Boolean> scheduleCleanup(String streamId, Duration delay) {
        String streamKey = streamKey(streamId);
        String contentKey = contentKey(streamId);
        String toolsKey = toolsKey(streamId);
        String cancelKey = CANCEL_KEY_PREFIX + streamId;
        return redisTemplate.expire(streamKey, delay)
                .then(redisTemplate.expire(contentKey, delay))
                .then(redisTemplate.expire(toolsKey, delay))
                .then(redisTemplate.expire(cancelKey, delay))
                .doOnSuccess(v -> log.info("🧹 [STREAM] Scheduled cleanup in {}s for: {}", delay.getSeconds(), streamId))
                .thenReturn(true);
    }

    @Override
    public Mono<StreamState> getState(String streamId) {
        String key = streamKey(streamId);

        return redisTemplate.opsForHash().get(key, "state")
                .map(Object::toString)
                .map(StreamState::valueOf)
                .defaultIfEmpty(StreamState.ERROR);
    }

    @Override
    public Mono<Boolean> isActive(String streamId) {
        return getMetadata(streamId)
                .map(m -> m.state().isActive())
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Long> delete(String streamId) {
        String streamKey = streamKey(streamId);
        String contentKey = contentKey(streamId);
        String toolsKey = toolsKey(streamId);

        // Clean up secondary indexes before deleting stream keys
        return getMetadata(streamId)
                .flatMap(metadata -> {
                    Mono<Void> cleanConvIndex = (metadata.conversationId() != null && !metadata.conversationId().isEmpty())
                            ? redisTemplate.delete(convIndexKey(metadata.conversationId())).then()
                            : Mono.empty();
                    Mono<Void> cleanUserIndex = (metadata.userId() != null && !metadata.userId().isEmpty())
                            ? redisTemplate.opsForSet().remove(userIndexKey(metadata.userId()), streamId).then()
                            : Mono.empty();
                    return cleanConvIndex.then(cleanUserIndex);
                })
                .then(redisTemplate.delete(streamKey, contentKey, toolsKey, CANCEL_KEY_PREFIX + streamId))
                .doOnSuccess(count -> log.debug("🗑️ [STREAM] Deleted: {} (keys: {})", streamId, count));
    }

    // ==================== Content Management ====================

    @Override
    public Mono<Long> appendContent(String streamId, String chunk) {
        String contentKey = contentKey(streamId);
        String streamKey = streamKey(streamId);

        log.trace("📝 [STREAM] Appending {} chars to stream: {}", chunk.length(), streamId);

        return redisTemplate.opsForList().rightPush(contentKey, chunk)
                .flatMap(length -> {
                    // Trim if exceeds max chunks
                    if (length > StreamRedisKeys.MAX_CONTENT_CHUNKS) {
                        return redisTemplate.opsForList()
                                .trim(contentKey, length - StreamRedisKeys.MAX_CONTENT_CHUNKS, length)
                                .thenReturn(length);
                    }
                    return Mono.just(length);
                })
                .flatMap(length -> redisTemplate.expire(contentKey, StreamRedisKeys.STREAM_TTL).thenReturn(length))
                .flatMap(length -> {
                    // Update lastActivity and state to STREAMING
                    return redisTemplate.opsForHash()
                            .put(streamKey, "lastActivity", Instant.now().toString())
                            .then(redisTemplate.opsForHash().put(streamKey, "state", StreamState.STREAMING.name()))
                            .then(redisTemplate.opsForHash().put(streamKey, "contentLength", String.valueOf(length)))
                            .thenReturn(length);
                })
                .doOnSuccess(length -> log.debug("✅ [STREAM] Appended content to stream: {} (total chunks: {})", streamId, length))
                .doOnError(e -> log.error("❌ [STREAM] Failed to append content: {}", streamId, e));
    }

    @Override
    public Mono<String> getFullContent(String streamId) {
        String contentKey = contentKey(streamId);
        log.info("📖 [STREAM] getFullContent for stream: {}, key: {}", streamId, contentKey);

        return redisTemplate.opsForList().size(contentKey)
                .doOnNext(size -> log.info("📖 [STREAM] Content key {} has {} chunks", contentKey, size))
                .then(getContentChunks(streamId)
                        .reduce(new StringBuilder(), StringBuilder::append)
                        .map(StringBuilder::toString)
                        .doOnNext(content -> log.info("📖 [STREAM] getFullContent result: {} chars",
                                content != null ? content.length() : 0)));
    }

    @Override
    public Flux<String> getContentChunks(String streamId) {
        String contentKey = contentKey(streamId);
        log.debug("📖 [STREAM] getContentChunks for key: {}", contentKey);
        return redisTemplate.opsForList().range(contentKey, 0, -1)
                .doOnNext(chunk -> log.trace("📖 [STREAM] Chunk: {} chars", chunk != null ? chunk.length() : 0));
    }

    // ==================== Tool Events (for reconnection) ====================

    @Override
    public Mono<Long> appendToolEvent(String streamId, String toolEventJson) {
        String toolsKey = toolsKey(streamId);
        log.debug("🔧 [STREAM] Appending tool event to stream: {}", streamId);

        return redisTemplate.opsForList().rightPush(toolsKey, toolEventJson)
                .flatMap(length -> {
                    // Trim if exceeds max tool events (keep most recent)
                    if (length > StreamRedisKeys.MAX_TOOL_EVENTS) {
                        return redisTemplate.opsForList()
                                .trim(toolsKey, length - StreamRedisKeys.MAX_TOOL_EVENTS, length)
                                .thenReturn(length);
                    }
                    return Mono.just(length);
                })
                .flatMap(length -> redisTemplate.expire(toolsKey, StreamRedisKeys.STREAM_TTL).thenReturn(length))
                .doOnSuccess(length -> log.debug("✅ [STREAM] Tool event added to stream: {} (total: {})", streamId, length))
                .doOnError(e -> log.error("❌ [STREAM] Failed to append tool event: {}", streamId, e));
    }

    @Override
    public Flux<String> getToolEvents(String streamId) {
        String toolsKey = toolsKey(streamId);
        log.debug("🔧 [STREAM] Getting tool events for stream: {}", streamId);
        return redisTemplate.opsForList().range(toolsKey, 0, -1)
                .doOnNext(event -> log.trace("🔧 [STREAM] Tool event: {}", event));
    }

    // ==================== Stop Signal (Pub/Sub) ====================

    private static final String CANCEL_KEY_PREFIX = "agent:cancel:";
    private static final Duration CANCEL_KEY_TTL = Duration.ofMinutes(5);

    @Override
    public Mono<Boolean> setCancelKey(String streamId) {
        String key = CANCEL_KEY_PREFIX + streamId;
        return redisTemplate.opsForValue()
                .set(key, "true", CANCEL_KEY_TTL)
                .thenReturn(true)
                .doOnSuccess(s -> log.info("🛑 [STREAM] Set cancel key for remote agent: {}", streamId));
    }

    @Override
    public Mono<Long> publishStop(String streamId) {
        return redisTemplate.convertAndSend(STOP_CHANNEL, streamId)
                .doOnSuccess(receivers -> log.debug("📢 [STREAM] Published stop for {} to {} receivers", streamId, receivers));
    }

    @Override
    public Flux<String> subscribeToStop(String streamId) {
        return messageListenerContainer
                .receive(ChannelTopic.of(STOP_CHANNEL))
                .map(ReactiveSubscription.Message::getMessage)
                .filter(receivedStreamId -> receivedStreamId.equals(streamId))
                .doOnNext(id -> log.debug("📥 [STREAM] Received stop signal for: {}", id));
    }

    // ==================== Heartbeat / TTL ====================

    @Override
    public Mono<Boolean> touch(String streamId) {
        String streamKey = streamKey(streamId);
        String contentKey = contentKey(streamId);
        String toolsKey = toolsKey(streamId);

        return redisTemplate.expire(streamKey, StreamRedisKeys.STREAM_TTL)
                .flatMap(success -> redisTemplate.expire(contentKey, StreamRedisKeys.STREAM_TTL))
                .flatMap(success -> redisTemplate.expire(toolsKey, StreamRedisKeys.STREAM_TTL))
                .flatMap(success -> redisTemplate.opsForHash()
                        .put(streamKey, "lastActivity", Instant.now().toString()));
    }

    @Override
    public Mono<Boolean> updateConversationId(String streamId, String newConversationId) {
        String key = streamKey(streamId);

        // Get old conversationId to clean up stale index
        return redisTemplate.opsForHash().get(key, "conversationId")
                .map(Object::toString)
                .defaultIfEmpty("")
                .flatMap(oldConversationId -> {
                    // Delete old index if it existed
                    Mono<Boolean> deleteOldIndex = oldConversationId.isEmpty()
                            ? Mono.just(true)
                            : redisTemplate.delete(convIndexKey(oldConversationId)).thenReturn(true);

                    return deleteOldIndex
                            .then(redisTemplate.opsForHash().put(key, "conversationId", newConversationId))
                            .then(indexByConversationId(newConversationId, streamId));
                })
                .doOnSuccess(success -> log.info("✅ [STREAM] Updated conversationId for stream: {} -> {}",
                        streamId, newConversationId));
    }

    // ==================== Helper Methods ====================

    private String streamKey(String streamId) {
        return StreamRedisKeys.streamKey(streamId);
    }

    private String contentKey(String streamId) {
        return StreamRedisKeys.contentKey(streamId);
    }

    private String toolsKey(String streamId) {
        return StreamRedisKeys.toolsKey(streamId);
    }

    private String convIndexKey(String conversationId) {
        return StreamRedisKeys.convIndexKey(conversationId);
    }

    private String userIndexKey(String userId) {
        return USER_INDEX_PREFIX + userId;
    }

    /**
     * Index a stream by conversationId for O(1) lookup.
     * Stores: stream:conv:{conversationId} → streamId (STRING with TTL).
     */
    private Mono<Boolean> indexByConversationId(String conversationId, String streamId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return Mono.just(true);
        }
        String key = convIndexKey(conversationId);
        return redisTemplate.opsForValue().set(key, streamId)
                .then(redisTemplate.expire(key, StreamRedisKeys.STREAM_TTL));
    }

    /**
     * Index a stream by userId for O(1) lookup of active streams.
     * Stores: stream:user:{userId} → SET of streamIds (with TTL).
     * Stale entries are filtered at read time via metadata check.
     */
    private Mono<Boolean> indexByUserId(String userId, String streamId) {
        if (userId == null || userId.isEmpty()) {
            return Mono.just(true);
        }
        String key = userIndexKey(userId);
        return redisTemplate.opsForSet().add(key, streamId)
                .then(redisTemplate.expire(key, StreamRedisKeys.STREAM_TTL));
    }

    private StreamMetadata parseMetadata(Map<String, String> fields) {
        return new StreamMetadata(
                fields.get("streamId"),
                fields.get("userId"),
                fields.get("conversationId"),
                fields.get("model"),
                fields.get("provider"),
                StreamState.valueOf(fields.getOrDefault("state", "CREATED")),
                parseInstantOrNow(fields.get("createdAt")),
                parseInstantOrNow(fields.get("lastActivity")),
                Long.parseLong(fields.getOrDefault("contentLength", "0"))
        );
    }

    private Instant parseInstantOrNow(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}

package com.apimarketplace.conversation.streaming;

import java.time.Instant;

/**
 * Immutable metadata for a streaming session.
 * Stored in Redis as a HASH.
 */
public record StreamMetadata(
        String streamId,
        String userId,
        String conversationId,
        String model,
        String provider,
        StreamState state,
        Instant createdAt,
        Instant lastActivity,
        long contentLength
) {
    /**
     * Creates initial metadata for a new stream.
     */
    public static StreamMetadata create(String streamId, String userId, String conversationId, String model, String provider) {
        Instant now = Instant.now();
        return new StreamMetadata(
                streamId,
                userId,
                conversationId,
                model,
                provider,
                StreamState.CREATED,
                now,
                now,
                0
        );
    }

    /**
     * Creates a copy with updated state.
     */
    public StreamMetadata withState(StreamState newState) {
        return new StreamMetadata(
                streamId, userId, conversationId, model, provider,
                newState, createdAt, Instant.now(), contentLength
        );
    }

    /**
     * Creates a copy with updated content length.
     */
    public StreamMetadata withContentLength(long newLength) {
        return new StreamMetadata(
                streamId, userId, conversationId, model, provider,
                state, createdAt, Instant.now(), newLength
        );
    }
}

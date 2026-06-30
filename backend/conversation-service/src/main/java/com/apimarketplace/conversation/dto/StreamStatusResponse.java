package com.apimarketplace.conversation.dto;

import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamState;

import java.time.Instant;

/**
 * Response DTO for stream status endpoint.
 */
public record StreamStatusResponse(
        String streamId,
        String conversationId,
        String model,
        String provider,
        StreamState state,
        Instant createdAt,
        Instant lastActivity,
        long contentLength,
        boolean hasActiveStream,
        String timestamp
) {
    /**
     * Creates a response from stream metadata.
     */
    public static StreamStatusResponse from(StreamMetadata metadata) {
        return new StreamStatusResponse(
                metadata.streamId(),
                metadata.conversationId(),
                metadata.model(),
                metadata.provider(),
                metadata.state(),
                metadata.createdAt(),
                metadata.lastActivity(),
                metadata.contentLength(),
                metadata.state().isReconnectable(),
                Instant.now().toString()
        );
    }

    /**
     * Creates a response indicating no active stream for the conversation.
     * Returns 200 with hasActiveStream=false instead of 404.
     */
    public static StreamStatusResponse noActiveStream(String conversationId) {
        return new StreamStatusResponse(
                null,
                conversationId,
                null,
                null,
                null,
                null,
                null,
                0,
                false,
                Instant.now().toString()
        );
    }
}

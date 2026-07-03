package com.apimarketplace.conversation.streaming;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Service interface for stream state management.
 * Single source of truth for stream lifecycle and content.
 *
 * Design principles:
 * - All state is stored in a single location (Redis)
 * - No in-memory caching of state
 * - Content is streamed directly to storage
 */
public interface StreamStateService {

    // ==================== Stream Lifecycle ====================

    /**
     * Creates a new stream and returns its metadata.
     */
    Mono<StreamMetadata> createStream(String userId, String conversationId, String model, String provider);

    /**
     * Registers a stream with a pre-assigned streamId (for external callers like orchestrator/agent-service).
     * Creates the metadata hash and conv index using the given streamId instead of generating a new one.
     */
    default Mono<StreamMetadata> registerExternalStream(String streamId, String conversationId, String model, String provider) {
        return registerExternalStream(streamId, conversationId, model, provider, null);
    }

    /**
     * Registers an external stream attributed to the conversation OWNER. When
     * {@code ownerUserId} is a real user id, the stream is ALSO indexed under
     * {@code stream:user:{ownerUserId}} so {@code getStreamingConversationIds}
     * (the {@code /streams/active} reconnect probe) sees externally-driven runs
     * (workflow agent nodes, task assignees, sub-workflows). Without the owner
     * index the main chat page never auto-attaches to an in-flight external
     * stream: only the side panel (which subscribes unconditionally) showed it
     * live. Null/blank owner falls back to the legacy "internal" attribution
     * (metadata only, no user index).
     */
    Mono<StreamMetadata> registerExternalStream(String streamId, String conversationId, String model, String provider,
                                                String ownerUserId);

    /**
     * Gets the current metadata for a stream.
     */
    Mono<StreamMetadata> getMetadata(String streamId);

    /**
     * Gets stream metadata by conversation ID.
     * Returns the most recent stream for the conversation.
     */
    Mono<StreamMetadata> getByConversationId(String conversationId);

    /**
     * Gets all active streaming conversation IDs for a user.
     */
    Flux<String> getStreamingConversationIds(String userId);

    /**
     * Updates the stream state.
     */
    Mono<Boolean> updateState(String streamId, StreamState newState);

    /**
     * Marks the stream as completed.
     */
    Mono<Boolean> complete(String streamId);

    /**
     * Stops the stream (user-initiated).
     */
    Mono<Boolean> stop(String streamId);

    /**
     * Marks the stream as awaiting approval (paused for user action).
     */
    Mono<Boolean> setAwaitingApproval(String streamId);

    /**
     * Marks the stream as error.
     */
    Mono<Boolean> error(String streamId, String errorMessage);

    /**
     * Gets the current state of a stream.
     */
    Mono<StreamState> getState(String streamId);

    /**
     * Checks if stream is currently active (CREATED or STREAMING).
     */
    Mono<Boolean> isActive(String streamId);

    /**
     * Deletes stream data (cleanup).
     */
    Mono<Long> delete(String streamId);

    // ==================== Content Management ====================

    /**
     * Appends a content chunk to the stream buffer.
     * Also updates lastActivity timestamp.
     */
    Mono<Long> appendContent(String streamId, String chunk);

    /**
     * Gets all buffered content as a single string.
     */
    Mono<String> getFullContent(String streamId);

    /**
     * Gets content chunks for streaming.
     */
    Flux<String> getContentChunks(String streamId);

    // ==================== Tool Events (for reconnection) ====================

    /**
     * Appends a tool event (JSON) to the stream's tool list.
     * Used to replay tool_call and tool_result events on reconnection.
     */
    Mono<Long> appendToolEvent(String streamId, String toolEventJson);

    /**
     * Gets all tool events for a stream (for reconnection replay).
     */
    Flux<String> getToolEvents(String streamId);

    // ==================== Stop Signal (Pub/Sub) ====================

    /**
     * Sets the cancel key in Redis for remote agent execution.
     * Agent-service polls this key to detect stop requests.
     */
    Mono<Boolean> setCancelKey(String streamId);

    /**
     * Publishes a stop signal for a stream.
     * Used to notify all subscribers that the stream should stop.
     */
    Mono<Long> publishStop(String streamId);

    /**
     * Subscribes to stop signals for a stream.
     * Returns a Flux that emits when stop is requested.
     */
    Flux<String> subscribeToStop(String streamId);

    // ==================== Heartbeat / TTL ====================

    /**
     * Refreshes the TTL for a stream (called on activity).
     */
    Mono<Boolean> touch(String streamId);

    /**
     * Updates the conversationId for a stream.
     * Used when a real conversationId is assigned after stream creation.
     */
    Mono<Boolean> updateConversationId(String streamId, String newConversationId);
}

package com.apimarketplace.conversation.client;

import java.time.Duration;

/**
 * Shared Redis key schema for stream state management.
 * <p>
 * Single source of truth for key naming conventions used by:
 * <ul>
 *   <li>{@code RedisStreamStateService} (conversation-service) - full lifecycle</li>
 *   <li>{@code ConversationEventPublisher} (orchestrator-service) - workflow agent buffering</li>
 *   <li>{@code ConversationRedisStreamingCallback} (agent-service) - remote agent buffering</li>
 * </ul>
 * <p>
 * Redis data structures:
 * <ul>
 *   <li>HASH: {@code stream:{streamId}} - metadata (state, userId, conversationId, etc.)</li>
 *   <li>LIST: {@code stream:{streamId}:content} - content chunks</li>
 *   <li>LIST: {@code stream:{streamId}:tools} - tool event JSONs</li>
 *   <li>STRING: {@code stream:conv:{conversationId}} - index → streamId</li>
 * </ul>
 */
public final class StreamRedisKeys {

    private StreamRedisKeys() {}

    public static final Duration STREAM_TTL = Duration.ofMinutes(30);
    public static final Duration CLEANUP_TTL = Duration.ofMinutes(5);
    public static final int MAX_CONTENT_CHUNKS = 10000;
    public static final int MAX_TOOL_EVENTS = 500;

    private static final String STREAM_KEY_PREFIX = "stream:";
    private static final String CONTENT_KEY_SUFFIX = ":content";
    private static final String TOOLS_KEY_SUFFIX = ":tools";
    private static final String CONV_INDEX_PREFIX = "stream:conv:";

    public static String streamKey(String streamId) {
        return STREAM_KEY_PREFIX + streamId;
    }

    public static String contentKey(String streamId) {
        return STREAM_KEY_PREFIX + streamId + CONTENT_KEY_SUFFIX;
    }

    public static String toolsKey(String streamId) {
        return STREAM_KEY_PREFIX + streamId + TOOLS_KEY_SUFFIX;
    }

    public static String convIndexKey(String conversationId) {
        return CONV_INDEX_PREFIX + conversationId;
    }
}

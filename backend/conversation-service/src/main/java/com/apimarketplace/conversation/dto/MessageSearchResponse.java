package com.apimarketplace.conversation.dto;

import java.util.List;

/**
 * Response of a message search call.
 *
 * <p>Cursor-based pagination: when {@link #hasMore} is true, pass
 * {@link #nextCursor} back to the next call to fetch the following page.
 * No total count is returned - computing it on a large FTS result set is
 * expensive and rarely needed by callers.
 *
 * <p>{@link #scopeTruncated} is true when the user's conversation list
 * exceeded the per-call cap (typically 200) and the oldest conversations
 * were silently dropped from the search scope. Callers can warn the user
 * or recommend narrowing the search.
 */
public record MessageSearchResponse(
        List<MessageSearchHit> results,
        String nextCursor,
        boolean hasMore,
        int returnedCount,
        boolean scopeTruncated
) {
    public static MessageSearchResponse empty() {
        return new MessageSearchResponse(List.of(), null, false, 0, false);
    }
}

package com.apimarketplace.conversation.dto;

import java.time.Instant;
import java.util.List;

/**
 * Internal request shape used by {@code MessageSearchService.search}.
 *
 * <p>Caller is responsible for providing a list of authorized
 * {@code conversationIds}. The service performs no permission check -
 * the controllers (user / admin / internal) resolve the visible conversation
 * set before calling this.
 *
 * <p>{@code conversationIds} cannot be empty (caller short-circuits to an
 * empty response in that case to avoid unnecessary DB round-trips).
 *
 * @param conversationIds  authorized conversation IDs to search within
 * @param query            user-provided FTS query (plainto_tsquery syntax)
 * @param since            inclusive lower bound on {@code created_at}
 * @param until            inclusive upper bound on {@code created_at}
 * @param roles            filter by message role; null/empty means no filter
 * @param toolName         filter by tool_name (only relevant for role=tool)
 * @param limit            max results; clamped to 50 by the service
 * @param cursor           opaque cursor string from previous response
 */
public record MessageSearchRequest(
        List<String> conversationIds,
        String query,
        Instant since,
        Instant until,
        List<String> roles,
        String toolName,
        int limit,
        String cursor
) {}

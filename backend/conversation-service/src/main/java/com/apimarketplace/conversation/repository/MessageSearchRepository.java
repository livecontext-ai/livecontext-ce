package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.dto.MessageSearchHit;

import java.time.Instant;
import java.util.List;

/**
 * Custom repository for full-text search across conversation messages.
 *
 * <p>Implemented separately from {@link MessageRepository} because it
 * relies on PostgreSQL-specific operators ({@code @@}, {@code ts_rank},
 * {@code ts_headline}) that JPA cannot express natively. Implementation
 * lives in {@link MessageSearchRepositoryImpl}.
 *
 * <p>The caller MUST pre-filter conversation IDs to enforce permission;
 * this layer trusts {@code conversationIds} as the authorized scope.
 */
public interface MessageSearchRepository {

    /**
     * Search across the given conversations.
     *
     * <p>Results are ordered by {@code ts_rank DESC, created_at DESC, id DESC}.
     *
     * @param conversationIds  authorized conversations (must be non-empty)
     * @param query            FTS query (plainto_tsquery syntax)
     * @param since            optional lower bound on created_at
     * @param until            optional upper bound on created_at
     * @param roles            optional list of role names to include
     * @param toolName         optional filter on tool_name
     * @param includeInactive  if false, conversations with active=false are skipped
     * @param cursorTimestamp  optional cursor's created_at component
     * @param cursorId         optional cursor's message_id component
     * @param limit            max rows to return; the implementation requests
     *                          {@code limit + 1} to detect {@code hasMore}
     * @return list of hits with up to {@code limit + 1} entries
     */
    List<MessageSearchHit> search(
            List<String> conversationIds,
            String query,
            Instant since,
            Instant until,
            List<String> roles,
            String toolName,
            boolean includeInactive,
            Instant cursorTimestamp,
            String cursorId,
            int limit
    );
}

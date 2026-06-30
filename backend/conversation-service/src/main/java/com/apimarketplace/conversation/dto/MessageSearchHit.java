package com.apimarketplace.conversation.dto;

import java.time.Instant;

/**
 * One hit returned by message full-text search.
 *
 * The {@code excerpt} contains the matching content with terms wrapped in
 * the unicode brackets {@code U+27E6 ⟦} and {@code U+27E7 ⟧} (rather than
 * HTML tags) so the response stays clean for both UI and agent consumers.
 *
 * The UI maps {@code ⟦…⟧} to its own emphasis style; agents pass the
 * excerpt straight to the LLM with no escaping needed.
 *
 * <p>{@code createdAt} is an {@link Instant} (UTC moment) so JSON
 * serialisation always produces a zone-suffixed ISO-8601 string and the
 * cursor pagination is timezone-agnostic.
 */
public record MessageSearchHit(
        String messageId,
        String conversationId,
        String conversationTitle,
        String agentId,
        String executionId,
        String role,
        String toolName,
        String excerpt,
        Double rank,
        Instant createdAt
) {}

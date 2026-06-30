package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.MessageSearchHit;
import com.apimarketplace.conversation.dto.MessageSearchRequest;
import com.apimarketplace.conversation.dto.MessageSearchResponse;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageSearchRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Full-text search across conversation messages.
 *
 * <p>Three callers - user REST, admin REST, internal S2S - all funnel here.
 * The service does NOT perform permission checks: callers MUST pre-filter
 * the conversation IDs they pass in. This is intentional so the same
 * service can serve every entry point with the same SQL.
 *
 * <p>Limits enforced here:
 * <ul>
 *   <li>{@code query} required, max 500 chars.</li>
 *   <li>{@code limit} clamped to {@value #MAX_LIMIT}.</li>
 *   <li>{@code conversationIds} max {@value #MAX_CONVERSATION_IDS} entries.</li>
 *   <li>An empty {@code conversationIds} returns an empty response without
 *       hitting the database.</li>
 * </ul>
 */
@Service
public class MessageSearchService {

    private static final Logger log = LoggerFactory.getLogger(MessageSearchService.class);

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    public static final int MAX_QUERY_LENGTH = 500;
    public static final int MAX_CONVERSATION_IDS = 200;

    private static final String CURSOR_PREFIX = "v1:";

    private final MessageSearchRepository searchRepository;
    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;

    public MessageSearchService(
            MessageSearchRepository searchRepository,
            ConversationRepository conversationRepository,
            ObjectMapper objectMapper) {
        this.searchRepository = searchRepository;
        this.conversationRepository = conversationRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolved scope for a user - list of conversation IDs plus a flag
     * indicating whether older conversations were silently dropped because
     * the user has more than {@value #MAX_CONVERSATION_IDS} active
     * conversations.
     *
     * @param conversationIds the conversation IDs to search within
     * @param truncated       true if the original list exceeded the cap
     */
    public record ResolvedScope(List<String> conversationIds, boolean truncated) {}

    /**
     * Resolve all active conversation IDs owned by the given user.
     * Used by the user REST and admin REST controllers to scope the search.
     *
     * <p>This list can grow large for power users; callers should pre-filter
     * by {@code conversationId} when possible. We hard-cap at
     * {@value #MAX_CONVERSATION_IDS} entries by sorting by {@code updatedAt}
     * desc and taking the head - the {@link ResolvedScope#truncated()} flag
     * tells callers when this happened.
     */
    /**
     * Post-V261 strict-isolation full-text search scope. Every conversation
     * row has a non-null {@code organization_id} (personal workspace = the
     * caller's personal org UUID, team workspace = the active org), so the
     * org-strict finder serves both surfaces.
     */
    @Transactional(readOnly = true)
    public ResolvedScope resolveScopeForUser(String userId, String organizationId, boolean includeInactive) {
        TenantResolver.requireOrgId(organizationId);
        var pageable = org.springframework.data.domain.PageRequest.of(0, MAX_CONVERSATION_IDS);
        var page = includeInactive
                ? conversationRepository.findByOrganizationIdStrictOrderByUpdatedAtDesc(organizationId, pageable)
                : conversationRepository.findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(organizationId, pageable);

        // Spring's Page#getTotalElements gives us the underlying total without
        // a second COUNT (it's already computed by the page query) so we can
        // know whether the cap clipped the head.
        boolean truncated = page.getTotalElements() > MAX_CONVERSATION_IDS;
        List<String> ids = page.getContent().stream().map(c -> c.getId()).toList();
        return new ResolvedScope(ids, truncated);
    }

    /**
     * Helper kept for callers that don't need the truncation flag (and for
     * existing tests). Delegates to {@link #resolveScopeForUser}.
     */
    @Transactional(readOnly = true)
    public List<String> resolveConversationIdsForUser(String userId, String organizationId, boolean includeInactive) {
        return resolveScopeForUser(userId, organizationId, includeInactive).conversationIds();
    }

    /**
     * Execute a search for the given pre-authorized scope.
     *
     * @param scopeTruncated  true when the caller's scope was clipped at
     *                         {@value #MAX_CONVERSATION_IDS} conversations
     *                         (propagated to the response so the UI/agent
     *                         can warn the user or refine the query)
     */
    @Transactional(readOnly = true)
    public MessageSearchResponse search(MessageSearchRequest request,
                                        boolean includeInactive,
                                        boolean scopeTruncated) {
        validate(request);

        if (request.conversationIds().isEmpty()) {
            return new MessageSearchResponse(List.of(), null, false, 0, scopeTruncated);
        }

        int limit = clampLimit(request.limit());
        Cursor cursor = decodeCursor(request.cursor());

        // Request limit + 1 to detect hasMore without a separate COUNT query.
        List<MessageSearchHit> hits = searchRepository.search(
                request.conversationIds(),
                request.query().trim(),
                request.since(),
                request.until(),
                request.roles(),
                request.toolName(),
                includeInactive,
                cursor == null ? null : cursor.timestamp(),
                cursor == null ? null : cursor.id(),
                limit + 1
        );

        boolean hasMore = hits.size() > limit;
        List<MessageSearchHit> trimmed = hasMore ? hits.subList(0, limit) : hits;

        String nextCursor = null;
        if (hasMore && !trimmed.isEmpty()) {
            MessageSearchHit last = trimmed.get(trimmed.size() - 1);
            nextCursor = encodeCursor(new Cursor(last.createdAt(), last.messageId()));
        }

        return new MessageSearchResponse(trimmed, nextCursor, hasMore, trimmed.size(), scopeTruncated);
    }

    /**
     * Backward-compatible overload for callers that don't track truncation
     * (e.g. internal S2S where the caller already pre-resolved its scope).
     */
    @Transactional(readOnly = true)
    public MessageSearchResponse search(MessageSearchRequest request, boolean includeInactive) {
        return search(request, includeInactive, false);
    }

    // -------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------

    private void validate(MessageSearchRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        if (request.query().length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                    "query length exceeds " + MAX_QUERY_LENGTH + " characters");
        }
        if (request.conversationIds() == null) {
            throw new IllegalArgumentException("conversationIds must not be null");
        }
        if (request.conversationIds().size() > MAX_CONVERSATION_IDS) {
            throw new IllegalArgumentException(
                    "conversationIds exceeds max of " + MAX_CONVERSATION_IDS);
        }
        if (request.since() != null && request.until() != null
                && request.since().isAfter(request.until())) {
            throw new IllegalArgumentException("since must be <= until");
        }
    }

    private static int clampLimit(int requested) {
        if (requested <= 0) return DEFAULT_LIMIT;
        return Math.min(requested, MAX_LIMIT);
    }

    // -------------------------------------------------------------------
    // Cursor encoding
    // -------------------------------------------------------------------

    /**
     * Cursor is opaque to callers: prefix-versioned, base64(JSON).
     * Format: {@code v1:base64({"ts":"<iso>","id":"<uuid>"})}.
     * The version prefix lets us evolve the encoding without breaking old clients.
     */
    record Cursor(Instant timestamp, String id) {}

    private String encodeCursor(Cursor cursor) {
        try {
            String json = objectMapper.writeValueAsString(
                    Map.of("ts", cursor.timestamp().toString(), "id", cursor.id()));
            String b64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            return CURSOR_PREFIX + b64;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode cursor", e);
        }
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        if (!cursor.startsWith(CURSOR_PREFIX)) {
            log.debug("Ignoring cursor with unknown prefix: {}", cursor);
            return null;
        }
        String b64 = cursor.substring(CURSOR_PREFIX.length());
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(b64);
            String json = new String(decoded, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, String> parsed = objectMapper.readValue(json, Map.class);
            return new Cursor(Instant.parse(parsed.get("ts")), parsed.get("id"));
        } catch (IllegalArgumentException | JsonProcessingException | DateTimeParseException e) {
            log.debug("Ignoring malformed cursor", e);
            return null;
        }
    }
}

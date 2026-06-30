package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.dto.MessageSearchRequest;
import com.apimarketplace.conversation.dto.MessageSearchResponse;
import com.apimarketplace.conversation.service.MessageSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * User-scoped full-text search across conversation messages.
 *
 * <p>Scope is implicit: the caller's own conversations (resolved via
 * {@code X-User-ID} header). Inactive conversations are excluded; there is
 * no opt-in for users - that capability is reserved for the admin endpoint.
 */
@RestController
@RequestMapping("/api/conversations/messages")
@CrossOrigin(origins = "*")
public class MessageSearchController {

    private static final Logger logger = LoggerFactory.getLogger(MessageSearchController.class);

    private final MessageSearchService messageSearchService;

    public MessageSearchController(MessageSearchService messageSearchService) {
        this.messageSearchService = messageSearchService;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam("query") String query,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "roles", required = false) String roles,
            @RequestParam(value = "toolName", required = false) String toolName,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        try {
            // PR21 R2 - strict-isolation search scope. Reviewer A round-1 caught
            // the pre-R2 leak: a team-workspace user running message search saw
            // personal-scope conversation IDs in the returned scope.
            var scope = messageSearchService.resolveScopeForUser(userId, organizationId, false);

            MessageSearchRequest request = new MessageSearchRequest(
                    scope.conversationIds(),
                    query,
                    parseTimestamp(since),
                    parseTimestamp(until),
                    splitCsv(roles),
                    toolName,
                    limit,
                    cursor
            );
            MessageSearchResponse response = messageSearchService.search(request, false, scope.truncated());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid message search request from user={}: {}", userId, e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /**
     * Parse an ISO-8601 timestamp into a UTC {@link Instant}.
     * Accepts both zoned ({@code 2026-04-23T10:00:00Z}) and offset
     * ({@code 2026-04-23T10:00:00+02:00}) forms; bare local times are
     * rejected to avoid silent timezone drift.
     */
    private static Instant parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            try {
                // Tolerate offset form by parsing via OffsetDateTime then converting
                return java.time.OffsetDateTime.parse(iso).toInstant();
            } catch (Exception e2) {
                throw new IllegalArgumentException(
                        "invalid timestamp '" + iso + "' - must be ISO-8601 with zone (e.g. 2026-04-23T10:00:00Z)");
            }
        }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}

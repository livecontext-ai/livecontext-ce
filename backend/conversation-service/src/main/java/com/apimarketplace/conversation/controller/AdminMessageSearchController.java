package com.apimarketplace.conversation.controller;

import com.apimarketplace.common.web.AdminRoleGuard;
import com.apimarketplace.conversation.dto.MessageSearchRequest;
import com.apimarketplace.conversation.dto.MessageSearchResponse;
import com.apimarketplace.conversation.entity.AdminSearchAudit;
import com.apimarketplace.conversation.repository.AdminSearchAuditRepository;
import com.apimarketplace.conversation.service.MessageSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin search across messages for a target user/tenant.
 *
 * <p>Authorization: caller must hold the {@code ADMIN} role (verified via
 * {@code X-User-Roles} header injected by the gateway). The {@code userId}
 * parameter is REQUIRED and must identify a single target - wildcards are
 * not supported on purpose.
 *
 * <p>Every successful call writes a row to
 * {@code conversation.admin_search_audit} so we can answer "who searched
 * what on whom?" during incident response.
 */
@RestController
@RequestMapping("/api/admin/conversations/messages")
@CrossOrigin(origins = "*")
public class AdminMessageSearchController {

    private static final Logger logger = LoggerFactory.getLogger(AdminMessageSearchController.class);

    private final MessageSearchService messageSearchService;
    private final AdminSearchAuditRepository auditRepository;

    public AdminMessageSearchController(
            MessageSearchService messageSearchService,
            AdminSearchAuditRepository auditRepository) {
        this.messageSearchService = messageSearchService;
        this.auditRepository = auditRepository;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestHeader(value = "X-User-ID") String adminUserId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestParam("userId") String targetUserId,
            // Post-V261 sweep - every conversation row has a non-null organization_id,
            // so admin must specify which workspace of the target user to search. Without
            // this header the scope resolver fails fast (no IS NULL branch any more).
            @RequestParam("targetOrganizationId") String targetOrganizationId,
            @RequestParam("query") String query,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "rolesFilter", required = false) String rolesFilter,
            @RequestParam(value = "toolName", required = false) String toolName,
            @RequestParam(value = "includeInactive", defaultValue = "false") boolean includeInactive,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "cursor", required = false) String cursor) {

        ResponseEntity<Map<String, Object>> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        if (targetUserId == null || targetUserId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }
        if (targetOrganizationId == null || targetOrganizationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "targetOrganizationId is required (post-V261, every conversation lives in a workspace)"));
        }

        // Audit row is written EAGERLY with result_count=null so even if the
        // search throws (DB blip, persistence error), an attempted admin
        // search is always logged. The row is then updated with the actual
        // count on success.
        Long auditId = writeInitialAuditRow(adminUserId, targetUserId, query,
                conversationId, since, until, rolesFilter, toolName, includeInactive);

        try {
            // Resolve scope to target user's conversations, optionally narrowed
            // to a single conversation. The narrowing is verified to belong to
            // the target user - admins cannot pull a conversation from a
            // different user by guessing its ID.
            var scope = messageSearchService.resolveScopeForUser(targetUserId, targetOrganizationId, includeInactive);
            List<String> conversationIds = scope.conversationIds();
            boolean scopeTruncated = scope.truncated();

            if (conversationId != null && !conversationId.isBlank()) {
                if (!conversationIds.contains(conversationId)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "conversationId does not belong to userId"));
                }
                conversationIds = List.of(conversationId);
                scopeTruncated = false; // single-conversation request, no truncation possible
            }

            MessageSearchRequest request = new MessageSearchRequest(
                    conversationIds,
                    query,
                    parseTimestamp(since),
                    parseTimestamp(until),
                    splitCsv(rolesFilter),
                    toolName,
                    limit,
                    cursor
            );
            MessageSearchResponse response = messageSearchService.search(
                    request, includeInactive, scopeTruncated);

            updateAuditResultCount(auditId, response.returnedCount());

            logger.info("[AdminSearch] admin={} target={} query='{}' results={}",
                    adminUserId, targetUserId, truncate(query, 80), response.returnedCount());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid admin search request from admin={}: {}",
                    adminUserId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Write the audit row before running the search, with {@code result_count=null}.
     * Returns the audit row id so we can update it on success. If the audit insert
     * itself fails, returns {@code null} - the caller still proceeds with the search
     * (audit best-effort) but the failure is logged loudly.
     */
    private Long writeInitialAuditRow(String adminUserId, String targetUserId, String query,
                                       String conversationId, String since, String until,
                                       String rolesFilter, String toolName,
                                       boolean includeInactive) {
        Map<String, Object> filters = new HashMap<>();
        if (conversationId != null) filters.put("conversationId", conversationId);
        if (since != null) filters.put("since", since);
        if (until != null) filters.put("until", until);
        if (rolesFilter != null) filters.put("roles", rolesFilter);
        if (toolName != null) filters.put("toolName", toolName);
        filters.put("includeInactive", includeInactive);

        try {
            AdminSearchAudit saved = auditRepository.save(new AdminSearchAudit(
                    adminUserId, targetUserId, query, filters, null));
            return saved.getId();
        } catch (Exception e) {
            logger.error("[AdminSearch] failed to write initial audit row for admin={} target={}",
                    adminUserId, targetUserId, e);
            return null;
        }
    }

    /**
     * Update the audit row with the search's result count. Best-effort: if it
     * fails, the audit row keeps {@code result_count=null} which is still
     * sufficient to know an attempt happened.
     */
    private void updateAuditResultCount(Long auditId, int resultCount) {
        if (auditId == null) return;
        try {
            auditRepository.findById(auditId).ifPresent(row -> {
                row.setResultCount(resultCount);
                auditRepository.save(row);
            });
        } catch (Exception e) {
            logger.error("[AdminSearch] failed to update audit row id={} with count",
                    auditId, e);
        }
    }

    private static Instant parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            try {
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

package com.apimarketplace.orchestrator.controllers.notification;

import com.apimarketplace.orchestrator.services.notification.NotificationService;
import com.apimarketplace.orchestrator.services.notification.NotificationService.BucketRef;
import com.apimarketplace.orchestrator.services.notification.NotificationsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST surface for the notification bell.
 *
 * <ul>
 *   <li>{@code GET  /api/notifications?page=N&size=M} → paginated bell payload + unread count</li>
 *   <li>{@code POST /api/notifications/read}          → mark all as read</li>
 *   <li>{@code POST /api/notifications/delete-batch}  → bulk-delete buckets (single + clear-page)</li>
 * </ul>
 *
 * Tenant isolation via the gateway-injected {@code X-User-ID} header.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    /**
     * Defense-in-depth allow-list of known bell categories. Mirrors the
     * constants emitted by {@code NotificationEmitter} and cross-service
     * notification producers ({@code TriggerLifecycleManager},
     * {@code AgentTaskService}, {@code OAuth2RefreshScheduler},
     * {@code OrganizationMemberService}).
     * The DELETE service path is already scope-bounded (WHERE clause uses
     * orgId / tenantId), but rejecting unknown categories at the controller
     * layer keeps a future caller from passing an attacker-controlled string
     * straight into the JPQL parameter binder.
     *
     * <p><strong>Keep in sync with every producer.</strong> A category that
     * a producer emits (so it appears in the bell) but that is missing here is
     * silently stripped from delete-batch, making that row impossible to
     * delete - the optimistic removal in the frontend is reverted by the
     * settle refetch and the row reappears. {@code AGENT_TASK_AWAITING_REVIEW}
     * was exactly that drift.
     */
    private static final Set<String> KNOWN_CATEGORIES = Set.of(
            "RUN_FAILED", "APPROVAL_PENDING", "CRED_EXPIRED", "WEBHOOK_TRIGGER_DISABLED",
            "AGENT_TASK_ASSIGNED", "AGENT_TASK_AWAITING_REVIEW", "BRIDGE_LOW_CREDIT",
            "ORG_INVITATION_PENDING");

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Returns up to {@code size} aggregated buckets at page {@code page}
     * (zero-based). {@code unreadCount} is the global count across ALL pages.
     *
     * <p>V220: {@code X-Organization-ID} threads the active workspace into
     * the service so the bell is scope-aware. Post-V261 (2026-05-19) the
     * gateway always injects this header (personal workspaces resolve to
     * the user's default personal org), so every workspace flows through
     * the strict-org branch.
     */
    @GetMapping
    public ResponseEntity<NotificationsResponse> getNotifications(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String activeOrgId,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "15") int size) {
        return ResponseEntity.ok(notificationService.getNotifications(tenantId, activeOrgId, page, size));
    }

    @PostMapping("/read")
    public ResponseEntity<Map<String, Object>> markAllAsRead(
            @RequestHeader("X-User-ID") String tenantId) {
        // Read-state cursor is per-user (single timestamp in
        // notification_read_state), not per-workspace. Mark-all-read across
        // workspaces is the user-friendly default - the alternative
        // (per-org cursor) would force the user to "mark read" each
        // workspace independently for no UX benefit.
        notificationService.markAllAsRead(tenantId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Bulk-delete by bucket. Body is a list of {@code {subjectId, category}}.
     * Single-row delete is just a 1-element list. Empty list → no-op (200 OK,
     * deleted=0). Out-of-scope rows are silently filtered server-side per
     * V220 scope predicate (org or personal).
     */
    @PostMapping("/delete-batch")
    public ResponseEntity<Map<String, Object>> deleteBatch(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String activeOrgId,
            @RequestBody List<BucketRef> buckets) {
        // Defense-in-depth: strip buckets whose category is not in the known
        // allow-list. The service layer's scope predicate (org / tenant)
        // already prevents cross-workspace deletes, but rejecting unknown
        // categories at the edge keeps a typoed or attacker-controlled body
        // from reaching the JPQL parameter binder. null buckets list → pass
        // through (service handles empty / null as a 0-row no-op).
        List<BucketRef> filtered = buckets == null ? null : buckets.stream()
                .filter(b -> b != null && b.category() != null && KNOWN_CATEGORIES.contains(b.category()))
                .toList();
        int deleted = notificationService.deleteBuckets(tenantId, activeOrgId, filtered);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }
}

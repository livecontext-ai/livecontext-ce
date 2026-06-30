package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.OrganizationService;
import com.apimarketplace.auth.service.WorkspacePauseReconciler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal ops hook to force a workspace pause reconciliation, on demand, bypassing the wait for the
 * CE-only {@code WorkspacePauseReconcileScheduler} sweep. Re-applies the owner's plan
 * {@code max_workspaces} cap: pauses over-cap workspaces, un-pauses any now back within budget.
 *
 * <p>Used by ops (force a re-apply after a manual plan change) and by e2e (trigger the pause/unpause
 * deterministically instead of waiting for the scheduler). Lives under {@code /api/internal/*} so the
 * gateway never routes it from external traffic (service-to-service / admin only). Idempotent.
 */
@RestController
@RequestMapping("/api/internal/auth/workspace")
public class WorkspaceReconcileInternalController {

    private final OrganizationService organizationService;
    private final WorkspacePauseReconciler reconciler;

    public WorkspaceReconcileInternalController(OrganizationService organizationService,
                                                WorkspacePauseReconciler reconciler) {
        this.organizationService = organizationService;
        this.reconciler = reconciler;
    }

    /** Reconcile the pause state of one owner's workspaces against their current plan cap. */
    @PostMapping("/reconcile/{userId}")
    public ResponseEntity<Map<String, Object>> reconcile(@PathVariable Long userId) {
        organizationService.reconcileWorkspacePauseState(userId);
        return ResponseEntity.ok(Map.of("userId", userId, "reconciled", true));
    }

    /** Reconcile every multi-workspace owner (the same sweep the CE scheduler runs). */
    @PostMapping("/reconcile-all")
    public ResponseEntity<Map<String, Object>> reconcileAll() {
        int processed = reconciler.reconcileAll();
        return ResponseEntity.ok(Map.of("processed", processed));
    }
}

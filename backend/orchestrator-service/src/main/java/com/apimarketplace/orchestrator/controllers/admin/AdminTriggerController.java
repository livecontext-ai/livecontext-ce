package com.apimarketplace.orchestrator.controllers.admin;

import com.apimarketplace.common.web.AdminRoleGuard;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Round-7 PR6: admin endpoints that close the operator triage loop.
 *
 * <p>Two-minute SLO from {@code the project docs}: when an oncall
 * sees the {@code trigger_dispatch_total{verdict="REFUSE_*"}} counter spike, they need
 * a one-click way to (a) inspect which workflow is affected and (b) re-arm it. PR6
 * delivers exactly that.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/admin/workflows/{id}/rearm} - invoke
 *       {@code WorkflowPinService.rearm} to re-point production_run_id at the latest
 *       TRUSTED run (or clear it). Used after a misbehaving run was force-cancelled
 *       and the operator wants to bring the schedule back online without re-pinning.</li>
 * </ul>
 *
 * <p>Authorization: {@code ADMIN} role via the existing {@link AdminRoleGuard} pattern
 * (X-User-Roles header injected by gateway). Round-7 simplification removed the
 * {@code PLATFORM_ADMIN} tier proposed in earlier rounds - the existing ADMIN role
 * already covers all admin trigger ops.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminTriggerController {

    private static final Logger logger = LoggerFactory.getLogger(AdminTriggerController.class);

    private final WorkflowPinService pinService;

    public AdminTriggerController(WorkflowPinService pinService) {
        this.pinService = pinService;
    }

    /**
     * Force a workflow's production_run_id to be re-resolved against the latest
     * TRUSTED run at the pinned version. Used by the oncall when a schedule is
     * stuck after a manual run cancellation or when the RunTerminationListener's
     * AFTER_COMMIT phase races with a concurrent admin action.
     *
     * <p>Returns:
     * <ul>
     *   <li>200 + {"rearmed": true}  - production_run_id pointed at a fresh TRUSTED run</li>
     *   <li>200 + {"rearmed": false} - workflow unpinned OR no TRUSTED run survives;
     *                                  production_run_id is now NULL. Caller may need
     *                                  to start a new manual run before re-trying.</li>
     *   <li>403                       - caller is not ADMIN</li>
     * </ul>
     */
    @PostMapping("/workflows/{workflowId}/rearm")
    public ResponseEntity<?> rearmWorkflow(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable UUID workflowId) {
        ResponseEntity<Map<String, Object>> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        boolean rearmed = pinService.rearm(workflowId);
        logger.info("[AdminTrigger] rearm workflow={} result={}", workflowId,
            rearmed ? "rearmed" : "cleared");
        return ResponseEntity.ok(Map.of(
            "workflowId", workflowId.toString(),
            "rearmed", rearmed
        ));
    }
}

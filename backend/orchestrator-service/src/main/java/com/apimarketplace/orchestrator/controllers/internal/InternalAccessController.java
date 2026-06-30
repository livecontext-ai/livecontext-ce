package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.scope.TolerantScope;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

/**
 * Internal endpoints for the Gateway to verify user access to resources
 * and trigger state snapshots for WebSocket reconnection.
 * Protected by X-Gateway-Secret header (verified by GatewaySecurityFilter).
 */
@RestController
@RequestMapping("/api/internal")
public class InternalAccessController {

    private static final Logger log = LoggerFactory.getLogger(InternalAccessController.class);

    private final WorkflowRunRepository runRepository;
    private final WorkflowRepository workflowRepository;
    private final SnapshotService snapshotService;

    public InternalAccessController(WorkflowRunRepository runRepository,
                                    WorkflowRepository workflowRepository,
                                    SnapshotService snapshotService) {
        this.runRepository = runRepository;
        this.workflowRepository = workflowRepository;
        this.snapshotService = snapshotService;
    }

    /**
     * Check if a user has access to a workflow run.
     * Used by the Gateway's ChannelAuthorizer for WebSocket channel subscriptions.
     */
    @GetMapping("/runs/{runId}/access")
    @TolerantScope(reason = "Gateway ChannelAuthorizer for WS subscriptions - gateway has already validated session.organizationId is a real membership for userId, so owner-OR-org access is intentional and matches the user's authority across workspaces")
    public ResponseEntity<Boolean> checkRunAccess(@PathVariable String runId,
                                                  @RequestParam String userId,
                                                  @RequestParam(required = false) String orgId) {
        Optional<WorkflowRunEntity> run = runRepository.findByRunIdPublic(runId);
        if (run.isEmpty()) {
            return ResponseEntity.ok(false);
        }
        WorkflowRunEntity r = run.get();
        boolean hasAccess = ScopeGuard.isInOwnerOrOrgScope(
                userId, orgId, r.getTenantId(), r.getOrganizationId());
        log.debug("Run access check: runId={}, userId={}, orgId={}, hasAccess={}",
                runId, userId, orgId, hasAccess);
        return ResponseEntity.ok(hasAccess);
    }

    /**
     * Trigger a state snapshot re-publish to Redis for a workflow run.
     * Called by the Gateway when a WebSocket client subscribes with requestSnapshot=true.
     * The snapshot is published to Redis channel ws:workflow:run:{runId} and forwarded
     * to the subscribing client via the RedisChannelBridge.
     */
    @PostMapping("/runs/{runId}/snapshot")
    public ResponseEntity<Void> triggerSnapshot(@PathVariable String runId) {
        log.debug("Snapshot trigger requested for runId={}", runId);
        try {
            snapshotService.sendSnapshotImmediate(runId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.warn("Snapshot trigger failed for runId={}: {}", runId, e.getMessage());
            return ResponseEntity.ok().build(); // Don't fail - snapshot is best-effort
        }
    }

    /**
     * Check if a user has access to a workflow.
     * Used by the Gateway's ChannelAuthorizer for WebSocket channel subscriptions.
     */
    @GetMapping("/workflows/{workflowId}/access")
    @TolerantScope(reason = "Gateway ChannelAuthorizer for collab WS channel - gateway has already validated session.organizationId is a real membership for userId; owner-OR-org access lets the user subscribe to channels for their workflows across workspaces")
    public ResponseEntity<Boolean> checkWorkflowAccess(@PathVariable String workflowId,
                                                       @RequestParam String userId,
                                                       @RequestParam(required = false) String orgId) {
        try {
            UUID id = UUID.fromString(workflowId);
            return workflowRepository.findById(id)
                    .map(wf -> ResponseEntity.ok(ScopeGuard.isInOwnerOrOrgScope(
                            userId, orgId, wf.getTenantId(), wf.getOrganizationId())))
                    .orElse(ResponseEntity.ok(false));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(false);
        }
    }
}

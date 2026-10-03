package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.scope.TolerantScope;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.interfaces.InterfacePlanExtractor;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.Set;
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
    private final InterfacePlanExtractor interfacePlanExtractor;
    private final StorageService storageService;

    public InternalAccessController(WorkflowRunRepository runRepository,
                                    WorkflowRepository workflowRepository,
                                    SnapshotService snapshotService,
                                    InterfacePlanExtractor interfacePlanExtractor,
                                    StorageService storageService) {
        this.runRepository = runRepository;
        this.workflowRepository = workflowRepository;
        this.snapshotService = snapshotService;
        this.interfacePlanExtractor = interfacePlanExtractor;
        this.storageService = storageService;
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

    /**
     * CASA LC-037 (gap 1): does {@code workflowId}'s CURRENT plan reference {@code interfaceId}?
     * Used by interface-service to bind a bare {@code GET /api/interfaces/{id}} to the ONE
     * workflow an APPLICATION share token actually covers (the share resolves the visitor to
     * the OWNER's identity, so a plain org-scope check on the interface row would otherwise
     * authorize the visitor against every interface the owner has ever built - see
     * {@code com.apimarketplace.common.web.ShareContextResourceBinding}'s javadoc).
     * {@code InterfaceEntity.sourceWorkflowId} is never populated on creation, so membership
     * can only be answered by re-deriving it from the plan, the same way
     * {@code WorkflowExecutionService.snapshotInterfacesForRun} does.
     *
     * <p>No scope/ownership check: this is a pure existence oracle over two ids the caller
     * already possesses (both came from server-derived headers or the interface's own path
     * variable), so it reveals nothing an unauthorized caller could not already infer, exactly
     * like {@link #checkWorkflowAccess}. An unknown workflow id answers {@code false}.
     *
     * <p><b>Round-3 audit decision:</b> deliberately reads the workflow's CURRENT (live) plan,
     * not a frozen run plan - unlike {@code InterfaceRenderService#isInterfaceReferencedByRun}
     * (the run-bound sibling check, gating {@code GET /api/interfaces/{id}/render}), this
     * endpoint backs the bare {@code GET /api/interfaces/{id}} which carries no {@code runId} at
     * all: there is no frozen plan to prefer. "Is this interface reachable from the shared
     * workflow at all" is answered correctly by the live/published plan, since a share visitor
     * loading the bare interface definition (template/config, not a run's resolved data) should
     * see the SAME membership the owner's own builder currently shows, not a stale one pinned to
     * whichever run happened to start first.
     */
    @GetMapping("/workflows/{workflowId}/interfaces/{interfaceId}/referenced")
    public ResponseEntity<Boolean> isInterfaceReferencedByWorkflow(@PathVariable String workflowId,
                                                                    @PathVariable String interfaceId) {
        UUID workflowUuid;
        UUID interfaceUuid;
        try {
            workflowUuid = UUID.fromString(workflowId);
            interfaceUuid = UUID.fromString(interfaceId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(false);
        }
        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowUuid);
        if (workflowOpt.isEmpty()) {
            return ResponseEntity.ok(false);
        }
        WorkflowPlan plan = WorkflowPlan.fromMap(workflowOpt.get().getPlan(), workflowId, workflowOpt.get().getTenantId());
        Set<UUID> interfaceIds = interfacePlanExtractor.extractInterfaceIds(plan);
        return ResponseEntity.ok(interfaceIds.contains(interfaceUuid));
    }

    /**
     * CASA LC-037 (gap 2): was {@code childRunId} genuinely invoked as a {@code core:sub_workflow}
     * child by SOME run of {@code workflowId}? Used by storage-service to extend an APPLICATION
     * share's file access to a file produced inside a sub-workflow: such a file is stamped with
     * the CHILD sub-workflow's own {@code workflowId}, never the shared parent's, so the direct
     * workflow-id equality {@code ShareContextResourceBinding.permitsWorkflowResource} performs
     * always fails for it. See {@link StorageService#existsSubWorkflowInvocation} for the exact
     * evidence (the sub_workflow node's own persisted step output records {@code subRunId}).
     *
     * <p>Same no-scope-check rationale as {@link #isInterfaceReferencedByWorkflow}: a boolean
     * oracle over two ids the caller already has. An unparsable id answers {@code false}
     * (fail closed).
     *
     * <p>{@code childRunId} is NOT a UUID (it's a {@code run_id_public} value, e.g.
     * {@code run_<millis>_<8hex>} - see {@code WorkflowUtils.generateRunId}), so it cannot be
     * shape-checked the same way as {@code workflowId}/{@code interfaceId} above. It still gets
     * an explicit shape guard here - mirroring the sibling endpoint's fail-BEFORE-the-repository
     * style rather than relying only on {@link StorageService#existsSubWorkflowInvocation}'s
     * blank check one layer down: blank, and longer than the {@code run_id_public} column width
     * (255, see {@code V1__create_orchestrator_schema.sql}) - a value that column could never
     * hold can never match, so there is no point spending the native query on it.
     */
    @GetMapping("/workflows/{workflowId}/sub-workflow-runs/{childRunId}/descendant")
    public ResponseEntity<Boolean> isSubWorkflowDescendant(@PathVariable String workflowId,
                                                            @PathVariable String childRunId) {
        try {
            UUID.fromString(workflowId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(false);
        }
        if (childRunId == null || childRunId.isBlank() || childRunId.length() > 255) {
            return ResponseEntity.ok(false);
        }
        return ResponseEntity.ok(storageService.existsSubWorkflowInvocation(workflowId, childRunId));
    }
}

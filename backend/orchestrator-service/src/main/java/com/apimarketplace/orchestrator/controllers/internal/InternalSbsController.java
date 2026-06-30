package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * Internal endpoint for the Gateway to forward step-by-step execution actions
 * received via WebSocket.
 * Protected by X-Gateway-Secret header.
 *
 * <p>The Gateway WsActionHandler forwards {@code sbs.execute} actions here.
 * Execution is dispatched asynchronously - the HTTP response is an immediate ack.
 * Results (node status, readySteps, decisionEvaluated) are delivered via
 * Redis pub/sub events to the WebSocket client.
 */
@RestController
@RequestMapping("/api/internal/sbs")
public class InternalSbsController {

    private static final Logger log = LoggerFactory.getLogger(InternalSbsController.class);

    private final V2StepByStepService v2StepByStepService;
    private final V2StepByStepScheduler v2StepByStepScheduler;
    private final WorkflowResumeService resumeService;
    private final StateSnapshotService stateSnapshotService;
    private final SnapshotService snapshotService;
    private final CreditConsumptionClient creditClient;
    private final TaskExecutor sbsExecutor;
    private final WorkflowRunRepository runRepository;

    public InternalSbsController(
            V2StepByStepService v2StepByStepService,
            V2StepByStepScheduler v2StepByStepScheduler,
            WorkflowResumeService resumeService,
            StateSnapshotService stateSnapshotService,
            SnapshotService snapshotService,
            CreditConsumptionClient creditClient,
            @Qualifier("sbsExecutor") TaskExecutor sbsExecutor,
            WorkflowRunRepository runRepository) {
        this.v2StepByStepService = v2StepByStepService;
        this.v2StepByStepScheduler = v2StepByStepScheduler;
        this.resumeService = resumeService;
        this.stateSnapshotService = stateSnapshotService;
        this.snapshotService = snapshotService;
        this.creditClient = creditClient;
        this.sbsExecutor = sbsExecutor;
        this.runRepository = runRepository;
    }

    /**
     * Execute a node in step-by-step mode.
     * Called by the Gateway when a WebSocket client sends {@code sbs.execute}.
     *
     * <p>Returns an immediate ack. The actual execution runs asynchronously on the
     * {@code sbsExecutor} thread pool. Results are emitted as WebSocket events
     * (batch-update, readySteps, decisionEvaluated) via Redis pub/sub.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{runId}/execute/{nodeId}")
    public ResponseEntity<Map<String, Object>> executeNode(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestBody(required = false) Map<String, Object> data) {

        log.info("[InternalSbs] Execute request: runId={}, nodeId={}, userId={}", runId, nodeId, userId);

        // Run-scope guard - mirror of the HTTP twin (StepByStepController.guardRunScope).
        // The runId is client-supplied over WebSocket: without this check a forged
        // sbs.execute could step a foreign run under that run's stored org scope.
        // The gateway/monolith WS layer forwards the session's active workspace as
        // X-Organization-ID (same resolution chain as the HTTP AuthenticationFilter).
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of(
                    "accepted", false,
                    "error", "MISSING_USER",
                    "message", "Missing X-User-ID"
            ));
        }
        var runRow = runRepository.findByRunIdPublic(runId);
        if (runRow.isEmpty()
                || !com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper
                        .isRunInScope(runRow.get(), userId, organizationId)) {
            log.warn("[InternalSbs] Run scope blocked: runId={}, caller={}, org={}", runId, userId, organizationId);
            return ResponseEntity.status(404).body(Map.of(
                    "accepted", false,
                    "error", "RUN_NOT_FOUND",
                    "message", "Run not found"
            ));
        }

        // Credit pre-check
        if (!creditClient.checkCredits(userId)) {
            log.warn("[InternalSbs] Insufficient credits for user {}, rejecting: runId={}, nodeId={}", userId, runId, nodeId);
            return ResponseEntity.status(402).body(Map.of(
                    "accepted", false,
                    "error", "INSUFFICIENT_CREDITS",
                    "message", "Insufficient credits to execute step"
            ));
        }

        // Extract itemId from data (for split items)
        String itemId = "0";
        if (data != null && data.containsKey("itemId")) {
            itemId = String.valueOf(data.get("itemId"));
        }

        // Extract epoch from data (for parallel epoch execution)
        Integer epoch = null;
        if (data != null && data.containsKey("epoch")) {
            Object epochObj = data.get("epoch");
            if (epochObj instanceof Number) {
                epoch = ((Number) epochObj).intValue();
            }
        }

        // Update plan if present in data (synchronous - must complete before execution).
        // updateRunPlan returns null on rejection (empty / topology / pinned-non-editor);
        // surface as WARN so the user sees a signal in logs even if the WS response
        // can't carry it. Silent swallow would let the frozen plan execute while the
        // inspector reports the edit applied.
        if (data != null && data.get("plan") instanceof Map) {
            com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan accepted =
                    resumeService.updateRunPlan(runId, (Map<String, Object>) data.get("plan"));
            if (accepted == null) {
                log.warn("[InternalSbs] updateRunPlan rejected for runId={} - step will execute against the frozen run.plan", runId);
            }
        }

        // SYNCHRONOUS claim: validate READY state under PESSIMISTIC_WRITE lock.
        // Prevents double-execution when multiple users click the same node.
        boolean claimed = stateSnapshotService.claimNodeForExecution(runId, nodeId);
        if (!claimed) {
            log.warn("[InternalSbs] Node not ready, rejecting: runId={}, nodeId={}", runId, nodeId);
            return ResponseEntity.status(409).body(Map.of(
                    "accepted", false,
                    "error", "NODE_NOT_READY",
                    "message", "Node is not in READY state (already executing or not yet ready)"
            ));
        }

        // Bind the worker to the org scope of the RUN row - it is authoritative
        // (stamped at run creation; null = legacy personal scope). The WS path
        // reaches this endpoint via the gateway WsActionHandler, which historically
        // sent no X-Organization-ID: in an org workspace the step then executed
        // with a null TenantResolver scope and every OrgScopedEntity write (e.g.
        // the storage.storage offload of step output payloads) was rejected by
        // OrgScopedEntityListener. Same pattern as RedisExecutionQueueService and
        // SignalResumeService.
        // Capture for lambda
        final String finalItemId = itemId;
        final Integer finalEpoch = epoch;
        final String orgIdForWorker = runRow.get().getOrganizationId();
        final String orgRoleForWorker = runRow.get().getOrganizationRole();

        // Dispatch execution asynchronously - events arrive via WS
        sbsExecutor.execute(() -> TenantResolver.runWithOrgScope(orgIdForWorker, orgRoleForWorker, () -> {
            try {
                // Check for pending Split items (same logic as StepByStepController)
                if ("0".equals(finalItemId)) {
                    Set<String> pendingItemIds = v2StepByStepScheduler.getPendingItemIdsForNode(runId, nodeId);
                    if (!pendingItemIds.isEmpty()) {
                        log.info("[InternalSbs] Executing {} split items: runId={}, nodeId={}",
                                pendingItemIds.size(), runId, nodeId);
                        v2StepByStepService.executeSplitItems(runId, nodeId, pendingItemIds);
                        return;
                    }
                }

                if (finalEpoch != null) {
                    v2StepByStepService.executeNode(runId, nodeId, finalItemId, finalEpoch);
                } else {
                    v2StepByStepService.executeNode(runId, nodeId, finalItemId);
                }

                // After execution, reconcile run status based on ready nodes.
                // SBS keeps the epoch open (never close/prune) so reruns always work.
                // Status is determined purely by readyNodeIds:
                //   non-trigger ready → PAUSED (user needs to click next step)
                //   only trigger ready → WAITING_TRIGGER (waiting for external fire)
                try {
                    stateSnapshotService.reconcileSbsRunStatus(runId);
                    // Send fresh snapshot immediately - SBS step completion is critical
                    snapshotService.sendSnapshotImmediate(runId);
                } catch (Exception statusEx) {
                    log.warn("[InternalSbs] Status reconcile failed: runId={}, nodeId={}",
                            runId, nodeId, statusEx);
                }
            } catch (Exception e) {
                log.error("[InternalSbs] Execution failed: runId={}, nodeId={}", runId, nodeId, e);
                // Error events (node FAILED, workflow FAILED) are emitted by the V2 pipeline.
                // If the execution died BEFORE the pipeline recorded any outcome for the node
                // (e.g. tree rebuild failure), the claim above left it neither READY nor
                // resolved - release it so the user can click the node again instead of
                // getting NODE_NOT_READY forever. No-op when an outcome was recorded.
                try {
                    boolean released = stateSnapshotService.releaseNodeClaimIfUnresolved(runId, nodeId);
                    if (released) {
                        // Re-derive run status (PAUSED/WAITING_TRIGGER) - the run may have
                        // flipped to RUNNING before the crash and would otherwise stay
                        // RUNNING while showing a READY node.
                        stateSnapshotService.reconcileSbsRunStatus(runId);
                        snapshotService.sendSnapshotImmediate(runId);
                    }
                } catch (Exception releaseEx) {
                    log.warn("[InternalSbs] Claim release failed: runId={}, nodeId={}", runId, nodeId, releaseEx);
                }
            }
        }));

        // Immediate ack - the result arrives via WS events
        return ResponseEntity.ok(Map.of(
                "accepted", true,
                "runId", runId,
                "nodeId", nodeId
        ));
    }
}

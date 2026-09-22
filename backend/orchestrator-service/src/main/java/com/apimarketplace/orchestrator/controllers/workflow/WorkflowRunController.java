package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.AgentRunStopService;
import com.apimarketplace.orchestrator.services.resume.AutoRestartExecutionService;
import com.apimarketplace.orchestrator.services.resume.StepRerunService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Controller for Workflow Run Management operations.
 * Handles instances listing, state retrieval, pause/resume, and rerun operations.
 */
@RestController
@RequestMapping("/api/v2/workflows/dag")
@Validated
public class WorkflowRunController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowRunController.class);

    @Autowired
    private WorkflowManagementService workflowManagementService;

    @Autowired
    private WorkflowResumeService resumeService;

    @Autowired
    private StepAggregationService stepAggregationService;

    @Autowired
    private StepRerunService stepRerunService;

    @Autowired
    private WorkflowControllerHelper helper;

    @Autowired
    private StateSnapshotService stateSnapshotService;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private SignalWaitRepository signalWaitRepository;

    @Autowired
    private WorkflowEpochService workflowEpochService;

    @Autowired
    private AutoRestartExecutionService autoRestartExecutionService;

    /**
     * 2026-05-04 hot-fix (audit TR-1): the REST `/state` payload's `seq` MUST
     * be the same counter the WS publish path stamps (WsEventSequencer), not
     * StateSnapshot.seq. Otherwise the frontend `lastKnownSeq` (WS-counter)
     * compares against a smaller REST seq and skip-applies tracking → UI
     * frozen on cold-load when a WS event arrives before the REST response.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.streaming.WsEventSequencer wsEventSequencer;

    /**
     * Lists all instances (executions) of a workflow.
     */
    @GetMapping("/{workflowId}/instances")
    public ResponseEntity<?> getWorkflowInstances(
            @PathVariable("workflowId") String workflowId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            // Audit 2026-05-17 round-5 - scope check on parent workflow.
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.status(401).build();
            }
            UUID id = UUID.fromString(workflowId);
            // Filter list to runs visible to the caller.
            List<WorkflowRunEntity> instances;
            int effectiveLimit = (limit <= 0 || limit > 1000) ? 1000 : limit;
            instances = workflowManagementService.getRecentInstances(id, effectiveLimit);
            final String fTenant = tenantId;
            final String fOrg = orgId;
            instances = instances.stream()
                .filter(r -> WorkflowControllerHelper.isRunInScope(r, fTenant, fOrg))
                .collect(Collectors.toList());

            List<Map<String, Object>> instancesData = instances.stream()
                .map(helper::buildInstanceData)
                .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("workflowId", workflowId);
            response.put("instances", instancesData);
            response.put("count", instancesData.size());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid workflow ID format"));
        } catch (Exception e) {
            logger.error("Error getting workflow instances: {}", workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Gets a specific instance by its runId.
     */
    @GetMapping("/instances/{runId}")
    public ResponseEntity<?> getInstance(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            // Audit 2026-05-17 round-5 - owner-or-org scope check.
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.status(401).build();
            }
            Optional<WorkflowRunEntity> instanceOpt = workflowManagementService.getInstance(runId);

            if (instanceOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            if (!WorkflowControllerHelper.isRunInScope(instanceOpt.get(), tenantId, orgId)) {
                logger.warn("[SCOPE] getInstance cross-tenant blocked: runId={} caller={} orgId={}", runId, tenantId, orgId);
                return ResponseEntity.notFound().build();
            }

            WorkflowRunEntity instance = instanceOpt.get();
            Map<String, Object> response = helper.buildInstanceDetailResponse(instance);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting instance: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Gets aggregated steps by alias for a runId.
     */
    @GetMapping("/instances/{runId}/steps/aggregated")
    public ResponseEntity<?> getAggregatedSteps(
            @PathVariable("runId") String runId,
            @RequestParam(value = "epoch", required = false) Integer epoch,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            // Cross-tenant guard - see getRunState.
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.status(401).build();
            }
            Optional<WorkflowRunEntity> ownerCheck = workflowRunRepository.findByRunIdPublic(runId);
            if (ownerCheck.isEmpty() || !WorkflowControllerHelper.isRunInScope(ownerCheck.get(), tenantId, orgId)) {
                logger.warn("Aggregated steps request denied: runId={} tenantId={} orgId={}", runId, tenantId, orgId);
                return ResponseEntity.notFound().build();
            }
            logger.info("Getting aggregated steps for runId: {}, epoch: {}", runId, epoch);

            Optional<List<StepAggregationService.AggregatedStep>> aggregatedOpt =
                epoch != null
                    ? stepAggregationService.getAggregatedSteps(runId, epoch)
                    : stepAggregationService.getAggregatedSteps(runId);

            if (aggregatedOpt.isEmpty()) {
                logger.warn("Run not found: {}", runId);
                return ResponseEntity.notFound().build();
            }

            List<Map<String, Object>> response = stepAggregationService.toResponseList(aggregatedOpt.get());

            // Enrich per-epoch response with awaiting_signal from StateSnapshot.
            // Awaiting-signal nodes are NOT persisted to workflow_step_data, so the SQL
            // aggregation won't find them. We inject them from the live state.
            if (epoch != null) {
                enrichWithAwaitingSignal(response, runId, epoch);
            } else {
                // Whole-run (no epoch) is the StepData modal's CUMULATIVE view. The aggregation
                // SQL keeps only the latest spawn per (alias, epoch, ...), so a node reran from
                // FAILED to COMPLETED has its prior FAILED superseded - the accumulated failures
                // vanish from the "Status Counts" column while the node badge (cumulative
                // NodeCounts) still shows them. Re-source the counts from the durable per-epoch
                // counters (same source as /status-counts) so the modal's accumulation matches
                // the badge. The derived "status" (current state, from the max-spawn query) is
                // intentionally left untouched.
                overrideStatusCountsWithCumulative(response, runId);
            }

            logger.info("Returning {} aggregated steps for runId: {}, epoch: {}", response.size(), runId, epoch);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting aggregated steps for runId: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Gets active signals for a specific epoch of a run.
     * Used by the frontend to determine which nodes have pending signals when viewing historical epochs.
     */
    @GetMapping("/runs/{runId}/epochs/{epoch}/signals")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getEpochSignals(
            @PathVariable("runId") String runId,
            @PathVariable("epoch") int epoch,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            // Cross-tenant guard - see getRunState.
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.status(401).build();
            }
            Optional<WorkflowRunEntity> ownerCheck = workflowRunRepository.findByRunIdPublic(runId);
            if (ownerCheck.isEmpty() || !WorkflowControllerHelper.isRunInScope(ownerCheck.get(), tenantId, orgId)) {
                logger.warn("Epoch signals request denied: runId={} epoch={} tenantId={} orgId={}", runId, epoch, tenantId, orgId);
                return ResponseEntity.notFound().build();
            }
            logger.info("Getting epoch signals for runId: {}, epoch: {}", runId, epoch);
            List<SignalWaitEntity> signals = signalWaitRepository.findActiveByRunIdAndEpoch(runId, epoch);

            List<Map<String, Object>> response = signals.stream()
                .map(s -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("nodeId", s.getNodeId());
                    map.put("signalType", s.getSignalType().name());
                    map.put("status", s.getStatus().name());
                    map.put("itemId", s.getItemId());
                    if (s.getCreatedAt() != null) map.put("createdAt", s.getCreatedAt().toString());
                    if (s.getExpiresAt() != null) map.put("expiresAt", s.getExpiresAt().toString());
                    // Split context: per-item data so the approver knows WHAT they approve.
                    // DISPLAY-only projection - strip the cross-pod restoration keys.
                    if (s.getSplitItemData() != null && !s.getSplitItemData().isEmpty()) {
                        map.put("itemContext",
                            SplitContextManager.toDisplayItemContext(s.getSplitItemData()));
                    }
                    // Configured approval context (contextTemplate resolved at yield), shown to the approver.
                    if (s.getApprovalContext() != null && !s.getApprovalContext().isBlank()) {
                        map.put("approvalContext", s.getApprovalContext());
                    }
                    return map;
                })
                .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting epoch signals for runId: {}, epoch: {}", runId, epoch, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Gets pre-aggregated node and edge status counts for a specific epoch.
     * Returns { epoch, nodes: {key: {status: count}}, edges: {key: {status: count}} }.
     */
    @GetMapping("/runs/{runId}/epochs/{epoch}/state")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getEpochState(
            @PathVariable("runId") String runId,
            @PathVariable("epoch") int epoch,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            // Cross-tenant guard - see getRunState.
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.status(401).build();
            }
            Optional<WorkflowRunEntity> ownerCheck = workflowRunRepository.findByRunIdPublic(runId);
            if (ownerCheck.isEmpty() || !WorkflowControllerHelper.isRunInScope(ownerCheck.get(), tenantId, orgId)) {
                logger.warn("Epoch state request denied: runId={} epoch={} tenantId={} orgId={}", runId, epoch, tenantId, orgId);
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> result = workflowEpochService.getEpochState(runId, epoch);

            // workflow_epochs is the single source of truth for epoch state.
            // Counter rows (NODE/EDGE) + header row (full EpochState JSONB).

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error getting epoch state for runId: {}, epoch: {}", runId, epoch, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Retrieves the complete state of a workflow run.
     *
     * <p>Tenant-scoped: when X-User-ID is present (gateway-injected from JWT),
     * the caller must own the run. Returns 404 (not 403) for cross-tenant
     * access so we don't leak run-id existence to other tenants.
     */
    @GetMapping("/runs/{runId}/state")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getRunState(
            @PathVariable("runId") String runId,
            @RequestParam(value = "full", defaultValue = "false") boolean full,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        try {
            // Owner-or-org scope: every authenticated call must own the run OR be a
            // member of the workspace the run was tagged to. Internal callers
            // (orchestrator services) hit the service directly, not this REST
            // endpoint, so requiring the header here is safe.
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.status(401).build();
            }
            Optional<WorkflowRunEntity> ownerCheck = workflowRunRepository.findByRunIdPublic(runId);
            if (ownerCheck.isEmpty() || !WorkflowControllerHelper.isRunInScope(ownerCheck.get(), tenantId, orgId)) {
                logger.warn("Run state request denied: runId={} tenantId={} orgId={} (out of scope or missing)", runId, tenantId, orgId);
                return ResponseEntity.notFound().build();
            }
            logger.info("Getting run state for runId: {} full={}", runId, full);
            // Default (full=false): skip storage round-trips for mcp/trigger/core/table outputs
            // and globalData. Agent + interface aliases keep loading their output (panel/modal
            // need them at refresh). full=true returns the engine-grade payload - used by
            // the E2E OutputVerifier and any caller that needs the complete state.
            WorkflowRunState state = full
                ? resumeService.reconstructState(runId)
                : resumeService.reconstructStateForApi(runId);
            logger.info("Ready nodes from StateManager: {}", state.readySteps());

            // Include seq + totalDurationMs from StateSnapshot
            StateSnapshot dbSnapshot = stateSnapshotService.getSnapshot(runId);
            // 2026-05-04 hot-fix (audit TR-1): the seq returned to the frontend
            // MUST match the counter that WS publish stamps (WsEventSequencer),
            // otherwise FE.lastKnownSeq (WS counter, e.g. 82) compares against
            // a smaller REST seq (StateSnapshot, e.g. 5) → partial-apply skips
            // tracking → page frozen on cold-load with WS-first race.
            // Fallback to StateSnapshot.seq when sequencer is unwired (tests).
            long seq = (wsEventSequencer != null)
                    ? Math.max(wsEventSequencer.currentSeq(runId), dbSnapshot.getSeq())
                    : dbSnapshot.getSeq();

            // Reuse the row the scope check already loaded: it feeds the ETag hash below and the
            // response fields further down (planVersion / cost / budget / lastCycleResult). This
            // endpoint is polled continuously, and it used to issue two more queries for the same
            // row - one here and one at the response-building block.
            var runEntity = ownerCheck;
            String lastCycleResult = runEntity
                    .map(com.apimarketplace.orchestrator.domain.WorkflowRunEntity::getMetadata)
                    .map(meta -> meta.get("lastCycleResult"))
                    .map(String::valueOf)
                    .orElse(null);

            // Phase G (archi-refoundation 2026-05-04) - ETag composite seq + full + lightweight hash.
            // Includes the `full` flag in the suffix (audit B v6 N3): without this, a client
            // hitting `?full=false` would 304-match a server response cached for `?full=true`,
            // returning an empty body to a caller that expects outputs (E2E OutputVerifier).
            // Hash is on a deterministic projection (seq + status + currentEpoch) - outputs are
            // explicitly excluded since they're volatile and would defeat the cache.
            int currentEpochForEtag = 0;
            // Open epochs join the hash. They are their own fact, not implied by seq: an epoch
            // opening or closing is what the client reads to decide whether a restart targeting
            // an OLDER epoch can be offered, and a body cached across that transition would keep
            // answering with the previous set.
            Set<Integer> activeEpochsForEtag = new TreeSet<>();
            for (DagState dag : dbSnapshot.getDags().values()) {
                currentEpochForEtag = Math.max(currentEpochForEtag, dag.getCurrentEpoch());
                activeEpochsForEtag.addAll(dag.getActiveEpochs());
            }
            // Both values are computed once, here, and reused by the body below: the response
            // ships exactly what the hash was taken over, so the two can never describe
            // different snapshots of the same read.
            final int currentEpoch = currentEpochForEtag;
            final Set<Integer> activeEpochs = activeEpochsForEtag;
            // lastCycleResult is in the hash because it is written OUTSIDE the seq bump that
            // carries the work: reconcileSbsRunStatus stamps run.metadata after the last node
            // completion has already advanced seq. A client that polls in that window caches the
            // response, and the verdict landing a moment later changes nothing the hash can see,
            // so the next poll 304s and the badge keeps showing the PREVIOUS cycle's outcome.
            String hashInput = seq + "|" + state.status() + "|" + currentEpochForEtag
                    + "|" + (lastCycleResult != null ? lastCycleResult : "-")
                    // TreeSet, so the projection is deterministic: DagState.getActiveEpochs()
                    // hands back a Set.copyOf, whose iteration order is randomized per JVM.
                    + "|" + activeEpochsForEtag;
            String etagBody;
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(hashInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) sb.append(String.format("%02x", b));
                etagBody = sb.substring(0, 12);  // first 12 hex = 6 bytes, plenty of entropy
            } catch (java.security.NoSuchAlgorithmException nsae) {
                // MD5 is mandated by JLS - this should never happen
                etagBody = String.valueOf(hashInput.hashCode());
            }
            String etag = "\"" + seq + "-" + (full ? "f" : "l") + "-" + etagBody + "\"";

            // 304 short-circuit: if the client's If-None-Match matches our current ETag,
            // return 304 with no body. The frontend apiClient (Phase G) translates this
            // to a NOT_MODIFIED sentinel that consumers handle by keeping their last value.
            if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .eTag(etag)
                        .build();
            }

            // currentEpoch and activeEpochs were computed above, for the ETag; timestamps come
            // from workflow_epochs. `activeEpochs` is the same union the WS snapshot publishes,
            // sent on this path too because a client that has not yet received a snapshot would
            // otherwise read "nothing is executing" from an empty list - the answer a targeted
            // epoch replay is refused on (rerunFromStep declines to reopen an older epoch while
            // a sibling of the same DAG is still running). It is a TreeSet, so the array is as
            // deterministic as the hash that covers it: DagState.getActiveEpochs() hands back a
            // Set.copyOf whose iteration order is randomized per JVM.
            var epochTimestamps = workflowEpochService.listEpochTimestamps(runId);

            // Wrap state + seq in a combined response
            Map<String, Object> response = new HashMap<>();
            response.put("runId", state.runId());
            response.put("workflowId", state.workflowId());
            response.put("status", state.status());
            response.put("executionMode", state.executionMode());
            response.put("startedAt", state.startedAt());
            response.put("pausedAt", state.pausedAt());
            response.put("plan", state.plan());
            response.put("steps", state.steps());
            response.put("edges", state.edges());
            response.put("readySteps", state.readySteps());
            response.put("completedStepIds", state.completedStepIds());
            response.put("failedStepIds", state.failedStepIds());
            response.put("skippedStepIds", state.skippedStepIds());
            // Awaiting-signal nodes (approval / wait / interface) are folded INTO
            // runningStepIds by StateReconstructor. On their own that makes the
            // frontend paint them blue "running" instead of amber "awaiting", because
            // it keys the amber set off a SEPARATE awaitingSignalStepIds field it never
            // receives on this REST path. Mirror the WS snapshot (SnapshotService): send
            // a CLEAN running set (awaiting excluded) plus the awaiting set separately.
            Set<String> awaitingSignalStepIds = dbSnapshot.getAwaitingSignalNodeIds() != null
                    ? new HashSet<>(dbSnapshot.getAwaitingSignalNodeIds())
                    : new HashSet<>();
            Set<String> runningStepIds = state.runningStepIds() != null
                    ? new HashSet<>(state.runningStepIds())
                    : new HashSet<>();
            runningStepIds.removeAll(awaitingSignalStepIds);
            response.put("runningStepIds", runningStepIds);
            response.put("awaitingSignalStepIds", awaitingSignalStepIds);
            response.put("loops", state.loops());
            response.put("interfaces", state.interfaces());
            response.put("seq", seq);
            response.put("currentEpoch", currentEpoch);
            response.put("activeEpochs", new ArrayList<>(activeEpochs));

            // The cycle outcome, as the backend computed it. Shipped ready to display so the
            // client stops deriving its own: it can only see the CUMULATIVE completed/failed
            // sets, while this is scoped to the epoch that actually closed. A node that failed
            // in epoch 1 and succeeded in epoch 2 is PARTIAL_SUCCESS (accumulation, which is
            // what its badge shows) yet epoch 2 closed clean - no client-side derivation can
            // tell those apart, so the canvas badge and the run-history row disagreed on the
            // same run. Absent until a cycle has closed, which is what keeps a launched run
            // whose trigger has not fired reading "waiting for trigger".
            if (lastCycleResult != null) {
                response.put("lastCycleResult", lastCycleResult);
            }
            if (!epochTimestamps.isEmpty()) {
                response.put("epochTimestamps", epochTimestamps);
            }

            // Cumulative execution duration: closed epochs + live active epochs
            long totalDurationMs = dbSnapshot.getTotalDurationMs();
            for (DagState dag : dbSnapshot.getDags().values()) {
                for (var epochEntry : dag.getActiveEpochStates().entrySet()) {
                    EpochState activeEpoch = epochEntry.getValue();
                    if (activeEpoch.getStartedAt() != null) {
                        totalDurationMs += Math.max(0,
                                java.time.Duration.between(activeEpoch.getStartedAt(), java.time.Instant.now()).toMillis());
                    }
                }
            }
            response.put("totalDurationMs", totalDurationMs);

            // Include planVersion + accumulated run cost + workflow budget from
            // the run entity. costCredits is the total across ALL epochs (agent
            // executions are the only cost source); budgetCredits is the
            // workflow's cap (null = unlimited). Both in credits (1 credit =
            // $0.001); the frontend renders dollars in CE, credits in cloud. Live
            // deltas arrive via the runCost WS event; this seeds the initial view.
            runEntity.ifPresent(run -> {
                response.put("planVersion", run.getPlanVersion());
                response.put("costCredits", run.getCostCredits());
                if (run.getCostByEpoch() != null) {
                    response.put("costByEpoch", run.getCostByEpoch());
                }
                if (run.getWorkflow() != null) {
                    response.put("budgetCredits", run.getWorkflow().getBudgetCredits());
                    // The cap is compared against the PERIOD spend, not this
                    // run's lifetime cost, so seed that figure too - otherwise
                    // the panel paints "over budget" off the wrong number until
                    // the first settle of the session corrects it.
                    //
                    // Whether the figure applies to THIS run is not decided
                    // here: it goes through the same
                    // WorkflowBudgetState.appliesToRun() that counts the spend
                    // and that both enforcement points refuse on. Re-deriving it
                    // from the production FK alone (the obvious one-liner, and
                    // what this used to do) put a fourth copy of the rule in the
                    // codebase, and a fourth copy is a fourth thing to keep in
                    // step - this one had already drifted.
                    var wf = run.getWorkflow();
                    response.put("budgetPeriodMode",
                            com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod
                                    .normaliseMode(wf.getBudgetPeriodMode()));
                    java.time.Instant budgetNow = java.time.Instant.now();
                    // When the allowance starts again. Null for a cap that never
                    // resets, which is the one case the panel must not promise a
                    // date for.
                    response.put("budgetPeriodResetsAt",
                            com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod
                                    .nextPeriodStart(wf.getBudgetPeriodMode(), budgetNow));
                    response.put("periodSpentCredits",
                            workflowRunRepository.findBudgetStateByRunIdPublic(run.getRunIdPublic())
                                    .filter(com.apimarketplace.orchestrator.services.credit
                                            .WorkflowBudgetState::appliesToRun)
                                    .map(budgetState -> budgetState.effectiveSpent(budgetNow))
                                    .orElse(null));
                }
            });

            return ResponseEntity.ok()
                    .eTag(etag)
                    .header("Cache-Control", "no-cache, must-revalidate")
                    .body(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Run not found: {}", runId);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error getting run state for runId: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to get run state: " + e.getMessage()));
        }
    }

    /**
     * Pauses a running workflow.
     */
    @PostMapping("/runs/{runId}/pause")
    public ResponseEntity<?> pauseWorkflow(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            if (tenantId == null || tenantId.isBlank()) return ResponseEntity.status(401).build();
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
                return ResponseEntity.notFound().build();
            }
            logger.info("Pausing workflow: {}", runId);
            WorkflowRunState state = resumeService.pauseWorkflow(runId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "runId", runId,
                "status", state.status().getValue(),
                "readySteps", state.readySteps(),
                "message", "Workflow paused successfully"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error pausing workflow: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to pause workflow: " + e.getMessage()));
        }
    }

    /**
     * Attribution written on a run stopped from the UI. Built by the service that owns the
     * convention (three keys written as a set, null reason clearing a previous stopper's
     * sentence) rather than assembled here: attribution policy is not controller business.
     */
    private static Map<String, Object> userStopMetadata() {
        return AgentRunStopService.stopMetadata("user");
    }

    /**
     * Hard-cancels a workflow run (terminal stop).
     * Accepts RUNNING, PAUSED, or WAITING_TRIGGER. Sets status to CANCELLED.
     */
    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<?> cancelWorkflow(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        try {
            if (tenantId == null || tenantId.isBlank()) return ResponseEntity.status(401).build();
            if (isViewerRole(orgId, orgRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot cancel workflow runs"));
            }
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
                return ResponseEntity.notFound().build();
            }
            logger.info("Cancelling workflow: {}", runId);
            // Record WHO stopped it, same keys the agent-facing stop_run writes, so a
            // later get_run can tell "the user stopped this" from "it crashed".
            resumeService.cancelWorkflow(runId, userStopMetadata());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "runId", runId,
                "status", RunStatus.CANCELLED.getValue(),
                "message", "Workflow cancelled successfully"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error cancelling workflow: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to cancel workflow: " + e.getMessage()));
        }
    }

    /** Backward-compatible direct-call overload used by controller unit tests. */
    public ResponseEntity<?> cancelWorkflow(String runId, String tenantId, String orgId) {
        return cancelWorkflow(runId, tenantId, orgId, null);
    }

    /**
     * Graceful stop: close active epochs and return run to WAITING_TRIGGER.
     * Unlike cancel, the run stays alive for future trigger fires.
     *
     * <p>Idempotent for terminal runs (the service performs best-effort cleanup
     * without changing status). Returns HTTP 409 Conflict only for non-terminal
     * non-stoppable states (PENDING, WAITING_TRIGGER, AWAITING_SIGNAL).</p>
     */
    @PostMapping("/runs/{runId}/stop")
    public ResponseEntity<?> stopWorkflow(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            if (tenantId == null || tenantId.isBlank()) return ResponseEntity.status(401).build();
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
                return ResponseEntity.notFound().build();
            }
            logger.info("Stopping workflow: {}", runId);
            // Same attribution as cancel: the run reports who stopped it.
            resumeService.stopWorkflow(runId, userStopMetadata());

            // Read back the post-stop status: terminal runs stay terminal,
            // RUNNING/PAUSED transition to WAITING_TRIGGER.
            String resultStatus = workflowRunRepository.findByRunIdPublic(runId)
                .map(r -> r.getStatus().getValue())
                .orElse(RunStatus.WAITING_TRIGGER.getValue());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "runId", runId,
                "status", resultStatus,
                "message", "Workflow stopped"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            // Non-terminal but not stoppable (e.g. PENDING, AWAITING_SIGNAL).
            // Surface as 409 Conflict + structured body so the frontend can show a proper toast.
            String currentStatus = workflowRunRepository.findByRunIdPublic(runId)
                .map(r -> r.getStatus().getValue())
                .orElse("unknown");
            logger.warn("Cannot stop workflow {} - status conflict: {}", runId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", e.getMessage(),
                "currentStatus", currentStatus,
                "runId", runId
            ));
        } catch (Exception e) {
            logger.error("Error stopping workflow: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to stop workflow: " + e.getMessage()));
        }
    }

    /**
     * Reactivates a cancelled workflow run, returning it to WAITING_TRIGGER.
     * This allows triggers to fire again on a run that was previously cancelled.
     */
    @PostMapping("/runs/{runId}/reactivate")
    public ResponseEntity<?> reactivateWorkflow(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        try {
            if (tenantId == null || tenantId.isBlank()) return ResponseEntity.status(401).build();
            if (isViewerRole(orgId, orgRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot reactivate workflow runs"));
            }
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
                return ResponseEntity.notFound().build();
            }
            logger.info("Reactivating workflow: {}", runId);
            resumeService.reactivateWorkflow(runId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "runId", runId,
                "status", RunStatus.WAITING_TRIGGER.getValue(),
                "message", "Workflow reactivated successfully"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error reactivating workflow: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to reactivate workflow: " + e.getMessage()));
        }
    }

    /** Backward-compatible direct-call overload used by controller unit tests. */
    public ResponseEntity<?> reactivateWorkflow(String runId, String tenantId, String orgId) {
        return reactivateWorkflow(runId, tenantId, orgId, null);
    }

    private static boolean isViewerRole(String orgId, String orgRole) {
        return orgId != null && orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }

    /**
     * Resumes a paused workflow.
     */
    @PostMapping("/runs/{runId}/resume")
    public ResponseEntity<?> resumeWorkflow(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            if (tenantId == null || tenantId.isBlank()) return ResponseEntity.status(401).build();
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
                return ResponseEntity.notFound().build();
            }
            logger.info("Resuming workflow: {}", runId);
            WorkflowRunState state = resumeService.resumeWorkflow(runId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "runId", runId,
                "status", state.status().getValue(),
                "completedSteps", state.completedStepIds().size(),
                "readySteps", state.readySteps(),
                "message", "Workflow resumed successfully"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error resuming workflow: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to resume workflow: " + e.getMessage()));
        }
    }

    /**
     * Re-runs a workflow from a specific step.
     * Optionally accepts a plan in the request body to update the run's plan before re-running.
     */
    @PostMapping("/runs/{runId}/rerun/{stepId}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> rerunFromStep(
            @PathVariable("runId") String runId,
            @PathVariable("stepId") String stepId,
            @RequestParam(value = "epoch", required = false) Integer epoch,
            @RequestBody(required = false) Map<String, Object> requestBody,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            // Audit 2026-05-17 round-5 - scope check before rerun. Prior:
            // any caller could rerun + overwrite plan on any run by UUID.
            if (tenantId == null || tenantId.isBlank()) return ResponseEntity.status(401).build();
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
                return ResponseEntity.notFound().build();
            }
            // Sanitize: a client must not be able to inject the internal marker
            // and bypass the workflow.plan refresh on rerun.
            requestBody = com.apimarketplace.orchestrator.trigger.ReusableTriggerService
                    .sanitizePlanMarker(requestBody);

            // If frontend sends a plan, update the run's plan before re-running.
            // Only flag planFromPayload when updateRunPlan actually wrote the new
            // plan to run.plan (non-null return). On rejection, refreshing from
            // workflow.plan is the correct fallback.
            boolean planFromPayload = false;
            if (requestBody != null && requestBody.get("plan") instanceof Map) {
                Map<String, Object> planMap = (Map<String, Object>) requestBody.get("plan");
                logger.info("[Rerun] Updating run plan before rerun for runId={}", runId);
                com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan accepted =
                        resumeService.updateRunPlan(runId, planMap);
                if (accepted != null) {
                    planFromPayload = true;
                } else {
                    logger.warn("[Rerun] updateRunPlan rejected payload for runId={} - workflow.plan refresh will apply", runId);
                }
            }

            // ?epoch=N replays that fire of the run instead of the default one. The builder sends
            // it whenever the canvas is reading a single epoch that is not the one the run is
            // living in, which on a SETTLED run includes the newest EXECUTED epoch (by then
            // DagState.currentEpoch points at the fire staged for next time). It stays absent on
            // the all-epochs view. Naming the epoch the default resolution would have picked
            // reaches the same replay through the explicit branch, plus one extra check: that
            // the node actually ran in it.
            //
            // Note the pre-existing ordering above: a request that carries a plan has ALREADY had
            // it written to run.plan by the time the rerun is refused (unknown epoch, node not
            // rerunnable, run stopped...). That was true of every refusal before this parameter
            // existed; the epoch adds more ways to reach it, and the builder sends a plan with a
            // rerun whenever its canvas has one. Left as is deliberately - moving the plan write
            // after the service call
            // changes what a rerun executes, which is a separate decision - but a caller
            // combining a plan with an epoch should read the response before assuming neither
            // took effect.
            logger.info("Re-running from step: {} for run: {} (planFromPayload={}, epoch={})",
                    stepId, runId, planFromPayload, epoch);
            StepRerunService.RerunResult result =
                    stepRerunService.rerunFromStep(runId, stepId, planFromPayload, epoch);

            // Once the rerun transaction is committed, advancing the run is execution policy:
            // step-by-step waits for the user, automatic drives the wave loop and closes the
            // cycle. Both live in AutoRestartExecutionService, including the "nothing ready"
            // case that used to be a separate branch here.
            AutoRestartExecutionService.Outcome outcome = autoRestartExecutionService
                    .resumeAfterRerun(runId, result.readySteps(), result.ownerTriggerId(), result.epoch());

            // Read actual status after the drive (step-by-step may have set PAUSED)
            String actualStatus = workflowRunRepository.findByRunIdPublic(runId)
                .map(r -> r.getStatus().getValue())
                .orElse(result.status());

            // LinkedHashMap, not Map.of: that caps at 10 pairs and `outcome` is the 11th.
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("success", true);
            body.put("runId", result.runId());
            body.put("stepId", result.stepId());
            body.put("epoch", result.epoch());
            body.put("spawn", result.spawn());
            body.put("resetSteps", result.resetSteps());
            body.put("readySteps", result.readySteps());
            body.put("status", actualStatus);
            body.put("seq", result.seq());
            // What the post-rerun drive actually did. Without it, "nothing ran", "it yielded
            // and something else will finish it" and "the wave cap truncated the work" are
            // indistinguishable from a RUNNING status.
            body.put("outcome", outcome.name());
            body.put("message", "Re-run initiated from step: " + stepId);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException e) {
            // State validation failure (e.g., step not in terminal state for rerun)
            return ResponseEntity.status(409).body(Map.of(
                "error", "INVALID_STATE_FOR_RERUN",
                "message", e.getMessage(),
                "stepId", stepId
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "stepId", stepId));
        } catch (Exception e) {
            logger.error("Error re-running from step {}: {}", stepId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to re-run from step: " + e.getMessage()));
        }
    }

    /**
     * Updates a run's plan without creating a version or modifying workflow.plan.
     * Used by the frontend to save parameter changes made during run mode.
     */
    @PutMapping("/runs/{runId}/plan")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> updateRunPlan(
            @PathVariable("runId") String runId,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestBody Map<String, Object> request) {
        try {
            Object planObj = request.get("plan");
            if (planObj == null || !(planObj instanceof Map)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing or invalid 'plan' in request body"));
            }

            Map<String, Object> planMap = (Map<String, Object>) planObj;

            // Verify tenant owns the run
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            String orgId = TenantResolver.currentRequestOrganizationId();
            if (!ScopeGuard.isInStrictScope(tenantId, orgId, runOpt.get().getTenantId(), runOpt.get().getOrganizationId())) {
                return ResponseEntity.notFound().build();
            }

            // Block plan updates on terminal runs
            if (runOpt.get().getStatus() != null && runOpt.get().getStatus().isTerminal()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Cannot update plan for a run in terminal state: " + runOpt.get().getStatus().getValue()));
            }

            // updateRunPlan returns null when the payload is rejected (empty,
            // topology-incompatible with the frozen plan, or pinned-non-editor
            // refusal). Surface that as 409 Conflict so the frontend "Save"
            // button does not falsely report success when nothing was written.
            com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan accepted =
                    resumeService.updateRunPlan(runId, planMap);
            if (accepted == null) {
                logger.warn("[updateRunPlan] runId={} payload rejected (empty / topology-incompatible / pinned-non-editor)", runId);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "runId", runId,
                    "error", "Plan update was rejected. The payload may be empty, structurally incompatible with the run's frozen plan, or the workflow is pinned (non-editor runs cannot mutate a pinned plan)."
                ));
            }

            return ResponseEntity.ok(Map.of(
                "success", true,
                "runId", runId,
                "message", "Run plan updated successfully"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error updating run plan for runId: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to update run plan: " + e.getMessage()));
        }
    }

    /**
     * Gets the re-run history for a specific step.
     */
    @GetMapping("/runs/{runId}/steps/{stepId}/history")
    public ResponseEntity<?> getStepHistory(
            @PathVariable("runId") String runId,
            @PathVariable("stepId") String stepId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            if (tenantId == null || tenantId.isBlank()) return ResponseEntity.status(401).build();
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
                return ResponseEntity.notFound().build();
            }
            logger.info("Getting step history: {} for run: {}", stepId, runId);
            List<StepRerunService.StepAttemptRecord> history = stepRerunService.getStepHistory(runId, stepId);
            int attemptCount = stepRerunService.getStepAttemptCount(runId, stepId);

            return ResponseEntity.ok(Map.of(
                "runId", runId,
                "stepId", stepId,
                "attemptCount", attemptCount,
                "attempts", history
            ));
        } catch (Exception e) {
            logger.error("Error getting step history for {}: {}", stepId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to get step history: " + e.getMessage()));
        }
    }

    /**
     * Replace each whole-run aggregated step's {@code statusCounts} with the cumulative per-node
     * counts (across all epochs AND spawns), matching the /status-counts endpoint and the node
     * badge.
     *
     * <p>The aggregation SQL ({@code getAggregatedStepsByRunId}) keeps only the latest spawn per
     * (alias, trigger, epoch, iteration, item) so the derived <em>status</em> reflects current
     * state (a branch deactivated by a rerun shows SKIPPED, not a stale COMPLETED). That same
     * filter drops superseded outcomes from the <em>counts</em>: a node reran FAILED -> COMPLETED
     * loses its earlier FAILED tally. The cumulative
     * {@link WorkflowEpochService#getAccumulatedNodeCounts} counters are never decremented by a
     * rerun, so they are the correct accumulation. We override only the TERMINAL counts
     * (completed/failed/skipped); the transient buckets (running/awaitingSignal), which the durable
     * epoch counters never record, are carried over from the max-spawn row so an in-flight node
     * keeps its live indicator. The max-spawn-derived {@code status} field is preserved. Steps with
     * no cumulative entry keep their original counts (defensive: an alias/label mismatch must never
     * blank an existing row).
     */
    private static final Set<String> TERMINAL_COUNT_KEYS = Set.of("completed", "failed", "skipped");

    private void overrideStatusCountsWithCumulative(List<Map<String, Object>> response, String runId) {
        Map<String, Map<String, Long>> cumulative = workflowEpochService.getAccumulatedNodeCounts(runId);
        if (cumulative == null || cumulative.isEmpty()) {
            return;
        }
        for (Map<String, Object> step : response) {
            Object aliasObj = step.get("alias");
            if (aliasObj == null) continue;
            Map<String, Long> counts = cumulative.get(String.valueOf(aliasObj));
            if (counts == null || counts.isEmpty()) continue;
            // Carry over transient buckets (running/awaitingSignal) from the max-spawn row...
            Map<String, Object> merged = new java.util.LinkedHashMap<>();
            if (step.get("statusCounts") instanceof Map<?, ?> existing) {
                existing.forEach((k, v) -> {
                    if (!TERMINAL_COUNT_KEYS.contains(String.valueOf(k))) {
                        merged.put(String.valueOf(k), v);
                    }
                });
            }
            // ...then replace the terminal buckets with the cumulative (rerun-safe) tally.
            counts.forEach((status, count) -> {
                if (count != null && count > 0) {
                    merged.put(status, count);
                }
            });
            if (!merged.isEmpty()) {
                step.put("statusCounts", merged);
            }
        }
    }

    /**
     * Enrich per-epoch aggregated steps with awaiting_signal info from StateSnapshot.
     * Awaiting-signal nodes are not persisted to workflow_step_data, so the SQL aggregation
     * misses them. We check the EpochState for the requested epoch and inject/update entries.
     */
    private void enrichWithAwaitingSignal(List<Map<String, Object>> response, String runId, int epoch) {
        try {
            var snapshot = stateSnapshotService.getSnapshot(runId);
            Set<String> awaitingInEpoch = new HashSet<>();

            for (var dag : snapshot.getDags().values()) {
                var epochState = dag.getEpochs().get(epoch);
                if (epochState != null) {
                    awaitingInEpoch.addAll(epochState.getAwaitingSignalNodeIds());
                }
            }

            if (awaitingInEpoch.isEmpty()) return;

            // Track which awaiting nodes already have an entry in the response
            Set<String> existingAliases = response.stream()
                .map(m -> (String) m.get("alias"))
                .collect(Collectors.toSet());

            for (String nodeId : awaitingInEpoch) {
                if (existingAliases.contains(nodeId)) {
                    // Update existing entry: override status and add awaitingSignal count
                    for (Map<String, Object> step : response) {
                        if (nodeId.equals(step.get("alias"))) {
                            step.put("status", "awaiting_signal");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> sc = (Map<String, Object>) step.get("statusCounts");
                            if (sc == null) {
                                sc = new HashMap<>();
                                step.put("statusCounts", sc);
                            }
                            sc.put("awaitingSignal", 1);
                        }
                    }
                } else {
                    // Add new entry for nodes that have no workflow_step_data row
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("alias", nodeId);
                    entry.put("status", "awaiting_signal");
                    entry.put("toolId", nodeId);
                    entry.put("statusCounts", Map.of("awaitingSignal", 1));
                    response.add(entry);
                }
            }
        } catch (Exception e) {
            logger.debug("[getAggregatedSteps] Could not enrich with awaiting signal for runId={}, epoch={}: {}",
                runId, epoch, e.getMessage());
        }
    }
}

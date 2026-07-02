package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.state.NodeId;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.state.WorkflowGraph;
import com.apimarketplace.orchestrator.services.state.WorkflowGraphBuilder;
import com.apimarketplace.orchestrator.services.state.WorkflowNode;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.orchestrator.trigger.TriggerEpochManager;
import com.apimarketplace.orchestrator.validation.DAGIndependenceValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Service for handling step re-run functionality in step-by-step mode.
 *
 * <p>When a user wants to re-run a step (whether COMPLETED, FAILED, or SKIPPED),
 * this service:
 * <ul>
 *   <li>Finds all downstream steps (successors) that depend on the target step</li>
 *   <li>Resets those steps to PENDING state</li>
 *   <li>Increments the epoch for tracking re-run attempts</li>
 *   <li>Marks the target step as READY for execution</li>
 * </ul>
 *
 * <p>This creates a fresh execution state from the target step onwards,
 * while preserving the history of previous attempts in the database.
 */
@Service
public class StepRerunService {

    private static final Logger logger = LoggerFactory.getLogger(StepRerunService.class);

    private final WorkflowRunRepository runRepository;
    private final WorkflowStepDataRepository stepDataRepository;
    private final WorkflowExecutionService executionService;
    private final WorkflowStreamingService streamingService;
    private final WorkflowResumeService resumeService;
    private final ExecutionContextManager contextManager;
    private final StateSnapshotService stateSnapshotService;
    private final TriggerEpochManager triggerEpochManager;
    private final DAGIndependenceValidator dagIndependenceValidator;

    @Autowired(required = false)
    @Lazy
    private UnifiedSignalService unifiedSignalService;

    /**
     * 2026-05-04 hot-fix (audit TR-3): rerun's returned `seq` MUST match the
     * counter that the WS publish path stamps (WsEventSequencer), not
     * StateSnapshot.seq. Otherwise the FE writes lastKnownSeq=<small>
     * (StateSnapshot) and any in-flight WS events with seq=<large>
     * (WsEventSequencer) from BEFORE the rerun pass the strict-`<` filter as
     * if they were fresh, overwriting the post-rerun state for 1-2s.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.streaming.WsEventSequencer wsEventSequencer;

    /**
     * Cycle-close funnel for the AUTO-mode rerun path (see
     * {@link #closeCycleAfterAutoExecution}). Optional + lazy so unit fixtures that
     * construct this service directly stay unaffected.
     */
    @Autowired(required = false)
    @Lazy
    private SignalResumeService signalResumeService;

    public StepRerunService(
            WorkflowRunRepository runRepository,
            WorkflowStepDataRepository stepDataRepository,
            WorkflowExecutionService executionService,
            WorkflowStreamingService streamingService,
            WorkflowResumeService resumeService,
            ExecutionContextManager contextManager,
            StateSnapshotService stateSnapshotService,
            TriggerEpochManager triggerEpochManager,
            DAGIndependenceValidator dagIndependenceValidator) {
        this.runRepository = runRepository;
        this.stepDataRepository = stepDataRepository;
        this.executionService = executionService;
        this.streamingService = streamingService;
        this.resumeService = resumeService;
        this.contextManager = contextManager;
        this.stateSnapshotService = stateSnapshotService;
        this.triggerEpochManager = triggerEpochManager;
        this.dagIndependenceValidator = dagIndependenceValidator;
    }

    /**
     * Re-runs a workflow from a specific step.
     *
     * <p>This resets the target step and all downstream steps to their initial state,
     * allowing the user to re-execute them with potentially modified configuration.
     *
     * @param runId      The workflow run ID
     * @param stepId     The normalized step ID to re-run from (e.g., "mcp:api_call")
     * @return The result containing reset steps and new ready steps
     */
    @Transactional
    public RerunResult rerunFromStep(String runId, String stepId) {
        return rerunFromStep(runId, stepId, /* planFromPayload */ false);
    }

    /**
     * Re-runs a workflow from a specific step.
     *
     * @param planFromPayload {@code true} when the controller already wrote the
     *     user's intended plan into {@code run.plan} via
     *     {@code WorkflowResumeService.updateRunPlan} (request body carried a
     *     plan). Propagated to {@code refreshPlanFromWorkflowDefinition} so
     *     the user's inspector edit is not silently reverted by the
     *     workflow.plan refresh.
     */
    @Transactional
    public RerunResult rerunFromStep(String runId, String stepId, boolean planFromPayload) {
        logger.info("[RerunService] Re-running from step: {} for run: {} (planFromPayload={})", stepId, runId, planFromPayload);

        // 1. Load workflow run
        WorkflowRunEntity runEntity = runRepository.findByRunIdPublic(runId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + runId));

        // 2. Sync run plan with latest workflow definition (picks up user modifications).
        // When planFromPayload=true, the user's plan is already on run.plan - preserve it.
        WorkflowPlan plan = resumeService.refreshPlanFromWorkflowDefinition(runId, planFromPayload);

        WorkflowGraph graph = WorkflowGraphBuilder.build(plan);

        // 3. Validate that the step exists in the graph
        NodeId targetNodeId = NodeId.parse(stepId);
        if (!graph.containsNode(targetNodeId)) {
            throw new IllegalArgumentException("Step not found in workflow: " + stepId);
        }

        // 3b. Validate: step must be in a rerunnable state.
        // Allows: completed, failed, awaiting_signal, ready (idempotent double-click).
        // Rejects: running (to prevent interrupting active execution).
        // Note: flat views only include activeEpochs, so also check global NodeCounts
        // which persist across closed epochs (e.g., after SBS epoch completion).
        StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
        boolean isTerminal = snapshot.getCompletedNodeIds().contains(stepId)
                          || snapshot.getFailedNodeIds().contains(stepId);
        boolean isAwaitingSignal = snapshot.getAwaitingSignalNodeIds().contains(stepId);
        boolean isReady = snapshot.getReadyNodeIds().contains(stepId);
        if (!isTerminal) {
            // Check global NodeCounts (never reset, survives epoch close)
            StateSnapshot.NodeCounts counts = snapshot.getNodes().get(stepId);
            if (counts != null && (counts.completed() > 0 || counts.failed() > 0)) {
                isTerminal = true;
            }
        }
        if (!isTerminal && !isAwaitingSignal && !isReady) {
            throw new IllegalStateException(
                "Cannot rerun step " + stepId + ": not in rerunnable state. " +
                "Current state may be: running or pending."
            );
        }

        // 4. Find all downstream steps (including the target step)
        Set<String> stepsToReset = findAllDownstreamSteps(graph, targetNodeId);
        stepsToReset.add(stepId);

        logger.info("[RerunService] Steps to reset: {}", stepsToReset);

        // 5. Increment spawn (NOT epoch) for the owning trigger's DAG
        String ownerTriggerId = dagIndependenceValidator.findOwnerTrigger(plan, stepId)
                .orElse(plan.getTriggers() != null && !plan.getTriggers().isEmpty()
                        ? plan.getTriggers().get(0).getNormalizedKey()
                        : null);
        int newSpawn = ownerTriggerId != null
                ? triggerEpochManager.incrementSpawn(runEntity, ownerTriggerId)
                : 0;
        int currentEpoch = ownerTriggerId != null
                ? triggerEpochManager.getGlobalEpochForDag(runId, ownerTriggerId)
                : triggerEpochManager.getCurrentEpoch(runId);

        // 5b. Sync dagLastEpoch with snapshot's DagState.currentEpoch.
        // After AUTO execution, resetForNextCycle advances DagState.currentEpoch via
        // prepareDagForNextCycle but dagLastEpoch in metadata stays at the trigger fire's epoch.
        // Without this sync, V2 execution resolves the wrong epoch, writes node completions
        // to an inactive epoch, and changes become invisible to computeFlatSet.
        if (ownerTriggerId != null) {
            var dagState = snapshot.getDags().get(ownerTriggerId);
            if (dagState != null && dagState.getCurrentEpoch() != currentEpoch) {
                int snapshotEpoch = dagState.getCurrentEpoch();
                logger.info("[RerunService] Epoch mismatch: dagLastEpoch={}, snapshot.currentEpoch={}. Syncing to {}",
                        currentEpoch, snapshotEpoch, snapshotEpoch);
                currentEpoch = snapshotEpoch;
            }
        }

        // 5c. Cancel active signals for EVERY node being reset - not just the target.
        // A downstream node (approval, wait timer, interface __continue) may be awaiting a
        // signal from the pre-rerun pass; resetDagAndSetReady wipes it from
        // awaitingSignalNodeIds but the workflow_signal_waits row would stay PENDING.
        // Resolving such a stale signal later (signals carry no spawn) would resume onto
        // the freshly reset DAG state. Surgical per-node cancel - cancelByDagAndEpoch
        // would also kill signals of untouched parallel-branch siblings.
        //
        // Scoped to the rerun's current epoch (resolved above, after the 5b sync): the
        // reset only touches the current epoch's state, so a reset node's signal in an
        // OLDER active epoch (multi-epoch SBS) or a deliberately cross-cycle non-blocking
        // interface signal from a closed epoch must survive - cancelling those would
        // strand waits the reset never touched.
        if (unifiedSignalService != null) {
            int cancelled = unifiedSignalService.cancelForNodes(runId, stepsToReset, currentEpoch);
            if (cancelled > 0) {
                logger.info("[RerunService] Cancelled {} active signals on reset nodes (target + downstream): runId={}, epoch={}",
                        cancelled, runId, currentEpoch);
            }
        }

        // 6. Update the run entity with rerun metadata
        // Re-load after incrementSpawn which saved its own locked copy
        runEntity = runRepository.findByRunIdPublic(runId)
                .orElseThrow(() -> new IllegalStateException("Run not found after spawn increment: " + runId));
        Map<String, Object> metadata = runEntity.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put("lastRerunStepId", stepId);
        metadata.put("lastRerunTime", Instant.now().toString());
        metadata.put("lastRerunSpawn", newSpawn);
        // Store reset steps for state reconstruction (avoids recomputation)
        metadata.put("resetSteps", new ArrayList<>(stepsToReset));

        // Sync dagLastEpoch and global currentEpoch in metadata to match snapshot
        if (ownerTriggerId != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dagLastEpoch = metadata.get("dagLastEpoch") instanceof Map
                    ? new HashMap<>((Map<String, Object>) metadata.get("dagLastEpoch"))
                    : new HashMap<>();
            dagLastEpoch.put(ownerTriggerId, currentEpoch);
            metadata.put("dagLastEpoch", dagLastEpoch);
            // Keep global currentEpoch >= dagLastEpoch for all triggers
            int globalEpoch = metadata.get("currentEpoch") instanceof Number
                    ? ((Number) metadata.get("currentEpoch")).intValue() : 0;
            if (currentEpoch > globalEpoch) {
                metadata.put("currentEpoch", currentEpoch);
            }
        }

        runEntity.setMetadata(metadata);
        runEntity.setStatus(RunStatus.RUNNING);
        runEntity.setUpdatedAt(Instant.now());
        runRepository.save(runEntity);

        // 7. Clear cached state before reconstructing. Exclude STREAMING domain:
        // run is set to RUNNING above (line 243), so the WS seq counter and snapshot
        // throttle must survive across the rerun - purging mid-run causes deferred
        // publishes from the pre-rerun state to collide with post-rerun seqs (FE
        // strict-< drop, UI freeze). Symmetric with SBS refire fix (2026-05-05 audit).
        resumeService.clearCachedStateForRerun(runId,
            Set.of(RunScopedCache.CacheDomain.STREAMING));

        // 8. Reset StateSnapshot AND mark target as READY in ONE atomic operation
        // This is more efficient (1 DB operation instead of 2) and eliminates sync issues.
        // ownerTriggerId targets the READY marker at the rerun target's own DAG - the flat
        // fallback resolves an arbitrary DAG in multi-trigger workflows.
        stateSnapshotService.resetDagAndSetReady(runId, stepsToReset, stepId, ownerTriggerId);
        logger.info("[RerunService] Reset StateSnapshot: removed {} steps, marked {} as READY (ownerTriggerId={})",
                stepsToReset.size(), stepId, ownerTriggerId);

        // 9. Reconstruct state and get new ready steps
        WorkflowRunState newState = resumeService.reconstructStateForApi(runId);

        // 10. Send streaming event for UI update (always rebuild execution from DB)
        WorkflowExecution execution = contextManager.rebuildExecutionContext(runId, newState);
        streamingService.sendRerunEvent(execution, stepId, stepsToReset, currentEpoch);

        // 2026-05-04 hot-fix (audit TR-3): align with WsEventSequencer counter
        // so the FE.lastKnownSeq update (overwritten on rerun via response.seq)
        // doesn't drop to a smaller value than already-streamed WS events.
        // Math.max guard handles unwired sequencer (tests).
        // Hoisted (2026-05-06): getSnapshot triggers a Jackson JSONB parse -
        // make the single-call invariant explicit instead of duplicating it
        // across both ternary branches.
        long snapshotSeq = stateSnapshotService.getSnapshot(runId).getSeq();
        long currentSeq = (wsEventSequencer != null)
                ? Math.max(wsEventSequencer.currentSeq(runId), snapshotSeq)
                : snapshotSeq;

        logger.info("[RerunService] Re-run initiated. Epoch: {}, Spawn: {}, Seq: {}, Reset steps: {}, Ready steps: {}",
                currentEpoch, newSpawn, currentSeq, stepsToReset.size(), newState.readySteps());

        return new RerunResult(
                runId,
                stepId,
                currentEpoch,
                newSpawn,
                stepsToReset,
                newState.readySteps(),
                newState.status().getValue(),
                currentSeq,
                ownerTriggerId
        );
    }

    /**
     * Finds all downstream steps (successors) from a given node.
     * Uses BFS to traverse the graph and collect all reachable nodes.
     * Also includes loop body steps when a loop node is encountered.
     *
     * @param graph      The workflow graph
     * @param startNodeId The starting node ID
     * @return Set of all downstream step IDs (normalized keys)
     */
    private Set<String> findAllDownstreamSteps(WorkflowGraph graph, NodeId startNodeId) {
        Set<String> downstream = new LinkedHashSet<>();
        Set<NodeId> visited = new HashSet<>();
        Queue<NodeId> queue = new LinkedList<>();

        // Start with immediate successors and loop body (not the start node itself)
        WorkflowNode startNode = graph.getNodeOrNull(startNodeId);
        if (startNode != null) {
            addNodesToQueue(startNode.successors(), visited, queue);
            // If start node is a loop, also include its body steps
            if (startNode.isLoop()) {
                addNodesToQueue(startNode.loopBody(), visited, queue);
            }
        }

        // BFS traversal
        while (!queue.isEmpty()) {
            NodeId current = queue.poll();
            downstream.add(current.toKey());

            WorkflowNode currentNode = graph.getNodeOrNull(current);
            if (currentNode != null) {
                addNodesToQueue(currentNode.successors(), visited, queue);
                // If current node is a loop, also include its body steps
                if (currentNode.isLoop()) {
                    addNodesToQueue(currentNode.loopBody(), visited, queue);
                }
            }
        }

        return downstream;
    }

    /**
     * Helper to add nodes to BFS queue if not already visited.
     */
    private void addNodesToQueue(List<NodeId> nodes, Set<NodeId> visited, Queue<NodeId> queue) {
        for (NodeId nodeId : nodes) {
            if (!visited.contains(nodeId)) {
                queue.add(nodeId);
                visited.add(nodeId);
            }
        }
    }

    /**
     * Close the trigger cycle after a rerun's AUTO-mode auto-execution ran to quiescence.
     *
     * <p>A rerun re-opens the armed epoch ({@code resetDagAndSetReady}) and executes
     * OUTSIDE a trigger fire, so the fire-path cycle close never runs for it. Without
     * this call the re-opened epoch stays in activeEpochs forever, hasAnyActiveEpoch()
     * never drops back to false, and the run can never re-arm to WAITING_TRIGGER - the
     * zombie scanner (OrchestrationRecoveryService) then fails the armed run after the
     * no-progress threshold.
     *
     * <p>Reusable-owner runs only: re-arming is the reusable cycle's semantic; one-shot
     * runs are finalized by the normal completion path. The owner trigger is resolved
     * through the canonical plan parser ({@code WorkflowPlan.fromMap} +
     * {@code Trigger.getNormalizedKey}), so label-less triggers (id-keyed) and type-less
     * triggers (record defaults) behave exactly like the rerun's own owner resolution.
     * Best-effort: failures are logged, never thrown - the rerun itself succeeded, only
     * the re-arm is at stake. Delegates to
     * {@link SignalResumeService#performDeferredReset}, the shared single reset entry
     * point, which self-defers while blocking signals or async agents are still pending
     * for the epoch.
     */
    public void closeCycleAfterAutoExecution(String runId, String ownerTriggerId, int rerunEpoch) {
        if (signalResumeService == null || ownerTriggerId == null) {
            return;
        }
        try {
            WorkflowRunEntity run = runRepository.findByRunIdPublic(runId).orElse(null);
            if (run == null || run.getPlan() == null || run.getPlan().isEmpty()) {
                return;
            }
            WorkflowPlan plan = WorkflowPlan.fromMap(run.getPlan(),
                    run.getWorkflow() != null ? run.getWorkflow().getId().toString() : runId,
                    run.getTenantId());
            Trigger owner = plan.getTriggers() == null ? null : plan.getTriggers().stream()
                    .filter(t -> ownerTriggerId.equals(t.getNormalizedKey()))
                    .findFirst().orElse(null);
            if (owner == null || !TriggerType.isReusable(owner)) {
                return;
            }
            signalResumeService.performDeferredReset(runId, ownerTriggerId, rerunEpoch);
        } catch (Exception e) {
            logger.warn("[RerunService] Cycle close after rerun failed for runId={} triggerId={} epoch={}: {}",
                    runId, ownerTriggerId, rerunEpoch, e.getMessage(), e);
        }
    }

    /**
     * Gets the number of re-run attempts for a specific step.
     *
     * @param runId  The workflow run ID
     * @param stepId The step ID
     * @return The number of attempts (1 = first execution, 2+ = re-runs)
     */
    public int getStepAttemptCount(String runId, String stepId) {
        List<WorkflowStepDataEntity> attempts = stepDataRepository
                .findByRunIdAndNormalizedKeyOrderByEpochDesc(runId, stepId);
        return attempts.size();
    }

    /**
     * Gets the history of attempts for a specific step.
     *
     * @param runId  The workflow run ID
     * @param stepId The step ID
     * @return List of step attempt records, ordered by epoch (newest first)
     */
    public List<StepAttemptRecord> getStepHistory(String runId, String stepId) {
        List<WorkflowStepDataEntity> attempts = stepDataRepository
                .findByRunIdAndNormalizedKeyOrderByEpochDesc(runId, stepId);

        return attempts.stream()
                .map(entity -> new StepAttemptRecord(
                        entity.getEpoch(),
                        entity.getStatus(),
                        entity.getStartTime(),
                        entity.getEndTime(),
                        entity.getErrorMessage(),
                        entity.getOutputStorageId()
                ))
                .toList();
    }

    /**
     * Result of a re-run operation.
     *
     * @param runId          The workflow run ID
     * @param stepId         The step that was re-run from
     * @param epoch          The epoch number (unchanged during rerun)
     * @param spawn          The new spawn number
     * @param resetSteps     The set of steps that were reset
     * @param readySteps     The new set of ready steps
     * @param status         The current workflow status
     * @param ownerTriggerId The trigger/DAG owning the rerun target - the caller needs it
     *                       to close the re-opened epoch once the auto-execution finishes
     *                       (a rerun runs outside a trigger fire, so the normal fire-path
     *                       cycle close never covers it)
     */
    public record RerunResult(
            String runId,
            String stepId,
            int epoch,
            int spawn,
            Set<String> resetSteps,
            Set<String> readySteps,
            String status,
            long seq,
            String ownerTriggerId
    ) {}

    /**
     * Record of a single step execution attempt.
     *
     * @param epoch           The epoch number for this attempt
     * @param status          The execution status
     * @param startTime       When the attempt started
     * @param endTime         When the attempt ended
     * @param errorMessage    Error message if failed
     * @param outputStorageId The output storage ID (reference to stored output)
     */
    public record StepAttemptRecord(
            int epoch,
            String status,
            Instant startTime,
            Instant endTime,
            String errorMessage,
            UUID outputStorageId
    ) {}
}

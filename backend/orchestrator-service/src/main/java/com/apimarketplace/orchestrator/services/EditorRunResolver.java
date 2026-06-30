package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Centralized "find or create" run resolver for editor-initiated executions.
 *
 * <p>Used by both the REST controller ({@code WorkflowExecutionController}) and the
 * agent tool ({@code AgentWorkflowFireService}) to ensure consistent behavior:
 *
 * <ul>
 *   <li><b>Same version, existing live run (same mode, compatible topology)</b> &rarr; reuse it
 *       (each trigger fire accumulates a new epoch into that run)</li>
 *   <li><b>Same version, no reusable run</b> &rarr; create a new run</li>
 *   <li><b>New version</b> &rarr; create a new run</li>
 * </ul>
 *
 * <p>Reusing runs allows epoch accumulation for better visualization.
 */
@Service
public class EditorRunResolver {

    private static final Logger log = LoggerFactory.getLogger(EditorRunResolver.class);

    /**
     * Statuses in which the existing editor run is still "live" and must absorb the next
     * fire as a new epoch instead of spawning a duplicate run at the same version.
     *
     * <p>A run mid-epoch sits in RUNNING, and PAUSED while blocked on a signal
     * (wait timer / user approval / interface). The fire path
     * ({@code ReusableTriggerService.executeTrigger}) fully supports firing on an active
     * run - StateSnapshot v3 keeps one DAG per trigger, AUTO mode allows concurrent
     * epochs, SBS auto-closes stale ones - and the production lane
     * ({@code ProductionRunResolver.TRUSTED_STATUSES}) already attaches webhook fires to
     * RUNNING/PAUSED runs. Restricting editor reuse to WAITING_TRIGGER made any fire
     * that arrived while the previous epoch was still in flight (e.g. an agent firing a
     * second trigger while the first epoch awaits a WAIT_TIMER) mint a duplicate
     * same-version run stuck at epoch 1.
     *
     * <p>Terminal statuses (COMPLETED/FAILED/CANCELLED/…) stay excluded - a finished run
     * is history, not a fire target. PENDING is pre-start and never reached by editor runs.
     */
    private static final List<RunStatus> REUSABLE_STATUSES = List.of(
            RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED);

    private final WorkflowPlanVersionService versionService;
    private final WorkflowExecutionService executionService;
    private final WorkflowRunRepository runRepository;

    public EditorRunResolver(WorkflowPlanVersionService versionService,
                             WorkflowExecutionService executionService,
                             WorkflowRunRepository runRepository) {
        this.versionService = versionService;
        this.executionService = executionService;
        this.runRepository = runRepository;
    }

    /**
     * Result of resolving an editor run.
     *
     * @param runEntity  the resolved (reused or newly created) run
     * @param execution  the WorkflowExecution (null when reusing an existing run)
     * @param planVersion the resolved plan version
     * @param reused     true if an existing live run (see {@code REUSABLE_STATUSES}) was reused
     */
    public record Resolution(WorkflowRunEntity runEntity, WorkflowExecution execution,
                              int planVersion, boolean reused) {}

    /**
     * Resolve or create a run for an editor-initiated execution.
     *
     * @param workflow      the workflow entity
     * @param plan          the parsed workflow plan (from canvas or DB)
     * @param dataInputs    data inputs for the execution
     * @param tenantId      the user ID
     * @param requestedMode the execution mode requested (AUTOMATIC or STEP_BY_STEP)
     * @return resolution containing the run entity, plan version, and whether it was reused
     */
    @Transactional
    public Resolution findOrCreateRun(WorkflowEntity workflow, WorkflowPlan plan,
                                      Map<String, Object> dataInputs, String tenantId,
                                      ExecutionMode requestedMode) {
        UUID workflowId = workflow.getId();

        // Step 1: resolve plan version. Executions never mint version numbers:
        // canvas content matching the latest version is a read-only resolve; drifted
        // content overwrites the latest version in place (same number). New numbers
        // come from explicit save paths only.
        int planVersion = versionService.resolveContentVersionForExecution(
                workflowId, plan.getOriginalPlan(), tenantId);
        log.info("[EditorRunResolver] Resolved planVersion={} for workflow={}, requestedMode={}",
                planVersion, workflowId, requestedMode);

        // Step 2: look for an existing live run at this version IN the requested execution
        // mode. Live = WAITING_TRIGGER (between fires) OR RUNNING/PAUSED (previous epoch
        // still in flight / blocked on a signal) - see REUSABLE_STATUSES. The query filters
        // by mode so an automatic re-execute finds its live automatic run even when a newer
        // step-by-step run exists at the same version (and vice versa) - epoch accumulation
        // only works within the same mode because the execution engine behaves differently;
        // a mode switch starts a fresh run (the one legitimate same-version duplicate).
        Optional<WorkflowRunEntity> reusable = runRepository
                .findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                        workflowId, planVersion, requestedMode, REUSABLE_STATUSES);

        if (reusable.isPresent()) {
            WorkflowRunEntity existing = reusable.get();
            // Topology guard: with stable version numbers a structurally edited canvas
            // now resolves to the SAME version as the old run. StateSnapshot indexes
            // per-node counters by node id, so such a run must not be reused - fall
            // through to a fresh run (clean snapshot), still at the same version.
            // Param-only edits stay reusable: the fire path propagates them to
            // run.plan (updateRunPlan payload or the unpinned passive-fire refresh).
            Map<String, Object> canvasContent = plan.getOriginalPlan();
            boolean structurallyCompatible = existing.getPlan() == null || canvasContent == null
                    || existing.getPlan().equals(canvasContent)
                    || com.apimarketplace.orchestrator.utils.PlanTopology.areCompatible(
                            existing.getPlan(), canvasContent);
            if (!structurallyCompatible) {
                log.info("[EditorRunResolver] Run {} at planVersion={} is structurally incompatible with the canvas - creating a fresh run",
                        existing.getRunIdPublic(), planVersion);
            } else {
                // Re-adopt as the current-canvas run: clear any replay flag left by a
                // prior findOrCreateRunForVersion reuse, otherwise the passive-fire
                // refresh in ReusableTriggerService would keep skipping workflow.plan
                // propagation for this run forever.
                Map<String, Object> existingMeta = existing.getMetadata();
                if (existingMeta != null && existingMeta.get("__versionReplay__") != null) {
                    Map<String, Object> cleared = new HashMap<>(existingMeta);
                    cleared.remove("__versionReplay__");
                    existing.setMetadata(cleared);
                    runRepository.save(existing);
                }
                log.info("[EditorRunResolver] Reusing existing run={} at planVersion={} (status={}, mode={}) for workflow={}",
                        existing.getRunIdPublic(), planVersion, existing.getStatus(), requestedMode, workflowId);
                return new Resolution(existing, null, planVersion, true);
            }
        }

        // Step 3: no reusable run - create a new one
        WorkflowExecution execution = executionService.createExecution(plan, dataInputs, planVersion);
        String runId = execution.getRunId();

        WorkflowRunEntity runEntity = runRepository.findByRunIdPublic(runId)
                .orElseThrow(() -> new IllegalStateException("Run not found after creation: " + runId));

        // Mark as editor-initiated directly on the fetched entity (no extra DB lookup)
        Map<String, Object> metadata = runEntity.getMetadata() != null
                ? new HashMap<>(runEntity.getMetadata()) : new HashMap<>();
        metadata.put("__editorRun__", true);
        runEntity.setMetadata(metadata);
        runRepository.save(runEntity);

        log.info("[EditorRunResolver] Created new run={} at planVersion={} for workflow={} (__editorRun__=true)",
                runId, planVersion, workflowId);

        return new Resolution(runEntity, execution, planVersion, false);
    }

    /**
     * Resolve or create an editor run targeting an EXISTING plan version - no new version is created.
     *
     * <p>Used when the agent explicitly fires a specific historical version
     * ({@code workflow(action='execute', id='...', version=N)}). The caller loads the frozen
     * plan from {@code WorkflowPlanVersionEntity} and passes it here with its version number.
     *
     * <p>Contrast with {@link #findOrCreateRun}, which treats the passed plan as the canvas
     * state and resolves it against the latest version (overwrite-in-place on drift,
     * never minting a new number).
     *
     * @param workflow         the workflow entity
     * @param versionedPlan    the parsed plan loaded from the targeted version
     * @param planVersion      the version number (must correspond to {@code versionedPlan})
     * @param dataInputs       data inputs for the execution
     * @param tenantId         the user ID
     * @param requestedMode    the execution mode requested
     * @return resolution containing the run entity, plan version, and whether it was reused
     */
    @Transactional
    public Resolution findOrCreateRunForVersion(WorkflowEntity workflow, WorkflowPlan versionedPlan,
                                                 int planVersion, Map<String, Object> dataInputs,
                                                 String tenantId, ExecutionMode requestedMode) {
        UUID workflowId = workflow.getId();
        log.info("[EditorRunResolver] findOrCreateRunForVersion workflow={} planVersion={} mode={}",
                workflowId, planVersion, requestedMode);

        Optional<WorkflowRunEntity> reusable = runRepository
                .findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                        workflowId, planVersion, requestedMode, REUSABLE_STATUSES);

        if (reusable.isPresent()) {
            WorkflowRunEntity existing = reusable.get();
            // The reused run may have been created by findOrCreateRun (current-canvas
            // editor run, no replay flag) or carry a plan that drifted from the stored
            // version content (legacy in-run edits). A replay must execute EXACTLY the
            // stored version-N content: re-freeze the plan from the version table and
            // stamp __versionReplay__ so the passive-fire refresh in
            // ReusableTriggerService leaves this run alone.
            //
            // Topology guard: StateSnapshot indexes per-node counters by node id, so a
            // structurally different plan must never be swapped onto a populated run
            // (legacy rows stamped at version N can carry structural drift). In that
            // case skip reuse and fall through to a fresh run with a clean snapshot.
            Map<String, Object> versionContent = versionedPlan.getOriginalPlan();
            boolean refreezeSafe = existing.getPlan() == null || versionContent == null
                    || existing.getPlan().equals(versionContent)
                    || com.apimarketplace.orchestrator.utils.PlanTopology.areCompatible(
                            existing.getPlan(), versionContent);
            if (refreezeSafe) {
                Map<String, Object> metadata = existing.getMetadata() != null
                        ? new HashMap<>(existing.getMetadata()) : new HashMap<>();
                metadata.put("__editorRun__", true);
                metadata.put("__versionReplay__", planVersion);
                existing.setMetadata(metadata);
                if (versionContent != null) {
                    existing.setPlan(new HashMap<>(versionContent));
                }
                runRepository.save(existing);
                log.info("[EditorRunResolver] Reusing run={} at requested planVersion={} for workflow={} (plan re-frozen, __versionReplay__ stamped)",
                        existing.getRunIdPublic(), planVersion, workflowId);
                return new Resolution(existing, null, planVersion, true);
            }
            log.info("[EditorRunResolver] Run {} at planVersion={} has structural drift from the stored version content - creating a fresh replay run",
                    existing.getRunIdPublic(), planVersion);
        }

        WorkflowExecution execution = executionService.createExecution(versionedPlan, dataInputs, planVersion);
        String runId = execution.getRunId();

        WorkflowRunEntity runEntity = runRepository.findByRunIdPublic(runId)
                .orElseThrow(() -> new IllegalStateException("Run not found after creation: " + runId));

        Map<String, Object> metadata = runEntity.getMetadata() != null
                ? new HashMap<>(runEntity.getMetadata()) : new HashMap<>();
        metadata.put("__editorRun__", true);
        metadata.put("__versionReplay__", planVersion);
        runEntity.setMetadata(metadata);
        runRepository.save(runEntity);

        log.info("[EditorRunResolver] Created run={} at replayed planVersion={} for workflow={}",
                runId, planVersion, workflowId);

        return new Resolution(runEntity, execution, planVersion, false);
    }
}

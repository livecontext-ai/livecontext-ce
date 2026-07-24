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

    /**
     * Optional: mock-mode gate cache, invalidated when {@code __mockMode__} is
     * reconciled on a reused run so the next node execution sees the fresh flag
     * without waiting out the gate's TTL. Null in unit tests.
     */
    private com.apimarketplace.orchestrator.execution.v2.engine.MockRunGate mockRunGate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMockRunGate(com.apimarketplace.orchestrator.execution.v2.engine.MockRunGate mockRunGate) {
        this.mockRunGate = mockRunGate;
    }

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
        return findOrCreateRun(workflow, plan, dataInputs, tenantId, requestedMode, null);
    }

    /**
     * Overload with the run-level mock override ({@code mockMode}: null = default,
     * "off" = ignore all mocks this run, "all_mcp" = catalog-example dry-run for
     * every mcp catalog tool). The FIRE REQUEST decides: the flag is stamped on
     * create AND reconciled on reuse, so a refire without the override removes it.
     * Only editor paths reach this resolver - production runs never carry the flag.
     */
    @Transactional
    public Resolution findOrCreateRun(WorkflowEntity workflow, WorkflowPlan plan,
                                      Map<String, Object> dataInputs, String tenantId,
                                      ExecutionMode requestedMode, String mockMode) {
        UUID workflowId = workflow.getId();
        String normalizedMockMode = normalizeMockMode(mockMode);

        // Step 1: resolve plan version. Executions never mint version numbers:
        // canvas content matching the latest version is a read-only resolve; drifted
        // content overwrites the latest version in place (same number). New numbers
        // come from explicit save paths only.
        //
        // On a pinned workflow, canvas content equal to the PINNED version resolves to
        // the pinned number (read-only) rather than the latest draft's. Step 2 then
        // looks for a live run at that number, so an editor canvas sitting on the
        // pinned content can collide with the live production run - the same collision
        // that already existed when the pin IS the latest version, now also reachable
        // when the pin trails a draft (e.g. after restoring the pinned version into
        // the canvas). Two shapes, both pre-existing and both widened here:
        //   - ADOPTED: reuse is mode-filtered, but an editor AUTOMATIC fire matches a
        //     production AUTOMATIC run exactly. The adopted run then has
        //     __versionReplay__ stripped and reconcileMockMode applied below, so an
        //     editor fire carrying a mock override leaves the production run mocking
        //     its tools on its next scheduled fire.
        //   - CREATED: when reuse is refused (mode mismatch, topology guard), a NEW
        //     editor run is created AT the pinned version. ProductionRunResolver
        //     .lookupByPolicy selects on (workflowId, pinnedVersion, status) ordered
        //     by started_at DESC with no __editorRun__ exclusion, so that fresh run
        //     can shadow the real production run for the next trigger fire.
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

        if (reusable.isPresent() && isProductionRun(workflow, reusable.get())) {
            // Never adopt the live PRODUCTION run into an editor/agent fire. Reuse is
            // keyed on (workflowId, planVersion, mode, live status) with no notion of
            // who owns the run, so on a pinned workflow an editor canvas sitting on
            // the pinned content matches production exactly. Adopting it would apply
            // the block below to production state: the mock override in particular
            // would then make the next SCHEDULED fire mock its tools. Fall through to
            // a fresh editor run instead.
            log.info("[EditorRunResolver] Run {} at planVersion={} is the live production run - not adopting it "
                    + "for an editor fire on workflow={}; creating a separate run",
                    reusable.get().getRunIdPublic(), planVersion, workflowId);
            reusable = Optional.empty();
        }

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
                // propagation for this run forever. Also reconcile the mock override -
                // the fire request decides, so a refire without it must REMOVE the flag.
                Map<String, Object> existingMeta = existing.getMetadata() != null
                        ? new HashMap<>(existing.getMetadata()) : new HashMap<>();
                boolean metaChanged = existingMeta.remove("__versionReplay__") != null;
                metaChanged |= reconcileMockMode(existingMeta, normalizedMockMode);
                if (metaChanged) {
                    existing.setMetadata(existingMeta);
                    runRepository.save(existing);
                    invalidateMockGate(existing.getRunIdPublic());
                }
                log.info("[EditorRunResolver] Reusing existing run={} at planVersion={} (status={}, mode={}, mockMode={}) for workflow={}",
                        existing.getRunIdPublic(), planVersion, existing.getStatus(), requestedMode,
                        normalizedMockMode, workflowId);
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
        reconcileMockMode(metadata, normalizedMockMode);
        runEntity.setMetadata(metadata);
        runRepository.save(runEntity);
        invalidateMockGate(runId);

        log.info("[EditorRunResolver] Created new run={} at planVersion={} for workflow={} (__editorRun__=true, mockMode={})",
                runId, planVersion, workflowId, normalizedMockMode);

        return new Resolution(runEntity, execution, planVersion, false);
    }

    // ===== Mock-mode reconciliation =====

    /**
     * Normalizes the requested run-level mock override. Null / blank / "default"
     * mean "no override" (the metadata key is absent and enabled node mocks apply).
     *
     * @throws IllegalArgumentException on an unknown value (clear failure at fire
     *         time instead of a silently-real run the caller believed was mocked)
     */
    private static String normalizeMockMode(String mockMode) {
        if (mockMode == null || mockMode.isBlank()) {
            return null;
        }
        String normalized = mockMode.trim().toLowerCase(java.util.Locale.ROOT);
        if ("default".equals(normalized)) {
            return null;
        }
        if (com.apimarketplace.orchestrator.execution.v2.engine.MockRunGate.MODE_OFF.equals(normalized)
                || com.apimarketplace.orchestrator.execution.v2.engine.MockRunGate.MODE_ALL_MCP.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException(
                "Invalid mockMode '" + mockMode + "'. Valid values: 'off' (ignore all mocks this run), "
                        + "'all_mcp' (mock every API tool node with its catalog example), or omit for the "
                        + "default (enabled node mocks apply).");
    }

    /**
     * Is {@code run} the workflow's live production run?
     *
     * <p>Identified by the {@code production_run_id} FK, the same pointer
     * {@code ProductionRunResolver.BY_PRODUCTION_RUN_ID} follows, so editor reuse and
     * production selection agree on which run belongs to production. Only meaningful
     * on a pinned workflow: the FK is set when a version is pinned.
     *
     * <p>Deliberately NOT metadata-based. A production run carries no positive marker
     * (the absence of {@code __editorRun__} is not proof - a run created before the
     * flag existed has no metadata either), so keying on the FK is the only
     * unambiguous test.
     */
    private static boolean isProductionRun(WorkflowEntity workflow, WorkflowRunEntity run) {
        if (workflow == null || run == null) {
            return false;
        }
        UUID productionRunId = workflow.getProductionRunId();
        return productionRunId != null && productionRunId.equals(run.getId());
    }

    /**
     * Reconciles {@code __mockMode__} on the run metadata to the requested state.
     *
     * @return true when the metadata map was changed
     */
    private static boolean reconcileMockMode(Map<String, Object> metadata, String normalizedMockMode) {
        String key = com.apimarketplace.orchestrator.execution.v2.engine.MockRunGate.MOCK_MODE_METADATA_KEY;
        Object current = metadata.get(key);
        if (normalizedMockMode == null) {
            return metadata.remove(key) != null;
        }
        if (normalizedMockMode.equals(current)) {
            return false;
        }
        metadata.put(key, normalizedMockMode);
        return true;
    }

    private void invalidateMockGate(String runId) {
        if (mockRunGate != null) {
            mockRunGate.invalidate(runId);
        }
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
        return findOrCreateRunForVersion(workflow, versionedPlan, planVersion, dataInputs,
                tenantId, requestedMode, null);
    }

    /** Overload with the run-level mock override - same semantics as {@link #findOrCreateRun}. */
    @Transactional
    public Resolution findOrCreateRunForVersion(WorkflowEntity workflow, WorkflowPlan versionedPlan,
                                                 int planVersion, Map<String, Object> dataInputs,
                                                 String tenantId, ExecutionMode requestedMode,
                                                 String mockMode) {
        UUID workflowId = workflow.getId();
        String normalizedMockMode = normalizeMockMode(mockMode);
        log.info("[EditorRunResolver] findOrCreateRunForVersion workflow={} planVersion={} mode={} mockMode={}",
                workflowId, planVersion, requestedMode, normalizedMockMode);

        Optional<WorkflowRunEntity> reusable = runRepository
                .findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                        workflowId, planVersion, requestedMode, REUSABLE_STATUSES);

        if (reusable.isPresent() && isProductionRun(workflow, reusable.get())) {
            // Same rule as findOrCreateRun, and it bites harder here: a replay
            // re-freezes the run's plan to the stored version content and stamps
            // __versionReplay__, which tells ReusableTriggerService to stop
            // propagating workflow.plan to that run. Doing that to the production run
            // would freeze production on a replayed version indefinitely.
            log.info("[EditorRunResolver] Run {} at planVersion={} is the live production run - not adopting it "
                    + "for a version replay on workflow={}; creating a separate run",
                    reusable.get().getRunIdPublic(), planVersion, workflowId);
            reusable = Optional.empty();
        }

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
                reconcileMockMode(metadata, normalizedMockMode);
                existing.setMetadata(metadata);
                if (versionContent != null) {
                    existing.setPlan(new HashMap<>(versionContent));
                }
                runRepository.save(existing);
                invalidateMockGate(existing.getRunIdPublic());
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
        reconcileMockMode(metadata, normalizedMockMode);
        runEntity.setMetadata(metadata);
        runRepository.save(runEntity);
        invalidateMockGate(runId);

        log.info("[EditorRunResolver] Created run={} at replayed planVersion={} for workflow={}",
                runId, planVersion, workflowId);

        return new Resolution(runEntity, execution, planVersion, false);
    }
}

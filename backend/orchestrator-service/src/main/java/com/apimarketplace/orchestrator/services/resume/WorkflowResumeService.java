package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.cache.RunCacheRegistry;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.events.WorkflowRunTerminatedEvent;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager.PausedWorkflowState;
import com.apimarketplace.orchestrator.services.resume.state.StateReconstructor;
import com.apimarketplace.orchestrator.trigger.TriggerEpochManager;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepContextManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Facade service for managing workflow pause/resume functionality and step-by-step execution.
 *
 * This class delegates to specialized services:
 * - {@link ExecutionContextManager} - Execution context lifecycle
 * - {@link StepByStepExecutor} - Step-by-step mode execution
 * - {@link StateReconstructor} - State reconstruction from DB
 *
 * @see ExecutionContextManager
 * @see StepByStepExecutor
 */
@Service
public class WorkflowResumeService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowResumeService.class);

    private final WorkflowRunRepository runRepository;
    private final WorkflowExecutionService executionService;
    private final WorkflowPersistenceService persistenceService;
    private final WorkflowStreamingService streamingService;
    private final RunStateStore runStateStore;
    private final WorkflowCacheManager cacheManager;
    private final StateReconstructor stateReconstructor;
    private final RunCacheRegistry cacheRegistry;

    // Extracted services
    private final ExecutionContextManager contextManager;
    private final StepByStepExecutor stepByStepExecutor;

    // V2 context manager (optional - for clearing split state on rerun)
    @Autowired(required = false)
    private V2StepByStepContextManager v2ContextManager;

    @Autowired(required = false)
    private UnifiedSignalService unifiedSignalService;

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher;

    @Autowired(required = false)
    private com.apimarketplace.trigger.client.TriggerClient triggerClient;

    /** Optional: without it a paused or cancelled workflow's incident closes by itself after a week. */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryService notificationDelivery;

    /**
     * Used by {@link #updateRunPlan(String, Map)} to mirror in-run MOCK edits of
     * editor runs into the workflow's plan (their durable home - run.plan is
     * refreshed from workflow.plan on every fire). {@code required=false} so
     * narrow test wirings boot without it; the mirror is then skipped.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.repository.WorkflowRepository workflowRepository;

    /**
     * Plan v4 §1.6 - advisory-lock helper. {@code required=false} so narrow
     * Spring tests boot without it. When null: pessimistic row-lock path
     * remains correctness backstop (pre-§1.6 semantics).
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHelper advisoryLockHelper;

    /**
     * Used by {@link #reactivateWorkflow(String)} to recreate schedule and
     * datasource-subscription rows from the pinned plan when an upstream
     * cleanup (orphan reaper, plan churn) deleted them. Webhook tokens, chat
     * endpoints, and form endpoints are NOT recreated by this - they're
     * REST-created user artifacts; their re-creation requires user action
     * from Settings.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService pinAwareTriggerSyncService;

    /**
     * Used by {@link #reactivateScheduleTriggers(WorkflowEntity)} to count the
     * schedule triggers declared by the pinned plan. The post-reconcile arm
     * compares actual rows against this expected count to detect silent
     * partial-failure (sync recreated some triggers but not all).
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.persistence.ScheduleSyncService scheduleSyncService;

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry pendingAgentRegistry;

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.trigger.EpochConcurrencyLimiter epochConcurrencyLimiter;

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.markup.PlatformMarkupPinService platformMarkupPinService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    /**
     * Aborts in-flight browser-agent sessions so Stop/Cancel actually kills
     * Chromium (the runner keeps browsing otherwise - the node is BLPOP-blocked
     * on the result and never sees the cancel). Optional: absent when the web
     * stack is off, in which case the abort calls are simply skipped.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.tools.websearch.BrowserAgentRunAborter browserAgentRunAborter;

    /**
     * Keeps the run.planVersion ↔ workflow_plan_versions content parity invariant
     * on every {@code run.plan} write in this class: an accepted plan must exist in
     * the version history before the run is stamped with its version number.
     * Optional so narrow unit tests construct cleanly; when absent the legacy
     * behavior (write plan, keep stale planVersion) applies.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.WorkflowPlanVersionService planVersionService;

    private final TriggerEpochManager epochManager;
    private final StateSnapshotService stateSnapshotService;

    public WorkflowResumeService(
            WorkflowRunRepository runRepository,
            WorkflowExecutionService executionService,
            WorkflowPersistenceService persistenceService,
            WorkflowStreamingService streamingService,
            RunStateStore runStateStore,
            WorkflowCacheManager cacheManager,
            StateReconstructor stateReconstructor,
            @Lazy RunCacheRegistry cacheRegistry,
            ExecutionContextManager contextManager,
            StepByStepExecutor stepByStepExecutor,
            TriggerEpochManager epochManager,
            StateSnapshotService stateSnapshotService) {
        this.runRepository = runRepository;
        this.executionService = executionService;
        this.persistenceService = persistenceService;
        this.streamingService = streamingService;
        this.runStateStore = runStateStore;
        this.cacheManager = cacheManager;
        this.stateReconstructor = stateReconstructor;
        this.cacheRegistry = cacheRegistry;
        this.contextManager = contextManager;
        this.stepByStepExecutor = stepByStepExecutor;
        this.epochManager = epochManager;
        this.stateSnapshotService = stateSnapshotService;
    }

    // ========================================================================
    // CACHE MANAGEMENT
    // ========================================================================

    /**
     * Clears all in-memory cached state for a workflow run.
     * Must be called before reconstructing state after a rerun.
     *
     * <p>Uses RunCacheRegistry to clean ALL registered caches including:
     * - StateManagerIntegrationService (removes SnapshotStateListener to prevent state overwrites)
     * - WorkflowCacheManager (paused workflow state, evaluated cores)
     * - RunStateStore, PersistenceService, etc.
     */
    public void clearCachedStateForRerun(String runId) {
        clearCachedStateForRerun(runId, Set.of());
    }

    /**
     * Clears in-memory cached state for a workflow run, excluding the given cache domains.
     *
     * <p>Use this overload from the SBS refire path ({@code ReusableTriggerService}):
     * the run is still active across fires, so {@link RunScopedCache.CacheDomain#STREAMING}
     * caches (monotonic seq counter, active-run cache) must survive between fires -
     * purging them mid-run causes a race where deferred publishes from fire #N collide
     * with seqs of fire #N+1, frontend strict-{@code <} drops the events, UI freezes.
     *
     * @param runId          The workflow run ID
     * @param excludeDomains Cache domains to skip (must not be null; empty = full purge)
     */
    public void clearCachedStateForRerun(String runId, Set<RunScopedCache.CacheDomain> excludeDomains) {
        logger.info("[clearCachedStateForRerun] Clearing caches for runId: {} (exclude={})", runId, excludeDomains);
        int cleanedCount = cacheRegistry.cleanupRun(runId, excludeDomains);
        logger.info("[clearCachedStateForRerun] Cleaned {} caches for runId: {}", cleanedCount, runId);
    }

    // ========================================================================
    // PLAN MANAGEMENT
    // ========================================================================

    /**
     * Refreshes the run's cached plan from the workflow definition.
     *
     * <p>Equivalent to {@link #refreshPlanFromWorkflowDefinition(String, boolean)}
     * with {@code planFromPayload=false}. Kept for internal lambda call sites
     * in this class where no controller payload context is available.
     */
    public WorkflowPlan refreshPlanFromWorkflowDefinition(String runId) {
        return refreshPlanFromWorkflowDefinition(runId, false);
    }

    /**
     * Refreshes the run's cached plan from the workflow definition.
     *
     * In step-by-step mode, users can modify node parameters via the InspectorPanel.
     * This method syncs the run's plan with the latest workflow definition.
     *
     * <p>Topology guard: the same protection applied by {@code ReusableTriggerService}
     * at trigger fires also applies here. If the latest workflow plan has a different
     * node-id set or edge set than the run's frozen plan, the swap would corrupt
     * {@code StateSnapshot} (which indexes per-node counters by node id); we keep the
     * frozen plan and WARN so the user knows their structural edits require a new run.
     *
     * <p>Two guards keep a run on its frozen plan and its {@code plan_version}. The pin
     * contract: on a PINNED workflow nothing is synced onto the production run, nor
     * onto any run carrying no {@code __editorRun__} marker, matching
     * {@link #updateRunPlan(String, Map)}'s predicate. And the index guard: a
     * {@code WAITING_TRIGGER} run with no marker sits inside
     * {@code idx_runs_one_production_waiting}, so re-stamping its version would collide
     * with another parked run, pin or no pin.
     *
     * <p>Everything else still tracks the draft, which is more than editor runs alone:
     * on an unpinned workflow a run carrying no marker follows the draft too, as long
     * as it is not parked in {@code WAITING_TRIGGER}. See the comment at the guard.
     *
     * @param planFromPayload {@code true} when the caller already wrote the user's
     *     intended plan into {@code run.plan} via {@link #updateRunPlan(String, Map)}
     *     (e.g. a step-rerun request that carried a plan in its body). In that case
     *     refreshing from {@code workflow.plan} would silently revert the inspector
     *     edit, so we return the freshly-written {@code run.plan} as-is.
     */
    public WorkflowPlan refreshPlanFromWorkflowDefinition(String runId, boolean planFromPayload) {
        WorkflowRunEntity runEntity = runRepository.findByRunIdPublic(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        if (planFromPayload && runEntity.getPlan() != null) {
            logger.info("[refreshPlanFromWorkflowDefinition] Preserving run plan (set by updateRunPlan) for runId={}", runId);
            return WorkflowPlan.fromMap(runEntity.getPlan(), runEntity.getWorkflow().getId().toString(), runEntity.getTenantId());
        }

        // Version-replay runs execute a frozen historical version - never sync them
        // to the live workflow definition (same guard as ReusableTriggerService).
        Map<String, Object> replayMeta = runEntity.getMetadata();
        if (replayMeta != null && replayMeta.get("__versionReplay__") != null && runEntity.getPlan() != null) {
            logger.info("[refreshPlanFromWorkflowDefinition] Run {} is a version replay at v{} - keeping frozen plan",
                    runId, runEntity.getPlanVersion());
            return WorkflowPlan.fromMap(runEntity.getPlan(), runEntity.getWorkflow().getId().toString(), runEntity.getTenantId());
        }

        WorkflowEntity workflow = runEntity.getWorkflow();
        Map<String, Object> latestPlanMap = workflow.getPlan();
        Map<String, Object> frozenPlan = runEntity.getPlan();

        if (latestPlanMap != null && frozenPlan != null && !latestPlanMap.equals(frozenPlan)
                && !com.apimarketplace.orchestrator.utils.PlanTopology.areCompatible(frozenPlan, latestPlanMap)) {
            logger.warn("[refreshPlanFromWorkflowDefinition] runId={} topology change detected in live plan; keeping frozen plan. Diff: {}",
                    runId,
                    com.apimarketplace.orchestrator.utils.PlanTopology.describeDiff(frozenPlan, latestPlanMap));
            return WorkflowPlan.fromMap(frozenPlan, runEntity.getWorkflow().getId().toString(), runEntity.getTenantId());
        }

        // TWO independent reasons to keep the frozen plan. The guard used to express
        // only a narrow slice of the first, and nothing of the second.
        //
        // 1. PIN CONTRACT. On a pinned workflow the production run executes the
        //    immutable pinned version, so syncing it to the draft would swap production
        //    onto unpinned content while run.planVersion still matches the pin, passing
        //    the defense-in-depth chokepoint. Editor runs that are NOT the production
        //    run stay exempt: they may iterate against drafts. This is deliberately the
        //    SAME predicate as updateRunPlan's pin guard - the two are siblings, the
        //    rerun caller falls back from that one to this one, and they must agree.
        //    Keying it on the production_run_id FK alone protected only the CURRENT
        //    production run, so a run a later pin had DEMOTED fell straight through.
        //
        // 2. INDEX COLLISION. idx_runs_one_production_waiting (V138) is unique over
        //    (workflow_id, plan_version) for WAITING_TRIGGER rows carrying no
        //    __editorRun__ marker, and it knows nothing about pins. Re-stamping such a
        //    row moves it onto whatever version the draft matches, normally the slot
        //    another parked run already holds, and Postgres rejects it at the next
        //    flush. Prod 2026-08-26: five HTTP 500s on "re-run from step", and the
        //    sixth attempt SUCCEEDED the moment a re-pin made the run production again,
        //    taking this branch. This term is deliberately NOT gated on the pin:
        //    unpinning clears pinned_version and production_run_id but leaves the
        //    parked runs behind, so the identical collision is reachable with no pin
        //    in sight.
        //
        // Runs carrying no marker come from several places: a pin-minted production run
        // (WorkflowPinService.provisionProductionRun mints one per pinned version and
        // retires none - that accumulation is the root cause and is NOT addressed here),
        // a legacy run predating the marker (see EditorRunResolver), a run that
        // EditorRunResolver.findOrCreateRun ADOPTED rather than created (only its create
        // branch stamps the marker; note the sibling findOrCreateRunForVersion DOES
        // stamp on reuse, so this is a property of one method, not of the class), or a
        // showcase clone (RunCloneService copies status and keeps whatever marker the
        // source had). The list is not meant to be exhaustive: what decides the outcome
        // here is the absence of the marker, not the provenance.
        //
        // Note the Java and SQL tests are close but not identical. The index says
        // `__editorRun__ IS NULL OR <> 'true'`, while Boolean.TRUE.equals() also treats
        // the STRING "true" as non-editor. That errs toward keeping the frozen plan for
        // a row the index would not have rejected, which is the safe direction.
        boolean isEditorRun = replayMeta != null && Boolean.TRUE.equals(replayMeta.get("__editorRun__"));
        boolean isProductionRun = isProductionRun(workflow, runEntity);
        boolean pinned = workflow.getPinnedVersion() != null;
        boolean pinContract = pinned && (!isEditorRun || isProductionRun);
        boolean indexCollisionRisk = !isEditorRun && RunStatus.WAITING_TRIGGER == runEntity.getStatus();
        if (pinContract || indexCollisionRisk) {
            logger.warn("[refreshPlanFromWorkflowDefinition] runId={} keeps its frozen plan ({}); "
                    + "the draft is not synced onto it", runId,
                    pinContract
                            ? "workflow is pinned to v" + workflow.getPinnedVersion()
                                    + (isProductionRun ? ", run is the production run" : ", demoted production run")
                            : "run is inside idx_runs_one_production_waiting; re-stamping it would collide");
            Map<String, Object> safePlan = frozenPlan;
            if (safePlan == null && planVersionService != null) {
                // Legacy/corrupt state: the run lost its cached plan. The draft must
                // still not execute here, so reload stored content instead: the PINNED
                // version for the production run of a pinned workflow (mirroring the
                // pin-safety reload in the trigger fire path), and otherwise the run's
                // OWN version, which it claims and would otherwise contradict.
                Integer safeVersion = (pinned && isProductionRun)
                        ? workflow.getPinnedVersion()
                        : runEntity.getPlanVersion();
                if (safeVersion != null) {
                    safePlan = planVersionService
                            .getVersion(workflow.getId(), safeVersion)
                            .map(v -> v.getPlan())
                            .orElse(null);
                }
                if (safePlan != null) {
                    logger.warn("[refreshPlanFromWorkflowDefinition] runId={} had a null cached plan; "
                            + "reloaded v{} from the version history", runId, safeVersion);
                }
            }
            if (safePlan == null) {
                // Last resort, loudly: no frozen plan and no stored row. Executing the
                // draft is the only remaining option, and it is never written back, so
                // this stays a one-shot fallback rather than a durable divergence.
                // The "pin violation" wording is kept for the one case where it is
                // literally true, and only that one: running the LIVE draft AS
                // PRODUCTION on a pinned workflow. It is the phrase a log alert keys
                // on, so it is worth keeping exact - pinContract alone would also fire
                // for a DEMOTED run, which is no longer production and whose fallback,
                // while still wrong, is not a pin violation.
                logger.error("[refreshPlanFromWorkflowDefinition] runId={} has no cached plan and its version is "
                        + "missing from the version history (pinnedVersion={}, planVersion={}) - falling back to "
                        + "the LIVE draft for this execution only.{} Investigate the version history.",
                        runId, workflow.getPinnedVersion(), runEntity.getPlanVersion(),
                        (pinned && isProductionRun) ? " This is a pin violation." : "");
                safePlan = latestPlanMap;
            }
            return WorkflowPlan.fromMap(safePlan,
                    runEntity.getWorkflow().getId().toString(), runEntity.getTenantId());
        }

        // Update run's cached plan + re-stamp planVersion so the run never claims a
        // version whose stored content differs from the plan it will execute.
        runEntity.setPlan(latestPlanMap);
        stampPlanVersion(runEntity, latestPlanMap, null);
        runRepository.save(runEntity);

        logger.info("[refreshPlanFromWorkflowDefinition] Synced run plan with workflow definition for runId={}", runId);

        return WorkflowPlan.fromMap(latestPlanMap, runEntity.getWorkflow().getId().toString(), runEntity.getTenantId());
    }

    /**
     * Updates the run's plan with the provided plan map.
     *
     * In step-by-step mode, the frontend sends the modified plan when executing a step.
     * This ensures that parameter changes made in the InspectorPanel are persisted.
     *
     * <p>Topology guard: the frontend payload is trusted for param/prompt/config
     * changes only. If it introduces/removes nodes or rewires edges, the swap
     * would corrupt {@code StateSnapshot} (node-id-indexed per-epoch counters).
     * Topology-incompatible updates are rejected - the frozen plan is preserved
     * and a WARN is logged. The caller keeps executing the current frozen plan.
     *
     * @param runId The run ID
     * @param planMap The updated plan map from the frontend
     * @return The updated WorkflowPlan when the write succeeded; {@code null} when
     *     the request was a no-op (empty payload) or the topology guard rejected
     *     the change. Callers that need to know whether {@code run.plan} actually
     *     reflects the supplied map (e.g. before tagging the trigger payload with
     *     {@link com.apimarketplace.orchestrator.trigger.ReusableTriggerService#PLAN_FROM_PAYLOAD_MARKER})
     *     must check for non-null.
     */
    @Transactional
    public WorkflowPlan updateRunPlan(String runId, Map<String, Object> planMap) {
        if (planMap == null || planMap.isEmpty()) {
            logger.warn("[updateRunPlan] Empty plan provided for runId={}, skipping update", runId);
            return null;
        }

        WorkflowRunEntity runEntity = runRepository.findByRunIdPublic(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        // Defense-in-depth: refuse payload-driven plan updates on pinned
        // workflows for non-editor runs. Pinning is a contract that
        // production fires execute the immutable pinned version; allowing
        // payload writes here would let a buggy/malicious caller diverge
        // run.plan from the pinned version, after which the pinned-branch
        // resolver in ReusableTriggerService.executeTriggerInternal would
        // happily execute the
        // diverged plan because run.planVersion still matches.
        // Editor runs that are NOT the production run are exempt - they may iterate
        // against drafts even on pinned workflows.
        //
        // The test is the production_run_id FK, NOT the __editorRun__ flag. Pinning
        // PROMOTES an existing run (almost always the editor run the user tested
        // with) and never strips that flag, so the production run of a pinned
        // workflow carries __editorRun__=true. Keying the exemption on the flag
        // therefore exempted exactly the run this guard exists to protect: edits
        // landed on the production run, and the block further down mirrored them
        // into workflow.plan.
        com.apimarketplace.orchestrator.domain.WorkflowEntity workflow = runEntity.getWorkflow();
        Map<String, Object> metadata = runEntity.getMetadata();
        boolean isEditorRun = metadata != null && Boolean.TRUE.equals(metadata.get("__editorRun__"));
        boolean isProductionRun = isProductionRun(workflow, runEntity);
        // Two independent reasons to refuse, both required:
        //   - not an editor run at all (the original guard: a buggy/malicious caller
        //     diverging run.plan from the pinned version), OR
        //   - it IS the production run (the promoted editor run, which carries the
        //     flag and was therefore exempted by the original guard).
        if (workflow != null && workflow.getPinnedVersion() != null && (!isEditorRun || isProductionRun)) {
            logger.warn("[updateRunPlan] runId={} refused: workflow is pinned to v{} ({}); pin a new version to publish edits",
                    runId, workflow.getPinnedVersion(),
                    isProductionRun ? "run is the production run" : "non-editor run");
            return null;
        }

        Map<String, Object> frozenPlan = runEntity.getPlan();
        if (frozenPlan != null && !planMap.equals(frozenPlan)
                && !com.apimarketplace.orchestrator.utils.PlanTopology.areCompatible(frozenPlan, planMap)) {
            logger.warn("[updateRunPlan] runId={} topology change rejected; keeping frozen plan. Diff: {}",
                    runId,
                    com.apimarketplace.orchestrator.utils.PlanTopology.describeDiff(frozenPlan, planMap));
            // Return null so callers can distinguish "wrote the user's plan" from
            // "rejected, run.plan is unchanged". Previously this returned the frozen
            // plan, which led TriggerController to tag PLAN_FROM_PAYLOAD_MARKER even
            // on rejection - silently suppressing the unpinned passive-fire fallback
            // and executing on a stale plan.
            return null;
        }

        // Update run's plan with the provided map. The accepted payload plan must
        // exist in the version history and the run must be stamped with that
        // version - otherwise the run keeps claiming a planVersion whose stored
        // content differs from what it executes (silent version divergence).
        // The stamp never mints a number on the unpinned lane: payload == latest
        // stored version → read-only no-op; drifted → latest version content is
        // overwritten in place (same number).
        runEntity.setPlan(new java.util.HashMap<>(planMap));
        stampPlanVersion(runEntity, planMap, "In-run edit");
        // A replay run that takes an in-run edit is no longer replaying its original
        // version: move the flag to the freshly stamped version so the metadata keeps
        // matching what the run actually executes (passive fires stay frozen on it).
        Map<String, Object> postStampMeta = runEntity.getMetadata();
        if (postStampMeta != null && postStampMeta.get("__versionReplay__") != null
                && runEntity.getPlanVersion() != null) {
            Map<String, Object> updatedMeta = new java.util.HashMap<>(postStampMeta);
            updatedMeta.put("__versionReplay__", runEntity.getPlanVersion());
            runEntity.setMetadata(updatedMeta);
        }
        runRepository.save(runEntity);

        logger.info("[updateRunPlan] Updated run plan for runId={} (planVersion={})", runId, runEntity.getPlanVersion());

        // In-run MOCK edits must outlive the run: run.plan is refreshed FROM
        // workflow.plan on every fire (ReusableTriggerService /
        // refreshPlanFromWorkflowDefinition), so a mock living only in run.plan is
        // wiped by the next refresh and vanishes when the editor reloads from
        // workflow.plan. Editor runs therefore mirror their mock blocks into the
        // workflow plan - the same durable home the workflow-assistant agent
        // writes to (UI/agent parity). Params and other node content stay
        // run-scoped by design; APPLICATION workflows stay untouched (immutable
        // acquired clones, same rule as the execute-time auto-save).
        // Same correction as the pin guard above: the production run carries
        // __editorRun__, so this mirror must additionally exclude it or an edit on
        // production rewrites the workflow's live plan.
        if (isEditorRun && !isProductionRun && workflow != null && !workflow.isApplication() && workflowRepository != null) {
            try {
                Map<String, Object> merged = com.apimarketplace.orchestrator.utils.NodeMockPlanMerger
                        .mergedWorkflowPlanOrNull(planMap, workflow.getPlan());
                if (merged != null) {
                    workflow.setPlan(merged);
                    workflow.setUpdatedAt(Instant.now());
                    workflowRepository.save(workflow);
                    logger.info("[updateRunPlan] Mirrored in-run mock edits into workflow {} plan", workflow.getId());
                }
            } catch (Exception e) {
                // Best-effort: synchronous failures are swallowed so the run keeps
                // executing its updated plan (the mirror is a durability improvement,
                // not a correctness requirement). A failure surfacing only at
                // flush/commit time still rolls back the whole transaction.
                logger.warn("[updateRunPlan] Mock mirror to workflow plan failed for runId={}: {}", runId, e.getMessage());
            }
        }

        return WorkflowPlan.fromMap(planMap, runEntity.getWorkflow().getId().toString(), runEntity.getTenantId());
    }

    /**
     * Is {@code run} the workflow's live production run?
     *
     * <p>Keyed on the {@code production_run_id} FK, the same pointer
     * {@code ProductionRunResolver} follows. Deliberately NOT the
     * {@code __editorRun__} flag: pinning promotes an existing editor run and never
     * strips it, so that flag is true on the production run too and cannot separate
     * "editor iteration" from "production".
     */
    private static boolean isProductionRun(com.apimarketplace.orchestrator.domain.WorkflowEntity workflow,
                                           WorkflowRunEntity run) {
        if (workflow == null || run == null) {
            return false;
        }
        java.util.UUID productionRunId = workflow.getProductionRunId();
        return productionRunId != null && productionRunId.equals(run.getId());
    }

    /**
     * Resolve the version-history entry matching {@code planMap} and stamp it on the
     * run. No-op when the version service bean is absent (narrow test wiring) -
     * callers then keep the legacy stale-version behavior.
     *
     * <p>Runs never mint version numbers: the latest version's stored content is
     * overwritten in place when the executing plan drifted from it
     * ({@link WorkflowPlanVersionService#resolveContentVersionForExecution}), so
     * re-fires of the same run (new epochs) keep the same version instead of
     * inflating the history with "In-run edit" entries.
     *
     * <p>This method is pin-blind by design: the pinned-workflow case is decided in
     * the resolver, which answers with the PINNED number when the executing plan is
     * the pinned content. Whatever it answers is written onto the run verbatim.
     *
     * <p>Both call sites can carry pinned content, and the pinned answer is the
     * correct one at either. Via {@code updateRunPlan} it is reachable for editor
     * runs on a pinned workflow (non-editor runs are refused outright, see the pin
     * guard in that method). Via {@code refreshPlanFromWorkflowDefinition} the plan
     * is {@code workflow.getPlan()}, which is usually the draft but IS the pinned
     * content after a version restore: restoring writes a historical plan into
     * {@code workflows.plan} without minting a version.
     *
     * <p>Exception - version-replay runs ({@code __versionReplay__}): an accepted
     * in-run edit there is NEW content derived from a historical plan; overwriting
     * the latest version with it would clobber the user's most recent save. Replay
     * edits keep the create-version semantics (the flag is then moved to the new
     * version by the caller).
     */
    private void stampPlanVersion(WorkflowRunEntity runEntity, Map<String, Object> planMap, String label) {
        if (planVersionService == null) {
            return;
        }
        try {
            UUID workflowId = runEntity.getWorkflow().getId();
            Map<String, Object> meta = runEntity.getMetadata();
            boolean versionReplay = meta != null && meta.get("__versionReplay__") != null;
            // REQUIRES_NEW: a versioning failure must degrade (WARN + legacy stamp),
            // not mark the caller-owned transaction rollback-only - which would fail
            // the whole request at commit despite this catch.
            int version = versionReplay
                    ? planVersionService.createVersionInNewTransaction(
                            workflowId, planMap, runEntity.getTenantId(), label)
                    : planVersionService.resolveContentVersionForExecutionInNewTransaction(
                            workflowId, planMap, runEntity.getTenantId());
            runEntity.setPlanVersion(version);
        } catch (Exception e) {
            logger.warn("[stampPlanVersion] Failed to stamp plan version for run {}: {}",
                    runEntity.getRunIdPublic(), e.getMessage());
        }
    }

    // ========================================================================
    // STATE RECONSTRUCTION
    // ========================================================================

    /**
     * Reconstructs the complete state of a workflow run from database.
     * Used for displaying accurate state on reconnect and for resume functionality.
     *
     * <p>Always reads fresh from DB (StateSnapshot is single source of truth).
     */
    @Transactional(readOnly = true)
    public WorkflowRunState reconstructState(String runId) {
        logger.debug("[WorkflowResumeService] Reconstructing state for runId: {}", runId);

        WorkflowRunEntity runEntity = runRepository.findByRunIdPublic(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        Map<String, Object> planMap = runEntity.getPlan();
        if (planMap == null || planMap.isEmpty()) {
            throw new IllegalStateException("No plan found for run: " + runId);
        }
        WorkflowPlan plan = WorkflowPlan.fromMap(planMap, runEntity.getWorkflow().getId().toString(), runEntity.getTenantId());

        return stateReconstructor.reconstructState(runEntity, plan);
    }

    /**
     * REST-only reconstruction: skip output blob dereferences for mcp/trigger/core/table
     * aliases. Agent and interface aliases still load their output (panel/modal need
     * them at refresh).
     *
     * <p>DO NOT call from engine paths (resume, signal, rerun, cron, async, cache rebuild).
     * Engine paths need full output payloads for the WS UI.
     */
    @Transactional(readOnly = true)
    public WorkflowRunState reconstructStateForApi(String runId) {
        logger.debug("[WorkflowResumeService] Reconstructing API state for runId: {}", runId);

        WorkflowRunEntity runEntity = runRepository.findByRunIdPublic(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        Map<String, Object> planMap = runEntity.getPlan();
        if (planMap == null || planMap.isEmpty()) {
            throw new IllegalStateException("No plan found for run: " + runId);
        }
        WorkflowPlan plan = WorkflowPlan.fromMap(planMap, runEntity.getWorkflow().getId().toString(), runEntity.getTenantId());

        return stateReconstructor.reconstructStateForApi(runEntity, plan);
    }

    // ========================================================================
    // PAUSE/RESUME
    // ========================================================================

    /**
     * Pauses a running workflow. Waits for all in-flight steps to complete.
     */
    @Transactional
    public WorkflowRunState pauseWorkflow(String runId) {
        logger.info("Pausing workflow: {}", runId);

        // Get run entity to access tenantId
        WorkflowRunEntity runEntity = runRepository.findByRunIdPublic(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        // Reconstruct state (light mode - only need plan + counters, not step outputs)
        WorkflowRunState state = reconstructStateForApi(runId);
        if (state == null) {
            throw new IllegalArgumentException("Run state could not be reconstructed: " + runId);
        }

        // Store paused workflow state for resume
        WorkflowPlan plan = WorkflowPlan.fromMap(state.plan(), state.workflowId(), runEntity.getTenantId());
        cacheManager.storePausedWorkflow(runId, new PausedWorkflowState(
            runId,
            Instant.now(),
            plan
        ));

        // Update status in DB
        runRepository.findByRunIdPublic(runId).ifPresent(entity -> {
            entity.setStatus(RunStatus.PAUSED);
            entity.setUpdatedAt(Instant.now());
            runRepository.save(entity);
        });

        // Send streaming event
        WorkflowExecution execution = contextManager.rebuildExecutionContext(runId, state);
        streamingService.sendWorkflowStatusEvent(execution, RunStatus.PAUSED, "Workflow paused by user");

        // Trigger-suspend policy: pausing also stops the workflow's schedule rows
        // so the dispatcher doesn't keep picking them every minute. Applies to both
        // WORKFLOW and APPLICATION since 2026-05-13 (was APPLICATION-only under
        // v3.5 §L3 single-run policy; extended to fix the paused-zombie class).
        // Re-arm path is symmetric via reactivateWorkflow → reactivateScheduleTriggers.
        applyApplicationSingleRunPolicyOnTermination(runEntity, "pauseWorkflow");

        return reconstructStateForApi(runId);
    }

    /**
     * Resumes a paused workflow from where it left off.
     */
    @Transactional
    public WorkflowRunState resumeWorkflow(String runId) {
        logger.info("Resuming workflow: {}", runId);

        WorkflowRunState state = reconstructStateForApi(runId);

        if (!state.canResume() && state.status() != RunStatus.PAUSED) {
            throw new IllegalStateException("Workflow cannot be resumed. Current status: " + state.status());
        }

        runRepository.findByRunIdPublic(runId).ifPresent(entity -> {
            entity.setStatus(RunStatus.RUNNING);
            entity.setUpdatedAt(Instant.now());
            runRepository.save(entity);
        });

        WorkflowExecution execution = contextManager.rebuildExecutionContext(runId, state);

        streamingService.sendWorkflowStatusEvent(execution, RunStatus.RUNNING, "Workflow resuming");

        contextManager.executeReadySteps(execution, state.readySteps());

        WorkflowRunState newState = reconstructStateForApi(runId);
        // Third copy of the same completion test, inlined. Also a WRITE path:
        // completeWorkflow stamps workflow_runs.status. See RunCompletionPredicate.
        if (newState.readySteps().isEmpty()
            && RunCompletionPredicate.allPlanNodesSettled(execution.getPlan(),
                newState.completedStepIds(), newState.failedStepIds(), newState.skippedStepIds())) {
            contextManager.completeWorkflow(execution, newState);
        }

        return newState;
    }

    /**
     * Checks if a workflow is currently paused.
     */
    public boolean isPaused(String runId) {
        return cacheManager.isPaused(runId) ||
               runRepository.findByRunIdPublic(runId)
                   .map(e -> e.getStatus() == RunStatus.PAUSED)
                   .orElse(false);
    }

    // ========================================================================
    // CANCEL
    // ========================================================================

    /**
     * Hard-cancels a workflow run (terminal stop).
     *
     * <p>Accepts RUNNING, PAUSED, or WAITING_TRIGGER status. Sets the run to
     * CANCELLED (terminal) - no further triggers will fire.</p>
     *
     * <ol>
     *   <li>Acquire pessimistic lock to prevent race conditions</li>
     *   <li>Cancel active signals (timers, approvals)</li>
     *   <li>Set status to CANCELLED with endedAt = now</li>
     *   <li>Send streaming CANCELLED event</li>
     *   <li>Cleanup caches</li>
     * </ol>
     */
    @Transactional
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public void cancelWorkflow(String runId) {
        cancelWorkflow(runId, null);
    }

    /**
     * Same hard-cancel, plus caller-supplied run metadata written in the SAME locked
     * transaction as the status change.
     *
     * <p>Used by the agent-facing stop ({@code AgentRunStopService}) and by the user's
     * Stop/Cancel buttons to record WHY, and by whom, the run was stopped. It goes through
     * here rather than through a separate save because this method already holds the run
     * under a pessimistic lock with a fresh entity: merging a metadata map read outside
     * that lock, from another transaction, would clobber the entries written here.
     *
     * @param extraMetadata entries merged into the run metadata (null = none, and a null
     *                      VALUE removes that key)
     */
    @Transactional
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public void cancelWorkflow(String runId, Map<String, Object> extraMetadata) {
        logger.info("[cancelWorkflow] Hard-cancelling workflow: {}", runId);

        // Plan v4 §1.6 - advisory lock serializes against any concurrent
        // CAS writer on this run. Released at @Transactional commit/rollback.
        if (advisoryLockHelper != null) advisoryLockHelper.acquireForRun(runId);

        // 1. Acquire pessimistic lock
        WorkflowRunEntity lockedRun = runRepository.findByRunIdPublicForUpdate(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        RunStatus currentStatus = lockedRun.getStatus();
        if (currentStatus == RunStatus.CANCELLED) {
            logger.info("[cancelWorkflow] Run {} already CANCELLED, skipping", runId);
            return;
        }
        // AWAITING_SIGNAL is cancellable too: a run parked on a USER_APPROVAL / WAIT_TIMER /
        // WEBHOOK_WAIT signal is exactly the kind of run a user needs to hard-cancel (e.g. a
        // step-by-step run stopped at an approval). This method already tears the signal down
        // below (unifiedSignalService.cancelByRun), so the only thing that blocked it was this
        // over-strict guard. Terminal statuses (COMPLETED/FAILED) stay non-cancellable.
        if (currentStatus != RunStatus.RUNNING &&
            currentStatus != RunStatus.PAUSED &&
            currentStatus != RunStatus.WAITING_TRIGGER &&
            currentStatus != RunStatus.AWAITING_SIGNAL) {
            throw new IllegalStateException(
                "Cannot cancel workflow in status: " + currentStatus +
                ". Must be RUNNING, PAUSED, WAITING_TRIGGER, or AWAITING_SIGNAL.");
        }

        // 2. Set agent cancel signal in Redis FIRST - closes the race where a late
        //    async result arriving between "cancel signals" and "set cancel signal"
        //    would slip past RunCancellationGuard.isAgentCancelSignalSet (which
        //    returns false until this point) and drive successors on a cancelled run.
        //    Setting the signal first turns the guard into an immediate reject for any
        //    late result, regardless of whether cancelByRun has run yet.
        if (workflowRedisPublisher != null) {
            workflowRedisPublisher.setAgentCancelSignal(runId);
        }

        // 2b. Cancel active signals (now that the cancel-signal flag is set, late
        //     resolutions are gated even if a few sneak through here).
        if (unifiedSignalService != null) {
            unifiedSignalService.cancelByRun(runId);
        }

        // 2b-bis. Abort any in-flight browser-agent session for this run. Without
        //     this the websearch runner keeps driving Chromium to completion even
        //     though the run is cancelled - the "Stop button did nothing" symptom.
        //     Best-effort (never throws): a websearch hiccup must not block cancel.
        if (browserAgentRunAborter != null) {
            browserAgentRunAborter.abortAllForRun(runId);
        }

        // 2c. Remove pending async agent entries - prevents late-arriving results from
        //     driving successor traversal on the cancelled run. The results themselves
        //     are still persisted if they arrive (deliverUnderLock's cancellation guard
        //     allows persistence but blocks traversal), but removing entries here avoids
        //     unnecessary work and speeds up the cleanup.
        if (pendingAgentRegistry != null) {
            pendingAgentRegistry.removeByRunId(runId);
        }

        // 2d. Release epoch concurrency permits - prevents leaked permits from blocking
        //     future trigger fires if the run is later reactivated.
        if (epochConcurrencyLimiter != null) {
            epochConcurrencyLimiter.cleanup(runId);
        }

        // 3. Set terminal status CANCELLED
        Instant now = Instant.now();
        lockedRun.setStatus(RunStatus.CANCELLED);
        lockedRun.setEndedAt(now);
        lockedRun.setUpdatedAt(now);

        Map<String, Object> metadata = lockedRun.getMetadata() != null
            ? new HashMap<>(lockedRun.getMetadata()) : new HashMap<>();
        // Clear lastCycleResult so display shows CANCELLED (explicit user action).
        // Unlike cancelStaleRuns (system-level, preserves cycle result), the Stop
        // button is a deliberate user action that should always show "cancelled".
        metadata.remove("lastCycleResult");
        metadata.remove("lastCycleEpoch");
        metadata.remove("lastCycleAt");
        metadata.put("cancelledAt", now.toString());
        metadata.put("cancelledFromStatus", currentStatus.getValue());
        mergeExtraMetadata(metadata, extraMetadata);
        lockedRun.setMetadata(metadata);
        runRepository.save(lockedRun);

        // 3b. Close all active epochs in StateSnapshot so they don't linger as
        // open DAGs after cancellation (prevents stale epoch state on reactivate).
        try {
            StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
            if (snapshot != null && snapshot.getDags() != null) {
                for (String triggerId : snapshot.getDags().keySet()) {
                    stateSnapshotService.closeAllActiveEpochs(runId, triggerId);
                }
            }
        } catch (Exception e) {
            logger.warn("[cancelWorkflow] Failed to close active epochs for runId={}: {}", runId, e.getMessage());
        }

        // 3c. Publish WorkflowRunTerminatedEvent so downstream listeners (pin re-arm,
        // notification bell) fire on cancel - same as recordWorkflowCompletion does for
        // COMPLETED/FAILED. Without this, RunTerminationListener.rearm() never runs and
        // the workflow stays pinned to the cancelled version forever.
        if (eventPublisher != null && lockedRun.getWorkflow() != null) {
            try {
                eventPublisher.publishEvent(new WorkflowRunTerminatedEvent(
                        lockedRun.getId(),
                        lockedRun.getWorkflow().getId(),
                        RunStatus.CANCELLED,
                        lockedRun.getPlanVersion()));
            } catch (Exception e) {
                logger.warn("[cancelWorkflow] Failed to publish WorkflowRunTerminatedEvent for runId={}: {}",
                        runId, e.getMessage());
            }
        }

        // 4. Send streaming event (light mode - SSE only needs counters, not step outputs)
        try {
            WorkflowRunState state = reconstructStateForApi(runId);
            WorkflowExecution execution = contextManager.rebuildExecutionContext(runId, state);
            streamingService.sendWorkflowStatusEvent(
                execution, RunStatus.CANCELLED, "Workflow cancelled");
        } catch (Exception e) {
            logger.warn("[cancelWorkflow] Failed to send streaming event for runId={}: {}", runId, e.getMessage());
        }

        // 5. Cleanup caches. PRESERVE the STREAMING domain: a cancelled run stays
        // reactivatable on the SAME runId (see closeAllActiveEpochs above, "prevents
        // stale epoch state on reactivate"), so purging WsEventSequencer's shared seq
        // counter here would make the next fire re-seed low -> frontend strict-< drops
        // the events -> run page freezes (same class as the reusable-trigger / step-
        // rerun refire paths, which already exclude STREAMING).
        clearCachedStateForRerun(runId, Set.of(RunScopedCache.CacheDomain.STREAMING));

        // 6. Cancel platform-markup pricing pins so stragglers can't keep billing
        // after cancel. Fail-open - never blocks the cancel path.
        if (platformMarkupPinService != null) {
            try {
                platformMarkupPinService.cancelPinsForRun(runId);
            } catch (Exception e) {
                logger.warn("[cancelWorkflow] Failed to cancel platform-markup pins for runId={}: {}",
                        runId, e.getMessage());
            }
        }

        // 7. Trigger-suspend policy: stop the workflow's schedule rows so the
        // dispatcher doesn't keep picking them every minute (zombie schedules
        // with NO_PRODUCTION_RUN warn spam + stale next_execution_at). Applies
        // to both WORKFLOW and APPLICATION since 2026-05-13 (was APPLICATION-only
        // under v3.5 §L3 single-run policy; extended to fix the paused-zombie
        // class). Re-arm path is symmetric via reactivateWorkflow.
        applyApplicationSingleRunPolicyOnTermination(lockedRun, "cancelWorkflow");

        logger.info("[cancelWorkflow] Workflow hard-cancelled: runId={}, previousStatus={}", runId, currentStatus);
    }

    /**
     * Cancel/pause-time trigger-suspension policy. Reads the workflow type
     * from the run's eagerly-fetched workflow association - no extra repository
     * call required.
     *
     * <p>v3.5 §L3 originally limited this to APPLICATION (single-run contract).
     * Extended 2026-05-13 to WORKFLOW too - same drive: when a user cancels a
     * run via the board paused column, the workflow's schedules must stop
     * firing or they create a "zombie" (next_execution_at frozen + log spam
     * every minute via NO_PRODUCTION_RUN). Re-arm path is symmetric and
     * already wired in {@link #reactivateWorkflow} via
     * {@link #reactivateScheduleTriggers}.
     *
     * <p>Both types: drive bulk-suspend on the workflow's schedule rows via
     * {@link com.apimarketplace.trigger.client.TriggerClient#suspendSchedulesByWorkflow}.
     * The triggers stop firing immediately; user re-activates via the
     * reactivate or pin/unpin flow when ready.
     *
     * <p><strong>Defers the HTTP call to {@code afterCommit}</strong> when an
     * active transaction is detected. The caller ({@code cancelWorkflow},
     * {@code pauseWorkflow}) is {@code @Transactional} and holds row locks
     * on {@code workflow_runs}; issuing a synchronous HTTP call inside the
     * tx would hold the DB connection during the network round-trip. The
     * Phase-5 audit flagged this as P1 (a hung trigger-service would pile
     * up cancel requests holding row locks). Registering an
     * {@link TransactionSynchronization#afterCommit() afterCommit} callback
     * lets the lifecycle row-write commit and release its lock first; the
     * HTTP call fires after, with no DB connection held.
     *
     * <p>When called outside a transaction (test contexts, or future
     * callers that don't wrap in {@code @Transactional}), the HTTP call
     * fires immediately - preserves the contract for those paths.
     *
     * <p>Fail-open: any error (missing trigger client, HTTP failure, missing
     * workflow association) is logged at WARN. Single-run policy is
     * best-effort observability of the contract, not a correctness predicate
     * of run termination.
     *
     * @param run    the run being terminated (must have its {@code workflow}
     *               association populated; the JPA fetch type is LAZY so the
     *               caller must touch the association inside the transaction).
     * @param caller method name for log correlation.
     */
    private void applyApplicationSingleRunPolicyOnTermination(WorkflowRunEntity run, String caller) {
        if (run == null || triggerClient == null) return;
        com.apimarketplace.orchestrator.domain.WorkflowEntity wf;
        boolean suspendsTriggers;
        try {
            wf = run.getWorkflow();
            if (wf == null) return;
            // getWorkflowType() INITIALIZES the lazy WorkflowEntity proxy - the try/catch MUST cover
            // this, not just run.getWorkflow() (which returns the uninitialized proxy without touching
            // the DB). In some tx/session boundaries - notably the CE monolith - no Hibernate session
            // is bound here, so this threw LazyInitializationException, which previously escaped the
            // method and 500'd the entire cancel/pause (the run could not be cancelled at all). This
            // policy is best-effort (see javadoc): fail-open, never block run termination on it.
            suspendsTriggers = com.apimarketplace.orchestrator.services.workflow.WorkflowTypePolicy
                    .suspendsTriggersOnRunTerminationOrDefault(wf.getWorkflowType());
        } catch (Exception e) {
            logger.debug("[{}] Could not load workflow type for runId={} - skipping single-run policy: {}",
                    caller, run.getRunIdPublic(), e.getMessage());
            return;
        }
        if (!suspendsTriggers) {
            return; // regular workflow → triggers preserved across runs
        }
        // Snapshot the values we need outside the tx (entity refs are unsafe
        // post-commit when the persistence context closes).
        final java.util.UUID workflowId = wf.getId();
        final String runIdPublic = run.getRunIdPublic();

        Runnable invokeSuspend = () -> {
            try {
                // Source RUN_TERMINATION reflects the cascade-from-run-cancel/pause
                // context in the audit log. Distinct from Source.DELETION (which is
                // for the workflow row being deleted - see archiveSchedulesByWorkflow
                // cascade in WorkflowManagementService). Forensic queries on
                // {@code source='DELETION'} stay narrow to actual deletions; cancel
                // and pause cascades surface as {@code source='RUN_TERMINATION'}.
                int suspended = triggerClient.suspendSchedulesByWorkflow(
                        workflowId,
                        "USER_DISABLED",
                        "RUN_TERMINATION");
                logger.info("[{}] Trigger-suspend policy: suspended {} schedule trigger(s) " +
                                "for workflowId={} after run termination (runId={})",
                        caller, suspended, workflowId, runIdPublic);
                // The owner switched the workflow off: no "still failing" reminder about it. Only
                // once a schedule really stopped (the client answers 0 on a failed call), as the
                // schedule controllers only close on a confirmed stop.
                if (suspended > 0 && notificationDelivery != null) {
                    notificationDelivery.onWorkflowStopped(workflowId);
                }
            } catch (Exception e) {
                logger.warn("[{}] Failed to apply trigger-suspend policy for workflowId={} runId={}: {}",
                        caller, workflowId, runIdPublic, e.getMessage());
            }
        };

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            invokeSuspend.run();
                        }
                    });
        } else {
            // No active tx - caller didn't wrap in @Transactional, or test path.
            // Fire immediately to preserve the contract.
            invokeSuspend.run();
        }
    }

    /**
     * Reactivate a cancelled workflow run, returning it to WAITING_TRIGGER so
     * triggers can fire again. This reverses a cancel operation - the run becomes
     * non-terminal and ready to accept new trigger fires.
     *
     * <p>Only CANCELLED runs can be reactivated. The method:
     * <ol>
     *   <li>Closes any stale active epochs left over from a cancel-while-RUNNING</li>
     *   <li>Cancels any residual signals (defensive, mirrors stopWorkflow)</li>
     *   <li>Clears the Redis agent-cancel signal</li>
     *   <li>Returns the run to WAITING_TRIGGER with clean metadata</li>
     *   <li>Re-enables schedules that were disabled on cancel</li>
     *   <li>Sends an SSE event for real-time UI update</li>
     *   <li>Clears caches</li>
     * </ol>
     *
     * <p>Works identically for AUTO and STEP_BY_STEP execution modes - the mode
     * is preserved on the run entity and the next trigger fire respects it.
     */
    @Transactional
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public void reactivateWorkflow(String runId) {
        logger.info("[reactivateWorkflow] Reactivating workflow run: {}", runId);

        // Plan v4 §1.6
        if (advisoryLockHelper != null) advisoryLockHelper.acquireForRun(runId);

        WorkflowRunEntity lockedRun = runRepository.findByRunIdPublicForUpdate(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        // Accept every terminal status. Originally limited to CANCELLED (user-initiated stop),
        // but commit a04f13449 made every dispatcher reject terminal runs at fire time -
        // including FAILED runs left behind by a JVM crash mid-cycle (prod 2026-05-07 12:40
        // UTC, run_<id>). Without extending reactivation to all terminals,
        // a crashed run becomes zombie irrecoverable: triggers refuse to fire it, and the
        // user has no UI path back to WAITING_TRIGGER. The stale-epoch close + signal
        // cleanup steps below already handle the hardest case (parallel epochs left active),
        // so the same flow works for FAILED/COMPLETED/PARTIAL_SUCCESS/TIMEOUT/SKIPPED.
        RunStatus currentStatus = lockedRun.getStatus();
        if (currentStatus == null || !currentStatus.isTerminal()) {
            throw new IllegalStateException(
                "Cannot reactivate workflow in status: " + currentStatus +
                ". Must be terminal (COMPLETED, FAILED, PARTIAL_SUCCESS, CANCELLED, TIMEOUT, SKIPPED).");
        }

        // 1. Close any stale active epochs in the state snapshot.
        //    cancelWorkflow now closes epochs, but a crash/timeout between save and
        //    epoch-close could still leave orphaned active epochs on reactivate.
        StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
        if (snapshot != null) {
            for (Map.Entry<String, com.apimarketplace.orchestrator.domain.execution.DagState> entry
                    : snapshot.getDags().entrySet()) {
                String triggerId = entry.getKey();
                Set<Integer> closedEpochs = stateSnapshotService.closeAllActiveEpochs(runId, triggerId);
                if (!closedEpochs.isEmpty()) {
                    logger.info("[reactivateWorkflow] Closed stale epochs {} for trigger {} on run {}",
                            closedEpochs, triggerId, runId);
                }
            }
        }

        // 2. Defensive signal cleanup - cancelWorkflow already calls cancelByRun(),
        //    but we mirror stopWorkflow's belt-and-suspenders approach to ensure no
        //    stale signals survive (e.g. if a signal was registered between cancel
        //    and reactivate by a concurrent async-completion path).
        if (unifiedSignalService != null) {
            unifiedSignalService.cancelByRun(runId);
        }

        // 3. Clear Redis cancel signal - prevents the next trigger execution from
        //    being immediately killed if reactivated within the 2-hour TTL.
        //    ReusableTriggerService also clears this on each trigger fire (line 294),
        //    but clearing here avoids a race if the trigger fires between reactivate
        //    and the clear in executeTriggerInternal.
        if (workflowRedisPublisher != null) {
            workflowRedisPublisher.clearAgentCancelSignal(runId);
        }

        // 4. Return to WAITING_TRIGGER, clear terminal fields
        Instant now = Instant.now();
        lockedRun.setStatus(RunStatus.WAITING_TRIGGER);
        lockedRun.setEndedAt(null);
        lockedRun.setUpdatedAt(now);

        // 5. Clean up cancel metadata, preserve the rest
        Map<String, Object> metadata = lockedRun.getMetadata() != null
            ? new HashMap<>(lockedRun.getMetadata()) : new HashMap<>();
        metadata.remove("cancelledAt");
        metadata.remove("cancelledFromStatus");
        // The stop cause belongs to the stop we are undoing: a reactivated run is alive
        // again, so reports of it must not keep saying why it was once stopped.
        metadata.remove(AgentRunStopService.META_STOP_REASON);
        metadata.remove(AgentRunStopService.META_STOPPED_BY);
        metadata.remove(AgentRunStopService.META_STOPPED_AT);
        metadata.put("reactivatedAt", now.toString());
        lockedRun.setMetadata(metadata);
        runRepository.save(lockedRun);

        // 6. Re-enable any schedules that were disabled when the run was cancelled.
        //    See {@link #reactivateScheduleTriggers} for the full self-healing
        //    contract; the call is wrapped in a defensive try/catch so a trigger
        //    re-arm failure never blocks the rest of the reactivate flow
        //    (status flip, streaming event, cache cleanup).
        try {
            reactivateScheduleTriggers(lockedRun.getWorkflow());
        } catch (Exception e) {
            logger.warn("[reactivateWorkflow] Failed to re-enable schedules for runId={}: {}", runId, e.getMessage());
        }

        // 7. Send streaming event (light mode - SSE only needs counters, not step outputs)
        try {
            WorkflowRunState state = reconstructStateForApi(runId);
            WorkflowExecution execution = contextManager.rebuildExecutionContext(runId, state);
            streamingService.sendWorkflowStatusEvent(
                execution, RunStatus.WAITING_TRIGGER, "Workflow reactivated - waiting for next trigger");
        } catch (Exception e) {
            logger.warn("[reactivateWorkflow] Failed to send streaming event for runId={}: {}", runId, e.getMessage());
        }

        // 8. Cleanup caches. PRESERVE the STREAMING domain: reactivation returns the
        // SAME runId to a refireable state, so the shared WS seq counter must survive
        // (else the next fire re-seeds low -> frontend strict-< drop -> UI freeze).
        clearCachedStateForRerun(runId, Set.of(RunScopedCache.CacheDomain.STREAMING));

        logger.info("[reactivateWorkflow] Workflow reactivated: runId={}", runId);
    }

    /**
     * Self-healing schedule re-arm for a workflow being reactivated.
     *
     * <p>Decision tree:
     * <ul>
     *   <li><b>Unpinned workflow:</b> arm whatever rows exist and stop -
     *       there is no source-of-truth plan to reconcile against.</li>
     *   <li><b>Pinned workflow + sync service absent:</b> deployment
     *       misconfiguration, surface as ERROR. Existing rows stay armed.</li>
     *   <li><b>Pinned workflow + sync service present:</b> always reconcile
     *       against the pinned plan via {@code syncAllTriggersFromPinnedVersion},
     *       <i>regardless</i> of how many rows the initial arm touched. The
     *       earlier "only sync if armed == 0" predicate missed the case where
     *       <i>some</i> schedule rows survived but others were reaped - the
     *       reconcile would have been skipped and the missing row(s) stayed
     *       silently absent. The per-row path
     *       ({@code createOrUpdateScheduleInternal}) honours the V60
     *       {@code uq_schedule_workflow_trigger} unique constraint, so the
     *       reconcile is idempotent and cheap when rows are already correct.</li>
     * </ul>
     *
     * <p><b>Post-reconcile verification:</b> after the sync, re-arm and assert
     * that at least one row exists. If the count is still 0, the sync silently
     * swallowed a sub-failure (corrupt pinned-plan blob, quota exceeded,
     * trigger-service flakiness) - log ERROR so this surfaces in monitoring
     * instead of returning a falsely-cheerful "Recreated" INFO log.
     *
     * <p><b>Side effects of the reconcile beyond schedules:</b>
     * {@code syncAllTriggersFromPinnedVersion} also runs the webhook,
     * chat-endpoint, form-endpoint, and datasource-subscription sync paths.
     * Webhook tokens, chat endpoints, and form endpoints carry user-defined
     * fields that the pinned plan does not store, so those sync paths are
     * UPDATE-style (re-arm config) - they do <i>not</i> recreate missing rows.
     * If a reaper deleted those rows, the user must recreate them from
     * Settings. Datasource subscriptions follow the same recreation pattern
     * as schedules and ARE restored by this fallback.
     *
     * <p>Package-private to allow direct unit-testing without standing up the
     * full reactivateWorkflow run/state harness.
     */
    void reactivateScheduleTriggers(WorkflowEntity workflow) {
        if (triggerClient == null) return;
        UUID workflowId = workflow.getId();
        Integer pinnedVersion = workflow.getPinnedVersion();

        if (pinnedVersion == null) {
            // Contract: a schedule row is ACTIVE iff workflow.pinned_version IS NOT NULL.
            // Reactivating an unpinned workflow MUST NOT re-arm SUSPENDED rows - the
            // user has either never toggled "live" or explicitly toggled off. Without
            // this gate, a workflow that was unpinned during a cancel-then-reactivate
            // cycle would have its suspended schedule rows flipped back to ACTIVE,
            // re-introducing the very auto-fire-on-unpinned bug we just closed.
            logger.info("[reactivateWorkflow] Workflow {} not pinned - skipping schedule rearm " +
                    "(triggers active iff pinned)", workflowId);
            return;
        }

        // Field naming: rowsBefore/rowsAfter (NOT "armed") because the trigger-service
        // endpoint returns the count of schedule rows for the workflow, NOT the count
        // of state transitions performed. The audit chain flagged the prior "armed"
        // wording as a count-semantics lie - a row already at state=ACTIVE is
        // counted but no transition fires (idempotency guard in armSchedule).
        int rowsBefore = triggerClient.enableSchedulesByWorkflow(workflowId);

        if (pinAwareTriggerSyncService == null) {
            logger.error("[reactivateWorkflow] PinAwareTriggerSyncService bean absent - cannot reconcile " +
                    "schedules from pinned plan for workflow {} ({} pre-existing rows armed)",
                    workflowId, rowsBefore);
            return;
        }

        // Always reconcile from the pinned plan - this catches both "0 rows" and
        // "M < N triggers" silent gaps. Idempotent when rows already align with
        // the plan thanks to the unique constraint on (workflow_id, trigger_id).
        pinAwareTriggerSyncService.syncAllTriggersFromPinnedVersion(workflow);

        // Verification: re-fetch row count post-reconcile + cross-check against the
        // pinned plan's declared schedule-trigger count. The cross-check is the only
        // way to detect SILENT PARTIAL FAILURE - sync recreated some rows but not
        // all (transient 503 from trigger-service swallowed by TriggerClient catches).
        // Without it, "{X} pre-existing → {Y} post-reconcile" would always log as
        // a happy WARN even when Y < expected.
        //
        // Five branches:
        //   1. expected unknown (plan unloadable / not pinned): fall back to row-delta heuristic
        //   2. expected == 0 && actual == 0: INFO (no-schedule plan - webhook/chat/form-only)
        //   3. expected > 0  && actual == 0: ERROR (total recreate failure)
        //   4. expected > 0  && actual <  expected: ERROR (silent partial failure)
        //   5. actual == expected: INFO when rowsBefore matched (no-op), WARN when rowsBefore < actual (rows recreated)
        //
        // The expected count is read fresh from the pinned plan (one extra query) -
        // cheap and worth the explicit signal vs. the false-positive ERROR the
        // prior heuristic produced on legitimate webhook-only workflows.
        int rowsAfter = triggerClient.enableSchedulesByWorkflow(workflowId);
        java.util.OptionalInt expectedOpt = scheduleSyncService != null
                ? scheduleSyncService.countScheduleTriggersInPinnedPlan(workflow)
                : java.util.OptionalInt.empty();

        if (expectedOpt.isEmpty()) {
            // Branch 1 - fall back to row-delta heuristic (couldn't determine expected).
            logReconcileWithoutExpected(workflowId, pinnedVersion, rowsBefore, rowsAfter);
            return;
        }

        int expected = expectedOpt.getAsInt();
        if (expected == 0 && rowsAfter == 0) {
            logger.info("[reactivateWorkflow] Pinned v{} for workflow {} declares no schedule triggers - " +
                    "no schedule rows expected (webhook/chat/form-only workflow). No action needed.",
                    pinnedVersion, workflowId);
            return;
        }
        if (expected > 0 && rowsAfter == 0) {
            logger.error("[reactivateWorkflow] Pinned v{} for workflow {} expected {} schedule row(s) but " +
                    "post-reconcile produced 0 dispatchable. Sync did not produce any armed row - check " +
                    "trigger-service health, quota, and the [ScheduleSync]/[TriggerSync] WARN/ERROR logs in " +
                    "the same window. If all triggers were archived (e.g. MAX_EXEC_REACHED), this is expected " +
                    "and the workflow correctly will not fire - investigate via the schedules admin view.",
                    pinnedVersion, workflowId, expected);
            return;
        }
        if (rowsAfter < expected) {
            // Round-5 audit (F1): the gap between expected and actual is NOT
            // automatically a bug. ARCHIVED rows count toward `expected` (plan
            // declares them) but not toward `actual` (P0b dispatchable filter
            // excludes them). When a user pins a plan with N schedule triggers
            // and one of them later archives via MAX_EXEC_REACHED, every
            // reactivate hits this branch - yet the state is stable and
            // intentional. Downgrading to WARN with a less alarmist message
            // avoids permanent ERROR-spam in monitoring; the dispatchable rows
            // still fire correctly. To distinguish a genuine sync failure
            // from this expected gap, operators should cross-reference the
            // schedule rows' state in the schedules admin view.
            logger.warn("[reactivateWorkflow] Pinned v{} for workflow {}: {} of {} expected schedule row(s) " +
                    "are dispatchable post-reconcile (rowsBefore={}, missing={}). Gap can be intentional " +
                    "(archived for MAX_EXEC_REACHED, USER_DISABLED, etc.) or a sync partial-failure - " +
                    "check the schedules admin view if a recent fire was expected.",
                    pinnedVersion, workflowId, rowsAfter, expected, rowsBefore, expected - rowsAfter);
            return;
        }
        // rowsAfter == expected (or rowsAfter > expected - rare, rows pre-existing for
        // triggers no longer in plan; cleanupOrphanSchedules should have culled them).
        if (rowsBefore == rowsAfter) {
            logger.info("[reactivateWorkflow] Pinned v{} for workflow {}: {} schedule row(s) already aligned " +
                    "with pinned plan, reconcile was a no-op.",
                    pinnedVersion, workflowId, rowsAfter);
        } else {
            logger.warn("[reactivateWorkflow] Pinned v{} for workflow {}: schedules reconciled, " +
                    "{} row(s) pre-existing → {} row(s) post-reconcile (expected {}). " +
                    "Missing rows recreated from the pinned plan.",
                    pinnedVersion, workflowId, rowsBefore, rowsAfter, expected);
        }
    }

    /**
     * Fallback log path when the pinned plan's expected schedule count cannot be
     * determined (sync service absent, plan unloadable). Less precise than the
     * expected-vs-actual comparison - use the count delta as a weaker signal.
     */
    private void logReconcileWithoutExpected(UUID workflowId, Integer pinnedVersion,
                                             int rowsBefore, int rowsAfter) {
        if (rowsAfter == 0) {
            if (rowsBefore > 0) {
                logger.error("[reactivateWorkflow] Pinned v{} for workflow {}: reconcile deleted all schedule rows " +
                        "({} → 0). Could not load pinned plan to confirm expected count - investigate trigger-service " +
                        "health and the pinned plan version row.",
                        pinnedVersion, workflowId, rowsBefore);
            } else {
                logger.warn("[reactivateWorkflow] Pinned v{} for workflow {}: 0 schedule rows present, " +
                        "could not determine expected count from pinned plan - could be a webhook-only workflow " +
                        "or a corrupt pinned plan. Investigate if a schedule was expected.",
                        pinnedVersion, workflowId);
            }
            return;
        }
        if (rowsBefore == rowsAfter) {
            logger.info("[reactivateWorkflow] Pinned v{} for workflow {}: {} schedule row(s) already aligned, " +
                    "reconcile was a no-op (expected count unavailable).",
                    pinnedVersion, workflowId, rowsAfter);
        } else {
            logger.warn("[reactivateWorkflow] Pinned v{} for workflow {}: {} → {} schedule row(s) post-reconcile " +
                    "(expected count unavailable - missing rows recreated, but cannot verify completeness).",
                    pinnedVersion, workflowId, rowsBefore, rowsAfter);
        }
    }

    /**
     * Graceful stop: close all active epochs, cancel their blocking signals,
     * and return the run to WAITING_TRIGGER so reusable triggers keep working.
     * Unlike cancel, this is non-terminal - the run stays alive for future fires.
     *
     * <p>Idempotent for terminal runs: if the run is already in a terminal status
     * (e.g. force-FAILED by the orchestration recovery watchdog), this performs
     * best-effort cleanup of pending signals/agents/epoch permits without changing
     * the status or broadcasting an SSE update. This avoids the silent-failure UX
     * where the user clicks Stop on a run the watchdog already killed and sees
     * nothing happen.</p>
     */
    @Transactional
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public void stopWorkflow(String runId) {
        stopWorkflow(runId, null);
    }

    /**
     * Merge caller-supplied entries into a run's metadata, where a {@code null} VALUE means
     * "remove this key".
     *
     * <p>Removal matters for the stop-cause keys: they describe ONE stop and are always
     * written as a set, so a later stop with no reason must clear the previous reason
     * instead of inheriting it and attributing it to the new stopper.
     */
    private static void mergeExtraMetadata(Map<String, Object> metadata, Map<String, Object> extra) {
        if (extra == null) {
            return;
        }
        extra.forEach((key, value) -> {
            if (value == null) {
                metadata.remove(key);
            } else {
                metadata.put(key, value);
            }
        });
    }

    /**
     * Same graceful stop, plus caller-supplied run metadata written in the SAME locked
     * transaction as the status change. See {@link #cancelWorkflow(String, Map)} for why
     * the metadata is merged here instead of being saved separately.
     *
     * <p>The metadata is only applied on the happy path: a run that is already terminal
     * takes the idempotent cleanup-only branch, which by design changes nothing on the row.
     *
     * @param extraMetadata entries merged into the run metadata (null = none, and a null
     *                      VALUE removes that key)
     */
    @Transactional
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public void stopWorkflow(String runId, Map<String, Object> extraMetadata) {
        logger.info("[stopWorkflow] Stopping workflow run: {}", runId);
        // Plan v4 §1.6
        if (advisoryLockHelper != null) advisoryLockHelper.acquireForRun(runId);

        WorkflowRunEntity lockedRun = runRepository.findByRunIdPublicForUpdate(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        RunStatus currentStatus = lockedRun.getStatus();

        // Terminal status (FAILED/COMPLETED/CANCELLED/PARTIAL_SUCCESS/TIMEOUT/SKIPPED):
        // perform best-effort cleanup, but do NOT change status or broadcast SSE.
        // The run is already terminal - telling the UI it just transitioned would lie.
        if (currentStatus.isTerminal()) {
            logger.warn("[stopWorkflow] Run already terminal (status={}) - performing best-effort cleanup only: runId={}",
                    currentStatus, runId);
            performStopCleanup(runId);
            return;
        }

        // Non-terminal but not stoppable (PENDING, WAITING_TRIGGER, AWAITING_SIGNAL):
        // these states genuinely shouldn't be stopped - there's no active execution to halt.
        if (currentStatus != RunStatus.RUNNING && currentStatus != RunStatus.PAUSED) {
            throw new IllegalStateException(
                "Cannot stop workflow in status: " + currentStatus +
                ". Must be RUNNING or PAUSED.");
        }

        // Happy path: RUNNING or PAUSED → cleanup + WAITING_TRIGGER
        // 1. Close all active epochs in the StateSnapshot (snapshot-only mutation).
        //
        //    We deliberately do NOT cancel the per-epoch blocking signals here.
        //    performStopCleanup() below cancels ALL signals run-wide via
        //    UnifiedSignalService.cancelByRun(), which since ea58a6e8a (2026-05-11)
        //    runs in its OWN transaction (Propagation.REQUIRES_NEW). Cancelling the
        //    same signal rows inside THIS outer @Transactional first leaves them
        //    row-locked-uncommitted; the REQUIRES_NEW cancelByRun then blocks on
        //    those locks until idle_in_transaction_session_timeout (prod: 5 min),
        //    because PostgreSQL sees no deadlock cycle (the outer tx is suspended
        //    waiting on the app thread, not lock-waiting). That self-deadlock is
        //    what made "Stop" hang for ~5 min on any run blocked at a WAIT_TIMER /
        //    INTERFACE_SIGNAL / USER_APPROVAL / WEBHOOK_WAIT signal (prod run
        //    run_<id>, 2026-06-05). cancelByRun is a strict
        //    superset of the per-epoch blocking cancel (it also publishes the
        //    USER_APPROVAL SignalsCancelledEvent), so nothing is lost. This mirrors
        //    the already-correct cancelWorkflow() ordering (cancelByRun BEFORE the
        //    outer tx touches any signal row).
        StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
        if (snapshot != null) {
            for (Map.Entry<String, com.apimarketplace.orchestrator.domain.execution.DagState> entry
                    : snapshot.getDags().entrySet()) {
                String triggerId = entry.getKey();
                Set<Integer> closedEpochs = stateSnapshotService.closeAllActiveEpochs(runId, triggerId);
                if (!closedEpochs.isEmpty()) {
                    logger.info("[stopWorkflow] Closed epochs {} for trigger {} on run {}",
                            closedEpochs, triggerId, runId);
                }
            }
        }

        // 2. Common cleanup steps - cancels ALL signals run-wide (REQUIRES_NEW),
        //    sets the Redis cancel flag, removes pending agents, releases epoch permits.
        performStopCleanup(runId);

        // 3. Return to WAITING_TRIGGER
        Instant now = Instant.now();
        lockedRun.setStatus(RunStatus.WAITING_TRIGGER);
        lockedRun.setUpdatedAt(now);
        if (extraMetadata != null) {
            Map<String, Object> metadata = lockedRun.getMetadata() != null
                ? new HashMap<>(lockedRun.getMetadata()) : new HashMap<>();
            mergeExtraMetadata(metadata, extraMetadata);
            lockedRun.setMetadata(metadata);
        }
        runRepository.save(lockedRun);

        // 4. Send streaming event (light mode - SSE only needs counters, not step outputs)
        try {
            WorkflowRunState state = reconstructStateForApi(runId);
            WorkflowExecution execution = contextManager.rebuildExecutionContext(runId, state);
            streamingService.sendWorkflowStatusEvent(
                execution, RunStatus.WAITING_TRIGGER, "Workflow stopped - waiting for next trigger");
        } catch (Exception e) {
            logger.warn("[stopWorkflow] Failed to send streaming event for runId={}: {}", runId, e.getMessage());
        }

        // 5. Cleanup caches. PRESERVE the STREAMING domain: stop returns the run to
        // WAITING_TRIGGER on the SAME runId (refireable), so the shared WS seq counter
        // must survive (else the next fire re-seeds low -> frontend strict-< drop -> UI freeze).
        clearCachedStateForRerun(runId, Set.of(RunScopedCache.CacheDomain.STREAMING));

        logger.info("[stopWorkflow] Workflow stopped and returned to WAITING_TRIGGER: runId={}, previousStatus={}",
                runId, currentStatus);
    }

    /**
     * Best-effort cleanup steps shared between the happy-path stop and the
     * idempotent stop-on-already-terminal path. Each step is a no-op if its
     * collaborator is unavailable, and any failure is isolated so a single
     * unhealthy collaborator (e.g. Redis down) cannot block the rest of the
     * cleanup or roll back the surrounding {@code @Transactional} status update.
     * Mirrors the per-step try/catch policy in
     * {@code OrchestrationRecoveryService.cleanupAfterForceFail}.
     */
    private void performStopCleanup(String runId) {
        // Set agent cancel signal in Redis FIRST - closes the race where a late
        // async result arriving between cancelByRun and setAgentCancelSignal would
        // slip past RunCancellationGuard.isAgentCancelSignalSet (false until set)
        // and drive successors on a stopped run. Setting the signal first turns the
        // guard into an immediate reject for any late result.
        if (workflowRedisPublisher != null) {
            try {
                workflowRedisPublisher.setAgentCancelSignal(runId);
            } catch (Exception e) {
                logger.warn("[stopWorkflow] Failed to set agent cancel signal for runId={}: {}",
                        runId, e.getMessage());
            }
        }

        // Cancel any remaining signals (non-blocking ones too for clean state).
        // Now that the cancel-signal flag is set, late resolutions are gated even
        // if a few sneak through between this call and removeByRunId.
        if (unifiedSignalService != null) {
            try {
                unifiedSignalService.cancelByRun(runId);
            } catch (Exception e) {
                logger.warn("[stopWorkflow] Failed to cancel signals for runId={}: {}", runId, e.getMessage());
            }
        }

        // Abort any in-flight browser-agent session for this run - otherwise the
        // websearch runner keeps driving Chromium after Stop (the node is
        // BLPOP-blocked on the result and never sees the cancel). Best-effort.
        if (browserAgentRunAborter != null) {
            try {
                browserAgentRunAborter.abortAllForRun(runId);
            } catch (Exception e) {
                logger.warn("[stopWorkflow] Failed to abort browser sessions for runId={}: {}",
                        runId, e.getMessage());
            }
        }

        // Remove pending async agent entries - prevents late-arriving results from
        // driving successor traversal on the stopped run.
        if (pendingAgentRegistry != null) {
            try {
                pendingAgentRegistry.removeByRunId(runId);
            } catch (Exception e) {
                logger.warn("[stopWorkflow] Failed to remove pending agent entries for runId={}: {}",
                        runId, e.getMessage());
            }
        }

        // Release epoch concurrency permits - the epochs are being closed,
        // permits should be freed so the next trigger fire is not blocked.
        if (epochConcurrencyLimiter != null) {
            try {
                epochConcurrencyLimiter.cleanup(runId);
            } catch (Exception e) {
                logger.warn("[stopWorkflow] Failed to release epoch concurrency permits for runId={}: {}",
                        runId, e.getMessage());
            }
        }
    }

    // ========================================================================
    // STEP EXECUTION - Delegated to StepByStepExecutor
    // ========================================================================

    /**
     * Executes a single step (used when paused, not step-by-step mode).
     */
    @Transactional
    public StepExecutionResult executeSingleStep(String runId, String stepId) {
        return stepByStepExecutor.executeSingleStep(runId, stepId,
            () -> reconstructStateForApi(runId),
            () -> refreshPlanFromWorkflowDefinition(runId));
    }

    /**
     * Gets the list of steps that are ready to execute.
     */
    @Transactional(readOnly = true)
    public Set<String> getReadySteps(String runId) {
        return stepByStepExecutor.getReadySteps(runId, () -> reconstructStateForApi(runId));
    }

    // ========================================================================
    // STEP-BY-STEP MODE - Delegated to StepByStepExecutor
    // ========================================================================

    /**
     * Starts a workflow in step-by-step mode.
     */
    @Transactional
    public WorkflowRunState startInStepByStepMode(String runId) {
        return stepByStepExecutor.startInStepByStepMode(runId, () -> reconstructStateForApi(runId));
    }

    /**
     * Sets the execution mode for a workflow run.
     */
    @Transactional
    public void setExecutionMode(String runId, ExecutionMode mode) {
        stepByStepExecutor.setExecutionMode(runId, mode);
    }

    /**
     * Gets the current execution mode for a workflow run.
     */
    @Transactional(readOnly = true)
    public ExecutionMode getExecutionMode(String runId) {
        return stepByStepExecutor.getExecutionMode(runId);
    }

    /**
     * Executes a single step in step-by-step mode.
     */
    @Transactional
    public StepExecutionResult executeSingleStepInStepByStepMode(String runId, String stepId) {
        return stepByStepExecutor.executeSingleStepInStepByStepMode(runId, stepId,
            () -> reconstructStateForApi(runId),
            () -> refreshPlanFromWorkflowDefinition(runId));
    }

    /**
     * Executes a single step in step-by-step mode with optional input data.
     */
    @Transactional
    public StepExecutionResult executeSingleStepInStepByStepMode(String runId, String stepId, Map<String, Object> inputData) {
        return stepByStepExecutor.executeSingleStepInStepByStepMode(runId, stepId, inputData,
            () -> reconstructStateForApi(runId),
            () -> refreshPlanFromWorkflowDefinition(runId));
    }

    /**
     * Cleans up step-by-step mode state for a run.
     */
    public void cleanupStepByStepState(String runId) {
        stepByStepExecutor.cleanupStepByStepState(runId);
    }

}

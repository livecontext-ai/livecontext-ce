package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.events.WorkflowEpochFailedEvent;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.resume.StepByStepExecutor;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RedisUnavailableException;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.services.transaction.TransactionalHelper;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueueRequestContext;
import com.apimarketplace.orchestrator.validation.DAGIndependenceValidator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for executing reusable triggers (webhook, manual, chat).
 *
 * Reusable triggers are triggers that can fire multiple times on the same workflow run.
 * Each execution is tracked as an "epoch" and accumulates statistics.
 *
 * This service provides a unified entry point for all reusable trigger types,
 * extracted from WorkflowResumeService for better separation of concerns (SOLID - S).
 *
 * Flow:
 * 1. Receive trigger payload (from webhook HTTP call, manual button click, or chat message)
 * 2. Update run status to RUNNING
 * 3. Execute the trigger step
 * 4. Either pause (step-by-step mode) or continue execution (auto mode)
 * 5. After completion, increment epoch and reset to WAITING_TRIGGER
 *
 * @see TriggerEpochManager
 * @see TriggerType
 * @see TriggerExecutionResult
 */
@Service
public class ReusableTriggerService {

    private static final Logger logger = LoggerFactory.getLogger(ReusableTriggerService.class);

    /**
     * Internal payload key written by TriggerController when the inbound HTTP
     * request body contained a `plan` map (i.e. the user clicked a manual
     * trigger from the editor / inspector with their canvas state). The
     * unpinned-branch override below uses this marker to decide whether to
     * preserve run.plan (just written by updateRunPlan) or fall back to
     * workflow.getPlan() for passive fires (webhook, schedule, sub-workflow).
     *
     * The marker is read-and-stripped at the very top of
     * executeTriggerInternal so it never reaches setWebhookTriggerPayload,
     * service logs, sub-workflow inheritance via context.triggerData(), or
     * any persisted record. External clients cannot inject it because
     * TriggerController sanitizes inbound payloads before applying its own
     * marker.
     */
    public static final String PLAN_FROM_PAYLOAD_MARKER = "__planFromPayload";

    @Value("${orchestrator.execution.parallel-ready.enabled:true}")
    private boolean parallelReadyExecutionEnabled;

    /**
     * Defense-in-depth: any caller that hands a payload to {@link #executeTrigger}
     * with content sourced from an untrusted boundary (HTTP webhook body, form
     * submission, chat message, public-app data, sub-workflow input data, etc.)
     * MUST run its payload through this helper first. Otherwise a client could
     * inject {@code __planFromPayload=true} (no actual {@code plan} key) and
     * trick {@code executeTriggerInternal} into skipping the workflow.plan
     * refresh.
     *
     * <p>{@link TriggerController} is the ONLY legitimate setter of the marker -
     * it does so AFTER a successful {@link WorkflowResumeService#updateRunPlan}.
     *
     * @return a new map without the marker key, or the original map unchanged
     *         when the marker is absent (no-allocation fast path).
     */
    public static Map<String, Object> sanitizePlanMarker(Map<String, Object> payload) {
        if (payload == null || !payload.containsKey(PLAN_FROM_PAYLOAD_MARKER)) {
            return payload;
        }
        Map<String, Object> sanitized = new HashMap<>(payload);
        sanitized.remove(PLAN_FROM_PAYLOAD_MARKER);
        return sanitized;
    }

    public static String deterministicScheduleRequestId(UUID scheduleId, Instant scheduledAt) {
        if (scheduleId == null) {
            return null;
        }
        String slot = scheduledAt != null ? scheduledAt.toString() : "manual";
        return "schedule:" + scheduleId + ":" + slot;
    }


    private final WorkflowRunRepository runRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowPlanVersionRepository planVersionRepository;
    private final TriggerEpochManager epochManager;
    private final WorkflowStreamingService streamingService;
    private final WorkflowExecutionService executionService;
    private final com.apimarketplace.orchestrator.services.TriggerResolverService triggerResolverService;
    private final StateSnapshotService stateSnapshotService;
    private final EpochConcurrencyLimiter epochConcurrencyLimiter;
    private final ExecutionQueue executionQueueService;
    private final CreditConsumptionClient creditClient;
    private final CreditBudgetService creditBudgetService;

    /**
     * Defense-in-depth pin check. Lazy because ReusableTriggerService is itself
     * widely depended on; the resolver is injected here purely for the chokepoint
     * in {@code executeTriggerInternal} and we don't want to widen any circular dep.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionRunResolver productionRunResolver;

    @Autowired(required = false)
    private V2StepByStepService v2StepByStepService;

    @Autowired(required = false)
    private V2StepByStepScheduler v2StepByStepScheduler;

    @Autowired(required = false)
    private UnifiedSignalService unifiedSignalService;

    @Autowired(required = false)
    private SnapshotService snapshotService;

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher workflowRedisPublisher;

    @Autowired(required = false)
    private DAGIndependenceValidator dagIndependenceValidator = new DAGIndependenceValidator();

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager splitContextManager;

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager executionCacheManager;

    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.merge.ItemMergeCollector itemMergeCollector;

    /**
     * Async agent registry - queried by {@link #hasActiveSignalsForTrigger} so that
     * {@code resetForNextCycle} defers epoch closure while {@code asyncRunning} agents are
     * still in flight. This is the async-path equivalent of the {@code SignalWait.blocking=true}
     * gate used by the signal subsystem: without it, the engine yields, the cycle reset
     * closes the epoch, and the successor traversal on result arrival finds an empty
     * ready-node set - {@code classify} after a guardrail split never executes.
     *
     * <p>Optional because the in-memory scaling mode also wires it, but the service must
     * still start cleanly if the bean is absent during unit tests.</p>
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry pendingAgentRegistry;

    /**
     * Plan v4 §1.6 - advisory-lock helper. Optional; null → row-lock-only
     * fallback (pre-§1.6 semantics).
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHelper advisoryLockHelper;

    // Lazy injection to avoid circular dependency (WorkflowTriggerDispatchService → ReusableTriggerService → WorkflowTriggerDispatchService)
    @Autowired
    @Lazy
    private WorkflowTriggerDispatchService workflowTriggerDispatchService;

    // Lazy injection to avoid circular dependency (ErrorTriggerDispatchService → ReusableTriggerService → ErrorTriggerDispatchService)
    @Autowired(required = false)
    @Lazy
    private ErrorTriggerDispatchService errorTriggerDispatchService;

    // Lazy injection to avoid circular dependency
    @Autowired
    @Lazy
    private WorkflowResumeService resumeService;

    // Self-injection for @Transactional on internal calls (Spring proxy bypass workaround)
    @Autowired
    @Lazy
    private ReusableTriggerService self;

    // P2.1.4 - fail-CLOSED gate variant for the deferred-reset epoch close decision
    // (line 1606). Optional so unit tests that don't seed Redis still construct
    // cleanly; the gate at line 1606 short-circuits when the bean is absent.
    @Autowired(required = false)
    private RunningNodeTracker runningNodeTracker;

    /**
     * P0 (notification V2): per-epoch failure event publisher. Optional so
     * unit tests without Spring context still construct cleanly. Published
     * inside {@link #resetForNextCycle} when {@code hasFailures=true}; the
     * AFTER_COMMIT listener in {@code NotificationEmitter} writes the bell
     * row with idempotency key {@code source_id = runId + ":" + epoch}.
     * Closes the gap where reusable-trigger workflows (schedule/webhook/
     * chat/form) cycle WAITING_TRIGGER ↔ RUNNING and never emit the
     * existing {@code WorkflowRunTerminatedEvent} per-epoch.
     */
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    /**
     * Interface-service client. Used at trigger fire to refresh per-run interface snapshots
     * against the live {@code interface.interfaces} entity so long-running
     * {@code WAITING_TRIGGER} workflows (form/webhook/schedule "apps") pick up agent-driven
     * interface iterations on the next epoch - mirrors the workflow-plan refresh idiom
     * (see {@code WorkflowResumeService.refreshPlanFromWorkflowDefinition}).
     *
     * <p>Optional so unit tests without Spring context still construct cleanly. When absent
     * (test harness) the refresh is silently skipped - snapshots stay frozen, same behaviour
     * as today.
     */
    @Autowired(required = false)
    private com.apimarketplace.interfaces.client.InterfaceClient interfaceClient;

    /**
     * Used to resolve a content-true plan version when a passive fire refreshes
     * {@code run.plan} from {@code workflow.plan}: the plan is compared against the
     * latest stored version (read-only no-op when equal); when it drifted, the
     * latest version's content is overwritten in place - fires never mint a new
     * version number - guaranteeing the run.planVersion ↔ workflow_plan_versions
     * content parity invariant with a version that stays stable across epochs.
     * Optional so narrow unit tests construct cleanly; when absent we fall back to
     * the legacy max-version stamp.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.WorkflowPlanVersionService planVersionService;

    public ReusableTriggerService(
            WorkflowRunRepository runRepository,
            WorkflowRepository workflowRepository,
            WorkflowPlanVersionRepository planVersionRepository,
            TriggerEpochManager epochManager,
            WorkflowStreamingService streamingService,
            @Lazy WorkflowExecutionService executionService,
            com.apimarketplace.orchestrator.services.TriggerResolverService triggerResolverService,
            StateSnapshotService stateSnapshotService,
            EpochConcurrencyLimiter epochConcurrencyLimiter,
            @Lazy ExecutionQueue executionQueueService,
            CreditConsumptionClient creditClient,
            CreditBudgetService creditBudgetService) {
        this.runRepository = runRepository;
        this.workflowRepository = workflowRepository;
        this.planVersionRepository = planVersionRepository;
        this.epochManager = epochManager;
        this.streamingService = streamingService;
        this.executionService = executionService;
        this.triggerResolverService = triggerResolverService;
        this.stateSnapshotService = stateSnapshotService;
        this.epochConcurrencyLimiter = epochConcurrencyLimiter;
        this.executionQueueService = executionQueueService;
        this.creditClient = creditClient;
        this.creditBudgetService = creditBudgetService;
    }

    /**
     * Execute a reusable trigger (webhook, manual, or chat).
     *
     * This is the SINGLE ENTRY POINT for all reusable triggers.
     * Delegates to the system-wide execution queue which handles priority ordering
     * by subscription plan and concurrency limiting.
     *
     * NOT @Transactional - the queue wait must not hold a DB connection.
     * The actual execution happens in {@link #executeTriggerInternal} which IS transactional.
     *
     * @param run The run in WAITING_TRIGGER status
     * @param triggerId The trigger node ID (e.g., "trigger:my_webhook")
     * @param triggerType The type of trigger (WEBHOOK, MANUAL, CHAT)
     * @param payload The trigger payload (webhook body, chat message, etc.)
     * @return Result containing success status, ready steps, and epoch
     */
    public TriggerExecutionResult executeTrigger(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload) {
        TriggerExecutionResult[] result = new TriggerExecutionResult[1];
        TenantResolver.runWithOrgScope(run != null ? run.getOrganizationId() : null,
                () -> result[0] = executeTriggerInCurrentOrgScope(run, triggerId, triggerType, payload));
        return result[0];
    }

    private TriggerExecutionResult executeTriggerInCurrentOrgScope(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload) {
        // Refresh local budget before each trigger fire so it reflects actual balance
        String tenantId = run != null ? run.getTenantId() : null;
        if (tenantId != null && creditBudgetService != null) {
            creditBudgetService.refreshBudget(tenantId);
        }
        // Centralized credit check for ALL trigger types (webhook, schedule, workflow, manual, chat)
        if (tenantId != null && !creditClient.checkCredits(tenantId)) {
            logger.warn("[ReusableTrigger] Insufficient credits for tenant {}, rejecting {} trigger for run {}",
                    tenantId, triggerType, run.getRunIdPublic());
            return TriggerExecutionResult.failure(
                    run.getRunIdPublic(), triggerId, triggerType,
                    "Insufficient credits. Please upgrade your plan at /app/settings/pricing");
        }

        String userPlan = resolveUserPlan(run);
        return executionQueueService.enqueueAndWait(
                run, triggerId, triggerType, payload, userPlan, ExecutionQueueRequestContext.currentRequestId());
    }

    /**
     * Fire-and-forget variant of {@link #executeTrigger} - dispatches the trigger
     * to the execution queue and returns immediately. The HTTP controllers use
     * this in AUTOMATIC mode so the Tomcat thread is freed instead of blocking
     * for the full epoch cycle (~10 s for Gmail-sized workflows).
     *
     * <p>Synchronous callers (MCP agent, schedule cron, webhook handler) keep
     * using {@link #executeTrigger} because they need the post-execution result
     * for their response payload (agent report) or for state advancement
     * ({@code advanceSchedule}).
     *
     * <p>Returns a {@link TriggerExecutionResult#accepted accepted} placeholder
     * (success=true, epoch=-1, no readySteps); SSE is the source of truth for
     * actual progress.
     */
    public TriggerExecutionResult executeTriggerAsync(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload) {
        TriggerExecutionResult[] result = new TriggerExecutionResult[1];
        TenantResolver.runWithOrgScope(run != null ? run.getOrganizationId() : null,
                () -> result[0] = executeTriggerAsyncInCurrentOrgScope(run, triggerId, triggerType, payload));
        return result[0];
    }

    private TriggerExecutionResult executeTriggerAsyncInCurrentOrgScope(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload) {
        String tenantId = run != null ? run.getTenantId() : null;
        if (tenantId != null && creditBudgetService != null) {
            creditBudgetService.refreshBudget(tenantId);
        }
        if (tenantId != null && !creditClient.checkCredits(tenantId)) {
            logger.warn("[ReusableTrigger] Insufficient credits for tenant {}, rejecting async {} trigger for run {}",
                    tenantId, triggerType, run.getRunIdPublic());
            return TriggerExecutionResult.failure(
                    run.getRunIdPublic(), triggerId, triggerType,
                    "Insufficient credits. Please upgrade your plan at /app/settings/pricing");
        }
        String userPlan = resolveUserPlan(run);
        return executionQueueService.enqueueAsync(
                run, triggerId, triggerType, payload, userPlan, ExecutionQueueRequestContext.currentRequestId());
    }

    /**
     * Resolves the user's subscription plan from run metadata.
     * The plan is stored in metadata at run creation time via X-User-Plan header.
     *
     * @return plan code string, defaults to "FREE" if not found
     */
    String resolveUserPlan(WorkflowRunEntity run) {
        if (run == null) {
            return "FREE";
        }
        Map<String, Object> metadata = run.getMetadata();
        if (metadata != null && metadata.containsKey("userPlan")) {
            Object planValue = metadata.get("userPlan");
            if (planValue instanceof String plan && !plan.isBlank()) {
                return plan;
            }
        }
        return "FREE";
    }

    /**
     * Internal method to execute a trigger with optional forced auto mode.
     */
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public TriggerExecutionResult executeTriggerInternal(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            boolean forceAutoMode) {
        return executeTriggerInternal(run, triggerId, triggerType, payload, forceAutoMode, Map.of());
    }

    /**
     * Internal method to execute a trigger with optional forced auto mode and
     * caller-owned execution metadata. The metadata is cached as globalData for
     * this trigger epoch and never becomes user trigger payload.
     */
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public TriggerExecutionResult executeTriggerInternal(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            boolean forceAutoMode,
            Map<String, Object> triggerGlobalData) {

        // Plan v4 §1.6
        if (advisoryLockHelper != null && run != null) {
            advisoryLockHelper.acquireForRun(run.getRunIdPublic());
        }

        // Read-and-strip the inspector-edit marker before anything else touches
        // the payload. The marker (set by TriggerController when the request
        // body carried a `plan` map) signals that updateRunPlan has already
        // written the user's intent to run.plan, so the unpinned-branch
        // unpinned-passive-fire branch in executeTriggerInternal must NOT
        // clobber it back with workflow.plan.
        // Stripping here ensures the marker never reaches setWebhookTriggerPayload
        // (which seeds context.triggerData() and would leak it to sub-workflows),
        // never appears in trigger-payload logs, and can never be replayed from
        // any persisted record.
        boolean planFromPayload = false;
        if (payload != null && Boolean.TRUE.equals(payload.get(PLAN_FROM_PAYLOAD_MARKER))) {
            planFromPayload = true;
            payload = new HashMap<>(payload);
            payload.remove(PLAN_FROM_PAYLOAD_MARKER);
        }

        String runId = run.getRunIdPublic();
        RunStatus previousStatus = run.getStatus();
        logger.info("[ReusableTrigger] Executing {} trigger for runId={}, triggerId={}, previousStatus={}",
            triggerType, runId, triggerId, previousStatus);

        // SBS mode: auto-close ALL active epochs before opening a new one.
        // In step-by-step mode, only one epoch at a time should be visible.
        // This handles two scenarios:
        //   1. Normal re-fire: previous epoch still PAUSED/RUNNING (previousStatus != WAITING_TRIGGER)
        //   2. Post-rerun stale epoch: rerun calls reactivateCurrentEpoch() which puts the old
        //      epoch back in activeEpochs, then sets status to WAITING_TRIGGER. When the trigger
        //      fires, previousStatus=WAITING_TRIGGER but activeEpochs still contains the old epoch.
        // We always check and clean up stale active epochs in SBS mode, regardless of previousStatus.
        {
            ExecutionMode mode = run.getExecutionMode();
            if (mode != null && mode.isStepByStep() && !forceAutoMode && triggerId != null) {
                // Close all active epochs for this trigger's DAG
                Set<Integer> closedEpochs = stateSnapshotService.closeAllActiveEpochs(runId, triggerId);
                if (!closedEpochs.isEmpty()) {
                    // Release concurrency slots for closed epochs
                    for (int i = 0; i < closedEpochs.size(); i++) {
                        epochConcurrencyLimiter.release(runId, triggerId);
                    }
                    // Cancel any blocking signals from the closed epochs
                    if (unifiedSignalService != null) {
                        for (int closedEpoch : closedEpochs) {
                            unifiedSignalService.cancelBlockingByDagAndEpoch(runId, triggerId, closedEpoch);
                        }
                    }
                    logger.info("[ReusableTrigger] SBS auto-closed {} stale epochs before new fire: runId={}, triggerId={}, epochs={}, previousStatus={}",
                        closedEpochs.size(), runId, triggerId, closedEpochs, previousStatus);

                    // CRITICAL: Reload the run entity from DB after closeAllActiveEpochs.
                    // closeAllActiveEpochs() persisted a cleaned state_snapshot (activeEpochs=[]).
                    // The `run` parameter is detached and still has the OLD state_snapshot.
                    // Without this reload, runRepository.save(run) below would overwrite the
                    // cleaned snapshot, restoring the closed epochs (activeEpochs=[1,2] instead of []).
                    run = runRepository.findByRunIdPublic(runId).orElse(run);
                }
            }
        }

        // Track epoch concurrency (for release/cleanup on completion).
        if (triggerId != null) {
            epochConcurrencyLimiter.tryAcquire(runId, triggerId, Integer.MAX_VALUE);
        }

        try {
            // 1. Update run status to RUNNING
            // CRITICAL: capture the return value of save() to get the managed entity.
            // The `run` parameter may be detached (loaded outside this transaction by TriggerController).
            // Without this, subsequent saves of the detached `run` overwrite metadata changes
            // (e.g., epoch increment) made on the managed copy, causing epoch=0 on every cycle.
            run.setStatus(RunStatus.RUNNING);
            run.setUpdatedAt(Instant.now());
            run = runRepository.save(run);

            // 1b. Clear any previous cancel signal (from a prior stop/cancel) so the new execution isn't immediately killed
            if (workflowRedisPublisher != null) {
                workflowRedisPublisher.clearAgentCancelSignal(runId);
            }

            // 2. Plan for this trigger fire - read directly from the run entity.
            //
            // Phase G.1 bypass: previously called resumeService.reconstructState(runId) here,
            // but the only field consumed downstream was state.plan(), which is verbatim
            // runEntity.getPlan() (StateReconstructor builds WorkflowRunState.plan from the
            // same Map). The full reconstructState ran ~200ms in prod (workflow_step_data
            // scan + per-step storage round-trips) and was 100% wasted on this path.
            // Audited 5x Opus 2026-05-07 - confirmed unanimously safe for all 8 trigger
            // types (manual/schedule/webhook/chat/form/datasource/workflow/error).
            String tenantId = run.getTenantId();

            // Resolve plan for this trigger fire:
            // - Pinned + run.planVersion matches → no reload needed (dispatch already ensured version)
            // - Pinned + run.planVersion differs → safety net: reload from version table
            // - No pinned version → use the workflow's latest saved plan
            // Initial seed = run's stored plan (may be null/empty for legacy runs;
            // the safety-net + unpinned-refresh logic below populates it from workflow/version).
            Map<String, Object> planMap = run.getPlan();
            logger.info("[Phase E TEMP][ReusableTrigger] runId={} planFromRun bypass (skipped reconstructState)",
                runId);
            // Load workflow eagerly to avoid LazyInitializationException
            // (run.getWorkflow() is a LAZY proxy that may not be initialized)
            UUID workflowId = run.getWorkflow().getId(); // proxy ID access is safe
            WorkflowEntity workflow = workflowRepository.findById(workflowId).orElse(null);

            // ─── Defense-in-depth chokepoint: pin enforcement for production triggers ───
            // The 5 dispatch services (Schedule, Webhook, Form, Chat, WorkflowTrigger) all
            // route through ProductionRunResolver which refuses to lookup a run if the
            // workflow is unpinned. If anything bypasses that resolver (a future dispatch,
            // a forgotten code path, an integration test), this chokepoint catches it.
            //
            // Editor runs (builder simulate, manual execute from the canvas) are exempt:
            // they're explicitly marked __editorRun__ and may execute against draft versions
            // for testing. The simulate path goes through TriggerController.triggerSpecific
            // → here, with isEditorRun(run) == true.
            if (workflow != null
                    && productionRunResolver != null
                    && !isEditorRun(run)
                    && !productionRunResolver.isAllowedForProduction(run, workflow)) {
                // Refuse without mutating run.status: the target run may be a
                // perfectly valid WAITING_TRIGGER that another correct dispatch
                // would have used. Severity = WARN if just unpinned (user error),
                // ERROR if version mismatch (real dispatch leak).
                if (workflow.getPinnedVersion() == null) {
                    logger.warn(
                        "[Chokepoint] Run {} refused: workflow {} has no pinned version. " +
                        "Pin a version to enable production triggers.",
                        runId, workflowId);
                } else {
                    logger.error(
                        "[Chokepoint] Run {} for workflow {} reached executeTriggerInternal " +
                        "but does not match pinned version (run.planVersion={}, pinned={}). " +
                        "Refusing to execute. This indicates a dispatch service bypassed " +
                        "ProductionRunResolver - investigate.",
                        runId, workflowId, run.getPlanVersion(),
                        workflow.getPinnedVersion());
                }
                return TriggerExecutionResult.failure(
                    runId, triggerId, triggerType,
                    "Production trigger refused: workflow has no pinned version, or run version differs from pinned. " +
                    "Pin a version via workflow management before public triggers can fire.");
            }

            Integer resolvedPlanVersion = null;
            boolean unpinnedRefreshPendingStamp = false;
            if (workflow != null) {
                if (workflow.getPinnedVersion() != null) {
                    if (Objects.equals(run.getPlanVersion(), workflow.getPinnedVersion())) {
                        // Run already has the correct pinned plan (dispatch was version-aware).
                        // Skip version table reload - use the run's cached plan directly.
                        // Phase G.1 pin-safety: if run.getPlan() is null (legacy/corrupt state),
                        // fetch from the version table for the pinned version. NEVER fall back
                        // to workflow.getPlan() (the draft) - that would be a silent pin violation.
                        if (planMap == null) {
                            Optional<WorkflowPlanVersionEntity> pinned = planVersionRepository
                                .findByWorkflowIdAndVersion(workflowId, workflow.getPinnedVersion());
                            if (pinned.isPresent()) {
                                planMap = pinned.get().getPlan();
                                logger.warn("[PinnedVersion] Run {} had null cached plan; loaded pinned v{} from version table",
                                        runId, workflow.getPinnedVersion());
                            }
                        }
                        resolvedPlanVersion = workflow.getPinnedVersion();
                        logger.info("[PinnedVersion] Run {} already at pinned v{}, skipping reload",
                                runId, workflow.getPinnedVersion());
                    } else if (isEditorRun(run)) {
                        // Run was explicitly created from the editor at a specific version.
                        // Respect the editor's version - don't override with pinned version.
                        resolvedPlanVersion = run.getPlanVersion();
                        logger.info("[PinnedVersion] Run {} is editor-initiated at v{}, keeping as-is (pinned is v{})",
                                runId, run.getPlanVersion(), workflow.getPinnedVersion());
                    } else {
                        // Safety net: trigger-dispatched run where pinned version changed
                        // after run creation. Reload to the current pinned version.
                        try {
                            // If the MOST RECENT run for this pinned version is CANCELLED,
                            // skip the last-run fallback and go directly to the version table.
                            Optional<WorkflowRunEntity> mostRecentRun = runRepository
                                    .findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(
                                            workflowId, workflow.getPinnedVersion());
                            boolean latestIsCancelled = mostRecentRun.isPresent()
                                    && mostRecentRun.get().getStatus() == RunStatus.CANCELLED;

                            if (!latestIsCancelled) {
                                Optional<WorkflowRunEntity> lastPinnedRun = runRepository
                                        .findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(
                                                workflowId, workflow.getPinnedVersion(),
                                                java.util.List.of(RunStatus.COMPLETED, RunStatus.WAITING_TRIGGER,
                                                        RunStatus.RUNNING, RunStatus.PAUSED));
                                if (lastPinnedRun.isPresent() && lastPinnedRun.get().getPlan() != null) {
                                    planMap = lastPinnedRun.get().getPlan();
                                    resolvedPlanVersion = workflow.getPinnedVersion();
                                    logger.info("[PinnedVersion] Safety net: using last run's plan for pinned v{} on run {} (was v{})",
                                            workflow.getPinnedVersion(), runId, run.getPlanVersion());
                                } else {
                                    // No run exists for this version yet - fall back to version table
                                    Optional<WorkflowPlanVersionEntity> pinned = planVersionRepository
                                            .findByWorkflowIdAndVersion(workflowId, workflow.getPinnedVersion());
                                    if (pinned.isPresent()) {
                                        planMap = pinned.get().getPlan();
                                        resolvedPlanVersion = workflow.getPinnedVersion();
                                        logger.info("[PinnedVersion] Safety net: loaded from version table for pinned v{} on run {} (no prior run)",
                                                workflow.getPinnedVersion(), runId, run.getPlanVersion());
                                    } else {
                                        logger.warn("[PinnedVersion] Pinned v{} not found for workflow {}, falling back to latest",
                                                workflow.getPinnedVersion(), workflowId);
                                        planMap = workflow.getPlan();
                                        resolvedPlanVersion = planVersionRepository.getMaxVersion(workflowId).orElse(null);
                                    }
                                }
                            } else {
                                // Latest run was cancelled - use version table directly (clean slate)
                                Optional<WorkflowPlanVersionEntity> pinned = planVersionRepository
                                        .findByWorkflowIdAndVersion(workflowId, workflow.getPinnedVersion());
                                if (pinned.isPresent()) {
                                    planMap = pinned.get().getPlan();
                                    resolvedPlanVersion = workflow.getPinnedVersion();
                                    logger.info("[PinnedVersion] Safety net: using version table for pinned v{} on run {} (latest run was CANCELLED)",
                                            workflow.getPinnedVersion(), runId);
                                } else {
                                    logger.warn("[PinnedVersion] Pinned v{} not found for workflow {}, falling back to latest",
                                            workflow.getPinnedVersion(), workflowId);
                                    planMap = workflow.getPlan();
                                    resolvedPlanVersion = planVersionRepository.getMaxVersion(workflowId).orElse(null);
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("[PinnedVersion] Failed to resolve pinned version for run {}: {}",
                                    runId, e.getMessage());
                        }
                    }
                } else if (!planFromPayload && workflow.getPlan() != null) {
                    if (isVersionReplay(run)) {
                        // Version-replay editor run (workflow(action='execute', version=N)):
                        // the whole point is to execute the frozen historical plan. Refreshing
                        // from workflow.plan here would silently swap in the latest draft
                        // whenever it is topology-compatible - the "replay" would execute the
                        // wrong version and get re-stamped to it. Keep the run's frozen
                        // plan + planVersion untouched.
                        logger.info("[PlanResolution] Run {} is a version replay at v{} - skipping workflow.plan refresh",
                                runId, run.getPlanVersion());
                    } else {
                        // Unpinned passive fire (webhook, schedule, sub-workflow,
                        // agent fire, error handler - anything that did NOT set
                        // PLAN_FROM_PAYLOAD_MARKER): refresh from the workflow's
                        // last saved plan so main-editor edits propagate to
                        // existing reusable runs.
                        //
                        // When the marker is present, TriggerController already
                        // wrote the user's payload plan into run.plan via
                        // updateRunPlan; run.getPlan() (assigned to planMap above)
                        // IS that fresh plan. Skipping this branch preserves the
                        // inspector-edit semantics that previously were silently
                        // reverted.
                        planMap = workflow.getPlan();
                        // Content-true version resolution is deferred until AFTER the
                        // topology guard - a rejected refresh must not create a version
                        // row (with billing + purge side effects) for a plan the run
                        // will not execute.
                        unpinnedRefreshPendingStamp = true;
                        logger.debug("[PlanResolution] Using latest workflow plan for trigger fire on run {}", runId);
                    }
                }
            }

            // ─── Topology guard: protect StateSnapshot across plan refresh ───
            // The resolved planMap may differ from run.getPlan() in two scenarios:
            //   (a) unpinned draft edits were applied between fires (primary case);
            //   (b) pinned safety-net reloaded v{N} from the version table and drift
            //       exists between run.getPlan() and that canonical copy - this
            //       should be a no-op in practice (same version = same topology);
            //       divergence here indicates stale snapshot or DB anomaly.
            // StateSnapshot indexes per-node counters by node id; swapping in a
            // plan with added/removed nodes or rewired edges corrupts that state.
            // Topology-compatible changes (params, prompts, config) flow through.
            // Incompatible changes → revert to the frozen plan and WARN - the user
            // must create a new run for structural edits.
            // Note on equality: both operands are Jackson-deserialized JSONB maps.
            // Key ordering is not guaranteed across round-trips; a false "differ"
            // costs one topology parse and still passes - harmless.
            if (planMap != null && run.getPlan() != null && !planMap.equals(run.getPlan())) {
                if (com.apimarketplace.orchestrator.utils.PlanTopology.areCompatible(run.getPlan(), planMap)) {
                    logger.info("[PlanRefresh] runId={} plan refreshed for upcoming epoch (topology-compatible edits applied)",
                            runId);
                } else {
                    logger.warn("[PlanRefresh] runId={} topology change detected in live plan; keeping frozen plan. Create a new run to apply structural edits. Diff: {}",
                            runId,
                            com.apimarketplace.orchestrator.utils.PlanTopology.describeDiff(run.getPlan(), planMap));
                    planMap = run.getPlan();
                    // Keep the run on its frozen plan version too - don't advance.
                    resolvedPlanVersion = run.getPlanVersion();
                    unpinnedRefreshPendingStamp = false;
                }
            }

            // Phase G.1: post-resolution null check. Replaces the old reconstructState
            // implicit fail-fast (it threw "No plan found for run" before any plan
            // resolution). Now we let the safety-net try to load from workflow/version
            // first, and only fail if STILL nothing. Runs BEFORE the content-true stamp
            // below so a refused fire never creates a version row as a side effect.
            if (planMap == null || planMap.isEmpty()) {
                logger.error("[ReusableTrigger] Run {} has no plan after resolution - refusing to fire trigger {}",
                    runId, triggerId);
                resetRunOnFailure(run, runId, triggerId);
                return TriggerExecutionResult.failure(runId, triggerId, triggerType,
                    "Run has no plan");
            }

            // Deferred content-true stamp for the unpinned refresh: the plan survived
            // the topology guard, so resolve the version whose stored content matches
            // it (read-only when equal; drifted content overwrites the latest version
            // in place - fires never mint a new version number).
            if (unpinnedRefreshPendingStamp) {
                resolvedPlanVersion = resolveVersionForPlan(workflowId, planMap, tenantId);
            }

            // Update run entity with the resolved plan + version for this trigger fire.
            // Without this, reusable runs keep the planVersion from creation time and the
            // frontend shows a stale version badge.
            if (resolvedPlanVersion != null) {
                run.setPlanVersion(resolvedPlanVersion);
            }
            run.setPlan(planMap instanceof HashMap ? planMap : new HashMap<>(planMap));
            run = runRepository.save(run);

            WorkflowPlan plan = WorkflowPlan.fromMap(planMap, workflowId.toString(), tenantId);

            // Defense-in-depth (every externally-fired trigger type): refuse to open an epoch
            // for a trigger that no longer exists in the resolved plan. A stale registration in
            // trigger-service (an orphaned schedule row, a webhook/chat/form token) must never
            // fire a trigger the user deleted from the workflow. The primary cure keeps the
            // trigger-service row suspended (InternalTriggerController.enableSchedulesByWorkflow
            // no longer revives PLAN_TRIGGER_REMOVED orphans), but this orchestrator-side guard
            // holds even across a sync race or a removal not yet synced - and there was NO
            // plan-membership check at fire time before this. Scoped to passive/public external
            // types: MANUAL/WORKFLOW/ERROR fire with internal trigger ids that are not always
            // declared in plan.triggers, so blocking them would be a regression.
            //
            // We only require getTriggers() != null (an unparseable plan must not block a fire);
            // an EMPTY triggers list is a fully-resolved "no triggers exist" state, so an external
            // fire against it IS an orphan and must be refused (covers deleting the last trigger).
            if (triggerId != null
                    && isExternallyFiredTriggerType(triggerType)
                    && plan.getTriggers() != null
                    && plan.findTriggerByKey(triggerId).isEmpty()) {
                logger.error("[ReusableTrigger] Refusing {} fire for runId={}: trigger {} is not present in the "
                        + "resolved plan (removed from the workflow) - no epoch opened.",
                        triggerType, runId, triggerId);
                resetRunOnFailure(run, runId, triggerId);
                return TriggerExecutionResult.failure(runId, triggerId, triggerType,
                        "Trigger '" + triggerId + "' no longer exists in the workflow plan");
            }

            // 3. Epoch management: always increment epoch on every trigger fire.
            // Each fire cycle MUST get a unique epoch to avoid duplicate scope conflicts
            // in workflow_step_data (unique index includes epoch).
            // Both AUTO and SBS modes increment BEFORE execution so that
            // getCurrentEpochFromRun() returns the correct epoch during step persistence.
            //
            // Capture per-DAG epoch BEFORE increment to detect re-fires.
            // We cannot rely on run-level previousStatus because multi-DAG keeps the run
            // in WAITING_TRIGGER even after a trigger has already fired (another DAG's
            // trigger holds it there).  The per-DAG epoch is the only reliable indicator.
            int epochBeforeFire = triggerId != null
                ? epochManager.getCurrentEpoch(run, triggerId)
                : epochManager.getCurrentEpoch(run);
            int newEpoch = triggerId != null
                ? epochManager.incrementEpoch(run, triggerId)
                : epochManager.incrementEpoch(run);

            boolean refire = epochBeforeFire > 0;

            // Stamp WorkflowEntity.lastExecutedAt on every trigger fire so the
            // workflow board "last executed" column reflects the most recent
            // epoch, not just the run creation. Reusable triggers (schedule,
            // webhook, datasource, chat, manual) reuse the same WorkflowRunEntity
            // for every fire, so WorkflowRunPersistenceService.recordWorkflowStart
            // (which already stamps lastExecutedAt) only runs once at run birth.
            // Without this, a daily/hourly schedule appears stuck at "1d ago".
            //
            // IMPORTANT: do NOT touch updatedAt here. updatedAt MUST track real
            // user/admin edits to the workflow definition (name, description,
            // plan, …) so the workflows tab "Modified" column stays meaningful.
            // Stamping updatedAt on every trigger fire pollutes that signal -
            // an hourly schedule would show "modified 1h ago" perpetually even
            // when nobody edited the workflow.
            try {
                // Bulk-update bypasses the WorkflowRunEntity.workflow LAZY proxy:
                // pre-fix `run.getWorkflow().setLastExecutedAt(..); save(entity)` forced
                // proxy initialization, which threw "Could not initialize proxy - no session"
                // on the schedule-spread async path (no active Hibernate session) and
                // silently dropped the timestamp 13×/day. proxy.getId() is safe - it does
                // not initialize the proxy.
                WorkflowEntity workflowProxy = run.getWorkflow();
                if (workflowProxy != null && workflowProxy.getId() != null) {
                    workflowRepository.updateLastExecutedAt(workflowProxy.getId(), Instant.now());
                }
            } catch (Exception e) {
                logger.warn("[ReusableTrigger] Failed to update workflow.lastExecutedAt for runId={}: {}",
                            runId, e.getMessage());
            }

            // Open the epoch in StateSnapshot so activeEpochs includes this epoch.
            // This is critical: without it, computeFlatSet() returns empty sets because
            // it only unions from activeEpochs. TriggerEpochManager.incrementEpoch() only
            // updates DB metadata - it does NOT touch the StateSnapshot's DagState.
            if (triggerId != null) {
                stateSnapshotService.openEpoch(runId, triggerId, newEpoch);
            }
            // Refresh per-run interface snapshots against the live interface entity.
            //
            // Long-running WAITING_TRIGGER workflows (form/webhook/schedule "apps") re-fire
            // the same run across many epochs; interface_run_snapshots are stamped once at
            // run creation and frozen for the run's lifetime. Without this refresh the user
            // sees the same (often outdated) HTML/JS even after the agent corrects the
            // template - exactly the prod issue surfaced 2026-05-14 on the Instagram Profile
            // Scraper (UI sees old `<script type="application/json">{{var}}` HTML-escape bug
            // even after the agent migrated to window.__RESOLVED_DATA__).
            //
            // Mirrors the plan-side refresh idiom used by
            // WorkflowResumeService.refreshPlanFromWorkflowDefinition - same contract:
            //   • interface still exists + content changed → write through to snapshot row
            //   • interface deleted since run started   → keep frozen (do NOT blank)
            //   • content unchanged                     → no-op SELECT, no UPDATE
            //
            // Tolerated as best-effort: if the interface-service round-trip fails the
            // trigger pipeline continues with whatever snapshot is there (worst case: user
            // sees yesterday's HTML for one more epoch). Gated on `refire` so the first fire
            // (where the snapshot was just created from the same live source) skips the
            // round-trip entirely.
            if (refire && interfaceClient != null) {
                try {
                    // Thread organizationId explicitly: trigger-fire is async (scheduled trigger
                    // path has no RequestContextHolder), so OrgContextHeaderForwarder would
                    // resolve null on the outbound hop and the receiver would fall to the
                    // unscoped legacy refresh - overwriting cross-org snapshots. Same shape as
                    // PR20's AgentObservabilityClient.recordAsync fix.
                    interfaceClient.refreshSnapshotsFromLive(run.getId(), run.getTenantId(),
                            run.getOrganizationId());
                } catch (Exception e) {
                    // Tolerated: never block the trigger pipeline on a snapshot refresh.
                    logger.warn("[ReusableTrigger] Interface snapshot refresh failed for runId={} (non-critical): {}",
                            runId, e.getMessage());
                }
            }

            // SBS refire: openEpoch() above already created a fresh epoch for this fire.
            // reconcileSbsRunStatus() closed all old epochs (JSONB + workflow_epochs table)
            // when the previous SBS cycle completed → the DagState is clean.
            //
            // Do NOT call resetDagWithRerunPattern here: it advances DagState.currentEpoch
            // beyond the just-opened epoch, causing prepareNextEpochReady() to target the
            // wrong epoch (phantom next-next epoch instead of the active one).
            //
            // AUTO mode: resetForNextCycle() handles state reset at end of cycle.
            ExecutionMode mode = forceAutoMode ? ExecutionMode.AUTOMATIC : resumeService.getExecutionMode(runId);
            if (mode.isStepByStep() && refire) {
                // Exclude STREAMING domain: WsEventSequencer's monotonic seq counter and
                // SnapshotService's active-run cache must survive across SBS refires.
                // Purging them mid-run causes deferred fire #N publishes to collide with
                // fire #N+1 seqs → frontend strict-< drop → UI freezes (2026-05-05 audit).
                resumeService.clearCachedStateForRerun(runId,
                    java.util.Set.of(RunScopedCache.CacheDomain.STREAMING));
            }
            logger.info("[ReusableTrigger] Epoch {} for runId={}, mode={}, refire={} (epochBefore={})",
                newEpoch, runId, mode, refire, epochBeforeFire);

            // 4. Create execution context from DB state (always fresh, no in-memory cache)
            WorkflowExecution execution = new WorkflowExecution(runId, plan, Map.of());
            execution.setWorkflowRunId(run.getId());

            // Restore display name from run metadata
            if (run.getMetadata() != null) {
                if (run.getMetadata().get("__displayName__") instanceof String dn) {
                    execution.setDisplayName(dn);
                }
            }

            // 5. Load and store trigger payload in execution context
            // All trigger types use the same payload storage mechanism
            Map<String, Object> triggerPayload = payload;

            // For DATASOURCE triggers, load data from database
            if (triggerType == TriggerType.DATASOURCE) {
                logger.info("[ReusableTrigger] Loading datasource data for triggerId={}", triggerId);
                triggerPayload = loadDatasourceData(run, plan, triggerId, payload);
            }

            if (triggerPayload != null && !triggerPayload.isEmpty()) {
                logger.info("[ReusableTrigger] Storing {} payload for triggerId={}: {}",
                           triggerType, triggerId, triggerPayload);
                execution.setWebhookTriggerPayload(triggerId, triggerPayload);
            }

            // 6. Send streaming event that workflow is resuming (all trigger types, including webhook)
            streamingService.sendWorkflowStatusEvent(execution, RunStatus.RUNNING,
                triggerType.getValue() + " trigger received");

            // 7. Execute the trigger step using V2 step-by-step service
            if (v2StepByStepService == null) {
                logger.error("[ReusableTrigger] V2StepByStepService not available - cannot execute trigger");
                resetRunOnFailure(run, runId, triggerId);
                return TriggerExecutionResult.failure(runId, triggerId, triggerType,
                    "V2 execution service not available");
            }
            TriggerExecutionResult result = executeWithV2Service(
                run, execution, plan, triggerId, triggerType, runId, forceAutoMode, newEpoch, triggerGlobalData);

            // Recovery: if execution failed, reset run back to WAITING_TRIGGER
            // so dispatch services (schedule, webhook) can reuse it instead of creating zombie runs
            if (!result.success()) {
                if (result.epoch() <= 0) {
                    resetRunOnFailure(run, runId, triggerId);
                } else {
                    logger.warn("[ReusableTrigger] Trigger returned failed after handled epoch {} for runId={}, skipping duplicate reset",
                            result.epoch(), runId);
                }
            }
            return result;

        } catch (Exception e) {
            logger.error("[ReusableTrigger] Error executing {} trigger: runId={}, error={}",
                        triggerType, runId, e.getMessage(), e);
            resetRunOnFailure(run, runId, triggerId);
            return TriggerExecutionResult.failure(runId, triggerId, triggerType,
                "Failed to execute trigger: " + e.getMessage());
        }
    }

    /**
     * Trigger types that are fired by an external/automated source against a public or
     * scheduled surface and therefore MUST exist as a declared trigger in the resolved plan.
     *
     * <p>Excludes {@code MANUAL} (editor/inspector runs), {@code WORKFLOW} (sub-workflow
     * invocation) and {@code ERROR} (error-handler dispatch): those fire with internal trigger
     * ids that are not always present in {@code plan.getTriggers()}, so gating them on plan
     * membership would refuse legitimate fires. The orphaned-trigger class this guards
     * (a deleted schedule/webhook/chat/form that keeps firing) only arises for these external
     * surfaces.
     */
    private static boolean isExternallyFiredTriggerType(TriggerType triggerType) {
        return triggerType == TriggerType.SCHEDULE
                || triggerType == TriggerType.WEBHOOK
                || triggerType == TriggerType.CHAT
                || triggerType == TriggerType.FORM
                || triggerType == TriggerType.DATASOURCE;
    }

    /**
     * Reset a run back to WAITING_TRIGGER after a failure in executeTriggerInternal.
     * This prevents zombie runs stuck in RUNNING status when trigger execution fails
     * (e.g., trigger ID mismatch, V2 service unavailable, unexpected exception).
     *
     * Dispatch services (schedule, webhook, form) query for WAITING_TRIGGER runs,
     * so resetting ensures the run can be reused on the next trigger event.
     */
    private void resetRunOnFailure(WorkflowRunEntity run, String runId, String triggerId) {
        try {
            // Release concurrency slot on failure
            if (triggerId != null) {
                epochConcurrencyLimiter.release(runId, triggerId);
            }

            // Close the epoch in StateSnapshot on failure too
            if (triggerId != null) {
                int failedEpoch = epochManager.getCurrentEpoch(run, triggerId);
                // Cancel pending blocking signals before pruning the epoch to avoid
                // zombie signals being resolved by the timer pollers post-close.
                if (unifiedSignalService != null) {
                    try {
                        unifiedSignalService.cancelBlockingByDagAndEpoch(runId, triggerId, failedEpoch);
                    } catch (Exception ex) {
                        logger.warn("[ReusableTrigger] Failure-path: signal cancel failed runId={} triggerId={} epoch={}: {}",
                                runId, triggerId, failedEpoch, ex.getMessage());
                    }
                }
                stateSnapshotService.closeEpoch(runId, triggerId, failedEpoch);
            }

            // Use StateSnapshot as source of truth for active epochs (not semaphore)
            boolean anyActive = stateSnapshotService.hasAnyActiveEpoch(runId);
            RunStatus status = anyActive ? RunStatus.RUNNING : RunStatus.WAITING_TRIGGER;

            // Reload run from DB to avoid overwriting state_snapshot changes
            // made by closeEpoch() and other snapshot-modifying methods above
            WorkflowRunEntity freshRun = runRepository.findByRunIdPublic(runId).orElse(run);

            // Terminal guard: if the run was cancelled/completed between the initial
            // failure and this reload, do NOT overwrite the terminal status with
            // RUNNING/WAITING_TRIGGER - that would silently un-cancel the run.
            if (freshRun.getStatus().isTerminal()) {
                logger.info("[ReusableTrigger] Run {} already terminal ({}) - skipping resetRunOnFailure",
                    runId, freshRun.getStatus());
                return;
            }

            freshRun.setStatus(status);
            if (!anyActive) {
                freshRun.setEndedAt(null);
            }
            freshRun.setUpdatedAt(Instant.now());
            runRepository.save(freshRun);

            // Send snapshot so frontend sees the failure recovery state
            sendPostResetSnapshot(runId);

            logger.warn("[ReusableTrigger] Reset run {} to {} after failure (otherEpochsActive={})",
                        runId, status, anyActive);
        } catch (Exception e) {
            logger.error("[ReusableTrigger] Failed to reset run {} after failure: {}",
                        runId, e.getMessage(), e);
        }
    }

    /**
     * Execute trigger using V2 step-by-step service.
     */
    private TriggerExecutionResult executeWithV2Service(
            WorkflowRunEntity run,
            WorkflowExecution execution,
            WorkflowPlan plan,
            String triggerId,
            TriggerType triggerType,
            String runId,
            boolean forceAutoMode,
            int epoch,
            Map<String, Object> triggerGlobalData) {

        try {
            // Always re-initialize V2 step-by-step on each trigger fire.
            // The plan may have changed (pinned version resolution or latest edit),
            // and the trigger ID differs between trigger types (webhook vs manual).
            // Skipping re-init would use a stale execution tree from a previous fire.
            logger.info("[ReusableTrigger] Initializing V2 step-by-step for runId={}, triggerId={}", runId, triggerId);
            v2StepByStepService.initializeStepByStep(execution, plan, triggerId);

            // Pre-cache trigger payload so it's available as triggerData in the execution context.
            // The execution object here has the payload (set in executeTriggerInternal), but
            // V2StepByStepService loads a different execution from DB, losing the payload.
            Map<String, Object> webhookPayload = execution.getWebhookTriggerPayload(triggerId);
            if (triggerGlobalData != null && !triggerGlobalData.isEmpty()) {
                v2StepByStepService.cacheTriggerGlobalData(runId, epoch, triggerGlobalData);
            }
            if (webhookPayload != null && !webhookPayload.isEmpty()) {
                v2StepByStepService.cacheTriggerPayload(runId, epoch, webhookPayload);
            }

            // Execute the trigger node with explicit epoch + triggerId for parallel epoch isolation
            StepByStepExecutionResult v2Result = v2StepByStepService.executeNode(runId, triggerId, "0", epoch, triggerId);

            if (v2Result.isSuccess()) {
                logger.info("[ReusableTrigger] {} trigger executed successfully: runId={}, triggerId={}, epoch={}",
                           triggerType, runId, triggerId, epoch);

                // Prepare the trigger for the next fire by adding it back to readyNodes
                // This enables parallel epoch execution: the trigger appears fireable
                // while the current epoch is still executing.
                stateSnapshotService.prepareNextEpochReady(runId, triggerId);

                // Get ready steps from result
                Set<String> readyNodes = v2Result.readyNodes();

                // Check execution mode (forceAutoMode overrides stored mode)
                ExecutionMode mode = resumeService.getExecutionMode(runId);
                boolean useStepByStep = mode.isStepByStep() && !forceAutoMode;

                if (useStepByStep) {
                    // Step-by-step mode: pause after trigger, wait for user action
                    return handleStepByStepMode(run, execution, readyNodes, triggerId, triggerType, runId, epoch);
                } else {
                    // Auto mode: continue execution then reset for next cycle
                    return handleAutoMode(run, execution, plan, readyNodes, triggerId, triggerType, runId, epoch,
                        shouldFailTriggerOnNodeFailures(triggerGlobalData));
                }
            } else {
                logger.error("[ReusableTrigger] {} trigger execution failed: runId={}, error={}",
                            triggerType, runId, v2Result.getErrorMessage());
                return TriggerExecutionResult.failure(runId, triggerId, triggerType,
                    v2Result.getErrorMessage());
            }
        } catch (Exception e) {
            logger.error("[ReusableTrigger] Error in V2 execution: runId={}, error={}",
                        runId, e.getMessage(), e);
            return TriggerExecutionResult.failure(runId, triggerId, triggerType,
                "V2 execution failed: " + e.getMessage());
        }
    }

    /**
     * Handle step-by-step mode: pause after trigger execution.
     *
     * Multi-DAG aware: uses the StateSnapshot (single source of truth) to get
     * the merged ready nodes across all DAGs.  If at least one other trigger is
     * still ready (another DAG waiting for its webhook/schedule), the run status
     * is set to WAITING_TRIGGER so the frontend keeps polling.  Otherwise PAUSED.
     */
    private TriggerExecutionResult handleStepByStepMode(
            WorkflowRunEntity run,
            WorkflowExecution execution,
            Set<String> readyNodes,
            String triggerId,
            TriggerType triggerType,
            String runId,
            int epoch) {

        logger.info("[ReusableTrigger] Step-by-step mode: after {} trigger for runId={}, epoch={}",
                   triggerType, runId, epoch);

        // Use snapshot as SINGLE SOURCE OF TRUTH for merged ready nodes across DAGs
        Set<String> mergedReadyNodes = stateSnapshotService.getReadyNodeIds(runId);
        Set<String> effectiveReadyNodes = (mergedReadyNodes != null && !mergedReadyNodes.isEmpty())
            ? mergedReadyNodes : (readyNodes != null ? readyNodes : Set.of());

        // Determine status based on ready nodes.
        // SBS single-epoch policy: WAITING_TRIGGER only when the epoch is fully done
        // (only trigger nodes remain ready - from prepareNextEpochReady).
        // If non-trigger nodes are still ready, the epoch is in progress → PAUSED.
        RunStatus status;
        {
            boolean hasNonTriggerReady = effectiveReadyNodes.stream()
                .anyMatch(nodeId -> !nodeId.startsWith("trigger:"));
            if (hasNonTriggerReady) {
                status = RunStatus.PAUSED;
            } else {
                status = StepByStepExecutor.hasTriggerInReadyNodes(effectiveReadyNodes)
                    ? RunStatus.WAITING_TRIGGER
                    : RunStatus.PAUSED;
            }
        }

        // CRITICAL: Reload run from DB to avoid overwriting state_snapshot.
        // The `run` parameter was loaded at the start of executeTriggerInternal() and is stale -
        // openEpoch(), mergeReadyNodesAfterExecution(), and prepareNextEpochReady() each loaded
        // fresh copies via findByRunIdPublicForUpdate() and saved updated snapshots.
        // Saving the stale `run` would overwrite those snapshot changes, causing agent nodes
        // to disappear from readyNodeIds and triggering 409 Conflict on SBS execute.
        WorkflowRunEntity freshRun = runRepository.findByRunIdPublic(runId).orElse(run);

        // Terminal guard: if the run was cancelled between trigger execution and this
        // status write, do NOT overwrite CANCELLED with PAUSED/WAITING_TRIGGER.
        if (freshRun.getStatus().isTerminal()) {
            logger.info("[ReusableTrigger] Run {} already terminal ({}) - skipping handleStepByStepMode status overwrite",
                runId, freshRun.getStatus());
            return TriggerExecutionResult.success(runId, triggerId, triggerType,
                "Run already terminal (" + freshRun.getStatus() + "), skipping step-by-step status update",
                effectiveReadyNodes, epoch);
        }

        freshRun.setStatus(status);
        freshRun.setUpdatedAt(Instant.now());
        runRepository.save(freshRun);

        logger.info("[ReusableTrigger] Set status to {} for runId={} (effectiveReadyNodes={})",
            status, runId, effectiveReadyNodes);

        // Send streaming events
        streamingService.sendWorkflowStatusEvent(execution, status,
            triggerType.getValue() + " received, waiting for next step");

        if (!effectiveReadyNodes.isEmpty()) {
            streamingService.sendReadyStepsEvent(execution, effectiveReadyNodes);
        }

        // Push the final accumulated snapshot, mirroring handleAutoMode (which already does this).
        // Without it, the run-view only has the trigger NODE's own completion snapshot - emitted
        // BEFORE prepareNextEpochReady() and the status save above - so the just-incremented
        // accumulated NodeCounts/EdgeCounts and the downstream nodes' new `pending` status never
        // reach the wire on an SBS fire. The frontend intentionally ignores the per-node
        // stepStatus/edgeStatus deltas (the batch-update snapshot is its single source of truth for
        // visualization), so without this the canvas stays one epoch stale until a manual refresh.
        // Called after runRepository.save(freshRun) so the snapshot reads the committed status.
        sendPostResetSnapshot(runId);

        return TriggerExecutionResult.success(runId, triggerId, triggerType,
            "Trigger executed, " + (status == RunStatus.WAITING_TRIGGER
                ? "waiting for other triggers" : "paused for step-by-step"),
            effectiveReadyNodes, epoch);
    }

    /**
     * Handle auto mode: execute all ready steps then reset for next cycle.
     *
     * V1 fix: After executing ready steps, check if any signals are still pending
     * (e.g., WaitNode > 3s registered a signal and yielded). If signals are pending,
     * do NOT reset - keep the run as RUNNING. The SignalResumeService will handle
     * deferred reset after the last signal for this DAG resolves.
     */
    private TriggerExecutionResult handleAutoMode(
            WorkflowRunEntity run,
            WorkflowExecution execution,
            WorkflowPlan plan,
            Set<String> readyNodes,
            String triggerId,
            TriggerType triggerType,
            String runId,
            int epoch,
            boolean failTriggerOnNodeFailures) {

        logger.info("[ReusableTrigger] Auto mode: continuing execution of {} ready nodes for runId={}, epoch={}",
                   readyNodes != null ? readyNodes.size() : 0, runId, epoch);

        // Execute all ready steps and track failures (with explicit epoch)
        boolean hasFailures = false;
        if (readyNodes != null && !readyNodes.isEmpty()) {
            hasFailures = executeReadySteps(execution, readyNodes, runId, epoch, triggerId, plan);
        }

        // Check for pending signals OR in-flight async agents BEFORE resetting
        // (epoch-scoped for parallel epochs). The async-agent half of this check is the
        // replacement for the old SignalWait.blocking=true gate and is critical for the
        // "classify after guardrail split" scenario - see hasActiveSignalsForTrigger.
        if (hasActiveSignalsForTrigger(runId, triggerId, epoch)) {
            logger.info("[ReusableTrigger] Signals or async agents pending for DAG {} epoch {}, deferring reset for runId={}",
                triggerId, epoch, runId);
            return TriggerExecutionResult.success(runId, triggerId, triggerType,
                "Execution paused, waiting for signals or async agents (epoch " + epoch + ")",
                Set.of(), epoch);
        }

        // Epoch-scoped reset: close this epoch, release concurrency slot
        int resultEpoch = self.resetForNextCycle(run, execution, plan, runId, triggerType, triggerId, hasFailures, epoch);

        // Send a final snapshot after reset (transaction committed via self-proxy).
        // Without this, the frontend only has the last node-completion snapshot (before epoch
        // close), missing the final state (readySteps, closed epoch, WAITING_TRIGGER status).
        // Critical for the "all epochs" view to show up-to-date accumulated data.
        sendPostResetSnapshot(runId);

        // Dispatch to downstream workflows (async) - reusable trigger workflows never go through
        // V2WorkflowFinalizer, so we must dispatch here after each successful epoch cycle.
        if (!hasFailures && workflowTriggerDispatchService != null) {
            workflowTriggerDispatchService.dispatchCycleCompletion(
                runId, execution.getWorkflowRunId(), execution.getStepOutputs(), false);
        }

        // Symmetric error dispatch: when the epoch cycle has failures, notify any workflow
        // whose `error` trigger references this workflow. Without this branch, reusable-trigger
        // workflows (manual/webhook/chat/schedule/form - i.e. nearly everything) never invoke
        // ErrorTriggerDispatchService, which was previously only wired into V2WorkflowFinalizer.
        // That made `error` triggers feature-dead at runtime (#ET1).
        //
        // dispatchEpochFailure skips the terminal-status gate because reusable-trigger runs
        // never transition to FAILED/PARTIAL_SUCCESS - they reset to WAITING_TRIGGER for the
        // next cycle.
        if (hasFailures && errorTriggerDispatchService != null) {
            try {
                errorTriggerDispatchService.dispatchEpochFailure(execution);
            } catch (Exception e) {
                logger.warn("[ReusableTrigger] Error dispatch threw for runId={}: {}", runId, e.getMessage(), e);
            }
        }

        if (hasFailures && failTriggerOnNodeFailures) {
            return TriggerExecutionResult.failure(runId, triggerId, triggerType,
                "Sub-workflow execution failed during epoch " + resultEpoch, resultEpoch);
        }

        return TriggerExecutionResult.success(runId, triggerId, triggerType,
            "Cycle completed, ready for next trigger (epoch " + resultEpoch + ")",
            Set.of(), resultEpoch);
    }

    private boolean shouldFailTriggerOnNodeFailures(Map<String, Object> triggerGlobalData) {
        return triggerGlobalData != null
            && (triggerGlobalData.containsKey(ExecutionMetadataKeys.SUB_WORKFLOW_DEPTH)
                || triggerGlobalData.containsKey(ExecutionMetadataKeys.SUB_WORKFLOW_ANCESTRY));
    }

    /**
     * Execute all ready steps using V2 service (backward compat, no explicit epoch/triggerId).
     */
    private boolean executeReadySteps(WorkflowExecution execution, Set<String> readySteps, String runId) {
        return executeReadySteps(execution, readySteps, runId, -1, null);
    }

    private boolean executeReadySteps(WorkflowExecution execution, Set<String> readySteps, String runId, int epoch, String triggerId) {
        WorkflowPlan plan = execution != null ? execution.getPlan() : null;
        return executeReadySteps(execution, readySteps, runId, epoch, triggerId, plan);
    }

    /**
     * Execute all ready steps using V2 service with optional explicit epoch and triggerId.
     *
     * IMPORTANT: This filters out trigger nodes - triggers must be explicitly fired
     * by the user and should NOT be auto-executed when calculating ready steps.
     * This enables multi-DAG workflows where each trigger controls its own DAG.
     *
     * @param epoch explicit epoch for parallel epoch isolation (-1 for legacy behavior)
     * @param triggerId the trigger that initiated this execution (for DAG-scoped context in shared DAGs), or null
     * @return true if any node failed during execution
     */
    private boolean executeReadySteps(WorkflowExecution execution, Set<String> readySteps, String runId, int epoch,
                                      String triggerId, WorkflowPlan plan) {
        if (readySteps == null || readySteps.isEmpty()) {
            return false;
        }

        if (v2StepByStepService == null) {
            logger.warn("[ReusableTrigger] Cannot execute ready steps: V2 service not available");
            return false;
        }

        // Filter out trigger nodes - they must be explicitly fired, not auto-executed
        Set<String> filteredReady = filterReadyNodesForTrigger(filterOutTriggers(readySteps), plan, triggerId);
        if (filteredReady.isEmpty()) {
            logger.info("[ReusableTrigger] No non-trigger steps to execute for runId={}", runId);
            return false;
        }

        logger.info("[ReusableTrigger] Starting automatic execution for runId={} with {} ready steps (filtered from {})",
            runId, filteredReady.size(), readySteps.size());

        boolean hasFailures = false;
        boolean awaitingSignal = false;
        Set<String> currentReady = filteredReady;
        int maxIterations = 100; // Safety limit
        int iteration = 0;

        // Build the LoadedExecution ONCE - the tree and plan are immutable for the run's
        // lifetime, and executeNodeInternal reads fresh state from StateSnapshotService,
        // not from this object. Without this, each executeNode call triggers a full
        // reconstructStateForApi (DB roundtrip + storage blob loads) - 50+ calls per epoch.
        com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution preloadedExec = null;
        try {
            preloadedExec = executionCacheManager != null
                ? executionCacheManager.loadTreeAndExecution(runId) : null;
        } catch (Exception e) {
            logger.warn("[ReusableTrigger] Failed to preload execution for runId={}, falling back to per-node load: {}",
                runId, e.getMessage());
        }
        final var preloaded = preloadedExec;

        while (!currentReady.isEmpty() && iteration < maxIterations && !awaitingSignal) {
            iteration++;

            // Separate split-pending nodes from normal nodes
            List<String> splitNodes = new ArrayList<>();
            List<String> normalNodes = new ArrayList<>();
            for (String stepId : currentReady) {
                if (v2StepByStepScheduler != null) {
                    Set<String> pendingItemIds = v2StepByStepScheduler.getPendingItemIdsForNode(runId, stepId);
                    if (!pendingItemIds.isEmpty()) {
                        splitNodes.add(stepId);
                        continue;
                    }
                }
                normalNodes.add(stepId);
            }

            // Execute split nodes sequentially (they manage their own parallelism internally)
            for (String stepId : splitNodes) {
                try {
                    Set<String> pendingItemIds = v2StepByStepScheduler.getPendingItemIdsForNode(runId, stepId);
                    logger.info("[ReusableTrigger] Found {} pending Split items for step {}: {}",
                        pendingItemIds.size(), stepId, pendingItemIds);
                    V2StepByStepService.SplitExecutionResult splitResult =
                        v2StepByStepService.executeSplitItems(runId, stepId, pendingItemIds);
                    if (!splitResult.allSuccess()) {
                        hasFailures = true;
                    }
                } catch (Exception e) {
                    logger.error("[ReusableTrigger] Error executing split step {}: {}", stepId, e.getMessage(), e);
                    hasFailures = true;
                }
            }

            // Execute normal nodes: parallel when multiple, sequential when single
            if (parallelReadyExecutionEnabled && normalNodes.size() > 1) {
                // PARALLEL EXECUTION - fork branches and other independent ready nodes
                logger.info("[ReusableTrigger] ⚡ Parallel execution: {} nodes for runId={}, epoch={}: {}",
                    normalNodes.size(), runId, epoch, normalNodes);

                AtomicBoolean parallelHasFailures = new AtomicBoolean(false);
                AtomicBoolean parallelAwaitingSignal = new AtomicBoolean(false);

                // HOTFIX-2 (2026-05-20) - capture orgId once outside the loop so each
                // ForkJoinPool worker re-binds it on entry. The default common pool
                // strips request ThreadLocals; downstream V2StepByStepService persists
                // OrgScopedEntity rows (storage.storage, workflow_step_data) and would
                // trip V261 NOT NULL otherwise (cascades the TX abort to other inserts).
                final String orgIdForWorker = runRepository.findByRunIdPublic(runId)
                        .map(WorkflowRunEntity::getOrganizationId)
                        .orElse(null);

                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (String stepId : normalNodes) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () -> {
                        try {
                            StepByStepExecutionResult result;
                            if (epoch >= 0 && preloaded != null) {
                                result = v2StepByStepService.executeNode(runId, stepId, "0", epoch, triggerId, preloaded);
                            } else if (epoch >= 0) {
                                result = v2StepByStepService.executeNode(runId, stepId, "0", epoch, triggerId);
                            } else {
                                result = v2StepByStepService.executeNode(runId, stepId, "0");
                            }

                            // Pending = the node is still in flight (AWAITING_SIGNAL, RUNNING, COLLECTING,
                            // WAITING_TRIGGER). The engine has handed off execution elsewhere (signal
                            // resolver, async worker queue, child trigger) and will resume it through a
                            // different path - the auto-execution loop must pause. Hard-failure detection
                            // MUST gate on isTerminal() first, otherwise an async yield (success=false,
                            // status=AWAITING_SIGNAL) is mis-treated as a failed step, triggering a
                            // spurious trigger refire loop that re-dispatches every in-flight agent.
                            if (result.isPending()) {
                                logger.info("[ReusableTrigger] Node {} is pending ({}), pausing auto-execution for runId={}",
                                    stepId, result.nodeResult() != null ? result.nodeResult().status() : "unknown", runId);
                                parallelAwaitingSignal.set(true);
                            } else if (result.isFailed()) {
                                // Only real FAILED counts as a failure. SKIPPED (branching-route)
                                // is legitimate data-flow control and MUST NOT mark the cycle as
                                // failed. See V2StepByStepService.executeSplitItems for the same
                                // semantic shift + rationale (n8n / Airflow / Temporal alignment).
                                logger.warn("[ReusableTrigger] Step {} failed: {}", stepId, result.getErrorMessage());
                                parallelHasFailures.set(true);
                            }
                        } catch (Exception e) {
                            logger.error("[ReusableTrigger] Error executing step {} in parallel: {}", stepId, e.getMessage(), e);
                            parallelHasFailures.set(true);
                        }
                    }), ForkJoinPool.commonPool());
                    futures.add(future);
                }

                // Wait for all parallel branches to complete
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(30, TimeUnit.MINUTES);
                } catch (Exception e) {
                    logger.error("[ReusableTrigger] Error waiting for parallel execution: runId={}, error={}", runId, e.getMessage(), e);
                    futures.forEach(f -> f.cancel(true));
                    hasFailures = true;
                }

                if (parallelHasFailures.get()) hasFailures = true;
                if (parallelAwaitingSignal.get()) awaitingSignal = true;

                logger.info("[ReusableTrigger] ⚡ Parallel execution completed: runId={}, failures={}, awaitingSignal={}",
                    runId, parallelHasFailures.get(), parallelAwaitingSignal.get());

            } else if (!normalNodes.isEmpty()) {
                // SEQUENTIAL EXECUTION - single node, no overhead
                for (String stepId : normalNodes) {
                    try {
                        StepByStepExecutionResult result;
                        if (epoch >= 0 && preloaded != null) {
                            result = v2StepByStepService.executeNode(runId, stepId, "0", epoch, triggerId, preloaded);
                        } else if (epoch >= 0) {
                            result = v2StepByStepService.executeNode(runId, stepId, "0", epoch, triggerId);
                        } else {
                            result = v2StepByStepService.executeNode(runId, stepId, "0");
                        }

                        // See parallel branch above for rationale: gate hard-failure detection on
                        // isFailed() (not !isSuccess()), so SKIPPED branching-routes and async-
                        // in-flight yields don't trip the failure gate.
                        if (result.isPending()) {
                            logger.info("[ReusableTrigger] Node {} is pending ({}), pausing auto-execution for runId={}",
                                stepId, result.nodeResult() != null ? result.nodeResult().status() : "unknown", runId);
                            awaitingSignal = true;
                            break;
                        } else if (result.isFailed()) {
                            logger.warn("[ReusableTrigger] Step {} failed: {}", stepId, result.getErrorMessage());
                            hasFailures = true;
                        }
                    } catch (Exception e) {
                        logger.error("[ReusableTrigger] Error executing step {}: {}", stepId, e.getMessage(), e);
                        hasFailures = true;
                    }
                }
            }

            // Get next ready steps from root context and filter out triggers
            currentReady = epoch >= 0
                ? v2StepByStepService.getReadyNodes(runId, "0", epoch, preloaded)
                : v2StepByStepService.getReadyNodes(runId, "0");
            if (currentReady == null) {
                currentReady = new java.util.HashSet<>();
            } else {
                currentReady = new java.util.HashSet<>(
                    filterReadyNodesForTrigger(filterOutTriggers(currentReady), plan, triggerId));
            }

            // Also check for pending Split items that need execution.
            // After a Split node executes, body nodes are registered as pending in the
            // scheduler with sub-context itemIds (e.g., "0.0", "0.1") and won't appear
            // in getReadyNodes(runId, "0") which only checks root context.
            if (currentReady.isEmpty() && v2StepByStepScheduler != null) {
                Set<String> pendingNodeIds = v2StepByStepScheduler.getPendingNodeIds(runId);
                if (!pendingNodeIds.isEmpty()) {
                    // Filter out triggers from pending nodes
                    Set<String> filteredPending = filterReadyNodesForTrigger(
                        filterOutTriggers(pendingNodeIds), plan, triggerId);
                    if (!filteredPending.isEmpty()) {
                        logger.info("[ReusableTrigger] Found {} pending Split nodes to execute: {}",
                            filteredPending.size(), filteredPending);
                        currentReady.addAll(filteredPending);
                    }
                }
            }
        }

        if (iteration >= maxIterations) {
            logger.warn("[ReusableTrigger] Reached max iterations ({}) for runId={}", maxIterations, runId);
        }

        logger.info("[ReusableTrigger] Automatic execution completed after {} iterations for runId={}, hasFailures={}",
            iteration, runId, hasFailures);
        return hasFailures;
    }

    /**
     * Reset run for next trigger cycle (global reset, backward compatible).
     *
     * Extracted from WorkflowResumeService.resetForNextWebhook().
     *
     * @param run The workflow run entity
     * @param execution The workflow execution context
     * @param plan The workflow plan
     * @param runId The public run ID
     * @param triggerType The trigger type (used to determine if streaming event should be sent)
     * @return The new epoch number
     */
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public int resetForNextCycle(WorkflowRunEntity run, WorkflowExecution execution,
                                  WorkflowPlan plan, String runId, TriggerType triggerType) {
        return self.resetForNextCycle(run, execution, plan, runId, triggerType, null, false, -1);
    }

    /**
     * Reset run for next trigger cycle with DAG-scoped operation.
     *
     * W4 fix: Acquires PESSIMISTIC_WRITE on the run entity to prevent concurrent
     * double-reset when two signals resolve simultaneously.
     *
     * W10 fix: If triggerId is null, uses run-wide signal check as fallback.
     *
     * For multi-DAG workflows, only the specified DAG's state is reset.
     * For single-DAG workflows (triggerId null), resets all state.
     *
     * @param run The workflow run entity
     * @param execution The workflow execution context
     * @param plan The workflow plan
     * @param runId The public run ID
     * @param triggerType The trigger type (used to determine if streaming event should be sent)
     * @param triggerId The trigger ID for DAG-scoped reset (null for global reset)
     * @return The new epoch number
     */
    @Transactional
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public int resetForNextCycle(WorkflowRunEntity run, WorkflowExecution execution,
                                  WorkflowPlan plan, String runId, TriggerType triggerType,
                                  String triggerId, boolean hasFailures, int explicitEpoch) {
        logger.info("[ReusableTrigger] Resetting run {} for next cycle, triggerId={}", runId, triggerId);

        // Plan v4 §1.6 - advisory lock serializes against concurrent CAS writers
        // BEFORE acquiring the pessimistic row lock below. Both layers stack
        // safely (advisory is transactional, row lock is row-level).
        if (advisoryLockHelper != null) advisoryLockHelper.acquireForRun(runId);

        // W4 fix: Acquire PESSIMISTIC_WRITE to prevent double-reset race
        WorkflowRunEntity lockedRun = runRepository.findByRunIdPublicForUpdate(runId)
            .orElse(run); // Fallback to provided run if lock fails

        // W4: Re-check signals under lock (another thread may have reset already)
        if (lockedRun.getStatus() == RunStatus.WAITING_TRIGGER) {
            int alreadyResetEpoch = explicitEpoch >= 0
                ? explicitEpoch
                : epochManager.getCurrentEpoch(lockedRun, triggerId);
            logger.info("[ReusableTrigger] Run {} already reset to WAITING_TRIGGER by another thread; returning epoch {}",
                runId, alreadyResetEpoch);
            return alreadyResetEpoch;
        }

        // Hard-stop guard: if the run was cancelled/failed/completed between the initial
        // check and this lock acquisition, do NOT overwrite the terminal status.
        // Without this, a concurrent cancelWorkflow() sets CANCELLED, then this method
        // overwrites it with WAITING_TRIGGER - silently un-cancelling the run.
        if (lockedRun.getStatus().isTerminal()) {
            logger.info("[ReusableTrigger] Run {} is in terminal status {} - skipping resetForNextCycle",
                runId, lockedRun.getStatus());
            return epochManager.getCurrentEpoch(lockedRun, triggerId);
        }

        // Resolve epoch BEFORE signal check so we can do epoch-scoped check.
        // When explicitEpoch is provided (e.g., from signal resolution), use it directly.
        // Otherwise fallback to getCurrentEpoch (works for single-epoch sequential flow).
        int currentEpoch = (explicitEpoch >= 0) ? explicitEpoch
            : (triggerId != null
                ? epochManager.getCurrentEpoch(lockedRun, triggerId)
                : epochManager.getCurrentEpoch(lockedRun));

        // Check for active signals OR in-flight async agents under the transactional lock
        // (epoch-scoped for parallel epochs). hasActiveSignalsForTrigger checks both
        // SignalWait.blocking=true rows AND the PendingAgentRegistry.
        if (hasActiveSignalsForTrigger(runId, triggerId, currentEpoch)) {
            logger.info("[ReusableTrigger] Signals or async agents still pending under lock for epoch {}, skip reset: runId={}, triggerId={}",
                currentEpoch, runId, triggerId);
            return currentEpoch;
        }

        // Cancel active blocking signals (cleanup) - preserves INTERFACE_SIGNAL across trigger cycles
        if (unifiedSignalService != null) {
            if (triggerId != null) {
                unifiedSignalService.cancelBlockingByDagAndEpoch(runId, triggerId, currentEpoch);
            } else {
                unifiedSignalService.cancelByRun(runId);
            }
        }

        // Release concurrency slot for this epoch
        if (triggerId != null) {
            epochConcurrencyLimiter.release(runId, triggerId);
        }

        // Close the epoch in StateSnapshot (removes from activeEpochs but keeps history)
        // Uses currentEpoch which respects explicitEpoch for parallel epoch correctness
        if (triggerId != null) {
            stateSnapshotService.closeEpoch(runId, triggerId, currentEpoch);
        }

        // Clear in-memory caches for this epoch to prevent stale contexts
        // from leaking into the next trigger fire
        if (splitContextManager != null) {
            splitContextManager.clearEpoch(runId, currentEpoch);
        }
        if (itemMergeCollector != null) {
            itemMergeCollector.clearCompletedEpochMergeStates(runId);
        }

        // 4. Determine run status based on active epochs
        // Only transition to WAITING_TRIGGER if NO epochs are active across all triggers
        // Use StateSnapshot as source of truth (not semaphore-based limiter)
        RunStatus cycleResult = hasFailures ? RunStatus.FAILED : RunStatus.COMPLETED;
        boolean anyActive = stateSnapshotService.hasAnyActiveEpoch(runId);
        RunStatus newStatus = anyActive ? RunStatus.RUNNING : RunStatus.WAITING_TRIGGER;

        lockedRun.setStatus(newStatus);
        if (!anyActive) {
            lockedRun.setEndedAt(null); // Clear endedAt only when truly waiting
        }
        lockedRun.setUpdatedAt(Instant.now());

        Map<String, Object> metadata = lockedRun.getMetadata() != null
            ? new java.util.HashMap<>(lockedRun.getMetadata()) : new java.util.HashMap<>();
        metadata.put("lastCycleResult", cycleResult.getValue());
        metadata.put("lastCycleEpoch", currentEpoch);
        metadata.put("lastCycleAt", Instant.now().toString());
        lockedRun.setMetadata(metadata);

        runRepository.save(lockedRun);

        // P0 (notification V2): emit per-epoch failure event so the bell can
        // surface reusable-trigger workflow failures. The terminal-event path
        // (WorkflowRunTerminatedEvent) does NOT fire here because the run
        // resets to WAITING_TRIGGER for the next cycle - without this hook,
        // schedule/webhook/chat/form failures are silent in the bell.
        // Idempotency: NotificationEmitter dedups on (tenant, category,
        // source_id) with source_id = runId + ":" + epoch, so re-emission
        // during recovery is safe. Optional eventPublisher keeps unit tests
        // that don't bootstrap Spring runnable.
        if (hasFailures && eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new WorkflowEpochFailedEvent(
                        lockedRun.getId(),
                        lockedRun.getWorkflow() != null ? lockedRun.getWorkflow().getId() : null,
                        currentEpoch,
                        lockedRun.getPlanVersion(),
                        lockedRun.getTenantId(),
                        lockedRun.getRunIdPublic(),
                        Instant.now()));
            } catch (Exception ex) {
                // Publish itself is in-process; AFTER_COMMIT listener errors
                // are swallowed by Spring downstream. We log here only if the
                // publisher rejected the event (e.g. context shutdown).
                logger.warn("[ReusableTrigger] Failed to publish epoch-failed event for runId={} epoch={}: {}",
                        runId, currentEpoch, ex.getMessage());
            }
        }

        // Cycle-end cache cleanup - only when the run is fully idle after this
        // epoch close (no other epochs running across all triggers), so we don't
        // purge state in-flight for parallel epochs. Excludes STREAMING so the
        // WsEventSequencer monotonic seq counter and SnapshotService's active-run
        // cache survive across fires (purging them mid-run was the original
        // 2026-05-05 incident - deferred publishes from fire #N collide with
        // fire #N+1 seqs → frontend strict-< drop → UI freeze).
        // Without this, reusable triggers leak ~5 of the 9 per-run caches
        // (RunStateStore, NodeEventStore, RunningNodeTracker, SplitCoalesceTracker,
        // PendingAgentRegistry split barriers …) progressively until OOM -
        // observed on triggers/unified-redesign with run_<id>.
        if (!anyActive && resumeService != null) {
            try {
                resumeService.clearCachedStateForRerun(runId,
                    java.util.Set.of(RunScopedCache.CacheDomain.STREAMING));
            } catch (Exception cleanupEx) {
                logger.warn("[ReusableTrigger] cycle-end cache cleanup failed runId={}: {}",
                    runId, cleanupEx.getMessage());
            }
        }

        // 5. Send streaming event BEFORE closing epoch (all trigger types, including webhook).
        streamingService.sendWorkflowStatusEvent(execution, newStatus,
            "Cycle " + cycleResult.getValue().toLowerCase() + " (epoch " + currentEpoch + "), " +
            (anyActive ? "other epochs still running" : "ready for next trigger"));

        // 6. Reset state and mark trigger as READY for next execution.
        // Use DAG-scoped reset for multi-DAG workflows.
        // This closes the RunContext (including streaming emitter) -- must be AFTER streaming send.
        //
        // IMPORTANT: Only create the "next" epoch when NO other epochs are active.
        // During parallel epoch execution, intermediate deferred resets must NOT create
        // phantom epochs - otherwise activeEpochs accumulates never-executed epochs that
        // prevent the run from transitioning to WAITING_TRIGGER when all real epochs complete.
        // The LAST epoch's reset (when anyActive=false) will create the next epoch cleanly.
        if (!anyActive) {
            if (triggerId != null) {
                epochManager.resetDagWithRerunPattern(runId, plan, triggerId);
            } else {
                String firstTriggerId = getFirstTriggerId(plan);
                if (firstTriggerId != null) {
                    epochManager.resetWithRerunPattern(runId, plan, firstTriggerId);
                }
            }
        }

        logger.info("[ReusableTrigger] Run {} cycle {} (epoch {}), status={}, triggerId={}, otherEpochsActive={}",
            runId, cycleResult, currentEpoch, newStatus, triggerId, anyActive);
        return currentEpoch;
    }

    /**
     * Load datasource data for a datasource trigger.
     *
     * Reuses TriggerResolverService to load data from datasource table.
     * This ensures consistency with legacy datasource trigger behavior while
     * enabling reusable trigger pattern (epochs, accumulation).
     *
     * @param run The workflow run
     * @param plan The workflow plan
     * @param triggerId The trigger node ID (e.g., "trigger:my_datasource")
     * @param triggerConfig Optional configuration (filters, etc.)
     * @return Payload with datasource data
     */
    private Map<String, Object> loadDatasourceData(
            WorkflowRunEntity run,
            WorkflowPlan plan,
            String triggerId,
            Map<String, Object> triggerConfig
    ) {
        // Find trigger in plan
        com.apimarketplace.orchestrator.domain.workflow.Trigger trigger = null;
        for (com.apimarketplace.orchestrator.domain.workflow.Trigger t : plan.getTriggers()) {
            if (triggerId.equals(t.getNormalizedKey())) {
                trigger = t;
                break;
            }
        }

        if (trigger == null) {
            logger.error("[ReusableTrigger] Trigger not found in plan: {}", triggerId);
            return java.util.Collections.emptyMap();
        }

        if (!"datasource".equals(trigger.type())) {
            logger.error("[ReusableTrigger] Trigger {} is not a datasource trigger: type={}",
                triggerId, trigger.type());
            return java.util.Collections.emptyMap();
        }

        // Reuse TriggerResolverService to load datasource data
        // This maintains consistency with existing datasource loading logic
        String tenantId = run.getTenantId();
        Map<String, Object> resolvedInputs = triggerConfig != null ? triggerConfig : java.util.Collections.emptyMap();

        try {
            Map<String, Object> datasourcePayload = triggerResolverService.resolveTrigger(
                trigger, tenantId, resolvedInputs
            );
            logger.info("[ReusableTrigger] Loaded datasource data for triggerId={}: {} items",
                triggerId, datasourcePayload.get("count"));
            return datasourcePayload;
        } catch (Exception e) {
            logger.error("[ReusableTrigger] Error loading datasource data for triggerId={}: {}",
                triggerId, e.getMessage(), e);
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * Filter out trigger nodes from a set of ready steps.
     *
     * Triggers must be explicitly fired by user action (clicking trigger button).
     * They should NOT be auto-executed during automatic execution of ready steps.
     * This is critical for multi-DAG workflows where each trigger controls its own DAG.
     *
     * @param readySteps The set of ready step IDs
     * @return A new set with trigger nodes removed
     */
    private Set<String> filterOutTriggers(Set<String> readySteps) {
        if (readySteps == null || readySteps.isEmpty()) {
            return Set.of();
        }

        Set<String> filtered = new java.util.HashSet<>();
        for (String stepId : readySteps) {
            if (!stepId.startsWith("trigger:")) {
                filtered.add(stepId);
            } else {
                logger.debug("[ReusableTrigger] Filtered out trigger from auto-execution: {}", stepId);
            }
        }
        return filtered;
    }

    private Set<String> filterReadyNodesForTrigger(Set<String> readyNodes, WorkflowPlan plan, String triggerId) {
        if (readyNodes == null || readyNodes.isEmpty()
                || triggerId == null || triggerId.isBlank()
                || plan == null || plan.getTriggers().size() <= 1
                || dagIndependenceValidator == null) {
            return readyNodes != null ? readyNodes : Set.of();
        }

        Set<String> scoped = new java.util.LinkedHashSet<>();
        Set<String> filteredOut = new java.util.LinkedHashSet<>();
        for (String nodeId : readyNodes) {
            if (nodeId == null) {
                continue;
            }
            List<String> owners = dagIndependenceValidator.findAllOwnerTriggers(plan, nodeId);
            if (owners.isEmpty() || owners.contains(triggerId)) {
                scoped.add(nodeId);
            } else {
                filteredOut.add(nodeId);
            }
        }

        if (!filteredOut.isEmpty()) {
            logger.info("[ReusableTrigger] Filtered {} foreign ready node(s) for triggerId={}: {}",
                filteredOut.size(), triggerId, filteredOut);
        }
        return scoped;
    }

    /**
     * Check if there is ANY reason to keep this DAG epoch open: a blocking signal
     * (WaitNode, USER_APPROVAL, INTERFACE_SIGNAL with {@code __continue}, …) or an
     * async agent still in flight (scaling.agent.queue.enabled - the producer yields
     * {@code asyncRunning} and the result comes back asynchronously via the Redis
     * worker pool).
     *
     * <p>The async-agent half of this check is the replacement for the old
     * {@code SignalWait.blocking=true} gate that AGENT_EXECUTION signals used to set
     * before the subsystem was moved out of the signal system. Without it, a guardrail
     * split yields, {@code resetForNextCycle} sees no blocking signals, closes the
     * epoch, and the {@code classify} successor on arrival finds an empty ready-node
     * set because its epoch is no longer active.</p>
     *
     * <p>Interface signals block the cycle reset - the workflow stays RUNNING while
     * the user interacts with the interface page. {@code __continue} resolves the
     * signal and advances execution.</p>
     *
     * <p>W10 fix: If triggerId is null, uses run-wide check as fallback.</p>
     *
     * @param runId The workflow run ID
     * @param triggerId The trigger ID (null for run-wide check)
     * @param epoch The epoch to check (-1 for legacy DAG-wide check)
     * @return true if there are any blocking signals or in-flight async agents
     */
    private boolean hasActiveSignalsForTrigger(String runId, String triggerId, int epoch) {
        // (1) Blocking-signal check (legacy SignalWait table).
        if (unifiedSignalService != null) {
            boolean signals;
            if (triggerId != null && epoch >= 0) {
                signals = unifiedSignalService.hasBlockingSignalsForDagAndEpoch(runId, triggerId, epoch);
            } else if (triggerId != null) {
                signals = unifiedSignalService.hasBlockingSignalsForDag(runId, triggerId);
            } else {
                signals = unifiedSignalService.hasBlockingSignals(runId);
            }
            if (signals) {
                return true;
            }
            if (triggerId != null && epoch >= 0
                    && unifiedSignalService.hasPendingSignalResumesForDagAndEpoch(runId, triggerId, epoch)) {
                logger.info("[ReusableTrigger] Signal resume still finalizing for epoch {}, skip reset: runId={}, triggerId={}",
                    epoch, runId, triggerId);
                return true;
            }
        }
        // (2) Async agent check.
        if (pendingAgentRegistry != null) {
            // Epoch-scoped path: precise check when both (triggerId, epoch) are provided.
            // Walks the local ConcurrentHashMap then falls back to Redis (cross-replica)
            // - see PendingAgentRegistry.hasPendingFor.
            if (triggerId != null && epoch >= 0) {
                return pendingAgentRegistry.hasPendingFor(runId, triggerId, epoch);
            }
            // Run-wide fallback (used by the 4-arg resetForNextCycle overload that
            // passes triggerId=null). Without this, the legacy code path silently
            // skipped the async-pending check and could close epochs while async
            // agents were still in flight on another replica.
            return pendingAgentRegistry.hasAnyPendingForRun(runId);
        }
        return false;
    }

    /**
     * Get the first trigger ID from a workflow plan.
     */
    private boolean isEditorRun(WorkflowRunEntity run) {
        Map<String, Object> metadata = run.getMetadata();
        return metadata != null && Boolean.TRUE.equals(metadata.get("__editorRun__"));
    }

    /**
     * True when the run was created by {@code EditorRunResolver.findOrCreateRunForVersion}
     * to replay a specific historical plan version ({@code __versionReplay__} metadata).
     * Such runs must never be refreshed to the live workflow plan.
     */
    private boolean isVersionReplay(WorkflowRunEntity run) {
        Map<String, Object> metadata = run.getMetadata();
        return metadata != null && metadata.get("__versionReplay__") != null;
    }

    /**
     * Resolve the version number whose stored plan content matches {@code planMap}.
     *
     * <p>Every save path creates a version, so in the common case this is a
     * read-only compare returning the current max. If {@code workflow.plan}
     * drifted from the latest version's stored content (write path that slipped
     * past versioning, FE/BE serialization differences), the latest version's
     * content is overwritten IN PLACE - trigger fires never mint a new version
     * number, so re-fires of the same run keep a stable version across epochs.
     * Only reached on the unpinned lane (the pinned branch resolves versions
     * upstream), so the pinned row is never at risk here; the service still
     * guards it defensively. Falls back to the legacy max-version stamp when
     * the service bean is absent (narrow test wiring).
     */
    private Integer resolveVersionForPlan(UUID workflowId, Map<String, Object> planMap, String tenantId) {
        if (planVersionService != null) {
            try {
                // REQUIRES_NEW so a versioning failure degrades to the max-version
                // fallback below instead of poisoning any caller-owned transaction.
                return planVersionService.resolveContentVersionForExecutionInNewTransaction(workflowId, planMap, tenantId);
            } catch (Exception e) {
                logger.warn("[PlanResolution] Content-true version resolution failed for workflow {}: {} - falling back to max version",
                        workflowId, e.getMessage());
            }
        }
        return planVersionRepository.getMaxVersion(workflowId).orElse(null);
    }

    private String getFirstTriggerId(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null || plan.getTriggers().isEmpty()) {
            return null;
        }
        return plan.getTriggers().get(0).getNormalizedKey();
    }

    /**
     * Send a snapshot after resetForNextCycle to ensure the frontend gets the final
     * accumulated state. Without this, the frontend only has the last node-completion
     * snapshot (before epoch close), missing updated readySteps and final status.
     *
     * <p>Called AFTER the @Transactional resetForNextCycle() returns (transaction committed
     * via self-proxy), so the snapshot reads committed state from DB.
     */
    private void sendPostResetSnapshot(String runId) {
        if (snapshotService == null) {
            return;
        }
        try {
            snapshotService.sendSnapshotImmediate(runId);
        } catch (Exception e) {
            logger.warn("[ReusableTrigger] Post-reset snapshot failed for runId={}: {}", runId, e.getMessage());
        }
    }

    // ========================================================================
    // SBS EPOCH COMPLETION DETECTION
    // ========================================================================

    /**
     * Check if a specific epoch has completed in SBS mode and close it if so.
     *
     * <p><b>Wiring status (audit A 2026-05-09):</b> this method currently has
     * <i>no production caller</i> - grep across {@code src/main} returns only
     * the definition itself plus a doc reference in
     * {@link com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService}.
     * The presumed historical caller (per-step hook in V2StepByStepService) was
     * removed during a refactor and never re-wired. The method is kept because
     * its tests pin the SBS auto-close contract, and the side-effects discipline
     * below remains correct for whenever the wiring is restored. <b>Verify with
     * the maintainer before extending this code path</b>.
     *
     * <p>In AUTO mode, epoch closing is handled by {@link #resetForNextCycle} after
     * all ready steps execute. In SBS mode, steps execute one-at-a-time via user clicks,
     * so we need to detect epoch completion after each step execution.
     *
     * <p>An epoch is "complete" when:
     * <ul>
     *   <li>No non-trigger nodes are in the ready set (trigger nodes are excluded
     *       because {@code prepareNextEpochReady()} keeps the trigger ready for the next fire)</li>
     *   <li>No nodes are running</li>
     *   <li>No nodes are awaiting signals</li>
     *   <li>The epoch is still in activeEpochs (not already closed)</li>
     * </ul>
     *
     * <p>When an epoch closes:
     * <ol>
     *   <li>Close the epoch in StateSnapshot (removes from activeEpochs, keeps history)</li>
     *   <li>Release the concurrency slot</li>
     *   <li>Determine new run status: PAUSED if other epochs active, WAITING_TRIGGER if none</li>
     *   <li>Send a snapshot so the frontend picks up the updated state</li>
     * </ol>
     *
     * @param runId     The workflow run ID
     * @param triggerId The trigger node ID (DAG key)
     * @param epoch     The epoch to check
     */
    @Transactional
    public void closeEpochIfCompleteForSbs(String runId, String triggerId, int epoch) {
        if (runId == null || triggerId == null || epoch < 0) {
            return;
        }

        try {
            StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
            var dagState = snapshot.getDags().get(triggerId);
            if (dagState == null) {
                return;
            }

            // Skip if epoch is not active (already closed)
            if (!dagState.getActiveEpochs().contains(epoch)) {
                return;
            }

            var epochState = dagState.getEpochState(epoch);
            if (epochState == null) {
                return;
            }

            // Check if epoch still has work to do.
            //
            // P2.1.4 - gate path: today the JSONB EpochState still carries
            // runningNodeIds, so this primary check fires correctly. Once the
            // P2.3 elide flag flips, runningNodeIds is no longer persisted to
            // JSONB and this set will always be empty for new writes.
            if (!epochState.getRunningNodeIds().isEmpty()) {
                return;
            }
            // Secondary fail-CLOSED Redis gate (P2.1.4): once JSONB is elided,
            // Redis is the only authoritative source for "is this epoch still
            // running?". If Redis is unreachable, DEFER the close (treat as "may
            // still have running" - fail-closed). Without this, a Redis hiccup
            // would let us close prematurely while a node is mid-execution.
            //
            // Note: this is a no-op until P2.3 because the JSONB check above
            // would have already returned when running was non-empty. Once the
            // elide flag is on, JSONB is always empty here and Redis becomes
            // the gate. {@code runningNodeTracker} is optional-injected so unit
            // tests without a Redis-backed tracker continue to pass - the gate
            // simply skips the Redis check in that scenario.
            if (runningNodeTracker != null) {
                try {
                    Map<String, Integer> redisRunning =
                            runningNodeTracker.getRunningCountsOrThrow(runId, epoch);
                    if (!redisRunning.isEmpty()) {
                        return;
                    }
                } catch (RedisUnavailableException ex) {
                    logger.warn("[ReusableTrigger] Redis unavailable during epoch close gate - deferring " +
                            "(runId={}, triggerId={}, epoch={}): {}", runId, triggerId, epoch, ex.getMessage());
                    return;  // fail-closed: defer reset, retry next cycle
                }
            }
            if (!epochState.getAwaitingSignalNodeIds().isEmpty()) {
                return;
            }

            // Filter out trigger nodes from ready set - trigger being ready (from
            // prepareNextEpochReady) should not prevent epoch close
            Set<String> nonTriggerReady = new java.util.HashSet<>(epochState.getReadyNodeIds());
            nonTriggerReady.removeIf(nodeId -> nodeId.startsWith("trigger:"));
            if (!nonTriggerReady.isEmpty()) {
                return;
            }

            // Epoch is complete - close it
            logger.info("[ReusableTrigger] SBS epoch complete: runId={}, triggerId={}, epoch={}, " +
                    "completed={}, failed={}, skipped={}",
                    runId, triggerId, epoch,
                    epochState.getCompletedNodeIds().size(),
                    epochState.getFailedNodeIds().size(),
                    epochState.getSkippedNodeIds().size());

            // 0. Cancel any blocking signals that were never resolved in this epoch.
            // In-tx by design: must rollback together with closeEpoch (atomic
            // pair) - a partial commit that pruned the epoch but kept the
            // signals would let WAIT_TIMER fire later against a closed epoch
            // (the very zombie loop we are guarding against). Contrast with the
            // 3 deferred side-effects below: those do NOT participate in the
            // atomicity invariant - they merely react to commit visibility.
            //
            // Without this cancellation, a zombie WAIT_TIMER PENDING in a closed
            // epoch would later be resolved by either RedisTimer or
            // pollExpiredTimers, triggering re-execution of the wait node in a
            // pruned epoch (infinite loop).
            if (unifiedSignalService != null) {
                try {
                    unifiedSignalService.cancelBlockingByDagAndEpoch(runId, triggerId, epoch);
                } catch (Exception ex) {
                    logger.warn("[ReusableTrigger] SBS epoch close: signal cancel failed runId={} triggerId={} epoch={}: {}",
                            runId, triggerId, epoch, ex.getMessage());
                }
            }

            // 1. Close epoch in StateSnapshot (DB write - in-tx)
            stateSnapshotService.closeEpoch(runId, triggerId, epoch);

            // 2. Determine run status
            boolean anyActive = stateSnapshotService.hasAnyActiveEpoch(runId);
            RunStatus newStatus = anyActive ? RunStatus.PAUSED : RunStatus.WAITING_TRIGGER;

            // 3. Update run entity under lock (DB write - in-tx)
            runRepository.findByRunIdPublicForUpdate(runId).ifPresent(run -> {
                run.setStatus(newStatus);
                if (!anyActive) {
                    run.setEndedAt(null);
                }
                run.setUpdatedAt(Instant.now());
                runRepository.save(run);
            });

            logger.info("[ReusableTrigger] SBS epoch {} closed: runId={}, newStatus={}, otherEpochsActive={}",
                    epoch, runId, newStatus, anyActive);

            // 4. Side-effects deferred to AFTER commit (race-safety with peer instances).
            //
            // Why AFTER commit:
            //  • {@code epochConcurrencyLimiter.release} frees the trigger slot. Releasing
            //    pre-commit lets a peer instance acquire and fire a new epoch BEFORE the
            //    epoch-close DB write is visible - the new fire would see the closed epoch
            //    still in {@code activeEpochs} and double-fire.
            //  • {@code clearCachedStateForRerun} invalidates per-run caches. Pre-commit
            //    invalidation re-warms the cache from a stale (uncommitted) DB read.
            //  • {@code sendPostResetSnapshot} pushes WS state to the frontend. Pre-commit
            //    push lets clients observe the closed epoch before MVCC visibility lands.
            //
            // Rollback semantics (audit A 2026-05-09): on rollback, the lambda below NEVER
            // fires - release stays held, caches stay warm, no snapshot is sent. This is
            // the CORRECT behavior: the DB rollback leaves the epoch open in
            // {@code activeEpochs}, so the slot must remain reserved (peer instance would
            // otherwise oversubscribe). Pre-fix, release was in-tx but the in-memory
            // semaphore decrement was NOT rolled back - slot OVER-released on every
            // rollback. The afterCommit deferral cures that bug as a free side-effect.
            // Caller is best-effort + retried per-step, so a rollback releases nothing
            // permanently.
            //
            // The previous implementation called all three in-tx despite the doc on
            // {@link #sendPostResetSnapshot} promising "AFTER the @Transactional resetForNextCycle()
            // returns" - true for the AUTO callsite (self-proxy boundary), but FALSE here in
            // SBS where {@code closeEpochIfCompleteForSbs} is itself the @Transactional method.
            // {@link TransactionalHelper#runAfterCommitOrNow} also handles the no-tx case
            // (unit tests, recovery paths) by running inline.
            final boolean shouldClearCachesPostCommit = !anyActive && resumeService != null;
            TransactionalHelper.runAfterCommitOrNow(() -> {
                try {
                    epochConcurrencyLimiter.release(runId, triggerId);
                } catch (Exception ex) {
                    logger.warn("[ReusableTrigger] SBS post-close release failed runId={} triggerId={}: {}",
                            runId, triggerId, ex.getMessage());
                }
                if (shouldClearCachesPostCommit) {
                    try {
                        resumeService.clearCachedStateForRerun(runId,
                                java.util.Set.of(RunScopedCache.CacheDomain.STREAMING));
                    } catch (Exception cleanupEx) {
                        logger.warn("[ReusableTrigger] SBS cycle-end cache cleanup failed runId={}: {}",
                                runId, cleanupEx.getMessage());
                    }
                }
                sendPostResetSnapshot(runId);
            });

        } catch (Exception e) {
            logger.warn("[ReusableTrigger] SBS epoch completion check failed: runId={}, epoch={}: {}",
                    runId, epoch, e.getMessage());
        }
    }

}

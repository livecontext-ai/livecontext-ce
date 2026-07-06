package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.WaitNode;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.context.ReadinessContextCache;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.services.streaming.StepByStepEventService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.trigger.ErrorTriggerDispatchService;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.orchestrator.utils.ExecutionConstants;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.apimarketplace.orchestrator.cache.RedisCacheKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Resumes workflow execution after a signal resolves.
 *
 * Follows the same pattern as ReusableTriggerService.executeReadySteps():
 * - Get ready nodes from V2StepByStepService
 * - Execute them in a while-loop (AUTO mode)
 * - Or emit streaming ready event (STEP_BY_STEP mode)
 *
 * Triggered via @TransactionalEventListener(AFTER_COMMIT) on SignalResolvedEvent.
 * This ensures DB locks from claim/resolve are released before node execution.
 *
 * Uses @Async("signalResumeExecutor") to prevent blocking the caller thread.
 *
 * @see UnifiedSignalService
 * @see SignalResolvedEvent
 */
@Service
public class SignalResumeService {

    private static final Logger logger = LoggerFactory.getLogger(SignalResumeService.class);

    private static final Duration SIGNAL_DEDUP_TTL = Duration.ofMinutes(5);
    private static final Duration SIGNAL_LOCK_TTL = Duration.ofMinutes(70);
    private static final Duration SIGNAL_LOCK_WARN_INTERVAL = Duration.ofSeconds(30);
    private static final long SIGNAL_LOCK_RETRY_MS = 50;

    private final StringRedisTemplate redis;
    private final WorkflowRunRepository runRepository;
    private final SplitContextManager splitContextManager;
    private final StorageService storageService;
    private final StateSnapshotService stateSnapshotService;
    private final WorkflowStepDataRepository stepDataRepository;
    private final ExecutionCacheManager executionCacheManager;
    private final NodeSearchService nodeSearchService;
    private final RunningNodeTracker runningNodeTracker;
    private final WorkflowEpochService workflowEpochService;

    @Autowired(required = false)
    private V2StepByStepService v2StepByStepService;

    @Autowired(required = false)
    private V2StepByStepContextManager stepByStepContextManager;

    @Autowired(required = false)
    private BackEdgeHandler backEdgeHandler;

    @Autowired(required = false)
    private V2ExecutionEventService eventService;

    /**
     * Plan v4 §10 STALE_OWNERSHIP validation (2026-05-11 wiring) - checks the
     * resolved signal's {@code claimed_generation} against this instance's
     * live generation. Mismatch (i.e. another instance claimed the signal,
     * crashed, restarted with new generation, or we ourselves restarted)
     * means our claim is stale; release with 5s {@code retry_after} cooldown
     * so a peer can re-pick the row.
     *
     * <p>{@code required=false}: when the heartbeat-failover flag is OFF the
     * bean isn't wired (the @ConditionalOnProperty gate); resume falls
     * through to the standard path (no STALE_OWNERSHIP check), which is the
     * pre-#10 semantics - no signal claim, no validation needed.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.heartbeat.InstanceHeartbeatService heartbeatService;

    /**
     * Plan v4 §1.6 - advisory-lock helper. {@code required=false} so narrow
     * Spring tests boot without it; resume falls back to row-lock-only when
     * absent (pre-§1.6 semantics).
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHelper advisoryLockHelper;

    @Autowired(required = false)
    private StepByStepEventService stepByStepEventService;

    @Autowired(required = false)
    private CreditConsumptionClient creditClient;

    @Autowired
    private WorkflowMetrics workflowMetrics;

    @Autowired(required = false)
    private SnapshotService snapshotService;

    @Autowired(required = false)
    private ReadinessContextCache readinessCache;

    /**
     * Async-agent registry. Optional because the bean is only meaningfully populated
     * when {@code scaling.agent.queue.enabled=true}; in queue-disabled deployments the
     * refinalize check below collapses to "blocking signals only" and is correct.
     */
    @Autowired(required = false)
    private PendingAgentRegistry pendingAgentRegistry;

    @Autowired(required = false)
    private ErrorTriggerDispatchService errorTriggerDispatchService;

    // Lazy to avoid circular dependencies
    @Autowired
    @Lazy
    private UnifiedSignalService signalService;

    @Autowired
    @Lazy
    private ReusableTriggerService reusableTriggerService;

    @Autowired
    @Lazy
    private WorkflowResumeService resumeService;

    public SignalResumeService(
            StringRedisTemplate redis,
            WorkflowRunRepository runRepository,
            SplitContextManager splitContextManager,
            StorageService storageService,
            StateSnapshotService stateSnapshotService,
            WorkflowStepDataRepository stepDataRepository,
            ExecutionCacheManager executionCacheManager,
            NodeSearchService nodeSearchService,
            RunningNodeTracker runningNodeTracker,
            WorkflowEpochService workflowEpochService) {
        this.redis = redis;
        this.runRepository = runRepository;
        this.splitContextManager = splitContextManager;
        this.storageService = storageService;
        this.stateSnapshotService = stateSnapshotService;
        this.stepDataRepository = stepDataRepository;
        this.executionCacheManager = executionCacheManager;
        this.nodeSearchService = nodeSearchService;
        this.runningNodeTracker = runningNodeTracker;
        this.workflowEpochService = workflowEpochService;
    }

    /**
     * Handle signal resolved event.
     * Dispatched async on signalResumeExecutor to avoid blocking the scheduler thread.
     * Fires AFTER_COMMIT to ensure DB locks are released.
     */
    @Async("signalResumeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSignalResolved(SignalResolvedEvent event) {
        SignalWaitEntity resolvedSignal = event.getResolvedSignal();
        // Audit 2026-05-17 round-5 - NF1 refined. The round-4 fix blanked
        // the request context to prevent leaking the resolver's identity to
        // downstream work, but that ALSO blanked org context entirely, so
        // post-signal node executions (Agent / MCP / HTTP nodes) lost the
        // run's org. Now: clear the inherited (resolver) context, then
        // restore the RUN's owning user + org so downstream `*-client`
        // forwardOrgHeaders sees the correct workspace.
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        org.slf4j.MDC.clear();
        final String[] orgScope = new String[2];
        try {
            runRepository.findByRunIdPublic(resolvedSignal.getRunId()).ifPresent(run -> {
                Map<String, String> headers = new HashMap<>();
                if (run.getTenantId() != null) headers.put("X-User-ID", run.getTenantId());
                if (run.getOrganizationId() != null) {
                    headers.put("X-Organization-ID", run.getOrganizationId());
                    org.slf4j.MDC.put("org", run.getOrganizationId());
                    orgScope[0] = run.getOrganizationId();
                }
                if (run.getOrganizationRole() != null) {
                    headers.put("X-Organization-Role", run.getOrganizationRole());
                    orgScope[1] = run.getOrganizationRole();
                }
                if (run.getTenantId() != null) {
                    org.slf4j.MDC.put("user", run.getTenantId());
                    org.slf4j.MDC.put("tenant", run.getTenantId());
                }
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                        new SignalResumeRequestAttributes(headers),
                        true);
            });
        } catch (Exception ctxEx) {
            logger.warn("[SignalResume] Failed to restore run context: {}", ctxEx.getMessage());
        }
        try {
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope(
                orgScope[0], orgScope[1], () -> resumeAfterSignal(resolvedSignal));
        } catch (Exception e) {
            logger.error("[SignalResume] Error resuming after signal resolution: signalId={}, runId={}, nodeId={}, error={}",
                resolvedSignal.getId(), resolvedSignal.getRunId(), resolvedSignal.getNodeId(), e.getMessage(), e);
        } finally {
            // Always clean up so a pooled thread doesn't carry over to the next signal.
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            org.slf4j.MDC.clear();
        }
    }

    /**
     * Resume workflow execution after a signal resolves.
     *
     * Follows the same pattern as ReusableTriggerService.executeReadySteps().
     */
    @com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding
    public void resumeAfterSignal(SignalWaitEntity resolvedSignal) {
        // Dedup: if both sync (controller) and async (event listener) paths fire,
        // only the first one processes - even across instances in a horizontal cluster.
        // Redis SETNX returns true only for the first caller; the second gets false.
        // On Redis failure, we proceed (graceful degradation) - at-least-once is safer
        // than losing a signal. The per-run lock below prevents duplicate execution.
        String dedupKey = RedisCacheKeys.signalDedup(resolvedSignal.getRunId(), resolvedSignal.getId());
        try {
            Boolean firstCaller = redis.opsForValue().setIfAbsent(dedupKey, "1", SIGNAL_DEDUP_TTL);
            if (!Boolean.TRUE.equals(firstCaller)) {
                logger.info("[SignalResume] Signal {} already being processed (Redis dedup), skipping duplicate resume",
                    resolvedSignal.getId());
                return;
            }
        } catch (Exception e) {
            logger.warn("[SignalResume] Redis dedup check failed, proceeding (per-run lock provides safety): signalId={}, error={}",
                resolvedSignal.getId(), e.getMessage());
        }

        // Plan v4 §1.6 - acquire advisory lock to serialize against any concurrent
        // CAS writer on the same run. Held until @Transactional commit/rollback.
        if (advisoryLockHelper != null) {
            advisoryLockHelper.acquireForRun(resolvedSignal.getRunId());
        }

        String runId = resolvedSignal.getRunId();
        String nodeId = resolvedSignal.getNodeId();
        String itemId = resolvedSignal.getItemId();

        // Plan v4 §10 STALE_OWNERSHIP validation - if heartbeat-failover is
        // active and this signal was claimed by the failover steal CTE,
        // verify the claim is still valid for THIS instance at THIS generation.
        // claimed_by==null -> not claimed (pre-#10 path), proceed normally.
        // claimed_generation==0 -> local/legacy claim from resolveSignal(), not
        //                           a heartbeat-failover ownership claim.
        // claimed_by!=me → peer owns it, peer will resume; skip (dedup handled
        //                  upstream, this is the cross-cluster duplicate path).
        // claimed_by==me but claimed_generation<myGeneration → my own pre-
        //                  restart claim. Release with 5s cooldown so peer
        //                  (or my fresh self after lease re-establishment)
        //                  can re-pick. The releaseSignalAsStale UPDATE is
        //                  guarded against peer-clobber (audit C M3).
        if (heartbeatService != null
                && resolvedSignal.getClaimedBy() != null
                && resolvedSignal.getClaimedGeneration() > 0) {
            String claimedBy = resolvedSignal.getClaimedBy();
            String myInstance = heartbeatService.getInstanceId();
            long claimedGen = resolvedSignal.getClaimedGeneration();
            long myGen = heartbeatService.getCurrentGeneration();
            if (!claimedBy.equals(myInstance)) {
                logger.info("[SignalResume] STALE_OWNERSHIP skip - signal {} claimed by peer {} (me={}); peer will resume",
                        resolvedSignal.getId(), claimedBy, myInstance);
                return;
            }
            if (claimedGen < myGen) {
                logger.warn("[SignalResume] STALE_OWNERSHIP - signal {} claimed by me at gen={} but live gen={}. Releasing.",
                        resolvedSignal.getId(), claimedGen, myGen);
                heartbeatService.releaseSignalAsStale(resolvedSignal.getId(), claimedGen);
                return;
            }
            if (claimedGen > myGen) {
                // Audit S4 - generation is monotonic; claimed_generation > live should
                // be impossible. Observing it means clock skew, manual DB intervention,
                // or a serialization bug. Log loud, proceed (benign - branch (d) semantics).
                logger.error("[SignalResume] Impossible monotonicity violation: signal {} "
                                + "claimed_generation={} > live={} for instance={}. Proceeding as if equal.",
                        resolvedSignal.getId(), claimedGen, myGen, myInstance);
            }
        }

        logger.info("[SignalResume] Resuming after signal: runId={}, nodeId={}, resolution={}, type={}",
            runId, nodeId, resolvedSignal.getResolution(), resolvedSignal.getSignalType());

        // 1. Validate run status
        WorkflowRunEntity run = runRepository.findByRunIdPublic(runId).orElse(null);
        if (run == null) {
            logger.warn("[SignalResume] Run not found: runId={}", runId);
            clearSignalResumePending(resolvedSignal);
            return;
        }

        RunStatus runStatus = run.getStatus();
        boolean reopenedFromTerminal = false;
        RunStatus previousStatus = null;

        if (runStatus != RunStatus.RUNNING && runStatus != RunStatus.WAITING_TRIGGER && runStatus != RunStatus.PAUSED) {
            // Allow terminal runs to resume for signal resolution.
            // INTERFACE_SIGNAL: interface actions can fire after workflow completes.
            // USER_APPROVAL / WAIT_TIMER: safety net for edge cases where the run was
            // prematurely marked as terminal while a signal was still pending.
            SignalType signalType = resolvedSignal.getSignalType();
            boolean isResumableSignalType = signalType == SignalType.INTERFACE_SIGNAL
                    || signalType == SignalType.USER_APPROVAL
                    || signalType == SignalType.WAIT_TIMER;
            if ((runStatus == RunStatus.COMPLETED || runStatus == RunStatus.FAILED)
                    && isResumableSignalType) {
                previousStatus = reopenRunForResume(run);
                reopenedFromTerminal = true;
            } else {
                logger.warn("[SignalResume] Run not in resumable state: runId={}, status={}", runId, runStatus);
                clearSignalResumePending(resolvedSignal);
                return;
            }
        }

        // 2. If workflow is PAUSED (SBS mode), persist signal resolution and update
        // ready nodes so the frontend shows the next step, but do NOT auto-execute.
        // In SBS mode, PAUSED means "waiting for user to click next step".
        // After a signal resolves (e.g., USER_APPROVAL approved), the successor node
        // must appear as ready so the user can click "execute" on it.
        if (runStatus == RunStatus.PAUSED) {
            boolean persisted = persistSignalResolutionOutput(resolvedSignal, run);
            if (persisted) {
                emitEdgesAfterSignalResolution(resolvedSignal, run);
            }
            restoreSplitContextIfNeeded(resolvedSignal, runId, nodeId);
            invalidateReadinessCache(runId, "paused-signal-resolution");

            if (v2StepByStepService != null && isStepByStepMode(run)) {
                // SBS: calculate and persist ready nodes so successor appears clickable
                logger.info("[SignalResume] Run {} is PAUSED (SBS) - persisting signal resolution for nodeId={} and updating ready nodes",
                    runId, nodeId);
                resumeStepByStep(runId, itemId, nodeId, resolvedSignal.getDagTriggerId(), resolvedSignal.getEpoch());
                // Send full snapshot immediately - signal resolution is a critical event
                if (snapshotService != null) {
                    snapshotService.sendSnapshotImmediate(runId);
                }
                // Reconcile SBS status (e.g., if approval was the last blocking node)
                stateSnapshotService.reconcileSbsRunStatus(runId);
            } else {
                // AUTO mode paused: defer execution until explicit resume
                logger.info("[SignalResume] Run {} is PAUSED (AUTO) - persisting signal resolution for nodeId={} but deferring execution until resume",
                    runId, nodeId);
            }
            clearSignalResumePending(resolvedSignal);
            return;
        }

        // 3. Check execution mode and resume
        if (v2StepByStepService == null) {
            logger.warn("[SignalResume] V2StepByStepService not available, cannot resume");
            clearSignalResumePending(resolvedSignal);
            return;
        }

        boolean isStepByStep = isStepByStepMode(run);

        if (isStepByStep) {
            // STEP_BY_STEP mode (RUNNING/WAITING_TRIGGER): persist + emit + resume
            boolean persisted = persistSignalResolutionOutput(resolvedSignal, run);
            if (persisted) {
                emitEdgesAfterSignalResolution(resolvedSignal, run);
            }
            restoreSplitContextIfNeeded(resolvedSignal, runId, nodeId);
            invalidateReadinessCache(runId, "sbs-signal-resolution");
            resumeStepByStep(runId, itemId, nodeId, resolvedSignal.getDagTriggerId(), resolvedSignal.getEpoch());
            clearSignalResumePending(resolvedSignal);
        } else {
            // AUTO mode: acquire per-run lock BEFORE persisting output + emitting edges.
            // This serializes the entire resume flow (persist → emit → getReadyNodes → execute)
            // for concurrent signal resolutions on the same run.
            //
            // Without this, two concurrent signals (e.g., fork branches form_a and form_b both
            // resolving their INTERFACE_SIGNAL) could both modify the snapshot via emitEdges
            // before either reads it in getReadyNodes, causing stale reads and duplicate execution.
            try {
                resumeAutoMode(runId, itemId, nodeId, resolvedSignal, run, reopenedFromTerminal, previousStatus,
                    resolvedSignal.getEpoch());
            } finally {
                // Leak-proofing for the deferred-reset sibling guard: AUTO paths that never
                // reach tryDeferredReset (waiting-for-split-outputs early return, refinalize
                // after terminal reopen, lock-acquire failure, any exception) must still drop
                // this signal's resume-pending marker - a leaked marker defers the
                // sibling-gated reset and the ReusableTriggerService refire guard until the
                // 2-min TTL self-heals it. Note the TTL heals the MARKER only: if the LAST
                // finalizer crashed before its tryDeferredReset, the reset itself is not
                // retried here - it is recovered at the next trigger fire, or the run
                // surfaces via the idle watchdog. Idempotent (set remove).
                clearSignalResumePending(resolvedSignal);
            }
        }
    }

    /**
     * Resume in AUTO mode: execute ready steps in a while-loop.
     * Follows ReusableTriggerService.executeReadySteps() pattern.
     *
     * Uses a per-run mutex to serialize the ENTIRE resume flow for the same run:
     * persist output → emit edges → get ready nodes → execute.
     *
     * Without this wide lock scope, two concurrent signals (e.g., fork branches form_a
     * and form_b) could both modify the snapshot via emitEdges before either reads it
     * in getReadyNodes. With H2 (used in tests) this can cause stale snapshot reads
     * where the EpochState shows nodes as not-yet-completed despite resolveAwaitingSignal
     * having committed, leading to duplicate node execution.
     */
    private void resumeAutoMode(String runId, String itemId, String nodeId, SignalWaitEntity resolvedSignal,
                                WorkflowRunEntity run, boolean reopenedFromTerminal, RunStatus previousStatus,
                                int signalEpoch) {

        // Per-run distributed mutex: serialize all resume flows for the same runId
        // across all orchestrator instances. Without this, two signals resolving on
        // different instances could both see the same downstream node as ready and
        // execute it, causing NodeCounts.completed to increment twice.
        String lockKey = RedisCacheKeys.lockSignalResume(runId);
        String lockOwner = UUID.randomUUID().toString();
        boolean lockAcquired = tryAcquireDistributedLock(lockKey, lockOwner, SIGNAL_LOCK_TTL, SIGNAL_LOCK_WARN_INTERVAL);
        if (!lockAcquired) {
            logger.warn("[SignalResume] Could not acquire distributed lock for runId={}, aborting resume instead of running without lock",
                runId);
            throw new IllegalStateException("Could not acquire signal-resume lock for run " + runId);
        }
        try {
            // Persist output and emit edges INSIDE the lock to prevent concurrent snapshot modifications
            boolean persisted = persistSignalResolutionOutput(resolvedSignal, run);
            if (persisted) {
                emitEdgesAfterSignalResolution(resolvedSignal, run);
            }
            restoreSplitContextIfNeeded(resolvedSignal, runId, nodeId);

            // Safety net for lost-update: ensure this node's completion is in the EpochState.
            // resolveAwaitingSignal() runs in the controller BEFORE this lock. When two signals
            // resolve concurrently, one thread's EpochState changes may be overwritten by the
            // other. This idempotent call re-asserts the node completion without double-counting.
            String dagTriggerId = resolvedSignal.getDagTriggerId();
            if (dagTriggerId != null && signalEpoch >= 0 && shouldMarkSignalCompletedInEpoch(resolvedSignal)) {
                stateSnapshotService.ensureNodeCompletedInEpoch(runId, dagTriggerId, signalEpoch, nodeId);
            }
            invalidateReadinessCache(runId, "auto-signal-resolution");

            resumeAutoModeUnderLock(runId, itemId, nodeId, resolvedSignal, run, reopenedFromTerminal, previousStatus, signalEpoch);
        } finally {
            // Clear the resume-pending marker BEFORE releasing the lock: the next sibling's
            // tryDeferredReset runs under this same lock and must see the true marker state.
            // Clearing after release would let the sibling observe a stale marker, defer the
            // reset, and leave nobody to retry it (stuck until the 2-min TTL / watchdog).
            // Covers every under-lock path, including the waiting-for-split-outputs early
            // return and exceptions. Idempotent with tryDeferredReset's own clear.
            clearSignalResumePending(resolvedSignal);
            if (lockAcquired) {
                releaseDistributedLock(lockKey, lockOwner);
            }
        }
    }

    private void invalidateReadinessCache(String runId, String reason) {
        if (readinessCache != null) {
            readinessCache.invalidateRun(runId);
            logger.debug("[SignalResume] Invalidated readiness cache after {}: runId={}", reason, runId);
        }
    }

    /**
     * Core AUTO mode logic, executed under per-run lock.
     */
    private void resumeAutoModeUnderLock(String runId, String itemId, String nodeId, SignalWaitEntity resolvedSignal,
                                         WorkflowRunEntity run, boolean reopenedFromTerminal, RunStatus previousStatus,
                                         int signalEpoch) {
        Set<String> currentReady = v2StepByStepService.getReadyNodes(runId, itemId, signalEpoch);
        // Concurrent-sibling guard (CE-SIGRACE-006/007): when sibling signals on other nodes
        // of the same (dag, epoch) resolved in the same instant, the resolver threads already
        // flipped THOSE nodes COMPLETED in the EpochState, so getReadyNodes returns the
        // siblings' successors to whichever resume wins the lock first - before the sibling
        // resume persisted its resolution output. Executing them here reads missing output;
        // leave each sibling's successors to its own resume (it runs this same flow under
        // this same lock right after us).
        currentReady = deferSuccessorsOfPendingSiblingResumes(currentReady, runId, resolvedSignal);

        // When executing successor nodes after all split signals resolve, run them at the
        // parent workflow item scope so SplitAwareNodeExecutor resolves the same split
        // context key that registered the batch. Passing the signal's sub-item id (e.g.
        // "4") would make workflowItemIndex=4 → context not found → single execution.
        boolean splitSignalsAllResolved = false;
        boolean waitingForSplitSignalOutputs = false;
        // Split context with sibling signals still pending: this resume must NOT trigger
        // successors at all (per-item or otherwise) - the LAST sibling's resume runs the
        // proper SplitAware fan-out at the parent item scope. Guards the back-edge/fallback block
        // below from overriding the suppress/defer decision (see regression note there).
        boolean splitSiblingSignalsPending = false;
        // Set when, after all split signals resolved, EVERY successor already reached a
        // terminal status (COMPLETED/FAILED/SKIPPED) in a PRIOR resume of this resolve-all
        // burst. Guards the signal-successor fallback below from re-triggering them - the
        // re-execution bug where an all-failed split successor (node-level FAILED, e.g. an
        // unconfigured send_email) was re-run once per resume, duplicating side effects.
        boolean splitSuccessorsAllTerminal = false;

        // Split context: when a signal resolves for one item but successor nodes were already
        // executed for a previous item, ReadyNodeCalculator marks them as "completed" and returns
        // empty. In split context, SplitAwareNodeExecutor handles ALL items in one invocation,
        // so we must wait until ALL signals for this node are resolved before triggering successors.
        // Otherwise, the first resolution would trigger SplitAware which runs all items - but only
        // some items have been approved yet. Waiting for the last signal ensures all items are ready.
        //
        // Gate on isSplitContextNode so non-split signals (single signal per node) preserve their
        // original itemId. WAIT_TIMER is included (CE-WAIT-002, 2026-06-12): a wait inside a
        // split registers one WAIT_TIMER per item (same nodeId, itemIds 0..N-1) exactly like a
        // per-item approval, and excluding it let the first expiry's resume execute the
        // successor once as a single non-split node - SKIP-cascading every sibling item's
        // continuation. Cost for non-split waits (itemId="0"): one indexed isSplitContextNode
        // lookup per resume, returning false.
        SignalType signalType = resolvedSignal != null ? resolvedSignal.getSignalType() : null;
        // WEBHOOK_WAIT deliberately absent: no node registers it today - add it here
        // (with tests) together with the node that registers per-item webhook signals.
        boolean canBeSplitSignal = signalType == SignalType.USER_APPROVAL
                || signalType == SignalType.INTERFACE_SIGNAL
                || signalType == SignalType.WAIT_TIMER;
        if (itemId != null && canBeSplitSignal
                && signalService.isSplitContextNode(runId, nodeId, signalEpoch)) {
            boolean hasRemaining = signalService.getActiveSignals(runId, signalEpoch).stream()
                .anyMatch(signal -> nodeId.equals(signal.getNodeId()));
            boolean allSignalOutputsPersisted = !hasRemaining
                && areAllSplitSignalOutputsPersisted(runId, nodeId, signalEpoch);
            if (!hasRemaining && !allSignalOutputsPersisted
                    && persistMissingSplitSignalOutputs(runId, nodeId, signalEpoch, run)) {
                allSignalOutputsPersisted = areAllSplitSignalOutputsPersisted(runId, nodeId, signalEpoch);
            }
            if (!hasRemaining && allSignalOutputsPersisted) {
                splitSignalsAllResolved = true;
                // If ReadyNodeCalculator returned empty (items 1..N-1), find successors directly.
                // Re-derivation site #3 - apply the concurrent-sibling guard here too: a split
                // successor that is ALSO fed by an INDEPENDENT pending sibling signal (merge
                // joining the split continuation and a concurrently-resolved gate) must wait
                // for that sibling's resume. Split-item siblings share OUR nodeId and are
                // excluded inside the guard, so the normal fan-out is unaffected.
                if (currentReady == null || currentReady.isEmpty()) {
                    Set<String> splitSuccessors = deferSuccessorsOfPendingSiblingResumes(
                        findSplitContextSuccessors(runId, nodeId), runId, resolvedSignal);
                    // Drop successors that already reached a terminal status in this epoch: a
                    // PRIOR resume of this resolve-all burst already executed them. Every resume
                    // that observes "all signals resolved" reaches this branch; without this
                    // filter each one re-triggers the successor. SplitAware's per-item idempotency
                    // short-circuits an already-COMPLETED successor, but an all-FAILED one
                    // (node-level FAILED) is not short-circuited and gets re-run on each resume.
                    Set<String> freshSuccessors = filterOutAlreadyTerminalNodes(
                        splitSuccessors, runId, resolvedSignal, signalEpoch);
                    if (!freshSuccessors.isEmpty()) {
                        logger.info("[SignalResume] Split context: all signals resolved for nodeId={}, triggering {} successors",
                            nodeId, freshSuccessors.size());
                        currentReady = freshSuccessors;
                    } else if (!splitSuccessors.isEmpty()) {
                        // All successors already ran in a prior resume - nothing to trigger.
                        // Flag it so the signal-successor fallback below does not resurrect them.
                        splitSuccessorsAllTerminal = true;
                        logger.info("[SignalResume] Split context: all {} successors already terminal for nodeId={} (prior resume ran them), skipping re-trigger",
                            splitSuccessors.size(), nodeId);
                    }
                }
            } else if (!hasRemaining) {
                logger.info("[SignalResume] Split context: waiting for signal outputs to persist before triggering successors for nodeId={}, itemId={}",
                    nodeId, itemId);
                waitingForSplitSignalOutputs = true;
                currentReady = Set.of();
            } else if (currentReady == null || currentReady.isEmpty()) {
                logger.info("[SignalResume] Split context: other items still have pending signals for nodeId={}, itemId={}, deferring successor execution",
                    nodeId, itemId);
                splitSiblingSignalsPending = true;
            } else {
                logger.info("[SignalResume] Split context: suppressing {} ready successors while other items still have pending signals for nodeId={}, itemId={}",
                    currentReady.size(), nodeId, itemId);
                currentReady = Set.of();
                splitSiblingSignalsPending = true;
            }
        }

        Set<String> executableReady = currentReady == null ? Set.of() : filterOutTriggers(currentReady);
        // Regression guard (CE-WF-OUTPUT-015, 2026-06-10): when the split-context gate above
        // decided to defer/suppress because SIBLING item signals are still pending, the
        // back-edge advance and the signal-successor fallback below must NOT run. The
        // fallback re-derives the resolved item's successors and - the moment the last
        // sibling resolves on the HTTP thread (resolveAwaitingSignal flips the node to
        // COMPLETED in EpochState mid-resume) - sees canExecute=true and executes the
        // successor ONCE as a single non-split node (this resume's itemId). That single
        // completion then makes the real all-resolved fan-out (parent itemId → SplitAware,
        // one split-aware execution) hit the idempotent-skip guard, losing every other
        // item's continuation (observed: 2 approved items → approved_path completed once).
        if (executableReady.isEmpty() && !waitingForSplitSignalOutputs && !splitSiblingSignalsPending
                && !splitSuccessorsAllTerminal) {
            // Concurrent-sibling guard: both fallbacks re-derive successors from the
            // EpochState, where sibling signal nodes are already COMPLETED (resolver-thread
            // CAS) - without re-filtering, a node the entry-point guard just deferred (e.g.
            // a merge fed by our node AND a pending sibling) is resurrected here and runs
            // before the sibling's resume persisted its output.
            Set<String> backEdgeReady = deferSuccessorsOfPendingSiblingResumes(
                advanceBackEdgeAfterSignalResolution(runId, itemId, nodeId, resolvedSignal, signalEpoch),
                runId, resolvedSignal);
            if (!backEdgeReady.isEmpty()) {
                logger.info("[SignalResume] Back-edge after signal produced ready nodes: runId={}, nodeId={}, ready={}",
                    runId, nodeId, backEdgeReady);
                currentReady = backEdgeReady;
            } else {
                // Re-derivation site #2 (the signal-successor fallback). Like the split branch
                // above, drop successors already terminal (COMPLETED/FAILED/SKIPPED) in this epoch.
                // A PRIOR resume re-delivery reaches this same fallback - e.g.
                // SignalRecoveryService re-driving an orphaned RESOLVED-but-RUNNING signal after a
                // restart, past the 5-minute dedup TTL. findResolvedSignalSuccessors gates only on
                // canExecute, which checks PREDECESSOR completion, NOT the successor's own terminal
                // status, so an already-FAILED side-effecting successor (e.g. an unconfigured
                // send_email / http_request that hit the endpoint then errored) would be re-executed
                // and its side effect duplicated. The back-edge branch above is intentionally NOT
                // filtered (loops legitimately re-run completed nodes).
                Set<String> signalSuccessors = filterOutAlreadyTerminalNodes(
                    deferSuccessorsOfPendingSiblingResumes(
                        findResolvedSignalSuccessors(runId, itemId, nodeId, resolvedSignal, signalEpoch),
                        runId, resolvedSignal),
                    runId, resolvedSignal, signalEpoch);
                if (!signalSuccessors.isEmpty()) {
                    logger.info("[SignalResume] Signal successor fallback produced ready nodes: runId={}, nodeId={}, ready={}",
                        runId, nodeId, signalSuccessors);
                    currentReady = signalSuccessors;
                }
            }
        }

        if (currentReady == null || currentReady.isEmpty()) {
            logger.info("[SignalResume] No ready nodes after signal resolution: runId={}, nodeId={}, epoch={}", runId, nodeId, signalEpoch);
            if (reopenedFromTerminal) {
                refinalizeAfterInterfaceResume(runId, previousStatus);
            } else if (waitingForSplitSignalOutputs) {
                logger.info("[SignalResume] Deferring epoch reset until all split signal outputs are persisted: runId={}, nodeId={}, epoch={}",
                    runId, nodeId, signalEpoch);
            } else {
                // No successors to execute - this signal's node was the last in the DAG.
                // Check if all blocking signals for this epoch are resolved and trigger deferred reset.
                tryDeferredReset(runId, resolvedSignal, signalEpoch);
            }
            return;
        }

        // Filter out trigger nodes (same as ReusableTriggerService)
        currentReady = filterOutTriggers(currentReady);
        // Filter out nodes already awaiting signals IN THIS EPOCH (e.g., interface nodes
        // in fork branches that yielded during initial execution but are returned as "ready"
        // by ReadyNodeCalculator because their predecessor completed and they have no output yet).
        // Must be epoch-scoped: a signal from epoch N-1 must NOT block the same node in epoch N.
        currentReady = filterOutAwaitingSignalNodes(currentReady, runId, signalEpoch);
        if (currentReady.isEmpty()) {
            logger.info("[SignalResume] No non-trigger ready steps: runId={}", runId);
            if (reopenedFromTerminal) {
                refinalizeAfterInterfaceResume(runId, previousStatus);
            } else {
                tryDeferredReset(runId, resolvedSignal, signalEpoch);
            }
            return;
        }

        logger.info("[SignalResume] AUTO mode: executing {} ready nodes for runId={}, epoch={}",
            currentReady.size(), runId, signalEpoch);

        int maxIterations = 100;
        int iteration = 0;
        boolean awaitingSignal = false;

        while (!currentReady.isEmpty() && iteration < maxIterations && !awaitingSignal) {
            iteration++;

            for (String stepId : currentReady) {
                try {
                    // Use the signal's epoch + triggerId so the next node executes in the correct
                    // epoch context. The triggerId from the resolved signal ensures shared DAG nodes
                    // use the correct trigger's DagState even during parallel epoch execution.
                    //
                    // When all split signals resolved, execute successors at the parent workflow
                    // item scope. SplitAwareNodeExecutor then rehydrates the sealed split context
                    // for that parent and iterates the routed sub-items. Passing the signal's
                    // sub-item itemId (e.g. "4") would look up the split context at the wrong key
                    // and degenerate to a single non-split execution.
                    String execItemId = splitSignalsAllResolved
                        ? resolveParentItemIdForSplitSignal(resolvedSignal)
                        : itemId;
                    String signalTriggerId = resolvedSignal != null ? resolvedSignal.getDagTriggerId() : null;
                    StepByStepExecutionResult result = signalTriggerId != null
                        ? v2StepByStepService.executeNode(runId, stepId, execItemId, signalEpoch, signalTriggerId)
                        : v2StepByStepService.executeNode(runId, stepId, execItemId, signalEpoch);

                    // Non-terminal (pending) results mean the node is still in flight - awaiting
                    // a signal, async agent-queue yield, running, etc. ALL pending states stop
                    // the loop - e.g. after __continue resolves an interface, execution may reach
                    // another interface/approval/agent node which must also pause.
                    //
                    // Gate on isPending() (not isAwaitingSignal() alone) so async-queue yields
                    // that use AWAITING_SIGNAL-adjacent statuses don't fall through to the
                    // "failed" log spam below.
                    if (result.isPending()) {
                        logger.info("[SignalResume] Node {} is pending ({}), pausing auto-execution for runId={}",
                            stepId,
                            result.nodeResult() != null ? result.nodeResult().status() : "unknown",
                            runId);
                        awaitingSignal = true;
                        break;
                    }

                    if (!result.isSuccess()) {
                        logger.warn("[SignalResume] Step {} failed: {}", stepId, result.getErrorMessage());
                    }
                } catch (Exception e) {
                    logger.error("[SignalResume] Error executing step {}: {}", stepId, e.getMessage(), e);
                }
            }

            if (awaitingSignal) {
                break;
            }

            // Get next ready steps (epoch-scoped for parallel epoch isolation)
            currentReady = v2StepByStepService.getReadyNodes(runId, itemId, signalEpoch);
            if (currentReady == null) {
                currentReady = Set.of();
            } else {
                currentReady = filterOutTriggers(currentReady);
                currentReady = filterOutAwaitingSignalNodes(currentReady, runId, signalEpoch);
                // Re-apply the concurrent-sibling guard: the refresh re-derives the sibling
                // signals' successors (their nodes are COMPLETED in the EpochState) even
                // though the entry-point filter dropped them - without this they leak back
                // into the loop on the second iteration.
                currentReady = deferSuccessorsOfPendingSiblingResumes(currentReady, runId, resolvedSignal);
            }
        }

        if (iteration >= maxIterations) {
            logger.warn("[SignalResume] Reached max iterations ({}) for runId={}", maxIterations, runId);
        }

        logger.info("[SignalResume] AUTO mode completed after {} iterations for runId={}", iteration, runId);

        if (reopenedFromTerminal) {
            // Re-finalize: restore terminal status after interface successors complete
            refinalizeAfterInterfaceResume(runId, previousStatus);
        } else {
            tryDeferredReset(runId, resolvedSignal, signalEpoch);
        }
    }

    /**
     * Check if all blocking signals for this epoch are resolved and trigger deferred reset if so.
     * Extracted to avoid duplicating the deferred-reset logic at every early-return point.
     */
    private void tryDeferredReset(String runId, SignalWaitEntity resolvedSignal, int signalEpoch) {
        String dagTriggerId = resolvedSignal.getDagTriggerId();
        clearSignalResumePending(resolvedSignal);
        // Concurrent-sibling guard (CE-SIGRACE-006/007, 2026-06-12): when N signals of the
        // same (dag, epoch) resolve in the same instant (same-duration timers, identical
        // approval timeouts, Promise.all resolutions), ALL are DB-RESOLVED before the first
        // resume runs - hasBlockingSignalsForDagAndEpoch is already false, and the FIRST
        // resume would reset the epoch while sibling resumes have persisted nothing. Each
        // later sibling resume then churns the freshly-reset DAG and re-resets it (observed:
        // 3 resets for one epoch, completed counts wiped). Mirror
        // ReusableTriggerService.hasActiveSignalsForTrigger: a sibling whose async resume is
        // still finalizing (signal_resume_pending marker, 2-min TTL self-heal) blocks the
        // reset - the LAST resume to clear its marker performs the single reset. Resumes for
        // the same run are serialized by the per-run lock, so the last clearer reliably sees
        // an empty marker set.
        if (signalService.hasPendingSignalResumesForDagAndEpoch(runId, dagTriggerId, signalEpoch)) {
            logger.info("[SignalResume] Sibling signal resumes still finalizing for DAG {} epoch {}, deferring reset: runId={}",
                dagTriggerId, signalEpoch, runId);
            return;
        }
        if (!signalService.hasBlockingSignalsForDagAndEpoch(runId, dagTriggerId, signalEpoch)) {
            logger.info("[SignalResume] All signals resolved for DAG {} epoch {}, runId={}, triggering deferred reset",
                dagTriggerId, signalEpoch, runId);
            performDeferredReset(runId, dagTriggerId, signalEpoch);
        } else {
            logger.info("[SignalResume] Signals still active for DAG {} epoch {}, runId={}", dagTriggerId, signalEpoch, runId);
        }
    }

    private boolean shouldMarkSignalCompletedInEpoch(SignalWaitEntity resolvedSignal) {
        return resolvedSignal == null || resolvedSignal.getResolution() != SignalResolution.CANCELLED;
    }

    private void clearSignalResumePending(SignalWaitEntity resolvedSignal) {
        if (resolvedSignal == null || signalService == null) {
            return;
        }
        signalService.clearSignalResumePending(resolvedSignal);
    }

    /**
     * Persist the signal resolution output to storage so that ReadyNodeCalculator
     * can see the node as completed (not skipped) and traverse its successors.
     *
     * When a node yields with AWAITING_SIGNAL, its output is NOT persisted to storage.
     * The engine returns immediately and relies on SignalResumeService to resume later.
     * However, getReadyNodes() reconstructs the execution tree from DB, and
     * ReadyNodeCalculator checks if a node has output in storage to determine if it
     * completed or was skipped. Without output, the node is treated as SKIPPED and
     * successors are not traversed.
     *
     * This method bridges that gap by persisting the signal resolution data as the
     * node's output, making ReadyNodeCalculator see it as completed.
     *
     * For approval nodes, the output includes "selected_port" which is critical for
     * UserApprovalNode.getNextNodes() to route to the correct branch (approved/rejected/timeout).
     *
     * @param resolvedSignal The resolved signal entity with resolution data
     * @param run The workflow run entity (for tenantId and epoch)
     */
    private void restoreSplitContextIfNeeded(SignalWaitEntity resolvedSignal, String runId, String nodeId) {
        if (resolvedSignal.getSplitItemData() != null && !resolvedSignal.getSplitItemData().isEmpty()) {
            splitContextManager.restoreContext(runId, nodeId, resolvedSignal.getSplitItemData());
        }
    }

    private boolean persistSignalResolutionOutput(SignalWaitEntity resolvedSignal, WorkflowRunEntity run) {
        String runId = resolvedSignal.getRunId();
        String nodeId = resolvedSignal.getNodeId();
        String tenantId = run.getTenantId();

        try {
            // Use the epoch from the signal itself (not the global current epoch).
            // With parallel epochs, multiple signals can be active for different epochs.
            // Using getCurrentEpoch() would return the latest global epoch, causing
            // collisions and wrong epoch assignment for earlier parallel epochs.
            String dagTriggerId = resolvedSignal.getDagTriggerId();
            int epoch = resolvedSignal.getEpoch();
            int itemIndex = parseItemIndex(resolvedSignal.getItemId());
            boolean cancelled = resolvedSignal.getResolution() == SignalResolution.CANCELLED;
            String terminalStatus = cancelled ? "SKIPPED" : "COMPLETED";
            Instant signalCreatedAt = resolvedSignal.getCreatedAt();
            boolean alreadyPersisted = signalCreatedAt != null
                ? stepDataRepository.existsByRunIdAndNormalizedKeyAndEpochAndItemIndexAndStatusAndStartTime(
                    runId, nodeId, epoch, itemIndex, terminalStatus, signalCreatedAt)
                : stepDataRepository.existsByRunIdAndNormalizedKeyAndEpochAndItemIndexAndStatus(
                    runId, nodeId, epoch, itemIndex, terminalStatus);
            if (alreadyPersisted) {
                logger.debug("[SignalResume] Signal output already persisted, skipping duplicate resume write: runId={}, nodeId={}, epoch={}, itemIndex={}",
                    runId, nodeId, epoch, itemIndex);
                return false;
            }
            long existingCompletions = stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndItemIndexAndStatus(
                runId, nodeId, epoch, itemIndex, terminalStatus);
            int stepIteration = existingCompletions > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) existingCompletions;

            // Build the output payload from the signal resolution data
            Map<String, Object> output = buildSignalResolutionOutput(resolvedSignal);
            Map<String, Object> inputData = buildSignalInputData(resolvedSignal);

            // Add split_item_count to the output if this is a split-context signal.
            // ReadyNodeCalculator uses this to detect split context and traverse ALL branches
            // of branching nodes (Decision, Switch, Approval) instead of just the last item's
            // selected port. Without this, the last resolved item's port (e.g., "rejected")
            // would prevent traversal to successor nodes on other branches (e.g., "approved").
            //
            // Only USER_APPROVAL needs this - WAIT_TIMER and INTERFACE_SIGNAL don't branch on
            // split items. Skip the DB lookup for non-approval signals.
            if (resolvedSignal.getSignalType() == SignalType.USER_APPROVAL) {
                try {
                    boolean hasSplitItemData = resolvedSignal.getSplitItemData() != null
                        && !resolvedSignal.getSplitItemData().isEmpty();
                    boolean splitContext = hasSplitItemData
                        || signalService.isSplitContextNode(runId, nodeId, epoch);
                    long signalCount = splitContext
                        ? signalService.getSignalCountForNodeEpoch(runId, nodeId, epoch)
                        : 0;
                    if (splitContext && signalCount <= 0) {
                        signalCount = 1;
                    }
                    if (signalCount > 0) {
                        // The output is wrapped: { "output": { ... } } - add to inner map
                        Object innerObj = output.get("output");
                        if (innerObj instanceof Map<?, ?> innerMap) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> inner = (Map<String, Object>) innerMap;
                            inner.put("split_item_count", (int) signalCount);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("[SignalResume] Could not determine split context for output enrichment: {}", e.getMessage());
                }
            }

            // Persist to storage with the node's step key so RunContextService can find it
            String workflowId = run.getWorkflow() != null ? run.getWorkflow().getId().toString() : null;
            UUID storageId = storageService.saveJsonWithContext(
                tenantId,
                output,
                ExecutionConstants.CONTENT_TYPE_JSON,
                null,    // expiresAt
                null,    // toolId
                runId,
                nodeId,  // stepKey - use the nodeId directly (e.g., "core:gate")
                itemIndex,
                epoch,
                workflowId,
                "SIGNAL"
            );

            // NOTE: Do NOT call stateSnapshotService.markNodeCompleted() here.
            // UnifiedSignalService.resolveSignal() already calls resolveAwaitingSignal()
            // which increments NodeCounts.completed. Calling markNodeCompleted() again
            // would double-count (completed=2 instead of 1).

            // Clear the in-memory running count for this node under the
            // per-epoch Redis key (P2.3.1).
            // emitNodeStart() marks the node as RUNNING in RunningNodeTracker
            // under {orchestrator:running:runId:epoch}, but emitNodeComplete()
            // is never called (node yielded with AWAITING_SIGNAL). Without this,
            // SnapshotService.buildSteps() merges running=1 + completed=1 = total 2.
            // The epoch comes from the resolved signal, NOT the run's "current"
            // epoch - a parallel earlier epoch may have left this node running.
            runningNodeTracker.markCompleted(runId, epoch, nodeId);

            // Determine the selected branch port for approval nodes
            String selectedBranch = null;
            SignalResolution resolution = resolvedSignal.getResolution();
            if (resolution != null) {
                selectedBranch = switch (resolution) {
                    case APPROVED -> "approved";
                    case REJECTED -> "rejected";
                    case TIMEOUT -> "timeout";
                    default -> null;
                };
            }

            // Record the selected branch in decisionBranches so that
            // SnapshotService can include it in batch-update step data,
            // and the frontend can show branch coloring (green/grey) on the node.
            if (selectedBranch != null) {
                if (dagTriggerId != null) {
                    stateSnapshotService.recordDecisionBranch(runId, dagTriggerId, epoch, nodeId, selectedBranch);
                } else {
                    stateSnapshotService.recordDecisionBranch(runId, nodeId, selectedBranch);
                }
            }

            // Persist workflow_step_data entry so the frontend output viewer can find it.
            // Without this, getRunStepsPaged() returns empty and the inspector shows "No output data".
            String stepLabel = LabelNormalizer.extractLabelFromKey(nodeId);
            Instant now = Instant.now();
            WorkflowStepDataEntity stepData = new WorkflowStepDataEntity(
                run.getId(),            // workflowRunId (UUID)
                runId,                  // runId (public string)
                stepLabel,              // stepAlias (e.g., "user_approval")
                stepLabel,              // toolId
                inputData,              // inputData / resolved signal params for inspector
                storageId,              // outputStorageId
                null,                   // httpStatus
                terminalStatus,         // status
                resolvedSignal.getCreatedAt(),  // startTime (signal creation)
                now,                    // endTime (resolution time)
                null,                   // errorMessage
                tenantId,               // tenantId
                epoch,                  // epoch
                extractSpawnFromRun(run), // spawn (actual, not hardcoded 0)
                stepIteration,          // iteration
                itemIndex,              // itemIndex
                null                    // metadata
            );
            stepData.setNormalizedKey(nodeId);
            stepData.setTriggerId(dagTriggerId != null ? dagTriggerId : "trigger:default");
            stepData.setOrganizationId(run.getOrganizationId());
            if (selectedBranch != null) {
                stepData.setSelectedBranch(selectedBranch);
            }
            if (cancelled) {
                stepData.setSkipReason("Signal cancelled");
                stepData.setSkipSourceNode(nodeId);
            }
            try {
                stepDataRepository.save(stepData);
                logger.info("[SignalResume] Persisted signal resolution output: runId={}, nodeId={}, resolution={}, epoch={}, outputKeys={}, stepDataId={}",
                    runId, nodeId, resolvedSignal.getResolution(), epoch, output.keySet(), stepData.getId());
            } catch (org.springframework.dao.DataIntegrityViolationException dupEx) {
                // Expected when output was already persisted (concurrent resolution or re-execution)
                logger.debug("[SignalResume] Signal output already persisted (idempotent): runId={}, nodeId={}, epoch={}",
                    runId, nodeId, epoch);
                return false;
            }

            // Record epoch count for signal nodes (UserApproval, Interface, Wait)
            // so that epoch state viewer includes them alongside regular step nodes
            try {
                workflowEpochService.recordNodeCount(runId, epoch, nodeId, terminalStatus, dagTriggerId);
            } catch (Exception ec) {
                logger.warn("[SignalResume] epoch count recording failed: {}", ec.getMessage());
            }

            // Consume 1 credit for this signal node (same as StepCompletionOrchestrator.consumeCreditForNode)
            int spawn = extractSpawnFromRun(run);
            consumeCreditForSignalNode(tenantId, runId, nodeId, epoch, spawn, run);
            return true;

        } catch (Exception e) {
            logger.error("[SignalResume] Failed to persist signal resolution output: runId={}, nodeId={}, error={}",
                runId, nodeId, e.getMessage(), e);
            // Don't fail the entire resume flow - getReadyNodes() may still work
            // if the node was already persisted by another mechanism
            return false;
        }
    }

    private Map<String, Object> buildSignalInputData(SignalWaitEntity resolvedSignal) {
        Map<String, Object> input = new HashMap<>();
        input.put("signal_type", resolvedSignal.getSignalType() != null
            ? resolvedSignal.getSignalType().name()
            : "UNKNOWN");

        if (resolvedSignal.getSignalConfig() != null && !resolvedSignal.getSignalConfig().isEmpty()) {
            Map<String, Object> signalConfig = new HashMap<>(resolvedSignal.getSignalConfig());
            signalConfig.remove("webhookToken");
            signalConfig.remove("cdpToken");
            input.put("signal_config", signalConfig);
        }
        if (resolvedSignal.getItemId() != null) {
            input.put("item_id", resolvedSignal.getItemId());
        }
        if (resolvedSignal.getDagTriggerId() != null) {
            input.put("trigger_id", resolvedSignal.getDagTriggerId());
        }
        input.put("epoch", resolvedSignal.getEpoch());
        return input;
    }

    /**
     * Emit outgoing edge events after signal resolution.
     *
     * When a node yields with AWAITING_SIGNAL, the engine calls emitNodeAwaitingSignal()
     * instead of emitNodeComplete(). This means edge statuses are never triggered.
     *
     * This method fills that gap for:
     * - USER_APPROVAL: branching edges (COMPLETED for selected branch, SKIPPED for others + propagation)
     * - INTERFACE_SIGNAL + CONTINUE: linear successor edges (COMPLETED)
     *
     * Uses V2ExecutionEventService.emitBranchingEdgesForSignalNode() which calls
     * EdgeStatusEmitter.emitOutgoingEdges() - handles both branching and linear nodes.
     */
    private void emitEdgesAfterSignalResolution(SignalWaitEntity resolvedSignal, WorkflowRunEntity run) {
        SignalType signalType = resolvedSignal.getSignalType();

        // Edge emission is required so the frontend's edge statusCount goes from
        // 0 → 1 after the wait completes. WAIT_TIMER and WEBHOOK_WAIT are now
        // re-enabled (2026-05-06) after the two prerequisite bugs were fixed in
        // the same series:
        //   (a) L1-cache pollution in UnifiedSignalService.resolveSignal (zombie
        //       guard previously called findById, which seeded the L1 cache with
        //       PENDING; the subsequent save(entity) reverted the just-resolved
        //       status - fixed by switching to findEpochInfoById projection).
        //   (b) Cross-epoch keepInAwaiting: hasRemainingSignals was run+node-wide,
        //       so a sibling signal in another epoch made the resolving epoch's
        //       wait stuck in awaitingSignalNodeIds - fixed by scoping to
        //       (run, node, dag, epoch) via countActiveByRunIdAndNodeIdAndDagAndEpoch.
        // With those fixed, the linear `success(nodeId, Map.of())` emission no
        // longer loops back into a fresh ready-node calc that sees the wait as
        // not-completed.
        //
        // AGENT_EXECUTION still skips this path - it has its own emit pipeline
        // in AgentAsyncCompletionService.emitDownstreamEvents.
        if (signalType == SignalType.AGENT_EXECUTION) {
            return;
        }

        // For INTERFACE_SIGNAL, only emit edges when the interface completes (__continue).
        // ACTION_FIRED does NOT complete the interface, so no edge emission needed.
        if (signalType == SignalType.INTERFACE_SIGNAL
                && resolvedSignal.getResolution() != SignalResolution.CONTINUE) {
            return;
        }

        if (eventService == null) {
            logger.warn("[SignalResume] V2ExecutionEventService not available, skipping edge emission");
            return;
        }

        String runId = resolvedSignal.getRunId();
        String nodeId = resolvedSignal.getNodeId();
        int itemIndex = parseItemIndex(resolvedSignal.getItemId());

        // Split context detection: if this node had multiple signals (one per split item),
        // suppress skip propagation so rejected items don't prematurely mark downstream
        // nodes as SKIPPED. SplitAwareNodeExecutor handles per-item routing (approved vs
        // rejected) when it executes successor nodes after ALL signals have resolved.
        // Epoch-scoped to avoid false positives from signals in other trigger cycles.
        // Uses countAll (not just active) to detect split context even for the last signal.
        int epoch = resolvedSignal.getEpoch();
        boolean isSplitContext = false;
        if (signalService != null) {
            isSplitContext = signalService.isSplitContextNode(runId, nodeId, epoch);
        }

        try {
            // Load execution tree from cache/DB
            ExecutionCacheManager.LoadedExecution loaded = executionCacheManager.loadTreeAndExecution(runId);
            if (loaded == null || loaded.tree() == null || loaded.execution() == null) {
                logger.warn("[SignalResume] Cannot emit edges - execution not available: runId={}", runId);
                return;
            }

            ExecutionTree tree = loaded.tree();
            WorkflowExecution execution = loaded.execution();

            // Find the node in the execution tree
            Map<String, ExecutionNode> nodeMap = nodeSearchService.buildNodeMapFromAllRoots(tree);
            ExecutionNode signalNode = nodeMap.get(nodeId);
            if (signalNode == null) {
                logger.warn("[SignalResume] Node not found: runId={}, nodeId={}", runId, nodeId);
                return;
            }

            if (signalNode.isBranchingNode()) {
                // Branching node (UserApproval): resolve selected port for branch routing
                String selectedPort = resolveSelectedPort(resolvedSignal.getResolution());
                if (selectedPort == null) {
                    logger.warn("[SignalResume] No selected port for resolution: {}", resolvedSignal.getResolution());
                    return;
                }

                Map<String, Object> output = Map.of("selected_port", selectedPort);
                NodeExecutionResult result = NodeExecutionResult.success(nodeId, output);
                // In split context, suppress skip propagation - SplitAwareNodeExecutor will
                // handle per-item routing (approved vs rejected) when executing successors.
                eventService.emitBranchingEdgesForSignalNode(
                    execution, signalNode, itemIndex, result,
                    resolvedSignal.getEpoch(), resolvedSignal.getDagTriggerId(), isSplitContext);

                logger.info("[SignalResume] Emitted branching edges: runId={}, nodeId={}, selectedPort={}, suppressSkip={}",
                    runId, nodeId, selectedPort, isSplitContext);
            } else {
                // Linear node (interface): emit simple COMPLETED edges to successors
                NodeExecutionResult result = NodeExecutionResult.success(nodeId, Map.of());
                eventService.emitBranchingEdgesForSignalNode(
                    execution, signalNode, itemIndex, result,
                    resolvedSignal.getEpoch(), resolvedSignal.getDagTriggerId(), isSplitContext);

                logger.info("[SignalResume] Emitted linear edges after signal: runId={}, nodeId={}, suppressSkip={}",
                    runId, nodeId, isSplitContext);
            }

        } catch (Exception e) {
            logger.error("[SignalResume] Failed to emit edges: runId={}, nodeId={}, error={}",
                runId, nodeId, e.getMessage(), e);
            // Non-fatal: ready node calculation may still work, but frontend won't show edge status
        }
    }

    /**
     * Resolve the selected port name from a signal resolution.
     */
    private String resolveSelectedPort(SignalResolution resolution) {
        if (resolution == null) return null;
        return switch (resolution) {
            case APPROVED -> "approved";
            case REJECTED -> "rejected";
            case TIMEOUT -> "timeout";
            default -> null;
        };
    }

    /**
     * Build the output map from signal resolution data.
     *
     * For USER_APPROVAL signals, maps the resolution to a "selected_port" value
     * so that UserApprovalNode.getNextNodes() can route to the correct branch.
     *
     * For WAIT_TIMER signals, produces a simple completion output.
     *
     * @param resolvedSignal The resolved signal entity
     * @return Map containing the output data to persist
     */
    private Map<String, Object> buildSignalResolutionOutput(SignalWaitEntity resolvedSignal) {
        Map<String, Object> output = new HashMap<>();

        SignalResolution resolution = resolvedSignal.getResolution();
        String signalType = resolvedSignal.getSignalType() != null
            ? resolvedSignal.getSignalType().name() : "UNKNOWN";

        output.put("signal_type", signalType);
        output.put("resolution", resolution != null ? resolution.name() : "UNKNOWN");
        output.put("resolved_at", resolvedSignal.getResolvedAt() != null
            ? resolvedSignal.getResolvedAt().toString() : Instant.now().toString());
        output.put("resolved_by", resolvedSignal.getResolvedBy() != null
            ? resolvedSignal.getResolvedBy() : "system");

        // #W1: WAIT_TIMER resume must publish the SAME output contract as the inline
        // Wait path so `{{core:wait.output.*}}` refs resolve identically for short
        // waits (< INLINE_THRESHOLD_MS, handled inline) and long waits (> 3s, yielded
        // as signals and resumed here). Delegate to `WaitNode.buildWaitOutput` - the
        // single source of truth for the contract.
        //
        // Sources:
        //   - durationMs: signalConfig.durationMs (set at registration time by SignalConfig.timer)
        //   - startedAt:  resolvedSignal.getCreatedAt() (yield time = wait start)
        //   - completedAt: resolvedSignal.getResolvedAt() (resume time = wait end)
        //   - itemId/itemIndex: from the SignalWaitEntity
        if (resolvedSignal.getSignalType() == SignalType.WAIT_TIMER) {
            long durationMs = 0L;
            Map<String, Object> cfg = resolvedSignal.getSignalConfig();
            if (cfg != null && cfg.get("durationMs") instanceof Number n) {
                durationMs = n.longValue();
            }
            Map<String, Object> waitOutput = WaitNode.buildWaitOutput(
                durationMs,
                resolvedSignal.getCreatedAt(),
                resolvedSignal.getResolvedAt(),
                resolvedSignal.getItemId(),
                parseItemIndex(resolvedSignal.getItemId()));
            output.putAll(waitOutput);
        }

        // Include any resolution data from the signal
        if (resolvedSignal.getResolutionData() != null) {
            output.putAll(resolvedSignal.getResolutionData());

            // For agent async results, flatten the nested "result" map into the top-level output.
            // AgentResultMessage.toResolutionData() puts the agent output under key "result",
            // but AgentNode.getGuardrailNextNodes() looks for "passed" at top level,
            // and AgentNode.getClassifyNextNodes() looks for "selected_category" at top level.
            if (resolution == SignalResolution.AGENT_COMPLETED || resolution == SignalResolution.AGENT_FAILED) {
                Object resultObj = resolvedSignal.getResolutionData().get("result");
                if (resultObj instanceof Map<?, ?> resultMap) {
                    for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                        if (entry.getKey() instanceof String key) {
                            output.putIfAbsent(key, entry.getValue());
                        }
                    }
                }
            }
        }

        // Map resolution to selected_port for approval nodes (UserApprovalNode.getNextNodes())
        // Also set selectedBranch for frontend batch-update compatibility (statusUpdater reads output.selectedBranch)
        if (resolution != null) {
            String selectedPort = switch (resolution) {
                case APPROVED -> "approved";
                case REJECTED -> "rejected";
                case TIMEOUT -> "timeout";
                case COMPLETED -> null; // WAIT_TIMER - no port needed (linear successor)
                case CANCELLED -> null;
                case ACTION_FIRED -> null; // Interface action fired - does NOT complete the interface
                case CONTINUE -> null; // Interface __continue action - linear successor
                case AGENT_COMPLETED -> null; // Agent execution completed (async queue mode)
                case AGENT_FAILED -> null; // Agent execution failed (async queue mode)
            };
            if (selectedPort != null) {
                output.put("selected_port", selectedPort);
                output.put("selectedBranch", selectedPort);
            }
        }

        // Carry the resolved approval context into the COMPLETED node output so it survives the
        // awaiting -> resolved transition. During awaiting it is only on the pending signal
        // (banner); at resolution SignalResumeService writes a NEW step whose output is built here,
        // so without this the resolved context vanished from the node's params and could not be
        // referenced downstream as {{core:<label>.output.approval_context}}. Read from the already
        // persisted signal value (no re-resolution). Omitted when blank / no template configured.
        if (resolvedSignal.getSignalType() == SignalType.USER_APPROVAL
                && resolvedSignal.getApprovalContext() != null
                && !resolvedSignal.getApprovalContext().isBlank()) {
            output.put("approval_context", resolvedSignal.getApprovalContext());
        }

        // Wrap in "output" key to match the expected storage format
        // that ReadyNodeCalculator's OutputUnwrapper can handle
        Map<String, Object> payload = new HashMap<>();
        payload.put("output", output);

        return payload;
    }

    /**
     * Parse item index from itemId string.
     *
     * @param itemId The item ID (e.g., "0", "0.1", "0.2")
     * @return The parsed item index, or 0 if parsing fails
     */
    private int parseItemIndex(String itemId) {
        if (itemId == null) return 0;
        try {
            if (itemId.contains(".")) {
                String[] parts = itemId.split("\\.");
                return Integer.parseInt(parts[parts.length - 1]);
            }
            return Integer.parseInt(itemId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Resolve the parent workflow item scope for successor execution after a split
     * signal barrier seals. Top-level split sub-items use single-segment item ids
     * ("3"), but their parent scope is the root workflow item ("0"). Nested split
     * sub-items use scoped ids ("7.3"), where "7" is the parent workflow item.
     */
    // Package-private static (no instance state) so the workflowItemIndex branch - now the
    // primary path once buildSplitItemData persists workflowItemIndex for approval/wait - is
    // unit-tested for equivalence with the legacy itemId-suffix fallback.
    static String resolveParentItemIdForSplitSignal(SignalWaitEntity resolvedSignal) {
        if (resolvedSignal == null) {
            return "0";
        }
        Map<String, Object> splitData = resolvedSignal.getSplitItemData();
        if (splitData != null) {
            Object workflowItemIndex = splitData.get("workflowItemIndex");
            if (workflowItemIndex instanceof Number n) {
                return String.valueOf(n.intValue());
            }
        }
        String signalItemId = resolvedSignal.getItemId();
        if (signalItemId != null) {
            int dot = signalItemId.lastIndexOf('.');
            if (dot > 0) {
                return signalItemId.substring(0, dot);
            }
        }
        return "0";
    }

    /**
     * Extract the current spawn from the run metadata.
     * Mirrors WorkflowEntityResolverService.getCurrentSpawnFromRun() logic
     * but operates directly on the already-loaded run entity.
     */
    private int extractSpawnFromRun(WorkflowRunEntity run) {
        try {
            Map<String, Object> metadata = run.getMetadata();
            if (metadata == null) return 0;
            Object spawnValue = metadata.get("lastRerunSpawn");
            if (spawnValue instanceof Number) {
                return ((Number) spawnValue).intValue();
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Consume 1 credit for a signal-based node (UserApproval, Interface, Wait).
     * Matches the pattern in StepCompletionOrchestrator.consumeCreditForNode().
     * Fire-and-forget: credit failures never block signal resume.
     *
     * Experience mode is excluded (platform pays for those executions).
     */
    private void consumeCreditForSignalNode(String tenantId, String runId, String nodeId,
                                             int epoch, int spawn,
                                             com.apimarketplace.orchestrator.domain.WorkflowRunEntity run) {
        try {
            if (creditClient == null || tenantId == null || tenantId.isBlank()) {
                return;
            }

            // Round-9 audit fix: signal-resume entry points (Redis cross-instance
            // pub/sub listener, scheduled signal recovery sweep) run without an
            // HTTP request context, so TenantResolver.currentRequestOrganizationId()
            // returns null. After round-8, CreditConsumptionClient.consumeCreditsAsync
            // fails-fast on null orgId - which would be swallowed by the catch
            // below and silently drop the credit (money leak). Bind the run's
            // organizationId to the ThreadLocal so the producer-side requireOrgId
            // sees it. Same shape as CliAgentService.cleanupExpiredSessions.
            String orgId = run != null ? run.getOrganizationId() : null;
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgId, () ->
                creditClient.consumeCreditsAsync(tenantId, "WORKFLOW_NODE",
                    runId + ":" + nodeId + ":" + epoch + ":" + spawn,
                    null, null, null, null)
            );

            // Record Prometheus metric and per-run cost accumulator
            workflowMetrics.recordNodeCreditConsumed(nodeId);
            workflowMetrics.recordRunNodeCredit(runId);
        } catch (Exception e) {
            logger.warn("[SignalResume] Credit consumption failed for signal node {}: {}", nodeId, e.getMessage());
        }
    }

    /**
     * Reopen a terminal run (COMPLETED/FAILED) for interface signal resume.
     * Sets status back to RUNNING so successors can execute.
     *
     * @return the previous status before reopening
     */
    private RunStatus reopenRunForResume(WorkflowRunEntity run) {
        // Acquire PESSIMISTIC_WRITE to prevent concurrent double-reopen race
        WorkflowRunEntity lockedRun = runRepository.findByRunIdPublicForUpdate(run.getRunIdPublic())
            .orElse(run);
        RunStatus previousStatus = lockedRun.getStatus();

        // Re-check status under lock: another thread may have already reopened
        if (previousStatus == RunStatus.RUNNING) {
            logger.info("[SignalResume] Run already RUNNING (reopened by another thread): runId={}",
                lockedRun.getRunIdPublic());
            return previousStatus;
        }

        lockedRun.setStatus(RunStatus.RUNNING);
        lockedRun.setEndedAt(null);
        runRepository.save(lockedRun);
        logger.info("[SignalResume] Reopened {} run for interface signal resume: runId={}",
            previousStatus, lockedRun.getRunIdPublic());
        return previousStatus;
    }

    /**
     * Re-finalize a run that was reopened from COMPLETED/FAILED for interface signal resume.
     * Restores the terminal status after interface successors have executed.
     */
    private void refinalizeAfterInterfaceResume(String runId, RunStatus previousStatus) {
        try {
            WorkflowRunEntity run = runRepository.findByRunIdPublic(runId).orElse(null);
            if (run == null) return;

            // If new blocking signals appeared, stay RUNNING
            if (signalService.hasBlockingSignals(runId)) {
                logger.info("[SignalResume] Blocking signals appeared during interface resume, staying RUNNING: runId={}", runId);
                return;
            }

            // Same gate as OrchestrationRecoveryService: an async agent kicked off during
            // (or right before) the interface resume is tracked in PendingAgentRegistry,
            // not in workflow_signal_waits. Without this check we would re-finalize the
            // run COMPLETED/FAILED while a worker is still computing, dropping its result
            // on a terminal run when it eventually arrives. Mirrors hasAnyPendingForRun's
            // local-then-Redis lookup so cross-replica coverage is preserved.
            if (pendingAgentRegistry != null) {
                try {
                    if (pendingAgentRegistry.hasAnyPendingForRun(runId)) {
                        logger.info("[SignalResume] Async agent in flight during interface resume, staying RUNNING: runId={}", runId);
                        return;
                    }
                } catch (Exception e) {
                    // Fail-safe (same policy as the watchdog): a registry/Redis hiccup must
                    // never re-finalize a run we cannot prove is idle.
                    logger.warn("[SignalResume] Pending-agent check failed during interface resume for runId={}, staying RUNNING: {}",
                            runId, e.getMessage());
                    return;
                }
            }

            // Restore terminal status
            run.setStatus(previousStatus);
            run.setEndedAt(Instant.now());
            runRepository.save(run);

            logger.info("[SignalResume] Re-finalized after interface resume: runId={}, status={}", runId, previousStatus);
        } catch (Exception e) {
            logger.error("[SignalResume] Error re-finalizing after interface resume: runId={}, error={}", runId, e.getMessage(), e);
        }
    }

    /**
     * Perform deferred reset for next trigger cycle after all signals are resolved.
     *
     * <p>Reconstructs the minimal state needed to call
     * {@link com.apimarketplace.orchestrator.trigger.ReusableTriggerService#resetForNextCycle}.
     * Called when a signal-based node (e.g., WaitNode > 3s) was the last pending work in
     * a reusable trigger DAG.</p>
     *
     * <p>Public so {@code AgentAsyncCompletionService} can invoke it once the last
     * async agent for a (runId, dagTriggerId, epoch) triple has been delivered - the
     * async-path counterpart of "all signals resolved". Reusing this method instead of
     * duplicating the WorkflowRunEntity / WorkflowPlan / WorkflowExecution rebuild is
     * deliberate: the async subsystem stays decoupled from the signal subsystem but both
     * funnel into the same single reset entry point.</p>
     */
    public void performDeferredReset(String runId, String dagTriggerId, int epoch) {
        try {
            WorkflowRunEntity run = runRepository.findByRunIdPublic(runId).orElse(null);
            if (run == null || run.getStatus() == RunStatus.WAITING_TRIGGER) {
                // Already reset by another thread, or run not found
                return;
            }

            // Phase G.1: read plan directly from run entity (same bypass as ReusableTriggerService).
            // Pre-bypass: called resumeService.reconstructState only to access state.plan(),
            // which is verbatim runEntity.getPlan() - wasted ~200ms per call.
            String tenantId = run.getTenantId();
            Map<String, Object> planMap = run.getPlan();
            if (planMap == null || planMap.isEmpty()) {
                logger.warn("[SignalResume] Run {} has no plan - cannot perform deferred reset", runId);
                return;
            }
            WorkflowPlan plan = WorkflowPlan.fromMap(planMap, run.getWorkflow().getId().toString(), tenantId);
            WorkflowExecution execution = new WorkflowExecution(runId, plan, Map.of());
            execution.setWorkflowRunId(run.getId());
            if (run.getMetadata() != null) {
                if (run.getMetadata().get("__displayName__") instanceof String dn) {
                    execution.setDisplayName(dn);
                }
            }

            // Determine trigger type from dagTriggerId
            TriggerType triggerType = inferTriggerType(dagTriggerId);

            // Derive hasFailures from the epoch's failedNodeIds in the StateSnapshot.
            // The previous hardcoded `false` caused async agent failures to be silent:
            // no bell notification, no error-trigger dispatch, wrong lastCycleResult.
            // Default to true (fail-safe): over-notify is safer than swallowing real failures.
            boolean hasFailures = true;
            try {
                StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
                EpochState epochState = snapshot.getEpochState(dagTriggerId, epoch);
                hasFailures = !epochState.getFailedNodeIds().isEmpty();
            } catch (Exception snEx) {
                logger.warn("[SignalResume] Could not read epoch failedNodeIds for runId={} epoch={}, defaulting hasFailures=true: {}",
                        runId, epoch, snEx.getMessage());
            }

            execution.setStatus(hasFailures ? RunStatus.FAILED : RunStatus.COMPLETED);

            reusableTriggerService.resetForNextCycle(run, execution, plan, runId, triggerType, dagTriggerId, hasFailures, epoch);

            // Dispatch error-trigger workflows when the epoch had failures.
            // The synchronous path (executeCycleInternal) calls this after resetForNextCycle;
            // the deferred path was missing it - async agent failures never fired error triggers.
            if (hasFailures && errorTriggerDispatchService != null) {
                try {
                    errorTriggerDispatchService.dispatchEpochFailure(execution);
                } catch (Exception etEx) {
                    logger.warn("[SignalResume] Error dispatching epoch failure trigger for runId={}: {}",
                            runId, etEx.getMessage());
                }
            }

            // Send final snapshot immediately after reset - deferred reset completion is critical
            if (snapshotService != null) {
                try {
                    snapshotService.sendSnapshotImmediate(runId);
                } catch (Exception snapErr) {
                    logger.warn("[SignalResume] Post-reset snapshot failed for runId={}: {}", runId, snapErr.getMessage());
                }
            }

            logger.info("[SignalResume] Deferred reset completed: runId={}, dagTriggerId={}", runId, dagTriggerId);
        } catch (Exception e) {
            logger.error("[SignalResume] Error performing deferred reset: runId={}, error={}", runId, e.getMessage(), e);
        }
    }

    /**
     * Infer trigger type from a trigger ID.
     * Falls back to WEBHOOK as the most common reusable trigger type.
     */
    private TriggerType inferTriggerType(String dagTriggerId) {
        if (dagTriggerId == null) {
            return TriggerType.WEBHOOK;
        }
        // Trigger IDs follow pattern: "trigger:my_label"
        // We can't directly determine type from the ID, so check run metadata or default
        // For signal deferred reset, the type mainly affects streaming emission (WEBHOOK skips streaming)
        return TriggerType.WEBHOOK; // Safe default: skips streaming (avoids errors if no emitter)
    }

    /**
     * Resume in STEP_BY_STEP mode: persist ready nodes to StateSnapshot and emit streaming event.
     * Follows the same pattern as V2StepByStepService.executeNode() which:
     * 1. Persists readyNodes to StateSnapshot (SINGLE SOURCE OF TRUTH for page refresh)
     * 2. Emits streaming readySteps event so frontend shows the ready nodes
     */
    private void resumeStepByStep(String runId, String itemId, String nodeId, String dagTriggerId, int signalEpoch) {
        Set<String> readyNodes = v2StepByStepService.getReadyNodes(runId, itemId, signalEpoch);
        if (readyNodes == null || readyNodes.isEmpty()) {
            logger.info("[SignalResume] STEP_BY_STEP: No ready nodes after signal: runId={}, nodeId={}", runId, nodeId);
            // Still persist empty ready set so StateSnapshot is accurate (DAG-scoped)
            if (dagTriggerId != null) {
                stateSnapshotService.updateReadyNodes(runId, dagTriggerId, signalEpoch, Set.of());
            } else {
                stateSnapshotService.updateReadyNodes(runId, Set.of());
            }

            return;
        }

        logger.info("[SignalResume] STEP_BY_STEP: {} nodes ready after signal: runId={}, nodeId={}, readyNodes={}",
            readyNodes.size(), runId, nodeId, readyNodes);

        // 1. Persist to StateSnapshot (SINGLE SOURCE OF TRUTH, DAG-scoped)
        // Frontend's getRunState API reads from StateSnapshot, so this is critical.
        if (dagTriggerId != null) {
            stateSnapshotService.updateReadyNodes(runId, dagTriggerId, signalEpoch, readyNodes);
        } else {
            stateSnapshotService.updateReadyNodes(runId, readyNodes);
        }

        // 2. Emit streaming "readySteps" event so frontend immediately shows the ready nodes
        // MUST use StepByStepEventService.sendReadyStepsEvent() which sends streaming event type "readySteps".
        // V2ExecutionEventService.emitStepByStepReady() emits WorkflowStatusEvent internally but does NOT
        // send the streaming "readySteps" event that the frontend listens for.
        if (stepByStepEventService != null) {
            try {
                ExecutionCacheManager.LoadedExecution loaded = executionCacheManager.loadTreeAndExecution(runId);
                if (loaded != null && loaded.execution() != null) {
                    stepByStepEventService.sendReadyStepsEvent(loaded.execution(), readyNodes);
                }
            } catch (Exception e) {
                logger.warn("[SignalResume] Failed to emit streaming readySteps: runId={}, error={}", runId, e.getMessage());
                // Non-fatal: frontend will pick up readyNodes via scheduled refresh
            }
        }
    }

    /**
     * Concurrent-sibling guard (CE-SIGRACE-006/007, 2026-06-12): drop from the ready set any
     * node that is a successor of a DIFFERENT signal node whose async resume is still
     * finalizing (resume-pending marker present on the same dag+epoch).
     *
     * <p>Why: the resolver threads (HTTP resolve / timer expiry) flip each signal node
     * COMPLETED in the EpochState BEFORE the resume lock, so when N signals resolve in the
     * same instant, the first resume's getReadyNodes() already returns the siblings'
     * successors - but the sibling resumes have not persisted their resolution outputs yet.
     * Executing those successors here reads missing output and triggers the premature
     * deferred-reset churn. Each deferred successor is executed by its own signal's resume,
     * which runs this same flow under the same per-run lock right after this one - no work
     * is lost, only re-sequenced.
     *
     * <p>Split siblings share OUR nodeId (per-item signals on one node), so they are never
     * filtered here - the split gate above handles per-item sequencing. Fails open: any
     * lookup error returns the ready set unchanged (pre-fix behavior).
     */
    private Set<String> deferSuccessorsOfPendingSiblingResumes(
            Set<String> ready, String runId, SignalWaitEntity resolvedSignal) {
        if (ready == null || ready.isEmpty() || resolvedSignal == null || signalService == null) {
            return ready;
        }
        String dagTriggerId = resolvedSignal.getDagTriggerId();
        if (dagTriggerId == null || resolvedSignal.getEpoch() < 0) {
            return ready;
        }
        try {
            Set<String> pendingNodes = signalService.getPendingResumeNodeIds(
                runId, dagTriggerId, resolvedSignal.getEpoch(), resolvedSignal.getId());
            if (pendingNodes == null || pendingNodes.isEmpty()) {
                return ready;
            }
            Set<String> pendingSiblingNodes = new HashSet<>(pendingNodes);
            pendingSiblingNodes.remove(resolvedSignal.getNodeId());
            if (pendingSiblingNodes.isEmpty()) {
                return ready;
            }
            ExecutionCacheManager.LoadedExecution loaded = executionCacheManager.loadTreeAndExecution(runId);
            if (loaded == null || loaded.tree() == null) {
                return ready;
            }
            Map<String, ExecutionNode> nodeMap = nodeSearchService.buildNodeMapFromAllRoots(loaded.tree());
            Set<String> filtered = new HashSet<>();
            Set<String> deferred = new HashSet<>();
            for (String candidate : ready) {
                ExecutionNode node = nodeMap.get(candidate);
                List<String> predecessors = node != null ? node.getPredecessorIds() : List.of();
                // Predecessor refs of branching signal successors are PORT-QUALIFIED
                // ("core:gate_b:approved" - see ApprovalNodeWirer), so strip the port to the
                // base node id before comparing, same as BaseNode.canExecute.
                boolean siblingSuccessor = predecessors != null && predecessors.stream()
                    .anyMatch(pred -> pendingSiblingNodes.contains(pred)
                        || pendingSiblingNodes.contains(stripPortToBaseNodeId(pred)));
                if (siblingSuccessor) {
                    deferred.add(candidate);
                } else {
                    filtered.add(candidate);
                }
            }
            if (!deferred.isEmpty()) {
                logger.info("[SignalResume] Deferring {} successor(s) of sibling signals whose resume is still finalizing: runId={}, deferred={}, pendingSiblingNodes={}",
                    deferred.size(), runId, deferred, pendingSiblingNodes);
            }
            return filtered;
        } catch (Exception e) {
            logger.warn("[SignalResume] Sibling-successor guard failed, continuing unfiltered: runId={}, error={}",
                runId, e.getMessage());
            return ready;
        }
    }

    /**
     * "core:gate_b:approved" → "core:gate_b"; refs without a port come back unchanged.
     * Mirrors the port-tolerant dependency check in BaseNode.canExecute.
     */
    private String stripPortToBaseNodeId(String predecessorRef) {
        if (predecessorRef == null) {
            return null;
        }
        EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(predecessorRef);
        if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
            return ref.nodeType() + ":" + ref.nodeLabel();
        }
        return predecessorRef;
    }

    /**
     * Filter out trigger nodes from ready set.
     * Triggers must be explicitly fired, not auto-executed.
     */
    private Set<String> filterOutTriggers(Set<String> readyNodes) {
        return readyNodes.stream()
            .filter(nodeId -> !nodeId.startsWith("trigger:"))
            .collect(java.util.stream.Collectors.toSet());
    }

    private Set<String> advanceBackEdgeAfterSignalResolution(
            String runId,
            String itemId,
            String nodeId,
            SignalWaitEntity resolvedSignal,
            int signalEpoch) {
        if (resolvedSignal == null || backEdgeHandler == null || stepByStepContextManager == null) {
            return Set.of();
        }

        try {
            ExecutionCacheManager.LoadedExecution loaded = executionCacheManager.loadTreeAndExecution(runId);
            if (loaded == null || loaded.tree() == null || loaded.execution() == null) {
                return Set.of();
            }

            ExecutionTree tree = loaded.tree();
            WorkflowExecution execution = loaded.execution();
            Map<String, ExecutionNode> nodeMap = nodeSearchService.buildNodeMapFromAllRoots(tree);
            ExecutionNode signalNode = nodeMap.get(nodeId);
            if (signalNode == null || !signalResolutionRoutesToIterate(resolvedSignal, execution.getPlan(), signalNode)) {
                return Set.of();
            }

            int itemIndex = parseItemIndex(itemId);
            ExecutionContext context = stepByStepContextManager.getOrCreateContextWithTriggerData(
                runId + ":" + itemId,
                tree,
                itemId,
                itemIndex,
                nodeId,
                signalEpoch,
                resolvedSignal.getDagTriggerId());
            TriggerItem triggerItem = TriggerItem.create(itemId, itemIndex, context.triggerData());
            NodeExecutionResult signalResult = NodeExecutionResult.success(
                nodeId,
                unwrapSignalOutput(buildSignalResolutionOutput(resolvedSignal)));

            StepByStepExecutionResult backEdgeResult = backEdgeHandler.executeBackEdgeIteration(
                signalNode,
                nodeId,
                signalResult,
                context,
                execution,
                eventService,
                triggerItem,
                context.itemIndex(),
                nodeMap);

            cacheBackEdgeGlobalData(runId, signalEpoch, backEdgeResult.context());
            resetBackEdgeNodes(runId, context.triggerId(), backEdgeResult.context());
            return backEdgeResult.readyNodes() != null ? backEdgeResult.readyNodes() : Set.of();
        } catch (Exception e) {
            logger.warn("[SignalResume] Failed to advance loop back-edge after signal resolution: runId={}, nodeId={}, error={}",
                runId, nodeId, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Advance a loop back-edge for a node that completed OUT-OF-BAND - i.e. via the async agent
     * queue ({@code AgentAsyncCompletionService}) rather than the inline engine. The async (and
     * signal) completion paths never run {@code UnifiedExecutionEngine.executeNodeStepByStep},
     * which is where the inline engine handles the iterate back-edge (the
     * {@code stepNextNodes.isEmpty() && hasBackEdge} block). Without this, an async agent (or
     * classify/guardrail) at the tail of a loop body drops the iterate edge and the loop runs its
     * body exactly once instead of N times.
     *
     * <p>Reuses the same {@code executeBackEdgeIteration} + snapshot reset as the signal-resume
     * path (single source of truth). Returns the loop body ready-nodes (empty when the node has
     * regular successors, has no back-edge, or the loop has terminated) - the caller's ready-node
     * loop re-scans the snapshot anyway, so the return is informational.</p>
     */
    public Set<String> advanceLoopBackEdgeForAsyncCompletedNode(
            String runId,
            String itemId,
            String nodeId,
            int itemIndex,
            int epoch,
            String dagTriggerId,
            NodeExecutionResult nodeResult) {
        if (backEdgeHandler == null || stepByStepContextManager == null) {
            return Set.of();
        }
        try {
            ExecutionCacheManager.LoadedExecution loaded = executionCacheManager.loadTreeAndExecution(runId);
            if (loaded == null || loaded.tree() == null || loaded.execution() == null) {
                return Set.of();
            }
            ExecutionTree tree = loaded.tree();
            WorkflowExecution execution = loaded.execution();
            Map<String, ExecutionNode> nodeMap = nodeSearchService.buildNodeMapFromAllRoots(tree);
            ExecutionNode node = nodeMap.get(nodeId);
            if (node == null) {
                return Set.of();
            }
            // Only a node that ENDS a loop body: no regular successors, but an iterate back-edge.
            // (Mirrors UnifiedExecutionEngine.executeNodeStepByStep's stepNextNodes.isEmpty() guard.)
            if (!node.getNextNodes(nodeResult).isEmpty()) {
                return Set.of();
            }
            ExecutionContext context = stepByStepContextManager.getOrCreateContextWithTriggerData(
                runId + ":" + itemId, tree, itemId, itemIndex, nodeId, epoch, dagTriggerId);
            if (!backEdgeHandler.hasBackEdge(node, context.plan())) {
                return Set.of();
            }
            TriggerItem triggerItem = TriggerItem.create(itemId, itemIndex, context.triggerData());
            StepByStepExecutionResult backEdgeResult = backEdgeHandler.executeBackEdgeIteration(
                node, nodeId, nodeResult, context, execution, eventService, triggerItem, context.itemIndex(), nodeMap);
            cacheBackEdgeGlobalData(runId, epoch, backEdgeResult.context());
            resetBackEdgeNodes(runId, context.triggerId(), backEdgeResult.context());
            logger.info("[SignalResume] Advanced loop back-edge for async-completed node: runId={}, nodeId={}, ready={}",
                runId, nodeId, backEdgeResult.readyNodes());
            return backEdgeResult.readyNodes() != null ? backEdgeResult.readyNodes() : Set.of();
        } catch (Exception e) {
            logger.warn("[SignalResume] Failed to advance loop back-edge for async-completed node: runId={}, nodeId={}, error={}",
                runId, nodeId, e.getMessage(), e);
            return Set.of();
        }
    }

    private boolean signalResolutionRoutesToIterate(SignalWaitEntity resolvedSignal, WorkflowPlan plan, ExecutionNode signalNode) {
        if (plan == null || signalNode == null) {
            return false;
        }

        String nodeId = resolvedSignal.getNodeId();
        String selectedPort = signalNode.isBranchingNode()
            ? resolveSelectedPort(resolvedSignal.getResolution())
            : null;

        return plan.getIterateEdgesForSource(nodeId).stream().anyMatch(edge -> {
            String fromPort = EdgeRefParser.getPort(edge.from());
            if (signalNode.isBranchingNode()) {
                return selectedPort != null && Objects.equals(selectedPort, fromPort);
            }
            return fromPort == null || fromPort.isBlank();
        });
    }

    private Set<String> findResolvedSignalSuccessors(
            String runId,
            String itemId,
            String nodeId,
            SignalWaitEntity resolvedSignal,
            int signalEpoch) {
        if (!shouldExecuteSuccessorsAfterSignal(resolvedSignal)) {
            return Set.of();
        }
        try {
            ExecutionCacheManager.LoadedExecution loaded = executionCacheManager.loadTreeAndExecution(runId);
            if (loaded == null || loaded.tree() == null) {
                return Set.of();
            }

            Map<String, ExecutionNode> nodeMap = nodeSearchService.buildNodeMapFromAllRoots(loaded.tree());
            ExecutionNode signalNode = nodeMap.get(nodeId);
            if (signalNode == null) {
                return Set.of();
            }

            List<ExecutionNode> successors;
            if (signalNode.isBranchingNode()) {
                NodeExecutionResult signalResult = NodeExecutionResult.success(
                    nodeId,
                    unwrapSignalOutput(buildSignalResolutionOutput(resolvedSignal)));
                successors = signalNode.getNextNodes(signalResult);
            } else {
                successors = signalNode.getSuccessors();
            }

            if (successors == null || successors.isEmpty()) {
                return Set.of();
            }

            ExecutionContext readinessContext = buildSignalSuccessorFallbackContext(
                runId, itemId, signalEpoch, resolvedSignal, loaded.tree());
            if (readinessContext == null) {
                logger.info("[SignalResume] Signal successor fallback suppressed because readiness context is unavailable: runId={}, nodeId={}",
                    runId, nodeId);
                return Set.of();
            }

            Set<String> successorIds = new java.util.LinkedHashSet<>();
            for (ExecutionNode successor : successors) {
                if (successor != null && successor.getNodeId() != null
                        && !successor.getNodeId().startsWith("trigger:")
                        && isSignalFallbackSuccessorReady(successor, readinessContext)) {
                    successorIds.add(successor.getNodeId());
                }
            }
            return successorIds;
        } catch (Exception e) {
            logger.warn("[SignalResume] Failed to derive resolved signal successors: runId={}, nodeId={}, error={}",
                runId, nodeId, e.getMessage());
            return Set.of();
        }
    }

    private ExecutionContext buildSignalSuccessorFallbackContext(
            String runId,
            String itemId,
            int signalEpoch,
            SignalWaitEntity resolvedSignal,
            ExecutionTree tree) {
        if (stepByStepContextManager == null || tree == null || resolvedSignal == null) {
            return null;
        }
        String triggerId = resolvedSignal.getDagTriggerId();
        if (triggerId == null || triggerId.isBlank()) {
            return null;
        }
        int itemIndex = parseItemIndex(itemId);
        String contextKey = runId + ":" + itemId + ":signal-successor-fallback:" + signalEpoch + ":" + triggerId;
        return stepByStepContextManager.getOrCreateContextWithTriggerData(
            contextKey,
            tree,
            itemId,
            itemIndex,
            resolvedSignal.getNodeId(),
            signalEpoch,
            triggerId);
    }

    private boolean isSignalFallbackSuccessorReady(ExecutionNode successor, ExecutionContext context) {
        try {
            boolean ready = successor.canExecute(context);
            if (!ready) {
                logger.info("[SignalResume] Signal successor fallback withheld nodeId={} because canExecute=false",
                    successor.getNodeId());
            }
            return ready;
        } catch (Exception e) {
            logger.warn("[SignalResume] Signal successor fallback readiness check failed for nodeId={}: {}",
                successor.getNodeId(), e.getMessage());
            return false;
        }
    }

    private boolean shouldExecuteSuccessorsAfterSignal(SignalWaitEntity resolvedSignal) {
        if (resolvedSignal == null || resolvedSignal.getSignalType() == null) {
            return false;
        }
        SignalResolution resolution = resolvedSignal.getResolution();
        return switch (resolvedSignal.getSignalType()) {
            case USER_APPROVAL -> resolution == SignalResolution.APPROVED
                || resolution == SignalResolution.REJECTED
                || resolution == SignalResolution.TIMEOUT;
            case WAIT_TIMER, WEBHOOK_WAIT -> resolution == SignalResolution.COMPLETED
                || resolution == SignalResolution.TIMEOUT;
            case INTERFACE_SIGNAL -> resolution == SignalResolution.CONTINUE;
            default -> false;
        };
    }

    private Map<String, Object> unwrapSignalOutput(Map<String, Object> wrappedOutput) {
        Object inner = wrappedOutput.get("output");
        if (inner instanceof Map<?, ?> innerMap) {
            Map<String, Object> output = new HashMap<>();
            innerMap.forEach((key, value) -> output.put(String.valueOf(key), value));
            return output;
        }
        return wrappedOutput;
    }

    private void cacheBackEdgeGlobalData(String runId, int epoch, ExecutionContext context) {
        if (context == null || context.getGlobalDataKeys().isEmpty()) {
            return;
        }
        Map<String, Object> globalData = new HashMap<>();
        for (String key : context.getGlobalDataKeys()) {
            if (BackEdgeHandler.BACK_EDGE_RESET_NODES_KEY.equals(key)) {
                continue;
            }
            context.getGlobalData(key).ifPresent(value -> globalData.put(key, value));
        }
        if (globalData.isEmpty()) {
            return;
        }
        if (epoch >= 0) {
            stepByStepContextManager.updateGlobalData(runId, epoch, globalData);
        } else {
            stepByStepContextManager.updateGlobalData(runId, globalData);
        }
    }

    private void resetBackEdgeNodes(String runId, String triggerId, ExecutionContext context) {
        if (context == null) {
            return;
        }
        context.getGlobalData(BackEdgeHandler.BACK_EDGE_RESET_NODES_KEY).ifPresent(value -> {
            if (value instanceof Set<?> rawSet && !rawSet.isEmpty()) {
                Set<String> resetNodeIds = rawSet.stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.toSet());
                if (triggerId != null) {
                    stateSnapshotService.removeNodesFromDag(runId, triggerId, resetNodeIds);
                } else {
                    stateSnapshotService.resetDagSnapshot(runId, resetNodeIds);
                }
                logger.info("[SignalResume] Reset {} loop body nodes after signal back-edge: runId={}, triggerId={}, nodes={}",
                    resetNodeIds.size(), runId, triggerId, resetNodeIds);
            }
        });
    }

    private boolean areAllSplitSignalOutputsPersisted(String runId, String nodeId, int epoch) {
        try {
            long signalCount = signalService.getSignalCountForNodeEpoch(runId, nodeId, epoch);
            if (signalCount <= 1) {
                return true;
            }

            long completedOutputs = stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(
                runId, nodeId, epoch, "COMPLETED");
            boolean allPersisted = completedOutputs >= signalCount;
            if (!allPersisted) {
                logger.info("[SignalResume] Split context: {} of {} signal outputs persisted for nodeId={}, runId={}, epoch={}",
                    completedOutputs, signalCount, nodeId, runId, epoch);
            }
            return allPersisted;
        } catch (Exception e) {
            logger.debug("[SignalResume] Could not verify split signal output persistence for nodeId={}, runId={}, epoch={}: {}",
                nodeId, runId, epoch, e.getMessage());
            return true;
        }
    }

    private boolean persistMissingSplitSignalOutputs(String runId, String nodeId, int epoch, WorkflowRunEntity run) {
        try {
            List<SignalWaitEntity> resolvedSignals = signalService.getResolvedSignalsForNodeEpoch(runId, nodeId, epoch);
            if (resolvedSignals == null || resolvedSignals.isEmpty()) {
                return false;
            }

            List<Integer> completedItemIndices = stepDataRepository.findCompletedItemIndicesByEpoch(
                runId, nodeId, epoch);
            int persisted = 0;
            for (SignalWaitEntity resolvedSignal : resolvedSignals) {
                int itemIndex = parseItemIndex(resolvedSignal.getItemId());
                if (completedItemIndices.contains(itemIndex)) {
                    continue;
                }
                if (persistSignalResolutionOutput(resolvedSignal, run)) {
                    emitEdgesAfterSignalResolution(resolvedSignal, run);
                    restoreSplitContextIfNeeded(resolvedSignal, runId, nodeId);
                    persisted++;
                }
            }

            if (persisted > 0) {
                invalidateReadinessCache(runId, "split-missing-signal-output");
                logger.info("[SignalResume] Split context: persisted {} missing resolved signal outputs for nodeId={}, runId={}, epoch={}",
                    persisted, nodeId, runId, epoch);
            }
            return persisted > 0;
        } catch (Exception e) {
            logger.warn("[SignalResume] Could not persist missing split signal outputs for nodeId={}, runId={}, epoch={}: {}",
                nodeId, runId, epoch, e.getMessage());
            return false;
        }
    }

    /**
     * Filter out nodes that have active (unresolved) signals in the DB.
     *
     * When a signal resolves and we calculate ready nodes, ReadyNodeCalculator may
     * return nodes that yielded AWAITING_SIGNAL during initial execution (e.g.,
     * interface nodes in parallel fork branches). These nodes have pending signals
     * and should NOT be re-executed - they need their own signal resolution.
     *
     * Without this filter, the while-loop re-executes awaiting nodes endlessly
     * (each execution yields AWAITING_SIGNAL again, creating duplicate signals).
     *
     * IMPORTANT: Uses the signal DB table (not the snapshot's awaitingSignalNodeIds)
     * because the snapshot flat view can be stale during concurrent signal resolution.
     * Must be epoch-scoped. A signal from epoch N-1 (e.g., interface:dashboard
     * still PENDING from the previous trigger fire) must NOT prevent the same node from
     * executing in epoch N. Without epoch scoping, the node is permanently blocked once
     * any prior epoch leaves a stale signal in the DB.
     *
     * Uses the signal DB table (not the snapshot's awaitingSignalNodeIds) because the
     * snapshot flat view can be stale during concurrent signal resolution.
     */
    private Set<String> filterOutAwaitingSignalNodes(Set<String> readyNodes, String runId, int epoch) {
        if (readyNodes.isEmpty()) {
            return readyNodes;
        }
        // Query the actual signals table for active (PENDING/CLAIMED) signals in this epoch
        List<SignalWaitEntity> activeSignals = epoch >= 0
            ? signalService.getActiveSignals(runId, epoch)
            : signalService.getActiveSignals(runId);
        if (activeSignals == null || activeSignals.isEmpty()) {
            return readyNodes;
        }
        Set<String> nodesWithActiveSignals = activeSignals.stream()
            .map(SignalWaitEntity::getNodeId)
            .collect(java.util.stream.Collectors.toSet());
        Set<String> filtered = readyNodes.stream()
            .filter(nodeId -> !nodesWithActiveSignals.contains(nodeId))
            .collect(java.util.stream.Collectors.toSet());
        if (filtered.size() < readyNodes.size()) {
            logger.info("[SignalResume] Filtered out {} nodes with active signals (epoch={}) from ready set: {}",
                readyNodes.size() - filtered.size(), epoch,
                readyNodes.stream().filter(nodesWithActiveSignals::contains).toList());
        }
        return filtered;
    }

    /**
     * Check if run is in step-by-step mode.
     */
    private boolean isStepByStepMode(WorkflowRunEntity run) {
        return run.isStepByStepMode();
    }

    // ========================================================================
    // DISTRIBUTED LOCK HELPERS
    // ========================================================================

    /**
     * Acquire a Redis distributed lock with spin-wait. Uses SETNX + TTL for safe lock with automatic expiry.
     * This method never times out on ordinary contention: a signal resume must not run without
     * the per-run mutex, otherwise sibling signal resumes can both execute the same downstream node.
     *
     * @param key     Redis key for the lock
     * @param owner   Unique owner ID (prevents releasing another instance's lock)
     * @param ttl     Lock auto-expiry (safety net for crashes)
     * @param warnInterval log interval while waiting for the lock
     * @return true if lock acquired, false only if interrupted or Redis fails
     */
    private boolean tryAcquireDistributedLock(String key, String owner, Duration ttl, Duration warnInterval) {
        long nextWarnAt = System.currentTimeMillis() + warnInterval.toMillis();
        try {
            while (true) {
                Boolean acquired = redis.opsForValue().setIfAbsent(key, owner, ttl);
                if (Boolean.TRUE.equals(acquired)) {
                    return true;
                }
                long now = System.currentTimeMillis();
                if (now >= nextWarnAt) {
                    logger.warn("[SignalResume] Waiting for distributed lock: key={}, ttlSeconds={}",
                        key, ttl.getSeconds());
                    nextWarnAt = now + warnInterval.toMillis();
                }
                Thread.sleep(SIGNAL_LOCK_RETRY_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[SignalResume] Lock acquisition interrupted for key={}", key);
        } catch (Exception e) {
            logger.warn("[SignalResume] Redis lock acquisition failed: key={}, error={}",
                key, e.getMessage());
        }
        return false;
    }

    /** Lua script for atomic compare-and-delete lock release. */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class);

    /**
     * Release a Redis distributed lock, only if the current instance owns it.
     * Uses an atomic Lua script (GET + compare + DELETE) to prevent releasing
     * another instance's lock that was acquired after TTL expiry.
     */
    private void releaseDistributedLock(String key, String owner) {
        try {
            redis.execute(RELEASE_LOCK_SCRIPT, List.of(key), owner);
        } catch (Exception e) {
            logger.warn("[SignalResume] Redis lock release failed (will auto-expire): key={}, error={}",
                key, e.getMessage());
        }
    }

    /**
     * Find successor node IDs for a signal node by traversing the execution tree directly.
     *
     * <p>In split context, ReadyNodeCalculator operates at the node level and marks a node
     * as "completed" if it has any output from a previous item. This means subsequent split
     * items get an empty ready set even though their successors need to execute independently.
     *
     * <p>This method bypasses ReadyNodeCalculator by loading the execution tree and finding
     * the direct successors of the signal node.
     *
     * @param runId  the workflow run ID
     * @param nodeId the signal node whose successors we need
     * @return set of successor node IDs, or empty set if not found
     */
    /**
     * Remove nodes that already reached a terminal status (COMPLETED, FAILED, or SKIPPED)
     * in this epoch. Used to stop a resolve-all burst from re-triggering a split successor
     * that a prior (lock-serialized) resume already executed - the bug where an all-failed
     * split successor (node-level FAILED) was re-run once per resume, duplicating its side
     * effects (re-sent emails, re-hit HTTP) and inflating its FAILED count.
     *
     * <p>Epoch-scoped via the signal's DAG trigger when available (parallel-epoch safe);
     * falls back to the flat union across DAGs ONLY when the signal carries no DAG trigger.
     * epoch 0 is a real, common (first-fire) epoch, so the epoch-scoped branch uses
     * {@code epoch >= 0} - gating on {@code > 0} would silently downgrade epoch 0 to the
     * cross-epoch flat union and could strand a run under concurrent epochs. Intentionally
     * NOT applied to the back-edge/loop path, where a node legitimately re-runs across
     * iterations. Fails open (returns the input unfiltered) on any snapshot error.
     */
    private Set<String> filterOutAlreadyTerminalNodes(Set<String> nodes, String runId,
                                                      SignalWaitEntity signal, int epoch) {
        if (nodes == null || nodes.isEmpty()) {
            return nodes == null ? Set.of() : nodes;
        }
        try {
            StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
            String triggerId = signal != null ? signal.getDagTriggerId() : null;
            // Canonical terminal-set + epoch gating lives on StateSnapshot (shared with
            // AgentAsyncCompletionService's per-item successor filter - keep them aligned there).
            Set<String> terminal = snapshot.getTerminalNodeIds(triggerId, epoch);
            Set<String> filtered = new java.util.HashSet<>(nodes);
            if (filtered.removeIf(terminal::contains)) {
                logger.info("[SignalResume] Skipping already-terminal split successors (prior resume ran them): runId={}, epoch={}, remaining={}",
                    runId, epoch, filtered);
            }
            return filtered;
        } catch (Exception e) {
            logger.warn("[SignalResume] Could not filter terminal successors (proceeding unfiltered): runId={}, error={}",
                runId, e.getMessage());
            return nodes;
        }
    }

    private Set<String> findSplitContextSuccessors(String runId, String nodeId) {
        try {
            ExecutionCacheManager.LoadedExecution loaded = executionCacheManager.loadTreeAndExecution(runId);
            if (loaded == null || loaded.tree() == null) {
                logger.warn("[SignalResume] Cannot find split successors - execution not available: runId={}", runId);
                return Set.of();
            }

            Map<String, ExecutionNode> nodeMap = nodeSearchService.buildNodeMapFromAllRoots(loaded.tree());
            ExecutionNode signalNode = nodeMap.get(nodeId);
            if (signalNode == null) {
                logger.warn("[SignalResume] Signal node not found in tree: runId={}, nodeId={}", runId, nodeId);
                return Set.of();
            }

            List<ExecutionNode> successors = signalNode.getSuccessors();
            if (successors == null || successors.isEmpty()) {
                return Set.of();
            }

            Set<String> successorIds = successors.stream()
                .map(ExecutionNode::getNodeId)
                .collect(java.util.stream.Collectors.toSet());

            logger.debug("[SignalResume] Found split context successors for nodeId={}: {}", nodeId, successorIds);
            return successorIds;
        } catch (Exception e) {
            logger.error("[SignalResume] Error finding split context successors: runId={}, nodeId={}, error={}",
                runId, nodeId, e.getMessage(), e);
            return Set.of();
        }
    }

    public static final class SignalResumeRequestAttributes
            implements org.springframework.web.context.request.RequestAttributes {
        private final SignalResumeHeaderRequest request;

        public SignalResumeRequestAttributes(Map<String, String> headers) {
            this.request = new SignalResumeHeaderRequest(headers);
        }

        public SignalResumeHeaderRequest getRequest() {
            return request;
        }

        @Override
        public Object getAttribute(String name, int scope) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value, int scope) {
            // No request attributes are needed; only headers are exposed.
        }

        @Override
        public void removeAttribute(String name, int scope) {
            // No-op.
        }

        @Override
        public String[] getAttributeNames(int scope) {
            return new String[0];
        }

        @Override
        public void registerDestructionCallback(String name, Runnable callback, int scope) {
            // No request lifecycle callbacks are needed for this synthetic context.
        }

        @Override
        public Object resolveReference(String key) {
            return null;
        }

        @Override
        public String getSessionId() {
            return "signal-resume";
        }

        @Override
        public Object getSessionMutex() {
            return this;
        }
    }

    public static final class SignalResumeHeaderRequest {
        private final Map<String, String> headers;

        private SignalResumeHeaderRequest(Map<String, String> headers) {
            this.headers = new HashMap<>(headers);
        }

        public String getHeader(String name) {
            return headers.get(name);
        }
    }
}

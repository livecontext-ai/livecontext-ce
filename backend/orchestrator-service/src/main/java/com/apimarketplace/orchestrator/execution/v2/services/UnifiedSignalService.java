package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.SignalConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity.SignalWaitStatus;
import com.apimarketplace.orchestrator.cache.RedisCacheKeys;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.services.events.SignalsCancelledEvent;
import com.apimarketplace.orchestrator.services.events.WorkflowApprovalPendingEvent;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import jakarta.annotation.PostConstruct;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Unified service for managing signal waits across the workflow engine.
 *
 * Supports 4 signal types: WAIT_TIMER, USER_APPROVAL, WEBHOOK_WAIT, INTERFACE_SIGNAL.
 *
 * Design:
 * - In-memory timers via {@link SignalTimerScheduler} for precise expiration (no polling lag).
 * - DB remains source of truth - timers are a performance optimization.
 * - Claim-before-process for idempotent multi-instance processing.
 * - Event-driven resume: resolveSignal() publishes SignalResolvedEvent,
 *   consumed by SignalResumeService via @TransactionalEventListener(AFTER_COMMIT).
 * - Crash recovery handled by {@link SignalRecoveryService} at startup (re-schedules timers, resumes orphans).
 * - Completely separate from PendingSignalDbService (step-by-step mode).
 *
 * @see SignalTimerScheduler
 * @see SignalResumeService
 * @see SignalResolvedEvent
 */
@Service
public class UnifiedSignalService {

    private static final Logger logger = LoggerFactory.getLogger(UnifiedSignalService.class);
    private static final Duration SIGNAL_RESUME_PENDING_TTL = Duration.ofMinutes(2);

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    private final SignalWaitRepository signalWaitRepository;
    private final StateSnapshotService stateSnapshotService;
    private final ApplicationEventPublisher eventPublisher;
    private final SignalTimerScheduler timerScheduler;
    private final SignalResumeRedisPublisher redisPublisher;
    private final Clock clock;
    private final UnifiedSignalService self;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${orchestrator.scheduled.signal-maintenance-pollers:true}")
    private boolean signalMaintenancePollersEnabled = true;

    public UnifiedSignalService(
            SignalWaitRepository signalWaitRepository,
            StateSnapshotService stateSnapshotService,
            ApplicationEventPublisher eventPublisher,
            SignalTimerScheduler timerScheduler,
            SignalResumeRedisPublisher redisPublisher,
            Clock clock,
            @Lazy UnifiedSignalService self) {
        this.signalWaitRepository = signalWaitRepository;
        this.stateSnapshotService = stateSnapshotService;
        this.eventPublisher = eventPublisher;
        this.timerScheduler = timerScheduler;
        this.redisPublisher = redisPublisher;
        this.clock = clock;
        this.self = self != null ? self : this;
    }

    @PostConstruct
    void init() {
        timerScheduler.setExpirationCallback(this::onTimerExpired);
    }

    // ========================================================================
    // REGISTER
    // ========================================================================

    /**
     * Register a new signal wait.
     * Called by WaitNode, UserApprovalNode, or WebhookWaitNode during execution.
     *
     * @param runId workflow run ID
     * @param itemId workflow item ID (e.g., "0")
     * @param nodeId the node registering the signal
     * @param dagTriggerId the DAG trigger that owns this node (for DAG-scoped operations)
     * @param epoch current epoch for this DAG
     * @param signalType type of signal
     * @param signalConfig type-specific configuration
     * @param splitItemData persisted split context for restart recovery (may be null)
     * @return the created entity
     */
    public SignalWaitEntity registerSignal(
            String runId,
            String itemId,
            String nodeId,
            String dagTriggerId,
            int epoch,
            SignalType signalType,
            Map<String, Object> signalConfig,
            Map<String, Object> splitItemData) {
        // Delegate through the self-proxy so the @Transactional boundary on the full overload applies.
        return self.registerSignal(runId, itemId, nodeId, dagTriggerId, epoch,
            signalType, signalConfig, splitItemData, null);
    }

    /**
     * Register a new signal wait, carrying a resolved approval-context string.
     * Same contract as the 8-arg overload, plus:
     *
     * @param approvalContext resolved display text (USER_APPROVAL contextTemplate) shown to the
     *                        approver as {@code approvalContext} in the signals payload (may be null)
     * @return the created entity
     */
    @Transactional
    public SignalWaitEntity registerSignal(
            String runId,
            String itemId,
            String nodeId,
            String dagTriggerId,
            int epoch,
            SignalType signalType,
            Map<String, Object> signalConfig,
            Map<String, Object> splitItemData,
            String approvalContext) {

        // Idempotency: if a PENDING signal already exists for this (run, node, item, epoch), return it.
        // If the existing signal is already resolved (from a previous execution or a step rerun that
        // CANCELLED it), it must be replaced so a fresh signal can be created for this execution.
        String effectiveItemId = itemId != null ? itemId : "0";
        Optional<SignalWaitEntity> existing =
            signalWaitRepository.findByRunIdAndNodeIdAndItemIdAndEpoch(runId, nodeId, effectiveItemId, epoch);
        Long replaceResolvedId = null;
        if (existing.isPresent()) {
            SignalWaitEntity existingSignal = existing.get();
            if (existingSignal.getStatus() == SignalWaitStatus.PENDING
                    || existingSignal.getStatus() == SignalWaitStatus.CLAIMED) {
                logger.info("[UnifiedSignal] Signal already registered (idempotent): runId={}, nodeId={}, epoch={}, id={}",
                    runId, nodeId, epoch, existingSignal.getId());
                return existingSignal;
            }
            // Already resolved/expired - replace with a fresh signal (rerun scenario).
            // CRITICAL: the stale row must be deleted inside insertOrFindExisting's
            // REQUIRES_NEW transaction, NOT here. Deleting it in this (outer) transaction
            // and inserting the same (run_id, node_id, item_id, epoch) unique key in the
            // suspended-inner transaction self-deadlocks: the inner INSERT waits on the
            // outer's uncommitted DELETE until lock_timeout kills the re-registration
            // (observed live: rerun upstream of an approval → re-step → node FAILED with
            // "canceling statement due to lock timeout" and the signal never re-registered).
            logger.info("[UnifiedSignal] Replacing resolved signal for rerun: runId={}, nodeId={}, epoch={}, id={}, status={}",
                runId, nodeId, epoch, existingSignal.getId(), existingSignal.getStatus());
            replaceResolvedId = existingSignal.getId();
        }

        // Build entity via factory
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setRunId(runId);
        entity.setItemId(effectiveItemId);
        entity.setNodeId(nodeId);
        entity.setDagTriggerId(dagTriggerId);
        entity.setEpoch(epoch);
        entity.setSignalType(signalType);
        entity.setSignalConfig(signalConfig);
        entity.setStatus(SignalWaitStatus.PENDING);
        entity.setCreatedAt(clock.instant());

        // Set expiration based on signal type
        if (signalType == SignalType.WAIT_TIMER) {
            long durationMs = SignalConfig.getDurationMs(signalConfig);
            entity.setExpiresAt(clock.instant().plusMillis(durationMs));
        } else {
            long timeoutMs = SignalConfig.getTimeoutMs(signalConfig);
            if (timeoutMs > 0) {
                entity.setExpiresAt(clock.instant().plusMillis(timeoutMs));
            }
        }

        // Persist split context for restart recovery
        if (splitItemData != null && !splitItemData.isEmpty()) {
            entity.setSplitItemData(splitItemData);
        }

        // Persist the resolved approval context (USER_APPROVAL contextTemplate), surfaced to the
        // approver. Plain display text; survives restart/resume since each sink re-reads it.
        if (approvalContext != null && !approvalContext.isBlank()) {
            entity.setApprovalContext(approvalContext);
        }

        // Set blocking flag from signal config
        entity.setBlocking(SignalConfig.isBlocking(signalConfig));

        // Set correlationId for AGENT_EXECUTION signals (used for async result lookup)
        if (signalType == SignalType.AGENT_EXECUTION) {
            String correlationId = SignalConfig.getCorrelationId(signalConfig);
            entity.setCorrelationId(correlationId);
        }

        // Race-tolerant insert. The SELECT-then-INSERT idempotency check above is not
        // serializable across concurrent transactions, so two parallel fires (or engine
        // re-traversals) can both pass the check and both reach INSERT, hitting the
        // uq_signal_waits unique constraint on (run_id, node_id, item_id, epoch).
        // self.insertOrFindExisting wraps the save in REQUIRES_NEW + recovers on
        // DataIntegrityViolationException by returning the row inserted by the winner.
        // When a stale resolved row occupies the unique key (rerun scenario), it is
        // deleted in that same REQUIRES_NEW transaction right before the insert.
        entity = self.insertOrFindExisting(entity, replaceResolvedId);

        // P2a notification-system: publish APPROVAL_PENDING for USER_APPROVAL only.
        // The listener (NotificationEmitter.onApprovalPending) ON-CONFLICT-DO-NOTHINGs
        // by source_id=signalWaitId, so re-publishes from idempotent re-traversal or
        // race-recovery (insertOrFindExisting returning existing row) are safe.
        // AFTER_COMMIT semantics ensure rollback of the outer registerSignal tx
        // suppresses the event - see WorkflowApprovalPendingEvent javadoc.
        if (signalType == SignalType.USER_APPROVAL) {
            eventPublisher.publishEvent(new WorkflowApprovalPendingEvent(
                    runId,
                    entity.getId(),
                    epoch,
                    entity.getCreatedAt(),
                    entity.getExpiresAt()));
        }

        // Schedule in-memory timer for precise expiration
        if (entity.getExpiresAt() != null) {
            timerScheduler.schedule(entity.getId(), entity.getExpiresAt());
        }

        // Update StateSnapshot: mark node as awaiting signal (epoch-scoped)
        // Only for blocking signals - non-blocking interfaces complete via SUCCESS return
        if (entity.isBlocking()) {
            if (dagTriggerId != null) {
                stateSnapshotService.markNodeAwaitingSignal(runId, dagTriggerId, epoch, nodeId);
            } else {
                stateSnapshotService.markNodeAwaitingSignal(runId, nodeId);
            }
        }

        logger.info("[UnifiedSignal] Signal registered: runId={}, nodeId={}, type={}, epoch={}, expiresAt={}, id={}",
            runId, nodeId, signalType, epoch, entity.getExpiresAt(), entity.getId());

        return entity;
    }

    /**
     * Insert a freshly-built {@link SignalWaitEntity} or, if a concurrent fire just
     * inserted a row with the same {@code (run_id, node_id, item_id, epoch)} tuple,
     * return that pre-existing row instead of bubbling the unique-constraint violation.
     *
     * <p>Runs in {@link Propagation#REQUIRES_NEW} so the failed INSERT does not poison
     * the caller's transaction (the rollback-only flag stays inside this sub-tx). The
     * recovery query is safe because it runs after the violation rolled back the sub-tx,
     * meaning the winning row is already committed and visible.
     *
     * <p>{@code replaceResolvedId} (nullable) is the id of a stale resolved/cancelled
     * signal row occupying the same unique key (rerun scenario). It is deleted HERE, in
     * the same REQUIRES_NEW transaction as the insert, so delete+insert commit atomically.
     * Deleting it in the caller's (suspended) transaction instead would self-deadlock:
     * this transaction's INSERT would block on the caller's uncommitted DELETE of the
     * unique key until {@code lock_timeout}. Signal ids are never reused and no code path
     * reopens a resolved row, so deleting by id cannot drop a live signal; if a concurrent
     * register already replaced the row, the delete is a no-op and the INSERT falls into
     * the unique-violation recovery below.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SignalWaitEntity insertOrFindExisting(SignalWaitEntity newEntity, Long replaceResolvedId) {
        try {
            if (replaceResolvedId != null) {
                signalWaitRepository.findById(replaceResolvedId)
                    .ifPresent(stale -> {
                        signalWaitRepository.delete(stale);
                        signalWaitRepository.flush();
                    });
            }
            return signalWaitRepository.saveAndFlush(newEntity);
        } catch (DataIntegrityViolationException ex) {
            String runId = newEntity.getRunId();
            String nodeId = newEntity.getNodeId();
            String itemId = newEntity.getItemId();
            int epoch = newEntity.getEpoch();
            SignalWaitEntity winner = signalWaitRepository
                .findByRunIdAndNodeIdAndItemIdAndEpoch(runId, nodeId, itemId, epoch)
                .orElseThrow(() -> ex);
            logger.info("[UnifiedSignal] Concurrent register lost race: returning winner "
                    + "runId={}, nodeId={}, itemId={}, epoch={}, winnerId={}",
                    runId, nodeId, itemId, epoch, winner.getId());
            return winner;
        }
    }

    // ========================================================================
    // RESOLVE (claim-before-process + event-driven decoupling)
    // ========================================================================

    /**
     * Resolve a signal. Uses claim-before-process pattern:
     * 1. Cancel in-memory timer (no-op if already fired)
     * 2. Claim: atomic UPDATE WHERE status='PENDING'
     * 3. Resolve: atomic UPDATE WHERE status='CLAIMED'
     * 4. Update snapshot
     * 5. Publish SignalResolvedEvent (consumed after TX commit)
     *
     * @param signalId the signal ID to resolve
     * @param resolution the resolution outcome
     * @param resolutionData optional resolution data (e.g., approval comment)
     * @param resolvedBy who resolved the signal (userId or instanceId)
     * @return true if successfully resolved, false if signal was already claimed/resolved
     */
    @Transactional
    public boolean resolveSignal(
            Long signalId,
            SignalResolution resolution,
            Map<String, Object> resolutionData,
            String resolvedBy) {

        // Cancel in-memory timer (no-op if already fired or doesn't exist)
        timerScheduler.cancel(signalId);

        Instant now = clock.instant();

        // Closed-epoch zombie guard: a signal whose owning epoch is no longer active
        // (epoch was pruned without cancelling its pending signals - see fixes in
        // ReusableTriggerService.closeEpochIfCompleteForSbs / resetRunOnFailure)
        // would otherwise be resolved here, causing SignalResumeService to re-mark
        // the wait node as ready in a pruned epoch (no completion record left) and
        // re-execute it forever. Hard-cancel the zombie instead - covers BOTH the
        // Redis ZSET path (onTimerExpired) and the DB poller path (pollExpiredTimers).
        if (isClosedEpochZombie(signalId)) {
            // P2a notification-system: capture USER_APPROVAL+runId BEFORE cancel so the
            // bell-side listener can clear the matching APPROVAL_PENDING row. Done via a
            // single projection query (no entity load - see findEpochInfoById rationale).
            SignalType zombieType = signalWaitRepository.findSignalTypeById(signalId).orElse(null);
            String zombieRunId = signalWaitRepository.findEpochInfoById(signalId)
                    .map(SignalWaitRepository.EpochInfo::runId).orElse(null);
            int cancelled = signalWaitRepository.cancelById(signalId, now);
            if (cancelled > 0 && zombieType == SignalType.USER_APPROVAL && zombieRunId != null) {
                eventPublisher.publishEvent(new SignalsCancelledEvent(zombieRunId, List.of(signalId)));
            }
            logger.warn("[UnifiedSignal] Hard-cancelled zombie signal id={} (resolveBy={}): "
                    + "epoch no longer active, cancelled={} row(s)",
                    signalId, resolvedBy, cancelled);
            return false;
        }

        // Step 1: Claim
        int claimed = signalWaitRepository.claimSignal(signalId, now, resolvedBy);
        if (claimed == 0) {
            logger.debug("[UnifiedSignal] Signal {} already claimed or resolved, skipping", signalId);
            return false;
        }

        SignalWaitRepository.ResumeInfo resumeInfo = signalWaitRepository.findResumeInfoById(signalId).orElse(null);
        markSignalResumePending(signalId, resumeInfo);

        // Step 2: Resolve
        int resolved = signalWaitRepository.resolveClaimedSignal(
            signalId, resolution.name(), now, resolvedBy);
        if (resolved == 0) {
            logger.warn("[UnifiedSignal] Signal {} claim succeeded but resolve failed (unexpected state)", signalId);
            clearSignalResumePending(signalId, resumeInfo);
            return false;
        }

        // Step 3: Reload entity for event publishing
        SignalWaitEntity entity = signalWaitRepository.findById(signalId).orElse(null);
        if (entity == null) {
            logger.error("[UnifiedSignal] Signal {} not found after resolve", signalId);
            clearSignalResumePending(signalId, resumeInfo);
            return false;
        }

        // Store resolution data on entity
        entity.setResolution(resolution);
        entity.setResolvedAt(now);
        entity.setResolvedBy(resolvedBy);
        entity.setResolutionData(resolutionData);
        signalWaitRepository.save(entity);

        // Step 4: Update StateSnapshot (epoch-scoped when DAG info available)
        // Compute signal wait duration generically: time between signal creation and resolution.
        // This gives accurate timing for ALL signal types (UserApproval, Interface, Wait, Webhook).
        long waitDurationMs = entity.getCreatedAt() != null
                ? Duration.between(entity.getCreatedAt(), now).toMillis()
                : 0L;

        // Split context: check if OTHER ITEMS in the SAME (dag, epoch) still have pending
        // signals for this node - that's the only legit reason to keep the node in
        // awaitingSignalNodeIds (split fan-out: multiple items, same epoch, same node).
        //
        // Regression 2026-05-06 (run_<id>): the prior run+node-wide
        // findActiveByRunIdAndNodeId conflated "another split item, same epoch" with
        // "another epoch entirely, same wait node" on a reusable-trigger run, fed
        // keepInAwaiting=true into the per-epoch EpochState.resolveAwaitingSignal, and
        // skipped the wait→completed transition for the resolving epoch. Successor
        // (echo) never became ready, watchdog FAILed the run after 5 min idle.
        //
        // Scope by (run, node, dag, epoch) so different-epoch siblings don't poison
        // each other. Also gate on itemId - non-split waits always have itemId="0"
        // (the default) so the count would always be ≤ 1 and the boolean would always
        // be false; explicit check makes the intent obvious.
        boolean hasRemainingSignals = false;
        if (entity.getDagTriggerId() != null) {
            // Subtract 1 for the just-resolved entity itself (its status is now RESOLVED,
            // so it won't be counted by countActive..., but defensive subtract anyway in
            // case of cross-replica view skew).
            long activeSiblings = signalWaitRepository.countActiveByRunIdAndNodeIdAndDagAndEpoch(
                entity.getRunId(), entity.getNodeId(), entity.getDagTriggerId(), entity.getEpoch());
            hasRemainingSignals = activeSiblings > 0;
        }

        boolean cancelled = entity.getResolution() == SignalResolution.CANCELLED;
        if (cancelled && entity.getDagTriggerId() != null) {
            stateSnapshotService.skipAwaitingSignal(
                entity.getRunId(), entity.getDagTriggerId(), entity.getEpoch(), entity.getNodeId());
        } else if (cancelled) {
            stateSnapshotService.skipAwaitingSignal(entity.getRunId(), entity.getNodeId());
        } else if (entity.getDagTriggerId() != null) {
            stateSnapshotService.resolveAwaitingSignal(
                entity.getRunId(), entity.getDagTriggerId(), entity.getEpoch(), entity.getNodeId(),
                waitDurationMs, hasRemainingSignals);
        } else {
            stateSnapshotService.resolveAwaitingSignal(entity.getRunId(), entity.getNodeId(), waitDurationMs);
        }

        // Step 5: Publish event (consumed by SignalResumeService after TX commit)
        eventPublisher.publishEvent(new SignalResolvedEvent(this, entity));

        // Step 6: Publish to Redis pub/sub for cross-instance notification AFTER TX commit.
        // Must be deferred - if published inside the transaction, remote listeners could
        // read the signal before the RESOLVED status is committed, causing data inconsistency.
        // Fallback to immediate publish when called outside a transaction (e.g. unit tests,
        // manual admin paths) - without an active tx there is nothing to wait for.
        // Phase A2 (archi-refoundation 2026-05-04) - DRY plan §0.3:
        // delegate to the extracted helper. Behavior unchanged.
        com.apimarketplace.orchestrator.services.transaction.TransactionalHelper
                .runAfterCommitOrNow(() -> redisPublisher.publish(entity));

        logger.info("[UnifiedSignal] Signal resolved: id={}, runId={}, nodeId={}, resolution={}, resolvedBy={}",
            signalId, entity.getRunId(), entity.getNodeId(), resolution, resolvedBy);

        return true;
    }

    // ========================================================================
    // CANCEL
    // ========================================================================

    /**
     * Cancel all active signals for a run.
     *
     * <p>Plan v4 E2E5/SF2 - {@code REQUIRES_NEW} propagation. Callers include
     * {@code OrchestrationRecoveryService.cancelRun} which fires from an
     * {@code afterCommit} hook on an outer tx that is already finishing.
     * In Spring's tx synchronization, "after commit" runs while the tx
     * manager still considers the outer tx active but no longer accepts
     * modifications - invoking this method under {@code REQUIRED} produces
     * <em>"Executing an update/delete query"</em> {@code InvalidDataAccessApiUsageException}
     * warnings and the cancel never actually commits.
     * {@code REQUIRES_NEW} starts an independent tx that completes
     * regardless of the outer's state.
     *
     * <p><b>Semantic change for in-active-tx callers</b> (e.g.
     * {@code WorkflowResumeService.cancelWorkflow},
     * {@code ReusableTriggerService.resetForNextCycle}): the signal cancel is
     * now <em>committed independently</em> of the outer tx. Outer-tx rollback
     * (V181 trip later in the method, advisory-lock release-then-rollback,
     * exception in post-cancel code) will NOT undo the cancel. This is the
     * intended forward-only semantic, mirroring the afterCommit path: a
     * cancelled signal stays cancelled even if the surrounding state mutation
     * fails. Callers that previously relied on rollback-cancel coupling
     * (i.e. "if my run reset fails, my signals should un-cancel") must
     * adapt: re-issue the signal explicitly on rollback if needed. Today
     * no caller in the codebase has this dependency - verified by tracing
     * all four callers (cancelRun via afterCommit, cancelWorkflow,
     * resetForNextCycle, WorkflowSignalController) - but a future caller
     * MUST be aware of this contract.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cancelByRun(String runId) {
        // Collect active signal IDs before DB cancel (for in-memory timer cleanup)
        List<Long> activeIds = signalWaitRepository.findActiveSignalIdsByRunId(runId);
        // P2a: harvest USER_APPROVAL subset BEFORE bulk cancel - IDs needed by the
        // bell-side cancel listener since the bulk UPDATE bypasses SignalResolvedEvent.
        List<Long> userApprovalIds = signalWaitRepository.findActiveUserApprovalIdsByRunId(runId);

        int cancelled = signalWaitRepository.cancelByRunId(runId, clock.instant());

        // Cancel in-memory timers
        timerScheduler.cancelAll(activeIds);

        if (cancelled > 0 && !userApprovalIds.isEmpty()) {
            eventPublisher.publishEvent(new SignalsCancelledEvent(runId, userApprovalIds));
        }

        if (cancelled > 0) {
            logger.info("[UnifiedSignal] Cancelled {} signals for runId={}", cancelled, runId);
        }
        return cancelled;
    }

    /**
     * Cancel all active signals for a specific DAG and epoch.
     */
    @Transactional
    public int cancelByDagAndEpoch(String runId, String dagTriggerId, int epoch) {
        // Collect active signal IDs before DB cancel (for in-memory timer cleanup)
        List<Long> activeIds = signalWaitRepository.findActiveSignalIdsByDagAndEpoch(runId, dagTriggerId, epoch);
        // P2a: USER_APPROVAL subset for bell-side cancel listener.
        List<Long> userApprovalIds =
                signalWaitRepository.findActiveUserApprovalIdsByDagAndEpoch(runId, dagTriggerId, epoch);

        int cancelled = signalWaitRepository.cancelByDagAndEpoch(runId, dagTriggerId, epoch, clock.instant());

        // Cancel in-memory timers
        timerScheduler.cancelAll(activeIds);

        if (cancelled > 0 && !userApprovalIds.isEmpty()) {
            eventPublisher.publishEvent(new SignalsCancelledEvent(runId, userApprovalIds));
        }

        if (cancelled > 0) {
            logger.info("[UnifiedSignal] Cancelled {} signals for runId={}, dagTriggerId={}, epoch={}",
                cancelled, runId, dagTriggerId, epoch);
        }
        return cancelled;
    }

    /**
     * Cancel active blocking signals for a specific DAG and epoch.
     * Non-blocking signals persist across trigger cycles.
     */
    @Transactional
    public int cancelBlockingByDagAndEpoch(String runId, String dagTriggerId, int epoch) {
        // P2a: harvest USER_APPROVAL+blocking subset BEFORE bulk cancel.
        // (No findActiveBlockingSignalIds method exists for the timer-cleanup path -
        //  blocking cancels typically happen on epoch close where in-memory timers
        //  would already be GC'd; we pre-fetch only the bell-relevant subset here.)
        List<Long> userApprovalIds =
                signalWaitRepository.findActiveBlockingUserApprovalIdsByDagAndEpoch(runId, dagTriggerId, epoch);

        int cancelled = signalWaitRepository.cancelBlockingByDagAndEpoch(runId, dagTriggerId, epoch, clock.instant());

        if (cancelled > 0 && !userApprovalIds.isEmpty()) {
            eventPublisher.publishEvent(new SignalsCancelledEvent(runId, userApprovalIds));
        }

        if (cancelled > 0) {
            logger.info("[UnifiedSignal] Cancelled {} blocking signals for runId={}, dagTriggerId={}, epoch={}",
                cancelled, runId, dagTriggerId, epoch);
        }
        return cancelled;
    }

    /**
     * Cancel every active signal belonging to one of the given nodes, scoped to one epoch.
     *
     * <p>Step-rerun support: a rerun resets the target node AND all its downstream nodes
     * <em>in the DAG's current epoch only</em>. Any of those downstream nodes may be awaiting
     * a signal (approval, wait timer, interface __continue) from the pre-rerun pass - those
     * signals must be cancelled surgically, signal-by-signal, NOT via
     * {@link #cancelByDagAndEpoch} which would also kill signals of parallel-branch siblings
     * that the rerun does not touch.
     *
     * <p>The epoch filter is load-bearing for multi-epoch SBS (parallel epochs stay open):
     * the rerun only resets the current epoch's state, so a reset node still awaiting a
     * signal in an OLDER active epoch must keep that signal - cancelling it would strand
     * the older epoch's wait (its {@code awaitingSignalNodeIds} entry survives the reset).
     *
     * @param runId   the workflow run
     * @param nodeIds the nodes whose active signals must be cancelled (normalized keys)
     * @param epoch   only signals registered for this epoch are cancelled; pass a negative
     *                value to cancel matching signals across all epochs
     * @return number of signals cancelled
     */
    @Transactional
    public int cancelForNodes(String runId, java.util.Set<String> nodeIds, int epoch) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        List<SignalWaitEntity> active = getActiveSignals(runId);
        java.util.List<Long> cancelledIds = new java.util.ArrayList<>();
        java.util.List<Long> userApprovalIds = new java.util.ArrayList<>();
        for (SignalWaitEntity signal : active) {
            if (!nodeIds.contains(signal.getNodeId())) {
                continue;
            }
            if (epoch >= 0 && signal.getEpoch() != epoch) {
                continue; // another epoch's wait - untouched by this reset
            }
            int n = signalWaitRepository.cancelById(signal.getId(), now);
            if (n > 0) {
                cancelledIds.add(signal.getId());
                if (signal.getSignalType() == SignalType.USER_APPROVAL) {
                    userApprovalIds.add(signal.getId());
                }
            }
        }
        // Cancel in-memory timers for the cancelled signals
        timerScheduler.cancelAll(cancelledIds);

        if (!userApprovalIds.isEmpty()) {
            eventPublisher.publishEvent(new SignalsCancelledEvent(runId, userApprovalIds));
        }
        if (!cancelledIds.isEmpty()) {
            logger.info("[UnifiedSignal] Cancelled {} signals for {} reset nodes: runId={}, epoch={}",
                cancelledIds.size(), nodeIds.size(), runId, epoch);
        }
        return cancelledIds.size();
    }

    // ========================================================================
    // RESCHEDULE
    // ========================================================================

    /**
     * Reschedule an active signal with a new expiration.
     * Updates DB + replaces the in-memory timer atomically.
     *
     * Works for all signal types with expiration:
     * - WAIT_TIMER: user changes the wait duration
     * - USER_APPROVAL: user changes the approval timeout
     * - WEBHOOK_WAIT: user changes the webhook timeout
     *
     * @param signalId the signal to reschedule
     * @param newExpiresAt the new expiration instant
     * @return true if rescheduled, false if signal was no longer PENDING
     */
    @Transactional
    public boolean rescheduleSignal(Long signalId, Instant newExpiresAt) {
        int updated = signalWaitRepository.updateExpiresAt(signalId, newExpiresAt);
        if (updated == 0) {
            logger.debug("[UnifiedSignal] Signal {} not rescheduled (not PENDING)", signalId);
            return false;
        }

        // Cancel old timer + schedule new one (schedule() already handles replace)
        if (newExpiresAt != null) {
            timerScheduler.schedule(signalId, newExpiresAt);
        } else {
            timerScheduler.cancel(signalId);
        }

        logger.info("[UnifiedSignal] Signal {} rescheduled to expiresAt={}", signalId, newExpiresAt);
        return true;
    }

    /**
     * Skew window before a freshly-registered signal becomes eligible for the
     * closed-epoch zombie guard. Without this, a timer that fires within the
     * snapshot-write commit window of {@link #registerSignal} could observe
     * "epoch not active" and hard-cancel a legitimate signal. 2s comfortably
     * covers the usual pg + JPA flush + snapshot-mutation TX commit (~50-200ms)
     * even under load, while leaving the guard effective for true orphans
     * (which are typically minutes/hours old).
     */
    private static final long ZOMBIE_GUARD_MIN_AGE_MS = 2_000L;

    /**
     * Return true when the signal's owning epoch has already been closed/pruned in
     * StateSnapshot. Such signals are leftovers from a previous trigger fire that
     * were never cancelled at epoch-close time. Resolving them would cause
     * re-execution of nodes whose epoch state no longer exists.
     *
     * <p>Conservative defaults: when info is missing (no entity, null dagTriggerId,
     * non-positive epoch) we return {@code false} - better to over-resolve than
     * to mistakenly cancel a legit signal.
     *
     * <p>Age gate: signals younger than {@link #ZOMBIE_GUARD_MIN_AGE_MS} are never
     * treated as zombies even if their epoch isn't yet visible in the snapshot -
     * this avoids the race where {@link #registerSignal}'s snapshot-mutation TX
     * has not yet committed when the timer fires.
     */
    private boolean isClosedEpochZombie(Long signalId) {
        try {
            // IMPORTANT: use the lightweight projection (NOT findById) so we don't pollute
            // the Hibernate L1 cache with the managed entity. Pre-fix, calling findById here
            // cached the entity with its pre-claim status (PENDING). The subsequent
            // findById in resolveSignal's Step 3 returned the same cached entity, and the
            // ensuing save(entity) wrote the cached PENDING back to DB, silently reverting
            // the just-issued native UPDATE that resolved the signal. Symptom on Gmail
            // Auto-Labeler (run_<id>, 2026-05-06): wait counted twice,
            // echo never reached, epoch never closed.
            var info = signalWaitRepository.findEpochInfoById(signalId).orElse(null);
            if (info == null) return false;
            String runId = info.runId();
            String dagTriggerId = info.dagTriggerId();
            int epoch = info.epoch();
            if (runId == null || dagTriggerId == null || epoch <= 0) return false;

            // Age gate - see field javadoc.
            Instant createdAt = info.createdAt();
            if (createdAt != null
                    && Duration.between(createdAt, clock.instant()).toMillis() < ZOMBIE_GUARD_MIN_AGE_MS) {
                return false;
            }

            var snapshot = stateSnapshotService.getSnapshot(runId);
            var dagState = snapshot.getDagState(dagTriggerId);
            return !dagState.getActiveEpochs().contains(epoch);
        } catch (Exception e) {
            logger.warn("[UnifiedSignal] Closed-epoch check failed for signal {}: {}", signalId, e.getMessage());
            return false;
        }
    }

    // ========================================================================
    // IN-MEMORY TIMER CALLBACK
    // ========================================================================

    /**
     * Called by SignalTimerScheduler when a timer expires.
     * Determines the correct resolution (COMPLETED for timers, TIMEOUT for non-timers)
     * and resolves via the normal claim-before-process pattern.
     */
    private void onTimerExpired(Long signalId) {
        try {
            SignalWaitEntity entity = signalWaitRepository.findById(signalId).orElse(null);
            if (entity == null || entity.getStatus() != SignalWaitStatus.PENDING) {
                return; // Already resolved/cancelled
            }

            SignalResolution resolution = (entity.getSignalType() == SignalType.WAIT_TIMER)
                ? SignalResolution.COMPLETED
                : SignalResolution.TIMEOUT;

            self.resolveSignal(signalId, resolution, Map.of(), instanceId);
        } catch (Exception e) {
            logger.error("[UnifiedSignal] Error in timer expiration for signal {}: {}", signalId, e.getMessage(), e);
        }
    }

    // ========================================================================
    // MAINTENANCE
    // ========================================================================

    /**
     * Defense-in-depth DB poller for expired WAIT_TIMER signals.
     *
     * <p>Primary path: {@link SignalTimerScheduler} → {@link com.apimarketplace.common.scaling.redis.RedisTimer}
     * (1s poll, fires expirationCallback on expiry). When that path silently fails to write
     * to the Redis ZSET (observed 2026-05-05 - schedule call returns without writing,
     * no FAIL-LOCAL log, never reproducible in tests), timers stall PENDING forever and
     * the workflow hangs at AWAITING_SIGNAL. This DB-backed safety net resolves them
     * within {@code fixedDelay} of expiry. Idempotent vs RedisTimer because
     * {@link #resolveSignal} uses claim-before-process (atomic UPDATE WHERE status='PENDING').
     */
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "signal-timer-db-poller", lockAtMostFor = "4s")
    public void pollExpiredTimers() {
        if (!signalMaintenancePollersEnabled) {
            return;
        }

        try {
            // Plan v3 #9 - bumped batch from 50 to 500. The V179 covering index
            // (status, signal_type, expires_at, id) keeps the scan index-only;
            // SKIP LOCKED prevents dup-claim across replicas. Keyset variant
            // {@link SignalWaitRepository#findExpiredTimersKeyset} is available
            // for the next iteration when per-replica lastId persistence is wired.
            List<SignalWaitEntity> expired =
                signalWaitRepository.findExpiredTimers(clock.instant(), 500);
            if (expired.isEmpty()) return;
            logger.info("[UnifiedSignal] DB poller found {} expired WAIT_TIMER signals", expired.size());
            for (SignalWaitEntity s : expired) {
                try {
                    self.resolveSignal(s.getId(), SignalResolution.COMPLETED, Map.of(), instanceId);
                } catch (Exception e) {
                    logger.warn("[UnifiedSignal] DB poller failed to resolve signal {}: {}",
                        s.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("[UnifiedSignal] Error in DB timer poller: {}", e.getMessage(), e);
        }
    }

    /**
     * Recover stale claims (claimed but not resolved within threshold).
     * Resets them to PENDING so they can be picked up again.
     */
    @Scheduled(fixedDelay = 30000)
    @SchedulerLock(name = "signal-stale-recovery", lockAtMostFor = "25s")
    public void recoverStaleClaims() {
        if (!signalMaintenancePollersEnabled) {
            return;
        }

        try {
            Instant staleThreshold = clock.instant().minus(Duration.ofMinutes(2));
            int reset = signalWaitRepository.resetStaleClaims(staleThreshold);
            if (reset > 0) {
                logger.warn("[UnifiedSignal] Reset {} stale claims (claimed > 2 min ago)", reset);
            }
        } catch (Exception e) {
            logger.error("[UnifiedSignal] Error in stale claims recovery: {}", e.getMessage(), e);
        }
    }

    // ========================================================================
    // QUERY
    // ========================================================================

    /**
     * Check if there are any active signals for a run.
     */
    public boolean hasActiveSignals(String runId) {
        return signalWaitRepository.hasActiveSignals(runId);
    }

    /**
     * Check for signals that should block workflow completion.
     * Non-blocking signals (e.g., auto-advance interfaces) don't prevent completion.
     */
    public boolean hasBlockingSignals(String runId) {
        return signalWaitRepository.hasBlockingSignals(runId);
    }

    /**
     * Check if there are any active signals for a specific DAG.
     * Falls back to run-wide check if dagTriggerId is null.
     */
    public boolean hasActiveSignalsForDag(String runId, String dagTriggerId) {
        if (dagTriggerId == null) {
            return hasActiveSignals(runId);
        }
        return signalWaitRepository.hasActiveSignalsForDag(runId, dagTriggerId);
    }

    /**
     * Check for blocking signals in a specific DAG.
     * Falls back to run-wide blocking check if dagTriggerId is null.
     */
    public boolean hasBlockingSignalsForDag(String runId, String dagTriggerId) {
        if (dagTriggerId == null) {
            return hasBlockingSignals(runId);
        }
        return signalWaitRepository.hasBlockingSignalsForDag(runId, dagTriggerId);
    }

    /**
     * Check for blocking signals in a specific DAG and epoch.
     * Used for parallel epoch support: each epoch closes independently.
     * Falls back to DAG-wide check if epoch <= 0.
     */
    public boolean hasBlockingSignalsForDagAndEpoch(String runId, String dagTriggerId, int epoch) {
        if (dagTriggerId == null) {
            return hasBlockingSignals(runId);
        }
        if (epoch < 0) {
            return hasBlockingSignalsForDag(runId, dagTriggerId);
        }
        return signalWaitRepository.hasBlockingSignalsForDagAndEpoch(runId, dagTriggerId, epoch);
    }

    /**
     * Check for resolved blocking signals whose async resume flow is still
     * finalizing persisted outputs, skipped branches, and selected successors.
     */
    public boolean hasPendingSignalResumesForDagAndEpoch(String runId, String dagTriggerId, int epoch) {
        if (redisTemplate == null || runId == null || dagTriggerId == null || epoch < 0) {
            return false;
        }
        try {
            Long size = redisTemplate.opsForSet().size(
                RedisCacheKeys.signalResumePending(runId, dagTriggerId, epoch));
            return size != null && size > 0;
        } catch (Exception e) {
            logger.warn("[UnifiedSignal] Failed to read pending signal-resume marker: runId={}, dag={}, epoch={}, error={}",
                runId, dagTriggerId, epoch, e.getMessage());
            return false;
        }
    }

    /**
     * Node IDs of signals on the same (run, dag, epoch) whose async resume flow is still
     * finalizing, excluding {@code excludeSignalId} (the caller's own signal). Used by
     * SignalResumeService's concurrent-sibling guard to defer executing a sibling's
     * successors until that sibling's resume has persisted its resolution output.
     * Returns a MUTABLE set; empty on any Redis/DB error (guard fails open).
     */
    public Set<String> getPendingResumeNodeIds(String runId, String dagTriggerId, int epoch, Long excludeSignalId) {
        Set<String> nodeIds = new HashSet<>();
        if (redisTemplate == null || runId == null || dagTriggerId == null || epoch < 0) {
            return nodeIds;
        }
        try {
            String key = RedisCacheKeys.signalResumePending(runId, dagTriggerId, epoch);
            Set<String> members = redisTemplate.opsForSet().members(key);
            if (members == null || members.isEmpty()) {
                return nodeIds;
            }
            // Refresh the protection window while sibling resumes are still active: the
            // 2-min TTL is set once at marking time, and a long-running first resume
            // (agent/HTTP successors) could otherwise outlive it, re-opening the
            // premature-reset window for the remaining siblings.
            redisTemplate.expire(key, SIGNAL_RESUME_PENDING_TTL);
            List<Long> ids = members.stream()
                .map(member -> {
                    try {
                        return Long.valueOf(member);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(excludeSignalId))
                .toList();
            if (ids.isEmpty()) {
                return nodeIds;
            }
            signalWaitRepository.findAllById(ids).forEach(signal -> {
                if (signal.getNodeId() != null) {
                    nodeIds.add(signal.getNodeId());
                }
            });
            return nodeIds;
        } catch (Exception e) {
            logger.warn("[UnifiedSignal] Failed to read pending signal-resume node ids: runId={}, dag={}, epoch={}, error={}",
                runId, dagTriggerId, epoch, e.getMessage());
            nodeIds.clear();
            return nodeIds;
        }
    }

    /**
     * Clear this signal's async resume marker before the deferred-reset gate runs.
     */
    public void clearSignalResumePending(SignalWaitEntity signal) {
        if (!isSignalResumePendingTrackable(signal) || redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForSet().remove(
                RedisCacheKeys.signalResumePending(signal.getRunId(), signal.getDagTriggerId(), signal.getEpoch()),
                String.valueOf(signal.getId()));
        } catch (Exception e) {
            logger.warn("[UnifiedSignal] Failed to clear pending signal-resume marker: signalId={}, runId={}, error={}",
                signal.getId(), signal.getRunId(), e.getMessage());
        }
    }

    private void markSignalResumePending(Long signalId, SignalWaitRepository.ResumeInfo resumeInfo) {
        if (!isSignalResumePendingTrackable(signalId, resumeInfo) || redisTemplate == null) {
            return;
        }
        try {
            String key = RedisCacheKeys.signalResumePending(
                resumeInfo.runId(), resumeInfo.dagTriggerId(), resumeInfo.epoch());
            redisTemplate.opsForSet().add(key, String.valueOf(signalId));
            redisTemplate.expire(key, SIGNAL_RESUME_PENDING_TTL);
        } catch (Exception e) {
            logger.warn("[UnifiedSignal] Failed to mark pending signal-resume: signalId={}, runId={}, error={}",
                signalId, resumeInfo.runId(), e.getMessage());
        }
    }

    private void clearSignalResumePending(Long signalId, SignalWaitRepository.ResumeInfo resumeInfo) {
        if (!isSignalResumePendingTrackable(signalId, resumeInfo) || redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForSet().remove(
                RedisCacheKeys.signalResumePending(resumeInfo.runId(), resumeInfo.dagTriggerId(), resumeInfo.epoch()),
                String.valueOf(signalId));
        } catch (Exception e) {
            logger.warn("[UnifiedSignal] Failed to clear pending signal-resume marker: signalId={}, runId={}, error={}",
                signalId, resumeInfo.runId(), e.getMessage());
        }
    }

    private boolean isSignalResumePendingTrackable(SignalWaitEntity signal) {
        return signal != null
            && signal.getId() != null
            && signal.isBlocking()
            && signal.getRunId() != null
            && signal.getDagTriggerId() != null
            && signal.getEpoch() >= 0;
    }

    private boolean isSignalResumePendingTrackable(Long signalId, SignalWaitRepository.ResumeInfo resumeInfo) {
        return signalId != null
            && resumeInfo != null
            && resumeInfo.blocking()
            && resumeInfo.runId() != null
            && resumeInfo.dagTriggerId() != null
            && resumeInfo.epoch() >= 0;
    }

    /**
     * Get all active signals for a run.
     */
    public List<SignalWaitEntity> getActiveSignals(String runId) {
        return signalWaitRepository.findActiveByRunId(runId);
    }

    /**
     * Get active signals for a run filtered by epoch.
     * Use this to avoid cross-epoch signal interference (e.g., an interface signal
     * from epoch 2 should not block the same interface node in epoch 3).
     */
    public List<SignalWaitEntity> getActiveSignals(String runId, int epoch) {
        return signalWaitRepository.findActiveByRunIdAndEpoch(runId, epoch);
    }

    /**
     * Get active signals for a specific node, ordered by itemId.
     * Used by the pending signals endpoint to expose per-item detail to the frontend.
     */
    public List<SignalWaitEntity> getActiveSignalsForNode(String runId, String nodeId) {
        return signalWaitRepository.findActiveByRunIdAndNodeId(runId, nodeId);
    }

    /**
     * Check if a node has multiple signals in the given epoch (split context detection).
     * Counts ALL signals regardless of status (PENDING, CLAIMED, RESOLVED).
     * If total > 1, the node had multiple signals, meaning it's in a split context.
     * Epoch-scoped to avoid false positives from signals in other trigger cycles.
     */
    public boolean isSplitContextNode(String runId, String nodeId, int epoch) {
        return signalWaitRepository.countAllByRunIdAndNodeIdAndEpoch(runId, nodeId, epoch) > 1;
    }

    /**
     * Get the total signal count for a node in a given epoch.
     * Used to populate split_item_count in the resolved output.
     */
    public long getSignalCountForNodeEpoch(String runId, String nodeId, int epoch) {
        return signalWaitRepository.countAllByRunIdAndNodeIdAndEpoch(runId, nodeId, epoch);
    }

    /**
     * Get all resolved signals for a node in a given epoch.
     * Used by split resume reconciliation to persist any resolved item output that
     * was skipped by a duplicate async resume path.
     */
    public List<SignalWaitEntity> getResolvedSignalsForNodeEpoch(String runId, String nodeId, int epoch) {
        return signalWaitRepository.findResolvedByRunIdAndNodeIdAndEpoch(runId, nodeId, epoch);
    }

    /**
     * Find a signal by its ID.
     */
    public SignalWaitEntity findById(Long signalId) {
        return signalWaitRepository.findById(signalId).orElse(null);
    }

    /**
     * Get instance ID (for logging and claim tracking).
     */
    public String getInstanceId() {
        return instanceId;
    }
}

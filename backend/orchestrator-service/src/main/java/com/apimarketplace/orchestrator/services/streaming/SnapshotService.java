package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.services.streaming.state.StateUtils;
import com.apimarketplace.orchestrator.services.transaction.TransactionalHelper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service responsible for building and publishing snapshots directly.
 *
 * Single Responsibility: Build snapshot from DB and publish to streaming clients via Redis/WebSocket.
 *
 * This replaces the batch scheduler approach. Instead of:
 *   Executor -> RunState (dirty) -> BatchScheduler (100ms) -> publish
 *
 * We now have:
 *   Executor -> SnapshotService -> Redis/WebSocket (immediate)
 *
 * Benefits:
 * - No race conditions (same thread reads and sends)
 * - Simpler code (no dirty tracking, no batch scheduler)
 * - Data always consistent
 */
@Service
public class SnapshotService implements RunScopedCache {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    private static final long THROTTLE_MS = 200;

    /**
     * Phase B.1 (archi-refoundation 2026-05-04) - number of stripes for per-runId
     * locks. 4096 keeps p(collision) ≤ 0.5% at 1000 concurrent runs (audit A v6
     * birthday-paradox correct calculation). Native ReentrantLock array, no
     * Guava dependency (audit A+B v5 confirmed Guava is absent from
     * orchestrator-service/pom.xml).
     */
    private static final int LOCK_STRIPES = 4096;

    /**
     * Phase B.3 (archi-refoundation 2026-05-04) - output bytes cap per step in a
     * snapshot. Today {@code SnapshotService.buildSteps} only puts small outputs
     * ({@code selectedBranch}, {@code interface_id}, {@code action_mapping} -
     * combined &lt; 1KB), so no truncation occurs in practice. The constant is a
     * <b>defensive guard</b> against future callsites adding large blobs (LLM
     * outputs, scrape results) - those should go through
     * {@code WorkflowStepOutputController} for lazy-fetch, NOT into the
     * snapshot.
     *
     * <p>Calibrated at 64KB (audit A v3/v4 R1: avoids cutting ≥30KB LLM outputs
     * that might legitimately be inlined). 1-week Prometheus baseline via
     * {@code snapshot.output.size.bytes} histogram before potential downward
     * adjustment.
     */
    private static final int STEP_OUTPUT_MAX_BYTES = 65_536;

    private final StateSnapshotService stateSnapshotService;
    private final WorkflowStreamingService streamingService;
    private final RunningNodeTracker runningNodeTracker;
    private final WorkflowEpochService workflowEpochService;

    // Per-runId throttle: coalesces rapid snapshot sends into one deferred send
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingSnapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastSendTime = new ConcurrentHashMap<>();
    // Per-runId monotonic seq of the LAST successfully published snapshot. If a
    // subsequent doSendSnapshot finds the same seq in DB, nothing has changed
    // since last publish and the entire build/send pipeline is short-circuited.
    // Audit hot-path #2 (OOM 2026-05-06): this skip eliminates 30-50% of
    // buildSnapshotFromDb calls during steady-state under WS reconnect storms
    // and rapid throttle expirations on quiescent runs.
    private final ConcurrentHashMap<String, Long> lastPublishedSeq = new ConcurrentHashMap<>();
    // Plan node IDs cache: plan is immutable during execution, no need to re-parse every snapshot
    private final ConcurrentHashMap<String, Set<String>> planNodeIdsCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService throttleScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "snapshot-throttle");
                t.setDaemon(true);
                return t;
            });

    /**
     * Phase B.1 - fixed-size lock stripe array. Indexed by
     * {@code Math.floorMod(runId.hashCode(), LOCK_STRIPES)}. NEVER evicted
     * (audits B+C v4/v5/v6 unanimous: LRU eviction of a held lock = race
     * re-introduction). Memory cost: ~96KB statique, négligeable.
     */
    private final ReentrantLock[] sendLocks = new ReentrantLock[LOCK_STRIPES];

    /**
     * Phase B.1 - POSITIVE cache: runId → Boolean.FALSE means "verified
     * non-terminal". Hot-path optimization: without this, every {@code
     * markDirty} call on an active run would query the DB (audit B+C v5 NA-2,
     * v4 ~31K queries/run regression).
     */
    private final Cache<String, Boolean> activeRunsCache;

    /**
     * Phase B.1 - TOMBSTONE cache: runId → Boolean.TRUE means terminal.
     * Pre-warmed in {@link #cleanupRun} so subsequent {@code markDirty}
     * calls (signal resume async tardif, etc.) short-circuit without DB hit.
     */
    private final Cache<String, Boolean> terminatedRunsCache;

    @Autowired(required = false)
    private UnifiedSignalService unifiedSignalService;

    @Autowired(required = false)
    private WorkflowRedisPublisher redisPublisher;

    /**
     * Phase B.2 (archi-refoundation 2026-05-04) - multi-replica leader lock.
     * Present only in {@code scaling.backend=redis} mode (conditional bean).
     * In single-pod memory mode this is null and the existing
     * {@link #sendLocks} striped lock is sufficient (single producer
     * trivially).
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.scaling.RedisLeaderLock redisLeaderLock;

    /**
     * Phase B.1 - required for the authoritative {@code isTerminal(runId)}
     * check in {@code markDirty}. Cache-miss path (1 PK-indexed query, ~50µs).
     */
    private final WorkflowRunRepository runRepository;

    public SnapshotService(
            StateSnapshotService stateSnapshotService,
            WorkflowStreamingService streamingService,
            RunningNodeTracker runningNodeTracker,
            WorkflowEpochService workflowEpochService,
            WorkflowRunRepository runRepository,
            @Value("${snapshot.active-cache-ttl-seconds:60}") long activeCacheTtlSeconds,
            @Value("${snapshot.terminated-cache-ttl-seconds:1800}") long terminatedCacheTtlSeconds) {
        this.stateSnapshotService = stateSnapshotService;
        this.streamingService = streamingService;
        this.runningNodeTracker = runningNodeTracker;
        this.workflowEpochService = workflowEpochService;
        this.runRepository = runRepository;

        // Initialize lock stripes
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.sendLocks[i] = new ReentrantLock();
        }

        // Caffeine bounded caches with explicit maxSize (audit C v6 NEW-1: without
        // maximumSize, LRU eviction under memory pressure would purge active-run
        // entries before TTL, regressing the hot-path optimization).
        this.activeRunsCache = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofSeconds(activeCacheTtlSeconds))
                .recordStats()
                .build();
        this.terminatedRunsCache = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofSeconds(terminatedCacheTtlSeconds))
                .recordStats()
                .build();
    }

    private ReentrantLock getLock(String runId) {
        return sendLocks[Math.floorMod(runId.hashCode(), LOCK_STRIPES)];
    }

    /**
     * Phase B.1 - DB-as-truth terminal check with cache layers.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>Positive cache (activeRunsCache) - runs known to be active. Hit
     *       returns false immediately, no DB query on hot path.</li>
     *   <li>Tombstone (terminatedRunsCache) - runs known to be terminal.
     *       Hit returns true.</li>
     *   <li>DB fallback - {@code WorkflowRunRepository.findStatusByRunIdPublic}
     *       (audit B v6 N1: signature MUST match the run_id_public column,
     *       NOT the UUID PK).</li>
     * </ol>
     *
     * <p>Missing run → treated as terminal (don't publish on ghosts).
     */
    private boolean isTerminal(String runId) {
        Boolean active = activeRunsCache.getIfPresent(runId);
        if (active != null) return false;  // FALSE = verified non-terminal
        Boolean terminated = terminatedRunsCache.getIfPresent(runId);
        if (terminated != null) return true;
        try {
            boolean terminal = runRepository.findStatusByRunIdPublic(runId)
                    .map(RunStatus::isTerminal)
                    .orElse(true);  // missing run → treat as ghost, no publish
            if (terminal) {
                terminatedRunsCache.put(runId, Boolean.TRUE);
            } else {
                activeRunsCache.put(runId, Boolean.FALSE);
            }
            return terminal;
        } catch (Exception e) {
            log.warn("[Snapshot] Terminal-status DB query failed for runId={}: {} (defaulting to non-terminal - fail-open)",
                    runId, e.getMessage());
            return false;  // fail-open: prefer extra publish over silenced UI
        }
    }

    /**
     * Phase B.1 (archi-refoundation 2026-05-04) - request a deferred snapshot
     * publish for {@code runId}. Wraps {@link #sendSnapshot(String)} with:
     * <ul>
     *   <li>Tombstone short-circuit (no publish on terminated runs)</li>
     *   <li>{@code afterCommit}-aware deferral via {@link TransactionalHelper}
     *       so the snapshot reads post-commit DB state, not the in-flight
     *       transaction view (audit B+C v6: read-before-commit bug).</li>
     * </ul>
     *
     * <p>Callers: {@code NodeEventEmitterService.onStepPersisted},
     * {@code EdgeStatusService.flushEdgeBatch}. Existing callers of
     * {@link #sendSnapshot(String)} and {@link #sendSnapshotImmediate(String)}
     * are NOT migrated - they keep their current semantics for back-compat.
     */
    public void markDirty(String runId) {
        if (runId == null) return;
        if (isTerminal(runId)) return;
        TransactionalHelper.runAfterCommitOrNow(() -> sendSnapshot(runId));
    }

    /**
     * Build snapshot from DB and publish to streaming clients.
     * Throttled: coalesces rapid calls within 200ms into a single send.
     * The snapshot always reads from DB, so the deferred send picks up the latest state.
     *
     * @param runId The workflow run ID
     */
    public void sendSnapshot(String runId) {
        if (runId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long lastSent = lastSendTime.getOrDefault(runId, 0L);
        long elapsed = now - lastSent;

        if (elapsed >= THROTTLE_MS) {
            // Enough time has passed - send immediately
            cancelPending(runId);
            doSendSnapshot(runId);
        } else {
            // Within throttle window - schedule deferred send (cancel any existing)
            cancelPending(runId);
            long delay = THROTTLE_MS - elapsed;
            ScheduledFuture<?> future = throttleScheduler.schedule(
                    () -> doSendSnapshot(runId), delay, TimeUnit.MILLISECONDS);
            pendingSnapshots.put(runId, future);
        }
    }

    /**
     * Send snapshot immediately, bypassing the throttle.
     * Use for critical events: workflow complete, signal resolved, manual trigger.
     */
    public void sendSnapshotImmediate(String runId) {
        if (runId == null) {
            return;
        }
        cancelPending(runId);
        doSendSnapshot(runId);
    }

    /**
     * Phase B.1 (archi-refoundation 2026-05-04) - implements
     * {@link RunScopedCache#cleanupRun(String)}. Auto-registered with
     * {@code RunCacheRegistry} (Spring injects all
     * {@code List<RunScopedCache>} beans), so cleanup is now actually
     * INVOKED - pre-Phase B.1 this method existed but had ZERO callers
     * (audit A v6 T1, verified by grep).
     */
    @Override
    public void cleanupRun(String runId) {
        // Audit 2026-05-06 round 2 P0 (B.4 cleanup race actual close):
        //   - Pre-fix: tombstone pre-warm ran OUTSIDE the lock. A deferred publish
        //     scheduled by doSendSnapshot's tryLock-fail path could take the lock
        //     AFTER cleanupRun released it but BEFORE the tombstone was set,
        //     publish, and re-populate lastPublishedSeq → leak past terminal.
        //   - Post-fix: tombstone pre-warm runs INSIDE the lock window, so any
        //     subsequent doSendSnapshot that acquires the lock sees the tombstone
        //     at the line ~421 check and short-circuits before the publish work.
        // The terminal-status DB read runs OUTSIDE the lock to avoid holding it
        // during IO; the captured boolean drives the in-lock tombstone-set.
        // (Audit 2026-05-06 round 4 #7 - comment-vs-code drift fix.)
        boolean actuallyTerminal = false;
        try {
            actuallyTerminal = runRepository.findStatusByRunIdPublic(runId)
                    .map(RunStatus::isTerminal)
                    .orElse(false);
        } catch (Exception e) {
            log.debug("[Snapshot] cleanupRun terminal-check failed for runId={}: {}",
                    runId, e.getMessage());
        }

        ReentrantLock lock = getLock(runId);
        lock.lock();
        try {
            // Tombstone FIRST (still under lock) - any deferred publish that
            // races for the lock after we release will see TRUE and bail at
            // doSendSnapshot's line 421-423 short-circuit, BEFORE the
            // lastPublishedSeq.put could leak.
            //
            // Reusable triggers transition to WAITING_TRIGGER (non-terminal) on
            // every epoch close - RunContextRegistry.closeEpochForDagByTriggerId
            // calls cacheRegistry.cleanupRun(runId) at every fire boundary.
            // We must NOT set the tombstone unconditionally: doing so on a
            // WAITING_TRIGGER run would short-circuit every subsequent markDirty
            // on the next fire and freeze the UI (verified 2026-05-05:
            // run_<id> had every sub-event but no batch-update).
            if (actuallyTerminal) {
                terminatedRunsCache.put(runId, Boolean.TRUE);
            }
            cancelPending(runId);
            lastSendTime.remove(runId);
            lastPublishedSeq.remove(runId);
            planNodeIdsCache.remove(runId);
            activeRunsCache.invalidate(runId);
        } finally {
            lock.unlock();
        }

        // sendLocks[] is fixed-size - no per-runId entry to remove (audit B+C v5/v6).
        // Phase B.2: release the leader lock so peer pods don't keep waiting on a TTL expiry.
        if (redisLeaderLock != null) {
            redisLeaderLock.release(runId);
        }
    }

    @Override
    public String getCacheName() {
        return "SnapshotService";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.STREAMING;
    }

    @Override
    public int getCacheSize() {
        // Sum of the two layered caches; ScheduledFuture map size doesn't reflect
        // run lifecycle (it's a transient throttle window).
        return (int) (activeRunsCache.estimatedSize() + terminatedRunsCache.estimatedSize());
    }

    private void cancelPending(String runId) {
        ScheduledFuture<?> existing = pendingSnapshots.remove(runId);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    private void doSendSnapshot(String runId) {
        // Phase B.2 (audit C v3 axe1, v4 axe1) - multi-replica leader-lock
        // ownership. In scaling.backend=redis with multiple pods executing
        // code for the same runId (sub-workflow async, signal resume on a
        // different replica), we want a single producer to coalesce. Try to
        // acquire ownership; if not owner, fall back to direct publish
        // (accepts +X% events during takeover, no events lost - audit C v6
        // NEW-3 trade-off explicitly accepted in plan §2.1.6).
        boolean amOwner = (redisLeaderLock == null) || redisLeaderLock.amOwner(runId)
                || redisLeaderLock.tryAcquire(runId);

        // Phase B.1 (audit C v3 axe3 + v4 axe7): per-runId stripe lock to
        // serialize concurrent doSendSnapshot intra-JVM. Without this, SBS
        // flushNow + cron tick + signal-resume on the same runId could each
        // build a snapshot from DB reads at slightly different timestamps,
        // and the frontend strict-< seq guard would let through 2 snapshots
        // with the same WsEventSequencer-assigned seq carrying different
        // payloads, clobbering one another.
        //
        // tryLock() non-bloquant: if another thread holds the same stripe
        // (same runId or hash-collision peer at 0.5% probability per audit
        // A v6 birthday calculation), we re-arm via the throttleScheduler.
        // No event lost: the holder's DB read covers our state, and the
        // deferred re-entry catches anything committed after the holder's
        // read.
        //
        // 2026-05-05 SOE fix: previously this branch called {@code
        // sendSnapshot(runId)} synchronously. When {@code lastSendTime} is
        // stale (fresh runId, or post-{@link #cleanupRun}), {@code
        // sendSnapshot} sees {@code elapsed >= THROTTLE_MS}, calls back into
        // {@code doSendSnapshot}, fails {@code tryLock} again, recurses on
        // the same call stack - {@link StackOverflowError} after ~1k frames.
        // Observed in prod 09:16 UTC pre-deploy of the seq-stripe fix
        // (3 occurrences). Switching to a deferred schedule via {@link
        // #throttleScheduler} breaks the synchronous recursion: the retry
        // runs on the scheduler thread, after THROTTLE_MS, by which time the
        // holder has either updated {@code lastSendTime} (taking the next
        // {@code sendSnapshot} into the safe {@code else} branch) or
        // released the stripe (letting the retry's {@code tryLock} succeed).
        ReentrantLock lock = getLock(runId);
        if (!lock.tryLock()) {
            cancelPending(runId);
            ScheduledFuture<?> future = throttleScheduler.schedule(
                    () -> sendSnapshot(runId), THROTTLE_MS, TimeUnit.MILLISECONDS);
            pendingSnapshots.put(runId, future);
            return;
        }
        try {
            // Re-check tombstone post-lock: a cleanupRun could have run
            // while we waited (rare given tryLock, but defensive).
            // Tombstone-only check (no DB hit) - `markDirty` already did the
            // authoritative DB check upstream. Skipping the DB query here also
            // preserves test compatibility for callers that invoke
            // sendSnapshot/sendSnapshotImmediate directly without seeding the
            // repository mock.
            if (Boolean.TRUE.equals(terminatedRunsCache.getIfPresent(runId))) {
                return;
            }

            // Skip-if-unchanged: read the StateSnapshot once, peek at its monotonic
            // seq, and short-circuit the heavy build pipeline (buildSteps/buildEdges
            // + Jackson + signals query + planNodeIds load) when nothing has
            // changed since the last successful publish on this runId. Saves
            // 30-50% of WS rebuild work under WS-reconnect storms / quiescent
            // runs / rapid throttle expirations (audit hot-path #2, OOM
            // 2026-05-06).
            //
            // Correctness: StateSnapshot.seq is bumped by saveSnapshot() on every
            // mutation (StateSnapshotService.saveSnapshot line ~1540: snapshot
            // .withIncrementedSeq()). All writers go through that single path
            // (verified by grep - see B.1 commit 9b923acd6 audit). An unchanged
            // seq guarantees no state-mutation happened since last publish, so
            // the previously-built payload is still authoritative for the FE.
            StateSnapshot dbSnapshot = stateSnapshotService.getSnapshot(runId);
            long currentSeq = dbSnapshot.getSeq();
            Long lastPub = lastPublishedSeq.get(runId);
            if (lastPub != null && lastPub.longValue() == currentSeq) {
                pendingSnapshots.remove(runId);
                if (log.isDebugEnabled()) {
                    log.debug("[Snapshot] Skip publish for runId={} (seq unchanged at {})",
                            runId, currentSeq);
                }
                return;
            }

            pendingSnapshots.remove(runId);
            lastSendTime.put(runId, System.currentTimeMillis());

            Map<String, Object> snapshot = buildSnapshotFromDb(runId, dbSnapshot);
            // Single publish: streamingService.sendDirectSnapshot routes via
            // WorkflowStreamingService.sendEvent → WorkflowRedisPublisher.publishEvent
            // with a fresh WsEventSequencer.nextSeq() stamped on the wire - which
            // is the seq the frontend's lastKnownSeq guard tracks.
            //
            // 2026-05-04 hot-fix (audit C1+C3): the prior dual-publish here
            // (sendDirectSnapshot + redisPublisher.publishSnapshot) sent the
            // SAME channel twice with two different seqs (nextSeq() in the
            // first, StateSnapshot.getSeq() in the second). The second arrived
            // with seq < first and was systematically dropped by the FE seq
            // guard, manifesting as "events temps réel s'arrêtent" in prod.
            streamingService.sendDirectSnapshot(runId, snapshot);
            // Mark the seq AFTER the publish succeeds so any exception thrown
            // by streamingService.sendDirectSnapshot leaves lastPublishedSeq
            // unchanged → the next throttle expiry retries the build+send for
            // the same seq instead of silently swallowing the failure.
            lastPublishedSeq.put(runId, currentSeq);

            log.debug("[Snapshot] Sent snapshot for runId={}, steps={}, edges={}, owner={}",
                runId,
                ((List<?>) snapshot.getOrDefault("steps", List.of())).size(),
                ((List<?>) snapshot.getOrDefault("edges", List.of())).size(),
                amOwner);
        } catch (Exception e) {
            log.warn("[Snapshot] Failed to send snapshot for runId={}: {}", runId, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Build the complete snapshot from DB.
     * This is the single source of truth - no in-memory caches.
     *
     * @param runId      the public run identifier
     * @param dbSnapshot the pre-loaded {@link StateSnapshot} from DB. Callers that
     *                   already read it (e.g. {@code doSendSnapshot} for the
     *                   skip-if-unchanged check) MUST pass their loaded instance
     *                   here so the same parsed snapshot is reused - avoids a
     *                   second {@code stateSnapshotService.getSnapshot(runId)}
     *                   call that would re-parse the JSONB inside the same tx
     *                   (the per-tx parse cache from B.1 catches it, but the
     *                   non-tx WS publish path can't rely on that).
     */
    private Map<String, Object> buildSnapshotFromDb(String runId, StateSnapshot dbSnapshot) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("runId", runId);
        snapshot.put("timestamp", System.currentTimeMillis());
        // 2026-05-04 audit clean-up: do NOT seed `seq` here. The wire `seq` is
        // stamped at publish-time by WorkflowRedisPublisher.publishEvent ligne 72
        // with WsEventSequencer.nextSeq() (overrides any pre-existing value).
        // Leaving dbSnapshot.getSeq() here was dead code that confused the
        // pattern and made it look like two seq sources coexisted.

        // Build steps with statusCounts from DB + in-memory running overlay + timing from NodeCounts
        Set<String> awaitingSignalNodeIds = dbSnapshot.getAwaitingSignalNodeIds();
        // P2.1.5 - PAUSED/WAITING_TRIGGER short-circuit: by definition zero nodes are
        // running in these states. Avoids surfacing stale Redis hash entries that may
        // linger inside the 1h TTL window after a mid-execution pause. Status fetch is
        // a single-column projection on a PK index - typically buffer-cached and well
        // under 100µs; cheaper than a Redis HGETALL on cache miss.
        // Terminal runs (COMPLETED/FAILED/CANCELLED/...) share the SAME invariant: a
        // finished run has nothing running, so a stale overlay entry (e.g. a node whose
        // markCompleted was dropped, or an overlay not yet swept) must never paint the
        // node as "running" forever. The authoritative completed/failed/skipped IDs are
        // taken from the DB snapshot below regardless of this overlay.
        Map<String, Integer> runningCounts;
        RunStatus runStatus = runRepository.findStatusByRunIdPublic(runId).orElse(null);
        if (runStatus == RunStatus.PAUSED || runStatus == RunStatus.WAITING_TRIGGER
                || (runStatus != null && runStatus.isTerminal())) {
            runningCounts = Map.of();
        } else {
            // Post-P2.3.1: writers populate per-epoch keys ({runId}:{epoch}) only.
            // SCAN every per-epoch key for this run so the WS payload's running
            // overlay covers all active epochs (and the legacy fallback inside
            // for pre-P2.3.1 in-flight runs during the overlap window).
            runningCounts = runningNodeTracker.getRunningCountsAcrossEpochs(runId);
        }
        // Load active signals ONCE - used for counts, interface configs, and pending signals list
        List<SignalWaitEntity> activeSignals = loadActiveSignals(runId);
        Map<String, Integer> awaitingSignalCounts = computeAwaitingSignalCounts(dbSnapshot, activeSignals);
        // Pre-load active signal configs for interface nodes (needed for output enrichment)
        Map<String, Map<String, Object>> interfaceSignalConfigs = extractInterfaceSignalConfigs(activeSignals);
        // Load all plan node IDs so buildSteps includes untouched nodes as "pending".
        // Without this, nodes that haven't started yet are missing from the snapshot,
        // causing them to disappear from the frontend when a batch-update arrives.
        // Cached: plan is immutable during execution, no need to re-parse every snapshot.
        Set<String> planNodeIds = planNodeIdsCache.computeIfAbsent(runId,
                id -> stateSnapshotService.getPlanNodeIds(id));
        List<Map<String, Object>> steps = buildSteps(dbSnapshot, awaitingSignalNodeIds, awaitingSignalCounts, runningCounts, interfaceSignalConfigs, planNodeIds);
        snapshot.put("steps", steps);

        // Compute running step IDs (merge DB + in-memory overlay)
        Set<String> runningStepIds = new HashSet<>();
        for (Map<String, Object> step : steps) {
            if ("running".equals(step.get("status"))) {
                runningStepIds.add((String) step.get("id"));
            }
        }

        // Build edges from DB, overlaying RUNNING state from node statuses.
        // When a node is RUNNING, the incoming edge that delivered data should also appear RUNNING.
        List<Map<String, Object>> edges = buildEdges(dbSnapshot, runningStepIds);
        snapshot.put("edges", edges);

        // Interfaces (empty - no longer tracked in-memory)
        snapshot.put("interfaces", List.of());

        // Include pending signals for frontend rendering (countdown timers, approval buttons)
        List<Map<String, Object>> pendingSignals = buildPendingSignals(activeSignals);
        snapshot.put("pendingSignals", pendingSignals);

        // Authoritative step tracking sets from StateSnapshot flat views.
        // These use activeEpochs and represent CURRENT state.
        snapshot.put("completedStepIds", new ArrayList<>(dbSnapshot.getCompletedNodeIds()));
        snapshot.put("failedStepIds", new ArrayList<>(dbSnapshot.getFailedNodeIds()));
        snapshot.put("skippedStepIds", new ArrayList<>(dbSnapshot.getSkippedNodeIds()));
        snapshot.put("readyStepIds", new ArrayList<>(dbSnapshot.getReadyNodeIds()));
        snapshot.put("awaitingSignalStepIds", new ArrayList<>(dbSnapshot.getAwaitingSignalNodeIds()));
        snapshot.put("runningStepIds", new ArrayList<>(runningStepIds));

        // Epoch metadata for frontend run info panel.
        // currentEpoch from StateSnapshot DagState (authoritative, no extra query).
        int currentEpoch = 0;
        for (DagState dag : dbSnapshot.getDags().values()) {
            currentEpoch = Math.max(currentEpoch, dag.getCurrentEpoch());
        }
        snapshot.put("currentEpoch", currentEpoch);

        // Per-epoch ready steps for SBS parallel epoch support.
        // Maps epoch number → list of ready node IDs for that epoch.
        // Only includes active epochs (closed epochs have no ready nodes).
        Map<String, List<String>> epochReadySteps = new LinkedHashMap<>();
        Set<Integer> activeEpochs = new LinkedHashSet<>();
        for (DagState dag : dbSnapshot.getDags().values()) {
            for (int activeEpoch : dag.getActiveEpochs()) {
                activeEpochs.add(activeEpoch);
                EpochState es = dag.getEpochState(activeEpoch);
                if (es != null && !es.getReadyNodeIds().isEmpty()) {
                    epochReadySteps.merge(
                            String.valueOf(activeEpoch),
                            new ArrayList<>(es.getReadyNodeIds()),
                            (existing, incoming) -> { existing.addAll(incoming); return existing; }
                    );
                }
            }
        }
        if (!epochReadySteps.isEmpty()) {
            snapshot.put("epochReadySteps", epochReadySteps);
        }
        snapshot.put("activeEpochs", new ArrayList<>(activeEpochs));

        // Cumulative execution duration across all closed epochs + live active epochs.
        // totalDurationMs from StateSnapshot = sum of closed epoch durations (accumulated).
        // For active epochs, add live duration (now - startedAt) on top.
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
        snapshot.put("totalDurationMs", totalDurationMs);

        try {
            StateSnapshotService.RunEpochInfo runInfo = stateSnapshotService.getRunEpochInfo(runId);

            // Workflow status for run info panel (keeps status badge in sync)
            Map<String, Object> workflowStatus = new LinkedHashMap<>();
            workflowStatus.put("status", runInfo.status());
            snapshot.put("workflowStatus", workflowStatus);
        } catch (Exception e) {
            log.debug("[Snapshot] Could not load run info for runId={}: {}", runId, e.getMessage());
        }

        // Epoch timestamps from workflow_epochs table (source of truth).
        // Replaces the growing metadata.epochTimestamps array.
        try {
            var epochTimestamps = workflowEpochService.listEpochTimestamps(runId);
            if (!epochTimestamps.isEmpty()) {
                snapshot.put("epochTimestamps", epochTimestamps);
            }
        } catch (Exception e) {
            log.debug("[Snapshot] Could not load epoch timestamps for runId={}: {}", runId, e.getMessage());
        }

        // Empty collections for other fields (can be populated if needed)
        snapshot.put("loops", List.of());
        snapshot.put("merges", List.of());
        snapshot.put("logs", List.of());
        snapshot.put("agentToolCalls", List.of());

        return snapshot;
    }

    private List<Map<String, Object>> buildSteps(
            StateSnapshot dbSnapshot,
            Set<String> awaitingSignalNodeIds,
            Map<String, Integer> awaitingSignalCounts,
            Map<String, Integer> runningCounts,
            Map<String, Map<String, Object>> interfaceSignalConfigs,
            Set<String> planNodeIds) {

        List<Map<String, Object>> steps = new ArrayList<>();
        Set<String> processedNodeIds = new java.util.HashSet<>();

        // DB StateSnapshot is authoritative over in-memory RunningNodeTracker.
        // If DB says a node is completed/failed/skipped, ignore stale overlay.
        Set<String> terminalNodeIds = new HashSet<>();
        terminalNodeIds.addAll(dbSnapshot.getCompletedNodeIds());
        terminalNodeIds.addAll(dbSnapshot.getFailedNodeIds());
        terminalNodeIds.addAll(dbSnapshot.getSkippedNodeIds());

        // Nodes explicitly ready for execution (after rerun, the EpochState is reset but
        // NodeCounts still has accumulated completed/failed - readyNodeIds overrides).
        Set<String> readyNodeIds = dbSnapshot.getReadyNodeIds();

        // Pre-compute: are there any active epochs? If so, nodes NOT in flat views
        // should show "pending" (not yet executed in current epoch), not historical status.
        // When no epochs are active (WAITING_TRIGGER, COMPLETED), historical counts are fine.
        // Sentinel-aware (StateSnapshot.hasAnyActiveEpoch): an epoch stranded on the
        // trigger:default migration sentinel must not make historical nodes render as
        // "pending" - keep this in lockstep with the run-status accounting.
        boolean hasAnyActiveEpoch = dbSnapshot.hasAnyActiveEpoch();

        for (Map.Entry<String, StateSnapshot.NodeCounts> entry : dbSnapshot.getNodes().entrySet()) {
            String nodeId = entry.getKey();
            StateSnapshot.NodeCounts dbCounts = entry.getValue();
            int overlayRunning = terminalNodeIds.contains(nodeId) ? 0 : runningCounts.getOrDefault(nodeId, 0);

            if (dbCounts.total() == 0 && overlayRunning == 0
                    && awaitingSignalCounts.getOrDefault(nodeId, 0) == 0) {
                continue;
            }

            processedNodeIds.add(nodeId);

            // Merge: running from in-memory tracker, completed/failed/skipped from DB
            StateSnapshot.NodeCounts mergedCounts = new StateSnapshot.NodeCounts(
                overlayRunning, dbCounts.completed(), dbCounts.failed(), dbCounts.skipped(),
                dbCounts.totalExecutionTimeMs(), dbCounts.lastEndTimeMs(), dbCounts.lastExecutionTimeMs());

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", nodeId);
            step.put("label", StateUtils.extractNodeLabel(nodeId));

            // Determine status from merged counts (with overrides for ready/awaiting nodes).
            //
            // Ready override: After a rerun, NodeCounts still has completed/failed from
            // previous execution, but the EpochState was reset and the node is in readyNodeIds.
            // Show "pending" so the frontend displays the execute button.
            //
            // Awaiting signal override: awaitingSignalNodeIds comes from the flat view
            // (active epochs). If a node is awaiting signal in any active epoch, that IS
            // its current state - show "awaiting_signal" regardless of historical completions
            // from prior closed epochs (those are reflected in statusCounts, not status).
            boolean isReady = readyNodeIds != null && readyNodeIds.contains(nodeId);
            boolean isAwaitingSignal = awaitingSignalNodeIds != null && awaitingSignalNodeIds.contains(nodeId);
            // Check if node has any state in active epochs' flat views.
            // If not, it only has historical NodeCounts from closed epochs - show "pending".
            boolean isInFlatView = terminalNodeIds.contains(nodeId)
                    || (readyNodeIds != null && readyNodeIds.contains(nodeId))
                    || (awaitingSignalNodeIds != null && awaitingSignalNodeIds.contains(nodeId))
                    || overlayRunning > 0;
            String baseStatus = determineStatus(mergedCounts);
            String status;
            if (isReady) {
                status = "pending";
            } else if (isAwaitingSignal) {
                status = "awaiting_signal";
            } else if (!isInFlatView && hasAnyActiveEpoch) {
                // Node has historical NodeCounts but no state in any active epoch.
                // This happens after a new trigger fire (new epoch): downstream nodes
                // haven't been executed yet. Show "pending" - statusCounts still has
                // the historical data for the badge display.
                status = "pending";
            } else {
                status = baseStatus;
            }
            step.put("status", status);

            // Add statusCounts
            Map<String, Object> statusCounts = new LinkedHashMap<>();
            statusCounts.put("running", mergedCounts.running());
            statusCounts.put("completed", mergedCounts.completed());
            statusCounts.put("failed", mergedCounts.failed());
            statusCounts.put("skipped", mergedCounts.skipped());
            statusCounts.put("awaitingSignal", awaitingSignalCounts.getOrDefault(nodeId, 0));
            statusCounts.put("total", mergedCounts.total());
            step.put("statusCounts", statusCounts);

            // Enrich with timing data from NodeCounts (accumulated in StateSnapshot JSONB).
            // displayDurationMs() prefers lastExecutionTimeMs (most recent execution) and
            // falls back to totalExecutionTimeMs for old snapshots without the new field.
            long displayDuration = dbCounts.displayDurationMs();
            if (displayDuration > 0 || dbCounts.totalExecutionTimeMs() > 0) {
                step.put("executionTimeMs", displayDuration);
                step.put("totalExecutionTimeMs", dbCounts.totalExecutionTimeMs());
                if (dbCounts.lastEndTimeMs() > 0) {
                    Instant endTime = Instant.ofEpochMilli(dbCounts.lastEndTimeMs());
                    step.put("endTime", endTime.toString());
                    // startTime = lastEndTimeMs - last execution duration (NOT cumulative)
                    step.put("startTime", Instant.ofEpochMilli(
                            dbCounts.lastEndTimeMs() - displayDuration).toString());
                }
            }

            // Include selectedBranch from decisionBranches (for decision, switch, approval nodes)
            // so the frontend statusUpdater can read output.selectedBranch and show branch coloring
            Set<String> branches = dbSnapshot.getDecisionBranches() != null
                ? dbSnapshot.getDecisionBranches().get(nodeId) : null;
            if (branches != null && !branches.isEmpty()) {
                Map<String, Object> stepOutput = new LinkedHashMap<>();
                stepOutput.put("selectedBranch", branches.iterator().next());
                step.put("output", stepOutput);
            }

            // Enrich interface awaiting_signal nodes with signal config (interface_id, action_mapping)
            // so the frontend can identify which interface to open in the application panel.
            enrichInterfaceOutput(step, nodeId, isAwaitingSignal, interfaceSignalConfigs);

            steps.add(step);
        }

        // Add nodes that are running but not yet in DB snapshot (skip terminal nodes)
        for (Map.Entry<String, Integer> entry : runningCounts.entrySet()) {
            String nodeId = entry.getKey();
            if (!processedNodeIds.contains(nodeId) && entry.getValue() > 0 && !terminalNodeIds.contains(nodeId)) {
                processedNodeIds.add(nodeId);
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("id", nodeId);
                step.put("label", StateUtils.extractNodeLabel(nodeId));
                step.put("status", "running");

                Map<String, Object> statusCounts = new LinkedHashMap<>();
                statusCounts.put("running", entry.getValue());
                statusCounts.put("completed", 0);
                statusCounts.put("failed", 0);
                statusCounts.put("skipped", 0);
                statusCounts.put("total", entry.getValue());
                step.put("statusCounts", statusCounts);

                steps.add(step);
            }
        }

        // Add nodes that are awaiting signal but not yet in DB snapshot
        // (nodes that yielded with AWAITING_SIGNAL have no NodeCounts - total==0 - so
        // the main loop skips them, and the running overlay loop skips them too because
        // emitNodeAwaitingSignal clears the running count).
        for (Map.Entry<String, Integer> entry : awaitingSignalCounts.entrySet()) {
            String nodeId = entry.getKey();
            if (!processedNodeIds.contains(nodeId) && entry.getValue() > 0) {
                processedNodeIds.add(nodeId);
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("id", nodeId);
                step.put("label", StateUtils.extractNodeLabel(nodeId));
                step.put("status", "awaiting_signal");

                Map<String, Object> statusCounts = new LinkedHashMap<>();
                statusCounts.put("running", 0);
                statusCounts.put("completed", 0);
                statusCounts.put("failed", 0);
                statusCounts.put("skipped", 0);
                statusCounts.put("awaitingSignal", entry.getValue());
                statusCounts.put("total", 0);
                step.put("statusCounts", statusCounts);

                // Enrich with signal config for interface nodes
                enrichInterfaceOutput(step, nodeId, true, interfaceSignalConfigs);

                steps.add(step);
            }
        }

        // Fill in plan nodes that haven't been touched yet (no NodeCounts, not running,
        // not awaiting signal). These nodes exist in the workflow plan but haven't started
        // execution. Without this, the frontend batch-update would only contain touched nodes,
        // causing untouched nodes to disappear from the canvas.
        if (planNodeIds != null) {
            for (String nodeId : planNodeIds) {
                if (!processedNodeIds.contains(nodeId)) {
                    processedNodeIds.add(nodeId);
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("id", nodeId);
                    step.put("label", StateUtils.extractNodeLabel(nodeId));
                    step.put("status", "pending");

                    Map<String, Object> statusCounts = new LinkedHashMap<>();
                    statusCounts.put("running", 0);
                    statusCounts.put("completed", 0);
                    statusCounts.put("failed", 0);
                    statusCounts.put("skipped", 0);
                    statusCounts.put("total", 0);
                    step.put("statusCounts", statusCounts);

                    steps.add(step);
                }
            }
        }

        // Sort by ID for consistent ordering
        steps.sort((a, b) -> {
            String first = Objects.toString(a.get("id"), "");
            String second = Objects.toString(b.get("id"), "");
            return first.compareTo(second);
        });

        return steps;
    }

    /**
     * Load active signals once per snapshot build. All downstream methods
     * (counts, interface configs, pending signals list) derive from this single load.
     */
    private List<SignalWaitEntity> loadActiveSignals(String runId) {
        if (unifiedSignalService == null) return List.of();
        try {
            return unifiedSignalService.getActiveSignals(runId);
        } catch (Exception e) {
            log.debug("[Snapshot] Could not load active signals for runId={}: {}", runId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Extract interface signal configs from pre-loaded active signals.
     */
    private Map<String, Map<String, Object>> extractInterfaceSignalConfigs(List<SignalWaitEntity> activeSignals) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (SignalWaitEntity signal : activeSignals) {
            if (signal.getSignalConfig() != null && signal.getNodeId() != null
                    && signal.getNodeId().startsWith("interface:")) {
                result.put(signal.getNodeId(), signal.getSignalConfig());
            }
        }
        return result;
    }

    /**
     * Enrich an interface awaiting_signal step with output fields from the signal config.
     * Adds interface_id and action_mapping to step.output so the frontend can identify
     * which interface to display without additional API calls.
     */
    @SuppressWarnings("unchecked")
    private void enrichInterfaceOutput(Map<String, Object> step, String nodeId,
                                        boolean isAwaitingSignal, Map<String, Map<String, Object>> interfaceSignalConfigs) {
        if (!isAwaitingSignal || !nodeId.startsWith("interface:")) return;
        Map<String, Object> config = interfaceSignalConfigs.get(nodeId);
        if (config == null) return;

        Map<String, Object> output = (Map<String, Object>) step.get("output");
        if (output == null) {
            output = new LinkedHashMap<>();
            step.put("output", output);
        }
        if (config.get("interfaceId") != null) {
            output.put("interface_id", config.get("interfaceId"));
        }
        if (config.get("actionMapping") != null) {
            output.put("action_mapping", config.get("actionMapping"));
        }
    }

    /**
     * Count how many active signals each node has.
     *
     * The epoch-based count (Set-based) only tells how many epochs have a node awaiting signal.
     * In split context, a single epoch may have multiple items awaiting (e.g., 5 items x 1 approval = 5 signals),
     * but the epoch set only contains the nodeId once, so it reports 1 instead of 5.
     *
     * We use the pre-loaded active signals for per-item accuracy, falling back to epoch-based counts
     * when no signals are loaded.
     */
    // Package-private for direct unit testing (see SnapshotServiceAwaitingCountsTest).
    Map<String, Integer> computeAwaitingSignalCounts(StateSnapshot snapshot, List<SignalWaitEntity> activeSignals) {
        // Start with epoch-based counts as baseline
        Map<String, Integer> counts = new HashMap<>();
        for (DagState dag : snapshot.getDags().values()) {
            for (int epochNum : dag.getActiveEpochs()) {
                EpochState epoch = dag.getEpochs().get(epochNum);
                if (epoch == null) continue;
                for (String nodeId : epoch.getAwaitingSignalNodeIds()) {
                    counts.merge(nodeId, 1, Integer::sum);
                }
            }
        }

        // Enhance with actual per-item counts from pre-loaded active signals.
        // In split context, a single epoch may have N active signals for the same nodeId.
        //
        // IMPORTANT: only count BLOCKING signals. Non-blocking interface signals
        // (no __continue in actionMapping) are registered as PENDING but the node
        // still returns SUCCESS and never enters awaitingSignalNodeIds on the
        // StateSnapshot. Counting them here would inflate statusCounts.awaitingSignal
        // and make the frontend `deriveStatusFromCounts` briefly show the node as
        // awaiting_signal (the yellow pause icon flash) before the next snapshot
        // cleanup clears it - confusing for passive/informational interfaces.
        if (!activeSignals.isEmpty()) {
            Map<String, Integer> signalCounts = new HashMap<>();
            for (SignalWaitEntity signal : activeSignals) {
                if (!signal.isBlocking()) continue;
                signalCounts.merge(signal.getNodeId(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : signalCounts.entrySet()) {
                // Use the higher count: signal-based (per-item) vs epoch-based (per-epoch)
                counts.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }

        return counts;
    }

    private String determineStatus(StateSnapshot.NodeCounts counts) {
        if (counts.running() > 0) {
            return "running";
        } else if (counts.failed() > 0) {
            return "failed";
        } else if (counts.skipped() > 0 && counts.completed() == 0) {
            return "skipped";
        } else if (counts.completed() > 0) {
            return "completed";
        }
        return "pending";
    }

    /**
     * Build edge data for the snapshot.
     * Overlays RUNNING state from node statuses: if a node is currently RUNNING,
     * its incoming edges (that have delivered data) are shown as RUNNING.
     *
     * @param dbSnapshot The persisted state snapshot
     * @param runningNodeIds Set of node IDs currently in RUNNING state
     */
    private List<Map<String, Object>> buildEdges(StateSnapshot dbSnapshot, Set<String> runningNodeIds) {
        List<Map<String, Object>> edges = new ArrayList<>();

        for (Map.Entry<String, StateSnapshot.EdgeCounts> entry : dbSnapshot.getEdges().entrySet()) {
            String edgeKey = entry.getKey(); // "from->to"
            StateSnapshot.EdgeCounts counts = entry.getValue();

            // Skip edges with no activity
            if (counts.total() == 0) {
                continue;
            }

            // Parse from->to
            String[] parts = edgeKey.split("->");
            if (parts.length != 2) {
                continue;
            }
            String from = parts[0];
            String to = parts[1];

            // Skip virtual loop nodes
            if (StateUtils.isVirtualLoopNodeId(from) || StateUtils.isVirtualLoopNodeId(to)) {
                continue;
            }

            // Derive edge RUNNING state from target node status.
            // If the target node is RUNNING and data was delivered (completed > 0),
            // the edge should also appear as RUNNING.
            // For ported edges (e.g., "core:decision:if" -> "mcp:step1"),
            // the target "to" is already the base node key (no port on target side).
            int runningCount = 0;
            if (counts.completed() > 0 && runningNodeIds.contains(to)) {
                runningCount = 1;
            }

            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", edgeKey);
            edge.put("from", from);
            edge.put("to", to);
            edge.put("running", runningCount);
            edge.put("completed", counts.completed());
            edge.put("skipped", counts.skipped());

            edges.add(edge);
        }

        // Sort by ID for consistent ordering
        edges.sort((a, b) -> {
            String first = Objects.toString(a.get("id"), "");
            String second = Objects.toString(b.get("id"), "");
            return first.compareTo(second);
        });

        return edges;
    }

    /**
     * Build pending signals list for frontend rendering.
     * Includes signal type, node ID, and expiration for countdown timers,
     * approval buttons, and webhook URL display.
     */
    /**
     * Build pending signal maps from pre-loaded active signals for frontend rendering.
     */
    private List<Map<String, Object>> buildPendingSignals(List<SignalWaitEntity> activeSignals) {
        if (activeSignals.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> signals = new ArrayList<>();
        for (SignalWaitEntity signal : activeSignals) {
            Map<String, Object> signalMap = new LinkedHashMap<>();
            signalMap.put("id", signal.getId());
            signalMap.put("nodeId", signal.getNodeId());
            signalMap.put("signalType", signal.getSignalType().name());
            signalMap.put("status", signal.getStatus().name());
            signalMap.put("epoch", signal.getEpoch());
            signalMap.put("itemId", signal.getItemId());
            if (signal.getExpiresAt() != null) {
                signalMap.put("expiresAt", signal.getExpiresAt().toString());
            }
            if (signal.getSignalConfig() != null) {
                signalMap.put("config", signal.getSignalConfig());
            }
            // Split context: expose the per-item data persisted at registration so the
            // approver can see WHAT they are approving (same key as the signals REST API).
            // DISPLAY-only projection - strip the cross-pod restoration keys (and the full
            // items list) so the repeatedly-streamed WS snapshot stays small.
            if (signal.getSplitItemData() != null && !signal.getSplitItemData().isEmpty()) {
                signalMap.put("itemContext",
                    com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager
                        .toDisplayItemContext(signal.getSplitItemData()));
            }
            // Configured approval context (contextTemplate resolved at yield), shown to the approver.
            if (signal.getApprovalContext() != null && !signal.getApprovalContext().isBlank()) {
                signalMap.put("approvalContext", signal.getApprovalContext());
            }
            signals.add(signalMap);
        }
        return signals;
    }

}

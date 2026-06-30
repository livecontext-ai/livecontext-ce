package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.repository.StateSnapshotSeqAndJsonProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Plan v4 §3 - run-scoped patch coalescer for the CAS hot path.
 *
 * <p>Replaces v2's tx-scoped {@code BatchedPatchSession} (broken under FJP
 * fan-out because items execute on {@code ForkJoinPool.commonPool} workers,
 * not the parent thread). The run-scoped design opens a session before
 * fan-out, lets every item-worker enqueue patches against the same session,
 * and returns a {@code CompletableFuture<Void>} per enqueuer. The flusher
 * batches up to 32 patches into a single chained {@code jsonb_set} CAS
 * UPDATE, completes the futures on tx commit.
 *
 * <p><b>POISON state (audit C M2):</b> {@code RunSession.state} is
 * {@code volatile}; on flusher rollback or final CAS conflict, the session
 * transitions ACTIVE → POISONED BEFORE futures complete exceptionally.
 * {@link #isCoalescing(String)} returns false for POISONED sessions so any
 * enqueuer that observes the transition falls through to per-patch CAS during
 * the teardown window.
 *
 * <p><b>ManagedBlocker (audit A M3):</b> {@link #awaitFlush(CompletableFuture)}
 * wraps {@code future.join()} in {@link ForkJoinPool.ManagedBlocker} so FJP
 * commonPool workers (typically running split fan-out per
 * {@code SplitAwareNodeExecutor}) don't starve when blocking on the flusher.
 *
 * <p><b>Re-mutate-on-flush (audit bug #2 - the core correctness fix):</b> the
 * production entry point is {@link #enqueueMutation(String, java.util.function.Function, String)}.
 * It enqueues the caller's {@code (StateSnapshot) → (patches, advanced snapshot)}
 * recompute closure, NOT a pre-built patch. At flush time the coalescer reads
 * the freshly-committed base ONCE, then re-runs every queued closure in order
 * against a running snapshot (each closure sees the effect of the previous one).
 * This is what makes two same-path ASSIGN patches (e.g. two split items each
 * adding their node to {@code epochs.E.completedNodeIds}) COMPOSE instead of
 * last-writer-wins clobber: the second closure's ASSIGN is built from a base
 * that already contains the first closure's write. DELTA+DELTA on the same path
 * still merges additively ({@code +N+M}); ASSIGN-involving same-path patches
 * take the LAST value, which is now correct because it was recomputed against
 * the running base. On CAS conflict the WHOLE chain re-reads + re-mutates.
 *
 * <p>The lower-level {@link #enqueuePatch(String, JsonbPatch, PatchClass.OpKind)}
 * commits frozen patches directly; it is the patch-commit primitive that the
 * flush engine and the unit tests exercise, but it is NOT vulnerable-path safe
 * for stale same-path ASSIGNs - production MUST use {@link #enqueueMutation}.
 *
 * <p><b>Semaphore cap:</b> 50 concurrent sessions per JVM. Beyond that,
 * {@link #openCoalescing(String)} returns null (caller falls back to
 * per-patch CAS - single-session correctness preserved).
 *
 * <p><b>Reaper:</b> {@link #reapIdleSessions()} runs every 60s, evicts
 * sessions with no patches enqueued in the last 10 minutes. Handles the
 * exception-path leak (caller skips closeCoalescing on throw - the
 * try-finally discipline at the {@code SplitAwareNodeExecutor} callsite is the
 * compile-time guard; the reaper is the runtime backstop).
 *
 * <p><b>Feature flag:</b> the bean instantiates behind
 * {@code orchestrator.optim.cas-state-snapshot} (default ON - the constructor
 * default below matches). Whether a coalescing session is actually OPENED for a
 * run is gated separately at the {@code SplitAwareNodeExecutor} callsite by
 * {@code orchestrator.optim.coalesce-split} (default OFF). When no session is
 * open, {@link #isCoalescing(String)} returns false and the canonical direct
 * CAS / pessimistic-lock path stays in effect.
 */
@Component
public class RunCoalescingService implements RunScopedCache {

    private static final Logger log = LoggerFactory.getLogger(RunCoalescingService.class);

    /** Plan §3 - caps and timings. */
    static final int MAX_PATCHES_PER_BATCH = 32;
    static final int MAX_CONCURRENT_SESSIONS = 50;
    static final Duration SESSION_IDLE_TTL = Duration.ofMinutes(10);
    static final long AWAIT_FLUSH_TIMEOUT_MS = 30_000L;
    /** Plan §1.7 CAS retry budget - 3 attempts with jittered backoff. */
    static final long[] CAS_RETRY_BACKOFF_MS = {1L, 5L, 15L};
    static final double CAS_RETRY_JITTER = 0.20;

    private final ConcurrentHashMap<String, RunSession> sessions = new ConcurrentHashMap<>();
    private final Semaphore concurrentSessionLimit = new Semaphore(MAX_CONCURRENT_SESSIONS);
    private final boolean coalescingEnabled;

    private final Counter sessionsOpenedCounter;
    private final Counter sessionsPoisonedCounter;
    private final Counter sessionsReapedCounter;
    private final Counter collisionMergeCounter;
    private final Counter collisionForceFlushCounter;
    private final Counter managedBlockCounter;
    private final Counter awaitTimeoutCounter;
    private final Counter capacityRejectCounter;
    private final Counter flushOkCounter;
    private final Counter flushBatchSizeCounter;
    private final Counter casConflictCounter;
    private final Counter casRetryExhaustedCounter;

    /**
     * Plan v4 §3 flusher dependencies - optional so existing unit tests that
     * exercise lifecycle/POISON/collision logic don't need to wire a JDBC
     * executor + repo. Required for {@link #flush} to actually call
     * {@code applyPatchesCas}; if either is null, {@link #flush} no-ops
     * after marking the session POISONED and completing futures exceptionally.
     */
    @Autowired(required = false)
    private JsonbPatchExecutor patchExecutor;

    @Autowired(required = false)
    private WorkflowRunRepository runRepository;

    /**
     * Audit bug #3 (cross-tx flush) mitigation - the flush runs its CAS UPDATE
     * in its OWN {@code REQUIRES_NEW} transaction so a peer enqueuer's outer-tx
     * rollback cannot retro-invalidate a flush that already committed other
     * peers' patches. Optional: when null (narrow unit tests that don't boot a
     * tx manager), the flush calls {@code applyPatchesCas} directly - those
     * tests run single-threaded with a mocked executor, so the cross-tx concern
     * does not apply.
     */
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    /** Lazily-built REQUIRES_NEW template (audit bug #3). Null when no tx manager. */
    private volatile TransactionTemplate requiresNewTx;

    public RunCoalescingService(
            MeterRegistry meterRegistry,
            @Value("${orchestrator.optim.cas-state-snapshot:true}") boolean coalescingEnabled) {
        this.coalescingEnabled = coalescingEnabled;
        this.sessionsOpenedCounter = Counter.builder("orchestrator.coalesce.session_opened_count")
                .description("Plan v4 §3 - RunSessions opened (one per fan-out scope)")
                .register(meterRegistry);
        this.sessionsPoisonedCounter = Counter.builder("orchestrator.coalesce.session_poisoned_count")
                .description("Plan v4 §3 - RunSessions poisoned on flusher rollback or final CAS conflict")
                .register(meterRegistry);
        this.sessionsReapedCounter = Counter.builder("orchestrator.coalesce.session_reaped_count")
                .description("Plan v4 §3 - sessions evicted by the 10-min idle reaper (exception-path leak guard)")
                .register(meterRegistry);
        this.collisionMergeCounter = Counter.builder("orchestrator.coalesce.collision_merge_count")
                .description("Plan v4 §3 - DELTA+DELTA same-path merges on COMMUTATIVE_DELTA opKind")
                .register(meterRegistry);
        this.collisionForceFlushCounter = Counter.builder("orchestrator.coalesce.collision_force_flush_count")
                .description("Plan v4 §3 - DELTA+ASSIGN / ASSIGN+ASSIGN same-path force-flushes")
                .register(meterRegistry);
        this.managedBlockCounter = Counter.builder("orchestrator.coalesce.managed_block_count")
                .description("Plan v4 §3 - awaitFlush calls wrapping ForkJoinPool.managedBlock")
                .register(meterRegistry);
        this.awaitTimeoutCounter = Counter.builder("orchestrator.coalesce.await_timeout_count")
                .description("Plan v4 §3 - awaitFlush 30s timeouts (caller fell back to per-patch CAS)")
                .register(meterRegistry);
        this.capacityRejectCounter = Counter.builder("orchestrator.coalesce.capacity_reject_count")
                .description("Plan v4 §3 - openCoalescing returned null because the 50-session cap was full")
                .register(meterRegistry);
        this.flushOkCounter = Counter.builder("orchestrator.coalesce.flush_ok_count")
                .description("Plan v4 §3 - flushes that committed successfully via applyPatchesCas")
                .register(meterRegistry);
        // Audit S5 - Micrometer auto-appends `_total` on Prometheus export for counters.
        // Naming without the suffix avoids the double `_total_total` after export.
        this.flushBatchSizeCounter = Counter.builder("orchestrator.coalesce.flush_batch_size")
                .description("Plan v4 §3 - total patches successfully flushed (divide by flush_ok_count for avg batch size)")
                .register(meterRegistry);
        this.casConflictCounter = Counter.builder("orchestrator.coalesce.cas_conflict_count")
                .description("Plan v4 §3 - applyPatchesCas returned rowCount=0 (peer commit raced)")
                .register(meterRegistry);
        this.casRetryExhaustedCounter = Counter.builder("orchestrator.coalesce.cas_retry_exhausted_count")
                .description("Plan v4 §3 - CAS retry budget exhausted; flusher POISONED the session + replayed per-patch")
                .register(meterRegistry);

        if (coalescingEnabled) {
            log.info("[RunCoalescingService] coalescing bean ENABLED (plan v4 §3) - max {} concurrent sessions, "
                    + "{}-patch batch cap, {} idle-TTL. Session opening is gated per-callsite "
                    + "(SplitAwareNodeExecutor: orchestrator.optim.coalesce-split, default OFF).",
                    MAX_CONCURRENT_SESSIONS, MAX_PATCHES_PER_BATCH, SESSION_IDLE_TTL);
        } else {
            log.info("[RunCoalescingService] coalescing bean DISABLED - pessimistic-lock path canonical");
        }
    }

    /**
     * Open a coalescing session for the given run. Returns the session, or
     * {@code null} if the global 50-session cap is full (caller MUST then
     * fall through to per-patch CAS - single-session correctness preserved).
     *
     * <p>Caller MUST wrap the matching {@link #closeCoalescing(String)} in a
     * try-finally - the ArchUnit rule pinning this contract ships with the
     * full #1+#3 bundle.
     */
    public RunSession openCoalescing(String runId) {
        Objects.requireNonNull(runId, "runId");
        if (!coalescingEnabled) {
            return null;
        }
        if (!concurrentSessionLimit.tryAcquire()) {
            capacityRejectCounter.increment();
            return null;
        }
        try {
            RunSession session = new RunSession(runId, Instant.now());
            RunSession prior = sessions.putIfAbsent(runId, session);
            if (prior != null) {
                // Another caller is already coalescing this run - release our
                // ad-hoc semaphore permit (not tracked by RunSession.permitReleased
                // because this permit never belonged to a live session) and
                // share the existing session via refcount.
                concurrentSessionLimit.release();
                prior.openCount.incrementAndGet();
                return prior;
            }
            session.openCount.incrementAndGet();
            sessionsOpenedCounter.increment();
            return session;
        } catch (RuntimeException ex) {
            // Same as the "prior != null" branch: this permit was never claimed
            // by a live session, so no permitReleased flag to flip.
            concurrentSessionLimit.release();
            throw ex;
        }
    }

    /**
     * Close a previously-opened session. Flushes any remaining queued
     * patches before evicting. Idempotent - safe to call twice.
     */
    public void closeCoalescing(String runId) {
        if (runId == null) return;
        RunSession session = sessions.get(runId);
        if (session == null) return;
        int remaining = session.openCount.decrementAndGet();
        if (remaining <= 0) {
            // Drain residual queue before evict - caller-thread flush so
            // futures complete before close returns. Matches plan §3
            // "Triggered by: ... OR explicit close" semantics.
            //
            // Audit S1 - synchronize on session for defense-in-depth.
            // Today closeCoalescing-at-refcount-zero is unreachable while an
            // enqueuer holds the monitor (every enqueuer must hold an open
            // ref), but we don't rely on caller discipline: take the monitor
            // so a future caller's reordering can't race the flush.
            synchronized (session) {
                if (session.state == SessionState.ACTIVE && !session.mutationQueue.isEmpty()) {
                    flushMutations(session);
                }
                if (session.state == SessionState.ACTIVE && !session.patchQueue.isEmpty()) {
                    flush(session);
                }
            }
            sessions.remove(runId, session);
            releasePermitOnce(session);
        }
    }

    /**
     * Lower-level patch-commit primitive - enqueue a PRE-BUILT {@link JsonbPatch}
     * into the active session. Returns a {@link CompletableFuture} that completes
     * when the flusher commits the batch (success or POISON-replay).
     *
     * <p><b>NOT the production split path.</b> Pre-built ASSIGN patches freeze a
     * snapshot taken at enqueue time, so two same-path ASSIGNs built from the
     * SAME stale base would last-writer-wins clobber (audit bug #2). Production
     * MUST use {@link #enqueueMutation(String, java.util.function.Function, String)},
     * which re-runs the caller's mutator against the freshly-flushed base. This
     * method stays as the patch engine that the flush/CAS/POISON machinery and
     * the unit tests exercise; it is safe for DELTA patches (they merge
     * additively) and for ASSIGNs whose value does not depend on a concurrently
     * mutated base (e.g. {@code seq}).
     *
     * <ul>
     *   <li>No active session OR session POISONED → returns a future
     *       exceptionally completed; caller falls through to per-patch CAS.</li>
     *   <li>Same-path COMMUTATIVE_DELTA+COMMUTATIVE_DELTA collision → MERGE
     *       (sum the deltas into one queued patch).</li>
     *   <li>Same-path ASSIGN+ASSIGN or DELTA+ASSIGN collision → force-flush
     *       current queue, enqueue the new patch into a fresh queue.</li>
     *   <li>Queue size hits {@link #MAX_PATCHES_PER_BATCH} → flush.</li>
     * </ul>
     */
    public CompletableFuture<Void> enqueuePatch(String runId, JsonbPatch patch,
                                                PatchClass.OpKind opKind) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(opKind, "opKind");
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!coalescingEnabled) {
            future.completeExceptionally(new IllegalStateException("coalescing disabled"));
            return future;
        }
        RunSession session = sessions.get(runId);
        if (session == null) {
            future.completeExceptionally(new IllegalStateException("no active session for runId=" + runId));
            return future;
        }
        if (session.state != SessionState.ACTIVE) {
            future.completeExceptionally(new IllegalStateException(
                    "session POISONED for runId=" + runId));
            return future;
        }
        synchronized (session) {
            CollisionDecision decision = detectCollision(session.patchQueue, patch, opKind);
            if (decision == CollisionDecision.FORCE_FLUSH) {
                flush(session);
                session.patchQueue.add(new EnqueuedPatch(patch, opKind, future));
            } else if (decision == CollisionDecision.MERGE) {
                // Plan v4 §2b - DELTA+DELTA on COMMUTATIVE_DELTA: sum the
                // deltas into the existing queued patch. The single future
                // for the existing entry covers BOTH callers; we link the
                // new future to the existing one so both complete on the
                // next flush.
                mergeDeltaInPlace(session.patchQueue, patch, future);
            } else {
                session.patchQueue.add(new EnqueuedPatch(patch, opKind, future));
            }
            session.lastActivity = Instant.now();
            if (session.patchQueue.size() >= MAX_PATCHES_PER_BATCH) {
                flush(session);
            }
        }
        return future;
    }

    /**
     * Audit bug #2 fix - enqueue a RECOMPUTE CLOSURE (not a frozen patch).
     *
     * <p>The closure {@code recompute} is {@code (StateSnapshot base) → (patches,
     * advanced snapshot)}: given a base snapshot it runs the caller's Java
     * mutator + patch builder and returns both the resulting patches and the
     * post-mutation snapshot. The coalescer re-runs every queued closure against
     * the freshly-flushed base at flush time, advancing the running base by each
     * closure's result so concurrent same-path ASSIGNs COMPOSE instead of
     * clobbering (read-modify-write semantics, identical to the direct CAS path).
     *
     * <p>The {@code bridge} (parse + post-flush cache update) is set on the
     * session by the first enqueuer and reused for the run's lifetime. It must
     * be non-null for the mutation path; callers obtain it from
     * {@code StateSnapshotService}.
     *
     * <p>Returns a {@link CompletableFuture} completing when the flush commits.
     * Behaves like {@link #enqueuePatch} for lifecycle: no/POISONED session →
     * exceptional future (caller falls through to direct CAS).
     *
     * <p><b>Flush triggering:</b> the enqueuer MUST drive the flush - call
     * {@link #flushPendingMutations(String)} then {@link #awaitFlush} after
     * enqueuing. Reaching {@link #MAX_PATCHES_PER_BATCH} queued mutations also
     * triggers a flush; {@link #closeCoalescing(String)} drains any remainder.
     * (Enqueuing without ever triggering a flush would block {@code awaitFlush}
     * indefinitely - there is no background timer flusher.)
     *
     * @param runId      target run public id
     * @param recompute  re-runnable mutator+builder closure (see above)
     * @param mutatorName metric tag (e.g. {@code "markNodeCompleted"})
     */
    public CompletableFuture<Void> enqueueMutation(String runId,
                                                   Function<StateSnapshot, RecomputeOutput> recompute,
                                                   String mutatorName) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(recompute, "recompute");
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!coalescingEnabled) {
            future.completeExceptionally(new IllegalStateException("coalescing disabled"));
            return future;
        }
        RunSession session = sessions.get(runId);
        if (session == null) {
            future.completeExceptionally(new IllegalStateException("no active session for runId=" + runId));
            return future;
        }
        if (session.state != SessionState.ACTIVE) {
            future.completeExceptionally(new IllegalStateException("session POISONED for runId=" + runId));
            return future;
        }
        synchronized (session) {
            session.mutationQueue.add(new CoalescedMutation(recompute, mutatorName, future));
            session.lastActivity = Instant.now();
            // Cap on mutation count keeps the recompute chain bounded and flush
            // latency predictable. Concurrent peers that enqueued before the cap
            // was hit are drained in the same flush (coalesced).
            if (session.mutationQueue.size() >= MAX_PATCHES_PER_BATCH) {
                flushMutations(session);
            }
        }
        return future;
    }

    /**
     * Trigger a flush of any pending mutations for the run. Idempotent and
     * cheap when the queue is empty (the just-enqueued caller may already have
     * been flushed by a peer hitting the cap, or by another enqueuer's
     * flushPendingMutations). The enqueuer calls this then {@link #awaitFlush}.
     *
     * <p>No-op when no/POISONED session (the awaited future is already
     * exceptionally completed in that case).
     */
    public void flushPendingMutations(String runId) {
        if (runId == null) return;
        RunSession session = sessions.get(runId);
        if (session == null) return;
        synchronized (session) {
            if (session.state == SessionState.ACTIVE && !session.mutationQueue.isEmpty()) {
                flushMutations(session);
            }
        }
    }

    /**
     * Set the parse + post-flush-cache bridge for a run's session. Idempotent;
     * the first non-null bridge wins (every enqueuer of the same run supplies
     * the same singleton {@code StateSnapshotService}). No-op if no session.
     */
    public void bindMutationBridge(String runId, MutationFlushBridge bridge) {
        if (runId == null || bridge == null) return;
        RunSession session = sessions.get(runId);
        if (session == null) return;
        synchronized (session) {
            if (session.bridge == null) {
                session.bridge = bridge;
            }
        }
    }

    /**
     * Audit bug #2 fix - flush the mutation queue by re-running every queued
     * recompute closure against the freshly-flushed base, in order, advancing
     * the running base by each closure's output. The folded patch list is then
     * committed via a single CAS UPDATE with the standard retry budget.
     *
     * <p>Caller MUST hold {@code synchronized (session)}.
     *
     * <p>Folding rules for same-path patches across the recomputed batch:
     * <ul>
     *   <li>DELTA + DELTA → sum (additive, order-independent).</li>
     *   <li>any ASSIGN involved → keep the LAST value. Safe here because the
     *       last value was recomputed against a running base that already
     *       reflects every earlier mutation in this batch.</li>
     * </ul>
     * The top-level {@code seq} patch is canonicalized to {@code expectedSeq+1}
     * so the JSON {@code seq} field matches the SQL {@code state_snapshot_seq}
     * column stamped by {@code applyPatchesCas} - one increment per flush.
     */
    void flushMutations(RunSession session) {
        if (session.state != SessionState.ACTIVE) {
            return;  // already POISONED - no-op
        }
        if (patchExecutor == null || runRepository == null || session.bridge == null) {
            poison(session.runId, new IllegalStateException(
                    "RunCoalescingService mutation flush missing patchExecutor/runRepository/bridge"));
            completeAllMutationsExceptionally(session, new IllegalStateException("coalescer mutation path not wired"));
            session.mutationQueue.clear();
            return;
        }
        List<CoalescedMutation> batch = new ArrayList<>(session.mutationQueue);
        if (batch.isEmpty()) {
            return;
        }
        session.mutationQueue.clear();

        Throwable lastError = null;
        for (int attempt = 0; attempt < CAS_RETRY_BACKOFF_MS.length; attempt++) {
            Optional<StateSnapshotSeqAndJsonProjection> freshOpt =
                    runRepository.findSeqAndStateSnapshotByRunIdPublic(session.runId);
            if (freshOpt.isEmpty()) {
                lastError = new IllegalStateException(
                        "workflow_runs row missing for runId=" + session.runId);
                break;
            }
            long expectedSeq = freshOpt.get().getStateSnapshotSeq();
            long newSeq = expectedSeq + 1L;
            String baseJson = freshOpt.get().getStateSnapshot();

            // Re-run the recompute chain against the fresh base. Any fallback
            // (e.g. epoch not materialized - jsonb_set can't create it) aborts
            // the whole coalesced flush and routes every queued future to the
            // pessimistic fallback (exceptional completion).
            List<JsonbPatch> folded;
            try {
                StateSnapshot base = session.bridge.parseBase(session.runId, baseJson);
                folded = recomputeAndFold(batch, base, newSeq);
            } catch (FlushFallbackException ffe) {
                // Builder asked for full-rewrite fallback - not retryable via CAS.
                lastError = ffe;
                break;
            } catch (RuntimeException ex) {
                lastError = ex;
                break;
            }
            if (folded.isEmpty()) {
                // Every mutation was a no-op - nothing to write, all succeed.
                completeAllMutationsNormally(batch);
                return;
            }

            try {
                int rows = applyPatchesCasMaybeNewTx(session.runId, folded, expectedSeq, newSeq);
                if (rows == 1) {
                    flushOkCounter.increment();
                    flushBatchSizeCounter.increment(folded.size());
                    // Bug #1 - read-after-write: invalidate the run's read caches
                    // (txCache + snapshotJsonCache) so the enqueuing caller's next
                    // getSnapshot re-reads the just-committed merged state from DB
                    // (now at newSeq) rather than a stale pre-flush parse. The
                    // bridge owns the cache tiers; best-effort, cache failure is
                    // non-fatal.
                    try {
                        session.bridge.afterCoalescedFlush(session.runId, newSeq);
                    } catch (RuntimeException cacheEx) {
                        log.warn("[RunCoalescingService] post-flush cache invalidation failed for runId={}: {}",
                                session.runId, cacheEx.getMessage());
                    }
                    completeAllMutationsNormally(batch);
                    return;
                }
                // rows == 0 → CAS conflict
                casConflictCounter.increment();
                lastError = new IllegalStateException(
                        "CAS conflict on mutation flush attempt " + attempt + " (expectedSeq=" + expectedSeq + ")");
            } catch (RuntimeException ex) {
                lastError = ex;
                break;
            }
            if (attempt < CAS_RETRY_BACKOFF_MS.length - 1) {
                long baseMs = CAS_RETRY_BACKOFF_MS[attempt];
                long jitter = (long) (baseMs * CAS_RETRY_JITTER * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
                LockSupport.parkNanos((baseMs + jitter) * 1_000_000L);
            }
        }
        casRetryExhaustedCounter.increment();
        poison(session.runId, lastError);
        completeAllMutationsExceptionally(batch, lastError);
    }

    /**
     * Re-run the recompute chain against {@code base}, advancing the running
     * snapshot per mutation, and FOLD same-path patches (DELTA sum / ASSIGN
     * last-wins). Returns the deduped, ordered patch list with a canonical
     * {@code seq} patch = {@code newSeq}. Throws {@link FlushFallbackException}
     * if any builder returns fallback.
     */
    private static List<JsonbPatch> recomputeAndFold(List<CoalescedMutation> batch,
                                                     StateSnapshot base, long newSeq) {
        // LinkedHashMap keyed by path preserves first-seen order while letting
        // same-path patches fold in place.
        LinkedHashMap<String, JsonbPatch> folded = new LinkedHashMap<>();
        StateSnapshot running = base;
        for (CoalescedMutation m : batch) {
            RecomputeOutput out = m.recompute().apply(running);
            if (out == null || out.fallbackRequested()) {
                throw new FlushFallbackException("recompute requested full-rewrite fallback for " + m.mutatorName());
            }
            if (out.advanced() != null) {
                running = out.advanced();
            }
            if (out.patches() == null) {
                continue;  // no-op mutation
            }
            for (JsonbPatch p : out.patches()) {
                String key = pathKey(p.path());
                JsonbPatch existing = folded.get(key);
                if (existing != null
                        && existing.opKind() == JsonbPatch.OpKind.COMMUTATIVE_DELTA
                        && p.opKind() == JsonbPatch.OpKind.COMMUTATIVE_DELTA) {
                    long sum = Long.parseLong(existing.jsonValue()) + Long.parseLong(p.jsonValue());
                    folded.put(key, JsonbPatch.commutativeDelta(p.path(), sum));
                } else {
                    // ASSIGN (or DELTA-replacing-ASSIGN / ASSIGN-replacing-DELTA):
                    // keep the LAST - recomputed against the running base, so correct.
                    folded.put(key, p);
                }
            }
        }
        if (folded.isEmpty()) {
            return List.of();
        }
        // Canonicalize seq to one increment per flush (matches the SQL column).
        folded.put(pathKey(new String[]{"seq"}),
                JsonbPatch.assignment(new String[]{"seq"}, Long.toString(newSeq)));
        return new ArrayList<>(folded.values());
    }

    /**
     * Run {@code applyPatchesCas} in a {@code REQUIRES_NEW} transaction when a
     * tx manager is wired (audit bug #3 - the committed flush survives a peer's
     * outer-tx rollback). When no tx manager (narrow unit tests), call directly.
     */
    private int applyPatchesCasMaybeNewTx(String runId, List<JsonbPatch> patches,
                                          long expectedSeq, long newSeq) {
        if (transactionManager == null) {
            return patchExecutor.applyPatchesCas(runId, patches, expectedSeq, newSeq);
        }
        TransactionTemplate tpl = requiresNewTx;
        if (tpl == null) {
            tpl = new TransactionTemplate(transactionManager);
            tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            requiresNewTx = tpl;
        }
        return tpl.execute(status -> patchExecutor.applyPatchesCas(runId, patches, expectedSeq, newSeq));
    }

    private static String pathKey(String[] path) {
        // ',' is a safe folding-key separator: JsonbPatch forbids ',' inside any
        // path element (it is the Postgres text[] array separator).
        return String.join(",", path);
    }

    private static void completeAllMutationsNormally(List<CoalescedMutation> batch) {
        for (CoalescedMutation m : batch) {
            m.future().complete(null);
        }
    }

    private static void completeAllMutationsExceptionally(List<CoalescedMutation> batch, Throwable cause) {
        Throwable ex = cause == null ? new IllegalStateException("mutation flush failed with no cause") : cause;
        for (CoalescedMutation m : batch) {
            m.future().completeExceptionally(ex);
        }
    }

    private static void completeAllMutationsExceptionally(RunSession session, Throwable cause) {
        completeAllMutationsExceptionally(new ArrayList<>(session.mutationQueue), cause);
    }

    /**
     * Plan v4 §2b - sum the new delta into the existing same-path DELTA patch.
     * Both callers' futures complete on the same flush: the existing entry's
     * future is replaced with a composite future, and the new caller's future
     * is linked to fire alongside.
     *
     * <p>Caller MUST hold {@code synchronized (session)}.
     */
    private static void mergeDeltaInPlace(List<EnqueuedPatch> queue, JsonbPatch newPatch,
                                          CompletableFuture<Void> newFuture) {
        for (int i = 0; i < queue.size(); i++) {
            EnqueuedPatch existing = queue.get(i);
            if (samePath(existing.patch().path(), newPatch.path())
                    && existing.opKind() == PatchClass.OpKind.COMMUTATIVE_DELTA
                    && newPatch.opKind() == JsonbPatch.OpKind.COMMUTATIVE_DELTA) {
                long existingDelta = Long.parseLong(existing.patch().jsonValue());
                long newDelta = Long.parseLong(newPatch.jsonValue());
                JsonbPatch merged = JsonbPatch.commutativeDelta(newPatch.path(), existingDelta + newDelta);
                // Chain: when the existing future completes, complete the new future
                // with the same outcome (normal or exceptional).
                existing.future().whenComplete((v, ex) -> {
                    if (ex != null) {
                        newFuture.completeExceptionally(ex);
                    } else {
                        newFuture.complete(null);
                    }
                });
                queue.set(i, new EnqueuedPatch(merged, existing.opKind(), existing.future()));
                return;
            }
        }
        // Shouldn't happen - detectCollision returned MERGE so there MUST be a
        // matching entry. Defensive fallback: append as a new patch.
        queue.add(new EnqueuedPatch(newPatch, PatchClass.OpKind.COMMUTATIVE_DELTA, newFuture));
    }

    /**
     * Frozen-patch flusher - drains the {@code patchQueue} into a single
     * {@code applyPatchesCas} call with retry budget. This is the patch-commit
     * primitive exercised by the unit tests and the lower-level
     * {@link #enqueuePatch} path. The PRODUCTION split path uses
     * {@link #flushMutations} (re-mutate-on-flush + REQUIRES_NEW tx, audit bugs
     * #2/#3); this method does not run REQUIRES_NEW because its only callers
     * (tests) mock the executor - there is no real outer tx to isolate from.
     *
     * <p>Tx semantics: {@code applyPatchesCas} is
     * {@code @Transactional(REQUIRED)}; each retry attempt joins or starts
     * its own tx. Per-tx fresh-state memoize (plan §1.7) is deferred to a
     * follow-up - each retry re-reads the fresh seq independently. Slight
     * extra round-trip cost on retry; correctness preserved.
     *
     * <p>Failure modes:
     * <ul>
     *   <li>patchExecutor or runRepository null (test config) → POISON +
     *       exceptional-complete; caller falls through to per-patch CAS.</li>
     *   <li>3 CAS conflicts in a row → POISON + exceptional-complete +
     *       cas_retry_exhausted_count metric.</li>
     *   <li>Trigger violation (V181 seq regression) → bubbles up as
     *       RuntimeException; POISON + exceptional-complete.</li>
     * </ul>
     */
    void flush(RunSession session) {
        if (session.state != SessionState.ACTIVE) {
            return;  // already POISONED - no-op
        }
        if (patchExecutor == null || runRepository == null) {
            poison(session.runId, new IllegalStateException(
                    "RunCoalescingService missing patchExecutor or runRepository - wiring incomplete"));
            completeAllExceptionally(session, new IllegalStateException("coalescer not wired"));
            session.patchQueue.clear();
            return;
        }
        List<EnqueuedPatch> batch = new ArrayList<>(session.patchQueue);
        if (batch.isEmpty()) {
            return;
        }
        session.patchQueue.clear();
        List<JsonbPatch> patches = batch.stream().map(EnqueuedPatch::patch).toList();
        int batchSize = patches.size();

        Throwable lastError = null;
        for (int attempt = 0; attempt < CAS_RETRY_BACKOFF_MS.length; attempt++) {
            Optional<Long> currentSeqOpt = runRepository.findStateSnapshotSeqByRunIdPublic(session.runId);
            if (currentSeqOpt.isEmpty()) {
                lastError = new IllegalStateException(
                        "workflow_runs row missing for runId=" + session.runId);
                break;
            }
            long expectedSeq = currentSeqOpt.get();
            long newSeq = expectedSeq + 1L;
            try {
                int rows = patchExecutor.applyPatchesCas(session.runId, patches, expectedSeq, newSeq);
                if (rows == 1) {
                    flushOkCounter.increment();
                    flushBatchSizeCounter.increment(batchSize);
                    completeAllNormally(batch);
                    return;
                }
                // rows == 0 → CAS conflict
                casConflictCounter.increment();
                lastError = new IllegalStateException(
                        "CAS conflict on attempt " + attempt + " (expectedSeq=" + expectedSeq + ")");
            } catch (RuntimeException ex) {
                // Trigger violation, DB-down, deadlock, etc. - don't retry; bubble.
                lastError = ex;
                break;
            }
            // Jittered backoff before next retry (only if more retries remain).
            if (attempt < CAS_RETRY_BACKOFF_MS.length - 1) {
                long baseMs = CAS_RETRY_BACKOFF_MS[attempt];
                long jitter = (long) (baseMs * CAS_RETRY_JITTER * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
                LockSupport.parkNanos((baseMs + jitter) * 1_000_000L);
            }
        }
        // Retry budget exhausted OR fatal exception.
        casRetryExhaustedCounter.increment();
        poison(session.runId, lastError);
        completeAllExceptionally(batch, lastError);
    }

    private void completeAllNormally(List<EnqueuedPatch> batch) {
        for (EnqueuedPatch p : batch) {
            p.future().complete(null);
        }
    }

    private void completeAllExceptionally(List<EnqueuedPatch> batch, Throwable cause) {
        Throwable ex = cause == null ? new IllegalStateException("flush failed with no cause") : cause;
        for (EnqueuedPatch p : batch) {
            p.future().completeExceptionally(ex);
        }
    }

    /** Used by the wiring-incomplete branch - drains the queue while completing exceptionally. */
    private void completeAllExceptionally(RunSession session, Throwable cause) {
        completeAllExceptionally(new ArrayList<>(session.patchQueue), cause);
    }

    /**
     * Audit M1 fix - centralized permit release. CAS-flips
     * {@link RunSession#permitReleased} so concurrent eviction paths
     * (closeCoalescing, reapIdleSessions, cleanupRun) cannot double-release.
     * Without this, a reaper firing on a session with refcount&gt;0 evicts +
     * releases AND the leaked opener's later closeCoalescing becomes a no-op
     * (sessions.get returns null) - net result: 1 release for 1 permit
     * consumed, correct. But if the reaper races with closeCoalescing-to-zero,
     * both observe sessions.contains(runId)=true → both release → double-release.
     * The CAS guarantees exactly-one release per session lifetime.
     */
    private void releasePermitOnce(RunSession session) {
        if (session.permitReleased.compareAndSet(false, true)) {
            concurrentSessionLimit.release();
        }
    }

    /**
     * Is this run currently in a coalescing session? Used by callers to
     * decide between coalescer-enqueue vs per-patch CAS path. Always
     * returns false when the feature flag is OFF.
     */
    public boolean isCoalescing(String runId) {
        if (!coalescingEnabled || runId == null) {
            return false;
        }
        RunSession session = sessions.get(runId);
        return session != null && session.state == SessionState.ACTIVE;
    }

    /**
     * Block on a flusher future using {@link ForkJoinPool.ManagedBlocker} so
     * FJP commonPool workers (which run {@code SplitAwareNodeExecutor}
     * fan-out items) don't starve. The pool compensates by spawning a
     * temporary worker.
     *
     * <p>30s timeout; on timeout the caller drops the coalesced path and
     * falls back to per-patch CAS (the patch is then committed individually
     * by the next CAS attempt - no loss).
     *
     * @return {@code true} if the flush completed normally, {@code false} on timeout
     */
    public boolean awaitFlush(CompletableFuture<Void> future) {
        Objects.requireNonNull(future, "future");
        managedBlockCounter.increment();
        FlusherBlocker blocker = new FlusherBlocker(future);
        try {
            ForkJoinPool.managedBlock(blocker);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (blocker.timedOut) {
            awaitTimeoutCounter.increment();
            return false;
        }
        return blocker.completed;
    }

    /**
     * Mark a session POISONED. Idempotent. POISON is one-way (no recovery).
     * Plan §3 + audit C M2: marks state BEFORE completing futures
     * exceptionally. {@link #isCoalescing(String)} returns false for POISONED
     * sessions (the volatile {@code state} write is the happens-before edge),
     * so an enqueuer that observes the transition falls through to direct CAS.
     */
    public void poison(String runId, Throwable cause) {
        if (runId == null) return;
        RunSession session = sessions.get(runId);
        if (session == null) return;
        SessionState prior = session.state;
        if (prior == SessionState.POISONED) {
            return;  // idempotent
        }
        session.poisonCause = cause;
        session.state = SessionState.POISONED;  // volatile write - happens-before with isCoalescing's volatile read
        sessionsPoisonedCounter.increment();
        log.warn("[RunCoalescingService] session POISONED runId={}: {}", runId,
                cause == null ? "no-cause" : cause.getMessage());
    }

    /**
     * Idle-session reaper - runs every 60s, evicts sessions that have not
     * had a patch enqueued in the last 10 minutes. Backstop for the
     * exception-path leak where a caller skips closeCoalescing.
     */
    @Scheduled(fixedDelay = 60_000)
    public void reapIdleSessions() {
        if (!coalescingEnabled) return;
        Instant now = Instant.now();
        Instant cutoff = now.minus(SESSION_IDLE_TTL);
        sessions.entrySet().removeIf(entry -> {
            RunSession s = entry.getValue();
            if (s.lastActivity.isBefore(cutoff)) {
                sessionsReapedCounter.increment();
                releasePermitOnce(s);  // M1: CAS-guarded; safe even if closeCoalescing already released
                log.warn("[RunCoalescingService] reaped idle session runId={} (last activity={})",
                        entry.getKey(), s.lastActivity);
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    void shutdown() {
        log.info("[RunCoalescingService] shutdown - {} session(s) active", sessions.size());
        sessions.clear();
    }

    // --- RunScopedCache implementation ---

    @Override
    public void cleanupRun(String runId) {
        if (runId == null) return;
        RunSession s = sessions.remove(runId);
        if (s != null) {
            releasePermitOnce(s);  // M1: CAS-guarded permit release
        }
    }

    @Override
    public String getCacheName() {
        return "RunCoalescingService.sessions";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.PERSISTENCE;
    }

    @Override
    public int getCacheSize() {
        return sessions.size();
    }

    /** Test accessor - number of acquired permits. */
    int activePermitCount() {
        return MAX_CONCURRENT_SESSIONS - concurrentSessionLimit.availablePermits();
    }

    // --- Session value types ---

    /** Active = healthy and accepting patches. POISONED = teardown after failure; isCoalescing returns false. */
    public enum SessionState { ACTIVE, POISONED }

    /**
     * Per-run coalescing session. Members are mutated under the session's
     * own monitor (callers MUST synchronize on the session for enqueue/flush
     * sequencing); {@code state} is {@code volatile} for cross-thread visibility
     * without a monitor (per audit C M2).
     */
    public static final class RunSession {

        final String runId;
        final java.util.concurrent.atomic.AtomicInteger openCount = new java.util.concurrent.atomic.AtomicInteger(0);

        /** Audit C M2 - volatile for happens-before with POISON write. */
        volatile SessionState state = SessionState.ACTIVE;

        volatile Throwable poisonCause;
        volatile Instant lastActivity;

        /**
         * Audit M1 fix - semaphore permit eviction guard. Set true by whichever
         * eviction path runs first (closeCoalescing-to-zero, reapIdleSessions,
         * cleanupRun, or poison-after-drain). Subsequent paths see true and
         * skip the release to prevent double-release / permit-leak races.
         */
        final java.util.concurrent.atomic.AtomicBoolean permitReleased =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        // Frozen-patch queue (lower-level primitive) - synchronized via this.
        final List<EnqueuedPatch> patchQueue = new ArrayList<>();

        // Recompute-closure queue (audit bug #2 production path) - synchronized via this.
        final List<CoalescedMutation> mutationQueue = new ArrayList<>();

        // Parse + post-flush-cache bridge for the mutation path (set on first
        // enqueueMutation; same StateSnapshotService singleton for the run).
        volatile MutationFlushBridge bridge;

        RunSession(String runId, Instant openedAt) {
            this.runId = Objects.requireNonNull(runId, "runId");
            this.lastActivity = openedAt;
        }

        public String getRunId() {
            return runId;
        }

        public SessionState getState() {
            return state;
        }

        /** Test access - current frozen-patch queue size. */
        public int queueSize() {
            synchronized (this) {
                return patchQueue.size();
            }
        }
    }

    /**
     * A queued patch waiting for the flusher. {@code future} completes when
     * the flush commits (normally) or rolls back (exceptionally → caller
     * falls through to per-patch CAS).
     */
    public record EnqueuedPatch(JsonbPatch patch, PatchClass.OpKind opKind, CompletableFuture<Void> future) {
        public EnqueuedPatch {
            Objects.requireNonNull(patch, "patch");
            Objects.requireNonNull(opKind, "opKind");
            Objects.requireNonNull(future, "future");
        }
    }

    /**
     * Audit bug #2 - a queued recompute closure. The flusher re-runs
     * {@code recompute} against the freshly-flushed base so concurrent same-path
     * ASSIGNs compose instead of clobber. {@code future} completes when the
     * coalesced flush commits (normally) or fails (exceptionally → caller falls
     * through to direct CAS / pessimistic).
     */
    public record CoalescedMutation(Function<StateSnapshot, RecomputeOutput> recompute,
                                    String mutatorName, CompletableFuture<Void> future) {
        public CoalescedMutation {
            Objects.requireNonNull(recompute, "recompute");
            Objects.requireNonNull(future, "future");
        }
    }

    /**
     * Output of one recompute step: the patches to apply (null = no-op),
     * the post-mutation snapshot to advance the running base, and a fallback
     * flag (builder could not express the mutation as patches - abort coalesced
     * flush and route the futures to the pessimistic fallback).
     */
    public record RecomputeOutput(List<JsonbPatch> patches, StateSnapshot advanced, boolean fallbackRequested) {
        public static RecomputeOutput of(List<JsonbPatch> patches, StateSnapshot advanced) {
            return new RecomputeOutput(patches, advanced, false);
        }
        public static RecomputeOutput noOp(StateSnapshot advanced) {
            return new RecomputeOutput(null, advanced, false);
        }
        public static RecomputeOutput fallback() {
            return new RecomputeOutput(null, null, true);
        }
    }

    /**
     * Bridge supplied by {@code StateSnapshotService} so the coalescer can parse
     * the fresh base JSON and refresh the read-after-write caches post-flush
     * without itself depending on Jackson or the cache tiers (audit bug #1).
     */
    public interface MutationFlushBridge {
        /** Parse the fresh base JSON into a {@link StateSnapshot} for re-mutation. */
        StateSnapshot parseBase(String runId, String json);
        /**
         * After a successful coalesced flush at {@code newSeq}, invalidate the
         * run's read caches (txCache + snapshotJsonCache) so the next read
         * re-fetches the merged state from DB (read-after-write, audit bug #1).
         */
        void afterCoalescedFlush(String runId, long newSeq);
    }

    /** Internal - a builder requested full-rewrite fallback during a coalesced flush. */
    private static final class FlushFallbackException extends RuntimeException {
        FlushFallbackException(String message) { super(message); }
    }

    /**
     * Plan v4 §3 same-path collision detector. Returns the merge decision
     * for a new patch given the existing queue.
     *
     * <ul>
     *   <li>MERGE: existing same-path patch has opKind=COMMUTATIVE_DELTA AND
     *       new patch has opKind=COMMUTATIVE_DELTA → safe to compose into
     *       {@code (jsonb->>'X')::int + (N+M)}.</li>
     *   <li>FORCE_FLUSH: any same-path patch where either side is ASSIGN -
     *       jsonb_set is replace-not-merge, so we MUST flush the older
     *       patch first.</li>
     *   <li>APPEND: no same-path collision - queue freely.</li>
     * </ul>
     */
    public CollisionDecision detectCollision(List<EnqueuedPatch> queue, JsonbPatch newPatch,
                                             PatchClass.OpKind newOpKind) {
        for (EnqueuedPatch existing : queue) {
            if (samePath(existing.patch().path(), newPatch.path())) {
                if (existing.opKind() == PatchClass.OpKind.COMMUTATIVE_DELTA
                        && newOpKind == PatchClass.OpKind.COMMUTATIVE_DELTA) {
                    collisionMergeCounter.increment();
                    return CollisionDecision.MERGE;
                }
                collisionForceFlushCounter.increment();
                return CollisionDecision.FORCE_FLUSH;
            }
        }
        return CollisionDecision.APPEND;
    }

    public enum CollisionDecision { APPEND, MERGE, FORCE_FLUSH }

    private static boolean samePath(String[] a, String[] b) {
        return Arrays.equals(a, b);
    }

    /**
     * ForkJoinPool.ManagedBlocker that waits on a CompletableFuture with a
     * 30s budget. Lets FJP commonPool spawn a compensating worker so the
     * caller (an FJP item-task) doesn't starve the split fan-out.
     */
    private static final class FlusherBlocker implements ForkJoinPool.ManagedBlocker {

        private final CompletableFuture<Void> future;
        private final long deadlineNs;
        boolean completed = false;
        boolean timedOut = false;

        FlusherBlocker(CompletableFuture<Void> future) {
            this.future = future;
            this.deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_FLUSH_TIMEOUT_MS);
        }

        @Override
        public boolean block() {
            long remainingNs = deadlineNs - System.nanoTime();
            if (remainingNs <= 0) {
                timedOut = true;
                return true;
            }
            try {
                future.get(remainingNs, TimeUnit.NANOSECONDS);
                completed = true;
                return true;
            } catch (java.util.concurrent.ExecutionException ee) {
                // Future completed exceptionally - caller routes through
                // POISON fallback. We treat this as "blocked block, done".
                completed = true;
                return true;
            } catch (TimeoutException te) {
                timedOut = true;
                return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return true;
            }
        }

        @Override
        public boolean isReleasable() {
            if (future.isDone()) {
                completed = true;
                return true;
            }
            if (System.nanoTime() >= deadlineNs) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }
}

package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Per-transaction parse cache for {@link StateSnapshot}, keyed by {@code runIdPublic}.
 *
 * <p><b>Plan v4 §4 (seq-bound + 5ms freshness):</b> every entry stores its
 * populate-time {@code seq} (taken from {@code StateSnapshot.seq()}) and a
 * monotonic timestamp. On {@link #get}, entries within {@code 5ms} of their
 * populate timestamp return immediately (hot path, no DB call); entries older
 * than {@code 5ms} trigger a cheap seq-only projection
 * ({@link WorkflowRunRepository#findStateSnapshotSeqByRunIdPublic}). If the
 * DB seq matches the entry's populate-seq, the entry is fresh, timestamp is
 * refreshed, return; if it differs, evict + return empty (caller falls
 * through to L1 Redis / L2 DB read).
 *
 * <p>Audit B S3 (round-2 v4 audit): the 5ms threshold replaces v3's 100µs
 * spec which was below PgBouncer typical RTT (1-3ms).
 *
 * <h2>Why seq-bound under CAS</h2>
 *
 * Pre-plan-v4 the cache was safe because every caller held
 * {@code PESSIMISTIC_WRITE} via {@code findByRunIdPublicForUpdate} → no peer
 * mutation possible during the cache's lifetime. Under plan v4 §1 CAS the
 * row lock is dropped; a peer in another tx (or another replica) can commit
 * mid-flight. The seq-bound invariant is strictly stronger: a cache entry is
 * valid IFF the DB column {@code state_snapshot_seq} hasn't advanced since
 * the entry was populated. The 5ms freshness window is a perf optimization
 * - multiple reads in the same tx within 5ms skip the verification (the
 * round-trip is the dominant cost over the parse).
 *
 * <h2>Correctness invariant (post plan v4)</h2>
 *
 * The cache is safe to use when EITHER:
 * <ul>
 *   <li>The caller holds {@code PESSIMISTIC_WRITE} (legacy advisory-lock
 *       carve-out path) - peer-mutation impossible, seq check is redundant
 *       but harmless</li>
 *   <li>The caller is under CAS path - the seq check catches any peer commit
 *       that beat us; we evict and re-read</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * Backed by Spring's {@link TransactionSynchronizationManager} which uses a
 * per-thread {@code ThreadLocal}. Each transaction (= one thread per Spring's
 * transaction model) gets its own private {@link Map}. No cross-thread contention.
 *
 * <h2>Suspend/resume contract</h2>
 *
 * {@code REQUIRES_NEW} inner tx sees a clean cache (suspend unbinds, resume
 * re-binds). Unchanged from pre-plan-v4 behavior.
 */
@Component
public class TxScopedSnapshotCache {

    private static final Logger log = LoggerFactory.getLogger(TxScopedSnapshotCache.class);

    /** Resource key for {@link TransactionSynchronizationManager}. Class object is unique. */
    private static final Object RESOURCE_KEY = TxScopedSnapshotCache.class;

    /** Plan v4 §4 + audit B S3 - freshness threshold below which entries skip the seq verification. */
    static final long FRESHNESS_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    private final WorkflowRunRepository runRepository;
    private final Counter hitFreshCounter;
    private final Counter hitVerifiedCounter;
    private final Counter staleEvictionCounter;
    private final Counter missCounter;

    public TxScopedSnapshotCache(WorkflowRunRepository runRepository, MeterRegistry meterRegistry) {
        this.runRepository = runRepository;
        this.hitFreshCounter = Counter.builder("orchestrator.tx_snapshot_cache.hit_fresh_count")
                .description("Plan v4 §4 - L0 cache hits within 5ms freshness window (no DB verification)")
                .register(meterRegistry);
        this.hitVerifiedCounter = Counter.builder("orchestrator.tx_snapshot_cache.hit_verified_count")
                .description("Plan v4 §4 - L0 cache hits verified via cheap seq projection (>5ms old, seq matched)")
                .register(meterRegistry);
        this.staleEvictionCounter = Counter.builder("orchestrator.tx_snapshot_cache.stale_eviction_count")
                .description("Plan v4 §4 - L0 cache entries evicted because DB seq advanced (peer commit)")
                .register(meterRegistry);
        this.missCounter = Counter.builder("orchestrator.tx_snapshot_cache.miss_count")
                .description("Plan v4 §4 - L0 cache misses (no entry bound to current tx)")
                .register(meterRegistry);
    }

    /**
     * Returns the cached snapshot for {@code runId} if one is bound to the
     * current transaction AND passes the seq-binding freshness check.
     *
     * <p>Hot path: entries populated within {@link #FRESHNESS_THRESHOLD_NANOS}
     * return immediately (no DB call). Cold path: a cheap seq projection
     * verifies the entry against the live DB seq; mismatch → evict + empty.
     *
     * @param runId the public run identifier; must not be null
     * @return cached snapshot or empty if no transaction is active, cache
     *         miss, or peer commit invalidated the entry
     */
    public Optional<StateSnapshot> get(String runId) {
        if (runId == null) return Optional.empty();
        Map<String, SeqBoundEntry> cache = currentCache();
        if (cache == null) {
            missCounter.increment();
            return Optional.empty();
        }
        SeqBoundEntry entry = cache.get(runId);
        if (entry == null) {
            missCounter.increment();
            return Optional.empty();
        }
        long ageNs = System.nanoTime() - entry.populateNs();
        if (ageNs <= FRESHNESS_THRESHOLD_NANOS) {
            hitFreshCounter.increment();
            return Optional.of(entry.snapshot());
        }
        // Stale-by-age - verify against DB seq via cheap projection.
        Optional<Long> liveSeq = runRepository.findStateSnapshotSeqByRunIdPublic(runId);
        if (liveSeq.isEmpty() || liveSeq.get() != entry.populateSeq()) {
            cache.remove(runId);
            staleEvictionCounter.increment();
            if (log.isDebugEnabled()) {
                log.debug("[TxSnapshotCache] evicted stale entry runId={} (populateSeq={}, liveSeq={})",
                        runId, entry.populateSeq(), liveSeq.orElse(null));
            }
            return Optional.empty();
        }
        // Verified fresh - refresh the timestamp so subsequent reads in the
        // next 5ms can short-circuit.
        cache.put(runId, new SeqBoundEntry(entry.snapshot(), entry.populateSeq(), System.nanoTime()));
        hitVerifiedCounter.increment();
        return Optional.of(entry.snapshot());
    }

    /**
     * Stores {@code snapshot} as the latest version for {@code runId} in the
     * current transaction. populateSeq is taken from {@code snapshot.seq()},
     * populateNs from {@link System#nanoTime()}.
     *
     * <p>No-op if no transaction is active.
     */
    public void put(String runId, StateSnapshot snapshot) {
        if (runId == null || snapshot == null) return;
        Map<String, SeqBoundEntry> cache = currentOrCreateCache();
        if (cache == null) return;
        cache.put(runId, new SeqBoundEntry(snapshot, snapshot.getSeq(), System.nanoTime()));
    }

    /**
     * Removes {@code runId} from the current transaction's cache. Used when a
     * mutation fails to serialize (defensive - force the next read to re-fetch
     * from DB rather than hand back a tentative pre-failure snapshot).
     */
    public void invalidate(String runId) {
        if (runId == null) return;
        Map<String, SeqBoundEntry> cache = currentCache();
        if (cache != null) cache.remove(runId);
    }

    /**
     * Test-only / observability: returns the number of entries currently bound
     * to the active transaction. Outside a transaction, returns 0.
     */
    public int size() {
        Map<String, SeqBoundEntry> cache = currentCache();
        return cache == null ? 0 : cache.size();
    }

    @SuppressWarnings("unchecked")
    private Map<String, SeqBoundEntry> currentCache() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return null;
        return (Map<String, SeqBoundEntry>) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
    }

    private Map<String, SeqBoundEntry> currentOrCreateCache() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return null;
        @SuppressWarnings("unchecked")
        Map<String, SeqBoundEntry> existing =
                (Map<String, SeqBoundEntry>) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (existing != null) return existing;

        Map<String, SeqBoundEntry> fresh = new HashMap<>();
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, fresh);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (TransactionSynchronizationManager.hasResource(RESOURCE_KEY)) {
                    TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[TxSnapshotCache] released {} cached entries (tx status={})",
                            fresh.size(), statusName(status));
                }
            }

            @Override
            public void suspend() {
                if (TransactionSynchronizationManager.hasResource(RESOURCE_KEY)) {
                    TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
                }
            }

            @Override
            public void resume() {
                if (!TransactionSynchronizationManager.hasResource(RESOURCE_KEY)) {
                    TransactionSynchronizationManager.bindResource(RESOURCE_KEY, fresh);
                }
            }
        });
        return fresh;
    }

    private static String statusName(int status) {
        return switch (status) {
            case TransactionSynchronization.STATUS_COMMITTED -> "COMMITTED";
            case TransactionSynchronization.STATUS_ROLLED_BACK -> "ROLLED_BACK";
            case TransactionSynchronization.STATUS_UNKNOWN -> "UNKNOWN";
            default -> "UNKNOWN(" + status + ")";
        };
    }

    /**
     * Plan v4 §4 - cache entry binding a {@link StateSnapshot} to its
     * populate-time {@code seq} and monotonic-clock timestamp.
     *
     * @param snapshot the parsed StateSnapshot
     * @param populateSeq the {@code state_snapshot_seq} that was on the DB
     *                    row when this entry was populated
     * @param populateNs {@link System#nanoTime} at populate; used to compute
     *                   the 5ms freshness window
     */
    record SeqBoundEntry(StateSnapshot snapshot, long populateSeq, long populateNs) {
        SeqBoundEntry {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}

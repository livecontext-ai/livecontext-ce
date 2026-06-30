package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase A2 (archi-refoundation 2026-05-04) - central source of monotonic
 * sequence numbers for WebSocket events sent to the frontend.
 *
 * <p><b>Why a separate seq from {@code StateSnapshot.seq}</b>: the snapshot
 * seq is bumped only on DB writes ({@code saveSnapshot}). Some events
 * ({@code decisionEvaluated}, {@code workflowStatus}, {@code agentBrowseStep})
 * fire without persisting a new snapshot - they would all share the same
 * snapshot seq, making the frontend strict-{@code <} guard ambiguous. A
 * dedicated atomic counter per runId guarantees strictly-increasing seq for
 * EVERY event, regardless of DB state.
 *
 * <p><b>Storage</b> is delegated to a {@link RunSeqBackend} strategy
 * ({@link RunSeqBackendConfig}): an {@link InMemoryRunSeqBackend} per-pod
 * {@code AtomicLong} in single-pod / {@code scaling.backend=memory} mode, or a
 * {@link RedisRunSeqBackend} shared atomic Redis counter when
 * {@code scaling.backend=redis} (prod, {@code replicas: 2}). The Redis backend is
 * what keeps the seq globally monotonic across pods - without it, a per-pod counter
 * diverges and the frontend's strict-{@code <} {@code lastKnownSeq} guard drops the
 * lower-seq events / REST refreshes, freezing the run page and desyncing nodes
 * (see {@code the project docs} #2). This class keeps the
 * cross-restart DB seed, the {@code last_event_seq} flusher, and the
 * {@link RunScopedCache} cleanup regardless of backend.
 *
 * <p><b>Cross-restart seed</b>: on the FIRST {@code nextSeq(runId)} call after
 * a pod restart, we read {@code workflow_runs.last_event_seq} (Flyway V100,
 * bumped by the {@link #flushPersistedSeq()} scheduled flusher) and seed the
 * in-memory {@code AtomicLong}. Without this, the FE {@code lastKnownSeq}
 * would reject all post-restart events.
 *
 * <p><b>Cleanup</b>: implements {@link RunScopedCache} so that
 * {@code RunCacheRegistry.cleanupRun(runId)} purges the entry when the run
 * terminates. Spring auto-wires us into the registry's
 * {@code List<RunScopedCache>}.
 */
@Component
public class WsEventSequencer implements RunScopedCache {

    private static final Logger log = LoggerFactory.getLogger(WsEventSequencer.class);

    /**
     * Per-runId counter storage. {@link InMemoryRunSeqBackend} (single-pod) or
     * {@link RedisRunSeqBackend} (multi-pod, shared atomic) - see {@link RunSeqBackendConfig}.
     */
    private final RunSeqBackend backend;

    /**
     * Tracks which runIds have new seq values not yet flushed to DB. Cleared
     * after successful batched UPDATE in {@link #flushPersistedSeq()}.
     */
    private final ConcurrentHashMap<String, Long> dirty = new ConcurrentHashMap<>();

    private final WorkflowRunRepository runRepository;

    @Autowired
    public WsEventSequencer(WorkflowRunRepository runRepository, RunSeqBackend backend) {
        this.runRepository = runRepository;
        this.backend = backend;
    }

    /**
     * Convenience constructor defaulting to the in-memory backend (single-pod
     * behavior). Used by unit tests and any context without an explicit backend bean.
     */
    public WsEventSequencer(WorkflowRunRepository runRepository) {
        this(runRepository, new InMemoryRunSeqBackend());
    }

    /**
     * Returns the next monotonic sequence for {@code runId}. Strictly
     * increasing across the lifetime of the run (modulo pod restart, where
     * cross-restart seeding from {@code workflow_runs.last_event_seq} ensures
     * forward progress).
     *
     * <p>First call for a runId: reads the seed value from DB (default 0),
     * stores in memory. Subsequent calls: pure {@code AtomicLong.incrementAndGet}.
     *
     * @param runId the public run identifier; must not be null
     * @return next seq (always {@code > 0})
     * @throws IllegalArgumentException if runId is null
     */
    public long nextSeq(String runId) {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        long value = backend.next(runId, this::seedFromDb);
        dirty.put(runId, value);
        return value;
    }

    /**
     * Returns the current seq without bumping. Lazy-seeds from DB on first
     * access - symmetric with {@link #nextSeq(String)} so callers reading the
     * counter for a runId that has never bumped (e.g. {@code WorkflowRunController}
     * computing the REST /state seq) see the cross-restart-persisted value
     * instead of 0.
     *
     * <p>2026-05-04 hot-fix (audit final): without this lazy-seed, after a
     * pod restart the sequence:
     *   1. FE held lastKnownSeq=N (WS counter pre-restart)
     *   2. REST /state called BEFORE any new WS event for runId
     *   3. currentSeq=0 → max(0, dbSnapshot.getSeq()=K) = K (small)
     *   4. FE compares N (cached) > K → REST tracking skipped
     * Lazy-seed makes step 3 read N from DB, restoring lockstep.
     */
    public long currentSeq(String runId) {
        if (runId == null) return 0L;
        // 2026-05-04 hot-fix (audit MEGA R3): currentSeq is read by REST
        // controllers for ANY runId (including 404 paths). The backend must NOT
        // lazy-seed per-run state for a runId never bumped via nextSeq() - that
        // would slow-leak indefinitely. Both backends honor this: in-memory reads
        // its map without inserting, Redis does a read-only GET. When unknown, the
        // seedLoader returns the DB-persisted last_event_seq (cold-restart case:
        // FE held lastKnownSeq=N pre-restart, REST called before first WS event).
        return backend.current(runId, this::seedFromDb);
    }

    /**
     * DB-persisted cross-restart seed for a runId: the {@code last_event_seq}
     * high-water mark, or 0 when the run is unknown or the DB is unreachable.
     * Invoked lazily by the backend (at most once per fresh counter, never on the
     * hot path). PK-indexed lookup, ~50µs.
     */
    private long seedFromDb(String runId) {
        try {
            long seed = runRepository.findByRunIdPublic(runId)
                    .map(entity -> entity.getLastEventSeq())
                    .orElse(0L);
            log.debug("[WsEventSequencer] Seeded counter for runId={} from DB: lastEventSeq={}", runId, seed);
            return seed;
        } catch (Exception e) {
            // DB unreachable at seed time → start at 0 and let the flusher catch up
            log.warn("[WsEventSequencer] DB seed failed for runId={} (starting at 0): {}", runId, e.getMessage());
            return 0L;
        }
    }

    /**
     * Phase A2 flusher - persists the latest seq for each dirty run to
     * {@code workflow_runs.last_event_seq}. Decoupled from step persists
     * (audit B v6 C1.2): edge events publish without a step persist, and a
     * naïve afterCommit hook on step persists alone would miss those.
     *
     * <p>Worst-case drop window post-restart: {@code 5s} of events. Frontend
     * recovers via WS-reconnect → REST {@code /state} → seq alignment in
     * {@code applyTrackingFromApi}.
     *
     * <p>Idempotent: if no runIds are dirty, no DB writes occur.
     */
    @Scheduled(fixedDelayString = "${ws-event-sequencer.flush-interval-ms:5000}",
               initialDelayString = "${ws-event-sequencer.flush-initial-delay-ms:5000}")
    @Transactional
    public void flushPersistedSeq() {
        if (dirty.isEmpty()) {
            return;
        }
        // Snapshot: drain dirty atomically. Concurrent nextSeq calls during the
        // flush re-add their runId to dirty for the NEXT tick, so we never lose
        // a bump. Worst case: a seq written in the same tick as the flush is
        // persisted on the next tick.
        Map<String, Long> snapshot = Map.copyOf(dirty);
        dirty.keySet().removeAll(snapshot.keySet());

        int total = 0;
        int failures = 0;
        for (Map.Entry<String, Long> entry : snapshot.entrySet()) {
            try {
                int updated = runRepository.upsertLastEventSeqSingle(entry.getKey(), entry.getValue());
                if (updated > 0) total++;
            } catch (Exception e) {
                failures++;
                // Re-mark dirty so next tick retries
                dirty.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        if (total > 0) {
            log.debug("[WsEventSequencer] Flushed {} runs to DB (failures={})", total, failures);
        }
        if (failures > 0) {
            log.warn("[WsEventSequencer] Persistence failures: {} runs (will retry next tick)", failures);
        }
    }

    // ====== RunScopedCache integration (audit A v6: SnapshotService.cleanupRun never invoked) ======

    @Override
    public void cleanupRun(String runId) {
        // 2026-05-05 fix: persist the current counter to DB BEFORE purging the
        // in-memory map. Without this synchronous flush, a re-fire of the same
        // runId (reusable triggers go through {@code RunContextRegistry
        // .closeEpochForDagByTriggerId} → {@code cacheRegistry.cleanupRun} on
        // every epoch close, well before the 5s @Scheduled flusher tick) would
        // lazy-seed from a stale {@code workflow_runs.last_event_seq} (often
        // 0 for fresh runs), emit seqs below the frontend's already-bumped
        // {@code lastKnownSeq}, and have all its events strict-< dropped by
        // the FE seq filter - manifesting as "second consecutive trigger fire
        // doesn't update the UI" in prod (run_<id> verified
        // 2026-05-05: fire #1 ended at lastKnownSeq=67, fire #2 emitted
        // seq=15/32/49/64 - all dropped).
        OptionalLong localOpt = backend.peek(runId);
        if (localOpt.isPresent()) {
            // Flush the AUTHORITATIVE high-water, not just this pod's local view: with the
            // Redis backend another pod may have driven the shared counter higher, and if
            // THIS (cleanup) pod under-flushes, a later refire that re-seeds from the DB
            // could regress below the frontend's known seq and freeze the run page.
            // backend.current() returns the shared value; max() guards the rare case where
            // the shared key was already removed and current() falls back to an older DB
            // seed. The upsert is itself monotone-guarded (last_event_seq < c.seq).
            long authoritative = Math.max(localOpt.getAsLong(), backend.current(runId, this::seedFromDb));
            if (authoritative > 0L) {
                try {
                    runRepository.upsertLastEventSeqSingle(runId, authoritative);
                } catch (Exception e) {
                    log.warn("[WsEventSequencer] Cleanup-flush failed for runId={} (seq={}): {}",
                            runId, authoritative, e.getMessage());
                }
            }
        }
        backend.remove(runId);
        dirty.remove(runId);
    }

    @Override
    public String getCacheName() {
        return "WsEventSequencer";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.STREAMING;
    }

    @Override
    public int getCacheSize() {
        return backend.size();
    }
}

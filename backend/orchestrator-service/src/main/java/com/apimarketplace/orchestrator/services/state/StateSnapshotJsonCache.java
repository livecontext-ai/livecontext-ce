package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.cache.RedisCacheKeys;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A2 Phase 4 (audit Opus 2026-05-09 - B+C convergence over A) - out-of-tx
 * Redis cache for the serialized {@code state_snapshot} JSON of a workflow
 * run, keyed by run + invalidated by the {@code state_snapshot_seq} SQL
 * column (the seq oracle).
 *
 * <p>Distinct from {@code SnapshotCacheService} (Caffeine, terminal-runs
 * WS-reconnect cache, frontend Map format): this cache stores the raw JSON
 * payload that backs {@code StateSnapshot} domain-object reconstruction on
 * the read hot path.
 *
 * <h2>Why Redis instead of Caffeine</h2>
 *
 * <p>Hot path is {@code StateSnapshotService.getSnapshot(runId)} fired
 * ~100×/s/run from SSE polls + StateReconstructor + AgentWorkflowFireService.
 * Caffeine local would cost 30KB × N runs × N replicas on-heap, replaying
 * the OOM 2026-05-06 profile. Redis is ~30MB total off-heap, shared by all
 * replicas - and the seq-oracle pattern keeps cross-instance reads
 * consistent (a peer-instance commit invalidates the cache for everyone via
 * the SQL seq comparison).
 *
 * <h2>Hash layout</h2>
 *
 * <pre>
 *   Key:    orchestrator:snapshot-cache:{runId}
 *   Field "seq"     → BIGINT-as-String, the {@code state_snapshot_seq} this payload was written for
 *   Field "payload" → serialized JSON (~10-30KB)
 * </pre>
 *
 * <h2>Cache lifecycle (read-side warmup)</h2>
 *
 * <p>The cache is populated <b>by readers, not by writers</b>:
 * {@code StateSnapshotService.getSnapshot} calls {@link #putIfNewer} after a
 * DB+parse on cache miss. The first reader after a writer commit pays the
 * full DB+parse cost (the writer never touched the cache), every subsequent
 * reader for the same seq hits L1.
 *
 * <p>This trades a one-time miss-storm post-write against the simplicity of
 * having no after-commit hook to maintain - and against the perf cost of
 * Jackson-serializing inside the {@code jsonb_set} patch path which currently
 * never produces a Java-side JSON string. Writer-side warmup is an open
 * follow-up if hit-rate dips below the target on the metrics dashboard.
 *
 * <h2>Race contract</h2>
 *
 * <ul>
 *   <li><b>Race 1 (concurrent put out-of-order)</b>: {@link #putIfNewer} uses
 *       a Lua script that compares the proposed seq to the current cached
 *       seq and writes only if strictly greater. A late-arriving put with a
 *       stale seq is dropped.</li>
 *   <li><b>Race 2 (rollback drift)</b>: writers stamp {@code state_snapshot}
 *       and {@code state_snapshot_seq} in the same DB transaction; a rollback
 *       reverts both atomically. The read-side populate
 *       ({@code StateSnapshotService.getSnapshot} on L2 miss) is wrapped in
 *       {@code TransactionalHelper.runAfterCommitOrNow} so it never persists
 *       a payload tagged with a seq the enclosing tx hasn't committed.
 *       Without that wrapper, a getSnapshot called from inside a
 *       {@code @Transactional} writer that subsequently rolls back could
 *       leave Redis holding a payload for {@code seq=N+1} while DB stayed
 *       at {@code N} - and the strict-greater Lua check would then DROP a
 *       legitimate concurrent commit for the same {@code N+1} (different
 *       content), poisoning the cache (audit Opus A 2026-05-09 finding M1).</li>
 *   <li><b>Race 3 (deploy boundary)</b>: V178 backfills
 *       {@code state_snapshot_seq} from {@code state_snapshot.seq} on legacy
 *       rows so the seq oracle is correct from the first post-deploy read.</li>
 *   <li><b>Race 6 (Redis seq desync)</b>: the SQL column is the unique seq
 *       oracle. The cache stores {@code (seq, payload)} tuples and a reader
 *       rejects any payload whose embedded seq does not match the SQL seq
 *       (strict equality - both directions: cache behind OR cache ahead).
 *       Drift cannot survive a single round-trip.</li>
 * </ul>
 *
 * <h2>Outage mode (fail-OPEN)</h2>
 *
 * <p>Aligned with {@code RunningNodeTracker}: any Redis exception during
 * {@link #getPayloadIfMatchesSeq} or {@link #putIfNewer} is swallowed and
 * recorded; the caller falls back to the legacy DB+parse path. Redis
 * unreachable degrades performance to pre-Phase-4 baseline but never blocks
 * the SSE poll.
 *
 * <h2>TTL</h2>
 *
 * <p>2 minutes - long enough that an active SSE session keeps the entry warm
 * across writes, short enough to bound memory if a run becomes idle without
 * an explicit {@link #cleanupRun}.
 */
@Component
public class StateSnapshotJsonCache implements RunScopedCache {

    private static final Logger log = LoggerFactory.getLogger(StateSnapshotJsonCache.class);
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final String FIELD_SEQ = "seq";
    private static final String FIELD_PAYLOAD = "payload";

    /**
     * Lua script for atomic put-only-if-newer.
     *
     * <p>KEYS[1] = orchestrator:snapshot-cache:{runId}
     * <p>ARGV[1] = proposed seq (numeric string)
     * <p>ARGV[2] = payload JSON
     * <p>ARGV[3] = TTL seconds
     *
     * <p>Returns 1 if applied, 0 if dropped (current seq &gt;= proposed). On a
     * malformed cached seq (parse error inside Redis) the script falls into
     * the "current is empty" branch and writes - defensive recovery from a
     * legacy corrupted entry.
     */
    private static final String PUT_IF_NEWER_LUA =
            "local current = redis.call('HGET', KEYS[1], 'seq') " +
            "if current == false or tonumber(current) == nil or tonumber(ARGV[1]) > tonumber(current) then " +
            "  redis.call('HSET', KEYS[1], 'seq', ARGV[1], 'payload', ARGV[2]) " +
            "  redis.call('EXPIRE', KEYS[1], ARGV[3]) " +
            "  return 1 " +
            "end " +
            "return 0";

    private static final DefaultRedisScript<Long> PUT_IF_NEWER_SCRIPT;
    static {
        PUT_IF_NEWER_SCRIPT = new DefaultRedisScript<>();
        PUT_IF_NEWER_SCRIPT.setScriptText(PUT_IF_NEWER_LUA);
        PUT_IF_NEWER_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final WorkflowMetrics metrics;

    @Autowired
    public StateSnapshotJsonCache(StringRedisTemplate redis, WorkflowMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    /** Test/legacy constructor - metrics omitted (no-op recording). */
    public StateSnapshotJsonCache(StringRedisTemplate redis) {
        this(redis, null);
    }

    /**
     * Read the cached payload IF AND ONLY IF its embedded seq matches
     * {@code expectedSeq}. The caller is expected to have just read
     * {@code expectedSeq} from
     * {@code WorkflowRunRepository.findStateSnapshotSeqByRunIdPublic} -
     * passing a stale seq here would silently return a stale payload.
     *
     * <p>Returns {@link Optional#empty()} on cache miss (no entry, missing
     * field, seq mismatch) OR on Redis failure (fail-OPEN: caller falls back
     * to the legacy DB+parse path).
     */
    public Optional<String> getPayloadIfMatchesSeq(String runId, long expectedSeq) {
        if (runId == null) return Optional.empty();
        try {
            List<Object> values = redis.opsForHash().multiGet(
                    RedisCacheKeys.snapshotCache(runId), List.of(FIELD_SEQ, FIELD_PAYLOAD));
            if (values == null || values.size() < 2) {
                if (metrics != null) metrics.recordSnapshotCacheOutcome("miss");
                return Optional.empty();
            }
            Object seqObj = values.get(0);
            Object payloadObj = values.get(1);
            if (seqObj == null || payloadObj == null) {
                if (metrics != null) metrics.recordSnapshotCacheOutcome("miss");
                return Optional.empty();
            }
            long cachedSeq;
            try {
                cachedSeq = Long.parseLong(seqObj.toString());
            } catch (NumberFormatException nfe) {
                // Legacy / corrupted entry - treat as miss; the next put will overwrite.
                if (metrics != null) metrics.recordSnapshotCacheOutcome("miss_corrupt");
                return Optional.empty();
            }
            if (cachedSeq != expectedSeq) {
                // Either we are ahead of Redis (writer hasn't replicated) or
                // behind (peer instance committed but we have a stale seq) -
                // either way, do not serve stale.
                if (metrics != null) metrics.recordSnapshotCacheOutcome("miss_seq_mismatch");
                return Optional.empty();
            }
            if (metrics != null) metrics.recordSnapshotCacheOutcome("hit");
            // Plan v3 #12 - refresh the TTL on every hit so an active SSE
            // session keeps the entry warm across long polling windows.
            // Best-effort: an EXPIRE failure is logged but non-fatal - the
            // entry will simply expire at its previous TTL.
            try {
                redis.expire(RedisCacheKeys.snapshotCache(runId), TTL);
            } catch (Exception expireErr) {
                // Same fail-OPEN discipline as the read.
                if (metrics != null) metrics.recordSnapshotCacheOutcome("error_ttl_refresh");
            }
            return Optional.of(payloadObj.toString());
        } catch (Exception e) {
            log.debug("[StateSnapshotJsonCache] Redis read failed (fail-OPEN): runId={}, error={}",
                    runId, e.getMessage());
            if (metrics != null) metrics.recordSnapshotCacheOutcome("error_read");
            return Optional.empty();
        }
    }

    /**
     * Atomic put-only-if-newer. Idempotent + race-safe: a stale concurrent
     * writer with a smaller seq cannot overwrite a fresh entry. Returns true
     * if the write was applied, false if the cache already had a seq
     * &gt;= the proposed one.
     *
     * <p>Best-effort: any Redis exception is swallowed and recorded - the
     * source of truth is the SQL column, not the cache.
     */
    public boolean putIfNewer(String runId, long seq, String payloadJson) {
        if (runId == null || payloadJson == null) return false;
        try {
            Long result = redis.execute(
                    PUT_IF_NEWER_SCRIPT,
                    Collections.singletonList(RedisCacheKeys.snapshotCache(runId)),
                    String.valueOf(seq), payloadJson, String.valueOf(TTL.getSeconds())
            );
            boolean applied = result != null && result == 1L;
            if (metrics != null) {
                metrics.recordSnapshotCacheOutcome(applied ? "put_applied" : "put_dropped_stale");
            }
            return applied;
        } catch (Exception e) {
            log.debug("[StateSnapshotJsonCache] Redis put failed (fail-OPEN): runId={}, seq={}, error={}",
                    runId, seq, e.getMessage());
            if (metrics != null) metrics.recordSnapshotCacheOutcome("error_put");
            return false;
        }
    }

    @Override
    public void cleanupRun(String runId) {
        try {
            redis.delete(RedisCacheKeys.snapshotCache(runId));
        } catch (Exception e) {
            log.debug("[StateSnapshotJsonCache] Redis cleanupRun failed: runId={}, error={}",
                    runId, e.getMessage());
        }
    }

    @Override
    public String getCacheName() {
        return "StateSnapshotJsonCache";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.STREAMING;
    }

    @Override
    public int getCacheSize() {
        return -1;
    }
}

package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.cache.RedisCacheKeys;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis-backed tracker for nodes currently in RUNNING state.
 *
 * <p>Uses a Redis HASH per run ({@code orchestrator:running:{runId}}) where each field
 * is a nodeId and the value is the running count. This provides cross-instance visibility
 * for horizontal scaling: any orchestrator instance can see which nodes are running,
 * regardless of which instance started the execution.
 *
 * <p>Thread-safe: Redis operations are atomic (HINCRBY). The hash auto-expires after 1 hour
 * as a safety net; explicit cleanup via {@link #cleanupRun} handles the normal path.
 *
 * <p>Implements {@link RunScopedCache} for automatic cleanup via
 * {@link com.apimarketplace.orchestrator.services.cache.RunCacheRegistry}.
 */
@Component
public class RunningNodeTracker implements RunScopedCache {

    private static final Logger log = LoggerFactory.getLogger(RunningNodeTracker.class);
    private static final Duration HASH_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final WorkflowMetrics metrics;

    @Autowired
    public RunningNodeTracker(StringRedisTemplate redis, WorkflowMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    /** Test/legacy constructor - metrics omitted (no-op recording). */
    public RunningNodeTracker(StringRedisTemplate redis) {
        this(redis, null);
    }

    // ====================================================================
    // Per-epoch overloads. Thread the epoch from the execution context.
    //
    // NOTE: the legacy 1-arg {@code getRunningCounts(String)} that read the
    // run-wide key {@code orchestrator:running:{runId}} was removed
    // 2026-05-08 (post-P2.3.1). All writers now populate per-epoch keys
    // ({runId}:{epoch}) only. Use {@link #getRunningCountsAcrossEpochs}
    // when the caller wants a flat union across all epochs of a run, or
    // {@link #getRunningCounts(String, int)} for the per-epoch view.
    // ====================================================================

    /** Increment running count for a node within a specific epoch. */
    public void markRunning(String runId, int epoch, String nodeId) {
        try {
            String key = RedisCacheKeys.runningNodes(runId, epoch);
            redis.opsForHash().increment(key, nodeId, 1);
            redis.expire(key, HASH_TTL);
            trackEpoch(runId, epoch);
            log.debug("[RunningTracker] markRunning: runId={}, epoch={}, nodeId={}", runId, epoch, nodeId);
        } catch (Exception e) {
            log.warn("[RunningTracker] Redis markRunning failed (non-fatal): runId={}, epoch={}, nodeId={}, error={}",
                    runId, epoch, nodeId, e.getMessage());
            if (metrics != null) metrics.recordRunningTrackerWriteFailure("markRunning");
        }
    }

    /** Set the running count for a node within a specific epoch (split dispatch). */
    public void setRunningCount(String runId, int epoch, String nodeId, int count) {
        try {
            String key = RedisCacheKeys.runningNodes(runId, epoch);
            if (count <= 0) {
                redis.opsForHash().delete(key, nodeId);
            } else {
                redis.opsForHash().put(key, nodeId, String.valueOf(count));
                redis.expire(key, HASH_TTL);
                trackEpoch(runId, epoch);
            }
            log.debug("[RunningTracker] setRunningCount: runId={}, epoch={}, nodeId={}, count={}", runId, epoch, nodeId, count);
        } catch (Exception e) {
            log.warn("[RunningTracker] Redis setRunningCount failed (non-fatal): runId={}, epoch={}, nodeId={}, count={}, error={}",
                    runId, epoch, nodeId, count, e.getMessage());
            if (metrics != null) metrics.recordRunningTrackerWriteFailure("setRunningCount");
        }
    }

    /**
     * Idempotent SADD into the per-run epoch tracker SET. Best-effort: a tracker
     * write failure does not bubble up to the caller - the readers
     * ({@link #getRunningCountsAcrossEpochs}, {@link #cleanupRun}) fall back to
     * a one-shot {@code KEYS} scan when the SET is empty for an in-flight run.
     */
    private void trackEpoch(String runId, int epoch) {
        try {
            String trackerKey = RedisCacheKeys.runningEpochsTracker(runId);
            redis.opsForSet().add(trackerKey, String.valueOf(epoch));
            redis.expire(trackerKey, HASH_TTL);
        } catch (Exception e) {
            // Non-fatal: drift fallback handles missing tracker entries.
            if (metrics != null) metrics.recordRunningTrackerWriteFailure("trackEpoch");
        }
    }

    /** Decrement running count for a node within a specific epoch. */
    public void markCompleted(String runId, int epoch, String nodeId) {
        try {
            String key = RedisCacheKeys.runningNodes(runId, epoch);
            redis.expire(key, HASH_TTL);
            Long newValue = redis.opsForHash().increment(key, nodeId, -1);
            if (newValue != null && newValue <= 0) {
                redis.opsForHash().delete(key, nodeId);
                // If the per-epoch hash is now empty, prune the tracker SET entry
                // so SMEMBERS (the hot SSE-poll reader) does not return ghost
                // epochs whose hash is empty. The merge into running counts on
                // an empty hash is a functional no-op, but each ghost still costs
                // a Redis HGETALL round-trip on every read. Best-effort: a
                // concurrent peer that just SADDed the same epoch will be
                // recovered by the drift fallback on next read.
                untrackEpochIfHashEmpty(runId, epoch, key);
            }
            log.debug("[RunningTracker] markCompleted: runId={}, epoch={}, nodeId={}", runId, epoch, nodeId);
        } catch (Exception e) {
            log.warn("[RunningTracker] Redis markCompleted failed (non-fatal): runId={}, epoch={}, nodeId={}, error={}",
                    runId, epoch, nodeId, e.getMessage());
            if (metrics != null) metrics.recordRunningTrackerWriteFailure("markCompleted");
        }
    }

    /**
     * Best-effort SREM of {@code epoch} from the per-run tracker SET when the
     * per-epoch hash is observed empty. Any failure (including the size check
     * itself) is swallowed and recorded - the drift fallback on the reader
     * side recovers correctness if the SREM was missed or applied prematurely.
     *
     * <p>Race window (audit A 2026-05-09 finding 2): if a peer instance executes
     * {@code markRunning(runId, epoch, peer)} between the {@code HLEN} read and
     * the {@code SREM} here, the tracker may transiently lose an epoch that
     * actually has a running node. Impact: {@code getRunningCountsAcrossEpochs}
     * (fail-OPEN, SSE display path) returns {@code 0} for that epoch until the
     * next {@code markRunning} re-SADDs the tracker - cosmetic transient
     * undercount, never overcount. The fail-CLOSED gate at
     * {@link com.apimarketplace.orchestrator.trigger.ReusableTriggerService}
     * uses {@link #getRunningCountsOrThrow(String, int)} which reads the per-epoch
     * hash directly by key (NOT via tracker enumeration) and is unaffected by
     * this race. Closing it would require a Lua atomic
     * {@code if HLEN==0 then SREM} - declined here because the cure is more
     * complex than the symptom (auto-corrects on the very next SADD).
     */
    private void untrackEpochIfHashEmpty(String runId, int epoch, String hashKey) {
        try {
            Long remaining = redis.opsForHash().size(hashKey);
            if (remaining != null && remaining == 0L) {
                redis.opsForSet().remove(
                        RedisCacheKeys.runningEpochsTracker(runId), String.valueOf(epoch));
            }
        } catch (Exception ex) {
            if (metrics != null) metrics.recordRunningTrackerWriteFailure("untrackEpoch");
        }
    }

    /**
     * Read running counts for a specific (runId, epoch) - fail-OPEN.
     * Use this for non-gate paths (SSE, recovery, diagnostics). Returns an empty
     * map on Redis failure rather than throwing.
     */
    public Map<String, Integer> getRunningCounts(String runId, int epoch) {
        try {
            String key = RedisCacheKeys.runningNodes(runId, epoch);
            return readHashCounts(key);
        } catch (Exception e) {
            log.warn("[RunningTracker] Redis getRunningCounts failed (returning empty): runId={}, epoch={}, error={}",
                    runId, epoch, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Read running counts for a specific (runId, epoch) - fail-CLOSED variant
     * for the deferred-reset gate at {@code ReusableTriggerService:1586} (P2.1.4).
     *
     * <p>If Redis is unreachable, throws {@link RedisUnavailableException}. The
     * caller catches and DEFERS the epoch close (treats as "may still have running").
     * Without this, a Redis read failure would silently return empty → premature
     * gate close while a node is actually executing.
     *
     * @throws RedisUnavailableException if the Redis read fails for any reason
     */
    public Map<String, Integer> getRunningCountsOrThrow(String runId, int epoch) {
        try {
            String key = RedisCacheKeys.runningNodes(runId, epoch);
            return readHashCounts(key);
        } catch (Exception e) {
            throw new RedisUnavailableException(
                    "Redis read failed for orchestrator:running:" + runId + ":" + epoch, e);
        }
    }

    /**
     * Aggregate running counts across ALL epochs of a run, plus the legacy flat
     * key shape for backward compat during the P2.1 overlap window. Used by SSE
     * + StateReconstructor where the consumer wants "any node running anywhere
     * in this run", not per-epoch precision.
     *
     * <p>Implementation (post-2026-05-09): read the per-run epoch tracker SET
     * ({@link RedisCacheKeys#runningEpochsTracker}) via SMEMBERS to enumerate
     * the active epochs, then HGETALL each per-epoch hash and union the counts.
     * SMEMBERS is O(N) on the SET size (~1-5 epochs per run) and does NOT block
     * the Redis event loop the way {@code KEYS pattern} does over the entire
     * keyspace. At &gt;500 in-flight runs the prior KEYS-based path was the
     * dominant Redis hot-path latency contributor.
     *
     * <p>Drift fallback: if the tracker SET is missing or empty (in-flight run
     * started before this fix shipped, OR tracker TTL expired ahead of the
     * per-epoch hash TTL), fall through to a one-shot {@code KEYS} scan. The
     * fallback re-populates the tracker as a side-effect so subsequent reads
     * are O(1).
     *
     * <p>Legacy flat key ({@code orchestrator:running:{runId}}, no epoch suffix)
     * is unioned in regardless - it remains the only source for runs that
     * started under the pre-P2.1 2-arg writers.
     */
    public Map<String, Integer> getRunningCountsAcrossEpochs(String runId) {
        Map<String, Integer> merged = new HashMap<>();
        try {
            Set<Integer> epochs = readEpochsFromTracker(runId);
            if (epochs.isEmpty()) {
                // Drift fallback - covers in-flight runs predating the tracker
                // and the rare TTL-expiry case. Re-populates the tracker so the
                // next call is back on the SMEMBERS fast path.
                epochs = scanEpochsViaKeys(runId);
                for (int epoch : epochs) {
                    trackEpoch(runId, epoch);
                }
            }
            for (int epoch : epochs) {
                mergeHashCountsInto(merged, RedisCacheKeys.runningNodes(runId, epoch));
            }
            // Legacy fallback (overlap window) - flat key. Preserved verbatim:
            // its data path is independent of the tracker SET.
            mergeHashCountsInto(merged, RedisCacheKeys.runningNodes(runId));
            return merged;
        } catch (Exception e) {
            log.warn("[RunningTracker] Redis getRunningCountsAcrossEpochs failed (returning empty): runId={}, error={}",
                    runId, e.getMessage());
            return Map.of();
        }
    }

    @Override
    public void cleanupRun(String runId) {
        try {
            // Enumerate active epochs via the tracker SET (O(1) hot-path).
            Set<Integer> epochs = readEpochsFromTracker(runId);
            if (epochs.isEmpty()) {
                // Drift fallback - same rationale as the reader: catches runs
                // that predate the tracker. One-shot KEYS scan is acceptable on
                // the cleanup path because it runs once per run lifecycle end,
                // not once per SSE poll.
                epochs = scanEpochsViaKeys(runId);
            }
            if (!epochs.isEmpty()) {
                List<String> keys = new ArrayList<>(epochs.size());
                for (int epoch : epochs) {
                    keys.add(RedisCacheKeys.runningNodes(runId, epoch));
                }
                redis.delete(keys);
            }
            // Tracker SET itself + legacy flat key (overlap-window safety).
            redis.delete(RedisCacheKeys.runningEpochsTracker(runId));
            redis.delete(RedisCacheKeys.runningNodes(runId));
            log.debug("[RunningTracker] cleanupRun: runId={}, perEpochKeys={}", runId, epochs.size());
        } catch (Exception e) {
            log.warn("[RunningTracker] Redis cleanupRun failed: runId={}, error={}", runId, e.getMessage());
        }
    }

    /**
     * Read the per-run epoch tracker SET and parse its members back into ints.
     * Returns an empty set on null/missing/parse failure - callers fall back to
     * the {@code KEYS} drift path on empty.
     */
    private Set<Integer> readEpochsFromTracker(String runId) {
        Set<String> members = redis.opsForSet().members(RedisCacheKeys.runningEpochsTracker(runId));
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<Integer> epochs = new HashSet<>(members.size());
        for (String member : members) {
            try {
                epochs.add(Integer.parseInt(member));
            } catch (NumberFormatException ignored) {
                // Defensive: drop malformed entries rather than fail the whole read.
            }
        }
        return epochs;
    }

    /**
     * Drift-fallback path: enumerate per-epoch hash keys via {@code KEYS} and
     * extract the epoch suffix. Used only when the tracker SET is empty;
     * pays the blocking-KEYS cost once and re-seeds the tracker so subsequent
     * reads return to the SMEMBERS fast path.
     */
    private Set<Integer> scanEpochsViaKeys(String runId) {
        Set<String> keys = redis.keys(RedisCacheKeys.runningNodesPattern(runId));
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        String prefix = RedisCacheKeys.runningNodes(runId) + ":";
        Set<Integer> epochs = new HashSet<>(keys.size());
        for (String key : keys) {
            if (!key.startsWith(prefix)) continue;
            try {
                epochs.add(Integer.parseInt(key.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // Skip non-conforming keys (e.g. legacy flat key matched by a wider pattern).
            }
        }
        return epochs;
    }

    /** Internal helper - read a Redis hash and return non-zero entries. */
    private Map<String, Integer> readHashCounts(String key) {
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            int value = parseCount(entry.getValue());
            if (value > 0) {
                result.put(entry.getKey().toString(), value);
            }
        }
        return result;
    }

    /** Internal helper - accumulate counts from a Redis hash into a running merged map. */
    private void mergeHashCountsInto(Map<String, Integer> target, String key) {
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            int value = parseCount(entry.getValue());
            if (value > 0) {
                target.merge(entry.getKey().toString(), value, Integer::sum);
            }
        }
    }

    @Override
    public String getCacheName() {
        return "RunningNodeTracker";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.STREAMING;
    }

    @Override
    public int getCacheSize() {
        // Redis keys are not cheaply countable; return -1 (unknown).
        return -1;
    }

    private static int parseCount(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt((String) value); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        return 0;
    }
}

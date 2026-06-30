package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.cache.RedisCacheKeys;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis-backed barrier that lets parallel async agents inside a split context
 * coalesce per-item completions into a single downstream traversal.
 *
 * <h2>Why Redis?</h2>
 * <p>When orchestrator scales horizontally, split items may complete on different instances.
 * Each per-item result is claimed by a random instance via {@link PendingAgentRegistry#consume}.
 * Without shared barrier state, the sealing instance wouldn't have all per-item results.
 * Redis provides the shared coordination layer.</p>
 *
 * <h2>Data layout (Redis Hash)</h2>
 * <pre>
 *   Key: orchestrator:split-barrier:{runId}:{nodeId}:{epoch}
 *   Fields:
 *     "total"     → expected item count (set once by register)
 *     "arrived"   → number of unique items arrived so far
 *     "item:{i}"  → JSON-serialized NodeExecutionResult for item i
 * </pre>
 *
 * <h2>Atomicity</h2>
 * <p>The {@link #arrive} operation uses a Lua script to atomically check for duplicate
 * arrivals, store the result, increment the counter, and return the sealed status.
 * This prevents race conditions where two instances arrive concurrently.</p>
 *
 * <h2>TTL</h2>
 * <p>Each barrier hash expires after 2 hours (aligned with the hard timeout in
 * {@link AgentRecoveryService}). Successful seal evicts the key immediately.</p>
 */
@Component
public class SplitCoalesceTracker implements RunScopedCache {

    private static final Logger logger = LoggerFactory.getLogger(SplitCoalesceTracker.class);
    private static final Duration BARRIER_TTL = Duration.ofHours(2);

    /**
     * Lua script for atomic arrive + seal check.
     *
     * KEYS[1] = barrier hash key
     * ARGV[1] = item field name ("item:{index}")
     * ARGV[2] = JSON-serialized result
     * ARGV[3] = TTL in seconds
     *
     * Returns:
     *   -1 = duplicate arrival (item field already exists)
     *    0 = first arrival, barrier NOT sealed (more items expected)
     *    1 = first arrival, barrier IS sealed (this was the last item)
     */
    private static final String ARRIVE_LUA =
        "if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then " +
        "  return -1 " +
        "end " +
        "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) " +
        "local arrived = redis.call('HINCRBY', KEYS[1], 'arrived', 1) " +
        "redis.call('EXPIRE', KEYS[1], ARGV[3]) " +
        "local total = tonumber(redis.call('HGET', KEYS[1], 'total') or '0') " +
        "if arrived >= total then " +
        "  return 1 " +
        "else " +
        "  return 0 " +
        "end";

    private static final DefaultRedisScript<Long> ARRIVE_SCRIPT;
    static {
        ARRIVE_SCRIPT = new DefaultRedisScript<>();
        ARRIVE_SCRIPT.setScriptText(ARRIVE_LUA);
        ARRIVE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SplitCoalesceTracker(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Register an expected total for a split-async barrier. Idempotent: if the key
     * already has a "total" field, the existing value is preserved.
     */
    public void register(String runId, String nodeId, int epoch, int totalItems) {
        if (totalItems <= 0) {
            throw new IllegalArgumentException("totalItems must be > 0, got " + totalItems);
        }
        String key = RedisCacheKeys.splitBarrier(runId, nodeId, epoch);
        try {
            // HSETNX: only set if not exists (idempotent for recovery re-registration)
            redis.opsForHash().putIfAbsent(key, "total", String.valueOf(totalItems));
            redis.opsForHash().putIfAbsent(key, "arrived", "0");
            redis.expire(key, BARRIER_TTL);
            trackBarrier(runId, nodeId, epoch);
            logger.debug("[SplitCoalesceTracker] Registered: key={}, total={}", key, totalItems);
        } catch (Exception e) {
            logger.warn("[SplitCoalesceTracker] Redis register failed: key={}, error={}", key, e.getMessage());
        }
    }

    /**
     * Idempotent SADD into the per-run barrier tracker SET so {@link #cleanupRun}
     * can enumerate active barriers via SMEMBERS instead of the blocking
     * {@code KEYS} command. Best-effort - drift fallback in {@code cleanupRun}
     * recovers if the SET write fails.
     */
    private void trackBarrier(String runId, String nodeId, int epoch) {
        try {
            String trackerKey = RedisCacheKeys.splitBarriersTracker(runId);
            redis.opsForSet().add(trackerKey, nodeId + ":" + epoch);
            redis.expire(trackerKey, BARRIER_TTL);
        } catch (Exception trackerEx) {
            logger.warn("[SplitCoalesceTracker] Redis tracker SADD failed (non-fatal): runId={}, error={}",
                    runId, trackerEx.getMessage());
        }
    }

    /**
     * Best-effort SREM from the per-run barrier tracker SET, called after a
     * barrier seals + the per-barrier hash key is deleted. Failure is silently
     * dropped - {@link #cleanupRun} on a missing tracker entry is a no-op.
     */
    private void untrackBarrier(String runId, String nodeId, int epoch) {
        try {
            redis.opsForSet().remove(RedisCacheKeys.splitBarriersTracker(runId), nodeId + ":" + epoch);
        } catch (Exception sremEx) {
            logger.debug("[SplitCoalesceTracker] Tracker SREM failed (non-fatal): runId={}, nodeId={}, epoch={}, error={}",
                runId, nodeId, epoch, sremEx.getMessage());
        }
    }

    /**
     * Record one per-item arrival with its result. Idempotent: a duplicate arrival for
     * the same {@code itemIndex} is ignored by the Lua script (no counter increment).
     *
     * @return the sealed batch paired with each item's absolute {@code itemIndex}
     *         (ordered ascending) if this arrival was the last expected one;
     *         {@link Optional#empty()} if more items are still pending OR if no
     *         barrier is registered for this key. Per-item indices may be
     *         non-contiguous when upstream filtering short-circuits some items - see
     *         {@link IndexedNodeResult}.
     */
    public Optional<List<IndexedNodeResult>> arrive(
            String runId, String nodeId, int epoch, int itemIndex, NodeExecutionResult result) {
        String key = RedisCacheKeys.splitBarrier(runId, nodeId, epoch);
        try {
            String resultJson = serializeResult(result);
            String itemField = "item:" + itemIndex;
            long ttlSeconds = BARRIER_TTL.getSeconds();

            Long scriptResult = redis.execute(
                ARRIVE_SCRIPT,
                Collections.singletonList(key),
                itemField, resultJson, String.valueOf(ttlSeconds)
            );

            if (scriptResult == null) {
                logger.warn("[SplitCoalesceTracker] Lua script returned null: key={}, itemIndex={}", key, itemIndex);
                return Optional.empty();
            }

            if (scriptResult == -1) {
                logger.debug("[SplitCoalesceTracker] Duplicate arrival ignored: key={}, itemIndex={}", key, itemIndex);
                return Optional.empty();
            }

            if (scriptResult == 0) {
                logger.debug("[SplitCoalesceTracker] Arrival (not sealed): key={}, itemIndex={}", key, itemIndex);
                return Optional.empty();
            }

            // Sealed! Load all items from hash, then clean up the key.
            logger.info("[SplitCoalesceTracker] Barrier sealed: key={}, itemIndex={}", key, itemIndex);
            List<IndexedNodeResult> batch = loadSealedBatch(key);
            try {
                redis.delete(key);
            } catch (Exception deleteEx) {
                // Non-fatal: TTL will clean up. Don't lose the sealed batch.
                logger.warn("[SplitCoalesceTracker] Redis delete after seal failed (TTL will clean up): key={}, error={}",
                    key, deleteEx.getMessage());
            }
            // Best-effort tracker prune: SREM the (nodeId, epoch) pair so cleanupRun
            // doesn't see a stale entry for an already-deleted barrier.
            untrackBarrier(runId, nodeId, epoch);
            return Optional.of(batch);

        } catch (Exception e) {
            logger.warn("[SplitCoalesceTracker] Redis arrive failed: key={}, itemIndex={}, error={}",
                key, itemIndex, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** True iff a barrier is currently registered for this run/node/epoch. */
    public boolean isRegistered(String runId, String nodeId, int epoch) {
        String key = RedisCacheKeys.splitBarrier(runId, nodeId, epoch);
        try {
            return Boolean.TRUE.equals(redis.opsForHash().hasKey(key, "total"));
        } catch (Exception e) {
            logger.warn("[SplitCoalesceTracker] Redis isRegistered failed: key={}, error={}", key, e.getMessage());
            return false;
        }
    }

    // ========================================================================
    // RunScopedCache IMPLEMENTATION
    // ========================================================================

    @Override
    public void cleanupRun(String runId) {
        try {
            String trackerKey = RedisCacheKeys.splitBarriersTracker(runId);
            java.util.Set<String> entries = redis.opsForSet().members(trackerKey);
            java.util.List<String> keysToDelete;
            if (entries == null || entries.isEmpty()) {
                // Drift fallback: SET tracker missing - pre-fix in-flight run or
                // TTL expired. One-shot KEYS on the cleanup path is acceptable
                // (called once per run lifecycle end, not per SSE poll).
                java.util.Set<String> keys = redis.keys(RedisCacheKeys.splitBarrierPattern(runId));
                keysToDelete = (keys == null || keys.isEmpty())
                        ? java.util.List.of()
                        : new java.util.ArrayList<>(keys);
            } else {
                keysToDelete = new java.util.ArrayList<>(entries.size());
                for (String entry : entries) {
                    int sep = entry.lastIndexOf(':');
                    if (sep <= 0 || sep >= entry.length() - 1) continue;
                    String nodeId = entry.substring(0, sep);
                    try {
                        int epoch = Integer.parseInt(entry.substring(sep + 1));
                        keysToDelete.add(RedisCacheKeys.splitBarrier(runId, nodeId, epoch));
                    } catch (NumberFormatException ignored) {
                        // Malformed tracker entry - skip. The DEL on the tracker key
                        // below evicts it regardless.
                    }
                }
            }
            if (!keysToDelete.isEmpty()) {
                redis.delete(keysToDelete);
            }
            // Always evict the tracker SET itself.
            redis.delete(trackerKey);
            logger.debug("[SplitCoalesceTracker] cleanupRun: runId={}, deleted {} barrier keys", runId, keysToDelete.size());
        } catch (Exception e) {
            logger.warn("[SplitCoalesceTracker] Redis cleanupRun failed: runId={}, error={}", runId, e.getMessage());
        }
    }

    @Override
    public String getCacheName() {
        return "SplitCoalesceTracker";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.REDIS;
    }

    @Override
    public int getCacheSize() {
        return -1;
    }

    /** Test-only hook: drop all barriers. */
    public void clear() {
        // No-op in Redis mode - TTL handles cleanup. Tests should flush Redis directly.
    }

    // ========================================================================
    // SERIALIZATION
    // ========================================================================

    private String serializeResult(NodeExecutionResult result) throws JsonProcessingException {
        Map<String, Object> map = new HashMap<>();
        map.put("nodeId", result.nodeId());
        map.put("status", result.status().name());
        map.put("output", result.output());
        map.put("errorMessage", result.errorMessage().orElse(null));
        map.put("metadata", result.metadata());
        map.put("durationMs", result.durationMs());
        return objectMapper.writeValueAsString(map);
    }

    @SuppressWarnings("unchecked")
    private NodeExecutionResult deserializeResult(String json) throws JsonProcessingException {
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        String nodeId = (String) map.get("nodeId");
        NodeStatus status = NodeStatus.valueOf((String) map.get("status"));
        Map<String, Object> output = map.get("output") instanceof Map
            ? (Map<String, Object>) map.get("output") : Map.of();
        Optional<String> errorMessage = Optional.ofNullable((String) map.get("errorMessage"));
        Map<String, Object> metadata = map.get("metadata") instanceof Map
            ? (Map<String, Object>) map.get("metadata") : Map.of();
        long durationMs = map.get("durationMs") instanceof Number
            ? ((Number) map.get("durationMs")).longValue() : 0L;
        return new NodeExecutionResult(nodeId, status, output, errorMessage, metadata, durationMs);
    }

    /**
     * Load all arrived item results from the sealed barrier hash, ordered by ascending
     * absolute {@code itemIndex}.
     *
     * <p>We scan the hash for all {@code item:{N}} fields and parse {@code N} directly
     * rather than iterating {@code 0..total-1}. Upstream filtering (e.g. a Decision
     * node that short-circuits some split items) causes the items that reach this
     * barrier to have non-contiguous indices - iterating a fixed 0..N-1 range dropped
     * any item whose index exceeded the expected count and left "Missing item" gaps
     * for indices that were filtered out upstream.
     */
    private List<IndexedNodeResult> loadSealedBatch(String key) {
        Map<Object, Object> entries = redis.opsForHash().entries(key);

        List<IndexedNodeResult> batch = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String field = entry.getKey().toString();
            if (!field.startsWith("item:")) {
                // Bookkeeping fields ("total", "arrived") - skip.
                continue;
            }
            int itemIndex;
            try {
                itemIndex = Integer.parseInt(field.substring("item:".length()));
            } catch (NumberFormatException nfe) {
                logger.warn("[SplitCoalesceTracker] Invalid item field '{}' in barrier: key={}", field, key);
                continue;
            }
            try {
                NodeExecutionResult result = deserializeResult(entry.getValue().toString());
                batch.add(new IndexedNodeResult(itemIndex, result));
            } catch (Exception e) {
                logger.warn("[SplitCoalesceTracker] Failed to deserialize item {}: key={}, error={}",
                    itemIndex, key, e.getMessage());
            }
        }
        // Stable order by itemIndex so downstream emitters see a predictable sequence.
        batch.sort(java.util.Comparator.comparingInt(IndexedNodeResult::itemIndex));
        return batch;
    }
}

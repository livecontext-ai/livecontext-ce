package com.apimarketplace.orchestrator.services.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/**
 * Multi-pod ({@code scaling.backend=redis}) {@link RunSeqBackend} - the seq counter
 * lives in a single Redis key {@code orch:ws:seq:{runId}} shared by every
 * orchestrator replica, so the WS sequence stays globally monotonic no matter which
 * pod processes a given step / epoch / signal-resolution. This is the Redis variant
 * the {@link WsEventSequencer} javadoc promised but that never existed (the bug that
 * froze the run page and desynced nodes under {@code replicas: 2} -
 * {@code the project docs} #2).
 *
 * <p><b>Atomicity</b>: the HOT path ({@link #INCR_PRESENT}) is one atomic Lua EVAL -
 * {@code GET}; if present {@code INCR + PEXPIRE} and return {v, 0}. Concurrent pods
 * INCR the same shared value, so no duplicate and no lower seq is ever handed out.
 *
 * <p>If the key is ABSENT (fresh run, or lost to TTL-expiry / eviction / failover)
 * {@code INCR_PRESENT} returns {0, 1} and DELIBERATELY does not create it, so a
 * stale-low local floor is never written to a lost key. {@link #next} then reseeds via
 * {@link #SEED_INCR} with {@code floor = max(localHighWater, dbSeed)} (one DB read, ONLY
 * on this rare path). Because the key stays absent until the reseed, every concurrent
 * pod takes this branch and their {@code SEED_INCR} EVALs serialize at the same floor -
 * distinct, strictly increasing, never regressing below the frontend's known seq (the
 * durable {@code last_event_seq} flusher keeps {@code dbSeed} current). This is what
 * makes the counter safe even when a lost key is re-created by several pods at once.
 *
 * <p><b>TTL</b>: refreshed on every event, so an active run never expires;
 * {@code remove} (epoch/run close) deletes the key proactively.
 *
 * <p><b>Redis down</b>: degrades to a local atomic increment (per-pod, may briefly
 * diverge across pods exactly like the old memory mode) rather than throwing - a WS
 * seq glitch is preferable to failing the execution path that stamps it.
 */
final class RedisRunSeqBackend implements RunSeqBackend {

    private static final Logger log = LoggerFactory.getLogger(RedisRunSeqBackend.class);

    private static final String KEY_PREFIX = "orch:ws:seq:";

    /**
     * Hot path. KEYS[1]=key, ARGV[1]=floor, ARGV[2]=ttlMs. Returns {newSeq, absent}.
     * If the key is ABSENT it returns {0, 1} and does NOT create it - so a stale-low
     * local floor is never written to a lost key, and concurrent pods all fall through
     * to the authoritative {@link #SEED_INCR} reseed instead of racing on a low value.
     * If the key is present it INCRs (single atomic round-trip) and returns {v, 0}.
     */
    private static final DefaultRedisScript<List> INCR_PRESENT = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1]) "
          + "if cur == false then return {0, 1} end "
          + "if tonumber(cur) < tonumber(ARGV[1]) then redis.call('SET', KEYS[1], ARGV[1]) end "
          + "local v = redis.call('INCR', KEYS[1]) "
          + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
          + "return {v, 0}",
            List.class);

    /**
     * Reseed path (rare: fresh run, or key lost to TTL-expiry / eviction / failover).
     * KEYS[1]=key, ARGV[1]=floor, ARGV[2]=ttlMs. Atomically seeds the key to at least
     * {@code floor} then INCRs. The seed-floor guard makes it idempotent under
     * concurrency: N pods reseeding a lost key serialize (Redis is single-threaded),
     * each subsequent EVAL sees the prior INCR, so they hand out distinct, strictly
     * increasing values and none regresses below {@code floor}. Returns the new seq.
     */
    private static final DefaultRedisScript<Long> SEED_INCR = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1]) "
          + "if (cur == false) or (tonumber(cur) < tonumber(ARGV[1])) then redis.call('SET', KEYS[1], ARGV[1]) end "
          + "local v = redis.call('INCR', KEYS[1]) "
          + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
          + "return v",
            Long.class);

    private final StringRedisTemplate redis;
    private final long ttlMs;

    /**
     * Highest seq THIS pod has handed out per runId. Doubles as the per-pod
     * INCR floor (so a lost/expired key never regresses below what we already
     * emitted) and as {@link #peek}'s answer for the cleanup flush. Never the
     * authoritative value - Redis is - but a safe lower bound.
     */
    private final ConcurrentHashMap<String, Long> localHighWater = new ConcurrentHashMap<>();

    RedisRunSeqBackend(StringRedisTemplate redis, long ttlMs) {
        this.redis = redis;
        this.ttlMs = Math.max(1L, ttlMs);
        log.info("[RedisRunSeqBackend] Initialized shared WS sequence (ttl={}ms) - multi-pod monotonic seq active", this.ttlMs);
    }

    @Override
    public long next(String runId, ToLongFunction<String> seedLoader) {
        // floor = this pod's local high-water, lazily seeded from the DB ONCE per
        // fresh run (no DB hit on the hot path). A present-but-stale local floor is
        // reconciled below, but only when the script reports the key was lost.
        long floor = localHighWater.computeIfAbsent(runId, k -> Math.max(0L, seedLoader.applyAsLong(k)));
        try {
            List<?> res = redis.execute(INCR_PRESENT, List.of(key(runId)),
                    Long.toString(floor), Long.toString(ttlMs));
            if (res != null && res.size() >= 2 && asLong(res.get(1)) == 0L) {
                // Hot path: the shared key was live, INCR_PRESENT returned the next seq.
                long value = asLong(res.get(0));
                localHighWater.merge(runId, value, Math::max);
                return value;
            }
            // Key ABSENT (fresh run, or lost to TTL-expiry / eviction / failover) and,
            // crucially, NOT re-created above. Our local floor can lag the global
            // high-water other pods persisted to workflow_runs.last_event_seq, so reseed
            // atomically from max(local, durable DB seed) - one DB read, ONLY on this rare
            // path. Because the key stays absent until SEED_INCR, concurrent pods all take
            // this branch and their SEED_INCR EVALs serialize at the same floor: distinct,
            // strictly-increasing values, never a regression below the frontend's seq.
            long dbSeed = Math.max(0L, seedLoader.applyAsLong(runId));
            long reseedFloor = Math.max(floor, dbSeed);
            Long value = redis.execute(SEED_INCR, List.of(key(runId)),
                    Long.toString(reseedFloor), Long.toString(ttlMs));
            if (value != null) {
                localHighWater.merge(runId, value, Math::max);
                return value;
            }
            log.warn("[RedisRunSeqBackend] SEED_INCR returned null for runId={} (degraded local increment)", runId);
        } catch (Exception e) {
            log.warn("[RedisRunSeqBackend] Redis unavailable for runId={} (degraded local increment): {}",
                    runId, e.getMessage());
        }
        // Degraded: atomic per-pod increment. compute() is atomic per key, so even
        // concurrent split items get distinct local values.
        return localHighWater.compute(runId, (k, old) -> (old == null ? floor : old) + 1L);
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o).trim());
    }

    @Override
    public long current(String runId, ToLongFunction<String> seedLoader) {
        // Authoritative shared value first; never creates the key.
        try {
            String s = redis.opsForValue().get(key(runId));
            if (s != null) {
                return Long.parseLong(s.trim());
            }
        } catch (Exception e) {
            log.debug("[RedisRunSeqBackend] current() Redis read failed for runId={}: {}", runId, e.getMessage());
        }
        Long local = localHighWater.get(runId);
        if (local != null) {
            return local;
        }
        return Math.max(0L, seedLoader.applyAsLong(runId));
    }

    @Override
    public OptionalLong peek(String runId) {
        Long local = localHighWater.get(runId);
        return local != null ? OptionalLong.of(local) : OptionalLong.empty();
    }

    @Override
    public void remove(String runId) {
        localHighWater.remove(runId);
        try {
            redis.delete(key(runId));
        } catch (Exception e) {
            log.debug("[RedisRunSeqBackend] remove() Redis delete failed for runId={} (will TTL-expire): {}",
                    runId, e.getMessage());
        }
    }

    @Override
    public int size() {
        return localHighWater.size();
    }

    private static String key(String runId) {
        return KEY_PREFIX + runId;
    }
}

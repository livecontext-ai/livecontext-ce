package com.apimarketplace.orchestrator.services.scaling;

import com.apimarketplace.common.scaling.registry.RedisServiceRegistry;
import com.apimarketplace.orchestrator.config.OrchestratorInstanceRegistrar;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase B.2 (archi-refoundation 2026-05-04) - distributed leader lock for
 * snapshot publishing in multi-replica orchestrator deployments.
 *
 * <p><b>Problem (audit C v3 axe1)</b>: in {@code scaling.backend=redis} mode,
 * multiple orchestrator pods can execute code for the same {@code runId}
 * (sub-workflow async, signal resolution on a different replica). Each pod's
 * in-memory {@link com.apimarketplace.orchestrator.services.streaming.SnapshotService}
 * coalesces independently → 2+ batch-update events per state transition,
 * partially defeating the {@code -85%} WS volume target. Worse, the events
 * may carry the same WsEventSequencer-assigned seq from different pods (each
 * pod has its own AtomicLong), letting the frontend strict-{@code <} guard
 * accept clobbered payloads.
 *
 * <p><b>Design</b>: SETNX-based ownership per runId, TTL 30s, refreshed by a
 * single-thread heartbeat every 10s. Pattern is borrowed from
 * {@code SignalResumeService.tryAcquireDistributedLock} (verified
 * {@code SignalResumeService:1244-1276}) - the SETNX shape is identical, but
 * the heartbeat is new (snapshot ownership is long-lived; the OAuth2
 * 180s-no-heartbeat pattern is wrong for this use case - audit B v5 C5).
 *
 * <p><b>Local cache</b>: a Caffeine {@code Cache<runId, ownedUntilMs>} avoids
 * a Redis round-trip on every {@code amOwner} check (path chaud). TTL is
 * half the Redis TTL ({@code 15s}) so we never trust the cache past the safety
 * margin. Heartbeat refreshes both Redis and cache.
 *
 * <p><b>Conditional</b>: {@code @ConditionalOnBean(RedisServiceRegistry.class)}
 * - only registered when {@code scaling.backend=redis}. In single-pod
 * memory mode this bean is absent, callers see {@code null}, and the
 * fallback (publish via the local throttle) is used as today.
 *
 * <p><b>NOT a port of OAuth2Service</b> (audits A+B v5/v6): OAuth2 lock has
 * no heartbeat (TTL 180s, refreshed by HTTP path frequency). Our use case is
 * long-lived, so a periodic refresh is required.
 */
@Component
@ConditionalOnBean(RedisServiceRegistry.class)
public class RedisLeaderLock {

    private static final Logger log = LoggerFactory.getLogger(RedisLeaderLock.class);

    private static final String LOCK_PREFIX = "orch:snapshot:owner:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final Duration LOCAL_CACHE_TTL = Duration.ofSeconds(15);  // TTL/2 safety margin

    /**
     * Atomic compare-and-delete release script (borrowed from
     * {@code SignalResumeService.RELEASE_LOCK_SCRIPT:1265-1267}). Returns 1 if
     * the calling instance still owned the lock and the key was deleted, 0
     * otherwise.
     */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redis;
    private final String instanceId;

    /**
     * Set of runIds for which this instance currently holds the lock. Used by
     * {@link #heartbeat()} to refresh TTLs in batch (pipelined). Concurrent
     * because acquisition can happen from any thread.
     */
    private final Set<String> ownedRuns = ConcurrentHashMap.newKeySet();

    /**
     * Local "I am owner" cache. Value = epochMs at which the local belief expires.
     * Avoids a round-trip Redis on every {@link #amOwner(String)} call (hot path).
     * TTL is {@code LOCAL_CACHE_TTL} (15s) - half the Redis TTL so we always
     * re-validate before the lock could plausibly have expired.
     */
    private final Cache<String, Long> localOwnerCache;

    public RedisLeaderLock(StringRedisTemplate redis, OrchestratorInstanceRegistrar registrar) {
        this.redis = redis;
        this.instanceId = registrar.getInstanceId();
        this.localOwnerCache = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(LOCAL_CACHE_TTL)
                .build();
        log.info("[RedisLeaderLock] Initialized: instanceId={}, lockTtl={}s, heartbeat={}s",
                instanceId, LOCK_TTL.getSeconds(), HEARTBEAT_INTERVAL.getSeconds());
    }

    /**
     * Try to acquire the snapshot lock for {@code runId}. Returns {@code true}
     * if either:
     * <ul>
     *   <li>This instance just SETNX'd the key (we became owner), or</li>
     *   <li>This instance was already the owner (idempotent re-acquire).</li>
     * </ul>
     *
     * <p>On Redis failure: returns {@code false} (fail-closed for the
     * coalescing path; the caller's fallback is to publish direct without
     * coalescing - accepts +X% events, but 0 events lost).
     */
    public boolean tryAcquire(String runId) {
        if (runId == null) return false;
        String key = LOCK_PREFIX + runId;
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, instanceId, LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                ownedRuns.add(runId);
                localOwnerCache.put(runId, System.currentTimeMillis() + LOCAL_CACHE_TTL.toMillis());
                return true;
            }
            // Already locked - check if it's us
            String currentOwner = redis.opsForValue().get(key);
            if (instanceId.equals(currentOwner)) {
                ownedRuns.add(runId);
                localOwnerCache.put(runId, System.currentTimeMillis() + LOCAL_CACHE_TTL.toMillis());
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("[RedisLeaderLock] tryAcquire failed for runId={} (defaulting to non-owner): {}",
                    runId, e.getMessage());
            return false;
        }
    }

    /**
     * Fast-path: returns {@code true} if this instance currently believes it
     * owns the runId (within the local cache TTL). Avoids Redis round-trip on
     * every event publish.
     *
     * <p>Stale-cache risk: between cache-expiry and heartbeat-refresh, this
     * could return {@code false} for a run we still own. Acceptable: the next
     * call falls through to {@link #tryAcquire} which re-reads Redis.
     */
    public boolean amOwner(String runId) {
        if (runId == null) return false;
        Long expiry = localOwnerCache.getIfPresent(runId);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    /**
     * Release the lock atomically (only if we still own it). Idempotent.
     * Called when a run terminates ({@code cleanupRun}).
     */
    public void release(String runId) {
        if (runId == null) return;
        ownedRuns.remove(runId);
        localOwnerCache.invalidate(runId);
        try {
            redis.execute(RELEASE_LOCK_SCRIPT, List.of(LOCK_PREFIX + runId), instanceId);
        } catch (Exception e) {
            log.debug("[RedisLeaderLock] release failed for runId={} (will auto-expire via TTL): {}",
                    runId, e.getMessage());
        }
    }

    /**
     * Heartbeat: refresh the Redis TTL on all owned locks every 10s
     * (Phase B.2 audit C v6 NA-3). <b>Pipelined</b> - single round-trip for N
     * runs (vs N round-trips naïve loop). At 1000 owned runs: 1 RPS Redis for
     * heartbeat (vs 100 RPS naïve).
     *
     * <p>If heartbeat fails for a run, we evict it from {@code ownedRuns} and
     * the local cache; the next event publish path will attempt re-acquire.
     */
    @Scheduled(fixedDelayString = "${redis-leader-lock.heartbeat-ms:10000}",
               initialDelayString = "${redis-leader-lock.heartbeat-initial-delay-ms:10000}")
    public void heartbeat() {
        if (ownedRuns.isEmpty()) return;
        // Snapshot to avoid concurrent-modification during the pipeline
        Collection<String> snapshot = new ArrayList<>(ownedRuns);
        try {
            redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                for (String runId : snapshot) {
                    String key = LOCK_PREFIX + runId;
                    connection.keyCommands().pExpire(key.getBytes(), LOCK_TTL.toMillis());
                }
                return null;
            });
            // Refresh local cache TTLs in lock-step
            long newExpiry = System.currentTimeMillis() + LOCAL_CACHE_TTL.toMillis();
            for (String runId : snapshot) {
                localOwnerCache.put(runId, newExpiry);
            }
            log.debug("[RedisLeaderLock] Heartbeat refreshed {} owned locks", snapshot.size());
        } catch (Exception e) {
            log.warn("[RedisLeaderLock] Heartbeat batch failed (will retry next tick): {}", e.getMessage());
        }
    }

    /** Test/admin hook: number of locks currently held by this instance. */
    public int ownedCount() {
        return ownedRuns.size();
    }
}

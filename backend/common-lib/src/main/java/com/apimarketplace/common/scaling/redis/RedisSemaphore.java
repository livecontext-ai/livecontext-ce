package com.apimarketplace.common.scaling.redis;

import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Redis-backed distributed semaphore using ZSET with per-owner expiry timestamps.
 *
 * <p>Each permit is stored as a ZSET member (ownerId) with score = expiry epoch seconds.
 * Expired owners are atomically cleaned up inside the Lua acquire script using
 * {@code ZREMRANGEBYSCORE} with Redis server time (no clock skew).
 *
 * <p>Failure strategy: FAIL-CLOSED - {@code tryAcquire()} returns false on Redis error.
 */
public class RedisSemaphore implements DistributedSemaphore {

    private static final Logger log = LoggerFactory.getLogger(RedisSemaphore.class);

    private static final String KEY_PREFIX = "orch:epoch:limiter:";
    private static final int DEFAULT_TTL_SECONDS = 7200; // 2 hours - must exceed max execution time (agent loops: 70min)

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    private final DefaultRedisScript<Long> acquireScript;
    private final DefaultRedisScript<Long> releaseScript;
    private final DefaultRedisScript<Long> cleanupScript;
    private final DefaultRedisScript<Long> heartbeatScript;
    private final DefaultRedisScript<Long> countScript;

    public RedisSemaphore(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.acquireScript = LuaScriptLoader.load("semaphore_acquire.lua", Long.class);
        this.releaseScript = LuaScriptLoader.load("semaphore_release.lua", Long.class);
        this.cleanupScript = LuaScriptLoader.load("semaphore_cleanup.lua", Long.class);
        this.heartbeatScript = LuaScriptLoader.load("semaphore_heartbeat.lua", Long.class);
        this.countScript = LuaScriptLoader.load("semaphore_count.lua", Long.class);
    }

    @Override
    public boolean tryAcquire(String key, int maxPermits, String ownerId) {
        return tryAcquire(key, maxPermits, ownerId, Duration.ofSeconds(DEFAULT_TTL_SECONDS));
    }

    @Override
    public boolean tryAcquire(String key, int maxPermits, String ownerId, Duration ttl) {
        String redisKey = KEY_PREFIX + key;
        long ttlSeconds = Math.max(1, ttl != null ? ttl.toSeconds() : DEFAULT_TTL_SECONDS);
        try {
            Long result = Timer.builder("scaling.redis.semaphore.acquire")
                    .tag("key", sanitizeTag(key))
                    .register(meterRegistry)
                    .record(() -> redisTemplate.execute(acquireScript,
                            Collections.singletonList(redisKey),
                            String.valueOf(maxPermits),
                            ownerId,
                            String.valueOf(ttlSeconds)));
            boolean acquired = result != null && result == 1L;
            log.debug("[RedisSemaphore] tryAcquire key={}, owner={}, max={} -> {}",
                    key, ownerId, maxPermits, acquired);
            return acquired;
        } catch (Exception e) {
            log.error("[RedisSemaphore] tryAcquire failed (FAIL-CLOSED): key={}, error={}",
                    key, e.getMessage());
            return false; // FAIL-CLOSED
        }
    }

    @Override
    public void release(String key, String ownerId) {
        String redisKey = KEY_PREFIX + key;
        try {
            Long result = Timer.builder("scaling.redis.semaphore.release")
                    .tag("key", sanitizeTag(key))
                    .register(meterRegistry)
                    .record(() -> redisTemplate.execute(releaseScript,
                            Collections.singletonList(redisKey),
                            ownerId));
            log.debug("[RedisSemaphore] release key={}, owner={} -> removed={}",
                    key, ownerId, result);
        } catch (Exception e) {
            log.error("[RedisSemaphore] release failed: key={}, owner={}, error={}",
                    key, ownerId, e.getMessage());
        }
    }

    @Override
    public int availablePermits(String key, int maxPermits) {
        String redisKey = KEY_PREFIX + key;
        try {
            Long count = redisTemplate.execute(countScript, Collections.singletonList(redisKey));
            int held = count != null ? count.intValue() : 0;
            return Math.max(0, maxPermits - held);
        } catch (Exception e) {
            log.error("[RedisSemaphore] availablePermits failed: key={}, error={}",
                    key, e.getMessage());
            return 0; // FAIL-CLOSED: report no permits available
        }
    }

    @Override
    public void cleanupByPrefix(String keyPrefix) {
        String pattern = KEY_PREFIX + keyPrefix + "*";
        try {
            // Use SCAN instead of KEYS to avoid blocking the Redis event loop
            Set<String> keys = new HashSet<>();
            try (var cursor = redisTemplate.scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(pattern).count(100).build())) {
                cursor.forEachRemaining(keys::add);
            }
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("[RedisSemaphore] cleanupByPrefix '{}': deleted {} keys",
                        keyPrefix, keys.size());
            }
        } catch (Exception e) {
            log.error("[RedisSemaphore] cleanupByPrefix failed: prefix={}, error={}",
                    keyPrefix, e.getMessage());
        }
    }

    /**
     * Extend the TTL of an active permit (heartbeat).
     *
     * @param key     resource identifier
     * @param ownerId the owner extending their permit
     * @return true if the permit was found and extended
     */
    @Override
    public boolean heartbeat(String key, String ownerId) {
        return heartbeat(key, ownerId, Duration.ofSeconds(DEFAULT_TTL_SECONDS));
    }

    @Override
    public boolean heartbeat(String key, String ownerId, Duration ttl) {
        String redisKey = KEY_PREFIX + key;
        long ttlSeconds = Math.max(1, ttl != null ? ttl.toSeconds() : DEFAULT_TTL_SECONDS);
        try {
            Long result = redisTemplate.execute(heartbeatScript,
                    Collections.singletonList(redisKey),
                    ownerId,
                    String.valueOf(ttlSeconds));
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("[RedisSemaphore] heartbeat failed: key={}, owner={}", key, ownerId);
            return false;
        }
    }

    private static String sanitizeTag(String key) {
        // Limit cardinality for Micrometer tags
        int idx = key.indexOf(':');
        return idx > 0 ? key.substring(0, idx) : key;
    }
}

package com.apimarketplace.common.scaling.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Redis-backed service registry using a sorted set per service name.
 *
 * <p>Each instance registers with its heartbeat timestamp as the score.
 * Discovery returns only instances whose heartbeat is fresh (within stale threshold).
 * Stale entries are pruned on every discovery call.
 *
 * <p>Key format: {@code orch:registry:{serviceName}}
 * Member format: {@code instanceId|host|port}
 * Score: epoch millis of last heartbeat
 */
public class RedisServiceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(RedisServiceRegistry.class);
    private static final String KEY_PREFIX = "orch:registry:";

    private final StringRedisTemplate redis;
    private final long staleThresholdMs;

    /**
     * @param redis              Redis template
     * @param staleThresholdMs   Instances with heartbeat older than this are considered dead (default 30000)
     */
    public RedisServiceRegistry(StringRedisTemplate redis, long staleThresholdMs) {
        this.redis = redis;
        this.staleThresholdMs = staleThresholdMs;
    }

    /**
     * Register or heartbeat an instance. Sets the score to current epoch millis.
     */
    public void heartbeat(String serviceName, ServiceInstance instance) {
        String key = KEY_PREFIX + serviceName;
        double score = System.currentTimeMillis();
        redis.opsForZSet().add(key, instance.encode(), score);
    }

    /**
     * Deregister an instance (graceful shutdown).
     */
    public void deregister(String serviceName, ServiceInstance instance) {
        String key = KEY_PREFIX + serviceName;
        redis.opsForZSet().remove(key, instance.encode());
        logger.info("[ServiceRegistry] Deregistered {} from {}", instance.instanceId(), serviceName);
    }

    /**
     * Get all live instances for a service (heartbeat within stale threshold).
     * Prunes stale entries as a side effect.
     */
    public List<ServiceInstance> getInstances(String serviceName) {
        String key = KEY_PREFIX + serviceName;
        long now = System.currentTimeMillis();
        long cutoff = now - staleThresholdMs;

        // Prune stale entries (score < cutoff)
        redis.opsForZSet().removeRangeByScore(key, 0, cutoff);

        // Return all remaining (fresh) entries
        Set<String> members = redis.opsForZSet().rangeByScore(key, cutoff, Double.MAX_VALUE);
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }

        return members.stream()
                .map(encoded -> {
                    try {
                        return ServiceInstance.decode(encoded);
                    } catch (Exception e) {
                        logger.warn("[ServiceRegistry] Skipping malformed entry: {}", encoded);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}

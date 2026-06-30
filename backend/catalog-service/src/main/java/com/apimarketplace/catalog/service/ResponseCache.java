package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed cache for API responses with configurable TTL.
 * Prevents duplicate API calls when LLM needs to expand truncated fields.
 *
 * Redis key pattern: catalog:response:{hash}
 * TTL: 5 minutes (configurable via catalog.cache.redis.response-cache-ttl)
 */
@Slf4j
@Component
public class ResponseCache {

    private static final String KEY_PREFIX = "catalog:response:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisConfig redisConfig;

    public ResponseCache(RedisTemplate<String, Object> redisTemplate, RedisConfig redisConfig) {
        this.redisTemplate = redisTemplate;
        this.redisConfig = redisConfig;
        log.info("ResponseCache initialized with Redis, TTL={}", redisConfig.getResponseCacheTtl());
    }

    /**
     * Get cached response if available and not expired.
     *
     * @param toolId     The tool identifier
     * @param parameters The request parameters
     * @return Cached response or null if not found/expired
     */
    public Object get(String toolId, Map<String, Object> parameters) {
        String key = buildKey(toolId, parameters);

        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("Cache HIT for tool: {} (key={})", toolId, key);
                return cached;
            }
            log.debug("Cache MISS for tool: {} (key={})", toolId, key);
            return null;
        } catch (Exception e) {
            log.warn("Redis read error for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Store response in cache with TTL.
     *
     * @param toolId     The tool identifier
     * @param parameters The request parameters
     * @param response   The API response to cache
     */
    public void put(String toolId, Map<String, Object> parameters, Object response) {
        if (response == null) {
            return;
        }

        String key = buildKey(toolId, parameters);

        try {
            redisTemplate.opsForValue().set(
                key,
                response,
                redisConfig.getResponseCacheTtl().toMillis(),
                TimeUnit.MILLISECONDS
            );
            log.debug("Cached response for tool: {} (key={}, ttl={})",
                toolId, key, redisConfig.getResponseCacheTtl());
        } catch (Exception e) {
            log.warn("Redis write error for key {}: {}", key, e.getMessage());
        }
    }

    /**
     * Invalidate cache for a specific tool/parameters combination.
     *
     * @param toolId     The tool identifier
     * @param parameters The request parameters
     */
    public void invalidate(String toolId, Map<String, Object> parameters) {
        String key = buildKey(toolId, parameters);

        try {
            Boolean deleted = redisTemplate.delete(key);
            if (Boolean.TRUE.equals(deleted)) {
                log.debug("Invalidated cache for tool: {} (key={})", toolId, key);
            }
        } catch (Exception e) {
            log.warn("Redis delete error for key {}: {}", key, e.getMessage());
        }
    }

    /**
     * Invalidate all response cache entries.
     */
    public void invalidateAll() {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Invalidated {} response cache entries", keys.size());
            }
        } catch (Exception e) {
            log.warn("Redis clear error: {}", e.getMessage());
        }
    }

    /**
     * Build cache key from tool and parameters.
     * Uses MD5 hash for consistent, compact key generation.
     */
    private String buildKey(String toolId, Map<String, Object> parameters) {
        String paramString = parameters != null ? parameters.toString() : "";
        String combined = toolId + ":" + paramString;

        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return KEY_PREFIX + sb.toString();
        } catch (Exception e) {
            // Fallback to simple hash
            return KEY_PREFIX + Math.abs(combined.hashCode());
        }
    }

    /**
     * Get cache statistics.
     */
    public Map<String, Object> getStats() {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            int size = keys != null ? keys.size() : 0;
            return Map.of(
                "size", size,
                "ttlMinutes", redisConfig.getResponseCacheTtl().toMinutes(),
                "backend", "redis"
            );
        } catch (Exception e) {
            log.warn("Redis stats error: {}", e.getMessage());
            return Map.of(
                "size", -1,
                "ttlMinutes", redisConfig.getResponseCacheTtl().toMinutes(),
                "backend", "redis",
                "error", e.getMessage()
            );
        }
    }
}

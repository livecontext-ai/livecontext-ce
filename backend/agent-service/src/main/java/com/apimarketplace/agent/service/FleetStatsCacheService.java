package com.apimarketplace.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Short-TTL Redis cache for the fleet batch stats payload ({@code GET /agents/stats}).
 *
 * <p>The fleet stats are <b>overlay badges</b> (cumulative ✓/✗ counts per agent/tool/
 * model), recomputed from the full execution history by four GROUP-BY aggregations.
 * They are non-critical and only read on canvas mount / manual refresh - the fleet's
 * real-time activity (running indicators, live tool calls) flows through a SEPARATE
 * WebSocket → Zustand channel and never refetches this endpoint. So a few seconds of
 * staleness here is invisible, which makes a short-TTL cache the cheapest big win.
 *
 * <p><b>Namespace.</b> Keys are {@code agent:fleetstats:{orgId}} - disjoint from the
 * WebSocket pub/sub channels ({@code agent:activity:*}); GET/SET keys and SUBSCRIBE
 * channels are separate Redis namespaces anyway, so there is no collision with the
 * real-time event path.
 *
 * <p><b>Graceful degradation.</b> Every Redis call is wrapped: a cache miss, a parse
 * failure, or Redis being entirely unavailable all degrade to "no cache" (the caller
 * recomputes from the DB). The stats endpoint never fails because of this cache.
 *
 * <p><b>Coherence.</b> Entries are actively evicted at the execution-finalize
 * choke-point (see {@code AgentObservabilityService}) so a freshly-completed run's
 * badges show on the next open instead of waiting for the TTL.
 */
@Service
public class FleetStatsCacheService {

    private static final Logger logger = LoggerFactory.getLogger(FleetStatsCacheService.class);
    private static final String KEY_PREFIX = "agent:fleetstats:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Duration ttl;

    public FleetStatsCacheService(
            StringRedisTemplate redis,
            @Value("${agent.fleet-stats.cache-ttl-seconds:60}") long cacheTtlSeconds) {
        this.redis = redis;
        // Floor at 1s so a misconfigured 0/negative value never disables expiry (which
        // would let a stale payload live forever). Default 60s.
        this.ttl = Duration.ofSeconds(Math.max(1L, cacheTtlSeconds));
    }

    /** Cached payload for this workspace, or empty on miss / parse failure / Redis down. */
    public Optional<Map<String, Object>> get(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key(organizationId));
            if (json == null) {
                return Optional.empty();
            }
            Map<String, Object> parsed = objectMapper.readValue(
                json, new TypeReference<LinkedHashMap<String, Object>>() {});
            // Cheap shape sanity check: only serve something that actually looks like a
            // fleet-stats payload. Guards against a key collision / a future writer ever
            // parking a differently-shaped object under this namespace.
            if (!parsed.containsKey("toolStats")) {
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (Exception e) {
            logger.warn("Fleet stats cache read failed for org={}: {}", organizationId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Store the computed payload with the configured TTL. No-op on serialization / Redis failure. */
    public void put(String organizationId, Map<String, Object> stats) {
        if (organizationId == null || organizationId.isBlank() || stats == null) {
            return;
        }
        try {
            redis.opsForValue().set(key(organizationId), objectMapper.writeValueAsString(stats), ttl);
        } catch (Exception e) {
            logger.warn("Fleet stats cache write failed for org={}: {}", organizationId, e.getMessage());
        }
    }

    /** Drop the cached payload so the next read recomputes. No-op on Redis failure. */
    public void evict(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return;
        }
        try {
            redis.delete(key(organizationId));
        } catch (Exception e) {
            logger.warn("Fleet stats cache evict failed for org={}: {}", organizationId, e.getMessage());
        }
    }

    private static String key(String organizationId) {
        return KEY_PREFIX + organizationId;
    }
}

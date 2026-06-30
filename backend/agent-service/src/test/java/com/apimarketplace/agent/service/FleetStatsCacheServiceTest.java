package com.apimarketplace.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link FleetStatsCacheService} - the short-TTL Redis cache for the
 * fleet stats payload. Pins the key namespace, TTL floor, JSON round-trip, and (critically)
 * the graceful-degradation contract: nothing it does can ever throw into the stats endpoint.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FleetStatsCacheService")
class FleetStatsCacheServiceTest {

    private static final String ORG = "org-acme";
    private static final String EXPECTED_KEY = "agent:fleetstats:org-acme";

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private FleetStatsCacheService cache;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        cache = new FleetStatsCacheService(redis, 60L);
    }

    @Test
    @DisplayName("get returns the parsed payload on a hit, keyed agent:fleetstats:{org}")
    void getHitParsesPayload() {
        when(valueOps.get(EXPECTED_KEY)).thenReturn(
            "{\"toolStats\":[{\"agentId\":\"a\"}],\"modelStats\":[]}");

        Optional<Map<String, Object>> result = cache.get(ORG);

        assertThat(result).isPresent();
        assertThat(result.get()).containsKey("toolStats").containsKey("modelStats");
    }

    @Test
    @DisplayName("get returns empty on a miss (null value)")
    void getMissReturnsEmpty() {
        when(valueOps.get(EXPECTED_KEY)).thenReturn(null);
        assertThat(cache.get(ORG)).isEmpty();
    }

    @Test
    @DisplayName("get treats a parseable-but-foreign object (no toolStats key) as a miss")
    void getForeignShapeIsMiss() {
        when(valueOps.get(EXPECTED_KEY)).thenReturn("{\"somethingElse\":1}");
        assertThat(cache.get(ORG)).isEmpty();
    }

    @Test
    @DisplayName("a put→get round-trip preserves the payload's values (Long/Double/null/String survive the JSON encoding)")
    void putGetRoundTripPreservesValueTypes() {
        // The cache must be transparent: a HIT has to return what a MISS would have computed.
        // The query rows carry Long counts, a Double rate, and null fields - exercise all.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("agentId", "agent-1");
        row.put("toolName", "web_search");
        row.put("totalCalls", 5L);
        row.put("successRatePct", 83.3);
        row.put("lastUsedAt", null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolStats", List.of(row));
        payload.put("resourceStats", List.of());
        payload.put("subAgentStats", List.of());
        payload.put("modelStats", List.of());

        // Capture exactly what put() serialized, then feed it back through get().
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        cache.put(ORG, payload);
        verify(valueOps).set(eq(EXPECTED_KEY), stored.capture(), eq(Duration.ofSeconds(60)));
        when(valueOps.get(EXPECTED_KEY)).thenReturn(stored.getValue());

        Optional<Map<String, Object>> result = cache.get(ORG);

        assertThat(result).isPresent();
        Map<String, Object> out = result.get();
        assertThat(out).containsOnlyKeys("toolStats", "resourceStats", "subAgentStats", "modelStats");
        @SuppressWarnings("unchecked")
        Map<String, Object> outRow = ((List<Map<String, Object>>) out.get("toolStats")).get(0);
        assertThat(outRow.get("agentId")).isEqualTo("agent-1");
        assertThat(outRow.get("toolName")).isEqualTo("web_search");
        assertThat(((Number) outRow.get("totalCalls")).longValue()).isEqualTo(5L);
        assertThat(((Number) outRow.get("successRatePct")).doubleValue()).isEqualTo(83.3);
        // A null field must survive as a present-but-null key (the frontend reads it).
        assertThat(outRow).containsKey("lastUsedAt");
        assertThat(outRow.get("lastUsedAt")).isNull();
    }

    @Test
    @DisplayName("get degrades to empty (never throws) when the stored value is unparseable")
    void getUnparseableDegradesToEmpty() {
        when(valueOps.get(EXPECTED_KEY)).thenReturn("not-json{");
        assertThat(cache.get(ORG)).isEmpty();
    }

    @Test
    @DisplayName("get degrades to empty (never throws) when Redis is down")
    void getRedisDownDegradesToEmpty() {
        when(valueOps.get(EXPECTED_KEY)).thenThrow(new RuntimeException("connection refused"));
        assertThat(cache.get(ORG)).isEmpty();
    }

    @Test
    @DisplayName("get is a no-op (empty) for a blank workspace id and never calls Redis")
    void getBlankOrgIsNoOp() {
        assertThat(cache.get("  ")).isEmpty();
        assertThat(cache.get(null)).isEmpty();
        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("put serializes the payload and sets it with the configured TTL under the namespaced key")
    void putSetsWithTtl() {
        Map<String, Object> payload = Map.of("toolStats", List.of(Map.of("agentId", "a")));

        cache.put(ORG, payload);

        verify(valueOps).set(eq(EXPECTED_KEY), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("put degrades silently (never throws) when Redis is down")
    void putRedisDownIsSwallowed() {
        org.mockito.Mockito.doThrow(new RuntimeException("connection refused"))
            .when(valueOps).set(anyString(), anyString(), eq(Duration.ofSeconds(60)));
        // Must not propagate.
        cache.put(ORG, Map.of("toolStats", List.of()));
    }

    @Test
    @DisplayName("put is a no-op for blank org / null payload")
    void putNoOpGuards() {
        cache.put("  ", Map.of("x", 1));
        cache.put(ORG, null);
        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("evict deletes the namespaced key")
    void evictDeletesKey() {
        cache.evict(ORG);
        verify(redis).delete(EXPECTED_KEY);
    }

    @Test
    @DisplayName("evict degrades silently (never throws) when Redis is down")
    void evictRedisDownIsSwallowed() {
        when(redis.delete(anyString())).thenThrow(new RuntimeException("connection refused"));
        cache.evict(ORG); // must not propagate
    }

    @Test
    @DisplayName("evict is a no-op for a blank workspace id")
    void evictBlankOrgIsNoOp() {
        cache.evict("");
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("a non-positive configured TTL is floored to 1s so expiry is never disabled")
    void ttlIsFlooredToOneSecond() {
        FleetStatsCacheService zeroTtl = new FleetStatsCacheService(redis, 0L);
        zeroTtl.put(ORG, Map.of("toolStats", List.of()));
        verify(valueOps).set(eq(EXPECTED_KEY), anyString(), eq(Duration.ofSeconds(1)));
    }
}

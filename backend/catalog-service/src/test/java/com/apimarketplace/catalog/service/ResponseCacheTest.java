package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ResponseCache.
 *
 * ResponseCache is a Redis-backed cache for API responses with configurable TTL.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseCache")
class ResponseCacheTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private RedisConfig redisConfig;

    private ResponseCache cache;

    @BeforeEach
    void setUp() {
        when(redisConfig.getResponseCacheTtl()).thenReturn(Duration.ofMinutes(5));
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new ResponseCache(redisTemplate, redisConfig);
    }

    // ========================================================================
    // get() method tests
    // ========================================================================

    @Nested
    @DisplayName("get()")
    class GetTests {

        @Test
        @DisplayName("should return null for non-existent key")
        void shouldReturnNullForNonExistentKey() {
            when(valueOperations.get(anyString())).thenReturn(null);

            Object result = cache.get("non-existent-tool", Map.of("param", "value"));

            assertNull(result);
            verify(valueOperations).get(anyString());
        }

        @Test
        @DisplayName("should return cached response for valid key")
        void shouldReturnCachedResponseForValidKey() {
            String toolId = "test-tool";
            Map<String, Object> params = Map.of("query", "test");
            String response = "cached response";

            when(valueOperations.get(anyString())).thenReturn(response);

            Object result = cache.get(toolId, params);

            assertEquals(response, result);
        }

        @Test
        @DisplayName("should handle null parameters")
        void shouldHandleNullParameters() {
            String toolId = "test-tool";
            String response = "cached response";

            when(valueOperations.get(anyString())).thenReturn(response);

            Object result = cache.get(toolId, null);

            assertEquals(response, result);
        }

        @Test
        @DisplayName("should handle empty parameters")
        void shouldHandleEmptyParameters() {
            String toolId = "test-tool";
            Map<String, Object> params = Map.of();
            String response = "cached response";

            when(valueOperations.get(anyString())).thenReturn(response);

            Object result = cache.get(toolId, params);

            assertEquals(response, result);
        }

        @Test
        @DisplayName("should return complex object response")
        void shouldReturnComplexObjectResponse() {
            String toolId = "test-tool";
            Map<String, Object> params = Map.of("id", 123);
            Map<String, Object> response = Map.of(
                "status", "success",
                "data", Map.of("name", "test", "value", 42)
            );

            when(valueOperations.get(anyString())).thenReturn(response);

            Object result = cache.get(toolId, params);

            assertEquals(response, result);
        }

        @Test
        @DisplayName("should handle Redis exception gracefully")
        void shouldHandleRedisExceptionGracefully() {
            when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis unavailable"));

            Object result = cache.get("test-tool", Map.of("q", "1"));

            assertNull(result);
        }
    }

    // ========================================================================
    // put() method tests
    // ========================================================================

    @Nested
    @DisplayName("put()")
    class PutTests {

        @Test
        @DisplayName("should store response in Redis with TTL")
        void shouldStoreResponseInRedisWithTtl() {
            String toolId = "test-tool";
            Map<String, Object> params = Map.of("query", "test");
            String response = "cached response";

            cache.put(toolId, params, response);

            verify(valueOperations).set(
                anyString(),
                eq(response),
                eq(Duration.ofMinutes(5).toMillis()),
                eq(TimeUnit.MILLISECONDS)
            );
        }

        @Test
        @DisplayName("should not store null response")
        void shouldNotStoreNullResponse() {
            String toolId = "test-tool";
            Map<String, Object> params = Map.of("query", "test");

            cache.put(toolId, params, null);

            verify(valueOperations, never()).set(anyString(), any(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("should handle parameters with various types")
        void shouldHandleParametersWithVariousTypes() {
            String toolId = "test-tool";
            Map<String, Object> params = new HashMap<>();
            params.put("string", "value");
            params.put("number", 42);
            params.put("boolean", true);
            params.put("list", java.util.List.of(1, 2, 3));

            cache.put(toolId, params, "response");

            verify(valueOperations).set(
                anyString(),
                eq("response"),
                eq(Duration.ofMinutes(5).toMillis()),
                eq(TimeUnit.MILLISECONDS)
            );
        }

        @Test
        @DisplayName("should handle Redis exception gracefully")
        void shouldHandleRedisExceptionGracefully() {
            doThrow(new RuntimeException("Redis unavailable"))
                .when(valueOperations).set(anyString(), any(), anyLong(), any(TimeUnit.class));

            // Should not throw
            assertDoesNotThrow(() -> cache.put("test-tool", Map.of("q", "1"), "response"));
        }
    }

    // ========================================================================
    // invalidate() method tests
    // ========================================================================

    @Nested
    @DisplayName("invalidate()")
    class InvalidateTests {

        @Test
        @DisplayName("should delete key from Redis")
        void shouldDeleteKeyFromRedis() {
            when(redisTemplate.delete(anyString())).thenReturn(true);

            cache.invalidate("test-tool", Map.of("q", "1"));

            verify(redisTemplate).delete(anyString());
        }

        @Test
        @DisplayName("should handle Redis exception gracefully")
        void shouldHandleRedisExceptionGracefully() {
            when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("Redis unavailable"));

            // Should not throw
            assertDoesNotThrow(() -> cache.invalidate("test-tool", Map.of("q", "1")));
        }
    }

    // ========================================================================
    // invalidateAll() method tests
    // ========================================================================

    @Nested
    @DisplayName("invalidateAll()")
    class InvalidateAllTests {

        @Test
        @DisplayName("should delete all keys with prefix")
        void shouldDeleteAllKeysWithPrefix() {
            Set<String> keys = Set.of("catalog:response:abc", "catalog:response:def");
            when(redisTemplate.keys("catalog:response:*")).thenReturn(keys);
            when(redisTemplate.delete(keys)).thenReturn(2L);

            cache.invalidateAll();

            verify(redisTemplate).keys("catalog:response:*");
            verify(redisTemplate).delete(keys);
        }

        @Test
        @DisplayName("should handle empty keys set")
        void shouldHandleEmptyKeysSet() {
            when(redisTemplate.keys("catalog:response:*")).thenReturn(Set.of());

            cache.invalidateAll();

            verify(redisTemplate).keys("catalog:response:*");
            verify(redisTemplate, never()).delete(anyCollection());
        }

        @Test
        @DisplayName("should handle null keys set")
        void shouldHandleNullKeysSet() {
            when(redisTemplate.keys("catalog:response:*")).thenReturn(null);

            // Should not throw
            assertDoesNotThrow(() -> cache.invalidateAll());
        }
    }

    // ========================================================================
    // getStats() method tests
    // ========================================================================

    @Nested
    @DisplayName("getStats()")
    class GetStatsTests {

        @Test
        @DisplayName("should return correct stats")
        void shouldReturnCorrectStats() {
            Set<String> keys = Set.of("catalog:response:abc", "catalog:response:def");
            when(redisTemplate.keys("catalog:response:*")).thenReturn(keys);

            Map<String, Object> stats = cache.getStats();

            assertEquals(2, stats.get("size"));
            assertEquals(5L, stats.get("ttlMinutes"));
            assertEquals("redis", stats.get("backend"));
        }

        @Test
        @DisplayName("should return 0 size for empty cache")
        void shouldReturnZeroSizeForEmptyCache() {
            when(redisTemplate.keys("catalog:response:*")).thenReturn(Set.of());

            Map<String, Object> stats = cache.getStats();

            assertEquals(0, stats.get("size"));
        }

        @Test
        @DisplayName("should handle Redis exception gracefully")
        void shouldHandleRedisExceptionGracefully() {
            when(redisTemplate.keys(anyString())).thenThrow(new RuntimeException("Redis unavailable"));

            Map<String, Object> stats = cache.getStats();

            assertEquals(-1, stats.get("size"));
            assertEquals("redis", stats.get("backend"));
            assertNotNull(stats.get("error"));
        }
    }

    // ========================================================================
    // Key generation tests (tested indirectly)
    // ========================================================================

    @Nested
    @DisplayName("Key generation")
    class KeyGenerationTests {

        @Test
        @DisplayName("should generate consistent keys for same inputs")
        void shouldGenerateConsistentKeysForSameInputs() {
            String toolId = "test-tool";
            Map<String, Object> params = Map.of("a", 1, "b", "value");

            // First call
            cache.get(toolId, params);
            // Second call with same inputs
            cache.get(toolId, Map.of("a", 1, "b", "value"));

            // Both calls should use the same key
            verify(valueOperations, times(2)).get(argThat((String key) ->
                key != null && key.startsWith("catalog:response:")
            ));
        }

        @Test
        @DisplayName("should generate different keys for different tool IDs")
        void shouldGenerateDifferentKeysForDifferentToolIds() {
            Map<String, Object> params = Map.of("q", "test");

            when(valueOperations.get(anyString())).thenReturn(null);

            cache.get("tool-a", params);
            cache.get("tool-b", params);

            // Verify two different keys were used
            verify(valueOperations, times(2)).get(argThat((String key) ->
                key != null && key.startsWith("catalog:response:")
            ));
        }

        @Test
        @DisplayName("key should start with correct prefix")
        void keyShouldStartWithCorrectPrefix() {
            when(valueOperations.get(anyString())).thenReturn(null);

            cache.get("test-tool", Map.of("q", "1"));

            verify(valueOperations).get(argThat((String key) ->
                key != null && key.startsWith("catalog:response:")
            ));
        }
    }
}

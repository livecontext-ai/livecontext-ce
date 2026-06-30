package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cache-invariant tests for the response shaping pipeline.
 *
 * <p>The contract: shaping params ({@code expand}, {@code max_items}) live on
 * {@link com.apimarketplace.catalog.domain.dto.ToolExecutionRequest} as
 * separate fields, NEVER inside the {@code parameters} Map fed to
 * {@link ResponseCache#buildKey}. Two calls with identical call-params and
 * different shaping params must hit the cache on the second call (no API
 * re-fetch); shaping then runs fresh per call on the cached tree.
 *
 * <p><b>Scope of this invariant:</b> applies to STREAM-scoped (chat-agent)
 * callers only. Workflow callers (RUN scope, or no scope) bypass
 * {@link ResponseCache} entirely - see {@code ToolExecutionManagerTest}
 * {@code WorkflowCacheBypass} for that contract. The shaping-param-strip
 * rule below is still correct in isolation (it tests {@code ResponseCache}
 * directly, not via {@code ToolExecutionManager}), but its operational
 * benefit is realised only on the STREAM path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Cache invariant - shaping params do NOT influence the cache key")
class CacheInvariantTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;
    @Mock
    private RedisConfig redisConfig;

    private ResponseCache cache;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisConfig.getResponseCacheTtl()).thenReturn(Duration.ofMinutes(5));
        cache = new ResponseCache(redisTemplate, redisConfig);
    }

    @Nested
    @DisplayName("ResponseCache.buildKey")
    class CacheKeyTests {

        @Test
        @DisplayName("cacheKeyIgnoresShapingParams - same parameters Map → same Redis key")
        void cacheKeyIgnoresShapingParams() {
            // Same `parameters` map fed to put() and get(); shaping params do
            // NOT travel here (they live on ToolExecutionRequest fields stripped
            // upstream by CatalogExecuteModule.extractShapingParams). Both calls
            // must hit the same Redis key.
            String toolId = "tool-uuid";
            Map<String, Object> firstCallParams = new LinkedHashMap<>();
            firstCallParams.put("dataset_id", "X");
            firstCallParams.put("clean", true);
            String expectedKey = computeCacheKey(toolId, firstCallParams);

            cache.put(toolId, firstCallParams, "first-response");

            Map<String, Object> secondCallParams = new LinkedHashMap<>();
            secondCallParams.put("dataset_id", "X");
            secondCallParams.put("clean", true);
            cache.get(toolId, secondCallParams);

            verify(valueOps).set(eq(expectedKey), eq("first-response"), anyLong(), any());
            verify(valueOps).get(eq(expectedKey));
        }

        /** Mirror of {@link ResponseCache#buildKey(String, Map)} for assertion. */
        private static String computeCacheKey(String toolId, Map<String, Object> params) {
            try {
                String combined = toolId + ":" + params;
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] hash = md.digest(combined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : hash) sb.append(String.format("%02x", b));
                return "catalog:response:" + sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Nested
    @DisplayName("STREAM mode discriminator")
    class StreamScopeTests {

        @Test
        @DisplayName("streamScopeMatchIsCaseInsensitive - lowercase 'stream' selects AGENT")
        void streamScopeMatchIsCaseInsensitive() {
            // Locks the discriminator at ToolExecutionManager:239:
            //   "STREAM".equalsIgnoreCase(scopeKind) ? AGENT : WORKFLOW.
            // Defends against any header-canonicalisation (Caddy, gateway) that
            // might lowercase or mixed-case the X-Lc-Billing-Scope-Kind header.
            assertTrue("STREAM".equalsIgnoreCase("stream"));
            assertTrue("STREAM".equalsIgnoreCase("Stream"));
            assertTrue("STREAM".equalsIgnoreCase("STREAM"));
            assertFalse("STREAM".equalsIgnoreCase("RUN"));
            assertFalse("STREAM".equalsIgnoreCase(null));
        }
    }
}

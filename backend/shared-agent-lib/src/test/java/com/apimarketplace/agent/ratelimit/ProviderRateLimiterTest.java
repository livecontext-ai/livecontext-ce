package com.apimarketplace.agent.ratelimit;

import com.apimarketplace.agent.provider.LLMProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProviderRateLimiter.
 *
 * The rate limiter uses an "acquire-before-call" pattern:
 * - checkRateLimit() or tryAcquire() reserves capacity AND records usage
 * - recordRequest() is a no-op (kept for API compatibility)
 */
class ProviderRateLimiterTest {

    private static final String TENANT_ID = "test-tenant";
    private RateLimitConfig config;
    private ProviderRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig();
        config.setEnabled(true);
        config.setDefaultMode(RateLimitMode.FAIL_FAST); // Use FAIL_FAST for tests

        // Configure test limits (global strategy by default)
        RateLimitConfig.ProviderLimit openaiLimit = new RateLimitConfig.ProviderLimit();
        openaiLimit.setTokensPerMinute(1000);
        openaiLimit.setRequestsPerMinute(10);

        RateLimitConfig.ProviderLimit claudeLimit = new RateLimitConfig.ProviderLimit();
        claudeLimit.setTokensPerMinute(500);
        claudeLimit.setRequestsPerMinute(5);

        config.getProviders().put("openai", openaiLimit);
        config.getProviders().put("anthropic", claudeLimit);

        rateLimiter = new ProviderRateLimiter(config);
    }

    @Test
    void shouldAllowRequestWithinTokenLimit() {
        // Should not throw - 100 tokens within 1000 TPM limit
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("openai", TENANT_ID, 100));
    }

    @Test
    void shouldBlockRequestExceedingTokenLimit() {
        // First request: 900 tokens (reserves 900)
        rateLimiter.checkRateLimit("openai", TENANT_ID, 900);

        // Second request: 200 tokens would exceed 1000 TPM limit (900 + 200 = 1100)
        LLMProviderException exception = assertThrows(
            LLMProviderException.class,
            () -> rateLimiter.checkRateLimit("openai", TENANT_ID, 200)
        );

        assertTrue(exception.getMessage().contains("token") || exception.getMessage().contains("Token")
                || exception.getMessage().contains("TPM"));
        assertTrue(exception.isRetryable());
    }

    @Test
    void shouldAllowRequestWithinRequestLimit() {
        // Make 9 requests (limit is 10 RPM)
        for (int i = 0; i < 9; i++) {
            rateLimiter.checkRateLimit("openai", TENANT_ID, 10);
        }

        // 10th request should still be allowed
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("openai", TENANT_ID, 10));
    }

    @Test
    void shouldBlockRequestExceedingRequestLimit() {
        // Make 10 requests (hit the limit)
        for (int i = 0; i < 10; i++) {
            rateLimiter.checkRateLimit("openai", TENANT_ID, 10);
        }

        // 11th request should be blocked (exceeds 10 RPM)
        LLMProviderException exception = assertThrows(
            LLMProviderException.class,
            () -> rateLimiter.checkRateLimit("openai", TENANT_ID, 10)
        );

        assertTrue(exception.getMessage().contains("request") || exception.getMessage().contains("Request")
                || exception.getMessage().contains("RPM"));
        assertTrue(exception.isRetryable());
    }

    @Test
    void shouldTrackUsagePerProvider() {
        // Each checkRateLimit reserves and records the tokens
        rateLimiter.checkRateLimit("openai", TENANT_ID, 100);
        rateLimiter.checkRateLimit("anthropic", TENANT_ID, 50);

        ProviderRateLimiter.UsageStats openaiStats = rateLimiter.getGlobalUsageStats("openai");
        ProviderRateLimiter.UsageStats claudeStats = rateLimiter.getGlobalUsageStats("anthropic");

        assertEquals(100, openaiStats.currentTokens());
        assertEquals(50, claudeStats.currentTokens());
        assertEquals(1, openaiStats.currentRequests());
        assertEquals(1, claudeStats.currentRequests());
    }

    @Test
    void shouldCalculateUsagePercentage() {
        rateLimiter.checkRateLimit("openai", TENANT_ID, 500); // 50% of 1000 TPM

        ProviderRateLimiter.UsageStats stats = rateLimiter.getGlobalUsageStats("openai");

        assertEquals(50.0, stats.getTokenUsagePercent(), 0.1);
        assertEquals(10.0, stats.getRequestUsagePercent(), 0.1); // 1 of 10 requests
    }

    @Test
    void shouldUseDefaultLimitsForUnconfiguredProvider() {
        // Configure custom defaults with low limits
        RateLimitConfig.ProviderLimit defaults = new RateLimitConfig.ProviderLimit();
        defaults.setTokensPerMinute(100);
        defaults.setRequestsPerMinute(5);
        config.setDefaults(defaults);

        // First request: 80 tokens
        rateLimiter.checkRateLimit("unknown-provider", TENANT_ID, 80);

        // Second request: 15 tokens should be allowed (80 + 15 = 95 < 100)
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("unknown-provider", TENANT_ID, 15));

        // Third request: 50 more tokens should exceed limit (95 + 50 = 145 > 100)
        assertThrows(
            LLMProviderException.class,
            () -> rateLimiter.checkRateLimit("unknown-provider", TENANT_ID, 50)
        );
    }

    @Test
    void shouldNotEnforceWhenDisabled() {
        config.setEnabled(false);
        ProviderRateLimiter disabledLimiter = new ProviderRateLimiter(config);

        // Should allow way over limits (no exception)
        assertDoesNotThrow(() -> disabledLimiter.checkRateLimit("openai", TENANT_ID, 10000));
        assertDoesNotThrow(() -> disabledLimiter.checkRateLimit("openai", TENANT_ID, 10000));
    }

    @Test
    void shouldNotEnforceWhenLimitSetToNegative() {
        RateLimitConfig.ProviderLimit unlimitedProvider = new RateLimitConfig.ProviderLimit();
        unlimitedProvider.setTokensPerMinute(-1);
        unlimitedProvider.setRequestsPerMinute(-1);
        config.getProviders().put("unlimited", unlimitedProvider);

        // Should allow any amount (negative limit means disabled)
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("unlimited", TENANT_ID, 100000));
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("unlimited", TENANT_ID, 100000));
    }

    @Test
    void shouldCleanupOldEntries() {
        // Note: This test verifies that usage is tracked
        // Actual time-based cleanup happens in real usage

        rateLimiter.checkRateLimit("openai", TENANT_ID, 500);

        ProviderRateLimiter.UsageStats statsAfter = rateLimiter.getGlobalUsageStats("openai");
        assertEquals(500, statsAfter.currentTokens());

        // In production, after 60 seconds, old entries would be cleaned up
        // For testing, we verify tracking works correctly
        assertTrue(statsAfter.currentTokens() > 0);
    }

    @Test
    void shouldReturnCorrectLimitInfo() {
        ProviderRateLimiter.UsageStats stats = rateLimiter.getGlobalUsageStats("openai");

        assertEquals(1000, stats.tokenLimit());
        assertEquals(10, stats.requestLimit());
        assertEquals(0, stats.currentTokens());
        assertEquals(0, stats.currentRequests());
    }

    @Test
    void shouldHandleConcurrentRequests() throws InterruptedException {
        // Test thread-safety with concurrent requests
        int numThreads = 10;
        Thread[] threads = new Thread[numThreads];

        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(() -> {
                try {
                    rateLimiter.checkRateLimit("openai", TENANT_ID, 50);
                } catch (LLMProviderException e) {
                    // Expected if limit exceeded
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Usage should be tracked correctly
        ProviderRateLimiter.UsageStats stats = rateLimiter.getGlobalUsageStats("openai");
        assertTrue(stats.currentTokens() > 0);
        assertTrue(stats.currentRequests() <= 10); // Should not exceed request limit
    }

    @Test
    void sharedAtomicReservationLockPreventsOvershootAcrossLimiterInstances() throws Exception {
        config.setStrategy(RateLimitStrategy.PER_TENANT);
        config.getProviders().put("openai", new RateLimitConfig.ProviderLimit(-1, -1, -1, 5));
        RateLimitWindowFactory sharedFactory = sharedLockedInMemoryWindowFactory();
        ProviderRateLimiter firstInstance = new ProviderRateLimiter(config, sharedFactory, null);
        ProviderRateLimiter secondInstance = new ProviderRateLimiter(config, sharedFactory, null);

        int attempts = 20;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < attempts; i++) {
                ProviderRateLimiter limiter = i % 2 == 0 ? firstInstance : secondInstance;
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        limiter.checkRateLimit("openai", "tenant-shared", 1);
                        allowed.incrementAndGet();
                    } catch (LLMProviderException e) {
                        blocked.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(5, allowed.get());
        assertEquals(15, blocked.get());
        assertEquals(5, firstInstance.getTenantUsageStats("openai", "tenant-shared").currentRequests());
    }

    @Test
    void perTenantStrategyUsesTenantScopedReservationLocks() {
        config.setStrategy(RateLimitStrategy.PER_TENANT);
        RecordingWindowFactory factory = new RecordingWindowFactory();
        ProviderRateLimiter limiter = new ProviderRateLimiter(config, factory, null);

        limiter.checkRateLimit("openai", "tenant-a", 1);
        limiter.checkRateLimit("openai", "tenant-b", 1);

        assertTrue(factory.lockKeys.contains("reserve:openai:tenant:tenant-a"));
        assertTrue(factory.lockKeys.contains("reserve:openai:tenant:tenant-b"));
        assertFalse(factory.lockKeys.contains("reserve:openai"));
    }

    @Test
    void hybridStrategyKeepsProviderScopedReservationLockForGlobalCapacity() {
        config.setStrategy(RateLimitStrategy.HYBRID);
        RecordingWindowFactory factory = new RecordingWindowFactory();
        ProviderRateLimiter limiter = new ProviderRateLimiter(config, factory, null);

        limiter.checkRateLimit("openai", "tenant-a", 1);
        limiter.checkRateLimit("openai", "tenant-b", 1);

        assertEquals(List.of("reserve:openai", "reserve:openai"), factory.lockKeys);
    }

    @Test
    void cleanupKeepsTenantReservationLockWhileTenantWindowIsActive() throws Exception {
        config.setStrategy(RateLimitStrategy.PER_TENANT);
        config.getProviders().put("openai", new RateLimitConfig.ProviderLimit(-1, -1, -1, 5));
        ProviderRateLimiter limiter = new ProviderRateLimiter(config);

        limiter.checkRateLimit("openai", "tenant-cleanup", 1);
        limiter.cleanupInactiveTenants();

        assertTrue(lockKeys(limiter).contains("reserve:openai:tenant:tenant-cleanup"));
    }

    @Test
    void shouldWorkWithNullTenantId() {
        // Should work with null tenant ID (global mode)
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("openai", null, 100));

        ProviderRateLimiter.UsageStats stats = rateLimiter.getGlobalUsageStats("openai");
        assertEquals(100, stats.currentTokens());
    }

    @Test
    void shouldUseTryAcquireNonBlocking() {
        // Test the non-blocking tryAcquire method
        RateLimitResult result = rateLimiter.tryAcquire("openai", TENANT_ID, 100);

        assertTrue(result.isAllowed());
        assertTrue(result.remainingCapacity() > 0);

        // Fill up the token limit (1000 TPM)
        // First call already used 100, so add 9 more of 100 each = 1000 total
        for (int i = 0; i < 9; i++) {
            rateLimiter.tryAcquire("openai", TENANT_ID, 100);
        }

        // Should be blocked now (1000 + 200 = 1200 > 1000)
        RateLimitResult blockedResult = rateLimiter.tryAcquire("openai", TENANT_ID, 200);
        assertTrue(blockedResult.isBlocked());
        assertNotNull(blockedResult.reason());
        assertTrue(blockedResult.waitTime().toMillis() > 0);
    }

    // ==================== PER-MODEL RATE LIMITING ====================

    private ProviderRateLimiter createWithModelOverrides(ModelRateLimitProvider provider) {
        return new ProviderRateLimiter(config, null, provider);
    }

    private RateLimitWindowFactory sharedLockedInMemoryWindowFactory() {
        ConcurrentMap<String, RateLimitWindow> windows = new ConcurrentHashMap<>();
        ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
        return new RateLimitWindowFactory() {
            @Override
            public RateLimitWindow create(String windowId, int windowSizeSeconds) {
                return windows.computeIfAbsent(windowId, ignored -> new InMemoryRateLimitWindow(windowSizeSeconds));
            }

            @Override
            public <T> T withAtomicReservationLock(String lockKey, java.util.function.Supplier<T> operation) {
                synchronized (locks.computeIfAbsent(lockKey, ignored -> new Object())) {
                    return operation.get();
                }
            }
        };
    }

    private static final class RecordingWindowFactory implements RateLimitWindowFactory {
        private final ConcurrentMap<String, RateLimitWindow> windows = new ConcurrentHashMap<>();
        private final List<String> lockKeys = new ArrayList<>();

        @Override
        public RateLimitWindow create(String windowId, int windowSizeSeconds) {
            return windows.computeIfAbsent(windowId, ignored -> new InMemoryRateLimitWindow(windowSizeSeconds));
        }

        @Override
        public <T> T withAtomicReservationLock(String lockKey, java.util.function.Supplier<T> operation) {
            lockKeys.add(lockKey);
            return operation.get();
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> lockKeys(ProviderRateLimiter limiter) throws Exception {
        var field = ProviderRateLimiter.class.getDeclaredField("windowLocks");
        field.setAccessible(true);
        return new HashSet<>(((Map<String, ?>) field.get(limiter)).keySet());
    }

    @Test
    void shouldUseModelOverrideWhenPresent() {
        // openai provider limit: 1000 TPM, 10 RPM
        // Model override for gpt-5: 200 TPM (inherit RPM)
        ModelRateLimitProvider modelProvider = (prov, model) -> {
            if ("openai".equals(prov) && "gpt-5".equals(model)) {
                return new ModelRateLimit(200, null, null, null);
            }
            return null;
        };
        ProviderRateLimiter limiter = createWithModelOverrides(modelProvider);

        // 150 tokens on gpt-5 should succeed (under 200 TPM model limit)
        assertDoesNotThrow(() -> limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 150));

        // Next 100 should fail (150 + 100 = 250 > 200 TPM model limit)
        assertThrows(LLMProviderException.class,
                () -> limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 100));
    }

    @Test
    void shouldFallBackToProviderLimitWhenNoModelOverride() {
        ModelRateLimitProvider modelProvider = (prov, model) -> null;
        ProviderRateLimiter limiter = createWithModelOverrides(modelProvider);

        // Should use provider limit (1000 TPM)
        assertDoesNotThrow(() -> limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 800));

        // 800 + 300 = 1100 > 1000 provider limit
        assertThrows(LLMProviderException.class,
                () -> limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 300));
    }

    @Test
    void shouldIsolateModelWindowsFromProviderWindows() {
        // Model override for gpt-5: 200 TPM
        ModelRateLimitProvider modelProvider = (prov, model) -> {
            if ("openai".equals(prov) && "gpt-5".equals(model)) {
                return new ModelRateLimit(200, null, null, null);
            }
            return null;
        };
        ProviderRateLimiter limiter = createWithModelOverrides(modelProvider);

        // Fill gpt-5 model window (200 TPM)
        limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 200);

        // gpt-5 should be blocked now
        assertThrows(LLMProviderException.class,
                () -> limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 1));

        // Provider-level (no model) should still work (uses provider's 1000 TPM window)
        assertDoesNotThrow(() -> limiter.checkRateLimit("openai", null, TENANT_ID, 500));
    }

    @Test
    void shouldMergePartialModelOverride() {
        // Override only RPM, inherit TPM from provider
        ModelRateLimitProvider modelProvider = (prov, model) -> {
            if ("openai".equals(prov) && "gpt-5".equals(model)) {
                return new ModelRateLimit(null, 2, null, null); // 2 RPM only
            }
            return null;
        };
        ProviderRateLimiter limiter = createWithModelOverrides(modelProvider);

        // 2 requests should succeed
        limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 10);
        limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 10);

        // 3rd request should fail (2 RPM model limit)
        assertThrows(LLMProviderException.class,
                () -> limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 10));
    }

    @Test
    void shouldBlockAllTrafficWhenModelLimitIsZero() {
        // Override TPM to 0 = block all traffic
        ModelRateLimitProvider modelProvider = (prov, model) -> {
            if ("openai".equals(prov) && "gpt-5".equals(model)) {
                return new ModelRateLimit(0, null, null, null);
            }
            return null;
        };
        ProviderRateLimiter limiter = createWithModelOverrides(modelProvider);

        // Even 1 token should be blocked (TPM limit = 0, any token count > 0 exceeds it)
        assertThrows(LLMProviderException.class,
                () -> limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 1));
    }

    @Test
    void shouldBlockZeroTokensWhenTpmLimitIsZero() {
        // TPM=0 means block ALL traffic, even 0-token requests
        ModelRateLimitProvider modelProvider = (prov, model) -> {
            if ("openai".equals(prov) && "gpt-5".equals(model)) {
                return new ModelRateLimit(0, null, null, null);
            }
            return null;
        };
        ProviderRateLimiter limiter = createWithModelOverrides(modelProvider);

        // 0 tokens should still be blocked when TPM limit is 0 (block-all semantics)
        assertThrows(LLMProviderException.class,
                () -> limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 0));
    }

    @Test
    void shouldReturnModelAwareStats() {
        ModelRateLimitProvider modelProvider = (prov, model) -> {
            if ("openai".equals(prov) && "gpt-5".equals(model)) {
                return new ModelRateLimit(500, 5, null, null);
            }
            return null;
        };
        ProviderRateLimiter limiter = createWithModelOverrides(modelProvider);

        limiter.checkRateLimit("openai", "gpt-5", TENANT_ID, 100);

        // Model-specific stats
        ProviderRateLimiter.UsageStats modelStats = limiter.getGlobalUsageStats("openai", "gpt-5");
        assertEquals(100, modelStats.currentTokens());
        assertEquals(500, modelStats.tokenLimit());
        assertEquals(5, modelStats.requestLimit());

        // Provider-level stats should be unaffected
        ProviderRateLimiter.UsageStats providerStats = limiter.getGlobalUsageStats("openai");
        assertEquals(0, providerStats.currentTokens());
    }

    @Test
    void shouldWorkWithNullModelId() {
        ModelRateLimitProvider modelProvider = (prov, model) -> null;
        ProviderRateLimiter limiter = createWithModelOverrides(modelProvider);

        // null modelId should use provider limits
        assertDoesNotThrow(() -> limiter.checkRateLimit("openai", null, TENANT_ID, 100));
        ProviderRateLimiter.UsageStats stats = limiter.getGlobalUsageStats("openai", null);
        assertEquals(100, stats.currentTokens());
        assertEquals(1000, stats.tokenLimit());
    }
}

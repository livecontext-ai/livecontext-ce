package com.apimarketplace.agent.integration;

import com.apimarketplace.agent.provider.LLMProviderException;
import com.apimarketplace.agent.ratelimit.ProviderRateLimiter;
import com.apimarketplace.agent.ratelimit.RateLimitConfig;
import com.apimarketplace.agent.ratelimit.RateLimitMode;
import com.apimarketplace.agent.ratelimit.RateLimitResult;
import com.apimarketplace.agent.ratelimit.RateLimitStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the ProviderRateLimiter.
 * Tests rate limiting strategies (GLOBAL, PER_TENANT, HYBRID),
 * modes (FAIL_FAST, WAIT, TRY_ACQUIRE), sliding window behavior,
 * usage stats, and concurrent access.
 *
 * No Spring context needed - directly instantiates with RateLimitConfig.
 */
@DisplayName("RateLimiterIntegrationTest - Rate limiting behavior")
class RateLimiterIntegrationTest {

    private RateLimitConfig config;
    private ProviderRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig();
        config.setEnabled(true);
        config.setStrategy(RateLimitStrategy.GLOBAL);
        config.setDefaultMode(RateLimitMode.FAIL_FAST);
        config.setMaxWaitTimeSeconds(5);

        // Configure a test provider with low limits for fast testing
        RateLimitConfig.ProviderLimit testLimit = new RateLimitConfig.ProviderLimit(
                1000,   // 1000 TPM global
                10,     // 10 RPM global
                500,    // 500 TPM per tenant
                5       // 5 RPM per tenant
        );
        config.setProviders(Map.of("test-provider", testLimit));

        rateLimiter = new ProviderRateLimiter(config);
    }

    // -------------------------------------------------------------------------
    // Disabled rate limiting
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Disabled rate limiting")
    class DisabledRateLimiting {

        @Test
        @DisplayName("should allow all requests when disabled")
        void shouldAllowAllWhenDisabled() {
            config.setEnabled(false);
            ProviderRateLimiter disabledLimiter = new ProviderRateLimiter(config);

            // Should not throw
            disabledLimiter.checkRateLimit("test-provider", "tenant-1", 5000);

            RateLimitResult result = disabledLimiter.tryAcquire("test-provider", "tenant-1", 5000);
            assertThat(result.isAllowed()).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // GLOBAL strategy
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GLOBAL strategy")
    class GlobalStrategy {

        @Test
        @DisplayName("should allow requests within global limits")
        void shouldAllowWithinLimits() {
            RateLimitResult result = rateLimiter.tryAcquire("test-provider", "tenant-1", 100);
            assertThat(result.isAllowed()).isTrue();
            assertThat(result.remainingCapacity()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should block when global token limit exceeded")
        void shouldBlockWhenTokenLimitExceeded() {
            // Use a provider with high RPM but low TPM to isolate token limit testing
            RateLimitConfig.ProviderLimit tokenOnlyLimit = new RateLimitConfig.ProviderLimit(
                    1000,   // 1000 TPM global
                    10000,  // 10000 RPM global (high, won't be hit)
                    500,    // 500 TPM per tenant
                    5000    // 5000 RPM per tenant (high, won't be hit)
            );
            config.setProviders(Map.of("token-test-provider", tokenOnlyLimit));
            rateLimiter = new ProviderRateLimiter(config);

            // Consume all tokens (10 requests * 100 tokens = 1000 TPM)
            for (int i = 0; i < 10; i++) {
                rateLimiter.tryAcquire("token-test-provider", "tenant-1", 100);
            }

            // Next request should be blocked by token limit (1000 tokens consumed, limit is 1000)
            RateLimitResult result = rateLimiter.tryAcquire("token-test-provider", "tenant-1", 100);
            assertThat(result.isBlocked()).isTrue();
            assertThat(result.reason()).contains("Global token limit");
            assertThat(result.waitTime()).isNotNull();
        }

        @Test
        @DisplayName("should block when global request limit exceeded")
        void shouldBlockWhenRequestLimitExceeded() {
            // Consume all request slots (limit is 10 RPM)
            for (int i = 0; i < 10; i++) {
                rateLimiter.tryAcquire("test-provider", "tenant-1", 1);
            }

            // Next request should be blocked
            RateLimitResult result = rateLimiter.tryAcquire("test-provider", "tenant-1", 1);
            assertThat(result.isBlocked()).isTrue();
            assertThat(result.reason()).contains("Global request limit");
        }

        @Test
        @DisplayName("should track usage across different tenants in GLOBAL mode")
        void shouldTrackUsageAcrossTenants() {
            // All tenants share global limits
            rateLimiter.tryAcquire("test-provider", "tenant-1", 400);
            rateLimiter.tryAcquire("test-provider", "tenant-2", 400);
            rateLimiter.tryAcquire("test-provider", "tenant-3", 200);

            // Global limit (1000 TPM) should now be exhausted
            RateLimitResult result = rateLimiter.tryAcquire("test-provider", "tenant-4", 100);
            assertThat(result.isBlocked()).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // PER_TENANT strategy
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("PER_TENANT strategy")
    class PerTenantStrategy {

        @BeforeEach
        void setUpPerTenant() {
            config.setStrategy(RateLimitStrategy.PER_TENANT);
            rateLimiter = new ProviderRateLimiter(config);
        }

        @Test
        @DisplayName("should enforce per-tenant limits independently")
        void shouldEnforcePerTenantLimitsIndependently() {
            // Exhaust tenant-1 limit (500 TPM per tenant)
            rateLimiter.tryAcquire("test-provider", "tenant-1", 500);

            // tenant-1 should be blocked
            RateLimitResult result1 = rateLimiter.tryAcquire("test-provider", "tenant-1", 100);
            assertThat(result1.isBlocked()).isTrue();
            assertThat(result1.reason()).contains("Tenant token limit");

            // tenant-2 should still be allowed
            RateLimitResult result2 = rateLimiter.tryAcquire("test-provider", "tenant-2", 100);
            assertThat(result2.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("should enforce per-tenant request limits")
        void shouldEnforcePerTenantRequestLimits() {
            // Exhaust tenant-1 request limit (5 RPM per tenant)
            for (int i = 0; i < 5; i++) {
                rateLimiter.tryAcquire("test-provider", "tenant-1", 1);
            }

            // tenant-1 should be blocked
            RateLimitResult result = rateLimiter.tryAcquire("test-provider", "tenant-1", 1);
            assertThat(result.isBlocked()).isTrue();
            assertThat(result.reason()).contains("Tenant request limit");
        }
    }

    // -------------------------------------------------------------------------
    // HYBRID strategy
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("HYBRID strategy")
    class HybridStrategy {

        @BeforeEach
        void setUpHybrid() {
            config.setStrategy(RateLimitStrategy.HYBRID);
            rateLimiter = new ProviderRateLimiter(config);
        }

        @Test
        @DisplayName("should enforce both global and per-tenant limits")
        void shouldEnforceBothLimits() {
            // Each tenant consumes tokens, staying under per-tenant limit (500)
            rateLimiter.tryAcquire("test-provider", "tenant-1", 400);
            rateLimiter.tryAcquire("test-provider", "tenant-2", 400);

            // tenant-3 tries 300 tokens: under per-tenant limit (500) but over global (1000)
            RateLimitResult result = rateLimiter.tryAcquire("test-provider", "tenant-3", 300);
            assertThat(result.isBlocked()).isTrue();
            assertThat(result.reason()).contains("Global");
        }

        @Test
        @DisplayName("should block by per-tenant limit when it is more restrictive")
        void shouldBlockByPerTenantLimit() {
            // tenant-1 tries to consume more than per-tenant limit
            rateLimiter.tryAcquire("test-provider", "tenant-1", 500);

            RateLimitResult result = rateLimiter.tryAcquire("test-provider", "tenant-1", 100);
            assertThat(result.isBlocked()).isTrue();
            assertThat(result.reason()).contains("Tenant");
        }
    }

    // -------------------------------------------------------------------------
    // FAIL_FAST mode
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("FAIL_FAST mode")
    class FailFastMode {

        @Test
        @DisplayName("should throw LLMProviderException when rate limit exceeded")
        void shouldThrowWhenExceeded() {
            // Exhaust limit (10 requests with RPM=10 triggers RPM check first)
            for (int i = 0; i < 10; i++) {
                rateLimiter.tryAcquire("test-provider", "tenant-1", 100);
            }

            assertThatThrownBy(() ->
                    rateLimiter.acquireOrFail("test-provider", "tenant-1", 100)
            ).isInstanceOf(LLMProviderException.class)
                    .hasMessageContaining("Global request limit");
        }

        @Test
        @DisplayName("should not throw when within limits")
        void shouldNotThrowWithinLimits() {
            // Should not throw
            rateLimiter.acquireOrFail("test-provider", "tenant-1", 50);
        }
    }

    // -------------------------------------------------------------------------
    // TRY_ACQUIRE mode
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("TRY_ACQUIRE mode")
    class TryAcquireMode {

        @Test
        @DisplayName("should return allowed result within limits")
        void shouldReturnAllowed() {
            RateLimitResult result = rateLimiter.tryAcquire("test-provider", "tenant-1", 50);

            assertThat(result.isAllowed()).isTrue();
            assertThat(result.isBlocked()).isFalse();
            assertThat(result.remainingCapacity()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should return blocked result with wait time when exceeded")
        void shouldReturnBlockedWithWaitTime() {
            // Exhaust limit
            for (int i = 0; i < 10; i++) {
                rateLimiter.tryAcquire("test-provider", "tenant-1", 100);
            }

            RateLimitResult result = rateLimiter.tryAcquire("test-provider", "tenant-1", 100);

            assertThat(result.isBlocked()).isTrue();
            assertThat(result.waitTime()).isGreaterThan(Duration.ZERO);
            assertThat(result.reason()).isNotNull();
            assertThat(result.errorCode()).isNotNull();
        }

        @Test
        @DisplayName("should track usage percentage")
        void shouldTrackUsagePercentage() {
            // Use half the tokens
            rateLimiter.tryAcquire("test-provider", "tenant-1", 500);

            RateLimitResult result = rateLimiter.tryAcquire("test-provider", "tenant-1", 100);
            assertThat(result.usagePercent()).isGreaterThan(0);
        }
    }

    // -------------------------------------------------------------------------
    // RateLimitResult utility methods
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("RateLimitResult utility methods")
    class RateLimitResultMethods {

        @Test
        @DisplayName("should detect warning level usage")
        void shouldDetectWarningLevel() {
            RateLimitResult warning = RateLimitResult.allowed(75.0, 100);
            assertThat(warning.isWarning()).isTrue();
            assertThat(warning.isCritical()).isFalse();
        }

        @Test
        @DisplayName("should detect critical level usage")
        void shouldDetectCriticalLevel() {
            RateLimitResult critical = RateLimitResult.allowed(95.0, 10);
            assertThat(critical.isCritical()).isTrue();
        }

        @Test
        @DisplayName("should not be warning or critical at low usage")
        void shouldNotBeWarningAtLowUsage() {
            RateLimitResult normal = RateLimitResult.allowed(30.0, 500);
            assertThat(normal.isWarning()).isFalse();
            assertThat(normal.isCritical()).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Usage stats
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Usage stats")
    class UsageStatsTests {

        @Test
        @DisplayName("should report global usage stats")
        void shouldReportGlobalStats() {
            rateLimiter.tryAcquire("test-provider", "tenant-1", 200);

            ProviderRateLimiter.UsageStats stats = rateLimiter.getGlobalUsageStats("test-provider");
            assertThat(stats.currentTokens()).isGreaterThan(0);
            assertThat(stats.tokenLimit()).isEqualTo(1000);
            assertThat(stats.currentRequests()).isGreaterThan(0);
            assertThat(stats.requestLimit()).isEqualTo(10);
        }

        @Test
        @DisplayName("should calculate usage percentages correctly")
        void shouldCalculatePercentages() {
            rateLimiter.tryAcquire("test-provider", "tenant-1", 500);

            ProviderRateLimiter.UsageStats stats = rateLimiter.getGlobalUsageStats("test-provider");
            assertThat(stats.getTokenUsagePercent()).isEqualTo(50.0);
            assertThat(stats.getRemainingTokens()).isEqualTo(500);
        }

        @Test
        @DisplayName("should report tenant usage stats")
        void shouldReportTenantStats() {
            config.setStrategy(RateLimitStrategy.PER_TENANT);
            rateLimiter = new ProviderRateLimiter(config);

            rateLimiter.tryAcquire("test-provider", "tenant-1", 100);

            ProviderRateLimiter.UsageStats stats = rateLimiter.getTenantUsageStats("test-provider", "tenant-1");
            assertThat(stats.currentTokens()).isEqualTo(100);
            assertThat(stats.tokenLimit()).isEqualTo(500);
        }

        @Test
        @DisplayName("should report zero stats for unused provider")
        void shouldReportZeroForUnused() {
            ProviderRateLimiter.UsageStats stats = rateLimiter.getGlobalUsageStats("unused-provider");
            assertThat(stats.currentTokens()).isEqualTo(0);
            assertThat(stats.currentRequests()).isEqualTo(0);
        }
    }

    // -------------------------------------------------------------------------
    // Default provider limits
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Default provider limits")
    class DefaultProviderLimits {

        @Test
        @DisplayName("should use default limits for unconfigured providers")
        void shouldUseDefaultLimits() {
            RateLimitConfig.ProviderLimit limit = config.getProviderLimit("unknown-provider");

            // Should get the default limits (very permissive)
            assertThat(limit.getTokensPerMinute()).isEqualTo(10_000_000);
            assertThat(limit.getRequestsPerMinute()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("should use configured limits for known providers")
        void shouldUseConfiguredLimits() {
            RateLimitConfig.ProviderLimit limit = config.getProviderLimit("test-provider");

            assertThat(limit.getTokensPerMinute()).isEqualTo(1000);
            assertThat(limit.getRequestsPerMinute()).isEqualTo(10);
            assertThat(limit.getTokensPerMinutePerTenant()).isEqualTo(500);
            assertThat(limit.getRequestsPerMinutePerTenant()).isEqualTo(5);
        }

        @Test
        @DisplayName("ProviderLimit should detect limit presence correctly")
        void shouldDetectLimitPresence() {
            RateLimitConfig.ProviderLimit withLimits = new RateLimitConfig.ProviderLimit(1000, 100, 500, 50);
            assertThat(withLimits.hasGlobalTokenLimit()).isTrue();
            assertThat(withLimits.hasGlobalRequestLimit()).isTrue();
            assertThat(withLimits.hasTenantTokenLimit()).isTrue();
            assertThat(withLimits.hasTenantRequestLimit()).isTrue();

            RateLimitConfig.ProviderLimit noLimits = new RateLimitConfig.ProviderLimit();
            assertThat(noLimits.hasGlobalTokenLimit()).isFalse();
            assertThat(noLimits.hasGlobalRequestLimit()).isFalse();
            assertThat(noLimits.hasTenantTokenLimit()).isFalse();
            assertThat(noLimits.hasTenantRequestLimit()).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Concurrent access
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Concurrent access")
    class ConcurrentAccess {

        @Test
        @DisplayName("should handle concurrent rate limit checks safely")
        void shouldHandleConcurrentChecks() throws InterruptedException {
            // Use high limits to avoid blocking during concurrency test
            RateLimitConfig.ProviderLimit highLimit = new RateLimitConfig.ProviderLimit(
                    1_000_000, 100_000, 100_000, 10_000);
            config.setProviders(Map.of("concurrent-provider", highLimit));
            rateLimiter = new ProviderRateLimiter(config);

            int threadCount = 10;
            int requestsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);
            AtomicInteger allowed = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < requestsPerThread; i++) {
                            RateLimitResult result = rateLimiter.tryAcquire(
                                    "concurrent-provider",
                                    "tenant-" + threadId,
                                    10
                            );
                            if (result.isAllowed()) {
                                allowed.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            assertThat(errors.get()).isZero();
            assertThat(allowed.get()).isGreaterThan(0);
        }
    }

    // -------------------------------------------------------------------------
    // RateLimitConfig
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("RateLimitConfig defaults")
    class ConfigDefaults {

        @Test
        @DisplayName("should have correct default values")
        void shouldHaveCorrectDefaults() {
            RateLimitConfig defaultConfig = new RateLimitConfig();

            assertThat(defaultConfig.isEnabled()).isTrue();
            assertThat(defaultConfig.getStrategy()).isEqualTo(RateLimitStrategy.GLOBAL);
            assertThat(defaultConfig.getDefaultMode()).isEqualTo(RateLimitMode.WAIT);
            assertThat(defaultConfig.getMaxWaitTimeSeconds()).isEqualTo(60);
        }

        @Test
        @DisplayName("should have correct default provider limits")
        void shouldHaveCorrectDefaultLimits() {
            RateLimitConfig defaultConfig = new RateLimitConfig();
            RateLimitConfig.ProviderLimit defaults = defaultConfig.getProviderLimit("any-provider");

            assertThat(defaults.getTokensPerMinute()).isEqualTo(10_000_000);
            assertThat(defaults.getRequestsPerMinute()).isEqualTo(10_000);
            assertThat(defaults.getTokensPerMinutePerTenant()).isEqualTo(1_000_000);
            assertThat(defaults.getRequestsPerMinutePerTenant()).isEqualTo(1_000);
        }
    }

    // -------------------------------------------------------------------------
    // Mode configuration
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Mode configuration")
    class ModeConfiguration {

        @Test
        @DisplayName("should allow changing default mode")
        void shouldAllowChangingMode() {
            rateLimiter.setDefaultMode(RateLimitMode.TRY_ACQUIRE);

            // Exhaust limit
            for (int i = 0; i < 10; i++) {
                rateLimiter.tryAcquire("test-provider", "tenant-1", 100);
            }

            // With TRY_ACQUIRE mode via checkRateLimit, should throw since fallback throws
            assertThatThrownBy(() ->
                    rateLimiter.checkRateLimit("test-provider", "tenant-1", 100)
            ).isInstanceOf(LLMProviderException.class);
        }

        @Test
        @DisplayName("should allow changing max wait time")
        void shouldAllowChangingMaxWaitTime() {
            rateLimiter.setMaxWaitTime(Duration.ofSeconds(1));

            // Should not throw errors on configuration change
            RateLimitResult result = rateLimiter.tryAcquire("test-provider", "tenant-1", 50);
            assertThat(result.isAllowed()).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Record usage
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Record usage")
    class RecordUsage {

        @Test
        @DisplayName("should accept recordRequest without error")
        void shouldAcceptRecordRequest() {
            rateLimiter.tryAcquire("test-provider", "tenant-1", 100);

            // Should not throw
            rateLimiter.recordRequest("test-provider", "tenant-1", 150);
        }

        @Test
        @DisplayName("should handle recordRequest when disabled")
        void shouldHandleRecordWhenDisabled() {
            config.setEnabled(false);
            ProviderRateLimiter disabledLimiter = new ProviderRateLimiter(config);

            // Should not throw
            disabledLimiter.recordRequest("test-provider", "tenant-1", 100);
        }
    }
}

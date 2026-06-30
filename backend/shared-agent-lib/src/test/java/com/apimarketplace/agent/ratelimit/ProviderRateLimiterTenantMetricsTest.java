package com.apimarketplace.agent.ratelimit;

import com.apimarketplace.agent.provider.LLMProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-tenant counter/metrics behavior of ProviderRateLimiter.
 *
 * Complements {@link ProviderRateLimiterTest} (which covers accept/block semantics)
 * with the tenant-dimensioned counters that back {@code rate_limit_*{tenant=...}}
 * Prometheus series.
 */
class ProviderRateLimiterTenantMetricsTest {

    private RateLimitConfig config;
    private ProviderRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig();
        config.setEnabled(true);
        config.setStrategy(RateLimitStrategy.HYBRID);
        config.setDefaultMode(RateLimitMode.FAIL_FAST);

        RateLimitConfig.ProviderLimit limit = new RateLimitConfig.ProviderLimit();
        limit.setTokensPerMinute(10_000);
        limit.setRequestsPerMinute(100);
        limit.setTokensPerMinutePerTenant(1_000);
        limit.setRequestsPerMinutePerTenant(3);
        config.getProviders().put("openai", limit);

        rateLimiter = new ProviderRateLimiter(config);
    }

    private ProviderRateLimiter.TenantWindowMetrics forTenant(
            List<ProviderRateLimiter.TenantWindowMetrics> all, String tenantId) {
        return all.stream()
                .filter(m -> tenantId.equals(m.tenant()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tenant " + tenantId + " not in " + all));
    }

    @Test
    @DisplayName("Acquires attribute to the calling tenant (no cross-tenant leakage)")
    void acquiresAttributeToCallingTenant() {
        rateLimiter.checkRateLimit("openai", "tenant-a", 100);
        rateLimiter.checkRateLimit("openai", "tenant-a", 100);
        rateLimiter.checkRateLimit("openai", "tenant-b", 100);

        List<ProviderRateLimiter.TenantWindowMetrics> tenantMetrics =
                rateLimiter.getAllTenantWindowMetrics();

        Map<String, Long> acquiredByTenant = new java.util.HashMap<>();
        for (var m : tenantMetrics) {
            acquiredByTenant.merge(m.tenant(), m.acquiredTotal(), Long::sum);
        }
        assertEquals(2L, acquiredByTenant.get("tenant-a"), "tenant-a should own exactly 2 acquires");
        assertEquals(1L, acquiredByTenant.get("tenant-b"), "tenant-b should own exactly 1 acquire");
    }

    @Test
    @DisplayName("Blocked requests are attributed to the tenant that hit the cap")
    void blockedAttributesToCappedTenant() {
        // Use up the 3 RPM per-tenant cap for tenant-a
        for (int i = 0; i < 3; i++) {
            rateLimiter.checkRateLimit("openai", "tenant-a", 10);
        }
        // 4th request should be blocked for tenant-a only
        assertThrows(LLMProviderException.class,
                () -> rateLimiter.checkRateLimit("openai", "tenant-a", 10));

        // tenant-b should still be within its own cap
        rateLimiter.checkRateLimit("openai", "tenant-b", 10);

        var tenantA = forTenant(rateLimiter.getAllTenantWindowMetrics(), "tenant-a");
        var tenantB = forTenant(rateLimiter.getAllTenantWindowMetrics(), "tenant-b");

        assertEquals(3L, tenantA.acquiredTotal());
        assertEquals(1L, tenantA.blockedTotal(), "tenant-a should own the single block");
        assertEquals(1L, tenantB.acquiredTotal());
        assertEquals(0L, tenantB.blockedTotal(), "tenant-b must not inherit tenant-a's block");
    }

    @Test
    @DisplayName("Token consumption counter tracks per-tenant reservations")
    void tokenConsumptionAttributesPerTenant() {
        rateLimiter.checkRateLimit("openai", "tenant-a", 250);
        rateLimiter.checkRateLimit("openai", "tenant-a", 150);
        rateLimiter.checkRateLimit("openai", "tenant-b", 75);

        var tenantA = forTenant(rateLimiter.getAllTenantWindowMetrics(), "tenant-a");
        var tenantB = forTenant(rateLimiter.getAllTenantWindowMetrics(), "tenant-b");

        assertEquals(400L, tenantA.tokensConsumedTotal());
        assertEquals(75L, tenantB.tokensConsumedTotal());
    }

    @Test
    @DisplayName("Per-tenant RPM/TPM gauges reflect the caller's own rolling window")
    void rpmTpmGaugesAreScopedPerTenant() {
        rateLimiter.checkRateLimit("openai", "tenant-a", 300);
        rateLimiter.checkRateLimit("openai", "tenant-a", 200);
        rateLimiter.checkRateLimit("openai", "tenant-b", 50);

        var tenantA = forTenant(rateLimiter.getAllTenantWindowMetrics(), "tenant-a");
        var tenantB = forTenant(rateLimiter.getAllTenantWindowMetrics(), "tenant-b");

        assertEquals(2, tenantA.currentRpm());
        assertEquals(500, tenantA.currentTpm());
        assertEquals(3, tenantA.rpmLimit(), "per-tenant cap exposed, not global cap");
        assertEquals(1000, tenantA.tpmLimit());

        assertEquals(1, tenantB.currentRpm());
        assertEquals(50, tenantB.currentTpm());
    }

    @Test
    @DisplayName("Null tenantId: aggregate counters move but no per-tenant series is emitted")
    void nullTenantIdSkipsTenantCounters() {
        rateLimiter.checkRateLimit("openai", (String) null, 100);

        assertTrue(rateLimiter.getAllTenantWindowMetrics().isEmpty(),
                "no tenant series should exist when tenantId is null");
        // Aggregate series still increments - verifies global bookkeeping is untouched
        var aggregate = rateLimiter.getAllWindowMetrics().stream().findFirst().orElseThrow();
        assertEquals(1L, aggregate.acquiredTotal());
    }

    @Test
    @DisplayName("TRY_ACQUIRE mode blocks attribute to the tenant that was rejected")
    void tryAcquireBlockedIsPerTenant() {
        // saturate tenant-a
        for (int i = 0; i < 3; i++) {
            rateLimiter.checkRateLimit("openai", "tenant-a", 10);
        }
        // next one should throw from TRY_ACQUIRE mode and bump blocked counter
        assertThrows(LLMProviderException.class,
                () -> rateLimiter.checkRateLimit("openai", null, "tenant-a", 10, RateLimitMode.TRY_ACQUIRE));

        var tenantA = forTenant(rateLimiter.getAllTenantWindowMetrics(), "tenant-a");
        assertEquals(1L, tenantA.blockedTotal());
    }

    @Test
    @DisplayName("Model override: tenant series key includes provider:model, not just provider")
    void tenantSeriesAreScopedToProviderModel() {
        var modelProvider = new ModelRateLimitProvider() {
            @Override
            public ModelRateLimit getModelLimit(String providerName, String modelId) {
                if ("openai".equals(providerName) && "gpt-4o".equals(modelId)) {
                    return new ModelRateLimit(5_000, 50, 500, 2);
                }
                return null;
            }
        };
        rateLimiter = new ProviderRateLimiter(config, null, modelProvider);

        rateLimiter.checkRateLimit("openai", "gpt-4o", "tenant-a", 100);
        rateLimiter.checkRateLimit("openai", "gpt-4o", "tenant-a", 100);

        var series = rateLimiter.getAllTenantWindowMetrics();
        assertFalse(series.isEmpty());
        var entry = series.get(0);
        assertEquals("openai:gpt-4o", entry.windowKey());
        assertEquals("openai", entry.provider());
        assertEquals("gpt-4o", entry.model());
        assertEquals("tenant-a", entry.tenant());
        assertEquals(2, entry.rpmLimit(), "per-tenant model override should surface in the series");
    }
}

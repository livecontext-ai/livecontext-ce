package com.apimarketplace.agent.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Publisher-layer test: verifies {@link RateLimitMetrics} emits both an aggregate
 * series ({@code tenant="all"}) AND a per-tenant series for each active caller,
 * under a single {@code rate_limit_*} metric name.
 */
class RateLimitMetricsTest {

    private RateLimitConfig config;
    private ProviderRateLimiter rateLimiter;
    private SimpleMeterRegistry registry;
    private RateLimitMetrics publisher;

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
        registry = new SimpleMeterRegistry();
        publisher = new RateLimitMetrics(rateLimiter, registry);
    }

    private List<Counter> counters(String name) {
        return registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(name))
                .filter(m -> m instanceof Counter)
                .map(m -> (Counter) m)
                .collect(Collectors.toList());
    }

    private List<Gauge> gauges(String name) {
        return registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(name))
                .filter(m -> m instanceof Gauge)
                .map(m -> (Gauge) m)
                .collect(Collectors.toList());
    }

    private Counter counterFor(String name, String tenant) {
        return counters(name).stream()
                .filter(c -> tenant.equals(c.getId().getTag("tenant")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        name + "{tenant=" + tenant + "} not registered. Present: "
                                + counters(name).stream().map(Meter::getId).toList()));
    }

    private Gauge gaugeFor(String name, String tenant) {
        return gauges(name).stream()
                .filter(g -> tenant.equals(g.getId().getTag("tenant")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        name + "{tenant=" + tenant + "} not registered"));
    }

    @Test
    @DisplayName("Publishes aggregate (tenant=all) AND per-tenant series for rate_limit_acquired_total")
    void emitsAggregateAndPerTenantAcquired() {
        rateLimiter.checkRateLimit("openai", "tenant-a", 100);
        rateLimiter.checkRateLimit("openai", "tenant-a", 100);
        rateLimiter.checkRateLimit("openai", "tenant-b", 100);

        publisher.refreshMetrics();

        Counter aggregate = counterFor("rate_limit_acquired_total", "all");
        Counter tenantA = counterFor("rate_limit_acquired_total", "tenant-a");
        Counter tenantB = counterFor("rate_limit_acquired_total", "tenant-b");

        assertEquals(3.0, aggregate.count(), "aggregate counts all acquires");
        assertEquals(2.0, tenantA.count());
        assertEquals(1.0, tenantB.count());
    }

    @Test
    @DisplayName("Counter tags include provider/model/tenant (no collisions across tags)")
    void countersCarryFullTagSet() {
        rateLimiter.checkRateLimit("openai", "tenant-x", 50);
        publisher.refreshMetrics();

        Counter c = counterFor("rate_limit_acquired_total", "tenant-x");
        assertEquals("openai", c.getId().getTag("provider"));
        assertEquals("all", c.getId().getTag("model")); // no model override → "all"
        assertEquals("tenant-x", c.getId().getTag("tenant"));
    }

    @Test
    @DisplayName("Delta tracking: second refresh only applies the new delta, not the cumulative total")
    void deltaTrackingAcrossRefreshes() {
        rateLimiter.checkRateLimit("openai", "tenant-a", 50);
        publisher.refreshMetrics();

        rateLimiter.checkRateLimit("openai", "tenant-a", 50);
        rateLimiter.checkRateLimit("openai", "tenant-a", 50);
        publisher.refreshMetrics();

        assertEquals(3.0, counterFor("rate_limit_acquired_total", "tenant-a").count(),
                "counter must reflect the 3 total acquires, not 1 + 3 = 4");
    }

    @Test
    @DisplayName("Blocked counter series are split per tenant (isolation proof)")
    void blockedSplitsPerTenant() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.checkRateLimit("openai", "noisy", 10);
        }
        assertThrows(Exception.class, () -> rateLimiter.checkRateLimit("openai", "noisy", 10));
        rateLimiter.checkRateLimit("openai", "quiet", 10);

        publisher.refreshMetrics();

        assertEquals(1.0, counterFor("rate_limit_blocked_total", "noisy").count());
        List<Counter> quietBlocked = counters("rate_limit_blocked_total").stream()
                .filter(c -> "quiet".equals(c.getId().getTag("tenant")))
                .toList();
        assertTrue(quietBlocked.isEmpty() || quietBlocked.get(0).count() == 0.0,
                "quiet tenant must not accumulate blocks");
    }

    @Test
    @DisplayName("Per-tenant RPM gauge reflects per-tenant cap, not the global cap")
    void rpmGaugeExposesTenantCap() {
        rateLimiter.checkRateLimit("openai", "tenant-a", 100);
        rateLimiter.checkRateLimit("openai", "tenant-a", 100);
        publisher.refreshMetrics();

        Gauge current = gaugeFor("rate_limit_rpm_current", "tenant-a");
        Gauge limit = gaugeFor("rate_limit_rpm_limit", "tenant-a");

        assertEquals(2.0, current.value());
        assertEquals(3.0, limit.value(), "per-tenant tag should expose the per-tenant cap");

        Gauge aggregateLimit = gaugeFor("rate_limit_rpm_limit", "all");
        assertEquals(100.0, aggregateLimit.value(), "tenant=all should expose the global cap");
    }

    @Test
    @DisplayName("No per-tenant series is emitted for null tenantId callers")
    void nullTenantIdDoesNotPublishTenantSeries() {
        rateLimiter.checkRateLimit("openai", (String) null, 100);
        publisher.refreshMetrics();

        // Aggregate is still there
        assertEquals(1.0, counterFor("rate_limit_acquired_total", "all").count());
        // No non-aggregate tenant series
        long nonAggregate = counters("rate_limit_acquired_total").stream()
                .filter(c -> !"all".equals(c.getId().getTag("tenant")))
                .count();
        assertEquals(0, nonAggregate);
    }

    @Test
    @DisplayName("All rate_limit_* counters share the same tag schema (no Micrometer conflict)")
    void allCountersShareTagSchema() {
        // Drive each counter path so that every metric name has at least one series:
        // - acquired/tokens_consumed via successful acquires
        // - blocked via over-cap rejection
        for (int i = 0; i < 3; i++) {
            rateLimiter.checkRateLimit("openai", "tenant-a", 100);
        }
        assertThrows(Exception.class, () -> rateLimiter.checkRateLimit("openai", "tenant-a", 100));
        publisher.refreshMetrics();

        // All 5 counter names: acquired, blocked, timeout, wait_ms, tokens_consumed.
        // timeout/wait_ms may have zero series in a single refresh (no path triggers
        // them here), so we check tag-schema consistency on ANY counter that exists,
        // not presence. The key invariant under test: whenever a counter is registered,
        // it carries the full (provider, model, tenant) tag set, so Micrometer will
        // never throw IllegalArgumentException on schema drift.
        List<String> metricNames = List.of(
                "rate_limit_acquired_total",
                "rate_limit_blocked_total",
                "rate_limit_timeout_total",
                "rate_limit_wait_ms_total",
                "rate_limit_tokens_consumed_total"
        );
        int totalInspected = 0;
        for (String name : metricNames) {
            for (Counter c : counters(name)) {
                assertNotNull(c.getId().getTag("provider"), name + " missing provider tag");
                assertNotNull(c.getId().getTag("model"), name + " missing model tag");
                assertNotNull(c.getId().getTag("tenant"), name + " missing tenant tag");
                totalInspected++;
            }
        }
        // acquired (aggregate + tenant-a) + blocked (aggregate + tenant-a) +
        // tokens_consumed (aggregate + tenant-a) = 6 counters minimum
        assertTrue(totalInspected >= 6,
                "expected at least 6 counters across acquired/blocked/tokens_consumed, saw " + totalInspected);
    }

    @Test
    @DisplayName("Stale gauges zero out *_current AND *_limit when a series disappears")
    void staleGaugesZeroBothCurrentAndLimit() throws Exception {
        rateLimiter.checkRateLimit("openai", "tenant-a", 100);
        publisher.refreshMetrics();

        // Sanity: all 4 gauges registered for tenant-a with non-zero values
        assertEquals(1.0, gaugeFor("rate_limit_rpm_current", "tenant-a").value());
        assertEquals(3.0, gaugeFor("rate_limit_rpm_limit", "tenant-a").value());
        assertEquals(100.0, gaugeFor("rate_limit_tpm_current", "tenant-a").value());
        assertEquals(1000.0, gaugeFor("rate_limit_tpm_limit", "tenant-a").value());

        // Simulate the scheduled cleanup having pruned tenant-a by clearing the
        // internal tenant-dimensioned state directly. This is the cheapest way to
        // put the publisher into the "series disappeared" regime without waiting
        // for the 5-minute cleanup schedule.
        clearTenantInternals(rateLimiter);
        publisher.refreshMetrics();

        // All 4 gauges must be zeroed - leaving *_limit frozen would cause Prometheus
        // queries like rate_limit_rpm_current / rate_limit_rpm_limit to report false
        // utilization on a series that no longer has any live traffic.
        assertEquals(0.0, gaugeFor("rate_limit_rpm_current", "tenant-a").value(),
                "rpm_current must zero on stale series");
        assertEquals(0.0, gaugeFor("rate_limit_rpm_limit", "tenant-a").value(),
                "rpm_limit must zero on stale series - bug would freeze at 3.0 forever");
        assertEquals(0.0, gaugeFor("rate_limit_tpm_current", "tenant-a").value(),
                "tpm_current must zero on stale series");
        assertEquals(0.0, gaugeFor("rate_limit_tpm_limit", "tenant-a").value(),
                "tpm_limit must zero on stale series - bug would freeze at 1000.0 forever");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void clearTenantInternals(ProviderRateLimiter rl) throws Exception {
        for (String field : List.of(
                "tenantRequestWindows", "tenantTokenWindows",
                "tenantAcquiredCounters", "tenantBlockedCounters",
                "tenantTimeoutCounters", "tenantWaitMsCounters",
                "tenantTokensConsumedCounters")) {
            var f = ProviderRateLimiter.class.getDeclaredField(field);
            f.setAccessible(true);
            ((java.util.Map) f.get(rl)).clear();
        }
    }
}

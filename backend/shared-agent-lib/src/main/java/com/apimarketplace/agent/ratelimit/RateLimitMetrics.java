package com.apimarketplace.agent.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes rate limiting metrics to Prometheus via Micrometer.
 *
 * <p>Auto-activates in any service that has both a {@link ProviderRateLimiter} bean
 * and a {@link MeterRegistry} on the classpath (i.e. any Spring Boot service with
 * {@code spring-boot-starter-actuator}).</p>
 *
 * <p>Scrapes {@link ProviderRateLimiter#getAllWindowMetrics()} every 15 seconds
 * and publishes gauges (instantaneous) + counters (monotonic) per provider/model.</p>
 *
 * <p>Gauges (instantaneous, sampled every 15s):
 * <ul>
 *   <li>{@code rate_limit_rpm_current} - requests in the 60s sliding window</li>
 *   <li>{@code rate_limit_rpm_limit} - configured RPM limit</li>
 *   <li>{@code rate_limit_tpm_current} - tokens in the 60s sliding window</li>
 *   <li>{@code rate_limit_tpm_limit} - configured TPM limit</li>
 * </ul>
 *
 * <p>Counters (monotonic, supports Prometheus {@code rate()} / {@code increase()}):
 * <ul>
 *   <li>{@code rate_limit_acquired_total} - requests that passed rate limiting</li>
 *   <li>{@code rate_limit_blocked_total} - unique requests that were delayed (not retry attempts)</li>
 *   <li>{@code rate_limit_timeout_total} - requests that timed out waiting</li>
 *   <li>{@code rate_limit_wait_ms_total} - cumulative wait time in milliseconds</li>
 *   <li>{@code rate_limit_tokens_consumed_total} - tokens reserved through rate limiter</li>
 * </ul>
 *
 * <p>All metrics tagged with {@code provider}, {@code model}, and {@code tenant}.
 * The aggregate series (sum across tenants) uses the sentinel {@code tenant="all"};
 * per-tenant breakdowns use the real tenantId. Query aggregate with
 * {@code rate_limit_acquired_total{tenant="all"}}; query per-tenant with
 * {@code rate_limit_acquired_total{tenant!="all"}}. Model cardinality is bounded
 * (~25 models); tenant cardinality is bounded by active tenants (counters for
 * idle tenants are purged by {@link ProviderRateLimiter#cleanupInactiveTenants()}).</p>
 *
 * <p><b>Reserved tenantId:</b> the literal string {@code "all"} is reserved as the
 * aggregate sentinel. A caller passing {@code tenantId="all"} would collide on the
 * same {@code seriesKey} as the aggregate series and overwrite its gauges. Callers
 * MUST avoid the literal {@code "all"} as a tenantId - in practice user IDs are
 * UUIDs or email-derived keys, so this is a theoretical concern.</p>
 */
@Slf4j
@Component
@ConditionalOnClass(MeterRegistry.class)
public class RateLimitMetrics {

    private final ProviderRateLimiter rateLimiter;
    private final MeterRegistry registry;

    // Gauge holders for instantaneous values (Micrometer reads on scrape)
    private final Map<String, AtomicLong> rpmCurrentValues = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> rpmLimitValues = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> tpmCurrentValues = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> tpmLimitValues = new ConcurrentHashMap<>();

    // Counter instances for monotonic event counts
    private final Map<String, Counter> acquiredCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> blockedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> timeoutCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> waitMsCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> tokensCounters = new ConcurrentHashMap<>();

    // Track last-seen values from ProviderRateLimiter to compute deltas for Counters
    private final Map<String, long[]> lastSeenCounters = new ConcurrentHashMap<>();

    public RateLimitMetrics(ProviderRateLimiter rateLimiter, MeterRegistry registry) {
        this.rateLimiter = rateLimiter;
        this.registry = registry;
        log.info("RateLimitMetrics initialized - registry={}, rateLimiter.enabled={}, activeWindows={}",
                registry.getClass().getSimpleName(),
                rateLimiter.getAllWindowMetrics().size(),
                rateLimiter.getActiveWindowKeys().size());

        // Diagnostic: register a sentinel gauge to verify Micrometer→Prometheus works
        Gauge.builder("rate_limit_metrics_active", () -> 1.0)
                .description("Sentinel: 1 if RateLimitMetrics bean is alive")
                .register(registry);
    }

    private static final String TENANT_AGGREGATE = "all";

    @Scheduled(fixedRate = 15000)
    public void refreshMetrics() {
        try {
            List<ProviderRateLimiter.WindowMetrics> metrics = rateLimiter.getAllWindowMetrics();
            List<ProviderRateLimiter.TenantWindowMetrics> tenantMetrics = rateLimiter.getAllTenantWindowMetrics();
            if (!metrics.isEmpty() || !tenantMetrics.isEmpty()) {
                log.info("Rate limit metrics tick: {} active windows, {} per-tenant series",
                        metrics.size(), tenantMetrics.size());
            } else {
                log.debug("Rate limit metrics tick: 0 active windows");
            }

            Set<String> activeSeriesKeys = new HashSet<>();

            // Aggregate series (sum across tenants) - published with tenant="all"
            for (ProviderRateLimiter.WindowMetrics m : metrics) {
                String seriesKey = m.windowKey() + "|" + TENANT_AGGREGATE;
                activeSeriesKeys.add(seriesKey);
                Tags tags = Tags.of("provider", m.provider(), "model", m.model(),
                        "tenant", TENANT_AGGREGATE);

                getOrRegisterGauge(rpmCurrentValues, "rate_limit_rpm_current",
                        "Current requests in the 60s sliding window", tags, seriesKey)
                        .set(m.currentRpm());
                getOrRegisterGauge(rpmLimitValues, "rate_limit_rpm_limit",
                        "Configured RPM limit", tags, seriesKey)
                        .set(m.rpmLimit());
                getOrRegisterGauge(tpmCurrentValues, "rate_limit_tpm_current",
                        "Current tokens in the 60s sliding window", tags, seriesKey)
                        .set(m.currentTpm());
                getOrRegisterGauge(tpmLimitValues, "rate_limit_tpm_limit",
                        "Configured TPM limit", tags, seriesKey)
                        .set(m.tpmLimit());

                long[] current = {m.acquiredTotal(), m.blockedTotal(), m.timeoutTotal(),
                                  m.waitMsTotal(), m.tokensConsumedTotal()};
                incrementCountersFromDelta(seriesKey, tags, current);
            }

            // Per-tenant series - same metric names, tenant=<id> tag. RPM/TPM gauges
            // here reflect the tenant's own rolling window (not the global aggregate).
            for (ProviderRateLimiter.TenantWindowMetrics m : tenantMetrics) {
                String seriesKey = m.windowKey() + "|" + m.tenant();
                activeSeriesKeys.add(seriesKey);
                Tags tags = Tags.of("provider", m.provider(), "model", m.model(),
                        "tenant", m.tenant());

                getOrRegisterGauge(rpmCurrentValues, "rate_limit_rpm_current",
                        "Current requests in the 60s sliding window", tags, seriesKey)
                        .set(m.currentRpm());
                getOrRegisterGauge(rpmLimitValues, "rate_limit_rpm_limit",
                        "Configured RPM limit (per-tenant cap when tenant!=all)", tags, seriesKey)
                        .set(m.rpmLimit());
                getOrRegisterGauge(tpmCurrentValues, "rate_limit_tpm_current",
                        "Current tokens in the 60s sliding window", tags, seriesKey)
                        .set(m.currentTpm());
                getOrRegisterGauge(tpmLimitValues, "rate_limit_tpm_limit",
                        "Configured TPM limit (per-tenant cap when tenant!=all)", tags, seriesKey)
                        .set(m.tpmLimit());

                long[] current = {m.acquiredTotal(), m.blockedTotal(), m.timeoutTotal(),
                                  m.waitMsTotal(), m.tokensConsumedTotal()};
                incrementCountersFromDelta(seriesKey, tags, current);
            }

            // Zero out gauges for series that disappeared (cleaned-up model / tenant windows)
            zeroStaleGauges(activeSeriesKeys);
            // Forget deltas for series we no longer see - next time they come back we
            // start from a fresh baseline instead of double-counting historical totals.
            lastSeenCounters.keySet().retainAll(activeSeriesKeys);
        } catch (Exception e) {
            log.warn("Failed to refresh rate limit metrics: {}", e.getMessage());
        }
    }

    private void incrementCountersFromDelta(String seriesKey, Tags tags, long[] current) {
        long[] prev = lastSeenCounters.getOrDefault(seriesKey, new long[5]);
        for (int i = 0; i < 5; i++) {
            // Counter reset guard: if current < prev, the source AtomicLong was cleaned up
            // and recreated - treat current as the full delta. With retainAll() below this
            // should not fire for a continuously-active series (baseline is dropped when
            // the series disappears), so if it triggers we log it for diagnosis.
            if (current[i] < prev[i]) {
                log.warn("Rate-limit counter reset detected for series {} index {}: {} -> {}. "
                                + "If this recurs without a known cleanup, investigate source state.",
                        seriesKey, i, prev[i], current[i]);
            }
            double delta = current[i] < prev[i] ? current[i] : current[i] - prev[i];
            if (delta > 0) {
                switch (i) {
                    case 0 -> getOrRegisterCounter(acquiredCounters, "rate_limit_acquired_total",
                            "Requests that passed rate limiting", tags, seriesKey).increment(delta);
                    case 1 -> getOrRegisterCounter(blockedCounters, "rate_limit_blocked_total",
                            "Unique requests delayed by rate limiting", tags, seriesKey).increment(delta);
                    case 2 -> getOrRegisterCounter(timeoutCounters, "rate_limit_timeout_total",
                            "Requests that timed out waiting", tags, seriesKey).increment(delta);
                    case 3 -> getOrRegisterCounter(waitMsCounters, "rate_limit_wait_ms_total",
                            "Cumulative wait time in milliseconds", tags, seriesKey).increment(delta);
                    case 4 -> getOrRegisterCounter(tokensCounters, "rate_limit_tokens_consumed_total",
                            "Tokens reserved through rate limiter", tags, seriesKey).increment(delta);
                }
            }
        }
        lastSeenCounters.put(seriesKey, current);
    }

    private void zeroStaleGauges(Set<String> activeSeriesKeys) {
        // Zero all 4 gauge maps: *_current AND *_limit. Leaving *_limit frozen at
        // the last observed cap would make derived queries like
        // rate_limit_rpm_current / rate_limit_rpm_limit report false utilization
        // on series that have disappeared (cleaned-up model or idle tenant).
        zeroStaleGaugeMap(rpmCurrentValues, "rate_limit_rpm_current", activeSeriesKeys);
        zeroStaleGaugeMap(rpmLimitValues, "rate_limit_rpm_limit", activeSeriesKeys);
        zeroStaleGaugeMap(tpmCurrentValues, "rate_limit_tpm_current", activeSeriesKeys);
        zeroStaleGaugeMap(tpmLimitValues, "rate_limit_tpm_limit", activeSeriesKeys);
    }

    private void zeroStaleGaugeMap(Map<String, AtomicLong> map, String prefix, Set<String> activeSeriesKeys) {
        for (Map.Entry<String, AtomicLong> entry : map.entrySet()) {
            String gaugeKey = entry.getKey();
            String seriesKey = gaugeKey.substring(prefix.length() + 1);
            if (!activeSeriesKeys.contains(seriesKey)) {
                entry.getValue().set(0);
            }
        }
    }

    private AtomicLong getOrRegisterGauge(Map<String, AtomicLong> map, String name,
                                           String description, Tags tags, String seriesKey) {
        String gaugeKey = name + ":" + seriesKey;
        return map.computeIfAbsent(gaugeKey, k -> {
            AtomicLong value = new AtomicLong(0);
            Gauge.builder(name, value, AtomicLong::doubleValue)
                    .tags(tags)
                    .description(description)
                    .register(registry);
            return value;
        });
    }

    private Counter getOrRegisterCounter(Map<String, Counter> map, String name,
                                          String description, Tags tags, String seriesKey) {
        String counterKey = name + ":" + seriesKey;
        return map.computeIfAbsent(counterKey, k ->
                Counter.builder(name)
                        .tags(tags)
                        .description(description)
                        .register(registry));
    }
}

package com.apimarketplace.common.credit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client that caches the full pricing snapshot from auth-service.
 *
 * <p>Fetches {@code GET /api/internal/auth/pricing/snapshot} and caches for
 * a configurable TTL (default 5 minutes). Consumers look up rates by
 * {@code (provider, model)} pair.</p>
 *
 * <p>This is the <strong>single source of truth</strong> for per-model rates
 * in any service that needs cost estimation (budget guards, cost projections).
 * Auth-service DB is the ultimate authority; this client mirrors it locally
 * to avoid per-iteration HTTP round trips.</p>
 *
 * <p><strong>No fallback defaults.</strong> If a model is not found in the
 * snapshot (and the snapshot itself loaded successfully), {@link #getRates}
 * returns {@link Optional#empty()}. Callers must decide how to handle the
 * absence explicitly, rather than silently using stale hardcoded rates.</p>
 */
public class PricingSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(PricingSnapshotClient.class);

    private static final Duration DEFAULT_REFRESH_TTL = Duration.ofMinutes(5);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
        new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;
    private final String snapshotUrl;
    private final Duration refreshTtl;

    /**
     * Minimum interval between refresh attempts after a failure. Prevents hammering
     * auth-service when it is down (without this, every {@code getRates()} call
     * would trigger an HTTP round trip).
     */
    private static final Duration MIN_RETRY_INTERVAL = Duration.ofSeconds(30);

    /** Cached rates keyed by "provider::model" (lowercased). */
    private volatile ConcurrentHashMap<String, PricingRates> cache = new ConcurrentHashMap<>();
    private volatile Instant lastRefreshAt = Instant.EPOCH;
    private volatile Instant lastFailureAt = Instant.EPOCH;
    private volatile boolean healthy = false;

    public PricingSnapshotClient(String authServiceUrl) {
        this(authServiceUrl, DEFAULT_REFRESH_TTL);
    }

    public PricingSnapshotClient(String authServiceUrl, Duration refreshTtl) {
        if (authServiceUrl == null || authServiceUrl.isBlank()) {
            throw new IllegalArgumentException("authServiceUrl must not be null or blank");
        }
        this.restTemplate = new RestTemplate();
        String base = authServiceUrl.endsWith("/")
            ? authServiceUrl.substring(0, authServiceUrl.length() - 1)
            : authServiceUrl;
        this.snapshotUrl = base + "/api/internal/auth/pricing/snapshot";
        this.refreshTtl = refreshTtl != null ? refreshTtl : DEFAULT_REFRESH_TTL;
    }

    /**
     * Look up pricing rates for a specific provider and model.
     *
     * <p>Refreshes the snapshot cache if stale. Returns empty if the model is
     * not in the snapshot (caller decides policy).</p>
     */
    public Optional<PricingRates> getRates(String provider, String model) {
        refreshIfStale();
        String key = keyFor(provider, model);
        return Optional.ofNullable(cache.get(key));
    }

    /**
     * Look up rates, falling back to given defaults if the model isn't in the snapshot.
     * This exists as a migration bridge - callers should prefer {@link #getRates} and
     * handle the empty case explicitly.
     */
    public PricingRates getRatesOrDefault(String provider, String model,
                                           BigDecimal defaultInput, BigDecimal defaultOutput) {
        return getRates(provider, model)
            .orElse(new PricingRates(defaultInput, defaultOutput, BigDecimal.ZERO));
    }

    /** Whether the last refresh succeeded. */
    public boolean isHealthy() {
        return healthy;
    }

    /**
     * List all known {@code (provider, model)} pairs in the snapshot.
     *
     * <p>Refreshes if stale, then returns a snapshot of the cache keys split
     * back into their two parts. Order is the cache's iteration order
     * (effectively insertion order from the auth-service response, which is
     * already grouped by provider). Empty when the snapshot is empty (CE
     * without billing) or unhealthy.
     *
     * <p>Used by tools that need to surface the catalog to the LLM agent
     * (e.g. {@code web_search(action='help')}). The agent path calls
     * {@code agent-service.ModelCatalogService} directly; cross-service
     * tools that don't run in agent-service prefer this client because it
     * doesn't add a new HTTP dependency - same snapshot, same cache.
     */
    public List<ProviderModel> listKnownModels() {
        refreshIfStale();
        List<ProviderModel> out = new ArrayList<>(cache.size());
        for (String key : cache.keySet()) {
            int sep = key.indexOf("::");
            if (sep < 0) continue;
            out.add(new ProviderModel(key.substring(0, sep), key.substring(sep + 2)));
        }
        return out;
    }

    /**
     * Pair of provider name and model id as stored in the snapshot. Both
     * lowercased - the {@code keyFor} normalisation flattens case before
     * caching.
     */
    public record ProviderModel(String provider, String model) {}

    /** Force a synchronous refresh (useful at startup). */
    public void refresh() {
        doRefresh();
    }

    private void refreshIfStale() {
        Instant now = Instant.now();
        if (Duration.between(lastRefreshAt, now).compareTo(refreshTtl) > 0) {
            // After a failure, back off for MIN_RETRY_INTERVAL to avoid hammering auth-service.
            if (!healthy && Duration.between(lastFailureAt, now).compareTo(MIN_RETRY_INTERVAL) < 0) {
                return;
            }
            doRefresh();
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized void doRefresh() {
        // Double-check after acquiring lock
        if (Duration.between(lastRefreshAt, Instant.now()).compareTo(refreshTtl) <= 0) {
            return;
        }

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                snapshotUrl, HttpMethod.GET, null, MAP_TYPE);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object ratesObj = response.getBody().get("rates");
                if (ratesObj instanceof List<?> ratesList && !ratesList.isEmpty()) {
                    ConcurrentHashMap<String, PricingRates> next = new ConcurrentHashMap<>();
                    for (Object item : ratesList) {
                        if (item instanceof Map<?, ?> row) {
                            String provider = String.valueOf(row.get("provider"));
                            String model = String.valueOf(row.get("model"));
                            BigDecimal inputRate = toBigDecimal(row.get("inputRate"));
                            BigDecimal outputRate = toBigDecimal(row.get("outputRate"));
                            BigDecimal fixedCost = toBigDecimal(row.get("fixedCost"));
                            // V162: contextWindow / maxOutputTokens are nullable on
                            // legacy rows. Backward-compatible: missing keys → null,
                            // not 0 (0 would be indistinguishable from "no context").
                            Integer contextWindow = toIntegerOrNull(row.get("contextWindow"));
                            Integer maxOutputTokens = toIntegerOrNull(row.get("maxOutputTokens"));
                            next.put(keyFor(provider, model),
                                new PricingRates(inputRate, outputRate, fixedCost,
                                    contextWindow, maxOutputTokens));
                        }
                    }
                    cache = next;
                    lastRefreshAt = Instant.now();
                    healthy = true;
                    log.debug("Pricing snapshot refreshed: {} models", next.size());
                    return;
                }
            }
            healthy = false;
            lastFailureAt = Instant.now();
            log.warn("Pricing snapshot returned no rates - keeping previous cache");
        } catch (Exception e) {
            healthy = false;
            lastFailureAt = Instant.now();
            log.warn("Pricing snapshot refresh failed: {} - keeping previous cache", e.getMessage());
        }
        // Don't update lastRefreshAt on failure so the next call retries.
    }

    private static String keyFor(String provider, String model) {
        return (provider != null ? provider.toLowerCase() : "")
            + "::"
            + (model != null ? model.toLowerCase() : "");
    }

    private static BigDecimal toBigDecimal(Object obj) {
        if (obj == null) return BigDecimal.ZERO;
        if (obj instanceof BigDecimal bd) return bd;
        if (obj instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(obj.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Parses an integer-valued field that is allowed to be missing or null
     * (V162 contextWindow / maxOutputTokens). Returns {@code null} for missing,
     * null, or unparseable values - distinct from {@code 0} so callers can detect
     * "unknown" and fail-closed.
     */
    private static Integer toIntegerOrNull(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Integer i) return i;
        if (obj instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Immutable container for per-model pricing rates (per 1K tokens).
     *
     * <p>{@code contextWindow} and {@code maxOutputTokens} added in V162 - both
     * nullable. Drive {@code worstCaseSingleIter} in budget guards (the
     * absolute upper bound on what a single iteration can cost). Pre-V162
     * snapshots and unseeded models leave them {@code null}; consumers
     * decide policy (fail-closed when the
     * {@code BUDGET_GUARD_REQUIRE_CTX_WINDOW} flag is on, fall back to growth-
     * based projection otherwise).</p>
     */
    public record PricingRates(BigDecimal inputRate, BigDecimal outputRate, BigDecimal fixedCost,
                               Integer contextWindow, Integer maxOutputTokens) {
        public PricingRates {
            if (inputRate == null) inputRate = BigDecimal.ZERO;
            if (outputRate == null) outputRate = BigDecimal.ZERO;
            if (fixedCost == null) fixedCost = BigDecimal.ZERO;
            // contextWindow / maxOutputTokens deliberately preserved as null when absent.
        }

        /** Backward-compat constructor for callers that only know about rates. */
        public PricingRates(BigDecimal inputRate, BigDecimal outputRate, BigDecimal fixedCost) {
            this(inputRate, outputRate, fixedCost, null, null);
        }
    }
}

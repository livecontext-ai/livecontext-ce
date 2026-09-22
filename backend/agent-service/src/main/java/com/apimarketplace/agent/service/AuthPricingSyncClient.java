package com.apimarketplace.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin HTTP client for pushing pricing changes from the agent-service catalog
 * (the source of truth) into {@code auth.model_pricing} (billing mirror).
 *
 * <p>Unit contract (shared with {@code auth.model_pricing}): both sides store
 * the provider list price as <b>USD per 1M tokens</b> - no conversion.
 *
 * <p>Called from two paths:
 * <ul>
 *   <li>{@link ModelCatalogService#saveOverride} - single-row admin UI edits (synchronous).</li>
 *   <li>{@code CatalogBundleApplier#apply} - bundle activations, invoked in
 *       {@code TransactionSynchronization.afterCommit()} so only successfully
 *       committed bundle rows hit auth-service.</li>
 * </ul>
 *
 * <p>Since V130 bridge rows carry the underlying cloud model's list price and
 * {@code CreditService.consumeForChat} bills them verbatim - there is no
 * "skip bridges" short-circuit here. {@code providerKind} is propagated so
 * the billing mirror keeps the catalog-origin discriminator for reporting.
 *
 * <p>Fire-and-forget: exceptions are logged and swallowed. A failed sync leaves
 * the billing mirror stale vs. the catalog SSoT; {@code ModelPricingService} has
 * a 1.0/4.0 USD/1M fallback so cost calculation still proceeds.
 */
@Slf4j
@Component
public class AuthPricingSyncClient {

    private final RestTemplate restTemplate;
    private final String authServiceUrl;

    /** Ceiling of {@code auth.model_pricing.input_rate/output_rate} (NUMERIC(10,6)). */
    private static final BigDecimal MAX_RATE = new BigDecimal("9999.999999");

    public AuthPricingSyncClient(RestTemplate restTemplate,
                                 @Value("${services.auth-service.url:http://localhost:8083}") String authServiceUrl) {
        this.restTemplate = restTemplate;
        this.authServiceUrl = authServiceUrl;
    }

    public boolean sync(String provider, String modelId, BigDecimal priceInput, BigDecimal priceOutput,
                        String providerKind) {
        return sync(provider, modelId, priceInput, priceOutput, providerKind, null, null, null);
    }

    /**
     * Rate variant carrying the model's own cache prices (V491), so the billing mirror
     * bills a cached token at what the provider actually charges for it instead of at
     * {@code input_rate} times a per-family constant.
     *
     * <p>A {@code null} or out-of-range cache rate is simply omitted from the payload:
     * auth-service then keeps whatever it already had and falls back to the family
     * multiplier, which is the pre-V491 behaviour. It is never sent as zero - zero would
     * mean "cached input is free".
     */
    public boolean sync(String provider, String modelId, BigDecimal priceInput, BigDecimal priceOutput,
                        String providerKind, BigDecimal priceCacheRead, BigDecimal priceCacheWrite) {
        return sync(provider, modelId, priceInput, priceOutput, providerKind,
                priceCacheRead, priceCacheWrite, null);
    }

    /**
     * @param freeTierEnabled mirror of the catalog's {@code free_tier_enabled} (V493).
     *                        {@code null} leaves the stored flag untouched, so a caller
     *                        that does not track it cannot silently close a model the
     *                        admin opened to the free plan.
     * @return {@code true} when the mirror took the write. Callers that only move RATES may
     *         ignore it (a stale rate is squared up by reconciliation), but a caller that
     *         moves the ACCESS flag must not: the mirror, not the catalog column, is what
     *         the free-tier gate reads, so a dropped write leaves the admin's switch lit
     *         and the gate shut, with nothing to show for it. {@code false} is also returned
     *         for a rate the mirror cannot store, which is a refusal, not a transport failure.
     */
    public boolean sync(String provider, String modelId, BigDecimal priceInput, BigDecimal priceOutput,
                        String providerKind, BigDecimal priceCacheRead, BigDecimal priceCacheWrite,
                        Boolean freeTierEnabled) {
        // Guard the billing mirror: a sentinel/garbage catalog price (e.g. the openrouter/auto
        // router's "-1" list price, which x1e6 becomes -1000000) would 500 the sync with a Postgres
        // numeric overflow (input_rate/output_rate are NUMERIC(10,6)) AND, if the column were ever
        // widened, bill users a NEGATIVE provider cost. A non-billable rate must not reach the mirror.
        if (outOfBillingRange(priceInput) || outOfBillingRange(priceOutput)) {
            log.warn("Skipping pricing sync for {}/{}: non-billable rate input={} output={} (auth allows 0..{})",
                    provider, modelId, priceInput, priceOutput, MAX_RATE);
            return false;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("provider", provider);
            body.put("model", modelId);
            body.put("inputRate", priceInput != null ? priceInput : BigDecimal.ZERO);
            body.put("outputRate", priceOutput != null ? priceOutput : BigDecimal.ZERO);
            if (providerKind != null && !providerKind.isBlank()) {
                body.put("providerKind", providerKind);
            }
            if (storableCacheRate(priceCacheRead)) {
                body.put("cacheReadRate", priceCacheRead);
            }
            if (storableCacheRate(priceCacheWrite)) {
                body.put("cacheWriteRate", priceCacheWrite);
            }
            if (freeTierEnabled != null) {
                body.put("freeTier", freeTierEnabled);
            }

            @SuppressWarnings("rawtypes")
            org.springframework.http.ResponseEntity<java.util.Map> response = restTemplate.postForEntity(
                    authServiceUrl + "/api/internal/auth/model-pricing/sync",
                    body, java.util.Map.class);
            if (freeTierEnabled != null && !mirrorEchoed(response.getBody(), freeTierEnabled)) {
                // A 200 is not proof for the ACCESS flag. An auth-service pod that predates
                // the column ignores the key and answers 200 all the same, which during a
                // rolling deploy is precisely how the catalog ends up saying "open" while
                // every free turn is still refused. Only the echo tells them apart.
                log.warn("Pricing mirror for {}/{} did not confirm freeTier={} (body={}) - treating as not mirrored",
                        provider, modelId, freeTierEnabled, response.getBody());
                return false;
            }
            log.info("Synced pricing to auth-service for {}/{}: input={} output={} cacheRead={} cacheWrite={} kind={} freeTier={} (USD/1M)",
                    provider, modelId, priceInput, priceOutput, priceCacheRead, priceCacheWrite,
                    providerKind, freeTierEnabled);
            return true;
        } catch (Exception e) {
            log.warn("Failed to sync pricing to auth-service for {}/{}: {}",
                    provider, modelId, e.getMessage());
            return false;
        }
    }

    /** Whether the mirror answered with the flag we asked it to store. */
    @SuppressWarnings("rawtypes")
    private static boolean mirrorEchoed(java.util.Map body, boolean expected) {
        return body != null && Boolean.valueOf(expected).equals(body.get("freeTier"));
    }

    /** A rate the billing mirror cannot store: negative, or above the NUMERIC(10,6) ceiling.
     *  {@code null} is in range (the sync coerces it to ZERO). */
    public static boolean outOfBillingRange(BigDecimal rate) {
        return rate != null && (rate.signum() < 0 || rate.compareTo(MAX_RATE) > 0);
    }

    /** The ceiling {@link #outOfBillingRange} enforces, for callers that explain a refusal. */
    public static BigDecimal maxBillableRate() {
        return MAX_RATE;
    }

    /** A cache rate worth sending: present, strictly positive, and within the column's range.
     *  Anything else means "unknown" and is omitted so the mirror keeps its family fallback. */
    private static boolean storableCacheRate(BigDecimal rate) {
        return rate != null && rate.signum() > 0 && rate.compareTo(MAX_RATE) <= 0;
    }
}

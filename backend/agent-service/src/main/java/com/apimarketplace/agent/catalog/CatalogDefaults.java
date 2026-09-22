package com.apimarketplace.agent.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Fallback values applied to {@code model_config_overrides} rows whose
 * feed-sourced fields come in null. Configured via
 * {@code ai.agent.defaults.*} in application.yml and injected into
 * {@code CatalogMergeService} so both bundle-apply and sync-apply paths
 * converge on the same baseline.
 *
 * <p>Rate-limit defaults: every model MUST have non-null tpm/rpm fields
 * so the rate limiter can enforce a ceiling. LiteLLM exposes {@code rpm}
 * and {@code tpm} for ~47 models (1.8% of the feed); every other row
 * arrives with nulls and would let calls bypass the limiter. Admins can
 * still raise or lower them per-model via the UI.
 *
 * <p><b>The TPM default is a floor, not a flat value.</b> What a single request
 * can cost is bounded by the model's context window, so one flat token ceiling
 * cannot fit every model: a number that is generous for a 32k model is smaller
 * than a SINGLE request on a 1M-context one, and the limiter then stops being a
 * safety net and becomes the bottleneck. {@code CatalogMergeService} therefore
 * raises the TPM default to {@code contextWindow x rateLimitTpmContextMultiplier}
 * whenever that is larger. The flat values here are what a model that declares
 * no context window gets.
 */
@Component
@ConfigurationProperties(prefix = "ai.agent.defaults")
public class CatalogDefaults {

    /**
     * Global tokens-per-minute floor when the feed gives no value. Raised to
     * {@code contextWindow x rateLimitTpmContextMultiplier} for models that
     * declare a context window; this value is what the rest get.
     */
    private Integer rateLimitTpm = 2_000_000;

    /** Global requests-per-minute cap when the feed gives no value. */
    private Integer rateLimitRpm = 500;

    /**
     * Per-tenant tokens-per-minute floor when the feed gives no value. Held
     * between a quarter of the row's effective global TPM and the whole of it,
     * so a tenant is never capped under its share and never allowed more than
     * the platform. Both bounds bite: on a row whose global TPM is itself small
     * the upper one wins, and the tenant inherits that small ceiling rather
     * than this floor.
     */
    private Integer rateLimitTpmPerTenant = 500_000;

    /** Per-tenant requests-per-minute cap when the feed gives no value. */
    private Integer rateLimitRpmPerTenant = 200;

    /**
     * Scales the derived floor: {@code contextWindow x this}. The row gets
     * whichever is LARGER, this or {@link #rateLimitTpm}, so with the shipped
     * numbers the multiplier only decides anything above a ~500k context
     * window; below that the flat value already fits many full-context
     * requests and wins. {@code null} or {@code <= 0} switches the derivation
     * off and leaves the flat values.
     */
    private Integer rateLimitTpmContextMultiplier = 4;

    public Integer getRateLimitTpm() { return rateLimitTpm; }
    public void setRateLimitTpm(Integer rateLimitTpm) { this.rateLimitTpm = rateLimitTpm; }

    public Integer getRateLimitRpm() { return rateLimitRpm; }
    public void setRateLimitRpm(Integer rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }

    public Integer getRateLimitTpmPerTenant() { return rateLimitTpmPerTenant; }
    public void setRateLimitTpmPerTenant(Integer rateLimitTpmPerTenant) { this.rateLimitTpmPerTenant = rateLimitTpmPerTenant; }

    public Integer getRateLimitRpmPerTenant() { return rateLimitRpmPerTenant; }
    public void setRateLimitRpmPerTenant(Integer rateLimitRpmPerTenant) { this.rateLimitRpmPerTenant = rateLimitRpmPerTenant; }

    public Integer getRateLimitTpmContextMultiplier() { return rateLimitTpmContextMultiplier; }
    public void setRateLimitTpmContextMultiplier(Integer rateLimitTpmContextMultiplier) { this.rateLimitTpmContextMultiplier = rateLimitTpmContextMultiplier; }
}

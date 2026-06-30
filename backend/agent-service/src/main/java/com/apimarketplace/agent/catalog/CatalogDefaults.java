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
 * arrives with nulls and would let calls bypass the limiter. The defaults
 * below are intentionally conservative - admins can raise them per-model
 * via the UI or per-tenant via {@code rate_limit_*_per_tenant}.
 */
@Component
@ConfigurationProperties(prefix = "ai.agent.defaults")
public class CatalogDefaults {

    /** Global tokens-per-minute cap when the feed gives no value. */
    private Integer rateLimitTpm = 60_000;

    /** Global requests-per-minute cap when the feed gives no value. */
    private Integer rateLimitRpm = 500;

    /** Per-tenant tokens-per-minute cap when the feed gives no value. */
    private Integer rateLimitTpmPerTenant = 20_000;

    /** Per-tenant requests-per-minute cap when the feed gives no value. */
    private Integer rateLimitRpmPerTenant = 200;

    public Integer getRateLimitTpm() { return rateLimitTpm; }
    public void setRateLimitTpm(Integer rateLimitTpm) { this.rateLimitTpm = rateLimitTpm; }

    public Integer getRateLimitRpm() { return rateLimitRpm; }
    public void setRateLimitRpm(Integer rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }

    public Integer getRateLimitTpmPerTenant() { return rateLimitTpmPerTenant; }
    public void setRateLimitTpmPerTenant(Integer rateLimitTpmPerTenant) { this.rateLimitTpmPerTenant = rateLimitTpmPerTenant; }

    public Integer getRateLimitRpmPerTenant() { return rateLimitRpmPerTenant; }
    public void setRateLimitRpmPerTenant(Integer rateLimitRpmPerTenant) { this.rateLimitRpmPerTenant = rateLimitRpmPerTenant; }
}

package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "model_pricing")
public class ModelPricing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "input_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal inputRate;

    @Column(name = "output_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal outputRate;

    /**
     * Provider USD per 1M cached-input tokens (V491), mirrored from the catalog's
     * {@code price_cache_read}. {@code null} = this model has no known cache price,
     * and {@link com.apimarketplace.auth.service.ModelPricingService} then falls back
     * to {@code inputRate} times the provider-family multiplier. Never write 0 to mean
     * "unknown": 0 makes cached input free.
     */
    @Column(name = "cache_read_rate", precision = 10, scale = 6)
    private BigDecimal cacheReadRate;

    /**
     * Provider USD per 1M cache-creation tokens (V491), mirrored from the catalog's
     * {@code price_cache_write}. Only Anthropic-style providers bill a separate,
     * additive cache write; elsewhere it stays {@code null}. Same NULL semantics as
     * {@link #cacheReadRate}.
     */
    @Column(name = "cache_write_rate", precision = 10, scale = 6)
    private BigDecimal cacheWriteRate;

    @Column(name = "fixed_cost", nullable = false, precision = 10, scale = 4)
    private BigDecimal fixedCost = BigDecimal.ZERO;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * Catalog origin (V117 CHECK constraint): "byok" (BYOK / cloud API),
     * "bridge" (local CLI bridge), or "cloud" (managed cloud-proxy).
     * Routing + reporting discriminator - see V117 / V130. DB default is "byok".
     */
    @Column(name = "provider_kind", nullable = false, length = 32)
    private String providerKind = "byok";

    /**
     * Billing mirror of {@code agent.model_config_overrides.free_tier_enabled}
     * (V493), pushed by agent-service's {@code AuthPricingSyncClient}.
     *
     * <p>TRUE means a FREE-plan account may fund a chat / agent turn on this model
     * from its monthly credits (V512; before, from a separate AI allowance, V494)
     * instead of the PAYG bucket alone. A FALSE model is PAYG-only on the Free plan
     * whatever the account's monthly balance. Cloud-only: a CE install meters
     * nothing, so the flag is never read there.
     *
     * <p>Defaults to FALSE and fails closed: a mirror that has not caught up with a
     * catalog edit leaves the model off the free tier, which asks the user to top up
     * rather than spending a grant that was never opened to it.
     */
    @Column(name = "free_tier", nullable = false)
    private Boolean freeTier = false;

    /**
     * Maximum prompt+completion tokens the model can hold (V162).
     * Drives {@code worstCaseSingleIter} in budget guards - the absolute upper bound on
     * what a single iteration can spend. {@code null} = unknown (fail-closed when
     * {@code BUDGET_GUARD_REQUIRE_CTX_WINDOW} flag is enabled, see Phase 1C).
     */
    @Column(name = "context_window")
    private Integer contextWindow;

    /**
     * Maximum completion tokens the model can emit in one call (V162). {@code null} =
     * unknown. Used together with {@link #contextWindow} to compute the worst-case
     * cost of a single iteration: {@code worstCase = computeCost(contextWindow,
     * maxOutputTokens)}.
     */
    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    public Integer getId() { return id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public BigDecimal getInputRate() { return inputRate; }
    public void setInputRate(BigDecimal inputRate) { this.inputRate = inputRate; }
    public BigDecimal getOutputRate() { return outputRate; }
    public void setOutputRate(BigDecimal outputRate) { this.outputRate = outputRate; }
    public BigDecimal getCacheReadRate() { return cacheReadRate; }
    public void setCacheReadRate(BigDecimal cacheReadRate) { this.cacheReadRate = cacheReadRate; }
    public BigDecimal getCacheWriteRate() { return cacheWriteRate; }
    public void setCacheWriteRate(BigDecimal cacheWriteRate) { this.cacheWriteRate = cacheWriteRate; }
    public BigDecimal getFixedCost() { return fixedCost; }
    public void setFixedCost(BigDecimal fixedCost) { this.fixedCost = fixedCost; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { this.isActive = active; }
    public String getProviderKind() { return providerKind; }
    public void setProviderKind(String providerKind) { this.providerKind = providerKind; }
    public Boolean getFreeTier() { return freeTier; }
    public void setFreeTier(Boolean freeTier) { this.freeTier = freeTier != null ? freeTier : Boolean.FALSE; }
    public Integer getContextWindow() { return contextWindow; }
    public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
}

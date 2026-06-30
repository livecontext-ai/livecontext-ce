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
    public BigDecimal getFixedCost() { return fixedCost; }
    public void setFixedCost(BigDecimal fixedCost) { this.fixedCost = fixedCost; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { this.isActive = active; }
    public String getProviderKind() { return providerKind; }
    public void setProviderKind(String providerKind) { this.providerKind = providerKind; }
    public Integer getContextWindow() { return contextWindow; }
    public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
}

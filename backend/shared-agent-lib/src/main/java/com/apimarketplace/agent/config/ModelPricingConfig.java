package com.apimarketplace.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for model pricing and rankings.
 * Reads from application.yml under ai.agent.pricing and ai.agent.rankings.
 */
@Configuration
@ConfigurationProperties(prefix = "ai.agent")
public class ModelPricingConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelPricingConfig.class);

    private Map<String, PricingInfo> pricing = new HashMap<>();

    // Model rankings (model-id -> display order, lower = better)
    // Source: LMArena leaderboard
    private Map<String, Integer> rankings = new HashMap<>();

    // Model tiers (model-id -> tier label: top, high, mid, budget)
    private Map<String, String> tiers = new HashMap<>();

    // Recommended models (good quality/price ratio)
    private List<String> recommended = new ArrayList<>();

    // Per-model rate limit defaults (TPM/RPM, global + per-tenant).
    // Consumed by CachedModelRateLimitProvider as fallback when no DB override exists.
    // Key: modelId (e.g. "claude-opus-4-6", "gpt-5.4"). No provider prefix - providers are
    // inferred at lookup time via the (provider, modelId) → modelId chain in the provider.
    private Map<String, ModelRateLimitInfo> rateLimits = new HashMap<>();

    @PostConstruct
    public void logLoadedConfig() {
        log.info("[ModelPricingConfig] Loaded {} pricing entries, keys: {}", pricing.size(), pricing.keySet());
        log.info("[ModelPricingConfig] Loaded {} ranking entries, keys: {}", rankings.size(), rankings.keySet());
        log.info("[ModelPricingConfig] Loaded {} tier entries, keys: {}", tiers.size(), tiers.keySet());
        log.info("[ModelPricingConfig] Loaded {} rate-limit entries, keys: {}", rateLimits.size(), rateLimits.keySet());
    }

    /**
     * Pricing information for a model (input and output prices per 1M tokens).
     */
    public static class PricingInfo {
        private double input;
        private double output;

        public double getInput() {
            return input;
        }

        public void setInput(double input) {
            this.input = input;
        }

        public double getOutput() {
            return output;
        }

        public void setOutput(double output) {
            this.output = output;
        }
    }

    public Map<String, PricingInfo> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, PricingInfo> pricing) {
        this.pricing = pricing;
    }

    /**
     * Get pricing info for a specific model.
     */
    public PricingInfo getPricingForModel(String modelId) {
        return pricing.get(modelId);
    }

    public Map<String, Integer> getRankings() {
        return rankings;
    }

    public void setRankings(Map<String, Integer> rankings) {
        this.rankings = rankings;
    }

    /**
     * Get ranking (display order) for a specific model.
     * Lower number = better model = displayed first.
     * Returns 999 for unranked models.
     */
    public int getRankingForModel(String modelId) {
        return rankings.getOrDefault(modelId, 999);
    }

    public Map<String, String> getTiers() {
        return tiers;
    }

    public void setTiers(Map<String, String> tiers) {
        this.tiers = tiers;
    }

    /**
     * Get tier label for a specific model (top, high, mid, budget).
     * Returns null for unclassified models.
     */
    public String getTierForModel(String modelId) {
        return tiers.get(modelId);
    }

    public List<String> getRecommended() {
        return recommended;
    }

    public void setRecommended(List<String> recommended) {
        this.recommended = recommended;
    }

    /**
     * Check if a model is recommended (good quality/price ratio).
     */
    public boolean isRecommended(String modelId) {
        return recommended.contains(modelId);
    }

    public Map<String, ModelRateLimitInfo> getRateLimits() {
        return rateLimits;
    }

    public void setRateLimits(Map<String, ModelRateLimitInfo> rateLimits) {
        this.rateLimits = rateLimits;
    }

    /**
     * Get rate limit info for a specific model, or {@code null} if no default is configured.
     */
    public ModelRateLimitInfo getRateLimitForModel(String modelId) {
        return rateLimits.get(modelId);
    }

    /**
     * Default rate limit configuration for a model - mirrors {@link PricingInfo} pattern.
     *
     * <p>All fields are {@link Integer} so that {@code null} means "inherit from provider
     * default" in the 3-level resolution chain (DB override → YAML default → provider default).
     *
     * <p><b>Anthropic ITPM/OTPM note:</b> Anthropic splits input-tokens-per-minute from
     * output-tokens-per-minute. Our sliding window is single-dimensional, so we collapse
     * {@code itpm + otpm} into an aggregate {@code tpm} via {@link #effectiveTpm()} when the
     * explicit {@code tpm} field is not set. This is a conservative approximation - for
     * strict ITPM/OTPM enforcement in production, set an explicit {@code tpm} or configure
     * a DB override in {@code agent.model_config_overrides}.
     */
    public static class ModelRateLimitInfo {
        private Integer tpm;
        private Integer itpm;
        private Integer otpm;
        private Integer rpm;
        private Integer tpmPerTenant;
        private Integer rpmPerTenant;

        public Integer getTpm() { return tpm; }
        public void setTpm(Integer tpm) { this.tpm = tpm; }

        public Integer getItpm() { return itpm; }
        public void setItpm(Integer itpm) { this.itpm = itpm; }

        public Integer getOtpm() { return otpm; }
        public void setOtpm(Integer otpm) { this.otpm = otpm; }

        public Integer getRpm() { return rpm; }
        public void setRpm(Integer rpm) { this.rpm = rpm; }

        public Integer getTpmPerTenant() { return tpmPerTenant; }
        public void setTpmPerTenant(Integer tpmPerTenant) { this.tpmPerTenant = tpmPerTenant; }

        public Integer getRpmPerTenant() { return rpmPerTenant; }
        public void setRpmPerTenant(Integer rpmPerTenant) { this.rpmPerTenant = rpmPerTenant; }

        /**
         * Returns the effective combined TPM used by the single-dimensional sliding window.
         * <ul>
         *   <li>explicit {@code tpm} wins if present;</li>
         *   <li>otherwise {@code itpm + otpm} if both are set (Anthropic pattern);</li>
         *   <li>otherwise whichever of {@code itpm}/{@code otpm} is set;</li>
         *   <li>otherwise {@code null} (= inherit from provider default).</li>
         * </ul>
         */
        public Integer effectiveTpm() {
            if (tpm != null) return tpm;
            if (itpm != null && otpm != null) return itpm + otpm;
            if (itpm != null) return itpm;
            return otpm;
        }
    }
}

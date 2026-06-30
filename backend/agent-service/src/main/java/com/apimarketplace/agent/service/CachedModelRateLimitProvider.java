package com.apimarketplace.agent.service;

import com.apimarketplace.agent.config.ModelPricingConfig;
import com.apimarketplace.agent.config.ModelPricingConfig.ModelRateLimitInfo;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.ratelimit.ModelRateLimit;
import com.apimarketplace.agent.ratelimit.ModelRateLimitProvider;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides per-model rate limit overrides from two sources, merged with clear precedence:
 *
 * <ol>
 *   <li>{@link ModelPricingConfig#getRateLimits()} - YAML defaults declared in
 *       {@code ai.agent.rate-limits.*}. These are the "seed" limits that ship with the
 *       service and represent what a reasonable production deployment should enforce
 *       based on the provider's published tier limits.</li>
 *   <li>{@code agent.model_config_overrides} table - runtime DB overrides. Any non-null
 *       rate limit column here wins over the YAML default on a per-field basis
 *       (DB {@code tpm_per_tenant = null} does NOT clear the YAML-declared value).</li>
 * </ol>
 *
 * <p>Maintains a volatile in-memory cache keyed by {@code "provider:modelId"} AND by
 * {@code modelId} alone. The {@code ProviderRateLimiter} asks via
 * {@link #getModelLimit(String, String)} which tries the prefixed key first and falls
 * back to the bare modelId - this lets the YAML block omit the provider prefix while
 * DB rows can still disambiguate when the same modelId exists under multiple providers.
 *
 * <p>The cache is refreshed every 30 seconds. Only entries with at least one effective
 * rate limit are stored; fully-null entries are omitted so that the rate limiter falls
 * through to provider defaults.
 */
@Slf4j
@Component
public class CachedModelRateLimitProvider implements ModelRateLimitProvider {

    private final ModelConfigOverrideRepository repository;
    private final ModelPricingConfig modelPricingConfig;

    /**
     * Volatile cache: "provider:modelId" or "modelId" → ModelRateLimit.
     * Swapped atomically on each refresh - readers never see a half-built map.
     */
    private volatile Map<String, ModelRateLimit> cache = Map.of();

    public CachedModelRateLimitProvider(ModelConfigOverrideRepository repository,
                                         ModelPricingConfig modelPricingConfig) {
        this.repository = repository;
        this.modelPricingConfig = modelPricingConfig;
    }

    @PostConstruct
    void init() {
        refreshCache();
    }

    @Override
    public ModelRateLimit getModelLimit(String provider, String modelId) {
        if (modelId == null) return null;
        Map<String, ModelRateLimit> snapshot = cache;
        // 1) Try full key "provider:modelId" first (DB entries index this way).
        ModelRateLimit scoped = snapshot.get(provider + ":" + modelId);
        if (scoped != null) return scoped;
        // 2) Fall back to YAML-seeded bare modelId key.
        return snapshot.get(modelId);
    }

    /**
     * Refresh the cache from YAML + database every 30 seconds.
     *
     * <p>Order: YAML first (base layer), DB last (override layer). Per-field merging:
     * a {@code null} column in the DB does NOT clear the YAML-declared default - only
     * non-null DB columns override.
     */
    @Scheduled(fixedRate = 30_000, initialDelay = 30_000)
    public void refreshCache() {
        try {
            Map<String, ModelRateLimit> newCache = new HashMap<>();

            // ───────── Layer 1: YAML defaults from ai.agent.rate-limits.* ─────────
            Map<String, ModelRateLimitInfo> yamlDefaults = modelPricingConfig.getRateLimits();
            if (yamlDefaults != null) {
                for (Map.Entry<String, ModelRateLimitInfo> entry : yamlDefaults.entrySet()) {
                    ModelRateLimit limit = toModelRateLimit(entry.getValue());
                    if (limit != null) {
                        newCache.put(entry.getKey(), limit);
                    }
                }
            }

            // ───────── Layer 2: DB overrides (per-field merge) ─────────
            List<ModelConfigOverrideEntity> overrides = repository.findAllByOrderByRankingAsc();
            for (ModelConfigOverrideEntity entity : overrides) {
                if (entity.getRateLimitTpm() == null
                        && entity.getRateLimitRpm() == null
                        && entity.getRateLimitTpmPerTenant() == null
                        && entity.getRateLimitRpmPerTenant() == null) {
                    continue; // No rate limit override - skip
                }

                String scopedKey = entity.getProvider() + ":" + entity.getModelId();
                // Seed from existing YAML default (scoped key first, then bare modelId).
                ModelRateLimit base = newCache.get(scopedKey);
                if (base == null) base = newCache.get(entity.getModelId());

                Integer tpm          = coalesce(entity.getRateLimitTpm(),          base != null ? base.tpm()          : null);
                Integer rpm          = coalesce(entity.getRateLimitRpm(),          base != null ? base.rpm()          : null);
                Integer tpmPerTenant = coalesce(entity.getRateLimitTpmPerTenant(), base != null ? base.tpmPerTenant() : null);
                Integer rpmPerTenant = coalesce(entity.getRateLimitRpmPerTenant(), base != null ? base.rpmPerTenant() : null);

                newCache.put(scopedKey, new ModelRateLimit(tpm, rpm, tpmPerTenant, rpmPerTenant));
            }

            this.cache = Map.copyOf(newCache); // Immutable snapshot
            if (!newCache.isEmpty()) {
                log.debug("Refreshed model rate limit cache: {} entries (yaml={}, db={})",
                        newCache.size(),
                        yamlDefaults != null ? yamlDefaults.size() : 0,
                        overrides.size());
            }
        } catch (Exception e) {
            log.warn("Failed to refresh model rate limit cache: {}", e.getMessage());
            // Keep stale cache - better than no cache
        }
    }

    /**
     * Convert a YAML-declared {@link ModelRateLimitInfo} to the runtime {@link ModelRateLimit}.
     * Collapses Anthropic's itpm/otpm split into aggregate tpm via
     * {@link ModelRateLimitInfo#effectiveTpm()}. Returns {@code null} if every dimension is
     * unset (caller will omit the entry).
     */
    private ModelRateLimit toModelRateLimit(ModelRateLimitInfo info) {
        if (info == null) return null;
        Integer tpm = info.effectiveTpm();
        if (tpm == null && info.getRpm() == null
                && info.getTpmPerTenant() == null && info.getRpmPerTenant() == null) {
            return null;
        }
        return new ModelRateLimit(tpm, info.getRpm(), info.getTpmPerTenant(), info.getRpmPerTenant());
    }

    private static Integer coalesce(Integer first, Integer second) {
        return first != null ? first : second;
    }
}

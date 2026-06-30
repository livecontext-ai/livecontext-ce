package com.apimarketplace.agent.factory;

import com.apimarketplace.agent.bridge.BridgeAccessGuard;
import com.apimarketplace.agent.config.ModelPricingConfig;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.LLMProviderException;
import com.apimarketplace.agent.provider.OpenAICompatibleProvider;
import com.apimarketplace.agent.provider.OpenAICompatibleProviderFactory;
import com.apimarketplace.agent.ratelimit.ProviderRateLimiter;
import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Factory for LLM providers.
 * Follows the Factory Pattern and Dependency Inversion Principle.
 *
 * All providers are auto-discovered via Spring injection.
 */
@Slf4j
@Component("sharedAgentLLMProviderFactory")
public class LLMProviderFactory {

    private final Map<String, LLMProvider> providersByName = new HashMap<>();

    @Autowired(required = false)
    private ModelPricingConfig pricingConfig;

    @Autowired(required = false)
    private OpenAICompatibleProviderFactory openAICompatibleProviderFactory;

    @Autowired(required = false)
    private LlmCredentialResolver credentialResolver;

    @Autowired(required = false)
    private ProviderRateLimiter rateLimiter;

    /**
     * Optional CLI-bridge access guard. Wired only in the CE monolith (where
     * bridges actually run against shared CLI sessions) - in cloud
     * agent-service the bean is absent and the user-aware path short-circuits
     * to a plain provider lookup.
     */
    @Autowired(required = false)
    private BridgeAccessGuard bridgeAccessGuard;

    /**
     * Constructor with auto-discovery of providers.
     */
    public LLMProviderFactory(List<LLMProvider> providers) {
        if (providers != null && !providers.isEmpty()) {
            for (LLMProvider provider : providers) {
                String name = provider.getProviderName().toLowerCase();
                providersByName.put(name, provider);
                log.info("Registered LLM provider: {} (configured: {})",
                    name, provider.isConfigured());
            }
        } else {
            log.warn("No LLM providers registered");
        }

        log.info("LLMProviderFactory initialized with {} providers", providersByName.size());
    }

    /**
     * Register OpenAI-compatible providers after construction.
     * These are configured via {@code ai.agent.providers.openai-compatible.*} in YAML.
     */
    @PostConstruct
    public void registerOpenAICompatibleProviders() {
        if (openAICompatibleProviderFactory == null) return;

        List<OpenAICompatibleProvider> compatibleProviders = openAICompatibleProviderFactory.createProviders();
        for (OpenAICompatibleProvider provider : compatibleProviders) {
            // Manually inject shared dependencies that Spring @Autowired would
            // handle for @Component-managed beans. Without this, DB-stored API
            // keys are invisible to OpenAI-compatible providers.
            if (credentialResolver != null) {
                provider.setCredentialResolver(credentialResolver);
            }
            if (rateLimiter != null) {
                provider.setRateLimiter(rateLimiter);
            }

            String name = provider.getProviderName().toLowerCase();
            providersByName.put(name, provider);
            // Do NOT call isConfigured() here - it would trigger a DB credential
            // lookup via HTTP to auth-service which may not be ready yet. A failed
            // lookup caches Optional.empty() for 5 min, hiding DB-stored keys.
            log.info("Registered OpenAI-compatible provider: {} (credentialResolver: {})",
                name, credentialResolver != null);
        }

        if (!compatibleProviders.isEmpty()) {
            log.info("LLMProviderFactory: added {} OpenAI-compatible providers (total: {})",
                compatibleProviders.size(), providersByName.size());
        }
    }

    /**
     * Get a provider by name.
     *
     * @param providerName The provider name (openai, anthropic, google, mistral, deepseek)
     * @return The provider
     * @throws LLMProviderException if provider not found
     */
    public LLMProvider getProvider(String providerName) {
        String name = providerName.toLowerCase();
        LLMProvider provider = providersByName.get(name);

        if (provider == null) {
            throw new LLMProviderException(providerName, "Provider not found: " + providerName);
        }

        return provider;
    }

    /**
     * Resolve a provider for a specific user, enforcing bridge access policy.
     *
     * <p>For NON-bridge providers (openai, anthropic, mistral, google, deepseek, …)
     * this is identical to {@link #getProvider(String)} - no auth round-trip.
     *
     * <p>For CLI bridges (claude-code / codex / gemini-cli / mistral-vibe) the
     * {@link BridgeAccessGuard} enforces the admin's policy (disabled /
     * admin_only / allowlist / all_users) and daily quota. A deny throws
     * {@link com.apimarketplace.agent.bridge.BridgeAccessDeniedException}
     * (subtype of {@link LLMProviderException}) so the existing provider error
     * path surfaces it at the API boundary.
     *
     * @param providerName   provider key (lowercased internally)
     * @param userId         caller's user id (Keycloak sub), nullable → treated as "" for header
     * @param userRoles      comma-separated roles (X-User-Roles format); null → {@code USER}
     * @param incrementUsage true → count this call against today's quota on allow
     */
    public LLMProvider getProviderForUser(String providerName,
                                          String userId,
                                          String userRoles,
                                          boolean incrementUsage) {
        if (bridgeAccessGuard != null) {
            bridgeAccessGuard.enforce(userId, userRoles, providerName, incrementUsage);
        }
        return getProvider(providerName);
    }

    /**
     * Get a provider by name, returning Optional.
     */
    public Optional<LLMProvider> findProvider(String providerName) {
        return Optional.ofNullable(providersByName.get(providerName.toLowerCase()));
    }

    /**
     * Get the first configured provider.
     */
    public Optional<LLMProvider> getFirstConfiguredProvider() {
        return providersByName.values().stream()
            .filter(LLMProvider::isConfigured)
            .findFirst();
    }

    /**
     * Get all configured providers.
     */
    public List<LLMProvider> getConfiguredProviders() {
        return providersByName.values().stream()
            .filter(LLMProvider::isConfigured)
            .toList();
    }

    /**
     * Get all available provider names.
     */
    public List<String> getAvailableProviderNames() {
        return List.copyOf(providersByName.keySet());
    }

    /**
     * Check if a provider exists and is configured.
     */
    public boolean isProviderAvailable(String providerName) {
        LLMProvider provider = providersByName.get(providerName.toLowerCase());
        return provider != null && provider.isConfigured();
    }

    /**
     * Get provider that supports a specific model.
     */
    public Optional<LLMProvider> getProviderForModel(String model) {
        return providersByName.values().stream()
            .filter(p -> p.supportsModel(model))
            .findFirst();
    }

    /**
     * Get the name of the first configured provider (lowest displayOrder).
     * Used as a dynamic default when no provider is specified.
     * Returns null only if no providers are configured at all.
     */
    public String getDefaultProviderName() {
        return providersByName.values().stream()
            .filter(LLMProvider::isConfigured)
            .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
            .map(LLMProvider::getProviderName)
            .findFirst()
            .orElse(null);
    }

    /**
     * Get all available models organized by provider.
     * Returns a map with provider info and their models.
     * Models are sorted globally by LMArena rankings (displayOrder from ai.agent.rankings config).
     */
    public Map<String, Object> getAllModelsInfo() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> providers = new java.util.ArrayList<>();

        // Get configured providers sorted by displayOrder
        List<LLMProvider> sortedProviders = providersByName.values().stream()
            .filter(LLMProvider::isConfigured)
            .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
            .toList();

        for (LLMProvider provider : sortedProviders) {
            Map<String, Object> providerInfo = new HashMap<>();
            providerInfo.put("name", provider.getProviderName());
            providerInfo.put("defaultModel", provider.getDefaultModel());
            providerInfo.put("supportsStreaming", provider.supportsStreaming());
            providerInfo.put("supportsToolCalling", provider.supportsToolCalling());
            providerInfo.put("displayOrder", provider.getDisplayOrder());

            List<Map<String, Object>> models = new java.util.ArrayList<>();
            List<String> supportedModels = provider.getSupportedModels();
            for (String model : supportedModels) {
                Map<String, Object> modelInfo = new HashMap<>();
                modelInfo.put("id", model);
                modelInfo.put("name", formatModelName(model));
                modelInfo.put("provider", provider.getProviderName());
                modelInfo.put("isDefault", model.equals(provider.getDefaultModel()));
                // Use global model ranking from config (ai.agent.rankings), fallback to 999
                int displayOrder = pricingConfig != null ? pricingConfig.getRankingForModel(model) : 999;
                modelInfo.put("displayOrder", displayOrder);

                // Add pricing information if available
                if (pricingConfig != null) {
                    ModelPricingConfig.PricingInfo pricing = pricingConfig.getPricingForModel(model);
                    if (pricing != null) {
                        Map<String, Object> pricingMap = new HashMap<>();
                        pricingMap.put("input", pricing.getInput());
                        pricingMap.put("output", pricing.getOutput());
                        modelInfo.put("pricing", pricingMap);
                    }

                    // Add tier classification (top, high, mid, budget)
                    String tier = pricingConfig.getTierForModel(model);
                    if (tier != null) {
                        modelInfo.put("tier", tier);
                    }

                    // Add recommended flag
                    modelInfo.put("recommended", pricingConfig.isRecommended(model));

                    // Add rate limits from YAML seed (ai.agent.rate-limits.*).
                    // These are the base values shown in the admin UI; DB overrides
                    // are applied per-field later in ModelCatalogService so
                    // admin changes always win over the YAML seed.
                    ModelPricingConfig.ModelRateLimitInfo rl = pricingConfig.getRateLimitForModel(model);
                    if (rl != null) {
                        modelInfo.put("rateLimitTpm", rl.effectiveTpm());
                        modelInfo.put("rateLimitRpm", rl.getRpm());
                        modelInfo.put("rateLimitTpmPerTenant", rl.getTpmPerTenant());
                        modelInfo.put("rateLimitRpmPerTenant", rl.getRpmPerTenant());
                    }
                }

                models.add(modelInfo);
            }
            providerInfo.put("models", models);
            providers.add(providerInfo);
        }

        result.put("providers", providers);

        // Default provider is the first sorted provider (lowest displayOrder)
        LLMProvider defaultProvider = sortedProviders.isEmpty() ? null : sortedProviders.get(0);
        result.put("defaultProvider", defaultProvider != null ? defaultProvider.getProviderName() : null);
        result.put("defaultModel", defaultProvider != null ? defaultProvider.getDefaultModel() : null);

        return result;
    }

    /**
     * Get ALL models info including unconfigured providers.
     * Used by the admin model config panel to show all possible models.
     */
    public Map<String, Object> getAllModelsInfoAdmin() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> providers = new java.util.ArrayList<>();

        List<LLMProvider> sortedProviders = providersByName.values().stream()
            .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
            .toList();

        for (LLMProvider provider : sortedProviders) {
            Map<String, Object> providerInfo = new HashMap<>();
            providerInfo.put("name", provider.getProviderName());
            providerInfo.put("defaultModel", provider.getDefaultModel());
            providerInfo.put("configured", provider.isConfigured());

            List<Map<String, Object>> models = new java.util.ArrayList<>();
            for (String model : provider.getSupportedModels()) {
                Map<String, Object> modelInfo = new HashMap<>();
                modelInfo.put("id", model);
                modelInfo.put("name", formatModelName(model));
                modelInfo.put("provider", provider.getProviderName());
                modelInfo.put("isDefault", model.equals(provider.getDefaultModel()));
                int displayOrder = pricingConfig != null ? pricingConfig.getRankingForModel(model) : 999;
                modelInfo.put("displayOrder", displayOrder);
                if (pricingConfig != null) {
                    ModelPricingConfig.PricingInfo pricing = pricingConfig.getPricingForModel(model);
                    if (pricing != null) {
                        modelInfo.put("pricing", Map.of("input", pricing.getInput(), "output", pricing.getOutput()));
                    }
                    modelInfo.put("recommended", pricingConfig.isRecommended(model));

                    // Rate limits from YAML seed (same logic as getAllModelsInfo).
                    ModelPricingConfig.ModelRateLimitInfo rl = pricingConfig.getRateLimitForModel(model);
                    if (rl != null) {
                        modelInfo.put("rateLimitTpm", rl.effectiveTpm());
                        modelInfo.put("rateLimitRpm", rl.getRpm());
                        modelInfo.put("rateLimitTpmPerTenant", rl.getTpmPerTenant());
                        modelInfo.put("rateLimitRpmPerTenant", rl.getRpmPerTenant());
                    }
                }
                models.add(modelInfo);
            }
            providerInfo.put("models", models);
            providers.add(providerInfo);
        }

        result.put("providers", providers);
        return result;
    }

    /**
     * Format a model ID to a readable name.
     * Converts version segments like "4-6" to "4.6" and capitalizes words.
     * Examples: "claude-opus-4-6" → "Claude Opus 4.6", "gpt-4o-mini" → "Gpt 4o Mini"
     */
    private String formatModelName(String modelId) {
        if (modelId == null) return "";

        // Convert version-like digit-digit patterns (e.g. "4-6") to dot notation ("4.6")
        String normalized = modelId.replaceAll("(\\d)-(\\d)", "$1.$2");
        String[] parts = normalized.replace("-", " ").replace("_", " ").split(" ");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(part.substring(0, 1).toUpperCase())
                  .append(part.substring(1));
            }
        }

        return sb.toString();
    }
}

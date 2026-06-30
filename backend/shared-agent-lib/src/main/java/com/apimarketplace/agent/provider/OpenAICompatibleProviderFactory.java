package com.apimarketplace.agent.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Creates {@link OpenAICompatibleProvider} instances for every entry under
 * {@code ai.agent.providers.<name>.*} in application.yml.
 *
 * <p>Each sub-key becomes a provider name (e.g. {@code xai}, {@code perplexity}),
 * and the nested properties ({@code api-key}, {@code api-url}, {@code models},
 * {@code display-order}) are read via Spring's {@link Environment}.
 *
 * <p>All providers are always registered regardless of whether an API key is set.
 * Callers check {@link OpenAICompatibleProvider#isConfigured()} to determine availability.
 *
 * <p>Intended usage: inject the list returned by {@link #createProviders()} into
 * {@link LLMProviderFactory}.
 */
@Slf4j
@Component
public class OpenAICompatibleProviderFactory {

    private final Environment env;

    public OpenAICompatibleProviderFactory(Environment env) {
        this.env = env;
    }

    private static final String BASE_PREFIX = "ai.agent.providers.";

    /**
     * Create provider instances from {@code ai.agent.providers.<name>.*}.
     * All providers are always registered; availability is determined by
     * {@link OpenAICompatibleProvider#isConfigured()}.
     */
    public List<OpenAICompatibleProvider> createProviders() {
        List<OpenAICompatibleProvider> providers = new ArrayList<>();

        // Well-known OpenAI-compatible providers with sensible defaults
        Map<String, ProviderDefaults> knownProviders = new LinkedHashMap<>();
        knownProviders.put("xai", new ProviderDefaults(
            "https://api.x.ai/v1/chat/completions",
            "grok-3-beta,grok-3-mini-beta",
            10
        ));
        knownProviders.put("perplexity", new ProviderDefaults(
            "https://api.perplexity.ai/chat/completions",
            "sonar-pro,sonar-reasoning-pro",
            11
        ));
        knownProviders.put("cohere", new ProviderDefaults(
            "https://api.cohere.ai/compatibility/v1/chat/completions",
            "command-r-plus-08-2024,command-r-08-2024",
            12
        ));
        knownProviders.put("zai", new ProviderDefaults(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            "glm-5.1,glm-5,glm-5-turbo",
            13
        ));
        knownProviders.put("openrouter", new ProviderDefaults(
            "https://openrouter.ai/api/v1/chat/completions",
            "anthropic/claude-sonnet-4-20250514,openai/gpt-5.4,google/gemini-3-pro-preview",
            14
        ));

        for (var entry : knownProviders.entrySet()) {
            String name = entry.getKey();
            ProviderDefaults defaults = entry.getValue();
            String prefix = BASE_PREFIX + name + ".";

            String apiKey = env.getProperty(prefix + "api-key", "");
            String apiUrl = env.getProperty(prefix + "api-url", defaults.apiUrl);

            String modelsStr = env.getProperty(prefix + "models", defaults.models);
            List<String> models = modelsStr != null && !modelsStr.isBlank()
                ? Arrays.asList(modelsStr.split(","))
                : List.of();

            int displayOrder = env.getProperty(prefix + "display-order", Integer.class, defaults.displayOrder);

            OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                name, apiUrl, apiKey, models, displayOrder
            );

            providers.add(provider);
            log.info("Created OpenAI-compatible provider: {} (models: {}, hasEnvKey: {})",
                name, models, !apiKey.isBlank());
        }

        return providers;
    }

    private record ProviderDefaults(String apiUrl, String models, int displayOrder) {}
}

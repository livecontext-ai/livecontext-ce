package com.apimarketplace.agent.cloud;

import java.util.Set;

/**
 * Provider names that the Cloud relay can execute using Cloud-side credentials.
 */
public final class CloudRelaySupport {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of(
            "anthropic",
            "openai",
            "google",
            "mistral",
            "deepseek",
            "xai",
            "perplexity",
            "cohere",
            "zai",
            "openrouter",
            "qwen",
            "moonshot"
    );

    private CloudRelaySupport() {
    }

    public static boolean isSupportedProvider(String providerName) {
        return providerName != null
                && SUPPORTED_PROVIDERS.contains(providerName.trim().toLowerCase());
    }
}

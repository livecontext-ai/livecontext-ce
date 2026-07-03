package com.apimarketplace.agent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CE boundary in {@link OpenAICompatibleProviderFactory}: on a self-hosted
 * install ({@code auth.mode=embedded}) the multi-provider aggregator
 * (openrouter) and the curated-out cohere provider are never registered - so a
 * key can't be set or used for them. Cloud (any non-embedded mode) registers
 * every provider unchanged.
 */
@DisplayName("OpenAICompatibleProviderFactory - CE provider gate")
class OpenAICompatibleProviderFactoryCeGateTest {

    private List<String> providerNames(String authMode) {
        MockEnvironment env = new MockEnvironment();
        if (authMode != null) {
            env.setProperty("auth.mode", authMode);
        }
        return new OpenAICompatibleProviderFactory(env).createProviders().stream()
                .map(OpenAICompatibleProvider::getProviderName)
                .toList();
    }

    @Test
    @DisplayName("CE (embedded) does NOT register openrouter or cohere, keeps the rest incl. qwen/moonshot")
    void embeddedSkipsBlockedProviders() {
        List<String> names = providerNames("embedded");
        assertThat(names).doesNotContain("openrouter", "cohere");
        assertThat(names).contains("xai", "perplexity", "zai", "qwen", "moonshot");
    }

    @Test
    @DisplayName("Cloud (no auth.mode) registers every provider incl. openrouter and cohere")
    void cloudRegistersAllProviders() {
        List<String> names = providerNames(null);
        assertThat(names).contains("openrouter", "cohere", "xai", "perplexity", "zai", "qwen", "moonshot");
    }

    @Test
    @DisplayName("Cloud (auth.mode=keycloak) also registers the blocked providers - gate is embedded-only")
    void keycloakRegistersBlockedProviders() {
        List<String> names = providerNames("keycloak");
        assertThat(names).contains("openrouter", "cohere");
    }
}

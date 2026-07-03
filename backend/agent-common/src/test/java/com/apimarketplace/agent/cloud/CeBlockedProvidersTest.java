package com.apimarketplace.agent.cloud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CeBlockedProviders - CE (self-hosted) provider boundary")
class CeBlockedProvidersTest {

    @Test
    @DisplayName("openrouter and cohere are blocked; every other provider is allowed")
    void blocksOnlyOpenRouterAndCohere() {
        assertThat(CeBlockedProviders.isBlocked("openrouter")).isTrue();
        assertThat(CeBlockedProviders.isBlocked("cohere")).isTrue();
        assertThat(CeBlockedProviders.isBlocked("openai")).isFalse();
        assertThat(CeBlockedProviders.isBlocked("anthropic")).isFalse();
        assertThat(CeBlockedProviders.isBlocked("qwen")).isFalse();
        assertThat(CeBlockedProviders.isBlocked("moonshot")).isFalse();
        assertThat(CeBlockedProviders.isBlocked("zai")).isFalse();
    }

    @Test
    @DisplayName("matching is case-insensitive and null-safe")
    void caseInsensitiveAndNullSafe() {
        assertThat(CeBlockedProviders.isBlocked("OpenRouter")).isTrue();
        assertThat(CeBlockedProviders.isBlocked("  COHERE ")).isTrue();
        assertThat(CeBlockedProviders.isBlocked(null)).isFalse();
        assertThat(CeBlockedProviders.isBlocked("")).isFalse();
    }

    @Test
    @DisplayName("isBlockedInMode only blocks under auth.mode=embedded (CE)")
    void blockedOnlyInEmbeddedMode() {
        // CE: embedded + blocked provider -> blocked
        assertThat(CeBlockedProviders.isBlockedInMode("embedded", "openrouter")).isTrue();
        assertThat(CeBlockedProviders.isBlockedInMode("EMBEDDED", "cohere")).isTrue();
        // CE: embedded + allowed provider -> not blocked
        assertThat(CeBlockedProviders.isBlockedInMode("embedded", "openai")).isFalse();
        // Cloud: keycloak / empty / null mode never blocks, even the aggregator
        assertThat(CeBlockedProviders.isBlockedInMode("keycloak", "openrouter")).isFalse();
        assertThat(CeBlockedProviders.isBlockedInMode("", "openrouter")).isFalse();
        assertThat(CeBlockedProviders.isBlockedInMode(null, "openrouter")).isFalse();
    }

    @Test
    @DisplayName("names() exposes exactly the two blocked providers")
    void namesExposesBlockedSet() {
        assertThat(CeBlockedProviders.names()).containsExactlyInAnyOrder("openrouter", "cohere");
    }
}

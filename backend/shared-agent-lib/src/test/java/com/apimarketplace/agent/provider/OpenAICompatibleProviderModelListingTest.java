package com.apimarketplace.agent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the provider-side half of native model discovery: turning a
 * configured chat-completions URL into the vendor's {@code /models} endpoint,
 * and reading the ids back out of whatever envelope the vendor returns.
 *
 * <p>The network call itself is not exercised here - it is a one-line
 * RestTemplate exchange wrapped in a catch-all. What can actually go wrong is
 * the URL derivation (a wrong path silently discovers nothing) and the
 * envelope parsing (OpenAI-compatible vendors take liberties), so that is what
 * is pinned.
 */
@DisplayName("OpenAICompatibleProvider - /models endpoint derivation + parsing")
class OpenAICompatibleProviderModelListingTest {

    private static OpenAICompatibleProvider provider(String apiUrl) {
        return new OpenAICompatibleProvider("zai", apiUrl, "", List.of(), 1);
    }

    @Test
    @DisplayName("Derives <base>/models from the configured chat-completions URL")
    void derivesModelsEndpoint() {
        // The four Chinese providers as configured in application.yml.
        assertThat(provider("https://api.z.ai/api/paas/v4/chat/completions").modelsEndpoint())
                .isEqualTo("https://api.z.ai/api/paas/v4/models");
        assertThat(provider("https://api.moonshot.ai/v1/chat/completions").modelsEndpoint())
                .isEqualTo("https://api.moonshot.ai/v1/models");
        assertThat(provider("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions").modelsEndpoint())
                .isEqualTo("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/models");
        assertThat(provider("https://api.minimax.io/v1/chat/completions").modelsEndpoint())
                .isEqualTo("https://api.minimax.io/v1/models");
    }

    @Test
    @DisplayName("A URL that is not a chat-completions path yields no endpoint, not a guessed one")
    void refusesToGuessOnAnUnexpectedUrl() {
        // An operator override pointing somewhere exotic must degrade to "no
        // discovery for this provider" rather than fire a request at a path
        // that was never verified to exist.
        assertThat(provider("https://example.test/v1/responses").modelsEndpoint()).isNull();
        assertThat(provider("").modelsEndpoint()).isNull();
        assertThat(provider(null).modelsEndpoint()).isNull();
    }

    @Test
    @DisplayName("An unconfigured provider is never probed")
    void unconfiguredProviderReturnsEmpty() {
        // No key anywhere -> isConfigured() is false -> no request at all.
        // This is what makes discovery safe to run on a cluster where a
        // provider is declared but has no secret wired.
        OpenAICompatibleProvider unkeyed =
                provider("https://api.z.ai/api/paas/v4/chat/completions");
        assertThat(unkeyed.listRemoteModelIds()).isEmpty();
    }

    @Test
    @DisplayName("Discovery uses SHORT timeouts, not the 1-hour completions one")
    void discoveryIsTightlyBounded() {
        // The completions RestTemplate is sized for LLM generation
        // (ai.agent.llm.read-timeout-ms, 1h by default). Discovery runs inside
        // the catalog sync's transaction, once per provider, so reusing that
        // timeout would let one unreachable vendor pin an open DB transaction
        // for an hour. A model list is a few KB: seconds, or never.
        assertThat(OpenAICompatibleProvider.DISCOVERY_CONNECT_TIMEOUT_MS)
                .isPositive().isLessThanOrEqualTo(10_000);
        assertThat(OpenAICompatibleProvider.DISCOVERY_READ_TIMEOUT_MS)
                .isPositive().isLessThanOrEqualTo(30_000);
    }

    @Test
    @DisplayName("Reads ids from the standard {data:[{id}]} envelope")
    void parsesStandardEnvelope() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object", "list");
        body.put("data", List.of(
                Map.of("id", "glm-5.3", "object", "model"),
                Map.of("id", "glm-5.2", "object", "model")));

        assertThat(provider("https://api.z.ai/v1/chat/completions").extractModelIds(body))
                .containsExactly("glm-5.3", "glm-5.2");
    }

    @Test
    @DisplayName("Tolerates plain-string entries and trims them")
    void parsesBareStringEntries() {
        Map<String, Object> body = Map.of("data", List.of("kimi-k3", "  kimi-k2.6  "));

        assertThat(provider("https://api.z.ai/v1/chat/completions").extractModelIds(body))
                .containsExactly("kimi-k3", "kimi-k2.6");
    }

    @Test
    @DisplayName("Unknown or empty shapes yield an empty list, never an exception")
    void toleratesUnknownShapes() {
        assertThat(provider("https://api.z.ai/v1/chat/completions").extractModelIds(null)).isEmpty();
        assertThat(provider("https://api.z.ai/v1/chat/completions").extractModelIds(Map.of())).isEmpty();
        assertThat(provider("https://api.z.ai/v1/chat/completions").extractModelIds(Map.of("data", "nope"))).isEmpty();
        assertThat(provider("https://api.z.ai/v1/chat/completions").extractModelIds(Map.of("data", List.of()))).isEmpty();
    }

    @Test
    @DisplayName("Entries without a usable id are skipped, the rest still come through")
    void skipsUnusableEntries() {
        Map<String, Object> withBlank = new LinkedHashMap<>();
        withBlank.put("id", "");
        Map<String, Object> withoutId = new LinkedHashMap<>();
        withoutId.put("object", "model");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", List.of(withBlank, withoutId, Map.of("id", "glm-5.3")));

        assertThat(provider("https://api.z.ai/v1/chat/completions").extractModelIds(body)).containsExactly("glm-5.3");
    }
}

package com.apimarketplace.agent.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LlmProviderStatusController#computeConnected}.
 *
 * The controller's {@code /bridge-status} endpoint must return {@code connected=true}
 * with these semantics:
 *   - With a CLI filter → only when that specific CLI is reported installed.
 *   - Without a filter   → when at least one CLI is installed.
 *
 * These tests guard against regressions where any 200 from the bridge would
 * incorrectly mark every CLI as connected (the original bug).
 */
@DisplayName("LlmProviderStatusController#computeConnected")
class LlmProviderStatusControllerTest {

    private final LlmProviderStatusController controller =
            new LlmProviderStatusController(null, null, null, null);

    @Nested
    @DisplayName("offering-models")
    class OfferingModels {

        @Test
        @DisplayName("relays the catalogue answer to any signed-in caller, with no admin check")
        void relaysTheCatalogueAnswer() {
            // The own-keys panel is a USER surface: gating this would empty it for everyone but
            // an admin. It carries no credential state, only which providers this install has
            // models for, which is the price list in a different shape.
            com.apimarketplace.agent.service.ModelCatalogService catalog =
                    org.mockito.Mockito.mock(com.apimarketplace.agent.service.ModelCatalogService.class);
            org.mockito.Mockito.when(catalog.providersOfferingModels())
                    .thenReturn(java.util.List.of("anthropic", "openai"));
            LlmProviderStatusController scoped =
                    new LlmProviderStatusController(null, null, null, catalog);

            var response = scoped.getProvidersOfferingModels();

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).containsExactly("anthropic", "openai");
        }

        @Test
        @DisplayName("an install that exposes nothing answers an empty list, not an error")
        void emptyIsAnAnswer() {
            // What the panel renders as "no provider to bring a key for". An error here would
            // make it fall back to its hardcoded list and name providers that serve nothing.
            com.apimarketplace.agent.service.ModelCatalogService catalog =
                    org.mockito.Mockito.mock(com.apimarketplace.agent.service.ModelCatalogService.class);
            org.mockito.Mockito.when(catalog.providersOfferingModels()).thenReturn(java.util.List.of());
            LlmProviderStatusController scoped =
                    new LlmProviderStatusController(null, null, null, catalog);

            var response = scoped.getProvidersOfferingModels();

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("invalidate-cache/mine")
    class InvalidateMine {

        @Test
        @DisplayName("drops only the caller's own resolver and hasDbKey slots for the provider, without any admin check")
        void dropsOnlyTheCallersSlots() {
            com.apimarketplace.agent.credential.LlmCredentialRepository repository =
                    org.mockito.Mockito.mock(com.apimarketplace.agent.credential.LlmCredentialRepository.class);
            com.apimarketplace.agent.credential.CachedLlmCredentialResolver resolver =
                    org.mockito.Mockito.mock(com.apimarketplace.agent.credential.CachedLlmCredentialResolver.class);
            LlmProviderStatusController scoped = new LlmProviderStatusController(null, repository, resolver, null);

            var response = scoped.invalidateMyCache("user-9", "openai");

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            org.mockito.Mockito.verify(resolver).invalidate("user-9", "openai");
            org.mockito.Mockito.verify(repository).clearHasDbKeyCache("user-9", "openai");
            // Never the platform-wide forms: those are the admin endpoint's job.
            org.mockito.Mockito.verify(resolver, org.mockito.Mockito.never()).invalidate("openai");
            org.mockito.Mockito.verify(resolver, org.mockito.Mockito.never()).invalidateAll();
            org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).clearHasDbKeyCache("openai");
        }
    }

    @Nested
    @DisplayName("validate")
    class Validate {

        private LlmProviderStatusController withProvider(String name, LLMProvider provider) {
            LLMProviderFactory factory = org.mockito.Mockito.mock(LLMProviderFactory.class);
            org.mockito.Mockito.when(factory.findProvider(name))
                    .thenReturn(Optional.ofNullable(provider));
            return new LlmProviderStatusController(factory, null, null, null);
        }

        @Test
        @DisplayName("asks the provider with the key and relays its verdict, without any admin check")
        @SuppressWarnings("unchecked")
        void relaysTheProviderVerdict() {
            LLMProvider provider = org.mockito.Mockito.mock(LLMProvider.class);
            org.mockito.Mockito.when(provider.validateApiKey("sk-bad"))
                    .thenReturn(LLMProvider.KeyCheck.rejected("rejected by openai (HTTP 401)"));

            var response = withProvider("openai", provider).validateKey("user-1", Map.of("provider", "OpenAI", "apiKey", "sk-bad"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("valid", false).containsEntry("verified", true)
                    .containsEntry("error", "rejected by openai (HTTP 401)");
        }

        @Test
        @DisplayName("an accepted key answers valid + verified with an empty error")
        @SuppressWarnings("unchecked")
        void acceptedKey() {
            LLMProvider provider = org.mockito.Mockito.mock(LLMProvider.class);
            org.mockito.Mockito.when(provider.validateApiKey("sk-ok")).thenReturn(LLMProvider.KeyCheck.accepted());

            Map<String, Object> body = (Map<String, Object>) withProvider("openai", provider)
                    .validateKey("user-1", Map.of("provider", "openai", "apiKey", "sk-ok")).getBody();

            assertThat(body).containsEntry("valid", true).containsEntry("verified", true).containsEntry("error", "");
        }

        @Test
        @DisplayName("an unknown provider is 404, a missing key or provider is 400")
        void guards() {
            LlmProviderStatusController controller = withProvider("nope", null);

            assertThat(controller.validateKey("user-1", Map.of("provider", "nope", "apiKey", "k")).getStatusCode().value()).isEqualTo(404);
            assertThat(controller.validateKey("user-1", Map.of("provider", "openai")).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.validateKey("user-1", Map.of("apiKey", "k")).getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("a user gets a budget of key checks per hour, then 429: the endpoint must not be a key-testing oracle for stolen keys")
        void perUserBudget() {
            LLMProvider provider = org.mockito.Mockito.mock(LLMProvider.class);
            org.mockito.Mockito.when(provider.validateApiKey("sk-x")).thenReturn(LLMProvider.KeyCheck.accepted());
            LlmProviderStatusController controller = withProvider("openai", provider);

            for (int i = 0; i < LlmProviderStatusController.KEY_CHECKS_PER_HOUR; i++) {
                assertThat(controller.validateKey("user-1", Map.of("provider", "openai", "apiKey", "sk-x")).getStatusCode().value())
                        .as("check %d", i + 1).isEqualTo(200);
            }
            assertThat(controller.validateKey("user-1", Map.of("provider", "openai", "apiKey", "sk-x")).getStatusCode().value()).isEqualTo(429);
            // Another user has their own budget, and the vendor was never asked for the refused one.
            assertThat(controller.validateKey("user-2", Map.of("provider", "openai", "apiKey", "sk-x")).getStatusCode().value()).isEqualTo(200);
            org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(LlmProviderStatusController.KEY_CHECKS_PER_HOUR + 1)).validateApiKey("sk-x");
        }

        @Test
        @DisplayName("the window slides: a check older than the window no longer counts")
        void budgetWindowSlides() throws Exception {
            LlmProviderStatusController.KeyCheckBudget budget =
                    new LlmProviderStatusController.KeyCheckBudget(2, java.time.Duration.ofMillis(1));
            assertThat(budget.tryAcquire("u")).isTrue();
            assertThat(budget.tryAcquire("u")).isTrue();
            assertThat(budget.tryAcquire("u")).isFalse();
            Thread.sleep(5);
            assertThat(budget.tryAcquire("u")).isTrue();
        }
    }

    private static Map<String, Object> entry(boolean installed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("installed", installed);
        return m;
    }

    @Nested
    @DisplayName("with cli filter")
    class WithFilter {

        @Test
        void returnsTrueWhenSelectedCliInstalled() {
            Map<String, Object> body = Map.of("cli", entry(true));
            assertThat(controller.computeConnected(body, "claudeCode")).isTrue();
        }

        @Test
        void returnsFalseWhenSelectedCliNotInstalled() {
            Map<String, Object> body = Map.of("cli", entry(false));
            assertThat(controller.computeConnected(body, "claudeCode")).isFalse();
        }

        @Test
        void returnsFalseWhenCliFieldMissing() {
            Map<String, Object> body = Map.of("bridgeReachable", true);
            assertThat(controller.computeConnected(body, "claudeCode")).isFalse();
        }
    }

    @Test
    @DisplayName("SUPPORTED_CLIS contains the four bridge ids and nothing else")
    void supportedClisAllowlistMatchesBridge() {
        assertThat(LlmProviderStatusController.SUPPORTED_CLIS)
                .containsExactlyInAnyOrder("claudeCode", "codex", "geminiCli", "mistralVibe");
    }

    @Nested
    @DisplayName("without cli filter")
    class WithoutFilter {

        @Test
        void returnsTrueWhenAtLeastOneCliInstalled() {
            Map<String, Object> clis = Map.of(
                    "claudeCode", entry(false),
                    "codex", entry(true),
                    "geminiCli", entry(false),
                    "mistralVibe", entry(false));
            Map<String, Object> body = Map.of("clis", clis);
            assertThat(controller.computeConnected(body, null)).isTrue();
        }

        @Test
        void returnsFalseWhenNoCliInstalled() {
            Map<String, Object> clis = Map.of(
                    "claudeCode", entry(false),
                    "codex", entry(false),
                    "geminiCli", entry(false),
                    "mistralVibe", entry(false));
            Map<String, Object> body = Map.of("clis", clis);
            assertThat(controller.computeConnected(body, null)).isFalse();
        }

        @Test
        void returnsFalseWhenClisFieldMissing() {
            Map<String, Object> body = Map.of("bridgeReachable", true);
            assertThat(controller.computeConnected(body, null)).isFalse();
        }

        @Test
        void treatsBlankFilterAsNoFilter() {
            Map<String, Object> clis = Map.of("codex", entry(true));
            Map<String, Object> body = Map.of("clis", clis);
            assertThat(controller.computeConnected(body, "")).isTrue();
        }
    }
}

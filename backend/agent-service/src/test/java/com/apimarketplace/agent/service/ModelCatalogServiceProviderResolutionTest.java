package com.apimarketplace.agent.service;

import com.apimarketplace.agent.service.ModelCatalogService.AvailableModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ModelCatalogService#resolveProviderForModel(List, String, String)} -
 * the pure provider-normalisation logic behind the F25 fix.
 *
 * <p>F25: a Claude "Bridge (CLI)" model selected in a workflow LLM node was
 * stored with {@code provider="anthropic"} (frontend prefix heuristic /
 * LLM-authored plan), so it dispatched to the direct Anthropic API (credit
 * pool) instead of the subscription bridge, AND silently bypassed
 * {@code BridgeAccessGuard} (which only gates bridge provider slugs). The
 * resolver re-routes such a stale/invalid pair to the provider that actually
 * serves the model, while leaving every genuinely-valid pair untouched.
 *
 * <p>Catalog modelled on the real {@code application.yml}:
 * {@code anthropic} serves opus-4-6 / sonnet-4-6 / haiku-4-5 (NOT opus-4-7);
 * {@code claude-code} serves opus-4-7 (+ shared ones); {@code deepseek} serves
 * deepseek-chat.
 */
@DisplayName("ModelCatalogService.resolveProviderForModel (F25 provider normalisation)")
class ModelCatalogServiceProviderResolutionTest {

    private static final List<AvailableModel> CATALOG = List.of(
        new AvailableModel("anthropic", "claude-opus-4-6", "high", 1),
        new AvailableModel("anthropic", "claude-sonnet-4-6", "mid", 2),
        new AvailableModel("anthropic", "claude-haiku-4-5", "low", 3),
        new AvailableModel("claude-code", "claude-opus-4-7", "high", 6),
        new AvailableModel("claude-code", "claude-opus-4-6", "high", 7),
        new AvailableModel("claude-code", "claude-sonnet-4-6", "mid", 8),
        new AvailableModel("claude-code", "claude-sonnet-4-5", "mid", 9),
        new AvailableModel("claude-code", "claude-haiku-4-5", "low", 10),
        new AvailableModel("deepseek", "deepseek-chat", "mid", 5)
    );

    private static String resolve(String provider, String model) {
        return ModelCatalogService.resolveProviderForModel(CATALOG, provider, model);
    }

    @Nested
    @DisplayName("Re-routes stale/invalid pairs to the only provider serving the model")
    class ReRoutes {

        @Test
        @DisplayName("F25 core: (anthropic, claude-opus-4-7) -> claude-code (bridge-only model)")
        void bridgeOnlyModelMislabelledAnthropic() {
            assertThat(resolve("anthropic", "claude-opus-4-7")).isEqualTo("claude-code");
        }

        @Test
        @DisplayName("blank provider + bridge-only model -> claude-code")
        void blankProviderBridgeOnly() {
            assertThat(resolve("", "claude-opus-4-7")).isEqualTo("claude-code");
            assertThat(resolve(null, "claude-opus-4-7")).isEqualTo("claude-code");
        }

        @Test
        @DisplayName("wrong provider entirely (openai, claude-opus-4-7) -> claude-code")
        void wrongProviderSingleServing() {
            assertThat(resolve("openai", "claude-opus-4-7")).isEqualTo("claude-code");
        }

        @Test
        @DisplayName("bridge-only sonnet variant (anthropic, claude-sonnet-4-5) -> claude-code")
        void otherBridgeOnlyModel() {
            assertThat(resolve("anthropic", "claude-sonnet-4-5")).isEqualTo("claude-code");
        }
    }

    @Nested
    @DisplayName("Leaves valid / ambiguous / unknown pairs untouched")
    class LeavesUntouched {

        @Test
        @DisplayName("valid direct-API pair (anthropic, claude-opus-4-6) stays anthropic (dual-listed)")
        void validDualListedDirectChoiceKept() {
            // claude-opus-4-6 is served by BOTH anthropic and claude-code; a
            // deliberate direct-API selection must NOT be hijacked to the bridge.
            assertThat(resolve("anthropic", "claude-opus-4-6")).isEqualTo("anthropic");
        }

        @Test
        @DisplayName("valid bridge pair (claude-code, claude-opus-4-7) stays claude-code")
        void validBridgePairKept() {
            assertThat(resolve("claude-code", "claude-opus-4-7")).isEqualTo("claude-code");
        }

        @Test
        @DisplayName("valid single-listed pair (deepseek, deepseek-chat) stays deepseek")
        void validSingleListedKept() {
            assertThat(resolve("deepseek", "deepseek-chat")).isEqualTo("deepseek");
        }

        @Test
        @DisplayName("valid pair is case-insensitive and returns the caller's provider verbatim")
        void validPairCaseInsensitiveReturnsVerbatim() {
            assertThat(resolve("Anthropic", "claude-opus-4-6")).isEqualTo("Anthropic");
        }

        @Test
        @DisplayName("blank provider + dual-listed model is ambiguous -> unchanged (no surprise bridge routing)")
        void blankProviderAmbiguousUnchanged() {
            assertThat(resolve("", "claude-opus-4-6")).isEqualTo("");
            assertThat(resolve(null, "claude-opus-4-6")).isNull();
        }

        @Test
        @DisplayName("unknown model -> provider unchanged (never mask a real config error)")
        void unknownModelUnchanged() {
            assertThat(resolve("anthropic", "gpt-9-imaginary")).isEqualTo("anthropic");
        }

        @Test
        @DisplayName("null / blank model -> provider unchanged")
        void nullOrBlankModelUnchanged() {
            assertThat(resolve("anthropic", null)).isEqualTo("anthropic");
            assertThat(resolve("anthropic", "  ")).isEqualTo("anthropic");
        }

        @Test
        @DisplayName("empty / null catalog -> provider unchanged (catalog unavailable)")
        void emptyCatalogUnchanged() {
            assertThat(ModelCatalogService.resolveProviderForModel(List.of(), "anthropic", "claude-opus-4-7"))
                .isEqualTo("anthropic");
            assertThat(ModelCatalogService.resolveProviderForModel(null, "anthropic", "claude-opus-4-7"))
                .isEqualTo("anthropic");
        }
    }
}

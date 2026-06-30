package com.apimarketplace.agent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the bridge routing logic used by ConversationAgentService.isBridgeProvider().
 * This method decides whether a request goes through the bridge (CLI) or agent-service (API).
 *
 * The logic is mirrored here as a pure function. Keep in sync with:
 * conversation-service/ConversationAgentService.isBridgeProvider(String provider, String model)
 */
@DisplayName("Bridge Routing Logic (isBridgeProvider)")
class BridgeRoutingLogicTest {

    /**
     * Mirror of ConversationAgentService.isBridgeProvider().
     * Must be kept in sync with the source method.
     */
    private static boolean isBridgeProvider(String provider, String model) {
        if (provider != null && !provider.isBlank()) {
            String lower = provider.toLowerCase();
            if ("claude-code".equals(lower) || "codex".equals(lower)) return true;
        }
        // Legacy fallback: existing opus/sonnet model-name detection
        if (model != null) {
            String lower = model.toLowerCase();
            return lower.contains("opus") || lower.contains("sonnet");
        }
        return false;
    }

    // ========== Explicit Bridge Providers ==========

    @Nested
    @DisplayName("Explicit bridge providers")
    class ExplicitBridgeProviderTests {

        @Test
        @DisplayName("claude-code provider → bridge")
        void claudeCodeIsBridge() {
            assertThat(isBridgeProvider("claude-code", "claude-sonnet-4-6")).isTrue();
        }

        @Test
        @DisplayName("codex provider → bridge")
        void codexIsBridge() {
            assertThat(isBridgeProvider("codex", "codex-mini-latest")).isTrue();
        }

        @Test
        @DisplayName("claude-code with any model → bridge (provider takes priority)")
        void claudeCodeWithAnyModel() {
            assertThat(isBridgeProvider("claude-code", "gpt-4o")).isTrue();
            assertThat(isBridgeProvider("claude-code", null)).isTrue();
            assertThat(isBridgeProvider("claude-code", "")).isTrue();
        }

        @Test
        @DisplayName("codex with any model → bridge (provider takes priority)")
        void codexWithAnyModel() {
            assertThat(isBridgeProvider("codex", "gpt-4o")).isTrue();
            assertThat(isBridgeProvider("codex", null)).isTrue();
        }
    }

    // ========== Case Insensitivity ==========

    @Nested
    @DisplayName("Case insensitivity")
    class CaseInsensitivityTests {

        @Test
        @DisplayName("Claude-Code (mixed case) → bridge")
        void mixedCaseClaudeCode() {
            assertThat(isBridgeProvider("Claude-Code", "any")).isTrue();
        }

        @Test
        @DisplayName("CLAUDE-CODE (uppercase) → bridge")
        void upperCaseClaudeCode() {
            assertThat(isBridgeProvider("CLAUDE-CODE", "any")).isTrue();
        }

        @Test
        @DisplayName("CODEX (uppercase) → bridge")
        void upperCaseCodex() {
            assertThat(isBridgeProvider("CODEX", "any")).isTrue();
        }

        @Test
        @DisplayName("Codex (capitalized) → bridge")
        void capitalizedCodex() {
            assertThat(isBridgeProvider("Codex", "any")).isTrue();
        }
    }

    // ========== Non-Bridge Providers ==========

    @Nested
    @DisplayName("Non-bridge providers")
    class NonBridgeProviderTests {

        @Test
        @DisplayName("openai provider → not bridge")
        void openaiIsNotBridge() {
            assertThat(isBridgeProvider("openai", "gpt-4o")).isFalse();
        }

        @Test
        @DisplayName("anthropic provider → not bridge")
        void anthropicIsNotBridge() {
            assertThat(isBridgeProvider("anthropic", "claude-3-haiku")).isFalse();
        }

        @Test
        @DisplayName("google provider → not bridge")
        void googleIsNotBridge() {
            assertThat(isBridgeProvider("google", "gemini-pro")).isFalse();
        }

        @Test
        @DisplayName("mistral provider → not bridge")
        void mistralIsNotBridge() {
            assertThat(isBridgeProvider("mistral", "mistral-large")).isFalse();
        }

        @Test
        @DisplayName("deepseek provider → not bridge")
        void deepseekIsNotBridge() {
            assertThat(isBridgeProvider("deepseek", "deepseek-coder")).isFalse();
        }

        @Test
        @DisplayName("unknown provider → not bridge")
        void unknownIsNotBridge() {
            assertThat(isBridgeProvider("some-new-provider", "some-model")).isFalse();
        }
    }

    // ========== Legacy Model Fallback ==========

    @Nested
    @DisplayName("Legacy model-name fallback")
    class LegacyFallbackTests {

        @Test
        @DisplayName("model containing 'opus' → bridge (legacy)")
        void opusModelIsBridge() {
            assertThat(isBridgeProvider(null, "claude-opus-4-6")).isTrue();
        }

        @Test
        @DisplayName("model containing 'sonnet' → bridge (legacy)")
        void sonnetModelIsBridge() {
            assertThat(isBridgeProvider(null, "claude-sonnet-4-6")).isTrue();
        }

        @Test
        @DisplayName("model with opus anywhere → bridge (legacy)")
        void opusAnywhereIsBridge() {
            assertThat(isBridgeProvider(null, "some-opus-model")).isTrue();
        }

        @Test
        @DisplayName("model with sonnet anywhere → bridge (legacy)")
        void sonnetAnywhereIsBridge() {
            assertThat(isBridgeProvider(null, "some-sonnet-variant")).isTrue();
        }

        @Test
        @DisplayName("legacy fallback is case-insensitive")
        void legacyCaseInsensitive() {
            assertThat(isBridgeProvider(null, "Claude-OPUS-4-6")).isTrue();
            assertThat(isBridgeProvider(null, "Claude-Sonnet-4-6")).isTrue();
        }

        @Test
        @DisplayName("non-opus/sonnet model without provider → not bridge")
        void nonBridgeModelWithoutProvider() {
            assertThat(isBridgeProvider(null, "gpt-4o")).isFalse();
            assertThat(isBridgeProvider(null, "claude-3-haiku")).isFalse();
            assertThat(isBridgeProvider(null, "gemini-pro")).isFalse();
            assertThat(isBridgeProvider(null, "codex-mini-latest")).isFalse();
        }
    }

    // ========== Provider Takes Priority Over Model ==========

    @Nested
    @DisplayName("Provider + model interaction")
    class PriorityTests {

        @Test
        @DisplayName("non-bridge provider with opus model → bridge (model fallback still applies)")
        void nonBridgeProviderWithOpusModel() {
            // Even though provider is "anthropic", model "opus" triggers legacy fallback.
            // In practice this doesn't happen: anthropic provider uses claude-3-haiku etc,
            // opus models are only selected with claude-code provider.
            assertThat(isBridgeProvider("anthropic", "claude-opus-4-6")).isTrue();
        }

        @Test
        @DisplayName("non-bridge provider with sonnet model → bridge (model fallback still applies)")
        void nonBridgeProviderWithSonnetModel() {
            assertThat(isBridgeProvider("anthropic", "claude-sonnet-4-6")).isTrue();
        }

        @Test
        @DisplayName("non-bridge provider with non-bridge model → not bridge")
        void nonBridgeProviderWithNonBridgeModel() {
            assertThat(isBridgeProvider("anthropic", "claude-3-haiku")).isFalse();
            assertThat(isBridgeProvider("openai", "gpt-4o")).isFalse();
        }

        @Test
        @DisplayName("bridge provider with non-bridge model → bridge (provider short-circuits)")
        void bridgeProviderWithNonBridgeModel() {
            assertThat(isBridgeProvider("claude-code", "gpt-4o")).isTrue();
        }
    }

    // ========== Null / Blank Edge Cases ==========

    @Nested
    @DisplayName("Null and blank edge cases")
    class NullBlankTests {

        @Test
        @DisplayName("null provider + null model → not bridge")
        void bothNull() {
            assertThat(isBridgeProvider(null, null)).isFalse();
        }

        @Test
        @DisplayName("empty provider + null model → not bridge")
        void emptyProviderNullModel() {
            assertThat(isBridgeProvider("", null)).isFalse();
        }

        @Test
        @DisplayName("blank provider + null model → not bridge")
        void blankProviderNullModel() {
            assertThat(isBridgeProvider("   ", null)).isFalse();
        }

        @Test
        @DisplayName("null provider + non-bridge model → not bridge")
        void nullProviderNonBridgeModel() {
            assertThat(isBridgeProvider(null, "gpt-4o")).isFalse();
        }

        @Test
        @DisplayName("blank provider + opus model → bridge (falls through to model check)")
        void blankProviderOpusModel() {
            assertThat(isBridgeProvider("", "claude-opus-4-6")).isTrue();
            assertThat(isBridgeProvider("   ", "claude-opus-4-6")).isTrue();
        }

        @Test
        @DisplayName("null provider + empty model → not bridge")
        void nullProviderEmptyModel() {
            assertThat(isBridgeProvider(null, "")).isFalse();
        }
    }
}

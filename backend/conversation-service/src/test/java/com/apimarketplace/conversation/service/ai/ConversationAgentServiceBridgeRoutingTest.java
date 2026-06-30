package com.apimarketplace.conversation.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the bridge routing logic used by ConversationAgentService.isBridgeProvider().
 * This method decides whether a request goes through the bridge (CLI) or agent-service (API).
 *
 * The logic is mirrored here as a pure function to avoid depending on ConversationAgentService
 * constructor dependencies. Keep in sync with the source method at
 * ConversationAgentService.isBridgeProvider(String provider, String model).
 */
@DisplayName("ConversationAgentService - Bridge Routing")
class ConversationAgentServiceBridgeRoutingTest {

    /**
     * Mirror of ConversationAgentService.isBridgeProvider().
     * Must be kept in sync with the source method.
     *
     * @see ConversationAgentService#isBridgeProvider(String, String)
     */
    private static boolean isBridgeProvider(String provider, String model) {
        if (provider == null || provider.isBlank()) return false;
        String lower = provider.toLowerCase();
        return "claude-code".equals(lower) || "codex".equals(lower);
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
        @DisplayName("anthropic provider → not bridge (even with opus/sonnet model)")
        void anthropicIsNotBridge() {
            assertThat(isBridgeProvider("anthropic", "claude-3-haiku")).isFalse();
            assertThat(isBridgeProvider("anthropic", "claude-opus-4-6")).isFalse();
            assertThat(isBridgeProvider("anthropic", "claude-sonnet-4-6")).isFalse();
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

    // ========== Provider + Model Interaction ==========

    @Nested
    @DisplayName("Provider + model interaction")
    class PriorityTests {

        @Test
        @DisplayName("anthropic provider with opus model → NOT bridge (provider-only routing)")
        void anthropicWithOpusModel() {
            assertThat(isBridgeProvider("anthropic", "claude-opus-4-6")).isFalse();
        }

        @Test
        @DisplayName("anthropic provider with sonnet model → NOT bridge (provider-only routing)")
        void anthropicWithSonnetModel() {
            assertThat(isBridgeProvider("anthropic", "claude-sonnet-4-6")).isFalse();
        }

        @Test
        @DisplayName("non-bridge provider with non-bridge model → not bridge")
        void nonBridgeProviderWithNonBridgeModel() {
            assertThat(isBridgeProvider("anthropic", "claude-3-haiku")).isFalse();
            assertThat(isBridgeProvider("openai", "gpt-4o")).isFalse();
        }

        @Test
        @DisplayName("bridge provider with non-bridge model → bridge (provider decides)")
        void bridgeProviderWithNonBridgeModel() {
            assertThat(isBridgeProvider("claude-code", "gpt-4o")).isTrue();
        }
    }

    // ========== Null / Blank Edge Cases ==========

    @Nested
    @DisplayName("Null and blank edge cases")
    class NullBlankTests {

        @Test
        @DisplayName("null provider → not bridge")
        void nullProvider() {
            assertThat(isBridgeProvider(null, null)).isFalse();
            assertThat(isBridgeProvider(null, "claude-opus-4-6")).isFalse();
            assertThat(isBridgeProvider(null, "gpt-4o")).isFalse();
        }

        @Test
        @DisplayName("empty provider → not bridge")
        void emptyProvider() {
            assertThat(isBridgeProvider("", null)).isFalse();
            assertThat(isBridgeProvider("", "claude-opus-4-6")).isFalse();
        }

        @Test
        @DisplayName("blank provider → not bridge")
        void blankProvider() {
            assertThat(isBridgeProvider("   ", null)).isFalse();
            assertThat(isBridgeProvider("   ", "claude-opus-4-6")).isFalse();
        }
    }
}

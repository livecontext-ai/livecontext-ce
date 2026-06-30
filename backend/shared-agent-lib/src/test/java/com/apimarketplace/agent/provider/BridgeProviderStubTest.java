package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Tests for BridgeProviderStub - base class for bridge-based providers
 * (Claude Code CLI, Codex CLI) that appear in the models endpoint
 * but never execute LLM calls directly.
 */
@DisplayName("BridgeProviderStub")
class BridgeProviderStubTest {

    /**
     * Concrete subclass for testing the abstract BridgeProviderStub.
     */
    static class TestBridgeProvider extends BridgeProviderStub {
        TestBridgeProvider(String name, boolean enabled, String modelsStr,
                           int displayOrder) {
            super(name, enabled, modelsStr, displayOrder);
        }
    }

    // ========== Constructor / Model Parsing ==========

    @Nested
    @DisplayName("Constructor & model parsing")
    class ConstructorTests {

        @Test
        @DisplayName("should parse comma-separated models string")
        void shouldParseModels() {
            var provider = new TestBridgeProvider("test", true, "model-a,model-b,model-c", 1);

            assertThat(provider.getSupportedModels())
                    .containsExactly("model-a", "model-b", "model-c");
        }

        @Test
        @DisplayName("should handle single model")
        void shouldHandleSingleModel() {
            var provider = new TestBridgeProvider("test", true, "only-model", 1);

            assertThat(provider.getSupportedModels()).containsExactly("only-model");
        }

        @Test
        @DisplayName("should return empty list for null models string")
        void shouldHandleNullModels() {
            var provider = new TestBridgeProvider("test", true,
                    null, 1);

            assertThat(provider.getSupportedModels()).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for empty models string")
        void shouldHandleEmptyModels() {
            var provider = new TestBridgeProvider("test", true, "", 1);

            assertThat(provider.getSupportedModels()).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for blank models string")
        void shouldHandleBlankModels() {
            var provider = new TestBridgeProvider("test", true, "   ", 1);

            assertThat(provider.getSupportedModels()).isEmpty();
        }

        @Test
        @DisplayName("should preserve model names with whitespace around commas")
        void shouldPreserveWhitespaceInModelNames() {
            // split(",") doesn't trim - verify current behavior
            var provider = new TestBridgeProvider("test", true, "model-a, model-b", 1);

            assertThat(provider.getSupportedModels())
                    .containsExactly("model-a", " model-b");
        }
    }

    // ========== Provider Identity ==========

    @Nested
    @DisplayName("Provider identity")
    class IdentityTests {

        @Test
        @DisplayName("should return correct provider name")
        void shouldReturnProviderName() {
            var provider = new TestBridgeProvider("claude-code", true, "model-a", 1);
            assertThat(provider.getProviderName()).isEqualTo("claude-code");
        }

        @Test
        @DisplayName("should return first model as default")
        void shouldReturnDefaultModel() {
            var provider = new TestBridgeProvider("test", true, "model-a,model-b", 1);
            assertThat(provider.getDefaultModel()).isEqualTo("model-a");
        }

        @Test
        @DisplayName("should return correct display order")
        void shouldReturnDisplayOrder() {
            var provider = new TestBridgeProvider("test", true, "model-a", 42);
            assertThat(provider.getDisplayOrder()).isEqualTo(42);
        }
    }

    // ========== Configuration State ==========

    @Nested
    @DisplayName("Configuration state")
    class ConfigurationTests {

        @Test
        @DisplayName("should be configured when enabled")
        void shouldBeConfiguredWhenEnabled() {
            var provider = new TestBridgeProvider("test", true, "model-a", 1);
            assertThat(provider.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("should not be configured when disabled")
        void shouldNotBeConfiguredWhenDisabled() {
            var provider = new TestBridgeProvider("test", false, "model-a", 1);
            assertThat(provider.isConfigured()).isFalse();
        }
    }

    // ========== Capabilities ==========

    @Nested
    @DisplayName("Capabilities")
    class CapabilityTests {

        @Test
        @DisplayName("should support streaming")
        void shouldSupportStreaming() {
            var provider = new TestBridgeProvider("test", true, "model-a", 1);
            assertThat(provider.supportsStreaming()).isTrue();
        }

        @Test
        @DisplayName("should support tool calling")
        void shouldSupportToolCalling() {
            var provider = new TestBridgeProvider("test", true, "model-a", 1);
            assertThat(provider.supportsToolCalling()).isTrue();
        }
    }

    // ========== supportsModel() ==========

    @Nested
    @DisplayName("supportsModel()")
    class SupportsModelTests {

        @Test
        @DisplayName("should return true for supported model")
        void shouldSupportListedModel() {
            var provider = new TestBridgeProvider("test", true, "model-a,model-b", 1);

            assertThat(provider.supportsModel("model-a")).isTrue();
            assertThat(provider.supportsModel("model-b")).isTrue();
        }

        @Test
        @DisplayName("should return false for unsupported model")
        void shouldNotSupportUnlistedModel() {
            var provider = new TestBridgeProvider("test", true, "model-a", 1);

            assertThat(provider.supportsModel("model-x")).isFalse();
        }

        @Test
        @DisplayName("should return false when models list is empty")
        void shouldNotSupportAnyWhenEmpty() {
            var provider = new TestBridgeProvider("test", true, "", 1);

            assertThat(provider.supportsModel("model-a")).isFalse();
        }
    }

    // ========== Execution Methods (must throw) ==========

    @Nested
    @DisplayName("Execution methods (must throw UnsupportedOperationException)")
    class ExecutionTests {

        private final TestBridgeProvider provider = new TestBridgeProvider(
                "test-bridge", true, "model-a", 1);

        @Test
        @DisplayName("complete() should throw UnsupportedOperationException")
        void completeShouldThrow() {
            CompletionRequest request = mock(CompletionRequest.class);

            assertThatThrownBy(() -> provider.complete(request))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("test-bridge")
                    .hasMessageContaining("does not support direct LLM calls")
                    .hasMessageContaining("bridge server");
        }

        @Test
        @DisplayName("completeStreaming() should throw UnsupportedOperationException")
        void completeStreamingShouldThrow() {
            CompletionRequest request = mock(CompletionRequest.class);
            StreamingCallback callback = mock(StreamingCallback.class);

            assertThatThrownBy(() -> provider.completeStreaming(request, callback))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("test-bridge")
                    .hasMessageContaining("does not support direct LLM calls");
        }

        @Test
        @DisplayName("streamReactive() should emit error with UnsupportedOperationException")
        void streamReactiveShouldEmitError() {
            CompletionRequest request = mock(CompletionRequest.class);

            Flux<StreamingEvent> flux = provider.streamReactive(request);
            AtomicReference<Throwable> captured = new AtomicReference<>();

            flux.subscribe(
                    event -> {},
                    captured::set
            );

            assertThat(captured.get())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("test-bridge")
                    .hasMessageContaining("does not support direct LLM calls");
        }

        @Test
        @DisplayName("exception messages should include provider name for debugging")
        void exceptionMessagesShouldIncludeProviderName() {
            var namedProvider = new TestBridgeProvider("claude-code", true, "model-a", 1);
            CompletionRequest request = mock(CompletionRequest.class);

            assertThatThrownBy(() -> namedProvider.complete(request))
                    .hasMessageContaining("claude-code");
        }
    }

    // ========== Concrete Implementations ==========

    @Nested
    @DisplayName("BridgeClaudeCodeProvider")
    class ClaudeCodeProviderTests {

        @Test
        @DisplayName("should have correct provider name")
        void shouldHaveCorrectName() {
            var provider = new BridgeClaudeCodeProvider(true,
                    "claude-opus-4-6,claude-sonnet-4-6", 1);

            assertThat(provider.getProviderName()).isEqualTo("claude-code");
        }

        @Test
        @DisplayName("should parse models from config string")
        void shouldParseModels() {
            var provider = new BridgeClaudeCodeProvider(true,
                    "claude-opus-4-6,claude-sonnet-4-6", 1);

            assertThat(provider.getSupportedModels())
                    .containsExactly("claude-opus-4-6", "claude-sonnet-4-6");
        }

        @Test
        @DisplayName("should use first model as default")
        void shouldUseDefaultModel() {
            var provider = new BridgeClaudeCodeProvider(true,
                    "claude-opus-4-6,claude-sonnet-4-6", 1);

            assertThat(provider.getDefaultModel()).isEqualTo("claude-opus-4-6");
        }

        @Test
        @DisplayName("should respect enabled flag")
        void shouldRespectEnabledFlag() {
            var enabled = new BridgeClaudeCodeProvider(true, "m", 1);
            var disabled = new BridgeClaudeCodeProvider(false, "m", 1);

            assertThat(enabled.isConfigured()).isTrue();
            assertThat(disabled.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("should handle empty models string (disabled provider)")
        void shouldHandleEmptyModels() {
            var provider = new BridgeClaudeCodeProvider(false, "", 99);

            assertThat(provider.getSupportedModels()).isEmpty();
            assertThat(provider.isConfigured()).isFalse();
        }
    }

    @Nested
    @DisplayName("BridgeCodexProvider")
    class CodexProviderTests {

        @Test
        @DisplayName("should have correct provider name")
        void shouldHaveCorrectName() {
            var provider = new BridgeCodexProvider(true,
                    "codex-mini-latest", 3);

            assertThat(provider.getProviderName()).isEqualTo("codex");
        }

        @Test
        @DisplayName("should parse models from config string")
        void shouldParseModels() {
            var provider = new BridgeCodexProvider(true,
                    "codex-mini-latest", 3);

            assertThat(provider.getSupportedModels()).containsExactly("codex-mini-latest");
        }

        @Test
        @DisplayName("should use default model from config")
        void shouldUseDefaultModel() {
            var provider = new BridgeCodexProvider(true,
                    "codex-mini-latest", 3);

            assertThat(provider.getDefaultModel()).isEqualTo("codex-mini-latest");
        }

        @Test
        @DisplayName("should respect enabled flag")
        void shouldRespectEnabledFlag() {
            var enabled = new BridgeCodexProvider(true, "m", 1);
            var disabled = new BridgeCodexProvider(false, "m", 1);

            assertThat(enabled.isConfigured()).isTrue();
            assertThat(disabled.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("should support display order configuration")
        void shouldSupportDisplayOrder() {
            var provider = new BridgeCodexProvider(true, "codex-mini-latest",
                    3);

            assertThat(provider.getDisplayOrder()).isEqualTo(3);
        }
    }

    // ========== LLMProvider Interface Compliance ==========

    @Nested
    @DisplayName("LLMProvider interface compliance")
    class InterfaceComplianceTests {

        @Test
        @DisplayName("bridge providers implement LLMProvider interface")
        void shouldImplementLLMProvider() {
            LLMProvider claudeCode = new BridgeClaudeCodeProvider(true,
                    "claude-sonnet-4-6", 1);
            LLMProvider codex = new BridgeCodexProvider(true,
                    "codex-mini-latest", 3);

            assertThat(claudeCode).isInstanceOf(LLMProvider.class);
            assertThat(codex).isInstanceOf(LLMProvider.class);
        }

        @Test
        @DisplayName("bridge providers can be used in a List<LLMProvider>")
        void shouldWorkInProviderList() {
            List<LLMProvider> providers = List.of(
                    new BridgeClaudeCodeProvider(true, "m1", 1),
                    new BridgeCodexProvider(true, "m2", 3)
            );

            assertThat(providers).hasSize(2);
            assertThat(providers.stream().map(LLMProvider::getProviderName))
                    .containsExactly("claude-code", "codex");
        }
    }
}

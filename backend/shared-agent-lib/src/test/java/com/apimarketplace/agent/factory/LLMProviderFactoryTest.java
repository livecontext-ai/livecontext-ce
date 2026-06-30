package com.apimarketplace.agent.factory;

import com.apimarketplace.agent.config.ModelPricingConfig;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.LLMProviderException;
import com.apimarketplace.agent.provider.OpenAICompatibleProvider;
import com.apimarketplace.agent.provider.OpenAICompatibleProviderFactory;
import com.apimarketplace.agent.ratelimit.ProviderRateLimiter;
import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.apimarketplace.agent.provider.BridgeClaudeCodeProvider;
import com.apimarketplace.agent.provider.BridgeCodexProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tests for LLMProviderFactory - factory for LLM providers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LLMProviderFactory")
class LLMProviderFactoryTest {

    @Mock
    private LLMProvider openaiProvider;

    @Mock
    private LLMProvider anthropicProvider;

    private LLMProviderFactory factory;

    @BeforeEach
    void setUp() {
        when(openaiProvider.getProviderName()).thenReturn("openai");
        when(anthropicProvider.getProviderName()).thenReturn("anthropic");

        factory = new LLMProviderFactory(List.of(openaiProvider, anthropicProvider));
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should register providers from list")
        void shouldRegisterProviders() {
            assertThat(factory.getAvailableProviderNames()).containsExactlyInAnyOrder("openai", "anthropic");
        }

        @Test
        @DisplayName("should handle null provider list")
        void shouldHandleNullProviderList() {
            LLMProviderFactory emptyFactory = new LLMProviderFactory(null);
            assertThat(emptyFactory.getAvailableProviderNames()).isEmpty();
        }

        @Test
        @DisplayName("should handle empty provider list")
        void shouldHandleEmptyProviderList() {
            LLMProviderFactory emptyFactory = new LLMProviderFactory(List.of());
            assertThat(emptyFactory.getAvailableProviderNames()).isEmpty();
        }

        @Test
        @DisplayName("should normalize provider names to lowercase")
        void shouldNormalizeProviderNames() {
            LLMProvider mixedCase = mock(LLMProvider.class);
            when(mixedCase.getProviderName()).thenReturn("OpenAI");

            LLMProviderFactory f = new LLMProviderFactory(List.of(mixedCase));
            assertThat(f.getAvailableProviderNames()).contains("openai");
        }
    }

    @Nested
    @DisplayName("getProvider()")
    class GetProviderTests {

        @Test
        @DisplayName("should return provider by name")
        void shouldReturnProviderByName() {
            LLMProvider result = factory.getProvider("openai");
            assertThat(result).isEqualTo(openaiProvider);
        }

        @Test
        @DisplayName("should be case-insensitive")
        void shouldBeCaseInsensitive() {
            LLMProvider result = factory.getProvider("OpenAI");
            assertThat(result).isEqualTo(openaiProvider);
        }

        @Test
        @DisplayName("should throw when provider not found")
        void shouldThrowWhenNotFound() {
            assertThatThrownBy(() -> factory.getProvider("unknown"))
                    .isInstanceOf(LLMProviderException.class)
                    .hasMessageContaining("Provider not found");
        }
    }

    @Nested
    @DisplayName("findProvider()")
    class FindProviderTests {

        @Test
        @DisplayName("should return Optional with provider when found")
        void shouldReturnOptionalWithProvider() {
            Optional<LLMProvider> result = factory.findProvider("anthropic");
            assertThat(result).isPresent().contains(anthropicProvider);
        }

        @Test
        @DisplayName("should return empty Optional when not found")
        void shouldReturnEmptyOptional() {
            Optional<LLMProvider> result = factory.findProvider("unknown");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should be case-insensitive")
        void shouldBeCaseInsensitive() {
            Optional<LLMProvider> result = factory.findProvider("ANTHROPIC");
            assertThat(result).isPresent();
        }
    }

    @Nested
    @DisplayName("getFirstConfiguredProvider()")
    class GetFirstConfiguredTests {

        @Test
        @DisplayName("should return first configured provider")
        void shouldReturnFirstConfigured() {
            when(openaiProvider.isConfigured()).thenReturn(false);
            when(anthropicProvider.isConfigured()).thenReturn(true);

            Optional<LLMProvider> result = factory.getFirstConfiguredProvider();
            assertThat(result).isPresent().contains(anthropicProvider);
        }

        @Test
        @DisplayName("should return empty when none configured")
        void shouldReturnEmptyWhenNoneConfigured() {
            when(openaiProvider.isConfigured()).thenReturn(false);
            when(anthropicProvider.isConfigured()).thenReturn(false);

            Optional<LLMProvider> result = factory.getFirstConfiguredProvider();
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getConfiguredProviders()")
    class GetConfiguredProvidersTests {

        @Test
        @DisplayName("should return only configured providers")
        void shouldReturnOnlyConfigured() {
            when(openaiProvider.isConfigured()).thenReturn(true);
            when(anthropicProvider.isConfigured()).thenReturn(false);

            List<LLMProvider> result = factory.getConfiguredProviders();
            assertThat(result).containsExactly(openaiProvider);
        }

        @Test
        @DisplayName("should return all when all configured")
        void shouldReturnAllWhenAllConfigured() {
            when(openaiProvider.isConfigured()).thenReturn(true);
            when(anthropicProvider.isConfigured()).thenReturn(true);

            List<LLMProvider> result = factory.getConfiguredProviders();
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should return empty list when none configured")
        void shouldReturnEmptyWhenNone() {
            when(openaiProvider.isConfigured()).thenReturn(false);
            when(anthropicProvider.isConfigured()).thenReturn(false);

            List<LLMProvider> result = factory.getConfiguredProviders();
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("isProviderAvailable()")
    class IsProviderAvailableTests {

        @Test
        @DisplayName("should return true when provider exists and is configured")
        void shouldReturnTrueWhenAvailable() {
            when(openaiProvider.isConfigured()).thenReturn(true);

            assertThat(factory.isProviderAvailable("openai")).isTrue();
        }

        @Test
        @DisplayName("should return false when provider exists but not configured")
        void shouldReturnFalseWhenNotConfigured() {
            when(openaiProvider.isConfigured()).thenReturn(false);

            assertThat(factory.isProviderAvailable("openai")).isFalse();
        }

        @Test
        @DisplayName("should return false when provider does not exist")
        void shouldReturnFalseWhenNotExists() {
            assertThat(factory.isProviderAvailable("unknown")).isFalse();
        }
    }

    @Nested
    @DisplayName("getProviderForModel()")
    class GetProviderForModelTests {

        @Test
        @DisplayName("should return provider that supports the model")
        void shouldReturnProviderForModel() {
            when(openaiProvider.supportsModel("gpt-4")).thenReturn(true);

            Optional<LLMProvider> result = factory.getProviderForModel("gpt-4");
            assertThat(result).isPresent().contains(openaiProvider);
        }

        @Test
        @DisplayName("should return empty when no provider supports the model")
        void shouldReturnEmptyWhenNoSupport() {
            when(openaiProvider.supportsModel("unknown-model")).thenReturn(false);
            when(anthropicProvider.supportsModel("unknown-model")).thenReturn(false);

            Optional<LLMProvider> result = factory.getProviderForModel("unknown-model");
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAllModelsInfo()")
    class GetAllModelsInfoTests {

        @Test
        @DisplayName("should return structured models info")
        void shouldReturnModelsInfo() {
            when(openaiProvider.isConfigured()).thenReturn(true);
            when(openaiProvider.getDisplayOrder()).thenReturn(1);
            when(openaiProvider.getDefaultModel()).thenReturn("gpt-4");
            when(openaiProvider.supportsStreaming()).thenReturn(true);
            when(openaiProvider.supportsToolCalling()).thenReturn(true);
            when(openaiProvider.getSupportedModels()).thenReturn(List.of("gpt-4", "gpt-3.5-turbo"));

            when(anthropicProvider.isConfigured()).thenReturn(false);

            Map<String, Object> result = factory.getAllModelsInfo();

            assertThat(result).containsKey("providers");
            assertThat(result).containsKey("defaultProvider");
            assertThat(result).containsKey("defaultModel");
            assertThat(result.get("defaultProvider")).isEqualTo("openai");
            assertThat(result.get("defaultModel")).isEqualTo("gpt-4");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> providers = (List<Map<String, Object>>) result.get("providers");
            assertThat(providers).hasSize(1); // Only openai is configured
        }

        @Test
        @DisplayName("should return null defaults when no providers configured")
        void shouldReturnNullDefaultsWhenEmpty() {
            when(openaiProvider.isConfigured()).thenReturn(false);
            when(anthropicProvider.isConfigured()).thenReturn(false);

            Map<String, Object> result = factory.getAllModelsInfo();

            assertThat(result.get("defaultProvider")).isNull();
            assertThat(result.get("defaultModel")).isNull();
        }

        @Test
        @DisplayName("should sort providers by display order")
        void shouldSortByDisplayOrder() {
            when(openaiProvider.isConfigured()).thenReturn(true);
            when(openaiProvider.getDisplayOrder()).thenReturn(2);
            when(openaiProvider.getSupportedModels()).thenReturn(List.of("gpt-4"));
            when(openaiProvider.getDefaultModel()).thenReturn("gpt-4");
            when(openaiProvider.supportsStreaming()).thenReturn(true);
            when(openaiProvider.supportsToolCalling()).thenReturn(true);

            when(anthropicProvider.isConfigured()).thenReturn(true);
            when(anthropicProvider.getDisplayOrder()).thenReturn(1);
            when(anthropicProvider.getSupportedModels()).thenReturn(List.of("claude-3"));
            when(anthropicProvider.getDefaultModel()).thenReturn("claude-3");
            when(anthropicProvider.supportsStreaming()).thenReturn(true);
            when(anthropicProvider.supportsToolCalling()).thenReturn(true);

            Map<String, Object> result = factory.getAllModelsInfo();

            // Anthropic has lower display order so it should be default
            assertThat(result.get("defaultProvider")).isEqualTo("anthropic");
        }
    }

    @Nested
    @DisplayName("Bridge provider integration")
    class BridgeProviderIntegrationTests {

        @Test
        @DisplayName("should auto-discover bridge provider stubs")
        void shouldAutoDiscoverBridgeProviders() {
            BridgeClaudeCodeProvider claudeCode = new BridgeClaudeCodeProvider(
                    true, "claude-opus-4-6,claude-sonnet-4-6", 1);
            BridgeCodexProvider codex = new BridgeCodexProvider(
                    true, "codex-mini-latest", 3);

            LLMProviderFactory bridgeFactory = new LLMProviderFactory(
                    List.of(openaiProvider, anthropicProvider, claudeCode, codex));

            assertThat(bridgeFactory.getAvailableProviderNames())
                    .containsExactlyInAnyOrder("openai", "anthropic", "claude-code", "codex");
        }

        @Test
        @DisplayName("should find bridge provider by name")
        void shouldFindBridgeProvider() {
            BridgeClaudeCodeProvider claudeCode = new BridgeClaudeCodeProvider(
                    true, "claude-sonnet-4-6", 1);

            LLMProviderFactory f = new LLMProviderFactory(List.of(claudeCode));

            assertThat(f.findProvider("claude-code")).isPresent();
            assertThat(f.getProvider("claude-code").getProviderName()).isEqualTo("claude-code");
        }

        @Test
        @DisplayName("should include bridge providers in configured providers list")
        void shouldIncludeInConfiguredProviders() {
            BridgeClaudeCodeProvider claudeCode = new BridgeClaudeCodeProvider(
                    true, "m", 1);
            BridgeCodexProvider codex = new BridgeCodexProvider(
                    false, "m", 3);

            LLMProviderFactory f = new LLMProviderFactory(List.of(claudeCode, codex));

            assertThat(f.getConfiguredProviders()).hasSize(1);
            assertThat(f.getConfiguredProviders().get(0).getProviderName()).isEqualTo("claude-code");
        }

        @Test
        @DisplayName("should find provider for bridge model")
        void shouldFindProviderForBridgeModel() {
            BridgeClaudeCodeProvider claudeCode = new BridgeClaudeCodeProvider(
                    true, "claude-opus-4-6,claude-sonnet-4-6", 1);

            LLMProviderFactory f = new LLMProviderFactory(List.of(claudeCode));

            assertThat(f.getProviderForModel("claude-opus-4-6")).isPresent();
            assertThat(f.getProviderForModel("claude-opus-4-6").get().getProviderName())
                    .isEqualTo("claude-code");
        }

        @Test
        @DisplayName("should include bridge providers in getAllModelsInfo")
        void shouldIncludeInModelsInfo() {
            BridgeClaudeCodeProvider claudeCode = new BridgeClaudeCodeProvider(
                    true, "claude-sonnet-4-6", 1);

            LLMProviderFactory f = new LLMProviderFactory(List.of(claudeCode));

            Map<String, Object> info = f.getAllModelsInfo();

            assertThat(info.get("defaultProvider")).isEqualTo("claude-code");
            assertThat(info.get("defaultModel")).isEqualTo("claude-sonnet-4-6");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> providers = (List<Map<String, Object>>) info.get("providers");
            assertThat(providers).hasSize(1);
            assertThat(providers.get(0).get("name")).isEqualTo("claude-code");
            assertThat(providers.get(0).get("supportsStreaming")).isEqualTo(true);
            assertThat(providers.get(0).get("supportsToolCalling")).isEqualTo(true);
        }

        @Test
        @DisplayName("bridge providers should be sorted by display order with API providers")
        void shouldSortWithApiProviders() {
            when(openaiProvider.isConfigured()).thenReturn(true);
            when(openaiProvider.getDisplayOrder()).thenReturn(2);
            when(openaiProvider.getSupportedModels()).thenReturn(List.of("gpt-4o"));
            when(openaiProvider.getDefaultModel()).thenReturn("gpt-4o");
            when(openaiProvider.supportsStreaming()).thenReturn(true);
            when(openaiProvider.supportsToolCalling()).thenReturn(true);

            BridgeClaudeCodeProvider claudeCode = new BridgeClaudeCodeProvider(
                    true, "claude-sonnet-4-6", 1);

            LLMProviderFactory f = new LLMProviderFactory(List.of(openaiProvider, claudeCode));

            Map<String, Object> info = f.getAllModelsInfo();

            // claude-code has displayOrder 1, openai has 2 → claude-code is default
            assertThat(info.get("defaultProvider")).isEqualTo("claude-code");
        }
    }

    @Nested
    @DisplayName("formatModelName()")
    class FormatModelNameTests {

        /**
         * Access the private formatModelName method via reflection.
         */
        private String formatModelName(String modelId) throws Exception {
            Method method = LLMProviderFactory.class
                    .getDeclaredMethod("formatModelName", String.class);
            method.setAccessible(true);
            return (String) method.invoke(factory, modelId);
        }

        @Test
        @DisplayName("should format claude model with version segments")
        void shouldFormatClaudeModel() throws Exception {
            assertThat(formatModelName("claude-sonnet-4-6")).isEqualTo("Claude Sonnet 4.6");
            assertThat(formatModelName("claude-opus-4-6")).isEqualTo("Claude Opus 4.6");
        }

        @Test
        @DisplayName("should format GPT models")
        void shouldFormatGptModels() throws Exception {
            assertThat(formatModelName("gpt-4o")).isEqualTo("Gpt 4o");
            assertThat(formatModelName("gpt-4o-mini")).isEqualTo("Gpt 4o Mini");
        }

        @Test
        @DisplayName("should handle null input")
        void shouldHandleNull() throws Exception {
            assertThat(formatModelName(null)).isEmpty();
        }

        @Test
        @DisplayName("should format codex models")
        void shouldFormatCodexModels() throws Exception {
            assertThat(formatModelName("codex-mini-latest")).isEqualTo("Codex Mini Latest");
        }

        @Test
        @DisplayName("should convert multiple version segments")
        void shouldConvertMultipleVersionSegments() throws Exception {
            assertThat(formatModelName("model-1-5-turbo")).isEqualTo("Model 1.5 Turbo");
        }
    }

    @Nested
    @DisplayName("registerOpenAICompatibleProviders() - dependency injection")
    class OpenAICompatibleInjectionTests {

        @Test
        @DisplayName("should inject credentialResolver and rateLimiter into OpenAI-compatible providers")
        void shouldInjectDependencies() throws Exception {
            OpenAICompatibleProvider zaiProvider = new OpenAICompatibleProvider(
                    "zai", "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                    "", List.of("glm-5"), 10);

            OpenAICompatibleProviderFactory compatibleFactory = mock(OpenAICompatibleProviderFactory.class);
            when(compatibleFactory.createProviders()).thenReturn(List.of(zaiProvider));

            LlmCredentialResolver resolver = mock(LlmCredentialResolver.class);
            ProviderRateLimiter limiter = mock(ProviderRateLimiter.class);

            LLMProviderFactory f = new LLMProviderFactory(List.of());

            // Inject dependencies via reflection (simulating Spring @Autowired)
            setField(f, "openAICompatibleProviderFactory", compatibleFactory);
            setField(f, "credentialResolver", resolver);
            setField(f, "rateLimiter", limiter);

            f.registerOpenAICompatibleProviders();

            // Verify provider was registered
            assertThat(f.findProvider("zai")).isPresent();

            // Verify credentialResolver was injected (check via reflection on the provider)
            Field crField = findField(zaiProvider.getClass(), "credentialResolver");
            crField.setAccessible(true);
            assertThat(crField.get(zaiProvider)).isSameAs(resolver);

            Field rlField = findField(zaiProvider.getClass(), "rateLimiter");
            rlField.setAccessible(true);
            assertThat(rlField.get(zaiProvider)).isSameAs(limiter);
        }

        private void setField(Object target, String fieldName, Object value) throws Exception {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        }

        private Field findField(Class<?> clazz, String fieldName) {
            while (clazz != null) {
                try {
                    return clazz.getDeclaredField(fieldName);
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            throw new RuntimeException("Field not found: " + fieldName);
        }
    }
}

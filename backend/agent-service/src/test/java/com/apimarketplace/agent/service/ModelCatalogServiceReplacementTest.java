package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The replacement intake of {@code saveOverride} (V515): the model a disabled model's runs
 * execute on instead. A bad pair must be refused at save time, because at run time it would
 * silently fall through to the platform default.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService.saveOverride - replacement while disabled")
class ModelCatalogServiceReplacementTest {

    @Mock private ModelConfigOverrideRepository repository;
    @Mock private ModelCategorySettingsRepository categoryRepository;
    @Mock private LLMProviderFactory llmProviderFactory;
    @Mock private LlmCredentialRepository credentialRepository;
    @Mock private CachedModelRateLimitProvider cachedRateLimitProvider;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private ModelCatalogService service;
    private ModelConfigOverrideEntity row;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(
                repository, categoryRepository, llmProviderFactory, credentialRepository,
                cachedRateLimitProvider, "", authPricingSyncClient);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        row = new ModelConfigOverrideEntity();
        row.setProvider("anthropic");
        row.setModelId("claude-opus-4-8");
        row.setDisplayName("Opus 4.8");
        lenient().when(repository.findByProviderAndModelId("anthropic", "claude-opus-4-8"))
                .thenReturn(Optional.of(row));
    }

    private static ModelConfigOverrideEntity input(String provider, String model, boolean explicitlySet) {
        ModelConfigOverrideEntity in = new ModelConfigOverrideEntity();
        in.setProvider("anthropic");
        in.setModelId("claude-opus-4-8");
        in.setReplacementProvider(provider);
        in.setReplacementModel(model);
        in.setReplacementExplicitlySet(explicitlySet);
        return in;
    }

    private void knownModel(String provider, String model) {
        ModelConfigOverrideEntity target = new ModelConfigOverrideEntity();
        target.setProvider(provider);
        target.setModelId(model);
        when(repository.findByProviderAndModelId(provider, model)).thenReturn(Optional.of(target));
    }

    @Test
    @DisplayName("a known replacement is stored, trimmed")
    void knownReplacementStored() {
        knownModel("anthropic", "claude-opus-4-9");

        service.saveOverride(input(" anthropic ", "claude-opus-4-9 ", true));

        assertThat(row.getReplacementProvider()).isEqualTo("anthropic");
        assertThat(row.getReplacementModel()).isEqualTo("claude-opus-4-9");
    }

    @Test
    @DisplayName("both blank clears it, back to the platform default")
    void bothBlankClears() {
        row.setReplacementProvider("anthropic");
        row.setReplacementModel("claude-opus-4-9");

        service.saveOverride(input(null, "", true));

        assertThat(row.getReplacementProvider()).isNull();
        assertThat(row.getReplacementModel()).isNull();
    }

    @Test
    @DisplayName("keys absent leave the stored replacement alone (an unrelated edit must not clear it)")
    void absentKeysPreserve() {
        row.setReplacementProvider("anthropic");
        row.setReplacementModel("claude-opus-4-9");

        service.saveOverride(input(null, null, false));

        assertThat(row.getReplacementModel()).isEqualTo("claude-opus-4-9");
    }

    @Test
    @DisplayName("half a pair is refused (400) and nothing is saved")
    void halfPairRefused() {
        assertThatThrownBy(() -> service.saveOverride(input("anthropic", null, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a model cannot replace itself")
    void selfReplacementRefused() {
        assertThatThrownBy(() -> service.saveOverride(input("anthropic", "claude-opus-4-8", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own replacement");
    }

    @Test
    @DisplayName("on cloud a CLI bridge is refused: non-admin runs swapped onto it would hit the bridge access check")
    void cloudRefusesBridgeReplacement() {
        assertThatThrownBy(() -> service.saveOverride(input("claude-code", "claude-opus-4-9", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLI bridge");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("self-hosted (CE) accepts a CLI bridge replacement: the install owns its bridge")
    void ceAcceptsBridgeReplacement() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "authMode", "embedded");
        knownModel("claude-code", "claude-opus-4-9");

        service.saveOverride(input("claude-code", "claude-opus-4-9", true));

        assertThat(row.getReplacementProvider()).isEqualTo("claude-code");
    }

    @Test
    @DisplayName("an unknown model is refused instead of silently falling to the default on every run")
    void unknownReplacementRefused() {
        when(repository.findByProviderAndModelId("openai", "gpt-typo")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveOverride(input("openai", "gpt-typo", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown replacement model: openai:gpt-typo");
        verify(repository, never()).save(any());
    }
}

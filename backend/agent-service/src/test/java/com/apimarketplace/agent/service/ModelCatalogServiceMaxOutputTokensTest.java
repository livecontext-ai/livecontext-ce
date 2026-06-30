package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.ModelCatalogService.AvailableModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Verifies the flat model catalog surfaces each model's {@code maxOutputTokens}
 * (the provider's real output ceiling) so the agent execution paths can clamp a
 * high platform default down to what the model accepts (via
 * {@link com.apimarketplace.agent.config.MaxTokensClamp}). Without this field the
 * clamp can't distinguish a 128K-output model from DeepSeek-chat's 8192 cap, and
 * a 16000 default would 400 the low-cap model.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - maxOutputTokens in flat catalog")
class ModelCatalogServiceMaxOutputTokensTest {

    @Mock private ModelConfigOverrideRepository repository;
    @Mock private ModelCategorySettingsRepository categoryRepository;
    @Mock private LLMProviderFactory llmProviderFactory;
    @Mock private LlmCredentialRepository credentialRepository;
    @Mock private CachedModelRateLimitProvider cachedRateLimitProvider;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(
                repository, categoryRepository, llmProviderFactory, credentialRepository,
                cachedRateLimitProvider, "", authPricingSyncClient);
    }

    private Map<String, Object> model(String id, Integer maxOutputTokens) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (maxOutputTokens != null) {
            m.put("maxOutputTokens", maxOutputTokens);
        }
        return m;
    }

    private Map<String, Object> provider(String name, Map<String, Object> model) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("configured", true); // env key present → kept by the availability filter
        p.put("models", new ArrayList<>(List.of(model)));
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> adminBase() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new ArrayList<>(List.of(
                provider("deepseek", model("deepseek-chat", 8192)),   // real low cap
                provider("anthropic", model("claude-opus", null))))); // no cap in catalog
        base.put("defaultProvider", "anthropic");
        base.put("defaultModel", "claude-opus");
        return base;
    }

    private void stubCatalog() {
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(adminBase());
        lenient().when(credentialRepository.hasDbKey("deepseek")).thenReturn(false);
        lenient().when(credentialRepository.hasDbKey("anthropic")).thenReturn(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());
    }

    @Test
    @DisplayName("flat AvailableModel carries the catalog maxOutputTokens (null when absent)")
    void flatCatalogCarriesMaxOutputTokens() {
        stubCatalog();

        List<AvailableModel> models = service.listAvailableModels();

        AvailableModel deepseek = models.stream()
                .filter(m -> "deepseek-chat".equals(m.modelId())).findFirst().orElseThrow();
        AvailableModel capless = models.stream()
                .filter(m -> "claude-opus".equals(m.modelId())).findFirst().orElseThrow();
        assertThat(deepseek.maxOutputTokens()).isEqualTo(8192);
        assertThat(capless.maxOutputTokens()).isNull();
    }

    @Test
    @DisplayName("resolveMaxOutputTokens returns the cap, or null for capless / unknown / null inputs")
    void resolveMaxOutputTokens() {
        stubCatalog();

        assertThat(service.resolveMaxOutputTokens("deepseek", "deepseek-chat")).isEqualTo(8192);
        assertThat(service.resolveMaxOutputTokens("anthropic", "claude-opus")).isNull(); // present, no cap
        assertThat(service.resolveMaxOutputTokens("openai", "gpt-x")).isNull();          // unknown model
        assertThat(service.resolveMaxOutputTokens(null, "deepseek-chat")).isNull();      // null guard
    }
}

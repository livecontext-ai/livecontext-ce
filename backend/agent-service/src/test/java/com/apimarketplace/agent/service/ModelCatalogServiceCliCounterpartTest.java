package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.factory.BridgeAvailabilityFilter;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The admin Models panel turns {@code cliBridgeProvider} into a ONE-CLICK
 * execution link (billed price kept, run dispatched to the CLI subscription), so
 * the stamp must be exact: present only where that CLI really routes the model
 * id, absent everywhere else. A false positive would offer a link the CLI
 * rejects at dispatch; a false negative hides the feature for a model that
 * qualifies.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService.getEffectiveModelList - CLI counterpart stamp")
class ModelCatalogServiceCliCounterpartTest {

    @Mock private ModelConfigOverrideRepository repository;
    @Mock private ModelCategorySettingsRepository categoryRepository;
    @Mock private LLMProviderFactory llmProviderFactory;
    @Mock private LlmCredentialRepository credentialRepository;
    @Mock private CachedModelRateLimitProvider cachedRateLimitProvider;
    @Mock private AuthPricingSyncClient authPricingSyncClient;
    @Mock private BridgeAvailabilityFilter bridgeAvailabilityFilter;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(
                repository, categoryRepository, llmProviderFactory, credentialRepository,
                cachedRateLimitProvider, "", authPricingSyncClient);
    }

    private Map<String, Object> model(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        return m;
    }

    private Map<String, Object> provider(String name, String... modelIds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("configured", true);
        List<Map<String, Object>> models = new ArrayList<>();
        for (String id : modelIds) models.add(model(id));
        p.put("models", models);
        return p;
    }

    @SafeVarargs
    private Map<String, Object> adminBase(Map<String, Object>... providers) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new ArrayList<>(List.of(providers)));
        return base;
    }

    private Map<String, Object> row(List<Map<String, Object>> result, String modelId) {
        return result.stream()
                .filter(m -> modelId.equals(m.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for model " + modelId));
    }

    @Test
    @DisplayName("a billed model its CLI routes carries the bridge slug")
    void stampsTheBridgeForARoutableBilledModel() {
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("anthropic", "claude-opus-4-7"), provider("openai", "gpt-5.5")));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();

        assertThat(row(result, "claude-opus-4-7")).containsEntry("cliBridgeProvider", "claude-code");
        assertThat(row(result, "gpt-5.5")).containsEntry("cliBridgeProvider", "codex");
    }

    @Test
    @DisplayName("a model the CLI cannot route carries NO stamp, even under a provider that has a CLI")
    void leavesUnroutableModelsUnstamped() {
        // The Codex CLI refuses a bare gpt-5.6 with a ChatGPT account, and no CLI
        // routes an embedding model: offering a link here would fail at dispatch.
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("openai", "gpt-5.6", "text-embedding-3-large")));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();

        assertThat(row(result, "gpt-5.6")).doesNotContainKey("cliBridgeProvider");
        assertThat(row(result, "text-embedding-3-large")).doesNotContainKey("cliBridgeProvider");
    }

    @Test
    @DisplayName("a provider with no CLI, and a bridge row itself, carry NO stamp")
    void leavesProvidersWithoutACliUnstamped() {
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("deepseek", "deepseek-chat"),
                        // A bridge row must not be offered a link to itself.
                        provider("claude-code", "claude-opus-4-7")));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();

        assertThat(row(result, "deepseek-chat")).doesNotContainKey("cliBridgeProvider");
        assertThat(row(result, "claude-opus-4-7")).doesNotContainKey("cliBridgeProvider");
    }

    @Test
    @DisplayName("a sync/custom row that has no YAML shell is stamped too")
    void stampsStandaloneOverrideRows() {
        // Catalog-sync rows are injected separately from the YAML models; a model
        // that only exists as an override row must get the same treatment or the
        // button would be missing for every recently-synced model.
        ModelConfigOverrideEntity synced = new ModelConfigOverrideEntity();
        synced.setProvider("anthropic");
        synced.setModelId("claude-sonnet-4-6");
        synced.setCustom(false);
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("anthropic", "claude-opus-4-7")));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(synced));

        List<Map<String, Object>> result = service.getEffectiveModelList();

        assertThat(row(result, "claude-sonnet-4-6")).containsEntry("cliBridgeProvider", "claude-code");
    }

    @Test
    @DisplayName("cliBridgeAvailable reports whether the CLI could RUN, not merely that it exists")
    void reportsTheCliRunnableState() {
        // The admin needs this BEFORE linking: only an unwired bridge transport falls
        // back to the billed pair, so a wired bridge with an unusable CLI fails the run.
        // It must read the STRICT signal - an installed-but-logged-out CLI runs nothing,
        // and that is exactly the state a green badge would misreport.
        ReflectionTestUtils.setField(service, "bridgeAvailabilityFilter", bridgeAvailabilityFilter);
        when(bridgeAvailabilityFilter.installedMap()).thenReturn(Map.of("claudeCode", true));
        when(bridgeAvailabilityFilter.runnableMap()).thenReturn(Map.of("claudeCode", false));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("anthropic", "claude-opus-4-7")));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();

        assertThat(row(result, "claude-opus-4-7"))
                .containsEntry("cliBridgeProvider", "claude-code")
                .containsEntry("cliBridgeAvailable", false);
    }

    @Test
    @DisplayName("the category-tab backstop loop stamps its rows too")
    void stampsRowsFromTheCategoryBackstopLoop() {
        // A third emission path exists for a provider the admin base does not carry at
        // all, on a category tab. It is a backstop today, but a row that reaches the
        // panel through it must not be the one row missing its routing button.
        ModelConfigOverrideEntity local = new ModelConfigOverrideEntity();
        local.setProvider("anthropic");
        local.setModelId("claude-opus-4-7");
        local.setCustom(true);
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("deepseek", "deepseek-chat")));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(local));

        List<Map<String, Object>> result = service.getEffectiveModelList("browser_agent", null);

        assertThat(row(result, "claude-opus-4-7")).containsEntry("cliBridgeProvider", "claude-code");
    }

    @Test
    @DisplayName("a row with no CLI counterpart carries NEITHER key, so the panel renders no control")
    void leavesBothKeysAbsentWhenUnstamped() {
        // The frontend keys the whole control off the PRESENCE of cliBridgeProvider; a
        // stray cliBridgeAvailable on an unstamped row would be a field with no subject.
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("deepseek", "deepseek-chat")));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();

        assertThat(row(result, "deepseek-chat"))
                .doesNotContainKey("cliBridgeProvider")
                .doesNotContainKey("cliBridgeAvailable");
    }

    @Test
    @DisplayName("an unreachable bridge leaves availability unknown (null), not false")
    void reportsUnknownAvailabilityWhenTheBridgeIsSilent() {
        // Blank bridge URL: nothing was probed. Reporting false would tell the admin
        // the CLI is missing when we simply did not ask.
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("anthropic", "claude-opus-4-7")));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();

        assertThat(row(result, "claude-opus-4-7"))
                .containsEntry("cliBridgeProvider", "claude-code")
                .containsEntry("cliBridgeAvailable", null);
    }
}

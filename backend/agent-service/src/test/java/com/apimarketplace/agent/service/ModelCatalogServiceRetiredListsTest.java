package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Where a retired (V533) or CE-deprecated model must NOT appear: the pickers, category lists
 * included, and the admin model list (retired models have their own list).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - retired and deprecated models in the lists")
class ModelCatalogServiceRetiredListsTest {

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
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);
        // A fresh YAML base per call: the service mutates it in place.
        lenient().when(llmProviderFactory.getAllModelsInfoAdmin())
                .thenAnswer(inv -> base("openai", "gpt-6-sol", "gpt-5.4", "gpt-5.2"));
    }

    private static Map<String, Object> base(String provider, String... modelIds) {
        List<Map<String, Object>> models = new ArrayList<>();
        int order = 1;
        for (String id : modelIds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", id);
            m.put("displayOrder", order++);
            models.add(m);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", provider);
        p.put("configured", true);
        p.put("models", models);
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new ArrayList<>(List.of(p)));
        return base;
    }

    private static ModelConfigOverrideEntity row(long id, String modelId, Boolean enabled) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setId(id);
        e.setProvider("openai");
        e.setModelId(modelId);
        e.setDisplayName(modelId);
        e.setRanking((int) id);
        e.setEnabled(enabled);
        return e;
    }

    @SuppressWarnings("unchecked")
    private static List<String> pickerIds(Map<String, Object> catalog) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) catalog.get("providers")) {
            for (Map<String, Object> m : (List<Map<String, Object>>) p.get("models")) {
                ids.add((String) m.get("id"));
            }
        }
        return ids;
    }

    @Test
    @DisplayName("a category row cannot put a retired model back in that category's picker")
    void categoryRowCannotReviveRetired() {
        ModelConfigOverrideEntity retired = row(2L, "gpt-5.4", false);
        retired.setRetiredAt(Instant.now());
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(retired));
        ModelCategorySettingsEntity sidecar = new ModelCategorySettingsEntity();
        sidecar.setModelConfigId(2L);
        sidecar.setCategory("browser_agent");
        sidecar.setEnabled(true);
        when(categoryRepository.findByCategory("browser_agent")).thenReturn(List.of(sidecar));

        List<String> ids = pickerIds(service.getModelsForCategory("browser_agent"));

        assertThat(ids).contains("gpt-6-sol", "gpt-5.2").doesNotContain("gpt-5.4");
    }

    @Test
    @DisplayName("a category row still enables a model that is only off for chat (not retired)")
    void categoryRowStillOverridesPlainDisable() {
        // By design the global flag is the Chat tab's: a model off for chat can be on for the
        // browser agent. Retirement must not turn that into "off everywhere".
        ModelConfigOverrideEntity offForChat = row(2L, "gpt-5.4", false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(offForChat));
        ModelCategorySettingsEntity sidecar = new ModelCategorySettingsEntity();
        sidecar.setModelConfigId(2L);
        sidecar.setCategory("browser_agent");
        sidecar.setEnabled(true);
        when(categoryRepository.findByCategory("browser_agent")).thenReturn(List.of(sidecar));

        assertThat(pickerIds(service.getModelsForCategory("browser_agent"))).contains("gpt-5.4");
    }

    @Test
    @DisplayName("a YAML-declared model whose row is deprecated (a CE bundle stopped carrying it) leaves the picker")
    void deprecatedYamlModelHidden() {
        // Regression: the YAML path only honoured enabled=false, so on a CE the models the cloud
        // stopped shipping stayed listed forever.
        ModelConfigOverrideEntity deprecated = row(3L, "gpt-5.2", null);
        deprecated.setDeprecatedAt(Instant.now());
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(deprecated));

        assertThat(pickerIds(service.getModelsForCategory(null)))
                .containsExactly("gpt-6-sol", "gpt-5.4");
    }

    @Test
    @DisplayName("the admin model list shows disabled models but not retired ones (they have their own list)")
    void adminListHidesRetiredKeepsDisabled() {
        ModelConfigOverrideEntity disabled = row(2L, "gpt-5.4", false);
        ModelConfigOverrideEntity retiredYaml = row(3L, "gpt-5.2", false);
        retiredYaml.setRetiredAt(Instant.now());
        ModelConfigOverrideEntity retiredDbOnly = row(4L, "gpt-4o", false);
        retiredDbOnly.setRetiredAt(Instant.now());
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(disabled, retiredYaml, retiredDbOnly));

        List<Map<String, Object>> rows = service.getEffectiveModelList();

        assertThat(rows).extracting(r -> r.get("id")).containsExactlyInAnyOrder("gpt-6-sol", "gpt-5.4");
        assertThat(rows).filteredOn(r -> "gpt-5.4".equals(r.get("id")))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("enabled", false));
    }
}

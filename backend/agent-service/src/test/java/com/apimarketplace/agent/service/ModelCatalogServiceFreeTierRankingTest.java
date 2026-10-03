package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.ModelCategory;
import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelCategorySettingsId;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The free tier has a ranking of its own: the {@code free_tier} category of the per-category
 * sidecar, read as a rank and nothing else.
 *
 * <ul>
 *   <li>The picker catalogue stamps {@code freeTierRank} on the opened models an admin ranked,
 *       and leaves the global {@code displayOrder} (what a paid account is ordered by) alone.</li>
 *   <li>The admin list for the category holds the opened models only, in that order.</li>
 *   <li>A sidecar row's {@code enabled} is never a switch there: it cannot turn a globally
 *       disabled model on, and the per-category toggle refuses the category.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - free-tier ranking")
class ModelCatalogServiceFreeTierRankingTest {

    private static final String FREE_TIER = ModelCategory.FREE_TIER.key();

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

    @Test
    @DisplayName("picker catalogue: an opened model carries its free-tier rank, and the global order is untouched")
    void pickerCatalogueStampsFreeTierRank() {
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, true, true);
        ModelConfigOverrideEntity flash = entity(20L, "deepseek", "flash", 56, true, true);
        stubCatalog(sonnet, flash);
        // The free tier flips them: flash first, sonnet second.
        when(categoryRepository.findByCategory(FREE_TIER)).thenReturn(List.of(
                sidecar(20L, 1), sidecar(10L, 2)));

        Map<String, Object> catalog = service.getModelsForCategory(null);

        assertThat(modelOf(catalog, "deepseek", "flash")).containsEntry("freeTierRank", 1);
        assertThat(modelOf(catalog, "anthropic", "sonnet")).containsEntry("freeTierRank", 2);
        // What a paid account is ordered by, and what picks the catalogue default.
        assertThat(modelOf(catalog, "anthropic", "sonnet")).containsEntry("displayOrder", 1);
        assertThat(modelOf(catalog, "deepseek", "flash")).containsEntry("displayOrder", 56);
        assertThat(catalog.get("defaultModel")).isEqualTo("sonnet");
    }

    @Test
    @DisplayName("picker catalogue: a model NOT opened to the free tier gets no rank, even with a leftover sidecar row")
    void closedModelCarriesNoFreeTierRank() {
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, true, true);
        ModelConfigOverrideEntity opus = entity(30L, "anthropic", "opus", 2, true, false);
        stubCatalog(sonnet, opus);
        // opus was ranked while it was open, then closed: its row stays behind.
        when(categoryRepository.findByCategory(FREE_TIER)).thenReturn(List.of(
                sidecar(30L, 1), sidecar(10L, 2)));

        Map<String, Object> catalog = service.getModelsForCategory(null);

        assertThat(modelOf(catalog, "anthropic", "opus")).doesNotContainKey("freeTierRank");
        assertThat(modelOf(catalog, "anthropic", "sonnet")).containsEntry("freeTierRank", 2);
    }

    @Test
    @DisplayName("picker catalogue: an opened model the admin never ranked carries no key, so it keeps its global order")
    void unrankedOpenedModelCarriesNoKey() {
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, true, true);
        stubCatalog(sonnet);
        when(categoryRepository.findByCategory(FREE_TIER)).thenReturn(List.of());

        Map<String, Object> catalog = service.getModelsForCategory(null);

        assertThat(modelOf(catalog, "anthropic", "sonnet")).doesNotContainKey("freeTierRank");
    }

    @Test
    @DisplayName("picker catalogue: nothing opened to the free tier means no sidecar query at all (every CE install)")
    void nothingOpenedMeansNoSidecarQuery() {
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, true, false);
        stubCatalog(sonnet);

        service.getModelsForCategory(null);

        verify(categoryRepository, never()).findByCategory(any());
    }

    @Test
    @DisplayName("admin list: the free_tier category holds the opened models only, in the free tier's own order")
    void adminListHoldsOpenedModelsInFreeTierOrder() {
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, true, true);
        ModelConfigOverrideEntity opus = entity(30L, "anthropic", "opus", 2, true, false);
        ModelConfigOverrideEntity flash = entity(20L, "deepseek", "flash", 56, true, true);
        stubCatalog(sonnet, opus, flash);
        when(categoryRepository.findByCategory(FREE_TIER)).thenReturn(List.of(
                sidecar(20L, 1), sidecar(10L, 2)));

        List<Map<String, Object>> rows = service.getEffectiveModelList(FREE_TIER);

        assertThat(rows).extracting(r -> r.get("id")).containsExactly("flash", "sonnet");
    }

    @Test
    @DisplayName("admin list: a free-tier sidecar row never switches a globally disabled model on")
    void sidecarRowDoesNotEnableADisabledModel() {
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, false, true);
        stubCatalog(sonnet);
        // bulkUpdateCategoryRankings writes enabled=TRUE on every row it creates.
        ModelCategorySettingsEntity row = sidecar(10L, 1);
        row.setEnabled(Boolean.TRUE);
        when(categoryRepository.findByCategory(FREE_TIER)).thenReturn(List.of(row));

        List<Map<String, Object>> rows = service.getEffectiveModelList(FREE_TIER);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("enabled", false);
    }

    @Test
    @DisplayName("admin list: a model opened but never ranked comes AFTER the ranked ones, even when it leads globally")
    void unrankedModelFollowsTheRankedOnes() {
        // sonnet is the global #1 and was just opened: no free-tier row yet. A free-tier rank
        // and a global rank are two scales; compared, sonnet would tie with flash at 1.
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, true, true);
        ModelConfigOverrideEntity flash = entity(20L, "deepseek", "flash", 56, true, true);
        ModelConfigOverrideEntity haiku = entity(40L, "anthropic", "haiku", 3, true, true);
        stubCatalog(sonnet, flash, haiku);
        when(categoryRepository.findByCategory(FREE_TIER)).thenReturn(List.of(
                sidecar(20L, 1), sidecar(40L, 2)));

        List<Map<String, Object>> rows = service.getEffectiveModelList(FREE_TIER);

        assertThat(rows).extracting(r -> r.get("id")).containsExactly("flash", "haiku", "sonnet");
    }

    @Test
    @DisplayName("admin list: a sidecar row with no rank counts as never ranked")
    void rowWithoutARankIsUnranked() {
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, true, true);
        ModelConfigOverrideEntity flash = entity(20L, "deepseek", "flash", 56, true, true);
        stubCatalog(sonnet, flash);
        when(categoryRepository.findByCategory(FREE_TIER)).thenReturn(List.of(
                sidecar(10L, null), sidecar(20L, 1)));

        List<Map<String, Object>> rows = service.getEffectiveModelList(FREE_TIER);
        Map<String, Object> catalog = service.getModelsForCategory(null);

        assertThat(rows).extracting(r -> r.get("id")).containsExactly("flash", "sonnet");
        assertThat(modelOf(catalog, "anthropic", "sonnet")).doesNotContainKey("freeTierRank");
    }

    @Test
    @DisplayName("admin list: a free-tier sidecar row never switches a globally enabled model off either")
    void sidecarRowDoesNotDisableAnEnabledModel() {
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, true, true);
        stubCatalog(sonnet);
        ModelCategorySettingsEntity row = sidecar(10L, 1);
        row.setEnabled(Boolean.FALSE);
        when(categoryRepository.findByCategory(FREE_TIER)).thenReturn(List.of(row));

        List<Map<String, Object>> rows = service.getEffectiveModelList(FREE_TIER);
        Map<String, Object> catalog = service.getModelsForCategory(null);

        assertThat(rows.get(0)).containsEntry("enabled", true);
        // And the picker keeps the model: on any other category that row would remove it.
        assertThat(modelOf(catalog, "anthropic", "sonnet")).containsEntry("freeTierRank", 1);
    }

    @Test
    @DisplayName("a model that exists only as a database row (no YAML entry) is ranked and listed like any other")
    void databaseOnlyModelIsStampedAndListed() {
        // Most production rows come from the catalogue sync and have no YAML twin: they are
        // injected by a different builder than the YAML-backed ones.
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, true, true);
        ModelConfigOverrideEntity synced = entity(50L, "anthropic", "synced-model", 9, true, true);
        stubCatalog(sonnet);
        lenient().when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(sonnet, synced));
        when(categoryRepository.findByCategory(FREE_TIER)).thenReturn(List.of(
                sidecar(50L, 1), sidecar(10L, 2)));

        Map<String, Object> catalog = service.getModelsForCategory(null);
        List<Map<String, Object>> rows = service.getEffectiveModelList(FREE_TIER);

        assertThat(modelOf(catalog, "anthropic", "synced-model")).containsEntry("freeTierRank", 1);
        assertThat(rows).extracting(r -> r.get("id")).containsExactly("synced-model", "sonnet");
    }

    @Test
    @DisplayName("picker catalogue of ANOTHER category still carries the free-tier rank, not that category's")
    void categoryScopedCatalogueCarriesTheFreeTierRank() {
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, true, true);
        stubCatalog(sonnet);
        ModelCategorySettingsEntity browserRow = new ModelCategorySettingsEntity();
        browserRow.setModelConfigId(10L);
        browserRow.setCategory("browser_agent");
        browserRow.setRank(7);
        browserRow.setEnabled(Boolean.TRUE);
        when(categoryRepository.findByCategory("browser_agent")).thenReturn(List.of(browserRow));
        when(categoryRepository.findByCategory(FREE_TIER)).thenReturn(List.of(sidecar(10L, 3)));

        Map<String, Object> catalog = service.getModelsForCategory("browser_agent");

        assertThat(modelOf(catalog, "anthropic", "sonnet"))
                .containsEntry("displayOrder", 7)
                .containsEntry("freeTierRank", 3);
    }

    @Test
    @DisplayName("ranking the free tier writes free_tier sidecar rows and leaves the global ranking alone")
    void rankingWritesTheFreeTierSidecar() {
        ModelConfigOverrideEntity sonnet = entity(10L, "anthropic", "sonnet", 1, true, true);
        when(repository.findByProviderAndModelId("anthropic", "sonnet")).thenReturn(Optional.of(sonnet));
        when(categoryRepository.findById(new ModelCategorySettingsId(10L, FREE_TIER)))
                .thenReturn(Optional.empty());

        service.bulkUpdateCategoryRankings(FREE_TIER, List.of(
                Map.of("provider", "anthropic", "modelId", "sonnet", "ranking", 4)));

        org.mockito.ArgumentCaptor<ModelCategorySettingsEntity> saved =
                org.mockito.ArgumentCaptor.forClass(ModelCategorySettingsEntity.class);
        verify(categoryRepository).save(saved.capture());
        assertThat(saved.getValue().getCategory()).isEqualTo(FREE_TIER);
        assertThat(saved.getValue().getRank()).isEqualTo(4);
        assertThat(sonnet.getRanking()).isEqualTo(1);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("ranking the free tier skips a model that is not open to it, and creates no row for an unknown one")
    void rankingSkipsModelsThatAreNotOpen() {
        // A list loaded before another admin closed opus: its rank must not be parked for later.
        ModelConfigOverrideEntity opus = entity(30L, "anthropic", "opus", 2, true, false);
        when(repository.findByProviderAndModelId("anthropic", "opus")).thenReturn(Optional.of(opus));
        when(repository.findByProviderAndModelId("anthropic", "ghost")).thenReturn(Optional.empty());

        service.bulkUpdateCategoryRankings(FREE_TIER, List.of(
                Map.of("provider", "anthropic", "modelId", "opus", "ranking", 1),
                Map.of("provider", "anthropic", "modelId", "ghost", "ranking", 2)));

        verify(categoryRepository, never()).save(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("closing a model that was never ranked deletes nothing and still saves")
    void closingAnUnrankedModelDeletesNothing() {
        ModelConfigOverrideEntity stored = entity(10L, "anthropic", "sonnet", 1, true, true);
        stored.setPriceInput(new BigDecimal("1.00"));
        stored.setPriceOutput(new BigDecimal("5.00"));
        when(repository.findByProviderAndModelId("anthropic", "sonnet")).thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(authPricingSyncClient.sync(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        when(categoryRepository.findById(new ModelCategorySettingsId(10L, FREE_TIER)))
                .thenReturn(Optional.empty());

        ModelConfigOverrideEntity saved = service.saveOverride(closeRequest("anthropic", "sonnet"));

        assertThat(saved.isFreeTierEnabled()).isFalse();
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("retiring a model forgets its free-tier rank too: it is closed by the same call")
    void retiringAModelDropsItsFreeTierRank() {
        ModelConfigOverrideEntity stored = entity(10L, "anthropic", "sonnet", 1, true, true);
        ModelCategorySettingsEntity rank = sidecar(10L, 1);
        when(repository.findByProviderAndModelId("anthropic", "sonnet")).thenReturn(Optional.of(stored));
        when(categoryRepository.findById(new ModelCategorySettingsId(10L, FREE_TIER)))
                .thenReturn(Optional.of(rank));

        service.retireModels(List.of(new ModelCatalogService.ModelRef("anthropic", "sonnet")), "7");

        assertThat(stored.isFreeTierEnabled()).isFalse();
        verify(categoryRepository).delete(rank);
    }

    @Test
    @DisplayName("closing a model to the free tier forgets its rank, so a re-opened model cannot come back tied with the new #1")
    void closingAModelDropsItsFreeTierRank() {
        ModelConfigOverrideEntity stored = entity(10L, "anthropic", "sonnet", 1, true, true);
        stored.setPriceInput(new BigDecimal("1.00"));
        stored.setPriceOutput(new BigDecimal("5.00"));
        ModelCategorySettingsEntity rank = sidecar(10L, 1);
        when(repository.findByProviderAndModelId("anthropic", "sonnet")).thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(authPricingSyncClient.sync(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        when(categoryRepository.findById(new ModelCategorySettingsId(10L, FREE_TIER)))
                .thenReturn(Optional.of(rank));

        service.saveOverride(closeRequest("anthropic", "sonnet"));

        verify(categoryRepository).delete(rank);
    }

    @Test
    @DisplayName("a save that does not close the free tier leaves the rank where it is")
    void unrelatedSaveKeepsTheFreeTierRank() {
        ModelConfigOverrideEntity stored = entity(10L, "anthropic", "sonnet", 1, true, true);
        stored.setPriceInput(new BigDecimal("1.00"));
        stored.setPriceOutput(new BigDecimal("5.00"));
        when(repository.findByProviderAndModelId("anthropic", "sonnet")).thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(authPricingSyncClient.sync(anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        ModelConfigOverrideEntity in = new ModelConfigOverrideEntity();
        in.setProvider("anthropic");
        in.setModelId("sonnet");
        in.setDisplayName("Sonnet renamed");

        service.saveOverride(in);

        verify(categoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("setCategoryEnabled refuses free_tier: it would answer saved and change nothing")
    void categoryToggleRefusesTheRanksOnlyCategory() {
        assertThatThrownBy(() -> service.setCategoryEnabled("anthropic", "sonnet", FREE_TIER, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only carries a ranking");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("free_tier admits chat models only, like the surfaces a Free account spends its allowance on")
    void freeTierAcceptsChatModesOnly() {
        assertThat(ModelCategory.acceptsMode(FREE_TIER, null)).isTrue();
        assertThat(ModelCategory.acceptsMode(FREE_TIER, "chat")).isTrue();
        assertThat(ModelCategory.acceptsMode(FREE_TIER, "image")).isFalse();
        assertThat(ModelCategory.acceptsMode(FREE_TIER, ModelCategory.DECISION_MODE)).isFalse();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void stubCatalog(ModelConfigOverrideEntity... entities) {
        lenient().when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(entities));
        // A fresh base per call: the service mutates the catalogue it is handed.
        lenient().when(llmProviderFactory.getAllModelsInfoAdmin()).thenAnswer(inv -> {
            Map<String, Map<String, Object>> byProvider = new LinkedHashMap<>();
            for (ModelConfigOverrideEntity e : entities) {
                Map<String, Object> p = byProvider.computeIfAbsent(e.getProvider(), k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", k);
                    m.put("models", new ArrayList<Map<String, Object>>());
                    m.put("configured", true);
                    return m;
                });
                Map<String, Object> model = new LinkedHashMap<>();
                model.put("id", e.getModelId());
                model.put("name", e.getModelId());
                // As the real base catalogue does: every model names its provider.
                model.put("provider", e.getProvider());
                model.put("displayOrder", e.getRanking());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> models = (List<Map<String, Object>>) p.get("models");
                models.add(model);
            }
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("providers", new ArrayList<>(byProvider.values()));
            return base;
        });
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);
    }

    private static ModelConfigOverrideEntity entity(Long id, String provider, String modelId,
                                                    Integer ranking, boolean enabled, boolean freeTier) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setId(id);
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setDisplayName(modelId);
        e.setRanking(ranking);
        e.setEnabled(enabled);
        e.setFreeTierEnabled(freeTier);
        return e;
    }

    private static ModelConfigOverrideEntity closeRequest(String provider, String modelId) {
        ModelConfigOverrideEntity in = new ModelConfigOverrideEntity();
        in.setProvider(provider);
        in.setModelId(modelId);
        in.setDisplayName(modelId);
        in.setFreeTierEnabledExplicitlySet(true);
        in.setFreeTierEnabled(false);
        return in;
    }

    private static ModelCategorySettingsEntity sidecar(Long modelConfigId, Integer rank) {
        ModelCategorySettingsEntity s = new ModelCategorySettingsEntity();
        s.setModelConfigId(modelConfigId);
        s.setCategory(FREE_TIER);
        s.setRank(rank);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> modelOf(Map<String, Object> catalog, String provider, String modelId) {
        for (Map<String, Object> p : (List<Map<String, Object>>) catalog.get("providers")) {
            if (!provider.equals(p.get("name"))) continue;
            for (Map<String, Object> m : (List<Map<String, Object>>) p.get("models")) {
                if (modelId.equals(m.get("id"))) return m;
            }
        }
        throw new AssertionError("model not in catalogue: " + provider + "/" + modelId);
    }
}

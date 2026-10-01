package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.ModelCatalogService.AvailableModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * V554: an UNLISTED model is available but not offered. It must stay runnable (validation,
 * provider resolution, the per-model limits all keep finding it) while leaving every list a
 * person or an agent chooses from, and it must never become the platform default. Also the
 * admin list's "new" badge, which ships with it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ModelCatalogService - unlisted models (V554) and the new badge")
class ModelCatalogServiceUnlistedTest {

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
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(credentialRepository.hasDbKey(any())).thenReturn(true);
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /** A fresh YAML-like base on every call: the pipeline mutates what it is given. */
    private void yamlCatalog(String provider, String... modelIds) {
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenAnswer(inv -> {
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
        });
    }

    private static ModelConfigOverrideEntity row(long id, String provider, String modelId, int ranking,
                                                 Boolean enabled, boolean unlisted) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setId(id);
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setDisplayName(modelId);
        e.setRanking(ranking);
        e.setEnabled(enabled);
        e.setUnlisted(unlisted);
        return e;
    }

    private void overrides(ModelConfigOverrideEntity... rows) {
        when(repository.findAllByOrderByRankingAsc()).thenReturn(Arrays.asList(rows));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> modelsOf(Map<String, Object> catalog, String provider) {
        List<Map<String, Object>> providers = (List<Map<String, Object>>) catalog.get("providers");
        return providers.stream()
                .filter(p -> provider.equals(p.get("name")))
                .findFirst()
                .map(p -> (List<Map<String, Object>>) p.get("models"))
                .orElse(List.of());
    }

    private static Map<String, Object> entryFor(List<Map<String, Object>> list, String modelId) {
        return list.stream().filter(m -> modelId.equals(m.get("id"))).findFirst().orElseThrow();
    }

    // ── picker catalogue ──────────────────────────────────────────────────

    @Nested
    @DisplayName("picker catalogue (what /v3/chat/models serves)")
    class PickerCatalogue {

        @Test
        @DisplayName("an unlisted ENABLED model stays in the catalogue, flagged, so what uses it keeps running")
        void unlistedEnabledStaysFlagged() {
            yamlCatalog("openai", "gpt-5", "gpt-4o");
            overrides(row(1, "openai", "gpt-5", 1, true, false),
                      row(2, "openai", "gpt-4o", 2, true, true));

            List<Map<String, Object>> models = modelsOf(service.getModelsForCategory(null), "openai");

            assertThat(models).extracting(m -> m.get("id")).containsExactly("gpt-5", "gpt-4o");
            assertThat(entryFor(models, "gpt-4o")).containsEntry("unlisted", true);
            assertThat(entryFor(models, "gpt-5")).containsEntry("unlisted", false);
        }

        @Test
        @DisplayName("a DISABLED model is still removed whatever its unlisted flag says: off wins")
        void disabledWinsOverUnlisted() {
            yamlCatalog("openai", "gpt-5", "gpt-4o");
            overrides(row(1, "openai", "gpt-5", 1, true, false),
                      row(2, "openai", "gpt-4o", 2, false, true));

            List<Map<String, Object>> models = modelsOf(service.getModelsForCategory(null), "openai");

            assertThat(models).extracting(m -> m.get("id")).containsExactly("gpt-5");
        }

        @Test
        @DisplayName("an unlisted model ranked #1 is NOT the default: the best LISTED model is")
        void unlistedNeverTheDefault() {
            yamlCatalog("openai", "gpt-4o", "gpt-5");
            overrides(row(1, "openai", "gpt-4o", 1, true, true),
                      row(2, "openai", "gpt-5", 2, true, false));

            Map<String, Object> catalog = service.getModelsForCategory(null);

            assertThat(catalog).containsEntry("defaultProvider", "openai");
            assertThat(catalog).containsEntry("defaultModel", "gpt-5");
            assertThat(catalog).containsEntry("defaultDirectModel", "gpt-5");
        }

        @Test
        @DisplayName("a catalogue made only of unlisted models still has a default (the best of them)")
        void onlyUnlistedStillHasADefault() {
            yamlCatalog("openai", "gpt-4o", "gpt-4");
            overrides(row(1, "openai", "gpt-4o", 1, true, true),
                      row(2, "openai", "gpt-4", 2, true, true));

            Map<String, Object> catalog = service.getModelsForCategory(null);

            assertThat(catalog).containsEntry("defaultModel", "gpt-4o");
        }

        @Test
        @DisplayName("a feed/bundle row absent from the YAML base (most of the catalogue) carries the flag too, and is not the default")
        void injectedRowCarriesTheFlag() {
            // Only gpt-5 is YAML-declared; gpt-4o reaches the catalogue through buildModelInfo.
            yamlCatalog("openai", "gpt-5");
            overrides(row(1, "openai", "gpt-4o", 1, true, true),
                      row(2, "openai", "gpt-5", 2, true, false));

            Map<String, Object> catalog = service.getModelsForCategory(null);

            assertThat(entryFor(modelsOf(catalog, "openai"), "gpt-4o")).containsEntry("unlisted", true);
            assertThat(catalog).containsEntry("defaultModel", "gpt-5");
        }

        @Test
        @DisplayName("a category tab cannot list a model the admin unlisted: the clone carries the global flag")
        void categoryOverlayKeepsTheGlobalFlag() {
            yamlCatalog("openai", "gpt-5", "gpt-4o");
            overrides(row(1, "openai", "gpt-5", 1, true, false),
                      row(2, "openai", "gpt-4o", 2, true, true));
            ModelCategorySettingsEntity sidecar = new ModelCategorySettingsEntity();
            sidecar.setModelConfigId(2L);
            sidecar.setCategory("browser_agent");
            sidecar.setRank(1);
            sidecar.setEnabled(true);
            when(categoryRepository.findByCategory("browser_agent")).thenReturn(List.of(sidecar));

            Map<String, Object> catalog = service.getModelsForCategory("browser_agent");

            assertThat(entryFor(modelsOf(catalog, "openai"), "gpt-4o")).containsEntry("unlisted", true);
            // Ranked #1 on this tab, and still not its default.
            assertThat(catalog).containsEntry("defaultDirectModel", "gpt-5");
        }
    }

    // ── flat catalogue ────────────────────────────────────────────────────

    @Nested
    @DisplayName("flat catalogue (validation, routing, agent-facing lists)")
    class FlatCatalogue {

        @Test
        @DisplayName("listAvailableModels carries the flag and isModelAvailable still accepts an unlisted model")
        void unlistedStaysAvailable() {
            yamlCatalog("openai", "gpt-5", "gpt-4o");
            overrides(row(1, "openai", "gpt-5", 1, true, false),
                      row(2, "openai", "gpt-4o", 2, true, true));

            List<AvailableModel> flat = service.listAvailableModels();

            assertThat(flat).extracting(AvailableModel::modelId, AvailableModel::unlisted)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("gpt-5", false),
                            org.assertj.core.groups.Tuple.tuple("gpt-4o", true));
            assertThat(service.isModelAvailable("openai", "gpt-4o"))
                    .as("an agent or node already on it must keep validating")
                    .isTrue();
        }

        @Test
        @DisplayName("the legacy 6-arg AvailableModel constructor reads as listed")
        void legacyConstructorIsListed() {
            assertThat(new AvailableModel("openai", "gpt-5", "top", 1, null, null).unlisted()).isFalse();
        }
    }

    // ── admin save ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("saveOverride intake")
    class Save {

        private ModelConfigOverrideEntity stored;

        @BeforeEach
        void existingRow() {
            stored = row(7, "openai", "gpt-4o", 3, true, false);
            stored.setPriceInput(new BigDecimal("2.50"));
            stored.setPriceOutput(new BigDecimal("10.00"));
            when(repository.findByProviderAndModelId("openai", "gpt-4o")).thenReturn(Optional.of(stored));
        }

        private ModelConfigOverrideEntity input() {
            ModelConfigOverrideEntity in = new ModelConfigOverrideEntity();
            in.setProvider("openai");
            in.setModelId("gpt-4o");
            return in;
        }

        @Test
        @DisplayName("an explicit unlisted=true is stored and marked user-modified, so a CE bundle apply keeps a CE admin's choice")
        void explicitTrueStored() {
            ModelConfigOverrideEntity in = input();
            in.setUnlistedExplicitlySet(true);
            in.setUnlisted(true);

            service.saveOverride(in);

            assertThat(stored.isUnlisted()).isTrue();
            assertThat(stored.getUserModifiedFields()).contains("unlisted");
        }

        @Test
        @DisplayName("a save without the key does not mark the field: the next bundle may still set it")
        void absentKeyDoesNotMark() {
            ModelConfigOverrideEntity in = input();
            in.setTier("mid");

            service.saveOverride(in);

            assertThat(stored.getUserModifiedFields()).doesNotContain("unlisted");
        }

        @Test
        @DisplayName("a save WITHOUT the key leaves an unlisted model unlisted (a rename must not list it again)")
        void absentKeyPreserves() {
            stored.setUnlisted(true);
            ModelConfigOverrideEntity in = input();
            in.setDisplayName("GPT-4o (legacy)");

            service.saveOverride(in);

            assertThat(stored.isUnlisted()).isTrue();
            assertThat(stored.getDisplayName()).isEqualTo("GPT-4o (legacy)");
        }

        @Test
        @DisplayName("an explicit unlisted=false lists it again")
        void explicitFalseLists() {
            stored.setUnlisted(true);
            ModelConfigOverrideEntity in = input();
            in.setUnlistedExplicitlySet(true);
            in.setUnlisted(false);

            service.saveOverride(in);

            assertThat(stored.isUnlisted()).isFalse();
        }

        @Test
        @DisplayName("off -> unlisted in one save keeps the replacement set while it was off (paused, not cleared)")
        void offToUnlistedKeepsReplacement() {
            stored.setEnabled(false);
            stored.setReplacementProvider("openai");
            stored.setReplacementModel("gpt-5");
            ModelConfigOverrideEntity in = input();
            in.setEnabled(true);
            in.setUnlistedExplicitlySet(true);
            in.setUnlisted(true);

            service.saveOverride(in);

            assertThat(stored.getEnabled()).isTrue();
            assertThat(stored.isUnlisted()).isTrue();
            assertThat(stored.getReplacementModel())
                    .as("switching the model back off must find its replacement where the admin left it")
                    .isEqualTo("gpt-5");
        }
    }

    // ── new badge ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the admin list's new badge")
    class NewBadge {

        private final Instant now = Instant.parse("2026-09-30T12:00:00Z");
        private final Instant baseline = Instant.parse("2026-01-10T08:00:00Z");

        @Test
        @DisplayName("added a few days ago = new")
        void recentIsNew() {
            assertThat(ModelCatalogService.isRecentlyAdded(now.minus(Duration.ofDays(3)), baseline, now)).isTrue();
        }

        @Test
        @DisplayName("added more than 14 days ago = not new")
        void oldIsNotNew() {
            assertThat(ModelCatalogService.isRecentlyAdded(now.minus(Duration.ofDays(15)), baseline, now)).isFalse();
        }

        @Test
        @DisplayName("the catalogue's initial fill is never new, even on an install seeded yesterday")
        void initialFillIsNotNew() {
            Instant seededYesterday = now.minus(Duration.ofDays(1));
            assertThat(ModelCatalogService.isRecentlyAdded(seededYesterday.plus(Duration.ofMinutes(2)),
                    seededYesterday, now))
                    .as("a first boot seeds the whole catalogue within minutes")
                    .isFalse();
            assertThat(ModelCatalogService.isRecentlyAdded(seededYesterday.plus(Duration.ofHours(3)),
                    seededYesterday, now))
                    .as("a model a later sync brought in is new")
                    .isTrue();
        }

        @Test
        @DisplayName("no creation time = not new")
        void nullIsNotNew() {
            assertThat(ModelCatalogService.isRecentlyAdded(null, baseline, now)).isFalse();
        }

        @Test
        @DisplayName("getEffectiveModelList stamps addedAt and isNew, on the chat tab and on a category tab alike")
        void adminListStampsAddedAt() {
            Instant old = Instant.now().minus(Duration.ofDays(200));
            Instant recent = Instant.now().minus(Duration.ofDays(2));
            yamlCatalog("openai", "gpt-5", "gpt-4o");
            ModelConfigOverrideEntity first = row(1, "openai", "gpt-4o", 1, true, false);
            first.setCreatedAt(old);
            ModelConfigOverrideEntity fresh = row(2, "openai", "gpt-5", 2, true, false);
            fresh.setCreatedAt(recent);
            overrides(first, fresh);
            ModelCategorySettingsEntity sidecar = new ModelCategorySettingsEntity();
            sidecar.setModelConfigId(2L);
            sidecar.setCategory("browser_agent");
            sidecar.setEnabled(true);
            when(categoryRepository.findByCategory("browser_agent")).thenReturn(List.of(sidecar));

            for (String category : new String[] {null, "browser_agent"}) {
                List<Map<String, Object>> list = service.getEffectiveModelList(category);
                assertThat(entryFor(list, "gpt-5"))
                        .as("category=%s", category)
                        .containsEntry("isNew", true)
                        .containsEntry("addedAt", recent.toString());
                assertThat(entryFor(list, "gpt-4o"))
                        .as("category=%s", category)
                        .containsEntry("isNew", false);
            }
        }

        @Test
        @DisplayName("a model outside the YAML base (feed, bundle or admin-added) gets the badge too")
        void injectedRowGetsTheBadge() {
            Instant recent = Instant.now().minus(Duration.ofDays(1));
            yamlCatalog("openai", "gpt-5");
            ModelConfigOverrideEntity first = row(1, "openai", "gpt-5", 1, true, false);
            first.setCreatedAt(Instant.now().minus(Duration.ofDays(300)));
            ModelConfigOverrideEntity feedRow = row(2, "openai", "gpt-5.5", 2, false, false);
            feedRow.setCreatedAt(recent);
            ModelConfigOverrideEntity custom = row(3, "openai", "my-local-model", 3, true, false);
            custom.setCustom(true);
            custom.setCreatedAt(recent);
            overrides(first, feedRow, custom);

            List<Map<String, Object>> list = service.getEffectiveModelList(null);

            assertThat(entryFor(list, "gpt-5.5")).containsEntry("isNew", true).containsEntry("addedAt", recent.toString());
            assertThat(entryFor(list, "my-local-model")).containsEntry("isNew", true);
            assertThat(entryFor(list, "gpt-5")).containsEntry("isNew", false);
        }

        @Test
        @DisplayName("the admin list reports the stored unlisted flag next to enabled")
        void adminListReportsUnlisted() {
            yamlCatalog("openai", "gpt-5", "gpt-4o");
            overrides(row(1, "openai", "gpt-5", 1, true, false),
                      row(2, "openai", "gpt-4o", 2, true, true));

            List<Map<String, Object>> list = service.getEffectiveModelList(null);

            assertThat(entryFor(list, "gpt-4o")).containsEntry("unlisted", true).containsEntry("enabled", true);
            assertThat(entryFor(list, "gpt-5")).containsEntry("unlisted", false);
        }
    }

    @Test
    @DisplayName("retiring a model clears the flag: a restore brings it back OFF, and off carries none")
    void retireClearsUnlisted() {
        ModelConfigOverrideEntity unlisted = row(5, "openai", "gpt-4o", 5, true, true);
        when(repository.findByProviderAndModelId("openai", "gpt-4o")).thenReturn(Optional.of(unlisted));

        service.retireModels(List.of(new ModelCatalogService.ModelRef("openai", "gpt-4o")), "admin");

        assertThat(unlisted.isRetired()).isTrue();
        assertThat(unlisted.getEnabled()).isFalse();
        assertThat(unlisted.isUnlisted()).isFalse();
    }

    @Test
    @DisplayName("the new window is two weeks and the initial-fill grace one hour")
    void windowConstants() {
        // Pinned because the admin panel copy says "added in the last 14 days".
        assertThat(ModelCatalogService.NEW_MODEL_WINDOW).isEqualTo(Duration.ofDays(14));
        assertThat(ModelCatalogService.INITIAL_FILL_GRACE).isEqualTo(Duration.ofHours(1));
    }
}

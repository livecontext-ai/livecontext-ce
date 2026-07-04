package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelCategorySettingsId;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.AuthPricingSyncClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V156 applier contract - when a bundle payload carries a {@code categories}
 * map per row, {@link CatalogMergeService#merge} writes it into
 * {@code model_category_settings}.
 *
 * <p>Covers:
 * <ul>
 *   <li>insert path → category written with the parent row's new id</li>
 *   <li>update path → category written with the existing row's id</li>
 *   <li>row without {@code categories} key → no sidecar write</li>
 *   <li>is_custom row → category writes are skipped (mirrors parent rule)</li>
 *   <li>invalid category key → skipped, log only</li>
 *   <li>existing sidecar row → updated in place (idempotent re-apply)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogMergeService - V156 category sidecar apply")
class CatalogMergeServiceCategoryApplyTest {

    @Mock private ModelConfigOverrideRepository modelRepo;
    @Mock private ModelCategorySettingsRepository categoryRepo;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private CatalogMergeService merge;

    @BeforeEach
    void setUp() {
        merge = new CatalogMergeService(modelRepo, categoryRepo, authPricingSyncClient, null);
    }

    @Test
    @DisplayName("Insert path writes category sidecar rows with the persisted parent id")
    void insertPathWritesCategories() {
        // No existing parent row.
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.empty());
        // save returns a managed entity carrying the new id.
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });
        when(categoryRepo.findById(any())).thenReturn(Optional.empty());

        merge.merge(List.of(modelMap("openai", "gpt-5", Map.of(
                "chat", Map.of("enabled", true, "rank", 1),
                "browser_agent", Map.of("enabled", false, "rank", 5)
        ))), MergeOptions.forBundle(99L));

        ArgumentCaptor<ModelCategorySettingsEntity> captor =
                ArgumentCaptor.forClass(ModelCategorySettingsEntity.class);
        verify(categoryRepo, times(2)).save(captor.capture());
        // Both writes target the new parent id.
        assertThat(captor.getAllValues())
                .extracting(ModelCategorySettingsEntity::getModelConfigId)
                .containsOnly(42L);
        assertThat(captor.getAllValues())
                .extracting(ModelCategorySettingsEntity::getCategory,
                            ModelCategorySettingsEntity::getRank,
                            ModelCategorySettingsEntity::getEnabled)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("chat", 1, true),
                        org.assertj.core.groups.Tuple.tuple("browser_agent", 5, false));
    }

    @Test
    @DisplayName("Row without `categories` field → no sidecar write")
    void absentCategoriesNoOp() {
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", "openai");
        row.put("modelId", "gpt-5");
        // No "categories" key - V1 bundle shape.
        merge.merge(List.of(row), MergeOptions.forBundle(99L));

        verify(categoryRepo, never()).save(any());
        verify(categoryRepo, never()).findById(any());
    }

    @Test
    @DisplayName("is_custom parent row → categories skipped wholesale (mirrors parent rule)")
    void customRowSkipsCategorySidecar() {
        ModelConfigOverrideEntity custom = new ModelConfigOverrideEntity();
        custom.setId(7L);
        custom.setProvider("openai");
        custom.setModelId("gpt-5");
        custom.setCustom(true);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.of(custom));

        merge.merge(List.of(modelMap("openai", "gpt-5", Map.of(
                "chat", Map.of("enabled", true, "rank", 1)
        ))), MergeOptions.forBundle(99L));

        // is_custom=true short-circuits the per-row update before the
        // category-apply step would consider it. The category sidecar write
        // sees no eligible row and never fires.
        verify(categoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("Update path: existing sidecar row is updated in place (idempotent re-apply)")
    void updatePathUpdatesExistingSidecar() {
        ModelConfigOverrideEntity row = new ModelConfigOverrideEntity();
        row.setId(7L);
        row.setProvider("openai");
        row.setModelId("gpt-5");
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.of(row));

        ModelCategorySettingsEntity existing = new ModelCategorySettingsEntity();
        existing.setModelConfigId(7L);
        existing.setCategory("chat");
        existing.setRank(99);
        existing.setEnabled(true);
        when(categoryRepo.findById(new ModelCategorySettingsId(7L, "chat")))
                .thenReturn(Optional.of(existing));

        merge.merge(List.of(modelMap("openai", "gpt-5", Map.of(
                "chat", Map.of("enabled", false, "rank", 1)
        ))), MergeOptions.forBundle(99L));

        ArgumentCaptor<ModelCategorySettingsEntity> captor =
                ArgumentCaptor.forClass(ModelCategorySettingsEntity.class);
        verify(categoryRepo).save(captor.capture());
        // Same instance was mutated, not a new row.
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(captor.getValue().getRank()).isEqualTo(1);
        assertThat(captor.getValue().getEnabled()).isFalse();
    }

    @Test
    @DisplayName("REGRESSION (audit caveat): CE-local sidecar row on a non-custom parent IS deleted when the bundle doesn't declare its category - documented authority behaviour")
    void ceLocalSidecarOnNonCustomParentIsDeletedByBundle() {
        // Scenario: CE admin manually toggled (model 7, image_generation,
        // enabled=false) on a non-custom row. Cloud bundle for the same model
        // declares only 'chat' - image_generation is not in the payload's
        // categories map. Per the documented authority rule (cloud is
        // authoritative for non-custom rows), the local image_generation
        // sidecar row gets deleted.
        ModelConfigOverrideEntity row = new ModelConfigOverrideEntity();
        row.setId(7L);
        row.setProvider("openai");
        row.setModelId("gpt-5");
        // is_custom = false - non-CE-managed row.
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.of(row));

        ModelCategorySettingsEntity ceLocalImg = sidecar(7L, "image_generation", 1, false);
        when(categoryRepo.findById(new ModelCategorySettingsId(7L, "chat")))
                .thenReturn(Optional.empty());
        when(categoryRepo.findByModelConfigId(7L)).thenReturn(List.of(ceLocalImg));

        merge.merge(List.of(modelMap("openai", "gpt-5", Map.of(
                "chat", Map.of("enabled", true, "rank", 1)
        ))), MergeOptions.forBundle(99L));

        // The CE-local image_generation row is deleted. To survive a bundle
        // apply, the parent row must be is_custom=true (then the whole
        // sidecar is skipped - covered by customRowSkipsCategorySidecar).
        verify(categoryRepo).delete(ceLocalImg);
    }

    @Test
    @DisplayName("REGRESSION (audit P1#1): bundle apply DELETES sidecar rows whose category vanished from the payload")
    void bundleApplyDeletesVanishedCategories() {
        // Existing parent row (row id=7); existing sidecar covers chat + browser_agent.
        ModelConfigOverrideEntity row = new ModelConfigOverrideEntity();
        row.setId(7L);
        row.setProvider("openai");
        row.setModelId("gpt-5");
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.of(row));

        ModelCategorySettingsEntity localChat = sidecar(7L, "chat", 1, true);
        ModelCategorySettingsEntity localBrowser = sidecar(7L, "browser_agent", 2, true);
        // After merge() saves the chat upsert, findByModelConfigId returns ALL rows
        // including the still-present chat - only browser_agent (vanished from payload)
        // is the deletion target.
        when(categoryRepo.findById(new ModelCategorySettingsId(7L, "chat")))
                .thenReturn(Optional.of(localChat));
        when(categoryRepo.findByModelConfigId(7L))
                .thenReturn(List.of(localChat, localBrowser));

        // Payload only declares 'chat' - 'browser_agent' is gone.
        merge.merge(List.of(modelMap("openai", "gpt-5", Map.of(
                "chat", Map.of("enabled", true, "rank", 1)
        ))), MergeOptions.forBundle(99L));

        // chat is upserted (left in place), browser_agent is deleted.
        verify(categoryRepo).delete(localBrowser);
        verify(categoryRepo, never()).delete(localChat);
    }

    @Test
    @DisplayName("Invalid category key in payload → skipped, no sidecar write, no exception")
    void invalidCategoryKeyIgnored() {
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });

        merge.merge(List.of(modelMap("openai", "gpt-5", Map.of(
                "Chat",          Map.of("enabled", true, "rank", 1),  // uppercase - invalid
                "with-dash",     Map.of("enabled", true, "rank", 2)   // hyphen - invalid
        ))), MergeOptions.forBundle(99L));

        verify(categoryRepo, never()).save(any());
    }

    // ── SEED path: default-category backfill (buildSeedExport omits the sidecar) ──

    @Test
    @DisplayName("SEED insert without categories → mode-aware defaults (chat + browser_agent), enabled, rank null")
    void seedInsertWithoutCategoriesGetsChatDefaults() {
        when(modelRepo.findByProviderAndModelId("anthropic", "claude-opus-4-8"))
                .thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });
        when(categoryRepo.findById(any())).thenReturn(Optional.empty());

        // Seed shape: no "categories" key, no "mode" → chat-capable.
        merge.merge(List.of(seedModelMap("anthropic", "claude-opus-4-8", null)),
                MergeOptions.forSeed());

        ArgumentCaptor<ModelCategorySettingsEntity> captor =
                ArgumentCaptor.forClass(ModelCategorySettingsEntity.class);
        verify(categoryRepo, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ModelCategorySettingsEntity::getModelConfigId)
                .containsOnly(42L);
        assertThat(captor.getAllValues())
                .extracting(ModelCategorySettingsEntity::getCategory,
                            ModelCategorySettingsEntity::getEnabled,
                            ModelCategorySettingsEntity::getRank)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("chat", true, null),
                        org.assertj.core.groups.Tuple.tuple("browser_agent", true, null));
    }

    @Test
    @DisplayName("SEED insert with mode=image → default image_generation only (no chat/browser_agent)")
    void seedInsertImageModeGetsImageGenerationOnly() {
        when(modelRepo.findByProviderAndModelId("openai", "gpt-image-1"))
                .thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(7L);
            return e;
        });
        when(categoryRepo.findById(any())).thenReturn(Optional.empty());

        merge.merge(List.of(seedModelMap("openai", "gpt-image-1", "image")),
                MergeOptions.forSeed());

        ArgumentCaptor<ModelCategorySettingsEntity> captor =
                ArgumentCaptor.forClass(ModelCategorySettingsEntity.class);
        verify(categoryRepo, times(1)).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo("image_generation");
        assertThat(captor.getValue().getEnabled()).isTrue();
    }

    @Test
    @DisplayName("SEED insert: an already-present category row is left untouched (idempotent re-apply)")
    void seedInsertIsIdempotentForExistingCategory() {
        when(modelRepo.findByProviderAndModelId("anthropic", "claude-opus-4-8"))
                .thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });
        // chat already exists → skip; browser_agent absent → create.
        when(categoryRepo.findById(new ModelCategorySettingsId(42L, "chat")))
                .thenReturn(Optional.of(sidecar(42L, "chat", 3, true)));
        when(categoryRepo.findById(new ModelCategorySettingsId(42L, "browser_agent")))
                .thenReturn(Optional.empty());

        merge.merge(List.of(seedModelMap("anthropic", "claude-opus-4-8", null)),
                MergeOptions.forSeed());

        ArgumentCaptor<ModelCategorySettingsEntity> captor =
                ArgumentCaptor.forClass(ModelCategorySettingsEntity.class);
        verify(categoryRepo, times(1)).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo("browser_agent");
    }

    @Test
    @DisplayName("SEED UPDATE (existing model) → NO default categories synthesised (only inserts are backfilled)")
    void seedUpdateDoesNotSynthesiseDefaults() {
        ModelConfigOverrideEntity existing = new ModelConfigOverrideEntity();
        existing.setId(7L);
        existing.setProvider("anthropic");
        existing.setModelId("claude-opus-4-6");
        when(modelRepo.findByProviderAndModelId("anthropic", "claude-opus-4-6"))
                .thenReturn(Optional.of(existing));

        merge.merge(List.of(seedModelMap("anthropic", "claude-opus-4-6", null)),
                MergeOptions.forSeed());

        // Update path: the row already exists, so no default-category insert.
        verify(categoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("BUNDLE insert without categories → NO default synthesis (bundle is authoritative for its sidecar)")
    void bundleInsertWithoutCategoriesNoDefaultSynthesis() {
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });

        // Bundle path, no categories → must stay a no-op (guards the seed-only gate).
        merge.merge(List.of(seedModelMap("openai", "gpt-5", null)),
                MergeOptions.forBundle(99L));

        verify(categoryRepo, never()).save(any());
    }

    /** Seed-shaped model map: provider/modelId/displayName, optional mode, NO categories. */
    private static Map<String, Object> seedModelMap(String provider, String modelId, String mode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("modelId", modelId);
        m.put("displayName", modelId);
        if (mode != null) m.put("mode", mode);
        return m;
    }

    private static Map<String, Object> modelMap(String provider, String modelId,
                                                Map<String, Object> categories) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("modelId", modelId);
        m.put("displayName", modelId);
        m.put("categories", categories);
        return m;
    }

    private static ModelCategorySettingsEntity sidecar(long modelConfigId, String category,
                                                       int rank, boolean enabled) {
        ModelCategorySettingsEntity s = new ModelCategorySettingsEntity();
        s.setModelConfigId(modelConfigId);
        s.setCategory(category);
        s.setRank(rank);
        s.setEnabled(enabled);
        return s;
    }
}

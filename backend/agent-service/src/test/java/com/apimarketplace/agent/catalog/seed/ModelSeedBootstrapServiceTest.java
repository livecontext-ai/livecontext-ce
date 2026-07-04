package com.apimarketplace.agent.catalog.seed;

import com.apimarketplace.agent.catalog.bundle.CatalogMergeService;
import com.apimarketplace.agent.catalog.bundle.MergeOptions;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.domain.ModelSeedStateEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.repository.ModelSeedStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The boot-time model-catalog seed is VERSION-GATED and update-capable: when the
 * shipped {@code models.json} version is higher than the last-applied marker it
 * runs the FULL {@link CatalogMergeService} merge (same path as the signed
 * bundle, so {@code is_custom} + {@code user_modified_fields} + the seed's
 * partial-update semantics preserve operator edits AND omitted enrichment),
 * advances the marker, and is a pure no-op on an unchanged version. An
 * UNVERSIONED (or non-positive) seed falls back to legacy insert-only. A
 * bad/missing seed never crash-loops the app.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelSeedBootstrapService - versioned, update-capable seed")
class ModelSeedBootstrapServiceTest {

    @Mock private CatalogMergeService merge;
    @Mock private ModelConfigOverrideRepository repo;
    @Mock private ModelSeedStateRepository seedStateRepo;
    @Mock private PlatformTransactionManager txManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TWO_MODELS_V1 = """
        {"version":1,"models":[
          {"provider":"openai","modelId":"gpt-5.4","displayName":"gpt-5.4","enabled":true,"priceInput":"2.50","priceOutput":"15.00"},
          {"provider":"anthropic","modelId":"claude-x","displayName":"claude-x","enabled":true}
        ]}""";

    private static final String TWO_MODELS_V2 = TWO_MODELS_V1.replace("\"version\":1", "\"version\":2");

    private static final String VERSION_ZERO = TWO_MODELS_V1.replace("\"version\":1", "\"version\":0");

    private static final String UNVERSIONED = """
        {"models":[
          {"provider":"openai","modelId":"gpt-5.4","displayName":"gpt-5.4","enabled":true},
          {"provider":"anthropic","modelId":"claude-x","displayName":"claude-x","enabled":true}
        ]}""";

    private ModelSeedBootstrapService service(Resource res) {
        return new ModelSeedBootstrapService(merge, repo, seedStateRepo, objectMapper, txManager, res);
    }

    private static Resource json(String body) {
        return new ByteArrayResource(body.getBytes(StandardCharsets.UTF_8));
    }

    private void stubTx() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private static ModelConfigOverrideEntity existing(String provider, String modelId) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(modelId);
        return e;
    }

    private static ModelSeedStateEntity marker(long appliedVersion) {
        ModelSeedStateEntity m = new ModelSeedStateEntity();
        m.setAppliedVersion(appliedVersion);
        return m;
    }

    // ── Versioned: first apply / version bump run the FULL merge ──────────────

    @Test
    @DisplayName("First apply (no marker): the FULL seed set goes through merge, unfiltered, and the marker advances")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void firstApplyRunsFullMergeAndAdvancesMarker() throws Exception {
        // marker findById defaults to Optional.empty() (never applied).
        stubTx();
        when(merge.merge(anyList(), any())).thenReturn(new CatalogMergeService.MergeResult(1, 1, 0, 0, 0, 0));

        int inserted = service(json(TWO_MODELS_V1)).seedNow();

        ArgumentCaptor<List> listCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<MergeOptions> optsCaptor = ArgumentCaptor.forClass(MergeOptions.class);
        verify(merge).merge(listCaptor.capture(), optsCaptor.capture());
        assertThat(listCaptor.getValue())
                .as("update-capable: ALL seed models are passed to merge, not just the absent ones")
                .hasSize(2);
        assertThat(optsCaptor.getValue().deprecateMissing())
                .as("seed must not mass-deprecate models it omits")
                .isFalse();
        assertThat(optsCaptor.getValue().partialUpdate())
                .as("seed merge is a PATCH so it never nulls omitted enrichment")
                .isTrue();

        // Never pre-filters against the existing catalog on the versioned path.
        verify(repo, never()).findAllByOrderByRankingAsc();

        ArgumentCaptor<ModelSeedStateEntity> markerCaptor = ArgumentCaptor.forClass(ModelSeedStateEntity.class);
        verify(seedStateRepo).save(markerCaptor.capture());
        assertThat(markerCaptor.getValue().getAppliedVersion()).isEqualTo(1L);
        assertThat(markerCaptor.getValue().getAppliedAt()).isNotNull();
        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("Version bump (seed v2 > marker v1): the seed re-applies via merge")
    void versionBumpReapplies() throws Exception {
        when(seedStateRepo.findById(ModelSeedStateEntity.SINGLETON_ID)).thenReturn(Optional.of(marker(1)));
        stubTx();
        when(merge.merge(anyList(), any())).thenReturn(new CatalogMergeService.MergeResult(0, 2, 0, 0, 0, 0));

        service(json(TWO_MODELS_V2)).seedNow();

        verify(merge).merge(anyList(), any(MergeOptions.class));
        ArgumentCaptor<ModelSeedStateEntity> markerCaptor = ArgumentCaptor.forClass(ModelSeedStateEntity.class);
        verify(seedStateRepo).save(markerCaptor.capture());
        assertThat(markerCaptor.getValue().getAppliedVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Unchanged version (marker >= seed version): pure no-op, no merge, no marker write")
    void unchangedVersionIsNoOp() throws Exception {
        when(seedStateRepo.findById(ModelSeedStateEntity.SINGLETON_ID)).thenReturn(Optional.of(marker(1)));

        int inserted = service(json(TWO_MODELS_V1)).seedNow();

        verify(merge, never()).merge(anyList(), any());
        verify(seedStateRepo, never()).save(any());
        assertThat(inserted).isZero();
    }

    @Test
    @DisplayName("Older seed than what is applied (marker v2 > seed v1): no downgrade")
    void olderSeedThanAppliedIsNoOp() throws Exception {
        when(seedStateRepo.findById(ModelSeedStateEntity.SINGLETON_ID)).thenReturn(Optional.of(marker(2)));

        int inserted = service(json(TWO_MODELS_V1)).seedNow();

        verify(merge, never()).merge(anyList(), any());
        verify(seedStateRepo, never()).save(any());
        assertThat(inserted).isZero();
    }

    // ── Unversioned / non-positive fallback: legacy additive insert-only ──────

    @Test
    @DisplayName("A non-positive version (0) is treated as unversioned -> insert-only fallback, no marker write")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void versionZeroFallsBackToInsertOnly() throws Exception {
        when(repo.findAllByOrderByRankingAsc()).thenReturn(List.of(existing("openai", "gpt-5.4")));
        stubTx();
        when(merge.merge(anyList(), any())).thenReturn(new CatalogMergeService.MergeResult(1, 0, 0, 0, 0, 0));

        service(json(VERSION_ZERO)).seedNow();

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(merge).merge(captor.capture(), any(MergeOptions.class));
        assertThat(captor.getValue()).as("insert-only: only the absent model is applied").hasSize(1);
        verify(seedStateRepo, never()).save(any());
    }

    @Test
    @DisplayName("Unversioned seed: inserts ONLY absent models and never advances the marker")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void unversionedSeedIsInsertOnly() throws Exception {
        when(repo.findAllByOrderByRankingAsc()).thenReturn(List.of(existing("openai", "gpt-5.4")));
        stubTx();
        when(merge.merge(anyList(), any())).thenReturn(new CatalogMergeService.MergeResult(1, 0, 0, 0, 0, 0));

        int inserted = service(json(UNVERSIONED)).seedNow();

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(merge).merge(captor.capture(), any(MergeOptions.class));
        List<Map<String, Object>> applied = captor.getValue();
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).get("modelId")).isEqualTo("claude-x");
        verify(seedStateRepo, never()).save(any());
        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("Unversioned seed, all present: merge is never called")
    void unversionedSeedAllPresentNoMerge() throws Exception {
        when(repo.findAllByOrderByRankingAsc()).thenReturn(List.of(
                existing("openai", "gpt-5.4"), existing("anthropic", "claude-x")));

        int inserted = service(json(UNVERSIONED)).seedNow();

        verify(merge, never()).merge(anyList(), any());
        verify(seedStateRepo, never()).save(any());
        assertThat(inserted).isZero();
    }

    // ── Failure safety ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Missing seed resource is a no-op - never blocks startup")
    void missingResourceIsNoOp() throws Exception {
        Resource missing = Mockito.mock(Resource.class);
        when(missing.exists()).thenReturn(false);

        int inserted = service(missing).seedNow();

        verify(merge, never()).merge(anyList(), any());
        assertThat(inserted).isZero();
    }

    @Test
    @DisplayName("Malformed seed JSON is swallowed by the startup hook (app keeps booting)")
    void malformedJsonSwallowedOnStartup() {
        service(json("{ not valid json")).seedOnStartup();
        verify(merge, never()).merge(anyList(), any());
    }

    @Test
    @DisplayName("The SHIPPED classpath seed (model-catalog/models.json) parses and merges into an empty DB")
    void realClasspathSeedSeedsEmptyDb() throws Exception {
        stubTx();
        when(merge.merge(anyList(), any())).thenAnswer(inv -> {
            List<?> applied = inv.getArgument(0);
            return new CatalogMergeService.MergeResult(applied.size(), 0, 0, 0, 0, 0);
        });

        Resource real = new ClassPathResource("model-catalog/models.json");
        assertThat(real.exists())
                .as("the seed resource must ship on the agent-service classpath")
                .isTrue();

        int inserted = service(real).seedNow();

        assertThat(inserted)
                .as("the curated seed should carry the full catalog; floor guards a gutted resource")
                .isGreaterThanOrEqualTo(50);
        verify(seedStateRepo).save(any(ModelSeedStateEntity.class));
    }
}

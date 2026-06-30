package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.AuthPricingSyncClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the ranking-reset-on-sync bug.
 *
 * <p>Root cause: admin drag-drop wrote {@code ranking} to
 * {@code model_config_overrides} but did NOT add {@code "ranking"} to
 * {@code user_modified_fields}. The next LiteLLM/OpenRouter sync called
 * {@code applyFields()} which saw ranking was unprotected and overwrote it
 * with {@code null} (feeds don't carry ranking). All admin ordering was lost.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogMergeService - ranking protection from sync overwrite")
class CatalogMergeServiceRankingProtectionTest {

    @Mock private ModelConfigOverrideRepository modelRepo;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private CatalogMergeService merge;

    @BeforeEach
    void setUp() {
        merge = new CatalogMergeService(modelRepo, null, authPricingSyncClient, null);
    }

    @Test
    @DisplayName("Sync does not overwrite admin-set ranking when ranking is in userModifiedFields")
    void syncPreservesAdminRankingWhenProtected() {
        ModelConfigOverrideEntity row = existingRow("openai", "gpt-5.4", 3);
        row.setUserModifiedFields(new String[]{"ranking"});
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.4"))
                .thenReturn(Optional.of(row));

        Map<String, Object> syncRow = syncPayload("openai", "gpt-5.4");

        merge.merge(List.of(syncRow), MergeOptions.forSync());

        assertThat(row.getRanking()).isEqualTo(3);
    }

    @Test
    @DisplayName("Sync does not overwrite existing ranking with null even when field is unprotected (defense-in-depth)")
    void syncDoesNotNullifyUnprotectedRanking() {
        ModelConfigOverrideEntity row = existingRow("openai", "gpt-5.4", 7);
        row.setUserModifiedFields(new String[0]);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.4"))
                .thenReturn(Optional.of(row));

        Map<String, Object> syncRow = syncPayload("openai", "gpt-5.4");

        merge.merge(List.of(syncRow), MergeOptions.forSync());

        assertThat(row.getRanking()).isEqualTo(7);
    }

    @Test
    @DisplayName("Bundle with explicit ranking still overwrites when field is unprotected")
    void bundleExplicitRankingOverwritesUnprotected() {
        ModelConfigOverrideEntity row = existingRow("openai", "gpt-5.4", 7);
        row.setUserModifiedFields(new String[0]);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.4"))
                .thenReturn(Optional.of(row));

        Map<String, Object> bundleRow = syncPayload("openai", "gpt-5.4");
        bundleRow.put("ranking", 42);

        merge.merge(List.of(bundleRow), MergeOptions.forBundle(1L));

        assertThat(row.getRanking()).isEqualTo(42);
    }

    @Test
    @DisplayName("Newly inserted model gets sequential ranking after current max")
    void newModelGetsSequentialRankingAfterMax() {
        when(modelRepo.findMaxRanking()).thenReturn(50);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.5"))
                .thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(100L);
            return e;
        });

        Map<String, Object> newRow = syncPayload("openai", "gpt-5.5");

        merge.merge(List.of(newRow), MergeOptions.forSync());

        ArgumentCaptor<ModelConfigOverrideEntity> captor =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo).save(captor.capture());
        assertThat(captor.getValue().getRanking()).isEqualTo(51);
    }

    @Test
    @DisplayName("Multiple new models get sequential rankings in insertion order")
    void multipleNewModelsGetSequentialRankings() {
        when(modelRepo.findMaxRanking()).thenReturn(10);
        when(modelRepo.findByProviderAndModelId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId((long) (Math.random() * 10000));
            return e;
        });

        List<Map<String, Object>> rows = List.of(
                syncPayload("openai", "gpt-5.5"),
                syncPayload("openai", "gpt-5.5-pro"),
                syncPayload("anthropic", "claude-opus-5")
        );

        merge.merge(rows, MergeOptions.forSync());

        ArgumentCaptor<ModelConfigOverrideEntity> captor =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo, times(3)).save(captor.capture());

        List<Integer> rankings = captor.getAllValues().stream()
                .map(ModelConfigOverrideEntity::getRanking)
                .toList();
        assertThat(rankings).containsExactly(11, 12, 13);
    }

    @Test
    @DisplayName("Empty table (max=0) assigns rankings starting from 1")
    void emptyTableStartsFromOne() {
        when(modelRepo.findMaxRanking()).thenReturn(0);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.5"))
                .thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });

        merge.merge(List.of(syncPayload("openai", "gpt-5.5")), MergeOptions.forSync());

        ArgumentCaptor<ModelConfigOverrideEntity> captor =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo).save(captor.capture());
        assertThat(captor.getValue().getRanking()).isEqualTo(1);
    }

    // --- helpers ---

    private static ModelConfigOverrideEntity existingRow(String provider, String modelId, int ranking) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setId(1L);
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setRanking(ranking);
        e.setDisplayName(modelId);
        e.setPriceInput(BigDecimal.ONE);
        e.setPriceOutput(BigDecimal.TEN);
        e.setEnabled(true);
        e.setUserModifiedFields(new String[0]);
        return e;
    }

    private static Map<String, Object> syncPayload(String provider, String modelId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("modelId", modelId);
        m.put("displayName", modelId);
        m.put("priceInput", "5.00");
        m.put("priceOutput", "30.00");
        m.put("mode", "chat");
        m.put("supportsTools", true);
        m.put("source", "litellm");
        return m;
    }
}

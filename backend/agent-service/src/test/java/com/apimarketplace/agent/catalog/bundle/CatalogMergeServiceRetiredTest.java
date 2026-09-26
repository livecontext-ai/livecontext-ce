package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.AuthPricingSyncClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V533: no automated source (feed sync, CE seed, signed bundle) may bring a retired model back.
 * Before, every merge that still saw the model cleared its deprecation, a bundle or seed could
 * re-enable it, and a deleted row came back on the next sync.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogMergeService - a retired row is never touched")
class CatalogMergeServiceRetiredTest {

    @Mock private ModelConfigOverrideRepository modelRepo;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private CatalogMergeService merge;

    @BeforeEach
    void setUp() {
        merge = new CatalogMergeService(modelRepo, null, authPricingSyncClient, null);
        lenient().when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());
    }

    static Stream<MergeOptions> everyAutomatedSource() {
        return Stream.of(MergeOptions.forSync(), MergeOptions.forSeed(), MergeOptions.forBundle(99L));
    }

    private static ModelConfigOverrideEntity retiredRow() {
        ModelConfigOverrideEntity row = new ModelConfigOverrideEntity();
        row.setId(7L);
        row.setProvider("anthropic");
        row.setModelId("claude-opus-4-8");
        row.setDisplayName("Opus 4.8");
        row.setEnabled(false);
        row.setPriceInput(new BigDecimal("15.00"));
        row.setPriceOutput(new BigDecimal("75.00"));
        row.setRetiredAt(Instant.parse("2026-09-25T10:00:00Z"));
        return row;
    }

    private static Map<String, Object> incoming() {
        Map<String, Object> m = new HashMap<>();
        m.put("provider", "anthropic");
        m.put("modelId", "claude-opus-4-8");
        m.put("displayName", "Claude Opus 4.8 (feed)");
        m.put("enabled", true);
        m.put("priceInput", "5.00");
        m.put("priceOutput", "25.00");
        return m;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyAutomatedSource")
    @DisplayName("sync, seed and bundle leave a retired row exactly as it was")
    void retiredRowUntouched(MergeOptions opts) {
        ModelConfigOverrideEntity row = retiredRow();
        when(modelRepo.findByProviderAndModelId("anthropic", "claude-opus-4-8")).thenReturn(Optional.of(row));

        merge.merge(List.of(incoming()), opts);

        assertThat(row.getEnabled()).isFalse();
        assertThat(row.getRetiredAt()).isEqualTo(Instant.parse("2026-09-25T10:00:00Z"));
        assertThat(row.getDisplayName()).isEqualTo("Opus 4.8");
        assertThat(row.getPriceInput()).isEqualByComparingTo("15.00");
        verify(modelRepo, never()).save(any());
        verify(authPricingSyncClient, never())
                .sync(any(), any(), any(), any(), any(), any(), any(), any());
    }
}

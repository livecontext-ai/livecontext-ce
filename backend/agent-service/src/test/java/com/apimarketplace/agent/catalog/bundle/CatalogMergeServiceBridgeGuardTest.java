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
 * Bridge-specific behaviour of {@link CatalogMergeService}.
 *
 * <p>Post-V128 semantics (shipped together with {@code BridgeAllowlist.java}
 * and the claude-adapter verbatim-forward fix):
 *
 * <ul>
 *   <li><b>Bundle apply CAN mutate bridge rows</b>. The cloud's hand-curated
 *       {@code BridgeAllowlist} evolves over time; CE clients must follow
 *       via the next bundle. The old "skip on existing bridge" was removed.</li>
 *   <li><b>Feed sync ({@code MergeOptions.forSync()}) never touches bridges</b>
 *       - {@code ModelCatalogSyncService.EXCLUDED_PROVIDERS} filters them
 *       out upstream, before they reach merge(). So a misbehaving feed
 *       cannot write to a bridge row even if it crafted a fake entry.</li>
 *   <li><b>Fresh insert of a bridge provider forces {@code provider_kind='bridge'}</b>
 *       - defends against {@code CatalogBundlePayload} stripping provider_kind
 *       from the canonical encoding: a new {@code claude-code} row arriving
 *       via bundle apply on CE lands with kind=bridge regardless of what the
 *       payload said.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogMergeService - bridge row semantics (post-V128)")
class CatalogMergeServiceBridgeGuardTest {

    @Mock private ModelConfigOverrideRepository modelRepo;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private CatalogMergeService merge;

    @BeforeEach
    void setUp() {
        merge = new CatalogMergeService(modelRepo, null, authPricingSyncClient, null);
    }

    @Test
    @DisplayName("Bundle apply updates an existing bridge row (kind stays bridge)")
    void bundleApplyUpdatesBridgeRow() {
        // Existing bridge row - V128 seed, enabled, rates=0.
        ModelConfigOverrideEntity row = new ModelConfigOverrideEntity();
        row.setProvider("claude-code");
        row.setModelId("claude-opus-4-7");
        row.setProviderKind("bridge");
        row.setPriceInput(BigDecimal.ZERO);
        row.setPriceOutput(BigDecimal.ZERO);
        row.setEnabled(true);
        row.setUserModifiedFields(new String[0]);
        when(modelRepo.findByProviderAndModelId("claude-code", "claude-opus-4-7"))
                .thenReturn(Optional.of(row));

        // Bundle snapshot includes the same bridge row with a display_name bump.
        Map<String, Object> bundleRow = new LinkedHashMap<>();
        bundleRow.put("provider", "claude-code");
        bundleRow.put("modelId", "claude-opus-4-7");
        bundleRow.put("displayName", "Claude Opus 4.7 (updated)");
        bundleRow.put("priceInput", "0");
        bundleRow.put("priceOutput", "0");

        CatalogMergeService.MergeResult result = merge.merge(
                List.of(bundleRow), MergeOptions.forBundle(99L));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.inserted()).isZero();
        assertThat(result.skippedCustom()).isZero();
        verify(modelRepo).save(row);
        assertThat(row.getDisplayName()).isEqualTo("Claude Opus 4.7 (updated)");
        // Bridge kind preserved (applyFields doesn't touch providerKind).
        assertThat(row.getProviderKind()).isEqualTo("bridge");
    }

    @Test
    @DisplayName("Fresh insert under a bridge provider forces provider_kind='bridge'")
    void freshBridgeInsertForcesKind() {
        when(modelRepo.findByProviderAndModelId("codex", "gpt-5.5")).thenReturn(Optional.empty());

        // Bundle publishes a new codex model. The payload does NOT carry
        // provider_kind (CatalogBundlePayload doesn't serialize it today).
        Map<String, Object> newBridgeRow = new LinkedHashMap<>();
        newBridgeRow.put("provider", "codex");
        newBridgeRow.put("modelId", "gpt-5.5");
        newBridgeRow.put("displayName", "GPT-5.5");
        newBridgeRow.put("priceInput", "0");
        newBridgeRow.put("priceOutput", "0");

        CatalogMergeService.MergeResult result = merge.merge(
                List.of(newBridgeRow), MergeOptions.forBundle(99L));

        assertThat(result.inserted()).isEqualTo(1);

        ArgumentCaptor<ModelConfigOverrideEntity> saved =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo).save(saved.capture());
        // Force-set to 'bridge' regardless of what the payload (didn't) say.
        assertThat(saved.getValue().getProviderKind()).isEqualTo("bridge");
    }

    @Test
    @DisplayName("Bundle deprecate loop deprecates obsolete bridge rows when cloud's allowlist shrinks")
    void bundleDeprecatesObsoleteBridge() {
        ModelConfigOverrideEntity staleBridge = new ModelConfigOverrideEntity();
        staleBridge.setProvider("codex");
        staleBridge.setModelId("gpt-5.1-old");
        staleBridge.setProviderKind("bridge");
        staleBridge.setUserModifiedFields(new String[0]);

        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(staleBridge));

        // Bundle's incoming set does NOT contain gpt-5.1-old → cloud removed
        // it from the allowlist. CE must follow and deprecate.
        CatalogMergeService.MergeResult result = merge.merge(
                Collections.emptyList(), MergeOptions.forBundle(99L));

        assertThat(result.deprecated()).isEqualTo(1);
        verify(modelRepo).save(staleBridge);
        assertThat(staleBridge.getDeprecatedAt()).isNotNull();
    }

    @Test
    @DisplayName("forSync() never deprecates anything (not even non-bridge rows)")
    void syncPathNeverDeprecates() {
        // Pre-condition: even if merge scanned rows, nothing gets deprecated.
        CatalogMergeService.MergeResult result = merge.merge(
                Collections.emptyList(), MergeOptions.forSync());

        assertThat(result.deprecated()).isZero();
        // Scan is skipped entirely because deprecateMissing=false.
        verify(modelRepo, never()).findAllByOrderByRankingAsc();
    }
}

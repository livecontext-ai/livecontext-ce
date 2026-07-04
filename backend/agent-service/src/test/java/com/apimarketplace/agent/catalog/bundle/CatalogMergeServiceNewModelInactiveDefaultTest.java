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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * New models introduced by a FEED SYNC land INACTIVE by default - a refresh can
 * add many models at once and auto-enabling them would silently expose
 * un-reviewed models to the picker and chat. The admin opts each one in from
 * /settings/ai-providers. Only fresh INSERTS are forced off; existing rows
 * keep their current enabled state untouched.
 *
 * <p>The review-gate is SYNC-ONLY since V381. Both trusted, cloud-curated
 * paths honor the payload's enabled on insert ({@code honorEnabledOnInsert=true}):
 * <ul>
 *   <li>SEED ({@link MergeOptions#forSeed()}): the curated code-shipped
 *       baseline, usable out of the box on a fresh CE.</li>
 *   <li>BUNDLE ({@link MergeOptions#forBundle(long)}): the payload's effective
 *       enabled is an explicit per-model cloud-admin decision
 *       ({@code bundle_enabled}) about what CE installs receive - force-off on
 *       insert would silently veto it for every model the CE had not seen.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogMergeService - new models default to inactive")
class CatalogMergeServiceNewModelInactiveDefaultTest {

    @Mock private ModelConfigOverrideRepository modelRepo;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private CatalogMergeService merge;

    @BeforeEach
    void setUp() {
        merge = new CatalogMergeService(modelRepo, null, authPricingSyncClient, null);
    }

    @Test
    @DisplayName("New model from a feed sync is saved disabled even when the payload says enabled=true")
    void newSyncModelForcedInactiveDespitePayloadEnabled() {
        when(modelRepo.findMaxRanking()).thenReturn(0);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.5")).thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });

        Map<String, Object> row = payload("openai", "gpt-5.5");
        row.put("enabled", true); // feed explicitly enables it - must be ignored on insert

        merge.merge(List.of(row), MergeOptions.forSync());

        ArgumentCaptor<ModelConfigOverrideEntity> captor =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo).save(captor.capture());
        assertThat(captor.getValue().getEnabled())
                .as("a freshly inserted model must land inactive regardless of payload")
                .isFalse();
    }

    @Test
    @DisplayName("V381: new model from a signed bundle keeps the payload's enabled - the cloud decision ships")
    void newBundleModelInsertHonorsPayloadEnabled() {
        when(modelRepo.findMaxRanking()).thenReturn(0);
        when(modelRepo.findByProviderAndModelId("anthropic", "claude-opus-5")).thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(2L);
            return e;
        });

        Map<String, Object> row = payload("anthropic", "claude-opus-5");
        row.put("enabled", true);

        merge.merge(List.of(row), MergeOptions.forBundle(1L));

        ArgumentCaptor<ModelConfigOverrideEntity> captor =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo).save(captor.capture());
        assertThat(captor.getValue().getEnabled())
                .as("V381: the bundle's effective enabled is a cloud-admin decision - it ships")
                .isTrue();
    }

    @Test
    @DisplayName("V381: bundle INSERT ships enabled=false when the cloud withheld the model (bundle_enabled=false upstream)")
    void newBundleModelInsertHonorsPayloadDisabled() {
        when(modelRepo.findMaxRanking()).thenReturn(0);
        when(modelRepo.findByProviderAndModelId("anthropic", "claude-opus-5")).thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity e = inv.getArgument(0);
            e.setId(3L);
            return e;
        });

        Map<String, Object> row = payload("anthropic", "claude-opus-5");
        row.put("enabled", false); // cloud explicitly withholds it from CE use

        merge.merge(List.of(row), MergeOptions.forBundle(1L));

        ArgumentCaptor<ModelConfigOverrideEntity> captor =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo).save(captor.capture());
        assertThat(captor.getValue().getEnabled())
                .as("honorEnabledOnInsert keeps the payload's explicit false too")
                .isFalse();
    }

    @Test
    @DisplayName("Existing enabled model is NOT forced off on update - inactive default is INSERT-only (and admin protection stands)")
    void existingModelNotForcedOffOnUpdate() {
        // Divergent + protected to be unambiguous: existing is enabled=true with
        // "enabled" admin-protected; the incoming payload tries enabled=false.
        // The row must STAY enabled - proving (a) the insert force-off did NOT
        // fire on the update branch (a raw setEnabled(false) there would win over
        // protection), and (b) the user-modified-field protection still stands.
        ModelConfigOverrideEntity existing = new ModelConfigOverrideEntity();
        existing.setId(9L);
        existing.setProvider("openai");
        existing.setModelId("gpt-5.4");
        existing.setDisplayName("gpt-5.4");
        existing.setEnabled(true);
        existing.setUserModifiedFields(new String[]{"enabled"});
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.4")).thenReturn(Optional.of(existing));

        Map<String, Object> row = payload("openai", "gpt-5.4");
        row.put("enabled", false); // incoming tries to disable - must be ignored (protected + not an insert)

        merge.merge(List.of(row), MergeOptions.forSync());

        assertThat(existing.getEnabled())
                .as("an already-known, admin-protected model keeps its enabled state across a refresh")
                .isTrue();
        verify(modelRepo).save(existing);
    }

    @Test
    @DisplayName("Update branch is independent of the insert default: an existing DISABLED model can be re-enabled by an unprotected payload")
    void existingModelUpdateBranchUnaffectedByInsertDefault() {
        // Existing disabled row, "enabled" NOT protected, payload enables it.
        // The update branch applies the payload (enabled=true) - the insert-only
        // force-off must not bleed into the update path and keep it false.
        ModelConfigOverrideEntity existing = new ModelConfigOverrideEntity();
        existing.setId(9L);
        existing.setProvider("openai");
        existing.setModelId("gpt-5.4");
        existing.setDisplayName("gpt-5.4");
        existing.setEnabled(false);
        existing.setUserModifiedFields(new String[0]);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.4")).thenReturn(Optional.of(existing));

        Map<String, Object> row = payload("openai", "gpt-5.4");
        row.put("enabled", true);

        merge.merge(List.of(row), MergeOptions.forSync());

        assertThat(existing.getEnabled())
                .as("update branch applies the payload - the insert force-off does not bleed in")
                .isTrue();
    }

    @Test
    @DisplayName("SEED insert keeps an explicit enabled=true (curated models usable out of the box) + source=curated")
    void seedInsertHonorsExplicitEnabledTrue() {
        when(modelRepo.findMaxRanking()).thenReturn(0);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.6")).thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> { ModelConfigOverrideEntity e = inv.getArgument(0); e.setId(1L); return e; });

        Map<String, Object> row = seedRow("openai", "gpt-5.6");
        row.put("enabled", true);

        merge.merge(List.of(row), MergeOptions.forSeed());

        ArgumentCaptor<ModelConfigOverrideEntity> captor = ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo).save(captor.capture());
        assertThat(captor.getValue().getEnabled())
                .as("forSeed keeps enabled=true on insert, unlike bundle/sync which force off")
                .isTrue();
        assertThat(captor.getValue().getSource())
                .as("a seed row with no explicit source is stamped with forSeed()'s 'curated'")
                .isEqualTo("curated");
    }

    @Test
    @DisplayName("SEED insert defaults enabled=true when the payload omits it")
    void seedInsertDefaultsEnabledTrueWhenOmitted() {
        when(modelRepo.findMaxRanking()).thenReturn(0);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.6")).thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> { ModelConfigOverrideEntity e = inv.getArgument(0); e.setId(1L); return e; });

        Map<String, Object> row = seedRow("openai", "gpt-5.6"); // no "enabled" key

        merge.merge(List.of(row), MergeOptions.forSeed());

        ArgumentCaptor<ModelConfigOverrideEntity> captor = ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo).save(captor.capture());
        assertThat(captor.getValue().getEnabled())
                .as("a seed row with no explicit enabled defaults to enabled (usable out of the box)")
                .isTrue();
    }

    @Test
    @DisplayName("SEED insert still respects a deliberate enabled=false in the seed")
    void seedInsertHonorsExplicitEnabledFalse() {
        when(modelRepo.findMaxRanking()).thenReturn(0);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.6")).thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> { ModelConfigOverrideEntity e = inv.getArgument(0); e.setId(1L); return e; });

        Map<String, Object> row = seedRow("openai", "gpt-5.6");
        row.put("enabled", false);

        merge.merge(List.of(row), MergeOptions.forSeed());

        ArgumentCaptor<ModelConfigOverrideEntity> captor = ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo).save(captor.capture());
        assertThat(captor.getValue().getEnabled())
                .as("an explicit enabled=false in the seed is a deliberate off and is respected")
                .isFalse();
    }

    @Test
    @DisplayName("forSeed UPDATE is a PATCH: a minimal seed refreshes the fields it carries but PRESERVES enrichment it omits (no clobber on a version bump)")
    void forSeedMergeUpdatesPresentRow_patchPreservesOmittedEnrichment() {
        // An existing row a bundle/admin enriched with tier='top' + a context
        // window, never manually edited (empty user_modified_fields).
        ModelConfigOverrideEntity existing = new ModelConfigOverrideEntity();
        existing.setId(9L);
        existing.setProvider("openai");
        existing.setModelId("gpt-5.4");
        existing.setDisplayName("gpt-5.4 (old)");
        existing.setTier("top");
        existing.setContextWindow(400_000);
        existing.setEnabled(true);
        existing.setUserModifiedFields(new String[0]);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.4")).thenReturn(Optional.of(existing));

        // A MINIMAL seed row: carries displayName (changed) but NOT tier / contextWindow.
        Map<String, Object> seed = seedRow("openai", "gpt-5.4");
        seed.put("displayName", "gpt-5.4 (new)");
        merge.merge(List.of(seed), MergeOptions.forSeed());

        // PATCH semantics (partialUpdate=true): fields the seed CARRIES are
        // refreshed; fields it OMITS are left untouched, so bundle/feed enrichment
        // survives a seed version bump. (Admin edits are additionally protected via
        // user_modified_fields.) This is what lets the shipped models.json stay a
        // minimal curated payload without erasing enrichment.
        assertThat(existing.getDisplayName())
                .as("a field present in the seed is refreshed")
                .isEqualTo("gpt-5.4 (new)");
        assertThat(existing.getTier())
                .as("tier is omitted by the minimal seed -> PRESERVED (not nulled)")
                .isEqualTo("top");
        assertThat(existing.getContextWindow())
                .as("contextWindow is omitted -> PRESERVED")
                .isEqualTo(400_000);
        verify(modelRepo).save(existing);
    }

    @Test
    @DisplayName("A BUNDLE (partialUpdate=false) still nulls a field it omits - authoritative snapshot, unchanged behaviour")
    void forBundleMergeStillNullsOmittedField() {
        ModelConfigOverrideEntity existing = new ModelConfigOverrideEntity();
        existing.setId(9L);
        existing.setProvider("openai");
        existing.setModelId("gpt-5.4");
        existing.setDisplayName("gpt-5.4");
        existing.setTier("top");
        existing.setEnabled(true);
        existing.setUserModifiedFields(new String[0]);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5.4")).thenReturn(Optional.of(existing));

        merge.merge(List.of(seedRow("openai", "gpt-5.4")), MergeOptions.forBundle(1L));

        assertThat(existing.getTier())
                .as("bundle is authoritative: an omitted field overwrites to null")
                .isNull();
    }

    /** A real seed payload: no 'source'/'mode' (the seed JSON omits them), so forSeed()'s source stands. */
    private static Map<String, Object> seedRow(String provider, String modelId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("modelId", modelId);
        m.put("displayName", modelId);
        return m;
    }

    private static Map<String, Object> payload(String provider, String modelId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("modelId", modelId);
        m.put("displayName", modelId);
        m.put("mode", "chat");
        m.put("source", "litellm");
        return m;
    }
}

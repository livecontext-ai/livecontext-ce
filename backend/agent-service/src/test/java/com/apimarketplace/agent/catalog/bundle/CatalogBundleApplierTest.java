package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.CatalogBundleEntity;
import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.CatalogBundleRepository;
import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.AuthPricingSyncClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Merge-aware apply contract: protects is_custom + user_modified_fields,
 * never hard-deletes (deprecates instead), and is idempotent on the bundle
 * row. This is the single most security- and correctness-sensitive code in
 * PR3 - every merge rule gets a dedicated test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogBundleApplier - merge semantics + idempotency")
class CatalogBundleApplierTest {

    @Mock private ModelConfigOverrideRepository modelRepo;
    @Mock private CatalogBundleRepository bundleRepo;
    @Mock private CatalogBundleSyncStatusRepository syncStatusRepo;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private CatalogBundleApplier applier;
    private CatalogMergeService mergeService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Real merge service with mocked deps - the applier delegates all
        // per-row merge logic to it, so we exercise the real code path.
        // categoryRepo=null - applier tests don't exercise the V156 sidecar
        // write path; the parent table merge contract is what's covered here.
        // V156 coverage lives in ModelCatalogServiceCategoryOverlayTest +
        // CatalogMergeServiceCategoryApplyTest.
        mergeService = new CatalogMergeService(modelRepo, null, authPricingSyncClient, null);
        // CatalogDefaults null = tests use nulls in rate-limit fields;
        // merge's applyRateLimitDefaults skips when defaults bean is null
        // so every existing assertion behaves as before.
        applier = new CatalogBundleApplier(mergeService, bundleRepo, syncStatusRepo, mapper);
    }

    private byte[] payloadBytes(List<Map<String, Object>> models) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 42L);
        root.put("schemaVersion", 1);
        root.put("issuer", "cloud");
        root.put("snapshotAt", "2026-04-21T10:00:00Z");
        root.put("models", models);
        return mapper.writeValueAsBytes(root);
    }

    private Map<String, Object> bundleModel(String provider, String modelId,
                                            String displayName, String price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("modelId", modelId);
        m.put("displayName", displayName);
        if (price != null) m.put("priceInput", price);
        return m;
    }

    private SignedBundle sbV42() {
        return new SignedBundle(42L, 1, "c".repeat(64), "sig", "k1", "cloud",
                1, 100, "ignored-by-applier");
    }

    @Test
    @DisplayName("Fresh apply to empty DB → inserts all models, updates sync status")
    void freshApply() throws Exception {
        byte[] bytes = payloadBytes(List.of(
                bundleModel("openai",    "gpt-5", "GPT-5", "1.25"),
                bundleModel("anthropic", "sonnet", "Sonnet", "3.00")));
        when(modelRepo.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, "https://cloud/x");

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.APPLIED);
        assertThat(r.inserted()).isEqualTo(2);
        assertThat(r.updated()).isZero();
        assertThat(r.deprecated()).isZero();
        ArgumentCaptor<ModelConfigOverrideEntity> saves = ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo, times(2)).save(saves.capture());
        assertThat(saves.getAllValues()).allSatisfy(e -> {
            assertThat(e.getSource()).isEqualTo("bundle");
            assertThat(e.getBundleVersion()).isEqualTo(42L);
            assertThat(e.getLastSyncedAt()).isNotNull();
        });
        verify(bundleRepo).deactivateAll();
        verify(bundleRepo).save(any(CatalogBundleEntity.class));
        verify(syncStatusRepo).save(any(CatalogBundleSyncStatusEntity.class));
    }

    @Test
    @DisplayName("CE (embedded): apply STRIPS openrouter + cohere bundle rows before merge - the signed cloud catalog never lands them in CE")
    void ceApplyFiltersBlockedProviders() throws Exception {
        ReflectionTestUtils.setField(applier, "authMode", "embedded");
        byte[] bytes = payloadBytes(List.of(
                bundleModel("openai",     "gpt-5",                    "GPT-5",      "1.25"),
                bundleModel("openrouter", "anthropic/claude-sonnet-4", "OR Sonnet", "3.00"),
                bundleModel("cohere",     "command-r-plus-08-2024",   "Command R+", "2.50"),
                bundleModel("qwen",       "qwen-max",                 "Qwen Max",   "1.60")));
        when(modelRepo.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, "https://cloud/x");

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.APPLIED);
        // openrouter + cohere dropped before merge; only openai + qwen inserted.
        assertThat(r.inserted()).isEqualTo(2);
        ArgumentCaptor<ModelConfigOverrideEntity> saves = ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo, times(2)).save(saves.capture());
        assertThat(saves.getAllValues()).extracting(ModelConfigOverrideEntity::getProvider)
                .containsExactlyInAnyOrder("openai", "qwen")
                .doesNotContain("openrouter", "cohere");
    }

    @Test
    @DisplayName("Cloud (default auth.mode): apply KEEPS openrouter + cohere - the CE filter never fires")
    void cloudApplyKeepsBlockedProviders() throws Exception {
        // applier built in setUp() has the default empty authMode = cloud.
        byte[] bytes = payloadBytes(List.of(
                bundleModel("openai",     "gpt-5",                    "GPT-5",      "1.25"),
                bundleModel("openrouter", "anthropic/claude-sonnet-4", "OR Sonnet", "3.00"),
                bundleModel("cohere",     "command-r-plus-08-2024",   "Command R+", "2.50")));
        when(modelRepo.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, "https://cloud/x");

        assertThat(r.inserted()).isEqualTo(3);
        verify(modelRepo, times(3)).save(any());
    }

    @Test
    @DisplayName("Re-applying the same ACTIVE bundle is a no-op on models/bundles but still refreshes sync-status")
    void idempotentOnActive() throws Exception {
        CatalogBundleEntity active = new CatalogBundleEntity();
        active.setVersion(42L);
        active.setActive(true);
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.of(active));
        // Prior failure streak on the status row - must reset on recovery.
        CatalogBundleSyncStatusEntity prior = new CatalogBundleSyncStatusEntity();
        prior.setConsecutiveFailures(4);
        prior.setLastFetchStatus("NETWORK_ERROR");
        prior.setLastFetchError("timeout");
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.of(prior));

        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), new byte[0], "url");

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.ALREADY_APPLIED);
        verifyNoInteractions(modelRepo);
        verify(bundleRepo, never()).save(any());
        verify(bundleRepo, never()).deactivateAll();
        // Status row MUST be refreshed - otherwise operators see stale
        // "4 failures" forever after recovery.
        ArgumentCaptor<CatalogBundleSyncStatusEntity> cap =
                ArgumentCaptor.forClass(CatalogBundleSyncStatusEntity.class);
        verify(syncStatusRepo).save(cap.capture());
        CatalogBundleSyncStatusEntity saved = cap.getValue();
        assertThat(saved.getLastAppliedVersion()).isEqualTo(42L);
        assertThat(saved.getLastFetchStatus()).isEqualTo("OK");
        assertThat(saved.getLastFetchError()).isNull();
        assertThat(saved.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("Known-version but inactive bundle gets re-activated (recovery scenario)")
    void reactivatesKnownInactive() throws Exception {
        CatalogBundleEntity inactive = new CatalogBundleEntity();
        inactive.setVersion(42L);
        inactive.setActive(false);
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.of(inactive));
        when(modelRepo.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        byte[] bytes = payloadBytes(List.of(bundleModel("openai", "gpt-5", "GPT-5", null)));
        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, "url");

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.APPLIED);
        ArgumentCaptor<CatalogBundleEntity> cap = ArgumentCaptor.forClass(CatalogBundleEntity.class);
        verify(bundleRepo).deactivateAll();
        verify(bundleRepo).save(cap.capture());
        assertThat(cap.getValue().isActive()).isTrue();
    }

    @Test
    @DisplayName("is_custom=true rows are never touched")
    void skipsCustom() throws Exception {
        ModelConfigOverrideEntity custom = row("openai", "my-local", "LOCAL", new BigDecimal("99"), true,
                new String[0]);
        when(modelRepo.findByProviderAndModelId("openai", "my-local")).thenReturn(Optional.of(custom));
        // A bundle model with the SAME provider/modelId as a custom row
        byte[] bytes = payloadBytes(List.of(bundleModel("openai", "my-local", "CLOUD NAME", "0.01")));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(custom));
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, null);

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.APPLIED);
        assertThat(r.skippedCustom()).isEqualTo(1);
        assertThat(r.updated()).isZero();
        // The entity must NOT be saved with bundle values - assert it wasn't saved via upsert.
        // (It could be saved from the deprecate loop if bundleKeys didn't contain it, but the
        //  bundle DOES contain it, so no save at all.)
        verify(modelRepo, never()).save(custom);
        // Custom display name preserved on the entity in memory
        assertThat(custom.getDisplayName()).isEqualTo("LOCAL");
        assertThat(custom.getPriceInput()).isEqualByComparingTo(new BigDecimal("99"));
    }

    @Test
    @DisplayName("user_modified_fields entries are preserved; other fields overwritten")
    void respectsUserModifiedFields() throws Exception {
        ModelConfigOverrideEntity existing = row("openai", "gpt-5", "LOCAL EDIT", new BigDecimal("50"), false,
                new String[]{"displayName", "priceInput"});
        existing.setTier("bronze"); // non-protected - should be overwritten
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.of(existing));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(existing));
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        Map<String, Object> m = bundleModel("openai", "gpt-5", "CLOUD", "1.25");
        m.put("tier", "gold");
        byte[] bytes = payloadBytes(List.of(m));

        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, null);

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.APPLIED);
        assertThat(r.updated()).isEqualTo(1);
        // Protected fields - unchanged.
        assertThat(existing.getDisplayName()).isEqualTo("LOCAL EDIT");
        assertThat(existing.getPriceInput()).isEqualByComparingTo(new BigDecimal("50"));
        // Unprotected field - overwritten.
        assertThat(existing.getTier()).isEqualTo("gold");
        // Meta stamps always written.
        assertThat(existing.getBundleVersion()).isEqualTo(42L);
        assertThat(existing.getLastSyncedAt()).isNotNull();
    }

    @Test
    @DisplayName("Non-custom row missing from bundle → deprecated_at set, NOT deleted")
    void deprecatesMissing() throws Exception {
        ModelConfigOverrideEntity stale = row("openai", "gpt-4", "GPT-4", null, false, new String[0]);
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(stale));
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        // Bundle contains a different model - gpt-4 is absent.
        byte[] bytes = payloadBytes(List.of(bundleModel("openai", "gpt-5", "GPT-5", "1.25")));
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.empty());

        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, null);

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.APPLIED);
        assertThat(r.deprecated()).isEqualTo(1);
        assertThat(stale.getDeprecatedAt()).isNotNull();
        verify(modelRepo, never()).deleteByProviderAndModelId(any(), any());
    }

    @Test
    @DisplayName("Already-deprecated row missing from bundle → left alone (no second deprecate)")
    void noDoubleDeprecate() throws Exception {
        ModelConfigOverrideEntity alreadyDep = row("openai", "gpt-3", "GPT-3", null, false, new String[0]);
        alreadyDep.setDeprecatedAt(java.time.Instant.parse("2025-01-01T00:00:00Z"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(alreadyDep));
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());
        byte[] bytes = payloadBytes(List.of(bundleModel("openai", "gpt-5", "GPT-5", null)));
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.empty());

        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, null);

        assertThat(r.deprecated()).isZero();
        assertThat(alreadyDep.getDeprecatedAt())
                .isEqualTo(java.time.Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("A previously-deprecated model that reappears in the bundle is un-deprecated")
    void undeprecatesOnReturn() throws Exception {
        ModelConfigOverrideEntity zombie = row("openai", "gpt-5", "OLD", null, false, new String[0]);
        zombie.setDeprecatedAt(java.time.Instant.parse("2025-01-01T00:00:00Z"));
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.of(zombie));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(zombie));
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        byte[] bytes = payloadBytes(List.of(bundleModel("openai", "gpt-5", "GPT-5", "1.25")));
        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, null);

        assertThat(r.updated()).isEqualTo(1);
        assertThat(zombie.getDeprecatedAt()).isNull();
        assertThat(zombie.getDisplayName()).isEqualTo("GPT-5");
    }

    @Test
    @DisplayName("Malformed payload → APPLY_FAILED, no mutations")
    void malformedPayload() {
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());

        CatalogBundleApplier.ApplyResult r =
                applier.apply(sbV42(), "not json".getBytes(StandardCharsets.UTF_8), null);

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).contains("parse failed");
        verifyNoInteractions(modelRepo);
        verify(bundleRepo, never()).save(any());
        verify(bundleRepo, never()).deactivateAll();
        verifyNoInteractions(syncStatusRepo);
    }

    @Test
    @DisplayName("Payload missing 'models' array → APPLY_FAILED")
    void missingModelsArray() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 42L);
        root.put("issuer", "cloud");
        // deliberately no "models" key
        byte[] bytes = mapper.writeValueAsBytes(root);
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());

        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, null);

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).contains("models");
    }

    @Test
    @DisplayName("Sync status row gets updated: version + OK + consecutiveFailures reset")
    void syncStatusUpdated() throws Exception {
        CatalogBundleSyncStatusEntity prior = new CatalogBundleSyncStatusEntity();
        prior.setConsecutiveFailures(3);
        prior.setLastFetchError("previous error");
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.of(prior));
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(modelRepo.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());

        byte[] bytes = payloadBytes(List.of(bundleModel("openai", "gpt-5", "GPT-5", null)));
        applier.apply(sbV42(), bytes, "https://cloud/x");

        ArgumentCaptor<CatalogBundleSyncStatusEntity> cap =
                ArgumentCaptor.forClass(CatalogBundleSyncStatusEntity.class);
        verify(syncStatusRepo).save(cap.capture());
        CatalogBundleSyncStatusEntity saved = cap.getValue();
        assertThat(saved.getLastAppliedVersion()).isEqualTo(42L);
        assertThat(saved.getLastFetchStatus()).isEqualTo("OK");
        assertThat(saved.getLastFetchError()).isNull();
        assertThat(saved.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("BigDecimal prices round-trip through JSON-as-string without precision loss")
    void bigDecimalRoundTrip() throws Exception {
        Map<String, Object> m = bundleModel("openai", "gpt-5", "GPT-5", "1.2500");
        byte[] bytes = payloadBytes(List.of(m));
        when(modelRepo.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        applier.apply(sbV42(), bytes, null);

        ArgumentCaptor<ModelConfigOverrideEntity> cap = ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo).save(cap.capture());
        assertThat(cap.getValue().getPriceInput()).isEqualByComparingTo(new BigDecimal("1.2500"));
        // Plain-string encoding preserves scale 4 from the canonical payload.
        assertThat(cap.getValue().getPriceInput().scale()).isEqualTo(4);
    }

    @Test
    @DisplayName("Malformed modalities (encoded as List not Map) → apply succeeds, modalities stays null, other fields applied")
    void malformedModalitiesDoesNotBreakApply() throws Exception {
        // Regression: the unchecked cast (Map<String,Object>) m.get("modalities")
        // would ClassCastException mid-apply-loop if a bad publisher ever
        // encoded modalities as a List. The row must still get all other
        // fields - we must not lose a whole model over one bad field.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", "openai");
        m.put("modelId", "gpt-5");
        m.put("displayName", "GPT-5");
        m.put("modalities", List.of("text", "image")); // malformed - should be Map
        byte[] bytes = payloadBytes(List.of(m));
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.empty());
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, null);

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.APPLIED);
        assertThat(r.inserted()).isEqualTo(1);
        ArgumentCaptor<ModelConfigOverrideEntity> cap =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo).save(cap.capture());
        ModelConfigOverrideEntity saved = cap.getValue();
        assertThat(saved.getDisplayName()).isEqualTo("GPT-5");
        // Malformed modalities was skipped - row lands with null rather than
        // propagating a CCE mid-loop.
        assertThat(saved.getModalities()).isNull();
    }

    @Test
    @DisplayName("Bundle apply → authPricingSyncClient.sync called once per inserted priced row (after-commit behavior)")
    void bundleApplyMirrorsPricingToAuth() throws Exception {
        // Two priced rows + one with no pricing - only the priced ones must
        // be forwarded to auth.model_pricing. This is the P0 regression:
        // previously the applier wrote pricing locally but never told
        // auth-service, so billing kept using the V80 seed or 1.0/4.0 fallback.
        Map<String, Object> a = bundleModel("openai",    "gpt-5",  "GPT-5",  "1.25");
        a.put("priceOutput", "10.00");
        Map<String, Object> b = bundleModel("anthropic", "sonnet", "Sonnet", "3.00");
        b.put("priceOutput", "15.00");
        Map<String, Object> c = bundleModel("local", "no-price", "NoPrice", null); // skip

        byte[] bytes = payloadBytes(List.of(a, b, c));
        when(modelRepo.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        // No TX active in unit test → applier falls back to sync-flush path,
        // which exercises exactly the same branch behavior as afterCommit.
        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, null);

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.APPLIED);
        assertThat(r.inserted()).isEqualTo(3);

        verify(authPricingSyncClient).sync(
                eq("openai"), eq("gpt-5"),
                eq(new BigDecimal("1.25")), eq(new BigDecimal("10.00")),
                eq("byok"));
        verify(authPricingSyncClient).sync(
                eq("anthropic"), eq("sonnet"),
                eq(new BigDecimal("3.00")), eq(new BigDecimal("15.00")),
                eq("byok"));
        // No pricing → no sync call.
        verify(authPricingSyncClient, never()).sync(
                eq("local"), eq("no-price"), any(), any(), any());
    }

    @Test
    @DisplayName("Bundle apply of a bridge row → auth sync carries providerKind='bridge'")
    void bundleApplyOfBridgeRowPropagatesProviderKind() throws Exception {
        // Regression guard for the cross-schema drift fixed alongside admin-UI
        // bridge creation: a new bridge model shipped via cloud→CE bundle must
        // land in auth.model_pricing with provider_kind='bridge', matching
        // agent.model_config_overrides.provider_kind. Pre-fix, the 4-arg
        // AuthPricingSyncClient.sync overload was used here and the auth-side
        // row defaulted to 'byok' (ModelPricing entity default), silently
        // diverging from the agent mirror.
        Map<String, Object> bridge = bundleModel("claude-code", "claude-opus-4-10",
                "Claude Opus 4.10", "5.00");
        bridge.put("priceOutput", "25.00");

        byte[] bytes = payloadBytes(List.of(bridge));
        when(modelRepo.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        CatalogBundleApplier.ApplyResult r = applier.apply(sbV42(), bytes, null);

        assertThat(r.status()).isEqualTo(CatalogBundleApplier.Status.APPLIED);
        assertThat(r.inserted()).isEqualTo(1);

        verify(authPricingSyncClient).sync(
                eq("claude-code"), eq("claude-opus-4-10"),
                eq(new BigDecimal("5.00")), eq(new BigDecimal("25.00")),
                eq("bridge"));
    }

    @Test
    @DisplayName("Bundle apply with no pricing changes on existing rows → no auth sync")
    void noSyncWhenPricingUnchanged() throws Exception {
        // Existing row with identical pricing + non-protected fields that ALSO
        // happen to match the bundle → no field actually changes. The pricing
        // mirror must NOT fire for every bundle apply, only when price drifts.
        ModelConfigOverrideEntity existing = new ModelConfigOverrideEntity();
        existing.setProvider("openai");
        existing.setModelId("gpt-5");
        existing.setDisplayName("GPT-5");
        existing.setPriceInput(new BigDecimal("1.25"));
        existing.setPriceOutput(new BigDecimal("10.00"));
        existing.setUserModifiedFields(new String[0]);
        when(modelRepo.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.of(existing));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(existing));
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.empty());
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        Map<String, Object> m = bundleModel("openai", "gpt-5", "GPT-5", "1.25");
        m.put("priceOutput", "10.00");
        byte[] bytes = payloadBytes(List.of(m));

        applier.apply(sbV42(), bytes, null);

        verify(authPricingSyncClient, never()).sync(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("ALREADY_APPLIED path → no sync (idempotent apply must not mass-re-POST)")
    void alreadyAppliedSkipsSync() {
        CatalogBundleEntity active = new CatalogBundleEntity();
        active.setVersion(42L);
        active.setActive(true);
        when(bundleRepo.findByVersion(42L)).thenReturn(Optional.of(active));
        when(syncStatusRepo.findById((short) 1)).thenReturn(Optional.empty());

        applier.apply(sbV42(), new byte[0], "url");

        verifyNoInteractions(authPricingSyncClient);
    }

    private ModelConfigOverrideEntity row(String provider, String modelId, String displayName,
                                          BigDecimal priceInput, boolean custom,
                                          String[] userModifiedFields) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setDisplayName(displayName);
        e.setPriceInput(priceInput);
        e.setCustom(custom);
        e.setUserModifiedFields(userModifiedFields);
        return e;
    }
}

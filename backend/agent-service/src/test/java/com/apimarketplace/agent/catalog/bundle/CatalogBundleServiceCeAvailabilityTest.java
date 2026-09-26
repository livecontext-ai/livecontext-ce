package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.CatalogBundleEntity;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.CatalogBundleRepository;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The CE bundle and seed carry only what a CE install may use (V533), and the auto-rebuild
 * staleness check reads exactly the rows the build signs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogBundleService - ships only models available to CE")
class CatalogBundleServiceCeAvailabilityTest {

    @Mock private CatalogBundleRepository bundleRepo;
    @Mock private ModelConfigOverrideRepository modelRepo;
    @Mock private ModelCategorySettingsRepository categoryRepo;

    private CatalogBundleService service;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        CatalogBundleSigner signer = new CatalogBundleSigner(
                Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()),
                "test-key", "test-cloud");
        service = new CatalogBundleService(bundleRepo, modelRepo, categoryRepo, signer);
        lenient().when(categoryRepo.findAll()).thenReturn(List.of());
        lenient().when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        lenient().when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ModelConfigOverrideEntity row(String provider, String modelId) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setDisplayName(modelId);
        e.setPriceInput(new BigDecimal("1.00"));
        e.setPriceOutput(new BigDecimal("4.00"));
        return e;
    }

    private static ModelConfigOverrideEntity disabled(String provider, String modelId) {
        ModelConfigOverrideEntity e = row(provider, modelId);
        e.setEnabled(false);
        return e;
    }

    @SuppressWarnings("unchecked")
    private static List<String> shippedIds(CatalogBundleEntity bundle) throws Exception {
        Map<String, Object> payload = new ObjectMapper().readValue(
                bundle.getPayload().getBytes(StandardCharsets.UTF_8), Map.class);
        return ((List<Map<String, Object>>) payload.get("models")).stream()
                .map(m -> m.get("provider") + "/" + m.get("modelId"))
                .toList();
    }

    @Test
    @DisplayName("the bundle leaves out disabled, retired and deprecated rows")
    void bundleShipsOnlyModelsAvailableToCe() throws Exception {
        ModelConfigOverrideEntity live = row("openai", "gpt-6-sol");
        ModelConfigOverrideEntity nullEnabled = row("anthropic", "claude-sonnet-5"); // NULL reads as on
        ModelConfigOverrideEntity off = disabled("google", "gemini-3.1-flash-lite-preview");
        ModelConfigOverrideEntity retired = row("anthropic", "claude-opus-4-8");
        retired.setRetiredAt(Instant.now());
        ModelConfigOverrideEntity deprecated = row("xai", "grok-3-beta");
        deprecated.setDeprecatedAt(Instant.now());
        live.setEnabled(true);
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(live, nullEnabled, off, retired, deprecated));

        CatalogBundleEntity bundle = service.buildBundle();

        // Regression: the bundle used to carry every row (410 in prod for ~30 live models), so a
        // linked CE listed the dead ones and the relay ran them.
        assertThat(shippedIds(bundle)).containsExactlyInAnyOrder("openai/gpt-6-sol", "anthropic/claude-sonnet-5");
        assertThat(bundle.getModelCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("bundle_enabled wins over the cloud's own flag, both ways")
    void bundleEnabledOverrideDecides() throws Exception {
        ModelConfigOverrideEntity cloudOffShippedOn = disabled("mistral", "devstral-2");
        cloudOffShippedOn.setBundleEnabled(true);
        ModelConfigOverrideEntity cloudOnShippedOff = row("openai", "gpt-image-2-high");
        cloudOnShippedOff.setEnabled(true);
        cloudOnShippedOff.setBundleEnabled(false);
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(cloudOffShippedOn, cloudOnShippedOff));

        assertThat(shippedIds(service.buildBundle())).containsExactly("mistral/devstral-2");
    }

    @Test
    @DisplayName("a catalog with nothing available to CE is refused, not published empty")
    void emptyAfterFilterIsRefused() {
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(disabled("openai", "gpt-4o")));

        assertThatThrownBy(() -> service.buildBundle())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No model is available to CE");
    }

    @Test
    @DisplayName("right after a build the active bundle is NOT stale, even with rows the build left out")
    void freshBundleIsNotStale() {
        // Regression: the staleness check checksummed the WHOLE table while the build filtered
        // it (CE-blocked providers already), so every 5-minute tick saw a difference and prod
        // republished an identical bundle forever (21 bundles in 50 minutes on 2026-09-25).
        List<ModelConfigOverrideEntity> table = List.of(
                row("openai", "gpt-6-sol"),
                row("openrouter", "anthropic/claude-sonnet-4"),
                disabled("google", "gemini-2.0-flash"));
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(table);
        CatalogBundleEntity built = service.buildBundle();
        built.setActive(true);
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(built));

        assertThat(service.isActiveBundleStale()).isFalse();
    }

    @Test
    @DisplayName("retiring a shipped model makes the active bundle stale, so the next tick republishes")
    void retiringAShippedModelMakesItStale() {
        ModelConfigOverrideEntity keep = row("openai", "gpt-6-sol");
        ModelConfigOverrideEntity toRetire = row("openai", "gpt-5.4");
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(keep, toRetire));
        CatalogBundleEntity built = service.buildBundle();
        built.setActive(true);
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(built));

        toRetire.setRetiredAt(Instant.now());

        assertThat(service.isActiveBundleStale()).isTrue();
    }

    @Test
    @DisplayName("the seed export applies the same rule: a disabled or retired model is not a CE baseline")
    @SuppressWarnings("unchecked")
    void seedExportShipsOnlyModelsAvailableToCe() {
        ModelConfigOverrideEntity retired = row("anthropic", "claude-opus-4-8");
        retired.setRetiredAt(Instant.now());
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(
                row("openai", "gpt-6-sol"), disabled("openai", "gpt-4o"), retired));

        Map<String, Object> seed = service.buildSeedExport(3000L);

        assertThat(((List<Map<String, Object>>) seed.get("models")))
                .extracting(m -> m.get("modelId"))
                .containsExactly("gpt-6-sol");
    }
}

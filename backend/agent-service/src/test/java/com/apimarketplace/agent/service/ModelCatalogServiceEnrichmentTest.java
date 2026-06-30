package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code ModelCatalogService.applyEnrichmentFields} surfaces the
 * V125-enriched columns (capability flags, context window, batch / cache
 * pricing, deprecation, mode, modalities) onto the catalog model map. The user-
 * facing model picker reads these fields from the {@code /api/v3/chat/models}
 * payload to render capability icons, context badge, deprecation banner, and
 * the detail popover (rate limits + batch / cache prices). If any field stops
 * being copied, the picker silently falls back to "feature absent" and the
 * user loses the ability to differentiate models.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - enrichment field surfacing")
class ModelCatalogServiceEnrichmentTest {

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

    /**
     * Reflective accessor - applyEnrichmentFields is package-private but treated
     * as an internal helper. Going through reflection avoids relaxing the
     * accessibility for a single test, which would make it look like a public
     * API on the service.
     */
    private void invokeEnrichment(Map<String, Object> model, ModelConfigOverrideEntity override) throws Exception {
        Method m = ModelCatalogService.class.getDeclaredMethod(
                "applyEnrichmentFields", Map.class, ModelConfigOverrideEntity.class);
        m.setAccessible(true);
        m.invoke(service, model, override);
    }

    private ModelConfigOverrideEntity baseOverride() {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider("anthropic");
        e.setModelId("claude-opus-4-7");
        return e;
    }

    @Test
    @DisplayName("Surfaces every capability flag and limit-related column when present")
    void surfacesAllEnrichmentFieldsWhenSet() throws Exception {
        ModelConfigOverrideEntity override = baseOverride();
        override.setContextWindow(200_000);
        override.setMaxOutputTokens(8_192);
        override.setSupportsTools(true);
        override.setSupportsVision(true);
        override.setSupportsPromptCaching(true);
        override.setSupportsReasoning(true);
        override.setSupportsComputerUse(false);
        override.setSupportsResponseSchema(true);
        override.setSupportsWebSearch(true);
        override.setMode("chat");
        override.setDefaultReasoningEffort("high");
        override.setPriceInputBatch(new BigDecimal("3.50"));
        override.setPriceOutputBatch(new BigDecimal("18.00"));
        override.setPriceCacheRead(new BigDecimal("0.30"));
        override.setPriceCacheWrite(new BigDecimal("3.00"));
        override.setModalities(Map.of("vision", true, "audio", false));

        Map<String, Object> model = new HashMap<>();
        invokeEnrichment(model, override);

        assertThat(model)
                .containsEntry("contextWindow", 200_000)
                .containsEntry("maxOutputTokens", 8_192)
                .containsEntry("supportsTools", true)
                .containsEntry("supportsVision", true)
                .containsEntry("supportsPromptCaching", true)
                .containsEntry("supportsReasoning", true)
                .containsEntry("supportsComputerUse", false)
                .containsEntry("supportsResponseSchema", true)
                .containsEntry("supportsWebSearch", true)
                .containsEntry("defaultReasoningEffort", "high")
                .containsEntry("mode", "chat")
                // BigDecimal → double so JSON serialisation stays a number, not a string
                .containsEntry("priceInputBatch", 3.50d)
                .containsEntry("priceOutputBatch", 18.00d)
                .containsEntry("priceCacheRead", 0.30d)
                .containsEntry("priceCacheWrite", 3.00d);
        assertThat(model.get("modalities")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("Skips null columns so YAML defaults survive (no overwrite-with-null)")
    void leavesYamlDefaultsAloneWhenColumnIsNull() throws Exception {
        // Override with NO enrichment columns set - everything still null.
        ModelConfigOverrideEntity override = baseOverride();

        // Map pre-populated as if seeded from YAML - none of these should be
        // wiped by the enrichment pass. Mirrors the legacy null-skip semantics
        // already enforced by applyOverride / applyRateLimitFields.
        Map<String, Object> model = new HashMap<>();
        model.put("contextWindow", 128_000);
        model.put("supportsTools", true);
        model.put("priceCacheRead", 0.10d);

        invokeEnrichment(model, override);

        assertThat(model)
                .containsEntry("contextWindow", 128_000)
                .containsEntry("supportsTools", true)
                .containsEntry("priceCacheRead", 0.10d);
    }

    @Test
    @DisplayName("Surfaces deprecatedAt as ISO-8601 instant + deprecationDate / releaseDate as ISO date")
    void surfacesDeprecationAndReleaseDates() throws Exception {
        ModelConfigOverrideEntity override = baseOverride();
        Instant deprecatedAt = Instant.parse("2026-09-01T00:00:00Z");
        override.setDeprecatedAt(deprecatedAt);
        override.setDeprecationDate(LocalDate.parse("2026-09-01"));
        override.setReleaseDate(LocalDate.parse("2025-04-15"));

        Map<String, Object> model = new HashMap<>();
        invokeEnrichment(model, override);

        // Strings, not Instants/LocalDates - Jackson would otherwise emit them
        // as objects on some configs and break the picker's date parsing.
        assertThat(model)
                .containsEntry("deprecatedAt", deprecatedAt.toString())
                .containsEntry("deprecationDate", "2026-09-01")
                .containsEntry("releaseDate", "2025-04-15");
    }

    @Test
    @DisplayName("Surfaces providerKind on the model row so the user picker can render the bridge / BYOK badge")
    void surfacesProviderKindOnModelRow() throws Exception {
        // The legacy markBridgeProviders() loop only tagged the provider; the
        // typed AIModel surface on the frontend exposes providerKind on each
        // option, so the user picker renders the badge from the model row.
        // Without this, the badge is dead code in the chat header / workflow
        // inspector and the bridge / BYOK distinction is invisible to users.
        ModelConfigOverrideEntity bridgeOverride = baseOverride();
        bridgeOverride.setProvider("claude-code");
        bridgeOverride.setProviderKind("bridge");

        Map<String, Object> bridgeModel = new HashMap<>();
        invokeEnrichment(bridgeModel, bridgeOverride);

        assertThat(bridgeModel).containsEntry("providerKind", "bridge");

        ModelConfigOverrideEntity byokOverride = baseOverride();
        byokOverride.setProviderKind("byok");

        Map<String, Object> byokModel = new HashMap<>();
        invokeEnrichment(byokModel, byokOverride);

        assertThat(byokModel).containsEntry("providerKind", "byok");
    }

    @Test
    @DisplayName("buildModelInfo (custom rows) carries the same enrichment fields as YAML-merged rows")
    void buildModelInfoCarriesEnrichmentFields() throws Exception {
        // Custom rows (is_custom=true and sync-fed rows whose YAML parent doesn't
        // exist) take a separate code path via buildModelInfo. The user picker
        // must see capability icons / context badge on these too - otherwise
        // a brand-new admin-added model renders blank in the dropdown.
        ModelConfigOverrideEntity custom = baseOverride();
        custom.setDisplayName("Custom Model");
        custom.setTier("high");
        custom.setSupportsTools(true);
        custom.setSupportsVision(true);
        custom.setContextWindow(500_000);
        custom.setMaxOutputTokens(16_000);
        custom.setProviderKind("byok");
        custom.setCustom(true);

        Method buildModelInfo = ModelCatalogService.class.getDeclaredMethod(
                "buildModelInfo", ModelConfigOverrideEntity.class);
        buildModelInfo.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> built = (Map<String, Object>) buildModelInfo.invoke(service, custom);

        assertThat(built)
                .containsEntry("id", "claude-opus-4-7")
                .containsEntry("name", "Custom Model")
                .containsEntry("supportsTools", true)
                .containsEntry("supportsVision", true)
                .containsEntry("contextWindow", 500_000)
                .containsEntry("maxOutputTokens", 16_000)
                .containsEntry("providerKind", "byok");
    }

    @Test
    @DisplayName("markBridgeProviders propagates providerKind=bridge to nested model rows so YAML-only bridge models surface the badge")
    void markBridgeProvidersPropagatesToModels() {
        // Rows declared in application.yml under a bridge provider (claude-code,
        // codex, …) often have NO override row in DB - apply* helpers never run
        // on them, so providerKind has to be propagated by the bridge tagging
        // pass. Without this, the user picker would show "Codex / gpt-5.4"
        // with no bridge badge and the user couldn't tell their pick is
        // CLI-routed.
        Map<String, Object> bridgeModel = new HashMap<>();
        bridgeModel.put("id", "gpt-5.4");
        Map<String, Object> overrideModel = new HashMap<>();
        overrideModel.put("id", "claude-opus-4-7");
        overrideModel.put("providerKind", "byok");

        Map<String, Object> codex = new HashMap<>();
        codex.put("name", "codex");
        codex.put("models", new ArrayList<Map<String, Object>>(List.of(bridgeModel)));

        Map<String, Object> custom = new HashMap<>();
        custom.put("name", "anthropic");
        custom.put("models", new ArrayList<Map<String, Object>>(List.of(overrideModel)));

        ModelCatalogService.markBridgeProviders(List.of(codex, custom));

        assertThat(bridgeModel).containsEntry("providerKind", "bridge");
        // Provider that already carries a kind (set by the override path) is
        // not overwritten by the bridge sweep - the merge stays predictable.
        assertThat(overrideModel).containsEntry("providerKind", "byok");
    }

    @Test
    @DisplayName("REGRESSION: capability flags survive applyOverride end-to-end (admin price edit doesn't strip them)")
    void capabilityFlagsSurviveFullApplyOverride() throws Exception {
        // Reproduces the bug where adding an admin price override to a YAML-
        // seeded model would cause the picker to lose the capability flags
        // because applyOverride didn't call the enrichment helper before the
        // price merge - the LLM-fed catalog stayed enriched but the user-
        // facing picker silently dropped vision/tools/etc.
        ModelConfigOverrideEntity override = baseOverride();
        override.setPriceInput(new BigDecimal("5.00"));
        override.setPriceOutput(new BigDecimal("25.00"));
        override.setSupportsVision(true);
        override.setSupportsReasoning(true);
        override.setContextWindow(256_000);

        Map<String, Object> model = new HashMap<>();
        Method apply = ModelCatalogService.class.getDeclaredMethod(
                "applyOverride", Map.class, ModelConfigOverrideEntity.class);
        apply.setAccessible(true);
        apply.invoke(service, model, override);

        // Pricing merged AND enrichment fields present on the same map - the
        // contract the picker depends on.
        assertThat(model.get("pricing")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> pricing = (Map<String, Object>) model.get("pricing");
        assertThat(pricing).containsEntry("input", 5.00d);
        assertThat(model)
                .containsEntry("supportsVision", true)
                .containsEntry("supportsReasoning", true)
                .containsEntry("contextWindow", 256_000);
    }
}

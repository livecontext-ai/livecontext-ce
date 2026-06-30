package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.ModelCatalogService.AvailableModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doReturn;

/**
 * Focused tests for the flat-catalog helpers added for the LLM agent-creation
 * validation path: {@link ModelCatalogService#listAvailableModels()} and
 * {@link ModelCatalogService#isModelAvailable(String, String)}.
 *
 * <p>We use a partial spy to stub the heavy {@code getModelsWithOverrides()}
 * pipeline (Bridge filter, DB overrides, LLMProviderFactory) and exercise only
 * the flattening / lookup logic on top of canned provider lists.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - available models catalog")
class ModelCatalogServiceCatalogTest {

    @Mock private ModelConfigOverrideRepository repository;
    @Mock private ModelCategorySettingsRepository categoryRepository;
    @Mock private LLMProviderFactory llmProviderFactory;
    @Mock private LlmCredentialRepository credentialRepository;
    @Mock private CachedModelRateLimitProvider cachedRateLimitProvider;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        // Partial spy so we can stub the heavy getModelsWithOverrides() pipeline
        // (DB overrides + bridge filter) and test only the catalog flattening
        // helpers on top of canned provider/model maps.
        service = Mockito.spy(new ModelCatalogService(
                repository, categoryRepository, llmProviderFactory, credentialRepository,
                cachedRateLimitProvider, "", authPricingSyncClient));
    }

    private Map<String, Object> provider(String name, List<Map<String, Object>> models) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("models", models);
        return p;
    }

    private Map<String, Object> model(String id, String tier) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (tier != null) m.put("tier", tier);
        return m;
    }

    @Test
    @DisplayName("listAvailableModels flattens providers in order and preserves tier")
    void listAvailableModelsFlattens() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", List.of(
                provider("anthropic", List.of(
                        model("claude-opus-4-6", "top"),
                        model("claude-sonnet-4-6", "top"))),
                provider("openai", List.of(
                        model("gpt-5", "top"),
                        model("gpt-4o-mini", "budget")))
        ));
        doReturn(base).when(service).getModelsForCategory(null, null);

        List<AvailableModel> out = service.listAvailableModels();

        assertThat(out).hasSize(4);
        // Order preservation - matters because the admin displayOrder sort is
        // the source of truth for which provider/model is "recommended first".
        assertThat(out).extracting(AvailableModel::provider, AvailableModel::modelId, AvailableModel::tier)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("anthropic", "claude-opus-4-6", "top"),
                        org.assertj.core.groups.Tuple.tuple("anthropic", "claude-sonnet-4-6", "top"),
                        org.assertj.core.groups.Tuple.tuple("openai", "gpt-5", "top"),
                        org.assertj.core.groups.Tuple.tuple("openai", "gpt-4o-mini", "budget")
                );
    }

    @Test
    @DisplayName("listAvailableModels handles missing providers key")
    void listAvailableModelsMissingProviders() {
        doReturn(Map.of()).when(service).getModelsForCategory(null, null);
        assertThat(service.listAvailableModels()).isEmpty();
    }

    @Test
    @DisplayName("listAvailableModels skips entries without an id")
    void listAvailableModelsSkipsMalformed() {
        Map<String, Object> brokenModel = new LinkedHashMap<>();
        brokenModel.put("name", "no-id");
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", List.of(
                provider("openai", List.of(brokenModel, model("gpt-5", "top")))));
        doReturn(base).when(service).getModelsForCategory(null, null);

        List<AvailableModel> out = service.listAvailableModels();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).modelId()).isEqualTo("gpt-5");
    }

    @Test
    @DisplayName("listAvailableModels defaults tier to 'mid' when absent")
    void listAvailableModelsDefaultsTier() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "some-model");
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", List.of(provider("openai", List.of(m))));
        doReturn(base).when(service).getModelsForCategory(null, null);

        List<AvailableModel> out = service.listAvailableModels();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).tier()).isEqualTo("mid");
    }

    @Test
    @DisplayName("isModelAvailable true for an exact match")
    void isModelAvailableExactMatch() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", List.of(
                provider("openai", List.of(model("gpt-5", "top")))));
        doReturn(base).when(service).getModelsForCategory(null, null);

        assertThat(service.isModelAvailable("openai", "gpt-5")).isTrue();
    }

    @Test
    @DisplayName("isModelAvailable false when provider is wrong")
    void isModelAvailableWrongProvider() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", List.of(
                provider("openai", List.of(model("gpt-5", "top")))));
        doReturn(base).when(service).getModelsForCategory(null, null);

        // Same model_name but paired with the wrong provider - must fail.
        // This is the LLM-mistake case (training-data noise).
        assertThat(service.isModelAvailable("anthropic", "gpt-5")).isFalse();
    }

    @Test
    @DisplayName("isModelAvailable false when model is unknown")
    void isModelAvailableUnknownModel() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", List.of(
                provider("openai", List.of(model("gpt-5", "top")))));
        doReturn(base).when(service).getModelsForCategory(null, null);

        assertThat(service.isModelAvailable("openai", "gpt-99")).isFalse();
    }

    @Test
    @DisplayName("isModelAvailable rejects null provider")
    void isModelAvailableNullProvider() {
        assertThat(service.isModelAvailable(null, "gpt-5")).isFalse();
    }

    @Test
    @DisplayName("isModelAvailable rejects null modelId")
    void isModelAvailableNullModel() {
        assertThat(service.isModelAvailable("openai", null)).isFalse();
    }

    @Test
    @DisplayName("isModelAvailable false on empty catalog")
    void isModelAvailableEmptyCatalog() {
        doReturn(Map.of("providers", List.of())).when(service).getModelsForCategory(null, null);
        assertThat(service.isModelAvailable("openai", "gpt-5")).isFalse();
    }

    /**
     * Regression: upstream YAML/Bridge parsers can seed the "pricing" value with an
     * immutable {@code Map.of(...)}. Calling {@code put()} on it previously threw
     * {@link UnsupportedOperationException} and 500'd /api/internal/agent/models
     * for any admin who had a price override persisted. The fix takes a defensive
     * copy before mutation.
     */
    @Test
    @DisplayName("Regression: applyOverride copies immutable pricing map before put()")
    void applyOverrideCopiesImmutablePricingBeforePut() throws Exception {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", "claude-opus-4-6");
        // Singleton / immutable map, exactly what Map.of(...) returns from YAML seed.
        model.put("pricing", Map.of("input", 5.0, "output", 25.0));

        ModelConfigOverrideEntity override = new ModelConfigOverrideEntity();
        override.setPriceInput(BigDecimal.valueOf(6.5));
        override.setPriceOutput(BigDecimal.valueOf(30.0));

        Method apply = ModelCatalogService.class.getDeclaredMethod(
                "applyOverride", Map.class, ModelConfigOverrideEntity.class);
        apply.setAccessible(true);

        assertThatCode(() -> apply.invoke(service, model, override))
                .doesNotThrowAnyException();

        @SuppressWarnings("unchecked")
        Map<String, Object> pricing = (Map<String, Object>) model.get("pricing");
        assertThat(pricing).containsEntry("input", 6.5).containsEntry("output", 30.0);
    }

    @Test
    @DisplayName("applyOverride seeds pricing when base model had none")
    void applyOverrideSeedsPricingWhenMissing() throws Exception {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", "claude-opus-4-6");

        ModelConfigOverrideEntity override = new ModelConfigOverrideEntity();
        override.setPriceInput(BigDecimal.valueOf(1.25));

        Method apply = ModelCatalogService.class.getDeclaredMethod(
                "applyOverride", Map.class, ModelConfigOverrideEntity.class);
        apply.setAccessible(true);

        apply.invoke(service, model, override);

        @SuppressWarnings("unchecked")
        Map<String, Object> pricing = (Map<String, Object>) model.get("pricing");
        assertThat(pricing).containsEntry("input", 1.25).doesNotContainKey("output");
    }

    // ── recalculateDefaults - global flat ranking respect (frontend drag-and-drop) ──
    // The frontend `/settings/ai-providers` ModelManagementPanel persists a
    // FLAT global ranking via bulkUpdateRankings (rank=i+1 across all
    // (provider, model) pairs). The backend default must therefore pick the
    // model with the lowest displayOrder GLOBALLY - not the first model of
    // the lowest-displayOrder provider, which would ignore per-model
    // reordering entirely.

    private Map<String, Object> modelWithOrder(String id, int order) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("displayOrder", order);
        return m;
    }

    @Test
    @DisplayName("recalculateDefaults exposes a SECOND default that excludes bridges (defaultDirectProvider/Model) - for browser_agent which can't use full-session bridges")
    void recalculateDefaultsAlsoExposesDirectApiOnlyDefaultThatSkipsBridges() {
        // Audit follow-up: bridges (Claude Code / codex) can't serve as the
        // LLM for browser_agent because they're full-CLI agent sessions, not
        // per-step chat-completion APIs. The catalog must therefore expose a
        // SEPARATE default that excludes bridges, so consumers needing
        // per-step calls (BrowserAgentModule) can opt into it without losing
        // bridges as the overall default for chat / agent.create.
        Map<String, Object> codex = new LinkedHashMap<>(
            provider("codex", new java.util.ArrayList<>(List.of(
                modelWithOrder("claude-sonnet-4-6-cc", 1)))));
        codex.put("providerKind", "bridge");  // tagged by markBridgeProviders
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(
            codex,
            provider("anthropic", new java.util.ArrayList<>(List.of(
                modelWithOrder("claude-opus-4-6", 5)))),
            provider("openai", new java.util.ArrayList<>(List.of(
                modelWithOrder("gpt-5", 7))))
        )));

        service.recalculateDefaults(base);

        // Overall default = codex (rank #1 globally) - bridges still win for
        // chat / agent.create which work with their session-per-call shape.
        assertThat(base.get("defaultProvider")).isEqualTo("codex");
        assertThat(base.get("defaultModel")).isEqualTo("claude-sonnet-4-6-cc");
        // Direct-API default = anthropic (next non-bridge, rank #5) -
        // browser_agent picks this so the runner can do per-step calls.
        assertThat(base.get("defaultDirectProvider")).isEqualTo("anthropic");
        assertThat(base.get("defaultDirectModel")).isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("recalculateDefaults: when ONLY bridges are configured, defaultDirect* are null (CE without direct API)")
    void recalculateDefaultsDirectIsNullWhenOnlyBridges() {
        Map<String, Object> codex = new LinkedHashMap<>(
            provider("codex", new java.util.ArrayList<>(List.of(
                modelWithOrder("claude-sonnet-4-6-cc", 1)))));
        codex.put("providerKind", "bridge");
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(codex)));

        service.recalculateDefaults(base);

        assertThat(base.get("defaultProvider")).isEqualTo("codex");
        assertThat(base.get("defaultDirectProvider")).isNull();
        assertThat(base.get("defaultDirectModel")).isNull();
    }

    @Test
    @DisplayName("REGRESSION: recalculateDefaults picks the model with the lowest GLOBAL displayOrder, not the first model of the first provider")
    void recalculateDefaultsHonoursGlobalRankingAcrossProviders() {
        // Pre-fix bug: anthropic's PROVIDER displayOrder was lower than
        // codex's, so anthropic's first model was always picked even when
        // the admin dragged a codex model to position #1 globally. The new
        // implementation walks every (provider, model) pair and picks the
        // one with the lowest model-level displayOrder.
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(
                provider("anthropic", new java.util.ArrayList<>(List.of(
                        modelWithOrder("claude-opus-4-6", 5),
                        modelWithOrder("claude-sonnet-4-6", 6)))),
                provider("codex", new java.util.ArrayList<>(List.of(
                        modelWithOrder("claude-sonnet-4-6-cc", 1)))),
                provider("openai", new java.util.ArrayList<>(List.of(
                        modelWithOrder("gpt-5", 3))))
        )));

        service.recalculateDefaults(base);

        // codex/claude-sonnet-4-6-cc has the global rank #1 - must win even
        // though codex appears AFTER anthropic in the providers list.
        assertThat(base.get("defaultProvider")).isEqualTo("codex");
        assertThat(base.get("defaultModel")).isEqualTo("claude-sonnet-4-6-cc");
    }

    @Test
    @DisplayName("recalculateDefaults: empty catalog → defaults are null")
    void recalculateDefaultsEmptyCatalog() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", List.of());
        service.recalculateDefaults(base);
        assertThat(base.get("defaultProvider")).isNull();
        assertThat(base.get("defaultModel")).isNull();
    }

    @Test
    @DisplayName("recalculateDefaults: provider with no models is skipped, fallback to next provider's model")
    void recalculateDefaultsSkipsEmptyProviders() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(
                provider("emptyprovider", new java.util.ArrayList<>()),
                provider("openai", new java.util.ArrayList<>(List.of(
                        modelWithOrder("gpt-5", 7))))
        )));
        service.recalculateDefaults(base);
        assertThat(base.get("defaultProvider")).isEqualTo("openai");
        assertThat(base.get("defaultModel")).isEqualTo("gpt-5");
    }

    @Test
    @DisplayName("recalculateDefaults: missing displayOrder defaults to 999 (least preferred), not 0")
    void recalculateDefaultsTreatsMissingOrderAsLowestPriority() {
        Map<String, Object> ranked = new LinkedHashMap<>();
        ranked.put("id", "ranked-model");
        ranked.put("displayOrder", 100);
        Map<String, Object> unranked = new LinkedHashMap<>();
        unranked.put("id", "unranked-model");
        // No displayOrder → must be treated as 999 (last), not 0 (first).

        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(
                provider("p", new java.util.ArrayList<>(List.of(unranked, ranked)))
        )));
        service.recalculateDefaults(base);
        assertThat(base.get("defaultModel")).isEqualTo("ranked-model");
    }

    // ── markBridgeProviders - tag bridge entries so consumers can route ──

    @Test
    @DisplayName("REGRESSION (Audit H BLOCKER): getModelsWithOverrides STILL tags bridges + recalculates defaults + exposes bridgeUrl when overrides are EMPTY (fresh CE / never-dragged tenant)")
    @SuppressWarnings("unchecked")
    void getModelsWithOverridesAppliesBridgeTagsAndRecalcEvenWithoutOverrides() {
        // Pre-fix bug: an `overrides.isEmpty() → return base` early-return at
        // the top of getModelsWithOverrides bypassed markBridgeProviders,
        // bridgeUrl injection, and recalculateDefaults entirely. On any fresh
        // deployment (no rankings saved yet) the catalog came back unmarked,
        // BrowserAgentModule's bridge detection silently misrouted, and
        // defaults stayed at the YAML-static values instead of the global
        // flat ranking. Pin the post-fix invariant: even on the no-override
        // path, the catalog payload carries everything cross-service consumers
        // need.
        ModelCatalogService spy = Mockito.spy(new ModelCatalogService(
                repository, categoryRepository, llmProviderFactory, credentialRepository,
                cachedRateLimitProvider, "http://bridge-host.test:8093", authPricingSyncClient));

        Map<String, Object> raw = new LinkedHashMap<>();
        Map<String, Object> codex = new LinkedHashMap<>();
        codex.put("name", "codex");
        codex.put("configured", true);  // bypass the !configured && !hasDbKey filter
        codex.put("models", new java.util.ArrayList<>(List.of(modelWithOrder("claude-sonnet-4-6-cc", 1))));
        Map<String, Object> anthropic = new LinkedHashMap<>();
        anthropic.put("name", "anthropic");
        anthropic.put("configured", true);
        anthropic.put("models", new java.util.ArrayList<>(List.of(modelWithOrder("claude-opus-4-6", 5))));
        raw.put("providers", new java.util.ArrayList<>(List.of(codex, anthropic)));
        raw.put("defaultProvider", "anthropic");   // YAML-static default that the rewrite must override
        raw.put("defaultModel", "claude-opus-4-6");

        Mockito.when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(raw);
        Mockito.when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of()); // EMPTY - the regression case

        Map<String, Object> result = spy.getModelsWithOverrides();

        // 1. bridgeUrl exposed even with no overrides
        assertThat(result).containsEntry("bridgeUrl", "http://bridge-host.test:8093");
        // 2. bridge tagging happened even with no overrides
        List<Map<String, Object>> providers = (List<Map<String, Object>>) result.get("providers");
        Map<String, Object> codexResult = providers.stream()
            .filter(p -> "codex".equals(p.get("name"))).findFirst().orElseThrow();
        assertThat(codexResult).containsEntry("providerKind", "bridge");
        // 3. recalculateDefaults ran even with no overrides - codex (rank #1
        // globally) wins over anthropic, overriding the YAML-static default.
        assertThat(result).containsEntry("defaultProvider", "codex");
        assertThat(result).containsEntry("defaultModel", "claude-sonnet-4-6-cc");
    }

    @Test
    @DisplayName("markBridgeProviders tags every entry whose name is in BRIDGE_PROVIDER_TO_CLI_ID, case-insensitively")
    void markBridgeProvidersTagsBridgesOnly() {
        Map<String, Object> codex = new LinkedHashMap<>();
        codex.put("name", "codex");
        Map<String, Object> openai = new LinkedHashMap<>();
        openai.put("name", "openai");
        Map<String, Object> claudeCode = new LinkedHashMap<>();
        claudeCode.put("name", "claude-code");
        // Mixed-case to exercise the .toLowerCase() guard - without it, a
        // provider name like "Codex" (yml typo, manual entry) would silently
        // fail to be tagged as a bridge.
        Map<String, Object> mixedCaseCodex = new LinkedHashMap<>();
        mixedCaseCodex.put("name", "Codex");
        List<Map<String, Object>> providers = new java.util.ArrayList<>(
            List.of(codex, openai, claudeCode, mixedCaseCodex));

        ModelCatalogService.markBridgeProviders(providers);

        assertThat(codex).containsEntry("providerKind", "bridge");
        assertThat(claudeCode).containsEntry("providerKind", "bridge");
        assertThat(mixedCaseCodex)
            .as("case-insensitive match: 'Codex' must be tagged the same as 'codex'")
            .containsEntry("providerKind", "bridge");
        // Direct API providers must NOT be tagged - absence of
        // providerKind == direct API path.
        assertThat(openai).doesNotContainKey("providerKind");
    }
}

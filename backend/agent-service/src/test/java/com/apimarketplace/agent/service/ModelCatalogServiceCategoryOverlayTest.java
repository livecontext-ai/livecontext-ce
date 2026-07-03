package com.apimarketplace.agent.service;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelCategorySettingsId;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V156 sidecar overlay contract - the rules callers depend on:
 * <ul>
 *   <li>Sidecar row absent → fall back to global {@code ranking}/{@code enabled}.</li>
 *   <li>Sidecar row with rank → ranking is overridden.</li>
 *   <li>Sidecar row with enabled=false → model is removed from the picker.</li>
 *   <li>category=null → no sidecar lookup at all (legacy code path).</li>
 *   <li>bulkUpdateCategoryRankings + setCategoryEnabled write through the
 *       sidecar repository, never the global ranking column.</li>
 *   <li>Invalid category shape is rejected before any DB write.</li>
 * </ul>
 *
 * <p>Tests run against a partial spy so the heavy YAML/Bridge pipeline is stubbed
 * and we exercise only the overlay merge.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - V156 per-category overlay")
class ModelCatalogServiceCategoryOverlayTest {

    @Mock private ModelConfigOverrideRepository repository;
    @Mock private ModelCategorySettingsRepository categoryRepository;
    @Mock private LLMProviderFactory llmProviderFactory;
    @Mock private LlmCredentialRepository credentialRepository;
    @Mock private CachedModelRateLimitProvider cachedRateLimitProvider;
    @Mock private AuthPricingSyncClient authPricingSyncClient;
    @Mock private CloudLlmRuntimeAccess cloudLlmRuntimeAccess;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(
                repository, categoryRepository, llmProviderFactory, credentialRepository,
                cachedRateLimitProvider, "", authPricingSyncClient);
    }

    @Test
    @DisplayName("category-aware response echoes the requested category for self-describing payloads")
    void categoryEchoedInResponse() {
        // Stub builds a fresh base catalog on every invocation - the service
        // mutates the response in-place, so reusing one instance pollutes the
        // second assertion.
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5", 1, true);
        lenient().when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(gpt5));
        lenient().when(categoryRepository.findByCategory("browser_agent")).thenReturn(List.of());
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenAnswer(inv -> freshBase("openai", "gpt-5"));
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);

        Map<String, Object> withCategory = service.getModelsForCategory("browser_agent");
        assertThat(withCategory).containsEntry("category", "browser_agent");

        Map<String, Object> withoutCategory = service.getModelsForCategory(null);
        // Legacy callers must not see a `category` field appear out of nowhere.
        assertThat(withoutCategory).doesNotContainKey("category");
    }

    private static Map<String, Object> freshBase(String provider, String modelId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", provider);
        p.put("configured", true);
        p.put("models", new java.util.ArrayList<>(List.of(model(modelId, 1))));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(p)));
        return base;
    }

    @Test
    @DisplayName("category=null path NEVER queries the sidecar repository")
    void nullCategoryBypassesSidecarLookup() {
        // Stub a minimal base catalog so getModelsForCategory(null) can run.
        // Using lenient since not every assertion path triggers all stubs.
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(
                provider("openai", new java.util.ArrayList<>(List.of(model("gpt-5", 1)))))));
        lenient().when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        service.getModelsForCategory(null);

        verify(categoryRepository, never()).findByCategory(any());
    }

    @Test
    @DisplayName("BYOK/cloud-prod: V157 factory image-gen rows (is_custom=false) are HIDDEN when the provider has no key - no provider, no model (reverses prior keyless-surfacing per product decision)")
    void v157RowsHiddenWithoutApiKeyInByok() {
        // Product decision: the admin Models panel now applies the SAME key
        // filter as the picker. In cloud-prod / CE BYOK, a provider with no key
        // (env or DB) is dropped by getAvailableProvidersBase, and the V156
        // second pass only re-surfaces is_custom=true LOCAL rows - never
        // is_custom=false factory/sync seeds. So V157 image-gen rows for an
        // unconfigured openai/google stay hidden until their key is added.
        // (When the key IS configured they surface via the main loop - see
        // getEffectiveModelListInjectsV157SeededImageGenRows; in cloud-connect
        // they surface for relay-supported providers - see the test below.)
        ModelConfigOverrideEntity gptImage = entity(30L, "openai", "gpt-image-1.5-medium", 100, true);
        gptImage.setMode("image");
        ModelConfigOverrideEntity geminiImage = entity(31L, "google", "gemini-2.5-flash-image", 101, true);
        geminiImage.setMode("image");
        when(repository.findAllByOrderByRankingAsc())
                .thenReturn(List.of(gptImage, geminiImage));

        // Empty YAML base - simulates "no API key configured for ANY provider"
        // (getAvailableProvidersBase removes unconfigured providers).
        Map<String, Object> emptyBase = new LinkedHashMap<>();
        emptyBase.put("providers", new java.util.ArrayList<>());
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(emptyBase);

        List<Map<String, Object>> rows = service.getEffectiveModelList("image_generation");

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("CE cloud-connect: V157 image-gen rows surface for a relay-supported provider WITHOUT a local key (cloud account is the source)")
    void v157RowsSurfaceInCloudConnectForRelayProviderWithoutLocalKey() {
        ReflectionTestUtils.setField(service, "cloudLlmRuntimeAccess", cloudLlmRuntimeAccess);
        when(cloudLlmRuntimeAccess.isCloudSelected("tenant-cloud")).thenReturn(true);
        // openai is relay-supported and has NO local key (configured=false). In
        // cloud-connect the base filter KEEPS its shell, so the image-gen DB
        // override lands in it via the main loop - the admin sees it without a
        // local key, matching the picker.
        ModelConfigOverrideEntity gptImage = entity(30L, "openai", "gpt-image-1.5-medium", 100, true);
        gptImage.setMode("image");
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(gptImage));

        Map<String, Object> openaiProvider = new LinkedHashMap<>();
        openaiProvider.put("name", "openai");
        openaiProvider.put("configured", false);
        // YAML carries only a chat model; the mode filter empties it but keeps
        // the shell so the DB image-gen override can land in it.
        openaiProvider.put("models", new java.util.ArrayList<>(List.of(model("gpt-5", 1))));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(openaiProvider)));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);

        List<Map<String, Object>> rows = service.getEffectiveModelList("image_generation", "tenant-cloud");

        assertThat(rows).extracting(r -> r.get("id")).containsExactly("gpt-image-1.5-medium");
    }

    @Test
    @DisplayName("second pass: an absent-provider is_custom=true LOCAL row surfaces; an is_custom=false factory row under an absent provider stays hidden")
    void secondPassSurfacesOnlyLocalCustomForAbsentProvider() {
        // Both providers are absent from the YAML base (no key in BYOK), so both
        // rows reach the V156 second pass. Only the is_custom=true LOCAL one
        // (admin-added server, no key needed) must surface; the is_custom=false
        // factory/sync row stays hidden ("no provider, no model"). Locks BOTH
        // halves of the second-pass is_custom filter - deleting the second pass
        // would fail this (the local row would vanish).
        ModelConfigOverrideEntity localCustom = entity(40L, "local-sd", "sd-xl", 100, true);
        localCustom.setMode("image");
        localCustom.setCustom(true);
        ModelConfigOverrideEntity factoryRow = entity(41L, "openai", "gpt-image-1.5", 101, true);
        factoryRow.setMode("image");
        factoryRow.setCustom(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(localCustom, factoryRow));

        // Empty YAML base: both providers are absent.
        Map<String, Object> emptyBase = new LinkedHashMap<>();
        emptyBase.put("providers", new java.util.ArrayList<>());
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(emptyBase);

        List<Map<String, Object>> rows = service.getEffectiveModelList("image_generation");

        assertThat(rows).extracting(r -> r.get("id")).containsExactly("sd-xl");
    }

    @Test
    @DisplayName("REGRESSION (user-reported \"No models configured\"): getEffectiveModelList(image_generation) MUST surface V157-seeded image-gen rows - DB-only is_custom=false rows that need a YAML provider shell to land in")
    void getEffectiveModelListInjectsV157SeededImageGenRows() {
        // V157 inserts 8 image-gen rows in model_config_overrides with
        // mode='image' and is_custom=false. They are NOT in YAML
        // (application.yml never declares image-gen models). The admin tab
        // image_generation calls getEffectiveModelList("image_generation").
        //
        // The bug fixed: an over-aggressive mode-filter removed the openai
        // and google provider shells (because their YAML models were all
        // chat - filtered out for image_generation), so the downstream
        // injection step had no provider bucket to drop the V157 rows into.
        // Result: empty admin list → "No models configured".
        //
        // Pin the post-fix invariant: empty YAML provider shells are
        // preserved so DB-only image-gen rows surface in the admin tab.
        ModelConfigOverrideEntity gptImageMedium = entity(30L, "openai", "gpt-image-1.5-medium", 100, true);
        gptImageMedium.setMode("image");
        ModelConfigOverrideEntity gptImageHigh = entity(31L, "openai", "gpt-image-1.5-high", 101, true);
        gptImageHigh.setMode("image");
        ModelConfigOverrideEntity geminiImage = entity(32L, "google", "gemini-2.5-flash-image", 102, true);
        geminiImage.setMode("image");

        when(repository.findAllByOrderByRankingAsc())
                .thenReturn(List.of(gptImageMedium, gptImageHigh, geminiImage));

        // YAML base - openai and google declare ONLY chat models. After
        // mode-filter, both providers have empty models[] but the SHELL
        // must survive so the V157 rows can be injected into them.
        Map<String, Object> openaiProvider = new LinkedHashMap<>();
        openaiProvider.put("name", "openai");
        openaiProvider.put("configured", true);
        openaiProvider.put("models", new java.util.ArrayList<>(List.of(
                model("gpt-5", 1) // chat - gets filtered out by mode predicate
        )));
        Map<String, Object> googleProvider = new LinkedHashMap<>();
        googleProvider.put("name", "google");
        googleProvider.put("configured", true);
        googleProvider.put("models", new java.util.ArrayList<>(List.of(
                model("gemini-2.5-pro", 2) // chat - also filtered
        )));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(openaiProvider, googleProvider)));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);

        List<Map<String, Object>> rows = service.getEffectiveModelList("image_generation");

        // The 3 V157 rows surface (image-gen ONLY - no chat leak). They
        // landed in their respective YAML provider shells (openai, google)
        // even though those shells had zero remaining YAML chat models
        // after the mode-filter.
        assertThat(rows).extracting(r -> r.get("id"))
                .containsExactlyInAnyOrder("gpt-image-1.5-medium", "gpt-image-1.5-high", "gemini-2.5-flash-image");
    }

    @Test
    @DisplayName("REGRESSION (user-reported): image_generation tab MUST NOT show chat models - only mode='image' rows leak through")
    void imageGenerationTabFiltersOutChatModels() {
        // 2 chat rows + 1 image row in the override list. The image_generation
        // tab must return ONLY the image row - even when the chat rows have
        // no sidecar entry for image_generation (which would otherwise let
        // them through via the "fall back to global" path).
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5", 1, true);
        gpt5.setMode("chat");
        ModelConfigOverrideEntity claude = entity(20L, "anthropic", "claude-opus-4-6", 2, true);
        claude.setMode("chat");
        ModelConfigOverrideEntity gptImage = entity(30L, "openai", "gpt-image-1.5-medium", 100, true);
        gptImage.setMode("image");

        when(repository.findAllByOrderByRankingAsc())
                .thenReturn(List.of(gpt5, claude, gptImage));
        when(categoryRepository.findByCategory("image_generation")).thenReturn(List.of(
                sidecar(30L, "image_generation", 1, true)));

        // YAML base carries chat models for openai + anthropic; image models
        // never come from YAML, only from the DB. The mode-filter must drop
        // the YAML chat rows AND the chat overrides, leaving only the image
        // override to surface.
        Map<String, Object> openaiProvider = new LinkedHashMap<>();
        openaiProvider.put("name", "openai");
        openaiProvider.put("configured", true);
        openaiProvider.put("models", new java.util.ArrayList<>(List.of(
                model("gpt-5", 1),                         // chat - must be filtered
                model("gpt-image-1.5-medium", 100, "image") // image - surfaces
        )));
        Map<String, Object> anthropicProvider = new LinkedHashMap<>();
        anthropicProvider.put("name", "anthropic");
        anthropicProvider.put("configured", true);
        anthropicProvider.put("models", new java.util.ArrayList<>(List.of(
                model("claude-opus-4-6", 2)               // chat - anthropic provider must be dropped
        )));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(openaiProvider, anthropicProvider)));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);

        Map<String, Object> result = service.getModelsForCategory("image_generation");

        // image_generation tab returns ONLY the image-gen model. Empty
        // provider shells (anthropic with no image-gen rows) are PRESERVED
        // on purpose - they're needed by the downstream injection loop so
        // DB-only image-gen rows can land in their YAML provider bucket.
        // Without that, V157-seeded rows would have nowhere to land and the
        // tab would render "No models configured".
        assertThat(extractAllModelIds(result)).containsExactly("gpt-image-1.5-medium");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) result.get("providers");
        // openai survives carrying the image-gen row; anthropic survives as
        // an empty shell (zero image rows but provider bucket preserved).
        assertThat(providers).extracting(p -> p.get("name"))
                .containsExactlyInAnyOrder("openai", "anthropic");
    }

    @Test
    @DisplayName("REGRESSION (user-reported 2026-06-16): admin Chat/Agent tab - getEffectiveModelList(null) - MUST NOT show image-gen models; mode='image' rows belong only to the Image Generation tab")
    void chatAgentAdminTabExcludesImageGenModels() {
        // The Chat/Agent tab reads the legacy GLOBAL view (category=null) so its
        // drag/toggle writes keep landing on the global ranking/enabled columns.
        // Pre-fix, the null path skipped the mode-filter entirely, so V157-seeded
        // image-gen rows (mode='image', surfaced via DB-only custom-injection)
        // leaked into the Chat tab. Post-fix the null path is mode-filtered as
        // 'chat' → image rows are dropped while chat models stay.
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5", 1, true);
        gpt5.setMode("chat");
        ModelConfigOverrideEntity gptImage = entity(30L, "openai", "gpt-image-1.5-medium", 100, true);
        gptImage.setMode("image");
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(gpt5, gptImage));

        // YAML base declares ONLY the chat model. Image-gen rows never come from
        // YAML - they are DB-only (V157) and would surface via custom-injection.
        Map<String, Object> openaiProvider = new LinkedHashMap<>();
        openaiProvider.put("name", "openai");
        openaiProvider.put("configured", true);
        openaiProvider.put("models", new java.util.ArrayList<>(List.of(model("gpt-5", 1))));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(openaiProvider)));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);

        List<Map<String, Object>> rows = service.getEffectiveModelList(null);

        assertThat(rows).extracting(r -> r.get("id"))
                .contains("gpt-5")
                .doesNotContain("gpt-image-1.5-medium");
        // The null path keeps the legacy global semantics - never the sidecar.
        verify(categoryRepository, never()).findByCategory(any());
    }

    @Test
    @DisplayName("REGRESSION (user-reported 2026-06-16, sibling): chat picker / flat LLM catalog - getModelsForCategory(null) - MUST NOT offer image-gen models as chat completions")
    void chatPickerNullCategoryExcludesImageGenModels() {
        // ChatControllerV3 fetches the chat model picker via getModelsInfo(null)
        // → getModelsForCategory(null); listAvailableModels(null) builds the flat
        // catalog the LLM sees from the same path. Pre-fix image-gen rows leaked
        // into both; post-fix the null path is mode-filtered as 'chat'.
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5", 1, true);
        gpt5.setMode("chat");
        // A synced/custom DB-only override with NO mode (mode=null) must stay
        // chat-eligible on the null path - acceptsMode("chat", null)==true.
        // Guards against the mode-filter becoming over-aggressive (dropping
        // rows whose mode simply wasn't set).
        ModelConfigOverrideEntity miniNoMode = entity(40L, "openai", "gpt-5-mini", 2, true);
        ModelConfigOverrideEntity gptImage = entity(30L, "openai", "gpt-image-1.5-medium", 100, true);
        gptImage.setMode("image");
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(gpt5, miniNoMode, gptImage));

        // Base declares only gpt-5; gpt-5-mini and the image row are DB-only and
        // surface via custom-injection (notInYaml).
        Map<String, Object> openaiProvider = provider("openai",
                new java.util.ArrayList<>(List.of(model("gpt-5", 1))));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(openaiProvider)));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);

        Map<String, Object> result = service.getModelsForCategory(null);

        assertThat(extractAllModelIds(result))
                .contains("gpt-5", "gpt-5-mini")   // chat + mode=null both survive
                .doesNotContain("gpt-image-1.5-medium");
        verify(categoryRepository, never()).findByCategory(any());
    }

    @Test
    @DisplayName("browser_agent tab also excludes image-gen models (mode='image' not eligible) while keeping chat models")
    void browserAgentTabExcludesImageGenModels() {
        // Sibling of the chat exclusion: browser_agent has always filtered image
        // rows (acceptsMode("browser_agent","image")==false). Pin it explicitly
        // since the fix re-routes the shared acceptsMode/modeFilterKey path.
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5", 1, true);
        gpt5.setMode("chat");
        ModelConfigOverrideEntity gptImage = entity(30L, "openai", "gpt-image-1.5-medium", 100, true);
        gptImage.setMode("image");
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(gpt5, gptImage));
        when(categoryRepository.findByCategory("browser_agent")).thenReturn(List.of());

        Map<String, Object> openaiProvider = provider("openai",
                new java.util.ArrayList<>(List.of(model("gpt-5", 1))));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(openaiProvider)));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);

        Map<String, Object> result = service.getModelsForCategory("browser_agent");

        assertThat(extractAllModelIds(result))
                .contains("gpt-5")
                .doesNotContain("gpt-image-1.5-medium");
    }

    @Test
    @DisplayName("Sidecar absent → entity.ranking + entity.enabled stand")
    void sidecarAbsentFallsBackToGlobal() {
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5", 1, true);
        ModelConfigOverrideEntity claude = entity(20L, "anthropic", "claude-opus-4-6", 2, true);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(gpt5, claude));
        when(categoryRepository.findByCategory("browser_agent")).thenReturn(List.of());
        stubBaseCatalog(gpt5, claude);

        Map<String, Object> result = service.getModelsForCategory("browser_agent");

        // No sidecar rows → original ranking carried through (global behaviour).
        assertThat(extractAllModelIds(result)).containsExactly("gpt-5", "claude-opus-4-6");
    }

    @Test
    @DisplayName("Sidecar rank wins over the global ranking - model-level displayOrder + global default reflect it")
    void sidecarRankOverridesGlobal() {
        // Globally gpt-5 ranks 1, claude ranks 2.
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5", 1, true);
        ModelConfigOverrideEntity claude = entity(20L, "anthropic", "claude-opus-4-6", 2, true);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(gpt5, claude));
        // browser_agent flips the order: claude ranks 1, gpt-5 ranks 2.
        when(categoryRepository.findByCategory("browser_agent")).thenReturn(List.of(
                sidecar(20L, "browser_agent", 1, true),
                sidecar(10L, "browser_agent", 2, true)));
        stubBaseCatalog(gpt5, claude);

        Map<String, Object> result = service.getModelsForCategory("browser_agent");

        // Per-model displayOrder reflects the sidecar rank (catalog stays grouped
        // by provider, but downstream consumers - frontend picker, listAvailableModels()
        // - sort by displayOrder globally).
        assertThat(displayOrderOf(result, "openai", "gpt-5")).isEqualTo(2);
        assertThat(displayOrderOf(result, "anthropic", "claude-opus-4-6")).isEqualTo(1);
        // recalculateDefaults() walks all (provider, model) pairs and picks the
        // lowest displayOrder - i.e. the user-visible #1. After overlay that's claude.
        assertThat(result.get("defaultProvider")).isEqualTo("anthropic");
        assertThat(result.get("defaultModel")).isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("Sidecar enabled=false removes the model - even if globally enabled")
    void sidecarDisabledRemovesModel() {
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5", 1, true);
        ModelConfigOverrideEntity claude = entity(20L, "anthropic", "claude-opus-4-6", 2, true);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(gpt5, claude));
        when(categoryRepository.findByCategory("browser_agent")).thenReturn(List.of(
                sidecar(20L, "browser_agent", 1, false))); // disable claude for browser_agent only
        stubBaseCatalog(gpt5, claude);

        Map<String, Object> result = service.getModelsForCategory("browser_agent");

        // claude was disabled in browser_agent - must be gone.
        assertThat(extractAllModelIds(result)).containsExactly("gpt-5");
    }

    @Test
    @DisplayName("bulkUpdateCategoryRankings writes through the sidecar repository, NOT the global ranking column")
    void bulkUpdateWritesToSidecar() {
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5", 99, true);
        when(repository.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.of(gpt5));
        when(categoryRepository.findById(new ModelCategorySettingsId(10L, "browser_agent")))
                .thenReturn(Optional.empty());

        service.bulkUpdateCategoryRankings("browser_agent",
                List.of(Map.of("provider", "openai", "modelId", "gpt-5", "ranking", 3)));

        ArgumentCaptor<ModelCategorySettingsEntity> captor =
                ArgumentCaptor.forClass(ModelCategorySettingsEntity.class);
        verify(categoryRepository).save(captor.capture());
        assertThat(captor.getValue().getModelConfigId()).isEqualTo(10L);
        assertThat(captor.getValue().getCategory()).isEqualTo("browser_agent");
        assertThat(captor.getValue().getRank()).isEqualTo(3);
        // Global ranking on the parent must NOT be touched - bridges/admin-edit
        // expectations rely on that column staying at its admin-set value.
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("REGRESSION (user-reported \"can't reorder browser_agent\"): bulkUpdateCategoryRankings lazy-creates the parent row for YAML-only models - without this, models declared in application.yml but never synced silently skip on re-rank")
    void bulkUpdateLazyCreatesParentRowForYamlOnlyModels() {
        // Real-world scenario: openai/gpt-5 is in application.yml but
        // ModelCatalogSyncService never synced it (no LiteLLM/OpenRouter
        // entry under that exact id). Admin opens browser_agent tab, drags
        // gpt-5 to position 1, expects persistence.
        // Pre-fix: parent.isEmpty() → continue → silent skip → drag never
        // persists → user sees order revert on refresh.
        // Post-fix: stub parent created on the fly, sidecar attaches normally.
        when(repository.findByProviderAndModelId("openai", "gpt-5"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity stub = inv.getArgument(0);
            stub.setId(99L);
            return stub;
        });
        when(categoryRepository.findById(new ModelCategorySettingsId(99L, "browser_agent")))
                .thenReturn(Optional.empty());

        service.bulkUpdateCategoryRankings("browser_agent",
                List.of(Map.of("provider", "openai", "modelId", "gpt-5", "ranking", 1)));

        // Stub parent row was created with the right shape (provider, modelId,
        // a non-null displayName mandated by the V109 NOT NULL constraint).
        ArgumentCaptor<ModelConfigOverrideEntity> stubCaptor =
                ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(repository).save(stubCaptor.capture());
        assertThat(stubCaptor.getValue().getProvider()).isEqualTo("openai");
        assertThat(stubCaptor.getValue().getModelId()).isEqualTo("gpt-5");
        assertThat(stubCaptor.getValue().getDisplayName()).isNotBlank();

        // And the sidecar row attaches to the freshly-created stub.
        ArgumentCaptor<ModelCategorySettingsEntity> sidecarCaptor =
                ArgumentCaptor.forClass(ModelCategorySettingsEntity.class);
        verify(categoryRepository).save(sidecarCaptor.capture());
        assertThat(sidecarCaptor.getValue().getModelConfigId()).isEqualTo(99L);
        assertThat(sidecarCaptor.getValue().getCategory()).isEqualTo("browser_agent");
        assertThat(sidecarCaptor.getValue().getRank()).isEqualTo(1);
    }

    @Test
    @DisplayName("bulkUpdateCategoryRankings rejects an invalid category shape (V156 CHECK mirror)")
    void bulkUpdateRejectsInvalidCategory() {
        assertThatThrownBy(() -> service.bulkUpdateCategoryRankings(
                "Bad Category!", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid category");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("REGRESSION (audit P1: validate upfront): bulkUpdateCategoryRankings rejects malformed rows BEFORE writing any sidecar - partial-batch corruption guard")
    void bulkUpdateRejectsMalformedRowsBeforeAnyWrite() {
        // Missing 'ranking' field - old code NPE'd mid-loop after writing the
        // valid row that came first; validate-upfront blocks the whole batch.
        java.util.List<Map<String, Object>> badBatch = java.util.List.of(
                Map.of("provider", "openai", "modelId", "gpt-5", "ranking", 1),
                Map.of("provider", "anthropic", "modelId", "claude-opus-4-6"));   // no ranking

        assertThatThrownBy(() -> service.bulkUpdateCategoryRankings("chat", badBatch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankings[1].ranking");
        verify(categoryRepository, never()).save(any());
        // Cache must NOT be invalidated - nothing was written.
        verify(cachedRateLimitProvider, never()).refreshCache();
    }

    @Test
    @DisplayName("bulkUpdateCategoryRankings rejects out-of-range rank values (defends against negative or wildly-large input)")
    void bulkUpdateRejectsOutOfRangeRank() {
        assertThatThrownBy(() -> service.bulkUpdateCategoryRankings("chat", java.util.List.of(
                Map.of("provider", "openai", "modelId", "gpt-5", "ranking", -1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> service.bulkUpdateCategoryRankings("chat", java.util.List.of(
                Map.of("provider", "openai", "modelId", "gpt-5", "ranking", 100_001))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    @DisplayName("bulkUpdateCategoryRankings invalidates the rate-limit cache after the write (admin sees fresh data)")
    void bulkUpdateInvalidatesCache() {
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5", 99, true);
        when(repository.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.of(gpt5));
        when(categoryRepository.findById(new ModelCategorySettingsId(10L, "chat")))
                .thenReturn(Optional.empty());

        service.bulkUpdateCategoryRankings("chat",
                List.of(Map.of("provider", "openai", "modelId", "gpt-5", "ranking", 1)));

        verify(cachedRateLimitProvider, times(1)).refreshCache();
    }

    @Test
    @DisplayName("setCategoryEnabled creates a sidecar row carrying the model's current global ranking when none exists")
    void setCategoryEnabledSeedsRankFromGlobal() {
        ModelConfigOverrideEntity gpt5 = entity(10L, "openai", "gpt-5", 7, true);
        when(repository.findByProviderAndModelId("openai", "gpt-5")).thenReturn(Optional.of(gpt5));
        when(categoryRepository.findById(new ModelCategorySettingsId(10L, "image_generation")))
                .thenReturn(Optional.empty());

        service.setCategoryEnabled("openai", "gpt-5", "image_generation", false);

        ArgumentCaptor<ModelCategorySettingsEntity> captor =
                ArgumentCaptor.forClass(ModelCategorySettingsEntity.class);
        verify(categoryRepository).save(captor.capture());
        assertThat(captor.getValue().getRank()).isEqualTo(7);
        assertThat(captor.getValue().getEnabled()).isFalse();
        verify(cachedRateLimitProvider, times(1)).refreshCache();
    }

    @Test
    @DisplayName("setCategoryEnabled rejects an invalid category key (V156 CHECK shape mirror)")
    void setCategoryEnabledRejectsInvalidCategory() {
        assertThatThrownBy(() -> service.setCategoryEnabled(
                "openai", "gpt-5", "Bad Category!", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid category");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("REGRESSION (audit P1 on cloneOverride completeness): every field in cloneOverride is populated on the returned overlay row so the V156 path doesn't silently null-out downstream getters")
    void cloneOverlayCarriesEveryFieldThroughTheV156Path() {
        // Build an entity with EVERY non-derived field populated. The overlay
        // path must surface each one through the (possibly cloned) instance.
        ModelConfigOverrideEntity rich = new ModelConfigOverrideEntity();
        rich.setId(42L);
        rich.setProvider("openai");
        rich.setModelId("gpt-5");
        rich.setEnabled(true);
        rich.setDisplayName("GPT-5");
        rich.setDescription("desc");
        rich.setTier("top");
        rich.setRanking(1);
        rich.setRecommended(true);
        rich.setPriceInput(new java.math.BigDecimal("1.5"));
        rich.setPriceOutput(new java.math.BigDecimal("3.0"));
        rich.setRateLimitTpm(1000);
        rich.setRateLimitRpm(10);
        rich.setRateLimitTpmPerTenant(100);
        rich.setRateLimitRpmPerTenant(2);
        rich.setSource("manual");
        rich.setProviderKind("byok");
        rich.setMode("chat");
        rich.setCustom(false);
        rich.setDeprecatedAt(null);

        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(rich));
        when(categoryRepository.findByCategory("chat")).thenReturn(List.of(
                sidecar(42L, "chat", 7, true)));
        stubBaseCatalog(rich);

        Map<String, Object> result = service.getModelsForCategory("chat");

        // The model surfaces with the sidecar-overlaid rank PLUS every
        // additional field carried through. If cloneOverride drops a field
        // (e.g. tier) this assertion fails because applyOverride writes the
        // field from the (cloned) entity onto the catalog row.
        @SuppressWarnings("unchecked")
        Map<String, Object> catalogRow = ((List<Map<String, Object>>) (
                (List<Map<String, Object>>) result.get("providers")).get(0).get("models")).get(0);
        assertThat(catalogRow).containsEntry("displayOrder", 7);  // overlaid rank
        assertThat(catalogRow).containsEntry("tier", "top");
        assertThat(catalogRow).containsEntry("recommended", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> pricing = (Map<String, Object>) catalogRow.get("pricing");
        assertThat(pricing).containsEntry("input", 1.5).containsEntry("output", 3.0);
    }

    @Test
    @DisplayName("setCategoryEnabled on an unknown model raises IllegalArgumentException")
    void setCategoryEnabledRejectsUnknownModel() {
        when(repository.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setCategoryEnabled(
                "openai", "ghost-model", "chat", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown model");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("CE (embedded): admin getEffectiveModelList DROPS a custom openrouter/cohere override (agrees with the picker); cloud KEEPS it")
    void adminPanelCeModeDropsBlockedCustomProvider() {
        // A CE admin saved a custom (is_custom=true) override under the
        // openrouter aggregator. It is NOT in the YAML base (the factory gate
        // keeps openrouter out in CE), so it only reaches the flat admin list
        // via the standalone-injection path (category != null). On a self-hosted
        // install the admin Models panel must drop it, exactly like the picker -
        // no openrouter/cohere anywhere. Cloud (empty auth.mode) keeps it.
        ModelConfigOverrideEntity blocked = entity(50L, "openrouter", "anthropic/claude-sonnet-4", 100, true);
        blocked.setMode("image");
        blocked.setCustom(true);
        ModelConfigOverrideEntity keep = entity(51L, "google", "gemini-2.5-flash-image", 101, true);
        keep.setMode("image");
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(blocked, keep));

        // Base has only the google shell (openrouter is gated out in CE).
        Map<String, Object> googleProvider = new LinkedHashMap<>();
        googleProvider.put("name", "google");
        googleProvider.put("configured", true);
        googleProvider.put("models", new java.util.ArrayList<>(List.of(model("gemini-2.5-pro", 2))));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(List.of(googleProvider)));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);

        // CE: openrouter row dropped, google image row survives.
        ReflectionTestUtils.setField(service, "authMode", "embedded");
        List<Map<String, Object>> ceRows = service.getEffectiveModelList("image_generation");
        assertThat(ceRows).extracting(r -> r.get("provider")).doesNotContain("openrouter");
        assertThat(ceRows).extracting(r -> r.get("id")).contains("gemini-2.5-flash-image");

        // Cloud: the openrouter custom row is kept (relay fallback territory).
        ReflectionTestUtils.setField(service, "authMode", "");
        List<Map<String, Object>> cloudRows = service.getEffectiveModelList("image_generation");
        assertThat(cloudRows).extracting(r -> r.get("provider")).contains("openrouter");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void stubBaseCatalog(ModelConfigOverrideEntity... entities) {
        // Build a base catalog containing exactly the provided entities so the
        // overlay path has a real provider list to mutate.
        Map<String, Map<String, Object>> byProvider = new LinkedHashMap<>();
        for (ModelConfigOverrideEntity e : entities) {
            Map<String, Object> p = byProvider.computeIfAbsent(e.getProvider(),
                    k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", k);
                        m.put("models", new java.util.ArrayList<Map<String, Object>>());
                        m.put("configured", true);
                        return m;
                    });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> models = (List<Map<String, Object>>) p.get("models");
            models.add(model(e.getModelId(),
                    e.getRanking() == null ? 999 : e.getRanking()));
        }
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new java.util.ArrayList<>(byProvider.values()));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);
    }

    private static Map<String, Object> provider(String name, List<Map<String, Object>> models) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("models", models);
        p.put("configured", true);
        return p;
    }

    private static Map<String, Object> model(String id, int displayOrder) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", id);
        m.put("displayOrder", displayOrder);
        return m;
    }

    private static Map<String, Object> model(String id, int displayOrder, String mode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", id);
        m.put("displayOrder", displayOrder);
        m.put("mode", mode);
        return m;
    }

    private static ModelConfigOverrideEntity entity(Long id, String provider, String modelId,
                                                    Integer ranking, boolean enabled) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setId(id);
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setDisplayName(modelId);
        e.setRanking(ranking);
        e.setEnabled(enabled);
        return e;
    }

    private static ModelCategorySettingsEntity sidecar(Long modelId, String category,
                                                       Integer rank, boolean enabled) {
        ModelCategorySettingsEntity s = new ModelCategorySettingsEntity();
        s.setModelConfigId(modelId);
        s.setCategory(category);
        s.setRank(rank);
        s.setEnabled(enabled);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractAllModelIds(Map<String, Object> catalog) {
        List<String> out = new java.util.ArrayList<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) catalog.get("providers")) {
            for (Map<String, Object> m : (List<Map<String, Object>>) p.get("models")) {
                out.add((String) m.get("id"));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static int displayOrderOf(Map<String, Object> catalog, String provider, String modelId) {
        for (Map<String, Object> p : (List<Map<String, Object>>) catalog.get("providers")) {
            if (!provider.equals(p.get("name"))) continue;
            for (Map<String, Object> m : (List<Map<String, Object>>) p.get("models")) {
                if (modelId.equals(m.get("id"))) {
                    return ((Number) m.get("displayOrder")).intValue();
                }
            }
        }
        throw new AssertionError("Model " + provider + ":" + modelId + " not found in catalog");
    }
}

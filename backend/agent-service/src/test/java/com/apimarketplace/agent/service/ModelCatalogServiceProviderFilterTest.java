package com.apimarketplace.agent.service;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the provider filtering logic in {@link ModelCatalogService}.
 *
 * <p>The two paths DIVERGE by design:
 * <ul>
 *   <li>Picker / runtime path ({@code getModelsForCategory}): drops providers
 *       with neither an env key nor a DB key, except cloud-relay supported ones
 *       when the tenant's LLM source is CLOUD ("no provider, no model" - a
 *       selector must never offer an unrunnable model).</li>
 *   <li>Admin config path ({@code getEffectiveModelList}): lists the FULL
 *       catalog - every provider, cloud-prod and CE alike, keyed or not - so
 *       each model can be ranked/priced/enabled before its key exists (the
 *       ranking is what the signed bundle ships). Each row is annotated with an
 *       {@code available} flag (would the picker offer it?) so the UI can badge
 *       "not configured" without hiding the model.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - provider base filtering")
class ModelCatalogServiceProviderFilterTest {

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

    private Map<String, Object> provider(String name, boolean configured) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("configured", configured);
        p.put("models", new ArrayList<>(List.of(model(name + "-model"))));
        return p;
    }

    private Map<String, Object> model(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        return m;
    }

    private Map<String, Object> modelWithMode(String id, String mode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("mode", mode);
        return m;
    }

    private Map<String, Object> adminBase(Map<String, Object>... providers) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new ArrayList<>(List.of(providers)));
        base.put("defaultProvider", "anthropic");
        base.put("defaultModel", "claude-sonnet");
        return base;
    }

    @Test
    @DisplayName("tenant Cloud source keeps unconfigured API providers and tags them as cloud")
    void tenantCloudSourceKeepsApiProvidersWithoutLocalKeys() {
        ReflectionTestUtils.setField(service, "cloudLlmRuntimeAccess", cloudLlmRuntimeAccess);
        when(cloudLlmRuntimeAccess.isCloudSelected("tenant-cloud")).thenReturn(true);
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("openai", false), provider("local-openai-compatible", false), provider("codex", false)));
        when(credentialRepository.hasDbKey("codex")).thenReturn(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        Map<String, Object> result = service.getModelsForCategory(null, "tenant-cloud");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) result.get("providers");
        assertThat(providers).hasSize(1);
        Map<String, Object> openai = providers.get(0);
        assertThat(openai)
                .containsEntry("name", "openai")
                .containsEntry("configured", true)
                .containsEntry("source", "cloud")
                .containsEntry("providerKind", "cloud");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) openai.get("models");
        assertThat(models.get(0))
                .containsEntry("source", "cloud")
                .containsEntry("providerKind", "cloud");
        assertThat(result).containsEntry("llmSource", "CLOUD");
        verify(credentialRepository, never()).hasDbKey("openai");
        verify(credentialRepository, never()).hasDbKey("local-openai-compatible");
    }

    @Test
    @DisplayName("Cloud source KEEPS local CLI/bridge providers (personal, run locally) but never defaults to one")
    void cloudSourceKeepsBridgeProvidersButDefaultsToRelay() {
        ReflectionTestUtils.setField(service, "cloudLlmRuntimeAccess", cloudLlmRuntimeAccess);
        when(cloudLlmRuntimeAccess.isCloudSelected("tenant-cloud")).thenReturn(true);
        // claude-code is a CLI/bridge provider (configured so it survives the key + bridge
        // availability filters); deepseek is a relay-supported API provider. In Cloud mode the
        // cloud relays API providers ONLY, but the user's LOCAL CLI stays available - it runs on
        // the local bridge with the user's own auth and is NEVER relayed. So the picker must KEEP
        // the bridge (tagged providerKind=bridge, NOT cloud) while the DEFAULT lands on a
        // relay-supported API model. Cloud overrides the API source, not the machine's CLI.
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("claude-code", true), provider("deepseek", false)));
        lenient().when(credentialRepository.hasDbKey("claude-code")).thenReturn(false);
        lenient().when(credentialRepository.hasDbKey("deepseek")).thenReturn(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        Map<String, Object> result = service.getModelsForCategory(null, "tenant-cloud");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) result.get("providers");
        // Both present - the local CLI is NOT stripped in cloud mode.
        assertThat(providers).extracting(p -> p.get("name"))
                .containsExactlyInAnyOrder("claude-code", "deepseek");
        // The bridge keeps its bridge tag and is never marked cloud-served (it's local/personal).
        Map<String, Object> claudeCode = providers.stream()
                .filter(p -> "claude-code".equals(p.get("name"))).findFirst().orElseThrow();
        assertThat(claudeCode)
                .containsEntry("providerKind", "bridge")
                .doesNotContainEntry("source", "cloud");
        // The relay-supported API provider IS tagged cloud-served.
        Map<String, Object> deepseek = providers.stream()
                .filter(p -> "deepseek".equals(p.get("name"))).findFirst().orElseThrow();
        assertThat(deepseek)
                .containsEntry("providerKind", "cloud")
                .containsEntry("source", "cloud");
        // The overall default in Cloud mode must be the relay-supported API model, never the
        // bridge - a fresh chat starts on the cloud; the local CLI stays selectable but is not
        // the default (would otherwise route a brand-new cloud-mode chat to the local CLI).
        assertThat(result.get("defaultProvider")).isEqualTo("deepseek");
        assertThat(result).containsEntry("llmSource", "CLOUD");
    }

    @Test
    @DisplayName("strict mode (production default): an UNVERIFIABLE bridge drops CLI providers from the picker, keeps API")
    void strictModeDropsUnverifiableCliProviders() {
        // Production wiring: strict=true + blank bridge URL => availability can't be verified.
        // A CLI provider differs from an API provider - it must NOT be offered when we can't
        // confirm the CLI is installed + authed on the bridge, so claude-code is dropped while
        // the API provider (anthropic) stays. This is the bug the strict default fixes.
        ModelCatalogService strict = new ModelCatalogService(
                repository, categoryRepository, llmProviderFactory, credentialRepository,
                cachedRateLimitProvider, "", authPricingSyncClient, true);
        ReflectionTestUtils.setField(strict, "cloudLlmRuntimeAccess", cloudLlmRuntimeAccess);
        when(cloudLlmRuntimeAccess.isCloudSelected("tenant-byok")).thenReturn(false);
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("claude-code", true), provider("anthropic", true)));
        lenient().when(credentialRepository.hasDbKey(anyString())).thenReturn(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        Map<String, Object> result = strict.getModelsForCategory(null, "tenant-byok");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) result.get("providers");
        assertThat(providers).extracting(p -> p.get("name")).containsExactly("anthropic");
    }

    @Test
    @DisplayName("Cloud source with ONLY a local bridge: it stays selectable but there is no cloud default (relay default null)")
    void cloudSourceWithOnlyBridgeKeepsItButHasNoDefault() {
        ReflectionTestUtils.setField(service, "cloudLlmRuntimeAccess", cloudLlmRuntimeAccess);
        when(cloudLlmRuntimeAccess.isCloudSelected("tenant-cloud")).thenReturn(true);
        // The only configured provider is the local CLI (claude-code). In cloud mode it stays
        // in the picker (personal, runs locally), but since the cloud DEFAULT must be a
        // relay-supported API model and there is none, defaultProvider/defaultModel are null -
        // a fresh cloud-mode chat has nothing to auto-start on; the user picks the CLI explicitly.
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("claude-code", true)));
        lenient().when(credentialRepository.hasDbKey("claude-code")).thenReturn(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        Map<String, Object> result = service.getModelsForCategory(null, "tenant-cloud");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) result.get("providers");
        assertThat(providers).extracting(p -> p.get("name")).containsExactly("claude-code");
        assertThat(providers.get(0)).containsEntry("providerKind", "bridge");
        assertThat(result.get("defaultProvider")).isNull();
        assertThat(result.get("defaultModel")).isNull();
        assertThat(result).containsEntry("llmSource", "CLOUD");
    }

    @Test
    @DisplayName("tenant API-keys source keeps strict local provider filtering")
    void tenantByokSourceKeepsStrictLocalProviderFiltering() {
        ReflectionTestUtils.setField(service, "cloudLlmRuntimeAccess", cloudLlmRuntimeAccess);
        when(cloudLlmRuntimeAccess.isCloudSelected("tenant-byok")).thenReturn(false);
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("anthropic", true), provider("openai", false)));
        lenient().when(credentialRepository.hasDbKey("anthropic")).thenReturn(false);
        when(credentialRepository.hasDbKey("openai")).thenReturn(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        Map<String, Object> result = service.getModelsForCategory(null, "tenant-byok");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) result.get("providers");
        assertThat(providers).extracting(p -> p.get("name")).containsExactly("anthropic");
        assertThat(providers.get(0)).doesNotContainKey("source");
        assertThat(result).containsEntry("llmSource", "BYOK");
    }

    @Test
    @DisplayName("public catalog keeps keyless API providers while runtime picker stays strict")
    void publicCatalogKeepsKeylessApiProviders() {
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenAnswer(inv -> adminBase(provider("openai", false)));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        Map<String, Object> publicResult = service.getPublicModelsForCategory(null);
        Map<String, Object> runtimeResult = service.getModelsForCategory(null, "tenant-byok");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> publicProviders = (List<Map<String, Object>>) publicResult.get("providers");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runtimeProviders = (List<Map<String, Object>>) runtimeResult.get("providers");

        assertThat(publicProviders).extracting(p -> p.get("name")).containsExactly("openai");
        assertThat(runtimeProviders).isEmpty();
    }

    @Test
    @DisplayName("admin panel: a configured provider is shown")
    void adminPanelShowsConfiguredProvider() {
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("anthropic", true)));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("id")).isEqualTo("anthropic-model");
    }

    @Test
    @DisplayName("admin panel (BYOK/cloud-prod) SHOWS a keyless provider, annotated available=false (rank/price before the key exists)")
    void adminPanelShowsKeylessProviderAnnotatedUnavailableInByok() {
        // configured=false and no DB key: the admin Models panel lists the full
        // catalog so the model can be ranked/priced, and marks it available=false
        // so the UI can badge "not configured". The PICKER still hides it.
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("openai", false)));
        when(credentialRepository.hasDbKey("openai")).thenReturn(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();
        assertThat(result).extracting(m -> m.get("id")).containsExactly("openai-model");
        assertThat(result.get(0)).containsEntry("available", false);
        verify(credentialRepository).hasDbKey("openai");
    }

    @Test
    @DisplayName("admin panel (BYOK/cloud-prod) shows configured AND keyless side by side, each annotated by availability")
    void adminPanelShowsConfiguredAndKeylessAnnotated() {
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("anthropic", true), provider("zai", false)));
        when(credentialRepository.hasDbKey("anthropic")).thenReturn(false);
        when(credentialRepository.hasDbKey("zai")).thenReturn(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();
        assertThat(result).extracting(m -> m.get("id"))
                .containsExactlyInAnyOrder("anthropic-model", "zai-model");
        Map<String, Object> anthropic = result.stream()
                .filter(m -> "anthropic-model".equals(m.get("id"))).findFirst().orElseThrow();
        Map<String, Object> zai = result.stream()
                .filter(m -> "zai-model".equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(anthropic).containsEntry("available", true);   // env key
        assertThat(zai).containsEntry("available", false);        // no key
    }

    @Test
    @DisplayName("admin panel availability flag: env key OR DB key => available=true, neither => available=false (ALL shown)")
    void adminPanelAvailabilityFlagRespectsEnvAndDbKeys() {
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(
                        provider("anthropic", true),    // env key
                        provider("zai", false),          // DB key only
                        provider("openai", true),        // env key
                        provider("cohere", false)         // neither - shown, available=false
                ));
        // hasDbKey is evaluated for every provider (eager, not short-circuited
        // on configured), so stub all four names.
        when(credentialRepository.hasDbKey("anthropic")).thenReturn(false);
        when(credentialRepository.hasDbKey("zai")).thenReturn(true);
        when(credentialRepository.hasDbKey("openai")).thenReturn(false);
        when(credentialRepository.hasDbKey("cohere")).thenReturn(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();
        // ALL shown now; the availability flag distinguishes them.
        assertThat(result).extracting(m -> m.get("id"))
                .containsExactlyInAnyOrder("anthropic-model", "zai-model", "openai-model", "cohere-model");
        Map<String, Boolean> avail = new HashMap<>();
        for (Map<String, Object> m : result) avail.put((String) m.get("id"), (Boolean) m.get("available"));
        assertThat(avail.get("anthropic-model")).isTrue();  // env key
        assertThat(avail.get("zai-model")).isTrue();        // DB key
        assertThat(avail.get("openai-model")).isTrue();     // env key
        assertThat(avail.get("cohere-model")).isFalse();    // neither
    }

    @Test
    @DisplayName("admin cloud-connect KEEPS an unconfigured relay-supported API provider (cloud account is the source)")
    void adminPanelCloudConnectKeepsUnconfiguredRelayProvider() {
        ReflectionTestUtils.setField(service, "cloudLlmRuntimeAccess", cloudLlmRuntimeAccess);
        when(cloudLlmRuntimeAccess.isCloudSelected("tenant-cloud")).thenReturn(true);
        // openai is relay-supported and has NO local key. In cloud-connect the
        // admin Models panel keeps it (the bound cloud account executes it), so
        // it is listed without a key - never consulting hasDbKey for it.
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("openai", false)));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList(null, "tenant-cloud");
        assertThat(result).extracting(m -> m.get("id")).containsExactly("openai-model");
        verify(credentialRepository, never()).hasDbKey("openai");
    }

    @Test
    @DisplayName("admin cloud-connect SHOWS a non-relay keyless provider annotated available=false (relay can't run it)")
    void adminPanelCloudConnectShowsNonRelayProviderUnavailable() {
        ReflectionTestUtils.setField(service, "cloudLlmRuntimeAccess", cloudLlmRuntimeAccess);
        when(cloudLlmRuntimeAccess.isCloudSelected("tenant-cloud")).thenReturn(true);
        // "local-openai-compatible" is NOT in CloudRelaySupport and has no key,
        // so the relay can't run it - but the admin panel still lists it for
        // ranking, marked available=false. openai IS relay-supported (available).
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("openai", false), provider("local-openai-compatible", false)));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList(null, "tenant-cloud");
        assertThat(result).extracting(m -> m.get("id"))
                .containsExactlyInAnyOrder("openai-model", "local-openai-compatible-model");
        Map<String, Object> openai = result.stream()
                .filter(m -> "openai-model".equals(m.get("id"))).findFirst().orElseThrow();
        Map<String, Object> local = result.stream()
                .filter(m -> "local-openai-compatible-model".equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(openai).containsEntry("available", true);   // relay-supported
        assertThat(local).containsEntry("available", false);   // not relay-supported, no key
    }

    @Test
    @DisplayName("admin image_generation tab (BYOK) SHOWS a keyless provider's image-gen model, annotated available=false")
    void adminImageGenTabShowsKeylessProviderUnavailable() {
        // openai has NO key (configured=false, no DB key). The panel lists the
        // full catalog, so the mode filter keeps the image model and it is shown
        // for ranking, annotated available=false.
        Map<String, Object> openai = new LinkedHashMap<>();
        openai.put("name", "openai");
        openai.put("configured", false);
        openai.put("models", new ArrayList<>(List.of(
                modelWithMode("gpt-image-1.5", "image"),
                modelWithMode("gpt-5", "chat"))));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(adminBase(openai));
        when(credentialRepository.hasDbKey("openai")).thenReturn(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList("image_generation");
        // Only the image-gen model survives the mode filter; shown, unavailable.
        assertThat(result).extracting(m -> m.get("id")).containsExactly("gpt-image-1.5");
        assertThat(result.get(0)).containsEntry("available", false);
    }

    @Test
    @DisplayName("admin panel lists a bridge provider even with NO key and an unreachable host (admins must always see/configure CLIs)")
    void adminPanelListsBridgeProviderWithoutKey() {
        // claude-code is a bridge; a default-enabled bridge stub reports
        // configured=true, so it survives the BYOK key filter without any env/DB
        // key. The admin keeps it (annotated, never dropped) - unlike the picker
        // which hard-filters an unavailable CLI. bridgeUrl is blank here, so
        // availability is unknown (null) but the row is still present.
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("claude-code", true)));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();
        assertThat(result).extracting(m -> m.get("id")).containsExactly("claude-code-model");
        assertThat(result.get(0))
                .containsEntry("providerKind", "bridge")
                .containsKey("bridgeAvailable");
    }

    @Test
    @DisplayName("DIVERGENCE (new): the SAME keyless provider is SHOWN in the admin panel (available=false) but DROPPED from the picker")
    void adminShowsWhatPickerDropsForKeylessProvider() {
        // Fresh base per call: the picker path mutates the provider list (removeIf).
        when(llmProviderFactory.getAllModelsInfoAdmin())
                .thenAnswer(inv -> adminBase(provider("openrouter", false)));
        lenient().when(credentialRepository.hasDbKey("openrouter")).thenReturn(false);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> admin = service.getEffectiveModelList();
        Map<String, Object> picker = service.getModelsForCategory(null, "tenant-byok");

        // Admin: shown, marked unavailable so the UI can badge "not configured".
        assertThat(admin).extracting(m -> m.get("id")).containsExactly("openrouter-model");
        assertThat(admin.get(0)).containsEntry("available", false);
        // Picker: dropped entirely ("no provider, no model").
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pickerProviders = (List<Map<String, Object>>) picker.get("providers");
        assertThat(pickerProviders).isEmpty();
    }

    @Test
    @DisplayName("CE (auth.mode=embedded) HIDES the openrouter aggregator and cohere; keeps every other provider")
    void ceEmbeddedModeHidesOpenRouterAndCohere() {
        // A self-hosted install must not surface the multi-provider aggregator
        // (openrouter) or the curated-out cohere, even though their rows are
        // present (V112 seeded them / a key is configured). Everything else -
        // including the newly-added qwen - stays. Cloud is unaffected (see the
        // companion test with the default empty auth.mode).
        ReflectionTestUtils.setField(service, "authMode", "embedded");
        lenient().when(credentialRepository.hasDbKey(anyString())).thenReturn(false);
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(adminBase(
                provider("openai", true),
                provider("openrouter", true),
                provider("cohere", true),
                provider("qwen", true)));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        Map<String, Object> result = service.getModelsForCategory(null, "tenant-byok");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) result.get("providers");
        assertThat(providers).extracting(p -> p.get("name"))
                .containsExactlyInAnyOrder("openai", "qwen")
                .doesNotContain("openrouter", "cohere");
    }

    @Test
    @DisplayName("Cloud (default empty auth.mode) KEEPS openrouter and cohere - the CE block never fires")
    void cloudModeKeepsOpenRouterAndCohere() {
        // The service built in setUp() has the default empty authMode (Spring
        // @Value not processed in a unit test), i.e. cloud. filterCeBlockedProviders
        // must be a no-op so the aggregator stays available (relay fallback).
        lenient().when(credentialRepository.hasDbKey(anyString())).thenReturn(false);
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(adminBase(
                provider("openai", true),
                provider("openrouter", true),
                provider("cohere", true),
                provider("qwen", true)));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        Map<String, Object> result = service.getModelsForCategory(null, "tenant-byok");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) result.get("providers");
        assertThat(providers).extracting(p -> p.get("name"))
                .containsExactlyInAnyOrder("openai", "openrouter", "cohere", "qwen");
    }

    @Test
    @DisplayName("empty provider list returns empty result")
    void emptyProviderList() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", new ArrayList<>());
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(base);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();
        assertThat(result).isEmpty();
    }
}

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
 * <p>Two distinct contracts:
 * <ul>
 *   <li>Picker / runtime path ({@code getModelsForCategory}): strict - drops
 *       providers with neither an env key nor a DB key, except cloud-relay
 *       supported ones when the tenant's LLM source is CLOUD.</li>
 *   <li>Admin config path ({@code getEffectiveModelList}): shows the FULL
 *       catalog regardless of keys or cloud/CE mode, so the Models panel can
 *       list / rank / price every model on every category tab before any key
 *       is configured.</li>
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
    @DisplayName("admin panel shows a provider with NO key (env or DB) - full catalog before keys are configured")
    void adminPanelShowsProviderWithoutAnyKey() {
        // configured=false and no DB key: the runtime/picker path drops this
        // provider, but the admin Models panel MUST list it so the admin can
        // rank / price / enable it BEFORE adding a key. getEffectiveModelList no
        // longer consults the key filter, so hasDbKey is never called here.
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("openai", false)));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("id")).isEqualTo("openai-model");
        verify(credentialRepository, never()).hasDbKey(anyString());
    }

    @Test
    @DisplayName("admin panel shows configured AND unconfigured providers side by side (was: unconfigured removed)")
    void adminPanelShowsConfiguredAndUnconfiguredProviders() {
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(provider("anthropic", true), provider("zai", false)));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();
        // Pre-change this returned only "anthropic-model"; the keyless "zai" is now visible too.
        assertThat(result).extracting(m -> m.get("id"))
                .containsExactlyInAnyOrder("anthropic-model", "zai-model");
        verify(credentialRepository, never()).hasDbKey(anyString());
    }

    @Test
    @DisplayName("admin panel shows EVERY provider regardless of key config (env-only, db-only, both, neither)")
    void adminPanelShowsEveryProviderRegardlessOfKeyConfig() {
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(
                adminBase(
                        provider("anthropic", true),   // env key
                        provider("zai", false),         // would be DB-key-only at runtime
                        provider("openai", true),       // both
                        provider("cohere", false)        // neither - previously hidden
                ));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList();
        // All four show now - including "cohere" which had neither key (excluded pre-change).
        assertThat(result).extracting(m -> m.get("id"))
                .containsExactlyInAnyOrder("anthropic-model", "zai-model", "openai-model", "cohere-model");
        verify(credentialRepository, never()).hasDbKey(anyString());
    }

    @Test
    @DisplayName("admin image_generation tab shows a keyless provider's image models; the mode filter still hides its chat models")
    void adminImageGenTabShowsKeylessProviderImageModelsAndModeFilters() {
        // openai has NO key (configured=false, no DB key) and ships both an image
        // model and a chat model. On the image_generation tab the admin must see
        // the image model (full catalog, every category tab) while the category
        // mode filter still drops the chat-mode row (not callable by the image
        // tool). Proves the full-catalog change holds on a NON-null category tab
        // without breaking mode-scoping. categoryRepository.findByCategory returns
        // an empty sidecar by default (no overlay), so no stub is needed.
        Map<String, Object> openai = new LinkedHashMap<>();
        openai.put("name", "openai");
        openai.put("configured", false);
        openai.put("models", new ArrayList<>(List.of(
                modelWithMode("gpt-image-1.5", "image"),
                modelWithMode("gpt-5", "chat"))));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(adminBase(openai));
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<Map<String, Object>> result = service.getEffectiveModelList("image_generation");

        assertThat(result).extracting(m -> m.get("id")).containsExactly("gpt-image-1.5");
        verify(credentialRepository, never()).hasDbKey(anyString());
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

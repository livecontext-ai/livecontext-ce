package com.apimarketplace.agent.service;

import com.apimarketplace.agent.bridge.BridgeAllowlist;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudRelaySupport;
import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.BridgeProviders;
import com.apimarketplace.agent.domain.ModelCategory;
import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelCategorySettingsId;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.domain.ReasoningEffort;
import com.apimarketplace.agent.domain.ReasoningEffortResolver;
import com.apimarketplace.agent.factory.BridgeAvailabilityFilter;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.domain.ModelProviderSettingsEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.repository.ModelProviderSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ModelCatalogService {

    private final ModelConfigOverrideRepository repository;
    private final ModelCategorySettingsRepository categoryRepository;
    private final LLMProviderFactory llmProviderFactory;
    private final LlmCredentialRepository credentialRepository;
    private final CachedModelRateLimitProvider cachedRateLimitProvider;
    private final String bridgeUrl;
    private final AuthPricingSyncClient authPricingSyncClient;
    @Autowired(required = false)
    private CloudLlmRuntimeAccess cloudLlmRuntimeAccess;

    /**
     * {@code auth.mode}: {@code "embedded"} ⇒ CE (self-hosted) install ⇒ the
     * catalog HIDES {@link com.apimarketplace.agent.cloud.CeBlockedProviders}
     * (openrouter/cohere) even if seeded rows exist in {@code model_config_overrides}.
     * Empty (cloud / tests) ⇒ no CE filtering. Field injection so neither
     * constructor signature changes; tests keep the default "".
     */
    @Value("${auth.mode:}")
    private String authMode = "";

    /**
     * Shared filter (lives in shared-agent-lib so the CE monolith stub can
     * reuse the same code path). Lazily-initialised on first use because
     * `bridgeUrl` is read from config - we capture the value at construction.
     */
    private final BridgeAvailabilityFilter bridgeAvailabilityFilter;
    private final ModelProviderSettingsRepository providerSettingsRepository;

    /**
     * Production constructor (Spring). {@code models.bridge-availability.strict}
     * defaults to true so the user-facing picker HIDES a CLI provider whose
     * availability cannot be verified (bridge unreachable / URL unset / CLI not
     * authed) - unlike an API model, a CLI model must not be offered unless we
     * can confirm it actually runs. Set the property to false to restore the
     * legacy lenient behaviour (keep CLI providers when the bridge is down).
     */
    @Autowired
    public ModelCatalogService(ModelConfigOverrideRepository repository,
                                      ModelCategorySettingsRepository categoryRepository,
                                      LLMProviderFactory llmProviderFactory,
                                      LlmCredentialRepository credentialRepository,
                                      CachedModelRateLimitProvider cachedRateLimitProvider,
                                      @Value("${conversation.bridge.url:}") String bridgeUrl,
                                      AuthPricingSyncClient authPricingSyncClient,
                                      @Value("${models.bridge-availability.strict:true}") boolean bridgeAvailabilityStrict,
                                      ModelProviderSettingsRepository providerSettingsRepository) {
        this.providerSettingsRepository = providerSettingsRepository;
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.llmProviderFactory = llmProviderFactory;
        this.credentialRepository = credentialRepository;
        this.cachedRateLimitProvider = cachedRateLimitProvider;
        this.bridgeUrl = bridgeUrl;
        this.authPricingSyncClient = authPricingSyncClient;
        this.bridgeAvailabilityFilter = new BridgeAvailabilityFilter(bridgeUrl, bridgeAvailabilityStrict);
    }

    /**
     * Legacy 7-arg constructor retained for unit tests that pre-seed bridge
     * providers against a blank bridge URL. It builds the filter in LENIENT
     * mode (strict=false) so those tests keep exercising ranking/tagging/cloud
     * logic without the strict availability drop. Production always uses the
     * {@link Autowired} strict constructor above; strict behaviour is covered
     * by dedicated BridgeAvailabilityFilter tests and a strict-mode test.
     */
    public ModelCatalogService(ModelConfigOverrideRepository repository,
                                      ModelCategorySettingsRepository categoryRepository,
                                      LLMProviderFactory llmProviderFactory,
                                      LlmCredentialRepository credentialRepository,
                                      CachedModelRateLimitProvider cachedRateLimitProvider,
                                      String bridgeUrl,
                                      AuthPricingSyncClient authPricingSyncClient) {
        // null provider settings: no provider is switched off, which is the state every one of
        // these tests was written against. The behaviour has its own tests, on the constructor
        // production uses.
        this(repository, categoryRepository, llmProviderFactory, credentialRepository,
                cachedRateLimitProvider, bridgeUrl, authPricingSyncClient, false, null);
    }

    /**
     * Get all models info with DB overrides applied on top of yml defaults.
     * Bridge providers (claude-code/codex/gemini-cli/mistral-vibe) whose CLI
     * binary isn't installed on the bridge host are filtered out so the
     * model picker only advertises providers the user can actually run.
     */
    public Map<String, Object> getModelsWithOverrides() {
        return getModelsForCategory(null, null);
    }

    /**
     * Category-aware variant. When {@code category} is non-null, the
     * per-category sidecar (V156 - {@code agent.model_category_settings}) is
     * applied as an overlay on top of the global {@code ranking} / {@code enabled}
     * fields BEFORE the standard merge pipeline runs. When the sidecar has no
     * row for a given model, the global values stand (legacy behaviour).
     *
     * <p>Categories supported: {@code chat}, {@code browser_agent}, the five
     * retired {@code <format>_generation} keys (answered for compatibility, no
     * screen sends them), plus any future category that matches the V156 shape
     * CHECK ({@link ModelCategory#isValidShape(String)}).
     *
     * <p>Resolution rule:
     * <pre>
     *   sidecar present + sidecar.enabled = false   → model removed
     *   sidecar present + sidecar.rank set          → ranking := sidecar.rank
     *   sidecar absent                              → fall back to global
     * </pre>
     *
     * <p>When {@code category} is null, this is a pure passthrough of the
     * legacy {@link #getModelsWithOverrides()} pipeline - no sidecar lookup.
     */
    public Map<String, Object> getModelsForCategory(String category) {
        return getModelsForCategory(category, null);
    }

    public Map<String, Object> getModelsForCategory(String category, String tenantId) {
        return getModelsForCategory(category, tenantId, false);
    }

    public Map<String, Object> getPublicModelsForCategory(String category) {
        return getModelsForCategory(category, null, true);
    }

    /**
     * The API providers this install exposes at least one model for, whether or not a key
     * exists for them yet, lower-cased and never including a CLI bridge.
     *
     * <p>Answers a question the picker catalogue cannot. That one DROPS every provider the
     * caller holds no key for, so a user without a Mistral key is told nothing about Mistral,
     * and the own-keys panel would otherwise have to invite a key from a hardcoded list, for a
     * provider that may serve nothing once the key is saved. Here the availability filter is
     * skipped on purpose and only the admin's exposure decision is left: a provider whose
     * models are all switched off vanishes from that panel exactly as it has already vanished
     * from every picker, and the user is never told it exists.
     *
     * <p>CLI bridges are excluded unconditionally, on every edition. They hold no key a user
     * could paste, so naming one here would be an invitation to nothing, and on the hosted
     * product they are the operator's subscription and not a user's business at all.
     *
     * <p>What is left is not a secret: it is the shape of the price list, which any signed-in
     * user already reads for the providers they do hold a key for.
     */
    public List<String> providersOfferingModels() {
        Map<String, Object> catalog = getPublicModelsForCategory(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) catalog.get("providers");
        if (providers == null) {
            return List.of();
        }
        List<String> offering = new ArrayList<>();
        for (Map<String, Object> provider : providers) {
            String name = (String) provider.get("name");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> models = (List<Map<String, Object>>) provider.get("models");
            if (name == null || name.isBlank() || models == null || models.isEmpty()) {
                continue;
            }
            if (isBridgeProviderName(name)) {
                continue;
            }
            offering.add(name.toLowerCase(Locale.ROOT));
        }
        return offering;
    }

    private Map<String, Object> getModelsForCategory(String category, String tenantId, boolean includeUnconfigured) {
        Map<String, Object> base = getAvailableProvidersBase(tenantId, includeUnconfigured);
        filterUnavailableBridgeProviders(base);
        // Read once for the whole build: the base list below and the custom injection further
        // down both need it, and this path already walks every override row.
        Set<String> disabledForThisBuild = disabledProviders();
        filterDisabledProviders(base, disabledForThisBuild);
        // V156/V158: filter the YAML/Bridge base catalog to only models whose
        // mode matches the category contract, so chat / browser_agent completion
        // lists do NOT show image-gen rows. (A <format>_generation category admits
        // only that mode, which is what keeps the two apart.) The null/global path here
        // backs the main chat picker (ChatControllerV3 → getModelsInfo(null)),
        // the flat LLM catalog (listAvailableModels) and the default-model pick;
        // it must apply the SAME mode-filter as the explicit 'chat' category,
        // else mode='image' rows leak into the chat completion list. Only the
        // mode-filter is shared - the per-category sidecar OVERLAY stays gated to
        // a non-null category (the null path keeps the legacy global
        // ranking/enabled, with no sidecar lookup).
        String modeKey = modeFilterKey(category);
        filterProvidersByCategoryMode(base, modeKey);
        List<ModelConfigOverrideEntity> overrides = repository.findAllByOrderByRankingAsc();
        // Drop overrides whose mode is incompatible with the (effective) category
        // BEFORE overlay, so the apply/inject loop never surfaces an image row in
        // a chat completion list when no sidecar exists for it.
        overrides = overrides.stream()
                .filter(o -> ModelCategory.acceptsMode(modeKey, o.getMode()))
                .toList();
        if (category != null) {
            overrides = applyCategoryOverlay(overrides, category);
        }

        // Index overrides by provider+modelId. Empty when no rankings have
        // been saved yet (fresh CE / tenant that hasn't dragged anything in
        // /settings/ai-providers). The catalog still needs the bridge tagging
        // and the global-default recalculation below - DO NOT early-return on
        // empty overrides, otherwise BrowserAgentModule sees an unmarked
        // catalog and silently misroutes bridge defaults to direct-API.
        Map<String, ModelConfigOverrideEntity> overrideMap = new HashMap<>();
        for (ModelConfigOverrideEntity o : overrides) {
            overrideMap.put(o.getProvider() + ":" + o.getModelId(), o);
        }

        // Surface the bridge URL on the catalog top-level FIRST so cross-service
        // consumers (e.g. BrowserAgentModule in orchestrator-service) see it
        // even on the (defensive) providers==null path. Single source of truth
        // - consumers don't re-read conversation.bridge.url from their own
        // Spring config.
        if (bridgeUrl != null && !bridgeUrl.isBlank()) {
            base.put("bridgeUrl", bridgeUrl);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) base.get("providers");
        if (providers == null) return base;

        // Collect rows to inject as standalone models: every DB row whose
        // (provider, model_id) is NOT already present in the YAML-derived
        // base. This covers (a) is_custom=true rows (CE-local additions) and
        // (b) rows fetched by ModelCatalogSyncService from LiteLLM/OpenRouter
        // which didn't exist in application.yml. Deprecated rows are skipped
        // so the picker doesn't advertise EOL models.
        Set<String> yamlKeys = new HashSet<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> yamlProviders = (List<Map<String, Object>>) base.get("providers");
        if (yamlProviders != null) {
            for (Map<String, Object> p : yamlProviders) {
                String pname = (String) p.get("name");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> yamlModels = (List<Map<String, Object>>) p.get("models");
                if (yamlModels == null) continue;
                for (Map<String, Object> ym : yamlModels) {
                    yamlKeys.add(pname + ":" + ym.get("id"));
                }
            }
        }

        Map<String, List<ModelConfigOverrideEntity>> customByProvider = new HashMap<>();
        for (ModelConfigOverrideEntity o : overrides) {
            if (o.getDeprecatedAt() != null) continue;
            boolean notInYaml = !yamlKeys.contains(o.getProvider() + ":" + o.getModelId());
            if (o.isCustom() || notInYaml) {
                customByProvider.computeIfAbsent(o.getProvider(), k -> new ArrayList<>()).add(o);
            }
        }

        for (Map<String, Object> providerInfo : providers) {
            String providerName = (String) providerInfo.get("name");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> models = (List<Map<String, Object>>) providerInfo.get("models");
            if (models == null) continue;

            // Apply overrides to existing models
            Iterator<Map<String, Object>> it = models.iterator();
            while (it.hasNext()) {
                Map<String, Object> model = it.next();
                String modelId = (String) model.get("id");
                ModelConfigOverrideEntity override = overrideMap.get(providerName + ":" + modelId);
                if (override != null) {
                    // If explicitly disabled, remove from list
                    if (Boolean.FALSE.equals(override.getEnabled())) {
                        it.remove();
                        continue;
                    }
                    applyOverride(model, override);
                }
            }

            // Inject custom models for this provider
            List<ModelConfigOverrideEntity> customs = customByProvider.get(providerName);
            if (customs != null) {
                for (ModelConfigOverrideEntity custom : customs) {
                    if (Boolean.FALSE.equals(custom.getEnabled())) continue;
                    models.add(buildModelInfo(custom));
                }
            }

            // Re-sort by displayOrder
            models.sort(Comparator.comparingInt(m -> (int) ((Map<String, Object>) m).getOrDefault("displayOrder", 999)));
        }

        // Inject providers for is_custom=true rows whose provider isn't
        // declared in YAML. Only true customs (admin-added local servers)
        // get their own provider slot; sync-sourced rows under an absent
        // provider are silently held back because the API key / bridge is
        // not configured - they'll reappear once availability is restored.
        Set<String> existingProviders = new HashSet<>();
        for (Map<String, Object> p : providers) {
            existingProviders.add((String) p.get("name"));
        }
        Set<String> disabledForInjection = disabledForThisBuild;
        for (var entry : customByProvider.entrySet()) {
            if (existingProviders.contains(entry.getKey())) continue;
            // A provider switched off must not come BACK through this door. The filter above
            // runs on the YAML/base list, which a provider whose only rows are custom never
            // appears in, so without this the switch was a no-op for exactly those providers
            // and silently partial for any other that carried one custom row.
            if (entry.getKey() != null
                    && disabledForInjection.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<ModelConfigOverrideEntity> trueCustoms = entry.getValue().stream()
                    .filter(ModelConfigOverrideEntity::isCustom)
                    .toList();
            if (trueCustoms.isEmpty()) continue;
            Map<String, Object> newProvider = new HashMap<>();
            newProvider.put("name", entry.getKey());
            newProvider.put("defaultModel", trueCustoms.get(0).getModelId());
            newProvider.put("supportsStreaming", true);
            newProvider.put("supportsToolCalling", true);
            newProvider.put("displayOrder", 99);
            List<Map<String, Object>> models = new ArrayList<>();
            for (ModelConfigOverrideEntity c : trueCustoms) {
                if (!Boolean.FALSE.equals(c.getEnabled())) {
                    models.add(buildModelInfo(c));
                }
            }
            newProvider.put("models", models);
            providers.add(newProvider);
        }

        // CE boundary: on a self-hosted (auth.mode=embedded) install, drop the
        // multi-provider aggregator (openrouter) and the curated-out cohere
        // provider from the catalog - even if V112 seeded their rows into
        // model_config_overrides (the DB-inject loop above would otherwise
        // surface them). Cloud (non-embedded) keeps every provider, openrouter
        // included, as a relay fallback. Applied here so it covers YAML-derived
        // AND DB-injected provider entries in one place.
        filterCeBlockedProviders(providers);

        // Tag bridge providers (provider-LEVEL only - see markBridgeProviders
        // javadoc) so consumers (BrowserAgentModule, frontend model picker, …)
        // don't need to know the bridge name list. BridgeAvailabilityFilter
        // already accepted only bridges whose CLI is installed; here we attach
        // `providerKind="bridge"` on the provider entry so callers route
        // bridge models through the bridge runtime path (with `provider_kind`
        // and `bridge_url`) instead of the direct-API path. Direct API
        // providers stay without the field - absence == direct API.
        markBridgeProviders(providers);
        if (isCloudSelected(tenantId)) {
            // CE CLOUD mode: the bound cloud account is the source for API models ONLY.
            // markCloudProviders() tags the relay-supported API providers as cloud-served so
            // they're usable without a local key (inference is relayed & billed to the cloud
            // account). CLI/bridge providers (claude-code, codex, gemini-cli, mistral-vibe) are
            // PERSONAL and LOCAL: they run on the user's own bridge with their own CLI auth and
            // are NEVER relayed to the cloud - the relay hard-rejects them (CloudLlmRelayController
            // .validate) and RuntimeLlmProviderResolver short-circuits them to the local provider
            // regardless of llmSource. They therefore STAY in the picker so the user keeps access
            // to their local CLI even while API calls go to the cloud (cloud overrides only the
            // API source, it does not take over the machine's CLI). We do NOT drop them here.
            // The cloud DEFAULT, however, must be a relay-supported API model - a fresh chat in
            // cloud mode starts on the cloud, not the local CLI - which recalculateDefaults()
            // enforces off base.llmSource below (the CLI stays selectable, just not the default).
            markCloudProviders(providers);
            base.put("llmSource", "CLOUD");
        } else {
            base.put("llmSource", "BYOK");
        }

        // Recalculate defaultModel/defaultProvider from the final filtered list.
        // Picks the model with the LOWEST global displayOrder across every
        // provider - i.e. the user-visible #1 in the admin's drag-and-drop
        // ranking, NOT just the first model of the first provider.
        // When category is non-null the list has already been overlaid with
        // the V156 sidecar; defaultProvider/defaultModel + defaultDirect* are
        // therefore already category-scoped without further work.
        recalculateDefaults(base);

        // V156: echo the requested category back so consumers know the scope of
        // the response without having to track it themselves. Absent on the
        // legacy global catalog (category=null) for backward compatibility.
        if (category != null) {
            base.put("category", category);
        }

        return base;
    }

    /**
     * Tag every provider whose name appears in
     * {@link BridgeAvailabilityFilter#BRIDGE_PROVIDER_TO_CLI_ID} with
     * {@code providerKind="bridge"} at the PROVIDER LEVEL only. The list of
     * bridge names is the single source of truth - adding a new bridge requires
     * editing one map. Models nested inside a bridge provider do NOT get an
     * individual {@code providerKind} field; consumers needing to know whether
     * a model is a bridge must look at the parent provider's tag (mirrors how
     * {@code BrowserAgentModule.isBridgeProviderInCatalog} resolves it).
     */
    static void markBridgeProviders(List<Map<String, Object>> providers) {
        if (providers == null) return;
        Set<String> bridges = BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.keySet();
        for (Map<String, Object> provider : providers) {
            Object nameObj = provider.get("name");
            if (nameObj instanceof String name && bridges.contains(name.toLowerCase())) {
                provider.put("providerKind", "bridge");
                // Propagate down to every nested model row so the typed
                // AIModel surface on the frontend can render the bridge badge
                // per option (the picker iterates models, not providers, when
                // they're flattened by useModels). Models that already carry
                // a providerKind from their override row keep it - admins
                // can't switch a row to a different kind, but defensive
                // doesn't-overwrite semantics keep the merge predictable.
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> models = (List<Map<String, Object>>) provider.get("models");
                if (models != null) {
                    for (Map<String, Object> model : models) {
                        if (!model.containsKey("providerKind")) {
                            model.put("providerKind", "bridge");
                        }
                    }
                }
            }
        }
    }

    /**
     * True iff this install is CE ({@code auth.mode=embedded}) AND {@code provider}
     * is one of the CE-blocked providers (openrouter, cohere). No-op in cloud
     * (empty/keycloak {@code authMode}). Single choke point reused by both the
     * picker path ({@link #filterCeBlockedProviders}) and the admin path
     * ({@link #getEffectiveModelList}) so they never disagree in CE.
     */
    private boolean isCeBlockedProvider(String provider) {
        return com.apimarketplace.agent.cloud.CeBlockedProviders.isBlockedInMode(authMode, provider);
    }

    /**
     * Remove CE-blocked providers (openrouter, cohere) from the catalog when this
     * install runs in CE mode ({@code auth.mode=embedded}). No-op in cloud, where
     * {@code authMode} is empty. Mutates {@code providers} in place.
     */
    void filterCeBlockedProviders(List<Map<String, Object>> providers) {
        if (providers == null || providers.isEmpty()) return;
        providers.removeIf(p -> {
            boolean blocked = p.get("name") instanceof String name && isCeBlockedProvider(name);
            if (blocked) {
                log.debug("CE catalog: hiding blocked provider '{}'", p.get("name"));
            }
            return blocked;
        });
    }


    @Autowired(required = false)
    private com.apimarketplace.common.web.AppEditionProvider appEditionProvider;

    /** Visible for tests. */
    public void setAppEditionProvider(com.apimarketplace.common.web.AppEditionProvider provider) {
        this.appEditionProvider = provider;
    }

    /**
     * Whether this install runs its own CLI. Resolved from {@code AppEditionProvider}, which
     * knows the difference between CE_FREE, SELF_HOSTED_ENTERPRISE (self-hosted, but running
     * keycloak) and the hosted product - a difference {@code auth.mode} alone cannot express, and
     * getting it wrong would ban an enterprise operator from the CLI they installed themselves.
     *
     * <p>The bean comes from common-lib auto-configuration and is present in every service. It is
     * optional only so test slices need not raise it; absent, the check falls back to
     * {@code auth.mode=embedded}, which is right for the CE monolith and is the value every
     * pre-existing test already sets.
     */
    private boolean isSelfHostedInstall() {
        if (appEditionProvider != null) {
            return appEditionProvider.isSelfHosted();
        }
        return "embedded".equalsIgnoreCase(authMode == null ? "" : authMode.trim());
    }

    /** True on the hosted product, i.e. not a self-hosted install of any tier. */
    private boolean isCloudEdition() {
        return !isSelfHostedInstall();
    }

    /**
     * CLOUD-only: strip the CLI bridges from a catalog served as a declared PUBLIC read.
     *
     * <p>Narrow on purpose, and the narrowness is the correction of an earlier, broader version of
     * this method that filtered every caller. {@code /api/internal/agent/models} is SHARED: the
     * nested shape it returns also feeds {@code ModelCatalogEnricher}, which rewrites the
     * {@code provider.enum} that {@code NodeParamsValidator} enforces at WRITE time, and it backs
     * {@code SmartDefaultsEngine}, {@code ChatDispatchService} and the cloud-only admin panel that
     * creates {@code agent.model_execution_links}. Filtering it for everyone made a classify node
     * on a bridge unsaveable and emptied the very panel that points a billed pair at a CLI, which
     * is the mechanism that makes hiding the bridges harmless in the first place.
     *
     * <p>A declared public read is none of those. It is the anonymous browser hitting
     * {@code /api/v3/chat/models}, which was advertising {@code claude-code} (9 models) and
     * {@code codex} (7) as {@code configured} to anyone who asked, along with the bridge host's
     * LAN address. The caller DECLARES it; this never infers it from a missing {@code X-User-ID},
     * because that header is equally absent on any internal call made off a request thread, and
     * inferring would make node validation depend on which thread asked. Nothing authenticated
     * changes here; per-surface hiding for signed-in users belongs in the picker, which already
     * has the granularity to exempt the admin surfaces that need the bridges.
     *
     * <p>No-op on CE, where a bridge is the self-hoster's own CLI under their own login.
     *
     * <p>{@code defaultProvider}/{@code defaultModel} are recomputed rather than left pointing at
     * a removed entry: a picker whose default names a provider absent from the list has no valid
     * selection at all. {@code bridgeUrl} goes with them, being an internal address that addresses
     * nothing once the bridges are gone.
     *
     * <p>Applied to an AUTHENTICATED read too since 2026-09-18, for any caller who is not a
     * platform admin: the CLI bridges are the operator's own subscription and must never be
     * named to an end user. Until then the authenticated payload carried them and only the
     * frontend hid them, so the guarantee lived in one React hook and any surface that forgot
     * it leaked the list. The admin panels that legitimately need the bridges
     * ({@code ModelExecutionLinksPanel}, {@code AddModelDialog}) are admin-only, so an admin
     * still receives the whole catalogue.
     *
     * @return the same map, mutated, for call-site chaining
     */
    public Map<String, Object> hideBridgeProviders(Map<String, Object> catalog) {
        if (catalog == null) {
            return null;
        }
        if (!isSelfHostedInstall() && catalog.containsKey("bridgeUrl")) {
            // FIRST, before any early return. bridgeUrl is written whenever conversation.bridge.url
            // is configured, independently of whether any provider list exists or any bridge
            // survived the availability filter - so both the providers==null path and the
            // "every CLI unverified, none listed" state were still publishing the host's LAN
            // address, exactly where nothing else would have triggered the removal.
            catalog = withoutBridgeUrl(catalog);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) catalog.get("providers");
        if (providers == null) {
            return catalog;
        }
        // A NEW list rather than removeIf on the caller's: this runs on whatever map the catalog
        // pipeline handed over, and an immutable one (List.of, or a future cached snapshot) would
        // throw UnsupportedOperationException from inside a read endpoint.
        List<Map<String, Object>> kept = providers.stream()
                .filter(p -> !(p.get("name") instanceof String name
                        && BridgeProviders.isHiddenFromUser(isSelfHostedInstall(), false, name)))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (kept.size() == providers.size()) {
            return catalog;
        }
        catalog.put("providers", kept);
        recalculateDefaults(catalog);
        log.debug("Public catalog: hid CLI bridge providers, default is now {}/{}",
                catalog.get("defaultProvider"), catalog.get("defaultModel"));
        return catalog;
    }

    /**
     * The catalogue minus {@code bridgeUrl}, in place when the map allows it and as a copy when it
     * does not. {@code Map.of} throws from {@code remove} unconditionally, and this class already
     * carries the scar of a 500 on this very endpoint from mutating an immutable map.
     */
    private static Map<String, Object> withoutBridgeUrl(Map<String, Object> catalog) {
        try {
            catalog.remove("bridgeUrl");
            return catalog;
        } catch (UnsupportedOperationException immutable) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(catalog);
            copy.remove("bridgeUrl");
            return copy;
        }
    }

    static void markCloudProviders(List<Map<String, Object>> providers) {
        if (providers == null) return;
        for (Map<String, Object> provider : providers) {
            Object nameObj = provider.get("name");
            if (!(nameObj instanceof String name)
                    || isBridgeProviderName(name)
                    || !CloudRelaySupport.isSupportedProvider(name)) {
                continue;
            }
            provider.put("configured", true);
            provider.put("source", "cloud");
            provider.put("providerKind", "cloud");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> models = (List<Map<String, Object>>) provider.get("models");
            if (models != null) {
                for (Map<String, Object> model : models) {
                    model.put("source", "cloud");
                    model.put("providerKind", "cloud");
                }
            }
        }
    }

    /**
     * After all filtering and overrides, recalculate defaultProvider and
     * defaultModel from the model with the LOWEST global displayOrder across
     * EVERY provider - i.e. the user-visible #1 in the admin's drag-and-drop
     * ranking (`/settings/ai-providers`, ModelManagementPanel.handleDragEnd
     * which assigns ranking=i+1 globally on reorder, persisted to
     * ModelConfigOverrideEntity.ranking and surfaced as the model-level
     * displayOrder in the catalog).
     *
     * <p>The historical bug was sorting by PROVIDER-level displayOrder first
     * (admin-yml-defined, NOT touched by the per-model UI ranking), then
     * picking that provider's first model. The frontend ranking is per-MODEL
     * and FLAT across providers - so the user could drag codex to the top of
     * the list and the backend would still pick anthropic's first model
     * because anthropic's PROVIDER displayOrder happened to be lower. This
     * implementation walks every (provider, model) pair and picks the one
     * with the lowest model-level displayOrder, matching {@link #listAvailableModels()}'s
     * global sort and what the UI model picker actually shows at position #1.
     */
    void recalculateDefaults(Map<String, Object> base) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) base.get("providers");
        if (providers == null || providers.isEmpty()) {
            // Both defaults set to null on empty catalog - symmetric shape so
            // consumers can rely on the keys always being present.
            base.put("defaultProvider", null);
            base.put("defaultModel", null);
            base.put("defaultDirectProvider", null);
            base.put("defaultDirectModel", null);
            return;
        }

        // Overall default = lowest global displayOrder. Used by chat / agent.create
        // which can drive full-session bridges (Claude Code / codex run an entire agent
        // loop per call). In CE CLOUD mode the default must be a relay-supported API model:
        // API calls go to the bound cloud account, so a fresh chat starts on a cloud-served
        // model rather than the user's LOCAL CLI - which stays in the picker and runs on the
        // local bridge (see the markCloudProviders branch in getModelsForCategory), but is not
        // the default. In BYOK mode any provider kind, including a bridge, may be the overall
        // default. cloudMode reads base.llmSource, set just above before this call; absent
        // (direct recalculateDefaults callers / tests) ⇒ legacy "any provider" behaviour.
        // The HOSTED product excludes bridges from the overall default for a second, independent
        // reason: there the four CLIs share ONE operator subscription, so making one of them the
        // pair every omitted model_provider resolves to is what silently put users on it. This is
        // the root of the production state - claude-code ranked first, so defaultProvider WAS
        // claude-code and anything that omitted a provider inherited it. It is edition, not
        // llmSource: a self-hosted install may legitimately default to the CLI it is running.
        boolean bridgeUnfitAsDefault = "CLOUD".equals(base.get("llmSource")) || isCloudEdition();
        applyBest(base, providers,
                bridgeUnfitAsDefault ? p -> !"bridge".equals(p.get("providerKind")) : p -> true,
                "defaultProvider", "defaultModel");

        // Direct-API default = lowest global displayOrder excluding bridges.
        // Used by browser_agent which needs per-step chat-completion calls
        // (browser-use itself runs an agent loop; bridges can't serve atomic
        // completions). Falls to null when ONLY bridges are configured.
        applyBest(base, providers, p -> !"bridge".equals(p.get("providerKind")),
            "defaultDirectProvider", "defaultDirectModel");
    }

    /**
     * Walk every {@code (provider, model)} pair matching {@code providerFilter},
     * pick the one with the lowest model-level {@code displayOrder} (matching
     * the global flat ranking persisted by the UI drag-and-drop), and write
     * its {@code (providerName, modelId)} into {@code base} under the supplied
     * keys. Writes {@code null} on both keys when no pair matches.
     *
     * <p>Models with a missing or non-numeric {@code displayOrder} are coerced
     * to {@code 999} (least preferred), matching the same convention as
     * {@link #listAvailableModels()} and the per-provider sort in
     * {@link #getModelsWithOverrides()}.
     */
    @SuppressWarnings("unchecked")
    private static void applyBest(Map<String, Object> base,
                                  List<Map<String, Object>> providers,
                                  java.util.function.Predicate<Map<String, Object>> providerFilter,
                                  String providerKey, String modelKey) {
        Map<String, Object> bestProvider = null;
        Map<String, Object> bestModel = null;
        int bestOrder = Integer.MAX_VALUE;
        for (Map<String, Object> provider : providers) {
            if (!providerFilter.test(provider)) continue;
            List<Map<String, Object>> models = (List<Map<String, Object>>) provider.get("models");
            if (models == null) continue;
            for (Map<String, Object> model : models) {
                int order = model.get("displayOrder") instanceof Number n ? n.intValue() : 999;
                if (order < bestOrder) {
                    bestOrder = order;
                    bestProvider = provider;
                    bestModel = model;
                }
            }
        }
        if (bestModel != null) {
            base.put(providerKey, bestProvider.get("name"));
            base.put(modelKey, bestModel.get("id"));
        } else {
            base.put(providerKey, null);
            base.put(modelKey, null);
        }
    }

    /**
     * Return the effective default model ID from the filtered provider catalog.
     * This is the first model of the first provider after all overrides and
     * bridge availability filtering have been applied.
     */
    public String getEffectiveDefaultModel() {
        Map<String, Object> data = getModelsWithOverrides();
        return (String) data.get("defaultModel");
    }

    /**
     * Return the effective default provider name from the filtered provider catalog.
     */
    public String getEffectiveDefaultProvider() {
        Map<String, Object> data = getModelsWithOverrides();
        return (String) data.get("defaultProvider");
    }

    /**
     * Flat catalog of available (provider, modelId) pairs.
     *
     * <p><strong>Scope:</strong> platform-wide, not tenant-scoped. The catalog
     * is controlled by the <em>platform admin</em> via {@code /settings/ai-providers}
     * (see {@link ModelConfigOverrideEntity} - no {@code tenant_id} column) and
     * shared across all tenants served by this deployment.
     *
     * <p>Single source of truth for "available model" - built on top of
     * {@link #getModelsWithOverrides()} so the picker UI, the agent tool's
     * {@code help} action, and the create/update validation path all agree
     * on the same filter. A model is available iff:
     * <ul>
     *   <li>its provider is configured (API key) or a reachable bridge CLI,</li>
     *   <li>its DB override (if any) does not have {@code enabled = false}.</li>
     * </ul>
     *
     * <p>The returned list preserves the display order from
     * {@code getModelsWithOverrides()} so the first entry per provider is the
     * recommended default. Use {@link #isModelAvailable(String, String)} for
     * a single-pair check - it's just a linear scan over this list.
     */
    public List<AvailableModel> listAvailableModels() {
        return listAvailableModels(null);
    }

    /**
     * Category-scoped overload. Same shape as {@link #listAvailableModels()}
     * but the per-category sidecar (rank + enabled) overrides the global
     * fields. Pass {@code null} for the legacy flat-by-global-rank view.
     */
    @SuppressWarnings("unchecked")
    public List<AvailableModel> listAvailableModels(String category) {
        return listAvailableModels(category, null);
    }

    @SuppressWarnings("unchecked")
    public List<AvailableModel> listAvailableModels(String category, String tenantId) {
        Map<String, Object> filtered = getModelsForCategory(category, tenantId);
        List<AvailableModel> out = new ArrayList<>();
        List<Map<String, Object>> providers = (List<Map<String, Object>>) filtered.get("providers");
        if (providers == null) return out;

        for (Map<String, Object> providerInfo : providers) {
            String providerName = (String) providerInfo.get("name");
            if (providerName == null) continue;
            List<Map<String, Object>> models = (List<Map<String, Object>>) providerInfo.get("models");
            if (models == null) continue;
            for (Map<String, Object> m : models) {
                String modelId = (String) m.get("id");
                if (modelId == null) continue;
                String tier = (String) m.getOrDefault("tier", "mid");
                int displayOrder = m.get("displayOrder") instanceof Number n ? n.intValue() : 999;
                String defaultReasoningEffort = (String) m.get("defaultReasoningEffort");
                Integer maxOutputTokens = m.get("maxOutputTokens") instanceof Number n ? n.intValue() : null;
                out.add(new AvailableModel(providerName, modelId, tier, displayOrder, defaultReasoningEffort, maxOutputTokens));
            }
        }
        // Sort globally by displayOrder so the list reflects the admin's ranking,
        // not the per-provider grouping order.
        out.sort(Comparator.comparingInt(AvailableModel::displayOrder));
        return out;
    }

    /**
     * True iff {@code (provider, modelId)} is present in
     * {@link #listAvailableModels()}. Rejects null inputs so callers can
     * forward user/LLM-supplied values directly without pre-validation.
     *
     * <p>Used by {@code AgentCrudModule} to block agent create/update calls
     * that reference a disabled or non-existing model. The check runs against
     * the platform-wide catalog (same one the admin edits under
     * {@code /settings/ai-providers}) - not a tenant-scoped view - so an LLM
     * will never see a model in {@code agent(action='help')}'s
     * {@code available_models} that the runtime would then reject.
     */
    public boolean isModelAvailable(String provider, String modelId) {
        if (provider == null || modelId == null) return false;
        for (AvailableModel am : listAvailableModels()) {
            if (am.provider().equals(provider) && am.modelId().equals(modelId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalise a {@code (provider, model)} pair against the live catalog so a
     * workflow LLM node whose stored provider is stale or blank routes to the
     * provider that can ACTUALLY serve the model. The motivating case: a Claude
     * "Bridge (CLI)" model ({@code claude-opus-4-7}) that was collapsed to
     * {@code provider="anthropic"} (frontend heuristic / LLM-authored plan) is
     * resolved back to {@code claude-code}. That single correction restores BOTH
     * bridge routing (the subscription, not the direct-API credit pool) AND the
     * bridge access policy enforced by {@code BridgeAccessGuard} - because the
     * guard only fires for bridge provider slugs, a node mislabelled
     * {@code anthropic} silently bypassed the admin-only / quota rules that the
     * equivalent chat model honours.
     *
     * <p>The catalog is the single source of truth (the admin's
     * {@code /settings/ai-providers}), and only providers that can actually run
     * are listed ({@link #listAvailableModels()} drops bridges whose CLI is not
     * installed), so the result never points at an unusable provider.
     *
     * <p>Rules:
     * <ul>
     *   <li>valid pair (the model IS served by the named provider) -&gt;
     *       unchanged - respects a genuine direct-API choice such as
     *       {@code anthropic + claude-opus-4-6};</li>
     *   <li>blank/invalid provider, model served by exactly ONE provider -&gt;
     *       that provider (maps {@code anthropic + claude-opus-4-7}, bridge-only,
     *       to {@code claude-code});</li>
     *   <li>ambiguous (served by several, the named one not among them) or
     *       unknown model -&gt; unchanged.</li>
     * </ul>
     *
     * <p>Catalog-build failures are swallowed and the caller's provider is
     * returned verbatim, so this normalisation can never break execution.
     */
    public String resolveProvider(String provider, String model) {
        if (model == null || model.isBlank()) {
            return provider;
        }
        try {
            return resolveProviderForModel(listAvailableModels(), provider, model);
        } catch (Exception e) {
            log.warn("Provider resolution skipped (catalog unavailable) for model={}: {}",
                    model, e.getMessage());
            return provider;
        }
    }

    /**
     * Pure resolution logic for {@link #resolveProvider(String, String)} - takes
     * the catalog explicitly so it is unit-testable without a Spring context or
     * DB. Package-private for the test in the same package.
     */
    static String resolveProviderForModel(List<AvailableModel> catalog, String provider, String model) {
        if (model == null || model.isBlank() || catalog == null || catalog.isEmpty()) {
            return provider;
        }
        String named = provider == null ? "" : provider.trim();

        // Valid pair: the named provider already serves this model -> keep it.
        if (!named.isEmpty()) {
            for (AvailableModel am : catalog) {
                if (model.equals(am.modelId()) && named.equalsIgnoreCase(am.provider())) {
                    return provider;
                }
            }
        }

        // Otherwise, collect the distinct providers that DO serve this model.
        java.util.LinkedHashSet<String> serving = new java.util.LinkedHashSet<>();
        for (AvailableModel am : catalog) {
            if (model.equals(am.modelId())) {
                serving.add(am.provider());
            }
        }
        if (serving.size() == 1) {
            String only = serving.iterator().next();
            if (!only.equalsIgnoreCase(named)) {
                log.info("Provider normalised for model={}: '{}' -> '{}' (only catalog provider serving it)",
                        model, provider, only);
            }
            return only;
        }
        // 0 (unknown model) or >1 (ambiguous) -> leave the caller's provider alone.
        return provider;
    }

    /**
     * Minimal DTO for the flat model catalog. Kept as a nested record so the
     * type is trivially serialisable as a map by Jackson (LLM help response)
     * and accessible from test code without extra imports.
     */
    public record AvailableModel(String provider, String modelId, String tier, int displayOrder,
                                 String defaultReasoningEffort, Integer maxOutputTokens) {
        /**
         * Backward-compatible 4-arg constructor (no per-model default effort,
         * no output cap). Keeps existing call/test sites compiling unchanged.
         */
        public AvailableModel(String provider, String modelId, String tier, int displayOrder) {
            this(provider, modelId, tier, displayOrder, null, null);
        }

        /**
         * Backward-compatible 5-arg constructor (effort, no output cap). The
         * canonical 6-arg form additionally carries the model's output ceiling
         * so callers can clamp {@code max_tokens} via
         * {@link com.apimarketplace.agent.config.MaxTokensClamp}.
         */
        public AvailableModel(String provider, String modelId, String tier, int displayOrder,
                              String defaultReasoningEffort) {
            this(provider, modelId, tier, displayOrder, defaultReasoningEffort, null);
        }
    }

    /**
     * Per-model admin default reasoning effort for a {@code (provider, modelId)}
     * pair, or {@code null} when none is configured / the model is unknown.
     * Read from the same platform catalog the picker uses, so the runtime default
     * always matches what the admin sees under {@code /settings/ai-providers}.
     */
    public String getDefaultReasoningEffort(String provider, String modelId) {
        if (provider == null || modelId == null) {
            return null;
        }
        for (AvailableModel am : listAvailableModels()) {
            if (am.provider().equals(provider) && am.modelId().equals(modelId)) {
                return am.defaultReasoningEffort();
            }
        }
        return null;
    }

    /**
     * Resolve the effective reasoning effort for a bridge dispatch: the
     * caller-supplied value (already encoding any per-conversation override and
     * per-agent setting) wins; otherwise fall back to this model's admin default.
     * Returns the canonical lowercase wire value or {@code null} (→ CLI default).
     */
    public String resolveEffortWithDefault(String callerEffort, String provider, String modelId) {
        return ReasoningEffortResolver.resolve(callerEffort, null, getDefaultReasoningEffort(provider, modelId));
    }

    /**
     * The model's output-token ceiling ({@code max_tokens} the provider will
     * accept) for a {@code (provider, modelId)} pair, or {@code null} when the
     * catalog carries no value (unsynced / custom model). Used by the agent
     * execution paths to clamp a high platform default down to what the model
     * actually accepts via {@link com.apimarketplace.agent.config.MaxTokensClamp},
     * so e.g. a 16000 default never 400s against DeepSeek-chat's 8192 cap.
     */
    public Integer resolveMaxOutputTokens(String provider, String modelId) {
        if (provider == null || modelId == null) {
            return null;
        }
        for (AvailableModel am : listAvailableModels()) {
            if (am.provider().equals(provider) && am.modelId().equals(modelId)) {
                return am.maxOutputTokens();
            }
        }
        return null;
    }

    /**
     * The model's total context window in tokens for a {@code (provider, modelId)} pair, or
     * {@code null} when the catalog carries no value.
     *
     * <p>Read from this catalog and NOT from the pricing snapshot, which is the neighbouring
     * table and the obvious-looking choice. The pricing snapshot only gained a
     * {@code context_window} column late and almost nothing backfills it: in production 744 of
     * its 816 rows are null, {@code deepseek-v4-pro} among them. A context monitor sourced from
     * there would therefore report "window unknown" for the very models it was built to watch,
     * and look fixed because it had gone quiet. This catalog has it for 779 of 805 rows.
     *
     * <p>Reads the override row DIRECTLY rather than scanning {@link #listAvailableModels()} as
     * {@link #resolveMaxOutputTokens} does: one indexed lookup instead of ~800 rows plus a yml
     * merge. It cuts both ways: a model present only in {@code application.yml} with no DB row
     * reports an unknown window here while still reporting an output cap there, and a DISABLED
     * model reports a window here while reporting no cap there (that listing filters on enabled
     * / category / bridge availability). For an observability read the latter is the better
     * behaviour - a stale agent config can still be executing a disabled model.
     */
    public Integer resolveContextWindow(String provider, String modelId) {
        if (provider == null || modelId == null) {
            return null;
        }
        return repository.findByProviderAndModelId(provider, modelId)
            .map(ModelConfigOverrideEntity::getContextWindow)
            .orElse(null);
    }

    /**
     * Get the effective config for each model (yml merged with DB) for the admin UI.
     * Includes disabled models (with enabled=false flag) so admins can re-enable them.
     */
    public List<Map<String, Object>> getEffectiveModelList() {
        return getEffectiveModelList(null);
    }

    /**
     * Category-scoped variant. When {@code category} is non-null, the V156
     * sidecar (rank + enabled per category) is overlaid on top of the global
     * fields BEFORE the admin row is built - so the panel displays the
     * effective state for the active tab (e.g. a model disabled in
     * {@code browser_agent} reports {@code enabled=false} on that tab even
     * if the global flag is {@code true}). Pass {@code null} for the legacy
     * global view.
     */
    public List<Map<String, Object>> getEffectiveModelList(String category) {
        return getEffectiveModelList(category, null);
    }

    /**
     * Tenant-aware admin model list. The admin config panel lists the FULL
     * catalog - EVERY provider, cloud-prod and CE alike, whether or not its key
     * is configured - so an admin can rank, sort, price and enable (and set the
     * bundle-ship flag on) every model BEFORE any key exists. That ranking is
     * what the signed bundle ships, so hiding keyless providers here would make
     * them impossible to order; and a model shown for ranking is not confusing
     * as long as its usability is marked, which each row's {@code available}
     * flag does:
     * <ul>
     *   <li>{@code available=true}: the picker/runtime would offer it under the
     *       tenant's LLM source (env/DB key present, or a cloud-relay-supported
     *       API provider in cloud-connect).</li>
     *   <li>{@code available=false}: listed for configuration/ranking only -
     *       no key yet (or a non-relay provider in cloud-connect). Bridge rows
     *       carry {@code bridgeAvailable} instead (CLI-install state).</li>
     * </ul>
     *
     * <p>This intentionally DIVERGES from the end-user picker
     * ({@link #getModelsForCategory(String, String)}), which still hard-drops
     * unconfigured providers ("no provider, no model") so a chat/agent selector
     * never offers an unrunnable model. Admin-gated
     * ({@link com.apimarketplace.agent.controller.ModelConfigController}) and
     * never feeds the picker, so keyless models cannot leak into a selector.
     */
    public List<Map<String, Object>> getEffectiveModelList(String category, String tenantId) {
        // Admin config view: include EVERY provider regardless of key/cloud mode
        // (includeUnconfigured=true). Each row is annotated with `available`
        // below so the panel can mark unusable-yet-rankable models. The picker
        // path keeps includeUnconfigured=false and is untouched.
        Map<String, Object> base = getAvailableProvidersBase(tenantId, true);
        // Runtime availability per provider (would the picker offer it?), stamped
        // per row as `available` so the UI can badge "not configured" without
        // hiding the model.
        boolean cloudSelected = isCloudSelected(tenantId);
        // Admins should see bridge providers regardless of CLI-install state so
        // they can configure, price, and set access policies. A default-enabled
        // bridge stub reports configured=true, so it already SURVIVES the key
        // filter above (no env/DB key needed) - that is the primary guarantee.
        // enrichWithBridgeProviders only ADDS any bridge not already present,
        // and only when the bridge host is reachable; an explicitly-disabled
        // bridge (configured=false) on an unreachable host is therefore not
        // listed, consistent with "no provider, no model". Runtime availability
        // (CLI installed yes/no) is surfaced per-row via the bridgeAvailable
        // flag below - the user-side picker still hard-filters unavailable CLIs.
        if (isBridgeConnected()) {
            enrichWithBridgeProviders(base);
        }
        // Compute which bridges are actually installed right now so we can
        // annotate rows rather than drop them.
        Map<String, Boolean> bridgeInstalled = bridgeAvailabilityFilter.installedMap();
        // Read once for the whole response, like the map above: called per row, a TTL
        // lapse mid-list would let two rows report different availability for the same CLI.
        Map<String, Boolean> bridgeRunnable = bridgeAvailabilityFilter.runnableMap();
        // V156/V158: scope the YAML/bridge base + overrides by category mode, so
        // a row only surfaces under a category its mode is eligible for. This is
        // the same filter applied by getModelsForCategory(), so picker and admin
        // stay in sync.
        //
        // The admin Chat / Agent tab reads the null/global view (to keep chat's
        // writes on the global ranking/enabled columns), but it must STILL drop
        // image-gen rows (mode='image'), so the mode-filter applies to the null
        // path too (treated as 'chat'). That is what makes an image row
        // unreachable from every surviving screen. The per-category sidecar
        // OVERLAY stays gated to a non-null category - overlaying the
        // V156-backfilled chat sidecar here would diverge from the global
        // ranking the Chat tab writes through.
        String modeKey = modeFilterKey(category);
        filterProvidersByCategoryMode(base, modeKey);
        List<ModelConfigOverrideEntity> overrides = repository.findAllByOrderByRankingAsc();
        overrides = overrides.stream()
                .filter(o -> ModelCategory.acceptsMode(modeKey, o.getMode()))
                .toList();
        if (category != null) {
            overrides = applyCategoryOverlay(overrides, category);
        }

        Map<String, ModelConfigOverrideEntity> overrideMap = new HashMap<>();
        for (ModelConfigOverrideEntity o : overrides) {
            overrideMap.put(o.getProvider() + ":" + o.getModelId(), o);
        }

        List<Map<String, Object>> result = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) base.get("providers");
        if (providers == null) return result;

        // Collect rows to inject standalone: is_custom=true rows (CE-local
        // additions) PLUS rows whose (provider, model_id) doesn't match any
        // YAML-declared model (e.g. rows added by ModelCatalogSyncService
        // from LiteLLM / OpenRouter). Deprecated rows are skipped - the
        // admin UI shouldn't list EOL models.
        Set<String> yamlKeys = new HashSet<>();
        for (Map<String, Object> p : providers) {
            String pname = (String) p.get("name");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> yamlModels = (List<Map<String, Object>>) p.get("models");
            if (yamlModels == null) continue;
            for (Map<String, Object> ym : yamlModels) {
                yamlKeys.add(pname + ":" + ym.get("id"));
            }
        }

        Map<String, List<ModelConfigOverrideEntity>> customByProvider = new HashMap<>();
        for (ModelConfigOverrideEntity o : overrides) {
            if (o.getDeprecatedAt() != null) continue;
            boolean notInYaml = !yamlKeys.contains(o.getProvider() + ":" + o.getModelId());
            if (o.isCustom() || notInYaml) {
                customByProvider.computeIfAbsent(o.getProvider(), k -> new ArrayList<>()).add(o);
            }
        }

        for (Map<String, Object> providerInfo : providers) {
            String providerName = (String) providerInfo.get("name");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> models = (List<Map<String, Object>>) providerInfo.get("models");
            if (models == null) continue;

            // Per-provider CLI availability. Null/absent = unknown (bridge
            // server unreachable or this is not a bridge provider). Admins
            // use this to decide whether to prod the infra team or to hide
            // the row from tenants.
            String cliId = BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.get(providerName);
            Boolean bridgeAvailable = cliId != null ? bridgeInstalled.get(cliId) : null;
            // Would the picker offer this provider? Stamped per row as `available`
            // so the admin panel can badge "not configured" without hiding it.
            boolean providerAvailable = isProviderRuntimeAvailable(
                    providerName, Boolean.TRUE.equals(providerInfo.get("configured")), cloudSelected);

            for (Map<String, Object> model : models) {
                Map<String, Object> entry = new LinkedHashMap<>(model);
                String modelId = (String) model.get("id");
                ModelConfigOverrideEntity override = overrideMap.get(providerName + ":" + modelId);
                if (override != null) {
                    applyOverride(entry, override);
                    applyRateLimitFields(entry, override);
                }
                entry.put("hasOverride", override != null);
                entry.put("isCustom", override != null && override.isCustom());
                entry.put("enabled", override == null || !Boolean.FALSE.equals(override.getEnabled()));
                entry.put("available", providerAvailable);
                // Cloud-admin bundle override (V381): 3-state (null = inherit).
                // Rendered by the admin Models panel on cloud only; harmless
                // extra field for CE readers.
                entry.put("bundleEnabled", override != null ? override.getBundleEnabled() : null);
                // freeTierEnabled is NOT stamped here: applyOverride above already set it
                // via applyEnrichmentFields, which is the one helper both catalog paths
                // share. Re-writing it here would be a second place to keep in step.
                entry.put("providerKind",
                        override != null && override.getProviderKind() != null
                                ? override.getProviderKind()
                                : inferProviderKind(providerName, null));
                if (cliId != null) {
                    entry.put("bridgeAvailable", bridgeAvailable);
                }
                stampCliCounterpart(entry, providerName, modelId, bridgeRunnable);
                result.add(entry);
            }

            // Standalone / custom rows for this provider (is_custom rows +
            // sync/bundle rows not declared in YAML). Now that the admin base
            // includes every provider, these surface under keyless providers too
            // (annotated available=false) so they can be ranked/priced.
            List<ModelConfigOverrideEntity> customs = customByProvider.get(providerName);
            if (customs != null) {
                for (ModelConfigOverrideEntity custom : customs) {
                    Map<String, Object> entry = buildModelInfo(custom);
                    applyRateLimitFields(entry, custom);
                    entry.put("hasOverride", true);
                    // isCustom reflects the DB flag - sync-sourced rows are NOT
                    // is_custom (bundle apply can overwrite them), but they
                    // still need to be visible in the picker.
                    entry.put("isCustom", custom.isCustom());
                    entry.put("enabled", !Boolean.FALSE.equals(custom.getEnabled()));
                    entry.put("available", providerAvailable);
                    entry.put("bundleEnabled", custom.getBundleEnabled());
                    // freeTierEnabled: stamped by buildModelInfo above, same as on the
                    // YAML path. Same rule, same reason - one place to keep in step.
                    entry.put("providerKind",
                            custom.getProviderKind() != null ? custom.getProviderKind() : "byok");
                    if (cliId != null) {
                        entry.put("bridgeAvailable", bridgeAvailable);
                    }
                    stampCliCounterpart(entry, providerName, custom.getModelId(), bridgeRunnable);
                    result.add(entry);
                }
            }
        }

        // V156 - defensive injection of is_custom=true LOCAL providers (admin-added
        // servers, no API key needed) that have no YAML shell, on a category tab.
        // Now that the admin base includes EVERY provider (includeUnconfigured),
        // every provider is already emitted above, so this loop is a no-op in
        // practice; it stays as a backstop for any provider the base might omit.
        if (category != null) {
            Set<String> alreadyEmittedProviders = new HashSet<>();
            for (Map<String, Object> p : providers) {
                Object name = p.get("name");
                if (name instanceof String s) alreadyEmittedProviders.add(s);
            }
            for (var entry : customByProvider.entrySet()) {
                String providerName = entry.getKey();
                if (alreadyEmittedProviders.contains(providerName)) continue;
                List<ModelConfigOverrideEntity> localCustoms = entry.getValue().stream()
                        .filter(ModelConfigOverrideEntity::isCustom)
                        .toList();
                if (localCustoms.isEmpty()) continue;
                String cliId = BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.get(providerName);
                Boolean bridgeAvailable = cliId != null ? bridgeInstalled.get(cliId) : null;
                for (ModelConfigOverrideEntity custom : localCustoms) {
                    Map<String, Object> e = buildModelInfo(custom);
                    applyRateLimitFields(e, custom);
                    e.put("hasOverride", true);
                    e.put("isCustom", custom.isCustom());
                    e.put("enabled", !Boolean.FALSE.equals(custom.getEnabled()));
                    // Local admin-added custom providers are self-managed (no
                    // platform key needed) - treat as available.
                    e.put("available", true);
                    e.put("bundleEnabled", custom.getBundleEnabled());
                    // freeTierEnabled: stamped by buildModelInfo above.
                    e.put("providerKind",
                            custom.getProviderKind() != null ? custom.getProviderKind() : "byok");
                    if (cliId != null) {
                        e.put("bridgeAvailable", bridgeAvailable);
                    }
                    stampCliCounterpart(e, providerName, custom.getModelId(), bridgeRunnable);
                    result.add(e);
                }
            }
        }

        // CE boundary: the admin Models panel must agree with the picker - never
        // list the openrouter aggregator or cohere on a self-hosted install, even
        // if an admin saved a custom (is_custom=true) override under those
        // providers (which the injection loop above would otherwise surface).
        // Mirrors filterCeBlockedProviders on the picker path. No-op in cloud.
        result.removeIf(m -> m.get("provider") instanceof String p && isCeBlockedProvider(p));

        // Sort by displayOrder
        result.sort(Comparator.comparingInt(m -> (int) ((Map<String, Object>) m).getOrDefault("displayOrder", 999)));

        return result;
    }

    /**
     * Admin-panel hint: the CLI bridge that could EXECUTE this billed model, so the
     * Models panel can offer a one-click execution link (billed price kept, run
     * dispatched to the CLI subscription) instead of making the admin retype the
     * pair in the Execution links tab.
     *
     * <p>Stamped ONLY when {@link BridgeAllowlist} says that CLI routes this model's
     * family, which keeps the panel from offering a link to a CLI that has no idea
     * what the model is. It is NOT a promise that the binary on the bridge host is
     * already new enough for a freshly-released id: that gap is irreducible (no CLI
     * exposes a model list) and is the same one the catalog itself lives with.
     *
     * <p>{@code cliBridgeAvailable} says whether that CLI could RUN right now: it reads
     * the strict signal (installed AND authenticated), not the looser {@code installed}
     * flag behind the neighbouring {@code bridgeAvailable} badge, because here the
     * answer drives a decision - an installed-but-logged-out CLI runs nothing. {@code
     * null} = unknown (bridge unreachable or URL unset; on a bridge too old to report
     * {@code authenticated} it degrades to the installed flag). It matters before the click,
     * not after: a bridge route is dropped back to the billed pair only when the whole
     * bridge TRANSPORT is unwired ({@code ExecutionLinkRouter} plus {@code
     * BridgeLoopDispatcher.isAvailable}), so with a wired bridge and an unusable CLI
     * the run is dispatched and FAILS.
     *
     * <p>Both keys are ABSENT for a model with no CLI counterpart - the panel renders
     * the button off their presence. Harmless extra fields for CE readers, where
     * execution links are disabled entirely.
     */
    private void stampCliCounterpart(Map<String, Object> entry, String providerName, String modelId,
                                     Map<String, Boolean> bridgeRunnable) {
        String bridge = BridgeAllowlist.cliCounterpart(providerName, modelId);
        if (bridge == null) {
            return;
        }
        entry.put("cliBridgeProvider", bridge);
        String cliId = BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.get(bridge);
        entry.put("cliBridgeAvailable", cliId != null ? bridgeRunnable.get(cliId) : null);
    }

    @Transactional
    public ModelConfigOverrideEntity saveOverride(ModelConfigOverrideEntity input) {
        Optional<ModelConfigOverrideEntity> existing = repository.findByProviderAndModelId(
                input.getProvider(), input.getModelId());

        ModelConfigOverrideEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
            // providerKind is immutable across edits - catalog origin decides it
            // at insert time (bridge vs byok/cloud). Admin edits never toggle
            // the discriminator so reporting / catalog filtering stay consistent.
        } else {
            entity = new ModelConfigOverrideEntity();
            entity.setProvider(input.getProvider());
            entity.setModelId(input.getModelId());
            entity.setCustom(input.isCustom());
            entity.setProviderKind(inferProviderKind(input.getProvider(), input.getProviderKind()));
        }

        if (input.getEnabled() != null) { entity.setEnabled(input.getEnabled()); entity.addUserModifiedField("enabled"); }
        // Cloud-admin bundle override (V381). Not tracked in userModifiedFields:
        // it never travels in a payload, so no merge can clobber it. Same
        // explicit-set contract as the rate-limit fields: the controller flips
        // the transient flag when the key is present in the request body, and
        // an explicit null means "reset to inherit".
        if (input.isBundleEnabledExplicitlySet()) {
            entity.setBundleEnabled(input.getBundleEnabled());
        }
        // Free-tier opening (V493). Same contract and same reason as bundleEnabled:
        // cloud-only, never travels in a bundle payload, so it is not tracked in
        // userModifiedFields and only an explicit key in the request changes it.
        if (input.isFreeTierEnabledExplicitlySet()) {
            entity.setFreeTierEnabled(input.isFreeTierEnabled());
        }
        if (input.getDisplayName() != null) { entity.setDisplayName(input.getDisplayName()); entity.addUserModifiedField("displayName"); }
        if (input.getTier() != null) { entity.setTier(input.getTier()); entity.addUserModifiedField("tier"); }
        // Per-model default reasoning effort: an explicit empty/blank string clears it
        // back to "no default" (the admin picked "Inherit"); a non-blank value is
        // normalized to the canonical lowercase wire form. Validation (reject unknown
        // levels) happens at the controller boundary before we get here.
        if (input.getDefaultReasoningEffort() != null) {
            String raw = input.getDefaultReasoningEffort().trim();
            ReasoningEffort parsed = ReasoningEffort.fromString(raw);
            entity.setDefaultReasoningEffort(parsed != null ? parsed.wire() : null);
            entity.addUserModifiedField("defaultReasoningEffort");
        }
        if (input.getRanking() != null) { entity.setRanking(input.getRanking()); entity.addUserModifiedField("ranking"); }
        if (input.getRecommended() != null) { entity.setRecommended(input.getRecommended()); entity.addUserModifiedField("recommended"); }
        if (input.getPriceInput() != null) { entity.setPriceInput(input.getPriceInput()); entity.addUserModifiedField("priceInput"); }
        if (input.getPriceOutput() != null) { entity.setPriceOutput(input.getPriceOutput()); entity.addUserModifiedField("priceOutput"); }
        // Cache prices are billing inputs like the other two since V491 (they decide what a
        // cached token costs), so a supplied value must be persisted and protected from the
        // next feed sync rather than dropped on the floor. CatalogMergeService already
        // honours both names in its protected-field list.
        if (input.getPriceCacheRead() != null) { entity.setPriceCacheRead(input.getPriceCacheRead()); entity.addUserModifiedField("priceCacheRead"); }
        if (input.getPriceCacheWrite() != null) { entity.setPriceCacheWrite(input.getPriceCacheWrite()); entity.addUserModifiedField("priceCacheWrite"); }
        // Rate limit fields: when explicitly set (even to null = "clear"), overwrite unconditionally
        if (input.isRateLimitsExplicitlySet()) {
            entity.setRateLimitTpm(input.getRateLimitTpm());
            entity.addUserModifiedField("rateLimitTpm");
            entity.setRateLimitRpm(input.getRateLimitRpm());
            entity.addUserModifiedField("rateLimitRpm");
            entity.setRateLimitTpmPerTenant(input.getRateLimitTpmPerTenant());
            entity.addUserModifiedField("rateLimitTpmPerTenant");
            entity.setRateLimitRpmPerTenant(input.getRateLimitRpmPerTenant());
            entity.addUserModifiedField("rateLimitRpmPerTenant");
        }

        // BOTH guards test what THIS REQUEST is doing, not what the row already was.
        //
        // They used to read the merged row, so a rename on a model that was ALREADY enabled
        // and carries no price of its own threw before the save, and the @Transactional method
        // rolled the whole thing back: the new name AND its "the user edited this" marker
        // never reached Postgres. The admin saw a generic banner at the top of a long table,
        // the cell snapped back to the server value, and after a refresh the model still wore
        // the name the upstream feed gave it. Reported as "my alias reverts on refresh".
        //
        // Such rows are ordinary: a model discovered from a provider's own endpoint arrives
        // unpriced, and the panel still shows a price for it because that comes from the
        // catalogue overlay rather than the row's own columns.
        requirePriceBeforeEnabling(entity, Boolean.TRUE.equals(input.getEnabled()));
        // Also when the request MOVES a price, not only when it opens the free tier. A model
        // already open to it can be repriced, and an unbillable value there is refused by the
        // mirror while the catalogue keeps it: the page would show a price billing never took,
        // with a WARN log as the only trace. That is the silent lie this guard exists to stop,
        // so narrowing it to the free-tier key alone gave the lie a second door.
        // carriesAPrice covers exactly the two rates the guard below validates. The cache
        // rates are deliberately NOT in it: the guard does not check them, so counting them
        // would imply a refusal that never comes.
        if (input.isFreeTierEnabledExplicitlySet() || carriesAPrice(input)) {
            requirePriceBeforeFreeTier(entity);
        }

        ModelConfigOverrideEntity saved = repository.save(entity);

        // Sync pricing into auth.model_pricing for any row that carries a price -
        // bridges included. Since V130 bridges store the underlying cloud model's
        // list price and CreditService bills them at that rate (see
        // CreditService.consumeForChat Javadoc). The providerKind is propagated so
        // the billing mirror keeps the catalog-origin discriminator for reporting.
        // V493: free_tier_enabled rides the same sync, and therefore inherits its
        // precondition - the row must carry a price. A priceless row can no longer be
        // opened to the free tier at all (requirePriceBeforeFreeTier rejects the save),
        // which is what closes the hole this guard would otherwise leave: the chip would
        // read lit, nothing would reach the mirror, and isFreeTierModel would keep
        // answering false off the synthetic default row. Do NOT read the condition below
        // as "syncs on every save" - an unpriced row still saves, it just cannot be open.
        if (saved.getPriceInput() != null || saved.getPriceOutput() != null) {
            boolean mirrored = authPricingSyncClient.sync(saved.getProvider(), saved.getModelId(),
                    saved.getPriceInput(), saved.getPriceOutput(),
                    saved.getProviderKind(),
                    saved.getPriceCacheRead(), saved.getPriceCacheWrite(),
                    saved.isFreeTierEnabled());
            // V493: a dropped RATE write is recoverable (reconciliation squares the ledger
            // up, and the next save re-sends it). A dropped ACCESS write is not: the gate
            // reads the mirror, so the catalog would say "open" forever while every free
            // account is refused, and nobody would know to save the row a second time.
            // Failing here rolls this transaction back, which keeps the two sides equal and
            // tells the admin to retry - the only outcome that is not a silent lie.
            //
            // Note this refuses CLOSING too, not just opening: against an auth-service pod
            // that predates the column there is no echo either way, so the flag cannot be
            // moved at all until the rollout finishes. Deliberate - a close that the mirror
            // did not take is the same lie wearing the other hat - and self-resolving.
            if (!mirrored && input.isFreeTierEnabledExplicitlySet()) {
                // One compensating close before giving up, and only when we were OPENING.
                // "Not mirrored" covers a response we never received, and auth-service may
                // have committed the TRUE before the connection dropped - in which case
                // rolling this transaction back alone would leave the mirror OPEN for a
                // model the catalog says is closed, which is the one direction that costs
                // money. Pushing FALSE makes the rollback true on both sides. If the
                // original write never landed, this is a no-op on an already-false row.
                if (saved.isFreeTierEnabled()) {
                    authPricingSyncClient.sync(saved.getProvider(), saved.getModelId(),
                            saved.getPriceInput(), saved.getPriceOutput(),
                            saved.getProviderKind(),
                            saved.getPriceCacheRead(), saved.getPriceCacheWrite(), false);
                }
                throw new IllegalStateException(
                        "Could not mirror the free-tier setting for " + saved.getProvider() + ":"
                                + saved.getModelId() + " to billing, so it was not saved. The"
                                + " free-tier gate reads the billing mirror, not this row."
                                + " Retry once auth-service is reachable.");
            }
        }

        invalidateModelCaches();
        return saved;
    }

    /**
     * Per-category bulk re-rank - writes to {@code model_category_settings}
     * instead of the global {@code model_config_overrides.ranking}. Each entry
     * is {@code {provider, modelId, ranking}}; an absent sidecar row is
     * created lazily so the admin can re-rank a model that has never carried
     * a category-scoped value before.
     *
     * <p><b>Frontend contract:</b> the admin UI MUST send the FULL re-ordered
     * list for a category (every model the admin sees in that category tab),
     * not a diff. Models omitted from the input are left unchanged - they
     * keep whichever sidecar row they had, or fall back to the global ranking
     * if no sidecar row exists. Sending a partial list silently breaks
     * relative ordering for the omitted models because their sidecar rank may
     * end up lower than a freshly-bumped model's rank.
     *
     * <p>Models without a parent {@code model_config_overrides} row are
     * silently skipped - categories are ALWAYS scoped to a parent model row,
     * never floating. The drag-and-drop UI on the chat tab seeds the parent
     * row lazily via {@link #bulkUpdateRankings(List)}; per-category UI
     * inherits that parent row.
     */
    @Transactional
    public void bulkUpdateCategoryRankings(String category, List<Map<String, Object>> rankings) {
        if (!ModelCategory.isValidShape(category)) {
            throw new IllegalArgumentException("Invalid category key: " + category);
        }
        if (rankings == null) {
            throw new IllegalArgumentException("rankings must not be null");
        }
        // Validate the WHOLE batch upfront so a malformed entry can't sneak past
        // the loop after writing N-1 rows. Without this, a missing 'ranking'
        // field NPEs mid-loop and leaves the sidecar partially written -
        // @Transactional rolls back runtime exceptions, but the audit-log
        // trigger has already fired with partial intermediate values.
        for (int i = 0; i < rankings.size(); i++) {
            Map<String, Object> item = rankings.get(i);
            if (item == null) {
                throw new IllegalArgumentException("rankings[" + i + "] is null");
            }
            if (!(item.get("provider") instanceof String) || ((String) item.get("provider")).isBlank()) {
                throw new IllegalArgumentException("rankings[" + i + "].provider must be a non-blank String");
            }
            if (!(item.get("modelId") instanceof String) || ((String) item.get("modelId")).isBlank()) {
                throw new IllegalArgumentException("rankings[" + i + "].modelId must be a non-blank String");
            }
            Object rank = item.get("ranking");
            if (!(rank instanceof Number n)) {
                throw new IllegalArgumentException("rankings[" + i + "].ranking must be a number");
            }
            int rankInt = n.intValue();
            if (rankInt < 0 || rankInt > 100_000) {
                throw new IllegalArgumentException(
                        "rankings[" + i + "].ranking out of range (got " + rankInt + ", expected 0..100000)");
            }
        }

        // Lazy-creation fallback for YAML-only rows (mirrors the legacy
        // bulkUpdateRankings behaviour). Without this, models declared in
        // application.yml but never synced into model_config_overrides would
        // be silently skipped on a browser_agent re-rank, and the admin's
        // drag-and-drop would not persist for those rows.
        // The chat tab gets this for free via the legacy path; the category
        // path needs it explicitly.
        Map<String, String> catalogDisplayNames = collectCatalogDisplayNames();

        for (Map<String, Object> item : rankings) {
            String provider = (String) item.get("provider");
            String modelId = (String) item.get("modelId");
            int rank = ((Number) item.get("ranking")).intValue();

            ModelConfigOverrideEntity parent = repository.findByProviderAndModelId(provider, modelId)
                    .orElseGet(() -> {
                        // Create a stub override row for the YAML-declared
                        // model so the sidecar FK target exists. display_name
                        // falls back to the YAML catalog name (NOT NULL since
                        // V109); provider_kind is inferred (bridge / cloud /
                        // byok). The row carries no per-field overrides - it
                        // only exists so the sidecar can attach.
                        ModelConfigOverrideEntity stub = new ModelConfigOverrideEntity();
                        stub.setProvider(provider);
                        stub.setModelId(modelId);
                        String fallbackName = catalogDisplayNames.getOrDefault(
                                provider + ":" + modelId, modelId);
                        stub.setDisplayName(fallbackName);
                        stub.setProviderKind(inferProviderKind(provider, null));
                        return repository.save(stub);
                    });

            ModelCategorySettingsEntity setting = categoryRepository
                    .findById(new ModelCategorySettingsId(parent.getId(), category))
                    .orElseGet(() -> {
                        ModelCategorySettingsEntity e = new ModelCategorySettingsEntity();
                        e.setModelConfigId(parent.getId());
                        e.setCategory(category);
                        e.setEnabled(Boolean.TRUE);
                        return e;
                    });
            setting.setRank(rank);
            categoryRepository.save(setting);
        }
        invalidateModelCaches();
    }

    /**
     * Per-category enable/disable for a single model. Inserts the sidecar row
     * if absent (defaulting rank to the model's current global ranking so the
     * relative order is preserved on first toggle).
     */
    @Transactional
    public void setCategoryEnabled(String provider, String modelId, String category, boolean enabled) {
        if (!ModelCategory.isValidShape(category)) {
            throw new IllegalArgumentException("Invalid category key: " + category);
        }
        // A model can be listed in the admin panel with NO row of its own: the panel shows the
        // YAML-declared catalogue too, and a row is only written the first time something is
        // saved about that model. The global toggle creates it (saveOverride does), and so does
        // drag-and-drop reordering, but this one used to throw "Unknown model" instead, so a
        // category tab could not disable exactly the models nobody had touched yet. That is
        // what the admin saw as "Failed to save changes" with no further explanation.
        ModelConfigOverrideEntity parent = findOrCreateOverrideRow(provider, modelId);
        if (enabled) {
            // Same rule as the global enable - a category sidecar is the other
            // door into the picker, so an unpriced model must not slip through it.
            requirePriceBeforeEnabling(parent, true);
        }

        ModelCategorySettingsEntity setting = categoryRepository
                .findById(new ModelCategorySettingsId(parent.getId(), category))
                .orElseGet(() -> {
                    ModelCategorySettingsEntity e = new ModelCategorySettingsEntity();
                    e.setModelConfigId(parent.getId());
                    e.setCategory(category);
                    e.setRank(parent.getRanking());
                    return e;
                });
        setting.setEnabled(enabled);
        categoryRepository.save(setting);
        invalidateModelCaches();
    }

    /**
     * The override row for a model, created and persisted when the model exists in the
     * catalogue but has never been saved.
     *
     * <p>Still refuses an unknown pair, which is the guard the plain {@code orElseThrow} was
     * there for: a typo must not quietly create a row for a model that does not exist. What it
     * no longer refuses is the ordinary case of a model the admin simply has not edited yet.
     *
     * <p>Persisted before returning because the per-category sidecar is keyed by the parent's
     * id, which a transient row does not have.
     */
    private ModelConfigOverrideEntity findOrCreateOverrideRow(String provider, String modelId) {
        Optional<ModelConfigOverrideEntity> existing =
                repository.findByProviderAndModelId(provider, modelId);
        if (existing.isPresent()) {
            return existing.get();
        }
        String key = provider + ":" + modelId;
        Map<String, String> catalogNames = collectCatalogDisplayNames();
        if (!catalogNames.containsKey(key)) {
            throw new IllegalArgumentException("Unknown model: " + key);
        }
        ModelConfigOverrideEntity created = new ModelConfigOverrideEntity();
        created.setProvider(provider);
        created.setModelId(modelId);
        // display_name is NOT NULL since V109; the catalogue name is the honest default.
        created.setDisplayName(catalogNames.get(key));
        created.setProviderKind(inferProviderKind(provider, null));
        return repository.save(created);
    }

    /**
     * Refuse to enable a model that carries no price.
     *
     * <p>An unpriced model does NOT bill zero: {@code ModelPricingService}
     * falls back to its documented default rates (1.00 / 4.00 USD per 1M) and
     * logs a warning, so enabling one silently charges every tenant a made-up
     * rate that can be off in either direction. That was harmless while every
     * row came from a feed that carries prices; it stopped being harmless when
     * {@code NativeModelDiscoveryService} started adding rows from each
     * provider's own {@code /models} endpoint, which publishes no pricing at
     * all. Those rows land disabled by design, and this is what keeps them
     * that way until an admin supplies the real rate.
     *
     * <p>Deliberately not a silent no-op: the caller gets a 400 naming the
     * model, because "I enabled it and nothing happened" is the worse failure.
     * A single price (input or output) is enough to pass - some models are
     * genuinely free on one side.
     *
     * @param becomingEnabled the state the CALLER is moving the model to, which
     *        is not always {@code entity.getEnabled()}: the category path flips
     *        a sidecar row while the parent's global flag stays untouched (and
     *        is typically null on a freshly discovered model). Reading the
     *        parent's own flag there would let every category enable through.
     */
    /**
     * Refuse to open a model to the free tier while it has no price (V493).
     *
     * <p>The free-tier flag only reaches {@code auth.model_pricing.free_tier} through the
     * pricing sync below, and that sync needs a price. Without this guard the save
     * succeeds, the chip lights up, and the gate keeps answering "not on the free tier"
     * forever, with no error and no log - the admin's switch silently does nothing.
     *
     * <p>{@link #requirePriceBeforeEnabling} does not already cover it: it only fires when
     * {@code enabled} is explicitly TRUE, while a row created by the drag-and-drop re-rank
     * path carries {@code enabled == null}, which every read treats as enabled. Such a row
     * is offered to users AND unpriced, which is exactly the case that used to fall through.
     *
     * <p>Pricing it is not busywork either: an unpriced model bills at the platform default
     * rate, so an open one would drain the allowance at a rate nobody chose.
     *
     * <p>"Unpriced" means THIS ROW, not the effective figure the panel renders: the panel
     * overlays the catalogue's YAML price onto a row that has none, and the sync pushes the
     * row's own columns. So an admin can be looking at "3.00 / 15.00" and still be refused
     * here, which is why the message says which of the two prices it means.
     */
    private static void requirePriceBeforeFreeTier(ModelConfigOverrideEntity entity) {
        if (!entity.isFreeTierEnabled()) {
            return;
        }
        if (AuthPricingSyncClient.outOfBillingRange(entity.getPriceInput())
                || AuthPricingSyncClient.outOfBillingRange(entity.getPriceOutput())) {
            // The other permanent refusal, and the one that used to masquerade as a
            // transport failure: the mirror REJECTS a rate it cannot store (a catalog
            // sentinel such as the openrouter/auto "-1" list price, or anything above
            // the NUMERIC(10,6) ceiling), so the sync answers "not mirrored" and the
            // save below would tell the admin to retry when auth-service is reachable.
            // It is reachable. Retrying forever is not a fix; pricing the model is.
            throw new IllegalArgumentException(
                    "Cannot open " + entity.getProvider() + ":" + entity.getModelId()
                            + " to the free tier - its price (" + entity.getPriceInput() + " / "
                            + entity.getPriceOutput() + " USD per 1M) is outside what billing can "
                            + "store (0 to " + AuthPricingSyncClient.maxBillableRate() + "). Some "
                            + "catalog feeds publish a sentinel such as -1 for a router model. Set "
                            + "a real price first, or leave the model closed to the free tier.");
        }
        if (entity.getPriceInput() != null || entity.getPriceOutput() != null) {
            return;
        }
        throw new IllegalArgumentException(
                "Cannot open " + entity.getProvider() + ":" + entity.getModelId()
                        + " to the free tier - this model's OWN row carries no price. A price "
                        + "may still be showing in the panel: that one comes from the catalogue "
                        + "and is not what gets mirrored. Billing is written from this row, so "
                        + "set priceInput and priceOutput here first - otherwise the allowance "
                        + "would be spent at the platform default rate rather than the "
                        + "provider's.");
    }

    /** Whether this request sets either of the two billing rates the free-tier guard reads. */
    private static boolean carriesAPrice(ModelConfigOverrideEntity input) {
        return input.getPriceInput() != null || input.getPriceOutput() != null;
    }

    private static void requirePriceBeforeEnabling(ModelConfigOverrideEntity entity,
                                                   boolean becomingEnabled) {
        if (!becomingEnabled) {
            return;
        }
        if (entity.getPriceInput() != null || entity.getPriceOutput() != null) {
            return;
        }
        throw new IllegalArgumentException(
                "Cannot enable " + entity.getProvider() + ":" + entity.getModelId()
                        + " - it has no price. Set priceInput and priceOutput first; "
                        + "an unpriced model would be billed at the platform default rate, "
                        + "not the provider's. Models discovered from a provider's own "
                        + "/models endpoint always arrive unpriced.");
    }

    @Transactional
    public void bulkUpdateRankings(List<Map<String, Object>> rankings) {
        // Catalog-base lookup for display name fallback when creating a fresh
        // override row for a ranking-only change. display_name is NOT NULL since
        // V109; without this fallback, drag-and-drop reorder fails the insert
        // for any model that has no override row yet.
        Map<String, String> catalogDisplayNames = collectCatalogDisplayNames();

        for (Map<String, Object> item : rankings) {
            String provider = (String) item.get("provider");
            String modelId = (String) item.get("modelId");
            int ranking = ((Number) item.get("ranking")).intValue();

            ModelConfigOverrideEntity entity = repository.findByProviderAndModelId(provider, modelId)
                    .orElseGet(() -> {
                        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
                        e.setProvider(provider);
                        e.setModelId(modelId);
                        String fallbackName = catalogDisplayNames.getOrDefault(provider + ":" + modelId, modelId);
                        e.setDisplayName(fallbackName);
                        e.setProviderKind(inferProviderKind(provider, null));
                        return e;
                    });
            entity.setRanking(ranking);
            entity.addUserModifiedField("ranking");
            repository.save(entity);
        }
        invalidateModelCaches();
    }

    private Map<String, String> collectCatalogDisplayNames() {
        Map<String, String> names = new HashMap<>();
        try {
            // The FULL catalogue, unconfigured providers included, because that is what the
            // admin panel lists and therefore what an admin can act on. Reading the
            // availability-filtered view here meant a model of a provider with no key yet was
            // "unknown" to the category toggle, which is the same "Failed to save the change"
            // this lookup was added to end, for a large part of the same population.
            Map<String, Object> base = getAvailableProvidersBase(null, true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> providers = (List<Map<String, Object>>) base.get("providers");
            if (providers == null) return names;
            for (Map<String, Object> p : providers) {
                String pname = (String) p.get("name");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> models = (List<Map<String, Object>>) p.get("models");
                if (pname == null || models == null) continue;
                for (Map<String, Object> m : models) {
                    String id = (String) m.get("id");
                    String name = (String) m.get("name");
                    if (id != null && name != null) {
                        names.put(pname + ":" + id, name);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to collect catalog display names for ranking fallback: {}", e.getMessage());
        }
        return names;
    }

    /**
     * Drop an override row.
     *
     * <p>V493: this CLOSES the model in the billing mirror on the way out. The free-tier
     * gate reads {@code auth.model_pricing.free_tier}, not this table, so deleting the
     * row of an opened model would otherwise leave it funded by the free allowance
     * forever while the admin panel draws the chip OFF (no row, no flag). That is the
     * same catalog/mirror divergence {@code saveOverride} refuses to commit, except it
     * fails OPEN and costs money. Best-effort on purpose: the row is already gone, and
     * a mirror left open for a model that no longer carries an override is still bounded
     * by the allowance, so a failed push must not block the delete. It is logged loudly.
     */
    @Transactional
    public void deleteOverride(String provider, String modelId) {
        closeFreeTierMirror(repository.findByProviderAndModelId(provider, modelId).orElse(null));
        repository.deleteByProviderAndModelId(provider, modelId);
        invalidateModelCaches();
    }

    /** Same contract as {@link #deleteOverride}, for every row at once. */
    @Transactional
    public void resetAll() {
        for (ModelConfigOverrideEntity row : repository.findAllByOrderByRankingAsc()) {
            closeFreeTierMirror(row);
        }
        repository.deleteAll();
        invalidateModelCaches();
    }

    /** Push {@code free_tier = false} for a row that was open, so the gate stops funding it. */
    private void closeFreeTierMirror(ModelConfigOverrideEntity row) {
        if (row == null || !row.isFreeTierEnabled()) {
            return;
        }
        if (row.getPriceInput() == null && row.getPriceOutput() == null) {
            // Nothing was ever mirrored for a priceless row (the sync needs a price), so
            // there is nothing open to close.
            return;
        }
        boolean mirrored = authPricingSyncClient.sync(row.getProvider(), row.getModelId(),
                row.getPriceInput(), row.getPriceOutput(), row.getProviderKind(),
                row.getPriceCacheRead(), row.getPriceCacheWrite(), false);
        if (!mirrored) {
            log.error("Deleted the override for {}:{} but could NOT close it in the billing mirror."
                            + " The free-tier allowance may keep funding it until the row is"
                            + " re-created and closed, or the mirror is corrected directly.",
                    row.getProvider(), row.getModelId());
        }
    }

    /**
     * Evict every in-process cache that admin edits can invalidate:
     * <ul>
     *   <li>{@link CachedModelRateLimitProvider} - rate-limit values per (provider, modelId).
     *       Without this, a limit change takes up to 30 s to reach the limiter.</li>
     *   <li>{@link LlmCredentialRepository} hasDbKey - provider-gating boolean cached 60 s.
     *       Without this, re-enabling an API key leaves the provider hidden for up to a minute.</li>
     * </ul>
     * Frontend caches (5-min useModels) are client-owned; the admin panel calls
     * {@code clearModelsCache()} itself after each mutation.
     */
    private void invalidateModelCaches() {
        try {
            cachedRateLimitProvider.refreshCache();
        } catch (Exception e) {
            log.warn("Rate-limit cache refresh failed after admin edit: {}", e.getMessage());
        }
        try {
            credentialRepository.clearHasDbKeyCacheAll();
        } catch (Exception e) {
            log.warn("hasDbKey cache clear failed after admin edit: {}", e.getMessage());
        }
    }

    private void applyOverride(Map<String, Object> model, ModelConfigOverrideEntity override) {
        if (override.getDisplayName() != null) {
            model.put("name", override.getDisplayName());
        }
        if (override.getTier() != null) {
            model.put("tier", override.getTier());
        }
        if (override.getRanking() != null) {
            model.put("displayOrder", override.getRanking());
        }
        if (override.getRecommended() != null) {
            model.put("recommended", override.getRecommended());
        }
        if (override.getPriceInput() != null || override.getPriceOutput() != null) {
            // Defensive copy: the base "pricing" value can come from an
            // immutable Map.of(...) produced upstream (e.g. ProviderYaml
            // parsers returning singleton maps). Calling put() on that would
            // throw UnsupportedOperationException and blow the whole
            // /api/models endpoint for every admin with a price override set.
            @SuppressWarnings("unchecked")
            Map<String, Object> existing = (Map<String, Object>) model.get("pricing");
            Map<String, Object> pricing = existing == null ? new HashMap<>() : new HashMap<>(existing);
            if (override.getPriceInput() != null) pricing.put("input", override.getPriceInput().doubleValue());
            if (override.getPriceOutput() != null) pricing.put("output", override.getPriceOutput().doubleValue());
            model.put("pricing", pricing);
        }
        applyEnrichmentFields(model, override);
        applyRateLimitFields(model, override);
    }

    /**
     * Surface the V125-enriched columns (capabilities, context window, batch /
     * cache pricing, deprecation, mode, modalities) onto the model map so the
     * frontend picker can show user-useful badges (vision / tools / reasoning,
     * context size, deprecation banner) without a second round-trip. Called
     * from both the user-picker path ({@link #applyOverride}) and the custom-
     * model path ({@link #buildModelInfo}) so admin and runtime catalogs stay
     * structurally identical.
     *
     * <p>Null-skip semantics mirror {@link #applyRateLimitFields}: a null DB
     * column leaves the existing map value alone (YAML seed wins). Non-null
     * always overwrites - admin edits are the source of truth.
     */
    private void applyEnrichmentFields(Map<String, Object> model, ModelConfigOverrideEntity override) {
        // V493: which models a FREE-plan allowance may fund. Stamped HERE because this
        // is the ONE helper both payload builders run through - applyOverride (the
        // YAML-backed rows) and buildModelInfo (admin-added custom rows) - and both
        // catalog entry points therefore carry it: getEffectiveModelList (the admin
        // Models panel) and getModelsForCategory (what /api/v3/chat/models actually
        // serves the chat and agent pickers).
        //
        // Setting it in only one of those was the whole feature silently doing
        // nothing: the admin saw the chip lit, while the picker's free-tier-first
        // ordering matched no model, every row kept its upgrade badge, and the
        // "do not open on a model whose first turn is refused" guard never fired -
        // because the key was simply absent from the payload the frontend reads.
        // Unconditional, unlike the fields below, so the two catalog payloads agree in
        // shape wherever an override row exists. A model with NO override row never
        // reaches this helper at all and the key is genuinely absent - harmless, since
        // the client reads `=== true` and a model can only be opened by creating a row.
        model.put("freeTierEnabled", override.isFreeTierEnabled());
        if (override.getContextWindow() != null) {
            model.put("contextWindow", override.getContextWindow());
        }
        if (override.getMaxOutputTokens() != null) {
            model.put("maxOutputTokens", override.getMaxOutputTokens());
        }
        if (override.getSupportsTools() != null) {
            model.put("supportsTools", override.getSupportsTools());
        }
        if (override.getSupportsVision() != null) {
            model.put("supportsVision", override.getSupportsVision());
        }
        if (override.getSupportsPromptCaching() != null) {
            model.put("supportsPromptCaching", override.getSupportsPromptCaching());
        }
        if (override.getSupportsReasoning() != null) {
            model.put("supportsReasoning", override.getSupportsReasoning());
        }
        if (override.getDefaultReasoningEffort() != null) {
            model.put("defaultReasoningEffort", override.getDefaultReasoningEffort());
        }
        if (override.getSupportsComputerUse() != null) {
            model.put("supportsComputerUse", override.getSupportsComputerUse());
        }
        if (override.getSupportsResponseSchema() != null) {
            model.put("supportsResponseSchema", override.getSupportsResponseSchema());
        }
        if (override.getSupportsWebSearch() != null) {
            model.put("supportsWebSearch", override.getSupportsWebSearch());
        }
        if (override.getMode() != null) {
            model.put("mode", override.getMode());
        }
        if (override.getModalities() != null) {
            model.put("modalities", override.getModalities());
        }
        if (override.getPriceInputBatch() != null) {
            model.put("priceInputBatch", override.getPriceInputBatch().doubleValue());
        }
        if (override.getPriceOutputBatch() != null) {
            model.put("priceOutputBatch", override.getPriceOutputBatch().doubleValue());
        }
        if (override.getPriceCacheRead() != null) {
            model.put("priceCacheRead", override.getPriceCacheRead().doubleValue());
        }
        if (override.getPriceCacheWrite() != null) {
            model.put("priceCacheWrite", override.getPriceCacheWrite().doubleValue());
        }
        if (override.getDeprecatedAt() != null) {
            model.put("deprecatedAt", override.getDeprecatedAt().toString());
        }
        if (override.getDeprecationDate() != null) {
            model.put("deprecationDate", override.getDeprecationDate().toString());
        }
        if (override.getReleaseDate() != null) {
            model.put("releaseDate", override.getReleaseDate().toString());
        }
        // providerKind on the model row - the picker reads it to render the
        // bridge / BYOK badge next to a model name. The legacy
        // markBridgeProviders() loop only tags the provider, but the typed
        // AIModel surface on the frontend exposes it on each option, so we
        // must mirror it here. inferProviderKind() falls back to "byok" when
        // the override row carries nothing, which is the platform-wide default.
        if (override.getProviderKind() != null) {
            model.put("providerKind", override.getProviderKind());
        }
    }

    /**
     * Per-field merge of DB override onto the YAML-seeded base.
     *
     * <p>Non-null DB columns ALWAYS win over the YAML seed (admin edits take
     * precedence). Null DB columns preserve the YAML value - "clear" in the
     * admin UI (via {@link #saveOverride} with all rate-limit fields explicitly
     * set to null) means "revert to the application.yml default", not "disable".
     *
     * <p>Mirrors the runtime merge semantics in
     * {@code CachedModelRateLimitProvider.refreshCache()} so the admin panel
     * and the actual limiter stay consistent.
     */
    private void applyRateLimitFields(Map<String, Object> model, ModelConfigOverrideEntity override) {
        if (override.getRateLimitTpm() != null) {
            model.put("rateLimitTpm", override.getRateLimitTpm());
        }
        if (override.getRateLimitRpm() != null) {
            model.put("rateLimitRpm", override.getRateLimitRpm());
        }
        if (override.getRateLimitTpmPerTenant() != null) {
            model.put("rateLimitTpmPerTenant", override.getRateLimitTpmPerTenant());
        }
        if (override.getRateLimitRpmPerTenant() != null) {
            model.put("rateLimitRpmPerTenant", override.getRateLimitRpmPerTenant());
        }
    }

    private Map<String, Object> buildModelInfo(ModelConfigOverrideEntity entity) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", entity.getModelId());
        model.put("name", entity.getDisplayName() != null ? entity.getDisplayName() : entity.getModelId());
        model.put("provider", entity.getProvider());
        model.put("isDefault", false);
        model.put("displayOrder", entity.getRanking() != null ? entity.getRanking() : 999);
        model.put("tier", entity.getTier());
        model.put("recommended", Boolean.TRUE.equals(entity.getRecommended()));
        model.put("isCustom", true);
        if (entity.getPriceInput() != null || entity.getPriceOutput() != null) {
            Map<String, Object> pricing = new HashMap<>();
            pricing.put("input", entity.getPriceInput() != null ? entity.getPriceInput().doubleValue() : 0.0);
            pricing.put("output", entity.getPriceOutput() != null ? entity.getPriceOutput().doubleValue() : 0.0);
            model.put("pricing", pricing);
        }
        applyEnrichmentFields(model, entity);
        return model;
    }

    private boolean isBridgeConnected() {
        if (bridgeUrl == null || bridgeUrl.isBlank()) return false;
        try {
            RestTemplate rt = new RestTemplateBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .readTimeout(Duration.ofSeconds(2))
                    .build();
            rt.getForEntity(bridgeUrl + "/health", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Drop bridge providers (claude-code/codex/gemini-cli/mistral-vibe) from
     * a model list when their CLI binary isn't installed on the bridge host.
     * Delegates to the shared {@link BridgeAvailabilityFilter} so the CE
     * monolith stub controller goes through the EXACT same code path.
     */
    private void filterUnavailableBridgeProviders(Map<String, Object> base) {
        bridgeAvailabilityFilter.filter(base);
    }

    /**
     * Drop every provider an admin has switched off entirely (V508).
     *
     * <p>Applied to the PICKER catalogue only. The admin list keeps showing a disabled
     * provider, or there would be no way to switch it back on, and each model's own flag is
     * left alone so turning the provider on again restores the curated selection rather than
     * enabling everything.
     *
     * <p>Defaults are recomputed for the same reason the bridge filter recomputes them: a
     * picker whose default names a provider that is no longer in the list has no valid
     * selection at all.
     */
    private void filterDisabledProviders(Map<String, Object> base, Set<String> disabled) {
        if (disabled.isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) base.get("providers");
        if (providers == null) {
            return;
        }
        boolean removed = providers.removeIf(p -> {
            String name = (String) p.get("name");
            return name != null && disabled.contains(name.toLowerCase(Locale.ROOT));
        });
        if (removed) {
            recalculateDefaults(base);
        }
    }

    /**
     * The providers switched off, lower-cased. Only exceptions are stored, so this is empty on
     * an install where nobody has touched the switch.
     *
     * <p>Reads through on every catalogue build rather than caching: the table holds a handful
     * of rows at most, on a path that already loads every model override. An unreadable table
     * leaves every provider ON, which is the same direction every other gate here fails and
     * the only one that cannot make the picker mysteriously empty.
     */
    private Set<String> disabledProviders() {
        if (providerSettingsRepository == null) {
            return Set.of();
        }
        try {
            Set<String> off = new HashSet<>();
            for (ModelProviderSettingsEntity row : providerSettingsRepository.findAll()) {
                if (Boolean.FALSE.equals(row.getEnabled()) && row.getProvider() != null) {
                    off.add(row.getProvider().toLowerCase(Locale.ROOT));
                }
            }
            return off;
        } catch (Exception e) {
            log.warn("Provider switches unreadable, treating every provider as enabled: {}",
                    e.getMessage());
            return Set.of();
        }
    }

    /**
     * The providers an admin has switched off, lower-cased, for the Models panel.
     *
     * <p>Only the exceptions: everything absent from this list is on. The panel already knows
     * the full provider list from the catalogue, so sending the short list keeps the two from
     * disagreeing about which providers exist.
     */
    public List<String> disabledProviderNames() {
        return new ArrayList<>(disabledProviders());
    }

    /**
     * Switch a whole provider on or off.
     *
     * <p>Stores only exceptions: switching one back ON deletes its row rather than writing
     * {@code true}, so the table stays the short list of what an admin has deliberately
     * removed instead of growing to catalogue size saying nothing.
     */
    @Transactional
    public void setProviderEnabled(String provider, boolean enabled) {
        String name = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        // The length bound is the column's (VARCHAR(64)): without it an over-long name is a
        // constraint violation at flush, which reaches the admin as a 500 rather than as the
        // 400 that says what is wrong.
        if (name.length() > 64 || !name.matches("^[a-z][a-z0-9_-]*$")) {
            throw new IllegalArgumentException("Invalid provider name: " + provider);
        }
        if (providerSettingsRepository == null) {
            throw new IllegalStateException("Provider switches are not available on this install");
        }
        if (enabled) {
            providerSettingsRepository.deleteById(name);
        } else {
            providerSettingsRepository.save(new ModelProviderSettingsEntity(name, false));
        }
        invalidateModelCaches();
    }

    /**
     * Effective mode-filter key for a (possibly null) category. The legacy
     * GLOBAL path ({@code category == null}) backs both the admin "Chat / Agent"
     * tab and the main chat picker / flat LLM catalog - it must apply the SAME
     * mode eligibility as the explicit {@code chat} category so image-gen rows
     * ({@code mode='image'}) never leak into a chat / browser_agent completion
     * list. Used for the mode-filter ONLY - the per-category sidecar overlay
     * stays gated to a non-null category so the null path keeps the legacy
     * global ranking/enabled (no sidecar lookup, no stale-backfill divergence).
     */
    private static String modeFilterKey(String category) {
        return category != null ? category : ModelCategory.CHAT.key();
    }

    /**
     * V156 - drop YAML/bridge-derived models whose mode is not eligible for
     * the active category. Without this, the YAML catalog (chat models seeded
     * from {@code application.yml}) leaks into a {@code <format>_generation}
     * category because the YAML rows have no sidecar entry and no DB override;
     * the mode predicate is the only eligibility signal.
     *
     * <p><b>Empty provider shells are preserved on purpose</b>. Image-gen rows
     * land in {@code model_config_overrides} as DB-only entries with
     * {@code is_custom=false} and {@code mode='image'} (V157 seed). The
     * downstream injection loop in {@link #getModelsWithOverrides()} /
     * {@link #getEffectiveModelList(String)} only adds those rows to providers
     * already present in the YAML base, so dropping the openai/google provider
     * shells here would leave the gpt-image-* / gemini-*-image rows nowhere to
     * land. Keeping the shell with an empty {@code models[]} lets the injection
     * step refill it. Note that no screen asks for a
     * {@code <format>_generation} category any more, so today this only matters
     * to a caller that asks the API for one directly.
     */
    @SuppressWarnings("unchecked")
    private void filterProvidersByCategoryMode(Map<String, Object> base, String category) {
        List<Map<String, Object>> providers = (List<Map<String, Object>>) base.get("providers");
        if (providers == null) return;
        for (Map<String, Object> provider : providers) {
            List<Map<String, Object>> models = (List<Map<String, Object>>) provider.get("models");
            if (models == null) continue;
            // A YAML-derived row carries the mode its PROVIDER declares
            // (LLMProvider.getModelMode, stamped by LLMProviderFactory), which is null
            // for every chat provider and therefore chat-eligible - the legacy default.
            // Before that existed the key was absent here for every YAML row, so this
            // filter read null unconditionally and a non-chat provider declared in YAML
            // survived it: dropping its DB override from the overlay does NOT remove the
            // YAML model, so it stayed in the chat picker and the default-model pick.
            models.removeIf(m -> !ModelCategory.acceptsMode(category, (String) m.get("mode")));
        }
    }

    /**
     * Return provider catalog filtered to only include providers that are
     * actually usable: either configured via env key OR have a DB-stored key.
     * This is the single base for both the admin Models tab and the runtime
     * model picker, ensuring both see the same set of providers.
     */
    /**
     * Decide which {@code provider_kind} row to write when inserting a new
     * override. Bridges are detected by name (single source of truth -
     * {@link BridgeAvailabilityFilter#BRIDGE_PROVIDER_TO_CLI_ID}). A caller-
     * supplied kind wins when it's {@code cloud} (the cloud proxy provider
     * populates this); anything else falls back to {@code byok}.
     */
    static String inferProviderKind(String provider, String requested) {
        if (provider != null
                && BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.containsKey(provider.toLowerCase())) {
            return "bridge";
        }
        if ("cloud".equalsIgnoreCase(requested)) {
            return "cloud";
        }
        return "byok";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getAvailableProvidersBase() {
        return getAvailableProvidersBase(null);
    }

    private Map<String, Object> getAvailableProvidersBase(String tenantId) {
        return getAvailableProvidersBase(tenantId, false);
    }

    /**
     * Build the base provider catalog from {@link LLMProviderFactory#getAllModelsInfoAdmin()}.
     *
     * <p>{@code includeUnconfigured == false} (the runtime / picker default):
     * drop every provider that is neither key-configured (env key or DB key)
     * nor cloud-relay-supported in CLOUD mode - so a chat/agent selector never
     * offers a model the runtime can't actually execute (would 403).
     *
     * <p>{@code includeUnconfigured == true} (admin config view only): keep
     * EVERY provider, even those without any key and regardless of cloud/CE
     * mode, so the {@code /settings/ai-providers} Models panel can list, rank,
     * price and enable the full catalog on every category tab BEFORE any key is
     * configured. Admin-gated ({@code getEffectiveModelList} only) and never
     * feeds the end-user picker, so unconfigured models can't leak into a
     * chat/agent selector.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getAvailableProvidersBase(String tenantId, boolean includeUnconfigured) {
        Map<String, Object> base = llmProviderFactory.getAllModelsInfoAdmin();
        if (includeUnconfigured) {
            return base;
        }
        List<Map<String, Object>> allProviders = (List<Map<String, Object>>) base.get("providers");
        boolean cloudSelected = isCloudSelected(tenantId);
        if (allProviders != null) {
            allProviders.removeIf(p -> !isProviderRuntimeAvailable(
                    (String) p.get("name"), Boolean.TRUE.equals(p.get("configured")), cloudSelected));
        }
        return base;
    }

    /**
     * Whether the picker/runtime would offer this provider under the tenant's
     * LLM source - the exact keep-predicate the picker filter uses:
     * <ul>
     *   <li>CLOUD source: relay-supported API providers are available (the bound
     *       cloud account executes them); local CLI/bridge providers follow their
     *       own key/availability rule below.</li>
     *   <li>otherwise (BYOK / cloud-prod): an env key ({@code configured}) OR a
     *       DB/BYOK key is required.</li>
     * </ul>
     *
     * <p>Reused by {@link #getAvailableProvidersBase} (to DROP unavailable
     * providers from the picker) and by {@link #getEffectiveModelList} (to
     * ANNOTATE each admin row's {@code available} flag WITHOUT dropping - the
     * admin config panel lists the full catalog so every model can be ranked and
     * priced before its key exists). {@code hasDbKey} is evaluated eagerly in the
     * non-cloud branch, matching the historical picker contract.
     */
    private boolean isProviderRuntimeAvailable(String name, boolean configured, boolean cloudSelected) {
        if (cloudSelected && !isBridgeProviderName(name)) {
            return CloudRelaySupport.isSupportedProvider(name);
        }
        boolean hasDbKey = credentialRepository.hasDbKey(name);
        return configured || hasDbKey;
    }

    private boolean isCloudSelected(String tenantId) {
        return cloudLlmRuntimeAccess != null
                && tenantId != null
                && !tenantId.isBlank()
                && cloudLlmRuntimeAccess.isCloudSelected(tenantId);
    }

    private static boolean isBridgeProviderName(String providerName) {
        return providerName != null
                && BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.containsKey(providerName.toLowerCase());
    }

    /**
     * Apply the V156 sidecar (per-category rank + enabled) on top of a list of
     * model_config_overrides rows. Returns DETACHED clones so downstream
     * mutation (e.g. inside the existing apply loop) doesn't flush back to DB.
     *
     * <p>Resolution per row:
     * <ul>
     *   <li>sidecar absent → original entity returned unchanged</li>
     *   <li>sidecar.rank set → clone with {@code ranking = sidecar.rank}</li>
     *   <li>sidecar.enabled set → clone with {@code enabled = sidecar.enabled};
     *       false propagates to the standard remove-when-disabled path in
     *       {@link #getModelsForCategory(String)}.</li>
     * </ul>
     *
     * <p>The output list is re-sorted by overlaid ranking so callers that
     * iterate in display order get the correct sequence without an extra sort.
     */
    private List<ModelConfigOverrideEntity> applyCategoryOverlay(
            List<ModelConfigOverrideEntity> source, String category) {
        List<ModelCategorySettingsEntity> sidecarRows = categoryRepository.findByCategory(category);
        if (sidecarRows.isEmpty()) return source;

        Map<Long, ModelCategorySettingsEntity> overlay = sidecarRows.stream()
                .collect(Collectors.toMap(ModelCategorySettingsEntity::getModelConfigId,
                        java.util.function.Function.identity(),
                        (a, b) -> a));

        List<ModelConfigOverrideEntity> out = new ArrayList<>(source.size());
        for (ModelConfigOverrideEntity orig : source) {
            ModelCategorySettingsEntity setting = orig.getId() == null ? null : overlay.get(orig.getId());
            if (setting == null) {
                out.add(orig);
                continue;
            }
            ModelConfigOverrideEntity copy = cloneOverride(orig);
            if (setting.getRank() != null) copy.setRanking(setting.getRank());
            copy.setEnabled(setting.getEnabled() == null ? Boolean.TRUE : setting.getEnabled());
            out.add(copy);
        }
        out.sort(Comparator.comparing(
                (ModelConfigOverrideEntity e) -> e.getRanking() == null ? Integer.MAX_VALUE : e.getRanking()));
        return out;
    }

    /**
     * Shallow detached copy carrying every field consumed by the merge
     * pipeline ({@link #applyOverride}, {@link #applyRateLimitFields},
     * {@link #buildModelInfo}, {@link #getEffectiveModelList}). Fields not
     * read in any read-path are intentionally skipped to keep the helper small.
     */
    private static ModelConfigOverrideEntity cloneOverride(ModelConfigOverrideEntity src) {
        ModelConfigOverrideEntity c = new ModelConfigOverrideEntity();
        c.setId(src.getId());
        c.setProvider(src.getProvider());
        c.setModelId(src.getModelId());
        c.setEnabled(src.getEnabled());
        c.setDisplayName(src.getDisplayName());
        c.setDescription(src.getDescription());
        c.setTier(src.getTier());
        c.setRanking(src.getRanking());
        c.setRecommended(src.getRecommended());
        c.setPriceInput(src.getPriceInput());
        c.setPriceOutput(src.getPriceOutput());
        c.setRateLimitTpm(src.getRateLimitTpm());
        c.setRateLimitRpm(src.getRateLimitRpm());
        c.setRateLimitTpmPerTenant(src.getRateLimitTpmPerTenant());
        c.setRateLimitRpmPerTenant(src.getRateLimitRpmPerTenant());
        c.setSource(src.getSource());
        c.setProviderKind(src.getProviderKind());
        c.setDeprecatedAt(src.getDeprecatedAt());
        c.setMode(src.getMode());
        c.setCustom(src.isCustom());
        // V493: freeTierEnabled is now read in a read-path (applyEnrichmentFields), so
        // by this method's own rule it must be carried. Without it, a category tab that
        // has a sidecar row renders the chip OFF for a model whose column is TRUE - and
        // the default Chat/Agent tab is category=null, which takes the no-sidecar path,
        // so the lie only shows on the tabs nobody checks first.
        c.setFreeTierEnabled(src.isFreeTierEnabled());
        // Same omission, pre-existing: bundleEnabled is read by CatalogBundlePayload and
        // rendered by the admin panel, and was never copied here either.
        c.setBundleEnabled(src.getBundleEnabled());
        return c;
    }

    @SuppressWarnings("unchecked")
    private void enrichWithBridgeProviders(Map<String, Object> base) {
        // When bridge is connected, add CLI-based providers (claude-code, codex, gemini-cli, mistral-vibe)
        Map<String, Object> allProviders = llmProviderFactory.getAllModelsInfoAdmin();
        List<Map<String, Object>> existingProviders = (List<Map<String, Object>>) base.getOrDefault("providers", new ArrayList<>());
        List<Map<String, Object>> allProvidersList = (List<Map<String, Object>>) allProviders.getOrDefault("providers", List.of());

        Set<String> existingNames = new HashSet<>();
        for (Map<String, Object> p : existingProviders) {
            existingNames.add((String) p.get("name"));
        }

        // Add only bridge providers - NOT the API providers (anthropic, openai, etc.).
        // The set comes from BridgeAvailabilityFilter so it stays single-source
        // with the availability filter; adding a new bridge provider only
        // requires editing one map.
        Set<String> bridgeProviderNames = BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.keySet();
        for (Map<String, Object> provider : allProvidersList) {
            String name = (String) provider.get("name");
            if (bridgeProviderNames.contains(name) && !existingNames.contains(name)) {
                provider.put("source", "bridge");
                existingProviders.add(provider);
            }
        }
        base.put("providers", existingProviders);
    }
}

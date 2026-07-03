package com.apimarketplace.agent.service;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudRelaySupport;
import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.ModelCategory;
import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelCategorySettingsId;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.domain.ReasoningEffort;
import com.apimarketplace.agent.domain.ReasoningEffortResolver;
import com.apimarketplace.agent.factory.BridgeAvailabilityFilter;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
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
     * Shared filter (lives in shared-agent-lib so the CE monolith stub can
     * reuse the same code path). Lazily-initialised on first use because
     * `bridgeUrl` is read from config - we capture the value at construction.
     */
    private final BridgeAvailabilityFilter bridgeAvailabilityFilter;

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
                                      @Value("${models.bridge-availability.strict:true}") boolean bridgeAvailabilityStrict) {
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
        this(repository, categoryRepository, llmProviderFactory, credentialRepository,
                cachedRateLimitProvider, bridgeUrl, authPricingSyncClient, false);
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
     * <p>Categories supported: {@code chat}, {@code browser_agent},
     * {@code image_generation}, plus any future category that matches the
     * V156 shape CHECK ({@link ModelCategory#isValidShape(String)}).
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

    private Map<String, Object> getModelsForCategory(String category, String tenantId, boolean includeUnconfigured) {
        Map<String, Object> base = getAvailableProvidersBase(tenantId, includeUnconfigured);
        filterUnavailableBridgeProviders(base);
        // V156/V158: filter the YAML/Bridge base catalog to only models whose
        // mode matches the category contract - so the image_generation tab shows
        // ONLY image-gen models (mode='image'), and chat / browser_agent
        // completion lists do NOT show image-gen rows. The null/global path here
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
        for (var entry : customByProvider.entrySet()) {
            if (existingProviders.contains(entry.getKey())) continue;
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
        boolean cloudMode = "CLOUD".equals(base.get("llmSource"));
        applyBest(base, providers,
                cloudMode ? p -> !"bridge".equals(p.get("providerKind")) : p -> true,
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
        // V156: scope the YAML/bridge base + overrides by category mode so the
        // admin tab shows ONLY models eligible for that category (image-gen
        // tab → mode='image' rows only ; chat/browser_agent → mode IS NULL
        // OR mode='chat'). This is the same filter applied by
        // getModelsForCategory() so picker and admin stay in sync.
        // V156/V158: scope the base + overrides by category mode. The admin
        // Chat / Agent tab reads the null/global view (to keep chat's writes on
        // the global ranking/enabled columns), but it must STILL drop image-gen
        // rows (mode='image') - they belong only to the Image Generation tab. So
        // apply the mode-filter for the null path too (treated as 'chat'). The
        // per-category sidecar OVERLAY stays gated to a non-null category -
        // overlaying the V156-backfilled chat sidecar here would diverge from the
        // global ranking the Chat tab writes through.
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
                entry.put("providerKind",
                        override != null && override.getProviderKind() != null
                                ? override.getProviderKind()
                                : inferProviderKind(providerName, null));
                if (cliId != null) {
                    entry.put("bridgeAvailable", bridgeAvailable);
                }
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
                    entry.put("providerKind",
                            custom.getProviderKind() != null ? custom.getProviderKind() : "byok");
                    if (cliId != null) {
                        entry.put("bridgeAvailable", bridgeAvailable);
                    }
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
                    e.put("providerKind",
                            custom.getProviderKind() != null ? custom.getProviderKind() : "byok");
                    if (cliId != null) {
                        e.put("bridgeAvailable", bridgeAvailable);
                    }
                    result.add(e);
                }
            }
        }

        // Sort by displayOrder
        result.sort(Comparator.comparingInt(m -> (int) ((Map<String, Object>) m).getOrDefault("displayOrder", 999)));

        return result;
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

        ModelConfigOverrideEntity saved = repository.save(entity);

        // Sync pricing into auth.model_pricing for any row that carries a price -
        // bridges included. Since V130 bridges store the underlying cloud model's
        // list price and CreditService bills them at that rate (see
        // CreditService.consumeForChat Javadoc). The providerKind is propagated so
        // the billing mirror keeps the catalog-origin discriminator for reporting.
        if (saved.getPriceInput() != null || saved.getPriceOutput() != null) {
            authPricingSyncClient.sync(saved.getProvider(), saved.getModelId(),
                    saved.getPriceInput(), saved.getPriceOutput(),
                    saved.getProviderKind());
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
        // be silently skipped on browser_agent / image_generation re-rank,
        // and the admin's drag-and-drop would not persist for those rows.
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
        ModelConfigOverrideEntity parent = repository.findByProviderAndModelId(provider, modelId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown model: " + provider + ":" + modelId));

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
            Map<String, Object> base = getAvailableProvidersBase();
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

    @Transactional
    public void deleteOverride(String provider, String modelId) {
        repository.deleteByProviderAndModelId(provider, modelId);
        invalidateModelCaches();
    }

    @Transactional
    public void resetAll() {
        repository.deleteAll();
        invalidateModelCaches();
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
     * V156 - drop YAML/bridge-derived models whose mode is not eligible for
     * the active category. Without this, the YAML catalog (chat models seeded
     * from {@code application.yml}) leaks into the {@code image_generation}
     * tab because the YAML rows have no sidecar entry and no DB override; the
     * mode predicate is the only eligibility signal.
     *
     * <p><b>Empty provider shells are preserved on purpose</b>. Image-gen rows
     * land in {@code model_config_overrides} as DB-only entries with
     * {@code is_custom=false} and {@code mode='image'} (V157 seed). The
     * downstream injection loop in {@link #getModelsWithOverrides()} /
     * {@link #getEffectiveModelList(String)} only adds those rows to providers
     * already present in the YAML base. If we removed the openai/google
     * provider shells here just because their YAML models are all chat, the
     * gpt-image-* / gemini-*-image rows would have nowhere to land and the
     * admin tab would render "No models configured" even though V157 ran.
     * Keeping the shell with an empty {@code models[]} lets the injection
     * step refill it with the eligible DB rows.
     */
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

    @SuppressWarnings("unchecked")
    private void filterProvidersByCategoryMode(Map<String, Object> base, String category) {
        List<Map<String, Object>> providers = (List<Map<String, Object>>) base.get("providers");
        if (providers == null) return;
        for (Map<String, Object> provider : providers) {
            List<Map<String, Object>> models = (List<Map<String, Object>>) provider.get("models");
            if (models == null) continue;
            // YAML-derived rows do NOT carry a 'mode' field today (the column
            // landed in V125 on the DB side only). Treat absent as null and
            // let acceptsMode decide - chat-eligibility of a YAML row is the
            // legacy default for chat/browser_agent and excludes image-gen.
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

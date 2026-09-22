package com.apimarketplace.catalog.tools.generation;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.agent.tools.common.ToolResultPersistEnricher;
import com.apimarketplace.catalog.service.generation.GenerationAssetResolver;
import com.apimarketplace.catalog.service.generation.GenerationInputs;
import com.apimarketplace.catalog.service.generation.GenerationLimits;
import com.apimarketplace.catalog.service.generation.GenerationInputResolver;
import com.apimarketplace.catalog.service.ResponseShaper;
import com.apimarketplace.catalog.service.ToolExecutionManager;
import com.apimarketplace.catalog.service.generation.DynamicOptionsResolver;
import com.apimarketplace.catalog.service.generation.GenerationProvenanceRecorder;
import com.apimarketplace.catalog.service.generation.GenerationRegistry;
import com.apimarketplace.catalog.service.generation.PlatformSalesResolver;
import com.apimarketplace.catalog.service.generation.GenerationRequestBuilder;
import com.apimarketplace.catalog.service.generation.GenerationSpec;
import com.apimarketplace.catalog.tools.CatalogExecuteModule;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.ImageGenerationInterfaceRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runtime of the unified {@code generation} tool.
 *
 * <p>ONE tool covers every format. The caller names a model
 * ({@code seedance-2.0-fast}, {@code eleven-v3}, {@code gpt-image-2}) and the
 * registry resolves everything else: which endpoint runs it, which provider
 * owns the credential, which parameters it accepts and what it costs. Onboarding
 * a provider is a JSON descriptor plus an import, and no surface changes.
 *
 * <p><b>Execution reuses the catalog path.</b> Once the request is projected,
 * the call is handed to {@link CatalogExecuteModule}, which already resolves
 * platform-versus-user credentials, enforces tool restrictions and approvals,
 * reserves and commits credits, and stores a returned binary as a file. A
 * second execution path would be a second set of those guarantees to keep
 * correct, so there is deliberately only one.
 */
@Slf4j
@Component
public class GenerationModule implements ToolModule {

    static final Set<String> HANDLED_ACTIONS = Set.of("create", "models", "generate", "options");

    /**
     * Type discriminator of the chat card, deliberately still the historical
     * one. It is what the frontend's visualize renderer matches on and what
     * interface-service stores rows under, and the card it draws is already
     * format-neutral. Coining a new type here would need a frontend that knows
     * it, and would leave every card written before that day unreadable.
     */
    static final String CARD_TYPE = "image_generation";

    /** Card titles are a chat-list line, not a document: longer prompts are cut. */
    private static final int CARD_NAME_MAX = 80;

    private final GenerationRegistry registry;
    private final CatalogExecuteModule executeModule;
    private final GenerationAssetResolver assetResolver;
    private final ResponseShaper responseShaper;
    private final GenerationInputResolver inputResolver;
    private final DynamicOptionsResolver optionsResolver;
    private final PlatformSalesResolver platformSales;
    private final InterfaceClient interfaceClient;
    private final GenerationProvenanceRecorder provenanceRecorder;

    /**
     * Used to refuse a generation BEFORE the provider is called, when the account has no storage
     * room left. Field-injected and optional so the existing constructor and its tests stay as
     * they are; null simply means no pre-check, which is the behaviour that shipped before.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.storage.client.StorageClient storageClient;

    public GenerationModule(GenerationRegistry registry,
                             CatalogExecuteModule executeModule,
                             GenerationAssetResolver assetResolver,
                             ResponseShaper responseShaper,
                             GenerationInputResolver inputResolver,
                             DynamicOptionsResolver optionsResolver,
                             PlatformSalesResolver platformSales,
                             InterfaceClient interfaceClient,
                             GenerationProvenanceRecorder provenanceRecorder) {
        this.registry = registry;
        this.executeModule = executeModule;
        this.assetResolver = assetResolver;
        this.responseShaper = responseShaper;
        this.inputResolver = inputResolver;
        this.optionsResolver = optionsResolver;
        this.platformSales = platformSales;
        this.interfaceClient = interfaceClient;
        this.provenanceRecorder = provenanceRecorder;
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of(); // definitions live on GenerationToolsProvider
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if ("models".equals(action)) {
            return Optional.of(listModels(parameters));
        }
        if ("options".equals(action)) {
            return Optional.of(listOptions(parameters, tenantId, context));
        }
        // 'generate' is the legacy verb from the image-only tool. Same behaviour.
        if ("create".equals(action) || "generate".equals(action)) {
            return Optional.of(create(parameters, tenantId, context));
        }
        return Optional.empty();
    }
    // ── options ─────────────────────────────────────────────────────────────

    /**
     * What a parameter accepts, when only the provider can say.
     *
     * <p>An ElevenLabs voice belongs to the account holding the key, so no list
     * can ship in the catalogue and no identifier can be guessed: it is twenty
     * opaque characters, and a wrong one fails at the provider AFTER the call is
     * charged. This asks, with the caller's own credential.
     *
     * <p>Separate from {@code models} on purpose. That answer is one snapshot
     * shared by every reader; this one differs per account and per key, and
     * costs a call to the provider. An agent reads {@code models} first, sees
     * which parameters carry {@code optionsAvailable}, and asks about those.
     */
    private ToolExecutionResult listOptions(Map<String, Object> parameters,
                                            String tenantId, ToolExecutionContext context) {
        String modelId = str(parameters, "model");
        String parameter = str(parameters, "parameter");
        if (modelId == null || parameter == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "model and parameter are both required: action='options' answers for ONE "
                    + "parameter of ONE model. action='models' lists the ids and marks the "
                    + "parameters worth asking about with optionsAvailable.");
        }
        GenerationRegistry.GenerationModel target = registry.resolve(modelId).orElse(null);
        if (target == null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "unknown model '" + modelId + "'. action='models' lists the ids.");
        }
        // The UNIFIED name is what a caller knows ('voice'); the catalogue knows
        // the provider's own ('voice_id'). Translating here means a caller never
        // has to learn the second.
        GenerationSpec.ParamBinding binding = target.spec().paramMap().get(parameter);
        if (binding == null || !target.model().capabilities().contains(parameter)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "model '" + modelId + "' does not accept '" + parameter + "'. Its accepts "
                    + "list in action='models' is the set it takes.");
        }

        String source = str(parameters, CatalogExecuteModule.CREDENTIAL_SOURCE_KEY);
        // WHICH of the caller's keys, when the surface pinned one. It reaches the
        // fetch either way (it rides the context, which the delegated execute
        // reads), so leaving it out of the cache key alone would serve one key's
        // voices under another's: a reader with two ElevenLabs keys switches from
        // A to B and gets A's list for five minutes, which is exactly the
        // "list read on one key, run dispatched on another" this exists to stop.
        String credentialId = pinnedCredentialId(context);
        // What the caller's configuration is ABOUT, for a source that answers
        // with every model at once. The upstream id is the one such an endpoint
        // keys its rows on, because it is the one the provider itself receives;
        // our public id ('eleven-v3' vs 'eleven_v3') would match nothing.
        Map<String, String> knownValues = Map.of("model",
                target.model().upstream() != null ? target.model().upstream() : target.modelId());
        // A SCALED binding writes a different unit from the one this parameter is
        // named in: duration_seconds reaching music_length_ms multiplies by 1000,
        // so the provider's own values are milliseconds and handing one back to be
        // passed as duration_seconds would ask for a half-minute clip as 30000.
        // The two listing paths already refuse it; this is the one that hands the
        // values over.
        if (binding.scale() != null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "'" + parameter + "' cannot be listed for model '" + modelId + "': this endpoint "
                    + "takes it in a different unit from the one the name states, so the provider's "
                    + "own values are not values you could pass back. Read its limits in "
                    + "action='models' instead.");
        }
        DynamicOptionsResolver.Resolution resolution = optionsResolver.resolve(
                target.apiToolId(), binding.path(), tenantId, source, credentialId, knownValues,
                (sourceToolId, credSource, credId) -> fetchSource(sourceToolId, credSource, tenantId, context));

        if (!resolution.isAvailable()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    unavailableMessage(resolution.unavailable(), target, parameter));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", modelId);
        data.put("parameter", parameter);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DynamicOptionsResolver.Option o : resolution.options()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("value", o.value());
            row.put("label", o.label());
            rows.add(row);
        }
        data.put("options", rows);
        data.put("count", rows.size());
        if (resolution.truncated()) {
            data.put("truncated", true);
            data.put("total_count", resolution.totalCount());
        }
        // WHICH key answered. Voices differ per key, so a list read on one and a
        // run dispatched on another offers an id the provider never had. Stated
        // so a caller can see the two agree.
        if (resolution.credentialSource() != null) {
            data.put("credential_source", resolution.credentialSource());
        }
        data.put("note", "These values belong to the provider account behind the key named in "
                + "credential_source. Pass one as '" + parameter + "' to action='create' with the "
                + "SAME credential_source: another key has different ones.");
        return ToolExecutionResult.success(data);
    }

    /** Why there is nothing to offer, in terms of what the caller can do next. */
    private String unavailableMessage(DynamicOptionsResolver.Unavailable reason,
                                      GenerationRegistry.GenerationModel target, String parameter) {
        switch (reason) {
            case NOT_DYNAMIC:
                return "'" + parameter + "' has no list to fetch here: either this endpoint "
                        + "declares no source for it, in which case any value the model accepts may "
                        + "be sent, or more than one of its parameters claims that field and none "
                        + "of them can be called its owner. Read its limits in action='models', "
                        + "which is what this platform can state about it either way.";
            case NO_CREDENTIAL:
                return "no " + target.apiName() + " key is connected, so its account cannot be "
                        + "asked what '" + parameter + "' accepts. Connecting one is the account "
                        + "owner's act: report this rather than retrying.";
            case NOT_LISTED:
                // The one empty answer that is not about an empty account, and
                // the one a blank field describes worst: the model is simply
                // absent from what this key can see (limited release, or an
                // account without access to it).
                return target.apiName() + " does not list '" + target.modelId() + "' for the key "
                        + "that answered, so it cannot say what '" + parameter + "' accepts for "
                        + "it. Either that model is unavailable to this account, or another key "
                        + "should be used. Any other model's values would not apply to it.";
            default:
                return "the " + target.apiName() + " account could not be asked what '" + parameter
                        + "' accepts. Nothing was charged. Ask the person for the value rather "
                        + "than trying identifiers.";
        }
    }

    /**
     * Run the source endpoint down the SAME delegated path a generation takes.
     *
     * <p>Not a direct call: this way the restriction list, the credential
     * pre-flight and the credential-mode resolution are the ones the run itself
     * would face, so a list can never come from a key the run would not use.
     */
    private DynamicOptionsResolver.SourceFetcher.Answer fetchSource(
            java.util.UUID sourceToolId, String credentialSource,
            String tenantId, ToolExecutionContext context) {
        Map<String, Object> delegated = new LinkedHashMap<>();
        delegated.put("tool_id", sourceToolId.toString());
        delegated.put("params", Map.of());
        if (credentialSource != null) {
            delegated.put(CatalogExecuteModule.CREDENTIAL_SOURCE_KEY, credentialSource);
        }

        Optional<ToolExecutionResult> raw =
                executeModule.execute("execute", delegated, tenantId, unbilled(context));
        if (raw.isEmpty()) {
            return new DynamicOptionsResolver.SourceFetcher.Answer(false, null, null, false);
        }
        ToolExecutionResult result = raw.get();
        if (!result.success()) {
            String message = result.error() == null ? "" : result.error();
            boolean missingKey = message.contains(CatalogExecuteModule.CREDENTIALS_REQUIRED_CODE);
            return new DynamicOptionsResolver.SourceFetcher.Answer(false, null, null, missingKey);
        }
        // The credential pre-flight answers with a SUCCESS that means the
        // opposite of one when no key is connected: nothing ran, and the payload
        // is an approval prompt rather than the endpoint's answer.
        if (approvalNeededPayload(result) != null) {
            return new DynamicOptionsResolver.SourceFetcher.Answer(false, null, null, true);
        }
        return new DynamicOptionsResolver.SourceFetcher.Answer(
                true, result.data(), credentialSourceOf(result), false);
    }

    /** The pinned key the surface chose, or null for the account's default. */
    private static String pinnedCredentialId(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return null;
        Object pinned = context.credentials().get(CatalogExecuteModule.CREDENTIAL_ID_CONTEXT_KEY);
        if (pinned == null) return null;
        String text = String.valueOf(pinned).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * The same caller, with nothing for a charge to land on.
     *
     * <p>Reading a list is not part of the work the caller is paying for. Passed
     * through whole, the context carries the chat stream or the workflow run the
     * caller is inside, and the delegated execute turns either into a billing
     * scope: opening a dropdown would then reserve and commit against that turn,
     * and an agent that asks in a loop would bill a loop. The GET-only rule keeps
     * a source from MUTATING anything; this is what keeps it from costing
     * anything. The key itself stays, because the answer depends on it.
     */
    private static ToolExecutionContext unbilled(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return context;
        Map<String, Object> credentials = new LinkedHashMap<>(context.credentials());
        boolean removed = credentials.keySet().removeAll(CatalogExecuteModule.BILLING_SCOPE_KEYS);
        if (!removed) return context;
        return new ToolExecutionContext(
                context.tenantId(), credentials, context.variables(), context.approvedServices(),
                context.viewingWorkflowId(), context.viewingWorkflowName(), context.orgId(),
                context.orgRole());
    }

    /** Which pool actually replied, when the execution reported it. */
    private String credentialSourceOf(ToolExecutionResult result) {
        if (result.data() instanceof Map<?, ?> data && data.get("metadata") instanceof Map<?, ?> meta) {
            Object source = meta.get("credentialSource");
            if (source != null) return String.valueOf(source);
        }
        return null;
    }

    /**
     * What the platform charged for this call, when it charged anything.
     *
     * <p>Read from the execution rather than recomputed from the model's published price: an
     * administrator can republish a rate at any time, so a price resolved when the asset is READ
     * would eventually state a figure the reader was never charged. The execution reports the
     * amount its reservation committed, which is the one on the ledger.
     *
     * <p>Null for everything the platform did not bill (the reader's own key, a self-hosted
     * install, an endpoint with no published price), and null is what gets stored: no key at all,
     * rather than a zero that would read as "this was free".
     */
    private java.math.BigDecimal billedCreditsOf(ToolExecutionResult result) {
        if (result.data() instanceof Map<?, ?> data && data.get("metadata") instanceof Map<?, ?> meta) {
            // A Number of whatever shape the JSON round trip produced (Integer, Double,
            // BigDecimal), via its own text so a double never introduces a fraction the ledger
            // does not have.
            if (meta.get(ToolExecutionManager.BILLED_CREDITS_KEY) instanceof Number amount) {
                try {
                    return new java.math.BigDecimal(amount.toString());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }


    // ── models ──────────────────────────────────────────────────────────────

    /**
     * Discovery. An agent cannot guess a model id, so this is what it calls
     * first; every id, capability and starting price the platform offers is
     * listed here rather than being spread across the tool description.
     */
    private ToolExecutionResult listModels(Map<String, Object> parameters) {
        String kind = str(parameters, "kind");
        String askedProvider = str(parameters, "provider");
        List<GenerationRegistry.GenerationModel> models = registry.list(kind);

        // Narrowing to ONE provider, because a provider that sells a quality or
        // an output size as its own price sells it as its own model id, and
        // comparing those ids is the only way to choose between them. Without
        // this the only way to see one provider's tiers was to read every
        // model of that kind, which for images is most of the catalogue.
        // Matched on the slug and on the display name, since an agent has both
        // in front of it and no way to know which one this asks for.
        String provider = str(parameters, "provider");
        if (provider != null && !provider.isBlank()) {
            String wanted = provider.trim().toLowerCase(java.util.Locale.ROOT);
            models = models.stream()
                    .filter(m -> wanted.equalsIgnoreCase(m.apiSlug())
                            || wanted.equalsIgnoreCase(m.apiName()))
                    .toList();
        }

        Map<PlatformSalesResolver.ModelRef, PlatformSalesResolver.Verdict> sold =
                platformSales.resolve(models.stream().map(PlatformSalesResolver.ModelRef::of).toList());

        List<Map<String, Object>> rows = new ArrayList<>(models.size());
        for (GenerationRegistry.GenerationModel m : models) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", m.modelId());
            row.put("kind", m.kind());
            row.put("label", m.label());
            row.put("provider", m.apiName());
            // Which key can actually run it HERE, so a caller stops choosing a
            // payer this installation does not have. Resolved for the whole list
            // in one call above.
            row.put("runs_on", sold.getOrDefault(PlatformSalesResolver.ModelRef.of(m),
                    PlatformSalesResolver.Verdict.UNKNOWN).wire());
            row.put("accepts", new TreeSet<>(m.model().capabilities()));
            row.put("required", new TreeSet<>(m.model().required()));
            // Capped here and not in the app's copy: this payload is TOKENS and
            // carries every model at once, so a provider's hundred-voice
            // catalogue would crowd out the models it is meant to describe.
            // WHICH parameters can be asked about is read from rows already in
            // hand, so naming them costs no provider call: the call happens only
            // when someone actually asks. Without it an agent has no way to know
            // that action='options' would answer for 'voice', and is left
            // inventing an opaque identifier.
            Map<String, Object> limits = GenerationLimits.describe(
                    m.model(), m.catalogAllowed(), GenerationLimits.AGENT_INLINE_CAP,
                    optionsResolver.unifiedDynamicParameters(m));
            if (!limits.isEmpty()) row.put("limits", limits);
            // What each FILE this model takes actually IS, how many of them, and
            // the two pairing rules a caller could otherwise only learn from a
            // refusal. Shaped by GenerationInputs, which the app's own listing
            // calls too: two surfaces describing one model differently is how a
            // builder comes to show a limit the tool does not enforce.
            Map<String, Object> inputs = GenerationInputs.describe(m.spec(), m.model());
            if (!inputs.isEmpty()) row.put("inputs", inputs);

            // What this model SENDS whatever the caller asks, which is the only
            // machine-readable statement of what separates one id from the next
            // when a provider sells a tier as its own model. Nine OpenAI image
            // ids accept nothing but a prompt and differ solely in a pinned
            // quality and size; without this the difference exists only in the
            // id string and the label, so an agent looking for a portrait image
            // has to parse names to find one. Empty for the ordinary model,
            // which pins nothing and pays no tokens for the key.
            // The endpoint's own scaffolding as well as the model's tier: both
            // are written on every call whatever the caller passes, and a field
            // that claims to list them cannot show only half.
            Map<String, Object> fixed = new LinkedHashMap<>(m.spec().constants());
            fixed.putAll(m.model().constants());
            if (!fixed.isEmpty()) row.put("fixed", fixed);

            // What this model is billed ON, and what that value becomes when the
            // parameter is left out. Without both, an agent cannot predict the
            // price of the call it is about to make - and a model that defaults
            // nothing refuses the call instead, which it can only avoid by
            // knowing in advance.
            String measuring = m.model().measuringParam();
            if (measuring != null) {
                row.put("billed_on", measuring);
                BigDecimal fallback = m.model().defaultMeasurement();
                if (fallback != null) {
                    row.put("default_" + measuring, fallback);
                }
            }
            row.put("price", describePrice(m.seedPrice()));
            row.put("async", m.isAsync());
            rows.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("models", rows);
        data.put("count", rows.size());
        data.put("kinds", registry.kinds());
        data.put("size_billed_note", "billed_quantity is the size you ASKED FOR, not the size that came "
                + "back. A provider that clamps a request to its own maximum still charges the length "
                + "you sent, so ask for what you need rather than for the ceiling.");
        data.put("cost_note", "action='create' reports what it actually cost as billed_credits, "
                + "beside the billed_quantity it was charged on. It is present only when the "
                + "platform's key answered and the charge went through: ABSENT MEANS NOT CHARGED "
                + "BY THE PLATFORM, never zero, because a key you configured yourself is paid to "
                + "the provider directly. Quote it when the user asks what something cost, and "
                + "never total a set of generations as though a missing one were free.");
        data.put("price_note", "Prices are the platform's list rate in credits (1 credit = $0.001). "
                + "A model priced per second or per character costs more for a longer request: "
                + "action='create' reports the size it billed on as billed_quantity, counted in "
                + "billed_unit, which is always the platform's own unit (seconds for duration, "
                + "assets for a count, characters for text). A rate quoted per minute applies to "
                + "that same size converted, so 60 seconds is charged as one minute. A model you "
                + "supply your own key for is not billed by the platform at all.");
        data.put("price_multipliers_note", "price.price_multipliers, when a model has it, says which "
                + "of your CHOICES cost more than its published rate. A by_value entry multiplies "
                + "the price by the factor listed for the value you send, and at least one value is "
                + "listed at 1: any value at 1 costs the published rate. Several values can sit at "
                + "1, so read the map rather than assuming the cheapest tier is the only one. A "
                + "parameter with a by_value entry is always in the model's required list, so "
                + "there is no omitting it: leave it out and the call is refused naming it, at no "
                + "cost. A per_file entry adds that factor for EACH "
                + "file you attach in that slot, so attaching none costs nothing. The factors "
                + "multiply together, and action='create' reports the result as billed_multiplier "
                + "with billed_multiplier_reasons beside it. The size of the call is reported "
                + "separately and is never scaled by them: ten seconds stay ten seconds.");
        data.put("size_note", "billed_on names the parameter a model's price multiplies. Leave it "
                + "out and one of two things happens, both shown in this list: the model has a "
                + "default_<parameter>, which is used AND sent to the provider, so you get and pay "
                + "for exactly that size; or the parameter is in its required list, and the call is "
                + "refused naming it, at no cost. A per-character model is measured by its own "
                + "prompt, so it never needs a size.");
        if (rows.isEmpty()) {
            // WHICH filter emptied it. Saying "none are configured" when a
            // provider name simply matched nothing tells an agent to give up on
            // a platform that sells plenty, and a mistyped name is the likeliest
            // way to get here.
            String hint;
            if (askedProvider != null && !askedProvider.isBlank()) {
                hint = "No generation models from a provider called '" + askedProvider + "'"
                        + (kind == null ? "" : " of kind '" + kind + "'")
                        + ". The name is matched against the 'provider' field of a listing, so ask "
                        + "without the filter and read one from there rather than guessing it.";
            } else if (kind == null) {
                hint = "No generation models are configured on this platform.";
            } else {
                hint = "No generation models of kind '" + kind + "'. Available kinds: " + registry.kinds();
            }
            data.put("hint", hint);
        }
        return ToolExecutionResult.success(data);
    }

    /** Render a price so the agent can compare models without parsing a formula. */
    /**
     * The catalog path's "no credential is connected" payload, or null when
     * this result is an ordinary one.
     *
     * <p>Recognised by the {@code status} field the pre-flight sets, not by the
     * absence of an asset: plenty of real failures also have no asset, and they
     * must keep their own words.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> approvalNeededPayload(ToolExecutionResult result) {
        if (!(result.data() instanceof Map<?, ?> map)) {
            return null;
        }
        return "approval_needed".equals(map.get("status")) ? (Map<String, Object>) map : null;
    }

    private static Map<String, Object> describePrice(GenerationSpec.Price price) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (price == null) {
            out.put("credits", 0);
            return out;
        }
        out.put("unit", price.unit());
        if (price.base() != null && price.base().signum() > 0) {
            out.put("base_credits", price.base());
        }
        if (price.perUnit() != null && price.perUnit().signum() > 0) {
            out.put("credits_per_" + price.unit(), price.perUnit());
        }
        if (price.min() != null) out.put("min_credits", price.min());
        if (price.max() != null) out.put("max_credits", price.max());
        if (!out.containsKey("credits_per_" + price.unit())) {
            out.put("credits", price.base() == null ? BigDecimal.ZERO : price.base());
        }
        // What the CALLER's own choices do to that rate. Without this an agent
        // reading a rate and a size can predict the price of some calls and not
        // others, with nothing saying which - and the ones it cannot predict are
        // exactly the expensive ones.
        Map<String, Object> modifiers = describeModifiers(price);
        if (!modifiers.isEmpty()) out.put("price_multipliers", modifiers);
        return out;
    }

    /**
     * The declared price factors, in the same vocabulary the caller writes its
     * parameters in.
     *
     * <p>Shared by the agent listing and the app's own, from one shaping: two
     * surfaces describing one price differently is how a screen comes to quote
     * a number the invoice does not.
     */
    public static Map<String, Object> describeModifiers(GenerationSpec.Price price) {
        if (price == null || price.modifiers().isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (GenerationSpec.PriceModifier modifier : price.modifiers()) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (modifier.countsAssets()) {
                row.put("per_file", modifier.perAsset());
            } else {
                row.put("by_value", modifier.multiply());
            }
            out.put(modifier.param(), row);
        }
        return Collections.unmodifiableMap(out);
    }

    // ── create ──────────────────────────────────────────────────────────────

    private ToolExecutionResult create(Map<String, Object> parameters, String tenantId,
                                        ToolExecutionContext context) {
        String modelId = GenerationRequestBuilder.normalizeModelId(str(parameters, "model"));
        if (modelId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "Parameter 'model' is required. Call action='models' to list the available "
                            + "model ids, their accepted parameters and their price.");
        }

        Optional<GenerationRegistry.GenerationModel> resolved = registry.resolve(modelId);
        if (resolved.isEmpty()) {
            List<String> known = registry.list(null).stream()
                    .map(GenerationRegistry.GenerationModel::modelId).toList();
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "Unknown generation model '" + modelId + "'. "
                            + (known.isEmpty()
                                ? "No generation models are configured on this platform."
                                : "Available: " + String.join(", ", known)));
        }
        GenerationRegistry.GenerationModel target = resolved.get();

        // Project the unified parameters onto this provider's request shape.
        // Anything wrong is refused HERE, before the customer pays for a call
        // that was never going to succeed.
        Map<String, Object> unified = collectUnifiedParams(parameters);
        GenerationRequestBuilder.Built built =
                GenerationRequestBuilder.build(target.spec(), target.model(), unified);
        if (!built.ok()) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    String.join("; ", built.errors()));
        }

        // An input file is a platform handle until here, where it becomes what
        // THIS provider takes: a data URL, base64 beside a media type, a link it
        // fetches, or a multipart part left alone for the encoder further down.
        // Done before the reservation, so a file that cannot be read costs
        // nothing rather than being charged for a call never dispatched.
        GenerationInputResolver.Prepared inputs =
                inputResolver.prepare(target.spec(), built.params(), tenantId);
        if (!inputs.ok()) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    String.join("; ", inputs.errors()));
        }

        // Hand over to the single catalog execution path: credentials, billing,
        // restrictions and binary storage all already live there.
        //
        // The pricing context travels as a TYPED argument, not inside the
        // delegated parameter map. That map is caller-influenced, and these two
        // values decide the amount charged: a caller-supplied quantity of zero
        // would be a free generation.
        Map<String, Object> delegated = new LinkedHashMap<>();
        // THE ENDPOINT'S ID, not its `api/tool` slug, even though the execute
        // route accepts both. The two pre-flight gates in front of that route do
        // not: the agent restriction list holds the ids catalog search hands out
        // (so a slug is never in it, and a restricted agent loses generation
        // entirely, told to run a search that cannot return what it is asked
        // for), and the credential pre-flight reads a single-segment
        // /api/catalog/tools/{id}/info that 404s on a two-segment slug (so the
        // structured "connect a key for X" prompt is swallowed into a warning).
        // Both failures are invisible in the response: the call itself succeeds.
        delegated.put("tool_id", target.apiToolId().toString());
        delegated.put("params", built.params());

        // KEEP THE ASSET READABLE THROUGH THE SHAPER.
        //
        // Every string leaf over 4 KB is clipped on the way out, and one that
        // looks like base64 is replaced outright by "[BASE64_CONTENT: n KB]".
        // That is right for an agent reading a tool result and fatal here: the
        // asset IS a base64 leaf, and the resolver runs after the shaper. The
        // asset came back as the marker text, failed to decode, and reported
        // "not decodable base64" for a call the customer had already paid for.
        //
        // The dehydrator does NOT cover this on its own, and believing it did
        // is what left the hole open once already: its threshold is 64 KB of
        // DECODED bytes, while the shaper measures serialised text. The two are
        // 4/3 apart, so an asset can be small enough to escape the dehydrator
        // and large enough for the shaper to destroy. Asking for the subtree
        // verbatim is what closes the gap at every size.
        //
        // The root segment rather than the full path: the shaper expands by
        // prefix, and a descriptor may address the leaf through a wildcard the
        // shaper's own canonical form does not spell the same way. The bytes do
        // not reach the agent either way, because decorate() prunes them from
        // provider_response once they are a stored file.
        if (target.spec().isBase64Asset()) {
            delegated.put("expand", List.of(assetRootOf(target.spec())));
        }
        // An omitted credential_source is forwarded as omitted, on purpose.
        //
        // It is tempting to default it to "platform" here, because that is what
        // the node and the app modal send and it would make one sentence true
        // everywhere. It would also take something away: an absent source means
        // the catalog tries the caller's OWN key first and falls back to the
        // platform, and the money is correct either way (a reservation whose
        // call is answered by the user's own credential is RELEASED, not
        // committed, in ToolExecutionManager.settleReservation). Pinning it to
        // "platform" would instead refuse two agents who are fine today: one
        // with its own key and no credits, and one on an install with no
        // platform credential configured at all.
        //
        // So the fix for "the help said platform was the default" is to correct
        // the help, not to change who pays. An agent that wants a guarantee
        // states the source, and both values are honoured strictly.
        Object credentialSource = parameters.get("credential_source");
        if (credentialSource != null) {
            delegated.put("credential_source", credentialSource);
        }
        // WHICH own key is deliberately NOT read from `parameters` here. It
        // travels on the execution context, which only the app dialog and the
        // workflow node populate (through GenerationController), so an agent
        // calling this tool cannot pin a key it has no way to learn about. The
        // context is already passed to executeGeneration below, untouched.

        // Refuse a full account HERE, before the provider is called, because billing is committed
        // inside executeGeneration. Past this line the customer has paid, and the storage gate
        // that runs when the asset is made durable can only report a loss, not prevent one. This
        // is the single point where "charged for an asset we then refuse to keep" is preventable.
        //
        // Advisory only: the probe fails OPEN (see StorageClient.hasRoomFor), so a storage-service
        // outage lets the generation proceed and the real gate still runs at write time. It asks
        // for 1 byte rather than the asset's size, which is not knowable before the model answers;
        // that means it catches the account that is already full, which is the case that hurts,
        // and never refuses one that merely might not fit.
        if (storageClient != null) {
            String storageTenant = context == null ? tenantId : context.tenantId();
            if (!storageClient.hasRoomFor(storageTenant, null, 1L)) {
                log.warn("generation: refused before billing, storage full for tenant={} model={}",
                        storageTenant, target.modelId());
                return ToolExecutionResult.failure(ToolErrorCode.QUOTA_EXCEEDED,
                        "Storage is full, so this generation was not started and you have NOT been "
                        + "charged. Free up space or raise the storage limit, then run it again.");
            }
        }

        Optional<ToolExecutionResult> raw = executeModule.executeGeneration(
                delegated, context,
                new CatalogExecuteModule.GenerationBilling(
                        target.modelId(), built.quantity(), built.quantityUnit(),
                        // What the caller's own choices do to the published
                        // rate, derived from the parameters that were just
                        // validated against this model. 1 for a model that
                        // declares no modifiers, which changes nothing.
                        built.priceMultiplier()));
        if (raw.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "Generation dispatch failed for model '" + modelId + "'");
        }
        ToolExecutionResult result = raw.get();
        if (!result.success()) {
            return result;
        }

        // THE CREDENTIAL PRE-FLIGHT ANSWERS WITH A SUCCESS, and it means the
        // opposite of one. When no key is connected for the provider, the
        // catalog path stops before dispatching and returns an
        // `approval_needed` payload; nothing ran, and nothing was charged.
        //
        // Read as an ordinary success it is a disaster of a message: there is
        // no asset in it, so the resolver below reports "the provider produced
        // nothing" and states that the call already cost money. Both halves are
        // false, and they send the reader looking for a failed generation
        // instead of connecting a key, which is the one thing that would fix it.
        //
        // (This path was unreachable while the generation surface delegated a
        // slug the pre-flight could not resolve: the check 404'd, returned null,
        // and the refusal came from further down. Making the identifier correct
        // is what exposed it.)
        Map<String, Object> approval = approvalNeededPayload(result);
        if (approval != null) {
            // NAME THE POOLS THAT WERE ACTUALLY EMPTY, from what the gate
            // REPORTS rather than from an assumption about which branch reached
            // here. Assuming it was always the own-key branch was wrong twice
            // over: the gate also fires when no pool was pinned and NEITHER pool
            // has a key, and telling that caller to buy on the platform key
            // sends them to a second empty pool, where a different guard
            // refuses them and points back at the first.
            String service = String.valueOf(approval.getOrDefault("serviceName", target.apiName()));
            boolean platformKeyAvailable = Boolean.TRUE.equals(approval.get("platformKeyAvailable"));
            String remedy = platformKeyAvailable
                    ? "Pass credential_source='platform' to buy it on the platform's key instead, "
                            + "or connect your own key first."
                    : "The platform does not sell this one either, so a key has to be connected "
                            + "before any generation on it can run. Retrying unchanged will be "
                            + "refused again.";
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    CatalogExecuteModule.CREDENTIALS_REQUIRED_CODE + ": you have no " + service
                            + " key of your own connected, so this generation was not started and "
                            + "nothing was charged. " + remedy
                            + " Connecting a key is the account owner's act, so report this rather "
                            + "than retrying unchanged.");
        }

        // Make the asset durable. A provider either returned the bytes (already
        // stored on the way through) or a URL that expires; either way the
        // caller must end up with a file, not a link that 404s tomorrow on
        // something they already paid for.
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = result.data() instanceof Map
                ? (Map<String, Object>) result.data() : Map.of();
        GenerationAssetResolver.Resolved asset =
                assetResolver.resolve(target.spec(), target.model(), payload,
                        context == null ? tenantId : context.tenantId());
        if (!asset.ok()) {
            // The call already cost money, so this is reported as a failure the
            // caller can act on rather than a success with nothing in it.
            //
            // "Act on" is the operative word, and it is why the failure carries
            // DATA. Billing committed inside executeGeneration, before this
            // line: the provider produced the asset and the customer has been
            // charged for it. Everything that can fail here is transient in
            // kind - a fetch timeout, a 5xx from the provider's CDN, a body
            // over the storage cap - so the provider's own link is a live route
            // to the thing that was paid for, and it expires in minutes.
            // Reporting only a sentence made a paid asset unrecoverable for the
            // sake of a hiccup. The raw provider response travels with it
            // because a descriptor that pointed at the wrong path is the other
            // way to get here, and then the URL is in that payload under a key
            // this code did not expect.
            log.error("generation: model={} completed upstream but no asset was produced: {}",
                    target.modelId(), asset.error());
            Map<String, Object> recovery = new LinkedHashMap<>();
            if (asset.assetUrl() != null && !asset.assetUrl().isBlank()) {
                recovery.put("asset_url", asset.assetUrl());
            }
            // Pruned here too. The asset path is asked to survive the shaper on
            // a base64 model, so on THIS path the payload can still carry the
            // whole inline blob, and it travels into a node output and from
            // there into the run's stored state. Every failure that reaches
            // here leaves those bytes useless anyway: the path was wrong and
            // there is nothing at it, the leaf did not decode, it was over the
            // storage cap, or storage itself refused. What the reader needs is
            // the SHAPE of the response, which is exactly what is left.
            recovery.put("provider_response", asDiagnosis(payload, target.spec()));
            // Named in the MESSAGE too, not only in the data: an agent reads the
            // failure text, and a URL it cannot see is a URL it cannot use.
            String recoveryNote = recovery.containsKey("asset_url")
                    ? " You have been charged for it. The provider's own link is returned as asset_url and "
                      + "is usually valid for a short while, so fetch it now to keep what you paid for: "
                      + asset.assetUrl()
                    : " You have been charged for it. No asset URL was found where the model's descriptor "
                      + "says it should be; the provider's whole answer is returned as provider_response, "
                      + "so look for a link in there.";
            return new ToolExecutionResult(false, recovery,
                    "The generation ran but no asset could be retrieved: " + asset.error() + "."
                            + recoveryNote,
                    ToolErrorCode.EXECUTION_FAILED, Map.of());
        }

        // Record on the asset the recipe that produced it, so the file can be told apart from an
        // uploaded one, listed among what this account has generated, and run again with one word
        // changed. Best-effort and AFTER the generation succeeded: it annotates a file that already
        // exists and has already been paid for, so it can add nothing and must take nothing away.
        //
        // The credential source comes from what the execution REPORTED, not from what the caller
        // asked for: an omitted source lets the catalog try the caller's own key and fall back to
        // the platform, so the two legitimately differ and only one of them is what happened.
        // Read once: the same figure is stamped on the asset and handed back to the caller, and two
        // reads of one value is how they end up disagreeing after an edit to only one of them.
        java.math.BigDecimal billedCredits = billedCreditsOf(result);
        provenanceRecorder.record(
                asset.fileRef(),
                new GenerationProvenanceRecorder.Recipe(
                        target.modelId(), target.kind(), target.apiName(), unified,
                        credentialSourceOf(result), built.quantity(), built.quantityUnit(),
                        billedCredits),
                // The SAME tenant the asset was resolved (and stored) under, sixty lines up. The
                // stamp looks the row up by (id, tenant), so reading the tenant differently here
                // would turn it into a silent no-op the moment a caller passes a context whose
                // tenant is not the outer one.
                context == null ? tenantId : context.tenantId(),
                context == null ? null : context.orgId());

        return persistCard(
                ToolExecutionResult.success(
                        decorate(payload, asset.fileRef(), target, built, billedCredits)),
                parameters, promptOf(unified), target.kind(), context);
    }

    /**
     * Put the finished generation on the chat side panel, and hand back the
     * result the caller sees.
     *
     * <p>A card is an Interface entity that the chat re-fetches by its id, so a
     * result that is only in the conversation history has no card at all. The
     * legacy image tool has persisted one since the beginning and is currently
     * the only producer of any, which is the last thing keeping it alive.
     *
     * <p>Two rules, both inherited from that path rather than re-decided here.
     * <b>No chat context, no card</b>: {@link ToolResultPersistEnricher} returns
     * the result untouched unless the credentials carry both a conversation and
     * a message id, which a workflow node and every other non-chat caller do
     * not, and there is nothing to attach a card to. <b>A card is never worth
     * the asset</b>: by this point the generation has run and been charged, so
     * failing to draw a card must not turn a paid success into a failure. Both
     * failure modes are already swallowed for us, which is the reason to go
     * through this pair rather than call the endpoint directly: the client
     * returns {@code null} on any transport or status error and logs it, and
     * the enricher catches anything the persist function throws. Either way the
     * caller still gets its file, only without the marker.
     */
    private ToolExecutionResult persistCard(ToolExecutionResult produced,
                                             Map<String, Object> parameters,
                                             String prompt,
                                             String kind,
                                             ToolExecutionContext context) {
        ToolResultPersistEnricher.PersistFn persist = (ctx, params, originalData) -> {
            if (!(originalData instanceof Map<?, ?> card)) return null;
            Map<String, Object> creds = ctx.credentials();

            ImageGenerationInterfaceRequest req = new ImageGenerationInterfaceRequest();
            req.setName(cardName(prompt, kind));
            req.setConversationId((String) creds.get("conversationId"));
            req.setMessageId((String) creds.get("__messageId__"));
            req.setAgentId((String) creds.get("__agentId__"));
            // The tool result verbatim. interface-service reads the unified
            // shape's `file` and the legacy shape's `images[]` on every call, so
            // nothing is reshaped on the way out.
            req.setData(ToolResultPersistEnricher.asStringKeyMap(card));
            // The prompt is an INPUT, so it is not in the result and has to be
            // carried beside it. This is the field the DTO gained for exactly
            // this producer.
            req.setPrompt(prompt);
            // Without the org stamp the card reads as "Failed to load" for the
            // author's org teammates, who can see the conversation.
            req.setOrganizationId(ctx.orgId());

            InterfaceDto persisted = interfaceClient.createOrUpdateImageGenerationInterface(req, ctx.tenantId());
            if (persisted == null || persisted.getId() == null) return null;
            return new ToolResultPersistEnricher.PersistedInterface(
                    persisted.getId().toString(), persisted.getName());
        };

        // No strip hook: the enricher takes one to keep inline bytes out of the
        // agent-visible result, and this result has none to remove. The asset is
        // a FileRef by the time it reaches here, because the resolver stored it.
        return ToolResultPersistEnricher.enrichAndPersist(
                produced, parameters, context, CARD_TYPE, persist, /* postPersistHook */ null);
    }

    /** Card title: the prompt, cut to a list line, or the format when there is none. */
    private static String cardName(String prompt, String kind) {
        if (prompt == null) {
            return "Generated " + (kind == null || kind.isBlank() ? "asset" : kind);
        }
        return prompt.length() > CARD_NAME_MAX
                ? prompt.substring(0, CARD_NAME_MAX) + "…"
                : prompt;
    }

    /**
     * The prompt as the caller wrote it, read from the already-collected unified
     * parameters so a prompt nested under {@code params} is found too. Reading
     * the top level only would leave the card of every nested caller untitled,
     * and this tool accepts both shapes on purpose.
     */
    private static String promptOf(Map<String, Object> unified) {
        Object v = unified.get("prompt");
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Stamp the generation context onto the catalog's result so the agent gets
     * one stable shape whatever the provider did, and always learns what the
     * call cost.
     */
    private static Map<String, Object> decorate(Map<String, Object> data,
                                                 Map<String, Object> fileRef,
                                                 GenerationRegistry.GenerationModel target,
                                                 GenerationRequestBuilder.Built built,
                                                 java.math.BigDecimal billedCredits) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", target.modelId());
        out.put("kind", target.kind());
        out.put("provider", target.apiName());
        out.put("file", fileRef);
        if (built.quantity() != null && built.quantity().signum() > 0) {
            // The unit REPORTED is the one the size was measured in, which is
            // the platform's own (a model listed per minute is measured in
            // seconds). Reporting the list price's unit next to this number
            // would state a size the call does not have.
            out.put("billed_quantity", built.quantity());
            out.put("billed_unit", built.quantityUnit());
        }
        // Why this call was not charged at the model's published rate. Reported
        // beside the size rather than folded into it: the size is what was
        // produced and stays true whatever it cost, and a reader handed only a
        // total that is not rate x quantity has no way to tell a surcharge from
        // an arithmetic error. Absent for a call at the published rate, which is
        // every model that declares no modifiers.
        if (!built.pricedAtBaseRate()) {
            out.put("billed_multiplier", built.priceMultiplier());
            if (!built.priceFactors().isEmpty()) {
                out.put("billed_multiplier_reasons", built.priceFactors());
            }
        }
        // What it actually cost, beside the size it was charged on. Absent whenever the platform
        // charged nothing - a key the caller supplied themselves, an install that does not meter -
        // and absent is not zero: the asset was paid for, at the provider. Reported here rather
        // than left buried in the provider payload, because the price of a call is a fact about
        // the call, and an agent asked what something cost should not have to know where the
        // execution layer keeps its metadata.
        if (billedCredits != null && billedCredits.signum() > 0) {
            out.put("billed_credits", billedCredits);
        }
        // Carry the provider payload through under its own key rather than
        // merging it, so a provider field can never shadow `file` or `model`.
        if (data != null && !data.isEmpty()) {
            out.put("provider_response", withoutTheAssetItself(data, target.spec()));
        }
        return out;
    }

    /**
     * First segment of a base64 asset path, which is what the shaper expands by.
     * {@code data[0].b64_json} and {@code candidates[0]...data} give {@code data}
     * and {@code candidates}.
     */
    /**
     * The provider payload as a DIAGNOSIS, with no leaf big enough to matter.
     *
     * <p>For the failure branch only. Pruning by path is the right tool when the
     * asset is where the descriptor said, but the commonest reason to be on this
     * branch is that it is NOT there: the path is wrong, and pruning it removes
     * nothing while the blob sits somewhere else entirely. The asset path is
     * also asked to survive the response shaper on a base64 model, so that blob
     * arrives here at full size and travels into a node output and the run's
     * stored state.
     *
     * <p>Re-shaping in WORKFLOW mode caps every leaf without digesting arrays or
     * moving anything, which leaves exactly what a reader needs to see WHERE the
     * asset actually was.
     */
    private Map<String, Object> asDiagnosis(Map<String, Object> payload, GenerationSpec spec) {
        if (payload == null || payload.isEmpty() || !spec.isBase64Asset()) return payload;
        Object shaped = responseShaper
                .shape(payload, null, null, ResponseShaper.Mode.WORKFLOW)
                .data();
        if (shaped instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> reduced = (Map<String, Object>) map;
            return reduced;
        }
        return payload;
    }

    static String assetRootOf(GenerationSpec spec) {
        String path = spec.base64Path();
        return path.split("\\.")[0].split("\\[")[0];
    }

    /**
     * Drop the base64 payload the resolver has just turned into a stored file.
     *
     * <p>The agent is handed the file; handing it the bytes as well doubles a
     * result it can already open, in a channel measured in tokens. Usually the
     * catalog's dehydrator has already replaced the leaf with a FileRef and
     * there is nothing to remove, which is exactly why this cannot be left to
     * it: when dehydration is skipped or fails, the leaf stays inline and a
     * multi-megabyte blob lands in the context window.
     *
     * <p>Only the path the descriptor NAMES is touched, and only for the base64
     * camp: every other field the provider returned is the agent's to read.
     */
    private static Map<String, Object> withoutTheAssetItself(Map<String, Object> data,
                                                              GenerationSpec spec) {
        if (!spec.isBase64Asset()) return data;
        return GenerationAssetResolver.withoutPath(data, spec.base64Path()).orElse(data);
    }

    /**
     * Gather the unified parameters, whether the agent nested them under
     * {@code params} or flattened them at the top level. Both shapes are common
     * in practice and refusing one of them would be a needless failure.
     */
    private static Map<String, Object> collectUnifiedParams(Map<String, Object> parameters) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object nested = parameters.get("params");
        if (nested instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        for (Map.Entry<String, Object> e : parameters.entrySet()) {
            if (RESERVED.contains(e.getKey()) || e.getValue() == null) continue;
            out.putIfAbsent(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Control keys that are never generation parameters. */
    private static final Set<String> RESERVED = Set.of(
            "action", "model", "kind", "params", "credential_source", "tool_id",
            // Names an account object, not a dimension of the asset. Left out of
            // this set it would be projected onto the provider's request as an
            // unknown parameter, and the model would refuse a call the caller
            // configured correctly.
            CatalogExecuteModule.CREDENTIAL_ID_KEY);

    private static String str(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s.toLowerCase(Locale.ROOT);
    }
}

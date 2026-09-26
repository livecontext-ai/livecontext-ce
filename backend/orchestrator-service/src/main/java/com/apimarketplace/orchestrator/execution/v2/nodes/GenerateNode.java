package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.template.ReportedParams;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.generation.GenerationExecutionService;
import com.apimarketplace.orchestrator.services.generation.GenerationExecutionService.GenerationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Generate node - produces an asset of any kind (image, video, audio, voice,
 * music) from a prompt.
 *
 * <p>The {@code model} parameter decides everything else: the format produced,
 * which of the unified parameters are accepted, and what the call costs. Model
 * ids come from the platform's generation catalog; a workflow author picks one
 * in the inspector, an agent lists them with {@code workflow(action='help',
 * topics=['generate'])} - a free read every builder has, unlike the opt-in
 * {@code generation} tool, which spends credits and may not be granted.
 *
 * <p>Config lives in Core's generic {@code params} map, exactly like
 * {@code core:media}. Every param accepts {@code {{...}}} template expressions
 * resolved at run time.
 *
 * <p><b>Execution is delegated, on purpose.</b> The node validates nothing about
 * the model beyond "one was named": model resolution, per-model parameter
 * validation, credential choice, credit reservation, long-job polling and asset
 * storage all live in catalog-service and are shared with the chat surface. See
 * {@link GenerationExecutionService} for why re-deriving any of it here would be
 * a second, divergent copy.
 *
 * <p>Like {@code core:media} and unlike a best-effort screenshot, this node FAILS
 * when no asset comes back: producing the asset IS its purpose, and the customer
 * has already been charged by the time the provider answers.
 */
public class GenerateNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(GenerateNode.class);

    /**
     * Keys of the params map that configure the NODE rather than the generation.
     * Everything else is forwarded as a generation parameter and validated
     * against the chosen model on the catalog side, which is the only place that
     * knows what a given model accepts.
     */
    static final Set<String> CONTROL_KEYS = Set.of("model", "credential_source", "credential_id");

    /** The two credential pools a workflow author can pin the node to. */
    static final Set<String> CREDENTIAL_SOURCES = Set.of("user", "platform");

    /** Which key an unstated node runs on. See where it is applied below. */
    static final String DEFAULT_CREDENTIAL_SOURCE = "platform";

    static final String GENERATION_SERVICE_UNAVAILABLE =
        "This generate node cannot run: the generation service is not reachable from this "
            + "installation, and producing the asset is this node's whole purpose. Only the user or "
            + "an administrator can enable it - tell them this node needs it, or remove the node.";

    private final Map<String, Object> params;

    // Injected via ServiceRegistry
    private GenerationExecutionService generationExecutionService;

    public GenerateNode(String nodeId, Map<String, Object> params) {
        super(nodeId, NodeType.GENERATE);
        this.params = params != null ? params : Map.of();
    }

    public void setGenerationExecutionService(GenerationExecutionService generationExecutionService) {
        this.generationExecutionService = generationExecutionService;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.generationExecutionService = registry.getGenerationExecutionService();
    }

    public Map<String, Object> getParams() {
        return params;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        // The resolved params once they exist, so every later failure reports what the node ran
        // with rather than the configured templates (which read as "unresolved" in the column).
        Map<String, Object> resolved = null;

        try {
            if (generationExecutionService == null) {
                return failure(context, startTime, GENERATION_SERVICE_UNAVAILABLE);
            }

            // Resolve the WHOLE params map first: every param accepts templates,
            // and whole-value templates keep their RAW type (a FileRef used as a
            // reference image stays a map, a duration stays a number).
            resolved = resolveParams(context);
            Map<String, Object> resolvedParams = reportableParams(resolved, context);

            String model = stringValue(resolved.get("model"));
            model = model != null ? model.trim().toLowerCase(Locale.ROOT) : null;
            if (model == null || model.isBlank()) {
                return failure(context, startTime, resolved,
                    // No UI navigation here: this message is read by an agent
                    // that has no screen, only the tools it can call.
                    "model is required. Set params={model: '<model-id>'}. List the ids with "
                        + "workflow(action='help', topics=['generate']), which lists every id this "
                        + "installation offers and is free to read. The model decides the format produced, the "
                        + "parameters accepted and the price.");
            }

            String credentialSource = stringValue(resolved.get("credential_source"));
            credentialSource = credentialSource == null ? null
                : credentialSource.trim().toLowerCase(Locale.ROOT);
            if (credentialSource != null && !credentialSource.isBlank()
                    && !CREDENTIAL_SOURCES.contains(credentialSource)) {
                return failure(context, startTime, resolved,
                    "credential_source must be 'platform' (use the platform's key and be billed the "
                        + "platform price) or 'user' (use your own key and be billed nothing by the "
                        + "platform), got '" + credentialSource + "'");
            }
            // Unstated means the platform key, which is what every surface that
            // shows this node quotes a price for. Left unstated, the executor
            // downstream would instead try the author's own key first: a
            // different provider account and a different price from the one the
            // builder displayed, decided by a field nobody filled in. Three
            // producers write this node (the builder inspector, add_node and a
            // hand-written plan) and only two of them state it, so the meaning
            // of "unstated" is settled here, once, for all three.
            if (credentialSource == null || credentialSource.isBlank()) {
                credentialSource = DEFAULT_CREDENTIAL_SOURCE;
            }

            // Everything that is not a node control key is a generation parameter.
            // Deliberately NOT filtered against a local allow-list: which
            // parameters exist, and which of them a given model accepts, is
            // known only to the catalog, and a second list here would go stale
            // the first time a seed adds a dimension. An unknown or unaccepted
            // name comes back as a refusal naming what the model does accept,
            // BEFORE anything is charged.
            Map<String, Object> generationParams = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : resolved.entrySet()) {
                if (CONTROL_KEYS.contains(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                generationParams.put(entry.getKey(), entry.getValue());
            }

            // WHICH of the author's own keys, when they hold several for the
            // provider. Only meaningful on the 'user' branch, and read as
            // "unpinned" when it is not a positive whole number: a template that
            // resolved to nothing must fall back to the account's default key,
            // not travel on as a credential nobody owns.
            Long credentialId = credentialIdValue(resolved.get("credential_id"));

            GenerationResult result = generationExecutionService.generate(
                context.tenantId(), context.runId(), nodeId, model, generationParams,
                credentialSource, credentialId);

            if (!result.success()) {
                // A failed generation can still have been CHARGED: billing
                // commits before the asset is fetched and stored, so a
                // transient fetch failure leaves the provider's own short-lived
                // link as the only way to retrieve something already paid for.
                // Naming it in the failure is the whole point of carrying it;
                // it is short-lived, so telling the reader to hurry is part of
                // the message rather than a detail.
                String recoverable = result.recoverableAssetUrl();
                if (recoverable != null) {
                    logger.error("Generate node failed with a paid asset still at the provider: "
                            + "nodeId={}, model={}", nodeId, model);
                    return failure(context, startTime, resolved, result.error()
                            + " This call was charged and the asset exists at the provider, but it "
                            + "could not be stored. Download it now, the link expires shortly: "
                            + recoverable);
                }
                return failure(context, startTime, resolved, result.error());
            }

            Map<String, Object> data = result.data();
            Object file = data.get("file");
            if (!(file instanceof Map<?, ?> fileMap) || fileMap.get("path") == null) {
                // The provider ran, so this call has already cost the customer
                // money. Reporting success with nothing in `file` would let the
                // rest of the workflow run on an empty asset and hide that.
                logger.error("Generate node produced no asset: nodeId={}, model={}", nodeId, model);
                return failure(context, startTime, resolved,
                    "The generation ran but produced no file, so there is nothing for the next node to "
                        + "use. This call was still charged. Check the model's limits and run again.");
            }

            Map<String, Object> output = new LinkedHashMap<>();
            // Already gated by reportableParams: a second pass would describe the prompt it
            // deliberately reported whole.
            output.put("resolved_params", resolvedParams);
            output.put("file", file);
            output.put("model", data.getOrDefault("model", model));
            output.put("kind", data.get("kind"));
            output.put("provider", data.get("provider"));
            output.put("billed_quantity", data.get("billed_quantity"));
            output.put("billed_unit", data.get("billed_unit"));
            // What it actually cost, as the ledger committed it. Reported rather than left to be
            // reconstructed from the size and today's published rate: a rate can be republished
            // between the run and the reading, and the arithmetic needed a unit conversion that
            // this node's own help had to explain in three sentences.
            output.put("billed_credits", data.get("billed_credits"));
            // The provider payload stays under its own key rather than being
            // merged, so a provider field can never shadow `file` or `model`.
            output.put("provider_response", data.get("provider_response"));
            return successWithMetadata(output, context);

        } catch (Exception e) {
            logger.error("Generate node failed unexpectedly: nodeId={}, error={}", nodeId, e.getMessage(), e);
            return failure(context, startTime, resolved, "Generation failed: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    /** Resolve the params map, keeping RAW object types for whole-value templates. */
    /**
     * Resolve every param's templates, and FAIL if that cannot be done.
     *
     * <p>This used to swallow the failure and fall back to the raw params with
     * a warning. On a node that copies data around, sending
     * {@code "{{trigger:x.output.text}}"} verbatim is a cosmetic bug. On this
     * one it is a paid one: the literal text goes to the provider, an asset is
     * produced from it, and the customer is charged for a generation of the
     * template's source code. The caller cannot even tell, since the run is
     * green and an asset came back.
     *
     * <p>So the exception propagates: {@code execute} catches it and fails the
     * node before anything is dispatched, which costs nothing.
     */
    private Map<String, Object> resolveParams(ExecutionContext context) {
        if (templateAdapter == null || params.isEmpty()) {
            return new LinkedHashMap<>(params);
        }
        Map<String, Object> resolved = templateAdapter.resolveTemplates(params, context);
        return resolved != null ? new LinkedHashMap<>(resolved) : new LinkedHashMap<>(params);
    }

    /**
     * A pinned credential id, or null when nothing usable was configured.
     *
     * <p>Deliberately silent about a bad value rather than failing the node: an
     * unpinned generation runs on the account's default key for the provider,
     * which is exactly what this node did before the field existed. Refusing the
     * run instead would turn a stale saved id into a broken workflow, and the
     * catalog already falls back to the default when a pinned credential has
     * been deleted.
     */
    private static Long credentialIdValue(Object value) {
        if (value instanceof Number number) {
            long id = number.longValue();
            return id > 0 ? id : null;
        }
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try {
            long id = Long.parseLong(text);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            logger.warn("Generate node ignoring an unusable credential_id '{}': the account's "
                    + "default key for the provider is used instead", text);
            return null;
        }
    }

    private static String stringValue(Object value) {
        if (value instanceof String s) {
            return s;
        }
        return value != null && !(value instanceof Map) && !(value instanceof List)
            ? String.valueOf(value) : null;
    }

    private NodeExecutionResult failure(ExecutionContext context, long startTime, String message) {
        return failure(context, startTime, null, message);
    }

    /**
     * @param resolved the resolved params, or {@code null} when the failure happened BEFORE or
     *        DURING resolution: then the templates the author wrote are the only honest thing to
     *        show. After resolution, the column shows what the node ran with; it used to show the
     *        templates on every failure, including the ones that happened after they resolved
     *        (an unknown model, a refused parameter, a provider error), so a reference that
     *        worked read as one that did not.
     */
    private NodeExecutionResult failure(ExecutionContext context, long startTime,
                                        Map<String, Object> resolved, String message) {
        Map<String, Object> source = resolved != null ? resolved : params;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", null);
        out.put("model", stringValue(source.get("model")));
        out.put("resolved_params", resolved != null ? reportableParams(resolved, context) : reportableParams(params, null));
        return NodeExecutionResult.failureWithOutput(nodeId, message,
            enrichWithMetadata(out, context), System.currentTimeMillis() - startTime);
    }

    /** The params a generation model reads as TEXT: reported whole, like an agent's prompt. */
    private static final List<String> MODEL_TEXT_PARAMS = List.of("prompt", "negative_prompt");

    /**
     * Every configured parameter except the credential reference.
     *
     * `credential_id` names one of the AUTHOR's own provider keys, and
     * PlanSecretRedactor already strips it from a shared plan for exactly that
     * reason; `resolved_params` is read by the same people, so it stays out here too.
     * Everything else - model, prompt, size, duration, the reference image - is what
     * a reader needs to understand a generation they were charged for.
     *
     * @param context the run, when {@code source} is RESOLVED; {@code null} when it is the
     *        configured params (a failure before resolution), which are templates and hold no
     *        variable's value
     */
    private Map<String, Object> reportableParams(Map<String, Object> source, ExecutionContext context) {
        Map<String, Object> reportable = new LinkedHashMap<>();
        if (source == null) return reportable;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if ("credential_id".equals(entry.getKey())) continue;
            if (entry.getValue() == null) continue;
            reportable.put(entry.getKey(), entry.getValue());
        }
        // A reference image can arrive as a data URI, and this map lands on the step row of
        // every item: what exceeds the budget is described. The prompt is the exception, it is
        // what a reader came for: reported whole (ModelInput), with a workspace variable in
        // it withheld. The credential filter above stays first: masking by name is a separate rule.
        for (Map.Entry<String, Object> entry : reportable.entrySet()) {
            String key = entry.getKey();
            if (MODEL_TEXT_PARAMS.contains(key) && entry.getValue() instanceof String text) {
                entry.setValue(new ReportedParams.ModelInput(maskedModelText(key, text, context)));
            } else if (context != null && params.get(key) != null) {
                // Any other param: withheld (a scalar) or described by shape (a structure) when
                // its configured template pulls a workspace variable, bounded as before otherwise.
                entry.setValue(ReportedParams.valueFromConfigured(params.get(key), entry.getValue()));
            }
        }
        return ReportedParams.forReport(reportable);
    }

    /** The resolved text, or its template re-resolved with every workspace variable withheld. */
    private String maskedModelText(String key, String resolvedText, ExecutionContext context) {
        if (context == null || !(params.get(key) instanceof String template)
                || !ReportedParams.referencesAnyWorkspaceVariable(template)) {
            return resolvedText;
        }
        try {
            return resolveTemplateString(ReportedParams.maskWorkspaceReferences(template), context);
        } catch (RuntimeException e) {
            // The report must never fail the node it reports on, nor fall back to the clear value.
            return ReportedParams.WITHHELD_WORKSPACE_VARIABLE;
        }
    }
}

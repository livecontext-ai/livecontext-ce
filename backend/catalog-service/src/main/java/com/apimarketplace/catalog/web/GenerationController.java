package com.apimarketplace.catalog.web;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.catalog.service.generation.DynamicOptionsResolver;
import com.apimarketplace.catalog.service.generation.GenerationInputs;
import com.apimarketplace.catalog.service.generation.GenerationLimits;
import com.apimarketplace.catalog.service.generation.GenerationRegistry;
import com.apimarketplace.catalog.service.generation.PlatformSalesResolver;
import com.apimarketplace.catalog.tools.CatalogExecuteModule;
import com.apimarketplace.catalog.service.generation.GenerationSpec;
import com.apimarketplace.catalog.tools.generation.GenerationModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * HTTP face of the generation surface, for the two callers that are not agents:
 * the workflow builder (which has to show a model list and a price before the
 * user runs anything) and the {@code agent:generate} node in orchestrator-service
 * (which has to actually run one).
 *
 * <p><b>Why the node goes through HTTP instead of calling the catalog tool by
 * slug.</b> Running a generation is four steps, not one: resolve a public model
 * id to an endpoint, project the unified parameters onto that provider's request
 * shape, execute with the right credential and billing, then make the returned
 * asset durable. Only the third step is a catalog tool call. If orchestrator
 * reached {@code /catalog/v1/tools/{slug}/execute} directly it would have to own
 * the other three, which means a second copy of {@link GenerationRegistry} and
 * {@link com.apimarketplace.catalog.service.generation.GenerationRequestBuilder}
 * living in a service that cannot read {@code catalog.api_tools} at all. Worse,
 * it would have to compute the billable quantity itself and send it, and a
 * quantity that travels from a caller is a quantity a caller can set to zero.
 *
 * <p>So the node posts a model id and unified parameters, and this endpoint hands
 * them to {@link GenerationModule} unchanged. The quantity stays derived on this
 * side, from parameters already validated here, exactly as it is for the chat
 * tool. There is one generation implementation and both surfaces call it.
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "generation.enabled", havingValue = "true", matchIfMissing = false)
public class GenerationController {

    private final GenerationRegistry registry;
    private final GenerationModule module;
    private final DynamicOptionsResolver optionsResolver;
    private final PlatformSalesResolver platformSales;

    public GenerationController(GenerationRegistry registry, GenerationModule module,
                                DynamicOptionsResolver optionsResolver,
                                PlatformSalesResolver platformSales) {
        this.registry = registry;
        this.module = module;
        this.optionsResolver = optionsResolver;
        this.platformSales = platformSales;
    }

    /**
     * Model catalog for the workflow builder's inspector.
     *
     * <p>Deliberately a different projection from the agent's
     * {@code generation(action='models')}: the inspector additionally needs
     * {@code apiToolId} and {@code integrationName}, which are what the platform
     * price quote is keyed on. The agent never sees those because it has no
     * quote endpoint to call them against.
     */
    @GetMapping("/api/generation/models")
    public ResponseEntity<Map<String, Object>> models(
            @RequestParam(value = "kind", required = false) String kind) {
        List<GenerationRegistry.GenerationModel> models = registry.list(kind);
        // One bulk resolution for the whole list: the price lookup takes every
        // integration and endpoint at once, so fifty models cost one call.
        Map<PlatformSalesResolver.ModelRef, PlatformSalesResolver.Verdict> sold =
                platformSales.resolve(models.stream().map(PlatformSalesResolver.ModelRef::of).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GenerationRegistry.GenerationModel m : models) {
            rows.add(describeModel(m, optionsResolver.unifiedDynamicParameters(m),
                    sold.getOrDefault(PlatformSalesResolver.ModelRef.of(m), PlatformSalesResolver.Verdict.UNKNOWN)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", rows);
        out.put("count", rows.size());
        out.put("kinds", registry.kinds());
        return ResponseEntity.ok(out);
    }

    /**
     * What one parameter of one model accepts, asked of the provider.
     *
     * <p>Separate from {@code /models} on purpose. That answer is one snapshot
     * shared by every reader and cached for five minutes; this one belongs to
     * the account behind the caller's key and costs a call to the provider, so
     * it is fetched only when a dialog actually opens the field.
     *
     * <p>Takes the payer the dialog is showing, because the answer depends on
     * it: a voice list read on the platform's key is not the reader's own. The
     * dialog re-asks when that toggle moves, and the answer names the key it
     * came from so the two cannot silently diverge.
     */
    @GetMapping("/api/generation/model-options")
    public ResponseEntity<Map<String, Object>> options(
            @RequestParam("model") String model,
            @RequestParam("parameter") String parameter,
            @RequestParam(value = "credential_source", required = false) String credentialSource,
            @RequestParam(value = "credential_id", required = false) String credentialId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of(
                    "success", false, "error", "Sign in to read a provider's values."));
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("model", model);
        parameters.put("parameter", parameter);
        if (credentialSource != null && !credentialSource.isBlank()) {
            parameters.put(CatalogExecuteModule.CREDENTIAL_SOURCE_KEY, credentialSource);
        }

        // Same lifting as a run: the pinned key travels on the CONTEXT, never in
        // the parameter map, because the module behind this is the one an agent
        // also reaches and an agent must not be able to name a credential.
        Map<String, Object> credentials = new LinkedHashMap<>();
        putPinnedCredential(credentials, Map.of(CatalogExecuteModule.CREDENTIAL_ID_KEY,
                credentialId == null ? "" : credentialId));
        ToolExecutionContext context = new ToolExecutionContext(
                userId, credentials, Map.of(), java.util.Set.of(), null, null, orgId, null);

        Optional<ToolExecutionResult> result = module.execute("options", parameters, userId, context);
        Map<String, Object> out = new LinkedHashMap<>();
        if (result.isEmpty()) {
            out.put("success", false);
            out.put("error", "Could not read the values for this field.");
            return ResponseEntity.ok(out);
        }
        ToolExecutionResult r = result.get();
        out.put("success", r.success());
        if (r.success()) {
            out.putAll(asMap(r.data()));
        } else {
            // The reason travels rather than an empty list: "connect a key" and
            // "we could not ask" are different facts from "there are none", and
            // a dialog drawing an empty dropdown states the last one.
            out.put("error", r.error());
        }
        return ResponseEntity.ok(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object data) {
        return data instanceof Map ? (Map<String, Object>) data : Map.of();
    }

    /**
     * Run one generation on behalf of a workflow node.
     *
     * <p>Body: {@code {model, params:{...unified params...}, credential_source,
     * credential_id}}.
     * The billing scope headers are the same ones {@code CatalogToolsGateway}
     * already sends for an ordinary workflow tool call, so the credit debit lands
     * on the run rather than on nothing.
     */
    @PostMapping("/api/internal/catalog/generation/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Lc-Billing-Scope-Kind", required = false) String scopeKind,
            @RequestHeader(value = "X-Lc-Billing-Scope-Id", required = false) String scopeId,
            @RequestHeader(value = "X-Lc-Billing-Step-Id", required = false) String stepId) {

        Map<String, Object> parameters = body == null ? Map.of() : body;

        // The billing scope travels in the SAME shape CatalogExecuteModule
        // already reads it in, so the credit debit is scoped identically whether
        // a generation was started from the chat or from a workflow node.
        Map<String, Object> credentials = new LinkedHashMap<>();
        if (scopeId != null && !scopeId.isBlank()) {
            if ("STREAM".equalsIgnoreCase(scopeKind)) {
                credentials.put("__streamId__", scopeId);
            } else {
                credentials.put("__workflowRunId__", scopeId);
            }
        }
        if (stepId != null && !stepId.isBlank()) {
            credentials.put("__nodeId__", stepId);
        }
        putPinnedCredential(credentials, parameters);

        ToolExecutionContext context = new ToolExecutionContext(
                userId, credentials, Map.of(), java.util.Set.of(), null, null, orgId, null);

        Optional<ToolExecutionResult> result = module.execute("create", parameters, userId, context);
        if (result.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", "Generation dispatch failed"));
        }
        ToolExecutionResult r = result.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", r.success());
        if (r.success()) {
            out.put("data", r.data());
        } else {
            out.put("error", r.error());
            if (r.errorCode() != null) {
                out.put("errorCode", r.errorCode().name());
            }
            // A FAILED generation can still carry data, and dropping it here is
            // how a paid asset went missing: billing commits before the asset is
            // fetched, so a transient fetch failure comes back with the
            // provider's own (short-lived) link under asset_url. The caller has
            // already been charged; it needs that link, not only a sentence.
            if (r.data() != null) {
                out.put("data", r.data());
            }
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Run a generation the USER asked for directly, from the app.
     *
     * <p>Body: {@code {model, params:{...unified params...}, credential_source,
     * credential_id}}.
     * Same shape as the internal endpoint above and the same module underneath,
     * so a generation started from a button is priced, reserved and committed
     * exactly like one started by an agent or a workflow node.
     *
     * <p>The billing scope is minted HERE, server side, and never read from the
     * request. A generation that carries no scope is refused on purpose,
     * because a charge nobody can trace back is worse than a refusal; but that
     * rule exists to stop an UNTRACEABLE charge, not to ban a person from
     * buying something. A direct action by an authenticated user is traceable:
     * this endpoint gives it its own scope so the ledger row has an owner and a
     * reference, and a client that tried to supply one would be choosing where
     * its own charge lands. The gateway strips those headers on the way in for
     * the same reason.
     */
    @PostMapping("/api/generation/execute")
    public ResponseEntity<Map<String, Object>> executeForUser(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "error", "Sign in to run a generation."));
        }

        Map<String, Object> credentials = new LinkedHashMap<>();
        // One scope per request, minted here. It is a STREAM scope because this
        // is an interactive call with no workflow run behind it, which is the
        // same shape a chat generation already bills under.
        credentials.put("__streamId__", "ui-" + java.util.UUID.randomUUID());
        putPinnedCredential(credentials, body);

        ToolExecutionContext context = new ToolExecutionContext(
                userId, credentials, Map.of(), java.util.Set.of(), null, null, orgId, null);

        Optional<ToolExecutionResult> result =
                module.execute("create", body == null ? Map.of() : body, userId, context);
        if (result.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", false, "error", "Generation dispatch failed"));
        }
        ToolExecutionResult r = result.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", r.success());
        if (r.success()) {
            out.put("data", r.data());
        } else {
            out.put("error", r.error());
            if (r.errorCode() != null) {
                out.put("errorCode", r.errorCode().name());
            }
            // A FAILED generation can still carry data, and dropping it here is
            // how a paid asset went missing: billing commits before the asset is
            // fetched, so a transient fetch failure comes back with the
            // provider's own (short-lived) link under asset_url. The caller has
            // already been charged; it needs that link, not only a sentence.
            if (r.data() != null) {
                out.put("data", r.data());
            }
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Lift the caller's pinned key out of the request body and onto the
     * execution context.
     *
     * <p>Both callers of this are surfaces that KNOW the account's credentials:
     * the app dialog, whose picker only ever offers keys of the chosen model's
     * provider, and the {@code agent:generate} node, whose id was chosen in the
     * builder by the workflow's owner. Moving it here is what keeps it out of
     * the parameter map an agent controls: the generation tool reads the same
     * module, and an agent has no way to learn a credential id, so it must not
     * be able to state one either.
     *
     * <p>A blank or absent value is simply not carried, which the executor
     * reads as "run on the account's default key for the provider".
     */
    private static void putPinnedCredential(Map<String, Object> credentials, Map<String, Object> body) {
        Object pinned = body == null ? null : body.get(CatalogExecuteModule.CREDENTIAL_ID_KEY);
        if (pinned != null && !String.valueOf(pinned).isBlank()) {
            credentials.put(CatalogExecuteModule.CREDENTIAL_ID_CONTEXT_KEY, pinned);
        }
    }

    // ── projection ──────────────────────────────────────────────────────────

    private static Map<String, Object> describeModel(GenerationRegistry.GenerationModel m,
                                                     java.util.Set<String> dynamic,
                                                     PlatformSalesResolver.Verdict sold) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", m.modelId());
        row.put("kind", m.kind());
        row.put("label", m.label());
        row.put("provider", m.apiName());
        row.put("iconSlug", m.iconSlug());
        // The two keys a price quote needs. The endpoint carries the published
        // rate; the integration name is the platform credential the rate hangs off.
        row.put("apiToolId", m.apiToolId() == null ? null : m.apiToolId().toString());
        row.put("integrationName", m.platformCredentialName());
        // WHICH key can run it HERE. A cloud deployment resells the providers it
        // holds keys AND published prices for; a self-hosted one usually resells
        // none, and the same model is then only runnable on a key its owner
        // connected. Without this a surface finds out by running and failing.
        // Three values, because the check can fail to answer and a boolean
        // would turn that into a confident claim in one direction.
        row.put("runsOn", sold.wire());
        row.put("accepts", new TreeSet<>(m.model().capabilities()));
        row.put("required", new TreeSet<>(m.model().required()));
        // Which parameter the price multiplies, and what it becomes when the
        // inspector leaves it empty. Without the second one the estimate has to
        // fall silent on the most common state of the form (nothing typed yet),
        // even though that call has a perfectly knowable price.
        row.put("billedOn", m.model().measuringParam());
        // What a call on this model is COUNTED in, which is not the same thing
        // as what it is SOLD by: a model measured in seconds can be published
        // per minute. A surface asking for a quote has to send this, or the
        // quote cannot notice that a rate published per image can never price a
        // call counted in seconds, and it would show an amount that every run
        // is then refused for.
        row.put("measuredUnit", m.model().platformUnit());
        BigDecimal defaultQuantity = m.model().defaultMeasurement();
        row.put("defaultQuantity", defaultQuantity == null ? null : defaultQuantity.toPlainString());

        // The same shape the agent's action='models' reports, from the same
        // code: two surfaces describing one model differently is how a builder
        // shows a limit the tool does not enforce, or hides one it does, and
        // two hand-kept copies of the shaping had already drifted on the empty
        // case. Uncapped, because this one is a browser fetch where a long list
        // costs bytes rather than context.
        row.put("limits", GenerationLimits.describe(m.model(), m.catalogAllowed(), 0, dynamic));

            // The same block the agent's action='models' reports, from the same
            // code: role, how many files, and what each slot cannot be sent
            // without or sent with. Two hand-kept copies of this shaping had
            // already drifted once on the empty case.
            Map<String, Object> inputs = GenerationInputs.describe(m.spec(), m.model());
            if (!inputs.isEmpty()) row.put("inputs", inputs);
        // The same statement the agent's listing carries: what the call sends
        // whatever the caller passes. It is what tells two ids apart when a
        // provider sells a quality or an output size as its own price. No picker
        // reads it yet, they group by provider and render a flat list, but the
        // alternative for one that wants to is parsing labels, so the contract
        // carries it rather than leaving that as the only way.
        Map<String, Object> fixed = new LinkedHashMap<>(m.spec().constants());
        fixed.putAll(m.model().constants());
        if (!fixed.isEmpty()) row.put("fixed", fixed);
        row.put("price", describePrice(m.seedPrice()));
        row.put("async", m.isAsync());
        return row;
    }

    /**
     * The seed price, as components rather than a formula, so the inspector can
     * say "60 credits per second" instead of only stating a total. This is the
     * STARTING rate; the number actually charged comes from the platform quote,
     * which an admin can have overridden.
     */
    private static Map<String, Object> describePrice(GenerationSpec.Price price) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (price == null) {
            out.put("unit", "call");
            out.put("baseCredits", BigDecimal.ZERO.toPlainString());
            out.put("unitCredits", BigDecimal.ZERO.toPlainString());
            return out;
        }
        out.put("unit", price.unit());
        out.put("baseCredits", plain(price.base()));
        out.put("unitCredits", plain(price.perUnit()));
        if (price.min() != null) out.put("minCredits", plain(price.min()));
        if (price.max() != null) out.put("maxCredits", plain(price.max()));
        // What the reader's own choices do to that rate, as a table rather than
        // an amount: the surface applies it to what is currently in the form and
        // asks the quote for the total, so it needs the rule and not one result
        // of it. Same shaping as the agent listing, from the same method.
        Map<String, Object> modifiers = GenerationModule.describeModifiers(price);
        if (!modifiers.isEmpty()) out.put("modifiers", modifiers);
        return out;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }
}

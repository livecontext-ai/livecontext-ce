package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.catalog.service.credential.EndpointCredentialCapabilityService;
import com.apimarketplace.catalog.service.credential.IntegrationNames;
import com.apimarketplace.catalog.service.credential.PlatformTenant;
import com.apimarketplace.catalog.util.CredentialTypeNormalizer;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Module handling catalog tool execution.
 * Operations: execute, call (legacy alias)
 */
@Slf4j
@Component
public class CatalogExecuteModule implements ToolModule {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CredentialClient credentialClient;

    @Value("${server.port:8081}")
    private int serverPort;

    private static final Set<String> HANDLED_ACTIONS = Set.of("execute", "call");

    /** Where the platform's own keys live, as the executor resolves them. */
    private static final String PLATFORM_TENANT_ID = PlatformTenant.ID;

    /**
     * Top-level keys of the {@code catalog} tool call that are control/shaping
     * parameters, NOT tool inputs. Any OTHER top-level key is treated as a tool
     * input parameter (chat models often flatten a tool's params to top level
     * instead of nesting them under {@code params}). Kept in sync with the
     * {@code catalog} tool schema in {@code CatalogToolsProvider}.
     */
    /**
     * Reserved so a caller cannot smuggle a price-determining value into the
     * tool's inputs. These keys are IGNORED, never read: the generation model
     * and the billable quantity reach billing through the typed
     * {@link GenerationBilling} argument of {@link #executeGeneration}, which
     * agent input cannot reach. Keeping them reserved means an agent that does
     * send them simply has them dropped rather than forwarded upstream as a
     * bogus parameter.
     */
    public static final String GENERATION_MODEL_KEY = "__generationModelId__";

    /** @see #GENERATION_MODEL_KEY */
    public static final String GENERATION_QUANTITY_KEY = "__generationQuantity__";

    /**
     * Which credential pool to use, when the caller made an explicit choice
     * ({@code "user"} or {@code "platform"}). It is a control key, not a tool
     * input: forwarding it upstream would send a provider a parameter it never
     * declared, and treating it as absent would silently ignore the choice.
     */
    public static final String CREDENTIAL_SOURCE_KEY = "credential_source";

    /**
     * WHICH of the caller's own keys runs the call, when it holds more than one
     * for the provider.
     *
     * <p>Only meaningful beside {@code credential_source='user'}: the catalog
     * reads the pinned id exclusively on that branch
     * ({@code HttpExecutionService.selectedUserCredentialId}), and a pinned id
     * whose credential has since been deleted falls back to the integration's
     * default rather than failing, so a saved choice survives a reconnection.
     *
     * <p>A control key like the source beside it: it names an account object,
     * never a provider parameter, so it must never reach the upstream request.
     */
    public static final String CREDENTIAL_ID_KEY = "credential_id";

    /**
     * WHICH of the caller's own accounts runs the call, named rather than numbered.
     *
     * <p>The id beside it is not something a chat agent can learn: ids reach this
     * module through the execution context, which tool arguments never touch. A NAME
     * is different - it is in every credential listing the agent already reads, so it
     * is the only handle it can actually hold. Without it an agent that could SEE that
     * the default account lacks a scope, and that another account has it, still had no
     * way to run on the other one.
     *
     * <p>Forwarded as the run-time selection the catalog already implements
     * ({@code selectedCredentialName} + {@code credentialSelectionStrict}), so an
     * unmatched name REFUSES instead of quietly running on the default account. That
     * strictness is the point: a silent substitution here would run against an account
     * the caller did not choose and report success.
     *
     * <p>A control key like the two above: it names an account, never a provider
     * parameter, so it must never reach the upstream request.
     */
    public static final String CREDENTIAL_NAME_KEY = "credential_name";

    /**
     * Where a pinned credential id is read FROM: the execution context, not the
     * caller's parameters.
     *
     * <p>Deliberately the same kind of channel the billing scope travels on.
     * Only the surfaces that know the account's credentials put a value here -
     * the app dialog and the {@code agent:generate} node, through
     * {@code GenerationController} - and the tool arguments of a chat agent
     * never reach it. That is what makes the generation tool's help TRUE when
     * it says the account's default key runs: an agent calling that tool
     * cannot learn an id, and cannot state one either.
     *
     * <p>An agent BUILDING a workflow is a different caller and does reach it,
     * through the node's params, which is what lets it carry a pin it found
     * when rewriting a node. It is bounded rather than trusted: the value must
     * be a real id (refused at build time by both validation paths) and the
     * key it names must belong to the endpoint's own provider (refused at run
     * time by {@code HttpExecutionService}), so the worst a guessed number can
     * do is name another of the owner's keys for the same provider.
     */
    public static final String CREDENTIAL_ID_CONTEXT_KEY = "__credentialId__";

    /**
     * The context keys a charge can land on.
     *
     * <p>Named here because this is the class that turns them into billing
     * headers. A caller doing something that is NOT the work being paid for
     * (reading a list to fill a dropdown) drops them, so the read cannot reserve
     * and commit against the turn or the run the caller happens to be inside.
     *
     * <p><b>They mean more than billing elsewhere.</b> Two of them are also
     * {@code ToolAuthorizationScope}'s test for whether an approval card can be
     * raised. Nothing on this path consults that today (the approval gate here
     * is {@code approvedServices()}, which a caller dropping these keeps), but a
     * future gate that did would be silently relaxed by the same drop. Anything
     * removing these must check what else reads them first.
     */
    public static final java.util.Set<String> BILLING_SCOPE_KEYS =
            java.util.Set.of("__streamId__", "__workflowRunId__", "__nodeId__");

    /**
     * Stable codes that LEAD a refusal message on this path.
     *
     * <p>They are part of the message and not only of {@code errorCode} because
     * the message is the only channel that reaches an agent: the MCP bridge
     * forwards a failed tool result as its {@code error} text alone, and the
     * direct-API loop appends {@code "Error: " + error} to the conversation.
     * {@code errorCode} and {@code metadata} survive only on the REST surfaces.
     * Leading with a code is also what the billing refusals on this same path
     * already do ({@code PLATFORM_NOT_AVAILABLE}, {@code GENERATION_SIZE_UNKNOWN}),
     * so a caller learns one vocabulary rather than three.
     */
    public static final String CREDENTIALS_REQUIRED_CODE = "CREDENTIALS_REQUIRED";

    /** @see #CREDENTIALS_REQUIRED_CODE */
    public static final String UPSTREAM_REJECTED_CODE = "UPSTREAM_REJECTED";

    /** @see #CREDENTIALS_REQUIRED_CODE */
    public static final String TOOL_CALL_FAILED_CODE = "TOOL_CALL_FAILED";

    /**
     * The tool_id names no tool any more. Distinct from {@link #TOOL_CALL_FAILED_CODE},
     * whose remedy is "send it once more": a missing tool fails identically every time,
     * so the only useful step is to look the tool up again.
     */
    public static final String TOOL_NOT_FOUND_CODE =
            com.apimarketplace.catalog.service.exception.ToolNotFoundException.ERROR_CODE;

    /**
     * The provider refused an authenticated call, and the account this call used is
     * the reason why.
     *
     * <p>Distinct from {@link #CREDENTIALS_REQUIRED_CODE} because the remedy is the
     * opposite one. That code means no usable key reached the provider, and its
     * answer is "connect one". This code means a key DID reach the provider and was
     * refused for what it was granted, so "connect one" is the single step that
     * changes nothing. A caller branches on the code before it reads a word of the
     * sentence, and the two situations must not share one.
     */
    public static final String CREDENTIALS_INSUFFICIENT_CODE = "CREDENTIALS_INSUFFICIENT";

    /**
     * The upstream code for "the account named for this run could not be used".
     *
     * <p>Matched instead of the bare HTTP status it arrives with. That route answers 422
     * from one place today, so keying on the status alone would be right by coincidence,
     * and the next refusal to use it would inherit advice about an argument its caller
     * never sent. The body already says which refusal it is.
     */
    private static final String CREDENTIAL_SELECTION_UNRESOLVED =
            com.apimarketplace.catalog.service.exception.CredentialSelectionException.ERROR_CODE;

    private static final Set<String> RESERVED_EXECUTE_KEYS = Set.of(
            "action", "tool_id", "params", "parameters", "input", "inputs",
            "expand", "max_items", "topics", "query", "api", "apis", "limit",
            "api_definition", "api_id", CREDENTIAL_SOURCE_KEY, CREDENTIAL_ID_KEY,
            CREDENTIAL_NAME_KEY,
            // V428 generation context: pricing metadata, never tool inputs.
            GENERATION_MODEL_KEY, GENERATION_QUANTITY_KEY);

    /**
     * Says which of the caller's accounts could run an endpoint, and what to do when
     * none can. Setter-injected so the hand-built modules in the unit tests keep
     * compiling and a slice without the bean refuses exactly as it refused before:
     * the capability sharpens a refusal, it never creates one.
     */
    private EndpointCredentialCapabilityService credentialCapability;

    @Autowired(required = false)
    public void setCredentialCapability(EndpointCredentialCapabilityService credentialCapability) {
        this.credentialCapability = credentialCapability;
    }

    public CatalogExecuteModule(ObjectMapper objectMapper, CredentialClient credentialClient) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.credentialClient = credentialClient;
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String toolName) {
        return HANDLED_ACTIONS.contains(toolName);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String toolName, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(toolName)) {
            return Optional.empty();
        }

        var accessDenied = ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "catalog", toolName);
        if (accessDenied.isPresent()) return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));

        return Optional.of(executeCatalogExecute(parameters, context, null));
    }

    /**
     * Execute a catalog tool with server-derived generation pricing context.
     *
     * <p>The generation model and the billable quantity are what DECIDE the
     * amount charged, so they must never be readable from caller input. They
     * arrive here as an explicit argument, computed by {@code GenerationModule}
     * from parameters it has already validated, and they are deliberately not
     * carried in the {@code parameters} map: an agent can put anything it likes
     * in there, and a caller-supplied quantity of zero would be a free
     * generation.
     */
    public Optional<ToolExecutionResult> executeGeneration(Map<String, Object> parameters,
                                                            ToolExecutionContext context,
                                                            GenerationBilling billing) {
        var accessDenied = ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "catalog", "execute");
        if (accessDenied.isPresent()) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));
        }
        return Optional.of(executeCatalogExecute(parameters, context, billing));
    }

    /**
     * Server-derived pricing context for one generation call.
     *
     * @param modelId  generation model whose published price applies
     * @param quantity size of the call in PLATFORM units (seconds, assets,
     *                 characters), never the provider's and never pre-converted
     *                 into the price's unit: the published rate is what converts
     *                 it, so the two cannot be scaled differently
     */
    /**
     * What the generation surface measured about this call, for pricing.
     *
     * @param quantityUnit the PLATFORM unit {@code quantity} is counted in
     *        (call, second, image, character). Carried alongside the number
     *        because a quantity without its unit cannot be checked against the
     *        published rate: a row priced per image and a call measured in
     *        seconds both arrive as a bare 10, and multiplying them charges a
     *        ten second clip ten times the per-image rate with nothing able to
     *        notice.
     */
    /**
     * @param priceMultiplier what the caller's CHOICES do to the price of this
     *        call, from the model's declared modifiers (a 1080p render, a
     *        reference image the provider charges to read). Separate from
     *        {@code quantity} because it is a different question: the quantity
     *        is how big the call is, this is what that size costs. Never null
     *        and never below zero at the point it is sent; 1 means the call
     *        sits exactly at the published rate, which is what every model
     *        without modifiers reports.
     */
    public record GenerationBilling(String modelId, java.math.BigDecimal quantity, String quantityUnit,
                                     java.math.BigDecimal priceMultiplier) {

        /** The shape callers spoke before price modifiers existed: a call at the published rate. */
        public GenerationBilling(String modelId, java.math.BigDecimal quantity, String quantityUnit) {
            this(modelId, quantity, quantityUnit, java.math.BigDecimal.ONE);
        }
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeCatalogExecute(Map<String, Object> parameters,
                                                      ToolExecutionContext context,
                                                      GenerationBilling billing) {
        String toolId = (String) parameters.get("tool_id");
        if (toolId == null || toolId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "tool_id is required");
        }
        ToolExecutionResult malformedId = checkToolIdShape(toolId);
        if (malformedId != null) {
            return malformedId;
        }

        // Resolve the tool's call parameters. The agent MAY nest them under the
        // documented `params` key (schema-preferred), under a legacy alias
        // (`parameters` / `input` / `inputs`), OR - as chat models frequently do
        // for a plain function-call - pass each tool parameter as a TOP-LEVEL
        // sibling of `action`/`tool_id` (e.g.
        // catalog(action='execute', tool_id='x', page_id='123')). The workflow
        // path (CatalogToolsGateway) always nests the resolved node params under
        // `parameters`, so it never hit this. The ad-hoc chat path did: a required
        // location:"path" param like Facebook's {page_id} then never reached
        // catalog, leaving the URL literal ".../{page_id}/posts" which fails URI
        // parsing ("Illegal character in path"). Gather ALL of these shapes so a
        // path/query/body param supplied any legitimate way still reaches catalog.
        // Unknown keys are harmless: filterParametersByToolDefinition on the
        // catalog side keeps only names declared in api_tool_parameters.
        Map<String, Object> input = toolInputsFor(parameters);

        String tenantId = context != null ? context.tenantId() : null;
        log.info("JIT executing tool {} for tenant {} with input: {}", toolId, tenantId, input.keySet());

        ToolExecutionResult restrictionCheck = checkToolRestriction(toolId, context);
        if (restrictionCheck != null) {
            return restrictionCheck;
        }

        ToolExecutionResult choiceConflict = checkCredentialChoice(parameters);
        if (choiceConflict != null) {
            return choiceConflict;
        }

        ToolExecutionResult approvalCheck = checkServiceApproval(toolId, context,
                normalizedCredentialSource(parameters), chosenCredentialName(parameters) != null);
        if (approvalCheck != null) {
            return approvalCheck;
        }

        try {
            String localUrl = "http://localhost:" + serverPort;
            String url = localUrl + "/catalog/v1/tools/" + toolId + "/execute";

            HttpHeaders headers = CatalogToolHeaderSupport.jsonHeaders(tenantId, context);

            // V148+ chat-scope billing headers. Pull __streamId__ from agent
            // execution context credentials. ToolExecutionManager reads these
            // and feeds CatalogToolBillingService.preflightReserve BEFORE the
            // call, then commitOnSuccess / releaseOnFailure after it.
            // RUN-priority precedence still applies: if the chat agent runs
            // INSIDE a workflow (embedded agent), __workflowRunId__ wins and
            // we set scopeKind=RUN instead. CatalogToolBillingService.BillingScope.of
            // also re-applies this precedence as a defense-in-depth check.
            Map<String, Object> creds = context != null && context.credentials() != null
                    ? context.credentials() : Map.of();
            Object workflowRunId = creds.get("__workflowRunId__");
            Object streamId = creds.get("__streamId__");
            Object stepId = creds.get("__nodeId__");
            if (workflowRunId != null) {
                headers.set("X-Lc-Billing-Scope-Kind", "RUN");
                headers.set("X-Lc-Billing-Scope-Id", String.valueOf(workflowRunId));
                if (stepId != null) {
                    headers.set("X-Lc-Billing-Step-Id", String.valueOf(stepId));
                }
            } else if (streamId != null) {
                headers.set("X-Lc-Billing-Scope-Kind", "STREAM");
                headers.set("X-Lc-Billing-Scope-Id", String.valueOf(streamId));
            }

            // V428 generation pricing context, taken from the SERVER-DERIVED
            // argument and never from `parameters`. These two values decide the
            // amount charged, so reading them from caller input would let an
            // agent send a quantity of zero and generate for free.
            if (billing != null) {
                if (billing.modelId() != null) {
                    headers.set("X-Lc-Generation-Model", billing.modelId());
                }
                if (billing.quantity() != null) {
                    headers.set("X-Lc-Generation-Quantity", billing.quantity().toPlainString());
                }
                // The unit that quantity is counted in. Without it the number is
                // uncheckable: a per-image row and a call measured in seconds
                // both arrive as a bare 10, and the price would be the product
                // of two amounts that describe different things.
                if (billing.quantityUnit() != null && !billing.quantityUnit().isBlank()) {
                    headers.set("X-Lc-Generation-Unit", billing.quantityUnit());
                }
                // What the call's own choices do to the price. Sent only when
                // it actually changes something, so an ordinary generation's
                // request looks exactly as it did before modifiers existed and
                // the absent header keeps meaning "at the published rate".
                if (billing.priceMultiplier() != null
                        && billing.priceMultiplier().compareTo(java.math.BigDecimal.ONE) != 0) {
                    headers.set("X-Lc-Generation-Multiplier",
                            billing.priceMultiplier().toPlainString());
                }
            }

            Map<String, Object> requestBody = new LinkedHashMap<>();

            // Extract shaping params (`expand`, `max_items`) BEFORE forwarding
            // to the catalog HTTP body. Cache key is built from the body's
            // `parameters` only - keeping shaping params separate ensures two
            // calls with the same call params but different shaping params hit
            // the cache (no API re-fetch); shaping then runs fresh per call.
            ShapingParams shaping = extractShapingParams(parameters, input);
            input = shaping.remainingParams();

            requestBody.put("parameters", input);

            if (shaping.expand() != null && !shaping.expand().isEmpty()) {
                requestBody.put("expand", shaping.expand());
                log.info("catalog_call: Including expand paths: {}", shaping.expand());
            }
            if (shaping.maxItems() != null) {
                requestBody.put("max_items", shaping.maxItems());
                log.info("catalog_call: Including max_items: {}", shaping.maxItems());
            }
            // Credential source. Absent → the catalog applies the implicit
            // fallback-if-priced rule (try user, fall back to platform when
            // pricing is published for the endpoint), which is the agentic
            // default this module was written for.
            //
            // Present → the caller made an explicit design-time choice and the
            // catalog honours it strictly, with no fallback to the other pool.
            // GenerationModule has always forwarded the caller's
            // credential_source here, and the `agent:generate` node surfaces it
            // as a toggle; without this branch the value was silently dropped
            // and a workflow that asked for its OWN key was billed the platform
            // price instead.
            String credentialSource = normalizedCredentialSource(parameters);
            applyCredentialChoice(requestBody, parameters, context);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // V148+ unified billing: catalog-service ran the reserve → call →
                // commit/release lifecycle (ToolExecutionManager →
                // CatalogToolBillingService). A 200 here means the reservation was
                // taken and committed; a refusal never reaches this branch, it
                // arrives as the 402 handled in handleHttpClientError.
                // Scope context is propagated via X-Lc-Billing-Scope-* headers above.
                String chosenName = chosenCredentialName(parameters);
                // An account can also be chosen WITHOUT a name: a numeric credential_name is
                // an id, and a pinned credential comes from the context. The capability
                // listing keys its accounts by name only, so neither can be matched against
                // it - and a gap on some other account is then not evidence about this call.
                boolean chosenById = normalizedCredentialId(chosenName) != null
                        || pinnedUserCredentialId(parameters, context) != null;
                return handleSuccessResponse(response.getBody(), toolId, credentialSource,
                        chosenById ? null : chosenName, chosenById, context);
            } else if (response.getStatusCode() == HttpStatus.UNAUTHORIZED ||
                       response.getStatusCode() == HttpStatus.FORBIDDEN) {
                return credentialsRequired(
                    toolId, Map.of(), extractMetadataFromResponse(response.getBody()),
                    credentialSource, context);
            } else {
                Map<String, Object> errorMetadata = extractMetadataFromResponse(response.getBody());
                return ToolExecutionResult.failure(
                    ToolErrorCode.EXECUTION_FAILED,
                    UPSTREAM_REJECTED_CODE + ": the call was refused with HTTP "
                        + response.getStatusCode().value() + ", so nothing ran and nothing was charged. "
                        + "Change the call before sending it again: the same call is refused the same way.",
                    errorMetadata
                );
            }

        } catch (HttpClientErrorException e) {
            return handleHttpClientError(e, toolId);
        } catch (Exception e) {
            log.error("Error executing tool {}: {}", toolId, e.getMessage(), e);
            // The exception message names the internal host it could not reach,
            // or carries an upstream 5xx body inline. Neither is something a
            // caller can act on, and this text is rendered verbatim to a person
            // by the generation dialog, so the detail rides on metadata (and in
            // the log line above) while the caller gets a sentence.
            return ToolExecutionResult.failure(
                ToolErrorCode.EXECUTION_FAILED,
                // Deliberately says nothing about WHERE it failed. This catch also covers a body
                // that could not be read AFTER the provider answered, where the call may well
                // have had its effect, so telling a caller the provider never answered would
                // invite a duplicate send. One retry at most, and then a person.
                TOOL_CALL_FAILED_CODE + ": this call could not be completed and nothing was charged, "
                    + "but whether it took effect is unknown, so treat a repeat as a possible "
                    + "duplicate. Send it once more at most; if it fails the same way, report it to "
                    + "the account's owner rather than retrying further.",
                e.getMessage() == null ? Map.of() : Map.of("failureDetail", e.getMessage())
            );
        }
    }

    /**
     * The refusal a caller is shown when no key of the tool's provider is
     * available for the credential pool the call asked for.
     *
     * <p>This used to hand back {@code objectMapper.writeValueAsString(resultMap)},
     * the WHOLE upstream envelope, as the error message: internal ids, an
     * endpoint path, a request id. Two readers see that string and neither can
     * use it. An agent reads the message text and nothing else (the bridge and
     * the direct-API loop both drop {@code metadata} and {@code errorCode} on a
     * failure, forwarding only {@code error}), and a person reads it verbatim in
     * the generation dialog, which renders {@code result.error} as the refusal.
     *
     * <p>So the message is a sentence naming the remedy, and it is led by a
     * STABLE CODE, exactly like the sibling refusals on this path
     * ({@code PLATFORM_NOT_AVAILABLE}, {@code GENERATION_SIZE_UNKNOWN}). The
     * code is what an agent branches on without parsing prose; it is the only
     * discriminator that survives to the model, which is why it is in the text
     * and not only in {@code errorCode}. The structured detail an interface
     * needs stays on {@code metadata}, in the same vocabulary the credential
     * pre-flight already uses ({@code serviceType} / {@code serviceName} /
     * {@code credential}), so a card can still be drawn from it.
     *
     * <p>The remedy is chosen from the pool the CALL asked for, because that is
     * what upstream refused, and naming a remedy the account does not have is
     * worse than naming none. An explicit {@code platform} was refused for want
     * of a platform key, an explicit {@code user} for want of the caller's own,
     * and an unstated source only when NEITHER pool could answer.
     */
    private ToolExecutionResult credentialsRequired(String toolId,
                                                     Map<String, Object> upstreamResult,
                                                     Map<String, Object> upstreamMetadata,
                                                     String requestedSource,
                                                     ToolExecutionContext context) {
        String integration = firstNonBlank(
                text(upstreamResult, "credential_name"),
                text(upstreamMetadata, "iconSlug"));
        String service = integration == null ? "provider" : integration.toLowerCase(Locale.ROOT);
        String display = displayName(integration);

        // What the caller could have read BEFORE calling: which of its accounts could run
        // this endpoint, and whether a standard connection could ever grant what it needs.
        // Consulted here because the remedy below used to be written without it, and got
        // it wrong in the one case that costs the most: an endpoint whose scope a standard
        // connection can never grant was answered with "add your key to this account",
        // which is precisely the step that does not work.
        Map<String, Object> capability = credentialCapability(toolId, integration, context);
        String capabilityRemedy = capability == null ? null : text(capability, "remedy");

        String remedy;
        if (capabilityRemedy != null) {
            // The capability knows the account inventory; these branches only know which
            // pool was asked for. Where it has something to say, it says the more specific
            // thing, including the one answer none of the branches below can reach: another
            // of the caller's own accounts CAN run this, name it.
            remedy = "this call could not run and nothing was charged. " + capabilityRemedy;
        } else if ("platform".equals(requestedSource)) {
            remedy = "this call asked for the platform's " + display + " key and this platform has none "
                    + "configured, so it could not run and nothing was charged. Run it on your own "
                    + display + " key instead (credential_source='user'), or ask the account's owner to "
                    + "configure the platform key.";
        } else if ("user".equals(requestedSource)) {
            remedy = "this call asked for your own " + display + " key and this account has none "
                    + "configured, so it could not run and nothing was charged. Add your " + display
                    + " key to this account, or run it on the platform's key instead "
                    + "(credential_source='platform'), which works only where the platform sells this "
                    + "integration.";
        } else {
            remedy = "no " + display + " key is available, on this account or on this platform, so the "
                    + "call could not run and nothing was charged. Add your " + display + " key to this "
                    + "account to run it. Whether the platform sells this integration is the account "
                    + "owner's decision, and nothing you send changes it.";
        }

        Map<String, Object> metadata = new LinkedHashMap<>(upstreamMetadata);
        metadata.put("credentialNeeded", true);
        metadata.put("serviceType", service);
        metadata.put("serviceName", display);
        if (!metadata.containsKey("iconSlug")) {
            metadata.put("iconSlug", service);
        }
        String credentialType = text(upstreamResult, "credential_type");
        if (credentialType != null) {
            metadata.put("credential", Map.of("type", credentialType));
        }
        if (capability != null) {
            // The structured half of what the sentence says, in the SAME shape the tool
            // contract publishes it, so a caller that reads one can read the other without
            // a second vocabulary. Under its OWN key: `credential` carries the catalog raw
            // credential type and an interface already matches on that exact string, so
            // replacing it with the normalised one would break a reader to add a field.
            metadata.put("credentialCapability", capability);
        }
        // Which pool was asked for. Two different remedies hang off it, so an
        // interface that wants to offer one has to be able to tell them apart
        // without re-reading the sentence.
        metadata.put("credentialSource", requestedSource == null ? "unspecified" : requestedSource);
        metadata.put("toolId", toolId);
        metadata.put("visualization", Map.of(
                "type", "credential",
                "id", toolId,
                "title", display,
                "iconSlug", service,
                "serviceName", display));

        log.info("Tool {} requires {} credentials (requested source: {})",
                toolId, service, requestedSource == null ? "unspecified" : requestedSource);
        return ToolExecutionResult.failure(
                ToolErrorCode.CREDENTIALS_REQUIRED,
                CREDENTIALS_REQUIRED_CODE + ": " + remedy,
                metadata);
    }

    /**
     * What this caller could do about the endpoint credential, or null when nothing
     * could be resolved.
     *
     * <p>Costs one loopback read of the tool contract, paid only on a path that has
     * already failed. Fail-open in every direction: no bean, no integration, no
     * contract, any exception - the refusal falls back to the sentence it had before,
     * which is still true, just less specific.
     */
    private Map<String, Object> credentialCapability(String toolId, String integration,
                                                      ToolExecutionContext context) {
        if (credentialCapability == null || integration == null || integration.isBlank()
                || context == null || context.tenantId() == null) {
            return null;
        }
        try {
            Map<String, Object> info = fetchToolInfo(toolId, context);
            if (info == null) {
                return null;
            }
            // The contract's own integration name wins over the one the upstream refusal
            // carried. Four places now ask this question and they must ask it about the
            // same string, or they answer about different accounts; integrationName is the
            // one the executor resolves credentials by.
            String keyedOn = firstNonBlank(
                    info.get("integrationName") == null ? null : info.get("integrationName").toString(),
                    integration);
            Map<String, Object> requirement = CredentialTypeNormalizer.buildRequirement(info);
            Map<String, Object> enriched = credentialCapability.describe(
                    requirement, keyedOn, requiredScopesOf(info), context.tenantId());
            return enriched == requirement ? null : enriched;
        } catch (Exception e) {
            log.debug("Credential capability unavailable for tool {}: {}", toolId, e.getMessage());
            return null;
        }
    }

    /** The endpoint declared OAuth scopes as read off its contract, never null. */
    private static List<String> requiredScopesOf(Map<String, Object> info) {
        Object scopes = info == null ? null : info.get("requiredScopes");
        if (!(scopes instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    /**
     * The tool contract, fetched over loopback with the caller's own headers.
     *
     * <p>One reader for the two places that need it - the pre-flight credential gate and
     * the refusal - because they answer the same caller about the same endpoint and must
     * not disagree about what it requires.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchToolInfo(String toolId, ToolExecutionContext context) {
        try {
            String url = "http://localhost:" + serverPort + "/api/catalog/tools/" + toolId + "/info";
            HttpHeaders headers = CatalogToolHeaderSupport.jsonHeaders(
                    context == null ? null : context.tenantId(), context);
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                return null;
            }
            return response.getBody();
        } catch (Exception e) {
            log.debug("Tool contract unavailable for {}: {}", toolId, e.getMessage());
            return null;
        }
    }

    /**
     * Whether a scope gap can actually explain THIS call's refusal.
     *
     * <p>A scope gap somewhere in the tenant is not evidence about the key that was sent.
     * Two refusals reach the same 403 and neither is about scopes:
     *
     * <ul>
     *   <li>The call used the PLATFORM pool. Those keys are the platform's own, so a user
     *       account short of a scope says nothing about them, and telling the agent to
     *       re-authorise its Gmail account cannot lift a plan or quota refusal.</li>
     *   <li>The call NAMED an account. Only that account's scopes are in play: if it is
     *       fine and a sibling is short, the remedy computed from the sibling reads
     *       {@code Run it with credential_name="A"}, naming the account the call just
     *       used, which invites an identical retry.</li>
     * </ul>
     *
     * <p>Both then fall through to {@code UPSTREAM_REJECTED}, which carries the provider's
     * own sentence and says retrying unchanged is refused the same way. That is the honest
     * answer when the cause is not visible from here.
     */
    private static boolean scopeGapExplainsThisCall(Map<String, Object> capability,
                                                    String requestedCredentialSource,
                                                    String chosenCredentialName,
                                                    boolean accountChosenById) {
        if ("platform".equals(requestedCredentialSource)) {
            return false;
        }
        if (accountChosenById) {
            // The listing carries no ids, so the account that ran this call cannot be picked
            // out of it. Saying nothing is the honest answer; blaming whichever account
            // happens to be short would name an account the caller never used.
            return false;
        }
        if (chosenCredentialName != null) {
            return accountMissesAScope(capability, chosenCredentialName, false);
        }
        // Nothing was chosen, so the DEFAULT account ran it, and only its gap is evidence.
        // Falling back to "any" when no entry declares itself the default keeps the branch
        // working on a listing that does not say (an older capability, or a single account).
        if (anyAccountDeclaresItselfDefault(capability)) {
            return accountMissesAScope(capability, null, true);
        }
        return accountMissesAScope(capability, null, false);
    }

    /**
     * The scope gap of ONE account, or of any of them.
     *
     * <p>Name matching is trimmed and case-insensitive, matching the layer directly below:
     * the catalog resolves {@code credential_name} that way, so a call that ran fine as
     * {@code "jaden"} against the account {@code "Jaden"} must not then lose its remedy here
     * on a capitalisation the selection itself ignored.
     */
    private static boolean accountMissesAScope(Map<String, Object> capability,
                                               String name, boolean defaultOnly) {
        Object accounts = capability == null ? null : capability.get("accounts");
        if (!(accounts instanceof List<?> list)) {
            return false;
        }
        for (Object account : list) {
            if (!(account instanceof Map<?, ?> map)) {
                continue;
            }
            if (name != null && !matchesName(name, map.get("name"))) {
                continue;
            }
            if (defaultOnly && !Boolean.TRUE.equals(map.get("isDefault"))) {
                continue;
            }
            if (map.get("missingScopes") instanceof List<?> missing && !missing.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesName(String chosen, Object accountName) {
        return accountName != null
                && chosen.trim().equalsIgnoreCase(String.valueOf(accountName).trim());
    }

    private static boolean anyAccountDeclaresItselfDefault(Map<String, Object> capability) {
        Object accounts = capability == null ? null : capability.get("accounts");
        if (!(accounts instanceof List<?> list)) {
            return false;
        }
        for (Object account : list) {
            if (account instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("isDefault"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A catalog id, as {@code catalog(action='search')} hands it back: lowercase hex only.
     *
     * <p>Not {@code a-fA-F}. The resolver matches lowercase, so an uppercase UUID passed this
     * gate and then failed to resolve, which put the call back in the generic catch this whole
     * check exists to keep it out of. Refused here instead, with the sentence that fixes it:
     * pass the id as search returned it.
     */
    private static final Pattern TOOL_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /**
     * A catalog slug. Kebab-case is not a convention here, it is the whole set: all
     * 32719 tool slugs and all 991 api slugs in production match this, none carries an
     * underscore, a dot, a colon or a slash. So a value outside it cannot name a tool,
     * and saying so costs one retry instead of a silent dead end.
     */
    private static final Pattern TOOL_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    /**
     * Refuses a {@code tool_id} that cannot name a tool, or null when it might.
     *
     * <p>Shape only. Whether the tool EXISTS is the resolver's answer and stays there;
     * this rejects the values that never reach a resolver at all, which used to leave
     * the call in the generic {@code catch} below and be reported as
     * {@code TOOL_CALL_FAILED: Nothing in the call itself causes this. Send it once
     * more, and if it fails the same way report it to the account's owner}. Every
     * clause of that was wrong for this cause: the call is the only thing that causes
     * it, resending is futile, and the owner has nothing to fix. A caller that read it
     * did the honest thing and filed the tool as broken.
     *
     * <p>The value that produced it is worth keeping in mind, because it is the one an
     * agent forms naturally: {@code catalog(action='search')} answers with
     * {@code {"id": "<uuid>", "name": "get_updates", "provider": "Telegram"}}, three
     * different APIs publish a tool named {@code get_updates}, and the caller composed
     * {@code telegram:get_updates} out of the two fields that were readable as a name.
     * Only {@code id} identifies one tool, so that is what the sentence points at.
     */
    private static ToolExecutionResult checkToolIdShape(String toolId) {
        // Checked RAW, deliberately not trimmed: the caller concatenates this exact value
        // into the execute URL, so accepting " <uuid> " here would pass the gate and fail
        // one hop later with a message about something else. Surrounding whitespace is a
        // malformed id like any other, and saying so costs one retry.
        String value = toolId;
        if (TOOL_UUID.matcher(value).matches() || TOOL_SLUG.matcher(value).matches()) {
            return null;
        }
        // api-slug/tool-slug, the shape a workflow step sends.
        int slash = value.indexOf('/');
        if (slash > 0 && value.indexOf('/', slash + 1) < 0
                && TOOL_SLUG.matcher(value.substring(0, slash)).matches()
                && TOOL_SLUG.matcher(value.substring(slash + 1)).matches()) {
            return null;
        }
        // Said before the general rule: a caller that joined a provider to a tool name
        // is one edit away, and naming that edit is worth more than restating the rule.
        String separator = value.contains(":") ? ":" : (value.contains(".") ? "." : null);
        String guess = separator == null ? ""
                : " A provider and a tool name joined by '" + separator + "' is not an id, and "
                        + "several APIs publish a tool of the same name.";
        return ToolExecutionResult.failure(
                ToolErrorCode.INVALID_PARAMETER_VALUE,
                "tool_id '" + toolId + "' cannot name a tool, so nothing ran and nothing was "
                        + "charged." + guess + " Pass the `id` field of the tool as "
                        + "catalog(action='search') returned it: it is the only value that "
                        + "identifies one tool. Search again if you no longer hold it.");
    }

    /** First non-blank of the given values, or null when they are all blank. */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /**
     * One quotable line of an upstream complaint, or null when there is none.
     *
     * <p>Bounded, and dropped entirely when it turns out to be a document
     * rather than a sentence. An upstream that answers with a nested JSON body
     * under its own {@code message} would otherwise put the envelope straight
     * back into the text this whole change exists to keep out of it.
     */
    private static String shortDetail(String detail) {
        if (detail == null) return null;
        String trimmed = detail.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return null;
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }

    /** A map entry as a trimmed string, or null when absent or blank. */
    private static String text(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * An integration name as a reader would write it. The catalog stores it
     * lowercase ({@code elevenlabs}), and a sentence that names a company in
     * lowercase reads as a typo rather than as the brand the user has to go
     * and connect.
     */
    private static String displayName(String integration) {
        return IntegrationNames.displayName(integration);
    }

    /**
     * The caller's explicit credential choice, or null when it did not make one.
     * Only the two values the catalog understands are forwarded: anything else
     * is treated as "no choice" rather than as a third mode, so a typo falls
     * back to the safe agentic default instead of pinning an unknown pool.
     */
    static String normalizedCredentialSource(Map<String, Object> parameters) {
        Object raw = parameters == null ? null : parameters.get(CREDENTIAL_SOURCE_KEY);
        if (raw == null) return null;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return "user".equals(value) || "platform".equals(value) ? value : null;
    }

    /**
     * The parameters that are meant for the PROVIDER, gathered from every shape
     * a caller legitimately uses.
     *
     * <p>Reserved control keys are excluded here and nowhere else, which is what
     * makes this the one place the distinction between "a dimension of the call"
     * and "an instruction to the platform" is drawn. A control key that slipped
     * through would reach the upstream request as a field the provider never
     * declared, and a correctly configured call would be refused for something
     * the caller never wrote.
     *
     * <p>An explicitly nested value wins over a top-level stray of the same
     * name: the nested form is the documented one, so it is the one the caller
     * meant.
     */
    static Map<String, Object> toolInputsFor(Map<String, Object> parameters) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (parameters == null) return input;
        Map<String, Object> nested = firstMapValue(parameters, "params", "parameters", "input", "inputs");
        if (nested != null) {
            input.putAll(nested);
        }
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            if (entry.getValue() == null || RESERVED_EXECUTE_KEYS.contains(entry.getKey())) {
                continue;
            }
            if (input.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
                log.debug("catalog_execute: Treating top-level '{}' as a tool input parameter", entry.getKey());
            }
        }
        return input;
    }

    /**
     * Write the caller's credential choice into the request the catalog route
     * will read.
     *
     * <p>Extracted so the WIRE NAMES are pinned by a test. They are the joint
     * between two services and they are not the names used on this side:
     * {@code credential_source} becomes {@code credentialSource} and
     * {@code credential_id} becomes {@code selectedCredentialId}, because those
     * are what {@code ToolExecutionRequest} declares. Mistype either and
     * nothing fails - the field is simply absent, the catalog applies its
     * default, and the surface that offered the choice goes back to deciding
     * nothing while still showing the control.
     */
    static void applyCredentialChoice(Map<String, Object> requestBody, Map<String, Object> parameters,
                                       ToolExecutionContext context) {
        String credentialSource = normalizedCredentialSource(parameters);
        String chosenName = chosenCredentialName(parameters);
        if (chosenName != null) {
            // A named account IS one of the caller's own accounts, so the pool follows from
            // the name and is not asked for separately. The catalog refuses a selection
            // that does not say so (CatalogV1Controller), and making the caller state
            // something only one value of which is ever valid would be a second way to
            // get the same call wrong.
            requestBody.put("credentialSource", "user");
            // A positive whole number is an ID, not a name, on THIS path as well as on a
            // workflow step's credential_selector. Both listings tell an agent so, in a
            // sentence this module's help now applies to both, and a rule that is only
            // true on one of them is worse than no rule: an account named "2024" would
            // mean one thing in a step and another here. Read the same way in both, so
            // the sentence is true wherever it is read.
            Long numbered = normalizedCredentialId(chosenName);
            if (numbered != null) {
                requestBody.put("selectedCredentialId", numbered);
            } else {
                requestBody.put("selectedCredentialName", chosenName);
            }
            // Strict, or the choice is decoration: an unmatched name or id would fall
            // through to the account default and the call would succeed against an account
            // nobody chose. The pair travels together, exactly as the workflow path sends it.
            requestBody.put("credentialSelectionStrict", true);
        } else if (credentialSource != null) {
            requestBody.put("credentialSource", credentialSource);
        }
        Long pinnedCredentialId = pinnedUserCredentialId(parameters, context);
        if (pinnedCredentialId != null && chosenName == null) {
            requestBody.put("selectedCredentialId", pinnedCredentialId);
        }
        // Not both. Downstream the NAME is consulted before the pinned id, so sending
        // both states a choice and then silently honours the other one - which is the
        // exact defect the pin was added to close, reappearing from the other side. The
        // name is the later and more specific choice, so it is the one that travels, and
        // the pin is dropped rather than carried into a request that will ignore it.
    }

    /**
     * The account the caller named for THIS call, or null when it named none.
     *
     * <p>Trimmed only. The matcher downstream ignores capitalisation and surrounding
     * spaces and NOTHING else, and every text that offers these names says so, so
     * cleaning the value further here would make this module accept names the run then
     * refuses.
     */
    static String chosenCredentialName(Map<String, Object> parameters) {
        Object raw = parameters == null ? null : parameters.get(CREDENTIAL_NAME_KEY);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * Refuses the one credential choice that cannot be honoured, before anything runs.
     *
     * <p>Naming an account and asking for the PLATFORM pool are contradictory: platform
     * keys are the platform's own and carry no name a caller could have read. Sending it
     * on would reach the same refusal one hop later, from a guard whose message is
     * about a field this caller never used, so it is answered here in the caller own
     * vocabulary instead.
     */
    static ToolExecutionResult checkCredentialChoice(Map<String, Object> parameters) {
        if (chosenCredentialName(parameters) == null) {
            return null;
        }
        if (!"platform".equals(normalizedCredentialSource(parameters))) {
            return null;
        }
        return ToolExecutionResult.failure(
                ToolErrorCode.INVALID_PARAMETER_VALUE,
                "This call names one of your own accounts (credential_name) and also asks for the "
                        + "platform's key (credential_source='platform'). Those cannot both hold, so "
                        + "nothing ran and nothing was charged. Drop credential_source to run on the "
                        + "named account, or drop credential_name to run on the platform's key.");
    }

    /**
     * The own-key id to send upstream, or null when none should be.
     *
     * <p>Only the {@code 'user'} branch reads a pinned id: the catalog resolves
     * it exclusively there ({@code HttpExecutionService.selectedUserCredentialId}
     * returns null for any other source), so emitting it beside
     * {@code 'platform'} - or beside no source at all, where the catalog is free
     * to answer from either pool - would state a choice the run cannot honour.
     * The same one-sided rule {@code CatalogToolsGateway} already applies to
     * workflow steps, expressed once here so both callers obey one sentence.
     *
     * <p>Until this existed the id never left this process, so the picker on the
     * generation surfaces chose a key that was then ignored: the account's
     * default ran instead, and nothing on screen said so.
     */
    static Long pinnedUserCredentialId(Map<String, Object> parameters, ToolExecutionContext context) {
        if (!"user".equals(normalizedCredentialSource(parameters))) {
            return null;
        }
        Map<String, Object> credentials = context == null ? null : context.credentials();
        return normalizedCredentialId(credentials == null ? null : credentials.get(CREDENTIAL_ID_CONTEXT_KEY));
    }

    /**
     * The caller's pinned own-key id, or null when it pinned none.
     *
     * <p>Anything that is not a positive whole number is read as "no choice",
     * never as an id of its own. A blank string, a template that resolved to
     * nothing or a malformed value would otherwise travel upstream as a
     * credential nobody owns, and the call would be refused for a reason that
     * has nothing to do with what the caller got wrong. Falling back to the
     * account's default key is both the safe answer and the one the catalog
     * already applies when a pinned credential has been deleted.
     */
    static Long normalizedCredentialId(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number number) {
            long value = number.longValue();
            return value > 0 ? value : null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return null;
        try {
            long value = Long.parseLong(text);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult handleSuccessResponse(String responseBody, String toolId,
                                                       String requestedCredentialSource,
                                                       String chosenCredentialName,
                                                       boolean accountChosenById,
                                                       ToolExecutionContext context) throws Exception {
        Object parsed = objectMapper.readValue(responseBody, Object.class);

        if (parsed instanceof Map) {
            Map<String, Object> resultMap = (Map<String, Object>) parsed;
            Map<String, Object> metadata = (Map<String, Object>) resultMap.get("metadata");

            log.info("🔍 [CATALOG_CALL DEBUG] catalog-service response keys: {}", resultMap.keySet());
            log.info("🔍 [CATALOG_CALL DEBUG] catalog-service metadata: {}", metadata);

            if (metadata == null) {
                metadata = Map.of();
            }

            if (resultMap.containsKey("error") &&
                "credentials_required".equals(resultMap.get("error"))) {
                // The upstream envelope is a MACHINE answer: it carries the
                // credential name and type this refusal needs, alongside ids, an
                // endpoint path and a request id that no reader can act on.
                // Read the useful fields out of it, and never re-emit the
                // envelope itself as the message.
                Object upstream = resultMap.get("result");
                Map<String, Object> detail = upstream instanceof Map
                        ? (Map<String, Object>) upstream : resultMap;
                return credentialsRequired(toolId, detail, metadata, requestedCredentialSource, context);
            }

            // A 200 FROM CATALOG-SERVICE MEANS THE REQUEST WAS HANDLED, NOT
            // THAT THE PROVIDER ACCEPTED IT.
            //
            // The envelope carries the upstream's own verdict - success=false
            // with the provider's status and message - and reading only the
            // transport status published a refusal as a success. Downstream
            // that was not survivable: GenerationModule checks result.success(),
            // saw true, ran the asset resolver on an empty body and reported
            // "the generation ran but no asset could be retrieved. You have
            // been charged for it", blaming the descriptor. Both halves were
            // false. ToolExecutionManager RELEASES the reservation whenever the
            // upstream call fails, so nothing was charged, and the real remedy
            // was sitting unread in this envelope's `error` - for an expired
            // ElevenLabs key, "Please reconnect your account".
            //
            // So the upstream message LEADS. It is the only text that names
            // what the provider objected to, and both readers get it: an agent
            // reads `error` and nothing else, and the generation dialog renders
            // it verbatim to a person.
            int upstreamStatus = upstreamStatusOf(resultMap, metadata);
            boolean envelopeFailed = Boolean.FALSE.equals(resultMap.get("success")) || upstreamStatus >= 400;
            if (envelopeFailed) {
                String detail = upstreamMessageOf(resultMap);
                String status = upstreamStatus > 0 ? " with HTTP " + upstreamStatus : "";
                log.warn("catalog_call: tool {} refused upstream{} - {}", toolId, status, detail);

                // A 401/403 HERE is not "no credential": a key was resolved, sent, and
                // refused. The branch that knows how to explain an account, and the only
                // one that can say a scope is unobtainable without the user's own OAuth
                // client, hangs off `credentials_required` above - which this envelope is
                // not, because the platform DID find a key to send. So an account granted
                // gmail.labels and gmail.send calling an endpoint that needs
                // gmail.readonly was answered with the provider's sentence and nothing
                // else, and the caller, told only that retrying is pointless, reported a
                // standing blocker rather than the one action that lifts it. Ask the same
                // capability the sibling branch asks, and lead with a code that says which
                // of the two situations this is.
                if (upstreamStatus == 401 || upstreamStatus == 403) {
                    String integration = firstNonBlank(
                            text(resultMap, "credential_name"),
                            text(metadata, "iconSlug"));
                    Map<String, Object> capability = credentialCapability(toolId, integration, context);
                    String capabilityRemedy = capability == null ? null : text(capability, "remedy");
                    // The capability answers for THREE situations and only one of them is this
                    // code's: nothing connected, nothing usable by STATUS, and an account short
                    // of scopes. Leading a "no account is connected" sentence with a code whose
                    // documented meaning is "a key WAS sent and connecting another is not the
                    // fix" contradicts itself in one line, and routes the agent to a require
                    // call with scopes the server will find nothing missing from. So the code is
                    // raised only on the evidence it names: an account that HOLDS the
                    // integration and lacks a scope the endpoint needs.
                    if (capabilityRemedy != null
                            && scopeGapExplainsThisCall(capability, requestedCredentialSource,
                                                        chosenCredentialName, accountChosenById)) {
                        Map<String, Object> enriched = new LinkedHashMap<>(metadata);
                        enriched.put("credentialCapability", capability);
                        return ToolExecutionResult.failure(
                                ToolErrorCode.EXECUTION_FAILED,
                                CREDENTIALS_INSUFFICIENT_CODE + ": the provider refused this call" + status
                                        + ", so it produced nothing and nothing was charged."
                                        + (detail.isEmpty() ? "" : " The provider said: " + detail)
                                        + " " + capabilityRemedy,
                                enriched);
                    }
                }

                return ToolExecutionResult.failure(
                        ToolErrorCode.EXECUTION_FAILED,
                        UPSTREAM_REJECTED_CODE + ": the provider refused this call" + status
                                + ", so it produced nothing and nothing was charged."
                                + (detail.isEmpty() ? "" : " The provider said: " + detail)
                                + " Retrying unchanged is refused the same way.",
                        metadata);
            }

            return ToolExecutionResult.success(resultMap, metadata);
        }

        return ToolExecutionResult.success(Map.of("result", parsed));
    }

    /**
     * The status the PROVIDER answered with, read from the envelope rather than
     * from the transport. Zero when the envelope names none.
     */
    @SuppressWarnings("unchecked")
    private static int upstreamStatusOf(Map<String, Object> resultMap, Map<String, Object> metadata) {
        for (Map<String, Object> source : List.of(metadata, resultMap)) {
            Object status = source.get("status");
            if (status instanceof Number n) return n.intValue();
            Object http = source.get("httpStatus");
            if (http instanceof Map<?, ?> m && m.get("code") instanceof Number n) return n.intValue();
        }
        Object nested = resultMap.get("result");
        if (nested instanceof Map<?, ?> m) {
            return upstreamStatusOf((Map<String, Object>) m, Map.of());
        }
        return 0;
    }

    /**
     * The provider's own sentence, from wherever the envelope put it. Empty
     * rather than a placeholder: a made-up explanation is worse than none.
     */
    @SuppressWarnings("unchecked")
    private static String upstreamMessageOf(Map<String, Object> resultMap) {
        Object error = resultMap.get("error");
        if (error != null && !String.valueOf(error).isBlank()) return String.valueOf(error).trim();
        Object nested = resultMap.get("result");
        if (nested instanceof Map<?, ?> m) {
            Object http = ((Map<String, Object>) m).get("httpStatus");
            if (http instanceof Map<?, ?> h && h.get("error") != null
                    && !String.valueOf(h.get("error")).isBlank()) {
                return String.valueOf(h.get("error")).trim();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult handleHttpClientError(HttpClientErrorException e, String toolId) {
        Map<String, Object> errorBody = extractErrorBodyAsMap(e.getResponseBodyAsString());

        if (e.getStatusCode() == HttpStatus.PAYMENT_REQUIRED) {
            // The pre-flight reservation was refused, so the tool never ran and
            // nothing was charged. Retrying changes nothing until the balance
            // does, which is why the message says so instead of inviting a
            // retry loop.
            boolean delinquent = Boolean.TRUE.equals(errorBody.get("delinquent"));
            Object detail = errorBody.get("message");
            String message = detail == null ? "" : String.valueOf(detail);
            log.info("💳 [BILLING] 402 for tool {} - delinquent={}", toolId, delinquent);

            // Not every refusal is a money problem, and the lead sentence is
            // what an agent acts on. Telling it to top up when the real cause is
            // an unstated call size, or a generation the platform does not sell,
            // sends it to a remedy that cannot work. The upstream message
            // already names the cause and the fix; when it does, lead with it.
            String lead;
            // PLAN_EXCLUDES_THIS belongs on this list for a sharper reason than
            // the other two. It is minted precisely to STOP the balance
            // sentence being said: the account has credits, they are the
            // monthly grant, and that grant funds workflow runs and free-tier
            // chat but never a generation. Demoting it to a Detail: made the generic lead say
            // "not enough credits" to a user whose screen shows several hundred,
            // which is how a correct rule reads as a bug, and it sends the agent
            // to a remedy (top up) that is not the one that works (subscribe, or
            // use your own key).
            if (message.startsWith("GENERATION_SIZE_UNKNOWN")
                    || message.startsWith("PLATFORM_NOT_AVAILABLE")
                    || message.startsWith("PLAN_EXCLUDES_THIS")) {
                lead = message + " The tool was NOT executed and nothing was charged.";
            } else if (delinquent) {
                lead = "This account is delinquent, so paid calls are paused until the balance is settled. "
                        + "The tool was NOT executed and nothing was charged. Tell the user to settle the "
                        + "balance; retrying now will be refused again."
                        + (message.isEmpty() ? "" : " Detail: " + message);
            } else {
                lead = "This account does not have enough credits to run this tool. "
                        + "The tool was NOT executed and nothing was charged. Tell the user to top up; "
                        + "retrying now will be refused again."
                        + (message.isEmpty() ? "" : " Detail: " + message);
            }
            return ToolExecutionResult.failure(ToolErrorCode.QUOTA_EXCEEDED, lead, errorBody);
        }

        if (e.getStatusCode() == HttpStatus.NOT_FOUND
                && TOOL_NOT_FOUND_CODE.equals(text(errorBody, "error"))) {
            // Keyed on the body's code and not on the bare 404, like the credential
            // selection branch below: only the catalog's own "no such tool" answer means
            // the id is stale. It used to arrive as a 500, land in the generic catch of
            // the caller, and tell the agent to send the same dead id once more.
            // RESOURCE_NOT_FOUND, not TOOL_NOT_FOUND: the latter means the agent-facing tool
            // or action itself does not exist, whereas here `catalog(action='execute')` exists
            // and it is the catalog ITEM the id pointed at that is gone.
            log.info("Tool {} not found in the catalog - agent told to search again", toolId);
            return ToolExecutionResult.failure(
                ToolErrorCode.RESOURCE_NOT_FOUND,
                TOOL_NOT_FOUND_CODE + ": tool_id '" + toolId + "' does not name any tool in the "
                    + "catalog (it may have been removed or replaced), so nothing ran and nothing was "
                    + "charged. Sending it again fails the same way. Find the tool again with "
                    + "catalog(action='search', query='<what this call should do>') and call "
                    + "catalog(action='execute') with a tool_id from that result.",
                Map.of("toolId", toolId)
            );
        }

        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
            String service = errorBody.get("service") != null ? errorBody.get("service").toString() : null;
            String iconSlug = service;
            String serviceName = service != null
                ? service.substring(0, 1).toUpperCase() + service.substring(1)
                : "Service";

            log.info("🔑 [AUTH_ERROR] HTTP {} for tool {} - service: {}", e.getStatusCode().value(), toolId, service);

            Map<String, Object> authMetadata = new LinkedHashMap<>();
            authMetadata.put("authExpired", true);
            authMetadata.put("errorType", "authentication");
            authMetadata.put("serviceType", iconSlug);
            authMetadata.put("serviceName", serviceName);
            authMetadata.put("iconSlug", iconSlug);
            authMetadata.put("toolId", toolId);

            String jitHint = String.format(
                "The %s connection has expired. " +
                "Call credential(action=\"require\", services=[\"%s\"], reason=\"Your %s session has expired. Please reconnect to continue.\")",
                serviceName,
                iconSlug != null ? iconSlug : "unknown",
                serviceName
            );

            return ToolExecutionResult.failure(
                ToolErrorCode.INVALID_CREDENTIALS,
                jitHint,
                authMetadata
            );
        } else if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY
                && CREDENTIAL_SELECTION_UNRESOLVED.equals(text(errorBody, "error"))) {
            // The credential selection was refused: the account named for this call could
            // not be identified, or two of them answer to that name. Upstream already
            // wrote the sentence that names WHICH account and what to do, so it leads
            // here, whole. The generic branch below would have prefixed it with an HTTP
            // status - which an agent can do nothing with and the help guide forbids -
            // and then cut it at 200 characters, losing the half that says how to fix it.
            String selection = firstNonBlank(text(errorBody, "message"), text(errorBody, "error"));
            log.info("Tool {} refused: credential selection could not be honoured", toolId);
            return ToolExecutionResult.failure(
                ToolErrorCode.INVALID_PARAMETER_VALUE,
                selection != null
                    ? selection + " Nothing ran and nothing was charged. Copy an account name "
                        + "exactly from the accounts list, or omit credential_name to use the "
                        + "account this call would pick on its own."
                    : "The account named for this call could not be used, so nothing ran and "
                        + "nothing was charged. Copy an account name exactly from the accounts "
                        + "list, or omit credential_name.",
                errorBody.isEmpty() ? Map.of() : Map.of("upstreamError", errorBody)
            );
        } else {
            log.error("HTTP error executing tool {}: {} - {}", toolId, e.getStatusCode(), e.getMessage());
            // NOT `e.getMessage()`: RestTemplate builds that from the status line
            // PLUS the raw upstream body, so putting it in `error` publishes a
            // machine envelope to a reader who cannot use it - the same defect
            // the credentials refusal above had. The body is kept as structured
            // metadata, and one short field of it is quoted in the sentence when
            // upstream wrote a human one.
            int status = e.getStatusCode().value();
            String detail = shortDetail(firstNonBlank(text(errorBody, "message"), text(errorBody, "error")));
            String lead = status == 429
                ? UPSTREAM_REJECTED_CODE + ": this call is being rate limited (HTTP 429), so nothing ran "
                    + "and nothing was charged. Wait before sending it again; sending it again now is "
                    + "refused the same way."
                : UPSTREAM_REJECTED_CODE + ": the call was refused with HTTP " + status + ", so nothing "
                    + "ran and nothing was charged. Change the call before sending it again: the same "
                    + "call is refused the same way.";
            return ToolExecutionResult.failure(
                ToolErrorCode.EXECUTION_FAILED,
                detail == null ? lead : lead + " The service said: " + detail,
                errorBody.isEmpty() ? Map.of() : Map.of("upstreamError", errorBody)
            );
        }
    }

    /**
     * Soft warning for "you have no key of your own for this service".
     *
     * <p><b>It asks about ONE pool, so it must not answer for the other.</b> The
     * lookup below reads the caller's own credentials (their row, then a
     * workspace-shared one) and never the platform's. A caller running on the
     * PLATFORM key therefore has nothing this gate can find, and warning them is
     * not a nuisance: this returns a result, so the tool is never dispatched.
     * On the generation surface that is the whole product, since the platform
     * key is what it resells and what the node and the app modal both ask for
     * by default.
     *
     * <p>Two skips, for two different reasons:
     * <ul>
     *   <li>the caller pinned {@code platform}: their own key is irrelevant to a
     *       call that will not use it;</li>
     *   <li>the caller said nothing, and a platform key exists: absent means
     *       "own key first, platform second", so a missing own key is not a
     *       dead end, it is the second branch. Warning here would refuse a call
     *       the platform can serve and would tell an agent to connect a key it
     *       does not need.</li>
     * </ul>
     *
     * <p>What is NOT skipped: a caller who pinned {@code user}. That is the case
     * this gate was written for, and it still answers it.
     *
     * <p>Suppressing the warning never grants anything. Whether the platform
     * will actually sell this call (a published price, an eligible plan, a
     * balance) is decided downstream, and the refusal from there names the real
     * cause instead of a key the caller was not asking to use.
     */
    @SuppressWarnings("unchecked")
    private ToolExecutionResult checkServiceApproval(String toolId, ToolExecutionContext context,
                                                      String requestedCredentialSource,
                                                      boolean namesAnAccount) {
        if (context == null || context.tenantId() == null) {
            return null;
        }
        if ("platform".equals(requestedCredentialSource)) {
            return null;
        }
        if (namesAnAccount) {
            // A named account answers the question this gate asks, and answers it more
            // precisely than the gate can. The gate looks for a credential marked DEFAULT
            // for the integration, so an account holding exactly one credential that
            // nothing ever marked default reads as "not connected" - and the caller would
            // be shown a Connect card for a service it just named an account of. Whether
            // the name resolves is settled by the run-time selection, which refuses by
            // name ("no active credential of this integration is named that") instead of
            // by brand.
            return null;
        }

        try {
            Map<String, Object> toolInfo = fetchToolInfo(toolId, context);

            if (toolInfo == null) {
                log.warn("Could not fetch tool info for credential check: {}", toolId);
                return null;
            }

            // A keyless / public tool (apis.auth_type 'none' or absent) needs no credential, so the
            // agent must be able to run it WITHOUT a connection. CredentialTypeNormalizer already
            // reports type:"none" for these tools, and the execution path requires no credential
            // (ApiService.getRequiredCredentialInfo finds no required link), so the pre-flight must
            // agree and never raise approval_needed - otherwise a genuinely public API (e.g. a
            // no-auth market-data endpoint) is unusable without a dummy connection. Checked before
            // the credential lookup so a public tool costs zero credential round-trips.
            Object rawAuthType = toolInfo.get("authType");
            if ("none".equals(CredentialTypeNormalizer.normalize(
                    rawAuthType == null ? null : rawAuthType.toString()))) {
                log.debug("Tool {} declares no auth (public) - skipping the credential pre-flight gate", toolId);
                return null;
            }

            String iconSlug = (String) toolInfo.get("iconSlug");
            String toolName = (String) toolInfo.get("name");
            String description = (String) toolInfo.get("description");

            if (iconSlug == null || iconSlug.isBlank()) {
                return null;
            }

            // The INTEGRATION name, falling back to the icon slug.
            //
            // Not because icon slugs are shared - the seed importer mirrors the icon slug
            // onto platform_credential_name and a validator refuses a duplicate, so for
            // every seeded API the two strings are equal and this changes nothing. The
            // reason is that the EXECUTOR resolves a credential by
            // apis.platform_credential_name, which is what /info publishes as
            // integrationName, so a gate that asks about a different string can answer a
            // different question. They can genuinely diverge for an API registered
            // through the tool, where the two are derived from different fields, and
            // there the icon slug was asking about a credential nothing resolves.
            String integrationName = firstNonBlank(
                    toolInfo.get("integrationName") == null ? null : toolInfo.get("integrationName").toString(),
                    iconSlug);
            String serviceType = integrationName.toLowerCase(Locale.ROOT);
            Optional<CredentialSummaryDto> defaultCred = credentialClient.getDefaultCredential(context.tenantId(), serviceType);
            boolean hasDefaultCredential = defaultCred.isPresent();

            if (hasDefaultCredential) {
                log.debug("User has default credential '{}' for service {} (tool {})",
                    defaultCred.get().getName(), serviceType, toolId);
                return null;
            }

            // Whether the OTHER pool has anything, asked once and REPORTED, not
            // merely used to decide. A refusal that does not know which pools
            // were empty can only guess at the remedy, and guessing here sends
            // the caller to a pool that is also empty: they retry, are refused
            // again by a different guard, and are pointed back at the first.
            //
            // It asks whether a platform KEY exists, not whether a platform OAuth
            // APPLICATION is registered. Those are different objects and the answer used
            // to come from the wrong one: findPlatformCredentialByName finds the shared
            // OAuth app row, which holds a client id and secret. That app lets a USER
            // connect their own account; it can never itself run a call on anybody
            // behalf. So for every OAuth integration this gate believed in a fallback
            // pool that cannot serve a single call, stood down, and let the request go on
            // to be refused downstream by the generic "no key here or on the platform" -
            // which is exactly the refusal a person cannot act on. The executor resolves
            // the platform key as a credential of the PLATFORM tenant, so that is what
            // gets asked here too.
            boolean platformKeyAvailable = credentialClient
                    .getAccessToken(PLATFORM_TENANT_ID, serviceType)
                    .isPresent();

            // No own key, and the caller did not insist on one. If the platform
            // sells this service, the call has a second pool to fall back on and
            // this is not the dead end the warning describes.
            if (requestedCredentialSource == null && platformKeyAvailable) {
                log.debug("No own key for service {} (tool {}), but a platform key exists and the "
                        + "caller did not pin a pool - letting the call fall back to it", serviceType, toolId);
                return null;
            }

            log.info("User has no default credential for service {} (tool {}) - returning soft warning", serviceType, toolId);

            String serviceName = IntegrationNames.displayName(integrationName);

            // Credential requirement the agent reads so it can tell the user what KIND of
            // connection request_credential will trigger (api_key prompt vs an OAuth consent
            // screen, with the scopes that consent will request). The type lives in the
            // catalog (apis.auth_type, surfaced by /info as authType) - without threading it
            // here the agent only knew THAT a credential was needed, never which kind.
            Map<String, Object> credential = CredentialTypeNormalizer.buildRequirement(toolInfo);
            if (credentialCapability != null) {
                // The same block the tool contract publishes: which accounts exist, which
                // could run this one, and the sentence to relay. A card that says
                // "connect Gmail" when the endpoint needs a scope a standard connection
                // never grants sends the user through a consent screen to arrive here again.
                credential = credentialCapability.describe(credential, integrationName,
                        requiredScopesOf(toolInfo), context.tenantId());
            }

            Map<String, Object> softWarning = new LinkedHashMap<>();
            softWarning.put("status", "approval_needed");
            // The SAME flag the two authorization refusals carry, so "did this run?" has one
            // answer everywhere instead of one per payload shape. Without it an agent taught
            // to branch on `executed` finds nothing here, reads a success, and narrates an
            // API outcome for a call that never left the building.
            softWarning.put("executed", false);
            softWarning.put("serviceType", serviceType);
            softWarning.put("serviceName", serviceName);
            softWarning.put("iconSlug", iconSlug);
            softWarning.put("toolId", toolId);
            softWarning.put("toolName", toolName != null ? toolName : "API Tool");
            softWarning.put("description", description != null ? description : "Access to " + serviceName);
            softWarning.put("credential", credential);
            // WHICH POOLS WERE EMPTY, stated rather than left to be assumed. A
            // reader of this payload cannot otherwise tell "you have no key of
            // your own, buy it on the platform's" from "neither pool has
            // anything, connect a key", and those need opposite advice.
            softWarning.put("platformKeyAvailable", platformKeyAvailable);
            softWarning.put("message", "Credential required for " + serviceName);
            // What to DO, not which call to make - and written for the moment this text is
            // actually READ. In a chat the user is asked to connect, and if they do in time
            // this payload is discarded and the real API response is returned instead; the
            // agent sees these words only once that chance is gone. Elsewhere (workflow
            // run, task, sub-agent) the same payload is produced with nobody to ask at all.
            // So it must claim neither that a result is still coming nor that anyone was
            // prompted - only what is true in both: the call did not run, and connecting is
            // not something the agent can do. That is also why the old
            // "call credential(require)" instruction is gone.
            softWarning.put("next", String.format(
                "The call did NOT run: nothing was sent to %s and no result exists. Connecting %s is the "
                + "user's own act, not a call you can make, and asking for it again only stacks a second "
                + "request. Do not re-send this call. Say plainly that %s is not connected, then continue "
                + "with other work or finish.",
                serviceName, serviceName, serviceName));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("credentialNeeded", true);
            metadata.put("serviceApprovalRequested", true);
            metadata.put("serviceType", serviceType);
            metadata.put("serviceName", serviceName);
            metadata.put("iconSlug", iconSlug);
            metadata.put("toolId", toolId);
            metadata.put("toolName", toolName);
            metadata.put("credential", credential);

            List<Map<String, String>> services = new ArrayList<>();
            Map<String, String> service = new LinkedHashMap<>();
            service.put("serviceType", serviceType);
            service.put("serviceName", serviceName);
            service.put("iconSlug", iconSlug);
            service.put("toolName", toolName != null ? toolName : "API Tool");
            service.put("toolId", toolId);
            service.put("description", description != null ? description : "Access to " + serviceName);
            services.add(service);
            metadata.put("services", services);
            metadata.put("reason", "Credential required to use " + serviceName);

            return ToolExecutionResult.success(softWarning, metadata);

        } catch (Exception e) {
            log.warn("Error checking service approval for tool {}: {}", toolId, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMetadataFromResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
            Map<String, Object> metadata = (Map<String, Object>) parsed.get("metadata");
            return metadata != null ? metadata : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractErrorBodyAsMap(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(responseBody, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Slim DTO carrying the extracted shaping params and the call-params Map
     * with both fields stripped. Cache-strip site: the {@code remainingParams}
     * is what gets fed to the catalog HTTP body and ultimately to
     * {@code ResponseCache.buildKey}. Package-private for unit tests.
     */
    record ShapingParams(List<String> expand, Integer maxItems, Map<String, Object> remainingParams) {}

    /**
     * Return the value of the first of {@code keys} present in {@code src} whose
     * value is a {@code Map} (the tool's nested call-params object). Non-map
     * values (e.g. a stray string) are skipped rather than cast, so a malformed
     * alias never throws. Returns {@code null} when none match.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstMapValue(Map<String, Object> src, String... keys) {
        for (String key : keys) {
            Object value = src.get(key);
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
        }
        return null;
    }

    /**
     * Extract {@code expand} and {@code max_items} from either the top-level
     * call params or the nested {@code input} map (LLMs occasionally place
     * shaping params inside the call body). Returns the surface-cleaned
     * params for the cache-key feed. Package-private for unit tests.
     */
    @SuppressWarnings("unchecked")
    static ShapingParams extractShapingParams(Map<String, Object> parameters, Map<String, Object> input) {
        Map<String, Object> remaining = input != null ? new LinkedHashMap<>(input) : new LinkedHashMap<>();

        // expand: prefer top-level `parameters.expand`; fall back to `input.expand`.
        List<String> expand = null;
        Object topExpand = parameters != null ? parameters.get("expand") : null;
        if (topExpand instanceof List<?> l) {
            expand = (List<String>) l;
        }
        if (expand == null) {
            Object nested = remaining.remove("expand");
            if (nested instanceof List<?> l2) {
                expand = (List<String>) l2;
                log.info("catalog_call: Extracted expand from input (LLM placed it inside)");
            }
        } else {
            // Always strip from input too, even when present at top - agent may
            // have duplicated it accidentally.
            remaining.remove("expand");
        }

        // max_items: same dual extraction, integer-coerced.
        Integer maxItems = null;
        Object topMax = parameters != null ? parameters.get("max_items") : null;
        if (topMax instanceof Number n) {
            maxItems = n.intValue();
        }
        if (maxItems == null) {
            Object nested = remaining.remove("max_items");
            if (nested instanceof Number n2) {
                maxItems = n2.intValue();
                log.info("catalog_call: Extracted max_items from input (LLM placed it inside)");
            }
        } else {
            remaining.remove("max_items");
        }

        return new ShapingParams(expand, maxItems, remaining);
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult checkToolRestriction(String toolId, ToolExecutionContext context) {
        if (context == null || context.credentials() == null) {
            return null;
        }

        Object allowed = context.credentials().get("allowedToolIds");
        if (allowed == null) {
            return null;
        }

        if (allowed instanceof List<?> allowedList) {
            if (allowedList.isEmpty()) {
                log.info("Agent restriction: mode=none, blocking execution of tool {}", toolId);
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "This agent does not have access to external API tools.");
            }

            if (!allowedList.contains(toolId)) {
                log.info("Agent restriction: tool {} not in allowed list of {} tools", toolId, allowedList.size());
                return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "This tool is not in your approved tool list. Use catalog(action='search') to see your available tools.");
            }
        }

        return null;
    }
}

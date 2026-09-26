package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/**
 * Implementation of ToolsGateway using the catalog service.
 * Delegates CRUD operations to CrudToolExecutor for better separation of concerns.
 */
@Service
@ConditionalOnProperty(name = "orchestrator.catalog.enabled", havingValue = "true")
public class CatalogToolsGateway implements ToolsGateway {

    private static final Logger logger = LoggerFactory.getLogger(CatalogToolsGateway.class);
    private static final String CRUD_TOOL_PREFIX = "crud/";

    /**
     * Set by the nodes whose catalog result IS a workflow step's output (StepNode, FindNode) and
     * forwarded as {@code X-Lc-Step-Output}. The catalog then keeps text leaves up to 1 MB instead of
     * clipping them at 4 KB; every other caller (chat, embedded agents, internal tools) is unchanged.
     */
    public static final String STEP_OUTPUT_MARKER = "__stepOutput__";

    /**
     * Per-tool {@code expand} paths sent to the catalog so its
     * {@code ResponseShaper} does NOT collapse large fields the orchestrator
     * needs verbatim (it replaces inline base64 above 4 KB in every mode).
     *
     * <p>Image-gen tools return base64-encoded images in
     * {@code data[].b64_json} (OpenAI) /
     * {@code candidates[].content.parts[].inlineData.data} (Gemini). A
     * 1024×1024 PNG is ~50KB-1MB of base64 - without this opt-out the
     * truncator replaces it with {@code "[BASE64_CONTENT: …]"} and the
     * provider returns a placeholder string instead of an image.
     *
     * <p>Adding a new tool that needs expand: add an entry here keyed on
     * the tool slug.
     */
    private static final Map<String, List<String>> TOOL_EXPAND_PATHS = Map.of(
            "openai/openai-create-image", List.of("data[].b64_json"),
            "google-gemini/google-gemini-generate-content", List.of("candidates[].content.parts[].inlineData.data")
    );

    private final RestTemplate restTemplate;
    private final String catalogBaseUrl;
    private final TypeCastingService typeCastingService;
    private final CrudToolExecutor crudToolExecutor;

    public CatalogToolsGateway(RestTemplate restTemplate,
                              @org.springframework.beans.factory.annotation.Value("${orchestrator.catalog.base-url:http://localhost:8081}") String catalogBaseUrl,
                              TypeCastingService typeCastingService,
                              CrudToolExecutor crudToolExecutor) {
        this.restTemplate = restTemplate;
        this.catalogBaseUrl = catalogBaseUrl;
        this.typeCastingService = typeCastingService;
        this.crudToolExecutor = crudToolExecutor;
    }

    @Override
    public ExecutionResult simulateTool(ToolRef tool, Map<String, Object> input, String tenantId) {
        try {
            String url = catalogBaseUrl + "/api/catalog/tools/" + tool.toolId() + "/info";
            Map<String, Object> toolInfo = restTemplate.getForObject(url, Map.class);

            if (toolInfo != null) {
                return new ExecutionResult(
                    true,
                    Map.of(
                        "tool_id", tool.toolId(),
                        "simulation", true,
                        "tool_info", toolInfo,
                        "input", input,
                        "message", "Simulation based on catalog service metadata"
                    ),
                    List.of(),
                    List.of()
                );
            } else {
                return new ExecutionResult(
                    false,
                    Map.of(),
                    List.of(Map.of("type", "simulation_error", "message", "Tool not found in catalog service")),
                    List.of()
                );
            }

        } catch (Exception e) {
            return new ExecutionResult(
                false,
                Map.of(),
                List.of(Map.of("type", "simulation_error", "message", "Error retrieving tool info: " + e.getMessage())),
                List.of()
            );
        }
    }

    @Override
    public ExecutionResult executeTool(ToolRef tool, Map<String, Object> input, String tenantId) {
        return executeTool(tool, input, tenantId, null);
    }

    /**
     * Billing-aware overload - callers (workflow {@code StepNode} /
     * {@code FindNode}, {@code ImageGenerationModule}, agent dispatch)
     * pass {@code billingIdentifiers} extracted from their execution
     * context so the dispatcher can build deterministic, idempotent
     * {@code sourceId}s. Expected keys:
     * {@code __streamId__, __toolCallId__, __workflowRunId__, __callIndex__}.
     *
     * <p>V148+ unified billing: catalog-service's
     * {@code CatalogToolBillingService.billImmediate} runs the post-success
     * hook (driven by {@code X-Lc-Billing-Scope-*} headers built here from
     * {@code __workflowRunId__} → RUN scope, {@code __streamId__} → STREAM
     * scope). The orchestrator-side workflow path bills the flat
     * {@code WORKFLOW_NODE} credit in {@code StepCompletionOrchestrator};
     * platform-credential markup is debited by the catalog hook against
     * the run pin. Workflow node executors must pass
     * {@code __workflowRunId__} so the catalog uses RUN scope.
     */
    @Override
    public ExecutionResult executeTool(ToolRef tool, Map<String, Object> input, String tenantId,
                                        Map<String, Object> billingIdentifiers) {
        String toolId = tool.toolId();

        // Route CRUD operations to CrudToolExecutor (no billing - internal CRUD).
        if (toolId != null && toolId.startsWith(CRUD_TOOL_PREFIX)) {
            // A CRUD tool reads the tenant's own datasource and authenticates against
            // no provider, so an account chosen for this run cannot be honoured here.
            // Dropping the markers silently would end the step green on a choice that
            // was never applied, which is the shape this whole feature guards against.
            if (Boolean.TRUE.equals(billingIdentifiers != null
                    ? billingIdentifiers.get("__credentialSelectionStrict__") : null)) {
                String message = "This step chooses an account at run time, but it is a table "
                        + "operation: it reads this workspace's own data and authenticates against "
                        + "no provider, so there is no account for it to choose. Remove the account "
                        + "expression from it.";
                logger.error("Refusing a run-time account selection on a table operation: toolId={}", toolId);
                return new ExecutionResult(false, Map.of(),
                        List.of(Map.of("type", "credential_selection_error", "message", message)),
                        List.of());
            }
            return crudToolExecutor.execute(toolId, input, tenantId);
        }

        try {
            logger.info("CatalogToolsGateway.executeTool - delegating to catalog service with tenantId={}", tenantId);
            String executionUrl = catalogBaseUrl + "/catalog/v1/tools/" + toolId + "/execute";

            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("parameters", input != null ? input : Map.of());
            payload.put("metadata", Map.of("tenantId", tenantId, "source", "orchestrator"));
            payload.put("context", "orchestrator");
            // Opt-out of catalog truncation for fields the orchestrator must
            // receive verbatim (e.g. image-gen base64 payloads). Without this,
            // ResponseShaper replaces inline base64 > 4 KB with a placeholder
            // and downstream providers parse garbage.
            List<String> expandPaths = TOOL_EXPAND_PATHS.get(toolId);
            if (expandPaths != null && !expandPaths.isEmpty()) {
                payload.put("expand", expandPaths);
            }
            // Credential-source forwarding.
            //
            // Workflow direct calls (StepNode/FindNode) set __credentialSource__
            // = "user" | "platform" from the node's UI toggle. The gateway
            // forwards it as `credentialSource` on the catalog request; when
            // "platform", `platformCredentialId` is also forwarded so the
            // catalog can stamp it on the response for billing pin lookup.
            // For these direct calls the catalog uses the source strictly -
            // no fallback to the other pool - because the workflow author
            // made an explicit choice at design time.
            //
            // Agent-driven calls (chat agents via CatalogExecuteModule,
            // ImageGenerationModule, agents running INSIDE a workflow which
            // carry __workflowRunId__ but no __credentialSource__) leave the
            // field unset. The catalog then applies the implicit fallback:
            // try user credential first, fall back to platform if pricing is
            // published for the endpoint. Billing differentiation is preserved
            // downstream via the credentialSource metadata returned in the
            // response (USER → no debit, PLATFORM → debit via
            // CatalogToolBillingService).
            //
            // Note: __workflowRunId__ continues to be the SOLE marker the
            // billing dispatcher reads to skip catalog-tier billing for
            // workflow-paid runs. Credential source resolution is now an
            // independent signal.
            if (billingIdentifiers != null) {
                Object credSource = billingIdentifiers.get("__credentialSource__");
                if (credSource instanceof String s && !s.isBlank()) {
                    payload.put("credentialSource", s);
                    // Defense-in-depth: only forward platformCredentialId on the
                    // platform branch. A future caller wrongly emitting both
                    // (source=user + a stray platformCredentialId) would otherwise
                    // confuse the billing scope at the catalog. StepNode/FindNode
                    // already gate emission on the same branch, so this is belt-
                    // and-braces.
                    if ("platform".equals(s)) {
                        Object platformCredId = billingIdentifiers.get("__platformCredentialId__");
                        if (platformCredId != null) {
                            payload.put("platformCredentialId", platformCredId);
                        }
                    } else if ("user".equals(s)) {
                        Object selectedCredId = billingIdentifiers.get("__selectedCredentialId__");
                        if (selectedCredId != null) {
                            payload.put("selectedCredentialId", selectedCredId);
                        }
                        // A step whose credential is chosen at RUN time can name it
                        // instead of numbering it, because that is what an author
                        // puts in a table row or a trigger field. The catalog is the
                        // one that knows which integration the endpoint requires, so
                        // it, not this gateway, turns the name into a credential.
                        Object selectedCredName = billingIdentifiers.get("__selectedCredentialName__");
                        if (selectedCredName != null) {
                            payload.put("selectedCredentialName", selectedCredName);
                        }
                    }
                    // Strict = this choice was made FOR THIS RUN, so an id or name
                    // the catalog cannot match must refuse the call rather than soften
                    // into the integration default. Absent for every author-time pin,
                    // which keeps their forgiving fallback exactly as it is.
                    if (Boolean.TRUE.equals(billingIdentifiers.get("__credentialSelectionStrict__"))) {
                        payload.put("credentialSelectionStrict", true);
                    }
                }
            }

            // Per-call opt-out from inline-binary dehydration. Workflow nodes
            // that genuinely need the raw bytes downstream (re-encoding to
            // an email attachment, uploading to a different API, etc.) set
            // {@code __inlineBinaries__=Boolean.TRUE} in their billingIdentifiers
            // map; the gateway forwards it as the canonical
            // {@code inlineBinaries} request field that catalog-service reads.
            //
            // <b>Status: forward-only infrastructure.</b> No StepNode/FindNode
            // currently sets the marker - there is no workflow-author UI to
            // toggle it, and no real consumer needs raw bytes today. The
            // forwarding is in place so a future producer (e.g. a Step config
            // checkbox, a SpEL hint that downstream nodes need bytes) can
            // activate it without touching the gateway. Treat this as a
            // ready-but-inert escape hatch, not a working feature.
            if (billingIdentifiers != null
                    && Boolean.TRUE.equals(billingIdentifiers.get("__inlineBinaries__"))) {
                payload.put("inlineBinaries", Boolean.TRUE);
            }

            // How long this call may spend waiting out a provider's rate-limit refusal, in
            // seconds, decided by the node. Absent = the platform's own budget applies, which is
            // what every caller without retry logic of its own wants. 0 = the node paces itself,
            // so the platform must not add requests underneath it.
            if (billingIdentifiers != null) {
                Object retryBudget = billingIdentifiers.get("__providerRetryMaxWaitSec__");
                if (retryBudget instanceof Number n) {
                    payload.put("providerRetryMaxWaitSeconds", n.intValue());
                }
            }

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if (tenantId != null && !tenantId.isBlank()) {
                headers.set("X-User-ID", tenantId);
                logger.info("CatalogToolsGateway.executeTool - Added X-User-Id header: {}", tenantId);
            }
            // Forward X-Organization-ID so downstream storage indexing
            // can tag files with the correct org (fixes image-gen files
            // invisible in StorageExplorer).
            OrgContextHeaderForwarder.forward(headers);

            // V148+ billing scope propagation. RUN-priority precedence:
            // workflow {@code __workflowRunId__} wins over chat {@code __streamId__}
            // (embedded-agent inside workflow → workflow atomicity). When neither
            // is set (test fixtures, internal tools) we send no scope headers and
            // catalog skips billing fail-closed.
            //
            // BOTH chat (image-gen, browser-agent) and workflow paths flow through
            // here - without the STREAM branch, chat image-generation would skip
            // billing entirely (revenue leak). The headers are read by
            // {@code CatalogV1Controller.applyBillingHeaders} and fed to
            // {@code ToolExecutionManager.invokeCatalogBilling} for the
            // post-success {@code CatalogToolBillingService.billImmediate} hook.
            if (billingIdentifiers != null) {
                Object runId = billingIdentifiers.get("__workflowRunId__");
                Object streamId = billingIdentifiers.get("__streamId__");
                Object stepId = billingIdentifiers.get("__nodeId__");
                if (runId != null) {
                    headers.set("X-Lc-Billing-Scope-Kind", "RUN");
                    headers.set("X-Lc-Billing-Scope-Id", String.valueOf(runId));
                    if (stepId != null) {
                        headers.set("X-Lc-Billing-Step-Id", String.valueOf(stepId));
                    }
                } else if (streamId != null) {
                    headers.set("X-Lc-Billing-Scope-Kind", "STREAM");
                    headers.set("X-Lc-Billing-Scope-Id", String.valueOf(streamId));
                }
                // Product-analytics attribution only (api_call_completed on the
                // catalog side). Separate from the billing step header on purpose:
                // X-Lc-Billing-Step-Id shapes the ledger source-id, these two
                // change nothing about what is charged.
                Object workflowId = billingIdentifiers.get("__workflowId__");
                Object analyticsNodeId = billingIdentifiers.get("__analyticsNodeId__");
                if (workflowId != null) {
                    headers.set("X-Lc-Workflow-Id", String.valueOf(workflowId));
                }
                if (analyticsNodeId != null) {
                    headers.set("X-Lc-Node-Id", String.valueOf(analyticsNodeId));
                }
                if (Boolean.TRUE.equals(billingIdentifiers.get(STEP_OUTPUT_MARKER))) {
                    headers.set("X-Lc-Step-Output", "true");
                }
            }

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);
            ResponseEntity<CatalogExecutionResponse> responseEntity = restTemplate.exchange(
                    executionUrl,
                    HttpMethod.POST,
                    requestEntity,
                    CatalogExecutionResponse.class
            );
            CatalogExecutionResponse response = responseEntity.getBody();

            if (response == null) {
                logger.error("Catalog service returned null response");
                return new ExecutionResult(
                        false,
                        Map.of(),
                        List.of(Map.of("type", "execution_error", "message", "Catalog service returned no response")),
                        List.of()
                );
            }

            boolean success = response.isSuccess();
            Integer httpStatus = extractHttpStatusFromResponse(response);

            // Canonicalization + flattening + envelope shared with the mock path
            // (CatalogMockClient) - see CatalogResultFlattener for the invariants.
            Map<String, Object> output = CatalogResultFlattener.flatten(
                    tool.toolId(), response.getResult(), response.getMetadata(), httpStatus,
                    "Catalog service executed the tool");

            List<Map<String, String>> errors = success
                    ? List.of()
                    : List.of(Map.of(
                            "type", "catalog_error",
                            "message", response.getError() != null ? response.getError() : "Unknown catalog execution error"
                    ));

            // V148+ unified billing: catalog-service runs the post-success hook
            // (ToolExecutionManager → CatalogToolBillingService). The orchestrator-
            // side dispatcher path is no longer used; scope context flows through
            // the X-Lc-Billing-Scope-* headers set above.

            return new ExecutionResult(success, output, errors, List.of());
        } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
            // The catalog refuses an integration the account's plan does not include, with
            // a sentence naming the plan that does. Same reasoning as the 422 below: left
            // to the generic handler the step would read "403 FORBIDDEN: {json blob}" and
            // the one actionable sentence would be buried in it. Only a body carrying the
            // refusal CODE is labelled as one - a provider's own 403 (bad token, missing
            // scope) must keep reading as an auth failure, not as a billing prompt.
            String body = e.getResponseBodyAsString();
            boolean isPlanRefusal = body != null && body.contains("PLAN_UPGRADE_REQUIRED");
            String message = isPlanRefusal ? extractJsonMessage(body, e.getMessage()) : e.getMessage();
            if (isPlanRefusal) {
                // Already identified as the customer's plan two lines up, and logged at INFO by
                // catalog-service itself: re-logging it here at ERROR promoted a refusal into an
                // incident.
                logger.warn("Catalog refused tool {} on the account's plan: {}", toolId, message);
            } else {
                logger.error("Catalog returned 403 for tool {}: {}", toolId, message);
            }
            return new ExecutionResult(false, Map.of(),
                    List.of(Map.of(
                        "type", isPlanRefusal ? "plan_upgrade_required" : "execution_error",
                        "message", message)),
                    List.of());
        } catch (org.springframework.web.client.HttpClientErrorException.UnprocessableEntity e) {
            // The catalog refuses a run-time credential choice with a sentence written
            // for the person who has to fix it. Left to the generic handler below, the
            // step's error reads "422 UNPROCESSABLE_ENTITY: {json blob}" and the
            // sentence is buried inside it, which defeats the point of writing one.
            String body = e.getResponseBodyAsString();
            // Only a body carrying the refusal CODE is labelled as one. The catalog
            // returns 422 from a single place today; the first future one would
            // otherwise reach the step as a credential error and send the reader
            // looking at an account name that is not the problem. Handled in this
            // block rather than rethrown, because a rethrow from a catch does not
            // re-enter its sibling.
            boolean isCredentialRefusal = body != null && body.contains("CREDENTIAL_SELECTION_UNRESOLVED");
            String message = isCredentialRefusal ? credentialSelectionMessage(body) : e.getMessage();
            if (isCredentialRefusal) {
                // The caller has to pick a credential: their action, not ours.
                logger.warn("Catalog refused tool {} pending a credential choice: {}", toolId, message);
            } else {
                logger.error("Catalog returned 422 for tool {}: {}", toolId, message);
            }
            return new ExecutionResult(false, Map.of(),
                    List.of(Map.of(
                        "type", isCredentialRefusal ? "credential_selection_error" : "execution_error",
                        "message", message)),
                    List.of());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // The catalog answers 404 TOOL_NOT_FOUND when the step's tool id names no tool
            // any more (removed, or re-imported under a new id). It used to answer 500, and
            // this step read "500 INTERNAL_SERVER_ERROR: {json}" beside an ERROR stack trace
            // for what is a stale reference in the workflow. Only a body carrying the code is
            // labelled as one; any other 404 keeps the generic reading.
            String body = e.getResponseBodyAsString();
            boolean isToolNotFound = body != null && body.contains("TOOL_NOT_FOUND");
            if (isToolNotFound) {
                // Read by people in the run view AND by agents through the run's errors, so the
                // remedy names only what an agent can do itself: find the tool, update the step.
                String message = "The tool '" + toolId + "' no longer exists in the catalog "
                        + "(it may have been removed or replaced), so this step did not run. Replace "
                        + "the tool on this step: search the catalog for the tool that does this job, "
                        + "then update the step's tool id to the one found.";
                logger.warn("Catalog has no tool {} (stale reference on the step)", toolId);
                return new ExecutionResult(false, Map.of(),
                        List.of(Map.of("type", "tool_not_found", "message", message)),
                        List.of());
            }
            logger.error("Catalog returned 404 for tool {}: {}", toolId, e.getMessage());
            return new ExecutionResult(false, Map.of(),
                    List.of(Map.of("type", "execution_error", "message", e.getMessage())),
                    List.of());
        } catch (Exception e) {
            // A 402 INSUFFICIENT_CREDITS from the catalogue has no catch of its own, so it lands
            // here and used to be logged at ERROR *with a stack trace* - the credit class, in its
            // loudest possible shape, in the service this rule is about. Only the LEVEL changes:
            // the ExecutionResult below is returned unchanged, so the step still reads the same.
            // Spring has no PaymentRequired subclass, hence the status check.
            boolean outOfCredits = e instanceof org.springframework.web.client.HttpClientErrorException http
                    && http.getStatusCode().value() == 402;
            if (outOfCredits) {
                logger.warn("Catalog refused tool {} for lack of credits: {}", tool.toolId(), e.getMessage());
            } else {
                logger.error("Error executing tool {} via catalog service: {}", tool.toolId(), e.getMessage(), e);
            }
            return new ExecutionResult(
                    false,
                    Map.of(),
                    List.of(Map.of("type", "execution_error", "message", e.getMessage())),
                    List.of()
            );
        }
    }

    private static class CatalogExecutionResponse {
        private boolean success;
        private Object result;
        private String error;
        private Map<String, Object> metadata;

        public boolean isSuccess() { return success; }
        public Object getResult() { return result; }
        public String getError() { return error; }
        public Map<String, Object> getMetadata() { return metadata; }
    }

    /**
     * Extract HTTP status from response metadata or result.
     */
    private Integer extractHttpStatusFromResponse(CatalogExecutionResponse response) {
        // Check metadata first
        if (response.getMetadata() != null) {
            Object statusObj = response.getMetadata().get("status");
            Integer status = parseStatusValue(statusObj);
            if (status != null) return status;
        }

        // Check result if it's a Map
        Object result = response.getResult();
        if (result instanceof Map<?, ?> resultMap) {
            Object statusObj = resultMap.get("status");
            return parseStatusValue(statusObj);
        }

        return null;
    }

    private Integer parseStatusValue(Object statusObj) {
        if (statusObj == null) return null;
        if (statusObj instanceof Number number) {
            return number.intValue();
        }
        if (statusObj instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, String> getParameterTypes(Map<String, Object> toolInfo) {
        Map<String, String> parameterTypes = new java.util.HashMap<>();

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> apiToolParameters = (List<Map<String, Object>>) toolInfo.get("api_tool_parameters");

            if (apiToolParameters != null) {
                for (Map<String, Object> param : apiToolParameters) {
                    String paramName = (String) param.get("name");
                    String paramType = (String) param.get("type");

                    if (paramName != null && paramType != null) {
                        parameterTypes.put(paramName, paramType);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Unable to retrieve parameter types: {}", e.getMessage());
        }

        return parameterTypes;
    }

    /**
     * Cast inputs according to expected types.
     */
    private Map<String, Object> castInputs(Map<String, Object> input, Map<String, String> parameterTypes) {
        Map<String, Object> castedInput = new java.util.HashMap<>();

        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String paramName = entry.getKey();
            Object value = entry.getValue();

            try {
                String expectedType = parameterTypes.get(paramName);
                if (expectedType != null) {
                    Object castedValue = typeCastingService.castValue(value, expectedType, paramName);
                    castedInput.put(paramName, castedValue);
                    logger.debug("Parameter '{}' cast from '{}' to '{}'", paramName, value, castedValue);
                } else {
                    castedInput.put(paramName, value);
                    logger.debug("No type specified for parameter '{}', keeping value: {}", paramName, value);
                }
            } catch (TypeCastingService.TypeConversionException e) {
                logger.error("Type conversion error for parameter '{}': {}", paramName, e.getMessage());
                castedInput.put(paramName, value);
            }
        }

        return castedInput;
    }

    /**
     * The human sentence out of a credential-selection refusal, or the raw body when
     * it is not one. Deliberately tolerant: a refusal whose message cannot be parsed
     * must still surface something, because the alternative is a step that failed for
     * a reason nobody can read.
     */
    /**
     * The {@code message} field of a catalog JSON error body, or {@code fallback}
     * when the body is absent, unparseable, or carries no message. Used for the
     * refusals whose whole value is the sentence they carry.
     */
    private static String extractJsonMessage(String responseBody, String fallback) {
        if (responseBody == null || responseBody.isBlank()) {
            return fallback;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
            com.fasterxml.jackson.databind.JsonNode message = node.get("message");
            if (message != null && message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // Fall through to the caller's fallback.
        }
        return fallback;
    }

    private String credentialSelectionMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "The catalog refused the credential this step selected for this run.";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
            com.fasterxml.jackson.databind.JsonNode message = node.get("message");
            if (message != null && message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // Fall through to the raw body below.
        }
        return responseBody;
    }
}

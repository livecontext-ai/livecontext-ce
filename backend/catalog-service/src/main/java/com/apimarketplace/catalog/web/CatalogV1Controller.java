package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.dto.IntentResolutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolListResponse;
import com.apimarketplace.catalog.service.CatalogV1Service;
import com.apimarketplace.catalog.service.exception.ApiAuthenticationException;
import com.apimarketplace.catalog.service.exception.ToolNotFoundException;
import com.apimarketplace.catalog.service.execution.MockToolExecutionService;
import lombok.RequiredArgsConstructor;
import com.apimarketplace.common.web.BillingContextHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/catalog/v1")
@RequiredArgsConstructor
@Slf4j
public class CatalogV1Controller {

    private final CatalogV1Service catalogV1Service;
    private final MockToolExecutionService mockToolExecutionService;

    @GetMapping("/tools")
    public ResponseEntity<?> getTools(@RequestParam(defaultValue = "20") int limit,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false) String search,
                                      @RequestHeader(value = "X-User-ID", required = false) String userId,
                                      @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            ToolListResponse response = catalogV1Service.getTools(limit, category, search, userId, orgId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching catalog tools", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "message", "Unable to fetch catalog tools",
                            "error", e.getMessage()
                    ));
        }
    }

    @PostMapping("/tools/{toolId}/execute")
    public ResponseEntity<?> executeTool(@PathVariable String toolId,
                                         @RequestBody(required = false) ToolExecutionRequest request,
                                         @RequestHeader(value = "X-User-ID", required = false) String userId,
                                         @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
                                         @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                         @RequestHeader(value = "X-Lc-Billing-Scope-Kind", required = false) String billingScopeKind,
                                         @RequestHeader(value = "X-Lc-Billing-Scope-Id", required = false) String billingScopeId,
                                         @RequestHeader(value = "X-Lc-Billing-Step-Id", required = false) String billingStepId,
                                         @RequestHeader(value = "X-Lc-Generation-Model", required = false) String generationModelId,
                                         @RequestHeader(value = "X-Lc-Generation-Quantity", required = false) java.math.BigDecimal generationQuantity,
                                         @RequestHeader(value = "X-Lc-Generation-Unit", required = false) String generationQuantityUnit,
                                         @RequestHeader(value = "X-Lc-Generation-Multiplier", required = false) java.math.BigDecimal generationPriceMultiplier,
                                         @RequestHeader(value = "X-Lc-Workflow-Id", required = false) String analyticsWorkflowId,
                                         @RequestHeader(value = "X-Lc-Node-Id", required = false) String analyticsNodeId) {
        applyBillingHeaders(request, billingScopeKind, billingScopeId, billingStepId,
                generationModelId, generationQuantity, generationQuantityUnit,
                generationPriceMultiplier);
        applyAnalyticsHeaders(request, analyticsWorkflowId, analyticsNodeId);
        return executeToolInternal(toolId, request, userId, orgId, requestId);
    }

    /**
     * Execute tool with apiSlug/toolSlug format.
     * Supports URL pattern: /catalog/v1/tools/{apiSlug}/{toolSlug}/execute
     * Used by orchestrator which sends toolId as "apiSlug/toolSlug".
     */
    @PostMapping("/tools/{apiSlug}/{toolSlug}/execute")
    public ResponseEntity<?> executeToolWithApiSlug(@PathVariable String apiSlug,
                                                    @PathVariable String toolSlug,
                                                    @RequestBody(required = false) ToolExecutionRequest request,
                                                    @RequestHeader(value = "X-User-ID", required = false) String userId,
                                                    @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                    @RequestHeader(value = "X-Lc-Billing-Scope-Kind", required = false) String billingScopeKind,
                                                    @RequestHeader(value = "X-Lc-Billing-Scope-Id", required = false) String billingScopeId,
                                                    @RequestHeader(value = "X-Lc-Billing-Step-Id", required = false) String billingStepId,
                                                    @RequestHeader(value = "X-Lc-Generation-Model", required = false) String generationModelId,
                                                    @RequestHeader(value = "X-Lc-Generation-Quantity", required = false) java.math.BigDecimal generationQuantity,
                                         @RequestHeader(value = "X-Lc-Generation-Unit", required = false) String generationQuantityUnit,
                                         @RequestHeader(value = "X-Lc-Generation-Multiplier", required = false) java.math.BigDecimal generationPriceMultiplier,
                                         @RequestHeader(value = "X-Lc-Workflow-Id", required = false) String analyticsWorkflowId,
                                         @RequestHeader(value = "X-Lc-Node-Id", required = false) String analyticsNodeId) {
        applyBillingHeaders(request, billingScopeKind, billingScopeId, billingStepId,
                generationModelId, generationQuantity, generationQuantityUnit,
                generationPriceMultiplier);
        applyAnalyticsHeaders(request, analyticsWorkflowId, analyticsNodeId);
        // Combine apiSlug/toolSlug - service handles this format
        String toolId = apiSlug + "/" + toolSlug;
        return executeToolInternal(toolId, request, userId, orgId, requestId);
    }

    /**
     * Mock execution: serves the tool's default example response projected through
     * the same output-schema pipeline as a real execution. Used by the orchestrator's
     * per-node mock mode. No HTTP call, no credentials, no billing.
     */
    @PostMapping("/tools/{toolId}/execute-mock")
    public ResponseEntity<?> executeMockTool(@PathVariable String toolId,
                                             @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return executeMockToolInternal(toolId, requestId);
    }

    /** Mock execution with the {@code apiSlug/toolSlug} id form (orchestrator's step id format). */
    @PostMapping("/tools/{apiSlug}/{toolSlug}/execute-mock")
    public ResponseEntity<?> executeMockToolWithApiSlug(@PathVariable String apiSlug,
                                                        @PathVariable String toolSlug,
                                                        @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return executeMockToolInternal(apiSlug + "/" + toolSlug, requestId);
    }

    private ResponseEntity<?> executeMockToolInternal(String toolId, String requestId) {
        try {
            String resolvedRequestId = requestId != null && !requestId.isBlank() ? requestId : UUID.randomUUID().toString();
            return ResponseEntity.ok(mockToolExecutionService.executeMockTool(toolId, resolvedRequestId));
        } catch (ToolNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "success", false,
                            "message", "Tool not found",
                            "toolId", toolId));
        } catch (MockToolExecutionService.MockExampleNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "success", false,
                            "message", e.getMessage(),
                            "toolId", toolId));
        } catch (Exception e) {
            log.error("Error executing mock for tool {}", toolId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "message", "Unable to execute mock",
                            "toolId", toolId,
                            "error", e.getMessage()));
        }
    }

    /** A workflow id is a UUID; anything else is dropped rather than stored as a property. */
    private static final java.util.regex.Pattern WORKFLOW_ID_SHAPE =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    /** A node id is `family:label[/tool]`; bounded so a stray header cannot become an unbounded property. */
    private static final java.util.regex.Pattern NODE_ID_SHAPE =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_:./-]{1,200}$");

    /**
     * Product-analytics attribution (workflow id + node id). Header-only like the
     * billing scope: the DTO fields are {@code @JsonIgnore}, so a body value can
     * never claim another workflow. Shape-checked because the gateway does not
     * strip these headers: a malformed value is dropped, never forwarded. Read by
     * {@code ApiCallAnalytics}; billing ignores them entirely.
     */
    static void applyAnalyticsHeaders(ToolExecutionRequest request,
                                      String workflowId,
                                      String nodeId) {
        if (request == null) return;
        if (workflowId != null && WORKFLOW_ID_SHAPE.matcher(workflowId.trim()).matches()) {
            request.setAnalyticsWorkflowId(workflowId.trim());
        }
        if (nodeId != null && NODE_ID_SHAPE.matcher(nodeId.trim()).matches()) {
            request.setAnalyticsNodeId(nodeId.trim());
        }
    }

    /**
     * V148+ billing scope header binding. Headers can also be set via the
     * request body (e.g. when an internal caller already builds the DTO);
     * the body wins because internal callers may have richer per-step context
     * (epoch/spawn/iteration) than what the headers carry.
     */
    private static void applyBillingHeaders(ToolExecutionRequest request,
                                              String scopeKind,
                                              String scopeId,
                                              String stepId,
                                              String generationModelId,
                                              java.math.BigDecimal generationQuantity,
                                              String generationQuantityUnit,
                                              java.math.BigDecimal generationPriceMultiplier) {
        if (request == null) return;
        if (request.getBillingScopeKind() == null && scopeKind != null && !scopeKind.isBlank()) {
            request.setBillingScopeKind(scopeKind);
        }
        if (request.getBillingScopeId() == null && scopeId != null && !scopeId.isBlank()) {
            request.setBillingScopeId(scopeId);
        }
        if (request.getBillingStepId() == null && stepId != null && !stepId.isBlank()) {
            request.setBillingStepId(stepId);
        }
        // Every field below is @JsonIgnore on the DTO, so the null check is
        // never anything but true on an inbound request: it exists for the
        // in-process caller that already set the value on a request object it
        // built itself.
        //
        // It read "a body value wins over the header so a caller that already
        // knows its model is not overridden by a proxy", which described a
        // behaviour that cannot happen and invited someone to make it possible
        // by lifting the seal. These values decide what is charged, and the
        // gateway strips their headers for exactly that reason.
        if (request.getGenerationModelId() == null && generationModelId != null
                && !generationModelId.isBlank()) {
            request.setGenerationModelId(generationModelId);
        }
        if (request.getGenerationQuantity() == null && generationQuantity != null
                && generationQuantity.signum() >= 0) {
            request.setGenerationQuantity(generationQuantity);
        }
        // The unit the quantity above is counted in. It travels with the number
        // because the number alone cannot be checked against the published
        // rate: a row priced per image and a call measured in seconds both
        // arrive as a bare 10.
        if (request.getGenerationQuantityUnit() == null && generationQuantityUnit != null
                && !generationQuantityUnit.isBlank()) {
            request.setGenerationQuantityUnit(generationQuantityUnit);
        }
        // What the call's own choices do to its price. Sanitised through the SAME rule the two
        // quote endpoints use, which this door did not apply: it dropped a non-positive value and
        // passed anything else straight through to the biller.
        //
        // That asymmetry was the wrong way round. The two doors that enforced a ceiling can only
        // MISQUOTE; this one is the one that takes money. The justification for leaving it open was
        // that the value can only come from a header this edge strips, but stripping happens at the
        // gateway and the CE monolith, not here: anything reaching catalog-service without
        // traversing an edge (another in-cluster service, a misrouted ingress) was believed. A
        // factor of a million on a row with no maxCredits is a million times the rate, reserved and
        // committed, with billed_quantity still correct so nothing on the invoice looks wrong.
        java.math.BigDecimal sanitizedMultiplier =
                BillingContextHeaders.sanitizeGenerationMultiplier(generationPriceMultiplier);
        if (request.getGenerationPriceMultiplier() == null && sanitizedMultiplier != null) {
            request.setGenerationPriceMultiplier(sanitizedMultiplier);
        }
    }

    private ResponseEntity<?> executeToolInternal(String toolId,
                                                  ToolExecutionRequest request,
                                                  String userId,
                                                  String orgId,
                                                  String requestId) {
        try {
            // Accept both UUIDs and slugs (e.g., "api-slug/tool-slug" or "tool-slug")
            ToolExecutionRequest safeRequest = request != null ? request : ToolExecutionRequest.builder().build();
            String resolvedRequestId = requestId != null && !requestId.isBlank() ? requestId : UUID.randomUUID().toString();

            // Two-track credential-resolution context, both cleared in finally:
            //   • setExplicitSource - workflow node UI toggle ("user"/"platform"),
            //     strictly honored, no fallback. Wins over agentic override.
            //   • setOverride - agentic per-call hint ("both"), enables
            //     user-then-platform fallback for chat agents / image-gen.
            // Both are advisory and clear automatically before the next
            // thread-pool task picks up this thread.
            com.apimarketplace.catalog.service.http.CredentialModeContext.setExplicitSource(safeRequest.getCredentialSource());
            com.apimarketplace.catalog.service.http.CredentialModeContext.setSelectedCredentialId(safeRequest.getSelectedCredentialId());
            //   - setSelectedCredentialName / setSelectionStrict - a step whose
            //     credential is decided at RUN time. The name is resolved against
            //     the endpoint's own requirement, and "strict" makes an unmatched
            //     choice refuse the call instead of quietly using the default key.
            com.apimarketplace.catalog.service.http.CredentialModeContext.setSelectedCredentialName(safeRequest.getSelectedCredentialName());
            com.apimarketplace.catalog.service.http.CredentialModeContext.setSelectionStrict(safeRequest.getCredentialSelectionStrict());
            // The caller's own retry budget for this call, and the counter the execution path
            // writes back into. Cleared in the same finally as the credential context.
            com.apimarketplace.catalog.service.http.ProviderRetryContext.begin(
                    safeRequest.getProviderRetryMaxWaitSeconds());
            // Refused HERE and not only where the choice is read, because the branches
            // that read it are not the only ones a caller can reach. The agentic
            // branch (no explicit source) and the platform branch never consult the
            // selection at all, so a request carrying "choose this account, strictly"
            // on either of them would run on a different account and report success.
            // Checking at the door makes the guarantee a property of the REQUEST.
            boolean namesACredential = safeRequest.getSelectedCredentialName() != null
                    && !safeRequest.getSelectedCredentialName().isBlank();
            boolean statesASelection =
                    Boolean.TRUE.equals(safeRequest.getCredentialSelectionStrict()) || namesACredential;
            // A name without the strict flag would resolve, fail to match, and fall
            // through to the account's default with nothing said. The two fields only
            // mean something together, so the pair is required rather than assumed:
            // the orchestrator always sends both, and a caller that sends one is told
            // so instead of being served a different account.
            if (namesACredential && !Boolean.TRUE.equals(safeRequest.getCredentialSelectionStrict())) {
                throw new com.apimarketplace.catalog.service.exception.CredentialSelectionException(
                        "This request names a credential to use for this run but does not mark the "
                                + "selection as strict. The call was NOT made: without it an unmatched "
                                + "name falls back to this account's default credential, which is a "
                                + "different account from the one named. Send "
                                + "credentialSelectionStrict=true alongside the name.");
            }
            if (statesASelection
                    && !"user".equalsIgnoreCase(String.valueOf(safeRequest.getCredentialSource()))) {
                throw new com.apimarketplace.catalog.service.exception.CredentialSelectionException(
                        "This request selects a credential for this run but does not state that it "
                                + "runs on the caller's own credentials. The call was NOT made: the "
                                + "branches that would have served it never read the selection, so it "
                                + "would have resolved an account the caller did not choose. Send "
                                + "credentialSource='user' alongside the selection.");
            }
            com.apimarketplace.catalog.service.http.CredentialModeContext.setOverride(safeRequest.getCredentialModeOverride());

            ToolExecutionResponse response = catalogV1Service.executeTool(toolId, safeRequest, userId, orgId, resolvedRequestId);
            return ResponseEntity.ok(response);
        } catch (com.apimarketplace.catalog.service.exception.InsufficientCreditsException e) {
            // Pre-flight reservation refused: the tool was NOT executed, so this
            // is a 402 rather than a 200 envelope with success=false. Same body
            // shape as the CE relay's insufficient-credits refusal, so a caller
            // handles one shape for both.
            log.info("Tool {} refused - insufficient credits (delinquent={})", toolId, e.isDelinquent());
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of(
                            "success", false,
                            "error", com.apimarketplace.catalog.service.exception.InsufficientCreditsException.ERROR_CODE,
                            "message", e.getMessage(),
                            "delinquent", e.isDelinquent(),
                            "toolId", toolId
                    ));
        } catch (com.apimarketplace.catalog.service.exception.PlanUpgradeRequiredException e) {
            // Refused BEFORE any credential or billing work: this integration is not
            // part of the account's plan. 403 and not 402 - nothing is missing from
            // the balance, the feature is simply not sold at this tier - and the body
            // names the plan that includes it so every surface can offer the way out.
            log.info("Tool {} refused - plan upgrade required ({})", toolId, e.getRequiredPlan());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "success", false,
                            "error", com.apimarketplace.catalog.service.exception.PlanUpgradeRequiredException.ERROR_CODE,
                            "message", e.getMessage(),
                            "requiredPlan", e.getRequiredPlan(),
                            "toolId", toolId
                    ));
        } catch (com.apimarketplace.catalog.service.exception.CredentialSelectionException e) {
            // Refused BEFORE any external call: the step named a credential for this
            // run and it could not be matched. 422 and not 401, because nothing was
            // rejected by the provider - the request is well-formed, the platform
            // simply will not substitute a different account for the one asked for.
            log.warn("Tool {} refused - run-time credential selection unresolved: {}", toolId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of(
                            "success", false,
                            "error", com.apimarketplace.catalog.service.exception.CredentialSelectionException.ERROR_CODE,
                            "message", e.getMessage(),
                            "toolId", toolId
                    ));
        } catch (ApiAuthenticationException e) {
            // Return proper HTTP status (401/403) for auth errors - don't wrap in 200!
            log.warn("Authentication error executing tool {}: {} (status={})", toolId, e.getMessage(), e.getStatus());
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of(
                            "success", false,
                            "error", e.getMessage(),
                            "errorType", "authentication",
                            "service", e.getService() != null ? e.getService() : "unknown",
                            "toolId", toolId
                    ));
        } catch (Exception e) {
            log.error("Error executing tool {}", toolId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "message", "Unable to execute tool",
                            "toolId", toolId,
                            "error", e.getMessage()
                    ));
        } finally {
            com.apimarketplace.catalog.service.http.CredentialModeContext.clear();
            com.apimarketplace.catalog.service.http.ProviderRetryContext.clear();
        }
    }

    @GetMapping("/intents/resolve")
    public ResponseEntity<?> resolveIntent(@RequestParam("q") String query,
                                           @RequestParam(defaultValue = "5") int limit,
                                           @RequestHeader(value = "X-User-ID", required = false) String userId,
                                           @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Query parameter 'q' is required"
            ));
        }
        try {
            IntentResolutionResponse response = catalogV1Service.resolveIntent(query, limit, userId, orgId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error resolving intent", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "message", "Unable to resolve intent",
                            "error", e.getMessage()
                    ));
        }
    }
}

package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.dto.IntentResolutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolListResponse;
import com.apimarketplace.catalog.service.CatalogV1Service;
import com.apimarketplace.catalog.service.exception.ApiAuthenticationException;
import lombok.RequiredArgsConstructor;
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
                                         @RequestHeader(value = "X-Lc-Billing-Step-Id", required = false) String billingStepId) {
        applyBillingHeaders(request, billingScopeKind, billingScopeId, billingStepId);
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
                                                    @RequestHeader(value = "X-Lc-Billing-Step-Id", required = false) String billingStepId) {
        applyBillingHeaders(request, billingScopeKind, billingScopeId, billingStepId);
        // Combine apiSlug/toolSlug - service handles this format
        String toolId = apiSlug + "/" + toolSlug;
        return executeToolInternal(toolId, request, userId, orgId, requestId);
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
                                              String stepId) {
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
            com.apimarketplace.catalog.service.http.CredentialModeContext.setOverride(safeRequest.getCredentialModeOverride());

            ToolExecutionResponse response = catalogV1Service.executeTool(toolId, safeRequest, userId, orgId, resolvedRequestId);
            return ResponseEntity.ok(response);
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

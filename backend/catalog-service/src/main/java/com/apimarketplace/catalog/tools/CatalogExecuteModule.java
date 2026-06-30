package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.catalog.util.CredentialTypeNormalizer;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

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

        return Optional.of(executeCatalogExecute(parameters, context));
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeCatalogExecute(Map<String, Object> parameters, ToolExecutionContext context) {
        String toolId = (String) parameters.get("tool_id");
        if (toolId == null || toolId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "tool_id is required");
        }

        Map<String, Object> input = (Map<String, Object>) parameters.get("params");
        if (input == null) {
            input = (Map<String, Object>) parameters.get("parameters");
            if (input != null) {
                log.debug("catalog_execute: Using 'parameters' as fallback for 'params'");
            }
        }
        if (input == null) {
            input = (Map<String, Object>) parameters.get("input");
            if (input != null) {
                log.debug("catalog_execute: Using 'input' as legacy fallback for 'params'");
            }
        }
        if (input == null) {
            input = Map.of();
        }

        String tenantId = context != null ? context.tenantId() : null;
        log.info("JIT executing tool {} for tenant {} with input: {}", toolId, tenantId, input.keySet());

        ToolExecutionResult restrictionCheck = checkToolRestriction(toolId, context);
        if (restrictionCheck != null) {
            return restrictionCheck;
        }

        ToolExecutionResult approvalCheck = checkServiceApproval(toolId, context);
        if (approvalCheck != null) {
            return approvalCheck;
        }

        try {
            String localUrl = "http://localhost:" + serverPort;
            String url = localUrl + "/catalog/v1/tools/" + toolId + "/execute";

            HttpHeaders headers = CatalogToolHeaderSupport.jsonHeaders(tenantId, context);

            // V148+ chat-scope billing headers. Pull __streamId__ from agent
            // execution context credentials. ToolExecutionManager reads these
            // and feeds CatalogToolBillingService.billImmediate after success.
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
            // No credentialSource on the request → catalog applies the
            // implicit fallback-if-priced rule (try user, fall back to platform
            // when pricing is published for the endpoint). This module IS the
            // chat-agent's catalog dispatcher; agentic semantics are the
            // default, no marker needed.

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // V148+ unified billing: catalog-service runs the post-success hook
                // (ToolExecutionManager → CatalogToolBillingService.billImmediate).
                // Scope context is propagated via X-Lc-Billing-Scope-* headers above.
                return handleSuccessResponse(response.getBody(), toolId);
            } else if (response.getStatusCode() == HttpStatus.UNAUTHORIZED ||
                       response.getStatusCode() == HttpStatus.FORBIDDEN) {
                return ToolExecutionResult.failure(
                    ToolErrorCode.CREDENTIALS_REQUIRED,
                    "{\"error\": \"credentials_required\", \"tool_id\": \"" + toolId + "\"}"
                );
            } else {
                Map<String, Object> errorMetadata = extractMetadataFromResponse(response.getBody());
                return ToolExecutionResult.failure(
                    ToolErrorCode.EXECUTION_FAILED,
                    "Tool execution failed with status: " + response.getStatusCode(),
                    errorMetadata
                );
            }

        } catch (HttpClientErrorException e) {
            return handleHttpClientError(e, toolId);
        } catch (Exception e) {
            log.error("Error executing tool {}: {}", toolId, e.getMessage(), e);
            return ToolExecutionResult.failure(
                ToolErrorCode.EXECUTION_FAILED,
                "Failed to execute tool: " + e.getMessage()
            );
        }
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult handleSuccessResponse(String responseBody, String toolId) throws Exception {
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
                log.info("Tool {} requires credentials", toolId);

                String iconSlug = metadata.get("iconSlug") != null ? metadata.get("iconSlug").toString() : null;
                String serviceName = metadata.get("toolName") != null ? metadata.get("toolName").toString() :
                                   (iconSlug != null ? iconSlug.substring(0, 1).toUpperCase() + iconSlug.substring(1) : "Service");

                Map<String, Object> enrichedMetadata = new LinkedHashMap<>(metadata);
                enrichedMetadata.put("visualization", Map.of(
                    "type", "credential",
                    "id", toolId,
                    "title", serviceName,
                    "iconSlug", iconSlug != null ? iconSlug : "",
                    "serviceName", serviceName
                ));

                return ToolExecutionResult.failure(
                    ToolErrorCode.CREDENTIALS_REQUIRED,
                    objectMapper.writeValueAsString(resultMap),
                    enrichedMetadata
                );
            }

            return ToolExecutionResult.success(resultMap, metadata);
        }

        return ToolExecutionResult.success(Map.of("result", parsed));
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult handleHttpClientError(HttpClientErrorException e, String toolId) {
        Map<String, Object> errorBody = extractErrorBodyAsMap(e.getResponseBodyAsString());

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
                "Call request_credential(services=[\"%s\"], reason=\"Your %s session has expired. Please reconnect to continue.\")",
                serviceName,
                iconSlug != null ? iconSlug : "unknown",
                serviceName
            );

            return ToolExecutionResult.failure(
                ToolErrorCode.INVALID_CREDENTIALS,
                jitHint,
                authMetadata
            );
        } else {
            log.error("HTTP error executing tool {}: {} - {}", toolId, e.getStatusCode(), e.getMessage());
            return ToolExecutionResult.failure(
                ToolErrorCode.EXECUTION_FAILED,
                "Tool execution failed: " + e.getMessage()
            );
        }
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult checkServiceApproval(String toolId, ToolExecutionContext context) {
        if (context == null || context.tenantId() == null) {
            return null;
        }

        try {
            String localUrl = "http://localhost:" + serverPort;
            String url = localUrl + "/api/catalog/tools/" + toolId + "/info";
            HttpHeaders headers = CatalogToolHeaderSupport.jsonHeaders(context.tenantId(), context);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("Could not fetch tool info for credential check: {}", toolId);
                return null;
            }

            Map<String, Object> toolInfo = response.getBody();

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

            String serviceType = iconSlug.toLowerCase();
            Optional<CredentialSummaryDto> defaultCred = credentialClient.getDefaultCredential(context.tenantId(), serviceType);
            boolean hasDefaultCredential = defaultCred.isPresent();

            if (hasDefaultCredential) {
                log.debug("User has default credential '{}' for service {} (tool {})",
                    defaultCred.get().getName(), serviceType, toolId);
                return null;
            }

            log.info("User has no default credential for service {} (tool {}) - returning soft warning", serviceType, toolId);

            String serviceName = iconSlug.substring(0, 1).toUpperCase() + iconSlug.substring(1);

            // Credential requirement the agent reads so it can tell the user what KIND of
            // connection request_credential will trigger (api_key prompt vs an OAuth consent
            // screen, with the scopes that consent will request). The type lives in the
            // catalog (apis.auth_type, surfaced by /info as authType) - without threading it
            // here the agent only knew THAT a credential was needed, never which kind.
            Map<String, Object> credential = CredentialTypeNormalizer.buildRequirement(toolInfo);

            Map<String, Object> softWarning = new LinkedHashMap<>();
            softWarning.put("status", "approval_needed");
            softWarning.put("serviceType", serviceType);
            softWarning.put("serviceName", serviceName);
            softWarning.put("iconSlug", iconSlug);
            softWarning.put("toolId", toolId);
            softWarning.put("toolName", toolName != null ? toolName : "API Tool");
            softWarning.put("description", description != null ? description : "Access to " + serviceName);
            softWarning.put("credential", credential);
            softWarning.put("message", "Credential required for " + serviceName);
            softWarning.put("action", String.format(
                "request_credential(services=[\"%s\"], reason=\"...\")",
                serviceType
            ));

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

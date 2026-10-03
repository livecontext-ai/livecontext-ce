package com.apimarketplace.orchestrator.services.mcp;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Executes an aggregated remote tool (one owned by a sibling microservice, not the
 * local orchestrator registry) by POSTing to that service's
 * {@code /api/agent-tools/execute}. Used only by the cloud MCP server for tools it
 * does not host locally; local tools stay on the fast in-process path.
 *
 * <p><b>Authority.</b> External MCP callers act with the authority their API key was
 * granted: this path carries the resolved tenant / org headers, the per-tool access modes
 * derived from the key's scopes, and runs the tool directly, with no interactive approval
 * gate (that gate lives on the agent chat loop, not here), matching the behaviour of local
 * MCP tool calls.
 */
@Slf4j
public class RemoteToolGateway {

    private static final String EXECUTE_PATH = "/api/agent-tools/execute";

    /** Suffix of every per-tool access-mode body key ({@code tableAccessMode}, ...). */
    private static final String ACCESS_MODE_SUFFIX = "AccessMode";

    private final AggregatedToolCatalog catalog;
    private final RestTemplate executionRestTemplate;
    private final ObjectMapper objectMapper;
    private final String gatewaySecretKey;

    public RemoteToolGateway(AggregatedToolCatalog catalog, RestTemplate executionRestTemplate,
                             ObjectMapper objectMapper) {
        this(catalog, executionRestTemplate, objectMapper, null);
    }

    /**
     * @param gatewaySecretKey shared gateway secret; the execute call is signed with it (CASA
     *                         LC-013) so the sibling can authorize on the signed identity. Blank
     *                         sends unsigned (dev).
     */
    public RemoteToolGateway(AggregatedToolCatalog catalog, RestTemplate executionRestTemplate,
                             ObjectMapper objectMapper, String gatewaySecretKey) {
        this.catalog = catalog;
        this.executionRestTemplate = executionRestTemplate;
        this.objectMapper = objectMapper;
        this.gatewaySecretKey = gatewaySecretKey;
        if (gatewaySecretKey != null && !gatewaySecretKey.isBlank()) {
            executionRestTemplate.getInterceptors().add(
                    new com.apimarketplace.common.web.GatewaySignatureV2Interceptor(() -> gatewaySecretKey));
        }
    }

    /** Unrestricted variant, for callers that carry no per-tool access modes. */
    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments,
                                       String tenantId, String orgId, String orgRole) {
        return execute(toolName, arguments, tenantId, orgId, orgRole, Map.of());
    }

    /**
     * Executes {@code toolName} on its owning service, carrying the caller's per-tool access
     * modes (LC-055, security audit 2026-08-13).
     *
     * <p>The receiving {@code /api/agent-tools/execute} rebuilds its {@code ToolExecutionContext}
     * credentials map key by key from the request BODY, and every tool module reads an absent
     * {@code <category>AccessMode} as UNRESTRICTED. So a mode this request does not carry does
     * not exist at the far end: before these keys were forwarded, a read-only API key kept full
     * write authority inside every tool the orchestrator does not host locally, while the very
     * same key was correctly restricted on the tools it does host. The modes are put in the body
     * under their plain names, which is the shape all five receivers already read.
     *
     * @param restrictions per-tool access modes ({@code <category>AccessMode -> read|write}),
     *                     empty for a full-access caller, which is what unrestricted means
     */
    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments,
                                       String tenantId, String orgId, String orgRole,
                                       Map<String, Object> restrictions) {
        String baseUrl = catalog.serviceUrlFor(toolName);
        if (baseUrl == null) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }

        Map<String, Object> request = new HashMap<>();
        request.put("tool", toolName);
        request.put("parameters", arguments != null ? arguments : Map.of());
        if (tenantId != null) request.put("tenantId", tenantId);
        if (orgId != null) request.put("orgId", orgId);
        if (orgRole != null) request.put("orgRole", orgRole);
        // Top-level body keys, NOT nested and NOT inside "parameters": a receiver copies them
        // from the request root, and anything under "parameters" would reach the tool as a
        // caller-supplied argument instead of an access-control decision.
        //
        // Only <category>AccessMode keys are copied. The category half comes from the API
        // key's own scope strings, so the map's key set is caller-influenced; restricting the
        // shape here makes it structurally impossible for a scope to name "tool",
        // "parameters" or an identity field and overwrite what was put above.
        if (restrictions != null) {
            restrictions.forEach((key, value) -> {
                if (key != null && value != null && key.endsWith(ACCESS_MODE_SUFFIX)
                        && key.length() > ACCESS_MODE_SUFFIX.length()) {
                    request.put(key, value);
                }
            });
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
            headers.set("X-Tenant-Id", tenantId);
        }
        if (orgId != null) headers.set("X-Organization-ID", orgId);
        if (orgRole != null) headers.set("X-Organization-Role", orgRole);
        com.apimarketplace.common.web.InternalGatewaySigner.stamp(headers, "internal-mcp-gateway", gatewaySecretKey);

        try {
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = executionRestTemplate.exchange(
                    baseUrl + EXECUTE_PATH, HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            if (body == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                        "Empty response from tool " + toolName);
            }
            return toResult(body);
        } catch (HttpClientErrorException e) {
            // The service returns 400 with a JSON {success:false,error,...} body on tool failure.
            Map<String, Object> body = parseErrorBody(e);
            if (body != null) {
                return toResult(body);
            }
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "HTTP " + e.getStatusCode() + " from tool " + toolName);
        } catch (Exception e) {
            log.error("Remote MCP tool {} failed: {}", toolName, e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "Remote execution error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult toResult(Map<String, Object> body) {
        boolean success = Boolean.TRUE.equals(body.get("success"));
        Map<String, Object> metadata = body.get("metadata") instanceof Map
                ? (Map<String, Object>) body.get("metadata") : Map.of();
        if (success) {
            return ToolExecutionResult.success(body.getOrDefault("data", Map.of()), metadata);
        }
        String error = body.get("error") instanceof String s ? s : "Tool execution failed";
        return ToolExecutionResult.failure(errorCodeFrom(body), error, metadata);
    }

    /**
     * Preserve the sibling's own error classification: {@code /api/agent-tools/execute}
     * returns {@code errorType} = the {@link ToolErrorCode} enum name. Falls back to
     * {@code EXECUTION_FAILED} when absent or unrecognized.
     */
    private ToolErrorCode errorCodeFrom(Map<String, Object> body) {
        if (body.get("errorType") instanceof String type) {
            try {
                return ToolErrorCode.valueOf(type);
            } catch (IllegalArgumentException ignored) {
                // unknown enum name from a newer sibling: fall through
            }
        }
        return ToolErrorCode.EXECUTION_FAILED;
    }

    private Map<String, Object> parseErrorBody(HttpClientErrorException e) {
        String raw = e.getResponseBodyAsString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(raw, Map.class);
            return body;
        } catch (Exception ex) {
            return null;
        }
    }
}

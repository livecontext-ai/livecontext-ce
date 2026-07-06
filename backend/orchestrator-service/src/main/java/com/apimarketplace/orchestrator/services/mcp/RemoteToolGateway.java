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
 * <p><b>Authority.</b> External MCP callers act with full account authority: this
 * path carries the resolved tenant / org headers and runs the tool directly, with
 * no interactive approval gate (that gate lives on the agent chat loop, not here),
 * matching the behaviour of local MCP tool calls.
 */
@Slf4j
public class RemoteToolGateway {

    private static final String EXECUTE_PATH = "/api/agent-tools/execute";

    private final AggregatedToolCatalog catalog;
    private final RestTemplate executionRestTemplate;
    private final ObjectMapper objectMapper;

    public RemoteToolGateway(AggregatedToolCatalog catalog, RestTemplate executionRestTemplate,
                             ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.executionRestTemplate = executionRestTemplate;
        this.objectMapper = objectMapper;
    }

    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments,
                                       String tenantId, String orgId, String orgRole) {
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

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
            headers.set("X-Tenant-Id", tenantId);
        }
        if (orgId != null) headers.set("X-Organization-ID", orgId);
        if (orgRole != null) headers.set("X-Organization-Role", orgRole);

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

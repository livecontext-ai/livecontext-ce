package com.apimarketplace.datasource.controllers.tools;

import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST Controller for tool execution in datasource-service.
 * Exposes the same /api/agent-tools/execute endpoint format as orchestrator/agent-service.
 * Routes directly to the local DataSourceToolsProvider (no HTTP hop).
 * Disabled in monolith mode (orchestrator's tools controller handles everything).
 */
@RestController
@RequestMapping("/api/agent-tools")
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class ServiceToolsController {

    private static final Logger log = LoggerFactory.getLogger(ServiceToolsController.class);

    private final ToolsProvider toolsProvider;

    public ServiceToolsController(ToolsProvider toolsProvider) {
        this.toolsProvider = toolsProvider;
    }

    /**
     * List available tools.
     * GET /api/agent-tools
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listTools() {
        var tools = toolsProvider.getTools();
        // Use toSummary() to include full parameter schemas (needed by CoreToolsCache/CoreToolsProvider)
        var summaries = tools.stream()
            .map(t -> t.toSummary())
            .toList();

        return ResponseEntity.ok(Map.of(
            "tools", summaries,
            "count", summaries.size()
        ));
    }

    /**
     * Execute a tool.
     * POST /api/agent-tools/execute
     * Body: { "tool": "table", "parameters": {...}, ... }
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeTool(
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String toolName = (String) request.get("tool");
        if (toolName == null || toolName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "tool name is required",
                "errorCode", "TOOL_011"
            ));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) request.getOrDefault("parameters", Map.of());

        // Resolve tenant from X-User-ID header (injected by Gateway)
        String tenantId = resolveHeader(httpRequest, "X-User-ID");

        // Build variables map with runtime context (allowedIds, conversationId, turnId, etc.)
        Map<String, Object> variables = new HashMap<>();
        copyIfPresent(request, "conversationId", variables, "conversationId");
        copyIfPresent(request, "turnId", variables, "turnId");
        copyIfPresent(request, "allowedToolIds", variables, "allowedToolIds");
        copyIfPresent(request, "allowedWorkflowIds", variables, "allowedWorkflowIds");
        copyIfPresent(request, "allowedApplicationIds", variables, "allowedApplicationIds");
        copyIfPresent(request, "allowedInterfaceIds", variables, "allowedInterfaceIds");
        copyIfPresent(request, "allowedAgentIds", variables, "allowedAgentIds");

        // Build credentials map for actual credentials (API keys, tokens)
        Map<String, Object> credentials = new HashMap<>();
        copyIfPresent(request, "agentId", credentials, "__agentId__");
        copyIfPresent(request, "messageId", credentials, "__messageId__");
        copyIfPresent(request, "streamId", credentials, "__streamId__");
        copyIfPresent(request, "toolCallId", credentials, "__toolCallId__");
        // Approved table allow-list on the canonical CREDENTIALS channel: ToolAccessControl
        // .getAllowedIds (read by every table module via TableToolAccess) and grantCreatedResource
        // (create auto-grant) both operate on credentials, so the list and the grant round-trip.
        // Plain key name = CREDENTIAL_KEYS["table"]. (Previously threaded into variables, which the
        // grant write never reached → silent no-op + no allow-list on row/schema/publish ops.)
        copyIfPresent(request, "allowedTableIds", credentials, "allowedTableIds");
        // Access mode keys for ToolAccessControl (read/write per resource)
        for (String am : List.of("tableAccessMode", "workflowAccessMode", "interfaceAccessMode",
                "agentAccessMode", "applicationAccessMode", "skillAccessMode", "fileAccessMode")) {
            copyIfPresent(request, am, credentials, am);
        }

        // Extract approvedServices
        @SuppressWarnings("unchecked")
        Set<String> approvedServices = request.get("approvedServices") instanceof Collection
            ? new HashSet<>((Collection<String>) request.get("approvedServices"))
            : Set.of();

        // Extract workflow + org context
        String viewingWorkflowId = (String) request.get("viewingWorkflowId");
        String viewingWorkflowName = (String) request.get("viewingWorkflowName");
        String orgId = resolveHeader(httpRequest, "X-Organization-ID");
        String orgRole = resolveHeader(httpRequest, "X-Organization-Role");
        if (orgId == null) orgId = (String) request.get("orgId");
        if (orgRole == null) orgRole = (String) request.get("orgRole");

        ToolExecutionContext context = new ToolExecutionContext(
            tenantId, credentials, variables, approvedServices,
            viewingWorkflowId, viewingWorkflowName, orgId, orgRole
        );

        // Execute via local ToolsProvider
        ToolExecutionResult result = toolsProvider.execute(toolName, parameters, context);

        return buildToolResponse(toolName, result);
    }

    private ResponseEntity<Map<String, Object>> buildToolResponse(String toolName, ToolExecutionResult result) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", result.success());
        response.put("tool", toolName);

        if (result.success()) {
            response.put("data", result.data() != null ? result.data() : Map.of());
            response.put("metadata", result.metadata() != null ? result.metadata() : Map.of());
            return ResponseEntity.ok(response);
        } else {
            response.put("error", result.error() != null ? result.error() : "Unknown error");
            if (result.errorCode() != null) {
                response.put("errorCode", result.errorCode().getCode());
                response.put("errorType", result.errorCode().name());
            }
            response.put("metadata", result.metadata() != null ? result.metadata() : Map.of());
            return ResponseEntity.badRequest().body(response);
        }
    }

    private String resolveHeader(HttpServletRequest request, String header) {
        String value = request.getHeader(header);
        return value != null && !value.isBlank() ? value : null;
    }

    private void copyIfPresent(Map<String, Object> source, String sourceKey,
                                Map<String, Object> target, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }
}

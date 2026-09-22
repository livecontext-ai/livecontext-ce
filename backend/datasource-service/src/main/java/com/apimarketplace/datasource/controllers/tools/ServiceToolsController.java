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
     * List available tools in MCP {@code tools/list} format
     * ({@code name} / {@code description} / {@code inputSchema}), so the cloud MCP
     * server can aggregate this service's tools alongside its own.
     * GET /api/agent-tools/mcp/tools
     */
    @GetMapping("/mcp/tools")
    public ResponseEntity<Map<String, Object>> listMcpTools() {
        var mcpTools = toolsProvider.getTools().stream()
            .map(t -> t.toMcpFormat())
            .toList();
        return ResponseEntity.ok(Map.of("tools", mcpTools));
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
        for (String am : com.apimarketplace.agent.config.ToolAccessControl.ACCESS_MODE_KEYS) {
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
        // The ROLE is never taken from the request body. This endpoint is gateway-routed, and the
        // gateway strips the caller's own identity HEADERS but not the body, so a user whose
        // gateway resolved no active org could name a workspace AND assert OWNER in it in one
        // request. Every legitimate internal caller already sends X-Organization-Role as a HEADER
        // from the same source it filled the body field with (RemoteToolExecutionService
        // .applyOrgHeaders, and the conversation relay's own forwarding), so dropping the body
        // read costs them nothing. An absent role resolves to MEMBER, the safe direction.
        //
        // The role GRANTS as well as refuses, which is why this is not cosmetic:
        // OrgAccessGuardImpl.getRestrictedResourceIds and getWriteRestrictedResourceIds return
        // Set.of() for OWNER/ADMIN, skipping the auth-service lookup entirely, so a body-asserted
        // OWNER did not merely avoid the VIEWER refusal, it bypassed that workspace's whole
        // restricted-resource list. Do not re-read this as "the role only ever refuses" and
        // restore the fallback.
        //
        // orgId is STILL read from the body, and that is a compatibility decision, not a safety
        // one: it is forgeable by the same route. Closing it needs the internal callers to name
        // the workspace by header first, which is a separate change.

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

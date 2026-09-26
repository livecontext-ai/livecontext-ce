package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import com.apimarketplace.agent.tools.common.ToolMediaMetadata;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * REST Controller for Agent Tools API.
 * Exposes structured tools for AI agents to discover and execute.
 */
@Slf4j
@RestController
@RequestMapping("/api/agent-tools")
@RequiredArgsConstructor
public class AgentToolsController {

    private final AgentToolRegistry registry;
    private final ToolsRegistrationService registrationService;

    /**
     * Resolve tenantId from X-User-ID header (injected by Gateway).
     */
    private String resolveTenantId(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-ID");
        return userIdHeader != null && !userIdHeader.isBlank() ? userIdHeader : null;
    }

    /**
     * Stores the caller's platform role set (X-User-Roles, injected by the gateway
     * from JWT claims; falls back to the request-body {@code userRoles} field for
     * callers that forward it there) into the credentials map under
     * {@code __userRoles__}. Tool modules that run in the service layer - e.g.
     * {@code AgentHelpModule}, which hides admin-only CLI-bridge models from
     * non-admin agents - read it from there without needing an HttpServletRequest.
     */
    private void applyUserRoles(Map<String, Object> credentials,
                                HttpServletRequest httpRequest,
                                Map<String, Object> request) {
        String roles = httpRequest.getHeader("X-User-Roles");
        if ((roles == null || roles.isBlank()) && request.get("userRoles") instanceof String body) {
            roles = body;
        }
        if (roles != null && !roles.isBlank()) {
            credentials.put("__userRoles__", roles);
        }
    }

    private Map<String, Object> buildCredentials(Map<String, Object> request) {
        Map<String, Object> credentials = new HashMap<>();

        if (request.get("conversationId") != null) {
            credentials.put("conversationId", request.get("conversationId"));
        }
        if (request.get("turnId") != null) {
            credentials.put("turnId", request.get("turnId"));
        }
        if (request.get("agentId") != null) {
            credentials.put("__agentId__", request.get("agentId"));
        }
        // The grant travels WITH the id. This map is rebuilt key by key, so a
        // key not copied here is simply gone, and a tool at the other end then
        // sees a caller with no stated permissions, which reads as allowed.
        if (request.get("enabledModules") != null) {
            credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, request.get("enabledModules"));
        }
        if (request.get("messageId") != null) {
            credentials.put("__messageId__", request.get("messageId"));
        }
        if (request.get("streamId") != null) {
            credentials.put("__streamId__", request.get("streamId"));
        }
        // The arming travels WITH the id, same rule as the grant above: a key not
        // copied here is gone, and the gate then treats an armed agent as unarmed.
        if (request.get("requireToolAuthorization") != null) {
            credentials.put("__requireToolAuthorization__", request.get("requireToolAuthorization"));
        }
        if (request.get("unattendedRun") != null) {
            credentials.put("__unattendedRun__", request.get("unattendedRun"));
        }
        if (request.get("reviewerExecutionId") != null) {
            credentials.put("__reviewerExecutionId__", request.get("reviewerExecutionId"));
        }
        if (request.get("toolCallId") != null) {
            credentials.put("__toolCallId__", request.get("toolCallId"));
        }
        for (String allowedKey : List.of(
                "allowedToolIds",
                "allowedWorkflowIds",
                "allowedApplicationIds",
                "allowedTableIds",
                "allowedInterfaceIds",
                "allowedAgentIds",
                "allowedFileIds")) {
            if (request.get(allowedKey) != null) {
                credentials.put(allowedKey, request.get(allowedKey));
            }
        }
        for (String accessModeKey : ToolAccessControl.ACCESS_MODE_KEYS) {
            if (request.get(accessModeKey) != null) {
                credentials.put(accessModeKey, request.get(accessModeKey));
            }
        }

        return credentials;
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<String> resolveApprovedServices(Map<String, Object> request) {
        return request.get("approvedServices") instanceof java.util.Collection
            ? new java.util.HashSet<>((java.util.Collection<String>) request.get("approvedServices"))
            : java.util.Set.of();
    }

    // ==================== Discovery Endpoints ====================

    /**
     * List all available tools.
     * GET /api/agent-tools
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listAllTools(
            @RequestParam(required = false) String category) {

        List<AgentToolDefinition> tools;
        if (category != null && !category.isBlank()) {
            ToolCategory cat = ToolCategory.fromSlug(category);
            if (cat == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid category: " + category,
                    "validCategories", Arrays.stream(ToolCategory.values())
                        .map(ToolCategory::getSlug)
                        .toList()
                ));
            }
            tools = registry.getToolsByCategory(cat);
        } else {
            tools = registry.getAllTools();
        }

        List<Map<String, Object>> toolSummaries = tools.stream()
            .map(AgentToolDefinition::toSummary)
            .toList();

        return ResponseEntity.ok(Map.of(
            "tools", toolSummaries,
            "count", toolSummaries.size(),
            "categories", Arrays.stream(ToolCategory.values())
                .map(cat -> Map.of(
                    "slug", cat.getSlug(),
                    "name", cat.getDisplayName(),
                    "description", cat.getDescription()
                ))
                .toList()
        ));
    }

    /**
     * List all categories.
     * GET /api/agent-tools/categories
     */
    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> listCategories() {
        List<Map<String, Object>> categories = Arrays.stream(ToolCategory.values())
            .map(cat -> {
                List<AgentToolDefinition> tools = registry.getToolsByCategory(cat);
                return Map.<String, Object>of(
                    "slug", cat.getSlug(),
                    "name", cat.getDisplayName(),
                    "description", cat.getDescription(),
                    "toolCount", tools.size(),
                    "tools", tools.stream().map(AgentToolDefinition::name).toList()
                );
            })
            .toList();

        return ResponseEntity.ok(Map.of(
            "categories", categories,
            "count", categories.size()
        ));
    }

    /**
     * Get tools by category.
     * GET /api/agent-tools/category/{category}
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<Map<String, Object>> getToolsByCategory(@PathVariable String category) {
        ToolCategory cat = ToolCategory.fromSlug(category);
        if (cat == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid category: " + category,
                "validCategories", Arrays.stream(ToolCategory.values())
                    .map(ToolCategory::getSlug)
                    .toList()
            ));
        }

        List<AgentToolDefinition> tools = registry.getToolsByCategory(cat);
        return ResponseEntity.ok(Map.of(
            "category", Map.of(
                "slug", cat.getSlug(),
                "name", cat.getDisplayName(),
                "description", cat.getDescription()
            ),
            "tools", tools.stream().map(AgentToolDefinition::toSummary).toList(),
            "count", tools.size()
        ));
    }

    /**
     * Get tool details by name.
     * GET /api/agent-tools/{name}
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getToolDetails(@PathVariable String name) {
        return registry.getToolByName(name)
            .map(tool -> {
                Map<String, Object> details = new java.util.HashMap<>();
                details.put("name", tool.name());
                details.put("description", tool.description());
                details.put("category", tool.category().getSlug());
                details.put("parameters", tool.parameters());
                details.put("requiredParameters", tool.requiredParameters());
                details.put("inputSchema", tool.inputSchema() != null ? tool.inputSchema() : Map.of());
                details.put("outputSchema", tool.outputSchema() != null ? tool.outputSchema() : Map.of());
                details.put("examples", tool.examples() != null ? tool.examples() : List.of());
                details.put("helpText", tool.helpText() != null ? tool.helpText() : "");
                details.put("requiresAuth", tool.requiresAuth());
                details.put("tags", tool.tags() != null ? tool.tags() : List.of());
                return ResponseEntity.ok(details);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get tool input schema.
     * GET /api/agent-tools/{name}/schema
     */
    @GetMapping("/{name}/schema")
    public ResponseEntity<Map<String, Object>> getToolSchema(@PathVariable String name) {
        Map<String, Object> schema = registry.getToolInputSchema(name);
        if (schema == null || schema.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(schema);
    }

    /**
     * Get tool examples.
     * GET /api/agent-tools/{name}/examples
     */
    @GetMapping("/{name}/examples")
    public ResponseEntity<Map<String, Object>> getToolExamples(@PathVariable String name) {
        return registry.getToolByName(name)
            .map(tool -> ResponseEntity.ok(Map.<String, Object>of(
                "name", tool.name(),
                "examples", tool.examples() != null ? tool.examples() : List.of(),
                "helpText", tool.helpText() != null ? tool.helpText() : ""
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Search tools by query.
     * GET /api/agent-tools/search?q={query}&max={maxResults}
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchTools(
            @RequestParam("q") String query,
            @RequestParam(value = "max", defaultValue = "10") int maxResults) {

        List<AgentToolDefinition> tools = registry.searchTools(query, maxResults);
        return ResponseEntity.ok(Map.of(
            "query", query,
            "tools", tools.stream().map(AgentToolDefinition::toSummary).toList(),
            "count", tools.size()
        ));
    }

    // ==================== Execution Endpoint ====================

    /**
     * Execute a tool.
     * POST /api/agent-tools/execute
     * Body: { "tool": "workflow_create", "parameters": {...} }
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

        // Check if tool exists
        if (!registry.hasTool(toolName)) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "Tool not found: " + toolName,
                "errorCode", "TOOL_001",
                "hint", "Use GET /api/agent-tools to list available tools"
            ));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) request.getOrDefault("parameters", Map.of());

        // Resolve tenant from header
        String tenantId = resolveTenantId(httpRequest);

        Map<String, Object> credentials = buildCredentials(request);
        applyUserRoles(credentials, httpRequest, request);

        java.util.Set<String> approvedServices = resolveApprovedServices(request);

        // Extract workflow context (passed from conversation-service when user is viewing a workflow)
        String viewingWorkflowId = (String) request.get("viewingWorkflowId");
        String viewingWorkflowName = (String) request.get("viewingWorkflowName");

        // Extract org context from headers (injected by Gateway) or from request body (forwarded by conversation-service)
        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");
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

        ToolsProvider.ToolExecutionContext context = new ToolsProvider.ToolExecutionContext(
            tenantId,
            credentials,
            Map.of(),  // variables
            approvedServices,
            viewingWorkflowId,
            viewingWorkflowName,
            orgId,
            orgRole
        );

        // Find provider and execute
        ToolsProvider.ToolExecutionResult result = registrationService.executeTool(toolName, parameters, context);

        return buildToolResponse(toolName, result);
    }

    /**
     * Execute a tool asynchronously.
     * POST /api/agent-tools/execute-async
     */
    @PostMapping("/execute-async")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> executeToolAsync(
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String toolName = (String) request.get("tool");
        if (toolName == null || toolName.isBlank()) {
            return CompletableFuture.completedFuture(
                ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "tool name is required",
                    "errorCode", "TOOL_011"
                ))
            );
        }

        if (!registry.hasTool(toolName)) {
            return CompletableFuture.completedFuture(
                ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Tool not found: " + toolName,
                    "errorCode", "TOOL_001"
                ))
            );
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) request.getOrDefault("parameters", Map.of());

        String tenantId = resolveTenantId(httpRequest);

        Map<String, Object> credentials = buildCredentials(request);
        applyUserRoles(credentials, httpRequest, request);
        java.util.Set<String> approvedServices = resolveApprovedServices(request);

        String viewingWorkflowId = (String) request.get("viewingWorkflowId");
        String viewingWorkflowName = (String) request.get("viewingWorkflowName");

        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");
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

        ToolsProvider.ToolExecutionContext context = new ToolsProvider.ToolExecutionContext(
            tenantId, credentials, Map.of(), approvedServices, viewingWorkflowId, viewingWorkflowName, orgId, orgRole
        );

        return registrationService.executeToolAsync(toolName, parameters, context)
            .thenApply(result -> buildToolResponse(toolName, result));
    }

    /**
     * Build response from tool execution result.
     */
    private ResponseEntity<Map<String, Object>> buildToolResponse(String toolName, ToolsProvider.ToolExecutionResult result) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", result.success());
        response.put("tool", toolName);

        if (result.success()) {
            Object data = result.data() != null ? result.data() : Map.of();
            response.put("data", data);
            response.put("metadata", result.metadata() != null ? result.metadata() : Map.of());

            // The HTTP response keeps the full metadata (the MCP bridge needs the __media__
            // vision bytes), but the log line must NOT dump multi-MB base64 - strip it first.
            log.info("Tool {} execution success - data keys: {}, metadata: {}", toolName,
                data instanceof Map ? ((Map<?, ?>)data).keySet() : "not a map",
                ToolMediaMetadata.withoutHeavyMedia(result.metadata()));
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

    // ==================== MCP Format Endpoints ====================

    /**
     * Get all tools in MCP format.
     * GET /api/agent-tools/mcp/tools
     */
    @GetMapping("/mcp/tools")
    public ResponseEntity<Map<String, Object>> getMcpTools() {
        return ResponseEntity.ok(Map.of(
            "tools", registry.getToolsInMcpFormat()
        ));
    }
}

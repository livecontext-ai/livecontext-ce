package com.apimarketplace.orchestrator.controllers.agent;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import com.apimarketplace.agent.tools.common.ToolMediaMetadata;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * REST Controller for Agent Tools API (orchestrator-side).
 * Mirrors the agent-service AgentToolsController but runs in orchestrator context
 * where all tool providers (catalog, workflow, table, etc.) are registered.
 *
 * conversation-service calls these endpoints to discover and execute tools.
 * Disabled in monolith mode (agent-service controller handles it directly).
 */
@Slf4j
@RestController
@RequestMapping("/api/agent-tools")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class AgentToolsController {

    private final AgentToolRegistry registry;
    private final ToolsRegistrationService registrationService;

    private String resolveTenantId(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-ID");
        return userIdHeader != null && !userIdHeader.isBlank() ? userIdHeader : null;
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
        if (request.get("messageId") != null) {
            credentials.put("__messageId__", request.get("messageId"));
        }
        if (request.get("streamId") != null) {
            credentials.put("__streamId__", request.get("streamId"));
        }
        if (request.get("toolCallId") != null) {
            credentials.put("__toolCallId__", request.get("toolCallId"));
        }
        if (request.get("workflowRunId") != null) {
            credentials.put("__workflowRunId__", request.get("workflowRunId"));
        }
        if (request.get("workflowNodeId") != null) {
            credentials.put("__workflowNodeId__", request.get("workflowNodeId"));
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
        for (String accessModeKey : List.of(
                "tableAccessMode",
                "workflowAccessMode",
                "interfaceAccessMode",
                "agentAccessMode",
                "applicationAccessMode",
                "skillAccessMode",
                "fileAccessMode")) {
            if (request.get(accessModeKey) != null) {
                credentials.put(accessModeKey, request.get(accessModeKey));
            }
        }
        return credentials;
    }

    @SuppressWarnings("unchecked")
    private Set<String> resolveApprovedServices(Map<String, Object> request) {
        return request.get("approvedServices") instanceof Collection
            ? new HashSet<>((Collection<String>) request.get("approvedServices"))
            : Set.of();
    }

    // ==================== Discovery Endpoints ====================

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

    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getToolDetails(@PathVariable String name) {
        return registry.getToolByName(name)
            .map(tool -> {
                Map<String, Object> details = new HashMap<>();
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

    @GetMapping("/{name}/schema")
    public ResponseEntity<Map<String, Object>> getToolSchema(@PathVariable String name) {
        Map<String, Object> schema = registry.getToolInputSchema(name);
        if (schema == null || schema.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(schema);
    }

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

    // ==================== Execution Endpoints ====================

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

        String tenantId = resolveTenantId(httpRequest);

        String conversationId = (String) request.get("conversationId");
        Map<String, Object> credentials = new HashMap<>();
        if (conversationId != null) {
            credentials.put("conversationId", conversationId);
        }

        // Forwarded by conversation-service from the current agent turn. Required by
        // WorkflowBuilderProvider's per-turn create cap (and any future orchestrator-side
        // resource limiter); without it the cap is silently bypassed.
        if (request.get("turnId") != null) {
            credentials.put("turnId", request.get("turnId"));
        }

        if (request.get("agentId") != null) {
            credentials.put("__agentId__", request.get("agentId"));
        }
        if (request.get("messageId") != null) {
            credentials.put("__messageId__", request.get("messageId"));
        }
        if (request.get("streamId") != null) {
            credentials.put("__streamId__", request.get("streamId"));
        }
        if (request.get("toolCallId") != null) {
            credentials.put("__toolCallId__", request.get("toolCallId"));
        }
        if (request.get("workflowRunId") != null) {
            credentials.put("__workflowRunId__", request.get("workflowRunId"));
        }
        if (request.get("workflowNodeId") != null) {
            credentials.put("__workflowNodeId__", request.get("workflowNodeId"));
        }
        if (request.get("allowedToolIds") != null) {
            credentials.put("allowedToolIds", request.get("allowedToolIds"));
        }
        if (request.get("allowedWorkflowIds") != null) {
            credentials.put("allowedWorkflowIds", request.get("allowedWorkflowIds"));
        }
        if (request.get("allowedApplicationIds") != null) {
            credentials.put("allowedApplicationIds", request.get("allowedApplicationIds"));
        }
        if (request.get("allowedTableIds") != null) {
            credentials.put("allowedTableIds", request.get("allowedTableIds"));
        }
        if (request.get("allowedInterfaceIds") != null) {
            credentials.put("allowedInterfaceIds", request.get("allowedInterfaceIds"));
        }
        if (request.get("allowedAgentIds") != null) {
            credentials.put("allowedAgentIds", request.get("allowedAgentIds"));
        }
        if (request.get("allowedFileIds") != null) {
            credentials.put("allowedFileIds", request.get("allowedFileIds"));
        }
        // Access mode keys for ToolAccessControl (read/write per resource)
        for (String am : List.of("tableAccessMode", "workflowAccessMode", "interfaceAccessMode",
                "agentAccessMode", "applicationAccessMode", "skillAccessMode", "fileAccessMode")) {
            if (request.get(am) != null) {
                credentials.put(am, request.get(am));
            }
        }

        @SuppressWarnings("unchecked")
        Set<String> approvedServices = request.get("approvedServices") instanceof Collection
            ? new HashSet<>((Collection<String>) request.get("approvedServices"))
            : Set.of();

        String viewingWorkflowId = (String) request.get("viewingWorkflowId");
        String viewingWorkflowName = (String) request.get("viewingWorkflowName");

        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");
        if (orgId == null) orgId = (String) request.get("orgId");
        if (orgRole == null) orgRole = (String) request.get("orgRole");

        ToolsProvider.ToolExecutionContext context = new ToolsProvider.ToolExecutionContext(
            tenantId, credentials, Map.of(), approvedServices,
            viewingWorkflowId, viewingWorkflowName, orgId, orgRole
        );

        ToolsProvider.ToolExecutionResult result = registrationService.executeTool(toolName, parameters, context);
        return buildToolResponse(toolName, result);
    }

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
        Set<String> approvedServices = resolveApprovedServices(request);

        String viewingWorkflowId = (String) request.get("viewingWorkflowId");
        String viewingWorkflowName = (String) request.get("viewingWorkflowName");

        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");
        if (orgId == null) orgId = (String) request.get("orgId");
        if (orgRole == null) orgRole = (String) request.get("orgRole");

        ToolsProvider.ToolExecutionContext context = new ToolsProvider.ToolExecutionContext(
            tenantId, credentials, Map.of(), approvedServices, viewingWorkflowId, viewingWorkflowName, orgId, orgRole
        );

        return registrationService.executeToolAsync(toolName, parameters, context)
            .thenApply(result -> buildToolResponse(toolName, result));
    }

    // ==================== MCP Format ====================

    @GetMapping("/mcp/tools")
    public ResponseEntity<Map<String, Object>> getMcpTools() {
        return ResponseEntity.ok(Map.of(
            "tools", registry.getToolsInMcpFormat()
        ));
    }

    // ==================== Helpers ====================

    private ResponseEntity<Map<String, Object>> buildToolResponse(
            String toolName, ToolsProvider.ToolExecutionResult result) {
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
                data instanceof Map ? ((Map<?, ?>) data).keySet() : "not a map",
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
}

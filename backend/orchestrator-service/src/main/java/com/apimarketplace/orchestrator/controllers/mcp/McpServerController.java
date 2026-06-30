package com.apimarketplace.orchestrator.controllers.mcp;

import com.apimarketplace.agent.mcp.McpMessageTypes.*;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) Server Controller.
 * Implements the MCP specification for exposing tools to AI agents.
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
// Note: CORS is handled by the Gateway (see gateway/config/CorsConfig.java)
@RequiredArgsConstructor
public class McpServerController {

    private final AgentToolRegistry registry;
    private final ToolsRegistrationService registrationService;
    private final ObjectMapper objectMapper;

    private static final String PROTOCOL_VERSION = "2024-11-05";

    /**
     * Resolve tenantId from X-User-ID header.
     */
    private String resolveTenantId(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-ID");
        return userIdHeader != null && !userIdHeader.isBlank() ? userIdHeader : null;
    }

    // ==================== MCP Core Endpoints ====================

    /**
     * Initialize MCP session.
     * POST /api/mcp/initialize
     */
    @PostMapping("/initialize")
    public ResponseEntity<JsonRpcResponse> initialize(@RequestBody JsonRpcRequest request) {
        log.info("MCP initialize request from client");

        InitializeResponse response = InitializeResponse.create();
        return ResponseEntity.ok(JsonRpcResponse.success(Map.of(
            "protocolVersion", PROTOCOL_VERSION,
            "serverInfo", Map.of(
                "name", "LiveContext Agent Tools",
                "version", "1.0.0"
            ),
            "capabilities", Map.of(
                "tools", Map.of(),
                "resources", Map.of()
            )
        ), request.id()));
    }

    /**
     * List available tools in MCP format.
     * GET /api/mcp/tools/list
     */
    @GetMapping("/tools/list")
    public ResponseEntity<Map<String, Object>> listTools() {
        List<Map<String, Object>> mcpTools = registry.getToolsInMcpFormat();

        return ResponseEntity.ok(Map.of(
            "tools", mcpTools
        ));
    }

    /**
     * List tools via JSON-RPC.
     * POST /api/mcp/tools/list
     */
    @PostMapping("/tools/list")
    public ResponseEntity<JsonRpcResponse> listToolsRpc(@RequestBody JsonRpcRequest request) {
        List<Map<String, Object>> mcpTools = registry.getToolsInMcpFormat();

        return ResponseEntity.ok(JsonRpcResponse.success(Map.of(
            "tools", mcpTools
        ), request.id()));
    }

    /**
     * Call a tool.
     * POST /api/mcp/tools/call
     */
    @PostMapping("/tools/call")
    public ResponseEntity<JsonRpcResponse> callTool(
            HttpServletRequest httpRequest,
            @RequestBody JsonRpcRequest request) {

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) request.params();

            String toolName = (String) params.get("name");
            if (toolName == null || toolName.isBlank()) {
                return ResponseEntity.ok(JsonRpcResponse.error(
                    JsonRpcError.INVALID_PARAMS,
                    "tool name is required",
                    request.id()
                ));
            }

            // Check if tool exists
            if (!registry.hasTool(toolName)) {
                return ResponseEntity.ok(JsonRpcResponse.error(
                    JsonRpcError.METHOD_NOT_FOUND,
                    "Tool not found: " + toolName,
                    request.id()
                ));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

            // Resolve tenant and org context from headers
            String tenantId = resolveTenantId(httpRequest);
            String orgId = httpRequest.getHeader("X-Organization-ID");
            String orgRole = httpRequest.getHeader("X-Organization-Role");

            ToolsProvider.ToolExecutionContext context = new ToolsProvider.ToolExecutionContext(
                tenantId,
                Map.of(),
                Map.of(),
                java.util.Set.of(),  // No approved services for MCP calls
                null,  // viewingWorkflowId
                null,  // viewingWorkflowName
                orgId,
                orgRole
            );

            // Execute tool
            ToolsProvider.ToolExecutionResult result = registrationService.executeTool(
                toolName, arguments, context
            );

            if (result.success()) {
                // Format response as MCP tool result
                String textContent;
                if (result.data() instanceof Map || result.data() instanceof List) {
                    textContent = objectMapper.writeValueAsString(result.data());
                } else {
                    textContent = result.data() != null ? result.data().toString() : "";
                }

                return ResponseEntity.ok(JsonRpcResponse.success(Map.of(
                    "content", List.of(Map.of(
                        "type", "text",
                        "text", textContent
                    )),
                    "isError", false
                ), request.id()));
            } else {
                return ResponseEntity.ok(JsonRpcResponse.success(Map.of(
                    "content", List.of(Map.of(
                        "type", "text",
                        "text", result.error() != null ? result.error() : "Tool execution failed"
                    )),
                    "isError", true
                ), request.id()));
            }

        } catch (Exception e) {
            log.error("Error calling tool via MCP: {}", e.getMessage(), e);
            return ResponseEntity.ok(JsonRpcResponse.error(
                JsonRpcError.INTERNAL_ERROR,
                "Error: " + e.getMessage(),
                request.id()
            ));
        }
    }

    // ==================== MCP Resources ====================

    /**
     * List available resources.
     * GET /api/mcp/resources/list
     */
    @GetMapping("/resources/list")
    public ResponseEntity<Map<String, Object>> listResources() {
        // Resources represent schemas and documentation
        List<Map<String, Object>> resources = List.of(
            Map.of(
                "uri", "schema://workflow",
                "name", "Workflow Schema",
                "description", "JSON Schema for workflow plans",
                "mimeType", "application/json"
            ),
            Map.of(
                "uri", "schema://agent",
                "name", "Agent Schema",
                "description", "JSON Schema for agent configuration",
                "mimeType", "application/json"
            ),
            Map.of(
                "uri", "schema://interface",
                "name", "Interface Schema",
                "description", "JSON Schema for interfaces (display, interactive apps, multi-page)",
                "mimeType", "application/json"
            ),
            Map.of(
                "uri", "schema://datasource",
                "name", "DataSource Schema",
                "description", "JSON Schema for data sources",
                "mimeType", "application/json"
            ),
            Map.of(
                "uri", "docs://tools",
                "name", "Tools Documentation",
                "description", "Full documentation of all available tools",
                "mimeType", "text/markdown"
            )
        );

        return ResponseEntity.ok(Map.of(
            "resources", resources
        ));
    }

    /**
     * List resources via JSON-RPC.
     * POST /api/mcp/resources/list
     */
    @PostMapping("/resources/list")
    public ResponseEntity<JsonRpcResponse> listResourcesRpc(@RequestBody JsonRpcRequest request) {
        var resources = listResources().getBody();
        return ResponseEntity.ok(JsonRpcResponse.success(resources, request.id()));
    }

    /**
     * Read a resource.
     * POST /api/mcp/resources/read
     */
    @PostMapping("/resources/read")
    public ResponseEntity<JsonRpcResponse> readResource(@RequestBody JsonRpcRequest request) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) request.params();
            String uri = (String) params.get("uri");

            if (uri == null || uri.isBlank()) {
                return ResponseEntity.ok(JsonRpcResponse.error(
                    JsonRpcError.INVALID_PARAMS,
                    "uri is required",
                    request.id()
                ));
            }

            String content = getResourceContent(uri);
            if (content == null) {
                return ResponseEntity.ok(JsonRpcResponse.error(
                    JsonRpcError.INVALID_PARAMS,
                    "Resource not found: " + uri,
                    request.id()
                ));
            }

            return ResponseEntity.ok(JsonRpcResponse.success(Map.of(
                "contents", List.of(Map.of(
                    "uri", uri,
                    "mimeType", uri.startsWith("schema://") ? "application/json" : "text/markdown",
                    "text", content
                ))
            ), request.id()));

        } catch (Exception e) {
            log.error("Error reading resource: {}", e.getMessage(), e);
            return ResponseEntity.ok(JsonRpcResponse.error(
                JsonRpcError.INTERNAL_ERROR,
                "Error: " + e.getMessage(),
                request.id()
            ));
        }
    }

    // ==================== MCP Server Info ====================

    /**
     * Get server information.
     * GET /api/mcp/info
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getServerInfo() {
        return ResponseEntity.ok(Map.of(
            "name", "LiveContext Agent Tools",
            "version", "1.0.0",
            "protocolVersion", PROTOCOL_VERSION,
            "capabilities", Map.of(
                "tools", true,
                "resources", true,
                "prompts", false
            ),
            "toolCount", registry.getAllTools().size(),
            "categories", java.util.Arrays.stream(ToolCategory.values())
                .map(cat -> Map.of(
                    "slug", cat.getSlug(),
                    "name", cat.getDisplayName(),
                    "toolCount", registry.getToolsByCategory(cat).size()
                ))
                .toList()
        ));
    }

    // ==================== Helper Methods ====================

    private String getResourceContent(String uri) {
        try {
            return switch (uri) {
                case "schema://workflow" -> objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(com.apimarketplace.agent.registry.ToolSchemaGenerator.getWorkflowPlanSchema());
                case "schema://agent" -> objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(com.apimarketplace.agent.registry.ToolSchemaGenerator.getAgentConfigSchema());
                case "schema://interface" -> objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(com.apimarketplace.agent.registry.ToolSchemaGenerator.getInterfaceSchema());
                case "schema://datasource" -> objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(com.apimarketplace.agent.registry.ToolSchemaGenerator.getDataSourceSchema());
                case "docs://tools" -> generateToolsDocumentation();
                default -> null;
            };
        } catch (Exception e) {
            log.error("Error generating resource content for {}: {}", uri, e.getMessage());
            return null;
        }
    }

    private String generateToolsDocumentation() {
        StringBuilder sb = new StringBuilder();
        sb.append("# LiveContext Agent Tools\n\n");
        sb.append("This document lists all available tools for AI agents.\n\n");

        for (ToolCategory category : ToolCategory.values()) {
            List<AgentToolDefinition> tools = registry.getToolsByCategory(category);
            if (tools.isEmpty()) continue;

            sb.append("## ").append(category.getDisplayName()).append("\n\n");
            sb.append(category.getDescription()).append("\n\n");

            for (AgentToolDefinition tool : tools) {
                sb.append("### `").append(tool.name()).append("`\n\n");
                sb.append(tool.description()).append("\n\n");

                if (tool.helpText() != null && !tool.helpText().isBlank()) {
                    sb.append(tool.helpText()).append("\n\n");
                }

                if (!tool.requiredParameters().isEmpty()) {
                    sb.append("**Required Parameters:** ");
                    sb.append(String.join(", ", tool.requiredParameters())).append("\n\n");
                }
            }
        }

        return sb.toString();
    }
}

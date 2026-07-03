package com.apimarketplace.orchestrator.controllers.mcp;

import com.apimarketplace.agent.mcp.McpMessageTypes.*;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.orchestrator.services.mcp.McpProtocolService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) Server Controller.
 * Exposes the tool registry through REST-shaped MCP endpoints (one path per method).
 * The standard single-endpoint Streamable HTTP transport lives at /mcp
 * ({@link McpStreamableHttpController}); both delegate to {@link McpProtocolService}.
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
// Note: CORS is handled by the Gateway (see gateway/config/CorsConfig.java)
@RequiredArgsConstructor
public class McpServerController {

    private final AgentToolRegistry registry;
    private final McpProtocolService protocolService;

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
        return ResponseEntity.ok(Map.of(
            "tools", protocolService.listTools()
        ));
    }

    /**
     * List tools via JSON-RPC.
     * POST /api/mcp/tools/list
     */
    @PostMapping("/tools/list")
    public ResponseEntity<JsonRpcResponse> listToolsRpc(@RequestBody JsonRpcRequest request) {
        return ResponseEntity.ok(JsonRpcResponse.success(Map.of(
            "tools", protocolService.listTools()
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
            if (!protocolService.hasTool(toolName)) {
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

            Map<String, Object> result = protocolService.callTool(toolName, arguments, tenantId, orgId, orgRole);
            return ResponseEntity.ok(JsonRpcResponse.success(result, request.id()));

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
        return ResponseEntity.ok(Map.of(
            "resources", protocolService.listResources()
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

            String content = protocolService.getResourceContent(uri);
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
                    "mimeType", protocolService.resourceMimeType(uri),
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
}

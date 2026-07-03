package com.apimarketplace.orchestrator.services.mcp;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.registry.ToolSchemaGenerator;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared MCP server logic used by both MCP surfaces:
 * the REST-shaped endpoints under /api/mcp ({@code McpServerController}) and the
 * standard Streamable HTTP transport at /mcp ({@code McpStreamableHttpController}).
 * Both surfaces expose the same {@link AgentToolRegistry} tools and the same
 * schema/documentation resources; only the wire framing differs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpProtocolService {

    private final AgentToolRegistry registry;
    private final ToolsRegistrationService registrationService;
    private final ObjectMapper objectMapper;

    public boolean hasTool(String toolName) {
        return registry.hasTool(toolName);
    }

    /**
     * All registered tools in MCP {@code tools/list} format
     * ({@code name} / {@code description} / {@code inputSchema}).
     */
    public List<Map<String, Object>> listTools() {
        return registry.getToolsInMcpFormat();
    }

    /**
     * Executes a tool and wraps the outcome as an MCP tool result
     * ({@code content} blocks + {@code isError}). Execution failures are reported
     * in-band ({@code isError: true}), matching the MCP convention that tool
     * errors are results, not protocol errors.
     */
    public Map<String, Object> callTool(String toolName,
                                        Map<String, Object> arguments,
                                        String tenantId,
                                        String orgId,
                                        String orgRole) throws JsonProcessingException {
        ToolsProvider.ToolExecutionContext context = new ToolsProvider.ToolExecutionContext(
                tenantId,
                Map.of(),
                Map.of(),
                Set.of(),  // No approved services for MCP calls
                null,      // viewingWorkflowId
                null,      // viewingWorkflowName
                orgId,
                orgRole
        );

        ToolsProvider.ToolExecutionResult result = registrationService.executeTool(
                toolName, arguments, context
        );

        if (result.success()) {
            String textContent;
            if (result.data() instanceof Map || result.data() instanceof List) {
                textContent = objectMapper.writeValueAsString(result.data());
            } else {
                textContent = result.data() != null ? result.data().toString() : "";
            }
            return Map.of(
                    "content", List.of(Map.of(
                            "type", "text",
                            "text", textContent
                    )),
                    "isError", false
            );
        }
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", result.error() != null ? result.error() : "Tool execution failed"
                )),
                "isError", true
        );
    }

    /**
     * Static resource catalog: schemas and the generated tools documentation.
     */
    public List<Map<String, Object>> listResources() {
        return List.of(
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
    }

    public String resourceMimeType(String uri) {
        return uri.startsWith("schema://") ? "application/json" : "text/markdown";
    }

    /**
     * Resource content by URI, or {@code null} when the URI is unknown.
     */
    public String getResourceContent(String uri) {
        try {
            return switch (uri) {
                case "schema://workflow" -> objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ToolSchemaGenerator.getWorkflowPlanSchema());
                case "schema://agent" -> objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ToolSchemaGenerator.getAgentConfigSchema());
                case "schema://interface" -> objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ToolSchemaGenerator.getInterfaceSchema());
                case "schema://datasource" -> objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ToolSchemaGenerator.getDataSourceSchema());
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

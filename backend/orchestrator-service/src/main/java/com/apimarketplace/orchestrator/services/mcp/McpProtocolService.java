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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
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

    /**
     * Cloud tool aggregation (agent/datasource/interface/catalog siblings). Present
     * only in microservice mode; absent (empty) in the CE monolith, where every
     * provider is already in the local registry. See {@code RemoteToolAggregationConfig}.
     */
    private final ObjectProvider<AggregatedToolCatalog> aggregatedCatalogProvider;
    private final ObjectProvider<RemoteToolGateway> remoteToolGatewayProvider;

    public boolean hasTool(String toolName) {
        if (registry.hasTool(toolName)) {
            return true;
        }
        AggregatedToolCatalog aggregated = aggregatedCatalogProvider.getIfAvailable();
        return aggregated != null && aggregated.knows(toolName);
    }

    /**
     * All available tools in MCP {@code tools/list} format
     * ({@code name} / {@code description} / {@code inputSchema}): the local registry
     * UNION the aggregated sibling-service tools (cloud only). Local wins on a name
     * clash; the merged list is name-sorted for a stable ordering.
     */
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> local = registry.getToolsInMcpFormat();
        AggregatedToolCatalog aggregated = aggregatedCatalogProvider.getIfAvailable();
        if (aggregated == null) {
            return local; // monolith: every provider is already local
        }
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> tool : local) {
            byName.putIfAbsent(String.valueOf(tool.get("name")), tool);
        }
        for (Map<String, Object> tool : aggregated.mcpTools()) {
            byName.putIfAbsent(String.valueOf(tool.get("name")), tool);
        }
        return byName.values().stream()
                .sorted(Comparator.comparing(t -> String.valueOf(t.get("name"))))
                .toList();
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
        ToolsProvider.ToolExecutionResult result;
        if (registry.hasTool(toolName)) {
            // Local tool: execute in-process (fast path, also the only path in the monolith).
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
            result = registrationService.executeTool(toolName, arguments, context);
        } else {
            // Aggregated sibling tool (cloud only): route to the owning service.
            RemoteToolGateway gateway = remoteToolGatewayProvider.getIfAvailable();
            if (gateway == null) {
                result = ToolsProvider.ToolExecutionResult.failure(
                        com.apimarketplace.agent.tools.ToolErrorCode.TOOL_NOT_FOUND,
                        "Unknown tool: " + toolName);
            } else {
                result = gateway.execute(toolName, arguments, tenantId, orgId, orgRole);
            }
        }

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

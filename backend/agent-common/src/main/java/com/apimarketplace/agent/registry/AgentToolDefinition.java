package com.apimarketplace.agent.registry;

import com.apimarketplace.agent.domain.ToolParameter;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Extended tool definition for agent tools.
 * Includes full schema, documentation, and examples.
 *
 * This is the canonical definition used by the Agent Tools Registry.
 */
@Builder
public record AgentToolDefinition(
    /**
     * Unique tool name (e.g., "workflow_create", "datasource_list")
     */
    String name,

    /**
     * Human-readable description of what the tool does
     */
    String description,

    /**
     * Category this tool belongs to
     */
    ToolCategory category,

    /**
     * List of parameters the tool accepts
     */
    List<ToolParameter> parameters,

    /**
     * Names of required parameters
     */
    List<String> requiredParameters,

    /**
     * Full JSON Schema for input validation
     */
    Map<String, Object> inputSchema,

    /**
     * JSON Schema for expected output
     */
    Map<String, Object> outputSchema,

    /**
     * Example usages (JSON strings)
     */
    List<String> examples,

    /**
     * Detailed help text (can include markdown)
     */
    String helpText,

    /**
     * Whether this tool requires authentication
     */
    boolean requiresAuth,

    /**
     * Tags for additional categorization
     */
    List<String> tags,

    /**
     * Timeout in milliseconds for this tool execution.
     * If null, uses the default timeout from configuration.
     */
    Long timeoutMs
) {

    /**
     * Check if a parameter is required.
     */
    public boolean isParameterRequired(String paramName) {
        return requiredParameters != null && requiredParameters.contains(paramName);
    }

    /**
     * Get parameter by name.
     */
    public ToolParameter getParameter(String paramName) {
        if (parameters == null) return null;
        return parameters.stream()
            .filter(p -> p.name().equals(paramName))
            .findFirst()
            .orElse(null);
    }

    /**
     * Create a compact summary for listing.
     * Includes full parameter schemas for LLM tool calling.
     */
    public Map<String, Object> toSummary() {
        // Build parameters list with full details
        List<Map<String, Object>> paramList = parameters != null
            ? parameters.stream()
                .map(p -> {
                    Map<String, Object> paramMap = new java.util.HashMap<>();
                    paramMap.put("name", p.name());
                    paramMap.put("type", p.type());
                    paramMap.put("description", p.description());
                    paramMap.put("required", p.required());
                    if (p.enumValues() != null && !p.enumValues().isEmpty()) {
                        paramMap.put("enum", p.enumValues());
                    }
                    return paramMap;
                })
                .toList()
            : List.of();

        Map<String, Object> summary = new java.util.HashMap<>();
        summary.put("name", name);
        summary.put("description", description);
        summary.put("category", category.getSlug());
        summary.put("requiresAuth", requiresAuth);
        summary.put("parameterCount", parameters != null ? parameters.size() : 0);
        summary.put("requiredParams", requiredParameters != null ? requiredParameters : List.of());
        summary.put("parameters", paramList);
        if (timeoutMs != null) {
            summary.put("timeoutMs", timeoutMs);
        }
        return summary;
    }

    /**
     * Convert to MCP tool format.
     */
    public Map<String, Object> toMcpFormat() {
        return Map.of(
            "name", name,
            "description", description,
            "inputSchema", inputSchema != null ? inputSchema : Map.of(
                "type", "object",
                "properties", Map.of()
            )
        );
    }
}

package com.apimarketplace.agent.registry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry for all agent tools.
 * Provides discovery, documentation, and schema access for tools.
 */
public interface AgentToolRegistry {

    /**
     * Register a tool in the registry.
     *
     * @param tool The tool definition to register
     */
    void register(AgentToolDefinition tool);

    /**
     * Register multiple tools at once.
     *
     * @param tools List of tool definitions to register
     */
    void registerAll(List<AgentToolDefinition> tools);

    /**
     * Get all registered tools.
     *
     * @return List of all tool definitions
     */
    List<AgentToolDefinition> getAllTools();

    /**
     * Get tools by category.
     *
     * @param category The category to filter by
     * @return List of tools in the category
     */
    List<AgentToolDefinition> getToolsByCategory(ToolCategory category);

    /**
     * Get a specific tool by name.
     *
     * @param name The tool name
     * @return Optional containing the tool if found
     */
    Optional<AgentToolDefinition> getToolByName(String name);

    /**
     * Search tools by query (matches name, description, tags).
     *
     * @param query Search query
     * @param maxResults Maximum results to return
     * @return List of matching tools
     */
    List<AgentToolDefinition> searchTools(String query, int maxResults);

    /**
     * Get the JSON Schema for a tool's input.
     *
     * @param toolName The tool name
     * @return JSON Schema as a map, or empty map if not found
     */
    Map<String, Object> getToolInputSchema(String toolName);

    /**
     * Get the JSON Schema for a tool's output.
     *
     * @param toolName The tool name
     * @return JSON Schema as a map, or empty map if not found
     */
    Map<String, Object> getToolOutputSchema(String toolName);

    /**
     * Get detailed documentation for a tool.
     *
     * @param toolName The tool name
     * @return Documentation object or null if not found
     */
    ToolDocumentation getToolDocumentation(String toolName);

    /**
     * Get all categories with their tool counts.
     *
     * @return Map of category slug to tool count
     */
    Map<String, Integer> getCategoryCounts();

    /**
     * Get all tools in MCP format.
     *
     * @return List of tools in MCP-compatible format
     */
    List<Map<String, Object>> getToolsInMcpFormat();

    /**
     * Check if a tool exists.
     *
     * @param toolName The tool name
     * @return true if the tool is registered
     */
    boolean hasTool(String toolName);

    /**
     * Get total number of registered tools.
     *
     * @return Tool count
     */
    int getToolCount();

    /**
     * Tool documentation record.
     */
    record ToolDocumentation(
        String name,
        String description,
        String helpText,
        List<String> examples,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        ToolCategory category,
        List<String> tags
    ) {}
}

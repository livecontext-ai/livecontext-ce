package com.apimarketplace.agent.tools.common;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Interface for tool modules that decompose large tool providers into focused, maintainable units.
 *
 * Each module handles a specific category of tools (CRUD, Query, Schema, Search, Execute, etc.)
 * and is responsible for:
 * - Providing tool definitions
 * - Executing tools it owns
 *
 * This interface enables splitting monolithic ToolsProvider classes (>500 LOC)
 * into smaller, testable modules (<300 LOC each).
 */
public interface ToolModule {

    /**
     * Get the tool definitions provided by this module.
     *
     * @return List of tool definitions (can be empty if definitions are centralized)
     */
    List<AgentToolDefinition> getToolDefinitions();

    /**
     * Check if this module can handle a specific tool/action.
     *
     * @param toolName The tool name or action to check
     * @return true if this module handles the tool
     */
    boolean canHandle(String toolName);

    /**
     * Execute a tool.
     *
     * @param toolName   The tool name
     * @param parameters Tool parameters
     * @param tenantId   The tenant ID
     * @param context    Execution context
     * @return Optional result (empty if this module doesn't handle the tool)
     */
    Optional<ToolExecutionResult> execute(String toolName, Map<String, Object> parameters,
                                          String tenantId, ToolExecutionContext context);
}

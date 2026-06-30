package com.apimarketplace.agent.tool;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;

import java.util.Map;

/**
 * Interface for executing tools.
 * Follows Interface Segregation Principle.
 */
public interface ToolExecutionService {

    /**
     * Execute a tool call.
     *
     * @param toolCall       The tool call request from the LLM
     * @param toolDefinition The definition of the tool to execute
     * @param tenantId       The tenant ID for authorization
     * @param credentials    Any credentials needed for tool execution
     * @return The result of the tool execution
     */
    ToolResult executeTool(ToolCall toolCall, ToolDefinition toolDefinition,
                           String tenantId, Map<String, Object> credentials);

    /**
     * Check if a tool is available for execution.
     *
     * @param toolDefinition The tool definition to check
     * @param tenantId       The tenant ID for authorization
     * @return true if the tool is available
     */
    boolean isToolAvailable(ToolDefinition toolDefinition, String tenantId);
}

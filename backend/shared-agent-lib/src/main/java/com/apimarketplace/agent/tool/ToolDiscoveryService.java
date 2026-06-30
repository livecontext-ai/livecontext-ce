package com.apimarketplace.agent.tool;

import com.apimarketplace.agent.domain.ToolDefinition;

import java.util.List;

/**
 * Interface for discovering relevant tools based on context.
 * Implementations may use RRF, semantic search, or other methods.
 */
public interface ToolDiscoveryService {

    /**
     * Find tools relevant to the given prompt.
     *
     * @param prompt   The user's prompt
     * @param maxTools Maximum number of tools to return
     * @return List of relevant tool definitions
     */
    List<ToolDefinition> findRelevantTools(String prompt, int maxTools);

    /**
     * Find tools relevant to the given prompt with a minimum score threshold.
     *
     * @param prompt   The user's prompt
     * @param maxTools Maximum number of tools to return
     * @param minScore Minimum relevance score (0.0-1.0)
     * @return List of relevant tool definitions
     */
    default List<ToolDefinition> findRelevantTools(String prompt, int maxTools, double minScore) {
        // Default implementation ignores minScore - override for actual filtering
        return findRelevantTools(prompt, maxTools);
    }

    /**
     * Get all available tools for a tenant.
     *
     * @param tenantId The tenant ID
     * @return List of all available tool definitions
     */
    List<ToolDefinition> getAllTools(String tenantId);
}

package com.apimarketplace.agent.domain;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Definition of a tool that can be called by the LLM.
 * Maps to the MCP tool format.
 */
@Builder
public record ToolDefinition(
    /**
     * Unique identifier for the tool (usually toolId from catalog)
     */
    String id,

    /**
     * Name of the tool (function name for LLM)
     */
    String name,

    /**
     * Description of what the tool does
     */
    String description,

    /**
     * API slug this tool belongs to (for execution)
     */
    String apiSlug,

    /**
     * Tool slug for execution
     */
    String toolSlug,

    /**
     * Parameters definition (JSON Schema format)
     */
    List<ToolParameter> parameters,

    /**
     * Required parameter names
     */
    List<String> requiredParameters,

    /**
     * RRF relevance score (from tool discovery)
     */
    Double relevanceScore,

    /**
     * Additional metadata
     */
    Map<String, Object> metadata,

    /**
     * Timeout in milliseconds for this tool execution.
     * If null, uses the default timeout from configuration.
     */
    Long timeoutMs
) {
    /**
     * Get effective timeout with default fallback
     */
    public long getEffectiveTimeoutMs(long defaultTimeoutMs) {
        return timeoutMs != null && timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;
    }
}

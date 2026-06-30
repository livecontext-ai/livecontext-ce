package com.apimarketplace.agent.dto.cli;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for session start - returns available tools and system prompt for Claude Code.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CliSessionResponse(
    String sessionId,
    String systemPrompt,
    List<ToolInfo> availableTools,
    List<String> toolNames
) {
    /**
     * Tool info with full JSON Schema parameters for MCP tool registration.
     */
    public record ToolInfo(
        String name,
        String description,
        Map<String, Object> inputSchema
    ) {}
}

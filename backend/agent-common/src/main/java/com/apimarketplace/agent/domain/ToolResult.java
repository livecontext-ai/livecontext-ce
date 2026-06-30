package com.apimarketplace.agent.domain;

import lombok.Builder;

import java.util.Map;

/**
 * Result of executing a tool.
 */
@Builder
public record ToolResult(
    /**
     * The tool call that was executed
     */
    ToolCall toolCall,

    /**
     * Whether the execution was successful
     */
    boolean success,

    /**
     * Result content (can be JSON string or plain text)
     */
    String content,

    /**
     * Error message if execution failed
     */
    String error,

    /**
     * Execution duration in milliseconds
     */
    Long durationMs,

    /**
     * Additional metadata
     */
    Map<String, Object> metadata
) {
    /**
     * Create a successful result
     */
    public static ToolResult success(ToolCall toolCall, String content) {
        return ToolResult.builder()
            .toolCall(toolCall)
            .success(true)
            .content(content)
            .build();
    }

    /**
     * Create a failed result
     */
    public static ToolResult failure(ToolCall toolCall, String error) {
        return ToolResult.builder()
            .toolCall(toolCall)
            .success(false)
            .error(error)
            .build();
    }
}

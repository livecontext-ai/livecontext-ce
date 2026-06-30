package com.apimarketplace.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Response from LLM completion.
 * Unified structure for all providers.
 */
@Builder
public record CompletionResponse(
    /**
     * The generated text content
     */
    String content,

    /**
     * Tool calls requested by the LLM
     */
    List<ToolCall> toolCalls,

    /**
     * Reason for completion (stop, tool_calls, length, etc.)
     */
    String finishReason,

    /**
     * Token usage information
     */
    UsageInfo usage,

    /**
     * The model that was used
     */
    String model,

    /**
     * Additional provider-specific metadata
     */
    Map<String, Object> metadata
) {
    /**
     * Check if the response contains tool calls
     */
    @JsonIgnore
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * Check if the response is complete (no more tool calls needed)
     */
    @JsonIgnore
    public boolean isComplete() {
        return !hasToolCalls() && "stop".equals(finishReason);
    }

    /**
     * Create a simple text response
     */
    public static CompletionResponse text(String content) {
        return CompletionResponse.builder()
            .content(content)
            .finishReason("stop")
            .build();
    }

    /**
     * Create an error response
     */
    public static CompletionResponse error(String errorMessage) {
        return CompletionResponse.builder()
            .content(errorMessage)
            .finishReason("error")
            .metadata(Map.of("error", true))
            .build();
    }
}

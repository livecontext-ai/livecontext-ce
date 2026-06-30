package com.apimarketplace.agent.cloud;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * NDJSON event exchanged between Cloud and CE for streaming LLM completions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloudLlmStreamEvent(
        Type type,
        String content,
        String thinking,
        ToolCall toolCall,
        CompletionResponse response,
        String error
) {
    public enum Type {
        CONTENT_CHUNK,
        THINKING_CHUNK,
        TOOL_CALL,
        COMPLETED,
        ERROR
    }

    public static CloudLlmStreamEvent content(String content) {
        return new CloudLlmStreamEvent(Type.CONTENT_CHUNK, content, null, null, null, null);
    }

    public static CloudLlmStreamEvent thinking(String thinking) {
        return new CloudLlmStreamEvent(Type.THINKING_CHUNK, null, thinking, null, null, null);
    }

    public static CloudLlmStreamEvent toolCall(ToolCall toolCall) {
        return new CloudLlmStreamEvent(Type.TOOL_CALL, null, null, toolCall, null, null);
    }

    public static CloudLlmStreamEvent completed(CompletionResponse response) {
        return new CloudLlmStreamEvent(Type.COMPLETED, null, null, null, response, null);
    }

    public static CloudLlmStreamEvent error(String error) {
        return new CloudLlmStreamEvent(Type.ERROR, null, null, null, null, error);
    }
}

package com.apimarketplace.agent.streaming;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;

/**
 * Sealed interface for streaming events from LLM providers.
 * Represents all possible events during a reactive streaming session.
 */
public sealed interface StreamingEvent permits
        StreamingEvent.ContentChunk,
        StreamingEvent.ToolCallEvent,
        StreamingEvent.CompletedEvent,
        StreamingEvent.ErrorEvent {

    /**
     * Content chunk event - contains a piece of the LLM response.
     */
    record ContentChunk(String content) implements StreamingEvent {
        public static ContentChunk of(String content) {
            return new ContentChunk(content);
        }
    }

    /**
     * Tool call event - the LLM is requesting a tool execution.
     */
    record ToolCallEvent(ToolCall toolCall) implements StreamingEvent {
        public static ToolCallEvent of(ToolCall toolCall) {
            return new ToolCallEvent(toolCall);
        }
    }

    /**
     * Completed event - streaming has finished successfully.
     */
    record CompletedEvent(CompletionResponse response) implements StreamingEvent {
        public static CompletedEvent of(CompletionResponse response) {
            return new CompletedEvent(response);
        }
    }

    /**
     * Error event - an error occurred during streaming.
     */
    record ErrorEvent(String message, Throwable cause) implements StreamingEvent {
        public static ErrorEvent of(String message) {
            return new ErrorEvent(message, null);
        }

        public static ErrorEvent of(String message, Throwable cause) {
            return new ErrorEvent(message, cause);
        }
    }

    // Factory methods for convenience
    static ContentChunk content(String content) {
        return ContentChunk.of(content);
    }

    static ToolCallEvent toolCall(ToolCall toolCall) {
        return ToolCallEvent.of(toolCall);
    }

    static CompletedEvent completed(CompletionResponse response) {
        return CompletedEvent.of(response);
    }

    static ErrorEvent error(String message) {
        return ErrorEvent.of(message);
    }

    static ErrorEvent error(String message, Throwable cause) {
        return ErrorEvent.of(message, cause);
    }
}

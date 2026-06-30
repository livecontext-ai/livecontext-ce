package com.apimarketplace.agent.domain;

import lombok.Builder;
import java.util.List;

/**
 * A message in the conversation history.
 * Immutable record used across all LLM providers.
 */
@Builder
public record Message(
    /**
     * Role of the message sender (system, user, assistant, tool)
     */
    Role role,

    /**
     * Content of the message
     */
    String content,

    /**
     * Tool call ID (for tool responses)
     */
    String toolCallId,

    /**
     * Tool name (for tool responses)
     */
    String toolName,

    /**
     * Tool calls made by assistant (for OpenAI compatibility)
     */
    List<ToolCall> toolCalls,

    /**
     * File attachments for multimodal messages (images, PDFs, text files)
     */
    List<MessageAttachment> attachments
) {
    /**
     * Message roles
     */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    /**
     * Create a system message
     */
    public static Message system(String content) {
        return Message.builder()
            .role(Role.SYSTEM)
            .content(content)
            .build();
    }

    /**
     * Create a user message
     */
    public static Message user(String content) {
        return Message.builder()
            .role(Role.USER)
            .content(content)
            .build();
    }

    /**
     * Create an assistant message
     */
    public static Message assistant(String content) {
        return Message.builder()
            .role(Role.ASSISTANT)
            .content(content)
            .build();
    }
    
    /**
     * Create an assistant message with tool calls (for OpenAI format)
     */
    public static Message assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return Message.builder()
            .role(Role.ASSISTANT)
            .content(content)
            .toolCalls(toolCalls)
            .build();
    }

    /**
     * Create a tool response message
     */
    public static Message toolResult(String toolCallId, String toolName, String result) {
        return Message.builder()
            .role(Role.TOOL)
            .content(result)
            .toolCallId(toolCallId)
            .toolName(toolName)
            .build();
    }

    /**
     * Create a user message with file attachments (multimodal)
     */
    public static Message userWithAttachments(String content, List<MessageAttachment> attachments) {
        return Message.builder()
            .role(Role.USER)
            .content(content)
            .attachments(attachments)
            .build();
    }

    /**
     * Check if this message has attachments
     */
    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }
}


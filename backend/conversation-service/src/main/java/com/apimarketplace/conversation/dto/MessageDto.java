package com.apimarketplace.conversation.dto;

import com.apimarketplace.conversation.entity.Message;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for Message operations.
 *
 * Supports all message roles: USER, ASSISTANT, SYSTEM, TOOL
 */
public class MessageDto {

    private String id;

    private String conversationId;

    @NotNull(message = "Message role is required")
    private String role;

    /**
     * Message content. Nullable for ASSISTANT messages with only tool_calls.
     */
    private String content;

    /**
     * JSON string of tool calls (for ASSISTANT messages).
     */
    private String toolCalls;

    /**
     * For TOOL role: the ID of the tool call this result responds to.
     */
    private String toolCallId;

    /**
     * For TOOL role: the name of the tool that was executed.
     */
    private String toolName;

    private String model;

    /**
     * The ID of the agent that generated this message (for ASSISTANT messages).
     */
    private String agentId;

    private String executionId;

    /**
     * User feedback: 1 (thumbs up), -1 (thumbs down), null (no feedback).
     */
    private Integer feedback;

    private String timestamp;

    private LocalDateTime createdAt;

    /**
     * List of attachments associated with this message.
     */
    private List<MessageAttachmentDto> attachments;

    // Constructors
    public MessageDto() {}

    public MessageDto(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public MessageDto(String role, String content, String toolCalls, String model, String timestamp) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.model = model;
        this.timestamp = timestamp;
    }

    /**
     * Create a TOOL result message DTO.
     */
    public static MessageDto toolResult(String toolCallId, String toolName, String resultContent) {
        MessageDto dto = new MessageDto();
        dto.setRole("tool");
        dto.setToolCallId(toolCallId);
        dto.setToolName(toolName);
        dto.setContent(resultContent);
        return dto;
    }

    /**
     * Create an ASSISTANT message with tool calls DTO.
     */
    public static MessageDto assistantWithToolCalls(String toolCallsJson, String model) {
        MessageDto dto = new MessageDto();
        dto.setRole("assistant");
        dto.setToolCalls(toolCallsJson);
        dto.setModel(model);
        return dto;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Message.MessageRole getRoleEnum() {
        if (role == null) return null;
        return Message.MessageRole.fromString(role);
    }

    public void setRoleEnum(Message.MessageRole role) {
        this.role = role != null ? role.getValue() : null;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(String toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public Integer getFeedback() {
        return feedback;
    }

    public void setFeedback(Integer feedback) {
        this.feedback = feedback;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<MessageAttachmentDto> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<MessageAttachmentDto> attachments) {
        this.attachments = attachments;
    }

    /**
     * Check if this is a tool result message.
     */
    public boolean isToolResult() {
        return "tool".equalsIgnoreCase(role) && toolCallId != null;
    }

    /**
     * Check if this message has tool calls.
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isBlank() && !toolCalls.equals("[]");
    }
}

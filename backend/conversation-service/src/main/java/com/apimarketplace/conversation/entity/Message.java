package com.apimarketplace.conversation.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entity representing a message within a conversation.
 *
 * Supports four roles:
 * - USER: User input messages
 * - ASSISTANT: AI assistant responses (may contain tool_calls JSON)
 * - SYSTEM: System messages (context injection, instructions)
 * - TOOL: Tool execution results (linked via tool_call_id)
 *
 * Message flow for tool calls:
 * 1. ASSISTANT message with tool_calls JSON (no content, or partial content)
 * 2. TOOL message with result content (linked by tool_call_id)
 * 3. ASSISTANT message with final response
 */
@Entity
@Table(name = "messages", schema = "conversation")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Convert(converter = com.apimarketplace.conversation.entity.converter.MessageRoleConverter.class)
    @Column(name = "role", nullable = false)
    private MessageRole role;

    /**
     * Message content. Nullable for ASSISTANT messages that only contain tool_calls.
     */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /**
     * JSON string of tool calls requested by the assistant.
     * Format: [{"id": "call_123", "type": "function", "function": {"name": "...", "arguments": "..."}}]
     */
    @Column(name = "tool_calls", columnDefinition = "TEXT")
    private String toolCalls;

    /**
     * For TOOL role messages: the ID of the tool call this result responds to.
     * Links to a tool call in a previous ASSISTANT message's toolCalls JSON.
     */
    @Column(name = "tool_call_id", length = 100)
    private String toolCallId;

    /**
     * For TOOL role messages: the name of the tool that was executed.
     * Useful for querying and display without parsing toolCalls JSON.
     */
    @Column(name = "tool_name", length = 100)
    private String toolName;

    @Column(name = "model")
    private String model;

    /**
     * The ID of the agent that generated this message (for ASSISTANT messages).
     * Allows tracking which agent was used for each response.
     */
    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "execution_id")
    private String executionId;

    /**
     * User feedback on this message: 1 (thumbs up), -1 (thumbs down), null (no feedback).
     */
    @Column(name = "feedback")
    private Short feedback;

    @Column(name = "timestamp")
    private String timestamp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Constructors
    public Message() {}

    public Message(MessageRole role, String content) {
        this.role = role;
        this.content = content;
    }

    public Message(MessageRole role, String content, String toolCalls, String model, String timestamp) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.model = model;
        this.timestamp = timestamp;
    }

    /**
     * Create a TOOL result message.
     */
    public static Message toolResult(String toolCallId, String toolName, String resultContent) {
        Message message = new Message();
        message.setRole(MessageRole.TOOL);
        message.setToolCallId(toolCallId);
        message.setToolName(toolName);
        message.setContent(resultContent);
        return message;
    }

    /**
     * Create an ASSISTANT message with tool calls (no text content).
     */
    public static Message assistantWithToolCalls(String toolCallsJson, String model) {
        Message message = new Message();
        message.setRole(MessageRole.ASSISTANT);
        message.setToolCalls(toolCallsJson);
        message.setModel(model);
        return message;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }

    public MessageRole getRole() {
        return role;
    }

    public void setRole(MessageRole role) {
        this.role = role;
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

    public Short getFeedback() {
        return feedback;
    }

    public void setFeedback(Short feedback) {
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

    /**
     * Check if this message has tool calls.
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isBlank() && !toolCalls.equals("[]");
    }

    /**
     * Check if this is a tool result message.
     */
    public boolean isToolResult() {
        return role == MessageRole.TOOL && toolCallId != null;
    }

    /**
     * Enum for message roles.
     * Matches Claude/OpenAI API message roles.
     */
    public enum MessageRole {
        USER("user"),
        ASSISTANT("assistant"),
        SYSTEM("system"),
        TOOL("tool");

        private final String value;

        MessageRole(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static MessageRole fromString(String value) {
            if (value == null) return null;
            for (MessageRole role : values()) {
                if (role.value.equalsIgnoreCase(value)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("Unknown message role: " + value);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}

package com.apimarketplace.conversation.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Entity for storing tool execution results.
 * Full content is stored and fetched on demand when user expands a tool result.
 * All tool-specific metadata (visualization, iconSlug, etc.) is stored in the metadata JSONB column.
 */
@Entity
@Table(name = "tool_results", schema = "conversation")
public class ToolResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "tool_call_id")
    private String toolCallId;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "content_full", columnDefinition = "TEXT")
    private String contentFull;

    /**
     * First 3000 chars of content_full for lightweight history loading.
     * Includes truncation hint with exact get_tool_result call when truncated.
     */
    @Column(name = "content_preview", columnDefinition = "TEXT")
    private String contentPreview;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // Generic metadata for all tool-specific data (visualization, iconSlug, displayToolName, etc.)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "execution_id")
    private String executionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ToolResult() {}

    public ToolResult(String conversationId, String tenantId, String toolName, String toolCallId,
                      boolean success, Long durationMs, String contentFull, String errorMessage) {
        this.conversationId = conversationId;
        this.tenantId = tenantId;
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.success = success;
        this.durationMs = durationMs;
        this.contentFull = contentFull;
        this.errorMessage = errorMessage;
    }

    /**
     * Get content for history building: returns preview if available, falls back to content_full.
     * This is the method ConversationHistoryConverter should use.
     */
    public String getContentForHistory() {
        return contentPreview != null ? contentPreview : contentFull;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getContentFull() {
        return contentFull;
    }

    public void setContentFull(String contentFull) {
        this.contentFull = contentFull;
    }

    public String getContentPreview() {
        return contentPreview;
    }

    public void setContentPreview(String contentPreview) {
        this.contentPreview = contentPreview;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }
}

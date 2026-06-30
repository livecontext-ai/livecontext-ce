package com.apimarketplace.agent.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for the agent_execution_tool_calls table.
 * Individual tool invocations with full input/output and repetition tracking.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "agent_execution_tool_calls")
public class AgentExecutionToolCallEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** PR20 strict-isolation: mirrors parent agent_executions.organization_id. NULL = personal scope. */
    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "iteration_number", nullable = false)
    private int iterationNumber;

    @Column(name = "tool_call_id", length = 255)
    private String toolCallId;

    @Column(name = "tool_name", nullable = false, length = 255)
    private String toolName;

    @Column(name = "parallel_index")
    private Integer parallelIndex;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments", columnDefinition = "jsonb")
    private Map<String, Object> arguments;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Column(name = "content_storage_id")
    private UUID contentStorageId;

    @Column(name = "content_length")
    private Integer contentLength;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "estimated_input_tokens")
    private Integer estimatedInputTokens;

    @Column(name = "estimated_output_tokens")
    private Integer estimatedOutputTokens;

    @Column(name = "is_repeat", nullable = false)
    private boolean isRepeat = false;

    @Column(name = "consecutive_count", nullable = false)
    private int consecutiveCount = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AgentExecutionToolCallEntity() {
    }

    @PrePersist
    private void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public void setExecutionId(UUID executionId) {
        this.executionId = executionId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public int getIterationNumber() {
        return iterationNumber;
    }

    public void setIterationNumber(int iterationNumber) {
        this.iterationNumber = iterationNumber;
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

    public Integer getParallelIndex() {
        return parallelIndex;
    }

    public void setParallelIndex(Integer parallelIndex) {
        this.parallelIndex = parallelIndex;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public UUID getContentStorageId() {
        return contentStorageId;
    }

    public void setContentStorageId(UUID contentStorageId) {
        this.contentStorageId = contentStorageId;
    }

    public Integer getContentLength() {
        return contentLength;
    }

    public void setContentLength(Integer contentLength) {
        this.contentLength = contentLength;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Integer getEstimatedInputTokens() {
        return estimatedInputTokens;
    }

    public void setEstimatedInputTokens(Integer estimatedInputTokens) {
        this.estimatedInputTokens = estimatedInputTokens;
    }

    public Integer getEstimatedOutputTokens() {
        return estimatedOutputTokens;
    }

    public void setEstimatedOutputTokens(Integer estimatedOutputTokens) {
        this.estimatedOutputTokens = estimatedOutputTokens;
    }

    public boolean isRepeat() {
        return isRepeat;
    }

    public void setRepeat(boolean repeat) {
        isRepeat = repeat;
    }

    public int getConsecutiveCount() {
        return consecutiveCount;
    }

    public void setConsecutiveCount(int consecutiveCount) {
        this.consecutiveCount = consecutiveCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

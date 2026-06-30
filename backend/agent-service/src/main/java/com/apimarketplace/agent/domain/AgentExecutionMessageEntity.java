package com.apimarketplace.agent.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for the agent_execution_messages table.
 * Full conversation history - one row per message (system/user/assistant/tool).
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "agent_execution_messages")
public class AgentExecutionMessageEntity implements OrgScopedEntity {

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

    @Column(name = "iteration_number")
    private Integer iterationNumber;

    @Column(name = "role", nullable = false, length = 10)
    private String role;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Column(name = "content_storage_id")
    private UUID contentStorageId;

    @Column(name = "content_length")
    private Integer contentLength;

    @Column(name = "tool_call_id", length = 255)
    private String toolCallId;

    @Column(name = "tool_name", length = 255)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls_requested", columnDefinition = "jsonb")
    private List<Map<String, Object>> toolCallsRequested;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AgentExecutionMessageEntity() {
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

    public Integer getIterationNumber() {
        return iterationNumber;
    }

    public void setIterationNumber(Integer iterationNumber) {
        this.iterationNumber = iterationNumber;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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

    public List<Map<String, Object>> getToolCallsRequested() {
        return toolCallsRequested;
    }

    public void setToolCallsRequested(List<Map<String, Object>> toolCallsRequested) {
        this.toolCallsRequested = toolCallsRequested;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

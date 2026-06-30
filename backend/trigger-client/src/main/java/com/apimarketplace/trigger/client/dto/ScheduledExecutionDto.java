package com.apimarketplace.trigger.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for scheduled execution data transferred between services.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScheduledExecutionDto {

    private UUID id;
    private UUID workflowId;
    private UUID agentEntityId;
    private String schedulePrompt;
    private boolean withMemory;
    private String triggerId;
    private String tenantId;
    /** PR22 - workspace identity. NULL = personal scope. Daemon stamps this on the created workflow_run. */
    private String organizationId;
    private String name;
    private String workflowName;
    private String cronExpression;
    private String timezone;
    private Integer maxExecutions;
    private boolean enabled;
    private Instant nextExecutionAt;
    private Instant lastExecutionAt;
    private int executionCount;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;
    private String description;
    private boolean isActive = true;
    private String sourceNodeId;

    public ScheduledExecutionDto() {}

    // Getters and Setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }

    public UUID getAgentEntityId() { return agentEntityId; }
    public void setAgentEntityId(UUID agentEntityId) { this.agentEntityId = agentEntityId; }

    public String getSchedulePrompt() { return schedulePrompt; }
    public void setSchedulePrompt(String schedulePrompt) { this.schedulePrompt = schedulePrompt; }

    public boolean getWithMemory() { return withMemory; }
    public void setWithMemory(boolean withMemory) { this.withMemory = withMemory; }

    public String getTriggerId() { return triggerId; }
    public void setTriggerId(String triggerId) { this.triggerId = triggerId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public Integer getMaxExecutions() { return maxExecutions; }
    public void setMaxExecutions(Integer maxExecutions) { this.maxExecutions = maxExecutions; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getNextExecutionAt() { return nextExecutionAt; }
    public void setNextExecutionAt(Instant nextExecutionAt) { this.nextExecutionAt = nextExecutionAt; }

    public Instant getLastExecutionAt() { return lastExecutionAt; }
    public void setLastExecutionAt(Instant lastExecutionAt) { this.lastExecutionAt = lastExecutionAt; }

    public int getExecutionCount() { return executionCount; }
    public void setExecutionCount(int executionCount) { this.executionCount = executionCount; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean getIsActive() { return isActive; }
    public void setIsActive(boolean isActive) { this.isActive = isActive; }

    public String getSourceNodeId() { return sourceNodeId; }
    public void setSourceNodeId(String sourceNodeId) { this.sourceNodeId = sourceNodeId; }

    /**
     * Check if this schedule has reached its maximum executions.
     */
    public boolean hasReachedMaxExecutions() {
        return maxExecutions != null && executionCount >= maxExecutions;
    }
}

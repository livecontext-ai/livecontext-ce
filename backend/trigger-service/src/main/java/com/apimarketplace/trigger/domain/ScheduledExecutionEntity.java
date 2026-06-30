package com.apimarketplace.trigger.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Entity for scheduled workflow executions.
 * One row per schedule trigger - unique constraint on (workflow_id, trigger_id).
 * Supports multi-DAG: multiple schedule triggers per workflow.
 * Optimized for O(1) daemon queries via indexed next_execution_at.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "scheduled_executions", schema = "trigger")
public class ScheduledExecutionEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "agent_entity_id")
    private UUID agentEntityId;

    @Column(name = "schedule_prompt", columnDefinition = "TEXT")
    private String schedulePrompt;

    @Column(name = "with_memory", nullable = false)
    private boolean withMemory = false;

    @Column(name = "trigger_id")
    private String triggerId;  // e.g., "trigger:daily_9am"

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** PR22 - workspace identity. NULL = personal scope. Daemon propagates this to the created workflow_run. */
    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "name")
    private String name;

    @Column(name = "workflow_name")
    private String workflowName;

    // Schedule configuration
    @Column(name = "cron_expression", nullable = false, length = 100)
    private String cronExpression;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone = "UTC";

    @Column(name = "max_executions")
    private Integer maxExecutions;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    // Execution tracking
    @Column(name = "next_execution_at", nullable = false)
    private Instant nextExecutionAt;

    @Column(name = "last_execution_at")
    private Instant lastExecutionAt;

    @Column(name = "execution_count", nullable = false)
    private int executionCount = 0;

    // TTL and timestamps
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    // Standalone management columns
    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "source_node_id")
    private String sourceNodeId;

    // Round-7 lifecycle state (PR2). Replaces enabled/is_active in PR5.
    // The legacy boolean columns above remain in sync via TriggerLifecycleManager.
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private TriggerState state = TriggerState.ACTIVE;

    @Column(name = "last_disabled_reason", length = 40)
    private String lastDisabledReason;

    @Column(name = "last_disabled_at")
    private Instant lastDisabledAt;

    // Constructors
    public ScheduledExecutionEntity() {}

    public ScheduledExecutionEntity(UUID workflowId, String triggerId, String tenantId,
                                     String cronExpression, String timezone, Instant nextExecutionAt) {
        this.workflowId = workflowId;
        this.triggerId = triggerId;
        this.tenantId = tenantId;
        this.cronExpression = cronExpression;
        this.timezone = timezone != null ? timezone : "UTC";
        this.nextExecutionAt = nextExecutionAt;
    }

    // Business methods
    public void recordExecution(Instant nextExecution) {
        this.lastExecutionAt = Instant.now();
        this.nextExecutionAt = nextExecution;
        this.executionCount++;
        this.updatedAt = Instant.now();
    }

    public boolean hasReachedMaxExecutions() {
        return maxExecutions != null && executionCount >= maxExecutions;
    }

    public boolean isDue() {
        return enabled && !hasReachedMaxExecutions() &&
               nextExecutionAt != null && !nextExecutionAt.isAfter(Instant.now());
    }

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

    public TriggerState getState() { return state; }
    public void setState(TriggerState state) { this.state = state; }

    public String getLastDisabledReason() { return lastDisabledReason; }
    public void setLastDisabledReason(String lastDisabledReason) { this.lastDisabledReason = lastDisabledReason; }

    public Instant getLastDisabledAt() { return lastDisabledAt; }
    public void setLastDisabledAt(Instant lastDisabledAt) { this.lastDisabledAt = lastDisabledAt; }
}

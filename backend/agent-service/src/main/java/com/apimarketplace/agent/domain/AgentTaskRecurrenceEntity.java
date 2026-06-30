package com.apimarketplace.agent.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Cron-driven template that periodically emits new {@link AgentTaskEntity} rows.
 * A NULL {@code targetAgentId} makes the emitted tasks land in the tenant backlog.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "agent_task_recurrences", schema = "agent")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentTaskRecurrenceEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** PR27.2 - workspace tag (V217). NULL = personal scope. */
    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "created_by_agent_id")
    private UUID createdByAgentId;

    @Column(name = "created_by_user_id")
    private String createdByUserId;

    /** {@code null} = emit backlog tasks. */
    @Column(name = "target_agent_id")
    private UUID targetAgentId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    // Plain text column (NOT @Lob) - see SkillEntity.instructions note. Audit 2026-06-14.
    @Column(name = "instructions", nullable = false, columnDefinition = "TEXT")
    private String instructions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "task_context", columnDefinition = "jsonb")
    private Map<String, Object> taskContext;

    @Column(name = "priority", nullable = false, length = 10)
    private String priority = AgentTaskEntity.PRIORITY_NORMAL;

    @Column(name = "cron_expression", nullable = false, length = 100)
    private String cronExpression;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone = "UTC";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Column(name = "next_fire_at", nullable = false)
    private Instant nextFireAt;

    @Column(name = "fire_count", nullable = false)
    private long fireCount = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AgentTaskRecurrenceEntity() {}

    @PrePersist
    private void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public UUID getCreatedByAgentId() { return createdByAgentId; }
    public void setCreatedByAgentId(UUID createdByAgentId) { this.createdByAgentId = createdByAgentId; }

    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }

    public UUID getTargetAgentId() { return targetAgentId; }
    public void setTargetAgentId(UUID targetAgentId) { this.targetAgentId = targetAgentId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }

    public Map<String, Object> getTaskContext() { return taskContext; }
    public void setTaskContext(Map<String, Object> taskContext) { this.taskContext = taskContext; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getLastFiredAt() { return lastFiredAt; }
    public void setLastFiredAt(Instant lastFiredAt) { this.lastFiredAt = lastFiredAt; }

    public Instant getNextFireAt() { return nextFireAt; }
    public void setNextFireAt(Instant nextFireAt) { this.nextFireAt = nextFireAt; }

    public long getFireCount() { return fireCount; }
    public void setFireCount(long fireCount) { this.fireCount = fireCount; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

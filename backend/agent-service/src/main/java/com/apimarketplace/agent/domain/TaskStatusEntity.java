package com.apimarketplace.agent.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A configurable column on the agent task board. Each row is one status a user
 * can rename, reorder, recolour, hide, or cap with a WIP limit. The seven
 * historical statuses are materialised per board on first access as
 * {@link #isSystem() system} rows (renamable but not deletable); users may add
 * further custom statuses.
 * <p>
 * {@link #category} maps the status to a canonical lifecycle role so the agent
 * state machine keeps working across custom statuses. Scope mirrors
 * {@link AgentTaskEntity}: {@code (tenant_id, organization_id)} with a NULL
 * {@code organization_id} meaning the personal workspace.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "task_statuses", schema = "agent")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class TaskStatusEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** NULL = personal scope. */
    @Column(name = "organization_id")
    private String organizationId;

    /** Stable machine key referenced by {@code agent_tasks.status}. */
    @Column(name = "key", nullable = false, length = 40)
    private String key;

    @Column(name = "label", nullable = false, length = 60)
    private String label;

    /** Wire token of the {@link TaskStatusCategory}. */
    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "position", nullable = false)
    private int position = 0;

    @Column(name = "color", length = 30)
    private String color;

    /** {@code null} = no WIP limit. */
    @Column(name = "wip_limit")
    private Integer wipLimit;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem = false;

    @Column(name = "hidden", nullable = false)
    private boolean hidden = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public TaskStatusEntity() {}

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

    /** Resolved category, defaulting to PENDING when the stored token is unknown (defensive). */
    @Transient
    public TaskStatusCategory categoryEnum() {
        return TaskStatusCategory.fromWire(category).orElse(TaskStatusCategory.PENDING);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    @Override
    public String getOrganizationId() { return organizationId; }
    @Override
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public Integer getWipLimit() { return wipLimit; }
    public void setWipLimit(Integer wipLimit) { this.wipLimit = wipLimit; }

    public boolean isSystem() { return isSystem; }
    public void setSystem(boolean system) { isSystem = system; }

    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

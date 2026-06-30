package com.apimarketplace.trigger.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "standalone_chat_endpoints", schema = "trigger")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class StandaloneChatEndpointEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** PR22 - workspace identity. NULL = personal scope. Fire path propagates this to created workflow_run. */
    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "workflow_name")
    private String workflowName;

    @Column(name = "welcome_message", columnDefinition = "TEXT")
    private String welcomeMessage;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "provider", length = 100)
    private String provider;

    @Column(name = "memory_enabled", nullable = false)
    private Boolean memoryEnabled = true;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "source_node_id")
    private String sourceNodeId;

    @Column(name = "trigger_id")
    private String triggerId;

    // Round-7 lifecycle state (PR2). Replaces is_active in PR5.
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private TriggerState state = TriggerState.ACTIVE;

    @Column(name = "last_disabled_reason", length = 40)
    private String lastDisabledReason;

    @Column(name = "last_disabled_at")
    private Instant lastDisabledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public StandaloneChatEndpointEntity() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }
    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }
    public String getWelcomeMessage() { return welcomeMessage; }
    public void setWelcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public Boolean getMemoryEnabled() { return memoryEnabled; }
    public void setMemoryEnabled(Boolean memoryEnabled) { this.memoryEnabled = memoryEnabled; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getSourceNodeId() { return sourceNodeId; }
    public void setSourceNodeId(String sourceNodeId) { this.sourceNodeId = sourceNodeId; }

    public TriggerState getState() { return state; }
    public void setState(TriggerState state) { this.state = state; }

    public String getLastDisabledReason() { return lastDisabledReason; }
    public void setLastDisabledReason(String lastDisabledReason) { this.lastDisabledReason = lastDisabledReason; }

    public Instant getLastDisabledAt() { return lastDisabledAt; }
    public void setLastDisabledAt(Instant lastDisabledAt) { this.lastDisabledAt = lastDisabledAt; }
    public String getTriggerId() { return triggerId; }
    public void setTriggerId(String triggerId) { this.triggerId = triggerId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

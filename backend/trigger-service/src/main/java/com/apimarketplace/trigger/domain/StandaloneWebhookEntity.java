package com.apimarketplace.trigger.domain;

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
 * Entity for standalone_webhooks table.
 * Standalone webhooks are created independently and reusable across workflows.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "standalone_webhooks", schema = "trigger")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class StandaloneWebhookEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /**
     * PR22 - workspace identity. NULL = personal scope, non-null = team workspace.
     * Stamped on create from X-Organization-ID. The anonymous fire path
     * (POST /webhook/{token}) reads this column to propagate scope onto the
     * created workflow_run (no inbound org header on anonymous fires).
     */
    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod = "POST";

    @Column(name = "auth_type", nullable = false, length = 20)
    private String authType = "none";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auth_config", columnDefinition = "jsonb")
    private Map<String, String> authConfig;

    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "workflow_name")
    private String workflowName;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "source_node_id")
    private String sourceNodeId;

    // Round-7 lifecycle state (PR2). Replaces is_active in PR5.
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private TriggerState state = TriggerState.ACTIVE;

    @Column(name = "last_disabled_reason", length = 40)
    private String lastDisabledReason;

    @Column(name = "last_disabled_at")
    private Instant lastDisabledAt;

    public StandaloneWebhookEntity() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public Map<String, String> getAuthConfig() {
        return authConfig;
    }

    public void setAuthConfig(Map<String, String> authConfig) {
        this.authConfig = authConfig;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(UUID workflowId) {
        this.workflowId = workflowId;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }

    public TriggerState getState() { return state; }
    public void setState(TriggerState state) { this.state = state; }

    public String getLastDisabledReason() { return lastDisabledReason; }
    public void setLastDisabledReason(String lastDisabledReason) { this.lastDisabledReason = lastDisabledReason; }

    public Instant getLastDisabledAt() { return lastDisabledAt; }
    public void setLastDisabledAt(Instant lastDisabledAt) { this.lastDisabledAt = lastDisabledAt; }
}

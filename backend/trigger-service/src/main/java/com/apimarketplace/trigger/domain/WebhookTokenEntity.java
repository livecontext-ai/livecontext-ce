package com.apimarketplace.trigger.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity for webhook_tokens table.
 * Stores one webhook token per trigger for multi-DAG webhook support.
 * Each webhook trigger in a workflow gets its own unique URL/token.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "webhook_tokens", schema = "trigger")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WebhookTokenEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "trigger_id", nullable = false)
    private String triggerId;

    @Column(name = "token", nullable = false, unique = true)
    private String token;

    // PR22c R3 - workspace tag for cross-scope dispatch guard at
    // WebhookDispatchService.dispatch (pinned branch). NULL = personal scope
    // OR legacy pre-PR22c-deploy token. See V215 migration for details.
    @Column(name = "organization_id")
    private String organizationId;

    // V253 (2026-05-18) - owner tenant id, backfilled from parent workflow.
    // NULL only on legacy orphan tokens whose parent workflow was deleted.
    // Powers strict-isolation in WebhookTokenService.deleteTokensForWorkflowScoped.
    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Round-7 lifecycle state (PR2). webhook_tokens has no legacy boolean -
    // state is the sole source of truth from PR2 onwards.
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private TriggerState state = TriggerState.ACTIVE;

    @Column(name = "last_disabled_reason", length = 40)
    private String lastDisabledReason;

    @Column(name = "last_disabled_at")
    private Instant lastDisabledAt;

    public WebhookTokenEntity() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public WebhookTokenEntity(UUID workflowId, String triggerId, String token) {
        this();
        this.workflowId = workflowId;
        this.triggerId = triggerId;
        this.token = token;
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(UUID workflowId) {
        this.workflowId = workflowId;
    }

    public String getTriggerId() {
        return triggerId;
    }

    public void setTriggerId(String triggerId) {
        this.triggerId = triggerId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
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

    public TriggerState getState() { return state; }
    public void setState(TriggerState state) { this.state = state; }

    public String getLastDisabledReason() { return lastDisabledReason; }
    public void setLastDisabledReason(String lastDisabledReason) { this.lastDisabledReason = lastDisabledReason; }

    public Instant getLastDisabledAt() { return lastDisabledAt; }
    public void setLastDisabledAt(Instant lastDisabledAt) { this.lastDisabledAt = lastDisabledAt; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    @Override
    public String toString() {
        return "WebhookTokenEntity{" +
                "id=" + id +
                ", workflowId=" + workflowId +
                ", triggerId='" + triggerId + '\'' +
                ", token='" + (token != null ? token.substring(0, Math.min(12, token.length())) + "..." : "null") + '\'' +
                '}';
    }
}

package com.apimarketplace.trigger.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for webhook token data transferred between services.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookTokenDto {

    private Long id;
    private UUID workflowId;
    private String triggerId;
    private String token;
    /**
     * PR22 R2 - workspace identity carried through the webhook token lookup so that
     * orchestrator's {@code WebhookDispatchService.dispatch} (the pinned/multi-DAG
     * path) can scope-match against the resolved workflow_run's organization_id
     * before firing. NULL = personal scope. Pre-R2 the entire pinned-token branch
     * fired blind to workspace boundaries.
     */
    private String organizationId;
    private Instant createdAt;
    private Instant updatedAt;

    public WebhookTokenDto() {}

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }

    public String getTriggerId() { return triggerId; }
    public void setTriggerId(String triggerId) { this.triggerId = triggerId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

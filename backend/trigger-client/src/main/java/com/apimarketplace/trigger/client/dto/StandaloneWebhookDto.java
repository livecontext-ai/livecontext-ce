package com.apimarketplace.trigger.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for standalone webhook data transferred between services.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StandaloneWebhookDto {

    private UUID id;
    private String tenantId;
    /** PR22 - workspace identity. NULL = personal scope. Webhook dispatcher uses this to stamp the workflow_run. */
    private String organizationId;
    private String name;
    private String description;
    private String token;
    private String webhookUrl;
    private String httpMethod;
    private String authType;
    private Map<String, String> authConfig;
    private UUID workflowId;
    private String workflowName;
    private Boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
    private String sourceNodeId;

    public StandaloneWebhookDto() {}

    // Getters and Setters

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

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public Map<String, String> getAuthConfig() { return authConfig; }
    public void setAuthConfig(Map<String, String> authConfig) { this.authConfig = authConfig; }

    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }

    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getSourceNodeId() { return sourceNodeId; }
    public void setSourceNodeId(String sourceNodeId) { this.sourceNodeId = sourceNodeId; }
}

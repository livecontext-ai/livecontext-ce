package com.apimarketplace.interfaces.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for interface run snapshot data transferred between services and to the frontend.
 * Uses default camelCase Jackson serialization to match frontend TypeScript types.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InterfaceSnapshotDto {

    private UUID id;
    private String tenantId;
    private UUID interfaceId;
    private UUID workflowRunId;
    private String name;
    private String description;
    private String htmlTemplate;
    private String cssTemplate;
    private String jsTemplate;
    private Map<String, String> variableMappings;
    private Map<String, String> actionMappings;
    private Instant createdAt;

    public InterfaceSnapshotDto() {}

    // Getters and setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public UUID getInterfaceId() { return interfaceId; }
    public void setInterfaceId(UUID interfaceId) { this.interfaceId = interfaceId; }

    public UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(UUID workflowRunId) { this.workflowRunId = workflowRunId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getHtmlTemplate() { return htmlTemplate; }
    public void setHtmlTemplate(String htmlTemplate) { this.htmlTemplate = htmlTemplate; }

    public String getCssTemplate() { return cssTemplate; }
    public void setCssTemplate(String cssTemplate) { this.cssTemplate = cssTemplate; }

    public String getJsTemplate() { return jsTemplate; }
    public void setJsTemplate(String jsTemplate) { this.jsTemplate = jsTemplate; }

    public Map<String, String> getVariableMappings() { return variableMappings; }
    public void setVariableMappings(Map<String, String> variableMappings) { this.variableMappings = variableMappings; }

    public Map<String, String> getActionMappings() { return actionMappings; }
    public void setActionMappings(Map<String, String> actionMappings) { this.actionMappings = actionMappings; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

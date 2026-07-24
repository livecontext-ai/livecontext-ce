package com.apimarketplace.interfaces.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for interface entity data transferred between services and to the frontend.
 * Uses default camelCase Jackson serialization to match frontend TypeScript types.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InterfaceDto {

    private UUID id;
    private String tenantId;
    private String name;
    private String description;
    private String htmlTemplate;
    private String cssTemplate;
    private String jsTemplate;
    private String targetTable;
    private Long dataSourceId;
    private List<String> templateVariables;
    private List<String> formFields;
    private UUID sourceWorkflowId;
    private Boolean isPublic;
    private Boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
    private String interfaceType;
    /** Display/capture format (preset name or "WIDTHxHEIGHT"). Null = full page at 1280x800. */
    private String format;
    private Map<String, Object> data;
    private String agentId;
    private String messageId;
    private String conversationId;
    private UUID projectId;
    private String organizationId;
    private UUID sourcePublicationId;
    private UUID workflowRunId;
    private Long stepDataId;

    public InterfaceDto() {}

    // Getters and setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

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

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long dataSourceId) { this.dataSourceId = dataSourceId; }

    public List<String> getTemplateVariables() { return templateVariables; }
    public void setTemplateVariables(List<String> templateVariables) { this.templateVariables = templateVariables; }

    public List<String> getFormFields() { return formFields; }
    public void setFormFields(List<String> formFields) { this.formFields = formFields; }

    public UUID getSourceWorkflowId() { return sourceWorkflowId; }
    public void setSourceWorkflowId(UUID sourceWorkflowId) { this.sourceWorkflowId = sourceWorkflowId; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getInterfaceType() { return interfaceType; }
    public void setInterfaceType(String interfaceType) { this.interfaceType = interfaceType; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public UUID getSourcePublicationId() { return sourcePublicationId; }
    public void setSourcePublicationId(UUID sourcePublicationId) { this.sourcePublicationId = sourcePublicationId; }

    public UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(UUID workflowRunId) { this.workflowRunId = workflowRunId; }

    public Long getStepDataId() { return stepDataId; }
    public void setStepDataId(Long stepDataId) { this.stepDataId = stepDataId; }
}

package com.apimarketplace.interfaces.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

/**
 * JPA entity for the interfaces table.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "interfaces")
public class InterfaceEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Lob
    @Column(name = "html_template", nullable = true)
    private String htmlTemplate;

    @Lob
    @Column(name = "css_template")
    private String cssTemplate;

    @Lob
    @Column(name = "js_template")
    private String jsTemplate;

    @Column(name = "workflow_run_id")
    private UUID workflowRunId;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "step_data_id")
    private Long stepDataId;

    @Column(name = "target_table")
    private String targetTable;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "data_source_id")
    private Long dataSourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_variables", columnDefinition = "jsonb")
    private List<String> templateVariables = new ArrayList<>();

    @Transient
    private List<String> formFields;

    @Column(name = "source_workflow_id")
    private UUID sourceWorkflowId;

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "interface_type", nullable = false)
    private String interfaceType = "html";

    /**
     * Display/capture format of this interface: a preset name or a custom "WIDTHxHEIGHT"
     * (see {@link com.apimarketplace.interfaces.client.InterfaceFormat}). It drives the
     * screenshot/video dimensions and every preview surface.
     *
     * <p>NULL is meaningful and is the default: "no declared shape" = full-page capture at a
     * 1280x800 viewport. It is NOT the same as the {@code classic} preset (an exact 1280x800
     * frame that crops below the fold), so this field deliberately has no default value and no
     * coalescing setter - defaulting it would silently crop every interface authored as a tall
     * page.
     */
    @Column(name = "format")
    private String format;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb")
    private Map<String, Object> data;

    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "message_id")
    private String messageId;

    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "source_publication_id")
    private UUID sourcePublicationId;

    public InterfaceEntity() {
    }

    @PrePersist
    private void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.isPublic == null) {
            this.isPublic = false;
        }
        if (this.isActive == null) {
            this.isActive = true;
        }
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters / setters

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

    public UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(UUID workflowRunId) { this.workflowRunId = workflowRunId; }

    public Long getStepDataId() { return stepDataId; }
    public void setStepDataId(Long stepDataId) { this.stepDataId = stepDataId; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long dataSourceId) { this.dataSourceId = dataSourceId; }

    public List<String> getTemplateVariables() {
        return templateVariables != null ? templateVariables : List.of();
    }

    public void setTemplateVariables(List<String> templateVariables) {
        this.templateVariables = templateVariables != null ? new ArrayList<>(templateVariables) : new ArrayList<>();
    }

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
    public void setInterfaceType(String interfaceType) { this.interfaceType = interfaceType != null ? interfaceType : "html"; }

    public String getFormat() { return format; }
    /** No null-coalescing here on purpose: null means "full page", not the classic preset. */
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
}

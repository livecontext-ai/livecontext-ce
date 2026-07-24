package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entite pour la table workflows
 * Stocke les workflows avec leur plan JSONB et les meta donnees principales
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "workflows")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WorkflowEntity implements OrgScopedEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "version", nullable = false)
    private String version = "1.0.0";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WorkflowStatus status = WorkflowStatus.ACTIVE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan", columnDefinition = "jsonb")
    private Map<String, Object> plan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_inputs", columnDefinition = "jsonb")
    private Map<String, Object> dataInputs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "execution_metadata", columnDefinition = "jsonb")
    private Map<String, Object> executionMetadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    private List<String> tags;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schedule", columnDefinition = "jsonb")
    private Map<String, Object> schedule;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "last_executed_at")
    private Instant lastExecutedAt;

    @Column(name = "last_executed_by")
    private String lastExecutedBy;

    @Column(name = "retention_days")
    private Integer retentionDays = 30;

    @Column(name = "webhook_token", unique = true)
    private String webhookToken;

    @Column(name = "webhook_created_at")
    private Instant webhookCreatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "node_icons", columnDefinition = "jsonb")
    private List<Map<String, Object>> nodeIcons;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "organization_id")
    private String organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_type", nullable = false)
    private WorkflowType workflowType = WorkflowType.WORKFLOW;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "base_plan", columnDefinition = "jsonb")
    private Map<String, Object> basePlan;

    @Column(name = "source_publication_id")
    private UUID sourcePublicationId;

    @Column(name = "acquired_at")
    private Instant acquiredAt;

    @Column(name = "pinned_version")
    private Integer pinnedVersion;

    /**
     * Round-7 redesign (PR3): canonical "run-of-record" pointer.
     * Set by {@code PinTransaction} on pin/rearm; cleared by run deletion (FK
     * {@code ON DELETE SET NULL}). PR4 dispatchers will read this directly via
     * {@code RunSelectionPolicy.BY_PRODUCTION_RUN_ID} for an O(1) lookup.
     */
    @Column(name = "production_run_id")
    private UUID productionRunId;

    /**
     * Optional cost budget for this workflow / application, in credits
     * (1 credit = $0.001). {@code null} = no budget. Edited in the "Advanced"
     * section of the workflow settings modal. Enforced at the epoch boundary:
     * once a run's accumulated cost across all epochs reaches this budget, no
     * NEW epoch is allowed to start (the in-flight epoch still finishes) - see
     * {@code ReusableTriggerService}. The CE edition shows this as dollars, the
     * cloud edition as credits (frontend display concern).
     */
    @Column(name = "budget_credits", precision = 15, scale = 4)
    private java.math.BigDecimal budgetCredits;

    public enum WorkflowStatus {
        ACTIVE, INACTIVE, DRAFT, ARCHIVED
    }

    public enum WorkflowType {
        WORKFLOW, APPLICATION
    }

    public WorkflowEntity() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public WorkflowEntity(String tenantId, String name, String createdBy) {
        this();
        this.tenantId = tenantId;
        this.name = name;
        this.createdBy = createdBy;
    }

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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowStatus status) {
        this.status = status;
    }

    public Map<String, Object> getPlan() {
        return plan;
    }

    public void setPlan(Map<String, Object> plan) {
        this.plan = plan;
    }

    public Map<String, Object> getDataInputs() {
        return dataInputs;
    }

    public void setDataInputs(Map<String, Object> dataInputs) {
        this.dataInputs = dataInputs;
    }

    public Map<String, Object> getExecutionMetadata() {
        return executionMetadata;
    }

    public void setExecutionMetadata(Map<String, Object> executionMetadata) {
        this.executionMetadata = executionMetadata;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Map<String, Object> getSchedule() {
        return schedule;
    }

    public void setSchedule(Map<String, Object> schedule) {
        this.schedule = schedule;
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

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Instant getLastExecutedAt() {
        return lastExecutedAt;
    }

    public void setLastExecutedAt(Instant lastExecutedAt) {
        this.lastExecutedAt = lastExecutedAt;
    }

    public String getLastExecutedBy() {
        return lastExecutedBy;
    }

    public void setLastExecutedBy(String lastExecutedBy) {
        this.lastExecutedBy = lastExecutedBy;
    }

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getWebhookToken() {
        return webhookToken;
    }

    public void setWebhookToken(String webhookToken) {
        this.webhookToken = webhookToken;
    }

    public Instant getWebhookCreatedAt() {
        return webhookCreatedAt;
    }

    public void setWebhookCreatedAt(Instant webhookCreatedAt) {
        this.webhookCreatedAt = webhookCreatedAt;
    }

    public List<Map<String, Object>> getNodeIcons() {
        return nodeIcons;
    }

    public void setNodeIcons(List<Map<String, Object>> nodeIcons) {
        this.nodeIcons = nodeIcons;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public UUID getSourcePublicationId() {
        return sourcePublicationId;
    }

    public void setSourcePublicationId(UUID sourcePublicationId) {
        this.sourcePublicationId = sourcePublicationId;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(Instant acquiredAt) {
        this.acquiredAt = acquiredAt;
    }

    public WorkflowType getWorkflowType() {
        return workflowType;
    }

    public void setWorkflowType(WorkflowType workflowType) {
        this.workflowType = workflowType;
    }

    public Map<String, Object> getBasePlan() {
        return basePlan;
    }

    public void setBasePlan(Map<String, Object> basePlan) {
        this.basePlan = basePlan;
    }

    public boolean isApplication() {
        return workflowType == WorkflowType.APPLICATION;
    }

    public Integer getPinnedVersion() {
        return pinnedVersion;
    }

    public void setPinnedVersion(Integer pinnedVersion) {
        this.pinnedVersion = pinnedVersion;
    }

    public UUID getProductionRunId() {
        return productionRunId;
    }

    public void setProductionRunId(UUID productionRunId) {
        this.productionRunId = productionRunId;
    }

    public java.math.BigDecimal getBudgetCredits() {
        return budgetCredits;
    }

    public void setBudgetCredits(java.math.BigDecimal budgetCredits) {
        this.budgetCredits = budgetCredits;
    }

    @PrePersist
    private void ensureIdentifiers() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = this.createdAt;
        } else {
            this.updatedAt = Instant.now();
        }
    }

    /**
     * Defense-in-depth: bump {@code updatedAt} on every Hibernate dirty-checked UPDATE.
     * 15+ call sites today call {@code workflowRepository.save(entity)} without first
     * calling {@code setUpdatedAt(Instant.now())} - pin/unpin, status toggle, project
     * re-assign, application reset, etc. Without this annotation, those saves leave
     * {@code updated_at} frozen on the last explicit set, which silently degrades the
     * bell's Activity tab freshness (the tab orders by {@code workflows.updated_at DESC}).
     *
     * <p>Mirrors {@code InterfaceEntity.@PreUpdate} (interface-service) and closes the
     * same shape of bug V249 trigger fixed for {@code data_sources} on row-CRUD.
     *
     * <p>Note: JPQL {@code @Modifying @Query} bulk UPDATEs (e.g.
     * {@code WorkflowRepository.updateLastExecutedAt}) bypass JPA lifecycle callbacks
     * by Hibernate contract - those queries must SET {@code updatedAt} explicitly in
     * their own SET clause if they want the Activity feed to surface the change.
     */
    @PreUpdate
    private void bumpUpdatedAt() {
        this.updatedAt = Instant.now();
    }
}

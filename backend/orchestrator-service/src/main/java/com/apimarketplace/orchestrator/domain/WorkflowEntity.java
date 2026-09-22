package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.apimarketplace.orchestrator.services.WorkflowNodeTypeExtractor;
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

    /**
     * Folder this workflow is filed under on the /app/workflow list
     * ({@code orchestrator.workflow_folders}, V448), or {@code null} for the top level.
     * Independent of {@link #projectId}: a project is a shared workspace for a piece of
     * work, a folder is how the list itself is organised, and a workflow can be in both.
     */
    @Column(name = "folder_id")
    private UUID folderId;

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
     * Optional spending cap for this workflow / application, in credits
     * (1 credit = $0.001). {@code null} or {@code <= 0} = no cap.
     *
     * <p>V474 changed what this is measured against. It used to be compared to
     * the RUN's lifetime cost, which on a pinned workflow (one production run
     * accumulating epochs forever) made it a lifetime cap: the workflow fired
     * until the cap was reached and then stopped for good. It is now the cap on
     * what the workflow spends inside one {@link #budgetPeriodMode} period,
     * tracked in {@link #budgetPeriodSpent}.
     *
     * <p>It counts AGENT spend only: paid catalog calls and resold generation
     * are billed inside catalog-service and never reach this counter.
     *
     * <p>Which runs it governs is decided in ONE place,
     * {@code WorkflowBudgetState.appliesToRun()}: every run except a builder
     * test fire. Read that method before rebuilding the rule from either signal
     * on its own; it explains why both are needed and what is, and is not, a
     * reachable hole. The CE edition shows this as dollars, the cloud edition as
     * credits (frontend display concern).
     */
    @Column(name = "budget_credits", precision = 15, scale = 4)
    private java.math.BigDecimal budgetCredits;

    /**
     * How {@link #budgetPeriodSpent} resets: {@code monthly} (default),
     * {@code weekly} or {@code cumulative} (never). Values are constrained in
     * the DB; the rollover rule itself lives in {@code WorkflowBudgetPeriod}.
     *
     * <p>Initialised in Java as well as in the DB default: the column is NOT
     * NULL, and a null field would make Hibernate insert an explicit NULL and
     * abort the INSERT.
     */
    @Column(name = "budget_period_mode", length = 16)
    private String budgetPeriodMode = "monthly";

    /**
     * Agent credits spent by the governed runs in the period that
     * {@link #budgetPeriodStartedAt} opens.
     *
     * <p>DB-managed: written ONLY by {@code WorkflowRepository}'s native
     * increment, which resets it in place when the period has rolled over. The
     * JPA column is {@code insertable=false, updatable=false} so a stray
     * {@code save(workflow)} can never clobber the live value with a stale
     * in-memory copy - the same fence {@code workflow_runs.cost_credits} and
     * {@code state_snapshot} use, for the same reason.
     */
    @Column(name = "budget_period_spent", precision = 15, scale = 4,
            insertable = false, updatable = false)
    private java.math.BigDecimal budgetPeriodSpent = java.math.BigDecimal.ZERO;

    /**
     * Start of the period {@link #budgetPeriodSpent} belongs to, truncated to
     * the mode's unit in UTC. {@code null} until the first production cost is
     * recorded. DB-managed, same fence as {@link #budgetPeriodSpent}.
     */
    @Column(name = "budget_period_started_at", insertable = false, updatable = false)
    private Instant budgetPeriodStartedAt;

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

    /**
     * Node-type tokens of this workflow ({@code mcp:gmail}, {@code core:loop},
     * {@code trigger:webhook}, ...), used by the node-type filter on the
     * workflow list. Never null; empty for a plan with no nodes.
     *
     * <p>Computed from {@link #plan} on every call rather than stored in a
     * column of its own. Every path that filters on this already has the plan in
     * memory (it is an eager column, and the list loads the whole org set to run
     * its search and sort), so a stored copy would be a duplicate of data
     * already here - one that could go stale and drop a workflow out of a
     * filtered list with no error to notice. The walk is over a handful of small
     * arrays, next to nothing beside the JSONB deserialization that already
     * happened to load the plan.
     *
     * <p>The published twin does keep a column, because its list queries
     * deliberately never load the plan snapshot.
     */
    public List<String> getNodeTypes() {
        return WorkflowNodeTypeExtractor.extractNodeTypes(this.plan);
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public void setFolderId(UUID folderId) {
        this.folderId = folderId;
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

    public String getBudgetPeriodMode() {
        return budgetPeriodMode;
    }

    public void setBudgetPeriodMode(String budgetPeriodMode) {
        this.budgetPeriodMode = budgetPeriodMode;
    }

    /** Read-only: written by the native increment, never by an entity flush. */
    public java.math.BigDecimal getBudgetPeriodSpent() {
        return budgetPeriodSpent;
    }

    /** Read-only: written by the native increment, never by an entity flush. */
    public Instant getBudgetPeriodStartedAt() {
        return budgetPeriodStartedAt;
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

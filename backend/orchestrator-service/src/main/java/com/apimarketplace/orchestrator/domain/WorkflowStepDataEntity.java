package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.domain.workflow.ErrorMessageLimits;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entite JPA pour les donnees d'etapes de workflow.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "workflow_step_data", uniqueConstraints = {
    // Matches the actual DB constraint created by V15/V16 (drops v5, adds v6
    // with trigger_id). The entity annotation had drifted to v5 for 4 years
    // - Hibernate `validate` tolerated the name mismatch but a future
    // `update` mode or schema-validator would have failed boot. Brought into
    // sync as part of the F1-F10 silent-drop sweep.
    @UniqueConstraint(name = "idx_workflow_step_data_unique_v6",
        columnNames = {"workflow_run_id", "step_alias", "trigger_id", "iteration", "item_index", "epoch", "spawn", "status"})
})
public class WorkflowStepDataEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "step_alias", nullable = false, length = 2000)
    private String stepAlias;

    @Column(name = "tool_id", nullable = false, length = 2000)
    private String toolId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_data", columnDefinition = "jsonb")
    private Map<String, Object> inputData;

    @Column(name = "output_storage_id")
    private UUID outputStorageId;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "epoch")
    private Integer epoch = 0;

    @Column(name = "spawn")
    private Integer spawn = 0;

    @Column(name = "iteration")
    private Integer iteration = 0;

    @Column(name = "item_index")
    private Integer itemIndex = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    // === NEW UNIFIED COLUMNS ===

    // Node type identification
    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", length = 20)
    private NodeType nodeType;

    // Decision node columns
    @Column(name = "condition_expression", columnDefinition = "text")
    private String conditionExpression;

    @Column(name = "condition_result")
    private Boolean conditionResult;

    @Column(name = "selected_branch", columnDefinition = "TEXT")
    private String selectedBranch;

    // Loop node columns
    @Column(name = "loop_id", length = 2000)
    private String loopId;

    @Column(name = "loop_iteration")
    private Integer loopIteration;

    @Column(name = "loop_exit_reason", length = 50)
    private String loopExitReason;

    // Merge node columns
    @Column(name = "merge_strategy", length = 50)
    private String mergeStrategy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "merge_received_branches", columnDefinition = "jsonb")
    private List<String> mergeReceivedBranches;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "merge_skipped_branches", columnDefinition = "jsonb")
    private List<String> mergeSkippedBranches;

    // Item tracking columns
    @Column(name = "item_id", columnDefinition = "TEXT")
    private String itemId;

    @Column(name = "trigger_id", length = 2000)
    private String triggerId;

    // Skip tracking columns
    @Column(name = "skip_reason", columnDefinition = "text")
    private String skipReason;

    @Column(name = "skip_source_node", length = 2000)
    private String skipSourceNode;

    // Normalized node key (e.g., "trigger:my_webhook", "mcp:api_call")
    // Part of the StateManager refactoring to eliminate alias/key confusion
    @Column(name = "normalized_key", length = 2000)
    private String normalizedKey;

    // Sequential item number per run for human-readable display (1, 2, 3...)
    @Column(name = "item_number")
    private Integer itemNumber;

    public WorkflowStepDataEntity() {
    }

    /**
     * Defense-in-depth: re-apply the F4/F1-bundle-2 identifier caps at the JPA
     * flush boundary so any write path that bypasses the setters (Hibernate
     * reflection on the field, native JdbcTemplate writes via
     * H2StepDataNativeRepository, raw entityManager.persist with a hand-built
     * entity) cannot leak uncapped values into the DB and trigger the
     * VARCHAR(255) overflow → DataIntegrityViolation → silent-drop family.
     * Re-running setters here is idempotent (the cap markers are regex-detected
     * and short-circuited) so the hot path is unaffected.
     */
    @jakarta.persistence.PrePersist
    @jakarta.persistence.PreUpdate
    void applyIdentifierCapsBeforeFlush() {
        setStepAlias(this.stepAlias);
        setToolId(this.toolId);
        setLoopId(this.loopId);
        setTriggerId(this.triggerId);
        setSkipSourceNode(this.skipSourceNode);
        setNormalizedKey(this.normalizedKey);
    }

    public WorkflowStepDataEntity(UUID workflowRunId,
                                   String runId,
                                   String stepAlias,
                                   String toolId,
                                   Map<String, Object> inputData,
                                   UUID outputStorageId,
                                   Integer httpStatus,
                                   String status,
                                   Instant startTime,
                                   Instant endTime,
                                   String errorMessage,
                                   String tenantId,
                                   Integer epoch,
                                   Integer spawn,
                                   Integer iteration,
                                   Integer itemIndex,
                                   Map<String, Object> metadata) {
        this.workflowRunId = workflowRunId;
        this.runId = runId;
        // Route stepAlias + toolId through setters so the F4/F1-bundle-2 caps
        // apply uniformly. Direct field assignment here would bypass the cap
        // and re-introduce the silent-drop path on a different code surface.
        setStepAlias(stepAlias);
        setToolId(toolId);
        this.inputData = inputData;
        this.outputStorageId = outputStorageId;
        this.httpStatus = httpStatus;
        this.status = status;
        this.startTime = startTime;
        this.endTime = endTime;
        this.errorMessage = errorMessage;
        this.tenantId = tenantId;
        this.epoch = epoch != null ? epoch : 0;
        this.spawn = spawn != null ? spawn : 0;
        this.iteration = iteration != null ? iteration : 0;
        this.itemIndex = itemIndex != null ? itemIndex : 0;
        this.metadata = metadata;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getWorkflowRunId() {
        return workflowRunId;
    }

    public void setWorkflowRunId(UUID workflowRunId) {
        this.workflowRunId = workflowRunId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getStepAlias() {
        return stepAlias;
    }

    public void setStepAlias(String stepAlias) {
        // F4/F1-bundle-2 prep: cap with hash-suffix to prevent silent-drop on
        // VARCHAR(255) overflow AND prevent collision-after-truncation on the
        // unique index v6 (workflow_run_id, step_alias, trigger_id, …).
        this.stepAlias = com.apimarketplace.orchestrator.domain.workflow
                .DiagnosticFieldLimits.capWithCollisionHash(stepAlias,
                        com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits.STEP_ALIAS_MAX);
    }

    public String getToolId() {
        return toolId;
    }

    public void setToolId(String toolId) {
        // F4/F1-bundle-2 prep: plain cap - not indexed, so no collision risk.
        this.toolId = com.apimarketplace.orchestrator.domain.workflow
                .DiagnosticFieldLimits.cap(toolId,
                        com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits.TOOL_ID_MAX);
    }

    public Map<String, Object> getInputData() {
        return inputData;
    }

    public void setInputData(Map<String, Object> inputData) {
        this.inputData = inputData;
    }

    public UUID getOutputStorageId() {
        return outputStorageId;
    }

    public void setOutputStorageId(UUID outputStorageId) {
        this.outputStorageId = outputStorageId;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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

    public Integer getEpoch() {
        return epoch;
    }

    public void setEpoch(Integer epoch) {
        this.epoch = epoch;
    }

    public Integer getSpawn() {
        return spawn;
    }

    public void setSpawn(Integer spawn) {
        this.spawn = spawn;
    }

    public Integer getIteration() {
        return iteration;
    }

    public void setIteration(Integer iteration) {
        this.iteration = iteration;
    }

    public Integer getItemIndex() {
        return itemIndex;
    }

    public void setItemIndex(Integer itemIndex) {
        this.itemIndex = itemIndex;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    // === NEW UNIFIED GETTERS AND SETTERS ===

    public NodeType getNodeType() {
        return nodeType;
    }

    public void setNodeType(NodeType nodeType) {
        this.nodeType = nodeType;
    }

    public String getConditionExpression() {
        return conditionExpression;
    }

    public void setConditionExpression(String conditionExpression) {
        this.conditionExpression = conditionExpression;
    }

    public Boolean getConditionResult() {
        return conditionResult;
    }

    public void setConditionResult(Boolean conditionResult) {
        this.conditionResult = conditionResult;
    }

    public String getSelectedBranch() {
        return selectedBranch;
    }

    public void setSelectedBranch(String selectedBranch) {
        // V187: column widened to TEXT (was VARCHAR(255)). Cap via the shared
        // 16 384-char limit - same protection as error_message - to keep
        // workflow_step_data row sizes bounded. Hot path is identity-return.
        this.selectedBranch = ErrorMessageLimits.truncate(selectedBranch);
    }

    public String getLoopId() {
        return loopId;
    }

    public void setLoopId(String loopId) {
        // F4/F1-bundle-2 prep: plain cap - not indexed.
        this.loopId = com.apimarketplace.orchestrator.domain.workflow
                .DiagnosticFieldLimits.cap(loopId,
                        com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits.LOOP_ID_MAX);
    }

    public Integer getLoopIteration() {
        return loopIteration;
    }

    public void setLoopIteration(Integer loopIteration) {
        this.loopIteration = loopIteration;
    }

    public String getLoopExitReason() {
        return loopExitReason;
    }

    public void setLoopExitReason(String loopExitReason) {
        this.loopExitReason = loopExitReason;
    }

    public String getMergeStrategy() {
        return mergeStrategy;
    }

    public void setMergeStrategy(String mergeStrategy) {
        this.mergeStrategy = mergeStrategy;
    }

    public List<String> getMergeReceivedBranches() {
        return mergeReceivedBranches;
    }

    public void setMergeReceivedBranches(List<String> mergeReceivedBranches) {
        this.mergeReceivedBranches = mergeReceivedBranches;
    }

    public List<String> getMergeSkippedBranches() {
        return mergeSkippedBranches;
    }

    public void setMergeSkippedBranches(List<String> mergeSkippedBranches) {
        this.mergeSkippedBranches = mergeSkippedBranches;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        // V187: column widened to TEXT (was VARCHAR(255)). Cap via the shared
        // 16 384-char limit so Transform outputs (JWTs, long URLs) cannot bloat
        // workflow_step_data rows. Hot path is identity-return.
        this.itemId = ErrorMessageLimits.truncate(itemId);
    }

    public String getTriggerId() {
        return triggerId;
    }

    public void setTriggerId(String triggerId) {
        // F4/F1-bundle-2 prep: hash-suffix cap - trigger_id is in unique index
        // v6 (workflow_run_id, step_alias, trigger_id, …). The sentinel
        // "trigger:default" (V164 NOT NULL default) is preserved unchanged.
        this.triggerId = com.apimarketplace.orchestrator.domain.workflow
                .DiagnosticFieldLimits.capWithCollisionHash(triggerId,
                        com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits.TRIGGER_ID_MAX);
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public String getSkipSourceNode() {
        return skipSourceNode;
    }

    public void setSkipSourceNode(String skipSourceNode) {
        // F4 prep: plain cap - populated from SkippedNodePersistenceService.
        this.skipSourceNode = com.apimarketplace.orchestrator.domain.workflow
                .DiagnosticFieldLimits.cap(skipSourceNode,
                        com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits.SKIP_SOURCE_NODE_MAX);
    }

    public String getNormalizedKey() {
        return normalizedKey;
    }

    public void setNormalizedKey(String normalizedKey) {
        // F4/F1-bundle-2 prep: hash-suffix cap - normalized_key is in the V155
        // aggregate index. Same collision-after-truncation risk as step_alias.
        this.normalizedKey = com.apimarketplace.orchestrator.domain.workflow
                .DiagnosticFieldLimits.capWithCollisionHash(normalizedKey,
                        com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits.NORMALIZED_KEY_MAX);
    }

    public Integer getItemNumber() {
        return itemNumber;
    }

    public void setItemNumber(Integer itemNumber) {
        this.itemNumber = itemNumber;
    }
}

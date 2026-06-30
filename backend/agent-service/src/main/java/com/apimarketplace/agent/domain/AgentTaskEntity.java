package com.apimarketplace.agent.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An asynchronous task delegated between agents (or assigned by a human).
 * <p>
 * Assignee is nullable - a NULL {@code assignedToAgentId} means the task is in the
 * tenant backlog and any eligible agent may claim it via
 * {@code agent(action='claim')}.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "agent_tasks", schema = "agent")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentTaskEntity implements OrgScopedEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_IN_REVIEW = "in_review";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";
    /**
     * Trash column for the task board. A 'deleted' task is soft-deleted: it leaves
     * its previous column (preserved in {@link #previousStatus}), is removed from
     * every agent inbox/backlog/review query (they filter on the active statuses),
     * and is hard-purged by {@code TaskRetentionPurger} after the retention window.
     * Set ONLY via the soft-delete path (never a direct status edit) so
     * {@link #deletedAt} is always populated alongside it.
     */
    public static final String STATUS_DELETED = "deleted";

    public static final String PRIORITY_LOW = "low";
    public static final String PRIORITY_NORMAL = "normal";
    public static final String PRIORITY_HIGH = "high";
    public static final String PRIORITY_URGENT = "urgent";

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** PR20 strict-isolation: NULL = personal scope, non-null = workspace scope. */
    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "parent_task_id")
    private UUID parentTaskId;

    @Column(name = "created_by_agent_id")
    private UUID createdByAgentId;

    @Column(name = "created_by_user_id")
    private String createdByUserId;

    /** {@code null} means the task is in the backlog. */
    @Column(name = "assigned_to_agent_id")
    private UUID assignedToAgentId;

    /**
     * Human assignee (auth user id). Mutually exclusive with
     * {@link #assignedToAgentId}: a task is assigned to an agent XOR a person.
     * A person assignee NEVER triggers auto-execution - the task is a Jira-style
     * card the human moves manually (no worker kickoff, no in_progress shimmer).
     */
    @Column(name = "assigned_to_user_id")
    private String assignedToUserId;

    @Column(name = "recurrence_id")
    private UUID recurrenceId;

    /** Optional reviewer agent - receives the task result for validation. */
    @Column(name = "reviewer_agent_id")
    private UUID reviewerAgentId;

    /**
     * Human reviewer (auth user id). Mutually exclusive with
     * {@link #reviewerAgentId}. A person reviewer is informational only - the
     * agent review loop (in_review → approve/reject) does not auto-run for them.
     */
    @Column(name = "reviewer_user_id")
    private String reviewerUserId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "instructions", nullable = false, columnDefinition = "TEXT")
    private String instructions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "task_context", columnDefinition = "jsonb")
    private Map<String, Object> taskContext;

    @Column(name = "priority", nullable = false, length = 10)
    private String priority = PRIORITY_NORMAL;

    @Column(name = "status", nullable = false, length = 40)
    private String status = STATUS_PENDING;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "depth", nullable = false)
    private int depth = 0;

    @Column(name = "due_by")
    private Instant dueBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "review_attempt_count", nullable = false)
    private int reviewAttemptCount = 0;

    /**
     * Per-task override for the reviewer reject-loop cap. {@code null} means
     * use the service-level default ({@code AgentTaskService.MAX_REVIEW_ATTEMPTS}).
     * When {@link #reviewAttemptCount} reaches this value the task is
     * auto-failed rather than auto-approved.
     */
    @Column(name = "max_review_attempts")
    private Integer maxReviewAttempts;

    @Column(name = "reviewer_execution_id")
    private UUID reviewerExecutionId;

    @Column(name = "assignee_execution_id")
    private UUID assigneeExecutionId;

    /**
     * When the task was moved to the trash (status='deleted'). NULL for any live
     * task. Drives the 30-day retention purge and the "purges in N days" badge.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * The status the task held when it was trashed, so a Restore returns it to its
     * origin column. NULL while live; cleared on restore.
     */
    @Column(name = "previous_status", length = 40)
    private String previousStatus;

    /**
     * Manual ordering rank within a board column (F1). {@code null} = unranked
     * (sorts after ranked cards, falling back to recency). Assigned sequentially
     * on drag-reorder and only ever compared within one column.
     */
    @Column(name = "board_rank")
    private Double boardRank;

    /**
     * Label ids carried by this task (F2), stored inline as a JSONB array of
     * label UUID strings. The frontend resolves each id to name/color from the
     * board's label catalog; an unknown id (deleted label) is simply skipped.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label_ids", columnDefinition = "jsonb")
    private List<String> labelIds = new ArrayList<>();

    /** Estimated effort in minutes (F12); null = not set. */
    @Column(name = "estimate_minutes")
    private Integer estimateMinutes;

    /** Logged time spent in minutes (F12); null = not set. */
    @Column(name = "time_spent_minutes")
    private Integer timeSpentMinutes;

    /**
     * Ids of tasks that must finish before this one (F9 dependencies), stored
     * inline as a JSONB array. The board computes "blocked" while any blocker is
     * still non-terminal; the reverse "blocks" edge is derived client-side.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "blocked_by_ids", columnDefinition = "jsonb")
    private List<String> blockedByIds = new ArrayList<>();

    /** Checklist items (F10): each {@code {id, text, done}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checklist", columnDefinition = "jsonb")
    private List<Map<String, Object>> checklist = new ArrayList<>();

    /** File attachments (F10): each {@code {id, fileName, storageKey, mimeType, sizeBytes}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments", columnDefinition = "jsonb")
    private List<Map<String, Object>> attachments = new ArrayList<>();

    public AgentTaskEntity() {}

    @PrePersist
    private void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public UUID getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(UUID parentTaskId) { this.parentTaskId = parentTaskId; }

    public UUID getCreatedByAgentId() { return createdByAgentId; }
    public void setCreatedByAgentId(UUID createdByAgentId) { this.createdByAgentId = createdByAgentId; }

    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }

    public UUID getAssignedToAgentId() { return assignedToAgentId; }
    public void setAssignedToAgentId(UUID assignedToAgentId) { this.assignedToAgentId = assignedToAgentId; }

    public String getAssignedToUserId() { return assignedToUserId; }
    public void setAssignedToUserId(String assignedToUserId) { this.assignedToUserId = assignedToUserId; }

    public UUID getRecurrenceId() { return recurrenceId; }
    public void setRecurrenceId(UUID recurrenceId) { this.recurrenceId = recurrenceId; }

    public UUID getReviewerAgentId() { return reviewerAgentId; }
    public void setReviewerAgentId(UUID reviewerAgentId) { this.reviewerAgentId = reviewerAgentId; }

    public String getReviewerUserId() { return reviewerUserId; }
    public void setReviewerUserId(String reviewerUserId) { this.reviewerUserId = reviewerUserId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }

    public Map<String, Object> getTaskContext() { return taskContext; }
    public void setTaskContext(Map<String, Object> taskContext) { this.taskContext = taskContext; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }

    public Instant getDueBy() { return dueBy; }
    public void setDueBy(Instant dueBy) { this.dueBy = dueBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public int getReviewAttemptCount() { return reviewAttemptCount; }
    public void setReviewAttemptCount(int reviewAttemptCount) { this.reviewAttemptCount = reviewAttemptCount; }

    public Integer getMaxReviewAttempts() { return maxReviewAttempts; }
    public void setMaxReviewAttempts(Integer maxReviewAttempts) { this.maxReviewAttempts = maxReviewAttempts; }

    public UUID getReviewerExecutionId() { return reviewerExecutionId; }
    public void setReviewerExecutionId(UUID reviewerExecutionId) { this.reviewerExecutionId = reviewerExecutionId; }

    public UUID getAssigneeExecutionId() { return assigneeExecutionId; }
    public void setAssigneeExecutionId(UUID assigneeExecutionId) { this.assigneeExecutionId = assigneeExecutionId; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public String getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(String previousStatus) { this.previousStatus = previousStatus; }

    public Double getBoardRank() { return boardRank; }
    public void setBoardRank(Double boardRank) { this.boardRank = boardRank; }

    public List<String> getLabelIds() { return labelIds; }
    public void setLabelIds(List<String> labelIds) { this.labelIds = labelIds == null ? new ArrayList<>() : labelIds; }

    public Integer getEstimateMinutes() { return estimateMinutes; }
    public void setEstimateMinutes(Integer estimateMinutes) { this.estimateMinutes = estimateMinutes; }

    public Integer getTimeSpentMinutes() { return timeSpentMinutes; }
    public void setTimeSpentMinutes(Integer timeSpentMinutes) { this.timeSpentMinutes = timeSpentMinutes; }

    public List<String> getBlockedByIds() { return blockedByIds; }
    public void setBlockedByIds(List<String> blockedByIds) { this.blockedByIds = blockedByIds == null ? new ArrayList<>() : blockedByIds; }

    public List<Map<String, Object>> getChecklist() { return checklist; }
    public void setChecklist(List<Map<String, Object>> checklist) { this.checklist = checklist == null ? new ArrayList<>() : checklist; }

    public List<Map<String, Object>> getAttachments() { return attachments; }
    public void setAttachments(List<Map<String, Object>> attachments) { this.attachments = attachments == null ? new ArrayList<>() : attachments; }
}

package com.apimarketplace.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Audit trail entry for state transitions on a task.
 * Event types: {@code CREATED, CLAIMED, STARTED, COMPLETED, FAILED, CANCELLED, REASSIGNED, NOTE_ADDED, UPDATED, AGENT_STOPPED}.
 */
@Entity
@Table(name = "agent_task_events", schema = "agent")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentTaskEventEntity {

    public static final String EVT_CREATED = "CREATED";
    public static final String EVT_CLAIMED = "CLAIMED";
    public static final String EVT_STARTED = "STARTED";
    public static final String EVT_COMPLETED = "COMPLETED";
    public static final String EVT_FAILED = "FAILED";
    public static final String EVT_CANCELLED = "CANCELLED";
    public static final String EVT_REASSIGNED = "REASSIGNED";
    public static final String EVT_NOTE_ADDED = "NOTE_ADDED";
    public static final String EVT_UPDATED = "UPDATED";
    public static final String EVT_SUBMITTED_FOR_REVIEW = "SUBMITTED_FOR_REVIEW";
    public static final String EVT_APPROVED = "APPROVED";
    public static final String EVT_REVIEW_REJECTED = "REVIEW_REJECTED";
    /** Task auto-failed after the reviewer reject cap was reached. Distinct from
     *  {@link #EVT_REVIEW_REJECTED} (reject→retry) so timeline consumers can tell
     *  "terminal auto-fail" from "non-terminal rejection". */
    public static final String EVT_AUTO_FAILED = "AUTO_FAILED";
    public static final String EVT_REOPENED = "REOPENED";
    public static final String EVT_AGENT_STOPPED = "AGENT_STOPPED";
    /** Task soft-deleted (moved to the board's Deleted/trash column). */
    public static final String EVT_DELETED = "DELETED";
    /** Task restored from the trash back to its previous column. */
    public static final String EVT_RESTORED = "RESTORED";

    public static final String ACTOR_AGENT = "agent";
    public static final String ACTOR_USER = "user";
    public static final String ACTOR_SYSTEM = "system";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /**
     * Stamped from the parent task's {@code organization_id} on insert (V281). Defense-in-depth
     * against a controller-layer regression that reads events without validating the parent
     * task is in scope.
     */
    @Column(name = "organization_id", nullable = false)
    private String organizationId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "actor_type", nullable = false, length = 10)
    private String actorType;

    @Column(name = "actor_id")
    private String actorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    private Map<String, Object> oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    private Map<String, Object> newValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AgentTaskEventEntity() {}

    @PrePersist
    private void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }

    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }

    public Map<String, Object> getOldValue() { return oldValue; }
    public void setOldValue(Map<String, Object> oldValue) { this.oldValue = oldValue; }

    public Map<String, Object> getNewValue() { return newValue; }
    public void setNewValue(Map<String, Object> newValue) { this.newValue = newValue; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

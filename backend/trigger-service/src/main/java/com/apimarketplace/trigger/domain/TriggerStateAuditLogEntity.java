package com.apimarketplace.trigger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * Append-only forensic record of every trigger lifecycle transition
 * (design v3.5). Mirrors the schema produced by
 * {@code V169__trigger_lifecycle_invariants.sql}:
 *
 * <pre>
 *   trigger.trigger_state_audit_log (
 *     id            BIGSERIAL PK,
 *     seq           BIGINT NOT NULL DEFAULT nextval('trigger.trigger_state_audit_log_seq'),
 *     trigger_id    TEXT NOT NULL,
 *     trigger_type  VARCHAR(40) NOT NULL,
 *     from_state    VARCHAR(20),
 *     to_state      VARCHAR(20) NOT NULL,
 *     reason        VARCHAR(40),
 *     source        VARCHAR(20) NOT NULL,
 *     actor         TEXT,
 *     created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
 *   )
 * </pre>
 *
 * <p>The {@code trigger_id} column is polymorphic-by-text so all five trigger
 * types fit one table without per-type FKs: schedules/webhooks/chat/form
 * stringify their UUID, webhook tokens stringify their BIGINT id. Joins back
 * to the typed tables are only needed for forensic queries - the table is
 * read-mostly observability, not a hot dispatch surface.
 *
 * <p>{@code seq} is sequence-backed for monotonic ordering across
 * transactions; gaps are acceptable (cached values + rollbacks). Tests
 * assert strict monotonicity, not gaplessness.
 *
 * <p>Retention: 30 days by default, bumped when T&Cs land. The table is
 * append-only - never updated, never deleted by application code (only by
 * the retention purge job, when implemented).
 */
@Entity
@Table(name = "trigger_state_audit_log", schema = "trigger")
public class TriggerStateAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Sequence-backed monotonic ordering column. Postgres' column default
     * ({@code nextval('trigger.trigger_state_audit_log_seq')}) populates the
     * value on INSERT; Hibernate reads it back via {@link Generated}. The
     * column is marked non-insertable/non-updatable so Hibernate never tries
     * to send a value (which would trip the DEFAULT and could double-increment
     * the sequence). Result: gaps possible (cached values + rolled-back tx),
     * but strict monotonicity holds across committed rows.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "seq", nullable = false, insertable = false, updatable = false)
    private Long seq;

    /**
     * Polymorphic trigger reference. Stringified UUID for schedule, webhook,
     * chat endpoint, form endpoint; stringified BIGINT for webhook token.
     */
    @Column(name = "trigger_id", nullable = false, columnDefinition = "text")
    private String triggerId;

    /** {@code schedule | webhook | chat | form | token}. */
    @Column(name = "trigger_type", nullable = false, length = 40)
    private String triggerType;

    /**
     * State the trigger was in BEFORE the transition. {@code null} for the
     * very first lifecycle event of a row (no prior state to record).
     * Example {@link TriggerState#name()} values: {@code ACTIVE},
     * {@code SUSPENDED_NO_RUN}.
     */
    @Column(name = "from_state", length = 20)
    private String fromState;

    /** State the trigger entered. Same enum domain as {@link #fromState}. */
    @Column(name = "to_state", nullable = false, length = 20)
    private String toState;

    /**
     * One of the {@code TriggerLifecycleManager.Reason} constants
     * (e.g. {@code USER_DISABLED}, {@code WORKFLOW_UNPINNED}); {@code null}
     * on arming transitions where no reason applies.
     */
    @Column(name = "reason", length = 40)
    private String reason;

    /**
     * One of the {@code TriggerLifecycleManager.Source} enum values
     * stringified ({@code PIN}, {@code ADMIN}, {@code REAPER}, {@code SYNC},
     * {@code QUOTA}, {@code DELETION}).
     */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    /**
     * Free-form actor identifier - typically the tenant id or a system
     * identifier ("system:reaper", "system:pin-cascade"). {@code null}
     * acceptable for legacy rows or unattributed system actions.
     */
    @Column(name = "actor", columnDefinition = "text")
    private String actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public TriggerStateAuditLogEntity() {
        this.createdAt = Instant.now();
    }

    public TriggerStateAuditLogEntity(String triggerId, String triggerType,
                                      String fromState, String toState,
                                      String reason, String source, String actor) {
        this();
        this.triggerId = triggerId;
        this.triggerType = triggerType;
        this.fromState = fromState;
        this.toState = toState;
        this.reason = reason;
        this.source = source;
        this.actor = actor;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }

    public String getTriggerId() { return triggerId; }
    public void setTriggerId(String triggerId) { this.triggerId = triggerId; }

    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public String getFromState() { return fromState; }
    public void setFromState(String fromState) { this.fromState = fromState; }

    public String getToState() { return toState; }
    public void setToState(String toState) { this.toState = toState; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

package com.apimarketplace.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only log linking {@code agent_executions} to {@code agent_tasks} across the
 * three claim-lifecycle events: {@link #EVT_CLAIMED}, {@link #EVT_RELEASED}, {@link #EVT_SUBMITTED}.
 *
 * <p>Keyed by {@code executionId} (= the stable {@code runId} minted at dispatch and persisted
 * as {@link AgentExecutionEntity#id}). The log row is written BEFORE the matching
 * {@code agent_executions} row exists - the agent calls {@code task_claim} mid-execution and the
 * observability row is only INSERTed at end-of-run. There is intentionally NO FK on
 * {@code execution_id} because of this ordering. The {@code task_id} FK enforces integrity in the
 * other direction (a task hard delete cascades the log rows).
 *
 * <p>Replaces the {@code AgentExecutionRepository.backfillTaskId} mechanism which left
 * {@code agent_executions.task_id = NULL} on the schedule-fire path (15/15 prod rows on
 * 2026-05-22) because the writer ran the UPDATE only when an execution was already
 * {@code status='RUNNING'} - the schedule-dispatch path's claim arrived before the row existed.
 */
@Entity
@Table(name = "agent_task_claims", schema = "agent")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentTaskClaimEntity {

    /** Agent called {@code task_claim} - start of a session against this task. */
    public static final String EVT_CLAIMED = "claimed";
    /** Agent voluntarily released the task without finishing - back to backlog. */
    public static final String EVT_RELEASED = "released";
    /** Agent submitted the task for review - terminal for this claim. */
    public static final String EVT_SUBMITTED = "submitted";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable runId, = {@link AgentExecutionEntity#getId()}. No FK by design (see class doc). */
    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "event", nullable = false, length = 20)
    private String event;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "organization_id", nullable = false)
    private String organizationId;

    @Column(name = "tenant_id", nullable = false, length = 255)
    private String tenantId;

    public AgentTaskClaimEntity() {}

    @PrePersist
    private void onCreate() {
        if (this.at == null) this.at = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getExecutionId() { return executionId; }
    public void setExecutionId(UUID executionId) { this.executionId = executionId; }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}

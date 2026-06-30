package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AgentExecutionRepository extends JpaRepository<AgentExecutionEntity, UUID> {

    Page<AgentExecutionEntity> findByAgentEntityIdAndTenantIdOrderByStartedAtDesc(
        UUID agentEntityId, String tenantId, Pageable pageable);

    // ──────────────────────────────────────────────────────────────────────
    // V261 strict-isolation finders - workspace-scope variants.
    //   • *OrganizationIdStrict → rows tagged with the given org. Membership is
    //     asserted upstream by the gateway via X-Organization-Role; tenant of
    //     caller is not checked here.
    // Post-V261 every user-scoped row has a non-null organization_id, so the
    // pre-V261 *TenantIdAndOrganizationIdIsNull pair was removed.
    // ──────────────────────────────────────────────────────────────────────

    @Query("SELECT e FROM AgentExecutionEntity e "
         + "WHERE e.agentEntityId = :agentEntityId AND e.organizationId = :orgId "
         + "ORDER BY e.startedAt DESC")
    Page<AgentExecutionEntity> findByAgentEntityIdAndOrganizationIdStrict(
        @Param("agentEntityId") UUID agentEntityId,
        @Param("orgId") String orgId,
        Pageable pageable);

    /**
     * Running executions for an agent started within the recency window. Powers the
     * {@code agent:activity:{agentId}} WS snapshot (replayed on a LATE subscribe):
     * re-publishing {@code execution_started} for these rows tells a client that
     * arrived mid-run the agent is busy - the only way it can learn that for a
     * bridge/CLI agent, whose only activity events are started/completed.
     *
     * <p>The {@code startedAt > :cutoff} guard prevents resurrecting executions left
     * {@code RUNNING} by a crashed pod (no terminal event was ever written), which
     * would otherwise show a phantom "working" shimmer forever on every subscribe.
     */
    @Query("SELECT e FROM AgentExecutionEntity e "
         + "WHERE e.agentEntityId = :agentEntityId AND e.status = 'RUNNING' "
         + "AND e.startedAt > :cutoff "
         + "ORDER BY e.startedAt DESC")
    List<AgentExecutionEntity> findRunningByAgentEntityIdSince(
        @Param("agentEntityId") UUID agentEntityId,
        @Param("cutoff") Instant cutoff);

    @Query("SELECT e FROM AgentExecutionEntity e WHERE e.id = :id AND e.organizationId = :orgId")
    java.util.Optional<AgentExecutionEntity> findByIdAndOrganizationIdStrict(
        @Param("id") UUID id, @Param("orgId") String orgId);

    @Query("SELECT COUNT(e) FROM AgentExecutionEntity e "
         + "WHERE e.agentEntityId = :agentEntityId AND e.organizationId = :orgId")
    long countByAgentEntityIdAndOrganizationIdStrict(
        @Param("agentEntityId") UUID agentEntityId, @Param("orgId") String orgId);

    /** Executions linked to a specific task (task board detail panel). */
    List<AgentExecutionEntity> findByTaskIdAndTenantIdOrderByStartedAtDesc(UUID taskId, String tenantId);

    /** Batch A (2026-05-20) - org-aware list by task. */
    @Query("SELECT e FROM AgentExecutionEntity e "
         + "WHERE e.taskId = :taskId AND e.organizationId = :orgId "
         + "ORDER BY e.startedAt DESC")
    List<AgentExecutionEntity> findByTaskIdAndOrganizationIdStrictOrderByStartedAtDesc(
        @Param("taskId") UUID taskId, @Param("orgId") String orgId);

    /** Paginated variant - DESC so page 0 is the newest batch. */
    Page<AgentExecutionEntity> findByTaskIdAndTenantIdOrderByStartedAtDesc(
        UUID taskId, String tenantId, Pageable pageable);

    /** Batch A (2026-05-20) - org-aware paged list by task. */
    @Query("SELECT e FROM AgentExecutionEntity e "
         + "WHERE e.taskId = :taskId AND e.organizationId = :orgId "
         + "ORDER BY e.startedAt DESC")
    Page<AgentExecutionEntity> findByTaskIdAndOrganizationIdStrictOrderByStartedAtDesc(
        @Param("taskId") UUID taskId, @Param("orgId") String orgId, Pageable pageable);

    Page<AgentExecutionEntity> findByWorkflowIdAndTenantIdOrderByStartedAtDesc(
        UUID workflowId, String tenantId, Pageable pageable);

    /** Batch A (2026-05-20) - org-aware paged list by workflow. */
    @Query("SELECT e FROM AgentExecutionEntity e "
         + "WHERE e.workflowId = :workflowId AND e.organizationId = :orgId "
         + "ORDER BY e.startedAt DESC")
    Page<AgentExecutionEntity> findByWorkflowIdAndOrganizationIdStrictOrderByStartedAtDesc(
        @Param("workflowId") UUID workflowId, @Param("orgId") String orgId, Pageable pageable);

    List<AgentExecutionEntity> findByWorkflowRunIdOrderByStartedAtAsc(UUID workflowRunId);

    long countByAgentEntityIdAndTenantId(UUID agentEntityId, String tenantId);

    Page<AgentExecutionEntity> findBySourceAndAgentEntityIdIsNullAndTenantIdOrderByStartedAtDesc(
        String source, String tenantId, Pageable pageable);

    Page<AgentExecutionEntity> findByAgentTypeAndTenantIdOrderByStartedAtDesc(
        String agentType, String tenantId, Pageable pageable);

    // ──────────────────────────────────────────────────────────────────────
    // V261 strict-isolation finders for chat-executions + agent-type executions.
    // Post-V261 every user-scoped row has a non-null organization_id, so the
    // pre-V261 *TenantIdAndOrganizationIdIsNull pair was removed.
    // ──────────────────────────────────────────────────────────────────────

    @Query("SELECT e FROM AgentExecutionEntity e "
         + "WHERE e.source = :source AND e.agentEntityId IS NULL "
         + "AND e.organizationId = :orgId ORDER BY e.startedAt DESC")
    Page<AgentExecutionEntity> findBySourceAndAgentEntityIdIsNullAndOrganizationIdStrictOrderByStartedAtDesc(
        @Param("source") String source, @Param("orgId") String orgId, Pageable pageable);

    @Query("SELECT e FROM AgentExecutionEntity e "
         + "WHERE e.agentType = :agentType AND e.organizationId = :orgId "
         + "ORDER BY e.startedAt DESC")
    Page<AgentExecutionEntity> findByAgentTypeAndOrganizationIdStrictOrderByStartedAtDesc(
        @Param("agentType") String agentType, @Param("orgId") String orgId, Pageable pageable);

    @Modifying
    @Query("UPDATE AgentEntity a SET " +
        "a.totalExecutions = a.totalExecutions + 1, " +
        "a.totalTokensUsed = a.totalTokensUsed + :tokens, " +
        "a.totalToolCalls = a.totalToolCalls + :toolCalls, " +
        "a.successCount = a.successCount + :success, " +
        "a.failureCount = a.failureCount + :failure, " +
        "a.totalDurationMs = a.totalDurationMs + :duration, " +
        "a.lastExecutionAt = :now, " +
        // Bump updated_at so the bell's Activity tab surfaces this agent on
        // every execution (COMPLETED / FAILED / CANCELLED - see
        // AgentObservabilityService line 414-424 where :now is always passed).
        // JPQL @Modifying bypasses @PreUpdate, so the explicit SET is required.
        "a.updatedAt = :now " +
        "WHERE a.id = :agentId")
    void incrementCounters(
        @Param("agentId") UUID agentId,
        @Param("tokens") long tokens,
        @Param("toolCalls") int toolCalls,
        @Param("success") int success,
        @Param("failure") int failure,
        @Param("duration") long duration,
        @Param("now") Instant now);

    @Modifying
    @Query("UPDATE AgentExecutionEntity e SET e.creditsConsumed = :credits WHERE e.id = :execId")
    void updateCreditsConsumed(@Param("execId") UUID execId, @Param("credits") BigDecimal credits);

    @Modifying
    @Query("UPDATE AgentExecutionEntity e SET e.source = :source WHERE e.id = :execId")
    void updateSource(@Param("execId") UUID execId, @Param("source") String source);

    /**
     * Backfill task_id on running executions for a given agent that don't
     * already have one. Called when a task transitions to in_progress so the
     * execution that triggered the inbox read gets linked retroactively.
     */
    @Modifying
    @Query("UPDATE AgentExecutionEntity e SET e.taskId = :taskId " +
           "WHERE e.agentEntityId = :agentId AND e.tenantId = :tenantId " +
           "AND e.taskId IS NULL AND e.status = 'RUNNING'")
    int backfillTaskId(@Param("agentId") UUID agentId,
                       @Param("tenantId") String tenantId,
                       @Param("taskId") UUID taskId);

    /** Unlink executions from a task before hard-deleting it. */
    @Modifying
    @Query("UPDATE AgentExecutionEntity e SET e.taskId = NULL WHERE e.taskId = :taskId")
    int unlinkTaskId(@Param("taskId") UUID taskId);

    /**
     * F2.1 - running executions tied to a conversation. Used by conversation
     * STOP to find which workflow runs (if any) the agent loop spawned, so we
     * can cascade the cancel signal to orchestrator. Filtering at status=RUNNING
     * means we never re-cancel terminal runs.
     */
    @Query("SELECT DISTINCT e.workflowRunId FROM AgentExecutionEntity e " +
           "WHERE e.conversationId = :conversationId " +
           "AND e.status = 'RUNNING' " +
           "AND e.workflowRunId IS NOT NULL")
    List<UUID> findRunningWorkflowRunIdsByConversationId(@Param("conversationId") String conversationId);

    @Query("SELECT DISTINCT e.workflowRunId FROM AgentExecutionEntity e " +
           "WHERE e.conversationId = :conversationId " +
           "AND e.organizationId = :organizationId " +
           "AND e.status = 'RUNNING' " +
           "AND e.workflowRunId IS NOT NULL")
    List<UUID> findRunningWorkflowRunIdsByConversationIdAndOrganizationId(
        @Param("conversationId") String conversationId,
        @Param("organizationId") String organizationId);

    /**
     * F3.4 - distinct task IDs touched by ANY execution that ran in this
     * conversation, regardless of execution status. The execution that fired
     * {@code agent.assign} may have completed (status=COMPLETED) while the
     * task it created is still in {@code pending}/{@code in_progress} on
     * another agent - the conversation STOP must still cascade to it.
     *
     * <p>Filtering by execution status would have missed those follow-on
     * tasks. Instead we widen the net here and let
     * {@code AgentTaskRepository.cascadingCancel} no-op on already-terminal
     * tasks (its WHERE clause filters to non-terminal statuses).
     */
    @Query("SELECT DISTINCT e.taskId FROM AgentExecutionEntity e " +
           "WHERE e.conversationId = :conversationId " +
           "AND e.taskId IS NOT NULL")
    List<UUID> findRunningTaskIdsByConversationId(@Param("conversationId") String conversationId);

    @Query("SELECT DISTINCT e.taskId FROM AgentExecutionEntity e " +
           "WHERE e.conversationId = :conversationId " +
           "AND e.organizationId = :organizationId " +
           "AND e.taskId IS NOT NULL")
    List<UUID> findRunningTaskIdsByConversationIdAndOrganizationId(
        @Param("conversationId") String conversationId,
        @Param("organizationId") String organizationId);

    /**
     * Sub-agent executions SPAWNED by a given conversation (org-strict, newest
     * first). Used by the conversation-scoped observability view to surface the
     * executions - and, via the per-execution drill-downs, the tool calls - of
     * sub-agents the parent spawned. These rows live under their own
     * conversationId and were previously invisible to a query filtering on
     * conversationId alone; they instead carry {@code parent_conversation_id} =
     * the spawning conversation.
     *
     * <p>Post-V261 every {@code agent_executions} row has a non-null
     * organization_id and observability reads MUST be org-strict, so there is no
     * tenant-agnostic variant - the caller passes a non-blank organizationId.
     */
    @Query("SELECT e FROM AgentExecutionEntity e "
         + "WHERE e.parentConversationId = :parentConversationId "
         + "AND e.organizationId = :orgId "
         + "ORDER BY e.startedAt DESC")
    List<AgentExecutionEntity> findByParentConversationIdAndOrganizationIdStrictOrderByStartedAtDesc(
        @Param("parentConversationId") String parentConversationId,
        @Param("orgId") String orgId);
}

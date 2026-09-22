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

    // ──────────────────────────────────────────────────────────────────────
    // ORDER BY startedAt, and why it STAYS that way (2026-09-18).
    //
    // started_at used to be persist time, so "ORDER BY e.startedAt DESC" meant
    // "most recently RECORDED first". It is now a real start
    // (AgentObservabilityService.startedAtFrom), so the same clause means "most
    // recently STARTED first", and for overlapping runs the two orders differ: a
    // 20-minute session that just finished now sorts below short runs that started
    // after it.
    //
    // That is the intended reading for a history list - a run belongs at the moment
    // it began - and it is the only one the indexes can serve: V210's
    // idx_agent_executions_agent_org_started and idx_agent_executions_org_started are
    // keyed on started_at, so ordering by ended_at would trade an index scan for a
    // sort on every page of every agent's history.
    //
    // The aggregates made the OPPOSITE choice, on purpose: AgentMetricsQueryService's
    // OCCURRED_AT reads ended_at, because "last run" and a daily bucket answer WHEN
    // something happened and must not move by a run's duration. Ordering and dating
    // are different questions; see that constant for the other half of the reasoning.
    //
    // startedAt is also a FILTER here, once: findRunningByAgentEntityIdSince's
    // `startedAt > :cutoff` recency guard below. Its window now starts from the real
    // start, so a run longer than the cutoff stops replaying as live - which is the
    // behaviour that guard wanted in the first place (it exists to stop resurrecting
    // executions a crashed pod left RUNNING). Nothing writes a RUNNING row today, so
    // this is a latent improvement rather than a live change; it is written down so the
    // next reader does not have to re-derive that the clause was considered.
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

    /**
     * Agent runs of a whole WORKSPACE inside a time window, newest first - the agenda's
     * agent history.
     *
     * <p>Four things about this query are load-bearing and were chosen rather than fallen
     * into:
     *
     * <p><b>It is a PROJECTION, not an entity read.</b> A month of a busy workspace is
     * thousands of rows, and the entity carries {@code system_prompt}, {@code tool_sequence}
     * and two JSONB snapshots. Selecting entities here would move megabytes across a
     * service boundary to draw a row of chips.
     *
     * <p><b>It is served by an index.</b> V210's org/started_at indexes cover the scope
     * and the range as an index condition, so the scan reads only the window. Keep the
     * predicate on {@code organizationId} an equality and the ordering on
     * {@code startedAt DESC} or that stops being true: ordering by {@code endedAt}, which
     * the aggregates use for a different question, would scan the workspace.
     *
     * <p>Two costs the index does NOT remove. The agent-type filter is a post-filter
     * ({@code LOWER(...)} is not sargable), so a workspace dominated by classify/guardrail
     * rows reads past them to fill a page - bounded by the window, which is why it is left
     * as is rather than given a functional index. And the join to the agent adds a sort:
     * the planner is free to pick a merge join, which does not preserve the scan's order,
     * and the sort then takes every qualifying row of the window rather than a page of
     * them. Measured on a 1 500-execution workspace: 0.7 ms for the whole history, 1.7 ms
     * for a month. If that ever stops being true, the sort is the thing to look at first,
     * not the scan.
     *
     * <p><b>The join to the agent is INNER, deliberately.</b> {@code agent_entity_id} is
     * {@code SET NULL} when an agent is deleted, so those rows can be neither named nor
     * opened; reporting them would put anonymous chips on a calendar that lead nowhere.
     *
     * <p><b>{@code agentTypes} is an ALLOW-list, and the criterion is "a run of a NAMED
     * agent, started in its own right".</b> Not "not internal", and not "the workflow's
     * epoch does not already draw it" - an ordinary {@code agent} node inside a workflow
     * IS drawn beside its workflow's epoch chip, deliberately, because the whole point of
     * the feature is seeing which agent ran and what started it.
     *
     * <p>What the column also holds, and why each is out:
     * <ul>
     *   <li>{@code classify} / {@code guardrail} - routing nodes of a workflow plan, not
     *       agents a user can open. {@code AgentNode} does not even give them a
     *       conversation.</li>
     *   <li>{@code compaction_summary} / {@code cold_summary} - summarisation calls the
     *       platform makes for itself. Nobody launched them.</li>
     *   <li>{@code browser_agent} - a Chromium session run as a TOOL inside another run,
     *       which already has its own chip; drawing it would double-count one action.
     *       Named here rather than left to inference, because it is the one excluded type
     *       that IS user-visible and separately billed, so a future reader will wonder.</li>
     * </ul>
     *
     * <p>An allow-list because the failure directions are not symmetric: a deny-list puts
     * the next internal type on someone's calendar silently, whereas a missing allow entry
     * leaves a visible hole somebody reports. Compared lower-case because the writers
     * disagree on case ({@code agent}, {@code CLI}, {@code SUB_AGENT}).
     *
     * <p>Bound the result with a {@link Pageable}, never a {@code Page}: the count query a
     * Page issues is a second pass over the same window, and the caller only needs to know
     * that it received as many rows as it asked for.
     */
    @Query("""
        SELECT new com.apimarketplace.agent.client.dto.AgentRunFireDto(
            e.id, e.agentEntityId, a.name, e.startedAt, e.endedAt, e.status, e.source, e.conversationId)
        FROM AgentExecutionEntity e
        JOIN AgentEntity a ON a.id = e.agentEntityId
        WHERE e.organizationId = :orgId
          AND e.startedAt >= :from
          AND e.startedAt <= :to
          AND LOWER(e.agentType) IN :agentTypes
        ORDER BY e.startedAt DESC
        """)
    List<com.apimarketplace.agent.client.dto.AgentRunFireDto> findWorkspaceRunsBetweenStrict(
        @Param("orgId") String orgId,
        @Param("from") Instant from,
        @Param("to") Instant to,
        @Param("agentTypes") java.util.Collection<String> agentTypes,
        Pageable pageable);
}

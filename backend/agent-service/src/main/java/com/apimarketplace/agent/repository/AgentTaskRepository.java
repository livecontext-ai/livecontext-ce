package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentTaskRepository extends JpaRepository<AgentTaskEntity, UUID> {

    // ------------------------------------------------------------------
    // Fetch
    // ------------------------------------------------------------------

    Optional<AgentTaskEntity> findByIdAndTenantId(UUID id, String tenantId);

    /**
     * Locks the task row with {@code FOR KEY SHARE} - the same lock an FK INSERT takes on its parent
     * row - and returns {@code 1} iff the task exists. Used by the observability recorder so that,
     * when an execution still carries a {@code task_id}, the referenced task cannot be deleted between
     * this check and the {@code agent_executions} INSERT: the share-lock is held until the recorder's
     * transaction commits, which CLOSES (not merely narrows) the race that would otherwise violate
     * {@code fk_agent_executions_task_id}. Returns {@code null} (no lock taken) when the task is
     * already gone, in which case the recorder nulls the denormalised link instead. Native +
     * schema-qualified so it never picks up an org-scope entity filter (a false "missing" would only
     * drop the denorm link, never corrupt data); MUST run inside the recorder's transaction.
     */
    @Query(value = "SELECT 1 FROM agent.agent_tasks WHERE id = :taskId FOR KEY SHARE", nativeQuery = true)
    Optional<Integer> lockTaskRowIfExists(@Param("taskId") UUID taskId);

    /** Inbox query (assigned to a specific agent, filterable by status). */
    List<AgentTaskEntity> findByTenantIdAndAssignedToAgentIdAndStatusInOrderByCreatedAtDesc(
            String tenantId, UUID assignedToAgentId, Collection<String> statuses, Pageable pageable);

    /** Outbox query (tasks this agent created). */
    List<AgentTaskEntity> findByTenantIdAndCreatedByAgentIdOrderByCreatedAtDesc(
            String tenantId, UUID createdByAgentId, Pageable pageable);

    List<AgentTaskEntity> findByTenantIdAndCreatedByAgentIdAndStatusOrderByCreatedAtDesc(
            String tenantId, UUID createdByAgentId, String status, Pageable pageable);

    // ──────────────────────────────────────────────────────────────────────
    // V261 strict-isolation finders - workspace-scope variants.
    //   • *OrganizationIdStrict → org workspace; rows tagged with :orgId only.
    // Post-V261 every user-scoped row has a non-null organization_id (personal
    // workspaces resolve to the user's auto-provisioned personal org), so the
    // pre-V261 *TenantIdAndOrganizationIdIsNull pair was removed.
    // ──────────────────────────────────────────────────────────────────────

    @Query("SELECT t FROM AgentTaskEntity t WHERE t.id = :id AND t.organizationId = :orgId")
    Optional<AgentTaskEntity> findByIdAndOrganizationIdStrict(
            @Param("id") UUID id, @Param("orgId") String orgId);

    @Query("SELECT t FROM AgentTaskEntity t "
         + "WHERE t.organizationId = :orgId AND t.assignedToAgentId = :agentId "
         + "AND t.status IN :statuses ORDER BY t.createdAt DESC")
    List<AgentTaskEntity> findAssignedInboxByOrganizationIdStrict(
            @Param("orgId") String orgId,
            @Param("agentId") UUID agentId,
            @Param("statuses") Collection<String> statuses,
            Pageable pageable);

    @Query("SELECT t FROM AgentTaskEntity t "
         + "WHERE t.organizationId = :orgId AND t.createdByAgentId = :agentId "
         + "ORDER BY t.createdAt DESC")
    List<AgentTaskEntity> findOutboxByOrganizationIdStrict(
            @Param("orgId") String orgId,
            @Param("agentId") UUID agentId,
            Pageable pageable);

    /**
     * PR23 R2 - strict-org outbox WITH status filter applied at SQL level.
     * <p>
     * Legacy {@code findByTenantIdAndCreatedByAgentIdAndStatusOrderByCreatedAtDesc}
     * applied {@code status = :status} in WHERE before LIMIT, so a 50-row request
     * always returned up to 50 rows matching the status. PR23 round-1 filtered
     * post-fetch in-Java, which broke pagination when the most-recent N rows did
     * not match - a real functional regression flagged by reviewer B.
     * This finder restores the SQL-level filter on the org-strict path.
     */
    @Query("SELECT t FROM AgentTaskEntity t "
         + "WHERE t.organizationId = :orgId AND t.createdByAgentId = :agentId "
         + "AND t.status = :status ORDER BY t.createdAt DESC")
    List<AgentTaskEntity> findOutboxByOrganizationIdStrictAndStatus(
            @Param("orgId") String orgId,
            @Param("agentId") UUID agentId,
            @Param("status") String status,
            Pageable pageable);

    // ------------------------------------------------------------------
    // Backlog (unassigned)
    // ------------------------------------------------------------------

    @Query("""
        SELECT t FROM AgentTaskEntity t
         WHERE t.tenantId = :tenantId
           AND t.assignedToAgentId IS NULL
           AND t.status = 'pending'
         ORDER BY CASE t.priority
                    WHEN 'urgent' THEN 0
                    WHEN 'high'   THEN 1
                    WHEN 'normal' THEN 2
                    WHEN 'low'    THEN 3
                    ELSE 4 END ASC,
                  t.createdAt ASC
    """)
    List<AgentTaskEntity> findBacklog(@Param("tenantId") String tenantId, Pageable pageable);

    /**
     * PR23 - strict-org backlog. Returns ONLY rows tagged with the given org;
     * never matches NULL organization_id. Membership is asserted upstream by the
     * gateway via X-Organization-Role; the workspace boundary is the orgId alone.
     */
    @Query("""
        SELECT t FROM AgentTaskEntity t
         WHERE t.organizationId = :orgId
           AND t.assignedToAgentId IS NULL
           AND t.status = 'pending'
         ORDER BY CASE t.priority
                    WHEN 'urgent' THEN 0
                    WHEN 'high'   THEN 1
                    WHEN 'normal' THEN 2
                    WHEN 'low'    THEN 3
                    ELSE 4 END ASC,
                  t.createdAt ASC
    """)
    List<AgentTaskEntity> findBacklogByOrganizationIdStrict(@Param("orgId") String orgId, Pageable pageable);

    @Query("""
        SELECT COUNT(t) FROM AgentTaskEntity t
         WHERE t.tenantId = :tenantId
           AND t.assignedToAgentId IS NULL
           AND t.status = 'pending'
    """)
    long countBacklog(@Param("tenantId") String tenantId);

    @Query("""
        SELECT COUNT(t) FROM AgentTaskEntity t
         WHERE t.organizationId = :orgId
           AND t.assignedToAgentId IS NULL
           AND t.status = 'pending'
    """)
    long countBacklogByOrganizationIdStrict(@Param("orgId") String orgId);

    // ------------------------------------------------------------------
    // Prompt summary counts (for system prompt injection)
    // ------------------------------------------------------------------

    @Query("""
        SELECT COUNT(t) FROM AgentTaskEntity t
         WHERE t.tenantId = :tenantId
           AND t.assignedToAgentId = :agentId
           AND t.status IN ('pending','in_progress','in_review')
    """)
    long countActiveInbox(@Param("tenantId") String tenantId, @Param("agentId") UUID agentId);

    // Audit 2026-05-17 round-4 - org-aware variants for system-prompt summary
    // counts. Mirror the backlog pattern (V217+PR23). Used by
    // AgentTaskService.getTaskSummaryForPrompt to inject org-scoped task
    // context into sub-agent prompts without leaking cross-workspace counts.
    @Query("""
        SELECT COUNT(t) FROM AgentTaskEntity t
         WHERE t.organizationId = :orgId
           AND t.assignedToAgentId = :agentId
           AND t.status IN ('pending','in_progress','in_review')
    """)
    long countActiveInboxByOrganizationIdStrict(@Param("orgId") String orgId,
                                                 @Param("agentId") UUID agentId);

    @Query("""
        SELECT COUNT(t) FROM AgentTaskEntity t
         WHERE t.tenantId = :tenantId
           AND t.createdByAgentId = :agentId
           AND t.status = 'completed'
           AND t.completedAt >= :since
    """)
    long countCompletedOutboxSince(@Param("tenantId") String tenantId,
                                   @Param("agentId") UUID agentId,
                                   @Param("since") Instant since);

    @Query("""
        SELECT COUNT(t) FROM AgentTaskEntity t
         WHERE t.organizationId = :orgId
           AND t.createdByAgentId = :agentId
           AND t.status = 'completed'
           AND t.completedAt >= :since
    """)
    long countCompletedOutboxSinceByOrganizationIdStrict(@Param("orgId") String orgId,
                                                          @Param("agentId") UUID agentId,
                                                          @Param("since") Instant since);

    /** Count tasks awaiting this agent's review. */
    @Query("""
        SELECT COUNT(t) FROM AgentTaskEntity t
         WHERE t.tenantId = :tenantId
           AND t.reviewerAgentId = :agentId
           AND t.status = 'in_review'
    """)
    long countPendingReviews(@Param("tenantId") String tenantId,
                             @Param("agentId") UUID agentId);

    @Query("""
        SELECT COUNT(t) FROM AgentTaskEntity t
         WHERE t.organizationId = :orgId
           AND t.reviewerAgentId = :agentId
           AND t.status = 'in_review'
    """)
    long countPendingReviewsByOrganizationIdStrict(@Param("orgId") String orgId,
                                                    @Param("agentId") UUID agentId);

    /** List tasks awaiting this agent's review. */
    @Query("""
        SELECT t FROM AgentTaskEntity t
         WHERE t.tenantId = :tenantId
           AND t.reviewerAgentId = :agentId
           AND t.status = 'in_review'
         ORDER BY CASE t.priority
                    WHEN 'urgent' THEN 0
                    WHEN 'high'   THEN 1
                    WHEN 'normal' THEN 2
                    WHEN 'low'    THEN 3
                    ELSE 4 END ASC,
                  t.updatedAt ASC
    """)
    List<AgentTaskEntity> findPendingReviews(@Param("tenantId") String tenantId,
                                              @Param("agentId") UUID agentId,
                                              Pageable pageable);

    /** PR23 - strict-org pending reviews. */
    @Query("""
        SELECT t FROM AgentTaskEntity t
         WHERE t.organizationId = :orgId
           AND t.reviewerAgentId = :agentId
           AND t.status = 'in_review'
         ORDER BY CASE t.priority
                    WHEN 'urgent' THEN 0
                    WHEN 'high'   THEN 1
                    WHEN 'normal' THEN 2
                    WHEN 'low'    THEN 3
                    ELSE 4 END ASC,
                  t.updatedAt ASC
    """)
    List<AgentTaskEntity> findPendingReviewsByOrganizationIdStrict(
            @Param("orgId") String orgId,
            @Param("agentId") UUID agentId,
            Pageable pageable);

    // ------------------------------------------------------------------
    // Optimistic state transitions
    // ------------------------------------------------------------------

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.assignedToAgentId = :agentId,
               t.status = 'in_progress',
               t.startedAt = CURRENT_TIMESTAMP,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.organizationId = :organizationId
           AND t.assignedToAgentId IS NULL
           AND t.status = 'pending'
    """)
    int claimIfAvailableByOrganizationId(@Param("taskId") UUID taskId,
                                          @Param("organizationId") String organizationId,
                                          @Param("agentId") UUID agentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'in_progress',
               t.startedAt = COALESCE(t.startedAt, CURRENT_TIMESTAMP),
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.assignedToAgentId = :agentId
           AND t.status = 'pending'
    """)
    int promoteToInProgress(@Param("taskId") UUID taskId,
                            @Param("tenantId") String tenantId,
                            @Param("agentId") UUID agentId);

    /**
     * Batch A (2026-05-20) - org-aware promote. Adds {@code organization_id}
     * to the CAS predicate so a cross-workspace caller with the right (taskId,
     * tenantId, agentId) cannot trigger the transition. Preferred entrypoint
     * for new code; the tenant-only variant above remains for legacy callers
     * pending Phase 11 removal.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'in_progress',
               t.startedAt = COALESCE(t.startedAt, CURRENT_TIMESTAMP),
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.organizationId = :organizationId
           AND t.assignedToAgentId = :agentId
           AND t.status = 'pending'
    """)
    int promoteToInProgressByOrganizationIdStrict(@Param("taskId") UUID taskId,
                                                   @Param("tenantId") String tenantId,
                                                   @Param("organizationId") String organizationId,
                                                   @Param("agentId") UUID agentId);

    /**
     * Complete → always in_review (regardless of reviewer).
     * The reviewer (agent or user) will approve or reject.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'in_review',
               t.result = :result,
               t.reviewerExecutionId = NULL,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.assignedToAgentId = :agentId
           AND t.status = 'in_progress'
    """)
    int submitForReview(@Param("taskId") UUID taskId,
                        @Param("tenantId") String tenantId,
                        @Param("agentId") UUID agentId,
                        @Param("result") String result);

    /**
     * Batch A (2026-05-20) - org-aware submitForReview. Adds {@code organization_id}
     * to the CAS predicate so a cross-workspace caller with the right (taskId,
     * tenantId, agentId) cannot land a review submission.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'in_review',
               t.result = :result,
               t.reviewerExecutionId = NULL,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.organizationId = :organizationId
           AND t.assignedToAgentId = :agentId
           AND t.status = 'in_progress'
    """)
    int submitForReviewByOrganizationIdStrict(@Param("taskId") UUID taskId,
                                               @Param("tenantId") String tenantId,
                                               @Param("organizationId") String organizationId,
                                               @Param("agentId") UUID agentId,
                                               @Param("result") String result);

    /**
     * Reject (fail) → always in_review with error (regardless of reviewer).
     * The reviewer (agent or user) will approve or reject.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'in_review',
               t.errorMessage = :reason,
               t.reviewerExecutionId = NULL,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.assignedToAgentId = :agentId
           AND t.status = 'in_progress'
    """)
    int submitFailureForReview(@Param("taskId") UUID taskId,
                                @Param("tenantId") String tenantId,
                                @Param("agentId") UUID agentId,
                                @Param("reason") String reason);

    /**
     * Batch A (2026-05-20) - org-aware submitFailureForReview. Adds
     * {@code organization_id} to the CAS predicate so a cross-workspace caller
     * cannot land a review-failure submission.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'in_review',
               t.errorMessage = :reason,
               t.reviewerExecutionId = NULL,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.organizationId = :organizationId
           AND t.assignedToAgentId = :agentId
           AND t.status = 'in_progress'
    """)
    int submitFailureForReviewByOrganizationIdStrict(@Param("taskId") UUID taskId,
                                                      @Param("tenantId") String tenantId,
                                                      @Param("organizationId") String organizationId,
                                                      @Param("agentId") UUID agentId,
                                                      @Param("reason") String reason);

    /**
     * Infrastructure failure → terminal 'failed' status (no reviewer cycle).
     * Used when the worker's execution crashes before it can call task_complete/task_reject.
     * See bug #6 in TASK_TEST_ERRORS.md - previously this went through submitFailureForReview
     * which wrongly put the task into in_review (stuck state, no reviewer trigger).
     */
    @Modifying
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'failed',
               t.errorMessage = :reason,
               t.completedAt = CURRENT_TIMESTAMP,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.status = 'in_progress'
    """)
    int markExecutionFailed(@Param("taskId") UUID taskId,
                            @Param("tenantId") String tenantId,
                            @Param("reason") String reason);

    /**
     * Dedup lookup - find a recent open task created by the same agent, assigned to the
     * same target, with the same title. Used by {@code handleAssign} to prevent
     * weak LLMs from spamming identical delegations (bug #9 in TASK_TEST_ERRORS.md).
     * "Open" = not yet terminal.
     */
    @Query("""
        SELECT t FROM AgentTaskEntity t
         WHERE t.tenantId = :tenantId
           AND t.createdByAgentId = :createdByAgentId
           AND t.assignedToAgentId = :assignedToAgentId
           AND LOWER(t.title) = LOWER(:title)
           AND t.status IN ('pending', 'in_progress', 'in_review')
           AND t.createdAt > :createdAfter
         ORDER BY t.createdAt DESC
    """)
    List<AgentTaskEntity> findRecentDuplicateAssigns(@Param("tenantId") String tenantId,
                                                    @Param("createdByAgentId") UUID createdByAgentId,
                                                    @Param("assignedToAgentId") UUID assignedToAgentId,
                                                    @Param("title") String title,
                                                    @Param("createdAfter") Instant createdAfter,
                                                    Pageable pageable);

    /**
     * Sweeper query - find in_review tasks whose retry schedule may have been lost
     * (e.g., service restart). Returns stale tasks with a reviewer set; the caller
     * (service loop) decides whether to re-run the reviewer or auto-approve based on
     * {@code reviewAttemptCount}. The {@code updatedAt < staleBefore} filter excludes
     * tasks with an in-flight retry that bumped updatedAt recently.
     * See bug #7 sweeper in TASK_TEST_ERRORS.md.
     */
    @Query("""
        SELECT t FROM AgentTaskEntity t
         WHERE t.status = 'in_review'
           AND t.reviewerAgentId IS NOT NULL
           AND t.updatedAt < :staleBefore
         ORDER BY t.updatedAt ASC
    """)
    List<AgentTaskEntity> findStuckInReviewTasks(@Param("staleBefore") Instant staleBefore,
                                                 Pageable pageable);

    /**
     * Backstop reaper query - in_progress tasks whose row has had NO terminal update for longer
     * than the (generous) stale window. A real assignee run transitions the task on completion, so
     * a task still in_progress long past the maximum agent executionTimeout is orphaned: the worker
     * thread or the synchronous downstream call was lost (pod death, dropped connection) and the
     * in-loop inactivity watchdog never got a chance to return a failure. The companion
     * {@code markExecutionFailed} CAS (status='in_progress' guard) makes the auto-fail race-safe.
     */
    @Query("""
        SELECT t FROM AgentTaskEntity t
         WHERE t.status = 'in_progress'
           AND t.updatedAt < :staleBefore
         ORDER BY t.updatedAt ASC
    """)
    List<AgentTaskEntity> findStuckInProgressTasks(@Param("staleBefore") Instant staleBefore,
                                                   Pageable pageable);

    /**
     * Trash retention sweep: ids of tasks soft-deleted before {@code cutoff}. Drives
     * {@code TaskRetentionPurger} (hard-purge of the board's Deleted column after the
     * retention window). Returns ids (not entities) so the purger can isolate each
     * delete in its own transaction. Oldest first; {@code Pageable} caps the batch.
     */
    @Query("""
        SELECT t.id FROM AgentTaskEntity t
         WHERE t.status = 'deleted'
           AND t.deletedAt < :cutoff
         ORDER BY t.deletedAt ASC
    """)
    List<UUID> findExpiredDeletedIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** Reviewer approves → completed. Clears errorMessage in case task was submitted via failure path. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'completed',
               t.errorMessage = NULL,
               t.reviewerExecutionId = NULL,
               t.completedAt = CURRENT_TIMESTAMP,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.reviewerAgentId = :reviewerAgentId
           AND t.reviewerExecutionId IS NULL
           AND t.status = 'in_review'
    """)
    int approveIfReviewer(@Param("taskId") UUID taskId,
                          @Param("tenantId") String tenantId,
                          @Param("reviewerAgentId") UUID reviewerAgentId);

    /** Reviewer approves, scoped to the execution token that started this review cycle. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'completed',
               t.errorMessage = NULL,
               t.reviewerExecutionId = NULL,
               t.completedAt = CURRENT_TIMESTAMP,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.reviewerAgentId = :reviewerAgentId
           AND t.reviewerExecutionId = :reviewerExecutionId
           AND t.status = 'in_review'
    """)
    int approveIfReviewerExecution(@Param("taskId") UUID taskId,
                                   @Param("tenantId") String tenantId,
                                   @Param("reviewerAgentId") UUID reviewerAgentId,
                                   @Param("reviewerExecutionId") UUID reviewerExecutionId);

    /** Tenant owner approves review, including reviewer-agent tasks. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'completed',
               t.errorMessage = NULL,
               t.reviewerExecutionId = NULL,
               t.completedAt = CURRENT_TIMESTAMP,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.status = 'in_review'
    """)
    int approveByTenantOwner(@Param("taskId") UUID taskId,
                              @Param("tenantId") String tenantId);

    /** Reviewer rejects → back to in_progress. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'in_progress',
               t.result = NULL,
               t.errorMessage = :reason,
               t.reviewerExecutionId = NULL,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.reviewerAgentId = :reviewerAgentId
           AND t.reviewerExecutionId IS NULL
           AND t.status = 'in_review'
    """)
    int rejectReviewIfReviewer(@Param("taskId") UUID taskId,
                                @Param("tenantId") String tenantId,
                                @Param("reviewerAgentId") UUID reviewerAgentId,
                                @Param("reason") String reason);

    /** Reviewer rejects, scoped to the execution token that started this review cycle. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'in_progress',
               t.result = NULL,
               t.errorMessage = :reason,
               t.reviewerExecutionId = NULL,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.reviewerAgentId = :reviewerAgentId
           AND t.reviewerExecutionId = :reviewerExecutionId
           AND t.status = 'in_review'
    """)
    int rejectReviewIfReviewerExecution(@Param("taskId") UUID taskId,
                                        @Param("tenantId") String tenantId,
                                        @Param("reviewerAgentId") UUID reviewerAgentId,
                                        @Param("reviewerExecutionId") UUID reviewerExecutionId,
                                        @Param("reason") String reason);

    /**
     * Reviewer (or the reviewer-failure path) terminally fails a task that was in_review.
     * Used when the reject loop cap is hit - the work was never validated, so we mark the
     * task {@code failed} with the last reviewer feedback instead of silently auto-approving.
     * Preserves the worker's last {@code result} as evidence of what was rejected.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'failed',
               t.errorMessage = :reason,
               t.reviewerExecutionId = NULL,
               t.completedAt = CURRENT_TIMESTAMP,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.reviewerAgentId = :reviewerAgentId
           AND t.reviewerExecutionId IS NULL
           AND t.status = 'in_review'
    """)
    int failReviewIfReviewer(@Param("taskId") UUID taskId,
                              @Param("tenantId") String tenantId,
                              @Param("reviewerAgentId") UUID reviewerAgentId,
                              @Param("reason") String reason);

    /** Reviewer auto-fails, scoped to the execution token that started this review cycle. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'failed',
               t.errorMessage = :reason,
               t.reviewerExecutionId = NULL,
               t.completedAt = CURRENT_TIMESTAMP,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.reviewerAgentId = :reviewerAgentId
           AND t.reviewerExecutionId = :reviewerExecutionId
           AND t.status = 'in_review'
    """)
    int failReviewIfReviewerExecution(@Param("taskId") UUID taskId,
                                      @Param("tenantId") String tenantId,
                                      @Param("reviewerAgentId") UUID reviewerAgentId,
                                      @Param("reviewerExecutionId") UUID reviewerExecutionId,
                                      @Param("reason") String reason);

    /** Tenant owner rejects review, including reviewer-agent tasks. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AgentTaskEntity t
           SET t.status = 'in_progress',
               t.result = NULL,
               t.errorMessage = :reason,
               t.reviewerExecutionId = NULL,
               t.updatedAt = CURRENT_TIMESTAMP
         WHERE t.id = :taskId
           AND t.tenantId = :tenantId
           AND t.status = 'in_review'
    """)
    int rejectReviewByTenantOwner(@Param("taskId") UUID taskId,
                                   @Param("tenantId") String tenantId,
                                   @Param("reason") String reason);

    // ------------------------------------------------------------------
    // Cascading cancel - recursive CTE via native SQL
    // ------------------------------------------------------------------

    @Modifying
    @Query(nativeQuery = true, value = """
        WITH RECURSIVE descendants AS (
            SELECT id
              FROM agent.agent_tasks
             WHERE id = CAST(:rootId AS uuid)
               AND tenant_id = :tenantId
               AND (status IN ('pending','in_progress','in_review')
                    OR status IN (SELECT ts.key FROM agent.task_statuses ts
                                   WHERE ts.tenant_id = :tenantId
                                     AND ts.category IN ('pending','in_progress','in_review')))
            UNION ALL
            SELECT t.id
              FROM agent.agent_tasks t
              JOIN descendants d ON t.parent_task_id = d.id
             WHERE t.tenant_id = :tenantId
               AND (t.status IN ('pending','in_progress','in_review')
                    OR t.status IN (SELECT ts.key FROM agent.task_statuses ts
                                     WHERE ts.tenant_id = :tenantId
                                       AND ts.category IN ('pending','in_progress','in_review')))
        )
        UPDATE agent.agent_tasks t
           SET status        = 'cancelled',
               error_message = CASE WHEN :reason IS NULL THEN t.error_message ELSE :reason END,
               completed_at  = now(),
               updated_at    = now()
          FROM descendants d
         WHERE t.id = d.id
    """)
    int cascadingCancel(@Param("rootId") UUID rootId,
                        @Param("tenantId") String tenantId,
                        @Param("reason") String reason);

    // V264 (2026-05-20) aligned agent.agent_tasks.organization_id to VARCHAR(255).
    // A prior CAST(:organizationId AS uuid) here threw "operator does not exist:
    // character varying = uuid" - PG has no implicit cast in equality. The param
    // is already a String, the column is varchar; direct equality works.
    @Modifying
    @Query(nativeQuery = true, value = """
        WITH RECURSIVE descendants AS (
            SELECT id
              FROM agent.agent_tasks
             WHERE id = CAST(:rootId AS uuid)
               AND tenant_id = :tenantId
               AND organization_id = :organizationId
               AND (status IN ('pending','in_progress','in_review')
                    OR status IN (SELECT ts.key FROM agent.task_statuses ts
                                   WHERE ts.tenant_id = :tenantId
                                     AND ts.organization_id = :organizationId
                                     AND ts.category IN ('pending','in_progress','in_review')))
            UNION ALL
            SELECT t.id
              FROM agent.agent_tasks t
              JOIN descendants d ON t.parent_task_id = d.id
             WHERE t.tenant_id = :tenantId
               AND t.organization_id = :organizationId
               AND (t.status IN ('pending','in_progress','in_review')
                    OR t.status IN (SELECT ts.key FROM agent.task_statuses ts
                                     WHERE ts.tenant_id = :tenantId
                                       AND ts.organization_id = :organizationId
                                       AND ts.category IN ('pending','in_progress','in_review')))
        )
        UPDATE agent.agent_tasks t
           SET status        = 'cancelled',
               error_message = CASE WHEN :reason IS NULL THEN t.error_message ELSE :reason END,
               completed_at  = now(),
               updated_at    = now()
          FROM descendants d
         WHERE t.id = d.id
    """)
    int cascadingCancelInOrganization(@Param("rootId") UUID rootId,
                                      @Param("tenantId") String tenantId,
                                      @Param("organizationId") String organizationId,
                                      @Param("reason") String reason);

    // ------------------------------------------------------------------
    // Schedule prompt replacement - inbox for a specific agent
    // ------------------------------------------------------------------

    @Query("""
        SELECT t FROM AgentTaskEntity t
         WHERE t.tenantId = :tenantId
           AND t.assignedToAgentId = :agentId
           AND t.status IN ('pending','in_progress','in_review')
         ORDER BY CASE t.priority
                    WHEN 'urgent' THEN 0
                    WHEN 'high'   THEN 1
                    WHEN 'normal' THEN 2
                    WHEN 'low'    THEN 3
                    ELSE 4 END ASC,
                  t.createdAt ASC
    """)
    List<AgentTaskEntity> findActiveInboxPrioritized(@Param("tenantId") String tenantId,
                                                     @Param("agentId") UUID agentId,
                                                     Pageable pageable);

    /**
     * PR26 - strict-org active inbox. Returns ONLY rows tagged with the given org;
     * never matches NULL organization_id. Mirror of findBacklogByOrganizationIdStrict.
     * Closes the cross-scope task bleed in ScheduledTaskPromptBuilder:
     * pre-PR26, a personal agent's schedule fire returned org-tagged tasks
     * (and vice versa) because findActiveInboxPrioritized filtered on tenantId only.
     */
    @Query("""
        SELECT t FROM AgentTaskEntity t
         WHERE t.organizationId = :orgId
           AND t.assignedToAgentId = :agentId
           AND t.status IN ('pending','in_progress','in_review')
         ORDER BY CASE t.priority
                    WHEN 'urgent' THEN 0
                    WHEN 'high'   THEN 1
                    WHEN 'normal' THEN 2
                    WHEN 'low'    THEN 3
                    ELSE 4 END ASC,
                  t.createdAt ASC
    """)
    List<AgentTaskEntity> findActiveInboxByOrganizationIdStrict(
            @Param("orgId") String orgId,
            @Param("agentId") UUID agentId,
            Pageable pageable);

    // ------------------------------------------------------------------
    // Board queries (tenant-scoped, no agent perspective)
    // ------------------------------------------------------------------

    /** Count tasks by status for the tenant (board stats). */
    @Query("""
        SELECT t.status, COUNT(t) FROM AgentTaskEntity t
         WHERE t.tenantId = :tenantId
         GROUP BY t.status
    """)
    List<Object[]> countByStatusGrouped(@Param("tenantId") String tenantId);

    /** PR23 - strict-org board stats. */
    @Query("""
        SELECT t.status, COUNT(t) FROM AgentTaskEntity t
         WHERE t.organizationId = :orgId
         GROUP BY t.status
    """)
    List<Object[]> countByStatusGroupedByOrganizationIdStrict(@Param("orgId") String orgId);

    /**
     * Relocate every task in {@code fromKey} to {@code toKey} on a single board,
     * used when a custom column is deleted so its cards are never orphaned. Board
     * scope is null-org-safe (personal workspace = organization_id IS NULL).
     * Returns the number of tasks moved.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE agent.agent_tasks
           SET status = :toKey, updated_at = now()
         WHERE tenant_id = :tenantId
           AND ((CAST(:orgId AS text) IS NULL AND organization_id IS NULL) OR organization_id = :orgId)
           AND status = :fromKey
    """)
    int relocateStatusKey(@Param("tenantId") String tenantId,
                          @Param("orgId") String orgId,
                          @Param("fromKey") String fromKey,
                          @Param("toKey") String toKey);

    /**
     * Scrub a deleted label's id from every task that carries it on a board (F2),
     * so deleting a label leaves no dangling reference. Null-org-safe. Uses
     * {@code jsonb_exists} (not the {@code ?} operator, which collides with the
     * JDBC placeholder) to touch only rows that actually hold the id. Returns the
     * number of tasks scrubbed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE agent.agent_tasks
           SET label_ids = label_ids - :labelId, updated_at = now()
         WHERE tenant_id = :tenantId
           AND ((CAST(:orgId AS text) IS NULL AND organization_id IS NULL) OR organization_id = :orgId)
           AND jsonb_exists(label_ids, :labelId)
    """)
    int removeLabelFromTasks(@Param("tenantId") String tenantId,
                             @Param("orgId") String orgId,
                             @Param("labelId") String labelId);

    /** List all tenant tasks with optional filters via native query. */
    @Query(nativeQuery = true, value = """
        SELECT * FROM agent.agent_tasks t
         WHERE t.tenant_id = :tenantId
           AND (:status IS NULL OR t.status = ANY(string_to_array(:status, ',')))
           AND (:unassigned = false OR t.assigned_to_agent_id IS NULL)
           AND (:assignedTo IS NULL OR t.assigned_to_agent_id = CAST(:assignedTo AS uuid))
           AND (:createdBy IS NULL OR t.created_by_agent_id = CAST(:createdBy AS uuid))
           AND (:priority IS NULL OR t.priority = :priority)
           AND (:search IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :search, '%')))
           AND (:parentTaskId IS NULL OR t.parent_task_id = CAST(:parentTaskId AS uuid))
         ORDER BY
           CASE WHEN :sort = 'priority' THEN
             CASE t.priority WHEN 'urgent' THEN '0' WHEN 'high' THEN '1' WHEN 'normal' THEN '2' WHEN 'low' THEN '3' ELSE '4' END
           ELSE NULL END ASC,
           CASE WHEN :sort = 'due_by' THEN CAST(t.due_by AS varchar) ELSE NULL END ASC,
           CASE WHEN :sort = 'created_at' THEN CAST(t.created_at AS varchar) ELSE NULL END DESC,
           CASE WHEN :sort = 'manual' THEN t.board_rank ELSE NULL END ASC,
           t.updated_at DESC
         LIMIT :size OFFSET :offset
    """)
    List<AgentTaskEntity> findAllFiltered(
            @Param("tenantId") String tenantId,
            @Param("status") String status,
            @Param("unassigned") boolean unassigned,
            @Param("assignedTo") String assignedTo,
            @Param("createdBy") String createdBy,
            @Param("priority") String priority,
            @Param("search") String search,
            @Param("parentTaskId") String parentTaskId,
            @Param("sort") String sort,
            @Param("size") int size,
            @Param("offset") int offset);

    @Query(nativeQuery = true, value = """
        SELECT COUNT(*) FROM agent.agent_tasks t
         WHERE t.tenant_id = :tenantId
           AND (:status IS NULL OR t.status = ANY(string_to_array(:status, ',')))
           AND (:unassigned = false OR t.assigned_to_agent_id IS NULL)
           AND (:assignedTo IS NULL OR t.assigned_to_agent_id = CAST(:assignedTo AS uuid))
           AND (:createdBy IS NULL OR t.created_by_agent_id = CAST(:createdBy AS uuid))
           AND (:priority IS NULL OR t.priority = :priority)
           AND (:search IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :search, '%')))
           AND (:parentTaskId IS NULL OR t.parent_task_id = CAST(:parentTaskId AS uuid))
    """)
    long countAllFiltered(
            @Param("tenantId") String tenantId,
            @Param("status") String status,
            @Param("unassigned") boolean unassigned,
            @Param("assignedTo") String assignedTo,
            @Param("createdBy") String createdBy,
            @Param("priority") String priority,
            @Param("search") String search,
            @Param("parentTaskId") String parentTaskId);

    /**
     * PR23 - strict-org board listing. Same filter shape as {@link #findAllFiltered}
     * but scoped to a workspace: returns ONLY rows tagged with the given org.
     * Membership is asserted upstream by the gateway via X-Organization-Role.
     */
    @Query(nativeQuery = true, value = """
        SELECT * FROM agent.agent_tasks t
         WHERE t.organization_id = :orgId
           AND (:status IS NULL OR t.status = ANY(string_to_array(:status, ',')))
           AND (:unassigned = false OR t.assigned_to_agent_id IS NULL)
           AND (:assignedTo IS NULL OR t.assigned_to_agent_id = CAST(:assignedTo AS uuid))
           AND (:createdBy IS NULL OR t.created_by_agent_id = CAST(:createdBy AS uuid))
           AND (:priority IS NULL OR t.priority = :priority)
           AND (:search IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :search, '%')))
           AND (:parentTaskId IS NULL OR t.parent_task_id = CAST(:parentTaskId AS uuid))
         ORDER BY
           CASE WHEN :sort = 'priority' THEN
             CASE t.priority WHEN 'urgent' THEN '0' WHEN 'high' THEN '1' WHEN 'normal' THEN '2' WHEN 'low' THEN '3' ELSE '4' END
           ELSE NULL END ASC,
           CASE WHEN :sort = 'due_by' THEN CAST(t.due_by AS varchar) ELSE NULL END ASC,
           CASE WHEN :sort = 'created_at' THEN CAST(t.created_at AS varchar) ELSE NULL END DESC,
           CASE WHEN :sort = 'manual' THEN t.board_rank ELSE NULL END ASC,
           t.updated_at DESC
         LIMIT :size OFFSET :offset
    """)
    List<AgentTaskEntity> findAllFilteredByOrganizationIdStrict(
            @Param("orgId") String orgId,
            @Param("status") String status,
            @Param("unassigned") boolean unassigned,
            @Param("assignedTo") String assignedTo,
            @Param("createdBy") String createdBy,
            @Param("priority") String priority,
            @Param("search") String search,
            @Param("parentTaskId") String parentTaskId,
            @Param("sort") String sort,
            @Param("size") int size,
            @Param("offset") int offset);

    @Query(nativeQuery = true, value = """
        SELECT COUNT(*) FROM agent.agent_tasks t
         WHERE t.organization_id = :orgId
           AND (:status IS NULL OR t.status = ANY(string_to_array(:status, ',')))
           AND (:unassigned = false OR t.assigned_to_agent_id IS NULL)
           AND (:assignedTo IS NULL OR t.assigned_to_agent_id = CAST(:assignedTo AS uuid))
           AND (:createdBy IS NULL OR t.created_by_agent_id = CAST(:createdBy AS uuid))
           AND (:priority IS NULL OR t.priority = :priority)
           AND (:search IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :search, '%')))
           AND (:parentTaskId IS NULL OR t.parent_task_id = CAST(:parentTaskId AS uuid))
    """)
    long countAllFilteredByOrganizationIdStrict(
            @Param("orgId") String orgId,
            @Param("status") String status,
            @Param("unassigned") boolean unassigned,
            @Param("assignedTo") String assignedTo,
            @Param("createdBy") String createdBy,
            @Param("priority") String priority,
            @Param("search") String search,
            @Param("parentTaskId") String parentTaskId);

    /** Direct children of a task. */
    List<AgentTaskEntity> findByTenantIdAndParentTaskIdOrderByCreatedAtAsc(String tenantId, UUID parentTaskId);

    /** PR23 - strict-org children of a task. */
    @Query("""
        SELECT t FROM AgentTaskEntity t
         WHERE t.organizationId = :orgId
           AND t.parentTaskId = :parentTaskId
         ORDER BY t.createdAt ASC
    """)
    List<AgentTaskEntity> findByParentTaskIdAndOrganizationIdStrict(
            @Param("orgId") String orgId,
            @Param("parentTaskId") UUID parentTaskId);

    /** Check if a task has any non-terminal children (pending, in_progress, in_review). */
    @Query("""
        SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END
          FROM AgentTaskEntity t
         WHERE t.parentTaskId = :parentTaskId
           AND t.tenantId = :tenantId
           AND t.status IN ('pending','in_progress','in_review')
    """)
    boolean hasActiveChildren(@Param("parentTaskId") UUID parentTaskId,
                              @Param("tenantId") String tenantId);

    /** Cancel all non-terminal descendants of a task (NOT the task itself). */
    @Modifying
    @Query(nativeQuery = true, value = """
        WITH RECURSIVE descendants AS (
            SELECT t.id
              FROM agent.agent_tasks t
             WHERE t.parent_task_id = CAST(:rootId AS uuid)
               AND t.tenant_id = :tenantId
               AND (t.status IN ('pending','in_progress','in_review')
                    OR t.status IN (SELECT ts.key FROM agent.task_statuses ts
                                     WHERE ts.tenant_id = :tenantId
                                       AND ts.category IN ('pending','in_progress','in_review')))
            UNION ALL
            SELECT t.id
              FROM agent.agent_tasks t
              JOIN descendants d ON t.parent_task_id = d.id
             WHERE t.tenant_id = :tenantId
               AND (t.status IN ('pending','in_progress','in_review')
                    OR t.status IN (SELECT ts.key FROM agent.task_statuses ts
                                     WHERE ts.tenant_id = :tenantId
                                       AND ts.category IN ('pending','in_progress','in_review')))
        )
        UPDATE agent.agent_tasks t
           SET status        = 'cancelled',
               error_message = CASE WHEN :reason IS NULL THEN t.error_message ELSE :reason END,
               completed_at  = now(),
               updated_at    = now()
          FROM descendants d
         WHERE t.id = d.id
    """)
    int cascadeCancelChildren(@Param("rootId") UUID rootId,
                              @Param("tenantId") String tenantId,
                              @Param("reason") String reason);

    /**
     * Finds the most recent in-progress task assigned to the given agent.
     * Used to infer {@code parent_task_id} when an agent calls
     * {@code agent(action='assign')} from within its own task execution.
     * Explicit @Query to avoid PostgreSQL "could not determine data type of parameter" on derived queries.
     */
    @Query("""
        SELECT t FROM AgentTaskEntity t
         WHERE t.tenantId = :tenantId
           AND t.assignedToAgentId = :agentId
           AND t.status = :status
         ORDER BY t.startedAt DESC
         LIMIT 1
    """)
    Optional<AgentTaskEntity> findTopByTenantIdAndAssignedToAgentIdAndStatusOrderByStartedAtDesc(
            @Param("tenantId") String tenantId,
            @Param("agentId") UUID agentId,
            @Param("status") String status);

    /** Most recently started task for an agent, regardless of current status. */
    Optional<AgentTaskEntity> findTopByTenantIdAndAssignedToAgentIdAndStartedAtIsNotNullOrderByStartedAtDesc(
            String tenantId, UUID assignedToAgentId);

    // ------------------------------------------------------------------
    // Reviewer execution concurrency (CAS lock) and attempt tracking
    // ------------------------------------------------------------------

    /**
     * CAS: claim reviewer execution slot. Succeeds if no other execution holds it,
     * OR if the existing lock is stale (older than 15 minutes - crash recovery).
     *
     * Note: the TTL uses {@code updated_at} which is also bumped by status-change queries
     * (e.g. rejectReviewIfReviewer). This is safe because those queries also change the task
     * status away from {@code in_review}, and reviewers only run on {@code in_review} tasks.
     * A status change while a reviewer holds the lock means the review was resolved externally,
     * so extending the TTL has no practical impact.
     */
    @Modifying
    @Query(value = "UPDATE agent.agent_tasks SET reviewer_execution_id = :execId, updated_at = NOW() " +
            "WHERE id = :taskId AND status = 'in_review' " +
            "AND reviewer_agent_id IS NOT NULL " +
            "AND (reviewer_execution_id IS NULL OR updated_at < NOW() - INTERVAL '15 minutes')",
            nativeQuery = true)
    int tryLockReviewerExecution(@Param("taskId") UUID taskId, @Param("execId") UUID execId);

    /** Release reviewer execution slot (only succeeds if caller holds the lock). */
    @Modifying
    @Query(value = "UPDATE agent.agent_tasks SET reviewer_execution_id = NULL WHERE id = :taskId AND reviewer_execution_id = :execId", nativeQuery = true)
    int unlockReviewerExecution(@Param("taskId") UUID taskId, @Param("execId") UUID execId);

    /**
     * CAS: claim assignee (worker) execution slot. Succeeds if no other execution holds it,
     * OR if the existing lock is stale (older than 15 minutes - crash recovery). Mirrors
     * {@link #tryLockReviewerExecution} so every kickoff path (REST POST /tasks, PATCH,
     * MCP assign, recurrence, reviewer-rejection retrigger) serialises through a single
     * chokepoint and double-dispatch is impossible.
     */
    @Modifying
    @Query(value = "UPDATE agent.agent_tasks SET assignee_execution_id = :execId, updated_at = NOW() " +
            "WHERE id = :taskId AND (assignee_execution_id IS NULL OR updated_at < NOW() - INTERVAL '15 minutes')",
            nativeQuery = true)
    int tryLockAssigneeExecution(@Param("taskId") UUID taskId, @Param("execId") UUID execId);

    /** Release assignee execution slot (only succeeds if caller holds the lock). */
    @Modifying
    @Query(value = "UPDATE agent.agent_tasks SET assignee_execution_id = NULL WHERE id = :taskId AND assignee_execution_id = :execId", nativeQuery = true)
    int unlockAssigneeExecution(@Param("taskId") UUID taskId, @Param("execId") UUID execId);

    /**
     * Promote a pending task to in_progress (and stamp started_at) - the single moment
     * in the lifecycle where a task honestly transitions to "running". Called by
     * {@link com.apimarketplace.agent.service.AgentTaskService#executeAgentForTask} right
     * after the assignee CAS lock is acquired. CAS-scoped to status='pending' so repeat
     * calls (e.g. reviewer-reject re-trigger on an already-in_progress task) are no-ops.
     */
    @Modifying
    @Query(value = "UPDATE agent.agent_tasks SET status = 'in_progress', started_at = COALESCE(started_at, NOW()), updated_at = NOW() " +
            "WHERE id = :taskId AND tenant_id = :tenantId AND status = 'pending'",
            nativeQuery = true)
    int promotePendingToInProgress(@Param("taskId") UUID taskId, @Param("tenantId") String tenantId);

    /**
     * Force-clear the assignee execution lock and revert status to pending.
     * Used by stop-agent: CAS on status='in_progress' ensures no race with natural completion.
     */
    @Modifying
    @Query(value = "UPDATE agent.agent_tasks SET assignee_execution_id = NULL, status = 'pending', " +
            "updated_at = NOW() WHERE id = :taskId AND status = 'in_progress'", nativeQuery = true)
    int forceUnlockAssigneeExecution(@Param("taskId") UUID taskId);

    /**
     * Force-clear the reviewer execution lock and send the task back to in_progress.
     * Used by stop-agent: CAS on status='in_review' ensures no race with natural completion.
     */
    @Modifying
    @Query(value = "UPDATE agent.agent_tasks SET reviewer_execution_id = NULL, status = 'in_progress', " +
            "updated_at = NOW() WHERE id = :taskId AND status = 'in_review'", nativeQuery = true)
    int forceUnlockReviewerExecution(@Param("taskId") UUID taskId);

    /** Increment review attempt counter. Scoped to {tenant, reviewer} so a caller that isn't
     *  the designated reviewer cannot bump the counter and prematurely drive a task to auto-fail.
     *  Returns 0 if the task is no longer in_review OR the caller is not the reviewer. */
    @Modifying
    @Query(value = "UPDATE agent.agent_tasks SET review_attempt_count = review_attempt_count + 1 " +
            "WHERE id = :taskId AND tenant_id = :tenantId " +
            "  AND reviewer_agent_id = :reviewerAgentId AND reviewer_execution_id IS NULL " +
            "  AND status = 'in_review'", nativeQuery = true)
    int incrementReviewAttemptCount(@Param("taskId") UUID taskId,
                                    @Param("tenantId") String tenantId,
                                    @Param("reviewerAgentId") UUID reviewerAgentId);

    /** Increment reviewer attempt only for the execution token that still owns this review cycle. */
    @Modifying
    @Query(value = "UPDATE agent.agent_tasks SET review_attempt_count = review_attempt_count + 1 " +
            "WHERE id = :taskId AND tenant_id = :tenantId " +
            "  AND reviewer_agent_id = :reviewerAgentId AND reviewer_execution_id = :reviewerExecutionId " +
            "  AND status = 'in_review'", nativeQuery = true)
    int incrementReviewAttemptCountForExecution(@Param("taskId") UUID taskId,
                                                @Param("tenantId") String tenantId,
                                                @Param("reviewerAgentId") UUID reviewerAgentId,
                                                @Param("reviewerExecutionId") UUID reviewerExecutionId);
}

package com.apimarketplace.trigger.repository;

import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for scheduled executions.
 * Optimized queries use the indexed next_execution_at column.
 */
@Repository
public interface ScheduledExecutionRepository extends JpaRepository<ScheduledExecutionEntity, UUID> {

    /**
     * THE critical query for the daemon - O(1) with index.
     * Finds all dispatchable schedules that are due for execution.
     * Excludes standalone schedules not yet linked to a workflow.
     *
     * <p>P0b root-cause fix: filters on {@code state = ACTIVE} in addition to
     * the legacy {@code enabled = true} flag. Without the state predicate, an
     * ARCHIVED row whose {@code enabled} got flipped to true through any
     * upsert / direct mutation path would silently fire - contradicting the
     * documented "ARCHIVED is permanent" contract on {@link com.apimarketplace.trigger.domain.TriggerState}.
     * Both predicates are kept (state + enabled) until PR5 drops the legacy
     * boolean column; reading both is the unique source-of-truth filter.
     */
    @Query("""
        SELECT se FROM ScheduledExecutionEntity se
        WHERE se.state = com.apimarketplace.trigger.domain.TriggerState.ACTIVE
        AND se.enabled = true
        AND (se.workflowId IS NOT NULL OR se.agentEntityId IS NOT NULL)
        AND se.nextExecutionAt <= :now
        AND (se.maxExecutions IS NULL OR se.executionCount < se.maxExecutions)
        ORDER BY se.nextExecutionAt ASC
        """)
    List<ScheduledExecutionEntity> findDueExecutions(@Param("now") Instant now);

    // Strict-isolation finders - see StandaloneWebhookRepository javadoc.
    // Post-V261 the *OrganizationIdIsNull* personal-scope variants were
    // removed because every row carries a non-null organization_id.

    @Query("SELECT se FROM ScheduledExecutionEntity se "
         + "WHERE se.id = :id AND se.organizationId = :orgId")
    Optional<ScheduledExecutionEntity> findByIdAndOrganizationIdStrict(
            @Param("id") UUID id, @Param("orgId") String orgId);

    @Query("SELECT se FROM ScheduledExecutionEntity se "
         + "WHERE se.organizationId = :orgId ORDER BY se.createdAt DESC")
    List<ScheduledExecutionEntity> findAllByOrganizationIdStrict(@Param("orgId") String orgId);

    /**
     * Find by workflow ID and trigger ID (unique constraint enforced by V60 migration).
     */
    Optional<ScheduledExecutionEntity> findByWorkflowIdAndTriggerId(UUID workflowId, String triggerId);

    /**
     * List-based lookup - used by createOrUpdate to gracefully handle pre-migration duplicates.
     */
    List<ScheduledExecutionEntity> findAllByWorkflowIdAndTriggerId(UUID workflowId, String triggerId);

    /**
     * Find all schedules for a workflow.
     */
    List<ScheduledExecutionEntity> findByWorkflowId(UUID workflowId);

    /**
     * Check if schedule exists for workflow and trigger.
     */
    boolean existsByWorkflowIdAndTriggerId(UUID workflowId, String triggerId);

    /**
     * Check if any schedule exists for workflow.
     */
    boolean existsByWorkflowId(UUID workflowId);

    /** Strict-org quota count. Mirrors webhook pattern (separate org quotas). */
    @Query("""
        SELECT COUNT(se) FROM ScheduledExecutionEntity se
        WHERE se.organizationId = :orgId
        AND (se.workflowId IS NOT NULL OR se.agentEntityId IS NOT NULL)
        """)
    long countActiveByOrganizationIdStrict(@Param("orgId") String orgId);

    /**
     * Orphan reaper: rows never linked to a workflow or agent and older than cutoff.
     */
    @Query("""
        SELECT se FROM ScheduledExecutionEntity se
        WHERE se.workflowId IS NULL AND se.agentEntityId IS NULL
        AND se.createdAt < :cutoff
        """)
    List<ScheduledExecutionEntity> findOrphansOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Distinct non-null workflow IDs older than {@code ageCutoff} - used by the stale-FK
     * reaper to batch-check workflow existence against orchestrator-service via
     * {@code GET /api/internal/publication-support/workflows/exists}.
     *
     * <p>The age filter is the race guard: a row written milliseconds before the sweeper
     * runs may reference a workflow whose orchestrator-side INSERT has not yet committed
     * (writes ordered as <i>workflow row → trigger row</i> through HTTP, but the sweeper
     * doesn't observe the same transaction boundary). Skipping rows younger than the
     * cutoff (default 1h) avoids race-deleting legitimate fresh links.
     */
    @Query("SELECT DISTINCT se.workflowId FROM ScheduledExecutionEntity se " +
           "WHERE se.workflowId IS NOT NULL AND se.createdAt < :ageCutoff")
    java.util.Set<UUID> findDistinctNonNullWorkflowIds(@Param("ageCutoff") Instant ageCutoff);

    /**
     * Bulk delete by workflow IDs - invoked by the stale-FK reaper after orchestrator
     * confirms the workflows no longer exist.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ScheduledExecutionEntity se WHERE se.workflowId IN :workflowIds")
    int deleteByWorkflowIdIn(@Param("workflowIds") java.util.Collection<UUID> workflowIds);

    /**
     * Delete schedule by workflow ID (for cleanup).
     */
    void deleteByWorkflowId(UUID workflowId);

    /**
     * Delete schedules for triggers that are no longer in the workflow.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ScheduledExecutionEntity se WHERE se.workflowId = :workflowId AND se.triggerId NOT IN :triggerIds")
    void deleteByWorkflowIdAndTriggerIdNotIn(@Param("workflowId") UUID workflowId, @Param("triggerIds") List<String> triggerIds);

    /**
     * TTL cleanup - delete expired schedules.
     * Returns count of deleted rows.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ScheduledExecutionEntity se WHERE se.expiresAt IS NOT NULL AND se.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    /**
     * Find schedules that have reached their max executions and still hold the
     * armed contract ({@code state=ACTIVE && enabled=true}). Used by the
     * cleanup-expired job to archive them through {@link com.apimarketplace.trigger.service.TriggerLifecycleManager#archiveSchedule}
     * (with reason {@code MAX_EXEC_REACHED}, source {@code QUOTA}) instead of
     * a bulk UPDATE that only touches {@code enabled} and leaves
     * {@code state=ACTIVE} drift behind.
     *
     * <p>P0c root-cause fix: filters on {@code state = ACTIVE} explicitly to
     * avoid an archive-rearm-archive loop. Without the state predicate, any
     * ARCHIVED row that got {@code enabled=true} drift via the upsert path
     * would re-match this query on every cleanup tick → archiveSchedule (no
     * state change but updates {@code last_disabled_at} + emits a log line)
     * → next reactivate sync flips enabled=true again → loops forever.
     *
     * <p>Replaces the prior {@code disableCompletedSchedules} bulk JPQL
     * UPDATE which created drift rows at {@code state=ACTIVE, enabled=false}
     * - invisible to {@code armSchedule}'s ARCHIVED-only refusal guard
     * (because state is still ACTIVE), so a reactivate would silently
     * re-enable them and they'd skip dispatch on the next tick because
     * {@code executionCount &gt;= maxExecutions} still holds. Routing through
     * {@code archiveSchedule} sets {@code state=ARCHIVED}, which then is
     * refused by {@code armSchedule} as designed.
     */
    @Query("""
        SELECT se FROM ScheduledExecutionEntity se
        WHERE se.state = com.apimarketplace.trigger.domain.TriggerState.ACTIVE
        AND se.maxExecutions IS NOT NULL
        AND se.executionCount >= se.maxExecutions
        AND se.enabled = true
        """)
    List<ScheduledExecutionEntity> findCompletedActiveSchedules();

    /**
     * Atomic claim for multi-pod horizontal scaling.
     * FOR UPDATE SKIP LOCKED ensures each pod gets a disjoint set of due schedules.
     * The caller advances nextExecutionAt in the same transaction before returning.
     */
    @Query(value = """
        SELECT * FROM trigger.scheduled_executions
        WHERE state = 'ACTIVE'
        AND enabled = true
        AND (workflow_id IS NOT NULL OR agent_entity_id IS NOT NULL)
        AND next_execution_at <= :now
        AND (max_executions IS NULL OR execution_count < max_executions)
        ORDER BY next_execution_at ASC
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<ScheduledExecutionEntity> findDueForUpdate(@Param("now") Instant now);

    /**
     * Count active schedules for monitoring.
     */
    @Query("SELECT COUNT(se) FROM ScheduledExecutionEntity se WHERE se.enabled = true")
    long countActiveSchedules();

    /** Strict-org dedup. */
    @Query("SELECT se FROM ScheduledExecutionEntity se "
         + "WHERE se.organizationId = :orgId AND se.sourceNodeId = :sourceNodeId")
    Optional<ScheduledExecutionEntity> findByOrganizationIdStrictAndSourceNodeId(
            @Param("orgId") String orgId, @Param("sourceNodeId") String sourceNodeId);

    /**
     * Find org-scoped schedules by agent entity ID.
     */
    @Query("""
        SELECT se FROM ScheduledExecutionEntity se
        WHERE se.agentEntityId = :agentEntityId
        AND se.organizationId = :orgId
        ORDER BY se.createdAt ASC
        """)
    List<ScheduledExecutionEntity> findAllByAgentEntityIdAndOrganizationIdStrict(
            @Param("agentEntityId") UUID agentEntityId,
            @Param("orgId") String orgId);

    /**
     * Find all schedules for an agent entity ID (used by the scope-aware
     * cascade delete in InternalTriggerController). Audit 2026-05-16 round-3.
     */
    List<ScheduledExecutionEntity> findByAgentEntityId(UUID agentEntityId);

    /**
     * Delete schedule by agent entity ID (for cleanup when agent is deleted).
     */
    void deleteByAgentEntityId(UUID agentEntityId);
}

package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository pour les donnees d'etapes de workflow
 * Respecte les principes SOLID et les bonnes pratiques
 */
@Repository
public interface WorkflowStepDataRepository extends JpaRepository<WorkflowStepDataEntity, Long> {
    
    /**
     * Trouve toutes les donnees d'etapes pour un run donne.
     *
     * <p><b>WARNING - heavy method.</b> Loads ALL columns including {@code input_data} and
     * {@code metadata} JSONB. For long-lived workflows (17k+ rows possible) this triggers
     * the 2026-05-07 OOM shape - multi-MB heap per call. New callers should prefer a
     * projection or one of the {@code Lightweight} variants below.
     */
    List<WorkflowStepDataEntity> findByRunId(String runId);

    /**
     * Lightweight projection for split-aggregate recovery: {@code (normalizedKey, epoch, count)}
     * for tuples appearing ≥ 2 times in a run (the split-shape signature). NO JSONB columns
     * loaded - fixes the OOM shape of {@link #findByRunId} for the recovery hot path.
     *
     * <p>Used by {@code AgentRecoveryService.recoverOrphanedAggregatesForRun}.
     */
    @Query("SELECT new com.apimarketplace.orchestrator.persistence.SplitAggregateProjection(w.normalizedKey, w.epoch, COUNT(w)) " +
           "FROM WorkflowStepDataEntity w " +
           "WHERE w.runId = :runId AND w.normalizedKey IS NOT NULL AND w.epoch IS NOT NULL " +
           "GROUP BY w.normalizedKey, w.epoch " +
           "HAVING COUNT(w) >= 2")
    List<SplitAggregateProjection> findSplitAggregateProjectionsByRunId(@Param("runId") String runId);

    /**
     * Projection of {@code output_storage_id} values for a workflow run - no JSONB columns
     * loaded. Used by {@code RunCloneService} for the "collect storage IDs to clone/delete"
     * pre-pass (the actual row clone still loads full entities because every column is
     * needed for the deep copy).
     */
    @Query("SELECT w.outputStorageId FROM WorkflowStepDataEntity w WHERE w.workflowRunId = :workflowRunId AND w.outputStorageId IS NOT NULL")
    List<UUID> findOutputStorageIdsByWorkflowRunId(@Param("workflowRunId") UUID workflowRunId);
    
    /**
     * BATCH-B (2026-05-20) - strict-org overload of
     * {@link #findByRunIdAndStepAliasAndTenantId(String, String, String)}.
     */
    @Query("SELECT w FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.stepAlias = :stepAlias AND w.organizationId = :orgId")
    List<WorkflowStepDataEntity> findByRunIdAndStepAliasAndOrganizationIdStrict(
            @Param("runId") String runId, @Param("stepAlias") String stepAlias, @Param("orgId") String orgId);

    /**
     * Trouve les donnees d'etape pour un run, un alias (case-insensitive) et un tenant donnes.
     * Utilise LOWER() pour ignorer la casse, car la DB peut avoir "C" et "c" pour le meme step.
     */
    @Query("SELECT w FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND LOWER(w.stepAlias) = LOWER(:stepAlias) AND w.tenantId = :tenantId")
    List<WorkflowStepDataEntity> findByRunIdAndStepAliasIgnoreCaseAndTenantId(@Param("runId") String runId, @Param("stepAlias") String stepAlias, @Param("tenantId") String tenantId);

    /**
     * Paged detailed-step lookup by public run id and tenant scope. Unlike
     * {@link #findByRunIdAndStepAliasIgnoreCaseAndTenantId(String, String, String)},
     * this keeps alias history bounded before Hibernate materializes JSONB columns.
     */
    @Query("""
        SELECT w FROM WorkflowStepDataEntity w
        WHERE w.runId = :runId
          AND LOWER(w.stepAlias) = LOWER(:stepAlias)
          AND w.tenantId = :tenantId
          AND (:epoch IS NULL OR w.epoch = :epoch)
        ORDER BY w.id DESC
        """)
    Page<WorkflowStepDataEntity> findDetailedByRunIdAndStepAliasAndTenantId(
            @Param("runId") String runId,
            @Param("stepAlias") String stepAlias,
            @Param("tenantId") String tenantId,
            @Param("epoch") Integer epoch,
            Pageable pageable);

    /**
     * Paged detailed-step lookup with canonical status values expanded by
     * {@link com.apimarketplace.orchestrator.stepdata.StepStatusFilter}.
     */
    @Query("""
        SELECT w FROM WorkflowStepDataEntity w
        WHERE w.runId = :runId
          AND LOWER(w.stepAlias) = LOWER(:stepAlias)
          AND w.tenantId = :tenantId
          AND (:epoch IS NULL OR w.epoch = :epoch)
          AND LOWER(w.status) IN :rawStatuses
        ORDER BY w.id DESC
        """)
    Page<WorkflowStepDataEntity> findDetailedByRunIdAndStepAliasAndTenantIdAndStatusIn(
            @Param("runId") String runId,
            @Param("stepAlias") String stepAlias,
            @Param("tenantId") String tenantId,
            @Param("epoch") Integer epoch,
            @Param("rawStatuses") List<String> rawStatuses,
            Pageable pageable);

    /**
     * BATCH-B (2026-05-20) - strict-org overload of
     * {@link #findByRunIdAndStepAliasIgnoreCaseAndTenantId(String, String, String)}.
     * Used by step-output and detailed-step-data services so that an org-mate
     * with access to a run's data does not leak rows belonging to a different
     * workspace owned by the same userId.
     */
    @Query("SELECT w FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND LOWER(w.stepAlias) = LOWER(:stepAlias) AND w.organizationId = :orgId")
    List<WorkflowStepDataEntity> findByRunIdAndStepAliasIgnoreCaseAndOrganizationIdStrict(
            @Param("runId") String runId, @Param("stepAlias") String stepAlias, @Param("orgId") String orgId);

    /**
     * Strict-org paged detailed-step lookup. Mirrors the tenant-scoped detailed
     * methods above while keeping org-mate access within the run workspace.
     */
    @Query("""
        SELECT w FROM WorkflowStepDataEntity w
        WHERE w.runId = :runId
          AND LOWER(w.stepAlias) = LOWER(:stepAlias)
          AND w.organizationId = :orgId
          AND (:epoch IS NULL OR w.epoch = :epoch)
        ORDER BY w.id DESC
        """)
    Page<WorkflowStepDataEntity> findDetailedByRunIdAndStepAliasAndOrganizationIdStrict(
            @Param("runId") String runId,
            @Param("stepAlias") String stepAlias,
            @Param("orgId") String orgId,
            @Param("epoch") Integer epoch,
            Pageable pageable);

    /**
     * Strict-org paged detailed-step lookup with canonical status filtering.
     */
    @Query("""
        SELECT w FROM WorkflowStepDataEntity w
        WHERE w.runId = :runId
          AND LOWER(w.stepAlias) = LOWER(:stepAlias)
          AND w.organizationId = :orgId
          AND (:epoch IS NULL OR w.epoch = :epoch)
          AND LOWER(w.status) IN :rawStatuses
        ORDER BY w.id DESC
        """)
    Page<WorkflowStepDataEntity> findDetailedByRunIdAndStepAliasAndOrganizationIdAndStatusInStrict(
            @Param("runId") String runId,
            @Param("stepAlias") String stepAlias,
            @Param("orgId") String orgId,
            @Param("epoch") Integer epoch,
            @Param("rawStatuses") List<String> rawStatuses,
            Pageable pageable);

    @Query("SELECT w FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.organizationId = :orgId")
    List<WorkflowStepDataEntity> findByRunIdAndOrganizationIdStrict(
            @Param("runId") String runId, @Param("orgId") String orgId);

    @Query("SELECT w FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.organizationId = :orgId AND w.status = :status")
    List<WorkflowStepDataEntity> findByRunIdAndOrganizationIdAndStatusStrict(
            @Param("runId") String runId, @Param("orgId") String orgId, @Param("status") String status);
    long countByRunId(String runId);

    Optional<WorkflowStepDataEntity> findTopByRunIdOrderByIdDesc(String runId);

    /**
     * Liste les donnees d'etapes pour un workflow run specifique
     */
    List<WorkflowStepDataEntity> findByWorkflowRunIdOrderByIdAsc(UUID workflowRunId);

    @Query("SELECT COUNT(w) FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.organizationId = :orgId")
    long countByRunIdAndOrganizationIdStrict(
            @Param("runId") String runId, @Param("orgId") String orgId);
    void deleteByRunId(String runId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.organizationId = :orgId")
    void deleteByRunIdAndOrganizationIdStrict(
            @Param("runId") String runId, @Param("orgId") String orgId);

    // === RE-RUN SUPPORT ===
    List<WorkflowStepDataEntity> findByRunIdAndNormalizedKeyOrderByEpochDesc(String runId, String normalizedKey);

    /**
     * Find step data for a specific epoch.
     */
    List<WorkflowStepDataEntity> findByRunIdAndEpoch(String runId, int epoch);

    interface EpochOutputProjection {
        String getStepAlias();
        UUID getOutputStorageId();
    }

    /**
     * Completed step output pointers for a specific run epoch. This intentionally
     * avoids loading JSONB columns; sub-workflow output collection only needs the
     * alias and storage id.
     */
    @Query("""
        SELECT w.stepAlias AS stepAlias, w.outputStorageId AS outputStorageId
        FROM WorkflowStepDataEntity w
        WHERE w.runId = :runId
          AND w.epoch = :epoch
          AND w.status = 'COMPLETED'
          AND w.outputStorageId IS NOT NULL
        ORDER BY w.id ASC
        """)
    List<EpochOutputProjection> findCompletedOutputRefsByRunIdAndEpoch(
        @Param("runId") String runId,
        @Param("epoch") int epoch);

    // === PAGINATED STEP DATA BY STEP ALIAS ===

    /**
     * Find steps for a workflowRunId + alias with optional epoch filter, paginated DB-side.
     * Replaces the load-all-and-filter-in-memory pattern that
     * {@code WorkflowRunQueryController.listStepsPaged} previously used - the in-memory
     * variant materialised every row of the run (~17k for the prod OOM run on 2026-05-07)
     * just to keep one paginated alias subset.
     *
     * <p>Use this overload when there is NO status filter; use
     * {@link #findByWorkflowRunIdAndStepAliasAndStatusInPaged} when status filtering is
     * required. Two methods (rather than a single conditional JPQL with a sentinel value)
     * keep the JPQL clean and avoid leaking dummy parameters to the database.
     *
     * <p>Returns full Hibernate-managed entities (heavy JSONB columns included). The page
     * size is bounded by the controller (≤500 since 2026-05-13, bumped from ≤100 so the
     * InspectorPanel's arrow navigator + logs table can surface alias histories that
     * routinely run into thousands of rows on long-lived reusable triggers). The
     * materialised footprint per call stays orders of magnitude below the all-rows
     * fetch pattern that triggered the OOM 2026-05-07.
     */
    @Query("""
        SELECT w FROM WorkflowStepDataEntity w
        WHERE w.workflowRunId = :workflowRunId
          AND LOWER(w.stepAlias) = LOWER(:stepAlias)
          AND (:epoch IS NULL OR w.epoch = :epoch)
        ORDER BY w.id DESC
        """)
    Page<WorkflowStepDataEntity> findByWorkflowRunIdAndStepAliasPagedFiltered(
            @Param("workflowRunId") UUID workflowRunId,
            @Param("stepAlias") String stepAlias,
            @Param("epoch") Integer epoch,
            Pageable pageable);

    /**
     * Same as {@link #findByWorkflowRunIdAndStepAliasPagedFiltered} but adds a status IN
     * filter. The {@code rawStatuses} list comes from
     * {@link com.apimarketplace.orchestrator.stepdata.StepStatusFilter#expandToRawList(String)}
     * which expands canonical frontend status values (e.g. {@code "completed"}) to their
     * raw DB equivalents ({@code "completed"}, {@code "success"}). Caller must guarantee
     * the list is non-empty - JPQL {@code IN} with an empty collection is invalid.
     */
    @Query("""
        SELECT w FROM WorkflowStepDataEntity w
        WHERE w.workflowRunId = :workflowRunId
          AND LOWER(w.stepAlias) = LOWER(:stepAlias)
          AND (:epoch IS NULL OR w.epoch = :epoch)
          AND LOWER(w.status) IN :rawStatuses
        ORDER BY w.id DESC
        """)
    Page<WorkflowStepDataEntity> findByWorkflowRunIdAndStepAliasAndStatusInPaged(
            @Param("workflowRunId") UUID workflowRunId,
            @Param("stepAlias") String stepAlias,
            @Param("epoch") Integer epoch,
            @Param("rawStatuses") List<String> rawStatuses,
            Pageable pageable);

    /**
     * Lightweight bulk listing of step rows for one run, ordered by id ASC, EXCLUDING heavy
     * JSONB columns ({@code input_data}, {@code metadata}, {@code merge_received_branches},
     * {@code merge_skipped_branches}). Returns detached projection-mode entities - no
     * Hibernate session attachment, so the response materialises the bytes only for the
     * scalar columns. ~10-30× smaller than {@link #findByWorkflowRunIdOrderByIdAsc} on a
     * run with 17 000 step rows.
     *
     * <p>Used by {@code WorkflowRunQueryController.listSteps} which previously relied on
     * the full-entity query: that path retained 207 741 {@code ManagedEntityImpl} +
     * {@code byte[]} JSONB on prod OOM 2026-05-07 12:40 UTC under frontend polling.
     *
     * <p>Callers that genuinely need {@code input_data}/{@code metadata}/{@code merge_*}
     * for one specific row must fetch them through the dedicated payload endpoint; the
     * list endpoint returns them as {@code null} by design.
     */
    @Query(nativeQuery = true, value = """
        SELECT id, workflow_run_id, run_id, step_alias, tool_id,
               NULL as input_data,
               output_storage_id, http_status, status, start_time, end_time,
               error_message, tenant_id, organization_id, epoch, spawn, iteration, item_index,
               NULL as metadata,
               node_type, condition_expression, condition_result, selected_branch,
               loop_id, loop_iteration, loop_exit_reason,
               merge_strategy, NULL as merge_received_branches, NULL as merge_skipped_branches,
               item_id, trigger_id, skip_reason, skip_source_node, normalized_key, item_number
        FROM workflow_step_data
        WHERE workflow_run_id = :workflowRunId
        ORDER BY id ASC
        """)
    List<WorkflowStepDataEntity> findByWorkflowRunIdLightweightAll(@Param("workflowRunId") UUID workflowRunId);

    // === CLASSIFY/DECISION BRANCH ROUTING ===

    /**
     * Epoch-scoped version: find item indices routed to a specific branch within a single epoch.
     * Prevents cross-epoch pollution where a previous epoch's routing decisions
     * (e.g., all items APPROVED) leak into the current epoch (e.g., 1 item REJECTED).
     */
    @Query("SELECT DISTINCT w.itemIndex FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.normalizedKey = :normalizedKey AND w.selectedBranch = :selectedBranch AND w.status = 'COMPLETED' AND w.epoch = :epoch")
    List<Integer> findItemIndicesBySelectedBranchAndEpoch(@Param("runId") String runId, @Param("normalizedKey") String normalizedKey, @Param("selectedBranch") String selectedBranch, @Param("epoch") int epoch);

    /**
     * Find completed item indices for a node in a given epoch (no branch filter).
     * Used for transitive routing: when a linear successor inherits its predecessor's
     * item routing, we need to know which items the predecessor actually executed.
     */
    @Query("SELECT DISTINCT w.itemIndex FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.normalizedKey = :normalizedKey AND w.status = 'COMPLETED' AND w.epoch = :epoch")
    List<Integer> findCompletedItemIndicesByEpoch(@Param("runId") String runId, @Param("normalizedKey") String normalizedKey, @Param("epoch") int epoch);

    /**
     * Find item indices that already reached ANY terminal status (COMPLETED/FAILED/SKIPPED)
     * for a node in a given epoch. Used by the per-item continuation mode
     * (approval continuationMode=per_item) as the durable per-item idempotency source:
     * a walk re-invocation must never re-execute an item whose row already landed
     * (rows are the cross-pod / crash-safe record of "this item already ran here").
     */
    @Query("SELECT DISTINCT w.itemIndex FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.normalizedKey = :normalizedKey AND w.status IN ('COMPLETED', 'FAILED', 'SKIPPED') AND w.epoch = :epoch")
    List<Integer> findTerminalItemIndicesByEpoch(@Param("runId") String runId, @Param("normalizedKey") String normalizedKey, @Param("epoch") int epoch);

    /**
     * Phase 2.E aggregate query - count COMPLETED vs FAILED rows for a split-aware
     * node within one epoch. Used by {@code recordSplitAggregateIfMissing} to write
     * the global node status ONCE at barrier seal, instead of on every per-item completion
     * (which would poison the global state on the first failure).
     *
     * <p>Two queries (one per status) - JPA won't return Long values for SUM(CASE WHEN ...).
     * Plain count by status is index-friendly via V155 idx_wsd_aggregate
     * {@code (run_id, normalized_key, epoch, status)}.
     */
    @Query("SELECT COUNT(w) FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.normalizedKey = :normalizedKey AND w.epoch = :epoch AND w.status = :status")
    long countByRunIdAndNormalizedKeyAndEpochAndStatus(
        @Param("runId") String runId,
        @Param("normalizedKey") String normalizedKey,
        @Param("epoch") int epoch,
        @Param("status") String status);

    /**
     * Check whether a completed item output already exists for a node in an epoch.
     * Used by signal resume to keep split signal output materialization idempotent.
     */
    @Query("SELECT CASE WHEN COUNT(w) > 0 THEN true ELSE false END FROM WorkflowStepDataEntity w "
        + "WHERE w.runId = :runId AND w.normalizedKey = :normalizedKey "
        + "AND w.epoch = :epoch AND w.itemIndex = :itemIndex AND w.status = :status")
    boolean existsByRunIdAndNormalizedKeyAndEpochAndItemIndexAndStatus(
        @Param("runId") String runId,
        @Param("normalizedKey") String normalizedKey,
        @Param("epoch") int epoch,
        @Param("itemIndex") int itemIndex,
        @Param("status") String status);

    /**
     * Check whether the exact signal yield already has a persisted completion.
     * Signal resume uses the signal creation time as the yield identity: loop
     * iterations reuse the same node/item/epoch, but each signal has a distinct
     * createdAt value.
     */
    @Query("SELECT CASE WHEN COUNT(w) > 0 THEN true ELSE false END FROM WorkflowStepDataEntity w "
        + "WHERE w.runId = :runId AND w.normalizedKey = :normalizedKey "
        + "AND w.epoch = :epoch AND w.itemIndex = :itemIndex AND w.status = :status "
        + "AND w.startTime = :startTime")
    boolean existsByRunIdAndNormalizedKeyAndEpochAndItemIndexAndStatusAndStartTime(
        @Param("runId") String runId,
        @Param("normalizedKey") String normalizedKey,
        @Param("epoch") int epoch,
        @Param("itemIndex") int itemIndex,
        @Param("status") String status,
        @Param("startTime") java.time.Instant startTime);

    /**
     * Count completed rows for a node/item in an epoch so repeated loop signal
     * resolutions can use a new iteration value and avoid the step-data unique key.
     */
    @Query("SELECT COUNT(w) FROM WorkflowStepDataEntity w "
        + "WHERE w.runId = :runId AND w.normalizedKey = :normalizedKey "
        + "AND w.epoch = :epoch AND w.itemIndex = :itemIndex AND w.status = :status")
    long countByRunIdAndNormalizedKeyAndEpochAndItemIndexAndStatus(
        @Param("runId") String runId,
        @Param("normalizedKey") String normalizedKey,
        @Param("epoch") int epoch,
        @Param("itemIndex") int itemIndex,
        @Param("status") String status);

    // === OPTIMIZED AGGREGATED STEPS QUERY ===

    /**
     * Get pre-aggregated step data for polling: status counts, toolId, and timing.
     * Uses GROUP BY to avoid loading full entities with heavy JSONB columns.
     *
     * Returns one row per (stepAlias, status) with aggregate timing data.
     * The caller must merge rows with the same stepAlias to build the final AggregatedStep.
     *
     * <p><b>Spawn-aware (rerun correctness):</b> a step rerun bumps {@code spawn} and resets the
     * target + downstream nodes; their pre-rerun rows are superseded state, NOT accumulated
     * history. Only the latest spawn per (alias, trigger, epoch, iteration, item) coordinate
     * counts - otherwise a branch deactivated by the rerun (old COMPLETED row at spawn N, new
     * SKIPPED row at spawn N+1) keeps reporting "completed", because the status derivation lets
     * any completed row win over skipped. Cross-epoch accumulation (multiple trigger fires) is
     * untouched: the max-spawn filter is per-coordinate and distinct epochs/iterations/items
     * are distinct coordinates.
     *
     * @param runId The public run ID
     * @return List of projections with alias, status, count, toolId, min startTime, max endTime
     */
    @Query(value = """
        SELECT w.step_alias as "stepAlias", w.status as status, COUNT(*) as count,
               MIN(w.tool_id) as "toolId", MIN(w.start_time) as "minStartTime", MAX(w.end_time) as "maxEndTime",
               CAST(GREATEST(COALESCE(SUM(GREATEST((EXTRACT(EPOCH FROM w.end_time) - EXTRACT(EPOCH FROM w.start_time)) * 1000, 0)), 0), 0) AS BIGINT) as "sumExecutionTimeMs"
        FROM workflow_step_data w
        WHERE w.run_id = :runId AND w.step_alias IS NOT NULL
          AND COALESCE(w.spawn, 0) = (
              SELECT MAX(COALESCE(w2.spawn, 0)) FROM workflow_step_data w2
              WHERE w2.run_id = w.run_id AND w2.step_alias = w.step_alias
                AND COALESCE(w2.trigger_id, '') = COALESCE(w.trigger_id, '')
                AND COALESCE(w2.epoch, 0) = COALESCE(w.epoch, 0)
                AND COALESCE(w2.iteration, 0) = COALESCE(w.iteration, 0)
                AND COALESCE(w2.item_index, 0) = COALESCE(w.item_index, 0))
        GROUP BY w.step_alias, w.status
        """, nativeQuery = true)
    List<com.apimarketplace.orchestrator.repository.AggregatedStepProjection> getAggregatedStepsByRunId(@Param("runId") String runId);

    /**
     * Get pre-aggregated step data filtered by epoch.
     * Same as getAggregatedStepsByRunId but restricted to a single epoch.
     * Used for per-epoch node timing display in the epoch timeline.
     *
     * @param runId The public run ID
     * @param epoch The epoch to filter by
     * @return List of projections with alias, status, count, toolId, min startTime, max endTime
     */
    @Query(value = """
        SELECT w.step_alias as "stepAlias", w.status as status, COUNT(*) as count,
               MIN(w.tool_id) as "toolId", MIN(w.start_time) as "minStartTime", MAX(w.end_time) as "maxEndTime",
               CAST(GREATEST(COALESCE(SUM(GREATEST((EXTRACT(EPOCH FROM w.end_time) - EXTRACT(EPOCH FROM w.start_time)) * 1000, 0)), 0), 0) AS BIGINT) as "sumExecutionTimeMs"
        FROM workflow_step_data w
        WHERE w.run_id = :runId AND w.step_alias IS NOT NULL AND w.epoch = :epoch
          AND COALESCE(w.spawn, 0) = (
              SELECT MAX(COALESCE(w2.spawn, 0)) FROM workflow_step_data w2
              WHERE w2.run_id = w.run_id AND w2.step_alias = w.step_alias
                AND COALESCE(w2.trigger_id, '') = COALESCE(w.trigger_id, '')
                AND COALESCE(w2.epoch, 0) = COALESCE(w.epoch, 0)
                AND COALESCE(w2.iteration, 0) = COALESCE(w.iteration, 0)
                AND COALESCE(w2.item_index, 0) = COALESCE(w.item_index, 0))
        GROUP BY w.step_alias, w.status
        """, nativeQuery = true)
    List<com.apimarketplace.orchestrator.repository.AggregatedStepProjection> getAggregatedStepsByRunIdAndEpoch(
        @Param("runId") String runId, @Param("epoch") int epoch);

    // === OPTIMIZED QUERIES FOR INTERFACE RENDER ===

    /**
     * Get the workflowRunId (UUID) for a given public runId.
     * Avoids loading full entities just to extract workflowRunId.
     */
    @Query("SELECT DISTINCT w.workflowRunId FROM WorkflowStepDataEntity w WHERE w.runId = :runId")
    List<UUID> findWorkflowRunIdsByRunId(@Param("runId") String runId);

    /**
     * Get distinct (epoch, itemIndex) pairs for a run with the earliest start time per group.
     * Used for interface pagination without loading full entities.
     */
    @Query("SELECT w.epoch as epoch, w.itemIndex as itemIndex, w.spawn as spawn, MIN(w.startTime) as minStartTime " +
           "FROM WorkflowStepDataEntity w WHERE w.runId = :runId GROUP BY w.epoch, w.itemIndex, w.spawn")
    List<com.apimarketplace.orchestrator.repository.EpochItemProjection> findDistinctEpochItemPairsByRunId(@Param("runId") String runId);

    /**
     * Get distinct (epoch, itemIndex) pairs for a run, excluding trigger-only epochs.
     * An epoch is included only if it has at least one non-trigger step (MCP, AGENT, etc.).
     * Used for interface pagination to avoid empty pages from trigger submissions.
     */
    @Query("SELECT w.epoch as epoch, w.itemIndex as itemIndex, w.spawn as spawn, MIN(w.startTime) as minStartTime " +
           "FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.nodeType <> com.apimarketplace.orchestrator.domain.execution.NodeType.TRIGGER " +
           "GROUP BY w.epoch, w.itemIndex, w.spawn")
    List<com.apimarketplace.orchestrator.repository.EpochItemProjection> findDistinctEpochItemPairsExcludingTriggers(@Param("runId") String runId);

    /**
     * Count distinct (epoch, itemIndex) pairs for a run.
     * Optimized count for interface item counting.
     */
    @Query("SELECT COUNT(DISTINCT CONCAT(COALESCE(w.epoch, 0), ':', COALESCE(w.itemIndex, 0))) FROM WorkflowStepDataEntity w WHERE w.runId = :runId")
    long countDistinctItemsByRunId(@Param("runId") String runId);

    /**
     * Count distinct (epoch, itemIndex) pairs excluding trigger-only epochs.
     */
    @Query("SELECT COUNT(DISTINCT CONCAT(COALESCE(w.epoch, 0), ':', COALESCE(w.itemIndex, 0))) FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.nodeType <> com.apimarketplace.orchestrator.domain.execution.NodeType.TRIGGER")
    long countDistinctItemsExcludingTriggers(@Param("runId") String runId);

    // === NODE-SCOPED PAGINATION QUERIES (for interface rendering) ===

    /**
     * Find all distinct normalizedKeys for interface nodes in a run.
     * Used to resolve which interface node to scope pagination to.
     */
    @Query("SELECT DISTINCT w.normalizedKey FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.nodeType = com.apimarketplace.orchestrator.domain.execution.NodeType.INTERFACE")
    List<String> findInterfaceNormalizedKeysByRunId(@Param("runId") String runId);

    /**
     * Get distinct (epoch, spawn, itemIndex) triples for a specific node (by normalizedKey).
     * Scopes pagination to a single node's executions instead of all nodes in the run.
     */
    @Query("SELECT w.epoch as epoch, w.itemIndex as itemIndex, w.spawn as spawn, MIN(w.startTime) as minStartTime " +
           "FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.normalizedKey = :normalizedKey GROUP BY w.epoch, w.itemIndex, w.spawn")
    List<com.apimarketplace.orchestrator.repository.EpochItemProjection> findDistinctEpochItemPairsByRunIdAndNormalizedKey(
            @Param("runId") String runId, @Param("normalizedKey") String normalizedKey);

    /**
     * Count distinct (epoch, itemIndex) pairs for a specific node (by normalizedKey).
     * Spawn reruns are intentionally deduplicated because render pagination keeps only the latest
     * spawn for each (epoch, itemIndex) pair.
     */
    @Query("SELECT COUNT(DISTINCT CONCAT(COALESCE(w.epoch, 0), ':', COALESCE(w.itemIndex, 0))) FROM WorkflowStepDataEntity w WHERE w.runId = :runId AND w.normalizedKey = :normalizedKey")
    long countDistinctItemsByRunIdAndNormalizedKey(@Param("runId") String runId, @Param("normalizedKey") String normalizedKey);

    // === OPTIMIZED QUERY FOR STATE RECONSTRUCTION ===

    /**
     * Load the latest step entity per alias for state reconstruction, EXCLUDING heavy
     * inputData/metadata JSONB columns. Returns at most one row per step_alias - the one
     * with the highest id (most recent insertion).
     *
     * <p>StepStateBuilder consumes only the last entity per alias
     * ({@code entities.get(size - 1)} after grouping). Loading every row across all epochs
     * materialised tens of thousands of useless PgResultSet rows for long-running workflows
     * (split + many epochs) and was the primary OOM pressure source - see prod incident
     * 2026-05-07 12:40 UTC where 17 180 rows × ~30 concurrent reconstructState calls
     * blew the heap. {@code DISTINCT ON (step_alias)} returns ~32 rows instead.
     */
    @Query(nativeQuery = true, value = """
        SELECT DISTINCT ON (step_alias)
               id, workflow_run_id, run_id, step_alias, tool_id,
               NULL as input_data,
               output_storage_id, http_status, status, start_time, end_time,
               error_message, tenant_id, organization_id, epoch, spawn, iteration, item_index,
               NULL as metadata,
               node_type, condition_expression, condition_result, selected_branch,
               loop_id, loop_iteration, loop_exit_reason,
               merge_strategy, NULL as merge_received_branches, NULL as merge_skipped_branches,
               item_id, trigger_id, skip_reason, skip_source_node, normalized_key, item_number
        FROM workflow_step_data
        WHERE workflow_run_id = :workflowRunId
        ORDER BY step_alias, id DESC
        """)
    List<WorkflowStepDataEntity> findLatestPerAliasLightweight(@Param("workflowRunId") UUID workflowRunId);

    /**
     * Latest-per-alias variant of {@link #findByWorkflowRunIdAndEpochLightweight} for the
     * single-epoch (epoch=0) path. Matches the multi-epoch
     * {@link #findLatestPerAliasLightweight} bound: {@code DISTINCT ON (step_alias)}
     * ORDER BY step_alias, id DESC - caps at ~32 aliases vs unbounded fan-out rowcount.
     *
     * <p>Post-2026-05-22 OOM hardening - closes the epoch=0 leak that survived the
     * 2026-05-07 fix (which only patched the multi-epoch path).
     */
    @Query(nativeQuery = true, value = """
        SELECT DISTINCT ON (step_alias)
               id, workflow_run_id, run_id, step_alias, tool_id,
               NULL as input_data,
               output_storage_id, http_status, status, start_time, end_time,
               error_message, tenant_id, organization_id, epoch, spawn, iteration, item_index,
               NULL as metadata,
               node_type, condition_expression, condition_result, selected_branch,
               loop_id, loop_iteration, loop_exit_reason,
               merge_strategy, NULL as merge_received_branches, NULL as merge_skipped_branches,
               item_id, trigger_id, skip_reason, skip_source_node, normalized_key, item_number
        FROM workflow_step_data
        WHERE workflow_run_id = :workflowRunId AND epoch = :epoch
        ORDER BY step_alias, id DESC
        """)
    List<WorkflowStepDataEntity> findByWorkflowRunIdAndEpochLatestPerAliasLightweight(
        @Param("workflowRunId") UUID workflowRunId,
        @Param("epoch") int epoch);
}

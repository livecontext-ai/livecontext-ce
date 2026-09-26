package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
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
     * The window each epoch of the given runs spent EXECUTING: first node start to
     * last node end, one row per (run, epoch). Feeds the run history's "last
     * execution duration" column and the epoch timeline; callers narrow to the
     * epoch they want (see {@code WorkflowEpochService}).
     *
     * <p>Deliberately ONE flat aggregate rather than a correlated
     * {@code epoch = (SELECT MAX(epoch) …)}: Postgres does not memoize a scalar
     * SubPlan, so the correlated form re-runs the inner query per candidate row of
     * every listed run, and this table is large and unpruned. The flat form is a
     * single index-ordered {@code GROUP BY} whose output is bounded by the epoch
     * count, and picking the latest epoch in Java costs nothing.
     *
     * <p>The window is first-start to last-end, so any gap INSIDE it counts: a node
     * waiting on an approval (its step row spans the wait) and a node re-run later in
     * the same epoch both extend it. That is intended - the epoch really was
     * executing across that span. What is excluded is the idle tail AFTER the last
     * node finished, which is what made the epoch header unusable.
     */
    @Query("""
           SELECT new com.apimarketplace.orchestrator.persistence.EpochWorkWindowProjection(
                      w.runId, w.epoch, MIN(w.startTime), MAX(w.endTime))
           FROM WorkflowStepDataEntity w
           WHERE w.runId IN :runIds
           GROUP BY w.runId, w.epoch
           """)
    List<EpochWorkWindowProjection> findEpochWorkWindows(@Param("runIds") Collection<String> runIds);

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
               item_id, trigger_id, skip_reason, skip_source_node, normalized_key, item_number, is_mocked
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
     * Terminal statuses of a SET of nodes, for one (epoch, item) coordinate.
     *
     * <p>Reads the durable per-item record rather than the in-memory execution state, which is
     * node-level and therefore cannot tell "skipped for item 2" from "skipped for every item".
     * Used by {@code MergeReachabilityGuard} to decide whether a merge node has any live
     * predecessor for the item it is about to run for.
     *
     * <p>Deliberately NOT filtered on iteration or spawn: a loop turn, or a re-execution, that
     * took a different branch leaves a COMPLETED row behind, and finding it makes the guard
     * stand down. Erring towards "reachable" is the safe direction, since that is exactly
     * what the engine does today.
     *
     * @return rows of {@code [normalizedKey, status]}
     */
    @Query("SELECT DISTINCT w.normalizedKey, w.status FROM WorkflowStepDataEntity w "
         + "WHERE w.runId = :runId AND w.normalizedKey IN :normalizedKeys "
         + "AND w.epoch = :epoch AND w.itemIndex = :itemIndex "
         + "AND w.status IN ('COMPLETED', 'FAILED', 'SKIPPED')")
    List<Object[]> findTerminalStatusesForItem(@Param("runId") String runId,
                                               @Param("normalizedKeys") Collection<String> normalizedKeys,
                                               @Param("epoch") int epoch,
                                               @Param("itemIndex") int itemIndex);

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
     * <p><b>Why a window function and not a correlated subquery:</b> the per-coordinate max used
     * to be a scalar subquery re-evaluated for EVERY row of the run, and each evaluation scanned
     * the whole {@code (run_id, step_alias)} index group - i.e. every epoch of that node. Cost was
     * therefore quadratic in the number of epochs, which is why the logs modal took seconds to open
     * on a long-lived run: measured on a synthetic run of 20 nodes, 2 items, 1 000 epochs
     * (40 000 rows), 10.7 s with the subquery against 69 ms with the window (400 epochs: 1 975 ms
     * against 37 ms). {@code MAX(...) OVER (PARTITION BY <the same coordinate>)} computes every
     * group in one pass, and {@code AggregatedStepsQueryPostgresTest} pins that both forms return
     * the same rows, spawn supersession included.
     *
     * @param runId The public run ID
     * @return List of projections with alias, status, count, toolId, min startTime, max endTime
     */
    @Query(value = """
        SELECT t.step_alias as "stepAlias", t.status as status, COUNT(*) as count,
               MIN(t.tool_id) as "toolId", MIN(t.start_time) as "minStartTime", MAX(t.end_time) as "maxEndTime",
               CAST(GREATEST(COALESCE(SUM(GREATEST((EXTRACT(EPOCH FROM t.end_time) - EXTRACT(EPOCH FROM t.start_time)) * 1000, 0)), 0), 0) AS BIGINT) as "sumExecutionTimeMs"
        FROM (
            SELECT w.step_alias, w.status, w.tool_id, w.start_time, w.end_time,
                   COALESCE(w.spawn, 0) AS spawn_norm,
                   MAX(COALESCE(w.spawn, 0)) OVER (
                       PARTITION BY w.step_alias,
                                    COALESCE(w.trigger_id, ''),
                                    COALESCE(w.epoch, 0),
                                    COALESCE(w.iteration, 0),
                                    COALESCE(w.item_index, 0)) AS max_spawn
            FROM workflow_step_data w
            WHERE w.run_id = :runId AND w.step_alias IS NOT NULL
        ) t
        WHERE t.spawn_norm = t.max_spawn
        GROUP BY t.step_alias, t.status
        """, nativeQuery = true)
    List<com.apimarketplace.orchestrator.repository.AggregatedStepProjection> getAggregatedStepsByRunId(@Param("runId") String runId);

    /**
     * Get pre-aggregated step data filtered by epoch.
     * Same as getAggregatedStepsByRunId but restricted to a single epoch.
     * Used for per-epoch node timing display in the epoch timeline.
     *
     * <p>Same single-pass max-spawn window as the whole-run query. The inner filter is
     * {@code COALESCE(epoch, 0) = :epoch} because that is the set the correlated subquery used to
     * take its max over (it compared coalesced epochs), while the outer {@code t.epoch = :epoch}
     * reproduces the old outer predicate, which a NULL epoch never satisfied. Keeping the two
     * apart matters only for the legacy NULL-epoch rows, and keeps this a pure rewrite.
     *
     * <p>The trade that buys: wrapping the epoch column in COALESCE makes the filter non-sargable,
     * so this reads the whole run's rows where the previous form could seek on
     * {@code idx_wsd_resolution}'s epoch column. It is still far cheaper than the correlated max it
     * replaces (measured 6.9 ms on a 40 000-row run), so the trade is worth it as written. If this
     * query ever becomes hot, {@code (w.epoch = :epoch OR (:epoch = 0 AND w.epoch IS NULL))} is the
     * sargable equivalent, and the NULL-epoch case in the test class is what proves it equivalent.
     *
     * @param runId The public run ID
     * @param epoch The epoch to filter by
     * @return List of projections with alias, status, count, toolId, min startTime, max endTime
     */
    @Query(value = """
        SELECT t.step_alias as "stepAlias", t.status as status, COUNT(*) as count,
               MIN(t.tool_id) as "toolId", MIN(t.start_time) as "minStartTime", MAX(t.end_time) as "maxEndTime",
               CAST(GREATEST(COALESCE(SUM(GREATEST((EXTRACT(EPOCH FROM t.end_time) - EXTRACT(EPOCH FROM t.start_time)) * 1000, 0)), 0), 0) AS BIGINT) as "sumExecutionTimeMs"
        FROM (
            SELECT w.step_alias, w.status, w.tool_id, w.start_time, w.end_time, w.epoch,
                   COALESCE(w.spawn, 0) AS spawn_norm,
                   MAX(COALESCE(w.spawn, 0)) OVER (
                       PARTITION BY w.step_alias,
                                    COALESCE(w.trigger_id, ''),
                                    COALESCE(w.epoch, 0),
                                    COALESCE(w.iteration, 0),
                                    COALESCE(w.item_index, 0)) AS max_spawn
            FROM workflow_step_data w
            WHERE w.run_id = :runId AND w.step_alias IS NOT NULL AND COALESCE(w.epoch, 0) = :epoch
        ) t
        WHERE t.epoch = :epoch AND t.spawn_norm = t.max_spawn
        GROUP BY t.step_alias, t.status
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
     * The highest spawn any row of ONE epoch carries, or 0 when the epoch has no rows.
     *
     * <p>Needed because {@code metadata.dagCurrentSpawn} is per DAG and is reset by every trigger
     * fire: "the next spawn" is only known-unused in the epoch that fire opened. A rerun aimed at
     * an OLDER epoch has to start above that epoch's own history, or it writes rows tied with an
     * earlier attempt at the same max spawn and the supersede filter above keeps both.
     */
    @Query("SELECT COALESCE(MAX(COALESCE(w.spawn, 0)), 0) FROM WorkflowStepDataEntity w "
         + "WHERE w.runId = :runId AND w.epoch = :epoch")
    Integer findMaxSpawnForEpoch(@Param("runId") String runId, @Param("epoch") int epoch);

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
               item_id, trigger_id, skip_reason, skip_source_node, normalized_key, item_number, is_mocked
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
               item_id, trigger_id, skip_reason, skip_source_node, normalized_key, item_number, is_mocked
        FROM workflow_step_data
        WHERE workflow_run_id = :workflowRunId AND epoch = :epoch
        ORDER BY step_alias, id DESC
        """)
    List<WorkflowStepDataEntity> findByWorkflowRunIdAndEpochLatestPerAliasLightweight(
        @Param("workflowRunId") UUID workflowRunId,
        @Param("epoch") int epoch);
}

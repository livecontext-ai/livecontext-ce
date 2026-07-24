package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {

    Optional<WorkflowRunEntity> findByRunIdPublic(String runIdPublic);

    /**
     * Check if a run exists by public run ID.
     * Optimized alternative to findByRunIdPublic when only existence check is needed.
     */
    boolean existsByRunIdPublic(String runIdPublic);

    /**
     * Batched existence projection - returns the subset of {@code runIdPublics}
     * that have a row in {@code workflow_runs} for the given tenant. Used by
     * the notification bell to drop / nullify references to runs that have
     * been deleted (retention purge, manual cleanup) so the user never lands
     * on an unknown-runId page.
     *
     * <p>Tenant-scoped: {@code run_id_public} is globally unique today so a
     * cross-tenant collision is structurally impossible, but the predicate is
     * defense-in-depth against a future refactor that drops the global
     * uniqueness invariant.
     *
     * <p>Single round-trip; the unique index on {@code run_id_public} keeps
     * the cost flat for the bounded {@code MAX_BUCKETS=40} input.
     */
    @Query("SELECT wr.runIdPublic FROM WorkflowRunEntity wr "
            + "WHERE wr.tenantId = :tenantId AND wr.runIdPublic IN :runIdPublics")
    List<String> findExistingRunIdPublics(@Param("tenantId") String tenantId,
                                          @Param("runIdPublics") Collection<String> runIdPublics);

    /**
     * V220 follow-up - org-scoped sibling of
     * {@link #findExistingRunIdPublics(String, Collection)} used by the
     * notification bell when the caller is operating inside an organization
     * workspace. The bell aggregates rows tagged with {@code organization_id};
     * the referenced {@code runIdPublic} was likely written by a teammate
     * (different {@code tenant_id}) so a tenant-scoped existence check would
     * spuriously nullify every deep-link in the org-scope page.
     *
     * <p>Predicate: {@code wr.organizationId = :orgId AND wr.runIdPublic IN :runIdPublics}.
     * Single round-trip; the unique index on {@code run_id_public} keeps the
     * cost flat for the bounded {@code MAX_BUCKETS=40} input. Strict-org
     * predicate (no {@code IS NULL} fallback) - personal-scope rows MUST NOT
     * leak into an org-scope read.
     */
    @Query("SELECT wr.runIdPublic FROM WorkflowRunEntity wr "
            + "WHERE wr.organizationId = :orgId AND wr.runIdPublic IN :runIdPublics")
    List<String> findExistingRunIdPublicsByOrganizationId(@Param("orgId") String orgId,
                                                          @Param("runIdPublics") Collection<String> runIdPublics);

    /**
     * Get only the stateSnapshot field for a run.
     * Used for streaming to avoid keeping entity proxy attached to session.
     * This prevents connection leaks during long-lived streaming connections.
     */
    @Query("SELECT wr.stateSnapshot FROM WorkflowRunEntity wr WHERE wr.runIdPublic = :runIdPublic")
    Optional<String> findStateSnapshotByRunIdPublic(@Param("runIdPublic") String runIdPublic);

    /**
     * Read-only projection of the {@code state_snapshot_seq} column. A2 Phase 4
     * (audit Opus 2026-05-09): used by the out-of-tx Redis snapshot cache to
     * decide whether the cached parsed {@code StateSnapshot} is still current
     * - without pulling and re-parsing the (potentially TOAST-detoasted) ~30KB
     * JSONB on every SSE poll.
     *
     * <p>~0.3ms in steady state (1-column scalar projection on the unique
     * index). The cache hit-path replaces a full row read + Jackson parse
     * (~1.5-5ms) with this single SELECT.
     */
    @Query("SELECT wr.stateSnapshotSeq FROM WorkflowRunEntity wr WHERE wr.runIdPublic = :runIdPublic")
    Optional<Long> findStateSnapshotSeqByRunIdPublic(@Param("runIdPublic") String runIdPublic);

    /**
     * Combined projection: {@code (state_snapshot_seq, state_snapshot)} in a
     * single round-trip. Used by the L2 fall-through of the 3-tier read path
     * when both the JSON payload and its seq oracle are needed.
     *
     * <p>Replaces the two-call sequence
     * {@code findStateSnapshotSeqByRunIdPublic} + {@code findStateSnapshotByRunIdPublic}
     * on the cache-miss path - eliminates the TOCTOU window between the two
     * reads (a peer could commit between them, leaving the parsed snapshot
     * tagged with a seq the caller never observed) and saves one network RTT
     * + one index lookup.
     *
     * <p>See plan v3 §4 / audit B M4 / C6.
     */
    @Query("SELECT wr.stateSnapshotSeq AS stateSnapshotSeq, wr.stateSnapshot AS stateSnapshot "
            + "FROM WorkflowRunEntity wr WHERE wr.runIdPublic = :runIdPublic")
    Optional<StateSnapshotSeqAndJsonProjection> findSeqAndStateSnapshotByRunIdPublic(
            @Param("runIdPublic") String runIdPublic);

    /**
     * Phase A2 (archi-refoundation 2026-05-04) - projection used by
     * {@code SnapshotService.markDirty} as the authoritative terminal-status
     * check. JPQL field is {@code wr.status} (verified
     * {@link WorkflowRunEntity#status}) - DO NOT change to {@code runStatus}.
     *
     * <p>Hot-path query: called once per cache miss on the
     * {@code activeRunsCache}/{@code terminatedRunsCache}. PK + indexed
     * {@code run_id_public} make this ~50µs.
     */
    @Query("SELECT wr.status FROM WorkflowRunEntity wr WHERE wr.runIdPublic = :runIdPublic")
    Optional<RunStatus> findStatusByRunIdPublic(@Param("runIdPublic") String runIdPublic);

    /**
     * Plan v4 §1.5 phase 2n - tenant ID projection for the lock-free CAS path.
     * Builders need the tenantId for elide-flag resolution; without
     * findByRunIdPublicForUpdate held, we need a separate cheap projection.
     * One row, PK lookup, ~50µs.
     */
    @Query("SELECT wr.tenantId FROM WorkflowRunEntity wr WHERE wr.runIdPublic = :runIdPublic")
    Optional<String> findTenantIdByRunIdPublic(@Param("runIdPublic") String runIdPublic);

    /**
     * Projection of the workflow UUID from a run identifier, used by
     * {@link com.apimarketplace.orchestrator.services.interfaces.InterfaceScreenshotServiceImpl}
     * when uploading a captured screenshot. Goes through the {@code @ManyToOne} lazy association
     * via a JPQL field access so the path is callable outside a transaction (no
     * {@code LazyInitializationException} risk under {@code spring.jpa.open-in-view=false}).
     */
    @Query("SELECT wr.workflow.id FROM WorkflowRunEntity wr WHERE wr.runIdPublic = :runIdPublic")
    Optional<UUID> findWorkflowIdByRunIdPublic(@Param("runIdPublic") String runIdPublic);

    /**
     * Is this run its workflow's live PRODUCTION run ({@code workflow.production_run_id}
     * points at it)? Single indexed round-trip through the lazy association (JPQL field
     * access - safe outside a transaction, same pattern as
     * {@link #findWorkflowIdByRunIdPublic}).
     *
     * <p>Exists because {@code __editorRun__} cannot answer the question: pinning
     * promotes an editor run to production and never strips the flag, so the
     * production run of a pinned workflow carries it too. Consumers that must treat
     * production differently ({@code MockRunGate}: production never mocks) key on
     * this instead of the metadata.
     */
    @Query("SELECT CASE WHEN COUNT(wr) > 0 THEN true ELSE false END FROM WorkflowRunEntity wr "
            + "WHERE wr.runIdPublic = :runIdPublic AND wr.id = wr.workflow.productionRunId")
    boolean isProductionRunByRunIdPublic(@Param("runIdPublic") String runIdPublic);

    /**
     * Phase A2 - batched flusher for {@code WsEventSequencer.lastEventSeq}.
     * Per the plan, runs every 5s in steady state. Single round-trip for N
     * runs (PostgreSQL {@code UPDATE ... FROM (VALUES ...)}) keeps DB write
     * load at ~0.2 RPS for 1000 concurrent runs (vs 200 RPS naïve loop).
     *
     * <p>Idempotent guard {@code last_event_seq < c.seq} so a slow flusher
     * from another pod cannot overwrite a fresher value. Uses native query
     * (FROM-VALUES is PG-specific syntax not supported in JPQL).
     */
    @Modifying
    @Query(value = """
        UPDATE workflow_runs r
        SET last_event_seq = c.seq, updated_at = now()
        FROM (VALUES (cast(:#{#runIdPublic} as text), cast(:#{#seq} as bigint))) AS c(run_id_public, seq)
        WHERE r.run_id_public = c.run_id_public
          AND r.last_event_seq < c.seq
        """, nativeQuery = true)
    int upsertLastEventSeqSingle(@Param("runIdPublic") String runIdPublic, @Param("seq") long seq);

    /**
     * Plan v4 E2E4 - targeted JSONB merge for {@code metadata.userPlan}.
     *
     * <p>The pre-fix call site
     * ({@code TriggerController.updateUserPlanMetadata}) did:
     * <pre>
     *   run.setMetadata(new HashMap&lt;&gt;(run.getMetadata()).put("userPlan", userPlan));
     *   runRepository.save(run);
     * </pre>
     * On a detached entity, {@code save()} routes to {@code EntityManager.merge}
     * which copies ALL in-memory fields onto the managed copy. Under heavy
     * concurrency, the in-memory {@code state_snapshot_seq} is stale (loaded
     * earlier in the request handler before parallel CAS writers advanced it),
     * so the merge re-writes {@code state_snapshot_seq=stale}. V181's
     * monotonicity trigger rejects with {@code "must not regress (was=N,
     * new=N-M)"}. {@code @DynamicUpdate} doesn't help because merge writes
     * every field whose in-memory value differs from the live DB row.
     *
     * <p>This method side-steps merge by emitting a targeted UPDATE on the
     * {@code metadata} JSONB column only. {@code jsonb_set} preserves the
     * existing keys + atomically inserts/overwrites {@code userPlan}.
     *
     * <p>{@code state_snapshot_seq} is not in the SET clause, so V181's
     * BEFORE-UPDATE trigger sees {@code NEW.seq = OLD.seq} and passes.
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = """
        UPDATE workflow_runs
        SET metadata = jsonb_set(
                COALESCE(metadata, '{}'::jsonb),
                '{userPlan}',
                to_jsonb(cast(:userPlan as text)),
                true),
            updated_at = now()
        WHERE run_id_public = :runIdPublic
        """, nativeQuery = true)
    int upsertUserPlanMetadata(@Param("runIdPublic") String runIdPublic,
                                @Param("userPlan") String userPlan);

    /**
     * Plan v4 E2E5 - atomic snapshot+seq native UPDATE for the legacy
     * full-rewrite path.
     *
     * <p>Why a native query: the JPA layer marks {@code state_snapshot_seq}
     * {@code @Column(updatable = false)} as a defense-in-depth against
     * detached-entity merges that would overwrite the live seq with a stale
     * load-time value (V181 trigger trip). The ONLY legitimate writer of seq
     * (outside the CAS path) is {@code saveSnapshotFullRewrite}, which now
     * uses this native query to bypass Hibernate entity tracking.
     *
     * <p>Atomicity: both columns are set in a single UPDATE statement. The
     * seq is incremented DB-side ({@code state_snapshot_seq + 1}), not by
     * caller-supplied value. This is the only path V181's BEFORE trigger
     * can never reject - the new seq is, by construction, the live seq + 1.
     * Callers do NOT pass a seq.
     *
     * <p>Plan v4 E2E5/MF1 - the SQL also rewrites the JSON-embedded
     * {@code {seq}} field to {@code state_snapshot_seq + 1} so the two
     * representations (JSON-embedded vs SQL column) can NEVER drift. The
     * {@code StateSnapshotJsonCache} keys its read-side {@code expectedSeq}
     * off the SQL column and its write-side {@code capturedSeq} off the
     * JSON-embedded value; pre-MF1 the two were free to diverge under
     * pathological conditions (txCache miss, V181-error recovery), silently
     * defeating the cache for the affected run (always
     * {@code miss_seq_mismatch}). The atomic {@code jsonb_set} guarantees
     * lockstep.
     *
     * <p>Returns rowCount (1 on success, 0 if the run was deleted between
     * load and UPDATE). Caller must surface 0-row as an error path.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query(value = """
        UPDATE workflow_runs
        SET state_snapshot = jsonb_set(
                cast(:snapshot as jsonb),
                '{seq}',
                to_jsonb(state_snapshot_seq + 1)),
            state_snapshot_seq = state_snapshot_seq + 1,
            updated_at = now()
        WHERE run_id_public = :runIdPublic
        """, nativeQuery = true)
    int updateSnapshotAndSeq(@Param("runIdPublic") String runIdPublic,
                              @Param("snapshot") String snapshot);

    /**
     * Accumulate agent cost onto a run, in credits (1 credit = $0.001). Agent
     * executions are the only cost source; each settled agent execution calls
     * this once through {@code RunCostService}.
     *
     * <p>Atomic + monotonic: {@code cost_credits} grows by {@code :credits} and
     * the per-epoch bucket in {@code cost_by_epoch} grows by the same amount in
     * the SAME statement, so the run total always equals the sum of its epoch
     * buckets. Because both are pure increments, concurrent notifications from
     * parallel agent nodes never lose a write (no read-modify-write in app code).
     *
     * <p>Neither column is {@code state_snapshot}/{@code state_snapshot_seq}, so
     * this does NOT go through the JsonbPatchExecutor - it is allow-listed in
     * {@code JsonbWritesCallsiteInvariantTest}. Org-scoped with a null-safe
     * predicate (personal-scope runs carry {@code organization_id = NULL}); the
     * unique {@code run_id_public} already pins the row, the org clause is
     * defense-in-depth against a cross-scope notification.
     *
     * @return rows updated (0 = run deleted, or org mismatch)
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = """
        UPDATE workflow_runs
        SET cost_credits = cost_credits + cast(:credits as numeric),
            cost_by_epoch = jsonb_set(
                COALESCE(cost_by_epoch, '{}'::jsonb),
                ARRAY[cast(:epochKey as text)],
                to_jsonb(
                    COALESCE((cost_by_epoch->>cast(:epochKey as text))::numeric, 0)
                    + cast(:credits as numeric))),
            updated_at = now()
        WHERE run_id_public = :runIdPublic
          AND (organization_id = cast(:orgId as text)
               OR (cast(:orgId as text) IS NULL AND organization_id IS NULL))
        """, nativeQuery = true)
    int incrementRunCost(@Param("runIdPublic") String runIdPublic,
                         @Param("orgId") String orgId,
                         @Param("epochKey") String epochKey,
                         @Param("credits") java.math.BigDecimal credits);

    /**
     * Fresh {@code cost_credits} for a run, bypassing any stale L1-cached entity.
     * Used by the epoch budget gate ({@code ReusableTriggerService}) which must
     * compare the LIVE accumulated cost against the workflow budget before
     * opening a new epoch - the {@code run} entity in that path was loaded
     * earlier and its {@code costCredits} field may lag behind the native
     * increments issued by concurrent agent notifications.
     */
    @Query("SELECT wr.costCredits FROM WorkflowRunEntity wr WHERE wr.runIdPublic = :runIdPublic")
    Optional<java.math.BigDecimal> findCostCreditsByRunIdPublic(@Param("runIdPublic") String runIdPublic);

    /**
     * The budget (credits) of the workflow this run belongs to, or empty when
     * the run has no budget set. Read via the {@code @ManyToOne} association as
     * a JPQL field access so it works outside a transaction. Consumed by
     * {@code RunCostService} to stamp {@code budgetCredits} on the emitted cost
     * event (so the frontend can paint the over-budget warning without a second
     * fetch) and by the epoch budget gate.
     */
    @Query("SELECT wr.workflow.budgetCredits FROM WorkflowRunEntity wr WHERE wr.runIdPublic = :runIdPublic")
    Optional<java.math.BigDecimal> findWorkflowBudgetByRunIdPublic(@Param("runIdPublic") String runIdPublic);

    /**
     * Fresh cost of a single epoch bucket from {@code cost_by_epoch}, in credits.
     * Read back by {@code RunCostService} right after {@link #incrementRunCost}
     * so the emitted WS event carries the up-to-date per-epoch figure. Returns
     * empty when the run or the epoch bucket does not exist yet.
     */
    @Query(value = """
        SELECT (cost_by_epoch->>cast(:epochKey as text))::numeric
        FROM workflow_runs WHERE run_id_public = :runIdPublic
        """, nativeQuery = true)
    Optional<java.math.BigDecimal> findEpochCostByRunIdPublic(@Param("runIdPublic") String runIdPublic,
                                                              @Param("epochKey") String epochKey);

    /**
     * Find by runIdPublic with pessimistic write lock.
     * Used for atomic read-modify-write operations on state_snapshot.
     * Prevents race conditions when multiple threads update the same run.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT wr FROM WorkflowRunEntity wr WHERE wr.runIdPublic = :runIdPublic")
    Optional<WorkflowRunEntity> findByRunIdPublicForUpdate(@Param("runIdPublic") String runIdPublic);

    List<WorkflowRunEntity> findByWorkflowIdOrderByStartedAtDesc(UUID workflowId);

    @Query("SELECT wr FROM WorkflowRunEntity wr WHERE wr.workflow.id = :workflowId ORDER BY wr.startedAt DESC")
    Page<WorkflowRunEntity> findByWorkflowIdOrderByStartedAtDescPageable(@Param("workflowId") UUID workflowId, Pageable pageable);

    /**
     * Optimized query that returns only essential columns for listing.
     * Excludes heavy JSONB columns (trigger_payload, plan) for better performance.
     * Includes metadata (small JSONB) for lastCycleResult display on WAITING_TRIGGER runs.
     * ORDER BY must be explicit in custom @Query (Pageable sort is ignored).
     */
    @Query("""
        SELECT wr.id as id, wr.runIdPublic as runIdPublic, wr.tenantId as tenantId,
               wr.status as status, wr.startedAt as startedAt, wr.endedAt as endedAt,
               wr.durationMs as durationMs, wr.totalNodes as totalNodes, wr.executionMode as executionMode,
               wr.planVersion as planVersion, wr.metadata as metadata
        FROM WorkflowRunEntity wr
        WHERE wr.workflow.id = :workflowId
        ORDER BY wr.startedAt DESC
        """)
    Page<WorkflowRunSummaryProjection> findRunSummariesByWorkflowId(@Param("workflowId") UUID workflowId, Pageable pageable);

    /**
     * Tenant-scoped variant of {@link #findRunSummariesByWorkflowId(UUID, Pageable)}.
     * Public REST endpoints MUST use this variant: returning runs of another
     * tenant by workflow_id alone is a cross-tenant data leak (e.g. a publisher
     * acquires their own clone, both runs share the workflow_id but live under
     * different tenants).
     */
    @Query("""
        SELECT wr.id as id, wr.runIdPublic as runIdPublic, wr.tenantId as tenantId,
               wr.status as status, wr.startedAt as startedAt, wr.endedAt as endedAt,
               wr.durationMs as durationMs, wr.totalNodes as totalNodes, wr.executionMode as executionMode,
               wr.planVersion as planVersion, wr.metadata as metadata
        FROM WorkflowRunEntity wr
        WHERE wr.workflow.id = :workflowId AND wr.tenantId = :tenantId
        ORDER BY wr.startedAt DESC
        """)
    Page<WorkflowRunSummaryProjection> findRunSummariesByWorkflowIdAndTenantId(
            @Param("workflowId") UUID workflowId,
            @Param("tenantId") String tenantId,
            Pageable pageable);

    /**
     * Owner-or-org scope variant of {@link #findRunSummariesByWorkflowIdAndTenantId}.
     *
     * <p>Used by REST list endpoints (`/workflows/{id}/runs` and `/runs/latest`)
     * when the gateway forwards an {@code X-Organization-ID} alongside the
     * caller's tenantId. Returns runs that either (a) belong to the caller
     * directly, OR (b) carry a non-null {@code organization_id} matching the
     * active workspace. NULL-org rows stay tenant-only (pre-V202/V209 backfill
     * data is never visible to org-teammates).
     *
     * <p>Mirror of the cross-scope read predicate in
     * {@link com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper#isRunInScope}
     * so list-vs-detail UX stays consistent (audit 2026-05-15 MF2: team member
     * could open `/state` of a single run but `/workflows/{id}/runs` showed
     * empty).
     */
    // Post-V261 (2026-05-19): every workflow_run row carries a non-null
    // organization_id; legacy OR-ownership branch was dead + cross-workspace
    // bleed. Strict-org match closes the leak. tenantId param preserved
    // (ignored) for back-compat; Phase 11 will rename + drop it.
    @Query("""
        SELECT wr.id as id, wr.runIdPublic as runIdPublic, wr.tenantId as tenantId,
               wr.status as status, wr.startedAt as startedAt, wr.endedAt as endedAt,
               wr.durationMs as durationMs, wr.totalNodes as totalNodes, wr.executionMode as executionMode,
               wr.planVersion as planVersion, wr.metadata as metadata
        FROM WorkflowRunEntity wr
        WHERE wr.workflow.id = :workflowId
          AND wr.organizationId = :orgId
        ORDER BY wr.startedAt DESC
        """)
    Page<WorkflowRunSummaryProjection> findRunSummariesByWorkflowIdInScope(
            @Param("workflowId") UUID workflowId,
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            Pageable pageable);

    long countByWorkflowId(UUID workflowId);

    List<WorkflowRunEntity> findByStatus(RunStatus status);

    /**
     * Lightweight count for {@link com.apimarketplace.orchestrator.lifecycle.AgentDrainCoordinator}
     * to assert "no RUNNING workflow runs left" at @PreDestroy time. Backed by Spring
     * Data's derived COUNT - no entity hydration.
     */
    long countByStatus(RunStatus status);

    /**
     * Find runs with a given status whose updated_at is before the given cutoff.
     * Used by {@link com.apimarketplace.orchestrator.config.OrchestrationRecoveryService}
     * to detect zombie RUNNING runs with no recent activity.
     */
    List<WorkflowRunEntity> findByStatusAndUpdatedAtBefore(RunStatus status, Instant cutoff);

    List<WorkflowRunEntity> findByWorkflowIdAndStartedAtAfter(UUID workflowId, Instant startedAt);
    
    List<WorkflowRunEntity> findByWorkflow(WorkflowEntity workflow);
    
    // BATCH-B (2026-05-20): findByTenantId(String) and
    // findByTenantIdOrderByStartedAtDesc(String) deleted as orphans (no src/main
    // caller). The integration test that exercised them was the sole consumer.
    // For org-scoped run listing, use {@link #findByOrganizationIdOrderByStartedAtDesc}
    // or other workspace-aware finders defined below.

    /**
     * Find runs waiting for trigger (webhook) for a specific workflow.
     * Used to find runs that are waiting for a webhook call.
     */
    List<WorkflowRunEntity> findByWorkflowIdAndStatus(UUID workflowId, RunStatus status);

    /**
     * Count runs by workflow and status.
     * Used to check concurrent run limits for workflow triggers.
     */
    long countByWorkflowIdAndStatus(UUID workflowId, RunStatus status);

    /**
     * Count runs grouped by plan version for a workflow.
     * Used to display run counts per version in the version history UI.
     * Returns Object[] where [0] = planVersion (Integer), [1] = count (Long).
     */
    @Query("SELECT wr.planVersion, COUNT(wr) FROM WorkflowRunEntity wr " +
           "WHERE wr.workflow.id = :workflowId AND wr.planVersion IS NOT NULL " +
           "GROUP BY wr.planVersion")
    List<Object[]> countRunsByPlanVersion(@Param("workflowId") UUID workflowId);

    /**
     * Find the most recent run waiting for trigger for a workflow.
     *
     * <p>"Most recent" means most-recently-CREATED (not most-recently-active): manually
     * re-executing an older run bumps {@code updatedAt} but never {@code createdAt}.
     * Sort keys: {@code started_at DESC, created_at DESC, id DESC} - primary sort uses
     * the index, then {@code created_at} when timestamps tie (clones), then {@code id}
     * as a final guarantee of total order.
     *
     * <p>Native SQL is used so {@code LIMIT 1} can apply (JPQL has no LIMIT, and
     * {@code @Query} disables Spring Data's automatic {@code findFirst} max-results hint).
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_runs wr
        WHERE wr.workflow_id = :workflowId AND wr.status = :#{#status?.name()}
        ORDER BY wr.started_at DESC, wr.created_at DESC, wr.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<WorkflowRunEntity> findFirstByWorkflowIdAndStatusOrderByStartedAtDesc(
        @Param("workflowId") UUID workflowId, @Param("status") RunStatus status);

    /**
     * Find the most recent run in any of the given statuses for a workflow.
     * Used by webhook dispatch to find active runs (WAITING_TRIGGER, RUNNING, or PAUSED)
     * for parallel epoch support.
     *
     * <p>See {@link #findFirstByWorkflowIdAndStatusOrderByStartedAtDesc} for the
     * deterministic-sort rationale.
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_runs wr
        WHERE wr.workflow_id = :workflowId AND wr.status IN (:#{#statuses.![name()]})
        ORDER BY wr.started_at DESC, wr.created_at DESC, wr.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<WorkflowRunEntity> findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
        @Param("workflowId") UUID workflowId, @Param("statuses") Collection<RunStatus> statuses);

    /**
     * Find the most recent run for a workflow with a specific plan version.
     * Used by version-aware webhook dispatch when workflow is pinned to a version.
     *
     * <p>See {@link #findFirstByWorkflowIdAndStatusOrderByStartedAtDesc} for the
     * deterministic-sort rationale.
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_runs wr
        WHERE wr.workflow_id = :workflowId AND wr.plan_version = :planVersion
        ORDER BY wr.started_at DESC, wr.created_at DESC, wr.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<WorkflowRunEntity> findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(
        @Param("workflowId") UUID workflowId, @Param("planVersion") Integer planVersion);

    /**
     * Find the most recent run for a workflow with a specific plan version and exact status.
     * Used by {@code EditorRunResolver} to find a reusable WAITING_TRIGGER run.
     *
     * <p>See {@link #findFirstByWorkflowIdAndStatusOrderByStartedAtDesc} for the
     * deterministic-sort rationale.
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_runs wr
        WHERE wr.workflow_id = :workflowId AND wr.plan_version = :planVersion AND wr.status = :#{#status?.name()}
        ORDER BY wr.started_at DESC, wr.created_at DESC, wr.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<WorkflowRunEntity> findFirstByWorkflowIdAndPlanVersionAndStatusOrderByStartedAtDesc(
        @Param("workflowId") UUID workflowId, @Param("planVersion") Integer planVersion, @Param("status") RunStatus status);

    /**
     * Find the most recent run for a workflow with a specific plan version,
     * filtered to only include runs in the given statuses.
     * Used for plan-trust decisions: only COMPLETED/WAITING_TRIGGER/RUNNING/PAUSED
     * runs should be trusted - CANCELLED/FAILED runs may have bad plans.
     *
     * <p>See {@link #findFirstByWorkflowIdAndStatusOrderByStartedAtDesc} for the
     * deterministic-sort rationale.
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_runs wr
        WHERE wr.workflow_id = :workflowId AND wr.plan_version = :planVersion AND wr.status IN (:#{#statuses.![name()]})
        ORDER BY wr.started_at DESC, wr.created_at DESC, wr.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<WorkflowRunEntity> findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(
        @Param("workflowId") UUID workflowId, @Param("planVersion") Integer planVersion, @Param("statuses") Collection<RunStatus> statuses);

    /**
     * Mode-aware variant of the lookup above - the editor run-reuse window.
     *
     * <p>{@code EditorRunResolver} reuses the live run of the REQUESTED execution mode.
     * Filtering by mode in the query (instead of checking the single latest run after
     * the fact) means an automatic re-execute still finds its live automatic run even
     * when a newer step-by-step run exists at the same version (and vice versa) -
     * otherwise every auto↔SBS alternation minted a fresh run.
     *
     * <p>See {@link #findFirstByWorkflowIdAndStatusOrderByStartedAtDesc} for the
     * deterministic-sort rationale.
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_runs wr
        WHERE wr.workflow_id = :workflowId AND wr.plan_version = :planVersion
          AND wr.execution_mode = :#{#mode?.name()}
          AND wr.status IN (:#{#statuses.![name()]})
        ORDER BY wr.started_at DESC, wr.created_at DESC, wr.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<WorkflowRunEntity> findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
        @Param("workflowId") UUID workflowId, @Param("planVersion") Integer planVersion,
        @Param("mode") com.apimarketplace.orchestrator.domain.workflow.ExecutionMode mode,
        @Param("statuses") Collection<RunStatus> statuses);

    /**
     * Production-run lookup that EXCLUDES showcase clones.
     *
     * <p>Showcase clones are inert frozen snapshots created by
     * {@code RunCloneService.cloneRun()} for marketplace publication display.
     * They share the publisher's {@code workflow_id} + {@code plan_version} +
     * {@code status} with the original run, so naïve "latest by startedAt" lookups
     * can pick them - silently making the schedule fire on a clone that doesn't
     * progress, or pin operations record the clone as {@code production_run_id}.
     *
     * <p>Filter: {@code source IS NULL OR source <> 'showcase'} (defensive - older
     * runs may have NULL source) AND {@code run_id_public NOT LIKE 'showcase\_%'}
     * for belt-and-braces (RunCloneService stamps both fields).
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_runs wr
        WHERE wr.workflow_id = :workflowId
          AND wr.plan_version = :planVersion
          AND wr.status IN (:#{#statuses.![name()]})
          AND (wr.source IS NULL OR wr.source <> 'showcase')
          AND wr.run_id_public NOT LIKE 'showcase\\_%' ESCAPE '\\'
        ORDER BY wr.started_at DESC, wr.created_at DESC, wr.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<WorkflowRunEntity> findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
        @Param("workflowId") UUID workflowId,
        @Param("planVersion") Integer planVersion,
        @Param("statuses") Collection<RunStatus> statuses);

    /**
     * Production-run lookup with exact-status filter, excluding showcase clones.
     * Used by {@link com.apimarketplace.orchestrator.trigger.ProductionRunResolver}
     * for the {@code LATEST_WAITING_TRIGGER} policy (schedule).
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_runs wr
        WHERE wr.workflow_id = :workflowId
          AND wr.plan_version = :planVersion
          AND wr.status = :#{#status?.name()}
          AND (wr.source IS NULL OR wr.source <> 'showcase')
          AND wr.run_id_public NOT LIKE 'showcase\\_%' ESCAPE '\\'
        ORDER BY wr.started_at DESC, wr.created_at DESC, wr.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<WorkflowRunEntity> findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
        @Param("workflowId") UUID workflowId,
        @Param("planVersion") Integer planVersion,
        @Param("status") RunStatus status);

    /**
     * Find the most recent run for a workflow regardless of status or version.
     * Used by version-aware webhook dispatch when workflow is not pinned.
     *
     * <p>See {@link #findFirstByWorkflowIdAndStatusOrderByStartedAtDesc} for the
     * deterministic-sort rationale.
     */
    @Query(value = """
        SELECT * FROM orchestrator.workflow_runs wr
        WHERE wr.workflow_id = :workflowId
        ORDER BY wr.started_at DESC, wr.created_at DESC, wr.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<WorkflowRunEntity> findFirstByWorkflowIdOrderByStartedAtDesc(@Param("workflowId") UUID workflowId);

    /**
     * Cancel all WAITING_TRIGGER runs for a workflow.
     * Used when starting a new run to ensure only one run is waiting at a time.
     *
     * @param workflowId The workflow ID
     * @param cancelledStatus The status to set (should be CANCELLED)
     * @param waitingStatus The status to match (should be WAITING_TRIGGER)
     * @param now The current timestamp for endedAt
     * @return Number of runs cancelled
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkflowRunEntity r SET r.status = :cancelledStatus, r.endedAt = :now, r.updatedAt = :now " +
           "WHERE r.workflow.id = :workflowId AND r.status = :waitingStatus")
    int cancelWaitingTriggerRuns(
        @Param("workflowId") UUID workflowId,
        @Param("cancelledStatus") RunStatus cancelledStatus,
        @Param("waitingStatus") RunStatus waitingStatus,
        @Param("now") Instant now
    );

    /**
     * Cancel stale runs for a workflow (WAITING_TRIGGER + PAUSED).
     * Used when creating a new run to prevent orphan runs that external dispatch
     * services (webhook, schedule) could accidentally pick up.
     * RUNNING runs are NOT cancelled - they finish naturally.
     *
     * @param workflowId The workflow ID
     * @param now The current timestamp for endedAt
     * @return Number of runs cancelled
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkflowRunEntity r SET r.status = 'CANCELLED', r.endedAt = :now, r.updatedAt = :now " +
           "WHERE r.workflow.id = :workflowId AND r.status IN ('WAITING_TRIGGER', 'PAUSED')")
    int cancelStaleRuns(
        @Param("workflowId") UUID workflowId,
        @Param("now") Instant now
    );

    /**
     * Find all runs for a workflow with any of the given statuses.
     * Used to individually finalize active runs with smart status resolution
     * (e.g., using lastCycleResult from metadata for WAITING_TRIGGER runs).
     */
    boolean existsByWorkflowIdAndStatusIn(UUID workflowId, Collection<RunStatus> statuses);

    List<WorkflowRunEntity> findByWorkflowIdAndStatusIn(UUID workflowId, Collection<RunStatus> statuses);

    /**
     * Find the most recent application run for a workflow+publication pair.
     * Uses the partial index on (workflow_id, publication_id) WHERE source = 'application'.
     */
    Optional<WorkflowRunEntity> findFirstByWorkflowIdAndSourceAndPublicationIdOrderByStartedAtDesc(
        UUID workflowId, String source, String publicationId);

    /**
     * Find all application-dedicated runs for a workflow+publication pair.
     * Used to extract run IDs for cleanup before deletion.
     */
    List<WorkflowRunEntity> findAllByWorkflowIdAndSourceAndPublicationId(UUID workflowId, String source, String publicationId);

    /**
     * Delete all application-dedicated runs for a workflow+publication pair.
     * Called on unpublish to ensure a fresh run is created on republish.
     */
    void deleteAllByWorkflowIdAndSourceAndPublicationId(UUID workflowId, String source, String publicationId);

    /**
     * Batch-count runs per workflow.
     * Returns Object[] where [0] = workflow_id (UUID), [1] = count (Long).
     * Used by the board endpoint to avoid N+1 count queries.
     */
    @Query("SELECT wr.workflow.id, COUNT(wr) FROM WorkflowRunEntity wr " +
           "WHERE wr.workflow.id IN :workflowIds GROUP BY wr.workflow.id")
    List<Object[]> countByWorkflowIds(@Param("workflowIds") Collection<UUID> workflowIds);

    /**
     * Find the most recent production run per workflow at each workflow's pinned version.
     * Uses PostgreSQL DISTINCT ON for efficient batch lookup.
     * Used by the board endpoint to batch-resolve production runs.
     *
     * <p>"Most recent" is defined by row-creation order, not last-activity order: the board
     * card must surface the run the user most recently <em>created</em> at the pinned
     * version, even if an older run was re-executed manually afterwards (which bumps
     * {@code updated_at} but not {@code created_at}). The secondary sort key
     * {@code created_at DESC} is the deterministic tie-break for the case where two rows
     * share the same {@code started_at} (e.g. clone-of-run scenarios that mirror it).
     */
    @Query(value = """
        SELECT DISTINCT ON (wr.workflow_id) wr.*
        FROM orchestrator.workflow_runs wr
        INNER JOIN orchestrator.workflows w ON wr.workflow_id = w.id
        WHERE wr.workflow_id IN :workflowIds
          AND w.pinned_version IS NOT NULL
          AND wr.plan_version = w.pinned_version
          AND (wr.source IS NULL OR wr.source <> 'showcase')
          AND wr.run_id_public NOT LIKE 'showcase\\_%' ESCAPE '\\'
        ORDER BY wr.workflow_id, wr.started_at DESC, wr.created_at DESC, wr.id DESC
        """, nativeQuery = true)
    List<WorkflowRunEntity> findProductionRunsBatch(@Param("workflowIds") Collection<UUID> workflowIds);

    /**
     * Batch sibling of {@link #findFirstByWorkflowIdAndSourceAndPublicationIdOrderByStartedAtDesc}:
     * the most recent application-dedicated run ({@code source='application'}) per workflow, for the
     * Applications page card grid (live preview + last-executed sort). Replaces one
     * {@code /runs/application} request PER application (an N+1 over ~200 cards) with a single
     * DISTINCT ON query, mirroring {@link #findProductionRunsBatch}.
     *
     * <p>Keyed by {@code workflow_id} ALONE (no {@code publication_id} predicate): an APPLICATION
     * workflow instance is the acquired clone (or published source) of exactly ONE publication, so all
     * its {@code source='application'} runs share one {@code publication_id} - one row per workflow is
     * correct. {@code source='application'} already excludes {@code 'showcase'} clones, so no extra
     * showcase guard is needed (same reason the single-item finder omits it). Same deterministic
     * tie-break as the production batch.
     */
    @Query(value = """
        SELECT DISTINCT ON (wr.workflow_id) wr.*
        FROM orchestrator.workflow_runs wr
        WHERE wr.workflow_id IN :workflowIds
          AND wr.source = 'application'
        ORDER BY wr.workflow_id, wr.started_at DESC, wr.created_at DESC, wr.id DESC
        """, nativeQuery = true)
    List<WorkflowRunEntity> findApplicationRunsBatch(@Param("workflowIds") Collection<UUID> workflowIds);

    /**
     * Cancel all active runs for a workflow (WAITING_TRIGGER, RUNNING, PAUSED).
     * Used by scheduled execution to ensure only one run at a time.
     *
     * @param workflowId The workflow ID
     * @param now The current timestamp for endedAt
     * @return Number of runs cancelled
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkflowRunEntity r SET r.status = 'CANCELLED', r.endedAt = :now, r.updatedAt = :now " +
           "WHERE r.workflow.id = :workflowId AND r.status IN ('WAITING_TRIGGER', 'RUNNING', 'PAUSED')")
    int cancelAllActiveRuns(
        @Param("workflowId") UUID workflowId,
        @Param("now") Instant now
    );
}

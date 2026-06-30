package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Entity representing an execution instance of a workflow.
 *
 * <p>Plan v4 E2E4 - {@code @DynamicUpdate} is REQUIRED. Without it, every
 * {@code repository.save(run)} emits an UPDATE setting ALL columns, including
 * {@code state_snapshot} (LOB) and {@code state_snapshot_seq}. When the
 * entity was loaded earlier in the same tx by a non-locked call (e.g.
 * {@code ReusableTriggerService.executeTriggerInternal} doing
 * {@code findByRunIdPublic} for a status check), its {@code state_snapshot_seq}
 * field reflects the L1-cached value, which becomes stale as concurrent CAS
 * writers advance the live row. A subsequent {@code run.setPlan(...) +
 * runRepository.save(run)} call - purely intended to update the {@code plan}
 * column - emits a full-column UPDATE that overwrites the live
 * {@code state_snapshot_seq=N} with the stale L1 value {@code N-M},
 * tripping V181's {@code "state_snapshot_seq must not regress"} trigger.
 *
 * <p>With {@code @DynamicUpdate}, Hibernate emits UPDATE statements
 * containing only the columns whose values changed via setters since load.
 * {@code run.setPlan(...)} now produces {@code UPDATE workflow_runs SET
 * plan=? WHERE id=?} - no {@code state_snapshot_seq} touch, no V181 trip.
 *
 * <p>Behavior verified against k6 saturation-single (50 VU × 3 min): the
 * 21 854 {@code "must not regress"} errors observed pre-fix go to zero
 * post-fix without sacrificing throughput.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "workflow_runs")
@DynamicUpdate
public class WorkflowRunEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private WorkflowEntity workflow;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "run_id_public", nullable = false, unique = true)
    private String runIdPublic;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RunStatus status = RunStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false)
    private ExecutionMode executionMode = ExecutionMode.AUTOMATIC;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "total_nodes")
    private Integer totalNodes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_payload", columnDefinition = "jsonb")
    private Map<String, Object> triggerPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan", columnDefinition = "jsonb")
    private Map<String, Object> plan;

    /**
     * Snapshot of execution state (node counts, edge counts, ready nodes).
     * This is the SINGLE SOURCE OF TRUTH for execution status.
     * Updated atomically on each node completion.
     */
    /**
     * Plan v4 E2E5/M3 - {@code updatable = false} symmetric to
     * {@link #stateSnapshotSeq}. Same lost-write risk: a detached-entity
     * {@code repository.save(run)} would otherwise emit a merge UPDATE that
     * overwrites the live JSONB with the request handler's stale L1 copy.
     * V181 doesn't fire because seq is updatable=false (NEW.seq == OLD.seq);
     * the regression is silent JSON corruption. Mirroring the fence here
     * closes the second half of the hole.
     *
     * <p>The only legitimate writer is the {@code WorkflowRunRepository}
     * native UPDATE pair: {@code updateSnapshotAndSeq} (full rewrite path,
     * via {@code StateSnapshotService.saveSnapshotFullRewrite}) and
     * {@code JsonbPatchExecutor.applyPatches[Cas]} (patch path). Both bypass
     * Hibernate entity tracking. The {@code setStateSnapshot} setter remains
     * for in-memory coherence (post-write mirror) but is excluded from
     * UPDATE statements. The {@code SetStateSnapshotGuardTest} ArchUnit rule
     * additionally pins that only {@code saveSnapshotFullRewrite} invokes
     * the setter from app code.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_snapshot", columnDefinition = "jsonb", updatable = false)
    private String stateSnapshot;

    /**
     * Monotonic version counter for {@link #stateSnapshot}. Incremented by every
     * state-snapshot writer (full rewrite + jsonb_set patch path) inside the
     * same UPDATE statement so the {@link
     * com.apimarketplace.orchestrator.services.streaming.SnapshotService}
     * out-of-tx cache can validate its parsed {@code StateSnapshot} against the
     * current DB version without pulling the JSONB column on every SSE poll.
     *
     * <p>A2 (Phase 2 jsonb_set follow-up, 2026-05-09 audit Opus A): hot path
     * target is the SSE poll loop where Jackson re-parsing of a 10-30KB
     * {@code state_snapshot} TEXT was the dominant alloc-tick contributor.
     *
     * <p><b>Plan v4 E2E5</b> - {@code updatable = false} at the JPA layer.
     * Hibernate auto-flush (triggered by any {@code repository.save(detachedRun)}
     * or dirty-entity flush at tx commit) MUST NEVER include this column in an
     * UPDATE statement. When the entity is detached and merged, its in-memory
     * {@code stateSnapshotSeq} reflects the load-time DB value, which becomes
     * stale as concurrent CAS writers advance the live row. A merge-driven
     * UPDATE would overwrite the live {@code N+M} with the stale {@code N},
     * tripping V181's monotonicity trigger.
     *
     * <p>The ONLY legitimate writers - the CAS path
     * ({@code JsonbPatchExecutor.applyPatchesCas} with a {@code SET
     * state_snapshot_seq = :newSeq WHERE state_snapshot_seq = :expectedSeq}
     * native UPDATE) and {@code saveSnapshotFullRewrite}
     * (via {@code WorkflowRunRepository.updateSnapshotAndSeq} native UPDATE) -
     * issue native SQL that bypasses Hibernate's entity tracking and bumps
     * the seq atomically alongside the snapshot. The {@code setStateSnapshotSeq}
     * setter remains for in-memory state propagation (parsing, caching) but
     * the JPA UPDATE always ignores it.
     */
    @Column(name = "state_snapshot_seq", nullable = false, updatable = false)
    private long stateSnapshotSeq = 0L;

    @Column(name = "plan_version")
    private Integer planVersion;

    /**
     * Source of the run: "application" for application-dedicated runs, null for builder runs.
     */
    @Column(name = "source", length = 20)
    private String source;

    /**
     * Publication ID this run is associated with (only set when source = "application").
     */
    @Column(name = "publication_id", length = 50)
    private String publicationId;

    @Column(name = "created_by")
    private String createdBy;

    /**
     * PR15 - workspace the run executes in. Promoted from the legacy
     * {@code metadata['__orgId__']} JSONB stash to a first-class column so it
     * can be indexed (for org-aware run listings) and accessed without
     * re-parsing the metadata blob on every read.
     *
     * <p>{@code null} = personal scope. Stamped at run-creation time by
     * {@link com.apimarketplace.orchestrator.controllers.workflow.WorkflowExecutionController}
     * from the {@code X-Organization-ID} header. The legacy
     * {@code metadata['__orgId__']} value is kept on existing rows for one
     * release cycle (V209 backfill goes the other direction); future writers
     * must set this column directly.
     */
    @Column(name = "organization_id")
    private String organizationId;

    /**
     * PR15 - caller's role in the active org at run-creation time
     * (OWNER/ADMIN/MEMBER/VIEWER). Used by org-aware access checks downstream
     * (OrgAccessGuard.filterAccessible). Sourced from {@code X-Organization-Role}.
     */
    @Column(name = "organization_role", length = 32)
    private String organizationRole;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Cross-restart seed for {@code WsEventSequencer}. Bumped by a scheduled
     * flusher (every 5s, batched UPDATE) - see Flyway V100. On pod restart,
     * {@code WsEventSequencer.nextSeq(runId)} reads this column to seed its
     * in-memory {@code AtomicLong} (or sets the Redis key) so the frontend
     * {@code lastKnownSeq} keeps making forward progress. Worst-case drop
     * window: 5s of events ; recovered via WS-reconnect → REST `/state`.
     * (Phase A2 archi-refoundation 2026-05-04.)
     */
    @Column(name = "last_event_seq", nullable = false)
    private long lastEventSeq = 0L;

    public WorkflowRunEntity() {
    }

    public WorkflowRunEntity(WorkflowEntity workflow,
                              String tenantId,
                              String runIdPublic,
                              Map<String, Object> triggerPayload,
                              Map<String, Object> metadata,
                              String createdBy) {
        this.workflow = workflow;
        this.tenantId = tenantId;
        this.runIdPublic = runIdPublic;
        this.triggerPayload = triggerPayload;
        this.metadata = metadata;
        this.createdBy = createdBy;
        this.startedAt = Instant.now();
        this.createdAt = this.startedAt;
        this.updatedAt = this.startedAt;
        this.status = RunStatus.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public WorkflowEntity getWorkflow() {
        return workflow;
    }

    public void setWorkflow(WorkflowEntity workflow) {
        this.workflow = workflow;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getRunIdPublic() {
        return runIdPublic;
    }

    public void setRunIdPublic(String runIdPublic) {
        this.runIdPublic = runIdPublic;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }

    public boolean isStepByStepMode() {
        return executionMode == ExecutionMode.STEP_BY_STEP;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getTotalNodes() {
        return totalNodes;
    }

    public void setTotalNodes(Integer totalNodes) {
        this.totalNodes = totalNodes;
    }

    public Map<String, Object> getTriggerPayload() {
        return triggerPayload;
    }

    public void setTriggerPayload(Map<String, Object> triggerPayload) {
        this.triggerPayload = triggerPayload;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Map<String, Object> getPlan() {
        return plan;
    }

    public void setPlan(Map<String, Object> plan) {
        this.plan = plan;
    }

    public String getStateSnapshot() {
        return stateSnapshot;
    }

    /**
     * <b>Direct setter - DO NOT call from new production code paths.</b>
     *
     * <p>Writes to {@code state_snapshot} MUST go through
     * {@code StateSnapshotService.saveSnapshot(WorkflowRunEntity, StateSnapshot)} so the
     * per-tx parse cache ({@code TxScopedSnapshotCache}) is kept in sync. Bypassing this
     * contract leaves a stale entry in the cache for the duration of the current
     * transaction - subsequent reads in the same tx return the pre-write snapshot,
     * silently corrupting downstream mutations.
     *
     * <p>Permitted callers:
     * <ul>
     *   <li>{@code StateSnapshotService.saveSnapshot} - single source of truth.</li>
     *   <li>{@code RunCloneService} - initialising a freshly-minted run row that no
     *       transaction has cached yet (no contention possible).</li>
     *   <li>Hibernate / Jackson (entity hydration) - internal framework concerns.</li>
     * </ul>
     */
    public void setStateSnapshot(String stateSnapshot) {
        this.stateSnapshot = stateSnapshot;
    }

    public long getStateSnapshotSeq() {
        return stateSnapshotSeq;
    }

    /**
     * Set the snapshot version counter. Callers should NOT use this directly -
     * the seq is incremented inside the same SQL UPDATE that writes
     * {@code state_snapshot}, via {@code StateSnapshotService} (full rewrite)
     * or {@code JsonbPatchExecutor.composeUpdateSql} (patch path). This setter
     * exists for Hibernate hydration, test fixtures, and run-clone bootstrap.
     */
    public void setStateSnapshotSeq(long stateSnapshotSeq) {
        this.stateSnapshotSeq = stateSnapshotSeq;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(Integer planVersion) {
        this.planVersion = planVersion;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getLastEventSeq() {
        return lastEventSeq;
    }

    public void setLastEventSeq(long lastEventSeq) {
        this.lastEventSeq = lastEventSeq;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getPublicationId() {
        return publicationId;
    }

    public void setPublicationId(String publicationId) {
        this.publicationId = publicationId;
    }

    // ===== Org context - first-class columns =====

    /**
     * Workspace this run executes in. Returns {@code null} = personal scope.
     */
    public String getOrgId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    /**
     * Caller's role in the active org at run-creation time.
     */
    public String getOrgRole() {
        return organizationRole;
    }

    public void setOrganizationRole(String organizationRole) {
        this.organizationRole = organizationRole;
    }

    public String getOrganizationRole() {
        return organizationRole;
    }
}

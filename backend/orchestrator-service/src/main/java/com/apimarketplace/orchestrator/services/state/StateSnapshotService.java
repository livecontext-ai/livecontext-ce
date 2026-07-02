package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.elide.EpochStateRunningElideSerializer;
import com.apimarketplace.orchestrator.services.state.patch.IncrementNodeCountsOnlyPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatch;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatchExecutor;
import com.apimarketplace.orchestrator.services.state.patch.MarkNodeAwaitingSignalPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.MarkNodeCompletedEpochOnlyPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.MarkNodeCompletedPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.MarkNodeFailedEpochOnlyPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.MarkNodeFailedPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.MarkNodePartialFailurePatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.MarkNodeSkippedPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.AddReadyNodePatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.RecordDecisionBranchPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.RecordEdgeStatusPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.RemoveReadyNodePatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.ReplaceReadyNodeSetPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.ResolveAwaitingSignalPatchBuilder;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueueClaimStore;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueueRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for managing workflow execution state snapshots.
 *
 * Single Responsibility: CRUD operations on StateSnapshot.
 *
 * This is the ONLY place where state snapshots are read/written.
 * All state mutations go through this service.
 *
 * The enriched StateSnapshot eliminates the need for:
 * - StateReconstructor (expensive 20+ queries)
 * - In-memory caches (ExecutionCacheManager, StateManagerIntegrationService, etc.)
 * - Redis step-by-step cache
 *
 * Benefits:
 * - Single source of truth (no reconstruction needed)
 * - Atomic updates (snapshot is updated in same transaction as step data)
 * - Instant resume (load snapshot, done)
 * - No cache synchronization issues
 */
@Service
public class StateSnapshotService
        implements com.apimarketplace.orchestrator.services.state.patch.RunCoalescingService.MutationFlushBridge {

    private static final Logger log = LoggerFactory.getLogger(StateSnapshotService.class);

    private final WorkflowRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final WorkflowEpochService workflowEpochService;
    private final WorkflowEventPublisher eventPublisher;
    private final StorageBreakdownService breakdownService;
    private final TxScopedSnapshotCache txCache;
    private final WorkflowMetrics workflowMetrics;

    // P2.3 - dedicated state-snapshot ObjectMapper with the
    // EpochStateRunningElideModule registered. Optional injection so existing
    // unit tests that mock-construct StateSnapshotService keep working without
    // touching their @BeforeEach. When the bean is absent (test scenarios), saves
    // fall back to the default mapper - i.e. zero behavior change vs pre-P2.3.
    @Autowired(required = false)
    @Qualifier("stateSnapshotMapper")
    private ObjectMapper stateSnapshotMapper;

    // Phase 2 jsonb_set wiring - optional injection so existing tests that
    // mock-construct StateSnapshotService keep working with default null
    // (which makes choosePatchPath() return false → full-rewrite always).
    @Autowired(required = false)
    private JsonbPatchExecutor patchExecutor;

    @Autowired(required = false)
    private MarkNodeCompletedPatchBuilder markNodeCompletedPatchBuilder;

    @Autowired(required = false)
    private MarkNodeAwaitingSignalPatchBuilder markNodeAwaitingSignalPatchBuilder;

    @Autowired(required = false)
    private IncrementNodeCountsOnlyPatchBuilder incrementNodeCountsOnlyPatchBuilder;

    @Autowired(required = false)
    private MarkNodeFailedPatchBuilder markNodeFailedPatchBuilder;

    @Autowired(required = false)
    private MarkNodeSkippedPatchBuilder markNodeSkippedPatchBuilder;

    @Autowired(required = false)
    private MarkNodeCompletedEpochOnlyPatchBuilder markNodeCompletedEpochOnlyPatchBuilder;

    @Autowired(required = false)
    private MarkNodeFailedEpochOnlyPatchBuilder markNodeFailedEpochOnlyPatchBuilder;

    @Autowired(required = false)
    private MarkNodePartialFailurePatchBuilder markNodePartialFailurePatchBuilder;

    @Autowired(required = false)
    private ResolveAwaitingSignalPatchBuilder resolveAwaitingSignalPatchBuilder;

    @Autowired(required = false)
    private AddReadyNodePatchBuilder addReadyNodePatchBuilder;

    @Autowired(required = false)
    private RemoveReadyNodePatchBuilder removeReadyNodePatchBuilder;

    @Autowired(required = false)
    private ReplaceReadyNodeSetPatchBuilder replaceReadyNodeSetPatchBuilder;

    @Autowired(required = false)
    private RecordEdgeStatusPatchBuilder recordEdgeStatusPatchBuilder;

    @Autowired(required = false)
    private RecordDecisionBranchPatchBuilder recordDecisionBranchPatchBuilder;

    /**
     * A2 Phase 4 - out-of-tx Redis cache for the serialized state_snapshot
     * JSON, validated against the {@code state_snapshot_seq} SQL column. When
     * not wired (test scenarios), {@link #getSnapshot} falls through to the
     * legacy DB+parse path with zero behavior change.
     */
    @Autowired(required = false)
    private StateSnapshotJsonCache snapshotJsonCache;

    @Autowired(required = false)
    private ExecutionQueueClaimStore executionQueueClaimStore;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Phase 2 feature flag. Default ON (flipped 2026-05-10) so a missing
     * {@code application.yml} entry no longer silently routes hot mutators
     * to the legacy full-rewrite path - production and CI both run the
     * patch path by design. Any environment that needs the legacy path can
     * set {@code STATE_SNAPSHOT_USE_JSONB_PATCH=false} explicitly. Tests
     * that exercise the full-rewrite branch override via reflection
     * ({@code setField("useJsonbPatch", false)}).
     */
    @Value("${state-snapshot.use-jsonb-patch:true}")
    private boolean useJsonbPatch;

    /**
     * Plan v4 §1.6 phase 2g POC - when true, {@link #saveSnapshotPatched} tries
     * the CAS path ({@link com.apimarketplace.orchestrator.services.state.patch.JsonbPatchExecutor#applyPatchesCas})
     * BEFORE the legacy pessimistic-lock {@code applyPatches}. On CAS success,
     * the legacy path is skipped. On retry-exhaust (3 conflicts), falls through
     * to {@code applyPatches} (degenerate to pessimistic since the caller still
     * holds {@code findByRunIdPublicForUpdate} - for now).
     *
     * <p>Real CAS benefit (no row-lock contention) lands when the 14 callsites
     * are flipped to skip {@code findByRunIdPublicForUpdate} entirely. Until
     * then, this dispatcher emits {@code cas_attempt_count} metrics + validates
     * the executor wiring. Env-var rollback: {@code ORCH_OPTIM_CAS_STATE_SNAPSHOT=false}.
     */
    @Value("${orchestrator.optim.cas-state-snapshot:true}")
    private boolean casEnabled;

    /** Plan §1.7 CAS retry budget. */
    private static final long[] CAS_RETRY_BACKOFF_MS = {1L, 5L, 15L};
    private static final double CAS_RETRY_JITTER = 0.20;

    /**
     * Plan v4 §1.11 - kill-switch for the CAS path. Optional injection so
     * narrow Spring tests boot without the kill-switch bean; null → no
     * kill-switch consulted, dispatcher always tries CAS when casEnabled.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.orchestrator.services.state.patch.CasKillSwitch casKillSwitch;

    /**
     * Plan v4 §3 - coalescer for DELTA merge under fan-out. Optional so
     * narrow tests boot without it; saveSnapshotPatchedCas falls back to
     * direct applyPatchesCas when null. Audit D M1 / E M2 wire-up.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.orchestrator.services.state.patch.RunCoalescingService runCoalescingService;

    public StateSnapshotService(WorkflowRunRepository runRepository, ObjectMapper objectMapper,
                                WorkflowEpochService workflowEpochService,
                                WorkflowEventPublisher eventPublisher,
                                StorageBreakdownService breakdownService,
                                TxScopedSnapshotCache txCache,
                                WorkflowMetrics workflowMetrics) {
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.workflowEpochService = workflowEpochService;
        this.eventPublisher = eventPublisher;
        this.breakdownService = breakdownService;
        this.txCache = txCache;
        this.workflowMetrics = workflowMetrics;
    }

    // ========================================================================
    // READ OPERATIONS
    // ========================================================================

    /**
     * Get the current snapshot for a run.
     * Returns empty snapshot if none exists.
     *
     * Note: Uses projection query (findStateSnapshotByRunIdPublic) to avoid returning
     * the full entity. This prevents Hibernate from keeping an entity proxy attached
     * to the session, which could cause connection leaks during streaming.
     */
    public StateSnapshot getSnapshot(String runId) {
        // L0 - per-tx cache. If a mutation already parsed/wrote inside the
        // same @Transactional, the cached snapshot IS the latest under the
        // row lock. Hits here cost ~50ns.
        java.util.Optional<StateSnapshot> cached = txCache.get(runId);
        if (cached.isPresent()) return cached.get();

        // L1 - out-of-tx Redis cache (A2 Phase 4 + plan v3 §4 3-tier read).
        // Algorithm:
        //   1. SELECT state_snapshot_seq (~0.3ms, 1-column projection).
        //   2. HMGET orchestrator:snapshot-cache:{runId} {seq, payload}; the
        //      cached entry is served only if its embedded seq matches the
        //      SQL oracle (Race-6 contract - drift cannot survive one RTT).
        //   3. On hit, parse the cached JSON; no JSONB column read.
        //   4. On miss/mismatch/Redis-down, fall through to L2 with the
        //      combined projection (seq + json) - saves 1 RT vs the old
        //      two-call sequence (audit v3 B M4 + C6).
        if (snapshotJsonCache != null) {
            java.util.Optional<Long> seqOpt =
                    runRepository.findStateSnapshotSeqByRunIdPublic(runId);
            if (seqOpt.isPresent()) {
                long expectedSeq = seqOpt.get();
                java.util.Optional<String> cachedJson =
                        snapshotJsonCache.getPayloadIfMatchesSeq(runId, expectedSeq);
                if (cachedJson.isPresent()) {
                    StateSnapshot parsed = parseSnapshotForRun(runId, cachedJson.get());
                    txCache.put(runId, parsed);
                    return parsed;
                }
            }
        }

        // L2 - DB read + parse. Populates L1 post-commit (and L0 via the
        // post-parse path inside parseSnapshotForRun).
        //
        // Note: the combined projection findSeqAndStateSnapshotByRunIdPublic
        // is available (plan v3 §4) and saves 1 RT on the L1-miss path. It
        // is intentionally NOT used here yet - switching the L2 fall-through
        // breaks ~30 mocks that stub findStateSnapshotByRunIdPublic. The
        // combined projection ships now for new callsites; existing readers
        // keep the legacy 1-column projection until the test mocks are
        // migrated in a follow-up.
        return runRepository.findStateSnapshotByRunIdPublic(runId)
                .map(json -> {
                    StateSnapshot parsed = parseSnapshotForRun(runId, json);
                    if (snapshotJsonCache != null) {
                        // M1 (audit A 2026-05-09): defer the cache populate to
                        // AFTER the enclosing tx commits.
                        final long capturedSeq = parsed.getSeq();
                        com.apimarketplace.orchestrator.services.transaction.TransactionalHelper
                                .runAfterCommitOrNow(() ->
                                        snapshotJsonCache.putIfNewer(runId, capturedSeq, json));
                    }
                    return parsed;
                })
                .orElse(StateSnapshot.empty());
    }

    /**
     * Check if snapshot exists for a run.
     */
    public boolean hasSnapshot(String runId) {
        return runRepository.findStateSnapshotByRunIdPublic(runId)
                .map(json -> json != null && !json.isBlank())
                .orElse(false);
    }

    /**
     * Get node counts for UI display.
     */
    public StateSnapshot.NodeCounts getNodeCounts(String runId, String nodeId) {
        return getSnapshot(runId).getNodeCounts(nodeId);
    }

    /**
     * Get edge counts for UI display.
     */
    public StateSnapshot.EdgeCounts getEdgeCounts(String runId, String from, String to) {
        return getSnapshot(runId).getEdgeCounts(from, to);
    }

    /**
     * Get all plan node IDs for a run (normalized keys like "trigger:start", "agent:check_safety").
     * Parses the plan stored in the workflow run entity and collects node IDs from all node types.
     * Returns empty set if run or plan not found.
     */
    public Set<String> getPlanNodeIds(String runId) {
        return runRepository.findByRunIdPublic(runId)
                .map(run -> {
                    Map<String, Object> plan = run.getPlan();
                    if (plan == null || plan.isEmpty()) return Set.<String>of();
                    try {
                        var workflowPlan = com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser
                                .parse(plan, run.getTenantId());
                        Set<String> nodeIds = new HashSet<>();
                        for (var t : workflowPlan.getTriggers()) {
                            String key = com.apimarketplace.orchestrator.utils.LabelNormalizer.triggerKey(t.label());
                            if (key != null) nodeIds.add(key);
                        }
                        for (var s : workflowPlan.getMcps()) {
                            String key = s.getNormalizedKey();
                            if (key != null) nodeIds.add(key);
                        }
                        for (var a : workflowPlan.getAgents()) {
                            String key = a.getNormalizedKey();
                            if (key != null) nodeIds.add(key);
                        }
                        for (var c : workflowPlan.getCores()) {
                            String key = c.getNormalizedKey();
                            if (key != null) nodeIds.add(key);
                        }
                        for (var t : workflowPlan.getTables()) {
                            String key = t.getNormalizedKey();
                            if (key != null) nodeIds.add(key);
                        }
                        for (var i : workflowPlan.getInterfaces()) {
                            String key = i.getNormalizedKey();
                            if (key != null) nodeIds.add(key);
                        }
                        return nodeIds;
                    } catch (Exception e) {
                        log.warn("[StateSnapshot] Failed to parse plan for runId={}: {}", runId, e.getMessage());
                        return Set.<String>of();
                    }
                })
                .orElse(Set.of());
    }

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    /**
     * Plan v4 E2E4 - load the run under PESSIMISTIC_WRITE AND ensure the entity
     * reflects the current DB row, not a stale Hibernate L1 copy.
     *
     * <p>Bug surfaced by k6 saturation (50 VU × 3 min on a single run):
     * callers upstream (e.g. {@code ReusableTriggerService.executeTriggerInternal})
     * load the run via the non-locked {@code findByRunIdPublic(runId)} for status
     * checks, which puts the entity into the persistence-context L1 cache.
     * When this service later calls {@code findByRunIdPublicForUpdate(runId)},
     * Hibernate still issues the {@code SELECT ... FOR UPDATE} (so the DB row
     * lock IS acquired) but returns the SAME entity from L1 - with the stale
     * {@code state_snapshot} JSON and {@code state_snapshot_seq} from the
     * earlier no-lock read. The mutator then computes a fresh
     * {@code snapshot.withIncrementedSeq()} against the stale seq, the write
     * hits V181's "must not regress" trigger (e.g. {@code was=479, new=428}),
     * the tx rolls back, the trigger fire is lost.
     *
     * <p>{@code entityManager.refresh(entity)} re-reads the row from DB under
     * the already-acquired lock, replacing the L1 entry. After this call
     * {@code run.getStateSnapshot()} and {@code run.getStateSnapshotSeq()}
     * reflect the live row, and serial mutators advance monotonically.
     *
     * <p>This helper is the ONE place that knows about the L1 trap. All ~50
     * call sites in this class use it instead of the raw repository method.
     */
    private java.util.Optional<WorkflowRunEntity> loadFreshForUpdate(String runId) {
        java.util.Optional<WorkflowRunEntity> found =
                runRepository.findByRunIdPublicForUpdate(runId);
        // Unit tests construct this service without @PersistenceContext injection,
        // so entityManager is null. Production always has it. Guard accordingly.
        if (entityManager != null) {
            found.ifPresent(entityManager::refresh);
        }
        return found;
    }

    /**
     * Initialize snapshot for a new run.
     */
    @Transactional
    public void initializeSnapshot(String runId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            if (run.getStateSnapshot() == null) {
                saveSnapshot(run, StateSnapshot.empty(), "initializeSnapshot");
                log.info("[StateSnapshot] Initialized empty snapshot for runId={}", runId);
            }
        });
    }

    // ========================================================================
    // NODE STATUS - DAG-scoped methods (preferred)
    // ========================================================================

    /**
     * Mark a node as completed within a specific DAG.
     *
     * <p>Plan v4 §1.5 phase 2n - tries the lock-free CAS path first
     * (no row lock; concurrent peers can race and we retry against
     * fresh snapshot). Falls back to pessimistic-lock + full rewrite
     * on retry exhaust / fallback path / flag off.
     */
    @Transactional
    public void markNodeCompleted(String runId, String triggerId, int epoch, String nodeId) {
        // CAS lock-free fast path - uses buildWithDeltaCounter to enable
        // DELTA merge on nodes.{X}.completed under fan-out (plan v4 §2t).
        // N items completing the same node → 1 merged +N patch instead of
        // N separate ASSIGN patches. The 6 ASSIGN sub-fields (running/failed/
        // skipped/timing) still force-flush per-item but the dominant cost
        // (the counter) is coalesced.
        if (useJsonbPatch && markNodeCompletedPatchBuilder != null && casEnabled
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            String tenantId = runRepository.findTenantIdByRunIdPublic(runId).orElse(null);
            if (tenantId != null) {
                boolean casOk = saveSnapshotPatchedCas(runId,
                        snap -> snap.markNodeCompleted(triggerId, nodeId, epoch),
                        (before, after) -> markNodeCompletedPatchBuilder.buildWithDeltaCounter(
                                before, after, triggerId, epoch, nodeId, tenantId),
                        "markNodeCompleted");
                if (casOk) {
                    log.debug("[StateSnapshot] Node marked completed (CAS lock-free DELTA): runId={}, triggerId={}, epoch={}, nodeId={}",
                            runId, triggerId, epoch, nodeId);
                    return;
                }
            }
        }
        // Pessimistic fallback (legacy path)
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.markNodeCompleted(triggerId, nodeId, epoch);
            if (useJsonbPatch && markNodeCompletedPatchBuilder != null) {
                String tenantId = run.getTenantId();
                saveSnapshotPatched(run, current, updated, "markNodeCompleted",
                        versioned -> markNodeCompletedPatchBuilder.build(
                                current, versioned, triggerId, epoch, nodeId, tenantId));
            } else {
                saveSnapshot(run, updated, "markNodeCompleted");
            }
            log.debug("[StateSnapshot] Node marked completed (pessimistic): runId={}, triggerId={}, epoch={}, nodeId={}",
                    runId, triggerId, epoch, nodeId);
        });
    }

    /**
     * Mark a node as failed within a specific DAG.
     * Plan v4 §1.5 phase 2n - lock-free CAS first, pessimistic fallback.
     */
    @Transactional
    public void markNodeFailed(String runId, String triggerId, int epoch, String nodeId) {
        if (useJsonbPatch && markNodeFailedPatchBuilder != null && casEnabled
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            String tenantId = runRepository.findTenantIdByRunIdPublic(runId).orElse(null);
            if (tenantId != null && saveSnapshotPatchedCas(runId,
                    snap -> snap.markNodeFailed(triggerId, nodeId, epoch),
                    (before, after) -> markNodeFailedPatchBuilder.build(
                            before, after, triggerId, epoch, nodeId, tenantId),
                    "markNodeFailed")) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.markNodeFailed(triggerId, nodeId, epoch);
            if (useJsonbPatch && markNodeFailedPatchBuilder != null) {
                String tenantId = run.getTenantId();
                saveSnapshotPatched(run, current, updated, "markNodeFailed",
                        versioned -> markNodeFailedPatchBuilder.build(
                                current, versioned, triggerId, epoch, nodeId, tenantId));
            } else {
                saveSnapshot(run, updated, "markNodeFailed");
            }
            log.debug("[StateSnapshot] Node marked failed (pessimistic): runId={}, triggerId={}, epoch={}, nodeId={}",
                    runId, triggerId, epoch, nodeId);
        });
    }

    /**
     * EpochState-only completion mark (no global {@link StateSnapshot.NodeCounts}
     * increment). For split-async barrier-seal aggregate writes - see
     * {@link StateSnapshot#markNodeCompletedEpochOnly} for the rationale (per-item
     * NodeCounts have already been written via {@link #incrementNodeCountsOnly};
     * re-using the regular {@link #markNodeCompleted} at the seal would inflate the
     * counter by one).
     */
    @Transactional
    public void markNodeCompletedEpochOnly(String runId, String triggerId, int epoch, String nodeId) {
        if (useJsonbPatch && markNodeCompletedEpochOnlyPatchBuilder != null && casEnabled
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            String tenantId = runRepository.findTenantIdByRunIdPublic(runId).orElse(null);
            if (tenantId != null && saveSnapshotPatchedCas(runId,
                    snap -> snap.markNodeCompletedEpochOnly(triggerId, nodeId, epoch),
                    (before, after) -> markNodeCompletedEpochOnlyPatchBuilder.build(
                            before, after, triggerId, epoch, nodeId, tenantId),
                    "markNodeCompletedEpochOnly")) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.markNodeCompletedEpochOnly(triggerId, nodeId, epoch);
            if (useJsonbPatch && markNodeCompletedEpochOnlyPatchBuilder != null) {
                String tenantId = run.getTenantId();
                saveSnapshotPatched(run, current, updated, "markNodeCompletedEpochOnly",
                        versioned -> markNodeCompletedEpochOnlyPatchBuilder.build(
                                current, versioned, triggerId, epoch, nodeId, tenantId));
            } else {
                saveSnapshot(run, updated, "markNodeCompletedEpochOnly");
            }
            log.debug("[StateSnapshot] Node marked completed (epoch-only, pessimistic): runId={}, triggerId={}, epoch={}, nodeId={}",
                    runId, triggerId, epoch, nodeId);
        });
    }

    /**
     * EpochState-only failure mark - companion to {@link #markNodeCompletedEpochOnly}.
     */
    @Transactional
    public void markNodeFailedEpochOnly(String runId, String triggerId, int epoch, String nodeId) {
        if (useJsonbPatch && markNodeFailedEpochOnlyPatchBuilder != null && casEnabled
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            String tenantId = runRepository.findTenantIdByRunIdPublic(runId).orElse(null);
            if (tenantId != null && saveSnapshotPatchedCas(runId,
                    snap -> snap.markNodeFailedEpochOnly(triggerId, nodeId, epoch),
                    (before, after) -> markNodeFailedEpochOnlyPatchBuilder.build(
                            before, after, triggerId, epoch, nodeId, tenantId),
                    "markNodeFailedEpochOnly")) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.markNodeFailedEpochOnly(triggerId, nodeId, epoch);
            if (useJsonbPatch && markNodeFailedEpochOnlyPatchBuilder != null) {
                String tenantId = run.getTenantId();
                saveSnapshotPatched(run, current, updated, "markNodeFailedEpochOnly",
                        versioned -> markNodeFailedEpochOnlyPatchBuilder.build(
                                current, versioned, triggerId, epoch, nodeId, tenantId));
            } else {
                saveSnapshot(run, updated, "markNodeFailedEpochOnly");
            }
            log.debug("[StateSnapshot] Node marked failed (epoch-only, pessimistic): runId={}, triggerId={}, epoch={}, nodeId={}",
                    runId, triggerId, epoch, nodeId);
        });
    }

    /**
     * Mark a node as skipped within a specific DAG.
     */
    @Transactional
    public void markNodeSkipped(String runId, String triggerId, int epoch, String nodeId) {
        if (useJsonbPatch && markNodeSkippedPatchBuilder != null && casEnabled
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            if (saveSnapshotPatchedCas(runId,
                    snap -> snap.markNodeSkipped(triggerId, nodeId, epoch),
                    (before, after) -> markNodeSkippedPatchBuilder.build(
                            before, after, triggerId, epoch, nodeId),
                    "markNodeSkipped")) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.markNodeSkipped(triggerId, nodeId, epoch);
            if (useJsonbPatch && markNodeSkippedPatchBuilder != null) {
                saveSnapshotPatched(run, current, updated, "markNodeSkipped",
                        versioned -> markNodeSkippedPatchBuilder.build(
                                current, versioned, triggerId, epoch, nodeId));
            } else {
                saveSnapshot(run, updated, "markNodeSkipped");
            }
            log.debug("[StateSnapshot] Node marked skipped (pessimistic): runId={}, triggerId={}, epoch={}, nodeId={}",
                    runId, triggerId, epoch, nodeId);
        });
    }

    /**
     * Mark a node as having had partial failures inside a split context (Phase 2.A).
     * Idempotent: re-applying for an already-marked node is a no-op.
     */
    @Transactional
    public void markNodePartialFailure(String runId, String triggerId, int epoch, String nodeId) {
        if (useJsonbPatch && markNodePartialFailurePatchBuilder != null && casEnabled
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            if (saveSnapshotPatchedCas(runId,
                    snap -> snap.markNodePartialFailure(triggerId, nodeId, epoch),
                    (before, after) -> markNodePartialFailurePatchBuilder.build(
                            before, after, triggerId, epoch, nodeId),
                    "markNodePartialFailure")) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.markNodePartialFailure(triggerId, nodeId, epoch);
            if (useJsonbPatch && markNodePartialFailurePatchBuilder != null) {
                saveSnapshotPatched(run, current, updated, "markNodePartialFailure",
                        versioned -> markNodePartialFailurePatchBuilder.build(
                                current, versioned, triggerId, epoch, nodeId));
            } else {
                saveSnapshot(run, updated, "markNodePartialFailure");
            }
            log.debug("[StateSnapshot] Node marked partial-failure (pessimistic): runId={}, triggerId={}, epoch={}, nodeId={}",
                    runId, triggerId, epoch, nodeId);
        });
    }

    // ========================================================================
    // NODE STATUS - Flat methods (legacy compat, delegate to DAG-scoped)
    // ========================================================================

    /**
     * Mark a node as completed (flat - searches all DAGs).
     */
    @Transactional
    public void markNodeCompleted(String runId, String nodeId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.markNodeCompleted(nodeId);
            saveSnapshot(run, updated, "markNodeCompleted");
            log.debug("[StateSnapshot] Node marked completed: runId={}, nodeId={}", runId, nodeId);
        });
    }

    /**
     * Mark a node as failed (flat - searches all DAGs).
     */
    @Transactional
    public void markNodeFailed(String runId, String nodeId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.markNodeFailed(nodeId);
            saveSnapshot(run, updated, "markNodeFailed");
            log.debug("[StateSnapshot] Node marked failed: runId={}, nodeId={}", runId, nodeId);
        });
    }

    /**
     * Mark a node as skipped (flat - searches all DAGs).
     */
    @Transactional
    public void markNodeSkipped(String runId, String nodeId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.markNodeSkipped(nodeId);
            saveSnapshot(run, updated, "markNodeSkipped");
            log.debug("[StateSnapshot] Node marked skipped: runId={}, nodeId={}", runId, nodeId);
        });
    }

    /**
     * Resolve an awaiting-signal node as SKIPPED within a specific DAG epoch.
     *
     * <p>Used for explicit signal cancellation: the signal wait is terminal, but it
     * must not increment completed counts or route through any selected successor.
     */
    @Transactional
    public void skipAwaitingSignal(String runId, String triggerId, int epoch, String nodeId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.skipAwaitingSignal(triggerId, nodeId, epoch);
            saveSnapshot(run, updated, "skipAwaitingSignal");
            log.debug("[StateSnapshot] Awaiting signal skipped: runId={}, triggerId={}, epoch={}, nodeId={}",
                    runId, triggerId, epoch, nodeId);
        });
    }

    /**
     * Resolve an awaiting-signal node as SKIPPED using the snapshot's default DAG.
     */
    @Transactional
    public void skipAwaitingSignal(String runId, String nodeId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            String triggerId = current.getDefaultTriggerId();
            DagState dagState = current.getDags().get(triggerId);
            int epoch = dagState != null ? dagState.getCurrentEpoch() : 0;
            StateSnapshot updated = current.skipAwaitingSignal(triggerId, nodeId, epoch);
            saveSnapshot(run, updated, "skipAwaitingSignal");
            log.debug("[StateSnapshot] Awaiting signal skipped: runId={}, triggerId={}, epoch={}, nodeId={}",
                    runId, triggerId, epoch, nodeId);
        });
    }

    // ========================================================================
    // SIGNAL SYSTEM
    // ========================================================================

    /**
     * Mark a node as awaiting signal within a specific DAG.
     */
    @Transactional
    public void markNodeAwaitingSignal(String runId, String triggerId, int epoch, String nodeId) {
        if (useJsonbPatch && markNodeAwaitingSignalPatchBuilder != null && casEnabled
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            String tenantId = runRepository.findTenantIdByRunIdPublic(runId).orElse(null);
            if (tenantId != null && saveSnapshotPatchedCas(runId,
                    snap -> snap.markNodeAwaitingSignal(triggerId, nodeId, epoch),
                    (before, after) -> markNodeAwaitingSignalPatchBuilder.build(
                            before, after, triggerId, epoch, nodeId, tenantId),
                    "markNodeAwaitingSignal")) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.markNodeAwaitingSignal(triggerId, nodeId, epoch);
            if (useJsonbPatch && markNodeAwaitingSignalPatchBuilder != null) {
                String tenantId = run.getTenantId();
                saveSnapshotPatched(run, current, updated, "markNodeAwaitingSignal",
                        versioned -> markNodeAwaitingSignalPatchBuilder.build(
                                current, versioned, triggerId, epoch, nodeId, tenantId));
            } else {
                saveSnapshot(run, updated, "markNodeAwaitingSignal");
            }
            log.debug("[StateSnapshot] Node marked awaiting signal (pessimistic): runId={}, triggerId={}, epoch={}, nodeId={}",
                    runId, triggerId, epoch, nodeId);
        });
    }

    /**
     * Mark a node as awaiting signal (flat - searches all DAGs).
     */
    @Transactional
    public void markNodeAwaitingSignal(String runId, String nodeId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.markNodeAwaitingSignal(nodeId);
            saveSnapshot(run, updated, "markNodeAwaitingSignal");
            log.debug("[StateSnapshot] Node marked awaiting signal: runId={}, nodeId={}", runId, nodeId);
        });
    }

    /**
     * Resolve a node's signal wait within a specific DAG and epoch (no timing).
     */
    @Transactional
    public void resolveAwaitingSignal(String runId, String triggerId, int epoch, String nodeId) {
        resolveAwaitingSignal(runId, triggerId, epoch, nodeId, 0L, false);
    }

    /**
     * Resolve a node's signal wait within a specific DAG and epoch, with wait duration.
     *
     * <p>If the epoch was pruned during cycle reset (signal resolved after epoch
     * was closed), restores the full EpochState from the
     * workflow_epochs table before resolving. Without this, the snapshot would only
     * contain a fresh EpochState with the interface node - missing the trigger and
     * other completed nodes, so ReadyNodeCalculator would never traverse to successors.
     *
     * @param durationMs the wall-clock time the node spent waiting for the signal
     *                   (resolvedAt - createdAt). 0 means no timing recorded.
     */
    @Transactional
    public void resolveAwaitingSignal(String runId, String triggerId, int epoch, String nodeId, long durationMs) {
        resolveAwaitingSignal(runId, triggerId, epoch, nodeId, durationMs, false);
    }

    /**
     * Resolve a node's signal wait within a specific DAG and epoch, with wait duration.
     *
     * @param durationMs       the wall-clock time the node spent waiting for the signal
     * @param keepInAwaiting   if true, the node stays in awaitingSignalNodeIds (split context:
     *                         one item's signal resolved but other items still have pending signals)
     */
    @Transactional
    public void resolveAwaitingSignal(String runId, String triggerId, int epoch, String nodeId,
                                      long durationMs, boolean keepInAwaiting) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot parsed = parseSnapshot(run);

            // Restore pruned epoch from workflow_epochs table if needed
            DagState dagState = parsed.getDags().get(triggerId);
            boolean epochPruned = dagState == null || dagState.getEpochState(epoch) == null;
            boolean restoredFromDb = false;
            if (epochPruned) {
                EpochState restored = workflowEpochService.getFullEpochState(runId, triggerId, epoch);
                if (restored != null) {
                    log.info("[StateSnapshot] Restoring pruned epoch from DB: runId={}, triggerId={}, epoch={}, nodeId={}",
                            runId, triggerId, epoch, nodeId);
                    parsed = parsed.withRestoredEpochState(triggerId, epoch, restored);
                    restoredFromDb = true;
                }
            }

            final StateSnapshot current = parsed;
            StateSnapshot updated = current.resolveAwaitingSignal(triggerId, nodeId, epoch, durationMs, keepInAwaiting);
            // Patch path NOT taken when the epoch was just restored from DB -
            // the in-memory `current` snapshot has the restored epoch in Java
            // but the DB JSONB does NOT have it (it's pruned). A jsonb_set
            // would target a non-existent epoch path → silent no-op + DB
            // divergence. Force full rewrite which serializes the restored
            // epoch back into the JSONB.
            if (useJsonbPatch && resolveAwaitingSignalPatchBuilder != null && !restoredFromDb) {
                saveSnapshotPatched(run, current, updated, "resolveAwaitingSignal",
                        versioned -> resolveAwaitingSignalPatchBuilder.build(
                                current, versioned, triggerId, epoch, nodeId));
            } else {
                saveSnapshot(run, updated, "resolveAwaitingSignal");
            }
            log.debug("[StateSnapshot] Awaiting signal resolved (epoch-scoped): runId={}, triggerId={}, epoch={}, nodeId={}, durationMs={}, keepInAwaiting={}",
                    runId, triggerId, epoch, nodeId, durationMs, keepInAwaiting);
        });
    }

    /**
     * Resolve a node's signal wait (flat - searches all DAGs, no timing).
     */
    @Transactional
    public void resolveAwaitingSignal(String runId, String nodeId) {
        resolveAwaitingSignal(runId, nodeId, 0L);
    }

    /**
     * Resolve a node's signal wait (flat - searches all DAGs, with timing).
     *
     * @param durationMs the wall-clock time the node spent waiting for the signal.
     */
    @Transactional
    public void resolveAwaitingSignal(String runId, String nodeId, long durationMs) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.resolveAwaitingSignal(nodeId, durationMs);
            saveSnapshot(run, updated, "resolveAwaitingSignal");
            log.debug("[StateSnapshot] Awaiting signal resolved: runId={}, nodeId={}, durationMs={}",
                    runId, nodeId, durationMs);
        });
    }

    /**
     * Ensure a node is marked as completed in a specific epoch's EpochState.
     *
     * <p>Idempotent: if the node is already in completedNodeIds, this is a no-op.
     * Does NOT increment NodeCounts - only ensures the EpochState is correct.
     *
     * <p>This is a safety net for H2 lost-update scenarios where concurrent
     * resolveAwaitingSignal() calls overwrite each other's changes. In production
     * (PostgreSQL), FOR UPDATE properly serializes writes. In H2 (used in tests),
     * MVCC may not fully serialize concurrent row modifications, causing one
     * resolveAwaitingSignal to lose the other's EpochState changes.
     *
     * @param runId the workflow run ID
     * @param triggerId the trigger/DAG ID
     * @param epoch the epoch number
     * @param nodeId the node ID to ensure is completed
     */
    @Transactional
    public void ensureNodeCompletedInEpoch(String runId, String triggerId, int epoch, String nodeId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            DagState dagState = current.getDags().get(triggerId);
            if (dagState == null) return;
            EpochState es = dagState.getEpochState(epoch);
            if (es == null) return;
            if (es.getCompletedNodeIds().contains(nodeId)) {
                return; // Already completed - no-op
            }
            // If the node is still awaiting signals (split context: keepInAwaiting=true),
            // do NOT force it into completedNodeIds. resolveAwaitingSignal() intentionally
            // kept it out because other split items still have pending signals.
            if (es.getAwaitingSignalNodeIds().contains(nodeId)) {
                log.debug("[StateSnapshot] Node still awaiting signal, skipping ensureCompleted: runId={}, nodeId={}", runId, nodeId);
                return;
            }
            // Node was lost due to concurrent write - re-assert it
            log.info("[StateSnapshot] Re-asserting lost node completion: runId={}, triggerId={}, epoch={}, nodeId={}",
                    runId, triggerId, epoch, nodeId);
            StateSnapshot updated = current.ensureNodeCompletedInEpoch(triggerId, nodeId, epoch);
            saveSnapshot(run, updated, "ensureNodeCompletedInEpoch");
        });
    }

    /**
     * Reset only nodes belonging to a specific DAG.
     * Preserves state for other DAGs' nodes.
     *
     * @param runId the workflow run ID
     * @param dagNodeIds the set of node IDs belonging to the DAG to reset
     */
    @Transactional
    public void resetDagSnapshot(String runId, Set<String> dagNodeIds) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.resetDag(dagNodeIds);
            saveSnapshot(run, updated, "resetDagSnapshot");
            log.info("[StateSnapshot] DAG snapshot reset: runId={}, dagNodeCount={}", runId, dagNodeIds.size());
        });
    }

    /**
     * Remove specific nodes from a single DAG's current epoch only.
     *
     * <p>Unlike {@link #resetDagSnapshot(String, Set)} which resets ALL DAGs and reactivates
     * their epochs (designed for step rerun), this method targets a single DAG and does NOT
     * reactivate any epochs. Used by loop back-edge iteration to "un-complete" body nodes
     * so they can be re-executed in the next loop iteration.
     *
     * @param runId the workflow run ID
     * @param triggerId the trigger/DAG ID to target
     * @param nodeIds the set of node IDs to remove from the DAG's epoch tracking sets
     */
    @Transactional
    public void removeNodesFromDag(String runId, String triggerId, Set<String> nodeIds) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.removeNodesFromDag(triggerId, nodeIds);
            saveSnapshot(run, updated, "removeNodesFromDag");
            log.info("[StateSnapshot] Removed nodes from DAG: runId={}, triggerId={}, nodeCount={}",
                    runId, triggerId, nodeIds.size());
        });
    }

    /**
     * Reset DAG nodes and set a specific node as ready in ONE atomic operation.
     *
     * <p>This is more efficient than calling resetDagSnapshot + addReadyNode separately
     * (1 DB round-trip instead of 2) and eliminates any synchronization issues.
     *
     * @param runId the workflow run ID
     * @param dagNodeIds the set of node IDs belonging to the DAG to reset
     * @param readyNodeId the node to mark as ready after reset
     */
    @Transactional
    public void resetDagAndSetReady(String runId, Set<String> dagNodeIds, String readyNodeId) {
        resetDagAndSetReady(runId, dagNodeIds, readyNodeId, null);
    }

    /**
     * Variant of {@link #resetDagAndSetReady(String, Set, String)} that targets the ready
     * node at an explicit owner DAG.
     *
     * <p>The 3-arg variant resolves the DAG for the ready node via the flat
     * {@code StateSnapshot.addReadyNode(String)} → {@code getDefaultTriggerId()}, which is
     * correct for single-trigger workflows but picks an <em>arbitrary</em> real trigger in
     * multi-trigger workflows (map iteration order). Step rerun knows the owner trigger of
     * the rerun target ({@code DAGIndependenceValidator.findOwnerTrigger}) and must pass it
     * here so the READY marker lands in the right DAG's epoch state.
     *
     * @param ownerTriggerId the trigger/DAG that owns {@code readyNodeId};
     *                       {@code null} falls back to the flat default-DAG resolution
     */
    @Transactional
    public void resetDagAndSetReady(String runId, Set<String> dagNodeIds, String readyNodeId,
                                    String ownerTriggerId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            // Chain operations: reset DAG execution state (counts always accumulate) then add
            // ready node. Owner-scoped: sibling DAGs must NOT be reactivated by a rerun
            // (nothing outside a trigger fire ever closes them - multi-trigger wedge).
            StateSnapshot reset = current.resetDag(dagNodeIds, ownerTriggerId);
            StateSnapshot updated = ownerTriggerId != null
                ? reset.addReadyNode(ownerTriggerId, readyNodeId)
                : reset.addReadyNode(readyNodeId);
            saveSnapshot(run, updated, "resetDagAndSetReady");
            log.info("[StateSnapshot] DAG reset and ready node set: runId={}, dagNodeCount={}, readyNode={}, ownerTriggerId={}",
                runId, dagNodeIds.size(), readyNodeId, ownerTriggerId);
        });
    }

    /**
     * Reset a specific DAG for a new epoch.
     * Other DAGs are NOT affected. Global NodeCounts/EdgeCounts are preserved.
     *
     * @param runId the workflow run ID
     * @param triggerId the trigger ID to reset
     * @param newGlobalEpoch the new global epoch number
     */
    @Transactional
    public void resetDagEpoch(String runId, String triggerId, int newGlobalEpoch) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.resetDag(triggerId, newGlobalEpoch);
            saveSnapshot(run, updated, "resetDagEpoch");
            log.info("[StateSnapshot] DAG epoch reset: runId={}, triggerId={}, newEpoch={}",
                    runId, triggerId, newGlobalEpoch);
        });
    }

    /**
     * Reset a specific DAG for a new epoch and set a ready node in one transaction.
     */
    @Transactional
    public void resetDagEpochAndSetReady(String runId, String triggerId, int newGlobalEpoch, String readyNodeId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.resetDag(triggerId, newGlobalEpoch)
                    .addReadyNode(triggerId, readyNodeId);
            saveSnapshot(run, updated, "resetDagEpochAndSetReady");
            log.info("[StateSnapshot] DAG epoch reset and ready: runId={}, triggerId={}, newEpoch={}, readyNode={}",
                    runId, triggerId, newGlobalEpoch, readyNodeId);
        });
    }

    /**
     * Prepare a DAG for the next trigger cycle WITHOUT creating an active epoch.
     *
     * <p>Sets up the trigger as ready so the frontend shows it as fireable,
     * but does NOT add the epoch to activeEpochs. This prevents "phantom epochs"
     * that cause hasAnyActiveEpoch() to return true when no real execution is running,
     * blocking the run from transitioning to WAITING_TRIGGER.
     *
     * <p>The actual epoch activation happens in executeTriggerInternal() via openEpoch().
     */
    @Transactional
    public void prepareDagForNextCycle(String runId, String triggerId, int newGlobalEpoch) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.prepareDagForNextCycle(triggerId, newGlobalEpoch, triggerId);
            saveSnapshot(run, updated, "prepareDagForNextCycle");
            log.info("[StateSnapshot] DAG prepared for next cycle (no active epoch): runId={}, triggerId={}, preparedEpoch={}",
                    runId, triggerId, newGlobalEpoch);
        });
    }

    // ========================================================================
    // PARALLEL EPOCH SUPPORT
    // ========================================================================

    /**
     * Open an epoch for a DAG: add it to activeEpochs and create EpochState if absent.
     * Must be called before executing a trigger so that computeFlatSet() includes
     * this epoch's node state in flat views (readyNodeIds, completedNodeIds, etc.).
     *
     * <p>Unlike resetDagEpoch(), this preserves existing EpochState and does NOT
     * increment fireCount. Safe to call multiple times for the same epoch.
     *
     * @param runId the workflow run ID
     * @param triggerId the trigger ID
     * @param epoch the epoch number to open
     */
    @Transactional
    public void openEpoch(String runId, String triggerId, int epoch) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.openEpochForDag(triggerId, epoch);
            saveSnapshot(run, updated, "openEpoch");

            // Dual-write: persist epoch header to workflow_epochs table
            DagState dagState = updated.getDags().get(triggerId);
            EpochState freshState = dagState != null ? dagState.getEpochState(epoch) : EpochState.fresh();
            workflowEpochService.openEpoch(runId, triggerId, epoch, freshState != null ? freshState : EpochState.fresh());
            String requestId = ExecutionQueueRequestContext.currentRequestId();
            if (requestId != null && executionQueueClaimStore != null) {
                try {
                    executionQueueClaimStore.markEpochStarted(requestId, runId, triggerId, epoch);
                } catch (Exception e) {
                    log.warn("[StateSnapshot] Claim-store markEpochStarted failed (non-fatal): runId={}, epoch={}, error={}",
                            runId, epoch, e.getMessage());
                }
            }

            log.info("[StateSnapshot] Opened epoch: runId={}, triggerId={}, epoch={}, activeEpochs={}",
                    runId, triggerId, epoch, dagState != null ? dagState.getActiveEpochs() : Set.of());
        });
    }

    /**
     * Prepare the next epoch as ready for a trigger, so the trigger appears in readyNodes
     * while the current epoch is still executing. This enables parallel epoch execution.
     *
     * <p>Creates a fresh EpochState for the next epoch (currentEpoch + 1) with the trigger
     * in its readyNodeIds, and adds it to the DAG's activeEpochs. The computeFlatSet union
     * ensures the trigger shows as ready in the flat view.
     *
     * @param runId the workflow run ID
     * @param triggerId the trigger ID to mark as ready for the next epoch
     */
    @Transactional
    public void prepareNextEpochReady(String runId, String triggerId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            // The DagState.advanceEpoch() already creates a fresh EpochState and adds to activeEpochs
            // But we don't want to advance yet - we just want to add the trigger to a new epoch's ready set.
            // Instead, we add a ready node to the current epoch. The trigger button readiness is
            // already controlled by EpochConcurrencyLimiter on the backend side.
            StateSnapshot updated = current.addReadyNode(triggerId, triggerId);
            saveSnapshot(run, updated, "prepareNextEpochReady");
            log.info("[StateSnapshot] Prepared trigger as ready for next fire: runId={}, triggerId={}",
                    runId, triggerId);
        });
    }

    /**
     * Reactivate the current epoch for a DAG so it appears in flat views.
     * Used after closing all epochs in WAITING_TRIGGER to make the trigger visible.
     *
     * @param runId the workflow run ID
     * @param triggerId the trigger/DAG ID
     */
    @Transactional
    public void reactivateCurrentEpoch(String runId, String triggerId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            DagState dag = current.getDags().get(triggerId);
            if (dag != null && !dag.getActiveEpochs().contains(dag.getCurrentEpoch())) {
                StateSnapshot updated = current.reactivateDagEpoch(triggerId);
                saveSnapshot(run, updated, "reactivateCurrentEpoch");
                log.info("[StateSnapshot] Reactivated epoch {} for trigger {}: runId={}",
                        dag.getCurrentEpoch(), triggerId, runId);
            }
        });
    }

    /**
     * Close a specific epoch for a DAG. Removes it from activeEpochs but keeps
     * its historical state. Updates flat views to reflect remaining active epochs only.
     *
     * @param runId the workflow run ID
     * @param triggerId the trigger ID
     * @param epoch the epoch number to close
     */
    @Transactional
    public void closeEpoch(String runId, String triggerId, int epoch) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);

            // Capture final EpochState BEFORE pruning (for workflow_epochs header + duration)
            DagState dagState = current.getDags().get(triggerId);
            EpochState finalState = dagState != null ? dagState.getEpochState(epoch) : null;

            // Compute epoch duration: same value used for both per-epoch and accumulation
            long epochDurationMs = 0L;
            if (finalState != null && finalState.getStartedAt() != null) {
                epochDurationMs = java.time.Duration.between(finalState.getStartedAt(), java.time.Instant.now()).toMillis();
            }

            // Prune + accumulate duration in StateSnapshot.totalDurationMs
            StateSnapshot updated = current.closeAndPruneEpochForDag(triggerId, epoch, epochDurationMs);
            saveSnapshot(run, updated, "closeEpoch");

            // Dual-write: close epoch header in workflow_epochs table with final state + duration
            if (finalState != null) {
                workflowEpochService.closeEpoch(runId, triggerId, epoch, finalState, epochDurationMs);
            }

            log.info("[StateSnapshot] Closed+pruned epoch: runId={}, triggerId={}, epoch={}, epochDurationMs={}",
                    runId, triggerId, epoch, epochDurationMs);
        });
    }

    /**
     * Close ALL active epochs for a specific DAG.
     * Used by SBS mode to auto-close previous epochs before opening a new one.
     * Closes each active epoch in the workflow_epochs table, then prunes them from StateSnapshot.
     *
     * @param runId the workflow run ID
     * @param triggerId the trigger ID
     * @return the set of epoch numbers that were closed
     */
    @Transactional
    public Set<Integer> closeAllActiveEpochs(String runId, String triggerId) {
        Set<Integer> closedEpochs = new HashSet<>();
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            DagState dagState = current.getDags().get(triggerId);
            if (dagState == null || !dagState.hasActiveEpochs()) {
                return;
            }

            // Close each active epoch in workflow_epochs table (dual-write)
            for (int epoch : dagState.getActiveEpochs()) {
                EpochState finalState = dagState.getEpochState(epoch);
                long epochDurationMs = 0L;
                if (finalState != null && finalState.getStartedAt() != null) {
                    epochDurationMs = java.time.Duration.between(finalState.getStartedAt(), java.time.Instant.now()).toMillis();
                }
                if (finalState != null) {
                    workflowEpochService.closeEpoch(runId, triggerId, epoch, finalState, epochDurationMs);
                }
                closedEpochs.add(epoch);
            }

            // Batch-close all active epochs in StateSnapshot
            StateSnapshot updated = current.closeAllActiveEpochsForDag(triggerId);
            saveSnapshot(run, updated, "closeAllActiveEpochs");

            log.info("[StateSnapshot] SBS auto-closed all active epochs: runId={}, triggerId={}, epochs={}",
                    runId, triggerId, closedEpochs);
        });
        return closedEpochs;
    }

    /**
     * Lightweight container for run-level info needed by SSE snapshots.
     */
    public record RunEpochInfo(Map<String, Object> metadata, String status) {}

    /**
     * Get run status for SSE snapshots via scalar projection (no JSONB load).
     */
    public RunEpochInfo getRunEpochInfo(String runId) {
        String status = runRepository.findStatusByRunIdPublic(runId)
                .map(com.apimarketplace.orchestrator.domain.workflow.RunStatus::getValue)
                .orElse("PENDING");
        return new RunEpochInfo(Map.of(), status);
    }

    /**
     * Check if any DAG has any active epoch in the snapshot.
     */
    public boolean hasAnyActiveEpoch(String runId) {
        return getSnapshot(runId).hasAnyActiveEpoch();
    }

    /**
     * Get all awaiting signal node IDs for a run.
     */
    public Set<String> getAwaitingSignalNodeIds(String runId) {
        return getSnapshot(runId).getAwaitingSignalNodeIds();
    }

    /**
     * Record a node completion AND return updated NodeCounts in a single transaction.
     * Epoch-scoped version: writes to the specific epoch's EpochState when triggerId is available.
     * Falls back to flat version when triggerId is null.
     *
     * @return the updated NodeCounts for the node after completion
     */
    @Transactional
    public StateSnapshot.NodeCounts recordNodeCompletionAndGetCounts(
            String runId, String nodeId, String finalStatus, String triggerId, int epoch) {
        return recordNodeCompletionAndGetCounts(runId, nodeId, finalStatus, triggerId, epoch, 0L);
    }

    /**
     * Record a node completion AND return updated NodeCounts in a single transaction, with timing.
     * Epoch-scoped version: writes to the specific epoch's EpochState when triggerId is available.
     * Falls back to flat version when triggerId is null.
     *
     * @param durationMs execution duration in milliseconds (0 = no timing)
     * @return the updated NodeCounts for the node after completion
     */
    @Transactional
    public StateSnapshot.NodeCounts recordNodeCompletionAndGetCounts(
            String runId, String nodeId, String finalStatus, String triggerId, int epoch, long durationMs) {
        if (triggerId == null) {
            // When triggerId is null but epoch is valid, resolve triggerId from snapshot
            // instead of falling back to the flat (non-epoch-scoped) version which loses epoch info.
            return loadFreshForUpdate(runId).map(run -> {
                StateSnapshot current = parseSnapshot(run);
                String resolvedTriggerId = current.getDefaultTriggerId();
                StateSnapshot updated = switch (finalStatus.toUpperCase()) {
                    case "COMPLETED", "SUCCESS" -> current.markNodeCompleted(resolvedTriggerId, nodeId, epoch, durationMs);
                    case "FAILED", "ERROR" -> current.markNodeFailed(resolvedTriggerId, nodeId, epoch, durationMs);
                    case "SKIPPED" -> current.markNodeSkipped(resolvedTriggerId, nodeId, epoch);
                    default -> {
                        log.warn("[StateSnapshot] Unknown final status: {}", finalStatus);
                        yield current;
                    }
                };
                saveSnapshot(run, updated, "recordNodeCompletionAndGetCounts");
                log.debug("[StateSnapshot] Node completion recorded (epoch-scoped, resolved triggerId): runId={}, nodeId={}, status={}, triggerId={}, epoch={}",
                        runId, nodeId, finalStatus, resolvedTriggerId, epoch);
                return updated.getNodeCounts(nodeId);
            }).orElse(StateSnapshot.NodeCounts.zero());
        }
        String upperStatus = finalStatus.toUpperCase();
        // FIX #1 - lock-free CAS fast path (mirrors markNodeCompleted/Failed/Skipped):
        // no SELECT…FOR UPDATE on the happy path, so concurrent completions on the same
        // run no longer serialize on the row lock. The returned NodeCounts are read back
        // from the tx-scoped cache (the committed `versioned` snapshot), so they stay
        // synchronous and reflect this completion. On retry-exhaust / flag-off / missing
        // builder, tryRecordNodeCompletionCas returns null and we drop to the unchanged
        // pessimistic path below.
        if (useJsonbPatch && casEnabled
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            StateSnapshot.NodeCounts casCounts =
                    tryRecordNodeCompletionCas(runId, nodeId, triggerId, epoch, durationMs, upperStatus);
            if (casCounts != null) {
                return casCounts;
            }
        }
        return loadFreshForUpdate(runId).map(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = switch (upperStatus) {
                case "COMPLETED", "SUCCESS" -> current.markNodeCompleted(triggerId, nodeId, epoch, durationMs);
                case "FAILED", "ERROR" -> current.markNodeFailed(triggerId, nodeId, epoch, durationMs);
                case "SKIPPED" -> current.markNodeSkipped(triggerId, nodeId, epoch);
                default -> {
                    log.warn("[StateSnapshot] Unknown final status: {}", finalStatus);
                    yield current;
                }
            };
            // Phase 2 patch path - dispatch by status to the matching builder.
            boolean tookPatchPath = false;
            if (useJsonbPatch) {
                String tenantId = run.getTenantId();
                if (("COMPLETED".equals(upperStatus) || "SUCCESS".equals(upperStatus))
                        && markNodeCompletedPatchBuilder != null) {
                    saveSnapshotPatched(run, current, updated, "recordNodeCompletionAndGetCounts",
                            versioned -> markNodeCompletedPatchBuilder.build(
                                    current, versioned, triggerId, epoch, nodeId, tenantId));
                    tookPatchPath = true;
                } else if (("FAILED".equals(upperStatus) || "ERROR".equals(upperStatus))
                        && markNodeFailedPatchBuilder != null) {
                    saveSnapshotPatched(run, current, updated, "recordNodeCompletionAndGetCounts",
                            versioned -> markNodeFailedPatchBuilder.build(
                                    current, versioned, triggerId, epoch, nodeId, tenantId));
                    tookPatchPath = true;
                } else if ("SKIPPED".equals(upperStatus) && markNodeSkippedPatchBuilder != null) {
                    saveSnapshotPatched(run, current, updated, "recordNodeCompletionAndGetCounts",
                            versioned -> markNodeSkippedPatchBuilder.build(
                                    current, versioned, triggerId, epoch, nodeId));
                    tookPatchPath = true;
                }
            }
            if (!tookPatchPath) {
                saveSnapshot(run, updated, "recordNodeCompletionAndGetCounts");
            }
            log.debug("[StateSnapshot] Node completion recorded (epoch-scoped): runId={}, nodeId={}, status={}, triggerId={}, epoch={}",
                    runId, nodeId, finalStatus, triggerId, epoch);
            return updated.getNodeCounts(nodeId);
        }).orElse(StateSnapshot.NodeCounts.zero());
    }

    /**
     * FIX #1 - lock-free CAS attempt for {@link #recordNodeCompletionAndGetCounts}.
     * Mirrors the CAS fast path of {@link #markNodeCompleted}/{@code markNodeFailed}/
     * {@code markNodeSkipped} but returns the post-commit {@link StateSnapshot.NodeCounts}
     * (read from the tx-scoped cache, i.e. the committed {@code versioned} snapshot) so
     * the caller keeps its synchronous return contract. Returns {@code null} to signal
     * the caller must take the pessimistic fallback (missing builder, missing tenantId,
     * unknown status, or CAS retry-exhausted). No {@code SELECT…FOR UPDATE} on this path.
     */
    private StateSnapshot.NodeCounts tryRecordNodeCompletionCas(
            String runId, String nodeId, String triggerId, int epoch, long durationMs, String upperStatus) {
        final String tenantId = runRepository.findTenantIdByRunIdPublic(runId).orElse(null);
        final java.util.function.UnaryOperator<StateSnapshot> mutator;
        final java.util.function.BiFunction<StateSnapshot, StateSnapshot, JsonbPatchBuilder.Result> builder;
        switch (upperStatus) {
            case "COMPLETED", "SUCCESS" -> {
                if (markNodeCompletedPatchBuilder == null || tenantId == null) return null;
                mutator = snap -> snap.markNodeCompleted(triggerId, nodeId, epoch, durationMs);
                // build (full-NodeCounts ASSIGN), NOT buildWithDeltaCounter: this is the
                // non-fan-out completion path (per-item fan-out uses incrementNodeCountsOnly),
                // so there is no concurrent same-node counter merge to coalesce - ASSIGN is exact.
                builder = (before, after) -> markNodeCompletedPatchBuilder.build(
                        before, after, triggerId, epoch, nodeId, tenantId);
            }
            case "FAILED", "ERROR" -> {
                if (markNodeFailedPatchBuilder == null || tenantId == null) return null;
                mutator = snap -> snap.markNodeFailed(triggerId, nodeId, epoch, durationMs);
                builder = (before, after) -> markNodeFailedPatchBuilder.build(
                        before, after, triggerId, epoch, nodeId, tenantId);
            }
            case "SKIPPED" -> {
                if (markNodeSkippedPatchBuilder == null) return null;
                mutator = snap -> snap.markNodeSkipped(triggerId, nodeId, epoch);
                builder = (before, after) -> markNodeSkippedPatchBuilder.build(
                        before, after, triggerId, epoch, nodeId);
            }
            default -> {
                return null;  // unknown status → pessimistic path emits the warning
            }
        }
        if (saveSnapshotPatchedCas(runId, mutator, builder, "recordNodeCompletionAndGetCounts")) {
            log.debug("[StateSnapshot] Node completion recorded (CAS lock-free): runId={}, nodeId={}, status={}, triggerId={}, epoch={}",
                    runId, nodeId, upperStatus, triggerId, epoch);
            return getNodeCounts(runId, nodeId);
        }
        return null;
    }

    /**
     * Increment only the global NodeCounts for per-item records without touching EpochState.
     * Used by split branches where individual items are skipped but the node itself is not.
     * Single DB transaction for all items.
     *
     * @param count number of items to increment
     * @return updated NodeCounts
     */
    @Transactional
    public StateSnapshot.NodeCounts incrementNodeCountsOnly(String runId, String nodeId, String status, int count) {
        if (count <= 0) return StateSnapshot.NodeCounts.zero();
        return loadFreshForUpdate(runId).map(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.incrementNodeCountsOnly(nodeId, status, count);
            if (useJsonbPatch && incrementNodeCountsOnlyPatchBuilder != null) {
                saveSnapshotPatched(run, current, updated, "incrementNodeCountsOnly",
                        versioned -> incrementNodeCountsOnlyPatchBuilder.build(current, versioned, nodeId));
            } else {
                saveSnapshot(run, updated, "incrementNodeCountsOnly");
            }
            log.debug("[StateSnapshot] NodeCounts incremented (no EpochState): runId={}, nodeId={}, status={}, count={}",
                    runId, nodeId, status, count);
            return updated.getNodeCounts(nodeId);
        }).orElse(StateSnapshot.NodeCounts.zero());
    }

    /**
     * Record a node completion AND return updated NodeCounts in a single transaction.
     * Eliminates the extra DB read that would be needed by calling recordNodeCompletion + getNodeCounts separately.
     *
     * @return the updated NodeCounts for the node after completion
     */
    @Transactional
    public StateSnapshot.NodeCounts recordNodeCompletionAndGetCounts(String runId, String nodeId, String finalStatus) {
        return loadFreshForUpdate(runId).map(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = switch (finalStatus.toUpperCase()) {
                case "COMPLETED", "SUCCESS" -> current.markNodeCompleted(nodeId);
                case "FAILED", "ERROR" -> current.markNodeFailed(nodeId);
                case "SKIPPED" -> current.markNodeSkipped(nodeId);
                default -> {
                    log.warn("[StateSnapshot] Unknown final status: {}", finalStatus);
                    yield current;
                }
            };
            saveSnapshot(run, updated, "recordNodeCompletionAndGetCounts");
            log.debug("[StateSnapshot] Node completion recorded: runId={}, nodeId={}, status={}", runId, nodeId, finalStatus);
            return updated.getNodeCounts(nodeId);
        }).orElse(StateSnapshot.NodeCounts.zero());
    }

    // ========================================================================
    // EDGE STATUS
    // ========================================================================

    /**
     * Record an edge traversal.
     */
    @Transactional
    public void recordEdgeStatus(String runId, String from, String to, String status) {
        log.debug("[StateSnapshot] Recording edge: runId={}, edge={}→{}, status={}", runId, from, to, status);
        // FIX #2 - lock-free CAS fast path (no SELECT…FOR UPDATE); pessimistic fallback below.
        if (useJsonbPatch && casEnabled && recordEdgeStatusPatchBuilder != null
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())
                && saveSnapshotPatchedCas(runId,
                        snap -> snap.incrementEdge(from, to, status),
                        (before, after) -> recordEdgeStatusPatchBuilder.build(before, after, from, to),
                        "recordEdgeStatus")) {
            return;
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.incrementEdge(from, to, status);
            if (useJsonbPatch && recordEdgeStatusPatchBuilder != null) {
                saveSnapshotPatched(run, current, updated, "recordEdgeStatus",
                        versioned -> recordEdgeStatusPatchBuilder.build(current, versioned, from, to));
            } else {
                saveSnapshot(run, updated, "recordEdgeStatus");
            }
            log.debug("[StateSnapshot] Edge status recorded: runId={}, edge={}→{}, status={}",
                    runId, from, to, status);
        });
    }

    /**
     * Record multiple edge traversals in a single transaction.
     * Eliminates N separate SELECT FOR UPDATE + parse + serialize cycles for N edges.
     *
     * @param runId the workflow run ID
     * @param edgeStatuses map of "from->to" edge keys to status (COMPLETED, SKIPPED)
     */
    @Transactional
    public void recordEdgeStatuses(String runId, Map<String, String> edgeStatuses) {
        if (edgeStatuses == null || edgeStatuses.isEmpty()) {
            return;
        }
        // FIX #2 - lock-free CAS fast path (replaces full-rewrite under FOR UPDATE).
        if (useJsonbPatch && casEnabled && recordEdgeStatusPatchBuilder != null
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            Set<String> edgeKeys = validEdgeKeys(edgeStatuses.keySet());
            if (!edgeKeys.isEmpty() && saveSnapshotPatchedCas(runId,
                    snap -> applyEdgeStatuses(snap, edgeStatuses),
                    (before, after) -> recordEdgeStatusPatchBuilder.buildBatch(before, after, edgeKeys),
                    "recordEdgeStatuses")) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = applyEdgeStatuses(current, edgeStatuses);
            saveSnapshot(run, updated, "recordEdgeStatuses");
            log.debug("[StateSnapshot] Batch edge status recorded: runId={}, edgeCount={}", runId, edgeStatuses.size());
        });
    }

    /** Apply a status-per-edge map (count 1 each) to a snapshot - shared by the CAS
     *  mutator and the pessimistic fallback so both paths increment identically. */
    private static StateSnapshot applyEdgeStatuses(StateSnapshot snap, Map<String, String> edgeStatuses) {
        StateSnapshot s = snap;
        for (var entry : edgeStatuses.entrySet()) {
            String[] parts = entry.getKey().split("->");
            if (parts.length == 2) {
                s = s.incrementEdge(parts[0], parts[1], entry.getValue());
            }
        }
        return s;
    }

    /** Subset of edge keys that are well-formed ("from->to") - the only ones the
     *  increment mutators materialise, hence the only ones buildBatch should patch. */
    private static Set<String> validEdgeKeys(Set<String> keys) {
        Set<String> valid = new java.util.HashSet<>();
        for (String key : keys) {
            if (key.split("->").length == 2) {
                valid.add(key);
            }
        }
        return valid;
    }

    /**
     * Increment edge counts by a given amount in a single transaction.
     * Used when multiple items are skipped for the same edge (e.g., split context
     * where 3 out of 5 items are not routed to a branch).
     *
     * @param runId the workflow run ID
     * @param edgeCountIncrements map of "from->to" edge keys to (status, count) pairs
     */
    @Transactional
    public void recordEdgeStatusesBatch(String runId, Map<String, Map.Entry<String, Integer>> edgeCountIncrements) {
        if (edgeCountIncrements == null || edgeCountIncrements.isEmpty()) {
            return;
        }
        // FIX #2 - lock-free CAS fast path (replaces full-rewrite under FOR UPDATE).
        if (useJsonbPatch && casEnabled && recordEdgeStatusPatchBuilder != null
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            Set<String> edgeKeys = validEdgeKeys(edgeCountIncrements.keySet());
            if (!edgeKeys.isEmpty() && saveSnapshotPatchedCas(runId,
                    snap -> applyEdgeCountIncrements(snap, edgeCountIncrements),
                    (before, after) -> recordEdgeStatusPatchBuilder.buildBatch(before, after, edgeKeys),
                    "recordEdgeStatusesBatch")) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = applyEdgeCountIncrements(current, edgeCountIncrements);
            saveSnapshot(run, updated, "recordEdgeStatusesBatch");
            log.debug("[StateSnapshot] Batch edge status increments recorded: runId={}, edges={}", runId, edgeCountIncrements.size());
        });
    }

    /** Apply a (status,count)-per-edge map to a snapshot - shared by the CAS mutator
     *  and the pessimistic fallback so both paths increment identically. */
    private static StateSnapshot applyEdgeCountIncrements(
            StateSnapshot snap, Map<String, Map.Entry<String, Integer>> edgeCountIncrements) {
        StateSnapshot s = snap;
        for (var entry : edgeCountIncrements.entrySet()) {
            String[] parts = entry.getKey().split("->");
            if (parts.length == 2) {
                String status = entry.getValue().getKey();
                int count = entry.getValue().getValue();
                for (int i = 0; i < count; i++) {
                    s = s.incrementEdge(parts[0], parts[1], status);
                }
            }
        }
        return s;
    }

    /**
     * Get edge counts in format suitable for pre-populating in-memory EdgeCounters.
     */
    public Map<String, Map<String, Integer>> getEdgeCountsForPrePopulation(String runId) {
        StateSnapshot snapshot = getSnapshot(runId);
        Map<String, Map<String, Integer>> result = new HashMap<>();

        for (var entry : snapshot.getEdges().entrySet()) {
            String edgeKey = entry.getKey();
            StateSnapshot.EdgeCounts counts = entry.getValue();
            if (counts.total() > 0) {
                Map<String, Integer> countsMap = new HashMap<>();
                countsMap.put("completed", counts.completed());
                countsMap.put("skipped", counts.skipped());
                countsMap.put("running", counts.running());
                result.put(edgeKey, countsMap);
            }
        }

        return result;
    }

    // ========================================================================
    // READY NODES
    // ========================================================================

    /**
     * Update the set of ready nodes for a specific DAG.
     */
    @Transactional
    public void updateReadyNodes(String runId, String triggerId, int epoch, Set<String> readyNodes) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.withReadyNodes(triggerId, readyNodes);
            saveSnapshot(run, updated, "updateReadyNodes");
            log.debug("[StateSnapshot] Ready nodes updated: runId={}, triggerId={}, epoch={}, count={}",
                    runId, triggerId, epoch, readyNodes.size());
        });
    }

    /**
     * Update the set of ready nodes (flat - uses default DAG).
     */
    @Transactional
    public void updateReadyNodes(String runId, Set<String> readyNodes) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.withReadyNodes(readyNodes);
            saveSnapshot(run, updated, "updateReadyNodes");
            log.debug("[StateSnapshot] Ready nodes updated: runId={}, count={}", runId, readyNodes.size());
        });
    }

    /**
     * Merge ready nodes after a node execution.
     *
     * Instead of replacing the entire ready set, this method:
     * 1. Preserves existing ready nodes from other DAGs
     * 2. Removes the executed node (it's no longer ready)
     * 3. Adds the new successor ready nodes
     *
     * This is critical for multi-DAG workflows where DAG1 may have ready nodes
     * while DAG2 is executing. A full replacement would lose DAG1's ready state.
     *
     * @param runId The workflow run ID
     * @param executedNodeId The node that was just executed (to remove from ready)
     * @param newReadyNodes The new ready nodes (successors of the executed node)
     */
    @Transactional
    public void mergeReadyNodesAfterExecution(String runId, String executedNodeId, Set<String> newReadyNodes) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            Set<String> merged = new java.util.HashSet<>(current.getReadyNodeIds());
            merged.remove(executedNodeId);
            merged.addAll(newReadyNodes);
            StateSnapshot updated = current.withReadyNodes(merged);
            saveSnapshot(run, updated, "mergeReadyNodesAfterExecution");
            log.debug("[StateSnapshot] Ready nodes merged after execution: runId={}, executed={}, added={}, total={}",
                    runId, executedNodeId, newReadyNodes.size(), merged.size());
        });
    }

    /**
     * Merge ready nodes after execution - DAG-scoped version.
     * Removes the executed node from its DAG's ready set and adds new ready nodes to that DAG.
     *
     * <p><b>FIX #3 (concurrency)</b>: the epoch-scoped branch ({@code epoch >= 0},
     * the only branch the live SBS engine takes) persists via a single
     * {@code jsonb_set} patch on {@code dags.{triggerId}.epochs.{epoch}.readyNodeIds}
     * through the lock-free CAS path, instead of re-serializing the entire ~30KB
     * snapshot per node. The remove-executed-node + add-N-successors mutation
     * touches exactly one set, so it collapses to one set-replacement patch.
     * If the CAS path can't run (flags off, builders/executor unwired, retry
     * exhausted) it falls through to the existing pessimistic full-rewrite -
     * behavior is identical, only the wire payload shrinks. The legacy
     * {@code epoch < 0} branch keeps the full-rewrite path (its mutators target
     * {@code currentEpochState}, which the {@code (triggerId, epoch)}-scoped
     * patch path cannot address safely).
     */
    @Transactional
    public void mergeReadyNodesAfterExecution(String runId, String triggerId, int epoch,
                                               String executedNodeId, Set<String> newReadyNodes) {
        // FIX #3 - lock-free CAS patch fast path for the epoch-scoped merge.
        // Only the single readyNodeIds set changes, so we emit one jsonb_set
        // patch rather than a full snapshot rewrite. On any inability to patch
        // (fallback / retry-exhaust) we drop to the pessimistic path below,
        // which is unchanged - correctness is never sacrificed.
        if (epoch >= 0 && useJsonbPatch && casEnabled
                && addReadyNodePatchBuilder != null && removeReadyNodePatchBuilder != null
                && replaceReadyNodeSetPatchBuilder != null
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            boolean casPatched = saveSnapshotPatchedCas(runId,
                    snap -> {
                        StateSnapshot s = snap.removeReadyNode(triggerId, executedNodeId, epoch);
                        if (newReadyNodes != null) {
                            for (String readyNode : newReadyNodes) {
                                s = s.addReadyNode(triggerId, readyNode, epoch);
                            }
                        }
                        return s;
                    },
                    (before, after) -> replaceReadyNodeSetPatchBuilder.build(before, after, triggerId, epoch),
                    "mergeReadyNodesAfterExecution");
            if (casPatched) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated;
            if (epoch >= 0) {
                // Epoch-scoped: target the specific epoch's EpochState
                updated = current.removeReadyNode(triggerId, executedNodeId, epoch);
                for (String readyNode : newReadyNodes) {
                    updated = updated.addReadyNode(triggerId, readyNode, epoch);
                }
            } else {
                // Legacy: target currentEpochState (epoch = -1 sentinel for "not set")
                updated = current.removeReadyNode(triggerId, executedNodeId);
                for (String readyNode : newReadyNodes) {
                    updated = updated.addReadyNode(triggerId, readyNode);
                }
            }
            saveSnapshot(run, updated, "mergeReadyNodesAfterExecution");
            // Diagnostic: verify the saved snapshot has the ready nodes
            Set<String> savedReady = updated.getReadyNodeIds();
            if (newReadyNodes != null && !newReadyNodes.isEmpty() && savedReady.isEmpty()) {
                // Log detailed state to help diagnose stale-read issues
                var dagState = updated.getDags().get(triggerId);
                log.warn("[StateSnapshot] Ready nodes merged but flat view is EMPTY! runId={}, triggerId={}, epoch={}, " +
                        "newReadyNodes={}, activeEpochs={}, currentEpoch={}, epochReadyNodes={}",
                        runId, triggerId, epoch, newReadyNodes,
                        dagState != null ? dagState.getActiveEpochs() : "NO_DAG",
                        dagState != null ? dagState.getCurrentEpoch() : -1,
                        dagState != null && dagState.getEpochState(epoch) != null
                            ? dagState.getEpochState(epoch).getReadyNodeIds() : "NO_EPOCH");
            }
            log.debug("[StateSnapshot] Ready nodes merged (DAG-scoped): runId={}, triggerId={}, epoch={}, executed={}, added={}, flatReady={}",
                    runId, triggerId, epoch, executedNodeId, newReadyNodes.size(), savedReady);
        });
    }

    /**
     * Add a node to ready set.
     */
    @Transactional
    public void addReadyNode(String runId, String nodeId) {
        if (useJsonbPatch && addReadyNodePatchBuilder != null && casEnabled
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            if (saveSnapshotPatchedCas(runId,
                    snap -> snap.addReadyNode(nodeId),
                    (before, after) -> {
                        String triggerId = before.getDefaultTriggerId();
                        int epoch = before.getDagState(triggerId).getCurrentEpoch();
                        return addReadyNodePatchBuilder.build(before, after, triggerId, epoch, nodeId);
                    },
                    "addReadyNode")) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.addReadyNode(nodeId);
            if (useJsonbPatch && addReadyNodePatchBuilder != null) {
                String triggerId = current.getDefaultTriggerId();
                int epoch = current.getDagState(triggerId).getCurrentEpoch();
                saveSnapshotPatched(run, current, updated, "addReadyNode",
                        versioned -> addReadyNodePatchBuilder.build(
                                current, versioned, triggerId, epoch, nodeId));
            } else {
                saveSnapshot(run, updated, "addReadyNode");
            }
            log.debug("[StateSnapshot] Node marked ready (pessimistic): runId={}, nodeId={}", runId, nodeId);
        });
    }

    /**
     * Remove a node from ready set.
     */
    @Transactional
    public void removeReadyNode(String runId, String nodeId) {
        if (useJsonbPatch && removeReadyNodePatchBuilder != null && casEnabled
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            if (saveSnapshotPatchedCas(runId,
                    snap -> snap.removeReadyNode(nodeId),
                    (before, after) -> {
                        String triggerId = before.findDagContaining(nodeId);
                        if (triggerId == null) return JsonbPatchBuilder.Result.fallback();
                        int epoch = before.getDagState(triggerId).getCurrentEpoch();
                        return removeReadyNodePatchBuilder.build(before, after, triggerId, epoch, nodeId);
                    },
                    "removeReadyNode")) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.removeReadyNode(nodeId);
            if (useJsonbPatch && removeReadyNodePatchBuilder != null) {
                String triggerId = current.findDagContaining(nodeId);
                int epoch = current.getDagState(triggerId).getCurrentEpoch();
                saveSnapshotPatched(run, current, updated, "removeReadyNode",
                        versioned -> removeReadyNodePatchBuilder.build(
                                current, versioned, triggerId, epoch, nodeId));
            } else {
                saveSnapshot(run, updated, "removeReadyNode");
            }
            log.debug("[StateSnapshot] Node removed from ready (pessimistic): runId={}, nodeId={}", runId, nodeId);
        });
    }

    // ========================================================================
    // DECISION BRANCHES
    // ========================================================================

    /**
     * Record a decision branch taken within a specific DAG.
     */
    @Transactional
    public void recordDecisionBranch(String runId, String triggerId, int epoch, String nodeId, String branch) {
        if (useJsonbPatch && recordDecisionBranchPatchBuilder != null && casEnabled
                && (casKillSwitch == null || !casKillSwitch.isCasDisabled())) {
            if (saveSnapshotPatchedCas(runId,
                    snap -> snap.recordDecisionBranch(triggerId, nodeId, epoch, branch),
                    (before, after) -> recordDecisionBranchPatchBuilder.build(
                            before, after, triggerId, epoch, nodeId, branch),
                    "recordDecisionBranch")) {
                return;
            }
        }
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.recordDecisionBranch(triggerId, nodeId, epoch, branch);
            if (useJsonbPatch && recordDecisionBranchPatchBuilder != null) {
                saveSnapshotPatched(run, current, updated, "recordDecisionBranch",
                        versioned -> recordDecisionBranchPatchBuilder.build(
                                current, versioned, triggerId, epoch, nodeId, branch));
            } else {
                saveSnapshot(run, updated, "recordDecisionBranch");
            }
            log.debug("[StateSnapshot] Decision branch recorded (pessimistic): runId={}, triggerId={}, epoch={}, nodeId={}, branch={}",
                    runId, triggerId, epoch, nodeId, branch);
        });
    }

    /**
     * Record a decision branch taken (flat - searches all DAGs).
     */
    @Transactional
    public void recordDecisionBranch(String runId, String nodeId, String branch) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.recordDecisionBranch(nodeId, branch);
            saveSnapshot(run, updated, "recordDecisionBranch");
            log.debug("[StateSnapshot] Decision branch recorded: runId={}, nodeId={}, branch={}",
                    runId, nodeId, branch);
        });
    }

    /**
     * Get branches taken for a decision node.
     */
    public Set<String> getDecisionBranches(String runId, String nodeId) {
        return getSnapshot(runId).getDecisionBranches(nodeId);
    }

    /**
     * Get all decision branches for a run.
     */
    public Map<String, Set<String>> getAllDecisionBranches(String runId) {
        return getSnapshot(runId).getDecisionBranches();
    }

    // ========================================================================
    // LOOP STATE
    // ========================================================================

    /**
     * Initialize loop state within a specific DAG.
     */
    @Transactional
    public void initializeLoopState(String runId, String triggerId, int epoch, String loopId, int totalItems) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.updateLoopState(triggerId, loopId, StateSnapshot.LoopState.initial(totalItems));
            saveSnapshot(run, updated, "initializeLoopState");
            log.debug("[StateSnapshot] Loop initialized: runId={}, triggerId={}, loopId={}, totalItems={}",
                    runId, triggerId, loopId, totalItems);
        });
    }

    /**
     * Initialize loop state (flat - searches all DAGs).
     */
    @Transactional
    public void initializeLoopState(String runId, String loopId, int totalItems) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.updateLoopState(loopId, StateSnapshot.LoopState.initial(totalItems));
            saveSnapshot(run, updated, "initializeLoopState");
            log.debug("[StateSnapshot] Loop initialized: runId={}, loopId={}, totalItems={}",
                    runId, loopId, totalItems);
        });
    }

    /**
     * Advance loop to next iteration.
     */
    @Transactional
    public void advanceLoopIteration(String runId, String loopId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot.LoopState loopState = current.getLoopState(loopId);
            if (loopState != null) {
                StateSnapshot updated = current.updateLoopState(loopId, loopState.nextIteration());
                saveSnapshot(run, updated, "advanceLoopIteration");
                log.debug("[StateSnapshot] Loop advanced: runId={}, loopId={}, index={}",
                        runId, loopId, loopState.currentIndex() + 1);
            }
        });
    }

    /**
     * Mark loop as completed.
     */
    @Transactional
    public void completeLoop(String runId, String loopId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot.LoopState loopState = current.getLoopState(loopId);
            if (loopState != null) {
                StateSnapshot updated = current.updateLoopState(loopId, loopState.complete());
                saveSnapshot(run, updated, "completeLoop");
                log.debug("[StateSnapshot] Loop completed: runId={}, loopId={}", runId, loopId);
            }
        });
    }

    /**
     * Get loop state.
     */
    public StateSnapshot.LoopState getLoopState(String runId, String loopId) {
        return getSnapshot(runId).getLoopState(loopId);
    }

    /**
     * Get all loop states for a run.
     */
    public Map<String, StateSnapshot.LoopState> getAllLoopStates(String runId) {
        return getSnapshot(runId).getLoops();
    }

    // ========================================================================
    // SPLIT STATE
    // ========================================================================

    /**
     * Initialize split state within a specific DAG.
     */
    @Transactional
    public void initializeSplitState(String runId, String triggerId, int epoch, String splitId, int itemCount) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.updateSplitState(triggerId, splitId, StateSnapshot.SplitState.initial(itemCount));
            saveSnapshot(run, updated, "initializeSplitState");
            log.debug("[StateSnapshot] Split initialized: runId={}, triggerId={}, splitId={}, itemCount={}",
                    runId, triggerId, splitId, itemCount);
        });
    }

    /**
     * Initialize split state (flat - searches all DAGs).
     */
    @Transactional
    public void initializeSplitState(String runId, String splitId, int itemCount) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot updated = current.updateSplitState(splitId, StateSnapshot.SplitState.initial(itemCount));
            saveSnapshot(run, updated, "initializeSplitState");
            log.debug("[StateSnapshot] Split initialized: runId={}, splitId={}, itemCount={}",
                    runId, splitId, itemCount);
        });
    }

    /**
     * Record split item completion.
     */
    @Transactional
    public void recordSplitItemCompleted(String runId, String splitId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot.SplitState splitState = current.getSplitState(splitId);
            if (splitState != null) {
                StateSnapshot updated = current.updateSplitState(splitId, splitState.incrementCompleted());
                saveSnapshot(run, updated, "recordSplitItemCompleted");
                log.debug("[StateSnapshot] Split item completed: runId={}, splitId={}, completed={}/{}",
                        runId, splitId, splitState.completedCount() + 1, splitState.itemCount());
            }
        });
    }

    /**
     * Record split item failure.
     */
    @Transactional
    public void recordSplitItemFailed(String runId, String splitId) {
        loadFreshForUpdate(runId).ifPresent(run -> {
            StateSnapshot current = parseSnapshot(run);
            StateSnapshot.SplitState splitState = current.getSplitState(splitId);
            if (splitState != null) {
                StateSnapshot updated = current.updateSplitState(splitId, splitState.incrementFailed());
                saveSnapshot(run, updated, "recordSplitItemFailed");
                log.debug("[StateSnapshot] Split item failed: runId={}, splitId={}", runId, splitId);
            }
        });
    }

    /**
     * Get split state.
     */
    public StateSnapshot.SplitState getSplitState(String runId, String splitId) {
        return getSnapshot(runId).getSplitState(splitId);
    }

    /**
     * Check if split is complete.
     */
    public boolean isSplitComplete(String runId, String splitId) {
        StateSnapshot.SplitState state = getSplitState(runId, splitId);
        return state != null && state.isComplete();
    }

    // ========================================================================
    // CONVENIENCE GETTERS FOR STATUS SETS
    // ========================================================================

    /**
     * Get completed node IDs for a specific DAG/epoch.
     */
    public Set<String> getCompletedNodeIds(String runId, String triggerId, int epoch) {
        return getSnapshot(runId).getCompletedNodeIds(triggerId, epoch);
    }

    /**
     * Get all completed node IDs for a run (flat - union across all DAGs).
     */
    public Set<String> getCompletedNodeIds(String runId) {
        return getSnapshot(runId).getCompletedNodeIds();
    }

    /**
     * Get all failed node IDs for a run.
     */
    public Set<String> getFailedNodeIds(String runId) {
        return getSnapshot(runId).getFailedNodeIds();
    }

    /**
     * Get all skipped node IDs for a run.
     */
    public Set<String> getSkippedNodeIds(String runId) {
        return getSnapshot(runId).getSkippedNodeIds();
    }

    /**
     * Get all running node IDs for a run.
     */
    public Set<String> getRunningNodeIds(String runId) {
        return getSnapshot(runId).getRunningNodeIds();
    }

    /**
     * Get all ready node IDs for a run.
     */
    public Set<String> getReadyNodeIds(String runId) {
        return getSnapshot(runId).getReadyNodeIds();
    }

    /**
     * Classify a completed cycle's outcome for display (the run badge / lastCycleResult). A cycle
     * with BOTH a failure AND a real (non-trigger) completion is a PARTIAL_SUCCESS; any failure
     * with no real completion is FAILED; otherwise COMPLETED. The trigger always completes, so it
     * is excluded from the "real completion" signal by the caller - otherwise an all-failed cycle
     * would read as partial.
     */
    public static RunStatus deriveCycleStatus(boolean hasFailures, boolean hasNonTriggerCompletions) {
        if (hasFailures) {
            return hasNonTriggerCompletions ? RunStatus.PARTIAL_SUCCESS : RunStatus.FAILED;
        }
        return RunStatus.COMPLETED;
    }

    /**
     * Reconcile SBS run status based on readyNodeIds.
     * Must run in a transaction since it loads + saves the run entity.
     *
     * <p>Logic:
     * - non-trigger nodes ready → PAUSED (user needs to click next step)
     * - only trigger nodes ready → WAITING_TRIGGER (waiting for external fire)
     * - no nodes ready → PAUSED (fallback)
     */
    @Transactional
    public void reconcileSbsRunStatus(String runId) {
        // SBS: ensure completed triggers stay in readySteps for re-fire capability.
        // Epochs stay open in SBS, so a trigger that has fired should remain "ready".
        StateSnapshot snapshot = getSnapshot(runId);
        Set<String> readyNodes = new java.util.HashSet<>(snapshot.getReadyNodeIds());
        boolean triggersAdded = false;
        for (var entry : snapshot.getDags().entrySet()) {
            String triggerId = entry.getKey();
            if (!triggerId.startsWith("trigger:")) continue;
            var dagState = entry.getValue();
            var epochState = dagState.currentEpochState();
            if (epochState.getCompletedNodeIds().contains(triggerId) && !readyNodes.contains(triggerId)) {
                readyNodes.add(triggerId);
                triggersAdded = true;
                log.info("[StateSnapshot] SBS reconcile: re-adding completed trigger {} to readyNodes", triggerId);
            }
        }
        if (triggersAdded) {
            // Persist the updated readyNodes to snapshot
            updateReadyNodes(runId, readyNodes);
        }

        boolean hasNonTriggerReady = readyNodes.stream()
                .anyMatch(id -> !id.startsWith("trigger:"));

        // Check for nodes awaiting signals (USER_APPROVAL, WEBHOOK_WAIT, etc.).
        // If any node is awaiting a signal, the workflow is NOT done - it should
        // stay PAUSED (not transition to WAITING_TRIGGER which would close epochs).
        Set<String> awaitingSignalNodes = getAwaitingSignalNodeIds(runId);
        boolean hasAwaitingSignal = !awaitingSignalNodes.isEmpty();

        RunStatus desired;
        if (hasNonTriggerReady || hasAwaitingSignal) {
            desired = RunStatus.PAUSED;
        } else if (readyNodes.stream().anyMatch(id -> id.startsWith("trigger:"))) {
            desired = RunStatus.WAITING_TRIGGER;
        } else {
            desired = RunStatus.PAUSED;
        }

        // When an SBS epoch completes and the run rests at WAITING_TRIGGER (reusable trigger
        // between fires), record the cycle result in run.metadata so the run badge shows
        // COMPLETED / FAILED / PARTIAL_SUCCESS instead of the raw idle "waiting_trigger". A cycle
        // with BOTH real (non-trigger) completions AND failures is a PARTIAL_SUCCESS, not a plain
        // FAILED (see deriveCycleStatus). NOTE: the AUTO rearm path
        // (ReusableTriggerService.resetForNextCycle) still collapses a mix to FAILED; unifying it
        // is a separate follow-up because lastCycleResult there also gates error-trigger and
        // notification dispatch.
        final String cycleResultValue;
        if (desired == RunStatus.WAITING_TRIGGER) {
            boolean hasFailures = false;
            boolean hasNonTriggerCompletions = false;
            for (var dag : snapshot.getDags().values()) {
                var es = dag.currentEpochState();
                if (!es.getFailedNodeIds().isEmpty()) hasFailures = true;
                if (es.getCompletedNodeIds().stream().anyMatch(id -> !id.startsWith("trigger:"))) {
                    hasNonTriggerCompletions = true;
                }
            }
            cycleResultValue = deriveCycleStatus(hasFailures, hasNonTriggerCompletions).getValue();
        } else {
            cycleResultValue = null;
        }

        runRepository.findByRunIdPublic(runId).ifPresent(run -> {
            boolean changed = false;
            if (run.getStatus() != desired) {
                log.info("[StateSnapshot] SBS status reconcile: runId={}, {} → {}, readyNodes={}",
                        runId, run.getStatus(), desired, readyNodes);
                run.setStatus(desired);
                changed = true;
            }
            if (cycleResultValue != null) {
                Map<String, Object> meta = run.getMetadata() != null
                        ? new HashMap<>(run.getMetadata()) : new HashMap<>();
                if (!cycleResultValue.equals(meta.get("lastCycleResult"))) {
                    meta.put("lastCycleResult", cycleResultValue);
                    run.setMetadata(meta);
                    changed = true;
                }
            }
            if (changed) {
                run.setUpdatedAt(java.time.Instant.now());
                runRepository.save(run);
            }
        });

        // SBS epoch lifecycle: do NOT close epochs here.
        // In SBS mode, the user may want to rerun any node, including the last one.
        // Closing the epoch here would prevent that rerun.
        //
        // Epochs are closed when the NEXT trigger fires:
        //   executeTriggerInternal() → closeAllActiveEpochs() (before opening new epoch)
        //
        // The epoch stays "open" (in activeEpochs) while in WAITING_TRIGGER.
        // This is correct because:
        //   1. The user can still rerun nodes from the current epoch
        //   2. Frontend shows the epoch as "active" (no closed_at) which is accurate
        //   3. When a new trigger fires, closeAllActiveEpochs() cleanly closes it

        // Emit WS event AFTER transaction completes so frontend gets the update
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "workflowStatus");
            payload.put("runId", runId);
            payload.put("status", desired.getValue());
            payload.put("message", "SBS status reconciled");
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("isRunning", false);
            eventPublisher.emitWorkflowStatus(runId, desired.getValue(),
                    "SBS status reconciled", payload, false);
            log.info("[StateSnapshot] SBS status WS event emitted: runId={}, status={}", runId, desired.getValue());
        } catch (Exception e) {
            log.error("[StateSnapshot] Failed to emit SBS status WS event: runId={}", runId, e);
        }
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    /**
     * Parse a StateSnapshot from JSON string.
     *
     * <p><b>Prefer {@link #parseSnapshotForRun(String, String)}</b> when a {@code runId}
     * is available - it goes through {@link TxScopedSnapshotCache} and avoids a Jackson
     * parse on every subsequent read in the same transaction. This raw variant is kept
     * for no-runId edge cases (test fixtures, migration tooling).
     *
     * @param json the JSON string (can be null or blank)
     * @return parsed StateSnapshot or empty if null/blank/invalid
     */
    public StateSnapshot parseSnapshotJson(String json) {
        if (json == null || json.isBlank()) {
            return StateSnapshot.empty();
        }
        try {
            return objectMapper.readValue(json, StateSnapshot.class);
        } catch (JsonProcessingException e) {
            String unwrapped = unwrapTextualJsonSnapshot(json);
            if (!unwrapped.equals(json)) {
                try {
                    return objectMapper.readValue(unwrapped, StateSnapshot.class);
                } catch (JsonProcessingException inner) {
                    e.addSuppressed(inner);
                }
            }
            log.warn("[StateSnapshot] Failed to parse snapshot JSON, returning empty", e);
            return StateSnapshot.empty();
        }
    }

    private String unwrapTextualJsonSnapshot(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isTextual()) {
                String text = node.asText();
                if (looksLikeJsonSnapshotObject(text)) {
                    return text;
                }
            }
        } catch (JsonProcessingException ignored) {
            // Keep the original parse failure as the logged cause.
        }
        return json;
    }

    private boolean looksLikeJsonSnapshotObject(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    /**
     * Cache-aware parse: returns the {@link TxScopedSnapshotCache} entry for {@code runId}
     * if one exists in the current transaction, otherwise parses {@code json} via Jackson
     * and caches the result. Outside an active transaction (recovery, schedulers, tests)
     * the cache is a no-op and this degrades to a plain {@code parseSnapshotJson}.
     *
     * <p>Eliminates the dominant Jackson allocation observed in the OOM 2026-05-06 12:22
     * incident: per-tx, the same JSONB blob was previously re-parsed up to 36 times
     * during split-fan-out / async barrier seal flows.
     *
     * @param runId the public run identifier (cache key); if null, falls back to direct parse
     * @param json  the JSON string read from {@code workflow_runs.state_snapshot}
     */
    public StateSnapshot parseSnapshotForRun(String runId, String json) {
        if (runId == null) return parseSnapshotJson(json);
        java.util.Optional<StateSnapshot> cached = txCache.get(runId);
        if (cached.isPresent()) return cached.get();
        StateSnapshot parsed = parseSnapshotJson(json);
        txCache.put(runId, parsed);
        return parsed;
    }

    private StateSnapshot parseSnapshot(WorkflowRunEntity run) {
        return parseSnapshotForRun(run.getRunIdPublic(), run.getStateSnapshot());
    }

    /**
     * Atomic claim within a specific DAG: verify node is READY, move to RUNNING.
     */
    @Transactional
    public boolean claimNodeForExecution(String runId, String triggerId, int epoch, String nodeId) {
        return loadFreshForUpdate(runId).map(run -> {
            StateSnapshot current = parseSnapshot(run);
            if (!current.getReadyNodeIds().contains(nodeId)) {
                log.warn("[StateSnapshot] Claim rejected: node {} not in readyNodeIds for run {}, triggerId={}, epoch={}",
                        nodeId, runId, triggerId, epoch);
                return false;
            }
            // Move from ready to running within the specific DAG and epoch
            StateSnapshot updated;
            if (epoch >= 0) {
                updated = current.removeReadyNode(triggerId, nodeId, epoch)
                                 .addRunningNode(triggerId, nodeId, epoch);
            } else {
                updated = current.removeReadyNode(triggerId, nodeId)
                                 .addRunningNode(triggerId, nodeId);
            }
            saveSnapshot(run, updated, "claimNodeForExecution");
            log.info("[StateSnapshot] Node claimed for execution: runId={}, triggerId={}, epoch={}, nodeId={}",
                    runId, triggerId, epoch, nodeId);
            return true;
        }).orElse(false);
    }

    /**
     * Atomic claim: verify node is READY, move to RUNNING, remove from readyNodeIds (flat).
     */
    @Transactional
    public boolean claimNodeForExecution(String runId, String nodeId) {
        return loadFreshForUpdate(runId).map(run -> {
            StateSnapshot current = parseSnapshot(run);
            if (!current.getReadyNodeIds().contains(nodeId)) {
                log.warn("[StateSnapshot] Claim rejected: node {} not in readyNodeIds for run {}", nodeId, runId);
                return false;
            }
            // Move from ready to running
            StateSnapshot updated = current.removeReadyNode(nodeId).addRunningNode(nodeId);
            saveSnapshot(run, updated, "claimNodeForExecution");
            log.info("[StateSnapshot] Node claimed for execution: runId={}, nodeId={}", runId, nodeId);
            return true;
        }).orElse(false);
    }

    /**
     * Release a node claim taken by {@link #claimNodeForExecution(String, String)} when the
     * execution threw <em>without</em> the engine persisting any terminal outcome for the node.
     *
     * <p>SBS robustness: the WS-driven execute path claims the node (READY → RUNNING) and then
     * dispatches asynchronously. When the async execution dies before the V2 pipeline records
     * COMPLETED/FAILED/SKIPPED/AWAITING_SIGNAL (e.g. tree rebuild failure), the node used to be
     * stuck - no longer READY, never resolved - and every further click was rejected with
     * NODE_NOT_READY until a manual rerun.
     *
     * <p>Guarded no-op when the node reached ANY resolved state (completed, failed, skipped,
     * awaiting signal - flat across DAGs): terminal marks already removed the RUNNING entry and
     * own the node's state; re-adding READY would double-execute.
     *
     * @return true when the claim was released (node re-added to READY)
     */
    @Transactional
    public boolean releaseNodeClaimIfUnresolved(String runId, String nodeId) {
        return loadFreshForUpdate(runId).map(run -> {
            StateSnapshot current = parseSnapshot(run);
            if (current.getCompletedNodeIds().contains(nodeId)
                    || current.getFailedNodeIds().contains(nodeId)
                    || current.getSkippedNodeIds().contains(nodeId)
                    || current.getAwaitingSignalNodeIds().contains(nodeId)) {
                log.debug("[StateSnapshot] Claim release skipped (node resolved): runId={}, nodeId={}", runId, nodeId);
                return false;
            }
            if (current.getReadyNodeIds().contains(nodeId)) {
                return false; // already ready - nothing to release
            }
            // Re-add READY in the DAG that tracks the node as RUNNING (pre-elide JSONB);
            // post-elide (running lives only in Redis) falls back to the flat default-DAG
            // resolution - acceptable for this error-recovery path: the flat ready union
            // is what drives both the FE play button and the claim check.
            StateSnapshot updated = current.releaseRunningToReady(nodeId);
            saveSnapshot(run, updated, "releaseNodeClaimIfUnresolved");
            log.warn("[StateSnapshot] Released stale node claim (execution died before any outcome): runId={}, nodeId={}",
                    runId, nodeId);
            return true;
        }).orElse(false);
    }

    /**
     * Persistence dispatcher: defaults to {@link #saveSnapshotFullRewrite} unless a
     * caller has explicitly chosen a patch builder via
     * {@link #saveSnapshotPatched}. All ~22 mutators in this file route through
     * this method, so a single switch (the {@code state-snapshot.use-jsonb-patch}
     * flag, plus per-mutator wiring) controls behavior.
     *
     * <p>Renamed from the historical {@code saveSnapshot} (now
     * {@link #saveSnapshotFullRewrite}). The ArchUnit guard
     * {@code SetStateSnapshotOnlyInFullRewriteTest} pins that
     * {@link WorkflowRunEntity#setStateSnapshot(String)} is invoked ONLY from
     * {@code saveSnapshotFullRewrite} - the patch path uses a native UPDATE
     * with {@code jsonb_set} on the row directly, not via the entity setter.
     */
    private void saveSnapshot(WorkflowRunEntity run, StateSnapshot snapshot, String mutator) {
        // Mutators that have not opted into the patch path always take the
        // full-rewrite. Patch-aware mutators call saveSnapshotPatched directly.
        saveSnapshotFullRewrite(run, snapshot, mutator);
    }

    /**
     * Legacy persistence path: increment seq, full Jackson serialize, full
     * row UPDATE via Hibernate. Kept verbatim - the patch path falls back here
     * when the builder cannot express the mutation, and ~17 of the 22 mutators
     * stay on this path indefinitely (they're rare or touch fields not covered
     * by the 3 patch builders).
     *
     * <p><b>Hibernate cache contract</b>: this method invokes
     * {@link WorkflowRunEntity#setStateSnapshot(String)}; ArchUnit pins it as
     * the ONLY caller. The patch path MUST detach the entity before its native
     * UPDATE so a stale Java setter can never clobber the {@code jsonb_set}
     * result via auto-flush.
     */
    private void saveSnapshotFullRewrite(WorkflowRunEntity run, StateSnapshot snapshot, String mutator) {
        String runId = run.getRunIdPublic();
        long t0 = System.nanoTime();
        long bytesWritten = 0L;
        boolean dbWriteSucceeded = false;
        try {
            StateSnapshot versioned = snapshot.withIncrementedSeq();
            String json;
            if (stateSnapshotMapper != null) {
                ObjectWriter writer = stateSnapshotMapper.writer()
                        .withAttribute(EpochStateRunningElideSerializer.TENANT_ATTRIBUTE,
                                       run.getTenantId());
                json = writer.writeValueAsString(versioned);
            } else {
                json = objectMapper.writeValueAsString(versioned);
            }
            String previousJson = run.getStateSnapshot();
            long oldSize = previousJson != null ? previousJson.getBytes(StandardCharsets.UTF_8).length : 0;
            long newSize = json.getBytes(StandardCharsets.UTF_8).length;
            bytesWritten = newSize;
            // Plan v4 E2E5 - native atomic UPDATE for both state_snapshot and
            // state_snapshot_seq. Was: run.setStateSnapshot + setStateSnapshotSeq
            // + runRepository.save(run). Detached-entity merge (the most common
            // path under load) emits a full-row UPDATE that includes every
            // column whose in-memory value differs from the live row - including
            // state_snapshot_seq with whatever stale value the L1 cache held.
            // V181 trigger rejected 13k+ writes per 3-min k6 saturation as a
            // result. Native UPDATE on these two columns only is bug-tight.
            //
            // entityManager.detach BEFORE the native UPDATE prevents Hibernate
            // from re-flushing dirty fields after the native UPDATE commits.
            // Without it, an in-tx auto-flush could re-emit a full UPDATE
            // that clobbers our atomic write. Plan v4 §1.4 invariant.
            if (entityManager != null) {
                entityManager.detach(run);
            }
            // Plan v4 E2E5 - seq is incremented DB-side (state_snapshot_seq + 1)
            // so we don't depend on the in-memory value, which may be stale
            // when a non-locked load earlier in the tx populated Hibernate L1.
            int rows = runRepository.updateSnapshotAndSeq(runId, json);
            if (rows == 0) {
                log.warn("[StateSnapshot] updateSnapshotAndSeq touched 0 rows for runId={} "
                        + "(row deleted between load and UPDATE - surfacing as failure)", runId);
                throw new IllegalStateException("Run row vanished during saveSnapshotFullRewrite: " + runId);
            }
            // Keep the in-memory entity coherent. The seq used here is the
            // builder's best guess (in-memory pre-write seq + 1); the DB row
            // is authoritative. Post-write callers in this tx that need exact
            // seq must re-read via getRunEpochInfo / similar.
            run.setStateSnapshot(json);
            run.setStateSnapshotSeq(versioned.getSeq());
            dbWriteSucceeded = true;
            if (newSize != oldSize) {
                // TODO(storage-drift): this passes the in-memory JSON byte length while
                // StorageReconciliationQueries.EXECUTION_DATA aggregates pg_column_size
                // (TOAST-compressed disk size). The asymmetry guarantees drift between
                // every state-snapshot write and the daily 2 AM reconciliation, which is
                // why StorageReconciliationService.refreshExecutionData has to mask the
                // residual on every GET /storage/quota and /storage/breakdown. Aligning
                // units (or dropping incremental tracking entirely + shortening the cron)
                // would let us remove that inline-refresh workaround.
                breakdownService.trackSizeChange(run.getTenantId(), "EXECUTION_DATA", newSize - oldSize);
            }
            txCache.put(runId, versioned);
        } catch (JsonProcessingException e) {
            txCache.invalidate(runId);
            log.error("[StateSnapshot] Failed to serialize snapshot for runId={}", runId, e);
            throw new RuntimeException("Failed to save state snapshot", e);
        } finally {
            if (dbWriteSucceeded && workflowMetrics != null) {
                workflowMetrics.recordStateSnapshotSave(mutator, "full_rewrite", bytesWritten, System.nanoTime() - t0);
            }
        }
    }

    /**
     * Phase 2 patch-path persistence. Increments seq Java-side, asks the
     * {@code builderResult} for the patch list, applies the patches via a
     * single {@code jsonb_set} cascade, and updates the {@code TxScopedSnapshotCache}.
     *
     * <p>Falls back to {@link #saveSnapshotFullRewrite} when the builder
     * returns {@link JsonbPatchBuilder.Result#fallback()} or the executor /
     * builder is not wired (test scenarios with mock construction).
     *
     * <p>NO-OP path: when the builder returns {@link JsonbPatchBuilder.Result#noOp()},
     * this method does nothing - no DB write, no seq increment, no cache update.
     * The caller's mutator already produced an in-memory {@code after} structurally
     * equal to {@code before}, so observability does not regress (the WS publisher
     * polls DB and sees no change either).
     *
     * @param run            attached entity from {@code findByRunIdPublicForUpdate}
     * @param before         pre-mutation snapshot
     * @param afterUnversioned post-mutation snapshot WITHOUT seq increment
     * @param mutator        mutator name (for metrics)
     * @param builderInvoker function that, given the seq-incremented {@code after},
     *                       returns a patch result. The lambda style lets each
     *                       mutator pass its own builder + extra args without a
     *                       generic-explosion in this method.
     */
    private void saveSnapshotPatched(WorkflowRunEntity run,
                                     StateSnapshot before,
                                     StateSnapshot afterUnversioned,
                                     String mutator,
                                     java.util.function.Function<StateSnapshot, JsonbPatchBuilder.Result> builderInvoker) {
        if (!useJsonbPatch || patchExecutor == null) {
            // Flag off or executor not wired: take the legacy path.
            if (workflowMetrics != null && useJsonbPatch && patchExecutor == null) {
                workflowMetrics.recordPatchFallback(mutator, "executor_unwired");
            }
            saveSnapshotFullRewrite(run, afterUnversioned, mutator);
            return;
        }
        String runId = run.getRunIdPublic();
        long t0 = System.nanoTime();
        StateSnapshot versioned = afterUnversioned.withIncrementedSeq();
        JsonbPatchBuilder.Result result;
        try {
            result = builderInvoker.apply(versioned);
        } catch (RuntimeException e) {
            log.warn("[StateSnapshot] Builder for mutator={} threw - falling back to full rewrite", mutator, e);
            if (workflowMetrics != null) workflowMetrics.recordPatchFallback(mutator, "builder_exception");
            saveSnapshotFullRewrite(run, afterUnversioned, mutator);
            return;
        }
        if (result.isNoOp()) {
            // Skip entirely - the mutation was a true no-op.
            return;
        }
        if (result.isFallback()) {
            if (workflowMetrics != null) workflowMetrics.recordPatchFallback(mutator, "builder_returned_fallback");
            saveSnapshotFullRewrite(run, afterUnversioned, mutator);
            return;
        }
        List<JsonbPatch> patches = result.patches().orElseThrow();
        long payloadBytes = patchExecutor.estimatePayloadBytes(patches);
        // CRITICAL: detach the entity BEFORE the native UPDATE.
        // Without detach, Hibernate's auto-flush could write the in-memory
        // run.stateSnapshot (which we never updated on this path - but a
        // dirty caller upstream could have left it dirty) right before the
        // jsonb_set, clobbering the patch.
        try {
            entityManager.detach(run);

            // Plan v4 §1.6 phase 2g POC + §1.11 kill-switch - try CAS first
            // when flag enabled AND no kill-switch active. Under current
            // row-lock-held semantics this is degenerate (always succeeds on
            // first try because no concurrent writer can change the seq while
            // the caller holds the lock). The dispatcher is in place for when
            // the 14-callsite-flip phase drops the row lock. On retry-exhaust
            // → fall through to applyPatches (legacy path).
            boolean casKilled = casKillSwitch != null && casKillSwitch.isCasDisabled();
            if (casEnabled && !casKilled
                    && tryCasPath(runId, patches, versioned, mutator, payloadBytes, t0)) {
                return;  // CAS path committed; legacy applyPatches skipped
            }

            // A2 mirror: thread the post-increment seq into the same UPDATE so
            // the SQL column stays in lockstep with the JSONB {seq} field.
            // Required for the out-of-tx SnapshotService cache to invalidate
            // by seq alone (no JSONB read).
            int updated = patchExecutor.applyPatches(runId, patches, versioned.getSeq());
            if (updated == 0) {
                log.warn("[StateSnapshot] Patch UPDATE 0 rows for runId={} mutator={} - falling back to full rewrite",
                        runId, mutator);
                if (workflowMetrics != null) workflowMetrics.recordPatchFallback(mutator, "zero_rows_updated");
                // Best effort fallback: re-find and full-rewrite. The run may have been deleted.
                loadFreshForUpdate(runId).ifPresent(refound ->
                        saveSnapshotFullRewrite(refound, afterUnversioned, mutator));
                return;
            }
            // Update the per-tx cache so subsequent reads in this tx see post-patch state.
            txCache.put(runId, versioned);
            if (workflowMetrics != null) {
                workflowMetrics.recordPatchPayloadBytes(mutator, payloadBytes);
                workflowMetrics.recordStateSnapshotSave(mutator, "jsonb_set", payloadBytes,
                        System.nanoTime() - t0);
            }
        } catch (RuntimeException e) {
            txCache.invalidate(runId);
            log.error("[StateSnapshot] Patch UPDATE failed for runId={} mutator={}, falling back",
                    runId, mutator, e);
            if (workflowMetrics != null) workflowMetrics.recordPatchFallback(mutator, "patch_update_exception");
            // Re-find since detach may have severed our reference.
            loadFreshForUpdate(runId).ifPresent(refound ->
                    saveSnapshotFullRewrite(refound, afterUnversioned, mutator));
        }
    }

    /**
     * Plan v4 §1.5 phase 2n - lock-free saveSnapshotPatched variant. Caller
     * provides a {@code (StateSnapshot) → StateSnapshot} mutator closure that
     * is re-run against a fresh-read snapshot on every CAS retry. No
     * {@code findByRunIdPublicForUpdate} held; concurrent peers can commit
     * mid-flight and we retry by re-fetching + re-mutating + re-CAS-ing.
     *
     * <p>This is the FUTURE shape of {@code saveSnapshotPatched} once the 14
     * hot-path callers are migrated. Today's callers continue to use the
     * legacy pessimistic-lock overload until per-callsite parity tests are
     * in place.
     *
     * @param runId target run public ID
     * @param mutator pure function applied to the freshly-read snapshot
     *                (must not have side effects beyond returning the new snapshot)
     * @param builder patch builder; receives (before, after) and emits patches
     * @param mutatorName metric tag (e.g. "markNodeCompleted")
     * @return true on CAS success, false on retry-exhaust (caller responsible
     *         for any pessimistic fallback chain; for new callers the recommended
     *         fallback is a single {@code saveSnapshotFullRewrite} via a
     *         pessimistic refound)
     */
    public boolean saveSnapshotPatchedCas(String runId,
                                          java.util.function.UnaryOperator<StateSnapshot> mutator,
                                          java.util.function.BiFunction<StateSnapshot, StateSnapshot,
                                                  JsonbPatchBuilder.Result> builder,
                                          String mutatorName) {
        if (!useJsonbPatch || patchExecutor == null || !casEnabled) {
            return false;  // legacy path responsibility
        }
        if (casKillSwitch != null && casKillSwitch.isCasDisabled()) {
            return false;
        }

        // Plan v4 §3 audit D-M1/E-M2 wire - if a fan-out caller opened a
        // coalescing session for this runId, route through the coalescer so
        // DELTA patches merge across concurrent same-path enqueues (N `+1` → 1 `+N`).
        // Without an active session, falls through to direct applyPatchesCas
        // (per-call SQL serialization via row lock - correct but no merge gain).
        // Future fan-out callers (SplitAwareNodeExecutor, batch resume) open
        // a session before invoking markNodeCompleted N times.
        if (runCoalescingService != null && runCoalescingService.isCoalescing(runId)) {
            return saveSnapshotPatchedCasViaCoalescer(runId, mutator, builder, mutatorName);
        }

        long t0 = System.nanoTime();
        for (int attempt = 0; attempt < CAS_RETRY_BACKOFF_MS.length; attempt++) {
            // Fresh-read seq + snapshot via stateless projection (no Hibernate L1 leak)
            var combined = runRepository.findSeqAndStateSnapshotByRunIdPublic(runId);
            if (combined.isEmpty()) {
                if (casKillSwitch != null) casKillSwitch.recordCasConflict();
                return false;  // row deleted
            }
            long expectedSeq = combined.get().getStateSnapshotSeq();
            String beforeJson = combined.get().getStateSnapshot();
            StateSnapshot before = parseSnapshotForRun(runId, beforeJson);
            // Re-apply the caller's mutator against the FRESH snapshot
            StateSnapshot after;
            try {
                after = mutator.apply(before);
            } catch (RuntimeException ex) {
                log.warn("[StateSnapshot] Mutator threw on attempt={} runId={} mutator={}, aborting CAS chain",
                        attempt, runId, mutatorName, ex);
                return false;
            }
            StateSnapshot versioned = after.withIncrementedSeq();
            JsonbPatchBuilder.Result result = builder.apply(before, versioned);
            if (result.isNoOp()) {
                return true;  // No-op = success; no patches to apply
            }
            if (result.isFallback()) {
                return false;  // Caller should fallback to full rewrite
            }
            List<JsonbPatch> patches = result.patches().orElseThrow();
            long newSeq = expectedSeq + 1L;
            try {
                int rows = patchExecutor.applyPatchesCas(runId, patches, expectedSeq, newSeq);
                if (rows == 1) {
                    if (casKillSwitch != null) casKillSwitch.recordCasSuccess();
                    txCache.put(runId, versioned);
                    if (snapshotJsonCache != null && newSeq == versioned.getSeq()) {
                        try {
                            final long finalSeq = newSeq;
                            final String finalJson = objectMapper.writeValueAsString(versioned);
                            com.apimarketplace.orchestrator.services.transaction.TransactionalHelper
                                    .runAfterCommitOrNow(() ->
                                            snapshotJsonCache.putIfNewer(runId, finalSeq, finalJson));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                            log.warn("[StateSnapshot] CAS post-commit pre-warm serialize failed for runId={}: {}",
                                    runId, ex.getMessage());
                        }
                    }
                    if (workflowMetrics != null) {
                        long payloadBytes = patchExecutor.estimatePayloadBytes(patches);
                        workflowMetrics.recordPatchPayloadBytes(mutatorName, payloadBytes);
                        workflowMetrics.recordStateSnapshotSave(mutatorName, "jsonb_set_cas_lock_free", payloadBytes,
                                System.nanoTime() - t0);
                    }
                    return true;
                }
                if (casKillSwitch != null) casKillSwitch.recordCasConflict();
            } catch (RuntimeException ex) {
                log.warn("[StateSnapshot] CAS lock-free attempt threw runId={} mutator={}: {}",
                        runId, mutatorName, ex.getMessage());
                return false;
            }
            if (attempt < CAS_RETRY_BACKOFF_MS.length - 1) {
                long baseMs = CAS_RETRY_BACKOFF_MS[attempt];
                long jitter = (long) (baseMs * CAS_RETRY_JITTER
                        * (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 2 - 1));
                java.util.concurrent.locks.LockSupport.parkNanos((baseMs + jitter) * 1_000_000L);
            }
        }
        if (workflowMetrics != null) {
            workflowMetrics.recordPatchFallback(mutatorName, "cas_lock_free_retry_exhausted");
        }
        log.info("[StateSnapshot] CAS lock-free retry budget exhausted runId={} mutator={}", runId, mutatorName);
        return false;
    }

    /**
     * Plan v4 §3 + audit bug #2 - coalescer-routed CAS variant. Instead of
     * enqueuing pre-built patches (which freeze a snapshot taken at enqueue time
     * and last-writer-wins clobber concurrent same-path ASSIGNs), we enqueue the
     * caller's {@code (mutator, builder)} as a RECOMPUTE CLOSURE. The coalescer
     * re-runs that closure against the freshly-flushed base at flush time, so
     * concurrent split items each adding their node to the SAME
     * {@code epochs.E.completedNodeIds} set COMPOSE (read-modify-write) instead
     * of clobbering. DELTA counters still merge additively.
     *
     * <p>Returns {@code true} on flush success, {@code false} on retry-exhaust,
     * POISON, no-op-with-nothing-to-do (still success), or a fallback request
     * (caller's pessimistic chain handles the missing-epoch materialization).
     */
    private boolean saveSnapshotPatchedCasViaCoalescer(
            String runId,
            java.util.function.UnaryOperator<StateSnapshot> mutator,
            java.util.function.BiFunction<StateSnapshot, StateSnapshot,
                    com.apimarketplace.orchestrator.services.state.patch.JsonbPatchBuilder.Result> builder,
            String mutatorName) {
        // Make sure the coalescer can parse the fresh base + invalidate caches
        // on flush (bug #1). Idempotent - first bind for the run's session wins.
        runCoalescingService.bindMutationBridge(runId, this);

        // The recompute closure: given a running base, run the Java mutator then
        // the patch builder, returning the patches + the advanced snapshot so the
        // coalescer can fold the next mutation on top. Re-run on every CAS retry.
        java.util.function.Function<StateSnapshot,
                com.apimarketplace.orchestrator.services.state.patch.RunCoalescingService.RecomputeOutput> recompute =
                base -> {
            StateSnapshot advanced = mutator.apply(base);
            var result = builder.apply(base, advanced.withIncrementedSeq());
            if (result.isFallback()) {
                return com.apimarketplace.orchestrator.services.state.patch
                        .RunCoalescingService.RecomputeOutput.fallback();
            }
            if (result.isNoOp()) {
                return com.apimarketplace.orchestrator.services.state.patch
                        .RunCoalescingService.RecomputeOutput.noOp(advanced);
            }
            return com.apimarketplace.orchestrator.services.state.patch
                    .RunCoalescingService.RecomputeOutput.of(result.patches().orElseThrow(), advanced);
        };

        java.util.concurrent.CompletableFuture<Void> future =
                runCoalescingService.enqueueMutation(runId, recompute, mutatorName);
        if (future == null) return false;

        // Drive the flush (there is no background timer flusher): drains this
        // mutation + any concurrently-enqueued peers' into one CAS flush. Then
        // block on the future via ManagedBlocker (FJP-safe).
        runCoalescingService.flushPendingMutations(runId);
        boolean completed = runCoalescingService.awaitFlush(future);
        if (!completed) return false;
        // Future already complete by here; surface exceptional completion as a
        // false → caller falls through to the pessimistic-lock fallback.
        try {
            future.getNow(null);
        } catch (java.util.concurrent.CompletionException ce) {
            return false;
        }
        return true;
    }

    // --- RunCoalescingService.MutationFlushBridge (audit bug #1 + #2 wire) ---

    /**
     * {@inheritDoc} Parse the coalescer's fresh base JSON using this service's
     * run-scoped parse path (txCache-aware).
     */
    @Override
    public StateSnapshot parseBase(String runId, String json) {
        return parseSnapshotForRun(runId, json);
    }

    /**
     * {@inheritDoc} Read-after-write (bug #1): invalidate the in-tx
     * {@link TxScopedSnapshotCache} (L0) so the enqueuing caller's subsequent
     * {@code getSnapshot} re-reads the just-committed merged state from DB
     * instead of returning the stale pre-flush parse it cached at enqueue time.
     *
     * <p>The L1 {@code StateSnapshotJsonCache} (Redis) is intentionally NOT
     * touched here: it is seq-gated - {@code getPayloadIfMatchesSeq} only serves
     * a cached entry when its embedded seq equals the live SQL oracle, so after
     * this flush bumps {@code state_snapshot_seq} to {@code newSeq} a stale
     * pre-flush entry can no longer be served and the read falls through to DB.
     * Self-healing; no explicit eviction needed.
     */
    @Override
    public void afterCoalescedFlush(String runId, long newSeq) {
        txCache.invalidate(runId);
    }

    /**
     * Plan v4 §1.7 - CAS attempt with retry budget over PRE-BUILT patches.
     * Returns true if CAS succeeded and the legacy {@code applyPatches} call
     * should be skipped; false on retry-exhaust → caller falls through to
     * pessimistic.
     *
     * <p>This is the OLDER pre-built-patch CAS helper. The lock-free,
     * re-mutate-on-retry path is {@link #saveSnapshotPatchedCas} (mutator +
     * builder overload), which the hot-path callers ({@code markNodeCompleted},
     * {@code markNodeFailed}, …) now invoke WITHOUT holding
     * {@code findByRunIdPublicForUpdate} - it re-reads the fresh snapshot and
     * re-runs the Java mutator on every CAS retry, and (when a fan-out caller
     * has opened a coalescing session) routes through
     * {@code saveSnapshotPatchedCasViaCoalescer} → the coalescer's
     * re-mutate-on-flush engine. {@code tryCasPath} remains for callers that
     * already hold a coherent {@code (patches, versioned)} pair.
     *
     * <p>Each retry re-reads the live seq via stateless projection
     * ({@code findStateSnapshotSeqByRunIdPublic}) - no Hibernate L1 leak
     * because the projection returns Long, not a managed entity. Backoff
     * {@code [1ms, 5ms, 15ms]} +/- 20% jitter via {@code LockSupport.parkNanos}.
     */
    private boolean tryCasPath(String runId, List<JsonbPatch> patches,
                                StateSnapshot versioned, String mutator,
                                long payloadBytes, long t0) {
        for (int attempt = 0; attempt < CAS_RETRY_BACKOFF_MS.length; attempt++) {
            java.util.Optional<Long> currentSeqOpt =
                    runRepository.findStateSnapshotSeqByRunIdPublic(runId);
            if (currentSeqOpt.isEmpty()) {
                // Row deleted between read and CAS - give pessimistic path a chance
                // to detect via findByRunIdPublicForUpdate (which also returns empty
                // and triggers the full-rewrite refound branch).
                // Audit S4: record as conflict so kill-switch sees sustained
                // delete-storm as a degraded condition.
                if (casKillSwitch != null) casKillSwitch.recordCasConflict();
                return false;
            }
            long expectedSeq = currentSeqOpt.get();
            // Newseq = expectedSeq + 1; ignore the in-memory versioned.getSeq() because
            // a peer may have bumped seq beyond what the caller-built `versioned` saw.
            long newSeq = expectedSeq + 1L;
            try {
                int rows = patchExecutor.applyPatchesCas(runId, patches, expectedSeq, newSeq);
                if (rows == 1) {
                    // Success - record metric, populate cache with the seq we wrote.
                    // Audit S1: if a peer raced ahead and our newSeq differs from
                    // versioned.getSeq() (only possible once the 14-callsite-flip
                    // drops the row lock - today guaranteed equal under the lock),
                    // skip the pre-warm. Better to leave L1 stale and let the next
                    // reader re-fetch than poison the cache with a seq/payload mismatch.
                    StateSnapshot withWrittenSeq = versioned;
                    txCache.put(runId, withWrittenSeq);
                    if (casKillSwitch != null) casKillSwitch.recordCasSuccess();
                    // Plan v4 §1.9 runAfterCommit pre-warm - populate L1 Redis
                    // cache after the outer tx commits so readers don't have to
                    // hit L2 DB on the next request. Best-effort; cache failure
                    // is non-fatal (StateSnapshotJsonCache fails OPEN).
                    boolean seqMatches = newSeq == versioned.getSeq();
                    if (snapshotJsonCache != null && seqMatches) {
                        try {
                            final long finalSeq = newSeq;
                            final String finalJson = objectMapper.writeValueAsString(withWrittenSeq);
                            com.apimarketplace.orchestrator.services.transaction.TransactionalHelper
                                    .runAfterCommitOrNow(() ->
                                            snapshotJsonCache.putIfNewer(runId, finalSeq, finalJson));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                            log.warn("[StateSnapshot] CAS post-commit pre-warm serialize failed for runId={}: {}",
                                    runId, ex.getMessage());
                        }
                    } else if (!seqMatches && log.isDebugEnabled()) {
                        log.debug("[StateSnapshot] CAS post-commit pre-warm skipped runId={} - "
                                        + "newSeq={} != versioned.seq={} (peer race after lock-drop refactor); "
                                        + "L1 will repopulate on next reader",
                                runId, newSeq, versioned.getSeq());
                    }
                    if (workflowMetrics != null) {
                        workflowMetrics.recordPatchPayloadBytes(mutator, payloadBytes);
                        workflowMetrics.recordStateSnapshotSave(mutator, "jsonb_set_cas", payloadBytes,
                                System.nanoTime() - t0);
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("[StateSnapshot] CAS success runId={} mutator={} attempt={}",
                                runId, mutator, attempt);
                    }
                    return true;
                }
                // rows == 0 → CAS conflict (peer commit). Retry.
                if (casKillSwitch != null) casKillSwitch.recordCasConflict();
                if (log.isDebugEnabled()) {
                    log.debug("[StateSnapshot] CAS conflict runId={} mutator={} attempt={} expectedSeq={}",
                            runId, mutator, attempt, expectedSeq);
                }
            } catch (RuntimeException ex) {
                // Trigger violation / DB-down / deadlock - don't retry. Fall through
                // to pessimistic which will either succeed or surface the same error.
                log.warn("[StateSnapshot] CAS attempt threw runId={} mutator={}: {} - falling through to pessimistic",
                        runId, mutator, ex.getMessage());
                return false;
            }
            // Jittered backoff before next retry (skip on last attempt).
            if (attempt < CAS_RETRY_BACKOFF_MS.length - 1) {
                long baseMs = CAS_RETRY_BACKOFF_MS[attempt];
                long jitter = (long) (baseMs * CAS_RETRY_JITTER
                        * (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 2 - 1));
                java.util.concurrent.locks.LockSupport.parkNanos((baseMs + jitter) * 1_000_000L);
            }
        }
        // Retry budget exhausted - pessimistic path takes over.
        if (workflowMetrics != null) {
            workflowMetrics.recordPatchFallback(mutator, "cas_retry_exhausted");
        }
        log.info("[StateSnapshot] CAS retry budget exhausted runId={} mutator={} - pessimistic fallback",
                runId, mutator);
        return false;
    }
}

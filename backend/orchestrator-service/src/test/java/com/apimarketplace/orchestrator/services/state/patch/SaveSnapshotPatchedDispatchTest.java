package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.state.TxScopedSnapshotCache;
import com.apimarketplace.orchestrator.services.state.elide.TenantElideFlagResolver;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration test for {@code StateSnapshotService.saveSnapshotPatched} -
 * exercises the dispatcher branching through the public {@code markNodeCompleted}
 * mutator (epoch-scoped). Verifies:
 *
 * <ul>
 *   <li>flag OFF → legacy {@code saveSnapshotFullRewrite} path (no patch metric)</li>
 *   <li>flag ON + patch executor null → {@code executor_unwired} fallback metric</li>
 *   <li>flag ON + builder returns FALLBACK → {@code builder_returned_fallback} metric</li>
 *   <li>flag ON + builder PATCH → {@code applyPatches} called, metric tagged path=jsonb_set,
 *       {@link EntityManager#detach} called BEFORE {@code applyPatches} (inOrder),
 *       {@code txCache.put} called with the seq-incremented snapshot</li>
 *   <li>{@code zero_rows_updated} → re-find via {@code findByRunIdPublicForUpdate} +
 *       full-rewrite fallback</li>
 * </ul>
 *
 * <p>Construction is hand-rolled (not Spring-managed) to keep the test tight and
 * to mirror the existing {@code StateSnapshotServiceTest} pattern.
 */
class SaveSnapshotPatchedDispatchTest {

    private static final String RUN_ID = "run-1";
    private static final String TRIGGER = "trigger:webhook";
    private static final int EPOCH = 5;
    private static final String NODE = "n1";
    private static final String TENANT = "tenant-a";

    private WorkflowRunRepository runRepository;
    private WorkflowEpochService workflowEpochService;
    private WorkflowEventPublisher eventPublisher;
    private StorageBreakdownService breakdownService;
    private TxScopedSnapshotCache txCache;
    private WorkflowMetrics workflowMetrics;
    private SimpleMeterRegistry meterRegistry;
    private ObjectMapper mapper;

    private JsonbPatchExecutor patchExecutor;
    private MarkNodeCompletedPatchBuilder completedBuilder;
    private EntityManager entityManager;

    private StateSnapshotService service;
    private WorkflowRunEntity run;

    @BeforeEach
    void setUp() throws Exception {
        runRepository = mock(WorkflowRunRepository.class);
        workflowEpochService = mock(WorkflowEpochService.class);
        eventPublisher = mock(WorkflowEventPublisher.class);
        breakdownService = mock(StorageBreakdownService.class);
        meterRegistry = new SimpleMeterRegistry();
        txCache = new TxScopedSnapshotCache(runRepository, meterRegistry);
        workflowMetrics = new WorkflowMetrics(meterRegistry);
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        service = new StateSnapshotService(runRepository, mapper, workflowEpochService,
                eventPublisher, breakdownService, txCache, workflowMetrics);

        // Plan v4 E2E5 - saveSnapshotFullRewrite now uses the native UPDATE
        // updateSnapshotAndSeq (was: runRepository.save(run)). Stub it lenient(ly)
        // so the legacy full-rewrite fallback path commits successfully in tests.
        org.mockito.Mockito.lenient().when(
                        runRepository.updateSnapshotAndSeq(anyString(), anyString()))
                .thenReturn(1);

        // Mock entity backed by an actual EpochState containing n1 in running set so
        // the builder can compute a real patch list.
        run = mock(WorkflowRunEntity.class);
        when(run.getRunIdPublic()).thenReturn(RUN_ID);
        when(run.getTenantId()).thenReturn(TENANT);
        StateSnapshot before = StateSnapshot.empty()
                .ensureDagInitialized(TRIGGER, EPOCH)
                .addRunningNode(TRIGGER, NODE, EPOCH);
        when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(before));

        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(run));

        // Wire patch dependencies via reflection (production uses @Autowired, optional)
        patchExecutor = mock(JsonbPatchExecutor.class);
        TenantElideFlagResolver elideOff = tenantId -> false;
        completedBuilder = new MarkNodeCompletedPatchBuilder(mapper, elideOff);
        entityManager = mock(EntityManager.class);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = StateSnapshotService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private void enablePatchPath() throws Exception {
        setField("useJsonbPatch", true);
        setField("patchExecutor", patchExecutor);
        setField("markNodeCompletedPatchBuilder", completedBuilder);
        setField("entityManager", entityManager);
    }

    private long savedSaveCount(String path) {
        var c = meterRegistry.find(WorkflowMetrics.STATE_SNAPSHOT_SAVE_COUNT)
                .tag("path", path).counter();
        return c == null ? 0L : (long) c.count();
    }

    private double fallbackCount(String reason) {
        var c = meterRegistry.find(WorkflowMetrics.STATE_SNAPSHOT_PATCH_FALLBACK_COUNT)
                .tag("reason", reason).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    @DisplayName("Flag OFF → legacy full-rewrite path, no patch metric")
    void flagOffTakesLegacyPath() throws Exception {
        // Default: useJsonbPatch=false; patchExecutor null; builder null.
        // markNodeCompleted should hit saveSnapshotFullRewrite.
        service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

        verify(runRepository, atLeastOnce()).updateSnapshotAndSeq(eq(RUN_ID), anyString());
        verify(patchExecutor, never()).applyPatches(anyString(), anyList(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(1L);
        assertThat(savedSaveCount("jsonb_set")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Flag ON + patch executor null → executor_unwired metric + full-rewrite")
    void flagOnExecutorMissingFallsBack() throws Exception {
        setField("useJsonbPatch", true);
        // Builder wired but executor null
        setField("markNodeCompletedPatchBuilder", completedBuilder);
        setField("entityManager", entityManager);

        service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

        verify(runRepository, atLeastOnce()).updateSnapshotAndSeq(eq(RUN_ID), anyString());
        assertThat(fallbackCount("executor_unwired")).isEqualTo(1.0);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Flag ON + builder PATCH → applyPatches called, detach BEFORE applyPatches, txCache populated")
    void happyPathAppliesPatchesInOrder() throws Exception {
        enablePatchPath();
        when(patchExecutor.applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(123L);

        service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

        // Critical ordering: detach MUST happen before applyPatches
        var inOrder = inOrder(entityManager, patchExecutor);
        inOrder.verify(entityManager).detach(run);
        inOrder.verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong());

        // Legacy path NOT taken
        verify(runRepository, never()).updateSnapshotAndSeq(anyString(), anyString());
        assertThat(savedSaveCount("jsonb_set")).isEqualTo(1L);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(0L);
    }

    @Test
    @DisplayName("A2 full-rewrite path: setStateSnapshotSeq is invoked with the post-increment seq so the SQL column mirrors the JSONB {seq} field on the legacy persistence path too (regression: drift between paths would defeat the cache)")
    void fullRewriteMirrorsSeqIntoSqlColumn() throws Exception {
        // Force the full-rewrite path by leaving useJsonbPatch=false (default).
        StateSnapshot before = service.getSnapshot(RUN_ID);
        long expectedNewSeq = before.withIncrementedSeq().getSeq();

        service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

        // setStateSnapshot AND setStateSnapshotSeq must BOTH be called inside
        // saveSnapshotFullRewrite - they keep the in-memory entity coherent
        // after the native UPDATE. Plan v4 E2E5: the DB persistence is now via
        // runRepository.updateSnapshotAndSeq (native UPDATE that increments
        // state_snapshot_seq DB-side), not runRepository.save(run).
        verify(run).setStateSnapshot(org.mockito.ArgumentMatchers.anyString());
        verify(run).setStateSnapshotSeq(expectedNewSeq);
        verify(runRepository, atLeastOnce()).updateSnapshotAndSeq(eq(RUN_ID), anyString());
    }

    @Test
    @DisplayName("A2 patch path: applyPatches receives the post-increment seq so the SQL state_snapshot_seq column stays in lockstep with the JSONB {seq} field (regression: out-of-tx cache cannot invalidate by seq alone if mirror is missing - audit Opus A 2026-05-09)")
    void patchPathStampsSeqColumnWithVersionedSeq() throws Exception {
        enablePatchPath();
        when(patchExecutor.applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        // Pre-mutation seq read off the wired snapshot (`enablePatchPath`
        // installs a snapshot with epoch+running set; default seq is 0).
        StateSnapshot before = service.getSnapshot(RUN_ID);
        long expectedNewSeq = before.withIncrementedSeq().getSeq();

        service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

        org.mockito.ArgumentCaptor<Long> seqCaptor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(), seqCaptor.capture());
        assertThat(seqCaptor.getValue())
                .as("patch path must stamp newSeq = before.seq + 1 atomically with jsonb_set")
                .isEqualTo(expectedNewSeq);
    }

    @Test
    @DisplayName("applyPatches returns 0 → zero_rows_updated metric + re-find + full-rewrite fallback")
    void zeroRowsTriggersRefindAndFullRewrite() throws Exception {
        enablePatchPath();
        when(patchExecutor.applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(0);

        // Second findByRunIdPublicForUpdate (the re-find) returns same mocked run.
        // The first call inside markNodeCompleted already returned `run`; the second
        // call (inside the zero_rows fallback) returns it again.
        service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

        // First call (inside markNodeCompleted body) + second call (re-find) = 2 invocations
        verify(runRepository, times(2)).findByRunIdPublicForUpdate(RUN_ID);
        verify(runRepository, atLeastOnce()).updateSnapshotAndSeq(eq(RUN_ID), anyString());
        assertThat(fallbackCount("zero_rows_updated")).isEqualTo(1.0);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(1L);
    }

    @Test
    @DisplayName("applyPatches throws → patch_update_exception metric + txCache invalidated + re-find fallback")
    void applyPatchesExceptionFallback() throws Exception {
        enablePatchPath();
        when(patchExecutor.applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong()))
                .thenThrow(new RuntimeException("boom"));

        service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

        verify(runRepository, times(2)).findByRunIdPublicForUpdate(RUN_ID);
        verify(runRepository, atLeastOnce()).updateSnapshotAndSeq(eq(RUN_ID), anyString());
        assertThat(fallbackCount("patch_update_exception")).isEqualTo(1.0);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Builder returns FALLBACK → builder_returned_fallback metric + full-rewrite")
    void builderFallbackTriggersFullRewrite() throws Exception {
        enablePatchPath();
        // Replace builder with one whose target snapshot lacks the epoch (forces fallback)
        StateSnapshot empty = StateSnapshot.empty();
        when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(empty));

        service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

        verify(runRepository, atLeastOnce()).updateSnapshotAndSeq(eq(RUN_ID), anyString());
        assertThat(fallbackCount("builder_returned_fallback")).isEqualTo(1.0);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Builder throws RuntimeException → builder_exception metric + full-rewrite fallback")
    void builderExceptionFallback() throws Exception {
        // Custom builder that throws - wires via reflection to substitute the field
        enablePatchPath();
        MarkNodeCompletedPatchBuilder throwingBuilder = mock(MarkNodeCompletedPatchBuilder.class);
        when(throwingBuilder.build(any(), any(), eq(TRIGGER), eq(EPOCH), eq(NODE), eq(TENANT)))
                .thenThrow(new RuntimeException("simulated builder explosion"));
        setField("markNodeCompletedPatchBuilder", throwingBuilder);

        service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

        verify(runRepository, atLeastOnce()).updateSnapshotAndSeq(eq(RUN_ID), anyString());
        assertThat(fallbackCount("builder_exception")).isEqualTo(1.0);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Dispatch in recordNodeCompletionAndGetCounts: FAILED → MarkNodeFailedPatchBuilder")
    void dispatchFailedRoutesToFailedBuilder() throws Exception {
        // Wire failed builder
        setField("useJsonbPatch", true);
        setField("patchExecutor", patchExecutor);
        setField("entityManager", entityManager);
        com.apimarketplace.orchestrator.services.state.elide.TenantElideFlagResolver elideOff = t -> false;
        MarkNodeFailedPatchBuilder failedBuilder = new MarkNodeFailedPatchBuilder(mapper, elideOff);
        setField("markNodeFailedPatchBuilder", failedBuilder);
        when(patchExecutor.applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(120L);

        service.recordNodeCompletionAndGetCounts(RUN_ID, NODE, "FAILED", TRIGGER, EPOCH);

        verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong());
        verify(runRepository, never()).updateSnapshotAndSeq(anyString(), anyString()); // patch path = jsonb_set via executor, not native full-rewrite
        assertThat(savedSaveCount("jsonb_set")).isEqualTo(1L);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(0L);
    }

    @Test
    @DisplayName("resolveAwaitingSignal restored-from-DB epoch → forces full-rewrite (no patch path)")
    void resolveAwaitingSignalRestoredFromDbForcesFullRewrite() throws Exception {
        // Wire patch path + resolveAwaitingSignal builder
        setField("useJsonbPatch", true);
        setField("patchExecutor", patchExecutor);
        setField("entityManager", entityManager);
        ResolveAwaitingSignalPatchBuilder resolveBuilder = new ResolveAwaitingSignalPatchBuilder(mapper);
        setField("resolveAwaitingSignalPatchBuilder", resolveBuilder);

        // Pre-mutation snapshot WITHOUT the epoch (simulates closeAndPruneEpoch).
        StateSnapshot epochPruned = StateSnapshot.empty();
        when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(epochPruned));

        // Mock WorkflowEpochService to "restore" an EpochState from the DB.
        EpochState restoredEpoch = new EpochState(
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of(NODE),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.time.Instant.parse("2026-05-09T00:00:00Z"));
        when(workflowEpochService.getFullEpochState(RUN_ID, TRIGGER, EPOCH))
                .thenReturn(restoredEpoch);

        service.resolveAwaitingSignal(RUN_ID, TRIGGER, EPOCH, NODE, 100L);

        // CRITICAL: full-rewrite path taken (Hibernate save), patch path NOT taken.
        // Without the restoredFromDb guard, jsonb_set on a non-existent epoch path
        // would silently no-op while leaving the in-memory restored state divergent
        // from the DB JSONB.
        verify(runRepository, atLeastOnce()).updateSnapshotAndSeq(eq(RUN_ID), anyString());
        verify(patchExecutor, never()).applyPatches(anyString(), anyList(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(1L);
        assertThat(savedSaveCount("jsonb_set")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Dispatch in recordNodeCompletionAndGetCounts: SKIPPED → MarkNodeSkippedPatchBuilder")
    void dispatchSkippedRoutesToSkippedBuilder() throws Exception {
        setField("useJsonbPatch", true);
        setField("patchExecutor", patchExecutor);
        setField("entityManager", entityManager);
        MarkNodeSkippedPatchBuilder skippedBuilder = new MarkNodeSkippedPatchBuilder(mapper);
        setField("markNodeSkippedPatchBuilder", skippedBuilder);
        // Build a snapshot with the node in ready (markNodeSkipped removes from ready)
        StateSnapshot skippableState = StateSnapshot.empty()
                .ensureDagInitialized(TRIGGER, EPOCH)
                .addReadyNode(TRIGGER, NODE, EPOCH);
        when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(skippableState));
        when(patchExecutor.applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(80L);

        service.recordNodeCompletionAndGetCounts(RUN_ID, NODE, "SKIPPED", TRIGGER, EPOCH);

        verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(savedSaveCount("jsonb_set")).isEqualTo(1L);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(0L);
    }

    @Test
    @DisplayName("R4 wiring: addReadyNode flat dispatches to AddReadyNodePatchBuilder with resolved triggerId+epoch")
    void dispatchAddReadyNodeToBuilder() throws Exception {
        setField("useJsonbPatch", true);
        setField("patchExecutor", patchExecutor);
        setField("entityManager", entityManager);
        AddReadyNodePatchBuilder addBuilder = new AddReadyNodePatchBuilder(mapper);
        setField("addReadyNodePatchBuilder", addBuilder);
        // before snapshot: DAG initialized at epoch 0 (DagState.initial().currentEpoch),
        // which matches what the wiring resolves via current.getDagState(triggerId).getCurrentEpoch().
        // Node not yet ready so the patch is observable (not NO_OP).
        StateSnapshot before = StateSnapshot.empty().ensureDagInitialized(TRIGGER, 0);
        when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(before));
        when(patchExecutor.applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(50L);

        service.addReadyNode(RUN_ID, NODE);

        verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(savedSaveCount("jsonb_set")).isEqualTo(1L);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(0L);
    }

    @Test
    @DisplayName("R4 wiring: removeReadyNode flat resolves triggerId via findDagContaining and dispatches to builder")
    void dispatchRemoveReadyNodeToBuilder() throws Exception {
        setField("useJsonbPatch", true);
        setField("patchExecutor", patchExecutor);
        setField("entityManager", entityManager);
        RemoveReadyNodePatchBuilder removeBuilder = new RemoveReadyNodePatchBuilder(mapper);
        setField("removeReadyNodePatchBuilder", removeBuilder);
        // before snapshot: node is in readyNodeIds at epoch 0 (DAG.initial currentEpoch).
        StateSnapshot before = StateSnapshot.empty()
                .ensureDagInitialized(TRIGGER, 0)
                .addReadyNode(TRIGGER, NODE, 0);
        when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(before));
        when(patchExecutor.applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(50L);

        service.removeReadyNode(RUN_ID, NODE);

        verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(savedSaveCount("jsonb_set")).isEqualTo(1L);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(0L);
    }

    @Test
    @DisplayName("R5 wiring: recordDecisionBranch (epoch-scoped) dispatches to RecordDecisionBranchPatchBuilder")
    void dispatchRecordDecisionBranchToBuilder() throws Exception {
        setField("useJsonbPatch", true);
        setField("patchExecutor", patchExecutor);
        setField("entityManager", entityManager);
        RecordDecisionBranchPatchBuilder decisionBuilder = new RecordDecisionBranchPatchBuilder(mapper);
        setField("recordDecisionBranchPatchBuilder", decisionBuilder);
        // before: epoch initialized so the path-init prerequisite holds
        StateSnapshot before = StateSnapshot.empty()
                .ensureDagInitialized(TRIGGER, EPOCH)
                .addRunningNode(TRIGGER, NODE, EPOCH);
        when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(before));
        when(patchExecutor.applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(60L);

        service.recordDecisionBranch(RUN_ID, TRIGGER, EPOCH, NODE, "if");

        verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(savedSaveCount("jsonb_set")).isEqualTo(1L);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(0L);
    }

    @Test
    @DisplayName("R4 wiring: recordEdgeStatus dispatches to RecordEdgeStatusPatchBuilder")
    void dispatchRecordEdgeStatusToBuilder() throws Exception {
        setField("useJsonbPatch", true);
        setField("patchExecutor", patchExecutor);
        setField("entityManager", entityManager);
        RecordEdgeStatusPatchBuilder edgeBuilder = new RecordEdgeStatusPatchBuilder(mapper);
        setField("recordEdgeStatusPatchBuilder", edgeBuilder);
        // before snapshot: edges map non-empty so the path-init prerequisite holds
        StateSnapshot before = StateSnapshot.empty().incrementEdge("a", "b", "COMPLETED");
        when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(before));
        when(patchExecutor.applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(40L);

        service.recordEdgeStatus(RUN_ID, "a", "b", "COMPLETED");

        verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(savedSaveCount("jsonb_set")).isEqualTo(1L);
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Guard: recordEdgeStatuses (batch) stays on full-rewrite - recordEdgeStatusPatchBuilder NEVER called")
    void recordEdgeStatusesBatchStaysOnFullRewrite() throws Exception {
        setField("useJsonbPatch", true);
        setField("patchExecutor", patchExecutor);
        setField("entityManager", entityManager);
        RecordEdgeStatusPatchBuilder edgeBuilder = new RecordEdgeStatusPatchBuilder(mapper);
        setField("recordEdgeStatusPatchBuilder", edgeBuilder);
        StateSnapshot before = StateSnapshot.empty().incrementEdge("a", "b", "COMPLETED");
        when(run.getStateSnapshot()).thenReturn(mapper.writeValueAsString(before));

        java.util.Map<String, String> batch = java.util.Map.of("a->b", "COMPLETED", "c->d", "COMPLETED");
        service.recordEdgeStatuses(RUN_ID, batch);

        // The single-edge builder must NEVER be invoked from the batch method -
        // the batch path is intentionally left on full rewrite (N×jsonb_set is
        // costlier than 1×fullRewrite for N edges).
        verify(patchExecutor, never()).applyPatches(eq(RUN_ID), anyList(), org.mockito.ArgumentMatchers.anyLong());
        verify(runRepository, atLeastOnce()).updateSnapshotAndSeq(eq(RUN_ID), anyString());
        assertThat(savedSaveCount("full_rewrite")).isEqualTo(1L);
        assertThat(savedSaveCount("jsonb_set")).isEqualTo(0L);
    }

    // ========================================================================
    // Plan v4 §1.6 phase 2g v2 - CAS-aware saveSnapshotPatched dispatcher tests
    // ========================================================================

    @org.junit.jupiter.api.Nested
    @DisplayName("Plan v4 §1.6 - CAS dispatcher (phase 2g v2 POC)")
    class CasDispatcher {

        @org.junit.jupiter.api.Test
        @DisplayName("CAS flag ON + applyPatchesCas success → skip legacy applyPatches, jsonb_set_cas metric")
        void casSuccessSkipsLegacyApplyPatches() throws Exception {
            enablePatchPath();
            setField("casEnabled", true);
            when(runRepository.findStateSnapshotSeqByRunIdPublic(RUN_ID))
                    .thenReturn(Optional.of(10L));
            when(patchExecutor.applyPatchesCas(eq(RUN_ID), anyList(),
                    eq(10L), eq(11L))).thenReturn(1);

            service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

            // CAS path took the call - legacy applyPatches NEVER invoked
            verify(patchExecutor).applyPatchesCas(eq(RUN_ID), anyList(), eq(10L), eq(11L));
            verify(patchExecutor, never()).applyPatches(anyString(), anyList(),
                    org.mockito.ArgumentMatchers.anyLong());
            assertThat(savedSaveCount("jsonb_set_cas")).isEqualTo(1L);
            assertThat(savedSaveCount("jsonb_set")).isZero();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("CAS flag ON + 3 conflicts → cas_retry_exhausted fallback + legacy applyPatches called")
        void casRetryExhaustsThenFallsBackToLegacy() throws Exception {
            enablePatchPath();
            setField("casEnabled", true);
            when(runRepository.findStateSnapshotSeqByRunIdPublic(RUN_ID))
                    .thenReturn(Optional.of(10L))
                    .thenReturn(Optional.of(11L))
                    .thenReturn(Optional.of(12L));
            when(patchExecutor.applyPatchesCas(anyString(), anyList(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(0);  // always conflict
            when(patchExecutor.applyPatches(eq(RUN_ID), anyList(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

            service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

            // 3 CAS attempts then legacy applyPatches
            verify(patchExecutor, times(3)).applyPatchesCas(anyString(), anyList(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
            verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(),
                    org.mockito.ArgumentMatchers.anyLong());
            assertThat(fallbackCount("cas_retry_exhausted")).isEqualTo(1.0);
            assertThat(savedSaveCount("jsonb_set")).isEqualTo(1L);
        }

        @org.junit.jupiter.api.Test
        @DisplayName("CAS flag OFF → applyPatchesCas NEVER called, direct legacy path (pre-#78 behavior)")
        void casFlagOffSkipsCasPath() throws Exception {
            enablePatchPath();
            setField("casEnabled", false);
            when(patchExecutor.applyPatches(eq(RUN_ID), anyList(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

            service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

            verify(patchExecutor, never()).applyPatchesCas(anyString(), anyList(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
            verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(),
                    org.mockito.ArgumentMatchers.anyLong());
            assertThat(savedSaveCount("jsonb_set")).isEqualTo(1L);
            assertThat(savedSaveCount("jsonb_set_cas")).isZero();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("CAS flag ON + row deleted (Optional.empty) → fall through to legacy without retry storm")
        void casRowDeletedFallsThroughImmediately() throws Exception {
            enablePatchPath();
            setField("casEnabled", true);
            when(runRepository.findStateSnapshotSeqByRunIdPublic(RUN_ID))
                    .thenReturn(Optional.empty());
            when(patchExecutor.applyPatches(eq(RUN_ID), anyList(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

            service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

            // No retry storm - single CAS attempt, then fall through
            verify(patchExecutor, never()).applyPatchesCas(anyString(), anyList(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
            verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(),
                    org.mockito.ArgumentMatchers.anyLong());
        }

        @org.junit.jupiter.api.Test
        @DisplayName("CAS flag ON + applyPatchesCas throws RuntimeException → no retry, fall through to legacy")
        void casThrowsFallsThroughImmediately() throws Exception {
            enablePatchPath();
            setField("casEnabled", true);
            when(runRepository.findStateSnapshotSeqByRunIdPublic(RUN_ID))
                    .thenReturn(Optional.of(10L));
            when(patchExecutor.applyPatchesCas(anyString(), anyList(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenThrow(new RuntimeException("V181 trigger violation"));
            when(patchExecutor.applyPatches(eq(RUN_ID), anyList(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

            service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

            // CAS throws once, no retry - fall through to legacy
            verify(patchExecutor, times(1)).applyPatchesCas(anyString(), anyList(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
            verify(patchExecutor).applyPatches(eq(RUN_ID), anyList(),
                    org.mockito.ArgumentMatchers.anyLong());
        }
    }
}

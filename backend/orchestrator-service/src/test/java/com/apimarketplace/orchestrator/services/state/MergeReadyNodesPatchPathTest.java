package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.repository.StateSnapshotSeqAndJsonProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.patch.AddReadyNodePatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatch;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatchExecutor;
import com.apimarketplace.orchestrator.services.state.patch.PatchPaths;
import com.apimarketplace.orchestrator.services.state.patch.RemoveReadyNodePatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.ReplaceReadyNodeSetPatchBuilder;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FIX #3 - concurrency optimization for the DAG-scoped
 * {@link StateSnapshotService#mergeReadyNodesAfterExecution(String, String, int, String, Set)}.
 *
 * <p>The epoch-scoped merge (the only branch the live SBS engine takes) must
 * persist the ready-set delta via a single {@code jsonb_set} patch through the
 * lock-free CAS path - NOT a full ~30KB snapshot rewrite. These tests pin:
 *
 * <ol>
 *   <li>add + remove correctly reflected in the emitted patch (and full rewrite
 *       is never called),</li>
 *   <li>a safe fall-through to the pessimistic full-rewrite path when the patch
 *       cannot be built (epoch missing / CAS retry exhausted),</li>
 *   <li>the regression guard {@code mergeReadyNodesUsesPatchNotFullRewrite}.</li>
 * </ol>
 *
 * <p>Wiring mirrors {@link SaveSnapshotPatchedCasTest}: real CAS path against a
 * mock {@link JsonbPatchExecutor}, builders reflection-injected (the
 * {@code @Autowired(required=false)} fields are null under plain construction).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FIX #3 - mergeReadyNodesAfterExecution patch path (CAS, not full rewrite)")
class MergeReadyNodesPatchPathTest {

    private static final String RUN = "run-1";
    private static final String TRG = "trigger:webhook";
    private static final int EPOCH = 1;

    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowEpochService workflowEpochService;
    @Mock WorkflowEventPublisher eventPublisher;
    @Mock StorageBreakdownService breakdownService;
    @Mock JsonbPatchExecutor patchExecutor;
    @Mock EntityManager entityManager;

    private ObjectMapper mapper;
    private StateSnapshotService service;

    @BeforeEach
    void setUp() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        WorkflowMetrics workflowMetrics = new WorkflowMetrics(meterRegistry);
        TxScopedSnapshotCache txCache = new TxScopedSnapshotCache(runRepository, meterRegistry);
        service = new StateSnapshotService(runRepository, mapper, workflowEpochService,
                eventPublisher, breakdownService, txCache, workflowMetrics);
        setField("useJsonbPatch", true);
        setField("casEnabled", true);
        setField("patchExecutor", patchExecutor);
        setField("entityManager", entityManager);
        // All three builders must be wired for the epoch-scoped fast path to engage
        // (a null replaceReadyNodeSetPatchBuilder falls through to full rewrite).
        setField("addReadyNodePatchBuilder", new AddReadyNodePatchBuilder(mapper));
        setField("removeReadyNodePatchBuilder", new RemoveReadyNodePatchBuilder(mapper));
        setField("replaceReadyNodeSetPatchBuilder", new ReplaceReadyNodeSetPatchBuilder(mapper));
        // Lenient: the full-rewrite fallback path uses this; happy-path tests never hit it.
        lenient().when(runRepository.updateSnapshotAndSeq(anyString(), anyString())).thenReturn(1);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = StateSnapshotService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    /** Build a snapshot with {@code readyNodeIds} pre-populated at (TRG, EPOCH). */
    private StateSnapshot snapshotWithReady(long seq, Set<String> ready) {
        StateSnapshot s = StateSnapshot.empty();
        for (String n : ready) {
            s = s.addReadyNode(TRG, n, EPOCH);
        }
        // Bring seq up to the requested value (each withIncrementedSeq adds 1 from 0).
        while (s.getSeq() < seq) {
            s = s.withIncrementedSeq();
        }
        return s;
    }

    private StateSnapshotSeqAndJsonProjection projection(long seq, String json) {
        return new StateSnapshotSeqAndJsonProjection() {
            @Override public Long getStateSnapshotSeq() { return seq; }
            @Override public String getStateSnapshot() { return json; }
        };
    }

    private void stubCasRead(StateSnapshot before) throws Exception {
        String json = mapper.writeValueAsString(before);
        when(runRepository.findSeqAndStateSnapshotByRunIdPublic(RUN))
                .thenReturn(Optional.of(projection(before.getSeq(), json)));
    }

    @SuppressWarnings("unchecked")
    private List<JsonbPatch> captureCasPatches() {
        ArgumentCaptor<List<JsonbPatch>> captor = ArgumentCaptor.forClass(List.class);
        verify(patchExecutor).applyPatchesCas(eq(RUN), captor.capture(), anyLong(), anyLong());
        return captor.getValue();
    }

    private JsonbPatch readyNodeIdsPatch(List<JsonbPatch> patches) {
        String[] expectedPath = PatchPaths.epochSet(TRG, EPOCH, PatchPaths.READY_NODE_IDS);
        return patches.stream()
                .filter(p -> java.util.Arrays.equals(p.path(), expectedPath))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no readyNodeIds patch in " + patches));
    }

    // ------------------------------------------------------------------------
    // (a) Ready-set correctly updated via patch (add + remove)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("(a) Remove executed node + add successors → single readyNodeIds patch, no full rewrite")
    void readySetUpdatedViaPatchAddAndRemove() throws Exception {
        // Arrange: trigger is ready; executing it removes trigger and adds step1.
        StateSnapshot before = snapshotWithReady(10L, Set.of(TRG));
        stubCasRead(before);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(10L), eq(11L))).thenReturn(1);
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(40L);

        // Act
        service.mergeReadyNodesAfterExecution(RUN, TRG, EPOCH, TRG, Set.of("mcp:step1"));

        // Assert: CAS patch applied once, NEVER the full rewrite.
        List<JsonbPatch> patches = captureCasPatches();
        Set<String> patchedReady = mapper.readValue(readyNodeIdsPatch(patches).jsonValue(), Set.class);
        assertThat(patchedReady)
                .as("executed node removed, successor added")
                .containsExactly("mcp:step1");
        verify(runRepository, never()).updateSnapshotAndSeq(anyString(), anyString());
        verify(runRepository, never()).findByRunIdPublicForUpdate(anyString());
    }

    @Test
    @DisplayName("(a) Multiple successors fan out into one set-replacement patch")
    void readySetUpdatedWithMultipleSuccessors() throws Exception {
        StateSnapshot before = snapshotWithReady(5L, Set.of(TRG));
        stubCasRead(before);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L))).thenReturn(1);
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(60L);

        service.mergeReadyNodesAfterExecution(RUN, TRG, EPOCH, TRG, Set.of("mcp:a", "mcp:b"));

        List<JsonbPatch> patches = captureCasPatches();
        Set<String> patchedReady = mapper.readValue(readyNodeIdsPatch(patches).jsonValue(), Set.class);
        assertThat(patchedReady).containsExactlyInAnyOrder("mcp:a", "mcp:b");
        verify(runRepository, never()).updateSnapshotAndSeq(anyString(), anyString());
    }

    @Test
    @DisplayName("(a) Last node (no successors) leaves an empty ready-set patch")
    void readySetEmptiedOnLastNode() throws Exception {
        StateSnapshot before = snapshotWithReady(7L, Set.of("mcp:last"));
        stubCasRead(before);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(7L), eq(8L))).thenReturn(1);
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(20L);

        service.mergeReadyNodesAfterExecution(RUN, TRG, EPOCH, "mcp:last", Set.of());

        List<JsonbPatch> patches = captureCasPatches();
        Set<String> patchedReady = mapper.readValue(readyNodeIdsPatch(patches).jsonValue(), Set.class);
        assertThat(patchedReady).isEmpty();
        verify(runRepository, never()).updateSnapshotAndSeq(anyString(), anyString());
    }

    @Test
    @DisplayName("(a) Other DAG ready nodes are NOT touched by the patch (only this DAG/epoch's set)")
    void otherDagReadyNodesUntouched() throws Exception {
        // DAG A (TRG) ready={trigger}; DAG B has its own ready node at epoch 1.
        StateSnapshot before = snapshotWithReady(3L, Set.of(TRG))
                .addReadyNode("trigger:other", "mcp:other_ready", EPOCH);
        stubCasRead(before);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(3L), eq(4L))).thenReturn(1);
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(40L);

        service.mergeReadyNodesAfterExecution(RUN, TRG, EPOCH, TRG, Set.of("mcp:step1"));

        // The single patch targets only dags.TRG.epochs.1.readyNodeIds - DAG B's
        // path is never written, so its ready node survives by construction.
        List<JsonbPatch> patches = captureCasPatches();
        String[] otherPath = PatchPaths.epochSet("trigger:other", EPOCH, PatchPaths.READY_NODE_IDS);
        boolean touchesOtherDag = patches.stream()
                .anyMatch(p -> java.util.Arrays.equals(p.path(), otherPath));
        assertThat(touchesOtherDag)
                .as("patch must not touch the other DAG's ready-set")
                .isFalse();
    }

    // ------------------------------------------------------------------------
    // (b) Fallback to full rewrite when the patch cannot be built
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("(b) Epoch absent in snapshot → builder fallback → pessimistic full rewrite")
    void fallbackToFullRewriteWhenEpochMissing() throws Exception {
        // Snapshot has the ready node at epoch 1, but we merge against epoch 99
        // which has no EpochState → ReplaceReadyNodeSetPatchBuilder returns
        // fallback → CAS returns false → pessimistic full-rewrite path runs.
        StateSnapshot before = snapshotWithReady(2L, Set.of(TRG));
        stubCasRead(before);
        WorkflowRunEntity run = runEntity(before);
        when(runRepository.findByRunIdPublicForUpdate(RUN)).thenReturn(Optional.of(run));

        service.mergeReadyNodesAfterExecution(RUN, TRG, 99, TRG, Set.of("mcp:step1"));

        // No CAS patch applied; the full rewrite path persisted instead.
        verify(patchExecutor, never()).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        verify(runRepository).updateSnapshotAndSeq(eq(RUN), anyString());
        // And the persisted snapshot is correct (behavior preserved).
        StateSnapshot persisted = service.parseSnapshotJson(run.getStateSnapshot());
        assertThat(persisted.getDags().get(TRG).getEpochState(99).getReadyNodeIds())
                .containsExactly("mcp:step1");
    }

    @Test
    @DisplayName("(b) CAS retry exhausted (3 conflicts) → pessimistic full rewrite, result correct")
    void fallbackToFullRewriteWhenCasRetryExhausted() throws Exception {
        StateSnapshot before = snapshotWithReady(4L, Set.of(TRG));
        stubCasRead(before);
        when(patchExecutor.applyPatchesCas(anyString(), anyList(), anyLong(), anyLong()))
                .thenReturn(0); // always conflicts → retry budget exhausted → CAS false
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(40L);
        WorkflowRunEntity run = runEntity(before);
        when(runRepository.findByRunIdPublicForUpdate(RUN)).thenReturn(Optional.of(run));

        service.mergeReadyNodesAfterExecution(RUN, TRG, EPOCH, TRG, Set.of("mcp:step1"));

        verify(patchExecutor, times(3)).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        verify(runRepository).updateSnapshotAndSeq(eq(RUN), anyString());
        StateSnapshot persisted = service.parseSnapshotJson(run.getStateSnapshot());
        assertThat(persisted.getDags().get(TRG).getEpochState(EPOCH).getReadyNodeIds())
                .as("merge result is identical to today after the pessimistic fallback")
                .containsExactly("mcp:step1");
    }

    @Test
    @DisplayName("(b) Legacy epoch<0 merge stays on the full-rewrite path (never CAS)")
    void legacyNegativeEpochStaysOnFullRewrite() throws Exception {
        StateSnapshot before = StateSnapshot.empty().addReadyNode(TRG, TRG);
        WorkflowRunEntity run = runEntity(before);
        when(runRepository.findByRunIdPublicForUpdate(RUN)).thenReturn(Optional.of(run));

        service.mergeReadyNodesAfterExecution(RUN, TRG, -1, TRG, Set.of("mcp:step1"));

        verify(patchExecutor, never()).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        verify(runRepository).updateSnapshotAndSeq(eq(RUN), anyString());
    }

    // ------------------------------------------------------------------------
    // No-op: ready-set byte-for-byte unchanged
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("No-op when ready-set unchanged → success, neither CAS SQL nor full rewrite")
    void noOpWhenReadySetUnchanged() throws Exception {
        // Executed node wasn't ready AND the only successor is already ready.
        StateSnapshot before = snapshotWithReady(8L, Set.of("mcp:step1"));
        stubCasRead(before);

        service.mergeReadyNodesAfterExecution(RUN, TRG, EPOCH, "mcp:not_ready", Set.of("mcp:step1"));

        verify(patchExecutor, never()).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        verify(runRepository, never()).updateSnapshotAndSeq(anyString(), anyString());
        verify(runRepository, never()).findByRunIdPublicForUpdate(anyString());
    }

    // ------------------------------------------------------------------------
    // (c) Regression guard - the intent in the name
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("(c) mergeReadyNodesUsesPatchNotFullRewrite")
    void mergeReadyNodesUsesPatchNotFullRewrite() throws Exception {
        StateSnapshot before = snapshotWithReady(12L, Set.of(TRG));
        stubCasRead(before);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(12L), eq(13L))).thenReturn(1);
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(40L);

        service.mergeReadyNodesAfterExecution(RUN, TRG, EPOCH, TRG, Set.of("mcp:step1"));

        // The whole point of FIX #3: the hot epoch-scoped merge must NOT
        // re-serialize the entire snapshot via the full-rewrite UPDATE.
        verify(patchExecutor).applyPatchesCas(eq(RUN), anyList(), eq(12L), eq(13L));
        verify(runRepository, never()).updateSnapshotAndSeq(anyString(), anyString());
        verify(runRepository, never()).findByRunIdPublicForUpdate(anyString());
    }

    private WorkflowRunEntity runEntity(StateSnapshot snapshot) throws Exception {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN);
        run.setStatus(RunStatus.RUNNING);
        run.setStateSnapshot(mapper.writeValueAsString(snapshot));
        return run;
    }
}

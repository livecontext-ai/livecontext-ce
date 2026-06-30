package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.repository.StateSnapshotSeqAndJsonProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.elide.TenantElideFlagResolver;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatchExecutor;
import com.apimarketplace.orchestrator.services.state.patch.MarkNodeCompletedPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.MarkNodeFailedPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.MarkNodeSkippedPatchBuilder;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FIX #1 - {@link StateSnapshotService#recordNodeCompletionAndGetCounts} must take the
 * lock-free CAS path (no {@code SELECT…FOR UPDATE}) on the happy path, returning the
 * post-commit {@link StateSnapshot.NodeCounts} read back from the snapshot, and fall back
 * to the unchanged pessimistic path on retry-exhaust / missing tenant.
 *
 * <p>Wiring mirrors {@link MergeReadyNodesPatchPathTest}: real CAS path against a mock
 * {@link JsonbPatchExecutor}, real patch builders reflection-injected.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FIX #1 - recordNodeCompletionAndGetCounts (CAS lock-free, not FOR UPDATE)")
class RecordNodeCompletionCasPathTest {

    private static final String RUN = "run-1";
    private static final String TRG = "trigger:webhook";
    private static final String NODE = "mcp:step1";
    private static final String TENANT = "tenant-1";
    private static final int EPOCH = 1;

    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowEpochService workflowEpochService;
    @Mock WorkflowEventPublisher eventPublisher;
    @Mock StorageBreakdownService breakdownService;
    @Mock JsonbPatchExecutor patchExecutor;
    @Mock EntityManager entityManager;
    @Mock TenantElideFlagResolver elideResolver;

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
        setField("markNodeCompletedPatchBuilder", new MarkNodeCompletedPatchBuilder(mapper, elideResolver));
        setField("markNodeFailedPatchBuilder", new MarkNodeFailedPatchBuilder(mapper, elideResolver));
        setField("markNodeSkippedPatchBuilder", new MarkNodeSkippedPatchBuilder(mapper));
        lenient().when(elideResolver.isElideEnabled(anyString())).thenReturn(false);
        lenient().when(runRepository.findTenantIdByRunIdPublic(RUN)).thenReturn(Optional.of(TENANT));
        lenient().when(runRepository.updateSnapshotAndSeq(anyString(), anyString())).thenReturn(1);
        // Pessimistic fallback persists via saveSnapshotPatched → applyPatches; make it succeed
        // so the fallback doesn't trigger a secondary zero-rows re-read of the run row.
        lenient().when(patchExecutor.applyPatches(anyString(), anyList(), anyLong())).thenReturn(1);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = StateSnapshotService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    /** Snapshot with {@code NODE} ready at (TRG, EPOCH) → epoch is materialised, seq bumped. */
    private StateSnapshot readySnapshot(long seq) {
        StateSnapshot s = StateSnapshot.empty().addReadyNode(TRG, NODE, EPOCH);
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

    /** Stub the CAS fresh-read (seq+json) and the post-commit counts read with {@code post}. */
    private void stubReads(StateSnapshot before, StateSnapshot post) throws Exception {
        when(runRepository.findSeqAndStateSnapshotByRunIdPublic(RUN))
                .thenReturn(Optional.of(projection(before.getSeq(), mapper.writeValueAsString(before))));
        lenient().when(runRepository.findStateSnapshotByRunIdPublic(RUN))
                .thenReturn(Optional.of(mapper.writeValueAsString(post)));
    }

    private WorkflowRunEntity runEntity(StateSnapshot snapshot) throws Exception {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN);
        run.setTenantId(TENANT);
        run.setStatus(RunStatus.RUNNING);
        run.setStateSnapshot(mapper.writeValueAsString(snapshot));
        return run;
    }

    @Test
    @DisplayName("COMPLETED → CAS patch applied, never FOR UPDATE, returns post-commit counts")
    void completedTakesCasPathAndReturnsCounts() throws Exception {
        StateSnapshot before = readySnapshot(5L);
        StateSnapshot post = before.markNodeCompleted(TRG, NODE, EPOCH, 100L).withIncrementedSeq();
        stubReads(before, post);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L))).thenReturn(1);
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(80L);

        StateSnapshot.NodeCounts result =
                service.recordNodeCompletionAndGetCounts(RUN, NODE, "COMPLETED", TRG, EPOCH, 100L);

        verify(patchExecutor).applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L));
        verify(runRepository, never()).findByRunIdPublicForUpdate(anyString());
        verify(runRepository, never()).updateSnapshotAndSeq(anyString(), anyString());
        assertThat(result.completed()).as("the completion is reflected in the returned counts").isEqualTo(1);
        assertThat(result).isEqualTo(post.getNodeCounts(NODE));
    }

    @Test
    @DisplayName("FAILED → CAS patch applied, never FOR UPDATE")
    void failedTakesCasPath() throws Exception {
        StateSnapshot before = readySnapshot(5L);
        StateSnapshot post = before.markNodeFailed(TRG, NODE, EPOCH, 100L).withIncrementedSeq();
        stubReads(before, post);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L))).thenReturn(1);
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(80L);

        StateSnapshot.NodeCounts result =
                service.recordNodeCompletionAndGetCounts(RUN, NODE, "FAILED", TRG, EPOCH, 100L);

        verify(patchExecutor).applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L));
        verify(runRepository, never()).findByRunIdPublicForUpdate(anyString());
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("SKIPPED → CAS patch applied (no tenantId needed), never FOR UPDATE")
    void skippedTakesCasPath() throws Exception {
        StateSnapshot before = readySnapshot(5L);
        StateSnapshot post = before.markNodeSkipped(TRG, NODE, EPOCH).withIncrementedSeq();
        stubReads(before, post);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L))).thenReturn(1);
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(60L);

        StateSnapshot.NodeCounts result =
                service.recordNodeCompletionAndGetCounts(RUN, NODE, "SKIPPED", TRG, EPOCH, 0L);

        verify(patchExecutor).applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L));
        verify(runRepository, never()).findByRunIdPublicForUpdate(anyString());
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("CAS retry exhausted (3 conflicts) → pessimistic fallback, counts still correct")
    void fallsBackToPessimisticWhenCasExhausted() throws Exception {
        StateSnapshot before = readySnapshot(5L);
        when(runRepository.findSeqAndStateSnapshotByRunIdPublic(RUN))
                .thenReturn(Optional.of(projection(before.getSeq(), mapper.writeValueAsString(before))));
        when(patchExecutor.applyPatchesCas(anyString(), anyList(), anyLong(), anyLong())).thenReturn(0);
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(80L);
        when(runRepository.findByRunIdPublicForUpdate(RUN)).thenReturn(Optional.of(runEntity(before)));

        StateSnapshot.NodeCounts result =
                service.recordNodeCompletionAndGetCounts(RUN, NODE, "COMPLETED", TRG, EPOCH, 100L);

        // CAS retries then drops to the pessimistic FOR UPDATE path (which itself re-tries CAS
        // inside saveSnapshotPatched, so we assert the lock acquisition rather than a CAS count).
        verify(runRepository).findByRunIdPublicForUpdate(RUN);
        assertThat(result.completed()).as("pessimistic fallback returns the same correct counts").isEqualTo(1);
    }

    @Test
    @DisplayName("Missing tenantId → COMPLETED skips CAS and takes the pessimistic fallback")
    void missingTenantFallsBackForCompleted() throws Exception {
        StateSnapshot before = readySnapshot(5L);
        when(runRepository.findTenantIdByRunIdPublic(RUN)).thenReturn(Optional.empty());
        when(runRepository.findByRunIdPublicForUpdate(RUN)).thenReturn(Optional.of(runEntity(before)));

        StateSnapshot.NodeCounts result =
                service.recordNodeCompletionAndGetCounts(RUN, NODE, "COMPLETED", TRG, EPOCH, 100L);

        verify(patchExecutor, never()).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        verify(runRepository).findByRunIdPublicForUpdate(RUN);
        assertThat(result.completed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Regression: nodeCompletionUsesCasNotForUpdateOnHappyPath")
    void nodeCompletionUsesCasNotForUpdateOnHappyPath() throws Exception {
        StateSnapshot before = readySnapshot(9L);
        StateSnapshot post = before.markNodeCompleted(TRG, NODE, EPOCH, 50L).withIncrementedSeq();
        stubReads(before, post);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(9L), eq(10L))).thenReturn(1);
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(80L);

        service.recordNodeCompletionAndGetCounts(RUN, NODE, "COMPLETED", TRG, EPOCH, 50L);

        // The whole point of FIX #1: the dominant per-node write path drops the row lock.
        verify(runRepository, never()).findByRunIdPublicForUpdate(anyString());
        verify(runRepository, never()).updateSnapshotAndSeq(anyString(), anyString());
    }
}

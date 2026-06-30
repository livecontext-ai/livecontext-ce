package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.repository.StateSnapshotSeqAndJsonProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatchExecutor;
import com.apimarketplace.orchestrator.services.state.patch.RecordEdgeStatusPatchBuilder;
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
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;

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
 * FIX #2 - the edge writers ({@code recordEdgeStatus}, {@code recordEdgeStatuses},
 * {@code recordEdgeStatusesBatch}) must persist via the lock-free CAS path (no
 * {@code SELECT…FOR UPDATE}, no full snapshot rewrite) and fall back to the pessimistic
 * full rewrite only when the patch can't be built (edges map not yet initialised) or the
 * CAS retry budget is exhausted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FIX #2 - edge writers (CAS lock-free, not full rewrite)")
class RecordEdgeStatusCasPathTest {

    private static final String RUN = "run-1";

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
        setField("recordEdgeStatusPatchBuilder", new RecordEdgeStatusPatchBuilder(mapper));
        lenient().when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(40L);
        lenient().when(runRepository.updateSnapshotAndSeq(anyString(), anyString())).thenReturn(1);
        lenient().when(patchExecutor.applyPatches(anyString(), anyList(), anyLong())).thenReturn(1);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = StateSnapshotService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    /** Snapshot with one seed edge already present (so jsonb_set has a parent map). */
    private StateSnapshot seededEdges(long seq) {
        StateSnapshot s = StateSnapshot.empty().incrementEdge("seed", "node", "COMPLETED");
        while (s.getSeq() < seq) {
            s = s.withIncrementedSeq();
        }
        return s;
    }

    private void stubCasRead(StateSnapshot before) throws Exception {
        when(runRepository.findSeqAndStateSnapshotByRunIdPublic(RUN)).thenReturn(Optional.of(
                new StateSnapshotSeqAndJsonProjection() {
                    @Override public Long getStateSnapshotSeq() { return before.getSeq(); }
                    @Override public String getStateSnapshot() {
                        try { return mapper.writeValueAsString(before); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }
                }));
    }

    private WorkflowRunEntity runEntity(StateSnapshot snapshot) throws Exception {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN);
        run.setStatus(RunStatus.RUNNING);
        run.setStateSnapshot(mapper.writeValueAsString(snapshot));
        return run;
    }

    private static Map<String, Map.Entry<String, Integer>> increments(String edgeKey, String status, int count) {
        return Map.of(edgeKey, new AbstractMap.SimpleEntry<>(status, count));
    }

    @Test
    @DisplayName("recordEdgeStatus (single) → CAS patch, never FOR UPDATE / full rewrite")
    void singleEdgeTakesCasPath() throws Exception {
        StateSnapshot before = seededEdges(5L);
        stubCasRead(before);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L))).thenReturn(1);

        service.recordEdgeStatus(RUN, "a", "b", "COMPLETED");

        verify(patchExecutor).applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L));
        verify(runRepository, never()).findByRunIdPublicForUpdate(anyString());
        verify(runRepository, never()).updateSnapshotAndSeq(anyString(), anyString());
    }

    @Test
    @DisplayName("recordEdgeStatusesBatch → single CAS call with one patch per changed edge")
    void batchTakesCasPathWithMultiEdgePatch() throws Exception {
        StateSnapshot before = seededEdges(5L);
        stubCasRead(before);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L))).thenReturn(1);

        Map<String, Map.Entry<String, Integer>> incs = Map.of(
                "a->b", new AbstractMap.SimpleEntry<>("COMPLETED", 2),
                "c->d", new AbstractMap.SimpleEntry<>("SKIPPED", 1));

        service.recordEdgeStatusesBatch(RUN, incs);

        verify(patchExecutor, times(1)).applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L));
        verify(runRepository, never()).findByRunIdPublicForUpdate(anyString());
        verify(runRepository, never()).updateSnapshotAndSeq(anyString(), anyString());
    }

    @Test
    @DisplayName("recordEdgeStatuses (status map) → CAS patch, never full rewrite")
    void statusMapTakesCasPath() throws Exception {
        StateSnapshot before = seededEdges(5L);
        stubCasRead(before);
        when(patchExecutor.applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L))).thenReturn(1);

        service.recordEdgeStatuses(RUN, Map.of("a->b", "COMPLETED"));

        verify(patchExecutor).applyPatchesCas(eq(RUN), anyList(), eq(5L), eq(6L));
        verify(runRepository, never()).findByRunIdPublicForUpdate(anyString());
    }

    @Test
    @DisplayName("Batch on an uninitialised edges map → builder fallback → pessimistic full rewrite")
    void batchFallsBackWhenEdgesMapEmpty() throws Exception {
        StateSnapshot before = StateSnapshot.empty();  // no edges → buildBatch FALLBACK
        stubCasRead(before);
        when(runRepository.findByRunIdPublicForUpdate(RUN)).thenReturn(Optional.of(runEntity(before)));

        service.recordEdgeStatusesBatch(RUN, increments("a->b", "COMPLETED", 1));

        verify(patchExecutor, never()).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        verify(runRepository).findByRunIdPublicForUpdate(RUN);
        verify(runRepository).updateSnapshotAndSeq(eq(RUN), anyString());
    }

    @Test
    @DisplayName("Batch CAS retry exhausted (3 conflicts) → pessimistic full rewrite, result correct")
    void batchFallsBackWhenCasExhausted() throws Exception {
        StateSnapshot before = seededEdges(5L);
        stubCasRead(before);
        when(patchExecutor.applyPatchesCas(anyString(), anyList(), anyLong(), anyLong())).thenReturn(0);
        WorkflowRunEntity run = runEntity(before);
        when(runRepository.findByRunIdPublicForUpdate(RUN)).thenReturn(Optional.of(run));

        service.recordEdgeStatusesBatch(RUN, increments("a->b", "COMPLETED", 1));

        // CAS retries (3) are exhausted, then the pessimistic FOR UPDATE full-rewrite persists the
        // batch onto the run entity - assert the NEW edge actually landed, not just the pre-seeded one.
        verify(patchExecutor, times(3)).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        verify(runRepository).findByRunIdPublicForUpdate(RUN);
        StateSnapshot persisted = service.parseSnapshotJson(run.getStateSnapshot());
        assertThat(persisted.getEdges()).as("fallback persisted the a->b increment").containsKey("a->b");
    }
}

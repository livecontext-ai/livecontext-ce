package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.repository.StateSnapshotSeqAndJsonProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.elide.TenantElideFlagResolver;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatch;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatchExecutor;
import com.apimarketplace.orchestrator.services.state.patch.MarkNodeCompletedPatchBuilder;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan v4 §2v - pins the wire: markNodeCompleted (épochal, CAS path) emits
 * patches via buildWithDeltaCounter (DELTA on nodes.{X}.completed + 6
 * ASSIGN sub-paths) - NOT the legacy full-ASSIGN build().
 *
 * <p>This is the load-bearing test that the 4× throughput unlock is
 * actually wired in. Without this, the CAS path would emit ASSIGN patches
 * that the RunCoalescingService cannot merge under fan-out.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Plan v4 §2v - markNodeCompleted wires to buildWithDeltaCounter (DELTA merge enabled)")
class MarkNodeCompletedDeltaWireTest {

    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowEpochService workflowEpochService;
    @Mock WorkflowEventPublisher eventPublisher;
    @Mock StorageBreakdownService breakdownService;
    @Mock JsonbPatchExecutor patchExecutor;
    @Mock EntityManager entityManager;

    private SimpleMeterRegistry meterRegistry;
    private StateSnapshotService service;
    private MarkNodeCompletedPatchBuilder realBuilder;
    private static final String RUN_ID = "run-1";
    private static final String TRIGGER = "trigger:webhook";
    private static final String NODE = "X";
    private static final int EPOCH = 0;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        WorkflowMetrics workflowMetrics = new WorkflowMetrics(meterRegistry);
        TxScopedSnapshotCache txCache = new TxScopedSnapshotCache(runRepository, meterRegistry);
        TenantElideFlagResolver elideOff = t -> false;
        realBuilder = new MarkNodeCompletedPatchBuilder(mapper, elideOff);

        service = new StateSnapshotService(runRepository, mapper, workflowEpochService,
                eventPublisher, breakdownService, txCache, workflowMetrics);
        setField("useJsonbPatch", true);
        setField("casEnabled", true);
        setField("patchExecutor", patchExecutor);
        setField("entityManager", entityManager);
        setField("markNodeCompletedPatchBuilder", realBuilder);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = StateSnapshotService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    @Test
    @DisplayName("markNodeCompleted CAS path emits at least 1 COMMUTATIVE_DELTA patch on nodes.X.completed")
    void casPathEmitsDeltaCounter() throws Exception {
        // Seed: before snapshot has the DAG initialized + X in running set (so
        // buildWithDeltaCounter's epoch precondition succeeds)
        StateSnapshot before = StateSnapshot.empty()
                .ensureDagInitialized(TRIGGER, EPOCH)
                .addRunningNode(TRIGGER, NODE, EPOCH);
        String beforeJson = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(before);
        StateSnapshotSeqAndJsonProjection projection = new StateSnapshotSeqAndJsonProjection() {
            @Override public Long getStateSnapshotSeq() { return 0L; }
            @Override public String getStateSnapshot() { return beforeJson; }
        };
        when(runRepository.findTenantIdByRunIdPublic(RUN_ID)).thenReturn(Optional.of("tenant-a"));
        when(runRepository.findSeqAndStateSnapshotByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(projection));
        when(patchExecutor.applyPatchesCas(eq(RUN_ID), anyList(), eq(0L), eq(1L)))
                .thenReturn(1);
        when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(100L);

        service.markNodeCompleted(RUN_ID, TRIGGER, EPOCH, NODE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JsonbPatch>> patchesCaptor = ArgumentCaptor.forClass(List.class);
        verify(patchExecutor).applyPatchesCas(eq(RUN_ID), patchesCaptor.capture(), eq(0L), eq(1L));

        List<JsonbPatch> patches = patchesCaptor.getValue();

        // The KEY contract: at least 1 DELTA patch on nodes.X.completed.
        // Without buildWithDeltaCounter wired, patches would all be ASSIGN.
        long deltaCount = patches.stream()
                .filter(p -> p.opKind() == JsonbPatch.OpKind.COMMUTATIVE_DELTA)
                .count();
        assertThat(deltaCount)
                .as("plan v4 §2v: cooperative builder wired - CAS path must emit DELTA on counter")
                .isGreaterThanOrEqualTo(1);

        // Locate the +1 DELTA on nodes.X.completed
        JsonbPatch deltaPatch = patches.stream()
                .filter(p -> p.opKind() == JsonbPatch.OpKind.COMMUTATIVE_DELTA)
                .filter(p -> p.path().length == 3
                        && "nodes".equals(p.path()[0])
                        && NODE.equals(p.path()[1])
                        && "completed".equals(p.path()[2]))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no DELTA patch on nodes.X.completed - buildWithDeltaCounter not wired?"));
        assertThat(deltaPatch.jsonValue()).isEqualTo("1");
    }
}

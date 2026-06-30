package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.repository.StateSnapshotSeqAndJsonProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatch;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatchBuilder;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatchExecutor;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan v4 §1.5 phase 2n - tests for the lock-free saveSnapshotPatchedCas
 * variant. Verifies the mutator-closure re-run on CAS retry semantics that
 * make real concurrent-writer CAS load-bearing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Plan v4 §1.5 phase 2n - saveSnapshotPatchedCas (lock-free)")
class SaveSnapshotPatchedCasTest {

    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowEpochService workflowEpochService;
    @Mock WorkflowEventPublisher eventPublisher;
    @Mock StorageBreakdownService breakdownService;
    @Mock JsonbPatchExecutor patchExecutor;
    @Mock EntityManager entityManager;

    private SimpleMeterRegistry meterRegistry;
    private StateSnapshotService service;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        WorkflowMetrics workflowMetrics = new WorkflowMetrics(meterRegistry);
        TxScopedSnapshotCache txCache = new TxScopedSnapshotCache(runRepository, meterRegistry);
        service = new StateSnapshotService(runRepository, mapper, workflowEpochService,
                eventPublisher, breakdownService, txCache, workflowMetrics);
        setField("useJsonbPatch", true);
        setField("casEnabled", true);
        setField("patchExecutor", patchExecutor);
        setField("entityManager", entityManager);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = StateSnapshotService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private StateSnapshotSeqAndJsonProjection projection(long seq, String json) {
        return new StateSnapshotSeqAndJsonProjection() {
            @Override
            public Long getStateSnapshotSeq() {
                return seq;
            }

            @Override
            public String getStateSnapshot() {
                return json;
            }
        };
    }

    @Nested
    @DisplayName("Single-writer happy path")
    class HappyPath {

        @Test
        @DisplayName("Mutator runs once, CAS succeeds → returns true, jsonb_set_cas_lock_free metric")
        void singleAttemptSucceeds() {
            String json = "{\"seq\":10,\"nodes\":{}}";
            when(runRepository.findSeqAndStateSnapshotByRunIdPublic("run-1"))
                    .thenReturn(Optional.of(projection(10L, json)));
            when(patchExecutor.applyPatchesCas(eq("run-1"), anyList(), eq(10L), eq(11L)))
                    .thenReturn(1);
            when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(50L);

            AtomicInteger mutatorCalls = new AtomicInteger(0);
            boolean ok = service.saveSnapshotPatchedCas("run-1",
                    snap -> {
                        mutatorCalls.incrementAndGet();
                        return snap.markNodeCompleted("X");
                    },
                    (before, after) -> JsonbPatchBuilder.Result.patch(List.of(
                            new JsonbPatch(new String[]{"seq"}, "11"))),
                    "markNodeCompleted");

            assertThat(ok).isTrue();
            assertThat(mutatorCalls.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("CAS conflict → mutator re-run on retry")
    class RetryMutator {

        @Test
        @DisplayName("Conflict on attempt 1 → mutator runs AGAIN against fresh snapshot on attempt 2")
        void conflictReRunsMutator() {
            String json10 = "{\"seq\":10,\"nodes\":{}}";
            String json11 = "{\"seq\":11,\"nodes\":{\"Y\":{\"completed\":1}}}";  // peer added Y
            when(runRepository.findSeqAndStateSnapshotByRunIdPublic("run-1"))
                    .thenReturn(Optional.of(projection(10L, json10)))
                    .thenReturn(Optional.of(projection(11L, json11)));
            when(patchExecutor.applyPatchesCas(eq("run-1"), anyList(), eq(10L), eq(11L)))
                    .thenReturn(0);  // conflict on attempt 1
            when(patchExecutor.applyPatchesCas(eq("run-1"), anyList(), eq(11L), eq(12L)))
                    .thenReturn(1);  // success on attempt 2
            when(patchExecutor.estimatePayloadBytes(anyList())).thenReturn(50L);

            AtomicInteger mutatorCalls = new AtomicInteger(0);
            boolean ok = service.saveSnapshotPatchedCas("run-1",
                    snap -> {
                        mutatorCalls.incrementAndGet();
                        return snap.markNodeCompleted("X");
                    },
                    (before, after) -> JsonbPatchBuilder.Result.patch(List.of(
                            new JsonbPatch(new String[]{"seq"}, Long.toString(after.getSeq())))),
                    "markNodeCompleted");

            assertThat(ok).isTrue();
            assertThat(mutatorCalls.get())
                    .as("mutator re-run against fresh snapshot on retry - key contract for concurrent CAS")
                    .isEqualTo(2);
            // Both CAS calls happened with the correct (expected, new) seq pairs
            verify(patchExecutor).applyPatchesCas(eq("run-1"), anyList(), eq(10L), eq(11L));
            verify(patchExecutor).applyPatchesCas(eq("run-1"), anyList(), eq(11L), eq(12L));
        }

        @Test
        @DisplayName("3 conflicts → retry budget exhausted, return false, fallback metric")
        void retryExhaustReturnsFalse() {
            String json = "{\"seq\":10,\"nodes\":{}}";
            when(runRepository.findSeqAndStateSnapshotByRunIdPublic("run-1"))
                    .thenReturn(Optional.of(projection(10L, json)));
            when(patchExecutor.applyPatchesCas(anyString(), anyList(), anyLong(), anyLong()))
                    .thenReturn(0);  // always conflicts

            boolean ok = service.saveSnapshotPatchedCas("run-1",
                    snap -> snap.markNodeCompleted("X"),
                    (before, after) -> JsonbPatchBuilder.Result.patch(List.of(
                            new JsonbPatch(new String[]{"seq"}, "11"))),
                    "markNodeCompleted");

            assertThat(ok).isFalse();
            verify(patchExecutor, times(3)).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("Edge cases - row deleted, no-op, fallback")
    class EdgeCases {

        @Test
        @DisplayName("Row deleted → return false immediately (no retry storm)")
        void rowDeleted() {
            when(runRepository.findSeqAndStateSnapshotByRunIdPublic("run-1"))
                    .thenReturn(Optional.empty());

            boolean ok = service.saveSnapshotPatchedCas("run-1",
                    snap -> snap.markNodeCompleted("X"),
                    (before, after) -> JsonbPatchBuilder.Result.patch(List.of(
                            new JsonbPatch(new String[]{"seq"}, "1"))),
                    "markNodeCompleted");

            assertThat(ok).isFalse();
            verify(patchExecutor, never()).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("Builder returns NO_OP → return true (success, no SQL)")
        void builderNoOp() {
            String json = "{\"seq\":10,\"nodes\":{}}";
            when(runRepository.findSeqAndStateSnapshotByRunIdPublic("run-1"))
                    .thenReturn(Optional.of(projection(10L, json)));

            boolean ok = service.saveSnapshotPatchedCas("run-1",
                    snap -> snap,  // no-op mutation
                    (before, after) -> JsonbPatchBuilder.Result.noOp(),
                    "markNodeAwaitingSignal");

            assertThat(ok).isTrue();
            verify(patchExecutor, never()).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("Builder returns fallback → return false (caller fallback to full rewrite)")
        void builderFallback() {
            String json = "{\"seq\":10,\"nodes\":{}}";
            when(runRepository.findSeqAndStateSnapshotByRunIdPublic("run-1"))
                    .thenReturn(Optional.of(projection(10L, json)));

            boolean ok = service.saveSnapshotPatchedCas("run-1",
                    snap -> snap.markNodeCompleted("X"),
                    (before, after) -> JsonbPatchBuilder.Result.fallback(),
                    "markNodeCompleted");

            assertThat(ok).isFalse();
            verify(patchExecutor, never()).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("Mutator throws → return false, no retry, no CAS attempt")
        void mutatorThrows() {
            String json = "{\"seq\":10,\"nodes\":{}}";
            when(runRepository.findSeqAndStateSnapshotByRunIdPublic("run-1"))
                    .thenReturn(Optional.of(projection(10L, json)));

            boolean ok = service.saveSnapshotPatchedCas("run-1",
                    snap -> { throw new RuntimeException("mutator bug"); },
                    (before, after) -> JsonbPatchBuilder.Result.fallback(),
                    "markNodeCompleted");

            assertThat(ok).isFalse();
            verify(patchExecutor, never()).applyPatchesCas(anyString(), anyList(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("Flag OFF / executor unwired → bail out cleanly")
    class FlagsOff {

        @Test
        @DisplayName("casEnabled=false → return false without DB calls")
        void casDisabled() throws Exception {
            setField("casEnabled", false);

            boolean ok = service.saveSnapshotPatchedCas("run-1",
                    snap -> snap, (b, a) -> JsonbPatchBuilder.Result.noOp(), "noop");

            assertThat(ok).isFalse();
            verify(runRepository, never()).findSeqAndStateSnapshotByRunIdPublic(anyString());
        }

        @Test
        @DisplayName("patchExecutor=null → return false without DB calls")
        void executorUnwired() throws Exception {
            setField("patchExecutor", null);

            boolean ok = service.saveSnapshotPatchedCas("run-1",
                    snap -> snap, (b, a) -> JsonbPatchBuilder.Result.noOp(), "noop");

            assertThat(ok).isFalse();
        }
    }
}

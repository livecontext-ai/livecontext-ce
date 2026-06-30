package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkflowRunController#getAggregatedSteps}.
 *
 * <p>Regression coverage for the StepData modal dropping accumulated failures after a rerun:
 * the aggregation SQL keeps only the latest spawn per (alias, epoch, ...), so a node reran from
 * FAILED to COMPLETED loses its earlier FAILED tally from the "Status Counts" column. The
 * whole-run (no-epoch) path now re-sources {@code statusCounts} from the durable cumulative
 * counters so the modal matches the node badge / {@code /status-counts}. The derived
 * {@code status} (current state) and the per-epoch path are left untouched.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunController - Aggregated Steps statusCounts")
class WorkflowRunControllerAggregatedStepsTest {

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private StepAggregationService stepAggregationService;

    @Mock
    private WorkflowEpochService workflowEpochService;

    @Mock
    private StateSnapshotService stateSnapshotService;

    @InjectMocks
    private WorkflowRunController controller;

    private static final String RUN_ID = "run-test-agg";
    private static final String TENANT_ID = "tenant-A";

    @BeforeEach
    void wireOwnerCheck() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        lenient().when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        // Both overloads must return a present Optional so the controller proceeds to
        // toResponseList; the actual response rows come from the toResponseList stub per test.
        lenient().when(stepAggregationService.getAggregatedSteps(RUN_ID)).thenReturn(Optional.of(List.of()));
        lenient().when(stepAggregationService.getAggregatedSteps(eq(RUN_ID), anyInt())).thenReturn(Optional.of(List.of()));
    }

    /** One mutable aggregated-step row as the SQL projection would build it (max-spawn). */
    private static List<Map<String, Object>> responseRow(String alias, String status, Map<String, Object> statusCounts) {
        Map<String, Object> row = new HashMap<>();
        row.put("alias", alias);
        row.put("toolId", alias);
        row.put("status", status);
        row.put("statusCounts", statusCounts);
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(row);
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstStatusCounts(ResponseEntity<?> response) {
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        return (Map<String, Object>) body.get(0).get("statusCounts");
    }

    @Nested
    @DisplayName("Whole-run (no epoch) - cumulative override")
    class WholeRun {

        @Test
        @DisplayName("rerun FAILED->COMPLETED keeps the earlier failures: statusCounts re-sourced from cumulative")
        void overridesStatusCountsWithCumulative() {
            // The SQL max-spawn view lost a failure: shows {failed:1, completed:1}.
            Map<String, Object> maxSpawn = new HashMap<>();
            maxSpawn.put("failed", 1);
            maxSpawn.put("completed", 1);
            when(stepAggregationService.toResponseList(any()))
                    .thenReturn(responseRow("boom", "partial_success", maxSpawn));
            // The durable cumulative counters retained both failures.
            when(workflowEpochService.getAccumulatedNodeCounts(RUN_ID))
                    .thenReturn(Map.of("boom", Map.of("failed", 2L, "completed", 1L)));

            ResponseEntity<?> response = controller.getAggregatedSteps(RUN_ID, null, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> counts = firstStatusCounts(response);
            assertThat(counts).containsEntry("failed", 2L).containsEntry("completed", 1L);
        }

        @Test
        @DisplayName("preserves the derived 'status' (current state), only counts change")
        void preservesStatusField() {
            Map<String, Object> maxSpawn = new HashMap<>();
            maxSpawn.put("completed", 1);
            when(stepAggregationService.toResponseList(any()))
                    .thenReturn(responseRow("boom", "completed", maxSpawn));
            when(workflowEpochService.getAccumulatedNodeCounts(RUN_ID))
                    .thenReturn(Map.of("boom", Map.of("failed", 8L, "completed", 2L)));

            ResponseEntity<?> response = controller.getAggregatedSteps(RUN_ID, null, TENANT_ID, null);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
            // status (current state, from max-spawn) untouched; counts now cumulative (✓2 ✗8).
            assertThat(body.get(0).get("status")).isEqualTo("completed");
            assertThat(firstStatusCounts(response)).containsEntry("failed", 8L).containsEntry("completed", 2L);
        }

        @Test
        @DisplayName("zero-valued cumulative buckets are dropped (no '0 failed' noise)")
        void dropsZeroBuckets() {
            Map<String, Object> maxSpawn = new HashMap<>();
            maxSpawn.put("completed", 3);
            when(stepAggregationService.toResponseList(any()))
                    .thenReturn(responseRow("boom", "completed", maxSpawn));
            when(workflowEpochService.getAccumulatedNodeCounts(RUN_ID))
                    .thenReturn(Map.of("boom", Map.of("completed", 3L, "failed", 0L)));

            ResponseEntity<?> response = controller.getAggregatedSteps(RUN_ID, null, TENANT_ID, null);

            Map<String, Object> counts = firstStatusCounts(response);
            assertThat(counts).containsEntry("completed", 3L);
            assertThat(counts).doesNotContainKey("failed");
        }

        @Test
        @DisplayName("currently-running node keeps its live 'running' bucket; terminal counts go cumulative")
        void mergesRunningWithCumulativeTerminal() {
            // Latest spawn is the in-flight re-execution -> max-spawn row shows {running:1}.
            Map<String, Object> maxSpawn = new HashMap<>();
            maxSpawn.put("running", 1);
            when(stepAggregationService.toResponseList(any()))
                    .thenReturn(responseRow("boom", "running", maxSpawn));
            // Durable counters only record terminal outcomes (the earlier failures).
            when(workflowEpochService.getAccumulatedNodeCounts(RUN_ID))
                    .thenReturn(Map.of("boom", Map.of("failed", 3L)));

            ResponseEntity<?> response = controller.getAggregatedSteps(RUN_ID, null, TENANT_ID, null);

            Map<String, Object> counts = firstStatusCounts(response);
            // Transient 'running' carried over from max-spawn, terminal 'failed' from cumulative.
            assertThat(counts).containsEntry("running", 1).containsEntry("failed", 3L);
        }

        @Test
        @DisplayName("step with no cumulative entry keeps its original counts (alias mismatch is non-destructive)")
        void keepsOriginalWhenNoCumulativeEntry() {
            Map<String, Object> maxSpawn = new HashMap<>();
            maxSpawn.put("completed", 1);
            maxSpawn.put("failed", 1);
            when(stepAggregationService.toResponseList(any()))
                    .thenReturn(responseRow("boom", "partial_success", maxSpawn));
            // Cumulative has a different alias - boom must be left as-is, not blanked.
            when(workflowEpochService.getAccumulatedNodeCounts(RUN_ID))
                    .thenReturn(Map.of("other", Map.of("completed", 5L)));

            ResponseEntity<?> response = controller.getAggregatedSteps(RUN_ID, null, TENANT_ID, null);

            Map<String, Object> counts = firstStatusCounts(response);
            assertThat(counts).containsEntry("failed", 1).containsEntry("completed", 1);
        }

        @Test
        @DisplayName("empty cumulative map is a no-op (counts unchanged)")
        void noOpWhenCumulativeEmpty() {
            Map<String, Object> maxSpawn = new HashMap<>();
            maxSpawn.put("completed", 2);
            when(stepAggregationService.toResponseList(any()))
                    .thenReturn(responseRow("boom", "completed", maxSpawn));
            when(workflowEpochService.getAccumulatedNodeCounts(RUN_ID))
                    .thenReturn(Collections.emptyMap());

            ResponseEntity<?> response = controller.getAggregatedSteps(RUN_ID, null, TENANT_ID, null);

            assertThat(firstStatusCounts(response)).containsEntry("completed", 2);
        }
    }

    @Nested
    @DisplayName("Per-epoch path - NOT overridden")
    class PerEpoch {

        @Test
        @DisplayName("epoch-filtered statusCounts keep the per-epoch (max-spawn) values, cumulative is not consulted")
        void epochPathNotOverridden() {
            Map<String, Object> perEpoch = new HashMap<>();
            perEpoch.put("failed", 1);
            when(stepAggregationService.toResponseList(any()))
                    .thenReturn(responseRow("boom", "error", perEpoch));
            // Empty snapshot so enrichWithAwaitingSignal is a no-op.
            StateSnapshot snap = org.mockito.Mockito.mock(StateSnapshot.class);
            lenient().when(snap.getDags()).thenReturn(Collections.emptyMap());
            lenient().when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snap);

            ResponseEntity<?> response = controller.getAggregatedSteps(RUN_ID, 2, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // Still the per-epoch value; cumulative override must NOT touch the epoch path.
            assertThat(firstStatusCounts(response)).containsEntry("failed", 1);
            org.mockito.Mockito.verify(workflowEpochService, org.mockito.Mockito.never()).getAccumulatedNodeCounts(RUN_ID);
        }
    }
}

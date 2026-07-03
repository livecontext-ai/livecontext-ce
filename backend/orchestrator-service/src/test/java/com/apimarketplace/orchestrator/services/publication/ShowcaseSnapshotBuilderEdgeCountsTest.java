package com.apimarketplace.orchestrator.services.publication;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression: the marketplace "All epochs" canvas rendered NO edge
 * statusCounts because an epoch-pinned capture filtered the reconstructed
 * edges through the JSONB epoch node view - and closed epochs are PRUNED
 * from the StateSnapshot ({@code closeAndPruneEpoch}), so on any finished
 * run the view was empty and every edge was dropped ({@code runState.edges=[]}
 * frozen in the publication JSONB). Steps never broke because they already
 * fall back to the aggregation table. The fix sources edges from the durable
 * per-epoch rows ({@code WorkflowEpochService.getEpochState}) - the same
 * source the working per-epoch view freezes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ShowcaseSnapshotBuilder - runState edge counts")
class ShowcaseSnapshotBuilderEdgeCountsTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowResumeService workflowResumeService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private StepAggregationService stepAggregationService;
    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private InterfaceRenderService interfaceRenderService;
    @Mock private InterfaceClient interfaceClient;

    private ShowcaseSnapshotBuilder builder() {
        return new ShowcaseSnapshotBuilder(
                workflowRunRepository,
                workflowResumeService,
                stateSnapshotService,
                workflowEpochService,
                stepAggregationService,
                signalWaitRepository,
                interfaceRenderService,
                interfaceClient);
    }

    private WorkflowRunEntity run(String runId) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setRunIdPublic(runId);
        run.setTenantId("tenant-owner");
        run.setOrganizationId(null);
        when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));
        return run;
    }

    private void stubReconstructedState(String runId, List<WorkflowRunState.EdgeState> edges) {
        when(workflowResumeService.reconstructStateForApi(runId))
                .thenReturn(new WorkflowRunState(
                        runId,
                        "workflow-1",
                        RunStatus.COMPLETED,
                        ExecutionMode.AUTOMATIC,
                        Instant.EPOCH,
                        null,
                        Map.of(),
                        List.of(),
                        edges,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of()));
    }

    @Test
    @DisplayName("Regression: pinned-epoch capture keeps edge counts when the epoch is pruned from JSONB (sources them from the per-epoch rows)")
    void prunedJsonbEpochStillYieldsEdgeCounts() {
        WorkflowRunEntity run = run("run-pruned");
        stubReconstructedState("run-pruned", List.of(
                new WorkflowRunState.EdgeState("trigger:ask", "core:dispatch", RunStatus.COMPLETED, 1, 0, 1)));

        // Finished run: closeAndPruneEpoch removed epoch 2 from the DagState -
        // the pre-fix epoch node view is EMPTY and dropped every edge.
        DagState prunedDag = new DagState(2, 0, 1, Map.of(), Set.of());
        when(stateSnapshotService.getSnapshot("run-pruned"))
                .thenReturn(StateSnapshot.empty().withDagState("trigger:ask", prunedDag));

        when(workflowEpochService.listEpochTimestamps("run-pruned")).thenReturn(List.of());
        // Durable per-epoch rows (same source as the /epochs/{epoch}/state view)
        when(workflowEpochService.getEpochState("run-pruned", 2)).thenReturn(Map.of(
                "epoch", 2,
                "nodes", Map.of(
                        "trigger:ask", Map.of("COMPLETED", 1),
                        "core:dispatch", Map.of("COMPLETED", 1)),
                "edges", Map.of(
                        "trigger:ask->core:dispatch", Map.of("COMPLETED", 1),
                        "core:dispatch->agent:fable_5", Map.of("COMPLETED", 2, "SKIPPED", 1))));
        when(stepAggregationService.getAggregatedSteps("run-pruned", 2)).thenReturn(Optional.of(List.of()));
        when(signalWaitRepository.findActiveByRunIdAndEpoch("run-pruned", 2)).thenReturn(List.of());
        when(interfaceClient.getSnapshotsForRun(run.getId(), "tenant-owner", null)).thenReturn(List.of());

        Optional<Map<String, Object>> snapshot = builder().capture("run-pruned", "tenant-owner", null, 2);

        assertThat(snapshot).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) snapshot.get().get("runState");
        @SuppressWarnings("unchecked")
        List<WorkflowRunState.EdgeState> edges = (List<WorkflowRunState.EdgeState>) runState.get("edges");
        assertThat(edges)
                .as("Pre-fix the empty epoch node view dropped every edge (runState.edges=[]); "
                    + "the per-epoch rows must now feed them.")
                .hasSize(2);
        WorkflowRunState.EdgeState triggerEdge = edges.stream()
                .filter(e -> "trigger:ask".equals(e.from())).findFirst().orElseThrow();
        assertThat(triggerEdge.to()).isEqualTo("core:dispatch");
        assertThat(triggerEdge.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(triggerEdge.completedCount()).isEqualTo(1);
        assertThat(triggerEdge.totalCount()).isEqualTo(1);
        WorkflowRunState.EdgeState mixedEdge = edges.stream()
                .filter(e -> "core:dispatch".equals(e.from())).findFirst().orElseThrow();
        assertThat(mixedEdge.completedCount()).isEqualTo(2);
        assertThat(mixedEdge.skippedCount()).isEqualTo(1);
        assertThat(mixedEdge.totalCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Fallback: no per-epoch edge rows → edges still filtered through the JSONB epoch view (legacy behavior preserved)")
    void missingEpochRowsFallsBackToNodeViewFilter() {
        WorkflowRunEntity run = run("run-legacy");
        stubReconstructedState("run-legacy", List.of(
                new WorkflowRunState.EdgeState("trigger:ask", "mcp:node_a", RunStatus.COMPLETED, 9, 0, 9)));

        // Epoch 2 still present in JSONB with both endpoints completed.
        EpochState epoch2 = new EpochState(
                Set.of("trigger:ask", "mcp:node_a"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), Map.of(), Instant.EPOCH);
        DagState dag = new DagState(2, 0, 1, Map.of(2, epoch2), Set.of());
        when(stateSnapshotService.getSnapshot("run-legacy"))
                .thenReturn(StateSnapshot.empty().withDagState("trigger:ask", dag));

        when(workflowEpochService.listEpochTimestamps("run-legacy")).thenReturn(List.of());
        // No durable edge rows for this epoch (legacy run)
        when(workflowEpochService.getEpochState("run-legacy", 2)).thenReturn(Map.of(
                "epoch", 2, "nodes", Map.of(), "edges", Map.of()));
        when(stepAggregationService.getAggregatedSteps("run-legacy", 2)).thenReturn(Optional.of(List.of()));
        when(signalWaitRepository.findActiveByRunIdAndEpoch("run-legacy", 2)).thenReturn(List.of());
        when(interfaceClient.getSnapshotsForRun(run.getId(), "tenant-owner", null)).thenReturn(List.of());

        Optional<Map<String, Object>> snapshot = builder().capture("run-legacy", "tenant-owner", null, 2);

        assertThat(snapshot).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) snapshot.get().get("runState");
        @SuppressWarnings("unchecked")
        List<WorkflowRunState.EdgeState> edges = (List<WorkflowRunState.EdgeState>) runState.get("edges");
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).from()).isEqualTo("trigger:ask");
        assertThat(edges.get(0).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(edges.get(0).completedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("RUNNING outranks COMPLETED and SKIPPED-only edges derive SKIPPED (status precedence legs)")
    void statusPrecedenceLegs() {
        WorkflowRunEntity run = run("run-status-legs");
        stubReconstructedState("run-status-legs", List.of());
        DagState prunedDag = new DagState(2, 0, 1, Map.of(), Set.of());
        when(stateSnapshotService.getSnapshot("run-status-legs"))
                .thenReturn(StateSnapshot.empty().withDagState("trigger:ask", prunedDag));
        when(workflowEpochService.listEpochTimestamps("run-status-legs")).thenReturn(List.of());
        when(workflowEpochService.getEpochState("run-status-legs", 2)).thenReturn(Map.of(
                "epoch", 2,
                "nodes", Map.of("trigger:ask", Map.of("COMPLETED", 1)),
                "edges", Map.of(
                        "core:a->mcp:running_leg", Map.of("RUNNING", 1, "COMPLETED", 2),
                        "core:a->mcp:skipped_leg", Map.of("skipped", 3))));
        when(stepAggregationService.getAggregatedSteps("run-status-legs", 2)).thenReturn(Optional.of(List.of()));
        when(signalWaitRepository.findActiveByRunIdAndEpoch("run-status-legs", 2)).thenReturn(List.of());
        when(interfaceClient.getSnapshotsForRun(run.getId(), "tenant-owner", null)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>)
                builder().capture("run-status-legs", "tenant-owner", null, 2).orElseThrow().get("runState");
        @SuppressWarnings("unchecked")
        List<WorkflowRunState.EdgeState> edges = (List<WorkflowRunState.EdgeState>) runState.get("edges");
        WorkflowRunState.EdgeState runningEdge = edges.stream()
                .filter(e -> "mcp:running_leg".equals(e.to())).findFirst().orElseThrow();
        assertThat(runningEdge.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(runningEdge.completedCount()).isEqualTo(2);
        WorkflowRunState.EdgeState skippedEdge = edges.stream()
                .filter(e -> "mcp:skipped_leg".equals(e.to())).findFirst().orElseThrow();
        assertThat(skippedEdge.status())
                .as("lowercase status keys are normalized before deriving")
                .isEqualTo(RunStatus.SKIPPED);
        assertThat(skippedEdge.skippedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Malformed edge rows (no arrow, dangling arrow, non-map counts, non-numeric counts) are skipped; nothing valid left → JSONB fallback")
    void malformedEpochRowsFallBackToNodeViewFilter() {
        WorkflowRunEntity run = run("run-malformed");
        stubReconstructedState("run-malformed", List.of(
                new WorkflowRunState.EdgeState("trigger:ask", "mcp:node_a", RunStatus.COMPLETED, 9, 0, 9)));
        EpochState epoch2 = new EpochState(
                Set.of("trigger:ask", "mcp:node_a"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), Map.of(), Instant.EPOCH);
        DagState dag = new DagState(2, 0, 1, Map.of(2, epoch2), Set.of());
        when(stateSnapshotService.getSnapshot("run-malformed"))
                .thenReturn(StateSnapshot.empty().withDagState("trigger:ask", dag));
        when(workflowEpochService.listEpochTimestamps("run-malformed")).thenReturn(List.of());
        when(workflowEpochService.getEpochState("run-malformed", 2)).thenReturn(Map.of(
                "epoch", 2,
                "nodes", Map.of("trigger:ask", Map.of("COMPLETED", 1)),
                "edges", Map.of(
                        "no_arrow_key", Map.of("COMPLETED", 1),
                        "dangling->", Map.of("COMPLETED", 1),
                        "core:a->mcp:b", "not-a-map",
                        "core:a->mcp:c", Map.of("COMPLETED", "oops"))));
        when(stepAggregationService.getAggregatedSteps("run-malformed", 2)).thenReturn(Optional.of(List.of()));
        when(signalWaitRepository.findActiveByRunIdAndEpoch("run-malformed", 2)).thenReturn(List.of());
        when(interfaceClient.getSnapshotsForRun(run.getId(), "tenant-owner", null)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>)
                builder().capture("run-malformed", "tenant-owner", null, 2).orElseThrow().get("runState");
        @SuppressWarnings("unchecked")
        List<WorkflowRunState.EdgeState> edges = (List<WorkflowRunState.EdgeState>) runState.get("edges");
        assertThat(edges)
                .as("no valid per-epoch row survives → the JSONB node-view filter must take over")
                .hasSize(1);
        assertThat(edges.get(0).from()).isEqualTo("trigger:ask");
        assertThat(edges.get(0).completedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Per-epoch row lookup throwing falls back to the JSONB node-view filter (never fails the capture)")
    void epochRowLookupExceptionFallsBack() {
        WorkflowRunEntity run = run("run-throwing");
        stubReconstructedState("run-throwing", List.of(
                new WorkflowRunState.EdgeState("trigger:ask", "mcp:node_a", RunStatus.COMPLETED, 9, 0, 9)));
        EpochState epoch2 = new EpochState(
                Set.of("trigger:ask", "mcp:node_a"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), Map.of(), Instant.EPOCH);
        DagState dag = new DagState(2, 0, 1, Map.of(2, epoch2), Set.of());
        when(stateSnapshotService.getSnapshot("run-throwing"))
                .thenReturn(StateSnapshot.empty().withDagState("trigger:ask", dag));
        when(workflowEpochService.listEpochTimestamps("run-throwing")).thenReturn(List.of());
        when(workflowEpochService.getEpochState("run-throwing", 2))
                .thenThrow(new IllegalStateException("epoch table unavailable"));
        when(stepAggregationService.getAggregatedSteps("run-throwing", 2)).thenReturn(Optional.of(List.of()));
        when(signalWaitRepository.findActiveByRunIdAndEpoch("run-throwing", 2)).thenReturn(List.of());
        when(interfaceClient.getSnapshotsForRun(run.getId(), "tenant-owner", null)).thenReturn(List.of());

        Optional<Map<String, Object>> snapshot = builder().capture("run-throwing", "tenant-owner", null, 2);

        assertThat(snapshot).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) snapshot.get().get("runState");
        @SuppressWarnings("unchecked")
        List<WorkflowRunState.EdgeState> edges = (List<WorkflowRunState.EdgeState>) runState.get("edges");
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    @DisplayName("Unfiltered capture (no pinned epoch) keeps the reconstructed edges verbatim")
    void unfilteredCaptureKeepsReconstructedEdges() {
        WorkflowRunEntity run = run("run-all");
        stubReconstructedState("run-all", List.of(
                new WorkflowRunState.EdgeState("trigger:ask", "mcp:node_a", RunStatus.COMPLETED, 4, 1, 5)));
        when(stateSnapshotService.getSnapshot("run-all")).thenReturn(StateSnapshot.empty());
        when(workflowEpochService.listEpochTimestamps("run-all")).thenReturn(List.of());
        when(stepAggregationService.getAggregatedSteps("run-all")).thenReturn(Optional.of(List.of()));
        when(interfaceClient.getSnapshotsForRun(run.getId(), "tenant-owner", null)).thenReturn(List.of());

        Optional<Map<String, Object>> snapshot = builder().capture("run-all", "tenant-owner", null, null);

        assertThat(snapshot).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) snapshot.get().get("runState");
        @SuppressWarnings("unchecked")
        List<WorkflowRunState.EdgeState> edges = (List<WorkflowRunState.EdgeState>) runState.get("edges");
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).completedCount()).isEqualTo(4);
        assertThat(edges.get(0).skippedCount()).isEqualTo(1);
    }
}

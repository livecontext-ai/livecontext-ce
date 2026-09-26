package com.apimarketplace.orchestrator.services.analysis;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochTimestampRow;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.StepAggregationService.AggregatedStep;
import com.apimarketplace.orchestrator.services.StepAggregationService.EpochRangeAggregation;
import com.apimarketplace.orchestrator.services.analysis.RunAnalysisService.EpochAnalysis;
import com.apimarketplace.orchestrator.services.analysis.RunAnalysisService.NodeCell;
import com.apimarketplace.orchestrator.services.analysis.RunAnalysisService.RunAnalysis;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunAnalysisService - windowed epochs joined with node cells and cost")
class RunAnalysisServiceTest {

    private static final String RUN_ID = "run_analysis";

    @Mock
    private WorkflowEpochService workflowEpochService;

    @Mock
    private StepAggregationService stepAggregationService;

    private RunAnalysisService service;
    private WorkflowRunEntity run;

    @BeforeEach
    void setUp() {
        service = new RunAnalysisService(workflowEpochService, stepAggregationService);
        run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
    }

    private void totalEpochs(long total) {
        lenient().when(workflowEpochService.countEpochsByRunIds(List.of(RUN_ID))).thenReturn(Map.of(RUN_ID, total));
    }

    private void window(int limit, EpochTimestampRow... rows) {
        when(workflowEpochService.listLatestEpochTimestamps(RUN_ID, limit)).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("a run that never fired answers an empty window without querying the steps")
    void emptyRunSkipsTheStepQuery() {
        window(RunAnalysisService.DEFAULT_LIMIT);

        RunAnalysis analysis = service.analyze(run, null);

        assertThat(analysis.totalEpochs()).isZero();
        assertThat(analysis.epochs()).isEmpty();
        verify(stepAggregationService, never()).getAggregatedStepsByEpochRange(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("the window is read from the database (never the whole run), clamped, and returned oldest first")
    void windowIsReadClampedAndSortedOldestFirst() {
        // The repository answers newest first; the payload must read left to right in time.
        window(RunAnalysisService.MAX_LIMIT, epoch(9), epoch(7), epoch(8));
        totalEpochs(1_234);
        when(stepAggregationService.getAggregatedStepsByEpochRange(RUN_ID, 7, 9))
                .thenReturn(new EpochRangeAggregation(Map.of(), Map.of()));

        RunAnalysis analysis = service.analyze(run, 10_000);

        assertThat(analysis.epochs()).extracting(EpochAnalysis::epoch).containsExactly(7, 8, 9);
        assertThat(analysis.totalEpochs()).as("the run's own count, so the client can say 'last 3 of 1 234'").isEqualTo(1_234);
        verify(workflowEpochService, never()).listEpochTimestamps(anyString());
    }

    @Test
    @DisplayName("the total is never below the window, even if the count read raced a new fire")
    void totalNeverBelowTheWindow() {
        window(5, epoch(1), epoch(2));
        totalEpochs(1);
        when(stepAggregationService.getAggregatedStepsByEpochRange(RUN_ID, 1, 2))
                .thenReturn(new EpochRangeAggregation(Map.of(), Map.of()));

        assertThat(service.analyze(run, 5).totalEpochs()).isEqualTo(2);
    }

    @Test
    @DisplayName("each epoch carries its own timing, outcome, cost and node cells")
    void joinsTimingOutcomeCostAndCells() {
        window(RunAnalysisService.DEFAULT_LIMIT,
                new EpochTimestampRow(1, "2026-09-26T10:00:00Z", "2026-09-26T10:00:05Z", 4_200L, "COMPLETED"),
                new EpochTimestampRow(2, "2026-09-26T10:30:00Z", "2026-09-26T10:30:01Z", 900L, "FAILED"));
        totalEpochs(2);
        Map<String, Object> cost = new HashMap<>();
        cost.put("1", 0.42);
        cost.put("2", "not-a-number");
        run.setCostByEpoch(cost);
        AggregatedStep ok = step("mcp:fetch", "completed", 1_500L, 1_400L, Map.of("completed", 1));
        AggregatedStep failed = step("mcp:fetch", "error", 300L, 300L, Map.of("failed", 1));
        when(stepAggregationService.getAggregatedStepsByEpochRange(RUN_ID, 1, 2)).thenReturn(new EpochRangeAggregation(
                Map.of(1, List.of(ok), 2, List.of(failed)),
                Map.of(2, Map.of("mcp:fetch", "HTTP 429"))));

        RunAnalysis analysis = service.analyze(run, null);

        EpochAnalysis first = analysis.epochs().get(0);
        assertThat(first.workDurationMs()).isEqualTo(4_200L);
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.costCredits()).isEqualTo(0.42);
        assertThat(first.nodes()).containsExactly(
                new NodeCell("mcp:fetch", "completed", 1_500L, 1_400L, Map.of("completed", 1), null));

        EpochAnalysis second = analysis.epochs().get(1);
        assertThat(second.status()).isEqualTo("FAILED");
        assertThat(second.costCredits()).as("a non-numeric cost entry is no cost, never a crash").isNull();
        assertThat(second.nodes()).containsExactly(
                new NodeCell("mcp:fetch", "error", 300L, 300L, Map.of("failed", 1), "HTTP 429"));
    }

    @Test
    @DisplayName("the elapsed time is the aggregation's own (per-iteration spans), never recomputed from the bounds")
    void elapsedComesFromTheAggregationNotFromTheBounds() {
        // Ten parallel items of 5 s: 50 s of work, 5 s held. The first-start/last-end bounds are
        // deliberately a minute apart (a loop around it): a span recomputed from them would say 60 s.
        AggregatedStep split = new AggregatedStep("agent:summarize", "completed", "agent:summarize",
                Instant.parse("2026-09-26T10:00:00Z"), Instant.parse("2026-09-26T10:01:00Z"),
                Map.of("completed", 10), 50_000L, 5_000L);
        window(RunAnalysisService.DEFAULT_LIMIT, epoch(1));
        totalEpochs(1);
        when(stepAggregationService.getAggregatedStepsByEpochRange(RUN_ID, 1, 1)).thenReturn(new EpochRangeAggregation(
                Map.of(1, List.of(split)), Map.of()));

        NodeCell cell = service.analyze(run, null).epochs().get(0).nodes().get(0);

        assertThat(cell.elapsedMs()).isEqualTo(5_000L);
        assertThat(cell.executionTimeMs()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("an epoch whose nodes produced no step row still appears, with no cells")
    void epochWithoutStepsHasNoCells() {
        window(RunAnalysisService.DEFAULT_LIMIT, epoch(7));
        totalEpochs(1);
        when(stepAggregationService.getAggregatedStepsByEpochRange(RUN_ID, 7, 7))
                .thenReturn(new EpochRangeAggregation(Map.of(), Map.of()));

        RunAnalysis analysis = service.analyze(run, null);

        assertThat(analysis.epochs()).singleElement().satisfies(e -> {
            assertThat(e.nodes()).isEmpty();
            assertThat(e.costCredits()).as("no cost_by_epoch on the run").isNull();
        });
    }

    @Test
    @DisplayName("a negative summed duration (writers disagreeing) is reported as zero; an untimed node has no elapsed")
    void negativeDurationIsClampedToZero() {
        window(RunAnalysisService.DEFAULT_LIMIT, epoch(1));
        totalEpochs(1);
        when(stepAggregationService.getAggregatedStepsByEpochRange(RUN_ID, 1, 1)).thenReturn(new EpochRangeAggregation(
                Map.of(1, List.of(step("core:x", "completed", -50L, null, Map.of("completed", 1)))), Map.of()));

        NodeCell cell = service.analyze(run, null).epochs().get(0).nodes().get(0);

        assertThat(cell.executionTimeMs()).isZero();
        assertThat(cell.elapsedMs()).as("never timed: no figure rather than a zero").isNull();
    }

    @Test
    @DisplayName("a long failure message is cut to the payload bound")
    void longErrorMessageIsTruncated() {
        String longMessage = "x".repeat(1_000);
        window(RunAnalysisService.DEFAULT_LIMIT, epoch(1));
        totalEpochs(1);
        when(stepAggregationService.getAggregatedStepsByEpochRange(RUN_ID, 1, 1)).thenReturn(new EpochRangeAggregation(
                Map.of(1, List.of(step("mcp:fetch", "error", 10L, 10L, Map.of("failed", 1)))),
                Map.of(1, Map.of("mcp:fetch", longMessage))));

        String message = service.analyze(run, null).epochs().get(0).nodes().get(0).errorMessage();

        assertThat(message).hasSize(RunAnalysisService.MAX_ERROR_LENGTH).endsWith("...");
    }

    @Test
    @DisplayName("the requested window is clamped: absent -> default, below 1 -> 1, above the cap -> cap")
    void limitIsClamped() {
        assertThat(RunAnalysisService.clampLimit(null)).isEqualTo(RunAnalysisService.DEFAULT_LIMIT);
        assertThat(RunAnalysisService.clampLimit(0)).isEqualTo(1);
        assertThat(RunAnalysisService.clampLimit(-5)).isEqualTo(1);
        assertThat(RunAnalysisService.clampLimit(25)).isEqualTo(25);
        assertThat(RunAnalysisService.clampLimit(10_000)).isEqualTo(RunAnalysisService.MAX_LIMIT);
    }

    private static EpochTimestampRow epoch(int n) {
        return new EpochTimestampRow(n, "2026-09-26T10:00:00Z", "2026-09-26T10:00:01Z", 1_000L, "COMPLETED");
    }

    private static AggregatedStep step(String alias, String status, long execMs, Long elapsedMs, Map<String, Integer> counts) {
        return new AggregatedStep(alias, status, alias, null, null, counts, execMs, elapsedMs);
    }
}

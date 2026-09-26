package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.AggregatedStepProjection;
import com.apimarketplace.orchestrator.repository.EpochAggregatedStepProjection;
import com.apimarketplace.orchestrator.repository.EpochElapsedProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.StepAggregationService.AggregatedStep;
import com.apimarketplace.orchestrator.services.StepAggregationService.EpochRangeAggregation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StepAggregationService#getAggregatedStepsByEpochRange}: the one-query source of the run
 * analysis grid. The SQL itself (spawn supersession, NULL epochs) is pinned against real Postgres
 * in {@code AggregatedStepsQueryPostgresTest}; this class pins the folding done in Java.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepAggregationService - aggregation over a range of epochs")
class StepAggregationServiceEpochRangeTest {

    private static final String RUN = "run-range";

    @Mock
    private WorkflowStepDataRepository workflowStepDataRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @InjectMocks
    private StepAggregationService service;

    @Test
    @DisplayName("rows are split per epoch, and each node's status is folded exactly as the single-epoch call folds it")
    void splitsPerEpochAndFoldsStatuses() {
        when(workflowStepDataRepository.getAggregatedStepsByRunIdAndEpochRange(RUN, 1, 2)).thenReturn(List.of(
                row(1, "mcp:fetch", "COMPLETED", 2L, 1_000L, null),
                row(1, "mcp:fetch", "FAILED", 1L, 300L, "HTTP 429"),
                row(2, "mcp:fetch", "SKIPPED", 1L, 0L, null)));

        EpochRangeAggregation result = service.getAggregatedStepsByEpochRange(RUN, 1, 2);

        AggregatedStep epoch1 = result.stepsByEpoch().get(1).get(0);
        assertThat(epoch1.status()).isEqualTo("partial_success");
        assertThat(epoch1.statusCounts()).containsExactlyInAnyOrderEntriesOf(Map.of("completed", 2, "failed", 1));
        assertThat(epoch1.totalExecutionTimeMs()).as("summed over the node's rows").isEqualTo(1_300L);
        assertThat(result.stepsByEpoch().get(2).get(0).status()).isEqualTo("skipped");
    }

    @Test
    @DisplayName("a failure message is kept for FAILED rows only, and only the first one per node")
    void keepsOneFailureMessagePerFailedNode() {
        when(workflowStepDataRepository.getAggregatedStepsByRunIdAndEpochRange(RUN, 1, 1)).thenReturn(List.of(
                row(1, "mcp:fetch", "FAILED", 1L, 10L, "first"),
                row(1, "mcp:fetch", "ERROR", 1L, 10L, "second"),
                row(1, "core:ok", "COMPLETED", 1L, 10L, "stale message on a success")));

        EpochRangeAggregation result = service.getAggregatedStepsByEpochRange(RUN, 1, 1);

        assertThat(result.failureMessages().get(1))
                .containsExactly(Map.entry("mcp:fetch", "first"));
    }

    @Test
    @DisplayName("an epoch with no step row is absent, not an empty entry")
    void epochsWithoutRowsAreAbsent() {
        when(workflowStepDataRepository.getAggregatedStepsByRunIdAndEpochRange(RUN, 1, 3)).thenReturn(List.of(
                row(3, "mcp:fetch", "COMPLETED", 1L, 10L, null)));

        EpochRangeAggregation result = service.getAggregatedStepsByEpochRange(RUN, 1, 3);

        assertThat(result.stepsByEpoch()).containsOnlyKeys(3);
        assertThat(result.failureMessages()).isEmpty();
    }

    @Test
    @DisplayName("an inverted range answers nothing without touching the database")
    void invertedRangeIsEmpty() {
        EpochRangeAggregation result = service.getAggregatedStepsByEpochRange(RUN, 5, 4);

        assertThat(result.stepsByEpoch()).isEmpty();
        verify(workflowStepDataRepository, never()).getAggregatedStepsByRunIdAndEpochRange(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("rows missing their epoch or alias are ignored rather than grouped under null")
    void ignoresRowsWithoutEpochOrAlias() {
        when(workflowStepDataRepository.getAggregatedStepsByRunIdAndEpochRange(RUN, 1, 1)).thenReturn(List.of(
                row(null, "mcp:fetch", "COMPLETED", 1L, 10L, null),
                row(1, null, "COMPLETED", 1L, 10L, null)));

        assertThat(service.getAggregatedStepsByEpochRange(RUN, 1, 1).stepsByEpoch()).isEmpty();
    }

    @Test
    @DisplayName("each node gets its elapsed time from the per-iteration span query, apart from the summed work")
    void attachesElapsedPerEpochAndAlias() {
        when(workflowStepDataRepository.getAggregatedStepsByRunIdAndEpochRange(RUN, 1, 2)).thenReturn(List.of(
                row(1, "mcp:fetch", "COMPLETED", 10L, 50_000L, null),
                row(2, "mcp:fetch", "COMPLETED", 1L, 700L, null),
                row(2, "core:never_timed", "COMPLETED", 1L, 0L, null)));
        when(workflowStepDataRepository.getElapsedByRunIdAndEpochRange(RUN, 1, 2)).thenReturn(List.of(
                elapsed(1, "mcp:fetch", 5_000L),
                elapsed(2, "mcp:fetch", 700L),
                elapsed(2, "core:never_timed", null)));

        EpochRangeAggregation result = service.getAggregatedStepsByEpochRange(RUN, 1, 2);

        AggregatedStep split = result.stepsByEpoch().get(1).get(0);
        assertThat(split.elapsedMs()).as("ten parallel items held the epoch 5 s").isEqualTo(5_000L);
        assertThat(split.totalExecutionTimeMs()).as("the work is still the sum").isEqualTo(50_000L);
        assertThat(result.stepsByEpoch().get(2)).filteredOn(s -> s.alias().equals("mcp:fetch"))
                .singleElement().extracting(AggregatedStep::elapsedMs).isEqualTo(700L);
        assertThat(result.stepsByEpoch().get(2)).filteredOn(s -> s.alias().equals("core:never_timed"))
                .singleElement().extracting(AggregatedStep::elapsedMs).isNull();
    }

    @Test
    @DisplayName("the single-epoch aggregation carries the same elapsed time, and the REST map exposes it only when known")
    void singleEpochCarriesElapsedAndTheMapExposesIt() {
        when(workflowRunRepository.existsByRunIdPublic(RUN)).thenReturn(true);
        when(workflowStepDataRepository.getAggregatedStepsByRunIdAndEpoch(RUN, 3)).thenReturn(List.<AggregatedStepProjection>of(
                row(3, "mcp:fetch", "COMPLETED", 10L, 50_000L, null),
                row(3, "core:x", "COMPLETED", 1L, 0L, null)));
        when(workflowStepDataRepository.getElapsedByRunIdAndEpochRange(RUN, 3, 3)).thenReturn(List.of(
                elapsed(3, "mcp:fetch", 5_000L)));

        List<AggregatedStep> steps = service.getAggregatedStepsWithElapsed(RUN, 3).orElseThrow();

        Map<String, Object> fetch = service.toResponseMap(steps.stream().filter(s -> s.alias().equals("mcp:fetch")).findFirst().orElseThrow());
        Map<String, Object> untimed = service.toResponseMap(steps.stream().filter(s -> s.alias().equals("core:x")).findFirst().orElseThrow());
        assertThat(fetch).containsEntry("elapsedMs", 5_000L).containsEntry("executionTimeMs", 50_000L);
        assertThat(untimed).doesNotContainKey("elapsedMs");
    }

    @Test
    @DisplayName("the plain single-epoch aggregation, used internally, never pays for the elapsed query")
    void plainSingleEpochSkipsTheElapsedQuery() {
        when(workflowRunRepository.existsByRunIdPublic(RUN)).thenReturn(true);
        when(workflowStepDataRepository.getAggregatedStepsByRunIdAndEpoch(RUN, 3)).thenReturn(List.<AggregatedStepProjection>of(
                row(3, "mcp:fetch", "COMPLETED", 1L, 1_000L, null)));

        AggregatedStep step = service.getAggregatedSteps(RUN, 3).orElseThrow().get(0);

        assertThat(step.elapsedMs()).isNull();
        verify(workflowStepDataRepository, never()).getElapsedByRunIdAndEpochRange(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("the whole-run aggregation never carries an elapsed time (it would span every epoch)")
    void wholeRunHasNoElapsed() {
        when(workflowRunRepository.existsByRunIdPublic(RUN)).thenReturn(true);
        when(workflowStepDataRepository.getAggregatedStepsByRunId(RUN)).thenReturn(List.<AggregatedStepProjection>of(
                row(1, "mcp:fetch", "COMPLETED", 3L, 3_000L, null)));

        AggregatedStep step = service.getAggregatedSteps(RUN).orElseThrow().get(0);

        assertThat(step.elapsedMs()).isNull();
        verify(workflowStepDataRepository, never()).getElapsedByRunIdAndEpochRange(anyString(), anyInt(), anyInt());
    }

    private static EpochElapsedProjection elapsed(Integer epoch, String alias, Long ms) {
        return new EpochElapsedProjection() {
            @Override public Integer getEpoch() { return epoch; }
            @Override public String getStepAlias() { return alias; }
            @Override public Long getElapsedMs() { return ms; }
        };
    }

    private static EpochAggregatedStepProjection row(Integer epoch, String alias, String status, Long count,
                                                     Long sumMs, String error) {
        return new EpochAggregatedStepProjection() {
            @Override public Integer getEpoch() { return epoch; }
            @Override public String getErrorMessage() { return error; }
            @Override public String getStepAlias() { return alias; }
            @Override public String getStatus() { return status; }
            @Override public Long getCount() { return count; }
            @Override public String getToolId() { return "tool"; }
            @Override public Object getMinStartTime() { return Instant.parse("2026-09-26T10:00:00Z"); }
            @Override public Object getMaxEndTime() { return Instant.parse("2026-09-26T10:00:02Z"); }
            @Override public Long getSumExecutionTimeMs() { return sumMs; }
        };
    }
}

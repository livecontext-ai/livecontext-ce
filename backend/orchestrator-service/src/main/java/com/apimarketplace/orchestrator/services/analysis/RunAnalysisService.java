package com.apimarketplace.orchestrator.services.analysis;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochTimestampRow;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.StepAggregationService.AggregatedStep;
import com.apimarketplace.orchestrator.services.StepAggregationService.EpochRangeAggregation;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds the run analysis view: the last N epochs of a run, each with its outcome, work
 * duration, credit cost and the per-node cells of the nodes x epochs grid.
 *
 * <p>Nothing is computed here that another service already owns. Epoch timing and outcome come
 * from {@link WorkflowEpochService#listLatestEpochTimestamps} (the same figures the Run tab's
 * epoch list shows, so both surfaces agree, but read for the window only), node cells from
 * {@link StepAggregationService#getAggregatedStepsByEpochRange} (the per-epoch aggregation the
 * Run tab uses, spawn supersession included), and cost from the run row's
 * {@code cost_by_epoch}. What this service adds is the windowing and the join.
 */
@Service
public class RunAnalysisService {

    /** Default window when the caller does not ask for one. */
    public static final int DEFAULT_LIMIT = 60;
    /** Hard cap: a once-a-minute schedule reaches ~10k epochs a week. */
    public static final int MAX_LIMIT = 200;
    /** A failure message is a hint in a tooltip, not a log: bounded so one epoch cannot bloat the payload. */
    static final int MAX_ERROR_LENGTH = 300;

    private final WorkflowEpochService workflowEpochService;
    private final StepAggregationService stepAggregationService;

    public RunAnalysisService(WorkflowEpochService workflowEpochService,
                              StepAggregationService stepAggregationService) {
        this.workflowEpochService = workflowEpochService;
        this.stepAggregationService = stepAggregationService;
    }

    /**
     * One node of one epoch. {@code status} uses the aggregated vocabulary (completed, error, skipped...).
     *
     * <p>Two durations on purpose. {@code executionTimeMs} ADDS UP the node's rows, so ten split items
     * running in parallel for 5 s each read 50 s: the work done, not the time it took.
     * {@code elapsedMs} is how long the node held the epoch (per-iteration spans, summed: parallel
     * items count once, loop iterations add up) and is what a timeline must draw; null when no
     * row of the node was timed yet.
     */
    public record NodeCell(String alias, String status, long executionTimeMs, Long elapsedMs,
                           Map<String, Integer> statusCounts, String errorMessage) {}

    /** One epoch of the window, oldest first in {@link RunAnalysis#epochs()}. */
    public record EpochAnalysis(int epoch, String startedAt, String endedAt, Long workDurationMs,
                                String status, Double costCredits, List<NodeCell> nodes) {}

    /**
     * @param totalEpochs every epoch the run has, so the client can say "last 60 of 1 234"
     * @param epochs      the window, oldest first
     */
    public record RunAnalysis(String runId, int totalEpochs, List<EpochAnalysis> epochs) {}

    /** Clamps a requested window to {@code [1, MAX_LIMIT]}, {@link #DEFAULT_LIMIT} when absent. */
    public static int clampLimit(Integer requested) {
        if (requested == null) return DEFAULT_LIMIT;
        return Math.max(1, Math.min(MAX_LIMIT, requested));
    }

    /**
     * The analysis of the {@code limit} most recent epochs of {@code run}. The caller owns the
     * scope check on the run.
     */
    public RunAnalysis analyze(WorkflowRunEntity run, Integer limit) {
        String runId = run.getRunIdPublic();
        List<EpochTimestampRow> rows = new ArrayList<>(workflowEpochService.listLatestEpochTimestamps(runId, clampLimit(limit)));
        if (rows.isEmpty()) return new RunAnalysis(runId, 0, List.of());
        rows.sort(Comparator.comparingInt(EpochTimestampRow::epoch));
        int totalEpochs = Math.toIntExact(Math.max(rows.size(),
                workflowEpochService.countEpochsByRunIds(List.of(runId)).getOrDefault(runId, 0L)));

        EpochRangeAggregation aggregation = stepAggregationService.getAggregatedStepsByEpochRange(
                runId, rows.get(0).epoch(), rows.get(rows.size() - 1).epoch());
        Map<String, Object> costByEpoch = run.getCostByEpoch();

        List<EpochAnalysis> epochs = new ArrayList<>(rows.size());
        for (EpochTimestampRow row : rows) {
            List<AggregatedStep> steps = aggregation.stepsByEpoch().getOrDefault(row.epoch(), List.of());
            Map<String, String> errors = aggregation.failureMessages().getOrDefault(row.epoch(), Map.of());
            List<NodeCell> nodes = new ArrayList<>(steps.size());
            for (AggregatedStep step : steps) {
                nodes.add(new NodeCell(step.alias(), step.status(), Math.max(0, step.totalExecutionTimeMs()),
                        step.elapsedMs(), step.statusCounts(), truncate(errors.get(step.alias()))));
            }
            epochs.add(new EpochAnalysis(row.epoch(), row.startedAt(), row.endedAt(), row.workDurationMs(),
                    row.status(), costOf(costByEpoch, row.epoch()), nodes));
        }
        return new RunAnalysis(runId, totalEpochs, epochs);
    }

    /** {@code cost_by_epoch[epoch]} as a number, null when the epoch has no recorded cost. */
    static Double costOf(Map<String, Object> costByEpoch, int epoch) {
        if (costByEpoch == null) return null;
        Object value = costByEpoch.get(String.valueOf(epoch));
        return value instanceof Number number ? number.doubleValue() : null;
    }

    static String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_LENGTH) return message;
        return message.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }
}

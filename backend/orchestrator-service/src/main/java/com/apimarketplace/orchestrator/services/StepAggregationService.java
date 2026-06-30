package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.AggregatedStepProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for aggregating workflow step data by alias.
 * Uses optimized SQL GROUP BY projection to avoid loading full entities
 * with heavy JSONB columns (inputData, metadata, etc.).
 */
@Service
public class StepAggregationService {

    private static final Logger logger = LoggerFactory.getLogger(StepAggregationService.class);

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private WorkflowStepDataRepository workflowStepDataRepository;

    /**
     * Aggregated step data record.
     */
    public record AggregatedStep(
        String alias,
        String status,
        String toolId,
        Instant startTime,
        Instant endTime,
        Map<String, Integer> statusCounts,
        long totalExecutionTimeMs
    ) {}

    /**
     * Gets aggregated steps for a run, grouped by step alias.
     * Uses an optimized SQL GROUP BY query that returns only counts, toolId, and timing -
     * avoids loading full entities with heavy JSONB columns.
     *
     * @param runId The public run ID
     * @return List of aggregated steps, or empty if run not found
     */
    public Optional<List<AggregatedStep>> getAggregatedSteps(String runId) {
        long startMs = System.currentTimeMillis();
        logger.info("📊 Getting aggregated steps for runId: {}", runId);

        // Verify the run exists
        boolean runExists = workflowRunRepository.existsByRunIdPublic(runId);
        if (!runExists) {
            logger.warn("⚠️ Run not found: {}", runId);
            return Optional.empty();
        }

        // Use optimized GROUP BY query - returns only counts + timing, no JSONB columns
        List<AggregatedStepProjection> projections = workflowStepDataRepository.getAggregatedStepsByRunId(runId);

        if (projections.isEmpty()) {
            logger.info("📊 No steps found for runId: {}, queryTimeMs={}", runId, System.currentTimeMillis() - startMs);
            return Optional.of(List.of());
        }

        // Group projection rows by stepAlias (each alias may have multiple status rows)
        Map<String, List<AggregatedStepProjection>> byAlias = projections.stream()
            .collect(Collectors.groupingBy(AggregatedStepProjection::getStepAlias));

        // Build AggregatedStep for each alias
        List<AggregatedStep> aggregatedSteps = new ArrayList<>();
        for (Map.Entry<String, List<AggregatedStepProjection>> entry : byAlias.entrySet()) {
            String alias = entry.getKey();
            List<AggregatedStepProjection> rows = entry.getValue();
            aggregatedSteps.add(buildAggregatedStep(alias, rows));
        }

        // Sort by start time (oldest first)
        aggregatedSteps.sort((a, b) -> {
            Instant startA = a.startTime();
            Instant startB = b.startTime();
            if (startA == null && startB == null) return 0;
            if (startA == null) return 1;
            if (startB == null) return -1;
            return startA.compareTo(startB);
        });

        long elapsed = System.currentTimeMillis() - startMs;
        logger.info("✅ Returning {} aggregated steps for runId: {}, projectionRows={}, queryTimeMs={}",
            aggregatedSteps.size(), runId, projections.size(), elapsed);
        return Optional.of(aggregatedSteps);
    }

    /**
     * Gets aggregated steps for a run filtered by epoch.
     * Same logic as {@link #getAggregatedSteps(String)} but restricted to a single epoch.
     * Used for per-epoch node timing display in the epoch timeline.
     *
     * @param runId The public run ID
     * @param epoch The epoch to filter by
     * @return List of aggregated steps for the given epoch, or empty if run not found
     */
    public Optional<List<AggregatedStep>> getAggregatedSteps(String runId, int epoch) {
        long startMs = System.currentTimeMillis();
        logger.info("Getting aggregated steps for runId: {}, epoch: {}", runId, epoch);

        boolean runExists = workflowRunRepository.existsByRunIdPublic(runId);
        if (!runExists) {
            logger.warn("Run not found: {}", runId);
            return Optional.empty();
        }

        List<AggregatedStepProjection> projections = workflowStepDataRepository.getAggregatedStepsByRunIdAndEpoch(runId, epoch);

        if (projections.isEmpty()) {
            logger.info("No steps found for runId: {}, epoch: {}, queryTimeMs={}", runId, epoch, System.currentTimeMillis() - startMs);
            return Optional.of(List.of());
        }

        Map<String, List<AggregatedStepProjection>> byAlias = projections.stream()
            .collect(Collectors.groupingBy(AggregatedStepProjection::getStepAlias));

        List<AggregatedStep> aggregatedSteps = new ArrayList<>();
        for (Map.Entry<String, List<AggregatedStepProjection>> entry : byAlias.entrySet()) {
            aggregatedSteps.add(buildAggregatedStep(entry.getKey(), entry.getValue()));
        }

        aggregatedSteps.sort((a, b) -> {
            Instant startA = a.startTime();
            Instant startB = b.startTime();
            if (startA == null && startB == null) return 0;
            if (startA == null) return 1;
            if (startB == null) return -1;
            return startA.compareTo(startB);
        });

        long elapsed = System.currentTimeMillis() - startMs;
        logger.info("Returning {} aggregated steps for runId: {}, epoch: {}, queryTimeMs={}",
            aggregatedSteps.size(), runId, epoch, elapsed);
        return Optional.of(aggregatedSteps);
    }

    /**
     * Builds an AggregatedStep from projection rows for the same alias.
     * Each row represents one (alias, status) combination with count and timing.
     */
    private AggregatedStep buildAggregatedStep(String alias, List<AggregatedStepProjection> rows) {
        int completedCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        int runningCount = 0;
        int awaitingSignalCount = 0;

        String toolId = null;
        Instant minStartTime = null;
        Instant maxEndTime = null;
        long totalExecMs = 0;

        for (AggregatedStepProjection row : rows) {
            String status = row.getStatus();
            long count = row.getCount() != null ? row.getCount() : 0;

            if (status != null) {
                String normalizedStatus = status.toUpperCase();
                switch (normalizedStatus) {
                    case "COMPLETED", "SUCCESS" -> completedCount += (int) count;
                    case "FAILED", "ERROR", "FAILURE" -> failedCount += (int) count;
                    case "SKIPPED", "SKIP" -> skippedCount += (int) count;
                    case "RUNNING", "PENDING" -> runningCount += (int) count;
                    case "AWAITING_SIGNAL" -> awaitingSignalCount += (int) count;
                }
            }

            // Get first non-null toolId
            if (toolId == null && row.getToolId() != null) {
                toolId = row.getToolId();
            }

            // Track earliest start and latest end across all status rows
            Instant rowStart = AggregatedStepProjection.toInstant(row.getMinStartTime());
            if (rowStart != null) {
                if (minStartTime == null || rowStart.isBefore(minStartTime)) {
                    minStartTime = rowStart;
                }
            }
            Instant rowEnd = AggregatedStepProjection.toInstant(row.getMaxEndTime());
            if (rowEnd != null) {
                if (maxEndTime == null || rowEnd.isAfter(maxEndTime)) {
                    maxEndTime = rowEnd;
                }
            }

            // Accumulate sum of individual execution times
            Long sumExec = row.getSumExecutionTimeMs();
            logger.info("[buildAggregatedStep] row: alias={}, status={}, count={}, sumExecMs={}, minStart={}, maxEnd={}",
                row.getStepAlias(), row.getStatus(), row.getCount(), sumExec, rowStart, rowEnd);
            if (sumExec != null) {
                totalExecMs += sumExec;
            }
        }

        String aggregatedStatus = deriveAggregatedStatus(completedCount, failedCount, skippedCount, runningCount, awaitingSignalCount);

        Map<String, Integer> statusCounts = new HashMap<>();
        if (completedCount > 0) statusCounts.put("completed", completedCount);
        if (failedCount > 0) statusCounts.put("failed", failedCount);
        if (skippedCount > 0) statusCounts.put("skipped", skippedCount);
        if (runningCount > 0) statusCounts.put("running", runningCount);
        if (awaitingSignalCount > 0) statusCounts.put("awaitingSignal", awaitingSignalCount);

        return new AggregatedStep(
            alias,
            aggregatedStatus,
            toolId != null ? toolId : alias,
            minStartTime,
            maxEndTime,
            statusCounts,
            totalExecMs
        );
    }

    /**
     * Derives the aggregated status from individual status counts.
     * Logic matches node status derivation (deriveStatusFromCounts):
     *
     * Priority order:
     * 1. RUNNING if there are running items and processed < total
     * 2. SKIPPED if all items were skipped (no success, no failure)
     * 3. Success/Error with skipped: skipped doesn't count, use success/error status
     *    9 skipped + 1 success => status = success (completed)
     *    9 skipped + 1 error => status = error
     * 4. PARTIAL_SUCCESS if there are both successes and failures
     * 5. ERROR if all items failed (no success, no skipped)
     * 6. COMPLETED if all items succeeded (no failures, no skipped)
     * 7. PENDING otherwise
     */
    private String deriveAggregatedStatus(int completedCount, int failedCount, int skippedCount, int runningCount, int awaitingSignalCount) {
        // Awaiting signal takes priority - node is paused waiting for user action
        if (awaitingSignalCount > 0 && completedCount == 0 && failedCount == 0) {
            return "awaiting_signal";
        }

        int processedCount = completedCount + failedCount + skippedCount;
        int totalCount = processedCount + runningCount + awaitingSignalCount;

        // 1. Running: items are currently being processed
        if (runningCount > 0 && (processedCount < totalCount || totalCount == 0)) {
            return "running";
        }

        // 2. Skipped: all items were skipped (no success, no failure)
        if (skippedCount > 0 && completedCount == 0 && failedCount == 0) {
            return "skipped";
        }

        // 3. Success/Error with skipped: skipped doesn't count, use success/error status
        if (skippedCount > 0 && (completedCount > 0 || failedCount > 0)) {
            if (completedCount > 0 && failedCount == 0) {
                return "completed";
            } else if (failedCount > 0 && completedCount == 0) {
                return "error";
            } else {
                // Both success and failure with skipped => partial_success
                return "partial_success";
            }
        }

        // 4. Partial success: mix of success and error
        if (completedCount > 0 && failedCount > 0) {
            return "partial_success";
        }

        // 5. Error: all items failed (no success, no skipped)
        if (failedCount > 0 && completedCount == 0 && skippedCount == 0) {
            return "error";
        }

        // 6. Completed: all items succeeded (no failures, no skipped)
        if (completedCount > 0 && failedCount == 0 && skippedCount == 0) {
            return "completed";
        }

        // 7. Pending: no processing yet
        return "pending";
    }

    /**
     * Converts an AggregatedStep to a Map for JSON response.
     */
    public Map<String, Object> toResponseMap(AggregatedStep step) {
        Map<String, Object> map = new HashMap<>();
        map.put("status", step.status());
        map.put("alias", step.alias());
        map.put("toolId", step.toolId());
        map.put("startTime", step.startTime());
        map.put("endTime", step.endTime());
        map.put("statusCounts", step.statusCounts());

        // Use sum of individual execution times (correct for multi-epoch aggregation)
        long execMs = Math.max(0, step.totalExecutionTimeMs());
        long spanMs = (step.startTime() != null && step.endTime() != null)
            ? step.endTime().toEpochMilli() - step.startTime().toEpochMilli() : -1;
        logger.info("[toResponseMap] alias={}, totalExecMs={}, spanMs={}, startTime={}, endTime={}, statusCounts={}",
            step.alias(), execMs, spanMs, step.startTime(), step.endTime(), step.statusCounts());
        map.put("executionTimeMs", execMs);

        return map;
    }

    /**
     * Converts a list of AggregatedSteps to a list of Maps for JSON response.
     */
    public List<Map<String, Object>> toResponseList(List<AggregatedStep> steps) {
        return steps.stream()
            .map(this::toResponseMap)
            .collect(Collectors.toList());
    }
}

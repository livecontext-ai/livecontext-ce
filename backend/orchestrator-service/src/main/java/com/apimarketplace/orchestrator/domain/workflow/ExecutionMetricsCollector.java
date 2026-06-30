package com.apimarketplace.orchestrator.domain.workflow;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.utils.WorkflowUtils;

/**
 * Collects and manages execution metrics for workflow steps.
 */
public class ExecutionMetricsCollector {

    private final Map<String, ItemMetrics> stepItemMetrics = new ConcurrentHashMap<>();
    private final Map<String, StepMetricsAccumulator> stepMetricsAccumulators = new ConcurrentHashMap<>();
    private final Map<String, Long> stepExecutionTimes = new ConcurrentHashMap<>();

    public void recordStepItemMetrics(String stepId, ItemMetrics metrics) {
        if (stepId == null || metrics == null) return;
        stepItemMetrics.put(WorkflowUtils.normalizeStepId(stepId), metrics);
    }

    public ItemMetrics getStepItemMetrics(String stepId) {
        if (stepId == null) return null;
        return stepItemMetrics.get(WorkflowUtils.normalizeStepId(stepId));
    }

    public StepMetricsAccumulator getOrCreateStepMetricsAccumulator(String stepId) {
        if (stepId == null) throw new IllegalArgumentException("stepId cannot be null");
        return stepMetricsAccumulators.computeIfAbsent(WorkflowUtils.normalizeStepId(stepId), id -> new StepMetricsAccumulator());
    }

    public void recordStepExecutionTime(String stepId, long executionTime) {
        if (stepId != null) stepExecutionTimes.put(stepId, executionTime);
    }

    public long getStepExecutionTime(String stepId) {
        return stepExecutionTimes.getOrDefault(stepId, 0L);
    }

    public Map<String, Long> getStepExecutionTimes() {
        return new HashMap<>(stepExecutionTimes);
    }

    public void clear() {
        stepItemMetrics.clear();
        stepMetricsAccumulators.clear();
        stepExecutionTimes.clear();
    }

    /**
     * Accumulates metrics for step execution including success/failure counts and HTTP status tracking.
     */
    public static final class StepMetricsAccumulator {
        private int running;
        private int success;
        private int failure;
        private int skipped;
        private int processed;
        private int total;
        private final Map<Integer, Integer> httpStatusCounts = new HashMap<>();

        public synchronized void onItemDispatched() {
            running++;
            total++;
        }

        public synchronized void onItemSkippedBeforeDispatch() {
            skipped++;
            processed++;
            total++;
        }

        public synchronized void onItemCompleted(NodeStatus status) {
            if (running > 0) running--;
            processed++;
            if (processed > total) total = processed;
            if (status == null) return;
            switch (status) {
                case COMPLETED -> success++;
                case FAILED -> failure++;
                case SKIPPED -> skipped++;
                default -> {}
            }
        }

        public synchronized void onHttpStatus(Integer statusCode) {
            if (statusCode != null) httpStatusCounts.merge(statusCode, 1, Integer::sum);
        }

        public synchronized ItemMetrics snapshot() {
            return new ItemMetrics(success, failure, skipped, running, processed, total, new HashMap<>(httpStatusCounts));
        }

        public synchronized void resetRunning() {
            running = Math.max(running, 0);
        }
    }

    /**
     * Immutable snapshot of item processing metrics.
     */
    public static final class ItemMetrics {
        private final int success;
        private final int failure;
        private final int skipped;
        private final int running;
        private final int processed;
        private final int total;
        private final Map<Integer, Integer> httpStatusCounts;

        public ItemMetrics(int success, int failure, int skipped, int running, int processed, int total, Map<Integer, Integer> httpStatusCounts) {
            this.success = Math.max(success, 0);
            this.failure = Math.max(failure, 0);
            this.skipped = Math.max(skipped, 0);
            this.running = Math.max(running, 0);
            this.processed = Math.max(processed, 0);
            this.total = Math.max(total, 0);
            this.httpStatusCounts = httpStatusCounts != null ? Map.copyOf(httpStatusCounts) : Map.of();
        }

        public static ItemMetrics single(NodeStatus status) {
            return switch (status) {
                case COMPLETED -> new ItemMetrics(1, 0, 0, 0, 1, 1, Map.of());
                case FAILED -> new ItemMetrics(0, 1, 0, 0, 1, 1, Map.of());
                case SKIPPED -> new ItemMetrics(0, 0, 1, 0, 1, 1, Map.of());
                default -> new ItemMetrics(0, 0, 0, 0, 0, 0, Map.of());
            };
        }

        public static ItemMetrics fromCounts(int success, int failure, int skipped, int processed, int total) {
            return new ItemMetrics(success, failure, skipped, 0, processed, total, Map.of());
        }

        public static ItemMetrics fromCounts(int success, int failure, int skipped, int running, int processed, int total, Map<Integer, Integer> httpStatusCounts) {
            return new ItemMetrics(success, failure, skipped, running, processed, total, httpStatusCounts);
        }

        public int success() { return success; }
        public int failure() { return failure; }
        public int skipped() { return skipped; }
        public int running() { return running; }
        public int processed() { return processed; }
        public int total() { return total; }
        public Map<Integer, Integer> httpStatusCounts() { return httpStatusCounts; }

        public Map<String, Object> toMap() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("completed", success);
            payload.put("failed", failure);
            payload.put("skipped", skipped);
            payload.put("running", running);
            payload.put("processed", processed);
            payload.put("total", total);
            if (!httpStatusCounts.isEmpty()) payload.put("httpStatusCounts", httpStatusCounts);
            return payload;
        }
    }
}

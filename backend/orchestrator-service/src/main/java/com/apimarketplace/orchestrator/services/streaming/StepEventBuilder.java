package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionMetricsCollector;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class StepEventBuilder {

    public Map<String, Object> build(WorkflowExecution execution,
                                     String stepId,
                                     String stepAlias,
                                     String normalizedStepId,
                                     StepExecutionResult eventResult) {
        return build(execution, stepId, stepAlias, normalizedStepId, eventResult, null);
    }

    /**
     * Build step event data with optional DB-sourced statusCounts.
     * When dbStatusCounts is provided, it takes precedence over in-memory metrics.
     * This ensures tight coupling between DB persistence and displayed status.
     */
    public Map<String, Object> build(WorkflowExecution execution,
                                     String stepId,
                                     String stepAlias,
                                     String normalizedStepId,
                                     StepExecutionResult eventResult,
                                     Map<String, Object> dbStatusCounts) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "step_executed");
        eventData.put("runId", execution.getRunId());
        eventData.put("stepId", stepId);
        eventData.put("stepAlias", stepAlias);
        eventData.put("normalizedStepId", normalizedStepId);
        eventData.put("originalStepId", stepAlias);

        String computedStatus = determineCustomStatus(eventResult).orElse(eventResult.status().toWireValue());
        eventData.put("status", computedStatus);
        eventData.put("uiStatus", StreamingEventUtils.mapToUIStatus(computedStatus));
        eventData.put("backendStatus", computedStatus);
        eventData.put("message", eventResult.message());
        eventData.put("executionTime", eventResult.executionTime());
        eventData.put("timestamp", System.currentTimeMillis());

        Map<String, Object> rawOutput = eventResult.output();
        if (rawOutput != null) {
            appendItemMetadata(eventData, rawOutput);
        }

        // Use DB-sourced statusCounts if provided, otherwise fall back to in-memory metrics
        if (dbStatusCounts != null && !dbStatusCounts.isEmpty()) {
            eventData.put("statusCounts", normalizeDbStatusCounts(dbStatusCounts));
        } else {
            eventData.put("statusCounts", resolveStatusCountsWithDefault(execution, stepId, normalizedStepId, eventResult));
        }

        eventData.put("currentLevel", execution.getCurrentLevel());
        eventData.put("executionType", "DAG");

        return eventData;
    }

    /**
     * Normalize DB statusCounts to include all expected fields (running, processed, total)
     */
    private Map<String, Object> normalizeDbStatusCounts(Map<String, Object> dbCounts) {
        Map<String, Object> normalized = new LinkedHashMap<>();

        // Read "completed" with fallback to "success" for backward compatibility
        int completed = toInt(dbCounts.get("completed"));
        if (completed == 0 && dbCounts.containsKey("success")) {
            completed = toInt(dbCounts.get("success"));
        }
        // Read "failed" with fallback to "failure" for backward compatibility
        int failed = toInt(dbCounts.get("failed"));
        if (failed == 0 && dbCounts.containsKey("failure")) {
            failed = toInt(dbCounts.get("failure"));
        }
        int skipped = toInt(dbCounts.get("skipped"));
        int running = toInt(dbCounts.get("running"));
        int processed = completed + failed + skipped;
        int total = processed + running;

        normalized.put("running", running);
        normalized.put("processed", processed);
        normalized.put("total", Math.max(total, processed));
        normalized.put("completed", completed);
        normalized.put("failed", failed);
        normalized.put("skipped", skipped);

        return normalized;
    }

    private Optional<String> determineCustomStatus(StepExecutionResult result) {
        if (result == null || result.output() == null) {
            return Optional.empty();
        }
        Object loopStatus = result.output().get("loopStatus");
        if (loopStatus instanceof String status && !status.isBlank()) {
            return Optional.of(status);
        }
        return Optional.empty();
    }

    private void appendItemMetadata(Map<String, Object> eventData, Map<String, Object> rawOutput) {
        if (eventData == null || rawOutput == null || rawOutput.isEmpty()) {
            return;
        }

        addScalarMetadata(eventData, "itemId", rawOutput.get("itemId"));
        addScalarMetadata(eventData, "triggerId", rawOutput.get("triggerId"));
        addScalarMetadata(eventData, "absoluteIndex", rawOutput.get("absoluteIndex"));
        Object itemIndexValue = rawOutput.containsKey("item_index") ? rawOutput.get("item_index") : rawOutput.get("itemIndex");
        addScalarMetadata(eventData, "itemIndex", itemIndexValue);
        addScalarMetadata(eventData, "tenantId", rawOutput.get("tenantId"));

        // Browser-agent live-view coordinates. Forwarded so the frontend
        // BrowserAgentNode can pass cdp_token/cdp_ws_url/session_id to
        // BrowserLiveCdpPanel and open the WS bridge. The fields are
        // present only on agent:browser_agent step outputs (set by
        // BrowserAgentModule + BrowserAgentNode#buildSuccessOutput); they
        // are no-ops for any other node type.
        addScalarMetadata(eventData, "session_id", rawOutput.get("session_id"));
        addScalarMetadata(eventData, "cdp_token", rawOutput.get("cdp_token"));
        addScalarMetadata(eventData, "cdp_ws_url", rawOutput.get("cdp_ws_url"));
        addScalarMetadata(eventData, "step_index", rawOutput.get("step_index"));
        addScalarMetadata(eventData, "last_action", rawOutput.get("last_action"));
        addScalarMetadata(eventData, "cost_usd", rawOutput.get("cost_usd"));
        addScalarMetadata(eventData, "run_id", rawOutput.get("run_id"));
        addScalarMetadata(eventData, "node_id", rawOutput.get("node_id"));

        // Intentionally skip heavy payloads (queue depths, merged entries, payload bodies) to keep streaming minimal.
    }

    private Map<String, Object> resolveStatusCounts(WorkflowExecution execution,
                                                    String stepId,
                                                    String normalizedStepId) {
        Map<String, Object> counts = Map.of();
        ExecutionMetricsCollector.ItemMetrics metrics = execution.getStepItemMetrics(normalizedStepId);
        if (metrics == null) {
            metrics = execution.getStepItemMetrics(stepId);
        }
        if (metrics != null) {
            Map<String, Object> metricsPayload = metrics.toMap();
            Map<String, Object> normalizedCounts = StreamingEventUtils.canonicalizeStatusCountMap(metricsPayload);
            counts = normalizedCounts != null ? normalizedCounts : metricsPayload;
        }
        Map<String, Object> adjusted = maybeOverrideForLoopCondition(execution, normalizedStepId, counts);
        return adjusted != null ? adjusted : counts;
    }

    /**
     * Resolves status counts for a step, providing default counts based on execution result
     * when no item metrics are available (e.g., step-by-step mode).
     */
    private Map<String, Object> resolveStatusCountsWithDefault(WorkflowExecution execution,
                                                                String stepId,
                                                                String normalizedStepId,
                                                                StepExecutionResult result) {
        Map<String, Object> counts = resolveStatusCounts(execution, stepId, normalizedStepId);

        // If counts are empty, provide default counts based on result status
        if (counts == null || counts.isEmpty()) {
            counts = createDefaultCountsFromResult(result);
        }

        return counts;
    }

    /**
     * Creates default statusCounts from a StepExecutionResult.
     * Used in step-by-step mode where there are no item metrics.
     */
    private Map<String, Object> createDefaultCountsFromResult(StepExecutionResult result) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("running", 0L);
        counts.put("processed", 1L);
        counts.put("total", 1L);

        if (result == null) {
            counts.put("completed", 0L);
            counts.put("failed", 0L);
            counts.put("skipped", 0L);
            return counts;
        }

        switch (result.status()) {
            case COMPLETED -> {
                counts.put("completed", 1L);
                counts.put("failed", 0L);
                counts.put("skipped", 0L);
            }
            case FAILED -> {
                counts.put("completed", 0L);
                counts.put("failed", 1L);
                counts.put("skipped", 0L);
            }
            case SKIPPED -> {
                counts.put("completed", 0L);
                counts.put("failed", 0L);
                counts.put("skipped", 1L);
            }
            default -> {
                counts.put("completed", 0L);
                counts.put("failed", 0L);
                counts.put("skipped", 0L);
            }
        }

        return counts;
    }

    private Map<String, Object> maybeOverrideForLoopCondition(WorkflowExecution execution,
                                                              String normalizedStepId,
                                                              Map<String, Object> baseCounts) {
        if (execution == null || normalizedStepId == null) {
            return null;
        }
        String loopNodeId = extractLoopNodeId(normalizedStepId);
        if (loopNodeId == null) {
            return null;
        }
        // Loop iteration metrics removed (while-loop cleanup) - return base counts
        return baseCounts;
    }

    private String extractLoopNodeId(String normalizedStepId) {
        if (normalizedStepId == null) {
            return null;
        }
        int marker = normalizedStepId.indexOf("::condition_checker");
        if (marker <= 0) {
            return null;
        }
        return normalizedStepId.substring(0, marker);
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str) {
            try {
                return Long.parseLong(str);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private void addScalarMetadata(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || value == null) {
            return;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(key, value);
        }
    }
}

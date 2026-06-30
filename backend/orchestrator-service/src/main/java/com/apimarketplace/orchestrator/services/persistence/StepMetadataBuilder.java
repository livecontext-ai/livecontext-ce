package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.workflow.DecisionEvaluationInfo;
import com.apimarketplace.orchestrator.domain.workflow.ErrorMessageLimits;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Builder for step metadata used in workflow persistence.
 * Centralizes all metadata construction logic for step entities.
 */
@Component
public class StepMetadataBuilder {

    /**
     * Builds complete metadata for a step execution result.
     *
     * @param execution The workflow execution
     * @param stepId The step ID
     * @param graphNodeId The graph node ID
     * @param result The step execution result
     * @param workflowRunId The workflow run ID
     * @return The constructed metadata map
     */
    public Map<String, Object> buildMetadata(WorkflowExecution execution, String stepId,
                                              String graphNodeId, StepExecutionResult result,
                                              UUID workflowRunId) {
        Map<String, Object> metadata = new HashMap<>();

        // Basic step info
        metadata.put("statusValue", result.status().toWireValue());
        metadata.put("statusMessage", result.message());
        metadata.put("stepEventId", stepId);
        metadata.put("resultStepId", result.stepId());
        if (graphNodeId != null) metadata.put("graphNodeId", graphNodeId);

        // Error info - Exception.getMessage() bypasses the StepExecutionResult compact
        // constructor cap, so apply the same bound explicitly. See ErrorMessageLimits.
        if (result.error() != null) {
            metadata.put("errorType", result.error().getClass().getName());
            metadata.put("errorMessage", ErrorMessageLimits.truncate(result.error().getMessage()));
        }

        // Context info
        metadata.put("tenantId", execution.getPlan().getTenantId());
        metadata.put("executionTimeMs", result.executionTime());
        metadata.put("recordedAt", Instant.now().toString());
        metadata.put("workflowRunId", workflowRunId.toString());
        metadata.put("workflowId", execution.getPlan().getId());

        // Enrich with item context
        enrichMetadataWithItemContext(metadata, result.output());

        // Enrich with tool info
        enrichMetadataWithToolInfo(metadata, result.output());

        // Add merge states snapshot
        Map<String, Object> mergeSnapshot = execution.getMergeStatesSnapshot();
        if (mergeSnapshot != null && !mergeSnapshot.isEmpty()) {
            metadata.put("mergeStates", mergeSnapshot);
        }

        // Add decision evaluation info
        DecisionEvaluationInfo decisionEvaluation = findDecisionEvaluation(execution, stepId, graphNodeId, result.stepId());
        if (decisionEvaluation != null) {
            metadata.put("decisionEvaluation", convertDecisionEvaluationToMap(decisionEvaluation));
        }

        // Propagate display name for envelope injection in StepPayloadService
        if (execution.getDisplayName() != null) {
            metadata.put("__displayName__", execution.getDisplayName());
        }

        return metadata;
    }

    /**
     * Finds the decision evaluation info for a step.
     */
    public DecisionEvaluationInfo findDecisionEvaluation(WorkflowExecution execution, String stepId,
                                                          String graphNodeId, String resultStepId) {
        DecisionEvaluationInfo info = execution.getDecisionEvaluation(stepId);
        if (info == null && graphNodeId != null) info = execution.getDecisionEvaluation(graphNodeId);
        if (info == null && resultStepId != null) info = execution.getDecisionEvaluation(resultStepId);
        return info;
    }

    /**
     * Converts a DecisionEvaluationInfo to a Map for storage.
     */
    public Map<String, Object> convertDecisionEvaluationToMap(DecisionEvaluationInfo evaluation) {
        Map<String, Object> map = new HashMap<>();
        map.put("decisionNodeId", evaluation.decisionNodeId());
        map.put("decisionNodeLabel", evaluation.decisionNodeLabel());
        map.put("sourceStepId", evaluation.sourceStepId());
        map.put("selectedBranch", evaluation.selectedBranch());

        List<DecisionEvaluationInfo.ConditionEvaluation> conditions = evaluation.conditions();
        if (conditions != null && !conditions.isEmpty()) {
            List<Map<String, Object>> conditionsList = new ArrayList<>(conditions.size());
            for (DecisionEvaluationInfo.ConditionEvaluation cond : conditions) {
                Map<String, Object> condMap = new HashMap<>();
                condMap.put("type", cond.type());
                condMap.put("originalExpression", cond.originalExpression());
                condMap.put("resolvedExpression", cond.resolvedExpression());
                condMap.put("result", cond.result());
                condMap.put("selected", cond.selected());
                condMap.put("targetBranch", cond.targetBranch());
                if (cond.errorMessage() != null) condMap.put("errorMessage", cond.errorMessage());
                conditionsList.add(condMap);
            }
            map.put("conditions", conditionsList);
        } else {
            map.put("conditions", Collections.emptyList());
        }

        if (evaluation.contextSnapshot() != null && !evaluation.contextSnapshot().isEmpty()) {
            map.put("contextSnapshot", evaluation.contextSnapshot());
        }
        return map;
    }

    private void enrichMetadataWithItemContext(Map<String, Object> target, Map<String, Object> output) {
        if (target == null || output == null) return;

        Object itemId = output.get("itemId");
        if (itemId == null && output.get("payload") instanceof Map<?, ?> payloadMap) {
            itemId = ((Map<?, ?>) payloadMap).get("itemId");
            putIfAbsent(target, "triggerId", ((Map<?, ?>) payloadMap).get("triggerId"));
            putIfAbsent(target, "itemAbsoluteIndex", ((Map<?, ?>) payloadMap).get("absoluteIndex"));
        }
        putIfAbsent(target, "itemId", itemId);
        putIfAbsent(target, "triggerId", output.get("triggerId"));
        putIfAbsent(target, "itemAbsoluteIndex", output.get("absoluteIndex"));
        putIfAbsent(target, "itemTenantId", output.get("tenantId"));
    }

    private void enrichMetadataWithToolInfo(Map<String, Object> target, Map<String, Object> output) {
        if (target == null || output == null) return;

        if (output.get("metadata") instanceof Map<?, ?> toolMetadata) {
            putIfAbsent(target, "iconSlug", ((Map<?, ?>) toolMetadata).get("iconSlug"));
            putIfAbsent(target, "toolName", ((Map<?, ?>) toolMetadata).get("toolName"));
            putIfAbsent(target, "apiName", ((Map<?, ?>) toolMetadata).get("apiName"));
        }
        putIfAbsent(target, "toolId", output.get("tool_id"));
    }

    private void putIfAbsent(Map<String, Object> map, String key, Object value) {
        if (value != null && !map.containsKey(key)) map.put(key, value);
    }
}

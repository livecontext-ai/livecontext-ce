package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;

import java.util.Map;

/**
 * Context object for state reconstruction operations.
 * Encapsulates all parameters needed for reconstructing workflow state.
 */
public record StateReconstructionContext(
    String runId,
    WorkflowRunEntity runEntity,
    WorkflowPlan plan,
    int currentEpoch,
    Map<String, Object> metadata
) {
    /**
     * Creates a context from a run entity.
     */
    public static StateReconstructionContext fromRunEntity(WorkflowRunEntity runEntity, WorkflowPlan plan) {
        int currentEpoch = 0;
        Map<String, Object> metadata = runEntity.getMetadata();
        if (metadata != null && metadata.containsKey("currentEpoch")) {
            Object epochValue = metadata.get("currentEpoch");
            if (epochValue instanceof Number) {
                currentEpoch = ((Number) epochValue).intValue();
            }
        }

        return new StateReconstructionContext(
            runEntity.getRunIdPublic(),
            runEntity,
            plan,
            currentEpoch,
            metadata
        );
    }
}

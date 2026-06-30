package com.apimarketplace.orchestrator.domain.workflow;

/**
 * Represents a step within a Split loop body.
 * Each step in the list is executed for each item in the iteration.
 */
public record SplitStep(
    String stepId,
    String to
) {
    public boolean isValid() {
        return stepId != null && !stepId.isBlank();
    }
}

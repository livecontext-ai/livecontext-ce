package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;

/**
 * Interface for workflow validators.
 * Each implementation validates a specific aspect of the workflow.
 */
public interface WorkflowValidator {

    /**
     * Validate a workflow builder session.
     * Adds errors and warnings to the result.
     *
     * @param session the workflow builder session to validate
     * @param result the validation result to populate
     */
    void validate(WorkflowBuilderSession session, ValidationResult result);
}

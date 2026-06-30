package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.domain.workflow.ValidationError;
import com.apimarketplace.orchestrator.domain.workflow.ValidationResult;
import com.apimarketplace.orchestrator.domain.workflow.ValidationWarning;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Factory for creating workflow API response objects.
 * Centralizes response creation logic to avoid duplication in controllers.
 */
@Component
public class WorkflowResponseFactory {

    /**
     * Creates a success response for workflow execution.
     */
    public WorkflowExecutionResponse createSuccessResponse(WorkflowExecution execution) {
        return new WorkflowExecutionResponse(
            execution.getRunId(),
            execution.getPlan().getId(),
            execution.getStatus().toString(),
            "Workflow started successfully",
            execution.getPlan().getTenantId(),
            execution.getStartTime(),
            execution.getPlan().getMcps().size(),
            true,
            Instant.now()
        );
    }

    /**
     * Creates a failure response for workflow execution.
     */
    public WorkflowExecutionResponse createFailureResponse(String message) {
        return new WorkflowExecutionResponse(
            null,
            null,
            "FAILED",
            message,
            null,
            null,
            0,
            false,
            Instant.now()
        );
    }

    /**
     * Creates a validation response from a ValidationResult.
     */
    public WorkflowValidationResponse createValidationResponse(ValidationResult validation) {
        return new WorkflowValidationResponse(
            validation.isValid(),
            extractErrorMessages(validation.getErrors()),
            extractErrorDetails(validation.getErrors()),
            extractWarningMessages(validation.getWarnings()),
            extractWarningDetails(validation.getWarnings()),
            validation.getComplexityScore(),
            Instant.now()
        );
    }

    /**
     * Creates an error validation response from a ValidationResult.
     */
    public WorkflowValidationResponse createValidationErrorResponse(ValidationResult validation) {
        return new WorkflowValidationResponse(
            false,
            extractErrorMessages(validation.getErrors()),
            extractErrorDetails(validation.getErrors()),
            extractWarningMessages(validation.getWarnings()),
            extractWarningDetails(validation.getWarnings()),
            validation.getComplexityScore(),
            Instant.now()
        );
    }

    /**
     * Extracts error messages from a list of ValidationErrors.
     */
    public List<String> extractErrorMessages(List<ValidationError> errors) {
        return extractMessages(errors, ValidationError::message);
    }

    /**
     * Extracts error details from a list of ValidationErrors.
     */
    public List<WorkflowValidationResponse.ValidationErrorDetail> extractErrorDetails(List<ValidationError> errors) {
        if (errors == null || errors.isEmpty()) {
            return List.of();
        }
        return errors.stream()
            .map(error -> new WorkflowValidationResponse.ValidationErrorDetail(
                error.type(),
                error.message(),
                error.path(),
                error.context()
            ))
            .collect(Collectors.toList());
    }

    /**
     * Extracts warning messages from a list of ValidationWarnings.
     */
    public List<String> extractWarningMessages(List<ValidationWarning> warnings) {
        return extractMessages(warnings, ValidationWarning::message);
    }

    /**
     * Extracts warning details from a list of ValidationWarnings.
     */
    public List<WorkflowValidationResponse.ValidationWarningDetail> extractWarningDetails(List<ValidationWarning> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return List.of();
        }
        return warnings.stream()
            .map(warning -> new WorkflowValidationResponse.ValidationWarningDetail(
                warning.type(),
                warning.message(),
                warning.path(),
                warning.context()
            ))
            .collect(Collectors.toList());
    }

    /**
     * Generic method to extract messages from a list of items.
     */
    private <T> List<String> extractMessages(List<T> items, Function<T, String> mapper) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream().map(mapper).collect(Collectors.toList());
    }
}

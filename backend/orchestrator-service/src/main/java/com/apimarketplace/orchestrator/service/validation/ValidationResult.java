package com.apimarketplace.orchestrator.service.validation;

import java.util.List;

/**
 * Result of node parameter validation.
 */
public record ValidationResult(
    boolean valid,
    List<ValidationError> errors,
    List<String> suggestions
) {

    /**
     * Create a valid result with no errors.
     */
    public static ValidationResult success() {
        return new ValidationResult(true, List.of(), List.of());
    }

    /**
     * Create an invalid result with errors and suggestions.
     */
    public static ValidationResult invalid(List<ValidationError> errors, List<String> suggestions) {
        return new ValidationResult(false, errors, suggestions);
    }
}

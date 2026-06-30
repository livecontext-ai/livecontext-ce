package com.apimarketplace.agent.tools.validation;

import com.apimarketplace.agent.tools.ToolErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Result of parameter validation.
 */
public record ValidationResult(
    boolean valid,
    List<ValidationError> errors
) {
    
    public static ValidationResult success() {
        return new ValidationResult(true, List.of());
    }
    
    public static ValidationResult failure(List<ValidationError> errors) {
        return new ValidationResult(false, errors);
    }
    
    public static ValidationResult failure(ValidationError error) {
        return new ValidationResult(false, List.of(error));
    }
    
    /**
     * Check if validation passed.
     */
    public boolean isValid() {
        return valid;
    }
    
    /**
     * Format all errors as a single string.
     */
    public String formatErrors() {
        if (errors == null || errors.isEmpty()) {
            return "";
        }
        return errors.stream()
            .map(ValidationError::message)
            .collect(Collectors.joining("; "));
    }
    
    /**
     * Get the primary error code (first error's code).
     */
    public ToolErrorCode getPrimaryErrorCode() {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        return errors.get(0).errorCode();
    }
    
    /**
     * Builder for constructing validation results.
     */
    public static class Builder {
        private final List<ValidationError> errors = new ArrayList<>();
        
        public Builder addError(String parameterName, String message, ToolErrorCode code) {
            errors.add(new ValidationError(parameterName, message, code));
            return this;
        }
        
        public Builder addMissingParameter(String parameterName) {
            errors.add(new ValidationError(
                parameterName,
                "Required parameter '" + parameterName + "' is missing",
                ToolErrorCode.MISSING_PARAMETER
            ));
            return this;
        }
        
        public Builder addInvalidType(String parameterName, String expectedType, String actualType) {
            errors.add(new ValidationError(
                parameterName,
                "Parameter '" + parameterName + "' expected type " + expectedType + " but got " + actualType,
                ToolErrorCode.INVALID_PARAMETER_TYPE
            ));
            return this;
        }
        
        public Builder addInvalidValue(String parameterName, String message) {
            errors.add(new ValidationError(
                parameterName,
                message,
                ToolErrorCode.INVALID_PARAMETER_VALUE
            ));
            return this;
        }
        
        public Builder addInvalidEnumValue(String parameterName, Object value, List<String> allowedValues) {
            errors.add(new ValidationError(
                parameterName,
                "Parameter '" + parameterName + "' value '" + value + "' is not one of allowed values: " + allowedValues,
                ToolErrorCode.INVALID_ENUM_VALUE
            ));
            return this;
        }
        
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
        
        public ValidationResult build() {
            if (errors.isEmpty()) {
                return ValidationResult.success();
            }
            return ValidationResult.failure(new ArrayList<>(errors));
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Individual validation error.
     */
    public record ValidationError(
        String parameterName,
        String message,
        ToolErrorCode errorCode
    ) {}
}

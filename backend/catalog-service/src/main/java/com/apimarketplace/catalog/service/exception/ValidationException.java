package com.apimarketplace.catalog.service.exception;

/**
 * Exception thrown when validation fails.
 */
public class ValidationException extends CatalogServiceException {

    public ValidationException(String message) {
        super(message, "VALIDATION_ERROR");
    }

    public ValidationException(String message, Throwable cause) {
        super(message, "VALIDATION_ERROR", cause);
    }

    public static ValidationException requiredField(String fieldName) {
        return new ValidationException("Required field missing: " + fieldName);
    }

    public static ValidationException invalidFormat(String fieldName, String expected) {
        return new ValidationException(
            String.format("Invalid format for field '%s'. Expected: %s", fieldName, expected));
    }
}

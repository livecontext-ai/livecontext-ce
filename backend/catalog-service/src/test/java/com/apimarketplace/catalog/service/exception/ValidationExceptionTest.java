package com.apimarketplace.catalog.service.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ValidationException.
 *
 * ValidationException is thrown when validation fails.
 */
@DisplayName("ValidationException")
class ValidationExceptionTest {

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should create exception with message only")
        void shouldCreateExceptionWithMessageOnly() {
            ValidationException exception = new ValidationException("Validation failed");

            assertEquals("Validation failed", exception.getMessage());
            assertEquals("VALIDATION_ERROR", exception.getErrorCode());
            assertNull(exception.getCause());
        }

        @Test
        @DisplayName("should create exception with message and cause")
        void shouldCreateExceptionWithMessageAndCause() {
            RuntimeException cause = new RuntimeException("Parse error");
            ValidationException exception = new ValidationException("Invalid input", cause);

            assertEquals("Invalid input", exception.getMessage());
            assertEquals("VALIDATION_ERROR", exception.getErrorCode());
            assertEquals(cause, exception.getCause());
        }
    }

    // ========================================================================
    // Factory method tests - requiredField
    // ========================================================================

    @Nested
    @DisplayName("requiredField()")
    class RequiredFieldTests {

        @Test
        @DisplayName("should create exception for required field")
        void shouldCreateExceptionForRequiredField() {
            ValidationException exception = ValidationException.requiredField("name");

            assertTrue(exception.getMessage().contains("name"));
            assertTrue(exception.getMessage().toLowerCase().contains("required"));
            assertEquals("VALIDATION_ERROR", exception.getErrorCode());
        }
    }

    // ========================================================================
    // Factory method tests - invalidFormat
    // ========================================================================

    @Nested
    @DisplayName("invalidFormat()")
    class InvalidFormatTests {

        @Test
        @DisplayName("should create exception for invalid format")
        void shouldCreateExceptionForInvalidFormat() {
            ValidationException exception = ValidationException.invalidFormat("email", "user@example.com");

            assertTrue(exception.getMessage().contains("email"));
            assertTrue(exception.getMessage().contains("user@example.com"));
            assertEquals("VALIDATION_ERROR", exception.getErrorCode());
        }

        @Test
        @DisplayName("should include field name and expected format")
        void shouldIncludeFieldNameAndExpectedFormat() {
            ValidationException exception = ValidationException.invalidFormat("date", "YYYY-MM-DD");

            String message = exception.getMessage();
            assertTrue(message.contains("date"));
            assertTrue(message.contains("YYYY-MM-DD"));
        }
    }

    // ========================================================================
    // Inheritance tests
    // ========================================================================

    @Nested
    @DisplayName("Inheritance")
    class InheritanceTests {

        @Test
        @DisplayName("should extend CatalogServiceException")
        void shouldExtendCatalogServiceException() {
            ValidationException exception = new ValidationException("test");

            assertTrue(exception instanceof CatalogServiceException);
        }
    }
}

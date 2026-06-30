package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValidationResult")
class ValidationResultTest {

    @Nested
    @DisplayName("Constructor and getters")
    class ConstructorTests {

        @Test
        @DisplayName("Should store all fields correctly")
        void shouldStoreAllFieldsCorrectly() {
            List<ValidationError> errors = List.of(new ValidationError("ERR", "msg", "/path", Map.of()));
            List<ValidationWarning> warnings = List.of(new ValidationWarning("WARN", "wmsg", "/wpath", Map.of()));

            ValidationResult result = new ValidationResult(false, errors, warnings, 42);

            assertFalse(result.isValid());
            assertEquals(errors, result.getErrors());
            assertEquals(warnings, result.getWarnings());
            assertEquals(42, result.getComplexityScore());
        }

        @Test
        @DisplayName("Should handle null errors and warnings")
        void shouldHandleNullErrorsAndWarnings() {
            ValidationResult result = new ValidationResult(true, null, null, 0);

            assertTrue(result.isValid());
            assertNull(result.getErrors());
            assertNull(result.getWarnings());
        }
    }

    @Nested
    @DisplayName("hasErrors()")
    class HasErrorsTests {

        @Test
        @DisplayName("Should return true when errors exist")
        void shouldReturnTrueWhenErrorsExist() {
            List<ValidationError> errors = List.of(new ValidationError("ERR", "msg", "/path", Map.of()));
            ValidationResult result = new ValidationResult(false, errors, List.of(), 0);
            assertTrue(result.hasErrors());
        }

        @Test
        @DisplayName("Should return false when errors is empty")
        void shouldReturnFalseWhenErrorsIsEmpty() {
            ValidationResult result = new ValidationResult(true, List.of(), List.of(), 0);
            assertFalse(result.hasErrors());
        }

        @Test
        @DisplayName("Should return false when errors is null")
        void shouldReturnFalseWhenErrorsIsNull() {
            ValidationResult result = new ValidationResult(true, null, List.of(), 0);
            assertFalse(result.hasErrors());
        }
    }

    @Nested
    @DisplayName("hasWarnings()")
    class HasWarningsTests {

        @Test
        @DisplayName("Should return true when warnings exist")
        void shouldReturnTrueWhenWarningsExist() {
            List<ValidationWarning> warnings = List.of(new ValidationWarning("WARN", "msg", "/path", Map.of()));
            ValidationResult result = new ValidationResult(true, List.of(), warnings, 0);
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("Should return false when warnings is empty")
        void shouldReturnFalseWhenWarningsIsEmpty() {
            ValidationResult result = new ValidationResult(true, List.of(), List.of(), 0);
            assertFalse(result.hasWarnings());
        }

        @Test
        @DisplayName("Should return false when warnings is null")
        void shouldReturnFalseWhenWarningsIsNull() {
            ValidationResult result = new ValidationResult(true, List.of(), null, 0);
            assertFalse(result.hasWarnings());
        }
    }
}

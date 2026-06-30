package com.apimarketplace.orchestrator.service.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ValidationResult record.
 */
@DisplayName("ValidationResult")
class ValidationResultTest {

    @Nested
    @DisplayName("success factory")
    class SuccessTests {

        @Test
        @DisplayName("Should create valid result with empty errors and suggestions")
        void shouldCreateValidResult() {
            ValidationResult result = ValidationResult.success();

            assertThat(result.valid()).isTrue();
            assertThat(result.errors()).isEmpty();
            assertThat(result.suggestions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("invalid factory")
    class InvalidTests {

        @Test
        @DisplayName("Should create invalid result with errors and suggestions")
        void shouldCreateInvalidResult() {
            List<ValidationError> errors = List.of(
                new ValidationError("prompt", "REQUIRED", "Prompt is required"),
                new ValidationError("model", "INVALID", "Unknown model")
            );
            List<String> suggestions = List.of("Add a prompt parameter", "Use a supported model");

            ValidationResult result = ValidationResult.invalid(errors, suggestions);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).hasSize(2);
            assertThat(result.errors().get(0).parameter()).isEqualTo("prompt");
            assertThat(result.errors().get(0).code()).isEqualTo("REQUIRED");
            assertThat(result.errors().get(0).message()).isEqualTo("Prompt is required");
            assertThat(result.errors().get(1).parameter()).isEqualTo("model");
            assertThat(result.suggestions()).hasSize(2);
        }

        @Test
        @DisplayName("Should handle empty errors and suggestions")
        void shouldHandleEmptyLists() {
            ValidationResult result = ValidationResult.invalid(List.of(), List.of());

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).isEmpty();
            assertThat(result.suggestions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("ValidationError record")
    class ValidationErrorTests {

        @Test
        @DisplayName("Should store all fields correctly")
        void shouldStoreAllFields() {
            ValidationError error = new ValidationError("temperature", "OUT_OF_RANGE", "Must be 0-2");

            assertThat(error.parameter()).isEqualTo("temperature");
            assertThat(error.code()).isEqualTo("OUT_OF_RANGE");
            assertThat(error.message()).isEqualTo("Must be 0-2");
        }
    }
}

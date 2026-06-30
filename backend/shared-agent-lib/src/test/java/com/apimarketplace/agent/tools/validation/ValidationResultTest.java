package com.apimarketplace.agent.tools.validation;

import com.apimarketplace.agent.tools.ToolErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ValidationResult record and its Builder.
 */
@DisplayName("ValidationResult")
class ValidationResultTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("success() should create valid result")
        void successShouldCreateValid() {
            ValidationResult result = ValidationResult.success();

            assertThat(result.isValid()).isTrue();
            assertThat(result.valid()).isTrue();
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("failure(List) should create invalid result with errors")
        void failureWithListShouldCreate() {
            ValidationResult.ValidationError error = new ValidationResult.ValidationError(
                    "query", "Required parameter 'query' is missing", ToolErrorCode.MISSING_PARAMETER
            );

            ValidationResult result = ValidationResult.failure(List.of(error));

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0).parameterName()).isEqualTo("query");
        }

        @Test
        @DisplayName("failure(ValidationError) should create invalid result with single error")
        void failureWithSingleErrorShouldCreate() {
            ValidationResult.ValidationError error = new ValidationResult.ValidationError(
                    "limit", "Invalid value", ToolErrorCode.INVALID_PARAMETER_VALUE
            );

            ValidationResult result = ValidationResult.failure(error);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("formatErrors()")
    class FormatErrorsTests {

        @Test
        @DisplayName("should format single error")
        void shouldFormatSingleError() {
            ValidationResult result = ValidationResult.failure(
                    new ValidationResult.ValidationError("q", "Missing query", ToolErrorCode.MISSING_PARAMETER)
            );

            assertThat(result.formatErrors()).isEqualTo("Missing query");
        }

        @Test
        @DisplayName("should format multiple errors joined by semicolon")
        void shouldFormatMultipleErrors() {
            List<ValidationResult.ValidationError> errors = List.of(
                    new ValidationResult.ValidationError("a", "Error A", ToolErrorCode.MISSING_PARAMETER),
                    new ValidationResult.ValidationError("b", "Error B", ToolErrorCode.INVALID_PARAMETER_TYPE)
            );

            ValidationResult result = ValidationResult.failure(errors);

            assertThat(result.formatErrors()).isEqualTo("Error A; Error B");
        }

        @Test
        @DisplayName("should return empty string for no errors")
        void shouldReturnEmptyForNoErrors() {
            ValidationResult result = ValidationResult.success();
            assertThat(result.formatErrors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getPrimaryErrorCode()")
    class PrimaryErrorCodeTests {

        @Test
        @DisplayName("should return first error code")
        void shouldReturnFirstErrorCode() {
            List<ValidationResult.ValidationError> errors = List.of(
                    new ValidationResult.ValidationError("a", "err", ToolErrorCode.MISSING_PARAMETER),
                    new ValidationResult.ValidationError("b", "err", ToolErrorCode.INVALID_PARAMETER_TYPE)
            );

            ValidationResult result = ValidationResult.failure(errors);

            assertThat(result.getPrimaryErrorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("should return null for no errors")
        void shouldReturnNullForNoErrors() {
            assertThat(ValidationResult.success().getPrimaryErrorCode()).isNull();
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build success when no errors added")
        void shouldBuildSuccessWhenNoErrors() {
            ValidationResult result = ValidationResult.builder().build();

            assertThat(result.isValid()).isTrue();
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("should build failure with errors")
        void shouldBuildFailureWithErrors() {
            ValidationResult result = ValidationResult.builder()
                    .addError("field", "Error message", ToolErrorCode.VALIDATION_ERROR)
                    .build();

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).hasSize(1);
        }

        @Test
        @DisplayName("addMissingParameter should add MISSING_PARAMETER error")
        void addMissingParameterShouldWork() {
            ValidationResult result = ValidationResult.builder()
                    .addMissingParameter("query")
                    .build();

            assertThat(result.errors().get(0).errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(result.errors().get(0).message()).contains("query");
        }

        @Test
        @DisplayName("addInvalidType should add INVALID_PARAMETER_TYPE error")
        void addInvalidTypeShouldWork() {
            ValidationResult result = ValidationResult.builder()
                    .addInvalidType("limit", "integer", "string")
                    .build();

            assertThat(result.errors().get(0).errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_TYPE);
            assertThat(result.errors().get(0).message()).contains("integer").contains("string");
        }

        @Test
        @DisplayName("addInvalidValue should add INVALID_PARAMETER_VALUE error")
        void addInvalidValueShouldWork() {
            ValidationResult result = ValidationResult.builder()
                    .addInvalidValue("count", "Must be positive")
                    .build();

            assertThat(result.errors().get(0).errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        }

        @Test
        @DisplayName("addInvalidEnumValue should add INVALID_ENUM_VALUE error")
        void addInvalidEnumValueShouldWork() {
            ValidationResult result = ValidationResult.builder()
                    .addInvalidEnumValue("action", "delete", List.of("init", "save", "load"))
                    .build();

            assertThat(result.errors().get(0).errorCode()).isEqualTo(ToolErrorCode.INVALID_ENUM_VALUE);
            assertThat(result.errors().get(0).message()).contains("delete").contains("init");
        }

        @Test
        @DisplayName("hasErrors should track error presence")
        void hasErrorsShouldTrack() {
            ValidationResult.Builder builder = ValidationResult.builder();
            assertThat(builder.hasErrors()).isFalse();

            builder.addMissingParameter("x");
            assertThat(builder.hasErrors()).isTrue();
        }

        @Test
        @DisplayName("should accumulate multiple errors")
        void shouldAccumulateErrors() {
            ValidationResult result = ValidationResult.builder()
                    .addMissingParameter("param1")
                    .addMissingParameter("param2")
                    .addInvalidType("param3", "string", "number")
                    .build();

            assertThat(result.errors()).hasSize(3);
            assertThat(result.isValid()).isFalse();
        }
    }
}

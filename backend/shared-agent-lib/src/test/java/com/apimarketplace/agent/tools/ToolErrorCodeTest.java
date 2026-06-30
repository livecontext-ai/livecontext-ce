package com.apimarketplace.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ToolErrorCode enum.
 */
@DisplayName("ToolErrorCode")
class ToolErrorCodeTest {

    @ParameterizedTest
    @EnumSource(ToolErrorCode.class)
    @DisplayName("all error codes should have non-null code and message")
    void allCodesShouldHaveFields(ToolErrorCode errorCode) {
        assertThat(errorCode.getCode()).isNotNull().startsWith("TOOL_");
        assertThat(errorCode.getDefaultMessage()).isNotNull().isNotBlank();
    }

    @Nested
    @DisplayName("format()")
    class FormatTests {

        @Test
        @DisplayName("should format with custom message")
        void shouldFormatWithCustomMessage() {
            String formatted = ToolErrorCode.TOOL_NOT_FOUND.format("Tool 'xyz' not found");
            assertThat(formatted).isEqualTo("TOOL_001: Tool 'xyz' not found");
        }

        @Test
        @DisplayName("should use default message when custom is null")
        void shouldUseDefaultWhenNull() {
            String formatted = ToolErrorCode.TOOL_NOT_FOUND.format(null);
            assertThat(formatted).isEqualTo("TOOL_001: Tool not found");
        }
    }

    @Nested
    @DisplayName("fromCode()")
    class FromCodeTests {

        @Test
        @DisplayName("should find error code by code string")
        void shouldFindByCode() {
            assertThat(ToolErrorCode.fromCode("TOOL_001")).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
            assertThat(ToolErrorCode.fromCode("TOOL_010")).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(ToolErrorCode.fromCode("TOOL_050")).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        }

        @Test
        @DisplayName("should return null for unknown code")
        void shouldReturnNullForUnknown() {
            assertThat(ToolErrorCode.fromCode("TOOL_999")).isNull();
        }

        @Test
        @DisplayName("should return null for null code")
        void shouldReturnNullForNull() {
            assertThat(ToolErrorCode.fromCode(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should format as CODE - message")
        void shouldFormatCorrectly() {
            assertThat(ToolErrorCode.TIMEOUT.toString()).isEqualTo("TOOL_051 - Execution timeout");
        }
    }

    @Nested
    @DisplayName("Error code ranges")
    class ErrorCodeRangeTests {

        @Test
        @DisplayName("discovery errors should be in 001-009 range")
        void discoveryErrorsRange() {
            assertThat(ToolErrorCode.TOOL_NOT_FOUND.getCode()).isEqualTo("TOOL_001");
            assertThat(ToolErrorCode.TOOL_DISABLED.getCode()).isEqualTo("TOOL_002");
        }

        @Test
        @DisplayName("validation errors should be in 010-029 range")
        void validationErrorsRange() {
            assertThat(ToolErrorCode.VALIDATION_ERROR.getCode()).isEqualTo("TOOL_010");
            assertThat(ToolErrorCode.MISSING_PARAMETER.getCode()).isEqualTo("TOOL_011");
        }

        @Test
        @DisplayName("auth errors should be in 030-039 range")
        void authErrorsRange() {
            assertThat(ToolErrorCode.AUTHENTICATION_REQUIRED.getCode()).isEqualTo("TOOL_030");
            assertThat(ToolErrorCode.PERMISSION_DENIED.getCode()).isEqualTo("TOOL_031");
        }

        @Test
        @DisplayName("execution errors should be in 050-069 range")
        void executionErrorsRange() {
            assertThat(ToolErrorCode.EXECUTION_FAILED.getCode()).isEqualTo("TOOL_050");
            assertThat(ToolErrorCode.TIMEOUT.getCode()).isEqualTo("TOOL_051");
        }
    }
}

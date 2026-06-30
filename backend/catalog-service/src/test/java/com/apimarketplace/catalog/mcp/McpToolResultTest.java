package com.apimarketplace.catalog.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for McpToolResult.
 *
 * McpToolResult represents the result of an MCP tool call.
 */
@DisplayName("McpToolResult")
class McpToolResultTest {

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Default constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create result with default values")
        void shouldCreateResultWithDefaultValues() {
            McpToolResult result = new McpToolResult();

            assertFalse(result.isSuccess());
            assertNull(result.getData());
            assertNull(result.getError());
            assertNull(result.getToolName());
            assertNull(result.getExecutionTimeMs());
            assertNull(result.getResultType());
        }
    }

    // ========================================================================
    // Factory method tests - success
    // ========================================================================

    @Nested
    @DisplayName("success()")
    class SuccessTests {

        @Test
        @DisplayName("should create success result with data")
        void shouldCreateSuccessResultWithData() {
            Object data = Map.of("key", "value");
            McpToolResult result = McpToolResult.success(data);

            assertTrue(result.isSuccess());
            assertEquals(data, result.getData());
            assertNull(result.getError());
        }

        @Test
        @DisplayName("should create success result with null data")
        void shouldCreateSuccessResultWithNullData() {
            McpToolResult result = McpToolResult.success(null);

            assertTrue(result.isSuccess());
            assertNull(result.getData());
        }

        @Test
        @DisplayName("should create success result with metadata")
        void shouldCreateSuccessResultWithMetadata() {
            Object data = "test data";
            McpToolResult result = McpToolResult.success(data, "weather", 150L);

            assertTrue(result.isSuccess());
            assertEquals(data, result.getData());
            assertEquals("weather", result.getToolName());
            assertEquals(150L, result.getExecutionTimeMs());
            assertEquals("text", result.getResultType());
        }

        @Test
        @DisplayName("should determine result type for different data types")
        void shouldDetermineResultTypeForDifferentDataTypes() {
            McpToolResult textResult = McpToolResult.success("hello", "tool", 100L);
            assertEquals("text", textResult.getResultType());

            McpToolResult numberResult = McpToolResult.success(42, "tool", 100L);
            assertEquals("number", numberResult.getResultType());

            McpToolResult boolResult = McpToolResult.success(true, "tool", 100L);
            assertEquals("boolean", boolResult.getResultType());

            McpToolResult listResult = McpToolResult.success(List.of(1, 2), "tool", 100L);
            assertEquals("array", listResult.getResultType());

            McpToolResult mapResult = McpToolResult.success(Map.of("k", "v"), "tool", 100L);
            assertEquals("object", mapResult.getResultType());

            McpToolResult nullResult = McpToolResult.success(null, "tool", 100L);
            assertEquals("null", nullResult.getResultType());
        }
    }

    // ========================================================================
    // Factory method tests - error
    // ========================================================================

    @Nested
    @DisplayName("error()")
    class ErrorTests {

        @Test
        @DisplayName("should create error result")
        void shouldCreateErrorResult() {
            McpToolResult result = McpToolResult.error("Connection failed");

            assertFalse(result.isSuccess());
            assertNull(result.getData());
            assertEquals("Connection failed", result.getError());
        }

        @Test
        @DisplayName("should create error result with metadata")
        void shouldCreateErrorResultWithMetadata() {
            McpToolResult result = McpToolResult.error("Timeout", "api-call", 5000L);

            assertFalse(result.isSuccess());
            assertEquals("Timeout", result.getError());
            assertEquals("api-call", result.getToolName());
            assertEquals(5000L, result.getExecutionTimeMs());
        }
    }

    // ========================================================================
    // hasData tests
    // ========================================================================

    @Nested
    @DisplayName("hasData()")
    class HasDataTests {

        @Test
        @DisplayName("should return true when data is present")
        void shouldReturnTrueWhenDataIsPresent() {
            McpToolResult result = McpToolResult.success("data");

            assertTrue(result.hasData());
        }

        @Test
        @DisplayName("should return false when data is null")
        void shouldReturnFalseWhenDataIsNull() {
            McpToolResult result = McpToolResult.success(null);

            assertFalse(result.hasData());
        }
    }

    // ========================================================================
    // getDataAsString tests
    // ========================================================================

    @Nested
    @DisplayName("getDataAsString()")
    class GetDataAsStringTests {

        @Test
        @DisplayName("should return null when data is null")
        void shouldReturnNullWhenDataIsNull() {
            McpToolResult result = McpToolResult.success(null);

            assertNull(result.getDataAsString());
        }

        @Test
        @DisplayName("should return string representation of data")
        void shouldReturnStringRepresentationOfData() {
            McpToolResult result = McpToolResult.success("test string");

            assertEquals("test string", result.getDataAsString());
        }

        @Test
        @DisplayName("should convert number to string")
        void shouldConvertNumberToString() {
            McpToolResult result = McpToolResult.success(42);

            assertEquals("42", result.getDataAsString());
        }
    }

    // ========================================================================
    // isTextResult tests
    // ========================================================================

    @Nested
    @DisplayName("isTextResult()")
    class IsTextResultTests {

        @Test
        @DisplayName("should return true for string data")
        void shouldReturnTrueForStringData() {
            McpToolResult result = McpToolResult.success("text");

            assertTrue(result.isTextResult());
        }

        @Test
        @DisplayName("should return true when resultType is 'text'")
        void shouldReturnTrueWhenResultTypeIsText() {
            McpToolResult result = McpToolResult.success("data", "tool", 100L);

            assertTrue(result.isTextResult());
        }

        @Test
        @DisplayName("should return false for non-text data")
        void shouldReturnFalseForNonTextData() {
            McpToolResult result = McpToolResult.success(123);

            assertFalse(result.isTextResult());
        }
    }

    // ========================================================================
    // isJsonResult tests
    // ========================================================================

    @Nested
    @DisplayName("isJsonResult()")
    class IsJsonResultTests {

        @Test
        @DisplayName("should return true for map data")
        void shouldReturnTrueForMapData() {
            McpToolResult result = McpToolResult.success(Map.of("key", "value"));

            assertTrue(result.isJsonResult());
        }

        @Test
        @DisplayName("should return true for list data")
        void shouldReturnTrueForListData() {
            McpToolResult result = McpToolResult.success(List.of(1, 2, 3));

            assertTrue(result.isJsonResult());
        }

        @Test
        @DisplayName("should return false for string data")
        void shouldReturnFalseForStringData() {
            McpToolResult result = McpToolResult.success("text");

            assertFalse(result.isJsonResult());
        }

        @Test
        @DisplayName("should return false for null data")
        void shouldReturnFalseForNullData() {
            McpToolResult result = McpToolResult.success(null);

            assertFalse(result.isJsonResult());
        }
    }

    // ========================================================================
    // isNumericResult tests
    // ========================================================================

    @Nested
    @DisplayName("isNumericResult()")
    class IsNumericResultTests {

        @Test
        @DisplayName("should return true for integer data")
        void shouldReturnTrueForIntegerData() {
            McpToolResult result = McpToolResult.success(42);

            assertTrue(result.isNumericResult());
        }

        @Test
        @DisplayName("should return true for double data")
        void shouldReturnTrueForDoubleData() {
            McpToolResult result = McpToolResult.success(3.14);

            assertTrue(result.isNumericResult());
        }

        @Test
        @DisplayName("should return false for string data")
        void shouldReturnFalseForStringData() {
            McpToolResult result = McpToolResult.success("42");

            assertFalse(result.isNumericResult());
        }
    }

    // ========================================================================
    // isBooleanResult tests
    // ========================================================================

    @Nested
    @DisplayName("isBooleanResult()")
    class IsBooleanResultTests {

        @Test
        @DisplayName("should return true for boolean data")
        void shouldReturnTrueForBooleanData() {
            McpToolResult result = McpToolResult.success(true);

            assertTrue(result.isBooleanResult());
        }

        @Test
        @DisplayName("should return false for non-boolean data")
        void shouldReturnFalseForNonBooleanData() {
            McpToolResult result = McpToolResult.success("true");

            assertFalse(result.isBooleanResult());
        }
    }

    // ========================================================================
    // getSummary tests
    // ========================================================================

    @Nested
    @DisplayName("getSummary()")
    class GetSummaryTests {

        @Test
        @DisplayName("should return error summary for error result")
        void shouldReturnErrorSummaryForErrorResult() {
            McpToolResult result = McpToolResult.error("Connection failed");

            String summary = result.getSummary();

            assertTrue(summary.contains("Erreur"));
            assertTrue(summary.contains("Connection failed"));
        }

        @Test
        @DisplayName("should return error summary with unknown for null error")
        void shouldReturnErrorSummaryWithUnknownForNullError() {
            McpToolResult result = new McpToolResult();
            result.setSuccess(false);

            String summary = result.getSummary();

            assertTrue(summary.contains("Erreur inconnue"));
        }

        @Test
        @DisplayName("should return success with no data message")
        void shouldReturnSuccessWithNoDataMessage() {
            McpToolResult result = McpToolResult.success(null);

            assertEquals("Succes (aucune donnee)", result.getSummary());
        }

        @Test
        @DisplayName("should include tool name in summary")
        void shouldIncludeToolNameInSummary() {
            McpToolResult result = McpToolResult.success("data", "weather", 100L);

            assertTrue(result.getSummary().contains("weather"));
        }

        @Test
        @DisplayName("should truncate long text in summary")
        void shouldTruncateLongTextInSummary() {
            String longText = "A".repeat(100);
            McpToolResult result = McpToolResult.success(longText, "tool", 100L);

            String summary = result.getSummary();

            assertTrue(summary.contains("..."));
            assertTrue(summary.length() < longText.length() + 50);
        }

        @Test
        @DisplayName("should indicate JSON object in summary")
        void shouldIndicateJsonObjectInSummary() {
            McpToolResult result = McpToolResult.success(Map.of("k", "v"), "tool", 100L);

            assertTrue(result.getSummary().contains("Objet JSON"));
        }

        @Test
        @DisplayName("should include execution time in summary")
        void shouldIncludeExecutionTimeInSummary() {
            McpToolResult result = McpToolResult.success("data", "tool", 150L);

            assertTrue(result.getSummary().contains("150ms"));
        }

        @Test
        @DisplayName("should include numeric value in summary")
        void shouldIncludeNumericValueInSummary() {
            McpToolResult result = McpToolResult.success(42, "tool", 100L);

            assertTrue(result.getSummary().contains("42"));
        }

        @Test
        @DisplayName("should include boolean value in summary")
        void shouldIncludeBooleanValueInSummary() {
            McpToolResult result = McpToolResult.success(true, "tool", 100L);

            assertTrue(result.getSummary().contains("true"));
        }
    }

    // ========================================================================
    // withToolName tests
    // ========================================================================

    @Nested
    @DisplayName("withToolName()")
    class WithToolNameTests {

        @Test
        @DisplayName("should create clone with new tool name")
        void shouldCreateCloneWithNewToolName() {
            McpToolResult original = McpToolResult.success("data", "old-tool", 100L);
            McpToolResult cloned = original.withToolName("new-tool");

            assertEquals("new-tool", cloned.getToolName());
            assertEquals("old-tool", original.getToolName()); // Original unchanged
            assertEquals("data", cloned.getData());
            assertEquals(100L, cloned.getExecutionTimeMs());
        }

        @Test
        @DisplayName("should preserve all other fields")
        void shouldPreserveAllOtherFields() {
            McpToolResult original = McpToolResult.success("test", "tool", 200L);
            McpToolResult cloned = original.withToolName("new-name");

            assertEquals(original.isSuccess(), cloned.isSuccess());
            assertEquals(original.getData(), cloned.getData());
            assertEquals(original.getExecutionTimeMs(), cloned.getExecutionTimeMs());
            assertEquals(original.getResultType(), cloned.getResultType());
        }
    }

    // ========================================================================
    // withExecutionTime tests
    // ========================================================================

    @Nested
    @DisplayName("withExecutionTime()")
    class WithExecutionTimeTests {

        @Test
        @DisplayName("should create clone with new execution time")
        void shouldCreateCloneWithNewExecutionTime() {
            McpToolResult original = McpToolResult.success("data", "tool", 100L);
            McpToolResult cloned = original.withExecutionTime(500L);

            assertEquals(500L, cloned.getExecutionTimeMs());
            assertEquals(100L, original.getExecutionTimeMs()); // Original unchanged
            assertEquals("data", cloned.getData());
            assertEquals("tool", cloned.getToolName());
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should return summary as toString")
        void shouldReturnSummaryAsToString() {
            McpToolResult result = McpToolResult.success("data", "tool", 100L);

            assertEquals(result.getSummary(), result.toString());
        }
    }
}

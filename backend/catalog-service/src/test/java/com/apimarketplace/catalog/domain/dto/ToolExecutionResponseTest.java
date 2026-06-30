package com.apimarketplace.catalog.domain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolExecutionResponse.
 *
 * ToolExecutionResponse is a DTO for tool execution responses.
 */
@DisplayName("ToolExecutionResponse")
class ToolExecutionResponseTest {

    // ========================================================================
    // Builder tests
    // ========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build successful response")
        void shouldBuildSuccessfulResponse() {
            Object result = Map.of("temperature", 25, "unit", "celsius");
            Map<String, Object> metadata = Map.of("source", "api");

            ToolExecutionResponse response = ToolExecutionResponse.builder()
                .success(true)
                .result(result)
                .metadata(metadata)
                .executionTimeMs(150L)
                .toolId("tool-123")
                .requestId("req-456")
                .build();

            assertTrue(response.isSuccess());
            assertEquals(result, response.getResult());
            assertNull(response.getError());
            assertEquals(metadata, response.getMetadata());
            assertEquals(150L, response.getExecutionTimeMs());
            assertEquals("tool-123", response.getToolId());
            assertEquals("req-456", response.getRequestId());
        }

        @Test
        @DisplayName("should build error response")
        void shouldBuildErrorResponse() {
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                .success(false)
                .error("Connection timeout")
                .executionTimeMs(5000L)
                .toolId("tool-789")
                .requestId("req-101")
                .build();

            assertFalse(response.isSuccess());
            assertNull(response.getResult());
            assertEquals("Connection timeout", response.getError());
            assertEquals(5000L, response.getExecutionTimeMs());
        }

        @Test
        @DisplayName("should build response with minimal fields")
        void shouldBuildResponseWithMinimalFields() {
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                .success(true)
                .build();

            assertTrue(response.isSuccess());
            assertNull(response.getResult());
            assertNull(response.getError());
            assertNull(response.getMetadata());
            assertEquals(0L, response.getExecutionTimeMs());
        }
    }

    // ========================================================================
    // Getter and Setter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set success")
        void shouldGetAndSetSuccess() {
            ToolExecutionResponse response = ToolExecutionResponse.builder().build();

            response.setSuccess(true);

            assertTrue(response.isSuccess());
        }

        @Test
        @DisplayName("should get and set result")
        void shouldGetAndSetResult() {
            ToolExecutionResponse response = ToolExecutionResponse.builder().build();
            Object result = "test result";

            response.setResult(result);

            assertEquals(result, response.getResult());
        }

        @Test
        @DisplayName("should get and set error")
        void shouldGetAndSetError() {
            ToolExecutionResponse response = ToolExecutionResponse.builder().build();

            response.setError("Error message");

            assertEquals("Error message", response.getError());
        }

        @Test
        @DisplayName("should get and set metadata")
        void shouldGetAndSetMetadata() {
            ToolExecutionResponse response = ToolExecutionResponse.builder().build();
            Map<String, Object> metadata = Map.of("key", "value");

            response.setMetadata(metadata);

            assertEquals(metadata, response.getMetadata());
        }

        @Test
        @DisplayName("should get and set executionTimeMs")
        void shouldGetAndSetExecutionTimeMs() {
            ToolExecutionResponse response = ToolExecutionResponse.builder().build();

            response.setExecutionTimeMs(250L);

            assertEquals(250L, response.getExecutionTimeMs());
        }

        @Test
        @DisplayName("should get and set toolId")
        void shouldGetAndSetToolId() {
            ToolExecutionResponse response = ToolExecutionResponse.builder().build();

            response.setToolId("tool-abc");

            assertEquals("tool-abc", response.getToolId());
        }

        @Test
        @DisplayName("should get and set requestId")
        void shouldGetAndSetRequestId() {
            ToolExecutionResponse response = ToolExecutionResponse.builder().build();

            response.setRequestId("req-xyz");

            assertEquals("req-xyz", response.getRequestId());
        }
    }

    // ========================================================================
    // Result type tests
    // ========================================================================

    @Nested
    @DisplayName("Result types")
    class ResultTypeTests {

        @Test
        @DisplayName("should handle string result")
        void shouldHandleStringResult() {
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                .success(true)
                .result("Simple string result")
                .build();

            assertEquals("Simple string result", response.getResult());
        }

        @Test
        @DisplayName("should handle map result")
        void shouldHandleMapResult() {
            Map<String, Object> result = Map.of("data", List.of(1, 2, 3), "count", 3);

            ToolExecutionResponse response = ToolExecutionResponse.builder()
                .success(true)
                .result(result)
                .build();

            assertEquals(result, response.getResult());
        }

        @Test
        @DisplayName("should handle list result")
        void shouldHandleListResult() {
            java.util.List<String> result = java.util.List.of("item1", "item2", "item3");

            ToolExecutionResponse response = ToolExecutionResponse.builder()
                .success(true)
                .result(result)
                .build();

            assertEquals(result, response.getResult());
        }

        @Test
        @DisplayName("should handle numeric result")
        void shouldHandleNumericResult() {
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                .success(true)
                .result(42)
                .build();

            assertEquals(42, response.getResult());
        }
    }

    // ========================================================================
    // Equality tests
    // ========================================================================

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("should be equal for same values")
        void shouldBeEqualForSameValues() {
            ToolExecutionResponse resp1 = ToolExecutionResponse.builder()
                .success(true)
                .result("data")
                .executionTimeMs(100L)
                .toolId("tool-1")
                .build();

            ToolExecutionResponse resp2 = ToolExecutionResponse.builder()
                .success(true)
                .result("data")
                .executionTimeMs(100L)
                .toolId("tool-1")
                .build();

            assertEquals(resp1, resp2);
            assertEquals(resp1.hashCode(), resp2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different values")
        void shouldNotBeEqualForDifferentValues() {
            ToolExecutionResponse resp1 = ToolExecutionResponse.builder()
                .success(true)
                .toolId("tool-1")
                .build();

            ToolExecutionResponse resp2 = ToolExecutionResponse.builder()
                .success(false)
                .toolId("tool-1")
                .build();

            assertNotEquals(resp1, resp2);
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("should return string representation")
        void shouldReturnStringRepresentation() {
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                .success(true)
                .toolId("weather-api")
                .executionTimeMs(150L)
                .build();

            String str = response.toString();

            assertNotNull(str);
            assertTrue(str.contains("ToolExecutionResponse"));
        }
    }
}

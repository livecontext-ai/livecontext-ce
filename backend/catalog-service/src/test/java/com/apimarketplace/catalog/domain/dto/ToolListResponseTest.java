package com.apimarketplace.catalog.domain.dto;

import com.apimarketplace.catalog.domain.ToolCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolListResponse.
 *
 * ToolListResponse is a DTO for tool list responses.
 */
@DisplayName("ToolListResponse")
class ToolListResponseTest {

    // ========================================================================
    // Builder tests
    // ========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build response with all fields")
        void shouldBuildResponseWithAllFields() {
            List<ToolCard> tools = List.of(
                ToolCard.of("weather", "Get weather data", "REST", "HIGH")
            );

            ToolListResponse response = ToolListResponse.builder()
                .tools(tools)
                .total(100)
                .limit(10)
                .offset(0)
                .build();

            assertEquals(tools, response.getTools());
            assertEquals(100, response.getTotal());
            assertEquals(10, response.getLimit());
            assertEquals(0, response.getOffset());
            assertNull(response.getError());
        }

        @Test
        @DisplayName("should build error response")
        void shouldBuildErrorResponse() {
            ToolListResponse response = ToolListResponse.builder()
                .error("Search failed")
                .build();

            assertNull(response.getTools());
            assertEquals("Search failed", response.getError());
        }

        @Test
        @DisplayName("should build empty response")
        void shouldBuildEmptyResponse() {
            ToolListResponse response = ToolListResponse.builder()
                .tools(List.of())
                .total(0)
                .limit(10)
                .offset(0)
                .build();

            assertNotNull(response.getTools());
            assertTrue(response.getTools().isEmpty());
            assertEquals(0, response.getTotal());
        }
    }

    // ========================================================================
    // Getter and Setter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set tools")
        void shouldGetAndSetTools() {
            ToolListResponse response = ToolListResponse.builder().build();
            List<ToolCard> tools = List.of(ToolCard.of("api", "desc", "MCP", "MEDIUM"));

            response.setTools(tools);

            assertEquals(tools, response.getTools());
        }

        @Test
        @DisplayName("should get and set total")
        void shouldGetAndSetTotal() {
            ToolListResponse response = ToolListResponse.builder().build();

            response.setTotal(50);

            assertEquals(50, response.getTotal());
        }

        @Test
        @DisplayName("should get and set limit")
        void shouldGetAndSetLimit() {
            ToolListResponse response = ToolListResponse.builder().build();

            response.setLimit(25);

            assertEquals(25, response.getLimit());
        }

        @Test
        @DisplayName("should get and set offset")
        void shouldGetAndSetOffset() {
            ToolListResponse response = ToolListResponse.builder().build();

            response.setOffset(20);

            assertEquals(20, response.getOffset());
        }

        @Test
        @DisplayName("should get and set error")
        void shouldGetAndSetError() {
            ToolListResponse response = ToolListResponse.builder().build();

            response.setError("An error occurred");

            assertEquals("An error occurred", response.getError());
        }
    }

    // ========================================================================
    // Pagination tests
    // ========================================================================

    @Nested
    @DisplayName("Pagination")
    class PaginationTests {

        @Test
        @DisplayName("should handle first page")
        void shouldHandleFirstPage() {
            ToolListResponse response = ToolListResponse.builder()
                .tools(List.of())
                .total(100)
                .limit(10)
                .offset(0)
                .build();

            assertEquals(0, response.getOffset());
            assertEquals(10, response.getLimit());
            assertEquals(100, response.getTotal());
        }

        @Test
        @DisplayName("should handle middle page")
        void shouldHandleMiddlePage() {
            ToolListResponse response = ToolListResponse.builder()
                .tools(List.of())
                .total(100)
                .limit(10)
                .offset(50)
                .build();

            assertEquals(50, response.getOffset());
        }

        @Test
        @DisplayName("should handle last page")
        void shouldHandleLastPage() {
            ToolListResponse response = ToolListResponse.builder()
                .tools(List.of())
                .total(95)
                .limit(10)
                .offset(90)
                .build();

            assertEquals(90, response.getOffset());
            assertEquals(95, response.getTotal());
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
            List<ToolCard> tools = List.of();

            ToolListResponse resp1 = ToolListResponse.builder()
                .tools(tools)
                .total(10)
                .limit(5)
                .offset(0)
                .build();

            ToolListResponse resp2 = ToolListResponse.builder()
                .tools(tools)
                .total(10)
                .limit(5)
                .offset(0)
                .build();

            assertEquals(resp1, resp2);
        }
    }
}

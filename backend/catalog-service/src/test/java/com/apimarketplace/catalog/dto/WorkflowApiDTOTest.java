package com.apimarketplace.catalog.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkflowApiDTO record.
 *
 * WorkflowApiDTO is an optimized DTO for APIs in the workflow inspector,
 * containing only fields necessary for display.
 */
@DisplayName("WorkflowApiDTO")
class WorkflowApiDTOTest {

    // ========================================================================
    // Constructor and accessor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor and accessors")
    class ConstructorAndAccessorTests {

        @Test
        @DisplayName("should create DTO with all fields")
        void shouldCreateDtoWithAllFields() {
            WorkflowApiDTO dto = new WorkflowApiDTO(
                "weather-api",
                "Weather API",
                "Get weather data",
                5,
                "weather-icon",
                null
            );

            assertEquals("weather-api", dto.slug());
            assertEquals("Weather API", dto.apiName());
            assertEquals("Get weather data", dto.description());
            assertEquals(5, dto.toolsCount());
            assertEquals("weather-icon", dto.iconSlug());
        }

        @Test
        @DisplayName("should create DTO with null values")
        void shouldCreateDtoWithNullValues() {
            WorkflowApiDTO dto = new WorkflowApiDTO(
                null, null, null, null, null, null
            );

            assertNull(dto.slug());
            assertNull(dto.apiName());
            assertNull(dto.description());
            assertNull(dto.toolsCount());
            assertNull(dto.iconSlug());
        }

        @Test
        @DisplayName("should create DTO with zero tools count")
        void shouldCreateDtoWithZeroToolsCount() {
            WorkflowApiDTO dto = new WorkflowApiDTO(
                "api-slug",
                "API Name",
                "Description",
                0,
                "icon",
                null
            );

            assertEquals(0, dto.toolsCount());
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
            WorkflowApiDTO dto1 = new WorkflowApiDTO(
                "slug", "API", "desc", 3, "icon", null
            );
            WorkflowApiDTO dto2 = new WorkflowApiDTO(
                "slug", "API", "desc", 3, "icon", null
            );

            assertEquals(dto1, dto2);
            assertEquals(dto1.hashCode(), dto2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different values")
        void shouldNotBeEqualForDifferentValues() {
            WorkflowApiDTO dto1 = new WorkflowApiDTO(
                "slug1", "API", "desc", 3, "icon", null
            );
            WorkflowApiDTO dto2 = new WorkflowApiDTO(
                "slug2", "API", "desc", 3, "icon", null
            );

            assertNotEquals(dto1, dto2);
        }

        @Test
        @DisplayName("should not be equal to null")
        void shouldNotBeEqualToNull() {
            WorkflowApiDTO dto = new WorkflowApiDTO(
                "slug", "API", "desc", 3, "icon", null
            );

            assertNotEquals(null, dto);
        }

        @Test
        @DisplayName("should handle equality with null fields")
        void shouldHandleEqualityWithNullFields() {
            WorkflowApiDTO dto1 = new WorkflowApiDTO(
                null, null, null, null, null, null
            );
            WorkflowApiDTO dto2 = new WorkflowApiDTO(
                null, null, null, null, null, null
            );

            assertEquals(dto1, dto2);
            assertEquals(dto1.hashCode(), dto2.hashCode());
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should include all fields")
        void shouldIncludeAllFields() {
            WorkflowApiDTO dto = new WorkflowApiDTO(
                "test-slug",
                "Test API",
                "Test description",
                10,
                "test-icon",
                null
            );

            String result = dto.toString();

            assertTrue(result.contains("test-slug"));
            assertTrue(result.contains("Test API"));
            assertTrue(result.contains("Test description"));
            assertTrue(result.contains("10"));
            assertTrue(result.contains("test-icon"));
        }

        @Test
        @DisplayName("should handle null values in toString")
        void shouldHandleNullValuesInToString() {
            WorkflowApiDTO dto = new WorkflowApiDTO(
                null, null, null, null, null, null
            );

            String result = dto.toString();

            assertNotNull(result);
            assertTrue(result.contains("null"));
        }
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle empty strings")
        void shouldHandleEmptyStrings() {
            WorkflowApiDTO dto = new WorkflowApiDTO(
                "", "", "", 0, "", null
            );

            assertEquals("", dto.slug());
            assertEquals("", dto.apiName());
            assertEquals("", dto.description());
            assertEquals(0, dto.toolsCount());
            assertEquals("", dto.iconSlug());
        }

        @Test
        @DisplayName("should handle large tools count")
        void shouldHandleLargeToolsCount() {
            WorkflowApiDTO dto = new WorkflowApiDTO(
                "slug", "API", "desc", Integer.MAX_VALUE, "icon", null
            );

            assertEquals(Integer.MAX_VALUE, dto.toolsCount());
        }

        @Test
        @DisplayName("should handle negative tools count")
        void shouldHandleNegativeToolsCount() {
            WorkflowApiDTO dto = new WorkflowApiDTO(
                "slug", "API", "desc", -1, "icon", null
            );

            assertEquals(-1, dto.toolsCount());
        }

        @Test
        @DisplayName("should handle special characters in strings")
        void shouldHandleSpecialCharactersInStrings() {
            WorkflowApiDTO dto = new WorkflowApiDTO(
                "api-with-émojis-🚀",
                "API \"with\" <special> & chars",
                "Description\nwith\nnewlines",
                5,
                "icon/path",
                null
            );

            assertEquals("api-with-émojis-🚀", dto.slug());
            assertEquals("API \"with\" <special> & chars", dto.apiName());
            assertEquals("Description\nwith\nnewlines", dto.description());
        }
    }
}

package com.apimarketplace.catalog.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkflowToolDTO record.
 *
 * WorkflowToolDTO is optimized for displaying tools in the workflow inspector list.
 * Contains only fields necessary for list display.
 */
@DisplayName("WorkflowToolDTO")
class WorkflowToolDTOTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {

        @Test
        @DisplayName("should create DTO with all fields")
        void shouldCreateDtoWithAllFields() {
            WorkflowToolDTO dto = new WorkflowToolDTO(
                "get-weather",
                "Get Weather",
                "Retrieves current weather data",
                "GET",
                "weather-api",
                "weather-icon",
                null,
                "11111111-2222-3333-4444-555555555555",
                null,
                null
            );

            assertEquals("get-weather", dto.slug());
            assertEquals("Get Weather", dto.name());
            assertEquals("Retrieves current weather data", dto.description());
            assertEquals("GET", dto.method());
            assertEquals("weather-api", dto.apiSlug());
            assertEquals("weather-icon", dto.iconSlug());
            assertEquals("11111111-2222-3333-4444-555555555555", dto.toolId());
        }

        @Test
        @DisplayName("should accept null values for optional fields")
        void shouldAcceptNullValues() {
            WorkflowToolDTO dto = new WorkflowToolDTO(
                "tool-slug",
                "Tool Name",
                null,  // description can be null
                null,  // method can be null
                null,  // apiSlug can be null
                null,  // iconSlug can be null
                null,  // iconUrl can be null
                null,  // toolId can be null
                null,  // requiredScopes
                null   // integrationName
            );

            assertEquals("tool-slug", dto.slug());
            assertEquals("Tool Name", dto.name());
            assertNull(dto.description());
            assertNull(dto.method());
            assertNull(dto.apiSlug());
            assertNull(dto.iconSlug());
            assertNull(dto.toolId());
        }

        @Test
        @DisplayName("should accept empty strings")
        void shouldAcceptEmptyStrings() {
            WorkflowToolDTO dto = new WorkflowToolDTO(
                "",
                "",
                "",
                "",
                "",
                "",
                null,
                null,
                null,
                null
            );

            assertEquals("", dto.slug());
            assertEquals("", dto.name());
            assertEquals("", dto.description());
            assertEquals("", dto.method());
            assertEquals("", dto.apiSlug());
            assertEquals("", dto.iconSlug());
        }
    }

    // ========================================================================
    // HTTP method tests
    // ========================================================================

    @Nested
    @DisplayName("HTTP methods")
    class HttpMethodTests {

        @Test
        @DisplayName("should handle GET method")
        void shouldHandleGetMethod() {
            WorkflowToolDTO dto = new WorkflowToolDTO(
                "list-users", "List Users", "Get all users",
                "GET", "user-api", "users-icon", null, null,
                null,
                null
            );

            assertEquals("GET", dto.method());
        }

        @Test
        @DisplayName("should handle POST method")
        void shouldHandlePostMethod() {
            WorkflowToolDTO dto = new WorkflowToolDTO(
                "create-user", "Create User", "Create a new user",
                "POST", "user-api", "users-icon", null, null,
                null,
                null
            );

            assertEquals("POST", dto.method());
        }

        @Test
        @DisplayName("should handle PUT method")
        void shouldHandlePutMethod() {
            WorkflowToolDTO dto = new WorkflowToolDTO(
                "update-user", "Update User", "Update existing user",
                "PUT", "user-api", "users-icon", null, null,
                null,
                null
            );

            assertEquals("PUT", dto.method());
        }

        @Test
        @DisplayName("should handle DELETE method")
        void shouldHandleDeleteMethod() {
            WorkflowToolDTO dto = new WorkflowToolDTO(
                "delete-user", "Delete User", "Delete a user",
                "DELETE", "user-api", "users-icon", null, null,
                null,
                null
            );

            assertEquals("DELETE", dto.method());
        }
    }

    // ========================================================================
    // Record equality tests
    // ========================================================================

    @Nested
    @DisplayName("Record equality")
    class EqualityTests {

        @Test
        @DisplayName("should be equal for same values")
        void shouldBeEqualForSameValues() {
            WorkflowToolDTO dto1 = new WorkflowToolDTO(
                "slug", "name", "desc", "GET", "api", "icon", null, null,
                null,
                null
            );

            WorkflowToolDTO dto2 = new WorkflowToolDTO(
                "slug", "name", "desc", "GET", "api", "icon", null, null,
                null,
                null
            );

            assertEquals(dto1, dto2);
            assertEquals(dto1.hashCode(), dto2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different values")
        void shouldNotBeEqualForDifferentValues() {
            WorkflowToolDTO dto1 = new WorkflowToolDTO(
                "slug1", "name", "desc", "GET", "api", "icon", null, null,
                null,
                null
            );

            WorkflowToolDTO dto2 = new WorkflowToolDTO(
                "slug2", "name", "desc", "GET", "api", "icon", null, null,
                null,
                null
            );

            assertNotEquals(dto1, dto2);
        }

        @Test
        @DisplayName("should handle equality with null fields")
        void shouldHandleEqualityWithNullFields() {
            WorkflowToolDTO dto1 = new WorkflowToolDTO(
                "slug", "name", null, null, null, null, null, null,
                null,
                null
            );

            WorkflowToolDTO dto2 = new WorkflowToolDTO(
                "slug", "name", null, null, null, null, null, null,
                null,
                null
            );

            assertEquals(dto1, dto2);
            assertEquals(dto1.hashCode(), dto2.hashCode());
        }
    }

    // ========================================================================
    // Real-world usage tests
    // ========================================================================

    @Nested
    @DisplayName("Real-world usage")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should represent weather API tool")
        void shouldRepresentWeatherApiTool() {
            WorkflowToolDTO dto = new WorkflowToolDTO(
                "get-current-weather",
                "Get Current Weather",
                "Retrieves current weather conditions for a specified location",
                "GET",
                "openweathermap",
                "weather",
                null,
                null,
                null,
                null
            );

            assertEquals("get-current-weather", dto.slug());
            assertEquals("openweathermap", dto.apiSlug());
            assertTrue(dto.description().contains("weather"));
        }

        @Test
        @DisplayName("should represent database query tool")
        void shouldRepresentDatabaseQueryTool() {
            WorkflowToolDTO dto = new WorkflowToolDTO(
                "execute-query",
                "Execute SQL Query",
                "Execute a parameterized SQL query against a configured database",
                "POST",
                "database-connector",
                "database",
                null,
                null,
                null,
                null
            );

            assertEquals("execute-query", dto.slug());
            assertEquals("POST", dto.method());
            assertEquals("database-connector", dto.apiSlug());
        }

        @Test
        @DisplayName("should represent notification tool")
        void shouldRepresentNotificationTool() {
            WorkflowToolDTO dto = new WorkflowToolDTO(
                "send-notification",
                "Send Notification",
                "Send push notification to specified users",
                "POST",
                "push-service",
                "notification",
                null,
                null,
                null,
                null
            );

            assertNotNull(dto.slug());
            assertNotNull(dto.name());
            assertEquals("POST", dto.method());
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("should include all field values in toString")
        void shouldIncludeAllFieldsInToString() {
            WorkflowToolDTO dto = new WorkflowToolDTO(
                "my-slug",
                "My Tool",
                "My description",
                "GET",
                "my-api",
                "my-icon",
                null,
                null,
                null,
                null
            );

            String str = dto.toString();
            assertTrue(str.contains("my-slug"));
            assertTrue(str.contains("My Tool"));
            assertTrue(str.contains("My description"));
            assertTrue(str.contains("GET"));
            assertTrue(str.contains("my-api"));
            assertTrue(str.contains("my-icon"));
        }
    }
}

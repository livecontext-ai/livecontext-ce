package com.apimarketplace.catalog.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkflowToolDetailDTO record.
 *
 * WorkflowToolDetailDTO is an optimized DTO for complete tool details
 * in the workflow inspector, containing parameters, responses and credentials.
 */
@DisplayName("WorkflowToolDetailDTO")
class WorkflowToolDetailDTOTest {

    // ========================================================================
    // Constructor and accessor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor and accessors")
    class ConstructorAndAccessorTests {

        @Test
        @DisplayName("should create DTO with all fields")
        void shouldCreateDtoWithAllFields() {
            List<WorkflowParameterDTO> params = List.of(
                new WorkflowParameterDTO("param1", "Param description", "string", true, "query", null, null)
            );
            List<WorkflowToolResponseDTO> responses = List.of(
                new WorkflowToolResponseDTO(UUID.randomUUID(), "Success", "Success response", null, null, null, "json", 200, true)
            );
            List<WorkflowToolCredentialDTO> credentials = List.of(
                new WorkflowToolCredentialDTO("api_key", true, "usage", null, null, "API Key", "API key credential", "api_key", "oauth2", null, null, null, null, null)
            );

            WorkflowToolDetailDTO dto = new WorkflowToolDetailDTO(
                "get-weather",
                "Get Weather",
                "Fetch weather data",
                "GET",
                "weather-api",
                "weather-icon",
                null,
                params,
                responses,
                credentials,
                "11111111-2222-3333-4444-555555555555",
                null,
                null
            );

            assertEquals("get-weather", dto.slug());
            assertEquals("Get Weather", dto.name());
            assertEquals("Fetch weather data", dto.description());
            assertEquals("GET", dto.method());
            assertEquals("weather-api", dto.apiSlug());
            assertEquals("weather-icon", dto.iconSlug());
            assertEquals(1, dto.parameters().size());
            assertEquals(1, dto.responses().size());
            assertEquals(1, dto.credentials().size());
        }

        @Test
        @DisplayName("should create DTO with null values")
        void shouldCreateDtoWithNullValues() {
            WorkflowToolDetailDTO dto = new WorkflowToolDetailDTO(
                null, null, null, null, null, null, null, null, null, null, null,
                null,
                null
            );

            assertNull(dto.slug());
            assertNull(dto.name());
            assertNull(dto.description());
            assertNull(dto.method());
            assertNull(dto.apiSlug());
            assertNull(dto.iconSlug());
            assertNull(dto.parameters());
            assertNull(dto.responses());
            assertNull(dto.credentials());
            assertNull(dto.toolId());
        }

        @Test
        @DisplayName("should create DTO with empty lists")
        void shouldCreateDtoWithEmptyLists() {
            WorkflowToolDetailDTO dto = new WorkflowToolDetailDTO(
                "slug",
                "Name",
                "Description",
                "POST",
                "api-slug",
                "icon",
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null
            );

            assertTrue(dto.parameters().isEmpty());
            assertTrue(dto.responses().isEmpty());
            assertTrue(dto.credentials().isEmpty());
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
            WorkflowToolDetailDTO dto = createBasicDto("GET");
            assertEquals("GET", dto.method());
        }

        @Test
        @DisplayName("should handle POST method")
        void shouldHandlePostMethod() {
            WorkflowToolDetailDTO dto = createBasicDto("POST");
            assertEquals("POST", dto.method());
        }

        @Test
        @DisplayName("should handle PUT method")
        void shouldHandlePutMethod() {
            WorkflowToolDetailDTO dto = createBasicDto("PUT");
            assertEquals("PUT", dto.method());
        }

        @Test
        @DisplayName("should handle DELETE method")
        void shouldHandleDeleteMethod() {
            WorkflowToolDetailDTO dto = createBasicDto("DELETE");
            assertEquals("DELETE", dto.method());
        }

        @Test
        @DisplayName("should handle PATCH method")
        void shouldHandlePatchMethod() {
            WorkflowToolDetailDTO dto = createBasicDto("PATCH");
            assertEquals("PATCH", dto.method());
        }

        private WorkflowToolDetailDTO createBasicDto(String method) {
            return new WorkflowToolDetailDTO(
                "slug", "Name", "Description", method,
                "api-slug", "icon", null, List.of(), List.of(), List.of(),
                null,
                null,
                null
            );
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
            List<WorkflowParameterDTO> params = List.of();
            List<WorkflowToolResponseDTO> responses = List.of();
            List<WorkflowToolCredentialDTO> credentials = List.of();

            WorkflowToolDetailDTO dto1 = new WorkflowToolDetailDTO(
                "slug", "Name", "Desc", "GET", "api", "icon", null, params, responses, credentials,
                null,
                null,
                null
            );
            WorkflowToolDetailDTO dto2 = new WorkflowToolDetailDTO(
                "slug", "Name", "Desc", "GET", "api", "icon", null, params, responses, credentials,
                null,
                null,
                null
            );

            assertEquals(dto1, dto2);
            assertEquals(dto1.hashCode(), dto2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different slugs")
        void shouldNotBeEqualForDifferentSlugs() {
            WorkflowToolDetailDTO dto1 = new WorkflowToolDetailDTO(
                "slug1", "Name", "Desc", "GET", "api", "icon", null,
                List.of(), List.of(), List.of(),
                null,
                null,
                null
            );
            WorkflowToolDetailDTO dto2 = new WorkflowToolDetailDTO(
                "slug2", "Name", "Desc", "GET", "api", "icon", null,
                List.of(), List.of(), List.of(),
                null,
                null,
                null
            );

            assertNotEquals(dto1, dto2);
        }

        @Test
        @DisplayName("should not be equal for different methods")
        void shouldNotBeEqualForDifferentMethods() {
            WorkflowToolDetailDTO dto1 = new WorkflowToolDetailDTO(
                "slug", "Name", "Desc", "GET", "api", "icon", null,
                List.of(), List.of(), List.of(),
                null,
                null,
                null
            );
            WorkflowToolDetailDTO dto2 = new WorkflowToolDetailDTO(
                "slug", "Name", "Desc", "POST", "api", "icon", null,
                List.of(), List.of(), List.of(),
                null,
                null,
                null
            );

            assertNotEquals(dto1, dto2);
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
            WorkflowToolDetailDTO dto = new WorkflowToolDetailDTO(
                "test-slug",
                "Test Tool",
                "Test description",
                "POST",
                "test-api",
                "test-icon",
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null
            );

            String result = dto.toString();

            assertTrue(result.contains("test-slug"));
            assertTrue(result.contains("Test Tool"));
            assertTrue(result.contains("POST"));
            assertTrue(result.contains("test-api"));
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
            WorkflowToolDetailDTO dto = new WorkflowToolDetailDTO(
                "", "", "", "", "", "", null,
                List.of(), List.of(), List.of(),
                null,
                null,
                null
            );

            assertEquals("", dto.slug());
            assertEquals("", dto.name());
            assertEquals("", dto.method());
        }

        @Test
        @DisplayName("should handle multiple parameters")
        void shouldHandleMultipleParameters() {
            List<WorkflowParameterDTO> params = List.of(
                new WorkflowParameterDTO("param1", "Description 1", "string", true, "query", null, null),
                new WorkflowParameterDTO("param2", "Description 2", "integer", true, "path", null, null),
                new WorkflowParameterDTO("param3", "Description 3", "boolean", false, "header", null, null)
            );

            WorkflowToolDetailDTO dto = new WorkflowToolDetailDTO(
                "slug", "Name", "Desc", "GET", "api", "icon", null,
                params, List.of(), List.of(),
                null,
                null,
                null
            );

            assertEquals(3, dto.parameters().size());
        }

        @Test
        @DisplayName("should handle multiple responses")
        void shouldHandleMultipleResponses() {
            List<WorkflowToolResponseDTO> responses = List.of(
                new WorkflowToolResponseDTO(UUID.randomUUID(), "Success", null, null, null, null, "json", 200, true),
                new WorkflowToolResponseDTO(UUID.randomUUID(), "Not Found", null, null, null, null, "json", 404, false),
                new WorkflowToolResponseDTO(UUID.randomUUID(), "Error", null, null, null, null, "json", 500, false)
            );

            WorkflowToolDetailDTO dto = new WorkflowToolDetailDTO(
                "slug", "Name", "Desc", "GET", "api", "icon", null,
                List.of(), responses, List.of(),
                null,
                null,
                null
            );

            assertEquals(3, dto.responses().size());
        }

        @Test
        @DisplayName("should handle multiple credentials")
        void shouldHandleMultipleCredentials() {
            List<WorkflowToolCredentialDTO> credentials = List.of(
                new WorkflowToolCredentialDTO("api_key", true, null, null, null, "API Key", null, "api_key", null, null, null, null, null, null),
                new WorkflowToolCredentialDTO("oauth2", false, null, null, null, "OAuth Token", null, "oauth2", null, null, null, null, null, null)
            );

            WorkflowToolDetailDTO dto = new WorkflowToolDetailDTO(
                "slug", "Name", "Desc", "GET", "api", "icon", null,
                List.of(), List.of(), credentials,
                null,
                null,
                null
            );

            assertEquals(2, dto.credentials().size());
        }
    }
}

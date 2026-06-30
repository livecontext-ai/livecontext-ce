package com.apimarketplace.catalog.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkflowToolResponseDTO record.
 *
 * WorkflowToolResponseDTO is an optimized DTO for tool responses
 * in the workflow inspector, containing only fields necessary for display.
 */
@DisplayName("WorkflowToolResponseDTO")
class WorkflowToolResponseDTOTest {

    // ========================================================================
    // Constructor and accessor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor and accessors")
    class ConstructorAndAccessorTests {

        @Test
        @DisplayName("should create DTO with all fields")
        void shouldCreateDtoWithAllFields() {
            UUID id = UUID.randomUUID();
            WorkflowToolResponseDTO dto = new WorkflowToolResponseDTO(
                id,
                "Success Response",
                "Returns success data",
                "{\"type\": \"object\"}",
                "{\"id\": 1}",
                "{\"id\": 1}",
                "json",
                200,
                true
            );

            assertEquals(id, dto.id());
            assertEquals("Success Response", dto.name());
            assertEquals("Returns success data", dto.description());
            assertEquals("{\"type\": \"object\"}", dto.schema());
            assertEquals("{\"id\": 1}", dto.example());
            assertEquals("{\"id\": 1}", dto.exampleJsonb());
            assertEquals("json", dto.format());
            assertEquals(200, dto.statusCode());
            assertTrue(dto.isDefault());
        }

        @Test
        @DisplayName("should create DTO with null values")
        void shouldCreateDtoWithNullValues() {
            WorkflowToolResponseDTO dto = new WorkflowToolResponseDTO(
                null, null, null, null, null, null, null, null, null
            );

            assertNull(dto.id());
            assertNull(dto.name());
            assertNull(dto.description());
            assertNull(dto.schema());
            assertNull(dto.example());
            assertNull(dto.exampleJsonb());
            assertNull(dto.format());
            assertNull(dto.statusCode());
            assertNull(dto.isDefault());
        }

        @Test
        @DisplayName("should create DTO with minimal fields")
        void shouldCreateDtoWithMinimalFields() {
            UUID id = UUID.randomUUID();
            WorkflowToolResponseDTO dto = new WorkflowToolResponseDTO(
                id, "Response", null, null, null, null, "json", 200, false
            );

            assertEquals(id, dto.id());
            assertEquals("Response", dto.name());
            assertEquals("json", dto.format());
            assertEquals(200, dto.statusCode());
            assertFalse(dto.isDefault());
        }
    }

    // ========================================================================
    // Status code tests
    // ========================================================================

    @Nested
    @DisplayName("Status codes")
    class StatusCodeTests {

        @Test
        @DisplayName("should handle success status codes")
        void shouldHandleSuccessStatusCodes() {
            assertEquals(200, createDtoWithStatus(200).statusCode());
            assertEquals(201, createDtoWithStatus(201).statusCode());
            assertEquals(204, createDtoWithStatus(204).statusCode());
        }

        @Test
        @DisplayName("should handle redirect status codes")
        void shouldHandleRedirectStatusCodes() {
            assertEquals(301, createDtoWithStatus(301).statusCode());
            assertEquals(302, createDtoWithStatus(302).statusCode());
            assertEquals(307, createDtoWithStatus(307).statusCode());
        }

        @Test
        @DisplayName("should handle client error status codes")
        void shouldHandleClientErrorStatusCodes() {
            assertEquals(400, createDtoWithStatus(400).statusCode());
            assertEquals(401, createDtoWithStatus(401).statusCode());
            assertEquals(403, createDtoWithStatus(403).statusCode());
            assertEquals(404, createDtoWithStatus(404).statusCode());
            assertEquals(422, createDtoWithStatus(422).statusCode());
        }

        @Test
        @DisplayName("should handle server error status codes")
        void shouldHandleServerErrorStatusCodes() {
            assertEquals(500, createDtoWithStatus(500).statusCode());
            assertEquals(502, createDtoWithStatus(502).statusCode());
            assertEquals(503, createDtoWithStatus(503).statusCode());
        }

        private WorkflowToolResponseDTO createDtoWithStatus(int statusCode) {
            return new WorkflowToolResponseDTO(
                UUID.randomUUID(), "Response", null, null, null, null, "json", statusCode, false
            );
        }
    }

    // ========================================================================
    // Format tests
    // ========================================================================

    @Nested
    @DisplayName("Format field")
    class FormatTests {

        @Test
        @DisplayName("should handle json format")
        void shouldHandleJsonFormat() {
            WorkflowToolResponseDTO dto = createDtoWithFormat("json");
            assertEquals("json", dto.format());
        }

        @Test
        @DisplayName("should handle xml format")
        void shouldHandleXmlFormat() {
            WorkflowToolResponseDTO dto = createDtoWithFormat("xml");
            assertEquals("xml", dto.format());
        }

        @Test
        @DisplayName("should handle text format")
        void shouldHandleTextFormat() {
            WorkflowToolResponseDTO dto = createDtoWithFormat("text");
            assertEquals("text", dto.format());
        }

        @Test
        @DisplayName("should handle binary format")
        void shouldHandleBinaryFormat() {
            WorkflowToolResponseDTO dto = createDtoWithFormat("binary");
            assertEquals("binary", dto.format());
        }

        private WorkflowToolResponseDTO createDtoWithFormat(String format) {
            return new WorkflowToolResponseDTO(
                UUID.randomUUID(), "Response", null, null, null, null, format, 200, false
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
            UUID id = UUID.randomUUID();
            WorkflowToolResponseDTO dto1 = new WorkflowToolResponseDTO(
                id, "Response", "Desc", "{}", "{}", "{}", "json", 200, true
            );
            WorkflowToolResponseDTO dto2 = new WorkflowToolResponseDTO(
                id, "Response", "Desc", "{}", "{}", "{}", "json", 200, true
            );

            assertEquals(dto1, dto2);
            assertEquals(dto1.hashCode(), dto2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different IDs")
        void shouldNotBeEqualForDifferentIds() {
            WorkflowToolResponseDTO dto1 = new WorkflowToolResponseDTO(
                UUID.randomUUID(), "Response", null, null, null, null, "json", 200, true
            );
            WorkflowToolResponseDTO dto2 = new WorkflowToolResponseDTO(
                UUID.randomUUID(), "Response", null, null, null, null, "json", 200, true
            );

            assertNotEquals(dto1, dto2);
        }

        @Test
        @DisplayName("should not be equal for different status codes")
        void shouldNotBeEqualForDifferentStatusCodes() {
            UUID id = UUID.randomUUID();
            WorkflowToolResponseDTO dto1 = new WorkflowToolResponseDTO(
                id, "Response", null, null, null, null, "json", 200, true
            );
            WorkflowToolResponseDTO dto2 = new WorkflowToolResponseDTO(
                id, "Response", null, null, null, null, "json", 404, true
            );

            assertNotEquals(dto1, dto2);
        }

        @Test
        @DisplayName("should not be equal for different isDefault values")
        void shouldNotBeEqualForDifferentIsDefaultValues() {
            UUID id = UUID.randomUUID();
            WorkflowToolResponseDTO dto1 = new WorkflowToolResponseDTO(
                id, "Response", null, null, null, null, "json", 200, true
            );
            WorkflowToolResponseDTO dto2 = new WorkflowToolResponseDTO(
                id, "Response", null, null, null, null, "json", 200, false
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
            UUID id = UUID.randomUUID();
            WorkflowToolResponseDTO dto = new WorkflowToolResponseDTO(
                id,
                "Test Response",
                "Test description",
                "{\"type\": \"string\"}",
                "example",
                "exampleJsonb",
                "json",
                201,
                true
            );

            String result = dto.toString();

            assertTrue(result.contains(id.toString()));
            assertTrue(result.contains("Test Response"));
            assertTrue(result.contains("json"));
            assertTrue(result.contains("201"));
            assertTrue(result.contains("true"));
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
            UUID id = UUID.randomUUID();
            WorkflowToolResponseDTO dto = new WorkflowToolResponseDTO(
                id, "", "", "", "", "", "", 200, false
            );

            assertEquals("", dto.name());
            assertEquals("", dto.description());
            assertEquals("", dto.schema());
            assertEquals("", dto.example());
            assertEquals("", dto.format());
        }

        @Test
        @DisplayName("should handle large JSON schema")
        void shouldHandleLargeJsonSchema() {
            String largeSchema = "{\"type\": \"object\", \"properties\": {" +
                "\"field1\": {\"type\": \"string\"}," +
                "\"field2\": {\"type\": \"number\"}," +
                "\"field3\": {\"type\": \"boolean\"}," +
                "\"field4\": {\"type\": \"array\", \"items\": {\"type\": \"string\"}}" +
                "}}";

            WorkflowToolResponseDTO dto = new WorkflowToolResponseDTO(
                UUID.randomUUID(), "Response", null, largeSchema, null, null, "json", 200, true
            );

            assertEquals(largeSchema, dto.schema());
        }

        @Test
        @DisplayName("should handle complex example JSON")
        void shouldHandleComplexExampleJson() {
            String complexExample = "{\"users\": [{\"id\": 1, \"name\": \"John\"}, {\"id\": 2, \"name\": \"Jane\"}], \"total\": 2}";

            WorkflowToolResponseDTO dto = new WorkflowToolResponseDTO(
                UUID.randomUUID(), "Response", null, null, complexExample, complexExample, "json", 200, true
            );

            assertEquals(complexExample, dto.example());
            assertEquals(complexExample, dto.exampleJsonb());
        }

        @Test
        @DisplayName("should handle special characters in description")
        void shouldHandleSpecialCharactersInDescription() {
            String description = "Response with <special> \"characters\" & symbols \n newlines";

            WorkflowToolResponseDTO dto = new WorkflowToolResponseDTO(
                UUID.randomUUID(), "Response", description, null, null, null, "json", 200, false
            );

            assertEquals(description, dto.description());
        }

        @Test
        @DisplayName("should handle zero status code")
        void shouldHandleZeroStatusCode() {
            WorkflowToolResponseDTO dto = new WorkflowToolResponseDTO(
                UUID.randomUUID(), "Response", null, null, null, null, "json", 0, false
            );

            assertEquals(0, dto.statusCode());
        }

        @Test
        @DisplayName("should handle negative status code")
        void shouldHandleNegativeStatusCode() {
            WorkflowToolResponseDTO dto = new WorkflowToolResponseDTO(
                UUID.randomUUID(), "Response", null, null, null, null, "json", -1, false
            );

            assertEquals(-1, dto.statusCode());
        }
    }
}

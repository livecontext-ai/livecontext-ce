package com.apimarketplace.catalog.dto;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkflowParameterDTO record.
 *
 * WorkflowParameterDTO is optimized for workflow inspector tool parameters display.
 */
@DisplayName("WorkflowParameterDTO")
class WorkflowParameterDTOTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class RecordConstructionTests {

        @Test
        @DisplayName("should create record with all fields")
        void shouldCreateRecordWithAllFields() {
            WorkflowParameterDTO dto = new WorkflowParameterDTO(
                    "userId",
                    "The user identifier",
                    "string",
                    true,
                    "path",
                    null,
                    null
            );

            assertEquals("userId", dto.name());
            assertEquals("The user identifier", dto.description());
            assertEquals("string", dto.dataType());
            assertTrue(dto.isRequired());
            assertEquals("path", dto.parameterType());
            assertNull(dto.defaultValue());
            assertNull(dto.allowedValues());
        }

        @Test
        @DisplayName("should carry scalar defaultValue when provided")
        void shouldCarryScalarDefault() {
            WorkflowParameterDTO dto = new WorkflowParameterDTO(
                    "temperature",
                    "Sampling temperature",
                    "number",
                    false,
                    "body",
                    "1",
                    null
            );

            assertEquals("1", dto.defaultValue());
            assertNull(dto.allowedValues());
        }

        @Test
        @DisplayName("should carry allowedValues array when provided (drives dropdown)")
        void shouldCarryAllowedValuesArray() {
            WorkflowParameterDTO dto = new WorkflowParameterDTO(
                    "model",
                    "Model ID",
                    "string",
                    true,
                    "body",
                    null,
                    List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1")
            );

            assertNotNull(dto.allowedValues());
            assertEquals(3, dto.allowedValues().size());
            assertTrue(dto.allowedValues().contains("gpt-4o-mini"));
        }

        @Test
        @DisplayName("should support both scalar default + allowedValues simultaneously")
        void shouldSupportBothDefaultAndAllowed() {
            WorkflowParameterDTO dto = new WorkflowParameterDTO(
                    "size",
                    "Image size",
                    "string",
                    false,
                    "body",
                    "1024x1024",
                    List.of("1024x1024", "1792x1024", "1024x1792")
            );

            assertEquals("1024x1024", dto.defaultValue());
            assertEquals(3, dto.allowedValues().size());
        }

        @Test
        @DisplayName("should create body parameter")
        void shouldCreateBodyParameter() {
            WorkflowParameterDTO dto = new WorkflowParameterDTO(
                    "payload",
                    "Request body",
                    "object",
                    true,
                    "body",
                    null,
                    null
            );

            assertEquals("body", dto.parameterType());
        }

        @Test
        @DisplayName("should create query parameter")
        void shouldCreateQueryParameter() {
            WorkflowParameterDTO dto = new WorkflowParameterDTO(
                    "page",
                    "Page number",
                    "integer",
                    false,
                    "query",
                    null,
                    null
            );

            assertEquals("query", dto.parameterType());
            assertFalse(dto.isRequired());
        }

        @Test
        @DisplayName("should create header parameter")
        void shouldCreateHeaderParameter() {
            WorkflowParameterDTO dto = new WorkflowParameterDTO(
                    "Authorization",
                    "Bearer token",
                    "string",
                    true,
                    "header",
                    null,
                    null
            );

            assertEquals("header", dto.parameterType());
        }

        @Test
        @DisplayName("should allow null fields")
        void shouldAllowNullFields() {
            WorkflowParameterDTO dto = new WorkflowParameterDTO(null, null, null, null, null, null, null);

            assertNull(dto.name());
            assertNull(dto.description());
            assertNull(dto.dataType());
            assertNull(dto.isRequired());
            assertNull(dto.parameterType());
            assertNull(dto.defaultValue());
            assertNull(dto.allowedValues());
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
            WorkflowParameterDTO dto1 = new WorkflowParameterDTO("name", "desc", "string", true, "query", null, null);
            WorkflowParameterDTO dto2 = new WorkflowParameterDTO("name", "desc", "string", true, "query", null, null);

            assertEquals(dto1, dto2);
            assertEquals(dto1.hashCode(), dto2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different values")
        void shouldNotBeEqualForDifferentValues() {
            WorkflowParameterDTO dto1 = new WorkflowParameterDTO("name1", "desc", "string", true, "query", null, null);
            WorkflowParameterDTO dto2 = new WorkflowParameterDTO("name2", "desc", "string", true, "query", null, null);

            assertNotEquals(dto1, dto2);
        }

        @Test
        @DisplayName("should differ when allowedValues differ")
        void shouldDifferOnAllowedValues() {
            WorkflowParameterDTO dto1 = new WorkflowParameterDTO("p", "d", "string", false, "body", null, List.of("a"));
            WorkflowParameterDTO dto2 = new WorkflowParameterDTO("p", "d", "string", false, "body", null, List.of("b"));

            assertNotEquals(dto1, dto2);
        }
    }
}

package com.apimarketplace.catalog.mapping.service;

import com.apimarketplace.catalog.mapping.generator.MappingGenerationException;
import com.apimarketplace.catalog.mapping.generator.StrictMappingConstraints;
import com.apimarketplace.catalog.mapping.generator.StrictMappingGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MappingGeneratorService class.
 *
 * MappingGeneratorService orchestrates the mapping generation process
 * with caching, validation, and error handling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MappingGeneratorService")
class MappingGeneratorServiceTest {

    @Mock
    private StrictMappingGenerator mappingGenerator;

    private MappingGeneratorService service;

    @BeforeEach
    void setUp() {
        service = new MappingGeneratorService(mappingGenerator);
    }

    // ========================================================================
    // generateStrictMapping tests - basic
    // ========================================================================

    @Nested
    @DisplayName("generateStrictMapping() - basic")
    class GenerateStrictMappingBasicTests {

        @Test
        @DisplayName("should generate mapping with constraints")
        void shouldGenerateMappingWithConstraints() throws Exception {
            String sampleJson = "{\"name\": \"John\", \"age\": 30}";
            StrictMappingConstraints constraints = new StrictMappingConstraints();
            String expectedMapping = "{\"source\": {\"format\": \"json\"}, \"fields\": {}}";

            when(mappingGenerator.isAvailable()).thenReturn(true);
            when(mappingGenerator.generateStrictMapping(eq(sampleJson), any(StrictMappingConstraints.class)))
                    .thenReturn(expectedMapping);

            String result = service.generateStrictMapping(sampleJson, constraints);

            assertEquals(expectedMapping, result);
            verify(mappingGenerator).isAvailable();
            verify(mappingGenerator).generateStrictMapping(eq(sampleJson), any(StrictMappingConstraints.class));
        }

        @Test
        @DisplayName("should generate mapping with default constraints")
        void shouldGenerateMappingWithDefaultConstraints() throws Exception {
            String sampleJson = "{\"name\": \"John\"}";
            String expectedMapping = "{\"source\": {\"format\": \"json\"}, \"fields\": {}}";

            when(mappingGenerator.isAvailable()).thenReturn(true);
            when(mappingGenerator.generateStrictMapping(eq(sampleJson), any(StrictMappingConstraints.class)))
                    .thenReturn(expectedMapping);

            String result = service.generateStrictMapping(sampleJson);

            assertEquals(expectedMapping, result);
        }

        @Test
        @DisplayName("should generate mapping with items path")
        void shouldGenerateMappingWithItemsPath() throws Exception {
            String sampleJson = "{\"data\": [{\"id\": 1}]}";
            String itemsPath = "$.data[*]";
            String expectedMapping = "{\"source\": {\"format\": \"json\", \"items_path\": \"$.data[*]\"}, \"fields\": {}}";

            when(mappingGenerator.isAvailable()).thenReturn(true);
            when(mappingGenerator.generateStrictMapping(eq(sampleJson), any(StrictMappingConstraints.class)))
                    .thenReturn(expectedMapping);

            String result = service.generateStrictMapping(sampleJson, itemsPath);

            assertEquals(expectedMapping, result);
        }
    }

    // ========================================================================
    // generateStrictMapping tests - errors
    // ========================================================================

    @Nested
    @DisplayName("generateStrictMapping() - errors")
    class GenerateStrictMappingErrorTests {

        @Test
        @DisplayName("should throw when generator is not available")
        void shouldThrowWhenGeneratorNotAvailable() {
            String sampleJson = "{\"name\": \"John\"}";
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            when(mappingGenerator.isAvailable()).thenReturn(false);

            MappingGenerationException exception = assertThrows(MappingGenerationException.class, () ->
                    service.generateStrictMapping(sampleJson, constraints)
            );

            assertNotNull(exception.getMessage());
        }

        @Test
        @DisplayName("should wrap generator exception")
        void shouldWrapGeneratorException() throws Exception {
            String sampleJson = "{\"name\": \"John\"}";
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            when(mappingGenerator.isAvailable()).thenReturn(true);
            when(mappingGenerator.generateStrictMapping(anyString(), any(StrictMappingConstraints.class)))
                    .thenThrow(new RuntimeException("Generator error"));

            MappingGenerationException exception = assertThrows(MappingGenerationException.class, () ->
                    service.generateStrictMapping(sampleJson, constraints)
            );

            assertTrue(exception.getMessage().contains("Failed to generate"));
        }
    }

    // ========================================================================
    // generateStrictMappingWithContext tests
    // ========================================================================

    @Nested
    @DisplayName("generateStrictMappingWithContext()")
    class GenerateStrictMappingWithContextTests {

        @Test
        @DisplayName("should generate mapping with full tool context")
        void shouldGenerateMappingWithFullToolContext() throws Exception {
            String sampleJson = "{\"name\": \"John\"}";
            StrictMappingConstraints constraints = new StrictMappingConstraints();
            String toolName = "GetUser";
            String categoryName = "Users";
            String subCategoryName = "Management";
            String httpMethod = "GET";
            String endpoint = "/api/users/{id}";
            String description = "Get user by ID";
            String expectedMapping = "{\"source\": {\"format\": \"json\"}, \"fields\": {}}";

            when(mappingGenerator.isAvailable()).thenReturn(true);
            when(mappingGenerator.generateStrictMappingWithContext(
                    eq(sampleJson),
                    any(StrictMappingConstraints.class),
                    eq(toolName),
                    eq(categoryName),
                    eq(subCategoryName),
                    eq(httpMethod),
                    eq(endpoint),
                    eq(description)
            )).thenReturn(expectedMapping);

            String result = service.generateStrictMappingWithContext(
                    sampleJson, constraints, toolName, categoryName, subCategoryName,
                    httpMethod, endpoint, description
            );

            assertEquals(expectedMapping, result);
            verify(mappingGenerator).generateStrictMappingWithContext(
                    eq(sampleJson),
                    any(StrictMappingConstraints.class),
                    eq(toolName),
                    eq(categoryName),
                    eq(subCategoryName),
                    eq(httpMethod),
                    eq(endpoint),
                    eq(description)
            );
        }

        @Test
        @DisplayName("should throw when generator not available for context mapping")
        void shouldThrowWhenGeneratorNotAvailableForContext() {
            String sampleJson = "{\"name\": \"John\"}";
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            when(mappingGenerator.isAvailable()).thenReturn(false);

            MappingGenerationException exception = assertThrows(MappingGenerationException.class, () ->
                    service.generateStrictMappingWithContext(
                            sampleJson, constraints, "Tool", "Category", "SubCategory",
                            "GET", "/api", "Description"
                    )
            );

            assertNotNull(exception.getMessage());
        }

        @Test
        @DisplayName("should wrap exception when context mapping fails")
        void shouldWrapExceptionWhenContextMappingFails() throws Exception {
            String sampleJson = "{\"name\": \"John\"}";
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            when(mappingGenerator.isAvailable()).thenReturn(true);
            when(mappingGenerator.generateStrictMappingWithContext(
                    anyString(), any(), any(), any(), any(), any(), any(), any()
            )).thenThrow(new RuntimeException("Context mapping error"));

            MappingGenerationException exception = assertThrows(MappingGenerationException.class, () ->
                    service.generateStrictMappingWithContext(
                            sampleJson, constraints, "Tool", "Category", "SubCategory",
                            "GET", "/api", "Description"
                    )
            );

            assertTrue(exception.getMessage().contains("Failed to generate"));
        }
    }

    // ========================================================================
    // isGeneratorAvailable tests
    // ========================================================================

    @Nested
    @DisplayName("isGeneratorAvailable()")
    class IsGeneratorAvailableTests {

        @Test
        @DisplayName("should return true when generator is available")
        void shouldReturnTrueWhenAvailable() {
            when(mappingGenerator.isAvailable()).thenReturn(true);

            boolean result = service.isGeneratorAvailable();

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when generator is not available")
        void shouldReturnFalseWhenNotAvailable() {
            when(mappingGenerator.isAvailable()).thenReturn(false);

            boolean result = service.isGeneratorAvailable();

            assertFalse(result);
        }
    }
}

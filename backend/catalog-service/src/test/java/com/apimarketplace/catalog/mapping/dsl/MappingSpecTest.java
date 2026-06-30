package com.apimarketplace.catalog.mapping.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MappingSpec class.
 *
 * MappingSpec is the main mapping specification that defines how to extract data.
 */
@DisplayName("MappingSpec")
class MappingSpecTest {

    // ========================================================================
    // Default constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Default constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create spec with null values")
        void shouldCreateSpecWithNullValues() {
            MappingSpec spec = new MappingSpec();

            assertNull(spec.getSource());
            assertNull(spec.getFields());
            assertNull(spec.getGlobals());
        }
    }

    // ========================================================================
    // Source tests
    // ========================================================================

    @Nested
    @DisplayName("Source")
    class SourceTests {

        @Test
        @DisplayName("should get and set source")
        void shouldGetAndSetSource() {
            MappingSpec spec = new MappingSpec();
            SourceSpec source = new SourceSpec("json", "$.data");

            spec.setSource(source);

            assertNotNull(spec.getSource());
            assertEquals("json", spec.getSource().getFormat());
            assertEquals("$.data", spec.getSource().getRoot());
        }

        @Test
        @DisplayName("should allow null source")
        void shouldAllowNullSource() {
            MappingSpec spec = new MappingSpec();
            spec.setSource(new SourceSpec());

            spec.setSource(null);

            assertNull(spec.getSource());
        }
    }

    // ========================================================================
    // Fields tests
    // ========================================================================

    @Nested
    @DisplayName("Fields")
    class FieldsTests {

        @Test
        @DisplayName("should get and set fields")
        void shouldGetAndSetFields() {
            MappingSpec spec = new MappingSpec();
            Map<String, FieldSpec> fields = new HashMap<>();
            fields.put("id", new FieldSpec(List.of("$.id"), "integer"));
            fields.put("name", new FieldSpec(List.of("$.name"), "string"));

            spec.setFields(fields);

            assertNotNull(spec.getFields());
            assertEquals(2, spec.getFields().size());
            assertNotNull(spec.getFields().get("id"));
            assertNotNull(spec.getFields().get("name"));
        }

        @Test
        @DisplayName("should allow empty fields map")
        void shouldAllowEmptyFieldsMap() {
            MappingSpec spec = new MappingSpec();

            spec.setFields(Map.of());

            assertNotNull(spec.getFields());
            assertTrue(spec.getFields().isEmpty());
        }

        @Test
        @DisplayName("should allow null fields")
        void shouldAllowNullFields() {
            MappingSpec spec = new MappingSpec();
            spec.setFields(Map.of("test", new FieldSpec()));

            spec.setFields(null);

            assertNull(spec.getFields());
        }
    }

    // ========================================================================
    // Globals tests
    // ========================================================================

    @Nested
    @DisplayName("Globals")
    class GlobalsTests {

        @Test
        @DisplayName("should get and set globals")
        void shouldGetAndSetGlobals() {
            MappingSpec spec = new MappingSpec();
            Map<String, FieldSpec> globals = new HashMap<>();
            globals.put("totalCount", new FieldSpec(List.of("$.total"), "integer"));
            globals.put("pageSize", new FieldSpec(List.of("$.limit"), "integer"));

            spec.setGlobals(globals);

            assertNotNull(spec.getGlobals());
            assertEquals(2, spec.getGlobals().size());
            assertNotNull(spec.getGlobals().get("totalCount"));
            assertNotNull(spec.getGlobals().get("pageSize"));
        }

        @Test
        @DisplayName("should allow empty globals map")
        void shouldAllowEmptyGlobalsMap() {
            MappingSpec spec = new MappingSpec();

            spec.setGlobals(Map.of());

            assertNotNull(spec.getGlobals());
            assertTrue(spec.getGlobals().isEmpty());
        }
    }

    // ========================================================================
    // Complete mapping tests
    // ========================================================================

    @Nested
    @DisplayName("Complete mapping")
    class CompleteMappingTests {

        @Test
        @DisplayName("should create complete mapping spec")
        void shouldCreateCompleteMappingSpec() {
            MappingSpec spec = new MappingSpec();

            // Set source
            SourceSpec source = new SourceSpec("json", "$.data");
            source.setItemsPath("$.items");
            spec.setSource(source);

            // Set fields (for items)
            Map<String, FieldSpec> fields = new HashMap<>();
            fields.put("id", new FieldSpec(List.of("$.id"), "integer"));
            fields.put("name", new FieldSpec(List.of("$.name", "$.title"), "string"));
            fields.put("active", new FieldSpec(List.of("$.active"), "boolean", false, false));
            spec.setFields(fields);

            // Set globals (outside items)
            Map<String, FieldSpec> globals = new HashMap<>();
            globals.put("total", new FieldSpec(List.of("$.total", "$.count"), "integer"));
            spec.setGlobals(globals);

            // Verify
            assertNotNull(spec.getSource());
            assertEquals("json", spec.getSource().getFormat());
            assertEquals("$.items", spec.getSource().getItemsPath());

            assertEquals(3, spec.getFields().size());
            assertEquals("integer", spec.getFields().get("id").getTo());
            assertEquals(2, spec.getFields().get("name").getCandidates().size());

            assertEquals(1, spec.getGlobals().size());
            assertNotNull(spec.getGlobals().get("total"));
        }

        @Test
        @DisplayName("should support different source formats")
        void shouldSupportDifferentSourceFormats() {
            MappingSpec jsonSpec = new MappingSpec();
            jsonSpec.setSource(new SourceSpec("json", "$"));

            MappingSpec xmlSpec = new MappingSpec();
            xmlSpec.setSource(new SourceSpec("xml", "/root"));

            MappingSpec csvSpec = new MappingSpec();
            csvSpec.setSource(new SourceSpec("csv", null));

            assertEquals("json", jsonSpec.getSource().getFormat());
            assertEquals("xml", xmlSpec.getSource().getFormat());
            assertEquals("csv", csvSpec.getSource().getFormat());
        }
    }
}

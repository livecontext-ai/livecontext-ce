package com.apimarketplace.catalog.mapping.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FieldSpec class.
 *
 * FieldSpec defines how to extract and transform a specific field in mapping.
 */
@DisplayName("FieldSpec")
class FieldSpecTest {

    // ========================================================================
    // Default constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Default constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create spec with default values")
        void shouldCreateSpecWithDefaultValues() {
            FieldSpec spec = new FieldSpec();

            assertNull(spec.getCandidates());
            assertNull(spec.getPathAnyOf());
            assertNull(spec.getTo());
            assertNull(spec.getDefaultValue());
            assertFalse(spec.getRequired());
            assertNull(spec.getMap());
            assertEquals(3, spec.getMaxFallbacks());
        }
    }

    // ========================================================================
    // Parameterized constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Parameterized constructors")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("should create spec with candidates and target type")
        void shouldCreateSpecWithCandidatesAndTargetType() {
            List<String> candidates = List.of("$.field1", "$.field2");

            FieldSpec spec = new FieldSpec(candidates, "string");

            assertEquals(candidates, spec.getCandidates());
            assertEquals("string", spec.getTo());
            assertNull(spec.getDefaultValue());
            assertFalse(spec.getRequired());
        }

        @Test
        @DisplayName("should create spec with all required parameters")
        void shouldCreateSpecWithAllRequiredParameters() {
            List<String> candidates = List.of("$.id");

            FieldSpec spec = new FieldSpec(candidates, "integer", 0, true);

            assertEquals(candidates, spec.getCandidates());
            assertEquals("integer", spec.getTo());
            assertEquals(0, spec.getDefaultValue());
            assertTrue(spec.getRequired());
        }
    }

    // ========================================================================
    // Getter and Setter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set candidates")
        void shouldGetAndSetCandidates() {
            FieldSpec spec = new FieldSpec();
            List<String> candidates = List.of("$.path1", "$.path2", "$.path3");

            spec.setCandidates(candidates);

            assertEquals(candidates, spec.getCandidates());
            assertEquals(3, spec.getCandidates().size());
        }

        @Test
        @DisplayName("should get and set pathAnyOf")
        void shouldGetAndSetPathAnyOf() {
            FieldSpec spec = new FieldSpec();
            List<String> paths = List.of("data.items", "results.items");

            spec.setPathAnyOf(paths);

            assertEquals(paths, spec.getPathAnyOf());
        }

        @Test
        @DisplayName("should get and set to (target type)")
        void shouldGetAndSetTo() {
            FieldSpec spec = new FieldSpec();

            spec.setTo("boolean");

            assertEquals("boolean", spec.getTo());
        }

        @Test
        @DisplayName("should get and set defaultValue")
        void shouldGetAndSetDefaultValue() {
            FieldSpec spec = new FieldSpec();

            spec.setDefaultValue("N/A");

            assertEquals("N/A", spec.getDefaultValue());
        }

        @Test
        @DisplayName("should get and set required")
        void shouldGetAndSetRequired() {
            FieldSpec spec = new FieldSpec();

            spec.setRequired(true);

            assertTrue(spec.getRequired());
        }

        @Test
        @DisplayName("should get and set map")
        void shouldGetAndSetMap() {
            FieldSpec spec = new FieldSpec();
            Map<String, String> map = Map.of("key", "$.items[*].key", "value", "$.items[*].value");

            spec.setMap(map);

            assertEquals(map, spec.getMap());
        }

        @Test
        @DisplayName("should get and set maxFallbacks")
        void shouldGetAndSetMaxFallbacks() {
            FieldSpec spec = new FieldSpec();

            spec.setMaxFallbacks(5);

            assertEquals(5, spec.getMaxFallbacks());
        }
    }

    // ========================================================================
    // Target type values tests
    // ========================================================================

    @Nested
    @DisplayName("Target types")
    class TargetTypeTests {

        @Test
        @DisplayName("should accept integer target type")
        void shouldAcceptIntegerTargetType() {
            FieldSpec spec = new FieldSpec(List.of("$.id"), "integer");

            assertEquals("integer", spec.getTo());
        }

        @Test
        @DisplayName("should accept boolean target type")
        void shouldAcceptBooleanTargetType() {
            FieldSpec spec = new FieldSpec(List.of("$.active"), "boolean");

            assertEquals("boolean", spec.getTo());
        }

        @Test
        @DisplayName("should accept datetime target type")
        void shouldAcceptDatetimeTargetType() {
            FieldSpec spec = new FieldSpec(List.of("$.createdAt"), "datetime");

            assertEquals("datetime", spec.getTo());
        }

        @Test
        @DisplayName("should accept uri target type")
        void shouldAcceptUriTargetType() {
            FieldSpec spec = new FieldSpec(List.of("$.url"), "uri");

            assertEquals("uri", spec.getTo());
        }

        @Test
        @DisplayName("should accept string target type")
        void shouldAcceptStringTargetType() {
            FieldSpec spec = new FieldSpec(List.of("$.name"), "string");

            assertEquals("string", spec.getTo());
        }
    }

    // ========================================================================
    // Default value types tests
    // ========================================================================

    @Nested
    @DisplayName("Default value types")
    class DefaultValueTypeTests {

        @Test
        @DisplayName("should accept string default value")
        void shouldAcceptStringDefaultValue() {
            FieldSpec spec = new FieldSpec();

            spec.setDefaultValue("unknown");

            assertEquals("unknown", spec.getDefaultValue());
        }

        @Test
        @DisplayName("should accept integer default value")
        void shouldAcceptIntegerDefaultValue() {
            FieldSpec spec = new FieldSpec();

            spec.setDefaultValue(42);

            assertEquals(42, spec.getDefaultValue());
        }

        @Test
        @DisplayName("should accept boolean default value")
        void shouldAcceptBooleanDefaultValue() {
            FieldSpec spec = new FieldSpec();

            spec.setDefaultValue(false);

            assertEquals(false, spec.getDefaultValue());
        }

        @Test
        @DisplayName("should accept null default value")
        void shouldAcceptNullDefaultValue() {
            FieldSpec spec = new FieldSpec();
            spec.setDefaultValue("initial");

            spec.setDefaultValue(null);

            assertNull(spec.getDefaultValue());
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
            FieldSpec spec = new FieldSpec(List.of("$.name"), "string", "default", true);

            String str = spec.toString();

            assertNotNull(str);
            assertTrue(str.contains("FieldSpec"));
            assertTrue(str.contains("candidates"));
            assertTrue(str.contains("$.name"));
            assertTrue(str.contains("to='string'"));
            assertTrue(str.contains("required=true"));
        }
    }
}

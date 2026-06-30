package com.apimarketplace.catalog.mapping.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SourceSpec class.
 *
 * SourceSpec defines the root path and format-specific details for mapping.
 */
@DisplayName("SourceSpec")
class SourceSpecTest {

    // ========================================================================
    // Default constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Default constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create spec with default values")
        void shouldCreateSpecWithDefaultValues() {
            SourceSpec spec = new SourceSpec();

            assertNull(spec.getFormat());
            assertNull(spec.getRoot());
            assertNull(spec.getRootAnyOf());
            assertNull(spec.getRootMatch());
            assertNull(spec.getItemsPath());
            assertTrue(spec.getStrictMode());
        }
    }

    // ========================================================================
    // Parameterized constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Parameterized constructors")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("should create spec with format and root")
        void shouldCreateSpecWithFormatAndRoot() {
            SourceSpec spec = new SourceSpec("json", "$.data");

            assertEquals("json", spec.getFormat());
            assertEquals("$.data", spec.getRoot());
        }

        @Test
        @DisplayName("should create spec with all parameters")
        void shouldCreateSpecWithAllParameters() {
            List<String> rootAnyOf = List.of("$.data", "$.results");
            Map<String, Object> rootMatch = Map.of("type", "array");

            SourceSpec spec = new SourceSpec("json", "$.data", rootAnyOf, rootMatch);

            assertEquals("json", spec.getFormat());
            assertEquals("$.data", spec.getRoot());
            assertEquals(rootAnyOf, spec.getRootAnyOf());
            assertEquals(rootMatch, spec.getRootMatch());
        }
    }

    // ========================================================================
    // Getter and Setter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set format")
        void shouldGetAndSetFormat() {
            SourceSpec spec = new SourceSpec();

            spec.setFormat("xml");

            assertEquals("xml", spec.getFormat());
        }

        @Test
        @DisplayName("should get and set root")
        void shouldGetAndSetRoot() {
            SourceSpec spec = new SourceSpec();

            spec.setRoot("/root/items");

            assertEquals("/root/items", spec.getRoot());
        }

        @Test
        @DisplayName("should get and set rootAnyOf")
        void shouldGetAndSetRootAnyOf() {
            SourceSpec spec = new SourceSpec();
            List<String> roots = List.of("$.data", "$.items", "$.results");

            spec.setRootAnyOf(roots);

            assertEquals(roots, spec.getRootAnyOf());
            assertEquals(3, spec.getRootAnyOf().size());
        }

        @Test
        @DisplayName("should get and set rootMatch")
        void shouldGetAndSetRootMatch() {
            SourceSpec spec = new SourceSpec();
            Map<String, Object> match = Map.of("type", "object", "hasItems", true);

            spec.setRootMatch(match);

            assertEquals(match, spec.getRootMatch());
        }

        @Test
        @DisplayName("should get and set itemsPath")
        void shouldGetAndSetItemsPath() {
            SourceSpec spec = new SourceSpec();

            spec.setItemsPath("$.data.items");

            assertEquals("$.data.items", spec.getItemsPath());
        }

        @Test
        @DisplayName("should get and set strictMode")
        void shouldGetAndSetStrictMode() {
            SourceSpec spec = new SourceSpec();

            spec.setStrictMode(false);

            assertFalse(spec.getStrictMode());
        }

        @Test
        @DisplayName("should get and set rootAlternatives")
        void shouldGetAndSetRootAlternatives() {
            SourceSpec spec = new SourceSpec();
            List<String> alternatives = List.of("$.primary", "$.secondary");

            spec.setRootAlternatives(alternatives);

            assertEquals(alternatives, spec.getRootAlternatives());
        }
    }

    // ========================================================================
    // Root alternatives fallback tests
    // ========================================================================

    @Nested
    @DisplayName("Root alternatives fallback")
    class RootAlternativesFallbackTests {

        @Test
        @DisplayName("should return rootAlternatives when set")
        void shouldReturnRootAlternativesWhenSet() {
            SourceSpec spec = new SourceSpec();
            List<String> alternatives = List.of("$.alt1", "$.alt2");
            List<String> anyOf = List.of("$.any1", "$.any2");

            spec.setRootAlternatives(alternatives);
            spec.setRootAnyOf(anyOf);

            assertEquals(alternatives, spec.getRootAlternatives());
        }

        @Test
        @DisplayName("should fallback to rootAnyOf when rootAlternatives is null")
        void shouldFallbackToRootAnyOfWhenRootAlternativesIsNull() {
            SourceSpec spec = new SourceSpec();
            List<String> anyOf = List.of("$.fallback1", "$.fallback2");

            spec.setRootAnyOf(anyOf);

            assertEquals(anyOf, spec.getRootAlternatives());
        }

        @Test
        @DisplayName("should return null when both are null")
        void shouldReturnNullWhenBothAreNull() {
            SourceSpec spec = new SourceSpec();

            assertNull(spec.getRootAlternatives());
        }
    }

    // ========================================================================
    // Format values tests
    // ========================================================================

    @Nested
    @DisplayName("Format values")
    class FormatValuesTests {

        @Test
        @DisplayName("should accept json format")
        void shouldAcceptJsonFormat() {
            SourceSpec spec = new SourceSpec("json", "$");

            assertEquals("json", spec.getFormat());
        }

        @Test
        @DisplayName("should accept xml format")
        void shouldAcceptXmlFormat() {
            SourceSpec spec = new SourceSpec("xml", "/root");

            assertEquals("xml", spec.getFormat());
        }

        @Test
        @DisplayName("should accept html format")
        void shouldAcceptHtmlFormat() {
            SourceSpec spec = new SourceSpec("html", "//body");

            assertEquals("html", spec.getFormat());
        }

        @Test
        @DisplayName("should accept csv format")
        void shouldAcceptCsvFormat() {
            SourceSpec spec = new SourceSpec("csv", null);

            assertEquals("csv", spec.getFormat());
        }
    }

    // ========================================================================
    // Strict mode tests
    // ========================================================================

    @Nested
    @DisplayName("Strict mode")
    class StrictModeTests {

        @Test
        @DisplayName("should have strict mode enabled by default")
        void shouldHaveStrictModeEnabledByDefault() {
            SourceSpec spec = new SourceSpec();

            assertTrue(spec.getStrictMode());
        }

        @Test
        @DisplayName("should allow disabling strict mode")
        void shouldAllowDisablingStrictMode() {
            SourceSpec spec = new SourceSpec();

            spec.setStrictMode(false);

            assertFalse(spec.getStrictMode());
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
            SourceSpec spec = new SourceSpec("json", "$.data");
            spec.setItemsPath("$.items");
            spec.setStrictMode(true);

            String str = spec.toString();

            assertNotNull(str);
            assertTrue(str.contains("SourceSpec"));
            assertTrue(str.contains("format='json'"));
            assertTrue(str.contains("root='$.data'"));
            assertTrue(str.contains("itemsPath='$.items'"));
            assertTrue(str.contains("strictMode=true"));
        }
    }
}

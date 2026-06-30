package com.apimarketplace.catalog.mapping.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StrictMappingConstraints class.
 *
 * StrictMappingConstraints defines constraints for generating strict JSONPath mappings.
 */
@DisplayName("StrictMappingConstraints")
class StrictMappingConstraintsTest {

    // ========================================================================
    // Default constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Default constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create constraints with default values")
        void shouldCreateConstraintsWithDefaultValues() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            assertNull(constraints.getItemsPath());
            assertEquals(4, constraints.getMaxFallbacks());
            assertNull(constraints.getFieldWhitelist());
            assertNull(constraints.getFieldBlacklist());
            assertTrue(constraints.isPreferRelative());
            assertEquals("deepinfra", constraints.getModelProvider());
            assertEquals("llama3.1:8b", constraints.getModelName());
            assertEquals(300000, constraints.getTimeoutMs());
        }
    }

    // ========================================================================
    // Parameterized constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Parameterized constructors")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("should create constraints with items path")
        void shouldCreateConstraintsWithItemsPath() {
            StrictMappingConstraints constraints = new StrictMappingConstraints("$.data.items");

            assertEquals("$.data.items", constraints.getItemsPath());
            assertEquals(4, constraints.getMaxFallbacks());
        }

        @Test
        @DisplayName("should create constraints with items path and max fallbacks")
        void shouldCreateConstraintsWithItemsPathAndMaxFallbacks() {
            StrictMappingConstraints constraints = new StrictMappingConstraints("$.results", 10);

            assertEquals("$.results", constraints.getItemsPath());
            assertEquals(10, constraints.getMaxFallbacks());
        }
    }

    // ========================================================================
    // Getter and Setter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set itemsPath")
        void shouldGetAndSetItemsPath() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            constraints.setItemsPath("$.items[*]");

            assertEquals("$.items[*]", constraints.getItemsPath());
        }

        @Test
        @DisplayName("should get and set maxFallbacks")
        void shouldGetAndSetMaxFallbacks() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            constraints.setMaxFallbacks(8);

            assertEquals(8, constraints.getMaxFallbacks());
        }

        @Test
        @DisplayName("should get and set fieldWhitelist")
        void shouldGetAndSetFieldWhitelist() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();
            Set<String> whitelist = Set.of("id", "name", "email");

            constraints.setFieldWhitelist(whitelist);

            assertEquals(whitelist, constraints.getFieldWhitelist());
            assertTrue(constraints.getFieldWhitelist().contains("id"));
        }

        @Test
        @DisplayName("should get and set fieldBlacklist")
        void shouldGetAndSetFieldBlacklist() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();
            Set<String> blacklist = Set.of("password", "secret", "token");

            constraints.setFieldBlacklist(blacklist);

            assertEquals(blacklist, constraints.getFieldBlacklist());
            assertTrue(constraints.getFieldBlacklist().contains("password"));
        }

        @Test
        @DisplayName("should get and set preferRelative")
        void shouldGetAndSetPreferRelative() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            constraints.setPreferRelative(false);

            assertFalse(constraints.isPreferRelative());
        }

        @Test
        @DisplayName("should get and set modelProvider")
        void shouldGetAndSetModelProvider() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            constraints.setModelProvider("openai");

            assertEquals("openai", constraints.getModelProvider());
        }

        @Test
        @DisplayName("should get and set modelName")
        void shouldGetAndSetModelName() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            constraints.setModelName("gpt-4");

            assertEquals("gpt-4", constraints.getModelName());
        }

        @Test
        @DisplayName("should get and set timeoutMs")
        void shouldGetAndSetTimeoutMs() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            constraints.setTimeoutMs(60000);

            assertEquals(60000, constraints.getTimeoutMs());
        }
    }

    // ========================================================================
    // Default value tests
    // ========================================================================

    @Nested
    @DisplayName("Default values")
    class DefaultValueTests {

        @Test
        @DisplayName("should have default max fallbacks of 4")
        void shouldHaveDefaultMaxFallbacksOf4() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            assertEquals(4, constraints.getMaxFallbacks());
        }

        @Test
        @DisplayName("should prefer relative paths by default")
        void shouldPreferRelativePathsByDefault() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            assertTrue(constraints.isPreferRelative());
        }

        @Test
        @DisplayName("should use deepinfra as default provider")
        void shouldUseDeepinfraAsDefaultProvider() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            assertEquals("deepinfra", constraints.getModelProvider());
        }

        @Test
        @DisplayName("should use llama3.1:8b as default model")
        void shouldUseLlama31AsDefaultModel() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            assertEquals("llama3.1:8b", constraints.getModelName());
        }

        @Test
        @DisplayName("should have 5 minute default timeout")
        void shouldHave5MinuteDefaultTimeout() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();

            assertEquals(300000, constraints.getTimeoutMs());
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
            StrictMappingConstraints constraints = new StrictMappingConstraints("$.items", 5);
            constraints.setPreferRelative(true);
            constraints.setModelProvider("custom");
            constraints.setModelName("model-v1");

            String str = constraints.toString();

            assertNotNull(str);
            assertTrue(str.contains("StrictMappingConstraints"));
            assertTrue(str.contains("itemsPath='$.items'"));
            assertTrue(str.contains("maxFallbacks=5"));
            assertTrue(str.contains("preferRelative=true"));
            assertTrue(str.contains("modelProvider='custom'"));
            assertTrue(str.contains("modelName='model-v1'"));
        }

        @Test
        @DisplayName("should include whitelist and blacklist in toString")
        void shouldIncludeListsInToString() {
            StrictMappingConstraints constraints = new StrictMappingConstraints();
            constraints.setFieldWhitelist(Set.of("name"));
            constraints.setFieldBlacklist(Set.of("password"));

            String str = constraints.toString();

            assertTrue(str.contains("fieldWhitelist"));
            assertTrue(str.contains("fieldBlacklist"));
        }
    }
}

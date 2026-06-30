package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolCard record.
 *
 * ToolCard represents a tool card for the model with concise information.
 */
@DisplayName("ToolCard")
class ToolCardTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {

        @Test
        @DisplayName("should create record with all fields")
        void shouldCreateRecordWithAllFields() {
            // Arrange
            String name = "weatherApi";
            String description = "Get weather data for a location";
            Map<String, String> required = Map.of("city", "The city name");
            Map<String, String> optional = Map.of("units", "Temperature units (celsius/fahrenheit)");
            String platform = "REST";
            String reliability = "HIGH";

            // Act
            ToolCard card = new ToolCard(name, description, required, optional, platform, reliability);

            // Assert
            assertEquals(name, card.name());
            assertEquals(description, card.description());
            assertEquals(required, card.requiredParams());
            assertEquals(optional, card.optionalParams());
            assertEquals(platform, card.platform());
            assertEquals(reliability, card.reliability());
        }

        @Test
        @DisplayName("should accept null values")
        void shouldAcceptNullValues() {
            ToolCard card = new ToolCard(null, null, null, null, null, null);

            assertNull(card.name());
            assertNull(card.description());
            assertNull(card.requiredParams());
            assertNull(card.optionalParams());
            assertNull(card.platform());
            assertNull(card.reliability());
        }
    }

    // ========================================================================
    // Factory method of() with empty params tests
    // ========================================================================

    @Nested
    @DisplayName("of() factory method with empty params")
    class OfEmptyParamsTests {

        @Test
        @DisplayName("should create tool card with empty parameters")
        void shouldCreateToolCardWithEmptyParameters() {
            // Act
            ToolCard card = ToolCard.of("testTool", "Test description", "MCP", "MEDIUM");

            // Assert
            assertEquals("testTool", card.name());
            assertEquals("Test description", card.description());
            assertEquals("MCP", card.platform());
            assertEquals("MEDIUM", card.reliability());
            assertTrue(card.requiredParams().isEmpty());
            assertTrue(card.optionalParams().isEmpty());
        }

        @Test
        @DisplayName("should create immutable empty parameter maps")
        void shouldCreateImmutableEmptyParameterMaps() {
            ToolCard card = ToolCard.of("tool", "desc", "platform", "HIGH");

            // Map.of() returns immutable map
            assertThrows(UnsupportedOperationException.class, () ->
                card.requiredParams().put("key", "value")
            );
            assertThrows(UnsupportedOperationException.class, () ->
                card.optionalParams().put("key", "value")
            );
        }
    }

    // ========================================================================
    // Factory method of() with params tests
    // ========================================================================

    @Nested
    @DisplayName("of() factory method with params")
    class OfWithParamsTests {

        @Test
        @DisplayName("should create tool card with parameters")
        void shouldCreateToolCardWithParameters() {
            // Arrange
            Map<String, String> required = Map.of("param1", "Description 1");
            Map<String, String> optional = Map.of("param2", "Description 2");

            // Act
            ToolCard card = ToolCard.of(
                "myTool",
                "My tool description",
                required,
                optional,
                "GraphQL",
                "LOW"
            );

            // Assert
            assertEquals("myTool", card.name());
            assertEquals("My tool description", card.description());
            assertEquals(required, card.requiredParams());
            assertEquals(optional, card.optionalParams());
            assertEquals("GraphQL", card.platform());
            assertEquals("LOW", card.reliability());
        }

        @Test
        @DisplayName("should handle empty maps as parameters")
        void shouldHandleEmptyMapsAsParameters() {
            ToolCard card = ToolCard.of(
                "tool", "desc", Map.of(), Map.of(), "platform", "HIGH"
            );

            assertTrue(card.requiredParams().isEmpty());
            assertTrue(card.optionalParams().isEmpty());
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
            Map<String, String> params = Map.of("key", "value");

            ToolCard card1 = new ToolCard("name", "desc", params, params, "platform", "HIGH");
            ToolCard card2 = new ToolCard("name", "desc", params, params, "platform", "HIGH");

            assertEquals(card1, card2);
            assertEquals(card1.hashCode(), card2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different values")
        void shouldNotBeEqualForDifferentValues() {
            ToolCard card1 = ToolCard.of("tool1", "desc", "platform", "HIGH");
            ToolCard card2 = ToolCard.of("tool2", "desc", "platform", "HIGH");

            assertNotEquals(card1, card2);
        }

        @Test
        @DisplayName("should have different hash codes for different values")
        void shouldHaveDifferentHashCodesForDifferentValues() {
            ToolCard card1 = ToolCard.of("tool1", "desc1", "p1", "LOW");
            ToolCard card2 = ToolCard.of("tool2", "desc2", "p2", "HIGH");

            // Not guaranteed but very likely for different values
            assertNotEquals(card1.hashCode(), card2.hashCode());
        }
    }
}

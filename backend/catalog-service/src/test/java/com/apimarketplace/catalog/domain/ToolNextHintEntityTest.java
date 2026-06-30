package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolNextHintEntity class.
 *
 * ToolNextHintEntity stores hints for the LLM about what to do after using a tool.
 * Uses Lombok for boilerplate generation.
 */
@DisplayName("ToolNextHintEntity Tests")
class ToolNextHintEntityTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entity with no-arg constructor")
        void shouldCreateEntityWithNoArgConstructor() {
            // Act
            ToolNextHintEntity entity = new ToolNextHintEntity();

            // Assert
            assertNull(entity.getId());
            assertNull(entity.getApiToolId());
            assertNull(entity.getToolNameId());
            assertNull(entity.getHint());
            assertNull(entity.getNextToolName());
            assertNull(entity.getNextToolId());
            assertNull(entity.getPriority());
            assertNull(entity.getConditionExpression());
            assertNull(entity.getIsActive());
        }

        @Test
        @DisplayName("Should create entity with all-args constructor")
        void shouldCreateEntityWithAllArgsConstructor() {
            // Arrange
            UUID id = UUID.randomUUID();
            UUID apiToolId = UUID.randomUUID();
            UUID toolNameId = UUID.randomUUID();
            UUID nextToolId = UUID.randomUUID();
            Long createdAt = System.currentTimeMillis();
            Long updatedAt = System.currentTimeMillis();

            // Act
            ToolNextHintEntity entity = new ToolNextHintEntity(
                id,
                apiToolId,
                toolNameId,
                "If you need more details, use the detail tool",
                "get_user_details",
                nextToolId,
                1,
                "{{response.hasMore}} == true",
                true,
                createdAt,
                updatedAt
            );

            // Assert
            assertEquals(id, entity.getId());
            assertEquals(apiToolId, entity.getApiToolId());
            assertEquals(toolNameId, entity.getToolNameId());
            assertEquals("If you need more details, use the detail tool", entity.getHint());
            assertEquals("get_user_details", entity.getNextToolName());
            assertEquals(nextToolId, entity.getNextToolId());
            assertEquals(1, entity.getPriority());
            assertEquals("{{response.hasMore}} == true", entity.getConditionExpression());
            assertTrue(entity.getIsActive());
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS AND SETTERS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters and Setters Tests")
    class GettersSettersTests {

        @Test
        @DisplayName("Should set and get id")
        void shouldSetAndGetId() {
            // Arrange
            ToolNextHintEntity entity = new ToolNextHintEntity();
            UUID id = UUID.randomUUID();

            // Act
            entity.setId(id);

            // Assert
            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("Should set and get apiToolId")
        void shouldSetAndGetApiToolId() {
            // Arrange
            ToolNextHintEntity entity = new ToolNextHintEntity();
            UUID apiToolId = UUID.randomUUID();

            // Act
            entity.setApiToolId(apiToolId);

            // Assert
            assertEquals(apiToolId, entity.getApiToolId());
        }

        @Test
        @DisplayName("Should set and get hint")
        void shouldSetAndGetHint() {
            // Arrange
            ToolNextHintEntity entity = new ToolNextHintEntity();

            // Act
            entity.setHint("Use this tool to get more details");

            // Assert
            assertEquals("Use this tool to get more details", entity.getHint());
        }

        @Test
        @DisplayName("Should set and get nextToolName")
        void shouldSetAndGetNextToolName() {
            // Arrange
            ToolNextHintEntity entity = new ToolNextHintEntity();

            // Act
            entity.setNextToolName("get_weather_forecast");

            // Assert
            assertEquals("get_weather_forecast", entity.getNextToolName());
        }

        @Test
        @DisplayName("Should set and get priority")
        void shouldSetAndGetPriority() {
            // Arrange
            ToolNextHintEntity entity = new ToolNextHintEntity();

            // Act
            entity.setPriority(10);

            // Assert
            assertEquals(10, entity.getPriority());
        }

        @Test
        @DisplayName("Should set and get conditionExpression")
        void shouldSetAndGetConditionExpression() {
            // Arrange
            ToolNextHintEntity entity = new ToolNextHintEntity();

            // Act
            entity.setConditionExpression("{{result.count}} > 0");

            // Assert
            assertEquals("{{result.count}} > 0", entity.getConditionExpression());
        }

        @Test
        @DisplayName("Should set and get isActive")
        void shouldSetAndGetIsActive() {
            // Arrange
            ToolNextHintEntity entity = new ToolNextHintEntity();

            // Act
            entity.setIsActive(true);

            // Assert
            assertTrue(entity.getIsActive());
        }

        @Test
        @DisplayName("Should set and get timestamps")
        void shouldSetAndGetTimestamps() {
            // Arrange
            ToolNextHintEntity entity = new ToolNextHintEntity();
            Long createdAt = 1704067200000L;
            Long updatedAt = 1704153600000L;

            // Act
            entity.setCreatedAt(createdAt);
            entity.setUpdatedAt(updatedAt);

            // Assert
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EQUALS AND HASHCODE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Equals and HashCode Tests")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal when all fields match")
        void shouldBeEqualWhenAllFieldsMatch() {
            // Arrange
            UUID id = UUID.randomUUID();
            UUID apiToolId = UUID.randomUUID();

            ToolNextHintEntity entity1 = new ToolNextHintEntity();
            entity1.setId(id);
            entity1.setApiToolId(apiToolId);
            entity1.setHint("hint");
            entity1.setPriority(1);

            ToolNextHintEntity entity2 = new ToolNextHintEntity();
            entity2.setId(id);
            entity2.setApiToolId(apiToolId);
            entity2.setHint("hint");
            entity2.setPriority(1);

            // Assert
            assertEquals(entity1, entity2);
            assertEquals(entity1.hashCode(), entity2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when id differs")
        void shouldNotBeEqualWhenIdDiffers() {
            // Arrange
            ToolNextHintEntity entity1 = new ToolNextHintEntity();
            entity1.setId(UUID.randomUUID());

            ToolNextHintEntity entity2 = new ToolNextHintEntity();
            entity2.setId(UUID.randomUUID());

            // Assert
            assertNotEquals(entity1, entity2);
        }

        @Test
        @DisplayName("Should not be equal when hint differs")
        void shouldNotBeEqualWhenHintDiffers() {
            // Arrange
            UUID id = UUID.randomUUID();

            ToolNextHintEntity entity1 = new ToolNextHintEntity();
            entity1.setId(id);
            entity1.setHint("hint1");

            ToolNextHintEntity entity2 = new ToolNextHintEntity();
            entity2.setId(id);
            entity2.setHint("hint2");

            // Assert
            assertNotEquals(entity1, entity2);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NULL HANDLING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Null Handling Tests")
    class NullHandlingTests {

        @Test
        @DisplayName("Should handle null values in setters")
        void shouldHandleNullValuesInSetters() {
            // Arrange
            ToolNextHintEntity entity = new ToolNextHintEntity();

            // Act
            entity.setHint(null);
            entity.setNextToolName(null);
            entity.setConditionExpression(null);
            entity.setPriority(null);
            entity.setIsActive(null);

            // Assert
            assertNull(entity.getHint());
            assertNull(entity.getNextToolName());
            assertNull(entity.getConditionExpression());
            assertNull(entity.getPriority());
            assertNull(entity.getIsActive());
        }
    }
}

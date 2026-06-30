package com.apimarketplace.catalog.service.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiSubmissionCommand record.
 *
 * ApiSubmissionCommand wraps API submission payload with helper methods.
 */
@DisplayName("ApiSubmissionCommand")
class ApiSubmissionCommandTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class RecordConstructionTests {

        @Test
        @DisplayName("should create command with all fields")
        void shouldCreateCommandWithAllFields() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("apiName", "Test API");
            String userId = "user-123";
            List<JsonNode> tools = List.of(objectMapper.createObjectNode().put("name", "tool1"));

            // Act
            ApiSubmissionCommand command = new ApiSubmissionCommand(payload, userId, tools);

            // Assert
            assertEquals(payload, command.payload());
            assertEquals(userId, command.userId());
            assertEquals(tools, command.tools());
        }

        @Test
        @DisplayName("should create command with null tools list")
        void shouldCreateCommandWithNullToolsList() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            String userId = "user-123";

            // Act
            ApiSubmissionCommand command = new ApiSubmissionCommand(payload, userId, null);

            // Assert
            assertNull(command.tools());
        }
    }

    // ========================================================================
    // Compact constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Compact constructor")
    class CompactConstructorTests {

        @Test
        @DisplayName("should extract tools from payload with mcpTools array")
        void shouldExtractToolsFromPayloadWithMcpToolsArray() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            ArrayNode mcpTools = payload.putArray("mcpTools");
            mcpTools.addObject().put("name", "Tool 1").put("endpoint", "/api/v1");
            mcpTools.addObject().put("name", "Tool 2").put("endpoint", "/api/v2");
            String userId = "user-456";

            // Act
            ApiSubmissionCommand command = new ApiSubmissionCommand(payload, userId);

            // Assert
            assertEquals(2, command.tools().size());
            assertEquals("Tool 1", command.tools().get(0).get("name").asText());
            assertEquals("Tool 2", command.tools().get(1).get("name").asText());
        }

        @Test
        @DisplayName("should return empty list when mcpTools is missing")
        void shouldReturnEmptyListWhenMcpToolsIsMissing() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("apiName", "Test API");
            String userId = "user-789";

            // Act
            ApiSubmissionCommand command = new ApiSubmissionCommand(payload, userId);

            // Assert
            assertTrue(command.tools().isEmpty());
        }

        @Test
        @DisplayName("should return empty list when mcpTools is not an array")
        void shouldReturnEmptyListWhenMcpToolsIsNotAnArray() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("mcpTools", "not an array");
            String userId = "user-abc";

            // Act
            ApiSubmissionCommand command = new ApiSubmissionCommand(payload, userId);

            // Assert
            assertTrue(command.tools().isEmpty());
        }

        @Test
        @DisplayName("should return empty list when payload is null")
        void shouldReturnEmptyListWhenPayloadIsNull() {
            // Act
            ApiSubmissionCommand command = new ApiSubmissionCommand(null, "user-xyz");

            // Assert
            assertTrue(command.tools().isEmpty());
        }

        @Test
        @DisplayName("should handle empty mcpTools array")
        void shouldHandleEmptyMcpToolsArray() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            payload.putArray("mcpTools"); // Empty array
            String userId = "user-empty";

            // Act
            ApiSubmissionCommand command = new ApiSubmissionCommand(payload, userId);

            // Assert
            assertTrue(command.tools().isEmpty());
        }
    }

    // ========================================================================
    // apiName() tests
    // ========================================================================

    @Nested
    @DisplayName("apiName()")
    class ApiNameTests {

        @Test
        @DisplayName("should return apiName from payload")
        void shouldReturnApiNameFromPayload() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("apiName", "My Custom API");
            ApiSubmissionCommand command = new ApiSubmissionCommand(payload, "user");

            // Act
            String result = command.apiName();

            // Assert
            assertEquals("My Custom API", result);
        }

        @Test
        @DisplayName("should return default when apiName is missing")
        void shouldReturnDefaultWhenApiNameIsMissing() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("description", "Some description");
            ApiSubmissionCommand command = new ApiSubmissionCommand(payload, "user");

            // Act
            String result = command.apiName();

            // Assert
            assertEquals("Unnamed API", result);
        }

        @Test
        @DisplayName("should throw NPE when payload is null")
        void shouldThrowNpeWhenPayloadIsNull() {
            // Arrange
            ApiSubmissionCommand command = new ApiSubmissionCommand(null, "user");

            // Act & Assert - payload.path() throws NPE when payload is null
            assertThrows(NullPointerException.class, command::apiName);
        }
    }

    // ========================================================================
    // apiDescription() tests
    // ========================================================================

    @Nested
    @DisplayName("apiDescription()")
    class ApiDescriptionTests {

        @Test
        @DisplayName("should return apiDescription from payload")
        void shouldReturnApiDescriptionFromPayload() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("apiDescription", "This API provides weather data");
            ApiSubmissionCommand command = new ApiSubmissionCommand(payload, "user");

            // Act
            String result = command.apiDescription();

            // Assert
            assertEquals("This API provides weather data", result);
        }

        @Test
        @DisplayName("should return default when apiDescription is missing")
        void shouldReturnDefaultWhenApiDescriptionIsMissing() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("apiName", "Test API");
            ApiSubmissionCommand command = new ApiSubmissionCommand(payload, "user");

            // Act
            String result = command.apiDescription();

            // Assert
            assertEquals("No description provided", result);
        }

        @Test
        @DisplayName("should throw NPE when payload is null")
        void shouldThrowNpeWhenPayloadIsNullForDescription() {
            // Arrange
            ApiSubmissionCommand command = new ApiSubmissionCommand(null, "user");

            // Act & Assert - payload.path() throws NPE when payload is null
            assertThrows(NullPointerException.class, command::apiDescription);
        }
    }

    // ========================================================================
    // Tools immutability tests
    // ========================================================================

    @Nested
    @DisplayName("Tools immutability")
    class ToolsImmutabilityTests {

        @Test
        @DisplayName("should return unmodifiable list for tools")
        void shouldReturnUnmodifiableListForTools() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            ArrayNode mcpTools = payload.putArray("mcpTools");
            mcpTools.addObject().put("name", "Tool 1");
            ApiSubmissionCommand command = new ApiSubmissionCommand(payload, "user");

            // Act & Assert
            List<JsonNode> tools = command.tools();
            assertThrows(UnsupportedOperationException.class, () -> tools.add(objectMapper.createObjectNode()));
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
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("apiName", "Test");
            List<JsonNode> tools = List.of();

            ApiSubmissionCommand cmd1 = new ApiSubmissionCommand(payload, "user", tools);
            ApiSubmissionCommand cmd2 = new ApiSubmissionCommand(payload, "user", tools);

            // Assert
            assertEquals(cmd1, cmd2);
            assertEquals(cmd1.hashCode(), cmd2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different user IDs")
        void shouldNotBeEqualForDifferentUserIds() {
            // Arrange
            ObjectNode payload = objectMapper.createObjectNode();
            List<JsonNode> tools = List.of();

            ApiSubmissionCommand cmd1 = new ApiSubmissionCommand(payload, "user1", tools);
            ApiSubmissionCommand cmd2 = new ApiSubmissionCommand(payload, "user2", tools);

            // Assert
            assertNotEquals(cmd1, cmd2);
        }
    }
}

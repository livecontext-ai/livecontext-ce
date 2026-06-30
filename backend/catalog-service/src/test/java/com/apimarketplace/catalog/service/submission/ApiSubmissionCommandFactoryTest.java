package com.apimarketplace.catalog.service.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiSubmissionCommandFactory.
 *
 * ApiSubmissionCommandFactory creates ApiSubmissionCommand instances with validation.
 */
@DisplayName("ApiSubmissionCommandFactory")
class ApiSubmissionCommandFactoryTest {

    private ApiSubmissionCommandFactory factory;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        factory = new ApiSubmissionCommandFactory();
        objectMapper = new ObjectMapper();
    }

    // ========================================================================
    // Valid input tests
    // ========================================================================

    @Nested
    @DisplayName("Valid inputs")
    class ValidInputTests {

        @Test
        @DisplayName("should create command with valid submission data and userId")
        void shouldCreateCommandWithValidInputs() {
            // Arrange
            ObjectNode submissionData = objectMapper.createObjectNode();
            submissionData.put("apiName", "Weather API");
            submissionData.put("apiDescription", "Provides weather data");
            String userId = "user-123";

            // Act
            ApiSubmissionCommand command = factory.from(submissionData, userId);

            // Assert
            assertNotNull(command);
            assertEquals(submissionData, command.payload());
            assertEquals(userId, command.userId());
        }

        @Test
        @DisplayName("should extract tools from submission data")
        void shouldExtractToolsFromSubmissionData() {
            // Arrange
            ObjectNode submissionData = objectMapper.createObjectNode();
            submissionData.put("apiName", "Multi-Tool API");
            ArrayNode mcpTools = submissionData.putArray("mcpTools");
            mcpTools.addObject().put("name", "Tool 1").put("endpoint", "/api/v1/tool1");
            mcpTools.addObject().put("name", "Tool 2").put("endpoint", "/api/v1/tool2");
            String userId = "user-456";

            // Act
            ApiSubmissionCommand command = factory.from(submissionData, userId);

            // Assert
            assertNotNull(command);
            assertEquals(2, command.tools().size());
        }

        @Test
        @DisplayName("should create command with empty submission data")
        void shouldCreateCommandWithEmptySubmissionData() {
            // Arrange
            ObjectNode submissionData = objectMapper.createObjectNode();
            String userId = "user-789";

            // Act
            ApiSubmissionCommand command = factory.from(submissionData, userId);

            // Assert
            assertNotNull(command);
            assertEquals(submissionData, command.payload());
            assertTrue(command.tools().isEmpty());
        }
    }

    // ========================================================================
    // Null submission data tests
    // ========================================================================

    @Nested
    @DisplayName("Null submission data")
    class NullSubmissionDataTests {

        @Test
        @DisplayName("should throw IllegalArgumentException when submissionData is null")
        void shouldThrowWhenSubmissionDataIsNull() {
            // Arrange
            String userId = "user-123";

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.from(null, userId)
            );
            assertEquals("submissionData is required", exception.getMessage());
        }
    }

    // ========================================================================
    // Invalid userId tests
    // ========================================================================

    @Nested
    @DisplayName("Invalid userId")
    class InvalidUserIdTests {

        @Test
        @DisplayName("should throw IllegalArgumentException when userId is null")
        void shouldThrowWhenUserIdIsNull() {
            // Arrange
            ObjectNode submissionData = objectMapper.createObjectNode();

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.from(submissionData, null)
            );
            assertEquals("userId is required to process the submission", exception.getMessage());
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when userId is empty")
        void shouldThrowWhenUserIdIsEmpty() {
            // Arrange
            ObjectNode submissionData = objectMapper.createObjectNode();

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.from(submissionData, "")
            );
            assertEquals("userId is required to process the submission", exception.getMessage());
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when userId is blank")
        void shouldThrowWhenUserIdIsBlank() {
            // Arrange
            ObjectNode submissionData = objectMapper.createObjectNode();

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.from(submissionData, "   ")
            );
            assertEquals("userId is required to process the submission", exception.getMessage());
        }
    }

    // ========================================================================
    // Command behavior verification tests
    // ========================================================================

    @Nested
    @DisplayName("Command behavior verification")
    class CommandBehaviorTests {

        @Test
        @DisplayName("should create command that returns correct apiName")
        void shouldCreateCommandWithCorrectApiName() {
            // Arrange
            ObjectNode submissionData = objectMapper.createObjectNode();
            submissionData.put("apiName", "Custom API Name");

            // Act
            ApiSubmissionCommand command = factory.from(submissionData, "user");

            // Assert
            assertEquals("Custom API Name", command.apiName());
        }

        @Test
        @DisplayName("should create command that returns default apiName when missing")
        void shouldCreateCommandWithDefaultApiName() {
            // Arrange
            ObjectNode submissionData = objectMapper.createObjectNode();

            // Act
            ApiSubmissionCommand command = factory.from(submissionData, "user");

            // Assert
            assertEquals("Unnamed API", command.apiName());
        }

        @Test
        @DisplayName("should create command that returns correct apiDescription")
        void shouldCreateCommandWithCorrectApiDescription() {
            // Arrange
            ObjectNode submissionData = objectMapper.createObjectNode();
            submissionData.put("apiDescription", "Detailed API description");

            // Act
            ApiSubmissionCommand command = factory.from(submissionData, "user");

            // Assert
            assertEquals("Detailed API description", command.apiDescription());
        }

        @Test
        @DisplayName("should create command that returns default apiDescription when missing")
        void shouldCreateCommandWithDefaultApiDescription() {
            // Arrange
            ObjectNode submissionData = objectMapper.createObjectNode();

            // Act
            ApiSubmissionCommand command = factory.from(submissionData, "user");

            // Assert
            assertEquals("No description provided", command.apiDescription());
        }
    }
}

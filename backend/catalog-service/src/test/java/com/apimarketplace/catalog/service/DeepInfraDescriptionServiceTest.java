package com.apimarketplace.catalog.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeepInfraDescriptionService.
 *
 * DeepInfraDescriptionService generates AI-powered descriptions via DeepInfra API.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeepInfraDescriptionService")
class DeepInfraDescriptionServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private DeepInfraDescriptionService service;

    @BeforeEach
    void setUp() {
        service = new DeepInfraDescriptionService(restTemplate);
        // Set required configuration values
        ReflectionTestUtils.setField(service, "deepInfraApiKey", "test-api-key");
        ReflectionTestUtils.setField(service, "deepInfraBaseUrl", "https://api.deepinfra.com/v1/openai/chat/completions");
        ReflectionTestUtils.setField(service, "modelName", "Qwen/Qwen2.5-72B-Instruct");
        ReflectionTestUtils.setField(service, "maxTokens", 40096);
        ReflectionTestUtils.setField(service, "temperature", 0.1);
        ReflectionTestUtils.setField(service, "topP", 0.95);
        ReflectionTestUtils.setField(service, "topK", 50);
        ReflectionTestUtils.setField(service, "presencePenalty", 0.0);
        ReflectionTestUtils.setField(service, "frequencyPenalty", 0.02);
        ReflectionTestUtils.setField(service, "seed", 0L);
        ReflectionTestUtils.setField(service, "forceJsonResponse", true);
        ReflectionTestUtils.setField(service, "jsonMode", true);
        ReflectionTestUtils.setField(service, "responseFormat", "json_object");
    }

    // ========================================================================
    // generateOptimizedDescription tests
    // ========================================================================

    @Nested
    @DisplayName("generateOptimizedDescription()")
    class GenerateOptimizedDescriptionTests {

        @Test
        @DisplayName("should generate description successfully with valid response")
        void shouldGenerateDescriptionSuccessfully() {
            // Arrange
            String prompt = "Generate a description for weather API";
            String expectedJson = "{\"content\":\"Weather API description\"}";
            Map<String, Object> responseBody = createSuccessResponse(expectedJson);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            String result = service.generateOptimizedDescription(prompt);

            // Assert
            assertEquals(expectedJson, result);
            verify(restTemplate).exchange(
                eq("https://api.deepinfra.com/v1/openai/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
            );
        }

        @Test
        @DisplayName("should throw exception when API key is not configured")
        void shouldThrowWhenApiKeyNotConfigured() {
            // Arrange
            ReflectionTestUtils.setField(service, "deepInfraApiKey", "");
            String prompt = "Test prompt";

            // Act & Assert
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.generateOptimizedDescription(prompt)
            );
            assertTrue(exception.getMessage().contains("DeepInfra API key not configured"));
        }

        @Test
        @DisplayName("should throw exception when API key is null")
        void shouldThrowWhenApiKeyIsNull() {
            // Arrange
            ReflectionTestUtils.setField(service, "deepInfraApiKey", null);
            String prompt = "Test prompt";

            // Act & Assert
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.generateOptimizedDescription(prompt)
            );
            assertTrue(exception.getMessage().contains("DeepInfra API key not configured"));
        }

        @Test
        @DisplayName("should clean markdown code blocks from response")
        void shouldCleanMarkdownCodeBlocksFromResponse() {
            // Arrange
            String prompt = "Test prompt";
            String jsonWithMarkdown = "```json\n{\"content\":\"test\"}\n```";
            Map<String, Object> responseBody = createSuccessResponse(jsonWithMarkdown);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            String result = service.generateOptimizedDescription(prompt);

            // Assert
            assertEquals("{\"content\":\"test\"}", result);
        }

        @Test
        @DisplayName("should throw exception when response is not valid JSON")
        void shouldThrowWhenResponseIsNotValidJson() {
            // Arrange
            String prompt = "Test prompt";
            String invalidJson = "not valid json";
            Map<String, Object> responseBody = createSuccessResponse(invalidJson);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act & Assert
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.generateOptimizedDescription(prompt)
            );
            assertTrue(exception.getMessage().contains("invalid JSON"));
        }

        @Test
        @DisplayName("should throw exception when response body is null")
        void shouldThrowWhenResponseBodyIsNull() {
            // Arrange
            String prompt = "Test prompt";

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
            )).thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

            // Act & Assert
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.generateOptimizedDescription(prompt)
            );
            assertTrue(exception.getMessage().contains("Unexpected response format"));
        }

        @Test
        @DisplayName("should throw exception when API call fails")
        void shouldThrowWhenApiCallFails() {
            // Arrange
            String prompt = "Test prompt";

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
            )).thenThrow(new RestClientException("Connection failed"));

            // Act & Assert - RestClientException extends RuntimeException so it's re-thrown directly
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.generateOptimizedDescription(prompt)
            );
            assertTrue(exception.getMessage().contains("Connection failed"));
        }

        @Test
        @DisplayName("should include presence penalty when non-zero")
        void shouldIncludePresencePenaltyWhenNonZero() {
            // Arrange
            ReflectionTestUtils.setField(service, "presencePenalty", 0.5);
            String prompt = "Test prompt";
            String expectedJson = "{\"result\":\"ok\"}";
            Map<String, Object> responseBody = createSuccessResponse(expectedJson);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            String result = service.generateOptimizedDescription(prompt);

            // Assert
            assertEquals(expectedJson, result);
            // Verify API was called (presence penalty included in request)
            verify(restTemplate).exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
            );
        }

        @Test
        @DisplayName("should include seed when greater than zero")
        void shouldIncludeSeedWhenGreaterThanZero() {
            // Arrange
            ReflectionTestUtils.setField(service, "seed", 42L);
            String prompt = "Test prompt";
            String expectedJson = "{\"result\":\"ok\"}";
            Map<String, Object> responseBody = createSuccessResponse(expectedJson);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            String result = service.generateOptimizedDescription(prompt);

            // Assert
            assertEquals(expectedJson, result);
        }
    }

    // ========================================================================
    // generateAISummaryAndAction tests
    // ========================================================================

    @Nested
    @DisplayName("generateAISummaryAndAction()")
    class GenerateAISummaryAndActionTests {

        @Test
        @DisplayName("should generate AI summary with valid input")
        void shouldGenerateAISummaryWithValidInput() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            String description = "API endpoint for fetching user data";
            String expectedJson = "{\"action\":\"get\",\"summary\":\"Fetches user data\"}";
            Map<String, Object> responseBody = createSuccessResponse(expectedJson);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            String result = service.generateAISummaryAndAction(toolId, description);

            // Assert
            assertEquals(expectedJson, result);
        }

        @Test
        @DisplayName("should throw exception when AI generation fails")
        void shouldThrowWhenAIGenerationFails() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            String description = "Test description";

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
            )).thenThrow(new RestClientException("API error"));

            // Act & Assert
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.generateAISummaryAndAction(toolId, description)
            );
            assertTrue(exception.getMessage().contains("Failed to generate AI synthesis"));
            assertTrue(exception.getMessage().contains(toolId.toString()));
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private Map<String, Object> createSuccessResponse(String content) {
        return Map.of(
            "choices", List.of(
                Map.of(
                    "message", Map.of("content", content)
                )
            )
        );
    }
}

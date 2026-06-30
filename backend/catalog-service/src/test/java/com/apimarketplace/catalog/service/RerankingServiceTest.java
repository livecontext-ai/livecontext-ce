package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.config.SearchConfig;
import com.apimarketplace.catalog.dto.CapabilityCard;
import com.apimarketplace.catalog.util.SearchScoreClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RerankingService.
 *
 * RerankingService reranks search results using cross-encoder or LLM-based scoring.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RerankingService")
class RerankingServiceTest {

    @Mock
    private SearchConfig searchConfig;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private SearchConfig.RerankingConfig rerankingConfig;

    private ObjectMapper objectMapper;

    private RerankingService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new RerankingService(searchConfig, objectMapper, restTemplate);
        // Clear all API keys by default
        ReflectionTestUtils.setField(service, "openaiApiKey", "");
        ReflectionTestUtils.setField(service, "cohereApiKey", "");
        ReflectionTestUtils.setField(service, "deepinfraToken", "");
    }

    // ========================================================================
    // rerank - disabled tests
    // ========================================================================

    @Nested
    @DisplayName("rerank() - disabled")
    class RerankDisabledTests {

        @Test
        @DisplayName("should return original order when reranking is disabled")
        void shouldReturnOriginalOrderWhenDisabled() {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(false);

            List<CapabilityCard> cards = createTestCards(3);

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(cards, result);
            verify(restTemplate, never()).exchange(anyString(), any(), any(), any(Class.class));
        }
    }

    // ========================================================================
    // rerank - edge cases tests
    // ========================================================================

    @Nested
    @DisplayName("rerank() - edge cases")
    class RerankEdgeCasesTests {

        @Test
        @DisplayName("should return empty list when input is empty")
        void shouldReturnEmptyListWhenInputIsEmpty() {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);

            // Act
            List<CapabilityCard> result = service.rerank("test query", List.of());

            // Assert
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return single card when input has only one card")
        void shouldReturnSingleCardWhenInputHasOnlyOneCard() {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);

            List<CapabilityCard> cards = createTestCards(1);

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(1, result.size());
            assertEquals(cards.get(0), result.get(0));
        }

        @Test
        @DisplayName("should return original order when no API key is available")
        void shouldReturnOriginalOrderWhenNoApiKeyAvailable() {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(cards, result);
        }
    }

    // ========================================================================
    // rerank - Cohere tests
    // ========================================================================

    @Nested
    @DisplayName("rerank() - Cohere")
    class RerankCohereTests {

        @BeforeEach
        void setUpCohere() {
            ReflectionTestUtils.setField(service, "cohereApiKey", "test-cohere-key");
        }

        @Test
        @DisplayName("should use Cohere when cohereApiKey is set")
        void shouldUseCohereWhenApiKeyIsSet() throws Exception {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            String cohereResponse = "{\"results\":[{\"index\":2,\"relevance_score\":0.95},{\"index\":0,\"relevance_score\":0.8},{\"index\":1,\"relevance_score\":0.6}]}";
            JsonNode responseBody = objectMapper.readTree(cohereResponse);

            when(restTemplate.exchange(
                eq("https://api.cohere.ai/v1/rerank"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(3, result.size());
            verify(restTemplate).exchange(
                eq("https://api.cohere.ai/v1/rerank"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            );
        }

        @Test
        @DisplayName("should reorder cards based on Cohere relevance scores")
        void shouldReorderCardsBasedOnCohereRelevanceScores() throws Exception {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            // Cohere returns results with index 1 as most relevant
            String cohereResponse = "{\"results\":[{\"index\":1,\"relevance_score\":0.95},{\"index\":2,\"relevance_score\":0.8},{\"index\":0,\"relevance_score\":0.6}]}";
            JsonNode responseBody = objectMapper.readTree(cohereResponse);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(3, result.size());
            assertEquals("Tool 1", result.get(0).name()); // Index 1 should be first
        }

        @Test
        @DisplayName("should return original order when Cohere fails")
        void shouldReturnOriginalOrderWhenCohereFails() {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenThrow(new RestClientException("API error"));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(cards, result);
        }

        @Test
        @DisplayName("should return original order when Cohere response is malformed")
        void shouldReturnOriginalOrderWhenCohereResponseIsMalformed() throws Exception {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            String malformedResponse = "{\"error\":\"something went wrong\"}";
            JsonNode responseBody = objectMapper.readTree(malformedResponse);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(cards, result);
        }
    }

    // ========================================================================
    // rerank - DeepInfra tests
    // ========================================================================

    @Nested
    @DisplayName("rerank() - DeepInfra")
    class RerankDeepInfraTests {

        @BeforeEach
        void setUpDeepInfra() {
            ReflectionTestUtils.setField(service, "deepinfraToken", "test-deepinfra-token");
        }

        @Test
        @DisplayName("should use DeepInfra when token is set and no Cohere key")
        void shouldUseDeepInfraWhenTokenIsSet() throws Exception {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            String llmResponse = "{\"choices\":[{\"message\":{\"content\":\"[1, 0, 2]\"}}]}";
            JsonNode responseBody = objectMapper.readTree(llmResponse);

            when(restTemplate.exchange(
                eq("https://api.deepinfra.com/v1/openai/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(3, result.size());
            verify(restTemplate).exchange(
                eq("https://api.deepinfra.com/v1/openai/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            );
        }

        @Test
        @DisplayName("should reorder cards based on LLM response indices")
        void shouldReorderCardsBasedOnLLMResponseIndices() throws Exception {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            // LLM returns indices with 2 as most relevant
            String llmResponse = "{\"choices\":[{\"message\":{\"content\":\"[2, 0, 1]\"}}]}";
            JsonNode responseBody = objectMapper.readTree(llmResponse);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(3, result.size());
            assertEquals("Tool 2", result.get(0).name()); // Index 2 should be first
        }
    }

    // ========================================================================
    // rerank - OpenAI tests
    // ========================================================================

    @Nested
    @DisplayName("rerank() - OpenAI")
    class RerankOpenAITests {

        @BeforeEach
        void setUpOpenAI() {
            ReflectionTestUtils.setField(service, "openaiApiKey", "test-openai-key");
        }

        @Test
        @DisplayName("should use OpenAI when key is set and no Cohere/DeepInfra")
        void shouldUseOpenAIWhenKeyIsSet() throws Exception {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            String llmResponse = "{\"choices\":[{\"message\":{\"content\":\"[0, 1, 2]\"}}]}";
            JsonNode responseBody = objectMapper.readTree(llmResponse);

            when(restTemplate.exchange(
                eq("https://api.openai.com/v1/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(3, result.size());
            verify(restTemplate).exchange(
                eq("https://api.openai.com/v1/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            );
        }

        @Test
        @DisplayName("should return original order when LLM fails")
        void shouldReturnOriginalOrderWhenLLMFails() {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenThrow(new RestClientException("API error"));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(cards, result);
        }
    }

    // ========================================================================
    // rerank - topK tests
    // ========================================================================

    @Nested
    @DisplayName("rerank() - topK")
    class RerankTopKTests {

        @BeforeEach
        void setUpDeepInfra() {
            ReflectionTestUtils.setField(service, "deepinfraToken", "test-token");
        }

        @Test
        @DisplayName("should only rerank topK cards and preserve remaining")
        void shouldOnlyRerankTopKCardsAndPreserveRemaining() throws Exception {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(2); // Only rerank top 2

            List<CapabilityCard> cards = createTestCards(5);

            String llmResponse = "{\"choices\":[{\"message\":{\"content\":\"[1, 0]\"}}]}";
            JsonNode responseBody = objectMapper.readTree(llmResponse);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(5, result.size());
            // First 2 should be reranked
            assertEquals("Tool 1", result.get(0).name()); // Index 1 from reranking
            // Last 3 should be in original order
            assertEquals("Tool 2", result.get(2).name());
            assertEquals("Tool 3", result.get(3).name());
            assertEquals("Tool 4", result.get(4).name());
        }

        @Test
        @DisplayName("should use cards size when topK is larger than cards size")
        void shouldUseCardsSizeWhenTopKIsLarger() throws Exception {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(100); // Larger than cards

            List<CapabilityCard> cards = createTestCards(3);

            String llmResponse = "{\"choices\":[{\"message\":{\"content\":\"[2, 1, 0]\"}}]}";
            JsonNode responseBody = objectMapper.readTree(llmResponse);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(3, result.size());
        }
    }

    // ========================================================================
    // rerank - LLM response handling tests
    // ========================================================================

    @Nested
    @DisplayName("rerank() - LLM response handling")
    class RerankLLMResponseHandlingTests {

        @BeforeEach
        void setUpDeepInfra() {
            ReflectionTestUtils.setField(service, "deepinfraToken", "test-token");
        }

        @Test
        @DisplayName("should handle partial LLM response")
        void shouldHandlePartialLLMResponse() throws Exception {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            // LLM only returns 2 indices instead of 3
            String llmResponse = "{\"choices\":[{\"message\":{\"content\":\"[1, 2]\"}}]}";
            JsonNode responseBody = objectMapper.readTree(llmResponse);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(3, result.size()); // Should include missing card
        }

        @Test
        @DisplayName("should handle invalid indices in LLM response")
        void shouldHandleInvalidIndicesInLLMResponse() throws Exception {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            // LLM returns invalid index (10 is out of bounds)
            String llmResponse = "{\"choices\":[{\"message\":{\"content\":\"[0, 10, 1]\"}}]}";
            JsonNode responseBody = objectMapper.readTree(llmResponse);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertTrue(result.size() <= 3);
        }

        @Test
        @DisplayName("should return original order when LLM response is not JSON array")
        void shouldReturnOriginalOrderWhenLLMResponseIsNotArray() throws Exception {
            // Arrange
            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            String llmResponse = "{\"choices\":[{\"message\":{\"content\":\"not an array\"}}]}";
            JsonNode responseBody = objectMapper.readTree(llmResponse);

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            List<CapabilityCard> result = service.rerank("test query", cards);

            // Assert
            assertEquals(cards, result);
        }
    }

    // ========================================================================
    // API priority tests
    // ========================================================================

    @Nested
    @DisplayName("API priority")
    class ApiPriorityTests {

        @Test
        @DisplayName("should prefer Cohere over DeepInfra")
        void shouldPreferCohereOverDeepInfra() throws Exception {
            // Arrange
            ReflectionTestUtils.setField(service, "cohereApiKey", "cohere-key");
            ReflectionTestUtils.setField(service, "deepinfraToken", "deepinfra-token");

            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            String cohereResponse = "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.8},{\"index\":2,\"relevance_score\":0.7}]}";
            JsonNode responseBody = objectMapper.readTree(cohereResponse);

            when(restTemplate.exchange(
                eq("https://api.cohere.ai/v1/rerank"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            service.rerank("test query", cards);

            // Assert - should call Cohere, not DeepInfra
            verify(restTemplate).exchange(
                eq("https://api.cohere.ai/v1/rerank"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            );
        }

        @Test
        @DisplayName("should prefer DeepInfra over OpenAI")
        void shouldPreferDeepInfraOverOpenAI() throws Exception {
            // Arrange
            ReflectionTestUtils.setField(service, "deepinfraToken", "deepinfra-token");
            ReflectionTestUtils.setField(service, "openaiApiKey", "openai-key");

            when(searchConfig.getReranking()).thenReturn(rerankingConfig);
            when(rerankingConfig.isEnabled()).thenReturn(true);
            when(rerankingConfig.getTopK()).thenReturn(10);

            List<CapabilityCard> cards = createTestCards(3);

            String llmResponse = "{\"choices\":[{\"message\":{\"content\":\"[0, 1, 2]\"}}]}";
            JsonNode responseBody = objectMapper.readTree(llmResponse);

            when(restTemplate.exchange(
                eq("https://api.deepinfra.com/v1/openai/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            // Act
            service.rerank("test query", cards);

            // Assert - should call DeepInfra, not OpenAI
            verify(restTemplate).exchange(
                eq("https://api.deepinfra.com/v1/openai/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
            );
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private List<CapabilityCard> createTestCards(int count) {
        List<CapabilityCard> cards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cards.add(new CapabilityCard(
                "tool-" + i,
                "Tool " + i,
                "Provider " + i,
                List.of("param" + i),
                Map.of("key" + i, "value" + i),
                false,
                0.8 - (i * 0.1), // Decreasing scores
                SearchScoreClassifier.Quality.GOOD,
                "Good",
                70
            ));
        }
        return cards;
    }
}

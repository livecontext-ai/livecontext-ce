package com.apimarketplace.catalog.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CapabilityResponse record.
 *
 * CapabilityResponse is a DTO for capability search responses.
 */
@DisplayName("CapabilityResponse")
class CapabilityResponseTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class RecordConstructionTests {

        @Test
        @DisplayName("should create response with all fields")
        void shouldCreateResponseWithAllFields() {
            // Arrange
            List<CapabilityCard> cards = List.of();
            Map<String, Object> meta = Map.of("total", 10);
            List<Map<String, Object>> knnResults = List.of(Map.of("score", 0.9));
            List<Map<String, Object>> lexicalResults = List.of(Map.of("bm25", 15.0));

            // Act
            CapabilityResponse response = new CapabilityResponse(cards, meta, knnResults, lexicalResults);

            // Assert
            assertEquals(cards, response.cards());
            assertEquals(meta, response.meta());
            assertEquals(knnResults, response.knnResults());
            assertEquals(lexicalResults, response.lexicalResults());
        }
    }

    // ========================================================================
    // Factory method tests - of(cards, meta)
    // ========================================================================

    @Nested
    @DisplayName("Factory method of(cards, meta)")
    class OfWithMetaTests {

        @Test
        @DisplayName("should create response with cards and metadata")
        void shouldCreateResponseWithCardsAndMetadata() {
            // Arrange
            List<CapabilityCard> cards = List.of();
            Map<String, Object> meta = Map.of("total", 5, "query", "test");

            // Act
            CapabilityResponse response = CapabilityResponse.of(cards, meta);

            // Assert
            assertEquals(cards, response.cards());
            assertEquals(meta, response.meta());
            assertTrue(response.knnResults().isEmpty());
            assertTrue(response.lexicalResults().isEmpty());
        }
    }

    // ========================================================================
    // Factory method tests - of(cards)
    // ========================================================================

    @Nested
    @DisplayName("Factory method of(cards)")
    class OfWithCardsOnlyTests {

        @Test
        @DisplayName("should create response with default metadata")
        void shouldCreateResponseWithDefaultMetadata() {
            // Arrange
            List<CapabilityCard> cards = List.of();

            // Act
            CapabilityResponse response = CapabilityResponse.of(cards);

            // Assert
            assertEquals(cards, response.cards());
            assertNotNull(response.meta());
            assertTrue(response.meta().containsKey("total"));
            assertTrue(response.meta().containsKey("timestamp"));
            assertTrue(response.meta().containsKey("version"));
            assertEquals(0, response.meta().get("total"));
        }

        @Test
        @DisplayName("should set total count from cards size")
        void shouldSetTotalCountFromCardsSize() {
            // Arrange - note: we can't easily create CapabilityCards without more setup,
            // so we test with empty list
            List<CapabilityCard> cards = List.of();

            // Act
            CapabilityResponse response = CapabilityResponse.of(cards);

            // Assert
            assertEquals(cards.size(), response.meta().get("total"));
        }
    }

    // ========================================================================
    // Factory method tests - of(cards, total, latencyMs, query)
    // ========================================================================

    @Nested
    @DisplayName("Factory method of(cards, total, latencyMs, query)")
    class OfWithQueryMetaTests {

        @Test
        @DisplayName("should create response with query metadata")
        void shouldCreateResponseWithQueryMetadata() {
            // Arrange
            List<CapabilityCard> cards = List.of();
            int total = 100;
            long latencyMs = 50L;
            String query = "find weather API";

            // Act
            CapabilityResponse response = CapabilityResponse.of(cards, total, latencyMs, query);

            // Assert
            assertEquals(cards, response.cards());
            assertNotNull(response.meta());
            assertEquals(total, response.meta().get("total"));
            assertEquals(cards.size(), response.meta().get("returned"));
            assertEquals(query, response.meta().get("query"));
            assertEquals(latencyMs, response.meta().get("latency_ms"));
        }
    }

    // ========================================================================
    // Factory method tests - of(cards, meta, knnResults, lexicalResults)
    // ========================================================================

    @Nested
    @DisplayName("Factory method of(cards, meta, knnResults, lexicalResults)")
    class OfWithDebugResultsTests {

        @Test
        @DisplayName("should create response with debug results")
        void shouldCreateResponseWithDebugResults() {
            // Arrange
            List<CapabilityCard> cards = List.of();
            Map<String, Object> meta = Map.of("debug", true);
            List<Map<String, Object>> knnResults = List.of(
                Map.of("id", "tool1", "score", 0.95),
                Map.of("id", "tool2", "score", 0.85)
            );
            List<Map<String, Object>> lexicalResults = List.of(
                Map.of("id", "tool3", "bm25", 18.5)
            );

            // Act
            CapabilityResponse response = CapabilityResponse.of(cards, meta, knnResults, lexicalResults);

            // Assert
            assertEquals(cards, response.cards());
            assertEquals(meta, response.meta());
            assertEquals(2, response.knnResults().size());
            assertEquals(1, response.lexicalResults().size());
        }
    }

    // ========================================================================
    // Equals and hashCode tests
    // ========================================================================

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("should be equal for identical responses")
        void shouldBeEqualForIdenticalResponses() {
            // Arrange
            List<CapabilityCard> cards = List.of();
            Map<String, Object> meta = Map.of("total", 0);
            CapabilityResponse response1 = CapabilityResponse.of(cards, meta);
            CapabilityResponse response2 = CapabilityResponse.of(cards, meta);

            // Assert
            assertEquals(response1, response2);
            assertEquals(response1.hashCode(), response2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different responses")
        void shouldNotBeEqualForDifferentResponses() {
            // Arrange
            CapabilityResponse response1 = CapabilityResponse.of(List.of(), Map.of("v", 1));
            CapabilityResponse response2 = CapabilityResponse.of(List.of(), Map.of("v", 2));

            // Assert
            assertNotEquals(response1, response2);
        }
    }
}

package com.apimarketplace.catalog.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LexicalResponse record.
 *
 * LexicalResponse is a DTO for lexical search responses.
 */
@DisplayName("LexicalResponse")
class LexicalResponseTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {

        @Test
        @DisplayName("should create response with all fields")
        void shouldCreateResponseWithAllFields() {
            List<LexicalCard> cards = List.of();
            Map<String, Object> meta = Map.of("total", 0);
            List<Map<String, Object>> rawResults = List.of();

            LexicalResponse response = new LexicalResponse(cards, meta, rawResults);

            assertEquals(cards, response.cards());
            assertEquals(meta, response.meta());
            assertEquals(rawResults, response.rawResults());
        }
    }

    // ========================================================================
    // Factory method tests - of() with cards only
    // ========================================================================

    @Nested
    @DisplayName("of() with cards only")
    class OfCardsOnlyTests {

        @Test
        @DisplayName("should create response with default metadata")
        void shouldCreateResponseWithDefaultMetadata() {
            List<LexicalCard> cards = List.of(
                LexicalCard.of("t1", "Tool1", "P1", "r1", "a1", 0.5)
            );

            LexicalResponse response = LexicalResponse.of(cards);

            assertEquals(cards, response.cards());
            assertNotNull(response.meta());
            assertEquals(1, response.meta().get("total"));
            assertEquals("1.0", response.meta().get("version"));
            assertNotNull(response.meta().get("timestamp"));
            assertTrue(response.rawResults().isEmpty());
        }

        @Test
        @DisplayName("should handle empty cards list")
        void shouldHandleEmptyCardsList() {
            LexicalResponse response = LexicalResponse.of(List.of());

            assertTrue(response.cards().isEmpty());
            assertEquals(0, response.meta().get("total"));
        }
    }

    // ========================================================================
    // Factory method tests - of() with cards and meta
    // ========================================================================

    @Nested
    @DisplayName("of() with cards and meta")
    class OfCardsAndMetaTests {

        @Test
        @DisplayName("should create response with custom metadata")
        void shouldCreateResponseWithCustomMetadata() {
            List<LexicalCard> cards = List.of();
            Map<String, Object> meta = Map.of("custom", "value", "total", 0);

            LexicalResponse response = LexicalResponse.of(cards, meta);

            assertEquals(cards, response.cards());
            assertEquals(meta, response.meta());
            assertEquals("value", response.meta().get("custom"));
            assertTrue(response.rawResults().isEmpty());
        }
    }

    // ========================================================================
    // Factory method tests - of() with all parameters
    // ========================================================================

    @Nested
    @DisplayName("of() with all parameters")
    class OfAllParamsTests {

        @Test
        @DisplayName("should create response with raw results")
        void shouldCreateResponseWithRawResults() {
            List<LexicalCard> cards = List.of();
            Map<String, Object> meta = Map.of("total", 0);
            List<Map<String, Object>> rawResults = List.of(Map.of("id", "raw1"));

            LexicalResponse response = LexicalResponse.of(cards, meta, rawResults);

            assertEquals(cards, response.cards());
            assertEquals(meta, response.meta());
            assertEquals(rawResults, response.rawResults());
            assertEquals(1, response.rawResults().size());
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
            List<LexicalCard> cards = List.of();
            Map<String, Object> meta = Map.of("k", "v");

            LexicalResponse resp1 = new LexicalResponse(cards, meta, List.of());
            LexicalResponse resp2 = new LexicalResponse(cards, meta, List.of());

            assertEquals(resp1, resp2);
            assertEquals(resp1.hashCode(), resp2.hashCode());
        }
    }
}

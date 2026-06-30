package com.apimarketplace.catalog.dto;

import com.apimarketplace.catalog.util.SearchScoreClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CapabilityCard record.
 *
 * CapabilityCard is a DTO for capability card responses.
 */
@DisplayName("CapabilityCard")
class CapabilityCardTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {

        @Test
        @DisplayName("should create card with all fields")
        void shouldCreateCardWithAllFields() {
            List<String> needs = List.of("query", "filters");
            Map<String, Object> supp = Map.of("limit", 100);
            SearchScoreClassifier.Quality quality = SearchScoreClassifier.Quality.EXCELLENT;

            CapabilityCard card = new CapabilityCard(
                "tool-1", "Search Tool", "SearchCo",
                needs, supp, true, 0.95,
                quality, "🏆 Excellent", 95
            );

            assertEquals("tool-1", card.id());
            assertEquals("Search Tool", card.name());
            assertEquals("SearchCo", card.prov());
            assertEquals(needs, card.needs());
            assertEquals(supp, card.supp());
            assertTrue(card.auth());
            assertEquals(0.95, card.score());
            assertEquals(quality, card.quality());
            assertEquals("🏆 Excellent", card.qualityLabel());
            assertEquals(95, card.qualityPercentage());
        }
    }

    // ========================================================================
    // fromDbResult factory method tests
    // ========================================================================

    @Nested
    @DisplayName("fromDbResult()")
    class FromDbResultTests {

        @Test
        @DisplayName("should create card from database result with all fields")
        void shouldCreateCardFromDbResultWithAllFields() {
            Map<String, Object> row = new HashMap<>();
            row.put("tool_name_id", "db-tool-1");
            row.put("name", "DB Tool");
            row.put("provider", "DBProvider");
            row.put("method", "GET");
            row.put("endpoint_pattern", "/users/{id}");
            row.put("requires_user_credentials", true);
            row.put("run_scope", "external");
            row.put("vec_score", 0.85);

            CapabilityCard card = CapabilityCard.fromDbResult(row);

            assertEquals("db-tool-1", card.id());
            assertEquals("DB Tool", card.name());
            assertEquals("DBProvider", card.prov());
            assertTrue(card.auth());
            assertEquals(0.85, card.score());
            assertNotNull(card.quality());
            assertNotNull(card.qualityLabel());
        }

        @Test
        @DisplayName("should handle missing optional fields")
        void shouldHandleMissingOptionalFields() {
            Map<String, Object> row = new HashMap<>();
            row.put("tool_name_id", "minimal-tool");
            row.put("name", "Minimal");

            CapabilityCard card = CapabilityCard.fromDbResult(row);

            assertEquals("minimal-tool", card.id());
            assertEquals("Minimal", card.name());
            assertEquals("unknown", card.prov());
            assertFalse(card.auth());
            assertEquals(0.0, card.score());
        }

        @Test
        @DisplayName("should use bm25_score when vec_score is missing")
        void shouldUseBm25ScoreWhenVecScoreIsMissing() {
            Map<String, Object> row = new HashMap<>();
            row.put("tool_name_id", "tool");
            row.put("name", "Tool");
            row.put("bm25_score", 0.75);

            CapabilityCard card = CapabilityCard.fromDbResult(row);

            assertEquals(0.75, card.score());
        }

        @Test
        @DisplayName("should derive needs based on GET method")
        void shouldDeriveNeedsBasedOnGetMethod() {
            Map<String, Object> row = new HashMap<>();
            row.put("tool_name_id", "tool");
            row.put("name", "Tool");
            row.put("method", "GET");
            row.put("endpoint_pattern", "/search");

            CapabilityCard card = CapabilityCard.fromDbResult(row);

            assertNotNull(card.needs());
            assertFalse(card.needs().isEmpty());
        }

        @Test
        @DisplayName("should derive user needs for user endpoint")
        void shouldDeriveUserNeedsForUserEndpoint() {
            Map<String, Object> row = new HashMap<>();
            row.put("tool_name_id", "tool");
            row.put("name", "Tool");
            row.put("method", "GET");
            row.put("endpoint_pattern", "/users/{id}");

            CapabilityCard card = CapabilityCard.fromDbResult(row);

            assertTrue(card.needs().contains("username") || card.needs().contains("user_id"));
        }

        @Test
        @DisplayName("should derive needs for POST method")
        void shouldDeriveNeedsForPostMethod() {
            Map<String, Object> row = new HashMap<>();
            row.put("tool_name_id", "tool");
            row.put("name", "Tool");
            row.put("method", "POST");
            row.put("endpoint_pattern", "/create");

            CapabilityCard card = CapabilityCard.fromDbResult(row);

            assertTrue(card.needs().contains("data") || card.needs().contains("content"));
        }

        @Test
        @DisplayName("should derive needs for DELETE method")
        void shouldDeriveNeedsForDeleteMethod() {
            Map<String, Object> row = new HashMap<>();
            row.put("tool_name_id", "tool");
            row.put("name", "Tool");
            row.put("method", "DELETE");
            row.put("endpoint_pattern", "/items/{id}");

            CapabilityCard card = CapabilityCard.fromDbResult(row);

            assertTrue(card.needs().contains("id"));
        }
    }

    // ========================================================================
    // forRRF factory method tests
    // ========================================================================

    @Nested
    @DisplayName("forRRF()")
    class ForRRFTests {

        @Test
        @DisplayName("should create card for RRF fusion results")
        void shouldCreateCardForRRFFusionResults() {
            List<String> needs = List.of("query");
            Map<String, Object> supp = Map.of("limit", 50);

            CapabilityCard card = CapabilityCard.forRRF(
                "rrf-tool", "RRF Tool", "Provider",
                needs, supp, false, 0.8, true, true
            );

            assertEquals("rrf-tool", card.id());
            assertEquals("RRF Tool", card.name());
            assertEquals("Provider", card.prov());
            assertEquals(needs, card.needs());
            assertEquals(supp, card.supp());
            assertFalse(card.auth());
            assertEquals(0.8, card.score());
            assertNotNull(card.quality());
        }

        @Test
        @DisplayName("should classify quality for hybrid results")
        void shouldClassifyQualityForHybridResults() {
            CapabilityCard card = CapabilityCard.forRRF(
                "id", "name", "prov",
                List.of(), Map.of(), false, 0.9, true, true
            );

            assertNotNull(card.quality());
            assertNotNull(card.qualityLabel());
            assertTrue(card.qualityPercentage() >= 0);
        }

        @Test
        @DisplayName("should handle KNN-only results")
        void shouldHandleKnnOnlyResults() {
            CapabilityCard card = CapabilityCard.forRRF(
                "id", "name", "prov",
                List.of(), Map.of(), false, 0.7, true, false
            );

            assertNotNull(card.quality());
        }

        @Test
        @DisplayName("should handle lexical-only results")
        void shouldHandleLexicalOnlyResults() {
            CapabilityCard card = CapabilityCard.forRRF(
                "id", "name", "prov",
                List.of(), Map.of(), false, 0.6, false, true
            );

            assertNotNull(card.quality());
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
            List<String> needs = List.of("query");
            Map<String, Object> supp = Map.of("k", "v");
            SearchScoreClassifier.Quality quality = SearchScoreClassifier.Quality.AVERAGE;

            CapabilityCard card1 = new CapabilityCard(
                "id", "name", "prov", needs, supp, true, 0.5,
                quality, "🔶 Average", 60
            );

            CapabilityCard card2 = new CapabilityCard(
                "id", "name", "prov", needs, supp, true, 0.5,
                quality, "🔶 Average", 60
            );

            assertEquals(card1, card2);
            assertEquals(card1.hashCode(), card2.hashCode());
        }
    }
}

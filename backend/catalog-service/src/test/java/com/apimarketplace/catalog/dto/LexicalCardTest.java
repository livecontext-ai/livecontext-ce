package com.apimarketplace.catalog.dto;

import com.apimarketplace.catalog.util.SearchScoreClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LexicalCard record.
 *
 * LexicalCard is a DTO for lexical search result cards.
 */
@DisplayName("LexicalCard")
class LexicalCardTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {

        @Test
        @DisplayName("should create card with all fields")
        void shouldCreateCardWithAllFields() {
            List<String> categories = List.of("weather", "data");
            Map<String, Object> metadata = Map.of("version", "1.0");
            SearchScoreClassifier.Quality quality = SearchScoreClassifier.Quality.VERY_GOOD;

            LexicalCard card = new LexicalCard(
                "tool-123", "Weather API", "WeatherCo",
                "forecast", "get", categories, metadata,
                true, 0.85, quality, "✨ Very Good", 85
            );

            assertEquals("tool-123", card.toolId());
            assertEquals("Weather API", card.displayName());
            assertEquals("WeatherCo", card.provider());
            assertEquals("forecast", card.resource());
            assertEquals("get", card.action());
            assertEquals(categories, card.categories());
            assertEquals(metadata, card.metadata());
            assertTrue(card.requiresCredentials());
            assertEquals(0.85, card.bm25Score());
            assertEquals(quality, card.quality());
            assertEquals("✨ Very Good", card.qualityLabel());
            assertEquals(85, card.qualityPercentage());
        }
    }

    // ========================================================================
    // Factory method tests - of() with basic info
    // ========================================================================

    @Nested
    @DisplayName("of() factory method with basic info")
    class OfBasicTests {

        @Test
        @DisplayName("should create card with basic information")
        void shouldCreateCardWithBasicInformation() {
            LexicalCard card = LexicalCard.of(
                "tool-1", "My Tool", "Provider", "resource", "action", 0.75
            );

            assertEquals("tool-1", card.toolId());
            assertEquals("My Tool", card.displayName());
            assertEquals("Provider", card.provider());
            assertEquals("resource", card.resource());
            assertEquals("action", card.action());
            assertEquals(0.75, card.bm25Score());
            assertTrue(card.categories().isEmpty());
            assertTrue(card.metadata().isEmpty());
            assertFalse(card.requiresCredentials());
        }

        @Test
        @DisplayName("should classify quality based on BM25 score")
        void shouldClassifyQualityBasedOnBm25Score() {
            LexicalCard highQuality = LexicalCard.of("t1", "Tool", "P", "r", "a", 0.9);
            assertNotNull(highQuality.quality());
            assertNotNull(highQuality.qualityLabel());
            assertTrue(highQuality.qualityPercentage() > 0);
        }
    }

    // ========================================================================
    // Factory method tests - of() with full info
    // ========================================================================

    @Nested
    @DisplayName("of() factory method with full info")
    class OfFullTests {

        @Test
        @DisplayName("should create card with full information")
        void shouldCreateCardWithFullInformation() {
            List<String> categories = List.of("api", "utility");
            Map<String, Object> metadata = Map.of("key", "value");

            LexicalCard card = LexicalCard.of(
                "tool-2", "Full Tool", "FullProvider",
                "fullResource", "fullAction",
                categories, metadata, true, 0.65
            );

            assertEquals("tool-2", card.toolId());
            assertEquals("Full Tool", card.displayName());
            assertEquals("FullProvider", card.provider());
            assertEquals(categories, card.categories());
            assertEquals(metadata, card.metadata());
            assertTrue(card.requiresCredentials());
            assertEquals(0.65, card.bm25Score());
        }

        @Test
        @DisplayName("should handle empty categories and metadata")
        void shouldHandleEmptyCategoriesAndMetadata() {
            LexicalCard card = LexicalCard.of(
                "tool-3", "Empty Tool", "Provider",
                "resource", "action",
                List.of(), Map.of(), false, 0.5
            );

            assertTrue(card.categories().isEmpty());
            assertTrue(card.metadata().isEmpty());
            assertFalse(card.requiresCredentials());
        }
    }

    // ========================================================================
    // Quality classification tests
    // ========================================================================

    @Nested
    @DisplayName("Quality classification")
    class QualityClassificationTests {

        @Test
        @DisplayName("should set quality based on high BM25 score")
        void shouldSetQualityBasedOnHighScore() {
            LexicalCard card = LexicalCard.of("t", "Tool", "P", "r", "a", 0.95);

            assertNotNull(card.quality());
            assertTrue(card.qualityPercentage() >= 80);
        }

        @Test
        @DisplayName("should set quality based on low BM25 score")
        void shouldSetQualityBasedOnLowScore() {
            LexicalCard card = LexicalCard.of("t", "Tool", "P", "r", "a", 0.1);

            assertNotNull(card.quality());
            assertTrue(card.qualityPercentage() >= 0);
        }

        @Test
        @DisplayName("should have matching quality label and percentage")
        void shouldHaveMatchingQualityLabelAndPercentage() {
            LexicalCard card = LexicalCard.of("t", "Tool", "P", "r", "a", 0.7);

            SearchScoreClassifier.Quality quality = card.quality();
            assertEquals(quality.getDisplayName(), card.qualityLabel());
            assertEquals(SearchScoreClassifier.getQualityPercentage(quality), card.qualityPercentage());
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
            SearchScoreClassifier.Quality quality = SearchScoreClassifier.classifyBM25Score(0.8);

            LexicalCard card1 = new LexicalCard(
                "tool-1", "Tool", "Provider", "resource", "action",
                List.of(), Map.of(), false, 0.8, quality, quality.getDisplayName(),
                SearchScoreClassifier.getQualityPercentage(quality)
            );

            LexicalCard card2 = new LexicalCard(
                "tool-1", "Tool", "Provider", "resource", "action",
                List.of(), Map.of(), false, 0.8, quality, quality.getDisplayName(),
                SearchScoreClassifier.getQualityPercentage(quality)
            );

            assertEquals(card1, card2);
            assertEquals(card1.hashCode(), card2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different tool IDs")
        void shouldNotBeEqualForDifferentToolIds() {
            LexicalCard card1 = LexicalCard.of("tool-1", "Tool", "P", "r", "a", 0.5);
            LexicalCard card2 = LexicalCard.of("tool-2", "Tool", "P", "r", "a", 0.5);

            assertNotEquals(card1, card2);
        }
    }
}

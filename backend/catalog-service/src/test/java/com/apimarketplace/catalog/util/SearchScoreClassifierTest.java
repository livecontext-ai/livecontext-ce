package com.apimarketplace.catalog.util;

import com.apimarketplace.catalog.util.SearchScoreClassifier.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SearchScoreClassifier utility class.
 *
 * Tests quality classification for RRF, BM25, and cosine similarity scores.
 */
@DisplayName("SearchScoreClassifier Tests")
class SearchScoreClassifierTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // Quality ENUM TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Quality Enum")
    class QualityEnumTests {

        @Test
        @DisplayName("Should have correct labels")
        void shouldHaveCorrectLabels() {
            assertEquals("Excellent", Quality.EXCELLENT.getLabel());
            assertEquals("Very Good", Quality.VERY_GOOD.getLabel());
            assertEquals("Good", Quality.GOOD.getLabel());
            assertEquals("Average", Quality.AVERAGE.getLabel());
            assertEquals("Poor", Quality.POOR.getLabel());
        }

        @Test
        @DisplayName("Should have correct emojis")
        void shouldHaveCorrectEmojis() {
            assertEquals("\uD83C\uDFC6", Quality.EXCELLENT.getEmoji()); // Trophy
            assertEquals("✨", Quality.VERY_GOOD.getEmoji());
            assertEquals("✅", Quality.GOOD.getEmoji());
            assertEquals("\uD83D\uDD36", Quality.AVERAGE.getEmoji()); // Orange diamond
            assertEquals("❌", Quality.POOR.getEmoji());
        }

        @ParameterizedTest
        @EnumSource(Quality.class)
        @DisplayName("Should format display name correctly")
        void shouldFormatDisplayNameCorrectly(Quality quality) {
            String displayName = quality.getDisplayName();

            assertNotNull(displayName);
            assertTrue(displayName.contains(quality.getLabel()));
            assertTrue(displayName.startsWith(quality.getEmoji()));
        }

        @Test
        @DisplayName("Should have 5 quality levels")
        void shouldHaveFiveQualityLevels() {
            assertEquals(5, Quality.values().length);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // classifyRRFScore() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("classifyRRFScore()")
    class ClassifyRRFScoreTests {

        @Nested
        @DisplayName("With both KNN and Lexical results")
        class BothResultsTests {

            @Test
            @DisplayName("Should return EXCELLENT for score >= 0.032")
            void shouldReturnExcellentForHighScore() {
                assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyRRFScore(0.032, true, true));
                assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyRRFScore(0.035, true, true));
                assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyRRFScore(0.050, true, true));
            }

            @Test
            @DisplayName("Should return VERY_GOOD for score >= 0.030 and < 0.032")
            void shouldReturnVeryGoodForMediumHighScore() {
                assertEquals(Quality.VERY_GOOD, SearchScoreClassifier.classifyRRFScore(0.030, true, true));
                assertEquals(Quality.VERY_GOOD, SearchScoreClassifier.classifyRRFScore(0.031, true, true));
            }

            @Test
            @DisplayName("Should return GOOD for score >= 0.028 and < 0.030")
            void shouldReturnGoodForMediumScore() {
                assertEquals(Quality.GOOD, SearchScoreClassifier.classifyRRFScore(0.028, true, true));
                assertEquals(Quality.GOOD, SearchScoreClassifier.classifyRRFScore(0.029, true, true));
            }

            @Test
            @DisplayName("Should return AVERAGE for score >= 0.025 and < 0.028")
            void shouldReturnAverageForLowScore() {
                assertEquals(Quality.AVERAGE, SearchScoreClassifier.classifyRRFScore(0.025, true, true));
                assertEquals(Quality.AVERAGE, SearchScoreClassifier.classifyRRFScore(0.027, true, true));
            }

            @Test
            @DisplayName("Should return POOR for score < 0.025")
            void shouldReturnPoorForVeryLowScore() {
                assertEquals(Quality.POOR, SearchScoreClassifier.classifyRRFScore(0.024, true, true));
                assertEquals(Quality.POOR, SearchScoreClassifier.classifyRRFScore(0.010, true, true));
                assertEquals(Quality.POOR, SearchScoreClassifier.classifyRRFScore(0.0, true, true));
            }
        }

        @Nested
        @DisplayName("With KNN only")
        class KnnOnlyTests {

            @Test
            @DisplayName("Should apply 0.95 confidence multiplier")
            void shouldApplyKnnConfidenceMultiplier() {
                // 0.032 * 0.95 = 0.0304, still >= 0.030, so VERY_GOOD
                // Need higher score for EXCELLENT with KNN only
                assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyRRFScore(0.034, true, false));
            }

            @Test
            @DisplayName("Should lower quality due to multiplier")
            void shouldLowerQualityDueToMultiplier() {
                // 0.032 * 0.95 = 0.0304, which is >= 0.030, so VERY_GOOD not EXCELLENT
                Quality quality = SearchScoreClassifier.classifyRRFScore(0.032, true, false);
                assertEquals(Quality.VERY_GOOD, quality);
            }
        }

        @Nested
        @DisplayName("With Lexical only")
        class LexicalOnlyTests {

            @Test
            @DisplayName("Should apply 1.05 confidence multiplier")
            void shouldApplyLexicalConfidenceMultiplier() {
                // 0.030 * 1.05 = 0.0315, which is < 0.032, so still VERY_GOOD
                // 0.031 * 1.05 = 0.03255, which is >= 0.032, so EXCELLENT
                assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyRRFScore(0.031, false, true));
            }

            @Test
            @DisplayName("Should boost quality due to multiplier")
            void shouldBoostQualityDueToMultiplier() {
                // A score that would normally be VERY_GOOD becomes EXCELLENT
                Quality withLexicalOnly = SearchScoreClassifier.classifyRRFScore(0.031, false, true);
                Quality withBoth = SearchScoreClassifier.classifyRRFScore(0.031, true, true);

                assertEquals(Quality.EXCELLENT, withLexicalOnly);
                assertEquals(Quality.VERY_GOOD, withBoth);
            }
        }

        @Test
        @DisplayName("Should handle zero score")
        void shouldHandleZeroScore() {
            assertEquals(Quality.POOR, SearchScoreClassifier.classifyRRFScore(0.0, true, true));
        }

        @Test
        @DisplayName("Should handle negative score")
        void shouldHandleNegativeScore() {
            assertEquals(Quality.POOR, SearchScoreClassifier.classifyRRFScore(-0.1, true, true));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // classifyBM25Score() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("classifyBM25Score()")
    class ClassifyBM25ScoreTests {

        @ParameterizedTest
        @CsvSource({
            "1.0, EXCELLENT",
            "0.9, EXCELLENT",
            "0.8, EXCELLENT",
            "0.79, VERY_GOOD",
            "0.5, VERY_GOOD",
            "0.49, GOOD",
            "0.3, GOOD",
            "0.29, AVERAGE",
            "0.1, AVERAGE",
            "0.09, POOR",
            "0.0, POOR"
        })
        @DisplayName("Should classify BM25 scores correctly")
        void shouldClassifyBM25Correctly(double score, Quality expected) {
            assertEquals(expected, SearchScoreClassifier.classifyBM25Score(score));
        }

        @Test
        @DisplayName("Should return EXCELLENT for very high scores")
        void shouldReturnExcellentForVeryHighScores() {
            assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyBM25Score(1.0));
            assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyBM25Score(0.95));
        }

        @Test
        @DisplayName("Should return POOR for very low scores")
        void shouldReturnPoorForVeryLowScores() {
            assertEquals(Quality.POOR, SearchScoreClassifier.classifyBM25Score(0.05));
            assertEquals(Quality.POOR, SearchScoreClassifier.classifyBM25Score(0.0));
        }

        @Test
        @DisplayName("Should handle negative scores")
        void shouldHandleNegativeScores() {
            assertEquals(Quality.POOR, SearchScoreClassifier.classifyBM25Score(-0.5));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // classifyCosineSimilarity() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("classifyCosineSimilarity()")
    class ClassifyCosineSimilarityTests {

        @ParameterizedTest
        @CsvSource({
            "1.0, EXCELLENT",
            "0.95, EXCELLENT",
            "0.9, EXCELLENT",
            "0.89, VERY_GOOD",
            "0.8, VERY_GOOD",
            "0.79, GOOD",
            "0.7, GOOD",
            "0.69, AVERAGE",
            "0.6, AVERAGE",
            "0.59, POOR",
            "0.5, POOR",
            "0.0, POOR"
        })
        @DisplayName("Should classify cosine similarity correctly")
        void shouldClassifyCosineCorrectly(double score, Quality expected) {
            assertEquals(expected, SearchScoreClassifier.classifyCosineSimilarity(score));
        }

        @Test
        @DisplayName("Should return EXCELLENT for perfect similarity")
        void shouldReturnExcellentForPerfectSimilarity() {
            assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyCosineSimilarity(1.0));
        }

        @Test
        @DisplayName("Should return POOR for low similarity")
        void shouldReturnPoorForLowSimilarity() {
            assertEquals(Quality.POOR, SearchScoreClassifier.classifyCosineSimilarity(0.4));
            assertEquals(Quality.POOR, SearchScoreClassifier.classifyCosineSimilarity(0.0));
        }

        @Test
        @DisplayName("Should handle negative similarity")
        void shouldHandleNegativeSimilarity() {
            // Cosine similarity can be negative for opposite vectors
            assertEquals(Quality.POOR, SearchScoreClassifier.classifyCosineSimilarity(-0.5));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getQualityDescription() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getQualityDescription()")
    class GetQualityDescriptionTests {

        @Test
        @DisplayName("Should return description for EXCELLENT")
        void shouldReturnExcellentDescription() {
            String description = SearchScoreClassifier.getQualityDescription(Quality.EXCELLENT);
            assertNotNull(description);
            assertTrue(description.toLowerCase().contains("perfect") ||
                       description.toLowerCase().contains("highly relevant"));
        }

        @Test
        @DisplayName("Should return description for VERY_GOOD")
        void shouldReturnVeryGoodDescription() {
            String description = SearchScoreClassifier.getQualityDescription(Quality.VERY_GOOD);
            assertNotNull(description);
            assertTrue(description.toLowerCase().contains("relevant") ||
                       description.toLowerCase().contains("high confidence"));
        }

        @Test
        @DisplayName("Should return description for GOOD")
        void shouldReturnGoodDescription() {
            String description = SearchScoreClassifier.getQualityDescription(Quality.GOOD);
            assertNotNull(description);
            assertTrue(description.toLowerCase().contains("good") ||
                       description.toLowerCase().contains("relevant"));
        }

        @Test
        @DisplayName("Should return description for AVERAGE")
        void shouldReturnAverageDescription() {
            String description = SearchScoreClassifier.getQualityDescription(Quality.AVERAGE);
            assertNotNull(description);
            assertTrue(description.toLowerCase().contains("moderate"));
        }

        @Test
        @DisplayName("Should return description for POOR")
        void shouldReturnPoorDescription() {
            String description = SearchScoreClassifier.getQualityDescription(Quality.POOR);
            assertNotNull(description);
            assertTrue(description.toLowerCase().contains("low") ||
                       description.toLowerCase().contains("refin"));
        }

        @ParameterizedTest
        @EnumSource(Quality.class)
        @DisplayName("Should return non-empty description for all qualities")
        void shouldReturnNonEmptyDescriptions(Quality quality) {
            String description = SearchScoreClassifier.getQualityDescription(quality);
            assertNotNull(description);
            assertFalse(description.isBlank());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getQualityPercentage() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getQualityPercentage()")
    class GetQualityPercentageTests {

        @Test
        @DisplayName("Should return correct percentages")
        void shouldReturnCorrectPercentages() {
            assertEquals(95, SearchScoreClassifier.getQualityPercentage(Quality.EXCELLENT));
            assertEquals(85, SearchScoreClassifier.getQualityPercentage(Quality.VERY_GOOD));
            assertEquals(75, SearchScoreClassifier.getQualityPercentage(Quality.GOOD));
            assertEquals(60, SearchScoreClassifier.getQualityPercentage(Quality.AVERAGE));
            assertEquals(30, SearchScoreClassifier.getQualityPercentage(Quality.POOR));
        }

        @ParameterizedTest
        @EnumSource(Quality.class)
        @DisplayName("Should return percentage in valid range for all qualities")
        void shouldReturnPercentageInValidRange(Quality quality) {
            int percentage = SearchScoreClassifier.getQualityPercentage(quality);
            assertTrue(percentage >= 0 && percentage <= 100,
                "Percentage should be between 0 and 100, but was " + percentage);
        }

        @Test
        @DisplayName("Percentages should be in descending order")
        void percentagesShouldBeDescending() {
            assertTrue(SearchScoreClassifier.getQualityPercentage(Quality.EXCELLENT) >
                       SearchScoreClassifier.getQualityPercentage(Quality.VERY_GOOD));
            assertTrue(SearchScoreClassifier.getQualityPercentage(Quality.VERY_GOOD) >
                       SearchScoreClassifier.getQualityPercentage(Quality.GOOD));
            assertTrue(SearchScoreClassifier.getQualityPercentage(Quality.GOOD) >
                       SearchScoreClassifier.getQualityPercentage(Quality.AVERAGE));
            assertTrue(SearchScoreClassifier.getQualityPercentage(Quality.AVERAGE) >
                       SearchScoreClassifier.getQualityPercentage(Quality.POOR));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle boundary values for RRF")
        void shouldHandleBoundaryValuesForRRF() {
            // Test exact boundary values
            assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyRRFScore(0.032, true, true));
            assertEquals(Quality.VERY_GOOD, SearchScoreClassifier.classifyRRFScore(0.0319999, true, true));
        }

        @Test
        @DisplayName("Should handle boundary values for BM25")
        void shouldHandleBoundaryValuesForBM25() {
            assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyBM25Score(0.8));
            assertEquals(Quality.VERY_GOOD, SearchScoreClassifier.classifyBM25Score(0.7999));
        }

        @Test
        @DisplayName("Should handle boundary values for cosine")
        void shouldHandleBoundaryValuesForCosine() {
            assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyCosineSimilarity(0.9));
            assertEquals(Quality.VERY_GOOD, SearchScoreClassifier.classifyCosineSimilarity(0.8999));
        }

        @Test
        @DisplayName("Should handle very large scores")
        void shouldHandleVeryLargeScores() {
            assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyRRFScore(1.0, true, true));
            assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyBM25Score(100.0));
            assertEquals(Quality.EXCELLENT, SearchScoreClassifier.classifyCosineSimilarity(10.0));
        }
    }
}

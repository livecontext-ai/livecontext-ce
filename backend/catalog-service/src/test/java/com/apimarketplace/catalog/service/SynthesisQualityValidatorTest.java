package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.service.SynthesisQualityValidator.SynthesisData;
import com.apimarketplace.catalog.service.SynthesisQualityValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SynthesisQualityValidatorTest {

    private SynthesisQualityValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SynthesisQualityValidator();
    }

    @Nested
    @DisplayName("Action Validation")
    class ActionValidation {

        @Test
        @DisplayName("should accept valid actions")
        void shouldAcceptValidActions() {
            List<String> validActions = List.of(
                "list", "get", "create", "update", "delete",
                "search", "filter", "upload", "download",
                "follow", "like", "share", "send"
            );

            for (String action : validActions) {
                SynthesisData data = createValidSynthesisData(action);
                ValidationResult result = validator.validate(data);
                assertThat(result.errors())
                    .as("Action '%s' should be valid", action)
                    .noneMatch(e -> e.contains("Invalid action"));
            }
        }

        @Test
        @DisplayName("should reject invalid actions")
        void shouldRejectInvalidActions() {
            SynthesisData data = createValidSynthesisData("invalid_action");
            ValidationResult result = validator.validate(data);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.contains("Invalid action"));
        }

        @Test
        @DisplayName("should reject null action")
        void shouldRejectNullAction() {
            SynthesisData data = new SynthesisData(
                null, "Valid summary with enough words to pass validation check.",
                "resource", List.of("keyword1", "keyword2", "keyword3"),
                List.of("synonym1", "synonym2"), List.of("param1:desc"), List.of("use case 1")
            );
            ValidationResult result = validator.validate(data);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.contains("Action is required"));
        }
    }

    @Nested
    @DisplayName("Summary Validation")
    class SummaryValidation {

        @Test
        @DisplayName("should accept summary with optimal length (100-200 words)")
        void shouldAcceptOptimalSummary() {
            String summary = generateWordsString(150);
            SynthesisData data = createSynthesisDataWithSummary(summary);
            ValidationResult result = validator.validate(data);

            assertThat(result.errors()).isEmpty();
            assertThat(result.warnings())
                .noneMatch(w -> w.contains("Summary") && (w.contains("short") || w.contains("long")));
        }

        @Test
        @DisplayName("should reject summary that is too short")
        void shouldRejectTooShortSummary() {
            String summary = generateWordsString(50); // Below 80 minimum
            SynthesisData data = createSynthesisDataWithSummary(summary);
            ValidationResult result = validator.validate(data);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.contains("Summary too short"));
        }

        @Test
        @DisplayName("should warn for summary below optimal length")
        void shouldWarnBelowOptimalSummary() {
            String summary = generateWordsString(90); // Between 80-100
            SynthesisData data = createSynthesisDataWithSummary(summary);
            ValidationResult result = validator.validate(data);

            assertThat(result.isValid()).isTrue();
            assertThat(result.warnings()).anyMatch(w -> w.contains("below optimal"));
        }

        @Test
        @DisplayName("should warn for summary above optimal length")
        void shouldWarnAboveOptimalSummary() {
            String summary = generateWordsString(250); // Between 200-300
            SynthesisData data = createSynthesisDataWithSummary(summary);
            ValidationResult result = validator.validate(data);

            assertThat(result.isValid()).isTrue();
            assertThat(result.warnings()).anyMatch(w -> w.contains("above optimal"));
        }

        @Test
        @DisplayName("should reject null summary")
        void shouldRejectNullSummary() {
            SynthesisData data = new SynthesisData(
                "list", null, "resource",
                List.of("keyword1", "keyword2", "keyword3"),
                List.of("synonym1", "synonym2"), List.of("param1:desc"), List.of("use case 1")
            );
            ValidationResult result = validator.validate(data);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.contains("Summary is required"));
        }
    }

    @Nested
    @DisplayName("Keywords Primary Validation")
    class KeywordsPrimaryValidation {

        @Test
        @DisplayName("should accept 3-6 primary keywords")
        void shouldAcceptValidKeywordsCount() {
            SynthesisData data = createValidSynthesisData("list");
            ValidationResult result = validator.validate(data);

            assertThat(result.errors())
                .noneMatch(e -> e.contains("primary keywords"));
        }

        @Test
        @DisplayName("should reject fewer than 3 primary keywords")
        void shouldRejectTooFewKeywords() {
            SynthesisData data = new SynthesisData(
                "list", generateWordsString(150), "resource",
                List.of("keyword1", "keyword2"), // Only 2
                List.of("synonym1", "synonym2"), List.of("param1:desc"), List.of("use case 1")
            );
            ValidationResult result = validator.validate(data);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.contains("at least 3 primary keywords"));
        }

        @Test
        @DisplayName("should warn for too many primary keywords")
        void shouldWarnTooManyKeywords() {
            SynthesisData data = new SynthesisData(
                "list", generateWordsString(150), "resource",
                List.of("k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8"), // 8 keywords
                List.of("synonym1", "synonym2"), List.of("param1:desc"), List.of("use case 1")
            );
            ValidationResult result = validator.validate(data);

            assertThat(result.isValid()).isTrue();
            assertThat(result.warnings()).anyMatch(w -> w.contains("Too many primary keywords"));
        }

        @Test
        @DisplayName("should warn for single-word keywords")
        void shouldWarnSingleWordKeywords() {
            SynthesisData data = new SynthesisData(
                "list", generateWordsString(150), "resource",
                List.of("single", "word", "keywords"), // All single words
                List.of("synonym1", "synonym2"), List.of("param1:desc"), List.of("use case 1")
            );
            ValidationResult result = validator.validate(data);

            assertThat(result.warnings()).anyMatch(w -> w.contains("too generic"));
        }
    }

    @Nested
    @DisplayName("Keywords Params Validation")
    class KeywordsParamsValidation {

        @Test
        @DisplayName("should accept properly formatted param keywords")
        void shouldAcceptProperFormat() {
            SynthesisData data = new SynthesisData(
                "list", generateWordsString(150), "resource",
                List.of("get data", "fetch info", "retrieve items"),
                List.of("synonym1", "synonym2"),
                List.of("user_id:target user", "limit:max results"), // Proper format
                List.of("use case 1", "use case 2")
            );
            ValidationResult result = validator.validate(data);

            assertThat(result.warnings())
                .noneMatch(w -> w.contains("param_name:description"));
        }

        @Test
        @DisplayName("should warn for improperly formatted param keywords")
        void shouldWarnImproperFormat() {
            SynthesisData data = new SynthesisData(
                "list", generateWordsString(150), "resource",
                List.of("get data", "fetch info", "retrieve items"),
                List.of("synonym1", "synonym2"),
                List.of("user_id", "limit"), // Missing descriptions
                List.of("use case 1", "use case 2")
            );
            ValidationResult result = validator.validate(data);

            assertThat(result.warnings()).anyMatch(w -> w.contains("param_name:description"));
        }
    }

    @Nested
    @DisplayName("Use Cases Validation")
    class UseCasesValidation {

        @Test
        @DisplayName("should accept 2-5 use cases")
        void shouldAcceptValidUseCasesCount() {
            SynthesisData data = createValidSynthesisData("list");
            ValidationResult result = validator.validate(data);

            assertThat(result.warnings())
                .noneMatch(w -> w.contains("use cases") && (w.contains("Few") || w.contains("Too many")));
        }

        @Test
        @DisplayName("should warn for no use cases")
        void shouldWarnNoUseCases() {
            SynthesisData data = new SynthesisData(
                "list", generateWordsString(150), "resource",
                List.of("get data", "fetch info", "retrieve items"),
                List.of("synonym1", "synonym2"),
                List.of("param1:desc"),
                List.of() // Empty
            );
            ValidationResult result = validator.validate(data);

            assertThat(result.warnings()).anyMatch(w -> w.contains("No use cases"));
        }
    }

    @Nested
    @DisplayName("Keyword Uniqueness Validation")
    class KeywordUniquenessValidation {

        @Test
        @DisplayName("should warn for too many duplicate keywords")
        void shouldWarnDuplicates() {
            SynthesisData data = new SynthesisData(
                "list", generateWordsString(150), "resource",
                List.of("get data", "fetch data", "retrieve data"), // All different
                List.of("get data", "fetch data", "retrieve data"), // Same as primary - duplicates!
                List.of("param1:desc"),
                List.of("use case 1", "use case 2")
            );
            ValidationResult result = validator.validate(data);

            assertThat(result.warnings()).anyMatch(w -> w.contains("duplicate keywords"));
        }
    }

    @Nested
    @DisplayName("Coherence Validation")
    class CoherenceValidation {

        @Test
        @DisplayName("should warn if summary does not mention action")
        void shouldWarnMissingAction() {
            String summaryWithoutAction = "This endpoint provides user profile information " +
                "including username, bio, and avatar. It returns detailed data about the user.";
            // Repeat to reach minimum word count
            summaryWithoutAction = summaryWithoutAction + " " + summaryWithoutAction + " " + summaryWithoutAction;

            SynthesisData data = new SynthesisData(
                "delete", // Action not mentioned in summary
                summaryWithoutAction,
                "user_profile",
                List.of("get user data", "fetch profile", "retrieve user"),
                List.of("profile info", "user details"),
                List.of("user_id:target user"),
                List.of("user management", "profile display")
            );
            ValidationResult result = validator.validate(data);

            assertThat(result.warnings()).anyMatch(w -> w.contains("should mention the action"));
        }

        @Test
        @DisplayName("should accept action synonyms in summary")
        void shouldAcceptActionSynonyms() {
            String summaryWithSynonym = "This endpoint retrieves user profile information " +
                "including username, bio, and avatar. It returns detailed data about the user. " +
                "The response contains all available profile fields and metadata.";
            summaryWithSynonym = summaryWithSynonym + " " + summaryWithSynonym;

            SynthesisData data = new SynthesisData(
                "get", // "retrieves" is a synonym
                summaryWithSynonym,
                "user_profile",
                List.of("get user data", "fetch profile", "retrieve user"),
                List.of("profile info", "user details"),
                List.of("user_id:target user"),
                List.of("user management", "profile display")
            );
            ValidationResult result = validator.validate(data);

            // Should not warn about missing action because "retrieves" is a synonym of "get"
            assertThat(result.warnings())
                .noneMatch(w -> w.contains("should mention the action"));
        }
    }

    @Nested
    @DisplayName("Quality Score Calculation")
    class QualityScoreCalculation {

        @Test
        @DisplayName("should give high score for perfect synthesis")
        void shouldGiveHighScoreForPerfect() {
            SynthesisData data = createValidSynthesisData("list");
            ValidationResult result = validator.validate(data);

            assertThat(result.qualityScore()).isGreaterThanOrEqualTo(80);
        }

        @Test
        @DisplayName("should give low score for synthesis with errors")
        void shouldGiveLowScoreForErrors() {
            SynthesisData data = new SynthesisData(
                "invalid_action",
                "Short",
                "resource",
                List.of("k"),
                List.of(),
                List.of(),
                List.of()
            );
            ValidationResult result = validator.validate(data);

            assertThat(result.qualityScore()).isLessThan(50);
        }
    }

    @Nested
    @DisplayName("Minimal Validation")
    class MinimalValidation {

        @Test
        @DisplayName("should return true for minimally valid data")
        void shouldReturnTrueForMinimallyValid() {
            SynthesisData data = new SynthesisData(
                "list",
                generateWordsString(60), // Above 50 words
                "resource",
                List.of("keyword1", "keyword2", "keyword3"),
                List.of(), List.of(), List.of()
            );

            assertThat(validator.isMinimallyValid(data)).isTrue();
        }

        @Test
        @DisplayName("should return false for invalid minimal data")
        void shouldReturnFalseForInvalidMinimal() {
            SynthesisData data = new SynthesisData(
                null, // Missing action
                generateWordsString(60),
                "resource",
                List.of("keyword1", "keyword2", "keyword3"),
                List.of(), List.of(), List.of()
            );

            assertThat(validator.isMinimallyValid(data)).isFalse();
        }
    }

    // Helper methods

    private SynthesisData createValidSynthesisData(String action) {
        return new SynthesisData(
            action,
            generateWordsString(150),
            "user_stories",
            List.of("get user stories", "fetch instagram stories", "list user stories"),
            List.of("ephemeral content", "temporary posts"),
            List.of("user_id:instagram user identifier", "limit:max results"),
            List.of("social media monitoring", "content backup")
        );
    }

    private SynthesisData createSynthesisDataWithSummary(String summary) {
        return new SynthesisData(
            "list",
            summary,
            "user_stories",
            List.of("get user stories", "fetch instagram stories", "list user stories"),
            List.of("ephemeral content", "temporary posts"),
            List.of("user_id:instagram user identifier", "limit:max results"),
            List.of("social media monitoring", "content backup")
        );
    }

    private String generateWordsString(int wordCount) {
        StringBuilder sb = new StringBuilder();
        String[] words = {"the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog",
            "retrieves", "data", "user", "profile", "information", "returns", "content"};
        for (int i = 0; i < wordCount; i++) {
            sb.append(words[i % words.length]);
            if (i < wordCount - 1) sb.append(" ");
        }
        return sb.toString();
    }
}

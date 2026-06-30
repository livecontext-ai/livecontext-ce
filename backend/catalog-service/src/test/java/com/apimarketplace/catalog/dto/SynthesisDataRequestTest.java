package com.apimarketplace.catalog.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SynthesisDataRequest record.
 *
 * SynthesisDataRequest is used for receiving pre-computed synthesis data from API import.
 */
@DisplayName("SynthesisDataRequest")
class SynthesisDataRequestTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class RecordConstructionTests {

        @Test
        @DisplayName("should create record with all fields")
        void shouldCreateRecordWithAllFields() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    "list_repos",
                    "GitHub",
                    "Repository",
                    "list",
                    "/repos",
                    "List repositories",
                    "Lists all repositories for a user",
                    List.of("repos", "repositories"),
                    List.of("projects", "code"),
                    List.of("user", "org"),
                    List.of("find repos", "list projects"),
                    List.of("username"),
                    List.of("page", "per_page"),
                    List.of("octocat"),
                    "repos, repositories, projects"
            );

            assertEquals("list_repos", request.toolName());
            assertEquals("GitHub", request.provider());
            assertEquals("Repository", request.resource());
            assertEquals("list", request.action());
            assertEquals("/repos", request.endpoint());
            assertEquals("List repositories", request.summary());
            assertEquals("Lists all repositories for a user", request.summaryExtended());
            assertEquals(List.of("repos", "repositories"), request.keywordsPrimary());
            assertEquals(List.of("projects", "code"), request.keywordsSynonyms());
            assertEquals(List.of("user", "org"), request.keywordsParams());
            assertEquals(List.of("find repos", "list projects"), request.useCases());
            assertEquals(List.of("username"), request.paramsRequired());
            assertEquals(List.of("page", "per_page"), request.paramsOptional());
            assertEquals(List.of("octocat"), request.paramExamples());
            assertEquals("repos, repositories, projects", request.keywords());
        }

        @Test
        @DisplayName("should allow null fields")
        void shouldAllowNullFields() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null
            );

            assertNull(request.provider());
            assertNull(request.resource());
            assertNull(request.action());
        }
    }

    // ========================================================================
    // hasValidSynthesis tests
    // ========================================================================

    @Nested
    @DisplayName("hasValidSynthesis()")
    class HasValidSynthesisTests {

        @Test
        @DisplayName("should return true for valid required fields")
        void shouldReturnTrueForValidRequiredFields() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "Provider", "Resource", "action", null, null, null,
                    null, null, null, null, null, null, null, null
            );

            assertTrue(request.hasValidSynthesis());
        }

        @Test
        @DisplayName("should return false when provider is null")
        void shouldReturnFalseWhenProviderIsNull() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, null, "Resource", "action", null, null, null,
                    null, null, null, null, null, null, null, null
            );

            assertFalse(request.hasValidSynthesis());
        }

        @Test
        @DisplayName("should return false when provider is blank")
        void shouldReturnFalseWhenProviderIsBlank() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "   ", "Resource", "action", null, null, null,
                    null, null, null, null, null, null, null, null
            );

            assertFalse(request.hasValidSynthesis());
        }

        @Test
        @DisplayName("should return false when resource is null")
        void shouldReturnFalseWhenResourceIsNull() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "Provider", null, "action", null, null, null,
                    null, null, null, null, null, null, null, null
            );

            assertFalse(request.hasValidSynthesis());
        }

        @Test
        @DisplayName("should return false when resource is blank")
        void shouldReturnFalseWhenResourceIsBlank() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "Provider", "", "action", null, null, null,
                    null, null, null, null, null, null, null, null
            );

            assertFalse(request.hasValidSynthesis());
        }

        @Test
        @DisplayName("should return false when action is null")
        void shouldReturnFalseWhenActionIsNull() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "Provider", "Resource", null, null, null, null,
                    null, null, null, null, null, null, null, null
            );

            assertFalse(request.hasValidSynthesis());
        }

        @Test
        @DisplayName("should return false when action is blank")
        void shouldReturnFalseWhenActionIsBlank() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "Provider", "Resource", "  ", null, null, null,
                    null, null, null, null, null, null, null, null
            );

            assertFalse(request.hasValidSynthesis());
        }
    }

    // ========================================================================
    // buildKeywordsString tests
    // ========================================================================

    @Nested
    @DisplayName("buildKeywordsString()")
    class BuildKeywordsStringTests {

        @Test
        @DisplayName("should combine primary and synonyms keywords")
        void shouldCombinePrimaryAndSynonymsKeywords() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "Provider", "Resource", "action", null, null, null,
                    List.of("primary1", "primary2"),
                    List.of("synonym1", "synonym2"),
                    null, null, null, null, null, null
            );

            String result = request.buildKeywordsString();

            assertEquals("primary1, primary2, synonym1, synonym2", result);
        }

        @Test
        @DisplayName("should return only primary keywords when no synonyms")
        void shouldReturnOnlyPrimaryKeywords() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "Provider", "Resource", "action", null, null, null,
                    List.of("primary1", "primary2"),
                    null,
                    null, null, null, null, null, null
            );

            String result = request.buildKeywordsString();

            assertEquals("primary1, primary2", result);
        }

        @Test
        @DisplayName("should return only synonyms keywords when no primary")
        void shouldReturnOnlySynonymsKeywords() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "Provider", "Resource", "action", null, null, null,
                    null,
                    List.of("synonym1", "synonym2"),
                    null, null, null, null, null, null
            );

            String result = request.buildKeywordsString();

            assertEquals("synonym1, synonym2", result);
        }

        @Test
        @DisplayName("should return legacy keywords when no primary or synonyms")
        void shouldReturnLegacyKeywords() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "Provider", "Resource", "action", null, null, null,
                    null, null, null, null, null, null, null,
                    "legacy keywords"
            );

            String result = request.buildKeywordsString();

            assertEquals("legacy keywords", result);
        }

        @Test
        @DisplayName("should return empty string when no keywords available")
        void shouldReturnEmptyStringWhenNoKeywords() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "Provider", "Resource", "action", null, null, null,
                    null, null, null, null, null, null, null, null
            );

            String result = request.buildKeywordsString();

            assertEquals("", result);
        }

        @Test
        @DisplayName("should handle empty lists")
        void shouldHandleEmptyLists() {
            SynthesisDataRequest request = new SynthesisDataRequest(
                    null, "Provider", "Resource", "action", null, null, null,
                    List.of(), List.of(), null, null, null, null, null, "fallback"
            );

            String result = request.buildKeywordsString();

            assertEquals("fallback", result);
        }
    }
}

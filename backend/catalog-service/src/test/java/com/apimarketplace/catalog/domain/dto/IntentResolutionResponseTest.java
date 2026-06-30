package com.apimarketplace.catalog.domain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IntentResolutionResponse.
 *
 * IntentResolutionResponse is a DTO for intent resolution responses.
 */
@DisplayName("IntentResolutionResponse")
class IntentResolutionResponseTest {

    // ========================================================================
    // Builder tests
    // ========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build response with all fields")
        void shouldBuildResponseWithAllFields() {
            List<ToolCandidate> candidates = List.of();

            IntentResolutionResponse response = IntentResolutionResponse.builder()
                .query("get weather in Paris")
                .candidates(candidates)
                .totalCandidates(5)
                .build();

            assertEquals("get weather in Paris", response.getQuery());
            assertEquals(candidates, response.getCandidates());
            assertEquals(5, response.getTotalCandidates());
            assertNull(response.getError());
        }

        @Test
        @DisplayName("should build error response")
        void shouldBuildErrorResponse() {
            IntentResolutionResponse response = IntentResolutionResponse.builder()
                .query("invalid query")
                .error("Query parsing failed")
                .build();

            assertEquals("invalid query", response.getQuery());
            assertEquals("Query parsing failed", response.getError());
            assertNull(response.getCandidates());
        }

        @Test
        @DisplayName("should build response with empty candidates")
        void shouldBuildResponseWithEmptyCandidates() {
            IntentResolutionResponse response = IntentResolutionResponse.builder()
                .query("unknown intent")
                .candidates(List.of())
                .totalCandidates(0)
                .build();

            assertNotNull(response.getCandidates());
            assertTrue(response.getCandidates().isEmpty());
            assertEquals(0, response.getTotalCandidates());
        }
    }

    // ========================================================================
    // Getter and Setter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set query")
        void shouldGetAndSetQuery() {
            IntentResolutionResponse response = IntentResolutionResponse.builder().build();

            response.setQuery("search for tools");

            assertEquals("search for tools", response.getQuery());
        }

        @Test
        @DisplayName("should get and set candidates")
        void shouldGetAndSetCandidates() {
            IntentResolutionResponse response = IntentResolutionResponse.builder().build();
            List<ToolCandidate> candidates = List.of();

            response.setCandidates(candidates);

            assertEquals(candidates, response.getCandidates());
        }

        @Test
        @DisplayName("should get and set error")
        void shouldGetAndSetError() {
            IntentResolutionResponse response = IntentResolutionResponse.builder().build();

            response.setError("Processing error");

            assertEquals("Processing error", response.getError());
        }

        @Test
        @DisplayName("should get and set totalCandidates")
        void shouldGetAndSetTotalCandidates() {
            IntentResolutionResponse response = IntentResolutionResponse.builder().build();

            response.setTotalCandidates(25);

            assertEquals(25, response.getTotalCandidates());
        }
    }

    // ========================================================================
    // Equality tests
    // ========================================================================

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("should be equal for same values")
        void shouldBeEqualForSameValues() {
            IntentResolutionResponse resp1 = IntentResolutionResponse.builder()
                .query("test query")
                .candidates(List.of())
                .totalCandidates(0)
                .build();

            IntentResolutionResponse resp2 = IntentResolutionResponse.builder()
                .query("test query")
                .candidates(List.of())
                .totalCandidates(0)
                .build();

            assertEquals(resp1, resp2);
            assertEquals(resp1.hashCode(), resp2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different queries")
        void shouldNotBeEqualForDifferentQueries() {
            IntentResolutionResponse resp1 = IntentResolutionResponse.builder()
                .query("query1")
                .build();

            IntentResolutionResponse resp2 = IntentResolutionResponse.builder()
                .query("query2")
                .build();

            assertNotEquals(resp1, resp2);
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("should return string representation")
        void shouldReturnStringRepresentation() {
            IntentResolutionResponse response = IntentResolutionResponse.builder()
                .query("test")
                .totalCandidates(3)
                .build();

            String str = response.toString();

            assertNotNull(str);
            assertTrue(str.contains("IntentResolutionResponse"));
        }
    }
}

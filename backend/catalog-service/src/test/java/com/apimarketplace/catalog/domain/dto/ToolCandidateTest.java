package com.apimarketplace.catalog.domain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolCandidate class.
 *
 * ToolCandidate represents a candidate tool returned from intent resolution.
 */
@DisplayName("ToolCandidate")
class ToolCandidateTest {

    // ========================================================================
    // Builder tests
    // ========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build candidate with all fields")
        void shouldBuildCandidateWithAllFields() {
            Map<String, Object> metadata = Map.of("source", "semantic", "version", "1.0");

            ToolCandidate candidate = ToolCandidate.builder()
                .toolId("tool-123")
                .name("Weather API")
                .description("Get current weather data")
                .category("weather")
                .confidence(0.95)
                .reason("High semantic similarity")
                .metadata(metadata)
                .build();

            assertEquals("tool-123", candidate.getToolId());
            assertEquals("Weather API", candidate.getName());
            assertEquals("Get current weather data", candidate.getDescription());
            assertEquals("weather", candidate.getCategory());
            assertEquals(0.95, candidate.getConfidence());
            assertEquals("High semantic similarity", candidate.getReason());
            assertEquals(metadata, candidate.getMetadata());
        }

        @Test
        @DisplayName("should build candidate with minimal fields")
        void shouldBuildCandidateWithMinimalFields() {
            ToolCandidate candidate = ToolCandidate.builder()
                .toolId("tool-1")
                .name("Test Tool")
                .build();

            assertEquals("tool-1", candidate.getToolId());
            assertEquals("Test Tool", candidate.getName());
            assertNull(candidate.getDescription());
            assertNull(candidate.getCategory());
            assertEquals(0.0, candidate.getConfidence());
            assertNull(candidate.getReason());
            assertNull(candidate.getMetadata());
        }
    }

    // ========================================================================
    // Getter and Setter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set toolId")
        void shouldGetAndSetToolId() {
            ToolCandidate candidate = ToolCandidate.builder().build();

            candidate.setToolId("new-tool-id");

            assertEquals("new-tool-id", candidate.getToolId());
        }

        @Test
        @DisplayName("should get and set name")
        void shouldGetAndSetName() {
            ToolCandidate candidate = ToolCandidate.builder().build();

            candidate.setName("Updated Name");

            assertEquals("Updated Name", candidate.getName());
        }

        @Test
        @DisplayName("should get and set description")
        void shouldGetAndSetDescription() {
            ToolCandidate candidate = ToolCandidate.builder().build();

            candidate.setDescription("A detailed description");

            assertEquals("A detailed description", candidate.getDescription());
        }

        @Test
        @DisplayName("should get and set category")
        void shouldGetAndSetCategory() {
            ToolCandidate candidate = ToolCandidate.builder().build();

            candidate.setCategory("finance");

            assertEquals("finance", candidate.getCategory());
        }

        @Test
        @DisplayName("should get and set confidence")
        void shouldGetAndSetConfidence() {
            ToolCandidate candidate = ToolCandidate.builder().build();

            candidate.setConfidence(0.87);

            assertEquals(0.87, candidate.getConfidence());
        }

        @Test
        @DisplayName("should get and set reason")
        void shouldGetAndSetReason() {
            ToolCandidate candidate = ToolCandidate.builder().build();

            candidate.setReason("Matched keywords");

            assertEquals("Matched keywords", candidate.getReason());
        }

        @Test
        @DisplayName("should get and set metadata")
        void shouldGetAndSetMetadata() {
            ToolCandidate candidate = ToolCandidate.builder().build();
            Map<String, Object> metadata = Map.of("score", 0.9, "rank", 1);

            candidate.setMetadata(metadata);

            assertEquals(metadata, candidate.getMetadata());
        }
    }

    // ========================================================================
    // Confidence values tests
    // ========================================================================

    @Nested
    @DisplayName("Confidence values")
    class ConfidenceValuesTests {

        @Test
        @DisplayName("should accept high confidence value")
        void shouldAcceptHighConfidenceValue() {
            ToolCandidate candidate = ToolCandidate.builder()
                .confidence(1.0)
                .build();

            assertEquals(1.0, candidate.getConfidence());
        }

        @Test
        @DisplayName("should accept low confidence value")
        void shouldAcceptLowConfidenceValue() {
            ToolCandidate candidate = ToolCandidate.builder()
                .confidence(0.1)
                .build();

            assertEquals(0.1, candidate.getConfidence());
        }

        @Test
        @DisplayName("should accept zero confidence value")
        void shouldAcceptZeroConfidenceValue() {
            ToolCandidate candidate = ToolCandidate.builder()
                .confidence(0.0)
                .build();

            assertEquals(0.0, candidate.getConfidence());
        }
    }

    // ========================================================================
    // Equality tests (Lombok @Data)
    // ========================================================================

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("should be equal for same values")
        void shouldBeEqualForSameValues() {
            ToolCandidate candidate1 = ToolCandidate.builder()
                .toolId("tool-1")
                .name("Test")
                .confidence(0.8)
                .build();

            ToolCandidate candidate2 = ToolCandidate.builder()
                .toolId("tool-1")
                .name("Test")
                .confidence(0.8)
                .build();

            assertEquals(candidate1, candidate2);
            assertEquals(candidate1.hashCode(), candidate2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different toolId")
        void shouldNotBeEqualForDifferentToolId() {
            ToolCandidate candidate1 = ToolCandidate.builder()
                .toolId("tool-1")
                .name("Test")
                .build();

            ToolCandidate candidate2 = ToolCandidate.builder()
                .toolId("tool-2")
                .name("Test")
                .build();

            assertNotEquals(candidate1, candidate2);
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
            ToolCandidate candidate = ToolCandidate.builder()
                .toolId("tool-123")
                .name("My Tool")
                .confidence(0.75)
                .build();

            String str = candidate.toString();

            assertNotNull(str);
            assertTrue(str.contains("tool-123"));
            assertTrue(str.contains("My Tool"));
            assertTrue(str.contains("0.75"));
        }
    }
}

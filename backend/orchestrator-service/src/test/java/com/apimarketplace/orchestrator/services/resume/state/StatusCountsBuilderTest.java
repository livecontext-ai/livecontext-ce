package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.execution.StatusCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StatusCountsBuilder")
class StatusCountsBuilderTest {

    @Mock
    private StateReconstructorHelper helper;

    private StatusCountsBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new StatusCountsBuilder(helper);
    }

    @Nested
    @DisplayName("getStatusCountsMap()")
    class GetStatusCountsMapTests {

        @Test
        @DisplayName("Should return counts map by stepId")
        void shouldReturnCountsByStepId() {
            StatusCounts counts = new StatusCounts();
            counts.incrementTotal();
            counts.incrementCompleted();

            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();
            stepStatusCounts.put("mcp:step1", counts);

            Map<String, Integer> result = builder.getStatusCountsMap(
                "mcp:step1", "My Step", stepStatusCounts
            );

            assertNotNull(result);
            assertEquals(1, result.get("completed"));
            assertEquals(0, result.get("failed"));
            assertEquals(0, result.get("skipped"));
            assertEquals(0, result.get("running"));
            assertEquals(1, result.get("total"));
        }

        @Test
        @DisplayName("Should fallback to alias when stepId not found")
        void shouldFallbackToAlias() {
            StatusCounts counts = new StatusCounts();
            counts.incrementTotal();
            counts.incrementFailed();

            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();
            stepStatusCounts.put("My Step", counts);

            Map<String, Integer> result = builder.getStatusCountsMap(
                "mcp:step1", "My Step", stepStatusCounts
            );

            assertNotNull(result);
            assertEquals(1, result.get("failed"));
        }

        @Test
        @DisplayName("Should try without prefix when both stepId and alias miss")
        void shouldTryWithoutPrefix() {
            StatusCounts counts = new StatusCounts();
            counts.incrementTotal();
            counts.incrementSkipped();

            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();
            stepStatusCounts.put("step1", counts);

            Map<String, Integer> result = builder.getStatusCountsMap(
                "mcp:step1", "Other Alias", stepStatusCounts
            );

            assertNotNull(result);
            assertEquals(1, result.get("skipped"));
        }

        @Test
        @DisplayName("Should return null when no counts found")
        void shouldReturnNullWhenNotFound() {
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            Map<String, Integer> result = builder.getStatusCountsMap(
                "mcp:missing", "Missing Alias", stepStatusCounts
            );

            assertNull(result);
        }
    }
}

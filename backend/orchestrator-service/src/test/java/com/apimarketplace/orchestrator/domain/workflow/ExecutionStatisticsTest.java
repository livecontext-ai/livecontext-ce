package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExecutionStatistics")
class ExecutionStatisticsTest {

    @Nested
    @DisplayName("empty()")
    class EmptyTests {

        @Test
        @DisplayName("Should create statistics with all zeros")
        void shouldCreateWithAllZeros() {
            ExecutionStatistics stats = ExecutionStatistics.empty();
            assertEquals(0, stats.totalSteps());
            assertEquals(0, stats.completedSteps());
            assertEquals(0, stats.failedSteps());
            assertEquals(0, stats.skippedSteps());
            assertEquals(0, stats.pendingSteps());
            assertEquals(0L, stats.totalExecutionTime());
            assertEquals(RunStatus.PENDING, stats.overallStatus());
            assertEquals(0, stats.currentLevel());
            assertEquals(0, stats.maxLevel());
            assertNotNull(stats.additionalMetrics());
        }
    }

    @Nested
    @DisplayName("getSuccessRate()")
    class SuccessRateTests {

        @Test
        @DisplayName("Should return 0 when no steps")
        void shouldReturnZeroWhenNoSteps() {
            ExecutionStatistics stats = ExecutionStatistics.empty();
            assertEquals(0.0, stats.getSuccessRate());
        }

        @Test
        @DisplayName("Should calculate correct success rate")
        void shouldCalculateCorrectRate() {
            ExecutionStatistics stats = new ExecutionStatistics(
                10, 7, 2, 1, 0, 1000L, RunStatus.COMPLETED, 3, 5, new HashMap<>()
            );
            assertEquals(0.7, stats.getSuccessRate(), 0.001);
        }

        @Test
        @DisplayName("Should return 1.0 when all complete")
        void shouldReturn1WhenAllComplete() {
            ExecutionStatistics stats = new ExecutionStatistics(
                5, 5, 0, 0, 0, 500L, RunStatus.COMPLETED, 1, 1, new HashMap<>()
            );
            assertEquals(1.0, stats.getSuccessRate(), 0.001);
        }
    }

    @Nested
    @DisplayName("isComplete()")
    class IsCompleteTests {

        @Test
        @DisplayName("Should return true when COMPLETED")
        void shouldReturnTrueWhenCompleted() {
            ExecutionStatistics stats = new ExecutionStatistics(
                5, 5, 0, 0, 0, 100L, RunStatus.COMPLETED, 1, 1, new HashMap<>()
            );
            assertTrue(stats.isComplete());
        }

        @Test
        @DisplayName("Should return true when FAILED")
        void shouldReturnTrueWhenFailed() {
            ExecutionStatistics stats = new ExecutionStatistics(
                5, 3, 2, 0, 0, 100L, RunStatus.FAILED, 1, 1, new HashMap<>()
            );
            assertTrue(stats.isComplete());
        }

        @Test
        @DisplayName("Should return false when RUNNING")
        void shouldReturnFalseWhenRunning() {
            ExecutionStatistics stats = new ExecutionStatistics(
                5, 2, 0, 0, 3, 50L, RunStatus.RUNNING, 1, 1, new HashMap<>()
            );
            assertFalse(stats.isComplete());
        }

        @Test
        @DisplayName("Should return false when PENDING")
        void shouldReturnFalseWhenPending() {
            ExecutionStatistics stats = ExecutionStatistics.empty();
            assertFalse(stats.isComplete());
        }
    }

    @Nested
    @DisplayName("progressPercentage()")
    class ProgressPercentageTests {

        @Test
        @DisplayName("Should return 0 when no steps")
        void shouldReturnZeroWhenNoSteps() {
            ExecutionStatistics stats = ExecutionStatistics.empty();
            assertEquals(0.0, stats.progressPercentage());
        }

        @Test
        @DisplayName("Should calculate correct percentage")
        void shouldCalculateCorrectPercentage() {
            ExecutionStatistics stats = new ExecutionStatistics(
                10, 3, 2, 1, 4, 200L, RunStatus.RUNNING, 2, 5, new HashMap<>()
            );
            // (3 + 2 + 1) / 10 * 100 = 60.0
            assertEquals(60.0, stats.progressPercentage(), 0.001);
        }

        @Test
        @DisplayName("Should return 100 when all done")
        void shouldReturn100WhenAllDone() {
            ExecutionStatistics stats = new ExecutionStatistics(
                5, 3, 1, 1, 0, 100L, RunStatus.COMPLETED, 1, 1, new HashMap<>()
            );
            assertEquals(100.0, stats.progressPercentage(), 0.001);
        }
    }
}

package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExecutionMetricsCollector")
class ExecutionMetricsCollectorTest {

    private ExecutionMetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ExecutionMetricsCollector();
    }

    @Nested
    @DisplayName("Step Item Metrics")
    class StepItemMetricsTests {

        @Test
        @DisplayName("Should record and retrieve step item metrics")
        void shouldRecordAndRetrieve() {
            ExecutionMetricsCollector.ItemMetrics metrics =
                new ExecutionMetricsCollector.ItemMetrics(5, 2, 1, 0, 8, 10, Map.of());
            collector.recordStepItemMetrics("mcp:step1", metrics);

            ExecutionMetricsCollector.ItemMetrics retrieved = collector.getStepItemMetrics("mcp:step1");
            assertNotNull(retrieved);
            assertEquals(5, retrieved.success());
            assertEquals(2, retrieved.failure());
        }

        @Test
        @DisplayName("Should return null for non-existent step")
        void shouldReturnNullForNonExistent() {
            assertNull(collector.getStepItemMetrics("mcp:nonexistent"));
        }

        @Test
        @DisplayName("Should handle null stepId in record")
        void shouldHandleNullStepIdInRecord() {
            ExecutionMetricsCollector.ItemMetrics metrics =
                new ExecutionMetricsCollector.ItemMetrics(1, 0, 0, 0, 1, 1, Map.of());
            collector.recordStepItemMetrics(null, metrics);
            // No exception
        }

        @Test
        @DisplayName("Should handle null metrics in record")
        void shouldHandleNullMetricsInRecord() {
            collector.recordStepItemMetrics("mcp:step1", null);
            assertNull(collector.getStepItemMetrics("mcp:step1"));
        }

        @Test
        @DisplayName("Should return null when getting with null stepId")
        void shouldReturnNullForNullStepId() {
            assertNull(collector.getStepItemMetrics(null));
        }
    }

    @Nested
    @DisplayName("Step Execution Times")
    class StepExecutionTimeTests {

        @Test
        @DisplayName("Should record and retrieve execution time")
        void shouldRecordAndRetrieve() {
            collector.recordStepExecutionTime("step1", 500L);
            assertEquals(500L, collector.getStepExecutionTime("step1"));
        }

        @Test
        @DisplayName("Should return 0 for unrecorded step")
        void shouldReturnZeroForUnrecorded() {
            assertEquals(0L, collector.getStepExecutionTime("nonexistent"));
        }

        @Test
        @DisplayName("Should handle null stepId")
        void shouldHandleNullStepId() {
            collector.recordStepExecutionTime(null, 100L);
            // No exception - null is ignored
        }

        @Test
        @DisplayName("Should return all execution times")
        void shouldReturnAllExecutionTimes() {
            collector.recordStepExecutionTime("step1", 100L);
            collector.recordStepExecutionTime("step2", 200L);

            Map<String, Long> times = collector.getStepExecutionTimes();
            assertEquals(2, times.size());
            assertEquals(100L, times.get("step1"));
            assertEquals(200L, times.get("step2"));
        }
    }

    @Nested
    @DisplayName("StepMetricsAccumulator")
    class StepMetricsAccumulatorTests {

        @Test
        @DisplayName("Should create accumulator for step")
        void shouldCreateAccumulator() {
            ExecutionMetricsCollector.StepMetricsAccumulator acc =
                collector.getOrCreateStepMetricsAccumulator("mcp:step1");
            assertNotNull(acc);
        }

        @Test
        @DisplayName("Should return same accumulator for same step")
        void shouldReturnSameAccumulator() {
            ExecutionMetricsCollector.StepMetricsAccumulator acc1 =
                collector.getOrCreateStepMetricsAccumulator("mcp:step1");
            ExecutionMetricsCollector.StepMetricsAccumulator acc2 =
                collector.getOrCreateStepMetricsAccumulator("mcp:step1");
            assertSame(acc1, acc2);
        }

        @Test
        @DisplayName("Should throw on null stepId")
        void shouldThrowOnNullStepId() {
            assertThrows(IllegalArgumentException.class,
                () -> collector.getOrCreateStepMetricsAccumulator(null));
        }

        @Test
        @DisplayName("Should track item dispatch and completion")
        void shouldTrackDispatchAndCompletion() {
            ExecutionMetricsCollector.StepMetricsAccumulator acc =
                collector.getOrCreateStepMetricsAccumulator("mcp:step1");

            acc.onItemDispatched();
            acc.onItemDispatched();
            acc.onItemDispatched();

            ExecutionMetricsCollector.ItemMetrics snap1 = acc.snapshot();
            assertEquals(3, snap1.running());
            assertEquals(3, snap1.total());
            assertEquals(0, snap1.processed());

            acc.onItemCompleted(NodeStatus.COMPLETED);
            acc.onItemCompleted(NodeStatus.FAILED);

            ExecutionMetricsCollector.ItemMetrics snap2 = acc.snapshot();
            assertEquals(1, snap2.running());
            assertEquals(1, snap2.success());
            assertEquals(1, snap2.failure());
            assertEquals(2, snap2.processed());
        }

        @Test
        @DisplayName("Should track skipped items")
        void shouldTrackSkippedItems() {
            ExecutionMetricsCollector.StepMetricsAccumulator acc =
                collector.getOrCreateStepMetricsAccumulator("mcp:step1");

            acc.onItemSkippedBeforeDispatch();
            acc.onItemSkippedBeforeDispatch();

            ExecutionMetricsCollector.ItemMetrics snap = acc.snapshot();
            assertEquals(2, snap.skipped());
            assertEquals(2, snap.processed());
            assertEquals(2, snap.total());
            assertEquals(0, snap.running());
        }

        @Test
        @DisplayName("Should track HTTP status codes")
        void shouldTrackHttpStatusCodes() {
            ExecutionMetricsCollector.StepMetricsAccumulator acc =
                collector.getOrCreateStepMetricsAccumulator("mcp:step1");

            acc.onHttpStatus(200);
            acc.onHttpStatus(200);
            acc.onHttpStatus(404);
            acc.onHttpStatus(null); // Should be ignored

            ExecutionMetricsCollector.ItemMetrics snap = acc.snapshot();
            assertEquals(2, snap.httpStatusCounts().get(200));
            assertEquals(1, snap.httpStatusCounts().get(404));
        }

        @Test
        @DisplayName("Should handle null status in onItemCompleted")
        void shouldHandleNullStatusInOnItemCompleted() {
            ExecutionMetricsCollector.StepMetricsAccumulator acc =
                collector.getOrCreateStepMetricsAccumulator("mcp:step1");
            acc.onItemDispatched();
            acc.onItemCompleted(null);

            ExecutionMetricsCollector.ItemMetrics snap = acc.snapshot();
            assertEquals(0, snap.running());
            assertEquals(1, snap.processed());
        }

        @Test
        @DisplayName("Should handle SKIPPED items via onItemCompleted")
        void shouldHandleSkippedViaOnItemCompleted() {
            ExecutionMetricsCollector.StepMetricsAccumulator acc =
                collector.getOrCreateStepMetricsAccumulator("mcp:step1");

            acc.onItemDispatched();
            acc.onItemCompleted(NodeStatus.SKIPPED);
            acc.onItemDispatched();
            acc.onItemCompleted(NodeStatus.SKIPPED);

            ExecutionMetricsCollector.ItemMetrics snap = acc.snapshot();
            assertEquals(2, snap.skipped());
        }

        @Test
        @DisplayName("resetRunning should keep running non-negative")
        void resetRunningShouldKeepNonNegative() {
            ExecutionMetricsCollector.StepMetricsAccumulator acc =
                collector.getOrCreateStepMetricsAccumulator("mcp:step1");
            acc.resetRunning();
            ExecutionMetricsCollector.ItemMetrics snap = acc.snapshot();
            assertTrue(snap.running() >= 0);
        }
    }

    @Nested
    @DisplayName("ItemMetrics")
    class ItemMetricsTests {

        @Test
        @DisplayName("Should clamp negative values to 0")
        void shouldClampNegativeValues() {
            ExecutionMetricsCollector.ItemMetrics m =
                new ExecutionMetricsCollector.ItemMetrics(-1, -2, -3, -4, -5, -6, null);
            assertEquals(0, m.success());
            assertEquals(0, m.failure());
            assertEquals(0, m.skipped());
            assertEquals(0, m.running());
            assertEquals(0, m.processed());
            assertEquals(0, m.total());
            assertNotNull(m.httpStatusCounts());
        }

        @Test
        @DisplayName("single(COMPLETED) should create correct metrics")
        void singleCompletedShouldBeCorrect() {
            ExecutionMetricsCollector.ItemMetrics m =
                ExecutionMetricsCollector.ItemMetrics.single(NodeStatus.COMPLETED);
            assertEquals(1, m.success());
            assertEquals(0, m.failure());
            assertEquals(1, m.processed());
            assertEquals(1, m.total());
        }

        @Test
        @DisplayName("single(FAILED) should create correct metrics")
        void singleFailedShouldBeCorrect() {
            ExecutionMetricsCollector.ItemMetrics m =
                ExecutionMetricsCollector.ItemMetrics.single(NodeStatus.FAILED);
            assertEquals(0, m.success());
            assertEquals(1, m.failure());
        }

        @Test
        @DisplayName("single(SKIPPED) should create correct metrics")
        void singleSkippedShouldBeCorrect() {
            ExecutionMetricsCollector.ItemMetrics m =
                ExecutionMetricsCollector.ItemMetrics.single(NodeStatus.SKIPPED);
            assertEquals(1, m.skipped());
        }

        @Test
        @DisplayName("single(RUNNING) should create empty metrics")
        void singleRunningShouldBeEmpty() {
            ExecutionMetricsCollector.ItemMetrics m =
                ExecutionMetricsCollector.ItemMetrics.single(NodeStatus.RUNNING);
            assertEquals(0, m.success());
            assertEquals(0, m.failure());
            assertEquals(0, m.total());
        }

        @Test
        @DisplayName("fromCounts should create correct metrics")
        void fromCountsShouldBeCorrect() {
            ExecutionMetricsCollector.ItemMetrics m =
                ExecutionMetricsCollector.ItemMetrics.fromCounts(3, 1, 2, 6, 10);
            assertEquals(3, m.success());
            assertEquals(1, m.failure());
            assertEquals(2, m.skipped());
            assertEquals(0, m.running());
            assertEquals(6, m.processed());
            assertEquals(10, m.total());
        }

        @Test
        @DisplayName("toMap should include all fields")
        void toMapShouldIncludeAllFields() {
            ExecutionMetricsCollector.ItemMetrics m =
                new ExecutionMetricsCollector.ItemMetrics(1, 2, 3, 4, 5, 6, Map.of(200, 5));
            Map<String, Object> map = m.toMap();

            assertEquals(1, map.get("completed"));
            assertEquals(2, map.get("failed"));
            assertEquals(3, map.get("skipped"));
            assertEquals(4, map.get("running"));
            assertEquals(5, map.get("processed"));
            assertEquals(6, map.get("total"));
            assertNotNull(map.get("httpStatusCounts"));
        }

        @Test
        @DisplayName("toMap should omit httpStatusCounts when empty")
        void toMapShouldOmitEmptyHttpStatus() {
            ExecutionMetricsCollector.ItemMetrics m =
                new ExecutionMetricsCollector.ItemMetrics(1, 0, 0, 0, 1, 1, Map.of());
            Map<String, Object> map = m.toMap();
            assertFalse(map.containsKey("httpStatusCounts"));
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTests {

        @Test
        @DisplayName("Should clear all metrics")
        void shouldClearAllMetrics() {
            collector.recordStepItemMetrics("mcp:step1",
                new ExecutionMetricsCollector.ItemMetrics(1, 0, 0, 0, 1, 1, Map.of()));
            collector.recordStepExecutionTime("step1", 100L);

            collector.clear();

            assertNull(collector.getStepItemMetrics("mcp:step1"));
            assertEquals(0L, collector.getStepExecutionTime("step1"));
            assertTrue(collector.getStepExecutionTimes().isEmpty());
        }
    }
}

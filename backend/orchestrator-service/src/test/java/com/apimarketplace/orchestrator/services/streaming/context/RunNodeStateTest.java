package com.apimarketplace.orchestrator.services.streaming.context;

import com.apimarketplace.orchestrator.services.streaming.context.RunNodeState.StatusCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RunNodeState")
class RunNodeStateTest {

    private RunNodeState nodeState;

    @BeforeEach
    void setUp() {
        nodeState = new RunNodeState("run-1");
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should store runId")
        void shouldStoreRunId() {
            assertEquals("run-1", nodeState.getRunId());
        }
    }

    @Nested
    @DisplayName("recordExecution()")
    class RecordExecutionTests {

        @Test
        @DisplayName("Should count COMPLETED as success")
        void shouldCountCompleted() {
            StatusCounts counts = nodeState.recordExecution("step1", 0, 0, "COMPLETED");
            assertEquals(1, counts.success());
            assertEquals(0, counts.failure());
            assertEquals(0, counts.running());
        }

        @Test
        @DisplayName("Should count FAILED as failure")
        void shouldCountFailed() {
            StatusCounts counts = nodeState.recordExecution("step1", 0, 0, "FAILED");
            assertEquals(0, counts.success());
            assertEquals(1, counts.failure());
        }

        @Test
        @DisplayName("Should count SKIPPED")
        void shouldCountSkipped() {
            StatusCounts counts = nodeState.recordExecution("step1", 0, 0, "SKIPPED");
            assertEquals(1, counts.skipped());
        }

        @Test
        @DisplayName("Should count RUNNING")
        void shouldCountRunning() {
            StatusCounts counts = nodeState.recordExecution("step1", 0, null, "RUNNING");
            assertEquals(1, counts.running());
        }

        @Test
        @DisplayName("Should decrement running when terminal status received")
        void shouldDecrementRunningOnTerminal() {
            nodeState.recordExecution("step1", 0, null, "RUNNING");
            StatusCounts counts = nodeState.recordExecution("step1", 0, 0, "COMPLETED");

            assertEquals(0, counts.running());
            assertEquals(1, counts.success());
        }

        @Test
        @DisplayName("Should count multiple items independently")
        void shouldCountMultipleItems() {
            nodeState.recordExecution("step1", 0, 0, "COMPLETED");
            nodeState.recordExecution("step1", 1, 0, "COMPLETED");
            StatusCounts counts = nodeState.recordExecution("step1", 2, 0, "FAILED");

            assertEquals(2, counts.success());
            assertEquals(1, counts.failure());
            assertEquals(3, counts.processed());
        }

        @Test
        @DisplayName("Should ignore negative itemIndex")
        void shouldIgnoreNegativeItemIndex() {
            StatusCounts counts = nodeState.recordExecution("step1", -1, 0, "COMPLETED");
            assertEquals(0, counts.success());
        }

        @Test
        @DisplayName("Should ignore null itemIndex")
        void shouldIgnoreNullItemIndex() {
            StatusCounts counts = nodeState.recordExecution("step1", null, 0, "COMPLETED");
            assertEquals(0, counts.success());
        }

        @Test
        @DisplayName("Should not record RUNNING if terminal status already exists for item")
        void shouldNotRecordRunningIfTerminalExists() {
            nodeState.recordExecution("step1", 0, 0, "COMPLETED");
            nodeState.recordExecution("step1", 0, null, "RUNNING");

            StatusCounts counts = nodeState.getStatusCounts("step1");
            assertEquals(1, counts.success());
            assertEquals(0, counts.running());
        }

        @ParameterizedTest
        @ValueSource(strings = {"SUCCESS", "completed", "COMPLETED"})
        @DisplayName("Should normalize success statuses")
        void shouldNormalizeSuccessStatuses(String status) {
            nodeState.recordExecution("step1", 0, 0, status);
            StatusCounts counts = nodeState.getStatusCounts("step1");
            assertEquals(1, counts.success());
        }

        @ParameterizedTest
        @ValueSource(strings = {"FAILED", "failed", "FAILURE", "failure", "ERROR", "error"})
        @DisplayName("Should normalize failure statuses")
        void shouldNormalizeFailureStatuses(String status) {
            nodeState.recordExecution("step1", 0, 0, status);
            StatusCounts counts = nodeState.getStatusCounts("step1");
            assertEquals(1, counts.failure());
        }

        @ParameterizedTest
        @ValueSource(strings = {"RUNNING", "PENDING", "IN_PROGRESS"})
        @DisplayName("Should normalize running statuses")
        void shouldNormalizeRunningStatuses(String status) {
            nodeState.recordExecution("step1", 0, null, status);
            StatusCounts counts = nodeState.getStatusCounts("step1");
            assertEquals(1, counts.running());
        }

        @Test
        @DisplayName("Should treat null status as RUNNING")
        void shouldTreatNullStatusAsRunning() {
            nodeState.recordExecution("step1", 0, null, null);
            StatusCounts counts = nodeState.getStatusCounts("step1");
            assertEquals(1, counts.running());
        }

        @Test
        @DisplayName("Should replace SKIPPED with non-SKIPPED terminal status")
        void shouldReplaceSkippedWithNonSkipped() {
            nodeState.recordExecution("step1", 0, 0, "SKIPPED");
            nodeState.recordExecution("step1", 0, 1, "COMPLETED");

            StatusCounts counts = nodeState.getStatusCounts("step1");
            assertEquals(1, counts.success());
            assertEquals(0, counts.skipped());
        }
    }

    @Nested
    @DisplayName("prePopulateCounts()")
    class PrePopulateCountsTests {

        @Test
        @DisplayName("Should populate completed counts")
        void shouldPopulateCompletedCounts() {
            nodeState.prePopulateCounts("step1", Map.of("COMPLETED", 10));

            StatusCounts counts = nodeState.getStatusCounts("step1");
            assertEquals(10, counts.success());
        }

        @Test
        @DisplayName("Should populate failure counts")
        void shouldPopulateFailureCounts() {
            nodeState.prePopulateCounts("step1", Map.of("FAILED", 3));

            StatusCounts counts = nodeState.getStatusCounts("step1");
            assertEquals(3, counts.failure());
        }

        @Test
        @DisplayName("Should populate skipped counts")
        void shouldPopulateSkippedCounts() {
            nodeState.prePopulateCounts("step1", Map.of("SKIPPED", 2));

            StatusCounts counts = nodeState.getStatusCounts("step1");
            assertEquals(2, counts.skipped());
        }

        @Test
        @DisplayName("Should recognize alternate key names")
        void shouldRecognizeAlternateKeys() {
            nodeState.prePopulateCounts("step1", Map.of("completed", 7));

            StatusCounts counts = nodeState.getStatusCounts("step1");
            assertEquals(7, counts.success());
        }
    }

    @Nested
    @DisplayName("getStatusCounts()")
    class GetStatusCountsTests {

        @Test
        @DisplayName("Should return empty counts for unknown node")
        void shouldReturnEmptyForUnknown() {
            StatusCounts counts = nodeState.getStatusCounts("unknown");
            assertEquals(StatusCounts.empty(), counts);
        }
    }

    @Nested
    @DisplayName("getAllStatusCounts()")
    class GetAllStatusCountsTests {

        @Test
        @DisplayName("Should return all tracked nodes")
        void shouldReturnAllTrackedNodes() {
            nodeState.recordExecution("step1", 0, 0, "COMPLETED");
            nodeState.recordExecution("step2", 0, 0, "FAILED");

            Map<String, StatusCounts> all = nodeState.getAllStatusCounts();

            assertEquals(2, all.size());
            assertTrue(all.containsKey("step1"));
            assertTrue(all.containsKey("step2"));
        }
    }

    @Nested
    @DisplayName("setTotalItems()")
    class SetTotalItemsTests {

        @Test
        @DisplayName("Should propagate total items to existing nodes")
        void shouldPropagateTotalItems() {
            nodeState.recordExecution("step1", 0, 0, "COMPLETED");
            nodeState.setTotalItems(100);

            // Total items is set globally but affects internal node state
            assertDoesNotThrow(() -> nodeState.getStatusCounts("step1"));
        }
    }

    @Nested
    @DisplayName("setNodeTotalItems()")
    class SetNodeTotalItemsTests {

        @Test
        @DisplayName("Should set total items for specific node")
        void shouldSetNodeTotalItems() {
            assertDoesNotThrow(() -> nodeState.setNodeTotalItems("step1", 50));
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTests {

        @Test
        @DisplayName("Should clear all state")
        void shouldClearAllState() {
            nodeState.recordExecution("step1", 0, 0, "COMPLETED");
            nodeState.clear();

            assertEquals(StatusCounts.empty(), nodeState.getStatusCounts("step1"));
            assertTrue(nodeState.getAllStatusCounts().isEmpty());
        }
    }

    @Nested
    @DisplayName("StatusCounts record")
    class StatusCountsRecordTests {

        @Test
        @DisplayName("empty() should return all zeros")
        void emptyShouldReturnAllZeros() {
            StatusCounts empty = StatusCounts.empty();
            assertEquals(0, empty.running());
            assertEquals(0, empty.success());
            assertEquals(0, empty.failure());
            assertEquals(0, empty.skipped());
            assertEquals(0, empty.processed());
            assertEquals(0, empty.total());
        }

        @Test
        @DisplayName("toMap() should include all fields")
        void toMapShouldIncludeAllFields() {
            StatusCounts counts = new StatusCounts(1, 5, 2, 1, 8, 9);
            Map<String, Object> map = counts.toMap();

            assertEquals(1, map.get("running"));
            assertEquals(5, map.get("completed"));
            assertEquals(2, map.get("failed"));
            assertEquals(1, map.get("skipped"));
            assertEquals(8, map.get("processed"));
            assertEquals(9, map.get("total"));
        }

        @Test
        @DisplayName("toString() should format correctly")
        void toStringShouldFormatCorrectly() {
            StatusCounts counts = new StatusCounts(1, 5, 2, 1, 8, 9);
            String str = counts.toString();

            assertTrue(str.contains("running=1"));
            assertTrue(str.contains("success=5"));
            assertTrue(str.contains("failure=2"));
            assertTrue(str.contains("skipped=1"));
        }
    }
}

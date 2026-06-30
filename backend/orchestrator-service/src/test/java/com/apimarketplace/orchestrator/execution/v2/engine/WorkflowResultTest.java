package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkflowResult record.
 */
@DisplayName("WorkflowResult")
class WorkflowResultTest {

    @Nested
    @DisplayName("aggregate")
    class Aggregate {

        @Test
        @DisplayName("should aggregate all successful items")
        void shouldAggregateAllSuccessful() {
            ExecutionContext ctx = createTestContext();
            List<ItemResult> items = List.of(
                ItemResult.success("item-0", ctx),
                ItemResult.success("item-1", ctx)
            );

            WorkflowResult result = WorkflowResult.aggregate("run-1", items);

            assertEquals("run-1", result.runId());
            assertEquals(NodeStatus.COMPLETED, result.overallStatus());
            assertEquals(2, result.totalItems());
            assertEquals(2, result.successItems());
            assertEquals(0, result.failedItems());
            assertTrue(result.isSuccess());
            assertTrue(result.errorMessage().isEmpty());
        }

        @Test
        @DisplayName("should aggregate all failed items")
        void shouldAggregateAllFailed() {
            List<ItemResult> items = List.of(
                ItemResult.failure("item-0", "Error 1"),
                ItemResult.failure("item-1", "Error 2")
            );

            WorkflowResult result = WorkflowResult.aggregate("run-1", items);

            assertEquals(NodeStatus.FAILED, result.overallStatus());
            assertEquals(2, result.totalItems());
            assertEquals(0, result.successItems());
            assertEquals(2, result.failedItems());
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("should aggregate partial success (some success, some failures)")
        void shouldAggregatePartialSuccess() {
            ExecutionContext ctx = createTestContext();
            List<ItemResult> items = List.of(
                ItemResult.success("item-0", ctx),
                ItemResult.failure("item-1", "Error")
            );

            WorkflowResult result = WorkflowResult.aggregate("run-1", items);

            assertEquals(NodeStatus.COMPLETED, result.overallStatus()); // partial success
            assertEquals(2, result.totalItems());
            assertEquals(1, result.successItems());
            assertEquals(1, result.failedItems());
        }

        @Test
        @DisplayName("should handle empty item list")
        void shouldHandleEmptyItemList() {
            WorkflowResult result = WorkflowResult.aggregate("run-1", List.of());

            assertEquals(NodeStatus.COMPLETED, result.overallStatus());
            assertEquals(0, result.totalItems());
            assertEquals(0, result.successItems());
            assertEquals(0, result.failedItems());
        }
    }

    @Nested
    @DisplayName("failed")
    class Failed {

        @Test
        @DisplayName("should create failed result with error message")
        void shouldCreateFailedResult() {
            WorkflowResult result = WorkflowResult.failed("run-1", "Something went wrong");

            assertEquals("run-1", result.runId());
            assertEquals(NodeStatus.FAILED, result.overallStatus());
            assertFalse(result.isSuccess());
            assertEquals(0, result.totalItems());
            assertTrue(result.errorMessage().isPresent());
            assertEquals("Something went wrong", result.errorMessage().get());
            assertTrue(result.itemResults().isEmpty());
        }
    }

    @Nested
    @DisplayName("isSuccess")
    class IsSuccess {

        @Test
        @DisplayName("should return true for SUCCESS status")
        void shouldReturnTrueForSuccess() {
            WorkflowResult result = new WorkflowResult("run-1", NodeStatus.COMPLETED, 1, 1, 0, List.of(), Optional.empty());
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("should return false for FAILURE status")
        void shouldReturnFalseForFailure() {
            WorkflowResult result = new WorkflowResult("run-1", NodeStatus.FAILED, 1, 0, 1, List.of(), Optional.empty());
            assertFalse(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("getStepOutputs")
    class GetStepOutputs {

        @Test
        @DisplayName("should return step outputs from successful items")
        void shouldReturnStepOutputs() {
            ExecutionContext ctx = createTestContextWithOutput("step-1", "result-value");

            List<ItemResult> items = List.of(
                ItemResult.success("item-0", ctx)
            );
            WorkflowResult result = WorkflowResult.aggregate("run-1", items);

            Map<String, Object> stepOutputs = result.getStepOutputs("step-1");
            assertNotNull(stepOutputs);
            assertEquals(1, stepOutputs.size());
            assertEquals("result-value", stepOutputs.get("item-0"));
        }

        @Test
        @DisplayName("should return empty map when no items have the step")
        void shouldReturnEmptyForMissingStep() {
            ExecutionContext ctx = createTestContext();
            List<ItemResult> items = List.of(ItemResult.success("item-0", ctx));
            WorkflowResult result = WorkflowResult.aggregate("run-1", items);

            Map<String, Object> stepOutputs = result.getStepOutputs("nonexistent-step");
            assertTrue(stepOutputs.isEmpty());
        }
    }

    private ExecutionContext createTestContext() {
        return ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), null);
    }

    private ExecutionContext createTestContextWithOutput(String stepId, Object value) {
        return new ExecutionContext(
            "run-1", "wr-1", "tenant-1", "item-0", 0,
            null, 0, 0,
            Map.of(),
            Map.of(stepId, value),
            com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create(),
            null
        );
    }
}

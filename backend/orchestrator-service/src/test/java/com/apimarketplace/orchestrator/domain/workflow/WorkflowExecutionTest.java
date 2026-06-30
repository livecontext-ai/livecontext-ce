package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for WorkflowExecution - the core domain class for workflow execution state.
 *
 * Tests cover: construction, status management, step results, loop tracking,
 * item-level tracking, decision evaluations, global data, output management,
 * execution checks, and statistics.
 */
@DisplayName("WorkflowExecution")
class WorkflowExecutionTest {

    private WorkflowPlan plan;
    private WorkflowExecution execution;

    @BeforeEach
    void setUp() {
        plan = createLinearPlan();
        execution = new WorkflowExecution("run-1", plan, Map.of("key1", "value1"));
    }

    // =========================================================================
    // Construction and Initialization
    // =========================================================================

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should initialize with correct defaults")
        void shouldInitializeWithCorrectDefaults() {
            assertThat(execution.getRunId()).isEqualTo("run-1");
            assertThat(execution.getPlan()).isSameAs(plan);
            assertThat(execution.getStatus()).isEqualTo(RunStatus.PENDING);
            assertThat(execution.getStartTime()).isNotNull();
            assertThat(execution.getEndTime()).isNull();
            assertThat(execution.getErrorMessage()).isNull();
            assertThat(execution.getLastException()).isNull();
            assertThat(execution.getCurrentLevel()).isEqualTo(0);
            assertThat(execution.getWorkflowRunId()).isNull();
            assertThat(execution.getPlanVersion()).isNull();
        }

        @Test
        @DisplayName("Should return defensive copy of initial inputs")
        void shouldReturnDefensiveCopyOfInitialInputs() {
            Map<String, Object> inputs = execution.getInitialInputs();
            inputs.put("hacked", "data");
            assertThat(execution.getInitialInputs()).doesNotContainKey("hacked");
        }

        @Test
        @DisplayName("Should handle null initial inputs")
        void shouldHandleNullInitialInputs() {
            WorkflowExecution exec = new WorkflowExecution("run-null", plan, null);

            assertThat(exec.getInitialInputs()).isEmpty();
        }

        @Test
        @DisplayName("Should start with empty step collections")
        void shouldStartWithEmptyStepCollections() {
            assertThat(execution.getCompletedSteps()).isEmpty();
            assertThat(execution.getFailedSteps()).isEmpty();
            assertThat(execution.getSkippedSteps()).isEmpty();
            assertThat(execution.getAllStepResults()).isEmpty();
            assertThat(execution.getStepOutputs()).isEmpty();
        }
    }

    // =========================================================================
    // Status Management
    // =========================================================================

    @Nested
    @DisplayName("Status Management")
    class StatusManagementTests {

        @Test
        @DisplayName("setStatus RUNNING should not set endTime")
        void setStatusRunningShouldNotSetEndTime() {
            execution.setStatus(RunStatus.RUNNING);

            assertThat(execution.getStatus()).isEqualTo(RunStatus.RUNNING);
            assertThat(execution.getEndTime()).isNull();
            assertThat(execution.isRunning()).isTrue();
        }

        @Test
        @DisplayName("setStatus COMPLETED should set endTime and totalExecutionTime")
        void setStatusCompletedShouldSetEndTime() {
            execution.setStatus(RunStatus.RUNNING);
            execution.setStatus(RunStatus.COMPLETED);

            assertThat(execution.getStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(execution.getEndTime()).isNotNull();
            assertThat(execution.getTotalExecutionTime()).isGreaterThanOrEqualTo(0);
            assertThat(execution.isCompleted()).isTrue();
        }

        @Test
        @DisplayName("setStatus FAILED should set endTime")
        void setStatusFailedShouldSetEndTime() {
            execution.setStatus(RunStatus.FAILED);

            assertThat(execution.getEndTime()).isNotNull();
            assertThat(execution.isFailed()).isTrue();
        }

        @Test
        @DisplayName("setStatus CANCELLED should set endTime")
        void setStatusCancelledShouldSetEndTime() {
            execution.setStatus(RunStatus.CANCELLED);

            assertThat(execution.getEndTime()).isNotNull();
            assertThat(execution.isFinished()).isTrue();
        }

        @Test
        @DisplayName("setStatus TIMEOUT should set endTime")
        void setStatusTimeoutShouldSetEndTime() {
            execution.setStatus(RunStatus.TIMEOUT);

            assertThat(execution.getEndTime()).isNotNull();
            assertThat(execution.isFinished()).isTrue();
        }

        @Test
        @DisplayName("isFinished should return true for terminal statuses")
        void isFinishedShouldReturnTrueForTerminalStatuses() {
            for (RunStatus status : List.of(
                    RunStatus.COMPLETED, RunStatus.FAILED,
                    RunStatus.CANCELLED, RunStatus.TIMEOUT)) {
                execution.setStatus(status);
                assertThat(execution.isFinished())
                        .as("isFinished() should be true for %s", status)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("isFinished should return false for non-terminal statuses")
        void isFinishedShouldReturnFalseForNonTerminalStatuses() {
            for (RunStatus status : List.of(
                    RunStatus.PENDING, RunStatus.RUNNING)) {
                execution.setStatus(status);
                assertThat(execution.isFinished())
                        .as("isFinished() should be false for %s", status)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("complete() should set COMPLETED status")
        void completeShouldSetCompletedStatus() {
            execution.complete();

            assertThat(execution.isCompleted()).isTrue();
            assertThat(execution.getEndTime()).isNotNull();
        }

        @Test
        @DisplayName("cancel() should set CANCELLED status")
        void cancelShouldSetCancelledStatus() {
            execution.cancel();

            assertThat(execution.getStatus()).isEqualTo(RunStatus.CANCELLED);
            assertThat(execution.isFinished()).isTrue();
        }

        @Test
        @DisplayName("setError should set message, exception, and FAILED status")
        void setErrorShouldSetAllFields() {
            RuntimeException exception = new RuntimeException("Test error");

            execution.setError("Something went wrong", exception);

            assertThat(execution.getErrorMessage()).isEqualTo("Something went wrong");
            assertThat(execution.getLastException()).isSameAs(exception);
            assertThat(execution.isFailed()).isTrue();
            assertThat(execution.getEndTime()).isNotNull();
        }

        @Test
        @DisplayName("setWorkflowRunId should store workflow run ID")
        void setWorkflowRunIdShouldStore() {
            UUID uuid = UUID.randomUUID();
            execution.setWorkflowRunId(uuid);

            assertThat(execution.getWorkflowRunId()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("setPlanVersion should store plan version")
        void setPlanVersionShouldStore() {
            assertThat(execution.getPlanVersion()).isNull();
            execution.setPlanVersion(3);
            assertThat(execution.getPlanVersion()).isEqualTo(3);
        }

        @Test
        @DisplayName("setPlanVersion null should clear")
        void setPlanVersionNullShouldClear() {
            execution.setPlanVersion(5);
            execution.setPlanVersion(null);
            assertThat(execution.getPlanVersion()).isNull();
        }
    }

    // =========================================================================
    // Step Results
    // =========================================================================

    @Nested
    @DisplayName("Step Results")
    class StepResultsTests {

        @Test
        @DisplayName("Should store completed step result and track in completedSteps")
        void shouldStoreCompletedStepResult() {
            StepExecutionResult result = StepExecutionResult.success(
                    "mcp:step_1", Map.of("data", "value"), 100);

            execution.setStepResult("mcp:step_1", result);

            assertThat(execution.hasStepResult("mcp:step_1")).isTrue();
            assertThat(execution.getStepResult("mcp:step_1")).isEqualTo(result);
            assertThat(execution.isStepCompleted("mcp:step_1")).isTrue();
            assertThat(execution.isStepFailed("mcp:step_1")).isFalse();
            assertThat(execution.isStepSkipped("mcp:step_1")).isFalse();
            assertThat(execution.getStepStatus("mcp:step_1")).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should store failed step result and track in failedSteps")
        void shouldStoreFailedStepResult() {
            StepExecutionResult result = StepExecutionResult.failure(
                    "mcp:step_1", "Error", new RuntimeException("boom"), 50);

            execution.setStepResult("mcp:step_1", result);

            assertThat(execution.isStepFailed("mcp:step_1")).isTrue();
            assertThat(execution.isStepCompleted("mcp:step_1")).isFalse();
            assertThat(execution.getStepStatus("mcp:step_1")).isEqualTo(NodeStatus.FAILED);
        }

        @Test
        @DisplayName("Should store skipped step result and track in skippedSteps")
        void shouldStoreSkippedStepResult() {
            StepExecutionResult result = StepExecutionResult.skipped("mcp:step_1", "Branch not taken");

            execution.setStepResult("mcp:step_1", result);

            assertThat(execution.isStepSkipped("mcp:step_1")).isTrue();
            assertThat(execution.isStepCompleted("mcp:step_1")).isFalse();
            assertThat(execution.getStepStatus("mcp:step_1")).isEqualTo(NodeStatus.SKIPPED);
        }

        @Test
        @DisplayName("Should update status when step transitions from one state to another")
        void shouldUpdateStatusOnTransition() {
            // First mark as failed
            StepExecutionResult failedResult = StepExecutionResult.failure(
                    "mcp:step_1", "Error", null, 50);
            execution.setStepResult("mcp:step_1", failedResult);
            assertThat(execution.isStepFailed("mcp:step_1")).isTrue();

            // Then mark as completed (retry succeeded)
            StepExecutionResult successResult = StepExecutionResult.success(
                    "mcp:step_1", Map.of("data", "value"), 100);
            execution.setStepResult("mcp:step_1", successResult);

            assertThat(execution.isStepCompleted("mcp:step_1")).isTrue();
            assertThat(execution.isStepFailed("mcp:step_1")).isFalse();
        }

        @Test
        @DisplayName("Should store step output when result has output")
        void shouldStoreStepOutput() {
            StepExecutionResult result = StepExecutionResult.success(
                    "mcp:step_1", Map.of("response", "data"), 100);

            execution.setStepResult("mcp:step_1", result);

            Map<String, Object> outputs = execution.getStepOutputs();
            assertThat(outputs).containsKey("mcp:step_1");
        }

        @Test
        @DisplayName("Should remove step output when result output is null")
        void shouldRemoveStepOutputWhenNull() {
            // First set a result with output
            execution.setStepResult("mcp:step_1",
                    StepExecutionResult.success("mcp:step_1", Map.of("a", "b"), 10));

            // Then set a result with null output
            execution.setStepResult("mcp:step_1",
                    StepExecutionResult.failure("mcp:step_1", "failed", null, 10));

            assertThat(execution.getStepOutputs()).doesNotContainKey("mcp:step_1");
        }

        @Test
        @DisplayName("clearStepResult should remove all traces of a step")
        void clearStepResultShouldRemoveAllTraces() {
            execution.setStepResult("mcp:step_1",
                    StepExecutionResult.success("mcp:step_1", Map.of("data", "v"), 10));

            execution.clearStepResult("mcp:step_1");

            assertThat(execution.hasStepResult("mcp:step_1")).isFalse();
            assertThat(execution.isStepCompleted("mcp:step_1")).isFalse();
            assertThat(execution.getStepOutputs()).doesNotContainKey("mcp:step_1");
        }

        @Test
        @DisplayName("clearStepResult with null should not throw")
        void clearStepResultWithNullShouldNotThrow() {
            execution.clearStepResult(null);
            // Should not throw
        }

        @Test
        @DisplayName("getStepStatus should return PENDING for unknown step")
        void getStepStatusShouldReturnPendingForUnknown() {
            assertThat(execution.getStepStatus("mcp:unknown")).isEqualTo(NodeStatus.PENDING);
        }

        @Test
        @DisplayName("isStepCompleted with null should return false")
        void isStepCompletedWithNullShouldReturnFalse() {
            assertThat(execution.isStepCompleted(null)).isFalse();
        }

        @Test
        @DisplayName("Should return defensive copies of step sets")
        void shouldReturnDefensiveCopiesOfStepSets() {
            execution.setStepResult("mcp:step_1",
                    StepExecutionResult.success("mcp:step_1", Map.of("a", "b"), 10));

            Set<String> completed = execution.getCompletedSteps();
            completed.add("hacked");

            assertThat(execution.getCompletedSteps()).doesNotContain("hacked");
        }

        @Test
        @DisplayName("Should handle timeout as failed (NodeStatus.FAILED)")
        void shouldHandleTimeoutAsFailed() {
            StepExecutionResult result = new StepExecutionResult(
                    "mcp:step_1", NodeStatus.FAILED, "Timed out", null, 5000, null);

            execution.setStepResult("mcp:step_1", result);

            assertThat(execution.isStepFailed("mcp:step_1")).isTrue();
            assertThat(execution.getStepStatus("mcp:step_1")).isEqualTo(NodeStatus.FAILED);
        }
    }

    // =========================================================================
    // Decision Evaluations
    // =========================================================================

    @Nested
    @DisplayName("Decision Evaluations")
    class DecisionEvaluationTests {

        @Test
        @DisplayName("Should store and retrieve decision evaluation")
        void shouldStoreAndRetrieveDecisionEvaluation() {
            DecisionEvaluationInfo info = new DecisionEvaluationInfo(
                    "core:check", "Check Status", "mcp:step_1",
                    "if", List.of(), Map.of());

            execution.storeDecisionEvaluation("mcp:step_1", info);

            assertThat(execution.getDecisionEvaluation("mcp:step_1")).isEqualTo(info);
        }

        @Test
        @DisplayName("Should return null for non-existent evaluation")
        void shouldReturnNullForNonExistent() {
            assertThat(execution.getDecisionEvaluation("missing")).isNull();
        }

        @Test
        @DisplayName("Should handle null sourceStepId gracefully")
        void shouldHandleNullSourceStepId() {
            execution.storeDecisionEvaluation(null, new DecisionEvaluationInfo(
                    "core:check", "Check", null, "if", List.of(), Map.of()));
            // Should not throw; null key means nothing stored
        }

        @Test
        @DisplayName("Should track processed decision items")
        void shouldTrackProcessedDecisionItems() {
            assertThat(execution.hasProcessedDecisionForItem("key1")).isFalse();

            execution.markDecisionProcessedForItem("key1");

            assertThat(execution.hasProcessedDecisionForItem("key1")).isTrue();
        }

        @Test
        @DisplayName("Should return defensive copy of all evaluations")
        void shouldReturnDefensiveCopyOfAllEvaluations() {
            DecisionEvaluationInfo info = new DecisionEvaluationInfo(
                    "core:check", "Check", "mcp:step_1", "if", List.of(), Map.of());
            execution.storeDecisionEvaluation("mcp:step_1", info);

            Map<String, DecisionEvaluationInfo> evals = execution.getDecisionEvaluations();
            evals.put("hacked", info);

            assertThat(execution.getDecisionEvaluations()).doesNotContainKey("hacked");
        }
    }

    // =========================================================================
    // Item-Level Step Tracking
    // =========================================================================

    @Nested
    @DisplayName("Item-Level Step Tracking")
    class ItemLevelTrackingTests {

        @Test
        @DisplayName("Should mark and check step completed for item")
        void shouldMarkAndCheckStepCompletedForItem() {
            execution.markStepCompletedForItem("mcp:step_1", 0);

            assertThat(execution.isStepCompletedForItem("mcp:step_1", 0)).isTrue();
            assertThat(execution.isStepCompletedForItem("mcp:step_1", 1)).isFalse();
        }

        @Test
        @DisplayName("Should mark and check step skipped for item")
        void shouldMarkAndCheckStepSkippedForItem() {
            execution.markStepSkippedForItem("mcp:step_1", 2);

            assertThat(execution.isStepSkippedForItem("mcp:step_1", 2)).isTrue();
            assertThat(execution.isStepSkippedForItem("mcp:step_1", 0)).isFalse();
        }

        @Test
        @DisplayName("Should handle null stepId gracefully for item tracking")
        void shouldHandleNullStepIdForItemTracking() {
            execution.markStepCompletedForItem(null, 0);
            assertThat(execution.isStepCompletedForItem(null, 0)).isFalse();

            execution.markStepSkippedForItem(null, 0);
            assertThat(execution.isStepSkippedForItem(null, 0)).isFalse();
        }

        @Test
        @DisplayName("Should handle negative itemIndex gracefully")
        void shouldHandleNegativeItemIndex() {
            execution.markStepCompletedForItem("mcp:step_1", -1);
            assertThat(execution.isStepCompletedForItem("mcp:step_1", -1)).isFalse();
        }

        @Test
        @DisplayName("Should store and retrieve item step outputs")
        void shouldStoreAndRetrieveItemStepOutputs() {
            Map<String, Object> output = Map.of("result", "success");
            execution.storeItemStepOutput("mcp:step_1", 0, output);

            Map<String, Object> retrieved = execution.getStoredItemStepOutput("mcp:step_1", 0);

            assertThat(retrieved).containsEntry("result", "success");
        }

        @Test
        @DisplayName("Should return null for missing item step output")
        void shouldReturnNullForMissingItemStepOutput() {
            assertThat(execution.getStoredItemStepOutput("mcp:step_1", 0)).isNull();
        }

        @Test
        @DisplayName("Should handle null output gracefully")
        void shouldHandleNullOutputGracefully() {
            execution.storeItemStepOutput("mcp:step_1", 0, null);

            Map<String, Object> retrieved = execution.getStoredItemStepOutput("mcp:step_1", 0);
            assertThat(retrieved).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("Should return all item outputs for step")
        void shouldReturnAllItemOutputsForStep() {
            execution.storeItemStepOutput("mcp:step_1", 0, Map.of("a", 1));
            execution.storeItemStepOutput("mcp:step_1", 1, Map.of("b", 2));

            Map<Integer, Map<String, Object>> all = execution.getAllItemOutputsForStep("mcp:step_1");

            assertThat(all).hasSize(2);
            assertThat(all.get(0)).containsEntry("a", 1);
            assertThat(all.get(1)).containsEntry("b", 2);
        }

        @Test
        @DisplayName("Should return empty map for step with no item outputs")
        void shouldReturnEmptyMapForNoItemOutputs() {
            assertThat(execution.getAllItemOutputsForStep("mcp:missing")).isEmpty();
        }

        @Test
        @DisplayName("Should return empty map for null stepId in getAllItemOutputsForStep")
        void shouldReturnEmptyMapForNullStepId() {
            assertThat(execution.getAllItemOutputsForStep(null)).isEmpty();
        }
    }

    // =========================================================================
    // Output Management
    // =========================================================================

    @Nested
    @DisplayName("Output Management")
    class OutputManagementTests {

        @Test
        @DisplayName("updateStepOutput should store sanitized output")
        void updateStepOutputShouldStoreSanitizedOutput() {
            execution.updateStepOutput("mcp:step_1", Map.of("key", "value"));

            Object output = execution.getStepOutput("mcp:step_1");
            assertThat(output).isNotNull();
        }

        @Test
        @DisplayName("updateStepOutput with null stepId should not throw")
        void updateStepOutputWithNullStepIdShouldNotThrow() {
            execution.updateStepOutput(null, Map.of("key", "value"));
            // Should not throw
        }

        @Test
        @DisplayName("getStepOutput should return null for unknown step")
        void getStepOutputShouldReturnNullForUnknown() {
            assertThat(execution.getStepOutput("mcp:unknown")).isNull();
        }

        @Test
        @DisplayName("getStepOutput with null should return null")
        void getStepOutputWithNullShouldReturnNull() {
            assertThat(execution.getStepOutput(null)).isNull();
        }
    }

    // =========================================================================
    // Level Management
    // =========================================================================

    @Nested
    @DisplayName("Level Management")
    class LevelManagementTests {

        @Test
        @DisplayName("Should set and get current level")
        void shouldSetAndGetCurrentLevel() {
            execution.setCurrentLevel(3);

            assertThat(execution.getCurrentLevel()).isEqualTo(3);
        }
    }

    // =========================================================================
    // Total Execution Time
    // =========================================================================

    @Nested
    @DisplayName("Total Execution Time")
    class TotalExecutionTimeTests {

        @Test
        @DisplayName("Should calculate elapsed time when still running")
        void shouldCalculateElapsedTimeWhenRunning() {
            long time = execution.getTotalExecutionTime();
            assertThat(time).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Should return fixed time when completed")
        void shouldReturnFixedTimeWhenCompleted() {
            execution.setStatus(RunStatus.COMPLETED);
            long time = execution.getTotalExecutionTime();
            assertThat(time).isGreaterThanOrEqualTo(0);
        }
    }

    // =========================================================================
    // Execution Statistics
    // =========================================================================

    @Nested
    @DisplayName("Execution Statistics")
    class ExecutionStatisticsTests {

        @Test
        @DisplayName("Should compute correct statistics")
        void shouldComputeCorrectStatistics() {
            // Add a step result to populate completed steps
            execution.setStepResult("mcp:step_1",
                    StepExecutionResult.success("mcp:step_1", Map.of("data", "v"), 100));

            ExecutionStatistics stats = execution.getStatistics();

            assertThat(stats.completedSteps()).isGreaterThanOrEqualTo(1);
            assertThat(stats.overallStatus()).isEqualTo(RunStatus.PENDING);
        }
    }

    // =========================================================================
    // toString
    // =========================================================================

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("Should include runId, status, and counts")
        void shouldIncludeRelevantInfo() {
            String str = execution.toString();

            assertThat(str).contains("run-1");
            assertThat(str).contains("PENDING");
            assertThat(str).contains("completed=0");
            assertThat(str).contains("failed=0");
        }
    }

    // =========================================================================
    // Consumer Tracking
    // =========================================================================

    @Nested
    @DisplayName("Consumer Tracking")
    class ConsumerTrackingTests {

        @Test
        @DisplayName("releaseStepOutputs should remove outputs and results")
        void releaseStepOutputsShouldRemoveOutputsAndResults() {
            execution.setStepResult("mcp:step_1",
                    StepExecutionResult.success("mcp:step_1", Map.of("data", "value"), 10));

            execution.releaseStepOutputs("mcp:step_1");

            assertThat(execution.getStepOutputs()).doesNotContainKey("mcp:step_1");
        }

        @Test
        @DisplayName("releaseStepOutputs with null should not throw")
        void releaseStepOutputsWithNullShouldNotThrow() {
            execution.releaseStepOutputs(null);
            // Should not throw
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private WorkflowPlan createLinearPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
                Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")
        ));
        data.put("mcps", List.of(
                Map.of("id", "s1", "label", "Step 1"),
                Map.of("id", "s2", "label", "Step 2")
        ));
        data.put("edges", List.of(
                Map.of("from", "trigger:start", "to", "mcp:step_1"),
                Map.of("from", "mcp:step_1", "to", "mcp:step_2")
        ));
        return WorkflowPlan.fromMap(data);
    }
}

package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StepExecutionResult")
class StepExecutionResultTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("success() should create COMPLETED result")
        void successShouldCreateCompleted() {
            StepExecutionResult result = StepExecutionResult.success("step1", Map.of("key", "value"), 150L);

            assertEquals("step1", result.stepId());
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals("Success", result.message());
            assertEquals(Map.of("key", "value"), result.output());
            assertEquals(150L, result.executionTime());
            assertNull(result.error());
        }

        @Test
        @DisplayName("failure() should create FAILED result with error")
        void failureShouldCreateFailed() {
            RuntimeException error = new RuntimeException("boom");
            StepExecutionResult result = StepExecutionResult.failure("step2", "Something went wrong", error, 200L);

            assertEquals("step2", result.stepId());
            assertEquals(NodeStatus.FAILED, result.status());
            assertEquals("Something went wrong", result.message());
            assertNull(result.output());
            assertEquals(200L, result.executionTime());
            assertSame(error, result.error());
        }

        @Test
        @DisplayName("failureWithOutput() should create FAILED result with output")
        void failureWithOutputShouldPreserveOutput() {
            Map<String, Object> output = Map.of("partial", "data");
            StepExecutionResult result = StepExecutionResult.failureWithOutput("step3", "Partial failure", output, 100L);

            assertEquals(NodeStatus.FAILED, result.status());
            assertEquals(output, result.output());
            assertNull(result.error());
        }

        @Test
        @DisplayName("skipped() should create SKIPPED result")
        void skippedShouldCreateSkipped() {
            StepExecutionResult result = StepExecutionResult.skipped("step4", "Branch not taken");

            assertEquals("step4", result.stepId());
            assertEquals(NodeStatus.SKIPPED, result.status());
            assertEquals("Branch not taken", result.message());
            assertNull(result.output());
            assertEquals(0, result.executionTime());
            assertNull(result.error());
        }
    }

    @Nested
    @DisplayName("Status check methods")
    class StatusCheckTests {

        @Test
        @DisplayName("isSuccess() should return true for COMPLETED")
        void isSuccessShouldReturnTrueForCompleted() {
            StepExecutionResult result = StepExecutionResult.success("s", Map.of(), 0);
            assertTrue(result.isSuccess());
            assertFalse(result.isFailure());
            assertFalse(result.isSkipped());
        }

        @Test
        @DisplayName("isFailure() should return true for FAILED")
        void isFailureShouldReturnTrueForFailed() {
            StepExecutionResult result = StepExecutionResult.failure("s", "err", null, 0);
            assertTrue(result.isFailure());
            assertFalse(result.isSuccess());
            assertFalse(result.isSkipped());
        }

        @Test
        @DisplayName("isSkipped() should return true for SKIPPED")
        void isSkippedShouldReturnTrueForSkipped() {
            StepExecutionResult result = StepExecutionResult.skipped("s", "reason");
            assertTrue(result.isSkipped());
            assertFalse(result.isSuccess());
            assertFalse(result.isFailure());
        }

        @Test
        @DisplayName("hasError() should return true when error is present")
        void hasErrorShouldReturnTrueWhenErrorPresent() {
            StepExecutionResult result = StepExecutionResult.failure("s", "err", new Exception(), 0);
            assertTrue(result.hasError());
        }

        @Test
        @DisplayName("hasError() should return false when no error")
        void hasErrorShouldReturnFalseWhenNoError() {
            StepExecutionResult result = StepExecutionResult.success("s", Map.of(), 0);
            assertFalse(result.hasError());
        }
    }

    @Nested
    @DisplayName("Mutation methods")
    class MutationTests {

        @Test
        @DisplayName("withOutputSnapshot() should replace output")
        void withOutputSnapshotShouldReplaceOutput() {
            StepExecutionResult original = StepExecutionResult.success("s", Map.of("a", "b"), 100);
            StepExecutionResult modified = original.withOutputSnapshot(Map.of("x", "y"));

            assertEquals(Map.of("x", "y"), modified.output());
            assertEquals("s", modified.stepId());
            assertEquals(NodeStatus.COMPLETED, modified.status());
        }

        @Test
        @DisplayName("withOutputSnapshot(null) should use empty map")
        void withOutputSnapshotNullShouldUseEmptyMap() {
            StepExecutionResult original = StepExecutionResult.success("s", Map.of("a", "b"), 100);
            StepExecutionResult modified = original.withOutputSnapshot(null);
            assertEquals(Map.of(), modified.output());
        }

        @Test
        @DisplayName("withoutOutput() should clear output")
        void withoutOutputShouldClearOutput() {
            StepExecutionResult original = StepExecutionResult.success("s", Map.of("key", "val"), 100);
            StepExecutionResult modified = original.withoutOutput();

            assertEquals(Map.of(), modified.output());
            assertEquals("s", modified.stepId());
        }

        @Test
        @DisplayName("withoutOutput() should return same instance when output is already empty")
        void withoutOutputShouldReturnSameWhenEmpty() {
            StepExecutionResult original = StepExecutionResult.skipped("s", "reason");
            StepExecutionResult modified = original.withoutOutput();
            assertSame(original, modified);
        }

        @Test
        @DisplayName("withStepId() should change step ID")
        void withStepIdShouldChangeStepId() {
            StepExecutionResult original = StepExecutionResult.success("old", Map.of(), 100);
            StepExecutionResult modified = original.withStepId("new");

            assertEquals("new", modified.stepId());
            assertEquals(original.status(), modified.status());
        }

        @Test
        @DisplayName("withStepId(null) should return same instance")
        void withStepIdNullShouldReturnSame() {
            StepExecutionResult original = StepExecutionResult.success("s", Map.of(), 100);
            StepExecutionResult modified = original.withStepId(null);
            assertSame(original, modified);
        }

        @Test
        @DisplayName("withStepId(same) should return same instance")
        void withStepIdSameShouldReturnSame() {
            StepExecutionResult original = StepExecutionResult.success("s", Map.of(), 100);
            StepExecutionResult modified = original.withStepId("s");
            assertSame(original, modified);
        }

        @Test
        @DisplayName("withStepId(blank) should return same instance")
        void withStepIdBlankShouldReturnSame() {
            StepExecutionResult original = StepExecutionResult.success("s", Map.of(), 100);
            StepExecutionResult modified = original.withStepId("   ");
            assertSame(original, modified);
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("Should include step info in toString")
        void shouldIncludeStepInfo() {
            StepExecutionResult result = StepExecutionResult.success("step1", Map.of("key", "val"), 100);
            String str = result.toString();
            assertTrue(str.contains("step1"));
            assertTrue(str.contains("COMPLETED"));
        }

        @Test
        @DisplayName("Should include error info in toString for failures")
        void shouldIncludeErrorInfo() {
            StepExecutionResult result = StepExecutionResult.failure("step2", "err", new RuntimeException("boom"), 50);
            String str = result.toString();
            assertTrue(str.contains("RuntimeException"));
            assertTrue(str.contains("boom"));
        }
    }
}

package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StepByStepExecutionResult record.
 */
@DisplayName("StepByStepExecutionResult")
class StepByStepExecutionResultTest {

    @Nested
    @DisplayName("success factory method")
    class SuccessFactory {

        @Test
        @DisplayName("should create success result with ready nodes")
        void shouldCreateSuccessWithReadyNodes() {
            ExecutionContext context = createContext();
            NodeExecutionResult nodeResult = NodeExecutionResult.success("node-1", Map.of("key", "value"));
            Set<String> readyNodes = Set.of("node-2", "node-3");

            StepByStepExecutionResult result = StepByStepExecutionResult.success(context, nodeResult, readyNodes);

            assertSame(context, result.context());
            assertSame(nodeResult, result.nodeResult());
            assertEquals(readyNodes, result.readyNodes());
            assertFalse(result.workflowComplete());
            assertTrue(result.isSuccess());
            assertFalse(result.isSkipped());
            assertFalse(result.isFailed());
        }

        @Test
        @DisplayName("should mark workflow complete when no ready nodes")
        void shouldMarkCompleteWhenNoReadyNodes() {
            ExecutionContext context = createContext();
            NodeExecutionResult nodeResult = NodeExecutionResult.success("node-1", Map.of());

            StepByStepExecutionResult result = StepByStepExecutionResult.success(context, nodeResult, Set.of());

            assertTrue(result.workflowComplete());
            assertTrue(result.readyNodes().isEmpty());
        }
    }

    @Nested
    @DisplayName("completed factory method")
    class CompletedFactory {

        @Test
        @DisplayName("should create completed result")
        void shouldCreateCompletedResult() {
            ExecutionContext context = createContext();
            NodeExecutionResult nodeResult = NodeExecutionResult.success("node-1", Map.of());

            StepByStepExecutionResult result = StepByStepExecutionResult.completed(context, nodeResult);

            assertTrue(result.workflowComplete());
            assertTrue(result.readyNodes().isEmpty());
        }
    }

    @Nested
    @DisplayName("skipped factory method")
    class SkippedFactory {

        @Test
        @DisplayName("should create skipped result with ready nodes")
        void shouldCreateSkippedResult() {
            ExecutionContext context = createContext();
            NodeExecutionResult skipResult = NodeExecutionResult.skipped("node-1", "Not needed");
            Set<String> readyNodes = Set.of("node-2");

            StepByStepExecutionResult result = StepByStepExecutionResult.skipped(context, skipResult, readyNodes);

            assertTrue(result.isSkipped());
            assertFalse(result.isSuccess());
            assertFalse(result.workflowComplete());
        }

        @Test
        @DisplayName("should mark complete if no ready nodes after skip")
        void shouldMarkCompleteIfNoReadyNodesAfterSkip() {
            ExecutionContext context = createContext();
            NodeExecutionResult skipResult = NodeExecutionResult.skipped("node-1", "Skipped");

            StepByStepExecutionResult result = StepByStepExecutionResult.skipped(context, skipResult, Set.of());

            assertTrue(result.workflowComplete());
        }
    }

    @Nested
    @DisplayName("waitingForTrigger factory method")
    class WaitingForTriggerFactory {

        @Test
        @DisplayName("should create waiting for trigger result")
        void shouldCreateWaitingResult() {
            ExecutionContext context = createContext();

            StepByStepExecutionResult result = StepByStepExecutionResult.waitingForTrigger(
                context, "trigger-node", "Waiting for webhook"
            );

            assertTrue(result.isWaitingForTrigger());
            assertFalse(result.workflowComplete());
            assertTrue(result.readyNodes().isEmpty());
        }
    }

    @Nested
    @DisplayName("status check methods")
    class StatusChecks {

        @Test
        @DisplayName("isFailed returns true for failure result")
        void isFailedCheck() {
            ExecutionContext context = createContext();
            NodeExecutionResult failureResult = NodeExecutionResult.failure("node-1", "error");

            StepByStepExecutionResult result = new StepByStepExecutionResult(context, failureResult, Set.of(), false);

            assertTrue(result.isFailed());
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("all status methods return false for null nodeResult")
        void allStatusMethodsReturnFalseForNull() {
            ExecutionContext context = createContext();
            StepByStepExecutionResult result = new StepByStepExecutionResult(context, null, Set.of(), false);

            assertFalse(result.isSuccess());
            assertFalse(result.isSkipped());
            assertFalse(result.isFailed());
            assertFalse(result.isWaitingForTrigger());
        }
    }

    @Nested
    @DisplayName("getNodeOutput")
    class GetNodeOutput {

        @Test
        @DisplayName("should return output from node result")
        void shouldReturnNodeOutput() {
            ExecutionContext context = createContext();
            Map<String, Object> output = Map.of("data", "value");
            NodeExecutionResult nodeResult = NodeExecutionResult.success("node-1", output);

            StepByStepExecutionResult result = new StepByStepExecutionResult(context, nodeResult, Set.of(), false);

            assertEquals(output, result.getNodeOutput());
        }

        @Test
        @DisplayName("should return empty map for null node result")
        void shouldReturnEmptyForNull() {
            ExecutionContext context = createContext();
            StepByStepExecutionResult result = new StepByStepExecutionResult(context, null, Set.of(), false);

            assertTrue(result.getNodeOutput().isEmpty());
        }
    }

    @Nested
    @DisplayName("getExecutionTime")
    class GetExecutionTime {

        @Test
        @DisplayName("should return duration from node result")
        void shouldReturnDuration() {
            ExecutionContext context = createContext();
            NodeExecutionResult nodeResult = NodeExecutionResult.success("node-1", Map.of(), 1500L);

            StepByStepExecutionResult result = new StepByStepExecutionResult(context, nodeResult, Set.of(), false);

            assertEquals(1500L, result.getExecutionTime());
        }

        @Test
        @DisplayName("should return 0 for null node result")
        void shouldReturnZeroForNull() {
            ExecutionContext context = createContext();
            StepByStepExecutionResult result = new StepByStepExecutionResult(context, null, Set.of(), false);

            assertEquals(0L, result.getExecutionTime());
        }
    }

    @Nested
    @DisplayName("getErrorMessage")
    class GetErrorMessage {

        @Test
        @DisplayName("should return error message from failed node result")
        void shouldReturnErrorMessage() {
            ExecutionContext context = createContext();
            NodeExecutionResult failureResult = NodeExecutionResult.failure("node-1", "Something broke");

            StepByStepExecutionResult result = new StepByStepExecutionResult(context, failureResult, Set.of(), false);

            assertEquals("Something broke", result.getErrorMessage());
        }

        @Test
        @DisplayName("should return null for success node result")
        void shouldReturnNullForSuccess() {
            ExecutionContext context = createContext();
            NodeExecutionResult successResult = NodeExecutionResult.success("node-1", Map.of());

            StepByStepExecutionResult result = new StepByStepExecutionResult(context, successResult, Set.of(), false);

            assertNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("should return null for null node result")
        void shouldReturnNullForNullResult() {
            ExecutionContext context = createContext();
            StepByStepExecutionResult result = new StepByStepExecutionResult(context, null, Set.of(), false);

            assertNull(result.getErrorMessage());
        }
    }

    private ExecutionContext createContext() {
        return ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), null);
    }
}

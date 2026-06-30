package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeExecutionResult record.
 */
@DisplayName("NodeExecutionResult")
class NodeExecutionResultTest {

    @Nested
    @DisplayName("success factory method")
    class SuccessFactory {

        @Test
        @DisplayName("should create success result without duration")
        void shouldCreateSuccessWithoutDuration() {
            Map<String, Object> output = Map.of("key", "value");
            NodeExecutionResult result = NodeExecutionResult.success("node-1", output);

            assertEquals("node-1", result.nodeId());
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(output, result.output());
            assertTrue(result.errorMessage().isEmpty());
            assertEquals(0L, result.durationMs());
            assertTrue(result.isSuccess());
            assertFalse(result.isFailure());
        }

        @Test
        @DisplayName("should create success result with duration")
        void shouldCreateSuccessWithDuration() {
            NodeExecutionResult result = NodeExecutionResult.success("node-1", Map.of(), 250L);

            assertEquals(250L, result.durationMs());
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("should handle null output (defaults to empty map)")
        void shouldHandleNullOutput() {
            NodeExecutionResult result = NodeExecutionResult.success("node-1", null);

            assertNotNull(result.output());
            assertTrue(result.output().isEmpty());
        }
    }

    @Nested
    @DisplayName("failure factory method")
    class FailureFactory {

        @Test
        @DisplayName("should create failure result with error message")
        void shouldCreateFailure() {
            NodeExecutionResult result = NodeExecutionResult.failure("node-1", "Connection timeout");

            assertEquals("node-1", result.nodeId());
            assertEquals(NodeStatus.FAILED, result.status());
            assertTrue(result.output().isEmpty());
            assertTrue(result.errorMessage().isPresent());
            assertEquals("Connection timeout", result.errorMessage().get());
            assertTrue(result.isFailure());
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("should create failure result with duration")
        void shouldCreateFailureWithDuration() {
            NodeExecutionResult result = NodeExecutionResult.failure("node-1", "error", 500L);

            assertEquals(500L, result.durationMs());
            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("should default null error to 'Unknown error'")
        void shouldDefaultNullError() {
            NodeExecutionResult result = NodeExecutionResult.failure("node-1", null);

            assertTrue(result.errorMessage().isPresent());
            assertEquals("Unknown error", result.errorMessage().get());
        }
    }

    @Nested
    @DisplayName("failureWithOutput factory method")
    class FailureWithOutput {

        @Test
        @DisplayName("should create failure with output preserved")
        void shouldPreserveOutput() {
            Map<String, Object> output = Map.of("partial", "data");
            NodeExecutionResult result = NodeExecutionResult.failureWithOutput("node-1", "error", output, 100L);

            assertTrue(result.isFailure());
            assertEquals(output, result.output());
            assertEquals("error", result.errorMessage().get());
            assertEquals(100L, result.durationMs());
        }

        @Test
        @DisplayName("should handle null output")
        void shouldHandleNullOutput() {
            NodeExecutionResult result = NodeExecutionResult.failureWithOutput("node-1", "err", null, 0);

            assertTrue(result.output().isEmpty());
        }
    }

    @Nested
    @DisplayName("skipped factory method")
    class SkippedFactory {

        @Test
        @DisplayName("should create skipped result")
        void shouldCreateSkipped() {
            NodeExecutionResult result = NodeExecutionResult.skipped("node-1", "Condition not met");

            assertEquals(NodeStatus.SKIPPED, result.status());
            assertTrue(result.isSkipped());
            assertFalse(result.isSuccess());
            assertFalse(result.isFailure());
            assertEquals("Condition not met", result.errorMessage().get());
            assertEquals("Condition not met", result.metadata().get("skip_reason"));
        }
    }

    @Nested
    @DisplayName("running factory method")
    class RunningFactory {

        @Test
        @DisplayName("should create running result")
        void shouldCreateRunning() {
            NodeExecutionResult result = NodeExecutionResult.running("node-1");

            assertEquals(NodeStatus.RUNNING, result.status());
            assertFalse(result.isSuccess());
            assertFalse(result.isFailure());
            assertFalse(result.isSkipped());
        }
    }

    @Nested
    @DisplayName("waitingForTrigger factory method")
    class WaitingForTriggerFactory {

        @Test
        @DisplayName("should create waiting for trigger result")
        void shouldCreateWaiting() {
            NodeExecutionResult result = NodeExecutionResult.waitingForTrigger("node-1", "Waiting for webhook");

            assertEquals(NodeStatus.WAITING_TRIGGER, result.status());
            assertTrue(result.isWaitingForTrigger());
            assertEquals("Waiting for webhook", result.metadata().get("waiting_reason"));
        }

        @Test
        @DisplayName("should handle null message")
        void shouldHandleNullMessage() {
            NodeExecutionResult result = NodeExecutionResult.waitingForTrigger("node-1", null);

            assertTrue(result.isWaitingForTrigger());
            assertEquals("Waiting for webhook", result.metadata().get("waiting_reason"));
        }
    }

    @Nested
    @DisplayName("awaitingSignal factory method")
    class AwaitingSignalFactory {

        @Test
        @DisplayName("should create awaiting signal result")
        void shouldCreateAwaiting() {
            Map<String, Object> meta = Map.of("timeout", 30);
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal("node-1", SignalType.WAIT_TIMER, meta);

            assertEquals(NodeStatus.AWAITING_SIGNAL, result.status());
            assertTrue(result.isAwaitingSignal());
            assertEquals("WAIT_TIMER", result.output().get("signal_type"));
            assertEquals("WAIT_TIMER", result.metadata().get("signal_type"));
            assertEquals(30, result.metadata().get("timeout"));
        }

        @Test
        @DisplayName("should handle null metadata")
        void shouldHandleNullMetadata() {
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal("node-1", SignalType.USER_APPROVAL, null);

            assertTrue(result.isAwaitingSignal());
            assertEquals("USER_APPROVAL", result.metadata().get("signal_type"));
        }
    }

    @Nested
    @DisplayName("collecting factory method")
    class CollectingFactory {

        @Test
        @DisplayName("should create collecting result")
        void shouldCreateCollecting() {
            Map<String, Object> partial = Map.of("received", 3, "expected", 5);
            NodeExecutionResult result = NodeExecutionResult.collecting("node-1", partial);

            assertEquals(NodeStatus.COLLECTING, result.status());
            assertTrue(result.isCollecting());
            assertEquals(3, result.output().get("received"));
        }

        @Test
        @DisplayName("should handle null partial output")
        void shouldHandleNullPartialOutput() {
            NodeExecutionResult result = NodeExecutionResult.collecting("node-1", null);

            assertTrue(result.isCollecting());
            assertTrue(result.output().isEmpty());
        }
    }

    @Nested
    @DisplayName("isTerminal / isPending predicates")
    class TerminalPredicate {

        @Test
        @DisplayName("COMPLETED is terminal, not pending")
        void completedIsTerminal() {
            NodeExecutionResult result = NodeExecutionResult.success("n", Map.of());
            assertTrue(result.isTerminal());
            assertFalse(result.isPending());
        }

        @Test
        @DisplayName("FAILED is terminal, not pending")
        void failedIsTerminal() {
            NodeExecutionResult result = NodeExecutionResult.failure("n", "boom");
            assertTrue(result.isTerminal());
            assertFalse(result.isPending());
        }

        @Test
        @DisplayName("SKIPPED is terminal, not pending")
        void skippedIsTerminal() {
            NodeExecutionResult result = NodeExecutionResult.skipped("n", "reason");
            assertTrue(result.isTerminal());
            assertFalse(result.isPending());
        }

        @Test
        @DisplayName("RUNNING is pending, not terminal")
        void runningIsPending() {
            NodeExecutionResult result = NodeExecutionResult.running("n");
            assertFalse(result.isTerminal());
            assertTrue(result.isPending());
        }

        @Test
        @DisplayName("AWAITING_SIGNAL is pending, not terminal (async agent yield scenario)")
        void awaitingSignalIsPending() {
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                "n", SignalType.AGENT_EXECUTION, Map.of());
            assertFalse(result.isTerminal());
            assertTrue(result.isPending());
            // Crucial invariant: pending yields satisfy neither isSuccess() NOR isFailure().
            // Call sites that branch on !isSuccess() as a failure sentinel must gate on isTerminal().
            assertFalse(result.isSuccess());
            assertFalse(result.isFailure());
        }

        @Test
        @DisplayName("COLLECTING is pending, not terminal")
        void collectingIsPending() {
            NodeExecutionResult result = NodeExecutionResult.collecting("n", Map.of());
            assertFalse(result.isTerminal());
            assertTrue(result.isPending());
        }

        @Test
        @DisplayName("WAITING_TRIGGER is pending, not terminal")
        void waitingTriggerIsPending() {
            NodeExecutionResult result = NodeExecutionResult.waitingForTrigger("n", "webhook");
            assertFalse(result.isTerminal());
            assertTrue(result.isPending());
        }
    }
}

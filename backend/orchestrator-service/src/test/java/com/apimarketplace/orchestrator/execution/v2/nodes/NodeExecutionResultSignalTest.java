package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NodeExecutionResult (Signal extensions)")
class NodeExecutionResultSignalTest {

    @Nested
    @DisplayName("awaitingSignal()")
    class AwaitingSignalTests {

        @Test
        @DisplayName("Should create result with AWAITING_SIGNAL status")
        void shouldCreateAwaitingSignalResult() {
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                "core:wait_1", SignalType.WAIT_TIMER,
                Map.of("duration_ms", 60000L));

            assertEquals(NodeStatus.AWAITING_SIGNAL, result.status());
            assertTrue(result.isAwaitingSignal());
            assertFalse(result.isSuccess());
            assertFalse(result.isFailure());
        }

        @Test
        @DisplayName("Should include signal_type in output and metadata")
        void shouldIncludeSignalTypeInOutput() {
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                "core:approval_1", SignalType.USER_APPROVAL,
                Map.of("required_approvals", 1));

            assertEquals("USER_APPROVAL", result.output().get("signal_type"));
            assertEquals("USER_APPROVAL", result.metadata().get("signal_type"));
        }

        @Test
        @DisplayName("Should preserve custom metadata")
        void shouldPreserveMetadata() {
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                "core:wait_1", SignalType.WAIT_TIMER,
                Map.of("duration_ms", 30000L, "expires_at", "2026-02-04T12:00:30Z"));

            assertEquals(30000L, result.metadata().get("duration_ms"));
            assertEquals("2026-02-04T12:00:30Z", result.metadata().get("expires_at"));
            assertEquals("WAIT_TIMER", result.metadata().get("signal_type"));
        }

        @Test
        @DisplayName("Should handle null metadata")
        void shouldHandleNullMetadata() {
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                "core:wait_1", SignalType.WAIT_TIMER, null);

            assertNotNull(result.metadata());
            assertEquals("WAIT_TIMER", result.metadata().get("signal_type"));
        }

        @Test
        @DisplayName("Should set nodeId correctly")
        void shouldSetNodeId() {
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                "core:webhook_wait", SignalType.WEBHOOK_WAIT,
                Map.of("token", "abc"));

            assertEquals("core:webhook_wait", result.nodeId());
        }

        @Test
        @DisplayName("Should have zero durationMs")
        void shouldHaveZeroDuration() {
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                "core:wait_1", SignalType.WAIT_TIMER, Map.of());

            assertEquals(0, result.durationMs());
        }

        @Test
        @DisplayName("Should have empty errorMessage")
        void shouldHaveEmptyError() {
            NodeExecutionResult result = NodeExecutionResult.awaitingSignal(
                "core:wait_1", SignalType.WAIT_TIMER, Map.of());

            assertTrue(result.errorMessage().isEmpty());
        }
    }

    @Nested
    @DisplayName("Status check methods")
    class StatusCheckTests {

        @Test
        @DisplayName("isAwaitingSignal() should only be true for AWAITING_SIGNAL status")
        void isAwaitingSignalExclusive() {
            NodeExecutionResult awaiting = NodeExecutionResult.awaitingSignal(
                "n1", SignalType.WAIT_TIMER, Map.of());
            NodeExecutionResult success = NodeExecutionResult.success("n2", Map.of());
            NodeExecutionResult failure = NodeExecutionResult.failure("n3", "err");
            NodeExecutionResult skipped = NodeExecutionResult.skipped("n4", "reason");

            assertTrue(awaiting.isAwaitingSignal());
            assertFalse(success.isAwaitingSignal());
            assertFalse(failure.isAwaitingSignal());
            assertFalse(skipped.isAwaitingSignal());
        }
    }
}

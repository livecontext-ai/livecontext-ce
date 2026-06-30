package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeStatus enum.
 * Tests the state transition graph defined by canTransitionTo().
 */
class NodeStatusTest {

    // ========================================================================
    // canTransitionTo() Tests
    // ========================================================================

    @Nested
    @DisplayName("canTransitionTo() - Valid Transitions")
    class ValidTransitionsTests {

        @Test
        @DisplayName("PENDING -> READY should be valid")
        void pendingToReady() {
            assertTrue(NodeStatus.PENDING.canTransitionTo(NodeStatus.READY));
        }

        @Test
        @DisplayName("PENDING -> SKIPPED should be valid")
        void pendingToSkipped() {
            assertTrue(NodeStatus.PENDING.canTransitionTo(NodeStatus.SKIPPED));
        }

        @Test
        @DisplayName("READY -> RUNNING should be valid")
        void readyToRunning() {
            assertTrue(NodeStatus.READY.canTransitionTo(NodeStatus.RUNNING));
        }

        @Test
        @DisplayName("READY -> SKIPPED should be valid")
        void readyToSkipped() {
            assertTrue(NodeStatus.READY.canTransitionTo(NodeStatus.SKIPPED));
        }

        @Test
        @DisplayName("READY -> COMPLETED should be valid (Decision/Fork auto-evaluation)")
        void readyToCompleted() {
            assertTrue(NodeStatus.READY.canTransitionTo(NodeStatus.COMPLETED));
        }

        @Test
        @DisplayName("RUNNING -> COMPLETED should be valid")
        void runningToCompleted() {
            assertTrue(NodeStatus.RUNNING.canTransitionTo(NodeStatus.COMPLETED));
        }

        @Test
        @DisplayName("RUNNING -> FAILED should be valid")
        void runningToFailed() {
            assertTrue(NodeStatus.RUNNING.canTransitionTo(NodeStatus.FAILED));
        }

        @Test
        @DisplayName("RUNNING -> AWAITING_SIGNAL should be valid")
        void runningToAwaitingSignal() {
            assertTrue(NodeStatus.RUNNING.canTransitionTo(NodeStatus.AWAITING_SIGNAL));
        }

        @Test
        @DisplayName("RUNNING -> COLLECTING should be valid")
        void runningToCollecting() {
            assertTrue(NodeStatus.RUNNING.canTransitionTo(NodeStatus.COLLECTING));
        }

        @Test
        @DisplayName("PENDING -> WAITING_TRIGGER should be valid")
        void pendingToWaitingTrigger() {
            assertTrue(NodeStatus.PENDING.canTransitionTo(NodeStatus.WAITING_TRIGGER));
        }

        @Test
        @DisplayName("WAITING_TRIGGER -> RUNNING should be valid")
        void waitingTriggerToRunning() {
            assertTrue(NodeStatus.WAITING_TRIGGER.canTransitionTo(NodeStatus.RUNNING));
        }

        @Test
        @DisplayName("AWAITING_SIGNAL -> COMPLETED should be valid")
        void awaitingSignalToCompleted() {
            assertTrue(NodeStatus.AWAITING_SIGNAL.canTransitionTo(NodeStatus.COMPLETED));
        }

        @Test
        @DisplayName("AWAITING_SIGNAL -> FAILED should be valid")
        void awaitingSignalToFailed() {
            assertTrue(NodeStatus.AWAITING_SIGNAL.canTransitionTo(NodeStatus.FAILED));
        }

        @Test
        @DisplayName("COLLECTING -> COMPLETED should be valid")
        void collectingToCompleted() {
            assertTrue(NodeStatus.COLLECTING.canTransitionTo(NodeStatus.COMPLETED));
        }

        @Test
        @DisplayName("COLLECTING -> FAILED should be valid")
        void collectingToFailed() {
            assertTrue(NodeStatus.COLLECTING.canTransitionTo(NodeStatus.FAILED));
        }
    }

    @Nested
    @DisplayName("canTransitionTo() - Invalid Transitions")
    class InvalidTransitionsTests {

        @Test
        @DisplayName("PENDING -> COMPLETED should be invalid")
        void pendingToCompleted() {
            assertFalse(NodeStatus.PENDING.canTransitionTo(NodeStatus.COMPLETED));
        }

        @Test
        @DisplayName("PENDING -> RUNNING should be invalid")
        void pendingToRunning() {
            assertFalse(NodeStatus.PENDING.canTransitionTo(NodeStatus.RUNNING));
        }

        @Test
        @DisplayName("PENDING -> FAILED should be invalid")
        void pendingToFailed() {
            assertFalse(NodeStatus.PENDING.canTransitionTo(NodeStatus.FAILED));
        }

        @Test
        @DisplayName("PENDING -> PENDING should be invalid (self-transition)")
        void pendingToPending() {
            assertFalse(NodeStatus.PENDING.canTransitionTo(NodeStatus.PENDING));
        }

        @Test
        @DisplayName("READY -> READY should be invalid (self-transition)")
        void readyToReady() {
            assertFalse(NodeStatus.READY.canTransitionTo(NodeStatus.READY));
        }

        @Test
        @DisplayName("READY -> PENDING should be invalid (backward transition)")
        void readyToPending() {
            assertFalse(NodeStatus.READY.canTransitionTo(NodeStatus.PENDING));
        }

        @Test
        @DisplayName("READY -> FAILED should be invalid")
        void readyToFailed() {
            assertFalse(NodeStatus.READY.canTransitionTo(NodeStatus.FAILED));
        }

        @Test
        @DisplayName("RUNNING -> PENDING should be invalid (backward transition)")
        void runningToPending() {
            assertFalse(NodeStatus.RUNNING.canTransitionTo(NodeStatus.PENDING));
        }

        @Test
        @DisplayName("RUNNING -> READY should be invalid (backward transition)")
        void runningToReady() {
            assertFalse(NodeStatus.RUNNING.canTransitionTo(NodeStatus.READY));
        }

        @Test
        @DisplayName("RUNNING -> RUNNING should be invalid (self-transition)")
        void runningToRunning() {
            assertFalse(NodeStatus.RUNNING.canTransitionTo(NodeStatus.RUNNING));
        }

        @Test
        @DisplayName("RUNNING -> SKIPPED should be invalid")
        void runningToSkipped() {
            assertFalse(NodeStatus.RUNNING.canTransitionTo(NodeStatus.SKIPPED));
        }

        @Test
        @DisplayName("WAITING_TRIGGER -> COMPLETED should be invalid")
        void waitingTriggerToCompleted() {
            assertFalse(NodeStatus.WAITING_TRIGGER.canTransitionTo(NodeStatus.COMPLETED));
        }

        @Test
        @DisplayName("COLLECTING -> RUNNING should be invalid")
        void collectingToRunning() {
            assertFalse(NodeStatus.COLLECTING.canTransitionTo(NodeStatus.RUNNING));
        }

        @Test
        @DisplayName("canTransitionTo(null) should return false")
        void transitionToNull() {
            assertFalse(NodeStatus.PENDING.canTransitionTo(null));
            assertFalse(NodeStatus.READY.canTransitionTo(null));
            assertFalse(NodeStatus.RUNNING.canTransitionTo(null));
            assertFalse(NodeStatus.COMPLETED.canTransitionTo(null));
            assertFalse(NodeStatus.SKIPPED.canTransitionTo(null));
            assertFalse(NodeStatus.FAILED.canTransitionTo(null));
        }
    }

    @Nested
    @DisplayName("canTransitionTo() - Terminal States")
    class TerminalStatesTests {

        @ParameterizedTest
        @EnumSource(NodeStatus.class)
        @DisplayName("COMPLETED cannot transition to any status")
        void completedCannotTransition(NodeStatus target) {
            assertFalse(NodeStatus.COMPLETED.canTransitionTo(target));
        }

        @ParameterizedTest
        @EnumSource(NodeStatus.class)
        @DisplayName("SKIPPED cannot transition to any status")
        void skippedCannotTransition(NodeStatus target) {
            assertFalse(NodeStatus.SKIPPED.canTransitionTo(target));
        }

        @ParameterizedTest
        @EnumSource(NodeStatus.class)
        @DisplayName("FAILED cannot transition to any status")
        void failedCannotTransition(NodeStatus target) {
            assertFalse(NodeStatus.FAILED.canTransitionTo(target));
        }
    }

    // ========================================================================
    // isTerminal() Tests
    // ========================================================================

    @Nested
    @DisplayName("isTerminal()")
    class IsTerminalTests {

        @Test
        @DisplayName("COMPLETED is terminal")
        void completedIsTerminal() {
            assertTrue(NodeStatus.COMPLETED.isTerminal());
        }

        @Test
        @DisplayName("SKIPPED is terminal")
        void skippedIsTerminal() {
            assertTrue(NodeStatus.SKIPPED.isTerminal());
        }

        @Test
        @DisplayName("FAILED is terminal")
        void failedIsTerminal() {
            assertTrue(NodeStatus.FAILED.isTerminal());
        }

        @Test
        @DisplayName("PENDING is not terminal")
        void pendingIsNotTerminal() {
            assertFalse(NodeStatus.PENDING.isTerminal());
        }

        @Test
        @DisplayName("READY is not terminal")
        void readyIsNotTerminal() {
            assertFalse(NodeStatus.READY.isTerminal());
        }

        @Test
        @DisplayName("RUNNING is not terminal")
        void runningIsNotTerminal() {
            assertFalse(NodeStatus.RUNNING.isTerminal());
        }
    }

    // ========================================================================
    // isResolved() Tests
    // ========================================================================

    @Nested
    @DisplayName("isResolved()")
    class IsResolvedTests {

        @Test
        @DisplayName("COMPLETED is resolved")
        void completedIsResolved() {
            assertTrue(NodeStatus.COMPLETED.isResolved());
        }

        @Test
        @DisplayName("SKIPPED is resolved (critical for merge nodes)")
        void skippedIsResolved() {
            assertTrue(NodeStatus.SKIPPED.isResolved());
        }

        @Test
        @DisplayName("FAILED is resolved")
        void failedIsResolved() {
            assertTrue(NodeStatus.FAILED.isResolved());
        }

        @Test
        @DisplayName("PENDING is not resolved")
        void pendingIsNotResolved() {
            assertFalse(NodeStatus.PENDING.isResolved());
        }

        @Test
        @DisplayName("READY is not resolved")
        void readyIsNotResolved() {
            assertFalse(NodeStatus.READY.isResolved());
        }

        @Test
        @DisplayName("RUNNING is not resolved")
        void runningIsNotResolved() {
            assertFalse(NodeStatus.RUNNING.isResolved());
        }
    }

    // ========================================================================
    // isActive() Tests
    // ========================================================================

    @Nested
    @DisplayName("isActive()")
    class IsActiveTests {

        @Test
        @DisplayName("RUNNING is active")
        void runningIsActive() {
            assertTrue(NodeStatus.RUNNING.isActive());
        }

        @Test
        @DisplayName("AWAITING_SIGNAL is active")
        void awaitingSignalIsActive() {
            assertTrue(NodeStatus.AWAITING_SIGNAL.isActive());
        }

        @Test
        @DisplayName("COLLECTING is active")
        void collectingIsActive() {
            assertTrue(NodeStatus.COLLECTING.isActive());
        }

        @Test
        @DisplayName("Non-active statuses are not active")
        void nonActiveNotActive() {
            assertFalse(NodeStatus.PENDING.isActive());
            assertFalse(NodeStatus.READY.isActive());
            assertFalse(NodeStatus.COMPLETED.isActive());
            assertFalse(NodeStatus.SKIPPED.isActive());
            assertFalse(NodeStatus.FAILED.isActive());
            assertFalse(NodeStatus.WAITING_TRIGGER.isActive());
        }
    }

    // ========================================================================
    // allowsProgress() Tests
    // ========================================================================

    @Nested
    @DisplayName("allowsProgress()")
    class AllowsProgressTests {

        @Test
        @DisplayName("COMPLETED allows progress")
        void completedAllowsProgress() {
            assertTrue(NodeStatus.COMPLETED.allowsProgress());
        }

        @Test
        @DisplayName("SKIPPED allows progress")
        void skippedAllowsProgress() {
            assertTrue(NodeStatus.SKIPPED.allowsProgress());
        }

        @Test
        @DisplayName("FAILED does not allow progress")
        void failedDoesNotAllowProgress() {
            assertFalse(NodeStatus.FAILED.allowsProgress());
        }

        @Test
        @DisplayName("Non-terminal statuses do not allow progress")
        void nonTerminalDoNotAllowProgress() {
            assertFalse(NodeStatus.PENDING.allowsProgress());
            assertFalse(NodeStatus.READY.allowsProgress());
            assertFalse(NodeStatus.RUNNING.allowsProgress());
        }
    }

    // ========================================================================
    // fromString() Tests
    // ========================================================================

    @Nested
    @DisplayName("fromString()")
    class FromStringTests {

        @Test
        @DisplayName("Should parse uppercase status strings")
        void shouldParseUppercase() {
            assertEquals(NodeStatus.PENDING, NodeStatus.fromString("PENDING"));
            assertEquals(NodeStatus.READY, NodeStatus.fromString("READY"));
            assertEquals(NodeStatus.RUNNING, NodeStatus.fromString("RUNNING"));
            assertEquals(NodeStatus.COMPLETED, NodeStatus.fromString("COMPLETED"));
            assertEquals(NodeStatus.SKIPPED, NodeStatus.fromString("SKIPPED"));
            assertEquals(NodeStatus.FAILED, NodeStatus.fromString("FAILED"));
        }

        @Test
        @DisplayName("Should parse lowercase status strings (case-insensitive)")
        void shouldParseLowercase() {
            assertEquals(NodeStatus.PENDING, NodeStatus.fromString("pending"));
            assertEquals(NodeStatus.COMPLETED, NodeStatus.fromString("completed"));
        }

        @Test
        @DisplayName("Should trim whitespace")
        void shouldTrimWhitespace() {
            assertEquals(NodeStatus.PENDING, NodeStatus.fromString("  PENDING  "));
        }

        @Test
        @DisplayName("Should throw on null")
        void shouldThrowOnNull() {
            assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromString(null));
        }

        @Test
        @DisplayName("Should throw on blank string")
        void shouldThrowOnBlank() {
            assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromString("   "));
        }

        @Test
        @DisplayName("Should throw on invalid value")
        void shouldThrowOnInvalid() {
            assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromString("INVALID"));
        }
    }

    // ========================================================================
    // fromStringOrDefault() Tests
    // ========================================================================

    @Nested
    @DisplayName("fromStringOrDefault()")
    class FromStringOrDefaultTests {

        @Test
        @DisplayName("Should parse valid string")
        void shouldParseValidString() {
            assertEquals(NodeStatus.COMPLETED, NodeStatus.fromStringOrDefault("COMPLETED", NodeStatus.PENDING));
        }

        @Test
        @DisplayName("Should return default on invalid string")
        void shouldReturnDefaultOnInvalid() {
            assertEquals(NodeStatus.PENDING, NodeStatus.fromStringOrDefault("INVALID", NodeStatus.PENDING));
        }

        @Test
        @DisplayName("Should return default on null")
        void shouldReturnDefaultOnNull() {
            assertEquals(NodeStatus.READY, NodeStatus.fromStringOrDefault(null, NodeStatus.READY));
        }
    }

    // ========================================================================
    // Transition Graph Completeness Tests
    // ========================================================================

    @Nested
    @DisplayName("Transition Graph Completeness")
    class TransitionGraphCompletenessTests {

        @Test
        @DisplayName("PENDING has exactly 3 valid transitions: READY, SKIPPED, WAITING_TRIGGER")
        void pendingTransitions() {
            int validCount = 0;
            for (NodeStatus target : NodeStatus.values()) {
                if (NodeStatus.PENDING.canTransitionTo(target)) {
                    validCount++;
                    assertTrue(target == NodeStatus.READY || target == NodeStatus.SKIPPED || target == NodeStatus.WAITING_TRIGGER,
                        "Unexpected valid transition from PENDING to " + target);
                }
            }
            assertEquals(3, validCount, "PENDING should have exactly 3 valid transitions");
        }

        @Test
        @DisplayName("READY has exactly 3 valid transitions: RUNNING, SKIPPED, COMPLETED")
        void readyTransitions() {
            int validCount = 0;
            for (NodeStatus target : NodeStatus.values()) {
                if (NodeStatus.READY.canTransitionTo(target)) {
                    validCount++;
                    assertTrue(target == NodeStatus.RUNNING || target == NodeStatus.SKIPPED || target == NodeStatus.COMPLETED,
                        "Unexpected valid transition from READY to " + target);
                }
            }
            assertEquals(3, validCount, "READY should have exactly 3 valid transitions");
        }

        @Test
        @DisplayName("RUNNING has exactly 4 valid transitions: COMPLETED, FAILED, AWAITING_SIGNAL, COLLECTING")
        void runningTransitions() {
            int validCount = 0;
            for (NodeStatus target : NodeStatus.values()) {
                if (NodeStatus.RUNNING.canTransitionTo(target)) {
                    validCount++;
                    assertTrue(target == NodeStatus.COMPLETED || target == NodeStatus.FAILED
                            || target == NodeStatus.AWAITING_SIGNAL || target == NodeStatus.COLLECTING,
                        "Unexpected valid transition from RUNNING to " + target);
                }
            }
            assertEquals(4, validCount, "RUNNING should have exactly 4 valid transitions");
        }

        @Test
        @DisplayName("Terminal states have 0 valid transitions")
        void terminalTransitions() {
            for (NodeStatus terminal : new NodeStatus[]{NodeStatus.COMPLETED, NodeStatus.SKIPPED, NodeStatus.FAILED}) {
                int validCount = 0;
                for (NodeStatus target : NodeStatus.values()) {
                    if (terminal.canTransitionTo(target)) {
                        validCount++;
                    }
                }
                assertEquals(0, validCount, terminal + " should have 0 valid transitions");
            }
        }
    }

    // ========================================================================
    // Workflow Lifecycle Scenario Tests
    // ========================================================================

    @Nested
    @DisplayName("Workflow Lifecycle Scenarios")
    class WorkflowLifecycleTests {

        @Test
        @DisplayName("Normal step lifecycle: PENDING -> READY -> RUNNING -> COMPLETED")
        void normalStepLifecycle() {
            NodeStatus current = NodeStatus.PENDING;

            assertTrue(current.canTransitionTo(NodeStatus.READY));
            current = NodeStatus.READY;

            assertTrue(current.canTransitionTo(NodeStatus.RUNNING));
            current = NodeStatus.RUNNING;

            assertTrue(current.canTransitionTo(NodeStatus.COMPLETED));
            current = NodeStatus.COMPLETED;

            assertTrue(current.isTerminal());
        }

        @Test
        @DisplayName("Failed step lifecycle: PENDING -> READY -> RUNNING -> FAILED")
        void failedStepLifecycle() {
            NodeStatus current = NodeStatus.PENDING;

            assertTrue(current.canTransitionTo(NodeStatus.READY));
            current = NodeStatus.READY;

            assertTrue(current.canTransitionTo(NodeStatus.RUNNING));
            current = NodeStatus.RUNNING;

            assertTrue(current.canTransitionTo(NodeStatus.FAILED));
            current = NodeStatus.FAILED;

            assertTrue(current.isTerminal());
        }

        @Test
        @DisplayName("Skipped from PENDING: PENDING -> SKIPPED")
        void skippedFromPending() {
            assertTrue(NodeStatus.PENDING.canTransitionTo(NodeStatus.SKIPPED));
            assertTrue(NodeStatus.SKIPPED.isTerminal());
        }

        @Test
        @DisplayName("Skipped from READY: READY -> SKIPPED")
        void skippedFromReady() {
            assertTrue(NodeStatus.READY.canTransitionTo(NodeStatus.SKIPPED));
            assertTrue(NodeStatus.SKIPPED.isTerminal());
        }

        @Test
        @DisplayName("Decision/Fork auto-evaluation: PENDING -> READY -> COMPLETED (skips RUNNING)")
        void decisionForkAutoEvaluation() {
            NodeStatus current = NodeStatus.PENDING;

            assertTrue(current.canTransitionTo(NodeStatus.READY));
            current = NodeStatus.READY;

            // Decision and Fork nodes auto-evaluate without RUNNING
            assertTrue(current.canTransitionTo(NodeStatus.COMPLETED));
            current = NodeStatus.COMPLETED;

            assertTrue(current.isTerminal());
        }

        @Test
        @DisplayName("Cannot skip directly from PENDING to COMPLETED")
        void cannotSkipToCompleted() {
            assertFalse(NodeStatus.PENDING.canTransitionTo(NodeStatus.COMPLETED),
                "PENDING -> COMPLETED should be invalid (must go through READY first)");
        }

        @Test
        @DisplayName("Cannot skip directly from PENDING to RUNNING")
        void cannotSkipToRunning() {
            assertFalse(NodeStatus.PENDING.canTransitionTo(NodeStatus.RUNNING),
                "PENDING -> RUNNING should be invalid (must go through READY first)");
        }
    }
}

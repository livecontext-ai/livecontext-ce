package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NodeStatus")
class NodeStatusTest {

    @Nested
    @DisplayName("toWireValue()")
    class ToWireValueTests {

        @Test
        @DisplayName("Should return correct lowercase wire values")
        void shouldReturnCorrectValues() {
            assertEquals("pending", NodeStatus.PENDING.toWireValue());
            assertEquals("ready", NodeStatus.READY.toWireValue());
            assertEquals("running", NodeStatus.RUNNING.toWireValue());
            assertEquals("completed", NodeStatus.COMPLETED.toWireValue());
            assertEquals("failed", NodeStatus.FAILED.toWireValue());
            assertEquals("skipped", NodeStatus.SKIPPED.toWireValue());
            assertEquals("awaiting_signal", NodeStatus.AWAITING_SIGNAL.toWireValue());
            assertEquals("waiting_trigger", NodeStatus.WAITING_TRIGGER.toWireValue());
            assertEquals("collecting", NodeStatus.COLLECTING.toWireValue());
        }
    }

    @Nested
    @DisplayName("isTerminal()")
    class IsTerminalTests {

        @ParameterizedTest
        @EnumSource(value = NodeStatus.class, names = {"COMPLETED", "FAILED", "SKIPPED"})
        @DisplayName("Terminal statuses should return true")
        void terminalShouldReturnTrue(NodeStatus status) {
            assertTrue(status.isTerminal());
        }

        @ParameterizedTest
        @EnumSource(value = NodeStatus.class, names = {"PENDING", "READY", "RUNNING", "AWAITING_SIGNAL", "WAITING_TRIGGER", "COLLECTING"})
        @DisplayName("Non-terminal statuses should return false")
        void nonTerminalShouldReturnFalse(NodeStatus status) {
            assertFalse(status.isTerminal());
        }
    }

    @Nested
    @DisplayName("isActive()")
    class IsActiveTests {

        @ParameterizedTest
        @EnumSource(value = NodeStatus.class, names = {"RUNNING", "AWAITING_SIGNAL", "COLLECTING"})
        @DisplayName("Active statuses should return true")
        void activeShouldReturnTrue(NodeStatus status) {
            assertTrue(status.isActive());
        }

        @ParameterizedTest
        @EnumSource(value = NodeStatus.class, names = {"PENDING", "READY", "COMPLETED", "SKIPPED", "FAILED", "WAITING_TRIGGER"})
        @DisplayName("Inactive statuses should return false")
        void inactiveShouldReturnFalse(NodeStatus status) {
            assertFalse(status.isActive());
        }
    }

    @Nested
    @DisplayName("shouldPropagateSkip()")
    class ShouldPropagateSkipTests {

        @Test
        @DisplayName("FAILED should propagate skip")
        void failedShouldPropagate() {
            assertTrue(NodeStatus.FAILED.shouldPropagateSkip());
        }

        @Test
        @DisplayName("SKIPPED should propagate skip")
        void skippedShouldPropagate() {
            assertTrue(NodeStatus.SKIPPED.shouldPropagateSkip());
        }

        @Test
        @DisplayName("COMPLETED should not propagate skip")
        void completedShouldNotPropagate() {
            assertFalse(NodeStatus.COMPLETED.shouldPropagateSkip());
        }
    }

    @Nested
    @DisplayName("isSuccessful()")
    class IsSuccessfulTests {

        @Test
        @DisplayName("COMPLETED should be successful")
        void completedShouldBeSuccessful() {
            assertTrue(NodeStatus.COMPLETED.isSuccessful());
        }

        @Test
        @DisplayName("FAILED should not be successful")
        void failedShouldNotBeSuccessful() {
            assertFalse(NodeStatus.FAILED.isSuccessful());
        }
    }

    @Nested
    @DisplayName("fromString()")
    class FromStringTests {

        @Test
        @DisplayName("Should parse standard values")
        void shouldParseStandardValues() {
            assertEquals(NodeStatus.PENDING, NodeStatus.fromString("PENDING"));
            assertEquals(NodeStatus.READY, NodeStatus.fromString("READY"));
            assertEquals(NodeStatus.RUNNING, NodeStatus.fromString("RUNNING"));
            assertEquals(NodeStatus.COMPLETED, NodeStatus.fromString("COMPLETED"));
            assertEquals(NodeStatus.FAILED, NodeStatus.fromString("FAILED"));
            assertEquals(NodeStatus.SKIPPED, NodeStatus.fromString("SKIPPED"));
            assertEquals(NodeStatus.AWAITING_SIGNAL, NodeStatus.fromString("AWAITING_SIGNAL"));
            assertEquals(NodeStatus.WAITING_TRIGGER, NodeStatus.fromString("WAITING_TRIGGER"));
            assertEquals(NodeStatus.COLLECTING, NodeStatus.fromString("COLLECTING"));
        }

        @Test
        @DisplayName("Should parse aliases")
        void shouldParseAliases() {
            assertEquals(NodeStatus.COMPLETED, NodeStatus.fromString("SUCCESS"));
            assertEquals(NodeStatus.FAILED, NodeStatus.fromString("FAILURE"));
            assertEquals(NodeStatus.FAILED, NodeStatus.fromString("ERROR"));
        }

        @Test
        @DisplayName("Should be case-insensitive")
        void shouldBeCaseInsensitive() {
            assertEquals(NodeStatus.COMPLETED, NodeStatus.fromString("completed"));
            assertEquals(NodeStatus.FAILED, NodeStatus.fromString("failed"));
            assertEquals(NodeStatus.COMPLETED, NodeStatus.fromString("success"));
        }

        @Test
        @DisplayName("Should throw on null")
        void shouldThrowOnNull() {
            assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromString(null));
        }

        @Test
        @DisplayName("Should throw on blank")
        void shouldThrowOnBlank() {
            assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromString("   "));
        }
    }

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
}

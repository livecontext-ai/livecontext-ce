package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EdgeStatus")
class EdgeStatusTest {

    @Nested
    @DisplayName("getValue()")
    class GetValueTests {

        @Test
        @DisplayName("Should return correct string values")
        void shouldReturnCorrectValues() {
            assertEquals("pending", EdgeStatus.PENDING.getValue());
            assertEquals("running", EdgeStatus.RUNNING.getValue());
            assertEquals("completed", EdgeStatus.COMPLETED.getValue());
            assertEquals("skipped", EdgeStatus.SKIPPED.getValue());
        }
    }

    @Nested
    @DisplayName("isTerminal()")
    class IsTerminalTests {

        @Test
        @DisplayName("COMPLETED should be terminal")
        void completedShouldBeTerminal() {
            assertTrue(EdgeStatus.COMPLETED.isTerminal());
        }

        @Test
        @DisplayName("SKIPPED should be terminal")
        void skippedShouldBeTerminal() {
            assertTrue(EdgeStatus.SKIPPED.isTerminal());
        }

        @Test
        @DisplayName("PENDING should not be terminal")
        void pendingShouldNotBeTerminal() {
            assertFalse(EdgeStatus.PENDING.isTerminal());
        }

        @Test
        @DisplayName("RUNNING should not be terminal")
        void runningShouldNotBeTerminal() {
            assertFalse(EdgeStatus.RUNNING.isTerminal());
        }
    }

    @Nested
    @DisplayName("fromValue()")
    class FromValueTests {

        @Test
        @DisplayName("Should parse standard values")
        void shouldParseStandardValues() {
            assertEquals(EdgeStatus.PENDING, EdgeStatus.fromValue("pending"));
            assertEquals(EdgeStatus.RUNNING, EdgeStatus.fromValue("running"));
            assertEquals(EdgeStatus.COMPLETED, EdgeStatus.fromValue("completed"));
            assertEquals(EdgeStatus.SKIPPED, EdgeStatus.fromValue("skipped"));
        }

        @Test
        @DisplayName("Should parse aliases")
        void shouldParseAliases() {
            assertEquals(EdgeStatus.RUNNING, EdgeStatus.fromValue("traversing"));
            assertEquals(EdgeStatus.COMPLETED, EdgeStatus.fromValue("success"));
            assertEquals(EdgeStatus.SKIPPED, EdgeStatus.fromValue("failed")); // failed edges become skipped
        }

        @Test
        @DisplayName("Should return PENDING for null")
        void shouldReturnPendingForNull() {
            assertEquals(EdgeStatus.PENDING, EdgeStatus.fromValue(null));
        }

        @Test
        @DisplayName("Should return PENDING for blank")
        void shouldReturnPendingForBlank() {
            assertEquals(EdgeStatus.PENDING, EdgeStatus.fromValue("  "));
        }

        @Test
        @DisplayName("Should return PENDING for unknown")
        void shouldReturnPendingForUnknown() {
            assertEquals(EdgeStatus.PENDING, EdgeStatus.fromValue("unknown"));
        }
    }

    @Nested
    @DisplayName("fromNodeStatus()")
    class FromNodeStatusTests {

        @Test
        @DisplayName("Should map PENDING to PENDING")
        void shouldMapPending() {
            assertEquals(EdgeStatus.PENDING, EdgeStatus.fromNodeStatus(NodeStatus.PENDING));
        }

        @Test
        @DisplayName("Should map RUNNING to RUNNING")
        void shouldMapRunning() {
            assertEquals(EdgeStatus.RUNNING, EdgeStatus.fromNodeStatus(NodeStatus.RUNNING));
        }

        @Test
        @DisplayName("Should map COMPLETED to COMPLETED")
        void shouldMapCompleted() {
            assertEquals(EdgeStatus.COMPLETED, EdgeStatus.fromNodeStatus(NodeStatus.COMPLETED));
        }

        @Test
        @DisplayName("Should map FAILED to SKIPPED")
        void shouldMapFailedToSkipped() {
            assertEquals(EdgeStatus.SKIPPED, EdgeStatus.fromNodeStatus(NodeStatus.FAILED));
        }

        @Test
        @DisplayName("Should map SKIPPED to SKIPPED")
        void shouldMapSkippedToSkipped() {
            assertEquals(EdgeStatus.SKIPPED, EdgeStatus.fromNodeStatus(NodeStatus.SKIPPED));
        }

        @Test
        @DisplayName("Should return PENDING for null")
        void shouldReturnPendingForNull() {
            assertEquals(EdgeStatus.PENDING, EdgeStatus.fromNodeStatus(null));
        }
    }
}

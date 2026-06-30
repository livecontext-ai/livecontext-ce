package com.apimarketplace.orchestrator.execution.v2.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeState.
 * Tests the immutable node state record.
 */
class NodeStateTest {

    // ========================================================================
    // Record Constructor Tests
    // ========================================================================

    @Nested
    @DisplayName("Record Constructor")
    class RecordConstructorTests {

        @Test
        @DisplayName("Should create NodeState with all fields")
        void shouldCreateNodeStateWithAllFields() {
            Instant startTime = Instant.now().minusSeconds(5);
            Instant endTime = Instant.now();
            Map<String, Object> output = Map.of("result", "value");

            NodeState state = new NodeState(
                    "mcp:step1",
                    NodeStatus.COMPLETED,
                    startTime,
                    endTime,
                    output,
                    Optional.empty()
            );

            assertEquals("mcp:step1", state.nodeId());
            assertEquals(NodeStatus.COMPLETED, state.status());
            assertEquals(startTime, state.startTime());
            assertEquals(endTime, state.endTime());
            assertEquals(output, state.output());
            assertTrue(state.errorMessage().isEmpty());
        }

        @Test
        @DisplayName("Should create NodeState with error message")
        void shouldCreateNodeStateWithErrorMessage() {
            NodeState state = new NodeState(
                    "mcp:step1",
                    NodeStatus.FAILED,
                    Instant.now(),
                    Instant.now(),
                    Map.of(),
                    Optional.of("Something went wrong")
            );

            assertTrue(state.errorMessage().isPresent());
            assertEquals("Something went wrong", state.errorMessage().get());
        }

        @Test
        @DisplayName("Should handle null times")
        void shouldHandleNullTimes() {
            NodeState state = new NodeState(
                    "mcp:step1",
                    NodeStatus.PENDING,
                    null,
                    null,
                    Map.of(),
                    Optional.empty()
            );

            assertNull(state.startTime());
            assertNull(state.endTime());
        }
    }

    // ========================================================================
    // from() Factory Method Tests
    // ========================================================================

    @Nested
    @DisplayName("from()")
    class FromTests {

        @Test
        @DisplayName("Should create NodeState from success result")
        void shouldCreateNodeStateFromSuccessResult() {
            Map<String, Object> output = Map.of("data", "result");
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", output);

            NodeState state = NodeState.from(result);

            assertEquals("mcp:step1", state.nodeId());
            assertEquals(NodeStatus.COMPLETED, state.status());
            assertNotNull(state.startTime());
            assertNotNull(state.endTime());
            assertEquals(output, state.output());
            assertTrue(state.errorMessage().isEmpty());
        }

        @Test
        @DisplayName("Should create NodeState from failure result")
        void shouldCreateNodeStateFromFailureResult() {
            NodeExecutionResult result = NodeExecutionResult.failure("mcp:step1", "Error occurred");

            NodeState state = NodeState.from(result);

            assertEquals("mcp:step1", state.nodeId());
            assertEquals(NodeStatus.FAILED, state.status());
            assertTrue(state.errorMessage().isPresent());
            assertEquals("Error occurred", state.errorMessage().get());
        }

        @Test
        @DisplayName("Should create NodeState from skipped result")
        void shouldCreateNodeStateFromSkippedResult() {
            NodeExecutionResult result = NodeExecutionResult.skipped("mcp:step1", "Branch not taken");

            NodeState state = NodeState.from(result);

            assertEquals("mcp:step1", state.nodeId());
            assertEquals(NodeStatus.SKIPPED, state.status());
        }

        @Test
        @DisplayName("Should set timestamps on creation")
        void shouldSetTimestampsOnCreation() {
            Instant before = Instant.now();
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of());

            NodeState state = NodeState.from(result);

            Instant after = Instant.now();

            assertNotNull(state.startTime());
            assertNotNull(state.endTime());
            assertTrue(state.startTime().compareTo(before) >= 0);
            assertTrue(state.endTime().compareTo(after) <= 0);
        }
    }

    // ========================================================================
    // running() Factory Method Tests
    // ========================================================================

    @Nested
    @DisplayName("running()")
    class RunningTests {

        @Test
        @DisplayName("Should create running NodeState")
        void shouldCreateRunningNodeState() {
            NodeState state = NodeState.running("mcp:step1");

            assertEquals("mcp:step1", state.nodeId());
            assertEquals(NodeStatus.RUNNING, state.status());
            assertNotNull(state.startTime());
            assertNull(state.endTime());
            assertTrue(state.output().isEmpty());
            assertTrue(state.errorMessage().isEmpty());
        }

        @Test
        @DisplayName("Should set start time for running state")
        void shouldSetStartTimeForRunningState() {
            Instant before = Instant.now();

            NodeState state = NodeState.running("mcp:step1");

            Instant after = Instant.now();

            assertNotNull(state.startTime());
            assertTrue(state.startTime().compareTo(before) >= 0);
            assertTrue(state.startTime().compareTo(after) <= 0);
        }
    }

    // ========================================================================
    // pending() Factory Method Tests
    // ========================================================================

    @Nested
    @DisplayName("pending()")
    class PendingTests {

        @Test
        @DisplayName("Should create pending NodeState")
        void shouldCreatePendingNodeState() {
            NodeState state = NodeState.pending("mcp:step1");

            assertEquals("mcp:step1", state.nodeId());
            assertEquals(NodeStatus.PENDING, state.status());
            assertNull(state.startTime());
            assertNull(state.endTime());
            assertTrue(state.output().isEmpty());
            assertTrue(state.errorMessage().isEmpty());
        }
    }

    // ========================================================================
    // Immutability Tests
    // ========================================================================

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("Should be immutable - output map")
        void shouldBeImmutableOutputMap() {
            Map<String, Object> output = Map.of("key", "value");
            NodeState state = new NodeState(
                    "mcp:step1",
                    NodeStatus.COMPLETED,
                    Instant.now(),
                    Instant.now(),
                    output,
                    Optional.empty()
            );

            // Output should be the same reference (records don't copy)
            // But if we use immutable Map.of(), modifications will fail
            assertThrows(UnsupportedOperationException.class, () ->
                    state.output().put("new", "value")
            );
        }
    }

    // ========================================================================
    // Equality Tests
    // ========================================================================

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            Instant time = Instant.now();
            Map<String, Object> output = Map.of("key", "value");

            NodeState state1 = new NodeState(
                    "mcp:step1",
                    NodeStatus.COMPLETED,
                    time,
                    time,
                    output,
                    Optional.empty()
            );

            NodeState state2 = new NodeState(
                    "mcp:step1",
                    NodeStatus.COMPLETED,
                    time,
                    time,
                    output,
                    Optional.empty()
            );

            assertEquals(state1, state2);
            assertEquals(state1.hashCode(), state2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal for different node IDs")
        void shouldNotBeEqualForDifferentNodeIds() {
            Instant time = Instant.now();

            NodeState state1 = new NodeState(
                    "mcp:step1",
                    NodeStatus.COMPLETED,
                    time,
                    time,
                    Map.of(),
                    Optional.empty()
            );

            NodeState state2 = new NodeState(
                    "mcp:step2",
                    NodeStatus.COMPLETED,
                    time,
                    time,
                    Map.of(),
                    Optional.empty()
            );

            assertNotEquals(state1, state2);
        }

        @Test
        @DisplayName("Should not be equal for different statuses")
        void shouldNotBeEqualForDifferentStatuses() {
            Instant time = Instant.now();

            NodeState state1 = new NodeState(
                    "mcp:step1",
                    NodeStatus.COMPLETED,
                    time,
                    time,
                    Map.of(),
                    Optional.empty()
            );

            NodeState state2 = new NodeState(
                    "mcp:step1",
                    NodeStatus.FAILED,
                    time,
                    time,
                    Map.of(),
                    Optional.empty()
            );

            assertNotEquals(state1, state2);
        }
    }

    // ========================================================================
    // All Status Values Tests
    // ========================================================================

    @Nested
    @DisplayName("All Status Values")
    class AllStatusValuesTests {

        @Test
        @DisplayName("Should handle all NodeStatus values")
        void shouldHandleAllNodeStatusValues() {
            for (NodeStatus status : NodeStatus.values()) {
                NodeState state = new NodeState(
                        "mcp:step1",
                        status,
                        Instant.now(),
                        Instant.now(),
                        Map.of(),
                        Optional.empty()
                );

                assertEquals(status, state.status());
            }
        }
    }

    // ========================================================================
    // toString Tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("Should have meaningful toString")
        void shouldHaveMeaningfulToString() {
            NodeState state = new NodeState(
                    "mcp:step1",
                    NodeStatus.COMPLETED,
                    Instant.now(),
                    Instant.now(),
                    Map.of("key", "value"),
                    Optional.of("error")
            );

            String str = state.toString();

            assertTrue(str.contains("mcp:step1"));
            assertTrue(str.contains("COMPLETED"));
        }
    }
}

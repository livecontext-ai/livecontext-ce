package com.apimarketplace.orchestrator.execution.v2.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExecutionState record.
 */
@DisplayName("ExecutionState")
class ExecutionStateTest {

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("should create empty state")
        void shouldCreateEmptyState() {
            ExecutionState state = ExecutionState.create();

            assertNotNull(state.nodeStates());
            assertTrue(state.nodeStates().isEmpty());
            assertNotNull(state.globalData());
            assertTrue(state.globalData().isEmpty());
        }
    }

    @Nested
    @DisplayName("getNodeStatus")
    class GetNodeStatus {

        @Test
        @DisplayName("should return PENDING for unknown node")
        void shouldReturnPendingForUnknown() {
            ExecutionState state = ExecutionState.create();
            assertEquals(NodeStatus.PENDING, state.getNodeStatus("nonexistent"));
        }

        @Test
        @DisplayName("should return actual status for known node")
        void shouldReturnActualStatus() {
            ExecutionState state = ExecutionState.create();
            NodeExecutionResult result = NodeExecutionResult.success("node-1", Map.of());
            state = state.recordResult("node-1", result);

            assertEquals(NodeStatus.COMPLETED, state.getNodeStatus("node-1"));
        }
    }

    @Nested
    @DisplayName("getNodeState")
    class GetNodeState {

        @Test
        @DisplayName("should return empty for unknown node")
        void shouldReturnEmptyForUnknown() {
            ExecutionState state = ExecutionState.create();
            assertTrue(state.getNodeState("nonexistent").isEmpty());
        }

        @Test
        @DisplayName("should return state for known node")
        void shouldReturnStateForKnown() {
            ExecutionState state = ExecutionState.create();
            NodeExecutionResult result = NodeExecutionResult.success("node-1", Map.of());
            state = state.recordResult("node-1", result);

            Optional<NodeState> nodeState = state.getNodeState("node-1");
            assertTrue(nodeState.isPresent());
            assertEquals(NodeStatus.COMPLETED, nodeState.get().status());
        }
    }

    @Nested
    @DisplayName("isCompleted")
    class IsCompleted {

        @Test
        @DisplayName("should return false for PENDING node")
        void shouldReturnFalseForPending() {
            ExecutionState state = ExecutionState.create();
            assertFalse(state.isCompleted("node-1"));
        }

        @Test
        @DisplayName("should return true for SUCCESS node")
        void shouldReturnTrueForSuccess() {
            ExecutionState state = ExecutionState.create()
                .recordResult("node-1", NodeExecutionResult.success("node-1", Map.of()));
            assertTrue(state.isCompleted("node-1"));
        }

        @Test
        @DisplayName("should return true for FAILURE node")
        void shouldReturnTrueForFailure() {
            ExecutionState state = ExecutionState.create()
                .recordResult("node-1", NodeExecutionResult.failure("node-1", "error"));
            assertTrue(state.isCompleted("node-1"));
        }

        @Test
        @DisplayName("should return true for SKIPPED node")
        void shouldReturnTrueForSkipped() {
            ExecutionState state = ExecutionState.create()
                .recordResult("node-1", NodeExecutionResult.skipped("node-1", "skipped"));
            assertTrue(state.isCompleted("node-1"));
        }
    }

    @Nested
    @DisplayName("isSuccess")
    class IsSuccess {

        @Test
        @DisplayName("should return true only for SUCCESS status")
        void shouldReturnTrueOnlyForSuccess() {
            ExecutionState state = ExecutionState.create()
                .recordResult("node-1", NodeExecutionResult.success("node-1", Map.of()))
                .recordResult("node-2", NodeExecutionResult.failure("node-2", "err"));

            assertTrue(state.isSuccess("node-1"));
            assertFalse(state.isSuccess("node-2"));
            assertFalse(state.isSuccess("nonexistent"));
        }
    }

    @Nested
    @DisplayName("isStarted")
    class IsStarted {

        @Test
        @DisplayName("should return false for unstarted node")
        void shouldReturnFalseForUnstarted() {
            ExecutionState state = ExecutionState.create();
            assertFalse(state.isStarted("node-1"));
        }

        @Test
        @DisplayName("should return true for running node")
        void shouldReturnTrueForRunning() {
            ExecutionState state = ExecutionState.create().recordStart("node-1");
            assertTrue(state.isStarted("node-1"));
        }

        @Test
        @DisplayName("should return true for completed node")
        void shouldReturnTrueForCompleted() {
            ExecutionState state = ExecutionState.create()
                .recordResult("node-1", NodeExecutionResult.success("node-1", Map.of()));
            assertTrue(state.isStarted("node-1"));
        }
    }

    @Nested
    @DisplayName("recordResult")
    class RecordResult {

        @Test
        @DisplayName("should return new state with recorded result")
        void shouldReturnNewState() {
            ExecutionState original = ExecutionState.create();
            NodeExecutionResult result = NodeExecutionResult.success("node-1", Map.of());

            ExecutionState updated = original.recordResult("node-1", result);

            assertNotSame(original, updated);
            assertFalse(original.isCompleted("node-1")); // Original unchanged
            assertTrue(updated.isCompleted("node-1"));
        }
    }

    @Nested
    @DisplayName("recordStart")
    class RecordStart {

        @Test
        @DisplayName("should return new state with running node")
        void shouldReturnNewStateWithRunning() {
            ExecutionState state = ExecutionState.create().recordStart("node-1");

            assertTrue(state.isStarted("node-1"));
            assertFalse(state.isCompleted("node-1"));
        }
    }

    @Nested
    @DisplayName("withGlobalData / getGlobalData")
    class GlobalData {

        @Test
        @DisplayName("should store and retrieve global data")
        void shouldStoreAndRetrieve() {
            ExecutionState state = ExecutionState.create()
                .withGlobalData("key1", "value1");

            Optional<Object> result = state.getGlobalData("key1");
            assertTrue(result.isPresent());
            assertEquals("value1", result.get());
        }

        @Test
        @DisplayName("should return empty for missing key")
        void shouldReturnEmptyForMissing() {
            ExecutionState state = ExecutionState.create();
            assertTrue(state.getGlobalData("missing").isEmpty());
        }

        @Test
        @DisplayName("getGlobalDataKeys should return all keys")
        void shouldReturnAllKeys() {
            ExecutionState state = ExecutionState.create()
                .withGlobalData("k1", "v1")
                .withGlobalData("k2", "v2");

            assertTrue(state.getGlobalDataKeys().contains("k1"));
            assertTrue(state.getGlobalDataKeys().contains("k2"));
        }
    }

    @Nested
    @DisplayName("merge")
    class Merge {

        @Test
        @DisplayName("should return this for null other")
        void shouldReturnThisForNull() {
            ExecutionState state = ExecutionState.create();
            assertSame(state, state.merge(null));
        }

        @Test
        @DisplayName("should merge node states from both branches")
        void shouldMergeNodeStates() {
            ExecutionState state1 = ExecutionState.create()
                .recordResult("node-1", NodeExecutionResult.success("node-1", Map.of()));
            ExecutionState state2 = ExecutionState.create()
                .recordResult("node-2", NodeExecutionResult.success("node-2", Map.of()));

            ExecutionState merged = state1.merge(state2);

            assertTrue(merged.isCompleted("node-1"));
            assertTrue(merged.isCompleted("node-2"));
        }

        @Test
        @DisplayName("should prefer more advanced status")
        void shouldPreferMoreAdvancedStatus() {
            ExecutionState state1 = ExecutionState.create().recordStart("node-1"); // RUNNING
            ExecutionState state2 = ExecutionState.create()
                .recordResult("node-1", NodeExecutionResult.success("node-1", Map.of())); // SUCCESS

            ExecutionState merged = state1.merge(state2);

            assertEquals(NodeStatus.COMPLETED, merged.getNodeStatus("node-1"));
        }

        @Test
        @DisplayName("should merge global data with other taking precedence")
        void shouldMergeGlobalData() {
            ExecutionState state1 = ExecutionState.create()
                .withGlobalData("key1", "from-state1")
                .withGlobalData("shared", "from-state1");
            ExecutionState state2 = ExecutionState.create()
                .withGlobalData("key2", "from-state2")
                .withGlobalData("shared", "from-state2");

            ExecutionState merged = state1.merge(state2);

            assertEquals("from-state1", merged.getGlobalData("key1").get());
            assertEquals("from-state2", merged.getGlobalData("key2").get());
            assertEquals("from-state2", merged.getGlobalData("shared").get()); // other takes precedence
        }
    }
}

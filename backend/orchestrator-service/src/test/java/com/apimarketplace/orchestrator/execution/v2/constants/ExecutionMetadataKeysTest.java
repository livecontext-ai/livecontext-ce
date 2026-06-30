package com.apimarketplace.orchestrator.execution.v2.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExecutionMetadataKeys utility class.
 */
@DisplayName("ExecutionMetadataKeys")
class ExecutionMetadataKeysTest {

    @Nested
    @DisplayName("Key construction helpers")
    class KeyConstruction {

        @Test
        @DisplayName("splitStateKey should build prefixed key")
        void splitStateKey() {
            assertEquals("split_state:core:my_split", ExecutionMetadataKeys.splitStateKey("core:my_split"));
        }

        @Test
        @DisplayName("currentItemKey should build suffixed key")
        void currentItemKey() {
            assertEquals("core:split.current_item", ExecutionMetadataKeys.currentItemKey("core:split"));
        }

        @Test
        @DisplayName("currentIndexKey should build suffixed key")
        void currentIndexKey() {
            assertEquals("core:split.current_index", ExecutionMetadataKeys.currentIndexKey("core:split"));
        }

        @Test
        @DisplayName("itemDataKey should build prefixed key")
        void itemDataKey() {
            assertEquals("item_data:item-0.1", ExecutionMetadataKeys.itemDataKey("item-0.1"));
        }

        @Test
        @DisplayName("itemIndexKey should build prefixed key")
        void itemIndexKey() {
            assertEquals("item_index:item-0.1", ExecutionMetadataKeys.itemIndexKey("item-0.1"));
        }

        @Test
        @DisplayName("splitIdKey should build prefixed key")
        void splitIdKey() {
            assertEquals("split_id:item-0.1", ExecutionMetadataKeys.splitIdKey("item-0.1"));
        }

        @Test
        @DisplayName("bodyCompletedKey should build composite key with iteration")
        void bodyCompletedKey() {
            assertEquals("body_completed:step_x:iter:2", ExecutionMetadataKeys.bodyCompletedKey("step_x", 2));
        }
    }

    @Nested
    @DisplayName("Metadata flag checkers")
    class MetadataFlagCheckers {

        @Test
        @DisplayName("isSplitAlreadyPersisted returns true when flag is set")
        void isSplitAlreadyPersistedTrue() {
            Map<String, Object> metadata = Map.of(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true);
            assertTrue(ExecutionMetadataKeys.isSplitAlreadyPersisted(metadata));
        }

        @Test
        @DisplayName("isSplitAlreadyPersisted returns false when flag is absent")
        void isSplitAlreadyPersistedFalse() {
            assertFalse(ExecutionMetadataKeys.isSplitAlreadyPersisted(Map.of()));
        }

        @Test
        @DisplayName("isSplitAlreadyPersisted returns false for null")
        void isSplitAlreadyPersistedNull() {
            assertFalse(ExecutionMetadataKeys.isSplitAlreadyPersisted(null));
        }

        @Test
        @DisplayName("isSplitSuccessorsHandled returns true when flag is set")
        void isSplitSuccessorsHandledTrue() {
            Map<String, Object> metadata = Map.of(ExecutionMetadataKeys.SPLIT_SUCCESSORS_HANDLED, true);
            assertTrue(ExecutionMetadataKeys.isSplitSuccessorsHandled(metadata));
        }

        @Test
        @DisplayName("isSplitSuccessorsHandled returns false for null")
        void isSplitSuccessorsHandledNull() {
            assertFalse(ExecutionMetadataKeys.isSplitSuccessorsHandled(null));
        }
    }

    @Nested
    @DisplayName("Output value helpers")
    class OutputValueHelpers {

        @Test
        @DisplayName("isTerminated returns true when terminated flag is set")
        void isTerminatedTrue() {
            Map<String, Object> output = Map.of(ExecutionMetadataKeys.TERMINATED, true);
            assertTrue(ExecutionMetadataKeys.isTerminated(output));
        }

        @Test
        @DisplayName("isTerminated returns false for null output")
        void isTerminatedNull() {
            assertFalse(ExecutionMetadataKeys.isTerminated(null));
        }

        @Test
        @DisplayName("shouldContinue returns true when continue flag is set")
        void shouldContinueTrue() {
            Map<String, Object> output = Map.of(ExecutionMetadataKeys.CONTINUE, true);
            assertTrue(ExecutionMetadataKeys.shouldContinue(output));
        }

        @Test
        @DisplayName("shouldContinue returns false for null output")
        void shouldContinueNull() {
            assertFalse(ExecutionMetadataKeys.shouldContinue(null));
        }

        @Test
        @DisplayName("getReason returns reason string from output")
        void getReason() {
            Map<String, Object> output = Map.of(ExecutionMetadataKeys.REASON, "condition_false");
            assertEquals("condition_false", ExecutionMetadataKeys.getReason(output));
        }

        @Test
        @DisplayName("getReason returns null for null output")
        void getReasonNull() {
            assertNull(ExecutionMetadataKeys.getReason(null));
        }

        @Test
        @DisplayName("getReason returns null when key is absent")
        void getReasonAbsent() {
            assertNull(ExecutionMetadataKeys.getReason(Map.of()));
        }

        @Test
        @DisplayName("getIteration returns iteration number")
        void getIteration() {
            Map<String, Object> output = Map.of(ExecutionMetadataKeys.ITERATION, 5);
            assertEquals(5, ExecutionMetadataKeys.getIteration(output));
        }

        @Test
        @DisplayName("getIteration returns null for null output")
        void getIterationNull() {
            assertNull(ExecutionMetadataKeys.getIteration(null));
        }

        @Test
        @DisplayName("getIteration returns null for non-number value")
        void getIterationNonNumber() {
            Map<String, Object> output = Map.of(ExecutionMetadataKeys.ITERATION, "not-a-number");
            assertNull(ExecutionMetadataKeys.getIteration(output));
        }

        @Test
        @DisplayName("getMaxIterations returns max iterations number")
        void getMaxIterations() {
            Map<String, Object> output = Map.of(ExecutionMetadataKeys.MAX_ITERATIONS, 100);
            assertEquals(100, ExecutionMetadataKeys.getMaxIterations(output));
        }

        @Test
        @DisplayName("getMaxIterations returns null for null output")
        void getMaxIterationsNull() {
            assertNull(ExecutionMetadataKeys.getMaxIterations(null));
        }

        @Test
        @DisplayName("getSelectedBranchIndex returns index number")
        void getSelectedBranchIndex() {
            Map<String, Object> output = Map.of(ExecutionMetadataKeys.SELECTED_BRANCH_INDEX, 2);
            assertEquals(2, ExecutionMetadataKeys.getSelectedBranchIndex(output));
        }

        @Test
        @DisplayName("getSelectedBranchIndex returns null for null output")
        void getSelectedBranchIndexNull() {
            assertNull(ExecutionMetadataKeys.getSelectedBranchIndex(null));
        }

        @Test
        @DisplayName("getNodeType returns node type string")
        void getNodeType() {
            Map<String, Object> output = Map.of(ExecutionMetadataKeys.NODE_TYPE, "decision");
            assertEquals("decision", ExecutionMetadataKeys.getNodeType(output));
        }

        @Test
        @DisplayName("getNodeType returns null for null output")
        void getNodeTypeNull() {
            assertNull(ExecutionMetadataKeys.getNodeType(null));
        }

        @Test
        @DisplayName("getItemCount returns count number")
        void getItemCount() {
            Map<String, Object> output = Map.of(ExecutionMetadataKeys.ITEM_COUNT, 10);
            assertEquals(10, ExecutionMetadataKeys.getItemCount(output));
        }

        @Test
        @DisplayName("getItemCount returns null for null output")
        void getItemCountNull() {
            assertNull(ExecutionMetadataKeys.getItemCount(null));
        }
    }

    @Nested
    @DisplayName("Constants are correct")
    class ConstantsValues {

        @Test
        @DisplayName("TERMINATED constant")
        void terminatedConstant() {
            assertEquals("terminated", ExecutionMetadataKeys.TERMINATED);
        }

        @Test
        @DisplayName("CONTINUE constant")
        void continueConstant() {
            assertEquals("continue", ExecutionMetadataKeys.CONTINUE);
        }

        @Test
        @DisplayName("REASON constant")
        void reasonConstant() {
            assertEquals("reason", ExecutionMetadataKeys.REASON);
        }

        @Test
        @DisplayName("SPLIT_STATE_PREFIX constant")
        void splitStatePrefixConstant() {
            assertEquals("split_state:", ExecutionMetadataKeys.SPLIT_STATE_PREFIX);
        }
    }
}

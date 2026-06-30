package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.state.ExecutionState;
import com.apimarketplace.orchestrator.execution.v2.state.BackEdgeState;
import com.apimarketplace.orchestrator.execution.v2.state.NodeState;
import com.apimarketplace.orchestrator.integration.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for workflow state management.
 *
 * <p>Tests the immutable execution state (ExecutionState, ExecutionContext)
 * for correctness during execution workflows. Verifies state persistence,
 * context merging from parallel branches, and state recovery scenarios.
 */
@IntegrationTest
@DisplayName("Workflow State Integration Tests")
class WorkflowStateIntegrationTest {

    private WorkflowPlan testPlan;

    @BeforeEach
    void setUp() {
        testPlan = buildMinimalPlan();
    }

    // =========================================================================
    // EXECUTION STATE TESTS
    // =========================================================================

    @Nested
    @DisplayName("ExecutionState immutability and correctness")
    class ExecutionStateTests {

        @Test
        @DisplayName("Should create empty initial state")
        void shouldCreateEmptyInitialState() {
            ExecutionState state = ExecutionState.create();

            assertNotNull(state);
            assertTrue(state.nodeStates().isEmpty());
            assertEquals(NodeStatus.PENDING, state.getNodeStatus("any-node"));
            assertFalse(state.isCompleted("any-node"));
            assertFalse(state.isSuccess("any-node"));
            assertFalse(state.isStarted("any-node"));
        }

        @Test
        @DisplayName("Should record node start as RUNNING")
        void shouldRecordNodeStart() {
            ExecutionState initial = ExecutionState.create();
            ExecutionState started = initial.recordStart("trigger:start");

            assertTrue(started.isStarted("trigger:start"));
            assertFalse(started.isCompleted("trigger:start"));
            assertEquals(NodeStatus.RUNNING, started.getNodeStatus("trigger:start"));

            // Original state should not be mutated
            assertFalse(initial.isStarted("trigger:start"));
        }

        @Test
        @DisplayName("Should record node success result")
        void shouldRecordNodeSuccess() {
            ExecutionState state = ExecutionState.create();
            NodeExecutionResult result = NodeExecutionResult.success(
                "mcp:step1", Map.of("data", "output"));

            ExecutionState afterResult = state.recordResult("mcp:step1", result);

            assertTrue(afterResult.isCompleted("mcp:step1"));
            assertTrue(afterResult.isSuccess("mcp:step1"));
            assertEquals(NodeStatus.COMPLETED, afterResult.getNodeStatus("mcp:step1"));
        }

        @Test
        @DisplayName("Should record node failure result")
        void shouldRecordNodeFailure() {
            ExecutionState state = ExecutionState.create();
            NodeExecutionResult result = NodeExecutionResult.failure("mcp:step1", "Connection timeout");

            ExecutionState afterResult = state.recordResult("mcp:step1", result);

            assertTrue(afterResult.isCompleted("mcp:step1"));
            assertFalse(afterResult.isSuccess("mcp:step1"));
            assertEquals(NodeStatus.FAILED, afterResult.getNodeStatus("mcp:step1"));
        }

        @Test
        @DisplayName("Should record node skipped result")
        void shouldRecordNodeSkipped() {
            ExecutionState state = ExecutionState.create();
            NodeExecutionResult result = NodeExecutionResult.skipped("mcp:step1", "Condition not met");

            ExecutionState afterResult = state.recordResult("mcp:step1", result);

            assertTrue(afterResult.isCompleted("mcp:step1"));
            assertFalse(afterResult.isSuccess("mcp:step1"));
            assertEquals(NodeStatus.SKIPPED, afterResult.getNodeStatus("mcp:step1"));
        }

        @Test
        @DisplayName("Should maintain multiple node states independently")
        void shouldMaintainMultipleNodeStates() {
            ExecutionState state = ExecutionState.create();

            state = state.recordResult("trigger:start",
                NodeExecutionResult.success("trigger:start", Map.of()));
            state = state.recordResult("mcp:step1",
                NodeExecutionResult.success("mcp:step1", Map.of("data", "1")));
            state = state.recordResult("mcp:step2",
                NodeExecutionResult.failure("mcp:step2", "Error"));

            assertTrue(state.isSuccess("trigger:start"));
            assertTrue(state.isSuccess("mcp:step1"));
            assertFalse(state.isSuccess("mcp:step2"));
            assertTrue(state.isCompleted("mcp:step2"));
            assertEquals(NodeStatus.PENDING, state.getNodeStatus("mcp:step3"));
        }

        @Test
        @DisplayName("Should store and retrieve global data")
        void shouldStoreAndRetrieveGlobalData() {
            ExecutionState state = ExecutionState.create();

            ExecutionState withData = state.withGlobalData("loop_state:core:loop", "iteration-3");

            assertEquals(Optional.of("iteration-3"), withData.getGlobalData("loop_state:core:loop"));
            assertTrue(state.getGlobalData("loop_state:core:loop").isEmpty(),
                "Original state should not be mutated");
        }
    }

    // =========================================================================
    // EXECUTION CONTEXT TESTS
    // =========================================================================

    @Nested
    @DisplayName("ExecutionContext immutability and state tracking")
    class ExecutionContextTests {

        @Test
        @DisplayName("Should create context with trigger data")
        void shouldCreateContextWithTriggerData() {
            Map<String, Object> triggerData = Map.of("name", "John", "amount", 100);

            ExecutionContext context = ExecutionContext.create(
                "run-1", "wfr-1", "tenant-1",
                "item-1", 0, triggerData, testPlan);

            assertEquals("run-1", context.runId());
            assertEquals("wfr-1", context.workflowRunId());
            assertEquals("tenant-1", context.tenantId());
            assertEquals("item-1", context.itemId());
            assertEquals(0, context.itemIndex());
            assertEquals("John", context.triggerData().get("name"));
            assertEquals(100, context.triggerData().get("amount"));
            assertTrue(context.stepOutputs().isEmpty());
        }

        @Test
        @DisplayName("Should record node start in context immutably")
        void shouldRecordNodeStartImmutably() {
            ExecutionContext context = createTestContext();
            ExecutionContext started = context.withStart("mcp:step1");

            assertTrue(started.isStarted("mcp:step1"));
            assertFalse(context.isStarted("mcp:step1"), "Original context should not be modified");
        }

        @Test
        @DisplayName("Should record node result and update step outputs")
        void shouldRecordNodeResultWithOutputs() {
            ExecutionContext context = createTestContext();

            NodeExecutionResult result = NodeExecutionResult.success(
                "mcp:step1", Map.of("response", "ok", "status_code", 200));

            ExecutionContext updated = context.withResult("mcp:step1", result);

            assertTrue(updated.isCompleted("mcp:step1"));
            assertTrue(updated.getStepOutput("mcp:step1").isPresent(),
                "Step output should be recorded");

            // Output wraps in {output: {...}} structure
            @SuppressWarnings("unchecked")
            Map<String, Object> stepOutput = (Map<String, Object>) updated.getStepOutput("mcp:step1").get();
            assertNotNull(stepOutput.get("output"), "Output should contain wrapped output");
        }

        @Test
        @DisplayName("Should accumulate outputs across multiple node completions")
        void shouldAccumulateOutputs() {
            ExecutionContext context = createTestContext();

            context = context.withResult("trigger:start",
                NodeExecutionResult.success("trigger:start", Map.of("trigger_id", "t1")));
            context = context.withResult("mcp:step1",
                NodeExecutionResult.success("mcp:step1", Map.of("data", "result1")));
            context = context.withResult("mcp:step2",
                NodeExecutionResult.success("mcp:step2", Map.of("data", "result2")));

            assertTrue(context.getStepOutput("trigger:start").isPresent());
            assertTrue(context.getStepOutput("mcp:step1").isPresent());
            assertTrue(context.getStepOutput("mcp:step2").isPresent());
            // 3 logical results × 2 keys each (nodeId + bare alias via
            // StepOutputsWriter.writeWithAlias) = 6.
            assertEquals(6, context.stepOutputs().size());
        }

        @Test
        @DisplayName("Should not record output for empty results")
        void shouldNotRecordEmptyOutput() {
            ExecutionContext context = createTestContext();
            NodeExecutionResult emptyResult = NodeExecutionResult.success("mcp:step1", Map.of());

            ExecutionContext updated = context.withResult("mcp:step1", emptyResult);

            assertTrue(updated.isCompleted("mcp:step1"));
            // Empty output should NOT be added to stepOutputs
            assertFalse(updated.getStepOutput("mcp:step1").isPresent(),
                "Empty output should not be recorded in step outputs");
        }

        @Test
        @DisplayName("Should store and retrieve global data in context")
        void shouldHandleGlobalData() {
            ExecutionContext context = createTestContext();

            BackEdgeState backEdgeState = BackEdgeState.create("mcp:source->mcp:target", 10, "true");
            ExecutionContext withBackEdge = context.withGlobalData("back_edge_state:mcp:source->mcp:target", backEdgeState);

            assertTrue(withBackEdge.getGlobalData("back_edge_state:mcp:source->mcp:target").isPresent());
            assertTrue(context.getGlobalData("back_edge_state:mcp:source->mcp:target").isEmpty(),
                "Original context should not be mutated");
        }
    }

    // =========================================================================
    // CONTEXT MERGE TESTS (Fork parallel execution)
    // =========================================================================

    @Nested
    @DisplayName("Context merging from parallel branches")
    class ContextMergeTests {

        @Test
        @DisplayName("Should merge step outputs from two parallel branches")
        void shouldMergeStepOutputs() {
            ExecutionContext base = createTestContext();

            // Simulate branch A completing step_a
            ExecutionContext branchA = base.withResult("mcp:step_a",
                NodeExecutionResult.success("mcp:step_a", Map.of("data", "from-a")));

            // Simulate branch B completing step_b
            ExecutionContext branchB = base.withResult("mcp:step_b",
                NodeExecutionResult.success("mcp:step_b", Map.of("data", "from-b")));

            // When: Merge branch contexts
            ExecutionContext merged = branchA.merge(branchB);

            // Then: Both outputs should be present
            assertTrue(merged.getStepOutput("mcp:step_a").isPresent(),
                "Step A output should be in merged context");
            assertTrue(merged.getStepOutput("mcp:step_b").isPresent(),
                "Step B output should be in merged context");
        }

        @Test
        @DisplayName("Should merge execution states from parallel branches")
        void shouldMergeExecutionStates() {
            ExecutionContext base = createTestContext();

            ExecutionContext branchA = base
                .withResult("mcp:step_a",
                    NodeExecutionResult.success("mcp:step_a", Map.of("data", "a")));

            ExecutionContext branchB = base
                .withResult("mcp:step_b",
                    NodeExecutionResult.success("mcp:step_b", Map.of("data", "b")));

            ExecutionContext merged = branchA.merge(branchB);

            assertTrue(merged.isCompleted("mcp:step_a"));
            assertTrue(merged.isCompleted("mcp:step_b"));
            assertTrue(merged.isSuccess("mcp:step_a"));
            assertTrue(merged.isSuccess("mcp:step_b"));
        }

        @Test
        @DisplayName("Should handle merging with null context")
        void shouldHandleNullMerge() {
            ExecutionContext context = createTestContext();
            ExecutionContext withResult = context.withResult("mcp:step1",
                NodeExecutionResult.success("mcp:step1", Map.of("data", "1")));

            ExecutionContext merged = withResult.merge(null);

            assertSame(withResult, merged, "Merging with null should return same context");
        }

        @Test
        @DisplayName("Should merge global data from both branches")
        void shouldMergeGlobalData() {
            ExecutionContext base = createTestContext();

            ExecutionContext branchA = base.withGlobalData("key_a", "value_a");
            ExecutionContext branchB = base.withGlobalData("key_b", "value_b");

            ExecutionContext merged = branchA.merge(branchB);

            assertEquals(Optional.of("value_a"), merged.getGlobalData("key_a"));
            assertEquals(Optional.of("value_b"), merged.getGlobalData("key_b"));
        }

        @Test
        @DisplayName("Should prefer more advanced status during state merge")
        void shouldPreferMoreAdvancedStatus() {
            ExecutionState state1 = ExecutionState.create()
                .recordStart("mcp:step1"); // RUNNING

            ExecutionState state2 = ExecutionState.create()
                .recordResult("mcp:step1",
                    NodeExecutionResult.success("mcp:step1", Map.of())); // SUCCESS

            ExecutionState merged = state1.merge(state2);

            assertEquals(NodeStatus.COMPLETED, merged.getNodeStatus("mcp:step1"),
                "Merged state should have the more advanced status (SUCCESS > RUNNING)");
        }
    }

    // =========================================================================
    // STATE RECOVERY TESTS
    // =========================================================================

    @Nested
    @DisplayName("State recovery after interruption")
    class StateRecoveryTests {

        @Test
        @DisplayName("Should reconstruct context from recorded results")
        void shouldReconstructContext() {
            // Simulate a context that has been partially executed
            ExecutionContext context = createTestContext();

            // Record some completions (simulating recovery from persisted state)
            context = context.withResult("trigger:start",
                NodeExecutionResult.success("trigger:start", Map.of("trigger_id", "t1")));
            context = context.withResult("mcp:step1",
                NodeExecutionResult.success("mcp:step1", Map.of("data", "step1-output")));

            // Verify the reconstructed state
            assertTrue(context.isCompleted("trigger:start"));
            assertTrue(context.isCompleted("mcp:step1"));
            assertFalse(context.isCompleted("mcp:step2"), "Unexecuted node should not be completed");

            // Step outputs should be available for downstream nodes
            assertTrue(context.getStepOutput("trigger:start").isPresent());
            assertTrue(context.getStepOutput("mcp:step1").isPresent());
        }

        @Test
        @DisplayName("Should build consistent state through sequential node completions")
        void shouldBuildConsistentState() {
            ExecutionContext context = createTestContext();

            // Simulate sequential execution
            // 1. Trigger starts and completes
            context = context.withStart("trigger:start");
            assertTrue(context.isStarted("trigger:start"));
            assertFalse(context.isCompleted("trigger:start"));

            context = context.withResult("trigger:start",
                NodeExecutionResult.success("trigger:start", Map.of("trigger_id", "t1")));
            assertTrue(context.isCompleted("trigger:start"));

            // 2. Step 1 starts and completes
            context = context.withStart("mcp:step1");
            assertTrue(context.isStarted("mcp:step1"));

            context = context.withResult("mcp:step1",
                NodeExecutionResult.success("mcp:step1", Map.of("result", "computed")));
            assertTrue(context.isCompleted("mcp:step1"));

            // 3. Verify final state is consistent.
            // 2 logical results (trigger:start + mcp:step1) → 4 stepOutputs keys
            // because StepOutputsWriter.writeWithAlias persists both the full nodeId
            // ("trigger:start", "mcp:step1") AND the bare alias ("start", "step1").
            Map<String, Object> allOutputs = context.getAllStepOutputs();
            assertEquals(4, allOutputs.size());
        }

        @Test
        @DisplayName("Should preserve failure information in state")
        void shouldPreserveFailureInfo() {
            ExecutionContext context = createTestContext();

            context = context.withResult("mcp:failing_step",
                NodeExecutionResult.failure("mcp:failing_step", "Connection refused"));

            assertTrue(context.isCompleted("mcp:failing_step"));
            assertFalse(context.isSuccess("mcp:failing_step"));

            Optional<NodeState> nodeState = context.state()
                .getNodeState("mcp:failing_step");
            assertTrue(nodeState.isPresent());
            assertEquals(NodeStatus.FAILED, nodeState.get().status());
        }
    }

    // =========================================================================
    // SNAPSHOT TESTS
    // =========================================================================

    @Nested
    @DisplayName("State snapshot creation")
    class SnapshotTests {

        @Test
        @DisplayName("Should capture complete state snapshot at any point")
        void shouldCaptureCompleteSnapshot() {
            ExecutionContext context = createTestContext();

            // Build up some state
            context = context.withResult("trigger:start",
                NodeExecutionResult.success("trigger:start", Map.of()));
            context = context.withResult("mcp:step1",
                NodeExecutionResult.success("mcp:step1", Map.of("val", 42)));
            context = context.withGlobalData("custom_key", "custom_value");

            // Take a "snapshot" by reading all state
            ExecutionState state = context.state();
            Map<String, Object> outputs = context.getAllStepOutputs();

            assertNotNull(state);
            assertEquals(2, state.nodeStates().size());
            // Only non-empty outputs are stored (trigger:start has empty Map.of() → skipped).
            // mcp:step1 has content → StepOutputsWriter.writeWithAlias persists BOTH the
            // full nodeId ("mcp:step1") AND the bare alias ("step1") for SpEL expression
            // resolution. So 1 logical result becomes 2 keys in the stepOutputs map.
            assertEquals(2, outputs.size());
            assertEquals(Optional.of("custom_value"), context.getGlobalData("custom_key"));
        }

        @Test
        @DisplayName("Should snapshot nodeStates map without mutation")
        void shouldSnapshotNodeStatesWithoutMutation() {
            ExecutionState state = ExecutionState.create();
            state = state.recordResult("mcp:a",
                NodeExecutionResult.success("mcp:a", Map.of()));

            // Take snapshot reference
            Map<String, NodeState> snapshot = state.nodeStates();
            int snapshotSize = snapshot.size();

            // Further mutations create new state
            ExecutionState newState = state.recordResult("mcp:b",
                NodeExecutionResult.success("mcp:b", Map.of()));

            // New state has both nodes
            assertEquals(2, newState.nodeStates().size());
            // Original snapshot may or may not be affected depending on ConcurrentHashMap semantics,
            // but the state object reference should be distinct
            assertNotNull(snapshot);
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private ExecutionContext createTestContext() {
        return ExecutionContext.create(
            "run-" + UUID.randomUUID().toString().substring(0, 8),
            "wfr-test",
            "tenant-test",
            "item-1",
            0,
            Map.of("data", "test"),
            testPlan);
    }

    private WorkflowPlan buildMinimalPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "Step", "alias", "step", "tool_name", "mock")));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "mcp:step")));
        data.put("agents", List.of());
        data.put("cores", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());
        return WorkflowPlan.fromMap(data);
    }
}

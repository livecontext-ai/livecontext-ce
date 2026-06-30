package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.state.ExecutionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for ExecutionContext record.
 *
 * ExecutionContext is an immutable execution context for a single item traversal.
 * All mutations return a new ExecutionContext instance.
 */
@DisplayName("ExecutionContext")
class ExecutionContextTest {

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("Should create context with all parameters")
        void shouldCreateContextWithAllParameters() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Map<String, Object> triggerData = Map.of("key", "value");

            ExecutionContext ctx = ExecutionContext.create(
                "run-1",
                "workflow-run-1",
                "tenant-1",
                "item-1",
                0,
                triggerData,
                plan
            );

            assertEquals("run-1", ctx.runId());
            assertEquals("workflow-run-1", ctx.workflowRunId());
            assertEquals("tenant-1", ctx.tenantId());
            assertEquals("item-1", ctx.itemId());
            assertEquals(0, ctx.itemIndex());
            assertEquals("value", ctx.triggerData().get("key"));
            assertNotNull(ctx.state());
            assertEquals(plan, ctx.plan());
        }

        @Test
        @DisplayName("Should create context with empty step outputs")
        void shouldCreateContextWithEmptyStepOutputs() {
            WorkflowPlan plan = mock(WorkflowPlan.class);

            ExecutionContext ctx = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1",
                "item-1", 0, Map.of(), plan
            );

            assertTrue(ctx.stepOutputs().isEmpty());
            assertTrue(ctx.getAllStepOutputs().isEmpty());
        }

        @Test
        @DisplayName("Should create defensive copy of trigger data")
        void shouldCreateDefensiveCopyOfTriggerData() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("key", "original");

            ExecutionContext ctx = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1",
                "item-1", 0, triggerData, plan
            );

            // Modify original
            triggerData.put("key", "modified");

            // Context should have original value
            assertEquals("original", ctx.triggerData().get("key"));
        }
    }

    @Nested
    @DisplayName("Completion checks")
    class CompletionChecksTests {

        @Test
        @DisplayName("isCompleted() should return false for unexecuted node")
        void isCompletedShouldReturnFalseForUnexecutedNode() {
            ExecutionContext ctx = createContext();

            assertFalse(ctx.isCompleted("mcp:step_1"));
        }

        @Test
        @DisplayName("isCompleted() should return true after withResult")
        void isCompletedShouldReturnTrueAfterWithResult() {
            ExecutionContext ctx = createContext();
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step_1", Map.of("data", "value"));

            ExecutionContext updated = ctx.withResult("mcp:step_1", result);

            assertTrue(updated.isCompleted("mcp:step_1"));
        }

        @Test
        @DisplayName("isSuccess() should return false for unexecuted node")
        void isSuccessShouldReturnFalseForUnexecutedNode() {
            ExecutionContext ctx = createContext();

            assertFalse(ctx.isSuccess("mcp:step_1"));
        }

        @Test
        @DisplayName("isSuccess() should return true for successful node")
        void isSuccessShouldReturnTrueForSuccessfulNode() {
            ExecutionContext ctx = createContext();
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step_1", Map.of());

            ExecutionContext updated = ctx.withResult("mcp:step_1", result);

            assertTrue(updated.isSuccess("mcp:step_1"));
        }

        @Test
        @DisplayName("isSuccess() should return false for failed node")
        void isSuccessShouldReturnFalseForFailedNode() {
            ExecutionContext ctx = createContext();
            NodeExecutionResult result = NodeExecutionResult.failure("mcp:step_1", "Error");

            ExecutionContext updated = ctx.withResult("mcp:step_1", result);

            assertFalse(updated.isSuccess("mcp:step_1"));
            assertTrue(updated.isCompleted("mcp:step_1"));
        }

        @Test
        @DisplayName("isStarted() should return false for unstarted node")
        void isStartedShouldReturnFalseForUnstartedNode() {
            ExecutionContext ctx = createContext();

            assertFalse(ctx.isStarted("mcp:step_1"));
        }

        @Test
        @DisplayName("isStarted() should return true after withStart")
        void isStartedShouldReturnTrueAfterWithStart() {
            ExecutionContext ctx = createContext();

            ExecutionContext updated = ctx.withStart("mcp:step_1");

            assertTrue(updated.isStarted("mcp:step_1"));
        }
    }

    @Nested
    @DisplayName("Step outputs")
    class StepOutputsTests {

        @Test
        @DisplayName("getStepOutput() should return empty for missing step")
        void getStepOutputShouldReturnEmptyForMissingStep() {
            ExecutionContext ctx = createContext();

            Optional<Object> output = ctx.getStepOutput("mcp:unknown");

            assertTrue(output.isEmpty());
        }

        @Test
        @DisplayName("getStepOutput() should return output after withResult")
        void getStepOutputShouldReturnOutputAfterWithResult() {
            ExecutionContext ctx = createContext();
            Map<String, Object> outputData = Map.of("status", 200, "body", "response");
            NodeExecutionResult result = NodeExecutionResult.success("mcp:api_call", outputData);

            ExecutionContext updated = ctx.withResult("mcp:api_call", result);

            Optional<Object> output = updated.getStepOutput("mcp:api_call");
            assertTrue(output.isPresent());
        }

        @Test
        @DisplayName("getAllStepOutputs() should return copy")
        void getAllStepOutputsShouldReturnCopy() {
            ExecutionContext ctx = createContext();
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step", Map.of("data", "value"));

            ExecutionContext updated = ctx.withResult("mcp:step", result);
            Map<String, Object> outputs = updated.getAllStepOutputs();

            assertNotSame(updated.stepOutputs(), outputs);
        }

        @Test
        @DisplayName("withResult() should wrap output with httpstatus structure")
        void withResultShouldWrapOutputWithHttpstatusStructure() {
            ExecutionContext ctx = createContext();
            Map<String, Object> outputData = new HashMap<>();
            outputData.put("name", "John");
            outputData.put("http_status", 201);
            NodeExecutionResult result = NodeExecutionResult.success("mcp:api", outputData);

            ExecutionContext updated = ctx.withResult("mcp:api", result);

            @SuppressWarnings("unchecked")
            Map<String, Object> stepOutput = (Map<String, Object>) updated.getStepOutput("mcp:api").orElse(null);
            assertNotNull(stepOutput);
            assertTrue(stepOutput.containsKey("output"));

            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) stepOutput.get("output");
            assertTrue(output.containsKey("httpstatus"));

            @SuppressWarnings("unchecked")
            Map<String, Object> httpstatus = (Map<String, Object>) output.get("httpstatus");
            assertEquals(201, httpstatus.get("code"));
            assertEquals("", httpstatus.get("error"));
        }
    }

    @Nested
    @DisplayName("withStart()")
    class WithStartTests {

        @Test
        @DisplayName("Should return new context")
        void shouldReturnNewContext() {
            ExecutionContext ctx = createContext();

            ExecutionContext updated = ctx.withStart("mcp:step_1");

            assertNotSame(ctx, updated);
        }

        @Test
        @DisplayName("Should preserve other fields")
        void shouldPreserveOtherFields() {
            ExecutionContext ctx = createContext();

            ExecutionContext updated = ctx.withStart("mcp:step_1");

            assertEquals(ctx.runId(), updated.runId());
            assertEquals(ctx.workflowRunId(), updated.workflowRunId());
            assertEquals(ctx.tenantId(), updated.tenantId());
            assertEquals(ctx.itemId(), updated.itemId());
            assertEquals(ctx.itemIndex(), updated.itemIndex());
        }
    }

    @Nested
    @DisplayName("withResult()")
    class WithResultTests {

        @Test
        @DisplayName("Should return new context")
        void shouldReturnNewContext() {
            ExecutionContext ctx = createContext();
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step", Map.of());

            ExecutionContext updated = ctx.withResult("mcp:step", result);

            assertNotSame(ctx, updated);
        }

        @Test
        @DisplayName("Should not modify original context")
        void shouldNotModifyOriginalContext() {
            ExecutionContext ctx = createContext();
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step", Map.of("data", "value"));

            ctx.withResult("mcp:step", result);

            assertFalse(ctx.isCompleted("mcp:step"));
            assertTrue(ctx.getStepOutput("mcp:step").isEmpty());
        }

        @Test
        @DisplayName("Should handle empty output")
        void shouldHandleEmptyOutput() {
            ExecutionContext ctx = createContext();
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step", Map.of());

            ExecutionContext updated = ctx.withResult("mcp:step", result);

            assertTrue(updated.isCompleted("mcp:step"));
        }

        @Test
        @DisplayName("Should handle null output")
        void shouldHandleNullOutput() {
            ExecutionContext ctx = createContext();
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step", null);

            ExecutionContext updated = ctx.withResult("mcp:step", result);

            assertTrue(updated.isCompleted("mcp:step"));
        }
    }

    @Nested
    @DisplayName("Global data")
    class GlobalDataTests {

        @Test
        @DisplayName("withGlobalData() should store data")
        void withGlobalDataShouldStoreData() {
            ExecutionContext ctx = createContext();

            ExecutionContext updated = ctx.withGlobalData("myKey", "myValue");

            Optional<Object> value = updated.getGlobalData("myKey");
            assertTrue(value.isPresent());
            assertEquals("myValue", value.get());
        }

        @Test
        @DisplayName("getGlobalData() should return empty for missing key")
        void getGlobalDataShouldReturnEmptyForMissingKey() {
            ExecutionContext ctx = createContext();

            Optional<Object> value = ctx.getGlobalData("nonexistent");

            assertTrue(value.isEmpty());
        }

        @Test
        @DisplayName("getGlobalDataKeys() should return all keys")
        void getGlobalDataKeysShouldReturnAllKeys() {
            ExecutionContext ctx = createContext()
                .withGlobalData("key1", "value1")
                .withGlobalData("key2", "value2");

            assertTrue(ctx.getGlobalDataKeys().contains("key1"));
            assertTrue(ctx.getGlobalDataKeys().contains("key2"));
        }
    }

    @Nested
    @DisplayName("merge()")
    class MergeTests {

        @Test
        @DisplayName("Should return same context when merging with null")
        void shouldReturnSameContextWhenMergingWithNull() {
            ExecutionContext ctx = createContext();

            ExecutionContext merged = ctx.merge(null);

            assertSame(ctx, merged);
        }

        @Test
        @DisplayName("Should merge step outputs from both contexts")
        void shouldMergeStepOutputsFromBothContexts() {
            ExecutionContext ctx1 = createContext()
                .withResult("mcp:step_a", NodeExecutionResult.success("mcp:step_a", Map.of("a", 1)));
            ExecutionContext ctx2 = createContext()
                .withResult("mcp:step_b", NodeExecutionResult.success("mcp:step_b", Map.of("b", 2)));

            ExecutionContext merged = ctx1.merge(ctx2);

            assertTrue(merged.getStepOutput("mcp:step_a").isPresent());
            assertTrue(merged.getStepOutput("mcp:step_b").isPresent());
        }

        @Test
        @DisplayName("Should merge execution states")
        void shouldMergeExecutionStates() {
            ExecutionContext ctx1 = createContext()
                .withResult("mcp:step_a", NodeExecutionResult.success("mcp:step_a", Map.of()));
            ExecutionContext ctx2 = createContext()
                .withResult("mcp:step_b", NodeExecutionResult.success("mcp:step_b", Map.of()));

            ExecutionContext merged = ctx1.merge(ctx2);

            assertTrue(merged.isCompleted("mcp:step_a"));
            assertTrue(merged.isCompleted("mcp:step_b"));
        }

        @Test
        @DisplayName("Should preserve identity fields")
        void shouldPreserveIdentityFields() {
            ExecutionContext ctx1 = createContext();
            ExecutionContext ctx2 = createContext();

            ExecutionContext merged = ctx1.merge(ctx2);

            assertEquals(ctx1.runId(), merged.runId());
            assertEquals(ctx1.workflowRunId(), merged.workflowRunId());
            assertEquals(ctx1.tenantId(), merged.tenantId());
            assertEquals(ctx1.itemId(), merged.itemId());
            assertEquals(ctx1.itemIndex(), merged.itemIndex());
        }
    }

    @Nested
    @DisplayName("withoutNodes()")
    class WithoutNodesTests {

        @Test
        @DisplayName("Should return new context without specified nodes")
        void shouldReturnContextWithoutSpecifiedNodes() {
            ExecutionContext ctx = createContext()
                .withResult("mcp:step_a", NodeExecutionResult.success("mcp:step_a", Map.of("a", 1)))
                .withResult("mcp:step_b", NodeExecutionResult.success("mcp:step_b", Map.of("b", 2)))
                .withResult("mcp:step_c", NodeExecutionResult.success("mcp:step_c", Map.of("c", 3)));

            ExecutionContext updated = ctx.withoutNodes(Set.of("mcp:step_a", "mcp:step_b"));

            assertTrue(updated.getStepOutput("mcp:step_a").isEmpty());
            assertTrue(updated.getStepOutput("mcp:step_b").isEmpty());
            assertTrue(updated.getStepOutput("mcp:step_c").isPresent());
        }

        @Test
        @DisplayName("Should not modify original context")
        void shouldNotModifyOriginalContext() {
            ExecutionContext ctx = createContext()
                .withResult("mcp:step_a", NodeExecutionResult.success("mcp:step_a", Map.of("a", 1)));

            ctx.withoutNodes(Set.of("mcp:step_a"));

            assertTrue(ctx.getStepOutput("mcp:step_a").isPresent());
        }

        @Test
        @DisplayName("Should handle empty node set")
        void shouldHandleEmptyNodeSet() {
            ExecutionContext ctx = createContext()
                .withResult("mcp:step_a", NodeExecutionResult.success("mcp:step_a", Map.of("a", 1)));

            ExecutionContext updated = ctx.withoutNodes(Set.of());

            assertTrue(updated.getStepOutput("mcp:step_a").isPresent());
        }

        @Test
        @DisplayName("Should handle removing non-existent nodes")
        void shouldHandleRemovingNonExistentNodes() {
            ExecutionContext ctx = createContext();

            // Should not throw
            ExecutionContext updated = ctx.withoutNodes(Set.of("mcp:nonexistent"));

            assertNotNull(updated);
        }

        @Test
        @DisplayName("Should remove completion state for removed nodes")
        void shouldRemoveCompletionStateForRemovedNodes() {
            ExecutionContext ctx = createContext()
                .withResult("mcp:step_a", NodeExecutionResult.success("mcp:step_a", Map.of()));

            assertTrue(ctx.isCompleted("mcp:step_a"));

            ExecutionContext updated = ctx.withoutNodes(Set.of("mcp:step_a"));

            assertFalse(updated.isCompleted("mcp:step_a"));
        }
    }

    // ===== Helper methods =====

    private ExecutionContext createContext() {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        return ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            Map.of("trigger", "data"),
            plan
        );
    }
}

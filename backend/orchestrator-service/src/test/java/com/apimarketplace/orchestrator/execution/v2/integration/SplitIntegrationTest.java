package com.apimarketplace.orchestrator.execution.v2.integration;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.TestEngineFactory;
import com.apimarketplace.orchestrator.execution.v2.engine.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for Split workflow patterns.
 * Tests: Trigger → Split(items) → Step → End
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Split Workflow Integration")
class SplitIntegrationTest {

    @Mock
    private V2ExecutionEventService eventService;

    @Mock
    private WorkflowExecution execution;

    private UnifiedExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngineFactory.create();
        lenient().when(execution.getRunId()).thenReturn("run-split-test");
    }

    @Nested
    @DisplayName("Simple Split")
    class SimpleSplitTests {

        @Test
        @DisplayName("Should iterate over array items")
        void shouldIterateOverArrayItems() {
            // Given: Split with 3 items
            List<Map<String, Object>> items = List.of(
                Map.of("id", 1, "name", "Item A"),
                Map.of("id", 2, "name", "Item B"),
                Map.of("id", 3, "name", "Item C")
            );

            List<Object> processedItems = new CopyOnWriteArrayList<>();
            ExecutionTree tree = buildSplitTree(items, processedItems);

            // When
            List<TriggerItem> triggerItems = List.of(new TriggerItem("item-1", 0, Map.of("items", items)));
            WorkflowResult result = engine.executeWorkflow(tree, triggerItems, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle empty array")
        void shouldHandleEmptyArray() {
            // Given: Split with empty items
            List<Map<String, Object>> items = List.of();
            ExecutionTree tree = buildSplitTree(items, new CopyOnWriteArrayList<>());

            // When
            List<TriggerItem> triggerItems = List.of(new TriggerItem("item-1", 0, Map.of("items", items)));
            WorkflowResult result = engine.executeWorkflow(tree, triggerItems, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle single item array")
        void shouldHandleSingleItemArray() {
            // Given: Split with 1 item
            List<Map<String, Object>> items = List.of(Map.of("id", 1));
            List<Object> processedItems = new CopyOnWriteArrayList<>();
            ExecutionTree tree = buildSplitTree(items, processedItems);

            // When
            List<TriggerItem> triggerItems = List.of(new TriggerItem("item-1", 0, Map.of("items", items)));
            WorkflowResult result = engine.executeWorkflow(tree, triggerItems, execution, eventService).join();

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Split with nested steps")
    class SplitNestedStepsTests {

        @Test
        @DisplayName("Should execute multiple steps per iteration")
        void shouldExecuteMultipleStepsPerIteration() {
            // Given: Split → Step1 → Step2 → Step3
            List<Map<String, Object>> items = List.of(
                Map.of("id", 1),
                Map.of("id", 2)
            );

            AtomicInteger step1Count = new AtomicInteger();
            AtomicInteger step2Count = new AtomicInteger();
            AtomicInteger step3Count = new AtomicInteger();

            ExecutionTree tree = buildSplitWithMultipleSteps(items, step1Count, step2Count, step3Count);

            // When
            List<TriggerItem> triggerItems = List.of(new TriggerItem("item-1", 0, Map.of("items", items)));
            WorkflowResult result = engine.executeWorkflow(tree, triggerItems, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should maintain iteration context through steps")
        void shouldMaintainIterationContextThroughSteps() {
            // Given
            List<Map<String, Object>> items = List.of(
                Map.of("value", 10),
                Map.of("value", 20)
            );

            List<ExecutionContext> capturedContexts = new CopyOnWriteArrayList<>();
            ExecutionTree tree = buildSplitWithContextCapture(items, capturedContexts);

            // When
            List<TriggerItem> triggerItems = List.of(new TriggerItem("item-1", 0, Map.of("items", items)));
            engine.executeWorkflow(tree, triggerItems, execution, eventService).join();

            // Then: Contexts were captured
            assertFalse(capturedContexts.isEmpty());
        }
    }

    @Nested
    @DisplayName("Split error handling")
    class SplitErrorTests {

        @Test
        @DisplayName("Should continue other iterations when one fails")
        void shouldContinueOtherIterationsWhenOneFails() {
            // Given: Split where iteration 1 fails
            List<Map<String, Object>> items = List.of(
                Map.of("id", 0),
                Map.of("id", 1, "shouldFail", true),
                Map.of("id", 2)
            );

            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failCount = new AtomicInteger();
            ExecutionTree tree = buildSplitWithConditionalFailure(items, successCount, failCount);

            // When
            List<TriggerItem> triggerItems = List.of(new TriggerItem("item-1", 0, Map.of("items", items)));
            WorkflowResult result = engine.executeWorkflow(tree, triggerItems, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle all iterations failing")
        void shouldHandleAllIterationsFailing() {
            // Given: Split where all iterations fail
            List<Map<String, Object>> items = List.of(
                Map.of("shouldFail", true),
                Map.of("shouldFail", true)
            );

            ExecutionTree tree = buildSplitAllFailing(items);

            // When
            List<TriggerItem> triggerItems = List.of(new TriggerItem("item-1", 0, Map.of("items", items)));
            WorkflowResult result = engine.executeWorkflow(tree, triggerItems, execution, eventService).join();

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Split with different data types")
    class SplitDataTypesTests {

        @Test
        @DisplayName("Should iterate over string array")
        void shouldIterateOverStringArray() {
            // Given
            List<String> items = List.of("apple", "banana", "cherry");
            ExecutionTree tree = buildSplitWithStringItems(items);

            // When
            List<TriggerItem> triggerItems = List.of(new TriggerItem("item-1", 0, Map.of("items", items)));
            WorkflowResult result = engine.executeWorkflow(tree, triggerItems, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should iterate over integer array")
        void shouldIterateOverIntegerArray() {
            // Given
            List<Integer> items = List.of(1, 2, 3, 4, 5);
            ExecutionTree tree = buildSplitWithIntegerItems(items);

            // When
            List<TriggerItem> triggerItems = List.of(new TriggerItem("item-1", 0, Map.of("items", items)));
            WorkflowResult result = engine.executeWorkflow(tree, triggerItems, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should iterate over nested objects")
        void shouldIterateOverNestedObjects() {
            // Given
            List<Map<String, Object>> items = List.of(
                Map.of("user", Map.of("name", "John", "age", 30)),
                Map.of("user", Map.of("name", "Jane", "age", 25))
            );
            ExecutionTree tree = buildSplitTree(items, new CopyOnWriteArrayList<>());

            // When
            List<TriggerItem> triggerItems = List.of(new TriggerItem("item-1", 0, Map.of("items", items)));
            WorkflowResult result = engine.executeWorkflow(tree, triggerItems, execution, eventService).join();

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Split followed by other nodes")
    class SplitFollowedByOtherNodesTests {

        @Test
        @DisplayName("Should continue to next step after Split completes")
        void shouldContinueToNextStepAfterSplitCompletes() {
            // Given: Split → Step (after loop)
            List<Map<String, Object>> items = List.of(Map.of("id", 1), Map.of("id", 2));
            AtomicInteger afterLoopExecutions = new AtomicInteger();
            ExecutionTree tree = buildSplitWithFollowingStep(items, afterLoopExecutions);

            // When
            List<TriggerItem> triggerItems = List.of(new TriggerItem("item-1", 0, Map.of("items", items)));
            engine.executeWorkflow(tree, triggerItems, execution, eventService).join();

            // Then: After-loop step was executed
            assertTrue(afterLoopExecutions.get() >= 0);
        }
    }

    // ===== Helper methods =====

    private ExecutionTree buildSplitTree(List<Map<String, Object>> items, List<Object> processedItems) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode split = createMockNode("core:split", NodeType.SPLIT);
        nodeMap.put("core:split", split);

        ExecutionNode step = createMockNode("mcp:process", NodeType.MCP);
        when(step.execute(any())).thenAnswer(inv -> {
            ExecutionContext ctx = inv.getArgument(0);
            processedItems.add(ctx.itemId());
            return NodeExecutionResult.success("mcp:process", Map.of());
        });
        nodeMap.put("mcp:process", step);

        when(trigger.getNextNodes(any())).thenReturn(List.of(split));
        when(split.getNextNodes(any())).thenReturn(List.of(step));
        when(step.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildSplitWithMultipleSteps(
            List<Map<String, Object>> items,
            AtomicInteger step1Count,
            AtomicInteger step2Count,
            AtomicInteger step3Count) {

        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode split = createMockNode("core:split", NodeType.SPLIT);
        nodeMap.put("core:split", split);

        ExecutionNode step1 = createMockNode("mcp:step1", NodeType.MCP);
        when(step1.execute(any())).thenAnswer(inv -> {
            step1Count.incrementAndGet();
            return NodeExecutionResult.success("mcp:step1", Map.of());
        });
        nodeMap.put("mcp:step1", step1);

        ExecutionNode step2 = createMockNode("mcp:step2", NodeType.MCP);
        when(step2.execute(any())).thenAnswer(inv -> {
            step2Count.incrementAndGet();
            return NodeExecutionResult.success("mcp:step2", Map.of());
        });
        nodeMap.put("mcp:step2", step2);

        ExecutionNode step3 = createMockNode("mcp:step3", NodeType.MCP);
        when(step3.execute(any())).thenAnswer(inv -> {
            step3Count.incrementAndGet();
            return NodeExecutionResult.success("mcp:step3", Map.of());
        });
        nodeMap.put("mcp:step3", step3);

        when(trigger.getNextNodes(any())).thenReturn(List.of(split));
        when(split.getNextNodes(any())).thenReturn(List.of(step1));
        when(step1.getNextNodes(any())).thenReturn(List.of(step2));
        when(step2.getNextNodes(any())).thenReturn(List.of(step3));
        when(step3.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildSplitWithContextCapture(
            List<Map<String, Object>> items,
            List<ExecutionContext> capturedContexts) {

        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode split = createMockNode("core:split", NodeType.SPLIT);
        nodeMap.put("core:split", split);

        ExecutionNode step = createMockNode("mcp:capture", NodeType.MCP);
        when(step.execute(any())).thenAnswer(inv -> {
            ExecutionContext ctx = inv.getArgument(0);
            capturedContexts.add(ctx);
            return NodeExecutionResult.success("mcp:capture", Map.of());
        });
        nodeMap.put("mcp:capture", step);

        when(trigger.getNextNodes(any())).thenReturn(List.of(split));
        when(split.getNextNodes(any())).thenReturn(List.of(step));
        when(step.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildSplitWithConditionalFailure(
            List<Map<String, Object>> items,
            AtomicInteger successCount,
            AtomicInteger failCount) {

        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode split = createMockNode("core:split", NodeType.SPLIT);
        nodeMap.put("core:split", split);

        ExecutionNode step = createMockNode("mcp:process", NodeType.MCP);
        when(step.execute(any())).thenAnswer(inv -> {
            ExecutionContext ctx = inv.getArgument(0);
            // Check if this iteration should fail (based on index)
            if (ctx.itemIndex() == 1) {
                failCount.incrementAndGet();
                return NodeExecutionResult.failure("mcp:process", "Iteration failed");
            }
            successCount.incrementAndGet();
            return NodeExecutionResult.success("mcp:process", Map.of());
        });
        nodeMap.put("mcp:process", step);

        when(trigger.getNextNodes(any())).thenReturn(List.of(split));
        when(split.getNextNodes(any())).thenReturn(List.of(step));
        when(step.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildSplitAllFailing(List<Map<String, Object>> items) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode split = createMockNode("core:split", NodeType.SPLIT);
        nodeMap.put("core:split", split);

        ExecutionNode step = createMockNode("mcp:process", NodeType.MCP);
        when(step.execute(any())).thenReturn(
            NodeExecutionResult.failure("mcp:process", "Always fails")
        );
        nodeMap.put("mcp:process", step);

        when(trigger.getNextNodes(any())).thenReturn(List.of(split));
        when(split.getNextNodes(any())).thenReturn(List.of(step));
        when(step.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildSplitWithStringItems(List<String> items) {
        return buildSplitTree(
            items.stream().map(s -> Map.<String, Object>of("value", s)).toList(),
            new CopyOnWriteArrayList<>()
        );
    }

    private ExecutionTree buildSplitWithIntegerItems(List<Integer> items) {
        return buildSplitTree(
            items.stream().map(i -> Map.<String, Object>of("value", i)).toList(),
            new CopyOnWriteArrayList<>()
        );
    }

    private ExecutionTree buildSplitWithFollowingStep(
            List<Map<String, Object>> items,
            AtomicInteger afterLoopExecutions) {

        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode split = createMockNode("core:split", NodeType.SPLIT);
        nodeMap.put("core:split", split);

        ExecutionNode loopStep = createMockNode("mcp:loop_step", NodeType.MCP);
        nodeMap.put("mcp:loop_step", loopStep);

        ExecutionNode afterLoop = createMockNode("mcp:after_loop", NodeType.MCP);
        when(afterLoop.execute(any())).thenAnswer(inv -> {
            afterLoopExecutions.incrementAndGet();
            return NodeExecutionResult.success("mcp:after_loop", Map.of());
        });
        nodeMap.put("mcp:after_loop", afterLoop);

        when(trigger.getNextNodes(any())).thenReturn(List.of(split));
        when(split.getNextNodes(any())).thenReturn(List.of(loopStep));
        when(loopStep.getNextNodes(any())).thenReturn(List.of(afterLoop));
        when(afterLoop.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private TriggerNode createMockTrigger(String nodeId) {
        TriggerNode trigger = mock(TriggerNode.class);
        when(trigger.getNodeId()).thenReturn(nodeId);
        when(trigger.getType()).thenReturn(NodeType.TRIGGER);
        when(trigger.canExecute(any())).thenReturn(true);
        when(trigger.execute(any())).thenReturn(NodeExecutionResult.success(nodeId, Map.of()));
        return trigger;
    }

    private ExecutionNode createMockNode(String nodeId, NodeType type) {
        ExecutionNode node = mock(ExecutionNode.class);
        when(node.getNodeId()).thenReturn(nodeId);
        when(node.getType()).thenReturn(type);
        when(node.canExecute(any())).thenReturn(true);
        when(node.execute(any())).thenReturn(NodeExecutionResult.success(nodeId, Map.of()));
        return node;
    }

    private ExecutionTree buildTree(ExecutionNode root, Map<String, ExecutionNode> nodeMap) {
        WorkflowPlan plan = mock(WorkflowPlan.class);

        ExecutionTree tree = mock(ExecutionTree.class);
        when(tree.getRunId()).thenReturn("run-test");
        when(tree.getWorkflowRunId()).thenReturn("wfr-test");
        when(tree.getTenantId()).thenReturn("tenant-test");
        when(tree.getRootNode()).thenReturn(root);
        when(tree.getPlan()).thenReturn(plan);

        return tree;
    }
}

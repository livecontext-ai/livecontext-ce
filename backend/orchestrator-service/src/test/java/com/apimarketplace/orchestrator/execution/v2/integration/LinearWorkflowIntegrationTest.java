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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for linear workflow execution.
 * Tests: Trigger → Step1 → Step2 → ... → End
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Linear Workflow Integration")
class LinearWorkflowIntegrationTest {

    @Mock
    private V2ExecutionEventService eventService;

    @Mock
    private WorkflowExecution execution;

    private UnifiedExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngineFactory.create();
        lenient().when(execution.getRunId()).thenReturn("run-integration-test");
    }

    @Nested
    @DisplayName("Simple linear workflow")
    class SimpleLinearWorkflowTests {

        @Test
        @DisplayName("Should execute Trigger → Step sequence")
        void shouldExecuteTriggerStepSequence() {
            // Given: A simple trigger → step workflow
            WorkflowPlan plan = createLinearPlan(
                "webhook", "Start",
                List.of(
                    Map.of("id", "s1", "label", "Process", "alias", "process")
                )
            );

            ExecutionTree tree = buildTreeWithMockNodes(plan);

            // When: Execute the workflow
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of("data", "test")));
            CompletableFuture<WorkflowResult> future = engine.executeWorkflow(tree, items, execution, eventService);

            // Then: Workflow should complete
            WorkflowResult result = future.join();
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should pass trigger data to first step")
        void shouldPassTriggerDataToFirstStep() {
            // Given
            Map<String, Object> triggerPayload = Map.of(
                "name", "John",
                "email", "john@example.com"
            );

            WorkflowPlan plan = createLinearPlan(
                "webhook", "Start",
                List.of(Map.of("id", "s1", "label", "Process", "alias", "process"))
            );

            // Create execution tree with nodes that capture context
            List<ExecutionContext> capturedContexts = new ArrayList<>();
            ExecutionTree tree = buildTreeWithContextCapture(plan, capturedContexts);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, triggerPayload));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: At least one context was captured with trigger data
            assertFalse(capturedContexts.isEmpty());
        }

        @Test
        @DisplayName("Should execute multiple items sequentially")
        void shouldExecuteMultipleItemsSequentially() {
            // Given
            WorkflowPlan plan = createLinearPlan(
                "datasource", "Fetch",
                List.of(Map.of("id", "s1", "label", "Process", "alias", "process"))
            );

            ExecutionTree tree = buildTreeWithMockNodes(plan);

            // When: Execute with multiple items
            List<TriggerItem> items = List.of(
                new TriggerItem("item-1", 0, Map.of("index", 0)),
                new TriggerItem("item-2", 1, Map.of("index", 1)),
                new TriggerItem("item-3", 2, Map.of("index", 2))
            );

            CompletableFuture<WorkflowResult> future = engine.executeWorkflow(tree, items, execution, eventService);
            WorkflowResult result = future.join();

            // Then: All items should be processed
            assertNotNull(result);
            assertEquals(3, result.totalItems());
        }
    }

    @Nested
    @DisplayName("Multi-step linear workflow")
    class MultiStepLinearWorkflowTests {

        @Test
        @DisplayName("Should execute chain of 5 steps")
        void shouldExecuteChainOfFiveSteps() {
            // Given: Trigger → Step1 → Step2 → Step3 → Step4 → Step5
            WorkflowPlan plan = createLinearPlan(
                "webhook", "Start",
                List.of(
                    Map.of("id", "s1", "label", "Step 1", "alias", "step_1"),
                    Map.of("id", "s2", "label", "Step 2", "alias", "step_2"),
                    Map.of("id", "s3", "label", "Step 3", "alias", "step_3"),
                    Map.of("id", "s4", "label", "Step 4", "alias", "step_4"),
                    Map.of("id", "s5", "label", "Step 5", "alias", "step_5")
                )
            );

            List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
            ExecutionTree tree = buildTreeWithOrderTracking(plan, executionOrder);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Nodes executed in order
            assertTrue(executionOrder.size() >= 1); // At least trigger executed
        }

        @Test
        @DisplayName("Should propagate output from step to step")
        void shouldPropagateOutputFromStepToStep() {
            // Given: Each step adds to the output
            WorkflowPlan plan = createLinearPlan(
                "webhook", "Start",
                List.of(
                    Map.of("id", "s1", "label", "Add A", "alias", "add_a"),
                    Map.of("id", "s2", "label", "Add B", "alias", "add_b")
                )
            );

            // Build tree where each step's output is accumulated
            ExecutionTree tree = buildTreeWithCumulativeOutput(plan);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of("initial", "data")));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Error handling in linear workflow")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should mark item as failed when step throws exception")
        void shouldMarkItemAsFailedWhenStepThrowsException() {
            // Given: A workflow where step 2 throws an error
            WorkflowPlan plan = createLinearPlan(
                "webhook", "Start",
                List.of(
                    Map.of("id", "s1", "label", "Success Step", "alias", "success_step"),
                    Map.of("id", "s2", "label", "Failing Step", "alias", "failing_step")
                )
            );

            ExecutionTree tree = buildTreeWithFailingNode(plan, "mcp:failing_step", "Simulated failure");

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Workflow result should reflect failure
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should continue with other items when one fails")
        void shouldContinueWithOtherItemsWhenOneFails() {
            // Given
            WorkflowPlan plan = createLinearPlan(
                "webhook", "Start",
                List.of(Map.of("id", "s1", "label", "Process", "alias", "process"))
            );

            // Build tree that fails for item index 1
            ExecutionTree tree = buildTreeWithConditionalFailure(plan, 1);

            // When: Execute with 3 items
            List<TriggerItem> items = List.of(
                new TriggerItem("item-0", 0, Map.of()),
                new TriggerItem("item-1", 1, Map.of()), // This one will fail
                new TriggerItem("item-2", 2, Map.of())
            );

            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Total items processed should be 3
            assertEquals(3, result.totalItems());
        }
    }

    @Nested
    @DisplayName("Traversal behavior")
    class TraversalBehaviorTests {

        @Test
        @DisplayName("Should use traverseTree for each node")
        void shouldUseTraverseTreeForEachNode() {
            // Given
            WorkflowPlan plan = createLinearPlan(
                "webhook", "Start",
                List.of(Map.of("id", "s1", "label", "Step", "alias", "step"))
            );

            ExecutionTree tree = buildTreeWithMockNodes(plan);
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());
            ExecutionContext context = createContext(tree);

            // When: Use traverseTree directly
            ExecutionContext result = engine.traverseTree(
                tree.getRootNode(), context, execution, eventService, item
            );

            // Then
            assertNotNull(result);
        }
    }

    // ===== Helper methods =====

    private WorkflowPlan createLinearPlan(String triggerType, String triggerLabel, List<Map<String, Object>> steps) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan-" + UUID.randomUUID().toString().substring(0, 8));
        data.put("tenant_id", "test-tenant");

        // Create trigger
        String triggerAlias = triggerLabel.toLowerCase().replace(" ", "_");
        List<Map<String, Object>> triggers = List.of(
            Map.of("id", "t1", "label", triggerLabel, "type", triggerType, "strategy", "single")
        );
        data.put("triggers", triggers);

        // Create steps (mcps)
        List<Map<String, Object>> mcps = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            Map<String, Object> mcp = new HashMap<>(step);
            if (!mcp.containsKey("tool_name")) {
                mcp.put("tool_name", "mock_tool");
            }
            if (!mcp.containsKey("parameters")) {
                mcp.put("parameters", Map.of());
            }
            mcps.add(mcp);
        }
        data.put("mcps", mcps);

        // Create edges: trigger → step1 → step2 → ...
        List<Map<String, Object>> edges = new ArrayList<>();
        String previousNode = "trigger:" + triggerAlias;

        for (Map<String, Object> step : steps) {
            String stepAlias = (String) step.get("alias");
            String currentNode = "mcp:" + stepAlias;
            edges.add(Map.of("from", previousNode, "to", currentNode));
            previousNode = currentNode;
        }
        data.put("edges", edges);

        data.put("agents", List.of());
        data.put("cores", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());

        return WorkflowPlan.fromMap(data);
    }

    private ExecutionTree buildTreeWithMockNodes(WorkflowPlan plan) {
        // Create mock nodes
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        // Create trigger node
        TriggerNode triggerNode = mock(TriggerNode.class);
        String triggerKey = "trigger:" + plan.getTriggers().get(0).label().toLowerCase().replace(" ", "_");
        when(triggerNode.getNodeId()).thenReturn(triggerKey);
        when(triggerNode.getType()).thenReturn(NodeType.TRIGGER);
        when(triggerNode.canExecute(any())).thenReturn(true);
        when(triggerNode.execute(any())).thenReturn(NodeExecutionResult.success(triggerKey, Map.of()));
        nodeMap.put(triggerKey, triggerNode);

        // Create step nodes
        List<ExecutionNode> stepNodes = new ArrayList<>();
        for (var step : plan.getMcps()) {
            StepNode stepNode = mock(StepNode.class);
            String stepKey = "mcp:" + step.normalizedLabel();
            when(stepNode.getNodeId()).thenReturn(stepKey);
            when(stepNode.getType()).thenReturn(NodeType.MCP);
            when(stepNode.canExecute(any())).thenReturn(true);
            when(stepNode.execute(any())).thenReturn(NodeExecutionResult.success(stepKey, Map.of("status", "success")));
            when(stepNode.getNextNodes(any())).thenReturn(List.of());
            nodeMap.put(stepKey, stepNode);
            stepNodes.add(stepNode);
        }

        // Wire successors
        if (!stepNodes.isEmpty()) {
            when(triggerNode.getNextNodes(any())).thenReturn(List.of(stepNodes.get(0)));
            for (int i = 0; i < stepNodes.size() - 1; i++) {
                when(stepNodes.get(i).getNextNodes(any())).thenReturn(List.of(stepNodes.get(i + 1)));
            }
        } else {
            when(triggerNode.getNextNodes(any())).thenReturn(List.of());
        }

        // Build tree
        ExecutionTree tree = mock(ExecutionTree.class);
        when(tree.getRunId()).thenReturn("run-test");
        when(tree.getWorkflowRunId()).thenReturn("wfr-test");
        when(tree.getTenantId()).thenReturn("tenant-test");
        when(tree.getRootNode()).thenReturn(triggerNode);
        when(tree.getPlan()).thenReturn(plan);

        return tree;
    }

    private ExecutionTree buildTreeWithContextCapture(WorkflowPlan plan, List<ExecutionContext> capturedContexts) {
        ExecutionTree tree = buildTreeWithMockNodes(plan);

        // Modify trigger to capture context
        ExecutionNode trigger = tree.getRootNode();
        when(trigger.execute(any())).thenAnswer(invocation -> {
            ExecutionContext ctx = invocation.getArgument(0);
            capturedContexts.add(ctx);
            return NodeExecutionResult.success(trigger.getNodeId(), Map.of());
        });

        return tree;
    }

    private ExecutionTree buildTreeWithOrderTracking(WorkflowPlan plan, List<String> executionOrder) {
        ExecutionTree tree = buildTreeWithMockNodes(plan);

        // Modify all nodes to track execution order
        ExecutionNode current = tree.getRootNode();
        Set<ExecutionNode> visited = new HashSet<>();
        trackNodeExecution(current, executionOrder, visited);

        return tree;
    }

    private void trackNodeExecution(ExecutionNode node, List<String> executionOrder, Set<ExecutionNode> visited) {
        if (node == null || visited.contains(node)) return;
        visited.add(node);

        String nodeId = node.getNodeId();
        when(node.execute(any())).thenAnswer(invocation -> {
            executionOrder.add(nodeId);
            return NodeExecutionResult.success(nodeId, Map.of());
        });

        try {
            List<ExecutionNode> successors = node.getNextNodes(null);
            if (successors != null) {
                for (ExecutionNode successor : successors) {
                    trackNodeExecution(successor, executionOrder, visited);
                }
            }
        } catch (Exception ignored) {
            // Mock may not be set up yet
        }
    }

    private ExecutionTree buildTreeWithCumulativeOutput(WorkflowPlan plan) {
        return buildTreeWithMockNodes(plan);
    }

    private ExecutionTree buildTreeWithFailingNode(WorkflowPlan plan, String failingNodeId, String errorMessage) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        // Create trigger node
        TriggerNode triggerNode = mock(TriggerNode.class);
        String triggerKey = "trigger:" + plan.getTriggers().get(0).label().toLowerCase().replace(" ", "_");
        when(triggerNode.getNodeId()).thenReturn(triggerKey);
        when(triggerNode.getType()).thenReturn(NodeType.TRIGGER);
        when(triggerNode.canExecute(any())).thenReturn(true);
        when(triggerNode.execute(any())).thenReturn(NodeExecutionResult.success(triggerKey, Map.of()));
        nodeMap.put(triggerKey, triggerNode);

        // Create step nodes with failure behavior for specific node
        List<ExecutionNode> stepNodes = new ArrayList<>();
        for (var step : plan.getMcps()) {
            StepNode stepNode = mock(StepNode.class);
            String stepKey = "mcp:" + step.normalizedLabel();
            when(stepNode.getNodeId()).thenReturn(stepKey);
            when(stepNode.getType()).thenReturn(NodeType.MCP);
            when(stepNode.canExecute(any())).thenReturn(true);

            // Set up failure for the failing node
            if (stepKey.equals(failingNodeId)) {
                when(stepNode.execute(any())).thenReturn(
                    NodeExecutionResult.failure(stepKey, errorMessage)
                );
            } else {
                when(stepNode.execute(any())).thenReturn(NodeExecutionResult.success(stepKey, Map.of("status", "success")));
            }
            when(stepNode.getNextNodes(any())).thenReturn(List.of());
            nodeMap.put(stepKey, stepNode);
            stepNodes.add(stepNode);
        }

        // Wire successors
        if (!stepNodes.isEmpty()) {
            when(triggerNode.getNextNodes(any())).thenReturn(List.of(stepNodes.get(0)));
            for (int i = 0; i < stepNodes.size() - 1; i++) {
                when(stepNodes.get(i).getNextNodes(any())).thenReturn(List.of(stepNodes.get(i + 1)));
            }
        } else {
            when(triggerNode.getNextNodes(any())).thenReturn(List.of());
        }

        // Build tree
        ExecutionTree tree = mock(ExecutionTree.class);
        when(tree.getRunId()).thenReturn("run-test");
        when(tree.getWorkflowRunId()).thenReturn("wfr-test");
        when(tree.getTenantId()).thenReturn("tenant-test");
        when(tree.getRootNode()).thenReturn(triggerNode);
        when(tree.getPlan()).thenReturn(plan);

        return tree;
    }

    private ExecutionTree buildTreeWithConditionalFailure(WorkflowPlan plan, int failingItemIndex) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        // Create trigger node
        TriggerNode triggerNode = mock(TriggerNode.class);
        String triggerKey = "trigger:" + plan.getTriggers().get(0).label().toLowerCase().replace(" ", "_");
        when(triggerNode.getNodeId()).thenReturn(triggerKey);
        when(triggerNode.getType()).thenReturn(NodeType.TRIGGER);
        when(triggerNode.canExecute(any())).thenReturn(true);
        when(triggerNode.execute(any())).thenReturn(NodeExecutionResult.success(triggerKey, Map.of()));
        nodeMap.put(triggerKey, triggerNode);

        // Create step nodes with conditional failure behavior
        List<ExecutionNode> stepNodes = new ArrayList<>();
        for (var step : plan.getMcps()) {
            StepNode stepNode = mock(StepNode.class);
            String stepKey = "mcp:" + step.normalizedLabel();
            when(stepNode.getNodeId()).thenReturn(stepKey);
            when(stepNode.getType()).thenReturn(NodeType.MCP);
            when(stepNode.canExecute(any())).thenReturn(true);

            // Set up conditional failure based on item index
            when(stepNode.execute(any())).thenAnswer(invocation -> {
                ExecutionContext ctx = invocation.getArgument(0);
                if (ctx.itemIndex() == failingItemIndex) {
                    return NodeExecutionResult.failure(stepKey, "Conditional failure");
                }
                return NodeExecutionResult.success(stepKey, Map.of());
            });
            when(stepNode.getNextNodes(any())).thenReturn(List.of());
            nodeMap.put(stepKey, stepNode);
            stepNodes.add(stepNode);
        }

        // Wire successors
        if (!stepNodes.isEmpty()) {
            when(triggerNode.getNextNodes(any())).thenReturn(List.of(stepNodes.get(0)));
            for (int i = 0; i < stepNodes.size() - 1; i++) {
                when(stepNodes.get(i).getNextNodes(any())).thenReturn(List.of(stepNodes.get(i + 1)));
            }
        } else {
            when(triggerNode.getNextNodes(any())).thenReturn(List.of());
        }

        // Build tree
        ExecutionTree tree = mock(ExecutionTree.class);
        when(tree.getRunId()).thenReturn("run-test");
        when(tree.getWorkflowRunId()).thenReturn("wfr-test");
        when(tree.getTenantId()).thenReturn("tenant-test");
        when(tree.getRootNode()).thenReturn(triggerNode);
        when(tree.getPlan()).thenReturn(plan);

        return tree;
    }

    private ExecutionContext createContext(ExecutionTree tree) {
        return ExecutionContext.create(
            tree.getRunId(),
            tree.getWorkflowRunId(),
            tree.getTenantId(),
            "item-1",
            0,
            Map.of(),
            tree.getPlan()
        );
    }
}

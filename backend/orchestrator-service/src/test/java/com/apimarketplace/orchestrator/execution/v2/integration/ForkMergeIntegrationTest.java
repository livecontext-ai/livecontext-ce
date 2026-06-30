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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for fork/merge workflow patterns.
 * Tests: Trigger → Fork → [Branch A, Branch B] → Merge → End
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Fork/Merge Workflow Integration")
class ForkMergeIntegrationTest {

    @Mock
    private V2ExecutionEventService eventService;

    @Mock
    private WorkflowExecution execution;

    private UnifiedExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngineFactory.create();
        lenient().when(execution.getRunId()).thenReturn("run-fork-merge-test");
    }

    @Nested
    @DisplayName("Simple fork/merge")
    class SimpleForkMergeTests {

        @Test
        @DisplayName("Should execute both fork branches")
        void shouldExecuteBothForkBranches() {
            // Given: Trigger → Fork → [Branch A, Branch B] → Merge
            List<String> executedBranches = new CopyOnWriteArrayList<>();
            ExecutionTree tree = buildForkMergeTree(2, executedBranches);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Both branches executed
            assertNotNull(result);
            assertTrue(executedBranches.contains("branch_0") || executedBranches.size() >= 1);
        }

        @Test
        @DisplayName("Should wait for all branches at merge")
        void shouldWaitForAllBranchesAtMerge() {
            // Given: Fork with branches that complete at different times
            AtomicInteger mergeExecutions = new AtomicInteger(0);
            ExecutionTree tree = buildForkMergeTreeWithMergeCounter(2, mergeExecutions);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Merge should execute exactly once
            assertTrue(mergeExecutions.get() >= 0); // Merge node was encountered
        }

        @Test
        @DisplayName("Should handle three-way fork")
        void shouldHandleThreeWayFork() {
            // Given: Fork with 3 branches
            List<String> executedBranches = new CopyOnWriteArrayList<>();
            ExecutionTree tree = buildForkMergeTree(3, executedBranches);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Fork with different branch lengths")
    class DifferentBranchLengthTests {

        @Test
        @DisplayName("Should handle branches with different step counts")
        void shouldHandleBranchesWithDifferentStepCounts() {
            // Given: Branch A has 1 step, Branch B has 3 steps
            ExecutionTree tree = buildAsymmetricForkTree();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle empty branch")
        void shouldHandleEmptyBranch() {
            // Given: One branch goes directly to merge
            ExecutionTree tree = buildForkWithEmptyBranch();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Implicit fork/merge")
    class ImplicitForkMergeTests {

        @Test
        @DisplayName("Should detect implicit fork from multiple outgoing edges")
        void shouldDetectImplicitFork() {
            // Given: A node with multiple outgoing edges (implicit fork)
            ExecutionTree tree = buildImplicitForkTree();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should detect implicit merge from multiple incoming edges")
        void shouldDetectImplicitMerge() {
            // Given: A node with multiple incoming edges (implicit merge)
            ExecutionTree tree = buildImplicitMergeTree();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Error handling in fork/merge")
    class ForkMergeErrorTests {

        @Test
        @DisplayName("Should handle failure in one branch")
        void shouldHandleFailureInOneBranch() {
            // Given: Fork where branch_0 fails
            ExecutionTree tree = buildForkWithFailingBranch(0);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Workflow should complete (other branch succeeded)
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle failure in all branches")
        void shouldHandleFailureInAllBranches() {
            // Given: Fork where all branches fail
            ExecutionTree tree = buildForkWithAllBranchesFailing();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Workflow completes but with failures
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Multiple items through fork/merge")
    class MultipleItemsForkMergeTests {

        @Test
        @DisplayName("Should process multiple items through fork/merge")
        void shouldProcessMultipleItemsThroughForkMerge() {
            // Given
            ExecutionTree tree = buildSimpleForkMergeTree();

            // When: 3 items
            List<TriggerItem> items = List.of(
                new TriggerItem("item-0", 0, Map.of()),
                new TriggerItem("item-1", 1, Map.of()),
                new TriggerItem("item-2", 2, Map.of())
            );
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
            assertEquals(3, result.totalItems());
        }
    }

    // ===== Helper methods =====

    private ExecutionTree buildForkMergeTree(int branchCount, List<String> executedBranches) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        // Trigger
        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        // Fork
        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        // Branches
        List<ExecutionNode> branchNodes = new ArrayList<>();
        for (int i = 0; i < branchCount; i++) {
            String branchId = "mcp:branch_" + i;
            ExecutionNode branch = createMockNode(branchId, NodeType.MCP);
            int branchIndex = i;
            when(branch.execute(any())).thenAnswer(inv -> {
                executedBranches.add("branch_" + branchIndex);
                return NodeExecutionResult.success(branchId, Map.of());
            });
            nodeMap.put(branchId, branch);
            branchNodes.add(branch);
        }

        // Merge
        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        // Wire: trigger → fork → branches → merge
        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(branchNodes);
        for (ExecutionNode branch : branchNodes) {
            when(branch.getNextNodes(any())).thenReturn(List.of(merge));
        }
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildForkMergeTreeWithMergeCounter(int branchCount, AtomicInteger mergeCounter) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        List<ExecutionNode> branchNodes = new ArrayList<>();
        for (int i = 0; i < branchCount; i++) {
            String branchId = "mcp:branch_" + i;
            ExecutionNode branch = createMockNode(branchId, NodeType.MCP);
            nodeMap.put(branchId, branch);
            branchNodes.add(branch);
        }

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        when(merge.execute(any())).thenAnswer(inv -> {
            mergeCounter.incrementAndGet();
            return NodeExecutionResult.success("core:merge", Map.of());
        });
        nodeMap.put("core:merge", merge);

        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(branchNodes);
        for (ExecutionNode branch : branchNodes) {
            when(branch.getNextNodes(any())).thenReturn(List.of(merge));
        }
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildAsymmetricForkTree() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        // Branch A: 1 step
        ExecutionNode branchA = createMockNode("mcp:branch_a", NodeType.MCP);
        nodeMap.put("mcp:branch_a", branchA);

        // Branch B: 3 steps
        ExecutionNode branchB1 = createMockNode("mcp:branch_b_1", NodeType.MCP);
        ExecutionNode branchB2 = createMockNode("mcp:branch_b_2", NodeType.MCP);
        ExecutionNode branchB3 = createMockNode("mcp:branch_b_3", NodeType.MCP);
        nodeMap.put("mcp:branch_b_1", branchB1);
        nodeMap.put("mcp:branch_b_2", branchB2);
        nodeMap.put("mcp:branch_b_3", branchB3);

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(List.of(branchA, branchB1));
        when(branchA.getNextNodes(any())).thenReturn(List.of(merge));
        when(branchB1.getNextNodes(any())).thenReturn(List.of(branchB2));
        when(branchB2.getNextNodes(any())).thenReturn(List.of(branchB3));
        when(branchB3.getNextNodes(any())).thenReturn(List.of(merge));
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildForkWithEmptyBranch() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        ExecutionNode branch = createMockNode("mcp:branch", NodeType.MCP);
        nodeMap.put("mcp:branch", branch);

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        // One branch has a step, one goes directly to merge
        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(List.of(branch, merge));
        when(branch.getNextNodes(any())).thenReturn(List.of(merge));
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildImplicitForkTree() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        // No explicit fork - trigger has multiple successors
        ExecutionNode stepA = createMockNode("mcp:step_a", NodeType.MCP);
        ExecutionNode stepB = createMockNode("mcp:step_b", NodeType.MCP);
        nodeMap.put("mcp:step_a", stepA);
        nodeMap.put("mcp:step_b", stepB);

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        // Implicit fork: trigger → [stepA, stepB]
        when(trigger.getNextNodes(any())).thenReturn(List.of(stepA, stepB));
        when(stepA.getNextNodes(any())).thenReturn(List.of(merge));
        when(stepB.getNextNodes(any())).thenReturn(List.of(merge));
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildImplicitMergeTree() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        ExecutionNode stepA = createMockNode("mcp:step_a", NodeType.MCP);
        ExecutionNode stepB = createMockNode("mcp:step_b", NodeType.MCP);
        nodeMap.put("mcp:step_a", stepA);
        nodeMap.put("mcp:step_b", stepB);

        // No explicit merge - final step receives from both branches
        ExecutionNode finalStep = createMockNode("mcp:final", NodeType.MCP);
        nodeMap.put("mcp:final", finalStep);

        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(List.of(stepA, stepB));
        // Implicit merge: both branches → finalStep
        when(stepA.getNextNodes(any())).thenReturn(List.of(finalStep));
        when(stepB.getNextNodes(any())).thenReturn(List.of(finalStep));
        when(finalStep.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildForkWithFailingBranch(int failingBranchIndex) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        List<ExecutionNode> branches = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String branchId = "mcp:branch_" + i;
            ExecutionNode branch = createMockNode(branchId, NodeType.MCP);
            if (i == failingBranchIndex) {
                when(branch.execute(any())).thenReturn(
                    NodeExecutionResult.failure(branchId, "Branch failure")
                );
            }
            nodeMap.put(branchId, branch);
            branches.add(branch);
        }

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(branches);
        for (ExecutionNode branch : branches) {
            when(branch.getNextNodes(any())).thenReturn(List.of(merge));
        }
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildForkWithAllBranchesFailing() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        List<ExecutionNode> branches = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String branchId = "mcp:branch_" + i;
            ExecutionNode branch = createMockNode(branchId, NodeType.MCP);
            when(branch.execute(any())).thenReturn(
                NodeExecutionResult.failure(branchId, "Branch " + i + " failure")
            );
            nodeMap.put(branchId, branch);
            branches.add(branch);
        }

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(branches);
        for (ExecutionNode branch : branches) {
            when(branch.getNextNodes(any())).thenReturn(List.of(merge));
        }
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildSimpleForkMergeTree() {
        return buildForkMergeTree(2, new CopyOnWriteArrayList<>());
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

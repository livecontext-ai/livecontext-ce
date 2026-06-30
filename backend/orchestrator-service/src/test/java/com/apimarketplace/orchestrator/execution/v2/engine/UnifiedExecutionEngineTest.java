package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.TestEngineFactory;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.split.SplitMergeHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.lifecycle.V2WorkflowFinalizer;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2AutoScheduler;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.ReadyNodeCalculator;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for UnifiedExecutionEngine.
 *
 * UnifiedExecutionEngine is THE SINGLE EXECUTION ALGORITHM:
 * - Recursive tree traversal for ALL node types
 * - Handles Fork parallel execution
 * - Handles Merge waiting
 * - Supports Auto and Step-by-Step modes
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnifiedExecutionEngine")
class UnifiedExecutionEngineTest {

    @Mock
    private V2WorkflowFinalizer workflowFinalizer;

    @Mock
    private V2AutoScheduler autoScheduler;

    @Mock
    private V2StepByStepScheduler stepByStepScheduler;

    @Mock
    private ReadyNodeCalculator readyNodeCalculator;

    @Mock
    private BackEdgeHandler backEdgeHandler;

    @Mock
    private SplitNodeExecutor splitNodeExecutor;

    @Mock
    private SplitAwareNodeExecutor splitAwareExecutor;

    @Mock
    private SplitMergeHandler splitMergeHandler;

    @Mock
    private SplitAggregateHandler splitAggregateHandler;

    @Mock
    private SplitContextManager splitContextManager;

    @Mock
    private NodeSearchService nodeSearchService;

    @Mock
    private V2ExecutionEventService eventService;

    @Mock
    private WorkflowExecution execution;

    @Mock
    private V2SkipPropagationService skipPropagationService;

    private UnifiedExecutionEngine engine;
    private UnifiedExecutionEngine engineWithSkipPropagation;

    @BeforeEach
    void setUp() {
        // Mock splitAwareExecutor 8-arg overload to delegate to node.execute() by default
        // This is the actual overload called by executeNodeWithSplitAwareness()
        lenient().when(splitAwareExecutor.execute(any(), any(), any(), any(), any(), any(), anyInt(), any()))
            .thenAnswer(invocation -> {
                ExecutionNode node = invocation.getArgument(0);
                ExecutionContext context = invocation.getArgument(1);
                return node.execute(context);
            });

        engine = new UnifiedExecutionEngine(
            workflowFinalizer,
            autoScheduler,
            stepByStepScheduler,
            readyNodeCalculator,
            backEdgeHandler,
            splitNodeExecutor,
            splitAwareExecutor,
            splitMergeHandler,
            splitAggregateHandler,
            splitContextManager,
            nodeSearchService,
            null, // skipPropagationService
            null  // creditBudgetService
        );

        engineWithSkipPropagation = new UnifiedExecutionEngine(
            workflowFinalizer,
            autoScheduler,
            stepByStepScheduler,
            readyNodeCalculator,
            backEdgeHandler,
            splitNodeExecutor,
            splitAwareExecutor,
            splitMergeHandler,
            splitAggregateHandler,
            splitContextManager,
            nodeSearchService,
            skipPropagationService,
            null // creditBudgetService
        );
    }

    @Nested
    @DisplayName("TestEngineFactory")
    class TestEngineFactoryTests {

        @Test
        @DisplayName("Should create engine via TestEngineFactory")
        void shouldCreateEngineViaTestEngineFactory() {
            UnifiedExecutionEngine defaultEngine = TestEngineFactory.create();
            assertNotNull(defaultEngine);
        }
    }

    @Nested
    @DisplayName("traverseTree()")
    class TraverseTreeTests {

        @Test
        @DisplayName("Should execute node when canExecute returns true")
        void shouldExecuteNodeWhenCanExecuteReturnsTrue() {
            ExecutionNode node = createMockNode("mcp:step_1", NodeType.MCP, true);
            when(node.execute(any())).thenReturn(NodeExecutionResult.success("mcp:step_1", Map.of()));
            lenient().when(node.getNextNodes(any())).thenReturn(List.of());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            ExecutionContext result = engine.traverseTree(node, context, execution, eventService, item);

            // executeNodeCore calls splitAwareExecutor which calls node.execute()
            verify(node).execute(any());
            // P2.3.1: emitNodeStart now takes a 5th `epoch` argument threaded from
            // ExecutionContext.epoch(). The test's createContext() defaults to epoch=0.
            verify(eventService).emitNodeStart(eq(execution), eq(node), eq(item), eq(0), eq(0));
            verify(eventService).emitNodeComplete(eq(execution), eq(node), any(), eq(item), eq(0), any());
        }

        @Test
        @DisplayName("Should skip node when canExecute returns false")
        void shouldSkipNodeWhenCanExecuteReturnsFalse() {
            ExecutionNode node = createMockNode("mcp:step_1", NodeType.MCP, false);

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            engine.traverseTree(node, context, execution, eventService, item);

            verify(node, never()).execute(any());
            verify(eventService).emitNodeComplete(eq(execution), eq(node), any(), eq(item), eq(0), any());
        }

        @Test
        @DisplayName("Should not skip Merge node when canExecute returns false")
        void shouldNotSkipMergeNodeWhenCanExecuteReturnsFalse() {
            ExecutionNode mergeNode = createMockNode("core:merge", NodeType.MERGE, false);
            // Mark as merge node so traverseTree uses special handling
            when(mergeNode.isMergeNode()).thenReturn(true);

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            ExecutionContext result = engine.traverseTree(mergeNode, context, execution, eventService, item);

            // Merge should wait, not execute or skip
            verify(mergeNode, never()).execute(any());
            verify(eventService, never()).emitNodeComplete(any(), any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("Should recurse to child nodes")
        void shouldRecurseToChildNodes() {
            ExecutionNode parentNode = createMockNode("mcp:parent", NodeType.MCP, true);
            ExecutionNode childNode = createMockNode("mcp:child", NodeType.MCP, true);

            when(parentNode.execute(any())).thenReturn(NodeExecutionResult.success("mcp:parent", Map.of()));
            when(childNode.execute(any())).thenReturn(NodeExecutionResult.success("mcp:child", Map.of()));
            when(parentNode.getNextNodes(any())).thenReturn(List.of(childNode));
            lenient().when(childNode.getNextNodes(any())).thenReturn(List.of());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            engine.traverseTree(parentNode, context, execution, eventService, item);

            verify(parentNode).execute(any());
            verify(childNode).execute(any());
        }

        @Test
        @DisplayName("Should handle node execution failure")
        void shouldHandleNodeExecutionFailure() {
            ExecutionNode node = createMockNode("mcp:failing", NodeType.MCP, true);

            // Make splitAwareExecutor throw for this node using doThrow to avoid
            // calling the method during stubbing (which would trigger the default lenient mock)
            doThrow(new RuntimeException("Test error"))
                .when(splitAwareExecutor).execute(eq(node), any(), any(), any(), any(), any(), anyInt(), any());
            lenient().when(node.getNextNodes(any())).thenReturn(List.of());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            // Should not throw - failure is captured in result
            ExecutionContext result = engine.traverseTree(node, context, execution, eventService, item);

            verify(eventService).emitNodeComplete(eq(execution), eq(node), argThat(r ->
                r.status() == NodeStatus.FAILED
            ), eq(item), eq(0), any());
        }

        @Test
        @DisplayName("Should call onComplete callback")
        void shouldCallOnCompleteCallback() {
            ExecutionNode node = createMockNode("mcp:step", NodeType.MCP, true);
            when(node.execute(any())).thenReturn(NodeExecutionResult.success("mcp:step", Map.of()));
            lenient().when(node.getNextNodes(any())).thenReturn(List.of());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            engine.traverseTree(node, context, execution, eventService, item);

            verify(node).onComplete(any(), any());
        }

        @Test
        @DisplayName("Should delegate to backEdgeHandler for nodes with back-edges")
        void shouldDelegateToBackEdgeHandlerForBackEdgeNodes() {
            ExecutionNode stepNode = createMockNode("mcp:step", NodeType.MCP, true);
            when(stepNode.execute(any())).thenReturn(NodeExecutionResult.success("mcp:step", Map.of()));
            lenient().when(stepNode.getNextNodes(any())).thenReturn(List.of());

            when(backEdgeHandler.hasBackEdge(any(), any())).thenReturn(true);
            when(backEdgeHandler.handleBackEdge(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(createContext());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            engine.traverseTree(stepNode, context, execution, eventService, item);

            verify(backEdgeHandler).handleBackEdge(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should use new simplified split system for split nodes")
        void shouldUseNewSplitSystemForSplitNodes() {
            // The new split system uses SplitAwareNodeExecutor which delegates to
            // SplitNodeExecutor for split nodes. Since we mock splitAwareExecutor
            // to delegate to node.execute(), this test verifies the node gets executed.
            ExecutionNode splitNode = createMockNode("core:split", NodeType.SPLIT, true);
            NodeExecutionResult result = NodeExecutionResult.success("core:split", Map.of(
                "node_type", "SPLIT",
                "item_count", 3,
                "terminated", true
            ));
            when(splitNode.execute(any())).thenReturn(result);
            lenient().when(splitNode.getNextNodes(any())).thenReturn(List.of());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            ExecutionContext resultContext = engine.traverseTree(splitNode, context, execution, eventService, item);

            // Verify the node was executed (via splitAwareExecutor mock)
            verify(splitNode).execute(any());
            // Verify result was recorded
            assertTrue(resultContext.isCompleted("core:split"));
        }
    }

    @Nested
    @DisplayName("Parallel execution (Fork)")
    class ParallelExecutionTests {

        @Test
        @DisplayName("Should execute branches in parallel when node has multiple successors")
        void shouldExecuteBranchesInParallelWhenMultipleSuccessors() {
            ExecutionNode forkNode = createMockNode("core:fork", NodeType.FORK, true);
            ExecutionNode branchA = createMockNode("mcp:task_a", NodeType.MCP, true);
            ExecutionNode branchB = createMockNode("mcp:task_b", NodeType.MCP, true);

            when(forkNode.execute(any())).thenReturn(NodeExecutionResult.success("core:fork", Map.of()));
            when(forkNode.getNextNodes(any())).thenReturn(List.of(branchA, branchB));
            when(branchA.execute(any())).thenReturn(NodeExecutionResult.success("mcp:task_a", Map.of()));
            when(branchB.execute(any())).thenReturn(NodeExecutionResult.success("mcp:task_b", Map.of()));
            lenient().when(branchA.getNextNodes(any())).thenReturn(List.of());
            lenient().when(branchB.getNextNodes(any())).thenReturn(List.of());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            engine.traverseTree(forkNode, context, execution, eventService, item);

            verify(branchA).execute(any());
            verify(branchB).execute(any());
        }
    }

    @Nested
    @DisplayName("executeWorkflow()")
    class ExecuteWorkflowTests {

        @Test
        @DisplayName("Should return CompletableFuture")
        void shouldReturnCompletableFuture() {
            ExecutionTree tree = createMockTree();
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));

            CompletableFuture<WorkflowResult> future = engine.executeWorkflow(tree, items, execution, eventService);

            assertNotNull(future);
        }

        @Test
        @DisplayName("Should initialize total items via eventService")
        void shouldInitializeTotalItemsViaEventService() {
            ExecutionTree tree = createMockTree();
            List<TriggerItem> items = List.of(
                new TriggerItem("item-1", 0, Map.of()),
                new TriggerItem("item-2", 1, Map.of())
            );

            engine.executeWorkflow(tree, items, execution, eventService);

            verify(eventService).initializeTotalItems(execution, 2);
        }
    }

    @Nested
    @DisplayName("executeSingleNode()")
    class ExecuteSingleNodeTests {

        @Test
        @DisplayName("Should execute single node and return result")
        void shouldExecuteSingleNodeAndReturnResult() {
            ExecutionNode triggerNode = createMockNode("trigger:start", NodeType.TRIGGER, true);
            ExecutionTree tree = createMockTreeWithNode(triggerNode);

            // executeSingleNode uses findNodeFromAllRoots which delegates to nodeSearchService
            when(nodeSearchService.findNodeFromAllRoots(tree, "trigger:start")).thenReturn(triggerNode);
            when(triggerNode.execute(any())).thenReturn(NodeExecutionResult.success("trigger:start", Map.of()));
            when(readyNodeCalculator.calculateReadyNodes(any(), any())).thenReturn(Set.of("mcp:step_1"));

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            StepByStepExecutionResult result = engine.executeSingleNode(
                "trigger:start", tree, context, execution, eventService, item
            );

            assertNotNull(result);
            assertTrue(result.readyNodes().contains("mcp:step_1"));
        }

        @Test
        @DisplayName("Should throw when node not found")
        void shouldThrowWhenNodeNotFound() {
            ExecutionNode triggerNode = createMockNode("trigger:start", NodeType.TRIGGER, true);
            ExecutionTree tree = createMockTreeWithNode(triggerNode);

            // nodeSearchService returns null for unknown node
            when(nodeSearchService.findNodeFromAllRoots(tree, "nonexistent")).thenReturn(null);

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            assertThrows(IllegalArgumentException.class, () ->
                engine.executeSingleNode("nonexistent", tree, context, execution, eventService, item)
            );
        }
    }

    @Nested
    @DisplayName("findNodeById()")
    class FindNodeByIdTests {

        @Test
        @DisplayName("Should return null for null root")
        void shouldReturnNullForNullRoot() {
            // findNodeById delegates to nodeSearchService
            when(nodeSearchService.findNodeById(null, "mcp:step")).thenReturn(null);

            ExecutionNode result = engine.findNodeById(null, "mcp:step");
            assertNull(result);
        }

        @Test
        @DisplayName("Should return root when id matches")
        void shouldReturnRootWhenIdMatches() {
            ExecutionNode root = createMockNode("trigger:start", NodeType.TRIGGER, true);
            // findNodeById delegates to nodeSearchService
            when(nodeSearchService.findNodeById(root, "trigger:start")).thenReturn(root);

            ExecutionNode result = engine.findNodeById(root, "trigger:start");

            assertEquals(root, result);
        }

        @Test
        @DisplayName("Should search in successors")
        void shouldSearchInSuccessors() {
            ExecutionNode root = createMockNode("trigger:start", NodeType.TRIGGER, true);
            ExecutionNode child = createMockNode("mcp:step", NodeType.MCP, true);

            // findNodeById delegates to nodeSearchService
            when(nodeSearchService.findNodeById(root, "mcp:step")).thenReturn(child);

            ExecutionNode result = engine.findNodeById(root, "mcp:step");

            assertEquals(child, result);
        }
    }

    @Nested
    @DisplayName("SBS failure skip propagation")
    class SbsFailureSkipPropagationTests {

        @Test
        @DisplayName("Should propagate SKIPPED to successors when node fails in SBS mode")
        void shouldPropagateSkipToSuccessorsOnFailure() {
            BaseNode failingNode = mock(BaseNode.class);
            lenient().when(failingNode.getNodeId()).thenReturn("mcp:step1");
            lenient().when(failingNode.getType()).thenReturn(NodeType.MCP);
            lenient().when(failingNode.canExecute(any())).thenReturn(true);
            lenient().when(failingNode.getPredecessorIds()).thenReturn(List.of());
            lenient().when(failingNode.getNextNodes(any())).thenReturn(List.of());

            ExecutionNode successor = createMockNode("mcp:step2", NodeType.MCP, true);
            lenient().when(failingNode.getSuccessors()).thenReturn(List.of(successor));

            // Node execution fails
            when(failingNode.execute(any())).thenReturn(
                NodeExecutionResult.failure("mcp:step1", "test error", 100));

            ExecutionTree tree = createMockTreeWithNode(failingNode);
            when(nodeSearchService.findNodeFromAllRoots(tree, "mcp:step1")).thenReturn(failingNode);
            when(readyNodeCalculator.calculateReadyNodes(any(), any())).thenReturn(Set.of());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            engineWithSkipPropagation.executeSingleNode(
                "mcp:step1", tree, context, execution, eventService, item);

            // Should propagate skip via the shared cascade routine (SOURCE_SYNC).
            // Same routine called by AgentAsyncCompletionService on async failure;
            // engine identifies as `sync` source so the Micrometer counter splits cleanly.
            verify(skipPropagationService).cascadeFailureToSuccessors(
                eq(execution), eq(failingNode), eq(0), eq(0), any(),
                eq(false),
                eq(com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService.SOURCE_SYNC));
        }

        @Test
        @DisplayName("Should NOT propagate skip when node succeeds in SBS mode")
        void shouldNotPropagateSkipOnSuccess() {
            BaseNode successNode = mock(BaseNode.class);
            lenient().when(successNode.getNodeId()).thenReturn("mcp:step1");
            lenient().when(successNode.getType()).thenReturn(NodeType.MCP);
            lenient().when(successNode.canExecute(any())).thenReturn(true);
            lenient().when(successNode.getPredecessorIds()).thenReturn(List.of());
            lenient().when(successNode.getNextNodes(any())).thenReturn(List.of());
            lenient().when(successNode.getSuccessors()).thenReturn(List.of());

            when(successNode.execute(any())).thenReturn(
                NodeExecutionResult.success("mcp:step1", Map.of()));

            ExecutionTree tree = createMockTreeWithNode(successNode);
            when(nodeSearchService.findNodeFromAllRoots(tree, "mcp:step1")).thenReturn(successNode);
            when(readyNodeCalculator.calculateReadyNodes(any(), any())).thenReturn(Set.of());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            engineWithSkipPropagation.executeSingleNode(
                "mcp:step1", tree, context, execution, eventService, item);

            // Should NOT propagate skip - neither the legacy direct call nor the new cascade.
            verify(skipPropagationService, never()).persistAndPropagateSkip(
                any(), any(), any(), anyInt(), anyInt(), any());
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("Should propagate skip to multiple successors on failure")
        void shouldPropagateSkipToMultipleSuccessors() {
            BaseNode failingNode = mock(BaseNode.class);
            lenient().when(failingNode.getNodeId()).thenReturn("mcp:step1");
            lenient().when(failingNode.getType()).thenReturn(NodeType.MCP);
            lenient().when(failingNode.canExecute(any())).thenReturn(true);
            lenient().when(failingNode.getPredecessorIds()).thenReturn(List.of());
            lenient().when(failingNode.getNextNodes(any())).thenReturn(List.of());

            ExecutionNode succ1 = createMockNode("mcp:step2", NodeType.MCP, true);
            ExecutionNode succ2 = createMockNode("mcp:step3", NodeType.MCP, true);
            lenient().when(failingNode.getSuccessors()).thenReturn(List.of(succ1, succ2));

            when(failingNode.execute(any())).thenReturn(
                NodeExecutionResult.failure("mcp:step1", "error", 50));

            ExecutionTree tree = createMockTreeWithNode(failingNode);
            when(nodeSearchService.findNodeFromAllRoots(tree, "mcp:step1")).thenReturn(failingNode);
            when(readyNodeCalculator.calculateReadyNodes(any(), any())).thenReturn(Set.of());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            engineWithSkipPropagation.executeSingleNode(
                "mcp:step1", tree, context, execution, eventService, item);

            // Single shared cascade call - the routine itself iterates both successors
            // (verified by V2SkipPropagationService unit tests).
            verify(skipPropagationService).cascadeFailureToSuccessors(
                eq(execution), eq(failingNode), eq(0), eq(0), any(),
                eq(false),
                eq(com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService.SOURCE_SYNC));
        }

        @Test
        @DisplayName("Should also propagate skip in auto mode (traverseTree) on failure")
        void shouldPropagateSkipInAutoModeOnFailure() {
            BaseNode failingNode = mock(BaseNode.class);
            lenient().when(failingNode.getNodeId()).thenReturn("mcp:step1");
            lenient().when(failingNode.getType()).thenReturn(NodeType.MCP);
            lenient().when(failingNode.canExecute(any())).thenReturn(true);
            lenient().when(failingNode.getPredecessorIds()).thenReturn(List.of());
            lenient().when(failingNode.getNextNodes(any())).thenReturn(List.of());

            ExecutionNode successor = createMockNode("mcp:step2", NodeType.MCP, true);
            lenient().when(failingNode.getSuccessors()).thenReturn(List.of(successor));

            when(failingNode.execute(any())).thenReturn(
                NodeExecutionResult.failure("mcp:step1", "error", 100));

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            engineWithSkipPropagation.traverseTree(
                failingNode, context, execution, eventService, item);

            // Auto-mode (traverseTree) and SBS converge on the same cascade entry point.
            verify(skipPropagationService).cascadeFailureToSuccessors(
                eq(execution), eq(failingNode), eq(0), eq(0), any(),
                eq(false),
                eq(com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService.SOURCE_SYNC));
        }
    }

    @Nested
    @DisplayName("getInitialReadyNodes()")
    class GetInitialReadyNodesTests {

        @Test
        @DisplayName("Should delegate to readyNodeCalculator")
        void shouldDelegateToReadyNodeCalculator() {
            ExecutionTree tree = createMockTree();
            when(readyNodeCalculator.getInitialReadyNodes(tree)).thenReturn(Set.of("trigger:start"));

            Set<String> result = engine.getInitialReadyNodes(tree);

            assertTrue(result.contains("trigger:start"));
            verify(readyNodeCalculator).getInitialReadyNodes(tree);
        }
    }

    @Nested
    @DisplayName("getStepByStepScheduler()")
    class GetStepByStepSchedulerTests {

        @Test
        @DisplayName("Should return stepByStepScheduler")
        void shouldReturnStepByStepScheduler() {
            V2StepByStepScheduler result = engine.getStepByStepScheduler();
            assertEquals(stepByStepScheduler, result);
        }
    }

    @Nested
    @DisplayName("shutdown()")
    class ShutdownTests {

        @Test
        @DisplayName("Should not throw")
        void shouldNotThrow() {
            assertDoesNotThrow(() -> engine.shutdown());
        }
    }

    @Nested
    @DisplayName("StopOnError propagation")
    class StopOnErrorTests {

        @Test
        @DisplayName("Should throw WorkflowStoppedException when StopOnError node executes in traverseTree")
        void shouldThrowWhenStopOnErrorInTraverseTree() {
            ExecutionNode stopNode = createMockNode("core:halt", NodeType.MCP, true);
            when(stopNode.isStopOnErrorNode()).thenReturn(true);
            when(stopNode.execute(any())).thenReturn(
                NodeExecutionResult.failureWithOutput("core:halt", "Critical failure",
                    Map.of("error_message", "Critical failure", "error_code", "ERR_001"), 0L)
            );

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            WorkflowStoppedException ex = assertThrows(WorkflowStoppedException.class,
                () -> engine.traverseTree(stopNode, context, execution, eventService, item));

            assertEquals("core:halt", ex.getNodeId());
            assertEquals("Critical failure", ex.getErrorMessage());
        }

        @Test
        @DisplayName("Should propagate WorkflowStoppedException from fork branch and cancel siblings")
        void shouldPropagateFromForkBranchAndCancelSiblings() {
            ExecutionNode forkNode = createMockNode("core:fork", NodeType.FORK, true);
            ExecutionNode branchA = createMockNode("mcp:task_a", NodeType.MCP, true);
            ExecutionNode stopNode = createMockNode("core:halt", NodeType.MCP, true);

            when(forkNode.execute(any())).thenReturn(NodeExecutionResult.success("core:fork", Map.of()));
            when(forkNode.getNextNodes(any())).thenReturn(List.of(branchA, stopNode));

            // Branch A succeeds normally
            when(branchA.execute(any())).thenReturn(NodeExecutionResult.success("mcp:task_a", Map.of()));
            lenient().when(branchA.getNextNodes(any())).thenReturn(List.of());

            // Branch B is a StopOnError node
            when(stopNode.isStopOnErrorNode()).thenReturn(true);
            when(stopNode.execute(any())).thenReturn(
                NodeExecutionResult.failureWithOutput("core:halt", "Workflow stopped",
                    Map.of("error_message", "Workflow stopped", "error_code", "ERR_STOP"), 0L)
            );
            lenient().when(stopNode.getNextNodes(any())).thenReturn(List.of());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            // traverseTree should propagate the exception from the fork handler
            WorkflowStoppedException ex = assertThrows(WorkflowStoppedException.class,
                () -> engine.traverseTree(forkNode, context, execution, eventService, item));

            assertEquals("core:halt", ex.getNodeId());
            assertEquals("Workflow stopped", ex.getErrorMessage());
        }

        @Test
        @DisplayName("Should not throw for normal failure (non-StopOnError node)")
        void shouldNotThrowForNormalFailure() {
            ExecutionNode failingNode = createMockNode("mcp:failing", NodeType.MCP, true);
            when(failingNode.execute(any())).thenReturn(
                NodeExecutionResult.failureWithOutput("mcp:failing", "Normal error",
                    Map.of("error", "something went wrong"), 0L)
            );
            lenient().when(failingNode.getNextNodes(any())).thenReturn(List.of());

            ExecutionContext context = createContext();
            TriggerItem item = new TriggerItem("item-1", 0, Map.of());

            // Normal failure should NOT throw WorkflowStoppedException
            assertDoesNotThrow(
                () -> engine.traverseTree(failingNode, context, execution, eventService, item));
        }
    }

    // ===== Helper methods =====

    private ExecutionNode createMockNode(String nodeId, NodeType type, boolean canExecute) {
        ExecutionNode node = mock(ExecutionNode.class);
        lenient().when(node.getNodeId()).thenReturn(nodeId);
        lenient().when(node.getType()).thenReturn(type);
        lenient().when(node.canExecute(any())).thenReturn(canExecute);
        lenient().when(node.getSuccessors()).thenReturn(List.of());
        lenient().when(node.getPredecessorIds()).thenReturn(List.of());
        lenient().when(node.getNextNodes(any())).thenReturn(List.of());
        return node;
    }

    private ExecutionContext createContext() {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        return ExecutionContext.create(
            "run-1", "workflow-run-1", "tenant-1",
            "item-1", 0, Map.of(), plan
        );
    }

    private ExecutionTree createMockTree() {
        ExecutionTree tree = mock(ExecutionTree.class);
        ExecutionNode rootNode = createMockNode("trigger:start", NodeType.TRIGGER, true);
        lenient().when(rootNode.execute(any())).thenReturn(NodeExecutionResult.success("trigger:start", Map.of()));

        lenient().when(tree.getRunId()).thenReturn("run-1");
        lenient().when(tree.getWorkflowRunId()).thenReturn("workflow-run-1");
        lenient().when(tree.getTenantId()).thenReturn("tenant-1");
        lenient().when(tree.getRootNode()).thenReturn(rootNode);
        lenient().when(tree.getRootNodes()).thenReturn(List.of(rootNode));
        lenient().when(tree.getPlan()).thenReturn(mock(WorkflowPlan.class));
        return tree;
    }

    private ExecutionTree createMockTreeWithNode(ExecutionNode node) {
        ExecutionTree tree = mock(ExecutionTree.class);
        lenient().when(tree.getRunId()).thenReturn("run-1");
        lenient().when(tree.getWorkflowRunId()).thenReturn("workflow-run-1");
        lenient().when(tree.getTenantId()).thenReturn("tenant-1");
        lenient().when(tree.getRootNode()).thenReturn(node);
        lenient().when(tree.getRootNodes()).thenReturn(List.of(node));
        lenient().when(tree.getPlan()).thenReturn(mock(WorkflowPlan.class));
        return tree;
    }
}

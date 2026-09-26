package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.state.BackEdgeState;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.LoopEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BackEdgeHandler.
 * Tests loop iteration logic using iterate-port edges, subgraph computation, and streaming event emission.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BackEdgeHandler")
class BackEdgeHandlerTest {

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private EdgeStatusService edgeStatusService;

    @Mock
    private WorkflowEventPublisher eventPublisher;

    private BackEdgeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BackEdgeHandler(templateEngine, edgeStatusService, eventPublisher, null);
    }

    // ========================================================================
    // hasBackEdge() Tests
    // ========================================================================

    @Nested
    @DisplayName("hasBackEdge()")
    class HasBackEdgeTests {

        @Test
        @DisplayName("Should return false for null node")
        void shouldReturnFalseForNullNode() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            assertFalse(handler.hasBackEdge(null, plan));
        }

        @Test
        @DisplayName("Should return false for null plan")
        void shouldReturnFalseForNullPlan() {
            ExecutionNode node = mock(ExecutionNode.class);
            assertFalse(handler.hasBackEdge(node, null));
        }

        @Test
        @DisplayName("Should return false when no iterate edges exist for source")
        void shouldReturnFalseWhenNoIterateEdges() {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn("mcp:step_1");
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:step_1")).thenReturn(List.of());

            assertFalse(handler.hasBackEdge(node, plan));
        }

        @Test
        @DisplayName("Should return true when iterate edge exists for source")
        void shouldReturnTrueWhenIterateEdgeExists() {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn("mcp:step_c");
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Edge iterateEdge = createIterateEdge("mcp:step_c", "core:my_loop:iterate");
            when(plan.getIterateEdgesForSource("mcp:step_c")).thenReturn(List.of(iterateEdge));

            assertTrue(handler.hasBackEdge(node, plan));
        }
    }

    // ========================================================================
    // shouldContinue() Tests
    // ========================================================================

    @Nested
    @DisplayName("shouldContinue()")
    class ShouldContinueTests {

        @Test
        @DisplayName("Should return false for null node")
        void shouldReturnFalseForNullNode() {
            assertFalse(handler.shouldContinue(null, mock(ExecutionContext.class), mock(WorkflowPlan.class)));
        }

        @Test
        @DisplayName("Should return false when no iterate edges")
        void shouldReturnFalseWhenNoIterateEdges() {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn("mcp:step_1");
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:step_1")).thenReturn(List.of());
            ExecutionContext ctx = mock(ExecutionContext.class);

            assertFalse(handler.shouldContinue(node, ctx, plan));
        }

        @Test
        @DisplayName("Should return true when no condition (always true until maxIterations)")
        void shouldReturnTrueWhenNoCondition() {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn("mcp:step_c");
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Edge iterateEdge = createIterateEdge("mcp:step_c", "core:my_loop:iterate");
            when(plan.getIterateEdgesForSource("mcp:step_c")).thenReturn(List.of(iterateEdge));
            // No condition on Core
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.empty());

            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.getGlobalData(anyString())).thenReturn(Optional.empty());

            assertTrue(handler.shouldContinue(node, ctx, plan));
        }

        @Test
        @DisplayName("regression: a templated hub cap is resolved; the loop does not run on the default budget")
        void templatedHubCapIsResolved() {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn("mcp:step_c");
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Edge iterateEdge = createIterateEdge("mcp:step_c", "core:my_loop:iterate");
            when(plan.getIterateEdgesForSource("mcp:step_c")).thenReturn(List.of(iterateEdge));
            Core hub = createTemplatedCapLoopCore("my_loop", "{{core:cfg.output.passes}}");
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(hub));
            when(plan.getCores()).thenReturn(List.of(hub));
            // Resolves to 1: the body entry already used the only iteration, so no room is left.
            // On the default budget (no condition) this answered true.
            when(templateEngine.evaluateTemplateWithMap(eq("{{core:cfg.output.passes}}"), anyMap())).thenReturn(1);
            ExecutionContext ctx = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-1", 0, Map.of(), plan);

            assertFalse(handler.shouldContinue(node, ctx, plan));
        }

        @Test
        @DisplayName("a bad templated cap on a back-edge the node did NOT route to is never evaluated")
        void templatedCapOnUntakenBranchIsNotEvaluated() {
            // The cap used to be resolved before the port check, so a decision that went forward
            // still evaluated (and could fail on) the cap of the loop-back it did not take.
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn("core:check");
            when(node.isBranchingNode()).thenReturn(true);
            when(node.getSelectedPort(any())).thenReturn("if");
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Edge iterateEdge = createIterateEdge("core:check:else", "core:my_loop:iterate");
            when(plan.getIterateEdgesForSource("core:check")).thenReturn(List.of(iterateEdge));
            Core hub = createTemplatedCapLoopCore("my_loop", "{{core:cfg.output.passes}}");
            org.mockito.Mockito.lenient().when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(hub));
            org.mockito.Mockito.lenient().when(plan.getCores()).thenReturn(List.of(hub));
            org.mockito.Mockito.lenient().when(templateEngine.evaluateTemplateWithMap(anyString(), anyMap())).thenReturn(null);
            ExecutionContext ctx = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-1", 0, Map.of(), plan);

            assertDoesNotThrow(() -> assertFalse(handler.shouldContinue(node, ctx, plan)));
            org.mockito.Mockito.verify(templateEngine, org.mockito.Mockito.never()).evaluateTemplateWithMap(anyString(), anyMap());
        }

        @Test
        @DisplayName("a templated hub cap that resolves to nothing fails loudly")
        void templatedHubCapResolvingToNothingFails() {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn("mcp:step_c");
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Edge iterateEdge = createIterateEdge("mcp:step_c", "core:my_loop:iterate");
            when(plan.getIterateEdgesForSource("mcp:step_c")).thenReturn(List.of(iterateEdge));
            Core hub = createTemplatedCapLoopCore("my_loop", "{{core:cfg.output.passes}}");
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(hub));
            when(plan.getCores()).thenReturn(List.of(hub));
            when(templateEngine.evaluateTemplateWithMap(eq("{{core:cfg.output.passes}}"), anyMap())).thenReturn(null);
            ExecutionContext ctx = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-1", 0, Map.of(), plan);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> handler.shouldContinue(node, ctx, plan));
            assertTrue(e.getMessage().contains("loop.maxIterations"), e.getMessage());
        }

        @Test
        @DisplayName("Should return false when state is terminated")
        void shouldReturnFalseWhenTerminated() {
            ExecutionNode node = mock(ExecutionNode.class);
            when(node.getNodeId()).thenReturn("mcp:step_c");
            WorkflowPlan plan = mock(WorkflowPlan.class);
            Edge iterateEdge = createIterateEdge("mcp:step_c", "core:my_loop:iterate");
            when(plan.getIterateEdgesForSource("mcp:step_c")).thenReturn(List.of(iterateEdge));

            BackEdgeState terminatedState = BackEdgeState.create(iterateEdge.getEdgeId(), 10, "").terminate();
            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.getGlobalData(anyString())).thenReturn(Optional.of(terminatedState));

            assertFalse(handler.shouldContinue(node, ctx, plan));
        }
    }

    // ========================================================================
    // computeSubgraphBetween() Tests (plan-based)
    // ========================================================================

    @Nested
    @DisplayName("computeSubgraphBetween() - plan-based")
    class ComputeSubgraphPlanTests {

        @Test
        @DisplayName("Should include target and source in subgraph")
        void shouldIncludeTargetAndSourceInSubgraph() {
            // A -> B -> C (loop body from A to C)
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("mcp:a", "mcp:b"),
                createEdge("mcp:b", "mcp:c")
            ));

            Set<String> subgraph = handler.computeSubgraphBetween("mcp:a", "mcp:c", plan);

            assertTrue(subgraph.contains("mcp:a"));
            assertTrue(subgraph.contains("mcp:b"));
            assertTrue(subgraph.contains("mcp:c"));
            assertEquals(3, subgraph.size());
        }

        @Test
        @DisplayName("Should handle direct edge between target and source")
        void shouldHandleDirectEdge() {
            // A -> B (loop body from A to B)
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("mcp:a", "mcp:b")
            ));

            Set<String> subgraph = handler.computeSubgraphBetween("mcp:a", "mcp:b", plan);

            assertTrue(subgraph.contains("mcp:a"));
            assertTrue(subgraph.contains("mcp:b"));
            assertEquals(2, subgraph.size());
        }

        @Test
        @DisplayName("Should not include nodes outside the loop")
        void shouldNotIncludeNodesOutsideLoop() {
            // trigger -> A -> B -> C -> D (loop body from A to C, D is after the loop)
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("trigger:start", "mcp:a"),
                createEdge("mcp:a", "mcp:b"),
                createEdge("mcp:b", "mcp:c"),
                createEdge("mcp:c", "mcp:d")
            ));

            Set<String> subgraph = handler.computeSubgraphBetween("mcp:a", "mcp:c", plan);

            assertTrue(subgraph.contains("mcp:a"));
            assertTrue(subgraph.contains("mcp:b"));
            assertTrue(subgraph.contains("mcp:c"));
            assertFalse(subgraph.contains("trigger:start"));
            assertFalse(subgraph.contains("mcp:d"));
        }

        @Test
        @DisplayName("Should exclude iterate edges from traversal")
        void shouldExcludeIterateEdgesFromTraversal() {
            // A -> B -> C with iterate edge C -> core:loop:iterate
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("mcp:a", "mcp:b"),
                createEdge("mcp:b", "mcp:c"),
                createIterateEdge("mcp:c", "core:loop:iterate")
            ));

            Set<String> subgraph = handler.computeSubgraphBetween("mcp:a", "mcp:c", plan);

            // Should only follow forward edges, iterate edge is excluded
            assertEquals(3, subgraph.size());
            assertTrue(subgraph.contains("mcp:a"));
            assertTrue(subgraph.contains("mcp:b"));
            assertTrue(subgraph.contains("mcp:c"));
        }

        @Test
        @DisplayName("Should handle branching within the loop")
        void shouldHandleBranchingWithinLoop() {
            // A -> B, A -> C, B -> D, C -> D (loop body from A to D)
            WorkflowPlan plan = createPlanWithEdges(List.of(
                createEdge("mcp:a", "mcp:b"),
                createEdge("mcp:a", "mcp:c"),
                createEdge("mcp:b", "mcp:d"),
                createEdge("mcp:c", "mcp:d")
            ));

            Set<String> subgraph = handler.computeSubgraphBetween("mcp:a", "mcp:d", plan);

            assertEquals(4, subgraph.size());
            assertTrue(subgraph.contains("mcp:a"));
            assertTrue(subgraph.contains("mcp:b"));
            assertTrue(subgraph.contains("mcp:c"));
            assertTrue(subgraph.contains("mcp:d"));
        }
    }

    // ========================================================================
    // computeSubgraphBetween() Tests (nodeMap-based)
    // ========================================================================

    @Nested
    @DisplayName("computeSubgraphBetween() - nodeMap-based")
    class ComputeSubgraphNodeMapTests {

        @Test
        @DisplayName("Should compute subgraph using ExecutionNode map")
        void shouldComputeSubgraphUsingNodeMap() {
            // A -> B -> C
            BaseNode nodeA = mock(BaseNode.class);
            BaseNode nodeB = mock(BaseNode.class);
            BaseNode nodeC = mock(BaseNode.class);

            when(nodeB.getNodeId()).thenReturn("mcp:b");
            when(nodeC.getNodeId()).thenReturn("mcp:c");

            when(nodeA.getSuccessors()).thenReturn(List.of(nodeB));
            when(nodeB.getSuccessors()).thenReturn(List.of(nodeC));

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("mcp:a", nodeA);
            nodeMap.put("mcp:b", nodeB);
            nodeMap.put("mcp:c", nodeC);

            Set<String> subgraph = handler.computeSubgraphBetween("mcp:a", "mcp:c", nodeMap);

            assertEquals(3, subgraph.size());
            assertTrue(subgraph.contains("mcp:a"));
            assertTrue(subgraph.contains("mcp:b"));
            assertTrue(subgraph.contains("mcp:c"));
        }

        @Test
        @DisplayName("Should handle missing nodes in map")
        void shouldHandleMissingNodesInMap() {
            BaseNode nodeA = mock(BaseNode.class);
            BaseNode nodeB = mock(BaseNode.class);
            when(nodeB.getNodeId()).thenReturn("mcp:b");
            when(nodeA.getSuccessors()).thenReturn(List.of(nodeB));

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("mcp:a", nodeA);

            Set<String> subgraph = handler.computeSubgraphBetween("mcp:a", "mcp:c", nodeMap);

            assertTrue(subgraph.contains("mcp:a"));
            assertTrue(subgraph.contains("mcp:b"));
        }
    }

    // ========================================================================
    // handleBackEdge() Tests
    // ========================================================================

    @Nested
    @DisplayName("handleBackEdge()")
    class HandleBackEdgeTests {

        @Mock
        private V2ExecutionEventService eventService;

        @Mock
        private WorkflowExecution execution;

        @Test
        @DisplayName("Should return context unchanged when no iterate edges")
        void shouldReturnContextWhenNoIterateEdges() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:step_1");

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:step_1")).thenReturn(List.of());

            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);

            ExecutionContext result = handler.handleBackEdge(
                sourceNode, ctx, execution, eventService,
                mock(TriggerItem.class), id -> null, (n, c, e, es, i) -> c
            );

            assertSame(ctx, result);
        }

        @Test
        @DisplayName("Should terminate when condition evaluates to false")
        void shouldTerminateWhenConditionFalse() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:step_c");

            Edge iterateEdge = createIterateEdge("mcp:step_c", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", "#{result > 0}", 10);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:step_c")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:step_a");

            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);
            when(ctx.getGlobalData(anyString())).thenReturn(Optional.empty());
            lenient().when(ctx.getAllStepOutputs()).thenReturn(Map.of());
            lenient().when(ctx.triggerData()).thenReturn(null);
            when(ctx.itemIndex()).thenReturn(0);
            when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
            when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);

            when(execution.getRunId()).thenReturn("run-1");

            // Condition evaluates to false
            TemplateEngine.ConditionEvaluationResult condResult =
                new TemplateEngine.ConditionEvaluationResult("#{result > 0}", "0 > 0", false, null);
            when(templateEngine.evaluateConditionWithDetailsWithMap(eq("#{result > 0}"), anyMap()))
                .thenReturn(condResult);

            handler.handleBackEdge(
                sourceNode, ctx, execution, eventService,
                mock(TriggerItem.class), id -> null, (n, c, e, es, i) -> c
            );

            // Should emit COMPLETED event with loopCoreKey as loopId
            verify(eventPublisher).emitLoopEvent(
                eq("run-1"),
                eq("core:my_loop"),
                eq(LoopEventType.COMPLETED),
                anyMap()
            );
        }

        @Test
        @DisplayName("Should emit ITERATION_COMPLETED when condition is true")
        void shouldEmitIterationCompletedWhenConditionTrue() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:step_c");

            Edge iterateEdge = createIterateEdge("mcp:step_c", "core:my_loop:iterate");

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:step_c")).thenReturn(List.of(iterateEdge));
            // No Core found = no condition = always true
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.empty());
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:step_a");
            when(plan.getEdges()).thenReturn(List.of(
                createEdge("mcp:step_a", "mcp:step_b"),
                createEdge("mcp:step_b", "mcp:step_c")
            ));

            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);
            when(ctx.getGlobalData(anyString())).thenReturn(Optional.empty());
            when(ctx.itemIndex()).thenReturn(0);
            when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
            when(ctx.withoutNodes(anySet())).thenReturn(ctx);
            when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);

            when(execution.getRunId()).thenReturn("run-1");

            // Mock target node lookup
            ExecutionNode targetNode = mock(ExecutionNode.class);
            java.util.function.Function<String, ExecutionNode> nodeFinder = id ->
                "mcp:step_a".equals(id) ? targetNode : null;

            handler.handleBackEdge(
                sourceNode, ctx, execution, eventService,
                mock(TriggerItem.class), nodeFinder, (n, c, e, es, i) -> c
            );

            // Should emit ITERATION_COMPLETED event with loopCoreKey as loopId
            verify(eventPublisher).emitLoopEvent(
                eq("run-1"),
                eq("core:my_loop"),
                eq(LoopEventType.ITERATION_COMPLETED),
                anyMap()
            );
        }

        /**
         * Regression: a later epoch's first back-edge advance was silently swallowed by the
         * per-JVM claim set, which keyed on {@code runId:edgeId:iteration} and so had no way
         * to tell epoch 2's completed-iteration 0 from epoch 1's. The advance was mistaken
         * for a split sibling and skipped: no body re-entry, no exit routing, no ready nodes,
         * and no error - the epoch closes as COMPLETED with everything after the loop missing.
         *
         * <p>Single-replica deployments hid it because {@code cleanupRun} clears the set on the
         * epoch reset; with several replicas only the pod that performed the reset clears, and
         * a resume that lands on any other pod hits the stale entry. The cross-replica claim
         * ({@code RedisLoopIterationStore.claimKey}) has always been epoch-scoped, so this was
         * the two layers disagreeing on what a claim identifies.
         */
        @Test
        @DisplayName("Epoch 2's back-edge must not be swallowed by epoch 1's per-JVM claim")
        void laterEpochBackEdgeIsNotSwallowedByEarlierEpochClaim() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:body_last");
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", null, 3);  // no condition = always true

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");

            when(execution.getRunId()).thenReturn("run-epoch-claim");

            ExecutionNode bodyEntry = mock(ExecutionNode.class);
            java.util.function.Function<String, ExecutionNode> nodeFinder = id ->
                "mcp:body_first".equals(id) ? bodyEntry : null;

            List<Integer> bodyReEntriesByEpoch = new ArrayList<>();

            // Epoch 1: first back-edge advance (completed body iteration 0).
            handler.handleBackEdge(
                sourceNode, freshLoopContext(plan, 1), execution, eventService,
                mock(TriggerItem.class), nodeFinder,
                (n, c, e, es, i) -> { bodyReEntriesByEpoch.add(1); return c; });
            assertEquals(List.of(1), bodyReEntriesByEpoch,
                "Epoch 1 must re-enter the loop body");

            // Epoch 2 on the SAME handler instance (= the same replica): a new epoch restarts
            // the loop at completed-iteration 0, with its own fresh back-edge state.
            handler.handleBackEdge(
                sourceNode, freshLoopContext(plan, 2), execution, eventService,
                mock(TriggerItem.class), nodeFinder,
                (n, c, e, es, i) -> { bodyReEntriesByEpoch.add(2); return c; });

            assertEquals(List.of(1, 2), bodyReEntriesByEpoch,
                "Epoch 2's back-edge must re-enter the loop body too. A missing second entry is "
              + "the silent truncation: the loop neither iterates nor routes to its exit, the "
              + "resume finds nothing ready, and the epoch closes COMPLETED with the rest of the "
              + "workflow never executed.");
        }

        /**
         * The purge in {@code clearNestedBackEdgeState} removes a nested loop's claims by
         * PREFIX. If the prefix and the key are ever built differently the purge matches
         * nothing, the inner loop keeps its iteration-0 claim across outer passes, and its
         * back-edge is dropped in silence. Both now come from one place; this pins that they
         * agree, which no behavioural test in this repo can currently do (nested loops have a
         * separate, pre-existing defect that masks the difference end-to-end).
         */
        @Test
        @DisplayName("The nested-claim purge prefix matches the claim key it must remove")
        void claimKeyAndPurgePrefixAgree() {
            String key = BackEdgeHandler.localClaimKey("run-7", 3, "edge-a", 0);
            String prefix = BackEdgeHandler.localClaimKeyPrefix("run-7", 3, "edge-a");

            assertTrue(key.startsWith(prefix),
                "A claim the purge cannot match is a claim that outlives its loop: key=" + key
              + " prefix=" + prefix);
            assertFalse(BackEdgeHandler.localClaimKey("run-7", 4, "edge-a", 0).startsWith(prefix),
                "The prefix must not reach into another epoch's claims");
            // "edge-a2" starts with "edge-a": without the trailing separator the prefix would
            // purge a DIFFERENT loop's claims. One character, silently wrong, and the pair of
            // ids has to be chosen this way for the assertion to be able to see it.
            assertFalse(BackEdgeHandler.localClaimKey("run-7", 3, "edge-a2", 0).startsWith(prefix),
                "The prefix must stop at the edge id, not swallow every id it prefixes");
            assertFalse(BackEdgeHandler.localClaimKey("run-70", 3, "edge-a", 0).startsWith(prefix),
                "The prefix must stop at the run id too");
            assertTrue(BackEdgeHandler.localClaimKey("run-7", 3, "edge-a", 12).startsWith(prefix),
                "The prefix must cover EVERY iteration of its own edge - that is what it purges");
        }

        /** A context for a loop back-edge in {@code epoch}, with no prior back-edge state. */
        private ExecutionContext freshLoopContext(WorkflowPlan plan, int epoch) {
            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);
            when(ctx.epoch()).thenReturn(epoch);
            when(ctx.itemIndex()).thenReturn(0);
            when(ctx.getGlobalData(anyString())).thenReturn(Optional.empty());
            when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
            when(ctx.withoutNodes(anySet())).thenReturn(ctx);
            when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);
            return ctx;
        }

        @Test
        @DisplayName("Bug A regression (AUTO mode) - terminate when state.iteration() + 1 == maxIterations and skip body re-traversal")
        void autoModeTerminatesWhenNextIterEqualsMax() {
            // AUTO-mode pin for the Bug A boundary: with state.iteration() = maxIter-1
            // (last admissible body iter has run), the back-edge takes the terminate
            // branch and does NOT call traverser.traverse for the body re-entry.
            // The buggy `state.iteration() < state.maxIterations()` check would have
            // mis-admitted one extra body run because state used to start at 1 and
            // would stop at iteration==maxIter, allowing body iter=maxIter to run too.
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:body_last");
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            // Always-true condition so the gate is purely the iteration boundary.
            Core loopCore = createLoopCore("my_loop", "true", 3);

            // Pre-seed state at iteration=2 (last body iter for max=3 has run).
            BackEdgeState atLastBody = BackEdgeState.create(iterateEdge.getEdgeId(), 3, "true")
                    .incrementIteration()   // 0 -> 1
                    .incrementIteration();  // 1 -> 2

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");

            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);
            when(ctx.getGlobalData("back_edge_state:" + iterateEdge.getEdgeId()))
                    .thenReturn(Optional.of(atLastBody));
            lenient().when(ctx.getAllStepOutputs()).thenReturn(Map.of());
            lenient().when(ctx.triggerData()).thenReturn(null);
            when(ctx.itemIndex()).thenReturn(0);
            when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
            when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);

            when(execution.getRunId()).thenReturn("run-auto-terminate");

            // Condition evaluates to true so the only gate is the iteration boundary.
            TemplateEngine.ConditionEvaluationResult condTrue =
                new TemplateEngine.ConditionEvaluationResult("true", "true", true, null);
            lenient().when(templateEngine.evaluateConditionWithDetailsWithMap(eq("true"), anyMap()))
                .thenReturn(condTrue);

            // Capture body re-entry traverser invocation: must NOT be called for body_first.
            ExecutionNode bodyFirst = mock(ExecutionNode.class);
            ExecutionNode exitTarget = mock(ExecutionNode.class);
            java.util.function.Function<String, ExecutionNode> nodeFinder = id -> {
                if ("mcp:body_first".equals(id)) return bodyFirst;
                if ("mcp:after_loop".equals(id)) return exitTarget;
                if ("core:my_loop".equals(id)) return mock(ExecutionNode.class);
                return null;
            };

            int[] traverseCalls = {0};
            String[] traversedNodeId = {null};
            TreeTraverser captureTraverser = (n, c, e, es, i) -> {
                traverseCalls[0]++;
                if (n != null) traversedNodeId[0] = (n == bodyFirst ? "body_first"
                        : n == exitTarget ? "after_loop" : "other");
                return c;
            };

            handler.handleBackEdge(
                sourceNode, ctx, execution, eventService,
                mock(TriggerItem.class), nodeFinder, captureTraverser
            );

            // Termination must emit COMPLETED, NOT ITERATION_COMPLETED.
            verify(eventPublisher).emitLoopEvent(
                eq("run-auto-terminate"),
                eq("core:my_loop"),
                eq(LoopEventType.COMPLETED),
                anyMap()
            );
            verify(eventPublisher, never()).emitLoopEvent(
                anyString(), anyString(), eq(LoopEventType.ITERATION_COMPLETED), anyMap()
            );

            // Traverser may be called for the EXIT path, but NEVER for body re-entry.
            assertNotEquals("body_first", traversedNodeId[0],
                "Body re-entry must NOT be traversed once iteration+1 == maxIterations");
        }

        @Test
        @DisplayName("Regression: AUTO loop condition is evaluated against the next iteration")
        void autoModeEvaluatesConditionAgainstNextIteration() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:body_last");
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            String condition = "{{core:my_loop.iteration}} < 3";
            Core loopCore = createLoopCore("my_loop", condition, 5);
            BackEdgeState afterIterationTwo = BackEdgeState.create(iterateEdge.getEdgeId(), 5, condition)
                    .incrementIteration()
                    .incrementIteration();

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");
            lenient().when(plan.getEdges()).thenReturn(List.of(
                createEdge("mcp:body_first", "mcp:body_last")
            ));

            ExecutionContext ctx = ExecutionContext.create(
                    "run-auto-next-condition", "wfr-auto-next-condition", "tenant", "0", 0, Map.of(), plan)
                .withStepOutput("core:my_loop", loopOutput(2, 5, false))
                .withGlobalData("back_edge_state:" + iterateEdge.getEdgeId(), afterIterationTwo);

            when(execution.getRunId()).thenReturn("run-auto-next-condition");
            when(templateEngine.evaluateConditionWithDetailsWithMap(eq(condition), anyMap()))
                .thenAnswer(invocation -> conditionResultForLoopIteration(condition, invocation.getArgument(1), 3));

            ExecutionNode bodyFirst = mock(ExecutionNode.class);
            ExecutionNode exitTarget = mock(ExecutionNode.class);
            java.util.function.Function<String, ExecutionNode> nodeFinder = id -> {
                if ("mcp:body_first".equals(id)) return bodyFirst;
                if ("mcp:after_loop".equals(id)) return exitTarget;
                return null;
            };

            List<String> traversed = new ArrayList<>();
            TreeTraverser captureTraverser = (n, c, e, es, i) -> {
                if (n == bodyFirst) {
                    traversed.add("body");
                } else if (n == exitTarget) {
                    traversed.add("exit");
                }
                return c;
            };

            handler.handleBackEdge(
                sourceNode, ctx, execution, eventService,
                mock(TriggerItem.class), nodeFinder, captureTraverser
            );

            assertFalse(traversed.contains("body"),
                "Condition must be evaluated with prospective iteration=3, so '< 3' exits after body iteration 2");
            assertTrue(traversed.contains("exit"),
                "When the prospective iteration fails the condition, the loop exit path becomes active");
        }

        @Test
        @DisplayName("regression: single-iteration loop persists distinct terminal controller row")
        void singleIterationLoopPersistsDistinctTerminalControllerRow() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:body_last");
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", "true", 1);
            BackEdgeState lastBody = BackEdgeState.create(iterateEdge.getEdgeId(), 1, "true");

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");

            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);
            when(ctx.getGlobalData("back_edge_state:" + iterateEdge.getEdgeId()))
                    .thenReturn(Optional.of(lastBody));
            when(ctx.getGlobalData(BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY))
                    .thenReturn(Optional.empty());
            lenient().when(ctx.getAllStepOutputs()).thenReturn(Map.of());
            lenient().when(ctx.triggerData()).thenReturn(null);
            when(ctx.itemIndex()).thenReturn(0);
            when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
            when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);
            when(execution.getRunId()).thenReturn("run-single-iteration");

            TemplateEngine.ConditionEvaluationResult condTrue =
                new TemplateEngine.ConditionEvaluationResult("true", "true", true, null);
            lenient().when(templateEngine.evaluateConditionWithDetailsWithMap(eq("true"), anyMap()))
                .thenReturn(condTrue);

            ExecutionNode loopCoreNode = mock(ExecutionNode.class);
            ExecutionNode exitTarget = mock(ExecutionNode.class);
            java.util.function.Function<String, ExecutionNode> nodeFinder = id -> {
                if ("core:my_loop".equals(id)) return loopCoreNode;
                if ("mcp:after_loop".equals(id)) return exitTarget;
                return null;
            };

            handler.handleBackEdge(
                sourceNode, ctx, execution, eventService,
                mock(TriggerItem.class), nodeFinder, (n, c, e, es, i) -> c
            );

            verify(eventService).rePublishNodeOutput(
                eq(execution), same(loopCoreNode), any(), any(), eq(0), eq(ctx), eq(1));
        }

        @Test
        @DisplayName("Regression: SBS loop condition is evaluated against the next iteration")
        void sbsModeEvaluatesConditionAgainstNextIteration() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            String condition = "{{core:my_loop.iteration}} < 3";
            Core loopCore = createLoopCore("my_loop", condition, 5);
            BackEdgeState afterIterationTwo = BackEdgeState.create(iterateEdge.getEdgeId(), 5, condition)
                    .incrementIteration()
                    .incrementIteration();

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");
            lenient().when(plan.getEdges()).thenReturn(List.of(
                createEdge("mcp:body_first", "mcp:body_last")
            ));

            ExecutionContext ctx = ExecutionContext.create(
                    "run-sbs-next-condition", "wfr-sbs-next-condition", "tenant", "0", 0, Map.of(), plan)
                .withStepOutput("core:my_loop", loopOutput(2, 5, false))
                .withGlobalData("back_edge_state:" + iterateEdge.getEdgeId(), afterIterationTwo);

            when(execution.getRunId()).thenReturn("run-sbs-next-condition");
            when(templateEngine.evaluateConditionWithDetailsWithMap(eq(condition), anyMap()))
                .thenAnswer(invocation -> conditionResultForLoopIteration(condition, invocation.getArgument(1), 3));

            StepByStepExecutionResult sbsResult = handler.executeBackEdgeIteration(
                sourceNode, "mcp:body_last", NodeExecutionResult.success("mcp:body_last", Map.of()),
                ctx, execution, eventService, mock(TriggerItem.class), 0, Map.of()
            );

            assertTrue(sbsResult.readyNodes().contains("mcp:after_loop"),
                "The exit target must be ready when prospective iteration=3 fails '< 3'");
            assertFalse(sbsResult.readyNodes().contains("mcp:body_first"),
                "The body target must not be requeued after the loop condition fails for the next iteration");
        }
    }

    // ========================================================================
    // executeBackEdgeIteration() Tests (step-by-step)
    // ========================================================================

    @Nested
    @DisplayName("executeBackEdgeIteration()")
    class ExecuteBackEdgeIterationTests {

        @Mock
        private V2ExecutionEventService eventService;

        @Mock
        private WorkflowExecution execution;

        @Test
        @DisplayName("Should return empty ready nodes when no iterate edges")
        void shouldReturnEmptyReadyNodesWhenNoIterateEdges() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:step_1")).thenReturn(List.of());

            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);

            NodeExecutionResult result = NodeExecutionResult.success("mcp:step_1", Map.of());

            StepByStepExecutionResult sbsResult = handler.executeBackEdgeIteration(
                sourceNode, "mcp:step_1", result, ctx, execution, eventService,
                mock(TriggerItem.class), 0, Map.of()
            );

            assertTrue(sbsResult.readyNodes().isEmpty());
        }

        @Test
        @DisplayName("Should add body target to ready nodes when condition is true")
        void shouldAddBodyTargetToReadyNodesWhenConditionTrue() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            Edge iterateEdge = createIterateEdge("mcp:step_c", "core:my_loop:iterate");

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:step_c")).thenReturn(List.of(iterateEdge));
            // No Core = no condition = always true
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.empty());
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:step_a");

            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);
            when(ctx.getGlobalData(anyString())).thenReturn(Optional.empty());
            when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
            when(ctx.withoutNodes(anySet())).thenReturn(ctx);
            when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);

            when(execution.getRunId()).thenReturn("run-1");

            NodeExecutionResult result = NodeExecutionResult.success("mcp:step_c", Map.of());
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            StepByStepExecutionResult sbsResult = handler.executeBackEdgeIteration(
                sourceNode, "mcp:step_c", result, ctx, execution, eventService,
                mock(TriggerItem.class), 0, nodeMap
            );

            assertTrue(sbsResult.readyNodes().contains("mcp:step_a"));
        }

        @Test
        @DisplayName("Bug A regression - first back-edge call after LoopNode initial body advances iteration to 1, not 2")
        void firstBackEdgeAdvancesIterationToOneNotTwo() {
            // Repro of the prod observation: with the buggy create()=1 + premature
            // increment, after the LoopNode initial body iter=0 the first back-edge
            // call jumped state directly from "create()=1 → increment()=2", causing
            // the body to re-run at iteration=2 (skipping iteration=1 entirely).
            //
            // Contract pinned: with create()=0, the first back-edge admits the
            // next body iter=1 (because (0+1)<maxIter=3 = true) and stores
            // BackEdgeState{iteration=1} in globalData via withGlobalData.
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", null, 3);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");

            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);
            when(ctx.getGlobalData(anyString())).thenReturn(Optional.empty());  // no prior state
            when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
            when(ctx.withoutNodes(anySet())).thenReturn(ctx);
            when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);

            when(execution.getRunId()).thenReturn("run-bug-a");

            NodeExecutionResult result = NodeExecutionResult.success("mcp:body_last", Map.of());

            handler.executeBackEdgeIteration(
                sourceNode, "mcp:body_last", result, ctx, execution, eventService,
                mock(TriggerItem.class), 0, new HashMap<>()
            );

            // Capture the BackEdgeState written to globalData under the back-edge state key.
            org.mockito.ArgumentCaptor<Object> stateCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(ctx, atLeastOnce())
                .withGlobalData(eq("back_edge_state:" + iterateEdge.getEdgeId()), stateCaptor.capture());

            BackEdgeState advanced = (BackEdgeState) stateCaptor.getAllValues().stream()
                .filter(v -> v instanceof BackEdgeState)
                .reduce((a, b) -> b)  // last write wins
                .orElseThrow(() -> new AssertionError("No BackEdgeState was stored in globalData"));

            assertEquals(1, advanced.iteration(),
                "First back-edge after LoopNode initial body must advance iteration to 1, not 2 - "
              + "iteration=2 is the symptom of the create()=1 + premature increment bug that "
              + "skipped iteration=1 from the body re-traversal sequence.");
            assertFalse(advanced.terminated(), "First back-edge should not terminate when (0+1)<3 admits next body");
        }

        @Test
        @DisplayName("Bug A regression - back-edge terminates when state.iteration() + 1 == maxIterations (no more bodies fit)")
        void backEdgeTerminatesWhenNextIterEqualsMax() {
            // After all admissible body runs (iter=0 .. maxIter-1), the back-edge
            // must take the terminate branch. With state.iteration() = maxIter-1,
            // next iter would be maxIter - does not fit (maxIter < maxIter is false).
            // Bug A fix: line 233 check is (state.iteration() + 1) < maxIterations.
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", null, 3);

            // Pre-seed state at iteration=2 (last body iter for max=3 has run).
            BackEdgeState atLastBody = BackEdgeState.create(iterateEdge.getEdgeId(), 3, null)
                    .incrementIteration()   // 0 -> 1
                    .incrementIteration();  // 1 -> 2

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");

            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);
            when(ctx.getGlobalData("back_edge_state:" + iterateEdge.getEdgeId()))
                    .thenReturn(Optional.of(atLastBody));
            when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
            when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);

            when(execution.getRunId()).thenReturn("run-bug-a-terminate");

            NodeExecutionResult result = NodeExecutionResult.success("mcp:body_last", Map.of());

            StepByStepExecutionResult sbsResult = handler.executeBackEdgeIteration(
                sourceNode, "mcp:body_last", result, ctx, execution, eventService,
                mock(TriggerItem.class), 0, new HashMap<>()
            );

            assertTrue(sbsResult.readyNodes().contains("mcp:after_loop"),
                "Termination must activate the loop's exit target after the last admissible body iter");
            assertFalse(sbsResult.readyNodes().contains("mcp:body_first"),
                "Termination must NOT re-traverse the body - no more iters fit");
        }

        /**
         * Companion to {@code laterEpochBackEdgeIsNotSwallowedByEarlierEpochClaim} in
         * {@code HandleBackEdgeTests}, on the SIGNAL-RESUME entry point.
         *
         * <p>A loop body that yields (wait / approval / interface) never reaches the AUTO
         * traversal at its tail: the back-edge is advanced from here instead, when the resume
         * runs the body's last node. This path does not claim today, so the test passes on
         * both sides of that fix - it is here so a future refactor that gives this entry point
         * the same claim cannot reintroduce the epoch-1 collision on the very path where a
         * dropped back-edge is invisible (no exception, epoch closes COMPLETED).
         */
        @Test
        @DisplayName("Epoch 2 back-edge must not be swallowed by epoch 1's per-JVM claim")
        void shouldNotDropLaterEpochBackEdgeClaimedInEarlierEpoch() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", null, 3);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");

            when(execution.getRunId()).thenReturn("run-epoch-claim");

            NodeExecutionResult result = NodeExecutionResult.success("mcp:body_last", Map.of());

            // Epoch 1, first back-edge advance (completed body iteration 0).
            StepByStepExecutionResult epochOne = handler.executeBackEdgeIteration(
                sourceNode, "mcp:body_last", result, freshLoopContext(plan, 1), execution, eventService,
                mock(TriggerItem.class), 0, new HashMap<>());
            assertTrue(epochOne.readyNodes().contains("mcp:body_first"),
                "Epoch 1 must re-enter the loop body");

            // Epoch 2 on the SAME handler instance: a new epoch restarts at completed
            // iteration 0, with its own fresh (empty) back-edge state.
            StepByStepExecutionResult epochTwo = handler.executeBackEdgeIteration(
                sourceNode, "mcp:body_last", result, freshLoopContext(plan, 2), execution, eventService,
                mock(TriggerItem.class), 0, new HashMap<>());

            assertTrue(epochTwo.readyNodes().contains("mcp:body_first"),
                "Epoch 2's back-edge must advance the loop. Empty ready nodes here is the silent "
              + "truncation: the resume loop sees nothing ready and closes the epoch as COMPLETED "
              + "with everything after the loop never executed.");
        }

        /** A context for a loop back-edge in {@code epoch}, with no prior back-edge state. */
        private ExecutionContext freshLoopContext(WorkflowPlan plan, int epoch) {
            ExecutionContext ctx = mock(ExecutionContext.class);
            when(ctx.plan()).thenReturn(plan);
            when(ctx.epoch()).thenReturn(epoch);
            when(ctx.getGlobalData(anyString())).thenReturn(Optional.empty());
            when(ctx.withGlobalData(anyString(), any())).thenReturn(ctx);
            when(ctx.withoutNodes(anySet())).thenReturn(ctx);
            when(ctx.withStepOutput(anyString(), anyMap())).thenReturn(ctx);
            return ctx;
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private Map<String, Object> loopOutput(int iteration, int maxIterations, boolean terminated) {
        boolean enterBody = !terminated;
        String selectedPath = terminated ? "exit" : "body";
        return Map.of(
            "iteration", iteration,
            "maxIterations", maxIterations,
            "terminated", terminated,
            "enter_body", enterBody,
            "selected_path", selectedPath,
            "output", Map.of(
                "iteration", iteration,
                "maxIterations", maxIterations,
                "terminated", terminated,
                "enter_body", enterBody,
                "selected_path", selectedPath
            )
        );
    }

    @SuppressWarnings("unchecked")
    private TemplateEngine.ConditionEvaluationResult conditionResultForLoopIteration(
            String expression, Map<String, Object> evalContext, int upperBoundExclusive) {
        Map<String, Object> loop = (Map<String, Object>) evalContext.get("core:my_loop");
        assertNotNull(loop, "Loop output must be available under the full loop key");
        int iteration = ((Number) loop.get("iteration")).intValue();
        boolean result = iteration < upperBoundExclusive;
        return new TemplateEngine.ConditionEvaluationResult(
            expression, iteration + " < " + upperBoundExclusive, result, null);
    }

    private Edge createEdge(String from, String to) {
        return new Edge(from, to, null);
    }

    /**
     * Creates an iterate edge: from -> core:label:iterate
     */
    private Edge createIterateEdge(String from, String to) {
        return new Edge(from, to, null);
    }

    /**
     * Creates a loop Core with condition and maxIterations.
     */
    private Core createLoopCore(String label, String condition, int maxIterations) {
        return new Core(
            label,          // id
            "loop",         // type
            Map.of(),       // position
            label,          // label
            null,           // decisionConditions
            null,           // switchExpression
            null,           // switchCases
            condition,      // loopCondition
            maxIterations,  // maxIterations
            null,           // strategy
            null,           // list
            null,           // maxItems
            null,           // splitStrategy
            null,           // forkOutputs
            null,           // transformConfig
            null,           // waitConfig
            null,           // downloadConfig
            null,           // responseConfig
            null,           // aggregateConfig
            null,           // optionChoices
            null,           // httpRequestConfig
            null,           // approvalConfig
            null,           // dataInputConfig
            null,           // filterConfig
            null,           // sortConfig
            null,           // limitConfig
            null,           // removeDuplicatesConfig
            null,           // summarizeConfig
            null,           // dateTimeConfig
            null,           // cryptoJwtConfig
            null,           // xmlConfig
            null,           // compressionConfig
            null,           // rssConfig
            null,           // convertToFileConfig
            null,           // extractFromFileConfig
            null,           // compareDatasetsConfig
            null,           // subWorkflowConfig
            null,           // respondToWebhookConfig
            null,           // sendEmailConfig
            null,           // emailInboxConfig
            null,           // codeConfig
            null,           // setConfig
            null,           // htmlExtractConfig
            null,           // taskConfig
            null,           // stopOnErrorConfig
            null,           // sshConfig
            null,           // sftpConfig
            null,           // databaseConfig
            Map.of(),       // params
            null            // graphNodeId
        );
    }

    /** A loop Core whose maxIterations the plan wrote as a {{...}} template. */
    private Core createTemplatedCapLoopCore(String label, String template) {
        return new Core(
            label,          // id
            "loop",         // type
            Map.of(),       // position
            label,          // label
            null,           // decisionConditions
            null,           // switchExpression
            null,           // switchCases
            null,           // loopCondition
            null,           // maxIterations (set aside: it was a template)
            null,           // strategy
            null,           // list
            null,           // maxItems
            null,           // splitStrategy
            null,           // forkOutputs
            null,           // transformConfig
            null,           // waitConfig
            null,           // downloadConfig
            null,           // responseConfig
            null,           // aggregateConfig
            null,           // optionChoices
            null,           // httpRequestConfig
            null,           // approvalConfig
            null,           // dataInputConfig
            null,           // filterConfig
            null,           // sortConfig
            null,           // limitConfig
            null,           // removeDuplicatesConfig
            null,           // summarizeConfig
            null,           // dateTimeConfig
            null,           // cryptoJwtConfig
            null,           // xmlConfig
            null,           // compressionConfig
            null,           // rssConfig
            null,           // convertToFileConfig
            null,           // extractFromFileConfig
            null,           // compareDatasetsConfig
            null,           // subWorkflowConfig
            null,           // respondToWebhookConfig
            null,           // sendEmailConfig
            null,           // emailInboxConfig
            null,           // codeConfig
            null,           // setConfig
            null,           // htmlExtractConfig
            null,           // taskConfig
            null,           // stopOnErrorConfig
            null,           // sshConfig
            null,           // sftpConfig
            null,           // databaseConfig
            Map.of(),       // params
            null,           // graphNodeId
            Map.of("loop", Map.of("maxIterations", template))
        );
    }

    private WorkflowPlan createPlanWithEdges(List<Edge> edges) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")
        ));
        data.put("mcps", List.of());

        List<Map<String, Object>> edgeMaps = edges.stream()
            .map(e -> {
                Map<String, Object> map = new HashMap<>();
                map.put("from", e.from());
                map.put("to", e.to());
                if (e.params() != null) map.put("params", e.params());
                return map;
            })
            .toList();
        data.put("edges", edgeMaps);

        return WorkflowPlan.fromMap(data);
    }

    // ========================================================================
    // updateLoopStepOutput() - write-side regression for split×loop iteration override
    // ========================================================================

    /**
     * Bite tests for the override write-side hoisted into {@code updateLoopStepOutput}
     * (commit 1cc0825ff). The wiring bite test in {@code SplitAwareNodeExecutorTest}
     * stamps the override directly into globalData, bypassing this method - so a future
     * refactor that lifts the override out of {@code updateLoopStepOutput} would silently
     * regress AUTO mode without any test failure. These tests close that guard.
     *
     * <p>Asserts the contract:
     * <ul>
     *   <li>{@code terminated=false} → {@code globalData[LOOP_CORE_OUTPUT_OVERRIDES_KEY][loopCoreKey]}
     *       contains the live wrapped output with {@code iteration == nextIteration}.</li>
     *   <li>{@code terminated=true} → entry removed from the override map.</li>
     * </ul>
     */
    @Nested
    @DisplayName("termination row condition fields")
    class TerminationRowConditionFields {

        /**
         * The row a terminated loop leaves behind is the LAST one for that node, so it is
         * the one the inspector reads. It carried half the condition fields: the other
         * half stayed blank, and `maxIterations` was written camelCase while the
         * enrichment reads `max_iterations`, so Max was empty on exactly the row a reader
         * opens to ask why the loop stopped.
         */
        @Test
        @DisplayName("carries every key StepDataPersistenceService.enrichLoopFields reads")
        void carriesEveryKeyTheEnrichmentReads() {
            com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode loop =
                com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode.builder()
                    .nodeId("core:repeat")
                    .loopCondition("{{value}} > 10")
                    .maxIterations(5)
                    .build();

            Map<String, Object> output = new HashMap<>();
            handler.addLoopConditionFields(
                output, loop, null,
                new BackEdgeHandler.BackEdgeConditionOutcome(false, "15 > 100"), false, false);

            // Exactly the snake_case names enrichLoopFields looks up.
            assertEquals("15 > 100", output.get("condition_resolved"));
            assertEquals(false, output.get("condition_result"));
            assertEquals("{{value}} > 10", output.get("loop_condition"));
            assertEquals("{{value}} > 10", output.get("condition_expression"));
            assertEquals(5, output.get("max_iterations"));
            assertNotNull(output.get("evaluations"));
        }

        @Test
        @DisplayName("a loop that ran out of iterations does not claim it took the exit port")
        void overflowDoesNotClaimTheExitPort() {
            // Hitting maxIterations while the condition still wants to iterate FAILS the
            // run, and this same output deliberately sets selected_path="failed" so the
            // exit is not re-armed. Naming "exit" in the evaluation would tell the reader,
            // on the one row they open to ask why the loop stopped, that the run took a
            // port it was refused.
            com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode loop =
                com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode.builder()
                    .nodeId("core:repeat")
                    .loopCondition("{{value}} > 0")
                    .maxIterations(3)
                    .build();

            Map<String, Object> output = new HashMap<>();
            handler.addLoopConditionFields(
                output, loop, null,
                new BackEdgeHandler.BackEdgeConditionOutcome(true, "15 > 0"), true, true);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evaluations = (List<Map<String, Object>>) output.get("evaluations");
            assertEquals("failed", evaluations.get(0).get("branch"));
            assertEquals(true, evaluations.get(0).get("result"),
                "the condition was still true: that is exactly why the run failed");
            assertEquals(true, output.get("condition_result"));
        }

        @Test
        @DisplayName("a counted loop reports its exit as a fallback, with no invented result")
        void countedLoopReportsFallback() {
            com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode loop =
                com.apimarketplace.orchestrator.execution.v2.nodes.LoopNode.builder()
                    .nodeId("core:repeat")
                    .maxIterations(3)
                    .build();

            Map<String, Object> output = new HashMap<>();
            handler.addLoopConditionFields(
                output, loop, null,
                BackEdgeHandler.BackEdgeConditionOutcome.notEvaluated("iteration cap reached"), false, false);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evaluations = (List<Map<String, Object>>) output.get("evaluations");
            assertEquals("fallback", evaluations.get(0).get("outcome"));
            assertNull(evaluations.get(0).get("result"));
            assertEquals("(not evaluated: iteration cap reached)", output.get("condition_resolved"));
        }
    }

    @Nested
    @DisplayName("updateLoopStepOutput() - override write-side (regression bite)")
    class UpdateLoopStepOutputOverrideWriteSide {

        private com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext freshContext() {
            return com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext.create(
                "run-back-edge-test",
                "wfr-back-edge-test",
                "tenant-back-edge-test",
                "item-0",
                0,
                new HashMap<>(),
                null
            );
        }

        @SuppressWarnings("unchecked")
        private Map<String, Map<String, Object>> overridesIn(
                com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext ctx) {
            Object raw = ctx.getGlobalData(BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY).orElse(null);
            return raw instanceof Map<?, ?> m ? (Map<String, Map<String, Object>>) m : Map.of();
        }

        @Test
        @DisplayName("Continue path publishes live iteration into LOOP_CORE_OUTPUT_OVERRIDES_KEY")
        void continuePathPublishesLiveIteration() {
            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext ctx = freshContext();

            // Simulate BackEdgeHandler advancing the loop from iter 0 to iter 2.
            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext after =
                handler.updateLoopStepOutput(ctx, "core:loop", 2, 5, false, null);

            Map<String, Map<String, Object>> overrides = overridesIn(after);
            assertEquals(1, overrides.size(), "Override map must contain the loop core entry");
            Map<String, Object> entry = overrides.get("core:loop");
            assertNotNull(entry, "Override entry must be published under loopCoreKey");

            // Top-level iteration AND nested output.iteration both reflect the live counter.
            assertEquals(2, entry.get("iteration"));
            @SuppressWarnings("unchecked")
            Map<String, Object> innerOutput = (Map<String, Object>) entry.get("output");
            assertNotNull(innerOutput);
            assertEquals(2, innerOutput.get("iteration"));
            assertEquals(false, entry.get("terminated"));
        }

        @Test
        @DisplayName("Terminate path removes the override entry")
        void terminatePathRemovesOverride() {
            // Pre-seed an active override (simulates a previous continue).
            Map<String, Object> activeOverride = Map.of(
                "iteration", 3, "terminated", false,
                "output", Map.of("iteration", 3, "terminated", false));
            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext ctx = freshContext()
                .withGlobalData(BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY,
                    Map.of("core:loop", activeOverride));

            // Loop terminates at iteration 3 (max reached).
            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext after =
                handler.updateLoopStepOutput(ctx, "core:loop", 3, 3, true, "max_iterations_reached");

            Map<String, Map<String, Object>> overrides = overridesIn(after);
            assertFalse(overrides.containsKey("core:loop"),
                "Terminate must remove the override entry - split×loop body downstream of "
                + "a terminated loop falls back to the persisted DB row, not a stale override.");
        }

        @Test
        @DisplayName("Multiple loops cohabit independently in the override map")
        void multipleLoopsCohabit() {
            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext ctx = freshContext();
            ctx = handler.updateLoopStepOutput(ctx, "core:outer_loop", 1, 5, false, null);
            ctx = handler.updateLoopStepOutput(ctx, "core:inner_loop", 4, 10, false, null);

            Map<String, Map<String, Object>> overrides = overridesIn(ctx);
            assertEquals(2, overrides.size());
            assertEquals(1, overrides.get("core:outer_loop").get("iteration"));
            assertEquals(4, overrides.get("core:inner_loop").get("iteration"));

            // Terminate one - only that one is removed.
            ctx = handler.updateLoopStepOutput(ctx, "core:inner_loop", 4, 10, true, "max_iterations_reached");
            overrides = overridesIn(ctx);
            assertEquals(1, overrides.size());
            assertTrue(overrides.containsKey("core:outer_loop"));
            assertFalse(overrides.containsKey("core:inner_loop"));
        }

        @Test
        @DisplayName("stepOutputs and globalData carry the same wrappedOutput value")
        void stepOutputsAndGlobalDataConsistent() {
            // Single source of truth: both writes must reflect the same shape.
            com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext after =
                handler.updateLoopStepOutput(freshContext(), "core:loop", 7, 10, false, null);

            Object stepOutput = after.getAllStepOutputs().get("core:loop");
            Map<String, Map<String, Object>> overrides = overridesIn(after);
            Object overrideEntry = overrides.get("core:loop");

            assertEquals(stepOutput, overrideEntry,
                "stepOutputs[loopCoreKey] and globalData override entry must be the SAME wrappedOutput map "
                + "(SplitAwareNodeExecutor.reapplyLoopCoreOverrides relies on this to restore via withStepOutput)");
        }

        @Test
        @DisplayName("Terminate writes `reason` into the loop step output (top-level AND inner output)")
        @SuppressWarnings("unchecked")
        void terminateWritesReasonIntoStepOutput() {
            // Pins the runtime producer of the `reason` key that LoopNodeSpec / V358
            // node_type_documentation / docs/node-schemas/loop.md now promise as
            // {{core:loop.output.reason}}. A rename/drop here breaks that contract silently.
            var after = handler.updateLoopStepOutput(
                freshContext(), "core:loop", 3, 3, true, "max_iterations_reached");

            Map<String, Object> stepOutput = (Map<String, Object>) after.getAllStepOutputs().get("core:loop");
            assertNotNull(stepOutput);
            assertEquals("max_iterations_reached", stepOutput.get("reason"));
            assertEquals("exit", stepOutput.get("selected_path"));
            assertEquals(true, stepOutput.get("terminated"));

            Map<String, Object> inner = (Map<String, Object>) stepOutput.get("output");
            assertNotNull(inner, "wrappedOutput must carry the inner 'output' map read via .output.reason");
            assertEquals("max_iterations_reached", inner.get("reason"),
                "{{core:loop.output.reason}} resolves through the inner output map");
        }

        @Test
        @DisplayName("Continue (non-terminated) omits `reason` from the loop step output")
        @SuppressWarnings("unchecked")
        void continueOmitsReasonFromStepOutput() {
            var after = handler.updateLoopStepOutput(
                freshContext(), "core:loop", 2, 5, false, null);

            Map<String, Object> stepOutput = (Map<String, Object>) after.getAllStepOutputs().get("core:loop");
            assertFalse(stepOutput.containsKey("reason"),
                "reason is termination-only - must be absent while iterating");
            assertEquals("body", stepOutput.get("selected_path"));

            Map<String, Object> inner = (Map<String, Object>) stepOutput.get("output");
            assertFalse(inner.containsKey("reason"));
        }
    }

    // ========================================================================
    // Cross-replica loop progress (prod overrun 2026-07-30)
    // ========================================================================

    @Nested
    @DisplayName("cross-replica loop progress")
    class CrossReplicaProgressTests {

        @Mock
        private V2ExecutionEventService eventService;

        @Mock
        private WorkflowExecution execution;

        @Mock
        private com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore loopIterationStore;

        private BackEdgeHandler sharedHandler;

        @BeforeEach
        void setUpSharedHandler() {
            sharedHandler = new BackEdgeHandler(templateEngine, edgeStatusService, eventPublisher, null, loopIterationStore);
        }

        /**
         * Prod bug 2026-07-30: a {@code maxIterations=60} loop whose body contains a 15s Wait
         * ran 73+ iterations. Every iteration resumes from a WAIT_TIMER signal, resumes are
         * dispatched cross-instance, and the iteration counter lived in a per-JVM cache - so
         * each of the 2 replicas enforced maxIterations against its OWN count only.
         *
         * <p>Here the LOCAL state says "iteration 5 of 60" (plenty of room) while the shared
         * store reports 59 - the last admissible body iteration. The handler must terminate.
         */
        @Test
        @DisplayName("regression: terminates at maxIterations using another replica's iteration count")
        void terminatesOnSharedIterationEvenWhenLocalStateIsBehind() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", "true", 60);
            BackEdgeState staleLocal = new BackEdgeState(iterateEdge.getEdgeId(), 5, 60, "true", false);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");

            ExecutionContext ctx = ExecutionContext.create(
                    "run-shared-progress", "wfr-shared", "tenant", "0", 0, Map.of(), plan)
                .withGlobalData("back_edge_state:" + iterateEdge.getEdgeId(), staleLocal);

            when(execution.getRunId()).thenReturn("run-shared-progress");
            when(loopIterationStore.readProgress("run-shared-progress", ctx.epoch(), iterateEdge.getEdgeId()))
                .thenReturn(new com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore
                    .LoopProgress(59, false));

            StepByStepExecutionResult result = sharedHandler.executeBackEdgeIteration(
                sourceNode, "mcp:body_last", NodeExecutionResult.success("mcp:body_last", Map.of()),
                ctx, execution, eventService, mock(TriggerItem.class), 0, Map.of()
            );

            assertTrue(result.readyNodes().contains("mcp:after_loop"),
                "iteration 59 is the last admissible body run of a maxIterations=60 loop, so the "
                + "back-edge must activate the exit path instead of a 61st body run");
            assertFalse(result.readyNodes().contains("mcp:body_first"),
                "the body must NOT be requeued once the shared iteration count reached the ceiling");
            verify(loopIterationStore).markTerminated("run-shared-progress", ctx.epoch(), iterateEdge.getEdgeId(), 59);
            verify(templateEngine, never()).evaluateConditionWithDetailsWithMap(anyString(), anyMap());
        }

        @Test
        @DisplayName("regression: a termination published by another replica stops this replica immediately")
        void adoptsTerminationPublishedByAnotherReplica() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", "true", 60);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            // Stubbed on purpose: pre-fix this path terminated locally and DID queue the exit,
            // so leaving it null would make the "no ready nodes" assertion pass either way.
            lenient().when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");

            ExecutionContext ctx = ExecutionContext.create(
                    "run-shared-terminated", "wfr-shared", "tenant", "0", 0, Map.of(), plan);

            when(execution.getRunId()).thenReturn("run-shared-terminated");
            when(loopIterationStore.readProgress("run-shared-terminated", ctx.epoch(), iterateEdge.getEdgeId()))
                .thenReturn(new com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore
                    .LoopProgress(12, true));

            StepByStepExecutionResult result = sharedHandler.executeBackEdgeIteration(
                sourceNode, "mcp:body_last", NodeExecutionResult.success("mcp:body_last", Map.of()),
                ctx, execution, eventService, mock(TriggerItem.class), 0, Map.of()
            );

            assertTrue(result.readyNodes().isEmpty(),
                "a loop already terminated by another replica must not re-enter the body NOR "
                + "re-activate the exit path - the terminating replica queued it before publishing "
                + "the flag (pre-fix this replica re-ran the whole termination and re-queued "
                + "mcp:after_loop)");
            verify(loopIterationStore, never()).recordIteration(anyString(), anyInt(), anyString(), anyInt());
            verify(loopIterationStore, never()).markTerminated(anyString(), anyInt(), anyString(), anyInt());
            verifyNoInteractions(eventService);
        }

        @Test
        @DisplayName("termination is published only AFTER the exit target is queued")
        void publishesTerminationAfterQueueingTheExit() {
            // Ordering matters: the flag short-circuits every other replica, so a crash between
            // "flag set" and "exit queued" would strand the run until the 24h TTL.
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", "true", 60);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");

            ExecutionContext ctx = ExecutionContext.create(
                    "run-order", "wfr-order", "tenant", "0", 0, Map.of(), plan);

            when(execution.getRunId()).thenReturn("run-order");
            when(loopIterationStore.readProgress("run-order", ctx.epoch(), iterateEdge.getEdgeId()))
                .thenReturn(new com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore
                    .LoopProgress(59, false));

            StepByStepExecutionResult result = sharedHandler.executeBackEdgeIteration(
                sourceNode, "mcp:body_last", NodeExecutionResult.success("mcp:body_last", Map.of()),
                ctx, execution, eventService, mock(TriggerItem.class), 0,
                Map.of("core:my_loop", mock(ExecutionNode.class))
            );

            assertTrue(result.readyNodes().contains("mcp:after_loop"));
            InOrder order = inOrder(eventService, loopIterationStore);
            order.verify(eventService).rePublishNodeOutput(any(), any(), any(), any(), anyInt(), any(), anyInt());
            order.verify(loopIterationStore).markTerminated("run-order", ctx.epoch(), iterateEdge.getEdgeId(), 59);
        }

        @Test
        @DisplayName("A shared count BEHIND the local one never rewinds this replica")
        void neverRewindsToAStaleSharedCount() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", "true", 60);
            BackEdgeState localAhead = new BackEdgeState(iterateEdge.getEdgeId(), 30, 60, "true", false);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            lenient().when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");
            lenient().when(plan.getEdges()).thenReturn(List.of(createEdge("mcp:body_first", "mcp:body_last")));

            ExecutionContext ctx = ExecutionContext.create(
                    "run-stale-shared", "wfr-stale", "tenant", "0", 0, Map.of(), plan)
                .withGlobalData("back_edge_state:" + iterateEdge.getEdgeId(), localAhead);

            when(execution.getRunId()).thenReturn("run-stale-shared");
            when(loopIterationStore.readProgress("run-stale-shared", ctx.epoch(), iterateEdge.getEdgeId()))
                .thenReturn(new com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore
                    .LoopProgress(4, false));
            when(templateEngine.evaluateConditionWithDetailsWithMap(eq("true"), anyMap()))
                .thenReturn(new TemplateEngine.ConditionEvaluationResult("true", "true", true, null));

            sharedHandler.executeBackEdgeIteration(
                sourceNode, "mcp:body_last", NodeExecutionResult.success("mcp:body_last", Map.of()),
                ctx, execution, eventService, mock(TriggerItem.class), 0, Map.of()
            );

            verify(loopIterationStore).recordIteration("run-stale-shared", ctx.epoch(), iterateEdge.getEdgeId(), 31);
        }

        @Test
        @DisplayName("AUTO path: also enforces maxIterations against another replica's count")
        void autoPathTerminatesOnSharedIteration() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:body_last");
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", "true", 60);
            BackEdgeState staleLocal = new BackEdgeState(iterateEdge.getEdgeId(), 2, 60, "true", false);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");
            lenient().when(plan.getEdges()).thenReturn(List.of(createEdge("mcp:body_first", "mcp:body_last")));

            ExecutionContext ctx = ExecutionContext.create(
                    "run-auto-shared", "wfr-auto", "tenant", "0", 0, Map.of(), plan)
                .withGlobalData("back_edge_state:" + iterateEdge.getEdgeId(), staleLocal);

            when(execution.getRunId()).thenReturn("run-auto-shared");
            when(loopIterationStore.tryClaim(eq("run-auto-shared"), anyInt(), anyString(), anyInt()))
                .thenReturn(true);
            when(loopIterationStore.readProgress("run-auto-shared", ctx.epoch(), iterateEdge.getEdgeId()))
                .thenReturn(new com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore
                    .LoopProgress(59, false));

            ExecutionNode bodyFirst = mock(ExecutionNode.class);
            ExecutionNode exitTarget = mock(ExecutionNode.class);
            java.util.function.Function<String, ExecutionNode> nodeFinder = id -> {
                if ("mcp:body_first".equals(id)) return bodyFirst;
                if ("mcp:after_loop".equals(id)) return exitTarget;
                return null;
            };
            List<String> traversed = new ArrayList<>();
            TreeTraverser traverser = (n, c, e, es, i) -> {
                if (n == bodyFirst) traversed.add("body");
                if (n == exitTarget) traversed.add("exit");
                return c;
            };

            sharedHandler.handleBackEdge(sourceNode, ctx, execution, eventService,
                mock(TriggerItem.class), nodeFinder, traverser);

            assertFalse(traversed.contains("body"),
                "the AUTO path must honour the ceiling reached by another replica");
            assertTrue(traversed.contains("exit"));
            verify(templateEngine, never()).evaluateConditionWithDetailsWithMap(anyString(), anyMap());
        }

        @Test
        @DisplayName("AUTO path: a claim lost to another replica makes this one a no-op")
        void autoPathSkipsWhenClaimIsLost() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            when(sourceNode.getNodeId()).thenReturn("mcp:body_last");
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", "true", 60);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");

            ExecutionContext ctx = ExecutionContext.create(
                    "run-claim-lost", "wfr-claim", "tenant", "0", 0, Map.of(), plan);

            when(execution.getRunId()).thenReturn("run-claim-lost");
            when(loopIterationStore.readProgress(anyString(), anyInt(), anyString())).thenReturn(null);
            when(loopIterationStore.tryClaim("run-claim-lost", ctx.epoch(), iterateEdge.getEdgeId(), 0))
                .thenReturn(false);

            List<String> traversed = new ArrayList<>();
            sharedHandler.handleBackEdge(sourceNode, ctx, execution, eventService,
                mock(TriggerItem.class), id -> mock(ExecutionNode.class),
                (n, c, e, es, i) -> { traversed.add("traversed"); return c; });

            assertTrue(traversed.isEmpty(),
                "a split sibling / replica that lost the claim must not advance the loop");
            verify(loopIterationStore, never()).recordIteration(anyString(), anyInt(), anyString(), anyInt());
        }

        @Test
        @DisplayName("publishes each started iteration so the next replica keeps counting")
        void publishesIterationBeforeRunningTheBody() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", "true", 60);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            lenient().when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");
            lenient().when(plan.getEdges()).thenReturn(List.of(createEdge("mcp:body_first", "mcp:body_last")));

            ExecutionContext ctx = ExecutionContext.create(
                    "run-publish", "wfr-publish", "tenant", "0", 0, Map.of(), plan);

            when(execution.getRunId()).thenReturn("run-publish");
            when(loopIterationStore.readProgress("run-publish", ctx.epoch(), iterateEdge.getEdgeId()))
                .thenReturn(new com.apimarketplace.orchestrator.execution.v2.state.RedisLoopIterationStore
                    .LoopProgress(7, false));
            when(templateEngine.evaluateConditionWithDetailsWithMap(eq("true"), anyMap()))
                .thenReturn(new TemplateEngine.ConditionEvaluationResult("true", "true", true, null));

            StepByStepExecutionResult result = sharedHandler.executeBackEdgeIteration(
                sourceNode, "mcp:body_last", NodeExecutionResult.success("mcp:body_last", Map.of()),
                ctx, execution, eventService, mock(TriggerItem.class), 0, Map.of()
            );

            assertTrue(result.readyNodes().contains("mcp:body_first"));
            verify(loopIterationStore).recordIteration("run-publish", ctx.epoch(), iterateEdge.getEdgeId(), 8);
        }

        @Test
        @DisplayName("No shared opinion (nothing recorded, or Redis erroring): keeps local state")
        void fallsBackToLocalStateWhenSharedProgressUnavailable() {
            ExecutionNode sourceNode = mock(ExecutionNode.class);
            Edge iterateEdge = createIterateEdge("mcp:body_last", "core:my_loop:iterate");
            Core loopCore = createLoopCore("my_loop", "true", 60);

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getIterateEdgesForSource("mcp:body_last")).thenReturn(List.of(iterateEdge));
            when(plan.findLoopCoreForIterateEdge(iterateEdge)).thenReturn(Optional.of(loopCore));
            when(plan.findLoopBodyTarget("core:my_loop")).thenReturn("mcp:body_first");
            lenient().when(plan.findLoopExitTarget("core:my_loop")).thenReturn("mcp:after_loop");
            lenient().when(plan.getEdges()).thenReturn(List.of(createEdge("mcp:body_first", "mcp:body_last")));

            ExecutionContext ctx = ExecutionContext.create(
                    "run-no-redis", "wfr-no-redis", "tenant", "0", 0, Map.of(), plan);

            when(execution.getRunId()).thenReturn("run-no-redis");
            when(loopIterationStore.readProgress(anyString(), anyInt(), anyString())).thenReturn(null);
            when(templateEngine.evaluateConditionWithDetailsWithMap(eq("true"), anyMap()))
                .thenReturn(new TemplateEngine.ConditionEvaluationResult("true", "true", true, null));

            StepByStepExecutionResult result = sharedHandler.executeBackEdgeIteration(
                sourceNode, "mcp:body_last", NodeExecutionResult.success("mcp:body_last", Map.of()),
                ctx, execution, eventService, mock(TriggerItem.class), 0, Map.of()
            );

            assertTrue(result.readyNodes().contains("mcp:body_first"),
                "with no shared opinion the handler keeps the pre-fix single-instance behavior");
            verify(loopIterationStore).recordIteration("run-no-redis", ctx.epoch(), iterateEdge.getEdgeId(), 1);
        }

        /**
         * cleanupRun is reached from WorkflowResumeService.clearCachedStateForRerun, whose
         * callers include ReusableTriggerService on a refire - while a PREVIOUS epoch's loop may
         * still be mid-Wait. Wiping shared counters there would restart that live loop at 0 and
         * reproduce the overrun. A refire opens a new EPOCH, so it lands on a fresh key anyway;
         * the ONE action that genuinely reuses the epoch (a step rerun) clears its own back-edges
         * explicitly from {@code StepRerunService} - see
         * {@code StepRerunServiceTest.rerunClearsSharedLoopCountersOfResetBackEdges}.
         */
        @Test
        @DisplayName("cleanupRun must NOT wipe shared loop counters (a refire can hit a live epoch)")
        void cleanupRunLeavesSharedProgressAlone() {
            sharedHandler.cleanupRun("run-to-clean");
            verifyNoInteractions(loopIterationStore);
        }
    }
}

package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.lifecycle.V2WorkflowFinalizer;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2AutoScheduler;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.ReadyNodeCalculator;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.split.SplitMergeHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase-1 engine parallelism tests:
 *
 * <ul>
 *   <li>nested implicit forks inside a parallel branch run their sub-branches
 *       concurrently (same path as explicit Fork);</li>
 *   <li>merge nodes (explicit AND implicit) fire exactly once, whichever
 *       branch/scope arrives last (atomic claim, no double-execution, no orphan);</li>
 *   <li>an AWAITING_SIGNAL yield in one branch does not prevent sibling
 *       branches from completing, and the blocked merge is neither executed
 *       nor skipped (resume picks it up from DB state).</li>
 * </ul>
 *
 * <p>All synchronization is latch/barrier based - no sleeps, no timing
 * assumptions beyond generous liveness timeouts.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnifiedExecutionEngine - parallel branches & merge registry")
class UnifiedExecutionEngineParallelMergeTest {

    private static final long WAIT_SECONDS = 15;

    @Mock private V2WorkflowFinalizer workflowFinalizer;
    @Mock private V2AutoScheduler autoScheduler;
    @Mock private V2StepByStepScheduler stepByStepScheduler;
    @Mock private ReadyNodeCalculator readyNodeCalculator;
    @Mock private BackEdgeHandler backEdgeHandler;
    @Mock private SplitNodeExecutor splitNodeExecutor;
    @Mock private SplitAwareNodeExecutor splitAwareExecutor;
    @Mock private SplitMergeHandler splitMergeHandler;
    @Mock private SplitAggregateHandler splitAggregateHandler;
    @Mock private SplitContextManager splitContextManager;
    @Mock private NodeSearchService nodeSearchService;
    @Mock private V2ExecutionEventService eventService;
    @Mock private WorkflowExecution execution;

    private UnifiedExecutionEngine engine;

    @BeforeEach
    void setUp() {
        // Delegate split-aware execution straight to node.execute() (same harness
        // as UnifiedExecutionEngineTest) - split behavior is out of scope here.
        lenient().when(splitAwareExecutor.execute(any(), any(), any(), any(), any(), any(), anyInt(), any()))
            .thenAnswer(invocation -> {
                ExecutionNode node = invocation.getArgument(0);
                ExecutionContext context = invocation.getArgument(1);
                return node.execute(context);
            });

        engine = new UnifiedExecutionEngine(
            workflowFinalizer, autoScheduler, stepByStepScheduler,
            readyNodeCalculator, backEdgeHandler,
            splitNodeExecutor, splitAwareExecutor, splitMergeHandler,
            splitAggregateHandler, splitContextManager, nodeSearchService,
            null, // skipPropagationService
            null  // creditBudgetService
        );
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private ExecutionNode mockNode(String nodeId, NodeType type) {
        ExecutionNode node = mock(ExecutionNode.class);
        lenient().when(node.getNodeId()).thenReturn(nodeId);
        lenient().when(node.getType()).thenReturn(type);
        lenient().when(node.canExecute(any())).thenReturn(true);
        lenient().when(node.getSuccessors()).thenReturn(List.of());
        lenient().when(node.getPredecessorIds()).thenReturn(List.of());
        lenient().when(node.getNextNodes(any())).thenReturn(List.of());
        return node;
    }

    /** A step node that immediately succeeds. */
    private ExecutionNode successNode(String nodeId) {
        ExecutionNode node = mockNode(nodeId, NodeType.MCP);
        lenient().when(node.execute(any()))
            .thenReturn(NodeExecutionResult.success(nodeId, Map.of("done", true)));
        return node;
    }

    /**
     * A merge node (explicit or implicit) whose canExecute requires ALL given
     * predecessors completed in the context, and whose executions are counted.
     * The context observed by each execution is captured for barrier assertions.
     */
    private ExecutionNode mergeNode(String nodeId, boolean explicitMerge, List<String> predecessors,
                                    AtomicInteger executionCount, AtomicReference<ExecutionContext> observedContext) {
        ExecutionNode node = mockNode(nodeId, explicitMerge ? NodeType.MERGE : NodeType.MCP);
        lenient().when(node.isMergeNode()).thenReturn(explicitMerge);
        lenient().when(node.isImplicitMerge()).thenReturn(!explicitMerge && predecessors.size() > 1);
        lenient().when(node.getPredecessorIds()).thenReturn(predecessors);
        lenient().when(node.canExecute(any())).thenAnswer(invocation -> {
            ExecutionContext ctx = invocation.getArgument(0);
            return predecessors.stream().allMatch(ctx::isCompleted);
        });
        lenient().when(node.execute(any())).thenAnswer(invocation -> {
            executionCount.incrementAndGet();
            if (observedContext != null) {
                observedContext.set(invocation.getArgument(0));
            }
            return NodeExecutionResult.success(nodeId, Map.of("merged", true));
        });
        return node;
    }

    private ExecutionContext createContext() {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        return ExecutionContext.create(
            "run-parallel", "workflow-run-parallel", "tenant-1",
            "item-1", 0, Map.of(), plan
        );
    }

    private TriggerItem item() {
        return new TriggerItem("item-1", 0, Map.of());
    }

    // =====================================================================
    // 1. Nested fork inside a branch runs concurrently
    // =====================================================================

    @Nested
    @DisplayName("Nested implicit fork inside a parallel branch")
    class NestedForkParallelismTests {

        @Test
        @DisplayName("multi-successor node INSIDE a branch runs its sub-branches concurrently (rendezvous barrier)")
        void nestedForkRunsSubBranchesConcurrently() {
            // Branches block on a rendezvous barrier - needs ≥2 common-pool workers
            // (engine runs branches on ForkJoinPool.commonPool(), same as prod).
            assumeTrue(ForkJoinPool.commonPool().getParallelism() >= 2,
                "rendezvous test needs a common pool with parallelism >= 2");
            // Topology: root → [branchEntry, sideB] (top fork)
            //           branchEntry → [a1, a2]      (nested fork INSIDE the branch)
            // a1 and a2 rendezvous on a CyclicBarrier(2): if they ran sequentially
            // on one thread (pre-fix behavior of traverseTreeWithMergeTracking),
            // the first await would time out and the test would fail.
            CyclicBarrier rendezvous = new CyclicBarrier(2);
            AtomicInteger rendezvousSuccesses = new AtomicInteger();

            ExecutionNode a1 = barrierNode("mcp:a1", rendezvous, rendezvousSuccesses);
            ExecutionNode a2 = barrierNode("mcp:a2", rendezvous, rendezvousSuccesses);

            ExecutionNode branchEntry = successNode("mcp:branch_entry");
            when(branchEntry.getNextNodes(any())).thenReturn(List.of(a1, a2));

            ExecutionNode sideB = successNode("mcp:side_b");

            ExecutionNode root = successNode("trigger:root");
            when(root.getNextNodes(any())).thenReturn(List.of(branchEntry, sideB));

            ExecutionContext result = engine.traverseTree(root, createContext(), execution, eventService, item());

            assertThat(rendezvousSuccesses.get())
                .as("a1 and a2 must rendezvous - proves they ran on two threads concurrently")
                .isEqualTo(2);
            assertThat(result.isSuccess("mcp:a1")).isTrue();
            assertThat(result.isSuccess("mcp:a2")).isTrue();
            assertThat(result.isSuccess("mcp:side_b")).isTrue();
        }

        @Test
        @DisplayName("single-successor chain stays sequential on the caller thread (no gratuitous parallelism)")
        void singleSuccessorChainStaysOnCallerThread() {
            AtomicReference<Thread> rootThread = new AtomicReference<>();
            AtomicReference<Thread> childThread = new AtomicReference<>();

            ExecutionNode child = mockNode("mcp:child", NodeType.MCP);
            when(child.execute(any())).thenAnswer(inv -> {
                childThread.set(Thread.currentThread());
                return NodeExecutionResult.success("mcp:child", Map.of());
            });

            ExecutionNode root = mockNode("trigger:root", NodeType.TRIGGER);
            when(root.execute(any())).thenAnswer(inv -> {
                rootThread.set(Thread.currentThread());
                return NodeExecutionResult.success("trigger:root", Map.of());
            });
            when(root.getNextNodes(any())).thenReturn(List.of(child));

            engine.traverseTree(root, createContext(), execution, eventService, item());

            assertThat(childThread.get())
                .as("single-successor chains must not hop threads")
                .isSameAs(rootThread.get())
                .isSameAs(Thread.currentThread());
        }

        private ExecutionNode barrierNode(String nodeId, CyclicBarrier barrier, AtomicInteger successes) {
            ExecutionNode node = mockNode(nodeId, NodeType.MCP);
            lenient().when(node.execute(any())).thenAnswer(inv -> {
                try {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    successes.incrementAndGet();
                    return NodeExecutionResult.success(nodeId, Map.of());
                } catch (TimeoutException | BrokenBarrierException e) {
                    return NodeExecutionResult.failure(nodeId, "not running concurrently: " + e);
                }
            });
            return node;
        }
    }

    // =====================================================================
    // 2. Merge fires exactly once - out-of-order arrival, implicit merges
    // =====================================================================

    @Nested
    @DisplayName("Merge exactly-once semantics")
    class MergeExactlyOnceTests {

        @Test
        @DisplayName("explicit merge fires exactly once when branches complete out of order (slow first branch)")
        void explicitMergeFiresOnceOutOfOrder() {
            // Branch A blocks while branch B runs - needs ≥2 common-pool workers.
            assumeTrue(ForkJoinPool.commonPool().getParallelism() >= 2,
                "out-of-order test needs a common pool with parallelism >= 2");
            // Branch A blocks until branch B has fully completed - guarantees the
            // FIRST listed branch arrives LAST at the merge.
            CountDownLatch bCompleted = new CountDownLatch(1);

            AtomicInteger mergeCount = new AtomicInteger();
            AtomicReference<ExecutionContext> mergeCtx = new AtomicReference<>();
            ExecutionNode merge = mergeNode("core:merge", true,
                List.of("mcp:a", "mcp:b"), mergeCount, mergeCtx);

            ExecutionNode a = mockNode("mcp:a", NodeType.MCP);
            when(a.execute(any())).thenAnswer(inv -> {
                assertThat(bCompleted.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("branch B must be able to complete while branch A is still running")
                    .isTrue();
                return NodeExecutionResult.success("mcp:a", Map.of());
            });
            lenient().when(a.getNextNodes(any())).thenReturn(List.of(merge));

            ExecutionNode b = mockNode("mcp:b", NodeType.MCP);
            when(b.execute(any())).thenAnswer(inv -> {
                bCompleted.countDown();
                return NodeExecutionResult.success("mcp:b", Map.of());
            });
            lenient().when(b.getNextNodes(any())).thenReturn(List.of(merge));

            ExecutionNode root = successNode("trigger:root");
            when(root.getNextNodes(any())).thenReturn(List.of(a, b));

            ExecutionContext result = engine.traverseTree(root, createContext(), execution, eventService, item());

            assertThat(mergeCount.get())
                .as("merge must execute exactly once regardless of arrival order")
                .isEqualTo(1);
            assertThat(mergeCtx.get().isCompleted("mcp:a")).isTrue();
            assertThat(mergeCtx.get().isCompleted("mcp:b")).isTrue();
            assertThat(result.isSuccess("core:merge")).isTrue();
        }

        @Test
        @DisplayName("implicit merge (multi-predecessor non-merge node) also fires exactly once with both branch results")
        void implicitMergeFiresOnce() {
            AtomicInteger mergeCount = new AtomicInteger();
            AtomicReference<ExecutionContext> mergeCtx = new AtomicReference<>();
            ExecutionNode implicitMerge = mergeNode("mcp:sink", false,
                List.of("mcp:a", "mcp:b"), mergeCount, mergeCtx);

            ExecutionNode a = successNode("mcp:a");
            lenient().when(a.getNextNodes(any())).thenReturn(List.of(implicitMerge));
            ExecutionNode b = successNode("mcp:b");
            lenient().when(b.getNextNodes(any())).thenReturn(List.of(implicitMerge));

            ExecutionNode root = successNode("trigger:root");
            when(root.getNextNodes(any())).thenReturn(List.of(a, b));

            ExecutionContext result = engine.traverseTree(root, createContext(), execution, eventService, item());

            assertThat(mergeCount.get()).isEqualTo(1);
            assertThat(mergeCtx.get().isCompleted("mcp:a")).isTrue();
            assertThat(mergeCtx.get().isCompleted("mcp:b")).isTrue();
            assertThat(result.isSuccess("mcp:sink")).isTrue();
        }

        @Test
        @DisplayName("merge spanning a NESTED fork scope and an outer sibling fires once at the outer join (promotion)")
        void mergeSpanningScopesFiresOnceViaPromotion() {
            // Topology: root → [A, B]; A → [a1, a2] (nested fork); a1 → M, a2 → M, B → M.
            // M needs [a1, a2, B]: the nested join can never satisfy it (B is in the
            // outer scope), so it must be PROMOTED and fired exactly once by the outer join.
            AtomicInteger mergeCount = new AtomicInteger();
            AtomicReference<ExecutionContext> mergeCtx = new AtomicReference<>();
            ExecutionNode merge = mergeNode("core:merge", true,
                List.of("mcp:a1", "mcp:a2", "mcp:b"), mergeCount, mergeCtx);

            ExecutionNode a1 = successNode("mcp:a1");
            lenient().when(a1.getNextNodes(any())).thenReturn(List.of(merge));
            ExecutionNode a2 = successNode("mcp:a2");
            lenient().when(a2.getNextNodes(any())).thenReturn(List.of(merge));

            ExecutionNode a = successNode("mcp:a");
            when(a.getNextNodes(any())).thenReturn(List.of(a1, a2));

            ExecutionNode b = successNode("mcp:b");
            lenient().when(b.getNextNodes(any())).thenReturn(List.of(merge));

            ExecutionNode root = successNode("trigger:root");
            when(root.getNextNodes(any())).thenReturn(List.of(a, b));

            ExecutionContext result = engine.traverseTree(root, createContext(), execution, eventService, item());

            assertThat(mergeCount.get())
                .as("cross-scope merge must fire exactly once (promoted to the outer join)")
                .isEqualTo(1);
            assertThat(mergeCtx.get().isCompleted("mcp:a1")).isTrue();
            assertThat(mergeCtx.get().isCompleted("mcp:a2")).isTrue();
            assertThat(mergeCtx.get().isCompleted("mcp:b")).isTrue();
            assertThat(result.isSuccess("core:merge")).isTrue();
        }

        @Test
        @DisplayName("diamond: merge executed inline by an earlier merge's continuation is NOT executed again (atomic claim)")
        void diamondMergeNotExecutedTwice() {
            // Branch C blocks while A and B run - needs ≥2 common-pool workers.
            assumeTrue(ForkJoinPool.commonPool().getParallelism() >= 2,
                "diamond test needs a common pool with parallelism >= 2");
            // Topology: root → [A, B, C]; A → M1, B → M1 (M1 preds [A,B]);
            //           C → M2; M1 → M2 (M2 preds [M1, C]).
            // C is gated to finish AFTER A and B, so M1 is deferred before M2 -
            // deterministic order: the join claims M1 first, M1's continuation
            // executes M2 inline; the deferred M2 entry must then be SKIPPED.
            // Pre-fix the join loop re-executed M2 (no claim, no isCompleted guard).
            CountDownLatch aAndBDone = new CountDownLatch(2);

            AtomicInteger m2Count = new AtomicInteger();
            ExecutionNode m2 = mergeNode("core:merge_2", true,
                List.of("core:merge_1", "mcp:c2"), m2Count, null);

            AtomicInteger m1Count = new AtomicInteger();
            ExecutionNode m1 = mergeNode("core:merge_1", true,
                List.of("mcp:a", "mcp:b"), m1Count, null);
            lenient().when(m1.getNextNodes(any())).thenReturn(List.of(m2));

            ExecutionNode a = mockNode("mcp:a", NodeType.MCP);
            when(a.execute(any())).thenAnswer(inv -> {
                aAndBDone.countDown();
                return NodeExecutionResult.success("mcp:a", Map.of());
            });
            lenient().when(a.getNextNodes(any())).thenReturn(List.of(m1));

            ExecutionNode b = mockNode("mcp:b", NodeType.MCP);
            when(b.execute(any())).thenAnswer(inv -> {
                aAndBDone.countDown();
                return NodeExecutionResult.success("mcp:b", Map.of());
            });
            lenient().when(b.getNextNodes(any())).thenReturn(List.of(m1));

            // Extra hop (c → c2 → M2) after the gate so M1 is deferred before M2
            // with near-certainty - pre-fix the join loop only double-executed M2
            // when it attempted M1 first.
            ExecutionNode c2 = successNode("mcp:c2");
            lenient().when(c2.getNextNodes(any())).thenReturn(List.of(m2));

            ExecutionNode c = mockNode("mcp:c", NodeType.MCP);
            when(c.execute(any())).thenAnswer(inv -> {
                assertThat(aAndBDone.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                return NodeExecutionResult.success("mcp:c", Map.of());
            });
            lenient().when(c.getNextNodes(any())).thenReturn(List.of(c2));

            ExecutionNode root = successNode("trigger:root");
            when(root.getNextNodes(any())).thenReturn(List.of(a, b, c));

            ExecutionContext result = engine.traverseTree(root, createContext(), execution, eventService, item());

            assertThat(m1Count.get()).as("M1 fires once").isEqualTo(1);
            assertThat(m2Count.get())
                .as("M2 must fire exactly once - inline continuation execution must claim it")
                .isEqualTo(1);
            assertThat(result.isSuccess("core:merge_2")).isTrue();
        }
    }

    // =====================================================================
    // 3. Signals in branches
    // =====================================================================

    @Nested
    @DisplayName("AWAITING_SIGNAL inside a parallel branch")
    class SignalInBranchTests {

        @Test
        @DisplayName("a yielding branch does not prevent the sibling branch from completing; the merge stays pending (not executed, not skipped)")
        void signalInBranchDoesNotBlockSiblingOrSkipMerge() {
            AtomicInteger mergeCount = new AtomicInteger();
            ExecutionNode merge = mergeNode("core:merge", true,
                List.of("mcp:waiting", "mcp:b"), mergeCount, null);

            // Branch A yields on a signal (e.g. user approval)
            ExecutionNode waiting = mockNode("mcp:waiting", NodeType.MCP);
            when(waiting.execute(any())).thenReturn(NodeExecutionResult.awaitingSignal(
                "mcp:waiting", SignalType.USER_APPROVAL, Map.of("signal_type", "USER_APPROVAL")));
            lenient().when(waiting.getNextNodes(any())).thenReturn(List.of(merge));

            // Branch B completes normally
            ExecutionNode b = successNode("mcp:b");
            lenient().when(b.getNextNodes(any())).thenReturn(List.of(merge));

            ExecutionNode root = successNode("trigger:root");
            when(root.getNextNodes(any())).thenReturn(List.of(waiting, b));

            ExecutionContext result = engine.traverseTree(root, createContext(), execution, eventService, item());

            // Sibling branch B completed despite branch A's yield
            assertThat(result.isSuccess("mcp:b")).isTrue();
            assertThat(result.isCompleted("mcp:waiting"))
                .as("yielded node must not be terminal - resume completes it later")
                .isFalse();

            // The yield was surfaced as an event (DB + WS path)
            verify(eventService).emitNodeAwaitingSignal(eq(execution), eq(waiting), any(), any(), anyInt(), any());

            // The merge is neither executed nor skipped - SignalResumeService
            // re-derives readiness from persisted state after the approval.
            assertThat(mergeCount.get()).isZero();
            verify(merge, never()).execute(any());
            verify(eventService, never()).emitNodeComplete(eq(execution), eq(merge), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("yield in a NESTED fork sub-branch does not block the nested sibling nor the outer branch")
        void signalInNestedBranchDoesNotBlockOuterScope() {
            // root → [A, B]; A → [waiting, a2]. waiting yields; a2 and B must complete.
            ExecutionNode waiting = mockNode("mcp:waiting", NodeType.MCP);
            when(waiting.execute(any())).thenReturn(NodeExecutionResult.awaitingSignal(
                "mcp:waiting", SignalType.USER_APPROVAL, Map.of("signal_type", "USER_APPROVAL")));

            ExecutionNode a2 = successNode("mcp:a2");
            ExecutionNode a = successNode("mcp:a");
            when(a.getNextNodes(any())).thenReturn(List.of(waiting, a2));

            ExecutionNode b = successNode("mcp:b");

            ExecutionNode root = successNode("trigger:root");
            when(root.getNextNodes(any())).thenReturn(List.of(a, b));

            ExecutionContext result = engine.traverseTree(root, createContext(), execution, eventService, item());

            assertThat(result.isSuccess("mcp:a2")).isTrue();
            assertThat(result.isSuccess("mcp:b")).isTrue();
            assertThat(result.isCompleted("mcp:waiting")).isFalse();
        }
    }
}

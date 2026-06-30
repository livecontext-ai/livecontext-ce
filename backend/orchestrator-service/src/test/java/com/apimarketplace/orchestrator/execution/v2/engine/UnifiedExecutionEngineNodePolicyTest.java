package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.lifecycle.V2WorkflowFinalizer;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2AutoScheduler;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.ReadyNodeCalculator;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase-2 engine tests: per-node execution policies (retry / backoff /
 * continue-on-failure) applied GENERICALLY by the executeNodeCore wrapper.
 *
 * <p>Same harness as {@code UnifiedExecutionEngineParallelMergeTest}; backoff
 * timing uses an injected recording/latched sleeper - never real sleeps as a
 * synchronization mechanism.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnifiedExecutionEngine - per-node execution policies (Phase 2)")
class UnifiedExecutionEngineNodePolicyTest {

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
    @Mock private V2SkipPropagationService skipPropagationService;
    @Mock private V2ExecutionEventService eventService;
    @Mock private WorkflowExecution execution;

    private UnifiedExecutionEngine engine;
    private WorkflowPlan plan;

    @BeforeEach
    void setUp() {
        // Delegate split-aware execution straight to node.execute() - split behavior
        // has its own per-item policy tests in SplitAwareNodeExecutorNodePolicyTest.
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
            skipPropagationService,
            null  // creditBudgetService
        );

        plan = mock(WorkflowPlan.class);
    }

    private void declarePolicy(String nodeId, NodePolicy policy) {
        lenient().when(plan.getNodePolicy(nodeId)).thenReturn(policy);
    }

    private ExecutionContext createContext() {
        return ExecutionContext.create(
            "run-policy", "workflow-run-policy", "tenant-1",
            "item-1", 0, Map.of(), plan
        );
    }

    private TriggerItem item() {
        return new TriggerItem("item-1", 0, Map.of());
    }

    /** Mocks a BaseNode (abstract class) so instanceof BaseNode checks (cascade) apply. */
    private BaseNode mockNode(String nodeId, NodeType type) {
        BaseNode node = mock(BaseNode.class);
        lenient().when(node.getNodeId()).thenReturn(nodeId);
        lenient().when(node.getType()).thenReturn(type);
        lenient().when(node.canExecute(any())).thenReturn(true);
        lenient().when(node.getSuccessors()).thenReturn(List.of());
        lenient().when(node.getPredecessorIds()).thenReturn(List.of());
        lenient().when(node.getNextNodes(any())).thenReturn(List.of());
        return node;
    }

    private BaseNode successNode(String nodeId) {
        BaseNode node = mockNode(nodeId, NodeType.MCP);
        lenient().when(node.execute(any()))
            .thenReturn(NodeExecutionResult.success(nodeId, Map.of("done", true)));
        return node;
    }

    /** A node that fails {@code failuresBeforeSuccess} times, then succeeds. */
    private BaseNode flakyNode(String nodeId, int failuresBeforeSuccess, AtomicInteger executions) {
        BaseNode node = mockNode(nodeId, NodeType.MCP);
        lenient().when(node.execute(any())).thenAnswer(inv -> {
            int n = executions.incrementAndGet();
            return n <= failuresBeforeSuccess
                ? NodeExecutionResult.failure(nodeId, "transient #" + n)
                : NodeExecutionResult.success(nodeId, Map.of("done", true));
        });
        return node;
    }

    // =====================================================================
    // Retry in the executeNodeCore wrapper
    // =====================================================================

    @Nested
    @DisplayName("Retry (auto-mode traversal)")
    class RetryTests {

        @Test
        @DisplayName("fail once → retry → success: node executes twice, the failed attempt goes through the ATTEMPT pipeline (emitNodeAttemptFailed), the terminal COMPLETED through emitNodeComplete")
        void failRetrySuccessEmitsAttemptThenCompletes() {
            AtomicInteger executions = new AtomicInteger();
            BaseNode flaky = flakyNode("mcp:flaky", 1, executions);
            declarePolicy("mcp:flaky", new NodePolicy(1, 0L, false));

            ExecutionContext result = engine.traverseTree(flaky, createContext(), execution, eventService, item());

            assertThat(executions.get()).as("one failure + one successful retry").isEqualTo(2);
            assertThat(result.isSuccess("mcp:flaky")).isTrue();

            // The intermediate FAILED attempt goes through the ATTEMPT-AWARE pipeline
            // (WS + step_data row, NO snapshot/edge mutation, NO billing)...
            ArgumentCaptor<NodeExecutionResult> attemptCaptor = ArgumentCaptor.forClass(NodeExecutionResult.class);
            verify(eventService, times(1)).emitNodeAttemptFailed(eq(execution), eq(flaky), attemptCaptor.capture(), any(), anyInt(), any());
            NodeExecutionResult attemptEvent = attemptCaptor.getValue();
            assertThat(attemptEvent.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(attemptEvent.metadata())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 1)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 2);

            // ...and ONLY the terminal COMPLETED result goes through the full
            // completion pipeline (counts + edges + billing).
            ArgumentCaptor<NodeExecutionResult> finalCaptor = ArgumentCaptor.forClass(NodeExecutionResult.class);
            verify(eventService, times(1)).emitNodeComplete(eq(execution), eq(flaky), finalCaptor.capture(), any(), anyInt(), any());
            NodeExecutionResult finalEvent = finalCaptor.getValue();
            assertThat(finalEvent.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(finalEvent.metadata())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 2)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 2);

            // The transient failure must NOT have cascaded SKIPPED downstream
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("all attempts fail (continueOnFailure=false): node is FAILED, attempts emit via the attempt pipeline, the terminal failure completes + cascades SKIPPED as today")
        void allAttemptsFailKeepsTodayFailureSemantics() {
            AtomicInteger executions = new AtomicInteger();
            BaseNode alwaysFails = mockNode("mcp:doomed", NodeType.MCP);
            when(alwaysFails.execute(any())).thenAnswer(inv -> {
                executions.incrementAndGet();
                return NodeExecutionResult.failure("mcp:doomed", "permanently down");
            });
            BaseNode successor = successNode("mcp:after");
            lenient().when(alwaysFails.getSuccessors()).thenReturn(List.of(successor));
            declarePolicy("mcp:doomed", new NodePolicy(2, 0L, false));

            ExecutionContext result = engine.traverseTree(alwaysFails, createContext(), execution, eventService, item());

            assertThat(executions.get()).isEqualTo(3);
            assertThat(result.isFailed("mcp:doomed")).isTrue();
            verify(successor, never()).execute(any());

            // Attempts 1 and 2 via the attempt pipeline; ONLY the terminal (attempt 3)
            // via emitNodeComplete - counts/failedNodeIds/edges mutate exactly once.
            verify(eventService, times(2)).emitNodeAttemptFailed(eq(execution), eq(alwaysFails), any(), any(), anyInt(), any());
            ArgumentCaptor<NodeExecutionResult> finalCaptor = ArgumentCaptor.forClass(NodeExecutionResult.class);
            verify(eventService, times(1)).emitNodeComplete(eq(execution), eq(alwaysFails), finalCaptor.capture(), any(), anyInt(), any());
            assertThat(finalCaptor.getValue().status()).isEqualTo(NodeStatus.FAILED);
            assertThat(finalCaptor.getValue().metadata())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 3)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 3);

            // Final failure cascades SKIPPED to successors - exactly today's semantics
            verify(skipPropagationService).cascadeFailureToSuccessors(
                eq(execution), eq(alwaysFails), anyInt(), anyInt(), any(), eq(false), eq(V2SkipPropagationService.SOURCE_SYNC));
        }

        @Test
        @DisplayName("REGRESSION (no-policy = byte-identical): without a nodePolicy block a failing node executes ONCE and emits ONE unannotated failure")
        void defaultPolicyExecutesOnceUnannotated() {
            AtomicInteger executions = new AtomicInteger();
            BaseNode failing = mockNode("mcp:legacy", NodeType.MCP);
            when(failing.execute(any())).thenAnswer(inv -> {
                executions.incrementAndGet();
                return NodeExecutionResult.failure("mcp:legacy", "down");
            });
            // plan.getNodePolicy returns null (unstubbed mock) → DEFAULT

            ExecutionContext result = engine.traverseTree(failing, createContext(), execution, eventService, item());

            assertThat(executions.get()).isEqualTo(1);
            assertThat(result.isFailed("mcp:legacy")).isTrue();

            ArgumentCaptor<NodeExecutionResult> captor = ArgumentCaptor.forClass(NodeExecutionResult.class);
            verify(eventService, times(1)).emitNodeComplete(eq(execution), eq(failing), captor.capture(), any(), anyInt(), any());
            assertThat(captor.getValue().metadata()).doesNotContainKey(ExecutionMetadataKeys.POLICY_ATTEMPT);
            assertThat(captor.getValue().output()).doesNotContainKey(ExecutionMetadataKeys.POLICY_ATTEMPT);
            verify(eventService, never()).emitNodeAttemptFailed(any(), any(), any(), any(), anyInt(), any());
        }
    }

    // =====================================================================
    // continueOnFailure
    // =====================================================================

    @Nested
    @DisplayName("continueOnFailure (auto-mode traversal)")
    class ContinueOnFailureTests {

        @Test
        @DisplayName("node FAILED after all attempts but successors execute (SKIPPED-with-error continuation) and no SKIPPED cascade fires")
        void continueOnFailureRunsSuccessors() {
            BaseNode doomed = mockNode("mcp:doomed", NodeType.MCP);
            when(doomed.execute(any()))
                .thenReturn(NodeExecutionResult.failure("mcp:doomed", "down"));
            BaseNode successor = successNode("mcp:after");
            when(doomed.getSuccessors()).thenReturn(List.of(successor));
            declarePolicy("mcp:doomed", new NodePolicy(1, 0L, true));

            ExecutionContext result = engine.traverseTree(doomed, createContext(), execution, eventService, item());

            // The node itself remains FAILED (statuses stay honest → run ends per existing semantics)
            assertThat(result.isFailed("mcp:doomed")).isTrue();
            // ... but traversal continued: the successor executed and completed
            verify(successor).execute(any());
            assertThat(result.isSuccess("mcp:after")).isTrue();
            // ... and the SKIPPED cascade was suppressed
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("merge fed by a FAILED-continueOnFailure predecessor FIRES: isCompleted treats the FAILED predecessor as resolved (merge readiness), exactly one execution")
        void mergeFedByFailedContinueOnFailurePredecessorFires() {
            // Topology: root → [doomed(continueOnFailure), sideB] → merge
            // Pins the claim NodePolicy's javadoc rests on: ExecutionContext.isCompleted
            // covers FAILED, so a merge waiting on a continueOnFailure-FAILED predecessor
            // becomes ready exactly as it does after a terminal SKIPPED node. The merge's
            // canExecute below is the REAL readiness predicate (all predecessors resolved
            // via ctx::isCompleted), not a stub - if isCompleted stopped covering FAILED,
            // the merge would stay deferred forever and the assertions would fail.
            BaseNode doomed = mockNode("mcp:doomed", NodeType.MCP);
            when(doomed.execute(any()))
                .thenReturn(NodeExecutionResult.failure("mcp:doomed", "down"));
            declarePolicy("mcp:doomed", new NodePolicy(0, 0L, true));

            BaseNode sideB = successNode("mcp:side_b");

            List<String> mergePredecessors = List.of("mcp:doomed", "mcp:side_b");
            AtomicInteger mergeExecutions = new AtomicInteger();
            BaseNode merge = mockNode("core:join", NodeType.MERGE);
            lenient().when(merge.isMergeNode()).thenReturn(true);
            lenient().when(merge.getPredecessorIds()).thenReturn(mergePredecessors);
            lenient().when(merge.canExecute(any())).thenAnswer(inv -> {
                ExecutionContext ctx = inv.getArgument(0);
                return mergePredecessors.stream().allMatch(ctx::isCompleted);
            });
            lenient().when(merge.execute(any())).thenAnswer(inv -> {
                mergeExecutions.incrementAndGet();
                return NodeExecutionResult.success("core:join", Map.of("merged", true));
            });

            // continueOnFailure continuation goes through getSuccessors() (getNextNodes
            // filters them out on failure); the success branch goes through getNextNodes.
            when(doomed.getSuccessors()).thenReturn(List.of(merge));
            when(sideB.getNextNodes(any())).thenReturn(List.of(merge));

            BaseNode root = successNode("trigger:root");
            when(root.getNextNodes(any())).thenReturn(List.of(doomed, sideB));

            ExecutionContext result = engine.traverseTree(root, createContext(), execution, eventService, item());

            assertThat(result.isFailed("mcp:doomed")).isTrue();
            assertThat(result.isSuccess("mcp:side_b")).isTrue();
            assertThat(mergeExecutions.get())
                .as("merge fires exactly once with the FAILED-continueOnFailure predecessor counted as resolved")
                .isEqualTo(1);
            assertThat(result.isSuccess("core:join")).isTrue();
        }

        @Test
        @DisplayName("continueOnFailure inside a PARALLEL branch: the failed branch continues to its successor while the sibling branch completes")
        void continueOnFailureInsideParallelBranch() {
            BaseNode doomed = mockNode("mcp:doomed", NodeType.MCP);
            when(doomed.execute(any()))
                .thenReturn(NodeExecutionResult.failure("mcp:doomed", "down"));
            BaseNode afterDoomed = successNode("mcp:after_doomed");
            when(doomed.getSuccessors()).thenReturn(List.of(afterDoomed));
            declarePolicy("mcp:doomed", new NodePolicy(0, 0L, true));

            BaseNode sideB = successNode("mcp:side_b");

            BaseNode root = successNode("trigger:root");
            when(root.getNextNodes(any())).thenReturn(List.of(doomed, sideB));

            ExecutionContext result = engine.traverseTree(root, createContext(), execution, eventService, item());

            assertThat(result.isFailed("mcp:doomed")).isTrue();
            assertThat(result.isSuccess("mcp:after_doomed")).isTrue();
            assertThat(result.isSuccess("mcp:side_b")).isTrue();
        }
    }

    // =====================================================================
    // Backoff under parallel branches
    // =====================================================================

    @Nested
    @DisplayName("Backoff under fork parallelism")
    class BackoffParallelismTests {

        @Test
        @DisplayName("retry backoff blocks ONLY its own branch: the sibling branch completes WHILE the flaky branch is sleeping")
        void backoffDoesNotBlockSiblingBranches() {
            assumeTrue(ForkJoinPool.commonPool().getParallelism() >= 2,
                "parallel backoff test needs a common pool with parallelism >= 2");

            // The flaky branch's backoff WAITS until the sibling branch has executed -
            // if the backoff blocked siblings, this latch would never open and the
            // sleeper would time out the assertion (deadlock-proof via failure).
            CountDownLatch siblingExecuted = new CountDownLatch(1);
            AtomicBoolean siblingRanDuringBackoff = new AtomicBoolean(false);
            engine.setNodePolicyRunner(new NodePolicyRunner(ms -> {
                try {
                    siblingRanDuringBackoff.set(siblingExecuted.await(WAIT_SECONDS, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));

            AtomicInteger flakyExecutions = new AtomicInteger();
            BaseNode flaky = flakyNode("mcp:flaky", 1, flakyExecutions);
            declarePolicy("mcp:flaky", new NodePolicy(1, 1_000L, false));

            BaseNode sideB = mockNode("mcp:side_b", NodeType.MCP);
            when(sideB.execute(any())).thenAnswer(inv -> {
                siblingExecuted.countDown();
                return NodeExecutionResult.success("mcp:side_b", Map.of());
            });

            BaseNode root = successNode("trigger:root");
            when(root.getNextNodes(any())).thenReturn(List.of(flaky, sideB));

            ExecutionContext result = engine.traverseTree(root, createContext(), execution, eventService, item());

            assertThat(siblingRanDuringBackoff.get())
                .as("sibling branch must complete while the flaky branch is in backoff")
                .isTrue();
            assertThat(flakyExecutions.get()).isEqualTo(2);
            assertThat(result.isSuccess("mcp:flaky")).isTrue();
            assertThat(result.isSuccess("mcp:side_b")).isTrue();
        }
    }
}

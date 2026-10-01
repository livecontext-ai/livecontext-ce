package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.SignalType;
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
import com.apimarketplace.orchestrator.execution.v2.split.SplitExecutionOptions;
import com.apimarketplace.orchestrator.execution.v2.split.SplitMergeHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * nodePolicy on the STEP_BY_STEP path ({@link UnifiedExecutionEngine#executeSingleNode}).
 *
 * <p>REGRESSION 2026-09-29: this is the path production runs take (execute, trigger fires, cron),
 * and until that date it applied only {@code timeoutMs}: a node's {@code retryCount} was accepted,
 * displayed and documented, and never ran (7 days of prod: 32k nodes executed here, 0 attempts
 * retried). These pin that the SAME runner wraps the split-aware body here as on the AUTOMATIC
 * path: retries, classification, non-final attempts through {@code emitNodeAttemptFailed} only,
 * and the guards that must never retry (fan-out summary, signal yield, async run).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UnifiedExecutionEngine - nodePolicy on the STEP_BY_STEP path (executeSingleNode)")
class UnifiedExecutionEngineStepByStepNodePolicyTest {

    private static final String NODE_ID = "mcp:provider_call";

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
    @Mock private ExecutionTree tree;
    @Mock private WorkflowPlan plan;

    private UnifiedExecutionEngine engine;
    private BaseNode node;
    private final List<Long> sleeps = new ArrayList<>();
    /** Invocations of the split-aware body (the attempt count), whichever overload the engine used. */
    private final AtomicInteger normalInvocations = new AtomicInteger();
    private final AtomicInteger walkInvocations = new AtomicInteger();

    @BeforeEach
    void setUp() {
        engine = new UnifiedExecutionEngine(
            workflowFinalizer, autoScheduler, stepByStepScheduler,
            readyNodeCalculator, backEdgeHandler,
            splitNodeExecutor, splitAwareExecutor, splitMergeHandler,
            splitAggregateHandler, splitContextManager, nodeSearchService,
            skipPropagationService,
            null  // creditBudgetService
        );
        engine.setNodePolicyRunner(new NodePolicyRunner(sleeps::add));

        node = mock(BaseNode.class);
        when(node.getNodeId()).thenReturn(NODE_ID);
        when(node.getType()).thenReturn(NodeType.MCP);
        when(node.canExecute(any())).thenReturn(true);
        when(node.getSuccessors()).thenReturn(List.of());
        when(node.getPredecessorIds()).thenReturn(List.of());
        when(node.getNextNodes(any())).thenReturn(List.of());

        when(tree.getRunId()).thenReturn("run-sbs");
        when(nodeSearchService.findNodeFromAllRoots(tree, NODE_ID)).thenReturn(node);
        when(nodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(Map.of(NODE_ID, node));
    }

    private ExecutionContext context() {
        return ExecutionContext.create("run-sbs", "workflow-run-sbs", "tenant-1", "item-1", 0, Map.of(), plan);
    }

    private TriggerItem item() {
        return new TriggerItem("item-1", 0, Map.of());
    }

    private void declarePolicy(NodePolicy policy) {
        when(plan.getNodePolicy(NODE_ID)).thenReturn(policy);
    }

    /** The split-aware body answers each call from {@code results}, in order (last one repeats). */
    @SafeVarargs
    private void bodyAnswers(Supplier<NodeExecutionResult>... results) {
        AtomicInteger calls = new AtomicInteger();
        when(splitAwareExecutor.execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any()))
            .thenAnswer(inv -> {
                normalInvocations.incrementAndGet();
                int i = Math.min(calls.getAndIncrement(), results.length - 1);
                return results[i].get();
            });
        when(splitAwareExecutor.execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any(), any()))
            .thenAnswer(inv -> {
                walkInvocations.incrementAndGet();
                int i = Math.min(calls.getAndIncrement(), results.length - 1);
                return results[i].get();
            });
    }

    private static NodeExecutionResult providerFailure(int status) {
        Map<String, Object> output = new HashMap<>();
        output.put("http_status", status);
        output.put("metadata", new HashMap<>());
        return NodeExecutionResult.failureWithOutput(NODE_ID, "provider refused", output, 5);
    }

    private StepByStepExecutionResult run() {
        return engine.executeSingleNode(NODE_ID, tree, context(), execution, eventService, item());
    }

    @Test
    @DisplayName("REGRESSION 2026-09-29: retryCount runs on the step-by-step path (fail, fail, succeed = 3 attempts, COMPLETED)")
    void retryCountRunsOnTheStepByStepPath() {
        declarePolicy(new NodePolicy(2, 0L, false));
        bodyAnswers(
            () -> NodeExecutionResult.failure(NODE_ID, "transient #1"),
            () -> NodeExecutionResult.failure(NODE_ID, "transient #2"),
            () -> NodeExecutionResult.success(NODE_ID, Map.of("done", true)));

        StepByStepExecutionResult outcome = run();

        assertThat(normalInvocations.get()).as("pre-fix: 1, the policy was never applied here").isEqualTo(3);
        assertThat(outcome.nodeResult().isSuccess()).isTrue();

        ArgumentCaptor<NodeExecutionResult> attempts = ArgumentCaptor.forClass(NodeExecutionResult.class);
        verify(eventService, times(2)).emitNodeAttemptFailed(
            eq(execution), eq(node), attempts.capture(), any(), anyInt(), any());
        assertThat(attempts.getAllValues())
            .extracting(r -> r.output().get(ExecutionMetadataKeys.POLICY_ATTEMPT))
            .containsExactly(1, 2);
        ArgumentCaptor<NodeExecutionResult> terminal = ArgumentCaptor.forClass(NodeExecutionResult.class);
        verify(eventService, times(1)).emitNodeComplete(
            eq(execution), eq(node), terminal.capture(), any(), anyInt(), any());
        assertThat(terminal.getValue().isSuccess()).isTrue();
        assertThat(terminal.getValue().output())
            .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 3)
            .containsEntry(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT, true);
    }

    @Test
    @DisplayName("default policy: one invocation, no attempt event, a thrown RuntimeException propagates unchanged")
    void defaultPolicyIsALegacyPassthrough() {
        bodyAnswers(() -> NodeExecutionResult.success(NODE_ID, Map.of("done", true)));

        run();

        assertThat(normalInvocations.get()).isEqualTo(1);
        verify(eventService, never()).emitNodeAttemptFailed(any(), any(), any(), any(), anyInt(), any());

        IllegalStateException boom = new IllegalStateException("legacy boom");
        when(splitAwareExecutor.execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any()))
            .thenThrow(boom);
        assertThatThrownBy(this::run).isSameAs(boom);
    }

    @Test
    @DisplayName("a 404 with retryCount 2 is attempted once and says why (permanent_refusal)")
    void definiteRefusalIsNotRetried() {
        declarePolicy(new NodePolicy(2, 0L, false));
        bodyAnswers(() -> providerFailure(404));

        StepByStepExecutionResult outcome = run();

        assertThat(normalInvocations.get()).isEqualTo(1);
        assertThat(outcome.nodeResult().isFailure()).isTrue();
        assertThat(outcome.nodeResult().output())
            .containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, "permanent_refusal");
        verify(eventService, never()).emitNodeAttemptFailed(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("a split fan-out summary is never retried here: its items applied the policy themselves")
    void splitFanOutSummaryIsNotRetried() {
        declarePolicy(new NodePolicy(3, 0L, false));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true);
        bodyAnswers(() -> new NodeExecutionResult(NODE_ID,
            com.apimarketplace.orchestrator.domain.execution.NodeStatus.FAILED,
            Map.of(), java.util.Optional.of("item failed"), metadata, 5));

        run();

        assertThat(normalInvocations.get()).isEqualTo(1);
        verify(eventService, never()).emitNodeAttemptFailed(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("a signal yield (AWAITING_SIGNAL) is never retried")
    void awaitingSignalIsNotRetried() {
        declarePolicy(new NodePolicy(3, 0L, false));
        bodyAnswers(() -> NodeExecutionResult.awaitingSignal(NODE_ID, SignalType.USER_APPROVAL, Map.of()));

        StepByStepExecutionResult outcome = run();

        assertThat(normalInvocations.get()).isEqualTo(1);
        assertThat(outcome.nodeResult().isAwaitingSignal()).isTrue();
        verify(eventService).emitNodeAwaitingSignal(eq(execution), eq(node), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("an async run (engine-owned async I/O) is never retried")
    void asyncRunningIsNotRetried() {
        declarePolicy(new NodePolicy(3, 0L, false));
        bodyAnswers(() -> NodeExecutionResult.asyncRunning(NODE_ID, "corr-1", "agent", Map.of()));

        StepByStepExecutionResult outcome = run();

        assertThat(normalInvocations.get()).isEqualTo(1);
        assertThat(outcome.nodeResult().isAsyncRunning()).isTrue();
        verify(eventService).emitNodeAsyncRunning(eq(execution), eq(node), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("a per-item continuation walk (options overload) is wrapped the same way")
    void perItemContinuationWalkRetries() {
        declarePolicy(new NodePolicy(1, 250L, false));
        bodyAnswers(
            () -> NodeExecutionResult.failure(NODE_ID, "transient"),
            () -> NodeExecutionResult.success(NODE_ID, Map.of("done", true)));

        StepByStepExecutionResult outcome = engine.executeSingleNode(NODE_ID, tree, context(), execution,
            eventService, item(), SplitExecutionOptions.perItemContinuationWalk());

        assertThat(walkInvocations.get()).isEqualTo(2);
        assertThat(normalInvocations.get()).as("the walk keeps its options overload").isZero();
        assertThat(outcome.nodeResult().isSuccess()).isTrue();
        assertThat(sleeps).containsExactly(250L);
        verify(eventService, times(1)).emitNodeAttemptFailed(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("REGRESSION: continueOnFailure continues on the production path: flag kept, no SKIPPED cascade, the calculator sees the flag")
    void continueOnFailureContinuesOnTheProductionPath() {
        // Until 2026-09-29 this path dropped the flag (withoutContinueOnFailure) and cascaded
        // SKIPPED to every node below: continueOnFailure did nothing on execute / trigger / cron runs.
        declarePolicy(new NodePolicy(1, 0L, true));
        bodyAnswers(
            () -> NodeExecutionResult.failure(NODE_ID, "down #1"),
            () -> NodeExecutionResult.failure(NODE_ID, "down #2"));

        StepByStepExecutionResult outcome = run();

        assertThat(normalInvocations.get()).as("the retry still runs").isEqualTo(2);
        assertThat(outcome.nodeResult().isFailure()).as("the node itself stays FAILED").isTrue();
        assertThat(outcome.nodeResult().output())
            .containsEntry(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true);
        assertThat(outcome.nodeResult().metadata())
            .containsEntry(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true);
        verify(skipPropagationService, never()).cascadeFailureToSuccessors(
            any(), any(), anyInt(), anyInt(), any(), anyBoolean(), any());
        // The readiness walk receives a context whose stored output of this FAILED node carries the
        // flag: that is what makes ReadyNodeCalculator walk past it to the successors.
        ArgumentCaptor<ExecutionContext> readiness = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(readyNodeCalculator).calculateReadyNodes(readiness.capture(), eq(tree));
        assertThat(readiness.getValue().isFailed(NODE_ID)).isTrue();
        assertThat(ExecutionMetadataKeys.isContinueOnFailureStored(
            readiness.getValue().getStepOutput(NODE_ID).orElse(null))).isTrue();
    }

    @Test
    @DisplayName("without continueOnFailure a final failure still cascades SKIPPED (legacy behaviour kept)")
    void failureWithoutTheFlagStillCascades() {
        declarePolicy(new NodePolicy(1, 0L, false));
        bodyAnswers(() -> NodeExecutionResult.failure(NODE_ID, "down"));

        StepByStepExecutionResult outcome = run();

        assertThat(outcome.nodeResult().metadata())
            .doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
        verify(skipPropagationService).cascadeFailureToSuccessors(
            eq(execution), eq(node), anyInt(), anyInt(), any(), eq(false), any());
    }

    @Test
    @DisplayName("a continued node with a forward successor is not taken for the tail of a loop body (no back-edge iteration)")
    void continuedNodeWithForwardSuccessorDoesNotIterateTheLoop() {
        declarePolicy(new NodePolicy(0, 0L, true));
        bodyAnswers(() -> NodeExecutionResult.failure(NODE_ID, "down"));
        BaseNode after = mock(BaseNode.class);
        when(after.getNodeId()).thenReturn("mcp:after");
        when(node.getSuccessors()).thenReturn(List.of(after));
        when(backEdgeHandler.hasBackEdge(any(), any())).thenReturn(true);

        run();

        verify(backEdgeHandler, never()).executeBackEdgeIteration(
            any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("a continued node that IS the tail of a loop body (no successor) still iterates the loop")
    void continuedTailOfALoopBodyStillIterates() {
        declarePolicy(new NodePolicy(0, 0L, true));
        bodyAnswers(() -> NodeExecutionResult.failure(NODE_ID, "down"));
        when(backEdgeHandler.hasBackEdge(any(), any())).thenReturn(true);
        when(backEdgeHandler.executeBackEdgeIteration(
            any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
            .thenAnswer(inv -> new StepByStepExecutionResult(
                inv.getArgument(3), inv.getArgument(2), java.util.Set.of(), false));

        run();

        verify(backEdgeHandler).executeBackEdgeIteration(
            eq(node), eq(NODE_ID), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("a whole split fan-out failure (SplitFanOutFailedException) with retryCount 3 is invoked once and propagates")
    void splitFanOutFailureIsNeverRetriedHere() {
        declarePolicy(new NodePolicy(3, 0L, false));
        SplitAwareNodeExecutor.SplitFanOutFailedException boom =
            new SplitAwareNodeExecutor.SplitFanOutFailedException("Split item execution failed or timed out", null);
        when(splitAwareExecutor.execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any()))
            .thenAnswer(inv -> {
                normalInvocations.incrementAndGet();
                throw boom;
            });

        assertThatThrownBy(this::run).isSameAs(boom);

        assertThat(normalInvocations.get()).as("re-running would repeat the items that already succeeded").isEqualTo(1);
        assertThat(sleeps).isEmpty();
        verify(eventService, never()).emitNodeAttemptFailed(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("behaviour change: a policied node (timeoutMs only) whose body throws becomes a FAILED result, not an exception")
    void policiedNodeBodyExceptionBecomesAFailedResult() {
        // Before 2026-09-29 the step-by-step path let the exception escape executeSingleNode for
        // every node. Any policy now routes the body through the runner, which converts a thrown
        // exception into a FAILED result exactly like the AUTO path; only the no-policy passthrough
        // keeps the legacy propagation (pinned in defaultPolicyIsALegacyPassthrough).
        declarePolicy(new NodePolicy(0, 0L, false, 30_000L, false, null));
        when(splitAwareExecutor.execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any()))
            .thenAnswer(inv -> {
                normalInvocations.incrementAndGet();
                throw new IllegalStateException("body blew up");
            });

        StepByStepExecutionResult outcome = run();

        assertThat(normalInvocations.get()).isEqualTo(1);
        assertThat(outcome.nodeResult().isFailure()).isTrue();
        assertThat(outcome.nodeResult().errorMessage()).contains("body blew up");
        verify(eventService).emitNodeComplete(eq(execution), eq(node), any(), any(), anyInt(), any());
    }
}

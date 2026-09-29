package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.lifecycle.V2WorkflowFinalizer;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.SetNode;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2AutoScheduler;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.MergeReachabilityGuard;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.ReadyNodeCalculator;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.split.SplitMergeHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.credit.NodeCreditGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for prod run {@code run_<id>} epoch 152.
 *
 * <p>{@code agent:draft_reply} merges three reply-category Move nodes. The mail at item 2 was
 * classified as spam, so all three were SKIPPED for it; an approval gate on its real branch
 * held the epoch open, and on resume the merge was declared ready ("all predecessors
 * resolved" counts SKIPPED as resolved) and executed for item 2 - a 70k-token Opus call
 * drafting a reply to a mail whose route was "delete", after which the epoch closed with
 * {@code prep_draft} and the whole reply chain never reached.
 *
 * <p>The engine wires the real {@link MergeReachabilityGuard} here, over a repository stubbed
 * with the rows epoch 152 actually held, so this test exercises the decision and the wiring
 * together.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UnifiedExecutionEngine - merge that no branch reached")
class UnifiedExecutionEngineUnreachableMergeTest {

    private static final String RUN_ID = "run_<id>";
    private static final String TENANT = "1";
    private static final String TRIGGER_ID = "trigger:poll_inbox";
    private static final String MERGE_ID = "agent:draft_reply";
    private static final int EPOCH = 152;
    private static final int SPAM_ITEM = 2;
    private static final List<String> REPLY_MOVES =
            List.of("core:move_clients", "core:move_prospects", "core:move_partenaires");

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
    @Mock private CreditBudgetService creditBudgetService;
    @Mock private NodeCreditGate nodeCreditGate;
    @Mock private WorkflowStepDataRepository stepDataRepository;

    @Mock private ExecutionTree tree;
    @Mock private WorkflowExecution execution;
    @Mock private V2ExecutionEventService eventService;
    @Mock private WorkflowPlan plan;

    private UnifiedExecutionEngine engine;
    private SetNode draftReply;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        engine = new UnifiedExecutionEngine(
                workflowFinalizer, autoScheduler, stepByStepScheduler, readyNodeCalculator,
                backEdgeHandler, splitNodeExecutor, splitAwareExecutor, splitMergeHandler,
                splitAggregateHandler, splitContextManager, nodeSearchService,
                skipPropagationService, creditBudgetService);
        engine.setNodeCreditGate(nodeCreditGate);
        engine.setMergeReachabilityGuard(new MergeReachabilityGuard(stepDataRepository));

        draftReply = new SetNode(MERGE_ID, null);
        draftReply.setPredecessors(REPLY_MOVES);

        // The state the resume rebuilt: all three Move nodes terminal-SKIPPED, which is what
        // makes canExecute() true and lets the merge through to the dispatch chain.
        ExecutionContext base = ExecutionContext.create(
                RUN_ID, "wf-run-1", TENANT, String.valueOf(SPAM_ITEM), SPAM_ITEM,
                TRIGGER_ID, EPOCH, 0, Map.of(), plan);
        for (String move : REPLY_MOVES) {
            base = base.withResult(move, NodeExecutionResult.skipped(move, "Not routed to this branch"));
        }
        context = base;

        when(tree.getRunId()).thenReturn(RUN_ID);
        when(nodeSearchService.findNodeFromAllRoots(tree, MERGE_ID)).thenReturn(draftReply);
        when(nodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(Map.of(MERGE_ID, draftReply));
        when(readyNodeCalculator.calculateReadyNodes(any(), any())).thenReturn(Set.of());
        when(backEdgeHandler.hasBackEdge(any(), any())).thenReturn(false);
        when(nodeCreditGate.denyOrNull(anyString(), anyString())).thenReturn(null);
        // The AUTO path charges the local budget mirror before the body; an unstubbed mock
        // answers false and every node comes back FAILED, which would make the assertions
        // below pass for the wrong reason.
        when(creditBudgetService.tryConsume(anyString(), any())).thenReturn(true);
        // The body succeeds whenever it is reached. Stubbed here on purpose: it makes the
        // pre-fix failure read "the merge COMPLETED" rather than an incidental NPE.
        when(splitAwareExecutor.execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(NodeExecutionResult.success(MERGE_ID, Map.of("ok", true)));
    }

    /** The rows epoch 152 held: every reply-branch Move node SKIPPED for the spam item. */
    private void everyBranchSkippedForSpamItem() {
        when(stepDataRepository.findTerminalStatusesForItem(eq(RUN_ID), any(), eq(EPOCH), eq(SPAM_ITEM)))
                .thenReturn(List.of(
                        new Object[]{"core:move_clients", "SKIPPED"},
                        new Object[]{"core:move_prospects", "SKIPPED"},
                        new Object[]{"core:move_partenaires", "SKIPPED"}));
    }

    @Test
    @DisplayName("no branch reached the item: the merge is SKIPPED and its body never runs")
    void mergeNoBranchReachedIsSkippedInsteadOfExecuted() {
        everyBranchSkippedForSpamItem();

        StepByStepExecutionResult result = engine.executeSingleNode(
                MERGE_ID, tree, context, execution, eventService, null);

        assertThat(result.nodeResult().status()).isEqualTo(NodeStatus.SKIPPED);
        assertThat(result.nodeResult().errorMessage()).contains(MergeReachabilityGuard.SKIP_REASON);
        // Pre-fix this dispatched to the split-aware executor, which ran the agent.
        verify(splitAwareExecutor, never())
                .execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("no branch reached the item: the skip cascades so the chain below gets rows too")
    void unreachableMergeCascadesSkipToItsChain() {
        everyBranchSkippedForSpamItem();

        engine.executeSingleNode(MERGE_ID, tree, context, execution, eventService, null);

        // prep_draft / approve_reply / send_reply had NO row in epoch 152; the cascade is
        // what gives them one instead of leaving the epoch silently incomplete.
        verify(skipPropagationService).cascadeFailureToSuccessors(
                eq(execution), eq(draftReply), eq(SPAM_ITEM), eq(EPOCH), eq(TRIGGER_ID),
                eq(false), eq(V2SkipPropagationService.SOURCE_SYNC));
    }

    @Test
    @DisplayName("no branch reached the item: the node still emits start then complete")
    void unreachableMergeStillEmitsTheNodeLifecycle() {
        everyBranchSkippedForSpamItem();

        engine.executeSingleNode(MERGE_ID, tree, context, execution, eventService, null);

        verify(eventService).emitNodeStart(eq(execution), eq(draftReply), any(), eq(SPAM_ITEM), eq(EPOCH));
        verify(eventService).emitNodeComplete(
                eq(execution), eq(draftReply), any(NodeExecutionResult.class), any(), eq(SPAM_ITEM), any());
    }

    @Test
    @DisplayName("one branch did reach the item: the merge executes exactly as before")
    void mergeWithOneLiveBranchStillExecutes() {
        when(stepDataRepository.findTerminalStatusesForItem(eq(RUN_ID), any(), eq(EPOCH), eq(SPAM_ITEM)))
                .thenReturn(List.of(
                        new Object[]{"core:move_clients", "COMPLETED"},
                        new Object[]{"core:move_prospects", "SKIPPED"},
                        new Object[]{"core:move_partenaires", "SKIPPED"}));

        StepByStepExecutionResult result = engine.executeSingleNode(
                MERGE_ID, tree, context, execution, eventService, null);

        assertThat(result.nodeResult().status()).isEqualTo(NodeStatus.COMPLETED);
        verify(splitAwareExecutor).execute(
                any(), any(), eq(RUN_ID), any(), eq(execution), any(), eq(SPAM_ITEM), eq(null));
        verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), anyString(), anyBoolean(), anyString());
    }

    /**
     * The same gate on the AUTO traversal path ({@code traverseTree} to
     * {@code executeNodeCore}). The prod occurrences all came through step-by-step, but an
     * approval inside a split is just as legal in an AUTOMATIC run, and a gate applied on
     * one dispatch path only is the shape half these engine defects have taken.
     */
    @Nested
    @DisplayName("AUTO traversal path")
    class AutoTraversal {

        private BaseNode mergeNode(AtomicInteger executions) {
            BaseNode node = mock(BaseNode.class);
            when(node.getNodeId()).thenReturn(MERGE_ID);
            when(node.getType()).thenReturn(NodeType.AGENT);
            when(node.canExecute(any())).thenReturn(true);
            when(node.getSuccessors()).thenReturn(List.of());
            when(node.getNextNodes(any())).thenReturn(List.of());
            when(node.getPredecessorIds()).thenReturn(REPLY_MOVES);
            when(node.execute(any())).thenAnswer(invocation -> {
                executions.incrementAndGet();
                return NodeExecutionResult.success(MERGE_ID, Map.of("ok", true));
            });
            return node;
        }

        @BeforeEach
        void delegateSplitAwareToTheNodeBody() {
            when(splitAwareExecutor.execute(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                    .thenAnswer(invocation -> {
                        BaseNode node = invocation.getArgument(0);
                        return node.execute(invocation.getArgument(1));
                    });
        }

        @Test
        @DisplayName("no branch reached the item: the merge body never runs and the node is SKIPPED")
        void unreachableMergeIsNotExecutedInAutoMode() {
            everyBranchSkippedForSpamItem();
            AtomicInteger executions = new AtomicInteger();
            BaseNode merge = mergeNode(executions);

            ExecutionContext result = engine.traverseTree(
                    merge, context, execution, eventService, new TriggerItem("2", SPAM_ITEM, Map.of()));

            assertThat(executions.get()).as("the merge body must not run").isZero();
            assertThat(result.isSkipped(MERGE_ID)).isTrue();
        }

        @Test
        @DisplayName("one branch did reach the item: the merge body runs exactly as before")
        void reachableMergeStillExecutesInAutoMode() {
            when(stepDataRepository.findTerminalStatusesForItem(eq(RUN_ID), any(), eq(EPOCH), eq(SPAM_ITEM)))
                    .thenReturn(List.of(
                            new Object[]{"core:move_clients", "COMPLETED"},
                            new Object[]{"core:move_prospects", "SKIPPED"},
                            new Object[]{"core:move_partenaires", "SKIPPED"}));
            AtomicInteger executions = new AtomicInteger();
            BaseNode merge = mergeNode(executions);

            ExecutionContext result = engine.traverseTree(
                    merge, context, execution, eventService, new TriggerItem("2", SPAM_ITEM, Map.of()));

            assertThat(executions.get()).isEqualTo(1);
            assertThat(result.isSuccess(MERGE_ID)).isTrue();
        }
    }

    /**
     * Prod run {@code run_<id>} epoch 5: the merge was dispatched ONCE for a whole
     * split, under the workflow item index. The gate read that index as a split item, found every
     * branch SKIPPED for split item 0, and skipped the merge for all eight items. When the dispatch
     * spans every split item the verdict of that one item says nothing about the others, so the gate
     * must stand aside and let the split's own per-item routing decide.
     */
    @Nested
    @DisplayName("dispatch that spans every item of a split")
    class DispatchSpanningEverySplitItem {

        private BaseNode explicitMerge() {
            BaseNode node = mock(BaseNode.class);
            when(node.getNodeId()).thenReturn(MERGE_ID);
            when(node.getType()).thenReturn(NodeType.MERGE);
            when(node.isMergeNode()).thenReturn(true);
            when(node.canExecute(any())).thenReturn(true);
            when(node.getSuccessors()).thenReturn(List.of());
            when(node.getNextNodes(any())).thenReturn(List.of());
            when(node.getPredecessorIds()).thenReturn(REPLY_MOVES);
            return node;
        }

        @Test
        @DisplayName("step-by-step: split item 0 unreached no longer skips the fan-out, the split-aware executor runs it")
        void stepByStepFanOutIsNotSkippedByTheItemZeroVerdict() {
            everyBranchSkippedForSpamItem();
            when(splitAwareExecutor.executesForEverySplitItem(
                    eq(draftReply), eq(RUN_ID), eq(SPAM_ITEM), any(), eq(true))).thenReturn(true);

            StepByStepExecutionResult result = engine.executeSingleNode(
                    MERGE_ID, tree, context, execution, eventService, null);

            // Pre-fix: SKIPPED "All predecessors were skipped for this item", body never reached.
            assertThat(result.nodeResult().status()).isEqualTo(NodeStatus.COMPLETED);
            verify(splitAwareExecutor).execute(
                    any(), any(), eq(RUN_ID), any(), eq(execution), any(), eq(SPAM_ITEM), eq(null));
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                    any(), any(), anyInt(), anyInt(), anyString(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("step-by-step: a split-aggregation merge (N to 1) is handed to the split merge handler, not skipped")
        void stepByStepSplitAggregationMergeIsNotSkipped() {
            everyBranchSkippedForSpamItem();
            BaseNode merge = explicitMerge();
            when(nodeSearchService.findNodeFromAllRoots(tree, MERGE_ID)).thenReturn(merge);
            when(nodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(Map.of(MERGE_ID, merge));
            when(splitMergeHandler.isSplitMerge(eq(RUN_ID), eq(MERGE_ID), eq(SPAM_ITEM), any())).thenReturn(true);
            when(splitMergeHandler.handleMerge(eq(RUN_ID), eq(MERGE_ID), eq(SPAM_ITEM), any(), any()))
                    .thenReturn(NodeExecutionResult.success(MERGE_ID, Map.of("split_merge", true)));

            StepByStepExecutionResult result = engine.executeSingleNode(
                    MERGE_ID, tree, context, execution, eventService, null);

            assertThat(result.nodeResult().status()).isEqualTo(NodeStatus.COMPLETED);
            verify(splitMergeHandler).handleMerge(eq(RUN_ID), eq(MERGE_ID), eq(SPAM_ITEM), any(), any());
        }

        @Test
        @DisplayName("AUTO: when the split-aware executor fans the merge out (e.g. direct split successor), it runs; asked with stepByStep=false and the singleton node map")
        void autoFanOutIsNotSkippedByTheItemZeroVerdict() {
            everyBranchSkippedForSpamItem();
            BaseNode merge = explicitMerge();
            when(splitContextManager.hasContexts(RUN_ID)).thenReturn(true);
            when(splitAwareExecutor.executesForEverySplitItem(
                    eq(merge), eq(RUN_ID), eq(SPAM_ITEM), eq(Map.of(MERGE_ID, merge)), eq(false))).thenReturn(true);
            when(splitAwareExecutor.execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(NodeExecutionResult.success(MERGE_ID, Map.of("ok", true)));

            ExecutionContext result = engine.traverseTree(
                    merge, context, execution, eventService, new TriggerItem("2", SPAM_ITEM, Map.of()));

            assertThat(result.isSuccess(MERGE_ID)).isTrue();
            verify(splitAwareExecutor).execute(
                    eq(merge), any(), eq(RUN_ID), any(), eq(execution), any(), eq(SPAM_ITEM), any());
        }

        private BaseNode multiInputAggregate() {
            BaseNode node = mock(BaseNode.class);
            when(node.getNodeId()).thenReturn(MERGE_ID);
            when(node.getType()).thenReturn(NodeType.AGGREGATE);
            when(node.isAggregateNode()).thenReturn(true);
            when(node.canExecute(any())).thenReturn(true);
            when(node.getSuccessors()).thenReturn(List.of());
            when(node.getNextNodes(any())).thenReturn(List.of());
            when(node.getPredecessorIds()).thenReturn(REPLY_MOVES);
            return node;
        }

        @Test
        @DisplayName("step-by-step: a split aggregate (N to 1) is handed to the split aggregate handler, not skipped")
        void stepByStepSplitAggregateIsNotSkipped() {
            everyBranchSkippedForSpamItem();
            BaseNode aggregate = multiInputAggregate();
            when(nodeSearchService.findNodeFromAllRoots(tree, MERGE_ID)).thenReturn(aggregate);
            when(nodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(Map.of(MERGE_ID, aggregate));
            when(splitAggregateHandler.isSplitAggregate(eq(RUN_ID), eq(MERGE_ID), eq(SPAM_ITEM), any())).thenReturn(true);
            when(splitAggregateHandler.handleAggregate(eq(RUN_ID), eq(MERGE_ID), eq(SPAM_ITEM), any(), any()))
                    .thenReturn(NodeExecutionResult.success(MERGE_ID, Map.of("items", List.of())));

            StepByStepExecutionResult result = engine.executeSingleNode(
                    MERGE_ID, tree, context, execution, eventService, null);

            assertThat(result.nodeResult().status()).isEqualTo(NodeStatus.COMPLETED);
            verify(splitAggregateHandler).handleAggregate(eq(RUN_ID), eq(MERGE_ID), eq(SPAM_ITEM), any(), any());
        }

        @Test
        @DisplayName("AUTO: an aggregate outside any split runs its body once, so the gate keeps its SKIPPED")
        void autoNonSplitAggregateKeepsTheGate() {
            everyBranchSkippedForSpamItem();
            AtomicInteger executions = new AtomicInteger();
            BaseNode aggregate = multiInputAggregate();
            when(aggregate.execute(any())).thenAnswer(invocation -> {
                executions.incrementAndGet();
                return NodeExecutionResult.success(MERGE_ID, Map.of());
            });

            ExecutionContext result = engine.traverseTree(
                    aggregate, context, execution, eventService, new TriggerItem("2", SPAM_ITEM, Map.of()));

            assertThat(executions.get()).isZero();
            assertThat(result.isSkipped(MERGE_ID)).isTrue();
            // AUTO never fans an aggregate out through the split-aware executor, so it is not asked.
            verify(splitAwareExecutor, never()).executesForEverySplitItem(any(), any(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("a single-item dispatch keeps the gate: the executor says no fan-out, the merge is SKIPPED")
        void singleItemDispatchKeepsTheGate() {
            everyBranchSkippedForSpamItem();
            // Default answer of the mock, stated for the reader: the approval-resume shape, where
            // the dispatch runs the node for the one held item only.
            when(splitAwareExecutor.executesForEverySplitItem(any(), any(), anyInt(), any(), anyBoolean()))
                    .thenReturn(false);

            StepByStepExecutionResult result = engine.executeSingleNode(
                    MERGE_ID, tree, context, execution, eventService, null);

            assertThat(result.nodeResult().status()).isEqualTo(NodeStatus.SKIPPED);
            verify(splitAwareExecutor, never())
                    .execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any());
        }
    }

    @Test
    @DisplayName("a predecessor that has not spoken yet leaves the merge executing, never mass-skipped")
    void silentPredecessorLeavesTheMergeExecuting() {
        when(stepDataRepository.findTerminalStatusesForItem(eq(RUN_ID), any(), eq(EPOCH), eq(SPAM_ITEM)))
                .thenReturn(List.of(
                        new Object[]{"core:move_clients", "SKIPPED"},
                        new Object[]{"core:move_prospects", "SKIPPED"}));

        StepByStepExecutionResult result = engine.executeSingleNode(
                MERGE_ID, tree, context, execution, eventService, null);

        assertThat(result.nodeResult().status()).isEqualTo(NodeStatus.COMPLETED);
    }
}

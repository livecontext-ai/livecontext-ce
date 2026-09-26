package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.lifecycle.V2WorkflowFinalizer;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.TriggerNode;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2AutoScheduler;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.ReadyNodeCalculator;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextRehydrator;
import com.apimarketplace.orchestrator.execution.v2.split.SplitMergeHandler;
import com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * Wiring of {@link SplitContextRehydrator} into {@code UnifiedExecutionEngine.executeSingleNode}, the
 * method every resume (signal, async delivery, step-by-step request) goes through. The context must
 * be rebuilt BEFORE any split-scope dispatch reads memory: prod 2026-09-26 (run
 * {@code run_<id>} epoch 51) an aggregate that ran on the pod that had not run the
 * split collapsed 5 items into 1.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UnifiedExecutionEngine - split context rebuilt before dispatch on a resume")
class UnifiedExecutionEngineSplitRehydrateTest {

    private static final String RUN_ID = "run-xpod";
    private static final String TENANT = "tenant-1";
    private static final String TRIGGER_ID = "trigger:start";

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

    @Mock private SplitContextRehydrator rehydrator;
    @Mock private ExecutionTree tree;
    @Mock private WorkflowExecution execution;
    @Mock private V2ExecutionEventService eventService;
    @Mock private WorkflowPlan plan;

    private UnifiedExecutionEngine engine;
    private ExecutionContext context;
    private Map<String, ExecutionNode> nodeMap;

    @BeforeEach
    void setUp() {
        engine = new UnifiedExecutionEngine(
                workflowFinalizer, autoScheduler, stepByStepScheduler, readyNodeCalculator,
                backEdgeHandler, splitNodeExecutor, splitAwareExecutor, splitMergeHandler,
                splitAggregateHandler, splitContextManager, nodeSearchService,
                skipPropagationService, creditBudgetService);

        TriggerNode node = new TriggerNode(TRIGGER_ID,
                new Trigger(TRIGGER_ID, "start", "single", "manual", Map.of()));
        context = ExecutionContext.create(RUN_ID, "wf-run-1", TENANT, "0", 0, TRIGGER_ID, 51, 0, Map.of(), plan);
        nodeMap = Map.of(TRIGGER_ID, node);

        when(tree.getRunId()).thenReturn(RUN_ID);
        when(nodeSearchService.findNodeFromAllRoots(tree, TRIGGER_ID)).thenReturn(node);
        when(nodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(nodeMap);
        when(readyNodeCalculator.calculateReadyNodes(any(), any())).thenReturn(Set.of());
        when(backEdgeHandler.hasBackEdge(any(), any())).thenReturn(false);
        when(splitAwareExecutor.execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(NodeExecutionResult.success(TRIGGER_ID, Map.of("ok", true)));
    }

    @Test
    @DisplayName("the split context is ensured with the node, item index, full node map and the resume epoch, before the node is dispatched")
    void rehydratesBeforeDispatch() {
        engine.setSplitContextRehydrator(rehydrator);

        engine.executeSingleNode(TRIGGER_ID, tree, context, execution, eventService, null);

        InOrder order = inOrder(rehydrator, splitAwareExecutor);
        order.verify(rehydrator).ensureContext(eq(RUN_ID), eq(TRIGGER_ID), eq(0), eq(nodeMap),
                argThat(c -> c.epoch() == 51 && TENANT.equals(c.tenantId())));
        order.verify(splitAwareExecutor).execute(any(), any(), anyString(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("no rehydrator wired (plain construction): the node still executes unchanged")
    void noRehydratorStillExecutes() {
        StepByStepExecutionResult result = engine.executeSingleNode(
                TRIGGER_ID, tree, context, execution, eventService, null);

        assertThat(result.nodeResult().status()).isEqualTo(NodeStatus.COMPLETED);
    }

    @Test
    @DisplayName("prod shape: aggregate executed on a pod that never ran the split - by the time the dispatch asks isSplitAggregate, the context is in memory")
    void aggregateDispatchSeesTheRebuiltContext() {
        SplitContextManager podB = new SplitContextManager();
        com.apimarketplace.orchestrator.services.StepOutputService store =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
        when(store.loadPerItemNodeOutputs(RUN_ID, "core:x_segments", 51, TENANT))
                .thenReturn(Map.of(0, Map.of("items", java.util.List.of("s0", "s1", "s2", "s3", "s4"))));
        engine.setSplitContextRehydrator(new SplitContextRehydrator(podB, store));

        ExecutionNode split = org.mockito.Mockito.mock(ExecutionNode.class);
        when(split.isSplitNode()).thenReturn(true);
        ExecutionNode append = org.mockito.Mockito.mock(ExecutionNode.class);
        when(append.getPredecessorIds()).thenReturn(java.util.List.of("core:x_segments"));
        ExecutionNode aggregate = org.mockito.Mockito.mock(ExecutionNode.class);
        when(aggregate.isAggregateNode()).thenReturn(true);
        when(aggregate.getNodeId()).thenReturn("core:x_collect");
        when(aggregate.canExecute(any())).thenReturn(true);
        when(aggregate.getPredecessorIds()).thenReturn(java.util.List.of("mcp:x_append"));
        Map<String, ExecutionNode> graph = Map.of(
                "core:x_segments", split, "mcp:x_append", append, "core:x_collect", aggregate);
        when(nodeSearchService.findNodeFromAllRoots(tree, "core:x_collect")).thenReturn(aggregate);
        when(nodeSearchService.buildNodeMapFromAllRoots(tree)).thenReturn(graph);

        boolean[] contextPresentAtDispatch = {false};
        when(splitAggregateHandler.isSplitAggregate(eq(RUN_ID), eq("core:x_collect"), eq(0), any()))
                .thenAnswer(inv -> {
                    contextPresentAtDispatch[0] = podB.getContext(RUN_ID, "core:x_segments", 0)
                            .map(c -> c.itemCount() == 5 && c.epoch() == 51).orElse(false);
                    return true;
                });
        when(splitAggregateHandler.handleAggregate(eq(RUN_ID), eq("core:x_collect"), eq(0), any(), any()))
                .thenReturn(NodeExecutionResult.success("core:x_collect", Map.of("aggregated_count", 5)));

        engine.executeSingleNode("core:x_collect", tree, context, execution, eventService, null);

        assertThat(contextPresentAtDispatch[0])
                .as("pre-fix pod B had no context here and the aggregate ran as a plain aggregate over item 0")
                .isTrue();
    }
}

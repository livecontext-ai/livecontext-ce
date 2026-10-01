package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.nodes.AgentNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code nodePolicy.timeoutMs} never bounds the DISPATCH of an agent node on the worker queue.
 *
 * <p>Its body only sends the request and returns ASYNC_RUNNING; the timeout bounds the ANSWER
 * instead ({@code AgentAttemptScheduler}). REGRESSION (e2e PROD-001.6, 2026-09-29, timeoutMs=1):
 * the executor bounded the dispatch too, abandoned it half-done, and the abandoned body registered
 * a request that was never sent. That kept the epoch open (the run stayed RUNNING) until the
 * recovery hard timeout, 130 minutes later.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SplitAwareNodeExecutor - timeoutMs bounds a queued agent's answer, never its dispatch")
@Timeout(30)
class SplitAwareNodeExecutorQueuedAgentTimeoutTest {

    private static final String RUN_ID = "run1";
    private static final String NODE_ID = "agent:writer";

    @Mock private SplitContextManager contextManager;
    @Mock private NodeCompletionService nodeCompletionService;
    @Mock private WorkflowStepDataRepository stepDataRepository;
    @Mock private ExecutionContext context;
    @Mock private WorkflowExecution execution;

    private SplitAwareNodeExecutor executor;
    private final Map<String, ExecutionNode> nodeMap = new HashMap<>();

    @BeforeEach
    void setUp() {
        executor = new SplitAwareNodeExecutor(contextManager, nodeCompletionService, null, null,
            stepDataRepository, null, Executors.newFixedThreadPool(2));
        when(contextManager.findActiveContext(eq(RUN_ID), eq(NODE_ID), anyInt(), any())).thenReturn(Optional.empty());
        lenient().when(contextManager.getAllContexts(RUN_ID)).thenReturn(Map.of());
        // A 50 ms bound on the node, whose dispatch takes 300 ms.
        when(context.plan()).thenReturn(new WorkflowPlan("11111111-1111-1111-1111-111111111111", "tenant-1",
            null, null, null, null, null, null, null, null,
            Map.of(NODE_ID, new NodePolicy(0, 0L, false, 50L, false)), Map.of()));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private AgentNode agentWhoseDispatchTakes300ms(boolean answersFromWorkerQueue) {
        AgentNode node = mock(AgentNode.class);
        when(node.getNodeId()).thenReturn(NODE_ID);
        when(node.getType()).thenReturn(NodeType.AGENT);
        when(node.getPredecessorIds()).thenReturn(List.of("trigger:start"));
        when(node.answersFromWorkerQueue()).thenReturn(answersFromWorkerQueue);
        when(node.execute(any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return NodeExecutionResult.asyncRunning(NODE_ID, "cid-1", "agent", new HashMap<>(Map.of("async", true)));
        });
        nodeMap.put(NODE_ID, node);
        return node;
    }

    @Test
    @DisplayName("REGRESSION: a queued agent's slow dispatch completes and yields ASYNC_RUNNING, it is not cut by timeoutMs")
    void queuedAgentDispatchIsNotBounded() {
        AgentNode node = agentWhoseDispatchTakes300ms(true);

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap, execution,
            new TriggerItem("item-0", 0, Map.of()), 0, null);

        assertThat(result.isAsyncRunning()).as("the request left; the answer is bounded later").isTrue();
        assertThat(result.output()).doesNotContainKey(ExecutionMetadataKeys.POLICY_TIMEOUT);
    }

    @Test
    @DisplayName("REGRESSION: in a split, every item's slow dispatch completes and yields; the barrier waits for every item")
    void queuedAgentItemsAreNotBounded() {
        AgentNode node = agentWhoseDispatchTakes300ms(true);
        when(node.getPredecessorIds()).thenReturn(List.of("core:split1"));
        nodeMap.put("core:split1", mock(ExecutionNode.class));
        ExecutionContext splitRun = ExecutionContext.create("run-split", "wfr-1", "tenant-1", "0", 0,
            "trigger:start", 2, 0, new HashMap<>(), new WorkflowPlan("11111111-1111-1111-1111-111111111111",
                "tenant-1", null, null, null, null, null, null, null, null,
                Map.of(NODE_ID, new NodePolicy(0, 0L, false, 50L, false)), Map.of()));
        when(contextManager.findActiveContext(eq("run-split"), eq(NODE_ID), eq(0), any()))
            .thenReturn(Optional.of(SplitContext.create("core:split1:0", List.of("a", "b"))));
        com.apimarketplace.orchestrator.execution.v2.async.SplitCoalesceTracker tracker =
            mock(com.apimarketplace.orchestrator.execution.v2.async.SplitCoalesceTracker.class);
        com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker running =
            mock(com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker.class);
        SplitAwareNodeExecutor splitExecutor = new SplitAwareNodeExecutor(contextManager, null, null,
            mock(com.apimarketplace.orchestrator.services.streaming.SnapshotService.class), null, null,
            Executors.newFixedThreadPool(2));
        splitExecutor.setSplitCoalesceTracker(tracker);
        splitExecutor.setRunningNodeTracker(running);

        try {
            splitExecutor.execute(node, splitRun, "run-split", nodeMap, execution, null, 0, null);

            // Before the fix each item's dispatch timed out, nothing yielded, and no barrier was set.
            org.mockito.Mockito.verify(tracker).register("run-split", NODE_ID, 2, 2);
            org.mockito.Mockito.verify(running).setRunningCount("run-split", 2, NODE_ID, 2);
        } finally {
            splitExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("an agent that does not use the worker queue runs its whole body inline, so the bound still applies to it")
    void inlineAgentIsStillBounded() {
        AgentNode node = agentWhoseDispatchTakes300ms(false);

        NodeExecutionResult result = executor.execute(node, context, RUN_ID, nodeMap, execution,
            new TriggerItem("item-0", 0, Map.of()), 0, null);

        assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(result.output()).containsEntry(ExecutionMetadataKeys.POLICY_TIMEOUT, true);
    }
}

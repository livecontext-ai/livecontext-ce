package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepContextManager;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionContext;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.resume.ExecutionContextManager;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code continueOnFailure} on an agent node that ran on the async queue.
 *
 * <p>The async delivery never passes through NodePolicyRunner (the node yielded), so before
 * 2026-09-29 a failed async agent always cascaded SKIPPED to every node below, whatever its policy.
 * The delivery now stamps the flag itself from the plan policy the runner would have read: the flag
 * is persisted with the row (output) and carried on the node result (metadata), the SKIPPED cascade
 * is not run, and inside a split a continued item dispatches the successors like a success.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentAsyncCompletionService - continueOnFailure on an async agent node")
class AgentAsyncCompletionContinueOnFailureTest {

    private static final String NODE = "agent:analyze";

    @Mock private PendingAgentRegistry registry;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private SplitContextManager splitContextManager;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private SplitCoalesceTracker splitCoalesceTracker;
    @Mock private NodeSearchService nodeSearchService;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private V2StepByStepService v2StepByStepService;
    @Mock private WorkflowResumeService workflowResumeService;
    @Mock private ExecutionContextManager executionContextManager;
    @Mock private V2ExecutionEventService v2ExecutionEventService;
    @Mock private V2StepByStepContextManager v2StepByStepContextManager;
    @Mock private V2SkipPropagationService skipPropagationService;
    @Mock private com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService signalResumeService;
    @Mock private ExecutionTree tree;
    @Mock private ExecutionNode agentNode;
    @Mock private WorkflowRunEntity runEntity;
    @Mock private WorkflowExecution execution;
    @Mock private WorkflowPlan plan;

    private AgentAsyncCompletionService service;

    @BeforeEach
    void setUp() {
        service = new AgentAsyncCompletionService(
            registry, stepCompletionOrchestrator, splitContextManager,
            runningNodeTracker, splitCoalesceTracker, nodeSearchService, runRepository);
        ReflectionTestUtils.setField(service, "v2StepByStepService", v2StepByStepService);
        ReflectionTestUtils.setField(service, "workflowResumeService", workflowResumeService);
        ReflectionTestUtils.setField(service, "executionContextManager", executionContextManager);
        ReflectionTestUtils.setField(service, "v2ExecutionEventService", v2ExecutionEventService);
        ReflectionTestUtils.setField(service, "v2StepByStepContextManager", v2StepByStepContextManager);
        ReflectionTestUtils.setField(service, "signalResumeService", signalResumeService);
        ReflectionTestUtils.setField(service, "skipPropagationService", skipPropagationService);
        ReflectionTestUtils.setField(service, "perItemTraversalEnabled", true);

        when(execution.getPlan()).thenReturn(plan);
        when(v2StepByStepContextManager.getTree(anyString())).thenReturn(tree);
        when(nodeSearchService.findNodeFromAllRoots(tree, NODE)).thenReturn(agentNode);
        when(agentNode.getNodeId()).thenReturn(NODE);
    }

    private void policy(boolean continueOnFailure) {
        when(plan.getNodePolicy(NODE)).thenReturn(new NodePolicy(0, 0L, continueOnFailure));
    }

    @Nested
    @DisplayName("outside a split")
    class NonSplit {

        private void primeDelivery(String correlationId) {
            PendingAgent pending = new PendingAgent(
                correlationId, "run-1", NODE, "Analyze",
                "trigger:cron", 2, 0, "0", "agent",
                "tenant-1", null, null, null, null, null, null, null, null, Instant.now());
            when(registry.consume(correlationId)).thenReturn(Optional.of(pending));
            WorkflowRunState state = mock(WorkflowRunState.class);
            when(workflowResumeService.reconstructState(anyString())).thenReturn(state);
            when(executionContextManager.rebuildExecutionContext(anyString(), any())).thenReturn(execution);
            when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.of(runEntity));
            when(runEntity.getStatus()).thenReturn(RunStatus.RUNNING);
        }

        private AgentResultMessage failure(String correlationId) {
            return new AgentResultMessage(correlationId, "run-1", NODE, null,
                false, "Provider timeout (600s)", "agent", Instant.now());
        }

        @Test
        @DisplayName("REGRESSION: a failed async agent whose policy continues is persisted flagged, emitted flagged, and does NOT cascade SKIPPED")
        void continuedAsyncFailureIsFlaggedAndNotCascaded() {
            policy(true);
            primeDelivery("corr-continue");

            boolean delivered = service.onAgentResult(failure("corr-continue"));

            assertThat(delivered).as("the delivery completes, it is not re-queued").isTrue();
            verify(registry, never()).register(any());
            // Persisted with the row: the flag is in the stored OUTPUT (what a context rebuild reads).
            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().status()).isEqualTo(NodeStatus.FAILED);
            assertThat(persisted.getValue().result().output())
                .containsEntry(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true);
            // Emitted downstream: the node result carries it in METADATA (the edge emitter reads it).
            ArgumentCaptor<NodeExecutionResult> emitted = ArgumentCaptor.forClass(NodeExecutionResult.class);
            verify(v2ExecutionEventService).emitPostPersistenceCompletion(
                eq(execution), eq(agentNode), emitted.capture(), eq(0), isNull(), eq(2), eq("trigger:cron"));
            assertThat(emitted.getValue().isFailure()).isTrue();
            assertThat(emitted.getValue().metadata())
                .containsEntry(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true);
            // And the node below is not skipped for it.
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("without continueOnFailure the async failure is unflagged and cascades SKIPPED (legacy kept)")
        void uncontinuedAsyncFailureCascades() {
            policy(false);
            primeDelivery("corr-plain");

            service.onAgentResult(failure("corr-plain"));

            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().output())
                .doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
            verify(skipPropagationService).cascadeFailureToSuccessors(
                any(), eq(agentNode), eq(0), eq(2), eq("trigger:cron"), eq(false),
                eq(V2SkipPropagationService.SOURCE_ASYNC));
        }

        @Test
        @DisplayName("a SUCCESS whose output could not be stored (payload lost) is an unflagged failure: it cascades even on a continuing node")
        void payloadLostSuccessIsNotContinued() {
            // The flag is stamped only on a failure the AGENT returned. A platform storage loss is
            // not the node failing, and the split summary treats it the same way (unflagged item).
            policy(true);
            primeDelivery("corr-lost");
            when(stepCompletionOrchestrator.complete(any(), eq("trigger:cron")))
                .thenReturn(com.apimarketplace.orchestrator.services.completion.StepCompletionResult
                    .persistedPayloadLost(Map.of(), Map.of(), "[storage] Output payload lost"));

            service.onAgentResult(new AgentResultMessage("corr-lost", "run-1", NODE, Map.of("ok", true),
                true, null, "agent", Instant.now()));

            verify(skipPropagationService).cascadeFailureToSuccessors(
                any(), eq(agentNode), eq(0), eq(2), eq("trigger:cron"), eq(false),
                eq(V2SkipPropagationService.SOURCE_ASYNC));
        }

        @Test
        @DisplayName("a SUCCESSFUL async agent on a continuing node is never flagged")
        void successIsNeverFlagged() {
            policy(true);
            primeDelivery("corr-ok");

            service.onAgentResult(new AgentResultMessage("corr-ok", "run-1", NODE, Map.of("ok", true),
                true, null, "agent", Instant.now()));

            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().output())
                .doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
        }
    }

    @Nested
    @DisplayName("inside a split (per-item traversal)")
    class PerItem {

        private PendingAgent splitAgent() {
            Map<String, Object> splitData = new HashMap<>();
            splitData.put("splitNodeId", "core:each");
            splitData.put("workflowItemIndex", 0);
            splitData.put("itemIndex", 1);
            splitData.put("items", List.of("a", "b"));
            return new PendingAgent(
                "corr-item", "run-1", NODE, "Analyze",
                "trigger:cron", 5, 1, "1", "agent",
                "tenant-1", splitData, null, null, null, null, null, null, null, Instant.now());
        }

        @SuppressWarnings("unchecked")
        private Set<String> traverse(List<IndexedNodeResult> batch) throws Exception {
            Method m = AgentAsyncCompletionService.class.getDeclaredMethod(
                "traverseSuccessorsPerItem",
                WorkflowExecution.class, PendingAgent.class, List.class,
                com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution.class);
            m.setAccessible(true);
            return (Set<String>) m.invoke(service, execution, splitAgent(), batch, null);
        }

        private ExecutionNode successor(String id) {
            ExecutionNode n = mock(ExecutionNode.class);
            when(n.getNodeId()).thenReturn(id);
            return n;
        }

        @Test
        @DisplayName("REGRESSION: a continued failed item dispatches the node's successors and gets no per-item cascade")
        void continuedItemDispatchesSuccessors() throws Exception {
            ExecutionNode after = successor("mcp:after");
            when(agentNode.getSuccessors()).thenReturn(List.of(after));
            // getNextNodes filters successors on a failure: the continuation must not rely on it.
            when(agentNode.getNextNodes(any())).thenReturn(List.of());
            NodeExecutionResult continued = new NodeExecutionResult(NODE, NodeStatus.FAILED, Map.of(),
                Optional.of("Provider timeout"), Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true), 5L);

            Set<String> dispatched = traverse(List.of(new IndexedNodeResult(1, continued)));

            assertThat(dispatched).containsExactly("mcp:after");
            verify(v2StepByStepService).executeNode(eq("run-1"), eq("mcp:after"), anyString(), eq(5), eq("trigger:cron"));
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("an unflagged failed item dispatches nothing and gets its per-item cascade (legacy kept)")
        void uncontinuedItemIsCascaded() throws Exception {
            ExecutionNode after = successor("mcp:after");
            when(agentNode.getSuccessors()).thenReturn(List.of(after));
            when(agentNode.getNextNodes(any())).thenReturn(List.of());

            Set<String> dispatched = traverse(List.of(
                new IndexedNodeResult(1, NodeExecutionResult.failure(NODE, "Provider timeout"))));

            assertThat(dispatched).isEmpty();
            verify(skipPropagationService).cascadeFailureToSuccessors(
                eq(execution), eq(agentNode), eq(1), eq(5), eq("trigger:cron"), eq(true),
                eq(V2SkipPropagationService.SOURCE_ASYNC));
        }
    }
}

package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepContextManager;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.resume.ExecutionContextManager;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the premature epoch-drain bug in the "fork -&gt; N parallel async agents
 * -&gt; merge" topology (the merge was never reached).
 *
 * <p><b>Root cause:</b> {@link PendingAgentRegistry#consume} GETDELs a completion's pending entry
 * at the very start of {@code onAgentResult}. When several fork-branch agents finish within the
 * same delivery window (e.g. all echoing the same prompt), their entries are all consumed before
 * the FIRST one's {@code deliverUnderLock} runs. The first delivery's drain check then saw an empty
 * registry, declared itself the last agent, and closed+pruned the epoch - destroying the state the
 * still-queued siblings AND the downstream merge needed, so the merge never fired.
 *
 * <p><b>Fix under test:</b> the drain check also consults {@link RedisInFlightStore} (staged at
 * consume, cleared after delivery) so consumed-but-not-yet-delivered siblings defer the reset until
 * the genuinely-last delivery. These tests fail on the pre-fix code (which only checked the registry).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentAsyncCompletionService - deferred-reset drain guard (fork -> N async agents -> merge)")
class AgentAsyncCompletionServiceDrainGuardTest {

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
    @Mock private SignalResumeService signalResumeService;
    @Mock private RedisInFlightStore inFlightStore;
    @Mock private ExecutionTree tree;
    @Mock private ExecutionNode agentNode;
    @Mock private WorkflowRunEntity runEntity;

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
        ReflectionTestUtils.setField(service, "inFlightStore", inFlightStore);
        ReflectionTestUtils.setField(service, "perItemTraversalEnabled", true);
    }

    /** Non-split fork-branch agent: run-1, node agent:analyze, trigger:ask, epoch 1, item "0". */
    private PendingAgent branchAgent(String correlationId) {
        return new PendingAgent(
            correlationId, "run-1", "agent:analyze", "Analyze",
            "trigger:ask", 1, 0, "0", "agent",
            "tenant-1", null, null, null, null, null, null, null, null, Instant.now());
    }

    private AgentResultMessage successResult(String correlationId) {
        return new AgentResultMessage(
            correlationId, "run-1", "agent:analyze", Map.of("ok", true),
            true, null, "agent", Instant.now());
    }

    private void primeRebuildAndRun() {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        WorkflowRunState state = mock(WorkflowRunState.class);
        when(workflowResumeService.reconstructState(anyString())).thenReturn(state);
        when(executionContextManager.rebuildExecutionContext(anyString(), any())).thenReturn(execution);
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.of(runEntity));
        when(runEntity.getStatus()).thenReturn(RunStatus.RUNNING);
        when(v2StepByStepContextManager.getTree("run-1")).thenReturn(tree);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:analyze")).thenReturn(agentNode);
        when(agentNode.getNodeId()).thenReturn("agent:analyze");
    }

    @Test
    @DisplayName("A sibling consumed-but-not-yet-delivered DEFERS the epoch reset (does not prune the epoch under the merge)")
    void siblingInFlightDefersDeferredReset() {
        when(registry.consume("corr-first")).thenReturn(Optional.of(branchAgent("corr-first")));
        // Registry already drained (all siblings GETDEL'd by their own onAgentResult), but a sibling
        // is still staged in the in-flight store awaiting its deliverUnderLock.
        when(registry.hasPendingFor("run-1", "trigger:ask", 1)).thenReturn(false);
        when(inFlightStore.hasOtherInFlightForEpoch("run-1", "trigger:ask", 1, "corr-first")).thenReturn(true);
        primeRebuildAndRun();

        service.onAgentResult(successResult("corr-first"));

        // Pre-fix: the empty registry made this the "last" agent and it closed the epoch. It MUST NOT.
        verify(signalResumeService, never()).performDeferredReset(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("The genuinely-last delivery (no siblings in flight, registry drained) performs the epoch reset")
    void lastDeliveryPerformsDeferredReset() {
        when(registry.consume("corr-last")).thenReturn(Optional.of(branchAgent("corr-last")));
        when(registry.hasPendingFor("run-1", "trigger:ask", 1)).thenReturn(false);
        when(inFlightStore.hasOtherInFlightForEpoch("run-1", "trigger:ask", 1, "corr-last")).thenReturn(false);
        primeRebuildAndRun();

        service.onAgentResult(successResult("corr-last"));

        verify(signalResumeService).performDeferredReset("run-1", "trigger:ask", 1);
    }

    @Test
    @DisplayName("Own in-flight stage is cleared BEFORE the sibling check (race-free last-one-closes; prevents symmetric double-defer)")
    void clearsOwnStageBeforeSiblingCheck() {
        when(registry.consume("corr-last")).thenReturn(Optional.of(branchAgent("corr-last")));
        when(registry.hasPendingFor("run-1", "trigger:ask", 1)).thenReturn(false);
        when(inFlightStore.hasOtherInFlightForEpoch("run-1", "trigger:ask", 1, "corr-last")).thenReturn(false);
        primeRebuildAndRun();

        service.onAgentResult(successResult("corr-last"));

        // The drain check must observe an in-flight store from which THIS delivery has already been
        // removed - otherwise two near-simultaneous last siblings each see the other and both defer,
        // leaving the epoch permanently un-closed. Clear(self) must therefore precede the sibling scan.
        InOrder inOrder = inOrder(inFlightStore);
        inOrder.verify(inFlightStore).clear("corr-last");
        inOrder.verify(inFlightStore).hasOtherInFlightForEpoch("run-1", "trigger:ask", 1, "corr-last");
    }

    @Test
    @DisplayName("A ready node still RUNNING (in-flight sibling) is skipped, never re-executed - kills the MAX_LOOP_ITERATIONS spin")
    void inFlightReadyNodeIsSkippedNotReExecuted() {
        when(registry.consume("corr-x")).thenReturn(Optional.of(branchAgent("corr-x")));
        primeRebuildAndRun();
        // Readiness surfaces a sibling branch that is not COMPLETED; the running tracker says it is
        // still executing (dispatched, awaiting its own async result).
        when(v2StepByStepService.getReadyNodes(eq("run-1"), eq("0"), eq(1), any()))
            .thenReturn(java.util.Set.of("agent:sibling"));
        when(v2StepByStepService.getReadyNodes("run-1", "0", 1))
            .thenReturn(java.util.Set.of("agent:sibling"));
        when(runningNodeTracker.getRunningCounts("run-1", 1))
            .thenReturn(java.util.Map.of("agent:sibling", 1));

        service.onAgentResult(successResult("corr-x"));

        // Pre-fix the loop re-dispatched the in-flight sibling every turn up to MAX_LOOP_ITERATIONS.
        // It must now be filtered out entirely - never handed to executeNode on any overload.
        verify(v2StepByStepService, never())
            .executeNode(anyString(), eq("agent:sibling"), anyString(), anyInt(), anyString(), any());
        verify(v2StepByStepService, never())
            .executeNode(anyString(), eq("agent:sibling"), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("A still-pending registry entry defers the reset (pre-existing behavior preserved)")
    void pendingRegistryDefersDeferredReset() {
        when(registry.consume("corr-early")).thenReturn(Optional.of(branchAgent("corr-early")));
        when(registry.hasPendingFor("run-1", "trigger:ask", 1)).thenReturn(true);
        primeRebuildAndRun();

        service.onAgentResult(successResult("corr-early"));

        verify(signalResumeService, never()).performDeferredReset(anyString(), anyString(), anyInt());
    }
}

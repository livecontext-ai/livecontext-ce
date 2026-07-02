package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepContextManager;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.context.ReadinessContextCache;
import com.apimarketplace.orchestrator.services.resume.ExecutionContextManager;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the "fork -&gt; N async agents -&gt; merge, merge never fires" bug.
 *
 * <p><b>Root cause:</b> {@link ReadinessContextCache} (500ms TTL, keyed by run/epoch/item/trigger,
 * NOT by the completing node) is invalidated by the inline completion path
 * ({@code NodeCompletionService.emitNodeComplete}) and the signal-resume path
 * ({@code SignalResumeService}) so the next readiness calculation sees fresh state. The async agent
 * completion path ({@link AgentAsyncCompletionService} -&gt; {@code StepCompletionOrchestrator.complete})
 * was the ONE completion path that never invalidated it.
 *
 * <p>When a fork dispatches parallel async agents that finish near-simultaneously (same prompt =&gt;
 * they complete within the cache TTL), sibling completions serialize under the per-run stripe lock;
 * the first populates the readiness context showing only itself completed, and the later ones reuse
 * that STALE context and never observe the siblings' just-persisted completions. The merge's
 * predecessor set is therefore never seen as fully COMPLETED, so the merge (and everything after it,
 * e.g. the interface node) is stranded with no error.
 *
 * <p><b>Fix under test:</b> {@code executeReadyNodesLoop} invalidates the run's readiness-cache
 * entries BEFORE computing ready nodes, so each async completion's ready-node calculation reflects
 * fresh post-persist state and the last sibling to complete drives the merge. These tests fail on
 * the pre-fix code (the invalidation call is absent).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentAsyncCompletionService - readiness-cache invalidation on async completion")
class AgentAsyncCompletionServiceReadinessInvalidationTest {

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
    @Mock private ReadinessContextCache readinessCache;
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
        ReflectionTestUtils.setField(service, "skipPropagationService", skipPropagationService);
        ReflectionTestUtils.setField(service, "readinessCache", readinessCache);
        ReflectionTestUtils.setField(service, "perItemTraversalEnabled", true);
    }

    /** PendingAgent for a plain (non-split) fork branch: run-1, node agent:analyze, epoch 2, item "0". */
    private PendingAgent nonSplitAgent(String correlationId) {
        return new PendingAgent(
            correlationId, "run-1", "agent:analyze", "Analyze",
            "trigger:cron", 2, 0, "0", "agent",
            "tenant-1", null, null, null, null, null, null, null, null, Instant.now());
    }

    private AgentResultMessage successResult(String correlationId) {
        return new AgentResultMessage(
            correlationId, "run-1", "agent:analyze", Map.of("ok", true),
            true, null, "agent", Instant.now());
    }

    private AgentResultMessage failureResult(String correlationId) {
        return new AgentResultMessage(
            correlationId, "run-1", "agent:analyze", null,
            false, "Provider timeout (600s)", "agent", Instant.now());
    }

    /** Stub the rebuild + running-run checks so deliverUnderLock reaches executeReadyNodesLoop. */
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
    @DisplayName("A successful async agent completion invalidates the run's readiness cache")
    void successfulAsyncCompletionInvalidatesReadinessCache() {
        when(registry.consume("corr-1")).thenReturn(Optional.of(nonSplitAgent("corr-1")));
        primeRebuildAndRun();

        service.onAgentResult(successResult("corr-1"));

        // Without this the next getReadyNodes for a sibling that completes within the cache TTL
        // reuses a stale context and the downstream merge is never seen ready.
        verify(readinessCache).invalidateRun("run-1");
    }

    @Test
    @DisplayName("Readiness cache is invalidated BEFORE ready nodes are computed (the ordering that fixes the stale read)")
    void invalidationHappensBeforeReadyNodeCalculation() {
        when(registry.consume("corr-2")).thenReturn(Optional.of(nonSplitAgent("corr-2")));
        primeRebuildAndRun();

        service.onAgentResult(successResult("corr-2"));

        // The invalidate MUST precede the readiness read; otherwise getReadyNodes serves the
        // stale cached context populated by an earlier sibling completion.
        InOrder inOrder = inOrder(readinessCache, v2StepByStepService);
        inOrder.verify(readinessCache).invalidateRun("run-1");
        inOrder.verify(v2StepByStepService).getReadyNodes(eq("run-1"), eq("0"), eq(2), any());
    }

    @Test
    @DisplayName("A FAILED async agent completion also invalidates readiness (the SKIPPED cascade mutates state)")
    void failedAsyncCompletionInvalidatesReadinessCache() {
        when(registry.consume("corr-3")).thenReturn(Optional.of(nonSplitAgent("corr-3")));
        primeRebuildAndRun();

        service.onAgentResult(failureResult("corr-3"));

        // A failed branch cascades SKIPPED to descendants before the readiness walk (a partial-skip
        // merge must still fire), so the stale-cache view would strand the merge here too.
        verify(readinessCache).invalidateRun("run-1");
    }

    @Test
    @DisplayName("Unwired readiness cache (null) degrades gracefully - delivery still succeeds")
    void nullReadinessCacheDoesNotBreakDelivery() {
        ReflectionTestUtils.setField(service, "readinessCache", null);
        when(registry.consume("corr-4")).thenReturn(Optional.of(nonSplitAgent("corr-4")));
        primeRebuildAndRun();

        assertDoesNotThrow(() -> service.onAgentResult(successResult("corr-4")));
    }
}

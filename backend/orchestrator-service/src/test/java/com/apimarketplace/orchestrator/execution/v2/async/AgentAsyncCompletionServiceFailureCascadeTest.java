package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
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
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.resume.ExecutionContextManager;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Async-side cascade-invocation contract tests.
 *
 * <p>Asserts that {@link AgentAsyncCompletionService} invokes
 * {@link V2SkipPropagationService#cascadeFailureToSuccessors} with the right parameters
 * at the right sites, with proper try/catch isolation, terminal-run short-circuit, and
 * no-op behaviour on terminal nodes. The cascade routine itself is tested separately
 * in {@code V2SkipPropagationServiceTest}.
 *
 * <p>Tests numbered against plan v4 / Étape 6.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentAsyncCompletionService - failure cascade invocation")
class AgentAsyncCompletionServiceFailureCascadeTest {

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
    @Mock private ExecutionNode failedNode;
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
        ReflectionTestUtils.setField(service, "perItemTraversalEnabled", true);
    }

    private PendingAgent nonSplitAgent(String correlationId) {
        return new PendingAgent(
            correlationId, "run-1", "agent:analyze", "Analyze",
            "trigger:cron", 2, 0, "0", "agent",
            "tenant-1", null, null, null, null, null, null, null, null, Instant.now());
    }

    private AgentResultMessage failureResult(String correlationId) {
        return new AgentResultMessage(
            correlationId, "run-1", "agent:analyze", null,
            false, "Provider timeout (600s)", "agent", Instant.now());
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
        // isRunTerminalOrStopped uses findByRunIdPublic (not findById).
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.of(runEntity));
        when(runEntity.getStatus()).thenReturn(RunStatus.RUNNING);
        when(v2StepByStepContextManager.getTree("run-1")).thenReturn(tree);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:analyze")).thenReturn(failedNode);
        when(failedNode.getNodeId()).thenReturn("agent:analyze");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2: non-split async fail cascades with perItemScope=false, source=async
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 2: non-split async fail invokes cascade with perItemScope=false, source=async")
    void nonSplitAsyncFailCascadesSkippedToLinearDescendants() {
        PendingAgent pending = nonSplitAgent("corr-2");
        when(registry.consume("corr-2")).thenReturn(Optional.of(pending));
        primeRebuildAndRun();

        service.onAgentResult(failureResult("corr-2"));

        // Cascade is invoked with perItemScope=false and SOURCE_ASYNC tag.
        verify(skipPropagationService).cascadeFailureToSuccessors(
            any(), eq(failedNode), eq(0), eq(2), eq("trigger:cron"),
            eq(false),
            eq(V2SkipPropagationService.SOURCE_ASYNC));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 9 (renamed from plan): cancellation guard short-circuits cascade
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 9: async fail on terminal/cancelled run does NOT invoke cascade")
    void asyncFailDuringRunCancellationShortCircuitsCascade() {
        PendingAgent pending = nonSplitAgent("corr-9");
        when(registry.consume("corr-9")).thenReturn(Optional.of(pending));
        // Run is CANCELLED - guard at deliverUnderLock:346 should fire BEFORE cascade.
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.of(runEntity));
        when(runEntity.getStatus()).thenReturn(RunStatus.CANCELLED);
        WorkflowExecution execution = mock(WorkflowExecution.class);
        WorkflowRunState state = mock(WorkflowRunState.class);
        when(workflowResumeService.reconstructState(anyString())).thenReturn(state);
        when(executionContextManager.rebuildExecutionContext(anyString(), any())).thenReturn(execution);

        service.onAgentResult(failureResult("corr-9"));

        // Cascade MUST NOT run on terminal runs.
        verify(skipPropagationService, never()).cascadeFailureToSuccessors(
            any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 10: WAITING_TRIGGER between-fires reusable trigger MUST cascade
    // (regression: Gmail Auto-Labeler run da7994c7, 2026-05-06)
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 10: late async fail on reusable-trigger run between fires (WAITING_TRIGGER + no cancel signal) DOES cascade")
    void lateResultOnReusableTriggerWaitingTriggerDrivesSuccessors() {
        // Wire the production guard with a Redis publisher that reports "no cancel signal"
        // - the Gmail Auto-Labeler scenario where resetForNextCycle has flipped status to
        // WAITING_TRIGGER but stopWorkflow was never called.
        var redisPublisher = mock(com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher.class);
        when(redisPublisher.isAgentCancelSignalSet(anyString())).thenReturn(false);
        var guard = new com.apimarketplace.orchestrator.services.resume.RunCancellationGuard(
            runRepository, redisPublisher);
        ReflectionTestUtils.setField(service, "runCancellationGuard", guard);

        PendingAgent pending = nonSplitAgent("corr-10");
        when(registry.consume("corr-10")).thenReturn(Optional.of(pending));
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.of(runEntity));
        when(runEntity.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
        WorkflowExecution execution = mock(WorkflowExecution.class);
        WorkflowRunState state = mock(WorkflowRunState.class);
        when(workflowResumeService.reconstructState(anyString())).thenReturn(state);
        when(executionContextManager.rebuildExecutionContext(anyString(), any())).thenReturn(execution);
        when(v2StepByStepContextManager.getTree("run-1")).thenReturn(tree);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:analyze")).thenReturn(failedNode);
        when(failedNode.getNodeId()).thenReturn("agent:analyze");

        service.onAgentResult(failureResult("corr-10"));

        // The guard recognizes WAITING_TRIGGER without cancel signal as alive - cascade MUST fire.
        verify(skipPropagationService).cascadeFailureToSuccessors(
            any(), eq(failedNode), eq(0), eq(2), eq("trigger:cron"),
            eq(false), eq(V2SkipPropagationService.SOURCE_ASYNC));
    }

    @Test
    @DisplayName("Test 10b: late async fail on stopped run (WAITING_TRIGGER + cancel signal SET) does NOT cascade")
    void lateResultOnStoppedRunSkipsCascade() {
        // Same status as test 10, but cancel signal IS set - i.e. stopWorkflow was called.
        // Late results must persist (already done before guard) but NOT drive successors.
        var redisPublisher = mock(com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher.class);
        when(redisPublisher.isAgentCancelSignalSet(anyString())).thenReturn(true);
        var guard = new com.apimarketplace.orchestrator.services.resume.RunCancellationGuard(
            runRepository, redisPublisher);
        ReflectionTestUtils.setField(service, "runCancellationGuard", guard);

        PendingAgent pending = nonSplitAgent("corr-10b");
        when(registry.consume("corr-10b")).thenReturn(Optional.of(pending));
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.of(runEntity));
        when(runEntity.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
        WorkflowExecution execution = mock(WorkflowExecution.class);
        WorkflowRunState state = mock(WorkflowRunState.class);
        when(workflowResumeService.reconstructState(anyString())).thenReturn(state);
        when(executionContextManager.rebuildExecutionContext(anyString(), any())).thenReturn(execution);

        service.onAgentResult(failureResult("corr-10b"));

        verify(skipPropagationService, never()).cascadeFailureToSuccessors(
            any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 11: cascade throwing exception does NOT strand downstream pipeline
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 11: cascade throwing does not strand RunningNodeTracker nor deferred reset")
    void cascadeThrowingExceptionDoesNotStrandRunningNodeTrackerNorDeferredReset() {
        PendingAgent pending = nonSplitAgent("corr-11");
        when(registry.consume("corr-11")).thenReturn(Optional.of(pending));
        primeRebuildAndRun();

        // Cascade misbehaves - NPE-style failure mid-iteration.
        doThrow(new RuntimeException("simulated cascade failure"))
            .when(skipPropagationService).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());

        // No exception propagates out of onAgentResult.
        boolean ok = service.onAgentResult(failureResult("corr-11"));

        // 1. RunningNodeTracker.markCompleted ran (decrement happens BEFORE cascade,
        //    deliverUnderLock:318) - already the case before this fix, but we lock it.
        // P2.3.1: writer flipped to per-epoch overload. epoch=2 from PendingAgent.
        verify(runningNodeTracker).markCompleted("run-1", 2, "agent:analyze");
        // 2. Cascade was attempted (and threw).
        verify(skipPropagationService).cascadeFailureToSuccessors(
            any(), any(), anyInt(), anyInt(), any(), anyBoolean(),
            eq(V2SkipPropagationService.SOURCE_ASYNC));
        // 3. The result is still considered delivered - no pending re-registration
        //    because the error was caught locally, not at the outer try/catch.
        verify(registry, never()).register(any());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 8: terminal node with zero successors - cascade is no-op
    // (verified at V2SkipPropagationService level - the routine handles empty
    //  getSuccessors() lists; here we just confirm the call still happens with
    //  the right params even when downstream is empty.)
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Test 8: async fail on node with zero successors invokes cascade (routine handles empty list)")
    void asyncFailOnTerminalNodeWithZeroSuccessorsInvokesCascadeRoutineWhichNoOps() {
        PendingAgent pending = nonSplitAgent("corr-8");
        when(registry.consume("corr-8")).thenReturn(Optional.of(pending));
        primeRebuildAndRun();
        when(failedNode.getSuccessors()).thenReturn(List.of()); // terminal node

        service.onAgentResult(failureResult("corr-8"));

        // Cascade is invoked even with no successors - the routine's job is to iterate.
        // V2SkipPropagationServiceTest covers the no-op branch.
        verify(skipPropagationService).cascadeFailureToSuccessors(
            any(), eq(failedNode), eq(0), eq(2), eq("trigger:cron"),
            eq(false),
            eq(V2SkipPropagationService.SOURCE_ASYNC));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Success path (regression): cascade is NOT invoked on success
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Async SUCCESS does NOT invoke cascade")
    void asyncSuccessDoesNotInvokeCascade() {
        PendingAgent pending = nonSplitAgent("corr-success");
        when(registry.consume("corr-success")).thenReturn(Optional.of(pending));
        primeRebuildAndRun();

        service.onAgentResult(successResult("corr-success"));

        verify(skipPropagationService, never()).cascadeFailureToSuccessors(
            any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Payload-lost rewrite (tier 2): a SUCCESS delivery whose output blob could
    // not be stored (orchestrator reported payloadLost, row flipped to FAILED)
    // must traverse EXACTLY like an async failure.
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Async SUCCESS with payloadLost persistence cascades SKIPPED like a failure")
    void asyncSuccessWithPayloadLostCascadesLikeFailure() {
        PendingAgent pending = nonSplitAgent("corr-lost");
        when(registry.consume("corr-lost")).thenReturn(Optional.of(pending));
        primeRebuildAndRun();
        when(stepCompletionOrchestrator.complete(any(), eq("trigger:cron")))
            .thenReturn(com.apimarketplace.orchestrator.services.completion.StepCompletionResult
                .persistedPayloadLost(Map.of(), Map.of(),
                    "[storage] Output payload lost: storage write failed after retries"));

        service.onAgentResult(successResult("corr-lost"));

        // Traversal truth: descendants are skip-cascaded exactly like an async failure.
        verify(skipPropagationService).cascadeFailureToSuccessors(
            any(), eq(failedNode), eq(0), eq(2), eq("trigger:cron"),
            eq(false), eq(V2SkipPropagationService.SOURCE_ASYNC));
    }

    @Test
    @DisplayName("Async SUCCESS with payloadLost does NOT advance the loop back-edge (success-only continuation)")
    void asyncSuccessWithPayloadLostDoesNotAdvanceLoopBackEdge() {
        PendingAgent pending = nonSplitAgent("corr-lost-loop");
        when(registry.consume("corr-lost-loop")).thenReturn(Optional.of(pending));
        primeRebuildAndRun();
        when(stepCompletionOrchestrator.complete(any(), eq("trigger:cron")))
            .thenReturn(com.apimarketplace.orchestrator.services.completion.StepCompletionResult
                .persistedPayloadLost(Map.of(), Map.of(),
                    "[storage] Output payload lost: storage quota exceeded - free space or raise the limit"));

        service.onAgentResult(successResult("corr-lost-loop"));

        verify(signalResumeService, never()).advanceLoopBackEdgeForAsyncCompletedNode(
            anyString(), any(), anyString(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("BEHAVIOUR GUARD: async SUCCESS with a NORMAL persisted completion does not cascade and DOES advance the loop back-edge")
    void asyncSuccessWithNormalPersistenceAdvancesLoopBackEdge() {
        PendingAgent pending = nonSplitAgent("corr-normal");
        when(registry.consume("corr-normal")).thenReturn(Optional.of(pending));
        primeRebuildAndRun();
        when(stepCompletionOrchestrator.complete(any(), eq("trigger:cron")))
            .thenReturn(com.apimarketplace.orchestrator.services.completion.StepCompletionResult
                .persisted(Map.of(), Map.of()));

        service.onAgentResult(successResult("corr-normal"));

        verify(skipPropagationService, never()).cascadeFailureToSuccessors(
            any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
        verify(signalResumeService).advanceLoopBackEdgeForAsyncCompletedNode(
            eq("run-1"), any(), eq("agent:analyze"), eq(0), eq(2), eq("trigger:cron"), any());
    }
}

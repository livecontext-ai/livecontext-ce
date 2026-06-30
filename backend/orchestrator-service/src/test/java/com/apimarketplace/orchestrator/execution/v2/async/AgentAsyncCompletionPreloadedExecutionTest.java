package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepContextManager;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.resume.ExecutionContextManager;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
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
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Caller-scoped {@code LoadedExecution} invariant tests.
 *
 * <p>Pins the contract that drives the OOM mitigation: a single async delivery loads the
 * tree+execution wrapper exactly once (via {@link ExecutionCacheManager#loadTreeAndExecution})
 * and threads it through {@link V2StepByStepService#executeNode(String, String, String, int,
 * String, ExecutionCacheManager.LoadedExecution)} so the first successor traversal does not
 * re-pay {@code reconstructState}.
 *
 * <p>If a future change reintroduces a per-successor reload - the regression that produced
 * the 2026-05-07 OOM - these tests fail before prod.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentAsyncCompletionService - caller-scoped LoadedExecution invariant")
class AgentAsyncCompletionPreloadedExecutionTest {

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
    @Mock private SignalResumeService signalResumeService;
    @Mock private ExecutionCacheManager executionCacheManager;
    @Mock private ExecutionTree tree;
    @Mock private ExecutionNode targetNode;
    @Mock private WorkflowRunEntity runEntity;
    @Mock private WorkflowExecution execution;

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
        ReflectionTestUtils.setField(service, "executionCacheManager", executionCacheManager);
        ReflectionTestUtils.setField(service, "perItemTraversalEnabled", true);
    }

    private PendingAgent nonSplitAgent(String correlationId) {
        return new PendingAgent(
            correlationId, "run-1", "agent:classify", "Classify",
            "trigger:cron", 2, 0, "0", "agent",
            "tenant-1", null, null, null, null, null, null, null, null, Instant.now());
    }

    private AgentResultMessage successResult(String correlationId) {
        return new AgentResultMessage(
            correlationId, "run-1", "agent:classify", java.util.Map.of("ok", true),
            true, null, "agent", Instant.now());
    }

    @Test
    @DisplayName("Single non-split delivery loads the tree+execution wrapper exactly once and shares it with executeNode")
    void singleDeliveryLoadsWrapperOnceAndSharesIt() {
        // Regression guard for prod OOM 2026-05-07 12:40 UTC: each async delivery must only
        // pay reconstructState once. Before the caller-scoped LoadedExecution plumbing, the
        // first executeNode in executeReadyNodesLoop re-fetched the tree+execution from the
        // database (one extra reconstructState per successor per item). On a 37-item burst
        // this compounded into the heap-saturating storm that fired the OOM.

        LoadedExecution loaded = new LoadedExecution(tree, execution);
        when(executionCacheManager.loadTreeAndExecution("run-1")).thenReturn(loaded);
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.of(runEntity));
        when(runEntity.getStatus()).thenReturn(RunStatus.RUNNING);

        // Successor traversal returns one ready node, then becomes empty so the loop exits.
        Set<String> firstSweep = Set.of("mcp:apply_label");
        when(v2StepByStepService.getReadyNodes(eq("run-1"), eq("0"), eq(2), eq(loaded)))
            .thenReturn(firstSweep);
        when(v2StepByStepService.getReadyNodes(eq("run-1"), eq("0"), eq(2)))
            .thenReturn(Set.of());

        StepByStepExecutionResult ok = new StepByStepExecutionResult(
            null,
            NodeExecutionResult.success("mcp:apply_label", java.util.Map.of(), 0L),
            Set.of(),
            false);
        when(v2StepByStepService.executeNode(
            eq("run-1"), eq("mcp:apply_label"), eq("0"), eq(2), eq("trigger:cron"), eq(loaded)))
            .thenReturn(ok);

        PendingAgent pending = nonSplitAgent("corr-1");
        when(registry.consume("corr-1")).thenReturn(Optional.of(pending));

        service.onAgentResult(successResult("corr-1"));

        // The wrapper is loaded exactly once for the entire delivery - not once for
        // rebuildLoadedExecution + once per successor inside the loop.
        verify(executionCacheManager, times(1)).loadTreeAndExecution("run-1");

        // The first executeNode in the loop receives the preloaded wrapper (6-arg overload),
        // proving the plumbing is wired end-to-end.
        verify(v2StepByStepService).executeNode(
            eq("run-1"), eq("mcp:apply_label"), eq("0"), eq(2), eq("trigger:cron"), eq(loaded));

        // The first getReadyNodes (before the loop body executes) also receives the wrapper.
        verify(v2StepByStepService).getReadyNodes(eq("run-1"), eq("0"), eq(2), eq(loaded));

        // Legacy rebuildExecution → workflowResumeService.reconstructState path is NOT used
        // when the cache manager is wired. This pins the migration: any new delivery code
        // that bypasses rebuildLoadedExecution will trip this assertion.
        verify(workflowResumeService, never()).reconstructState(anyString());
    }

    @Test
    @DisplayName("Split delivery shares the wrapper across traverseSuccessorsPerItem AND executeReadyNodesLoop - single load")
    void splitDeliveryLoadsWrapperOnceAcrossPerItemAndReadyLoop() {
        // The Gmail Auto-Labeler 37-item split is the explicit OOM scenario. After barrier
        // seal, deliverSplitItem invokes BOTH traverseSuccessorsPerItem AND
        // executeReadyNodesLoop on the same delivery. The fix must hand them both the
        // SAME LoadedExecution so reconstructState fires once, not twice. A regression
        // that double-loads here would silently halve the OOM mitigation on the workload
        // that motivated the fix.

        LoadedExecution loaded = new LoadedExecution(tree, execution);
        when(executionCacheManager.loadTreeAndExecution("run-1")).thenReturn(loaded);
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.of(runEntity));
        when(runEntity.getStatus()).thenReturn(RunStatus.RUNNING);
        when(v2StepByStepContextManager.getTree("run-1")).thenReturn(tree);
        when(nodeSearchService.findNodeFromAllRoots(eq(tree), eq("agent:classify")))
            .thenReturn(targetNode);
        when(targetNode.getNextNodes(any(NodeExecutionResult.class))).thenReturn(List.of());

        // Barrier seals on first arrival → returns the batch immediately so traverse
        // + executeReadyNodesLoop both run within this delivery.
        IndexedNodeResult resultRow = new IndexedNodeResult(0,
            NodeExecutionResult.success("agent:classify", java.util.Map.of("ok", true), 0L));
        when(splitCoalesceTracker.arrive(anyString(), anyString(), anyInt(), anyInt(), any()))
            .thenReturn(Optional.of(List.of(resultRow)));

        when(v2StepByStepService.getReadyNodes(anyString(), anyString(), anyInt(), any()))
            .thenReturn(Set.of());
        when(v2StepByStepService.getReadyNodes(anyString(), anyString(), anyInt()))
            .thenReturn(Set.of());

        // PendingAgent with splitItemData routes deliverUnderLock → deliverSplitItem
        Map<String, Object> splitData = new HashMap<>();
        splitData.put("splitNodeId", "core:split_messages");
        splitData.put("workflowItemIndex", 0);
        splitData.put("itemIndex", 0);
        splitData.put("items", List.of("a"));
        PendingAgent pending = new PendingAgent(
            "corr-split-1", "run-1", "agent:classify", "Classify",
            "trigger:cron", 2, 0, "0", "classify",
            "tenant-1", splitData, null, null, null, null, null, null, null, Instant.now());
        when(registry.consume("corr-split-1")).thenReturn(Optional.of(pending));

        service.onAgentResult(new AgentResultMessage(
            "corr-split-1", "run-1", "agent:classify", java.util.Map.of("ok", true),
            true, null, "agent", Instant.now()));

        // Single reconstructState across the entire split delivery.
        verify(executionCacheManager, times(1)).loadTreeAndExecution("run-1");
        verify(workflowResumeService, never()).reconstructState(anyString());
    }

    @Test
    @DisplayName("Test fixture without ExecutionCacheManager falls back to rebuildExecution (legacy compat)")
    void testFixtureWithoutCacheManagerFallsBackToRebuildExecution() {
        // The fallback is what kept AgentAsyncCompletionServiceFailureCascadeTest green when
        // we introduced ExecutionCacheManager - it lets focused unit-test fixtures that
        // don't wire the cache manager continue using the legacy reconstructState path.

        ReflectionTestUtils.setField(service, "executionCacheManager", null);
        com.apimarketplace.orchestrator.services.resume.WorkflowRunState state =
            mock(com.apimarketplace.orchestrator.services.resume.WorkflowRunState.class);
        when(workflowResumeService.reconstructState("run-1")).thenReturn(state);
        when(executionContextManager.rebuildExecutionContext(eq("run-1"), any())).thenReturn(execution);
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.of(runEntity));
        when(runEntity.getStatus()).thenReturn(RunStatus.RUNNING);
        when(v2StepByStepService.getReadyNodes(anyString(), anyString(), anyInt(), any())).thenReturn(Set.of());
        when(v2StepByStepService.getReadyNodes(anyString(), anyString(), anyInt())).thenReturn(Set.of());

        PendingAgent pending = nonSplitAgent("corr-2");
        when(registry.consume("corr-2")).thenReturn(Optional.of(pending));

        service.onAgentResult(successResult("corr-2"));

        verify(workflowResumeService, times(1)).reconstructState("run-1");
        verify(executionContextManager, times(1)).rebuildExecutionContext(eq("run-1"), eq(state));
    }
}

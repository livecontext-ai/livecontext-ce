package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepContextManager;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 1 regression tests - per-item async successor traversal.
 *
 * Cross-references the prod incident (run_<id>, 2026-04-29):
 * 30-item split → classify (gemini-3-pro-preview), 4 items hit Google 429.
 * Pre-fix: the global readiness walker fanned out via the LAST item's selected_category
 * only - 5 of 6 mcp:apply_* successors never fired. Post-fix: per-item dispatch routes
 * EACH successful item to its correct port.
 *
 * Uses reflection to invoke the private helper, mirroring the package-private testing
 * pattern at {@link AgentAsyncCompletionService#injectAgentMetadata} (line 734).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentAsyncCompletionService - traverseSuccessorsPerItem (Phase 1)")
class AgentAsyncCompletionPerItemTraversalTest {

    @Mock private SplitCoalesceTracker splitCoalesceTracker;
    @Mock private PendingAgentRegistry registry;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private SplitContextManager splitContextManager;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;
    @Mock private V2StepByStepContextManager v2StepByStepContextManager;
    @Mock private V2StepByStepService v2StepByStepService;
    @Mock private com.apimarketplace.orchestrator.services.state.StateSnapshotService stateSnapshotService;
    @Mock private WorkflowExecution execution;
    @Mock private ExecutionTree tree;

    private AgentAsyncCompletionService service;
    private NodeSearchService nodeSearchService;

    @BeforeEach
    void setUp() {
        nodeSearchService = mock(NodeSearchService.class);
        service = new AgentAsyncCompletionService(
            registry,
            stepCompletionOrchestrator,
            splitContextManager,
            runningNodeTracker,
            splitCoalesceTracker,
            nodeSearchService,
            runRepository);
        ReflectionTestUtils.setField(service, "v2StepByStepContextManager", v2StepByStepContextManager);
        ReflectionTestUtils.setField(service, "v2StepByStepService", v2StepByStepService);
        ReflectionTestUtils.setField(service, "perItemTraversalEnabled", true);
    }

    private PendingAgent splitAgent(int itemIndex) {
        Map<String, Object> splitData = new HashMap<>();
        splitData.put("splitNodeId", "core:each_email");
        splitData.put("workflowItemIndex", 0);
        splitData.put("itemIndex", itemIndex);
        splitData.put("items", List.of("a"));
        return new PendingAgent(
            "corr-" + itemIndex, "run-prod-incident", "agent:classify", "Classify",
            "trigger:cron", 5, itemIndex, String.valueOf(itemIndex), "classify",
            "tenant-1", splitData, null, null, null, null, null, null, null, Instant.now());
    }

    private IndexedNodeResult successItem(int idx, String selectedCategoryPort) {
        Map<String, Object> output = new HashMap<>();
        output.put("selected_category", "cat-" + idx);
        output.put("selected_category_index", idx % 6);
        return new IndexedNodeResult(idx,
            new NodeExecutionResult("agent:classify", NodeStatus.COMPLETED, output,
                java.util.Optional.empty(), java.util.Map.of(), 100L));
    }

    private IndexedNodeResult failedItem(int idx) {
        return new IndexedNodeResult(idx,
            NodeExecutionResult.failure("agent:classify", "Rate limit exceeded"));
    }

    private ExecutionNode mockSuccessor(String successorId) {
        ExecutionNode n = mock(ExecutionNode.class);
        lenient().when(n.getNodeId()).thenReturn(successorId);
        return n;
    }

    private ExecutionNode mockMergeSuccessor(String successorId) {
        ExecutionNode n = mock(ExecutionNode.class);
        lenient().when(n.getNodeId()).thenReturn(successorId);
        lenient().when(n.isImplicitMerge()).thenReturn(true);
        return n;
    }

    @SuppressWarnings("unchecked")
    private Set<String> invokeTraverse(PendingAgent pending, List<IndexedNodeResult> batch) throws Exception {
        Method m = AgentAsyncCompletionService.class.getDeclaredMethod(
            "traverseSuccessorsPerItem",
            WorkflowExecution.class, PendingAgent.class, List.class,
            com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(service, execution, pending, batch, null);
    }

    @Test
    @DisplayName("prodIncident20260429: 26 successful items dispatch their respective category successors")
    void prodIncident20260429GmailLabelerSplit30Classify4Items429Routes26SuccessfulItemsToSixCategoryPorts() throws Exception {
        // Reproduce: 30 items, indices 0..25 succeed (route to 6 distinct mcp:apply_*),
        // indices 26..29 fail (Google 429). Exactly 6 successors should be dispatched.
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(tree);
        ExecutionNode classifyNode = mock(ExecutionNode.class);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:classify")).thenReturn(classifyNode);

        // Mock per-item routing: each successful item's getNextNodes returns one of 6
        // distinct mcp:apply_* successors based on selected_category_index
        when(classifyNode.getNextNodes(any(NodeExecutionResult.class))).thenAnswer(inv -> {
            NodeExecutionResult r = inv.getArgument(0);
            if (!r.isSuccess()) return List.of();
            int idx = ((Number) r.output().get("selected_category_index")).intValue();
            return List.of(mockSuccessor("mcp:apply_cat_" + idx));
        });

        List<IndexedNodeResult> batch = new ArrayList<>();
        for (int i = 0; i < 26; i++) batch.add(successItem(i, "cat_" + (i % 6)));
        for (int i = 26; i < 30; i++) batch.add(failedItem(i));

        Set<String> dispatched = invokeTraverse(splitAgent(0), batch);

        assertThat(dispatched).hasSize(6);
        assertThat(dispatched).containsExactlyInAnyOrder(
            "mcp:apply_cat_0", "mcp:apply_cat_1", "mcp:apply_cat_2",
            "mcp:apply_cat_3", "mcp:apply_cat_4", "mcp:apply_cat_5");
        // Each distinct successor dispatched exactly once via executeNode (LinkedHashSet dedup)
        verify(v2StepByStepService, times(6)).executeNode(eq("run-prod-incident"), anyString(),
            eq("0"), eq(5), eq("trigger:cron"));
    }

    @Test
    @DisplayName("split classify all-failed batch: no traversal - empty dispatched set")
    void splitClassifyAllFailedDoesNotTraverse() throws Exception {
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(tree);
        ExecutionNode classifyNode = mock(ExecutionNode.class);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:classify")).thenReturn(classifyNode);

        List<IndexedNodeResult> batch = List.of(failedItem(0), failedItem(1), failedItem(2));

        Set<String> dispatched = invokeTraverse(splitAgent(0), batch);

        assertThat(dispatched).isEmpty();
        verify(v2StepByStepService, never()).executeNode(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("split classify mixed success+failure routes ONLY successful items to their ports")
    void splitClassifyMixedSuccessAndFailureRoutesEachSuccessfulItemToItsCategoryPort() throws Exception {
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(tree);
        ExecutionNode classifyNode = mock(ExecutionNode.class);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:classify")).thenReturn(classifyNode);
        when(classifyNode.getNextNodes(any(NodeExecutionResult.class))).thenAnswer(inv -> {
            NodeExecutionResult r = inv.getArgument(0);
            if (!r.isSuccess()) return List.of();
            int idx = ((Number) r.output().get("selected_category_index")).intValue();
            return List.of(mockSuccessor("mcp:apply_cat_" + idx));
        });

        // 2 success (cat_0, cat_1), 1 fail (would route to cat_2 if it had succeeded)
        List<IndexedNodeResult> batch = List.of(
            successItem(0, "cat_0"),
            successItem(1, "cat_1"),
            failedItem(2));

        Set<String> dispatched = invokeTraverse(splitAgent(0), batch);

        assertThat(dispatched).containsExactlyInAnyOrder("mcp:apply_cat_0", "mcp:apply_cat_1");
        // cat_2 NOT dispatched - its only routed item failed
        assertThat(dispatched).doesNotContain("mcp:apply_cat_2");
    }

    @Test
    @DisplayName("split classify all-success preserves happy-path traversal for distinct successors")
    void splitClassifyAllSuccessTraversesViaPerItemPath() throws Exception {
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(tree);
        ExecutionNode classifyNode = mock(ExecutionNode.class);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:classify")).thenReturn(classifyNode);
        ExecutionNode applyOnly = mockSuccessor("mcp:apply_only");
        when(classifyNode.getNextNodes(any(NodeExecutionResult.class))).thenReturn(List.of(applyOnly));

        List<IndexedNodeResult> batch = List.of(
            successItem(0, "x"), successItem(1, "x"), successItem(2, "x"));

        Set<String> dispatched = invokeTraverse(splitAgent(0), batch);

        // All 3 items route to the same successor - dedup'd to 1 dispatch
        assertThat(dispatched).containsExactly("mcp:apply_only");
        verify(v2StepByStepService, times(1)).executeNode(anyString(), eq("mcp:apply_only"),
            anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("traverseSuccessorsPerItem skipped when execution tree not available")
    void splitClassifyNoTreeAvailableSkipsTraversal() throws Exception {
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(null);

        Set<String> dispatched = invokeTraverse(splitAgent(0), List.of(successItem(0, "x")));

        assertThat(dispatched).isEmpty();
        verifyNoInteractions(v2StepByStepService);
    }

    @Test
    @DisplayName("traverseSuccessorsPerItem skipped when node not found in tree")
    void splitClassifyNodeNotInTreeSkipsTraversal() throws Exception {
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(tree);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:classify")).thenReturn(null);

        Set<String> dispatched = invokeTraverse(splitAgent(0), List.of(successItem(0, "x")));

        assertThat(dispatched).isEmpty();
        verifyNoInteractions(v2StepByStepService);
    }

    @Test
    @DisplayName("per-item dispatch uses parent itemId from splitItemData (workflowItemIndex)")
    void perItemDispatchUsesParentItemIdFromWorkflowItemIndex() throws Exception {
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(tree);
        ExecutionNode classifyNode = mock(ExecutionNode.class);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:classify")).thenReturn(classifyNode);
        ExecutionNode applyX = mockSuccessor("mcp:apply_x");
        when(classifyNode.getNextNodes(any(NodeExecutionResult.class))).thenReturn(List.of(applyX));

        // Build pending with workflowItemIndex=7 (outer split parent)
        Map<String, Object> splitData = new HashMap<>();
        splitData.put("splitNodeId", "core:each_email");
        splitData.put("workflowItemIndex", 7);
        splitData.put("items", List.of("a"));
        PendingAgent pending = new PendingAgent(
            "corr-7", "run-prod-incident", "agent:classify", "Classify",
            "trigger:cron", 5, 0, "0", "classify",
            "tenant-1", splitData, null, null, null, null, null, null, null, Instant.now());

        invokeTraverse(pending, List.of(successItem(0, "x")));

        ArgumentCaptor<String> itemIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(v2StepByStepService).executeNode(eq("run-prod-incident"), eq("mcp:apply_x"),
            itemIdCaptor.capture(), eq(5), eq("trigger:cron"));
        // resolveParentItemIdForSplitSuccessor returns workflowItemIndex stringified
        assertThat(itemIdCaptor.getValue()).isEqualTo("7");
    }

    @Test
    @DisplayName("dispatch failure on one successor does not block siblings")
    void dispatchFailureOnOneSuccessorContinuesWithOthers() throws Exception {
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(tree);
        ExecutionNode classifyNode = mock(ExecutionNode.class);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:classify")).thenReturn(classifyNode);
        when(classifyNode.getNextNodes(any(NodeExecutionResult.class))).thenAnswer(inv -> {
            NodeExecutionResult r = inv.getArgument(0);
            int idx = ((Number) r.output().get("selected_category_index")).intValue();
            return List.of(mockSuccessor("mcp:apply_cat_" + idx));
        });
        // Make the FIRST executeNode call throw, but the second should still fire
        StepByStepExecutionResult okResult = mock(StepByStepExecutionResult.class);
        when(v2StepByStepService.executeNode(anyString(), eq("mcp:apply_cat_0"), anyString(), anyInt(), anyString()))
            .thenThrow(new RuntimeException("boom"));
        when(v2StepByStepService.executeNode(anyString(), eq("mcp:apply_cat_1"), anyString(), anyInt(), anyString()))
            .thenReturn(okResult);

        List<IndexedNodeResult> batch = List.of(
            successItem(0, "cat_0"), successItem(1, "cat_1"));

        Set<String> dispatched = invokeTraverse(splitAgent(0), batch);

        // Both attempted (continuing siblings on error); set returns successors regardless
        assertThat(dispatched).containsExactlyInAnyOrder("mcp:apply_cat_0", "mcp:apply_cat_1");
        verify(v2StepByStepService).executeNode(anyString(), eq("mcp:apply_cat_0"), anyString(), anyInt(), anyString());
        verify(v2StepByStepService).executeNode(anyString(), eq("mcp:apply_cat_1"), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("BUG FIX: an already-FAILED successor (prior delivery) is NOT re-dispatched on recovery re-seal")
    void recoveryReSealSkipsAlreadyTerminalSuccessor() throws Exception {
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(tree);
        ExecutionNode classifyNode = mock(ExecutionNode.class);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:classify")).thenReturn(classifyNode);
        when(classifyNode.getNextNodes(any(NodeExecutionResult.class))).thenAnswer(inv -> {
            NodeExecutionResult r = inv.getArgument(0);
            int idx = ((Number) r.output().get("selected_category_index")).intValue();
            return List.of(mockSuccessor("mcp:apply_cat_" + idx));
        });

        // A prior barrier seal already ran the successors; mcp:apply_cat_0 FAILED at node level
        // (e.g. an unconfigured send_email / http_request that hit the endpoint then errored).
        // The recovery replay (AgentRecoveryService.replayInFlightEntries after a crash) re-seals
        // the barrier and re-enters traverseSuccessorsPerItem for the SAME (runId, trigger, epoch).
        // splitAgent(0) carries dagTriggerId="trigger:cron", epoch=5.
        var snapshot = mock(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.class);
        when(snapshot.getTerminalNodeIds("trigger:cron", 5)).thenReturn(Set.of("mcp:apply_cat_0"));
        when(stateSnapshotService.getSnapshot("run-prod-incident")).thenReturn(snapshot);
        ReflectionTestUtils.setField(service, "stateSnapshotService", stateSnapshotService);

        List<IndexedNodeResult> batch = List.of(
            successItem(0, "cat_0"), successItem(1, "cat_1"));

        Set<String> dispatched = invokeTraverse(splitAgent(0), batch);

        // cat_0 already terminal -> filtered out (no duplicated side effect); cat_1 still dispatched.
        assertThat(dispatched).containsExactly("mcp:apply_cat_1");
        verify(v2StepByStepService, never()).executeNode(
            anyString(), eq("mcp:apply_cat_0"), anyString(), anyInt(), anyString());
        verify(v2StepByStepService).executeNode(
            anyString(), eq("mcp:apply_cat_1"), anyString(), anyInt(), anyString());
        verify(stateSnapshotService).getSnapshot("run-prod-incident");
    }

    @Test
    @DisplayName("BUG FIX: a MERGE successor terminal in the epoch is EXEMPT from the filter (disjoint-branch rejoin not lost)")
    void mergeSuccessorExemptFromTerminalFilter() throws Exception {
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(tree);
        ExecutionNode classifyNode = mock(ExecutionNode.class);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:classify")).thenReturn(classifyNode);
        ExecutionNode mergeNode = mockMergeSuccessor("core:join");
        when(classifyNode.getNextNodes(any(NodeExecutionResult.class))).thenReturn(List.of(mergeNode));

        // The merge is already node-level COMPLETED in this epoch (a SIBLING async branch sealed
        // first and ran it for its subset). Node-level terminal status cannot tell "ran for this
        // branch" from "ran for a sibling branch", so the merge must STILL be dispatched for THIS
        // branch's items - dropping it would silently lose them. The non-merge filter (proven by
        // recoveryReSealSkipsAlreadyTerminalSuccessor) must NOT apply to a merge join.
        var snapshot = mock(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.class);
        when(snapshot.getTerminalNodeIds("trigger:cron", 5)).thenReturn(Set.of("core:join"));
        when(stateSnapshotService.getSnapshot("run-prod-incident")).thenReturn(snapshot);
        ReflectionTestUtils.setField(service, "stateSnapshotService", stateSnapshotService);

        Set<String> dispatched = invokeTraverse(splitAgent(0), List.of(successItem(0, "x")));

        assertThat(dispatched).containsExactly("core:join");
        verify(v2StepByStepService).executeNode(
            anyString(), eq("core:join"), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("mixed batch: a terminal NON-merge successor is dropped while a terminal explicit MERGE successor in the SAME traversal is kept")
    void mixedBatchDropsNonMergeKeepsMerge() throws Exception {
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(tree);
        ExecutionNode classifyNode = mock(ExecutionNode.class);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:classify")).thenReturn(classifyNode);
        // Item 0 routes to a non-merge node; item 1 routes to an EXPLICIT merge (isMergeNode=true,
        // exercising the other arm of the `isImplicitMerge() || isMergeNode()` predicate).
        ExecutionNode plain = mockSuccessor("mcp:apply_cat_0");
        ExecutionNode merge = mock(ExecutionNode.class);
        lenient().when(merge.getNodeId()).thenReturn("core:join");
        lenient().when(merge.isMergeNode()).thenReturn(true);
        when(classifyNode.getNextNodes(any(NodeExecutionResult.class))).thenAnswer(inv -> {
            NodeExecutionResult r = inv.getArgument(0);
            int idx = ((Number) r.output().get("selected_category_index")).intValue();
            return idx == 0 ? List.of(plain) : List.of(merge);
        });

        // BOTH successors are already terminal in the epoch. The filter must discriminate
        // element-by-element within ONE removeIf pass: drop the non-merge (recovery dedup), keep
        // the merge (disjoint-branch rejoin). All-or-nothing handling would fail one assertion.
        var snapshot = mock(com.apimarketplace.orchestrator.domain.execution.StateSnapshot.class);
        when(snapshot.getTerminalNodeIds("trigger:cron", 5))
            .thenReturn(Set.of("mcp:apply_cat_0", "core:join"));
        when(stateSnapshotService.getSnapshot("run-prod-incident")).thenReturn(snapshot);
        ReflectionTestUtils.setField(service, "stateSnapshotService", stateSnapshotService);

        List<IndexedNodeResult> batch = List.of(successItem(0, "cat_0"), successItem(1, "cat_1"));
        Set<String> dispatched = invokeTraverse(splitAgent(0), batch);

        assertThat(dispatched).containsExactly("core:join");
        verify(v2StepByStepService, never()).executeNode(
            anyString(), eq("mcp:apply_cat_0"), anyString(), anyInt(), anyString());
        verify(v2StepByStepService).executeNode(
            anyString(), eq("core:join"), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("fail-open: a snapshot read error still dispatches the successors (no strand)")
    void filterFailsOpenOnSnapshotError() throws Exception {
        when(v2StepByStepContextManager.getTree("run-prod-incident")).thenReturn(tree);
        ExecutionNode classifyNode = mock(ExecutionNode.class);
        when(nodeSearchService.findNodeFromAllRoots(tree, "agent:classify")).thenReturn(classifyNode);
        ExecutionNode applyOnly = mockSuccessor("mcp:apply_only");
        when(classifyNode.getNextNodes(any(NodeExecutionResult.class))).thenReturn(List.of(applyOnly));

        when(stateSnapshotService.getSnapshot("run-prod-incident")).thenThrow(new RuntimeException("boom"));
        ReflectionTestUtils.setField(service, "stateSnapshotService", stateSnapshotService);

        Set<String> dispatched = invokeTraverse(splitAgent(0), List.of(successItem(0, "x")));

        // Snapshot read failed -> proceed unfiltered rather than strand the traversal.
        assertThat(dispatched).containsExactly("mcp:apply_only");
        verify(v2StepByStepService).executeNode(anyString(), eq("mcp:apply_only"), anyString(), anyInt(), anyString());
    }
}

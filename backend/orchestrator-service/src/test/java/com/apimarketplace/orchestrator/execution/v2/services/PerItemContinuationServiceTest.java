package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.AgentNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.split.SplitExecutionOptions;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PerItemContinuationService} - the walk/seal engine behind
 * approval {@code continuationMode=per_item} in a split context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PerItemContinuationService")
class PerItemContinuationServiceTest {

    private static final String RUN_ID = "run-1";
    private static final String SIGNAL_NODE_ID = "core:item_approval";
    private static final String TRIGGER_ID = "trigger:start";
    private static final int EPOCH = 1;

    @Mock private V2StepByStepService v2StepByStepService;
    @Mock private ExecutionCacheManager executionCacheManager;
    @Mock private NodeSearchService nodeSearchService;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private WorkflowStepDataRepository stepDataRepository;
    @Mock private SplitAwareNodeExecutor splitAwareNodeExecutor;
    @Mock private ExecutionTree tree;
    @Mock private WorkflowExecution execution;

    private PerItemContinuationService service;
    private Map<String, ExecutionNode> nodeMap;

    @BeforeEach
    void setUp() {
        service = new PerItemContinuationService(
            v2StepByStepService, executionCacheManager, nodeSearchService,
            stepCompletionOrchestrator, stepDataRepository, splitAwareNodeExecutor);
        nodeMap = new HashMap<>();
        when(executionCacheManager.loadTreeAndExecution(RUN_ID))
            .thenReturn(new LoadedExecution(tree, execution));
        when(nodeSearchService.buildNodeMapFromAllRoots(tree)).thenAnswer(inv -> nodeMap);
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private SignalWaitEntity signal(String itemId, Map<String, Object> signalConfig,
                                    Map<String, Object> splitItemData) {
        SignalWaitEntity s = mock(SignalWaitEntity.class);
        lenient().when(s.getRunId()).thenReturn(RUN_ID);
        lenient().when(s.getNodeId()).thenReturn(SIGNAL_NODE_ID);
        lenient().when(s.getItemId()).thenReturn(itemId);
        lenient().when(s.getSignalConfig()).thenReturn(signalConfig);
        lenient().when(s.getSplitItemData()).thenReturn(splitItemData);
        lenient().when(s.getEpoch()).thenReturn(EPOCH);
        lenient().when(s.getDagTriggerId()).thenReturn(TRIGGER_ID);
        return s;
    }

    /** Plain walkable node mock (all cross-item predicates false, type MCP). */
    private ExecutionNode plainNode(String nodeId) {
        ExecutionNode node = mock(ExecutionNode.class);
        lenient().when(node.getNodeId()).thenReturn(nodeId);
        lenient().when(node.getType()).thenReturn(NodeType.MCP);
        lenient().when(node.getSuccessors()).thenReturn(List.of());
        nodeMap.put(nodeId, node);
        return node;
    }

    private void wire(ExecutionNode from, ExecutionNode... to) {
        lenient().when(from.getSuccessors()).thenReturn(List.of(to));
    }

    /** The signal (approval) node itself - only its successors matter to the walk. */
    private ExecutionNode approvalNode(ExecutionNode... successors) {
        ExecutionNode approval = mock(ExecutionNode.class);
        lenient().when(approval.getNodeId()).thenReturn(SIGNAL_NODE_ID);
        lenient().when(approval.isApprovalNode()).thenReturn(true);
        lenient().when(approval.getSuccessors()).thenReturn(List.of(successors));
        nodeMap.put(SIGNAL_NODE_ID, approval);
        return approval;
    }

    private static SplitExecutionOptions continuationOptions() {
        return SplitExecutionOptions.perItemContinuationWalk();
    }

    // ------------------------------------------------------------------
    // isPerItemContinuation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("isPerItemContinuation()")
    class IsPerItemContinuationTests {

        @Test
        @DisplayName("true when the signal config carries continuationMode=per_item")
        void trueForPerItemConfig() {
            SignalWaitEntity s = signal("1", Map.of("continuationMode", "per_item"), null);

            assertThat(service.isPerItemContinuation(s)).isTrue();
        }

        @Test
        @DisplayName("legacy raw value \"PER_ITEM\" persisted in the config is normalized at read-back")
        void trueForUnnormalizedPerItemConfig() {
            SignalWaitEntity s = signal("1", Map.of("continuationMode", "PER_ITEM"), null);

            assertThat(service.isPerItemContinuation(s)).isTrue();
        }

        @Test
        @DisplayName("false for continuationMode=all_items (the default barrier semantics)")
        void falseForAllItemsConfig() {
            SignalWaitEntity s = signal("1", Map.of("continuationMode", "all_items"), null);

            assertThat(service.isPerItemContinuation(s)).isFalse();
        }

        @Test
        @DisplayName("false when the signal has no config (pre-feature signals)")
        void falseForNullConfig() {
            SignalWaitEntity s = signal("1", null, null);

            assertThat(service.isPerItemContinuation(s)).isFalse();
        }

        @Test
        @DisplayName("false for a null signal")
        void falseForNullSignal() {
            assertThat(service.isPerItemContinuation(null)).isFalse();
        }

        @Test
        @DisplayName("false for ANY dotted (scoped) sub-item id - \"4.2\" AND \"4.2.1\" keep the all_items barrier")
        void falseForAnyDottedScopedSubItemId() {
            // Audit fix: the guard rejects every dotted id, not only 2+ dots. A dot means
            // an outer scope (nested split, multi-item outer workflow) where the durable
            // item_index column is ambiguous across scopes - fail safe to the barrier.
            SignalWaitEntity singleDot = signal("4.2", Map.of("continuationMode", "per_item"), null);
            SignalWaitEntity nested = signal("4.2.1", Map.of("continuationMode", "per_item"), null);

            assertThat(service.isPerItemContinuation(singleDot))
                .as("single-dot scoped id \"4.2\" must be rejected")
                .isFalse();
            assertThat(service.isPerItemContinuation(nested))
                .as("nested-split id \"4.2.1\" must be rejected")
                .isFalse();
        }

        @Test
        @DisplayName("true for a plain single-segment sub-item id (top-level split)")
        void trueForPlainSingleSegmentSubItemId() {
            SignalWaitEntity s = signal("3", Map.of("continuationMode", "per_item"), null);

            assertThat(service.isPerItemContinuation(s)).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // walkResolvedItem
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("walkResolvedItem()")
    class WalkResolvedItemTests {

        @Test
        @DisplayName("walks exactly the walkable region in BFS order via the per-item-continuation executeNode overload; the merge frontier is NOT executed")
        void walksRegionInBfsOrderStoppingAtMerge() {
            ExecutionNode merge = plainNode("core:join");
            when(merge.isMergeNode()).thenReturn(true);
            ExecutionNode b = plainNode("mcp:b");
            wire(b, merge);
            ExecutionNode a = plainNode("mcp:a");
            wire(a, b);
            approvalNode(a);
            SignalWaitEntity s = signal("2", Map.of("continuationMode", "per_item"),
                Map.of("workflowItemIndex", 7));

            Set<String> region = service.walkResolvedItem(s);

            assertThat(region).containsExactly("mcp:a", "mcp:b");
            var order = inOrder(v2StepByStepService);
            order.verify(v2StepByStepService).executeNode(
                RUN_ID, "mcp:a", "7", EPOCH, TRIGGER_ID, continuationOptions());
            order.verify(v2StepByStepService).executeNode(
                RUN_ID, "mcp:b", "7", EPOCH, TRIGGER_ID, continuationOptions());
            verify(v2StepByStepService, never()).executeNode(
                eq(RUN_ID), eq("core:join"), any(), anyInt(), anyString(), any(SplitExecutionOptions.class));
        }

        @Test
        @DisplayName("stop-set: an END/Exit node is frontier - walks must never execute it (it skips split handling BEFORE the split-context lookup, so a walk would persist node-level state mid-barrier once per sibling resolution)")
        void endNodeIsFrontierNotExecuted() {
            ExecutionNode exitNode = plainNode("core:exit");
            when(exitNode.isEndNode()).thenReturn(true);
            ExecutionNode work = plainNode("mcp:work");
            approvalNode(exitNode, work);
            SignalWaitEntity s = signal("0", Map.of("continuationMode", "per_item"),
                Map.of("workflowItemIndex", 0));

            Set<String> region = service.walkResolvedItem(s);

            assertThat(region)
                .as("the END node is frontier (post-seal ready-loop runs it once); the plain node stays walkable")
                .containsExactlyInAnyOrder("mcp:work");
            verify(v2StepByStepService, never()).executeNode(
                eq(RUN_ID), eq("core:exit"), any(), anyInt(), anyString(), any(SplitExecutionOptions.class));
        }

        @Test
        @DisplayName("stop-set: merge/implicit-merge/aggregate/loop/fork/split/approval/wait/interface/async-agent are frontier; decision and sync agent ARE walked")
        void stopSetNodesAreFrontierNotExecuted() {
            ExecutionNode merge = plainNode("core:merge");
            when(merge.isMergeNode()).thenReturn(true);
            ExecutionNode implicitMerge = plainNode("mcp:implicit_merge");
            when(implicitMerge.isImplicitMerge()).thenReturn(true);
            ExecutionNode aggregate = plainNode("core:aggregate");
            when(aggregate.isAggregateNode()).thenReturn(true);
            ExecutionNode loop = plainNode("core:loop");
            when(loop.isLoopNode()).thenReturn(true);
            ExecutionNode fork = plainNode("core:fork");
            when(fork.isForkNode()).thenReturn(true);
            ExecutionNode split = plainNode("core:split");
            when(split.isSplitNode()).thenReturn(true);
            ExecutionNode approval2 = plainNode("core:approval2");
            when(approval2.isApprovalNode()).thenReturn(true);
            ExecutionNode waitNode = plainNode("core:wait");
            when(waitNode.getType()).thenReturn(NodeType.WAIT);
            ExecutionNode interfaceNode = plainNode("interface:page");
            when(interfaceNode.getType()).thenReturn(NodeType.INTERFACE);
            AgentNode asyncAgent = mock(AgentNode.class);
            lenient().when(asyncAgent.getNodeId()).thenReturn("agent:async");
            lenient().when(asyncAgent.getSuccessors()).thenReturn(List.of());
            when(asyncAgent.isAsyncQueueEnabled()).thenReturn(true);
            nodeMap.put("agent:async", asyncAgent);
            AgentNode syncAgent = mock(AgentNode.class);
            lenient().when(syncAgent.getNodeId()).thenReturn("agent:sync");
            lenient().when(syncAgent.getSuccessors()).thenReturn(List.of());
            when(syncAgent.isAsyncQueueEnabled()).thenReturn(false);
            nodeMap.put("agent:sync", syncAgent);
            ExecutionNode decision = plainNode("core:decision");
            when(decision.getType()).thenReturn(NodeType.DECISION);
            approvalNode(merge, implicitMerge, aggregate, loop, fork, split, approval2,
                waitNode, interfaceNode, asyncAgent, syncAgent, decision);
            SignalWaitEntity s = signal("0", Map.of("continuationMode", "per_item"),
                Map.of("workflowItemIndex", 0));

            Set<String> region = service.walkResolvedItem(s);

            assertThat(region)
                .as("only the sync agent and the decision node are walkable - every cross-item / signal-yielding / async node is frontier")
                .containsExactlyInAnyOrder("agent:sync", "core:decision");
            verify(v2StepByStepService).executeNode(
                RUN_ID, "agent:sync", "0", EPOCH, TRIGGER_ID, continuationOptions());
            verify(v2StepByStepService).executeNode(
                RUN_ID, "core:decision", "0", EPOCH, TRIGGER_ID, continuationOptions());
            verify(v2StepByStepService, never()).executeNode(
                eq(RUN_ID), eq("agent:async"), any(), anyInt(), anyString(), any(SplitExecutionOptions.class));
            verify(v2StepByStepService, never()).executeNode(
                eq(RUN_ID), eq("core:merge"), any(), anyInt(), anyString(), any(SplitExecutionOptions.class));
            verify(v2StepByStepService, never()).executeNode(
                eq(RUN_ID), eq("core:wait"), any(), anyInt(), anyString(), any(SplitExecutionOptions.class));
            verify(v2StepByStepService, never()).executeNode(
                eq(RUN_ID), eq("interface:page"), any(), anyInt(), anyString(), any(SplitExecutionOptions.class));
        }

        @Test
        @DisplayName("no walkable successor (approval feeds a merge directly): empty region, nothing executed")
        void emptyRegionExecutesNothing() {
            ExecutionNode merge = plainNode("core:join");
            when(merge.isMergeNode()).thenReturn(true);
            approvalNode(merge);
            SignalWaitEntity s = signal("1", Map.of("continuationMode", "per_item"), null);

            Set<String> region = service.walkResolvedItem(s);

            assertThat(region).isEmpty();
            verify(v2StepByStepService, never()).executeNode(
                anyString(), anyString(), any(), anyInt(), anyString(), any(SplitExecutionOptions.class));
        }

        @Test
        @DisplayName("parent item scope comes from splitItemData.workflowItemIndex when present")
        void parentItemIdFromWorkflowItemIndex() {
            ExecutionNode a = plainNode("mcp:a");
            approvalNode(a);
            SignalWaitEntity s = signal("2", Map.of("continuationMode", "per_item"),
                Map.of("workflowItemIndex", 3));

            service.walkResolvedItem(s);

            verify(v2StepByStepService).executeNode(
                RUN_ID, "mcp:a", "3", EPOCH, TRIGGER_ID, continuationOptions());
        }

        @Test
        @DisplayName("DEFENSIVE fallback: a scoped sub-item id \"4.2\" strips its LAST suffix segment -> parent \"4\" (dotted ids never reach the walk via the gate)")
        void parentItemIdDefensiveFallbackStripsLastSuffixSegment() {
            // NOT a supported entry path: isPerItemContinuation rejects every dotted id,
            // so SignalResumeService never routes a "4.2" signal into a walk. This pins
            // the method-level DEFENSIVE behavior only (lastIndexOf-based strip), so a
            // direct caller cannot mis-scope the walk to the sub-item id.
            ExecutionNode a = plainNode("mcp:a");
            approvalNode(a);
            SignalWaitEntity s = signal("4.2", Map.of("continuationMode", "per_item"), null);

            service.walkResolvedItem(s);

            verify(v2StepByStepService).executeNode(
                RUN_ID, "mcp:a", "4", EPOCH, TRIGGER_ID, continuationOptions());
        }

        @Test
        @DisplayName("fallback: no splitItemData and a plain sub-item id -> parent \"0\"")
        void parentItemIdDefaultsToZero() {
            ExecutionNode a = plainNode("mcp:a");
            approvalNode(a);
            SignalWaitEntity s = signal("2", Map.of("continuationMode", "per_item"), null);

            service.walkResolvedItem(s);

            verify(v2StepByStepService).executeNode(
                RUN_ID, "mcp:a", "0", EPOCH, TRIGGER_ID, continuationOptions());
        }

        @Test
        @DisplayName("a failing region node does not abort the walk: the next region node still executes and the walk returns normally")
        void walkFailureContinuesToNextRegionNode() {
            ExecutionNode b = plainNode("mcp:b");
            ExecutionNode a = plainNode("mcp:a");
            wire(a, b);
            approvalNode(a);
            SignalWaitEntity s = signal("1", Map.of("continuationMode", "per_item"),
                Map.of("workflowItemIndex", 0));
            when(v2StepByStepService.executeNode(
                eq(RUN_ID), eq("mcp:a"), any(), anyInt(), anyString(), any(SplitExecutionOptions.class)))
                .thenThrow(new RuntimeException("boom"));

            Set<String> region = assertDoesNotThrow(() -> service.walkResolvedItem(s));

            assertThat(region).containsExactly("mcp:a", "mcp:b");
            verify(v2StepByStepService).executeNode(
                RUN_ID, "mcp:b", "0", EPOCH, TRIGGER_ID, continuationOptions());
        }

        @Test
        @DisplayName("execution not loadable: walk returns empty and never throws")
        void unloadableExecutionReturnsEmpty() {
            when(executionCacheManager.loadTreeAndExecution(RUN_ID)).thenReturn(null);
            SignalWaitEntity s = signal("1", Map.of("continuationMode", "per_item"), null);

            Set<String> region = assertDoesNotThrow(() -> service.walkResolvedItem(s));

            assertThat(region).isEmpty();
            verify(v2StepByStepService, never()).executeNode(
                anyString(), anyString(), any(), anyInt(), anyString(), any(SplitExecutionOptions.class));
        }
    }

    // ------------------------------------------------------------------
    // sealRegion
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("sealRegion()")
    class SealRegionTests {

        private SignalWaitEntity sealSignal() {
            return signal("2", Map.of("continuationMode", "per_item"),
                Map.of("workflowItemIndex", 0));
        }

        @Test
        @DisplayName("runs a final walk pass FIRST (all executeNode calls), THEN writes the node-level aggregate marks - InOrder across both collaborators")
        void sealRunsWalkPassBeforeAnyAggregateMark() {
            // Ordering is load-bearing: in a resolve-all burst the sealing resume may be
            // the first to observe "all outputs persisted" - items resolved by sibling
            // resumes must EXECUTE (walk pass) before any node-level mark makes the
            // region look terminal, or their rows would be missing at aggregate time.
            ExecutionNode b = plainNode("mcp:b");
            ExecutionNode a = plainNode("mcp:a");
            wire(a, b);
            approvalNode(a);
            when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "mcp:a", EPOCH))
                .thenReturn(List.of(0, 1, 2));
            when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "mcp:b", EPOCH))
                .thenReturn(List.of(0, 1, 2));

            service.sealRegion(sealSignal(), 3);

            var order = inOrder(v2StepByStepService, stepCompletionOrchestrator);
            // The COMPLETE walk pass (every region node) precedes the FIRST aggregate mark.
            order.verify(v2StepByStepService).executeNode(
                RUN_ID, "mcp:a", "0", EPOCH, TRIGGER_ID, continuationOptions());
            order.verify(v2StepByStepService).executeNode(
                RUN_ID, "mcp:b", "0", EPOCH, TRIGGER_ID, continuationOptions());
            order.verify(stepCompletionOrchestrator).recordSplitAggregateIfMissing(
                RUN_ID, TRIGGER_ID, "mcp:a", EPOCH);
            order.verify(stepCompletionOrchestrator).recordSplitAggregateIfMissing(
                RUN_ID, TRIGGER_ID, "mcp:b", EPOCH);
        }

        @Test
        @DisplayName("double seal is a no-op amplifier-free re-run: identical persistSealSkipRecords args each time, no extra duplication")
        void doubleSealTolerated() {
            // A re-delivered last-resolution (orphan re-drive, Redis dedup TTL expiry) can
            // seal twice. The walk pass re-executes nothing (durable row exclusion inside
            // the executor), recordSplitAggregateIfMissing is idempotent BY CONTRACT (its
            // own guard is pinned by StepCompletionOrchestrator's tests), and the seal
            // skip rows re-run with IDENTICAL args - the persist pipeline dedupes rows.
            ExecutionNode a = plainNode("mcp:a");
            approvalNode(a);
            when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "mcp:a", EPOCH))
                .thenReturn(List.of(0, 1));

            service.sealRegion(sealSignal(), 3);
            service.sealRegion(sealSignal(), 3);

            // Exactly once per seal call, with byte-identical arguments - no drift, no
            // widening of the skip set, no different item count on the second pass.
            verify(stepCompletionOrchestrator, org.mockito.Mockito.times(2))
                .recordSplitAggregateIfMissing(RUN_ID, TRIGGER_ID, "mcp:a", EPOCH);
            verify(splitAwareNodeExecutor, org.mockito.Mockito.times(2)).persistSealSkipRecords(
                eq(execution), same(nodeMap.get("mcp:a")), eq(new HashSet<>(List.of(0, 1))),
                eq(3), eq(EPOCH), eq(TRIGGER_ID));
        }

        @Test
        @DisplayName("persistSealSkipRecords only for region nodes with FEWER rows than the split item count")
        void sealSkipRecordsOnlyWhenRowsMissing() {
            ExecutionNode b = plainNode("mcp:b");
            ExecutionNode a = plainNode("mcp:a");
            wire(a, b);
            approvalNode(a);
            // a: partial rows (2 of 3) -> needs the skip parity rows; b: full rows -> no skips.
            when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "mcp:a", EPOCH))
                .thenReturn(List.of(0, 1));
            when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "mcp:b", EPOCH))
                .thenReturn(List.of(0, 1, 2));

            service.sealRegion(sealSignal(), 3);

            verify(splitAwareNodeExecutor).persistSealSkipRecords(
                eq(execution), same(a()), eq(new HashSet<>(List.of(0, 1))), eq(3), eq(EPOCH), eq(TRIGGER_ID));
            verify(splitAwareNodeExecutor, never()).persistSealSkipRecords(
                any(), same(b()), any(), anyInt(), anyInt(), any());
        }

        private ExecutionNode a() {
            return nodeMap.get("mcp:a");
        }

        private ExecutionNode b() {
            return nodeMap.get("mcp:b");
        }

        @Test
        @DisplayName("a ZERO-row region node gets NEITHER an aggregate mark NOR seal skips (post-seal ready-loop handles it as all_items would)")
        void zeroRowRegionNodeIsLeftUntouched() {
            ExecutionNode a = plainNode("mcp:a");
            approvalNode(a);
            when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "mcp:a", EPOCH))
                .thenReturn(List.of());

            service.sealRegion(sealSignal(), 3);

            verify(stepCompletionOrchestrator, never()).recordSplitAggregateIfMissing(
                anyString(), anyString(), anyString(), anyInt());
            verify(splitAwareNodeExecutor, never()).persistSealSkipRecords(
                any(), any(), any(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("a failing seal step does not abort the seal of the remaining region nodes")
        void sealStepFailureContinues() {
            ExecutionNode b = plainNode("mcp:b");
            ExecutionNode a = plainNode("mcp:a");
            wire(a, b);
            approvalNode(a);
            when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "mcp:a", EPOCH))
                .thenThrow(new RuntimeException("db down"));
            when(stepDataRepository.findTerminalItemIndicesByEpoch(RUN_ID, "mcp:b", EPOCH))
                .thenReturn(List.of(0, 1, 2));

            assertDoesNotThrow(() -> service.sealRegion(sealSignal(), 3));

            verify(stepCompletionOrchestrator).recordSplitAggregateIfMissing(
                RUN_ID, TRIGGER_ID, "mcp:b", EPOCH);
        }
    }
}

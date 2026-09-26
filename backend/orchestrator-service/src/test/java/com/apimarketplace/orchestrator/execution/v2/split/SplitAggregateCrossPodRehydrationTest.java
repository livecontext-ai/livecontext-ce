package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.AggregateNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.state.ExecutionState;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Prod 2026-09-26, run {@code run_<id>} epoch 51: split {@code core:x_segments}
 * (5 items) ran on pod A; five minutes later a wait on ANOTHER branch resumed on pod B and
 * executed the aggregate {@code core:x_collect} there. Pod B held no context for the split, so the
 * aggregate ran as a plain aggregate over item 0: {@code aggregated_count=1, segments=[null]}, and
 * the X video upload was never finalized.
 *
 * <p>Each test runs the aggregate on a FRESH {@link SplitContextManager} ("pod B") with the split's
 * output only in the durable store.
 */
@DisplayName("SplitAggregateHandler - split context absent on this pod (cross-pod resume)")
class SplitAggregateCrossPodRehydrationTest {

    private static final String RUN = "run-1";
    private static final int EPOCH = 51;
    private static final String SPLIT = "core:x_segments";
    private static final String APPEND = "mcp:x_append";
    private static final String AGGREGATE = "core:x_collect";

    private SplitContextManager podB;
    private StepOutputService stepOutputService;
    private SplitAwareNodeExecutor splitAwareNodeExecutor;
    private TemplateEngine templateEngine;
    private SplitAggregateHandler handler;
    private Map<String, ExecutionNode> nodeMap;
    private ExecutionContext context;

    private final List<Object> segments = List.of(
        Map.of("segment_index", 0), Map.of("segment_index", 1), Map.of("segment_index", 2),
        Map.of("segment_index", 3), Map.of("segment_index", 4));

    @BeforeEach
    void setUp() {
        podB = new SplitContextManager();
        stepOutputService = mock(StepOutputService.class);
        splitAwareNodeExecutor = mock(SplitAwareNodeExecutor.class);
        templateEngine = mock(TemplateEngine.class);
        handler = new SplitAggregateHandler(podB, templateEngine, null, splitAwareNodeExecutor, stepOutputService);

        // Field expression reads the split item: resolve it from the eval context's split output.
        when(templateEngine.resolveWithMap(anyString(), any())).thenAnswer(inv -> {
            Map<String, Object> ctx = inv.getArgument(1);
            if (ctx.get(SPLIT) instanceof Map<?, ?> wrapped
                    && wrapped.get("output") instanceof Map<?, ?> out
                    && out.get("current_item") instanceof Map<?, ?> item) {
                return String.valueOf(item.get("segment_index"));
            }
            return null;
        });
        when(splitAwareNodeExecutor.resolveRoutedItemIndices(any(), eq(RUN), anyInt(), eq(EPOCH), anyString()))
            .thenReturn(Set.of(0, 1, 2, 3, 4));

        nodeMap = graph(SPLIT);
        context = new ExecutionContext(RUN, "wr-1", "tenant-1", "0", 0,
            "trigger:start", EPOCH, 0, Map.of(), Map.of(), ExecutionState.create(), null);
    }

    /** split -> append -> aggregate, with the split's own predecessor configurable. */
    private Map<String, ExecutionNode> graph(String splitNodeId, String... splitPredecessors) {
        ExecutionNode split = mock(ExecutionNode.class);
        when(split.isSplitNode()).thenReturn(true);
        when(split.getPredecessorIds()).thenReturn(List.of(splitPredecessors));
        ExecutionNode append = mock(ExecutionNode.class);
        when(append.getPredecessorIds()).thenReturn(List.of(splitNodeId));
        AggregateNode aggregate = AggregateNode.builder()
            .nodeId(AGGREGATE)
            .templateEngine(null)
            .addField("segments", "{{" + SPLIT + ".output.current_item.segment_index}}")
            .build();
        aggregate.addPredecessor(APPEND);

        Map<String, ExecutionNode> map = new HashMap<>();
        map.put(splitNodeId, split);
        map.put(APPEND, append);
        map.put(AGGREGATE, aggregate);
        return map;
    }

    /** What production does on a resume: the engine hook at executeSingleNode entry, then the dispatch check. */
    private boolean isSplitAggregateOnResume(Map<String, ExecutionNode> graph) {
        new SplitContextRehydrator(podB, stepOutputService).ensureContext(RUN, AGGREGATE, 0, graph, context);
        return handler.isSplitAggregate(RUN, AGGREGATE, 0, graph);
    }

    private void splitPersisted() {
        when(stepOutputService.loadPerItemNodeOutputs(RUN, SPLIT, EPOCH, "tenant-1"))
            .thenReturn(Map.of(0, Map.of("items", segments, "item_count", 5)));
    }

    @Test
    @DisplayName("the prod bug: detected as a split aggregate and aggregates all 5 items, not 1")
    @SuppressWarnings("unchecked")
    void aggregateOnPodWithoutContextCollectsEveryItem() {
        splitPersisted();

        assertTrue(isSplitAggregateOnResume(nodeMap),
            "pre-fix this answered false and the aggregate ran as a plain aggregate over item 0");
        NodeExecutionResult result = handler.handleAggregate(RUN, AGGREGATE, 0, context, nodeMap);

        assertEquals(NodeStatus.COMPLETED, result.status());
        assertEquals(true, result.output().get("split_aggregate"));
        assertEquals(5, result.output().get("aggregated_count"));
        assertEquals(List.of("0", "1", "2", "3", "4"), (List<Object>) result.output().get("segments"));
        assertFalse(podB.hasContexts(RUN), "the aggregate still closes the scope it rebuilt");
    }

    @Test
    @DisplayName("the rebuilt context is stamped with the resume's epoch, so the stale-epoch guard keeps working")
    void rebuiltContextCarriesTheEpoch() {
        splitPersisted();

        isSplitAggregateOnResume(nodeMap);

        assertEquals(EPOCH, podB.getContext(RUN, SPLIT, 0).orElseThrow().epoch());
    }

    @Test
    @DisplayName("an unrelated split's context on this pod is NOT used: the aggregate's own split is rebuilt first")
    @SuppressWarnings("unchecked")
    void unrelatedContextOnThisPodIsNotPickedUp() {
        splitPersisted();
        podB.createContext(RUN, "core:li_parts", 0, List.of("p0", "p1"));

        NodeExecutionResult result = handler.handleAggregate(RUN, AGGREGATE, 0, context, nodeMap);

        assertEquals(SPLIT, result.output().get("split_id"),
            "pre-fix the any-context fallback returned core:li_parts and aggregated the wrong split");
        assertEquals(5, result.output().get("aggregated_count"));
        assertTrue(podB.getContext(RUN, "core:li_parts", 0).isPresent(), "the other split's scope is untouched");
    }

    @Test
    @DisplayName("stale context: this pod still holds the split from epoch 50 (3 items) - replaced by epoch 51's 5 items, not reused")
    @SuppressWarnings("unchecked")
    void olderEpochContextOnThisPodIsReplaced() {
        splitPersisted();
        podB.createContext(RUN, SPLIT, 0, null, List.of(Map.of("segment_index", 9), Map.of("segment_index", 9),
            Map.of("segment_index", 9)), EPOCH - 1);

        NodeExecutionResult result = handler.handleAggregate(RUN, AGGREGATE, 0, context, nodeMap);

        assertEquals(5, result.output().get("aggregated_count"),
            "pre-fix findActiveContext returned the epoch-50 context and the rebuild never ran");
        assertEquals(List.of("0", "1", "2", "3", "4"), (List<Object>) result.output().get("segments"));
    }

    @Test
    @DisplayName("same-epoch context already on this pod is kept as-is (it carries the per-item results)")
    void sameEpochContextIsKept() {
        SplitContext live = podB.createContext(RUN, SPLIT, 0, null, segments, EPOCH);

        SplitContextRehydrator rehydrator = new SplitContextRehydrator(podB, stepOutputService);
        assertTrue(rehydrator.ensureContext(RUN, AGGREGATE, 0, nodeMap, context));

        assertSame(live, podB.getContext(RUN, SPLIT, 0).orElseThrow());
        verifyNoInteractions(stepOutputService);
    }

    @Test
    @DisplayName("rehydrator serves every split-scope node, not only aggregates (split merge / per-item fan-out read the same memory)")
    void rehydratorRebuildsForAnyNodeInScope() {
        splitPersisted();

        SplitContextRehydrator rehydrator = new SplitContextRehydrator(podB, stepOutputService);

        assertTrue(rehydrator.ensureContext(RUN, APPEND, 0, nodeMap, context));
        assertEquals(5, podB.findActiveContext(RUN, APPEND, 0, nodeMap).orElseThrow().itemCount());
    }

    @Test
    @DisplayName("rehydrator on the split node itself (top-level): nothing upstream, no durable read")
    void rehydratorIgnoresTheSplitNodeItself() {
        SplitContextRehydrator rehydrator = new SplitContextRehydrator(podB, stepOutputService);

        assertFalse(rehydrator.ensureContext(RUN, SPLIT, 0, nodeMap, context));
        verifyNoInteractions(stepOutputService);
    }

    @Test
    @DisplayName("a NEWER-epoch context on this pod is kept (older loses, not different loses): no durable read")
    void newerEpochContextIsKept() {
        SplitContext newer = podB.createContext(RUN, SPLIT, 0, null, segments, EPOCH + 1);

        assertTrue(new SplitContextRehydrator(podB, stepOutputService).ensureContext(RUN, AGGREGATE, 0, nodeMap, context));

        assertSame(newer, podB.getContext(RUN, SPLIT, 0).orElseThrow());
        verifyNoInteractions(stepOutputService);
    }

    @Test
    @DisplayName("an UNKNOWN_EPOCH context on this pod is never treated as stale: kept, no durable read")
    void unknownEpochContextIsKept() {
        SplitContext legacy = podB.createContext(RUN, SPLIT, 0, segments);

        assertTrue(new SplitContextRehydrator(podB, stepOutputService).ensureContext(RUN, AGGREGATE, 0, nodeMap, context));

        assertSame(legacy, podB.getContext(RUN, SPLIT, 0).orElseThrow());
        verifyNoInteractions(stepOutputService);
    }

    @Test
    @DisplayName("workflow item 1: the split row at item_index 1 rebuilds the :1 scope only")
    void workflowItemIndexOtherThanZero() {
        when(stepOutputService.loadPerItemNodeOutputs(RUN, SPLIT, EPOCH, "tenant-1"))
            .thenReturn(Map.of(0, Map.of("items", List.of("a")), 1, Map.of("items", segments)));

        assertTrue(new SplitContextRehydrator(podB, stepOutputService).ensureContext(RUN, AGGREGATE, 1, nodeMap, context));

        assertEquals(5, podB.getContext(RUN, SPLIT, 1).orElseThrow().itemCount());
        assertTrue(podB.getContext(RUN, SPLIT, 0).isEmpty());
    }

    @Test
    @DisplayName("no durable store wired: nothing rebuilt, nothing thrown")
    void noStepOutputService() {
        assertFalse(new SplitContextRehydrator(podB, null).ensureContext(RUN, AGGREGATE, 0, nodeMap, context));
        assertFalse(podB.hasContexts(RUN));
    }

    @Test
    @DisplayName("split row exists but its items are not a list: no scope invented")
    void splitRowWithoutListItems() {
        when(stepOutputService.loadPerItemNodeOutputs(RUN, SPLIT, EPOCH, "tenant-1"))
            .thenReturn(Map.of(0, Map.of("items", "not-a-list")));

        assertFalse(new SplitContextRehydrator(podB, stepOutputService).ensureContext(RUN, AGGREGATE, 0, nodeMap, context));
        assertFalse(podB.hasContexts(RUN));
    }

    @Test
    @DisplayName("durable read fails while an OLDER context is in memory: reports no current context (the stale one is left, not claimed)")
    void durableFailureWithOlderContextReportsFalse() {
        SplitContext stale = podB.createContext(RUN, SPLIT, 0, null, List.of("old"), EPOCH - 1);
        when(stepOutputService.loadPerItemNodeOutputs(RUN, SPLIT, EPOCH, "tenant-1"))
            .thenThrow(new IllegalStateException("db down"));

        assertFalse(new SplitContextRehydrator(podB, stepOutputService).ensureContext(RUN, AGGREGATE, 0, nodeMap, context));
        assertSame(stale, podB.getContext(RUN, SPLIT, 0).orElseThrow());
    }

    @Test
    @DisplayName("scope already closed by the aggregate on this pod: a node on a branch that bypasses the aggregate reopens it (fans out N times, not once)")
    void bypassBranchAfterAggregateClosedTheScope() {
        splitPersisted();
        podB.createContext(RUN, SPLIT, 0, null, segments, EPOCH);
        handler.handleAggregate(RUN, AGGREGATE, 0, context, nodeMap);
        assertFalse(podB.hasContexts(RUN));
        ExecutionNode bypass = mock(ExecutionNode.class);
        when(bypass.getPredecessorIds()).thenReturn(List.of(SPLIT));
        nodeMap.put("mcp:bypass", bypass);

        assertTrue(new SplitContextRehydrator(podB, stepOutputService).ensureContext(RUN, "mcp:bypass", 0, nodeMap, context));
        assertEquals(5, podB.getContext(RUN, SPLIT, 0).orElseThrow().itemCount());
    }

    @Test
    @DisplayName("graph-only lookup strips port suffixes and walks through a decision branch")
    void upstreamLookupThroughPortedPredecessor() {
        ExecutionNode decision = mock(ExecutionNode.class);
        when(decision.getPredecessorIds()).thenReturn(List.of(SPLIT));
        ExecutionNode branch = mock(ExecutionNode.class);
        when(branch.getPredecessorIds()).thenReturn(List.of("core:check:if"));
        nodeMap.put("core:check", decision);
        nodeMap.put("mcp:branch", branch);

        assertEquals(SPLIT, SplitContextManager.findUpstreamSplitNodeId("mcp:branch", nodeMap));
    }

    @Test
    @DisplayName("graph-only lookup passes a branch-rejoin merge but stops at a split-aggregation merge")
    void upstreamLookupAndMerges() {
        ExecutionNode decision = mock(ExecutionNode.class);
        when(decision.getPredecessorIds()).thenReturn(List.of(SPLIT));
        ExecutionNode a = mock(ExecutionNode.class);
        when(a.getPredecessorIds()).thenReturn(List.of("core:check:if"));
        ExecutionNode b = mock(ExecutionNode.class);
        when(b.getPredecessorIds()).thenReturn(List.of("core:check:else"));
        ExecutionNode rejoin = mock(ExecutionNode.class);
        when(rejoin.isMergeNode()).thenReturn(true);
        when(rejoin.getPredecessorIds()).thenReturn(List.of("mcp:a", "mcp:b"));
        ExecutionNode afterRejoin = mock(ExecutionNode.class);
        when(afterRejoin.getPredecessorIds()).thenReturn(List.of("core:rejoin"));
        ExecutionNode closing = mock(ExecutionNode.class);
        when(closing.isMergeNode()).thenReturn(true);
        when(closing.getPredecessorIds()).thenReturn(List.of(APPEND));
        ExecutionNode afterClosing = mock(ExecutionNode.class);
        when(afterClosing.getPredecessorIds()).thenReturn(List.of("core:closing"));
        nodeMap.put("core:check", decision);
        nodeMap.put("mcp:a", a);
        nodeMap.put("mcp:b", b);
        nodeMap.put("core:rejoin", rejoin);
        nodeMap.put("mcp:after_rejoin", afterRejoin);
        nodeMap.put("core:closing", closing);
        nodeMap.put("mcp:after_closing", afterClosing);

        assertEquals(SPLIT, SplitContextManager.findUpstreamSplitNodeId("mcp:after_rejoin", nodeMap));
        assertNull(SplitContextManager.findUpstreamSplitNodeId("mcp:after_closing", nodeMap));
    }

    @Test
    @DisplayName("standalone aggregate (no split upstream): no durable read, not a split aggregate")
    void standaloneAggregateDoesNotTouchTheStore() {
        AggregateNode aggregate = AggregateNode.builder().nodeId(AGGREGATE).templateEngine(null).build();
        Map<String, ExecutionNode> standalone = Map.of(AGGREGATE, aggregate);

        assertFalse(isSplitAggregateOnResume(standalone));
        verifyNoInteractions(stepOutputService);
    }

    @Test
    @DisplayName("split already in this pod's memory (warm path): no durable read")
    void warmPathDoesNotTouchTheStore() {
        podB.createContext(RUN, SPLIT, 0, segments);

        assertTrue(isSplitAggregateOnResume(nodeMap));
        verifyNoInteractions(stepOutputService);
    }

    @Test
    @DisplayName("split has no persisted items for this epoch: falls back to the pre-fix answer, no context invented")
    void noPersistedItemsKeepsTheLegacyAnswer() {
        when(stepOutputService.loadPerItemNodeOutputs(RUN, SPLIT, EPOCH, "tenant-1")).thenReturn(Map.of());

        assertFalse(isSplitAggregateOnResume(nodeMap));
        assertFalse(podB.hasContexts(RUN));
    }

    @Test
    @DisplayName("durable read throws: degrades to the pre-fix answer instead of failing the node")
    void durableReadFailureDegrades() {
        when(stepOutputService.loadPerItemNodeOutputs(RUN, SPLIT, EPOCH, "tenant-1"))
            .thenThrow(new IllegalStateException("db down"));

        assertFalse(isSplitAggregateOnResume(nodeMap));
    }

    @Test
    @DisplayName("nested split (split inside another split's scope): not rebuilt, its key needs a parent scope")
    void nestedSplitIsNotRebuilt() {
        Map<String, ExecutionNode> nested = graph(SPLIT, "core:outer");
        ExecutionNode outer = mock(ExecutionNode.class);
        when(outer.isSplitNode()).thenReturn(true);
        nested.put("core:outer", outer);

        assertFalse(isSplitAggregateOnResume(nested));
        verifyNoInteractions(stepOutputService);
    }

    @Test
    @DisplayName("graph-only lookup stops at an earlier aggregate: a second aggregate does not reopen a closed scope")
    void upstreamLookupStopsAtAnEarlierAggregate() {
        AggregateNode first = AggregateNode.builder().nodeId("core:first_collect").templateEngine(null).build();
        first.addPredecessor(APPEND);
        AggregateNode second = AggregateNode.builder().nodeId("core:second_collect").templateEngine(null).build();
        second.addPredecessor("core:first_collect");
        nodeMap.put("core:first_collect", first);
        nodeMap.put("core:second_collect", second);

        assertEquals(SPLIT, SplitContextManager.findUpstreamSplitNodeId(AGGREGATE, nodeMap));
        assertNull(SplitContextManager.findUpstreamSplitNodeId("core:second_collect", nodeMap));
    }
}

package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SplitAwareNodeExecutor")
class SplitAwareNodeExecutorTest {

    @Mock
    private SplitContextManager contextManager;

    @Mock
    private ExecutionContext context;

    private SplitAwareNodeExecutor executor;
    private Map<String, ExecutionNode> nodeMap;

    @BeforeEach
    void setUp() {
        executor = new SplitAwareNodeExecutor(
            contextManager,
            null,  // NodeCompletionService - not needed for unit tests
            null,  // EdgeStatusEmitter - not needed for unit tests
            null,  // SnapshotService - not needed for unit tests
            null,  // WorkflowStepDataRepository - not needed for unit tests
            null,  // StateSnapshotService - not needed for unit tests
            Executors.newFixedThreadPool(2)
        );
        nodeMap = new HashMap<>();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Nested
    @DisplayName("execute() without split context")
    class ExecuteWithoutSplit {

        @Test
        @DisplayName("should execute node normally when no split context")
        void shouldExecuteNormally() {
            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setExecuteResult(NodeExecutionResult.success("mcp:step1", Map.of("data", "value")));
            nodeMap.put("mcp:step1", node);

            // Use 4-parameter version with workflowItemIndex=0 (legacy behavior)
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.empty());

            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(node.getExecuteCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("execute() with split context")
    class ExecuteWithSplit {

        @Test
        @DisplayName("should execute for all items in parallel")
        void shouldExecuteForAllItems() {
            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setPredecessors(List.of("core:split1")); // Make it a direct successor
            node.setExecuteResult(NodeExecutionResult.success("mcp:step1", Map.of("processed", true)));
            nodeMap.put("mcp:step1", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            // Context key is "core:split1:0" for workflowItemIndex=0
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("split_item_count")).isEqualTo(3);
            assertThat(node.getExecuteCount()).isEqualTo(3); // Executed 3 times
        }

        @Test
        @DisplayName("should mark node as SKIPPED when no items routed to this branch")
        void shouldSkipNodeWhenNoItemsRoutedToBranch() {
            // Regression: previously returned COMPLETED with SPLIT_ALREADY_PERSISTED=true
            // which made the engine skip emitNodeComplete → running=1 never cleared →
            // ReadyNodeCalculator looped the node forever (observed in prod on label_finance
            // when the classifier routed 0 emails to category_0).
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            SplitAwareNodeExecutor localExecutor = new SplitAwareNodeExecutor(
                contextManager, null, null, null, repo, null, Executors.newFixedThreadPool(2));

            TestNode node = new TestNode("mcp:label_finance", NodeType.MCP);
            node.setPredecessors(List.of("agent:classifier:category_0"));
            nodeMap.put("mcp:label_finance", node);
            nodeMap.put("agent:classifier", new TestNode("agent:classifier", NodeType.AGENT));
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:label_finance"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(repo.findItemIndicesBySelectedBranchAndEpoch("run1", "agent:classifier", "category_0", 0))
                .thenReturn(List.of());

            NodeExecutionResult result = localExecutor.execute(node, context, "run1", nodeMap);

            assertThat(result.status()).isEqualTo(NodeStatus.SKIPPED);
            assertThat(node.getExecuteCount()).isEqualTo(0);
            // No SPLIT_ALREADY_PERSISTED - engine must emit node-level completion
            assertThat(result.metadata()).doesNotContainKey("split_already_persisted");
            assertThat(result.metadata())
                .containsEntry(ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT, true);

            localExecutor.shutdown();
        }

        @Test
        @DisplayName("should persist per-item SKIPPED records for non-routed items when nodeCompletionService available")
        void shouldPersistSkippedRecordsForNonRoutedItems() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            NodeCompletionService ncs = org.mockito.Mockito.mock(NodeCompletionService.class);
            WorkflowExecution execution = org.mockito.Mockito.mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run1");

            SplitAwareNodeExecutor localExecutor = new SplitAwareNodeExecutor(
                contextManager, ncs, null, null, repo, null, Executors.newFixedThreadPool(2));

            TestNode node = new TestNode("mcp:label_finance", NodeType.MCP);
            node.setPredecessors(List.of("agent:classifier:category_0"));
            nodeMap.put("mcp:label_finance", node);
            nodeMap.put("agent:classifier", new TestNode("agent:classifier", NodeType.AGENT));

            // 5 items in split, 0 routed to this branch.
            // Stub real DAG coordinates (epoch=7, triggerId="trigger:cron") on the context so that
            // a regression to hardcoded (0, null) in production code would be CAUGHT - the prior
            // version of this test relied on Mockito defaults and would have passed silently.
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c", "d", "e"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:label_finance"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(repo.findItemIndicesBySelectedBranchAndEpoch("run1", "agent:classifier", "category_0", 7))
                .thenReturn(List.of());
            when(context.epoch()).thenReturn(7);
            when(context.triggerId()).thenReturn("trigger:cron");

            NodeExecutionResult result = localExecutor.execute(
                node, context, "run1", nodeMap, execution, null, 0, null);

            assertThat(result.status()).isEqualTo(NodeStatus.SKIPPED);
            // Items 1..4 get per-item SKIPPED records (item 0 handled by engine's emitNodeComplete).
            // Production calls the (epoch, triggerId)-aware 6-arg overload - the assertion locks
            // in the EXACT (epoch=7, triggerId="trigger:cron") values from the context so that a
            // regression to (0, null) hardcoding would fail this test.
            verify(ncs, times(4)).emitNodeSkippedForItem(eq(execution), eq(node),
                org.mockito.ArgumentMatchers.anyInt(), eq("Not routed to this branch"),
                eq(7), eq("trigger:cron"));
            // Verify specific indices: 1, 2, 3, 4 (not 0)
            verify(ncs, never()).emitNodeSkippedForItem(eq(execution), eq(node), eq(0), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
            // 2026-05-21 follow-up: this all-unrouted path must not emit the
            // aggregate before the engine records item 0. It increments the
            // pre-persisted item rows now, then defers the final aggregate to
            // StepCompletionOrchestrator after the node-level SKIPPED completion.
            verify(ncs).batchIncrementSkippedCounts("run1", "mcp:label_finance", 4);
            verify(ncs, never()).batchIncrementSkippedCountsAndEmit(eq(execution),
                eq("mcp:label_finance"), eq("label_finance"),
                anyInt(), anyInt(), anyString());
            assertThat(result.metadata())
                .containsEntry(ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT, true);

            localExecutor.shutdown();
        }

        @Test
        @DisplayName("regression: single-item unrouted choice branch cascades skipped descendants")
        void singleItemUnroutedChoiceBranchCascadesSkippedDescendants() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            NodeCompletionService ncs = org.mockito.Mockito.mock(NodeCompletionService.class);
            com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService skipService =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService.class);
            com.apimarketplace.orchestrator.services.state.StateSnapshotService stateSnapshotService =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.state.StateSnapshotService.class);
            com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService eventService =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService.class);
            WorkflowExecution execution = org.mockito.Mockito.mock(WorkflowExecution.class);

            SplitAwareNodeExecutor localExecutor = new SplitAwareNodeExecutor(
                contextManager, ncs, null, null, repo, stateSnapshotService, Executors.newFixedThreadPool(2));
            localExecutor.setSkipPropagationService(skipService);
            localExecutor.setEventService(eventService);

            TestNode applyOps = new TestNode("mcp:apply_ops", NodeType.MCP);
            applyOps.setPredecessors(List.of("core:route_item:case_1"));
            TestNode recordOps = new TestNode("mcp:record_ops", NodeType.MCP);
            applyOps.setSuccessors(List.of(recordOps));
            nodeMap.put("mcp:apply_ops", applyOps);
            nodeMap.put("mcp:record_ops", recordOps);
            nodeMap.put("core:route_item", new TestNode("core:route_item", NodeType.SWITCH));

            SplitContext splitContext = SplitContext.create("core:split_items:0", List.of("only-item"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:apply_ops"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(repo.findItemIndicesBySelectedBranchAndEpoch("run1", "core:route_item", "case_1", 9))
                .thenReturn(List.of());
            when(context.epoch()).thenReturn(9);
            when(context.triggerId()).thenReturn("trigger:start");

            NodeExecutionResult result = localExecutor.execute(
                applyOps, context, "run1", nodeMap, execution, null, 0, null);

            assertThat(result.status()).isEqualTo(NodeStatus.SKIPPED);
            verify(ncs, never()).emitNodeSkippedForItem(
                eq(execution), eq(applyOps), eq(0), anyString(), anyInt(), anyString());
            verify(skipService).cascadeFailureToSuccessors(
                eq(execution), eq(applyOps), eq(0), eq(9), eq("trigger:start"),
                eq(true), eq("split_unrouted"));
            verify(ncs).batchIncrementSkippedCountsAndEmit(
                eq(execution), eq("mcp:record_ops"), eq("record_ops"),
                eq(1), eq(9), eq("trigger:start"));
            org.mockito.ArgumentCaptor<Map<String, Map.Entry<String, Integer>>> edgeBatchCaptor =
                org.mockito.ArgumentCaptor.forClass((Class) Map.class);
            verify(eventService).recordSkipEdgesPerEpoch(
                eq(execution), edgeBatchCaptor.capture(), eq(9), eq("trigger:start"));
            assertThat(edgeBatchCaptor.getValue())
                .containsEntry("mcp:apply_ops->mcp:record_ops", Map.entry("SKIPPED", 1));
            verify(stateSnapshotService, never()).recordEdgeStatusesBatch(anyString(), any());

            localExecutor.shutdown();
        }

        @Test
        @DisplayName("Regression: persistSkippedItemRecords forwards real (epoch, triggerId) on the mixed-routing path")
        void persistSkippedItemRecordsForwardsContextEpochAndTriggerIdMixedRouting() {
            // Bug: pre-fix, the L557 mixed-routing call site (some items routed, some not) bucketed
            // the per-item SKIPPED rows under (epoch=0, triggerId="trigger:default") because the
            // helper signature didn't carry context. This test stubs the EXECUTION CONTEXT with
            // real DAG coordinates and asserts they propagate end-to-end through to the
            // NodeCompletionService boundary.
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            NodeCompletionService ncs = org.mockito.Mockito.mock(NodeCompletionService.class);
            WorkflowExecution execution = org.mockito.Mockito.mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run1");

            SplitAwareNodeExecutor localExecutor = new SplitAwareNodeExecutor(
                contextManager, ncs, null, null, repo, null, Executors.newFixedThreadPool(2));

            TestNode node = new TestNode("mcp:apply_tech", NodeType.MCP);
            node.setExecuteResult(NodeExecutionResult.success("mcp:apply_tech", Map.of("ok", true)));
            node.setPredecessors(List.of("agent:classify:category_0"));
            nodeMap.put("mcp:apply_tech", node);
            nodeMap.put("agent:classify", new TestNode("agent:classify", NodeType.AGENT));

            // 5 items in split; only items 0 and 2 routed to this branch.
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c", "d", "e"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:apply_tech"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(repo.findItemIndicesBySelectedBranchAndEpoch("run1", "agent:classify", "category_0", 4))
                .thenReturn(List.of(0, 2));

            // Stub the REAL DAG coordinates the production code must thread.
            when(context.epoch()).thenReturn(4);
            when(context.triggerId()).thenReturn("trigger:cron");
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            localExecutor.execute(node, context, "run1", nodeMap, execution, null, 0, null);

            // Items 1, 3, 4 are NOT routed → must receive per-item SKIPPED records under the
            // real (epoch=4, triggerId="trigger:cron"), NOT the legacy (0, null) bucket.
            verify(ncs).emitNodeSkippedForItem(
                eq(execution), eq(node), eq(1), eq("Not routed to this branch"),
                eq(4), eq("trigger:cron"));
            verify(ncs).emitNodeSkippedForItem(
                eq(execution), eq(node), eq(3), eq("Not routed to this branch"),
                eq(4), eq("trigger:cron"));
            verify(ncs).emitNodeSkippedForItem(
                eq(execution), eq(node), eq(4), eq("Not routed to this branch"),
                eq(4), eq("trigger:cron"));
            // Routed items (0, 2) must NOT receive a SKIPPED record.
            verify(ncs, never()).emitNodeSkippedForItem(any(), eq(node), eq(0), any(), anyInt(), anyString());
            verify(ncs, never()).emitNodeSkippedForItem(any(), eq(node), eq(2), any(), anyInt(), anyString());

            localExecutor.shutdown();
        }

        /**
         * Regression for the prod bug surfaced 2026-05-14 in conv {@code 1f209071…}:
         * the user's "Hourly Price Alert Monitor" workflow had a 4-item split routing
         * by {@code asset_type}. The switch sent items [2, 3] (the stocks) to
         * {@code mcp:stock_quote} and items [0, 1] (the crypto) to coingecko. After
         * stock_quote completed for items 2 and 3, downstream {@code core:extract_stock_price}
         * (code node) tried to read {@code $input.stock_quote.results[0].c} for its own
         * per-item invocation (item_index=2) and got {@code null}.
         *
         * <p>Root cause: {@link #storePerItemResultsInContext} stored {@code results} as
         * a DENSE list of size 2 (routed items only). Downstream
         * {@link #injectPredecessorPerItemOutputs} looked up {@code results.get(2)}
         * which fell off the end (size 2, index 2). No injection happened, so
         * {@code $input.stock_quote} was empty.
         *
         * <p>Fix contract: outputs must be a SPARSE list of size
         * {@code splitContext.itemCount()} with the actual output placed at each routed
         * item's ABSOLUTE index and {@code null} at every non-routed slot.
         */
        @Test
        @DisplayName("Regression: partial-routing stores SPARSE outputs aligned with absolute item indices")
        void partialRoutingStoresSparseList() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            SplitAwareNodeExecutor localExecutor = new SplitAwareNodeExecutor(
                contextManager, null, null, null, repo, null, Executors.newFixedThreadPool(2));

            TestNode node = new TestNode("mcp:stock_quote", NodeType.MCP);
            // Predecessor is the switch port that routes only asset_type=stock items.
            node.setPredecessors(List.of("core:route_by_asset_type:case_0"));
            // Return a per-item output keyed on the absolute index so the test can
            // verify each routed slot carries its OWN result, not a sibling's.
            node.setDynamicResult(ctx -> NodeExecutionResult.success(
                "mcp:stock_quote",
                Map.of("ticker_idx", ctx.itemIndex(), "results",
                       List.of(Map.of("c", 100.0 + ctx.itemIndex())))));
            nodeMap.put("mcp:stock_quote", node);
            nodeMap.put("core:route_by_asset_type", new TestNode("core:route_by_asset_type", NodeType.SWITCH));

            // 4-item split: items 2 and 3 are routed to stock_quote (the bug scenario).
            SplitContext splitContext = SplitContext.create(
                "core:split_alerts:0", List.of("BTC", "ETH", "AAPL", "TSLA"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:stock_quote"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(repo.findItemIndicesBySelectedBranchAndEpoch(
                    "run1", "core:route_by_asset_type", "case_0", 0))
                .thenReturn(List.of(2, 3));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            localExecutor.execute(node, context, "run1", nodeMap);

            // Capture what landed in SplitContext via storeResults - that's the lookup
            // surface for downstream injectPredecessorPerItemOutputs.
            @SuppressWarnings({"rawtypes", "unchecked"})
            org.mockito.ArgumentCaptor<List<Object>> outputsCaptor =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
            verify(contextManager).storeResults(
                eq("run1"),
                eq("core:split_alerts"),  // extractBaseSplitKey strips ":0" suffix
                eq(0),
                eq("mcp:stock_quote"),
                outputsCaptor.capture());

            List<Object> outputs = outputsCaptor.getValue();
            assertThat(outputs)
                .as("stored outputs must be SPARSE over splitContext.itemCount(), not dense over routed count")
                .hasSize(4);
            assertThat(outputs.get(0))
                .as("non-routed slot 0 must be null (crypto item, no stock_quote run)")
                .isNull();
            assertThat(outputs.get(1))
                .as("non-routed slot 1 must be null").isNull();
            assertThat(outputs.get(2))
                .as("routed slot 2 must hold the result for item 2 (AAPL)")
                .isNotNull();
            assertThat(outputs.get(3))
                .as("routed slot 3 must hold the result for item 3 (TSLA)")
                .isNotNull();

            // Sanity: each routed slot carries an output map (not a primitive or null).
            // We can't assert per-item identity from the mock setup (context.withItemIndex
            // returns the same mock for every item), so we verify the sparse SHAPE - which
            // is the invariant that the dense-list bug violated.
            assertThat(outputs.get(2)).isInstanceOf(Map.class);
            assertThat(outputs.get(3)).isInstanceOf(Map.class);

            localExecutor.shutdown();
        }

        /**
         * Iteration variant: route ONLY the last item (index N-1) on a wide split.
         * Pre-fix, the dense list had a single entry at index 0, and a downstream
         * lookup at absolute index N-1 missed entirely. Post-fix, the result lands at
         * its absolute slot regardless of how many siblings were skipped.
         */
        @Test
        @DisplayName("Iteration: only the LAST item routed → result lands at absolute index N-1")
        void partialRoutingSingleLastItem() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            SplitAwareNodeExecutor localExecutor = new SplitAwareNodeExecutor(
                contextManager, null, null, null, repo, null, Executors.newFixedThreadPool(2));

            TestNode node = new TestNode("mcp:probe", NodeType.MCP);
            node.setPredecessors(List.of("agent:classify:category_2"));
            node.setDynamicResult(ctx -> NodeExecutionResult.success(
                "mcp:probe", Map.of("from", ctx.itemIndex())));
            nodeMap.put("mcp:probe", node);
            nodeMap.put("agent:classify", new TestNode("agent:classify", NodeType.AGENT));

            // 6-item split, only item 5 routed.
            SplitContext splitContext = SplitContext.create(
                "core:split:0", List.of("a", "b", "c", "d", "e", "f"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:probe"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(repo.findItemIndicesBySelectedBranchAndEpoch(
                    "run1", "agent:classify", "category_2", 0))
                .thenReturn(List.of(5));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            localExecutor.execute(node, context, "run1", nodeMap);

            @SuppressWarnings({"rawtypes", "unchecked"})
            org.mockito.ArgumentCaptor<List<Object>> outputsCaptor =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
            verify(contextManager).storeResults(
                eq("run1"), eq("core:split"), eq(0), eq("mcp:probe"),
                outputsCaptor.capture());

            List<Object> outputs = outputsCaptor.getValue();
            assertThat(outputs).hasSize(6);
            for (int i = 0; i < 5; i++) {
                assertThat(outputs.get(i)).as("slot %d must be null", i).isNull();
            }
            assertThat(outputs.get(5))
                .as("only routed item must land at its absolute index 5, not index 0")
                .isInstanceOf(Map.class);

            localExecutor.shutdown();
        }

        /**
         * Backward-compat: when ALL items are routed (no skip), the stored list is
         * still aligned by absolute index. The pre-fix behavior happened to work in
         * this case (dense list of size N == itemCount), so we verify the fix doesn't
         * regress the happy path.
         */
        @Test
        @DisplayName("Iteration: all items routed → outputs aligned at every absolute index, no shift")
        void fullRoutingPreservesAlignment() {
            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setPredecessors(List.of("core:split1")); // direct successor - all routed
            node.setDynamicResult(ctx -> NodeExecutionResult.success(
                "mcp:step1", Map.of("idx", ctx.itemIndex())));
            nodeMap.put("mcp:step1", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create(
                "core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            executor.execute(node, context, "run1", nodeMap);

            @SuppressWarnings({"rawtypes", "unchecked"})
            org.mockito.ArgumentCaptor<List<Object>> outputsCaptor =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
            verify(contextManager).storeResults(
                eq("run1"), eq("core:split1"), eq(0), eq("mcp:step1"),
                outputsCaptor.capture());

            List<Object> outputs = outputsCaptor.getValue();
            assertThat(outputs).hasSize(3);
            for (int i = 0; i < 3; i++) {
                assertThat(outputs.get(i))
                    .as("slot %d must carry an output (full-routing must not introduce gaps)", i)
                    .isInstanceOf(Map.class);
            }
        }

        /**
         * Regression for the prod bug surfaced 2026-05-14 on {@code run_<id>}
         * ("Hourly Price Alert Monitor"). The user's workflow had a 4-item split routing by
         * {@code asset_type}. The switch sent items [2,3] (stocks) to a stock branch and items
         * [0,1] (cryptos) to a crypto branch. Both branches re-joined on
         * {@code core:merge_branches}. The merge had predecessors
         * {@code [table:log_stock_check, table:log_crypto_check]} - both linear (no port) edges.
         *
         * <p><b>Bug.</b> {@link com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor}'s
         * {@code getTransitiveRoutedItemIndices} used INTERSECTION across predecessor
         * {@code completedIndices}: {@code intersect([2,3],[0,1]) = ∅}. Empty intersection →
         * {@code routedItemIndices.isEmpty()} → the merge was marked SKIPPED for all 4 items
         * (visible as {@code statusCounts.skipped=4, completed=0} on the merge node in the
         * canvas, even though logically every item DID complete via one branch).
         *
         * <p><b>Fix contract.</b> For merge-like nodes ({@code isMergeNode()} OR
         * {@code isImplicitMerge()}), the routing aggregation across predecessors is the
         * UNION, not the intersection. So the merge runs for items [0,1,2,3] - one item per
         * branch that completed on either side. Linear chains keep their intersection
         * semantics (asserted by sibling tests).
         */
        @Test
        @DisplayName("Regression (prod 2026-05-14 merge_branches): explicit merge over disjoint switch branches uses UNION of per-pred completedIndices, NOT intersection")
        void mergeOverDisjointBranchesUsesUnionNotIntersection() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            SplitAwareNodeExecutor localExecutor = new SplitAwareNodeExecutor(
                contextManager, null, null, null, repo, null, Executors.newFixedThreadPool(2));

            // The merge node - NodeType.MERGE so isMergeNode()=true. Predecessors are the
            // two branches' leaf nodes (linear edges, NO ports - exactly the prod shape).
            TestNode merge = new TestNode("core:merge_branches", NodeType.MERGE);
            merge.setPredecessors(List.of("table:log_stock_check", "table:log_crypto_check"));
            merge.setDynamicResult(ctx -> NodeExecutionResult.success(
                "core:merge_branches", Map.of("merged_for_item", ctx.itemIndex())));
            nodeMap.put("core:merge_branches", merge);
            // Predecessor stubs - only need to exist in nodeMap so the executor can resolve
            // edge refs. Their NodeType is irrelevant to this test (we never execute them);
            // MCP is the simplest placeholder.
            nodeMap.put("table:log_stock_check", new TestNode("table:log_stock_check", NodeType.MCP));
            nodeMap.put("table:log_crypto_check", new TestNode("table:log_crypto_check", NodeType.MCP));

            // 4-item split, disjoint branch coverage matching the prod run.
            SplitContext splitContext = SplitContext.create(
                "core:split_alerts:0", List.of("BTC", "ETH", "AAPL", "TSLA"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:merge_branches"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            // No port-based selectedBranch query for the merge - its predecessors are linear,
            // so getRoutedItemIndices falls through to getTransitiveRoutedItemIndices.
            // Stub per-pred completion query: stock-branch leaf completed items [2,3], crypto
            // leaf completed items [0,1].
            when(repo.findCompletedItemIndicesByEpoch("run1", "table:log_stock_check", 0))
                .thenReturn(List.of(2, 3));
            when(repo.findCompletedItemIndicesByEpoch("run1", "table:log_crypto_check", 0))
                .thenReturn(List.of(0, 1));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            NodeExecutionResult result = localExecutor.execute(merge, context, "run1", nodeMap);

            // Pre-fix this returned SKIPPED with executeCount=0 and persisted 4 SKIPPED rows.
            // Post-fix the merge must run for every item that completed on ANY branch (UNION
            // = [0,1,2,3] = 4 items) and return COMPLETED.
            assertThat(result.status())
                .as("merge over disjoint branches must NOT be marked SKIPPED - union of branches covers all items")
                .isEqualTo(NodeStatus.COMPLETED);
            assertThat(merge.getExecuteCount())
                .as("merge must execute exactly once per item that completed on either branch (UNION semantics)")
                .isEqualTo(4);

            localExecutor.shutdown();
        }

        /**
         * Sibling guard: a LINEAR chain (single predecessor with a routed subset) must
         * STILL intersect - i.e. the regression fix above must not flip the semantics for
         * non-merge nodes. Pins the contract that intersection vs. union is gated on
         * {@code isMergeNode() || isImplicitMerge()}, not blanket-applied.
         */
        @Test
        @DisplayName("Non-merge node with a single routed predecessor keeps INTERSECTION (inherits the predecessor's routed subset)")
        void linearChainKeepsIntersectionSemantics() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            SplitAwareNodeExecutor localExecutor = new SplitAwareNodeExecutor(
                contextManager, null, null, null, repo, null, Executors.newFixedThreadPool(2));

            // Plain MCP node with a single linear predecessor (no port). isMergeNode()=false,
            // isImplicitMerge()=false (only 1 pred) - must use INTERSECTION (which for a
            // single pred is just "inherit the pred's subset").
            TestNode node = new TestNode("mcp:downstream", NodeType.MCP);
            node.setPredecessors(List.of("mcp:upstream"));
            node.setDynamicResult(ctx -> NodeExecutionResult.success(
                "mcp:downstream", Map.of("idx", ctx.itemIndex())));
            nodeMap.put("mcp:downstream", node);
            nodeMap.put("mcp:upstream", new TestNode("mcp:upstream", NodeType.MCP));

            SplitContext splitContext = SplitContext.create(
                "core:split:0", List.of("a", "b", "c", "d"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:downstream"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            // Upstream only completed items [1,2] - downstream must inherit that subset.
            when(repo.findCompletedItemIndicesByEpoch("run1", "mcp:upstream", 0))
                .thenReturn(List.of(1, 2));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            NodeExecutionResult result = localExecutor.execute(node, context, "run1", nodeMap);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(node.getExecuteCount())
                .as("linear downstream must run only for items its predecessor completed (intersection / inherit)")
                .isEqualTo(2);

            localExecutor.shutdown();
        }

        /**
         * Regression for prod run {@code 656a4aed-…} epochs 7-10
         * (Gmail Auto-Labeler, May 2026): when a linear downstream node lands on the
         * "No items routed to this branch" path because its predecessor's per-item
         * step_data is absent in the DB (e.g. a V261-NOT-NULL transactional abort
         * silently aborted the predecessor's {@code workflow_step_data} INSERT), the
         * returned {@code NodeExecutionResult} must be BARE {@code skipped(...)} -
         * NOT {@code skippedWithCascade(...)}.
         *
         * <p>The cascade variant triggers {@code V2SkipPropagationService.cascadeFailureToSuccessors}
         * which recursively marks every descendant SKIPPED in {@code EpochState.skippedNodeIds}
         * AND increments {@code NodeCounts.skipped} +1 per descendant per fire. In prod,
         * 6 cron fires with missing predecessor step_data inflated {@code parse_headers}'s
         * counter from an expected ~0 to 36, classify's by 36, every apply_X by 36, every
         * record_X by 36 - clean numerical fingerprint visible in {@code workflow_epochs}.
         *
         * <p>The "No items routed" condition is derived from a DB lookup
         * ({@code getTransitiveRoutedItemIndices} querying {@code findCompletedItemIndicesByEpoch}),
         * so its emptiness is a TRANSIENT signal indistinguishable from a true routing
         * decision. Cascading from this state amplifies unrelated persistence
         * regressions. Pre-fix this site used bare {@code skipped()}; the
         * defense-in-depth cascade flag added 2026-05-20 was reverted after the prod
         * incident.
         */
        @Test
        @DisplayName("Regression (prod 656a4aed): No-items-routed path must return BARE skipped, never skippedWithCascade")
        void noItemsRoutedReturnsBareSkippedNotCascade() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            SplitAwareNodeExecutor localExecutor = new SplitAwareNodeExecutor(
                contextManager, null, null, null, repo, null, Executors.newFixedThreadPool(2));

            TestNode downstream = new TestNode("core:parse_headers", NodeType.MCP);
            downstream.setPredecessors(List.of("mcp:get_content"));
            nodeMap.put("core:parse_headers", downstream);
            nodeMap.put("mcp:get_content", new TestNode("mcp:get_content", NodeType.MCP));

            // Predecessor step_data is empty (simulating the V261 NOT NULL abort) →
            // getTransitiveRoutedItemIndices returns Set.of() → executor lands on the
            // "No items routed" branch.
            SplitContext splitContext = SplitContext.create(
                "core:each_email:0", List.of("email1", "email2", "email3", "email4"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:parse_headers"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(repo.findCompletedItemIndicesByEpoch("run1", "mcp:get_content", 0))
                .thenReturn(List.of());

            NodeExecutionResult result = localExecutor.execute(downstream, context, "run1", nodeMap);

            assertThat(result.status())
                .as("Node must be marked SKIPPED on the empty-routing path")
                .isEqualTo(NodeStatus.SKIPPED);
            assertThat(result.metadata())
                .as("CASCADE_SKIP_TO_SUCCESSORS metadata flag must NOT be set on the "
                    + "No-items-routed return - otherwise the engine cascades and inflates "
                    + "descendant NodeCounts whenever predecessor step_data is missing "
                    + "(prod 656a4aed epochs 7-10 fingerprint: parse_headers SKIPPED=36).")
                .doesNotContainKey(
                    com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS);

            localExecutor.shutdown();
        }

        @Test
        @DisplayName("should mark node as SKIPPED when split is empty")
        void shouldHandleEmptySplit() {
            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setPredecessors(List.of("core:split1")); // Make it a direct successor
            nodeMap.put("mcp:step1", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of());
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));

            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            // SKIPPED (not COMPLETED) so engine's emitNodeComplete fires and clears
            // runningNodeTracker - otherwise ReadyNodeCalculator would loop forever.
            assertThat(result.status()).isEqualTo(NodeStatus.SKIPPED);
            assertThat(node.getExecuteCount()).isEqualTo(0); // Not executed
        }

        @Test
        @DisplayName("should execute and track item count")
        void shouldExecuteAndTrackItemCount() {
            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setPredecessors(List.of("core:split1")); // Make it a direct successor
            node.setDynamicResult(ctx -> {
                return NodeExecutionResult.success("mcp:step1", Map.of("result", "done"));
            });
            nodeMap.put("mcp:step1", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("split_item_count")).isEqualTo(2);
            assertThat(node.getExecuteCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Phase 2.B: partial failure (1 of 3 fails) returns COMPLETED with split_partial_failure marker")
        void shouldReturnCompletedWithPartialMarkerWhenSomeItemsFailAndSomeSucceed() {
            // Phase 2.B (2026-04-29 prod-incident fix): the previous contract was
            // "any failure → global FAILED". That poisoned the readiness gate and
            // blocked successful items' downstream from firing. New contract:
            // - all failed → FAILED (preserved)
            // - ≥1 success + ≥1 failure → COMPLETED with split_partial_failure flag
            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setPredecessors(List.of("core:split1"));
            // AtomicInteger because the lambda runs on the ForkJoinPool (parallel items).
            java.util.concurrent.atomic.AtomicInteger itemIndex = new java.util.concurrent.atomic.AtomicInteger(0);
            node.setDynamicResult(ctx -> {
                int currentIndex = itemIndex.getAndIncrement();
                if (currentIndex == 1) {
                    return NodeExecutionResult.failure("mcp:step1", "Item 1 failed");
                }
                return NodeExecutionResult.success("mcp:step1", Map.of());
            });
            nodeMap.put("mcp:step1", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            // 2 succeed (index 0, 2) + 1 fails (index 1) → partial failure
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output()).containsEntry("split_partial_failure", true);
            assertThat(result.output()).containsKey("split_failed_item_indices");
            assertThat(result.output()).containsKey("split_completed_item_indices");
        }

        @Test
        @DisplayName("should return AWAITING_SIGNAL when all items yield AWAITING_SIGNAL")
        void shouldReturnAwaitingSignalWhenAllItemsYield() {
            TestNode node = new TestNode("core:user_approval", NodeType.APPROVAL);
            node.setPredecessors(List.of("core:split1"));
            // Simulate UserApproval node: each item returns AWAITING_SIGNAL
            node.setDynamicResult(ctx -> {
                Map<String, Object> output = new HashMap<>();
                output.put("status", "awaiting_approval");
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("signal_type", "USER_APPROVAL");
                return new NodeExecutionResult(
                    "core:user_approval", NodeStatus.AWAITING_SIGNAL,
                    output, Optional.empty(), metadata, 10L);
            });
            nodeMap.put("core:user_approval", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c", "d", "e"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:user_approval"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            // Must return AWAITING_SIGNAL, NOT COMPLETED
            assertThat(result.status()).isEqualTo(NodeStatus.AWAITING_SIGNAL);
            assertThat(result.output()).containsEntry("split_item_count", 5);
            assertThat(result.output()).containsEntry("split_awaiting_signal", true);
            assertThat(result.output()).containsEntry("signal_type", "USER_APPROVAL");
            // All 5 items should have been executed
            assertThat(node.getExecuteCount()).isEqualTo(5);
            // Metadata should mark split_already_persisted
            assertThat(result.metadata()).containsEntry("split_already_persisted", true);
        }

        @Test
        @DisplayName("should return COMPLETED when some items complete and some yield AWAITING_SIGNAL")
        void shouldReturnCompletedWhenMixedResults() {
            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setPredecessors(List.of("core:split1"));
            // AtomicInteger because the lambda runs on the ForkJoinPool (3 parallel items).
            // Pre-fix this used `int[] callIndex = {0}` with unsynchronized `callIndex[0]++`,
            // which raced under parallel execution - two threads could both read idx=1 and
            // both yield AWAITING_SIGNAL, dropping the success count from 2 to 1 and tripping
            // `split_item_count` (caught on the app-host deploy 2026-05-14).
            java.util.concurrent.atomic.AtomicInteger callIndex = new java.util.concurrent.atomic.AtomicInteger(0);
            node.setDynamicResult(ctx -> {
                int idx = callIndex.getAndIncrement();
                if (idx == 1) {
                    // Item 1 yields AWAITING_SIGNAL
                    return new NodeExecutionResult(
                        "mcp:step1", NodeStatus.AWAITING_SIGNAL,
                        Map.of(), Optional.empty(), Map.of("signal_type", "USER_APPROVAL"), 10L);
                }
                return NodeExecutionResult.success("mcp:step1", Map.of("result", "done"));
            });
            nodeMap.put("mcp:step1", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            // Mixed: 2 completed + 1 awaiting → aggregate is COMPLETED (non-empty results list)
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("split_item_count")).isEqualTo(2);
            assertThat(node.getExecuteCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should execute exactly once for single item")
        void shouldExecuteOnceForSingleItem() {
            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setPredecessors(List.of("core:split1")); // Make it a direct successor
            node.setExecuteResult(NodeExecutionResult.success("mcp:step1", Map.of("result", "done")));
            nodeMap.put("mcp:step1", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("single_item"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output().get("split_item_count")).isEqualTo(1);
            assertThat(node.getExecuteCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should provide item and index in execution context")
        @SuppressWarnings("unchecked")
        void shouldProvideItemAndIndexInContext() {
            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setPredecessors(List.of("core:split1")); // Make it a direct successor
            // Use a counter since mock context doesn't store global data.
            // AtomicInteger because the lambda runs on the ForkJoinPool (parallel items).
            java.util.concurrent.atomic.AtomicInteger callIndex = new java.util.concurrent.atomic.AtomicInteger(0);
            node.setDynamicResult(ctx -> {
                int index = callIndex.getAndIncrement();
                // Return simple result without relying on context.getGlobalData
                return NodeExecutionResult.success("mcp:step1", Map.of(
                    "processed_index", index
                ));
            });
            nodeMap.put("mcp:step1", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            List<String> items = List.of("apple", "banana", "cherry");
            SplitContext splitContext = SplitContext.create("core:split1:0", (List) items);
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(node.getExecuteCount()).isEqualTo(3);
            assertThat(result.output().get("split_item_count")).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("chained downstream nodes in auto mode (regression - aggregate per-item duplication)")
    class ChainedDownstreamAutoMode {

        @Test
        @DisplayName("Records each per-item output into SplitContext so a downstream aggregate sees N distinct values, not the same one N times")
        void shouldStorePerItemResultForChainedDownstreamNode() {
            // Production bug: workflow `split → read_email → clean_email → aggregate` produced
            // 31 identical entries in aggregate.email_ids because clean_email's per-item outputs
            // were never written into SplitContext.resultsByNode (only read_email's were).
            // SplitAggregateHandler.evaluateConfiguredFields then iterated 31 times against an
            // eval context that lacked clean_email per-item, falling back to a stale single
            // value. Fix: chained nodes must call SplitContextManager.storeItemResult after
            // node.execute() returns inside the auto-mode "not direct successor" path.

            TestNode clean = new TestNode("core:clean", NodeType.CODE);
            // Predecessor is the IMMEDIATE successor of split, NOT the split itself.
            // This is what makes the executor take the "auto mode + not direct successor" branch.
            clean.setPredecessors(List.of("mcp:read"));
            clean.setExecuteResult(NodeExecutionResult.success("core:clean", Map.of("id", "id-7")));
            nodeMap.put("core:clean", clean);
            nodeMap.put("mcp:read", new TestNode("mcp:read", NodeType.MCP));
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:clean"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            // Sub-item index 7 - what traverseTree would carry through for the 8th split item.
            when(context.itemIndex()).thenReturn(7);

            // Auto mode is signalled by passing a non-null SuccessorTraverser.
            SplitAwareNodeExecutor.SuccessorTraverser noopTraverser = (s, c, idx) -> c;

            NodeExecutionResult result = executor.execute(
                clean, context, "run1", nodeMap, null, null, 0, noopTraverser);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(clean.getExecuteCount()).isEqualTo(1); // still per-item (not N times)

            // The fix's contract: the per-item output landed in SplitContext.resultsByNode["core:clean"]
            // at slot 7, with totalItems = splitContext.itemCount(). Without this call, sibling
            // items would produce the same-value-N-times bug at the aggregate. Routes through the
            // scoped-key overload so nested splits don't collide via findScopedKey's first-match scan.
            verify(contextManager).storeItemResultByScopedKey(
                eq("run1"),
                eq("core:split1:0"),     // exact scoped key from SplitContext
                eq("core:clean"),
                eq(7),                   // sub-item index from context
                eq(3),                   // splitContext.itemCount()
                eq(Map.of("id", "id-7")) // node.execute()'s output
            );
        }

        @Test
        @DisplayName("Routes through the SCOPED key (with /sN suffix) for nested splits, not the base key - guards against findScopedKey first-match ambiguity")
        void shouldUseScopedKeyForNestedSplits() {
            TestNode clean = new TestNode("core:clean", NodeType.CODE);
            clean.setPredecessors(List.of("mcp:read"));
            clean.setExecuteResult(NodeExecutionResult.success("core:clean", Map.of("id", "id-1")));
            nodeMap.put("core:clean", clean);
            nodeMap.put("mcp:read", new TestNode("mcp:read", NodeType.MCP));
            nodeMap.put("core:inner_loop", new TestNode("core:inner_loop", NodeType.SPLIT));

            // Nested-split key shape: "core:inner_loop:0/s1" - outer split's item 1 spawned this
            // inner context. A sibling "/s0" context for the same workflowItemIndex=0 would race
            // with first-match in findScopedKey if we routed by base key.
            SplitContext splitContext = SplitContext.create("core:inner_loop:0/s1", List.of("a", "b"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:clean"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(1);

            SplitAwareNodeExecutor.SuccessorTraverser noopTraverser = (s, c, idx) -> c;

            executor.execute(clean, context, "run1", nodeMap, null, null, 0, noopTraverser);

            verify(contextManager).storeItemResultByScopedKey(
                eq("run1"),
                eq("core:inner_loop:0/s1"),  // EXACT scoped key - preserves /s1 disambiguator
                eq("core:clean"),
                eq(1),
                eq(2),
                eq(Map.of("id", "id-1"))
            );
            // The base-key overload (which would defer to findScopedKey) must NOT be used.
            verify(contextManager, never()).storeItemResult(
                anyString(), anyString(), anyInt(), anyString(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("Does not record into SplitContext when node failed - avoids poisoning aggregate with empty/error outputs")
        void shouldNotStoreOnFailure() {
            TestNode clean = new TestNode("core:clean", NodeType.CODE);
            clean.setPredecessors(List.of("mcp:read"));
            clean.setExecuteResult(NodeExecutionResult.failure("core:clean", "boom"));
            nodeMap.put("core:clean", clean);
            nodeMap.put("mcp:read", new TestNode("mcp:read", NodeType.MCP));
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:clean"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));

            SplitAwareNodeExecutor.SuccessorTraverser noopTraverser = (s, c, idx) -> c;

            executor.execute(clean, context, "run1", nodeMap, null, null, 0, noopTraverser);

            // Failed results are handled by the skip-cascade machinery, not by replaying a
            // garbage value at every aggregate slot. Storing here would leak the failure into
            // every aggregate output (resolved as fallback for items that completed cleanly).
            verify(contextManager, never()).storeItemResultByScopedKey(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), any());
            verify(contextManager, never()).storeItemResult(
                anyString(), anyString(), anyInt(), anyString(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("Does not record in step-by-step mode - that path already batches via storePerItemResultsInContext, and item index is not yet pinned")
        void shouldNotStoreInStepByStepMode() {
            TestNode clean = new TestNode("core:clean", NodeType.CODE);
            clean.setPredecessors(List.of("mcp:read"));
            clean.setExecuteResult(NodeExecutionResult.success("core:clean", Map.of("id", "id-0")));
            nodeMap.put("core:clean", clean);
            nodeMap.put("mcp:read", new TestNode("mcp:read", NodeType.MCP));
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:clean"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            // 4-param call → successorTraverser=null → step-by-step path.
            executor.execute(clean, context, "run1", nodeMap);

            // Step-by-step takes the executeForAllItemsAndTraverse → storePerItemResultsInContext
            // route, which calls storeResults (batch) - NOT either of the per-item overloads.
            verify(contextManager, never()).storeItemResultByScopedKey(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), any());
            verify(contextManager, never()).storeItemResult(
                anyString(), anyString(), anyInt(), anyString(), anyInt(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("execute() with control flow nodes")
    class ExecuteControlFlowNodes {

        @Test
        @DisplayName("should skip split handling for SPLIT node")
        void shouldSkipForSplitNode() {
            TestNode node = new TestNode("core:split1", NodeType.SPLIT);
            node.setExecuteResult(NodeExecutionResult.success("core:split1", Map.of()));

            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            verify(contextManager, never()).findActiveContext(any(), any(), eq(0), any());
            assertThat(node.getExecuteCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should check split scope for MERGE node (branch-rejoin support) but execute normally when not in scope")
        void shouldCheckSplitScopeForMergeNode() {
            TestNode node = new TestNode("core:merge1", NodeType.MERGE);
            node.setExecuteResult(NodeExecutionResult.success("core:merge1", Map.of()));

            // Merge now checks if it's in split scope (for branch-rejoin per-item execution).
            // When no split context is found, it falls back to normal single execution.
            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            assertThat(node.getExecuteCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should execute LOOP node per-item when in split scope")
        void shouldExecuteLoopPerItemInSplitScope() {
            TestNode loopNode = new TestNode("core:loop1", NodeType.LOOP);
            loopNode.setExecuteResult(NodeExecutionResult.success("core:loop1", Map.of("iteration", 0)));
            nodeMap.put("core:loop1", loopNode);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b"));
            // findSplitContextWithFallback calls findActiveContext (not isInSplitScope)
            when(contextManager.findActiveContext(eq("run1"), eq("core:loop1"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            NodeExecutionResult result = executor.execute(loopNode, context, "run1", nodeMap);

            // Loop in split scope should execute per-item (2 items = 2 executions)
            assertThat(loopNode.getExecuteCount()).isEqualTo(2);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("should execute FORK node per-item when in split scope")
        void shouldExecuteForkPerItemInSplitScope() {
            TestNode forkNode = new TestNode("core:fork1", NodeType.FORK);
            forkNode.setExecuteResult(NodeExecutionResult.success("core:fork1", Map.of()));
            nodeMap.put("core:fork1", forkNode);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("x", "y", "z"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:fork1"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);

            NodeExecutionResult result = executor.execute(forkNode, context, "run1", nodeMap);

            // Fork in split scope should execute per-item (3 items = 3 executions)
            assertThat(forkNode.getExecuteCount()).isEqualTo(3);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("should skip split handling for DECISION node")
        void shouldSkipForDecisionNode() {
            TestNode node = new TestNode("core:decision1", NodeType.DECISION);
            node.setExecuteResult(NodeExecutionResult.success("core:decision1", Map.of()));

            NodeExecutionResult result = executor.execute(node, context, "run1", nodeMap);

            verify(contextManager, never()).findActiveContext(any(), any(), eq(0), any());
        }
    }

    @Nested
    @DisplayName("transitive routing from linear predecessors")
    class TransitiveRouting {

        @Mock
        private WorkflowStepDataRepository mockStepDataRepo;

        private SplitAwareNodeExecutor executorWithRepo;

        @BeforeEach
        void setUpWithRepo() {
            executorWithRepo = new SplitAwareNodeExecutor(
                contextManager,
                null, null, null, mockStepDataRepo, null,
                Executors.newFixedThreadPool(2)
            );
        }

        @AfterEach
        void tearDownWithRepo() {
            executorWithRepo.shutdown();
        }

        @Test
        @DisplayName("should inherit predecessor's item routing for linear successor (wait -> wait_copy)")
        void shouldInheritPredecessorRouting() {
            // Scenario: split(5) -> user_approval -> wait(3 approved) -> wait_copy
            // wait_copy has no port predecessor, but wait only completed items [0, 1, 2]
            TestNode waitCopy = new TestNode("core:wait_copy", NodeType.WAIT);
            waitCopy.setPredecessors(List.of("core:wait")); // linear edge, no port
            waitCopy.setExecuteResult(NodeExecutionResult.success("core:wait_copy", Map.of()));
            nodeMap.put("core:wait_copy", waitCopy);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c", "d", "e"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:wait_copy"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            // Predecessor core:wait only completed for items 0, 1, 2 (3 approved out of 5)
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait", 0))
                .thenReturn(List.of(0, 1, 2));

            NodeExecutionResult result = executorWithRepo.execute(waitCopy, context, "run1", nodeMap);

            // Should only execute 3 items (inherited from predecessor), not all 5
            assertThat(waitCopy.getExecuteCount()).isEqualTo(3);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("should return empty (skip all) when predecessor completed 0 items (all rejected)")
        void shouldSkipAllWhenPredecessorCompletedNone() {
            TestNode waitCopy = new TestNode("core:wait_copy", NodeType.WAIT);
            waitCopy.setPredecessors(List.of("core:wait"));
            waitCopy.setExecuteResult(NodeExecutionResult.success("core:wait_copy", Map.of()));
            nodeMap.put("core:wait_copy", waitCopy);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c", "d", "e"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:wait_copy"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            lenient().when(context.withGlobalData(any(), any())).thenReturn(context);
            lenient().when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            // Predecessor completed 0 items (all rejected upstream)
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait", 0))
                .thenReturn(List.of());

            NodeExecutionResult result = executorWithRepo.execute(waitCopy, context, "run1", nodeMap);

            // Should skip - 0 items routed
            assertThat(waitCopy.getExecuteCount()).isEqualTo(0);
            assertThat(result.status()).isEqualTo(NodeStatus.SKIPPED);
        }

        @Test
        @DisplayName("should fall back to all items on DB exception")
        void shouldFallBackOnDbException() {
            TestNode waitCopy = new TestNode("core:wait_copy", NodeType.WAIT);
            waitCopy.setPredecessors(List.of("core:wait"));
            waitCopy.setExecuteResult(NodeExecutionResult.success("core:wait_copy", Map.of()));
            nodeMap.put("core:wait_copy", waitCopy);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:wait_copy"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            // DB throws
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait", 0))
                .thenThrow(new RuntimeException("DB error"));

            NodeExecutionResult result = executorWithRepo.execute(waitCopy, context, "run1", nodeMap);

            // Falls back to all items (safe default)
            assertThat(waitCopy.getExecuteCount()).isEqualTo(3);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("Implicit merge (2 linear predecessors): UNION routing - runs for every item completed on EITHER branch")
        void shouldUnionMultiplePredecessorsForImplicitMerge() {
            // A node with two linear predecessors IS an implicit merge per
            // BaseNode.isImplicitMerge() (predecessorIds.size() > 1). Per CLAUDE.md's
            // implicit-merge semantics ("ANY node with multiple incoming edges. Wait for ALL
            // predecessors (COMPLETED or SKIPPED)"), the per-item routing across branches is
            // UNION - items completed by ANY branch should reach the merge.
            //
            // Pre-fix this test encoded an INTERSECTION expectation, but that semantic was the
            // root cause of the prod bug on `core:merge_branches` (run_<id>,
            // 2026-05-14): items routed disjointly through a switch hit the rejoin merge with
            // an empty intersection and got marked SKIPPED. The fix in
            // SplitAwareNodeExecutor.getTransitiveRoutedItemIndices applies UNION for both
            // explicit merges (isMergeNode()) and implicit merges (isImplicitMerge()).
            TestNode mergeTarget = new TestNode("mcp:process", NodeType.MCP);
            mergeTarget.setPredecessors(List.of("core:wait_a", "core:wait_b"));
            mergeTarget.setExecuteResult(NodeExecutionResult.success("mcp:process", Map.of()));
            nodeMap.put("mcp:process", mergeTarget);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c", "d", "e"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:process"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            // Predecessor A completed items [0, 1, 2], B completed items [1, 2, 3]
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_a", 0))
                .thenReturn(List.of(0, 1, 2));
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_b", 0))
                .thenReturn(List.of(1, 2, 3));

            NodeExecutionResult result = executorWithRepo.execute(mergeTarget, context, "run1", nodeMap);

            // Union of [0,1,2] and [1,2,3] = [0,1,2,3] → 4 items run.
            assertThat(mergeTarget.getExecuteCount()).isEqualTo(4);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("Implicit merge with DISJOINT predecessor completions: UNION → runs for every routed item (prod bug class)")
        void shouldUnionDisjointPredecessorsForImplicitMerge() {
            // EXACT reproduction of the prod bug class on a NON-explicit merge: a plain MCP
            // node fed by two disjoint switch branches. Pre-fix this returned SKIPPED with
            // executeCount=0 because intersection([0,1],[2,3])=∅. Post-fix the node runs for
            // every item completed on either branch (UNION = [0,1,2,3]).
            TestNode mergeTarget = new TestNode("mcp:process", NodeType.MCP);
            mergeTarget.setPredecessors(List.of("core:wait_a", "core:wait_b"));
            mergeTarget.setExecuteResult(NodeExecutionResult.success("mcp:process", Map.of()));
            nodeMap.put("mcp:process", mergeTarget);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c", "d"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:process"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            lenient().when(context.withGlobalData(any(), any())).thenReturn(context);
            lenient().when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            // Predecessor A completed [0, 1], B completed [2, 3] - disjoint
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_a", 0))
                .thenReturn(List.of(0, 1));
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_b", 0))
                .thenReturn(List.of(2, 3));

            NodeExecutionResult result = executorWithRepo.execute(mergeTarget, context, "run1", nodeMap);

            // Union [0,1] ∪ [2,3] = [0,1,2,3] → 4 items run, COMPLETED (not SKIPPED).
            assertThat(mergeTarget.getExecuteCount()).isEqualTo(4);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        /**
         * Edge case: ONE branch of a switch routed 0 items (e.g. the "stock" port matched
         * nothing), the OTHER branch routed all 4. The merge must still run for the items
         * routed by the non-empty branch - the empty branch is informational, not blocking.
         */
        @Test
        @DisplayName("Merge: one pred empty + other has items → UNION skips the empty pred, runs for the populated subset")
        void mergeWithOnePredEmptyOtherWithItemsRunsForPopulatedSubset() {
            TestNode mergeTarget = new TestNode("mcp:process", NodeType.MCP);
            mergeTarget.setPredecessors(List.of("core:wait_a", "core:wait_b"));
            mergeTarget.setExecuteResult(NodeExecutionResult.success("mcp:process", Map.of()));
            nodeMap.put("mcp:process", mergeTarget);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c", "d"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:process"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            // A completed nothing (e.g. all items routed elsewhere), B completed items [1, 2].
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_a", 0))
                .thenReturn(List.of());
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_b", 0))
                .thenReturn(List.of(1, 2));

            NodeExecutionResult result = executorWithRepo.execute(mergeTarget, context, "run1", nodeMap);

            // The empty pred must NOT short-circuit the merge to SKIPPED - that was the
            // pre-fix linear-only behavior. Merge mode `continue`s past empty preds.
            assertThat(mergeTarget.getExecuteCount())
                .as("merge must inherit the non-empty branch's subset when the other branch is empty")
                .isEqualTo(2);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        /**
         * Edge case: BOTH predecessor branches routed 0 items (e.g. switch matched nothing,
         * fork-style branches both saw empty inputs upstream). The merge has nothing to do
         * and must return SKIPPED with executeCount=0. This is the terminal of the merge-
         * mode `sawPredecessorData=true, inherited==null` branch - without that bookkeeping
         * the post-loop fall-through would incorrectly return allItems and run the merge
         * for every item against empty inputs.
         */
        @Test
        @DisplayName("Merge: BOTH preds empty → SKIPPED (sawPredecessorData routes the terminal correctly)")
        void mergeWithBothPredsEmptyReturnsSkipped() {
            TestNode mergeTarget = new TestNode("mcp:process", NodeType.MCP);
            mergeTarget.setPredecessors(List.of("core:wait_a", "core:wait_b"));
            mergeTarget.setExecuteResult(NodeExecutionResult.success("mcp:process", Map.of()));
            nodeMap.put("mcp:process", mergeTarget);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c", "d"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:process"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            lenient().when(context.withGlobalData(any(), any())).thenReturn(context);
            lenient().when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_a", 0))
                .thenReturn(List.of());
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_b", 0))
                .thenReturn(List.of());

            NodeExecutionResult result = executorWithRepo.execute(mergeTarget, context, "run1", nodeMap);

            assertThat(mergeTarget.getExecuteCount())
                .as("merge with both preds empty must NOT execute (genuinely nothing to do)")
                .isEqualTo(0);
            assertThat(result.status()).isEqualTo(NodeStatus.SKIPPED);
        }

        /**
         * Edge case: one pred of a merge completed ALL items (e.g. it was upstream of the
         * routing, not downstream of it). UNION with the full set is the full set - the fix
         * short-circuits to `allItems` without iterating remaining preds. Asserts the
         * short-circuit path: the second pred mock is never consulted (Mockito strictness
         * would surface an unused stub).
         */
        @Test
        @DisplayName("Merge: one pred covers all items → short-circuit to allItems, remaining preds not consulted")
        void mergeWithOnePredCoveringAllItemsShortCircuits() {
            TestNode mergeTarget = new TestNode("mcp:process", NodeType.MCP);
            mergeTarget.setPredecessors(List.of("core:wait_a", "core:wait_b"));
            mergeTarget.setExecuteResult(NodeExecutionResult.success("mcp:process", Map.of()));
            nodeMap.put("mcp:process", mergeTarget);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c", "d"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:process"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            // A completed every item; B's stub is intentionally lenient - if the short-circuit
            // path holds, this mock is never invoked. (lenient() avoids Mockito strict-stub
            // failure on unused stub.)
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_a", 0))
                .thenReturn(List.of(0, 1, 2, 3));
            lenient().when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_b", 0))
                .thenReturn(List.of(0, 1));

            NodeExecutionResult result = executorWithRepo.execute(mergeTarget, context, "run1", nodeMap);

            // Short-circuit returns allItems → 4 executions, COMPLETED.
            assertThat(mergeTarget.getExecuteCount()).isEqualTo(4);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            // Confirm the short-circuit: B was never queried. (Order-dependent - A appears
            // first in predecessorIds. If the loop order ever flips, this assertion catches
            // it.)
            verify(mockStepDataRepo, never())
                .findCompletedItemIndicesByEpoch("run1", "core:wait_b", 0);
        }

        /**
         * Edge case: DB error on one pred of a 2-pred merge. The legacy single-pred test at
         * L1080 covered the linear path; this asserts the same `return allItems` safe-default
         * applies to merge mode too - a DB hiccup must not silently turn the merge into a
         * SKIPPED no-op (worse than over-running).
         */
        @Test
        @DisplayName("Merge: DB error on one pred falls back to allItems (safe default)")
        void mergeWithDbErrorOnOnePredFallsBackToAllItems() {
            TestNode mergeTarget = new TestNode("mcp:process", NodeType.MCP);
            mergeTarget.setPredecessors(List.of("core:wait_a", "core:wait_b"));
            mergeTarget.setExecuteResult(NodeExecutionResult.success("mcp:process", Map.of()));
            nodeMap.put("mcp:process", mergeTarget);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:process"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_a", 0))
                .thenThrow(new RuntimeException("DB error"));
            // B's stub is lenient - once A throws, the early return skips B entirely.
            lenient().when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait_b", 0))
                .thenReturn(List.of(0, 1));

            NodeExecutionResult result = executorWithRepo.execute(mergeTarget, context, "run1", nodeMap);

            // Safe default: run for every item rather than dropping work silently.
            assertThat(mergeTarget.getExecuteCount()).isEqualTo(3);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("should not filter by split node itself (split completes once, not per-item)")
        void shouldNotFilterBySplitNodeItself() {
            // Scenario: split(5) -> user_approval
            // user_approval's predecessor is core:split_items (the split node itself).
            // Split nodes complete once with itemIndex=0, so querying completed items
            // would return [0] - incorrectly filtering to 1 item. The split node must be skipped.
            TestNode approval = new TestNode("core:user_approval", NodeType.APPROVAL);
            approval.setPredecessors(List.of("core:split_items")); // predecessor IS the split
            approval.setExecuteResult(NodeExecutionResult.success("core:user_approval", Map.of()));
            nodeMap.put("core:user_approval", approval);
            nodeMap.put("core:split_items", new TestNode("core:split_items", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split_items:0", List.of("a", "b", "c", "d", "e"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:user_approval"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            // DO NOT stub mockStepDataRepo - split node should be skipped entirely
            // If it were queried, it would return [0] (1 item) and break routing

            NodeExecutionResult result = executorWithRepo.execute(approval, context, "run1", nodeMap);

            // All 5 items should execute (split node is not a filter)
            assertThat(approval.getExecuteCount()).isEqualTo(5);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("should not filter by nested split node (splitNodeId with /sN suffix)")
        void shouldNotFilterByNestedSplitNode() {
            // Scenario: nested split "core:outer_split:0/s1" -> inner node
            TestNode innerNode = new TestNode("mcp:inner_step", NodeType.MCP);
            innerNode.setPredecessors(List.of("core:outer_split")); // predecessor is the outer split
            innerNode.setExecuteResult(NodeExecutionResult.success("mcp:inner_step", Map.of()));
            nodeMap.put("mcp:inner_step", innerNode);
            nodeMap.put("core:outer_split", new TestNode("core:outer_split", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:outer_split:0/s1", List.of("x", "y", "z"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:inner_step"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            NodeExecutionResult result = executorWithRepo.execute(innerNode, context, "run1", nodeMap);

            // All 3 items should execute (nested split node is not a filter)
            assertThat(innerNode.getExecuteCount()).isEqualTo(3);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("should execute all items when predecessor completed all")
        void shouldExecuteAllWhenPredecessorCompletedAll() {
            TestNode waitCopy = new TestNode("core:wait_copy", NodeType.WAIT);
            waitCopy.setPredecessors(List.of("core:wait"));
            waitCopy.setExecuteResult(NodeExecutionResult.success("core:wait_copy", Map.of()));
            nodeMap.put("core:wait_copy", waitCopy);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:wait_copy"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(org.mockito.ArgumentMatchers.anyInt())).thenReturn(context);
            when(context.epoch()).thenReturn(0);

            // Predecessor completed ALL items
            when(mockStepDataRepo.findCompletedItemIndicesByEpoch("run1", "core:wait", 0))
                .thenReturn(List.of(0, 1, 2));

            NodeExecutionResult result = executorWithRepo.execute(waitCopy, context, "run1", nodeMap);

            // All 3 items = 3 items (no filtering)
            assertThat(waitCopy.getExecuteCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("isInSplitScope()")
    class IsInSplitScope {

        @Test
        @DisplayName("should delegate to context manager")
        void shouldDelegateToContextManager() {
            // Legacy 3-parameter version delegates to 4-parameter version with itemIndex=0
            when(contextManager.isInSplitScope("run1", "mcp:step1", 0, nodeMap))
                .thenReturn(true);

            boolean result = executor.isInSplitScope("run1", "mcp:step1", nodeMap);

            assertThat(result).isTrue();
            verify(contextManager).isInSplitScope("run1", "mcp:step1", 0, nodeMap);
        }
    }

    /**
     * Regression for the split×loop iteration clobber bug (audit 2026-05-08, 3 Opus rounds).
     *
     * <p>When a Loop runs INSIDE a Split scope, body downstream nodes (transform/mcp/code/agent)
     * resolved {@code {{core:loop.output.iteration}}} to 0 instead of the live counter, because
     * {@link SplitAwareNodeExecutor#injectPredecessorPerItemOutputs} clobbers stepOutputs[loop_core]
     * with the per-item snapshot frozen at {@code LoopNode.execute} (iteration=0). The fix
     * applies BackEdgeHandler's loop core overrides (stored in globalData under
     * {@link com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler#LOOP_CORE_OUTPUT_OVERRIDES_KEY})
     * after the predecessor injection, restoring the live value.
     *
     * <p>These tests target {@code reapplyLoopCoreOverrides} directly (package-private) since
     * the helper is the load-bearing piece. End-to-end coverage exists separately (workflow
     * 0d8b0eef-5b55-408a-8d05-369dd4c553f4 split×loop run).
     */
    @Nested
    @DisplayName("reapplyLoopCoreOverrides() - split×loop iteration clobber regression")
    class ReapplyLoopCoreOverridesRegression {

        private ExecutionContext freshContext() {
            return ExecutionContext.create(
                "run-loop-test",
                "wfr-test",
                "tenant-test",
                "item-0",
                0,
                new HashMap<>(),
                null
            );
        }

        @SuppressWarnings("unchecked")
        private Integer iterationOf(ExecutionContext ctx, String loopCoreKey) {
            Object stepOutput = ctx.getAllStepOutputs().get(loopCoreKey);
            if (!(stepOutput instanceof Map<?, ?> m)) return null;
            Object inner = m.get("output");
            if (inner instanceof Map<?, ?> innerMap) {
                Object iter = ((Map<String, Object>) innerMap).get("iteration");
                return iter instanceof Number n ? n.intValue() : null;
            }
            return null;
        }

        @Test
        @DisplayName("Restores live loop iteration after predecessor injection clobbers it (regression bite)")
        void restoresLiveIterationAfterClobber() {
            // Simulate the post-clobber state: stepOutputs[core:loop] holds a stale snapshot
            // (iteration=0 - what LoopNode.execute initially wrote and what
            // injectPredecessorPerItemOutputs restored).
            Map<String, Object> staleLoopOutput = new HashMap<>();
            Map<String, Object> staleInner = new HashMap<>();
            staleInner.put("iteration", 0);
            staleInner.put("terminated", false);
            staleLoopOutput.put("output", staleInner);
            staleLoopOutput.put("iteration", 0);

            // BackEdgeHandler has stamped the live iteration=2 into globalData.
            Map<String, Object> liveLoopOutput = new HashMap<>();
            Map<String, Object> liveInner = new HashMap<>();
            liveInner.put("iteration", 2);
            liveInner.put("terminated", false);
            liveLoopOutput.put("output", liveInner);
            liveLoopOutput.put("iteration", 2);

            ExecutionContext ctx = freshContext()
                .withStepOutput("core:loop", staleLoopOutput)
                .withGlobalData(
                    com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY,
                    Map.of("core:loop", liveLoopOutput));

            // PRE-FIX behavior would be: stepOutputs[core:loop].output.iteration == 0 (stale).
            assertThat(iterationOf(ctx, "core:loop"))
                .as("Sanity: pre-call stepOutput is the stale snapshot")
                .isEqualTo(0);

            ExecutionContext restored = executor.reapplyLoopCoreOverrides(ctx);

            // POST-FIX: live value from globalData wins.
            assertThat(iterationOf(restored, "core:loop"))
                .as("Live iteration from globalData override must overwrite the stale snapshot")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("No override in globalData - context returned unchanged")
        void noOverrideReturnsContextUnchanged() {
            Map<String, Object> stepOut = new HashMap<>();
            stepOut.put("output", Map.of("iteration", 0));
            stepOut.put("iteration", 0);
            ExecutionContext ctx = freshContext().withStepOutput("core:loop", stepOut);

            ExecutionContext result = executor.reapplyLoopCoreOverrides(ctx);

            // No override → no rewrite.
            assertThat(iterationOf(result, "core:loop")).isEqualTo(0);
        }

        @Test
        @DisplayName("Multiple loop overrides - all restored")
        void multipleLoopOverridesAllRestored() {
            Map<String, Object> override1 = Map.of("output", Map.of("iteration", 3), "iteration", 3);
            Map<String, Object> override2 = Map.of("output", Map.of("iteration", 5), "iteration", 5);

            ExecutionContext ctx = freshContext()
                .withStepOutput("core:outer_loop", Map.of("output", Map.of("iteration", 0), "iteration", 0))
                .withStepOutput("core:inner_loop", Map.of("output", Map.of("iteration", 0), "iteration", 0))
                .withGlobalData(
                    com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY,
                    Map.of("core:outer_loop", override1, "core:inner_loop", override2));

            ExecutionContext restored = executor.reapplyLoopCoreOverrides(ctx);

            assertThat(iterationOf(restored, "core:outer_loop")).isEqualTo(3);
            assertThat(iterationOf(restored, "core:inner_loop")).isEqualTo(5);
        }

        @Test
        @DisplayName("Null state context - no NPE, returned unchanged (test-mock safety)")
        void nullStateContextNoNpe() {
            // Some unit-test contexts have null state. The guard prevents NPE on getGlobalData.
            ExecutionContext mockedNullState = org.mockito.Mockito.mock(ExecutionContext.class);
            when(mockedNullState.state()).thenReturn(null);

            ExecutionContext result = executor.reapplyLoopCoreOverrides(mockedNullState);

            assertThat(result).isSameAs(mockedNullState);
        }

        /**
         * WIRING bite test: exercises the full enrichContextWithItem path -
         * injectPredecessorPerItemOutputs CLOBBERS stepOutputs[loop_core] with the per-item
         * frozen snapshot (iter=0), then reapplyLoopCoreOverrides MUST restore the live
         * value from globalData. If a future refactor removes the reapplyLoopCoreOverrides
         * call from enrichContextWithItem, THIS test fails (the helper-only tests above
         * would still pass - they don't catch wiring regression).
         */
        @Test
        @DisplayName("Wiring: enrichContextWithItem preserves live loop iteration after predecessor clobber")
        void enrichContextWithItemPreservesLiveIterationAfterClobber() {
            // SplitContext with a frozen iter=0 snapshot of the loop core for item index 1
            // (simulates what storePerItemResultsInContext writes after LoopNode.execute).
            Map<String, Object> frozenLoopOutput = Map.of(
                "iteration", 0,
                "terminated", false,
                "output", Map.of("iteration", 0, "terminated", false));
            SplitContext splitContext = SplitContext.create("core:split:0",
                    List.of(Map.of("id", "X"), Map.of("id", "Y"), Map.of("id", "Z")))
                .withResults("core:loop", List.of(frozenLoopOutput, frozenLoopOutput, frozenLoopOutput));

            // Outer context has the live iteration override stamped by BackEdgeHandler.updateLoopStepOutput
            Map<String, Object> liveLoopOutput = Map.of(
                "iteration", 2,
                "terminated", false,
                "output", Map.of("iteration", 2, "terminated", false));
            ExecutionContext outerContext = freshContext()
                .withGlobalData(
                    com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY,
                    Map.of("core:loop", liveLoopOutput));

            // Call enrichContextWithItem - same path used in production for split per-item dispatch.
            ExecutionContext enriched = executor.enrichContextWithItem(
                outerContext,
                Map.of("id", "Y"),
                1,
                List.of(Map.of("id", "X"), Map.of("id", "Y"), Map.of("id", "Z")),
                splitContext
            );

            // After the full enrich pipeline (including injectPredecessorPerItemOutputs which
            // would clobber to iter=0 if reapplyLoopCoreOverrides was missing), the loop core
            // stepOutput must reflect the LIVE iteration=2 from the override.
            assertThat(iterationOf(enriched, "core:loop"))
                .as("Live loop iteration must survive predecessor injection (wiring regression bite)")
                .isEqualTo(2);
        }
    }

    /**
     * <b>Daily Email Digest bare-alias regression bite (2026-05-09):</b>
     * In prod (run {@code 6c67cb76-...}, epoch 3), the chain
     * {@code split → mcp:read_email → core:clean_email (CodeNode reading $input.read_email)}
     * persisted 12 distinct read_email rows but clean_email saw item 0's email for ALL
     * 12 items. Root cause: {@code V2StepByStepContextManager} loaded the OUTER context
     * with both {@code mcp:read_email} (full key) and {@code read_email} (bare alias)
     * pointing at item 0; {@code SplitAwareNodeExecutor.injectPredecessorPerItemOutputs}
     * only OVERWROTE the full-key entry per item, leaving the bare-alias entry stuck
     * on item 0. {@code CodeNode.buildInputData} surfaces the bare alias to JS, so
     * {@code $input.read_email} resolved to item 0 for every item.
     *
     * <p>Fix: {@code injectPredecessorPerItemOutputs} and
     * {@code injectCurrentItemIntoStepOutputs} now write a companion entry under the
     * bare alias (e.g., {@code read_email}, {@code split_emails}) alongside the full
     * key. Loop core override paths
     * ({@code applyLoopCoreOutputOverrides} + {@code reapplyLoopCoreOverrides}) do the
     * same so {@code $input.<loop_alias>.iteration} sees the live counter inside
     * a CodeNode body that is itself inside a split×loop scope.
     */
    @Nested
    @DisplayName("bare-alias per-item companion writes (Daily Email Digest regression)")
    class BareAliasPerItemRegression {

        private ExecutionContext freshContext() {
            return ExecutionContext.create(
                "run-bare-alias",
                "wfr-bare-alias",
                "tenant-bare-alias",
                "item-0",
                0,
                new HashMap<>(),
                null
            );
        }

        @SuppressWarnings("unchecked")
        private Object aliasOutput(ExecutionContext ctx, String alias, String field) {
            Object wrapper = ctx.getAllStepOutputs().get(alias);
            if (!(wrapper instanceof Map<?, ?> m)) return null;
            Object output = m.get("output");
            if (!(output instanceof Map<?, ?> outMap)) return null;
            return ((Map<String, Object>) outMap).get(field);
        }

        @Test
        @DisplayName("injectPredecessorPerItemOutputs writes the per-item value under the bare alias (read_email), not just the full key (mcp:read_email)")
        void perItemInjectionWritesBareAlias() {
            // Simulate the prod chain after read_email has executed for 3 items.
            // splitContext.resultsByNode["mcp:read_email"] = [emailA, emailB, emailC].
            Map<String, Object> emailA = Map.of("id", "msg-A", "from", "alice@example.com");
            Map<String, Object> emailB = Map.of("id", "msg-B", "from", "bob@example.com");
            Map<String, Object> emailC = Map.of("id", "msg-C", "from", "carol@example.com");

            SplitContext splitContext = SplitContext.create(
                    "core:split_emails:0",
                    List.of(Map.of("id", "X"), Map.of("id", "Y"), Map.of("id", "Z")))
                .withResults("mcp:read_email", List.of(emailA, emailB, emailC));

            // Outer context has BOTH the full key AND the bare alias from V2StepByStepContextManager
            // DB load - both pointing at item 0 (the OUTER SBS step's itemIndex).
            ExecutionContext outerContext = freshContext()
                .withStepOutput("mcp:read_email", Map.of(
                    "output", emailA,
                    "httpstatus", 200))
                .withStepOutput("read_email", Map.of(
                    "output", emailA,
                    "httpstatus", 200));

            // Enrich for item 1 (Y) - the per-item path the executor takes for chained downstream.
            ExecutionContext enriched = executor.enrichContextWithItem(
                outerContext,
                Map.of("id", "Y"),
                1,
                List.of(Map.of("id", "X"), Map.of("id", "Y"), Map.of("id", "Z")),
                splitContext
            );

            // BEFORE fix: aliasOutput(enriched, "read_email", "id") == "msg-A" (item 0).
            // AFTER fix: aliasOutput(enriched, "read_email", "id") == "msg-B" (item 1, this branch).
            assertThat(aliasOutput(enriched, "mcp:read_email", "id"))
                .as("Full-key entry must reflect this item's value")
                .isEqualTo("msg-B");
            assertThat(aliasOutput(enriched, "read_email", "id"))
                .as("Bare-alias entry must ALSO reflect this item's value - without "
                  + "this companion write, CodeNode's $input.read_email sees item 0 for every item.")
                .isEqualTo("msg-B");
        }

        @Test
        @DisplayName("Per-item value differs across branches - alias rewrite is per-branch, not last-write-wins")
        void perItemAliasIsPerBranchNotLastWriteWins() {
            Map<String, Object> emailA = Map.of("id", "msg-A");
            Map<String, Object> emailB = Map.of("id", "msg-B");
            Map<String, Object> emailC = Map.of("id", "msg-C");

            SplitContext splitContext = SplitContext.create(
                    "core:split_emails:0",
                    List.of(Map.of("id", "X"), Map.of("id", "Y"), Map.of("id", "Z")))
                .withResults("mcp:read_email", List.of(emailA, emailB, emailC));

            ExecutionContext outer = freshContext();

            // Each per-item enrich call produces an INDEPENDENT context - they don't share
            // mutation state, so item 0's enrich must not pollute item 2's enrich.
            ExecutionContext item0 = executor.enrichContextWithItem(
                outer, Map.of("id", "X"), 0,
                List.of(Map.of("id", "X"), Map.of("id", "Y"), Map.of("id", "Z")), splitContext);
            ExecutionContext item2 = executor.enrichContextWithItem(
                outer, Map.of("id", "Z"), 2,
                List.of(Map.of("id", "X"), Map.of("id", "Y"), Map.of("id", "Z")), splitContext);

            assertThat(aliasOutput(item0, "read_email", "id")).isEqualTo("msg-A");
            assertThat(aliasOutput(item2, "read_email", "id")).isEqualTo("msg-C");
        }

        @Test
        @DisplayName("injectCurrentItemIntoStepOutputs also writes split's current_item under bare alias (split_emails) - not just core:split_emails")
        void splitCurrentItemUnderBareAlias() {
            Map<String, Object> currentItem = Map.of("id", "Y", "rank", 42);

            SplitContext splitContext = SplitContext.create(
                    "core:split_emails:0",
                    List.of(Map.of("id", "X"), Map.of("id", "Y"), Map.of("id", "Z")));

            ExecutionContext enriched = executor.enrichContextWithItem(
                freshContext(),
                currentItem,
                1,
                List.of(Map.of("id", "X"), currentItem, Map.of("id", "Z")),
                splitContext
            );

            // Both keys must carry current_item AND current_index for this branch.
            assertThat(aliasOutput(enriched, "core:split_emails", "current_item"))
                .as("Full-key split output must carry current_item for this branch")
                .isEqualTo(currentItem);
            assertThat(aliasOutput(enriched, "split_emails", "current_item"))
                .as("Bare-alias split output must ALSO carry current_item - without this, "
                  + "$input.split_emails.current_item resolves to item 0 in CodeNode JS.")
                .isEqualTo(currentItem);
            assertThat(aliasOutput(enriched, "split_emails", "current_index"))
                .isEqualTo(1);
        }

        @Test
        @DisplayName("reapplyLoopCoreOverrides writes the live loop iteration under the bare loop alias too - while-loop awareness")
        void reapplyLoopOverridesWritesBareAlias() {
            // Live loop iteration override stamped by BackEdgeHandler.updateLoopStepOutput.
            Map<String, Object> liveLoopOutput = new HashMap<>();
            liveLoopOutput.put("iteration", 3);
            liveLoopOutput.put("terminated", false);
            liveLoopOutput.put("output", Map.of("iteration", 3, "terminated", false));

            ExecutionContext outer = freshContext()
                .withGlobalData(
                    com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY,
                    Map.of("core:my_loop", liveLoopOutput));

            ExecutionContext after = executor.reapplyLoopCoreOverrides(outer);

            // Both keys must carry the live iteration counter so CodeNode JS reading
            // $input.my_loop.iteration sees 3, not the iter=0 from the OUTER DB load.
            assertThat(after.getAllStepOutputs().get("core:my_loop"))
                .as("Full-key loop output must carry the live override")
                .isEqualTo(liveLoopOutput);
            assertThat(after.getAllStepOutputs().get("my_loop"))
                .as("Bare-alias loop output must ALSO carry the live override - required for "
                  + "while-loop awareness in CodeNode body that reads $input.my_loop.iteration.")
                .isEqualTo(liveLoopOutput);
        }
    }

    @Nested
    @DisplayName("async-coalesce barrier - per-epoch setRunningCount (P2.3.1)")
    class AsyncCoalesceBarrierEpochThreading {

        @Test
        @DisplayName("threads context.epoch() into setRunningCount(runId, barrierEpoch, nodeId, count) (non-zero epoch)")
        void shouldThreadContextEpochIntoSetRunningCount() {
            // Pin SplitAwareNodeExecutor:466 - when split items yield asyncRunning,
            // the barrier MUST register the running count under the OUTER context's
            // epoch (the barrier's own epoch), NOT epoch=0. Symmetry is the contract:
            // emitNodeStart marked the node under (runId, barrierEpoch); each async
            // completion will markCompleted under the SAME (runId, barrierEpoch) key.
            // If the barrier flipped to epoch=0 here, the count would be applied to a
            // different Redis key and never decrement, leaving "running=1" forever
            // for the live epoch and breaking the §3.6.1 deferred-reset gate.
            int barrierEpoch = 7;

            // Build a real ExecutionContext at epoch=7 - simpler than mocking the chain.
            ExecutionContext realContext = ExecutionContext.create(
                "run-async-1",       // runId
                "wfr-async-1",       // workflowRunId
                "tenant-1",          // tenantId
                "0",                 // itemId
                0,                   // itemIndex
                "trigger:start",     // triggerId
                barrierEpoch,        // epoch - THE LOAD-BEARING VALUE
                0,                   // spawn
                new HashMap<>(),     // triggerData
                null                 // plan
            );

            // The async path needs:
            //   - a non-null WorkflowExecution (gates the async branch at line 449)
            //   - a non-null splitCoalesceTracker (gates the async branch)
            //   - a non-null runningNodeTracker (gates the setRunningCount call)
            //   - a non-null snapshotService (called immediately after setRunningCount)
            com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution execMock =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution.class);
            com.apimarketplace.orchestrator.execution.v2.async.SplitCoalesceTracker tracker =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.async.SplitCoalesceTracker.class);
            com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker rnTracker =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker.class);
            com.apimarketplace.orchestrator.services.streaming.SnapshotService snapshot =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.streaming.SnapshotService.class);

            // Build a fresh executor with non-null SnapshotService so the barrier's
            // sendSnapshotImmediate call doesn't NPE. nodeCompletionService stays
            // null so canPersist=false and no persist path runs for the async items.
            SplitAwareNodeExecutor freshExecutor = new SplitAwareNodeExecutor(
                contextManager,
                null,        // NodeCompletionService
                null,        // EdgeStatusEmitter
                snapshot,    // SnapshotService - needed by line 472
                null,        // WorkflowStepDataRepository
                null,        // StateSnapshotService
                Executors.newFixedThreadPool(2)
            );
            freshExecutor.setSplitCoalesceTracker(tracker);
            freshExecutor.setRunningNodeTracker(rnTracker);

            // Async-yielding node, marked as a direct successor of core:split1
            TestNode asyncNode = new TestNode("agent:async_step", NodeType.AGENT);
            asyncNode.setPredecessors(List.of("core:split1"));
            asyncNode.setExecuteResult(NodeExecutionResult.asyncRunning(
                "agent:async_step",
                "corr-1",
                "agent",
                Map.of()
            ));
            Map<String, ExecutionNode> localNodeMap = new HashMap<>();
            localNodeMap.put("agent:async_step", asyncNode);
            localNodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            // Active split context with 3 items at workflowItemIndex=0
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run-async-1"), eq("agent:async_step"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));

            try {
                // 8-arg overload - passes execution so the async branch is taken.
                NodeExecutionResult result = freshExecutor.execute(
                    asyncNode, realContext, "run-async-1", localNodeMap,
                    execMock, null, 0, null);

                // Contract: barrier registered with the OUTER context's epoch (7), not 0.
                verify(tracker).register("run-async-1", "agent:async_step", barrierEpoch, 3);
                // Contract: setRunningCount must use the SAME barrierEpoch as register().
                verify(rnTracker).setRunningCount("run-async-1", barrierEpoch, "agent:async_step", 3);
                // Snapshot is sent so the frontend sees the live count immediately.
                verify(snapshot).sendSnapshotImmediate("run-async-1");

                // Sanity: 3 items executed (one per split item).
                assertThat(asyncNode.getExecuteCount()).isEqualTo(3);
                assertThat(result).isNotNull();
            } finally {
                freshExecutor.shutdown();
            }
        }
    }

    /**
     * Regression: persistItemResult MUST route edge emission through
     * V2ExecutionEventService so the per-epoch workflow_epochs table is
     * written. Pre-fix called edgeStatusEmitter.emitOutgoingEdges directly,
     * which fell into EdgeStatusService "immediate mode" - the top-level
     * StateSnapshot.edges map got the edge, but the per-epoch table did NOT.
     * That left the frontend epoch viewer blind to body-node edges (e.g.
     * check_memory→is_new, is_new:if→exit) for any split-body item.
     */
    @Nested
    @DisplayName("persistItemResult - per-epoch edge recording (regression)")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class PersistItemResultPerEpochRecording {

        @Test
        @DisplayName("Routes body-item edges through V2ExecutionEventService.emitItemOutgoingEdgesInSplit so workflow_epochs gets per-epoch rows")
        void shouldRouteEdgeEmitThroughEventServiceForPerEpochRecording() {
            ExecutionContext realContext = ExecutionContext.create(
                "run-edge-1", "wfr-edge-1", "tenant-1", "0", 0,
                "trigger:start", 9, 0, new HashMap<>(), null);

            com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution execMock =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution.class);
            org.mockito.Mockito.when(execMock.getRunId()).thenReturn("run-edge-1");

            com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService nodeCompletion =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService.class);
            com.apimarketplace.orchestrator.execution.v2.services.EdgeStatusEmitter edgeEmitter =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.services.EdgeStatusEmitter.class);
            com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService eventSvc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService.class);

            SplitAwareNodeExecutor freshExecutor = new SplitAwareNodeExecutor(
                contextManager, nodeCompletion, edgeEmitter,
                null, null, null,
                Executors.newFixedThreadPool(2));
            freshExecutor.setEventService(eventSvc);

            TestNode bodyNode = new TestNode("mcp:body_node_a", NodeType.MCP);
            bodyNode.setPredecessors(List.of("core:split1"));
            bodyNode.setExecuteResult(NodeExecutionResult.success(
                "mcp:body_node_a", Map.of("ok", true)));

            Map<String, ExecutionNode> localNodeMap = new HashMap<>();
            localNodeMap.put("mcp:body_node_a", bodyNode);
            localNodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0",
                List.of(Map.of("id", "X"), Map.of("id", "Y")));
            org.mockito.Mockito.when(contextManager.findActiveContext(
                eq("run-edge-1"), eq("mcp:body_node_a"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));

            try {
                freshExecutor.execute(bodyNode, realContext, "run-edge-1", localNodeMap,
                    execMock, null, 0, null);

                // Pin: eventService.emitItemOutgoingEdgesInSplit MUST be called per item
                // with epoch=context.epoch() and triggerId=context.triggerId().
                // The §3.6.1-style epoch-threading contract - a future regression that
                // hardcodes 0 or null would silently re-introduce the bug, so we pin
                // the exact load-bearing values here.
                org.mockito.Mockito.verify(eventSvc, org.mockito.Mockito.atLeastOnce())
                    .emitItemOutgoingEdgesInSplit(
                        eq(execMock),
                        eq(bodyNode),
                        anyInt(),                    // subItemIndex (0 then 1)
                        any(),                       // iteration (null but Integer; lenient)
                        any(NodeExecutionResult.class),
                        eq(false),                   // suppressSkipPropagation = false (non-branching MCP node)
                        eq(9),                       // epoch from realContext
                        eq("trigger:start"));        // triggerId from realContext

                // Negative pin: legacy direct-emit fallback MUST NOT fire when eventService
                // is wired. Catches future "just call edgeEmitter to be sure" regressions.
                org.mockito.Mockito.verify(edgeEmitter, org.mockito.Mockito.never())
                    .emitOutgoingEdges(any(), any(), anyInt(), any(), any(),
                        org.mockito.ArgumentMatchers.anyBoolean(), anyInt(), any(), eq(true));
            } finally {
                freshExecutor.shutdown();
            }
        }

        @Test
        @DisplayName("FAILED branching body item suppresses emitter recursion and invokes per-item cascade")
        void failedBranchingBodyItemSuppressesEmitterRecursionAndCascades() {
            ExecutionContext realContext = ExecutionContext.create(
                "run-fail-branch-1", "wfr-fail-branch-1", "tenant-1", "0", 0,
                "trigger:start", 11, 0, new HashMap<>(), null);

            com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution execMock =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution.class);
            org.mockito.Mockito.when(execMock.getRunId()).thenReturn("run-fail-branch-1");

            com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService nodeCompletion =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService.class);
            com.apimarketplace.orchestrator.execution.v2.services.EdgeStatusEmitter edgeEmitter =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.services.EdgeStatusEmitter.class);
            com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService eventSvc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService.class);
            com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService skipSvc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService.class);

            SplitAwareNodeExecutor freshExecutor = new SplitAwareNodeExecutor(
                contextManager, nodeCompletion, edgeEmitter,
                null, null, null,
                Executors.newFixedThreadPool(2));
            freshExecutor.setEventService(eventSvc);
            freshExecutor.setSkipPropagationService(skipSvc);

            TestNode guardrail = new TestNode("agent:compliance_screen", NodeType.AGENT) {
                @Override
                public boolean isBranchingNode() {
                    return true;
                }
            };
            guardrail.setPredecessors(List.of("core:split1"));
            guardrail.setExecuteResult(NodeExecutionResult.failure(
                "agent:compliance_screen", "Guardrail provider failed"));

            Map<String, ExecutionNode> localNodeMap = new HashMap<>();
            localNodeMap.put("agent:compliance_screen", guardrail);
            localNodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0", List.of(Map.of("id", "X")));
            org.mockito.Mockito.when(contextManager.findActiveContext(
                    eq("run-fail-branch-1"), eq("agent:compliance_screen"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));

            try {
                freshExecutor.execute(guardrail, realContext, "run-fail-branch-1", localNodeMap,
                    execMock, null, 0, null);

                org.mockito.Mockito.verify(eventSvc).emitItemOutgoingEdgesInSplit(
                    eq(execMock),
                    eq(guardrail),
                    eq(0),
                    any(),
                    org.mockito.ArgumentMatchers.argThat(r -> r != null && r.isFailure()),
                    eq(true),
                    eq(11),
                    eq("trigger:start"));
                org.mockito.Mockito.verify(skipSvc).cascadeFailureToSuccessors(
                    eq(execMock), eq(guardrail), eq(0), eq(11), eq("trigger:start"),
                    eq(true), eq("split_failure"));
            } finally {
                freshExecutor.shutdown();
            }
        }

        @Test
        @DisplayName("Falls back to direct edgeEmitter when eventService not wired (preserves legacy unit-test wiring)")
        void shouldFallBackToEdgeEmitterWhenEventServiceNull() {
            ExecutionContext realContext = ExecutionContext.create(
                "run-fallback-1", "wfr-fallback-1", "tenant-1", "0", 0,
                "trigger:start", 0, 0, new HashMap<>(), null);

            com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution execMock =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution.class);

            com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService nodeCompletion =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService.class);
            com.apimarketplace.orchestrator.execution.v2.services.EdgeStatusEmitter edgeEmitter =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.services.EdgeStatusEmitter.class);

            SplitAwareNodeExecutor freshExecutor = new SplitAwareNodeExecutor(
                contextManager, nodeCompletion, edgeEmitter,
                null, null, null,
                Executors.newFixedThreadPool(2));
            // Intentionally NOT calling setEventService - eventService stays null.

            TestNode bodyNode = new TestNode("mcp:body_node_b", NodeType.MCP);
            bodyNode.setPredecessors(List.of("core:split1"));
            bodyNode.setExecuteResult(NodeExecutionResult.success(
                "mcp:body_node_b", Map.of("ok", true)));

            Map<String, ExecutionNode> localNodeMap = new HashMap<>();
            localNodeMap.put("mcp:body_node_b", bodyNode);
            localNodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:split1:0",
                List.of(Map.of("id", "X")));
            org.mockito.Mockito.when(contextManager.findActiveContext(
                eq("run-fallback-1"), eq("mcp:body_node_b"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));

            try {
                freshExecutor.execute(bodyNode, realContext, "run-fallback-1", localNodeMap,
                    execMock, null, 0, null);

                // With eventService null, persistItemResult falls back to direct
                // emitOutgoingEdges. Per-epoch recording is lost (acceptable in tests)
                // but the StateSnapshot.edges map still gets the edge (immediate mode).
                org.mockito.Mockito.verify(edgeEmitter, org.mockito.Mockito.atLeastOnce())
                    .emitOutgoingEdges(any(), any(), anyInt(), any(), any(),
                        org.mockito.ArgumentMatchers.anyBoolean(), anyInt(), any(), eq(true));
            } finally {
                freshExecutor.shutdown();
            }
        }
    }

    @Nested
    @DisplayName("Coalescing session gating (orchestrator.optim.coalesce-split)")
    class CoalescingSessionGating {

        private SplitAwareNodeExecutor newExecutor(
                com.apimarketplace.orchestrator.services.state.patch.RunCoalescingService coalescer,
                boolean flagOn) throws Exception {
            SplitAwareNodeExecutor ex = new SplitAwareNodeExecutor(
                contextManager, null, null, null, null, null, Executors.newFixedThreadPool(2));
            ex.setRunCoalescingService(coalescer);
            java.lang.reflect.Field flag = SplitAwareNodeExecutor.class.getDeclaredField("coalesceSplitEnabled");
            flag.setAccessible(true);
            flag.setBoolean(ex, flagOn);
            return ex;
        }

        private void wireThreeItemSplit(TestNode node) {
            node.setPredecessors(List.of("core:split1"));
            node.setExecuteResult(NodeExecutionResult.success(node.getNodeId(), Map.of("ok", true)));
            nodeMap.put(node.getNodeId(), node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq(node.getNodeId()), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            lenient().when(context.withGlobalData(any(), any())).thenReturn(context);
            lenient().when(context.withItemIndex(anyInt())).thenReturn(context);
        }

        @Test
        @DisplayName("Flag ON → opens exactly one coalescing session and closes it (try/finally) around the fan-out")
        void flagOnOpensAndClosesSession() throws Exception {
            var coalescer = org.mockito.Mockito.mock(
                com.apimarketplace.orchestrator.services.state.patch.RunCoalescingService.class);
            // openCoalescing returns a non-null session → coalescingOpened=true.
            when(coalescer.openCoalescing("run1")).thenReturn(
                org.mockito.Mockito.mock(
                    com.apimarketplace.orchestrator.services.state.patch.RunCoalescingService.RunSession.class));
            SplitAwareNodeExecutor ex = newExecutor(coalescer, true);

            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            wireThreeItemSplit(node);

            try {
                NodeExecutionResult result = ex.execute(node, context, "run1", nodeMap);
                assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
                verify(coalescer, times(1)).openCoalescing("run1");
                verify(coalescer, times(1)).closeCoalescing("run1");
            } finally {
                ex.shutdown();
            }
        }

        @Test
        @DisplayName("Flag OFF → never opens a coalescing session (today's direct-CAS behavior)")
        void flagOffNeverOpensSession() throws Exception {
            var coalescer = org.mockito.Mockito.mock(
                com.apimarketplace.orchestrator.services.state.patch.RunCoalescingService.class);
            SplitAwareNodeExecutor ex = newExecutor(coalescer, false);

            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            wireThreeItemSplit(node);

            try {
                NodeExecutionResult result = ex.execute(node, context, "run1", nodeMap);
                assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
                verify(coalescer, never()).openCoalescing(anyString());
                verify(coalescer, never()).closeCoalescing(anyString());
            } finally {
                ex.shutdown();
            }
        }

        @Test
        @DisplayName("Flag ON but capacity full (openCoalescing returns null) → no closeCoalescing, fan-out completes")
        void flagOnButCapacityFull() throws Exception {
            var coalescer = org.mockito.Mockito.mock(
                com.apimarketplace.orchestrator.services.state.patch.RunCoalescingService.class);
            when(coalescer.openCoalescing("run1")).thenReturn(null);  // cap full
            SplitAwareNodeExecutor ex = newExecutor(coalescer, true);

            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            wireThreeItemSplit(node);

            try {
                NodeExecutionResult result = ex.execute(node, context, "run1", nodeMap);
                assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
                verify(coalescer, times(1)).openCoalescing("run1");
                // openCoalescing returned null → coalescingOpened=false → no close.
                verify(coalescer, never()).closeCoalescing("run1");
            } finally {
                ex.shutdown();
            }
        }
    }

    /**
     * Test node implementation that properly delegates type-based checks.
     */
    private static class TestNode extends BaseNode {
        private NodeExecutionResult executeResult;
        private java.util.function.Function<ExecutionContext, NodeExecutionResult> dynamicResult;
        private final java.util.concurrent.atomic.AtomicInteger executeCount = new java.util.concurrent.atomic.AtomicInteger(0);

        TestNode(String nodeId, NodeType type) {
            super(nodeId, type);
        }

        void setExecuteResult(NodeExecutionResult result) {
            this.executeResult = result;
        }

        void setDynamicResult(java.util.function.Function<ExecutionContext, NodeExecutionResult> fn) {
            this.dynamicResult = fn;
        }

        int getExecuteCount() {
            return executeCount.get();
        }

        @Override
        public boolean skipsSplitHandling() {
            return type == NodeType.SPLIT || type == NodeType.MERGE
                || type == NodeType.DECISION || type == NodeType.FORK
                || type == NodeType.LOOP || type == NodeType.TRIGGER
                || type == NodeType.SWITCH || type == NodeType.END;
        }

        @Override
        public boolean isSplitNode() {
            return type == NodeType.SPLIT;
        }

        @Override
        public boolean isMergeNode() {
            return type == NodeType.MERGE;
        }

        @Override
        public boolean isLoopNode() {
            return type == NodeType.LOOP;
        }

        @Override
        public boolean isForkNode() {
            return type == NodeType.FORK;
        }

        @Override
        public NodeExecutionResult execute(ExecutionContext context) {
            executeCount.incrementAndGet();
            if (dynamicResult != null) {
                return dynamicResult.apply(context);
            }
            return executeResult != null ? executeResult : NodeExecutionResult.success(nodeId, Map.of());
        }
    }
}

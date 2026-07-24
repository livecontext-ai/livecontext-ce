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
    @DisplayName("per-item durable backfill (branch-rejoin merge / cross-item consumer collapse fix)")
    class PerItemDurableBackfill {

        private ExecutionContext freshContext() {
            return ExecutionContext.create(
                "run-durable", "wfr-durable", "tenant-durable", "item-0", 0,
                new HashMap<>(), null);
        }

        /**
         * A base context whose stepOutputs already carry classify = item 0's value - the single
         * value a branch-rejoin merge falls back to (and every item collapses to) when the per-item
         * override is missing. Mirrors the threaded/DB-loaded base context in production.
         */
        private ExecutionContext contextWithItem0Classify(String category) {
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("output", Map.of("selected_category", category));
            wrapper.put("httpstatus", 200);
            return freshContext().withStepOutput("agent:classify", wrapper);
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
        @DisplayName("Regression: classify ABSENT from resultsByNode collapses to item 0 WITHOUT durable, resolves per item WITH durable")
        void durableBackfillResolvesAbsentPredecessorPerItem() {
            // resultsByNode is EMPTY for classify: the exact cross-pod async-agent / signal resume
            // (restoreContext rebuilds items only) or read-before-async-seal state. The base context
            // carries item 0's classify value ("billing") - the collapse baseline.
            SplitContext splitContext = SplitContext.create(
                "core:triage:0", List.of("m0", "m1", "m2"));

            // Durable store has the real per-item classify outputs (what SplitAggregateHandler already reads).
            Map<String, Map<Integer, Object>> durable = Map.of(
                "agent:classify", Map.of(
                    0, Map.of("selected_category", "billing"),
                    1, Map.of("selected_category", "bug"),
                    2, Map.of("selected_category", "refund_clear")));

            // WITHOUT durable (pre-fix behavior): the per-item classify override is ABSENT for item 2,
            // so injectPredecessorPerItemOutputs leaves the base context's single "item 0" value in
            // place and EVERY item collapses to it -> the exact reported symptom.
            ExecutionContext collapsed = executor.enrichContextWithItem(
                contextWithItem0Classify("billing"), "m2", 2, List.of("m0", "m1", "m2"), splitContext);
            assertThat(aliasOutput(collapsed, "agent:classify", "selected_category"))
                .as("reproduction: no durable source -> item 2 collapses to item 0's 'billing'")
                .isEqualTo("billing");

            // WITH durable (fix): item 2 resolves to its OWN classification.
            ExecutionContext fixed = executor.enrichContextWithItem(
                contextWithItem0Classify("billing"), "m2", 2, List.of("m0", "m1", "m2"), splitContext, durable);
            assertThat(aliasOutput(fixed, "agent:classify", "selected_category"))
                .as("fix: durable backfill resolves classify to THIS item's value")
                .isEqualTo("refund_clear");
            assertThat(aliasOutput(fixed, "classify", "selected_category"))
                .as("bare alias must also carry the per-item value")
                .isEqualTo("refund_clear");
        }

        @Test
        @DisplayName("in-memory slot WINS over durable (warm path unchanged, no stale overwrite)")
        void memoryWinsOverDurable() {
            SplitContext splitContext = SplitContext.create("core:triage:0", List.of("m0", "m1", "m2"))
                .withResults("agent:classify", java.util.Arrays.asList(
                    Map.of("selected_category", "billing"),
                    Map.of("selected_category", "bug"),
                    Map.of("selected_category", "refund_clear")));
            // Durable carries a STALE value that must never override a live in-memory slot.
            Map<String, Map<Integer, Object>> durable = Map.of(
                "agent:classify", Map.of(2, Map.of("selected_category", "STALE")));

            ExecutionContext ctx = executor.enrichContextWithItem(
                freshContext(), "m2", 2, List.of("m0", "m1", "m2"), splitContext, durable);
            assertThat(aliasOutput(ctx, "agent:classify", "selected_category"))
                .as("in-memory per-item value wins; durable is a fallback only")
                .isEqualTo("refund_clear");
        }

        @Test
        @DisplayName("NULL in-memory slot is backfilled from durable (async-partial: node recorded but this item's slot not yet landed)")
        void nullSlotBackfilledFromDurable() {
            SplitContext splitContext = SplitContext.create("core:triage:0", List.of("m0", "m1", "m2"))
                .withResults("agent:classify", java.util.Arrays.asList(
                    Map.of("selected_category", "billing"),
                    Map.of("selected_category", "bug"),
                    null)); // item 2's slot not yet populated in memory

            Map<String, Map<Integer, Object>> durable = Map.of(
                "agent:classify", Map.of(2, Map.of("selected_category", "refund_clear")));

            ExecutionContext ctx = executor.enrichContextWithItem(
                freshContext(), "m2", 2, List.of("m0", "m1", "m2"), splitContext, durable);
            assertThat(aliasOutput(ctx, "agent:classify", "selected_category"))
                .as("null in-memory slot must be backfilled from the durable store")
                .isEqualTo("refund_clear");
        }

        @Test
        @DisplayName("durable backfill NEVER clobbers the split node's own current_item")
        void durableSkipsSplitNodeKey() {
            Object item2 = Map.of("id", "m2");
            SplitContext splitContext = SplitContext.create(
                "core:triage:0", List.of(Map.of("id", "m0"), Map.of("id", "m1"), item2));
            // A durable row keyed on the split node itself must be ignored so current_item survives.
            Map<String, Map<Integer, Object>> durable = Map.of(
                "core:triage", Map.of(2, Map.of("current_item", Map.of("id", "WRONG"))));

            ExecutionContext ctx = executor.enrichContextWithItem(
                freshContext(), item2, 2,
                List.of(Map.of("id", "m0"), Map.of("id", "m1"), item2), splitContext, durable);
            assertThat(aliasOutput(ctx, "core:triage", "current_item"))
                .as("split node's current_item must not be overwritten by a durable backfill entry")
                .isEqualTo(item2);
        }

        @Test
        @DisplayName("null durable map is a no-op (behavior identical to pre-fix)")
        void nullDurableIsNoOp() {
            SplitContext splitContext = SplitContext.create("core:triage:0", List.of("m0", "m1", "m2"));
            ExecutionContext ctx = executor.enrichContextWithItem(
                contextWithItem0Classify("billing"), "m2", 2, List.of("m0", "m1", "m2"), splitContext, null);
            assertThat(aliasOutput(ctx, "agent:classify", "selected_category")).isEqualTo("billing");
        }

        @Test
        @DisplayName("gate: cross-item consumers (merge/loop/fork) are eligible for durable; a plain mcp/agent successor is not")
        void crossItemConsumerGate() {
            assertThat(executor.isCrossItemPerItemConsumer(new TestNode("core:m", NodeType.MERGE))).isTrue();
            assertThat(executor.isCrossItemPerItemConsumer(new TestNode("core:l", NodeType.LOOP))).isTrue();
            assertThat(executor.isCrossItemPerItemConsumer(new TestNode("core:f", NodeType.FORK))).isTrue();
            assertThat(executor.isCrossItemPerItemConsumer(new TestNode("mcp:x", NodeType.MCP))).isFalse();
            assertThat(executor.isCrossItemPerItemConsumer(new TestNode("agent:a", NodeType.AGENT))).isFalse();
        }

        @Test
        @DisplayName("loadDurableEpochOutputs: null service -> null; wired -> per-epoch map; throwing service -> null (degrades, never propagates)")
        void loadDurableEpochOutputsWiring() {
            // No service wired on the shared executor -> null (warm behavior).
            assertThat(executor.loadDurableEpochOutputs(freshContext())).isNull();

            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, null, null, Executors.newFixedThreadPool(1));
            local.setStepOutputService(svc);
            Map<String, Map<Integer, Object>> map = Map.of("agent:classify", Map.of(0, Map.of("k", "v")));
            when(svc.loadPerItemOutputsByStepKey("run-durable", 0, "tenant-durable")).thenReturn(map);
            assertThat(local.loadDurableEpochOutputs(freshContext()))
                .as("wired service returns the per-epoch map straight through")
                .isSameAs(map);
            local.shutdown();

            com.apimarketplace.orchestrator.services.StepOutputService throwing =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local2 = new SplitAwareNodeExecutor(
                contextManager, null, null, null, null, null, Executors.newFixedThreadPool(1));
            local2.setStepOutputService(throwing);
            when(throwing.loadPerItemOutputsByStepKey(anyString(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("db down"));
            assertThat(local2.loadDurableEpochOutputs(freshContext()))
                .as("a store failure degrades to no backfill, never propagates")
                .isNull();
            local2.shutdown();
        }

        @Test
        @DisplayName("wiring: a plain direct-successor fan-out never issues a durable epoch query (warm path stays query-free)")
        void plainSuccessorNeverQueriesDurable() {
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, null, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setPredecessors(List.of("core:split1"));
            node.setExecuteResult(NodeExecutionResult.success("mcp:step1", Map.of("ok", true)));
            nodeMap.put("mcp:step1", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));
            SplitContext splitContext = SplitContext.create("core:split1:0", List.of("a", "b", "c"));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(anyInt())).thenReturn(context);

            local.execute(node, context, "run1", nodeMap);

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.never())
                .loadPerItemOutputsByStepKey(anyString(), anyInt(), anyString());
            local.shutdown();
        }

        @Test
        @DisplayName("read path WARM: a cross-item consumer whose resultsByNode is fully dense issues ZERO durable epoch queries")
        void readPathWarmMemorySkipsDurableQuery() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, repo, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            // A branch-rejoin merge (cross-item consumer -> passes isCrossItemPerItemConsumer).
            TestNode merge = new TestNode("core:merge", NodeType.MERGE);
            merge.setPredecessors(List.of("table:a", "table:b"));
            merge.setDynamicResult(ctx -> NodeExecutionResult.success(
                "core:merge", Map.of("i", ctx.itemIndex())));
            nodeMap.put("core:merge", merge);
            nodeMap.put("table:a", new TestNode("table:a", NodeType.MCP));
            nodeMap.put("table:b", new TestNode("table:b", NodeType.MCP));

            // DENSE in-memory results for every routed item -> inMemorySlotsComplete == true -> warm skip.
            SplitContext splitContext = SplitContext.create("core:split:0", List.of("w", "x", "y", "z"))
                .withResults("table:a", java.util.Arrays.asList(
                    Map.of("v", 0), Map.of("v", 1), Map.of("v", 2), Map.of("v", 3)))
                .withResults("table:b", java.util.Arrays.asList(
                    Map.of("v", 0), Map.of("v", 1), Map.of("v", 2), Map.of("v", 3)));
            when(contextManager.findActiveContext(eq("run1"), eq("core:merge"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(repo.findCompletedItemIndicesByEpoch("run1", "table:a", 0)).thenReturn(List.of(0, 1));
            when(repo.findCompletedItemIndicesByEpoch("run1", "table:b", 0)).thenReturn(List.of(2, 3));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(anyInt())).thenReturn(context);

            local.execute(merge, context, "run1", nodeMap);

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.never())
                .loadPerItemOutputsByStepKey(anyString(), anyInt(), anyString());
            local.shutdown();
        }

        @Test
        @DisplayName("read path COLD: a cross-item consumer whose resultsByNode is empty (cross-pod/restart) issues exactly one durable epoch query")
        void readPathColdMemoryIssuesDurableQuery() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, repo, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            TestNode merge = new TestNode("core:merge", NodeType.MERGE);
            merge.setPredecessors(List.of("table:a", "table:b"));
            merge.setDynamicResult(ctx -> NodeExecutionResult.success(
                "core:merge", Map.of("i", ctx.itemIndex())));
            nodeMap.put("core:merge", merge);
            nodeMap.put("table:a", new TestNode("table:a", NodeType.MCP));
            nodeMap.put("table:b", new TestNode("table:b", NodeType.MCP));

            // EMPTY resultsByNode -> the cross-pod/restart state restoreContext leaves behind
            // (items rebuilt, per-node results not) -> inMemorySlotsComplete == false -> durable load fires.
            SplitContext splitContext = SplitContext.create("core:split:0", List.of("w", "x", "y", "z"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:merge"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(repo.findCompletedItemIndicesByEpoch("run1", "table:a", 0)).thenReturn(List.of(0, 1));
            when(repo.findCompletedItemIndicesByEpoch("run1", "table:b", 0)).thenReturn(List.of(2, 3));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(anyInt())).thenReturn(context);
            when(context.runId()).thenReturn("run1");
            when(context.tenantId()).thenReturn("tenant1");
            when(svc.loadPerItemOutputsByStepKey("run1", 0, "tenant1")).thenReturn(java.util.Map.of());

            local.execute(merge, context, "run1", nodeMap);

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.times(1))
                .loadPerItemOutputsByStepKey("run1", 0, "tenant1");
            local.shutdown();
        }

        @Test
        @DisplayName("inMemorySlotsComplete: empty cache / null slot / short list -> not warm (load); fully dense with all direct predecessors present -> warm (skip)")
        void inMemorySlotsCompleteBranches() {
            java.util.Set<Integer> routed = java.util.Set.of(0, 1, 2);
            // A reader whose only in-split predecessor entry we control per case.
            TestNode reader = new TestNode("mcp:send", NodeType.MCP);
            reader.setPredecessors(List.of("agent:classify"));

            // Empty resultsByNode (post-restore) -> not warm, must allow the durable backfill.
            assertThat(executor.inMemorySlotsComplete(
                SplitContext.create("core:s:0", List.of("a", "b", "c")), routed, reader, nodeMap)).isFalse();

            // Every present node dense for every routed item AND the reader's direct
            // predecessor present -> warm, skip the durable read.
            SplitContext dense = SplitContext.create("core:s:0", List.of("a", "b", "c"))
                .withResults("agent:classify", java.util.Arrays.asList(
                    Map.of("c", "0"), Map.of("c", "1"), Map.of("c", "2")));
            assertThat(executor.inMemorySlotsComplete(dense, routed, reader, nodeMap)).isTrue();

            // A null slot for a routed item -> not warm (async-partial gap).
            SplitContext nullSlot = SplitContext.create("core:s:0", List.of("a", "b", "c"))
                .withResults("agent:classify", java.util.Arrays.asList(
                    Map.of("c", "0"), null, Map.of("c", "2")));
            assertThat(executor.inMemorySlotsComplete(nullSlot, routed, reader, nodeMap)).isFalse();

            // A short list (routed item 2 absent) -> not warm.
            SplitContext shortList = SplitContext.create("core:s:0", List.of("a", "b", "c"))
                .withResults("agent:classify", java.util.Arrays.asList(
                    Map.of("c", "0"), Map.of("c", "1")));
            assertThat(executor.inMemorySlotsComplete(shortList, routed, reader, nodeMap)).isFalse();

            // Null split context -> not warm (defensive).
            assertThat(executor.inMemorySlotsComplete(null, routed, reader, nodeMap)).isFalse();
        }

        @Test
        @DisplayName("Regression (cross-pod dense-but-incomplete map): a direct non-split predecessor entirely ABSENT from resultsByNode -> not warm, even when every present node is dense")
        void inMemorySlotsCompleteAbsentDirectPredecessorNotWarm() {
            java.util.Set<Integer> routed = java.util.Set.of(0, 1, 2);
            SplitContext denseForOthers = SplitContext.create("core:triage:0", List.of("a", "b", "c"))
                .withResults("agent:classify", java.util.Arrays.asList(
                    Map.of("c", "0"), Map.of("c", "1"), Map.of("c", "2")));

            // The reader's direct predecessor (core:prep_draft) ran entirely on ANOTHER pod:
            // it is ABSENT from the map (not present-with-null). The old 2-arg check read this
            // dense map as warm and starved the durable backfill - the exact multi-pod blind spot.
            TestNode reader = new TestNode("core:approve", NodeType.APPROVAL);
            reader.setPredecessors(List.of("core:prep_draft"));
            assertThat(executor.inMemorySlotsComplete(denseForOthers, routed, reader, nodeMap))
                .as("absent direct predecessor -> memory provably cannot serve it -> not warm")
                .isFalse();

            // Ported predecessor ids normalize before the lookup (core:gate:approved -> core:gate).
            TestNode portedReader = new TestNode("core:observe", NodeType.TRANSFORM);
            portedReader.setPredecessors(List.of("core:gate:approved"));
            assertThat(executor.inMemorySlotsComplete(denseForOthers, routed, portedReader, nodeMap))
                .as("ported predecessor (core:gate:approved) absent as core:gate -> not warm")
                .isFalse();

            // FIRST node after the split: its only predecessor IS the split node (exempt) ->
            // still warm, the hot path stays query-free.
            TestNode direct = new TestNode("core:tag", NodeType.TRANSFORM);
            direct.setPredecessors(List.of("core:triage"));
            assertThat(executor.inMemorySlotsComplete(denseForOthers, routed, direct, nodeMap))
                .as("split-node predecessor is exempt from the absent-key check")
                .isTrue();

            // Null node skips the hardening (pre-existing 2-arg semantics, defensive).
            assertThat(executor.inMemorySlotsComplete(denseForOthers, routed, null, nodeMap)).isTrue();
        }

        /**
         * Prod regression, 2026-07-15 (2 orchestrator replicas, ~3 failing runs in 5).
         *
         * <p>Chain: {@code split -> snap -> echo_before -> think(async agent) -> echo_after}.
         * {@code echo_after} reads {@code {{core:snap.output.v}}} and collapsed to item 0's value
         * (A,A,A instead of A,B,C) while {@code echo_before}, reading the same node one hop
         * earlier, stayed correct. {@code echo_after}'s only DIRECT predecessor is {@code think},
         * which IS present in the map, so the direct-only absent-key check read the map as warm
         * and vetoed the durable backfill for {@code snap} - an INDIRECT ancestor, absent because
         * it ran on the other pod. The template then fell back to the item-0 base context.
         */
        @Test
        @DisplayName("Regression (indirect ancestor across an async agent): reader whose DIRECT predecessor is present but whose 3-hop ancestor is ABSENT -> not warm")
        void inMemorySlotsCompleteAbsentIndirectAncestorNotWarm() {
            java.util.Set<Integer> routed = java.util.Set.of(0, 1, 2);

            // split -> core:snap -> core:echo_before -> agent:think -> core:echo_after
            TestNode snap = new TestNode("core:snap", NodeType.TRANSFORM);
            snap.setPredecessors(List.of("core:each"));
            TestNode echoBefore = new TestNode("core:echo_before", NodeType.TRANSFORM);
            echoBefore.setPredecessors(List.of("core:snap"));
            TestNode think = new TestNode("agent:think", NodeType.AGENT);
            think.setPredecessors(List.of("core:echo_before"));
            TestNode echoAfter = new TestNode("core:echo_after", NodeType.TRANSFORM);
            echoAfter.setPredecessors(List.of("agent:think"));
            nodeMap.put("core:snap", snap);
            nodeMap.put("core:echo_before", echoBefore);
            nodeMap.put("agent:think", think);
            nodeMap.put("core:echo_after", echoAfter);

            // The pod that resumes after the async agent holds a map that is DENSE for the nodes
            // it ran itself (echo_before, think) but has NO entry for core:snap - it ran on the
            // other pod. echo_after's template reads core:snap, three hops back.
            SplitContext denseButSnapAbsent = SplitContext.create("core:each:0", List.of("A", "B", "C"))
                .withResults("core:echo_before", java.util.Arrays.asList(
                    Map.of("v", "A"), Map.of("v", "B"), Map.of("v", "C")))
                .withResults("agent:think", java.util.Arrays.asList(
                    Map.of("response", "r0"), Map.of("response", "r1"), Map.of("response", "r2")));

            assertThat(executor.inMemorySlotsComplete(denseButSnapAbsent, routed, echoAfter, nodeMap))
                .as("core:snap is an INDIRECT ancestor absent from the map: memory cannot serve "
                  + "{{core:snap.output.v}}, so the map is NOT warm and the durable backfill must fire")
                .isFalse();

            // Control: echo_before, one hop earlier, reads snap as its DIRECT predecessor. It was
            // already correct pre-fix (A,B,C in prod) and must stay not-warm for the same reason.
            assertThat(executor.inMemorySlotsComplete(denseButSnapAbsent, routed, echoBefore, nodeMap))
                .as("direct predecessor core:snap absent -> not warm (pre-existing hardening)")
                .isFalse();

            // Control: once core:snap IS present and dense, every ancestor of echo_after is
            // servable from memory -> warm again, no durable query.
            SplitContext allPresent = denseButSnapAbsent
                .withResults("core:snap", java.util.Arrays.asList(
                    Map.of("v", "A"), Map.of("v", "B"), Map.of("v", "C")));
            assertThat(executor.inMemorySlotsComplete(allPresent, routed, echoAfter, nodeMap))
                .as("every in-split ancestor present and dense -> warm, stay query-free")
                .isTrue();
        }

        @Test
        @DisplayName("Ancestor walk is walled at the split: a direct split successor stays warm (query-free) even though pre-split ancestors are absent from the map")
        void inMemorySlotsCompleteAncestorWalkStopsAtSplitNode() {
            java.util.Set<Integer> routed = java.util.Set.of(0, 1, 2);

            // trigger:webhook -> core:fetch -> core:each(split) -> core:tag
            // Only core:tag is inside the split. The walk must stop AT core:each and never reach
            // core:fetch / trigger:webhook - they are not per-item, are never in resultsByNode,
            // and would otherwise force a durable query on every split's FIRST node.
            TestNode trigger = new TestNode("trigger:webhook", NodeType.TRIGGER);
            TestNode fetch = new TestNode("core:fetch", NodeType.TRANSFORM);
            fetch.setPredecessors(List.of("trigger:webhook"));
            TestNode each = new TestNode("core:each", NodeType.SPLIT);
            each.setPredecessors(List.of("core:fetch"));
            TestNode tag = new TestNode("core:tag", NodeType.TRANSFORM);
            tag.setPredecessors(List.of("core:each"));
            nodeMap.put("trigger:webhook", trigger);
            nodeMap.put("core:fetch", fetch);
            nodeMap.put("core:each", each);
            nodeMap.put("core:tag", tag);

            SplitContext dense = SplitContext.create("core:each:0", List.of("a", "b", "c"))
                .withResults("core:tag", java.util.Arrays.asList(
                    Map.of("t", "0"), Map.of("t", "1"), Map.of("t", "2")));

            assertThat(executor.inMemorySlotsComplete(dense, routed, tag, nodeMap))
                .as("the walk is walled at core:each, so absent pre-split nodes (core:fetch, "
                  + "trigger:webhook) never make the FIRST node after a split issue a durable query")
                .isTrue();
        }

        @Test
        @DisplayName("Ancestor walk on a cycle visits each node once: both members present and dense -> warm (a re-visiting walk would hit the cap and fail open)")
        void inMemorySlotsCompleteAncestorWalkHandlesCycleWithoutTruncating() {
            java.util.Set<Integer> routed = java.util.Set.of(0);

            // A cycle inside the split body: core:a -> core:b -> core:a, read by core:reader.
            TestNode a = new TestNode("core:a", NodeType.TRANSFORM);
            a.setPredecessors(List.of("core:b"));
            TestNode b = new TestNode("core:b", NodeType.TRANSFORM);
            b.setPredecessors(List.of("core:a"));
            TestNode reader = new TestNode("core:reader", NodeType.TRANSFORM);
            reader.setPredecessors(List.of("core:a"));
            nodeMap.put("core:a", a);
            nodeMap.put("core:b", b);
            nodeMap.put("core:reader", reader);

            SplitContext dense = SplitContext.create("core:each:0", List.of("x"))
                .withResults("core:a", java.util.Arrays.asList(Map.of("v", 0)))
                .withResults("core:b", java.util.Arrays.asList(Map.of("v", 0)));

            // Asserting WARM is what makes this falsifiable: the visited-set is what keeps the
            // cycle to 2 processed nodes. Drop it and the walk re-enqueues forever, spins to the
            // processing bound (the 50 floor on this tiny graph), reports truncated, and the
            // fail-open branch returns NOT warm - flipping this assertion.
            assertThat(executor.inMemorySlotsComplete(dense, routed, reader, nodeMap))
                .as("the visited-set bounds the cycle to {core:a, core:b}, both present and dense, "
                  + "so the walk completes untruncated and the map reads warm")
                .isTrue();
        }

        /**
         * Regression for the root fix (2026-07-16): the walk used to cap at a CONSTANT 50
         * processed nodes, so a legitimate split body longer than 50 nodes truncated the walk and
         * the fail-open forced a durable query even though memory was provably complete. The
         * bound is now the actual graph size ({@code max(50, nodeMap.size() + 1)}); with the
         * visited-dedup the walk processes each distinct node at most once, so a real plan (all
         * predecessors resolvable in the nodeMap) can never exhaust it. This dense 60-node chain
         * must therefore read WARM (query-free). Fails on the pre-fix constant-50 code.
         */
        @Test
        @DisplayName("Regression: a dense 60-node chain with a FULL nodeMap completes untruncated and reads WARM (graph-size bound replaced the constant 50 cap)")
        void inMemorySlotsCompleteLongChainWithFullNodeMapReadsWarm() {
            java.util.Set<Integer> routed = java.util.Set.of(0);

            // A 60-node chain inside the split: core:n0 (after the split) -> ... -> core:n59,
            // read by core:reader. Every node is in the nodeMap and dense in memory.
            int chainLength = 60;
            SplitContext dense = SplitContext.create("core:each:0", List.of("x"));
            for (int i = 0; i < chainLength; i++) {
                TestNode n = new TestNode("core:n" + i, NodeType.TRANSFORM);
                n.setPredecessors(List.of(i == 0 ? "core:each" : "core:n" + (i - 1)));
                nodeMap.put("core:n" + i, n);
                dense = dense.withResults("core:n" + i, java.util.Arrays.asList(Map.of("v", i)));
            }
            TestNode reader = new TestNode("core:reader", NodeType.TRANSFORM);
            reader.setPredecessors(List.of("core:n" + (chainLength - 1)));
            nodeMap.put("core:reader", reader);

            assertThat(executor.inMemorySlotsComplete(dense, routed, reader, nodeMap))
                .as("visited-dedup + a bound >= the graph size make truncation unreachable on a "
                  + "real plan: all 60 ancestors are seen, all are present and dense, so memory "
                  + "is provably complete and the map reads warm (query-free)")
                .isTrue();
        }

        /**
         * Fail-open guard, kept as a defensive net. Truncation is unreachable on a real plan
         * (visited-dedup + graph-size bound), so the only way to exercise the flag is a
         * pathological input: a reader whose predecessor list references more distinct ids than
         * the nodeMap resolves, with the 50 floor as the effective bound (unresolvable ids ARE
         * enqueued and counted; they just cannot expand). Every referenced ancestor is present
         * and dense in memory, so ONLY the truncated flag can flip the result - a truncated walk
         * yields a PARTIAL set that cannot prove memory is complete and must never report warm.
         */
        @Test
        @DisplayName("Fail-open (defensive net): 60 unresolvable predecessor ids on a tiny nodeMap truncate at the 50 floor -> NOT warm, even with every referenced ancestor present and dense")
        void inMemorySlotsCompleteTruncatedWalkFailsOpen() {
            java.util.Set<Integer> routed = java.util.Set.of(0);

            // Pathological fan-in: 60 distinct predecessor ids, NONE resolvable in the nodeMap.
            int fanIn = 60;
            SplitContext dense = SplitContext.create("core:each:0", List.of("x"));
            java.util.List<String> preds = new java.util.ArrayList<>();
            for (int i = 0; i < fanIn; i++) {
                preds.add("core:g" + i);
                // EVERY referenced ancestor is present and dense: without the truncation signal
                // the check would find nothing absent and wrongly report warm.
                dense = dense.withResults("core:g" + i, java.util.Arrays.asList(Map.of("v", i)));
            }
            TestNode reader = new TestNode("core:reader", NodeType.TRANSFORM);
            reader.setPredecessors(preds);
            // The nodeMap resolves ONLY the reader: bound = max(50, 1 + 1) = 50 (the floor), but
            // 60 distinct ids are enqueued -> the walk stops with work still queued -> truncated.
            Map<String, ExecutionNode> tinyMap = Map.of("core:reader", reader);

            assertThat(executor.inMemorySlotsComplete(dense, routed, reader, tinyMap))
                .as("a truncated walk yields a PARTIAL ancestor set, which cannot prove memory "
                  + "is complete: fail open and let the durable backfill run")
                .isFalse();

            // Control: the same pathological shape UNDER the floor (40 ids) completes
            // untruncated and reads warm - it is the truncation that flips the result, not the
            // unresolvable ids themselves.
            int smallFanIn = 40;
            SplitContext smallDense = SplitContext.create("core:each:0", List.of("x"));
            java.util.List<String> smallPreds = new java.util.ArrayList<>();
            for (int i = 0; i < smallFanIn; i++) {
                smallPreds.add("core:s" + i);
                smallDense = smallDense.withResults("core:s" + i, java.util.Arrays.asList(Map.of("v", i)));
            }
            TestNode smallReader = new TestNode("core:reader", NodeType.TRANSFORM);
            smallReader.setPredecessors(smallPreds);

            assertThat(executor.inMemorySlotsComplete(smallDense, routed, smallReader,
                    Map.of("core:reader", smallReader)))
                .as("an untruncated walk over fully present, dense ancestors reads warm")
                .isTrue();
        }

        /**
         * The AUTO path (UnifiedExecutionEngine.executeNodeCore) passes nodeMap = {nodeId: node}.
         * This is a distinct production shape from null / Map.of(): the reader IS in the map, so
         * the BFS seeds from its predecessor ids and still yields the DIRECT predecessors, but it
         * cannot expand them (they are absent from the singleton map). The effective scope is
         * therefore the direct predecessors, and the veto must still fire on an absent one.
         *
         * <p>AUTO cannot reach the cross-pod sparse map that needs the wider scope (one call stack
         * in one JVM; it yields out at every signal/async boundary and the resume re-enters through
         * the full-map path), so degrading there is safe, but it must degrade to the OLD scope,
         * never to "silently warm".
         *
         * <p>Two redundant mechanisms cover this shape (the direct-predecessor seed AND the walk's
         * own seeding), so this is a shape-characterization test: it flips only when BOTH are
         * removed. Verified by mutation: disabling seed + walk together turns this assertion true.
         */
        @Test
        @DisplayName("AUTO singleton nodeMap {nodeId: node}: walk cannot expand past the direct predecessors, and the absent-direct-predecessor veto still fires")
        void inMemorySlotsCompleteWithAutoSingletonNodeMapChecksDirectPredecessors() {
            java.util.Set<Integer> routed = java.util.Set.of(0, 1, 2);
            SplitContext denseForOthers = SplitContext.create("core:triage:0", List.of("a", "b", "c"))
                .withResults("agent:classify", java.util.Arrays.asList(
                    Map.of("c", "0"), Map.of("c", "1"), Map.of("c", "2")));

            TestNode reader = new TestNode("core:approve", NodeType.APPROVAL);
            reader.setPredecessors(List.of("core:prep_draft"));

            assertThat(executor.inMemorySlotsComplete(
                    denseForOthers, routed, reader, Map.of(reader.getNodeId(), reader)))
                .as("AUTO's singleton map cannot expand past the direct predecessors; the absent "
                  + "direct predecessor core:prep_draft must still veto the warm skip")
                .isFalse();

            // And the split's FIRST node stays query-free on the AUTO shape too.
            TestNode direct = new TestNode("core:tag", NodeType.TRANSFORM);
            direct.setPredecessors(List.of("core:triage"));
            assertThat(executor.inMemorySlotsComplete(
                    denseForOthers, routed, direct, Map.of(direct.getNodeId(), direct)))
                .as("split-node predecessor is exempt: no durable query for the first node")
                .isTrue();
        }

        @Test
        @DisplayName("Absent-key hardening degrades to direct predecessors (not silently warm) when nodeMap is null or lacks the reader")
        void inMemorySlotsCompleteWithoutUsableNodeMapChecksDirectPredecessors() {
            java.util.Set<Integer> routed = java.util.Set.of(0, 1, 2);
            SplitContext denseForOthers = SplitContext.create("core:triage:0", List.of("a", "b", "c"))
                .withResults("agent:classify", java.util.Arrays.asList(
                    Map.of("c", "0"), Map.of("c", "1"), Map.of("c", "2")));

            TestNode reader = new TestNode("core:approve", NodeType.APPROVAL);
            reader.setPredecessors(List.of("core:prep_draft"));

            // nodeMap null (defensive) -> the transitive walk is skipped, the direct-predecessor
            // check still catches the absent core:prep_draft.
            assertThat(executor.inMemorySlotsComplete(denseForOthers, routed, reader, null))
                .as("null nodeMap must not weaken the pre-existing direct-predecessor hardening")
                .isFalse();

            // nodeMap present but without the reader (auto mode can carry a partial map) -> the
            // walk returns empty, the direct-predecessor check still fires.
            assertThat(executor.inMemorySlotsComplete(denseForOthers, routed, reader, Map.of()))
                .as("nodeMap lacking the reader must not weaken the direct-predecessor hardening")
                .isFalse();
        }

        @Test
        @DisplayName("isSignalYieldingNode: approval/interface/browser-agent -> true; wait/mcp/agent/transform -> false")
        void isSignalYieldingNodePredicate() {
            assertThat(executor.isSignalYieldingNode(new TestNode("core:gate", NodeType.APPROVAL))).isTrue();
            assertThat(executor.isSignalYieldingNode(new TestNode("interface:card", NodeType.INTERFACE))).isTrue();
            assertThat(executor.isSignalYieldingNode(new TestNode("core:browse", NodeType.BROWSER_AGENT))).isTrue();
            // The polymorphic predicate is honored too (real UserApprovalNode overrides isApprovalNode).
            assertThat(executor.isSignalYieldingNode(
                com.apimarketplace.orchestrator.execution.v2.nodes.UserApprovalNode.builder()
                    .nodeId("core:real_gate").approverRoles(List.of("manager")).requiredApprovals(1)
                    .timeoutMs(1000L).build())).isTrue();
            // WAIT yields a signal but resolves no user templates at yield -> deliberately excluded.
            assertThat(executor.isSignalYieldingNode(new TestNode("core:wait", NodeType.WAIT))).isFalse();
            assertThat(executor.isSignalYieldingNode(new TestNode("mcp:x", NodeType.MCP))).isFalse();
            assertThat(executor.isSignalYieldingNode(new TestNode("agent:a", NodeType.AGENT))).isFalse();
            assertThat(executor.isSignalYieldingNode(new TestNode("core:t", NodeType.TRANSFORM))).isFalse();
            assertThat(executor.isSignalYieldingNode(null)).isFalse();
        }

        @Test
        @DisplayName("readsNonSplitPredecessor: direct split successor -> false (no backfill); any other predecessor (bare or ported) -> true")
        void readsNonSplitPredecessorPredicate() {
            // Direct split successor: only predecessor is the split node itself -> false.
            TestNode direct = new TestNode("core:tag", NodeType.TRANSFORM);
            direct.setPredecessors(List.of("core:per_tag"));
            assertThat(executor.readsNonSplitPredecessor(direct, "core:per_tag")).isFalse();

            // Split id carries an item suffix (core:per_tag:0) - getNodeKey normalizes both sides.
            assertThat(executor.readsNonSplitPredecessor(direct, "core:per_tag:0")).isFalse();

            // Non-adjacent predecessor across an approval (ported id core:gate:approved) -> true.
            TestNode observe = new TestNode("core:observe", NodeType.TRANSFORM);
            observe.setPredecessors(List.of("core:gate:approved"));
            assertThat(executor.readsNonSplitPredecessor(observe, "core:per_tag")).isTrue();

            // Plain non-split predecessor (no port) -> true.
            TestNode chained = new TestNode("mcp:send", NodeType.MCP);
            chained.setPredecessors(List.of("core:tag"));
            assertThat(executor.readsNonSplitPredecessor(chained, "core:per_tag")).isTrue();

            // Mixed: one predecessor is the split, another is not -> true (reads a prior per-item node).
            TestNode mixed = new TestNode("mcp:m", NodeType.MCP);
            mixed.setPredecessors(java.util.Arrays.asList("core:per_tag", "core:tag"));
            assertThat(executor.readsNonSplitPredecessor(mixed, "core:per_tag")).isTrue();

            // No predecessors / null args -> false (defensive).
            assertThat(executor.readsNonSplitPredecessor(new TestNode("mcp:x", NodeType.MCP), "core:per_tag")).isFalse();
            assertThat(executor.readsNonSplitPredecessor(null, "core:per_tag")).isFalse();
            assertThat(executor.readsNonSplitPredecessor(direct, null)).isFalse();
        }

        @Test
        @DisplayName("read path COLD (plain successor, non-adjacent predecessor): empty resultsByNode issues the durable query that closes the restart-resume collapse")
        void plainNonAdjacentSuccessorColdMemoryIssuesDurableQuery() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, repo, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            // A PLAIN transform (NOT a cross-item consumer) that reads a NON-adjacent predecessor
            // (core:tag, across an approval) - the exact shape proven to collapse on a restart resume.
            TestNode observe = new TestNode("core:observe", NodeType.TRANSFORM);
            observe.setPredecessors(List.of("core:gate:approved"));
            observe.setDynamicResult(ctx -> NodeExecutionResult.success(
                "core:observe", Map.of("i", ctx.itemIndex())));
            nodeMap.put("core:observe", observe);
            nodeMap.put("core:gate", new TestNode("core:gate", NodeType.APPROVAL));

            // EMPTY resultsByNode = the cross-pod/restart state (singleton wiped) -> not warm -> load fires.
            SplitContext splitContext = SplitContext.create("core:per_tag", List.of("alpha", "beta", "gamma"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:observe"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            // observe's predecessor carries the approval "approved" port, so routing resolves the
            // approved item set from the branch table - all 3 items were approved (resolve-all).
            when(repo.findItemIndicesBySelectedBranchAndEpoch("run1", "core:gate", "approved", 0))
                .thenReturn(List.of(0, 1, 2));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(anyInt())).thenReturn(context);
            when(context.runId()).thenReturn("run1");
            when(context.tenantId()).thenReturn("tenant1");
            when(svc.loadPerItemOutputsByStepKey("run1", 0, "tenant1")).thenReturn(java.util.Map.of());

            local.execute(observe, context, "run1", nodeMap);

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.times(1))
                .loadPerItemOutputsByStepKey("run1", 0, "tenant1");
            local.shutdown();
        }

        @Test
        @DisplayName("read path WARM (plain non-adjacent successor): a resultsByNode dense for ALL direct predecessors issues ZERO durable queries (linear B-reads-A chain stays query-free)")
        void plainNonAdjacentSuccessorWarmMemorySkipsDurableQuery() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, repo, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            // Plain transform reading a NON-adjacent predecessor (core:tag) - same shape as the cold
            // test, but here resultsByNode is DENSE for every routed item, INCLUDING the node's
            // direct predecessor (core:gate) - the healthy single-pod state. Before the absent-key
            // hardening, "warm" ignored the direct predecessor's presence entirely; a warm map must
            // now cover it to stay query-free (this replaces the old blind-spot pin that asserted
            // zero queries even with core:gate absent).
            TestNode observe = new TestNode("core:observe", NodeType.TRANSFORM);
            observe.setPredecessors(List.of("core:gate:approved"));
            observe.setDynamicResult(ctx -> NodeExecutionResult.success(
                "core:observe", Map.of("i", ctx.itemIndex())));
            nodeMap.put("core:observe", observe);
            nodeMap.put("core:gate", new TestNode("core:gate", NodeType.APPROVAL));

            SplitContext splitContext = SplitContext.create("core:per_tag", List.of("alpha", "beta", "gamma"))
                .withResults("core:tag", java.util.Arrays.asList(
                    Map.of("v", "tag-alpha"), Map.of("v", "tag-beta"), Map.of("v", "tag-gamma")))
                .withResults("core:gate", java.util.Arrays.asList(
                    Map.of("resolution", "APPROVED"), Map.of("resolution", "APPROVED"),
                    Map.of("resolution", "APPROVED")));
            when(contextManager.findActiveContext(eq("run1"), eq("core:observe"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(repo.findItemIndicesBySelectedBranchAndEpoch("run1", "core:gate", "approved", 0))
                .thenReturn(List.of(0, 1, 2));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(anyInt())).thenReturn(context);
            when(context.runId()).thenReturn("run1");
            when(context.tenantId()).thenReturn("tenant1");

            local.execute(observe, context, "run1", nodeMap);

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.never())
                .loadPerItemOutputsByStepKey(anyString(), anyInt(), anyString());
            local.shutdown();
        }

        /**
         * Regression for the multi-pod approval-freeze bug (2 prod replicas, sequential async
         * bridge agents): the approval fan-out ran on a pod whose in-memory resultsByNode was
         * DENSE for the nodes that executed locally but had NO entry for an upstream per-item
         * node that ran on the other pod. The old gate read the dense map as warm, skipped the
         * durable load, the delegation template resolved empty, and
         * UnifiedSignalService.registerSignal (first-registration-wins) froze the degraded
         * message. Signal-yielding nodes now bypass the warm-skip veto entirely.
         */
        @Test
        @DisplayName("Regression (cross-pod approval freeze): approval fan-out on a dense-but-incomplete map ISSUES the durable query and resolves the upstream per-item node at yield")
        void approvalFanOutOnDenseButIncompleteMapLoadsDurableAndResolvesPerItem() {
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, null, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            // The approval reads a non-split predecessor (core:prep_draft) whose in-memory slots
            // are DENSE (it re-ran on this pod during the walk)... but the template ALSO references
            // agent:classify, which ran entirely on the OTHER pod and is ABSENT from the map. With
            // every direct predecessor present-dense, inMemorySlotsComplete stays true - ONLY the
            // signal-yielding bypass triggers the durable load here.
            TestNode approve = new TestNode("core:approve", NodeType.APPROVAL);
            approve.setPredecessors(List.of("core:prep_draft"));
            java.util.Queue<Object> resolvedClassifies = new java.util.concurrent.ConcurrentLinkedQueue<>();
            approve.setDynamicResult(ctx -> {
                Object v = aliasOutput(ctx, "agent:classify", "selected_category");
                if (v != null) {
                    resolvedClassifies.add(v);
                }
                return NodeExecutionResult.success("core:approve", Map.of("ok", true));
            });
            nodeMap.put("core:approve", approve);
            nodeMap.put("core:prep_draft", new TestNode("core:prep_draft", NodeType.CODE));

            SplitContext splitContext = SplitContext.create("core:triage:0", List.of("m0", "m1", "m2"))
                .withResults("core:prep_draft", java.util.Arrays.asList(
                    Map.of("draft", "d0"), Map.of("draft", "d1"), Map.of("draft", "d2")));
            when(contextManager.findActiveContext(eq("run1"), eq("core:approve"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(anyInt())).thenReturn(context);
            when(context.runId()).thenReturn("run1");
            when(context.tenantId()).thenReturn("tenant1");
            when(svc.loadPerItemOutputsByStepKey("run1", 0, "tenant1")).thenReturn(Map.of(
                "agent:classify", Map.of(
                    0, Map.of("selected_category", "billing"),
                    1, Map.of("selected_category", "bug"),
                    2, Map.of("selected_category", "refund"))));

            local.execute(approve, context, "run1", nodeMap);

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.times(1))
                .loadPerItemOutputsByStepKey("run1", 0, "tenant1");
            assertThat(resolvedClassifies)
                .as("each item's yield-time context must resolve agent:classify to ITS OWN value, "
                  + "not collapse to a single item-0 value")
                .containsExactlyInAnyOrder("billing", "bug", "refund");
            local.shutdown();
        }

        @Test
        @DisplayName("Regression (cross-pod absent direct predecessor): a PLAIN node whose direct non-split predecessor is entirely ABSENT from a dense map issues the durable query and resolves it per item")
        void plainNodeAbsentDirectPredecessorOnDenseMapLoadsDurable() {
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, null, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            // mcp:send (NOT signal-yielding, NOT a cross-item consumer) reads core:prep_draft,
            // which ran entirely on another pod: ABSENT from the map while agent:classify is dense.
            // The old 2-arg warm check saw "dense" and skipped the load - the absent-key hardening
            // in inMemorySlotsComplete now detects the missing direct predecessor.
            TestNode send = new TestNode("mcp:send", NodeType.MCP);
            send.setPredecessors(List.of("core:prep_draft"));
            java.util.Queue<Object> resolvedDrafts = new java.util.concurrent.ConcurrentLinkedQueue<>();
            send.setDynamicResult(ctx -> {
                Object v = aliasOutput(ctx, "core:prep_draft", "draft");
                if (v != null) {
                    resolvedDrafts.add(v);
                }
                return NodeExecutionResult.success("mcp:send", Map.of("sent", true));
            });
            nodeMap.put("mcp:send", send);
            nodeMap.put("core:prep_draft", new TestNode("core:prep_draft", NodeType.CODE));

            SplitContext splitContext = SplitContext.create("core:triage:0", List.of("m0", "m1", "m2"))
                .withResults("agent:classify", java.util.Arrays.asList(
                    Map.of("c", "0"), Map.of("c", "1"), Map.of("c", "2")));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:send"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(anyInt())).thenReturn(context);
            when(context.runId()).thenReturn("run1");
            when(context.tenantId()).thenReturn("tenant1");
            when(svc.loadPerItemOutputsByStepKey("run1", 0, "tenant1")).thenReturn(Map.of(
                "core:prep_draft", Map.of(
                    0, Map.of("draft", "d0"),
                    1, Map.of("draft", "d1"),
                    2, Map.of("draft", "d2"))));

            local.execute(send, context, "run1", nodeMap);

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.times(1))
                .loadPerItemOutputsByStepKey("run1", 0, "tenant1");
            assertThat(resolvedDrafts)
                .as("the absent predecessor must resolve per item from the durable store")
                .containsExactlyInAnyOrder("d0", "d1", "d2");
            local.shutdown();
        }

        /**
         * Prod regression, 2026-07-15: the reported (A,A,A) collapse, driven through execute().
         *
         * <p>Chain {@code split -> snap -> echo_before -> think(async agent) -> echo_after}, on the
         * pod that resumes after the agent: the map is dense for {@code echo_before}/{@code think}
         * but has no entry for {@code snap}. {@code echo_after} is a PLAIN transform (not
         * signal-yielding, not a cross-item consumer), so neither unconditional bypass applies and
         * the warm-skip veto is the only thing standing between it and the durable backfill.
         * Pre-fix the veto only looked at {@code echo_after}'s DIRECT predecessor
         * ({@code think}, present) and vetoed the load, collapsing {{core:snap.output.v}} to
         * item 0's value. This also pins the nodeMap threading: without it the veto has no graph
         * to walk.
         */
        @Test
        @DisplayName("Regression (indirect ancestor A,A,A collapse): a plain node reading a 3-hop ancestor absent from a dense map issues the durable query and resolves it per item")
        void plainNodeAbsentIndirectAncestorOnDenseMapLoadsDurable() {
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, null, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            TestNode snap = new TestNode("core:snap", NodeType.TRANSFORM);
            snap.setPredecessors(List.of("core:each"));
            TestNode echoBefore = new TestNode("core:echo_before", NodeType.TRANSFORM);
            echoBefore.setPredecessors(List.of("core:snap"));
            TestNode think = new TestNode("agent:think", NodeType.AGENT);
            think.setPredecessors(List.of("core:echo_before"));
            TestNode echoAfter = new TestNode("core:echo_after", NodeType.TRANSFORM);
            echoAfter.setPredecessors(List.of("agent:think"));

            java.util.Queue<Object> resolvedSnaps = new java.util.concurrent.ConcurrentLinkedQueue<>();
            echoAfter.setDynamicResult(ctx -> {
                Object v = aliasOutput(ctx, "core:snap", "v");
                if (v != null) {
                    resolvedSnaps.add(v);
                }
                return NodeExecutionResult.success("core:echo_after", Map.of("echoed", true));
            });
            nodeMap.put("core:snap", snap);
            nodeMap.put("core:echo_before", echoBefore);
            nodeMap.put("agent:think", think);
            nodeMap.put("core:echo_after", echoAfter);

            SplitContext splitContext = SplitContext.create("core:each:0", List.of("A", "B", "C"))
                .withResults("core:echo_before", java.util.Arrays.asList(
                    Map.of("v", "A"), Map.of("v", "B"), Map.of("v", "C")))
                .withResults("agent:think", java.util.Arrays.asList(
                    Map.of("response", "r0"), Map.of("response", "r1"), Map.of("response", "r2")));
            when(contextManager.findActiveContext(eq("run1"), eq("core:echo_after"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(anyInt())).thenReturn(context);
            when(context.runId()).thenReturn("run1");
            when(context.tenantId()).thenReturn("tenant1");
            when(svc.loadPerItemOutputsByStepKey("run1", 0, "tenant1")).thenReturn(Map.of(
                "core:snap", Map.of(
                    0, Map.of("v", "A"),
                    1, Map.of("v", "B"),
                    2, Map.of("v", "C"))));

            local.execute(echoAfter, context, "run1", nodeMap);

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.times(1))
                .loadPerItemOutputsByStepKey("run1", 0, "tenant1");
            assertThat(resolvedSnaps)
                .as("each item must resolve {{core:snap.output.v}} to ITS OWN value: the reported "
                  + "bug returned A,A,A here")
                .containsExactlyInAnyOrder("A", "B", "C");
            local.shutdown();
        }

        /**
         * Pins the invariant the whole nodeMap-scope decision rests on.
         *
         * <p>The ancestor scope is only wired on the STEP_BY_STEP / resume path, because AUTO passes
         * a {nodeId: node} singleton map. That is safe ONLY while AUTO never runs a non-direct
         * split successor through the gate: the "auto mode + not direct successor" branch in
         * execute() returns from executeNodeBody and never reaches executeForAllItemsAndTraverse,
         * so the singleton map only ever meets the gate for DIRECT successors, whose ancestor set
         * is empty and walled anyway.
         *
         * <p>If a future change lets AUTO fall through to the fan-out, the singleton map would
         * silently narrow the veto to direct predecessors and the item-0 collapse would return with
         * no test saying a word. This asserts the seam itself: a dense-but-incomplete map that WOULD
         * force a durable load if the gate were reached must issue ZERO durable queries under AUTO.
         */
        @Test
        @DisplayName("AUTO invariant: a non-direct split successor never reaches the fan-out gate, so the singleton nodeMap never narrows the ancestor scope")
        void autoModeNonDirectSuccessorNeverReachesTheDurableGate() {
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, null, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            // core:echo_after reads a non-split predecessor (readsNonSplitPredecessor -> true) and
            // its ancestor core:snap is ABSENT from an otherwise dense map. Were the gate reached,
            // inMemorySlotsComplete would return false and a durable epoch query WOULD fire.
            TestNode echoAfter = new TestNode("core:echo_after", NodeType.TRANSFORM);
            echoAfter.setPredecessors(List.of("agent:think"));
            echoAfter.setExecuteResult(NodeExecutionResult.success("core:echo_after", Map.of("ok", true)));
            TestNode think = new TestNode("agent:think", NodeType.AGENT);
            think.setPredecessors(List.of("core:snap"));   // core:snap is the ABSENT indirect ancestor
            TestNode snap = new TestNode("core:snap", NodeType.TRANSFORM);
            snap.setPredecessors(List.of("core:each"));
            nodeMap.put("core:echo_after", echoAfter);
            nodeMap.put("agent:think", think);
            nodeMap.put("core:snap", snap);
            nodeMap.put("core:each", new TestNode("core:each", NodeType.SPLIT));

            // Dense for agent:think, NO entry for core:snap: exactly the prod shape that makes
            // inMemorySlotsComplete return false and the gate fire a durable query.
            SplitContext splitContext = SplitContext.create("core:each:0", List.of("A", "B", "C"))
                .withResults("agent:think", java.util.Arrays.asList(
                    Map.of("response", "r0"), Map.of("response", "r1"), Map.of("response", "r2")));
            when(contextManager.findActiveContext(eq("run1"), eq("core:echo_after"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.itemIndex()).thenReturn(1);
            // Fully stub the fan-out's context needs, so that if the gate WERE reached the durable
            // query would really be issued (an under-stubbed context would make verify(never) pass
            // for the wrong reason).
            org.mockito.Mockito.lenient().when(context.withGlobalData(any(), any())).thenReturn(context);
            org.mockito.Mockito.lenient().when(context.withItemIndex(anyInt())).thenReturn(context);
            org.mockito.Mockito.lenient().when(context.runId()).thenReturn("run1");
            org.mockito.Mockito.lenient().when(context.tenantId()).thenReturn("tenant1");
            org.mockito.Mockito.lenient().when(svc.loadPerItemOutputsByStepKey("run1", 0, "tenant1"))
                .thenReturn(Map.of("core:snap", Map.of(0, Map.of("v", "A"))));

            // Auto mode is signalled by a non-null SuccessorTraverser.
            SplitAwareNodeExecutor.SuccessorTraverser noopTraverser = (s, c, idx) -> c;

            local.execute(echoAfter, context, "run1", nodeMap, null, null, 0, noopTraverser);

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.never())
                .loadPerItemOutputsByStepKey(anyString(), anyInt(), anyString());
            assertThat(echoAfter.getExecuteCount())
                .as("AUTO executes the chained node ONCE for its own item (the per-item fan-out, "
                  + "and therefore the gate, belongs to the resume path that carries the full map)")
                .isEqualTo(1);
            local.shutdown();
        }

        @Test
        @DisplayName("Regression (walk mode): a perItemContinuation fan-out loads durable even when resultsByNode is fully dense (continuation walks span pods)")
        void perItemContinuationWalkLoadsDurableEvenWhenMapIsDense() {
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, null, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            // Plain transform whose direct predecessor IS dense in memory: pre-fix, the
            // perItemContinuation trigger sat BEHIND the warm-skip veto, so a dense map suppressed
            // the durable load a continuation walk depends on (walks span resumes and pods).
            TestNode observe = new TestNode("core:observe", NodeType.TRANSFORM);
            observe.setPredecessors(List.of("core:tag"));
            observe.setDynamicResult(ctx -> NodeExecutionResult.success(
                "core:observe", Map.of("i", ctx.itemIndex())));
            nodeMap.put("core:observe", observe);
            nodeMap.put("core:tag", new TestNode("core:tag", NodeType.TRANSFORM));

            SplitContext splitContext = SplitContext.create("core:per_tag:0", List.of("alpha", "beta", "gamma"))
                .withResults("core:tag", java.util.Arrays.asList(
                    Map.of("v", "t0"), Map.of("v", "t1"), Map.of("v", "t2")));
            when(contextManager.findActiveContext(eq("run1"), eq("core:observe"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(anyInt())).thenReturn(context);
            when(context.runId()).thenReturn("run1");
            when(context.tenantId()).thenReturn("tenant1");
            when(svc.loadPerItemOutputsByStepKey("run1", 0, "tenant1")).thenReturn(java.util.Map.of());

            local.execute(observe, context, "run1", nodeMap, null, null, 0, null,
                SplitExecutionOptions.perItemContinuationWalk());

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.times(1))
                .loadPerItemOutputsByStepKey("run1", 0, "tenant1");
            local.shutdown();
        }

        @Test
        @DisplayName("read path: a signal-yielding node DIRECTLY after the split (only predecessor = split) stays query-free even with an empty map")
        void signalYieldingDirectSuccessorStaysQueryFree() {
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, null, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            // Approval placed DIRECTLY after the split: its templates can only reference
            // {{item}}/current_item, so despite being signal-yielding (veto bypass), the
            // consumer-shape clause (readsNonSplitPredecessor false) keeps it query-free.
            TestNode gate = new TestNode("core:gate", NodeType.APPROVAL);
            gate.setPredecessors(List.of("core:triage"));
            gate.setExecuteResult(NodeExecutionResult.success("core:gate", Map.of("ok", true)));
            nodeMap.put("core:gate", gate);
            nodeMap.put("core:triage", new TestNode("core:triage", NodeType.SPLIT));

            SplitContext splitContext = SplitContext.create("core:triage:0", List.of("m0", "m1", "m2"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:gate"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(anyInt())).thenReturn(context);

            local.execute(gate, context, "run1", nodeMap);

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.never())
                .loadPerItemOutputsByStepKey(anyString(), anyInt(), anyString());
            local.shutdown();
        }

        /**
         * End-to-end regression at the UserApprovalNode level for the frozen-delegation bug:
         * a REAL approval node with a Telegram delegation whose messageTemplate references TWO
         * step keys - one warm in memory (mcp:fetch, the node's direct predecessor) and one that
         * only exists in the durable store (core:prep_draft, ran on the other pod). Pre-fix the
         * dense in-memory map vetoed the durable load, the draft reference resolved empty, and
         * the degraded message froze in signal_config (registerSignal is first-registration-wins).
         */
        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("Regression (frozen delegation message): approval delegation resolves BOTH a memory-served and a durable-only step key per item at yield")
        void approvalDelegationMessageResolvesDurableOnlyStepKeyPerItemAtYield() {
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, null, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            // Real template stack (same engine prod uses) so the delegation message truly resolves.
            com.apimarketplace.orchestrator.services.template.SpelEvaluator spel =
                new com.apimarketplace.orchestrator.services.template.SpelEvaluator();
            spel.init();
            com.apimarketplace.orchestrator.services.template.PathNavigator nav =
                new com.apimarketplace.orchestrator.services.template.PathNavigator();
            com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter realAdapter =
                new com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter(
                    new com.apimarketplace.orchestrator.services.TemplateEngine(
                        new com.apimarketplace.orchestrator.services.TypeCastingService(),
                        new com.apimarketplace.orchestrator.services.template.NamespaceResolver(nav),
                        nav, spel));

            com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService signalService =
                org.mockito.Mockito.mock(
                    com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService.class);
            com.apimarketplace.orchestrator.execution.v2.nodes.UserApprovalNode gate =
                com.apimarketplace.orchestrator.execution.v2.nodes.UserApprovalNode.builder()
                    .nodeId("core:gate")
                    .approverRoles(List.of("manager"))
                    .requiredApprovals(1)
                    .timeoutMs(60000L)
                    .delegation(new com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation(
                        "telegram", 42L, "123456",
                        "Subject: {{mcp:fetch.output.subject}} / Draft: {{core:prep_draft.output.draft}}",
                        "", List.of(), null, null))
                    .build();
            // Direct predecessor is WARM in memory -> pre-fix the veto read this as "complete";
            // ONLY the signal-yielding bypass makes the durable load fire here.
            gate.setPredecessors(List.of("mcp:fetch"));
            gate.setSignalService(signalService);
            gate.setTemplateAdapter(realAdapter);
            nodeMap.put("core:gate", gate);
            nodeMap.put("mcp:fetch", new TestNode("mcp:fetch", NodeType.MCP));

            SplitContext splitContext = SplitContext.create("core:triage:0", List.of("m0", "m1", "m2"))
                .withResults("mcp:fetch", java.util.Arrays.asList(
                    Map.of("subject", "s0"), Map.of("subject", "s1"), Map.of("subject", "s2")));
            ExecutionContext realContext = ExecutionContext.create(
                "run1", "wfr1", "tenant1", "0", 0, new HashMap<>(), null);
            when(contextManager.findActiveContext(eq("run1"), eq("core:gate"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(svc.loadPerItemOutputsByStepKey("run1", 0, "tenant1")).thenReturn(Map.of(
                "core:prep_draft", Map.of(
                    0, Map.of("draft", "d0"),
                    1, Map.of("draft", "d1"),
                    2, Map.of("draft", "d2"))));

            NodeExecutionResult summary = local.execute(gate, realContext, "run1", nodeMap);

            assertThat(summary.status()).isEqualTo(NodeStatus.AWAITING_SIGNAL);
            org.mockito.ArgumentCaptor<Map<String, Object>> configCaptor =
                org.mockito.ArgumentCaptor.forClass((Class) Map.class);
            verify(signalService, times(3)).registerSignal(
                eq("run1"), any(), eq("core:gate"), any(), anyInt(),
                eq(com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL),
                configCaptor.capture(), any(), any());
            List<String> messages = configCaptor.getAllValues().stream()
                .map(cfg -> (Map<String, Object>) cfg.get("delegation"))
                .map(d -> (String) d.get("message"))
                .toList();
            assertThat(messages)
                .as("each per-item FROZEN delegation message must carry ITS item's values for "
                  + "both the memory-served (mcp:fetch) and the durable-only (core:prep_draft) "
                  + "step keys - the durable one with the non-item-0 value")
                .containsExactlyInAnyOrder(
                    "Subject: s0 / Draft: d0",
                    "Subject: s1 / Draft: d1",
                    "Subject: s2 / Draft: d2");
            local.shutdown();
        }

        @Test
        @DisplayName("read path: a plain DIRECT split successor with empty resultsByNode still issues ZERO durable queries (first-node hot path stays query-free)")
        void plainDirectSuccessorColdMemoryStillSkipsDurableQuery() {
            WorkflowStepDataRepository repo = org.mockito.Mockito.mock(WorkflowStepDataRepository.class);
            com.apimarketplace.orchestrator.services.StepOutputService svc =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.StepOutputService.class);
            SplitAwareNodeExecutor local = new SplitAwareNodeExecutor(
                contextManager, null, null, null, repo, null, Executors.newFixedThreadPool(2));
            local.setStepOutputService(svc);

            // FIRST node after the split: its only predecessor is the split node -> direct successor.
            TestNode tag = new TestNode("core:tag", NodeType.TRANSFORM);
            tag.setPredecessors(List.of("core:per_tag"));
            tag.setDynamicResult(ctx -> NodeExecutionResult.success("core:tag", Map.of("i", ctx.itemIndex())));
            nodeMap.put("core:tag", tag);
            nodeMap.put("core:per_tag", new TestNode("core:per_tag", NodeType.SPLIT));

            // Even with an EMPTY map (legitimately empty - nothing ran yet) the direct successor
            // must NOT pay a durable read (there is no prior per-item predecessor to backfill).
            SplitContext splitContext = SplitContext.create("core:per_tag", List.of("alpha", "beta", "gamma"));
            when(contextManager.findActiveContext(eq("run1"), eq("core:tag"), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
            when(context.withGlobalData(any(), any())).thenReturn(context);
            when(context.withItemIndex(anyInt())).thenReturn(context);
            when(context.runId()).thenReturn("run1");
            when(context.tenantId()).thenReturn("tenant1");

            local.execute(tag, context, "run1", nodeMap);

            org.mockito.Mockito.verify(svc, org.mockito.Mockito.never())
                .loadPerItemOutputsByStepKey(anyString(), anyInt(), anyString());
            local.shutdown();
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

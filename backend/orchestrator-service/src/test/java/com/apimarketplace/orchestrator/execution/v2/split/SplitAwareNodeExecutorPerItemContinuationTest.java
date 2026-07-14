package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Per-item continuation walk disposition of {@link SplitAwareNodeExecutor}
 * ({@link SplitExecutionOptions#perItemContinuationWalk()}), used by approval
 * {@code continuationMode=per_item} inside a split. Pins the four branch points the
 * options flag flips (durable item exclusion, suppressGlobalMark persist, suppressed
 * skip records, deferred no-op result) AND the regression contract that
 * {@link SplitExecutionOptions#NONE} / the legacy 8-arg overload stays byte-identical
 * to pre-feature behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SplitAwareNodeExecutor - per-item continuation walk")
class SplitAwareNodeExecutorPerItemContinuationTest {

    @Mock
    private SplitContextManager contextManager;

    @Mock
    private ExecutionContext context;

    @Mock
    private WorkflowStepDataRepository repo;

    @Mock
    private NodeCompletionService completionService;

    @Mock
    private WorkflowExecution execution;

    @Mock
    private RunningNodeTracker runningNodeTracker;

    private SplitAwareNodeExecutor executor;
    private Map<String, ExecutionNode> nodeMap;

    @BeforeEach
    void setUp() {
        executor = new SplitAwareNodeExecutor(
            contextManager,
            completionService,
            null,  // EdgeStatusEmitter - not needed
            null,  // SnapshotService - not needed
            repo,
            null,  // StateSnapshotService - not needed
            Executors.newFixedThreadPool(2)
        );
        executor.setRunningNodeTracker(runningNodeTracker);
        nodeMap = new HashMap<>();
        lenient().when(execution.getRunId()).thenReturn("run1");
        lenient().when(context.epoch()).thenReturn(5);
        lenient().when(context.triggerId()).thenReturn("trigger:start");
        lenient().when(context.withGlobalData(any(), any())).thenReturn(context);
        lenient().when(context.withItemIndex(anyInt())).thenReturn(context);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    /** A plain MCP node that is a DIRECT split successor (all items routed by default). */
    private TestNode directSplitSuccessor(String nodeId) {
        TestNode node = new TestNode(nodeId, NodeType.MCP);
        node.setPredecessors(List.of("core:split1"));
        node.setExecuteResult(NodeExecutionResult.success(nodeId, Map.of("ok", true)));
        nodeMap.put(nodeId, node);
        nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));
        return node;
    }

    private SplitContext stubSplitContext(String nodeId, List<Object> items) {
        SplitContext splitContext = SplitContext.create("core:split1:0", items);
        when(contextManager.findActiveContext(eq("run1"), eq(nodeId), eq(0), any()))
            .thenReturn(Optional.of(splitContext));
        return splitContext;
    }

    private NodeExecutionResult executeWithContinuation(TestNode node) {
        return executor.execute(node, context, "run1", nodeMap, execution, null, 0, null,
            SplitExecutionOptions.perItemContinuationWalk());
    }

    @Nested
    @DisplayName("durable per-item idempotency (already-persisted item exclusion)")
    class AlreadyPersistedExclusion {

        @Test
        @DisplayName("an item with a terminal workflow_step_data row is EXCLUDED from the walk: only the remaining item executes")
        void alreadyPersistedItemIsExcludedFromExecution() {
            TestNode node = directSplitSuccessor("mcp:step1");
            stubSplitContext("mcp:step1", List.of("a", "b"));
            // Item 0's terminal row already landed in a prior walk.
            when(repo.findTerminalItemIndicesByEpoch("run1", "mcp:step1", 5))
                .thenReturn(List.of(0));

            NodeExecutionResult result = executeWithContinuation(node);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(node.getExecuteCount())
                .as("only item 1 (no terminal row yet) may execute - item 0 already ran and its side effects landed")
                .isEqualTo(1);
            // The executed item is item 1: its context is stamped with index 1, never 0.
            verify(context).withItemIndex(1);
            verify(context, never()).withItemIndex(0);
        }

        @Test
        @DisplayName("fully-persisted node (every routed item already has a terminal row) returns the benign deferred result, NOT a skip")
        void fullyPersistedNodeDefersWithoutSkipping() {
            TestNode node = directSplitSuccessor("mcp:step1");
            stubSplitContext("mcp:step1", List.of("a", "b"));
            when(repo.findTerminalItemIndicesByEpoch("run1", "mcp:step1", 5))
                .thenReturn(List.of(0, 1));

            NodeExecutionResult result = executeWithContinuation(node);

            assertThat(result.status())
                .as("deferred no-op must be COMPLETED with SPLIT_ALREADY_PERSISTED so the engine records no node-level state")
                .isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.metadata())
                .containsEntry(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true)
                .containsEntry("per_item_continuation_deferred", true);
            assertThat(node.getExecuteCount()).isZero();
            // No skip rows, no node-level completion: nothing reached the completion service.
            verifyNoInteractions(completionService);
            // The deferred no-op rebalances the running tracker (emitNodeStart marked it
            // running) so the per-epoch running count doesn't drift up on each no-op walk.
            verify(runningNodeTracker).markCompleted("run1", 5, "mcp:step1");
        }

        @Test
        @DisplayName("wrong-port region node (resolved item routed elsewhere) defers instead of marking node-level SKIPPED")
        void wrongPortWalkDefersInsteadOfSkipping() {
            // Node hangs off the approval's 'approved' port; the resolved item was REJECTED,
            // so no row selected this branch yet - routing only becomes final at seal.
            TestNode node = new TestNode("mcp:notify", NodeType.MCP);
            node.setPredecessors(List.of("core:item_approval:approved"));
            nodeMap.put("mcp:notify", node);
            nodeMap.put("core:item_approval", new TestNode("core:item_approval", NodeType.APPROVAL));
            stubSplitContext("mcp:notify", List.of("a", "b", "c"));
            when(repo.findItemIndicesBySelectedBranchAndEpoch("run1", "core:item_approval", "approved", 5))
                .thenReturn(List.of());
            lenient().when(repo.findTerminalItemIndicesByEpoch("run1", "mcp:notify", 5))
                .thenReturn(List.of());

            NodeExecutionResult result = executeWithContinuation(node);

            assertThat(result.status())
                .as("continuation mode must NOT mark the node SKIPPED - sibling approvals may still route items here")
                .isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.metadata())
                .containsEntry(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true)
                .containsEntry("per_item_continuation_deferred", true);
            assertThat(result.metadata())
                .doesNotContainKey(ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT);
            assertThat(node.getExecuteCount()).isZero();
            verifyNoInteractions(completionService);
        }

        @Test
        @DisplayName("repository failure fails CLOSED: no item executes (deferring is safe, re-executing side effects is not)")
        void repositoryFailureFailsClosed() {
            TestNode node = directSplitSuccessor("mcp:step1");
            stubSplitContext("mcp:step1", List.of("a", "b"));
            when(repo.findTerminalItemIndicesByEpoch("run1", "mcp:step1", 5))
                .thenThrow(new RuntimeException("connection refused"));

            NodeExecutionResult result = executeWithContinuation(node);

            assertThat(node.getExecuteCount())
                .as("without the exclusion set we cannot prove an item was not already executed - defer everything")
                .isZero();
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.metadata())
                .containsEntry("per_item_continuation_deferred", true)
                .containsEntry(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true);
            verifyNoInteractions(completionService);
        }
    }

    @Nested
    @DisplayName("suppressGlobalMark persistence")
    class SuppressGlobalMarkPersistence {

        @Test
        @DisplayName("executed items persist via emitNodeCompletePerItem (suppressGlobalMark path), never the plain emitNodeComplete")
        void executedItemsPersistViaPerItemOverload() {
            TestNode node = directSplitSuccessor("mcp:step1");
            stubSplitContext("mcp:step1", List.of("a", "b"));
            when(repo.findTerminalItemIndicesByEpoch("run1", "mcp:step1", 5))
                .thenReturn(List.of());

            executeWithContinuation(node);

            verify(completionService, times(2)).emitNodeCompletePerItem(
                eq(execution), eq(node), any(NodeExecutionResult.class), isNull(), anyInt(),
                any(ExecutionContext.class));
            verify(completionService, never()).emitNodeComplete(
                any(), any(), any(), any(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("skip-record suppression")
    class SkipRecordSuppression {

        private TestNode approvedPortNode() {
            TestNode node = new TestNode("mcp:notify", NodeType.MCP);
            node.setPredecessors(List.of("core:item_approval:approved"));
            node.setExecuteResult(NodeExecutionResult.success("mcp:notify", Map.of("ok", true)));
            nodeMap.put("mcp:notify", node);
            nodeMap.put("core:item_approval", new TestNode("core:item_approval", NodeType.APPROVAL));
            return node;
        }

        @Test
        @DisplayName("continuation walk with a routed SUBSET writes NO skipped-item records - unrouted siblings are PENDING, not skipped")
        void continuationWalkSuppressesSkippedItemRecords() {
            TestNode node = approvedPortNode();
            stubSplitContext("mcp:notify", List.of("a", "b", "c"));
            // Only item 1 approved so far; items 0 and 2 still awaiting their signal.
            when(repo.findItemIndicesBySelectedBranchAndEpoch("run1", "core:item_approval", "approved", 5))
                .thenReturn(List.of(1));
            when(repo.findTerminalItemIndicesByEpoch("run1", "mcp:notify", 5))
                .thenReturn(List.of());

            executeWithContinuation(node);

            assertThat(node.getExecuteCount()).isEqualTo(1);
            verify(completionService, never()).emitNodeSkippedForItem(
                any(), any(), anyInt(), anyString(), anyInt(), anyString());
            verify(completionService, never()).emitNodeSkippedForItem(
                any(), any(), anyInt(), anyString());
            verify(completionService).emitNodeCompletePerItem(
                eq(execution), eq(node), any(NodeExecutionResult.class), isNull(), eq(1),
                any(ExecutionContext.class));
        }

        @Test
        @DisplayName("CONTROL: the same routed subset in normal mode (NONE) DOES persist per-item SKIPPED records")
        void normalModePersistsSkippedItemRecordsForSameShape() {
            TestNode node = approvedPortNode();
            stubSplitContext("mcp:notify", List.of("a", "b", "c"));
            when(repo.findItemIndicesBySelectedBranchAndEpoch("run1", "core:item_approval", "approved", 5))
                .thenReturn(List.of(1));

            executor.execute(node, context, "run1", nodeMap, execution, null, 0, null,
                SplitExecutionOptions.NONE);

            // Items 0 and 2 are unrouted -> SKIPPED rows under the real (epoch, triggerId).
            verify(completionService).emitNodeSkippedForItem(
                eq(execution), eq(node), eq(0), eq("Not routed to this branch"), eq(5), eq("trigger:start"));
            verify(completionService).emitNodeSkippedForItem(
                eq(execution), eq(node), eq(2), eq("Not routed to this branch"), eq(5), eq("trigger:start"));
        }
    }

    @Nested
    @DisplayName("continuation summary")
    class ContinuationSummary {

        @Test
        @DisplayName("the walk summary carries SPLIT_ALREADY_PERSISTED + per_item_continuation so the engine records no node-level completion")
        void continuationSummaryCarriesMarkers() {
            TestNode node = directSplitSuccessor("mcp:step1");
            stubSplitContext("mcp:step1", List.of("a", "b"));
            when(repo.findTerminalItemIndicesByEpoch("run1", "mcp:step1", 5))
                .thenReturn(List.of());

            NodeExecutionResult result = executeWithContinuation(node);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.metadata())
                .containsEntry(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true)
                .containsEntry("per_item_continuation", true);
        }
    }

    @Nested
    @DisplayName("missing split context fails CLOSED (audit FIX 2)")
    class MissingSplitContextFailClosed {

        @Test
        @DisplayName("plain-node branch: no split context + continuation options -> deferred no-op, node NEVER executes, tracker rebalanced")
        void plainNodeBranchDefersWhenSplitContextMissing() {
            // Walk node whose split context could not be restored (cross-pod restore
            // failure, eviction). Pre-fix this degraded to a single UNGUARDED node-level
            // execution (executeNodeBody) - persisting node-level state mid-barrier.
            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setPredecessors(List.of("core:split1"));
            nodeMap.put("mcp:step1", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.empty());

            NodeExecutionResult result = executeWithContinuation(node);

            assertThat(node.getExecuteCount())
                .as("the node body must NOT run - a degraded single execution would bypass the per-item guards")
                .isZero();
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.metadata())
                .containsEntry(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true)
                .containsEntry("per_item_continuation_deferred", true);
            verify(runningNodeTracker).markCompleted("run1", 5, "mcp:step1");
            verifyNoInteractions(completionService);
        }

        @Test
        @DisplayName("routing-node branch: decision with no split context + continuation options -> deferred no-op, decision NEVER executes")
        void routingNodeBranchDefersWhenSplitContextMissing() {
            // Decision/switch nodes take the EARLIER shouldSkipSplitHandling branch, which
            // has its own findSplitContextWithFallback + executeNodeBody degrade path -
            // it must fail closed under continuation options exactly like the plain branch.
            TestNode decision = new TestNode("core:route", NodeType.DECISION);
            decision.setPredecessors(List.of("core:split1"));
            nodeMap.put("core:route", decision);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));
            when(contextManager.findActiveContext(eq("run1"), eq("core:route"), eq(0), any()))
                .thenReturn(Optional.empty());

            NodeExecutionResult result = executeWithContinuation(decision);

            assertThat(decision.getExecuteCount()).isZero();
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.metadata())
                .containsEntry(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true)
                .containsEntry("per_item_continuation_deferred", true);
            verify(runningNodeTracker).markCompleted("run1", 5, "core:route");
            verifyNoInteractions(completionService);
        }

        @Test
        @DisplayName("CONTROL: NONE mode with no split context still degrades to normal single execution (pre-feature behavior)")
        void noneDispositionStillExecutesNormallyWithoutSplitContext() {
            TestNode node = new TestNode("mcp:step1", NodeType.MCP);
            node.setPredecessors(List.of("core:split1"));
            node.setExecuteResult(NodeExecutionResult.success("mcp:step1", Map.of("ok", true)));
            nodeMap.put("mcp:step1", node);
            nodeMap.put("core:split1", new TestNode("core:split1", NodeType.SPLIT));
            when(contextManager.findActiveContext(eq("run1"), eq("mcp:step1"), eq(0), any()))
                .thenReturn(Optional.empty());

            NodeExecutionResult result = executor.execute(
                node, context, "run1", nodeMap, execution, null, 0, null, SplitExecutionOptions.NONE);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(node.getExecuteCount())
                .as("outside a walk, a node without split context executes once - unchanged")
                .isEqualTo(1);
            assertThat(result.metadata()).doesNotContainKey("per_item_continuation_deferred");
        }
    }

    @Nested
    @DisplayName("REGRESSION: default disposition (NONE / legacy 8-arg overload) is byte-identical to pre-feature behavior")
    class DefaultDispositionRegression {

        @Test
        @DisplayName("NONE never consults findTerminalItemIndicesByEpoch and persists via the plain emitNodeComplete")
        void noneDispositionKeepsPlainPersistPathAndNoExclusionQuery() {
            TestNode node = directSplitSuccessor("mcp:step1");
            stubSplitContext("mcp:step1", List.of("a", "b"));

            NodeExecutionResult result = executor.execute(
                node, context, "run1", nodeMap, execution, null, 0, null, SplitExecutionOptions.NONE);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(node.getExecuteCount()).isEqualTo(2);
            verify(repo, never()).findTerminalItemIndicesByEpoch(anyString(), anyString(), anyInt());
            verify(completionService, times(2)).emitNodeComplete(
                eq(execution), eq(node), any(NodeExecutionResult.class), isNull(), anyInt(),
                any(ExecutionContext.class));
            verify(completionService, never()).emitNodeCompletePerItem(
                any(), any(), any(), any(), anyInt(), any());
            assertThat(result.metadata()).doesNotContainKey("per_item_continuation");
        }

        @Test
        @DisplayName("the legacy 8-arg overload behaves exactly like NONE (no continuation branch points)")
        void legacyOverloadBehavesLikeNone() {
            TestNode node = directSplitSuccessor("mcp:step1");
            stubSplitContext("mcp:step1", List.of("a", "b"));

            NodeExecutionResult result = executor.execute(
                node, context, "run1", nodeMap, execution, null, 0, null);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(node.getExecuteCount()).isEqualTo(2);
            verify(repo, never()).findTerminalItemIndicesByEpoch(anyString(), anyString(), anyInt());
            verify(completionService, never()).emitNodeCompletePerItem(
                any(), any(), any(), any(), anyInt(), any());
            assertThat(result.metadata()).doesNotContainKey("per_item_continuation");
        }
    }

    // Simple test node implementation (mirrors SplitAwareNodeExecutorTest.TestNode)
    private static class TestNode extends BaseNode {
        private NodeExecutionResult executeResult;
        private final java.util.concurrent.atomic.AtomicInteger executeCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

        TestNode(String nodeId, NodeType type) {
            super(nodeId, type);
        }

        void setExecuteResult(NodeExecutionResult result) {
            this.executeResult = result;
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
        public boolean isDecisionNode() {
            return type == NodeType.DECISION;
        }

        @Override
        public NodeExecutionResult execute(ExecutionContext context) {
            executeCount.incrementAndGet();
            return executeResult != null ? executeResult : NodeExecutionResult.success(nodeId, Map.of());
        }
    }
}

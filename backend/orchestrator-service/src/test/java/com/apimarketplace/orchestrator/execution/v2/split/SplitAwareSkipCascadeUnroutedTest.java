package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the 2026-05-20 prod bug: per-item SKIP cascade was missing when
 * all split items get classified to a single branch.
 *
 * <p><b>Repro condition</b> (from prod run {@code 656a4aed} epoch 16, 19:00 UTC):
 * 2 emails both routed to category "reseaux" by classify. {@code apply_reseaux}
 * COMPLETED for both items; {@code apply_finance / newsletters / professionnel /
 * promotions / tech / urgent} all SKIPPED via {@code persistSkippedItemRecords} with
 * reason "Not routed to this branch". But downstream {@code record_finance /
 * newsletters / …} were NEVER enqueued by ReadyNodeCalculator (predecessor has 0
 * COMPLETED items), so no SKIPPED step_data rows landed for them. Frontend inspector
 * showed them as never-ran and {@code core:collect_urgents} stayed PENDING.
 *
 * <p><b>Fix</b>: {@link SplitAwareNodeExecutor#persistSkippedItemRecords} now invokes
 * {@link V2SkipPropagationService#cascadeFailureToSuccessors} for each non-routed
 * item index, propagating the SKIP through downstream descendants (per-item scope,
 * idempotent via the unique step_data index).
 *
 * <p>This test pins the contract: for every non-routed item index, the cascade
 * service is invoked exactly once with {@code perItemScope=true} and the source
 * tag {@code split_unrouted}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SplitAwareNodeExecutor - per-item SKIP cascade for unrouted items (prod fire 2026-05-20)")
class SplitAwareSkipCascadeUnroutedTest {

    @Mock private SplitContextManager contextManager;
    @Mock private NodeCompletionService nodeCompletionService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private V2SkipPropagationService skipPropagationService;
    @Mock private WorkflowExecution execution;
    @Mock private ExecutionNode applyNode;

    private SplitAwareNodeExecutor executor;
    private Method persistSkippedItemRecords;
    private Method persistSkippedItemRecordsWithOptions;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        executor = new SplitAwareNodeExecutor(
            contextManager,
            nodeCompletionService,
            null,
            null,
            null,
            stateSnapshotService,
            Executors.newFixedThreadPool(2));
        executor.setSkipPropagationService(skipPropagationService);

        when(applyNode.getNodeId()).thenReturn("mcp:apply_urgent");
        when(applyNode.getSuccessors()).thenReturn(java.util.List.of());
        when(execution.getRunId()).thenReturn("run_test_skip_cascade");

        persistSkippedItemRecords = SplitAwareNodeExecutor.class.getDeclaredMethod(
            "persistSkippedItemRecords",
            WorkflowExecution.class, ExecutionNode.class, Set.class, int.class, int.class, String.class);
        persistSkippedItemRecords.setAccessible(true);
        persistSkippedItemRecordsWithOptions = SplitAwareNodeExecutor.class.getDeclaredMethod(
            "persistSkippedItemRecords",
            WorkflowExecution.class, ExecutionNode.class, Set.class, int.class, int.class, String.class,
            boolean.class, boolean.class);
        persistSkippedItemRecordsWithOptions.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("cascadeFailureToSuccessors invoked for every unrouted item (2 items, 0 routed) - regression for record_* never-ran")
    void cascadesSkipForAllItemsWhenNoneRouted() throws Exception {
        int totalItems = 2;
        Set<Integer> routedItemIndices = new HashSet<>(); // 0 routed - all items go through cascade
        int epoch = 16;
        String triggerId = "trigger:cron";

        persistSkippedItemRecordsWithCascade(
            execution, applyNode, routedItemIndices, totalItems, epoch, triggerId);

        // Pass 1: each non-routed item gets its own SKIPPED row.
        verify(nodeCompletionService, times(totalItems))
            .emitNodeSkippedForItem(eq(execution), eq(applyNode), any(Integer.class),
                eq("Not routed to this branch"), eq(epoch), eq(triggerId));
        verify(nodeCompletionService).emitNodeSkippedForItem(
            execution, applyNode, 0, "Not routed to this branch", epoch, triggerId);
        verify(nodeCompletionService).emitNodeSkippedForItem(
            execution, applyNode, 1, "Not routed to this branch", epoch, triggerId);

        // Pass 2 (the regression-guard): cascade to downstream descendants per item.
        // perItemScope=true so EpochState.skippedNodeIds is not mutated (the node may
        // still execute for other items in mixed-routing scenarios); source tag
        // distinguishes from agent-failure cascades in Micrometer telemetry.
        verify(skipPropagationService, times(totalItems))
            .cascadeFailureToSuccessors(eq(execution), eq(applyNode), any(Integer.class),
                eq(epoch), eq(triggerId), eq(true), eq("split_unrouted"));
        verify(skipPropagationService).cascadeFailureToSuccessors(
            execution, applyNode, 0, epoch, triggerId, true, "split_unrouted");
        verify(skipPropagationService).cascadeFailureToSuccessors(
            execution, applyNode, 1, epoch, triggerId, true, "split_unrouted");
    }

    @Test
    @DisplayName("cascadeFailureToSuccessors skipped for routed items (partial routing - only unrouted indices cascade)")
    void cascadesOnlyForNonRoutedItems() throws Exception {
        int totalItems = 3;
        Set<Integer> routedItemIndices = Set.of(0, 2); // items 0 and 2 routed, item 1 unrouted
        int epoch = 16;
        String triggerId = "trigger:cron";

        persistSkippedItemRecordsWithCascade(
            execution, applyNode, routedItemIndices, totalItems, epoch, triggerId);

        // Item 1 is the only one skipped → exactly one emit + one cascade.
        verify(nodeCompletionService, times(1))
            .emitNodeSkippedForItem(eq(execution), eq(applyNode), eq(1),
                eq("Not routed to this branch"), eq(epoch), eq(triggerId));
        verify(skipPropagationService, times(1))
            .cascadeFailureToSuccessors(eq(execution), eq(applyNode), eq(1),
                eq(epoch), eq(triggerId), eq(true), eq("split_unrouted"));

        // Items 0 and 2 (routed) must NOT trigger cascade - they completed via the
        // normal path and their downstream record_X runs via the standard executor.
        verify(skipPropagationService, never())
            .cascadeFailureToSuccessors(any(), any(), eq(0), anyInt(), any(), anyBoolean(), any());
        verify(skipPropagationService, never())
            .cascadeFailureToSuccessors(any(), any(), eq(2), anyInt(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("cascade no-op when skipPropagationService null (back-compat for test harnesses without the bean)")
    void noOpWhenServiceMissing() throws Exception {
        executor.setSkipPropagationService(null);
        int totalItems = 2;
        Set<Integer> routedItemIndices = new HashSet<>();

        persistSkippedItemRecordsWithCascade(
            execution, applyNode, routedItemIndices, totalItems, 16, "trigger:cron");

        // Pass 1 still runs - the SKIPPED step_data rows for the apply_X node itself
        // are load-bearing and must not be dropped just because the cascade service
        // is unwired in a test profile.
        verify(nodeCompletionService, times(totalItems))
            .emitNodeSkippedForItem(any(), any(), any(Integer.class), any(), anyInt(), any());
        // Pass 2 is silently skipped - no NPE, no log spam beyond the existing debug line.
    }

    @Test
    @DisplayName("cascade failure on one item does not strand subsequent items' Pass 1 writes (defense in depth)")
    void cascadeFailureDoesNotStrandOtherItems() throws Exception {
        int totalItems = 3;
        Set<Integer> routedItemIndices = new HashSet<>();
        int epoch = 16;
        String triggerId = "trigger:cron";

        // Make the cascade throw on item index 1 only - items 0 and 2 must still
        // get both Pass 1 and Pass 2 invoked.
        org.mockito.Mockito.doThrow(new RuntimeException("cascade boom"))
            .when(skipPropagationService).cascadeFailureToSuccessors(
                any(), any(), eq(1), anyInt(), any(), anyBoolean(), any());

        persistSkippedItemRecordsWithCascade(
            execution, applyNode, routedItemIndices, totalItems, epoch, triggerId);

        // Pin item indices 0 and 2 explicitly so a regression that retries item 1
        // three times (or stops at item 1) is caught - verifying just times(3) on
        // anyInt() would silently pass that case.
        verify(nodeCompletionService).emitNodeSkippedForItem(
            execution, applyNode, 0, "Not routed to this branch", epoch, triggerId);
        verify(nodeCompletionService).emitNodeSkippedForItem(
            execution, applyNode, 1, "Not routed to this branch", epoch, triggerId);
        verify(nodeCompletionService).emitNodeSkippedForItem(
            execution, applyNode, 2, "Not routed to this branch", epoch, triggerId);
        verify(skipPropagationService).cascadeFailureToSuccessors(
            execution, applyNode, 0, epoch, triggerId, true, "split_unrouted");
        verify(skipPropagationService).cascadeFailureToSuccessors(
            execution, applyNode, 1, epoch, triggerId, true, "split_unrouted");
        verify(skipPropagationService).cascadeFailureToSuccessors(
            execution, applyNode, 2, epoch, triggerId, true, "split_unrouted");
    }

    @Test
    @DisplayName("default split skipped persistence suppresses descendant cascade for mixed-routing paths")
    void defaultPersistSkippedItemRecordsSuppressesDescendantCascade() throws Exception {
        ExecutionNode recordNode = org.mockito.Mockito.mock(ExecutionNode.class);
        when(recordNode.getNodeId()).thenReturn("mcp:record_urgent");
        when(recordNode.isMergeNode()).thenReturn(false);
        when(recordNode.isImplicitMerge()).thenReturn(false);
        when(applyNode.getSuccessors()).thenReturn(java.util.List.of(recordNode));

        persistSkippedItemRecords.invoke(executor,
            execution, applyNode, Set.of(0), 2, 16, "trigger:cron");

        verify(nodeCompletionService).emitNodeSkippedForItem(
            execution, applyNode, 1, "Not routed to this branch", 16, "trigger:cron");
        verify(nodeCompletionService).batchIncrementSkippedCountsAndEmit(
            execution, "mcp:apply_urgent", "apply_urgent", 1, 16, "trigger:cron");
        verify(skipPropagationService, never())
            .cascadeFailureToSuccessors(any(), any(), any(Integer.class), anyInt(), any(), anyBoolean(), any());
        verify(nodeCompletionService, never()).batchIncrementSkippedCountsAndEmit(
            any(), eq("mcp:record_urgent"), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("all items routed - early-return short-circuits, no Pass 1 or Pass 2 fires (audit C gap)")
    void allItemsRoutedNoCascadeFires() throws Exception {
        // skippedCount = totalItems - routedItemIndices.size() = 2 - 2 = 0 → early return.
        int totalItems = 2;
        Set<Integer> routedItemIndices = Set.of(0, 1);

        persistSkippedItemRecords.invoke(executor,
            execution, applyNode, routedItemIndices, totalItems, 16, "trigger:cron");

        // Neither Pass 1 nor Pass 2 fires when no items need skipping. Without this
        // guard, the chosen branch (apply_reseaux COMPLETED for all items) would
        // spuriously write SKIPPED rows for itself and cascade-skip its own
        // downstream record_reseaux - silently corrupting the working path.
        verify(nodeCompletionService, never())
            .emitNodeSkippedForItem(any(), any(), any(Integer.class), any(), anyInt(), any());
        verify(skipPropagationService, never())
            .cascadeFailureToSuccessors(any(), any(), any(Integer.class), anyInt(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("source tag is 'split_unrouted' (pinned for telemetry - distinguishes from agent-failure cascades)")
    void cascadeUsesSplitUnroutedSourceTag() throws Exception {
        Set<Integer> routedItemIndices = new HashSet<>();

        persistSkippedItemRecordsWithCascade(
            execution, applyNode, routedItemIndices, 1, 16, "trigger:cron");

        // The Micrometer counter orchestrator.skip.cascade.descendants is tagged with
        // source={sync|async|split_unrouted}. If the source value drifts (e.g. someone
        // changes it back to "sync"), prod dashboards lose visibility into split-unrouted
        // cascade volume - alerting on regressions becomes impossible. Pin the value.
        verify(skipPropagationService).cascadeFailureToSuccessors(
            execution, applyNode, 0, 16, "trigger:cron", true, "split_unrouted");
    }

    @Test
    @DisplayName("branch node NodeCounts incremented + aggregated event emitted (2026-05-21 frontend no-badge bug)")
    void branchNodeBatchIncrementAndEmitFires() throws Exception {
        // 2 emails both routed to "reseaux" → apply_urgent has 2 skipped items.
        // Pre-fix, only batchIncrementSkippedCounts (DB-only) was called - the
        // frontend received N per-item events all carrying statusCounts.SKIPPED=0
        // (read before the batch increment) so NodeStatusBadge rendered nothing.
        // The new batchIncrementSkippedCountsAndEmit increments AND emits a fresh
        // aggregated step.skipped event with the post-increment counts so the
        // badge appears.
        int totalItems = 2;
        Set<Integer> routedItemIndices = new HashSet<>(); // 0 routed
        int epoch = 16;
        String triggerId = "trigger:cron";

        persistSkippedItemRecords.invoke(executor,
            execution, applyNode, routedItemIndices, totalItems, epoch, triggerId);

        // Branch node: ONE post-loop emit with the total skippedCount (NOT the
        // pre-fix DB-only batchIncrementSkippedCounts).
        verify(nodeCompletionService).batchIncrementSkippedCountsAndEmit(
            eq(execution), eq("mcp:apply_urgent"), eq("apply_urgent"),
            eq(totalItems), eq(epoch), eq(triggerId));

        // Guard: the legacy DB-only method must NOT also be called - a regression
        // that calls BOTH would double-increment the SKIPPED counter.
        verify(nodeCompletionService, never())
            .batchIncrementSkippedCounts(any(), any(), anyInt());
    }

    @Test
    @DisplayName("BFS walks 2+ levels deep - Gmail Auto-Labeler shape apply→record→build (non-merge chain) all get +emit (audit MEDIUM follow-up to 9672b1bb8)")
    void cascadeDescendantsTwoLevelDeep() throws Exception {
        // Real Gmail Auto-Labeler shape per branch: apply → record → build → send → collect_merge.
        // The 1-deep test below only proves walk-1. A regression where the BFS only
        // descends one level (e.g. wrong placement of visited.add) would silently
        // re-introduce the no-badge bug for build/send while passing the 1-deep test.
        ExecutionNode buildNode = org.mockito.Mockito.mock(ExecutionNode.class);
        when(buildNode.getNodeId()).thenReturn("mcp:build_urgent");
        when(buildNode.isMergeNode()).thenReturn(false);
        when(buildNode.isImplicitMerge()).thenReturn(false);

        ExecutionNode sendNode = org.mockito.Mockito.mock(ExecutionNode.class);
        when(sendNode.getNodeId()).thenReturn("mcp:send_urgent");
        when(sendNode.isMergeNode()).thenReturn(false);
        when(sendNode.isImplicitMerge()).thenReturn(false);

        ExecutionNode mergeNode = org.mockito.Mockito.mock(ExecutionNode.class);
        when(mergeNode.getNodeId()).thenReturn("core:collect_urgents");
        when(mergeNode.isMergeNode()).thenReturn(true);

        ExecutionNode recordNode = org.mockito.Mockito.mock(ExecutionNode.class);
        when(recordNode.getNodeId()).thenReturn("mcp:record_urgent");
        when(recordNode.isMergeNode()).thenReturn(false);
        when(recordNode.isImplicitMerge()).thenReturn(false);

        // Chain: apply → record → build → send → collect_merge
        when(sendNode.getSuccessors()).thenReturn(java.util.List.of(mergeNode));
        when(buildNode.getSuccessors()).thenReturn(java.util.List.of(sendNode));
        when(recordNode.getSuccessors()).thenReturn(java.util.List.of(buildNode));
        when(applyNode.getSuccessors()).thenReturn(java.util.List.of(recordNode));

        persistSkippedItemRecordsWithCascade(
            execution, applyNode, new HashSet<>(), 2, 16, "trigger:cron");

        // Branch + ALL THREE non-merge descendants get +emit. Verify each by node ID.
        verify(nodeCompletionService).batchIncrementSkippedCountsAndEmit(
            eq(execution), eq("mcp:apply_urgent"), eq("apply_urgent"), eq(2), eq(16), eq("trigger:cron"));
        verify(nodeCompletionService).batchIncrementSkippedCountsAndEmit(
            eq(execution), eq("mcp:record_urgent"), eq("record_urgent"), eq(2), eq(16), eq("trigger:cron"));
        verify(nodeCompletionService).batchIncrementSkippedCountsAndEmit(
            eq(execution), eq("mcp:build_urgent"), eq("build_urgent"), eq(2), eq(16), eq("trigger:cron"));
        verify(nodeCompletionService).batchIncrementSkippedCountsAndEmit(
            eq(execution), eq("mcp:send_urgent"), eq("send_urgent"), eq(2), eq(16), eq("trigger:cron"));

        // The merge node (collect_urgents) is INTENTIONALLY skipped - mirrors
        // V2SkipPropagationService:655 policy. Merges have convergence semantics
        // and post-merge descendants are handled by the global skip path.
        verify(nodeCompletionService, never()).batchIncrementSkippedCountsAndEmit(
            any(), eq("core:collect_urgents"), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("non-merge descendants also get batchIncrement+emit per skippedCount - closes the missing-counts gap on record_*/collect_*/build_*/send_*")
    void cascadeDescendantsAlsoGetBatchIncrementAndEmit() throws Exception {
        // Wire apply_urgent → record_urgent (non-merge) → collect (implicit merge,
        // must NOT be traversed). The descendant walk must increment record_urgent
        // but stop at the merge boundary.
        ExecutionNode recordNode = org.mockito.Mockito.mock(ExecutionNode.class);
        when(recordNode.getNodeId()).thenReturn("mcp:record_urgent");
        when(recordNode.isMergeNode()).thenReturn(false);
        when(recordNode.isImplicitMerge()).thenReturn(false);
        ExecutionNode collectNode = org.mockito.Mockito.mock(ExecutionNode.class);
        when(collectNode.getNodeId()).thenReturn("core:collect");
        when(collectNode.isMergeNode()).thenReturn(true);
        when(recordNode.getSuccessors()).thenReturn(java.util.List.of(collectNode));
        when(applyNode.getSuccessors()).thenReturn(java.util.List.of(recordNode));

        int totalItems = 2;
        Set<Integer> routedItemIndices = new HashSet<>();
        int epoch = 16;
        String triggerId = "trigger:cron";

        persistSkippedItemRecordsWithCascade(
            execution, applyNode, routedItemIndices, totalItems, epoch, triggerId);

        // Branch node + record_urgent both get the +emit call (same skippedCount).
        verify(nodeCompletionService).batchIncrementSkippedCountsAndEmit(
            eq(execution), eq("mcp:apply_urgent"), eq("apply_urgent"),
            eq(totalItems), eq(epoch), eq(triggerId));
        verify(nodeCompletionService).batchIncrementSkippedCountsAndEmit(
            eq(execution), eq("mcp:record_urgent"), eq("record_urgent"),
            eq(totalItems), eq(epoch), eq(triggerId));

        // The merge node (collect) is INTENTIONALLY skipped - mirrors the
        // V2SkipPropagationService policy at line 655: merges have convergence
        // semantics and their counts are NOT mirrored from upstream skips.
        // A regression that walks through merges would double-count.
        verify(nodeCompletionService, never()).batchIncrementSkippedCountsAndEmit(
            any(), eq("core:collect"), any(), anyInt(), anyInt(), any());
    }

    private void persistSkippedItemRecordsWithCascade(
            WorkflowExecution execution,
            ExecutionNode node,
            Set<Integer> routedItemIndices,
            int totalItems,
            int epoch,
            String triggerId) throws Exception {
        persistSkippedItemRecordsWithOptions.invoke(executor,
            execution, node, routedItemIndices, totalItems, epoch, triggerId, true, true);
    }

}

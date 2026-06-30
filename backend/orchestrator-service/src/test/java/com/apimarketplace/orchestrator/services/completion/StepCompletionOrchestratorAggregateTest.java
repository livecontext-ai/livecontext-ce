package com.apimarketplace.orchestrator.services.completion;

import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.merge.MergeIntegrationService;
import com.apimarketplace.orchestrator.services.persistence.StepPersistenceResult;
import com.apimarketplace.orchestrator.services.persistence.WorkflowEntityResolverService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Phase 2.E regression tests - recordSplitAggregateIfMissing behavior.
 *
 * Decision matrix tested:
 * - 0 completed, 0 failed → no-op (no items persisted yet, not our turn)
 * - ≥1 completed, 0 failed → markNodeCompletedEpochOnly only
 * - 0 completed, ≥1 failed → markNodeFailedEpochOnly only
 * - ≥1 completed, ≥1 failed → markNodeCompletedEpochOnly + markNodePartialFailure
 *
 * Idempotency: if the node is already in completedNodeIds or failedNodeIds for this
 * epoch, the method returns early. Tests cover this via prepared StateSnapshot.
 *
 * Bug this defends against:
 * Run run_<id> (2026-04-29) - 4 per-item failures fired
 * markNodeFailed and poisoned EpochState.failedNodeIds before the 26 successful items
 * could land. Phase 2.E suppresses the per-item global mark, then this method writes
 * the aggregate ONCE at barrier seal.
 *
 * Run run_<id> (2026-05-02) - Gmail Auto-Labeler observed
 * NodeCounts.completed=5 for 4 actual items because the seal called markNodeCompleted
 * (which re-incremented NodeCounts on top of per-item incrementNodeCountsOnly).
 * Fix: route the seal through markNodeCompletedEpochOnly / markNodeFailedEpochOnly
 * so EpochState updates without touching NodeCounts.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepCompletionOrchestrator.recordSplitAggregateIfMissing")
class StepCompletionOrchestratorAggregateTest {

    @Mock private WorkflowPersistenceService persistenceService;
    @Mock private WorkflowEventPublisher eventPublisher;
    @Mock private MergeIntegrationService mergeIntegrationService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private WorkflowEntityResolverService entityResolverService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private WorkflowMetrics workflowMetrics;
    @Mock private WorkflowStepDataRepository stepDataRepository;

    private StepCompletionOrchestrator orchestrator;

    private static final String RUN_ID = "run-aggregate-test";
    private static final String TRIGGER_ID = "trigger:cron";
    private static final String NODE_KEY = "agent:classify";
    private static final int EPOCH = 5;

    @BeforeEach
    void setUp() {
        orchestrator = new StepCompletionOrchestrator(
            persistenceService, eventPublisher, mergeIntegrationService, stateSnapshotService,
            workflowEpochService, entityResolverService, creditClient, workflowMetrics,
            stepDataRepository);
    }

    @Test
    @DisplayName("recordSplitAggregateIfMissing - no items persisted yet → no-op")
    void recordSplitAggregateIfMissingNoOpsWhenNoStepDataYet() {
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(emptySnapshot());
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "COMPLETED")).thenReturn(0L);
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "FAILED")).thenReturn(0L);

        orchestrator.recordSplitAggregateIfMissing(RUN_ID, TRIGGER_ID, NODE_KEY, EPOCH);

        verify(stateSnapshotService, never()).markNodeCompletedEpochOnly(eq(RUN_ID), anyString(), anyInt(), anyString());
        verify(stateSnapshotService, never()).markNodeFailedEpochOnly(eq(RUN_ID), anyString(), anyInt(), anyString());
        verify(stateSnapshotService, never()).markNodePartialFailure(eq(RUN_ID), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("recordSplitAggregateIfMissing - all completed → markNodeCompletedEpochOnly only")
    void recordSplitAggregateIfMissingAllSuccessMarksCompleted() {
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(emptySnapshot());
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "COMPLETED")).thenReturn(30L);
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "FAILED")).thenReturn(0L);

        orchestrator.recordSplitAggregateIfMissing(RUN_ID, TRIGGER_ID, NODE_KEY, EPOCH);

        verify(stateSnapshotService).markNodeCompletedEpochOnly(RUN_ID, TRIGGER_ID, EPOCH, NODE_KEY);
        // The seal MUST NOT call the regular markNodeCompleted (which would re-increment
        // NodeCounts on top of the per-item incrementNodeCountsOnly calls - the
        // 2026-05-02 prod bug).
        verify(stateSnapshotService, never()).markNodeCompleted(eq(RUN_ID), anyString(), anyInt(), anyString());
        verify(stateSnapshotService, never()).markNodeFailedEpochOnly(eq(RUN_ID), anyString(), anyInt(), anyString());
        verify(stateSnapshotService, never()).markNodePartialFailure(eq(RUN_ID), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("recordSplitAggregateIfMissing - all failed → markNodeFailedEpochOnly only")
    void recordSplitAggregateIfMissingAllFailedMarksFailed() {
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(emptySnapshot());
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "COMPLETED")).thenReturn(0L);
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "FAILED")).thenReturn(30L);

        orchestrator.recordSplitAggregateIfMissing(RUN_ID, TRIGGER_ID, NODE_KEY, EPOCH);

        verify(stateSnapshotService).markNodeFailedEpochOnly(RUN_ID, TRIGGER_ID, EPOCH, NODE_KEY);
        verify(stateSnapshotService, never()).markNodeFailed(eq(RUN_ID), anyString(), anyInt(), anyString());
        verify(stateSnapshotService, never()).markNodeCompletedEpochOnly(eq(RUN_ID), anyString(), anyInt(), anyString());
        verify(stateSnapshotService, never()).markNodePartialFailure(eq(RUN_ID), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("recordSplitAggregateIfMissing - mixed success+failure → markNodeCompletedEpochOnly + markNodePartialFailure")
    void recordSplitAggregateIfMissingMixedMarksCompletedAndPartialFailure() {
        // The exact prod scenario: 26 completed, 4 failed (Google 429s)
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(emptySnapshot());
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "COMPLETED")).thenReturn(26L);
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "FAILED")).thenReturn(4L);

        orchestrator.recordSplitAggregateIfMissing(RUN_ID, TRIGGER_ID, NODE_KEY, EPOCH);

        verify(stateSnapshotService).markNodeCompletedEpochOnly(RUN_ID, TRIGGER_ID, EPOCH, NODE_KEY);
        verify(stateSnapshotService).markNodePartialFailure(RUN_ID, TRIGGER_ID, EPOCH, NODE_KEY);
        verify(stateSnapshotService, never()).markNodeCompleted(eq(RUN_ID), anyString(), anyInt(), anyString());
        verify(stateSnapshotService, never()).markNodeFailedEpochOnly(eq(RUN_ID), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("recordSplitAggregateIfMissing - already completed in EpochState → no-op (idempotent)")
    void recordSplitAggregateIfMissingIdempotentOnExistingCompletion() {
        // Snapshot already has the node in completedNodeIds - recovery scanner replay
        StateSnapshot snapshot = snapshotWithCompletedNode();
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

        orchestrator.recordSplitAggregateIfMissing(RUN_ID, TRIGGER_ID, NODE_KEY, EPOCH);

        verify(stateSnapshotService, never()).markNodeCompletedEpochOnly(eq(RUN_ID), anyString(), anyInt(), anyString());
        verify(stateSnapshotService, never()).markNodeFailedEpochOnly(eq(RUN_ID), anyString(), anyInt(), anyString());
        verify(stateSnapshotService, never()).markNodePartialFailure(eq(RUN_ID), anyString(), anyInt(), anyString());
        // Repo not even queried - early return on idempotency check
        verify(stepDataRepository, never()).countByRunIdAndNormalizedKeyAndEpochAndStatus(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("Phase 2.F: null triggerId resolves via snapshot.getDefaultTriggerId for recovery scan path")
    void recordSplitAggregateIfMissingResolvesTriggerIdFromSnapshotWhenNull() {
        // Phase 2.F (2026-04-29) - recovery scanner passes null triggerId because
        // step_data doesn't carry it. We resolve from the snapshot's default DAG.
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(emptySnapshot());
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "COMPLETED")).thenReturn(2L);
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "FAILED")).thenReturn(1L);

        orchestrator.recordSplitAggregateIfMissing(RUN_ID, null, NODE_KEY, EPOCH);

        // Resolved triggerId is the snapshot's default - emptySnapshot's
        // openEpochForDag(TRIGGER_ID, EPOCH) put TRIGGER_ID as the only DAG, so
        // getDefaultTriggerId returns TRIGGER_ID. Aggregate fires (mixed → completed + partial).
        verify(stateSnapshotService).markNodeCompletedEpochOnly(RUN_ID, TRIGGER_ID, EPOCH, NODE_KEY);
        verify(stateSnapshotService).markNodePartialFailure(RUN_ID, TRIGGER_ID, EPOCH, NODE_KEY);
    }

    @Test
    @DisplayName("F1 bundle 2 read-side cap: long normalizedKey is capped before the count query (symmetric with writer)")
    void recordSplitAggregateIfMissingCapsLongNormalizedKey() {
        // Writer side (entity setter + @PrePersist) caps the persisted
        // normalized_key via DiagnosticFieldLimits.capWithCollisionHash. If
        // this seal queried with the RAW key, it would get count=0 against
        // rows that ARE present under the capped key → split nodes silently
        // stay PENDING forever. The wrap at StepCompletionOrchestrator:837-848
        // closes the symmetry.
        String rawKey = "agent:classify_" + "x".repeat(800);
        String cappedKey = com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits
                .capWithCollisionHash(rawKey,
                        com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits.NORMALIZED_KEY_MAX);
        // Pre-fix: the query is issued with rawKey → mock returns 0/0 → no-op.
        // Post-fix: the query is issued with cappedKey → mock returns 3/0 →
        // seal fires markNodeCompletedEpochOnly with the cappedKey.
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(emptySnapshot());
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, cappedKey, EPOCH, "COMPLETED")).thenReturn(3L);
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, cappedKey, EPOCH, "FAILED")).thenReturn(0L);

        orchestrator.recordSplitAggregateIfMissing(RUN_ID, TRIGGER_ID, rawKey, EPOCH);

        // Seal fires because the cappedKey matched 3 rows; raw key would have returned 0.
        verify(stateSnapshotService).markNodeCompletedEpochOnly(RUN_ID, TRIGGER_ID, EPOCH, cappedKey);
    }

    @Test
    @DisplayName("F1 bundle 2 read-side cap (FAILED branch): long normalizedKey threads cappedKey through markNodeFailedEpochOnly")
    void recordSplitAggregateIfMissingCapsLongNormalizedKeyFailedBranch() {
        String rawKey = "agent:classify_" + "y".repeat(800);
        String cappedKey = com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits
                .capWithCollisionHash(rawKey,
                        com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits.NORMALIZED_KEY_MAX);
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(emptySnapshot());
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, cappedKey, EPOCH, "COMPLETED")).thenReturn(0L);
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, cappedKey, EPOCH, "FAILED")).thenReturn(5L);

        orchestrator.recordSplitAggregateIfMissing(RUN_ID, TRIGGER_ID, rawKey, EPOCH);

        verify(stateSnapshotService).markNodeFailedEpochOnly(RUN_ID, TRIGGER_ID, EPOCH, cappedKey);
        verify(stateSnapshotService, never()).markNodeCompletedEpochOnly(eq(RUN_ID), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("F1 bundle 2 read-side cap (mixed branch): cappedKey threads through markNodeCompletedEpochOnly + markNodePartialFailure")
    void recordSplitAggregateIfMissingCapsLongNormalizedKeyMixedBranch() {
        String rawKey = "agent:classify_" + "z".repeat(800);
        String cappedKey = com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits
                .capWithCollisionHash(rawKey,
                        com.apimarketplace.orchestrator.domain.workflow.DiagnosticFieldLimits.NORMALIZED_KEY_MAX);
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(emptySnapshot());
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, cappedKey, EPOCH, "COMPLETED")).thenReturn(2L);
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, cappedKey, EPOCH, "FAILED")).thenReturn(1L);

        orchestrator.recordSplitAggregateIfMissing(RUN_ID, TRIGGER_ID, rawKey, EPOCH);

        // Mixed path writes BOTH markers - must thread cappedKey through both
        // so the symmetry holds against the persisted rows AND the
        // completedNodeIds/failedNodeIds sets.
        verify(stateSnapshotService).markNodeCompletedEpochOnly(RUN_ID, TRIGGER_ID, EPOCH, cappedKey);
        verify(stateSnapshotService).markNodePartialFailure(RUN_ID, TRIGGER_ID, EPOCH, cappedKey);
    }

    @Test
    @DisplayName("Phase 2.F: null triggerId AND no resolvable default → no-op")
    void recordSplitAggregateIfMissingNoOpsWhenNoTriggerResolvable() {
        // No snapshot at all - getDefaultTriggerId returns null. Method must short-circuit.
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(null);

        orchestrator.recordSplitAggregateIfMissing(RUN_ID, null, NODE_KEY, EPOCH);

        verify(stateSnapshotService, never()).markNodeCompletedEpochOnly(eq(RUN_ID), anyString(), anyInt(), anyString());
        verify(stateSnapshotService, never()).markNodeFailedEpochOnly(eq(RUN_ID), anyString(), anyInt(), anyString());
        verify(stepDataRepository, never()).countByRunIdAndNormalizedKeyAndEpochAndStatus(anyString(), anyString(), anyInt(), anyString());
    }

    /**
     * Regression - runId run_<id> (2026-05-02): Gmail Auto-Labeler
     * reported {@code NodeCounts.completed=5} for 4 actual classify executions.
     * Asserts: the seal calls the EpochOnly variant exactly once (not the global
     * mark, which would re-increment {@code NodeCounts}).
     */
    @Test
    @DisplayName("Regression run_<id> - seal does NOT call markNodeCompleted (would re-increment NodeCounts)")
    void sealUsesEpochOnlyVariantNotGlobalMark() {
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(emptySnapshot());
        // 4 successful classify items - exactly the production scenario.
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "COMPLETED")).thenReturn(4L);
        when(stepDataRepository.countByRunIdAndNormalizedKeyAndEpochAndStatus(RUN_ID, NODE_KEY, EPOCH, "FAILED")).thenReturn(0L);

        orchestrator.recordSplitAggregateIfMissing(RUN_ID, TRIGGER_ID, NODE_KEY, EPOCH);

        verify(stateSnapshotService).markNodeCompletedEpochOnly(RUN_ID, TRIGGER_ID, EPOCH, NODE_KEY);
        verify(stateSnapshotService, never()).markNodeCompleted(eq(RUN_ID), anyString(), anyInt(), anyString());
    }

    // ───────────────────────── helpers ─────────────────────────

    private StateSnapshot emptySnapshot() {
        // Build an EpochState with no markers so the idempotency check falls through
        // to the count query path.
        return StateSnapshot.empty()
            .openEpochForDag(TRIGGER_ID, EPOCH);
    }

    private StateSnapshot snapshotWithCompletedNode() {
        return StateSnapshot.empty()
            .openEpochForDag(TRIGGER_ID, EPOCH)
            .markNodeCompleted(TRIGGER_ID, NODE_KEY, EPOCH);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // batchIncrementSkippedCountsAndEmit - 2026-05-21 audit follow-up (test depth)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Audit MEDIUM finding (commit 9672b1bb8): the wrapper tests at
     * {@code SplitAwareSkipCascadeUnroutedTest.branchNodeBatchIncrementAndEmitFires}
     * verify only the NodeCompletionService wrapper - the underlying
     * {@code eventPublisher.emitStep} call inside
     * {@link StepCompletionOrchestrator#batchIncrementSkippedCountsAndEmit} is not
     * asserted. A regression that drops the emit (the exact bug being fixed) would
     * silently re-introduce the no-badge bug with tests still green. This test pins
     * the emit-side contract: increment runs first, then emitStep fires with
     * aggregated=true and statusCounts derived from the POST-increment counts.
     */
    @Test
    @DisplayName("batchIncrementSkippedCountsAndEmit - emit-side regression: aggregated step.skipped event carries POST-increment counts (closes audit-side MEDIUM finding from 9672b1bb8)")
    void batchIncrementSkippedCountsAndEmitFiresEmitWithPostIncrementCounts() {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        when(execution.getRunId()).thenReturn(RUN_ID);

        // Pre-increment: 0. Post-increment: 3 skipped (regression guard - must
        // be the value returned by incrementNodeCountsOnly, not the pre-read).
        StateSnapshot.NodeCounts postIncrement = new StateSnapshot.NodeCounts(0, 0, 0, 3, 0L, 0L, 0L);
        when(stateSnapshotService.incrementNodeCountsOnly(RUN_ID, "mcp:apply_finance", "SKIPPED", 3))
            .thenReturn(postIncrement);

        StateSnapshot.NodeCounts returned = orchestrator.batchIncrementSkippedCountsAndEmit(
            execution, "mcp:apply_finance", "apply_finance", 3, 16, "trigger:cron");

        // 1. Return value: the post-increment counts (not pre).
        assertThat(returned.skipped()).isEqualTo(3);

        // 2. Increment fired with the right (nodeId, status, count).
        verify(stateSnapshotService).incrementNodeCountsOnly(RUN_ID, "mcp:apply_finance", "SKIPPED", 3);

        // 3. emitStep fired with the aggregated payload + post-increment counts.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).emitStep(eq(RUN_ID), eq("mcp:apply_finance"),
            eventCaptor.capture(), eq(StepLifecycle.SKIPPED));
        Map<String, Object> event = eventCaptor.getValue();
        assertThat(event).containsEntry("type", "step_skipped");
        assertThat(event).containsEntry("nodeId", "mcp:apply_finance");
        assertThat(event).containsEntry("aggregated", true);
        assertThat(event).containsEntry("status", "SKIPPED");
        assertThat(event).containsEntry("epoch", 16);
        assertThat(event).containsEntry("triggerId", "trigger:cron");

        @SuppressWarnings("unchecked")
        Map<String, Object> statusCounts = (Map<String, Object>) event.get("statusCounts");
        assertThat(statusCounts).isNotNull();
        // Regression guard: SKIPPED=3 (POST-increment). Pre-fix this was 0 (snapshot
        // read BEFORE increment) and the frontend NodeStatusBadge rendered nothing.
        assertThat(statusCounts).containsEntry("skipped", 3);
        assertThat(statusCounts).containsEntry("processed", 3);
    }

    /**
     * Companion to the test above - verifies the increment ORDER (DB write before
     * emit). A regression that emits BEFORE incrementing would re-introduce the
     * stale-counts bug.
     */
    @Test
    @DisplayName("batchIncrementSkippedCountsAndEmit - increment is called BEFORE emitStep (ordering regression guard)")
    void batchIncrementSkippedCountsAndEmitOrderIncrementThenEmit() {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        when(execution.getRunId()).thenReturn(RUN_ID);
        when(stateSnapshotService.incrementNodeCountsOnly(eq(RUN_ID), anyString(), eq("SKIPPED"), anyInt()))
            .thenReturn(new StateSnapshot.NodeCounts(0, 0, 0, 2, 0L, 0L, 0L));

        orchestrator.batchIncrementSkippedCountsAndEmit(
            execution, "mcp:apply_x", "apply_x", 2, 7, "trigger:t");

        InOrder ordering = inOrder(stateSnapshotService, eventPublisher);
        ordering.verify(stateSnapshotService).incrementNodeCountsOnly(RUN_ID, "mcp:apply_x", "SKIPPED", 2);
        ordering.verify(eventPublisher).emitStep(eq(RUN_ID), eq("mcp:apply_x"), anyMap(), eq(StepLifecycle.SKIPPED));
    }

    @Test
    @DisplayName("regression: no-items-routed split emits deferred aggregated SKIPPED event after node-level count reaches final value")
    void completeSkippedWithDeferredAggregateEmitsFinalAggregatedEventLast() {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        when(execution.getRunId()).thenReturn(RUN_ID);
        when(persistenceService.recordStep(
            eq(execution), eq("core:apply_ops"), eq("apply_ops"), eq("core:apply_ops"),
            any(StepExecutionResult.class), eq(1), eq(TRIGGER_ID)))
            .thenReturn(StepPersistenceResult.success(UUID.randomUUID()));
        when(stateSnapshotService.recordNodeCompletionAndGetCounts(
            RUN_ID, "core:apply_ops", "SKIPPED", TRIGGER_ID, 1, 0L))
            .thenReturn(new StateSnapshot.NodeCounts(0, 0, 0, 3, 0L, 0L, 0L));

        StepExecutionResult result = new StepExecutionResult(
            "core:apply_ops",
            NodeStatus.SKIPPED,
            "No items routed to this branch",
            Map.of(
                "skip_reason", "No items routed to this branch",
                ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT, true
            ),
            0L,
            null
        );
        StepCompletionContext ctx = StepCompletionContext.of(
            execution, "core:apply_ops", "apply_ops", result, 0, 0, 1);

        orchestrator.complete(ctx, TRIGGER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher, times(2)).emitStep(
            eq(RUN_ID), eq("core:apply_ops"), eventCaptor.capture(), eq(StepLifecycle.SKIPPED));

        Map<String, Object> normalEvent = eventCaptor.getAllValues().get(0);
        Map<String, Object> aggregateEvent = eventCaptor.getAllValues().get(1);
        assertThat(normalEvent).doesNotContainEntry("aggregated", true);
        assertThat((Map<String, Object>) normalEvent.get("output"))
            .doesNotContainKey(ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT);
        assertThat(aggregateEvent).containsEntry("aggregated", true);
        assertThat(aggregateEvent).containsEntry("skipReason", "No items routed to this branch");
        assertThat(aggregateEvent).containsEntry("epoch", 1);
        assertThat(aggregateEvent).containsEntry("triggerId", TRIGGER_ID);

        ArgumentCaptor<StepExecutionResult> persistedResult = ArgumentCaptor.forClass(StepExecutionResult.class);
        verify(persistenceService).recordStep(
            eq(execution), eq("core:apply_ops"), eq("apply_ops"), eq("core:apply_ops"),
            persistedResult.capture(), eq(1), eq(TRIGGER_ID));
        assertThat(persistedResult.getValue().output())
            .doesNotContainKey(ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT);

        @SuppressWarnings("unchecked")
        Map<String, Object> statusCounts = (Map<String, Object>) aggregateEvent.get("statusCounts");
        assertThat(statusCounts).containsEntry("skipped", 3);
        assertThat(statusCounts).containsEntry("processed", 3);
    }
}

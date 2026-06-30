package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RedisUnavailableException;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2.1.4 - fail-CLOSED Redis gate inside {@code closeEpochIfCompleteForSbs}.
 *
 * <p>The primitive {@code RunningNodeTracker.getRunningCountsOrThrow} is unit-tested
 * in {@code RunningNodeTrackerTest}. This class exercises the INTEGRATION SEAM:
 * how {@code ReusableTriggerService} consumes the fail-closed signal in the
 * deferred-reset epoch close decision.
 *
 * <p>Three contracts pinned (per design rev12 §3.4):
 * <ol>
 *   <li>JSONB primary check fires first; the Redis gate is only consulted when
 *       JSONB shows no running.</li>
 *   <li>Redis returns non-empty → defer epoch close (treat as "still running").</li>
 *   <li>Redis throws {@link RedisUnavailableException} → defer epoch close
 *       (fail-closed; do NOT swallow and proceed to close).</li>
 * </ol>
 *
 * <p>Without these tests, a regression that drops the {@code try/catch} around
 * {@code getRunningCountsOrThrow} would silently let exceptions propagate (or
 * worse, "fix" them by treating Redis failure as empty), breaking the bounded
 * acceptance contract from rev12 §3.9 + §3.4.1.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReusableTriggerService - closeEpochIfCompleteForSbs fail-CLOSED Redis gate (P2.1.4)")
class ReusableTriggerServiceFailClosedGateTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private TriggerEpochManager epochManager;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private WorkflowExecutionService executionService;
    @Mock private com.apimarketplace.orchestrator.services.TriggerResolverService triggerResolverService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private EpochConcurrencyLimiter epochConcurrencyLimiter;
    @Mock private com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue executionQueueService;
    @Mock private UnifiedSignalService unifiedSignalService;
    @Mock private SnapshotService snapshotService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private CreditBudgetService creditBudgetService;
    @Mock private com.apimarketplace.orchestrator.services.resume.WorkflowResumeService resumeService;
    @Mock private RunningNodeTracker runningNodeTracker;

    private ReusableTriggerService service;

    private static final String RUN_ID = "run-gate-1";
    private static final String TRIGGER_ID = "trigger:my_webhook";
    private static final int EPOCH = 1;

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                runRepository,
                mock(WorkflowRepository.class),
                mock(WorkflowPlanVersionRepository.class),
                epochManager, streamingService,
                executionService, triggerResolverService, stateSnapshotService,
                epochConcurrencyLimiter, executionQueueService, creditClient, creditBudgetService);
        ReflectionTestUtils.setField(service, "unifiedSignalService", unifiedSignalService);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "resumeService", resumeService);
        ReflectionTestUtils.setField(service, "self", service);
        // P2.1.4: the new field-injected, optional Redis tracker
        ReflectionTestUtils.setField(service, "runningNodeTracker", runningNodeTracker);
    }

    private StateSnapshot snapshotWithEmptyRunning() {
        // JSONB epoch with no ready/running/awaiting - JSONB primary check passes,
        // gate falls through to the Redis secondary.
        EpochState epochState = new EpochState(
                Set.of(TRIGGER_ID, "mcp:step1"), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), Map.of(), Instant.now());
        Map<Integer, EpochState> epochs = new HashMap<>();
        epochs.put(EPOCH, epochState);
        DagState dag = new DagState(EPOCH, 0, 1, epochs, Set.of(EPOCH));
        return StateSnapshot.empty().withDagState(TRIGGER_ID, dag);
    }

    private WorkflowRunEntity createRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(RunStatus.RUNNING);
        return run;
    }

    @Test
    @DisplayName("Redis non-empty → defer close (treat as 'still running'); closeEpoch NEVER called")
    void redisNonEmptyDefersClose() {
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshotWithEmptyRunning());
        when(runningNodeTracker.getRunningCountsOrThrow(RUN_ID, EPOCH))
                .thenReturn(Map.of("mcp:step1", 1));

        service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, EPOCH);

        verify(runningNodeTracker).getRunningCountsOrThrow(RUN_ID, EPOCH);
        // Critical: the close MUST NOT happen when Redis says a node is running.
        verify(stateSnapshotService, never()).closeEpoch(anyString(), anyString(), anyInt());
        verify(epochConcurrencyLimiter, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("Redis empty → close proceeds normally; gate is a no-op when both JSONB and Redis are empty")
    void redisEmptyAllowsClose() {
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshotWithEmptyRunning());
        when(runningNodeTracker.getRunningCountsOrThrow(RUN_ID, EPOCH)).thenReturn(Map.of());
        when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(createRun()));

        service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, EPOCH);

        verify(runningNodeTracker).getRunningCountsOrThrow(RUN_ID, EPOCH);
        verify(stateSnapshotService).closeEpoch(RUN_ID, TRIGGER_ID, EPOCH);
    }

    @Test
    @DisplayName("Redis throws RedisUnavailableException → defer close (fail-CLOSED); closeEpoch NEVER called")
    void redisUnavailableDefersClose() {
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshotWithEmptyRunning());
        when(runningNodeTracker.getRunningCountsOrThrow(RUN_ID, EPOCH))
                .thenThrow(new RedisUnavailableException("Redis read failed", new RuntimeException("boom")));

        service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, EPOCH);

        verify(runningNodeTracker).getRunningCountsOrThrow(RUN_ID, EPOCH);
        // CRITICAL: fail-closed contract from design §3.4 + §3.4.1. Without this,
        // a Redis hiccup would let us close prematurely while a node is mid-execution.
        verify(stateSnapshotService, never()).closeEpoch(anyString(), anyString(), anyInt());
        verify(epochConcurrencyLimiter, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("JSONB primary check non-empty → Redis gate NEVER consulted (skip the secondary read)")
    void jsonbNonEmptySkipsRedisGate() {
        // JSONB epoch with running=non-empty → primary check fires, secondary skipped.
        EpochState epochState = new EpochState(
                Set.of(TRIGGER_ID, "mcp:step1"), Set.of(), Set.of(),
                Set.of("mcp:step2"), Set.of(), Set.of(),
                Map.of(), Map.of(), Map.of(), Instant.now());
        Map<Integer, EpochState> epochs = new HashMap<>();
        epochs.put(EPOCH, epochState);
        DagState dag = new DagState(EPOCH, 0, 1, epochs, Set.of(EPOCH));
        StateSnapshot snapshot = StateSnapshot.empty().withDagState(TRIGGER_ID, dag);
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

        service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, EPOCH);

        // Redis gate NEVER consulted when JSONB already shows running.
        verify(runningNodeTracker, never()).getRunningCountsOrThrow(anyString(), anyInt());
        verify(stateSnapshotService, never()).closeEpoch(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("RunningNodeTracker bean absent (legacy unit-test wiring) → gate falls through cleanly without crash")
    void absentBeanFallsThroughCleanly() {
        ReflectionTestUtils.setField(service, "runningNodeTracker", null);
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshotWithEmptyRunning());
        when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(createRun()));

        service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, EPOCH);

        // Close proceeds (no Redis tracker → fall through).
        verify(stateSnapshotService).closeEpoch(RUN_ID, TRIGGER_ID, EPOCH);
    }

    @Test
    @DisplayName("Redis gate is consulted exactly once per call (no retry / no double-read)")
    void redisGateConsultedOnce() {
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshotWithEmptyRunning());
        when(runningNodeTracker.getRunningCountsOrThrow(RUN_ID, EPOCH)).thenReturn(Map.of());
        when(stateSnapshotService.hasAnyActiveEpoch(RUN_ID)).thenReturn(false);
        when(runRepository.findByRunIdPublicForUpdate(RUN_ID)).thenReturn(Optional.of(createRun()));

        service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, EPOCH);

        verify(runningNodeTracker, times(1)).getRunningCountsOrThrow(eq(RUN_ID), eq(EPOCH));
    }

    @Test
    @DisplayName("§3.6.1 - gate reads per-epoch key (NOT run-wide); production writer flips populate {runId}:{epoch}")
    void gateReadsPerEpochKey_notRunWide() {
        // P2.3.1 §3.6.1 positive contract.
        //
        // The 5 production writer sites (NodeCompletionService:121,
        // V2ExecutionEventService:159+225, SignalResumeService:619,
        // AgentAsyncCompletionService:364, SplitAwareNodeExecutor:466)
        // all flipped from the legacy 2-arg overload (run-wide key
        // {orchestrator:running:runId}) to the 3-arg per-epoch overload
        // ({orchestrator:running:runId:epoch}).
        //
        // The deferred-reset gate at ReusableTriggerService:1660 must read
        // from the SAME per-epoch key shape - calling the per-epoch
        // {@code getRunningCountsOrThrow(runId, epoch)} overload, NOT the
        // run-wide one. This test pins the call signature so that a future
        // refactor can't silently regress the read side back to run-wide
        // (which would observe an always-empty map under elide and close
        // the epoch while a node is mid-execution).
        //
        // Specifically: when a non-zero epoch (3) has a running node populated
        // by a flipped writer (e.g. NodeCompletionService.emitNodeStart fired
        // markRunning(runId, 3, "mcp:step1")), the gate MUST observe it under
        // the (runId, 3) lookup, not (runId, 0) or (runId).
        int writerEpoch = 3;
        when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(
                snapshotWithEmptyRunningAtEpoch(writerEpoch));
        // The per-epoch overload is what the writer side targets.
        when(runningNodeTracker.getRunningCountsOrThrow(RUN_ID, writerEpoch))
                .thenReturn(Map.of("mcp:step1", 1));

        service.closeEpochIfCompleteForSbs(RUN_ID, TRIGGER_ID, writerEpoch);

        // Pin the EXACT lookup shape: (runId, epoch) - no degraded run-wide reads.
        verify(runningNodeTracker).getRunningCountsOrThrow(RUN_ID, writerEpoch);
        // Gate must DEFER (Redis observed the running node populated by the writer).
        verify(stateSnapshotService, never()).closeEpoch(anyString(), anyString(), anyInt());
        verify(epochConcurrencyLimiter, never()).release(anyString(), anyString());
    }

    private StateSnapshot snapshotWithEmptyRunningAtEpoch(int epoch) {
        EpochState epochState = new EpochState(
                Set.of(TRIGGER_ID, "mcp:step1"), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), Map.of(), Instant.now());
        Map<Integer, EpochState> epochs = new HashMap<>();
        epochs.put(epoch, epochState);
        DagState dag = new DagState(epoch, 0, 1, epochs, Set.of(epoch));
        return StateSnapshot.empty().withDagState(TRIGGER_ID, dag);
    }
}

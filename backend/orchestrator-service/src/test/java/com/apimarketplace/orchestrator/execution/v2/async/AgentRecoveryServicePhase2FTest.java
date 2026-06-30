package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.persistence.SplitAggregateProjection;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2.F regression tests - seal-then-crash recovery scan.
 *
 * Defends against the seal-then-crash window: JVM crashes AFTER
 * SplitCoalesceTracker.arrive() returned the sealed batch but BEFORE
 * recordSplitAggregateIfMissing wrote the global mark. The pending entries
 * are already consumed (registry empty), barrier deleted from Redis, but
 * step_data has N rows and EpochState has the node in NEITHER completedNodeIds
 * NOR failedNodeIds.
 *
 * Without Phase 2.F the run hangs at RUNNING forever - same end-state as the
 * original prod incident.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRecoveryService - Phase 2.F orphan-aggregate recovery")
class AgentRecoveryServicePhase2FTest {

    @Mock private RedisPendingAgentStore pendingStore;
    @Mock private PendingAgentRegistry registry;
    @Mock private AgentAsyncCompletionService completionService;
    @Mock private SplitCoalesceTracker splitCoalesceTracker;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private WorkflowStepDataRepository stepDataRepository;

    private AgentRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        recoveryService = new AgentRecoveryService(
            pendingStore, registry, completionService, splitCoalesceTracker,
            redisTemplate, new ObjectMapper(), runRepository,
            stepCompletionOrchestrator, stepDataRepository,
            1_800_000L);
    }

    private WorkflowRunEntity runWithStatus(String runId, RunStatus status) {
        WorkflowRunEntity e = mock(WorkflowRunEntity.class);
        lenient().when(e.getRunIdPublic()).thenReturn(runId);
        lenient().when(e.getStatus()).thenReturn(status);
        return e;
    }

    private WorkflowStepDataEntity stepData(String normalizedKey, int epoch, String status) {
        WorkflowStepDataEntity e = new WorkflowStepDataEntity();
        e.setNormalizedKey(normalizedKey);
        e.setEpoch(epoch);
        e.setStatus(status);
        return e;
    }

    /**
     * Build a lightweight {@link SplitAggregateProjection} for the new GROUP BY path
     * (post-2026-05-22 OOM hardening). Replaces {@code stepData(...)} list mocks for
     * Phase 2.F tests - the recovery service no longer touches full {@code findByRunId},
     * it goes straight to the projection.
     */
    private SplitAggregateProjection projection(String normalizedKey, int epoch, long count) {
        return new SplitAggregateProjection(normalizedKey, epoch, count);
    }

    @Test
    @DisplayName("recoverOrphanedAggregatesUsesGroupByProjectionAndDoesNotLoadFullEntities: split with multiple item rows triggers recordSplitAggregateIfMissing via lightweight projection (NO findByRunId call)")
    void splitAggregateRecoveryServiceFindsOrphanedSplitsOnStartupAndAggregates() {
        // Setup: 1 active run with classify orphans - projection returns ONE row (count=3)
        // because the SQL GROUP BY collapses the 3 raw rows. We assert findByRunId is NOT
        // touched anymore (that was the 2026-05-22 OOM shape).
        WorkflowRunEntity orphan = runWithStatus("run-orphaned", RunStatus.RUNNING);
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(orphan));
        when(runRepository.findByStatus(RunStatus.PENDING)).thenReturn(List.of());

        when(stepDataRepository.findSplitAggregateProjectionsByRunId("run-orphaned"))
            .thenReturn(List.of(projection("agent:classify", 5, 3L)));

        recoveryService.recoverOrphanedSplitAggregates();

        // Critical assertion: the heavy unbounded fetch MUST NOT be called.
        verify(stepDataRepository, never()).findByRunId(anyString());
        // The projection path IS called.
        verify(stepDataRepository).findSplitAggregateProjectionsByRunId("run-orphaned");
        // Aggregate marking for the (classify, epoch=5) tuple proceeds as before.
        verify(stepCompletionOrchestrator).recordSplitAggregateIfMissing(
            eq("run-orphaned"), isNull(), eq("agent:classify"), eq(5));
    }

    @Test
    @DisplayName("Phase 2.F: single-item rows ignored - only split-shaped (≥2) trigger aggregate scan (now enforced server-side by HAVING COUNT ≥ 2)")
    void splitAggregateRecoverySkipsSingleItemRows() {
        WorkflowRunEntity singleton = runWithStatus("run-singleton", RunStatus.RUNNING);
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(singleton));
        when(runRepository.findByStatus(RunStatus.PENDING)).thenReturn(List.of());

        // HAVING COUNT(w) >= 2 server-side filter returns NOTHING for single-item rows.
        when(stepDataRepository.findSplitAggregateProjectionsByRunId("run-singleton"))
            .thenReturn(List.of());

        recoveryService.recoverOrphanedSplitAggregates();

        verify(stepCompletionOrchestrator, never()).recordSplitAggregateIfMissing(
            anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Phase 2.F: terminal runs are NOT scanned (only RUNNING + PENDING)")
    void splitAggregateRecoveryIgnoresTerminalRuns() {
        // Terminal runs don't appear in findByStatus(RUNNING) or PENDING - verify
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
        when(runRepository.findByStatus(RunStatus.PENDING)).thenReturn(List.of());

        recoveryService.recoverOrphanedSplitAggregates();

        verify(stepDataRepository, never()).findSplitAggregateProjectionsByRunId(anyString());
        verify(stepDataRepository, never()).findByRunId(anyString());
        verify(stepCompletionOrchestrator, never()).recordSplitAggregateIfMissing(
            anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Phase 2.F: collaborators unavailable (null) → no-op (backward-compat path)")
    void splitAggregateRecoveryNoOpWhenCollaboratorsUnavailable() {
        // Use the 8-arg backward-compat constructor (no Phase 2.F collaborators)
        AgentRecoveryService noPhase2F = new AgentRecoveryService(
            pendingStore, registry, completionService, splitCoalesceTracker,
            redisTemplate, new ObjectMapper(), runRepository, 1_800_000L);

        // Should not throw, not interact with anything
        noPhase2F.recoverOrphanedSplitAggregates();

        verify(stepCompletionOrchestrator, never()).recordSplitAggregateIfMissing(
            anyString(), anyString(), anyString(), anyInt());
        verifyNoInteractions(runRepository);
    }

    @Test
    @DisplayName("Phase 2.F: multiple split tuples in same run each trigger one aggregate call")
    void splitAggregateRecoveryHandlesMultipleSplitsInSameRun() {
        WorkflowRunEntity multi = runWithStatus("run-multi", RunStatus.RUNNING);
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(multi));
        when(runRepository.findByStatus(RunStatus.PENDING)).thenReturn(List.of());

        // Two distinct (normalizedKey, epoch) tuples each with ≥2 items - server-side
        // GROUP BY returns one row per tuple.
        when(stepDataRepository.findSplitAggregateProjectionsByRunId("run-multi"))
            .thenReturn(List.of(
                projection("agent:classify", 5, 2L),
                projection("agent:guardrail", 5, 2L)));

        recoveryService.recoverOrphanedSplitAggregates();

        verify(stepCompletionOrchestrator).recordSplitAggregateIfMissing(
            eq("run-multi"), isNull(), eq("agent:classify"), eq(5));
        verify(stepCompletionOrchestrator).recordSplitAggregateIfMissing(
            eq("run-multi"), isNull(), eq("agent:guardrail"), eq(5));
    }
}

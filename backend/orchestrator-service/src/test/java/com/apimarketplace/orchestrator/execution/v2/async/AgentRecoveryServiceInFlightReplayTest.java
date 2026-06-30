package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the Site C replay in {@link AgentRecoveryService#replayInFlightEntries}.
 *
 * <p>Pre-fix prod incident 2026-05-22 21:01 UTC: on restart, the standard recovery loop
 * scanned {@code agent:pending:*} and found 0 entries (the GETDEL had already happened
 * pre-crash). 10 in-flight items were lost.
 *
 * <p>Post-fix: this method SCANs {@code agent:in_flight:*} and replays each consumed
 * pending/result pair directly. It must not route through {@code onAgentResult}, because
 * that path correctly requires the already-consumed {@code agent:pending:{cid}} Redis key.
 * Re-running the replay is idempotent - guarded by {@code idx_workflow_step_data_unique_v6}
 * at the persistence layer and {@code EpochState} set semantics in memory.
 */
@ExtendWith(MockitoExtension.class)
class AgentRecoveryServiceInFlightReplayTest {

    @Mock private RedisPendingAgentStore pendingStore;
    @Mock private PendingAgentRegistry registry;
    @Mock private AgentAsyncCompletionService completionService;
    @Mock private SplitCoalesceTracker splitCoalesceTracker;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository stepDataRepository;
    @Mock private RedisInFlightStore inFlightStore;

    private AgentRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new AgentRecoveryService(
            pendingStore, registry, completionService, splitCoalesceTracker,
            redisTemplate, new ObjectMapper(), runRepository,
            stepCompletionOrchestrator, stepDataRepository,
            1_800_000L);
        ReflectionTestUtils.setField(service, "inFlightStore", inFlightStore);
    }

    @Test
    @DisplayName("replayInFlightEntriesDeliversConsumedPendingWithoutRedisConsume: Site C contract - replay bypasses missing agent:pending key")
    void replayInFlightEntriesDeliversConsumedPendingWithoutRedisConsume() {
        PendingAgent p1 = pending("cid-1");
        PendingAgent p2 = pending("cid-2");
        AgentResultMessage r1 = result("cid-1");
        AgentResultMessage r2 = result("cid-2");
        RedisInFlightStore.InFlightEntry e1 = new RedisInFlightStore.InFlightEntry(p1, r1);
        RedisInFlightStore.InFlightEntry e2 = new RedisInFlightStore.InFlightEntry(p2, r2);
        when(inFlightStore.listAll()).thenReturn(List.of(
            e1,
            e2));

        ReflectionTestUtils.invokeMethod(service, "replayInFlightEntries");

        InOrder o = inOrder(completionService);
        o.verify(completionService).replayInFlightResult(e1);
        o.verify(completionService).replayInFlightResult(e2);
        verify(registry, never()).registerFromRecovery(any());
        verify(completionService, never()).onAgentResult(any());
    }

    @Test
    @DisplayName("replayInFlightEntriesProducesSameEffectAcrossTwoInvocationsWhenStoreClearsAfterDelivery: realistic idempotency - second pass sees an empty store and is a no-op")
    void replayIsIdempotentWhenStoreClearsAfterFirstPass() {
        // Realistic flow: first pass delivers, then AgentAsyncCompletionService.onAgentResult
        // runs through its finally { inFlightStore.clear(cid) } block. Second SCAN sees an
        // empty list. The contract under test: invoking replayInFlightEntries TWICE matches
        // the effect of invoking it ONCE - the canonical definition of idempotency-of-effect.
        PendingAgent p = pending("cid-idem");
        AgentResultMessage r = result("cid-idem");
        when(inFlightStore.listAll())
            .thenReturn(List.of(new RedisInFlightStore.InFlightEntry(p, r)))   // 1st pass
            .thenReturn(List.of());                                             // 2nd pass

        ReflectionTestUtils.invokeMethod(service, "replayInFlightEntries");
        ReflectionTestUtils.invokeMethod(service, "replayInFlightEntries");

        // Exactly ONE register + ONE deliver across the two passes. Pinning times(1) here
        // is the load-bearing contract: a regression that scans-but-doesn't-clear would
        // double-deliver and this assertion would catch it.
        verify(registry, never()).registerFromRecovery(any());
        verify(completionService, times(1)).replayInFlightResult(new RedisInFlightStore.InFlightEntry(p, r));
        verify(completionService, never()).onAgentResult(any());
    }

    @Test
    @DisplayName("replayInFlightEntriesPathologicalCaseReDeliversWhenStoreNeverClears: documents the downstream absorbtion contract - register putIfAbsent + step_data unique constraint + EpochState set absorb duplicate effects")
    void replayDuplicatesWhenStoreNeverClears() {
        // Pathological flow: store.clear failed silently both times (Redis hiccup) so the
        // staged entry persists. Replay re-issues - which is the documented "safe" path
        // because downstream idempotency (PendingAgentRegistry.register = putIfAbsent,
        // idx_workflow_step_data_unique_v6 unique constraint, EpochState set semantics)
        // absorbs the duplicate effect. This test pins the upstream invocation count so a
        // future refactor that adds short-circuiting at the replay layer doesn't
        // regress the safety net.
        PendingAgent p = pending("cid-stuck");
        AgentResultMessage r = result("cid-stuck");
        when(inFlightStore.listAll()).thenReturn(List.of(
            new RedisInFlightStore.InFlightEntry(p, r)));

        ReflectionTestUtils.invokeMethod(service, "replayInFlightEntries");
        ReflectionTestUtils.invokeMethod(service, "replayInFlightEntries");

        verify(registry, never()).registerFromRecovery(any());
        verify(completionService, times(2)).replayInFlightResult(new RedisInFlightStore.InFlightEntry(p, r));
        verify(completionService, never()).onAgentResult(any());
    }

    @Test
    @DisplayName("recoverOnStartupStillReplaysInFlightWhenPendingSideStoreIsEmpty: post-GETDEL crash window must not return before Site C")
    void recoverOnStartupStillReplaysInFlightWhenPendingSideStoreIsEmpty() {
        PendingAgent p = pending("cid-after-getdel");
        AgentResultMessage r = result("cid-after-getdel");
        RedisInFlightStore.InFlightEntry entry = new RedisInFlightStore.InFlightEntry(p, r);
        when(pendingStore.listAll()).thenReturn(List.of());
        when(inFlightStore.listAll()).thenReturn(List.of(entry));

        service.recoverOnStartup();

        verify(completionService).replayInFlightResult(entry);
        verify(completionService, never()).onAgentResult(any());
    }

    @Test
    @DisplayName("recoverOnStartupPreRegistersCombinedPendingAndInFlightSplitBarrierBeforeDelivery: mixed crash state uses the total staged sources")
    void recoverOnStartupPreRegistersCombinedPendingAndInFlightSplitBarrierBeforeDelivery() {
        PendingAgent pending = splitPending("cid-pending", "run-mixed", "agent:classify", 5);
        PendingAgent stagedPending = splitPending("cid-staged", "run-mixed", "agent:classify", 5);
        RedisInFlightStore.InFlightEntry staged =
            new RedisInFlightStore.InFlightEntry(stagedPending, result("cid-staged"));
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setStatus(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublic("run-mixed")).thenReturn(Optional.of(run));
        when(pendingStore.listAll()).thenReturn(List.of(pending));
        when(inFlightStore.listAll()).thenReturn(List.of(staged));

        service.recoverOnStartup();

        InOrder order = inOrder(splitCoalesceTracker, registry, completionService);
        order.verify(splitCoalesceTracker).register("run-mixed", "agent:classify", 0, 2);
        order.verify(registry).registerFromRecovery(pending);
        order.verify(completionService).replayInFlightResult(staged);
        verify(splitCoalesceTracker, never()).register(eq("run-mixed"), eq("agent:classify"), eq(0), eq(1));
        verify(splitCoalesceTracker, never()).register(eq("run-mixed"), eq("agent:classify"), eq(0), eq(5));
        verify(completionService, never()).onAgentResult(any());
    }

    @Test
    @DisplayName("recoverOnStartupContinuesPendingRecoveryWhenInFlightScanFails: in-flight replay scan must not block side-store recovery")
    void recoverOnStartupContinuesPendingRecoveryWhenInFlightScanFails() {
        PendingAgent pending = splitPending("cid-pending-scan-fail", "run-scan-fail", "agent:classify", 3);
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setStatus(RunStatus.RUNNING);
        when(runRepository.findByRunIdPublic("run-scan-fail")).thenReturn(Optional.of(run));
        when(pendingStore.listAll()).thenReturn(List.of(pending));
        when(inFlightStore.listAll()).thenThrow(new IllegalStateException("redis scan failed"));

        service.recoverOnStartup();

        verify(splitCoalesceTracker).register("run-scan-fail", "agent:classify", 0, 1);
        verify(registry).registerFromRecovery(pending);
        verify(pendingStore).touchIndex(pending);
        verify(completionService, never()).replayInFlightResult(any());
        verify(completionService, never()).onAgentResult(any());
    }

    @Test
    @DisplayName("replayInFlightEntriesPreRegistersSplitBarriersBeforeDelivery: partial split replay uses staged count, not original split size")
    void replayInFlightEntriesPreRegistersSplitBarriersBeforeDelivery() {
        PendingAgent p1 = splitPending("cid-split-1", "run-split", "agent:classify", 4);
        PendingAgent p2 = splitPending("cid-split-2", "run-split", "agent:classify", 4);
        RedisInFlightStore.InFlightEntry e1 = new RedisInFlightStore.InFlightEntry(p1, result("cid-split-1"));
        RedisInFlightStore.InFlightEntry e2 = new RedisInFlightStore.InFlightEntry(p2, result("cid-split-2"));
        when(inFlightStore.listAll()).thenReturn(List.of(e1, e2));

        ReflectionTestUtils.invokeMethod(service, "replayInFlightEntries");

        InOrder order = inOrder(splitCoalesceTracker, completionService);
        order.verify(splitCoalesceTracker).register("run-split", "agent:classify", 0, 2);
        order.verify(completionService).replayInFlightResult(e1);
        order.verify(completionService).replayInFlightResult(e2);
        verify(splitCoalesceTracker, never()).register(eq("run-split"), eq("agent:classify"), eq(0), eq(4));
    }

    @Test
    @DisplayName("replayInFlightEntriesIsNoOpWhenInFlightStoreIsAbsent: backward-compat path when scaling.agent.queue.enabled=false")
    void replayIsNoOpWhenStoreAbsent() {
        ReflectionTestUtils.setField(service, "inFlightStore", null);

        ReflectionTestUtils.invokeMethod(service, "replayInFlightEntries");

        verify(registry, never()).registerFromRecovery(any());
        verify(completionService, never()).replayInFlightResult(any());
        verify(completionService, never()).onAgentResult(any());
    }

    @Test
    @DisplayName("replayInFlightEntriesIsNoOpWhenStagedListIsEmpty: empty SCAN result short-circuits without touching collaborators")
    void replayIsNoOpWhenStagedListEmpty() {
        when(inFlightStore.listAll()).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(service, "replayInFlightEntries");

        verify(registry, never()).registerFromRecovery(any());
        verify(completionService, never()).replayInFlightResult(any());
        verify(completionService, never()).onAgentResult(any());
    }

    private static PendingAgent pending(String cid) {
        return new PendingAgent(cid, "run", "agent:a", "A", "trigger:t", 0, 0, null,
            "agent", "t1", null, null, null, null, null, "m", null, null,
            Instant.now(), "o1");
    }

    private static PendingAgent splitPending(String cid, String runId, String nodeId, int originalSplitSize) {
        List<Map<String, Object>> originalItems = java.util.stream.IntStream.range(0, originalSplitSize)
            .mapToObj(i -> Map.<String, Object>of("index", i))
            .toList();
        Map<String, Object> splitData = Map.of(
            "items", originalItems,
            "workflowItemIndex", 0);
        return new PendingAgent(cid, runId, nodeId, "Classify", "trigger:t", 0, 0, "0",
            "classify", "t1", splitData, null, null, null, null, "m", null, null,
            Instant.now(), "o1");
    }

    private static AgentResultMessage result(String cid) {
        return new AgentResultMessage(cid, "run", "agent:a", Map.of(), true, null, "agent", Instant.now());
    }
}

package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.services.context.ReadinessContextCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the "fork -&gt; N parallel async agents -&gt; merge never fires"
 * hang (reproduced live 2026-07-01 on a CE mock-llm slot: epoch 1 hung on the merge,
 * a rebuilt epoch with the fix completed). Two independent defects, both required:
 *
 * <ol>
 *   <li><b>Premature epoch drain:</b> {@code triggerDeferredResetIfDrained} consulted
 *       only {@code registry.hasPendingFor} (the {@code agent:pending:*} store), but
 *       {@code onAgentResult} GETDELs that key at delivery START - so with N
 *       near-simultaneous completions the first-delivered agent saw an empty pending
 *       store and closed + pruned the epoch under its still-delivering siblings.</li>
 *   <li><b>Stale readiness view:</b> the async completion path persists via
 *       {@code StepCompletionOrchestrator}, which never invalidates
 *       {@link ReadinessContextCache} - so a sibling delivery &lt;500ms later read a
 *       cached context in which the merge was not ready, and nothing ever re-drove
 *       readiness.</li>
 * </ol>
 *
 * <p>The drain guard consults the in-flight store with a clear-self-BEFORE-check
 * protocol: each delivery clears its own staged entry and only then looks for
 * siblings, so the delivery whose clear lands last always observes a drained set and
 * fires the reset - a symmetric "both defer" is an ordering contradiction. Real LLMs
 * usually stagger completions past the cache TTL / delivery window, masking both
 * defects; mock or fast models surface them deterministically.
 */
@ExtendWith(MockitoExtension.class)
class AgentAsyncCompletionServiceForkMergeRaceTest {

    private static final String RUN_ID = "run-fork-merge";
    private static final String TRIGGER_ID = "trigger:chat";
    private static final int EPOCH = 3;
    private static final String DELIVERING_CID = "cid-branch-a";

    @Mock private PendingAgentRegistry registry;
    @Mock private RedisInFlightStore inFlightStore;
    @Mock private SignalResumeService signalResumeService;
    @Mock private V2StepByStepService v2StepByStepService;
    @Mock private ReadinessContextCache readinessCache;

    private AgentAsyncCompletionService service;

    @BeforeEach
    void setUp() {
        service = new AgentAsyncCompletionService(
            registry, mock(com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator.class),
            mock(com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager.class),
            mock(com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker.class),
            mock(com.apimarketplace.orchestrator.execution.v2.async.SplitCoalesceTracker.class),
            mock(com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService.class),
            mock(com.apimarketplace.orchestrator.repository.WorkflowRunRepository.class));
        ReflectionTestUtils.setField(service, "inFlightStore", inFlightStore);
        ReflectionTestUtils.setField(service, "signalResumeService", signalResumeService);
        ReflectionTestUtils.setField(service, "v2StepByStepService", v2StepByStepService);
        ReflectionTestUtils.setField(service, "readinessCache", readinessCache);
    }

    // ── Defect 1: premature epoch drain ────────────────────────────────────────

    @Test
    @DisplayName("consumedButStillDeliveringSiblingBlocksDeferredEpochReset")
    void consumedButStillDeliveringSiblingBlocksDeferredEpochReset() {
        // Sibling B was consume()d (pending store empty) but its delivery has not
        // finished: its agent:in_flight:* twin is still staged.
        when(registry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(false);
        when(inFlightStore.hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID))
            .thenReturn(true);

        service.triggerDeferredResetIfDrained(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID);

        verify(signalResumeService, never()).performDeferredReset(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("resetFiresWhenPendingStoreAndInFlightStoreAreBothDrained")
    void resetFiresWhenPendingStoreAndInFlightStoreAreBothDrained() {
        when(registry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(false);
        when(inFlightStore.hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID))
            .thenReturn(false);

        service.triggerDeferredResetIfDrained(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID);

        verify(signalResumeService).performDeferredReset(RUN_ID, TRIGGER_ID, EPOCH);
    }

    @Test
    @DisplayName("clearsOwnEntryBeforeTheSiblingCheckSoASymmetricDoubleDeferIsImpossible")
    void clearsOwnEntryBeforeTheSiblingCheck() {
        // The load-bearing ordering: clear(self) STRICTLY BEFORE hasOtherInFlightForEpoch.
        // With two near-simultaneous deliveries on different pods, whichever clear lands
        // last is followed by a check that sees an empty set - so at least one delivery
        // always closes the epoch. Checking before clearing would allow both to observe
        // each other and both defer (lost wakeup).
        when(registry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(false);
        when(inFlightStore.hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID))
            .thenReturn(false);

        service.triggerDeferredResetIfDrained(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID);

        InOrder order = inOrder(inFlightStore);
        order.verify(inFlightStore).clear(DELIVERING_CID);
        order.verify(inFlightStore).hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID);
    }

    @Test
    @DisplayName("pendingStoreEntriesStillBlockResetBeforeTheInFlightConsult")
    void pendingStoreEntriesStillBlockReset() {
        when(registry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(true);

        service.triggerDeferredResetIfDrained(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID);

        verify(signalResumeService, never()).performDeferredReset(anyString(), anyString(), anyInt());
        // Own entry is still cleared up-front (idempotent with the outer finally),
        // but the sibling consult is never reached.
        verify(inFlightStore).clear(DELIVERING_CID);
        verify(inFlightStore, never()).hasOtherInFlightForEpoch(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("drainCheckExcludesTheDeliveringAgentsOwnInFlightEntry")
    void drainCheckExcludesTheDeliveringAgentsOwnInFlightEntry() {
        // The exclusion matters for interleavings where our clear has not landed yet
        // when the check runs (and for recovery replays staging the same cid).
        when(registry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(false);
        when(inFlightStore.hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID))
            .thenReturn(false);

        service.triggerDeferredResetIfDrained(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID);

        verify(inFlightStore).hasOtherInFlightForEpoch(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID);
    }

    @Test
    @DisplayName("resetStillFiresWhenInFlightStoreIsAbsent")
    void resetStillFiresWhenInFlightStoreIsAbsent() {
        // Memory-only / legacy fixtures have no in-flight store; behavior must degrade
        // to the pre-guard contract, not NPE or block forever.
        ReflectionTestUtils.setField(service, "inFlightStore", null);
        when(registry.hasPendingFor(RUN_ID, TRIGGER_ID, EPOCH)).thenReturn(false);

        service.triggerDeferredResetIfDrained(RUN_ID, TRIGGER_ID, EPOCH, DELIVERING_CID);

        verify(signalResumeService).performDeferredReset(RUN_ID, TRIGGER_ID, EPOCH);
    }

    @Test
    @DisplayName("nullDagTriggerIdSkipsResetEntirely")
    void nullDagTriggerIdSkipsResetEntirely() {
        service.triggerDeferredResetIfDrained(RUN_ID, null, EPOCH, DELIVERING_CID);

        org.mockito.Mockito.verifyNoInteractions(signalResumeService);
        org.mockito.Mockito.verifyNoInteractions(inFlightStore);
    }

    @Test
    @DisplayName("failedDeliveryNeverTriggersTheDeferredReset: a lost result must leave the epoch open for the recovery scanner")
    void failedDeliveryNeverTriggersTheDeferredReset() {
        // onAgentResult with an unstubbed delivery pipeline: deliverUnderLock throws
        // internally, is caught, re-registers the pending, and returns false. The
        // outer finally must still clear our stage entry, but nothing may close the
        // epoch over a result that never persisted.
        when(registry.consume(DELIVERING_CID)).thenReturn(java.util.Optional.of(pendingAgent()));

        service.onAgentResult(new com.apimarketplace.orchestrator.domain.execution.AgentResultMessage(
            DELIVERING_CID, RUN_ID, "agent:branch", java.util.Map.of(), true, null, "agent",
            java.time.Instant.now()));

        verify(inFlightStore).clear(DELIVERING_CID);
        verify(signalResumeService, never()).performDeferredReset(anyString(), anyString(), anyInt());
    }

    private static PendingAgent pendingAgent() {
        return new PendingAgent(
            DELIVERING_CID, RUN_ID, "agent:branch", "Branch", TRIGGER_ID,
            EPOCH, 0, null, "agent", "tenant-1",
            null, null, null, null, null, "deepseek-chat", null, null,
            java.time.Instant.now(), "org-1");
    }

    // ── Defect 2: stale readiness view on the async path ───────────────────────

    @Test
    @DisplayName("invalidatesReadinessCacheBeforeFirstGetReadyNodesSoSiblingStaleContextIsNotServed")
    void invalidatesReadinessCacheBeforeFirstGetReadyNodes() {
        when(v2StepByStepService.getReadyNodes(RUN_ID, "item-0", EPOCH)).thenReturn(Set.of());

        service.executeReadyNodesLoop(RUN_ID, "item-0", TRIGGER_ID, EPOCH, Set.of(), null);

        // Ordering IS the contract: invalidating after the read would serve the sibling
        // delivery's <500ms-old context in which this delivery's row does not exist,
        // and the downstream merge would never be seen as ready.
        InOrder order = inOrder(readinessCache, v2StepByStepService);
        order.verify(readinessCache).invalidateRun(RUN_ID);
        order.verify(v2StepByStepService).getReadyNodes(RUN_ID, "item-0", EPOCH);
    }

    @Test
    @DisplayName("readinessLoopInvalidatesEvenOnThePreloadedExecutionPath")
    void readinessLoopInvalidatesEvenOnThePreloadedExecutionPath() {
        com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution preloaded =
            mock(com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager.LoadedExecution.class);
        when(v2StepByStepService.getReadyNodes(eq(RUN_ID), eq("item-0"), eq(EPOCH), eq(preloaded)))
            .thenReturn(Set.of());

        service.executeReadyNodesLoop(RUN_ID, "item-0", TRIGGER_ID, EPOCH, Set.of(), preloaded);

        InOrder order = inOrder(readinessCache, v2StepByStepService);
        order.verify(readinessCache).invalidateRun(RUN_ID);
        order.verify(v2StepByStepService).getReadyNodes(RUN_ID, "item-0", EPOCH, preloaded);
    }

    @Test
    @DisplayName("readinessLoopToleratesAbsentCacheForLegacyFixtures")
    void readinessLoopToleratesAbsentCache() {
        ReflectionTestUtils.setField(service, "readinessCache", null);
        when(v2StepByStepService.getReadyNodes(RUN_ID, "item-0", EPOCH)).thenReturn(Set.of());

        service.executeReadyNodesLoop(RUN_ID, "item-0", TRIGGER_ID, EPOCH, Set.of(), null);

        verify(v2StepByStepService).getReadyNodes(RUN_ID, "item-0", EPOCH);
    }
}

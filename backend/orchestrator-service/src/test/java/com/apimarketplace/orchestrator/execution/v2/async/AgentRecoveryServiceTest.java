package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentRecoveryService}.
 *
 * <p>Covers four failure modes:
 * <ol>
 *   <li>Restart with side-store entries → re-register + immediate result poll</li>
 *   <li>Pub/sub miss while running → periodic scan delivers from result key</li>
 *   <li>Worker crashed before publishing (stale, no result key) → synthetic failure
 *       past hard timeout</li>
 *   <li>Result key missing AND not yet timed out → no action</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRecoveryService")
class AgentRecoveryServiceTest {

    @Mock
    private RedisPendingAgentStore pendingStore;

    @Mock
    private AgentAsyncCompletionService completionService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private SplitCoalesceTracker splitCoalesceTracker;

    @Mock
    private com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;

    private PendingAgentRegistry registry;
    private ObjectMapper objectMapper;
    private AgentRecoveryService recoveryService;

    /** 30-min hard timeout (default). */
    private static final long HARD_TIMEOUT_MS = 30L * 60 * 1000;

    @BeforeEach
    void setUp() {
        registry = new PendingAgentRegistry();
        objectMapper = new ObjectMapper();
        recoveryService = new AgentRecoveryService(
            pendingStore, registry, completionService, splitCoalesceTracker,
            redisTemplate, objectMapper, runRepository, HARD_TIMEOUT_MS);

        // Default: runs are RUNNING (non-terminal) so recovery does not skip them.
        // Individual tests override this when they need the terminal-run behavior.
        com.apimarketplace.orchestrator.domain.WorkflowRunEntity runningRun =
            new com.apimarketplace.orchestrator.domain.WorkflowRunEntity();
        runningRun.setStatus(com.apimarketplace.orchestrator.domain.workflow.RunStatus.RUNNING);
        org.mockito.Mockito.lenient().when(runRepository.findByRunIdPublic(anyString()))
            .thenReturn(java.util.Optional.of(runningRun));
    }

    private PendingAgent agent(String correlationId, Instant startedAt) {
        return new PendingAgent(
            correlationId,
            "run-" + correlationId,
            "agent:test",
            "test",
            "trigger:default",
            0,
            0,
            "0",
            "agent",
            "tenant-1",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            startedAt
        );
    }

    /** Helper for split-context agents sharing the same (runId, nodeId, epoch). */
    private PendingAgent splitAgent(String correlationId, String runId, String nodeId,
                                    int epoch, int itemIndex, int totalItemsSnapshot) {
        Map<String, Object> splitData = new HashMap<>();
        splitData.put("splitNodeId", "core:split_messages");
        splitData.put("workflowItemIndex", 0);
        splitData.put("itemIndex", itemIndex);
        // Snapshot of the full dispatched list - used as the FALLBACK total in
        // ensureBarrierRegistered, and we specifically want to show recovery overrides it.
        splitData.put("items", List.of("a", "b", "c").subList(0, totalItemsSnapshot));
        return new PendingAgent(
            correlationId, runId, nodeId, "classifier", "trigger:default",
            epoch, itemIndex, String.valueOf(itemIndex), "classify",
            "tenant-1", splitData, null, null, null, null, null, null, null, Instant.now());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Startup recovery
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Startup recovery re-registers entries and polls each result key")
    void startupRecoversAndPollsResults() throws Exception {
        PendingAgent a1 = agent("c-1", Instant.now());
        PendingAgent a2 = agent("c-2", Instant.now());
        when(pendingStore.listAll()).thenReturn(List.of(a1, a2));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // c-1 has a result waiting, c-2 does not
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("output", "ok");
        when(valueOps.get("agent:result:c-1")).thenReturn(objectMapper.writeValueAsString(result));
        when(valueOps.get("agent:result:c-2")).thenReturn(null);
        when(completionService.onAgentResult(any())).thenReturn(true);

        recoveryService.recoverOnStartup();

        // Both re-registered
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.peek("c-1")).isPresent();
        assertThat(registry.peek("c-2")).isPresent();

        // Only c-1 was delivered
        ArgumentCaptor<AgentResultMessage> captor = ArgumentCaptor.forClass(AgentResultMessage.class);
        verify(completionService, times(1)).onAgentResult(captor.capture());
        assertThat(captor.getValue().correlationId()).isEqualTo("c-1");
        assertThat(captor.getValue().success()).isTrue();

        // Result key cleaned up after successful delivery
        verify(redisTemplate).delete("agent:result:c-1");
        verify(redisTemplate, never()).delete("agent:result:c-2");
    }

    @Test
    @DisplayName("Startup recovery is a no-op when the side-store is empty")
    void startupNoOpWhenStoreEmpty() {
        when(pendingStore.listAll()).thenReturn(List.of());

        recoveryService.recoverOnStartup();

        assertThat(registry.size()).isZero();
        verify(completionService, never()).onAgentResult(any());
    }

    @Test
    @DisplayName("Startup recovery survives a side-store listAll failure")
    void startupSurvivesListAllFailure() {
        when(pendingStore.listAll()).thenThrow(new RuntimeException("redis down"));

        // Must not throw
        recoveryService.recoverOnStartup();

        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("Startup recovery does NOT mirror back to Redis (registerFromRecovery skips redisStore)")
    void startupDoesNotMirrorBackToRedis() {
        PendingAgent a1 = agent("c-1", Instant.now());
        when(pendingStore.listAll()).thenReturn(List.of(a1));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("agent:result:c-1")).thenReturn(null);

        recoveryService.recoverOnStartup();

        // The store was only used for listAll - no store() calls during recovery
        verify(pendingStore, never()).store(any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Periodic scan
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("scanPending is a no-op when registry is empty")
    void scanIsNoOpWhenRegistryEmpty() {
        recoveryService.scanPending();
        verify(redisTemplate, never()).opsForValue();
        verify(completionService, never()).onAgentResult(any());
    }

    @Test
    @DisplayName("scanPending delivers result-key hits (pub/sub miss recovery)")
    void scanDeliversResultKeyHits() throws Exception {
        PendingAgent fresh = agent("c-fresh", Instant.now());
        registry.registerFromRecovery(fresh);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        when(valueOps.get("agent:result:c-fresh")).thenReturn(objectMapper.writeValueAsString(result));
        when(completionService.onAgentResult(any())).thenReturn(true);

        recoveryService.scanPending();

        verify(completionService).onAgentResult(any());
        verify(redisTemplate).delete("agent:result:c-fresh");
    }

    @Test
    @DisplayName("scanPending delivers synthetic failure for stale entries past hard timeout")
    void scanDeliversSyntheticFailureWhenStale() {
        Instant longAgo = Instant.now().minusMillis(HARD_TIMEOUT_MS + 60_000); // 1 min past timeout
        PendingAgent stale = agent("c-stale", longAgo);
        registry.registerFromRecovery(stale);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("agent:result:c-stale")).thenReturn(null); // no worker result

        recoveryService.scanPending();

        ArgumentCaptor<AgentResultMessage> captor = ArgumentCaptor.forClass(AgentResultMessage.class);
        verify(completionService).onAgentResult(captor.capture());
        AgentResultMessage failure = captor.getValue();
        assertThat(failure.correlationId()).isEqualTo("c-stale");
        assertThat(failure.success()).isFalse();
        assertThat(failure.errorMessage()).contains("Hard timeout");
        assertThat(failure.result()).containsEntry("synthetic", true);
    }

    @Test
    @DisplayName("scanPending leaves fresh entries with no result alone")
    void scanLeavesFreshEntriesAlone() {
        PendingAgent fresh = agent("c-fresh", Instant.now());
        registry.registerFromRecovery(fresh);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("agent:result:c-fresh")).thenReturn(null);

        recoveryService.scanPending();

        verify(completionService, never()).onAgentResult(any());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("scanPending prefers result delivery over synthetic failure when both apply")
    void scanPrefersResultOverFailure() throws Exception {
        // Stale AND has a result waiting → deliver the real result, not a failure
        Instant longAgo = Instant.now().minusMillis(HARD_TIMEOUT_MS + 60_000);
        PendingAgent stale = agent("c-late-result", longAgo);
        registry.registerFromRecovery(stale);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("output", "actually completed");
        when(valueOps.get("agent:result:c-late-result"))
            .thenReturn(objectMapper.writeValueAsString(result));
        when(completionService.onAgentResult(any())).thenReturn(true);

        recoveryService.scanPending();

        ArgumentCaptor<AgentResultMessage> captor = ArgumentCaptor.forClass(AgentResultMessage.class);
        verify(completionService, times(1)).onAgentResult(captor.capture());
        // It's the real result, not synthetic
        assertThat(captor.getValue().success()).isTrue();
        assertThat(captor.getValue().result()).doesNotContainKey("synthetic");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // tryDeliverResult - error result handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Worker error result is delivered with success=false")
    void deliversWorkerErrorAsFailure() throws Exception {
        PendingAgent a = agent("c-err", Instant.now());
        registry.registerFromRecovery(a);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("error", "model timeout");
        when(valueOps.get("agent:result:c-err")).thenReturn(objectMapper.writeValueAsString(errorResult));
        when(completionService.onAgentResult(any())).thenReturn(true);

        recoveryService.scanPending();

        ArgumentCaptor<AgentResultMessage> captor = ArgumentCaptor.forClass(AgentResultMessage.class);
        verify(completionService).onAgentResult(captor.capture());
        AgentResultMessage delivered = captor.getValue();
        assertThat(delivered.success()).isFalse();
        assertThat(delivered.errorMessage()).isEqualTo("model timeout");
    }

    @Test
    @DisplayName("Result key is NOT deleted if completion service rejects (idempotent retry on next scan)")
    void resultKeyKeptOnRejection() throws Exception {
        PendingAgent a = agent("c-rej", Instant.now());
        registry.registerFromRecovery(a);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        when(valueOps.get("agent:result:c-rej")).thenReturn(objectMapper.writeValueAsString(result));
        // Completion service rejects (e.g., race with another delivery)
        when(completionService.onAgentResult(any())).thenReturn(false);

        recoveryService.scanPending();

        verify(completionService).onAgentResult(any());
        verify(redisTemplate, never()).delete(anyString());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Split barrier pre-registration (partial-restart deadlock fix)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("preRegisterSplitBarriers counts actual post-restart in-flight, NOT pre-restart items.size()")
    void preRegistersBarrierWithPostRestartCount() {
        // Scenario: 3 items were originally dispatched. 1 was delivered BEFORE the restart.
        // After restart, only 2 PendingAgent entries survive in Redis. The barrier MUST be
        // sized 2, not 3 - otherwise we wait for an arrival that will never come.
        PendingAgent item1 = splitAgent("c-1", "run-split", "agent:classifier", 3, 1, 3);
        PendingAgent item2 = splitAgent("c-2", "run-split", "agent:classifier", 3, 2, 3);

        recoveryService.preRegisterSplitBarriers(List.of(item1, item2));

        // Barrier was registered with the recovered count (=2, not 3)
        verify(splitCoalesceTracker).register("run-split", "agent:classifier", 3, 2);
    }

    @Test
    @DisplayName("preRegisterSplitBarriers groups entries by (runId, nodeId, epoch)")
    void preRegistersSeparateBarriersPerGroup() {
        // Two separate split contexts: same nodeId different runId
        PendingAgent a1 = splitAgent("c-1", "run-A", "agent:classifier", 0, 0, 2);
        PendingAgent a2 = splitAgent("c-2", "run-A", "agent:classifier", 0, 1, 2);
        PendingAgent b1 = splitAgent("c-3", "run-B", "agent:classifier", 0, 0, 3);
        PendingAgent b2 = splitAgent("c-4", "run-B", "agent:classifier", 0, 1, 3);
        PendingAgent b3 = splitAgent("c-5", "run-B", "agent:classifier", 0, 2, 3);

        recoveryService.preRegisterSplitBarriers(List.of(a1, a2, b1, b2, b3));

        // Two separate barriers were registered
        verify(splitCoalesceTracker).register("run-A", "agent:classifier", 0, 2);
        verify(splitCoalesceTracker).register("run-B", "agent:classifier", 0, 3);
    }

    @Test
    @DisplayName("preRegisterSplitBarriers skips non-split entries entirely")
    void preRegisterIgnoresNonSplitAgents() {
        PendingAgent plain = agent("c-plain", Instant.now());
        recoveryService.preRegisterSplitBarriers(List.of(plain));
        // No barriers should be registered for non-split agents
        verify(splitCoalesceTracker, never()).register(anyString(), anyString(), any(int.class), any(int.class));
    }

    @Test
    @DisplayName("recoverOnStartup pre-registers barriers BEFORE populating the registry")
    void startupPreRegistersBarriersBeforeRegistry() throws Exception {
        PendingAgent item1 = splitAgent("c-r1", "run-rec", "agent:classifier", 0, 0, 2);
        PendingAgent item2 = splitAgent("c-r2", "run-rec", "agent:classifier", 0, 1, 2);
        when(pendingStore.listAll()).thenReturn(List.of(item1, item2));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        recoveryService.recoverOnStartup();

        // Barrier was pre-registered with the recovered count (=2)
        verify(splitCoalesceTracker).register("run-rec", "agent:classifier", 0, 2);
        // And registry was populated too
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Recovery filter rehydrates pending agents on a reusable-trigger run between fires (WAITING_TRIGGER + no cancel signal)")
    void startupRehydratesPendingAgentsForReusableTriggerRunBetweenFires() throws Exception {
        // Regression for the Gmail Auto-Labeler incident family - pre-fix the recovery
        // filter dropped any WAITING_TRIGGER run on startup, even though that's the normal
        // between-fires state for cron triggers. The pending agent's eventual result would
        // arrive after restart with no matching registry entry → silent data loss.
        PendingAgent agent = agent("c-rt", Instant.now());
        com.apimarketplace.orchestrator.domain.WorkflowRunEntity run =
            mock(com.apimarketplace.orchestrator.domain.WorkflowRunEntity.class);
        when(run.getStatus()).thenReturn(com.apimarketplace.orchestrator.domain.workflow.RunStatus.WAITING_TRIGGER);
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(java.util.Optional.of(run));

        // Wire the production guard with a Redis publisher reporting "no cancel signal" -
        // i.e. resetForNextCycle path, not stopWorkflow.
        var redisPublisher = mock(com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher.class);
        when(redisPublisher.isAgentCancelSignalSet(anyString())).thenReturn(false);
        var guard = new com.apimarketplace.orchestrator.services.resume.RunCancellationGuard(
            runRepository, redisPublisher);
        org.springframework.test.util.ReflectionTestUtils.setField(recoveryService, "runCancellationGuard", guard);

        when(pendingStore.listAll()).thenReturn(List.of(agent));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        recoveryService.recoverOnStartup();

        // Entry MUST be re-registered - the guard correctly recognized WAITING_TRIGGER
        // without a cancel signal as alive-between-fires.
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.peek("c-rt")).isPresent();
    }

    @Test
    @DisplayName("Recovery filter still drops pending agents on a stopped run (WAITING_TRIGGER + cancel signal SET)")
    void startupDropsPendingAgentsOnStoppedRun() throws Exception {
        PendingAgent agent = agent("c-stopped", Instant.now());
        com.apimarketplace.orchestrator.domain.WorkflowRunEntity run =
            mock(com.apimarketplace.orchestrator.domain.WorkflowRunEntity.class);
        when(run.getStatus()).thenReturn(com.apimarketplace.orchestrator.domain.workflow.RunStatus.WAITING_TRIGGER);
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(java.util.Optional.of(run));

        // stopWorkflow path - cancel signal IS set.
        var redisPublisher = mock(com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher.class);
        when(redisPublisher.isAgentCancelSignalSet(anyString())).thenReturn(true);
        var guard = new com.apimarketplace.orchestrator.services.resume.RunCancellationGuard(
            runRepository, redisPublisher);
        org.springframework.test.util.ReflectionTestUtils.setField(recoveryService, "runCancellationGuard", guard);

        when(pendingStore.listAll()).thenReturn(List.of(agent));

        recoveryService.recoverOnStartup();

        // Entry MUST be filtered out - guard recognized stopWorkflow case.
        assertThat(registry.size()).isZero();
    }
}

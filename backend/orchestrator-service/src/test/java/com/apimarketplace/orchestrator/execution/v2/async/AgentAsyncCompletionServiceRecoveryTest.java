package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for the recovery-path split barrier re-registration in
 * {@link AgentAsyncCompletionService#ensureBarrierRegistered}.
 *
 * <p>Uses a mocked {@link SplitCoalesceTracker} (now Redis-backed) to verify
 * the correct register/isRegistered calls without requiring a Redis connection.
 * The actual barrier sealing logic is tested in {@link SplitCoalesceTrackerTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentAsyncCompletionService - ensureBarrierRegistered (recovery path)")
class AgentAsyncCompletionServiceRecoveryTest {

    @Mock private SplitCoalesceTracker splitCoalesceTracker;
    @Mock private PendingAgentRegistry registry;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private SplitContextManager splitContextManager;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;

    private AgentAsyncCompletionService service;

    @BeforeEach
    void setUp() {
        service = new AgentAsyncCompletionService(
            registry,
            stepCompletionOrchestrator,
            splitContextManager,
            runningNodeTracker,
            splitCoalesceTracker,
            new com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService(),
            runRepository);
    }

    private PendingAgent splitAgent(String correlationId, int itemIndex, int totalItems) {
        Map<String, Object> splitData = new HashMap<>();
        splitData.put("splitNodeId", "core:split_messages");
        splitData.put("workflowItemIndex", 0);
        splitData.put("itemIndex", itemIndex);
        splitData.put("items", List.of("a", "b", "c").subList(0, totalItems));
        return new PendingAgent(
            correlationId, "run-recovery", "agent:classifier", "classifier",
            "trigger:default", 3, itemIndex, String.valueOf(itemIndex), "classify",
            "tenant-1", splitData, null, null, null, null, null, null, null, Instant.now().minusSeconds(5));
    }

    @Test
    @DisplayName("re-registers barrier from items.size() when not yet registered")
    void reRegistersFromItemsSize() {
        PendingAgent pending = splitAgent("c-1", 0, 3);
        when(splitCoalesceTracker.isRegistered("run-recovery", "agent:classifier", 3)).thenReturn(false);

        service.ensureBarrierRegistered("run-recovery", "agent:classifier", pending);

        verify(splitCoalesceTracker).register("run-recovery", "agent:classifier", 3, 3);
    }

    @Test
    @DisplayName("is idempotent - no-op when barrier is already registered")
    void idempotentWhenAlreadyRegistered() {
        PendingAgent pending = splitAgent("c-2", 1, 3);
        when(splitCoalesceTracker.isRegistered("run-recovery", "agent:classifier", 3)).thenReturn(true);

        service.ensureBarrierRegistered("run-recovery", "agent:classifier", pending);

        // Should NOT call register since barrier already exists
        verify(splitCoalesceTracker, never()).register(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("warns and skips when splitItemData is missing the items key")
    void skipsWhenItemsMissing() {
        Map<String, Object> splitData = new HashMap<>();
        splitData.put("splitNodeId", "core:split_messages");
        splitData.put("workflowItemIndex", 0);
        // no "items" key
        PendingAgent pending = new PendingAgent(
            "c-3", "run-x", "agent:x", "x", "trigger:default", 1, 0, "0", "classify",
            "tenant-1", splitData, null, null, null, null, null, null, null, Instant.now());

        service.ensureBarrierRegistered("run-x", "agent:x", pending);

        verify(splitCoalesceTracker, never()).register(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("warns and skips when items is present but empty")
    void skipsWhenItemsEmpty() {
        Map<String, Object> splitData = new HashMap<>();
        splitData.put("items", List.of());
        PendingAgent pending = new PendingAgent(
            "c-4", "run-y", "agent:y", "y", "trigger:default", 0, 0, "0", "classify",
            "tenant-1", splitData, null, null, null, null, null, null, null, Instant.now());

        service.ensureBarrierRegistered("run-y", "agent:y", pending);

        verify(splitCoalesceTracker, never()).register(anyString(), anyString(), anyInt(), anyInt());
    }
}

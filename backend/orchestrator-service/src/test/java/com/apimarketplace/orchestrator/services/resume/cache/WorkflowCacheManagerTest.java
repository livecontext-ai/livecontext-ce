package com.apimarketplace.orchestrator.services.resume.cache;

import com.apimarketplace.orchestrator.config.RedisCacheConfig;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager.PausedWorkflowState;
import com.apimarketplace.orchestrator.services.state.WorkflowStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WorkflowCacheManager (Caffeine-backed).
 */
@ExtendWith(MockitoExtension.class)
class WorkflowCacheManagerTest {

    @Mock
    private RedisCacheConfig cacheConfig;

    private WorkflowCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        lenient().when(cacheConfig.getWorkflowStateTtl()).thenReturn(Duration.ofHours(24));
        cacheManager = new WorkflowCacheManager(cacheConfig);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAUSED WORKFLOWS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void shouldStorePausedWorkflow() {
        String runId = "run-1";
        Instant pausedAt = Instant.now();
        PausedWorkflowState state = new PausedWorkflowState(runId, pausedAt, null);

        cacheManager.storePausedWorkflow(runId, state);

        PausedWorkflowState result = cacheManager.getPausedWorkflow(runId);
        assertNotNull(result);
        assertEquals(runId, result.runId());
        assertEquals(pausedAt, result.pausedAt());
    }

    @Test
    void shouldRetrievePausedWorkflow() {
        String runId = "run-1";
        Instant pausedAt = Instant.parse("2024-01-15T10:00:00Z");
        PausedWorkflowState state = new PausedWorkflowState(runId, pausedAt, null);

        cacheManager.storePausedWorkflow(runId, state);

        PausedWorkflowState result = cacheManager.getPausedWorkflow(runId);
        assertNotNull(result);
        assertEquals(runId, result.runId());
        assertEquals(pausedAt, result.pausedAt());
    }

    @Test
    void shouldReturnNullForNonExistentPausedWorkflow() {
        PausedWorkflowState result = cacheManager.getPausedWorkflow("non-existent");
        assertNull(result);
    }

    @Test
    void shouldRemovePausedWorkflow() {
        String runId = "run-1";
        PausedWorkflowState state = new PausedWorkflowState(runId, Instant.now(), null);
        cacheManager.storePausedWorkflow(runId, state);

        cacheManager.removePausedWorkflow(runId);

        assertNull(cacheManager.getPausedWorkflow(runId));
    }

    @Test
    void shouldCheckIfPaused() {
        String runId = "run-1";
        PausedWorkflowState state = new PausedWorkflowState(runId, Instant.now(), null);
        cacheManager.storePausedWorkflow(runId, state);

        assertTrue(cacheManager.isPaused(runId));
    }

    @Test
    void shouldReturnFalseWhenNotPaused() {
        assertFalse(cacheManager.isPaused("run-1"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EVALUATED CORE NODES TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void shouldMarkCoreEvaluated() {
        cacheManager.markCoreEvaluated("run-1", "decision-1");

        assertTrue(cacheManager.isCoreEvaluated("run-1", "decision-1"));
    }

    @Test
    void shouldReturnFalseForNonEvaluatedCore() {
        assertFalse(cacheManager.isCoreEvaluated("run-1", "decision-1"));
    }

    @Test
    void shouldClearEvaluatedCores() {
        cacheManager.markCoreEvaluated("run-1", "decision-1");
        cacheManager.clearEvaluatedCores("run-1");

        assertFalse(cacheManager.isCoreEvaluated("run-1", "decision-1"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE MANAGER TESTS (Local Memory)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void shouldStoreStateManager() {
        String runId = "run-1";
        WorkflowStateManager stateManager = new WorkflowStateManager();

        cacheManager.storeStateManager(runId, stateManager);

        assertEquals(stateManager, cacheManager.getStateManager(runId));
    }

    @Test
    void shouldReturnNullForNonExistentStateManager() {
        assertNull(cacheManager.getStateManager("non-existent"));
    }

    @Test
    void shouldRemoveStateManager() {
        String runId = "run-1";
        WorkflowStateManager stateManager = new WorkflowStateManager();
        cacheManager.storeStateManager(runId, stateManager);

        cacheManager.removeStateManager(runId);

        assertNull(cacheManager.getStateManager(runId));
    }

    @Test
    void shouldOverwriteExistingStateManager() {
        String runId = "run-1";
        WorkflowStateManager stateManager1 = new WorkflowStateManager();
        WorkflowStateManager stateManager2 = new WorkflowStateManager();

        cacheManager.storeStateManager(runId, stateManager1);
        cacheManager.storeStateManager(runId, stateManager2);

        assertEquals(stateManager2, cacheManager.getStateManager(runId));
        assertNotEquals(stateManager1, cacheManager.getStateManager(runId));
    }

    @Test
    void shouldIsolateStateManagersByRun() {
        String runId1 = "run-1";
        String runId2 = "run-2";
        WorkflowStateManager stateManager1 = new WorkflowStateManager();
        WorkflowStateManager stateManager2 = new WorkflowStateManager();

        cacheManager.storeStateManager(runId1, stateManager1);
        cacheManager.storeStateManager(runId2, stateManager2);

        assertEquals(stateManager1, cacheManager.getStateManager(runId1));
        assertEquals(stateManager2, cacheManager.getStateManager(runId2));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BULK CLEAR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void shouldClearAllCaches() {
        String runId = "run-1";
        WorkflowStateManager stateManager = new WorkflowStateManager();
        cacheManager.storeStateManager(runId, stateManager);
        cacheManager.storePausedWorkflow(runId, new PausedWorkflowState(runId, Instant.now(), null));
        cacheManager.markCoreEvaluated(runId, "core-1");

        cacheManager.clearAllCaches(runId);

        assertNull(cacheManager.getStateManager(runId));
        assertNull(cacheManager.getPausedWorkflow(runId));
        assertFalse(cacheManager.isCoreEvaluated(runId, "core-1"));
    }

    @Test
    void shouldHandleClearingNonExistentRun() {
        assertDoesNotThrow(() -> cacheManager.clearAllCaches("non-existent"));
    }

    @Test
    void shouldReturnLocalStateManagerCount() {
        cacheManager.storeStateManager("run-1", new WorkflowStateManager());
        cacheManager.storeStateManager("run-2", new WorkflowStateManager());

        assertEquals(2, cacheManager.getLocalStateManagerCount());
    }
}

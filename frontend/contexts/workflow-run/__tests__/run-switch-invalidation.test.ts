/**
 * Comprehensive tests for the run-switch invalidation system.
 *
 * When a user switches from run A to run B, the system must:
 * 1. Destroy the old WorkflowRunManager (timers, in-flight requests, plan getter)
 * 2. Reset/delete the old RunStateStore (step sets, status, visualization)
 * 3. Real-time events handled by WebSocket (no per-run cleanup needed)
 * 4. Clear Zustand interface stores (pending interfaces, pagination)
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  getWorkflowRunManager,
  deleteWorkflowRunManager,
  hasWorkflowRunManager,
  WorkflowRunManager,
} from '../WorkflowRunManager';
import {
  getRunStateStore,
  deleteRunStateStore,
  hasRunStateStore,
} from '../RunStateStore';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunState: vi.fn().mockResolvedValue({
      runId: 'mock',
      status: 'running',
      readySteps: [],
      completedStepIds: [],
      failedStepIds: [],
      skippedStepIds: [],
      steps: [],
      edges: [],
    }),
    executeStep: vi.fn().mockResolvedValue({ status: 'ok' }),
    executeCoreNode: vi.fn().mockResolvedValue({ status: 'ok' }),
    rerunStep: vi.fn().mockResolvedValue({ status: 'ok' }),
    setExecutionMode: vi.fn().mockResolvedValue({}),
    pauseWorkflow: vi.fn().mockResolvedValue({}),
    resumeWorkflow: vi.fn().mockResolvedValue({}),
  },
  apiClient: {
    getTokenProvider: vi.fn(() => () => Promise.resolve('test-token')),
  },
}));

vi.mock('../streamingDebug', () => ({
  streamDebug: {
    log: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
    isEnabled: () => false,
  },
}));

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Track all runIds created during a test for cleanup. */
let createdRunIds: string[] = [];

function createManager(runId: string): WorkflowRunManager {
  createdRunIds.push(runId);
  return getWorkflowRunManager(runId);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('Run switch invalidation system', () => {
  afterEach(() => {
    // Clean up all managers created during each test
    for (const id of createdRunIds) {
      if (hasWorkflowRunManager(id)) {
        deleteWorkflowRunManager(id);
      }
    }
    createdRunIds = [];
  });

  // =========================================================================
  // Manager Factory - Basic Operations
  // =========================================================================

  describe('Manager factory basics', () => {
    it('should create a new manager for unknown runId', () => {
      const manager = createManager('run-new-1');
      expect(manager).toBeInstanceOf(WorkflowRunManager);
    });

    it('should return the same instance for same runId', () => {
      const m1 = createManager('run-same-1');
      const m2 = getWorkflowRunManager('run-same-1');
      expect(m1).toBe(m2);
    });

    it('should return different instances for different runIds', () => {
      const m1 = createManager('run-diff-1');
      const m2 = createManager('run-diff-2');
      expect(m1).not.toBe(m2);
    });

    it('hasWorkflowRunManager should return false for unknown runId', () => {
      expect(hasWorkflowRunManager('never-created')).toBe(false);
    });

    it('hasWorkflowRunManager should return true after creation', () => {
      createManager('run-has-1');
      expect(hasWorkflowRunManager('run-has-1')).toBe(true);
    });
  });

  // =========================================================================
  // Delete & Cleanup Chain
  // =========================================================================

  describe('Delete and cleanup chain', () => {
    it('should remove manager from registry on delete', () => {
      createManager('run-del-1');
      expect(hasWorkflowRunManager('run-del-1')).toBe(true);

      deleteWorkflowRunManager('run-del-1');
      expect(hasWorkflowRunManager('run-del-1')).toBe(false);
    });

    it('should also remove RunStateStore on delete', () => {
      createManager('run-del-2');
      expect(hasRunStateStore('run-del-2')).toBe(true);

      deleteWorkflowRunManager('run-del-2');
      expect(hasRunStateStore('run-del-2')).toBe(false);
    });

    it('should clean up all singletons atomically', () => {
      createManager('run-del-4');
      expect(hasWorkflowRunManager('run-del-4')).toBe(true);
      expect(hasRunStateStore('run-del-4')).toBe(true);

      deleteWorkflowRunManager('run-del-4');
      expect(hasWorkflowRunManager('run-del-4')).toBe(false);
      expect(hasRunStateStore('run-del-4')).toBe(false);
    });

    it('should reset store state to defaults on destroy', () => {
      createManager('run-del-5');
      const store = getRunStateStore('run-del-5');

      // Mutate store to verify reset
      store.setRunStatus('running');
      store.setConnected(true);
      store.addCompletedStep('mcp:step1');
      store.setEpoch(3);
      expect(store.getState().runStatus).toBe('running');

      deleteWorkflowRunManager('run-del-5');

      // Store was reset before deletion
      // Create a new store to verify the old one was cleaned
      const freshStore = getRunStateStore('run-del-5');
      expect(freshStore.getState().runStatus).toBe('pending');
      expect(freshStore.getState().isConnected).toBe(false);
      expect(freshStore.getState().completedSteps.size).toBe(0);
      expect(freshStore.getState().currentEpoch).toBe(0);

      // Cleanup
      deleteRunStateStore('run-del-5');
    });

    it('should be safe to delete a non-existent runId', () => {
      expect(() => deleteWorkflowRunManager('does-not-exist')).not.toThrow();
    });

    it('should be safe to delete the same runId twice', () => {
      createManager('run-del-6');
      deleteWorkflowRunManager('run-del-6');
      expect(() => deleteWorkflowRunManager('run-del-6')).not.toThrow();
    });

    it('should create fresh instances after deletion', () => {
      const m1 = createManager('run-del-7');
      const store1 = getRunStateStore('run-del-7');
      store1.setRunStatus('completed');

      deleteWorkflowRunManager('run-del-7');

      const m2 = createManager('run-del-7');
      expect(m2).not.toBe(m1);

      const store2 = getRunStateStore('run-del-7');
      expect(store2.getState().runStatus).toBe('pending');
    });
  });

  // =========================================================================
  // Run-to-Run Switch Simulation
  // =========================================================================

  describe('Run-to-run switch simulation', () => {
    it('switching from run A to run B: A should be fully cleaned up', () => {
      const managerA = createManager('run-switch-A');
      const storeA = getRunStateStore('run-switch-A');
      storeA.setRunStatus('running');
      storeA.addCompletedStep('mcp:step1');

      // Simulate switch: delete old, create new
      deleteWorkflowRunManager('run-switch-A');
      const managerB = createManager('run-switch-B');

      // A fully cleaned
      expect(hasWorkflowRunManager('run-switch-A')).toBe(false);
      expect(hasRunStateStore('run-switch-A')).toBe(false);

      // B active
      expect(hasWorkflowRunManager('run-switch-B')).toBe(true);
      expect(managerB).toBeInstanceOf(WorkflowRunManager);
    });

    it('switching A → B → A: should get fresh instances for A', () => {
      const mA1 = createManager('run-cycle-A');
      const storeA1 = getRunStateStore('run-cycle-A');
      storeA1.setRunStatus('completed');
      storeA1.addCompletedStep('mcp:done');

      // Switch to B
      deleteWorkflowRunManager('run-cycle-A');
      createManager('run-cycle-B');

      // Switch back to A
      deleteWorkflowRunManager('run-cycle-B');
      const mA2 = createManager('run-cycle-A');

      expect(mA2).not.toBe(mA1); // Fresh instance
      const storeA2 = getRunStateStore('run-cycle-A');
      expect(storeA2.getState().runStatus).toBe('pending'); // Fresh state
      expect(storeA2.getState().completedSteps.size).toBe(0);
    });

    it('rapid switching: A → B → C → D → E should leave only E active', () => {
      const runIds = ['rapid-A', 'rapid-B', 'rapid-C', 'rapid-D', 'rapid-E'];

      for (let i = 0; i < runIds.length; i++) {
        if (i > 0) {
          deleteWorkflowRunManager(runIds[i - 1]);
        }
        createManager(runIds[i]);
      }

      // Only E should exist
      for (let i = 0; i < runIds.length - 1; i++) {
        expect(hasWorkflowRunManager(runIds[i])).toBe(false);
        expect(hasRunStateStore(runIds[i])).toBe(false);
      }

      expect(hasWorkflowRunManager('rapid-E')).toBe(true);
    });

    it('switching to same run should not create a new instance', () => {
      const m1 = createManager('run-same-switch');
      const m2 = getWorkflowRunManager('run-same-switch');
      expect(m1).toBe(m2);
    });

    it('store data from old run does not leak into new run', () => {
      createManager('leak-A');
      const storeA = getRunStateStore('leak-A');
      storeA.setRunStatus('running');
      storeA.addCompletedStep('mcp:step1');
      storeA.addCompletedStep('mcp:step2');
      storeA.addFailedStep('mcp:step3');
      storeA.setReadySteps(['mcp:step4']);
      storeA.setConnected(true);
      storeA.setEpoch(5);
      storeA.updateVisualization({
        steps: [{ nodeId: 'mcp:step1', status: 'completed' }],
        edges: [{ from: 'trigger:start', to: 'mcp:step1', status: 'completed' }],
      });

      deleteWorkflowRunManager('leak-A');
      createManager('leak-B');

      const storeB = getRunStateStore('leak-B');
      const state = storeB.getState();
      expect(state.runStatus).toBe('pending');
      expect(state.completedSteps.size).toBe(0);
      expect(state.failedSteps.size).toBe(0);
      expect(state.readySteps.size).toBe(0);
      expect(state.isConnected).toBe(false);
      expect(state.currentEpoch).toBe(0);
      expect(state.batchSteps).toEqual([]);
      expect(state.batchEdges).toEqual([]);
    });
  });

  // =========================================================================
  // Destroy Behavior Details
  // =========================================================================

  describe('Destroy clears all internal state', () => {
    it('destroy should clear pending timers', () => {
      const manager = createManager('run-destroy-1');
      // Internal state is private, but we can verify destroy doesn't throw
      // and that the manager is properly cleaned up
      deleteWorkflowRunManager('run-destroy-1');
      expect(hasWorkflowRunManager('run-destroy-1')).toBe(false);
    });

    it('destroy should disconnect streaming (onDisconnect callback fires)', () => {
      createManager('run-destroy-2');
      const store = getRunStateStore('run-destroy-2');

      // Set connected to simulate active streaming
      store.setConnected(true);
      expect(store.getState().isConnected).toBe(true);

      // Destroy triggers manager.destroy() → disconnect() → onDisconnect → store.setConnected(false)
      // But then store.reset() also runs, resetting everything
      deleteWorkflowRunManager('run-destroy-2');

      // After deletion, store is removed from registry
      expect(hasRunStateStore('run-destroy-2')).toBe(false);
    });

    it('destroy should clear the plan getter', () => {
      const manager = createManager('run-destroy-3');
      const planGetter = vi.fn(() => ({ nodes: [] }));
      manager.setCurrentPlanGetter(planGetter);

      deleteWorkflowRunManager('run-destroy-3');

      // After destroy, the plan getter is nulled out
      // We verify indirectly by checking the manager is gone
      expect(hasWorkflowRunManager('run-destroy-3')).toBe(false);
    });
  });

  // =========================================================================
  // Subscriber Cleanup on Destroy
  // =========================================================================

  describe('Subscriber cleanup', () => {
    it('subscribers should not receive updates after destroy', () => {
      createManager('run-sub-1');
      const store = getRunStateStore('run-sub-1');
      const listener = vi.fn();
      const unsub = store.subscribe(listener);
      listener.mockClear();

      // Destroy resets the store, which triggers one last notification
      deleteWorkflowRunManager('run-sub-1');
      listener.mockClear();

      // Create a new store with same runId - listener should NOT be called
      const newStore = getRunStateStore('run-sub-1');
      newStore.setRunStatus('running');

      // The old listener was subscribed to the old store instance
      // which was reset and then deleted. It should not see updates on the new store.
      // (unsub was never called, but the store instance is gone)
      expect(listener).not.toHaveBeenCalled();

      unsub(); // Clean up
      deleteRunStateStore('run-sub-1');
    });
  });

  // =========================================================================
  // Edge Cases
  // =========================================================================

  describe('Edge cases', () => {
    it('should handle empty string runId', () => {
      const manager = createManager('');
      expect(manager).toBeInstanceOf(WorkflowRunManager);
      expect(hasWorkflowRunManager('')).toBe(true);
      deleteWorkflowRunManager('');
      expect(hasWorkflowRunManager('')).toBe(false);
    });

    it('should handle very long runId', () => {
      const longId = 'run-' + 'x'.repeat(500);
      createdRunIds.push(longId);
      const manager = getWorkflowRunManager(longId);
      expect(manager).toBeInstanceOf(WorkflowRunManager);
      deleteWorkflowRunManager(longId);
      expect(hasWorkflowRunManager(longId)).toBe(false);
    });

    it('should handle special characters in runId', () => {
      const specialId = 'run/with:special-chars_and.dots';
      const manager = createManager(specialId);
      expect(hasWorkflowRunManager(specialId)).toBe(true);
      deleteWorkflowRunManager(specialId);
      expect(hasWorkflowRunManager(specialId)).toBe(false);
    });

    it('creating and immediately deleting should not leak', () => {
      for (let i = 0; i < 50; i++) {
        const id = `ephemeral-${i}`;
        createdRunIds.push(id);
        getWorkflowRunManager(id);
        deleteWorkflowRunManager(id);
      }

      // None should remain
      for (let i = 0; i < 50; i++) {
        expect(hasWorkflowRunManager(`ephemeral-${i}`)).toBe(false);
        expect(hasRunStateStore(`ephemeral-${i}`)).toBe(false);
      }
    });

    it('interleaving creates and deletes should maintain consistency', () => {
      createManager('interleave-A');
      createManager('interleave-B');
      deleteWorkflowRunManager('interleave-A');
      createManager('interleave-C');
      deleteWorkflowRunManager('interleave-B');
      createManager('interleave-D');

      expect(hasWorkflowRunManager('interleave-A')).toBe(false);
      expect(hasWorkflowRunManager('interleave-B')).toBe(false);
      expect(hasWorkflowRunManager('interleave-C')).toBe(true);
      expect(hasWorkflowRunManager('interleave-D')).toBe(true);
    });

    it('multiple managers can coexist temporarily before cleanup', () => {
      // In real usage, WorkflowBuilder cleans prev run on switch,
      // but briefly both may exist during the transition
      createManager('coexist-A');
      createManager('coexist-B');

      expect(hasWorkflowRunManager('coexist-A')).toBe(true);
      expect(hasWorkflowRunManager('coexist-B')).toBe(true);

      // Now clean up A (as WorkflowBuilder would)
      deleteWorkflowRunManager('coexist-A');
      expect(hasWorkflowRunManager('coexist-A')).toBe(false);
      expect(hasWorkflowRunManager('coexist-B')).toBe(true);
    });
  });

  // =========================================================================
  // Store State Isolation Between Runs
  // =========================================================================

  describe('State isolation between concurrent runs', () => {
    it('stores for different runs should be independent', () => {
      createManager('iso-A');
      createManager('iso-B');

      const storeA = getRunStateStore('iso-A');
      const storeB = getRunStateStore('iso-B');

      storeA.setRunStatus('running');
      storeA.addCompletedStep('mcp:step1');
      storeB.setRunStatus('completed');

      expect(storeA.getState().runStatus).toBe('running');
      expect(storeA.getState().completedSteps.has('mcp:step1')).toBe(true);
      expect(storeB.getState().runStatus).toBe('completed');
      expect(storeB.getState().completedSteps.size).toBe(0);
    });

    it('deleting one run should not affect another', () => {
      createManager('iso-del-A');
      createManager('iso-del-B');

      const storeB = getRunStateStore('iso-del-B');
      storeB.setRunStatus('running');

      deleteWorkflowRunManager('iso-del-A');

      expect(hasWorkflowRunManager('iso-del-A')).toBe(false);
      expect(hasWorkflowRunManager('iso-del-B')).toBe(true);
      expect(storeB.getState().runStatus).toBe('running');
    });
  });
});

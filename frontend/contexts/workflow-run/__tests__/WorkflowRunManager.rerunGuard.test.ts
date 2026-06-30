import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WorkflowRunManager } from '../WorkflowRunManager';

// ============================================================================
// Mocks
// ============================================================================

vi.mock('../streamingDebug', () => ({
  streamDebug: {
    log: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
    isEnabled: () => false,
  },
}));

const mockGetRunState = vi.fn();
const mockRerunFromStep = vi.fn();
const mockGetStatusCounts = vi.fn();
const mockTriggerSpecific = vi.fn().mockResolvedValue({ status: 'triggered', readySteps: [] });

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunState: (...args) => mockGetRunState(...args),
    rerunFromStep: (...args) => mockRerunFromStep(...args),
    getStatusCounts: (...args) => mockGetStatusCounts(...args),
    triggerSpecific: (...args) => mockTriggerSpecific(...args),
    executeSingleStep: vi.fn().mockResolvedValue({}),
    pauseWorkflow: vi.fn().mockResolvedValue({}),
    resumeWorkflow: vi.fn().mockResolvedValue({}),
    cancelWorkflow: vi.fn().mockResolvedValue({}),
    setExecutionMode: vi.fn().mockResolvedValue({ readySteps: [] }),
    resolveSignal: vi.fn().mockResolvedValue({ status: 'resolved' }),
  },
}));

vi.mock('@/lib/websocket', () => ({
  wsClient: {
    sendAction: vi.fn().mockResolvedValue(undefined),
  },
}));

vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({
  normalizeLabel: (label: string) =>
    label
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_|_$/g, ''),
}));

// ============================================================================
// Helpers
// ============================================================================

function createRunState(overrides: Record<string, any> = {}) {
  return {
    workflowId: 'wf-1',
    status: 'running',
    executionMode: 'step_by_step',
    readySteps: [],
    completedStepIds: [],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: [],
    steps: [],
    edges: [],
    plan: { triggers: [{ type: 'manual', label: 'Start' }] },
    seq: 0,
    ...overrides,
  };
}

function createRerunResponse(overrides: Record<string, any> = {}) {
  return {
    success: true,
    runId: 'run-1',
    stepId: 'mcp:node2',
    epoch: 2,
    spawn: 1,
    resetSteps: ['mcp:node2', 'mcp:node3'],
    readySteps: ['mcp:node2'],
    status: 'paused',
    seq: 10,
    ...overrides,
  };
}

/** Initialize manager with a basic running state. */
async function initManager(manager: WorkflowRunManager, stateOverrides: Record<string, any> = {}) {
  mockGetRunState.mockResolvedValueOnce(
    createRunState({
      completedStepIds: ['trigger:start', 'mcp:node1', 'mcp:node2', 'mcp:node3'],
      steps: [
        { stepId: 'trigger:start', stepAlias: 'Start', status: 'COMPLETED' },
        { stepId: 'mcp:node1', stepAlias: 'Node 1', status: 'COMPLETED' },
        { stepId: 'mcp:node2', stepAlias: 'Node 2', status: 'COMPLETED' },
        { stepId: 'mcp:node3', stepAlias: 'Node 3', status: 'COMPLETED' },
      ],
      edges: [
        { from: 'trigger:start', to: 'mcp:node1', completedCount: 1 },
        { from: 'mcp:node1', to: 'mcp:node2', completedCount: 1 },
        { from: 'mcp:node2', to: 'mcp:node3', completedCount: 1 },
      ],
      seq: 5,
      ...stateOverrides,
    })
  );
  await manager.initialize();
}

// ============================================================================
// Tests
// ============================================================================

describe('WorkflowRunManager - Seq-Based Stale Event Filtering', () => {
  let manager: WorkflowRunManager;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    manager = new WorkflowRunManager('run-1');
  });

  afterEach(() => {
    manager.destroy();
    vi.useRealTimers();
  });

  // ==========================================================================
  // Basic seq-based filtering
  // ==========================================================================

  describe('Basic seq-based filtering', () => {
    it('should update state after rerunStep()', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      const state = manager.getState();
      // node2 should be ready, node3 should be reset (not completed)
      expect(state.readySteps.has('mcp:node2')).toBe(true);
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.completedSteps.has('mcp:node3')).toBe(false);
    });

    it('should discard stale batch-update with lower seq', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // Simulate stale batch-update arriving with seq < 10 (pre-rerun data)
      manager.handleBatchUpdate({
        seq: 7,
        steps: [
          { id: 'mcp:node2', status: 'completed' },
          { id: 'mcp:node3', status: 'completed' },
        ],
      });

      // Flush the debounce timer (200ms)
      vi.advanceTimersByTime(200);

      const state = manager.getState();
      // node2 and node3 should NOT be re-completed (stale event discarded)
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.completedSteps.has('mcp:node3')).toBe(false);
      // node2 should still be ready
      expect(state.readySteps.has('mcp:node2')).toBe(true);
    });

    it('should filter reset steps from batch-update with equal seq (stale NodeCounts)', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // Batch-update with same seq as rerun carries post-rerun snapshot data.
      // But reset steps still show as "completed" due to NodeCounts (never reset).
      // These should be filtered until a batch with seq > rerunSeq arrives.
      manager.handleBatchUpdate({
        seq: 10,
        steps: [
          { id: 'mcp:node2', status: 'completed' },
        ],
        completedStepIds: ['mcp:node2'],
      });

      vi.advanceTimersByTime(200);

      // mcp:node2 was reset by rerun - should NOT appear as completed at same seq
      expect(manager.getState().completedSteps.has('mcp:node2')).toBe(false);
      expect(manager.getState().readySteps.has('mcp:node2')).toBe(true);
    });

    it('should process both batch-update and readySteps with same seq', async () => {
      await initManager(manager);

      // This is the key scenario: backend sends batch-update and readySteps
      // as separate WS events with the SAME seq. Both must be processed.
      manager.handleBatchUpdate({
        seq: 8,
        steps: [
          { id: 'mcp:node1', status: 'completed' },
        ],
        completedStepIds: ['mcp:node1'],
      });

      // readySteps arrives with same seq - must NOT be discarded
      manager.handleEvent('readySteps', {
        seq: 8,
        readySteps: ['mcp:node2'],
      });

      vi.advanceTimersByTime(200);

      const state = manager.getState();
      expect(state.completedSteps.has('mcp:node1')).toBe(true);
      expect(state.readySteps.has('mcp:node2')).toBe(true);
    });

    it('should accept batch-update with higher seq', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // Fresh batch-update with seq > 10 (post-rerun execution)
      manager.handleBatchUpdate({
        seq: 11,
        steps: [
          { id: 'mcp:node2', status: 'completed' },
        ],
        completedStepIds: ['mcp:node2'],
      });

      vi.advanceTimersByTime(200);

      expect(manager.getState().completedSteps.has('mcp:node2')).toBe(true);
    });

    it('should accept batch-update with failed status when seq is higher', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // Stale batch with failed status (lower seq) - discarded
      manager.handleBatchUpdate({
        seq: 8,
        steps: [
          { id: 'mcp:node2', status: 'failed' },
          { id: 'mcp:node3', status: 'error' },
        ],
      });
      vi.advanceTimersByTime(200);
      expect(manager.getState().failedSteps.has('mcp:node2')).toBe(false);

      // Fresh batch with failed status (higher seq) - accepted
      manager.handleBatchUpdate({
        seq: 12,
        steps: [
          { id: 'mcp:node2', status: 'failed' },
        ],
        failedStepIds: ['mcp:node2'],
      });
      vi.advanceTimersByTime(200);
      expect(manager.getState().failedSteps.has('mcp:node2')).toBe(true);
    });

    it('should handle batch-update without seq (legacy/backwards-compatible)', async () => {
      await initManager(manager);

      // Batch-update without seq field should still be processed
      manager.handleBatchUpdate({
        steps: [
          { id: 'mcp:node1', status: 'completed' },
        ],
        completedStepIds: ['mcp:node1'],
      });

      vi.advanceTimersByTime(200);

      expect(manager.getState().completedSteps.has('mcp:node1')).toBe(true);
    });
  });

  // ==========================================================================
  // Seq-based filtering for readySteps events
  // ==========================================================================

  describe('ReadySteps seq filtering', () => {
    it('should discard stale readySteps event with lower seq', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // Stale readySteps event (pre-rerun)
      manager.handleEvent('readySteps', {
        seq: 7,
        readySteps: ['mcp:node3'],
      });

      const state = manager.getState();
      // Should NOT have updated readySteps from stale event
      expect(state.readySteps.has('mcp:node3')).toBe(false);
      expect(state.readySteps.has('mcp:node2')).toBe(true);
    });

    it('should accept readySteps event with higher seq', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // Fresh readySteps event (post-rerun execution)
      manager.handleEvent('readySteps', {
        seq: 11,
        readySteps: ['mcp:node3'],
      });

      const state = manager.getState();
      expect(state.readySteps.has('mcp:node3')).toBe(true);
    });

    it('should accept readySteps event without seq', async () => {
      await initManager(manager);

      // Legacy readySteps event without seq - should still be processed
      manager.handleEvent('readySteps', {
        readySteps: ['mcp:node2'],
      });

      const state = manager.getState();
      expect(state.readySteps.has('mcp:node2')).toBe(true);
    });
  });

  // ==========================================================================
  // Seq updated from API responses
  // ==========================================================================

  describe('Seq updated from API responses', () => {
    it('should update lastKnownSeq from rerun response', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 15 }));

      await manager.rerunStep('mcp:node2');

      // Batch-update with seq 14 should be discarded (rerun set seq to 15)
      manager.handleBatchUpdate({
        seq: 14,
        steps: [{ id: 'mcp:node2', status: 'completed' }],
      });
      vi.advanceTimersByTime(200);

      expect(manager.getState().completedSteps.has('mcp:node2')).toBe(false);
    });

    it('should update lastKnownSeq from getRunState on refresh', async () => {
      await initManager(manager);

      // Refresh returns seq: 20
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          seq: 20,
          completedStepIds: ['trigger:start', 'mcp:node1'],
          readySteps: ['mcp:node2'],
        })
      );
      await manager.refresh();

      // Batch-update with seq 18 should be discarded
      manager.handleBatchUpdate({
        seq: 18,
        steps: [{ id: 'mcp:node2', status: 'completed' }],
      });
      vi.advanceTimersByTime(200);

      expect(manager.getState().completedSteps.has('mcp:node2')).toBe(false);
      expect(manager.getState().readySteps.has('mcp:node2')).toBe(true);
    });

    it('should update lastKnownSeq on initialize', async () => {
      mockGetRunState.mockResolvedValueOnce(
        createRunState({ seq: 8, readySteps: ['mcp:node1'] })
      );
      await manager.initialize();

      // Stale batch-update with seq < 8 should be discarded
      manager.handleBatchUpdate({
        seq: 6,
        steps: [{ id: 'mcp:node1', status: 'completed' }],
      });
      vi.advanceTimersByTime(200);

      expect(manager.getState().completedSteps.has('mcp:node1')).toBe(false);
    });
  });

  // ==========================================================================
  // stepRerun WS event handling
  // ==========================================================================

  describe('stepRerun WS event', () => {
    it('should always refresh on stepRerun event (seq protects state)', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // Simulate stepRerun WS event (even self-initiated, refresh is safe now)
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          seq: 10,
          completedStepIds: ['trigger:start', 'mcp:node1'],
          readySteps: ['mcp:node2'],
        })
      );
      manager.handleEvent('stepRerun', { stepId: 'mcp:node2' });

      // Wait for the async refresh
      await vi.advanceTimersByTimeAsync(100);

      // getRunState should be called: init + refresh = 2
      expect(mockGetRunState).toHaveBeenCalledTimes(2);
    });

    it('should refresh for external rerun event', async () => {
      await initManager(manager);

      // No rerun was initiated - simulate external stepRerun WS event
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          seq: 12,
          completedStepIds: ['trigger:start', 'mcp:node1'],
          readySteps: ['mcp:node2'],
        })
      );

      manager.handleEvent('stepRerun', { stepId: 'mcp:node2' });

      await vi.advanceTimersByTimeAsync(100);

      expect(mockGetRunState).toHaveBeenCalledTimes(2); // init + refresh
    });
  });

  // ==========================================================================
  // Pending batch-update cleared on rerun
  // ==========================================================================

  describe('Pending batch-update cleared on rerun', () => {
    it('should cancel pending debounced batch-update when rerun fires', async () => {
      await initManager(manager);

      // Enqueue a batch-update that would mark node2 completed (pre-rerun state)
      manager.handleBatchUpdate({
        seq: 6,
        steps: [
          { id: 'mcp:node2', status: 'completed' },
          { id: 'mcp:node3', status: 'completed' },
        ],
      });

      // Don't flush the debounce yet (still pending at 0ms)

      // Now rerun fires - should cancel the pending batch-update
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));
      await manager.rerunStep('mcp:node2');

      // Flush well past the debounce timer - the pending batch should have been cancelled
      vi.advanceTimersByTime(500);

      const state = manager.getState();
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.completedSteps.has('mcp:node3')).toBe(false);
      expect(state.readySteps.has('mcp:node2')).toBe(true);
    });

    it('should handle multiple rapid batch-updates before rerun', async () => {
      await initManager(manager);

      // Multiple batch-updates arrive rapidly (debounce coalesces)
      manager.handleBatchUpdate({
        seq: 6,
        steps: [{ id: 'mcp:node2', status: 'running' }],
      });
      manager.handleBatchUpdate({
        seq: 7,
        steps: [{ id: 'mcp:node2', status: 'completed' }],
      });
      manager.handleBatchUpdate({
        seq: 8,
        steps: [
          { id: 'mcp:node2', status: 'completed' },
          { id: 'mcp:node3', status: 'completed' },
        ],
      });

      // Rerun clears all pending
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));
      await manager.rerunStep('mcp:node2');

      vi.advanceTimersByTime(500);

      const state = manager.getState();
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.completedSteps.has('mcp:node3')).toBe(false);
    });
  });

  // ==========================================================================
  // Multiple sequential reruns
  // ==========================================================================

  describe('Multiple sequential reruns', () => {
    it('should bump lastKnownSeq on each rerun', async () => {
      await initManager(manager);

      // First rerun: seq = 10
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));
      await manager.rerunStep('mcp:node2');

      // Second rerun: seq = 15
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        resetSteps: ['mcp:node3'],
        readySteps: ['mcp:node3'],
        stepId: 'mcp:node3',
        seq: 15,
      }));
      await manager.rerunStep('mcp:node3');

      // Batch-update with seq=12 (between the two reruns) - stale, discarded
      manager.handleBatchUpdate({
        seq: 12,
        steps: [
          { id: 'mcp:node2', status: 'completed' },
          { id: 'mcp:node3', status: 'completed' },
        ],
      });
      vi.advanceTimersByTime(200);

      const state = manager.getState();
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.completedSteps.has('mcp:node3')).toBe(false);
    });

    it('should handle rapid sequential reruns', async () => {
      await initManager(manager);

      // 10 sequential reruns with increasing seq
      for (let i = 0; i < 10; i++) {
        mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
          resetSteps: [`mcp:node${i}`],
          readySteps: [`mcp:node${i}`],
          stepId: `mcp:node${i}`,
          epoch: i + 1,
          seq: 10 + i,
        }));

        await manager.rerunStep(`mcp:node${i}`);
      }

      // Batch-update with seq=15 (between reruns) - stale
      manager.handleBatchUpdate({
        seq: 15,
        steps: [
          { id: 'mcp:node0', status: 'completed' },
          { id: 'mcp:node9', status: 'completed' },
        ],
      });
      vi.advanceTimersByTime(200);

      const state = manager.getState();
      // seq=15 < lastKnownSeq=19 → discarded entirely
      expect(state.completedSteps.has('mcp:node0')).toBe(false);
      expect(state.completedSteps.has('mcp:node9')).toBe(false);
    });
  });

  // ==========================================================================
  // Trigger rerun
  // ==========================================================================

  describe('Trigger rerun', () => {
    it('should discard stale batch-update after trigger rerun via seq', async () => {
      await initManager(manager);

      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        stepId: 'trigger:start',
        resetSteps: ['trigger:start', 'mcp:node1', 'mcp:node2', 'mcp:node3'],
        readySteps: ['trigger:start'],
        seq: 10,
      }));

      await manager.rerunStep('trigger:start');

      // Stale batch-update tries to re-complete everything (seq < 10)
      manager.handleBatchUpdate({
        seq: 7,
        steps: [
          { id: 'trigger:start', status: 'completed' },
          { id: 'mcp:node1', status: 'completed' },
          { id: 'mcp:node2', status: 'completed' },
          { id: 'mcp:node3', status: 'completed' },
        ],
      });
      vi.advanceTimersByTime(200);

      const state = manager.getState();
      expect(state.completedSteps.has('trigger:start')).toBe(false);
      expect(state.completedSteps.has('mcp:node1')).toBe(false);
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.completedSteps.has('mcp:node3')).toBe(false);
      expect(state.readySteps.has('trigger:start')).toBe(true);
    });
  });

  // ==========================================================================
  // Edge cases
  // ==========================================================================

  describe('Edge cases', () => {
    it('should handle rerun with empty resetSteps', async () => {
      await initManager(manager);

      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        resetSteps: [],
        readySteps: ['mcp:node2'],
        seq: 10,
      }));

      await manager.rerunStep('mcp:node2');

      // Fresh batch-update (seq > 10) should go through
      manager.handleBatchUpdate({
        seq: 11,
        steps: [{ id: 'mcp:node2', status: 'completed' }],
        completedStepIds: ['mcp:node2'],
      });
      vi.advanceTimersByTime(200);

      expect(manager.getState().completedSteps.has('mcp:node2')).toBe(true);
    });

    it('should handle rerun with undefined seq (backwards-compat)', async () => {
      await initManager(manager);

      mockRerunFromStep.mockResolvedValueOnce({
        success: true,
        runId: 'run-1',
        stepId: 'mcp:node2',
        epoch: 2,
        spawn: 1,
        readySteps: ['mcp:node2'],
        status: 'paused',
        // seq is undefined
      });

      await manager.rerunStep('mcp:node2');

      // No crash, batch-update without seq still processed
      manager.handleBatchUpdate({
        steps: [{ id: 'mcp:node2', status: 'completed' }],
        completedStepIds: ['mcp:node2'],
      });
      vi.advanceTimersByTime(200);

      expect(manager.getState().completedSteps.has('mcp:node2')).toBe(true);
    });

    it('should handle batch-update with nodeId instead of id', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // Stale batch-update using nodeId format (seq < 10)
      manager.handleBatchUpdate({
        seq: 8,
        steps: [
          { nodeId: 'mcp:node2', status: 'completed' },
          { nodeId: 'mcp:node3', status: 'completed' },
        ],
      });
      vi.advanceTimersByTime(200);

      const state = manager.getState();
      // Entire batch discarded due to seq
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.completedSteps.has('mcp:node3')).toBe(false);
    });

    it('should handle destroy without errors', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // Destroy should not throw
      expect(() => manager.destroy()).not.toThrow();
    });

    it('should handle batch-update with no steps field', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // Batch-update with only edges (no steps) and higher seq
      manager.handleBatchUpdate({
        seq: 11,
        edges: [{ from: 'mcp:node1', to: 'mcp:node2', status: 'pending' }],
      });
      vi.advanceTimersByTime(200);

      // Should not crash
      const state = manager.getState();
      expect(state.readySteps.has('mcp:node2')).toBe(true);
    });

    it('should handle null batch-update data gracefully', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // null data should be ignored
      manager.handleBatchUpdate(null);
      vi.advanceTimersByTime(200);

      expect(manager.getState().readySteps.has('mcp:node2')).toBe(true);
    });
  });

  // ==========================================================================
  // Stress tests
  // ==========================================================================

  describe('Stress tests', () => {
    it('should discard all stale batch-updates in a rapid storm', async () => {
      await initManager(manager);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));

      await manager.rerunStep('mcp:node2');

      // Simulate 50 rapid stale batch-updates (all with seq < 10)
      for (let i = 0; i < 50; i++) {
        manager.handleBatchUpdate({
          seq: 5 + (i % 5), // seq: 5, 6, 7, 8, 9
          steps: [
            { id: 'mcp:node2', status: 'completed' },
            { id: 'mcp:node3', status: 'completed' },
            { id: 'mcp:node1', status: 'completed' },
          ],
        });
      }

      // Flush all debounce timers
      vi.advanceTimersByTime(500);

      const state = manager.getState();
      // All stale events discarded - no steps re-completed
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.completedSteps.has('mcp:node3')).toBe(false);
      // node1 was in initial completedStepIds and NOT in resetSteps,
      // so it stays completed from init (rerun only reset node2 + node3)
      expect(state.completedSteps.has('mcp:node1')).toBe(true);
    });

    it('should handle large resetSteps set (many nodes in DAG)', async () => {
      await initManager(manager);

      const largeResetSteps = Array.from({ length: 100 }, (_, i) => `mcp:node_${i}`);
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        resetSteps: largeResetSteps,
        readySteps: ['mcp:node_0'],
        seq: 10,
      }));

      await manager.rerunStep('mcp:node_0');

      // Stale batch-update with seq < 10 - entirely discarded
      manager.handleBatchUpdate({
        seq: 8,
        steps: largeResetSteps.map(id => ({ id, status: 'completed' })),
      });
      vi.advanceTimersByTime(200);

      const state = manager.getState();
      for (const stepId of largeResetSteps) {
        expect(state.completedSteps.has(stepId)).toBe(false);
      }
    });

    it('should handle interleaved batch-updates and reruns', async () => {
      await initManager(manager);

      // Batch-update arrives (seq=6, which is > init seq=5 - accepted)
      manager.handleBatchUpdate({
        seq: 6,
        steps: [{ id: 'mcp:node1', status: 'completed' }],
        completedStepIds: ['trigger:start', 'mcp:node1', 'mcp:node2', 'mcp:node3'],
      });

      // Rerun fires before batch-update debounce resolves (clears pending batch)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        resetSteps: ['mcp:node2', 'mcp:node3'],
        readySteps: ['mcp:node2'],
        seq: 10,
      }));
      await manager.rerunStep('mcp:node2');

      // Another stale batch-update arrives (seq < 10)
      manager.handleBatchUpdate({
        seq: 8,
        steps: [
          { id: 'mcp:node2', status: 'completed' },
          { id: 'mcp:node3', status: 'failed' },
        ],
      });

      vi.advanceTimersByTime(500);

      const state = manager.getState();
      // First batch was cancelled by rerun (pending batch cleared)
      // Second batch discarded by seq
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.failedSteps.has('mcp:node3')).toBe(false);
    });
  });

  // ==========================================================================
  // Integration: full rerun flow
  // ==========================================================================

  describe('Integration: full rerun flow', () => {
    it('should maintain correct state through rerun → stale events → fresh events', async () => {
      await initManager(manager);

      // Step 1: Rerun node2 (seq bumps to 10)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        resetSteps: ['mcp:node2', 'mcp:node3'],
        readySteps: ['mcp:node2'],
        epoch: 2,
        seq: 10,
      }));
      await manager.rerunStep('mcp:node2');

      let state = manager.getState();
      expect(state.readySteps.has('mcp:node2')).toBe(true);
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.completedSteps.has('mcp:node3')).toBe(false);
      expect(state.currentEpoch).toBe(2);

      // Step 2: Stale batch arrives (seq=8) - discarded
      manager.handleBatchUpdate({
        seq: 8,
        steps: [
          { id: 'mcp:node2', status: 'completed' },
          { id: 'mcp:node3', status: 'completed' },
        ],
      });
      vi.advanceTimersByTime(200);

      state = manager.getState();
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.completedSteps.has('mcp:node3')).toBe(false);

      // Step 3: Fresh batch from post-rerun execution (seq=11) - accepted
      manager.handleBatchUpdate({
        seq: 11,
        steps: [
          { id: 'mcp:node2', status: 'completed' },
        ],
        completedStepIds: ['mcp:node2'],
      });
      vi.advanceTimersByTime(200);

      state = manager.getState();
      expect(state.completedSteps.has('mcp:node2')).toBe(true);
      expect(state.completedSteps.has('mcp:node3')).toBe(false); // not yet

      // Step 4: node3 completes (seq=12)
      manager.handleBatchUpdate({
        seq: 12,
        steps: [{ id: 'mcp:node3', status: 'completed' }],
        completedStepIds: ['mcp:node2', 'mcp:node3'],
      });
      vi.advanceTimersByTime(200);

      state = manager.getState();
      expect(state.completedSteps.has('mcp:node3')).toBe(true);
    });

    it('should handle refresh after rerun (seq protects, no guard skip needed)', async () => {
      await initManager(manager);

      // Rerun with seq=10
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({ seq: 10 }));
      await manager.rerunStep('mcp:node2');

      // Refresh should work immediately (no guard blocking)
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          seq: 10,
          completedStepIds: ['trigger:start', 'mcp:node1'],
          readySteps: ['mcp:node2'],
        })
      );
      await manager.refresh();

      // getRunState: init + refresh = 2
      expect(mockGetRunState).toHaveBeenCalledTimes(2);

      const state = manager.getState();
      expect(state.readySteps.has('mcp:node2')).toBe(true);
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
    });
  });

  // ==========================================================================
  // Stale API refresh guard (refreshStateInternal seq check)
  // ==========================================================================

  describe('Stale API refresh guard', () => {
    /**
     * Core scenario: stepRerun WS event triggers refreshStateInternal().
     * Between the rerun and the API response, the user clicks "execute" (ready)
     * on the decision node. The batch-update from execution arrives first (seq=M+1),
     * correctly adding the decision node to completedSteps/evaluatedCores.
     * Then the stale API response arrives with seq=M (pre-execution state).
     * Without the guard, initializeFromApi() would overwrite correct state.
     */
    it('should skip stale API refresh after batch-update advances seq (decision rerun bug)', async () => {
      // Init: trigger → core:if_else completed, seq=5
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          completedStepIds: ['trigger:start', 'mcp:node1', 'core:if_else'],
          steps: [
            { stepId: 'trigger:start', stepAlias: 'Start', status: 'COMPLETED' },
            { stepId: 'mcp:node1', stepAlias: 'Node 1', status: 'COMPLETED' },
            { stepId: 'core:if_else', stepAlias: 'If Else', status: 'COMPLETED' },
          ],
          seq: 5,
        })
      );
      await manager.initialize();

      // Verify initial state: core:if_else is completed
      expect(manager.getState().completedSteps.has('core:if_else')).toBe(true);
      expect(manager.getState().evaluatedCores.has('core:if_else')).toBe(true);

      // Step 1: Rerun core:if_else (seq→15)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        stepId: 'core:if_else',
        resetSteps: ['core:if_else', 'mcp:node2'],
        readySteps: ['core:if_else'],
        epoch: 2,
        seq: 15,
      }));
      await manager.rerunStep('core:if_else');

      let state = manager.getState();
      expect(state.readySteps.has('core:if_else')).toBe(true);
      expect(state.completedSteps.has('core:if_else')).toBe(false);
      expect(state.evaluatedCores.has('core:if_else')).toBe(false);

      // Step 2: User clicks "execute" on core:if_else.
      // Backend evaluates the decision and sends batch-update with seq=16.
      manager.handleBatchUpdate({
        seq: 16,
        steps: [{ id: 'core:if_else', status: 'completed' }],
        completedStepIds: ['trigger:start', 'mcp:node1', 'core:if_else'],
      });
      vi.advanceTimersByTime(200);

      state = manager.getState();
      expect(state.completedSteps.has('core:if_else')).toBe(true);
      expect(state.evaluatedCores.has('core:if_else')).toBe(true);

      // Step 3: Stale API response from stepRerun WS event arrives (seq=15, pre-execution).
      // Mock the stale API call - has core:if_else in readySteps, NOT in completedStepIds.
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          completedStepIds: ['trigger:start', 'mcp:node1'],
          readySteps: ['core:if_else'],
          seq: 15,  // Stale: older than lastKnownSeq=16
        })
      );

      // Simulate the stepRerun WS event → calls refreshStateInternal() (no force)
      manager.handleEvent('stepRerun', { stepId: 'core:if_else' });
      // Wait for the async refresh to complete
      await vi.advanceTimersByTimeAsync(100);

      // The stale refresh should have been SKIPPED.
      // core:if_else should still be in completedSteps (not overwritten by stale initializeFromApi).
      state = manager.getState();
      expect(state.completedSteps.has('core:if_else')).toBe(true);
      expect(state.evaluatedCores.has('core:if_else')).toBe(true);
      // completedStepIds from stale API had only ['trigger:start', 'mcp:node1'] -
      // if stale refresh was applied, core:if_else would have been removed from completedSteps.
      // The fact that it's still there proves the guard worked.
    });

    /**
     * Force refresh (user-initiated) bypasses the seq guard.
     * Even if the API returns a lower seq, force=true applies it.
     */
    it('should apply API data when force=true even with lower seq', async () => {
      await initManager(manager);

      // Advance seq via batch-update
      manager.handleBatchUpdate({
        seq: 20,
        steps: [{ id: 'mcp:node1', status: 'completed' }],
        completedStepIds: ['mcp:node1'],
      });
      vi.advanceTimersByTime(200);

      // Force refresh returns lower seq (e.g., eventual consistency)
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          completedStepIds: ['trigger:start'],
          readySteps: ['mcp:node1'],
          seq: 18,  // Lower than lastKnownSeq=20
        })
      );
      await manager.refresh(); // force=true

      // Despite lower seq, force refresh applies the data
      const state = manager.getState();
      expect(state.readySteps.has('mcp:node1')).toBe(true);
    });

    /**
     * API refresh with equal seq should be applied (not stale).
     */
    it('should apply API data when seq equals lastKnownSeq', async () => {
      await initManager(manager); // seq=5

      // Stale refresh with seq=5 (equal) - should be applied
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          completedStepIds: ['trigger:start', 'mcp:node1'],
          readySteps: ['mcp:node2'],
          seq: 5,
        })
      );
      manager.handleEvent('stepRerun', { stepId: 'mcp:node2' });
      await vi.advanceTimersByTimeAsync(100);

      const state = manager.getState();
      // Equal seq should NOT be skipped
      expect(state.readySteps.has('mcp:node2')).toBe(true);
    });

    /**
     * API refresh with higher seq should always be applied.
     */
    it('should apply API data when seq is higher than lastKnownSeq', async () => {
      await initManager(manager); // seq=5

      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          completedStepIds: ['trigger:start'],
          readySteps: ['mcp:node1'],
          seq: 10,  // Higher
        })
      );
      manager.handleEvent('stepRerun', { stepId: 'mcp:node1' });
      await vi.advanceTimersByTimeAsync(100);

      const state = manager.getState();
      expect(state.readySteps.has('mcp:node1')).toBe(true);
      expect(state.completedSteps.has('mcp:node1')).toBe(false);
    });

    /**
     * Multiple stale refreshes in flight: only the first one that returns
     * fresh data should apply; subsequent stale ones should be skipped.
     */
    it('should handle multiple concurrent stale refreshes correctly', async () => {
      await initManager(manager); // seq=5

      // Rerun → seq=20
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        resetSteps: ['mcp:node2', 'mcp:node3'],
        readySteps: ['mcp:node2'],
        seq: 20,
      }));
      await manager.rerunStep('mcp:node2');

      // Execute completes → seq=21
      manager.handleBatchUpdate({
        seq: 21,
        steps: [{ id: 'mcp:node2', status: 'completed' }],
        completedStepIds: ['trigger:start', 'mcp:node1', 'mcp:node2'],
      });
      vi.advanceTimersByTime(200);

      expect(manager.getState().completedSteps.has('mcp:node2')).toBe(true);

      // 3 stale stepRerun WS events arrive (from delayed WS delivery)
      // Each triggers refreshStateInternal() with stale data
      for (let i = 0; i < 3; i++) {
        mockGetRunState.mockResolvedValueOnce(
          createRunState({
            completedStepIds: ['trigger:start', 'mcp:node1'],
            readySteps: ['mcp:node2'],
            seq: 20,  // All stale (< 21)
          })
        );
        manager.handleEvent('stepRerun', { stepId: 'mcp:node2' });
      }
      await vi.advanceTimersByTimeAsync(100);

      // All 3 should be skipped - mcp:node2 remains completed
      const state = manager.getState();
      expect(state.completedSteps.has('mcp:node2')).toBe(true);
      // If any stale refresh was applied, completedSteps would only have
      // ['trigger:start', 'mcp:node1'] and mcp:node2 would be gone.
    });
  });

  // ==========================================================================
  // Rerun cancels all pending timers (POST_COMPLETION_REFRESH race fix)
  // ==========================================================================

  describe('Rerun cancels all pending timers', () => {
    it('should cancel POST_COMPLETION_REFRESH timer on rerun', async () => {
      await initManager(manager);

      // Simulate workflow completion → schedules POST_COMPLETION_REFRESH (500ms)
      manager.handleEvent('workflowStatus', {
        status: 'completed',
        message: 'Workflow completed',
      });

      // User clicks rerun within 500ms
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        resetSteps: ['trigger:start', 'mcp:node1', 'mcp:node2', 'mcp:node3'],
        readySteps: ['trigger:start'],
        seq: 10,
      }));
      await manager.rerunStep('trigger:start');

      // Advance past POST_COMPLETION_REFRESH timer (500ms)
      vi.advanceTimersByTime(600);

      // The refresh should NOT have fired (timer was cancelled by rerunStep)
      // Init + no refresh = 1 call
      expect(mockGetRunState).toHaveBeenCalledTimes(1);

      // State should still be correct from rerun (not overwritten by stale refresh)
      const state = manager.getState();
      expect(state.readySteps.has('trigger:start')).toBe(true);
      expect(state.completedSteps.has('mcp:node1')).toBe(false);
    });

    it('should cancel safety refresh timer on rerun', async () => {
      // Init with waiting_trigger state so trigger can be executed
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          status: 'waiting_trigger',
          completedStepIds: [],
          readySteps: ['trigger:start'],
          steps: [],
          seq: 3,
        })
      );
      await manager.initialize();

      // Execute trigger (schedules safety refresh at 3s)
      mockTriggerSpecific.mockResolvedValueOnce({
        status: 'triggered',
        readySteps: ['mcp:node1'],
        epoch: 1,
      });
      await manager.executeStep('trigger:start');

      // User clicks rerun within 3s
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        resetSteps: ['trigger:start', 'mcp:node1'],
        readySteps: ['trigger:start'],
        seq: 10,
      }));
      await manager.rerunStep('trigger:start');

      // Advance past safety refresh (3s)
      vi.advanceTimersByTime(3100);

      // Safety refresh should NOT have fired (cancelled by rerunStep)
      // Init call only
      expect(mockGetRunState).toHaveBeenCalledTimes(1);
    });
  });

  // ==========================================================================
  // Trigger re-fire clears readySteps from terminal sets (SBS mode)
  // ==========================================================================

  describe('Trigger re-fire in SBS mode', () => {
    it('should set readySteps from trigger API response (no optimistic completedSteps)', async () => {
      // Init with completed state (trigger + node both completed)
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          status: 'completed',
          completedStepIds: ['trigger:start', 'mcp:node1'],
          readySteps: ['trigger:start'],
          steps: [
            { stepId: 'trigger:start', stepAlias: 'Start', status: 'COMPLETED' },
            { stepId: 'mcp:node1', stepAlias: 'Node 1', status: 'COMPLETED' },
          ],
          seq: 5,
        })
      );
      await manager.initialize();

      // Verify initial state: node1 is completed
      expect(manager.getState().completedSteps.has('mcp:node1')).toBe(true);

      // Execute trigger (re-fire) - backend returns node1 as ready
      mockTriggerSpecific.mockResolvedValueOnce({
        status: 'triggered',
        readySteps: ['mcp:node1'],
        epoch: 2,
      });
      await manager.executeStep('trigger:start');

      const state = manager.getState();
      // readySteps are set from API response (authoritative)
      expect(state.readySteps.has('mcp:node1')).toBe(true);
      // No optimistic addCompletedStep - trigger completion comes via backend batch-update
      // No optimistic clearStepFromTerminalSets - backend batch-update will update completedStepIds
    });

    it('should set readySteps even when node was previously failed', async () => {
      // Init with node1 failed
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          status: 'failed',
          completedStepIds: ['trigger:start'],
          failedStepIds: ['mcp:node1'],
          readySteps: ['trigger:start'],
          steps: [
            { stepId: 'trigger:start', stepAlias: 'Start', status: 'COMPLETED' },
            { stepId: 'mcp:node1', stepAlias: 'Node 1', status: 'FAILED' },
          ],
          seq: 5,
        })
      );
      await manager.initialize();

      expect(manager.getState().failedSteps.has('mcp:node1')).toBe(true);

      // Execute trigger (re-fire) - backend returns node1 as ready
      mockTriggerSpecific.mockResolvedValueOnce({
        status: 'triggered',
        readySteps: ['mcp:node1'],
        epoch: 2,
      });
      await manager.executeStep('trigger:start');

      const state = manager.getState();
      // readySteps are set from API response (authoritative)
      expect(state.readySteps.has('mcp:node1')).toBe(true);
      // No optimistic clearStepFromTerminalSets - backend batch-update will clear failedStepIds
    });

    it('should reproduce the exact bug scenario: trigger→node→rerun→re-fire', async () => {
      // Init: fresh workflow with trigger ready
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          status: 'waiting_trigger',
          completedStepIds: [],
          readySteps: ['trigger:start'],
          steps: [],
          seq: 1,
        })
      );
      await manager.initialize();

      // Step 1: Execute trigger - readySteps set from API response
      mockTriggerSpecific.mockResolvedValueOnce({
        status: 'triggered',
        readySteps: ['mcp:node1'],
        epoch: 1,
      });
      await manager.executeStep('trigger:start');

      let state = manager.getState();
      // No optimistic addCompletedStep - trigger completion comes via batch-update
      expect(state.readySteps.has('mcp:node1')).toBe(true);

      // Step 2: Backend confirms trigger completed and node1 completes (via batch-update)
      manager.handleBatchUpdate({
        seq: 3,
        steps: [
          { id: 'trigger:start', status: 'completed' },
          { id: 'mcp:node1', status: 'completed' },
        ],
        completedStepIds: ['trigger:start', 'mcp:node1'],
      });
      vi.advanceTimersByTime(200);

      state = manager.getState();
      expect(state.completedSteps.has('trigger:start')).toBe(true);
      expect(state.completedSteps.has('mcp:node1')).toBe(true);

      // Step 3: Rerun trigger - reset all (seq bumps to 10)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        stepId: 'trigger:start',
        resetSteps: ['trigger:start', 'mcp:node1'],
        readySteps: ['trigger:start'],
        epoch: 2,
        seq: 10,
      }));
      await manager.rerunStep('trigger:start');

      state = manager.getState();
      expect(state.completedSteps.has('trigger:start')).toBe(false);
      expect(state.completedSteps.has('mcp:node1')).toBe(false);
      expect(state.readySteps.has('trigger:start')).toBe(true);

      // Step 4: Execute trigger again (re-fire) → node1 should be READY
      mockTriggerSpecific.mockResolvedValueOnce({
        status: 'triggered',
        readySteps: ['mcp:node1'],
        epoch: 2,
      });
      await manager.executeStep('trigger:start');

      state = manager.getState();
      // node1 should be in readySteps (from API response)
      expect(state.readySteps.has('mcp:node1')).toBe(true);
      expect(state.completedSteps.has('mcp:node1')).toBe(false);
    });
  });

  // ==========================================================================
  // 409 Conflict handling
  // ==========================================================================

  describe('409 Conflict handling', () => {
    it('should handle 409 response on execute step', async () => {
      await initManager(manager, { readySteps: ['mcp:node1'] });

      // Simulate WS sendAction throwing a 409
      const { wsClient } = await import('@/lib/websocket');
      (wsClient.sendAction as any).mockRejectedValueOnce({ status: 409 });

      // Mock refresh response
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          seq: 8,
          readySteps: [],
          runningStepIds: ['mcp:node1'],
        })
      );

      const result = await manager.executeStep('mcp:node1');

      expect(result.success).toBe(false);
      expect(result.status).toBe('CONFLICT');
    });

    it('should handle 409 on rerun (step already rerunning by another user)', async () => {
      await initManager(manager);

      // Simulate rerun API returning 409 (another user already reran this step)
      const error: any = new Error('INVALID_STATE_FOR_RERUN');
      error.status = 409;
      mockRerunFromStep.mockRejectedValueOnce(error);

      await expect(manager.rerunStep('mcp:node2')).rejects.toThrow();

      // State should have error set
      expect(manager.getState().error).toBeTruthy();
    });
  });

  // ==========================================================================
  // 10-User Collaborative SBS Mode - Stress Tests
  // ==========================================================================

  describe('Multi-user collaborative (10 users, SBS mode)', () => {

    /**
     * Scenario: 10 users all see node1 as READY.
     * User A clicks execute (accepted), users B-J click right after.
     * Backend returns 409 for B-J. Each client sees the 409,
     * refreshes, and sees node1 as RUNNING (not READY).
     * No double-execution occurs because seq filtering discards
     * stale events with lower seq.
     */
    it('should handle 10 users clicking the same READY node simultaneously', async () => {
      await initManager(manager, { readySteps: ['mcp:node1'], seq: 5 });

      const { wsClient } = await import('@/lib/websocket');

      // User A: succeeds (ack)
      (wsClient.sendAction as any).mockResolvedValueOnce(undefined);
      const resultA = await manager.executeStep('mcp:node1');
      expect(resultA.success).toBe(true);
      expect(resultA.status).toBe('ACCEPTED');

      // Users B-J (9 users): get 409 CONFLICT
      for (let i = 1; i <= 9; i++) {
        (wsClient.sendAction as any).mockRejectedValueOnce({ status: 409 });
        mockGetRunState.mockResolvedValueOnce(
          createRunState({
            seq: 6,
            readySteps: [],
            runningStepIds: ['mcp:node1'],
          })
        );
        const result = await manager.executeStep(`mcp:node1`);
        expect(result.success).toBe(false);
        expect(result.status).toBe('CONFLICT');
      }

      // After all attempts, state should show node1 as running (from refresh)
      const state = manager.getState();
      expect(state.readySteps.has('mcp:node1')).toBe(false);
    });

    /**
     * Scenario: 10 users each see a different node as READY in a 10-step parallel DAG.
     * Each user executes their node concurrently.
     * WS batch-update events arrive in interleaved order with increasing seq.
     * State should converge correctly.
     */
    it('should handle 10 users executing different READY nodes concurrently', async () => {
      const readySteps = Array.from({ length: 10 }, (_, i) => `mcp:step_${i}`);
      await initManager(manager, { readySteps, seq: 5 });

      const { wsClient } = await import('@/lib/websocket');

      // All 10 users execute concurrently (all succeed - different nodes)
      for (const stepId of readySteps) {
        (wsClient.sendAction as any).mockResolvedValueOnce(undefined);
        const result = await manager.executeStep(stepId);
        expect(result.success).toBe(true);
      }

      // Batch-updates arrive in interleaved order (seq monotonically increasing)
      // Each batch includes cumulative steps array (processBatchUpdate prioritizes steps over completedStepIds)
      const cumulativeSteps: Array<{ id: string; status: string }> = [];
      for (let i = 0; i < 10; i++) {
        cumulativeSteps.push({ id: `mcp:step_${i}`, status: 'completed' });
        manager.handleBatchUpdate({
          seq: 6 + i,
          steps: [...cumulativeSteps],
        });
        vi.advanceTimersByTime(200);
      }

      const state = manager.getState();
      // All 10 steps should be completed
      for (let i = 0; i < 10; i++) {
        expect(state.completedSteps.has(`mcp:step_${i}`)).toBe(true);
      }
    });

    /**
     * Scenario: User A reruns node3 while users B-I are viewing.
     * Backend bumps seq to 20 on rerun. Then 8 stale batch-updates
     * arrive from before the rerun (seq 12-19). They must all be discarded.
     * Then a fresh batch-update (seq 21) arrives showing node3 as RUNNING.
     */
    it('should protect 10 users from stale events after one user reruns', async () => {
      await initManager(manager, { seq: 10 });

      // User A reruns node2 (seq bumps to 20)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        resetSteps: ['mcp:node2', 'mcp:node3'],
        readySteps: ['mcp:node2'],
        seq: 20,
      }));
      await manager.rerunStep('mcp:node2');

      // 8 stale batch-updates from other users' views (seq 12-19)
      for (let i = 12; i < 20; i++) {
        manager.handleBatchUpdate({
          seq: i,
          steps: [
            { id: 'mcp:node2', status: 'completed' },
            { id: 'mcp:node3', status: 'completed' },
          ],
        });
      }
      vi.advanceTimersByTime(200);

      let state = manager.getState();
      // All stale events discarded
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.completedSteps.has('mcp:node3')).toBe(false);
      expect(state.readySteps.has('mcp:node2')).toBe(true);

      // Fresh batch-update (seq 21) - node2 is now running
      manager.handleBatchUpdate({
        seq: 21,
        steps: [{ id: 'mcp:node2', status: 'running' }],
      });
      vi.advanceTimersByTime(200);

      state = manager.getState();
      // running clears from terminal sets
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
    });

    /**
     * Scenario: 3 users fire reruns in rapid succession on different steps.
     * Each rerun bumps seq higher. Only events with seq > the latest
     * rerun's seq should be accepted.
     */
    it('should handle 3 users firing reruns in rapid succession', async () => {
      await initManager(manager, { seq: 5 });

      // User A reruns node1 (seq→10)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        stepId: 'mcp:node1',
        resetSteps: ['mcp:node1', 'mcp:node2', 'mcp:node3'],
        readySteps: ['mcp:node1'],
        seq: 10,
      }));
      await manager.rerunStep('mcp:node1');

      // User B reruns node2 (seq→15)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        stepId: 'mcp:node2',
        resetSteps: ['mcp:node2', 'mcp:node3'],
        readySteps: ['mcp:node2'],
        seq: 15,
      }));
      await manager.rerunStep('mcp:node2');

      // User C reruns node3 (seq→20)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        stepId: 'mcp:node3',
        resetSteps: ['mcp:node3'],
        readySteps: ['mcp:node3'],
        seq: 20,
      }));
      await manager.rerunStep('mcp:node3');

      // Batch-update from User A's rerun (seq=11, stale)
      manager.handleBatchUpdate({
        seq: 11,
        steps: [{ id: 'mcp:node1', status: 'completed' }],
      });
      vi.advanceTimersByTime(200);
      expect(manager.getState().completedSteps.has('mcp:node1')).toBe(false);

      // Batch-update from User B's rerun (seq=16, stale)
      manager.handleBatchUpdate({
        seq: 16,
        steps: [{ id: 'mcp:node2', status: 'completed' }],
      });
      vi.advanceTimersByTime(200);
      expect(manager.getState().completedSteps.has('mcp:node2')).toBe(false);

      // Batch-update from User C's execution (seq=21, fresh)
      manager.handleBatchUpdate({
        seq: 21,
        steps: [{ id: 'mcp:node3', status: 'completed' }],
        completedStepIds: ['mcp:node3'],
      });
      vi.advanceTimersByTime(200);
      expect(manager.getState().completedSteps.has('mcp:node3')).toBe(true);
    });

    /**
     * Scenario: Multiple users execute nodes in a pipeline (A→B→C).
     * User1 executes node_A, User2 executes node_B (when ready), etc.
     * readySteps WS events arrive with increasing seq. Stale readySteps
     * from before node_A completed must not overwrite the current readySteps.
     */
    it('should handle pipeline execution across multiple users with seq ordering', async () => {
      await initManager(manager, {
        readySteps: ['mcp:step_a'],
        seq: 5,
      });

      // User1 executes step_a (via WS ack)
      const { wsClient } = await import('@/lib/websocket');
      (wsClient.sendAction as any).mockResolvedValueOnce(undefined);
      await manager.executeStep('mcp:step_a');

      // Backend: step_a completes → step_b ready (seq=6)
      manager.handleBatchUpdate({
        seq: 6,
        steps: [{ id: 'mcp:step_a', status: 'completed' }],
        completedStepIds: ['mcp:step_a'],
      });
      vi.advanceTimersByTime(200);

      manager.handleEvent('readySteps', {
        seq: 7,
        readySteps: ['mcp:step_b'],
      });

      let state = manager.getState();
      expect(state.completedSteps.has('mcp:step_a')).toBe(true);
      expect(state.readySteps.has('mcp:step_b')).toBe(true);

      // User2 executes step_b
      (wsClient.sendAction as any).mockResolvedValueOnce(undefined);
      await manager.executeStep('mcp:step_b');

      // Stale readySteps event from before step_a completed (seq=4, stale) - discarded
      manager.handleEvent('readySteps', {
        seq: 4,
        readySteps: ['mcp:step_a'], // stale: step_a should NOT be re-added as ready
      });

      state = manager.getState();
      // step_a should NOT be ready again (stale event discarded)
      expect(state.readySteps.has('mcp:step_a')).toBe(false);
      expect(state.completedSteps.has('mcp:step_a')).toBe(true);
    });

    /**
     * Scenario: User A reruns node2 while User B independently fires a refresh.
     * User B's refresh returns the post-rerun state with seq=20.
     * Then stale batch-update (seq=15) arrives. It must be discarded.
     */
    it('should handle concurrent rerun and refresh from different users', async () => {
      await initManager(manager, { seq: 10 });

      // User A reruns node2 (seq→20)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        resetSteps: ['mcp:node2', 'mcp:node3'],
        readySteps: ['mcp:node2'],
        seq: 20,
      }));
      await manager.rerunStep('mcp:node2');

      // User B's refresh (returns post-rerun state, seq=20)
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          seq: 20,
          completedStepIds: ['trigger:start', 'mcp:node1'],
          readySteps: ['mcp:node2'],
        })
      );
      await manager.refresh();

      // Stale batch-update from before the rerun (seq=15) - must be discarded
      manager.handleBatchUpdate({
        seq: 15,
        steps: [
          { id: 'mcp:node2', status: 'completed' },
          { id: 'mcp:node3', status: 'completed' },
        ],
      });
      vi.advanceTimersByTime(200);

      const state = manager.getState();
      expect(state.completedSteps.has('mcp:node2')).toBe(false);
      expect(state.readySteps.has('mcp:node2')).toBe(true);
    });

    /**
     * Scenario: In a 10-node parallel DAG, one user reruns node5.
     * While the rerun is in flight, 9 other users' batch-updates
     * (from watching their own nodes complete) arrive with mixed seq values.
     * Only events with seq > rerun seq should be accepted.
     */
    it('should handle parallel DAG with 10 nodes and selective rerun', async () => {
      const allSteps = Array.from({ length: 10 }, (_, i) => `mcp:step_${i}`);
      await initManager(manager, {
        completedStepIds: [...allSteps],
        readySteps: [],
        seq: 30,
      });

      // User reruns step_5 (seq→40, resets step_5..step_9)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        stepId: 'mcp:step_5',
        resetSteps: ['mcp:step_5', 'mcp:step_6', 'mcp:step_7', 'mcp:step_8', 'mcp:step_9'],
        readySteps: ['mcp:step_5'],
        seq: 40,
      }));
      await manager.rerunStep('mcp:step_5');

      // 9 stale batch-updates (seq 31-39, from other users' views)
      for (let i = 31; i < 40; i++) {
        manager.handleBatchUpdate({
          seq: i,
          steps: allSteps.map(id => ({ id, status: 'completed' })),
        });
      }
      vi.advanceTimersByTime(200);

      const state = manager.getState();
      // Steps 5-9 should NOT be re-completed (stale events discarded)
      for (let i = 5; i <= 9; i++) {
        expect(state.completedSteps.has(`mcp:step_${i}`)).toBe(false);
      }
      // step_5 should be ready
      expect(state.readySteps.has('mcp:step_5')).toBe(true);

      // Fresh batch-update (seq=41) - step_5 executing
      manager.handleBatchUpdate({
        seq: 41,
        steps: [{ id: 'mcp:step_5', status: 'running' }],
      });
      vi.advanceTimersByTime(200);

      // step_5 cleared from terminal sets (running)
      expect(manager.getState().completedSteps.has('mcp:step_5')).toBe(false);
    });

    /**
     * Scenario: 10 stepRerun WS events arrive simultaneously
     * (e.g., 10 users all see the rerun notification).
     * Each triggers a refresh. The last refresh wins (highest seq),
     * and all stale batch-updates are correctly discarded.
     */
    it('should handle 10 simultaneous stepRerun WS events triggering refreshes', async () => {
      await initManager(manager, { seq: 5 });

      // 10 stepRerun events arrive, each triggers refreshStateInternal()
      for (let i = 0; i < 10; i++) {
        mockGetRunState.mockResolvedValueOnce(
          createRunState({
            seq: 20 + i, // each refresh returns incrementing seq
            completedStepIds: ['trigger:start', 'mcp:node1'],
            readySteps: ['mcp:node2'],
          })
        );
        manager.handleEvent('stepRerun', { stepId: 'mcp:node2' });
      }

      // Wait for all async refreshes
      await vi.advanceTimersByTimeAsync(500);

      // lastKnownSeq should be at least 20 (from first refresh)
      // Stale batch-update with seq=18 should be discarded
      manager.handleBatchUpdate({
        seq: 18,
        steps: [{ id: 'mcp:node2', status: 'completed' }],
      });
      vi.advanceTimersByTime(200);

      expect(manager.getState().completedSteps.has('mcp:node2')).toBe(false);
      expect(manager.getState().readySteps.has('mcp:node2')).toBe(true);
    });

    /**
     * Scenario: Out-of-order batch-updates from 10 users.
     * seq=50 arrives and is flushed. Then seq=48 and seq=47 arrive.
     * They must be discarded because lastKnownSeq is already 50.
     * Finally, seq=51 arrives and is accepted.
     */
    it('should handle completely out-of-order batch-updates from multiple users', async () => {
      // Use clean init (no pre-completed steps) to avoid interference
      await initManager(manager, { seq: 45, completedStepIds: [], steps: [] });

      // seq=50 arrives first - flush it so lastKnownSeq advances to 50
      manager.handleBatchUpdate({
        seq: 50,
        steps: [{ id: 'mcp:node2', status: 'completed' }],
        completedStepIds: ['mcp:node2'],
      });
      vi.advanceTimersByTime(200);
      expect(manager.getState().completedSteps.has('mcp:node2')).toBe(true);

      // seq=48 arrives late - discarded at handleBatchUpdate entry (seq < 50)
      manager.handleBatchUpdate({
        seq: 48,
        steps: [{ id: 'mcp:node3', status: 'completed' }],
        completedStepIds: ['mcp:node3'],
      });
      // seq=47 arrives even later - also discarded at entry (seq < 50)
      manager.handleBatchUpdate({
        seq: 47,
        steps: [{ id: 'mcp:node1', status: 'failed' }],
        failedStepIds: ['mcp:node1'],
      });
      // Flush debounce - nothing pending (both were discarded before debounce)
      vi.advanceTimersByTime(200);

      expect(manager.getState().completedSteps.has('mcp:node3')).toBe(false);
      expect(manager.getState().failedSteps.has('mcp:node1')).toBe(false);

      // seq=51 arrives (fresh, processed)
      manager.handleBatchUpdate({
        seq: 51,
        steps: [{ id: 'mcp:node3', status: 'completed' }],
        completedStepIds: ['mcp:node2', 'mcp:node3'],
      });
      vi.advanceTimersByTime(200);
      expect(manager.getState().completedSteps.has('mcp:node3')).toBe(true);
    });
  });

  // ==========================================================================
  // Multi-User Automatic Mode - Stress Tests
  // ==========================================================================

  describe('Multi-user collaborative (automatic mode)', () => {

    /** Helper: init in automatic mode. */
    async function initAutoManager(mgr: WorkflowRunManager, overrides: Record<string, any> = {}) {
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          executionMode: 'automatic',
          status: 'running',
          completedStepIds: [],
          readySteps: [],
          steps: [],
          edges: [],
          seq: 5,
          ...overrides,
        })
      );
      await mgr.initialize();
    }

    /**
     * In automatic mode, nodes execute sequentially/in-parallel without
     * user clicks. Batch-updates stream in rapidly. seq filtering ensures
     * state consistency when events arrive out of order.
     */
    it('should handle rapid automatic execution with out-of-order batch-updates', async () => {
      await initAutoManager(manager);

      // 20 batch-updates arrive in rapid succession (simulating fast auto execution)
      // Some arrive out of order (network jitter)
      const seqOrder = [6, 8, 7, 9, 11, 10, 12, 14, 13, 15, 17, 16, 18, 20, 19, 21, 23, 22, 24, 25];
      for (const seq of seqOrder) {
        manager.handleBatchUpdate({
          seq,
          steps: [{ id: `mcp:step_${seq}`, status: 'completed' }],
          completedStepIds: [`mcp:step_${seq}`],
        });
      }
      vi.advanceTimersByTime(200);

      const state = manager.getState();
      // Only the last processed batch-update should have taken effect
      // seq=25 is the highest, so it was the last one NOT discarded.
      // Due to debouncing, only the latest pending data is processed.
      // The last call was seq=25 → processed.
      expect(state.completedSteps.has('mcp:step_25')).toBe(true);

      // seq=6 was processed (first one, higher than init seq=5)
      // but due to debouncing, only the last batch (seq=25) is actually flushed
      // Earlier seq values (7, 8, 10, etc.) were discarded because seq check
      // happens BEFORE debounce, and lower seq entries are dropped immediately
    });

    /**
     * In automatic mode, a trigger fires and kicks off execution.
     * While nodes execute, batch-updates with increasing seq stream in.
     * User1 triggers a rerun mid-execution. The seq from the rerun
     * must cause all pre-rerun batch-updates to be discarded.
     */
    it('should handle rerun during automatic execution (mid-flight)', async () => {
      await initAutoManager(manager, {
        completedStepIds: ['trigger:start', 'mcp:step_1', 'mcp:step_2'],
        runningStepIds: ['mcp:step_3'],
        seq: 15,
      });

      // Batch-updates from ongoing execution (step_3 completes, step_4 starts)
      manager.handleBatchUpdate({
        seq: 16,
        steps: [
          { id: 'mcp:step_3', status: 'completed' },
          { id: 'mcp:step_4', status: 'running' },
        ],
        completedStepIds: ['trigger:start', 'mcp:step_1', 'mcp:step_2', 'mcp:step_3'],
        runningStepIds: ['mcp:step_4'],
      });
      vi.advanceTimersByTime(200);
      expect(manager.getState().completedSteps.has('mcp:step_3')).toBe(true);

      // User1 reruns from step_2 (seq→30)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        stepId: 'mcp:step_2',
        resetSteps: ['mcp:step_2', 'mcp:step_3', 'mcp:step_4'],
        readySteps: ['mcp:step_2'],
        status: 'running', // auto mode stays running
        seq: 30,
      }));
      await manager.rerunStep('mcp:step_2');

      let state = manager.getState();
      expect(state.completedSteps.has('mcp:step_2')).toBe(false);
      expect(state.completedSteps.has('mcp:step_3')).toBe(false);
      expect(state.readySteps.has('mcp:step_2')).toBe(true);

      // Stale batch-updates from the old execution (seq 17-29)
      for (let seq = 17; seq < 30; seq++) {
        manager.handleBatchUpdate({
          seq,
          steps: [
            { id: 'mcp:step_4', status: 'completed' },
            { id: 'mcp:step_5', status: 'completed' },
          ],
        });
      }
      vi.advanceTimersByTime(200);

      state = manager.getState();
      // All stale - step_4 and step_5 should NOT be completed
      expect(state.completedSteps.has('mcp:step_4')).toBe(false);
      expect(state.completedSteps.has('mcp:step_5')).toBe(false);

      // Fresh batch from new execution (seq=31) - step_2 re-executes and completes
      manager.handleBatchUpdate({
        seq: 31,
        steps: [{ id: 'mcp:step_2', status: 'completed' }],
        completedStepIds: ['trigger:start', 'mcp:step_1', 'mcp:step_2'],
      });
      vi.advanceTimersByTime(200);
      expect(manager.getState().completedSteps.has('mcp:step_2')).toBe(true);
    });

    /**
     * Automatic mode: workflow completes, but stale batch-updates arrive
     * after completion. Terminal status should not be corrupted.
     */
    it('should handle stale events arriving after workflow completion in auto mode', async () => {
      await initAutoManager(manager, {
        completedStepIds: ['trigger:start', 'mcp:step_1'],
        seq: 10,
      });

      // Workflow completes (seq=15)
      manager.handleEvent('workflowStatus', { status: 'completed' });
      manager.handleBatchUpdate({
        seq: 15,
        steps: [
          { id: 'mcp:step_1', status: 'completed' },
          { id: 'mcp:step_2', status: 'completed' },
        ],
        completedStepIds: ['trigger:start', 'mcp:step_1', 'mcp:step_2'],
      });
      vi.advanceTimersByTime(200);

      let state = manager.getState();
      expect(state.runStatus).toBe('completed');
      expect(state.completedSteps.has('mcp:step_2')).toBe(true);

      // Late stale batch-update (seq=12) arrives - must be discarded
      manager.handleBatchUpdate({
        seq: 12,
        steps: [
          { id: 'mcp:step_2', status: 'running' }, // stale: would incorrectly clear terminal
        ],
      });
      vi.advanceTimersByTime(200);

      state = manager.getState();
      // step_2 should still be completed (stale event discarded)
      expect(state.completedSteps.has('mcp:step_2')).toBe(true);
    });

    /**
     * Automatic mode: 10 parallel branches complete simultaneously.
     * Batch-updates for all branches arrive out of order but with
     * monotonically tracked seq. State should converge correctly.
     */
    it('should handle 10 parallel branches completing in auto mode', async () => {
      const branches = Array.from({ length: 10 }, (_, i) => `mcp:branch_${i}`);
      await initAutoManager(manager, {
        completedStepIds: ['trigger:start'],
        runningStepIds: branches,
        seq: 10,
      });

      // Each branch completes in a separate batch-update (seq increasing)
      // processBatchUpdate prioritizes steps array over completedStepIds,
      // so send cumulative steps array to track all completed branches
      const cumulativeSteps: Array<{ id: string; status: string }> = [
        { id: 'trigger:start', status: 'completed' },
      ];
      for (let i = 0; i < 10; i++) {
        cumulativeSteps.push({ id: `mcp:branch_${i}`, status: 'completed' });
        manager.handleBatchUpdate({
          seq: 11 + i,
          steps: [...cumulativeSteps],
        });
        vi.advanceTimersByTime(200);
      }

      const state = manager.getState();
      for (const branch of branches) {
        expect(state.completedSteps.has(branch)).toBe(true);
      }
    });

    /**
     * Automatic mode: rerun during parallel execution.
     * 5 branches are running, user reruns from the fork node.
     * All in-flight branches should be reset. Stale completions
     * from the old parallel execution must be discarded via seq.
     */
    it('should handle rerun of fork node with 5 running branches in auto mode', async () => {
      const branches = Array.from({ length: 5 }, (_, i) => `mcp:branch_${i}`);
      await initAutoManager(manager, {
        completedStepIds: ['trigger:start', 'core:fork'],
        runningStepIds: branches,
        seq: 20,
      });

      // 2 branches complete before rerun (seq=21, 22)
      // processBatchUpdate prioritizes steps array over completedStepIds,
      // so send cumulative steps to reflect all completed nodes
      manager.handleBatchUpdate({
        seq: 21,
        steps: [
          { id: 'trigger:start', status: 'completed' },
          { id: 'core:fork', status: 'completed' },
          { id: 'mcp:branch_0', status: 'completed' },
        ],
      });
      vi.advanceTimersByTime(200);
      manager.handleBatchUpdate({
        seq: 22,
        steps: [
          { id: 'trigger:start', status: 'completed' },
          { id: 'core:fork', status: 'completed' },
          { id: 'mcp:branch_0', status: 'completed' },
          { id: 'mcp:branch_1', status: 'completed' },
        ],
      });
      vi.advanceTimersByTime(200);

      expect(manager.getState().completedSteps.has('mcp:branch_0')).toBe(true);
      expect(manager.getState().completedSteps.has('mcp:branch_1')).toBe(true);

      // User reruns from core:fork (resets all branches, seq→40)
      mockRerunFromStep.mockResolvedValueOnce(createRerunResponse({
        stepId: 'core:fork',
        resetSteps: ['core:fork', ...branches, 'core:merge'],
        readySteps: ['core:fork'],
        status: 'running',
        seq: 40,
      }));
      await manager.rerunStep('core:fork');

      let state = manager.getState();
      expect(state.completedSteps.has('mcp:branch_0')).toBe(false);
      expect(state.completedSteps.has('mcp:branch_1')).toBe(false);
      expect(state.readySteps.has('core:fork')).toBe(true);

      // Stale completions from old execution (seq 23-39)
      for (let seq = 23; seq < 40; seq++) {
        manager.handleBatchUpdate({
          seq,
          steps: branches.map(id => ({ id, status: 'completed' })),
        });
      }
      vi.advanceTimersByTime(200);

      state = manager.getState();
      // All stale - branches should NOT be completed
      for (const branch of branches) {
        expect(state.completedSteps.has(branch)).toBe(false);
      }

      // Fresh execution after rerun (seq=41)
      manager.handleBatchUpdate({
        seq: 41,
        steps: [{ id: 'core:fork', status: 'completed' }],
        completedStepIds: ['trigger:start', 'core:fork'],
      });
      vi.advanceTimersByTime(200);
      expect(manager.getState().completedSteps.has('core:fork')).toBe(true);
    });

    /**
     * Automatic mode: trigger re-fire with multi-epoch.
     * Epoch 1 completes, trigger re-fires for epoch 2.
     * Stale batch-updates from epoch 1 must not pollute epoch 2.
     */
    it('should isolate epochs via seq in automatic trigger re-fire', async () => {
      await initAutoManager(manager, {
        status: 'waiting_trigger',
        completedStepIds: [],
        readySteps: ['trigger:start'],
        seq: 5,
      });

      // Epoch 1: trigger fires
      mockTriggerSpecific.mockResolvedValueOnce({
        status: 'triggered',
        readySteps: [],
        epoch: 1,
      });
      await manager.executeStep('trigger:start');

      // Epoch 1 executes: nodes complete (seq 6-10)
      const cumulativeEpoch1: string[] = [];
      for (let i = 1; i <= 5; i++) {
        cumulativeEpoch1.push(`mcp:step_${i}`);
        manager.handleBatchUpdate({
          seq: 5 + i,
          steps: [{ id: `mcp:step_${i}`, status: 'completed' }],
          completedStepIds: [...cumulativeEpoch1],
        });
        vi.advanceTimersByTime(200);
      }

      // Workflow completes epoch 1 (seq=11)
      manager.handleEvent('workflowStatus', { status: 'completed' });
      manager.handleBatchUpdate({
        seq: 11,
        steps: Array.from({ length: 5 }, (_, i) => ({ id: `mcp:step_${i + 1}`, status: 'completed' })),
        completedStepIds: ['trigger:start', 'mcp:step_1', 'mcp:step_2', 'mcp:step_3', 'mcp:step_4', 'mcp:step_5'],
      });
      vi.advanceTimersByTime(200);

      // Epoch 2: trigger re-fires (seq=12 from backend)
      // Mock the post-completion refresh
      mockGetRunState.mockResolvedValueOnce(
        createRunState({
          status: 'waiting_trigger',
          completedStepIds: ['trigger:start', 'mcp:step_1', 'mcp:step_2', 'mcp:step_3', 'mcp:step_4', 'mcp:step_5'],
          readySteps: ['trigger:start'],
          executionMode: 'automatic',
          seq: 12,
        })
      );
      // Flush post-completion refresh timer
      vi.advanceTimersByTime(600);
      await vi.advanceTimersByTimeAsync(100);

      mockTriggerSpecific.mockResolvedValueOnce({
        status: 'triggered',
        readySteps: [],
        epoch: 2,
      });
      await manager.executeStep('trigger:start');

      // Stale batch-update from epoch 1 (seq=9) - must be discarded
      manager.handleBatchUpdate({
        seq: 9,
        steps: [{ id: 'mcp:step_3', status: 'failed' }], // stale failure
      });
      vi.advanceTimersByTime(200);

      // step_3 should NOT be marked as failed (stale epoch 1 event)
      expect(manager.getState().failedSteps.has('mcp:step_3')).toBe(false);
    });

    /**
     * High-frequency event storm: 100 batch-updates in automatic mode.
     * Simulates a large workflow with many nodes completing in quick succession.
     * Only the last debounced batch should be processed.
     */
    it('should handle 100-event storm in automatic mode without state corruption', async () => {
      await initAutoManager(manager, { seq: 0 });

      // 100 batch-updates arrive within the debounce window
      for (let i = 1; i <= 100; i++) {
        const completed = Array.from({ length: i }, (_, j) => `mcp:step_${j + 1}`);
        manager.handleBatchUpdate({
          seq: i,
          steps: [
            { id: `mcp:step_${i}`, status: 'completed' },
            // Also include some "running" statuses to simulate pipeline
            ...(i < 100 ? [{ id: `mcp:step_${i + 1}`, status: 'running' }] : []),
          ],
          completedStepIds: completed,
          ...(i < 100 ? { runningStepIds: [`mcp:step_${i + 1}`] } : {}),
        });
      }

      // Flush debounce (only the last pending batch is processed)
      vi.advanceTimersByTime(200);

      const state = manager.getState();
      // The last batch (seq=100) should have been processed
      expect(state.completedSteps.has('mcp:step_100')).toBe(true);
      // Earlier events were coalesced by debounce - only the last one matters
    });
  });
});

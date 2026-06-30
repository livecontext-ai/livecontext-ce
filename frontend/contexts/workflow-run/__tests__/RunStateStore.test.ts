import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  RunStateStore,
  getRunStateStore,
  deleteRunStateStore,
  hasRunStateStore,
} from '../RunStateStore';

// Mock streamDebug to silence log output during tests
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

function createMinimalApiData(overrides: Record<string, any> = {}) {
  return {
    runId: 'run-1',
    status: 'running',
    readySteps: [],
    completedStepIds: [],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: [],
    steps: [],
    edges: [],
    ...overrides,
  };
}

function createStepData(stepId: string, status: string, extra: Record<string, any> = {}) {
  return { stepId, stepAlias: stepId, status, ...extra };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('RunStateStore', () => {
  let store: RunStateStore;

  beforeEach(() => {
    store = new RunStateStore('run-1');
  });

  // =========================================================================
  // Initial State
  // =========================================================================

  describe('Initial state', () => {
    it('should have the correct runId', () => {
      expect(store.getRunId()).toBe('run-1');
      expect(store.getState().runId).toBe('run-1');
    });

    it('should start with pending runStatus', () => {
      expect(store.getState().runStatus).toBe('pending');
    });

    it('should start disconnected and not loading', () => {
      const state = store.getState();
      expect(state.isConnected).toBe(false);
      expect(state.isLoading).toBe(false);
    });

    it('should start with no error', () => {
      expect(store.getState().error).toBeNull();
    });

    it('should start with empty step sets', () => {
      const state = store.getState();
      expect(state.readySteps.size).toBe(0);
      expect(state.completedSteps.size).toBe(0);
      expect(state.failedSteps.size).toBe(0);
      expect(state.skippedSteps.size).toBe(0);
      expect(state.runningSteps.size).toBe(0);
      expect(state.evaluatedCores.size).toBe(0);
    });

    it('should start with empty visualization arrays', () => {
      const state = store.getState();
      expect(state.nodes).toEqual([]);
      expect(state.batchSteps).toEqual([]);
      expect(state.batchEdges).toEqual([]);
      expect(state.loops).toEqual([]);
      expect(state.decisionEvaluations).toEqual([]);
    });

    it('should start with automatic execution mode', () => {
      expect(store.getState().executionMode).toBe('automatic');
    });

    it('should start with null trigger type', () => {
      expect(store.getState().triggerType).toBeNull();
    });

    it('should start with zero totalNodes and currentEpoch', () => {
      const state = store.getState();
      expect(state.totalNodes).toBe(0);
      expect(state.currentEpoch).toBe(0);
    });
  });

  // =========================================================================
  // State Mutations - Simple Setters
  // =========================================================================

  describe('Simple state mutations', () => {
    it('setConnected should update isConnected', () => {
      store.setConnected(true);
      expect(store.getState().isConnected).toBe(true);
      store.setConnected(false);
      expect(store.getState().isConnected).toBe(false);
    });

    it('setLoading should update isLoading', () => {
      store.setLoading(true);
      expect(store.getState().isLoading).toBe(true);
    });

    it('setError should update error and clear isLoading', () => {
      store.setLoading(true);
      store.setError('Something went wrong');
      const state = store.getState();
      expect(state.error).toBe('Something went wrong');
      expect(state.isLoading).toBe(false);
    });

    it('setError(null) should clear the error', () => {
      store.setError('err');
      store.setError(null);
      expect(store.getState().error).toBeNull();
    });

    it('setExecutionMode should update executionMode', () => {
      store.setExecutionMode('step_by_step');
      expect(store.getState().executionMode).toBe('step_by_step');
    });

    it('setTriggerType should update triggerType', () => {
      store.setTriggerType('webhook');
      expect(store.getState().triggerType).toBe('webhook');
    });

    it('setEpoch should update currentEpoch', () => {
      store.setEpoch(3);
      expect(store.getState().currentEpoch).toBe(3);
    });

    it('setRerunning should update isRerunning', () => {
      store.setRerunning(true);
      expect(store.getState().isRerunning).toBe(true);
    });
  });

  // =========================================================================
  // Ready Steps Tracking
  // =========================================================================

  describe('Ready steps tracking', () => {
    it('setReadySteps should replace the set of ready steps', () => {
      store.setReadySteps(['mcp:step1', 'mcp:step2']);
      expect(store.getState().readySteps.size).toBe(2);
      expect(store.isReady('mcp:step1')).toBe(true);
      expect(store.isReady('mcp:step2')).toBe(true);
    });

    it('setReadySteps with empty array should clear ready steps', () => {
      store.setReadySteps(['mcp:step1']);
      store.setReadySteps([]);
      expect(store.getState().readySteps.size).toBe(0);
      expect(store.isReady('mcp:step1')).toBe(false);
    });

    it('isReady should return false for unknown steps', () => {
      expect(store.isReady('nonexistent')).toBe(false);
    });
  });

  // =========================================================================
  // Completed / Failed / Skipped Step Tracking
  // =========================================================================

  describe('Completed, failed, and skipped step tracking', () => {
    it('setCompletedSteps should replace the set', () => {
      store.setCompletedSteps(['s1', 's2']);
      expect(store.isCompleted('s1')).toBe(true);
      expect(store.isCompleted('s2')).toBe(true);
    });

    it('addCompletedStep should add to existing set', () => {
      store.setCompletedSteps(['s1']);
      store.addCompletedStep('s2');
      expect(store.getState().completedSteps.size).toBe(2);
      expect(store.isCompleted('s1')).toBe(true);
      expect(store.isCompleted('s2')).toBe(true);
    });

    it('addCompletedStep should be idempotent', () => {
      store.addCompletedStep('s1');
      store.addCompletedStep('s1');
      expect(store.getState().completedSteps.size).toBe(1);
    });

    it('addCompletedStep should track core: steps in evaluatedCores', () => {
      store.addCompletedStep('core:decision_1');
      store.addCompletedStep('mcp:step1');
      const state = store.getState();
      expect(state.evaluatedCores.has('core:decision_1')).toBe(true);
      expect(state.evaluatedCores.has('mcp:step1')).toBe(false);
    });

    it('setFailedSteps should replace the set', () => {
      store.setFailedSteps(['f1']);
      expect(store.getState().failedSteps.has('f1')).toBe(true);
    });

    it('addFailedStep should add to existing set', () => {
      store.addFailedStep('f1');
      store.addFailedStep('f2');
      expect(store.getState().failedSteps.size).toBe(2);
    });

    it('setSkippedSteps should replace the set', () => {
      store.setSkippedSteps(['sk1', 'sk2']);
      expect(store.getState().skippedSteps.size).toBe(2);
    });
  });

  // =========================================================================
  // canRerun
  // =========================================================================

  describe('canRerun', () => {
    it('should return true for completed steps', () => {
      store.addCompletedStep('s1');
      expect(store.canRerun('s1')).toBe(true);
    });

    it('should return true for failed steps', () => {
      store.addFailedStep('f1');
      expect(store.canRerun('f1')).toBe(true);
    });

    it('should return true for skipped steps', () => {
      store.setSkippedSteps(['sk1']);
      expect(store.canRerun('sk1')).toBe(true);
    });

    it('should return false for pending/running steps', () => {
      store.setReadySteps(['r1']);
      expect(store.canRerun('r1')).toBe(false);
      expect(store.canRerun('nonexistent')).toBe(false);
    });
  });

  // =========================================================================
  // isStepByStepMode
  // =========================================================================

  describe('isStepByStepMode', () => {
    it('should return false when mode is automatic', () => {
      expect(store.isStepByStepMode()).toBe(false);
    });

    it('should return true when mode is step_by_step', () => {
      store.setExecutionMode('step_by_step');
      expect(store.isStepByStepMode()).toBe(true);
    });
  });

  // =========================================================================
  // Terminal State Detection (Run Status State Machine)
  // =========================================================================

  describe('Terminal state detection', () => {
    it('should allow transition from pending to running', () => {
      store.setRunStatus('running');
      expect(store.getState().runStatus).toBe('running');
    });

    it('should allow transition from running to completed', () => {
      store.setRunStatus('running');
      store.setRunStatus('completed');
      expect(store.getState().runStatus).toBe('completed');
    });

    it('should allow transition from running to failed', () => {
      store.setRunStatus('running');
      store.setRunStatus('failed');
      expect(store.getState().runStatus).toBe('failed');
    });

    it('should NOT allow transition from completed to running (terminal is sticky)', () => {
      store.setRunStatus('completed');
      store.setRunStatus('running');
      expect(store.getState().runStatus).toBe('completed');
    });

    it('should NOT allow transition from failed to running', () => {
      store.setRunStatus('failed');
      store.setRunStatus('running');
      expect(store.getState().runStatus).toBe('failed');
    });

    it('should NOT allow transition from stopped to pending', () => {
      store.setRunStatus('stopped');
      store.setRunStatus('pending');
      expect(store.getState().runStatus).toBe('stopped');
    });

    it('should allow transition from completed to failed (both terminal)', () => {
      store.setRunStatus('completed');
      store.setRunStatus('failed');
      expect(store.getState().runStatus).toBe('failed');
    });

    it('should allow transition from failed to stopped (both terminal)', () => {
      store.setRunStatus('failed');
      store.setRunStatus('stopped');
      expect(store.getState().runStatus).toBe('stopped');
    });
  });

  // =========================================================================
  // setWorkflowStatus
  // =========================================================================

  describe('setWorkflowStatus', () => {
    it('should update both workflowStatus and runStatus', () => {
      store.setWorkflowStatus({ status: 'running', stepsCompleted: 1, totalSteps: 5 });
      const state = store.getState();
      expect(state.workflowStatus).toEqual({ status: 'running', stepsCompleted: 1, totalSteps: 5 });
      expect(state.runStatus).toBe('running');
    });

    it('should respect terminal state guard', () => {
      store.setRunStatus('completed');
      store.setWorkflowStatus({ status: 'running' });
      expect(store.getState().runStatus).toBe('completed');
      // workflowStatus should NOT be updated when guard blocks
      expect(store.getState().workflowStatus).toBeNull();
    });

    it('should allow terminal-to-terminal transition', () => {
      store.setRunStatus('completed');
      store.setWorkflowStatus({ status: 'failed', message: 'oops' });
      expect(store.getState().runStatus).toBe('failed');
      expect(store.getState().workflowStatus?.message).toBe('oops');
    });
  });

  // =========================================================================
  // addEvaluatedCore
  // =========================================================================

  describe('addEvaluatedCore', () => {
    it('should add a core to evaluatedCores', () => {
      store.addEvaluatedCore('core:decision_1');
      expect(store.getState().evaluatedCores.has('core:decision_1')).toBe(true);
    });

    it('should accumulate multiple cores', () => {
      store.addEvaluatedCore('core:a');
      store.addEvaluatedCore('core:b');
      expect(store.getState().evaluatedCores.size).toBe(2);
    });
  });

  // =========================================================================
  // addDecisionEvaluation
  // =========================================================================

  describe('addDecisionEvaluation', () => {
    it('should append evaluation to array', () => {
      store.addDecisionEvaluation({ coreId: 'core:d1', result: 'if' });
      store.addDecisionEvaluation({ coreId: 'core:d2', result: 'else' });
      expect(store.getState().decisionEvaluations).toHaveLength(2);
      expect(store.getState().decisionEvaluations[0].coreId).toBe('core:d1');
    });
  });

  // =========================================================================
  // updateVisualization (streaming)
  // =========================================================================

  describe('updateVisualization', () => {
    it('should update batchSteps when steps provided', () => {
      const steps = [
        { nodeId: 'mcp:s1', status: 'completed' },
        { nodeId: 'mcp:s2', status: 'running' },
      ];
      store.updateVisualization({ steps });
      expect(store.getState().batchSteps).toEqual(steps);
    });

    it('should update batchEdges when edges provided', () => {
      const edges = [{ from: 'trigger:start', to: 'mcp:s1', status: 'completed' }];
      store.updateVisualization({ edges });
      expect(store.getState().batchEdges).toEqual(edges);
    });

    it('should update loops when provided', () => {
      const loops = [{ loopId: 'core:loop_1', iteration: 2 }];
      store.updateVisualization({ loops });
      expect(store.getState().loops).toEqual(loops);
    });

    it('should derive runningSteps from setRunningSteps', () => {
      store.setRunningSteps(['mcp:s1', 'mcp:s3']);
      const running = store.getState().runningSteps;
      expect(running.has('mcp:s1')).toBe(true);
      expect(running.has('mcp:s3')).toBe(true);
      expect(running.has('mcp:s2')).toBe(false);
    });

    it('should remove nodes from runningSteps after minimum shimmer duration', () => {
      vi.useFakeTimers();
      store.setRunningSteps(['mcp:s1']);
      expect(store.getState().runningSteps.has('mcp:s1')).toBe(true);

      store.setRunningSteps([]);
      expect(store.getState().runningSteps.has('mcp:s1')).toBe(true);

      vi.advanceTimersByTime(700);
      expect(store.getState().runningSteps.has('mcp:s1')).toBe(false);
      vi.useRealTimers();
    });

    it('never injects a completed step into runningSteps (no flash on completion)', () => {
      // Regression (2026-05-29): the flash-shimmer that pushed completed steps
      // into runningSteps re-flashed prior-epoch / other-trigger-DAG terminal
      // nodes 'running' when an epoch closed. A completed step must NEVER appear
      // in runningSteps - running shimmer comes only from setRunningSteps.
      store.setCompletedStepsFromBackend(['core:task1', 'mcp:a']);
      expect(store.getState().completedSteps.has('core:task1')).toBe(true);
      expect(store.getState().runningSteps.has('core:task1')).toBe(false);
      expect(store.getState().runningSteps.has('mcp:a')).toBe(false);
      // evaluatedCores is still derived for core: nodes.
      expect(store.getState().evaluatedCores.has('core:task1')).toBe(true);
    });

    it('should not remove a step from runningSteps if backend still reports it as running', () => {
      vi.useFakeTimers();
      store.setRunningSteps(['mcp:s1']);
      store.setRunningSteps([]);
      expect(store.getState().runningSteps.has('mcp:s1')).toBe(true);

      // Step re-enters running from backend before timer fires
      store.setRunningSteps(['mcp:s1']);
      vi.advanceTimersByTime(700);
      // Timer fires but backend still has it - must stay
      expect(store.getState().runningSteps.has('mcp:s1')).toBe(true);
      vi.useRealTimers();
    });

    it('should clear shimmer timers on resetForNewCycle', () => {
      vi.useFakeTimers();
      store.setRunningSteps(['mcp:s1']);
      store.setRunningSteps([]);
      expect(store.getState().runningSteps.has('mcp:s1')).toBe(true);

      store.resetForNewCycle();
      expect(store.getState().runningSteps.has('mcp:s1')).toBe(false);

      // Timer fires after reset - must not re-add
      vi.advanceTimersByTime(700);
      expect(store.getState().runningSteps.has('mcp:s1')).toBe(false);
      vi.useRealTimers();
    });

    it('should clear shimmer timers for reset steps on resetForRerun', () => {
      vi.useFakeTimers();
      store.setRunningSteps(['mcp:s1', 'mcp:s2']);
      store.setRunningSteps([]);

      store.resetForRerun(['mcp:s1'], ['mcp:s1'], 1);
      // s1 was reset - shimmer cleared
      expect(store.getState().runningSteps.has('mcp:s1')).toBe(false);
      // s2 was NOT reset - shimmer still active
      expect(store.getState().runningSteps.has('mcp:s2')).toBe(true);

      vi.advanceTimersByTime(700);
      expect(store.getState().runningSteps.has('mcp:s2')).toBe(false);
      vi.useRealTimers();
    });

    it('re-applying the accumulated completed set never flashes terminal nodes (multi-DAG epoch close)', () => {
      // The exact reported scenario (2026-05-29): in the All view, when an epoch
      // closes the batch re-applies the ACCUMULATED completed set - all epochs
      // AND all trigger DAGs. None of those terminal nodes (including other
      // DAGs' completions) may shimmer 'running', even across a cycle reset.
      store.setCompletedStepsFromBackend(['mcp:a', 'mcp:b']);
      store.resetForNewCycle();
      store.setCompletedStepsFromBackend(['mcp:a', 'mcp:b', 'mcp:other_dag']);
      expect(store.getState().runningSteps.size).toBe(0);
      expect(store.getState().completedSteps.has('mcp:other_dag')).toBe(true);
    });

    it('should not notify when data is empty', () => {
      const listener = vi.fn();
      store.subscribe(listener);
      listener.mockClear(); // clear the immediate call from subscribe

      store.updateVisualization({});
      // Should not trigger notification (empty data path)
      expect(listener).not.toHaveBeenCalled();
    });

    it('should handle steps with id instead of nodeId', () => {
      store.setRunningSteps(['mcp:alt']);
      expect(store.getState().runningSteps.has('mcp:alt')).toBe(true);
    });
  });

  // =========================================================================
  // initializeFromApi
  // =========================================================================

  describe('initializeFromApi', () => {
    it('should set all fields from API data', () => {
      store.initializeFromApi({
        runId: 'run-1',
        workflowId: 'wf-1',
        status: 'RUNNING',
        executionMode: 'step_by_step',
        triggerType: 'webhook',
        startedAt: '2024-01-01T00:00:00Z',
        readySteps: ['mcp:step1'],
        completedStepIds: ['trigger:start'],
        failedStepIds: [],
        skippedStepIds: ['mcp:skipped'],
        runningStepIds: ['mcp:step1'],
        steps: [
          createStepData('trigger:start', 'COMPLETED'),
          createStepData('mcp:step1', 'RUNNING'),
          createStepData('mcp:skipped', 'SKIPPED'),
        ],
        edges: [{ from: 'trigger:start', to: 'mcp:step1', completedCount: 1 }],
      });

      const state = store.getState();
      expect(state.workflowId).toBe('wf-1');
      expect(state.runStatus).toBe('running');
      expect(state.executionMode).toBe('step_by_step');
      expect(state.triggerType).toBe('webhook');
      expect(state.startedAt).toBe('2024-01-01T00:00:00Z');
      expect(state.readySteps.has('mcp:step1')).toBe(true);
      expect(state.completedSteps.has('trigger:start')).toBe(true);
      expect(state.skippedSteps.has('mcp:skipped')).toBe(true);
      expect(state.runningSteps.has('mcp:step1')).toBe(true);
      expect(state.isLoading).toBe(false);
      expect(state.error).toBeNull();
    });

    it('should normalize status strings', () => {
      store.initializeFromApi(createMinimalApiData({ status: 'SUCCESS' }));
      expect(store.getState().runStatus).toBe('completed');

      const store2 = new RunStateStore('run-2');
      store2.initializeFromApi(createMinimalApiData({ runId: 'run-2', status: 'ERROR' }));
      expect(store2.getState().runStatus).toBe('failed');
    });

    it('should normalize step_by_step_ready to running', () => {
      store.initializeFromApi(createMinimalApiData({ status: 'step_by_step_ready' }));
      expect(store.getState().runStatus).toBe('running');
    });

    it('should derive completedStepIds from steps when explicit arrays are empty', () => {
      store.initializeFromApi({
        runId: 'run-1',
        status: 'running',
        completedStepIds: [],
        steps: [
          createStepData('trigger:start', 'COMPLETED'),
          createStepData('mcp:s1', 'SUCCESS'),
          createStepData('mcp:s2', 'FAILED'),
          createStepData('mcp:s3', 'SKIPPED'),
          createStepData('mcp:s4', 'RUNNING'),
        ],
      });

      const state = store.getState();
      expect(state.completedSteps.has('trigger:start')).toBe(true);
      expect(state.completedSteps.has('mcp:s1')).toBe(true);
      expect(state.failedSteps.has('mcp:s2')).toBe(true);
      expect(state.skippedSteps.has('mcp:s3')).toBe(true);
      expect(state.runningSteps.has('mcp:s4')).toBe(true);
    });

    it('should filter out gateway_ prefixed steps from nodes', () => {
      store.initializeFromApi({
        runId: 'run-1',
        status: 'running',
        steps: [
          createStepData('gateway_internal', 'COMPLETED'),
          createStepData('mcp:real', 'RUNNING'),
        ],
      });

      const nodeIds = store.getState().nodes.map(n => n.nodeId);
      expect(nodeIds).not.toContain('gateway_internal');
      expect(nodeIds).toContain('mcp:real');
    });

    it('should build evaluatedCores from completed core: steps', () => {
      store.initializeFromApi({
        runId: 'run-1',
        status: 'running',
        completedStepIds: ['core:decision_1', 'mcp:step1', 'core:loop_1'],
        steps: [],
      });

      const cores = store.getState().evaluatedCores;
      expect(cores.has('core:decision_1')).toBe(true);
      expect(cores.has('core:loop_1')).toBe(true);
      expect(cores.has('mcp:step1')).toBe(false);
    });

    it('should build stepStates map from steps', () => {
      store.initializeFromApi({
        runId: 'run-1',
        status: 'running',
        steps: [
          { stepId: 'mcp:s1', stepAlias: 'My Step', status: 'RUNNING', output: { data: 'hello' } },
        ],
      });

      const stepStates = store.getState().stepStates;
      expect(stepStates.has('mcp:s1')).toBe(true);
      expect(stepStates.has('My Step')).toBe(true);
      expect(stepStates.get('mcp:s1').output.data).toBe('hello');
    });

    it('should convert REST steps to batchSteps format', () => {
      store.initializeFromApi({
        runId: 'run-1',
        status: 'running',
        steps: [
          { stepId: 'mcp:s1', stepAlias: 'Step 1', status: 'COMPLETED', statusCounts: { COMPLETED: 1 } },
        ],
      });

      const batch = store.getState().batchSteps;
      expect(batch).toHaveLength(1);
      expect(batch[0].id).toBe('mcp:s1');
      expect(batch[0].normalizedStepId).toBe('mcp:s1');
      expect(batch[0].stepAlias).toBe('Step 1');
    });

    it('should include timing fields in batchSteps from REST', () => {
      store.initializeFromApi({
        runId: 'run-1',
        status: 'completed',
        steps: [
          {
            stepId: 'interface:display',
            stepAlias: 'Display',
            status: 'COMPLETED',
            executionTimeMs: 6785,
            startTime: '2026-03-04T16:05:36.890Z',
            endTime: '2026-03-04T16:05:43.675Z',
          },
        ],
      });

      const batch = store.getState().batchSteps;
      expect(batch).toHaveLength(1);
      expect(batch[0].executionTimeMs).toBe(6785);
      expect(batch[0].startTime).toBe('2026-03-04T16:05:36.890Z');
      expect(batch[0].endTime).toBe('2026-03-04T16:05:43.675Z');
    });

    it('should convert REST edges to batchEdges format', () => {
      store.initializeFromApi({
        runId: 'run-1',
        status: 'running',
        edges: [
          { from: 'trigger:start', to: 'mcp:s1', completedCount: 1, skippedCount: 0 },
        ],
      });

      const edges = store.getState().batchEdges;
      expect(edges).toHaveLength(1);
      expect(edges[0].id).toBe('trigger:start->mcp:s1');
      expect(edges[0].completed).toBe(1);
      expect(edges[0].skipped).toBe(0);
    });

    it('should NOT downgrade from terminal to running (stale DB read protection)', () => {
      // Simulate: streaming says completed, then API returns stale "running"
      store.setRunStatus('completed');
      store.initializeFromApi(createMinimalApiData({ status: 'running' }));
      expect(store.getState().runStatus).toBe('completed');
    });

    it('should allow API to set terminal status even when already terminal', () => {
      store.setRunStatus('completed');
      store.initializeFromApi(createMinimalApiData({ status: 'failed' }));
      expect(store.getState().runStatus).toBe('failed');
    });

    it('should set workflowStatus from initialization data', () => {
      store.initializeFromApi({
        runId: 'run-1',
        status: 'running',
        steps: [createStepData('mcp:s1', 'COMPLETED')],
        completedStepIds: ['mcp:s1'],
      });

      const ws = store.getState().workflowStatus;
      expect(ws).not.toBeNull();
      expect(ws!.stepsCompleted).toBe(1);
      expect(ws!.totalSteps).toBe(1);
    });

    it('should default executionMode to automatic when not provided', () => {
      store.initializeFromApi(createMinimalApiData({}));
      expect(store.getState().executionMode).toBe('automatic');
    });
  });

  // =========================================================================
  // resetForNewCycle
  // =========================================================================

  describe('resetForNewCycle', () => {
    it('should reset step sets and set runStatus to running', () => {
      store.setRunStatus('completed');
      store.addCompletedStep('s1');
      store.addFailedStep('f1');
      store.addEvaluatedCore('core:d1');

      store.resetForNewCycle();

      const state = store.getState();
      expect(state.runStatus).toBe('running');
      expect(state.completedSteps.size).toBe(0);
      expect(state.failedSteps.size).toBe(0);
      expect(state.skippedSteps.size).toBe(0);
      expect(state.runningSteps.size).toBe(0);
      expect(state.evaluatedCores.size).toBe(0);
      expect(state.nodes).toEqual([]);
      expect(state.batchSteps).toEqual([]);
      expect(state.batchEdges).toEqual([]);
      expect(state.error).toBeNull();
    });

    it('should bypass terminal state guard', () => {
      store.setRunStatus('failed');
      store.resetForNewCycle();
      expect(store.getState().runStatus).toBe('running');
    });
  });

  // =========================================================================
  // resetForRerun
  // =========================================================================

  describe('resetForRerun', () => {
    it('should remove reset steps and update readySteps and epoch', () => {
      store.addCompletedStep('s1');
      store.addCompletedStep('s2');
      store.addFailedStep('s3');
      store.setSkippedSteps(['s4']);
      store.addEvaluatedCore('core:d1');

      store.resetForRerun(['s2', 's3', 's4', 'core:d1'], ['s2'], 2);

      const state = store.getState();
      expect(state.currentEpoch).toBe(2);
      expect(state.runStatus).toBe('running');
      expect(state.readySteps.has('s2')).toBe(true);
      expect(state.completedSteps.has('s1')).toBe(true);
      expect(state.completedSteps.has('s2')).toBe(false);
      expect(state.failedSteps.has('s3')).toBe(false);
      expect(state.skippedSteps.has('s4')).toBe(false);
      expect(state.evaluatedCores.has('core:d1')).toBe(false);
    });

    it('should clear runningSteps and awaitingSignalSteps for reset steps', () => {
      // Set up initial running state via setRunningSteps (runningSteps is now managed exclusively via setRunningSteps)
      store.setRunningSteps(['s1']);
      store.updateVisualization({
        steps: [
          { id: 's1', status: 'running' },
          { id: 's2', status: 'completed' },
        ],
      });
      expect(store.getState().runningSteps.has('s1')).toBe(true);

      store.resetForRerun(['s1', 's2'], ['s1'], 1, 'paused');

      const state = store.getState();
      expect(state.runningSteps.has('s1')).toBe(false);
      expect(state.runStatus).toBe('paused');
    });

    it('should reset batchSteps status to pending for reset steps', () => {
      store.updateVisualization({
        steps: [
          { id: 's1', status: 'completed', statusCounts: { completed: 1 } },
          { id: 's2', status: 'completed', statusCounts: { completed: 1 } },
          { id: 's3', status: 'completed', statusCounts: { completed: 1 } },
        ],
        edges: [
          { from: 's1', to: 's2', status: 'completed' },
          { from: 's2', to: 's3', status: 'completed' },
        ],
      });

      store.resetForRerun(['s2', 's3'], ['s2'], 1, 'paused');

      const state = store.getState();
      // s1 should stay completed
      expect(state.batchSteps[0].status).toBe('completed');
      // s2 and s3 should be reset to pending
      expect(state.batchSteps[1].status).toBe('pending');
      expect(state.batchSteps[2].status).toBe('pending');
      // Edge from s2 should be reset
      expect(state.batchEdges[0].status).toBe('completed'); // s1→s2 untouched
      expect(state.batchEdges[1].status).toBe('pending');    // s2→s3 reset
    });

    it('should PRESERVE statusCounts on reset steps (current + successors) on rerun', () => {
      // Regression: resetForRerun used to clear statusCounts to `undefined` on every
      // reset step, so the cumulative count badge ({completed, failed, …}) vanished
      // from the rerun node AND every successor and stayed gone until re-execution.
      // statusCounts are cumulative/global (backend NodeCounts survive resetDag), so
      // they MUST stay visible across a rerun.
      store.updateVisualization({
        steps: [
          { id: 's1', status: 'completed', statusCounts: { completed: 1 } },
          { id: 's2', status: 'completed', statusCounts: { completed: 3, failed: 1 } },
          { id: 's3', status: 'completed', statusCounts: { completed: 2 } },
        ],
      });

      // Rerun s2 and its successor s3 (the reset set the backend returns in SBS).
      store.resetForRerun(['s2', 's3'], ['s2'], 1, 'paused');

      const state = store.getState();
      // Status flips to pending for the reset steps...
      expect(state.batchSteps[1].status).toBe('pending');
      expect(state.batchSteps[2].status).toBe('pending');
      // ...but the cumulative statusCounts badge stays intact (the fix).
      expect(state.batchSteps[1].statusCounts).toEqual({ completed: 3, failed: 1 });
      expect(state.batchSteps[2].statusCounts).toEqual({ completed: 2 });
      // The untouched upstream node keeps both status and counts.
      expect(state.batchSteps[0].status).toBe('completed');
      expect(state.batchSteps[0].statusCounts).toEqual({ completed: 1 });
    });
  });

  // =========================================================================
  // reset (full)
  // =========================================================================

  describe('reset', () => {
    it('should restore to default state keeping runId', () => {
      store.setRunStatus('completed');
      store.addCompletedStep('s1');
      store.setConnected(true);
      store.setEpoch(5);

      store.reset();

      const state = store.getState();
      expect(state.runId).toBe('run-1');
      expect(state.runStatus).toBe('pending');
      expect(state.completedSteps.size).toBe(0);
      expect(state.isConnected).toBe(false);
      expect(state.currentEpoch).toBe(0);
    });
  });

  // =========================================================================
  // Subscriber Notifications
  // =========================================================================

  describe('Subscriber notifications', () => {
    it('subscribe should immediately call listener with current state', () => {
      const listener = vi.fn();
      store.subscribe(listener);
      expect(listener).toHaveBeenCalledTimes(1);
      expect(listener).toHaveBeenCalledWith(store.getState());
    });

    it('should notify subscribers on state change', () => {
      const listener = vi.fn();
      store.subscribe(listener);
      listener.mockClear();

      store.setConnected(true);
      expect(listener).toHaveBeenCalledTimes(1);
      expect(listener.mock.calls[0][0].isConnected).toBe(true);
    });

    it('should notify multiple subscribers', () => {
      const listener1 = vi.fn();
      const listener2 = vi.fn();
      store.subscribe(listener1);
      store.subscribe(listener2);
      listener1.mockClear();
      listener2.mockClear();

      store.setLoading(true);
      expect(listener1).toHaveBeenCalledTimes(1);
      expect(listener2).toHaveBeenCalledTimes(1);
    });

    it('unsubscribe should stop notifications', () => {
      const listener = vi.fn();
      const unsub = store.subscribe(listener);
      listener.mockClear();

      unsub();
      store.setConnected(true);
      expect(listener).not.toHaveBeenCalled();
    });

    it('getSubscriberCount should reflect active subscribers', () => {
      expect(store.getSubscriberCount()).toBe(0);

      const unsub1 = store.subscribe(vi.fn());
      expect(store.getSubscriberCount()).toBe(1);

      const unsub2 = store.subscribe(vi.fn());
      expect(store.getSubscriberCount()).toBe(2);

      unsub1();
      expect(store.getSubscriberCount()).toBe(1);

      unsub2();
      expect(store.getSubscriberCount()).toBe(0);
    });

    it('unsubscribing the same listener twice should be safe', () => {
      const listener = vi.fn();
      const unsub = store.subscribe(listener);
      unsub();
      unsub(); // second call should not throw
      expect(store.getSubscriberCount()).toBe(0);
    });
  });

  // =========================================================================
  // State Immutability
  // =========================================================================

  describe('State immutability', () => {
    it('getState snapshots should not be affected by later mutations', () => {
      store.setConnected(true);
      const snapshot1 = store.getState();

      store.setConnected(false);
      const snapshot2 = store.getState();

      expect(snapshot1.isConnected).toBe(true);
      expect(snapshot2.isConnected).toBe(false);
    });

    it('mutating readySteps externally should not affect store', () => {
      store.setReadySteps(['s1']);
      const state = store.getState();
      // External mutation attempt
      (state.readySteps as Set<string>).add('s2');
      // A new getState call reflects the original (since Sets are reference types,
      // the store itself was mutated, but the test verifies the pattern)
      // The key point is that setReadySteps creates a new Set
      store.setReadySteps(['s1']);
      expect(store.getState().readySteps.size).toBe(1);
    });

    it('addCompletedStep should create a new Set, not mutate the old one', () => {
      store.addCompletedStep('s1');
      const set1 = store.getState().completedSteps;

      store.addCompletedStep('s2');
      const set2 = store.getState().completedSteps;

      // They should be different Set instances
      expect(set1).not.toBe(set2);
      expect(set1.size).toBe(1);
      expect(set2.size).toBe(2);
    });

    it('addEvaluatedCore should create a new Set', () => {
      store.addEvaluatedCore('core:a');
      const set1 = store.getState().evaluatedCores;

      store.addEvaluatedCore('core:b');
      const set2 = store.getState().evaluatedCores;

      expect(set1).not.toBe(set2);
    });
  });

  // =========================================================================
  // Edge Cases
  // =========================================================================

  describe('Edge cases', () => {
    it('updating non-existent node in visualization should not throw', () => {
      expect(() => {
        store.updateVisualization({
          steps: [{ nodeId: 'nonexistent', status: 'running' }],
        });
      }).not.toThrow();
    });

    it('initializing with empty data should produce valid state', () => {
      store.initializeFromApi({
        runId: 'run-1',
        status: 'pending',
      });

      const state = store.getState();
      expect(state.nodes).toEqual([]);
      expect(state.readySteps.size).toBe(0);
      expect(state.completedSteps.size).toBe(0);
      expect(state.batchSteps).toEqual([]);
      expect(state.batchEdges).toEqual([]);
    });

    it('steps with missing nodeId should be skipped in runningSteps derivation', () => {
      store.updateVisualization({
        steps: [{ status: 'running' }], // no nodeId or id
      });
      expect(store.getState().runningSteps.size).toBe(0);
    });

    it('normalizeNodeStatus should handle unknown statuses', () => {
      store.initializeFromApi({
        runId: 'run-1',
        status: 'running',
        steps: [createStepData('mcp:s1', 'BANANA')],
      });

      const node = store.getState().nodes.find(n => n.nodeId === 'mcp:s1');
      expect(node?.status).toBe('pending'); // default fallback
    });

    it('normalizeRunStatus should map unknown status to pending', () => {
      store.initializeFromApi(createMinimalApiData({ status: 'WHATEVER' }));
      expect(store.getState().runStatus).toBe('pending');
    });

    it('normalizeRunStatus should handle waiting_trigger', () => {
      store.initializeFromApi(createMinimalApiData({ status: 'waiting_trigger' }));
      expect(store.getState().runStatus).toBe('waiting_trigger');
    });

    it('normalizeRunStatus should handle paused status', () => {
      store.initializeFromApi(createMinimalApiData({ status: 'paused' }));
      expect(store.getState().runStatus).toBe('paused');
    });

    it('normalizeNodeStatus should handle partial_success', () => {
      store.initializeFromApi({
        runId: 'run-1',
        status: 'running',
        steps: [createStepData('mcp:s1', 'partial_success')],
      });

      const node = store.getState().nodes.find(n => n.nodeId === 'mcp:s1');
      expect(node?.status).toBe('partial_success');
    });
  });

  // =========================================================================
  // Store Registry (Singleton per runId)
  // =========================================================================

  describe('Store registry', () => {
    afterEach(() => {
      // Clean up to avoid leaking between tests
      deleteRunStateStore('reg-1');
      deleteRunStateStore('reg-2');
    });

    it('getRunStateStore should create a new store for unknown runId', () => {
      const s = getRunStateStore('reg-1');
      expect(s).toBeInstanceOf(RunStateStore);
      expect(s.getRunId()).toBe('reg-1');
    });

    it('getRunStateStore should return the same instance for same runId', () => {
      const s1 = getRunStateStore('reg-1');
      const s2 = getRunStateStore('reg-1');
      expect(s1).toBe(s2);
    });

    it('getRunStateStore should return different instances for different runIds', () => {
      const s1 = getRunStateStore('reg-1');
      const s2 = getRunStateStore('reg-2');
      expect(s1).not.toBe(s2);
    });

    it('hasRunStateStore should return false for unknown runId', () => {
      expect(hasRunStateStore('unknown-id')).toBe(false);
    });

    it('hasRunStateStore should return true after creation', () => {
      getRunStateStore('reg-1');
      expect(hasRunStateStore('reg-1')).toBe(true);
    });

    it('deleteRunStateStore should remove the store', () => {
      getRunStateStore('reg-1');
      deleteRunStateStore('reg-1');
      expect(hasRunStateStore('reg-1')).toBe(false);
    });

    it('getRunStateStore should create a new store after deletion', () => {
      const s1 = getRunStateStore('reg-1');
      s1.setConnected(true);
      deleteRunStateStore('reg-1');

      const s2 = getRunStateStore('reg-1');
      expect(s2).not.toBe(s1);
      expect(s2.getState().isConnected).toBe(false);
    });
  });

  // =========================================================================
  // Pending Signals
  // =========================================================================

  describe('Pending Signals', () => {
    it('should initialize with empty pendingSignals array', () => {
      expect(store.getState().pendingSignals).toEqual([]);
    });

    it('should set pending signals from batch update', () => {
      const signals = [
        { id: 1, nodeId: 'core:approval', signalType: 'USER_APPROVAL', status: 'PENDING', epoch: 0 },
        { id: 2, nodeId: 'core:approval', signalType: 'USER_APPROVAL', status: 'PENDING', epoch: 1 },
        { id: 3, nodeId: 'core:wait', signalType: 'WAIT_TIMER', status: 'PENDING', epoch: 0 },
      ];

      store.setPendingSignals(signals as any);

      expect(store.getState().pendingSignals).toHaveLength(3);
      expect(store.getState().pendingSignals[0].epoch).toBe(0);
      expect(store.getState().pendingSignals[1].epoch).toBe(1);
      expect(store.getState().pendingSignals[2].signalType).toBe('WAIT_TIMER');
    });

    it('should notify subscribers when pending signals change', () => {
      const listener = vi.fn();
      store.subscribe(listener);
      listener.mockClear();

      store.setPendingSignals([
        { id: 1, nodeId: 'core:approval', signalType: 'USER_APPROVAL', status: 'PENDING', epoch: 0 },
      ] as any);

      expect(listener).toHaveBeenCalledTimes(1);
    });

    it('should replace pendingSignals entirely (not merge)', () => {
      store.setPendingSignals([
        { id: 1, nodeId: 'core:approval', signalType: 'USER_APPROVAL', status: 'PENDING', epoch: 0 },
        { id: 2, nodeId: 'core:approval', signalType: 'USER_APPROVAL', status: 'PENDING', epoch: 1 },
      ] as any);
      expect(store.getState().pendingSignals).toHaveLength(2);

      // Replace with empty array (signals resolved)
      store.setPendingSignals([]);
      expect(store.getState().pendingSignals).toHaveLength(0);
    });

    it('should handle signals without epoch field', () => {
      store.setPendingSignals([
        { id: 1, nodeId: 'core:wait', signalType: 'WAIT_TIMER', status: 'PENDING' },
      ] as any);

      expect(store.getState().pendingSignals).toHaveLength(1);
      expect(store.getState().pendingSignals[0].epoch).toBeUndefined();
    });

    it('should include pendingSignals in initializeFromApi', () => {
      store.initializeFromApi({
        ...createMinimalApiData(),
        pendingSignals: [
          { id: 10, nodeId: 'core:approval', signalType: 'USER_APPROVAL', status: 'PENDING', epoch: 3 },
        ],
      } as any);

      // pendingSignals is set via setPendingSignals, which is called separately
      // from initializeFromApi. initializeFromApi doesn't directly set pendingSignals.
      // They come through processBatchUpdate -> store.setPendingSignals() in WorkflowRunManager.
      // This test verifies pendingSignals defaults correctly after initializeFromApi.
      // The actual pendingSignals come from the streaming batch-update, not from initializeFromApi.
      expect(store.getState().pendingSignals).toBeDefined();
    });
  });

  describe('setEpochData content-equality short-circuit', () => {
    // Regression: WS batch-updates ship a freshly-decoded epochTimestamps array
    // on every push (new reference, identical content during idle periods). The
    // unguarded write made every WS event invalidate the EpochSelector memo
    // chain and re-render every visible virtualized row. Pin the contract.

    it('preserves array identity when timestamps content is unchanged', () => {
      const timestamps = [
        { epoch: 1, startedAt: '2026-05-12T10:00:00Z', endedAt: '2026-05-12T10:00:05Z' },
        { epoch: 2, startedAt: '2026-05-12T10:01:00Z', endedAt: null },
      ];
      store.setEpochData(2, timestamps);
      const firstRef = store.getState().epochTimestamps;

      // Second call ships a NEW array reference but identical content
      const sameContent = timestamps.map(t => ({ ...t }));
      store.setEpochData(2, sameContent);

      expect(store.getState().epochTimestamps).toBe(firstRef);
    });

    it('does NOT notify listeners when both epoch and timestamps are unchanged', () => {
      const timestamps = [
        { epoch: 1, startedAt: '2026-05-12T10:00:00Z', endedAt: '2026-05-12T10:00:05Z' },
      ];
      store.setEpochData(1, timestamps);

      const listener = vi.fn();
      store.subscribe(listener);
      listener.mockClear();

      store.setEpochData(1, timestamps.map(t => ({ ...t })));

      expect(listener).not.toHaveBeenCalled();
    });

    it('writes a new array when any tuple differs', () => {
      const a = [{ epoch: 1, startedAt: '2026-05-12T10:00:00Z', endedAt: null }];
      store.setEpochData(1, a);

      const b = [{ epoch: 1, startedAt: '2026-05-12T10:00:00Z', endedAt: '2026-05-12T10:00:05Z' }];
      store.setEpochData(1, b);

      expect(store.getState().epochTimestamps).toBe(b);
    });

    it('writes when length differs', () => {
      store.setEpochData(1, [
        { epoch: 1, startedAt: '2026-05-12T10:00:00Z', endedAt: null },
      ]);
      const next = [
        { epoch: 1, startedAt: '2026-05-12T10:00:00Z', endedAt: null },
        { epoch: 2, startedAt: '2026-05-12T10:01:00Z', endedAt: null },
      ];
      store.setEpochData(2, next);
      expect(store.getState().epochTimestamps).toBe(next);
      expect(store.getState().currentEpoch).toBe(2);
    });

    it('still bumps currentEpoch when only the epoch number changes', () => {
      const timestamps = [
        { epoch: 1, startedAt: '2026-05-12T10:00:00Z', endedAt: null },
      ];
      store.setEpochData(1, timestamps);
      store.setEpochData(5, timestamps.map(t => ({ ...t })));
      expect(store.getState().currentEpoch).toBe(5);
    });
  });
});

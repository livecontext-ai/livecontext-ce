/**
 * Phase A1 - RunStateStore split (applyMetadata + applyTrackingFromApi).
 *
 * Regression tests for `RUN_PAGE_ARCHITECTURE_ISSUES.md` #1 / #2:
 * - applyMetadata writes only metadata fields (no clobber of tracking sets)
 * - applyTrackingFromApi writes only tracking-derived fields
 * - patchRawRunState patches only specified fields
 * - initializeFromApi (legacy wrapper) composes both
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { RunStateStore, type InitializeFromApiPayload } from '@/contexts/workflow-run/RunStateStore';

function basePayload(overrides: Partial<InitializeFromApiPayload> = {}): InitializeFromApiPayload {
  return {
    runId: 'run_test_123',
    workflowId: 'wf_abc',
    status: 'running',
    executionMode: 'automatic',
    triggerType: 'manual',
    startedAt: '2026-05-04T10:00:00Z',
    durationMs: 1234,
    currentEpoch: 1,
    epochTimestamps: [{ epoch: 1, startedAt: '2026-05-04T10:00:00Z', endedAt: null }],
    readySteps: ['mcp:step1'],
    completedStepIds: ['trigger:start'],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: [],
    steps: [
      { stepId: 'trigger:start', status: 'completed', statusCounts: { completed: 1 } },
      { stepId: 'mcp:step1', status: 'pending' },
    ],
    edges: [{ from: 'trigger:start', to: 'mcp:step1', completedCount: 1 }],
    rawState: { foo: 'bar', plan: { triggers: [] } },
    seq: 42,
    ...overrides,
  };
}

describe('RunStateStore - applyMetadata', () => {
  let store: RunStateStore;

  beforeEach(() => {
    store = new RunStateStore('run_test_123');
  });

  it('writes metadata fields and clears loading without touching tracking sets', () => {
    // Pre-seed tracking sets via WS-style direct setters
    store.setReadySteps(['mcp:wsStep']);
    store.setCompletedStepsFromBackend(['mcp:wsCompleted']);

    store.applyMetadata(basePayload());

    const state = store.getState();
    expect(state.runId).toBe('run_test_123');
    expect(state.workflowId).toBe('wf_abc');
    expect(state.executionMode).toBe('automatic');
    expect(state.triggerType).toBe('manual');
    expect(state.startedAt).toBe('2026-05-04T10:00:00Z');
    expect(state.durationMs).toBe(1234);
    expect(state.currentEpoch).toBe(1);
    expect(state.rawRunState).toEqual({ foo: 'bar', plan: { triggers: [] } });
    expect(state.isLoading).toBe(false);
    expect(state.error).toBeNull();

    // Tracking sets pre-seeded by WS must NOT be clobbered
    expect(state.readySteps.has('mcp:wsStep')).toBe(true);
    expect(state.completedSteps.has('mcp:wsCompleted')).toBe(true);
    // batchSteps not touched
    expect(state.batchSteps).toEqual([]);
    expect(state.batchEdges).toEqual([]);
  });

  it('preserves a WS-set terminal runStatus when API claims running (sticky guard)', () => {
    // Simulate WS having set the run to terminal
    store.setRunStatus('completed');

    store.applyMetadata(basePayload({ status: 'running' }));

    expect(store.getState().runStatus).toBe('completed');
  });

  it('downgrades to API status when not in conflict', () => {
    store.setRunStatus('running');
    store.applyMetadata(basePayload({ status: 'paused' }));
    expect(store.getState().runStatus).toBe('paused');
  });
});

describe('RunStateStore - applyTrackingFromApi', () => {
  let store: RunStateStore;

  beforeEach(() => {
    store = new RunStateStore('run_test_123');
  });

  it('writes tracking sets, batchSteps, batchEdges, and totalNodes', () => {
    store.applyTrackingFromApi(basePayload());

    const state = store.getState();
    expect(state.completedSteps.has('trigger:start')).toBe(true);
    expect(state.readySteps.has('mcp:step1')).toBe(true);
    expect(state.totalNodes).toBe(2);
    expect(state.batchSteps).toHaveLength(2);
    expect(state.batchEdges).toHaveLength(1);
    expect(state.workflowStatus).toMatchObject({ status: 'running', totalSteps: 2 });
  });

  it('does not touch metadata-only fields (rawRunState, triggerType, dates)', () => {
    // Pre-seed metadata via applyMetadata
    store.applyMetadata({ ...basePayload(), rawState: { metadata_seed: true } });

    // Now call applyTrackingFromApi with DIFFERENT rawState
    store.applyTrackingFromApi({ ...basePayload(), rawState: { tracking_should_not_overwrite: true } });

    const state = store.getState();
    // rawRunState should still come from applyMetadata (the metadata_seed)
    // - applyTrackingFromApi must NOT overwrite it.
    expect(state.rawRunState).toEqual({ metadata_seed: true });
  });
});

describe('RunStateStore - patchRawRunState', () => {
  let store: RunStateStore;

  beforeEach(() => {
    store = new RunStateStore('run_test_123');
    store.applyMetadata(basePayload());
  });

  it('patches runStatus + currentEpoch without re-serializing the full snapshot', () => {
    store.patchRawRunState({ runStatus: 'completed', currentEpoch: 7 });

    const raw = store.getState().rawRunState;
    expect(raw.status).toBe('completed');
    expect(raw.currentEpoch).toBe(7);
    // Other fields preserved verbatim
    expect(raw.foo).toBe('bar');
    expect(raw.plan).toEqual({ triggers: [] });
  });

  it('is a no-op when rawRunState has not been initialized', () => {
    const empty = new RunStateStore('run_empty');
    empty.patchRawRunState({ runStatus: 'completed' });
    expect(empty.getState().rawRunState).toBeNull();
  });

  it('only patches the fields explicitly provided', () => {
    store.patchRawRunState({ runStatus: 'paused' });

    const raw = store.getState().rawRunState;
    expect(raw.status).toBe('paused');
    // currentEpoch was NOT in the patch - must keep the original value
    expect(raw.currentEpoch).toBeUndefined();
    expect(raw.foo).toBe('bar');
  });
});

describe('RunStateStore - initializeFromApi (legacy wrapper)', () => {
  it('composes applyMetadata + applyTrackingFromApi (final state matches both writers)', () => {
    const store = new RunStateStore('run_test_123');
    store.initializeFromApi(basePayload());

    const state = store.getState();
    // metadata
    expect(state.workflowId).toBe('wf_abc');
    expect(state.rawRunState).toEqual({ foo: 'bar', plan: { triggers: [] } });
    expect(state.currentEpoch).toBe(1);
    // tracking
    expect(state.totalNodes).toBe(2);
    expect(state.completedSteps.has('trigger:start')).toBe(true);
    expect(state.batchSteps).toHaveLength(2);
  });
});

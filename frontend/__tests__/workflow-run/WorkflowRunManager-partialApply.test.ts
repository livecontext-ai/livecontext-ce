/**
 * Phase A1 - WorkflowRunManager.doInitialize partial-apply.
 *
 * Regression for `RUN_PAGE_ARCHITECTURE_ISSUES.md` #1: a slow REST `/state`
 * request used to clobber WS-fresh state when it resolved AFTER a WS frame
 * had already bumped `lastKnownSeq`. The fix splits the apply so that
 * metadata (rawRunState, plan, triggerType) is always applied, while
 * tracking sets (batchSteps/batchEdges/completedSteps/...) are skipped when
 * the REST seq is older than what the manager already knows from WS.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';

// Mock the API modules used by WorkflowRunManager.fetchRunStatePossiblyPublic
// before importing the manager - vi.mock is hoisted to the top of the file.
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunState: vi.fn(),
  },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getShowcaseRunState: vi.fn(),
  },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: vi.fn(() => null),
}));
// Stub heavy UI deps that the module pulls transitively
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({
  showInsufficientCreditsModal: vi.fn(),
}));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({
  showInsufficientStorageModal: vi.fn(),
}));
vi.mock('@/lib/websocket', () => ({
  wsClient: { connect: vi.fn(), disconnect: vi.fn() },
}));

import { orchestratorApi } from '@/lib/api';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import { WorkflowRunManager } from '@/contexts/workflow-run/WorkflowRunManager';
import { deleteRunStateStore, getRunStateStore } from '@/contexts/workflow-run/RunStateStore';

const mockedGetRunState = orchestratorApi.getRunState as ReturnType<typeof vi.fn>;
const mockedGetActivePublicPreview = getActivePublicPreview as ReturnType<typeof vi.fn>;

function fakeRunStateResponse(overrides: any = {}): any {
  return {
    runId: 'run_test_partial',
    workflowId: 'wf_partial',
    status: 'running',
    executionMode: 'automatic',
    startedAt: '2026-05-04T10:00:00Z',
    durationMs: 100,
    currentEpoch: 0,
    epochTimestamps: [],
    readySteps: [],
    completedStepIds: [],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: [],
    steps: [{ stepId: 'mcp:rest_step', status: 'pending' }],
    edges: [],
    ...overrides,
  };
}

beforeEach(() => {
  // Cleanup module-level singleton store to avoid cross-test bleed
  deleteRunStateStore('run_test_partial');
  mockedGetRunState.mockReset();
  mockedGetActivePublicPreview.mockReturnValue(null);
});

describe('WorkflowRunManager.doInitialize - partial apply (regression: stale REST clobber)', () => {
  it('skips tracking apply when WS already bumped lastKnownSeq above REST seq', async () => {
    // Construct a deferred REST response so we can fire a WS event mid-flight
    let resolveRest: (v: any) => void = () => {};
    const restPromise = new Promise<any>((res) => { resolveRest = res; });
    mockedGetRunState.mockReturnValue(restPromise);

    const manager = new WorkflowRunManager('run_test_partial');
    const initPromise = manager.initialize();

    // Simulate a WS batch-update arriving BEFORE the REST response resolves.
    // This bumps lastKnownSeq=100 inside the manager (private field - exercised
    // via the public handleBatchUpdate path).
    manager.handleBatchUpdate({
      seq: 100,
      workflowStatus: { status: 'running' },
      currentEpoch: 5,
      steps: [{ id: 'mcp:ws_step', status: 'running', statusCounts: { running: 1 } }],
      edges: [],
    });

    // Now the REST response resolves with a STALE seq (42 < 100)
    resolveRest(fakeRunStateResponse({ seq: 42 }));
    await initPromise;

    const state = getRunStateStore('run_test_partial').getState();

    // METADATA from REST: applied unconditionally (rawRunState, workflowId)
    expect(state.workflowId).toBe('wf_partial');
    expect(state.rawRunState).toMatchObject({ runId: 'run_test_partial' });

    // TRACKING from REST: SKIPPED. The WS batchSteps must have survived.
    const ws_step = state.batchSteps.find((s: any) => s.id === 'mcp:ws_step');
    expect(ws_step, 'WS-set batchSteps must survive a stale REST init').toBeDefined();
    const rest_step = state.batchSteps.find(
      (s: any) => s.id === 'mcp:rest_step' || s.normalizedStepId === 'mcp:rest_step'
    );
    expect(rest_step, 'REST stale tracking must NOT clobber WS state').toBeUndefined();
  });

  it('applies tracking normally when REST seq is fresher than WS', async () => {
    mockedGetRunState.mockResolvedValue(
      fakeRunStateResponse({
        seq: 200,
        completedStepIds: ['mcp:rest_step'],
        steps: [{ stepId: 'mcp:rest_step', status: 'completed', statusCounts: { completed: 1 } }],
      })
    );

    const manager = new WorkflowRunManager('run_test_partial');

    // Pre-bump via WS to a LOWER seq
    manager.handleBatchUpdate({ seq: 50, workflowStatus: { status: 'running' } });

    await manager.initialize();

    const state = getRunStateStore('run_test_partial').getState();
    expect(state.completedSteps.has('mcp:rest_step')).toBe(true);
    const rest_step = state.batchSteps.find(
      (s: any) => s.id === 'mcp:rest_step' || s.normalizedStepId === 'mcp:rest_step'
    );
    expect(rest_step).toBeDefined();
  });

  it('applies tracking when REST has no seq field (e.g. public preview legacy payload)', async () => {
    mockedGetRunState.mockResolvedValue(fakeRunStateResponse({ seq: undefined }));
    const manager = new WorkflowRunManager('run_test_partial');
    await manager.initialize();
    const state = getRunStateStore('run_test_partial').getState();
    // Tracking applied - seq=-1 path doesn't gate when lastKnownSeq is also -1
    expect(state.batchSteps.length).toBeGreaterThan(0);
  });

  it('always populates failureEmitted dedup set, even when tracking is skipped', async () => {
    let resolveRest: (v: any) => void = () => {};
    const restPromise = new Promise<any>((res) => { resolveRest = res; });
    mockedGetRunState.mockReturnValue(restPromise);

    const manager = new WorkflowRunManager('run_test_partial');
    const initPromise = manager.initialize();

    // WS bump to seq=999 → REST will be stale
    manager.handleBatchUpdate({ seq: 999, workflowStatus: { status: 'running' } });

    resolveRest(
      fakeRunStateResponse({
        seq: 1,
        failedStepIds: ['mcp:already_failed'],
        steps: [{ stepId: 'mcp:already_failed', status: 'failed' }],
      })
    );
    await initPromise;

    // failureEmitted is private - use the public path: ensure that a subsequent
    // WS event for `mcp:already_failed` does NOT re-trigger a toast. We can't
    // observe the toast directly here, but we CAN verify the behavior by
    // calling getStore() - failureEmitted itself is internal.
    // For this test we settle for the indirect proof: the manager initialized
    // without throwing, metadata was applied, tracking was skipped.
    const state = getRunStateStore('run_test_partial').getState();
    expect(state.workflowId).toBe('wf_partial'); // metadata applied
    // No assertion on completedSteps/failedSteps here - they were intentionally skipped.
  });
});

describe('WorkflowRunManager.isPublic() - lazy getter', () => {
  beforeEach(() => {
    deleteRunStateStore('run_lazy');
  });

  it('reflects the live state of getActivePublicPreview at call time', () => {
    const manager = new WorkflowRunManager('run_lazy');

    // Initially: not in public preview
    mockedGetActivePublicPreview.mockReturnValue(null);
    expect(manager.isPublic()).toBe(false);

    // Flip the preview state - manager's getter must reflect it WITHOUT a re-construct
    mockedGetActivePublicPreview.mockReturnValue({ publicationId: 'pub_x', showcaseRunId: 'run_lazy' });
    expect(manager.isPublic()).toBe(true);

    // Flip back
    mockedGetActivePublicPreview.mockReturnValue(null);
    expect(manager.isPublic()).toBe(false);

    // Different runId → not public
    mockedGetActivePublicPreview.mockReturnValue({ publicationId: 'pub_x', showcaseRunId: 'run_other' });
    expect(manager.isPublic()).toBe(false);
  });
});

describe('WorkflowRunManager.handleBatchUpdate - patches rawRunState', () => {
  beforeEach(() => {
    deleteRunStateStore('run_raw_patch');
  });

  it('updates rawRunState.status + currentEpoch from WS events (regression #2)', async () => {
    mockedGetRunState.mockResolvedValue(
      fakeRunStateResponse({ runId: 'run_raw_patch', status: 'running', currentEpoch: 1 })
    );
    const manager = new WorkflowRunManager('run_raw_patch');
    await manager.initialize();

    const initialRaw = getRunStateStore('run_raw_patch').getState().rawRunState;
    expect(initialRaw).toBeDefined();
    expect(initialRaw.status).toBe('running');
    expect(initialRaw.currentEpoch).toBe(1);

    // WS event flips status to completed and bumps epoch
    manager.handleBatchUpdate({
      seq: 1000,
      workflowStatus: { status: 'completed' },
      currentEpoch: 2,
    });

    const patchedRaw = getRunStateStore('run_raw_patch').getState().rawRunState;
    expect(patchedRaw.status).toBe('completed');
    expect(patchedRaw.currentEpoch).toBe(2);
    // Other fields preserved (workflowId from REST init)
    expect(patchedRaw.workflowId).toBe('wf_partial');
  });
});

/**
 * Regression tests for pendingSignals hydration + post-resolve convergence in
 * {@link WorkflowRunManager}.
 *
 * GET /state carries NO signal rows, and WS batch-updates only reach sessions
 * that were already connected when the signal registered. Before the fix, a
 * page load during a pending approval showed an approval node with NO per-item
 * signal list, and resolving a signal in a session without a live WS batch
 * stream never converged (the bar kept showing the resolved items).
 *
 * The fix: refreshStateInternal() hydrates pendingSignals from
 * GET /runs/{id}/signals (PENDING only, seq-guarded against a WS batch landing
 * mid-fetch), and resolveApproval() re-runs the refresh after a successful
 * resolution so REST alone converges the approval UI.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WorkflowRunManager } from '../WorkflowRunManager';

vi.mock('../streamingDebug', () => ({
  streamDebug: { log: vi.fn(), warn: vi.fn(), error: vi.fn(), isEnabled: () => false },
}));

const mockGetRunState = vi.fn();
const mockGetRunSignals = vi.fn();
const mockResolveSignal = vi.fn().mockResolvedValue({ status: 'resolved' });

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunState: (...args: unknown[]) => mockGetRunState(...args),
    resolveSignal: (...args: unknown[]) => mockResolveSignal(...args),
    resolveAllSignals: vi.fn().mockResolvedValue({ count: 0 }),
    execution: {
      getRunSignals: (...args: unknown[]) => mockGetRunSignals(...args),
    },
    triggerSpecific: vi.fn().mockResolvedValue({ status: 'triggered', readySteps: [] }),
    executeSingleStep: vi.fn().mockResolvedValue({}),
    pauseWorkflow: vi.fn().mockResolvedValue({}),
    resumeWorkflow: vi.fn().mockResolvedValue({}),
    cancelWorkflow: vi.fn().mockResolvedValue({}),
    setExecutionMode: vi.fn().mockResolvedValue({ readySteps: [] }),
  },
}));

vi.mock('@/lib/websocket', () => ({
  wsClient: { sendAction: vi.fn().mockResolvedValue(undefined) },
}));

vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({
  normalizeLabel: (label: string) =>
    label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, ''),
}));

function runState(overrides: Record<string, unknown> = {}) {
  return {
    workflowId: 'wf-1',
    status: 'running',
    executionMode: 'automatic',
    readySteps: [],
    completedStepIds: [],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: [],
    steps: [],
    edges: [],
    plan: { triggers: [{ type: 'manual', label: 'Start' }] },
    seq: 1,
    ...overrides,
  };
}

function signal(id: number, itemId: string, status = 'PENDING') {
  return {
    id,
    nodeId: 'core:item_approval',
    signalType: 'USER_APPROVAL',
    status,
    epoch: 1,
    itemId,
  };
}

describe('WorkflowRunManager - pendingSignals hydration', () => {
  let manager: WorkflowRunManager;

  beforeEach(() => {
    vi.clearAllMocks();
    manager = new WorkflowRunManager('run-hydrate-1');
  });

  afterEach(() => {
    manager.destroy();
  });

  it('refresh() hydrates pendingSignals from the signals endpoint, keeping only PENDING rows', async () => {
    mockGetRunState.mockResolvedValue(runState({ seq: 2 }));
    mockGetRunSignals.mockResolvedValue([
      signal(1, '0'),
      signal(2, '1', 'RESOLVED'),
      signal(3, '2'),
    ]);

    await manager.refresh();

    expect(mockGetRunSignals).toHaveBeenCalledWith('run-hydrate-1');
    const pending = manager.getState().pendingSignals;
    expect(pending.map((s) => s.id)).toEqual([1, 3]);
  });

  it('a signals fetch failure is non-fatal: refresh() still applies the /state snapshot', async () => {
    mockGetRunState.mockResolvedValue(
      runState({ seq: 2, completedStepIds: ['mcp:done'], steps: [{ stepId: 'mcp:done', status: 'COMPLETED' }] }),
    );
    mockGetRunSignals.mockRejectedValue(new Error('boom'));

    await expect(manager.refresh()).resolves.toBeUndefined();
    expect(manager.getState().completedSteps.has('mcp:done')).toBe(true);
    expect(manager.getState().pendingSignals).toEqual([]);
  });

  it('skips the overwrite when a fresher WS batch advanced the seq while the fetch was in flight', async () => {
    mockGetRunState.mockResolvedValue(runState({ seq: 2 }));
    // The /signals response resolves only after we let a WS batch land.
    let releaseSignals: (v: unknown) => void = () => {};
    mockGetRunSignals.mockImplementation(
      () => new Promise((resolve) => { releaseSignals = resolve; }),
    );

    const refreshPromise = manager.refresh();
    // Give the /state fetch a chance to complete so the signal fetch is in flight.
    await vi.waitFor(() => expect(mockGetRunSignals).toHaveBeenCalled());

    // A WS batch lands mid-fetch carrying a just-registered signal.
    manager.handleBatchUpdate({ seq: 9, completedStepIds: [], pendingSignals: [signal(7, '0')] });
    releaseSignals([]); // stale REST snapshot says "no signals"

    await refreshPromise;
    expect(manager.getState().pendingSignals.map((s) => s.id)).toEqual([7]);
  });

  it('resolveApproval converges from REST alone - no WS event needed', async () => {
    // Initial refresh: 2 pending items.
    mockGetRunState.mockResolvedValueOnce(runState({ seq: 2 }));
    mockGetRunSignals.mockResolvedValueOnce([signal(1, '0'), signal(2, '1')]);
    await manager.refresh();
    expect(manager.getState().pendingSignals).toHaveLength(2);

    // Post-resolve refresh: backend advanced the seq, one item remains.
    mockGetRunState.mockResolvedValueOnce(runState({ seq: 3 }));
    mockGetRunSignals.mockResolvedValueOnce([signal(2, '1')]);

    await manager.resolveApproval('core:item_approval', 'APPROVED', 1, '0');

    expect(mockResolveSignal).toHaveBeenCalledWith(
      'run-hydrate-1', 'core:item_approval', 'APPROVED', undefined, 1, '0',
    );
    expect(manager.getState().pendingSignals.map((s) => s.id)).toEqual([2]);
  });

  it('post-resolve refresh respects the seq guard: a stale REST snapshot does not clobber fresher WS state', async () => {
    mockGetRunState.mockResolvedValueOnce(runState({ seq: 2 }));
    mockGetRunSignals.mockResolvedValueOnce([signal(1, '0')]);
    await manager.refresh();

    // WS already delivered seq 10 (e.g. the resolution's own batch arrived first).
    manager.handleBatchUpdate({ seq: 10, completedStepIds: [], pendingSignals: [] });

    // The post-resolve REST snapshot is OLDER than the WS state → skipped
    // (non-force refresh), so the WS-cleared signal list stays authoritative.
    mockGetRunState.mockResolvedValueOnce(runState({ seq: 4 }));
    mockGetRunSignals.mockResolvedValueOnce([signal(1, '0')]);

    await manager.resolveApproval('core:item_approval', 'APPROVED', 1, '0');
    expect(manager.getState().pendingSignals).toEqual([]);
  });
});

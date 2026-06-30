/**
 * Regression: backend wire-types alignment.
 *
 * `WorkflowEventPublisher.EVENT_TYPE_WIRE_NAMES` (Java) emits 9 camelCase
 * event types (stepStatus, edgeStatus, workflowStatus, workflowStatistics,
 * loopEvent, retryEvent, debugLog, mergeEvent, agentToolCall) plus named
 * events (workflowConfiguration, …). Before 2026-05-05, the FE
 * `INDIVIDUAL_EVENT_TYPES` only enumerated 7 of those - the rest fell
 * through to `handleBatchUpdate`, where:
 *   1. `lastKnownSeq` was bumped from the sub-event's seq;
 *   2. `hasSnapshotData=false` (the payload has no `.steps/.edges`), so
 *      `processBatchUpdate` was skipped - no UI repaint;
 *   3. the next legitimate snapshot/REST refresh with seq <= bumped value
 *      was strict-`<` dropped → run-page UI froze on partial state.
 *
 * The fix (this commit):
 *   - All 9 camelCase types + workflowConfiguration are in
 *     `INDIVIDUAL_EVENT_TYPES` so they route to `handleEvent` (no-op).
 *   - `handleBatchUpdate` defers the `lastKnownSeq` bump until *after* a
 *     real snapshot is applied - so even a future mis-routed sub-event
 *     can't poison the high-water mark.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WorkflowRunManager } from '../WorkflowRunManager';

vi.mock('../streamingDebug', () => ({
  streamDebug: { log: vi.fn(), warn: vi.fn(), error: vi.fn(), isEnabled: () => false },
}));

const mockGetRunState = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunState: (...args: any[]) => mockGetRunState(...args),
    triggerSpecific: vi.fn().mockResolvedValue({ status: 'triggered', readySteps: [] }),
    getLatestWorkflowRun: vi.fn(),
    getAllRunSteps: vi.fn(),
    rerunFromStep: vi.fn(),
    getStatusCounts: vi.fn(),
    executeSingleStep: vi.fn().mockResolvedValue({}),
    pauseWorkflow: vi.fn().mockResolvedValue({}),
    resumeWorkflow: vi.fn().mockResolvedValue({}),
    cancelWorkflow: vi.fn().mockResolvedValue({}),
    setExecutionMode: vi.fn().mockResolvedValue({ readySteps: [] }),
    resolveSignal: vi.fn().mockResolvedValue({ status: 'resolved' }),
  },
}));

vi.mock('@/lib/api/error-utils', () => ({
  is402Error: () => false,
  is413StorageError: () => false,
}));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ showInsufficientCreditsModal: vi.fn() }));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({ showInsufficientStorageModal: vi.fn() }));
vi.mock('@/lib/websocket', () => ({ wsClient: { sendAction: vi.fn().mockResolvedValue(undefined) } }));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({
  normalizeLabel: (label: string) =>
    label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, ''),
}));

function baseState() {
  return {
    workflowId: 'wf-1',
    status: 'running',
    executionMode: 'automatic',
    triggerType: 'manual',
    readySteps: [],
    completedStepIds: [],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: [],
    steps: [],
    edges: [],
    plan: { triggers: [], mcps: [], cores: [], edges: [] },
    seq: 0,
  };
}

describe('WorkflowRunManager - sub-event routing & lastKnownSeq guard', () => {
  let manager: WorkflowRunManager;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    manager = new WorkflowRunManager('run-sub-event');
    manager.setCurrentPlanGetter(() => ({ triggers: [], mcps: [], cores: [], edges: [] }) as any);
    mockGetRunState.mockResolvedValue(baseState());
  });

  afterEach(() => {
    manager.destroy();
    vi.useRealTimers();
  });

  it('handleEvent("stepStatus") is a no-op that does NOT bump lastKnownSeq', async () => {
    await manager.initialize();
    const before = (manager as any).lastKnownSeq;

    manager.handleEvent('stepStatus', {
      seq: 999,
      runId: 'run-sub-event',
      normalizedStepId: 'mcp:step_a',
      lifecycle: 'RUNNING',
    });

    expect((manager as any).lastKnownSeq).toBe(before);
  });

  it('handleEvent("edgeStatus") is a no-op that does NOT bump lastKnownSeq', async () => {
    await manager.initialize();
    const before = (manager as any).lastKnownSeq;

    manager.handleEvent('edgeStatus', { seq: 1234, runId: 'run-sub-event', edgeId: 'a->b', lifecycle: 'COMPLETED' });

    expect((manager as any).lastKnownSeq).toBe(before);
  });

  it.each([
    'workflowStatistics',
    'loopEvent',
    'retryEvent',
    'debugLog',
    'mergeEvent',
    'agentToolCall',
    'workflowConfiguration',
  ])('handleEvent(%s) is a no-op that does NOT bump lastKnownSeq', async (eventType) => {
    await manager.initialize();
    const before = (manager as any).lastKnownSeq;

    manager.handleEvent(eventType, { seq: 500, runId: 'run-sub-event' });

    expect((manager as any).lastKnownSeq).toBe(before);
  });

  it('handleBatchUpdate does NOT bump lastKnownSeq for non-snapshot payloads (defensive)', async () => {
    await manager.initialize();
    const before = (manager as any).lastKnownSeq;

    // Non-snapshot payload: just a seq + workflowStatus, no .steps/.edges/etc.
    manager.handleBatchUpdate({ seq: 50, workflowStatus: { status: 'running' } });

    // Pre-fix this would set lastKnownSeq=50, blocking future snapshots with seq<50.
    expect((manager as any).lastKnownSeq).toBe(before);
  });

  it('handleBatchUpdate DOES bump lastKnownSeq when a real snapshot is applied', async () => {
    await manager.initialize();

    manager.handleBatchUpdate({
      seq: 42,
      steps: [{ id: 'mcp:step_a', status: 'completed' }],
      edges: [],
    });

    expect((manager as any).lastKnownSeq).toBe(42);
  });

  it('a non-snapshot batch-update followed by a lower-seq snapshot still applies the snapshot', async () => {
    await manager.initialize();

    // Pre-fix: this would bump lastKnownSeq=100 even though hasSnapshotData=false.
    manager.handleBatchUpdate({ seq: 100 });

    // Then a legitimate snapshot arrives with seq=50 (e.g. a slow REST refresh).
    const store = (manager as any).store;
    const spy = vi.spyOn(store, 'updateVisualization');
    manager.handleBatchUpdate({
      seq: 50,
      steps: [{ id: 'mcp:step_b', status: 'completed' }],
      edges: [],
    });

    expect(spy).toHaveBeenCalledTimes(1);
    const visualizationUpdate = spy.mock.calls[0][0] as { steps?: unknown[] };
    expect(visualizationUpdate.steps).toEqual([{ id: 'mcp:step_b', status: 'completed' }]);
  });
});

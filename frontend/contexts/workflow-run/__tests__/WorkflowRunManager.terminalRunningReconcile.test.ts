// @vitest-environment jsdom
/**
 * Regression: a terminal run must never leave a node painted "running"/"Thinking..." forever.
 *
 * Bug: when an agent node's terminal status event was dropped (the conversation/run
 * panel subscribed mid-run, or a batch-update/snapshot was lost), the node stayed in
 * `runningSteps` indefinitely and kept shimmering "running" even though the workflow
 * run had already reached `completed`. There was no view-layer reconciliation on a
 * terminal status transition - the running set only shrank when an explicit
 * non-running status arrived for that node, which never came.
 *
 * Fix: `handleWorkflowStatusTransition` clears the running set the moment the run
 * reaches a terminal status (a finished run has nothing running by definition). The
 * backend now also reports zero running for terminal runs (SnapshotService terminal
 * short-circuit + RunningNodeTracker overlay purge on cleanup); this front-end guard
 * makes the heal instant and event-loss-proof.
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
  isCreditExhaustedFailure: () => false,
}));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ showInsufficientCreditsModal: vi.fn() }));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({ showInsufficientStorageModal: vi.fn() }));
vi.mock('@/lib/websocket', () => ({ wsClient: { onReconnected: () => () => {}, sendAction: vi.fn().mockResolvedValue(undefined) } }));
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

describe('WorkflowRunManager - terminal-run running reconciliation', () => {
  let manager: WorkflowRunManager;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    manager = new WorkflowRunManager('run-terminal');
    manager.setCurrentPlanGetter(() => ({ triggers: [], mcps: [], cores: [], edges: [] }) as any);
    mockGetRunState.mockResolvedValue(baseState());
  });

  afterEach(() => {
    manager.destroy();
    vi.useRealTimers();
  });

  it('clears the running set when the run reaches a terminal status (completed)', async () => {
    await manager.initialize();
    const store = (manager as any).store;
    const spy = vi.spyOn(store, 'setRunningSteps');

    (manager as any).handleWorkflowStatusTransition('completed');

    // A finished run has nothing running: the running set is cleared so a node
    // whose terminal event was dropped never stays "running"/Thinking forever.
    expect(spy).toHaveBeenCalledWith([]);
  });

  it('actually empties a long-running node from the store on completion', async () => {
    await manager.initialize();
    const store = (manager as any).store;

    // Node has been "running" well past the min-shimmer window (the eternal case).
    store.setRunningSteps(['agent:writer']);
    expect(store.getState().runningSteps.has('agent:writer')).toBe(true);
    vi.advanceTimersByTime(700); // > MIN_SHIMMER_MS (600), so the clear is immediate

    (manager as any).handleWorkflowStatusTransition('completed');

    expect(store.getState().runningSteps.size).toBe(0);
  });

  it('does NOT clear the running set on a non-terminal (running) status', async () => {
    await manager.initialize();
    const store = (manager as any).store;
    const spy = vi.spyOn(store, 'setRunningSteps');

    (manager as any).handleWorkflowStatusTransition('running');

    expect(spy).not.toHaveBeenCalledWith([]);
  });

  it('clears the running set on partial_success (also a terminal status)', async () => {
    // partial_success is terminal (run finished with some failed steps). It must
    // be in TERMINAL_STATUSES so the running set is reconciled - otherwise a node
    // could stay painted running/Thinking on a partial_success run.
    await manager.initialize();
    const store = (manager as any).store;
    const spy = vi.spyOn(store, 'setRunningSteps');

    (manager as any).handleWorkflowStatusTransition('partial_success');

    expect(spy).toHaveBeenCalledWith([]);
  });

  it('does NOT clear the running set on a waiting_trigger transition (multi-DAG next epoch)', async () => {
    // waiting_trigger means one DAG finished but another epoch is ready - nodes may
    // legitimately still be running, so the running set must NOT be wiped here.
    await manager.initialize();
    const store = (manager as any).store;
    const spy = vi.spyOn(store, 'setRunningSteps');

    (manager as any).handleWorkflowStatusTransition('waiting_trigger');

    expect(spy).not.toHaveBeenCalledWith([]);
  });
});

/**
 * The waiting_trigger transition must pull the closed cycle's VERDICT.
 *
 * The run badge reads `lastCycleResult` off rawRunState, and only a full REST hydrate writes it -
 * no WS payload carries it. Without a refresh here the canvas kept showing the PREVIOUS cycle's
 * outcome for a whole live session while the run-history row showed the current one: the very
 * contradiction between two badges on one run that this work exists to remove.
 *
 * The guard matters as much as the refresh: this handler runs on every batch carrying a status,
 * and with parallel DAGs an unguarded refresh fired once per DAG for a single cycle.
 */
describe('WorkflowRunManager - closed-cycle verdict refresh', () => {
  let manager: WorkflowRunManager;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    mockGetRunState.mockResolvedValue(baseState());
  });

  afterEach(() => {
    manager?.destroy();
    vi.useRealTimers();
  });

  it('refreshes when a cycle closes, because the badge has no other source for the verdict', async () => {
    manager = new WorkflowRunManager('run-verdict');
    manager.setCurrentPlanGetter(() => ({ triggers: [], mcps: [], cores: [], edges: [] }) as any);
    await manager.initialize();
    mockGetRunState.mockClear();

    (manager as any).handleWorkflowStatusTransition('waiting_trigger');
    await vi.advanceTimersByTimeAsync(600);

    expect(mockGetRunState).toHaveBeenCalled();
  });

  it('refreshes ONCE per cycle, not once per parallel DAG', async () => {
    manager = new WorkflowRunManager('run-verdict-fanout');
    manager.setCurrentPlanGetter(() => ({ triggers: [], mcps: [], cores: [], edges: [] }) as any);
    await manager.initialize();
    mockGetRunState.mockClear();

    // Same epoch, several DAGs reaching waiting_trigger: the 12x fanout the refresh was
    // originally removed for.
    (manager as any).handleWorkflowStatusTransition('waiting_trigger');
    (manager as any).handleWorkflowStatusTransition('waiting_trigger');
    (manager as any).handleWorkflowStatusTransition('waiting_trigger');
    await vi.advanceTimersByTimeAsync(600);

    expect(mockGetRunState.mock.calls.length, 'one cycle closing is one refresh').toBe(1);
  });
});

/**
 * The hydrate must seed the failure baseline, using the field name the REST payload actually uses.
 *
 * The live failure notification fires when a node's failure tally GOES UP. That needs a baseline,
 * and the hydrate is where it comes from after a page load. The seeding block read `step.id`,
 * while `/runs/{id}/state` names the field `stepId` (WorkflowRunState.StepState) - so it matched
 * nothing, recorded no baseline, and the first live update treated an already-known node as newly
 * seen and stayed silent.
 */
describe('WorkflowRunManager - hydrate seeds the failure baseline', () => {
  let manager: WorkflowRunManager;
  const dispatched: string[] = [];

  const hydratedWith = (failed: number) => ({
    ...baseState(),
    // The REST shape: `stepId`, not `id`.
    steps: [{ stepId: 'agent:writer', status: 'PARTIAL_SUCCESS', statusCounts: { completed: 1, failed } }],
  });

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    dispatched.length = 0;
    vi.spyOn(window, 'dispatchEvent').mockImplementation((e: Event) => {
      if (e.type === 'workflowStepFailed') dispatched.push((e as CustomEvent).detail?.stepId);
      return true;
    });
  });

  afterEach(() => {
    manager?.destroy();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('notifies on the first live failure after a page load', async () => {
    mockGetRunState.mockResolvedValue(hydratedWith(1));
    manager = new WorkflowRunManager('run-hydrate-baseline');
    manager.setCurrentPlanGetter(() => ({ triggers: [], mcps: [], cores: [], edges: [] }) as any);
    await manager.initialize();
    dispatched.length = 0;

    // The node fails again: the tally goes 1 -> 2. Without the seeded baseline this reads as a
    // first sighting and stays silent.
    (manager as any).processBatchUpdate({
      steps: [{ id: 'agent:writer', label: 'Writer', status: 'partial_success', statusCounts: { completed: 1, failed: 2 } }],
    });

    expect(dispatched, 'the baseline seeded from the hydrate is what makes this observable')
      .toEqual(['agent:writer']);
  });

  it('stays silent when the tally has not moved since the hydrate', async () => {
    // The counterexample: the failure was already there when the page opened. Re-announcing it
    // would toast history on every load.
    mockGetRunState.mockResolvedValue(hydratedWith(1));
    manager = new WorkflowRunManager('run-hydrate-baseline-flat');
    manager.setCurrentPlanGetter(() => ({ triggers: [], mcps: [], cores: [], edges: [] }) as any);
    await manager.initialize();
    dispatched.length = 0;

    (manager as any).processBatchUpdate({
      steps: [{ id: 'agent:writer', label: 'Writer', status: 'partial_success', statusCounts: { completed: 1, failed: 1 } }],
    });

    expect(dispatched).toEqual([]);
  });
});

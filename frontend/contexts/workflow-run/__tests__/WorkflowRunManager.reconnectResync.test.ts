/**
 * Regression: a run whose epoch closed (or which finished) while the tab's WebSocket was down
 * stayed painted "running" until a manual reload.
 *
 * Production shape: an application launched a workflow, the run executed and its epoch closed
 * (run row back to WAITING_TRIGGER), but the tab had no live subscription while it happened, so
 * the closing snapshot reached nobody. The gateway keeps no backlog and the manager never polls:
 * nothing re-read the run after the socket came back.
 *
 * Fix: every manager listens to `wsClient.onReconnected` and re-reads its run through the normal
 * refresh path when something renders it.
 *
 * Second shape, same symptom: managers live for the whole session and initialize() is a no-op on
 * one that already holds a state, so a card unmounted while its run was live and shown again after
 * the run finished kept showing "running" (the channel snapshot is skipped when unchanged and never
 * sent for a finished run). The first subscriber of an already-loaded run re-reads it, whatever
 * its status (same rule as the reconnect resync, pinned below). A resync read that fails is retried
 * once, 3 s later, while the run is still shown.
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
    execution: { getRunSignals: vi.fn().mockResolvedValue([]) },
  },
}));

vi.mock('@/lib/api/error-utils', () => ({
  is402Error: () => false,
  is413StorageError: () => false,
  isCreditExhaustedFailure: () => false,
}));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ showInsufficientCreditsModal: vi.fn() }));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({ showInsufficientStorageModal: vi.fn() }));

// The real contract of wsClient.onReconnected: register, get a disposer back.
const reconnectListeners = new Set<() => void>();
vi.mock('@/lib/websocket', () => ({
  wsClient: {
    sendAction: vi.fn().mockResolvedValue(undefined),
    onReconnected: (listener: () => void) => {
      reconnectListeners.add(listener);
      return () => { reconnectListeners.delete(listener); };
    },
  },
}));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({
  normalizeLabel: (label: string) =>
    label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, ''),
}));

function fireReconnected(): void {
  for (const listener of Array.from(reconnectListeners)) listener();
}

async function flush(): Promise<void> {
  for (let i = 0; i < 10; i++) await Promise.resolve();
}

function runState(overrides: Record<string, any> = {}) {
  return {
    workflowId: 'wf-1',
    status: 'running',
    executionMode: 'automatic',
    readySteps: [],
    completedStepIds: [],
    failedStepIds: [],
    skippedStepIds: [],
    runningStepIds: ['mcp:fetch'],
    steps: [{ stepId: 'mcp:fetch', status: 'RUNNING' }],
    edges: [],
    plan: { triggers: [{ type: 'manual', label: 'Start' }], mcps: [], cores: [], edges: [] },
    seq: 10,
    ...overrides,
  };
}

describe('WorkflowRunManager - re-reads the run after a WebSocket reconnect', () => {
  let manager: WorkflowRunManager;
  let unsubscribe: () => void;

  beforeEach(async () => {
    vi.clearAllMocks();
    mockGetRunState.mockReset(); // drop any queued once-implementation a failed test left behind
    reconnectListeners.clear();
    manager = new WorkflowRunManager('run-app');
    mockGetRunState.mockResolvedValue(runState());
    // A surface renders this run. Subscribed while the first load is still pending, like useRun:
    // nothing is loaded yet, so this first subscriber does not trigger a re-read of its own.
    unsubscribe = manager.subscribe(() => {});
    await manager.initialize();
    expect(manager.getState().runStatus).toBe('running');
    expect(mockGetRunState).toHaveBeenCalledTimes(1);
  });

  afterEach(() => {
    unsubscribe?.();
    manager.destroy();
  });

  it('recovers an epoch that closed while the socket was down (running -> waiting_trigger)', async () => {
    // The closing snapshot was published to nobody. The run row is back to WAITING_TRIGGER.
    mockGetRunState.mockResolvedValue(runState({
      status: 'waiting_trigger',
      runningStepIds: [],
      completedStepIds: ['mcp:fetch'],
      steps: [{ stepId: 'mcp:fetch', status: 'COMPLETED' }],
      seq: 14,
    }));

    fireReconnected();
    await flush();

    expect(mockGetRunState).toHaveBeenCalledTimes(2);
    expect(manager.getState().runStatus).toBe('waiting_trigger');
    expect(manager.getState().runningSteps.has('mcp:fetch')).toBe(false);
  });

  it('recovers a terminal status missed while the socket was down (running -> completed)', async () => {
    mockGetRunState.mockResolvedValue(runState({
      status: 'completed',
      runningStepIds: [],
      completedStepIds: ['mcp:fetch'],
      steps: [{ stepId: 'mcp:fetch', status: 'COMPLETED' }],
      seq: 14,
    }));

    fireReconnected();
    await flush();

    expect(manager.getState().runStatus).toBe('completed');
  });

  it('coalesces reconnects: one read in flight plus one follow-up, never a stack of reads', async () => {
    let release!: (value: unknown) => void;
    mockGetRunState.mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
    mockGetRunState.mockResolvedValue(runState({ status: 'waiting_trigger', runningStepIds: [], seq: 16 }));

    fireReconnected();
    fireReconnected();
    fireReconnected();
    await flush();
    // initialize + the one read in flight; the two later reconnects are folded into one follow-up.
    expect(mockGetRunState).toHaveBeenCalledTimes(2);

    release(runState({ status: 'running', seq: 12 }));
    await flush();

    // The follow-up covers whatever the second drop hid from the first read.
    expect(mockGetRunState).toHaveBeenCalledTimes(3);
    expect(manager.getState().runStatus).toBe('waiting_trigger');
  });

  it('does not re-read a run that nothing renders (a manager merely kept in the registry)', async () => {
    unsubscribe();

    fireReconnected();
    await flush();

    expect(mockGetRunState).toHaveBeenCalledTimes(1);
  });

  it('re-reads a run shown again after it finished server-side, without any WS event', async () => {
    unsubscribe(); // the surface goes away while the run is live
    mockGetRunState.mockResolvedValue(runState({
      status: 'completed',
      runningStepIds: [],
      completedStepIds: ['mcp:fetch'],
      steps: [{ stepId: 'mcp:fetch', status: 'COMPLETED' }],
      seq: 14,
    }));

    unsubscribe = manager.subscribe(() => {}); // shown again: initialize() would be a no-op
    await flush();

    expect(mockGetRunState).toHaveBeenCalledTimes(2);
    expect(manager.getState().runStatus).toBe('completed');
  });

  it('does not re-read when another surface joins a run already shown', async () => {
    const second = manager.subscribe(() => {});
    await flush();

    expect(mockGetRunState).toHaveBeenCalledTimes(1);
    second();
  });

  describe('one rule for both resync paths: a finished run is re-read too', () => {
    // A finished run can be reactivated or re-run from a step in another tab, and a terminal run
    // gets no channel snapshot to say so: a tab that was not listening cannot know it is final.
    // Reactivated elsewhere: the run rests armed again, waiting for its trigger.
    const restarted = () => runState({
      status: 'waiting_trigger', runningStepIds: [], readySteps: ['trigger:start'], seq: 20,
    });

    async function finishRun(): Promise<void> {
      mockGetRunState.mockResolvedValue(runState({ status: 'completed', runningStepIds: [], seq: 14 }));
      await manager.refresh();
      expect(manager.getState().runStatus).toBe('completed');
      mockGetRunState.mockResolvedValue(restarted());
    }

    it('on reconnect', async () => {
      await finishRun();
      const calls = mockGetRunState.mock.calls.length;

      fireReconnected();
      await flush();

      expect(mockGetRunState).toHaveBeenCalledTimes(calls + 1);
      expect(manager.getState().runStatus).toBe('waiting_trigger');
    });

    it('when the run is shown again', async () => {
      await finishRun();
      unsubscribe();
      const calls = mockGetRunState.mock.calls.length;

      unsubscribe = manager.subscribe(() => {});
      await flush();

      expect(mockGetRunState).toHaveBeenCalledTimes(calls + 1);
      expect(manager.getState().runStatus).toBe('waiting_trigger');
    });
  });

  it('a watcher (the run history) is not a surface showing the run: it neither reads nor swallows the re-read owed to one', async () => {
    unsubscribe(); // nothing shows the run any more
    const seen: string[] = [];
    const unwatch = manager.watch(state => seen.push(state.runStatus));
    await flush();
    expect(seen).toEqual([]); // no call on registration
    expect(mockGetRunState).toHaveBeenCalledTimes(1); // and no read of its own

    fireReconnected(); // not a render subscriber: no reconnect read for it either
    await flush();
    expect(mockGetRunState).toHaveBeenCalledTimes(1);

    mockGetRunState.mockResolvedValue(runState({ status: 'completed', runningStepIds: [], seq: 14 }));
    unsubscribe = manager.subscribe(() => {}); // a surface shows the run: still the first one
    await flush();

    expect(mockGetRunState).toHaveBeenCalledTimes(2);
    expect(manager.getState().runStatus).toBe('completed');
    expect(seen).toContain('completed'); // the watcher follows the change
    unwatch();
  });

  it('a mount/unmount/mount burst shares one re-read and queues no follow-up', async () => {
    unsubscribe();
    let release!: (value: unknown) => void;
    mockGetRunState.mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));

    unsubscribe = manager.subscribe(() => {});
    unsubscribe();
    unsubscribe = manager.subscribe(() => {});
    await flush();
    expect(mockGetRunState).toHaveBeenCalledTimes(2); // initialize + one resync read

    release(runState({ seq: 12 }));
    await flush();
    expect(mockGetRunState).toHaveBeenCalledTimes(2); // a re-shown run queues no follow-up
  });

  describe('a failed resync read is retried once, 3 s later, while the run is still shown', () => {
    const completed = () => runState({
      status: 'completed', runningStepIds: [], completedStepIds: ['mcp:fetch'],
      steps: [{ stepId: 'mcp:fetch', status: 'COMPLETED' }], seq: 14,
    });

    afterEach(() => { vi.useRealTimers(); });

    it('recovers when the retry succeeds', async () => {
      vi.useFakeTimers();
      mockGetRunState.mockRejectedValueOnce(new Error('502 during a rolling deploy'));
      mockGetRunState.mockResolvedValue(completed());

      fireReconnected();
      await flush();
      expect(mockGetRunState).toHaveBeenCalledTimes(2);
      expect(manager.getState().runStatus).toBe('running');

      await vi.advanceTimersByTimeAsync(2999);
      expect(mockGetRunState).toHaveBeenCalledTimes(2);
      await vi.advanceTimersByTimeAsync(1);
      await flush();

      expect(mockGetRunState).toHaveBeenCalledTimes(3);
      expect(manager.getState().runStatus).toBe('completed');
    });

    it('retries only once (a failing retry does not loop)', async () => {
      vi.useFakeTimers();
      mockGetRunState.mockRejectedValue(new Error('503'));

      fireReconnected();
      await flush();
      await vi.advanceTimersByTimeAsync(3000);
      await flush();
      await vi.advanceTimersByTimeAsync(30_000);
      await flush();

      expect(mockGetRunState).toHaveBeenCalledTimes(3); // initialize + the read + one retry
    });

    it('does not retry for a run nobody shows any more', async () => {
      vi.useFakeTimers();
      mockGetRunState.mockRejectedValueOnce(new Error('502'));

      fireReconnected();
      await flush();
      unsubscribe();
      await vi.advanceTimersByTimeAsync(3000);
      await flush();

      expect(mockGetRunState).toHaveBeenCalledTimes(2);
    });
  });

  it('stops listening once destroyed', () => {
    expect(reconnectListeners.size).toBe(1);
    manager.destroy();
    expect(reconnectListeners.size).toBe(0);
  });
});

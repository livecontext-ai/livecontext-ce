// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WorkflowRunManager, interfaceStatusClearsPending, stepFailedNow } from '../WorkflowRunManager';
import { usePendingInterfacesStore } from '@/lib/stores/pending-interfaces-store';

/**
 * A partial node must land in the COMPLETED tracking set built from a WS snapshot.
 *
 * The two halves of a partial node are read from different places and pull in opposite
 * directions, which is what made this easy to get half-right:
 *  - the amber border comes from `data.status`, which is the backend verdict verbatim;
 *  - the rerun button comes from these tracking sets, because `canRerunStep` tests
 *    `completedSteps || failedSteps`.
 *
 * So the status has to say "partial_success" AND the node has to be bucketed as completed. Until
 * the backend started emitting `partial_success` over the socket, this switch never saw the value;
 * once it did, an unhandled case would have silently dropped the node from BOTH sets and taken
 * away the rerun button on the live canvas - the exact symptom that opened this work.
 */
vi.mock('../streamingDebug', () => ({
  streamDebug: { log: vi.fn(), warn: vi.fn(), error: vi.fn(), isEnabled: () => false },
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunState: vi.fn(),
    getStatusCounts: vi.fn(),
    rerunFromStep: vi.fn(),
    triggerSpecific: vi.fn(),
    executeSingleStep: vi.fn(),
    pauseWorkflow: vi.fn(),
    resumeWorkflow: vi.fn(),
    cancelWorkflow: vi.fn(),
    setExecutionMode: vi.fn(),
    resolveSignal: vi.fn(),
  },
}));

vi.mock('@/lib/websocket', () => ({
  wsClient: { onReconnected: () => () => {}, sendAction: vi.fn().mockResolvedValue(undefined) },
}));

vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({
  normalizeLabel: (label: string) =>
    label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, ''),
}));

function derive(steps: Array<{ id: string; status: string }>) {
  const manager = new WorkflowRunManager('run-partial-tracking');
  // Private on purpose: it is an internal projection of the WS payload, and the behaviour under
  // test is which bucket a status lands in.
  return (manager as unknown as {
    deriveTrackingSetsFromSteps: (s: unknown[]) => {
      completed: string[]; failed: string[]; skipped: string[]; running: string[];
    };
  }).deriveTrackingSetsFromSteps(steps);
}

describe('WorkflowRunManager - tracking sets from a WS snapshot', () => {
  it('buckets a partial_success node as completed, so it keeps its rerun button', () => {
    const sets = derive([{ id: 'core:boom', status: 'partial_success' }]);
    expect(sets.completed, 'canRerunStep reads completedSteps - dropping it removes the button')
      .toContain('core:boom');
    expect(sets.failed).not.toContain('core:boom');
    expect(sets.skipped).not.toContain('core:boom');
    expect(sets.running).not.toContain('core:boom');
  });

  it('still separates a plainly failed node from a completed one', () => {
    const sets = derive([
      { id: 'core:ok', status: 'completed' },
      { id: 'core:bad', status: 'failed' },
    ]);
    expect(sets.completed).toEqual(['core:ok']);
    expect(sets.failed).toEqual(['core:bad']);
  });

  it('leaves a pending node out of every terminal set', () => {
    // NodeCounts accumulate across epochs, so promoting "pending" would resurrect a stale
    // verdict after a new trigger fire.
    const sets = derive([{ id: 'core:next', status: 'pending' }]);
    expect(sets.completed).toHaveLength(0);
    expect(sets.failed).toHaveLength(0);
    expect(sets.skipped).toHaveLength(0);
    expect(sets.running).toHaveLength(0);
  });
});

/**
 * The sibling switches that read the same status, files apart, and were left behind.
 *
 * They listed the statuses they handled instead of the ones they did not, so the moment the
 * backend started emitting `partial_success` over the socket they matched nothing and failed
 * OPEN - silently, with no error and no visible change until much later.
 */
describe('interfaceStatusClearsPending - the rule that strands the application panel', () => {
  it('clears a partial node, which used to fall through and stay awaiting forever', () => {
    // An interface node that failed once and was re-run successfully reports partial_success on
    // every later update (counts never reset), so falling through pinned it in the panel.
    expect(interfaceStatusClearsPending('partial_success')).toBe(true);
  });

  it('clears every ordinary terminal status', () => {
    for (const status of ['completed', 'success', 'failed', 'error', 'skipped']) {
      expect(interfaceStatusClearsPending(status), `${status} must clear the entry`).toBe(true);
    }
  });

  it('keeps the entry while the node is still going or awaiting the user', () => {
    // The counterexample that makes a complement-shaped rule safe: "not finished" must still mean
    // "stays pending", or the panel would drop a screen nobody has answered.
    expect(interfaceStatusClearsPending('awaiting_signal')).toBe(false);
    expect(interfaceStatusClearsPending('running')).toBe(false);
    expect(interfaceStatusClearsPending('pending')).toBe(false);
  });
});
/**
 * The failure notification, where this class of bug stopped being cosmetic.
 *
 * A node that has ever succeeded reports the ACCUMULATED `partial_success` even on the pass that
 * just failed, so a plain status check silently stopped the failure toast - and it disappeared for
 * exactly the long-running scheduled workflows where it matters.
 *
 * The discriminator is the tally GOING UP. A first attempt keyed off an error message instead,
 * which cannot work: the streaming payload carries status and statusCounts and nothing else, so
 * that condition was structurally always false. Its test passed only because it hand-built a
 * payload the producer cannot emit - green for a reason unrelated to the behaviour it claimed to
 * protect. These cases use the real payload shape.
 */
describe('stepFailedNow - did THIS pass fail', () => {
  it('fires when the failure tally goes up on a partial node', () => {
    // completed=1 then it fails: the socket reports partial_success with failed 0 -> 1.
    expect(stepFailedNow('partial_success', 1, 0)).toBe(true);
    expect(stepFailedNow('partial_success', 3, 2)).toBe(true);
  });

  it('stays silent when the tally is unchanged - the failure is OLD', () => {
    // The counterexample that makes this safe. Counts never reset, so a node fixed long ago keeps
    // reporting partial forever; firing on that would toast on every snapshot, every epoch.
    expect(stepFailedNow('partial_success', 1, 1)).toBe(false);
    expect(stepFailedNow('partial_success', 2, 2)).toBe(false);
  });

  it('stays silent on first sighting, so opening a page does not replay history', () => {
    expect(stepFailedNow('partial_success', 5, undefined)).toBe(false);
  });

  it('still fires for the plainly failed statuses, tally or not', () => {
    for (const status of ['failed', 'error', 'failure']) {
      expect(stepFailedNow(status, 0, 0), `${status} must notify`).toBe(true);
    }
  });

  it('never fires for a node that is fine or still going', () => {
    for (const status of ['completed', 'success', 'running', 'pending', 'skipped', 'awaiting_signal']) {
      expect(stepFailedNow(status, 1, 0), `${status} must not notify`).toBe(false);
    }
  });
});

/**
 * The CALL SITE, not the predicate.
 *
 * A review proved this exact gap twice: the extracted rule was tested while its caller was not, so
 * reverting the caller to the old status list reinstated the bug with every suite still green. The
 * payloads below are the real socket shape (status + statusCounts, no error text), driven through
 * the real batch handler.
 */
describe('processBatchUpdate - the failure notification reaches the caller', () => {
  const dispatched: string[] = [];
  let manager: WorkflowRunManager;

  const feedBatch = (failed: number, status = 'partial_success') =>
    (manager as unknown as { processBatchUpdate: (d: unknown) => void }).processBatchUpdate({
      steps: [{ id: 'agent:writer', label: 'Writer', status, statusCounts: { completed: 1, failed } }],
    });

  beforeEach(() => {
    dispatched.length = 0;
    manager = new WorkflowRunManager('run-failure-callsite');
    vi.spyOn(window, 'dispatchEvent').mockImplementation((e: Event) => {
      if (e.type === 'workflowStepFailed') dispatched.push((e as CustomEvent).detail?.stepId);
      return true;
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('notifies when a node that had succeeded fails again', () => {
    feedBatch(0);            // baseline: the node is clean
    feedBatch(1);            // it fails -> the socket reports partial_success, tally 0 -> 1
    expect(dispatched, 'the toast must fire for a node reported as partial').toEqual(['agent:writer']);
  });

  it('does not notify again while the tally stays put', () => {
    feedBatch(0);
    feedBatch(1);
    dispatched.length = 0;
    feedBatch(1);
    feedBatch(1);
    expect(dispatched, 'an old failure must stay quiet on every later snapshot').toEqual([]);
  });

  it('notifies for a plainly failed node', () => {
    feedBatch(1, 'failed');
    expect(dispatched).toEqual(['agent:writer']);
  });
});

/**
 * The pending-panel CALL SITE.
 *
 * This branch used to be unreachable from any test: the store arrived through a `require()` that
 * throws under ESM, inside a catch that swallows it - which is precisely why the panel-stranding
 * bug it guards went unnoticed. The require's stated reason ("avoid circular dependencies") did not
 * hold: the store imports nothing but zustand. It is a static import now, and the branch is
 * observable.
 */
describe('handleInterfaceStepUpdate - the pending panel call site', () => {
  const feed = (status: string) => {
    const m = new WorkflowRunManager('run-iface-callsite');
    (m as unknown as { handleInterfaceStepUpdate: (id: string, s: unknown) => void })
      .handleInterfaceStepUpdate('interface:screen', { status, label: 'Screen', output: {} });
  };

  const pending = () => usePendingInterfacesStore.getState().interfaces;

  beforeEach(() => {
    usePendingInterfacesStore.setState({ interfaces: new Map(), activeNodeId: null });
  });

  it('clears a partial interface node, instead of stranding it as awaiting forever', () => {
    feed('awaiting_signal');
    expect(pending().size).toBe(1);

    feed('partial_success');
    expect(pending().size, 'a node that finished must leave the application panel').toBe(0);
  });

  it('clears on every ordinary terminal status', () => {
    for (const status of ['completed', 'success', 'failed', 'error', 'skipped']) {
      feed('awaiting_signal');
      expect(pending().size).toBe(1);
      feed(status);
      expect(pending().size, `${status} must clear the entry`).toBe(0);
    }
  });

  it('keeps a node that is still waiting for the user', () => {
    // The counterexample: dropping this would remove a screen nobody has answered.
    feed('awaiting_signal');
    feed('running');
    expect(pending().size).toBe(1);
  });
});

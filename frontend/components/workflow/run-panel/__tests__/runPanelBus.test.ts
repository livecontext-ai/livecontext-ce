/**
 * @vitest-environment jsdom
 *
 * The Run tab lives in the side panel (app-layout tree) while the run state is
 * owned by the canvas (page tree). Everything they exchange goes through this
 * bus, so the scoping rules are load-bearing:
 *  - a snapshot must only reach the panel bound to the SAME workflow (a
 *    sub-workflow tab and the main panel are mounted at the same time);
 *  - the panel body is unmounted while the side panel is closed, so the level
 *    request must survive until it mounts.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  boundRunId,
  clearRunPanelCache,
  consumeRunPanelViewRequest,
  getCachedRunPanelData,
  getRunPanelViewRequest,
  makeEmptyRunPanelData,
  openRunPanel,
  publishRunPanelData,
  requestBindRun,
  requestRunAction,
  subscribeBindRun,
  subscribeRunPanelData,
  BIND_RUN_EVENT,
  OPEN_RUN_PANEL_EVENT,
  RUN_PANEL_ACTION_EVENT,
  type RunPanelData,
} from '@/components/workflow/run-panel/runPanelBus';

const snapshot = (workflowId: string, overrides: Partial<RunPanelData> = {}): RunPanelData => ({
  ...makeEmptyRunPanelData(workflowId),
  runId: 'run-1',
  runInfo: { runId: 'run-1', status: 'RUNNING' },
  ...overrides,
});

afterEach(() => clearRunPanelCache());

describe('run panel snapshots', () => {
  it('delivers a snapshot to the subscriber bound to that workflow', () => {
    const received: RunPanelData[] = [];
    const unsubscribe = subscribeRunPanelData('wf-1', d => received.push(d));
    publishRunPanelData(snapshot('wf-1'));
    unsubscribe();

    expect(received).toHaveLength(1);
    expect(received[0].runId).toBe('run-1');
  });

  it('never delivers another workflow snapshot (sub-workflow tab isolation)', () => {
    const received: RunPanelData[] = [];
    const unsubscribe = subscribeRunPanelData('wf-1', d => received.push(d));
    publishRunPanelData(snapshot('wf-2'));
    unsubscribe();

    expect(received).toHaveLength(0);
  });

  it('caches the latest snapshot so a panel mounting later is not empty', () => {
    publishRunPanelData(snapshot('wf-1', { currentEpoch: 4 }));
    expect(getCachedRunPanelData('wf-1').currentEpoch).toBe(4);
    // An unknown workflow yields inert defaults rather than another one's run.
    expect(getCachedRunPanelData('wf-other').runInfo).toBeNull();
  });

  it('isolates two mounted surfaces of the same workflow', () => {
    const surfaceA = vi.fn();
    const surfaceB = vi.fn();
    const unsubscribeA = subscribeRunPanelData('wf-1', surfaceA, 'surface-a');
    const unsubscribeB = subscribeRunPanelData('wf-1', surfaceB, 'surface-b');

    publishRunPanelData(snapshot('wf-1', { surfaceId: 'surface-a', runId: 'run-a' }));
    publishRunPanelData(snapshot('wf-1', { surfaceId: 'surface-b', runId: 'run-b' }));

    expect(surfaceA).toHaveBeenCalledTimes(1);
    expect(surfaceA).toHaveBeenCalledWith(expect.objectContaining({ runId: 'run-a' }));
    expect(surfaceB).toHaveBeenCalledTimes(1);
    expect(surfaceB).toHaveBeenCalledWith(expect.objectContaining({ runId: 'run-b' }));
    expect(getCachedRunPanelData('wf-1', 'surface-a').runId).toBe('run-a');
    expect(getCachedRunPanelData('wf-1', 'surface-b').runId).toBe('run-b');
    expect(getCachedRunPanelData('wf-1').runId).toBeNull();

    unsubscribeA();
    unsubscribeB();
  });

  it('stops delivering after unsubscribe', () => {
    const onData = vi.fn();
    subscribeRunPanelData('wf-1', onData)();
    publishRunPanelData(snapshot('wf-1'));
    expect(onData).not.toHaveBeenCalled();
  });
});

describe('the run a canvas is bound to', () => {
  // Components deep inside the canvas key per-run state (the epoch the user
  // picked) off this. Their provider does not always know it: a panel-mounted
  // canvas resolves its run from run info with no run id in its provider, and
  // keying the same state under two ids is how a cleared epoch came back.
  it('is the run the canvas published, not the one the caller happens to hold', () => {
    publishRunPanelData(snapshot('wf-1', { runId: 'run-from-canvas' }));
    expect(boundRunId('wf-1', 'run-from-provider')).toBe('run-from-canvas');
  });

  it('falls back to the caller when the canvas has published nothing yet', () => {
    expect(boundRunId('wf-1', 'run-from-provider')).toBe('run-from-provider');
    publishRunPanelData(snapshot('wf-1', { runId: null }));
    expect(boundRunId('wf-1', 'run-from-provider')).toBe('run-from-provider');
  });

  it('never mixes workflows, and answers null when there is nothing to answer', () => {
    publishRunPanelData(snapshot('wf-1', { runId: 'run-from-canvas' }));
    expect(boundRunId('wf-2', 'run-from-provider')).toBe('run-from-provider');
    expect(boundRunId('wf-2')).toBeNull();
    expect(boundRunId(null, 'run-from-provider')).toBe('run-from-provider');
    expect(boundRunId(undefined)).toBeNull();
  });

  it('resolves the run within the requested surface', () => {
    publishRunPanelData(snapshot('wf-1', { surfaceId: 'surface-a', runId: 'run-a' }));
    publishRunPanelData(snapshot('wf-1', { surfaceId: 'surface-b', runId: 'run-b' }));

    expect(boundRunId('wf-1', null, 'surface-a')).toBe('run-a');
    expect(boundRunId('wf-1', null, 'surface-b')).toBe('run-b');
  });
});

describe('run panel level requests', () => {
  it('remembers the requested level for a panel that is not mounted yet', () => {
    openRunPanel({ workflowId: 'wf-1', view: 'history' });
    expect(getRunPanelViewRequest('wf-1')?.view).toBe('history');
  });

  it('bumps the sequence so the same level can be requested twice', () => {
    openRunPanel({ workflowId: 'wf-1', view: 'history' });
    const first = getRunPanelViewRequest('wf-1')!.seq;
    openRunPanel({ workflowId: 'wf-1', view: 'history' });
    expect(getRunPanelViewRequest('wf-1')!.seq).toBeGreaterThan(first);
  });

  it('defaults to the run detail and ignores a request aimed at another workflow', () => {
    openRunPanel({ workflowId: 'wf-1' });
    expect(getRunPanelViewRequest('wf-1')?.view).toBe('run');
    expect(getRunPanelViewRequest('wf-2')).toBeNull();
  });

  it('a request with NO workflowId applies to whichever panel asks', () => {
    // The application panel opens the Run tab without naming a workflow; the
    // request must not be swallowed there.
    openRunPanel({ view: 'run' });
    expect(getRunPanelViewRequest('wf-1')?.view).toBe('run');
    expect(getRunPanelViewRequest('wf-any-other')?.view).toBe('run');
  });

  it('emits an event carrying the requested level', () => {
    const seen: any[] = [];
    const handler = (e: Event) => seen.push((e as CustomEvent).detail);
    window.addEventListener(OPEN_RUN_PANEL_EVENT, handler);
    try {
      openRunPanel({ workflowId: 'wf-1', view: 'history' });
    } finally {
      window.removeEventListener(OPEN_RUN_PANEL_EVENT, handler);
    }
    expect(seen[0]).toMatchObject({ workflowId: 'wf-1', view: 'history' });
  });
});

describe('run binding', () => {
  it('names the run to bind in place, without any navigation', () => {
    const seen: any[] = [];
    const handler = (e: Event) => seen.push((e as CustomEvent).detail);
    window.addEventListener(BIND_RUN_EVENT, handler);
    try {
      requestBindRun({ workflowId: 'wf-1', runId: 'run-9' });
    } finally {
      window.removeEventListener(BIND_RUN_EVENT, handler);
    }
    expect(seen).toEqual([{ workflowId: 'wf-1', runId: 'run-9' }]);
  });

  it('delivers a request to the surface showing that workflow', () => {
    const seen: string[] = [];
    const off = subscribeBindRun('wf-1', (runId) => seen.push(runId));
    try {
      requestBindRun({ workflowId: 'wf-1', runId: 'run-9' });
      // A repeat is a real user action (picking the run again after the canvas
      // moved on its own), not a duplicate to swallow.
      requestBindRun({ workflowId: 'wf-1', runId: 'run-9' });
    } finally {
      off();
    }
    expect(seen).toEqual(['run-9', 'run-9']);
  });

  it('never delivers a run picked in another workflow', () => {
    const seen: string[] = [];
    const off = subscribeBindRun('wf-1', (runId) => seen.push(runId));
    try {
      requestBindRun({ workflowId: 'wf-2', runId: 'run-9' });
    } finally {
      off();
    }
    expect(seen).toEqual([]);
  });

  it('never delivers a surface-addressed pick to the page', () => {
    // The page subscribes with NO surfaceId because it owns the route. When a
    // side-panel tab shows the same workflow, its pick must not reach the page:
    // that rewrote the address bar and moved the canvas behind the panel.
    const seen: string[] = [];
    const off = subscribeBindRun('wf-1', (runId) => seen.push(runId));
    try {
      requestBindRun({ workflowId: 'wf-1', runId: 'run-9', surfaceId: 'tab-1' });
    } finally {
      off();
    }
    expect(seen).toEqual([]);
  });

  it('never delivers the page pick to a surface, nor one surface pick to another', () => {
    const tabSeen: string[] = [];
    const off = subscribeBindRun('wf-1', (runId) => tabSeen.push(runId), 'tab-1');
    try {
      requestBindRun({ workflowId: 'wf-1', runId: 'from-page' });
      requestBindRun({ workflowId: 'wf-1', runId: 'from-sibling', surfaceId: 'tab-2' });
      requestBindRun({ workflowId: 'wf-1', runId: 'mine', surfaceId: 'tab-1' });
    } finally {
      off();
    }
    expect(tabSeen).toEqual(['mine']);
  });

  it('ignores an unaddressed request, unlike the level request', () => {
    // Unlike `openRunPanel`, whose level applies to whichever panel asks, a bind
    // names a RUN: several surfaces showing different workflows listen at once,
    // and an unaddressed one would bind a foreign run into all of them.
    const seen: string[] = [];
    const off = subscribeBindRun('wf-1', (runId) => seen.push(runId));
    try {
      window.dispatchEvent(new CustomEvent(BIND_RUN_EVENT, { detail: { runId: 'run-9' } }));
    } finally {
      off();
    }
    expect(seen).toEqual([]);
  });

  it('stops delivering after unsubscribe', () => {
    const seen: string[] = [];
    const off = subscribeBindRun('wf-1', (runId) => seen.push(runId));
    off();
    requestBindRun({ workflowId: 'wf-1', runId: 'run-9' });
    expect(seen).toEqual([]);
  });
});

describe('level request lifetime', () => {
  it('is dropped once a panel has adopted it', () => {
    openRunPanel({ workflowId: 'wf-1', view: 'history' });
    consumeRunPanelViewRequest('wf-1');
    // A remount an hour later must not replay a level asked for one navigation ago.
    expect(getRunPanelViewRequest('wf-1')).toBeNull();
  });

  it('is not consumed by a panel bound to another workflow', () => {
    openRunPanel({ workflowId: 'wf-1', view: 'history' });
    consumeRunPanelViewRequest('wf-2');
    expect(getRunPanelViewRequest('wf-1')?.view).toBe('history');
  });
});

describe('workspace switch', () => {
  it('drops the pending level request, not just the snapshots', () => {
    publishRunPanelData(snapshot('wf-1'));
    openRunPanel({ workflowId: 'wf-1', view: 'history' });

    clearRunPanelCache();

    // Replaying a request minted in the previous workspace would yank the panel
    // to a level the user never asked for here.
    expect(getRunPanelViewRequest('wf-1')).toBeNull();
    expect(getCachedRunPanelData('wf-1').runInfo).toBeNull();
  });
});

describe('run actions', () => {
  it('names the action and the run so the canvas can perform it', () => {
    const seen: any[] = [];
    const handler = (e: Event) => seen.push((e as CustomEvent).detail);
    window.addEventListener(RUN_PANEL_ACTION_EVENT, handler);
    try {
      requestRunAction({ action: 'reactivate', workflowId: 'wf-1', runId: 'run-1' });
    } finally {
      window.removeEventListener(RUN_PANEL_ACTION_EVENT, handler);
    }
    // `handled` rides along so a listener can acknowledge the request; the
    // caller reads it back to decide whether to fall back to the REST call.
    expect(seen[0]).toEqual({ action: 'reactivate', workflowId: 'wf-1', runId: 'run-1', handled: false });
  });

  it('reports NOT handled when no canvas is listening', () => {
    expect(requestRunAction({ action: 'stop', workflowId: 'wf-1', runId: 'run-1' }).handled).toBe(false);
  });

  it('reports handled once a listener claims the request', () => {
    const handler = (e: Event) => { (e as CustomEvent).detail.handled = true; };
    window.addEventListener(RUN_PANEL_ACTION_EVENT, handler);
    try {
      expect(requestRunAction({ action: 'stop', workflowId: 'wf-1', runId: 'run-1' }).handled).toBe(true);
    } finally {
      window.removeEventListener(RUN_PANEL_ACTION_EVENT, handler);
    }
  });

  it('hands back the claimer promise, so the caller can wait for the real work', () => {
    // Without it the caller only learns the request was ACCEPTED, never whether
    // it worked: its in-flight state cleared in the next microtask and a
    // canvas-side failure surfaced nowhere.
    const result = Promise.resolve();
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      detail.handled = true;
      detail.result = result;
    };
    window.addEventListener(RUN_PANEL_ACTION_EVENT, handler);
    try {
      expect(requestRunAction({ action: 'stop', workflowId: 'wf-1' }).result).toBe(result);
    } finally {
      window.removeEventListener(RUN_PANEL_ACTION_EVENT, handler);
    }
  });

  it('does not mutate the request object the caller passed in', () => {
    // The detail is a copy: a caller reusing its request object across surfaces
    // would otherwise carry a stale `handled: true` into the next dispatch and
    // silently skip the REST fallback.
    const detail = { action: 'stop' as const, workflowId: 'wf-1', runId: 'run-1' };
    const handler = (e: Event) => { (e as CustomEvent).detail.handled = true; };
    window.addEventListener(RUN_PANEL_ACTION_EVENT, handler);
    try {
      requestRunAction(detail);
    } finally {
      window.removeEventListener(RUN_PANEL_ACTION_EVENT, handler);
    }
    expect((detail as Record<string, unknown>).handled).toBeUndefined();
  });
});

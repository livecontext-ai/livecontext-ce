/**
 * @vitest-environment jsdom
 *
 * Regression: the run history loaded once per workflow, so the row of the run that was
 * executing kept its "running" pulse after the run's epoch closed (or the run finished)
 * until the panel was remounted.
 *
 * The list now re-reads its rows, in place, when the live manager of a run it shows as
 * running reports that it stopped running, and whenever the WebSocket session comes back
 * (a status change published while it was down reached nobody). No polling.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';

const getWorkflowRuns = vi.hoisted(() => vi.fn());
const getPinnedWorkflowRun = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => (ns ? `${ns}.${key}` : key),
}));
vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getWorkflowRuns,
    getPinnedWorkflowRun,
    listVersions: vi.fn().mockResolvedValue({ pinnedVersion: null }),
  },
}));

// Run managers, with the real watch contract: no call on registration, then one per change.
type FakeState = { rawRunState: unknown; runStatus: string };
const managers = vi.hoisted(() => new Map<string, { state: FakeState; listeners: Set<(s: FakeState) => void> }>());
vi.mock('@/contexts/workflow-run', () => ({
  hasWorkflowRunManager: (runId: string) => managers.has(runId),
  getWorkflowRunManager: (runId: string) => ({
    getState: () => managers.get(runId)!.state,
    watch: (listener: (s: FakeState) => void) => {
      const manager = managers.get(runId)!;
      manager.listeners.add(listener);
      return () => { manager.listeners.delete(listener); };
    },
  }),
}));

const reconnect = vi.hoisted(() => ({ listeners: new Set<() => void>() }));
vi.mock('@/lib/websocket', async () => {
  const { useEffect, useRef } = await import('react');
  return {
    useWsReconnected: (callback: () => void) => {
      const ref = useRef(callback);
      ref.current = callback;
      useEffect(() => {
        const listener = () => ref.current();
        reconnect.listeners.add(listener);
        return () => { reconnect.listeners.delete(listener); };
      }, []);
    },
  };
});

import { RunHistoryList } from '@/components/workflow/run-panel/RunHistoryList';

class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() { return []; }
}
(globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = NoopObserver;

function setManager(runId: string, state: FakeState): void {
  const existing = managers.get(runId);
  if (!existing) {
    managers.set(runId, { state, listeners: new Set() });
    return;
  }
  existing.state = state;
  existing.listeners.forEach(listener => listener(state));
}

/** Infinite scroll driven by hand: the observer hands back its callback. */
function captureInfiniteScroll(): { intersect: () => void; restore: () => void } {
  let callback: ((entries: Array<{ isIntersecting: boolean }>) => void) | null = null;
  class CapturingObserver {
    constructor(cb: (entries: Array<{ isIntersecting: boolean }>) => void) { callback = cb; }
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() { return []; }
  }
  const g = globalThis as unknown as { IntersectionObserver: unknown };
  const previous = g.IntersectionObserver;
  g.IntersectionObserver = CapturingObserver;
  return {
    intersect: () => callback!([{ isIntersecting: true }]),
    restore: () => { g.IntersectionObserver = previous; },
  };
}

/** The real endpoint's paging: limit capped at 200, page = offset / limit. Newest first. */
function realEndpoint(all: Array<Record<string, unknown>>) {
  return (_wf: string, limit: number, offset: number) => {
    const size = Math.min(limit, 200);
    const start = Math.floor(offset / size) * size;
    return Promise.resolve(all.slice(start, start + size));
  };
}

function rowIds(container: HTMLElement): Array<string | null> {
  return Array.from(container.querySelectorAll('[data-run-history-row]')).map(el => el.getAttribute('title'));
}

function fireReconnect(): void {
  reconnect.listeners.forEach(listener => listener());
}

/** The list registers its watch in an effect after the row renders: drive changes only then. */
async function watched(runId: string): Promise<void> {
  await waitFor(() => expect(managers.get(runId)!.listeners.size).toBe(1));
}

const row = (runId: string, status: string, extra: Record<string, unknown> = {}) =>
  ({ id: `id-${runId}`, runId, status, startedAt: '2026-09-30T08:00:00Z', ...extra });

beforeEach(() => {
  managers.clear();
  reconnect.listeners.clear();
  getWorkflowRuns.mockReset();
  getPinnedWorkflowRun.mockReset();
  getPinnedWorkflowRun.mockResolvedValue(null);
});

afterEach(() => { cleanup(); });

describe('RunHistoryList - the live row follows its run', () => {
  it('drops the running pulse when the run\'s live manager reports its epoch closed', async () => {
    getWorkflowRuns.mockResolvedValue([row('run_a', 'RUNNING')]);
    setManager('run_a', { rawRunState: {}, runStatus: 'running' });

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('status.running')).toBeTruthy());
    await watched('run_a');

    // Epoch closed: the run rests in WAITING_TRIGGER and the row shows the cycle verdict.
    getWorkflowRuns.mockResolvedValue([row('run_a', 'WAITING_TRIGGER', { metadata: { lastCycleResult: 'COMPLETED' } })]);
    act(() => setManager('run_a', { rawRunState: {}, runStatus: 'waiting_trigger' }));

    await waitFor(() => expect(screen.getByText('status.completed')).toBeTruthy());
    expect(screen.queryByText('status.running')).toBeNull();
    // The rows already on screen, re-read in one request from the top.
    expect(getWorkflowRuns).toHaveBeenLastCalledWith('wf-1', 15, 0);
  });

  it('does not re-read while the run is still running', async () => {
    getWorkflowRuns.mockResolvedValue([row('run_a', 'RUNNING')]);
    setManager('run_a', { rawRunState: {}, runStatus: 'running' });

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('status.running')).toBeTruthy());
    await watched('run_a');

    act(() => setManager('run_a', { rawRunState: {}, runStatus: 'running' }));
    act(() => setManager('run_a', { rawRunState: {}, runStatus: 'running' }));

    expect(getWorkflowRuns).toHaveBeenCalledTimes(1);
  });

  it('re-reads once per stop, not on every later store update of a stopped run', async () => {
    // The list endpoint lags: it still says RUNNING after the stop.
    getWorkflowRuns.mockResolvedValue([row('run_a', 'RUNNING')]);
    setManager('run_a', { rawRunState: {}, runStatus: 'running' });

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('status.running')).toBeTruthy());
    await watched('run_a');

    act(() => setManager('run_a', { rawRunState: {}, runStatus: 'completed' }));
    await waitFor(() => expect(getWorkflowRuns).toHaveBeenCalledTimes(2));
    act(() => setManager('run_a', { rawRunState: {}, runStatus: 'completed' }));
    act(() => setManager('run_a', { rawRunState: {}, runStatus: 'completed' }));

    expect(getWorkflowRuns).toHaveBeenCalledTimes(2);
  });

  it('re-reads the rows, pinned run included, when the WebSocket session comes back', async () => {
    getPinnedWorkflowRun.mockResolvedValue(row('run_prod', 'RUNNING'));
    getWorkflowRuns.mockResolvedValue([row('run_b', 'COMPLETED')]);

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('status.running')).toBeTruthy());

    // The production run finished while the socket was down; no manager follows it here.
    getPinnedWorkflowRun.mockResolvedValue(row('run_prod', 'FAILED'));
    await act(async () => { reconnect.listeners.forEach(listener => listener()); });

    await waitFor(() => expect(screen.getByText('status.failed')).toBeTruthy());
    expect(screen.queryByText('status.running')).toBeNull();
    expect(screen.getByText('status.completed')).toBeTruthy(); // the other row is kept
  });

  it('ignores a manager emptied back to its defaults (destroyed): that is not the run stopping', async () => {
    getWorkflowRuns.mockResolvedValue([row('run_a', 'RUNNING')]);
    setManager('run_a', { rawRunState: {}, runStatus: 'running' });

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('status.running')).toBeTruthy());
    await watched('run_a');

    act(() => setManager('run_a', { rawRunState: null, runStatus: 'pending' }));

    expect(getWorkflowRuns).toHaveBeenCalledTimes(1);
  });

  it('does not spend its refresh on a status kept from an earlier visit, and still sees the real stop', async () => {
    getWorkflowRuns.mockResolvedValue([row('run_a', 'RUNNING')]);
    // The manager still holds the previous epoch's resting state from an earlier visit.
    setManager('run_a', { rawRunState: {}, runStatus: 'waiting_trigger' });

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('status.running')).toBeTruthy());
    await watched('run_a');
    expect(getWorkflowRuns).toHaveBeenCalledTimes(1);

    // A surface re-reads the run: it is running. Then its epoch really closes.
    act(() => setManager('run_a', { rawRunState: {}, runStatus: 'running' }));
    expect(getWorkflowRuns).toHaveBeenCalledTimes(1);
    getWorkflowRuns.mockResolvedValue([row('run_a', 'WAITING_TRIGGER', { metadata: { lastCycleResult: 'COMPLETED' } })]);
    act(() => setManager('run_a', { rawRunState: {}, runStatus: 'waiting_trigger' }));

    await waitFor(() => expect(screen.getByText('status.completed')).toBeTruthy());
    expect(getWorkflowRuns).toHaveBeenCalledTimes(2);
  });

  it('clears the load error once an in-place re-read succeeds', async () => {
    getWorkflowRuns.mockRejectedValueOnce(new Error('503'));

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('runs.loadError')).toBeTruthy());

    getWorkflowRuns.mockResolvedValue([row('run_b', 'COMPLETED')]);
    await act(async () => { reconnect.listeners.forEach(listener => listener()); });

    await waitFor(() => expect(screen.getByText('status.completed')).toBeTruthy());
    expect(screen.queryByText('runs.loadError')).toBeNull();
  });

  it('re-reads at most 195 rows (endpoint max 200, whole pages) and resumes paging right after them', async () => {
    const scroll = captureInfiniteScroll();
    try {
      const all = Array.from({ length: 400 }, (_, i) => row(`run_${i}`, 'COMPLETED'));
      getWorkflowRuns.mockImplementation(realEndpoint(all));

      const { container } = render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
      const rowCount = () => container.querySelectorAll('[data-run-history-row]').length;
      await waitFor(() => expect(rowCount()).toBe(15));
      for (let pages = 2; pages <= 14; pages++) {
        await act(async () => { scroll.intersect(); });
        await waitFor(() => expect(rowCount()).toBe(pages * 15));
      }
      expect(rowCount()).toBe(210);

      await act(async () => { fireReconnect(); });

      await waitFor(() => expect(getWorkflowRuns).toHaveBeenLastCalledWith('wf-1', 195, 0));
      await waitFor(() => expect(rowCount()).toBe(195));

      // The next page starts exactly after the refreshed rows: no gap, no duplicate.
      await act(async () => { scroll.intersect(); });
      await waitFor(() => expect(rowCount()).toBe(210));
      expect(getWorkflowRuns).toHaveBeenLastCalledWith('wf-1', 15, 195);
      expect(new Set(rowIds(container)).size).toBe(210);
    } finally {
      scroll.restore();
    }
  });

  it('keeps paging on whole pages after a re-read whose row count is not a multiple of 15', async () => {
    const scroll = captureInfiniteScroll();
    try {
      const all = Array.from({ length: 20 }, (_, i) => row(`run_old_${i}`, 'COMPLETED'));
      getWorkflowRuns.mockImplementation(realEndpoint(all));

      const { container } = render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
      const rowCount = () => container.querySelectorAll('[data-run-history-row]').length;
      await waitFor(() => expect(rowCount()).toBe(15));
      await act(async () => { scroll.intersect(); });
      await waitFor(() => expect(rowCount()).toBe(20)); // two pages, the second one short

      all.unshift(row('run_new_1', 'COMPLETED'), row('run_new_2', 'COMPLETED')); // 22 runs
      await act(async () => { fireReconnect(); });
      await waitFor(() => expect(rowCount()).toBe(22));

      for (let i = 3; i <= 20; i++) all.unshift(row(`run_new_${i}`, 'COMPLETED')); // 40 runs
      await act(async () => { fireReconnect(); });
      await waitFor(() => expect(rowCount()).toBe(30)); // the two loaded pages, full again

      await act(async () => { scroll.intersect(); });
      await waitFor(() => expect(rowCount()).toBe(40));
      expect(getWorkflowRuns).toHaveBeenLastCalledWith('wf-1', 15, 30);
      expect(new Set(rowIds(container)).size).toBe(40);
    } finally {
      scroll.restore();
    }
  });

  it('never lets the pinned-run read erase a production run already shown (it answers null on errors)', async () => {
    getPinnedWorkflowRun.mockResolvedValue(row('run_prod', 'RUNNING'));
    getWorkflowRuns.mockResolvedValue([row('run_b', 'COMPLETED')]);

    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('status.running')).toBeTruthy());

    getPinnedWorkflowRun.mockResolvedValue(null); // a 502 during a rolling deploy, swallowed to null
    await act(async () => { fireReconnect(); });
    await waitFor(() => expect(getPinnedWorkflowRun).toHaveBeenCalledTimes(2));

    expect(screen.getByText('status.running')).toBeTruthy(); // the production row is still there
  });

  it('runs one re-read at a time and follows up once, so an older answer never lands last', async () => {
    getWorkflowRuns.mockResolvedValue([row('run_a', 'RUNNING')]);
    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('status.running')).toBeTruthy());

    let answerFirst!: (rows: unknown) => void;
    getWorkflowRuns.mockImplementationOnce(() => new Promise(resolve => { answerFirst = resolve; }));
    getWorkflowRuns.mockResolvedValue([row('run_a', 'COMPLETED')]);

    await act(async () => { fireReconnect(); fireReconnect(); fireReconnect(); });
    expect(getWorkflowRuns).toHaveBeenCalledTimes(2); // the first load + one re-read in flight

    // The read in flight answers with what the run looked like when it was sent.
    await act(async () => { answerFirst([row('run_a', 'RUNNING')]); });

    await waitFor(() => expect(screen.getByText('status.completed')).toBeTruthy());
    expect(getWorkflowRuns).toHaveBeenCalledTimes(3); // exactly one follow-up for the two signals
  });
});

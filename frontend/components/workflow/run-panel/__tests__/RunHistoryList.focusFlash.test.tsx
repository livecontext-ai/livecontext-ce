/**
 * @vitest-environment jsdom
 *
 * Walking back up from a run to the run history landed the user on a list of
 * near-identical rows, with only a thin left border saying which one they had
 * just been looking at. The row of the current run is now scrolled to and
 * flashed - the same "here is what you came back to" cue the chat's go-to-message
 * jump gives - through the shared `scrollToAndFlash` helper.
 *
 * The cue is a one-shot EVENT, not a state: the class comes off on `animationend`
 * (which jsdom never fires, so it stays on the node here) and the row is never
 * remounted. What has to be pinned is therefore WHEN it is played, on WHICH row,
 * and when it is deliberately not played at all.
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
    listVersions: vi.fn().mockResolvedValue({ pinnedVersion: 12 }),
    getPinnedWorkflowRun,
  },
}));

import { RunHistoryList } from '@/components/workflow/run-panel/RunHistoryList';
import { RUN_ROW_FLASH_CLASS } from '@/components/workflow/run-panel/runFormatting';

class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() { return []; }
}
(globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = NoopObserver;

const RUNS = [
  { id: 'r1', runId: 'r1', status: 'COMPLETED', planVersion: 12, startedAt: '2026-01-01T00:00:00Z' },
  { id: 'r2', runId: 'r2', status: 'FAILED', planVersion: 11, startedAt: '2026-01-01T00:00:00Z' },
];
const PINNED = { id: 'p1', runId: 'p1', status: 'COMPLETED', planVersion: 12, startedAt: '2026-01-01T00:00:00Z' };

const rows = () => Array.from(document.querySelectorAll('[data-run-history-row]')) as HTMLElement[];
const flashed = () => rows().filter(r => r.classList.contains(RUN_ROW_FLASH_CLASS));

/** jsdom has no scrollIntoView; the helper optional-calls it. */
const scrollSpy = vi.fn();

beforeEach(() => {
  getWorkflowRuns.mockResolvedValue(RUNS);
  getPinnedWorkflowRun.mockResolvedValue(null);
  scrollSpy.mockReset();
  (Element.prototype as unknown as { scrollIntoView: unknown }).scrollIntoView = scrollSpy;
});
afterEach(() => {
  getWorkflowRuns.mockReset();
  getPinnedWorkflowRun.mockReset();
  cleanup();
});

describe('RunHistoryList - the run you came back from is highlighted', () => {
  it('flashes and scrolls to the current run row, and to no other', async () => {
    render(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);

    await waitFor(() => expect(flashed().length).toBe(1));
    const target = flashed()[0];
    // It is the row of the run we were on, not just any row.
    expect(target.textContent).toContain('status.failed');
    expect(target.getAttribute('data-selected')).toBe('true');
    expect(scrollSpy).toHaveBeenCalledTimes(1);
  });

  it('highlights nothing when no run is bound (opening the history from edit mode)', async () => {
    render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);

    await waitFor(() => expect(rows().length).toBe(2));
    expect(flashed().length).toBe(0);
    expect(scrollSpy).not.toHaveBeenCalled();
  });

  it('keeps the row mounted and focused - the cue never remounts what it points at', async () => {
    // The highlight invites the user onto that row; remounting it (to restart a
    // CSS animation, say) would send keyboard focus to <body> mid-interaction.
    const { rerender } = render(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(flashed().length).toBe(1));
    const row = flashed()[0];
    row.focus();

    // Everything that used to move the row's React key: re-render, the run going
    // away, and it coming back.
    rerender(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);
    rerender(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    rerender(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);

    await waitFor(() => expect(rows().length).toBe(2));
    expect(rows().find(r => r.getAttribute('data-selected') === 'true')).toBe(row);
    expect(document.activeElement).toBe(row);
  });

  it('replays the cue when the SAME row is re-armed', async () => {
    // A browser does not restart an animation already on an element, so the
    // helper has to take the class off and re-add it around a reflow.
    const { rerender } = render(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(scrollSpy).toHaveBeenCalledTimes(1));
    const row = flashed()[0];
    const removals: string[] = [];
    const originalRemove = row.classList.remove.bind(row.classList);
    row.classList.remove = ((...names: string[]) => {
      removals.push(...names);
      return originalRemove(...names);
    }) as never;

    rerender(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    rerender(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);

    await waitFor(() => expect(scrollSpy).toHaveBeenCalledTimes(2));
    expect(removals).toContain(RUN_ROW_FLASH_CLASS);
    expect(row.classList.contains(RUN_ROW_FLASH_CLASS)).toBe(true);
  });

  it('plays the cue once per arming, however often the list re-renders', async () => {
    const { rerender } = render(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(scrollSpy).toHaveBeenCalledTimes(1));

    // Re-renders alone are not enough to prove the guard: they change no effect
    // dependency. A refresh DOES - it moves `runs` and both settled flags - and
    // must still not replay a cue the user has already been shown.
    await act(async () => { screen.getByTitle('actions.refresh').click(); });
    await waitFor(() => expect(rows().length).toBe(2));
    rerender(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);

    expect(scrollSpy).toHaveBeenCalledTimes(1);
  });

  it('re-arms when the bound run changes under the open list', async () => {
    const { rerender } = render(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(flashed()[0]?.textContent).toContain('status.failed'));

    rerender(<RunHistoryList workflowId="wf-1" currentRunId="r1" onSelectRun={vi.fn()} />);

    await waitFor(() => expect(flashed().some(r => r.textContent?.includes('status.completed'))).toBe(true));
    expect(scrollSpy).toHaveBeenCalledTimes(2);
  });

  it('cues the NEW list after a workflow change, never the outgoing row', async () => {
    // This component is not remounted when `workflowId` changes (the pinned
    // workflow panel survives navigating from one workflow to another), so for
    // one commit the props describe B while the rows, the row ref and every
    // settled flag still describe A.
    const OTHER = [
      { id: 'o1', runId: 'o1', status: 'RUNNING', planVersion: 3, startedAt: '2026-01-01T00:00:00Z' },
      { id: 'o2', runId: 'o2', status: 'PAUSED', planVersion: 4, startedAt: '2026-01-01T00:00:00Z' },
    ];
    const { rerender } = render(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(flashed()[0]?.textContent).toContain('status.failed'));
    scrollSpy.mockClear();

    let resolveOther: (runs: unknown[]) => void = () => {};
    getWorkflowRuns.mockReturnValue(new Promise((resolve) => { resolveOther = resolve as never; }));
    rerender(<RunHistoryList workflowId="wf-2" currentRunId="o2" onSelectRun={vi.fn()} />);

    // While wf-2 is loading nothing may be cued: the rows on screen are wf-1's.
    expect(scrollSpy).not.toHaveBeenCalled();
    expect(flashed().length).toBe(0);

    await act(async () => { resolveOther(OTHER); });

    await waitFor(() => expect(flashed().length).toBe(1));
    expect(flashed()[0].textContent).toContain('status.paused');
    expect(scrollSpy).toHaveBeenCalledTimes(1);
  });

  it('waits for the pinned run before giving up - it is prepended, so it can be the target', async () => {
    // The pinned (production) run comes from its OWN fetch and is put at the top:
    // a sub-workflow tab is opened on exactly that run.
    let resolvePinned: (run: unknown) => void = () => {};
    getPinnedWorkflowRun.mockReturnValue(new Promise((resolve) => { resolvePinned = resolve as never; }));

    render(<RunHistoryList workflowId="wf-1" currentRunId="p1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(rows().length).toBe(2));
    expect(flashed().length).toBe(0);

    await act(async () => { resolvePinned(PINNED); });

    await waitFor(() => expect(flashed().length).toBe(1));
    expect(flashed()[0]).toBe(rows()[0]);
    expect(scrollSpy).toHaveBeenCalledTimes(1);
  });

  it('gives up once both fetches have settled, so a late arrival cannot ambush the user', async () => {
    // The run came back from further down the history than the first page. The
    // cue is spent - a row surfacing later through infinite scroll must not light
    // up and drag the list under someone who is reading it.
    render(<RunHistoryList workflowId="wf-1" currentRunId="p1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(rows().length).toBe(2));
    expect(flashed().length).toBe(0);

    // The row finally lands (the pinned fetch is the reachable late path here).
    getWorkflowRuns.mockResolvedValue([...RUNS, PINNED]);
    await act(async () => {
      window.dispatchEvent(new CustomEvent('workflowPinnedVersionChange', { detail: { pinnedVersion: 12 } }));
    });
    getPinnedWorkflowRun.mockResolvedValue(PINNED);
    await act(async () => {
      window.dispatchEvent(new CustomEvent('workflowPinnedVersionChange', { detail: { pinnedVersion: 12 } }));
    });

    await waitFor(() => expect(rows().length).toBe(3));
    expect(flashed().length).toBe(0);
    expect(scrollSpy).not.toHaveBeenCalled();
  });

  it('re-arms for the SAME run id when only the workflow changes', async () => {
    // Run ids are not unique across workflows, and a new list is a new "came
    // back" context: keying the arming on the run alone would show the second
    // list with no cue at all.
    const OTHER = [{ id: 'r2', runId: 'r2', status: 'RUNNING', planVersion: 3, startedAt: '2026-01-01T00:00:00Z' }];
    const { rerender } = render(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(flashed()[0]?.textContent).toContain('status.failed'));
    scrollSpy.mockClear();

    getWorkflowRuns.mockResolvedValue(OTHER);
    rerender(<RunHistoryList workflowId="wf-2" currentRunId="r2" onSelectRun={vi.fn()} />);

    await waitFor(() => expect(flashed()[0]?.textContent).toContain('status.running'));
    expect(scrollSpy).toHaveBeenCalledTimes(1);
  });

  it('appends the next page when the infinite-scroll sentinel is reached', async () => {
    const observed: Array<() => void> = [];
    (globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = class {
      constructor(private cb: (entries: Array<{ isIntersecting: boolean }>) => void) {
        observed.push(() => this.cb([{ isIntersecting: true }]));
      }
      observe() {} unobserve() {} disconnect() {} takeRecords() { return []; }
    };
    const firstPage = Array.from({ length: 15 }, (_, index) => ({
      id: `first-${index}`,
      runId: `first-${index}`,
      status: 'COMPLETED',
      planVersion: 1,
      startedAt: '2026-01-01T00:00:00Z',
    }));
    const secondPage = [{
      id: 'second-1',
      runId: 'second-1',
      status: 'RUNNING',
      planVersion: 2,
      startedAt: '2026-01-02T00:00:00Z',
    }];
    getWorkflowRuns
      .mockResolvedValueOnce(firstPage)
      .mockResolvedValueOnce(secondPage);

    try {
      render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
      await waitFor(() => expect(rows()).toHaveLength(15));

      await act(async () => { observed[observed.length - 1]?.(); });

      await waitFor(() => expect(rows()).toHaveLength(16));
      expect(getWorkflowRuns).toHaveBeenNthCalledWith(1, 'wf-1', 15, 0);
      expect(getWorkflowRuns).toHaveBeenNthCalledWith(2, 'wf-1', 15, 15);
    } finally {
      (globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = NoopObserver;
    }
  });

  it('discards a load-more that belonged to the previous list', async () => {
    // The observer fires page 2 for wf-1, the panel switches to wf-2, and wf-1's
    // page then lands: appending it would put one workflow's rows under
    // another's header and rewind the paging offset with them.
    let resolveMore: (runs: unknown[]) => void = () => {};
    const OTHER = [{ id: 'o1', runId: 'o1', status: 'RUNNING', planVersion: 3, startedAt: '2026-01-01T00:00:00Z' }];
    const observed: Array<() => void> = [];
    (globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = class {
      constructor(private cb: (e: Array<{ isIntersecting: boolean }>) => void) {
        observed.push(() => this.cb([{ isIntersecting: true }]));
      }
      observe() {} unobserve() {} disconnect() {} takeRecords() { return []; }
    };
    // A full page makes `hasMore` true, so the observer is installed.
    const FULL = Array.from({ length: 15 }, (_, i) => ({
      id: `p${i}`, runId: `p${i}`, status: 'COMPLETED', planVersion: 1, startedAt: '2026-01-01T00:00:00Z',
    }));
    getWorkflowRuns.mockResolvedValueOnce(FULL);

    const { rerender } = render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(rows().length).toBe(15));

    getWorkflowRuns.mockReturnValueOnce(new Promise((resolve) => { resolveMore = resolve as never; }));
    await act(async () => { observed.forEach(fire => fire()); });

    getWorkflowRuns.mockResolvedValue(OTHER);
    rerender(<RunHistoryList workflowId="wf-2" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(rows().length).toBe(1));

    await act(async () => { resolveMore(FULL); });

    expect(rows().length).toBe(1);
    (globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = NoopObserver;
  });

  it('discards a pinned run that belonged to the previous list', async () => {
    // Same race on the parallel fetch: it PREPENDS its run, so a slow response
    // would put a foreign workflow's run at the top of this list, and declare
    // this list's pinned fetch settled while it is still in flight.
    let resolvePinned: (run: unknown) => void = () => {};
    getPinnedWorkflowRun.mockReturnValueOnce(new Promise((resolve) => { resolvePinned = resolve as never; }));
    const OTHER = [{ id: 'o1', runId: 'o1', status: 'RUNNING', planVersion: 3, startedAt: '2026-01-01T00:00:00Z' }];

    const { rerender } = render(<RunHistoryList workflowId="wf-1" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(rows().length).toBe(2));

    getWorkflowRuns.mockResolvedValue(OTHER);
    rerender(<RunHistoryList workflowId="wf-2" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(rows().length).toBe(1));

    await act(async () => { resolvePinned(PINNED); });

    expect(rows().length).toBe(1);
    expect(rows()[0].textContent).toContain('status.running');
  });

  it('gives up on a failed first page instead of staying armed', async () => {
    getWorkflowRuns.mockRejectedValue(new Error('boom'));
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    render(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('runs.loadError')).toBeTruthy());

    // A retry that finally produces the row must not flash it.
    getWorkflowRuns.mockResolvedValue(RUNS);
    await act(async () => { screen.getByText('errors.retry').click(); });

    await waitFor(() => expect(rows().length).toBe(2));
    expect(flashed().length).toBe(0);
    errorSpy.mockRestore();
  });

  it('lets the newest reset win when two overlap', async () => {
    // The list is switched to another workflow while the first fetch is still in
    // flight; the older one resolving last must not clobber the newer list.
    let resolveFirst: (runs: unknown[]) => void = () => {};
    getWorkflowRuns.mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve as never; }));
    const STALE = [{ id: 'stale', runId: 'stale', status: 'TIMEOUT', planVersion: 1, startedAt: '2026-01-01T00:00:00Z' }];

    const { rerender } = render(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);
    rerender(<RunHistoryList workflowId="wf-2" currentRunId="r2" onSelectRun={vi.fn()} />);
    await waitFor(() => expect(rows().length).toBe(2));

    await act(async () => { resolveFirst(STALE); });

    expect(rows().length).toBe(2);
    expect(screen.queryByText('status.timeout')).toBeNull();
  });

  it('marks the current run for assistive tech, not just for the eye', async () => {
    render(<RunHistoryList workflowId="wf-1" currentRunId="r2" onSelectRun={vi.fn()} />);

    await waitFor(() => expect(rows().length).toBe(2));
    const current = rows().filter(r => r.getAttribute('aria-current') === 'true');
    expect(current.length).toBe(1);
    expect(current[0].getAttribute('data-selected')).toBe('true');
  });
});

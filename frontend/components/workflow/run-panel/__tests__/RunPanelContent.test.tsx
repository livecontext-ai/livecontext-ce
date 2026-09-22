/**
 * @vitest-environment jsdom
 *
 * The Run tab is a two-level hierarchy: run history (which run?) is the PARENT
 * of the run detail (which epoch? which step?). These tests pin that contract -
 * a picked run drills down, the back affordances walk back up, and surfaces
 * whose run is frozen (marketplace preview, embedded canvas) never expose the
 * history at all.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

const push = vi.hoisted(() => vi.fn());
const setRunId = vi.hoisted(() => vi.fn());
const setViewingEpoch = vi.hoisted(() => vi.fn());
/** Run the page context is bound to - null when the workflow has never run. */
const ctxRunId = vi.hoisted(() => ({ value: 'run-1' as string | null }));
/** Epoch the panel's own provider currently holds - null = freshly mounted. */
const ctxEpoch = vi.hoisted(() => ({ value: 2 as number | null }));
const historyProps = vi.hoisted(() => ({ current: null as any }));
const summaryProps = vi.hoisted(() => ({ current: null as any }));
const stepsProps = vi.hoisted(() => ({ current: null as any }));

const api = vi.hoisted(() => ({
  stopWorkflow: vi.fn(async () => ({}) as never),
  cancelWorkflow: vi.fn(async () => ({}) as never),
  reactivateWorkflow: vi.fn(async () => ({}) as never),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: api }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k, useLocale: () => 'en' }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
/** Workspace role the Run tab is rendered under. */
const orgRole = vi.hoisted(() => ({ canMutate: true }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => orgRole.canMutate,
}));
/** Route the Run tab is mounted on - a public share link is not the same thing. */
const pathname = vi.hoisted(() => ({ current: '/app/workflow/wf-1' }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => pathname.current }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ runId: ctxRunId.value, setRunId, viewingEpoch: ctxEpoch.value, setViewingEpoch }),
}));
vi.mock('@/components/workflow/run-panel/RunHistoryList', () => ({
  RunHistoryList: (props: any) => {
    historyProps.current = props;
    return (
      <button type="button" data-testid="history" onClick={() => props.onSelectRun({ runId: 'run-2', id: 'run-2' })}>
        history
      </button>
    );
  },
}));
vi.mock('@/components/workflow/run-panel/RunSummaryBar', () => ({
  RunSummaryBar: (props: any) => {
    summaryProps.current = props;
    return (
      <div data-testid="summary">
        {props.leading}
        <button type="button" data-testid="version" onClick={() => props.onVersionClick?.()}>version</button>
        {props.trailing}
      </div>
    );
  },
}));
vi.mock('@/components/workflow/run-panel/RunStepsPanel', () => ({
  RunStepsPanel: (props: any) => { stepsProps.current = props; return <div data-testid="steps" />; },
}));

import { RunPanelContent } from '@/components/workflow/run-panel/RunPanelContent';
import { clearRunPanelCache, publishRunPanelData, makeEmptyRunPanelData } from '@/components/workflow/run-panel/runPanelBus';
import { resetEpochSelectionState } from '@/components/workflow/run-panel/useDefaultEpochSelection';

/** The back arrow rendered inside the summary bar's `leading` slot. */
const backButton = () => document.querySelector<HTMLElement>('[data-run-panel-back]');
/** The "back to the canvas" control, also in the `leading` slot. */
const workflowButton = () => document.querySelector<HTMLElement>('[data-run-panel-to-workflow]');

function publish(overrides: Record<string, unknown> = {}) {
  publishRunPanelData({
    ...makeEmptyRunPanelData('wf-1'),
    runId: 'run-1',
    runInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 3 },
    currentEpoch: 2,
    epochTimestamps: [{ epoch: 1, startedAt: 'x', endedAt: 'y' }, { epoch: 2, startedAt: 'z', endedAt: null }],
    ...overrides,
  } as any);
}

beforeEach(() => { ctxRunId.value = 'run-1'; ctxEpoch.value = 2; publish(); });
afterEach(() => {
  clearRunPanelCache();
  resetEpochSelectionState();
  ctxRunId.value = 'run-1';
  ctxEpoch.value = 2;
  push.mockReset(); setRunId.mockReset(); setViewingEpoch.mockReset();
  historyProps.current = null; summaryProps.current = null; stepsProps.current = null;
  cleanup();
});

describe('RunPanelContent - run history is the parent of the run detail', () => {
  it('opens on the run detail and feeds it the published run snapshot', () => {
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(screen.getByTestId('summary')).toBeTruthy();
    expect(stepsProps.current.epochTimestamps).toHaveLength(2);
    expect(stepsProps.current.selectedEpoch).toBe(2);
  });

  it('walks back up to the history from the back button AND the version chip', () => {
    const { rerender } = render(<RunPanelContent workflowId="wf-1" allowHistory />);

    fireEvent.click(backButton()!);
    expect(screen.getByTestId('history')).toBeTruthy();

    // Back down, then up again via the version chip - both lead to the same level.
    rerender(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'run', seq: 1 }} />);
    fireEvent.click(screen.getByTestId('version'));
    expect(screen.getByTestId('history')).toBeTruthy();
  });

  it('picking a run binds it IN PLACE (no navigation) and drills into its detail', () => {
    const bound: any[] = [];
    const onBind = (e: Event) => bound.push((e as CustomEvent).detail);
    window.addEventListener('workflowBindRun', onBind);
    try {
      render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />);
      fireEvent.click(screen.getByTestId('history'));
    } finally {
      window.removeEventListener('workflowBindRun', onBind);
    }

    expect(setRunId).toHaveBeenCalledWith('run-2');
    expect(bound).toEqual([{ workflowId: 'wf-1', runId: 'run-2' }]);
    // A router push here would remount the whole route - the "full page refresh"
    // the user sees just for looking at a sibling run.
    expect(push).not.toHaveBeenCalled();
    expect(screen.getByTestId('summary')).toBeTruthy();
  });

  it('marks the current run as selected in the history list', () => {
    render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />);
    expect(historyProps.current.currentRunId).toBe('run-1');
  });

  it('honours a re-requested level even when the level did not change', () => {
    const { rerender } = render(
      <RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />,
    );
    fireEvent.click(screen.getByTestId('history')); // navigates to the run detail
    expect(screen.getByTestId('summary')).toBeTruthy();

    rerender(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 2 }} />);
    expect(screen.getByTestId('history')).toBeTruthy();
  });
});

describe('RunPanelContent - how many epochs the bar is told the run has', () => {
  it('hands the bar the epochs that EXIST, not the engine cursor', () => {
    // The engine moves its cursor to N+1 the moment an epoch closes, to prepare
    // the next cycle - that epoch is dormant and has no row. Feeding the bar the
    // cursor made a run fired ONCE announce "All epochs (2)" directly above a
    // selector listing one. The count and the list must come from one source.
    publish({ currentEpoch: 3, epochTimestamps: [{ epoch: 1, startedAt: 'x', endedAt: 'y' }] });
    render(<RunPanelContent workflowId="wf-1" />);

    expect(summaryProps.current.epochCount).toBe(1);
    // Same array the selector below renders: they cannot disagree.
    expect(stepsProps.current.epochTimestamps).toHaveLength(1);
  });

  it('says zero before the run has any epoch at all, whatever the cursor claims', () => {
    publish({ currentEpoch: 1, epochTimestamps: [] });
    render(<RunPanelContent workflowId="wf-1" />);
    expect(summaryProps.current.epochCount).toBe(0);
  });
});

describe('RunPanelContent - frozen-run surfaces', () => {
  it('locks the marketplace preview to the run detail, with no way back to the history', () => {
    publish({ isPreviewOnly: true });
    render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />);

    expect(screen.getByTestId('summary')).toBeTruthy();
    expect(screen.queryByTestId('history')).toBeNull();
    expect(backButton()).toBeNull();
    expect(summaryProps.current.onVersionClick).toBeUndefined();
  });

  it('hides the run actions on a preview (a frozen run cannot be stopped)', () => {
    publish({ isPreviewOnly: true });
    render(<RunPanelContent workflowId="wf-1" />);
    expect(summaryProps.current.onStop).toBeUndefined();
    expect(summaryProps.current.onReactivate).toBeUndefined();
  });

  it('keeps an embedded canvas (allowHistory=false) on its own run', () => {
    render(<RunPanelContent workflowId="wf-1" />);
    expect(backButton()).toBeNull();
    expect(screen.getByTestId('summary')).toBeTruthy();
  });
});

describe('RunPanelContent - no run yet', () => {
  it('falls back to the history when the workflow has no run bound at all', () => {
    ctxRunId.value = null;
    publish({ runId: null, runInfo: null });
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(screen.getByTestId('history')).toBeTruthy();
  });

  it('shows an empty state instead of a broken detail when history is not available', () => {
    ctxRunId.value = null;
    publish({ runId: null, runInfo: null });
    render(<RunPanelContent workflowId="wf-1" />);
    expect(screen.queryByTestId('summary')).toBeNull();
    expect(screen.getByText('runs.noRuns')).toBeTruthy();
  });

  it('STAYS on the run level while a bound run has no snapshot yet (just launched)', () => {
    // The canvas has not published its first snapshot; bouncing to the history
    // here is what made "open the run I just launched" land on the wrong level.
    publish({ runId: null, runInfo: null }); // context still holds run-1
    render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'run', seq: 1 }} />);
    expect(screen.queryByTestId('history')).toBeNull();
    expect(screen.getByText('runs.loading')).toBeTruthy();
  });
});

describe('RunPanelContent - run actions', () => {
  it('asks the canvas to stop / cancel / reactivate the run it is showing', async () => {
    const seen: any[] = [];
    const handler = (e: Event) => seen.push((e as CustomEvent).detail);
    window.addEventListener('workflowRunAction', handler);
    try {
      render(<RunPanelContent workflowId="wf-1" allowHistory />);
      act(() => { summaryProps.current.onStop(); });
      act(() => { summaryProps.current.onCancel(); });
      act(() => { summaryProps.current.onReactivate(); });
    } finally {
      window.removeEventListener('workflowRunAction', handler);
    }

    expect(seen.map((d) => ({ action: d.action, workflowId: d.workflowId, runId: d.runId, handled: d.handled }))).toEqual([
      { action: 'stop', workflowId: 'wf-1', runId: 'run-1', handled: false },
      { action: 'cancel', workflowId: 'wf-1', runId: 'run-1', handled: false },
      { action: 'reactivate', workflowId: 'wf-1', runId: 'run-1', handled: false },
    ]);
  });

  it('hides all three actions from a VIEWER, who may watch but not steer', () => {
    // This bar offers the hard CANCEL and the reactivate as well as the stop, so
    // a role gate matters more here than on the tab bar, not less.
    orgRole.canMutate = false;
    try {
      render(<RunPanelContent workflowId="wf-1" allowHistory />);
      expect(summaryProps.current.onStop).toBeUndefined();
      expect(summaryProps.current.onCancel).toBeUndefined();
      expect(summaryProps.current.onReactivate).toBeUndefined();
    } finally {
      orgRole.canMutate = true;
    }
  });

  it('hides all three on a public share link, where none of them is allow-listed', () => {
    // The panel is kept off a share page by a `display:none` wrapper, which is
    // layout, not authorization - and the role check reads an anonymous visitor
    // as a personal workspace, so it does not help either.
    pathname.current = '/s/some-share-token';
    try {
      render(<RunPanelContent workflowId="wf-1" allowHistory />);
      expect(summaryProps.current.onStop).toBeUndefined();
      expect(summaryProps.current.onCancel).toBeUndefined();
      expect(summaryProps.current.onReactivate).toBeUndefined();
    } finally {
      pathname.current = '/app/workflow/wf-1';
    }
  });

  it('acts on the run just PICKED, not the one the bus still names', async () => {
    // Picking a run binds it here and only then asks the page to rebind the
    // canvas; until it does, the bus still names the previous run. Dropping the
    // id from the hook call would stop that previous run from a bar showing the
    // new one - the exact case the hook's own doc calls out.
    publish({ runId: 'run-STALE', runInfo: { runId: 'run-STALE', status: 'RUNNING' } });
    render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />);
    act(() => { screen.getByTestId('history').click(); });

    await act(async () => { summaryProps.current.onStop(); });

    expect(api.stopWorkflow).toHaveBeenCalledWith('run-2');
  });

  it('offers all three to a member', () => {
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(summaryProps.current.onStop).toBeTypeOf('function');
    expect(summaryProps.current.onCancel).toBeTypeOf('function');
    expect(summaryProps.current.onReactivate).toBeTypeOf('function');
  });

  it('tells the bar what is in flight and what failed, so the control is never inert', async () => {
    // Both are passed straight through to the shared control: without them a
    // pressed stop looks identical to a stop that never happened.
    render(<RunPanelContent workflowId="wf-1" allowHistory />);

    expect(summaryProps.current.actionPending).toBeNull();
    expect(summaryProps.current.actionFailed).toBe(false);

    // Observed NON-null mid-flight: asserting it is null before and after cannot
    // see the prop stop being forwarded at all.
    let release!: () => void;
    api.stopWorkflow.mockImplementationOnce(() => new Promise((_r, rej) => {
      release = () => rej(new Error('backend refused'));
    }));
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    await act(async () => { summaryProps.current.onStop(); });
    expect(summaryProps.current.actionPending).toBe('stop');

    await act(async () => { release(); });
    errors.mockRestore();

    expect(summaryProps.current.actionFailed).toBe(true);
    expect(summaryProps.current.actionPending).toBeNull();
  });
});

describe('RunPanelContent - picking a run', () => {
  it('still asks the page to align on the run already being shown', () => {
    // An agent-launched run is bound in place on the EDIT url, and picking it
    // here is how the user says "keep this one" - so the request goes out and
    // the page decides whether there is an address bar left to align. Deciding
    // here would need the panel to know the route, which it does not: it is
    // also mounted on surfaces whose URL says nothing about a run.
    const bound: any[] = [];
    const onBind = (e: Event) => bound.push((e as CustomEvent).detail);
    window.addEventListener('workflowBindRun', onBind);
    try {
      render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />);
      act(() => { historyProps.current.onSelectRun({ runId: 'run-1', id: 'run-1' }); });
    } finally {
      window.removeEventListener('workflowBindRun', onBind);
    }
    expect(bound).toEqual([{ workflowId: 'wf-1', runId: 'run-1', surfaceId: undefined }]);
    expect(screen.getByTestId('summary')).toBeTruthy();
  });

  it('names the surface it belongs to, so the pick binds THAT canvas and no other', () => {
    // Two surfaces can show the same workflow at once (the page and a side-panel
    // workflow tab). Without the surface on the request, a pick made in the tab
    // is delivered to the page instead: its address bar is rewritten and its
    // canvas swaps behind the panel, while the tab's own canvas never moves.
    const bound: any[] = [];
    const onBind = (e: Event) => bound.push((e as CustomEvent).detail);
    window.addEventListener('workflowBindRun', onBind);
    try {
      render(<RunPanelContent workflowId="wf-1" allowHistory surfaceId="tab-7" viewRequest={{ view: 'history', seq: 1 }} />);
      act(() => { historyProps.current.onSelectRun({ runId: 'run-1', id: 'run-1' }); });
    } finally {
      window.removeEventListener('workflowBindRun', onBind);
    }
    expect(bound).toEqual([{ workflowId: 'wf-1', runId: 'run-1', surfaceId: 'tab-7' }]);
  });

  it('ignores a row with no usable id instead of binding to undefined', () => {
    const bound: any[] = [];
    const onBind = (e: Event) => bound.push((e as CustomEvent).detail);
    window.addEventListener('workflowBindRun', onBind);
    try {
      render(<RunPanelContent workflowId="wf-1" allowHistory viewRequest={{ view: 'history', seq: 1 }} />);
      act(() => { historyProps.current.onSelectRun({}); });
    } finally {
      window.removeEventListener('workflowBindRun', onBind);
    }
    expect(bound).toEqual([]);
    expect(setRunId).not.toHaveBeenCalled();
    // Still on the history: nothing was selected.
    expect(screen.getByTestId('history')).toBeTruthy();
  });
});

describe('RunPanelContent - epoch selection', () => {
  it('tells its own provider which run it shows, so epoch picks stay scoped', () => {
    // The side panel mounts its OWN WorkflowModeProvider and the workflow-panel
    // tab does not name a run when it does. Until the provider is told, its
    // viewingEpoch broadcast carries runId:null, which every other mounted
    // provider adopts - picking an epoch here dragged a sub-workflow tab and an
    // application tab to the same epoch.
    ctxRunId.value = null;
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(setRunId).toHaveBeenCalledWith('run-1');
  });

  it('announces the run to its provider without touching the epoch', () => {
    // setRunId is a state update: selecting an epoch in the same commit would
    // broadcast it with runId:null, which every other mounted provider adopts -
    // the cross-talk the scoping exists to prevent, on the very first selection.
    ctxRunId.value = null;
    ctxEpoch.value = null;
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(setRunId).toHaveBeenCalledWith('run-1');
    expect(setViewingEpoch).not.toHaveBeenCalled();
  });

  it('opens on "All epochs", never on an epoch picked for the user', () => {
    // A run is its whole sequence of fires: landing on one epoch hid the others
    // behind a selector nobody touched. Only an explicit pick shows a single one.
    ctxEpoch.value = null;
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(setViewingEpoch).not.toHaveBeenCalled();
  });

  it('does not re-announce a run the provider already knows', () => {
    ctxRunId.value = 'run-1';
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(setRunId).not.toHaveBeenCalled();
  });

  it('records an explicit epoch pick so the other surfaces show the same epoch', async () => {
    const { getPickedEpoch } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    expect(getPickedEpoch('run-1'), 'nothing picked before the click').toBeUndefined();
    render(<RunPanelContent workflowId="wf-1" allowHistory />);

    act(() => { stepsProps.current.onSelectEpoch(1); });

    expect(setViewingEpoch).toHaveBeenCalledWith(1);
    expect(getPickedEpoch('run-1')).toBe(1);
  });

  it('records a pick BACK to "All epochs" too, so nothing restores the old epoch', async () => {
    const { getPickedEpoch } = await import(
      '@/components/workflow/run-panel/useDefaultEpochSelection'
    );
    render(<RunPanelContent workflowId="wf-1" allowHistory />);

    act(() => { stepsProps.current.onSelectEpoch(2); });
    act(() => { stepsProps.current.onSelectEpoch(null); });

    expect(setViewingEpoch).toHaveBeenLastCalledWith(null);
    expect(getPickedEpoch('run-1')).toBeNull();
  });
});

describe('RunPanelContent - the way back to the workflow canvas', () => {
  it('opens the logs child from the right side of the run header', () => {
    const onOpenLogs = vi.fn();
    render(<RunPanelContent workflowId="wf-1" onOpenLogs={onOpenLogs} />);

    fireEvent.click(screen.getByRole('button', { name: 'workflow.logs.openLogs' }));
    expect(onOpenLogs).toHaveBeenCalledTimes(1);
  });

  it('offers a labelled control in the run header when the host has a canvas tab', () => {
    const onBackToWorkflow = vi.fn();
    render(<RunPanelContent workflowId="wf-1" onBackToWorkflow={onBackToWorkflow} />);

    const button = workflowButton();
    expect(button).toBeTruthy();
    // Labelled, not a bare glyph: it leaves the Run view entirely, unlike the
    // history arrow, which only walks one level up inside it.
    expect(button!.textContent).toContain('common.workflow');
    fireEvent.click(button!);
    expect(onBackToWorkflow).toHaveBeenCalledTimes(1);
  });

  it('omits it when there is no canvas tab to go back to', () => {
    // Without a workflow slot the host renders no Workflow sub-tab, so the button
    // would lead nowhere.
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(workflowButton()).toBeNull();
    expect(backButton()).toBeTruthy();
  });

  it('shows it beside the run-history arrow, not instead of it', () => {
    // The application panel has no history, but the sub-workflow tab has both, and
    // one leading control must not evict the other.
    render(<RunPanelContent workflowId="wf-1" allowHistory onBackToWorkflow={vi.fn()} />);
    expect(workflowButton()).toBeTruthy();
    expect(backButton()).toBeTruthy();
  });

  it('stays available on the "All epochs" view, where the sub-tab bar is furthest away', () => {
    // All epochs is the default view and the tallest one: its steps push the panel's
    // own tab bar off the bottom of the screen, which is what made the way back to
    // the canvas unreachable from the top of the run.
    ctxEpoch.value = null;
    render(<RunPanelContent workflowId="wf-1" onBackToWorkflow={vi.fn()} />);
    expect(stepsProps.current.selectedEpoch).toBeNull();
    expect(workflowButton()).toBeTruthy();
  });

  it('is offered on the loading state too, the state the tab opens on after a launch', () => {
    // A run whose snapshot has not arrived yet renders a placeholder, not the run
    // header - which used to be the one place in the Run tab with no way back to
    // the canvas, reached every time a user presses Run and opens the tab.
    publish({ runInfo: null });
    render(<RunPanelContent workflowId="wf-1" onBackToWorkflow={vi.fn()} />);
    expect(screen.queryByTestId('summary'), 'no run header yet').toBeNull();
    expect(workflowButton()).toBeTruthy();
  });

  it('is offered on the run HISTORY level too, which is where a workflow with no runs lands', () => {
    // A canvas whose host can rebind it auto-switches to the history when there is
    // no run, so this is the FIRST thing a user sees before their first run - and a
    // long list of runs pushes the sub-tab bar off the bottom exactly like a long
    // list of steps does. Leaving it out made that state a dead end.
    publish({ runId: null, runInfo: null });
    ctxRunId.value = null;
    const onBackToWorkflow = vi.fn();
    render(<RunPanelContent workflowId="wf-1" allowHistory onBackToWorkflow={onBackToWorkflow} />);

    expect(screen.getByTestId('history')).toBeTruthy();
    const button = workflowButton();
    expect(button).toBeTruthy();
    fireEvent.click(button!);
    expect(onBackToWorkflow).toHaveBeenCalledTimes(1);
  });

  it('leaves the history level bare when the host has no canvas tab', () => {
    publish({ runId: null, runInfo: null });
    ctxRunId.value = null;
    render(<RunPanelContent workflowId="wf-1" allowHistory />);
    expect(screen.getByTestId('history')).toBeTruthy();
    expect(workflowButton()).toBeNull();
  });

  it('shows both ways out side by side on the loading state', () => {
    // The two controls answer different questions (which run? / leave the run
    // view), so a surface that offers both must render both.
    publish({ runInfo: null });
    render(<RunPanelContent workflowId="wf-1" allowHistory onBackToWorkflow={vi.fn()} />);
    expect(workflowButton()).toBeTruthy();
    expect(screen.getByText('runs.title')).toBeTruthy();
  });
});

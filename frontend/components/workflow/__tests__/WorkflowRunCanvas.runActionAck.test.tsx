/**
 * @vitest-environment jsdom
 *
 * The canvas must ACKNOWLEDGE the run actions it takes.
 *
 * A run action asked for from a panel travels as a `CustomEvent`, and a
 * `CustomEvent` nobody hears is a silent no-op - that is how a live run ended up
 * with a stop button that did nothing on surfaces with no canvas. The caller now
 * falls back to the REST call when the request goes unclaimed, which only works
 * if the canvas reliably says "mine":
 *
 *  - it claims requests for its own workflow, and takes them;
 *  - it claims them in PREVIEW too, then declines - otherwise the fallback would
 *    quietly perform on the publisher's workflow the very action the read-only
 *    surface exists to refuse;
 *  - it stays out of requests addressed to another workflow, so the canvas of a
 *    sub-workflow tab cannot answer for the page behind it.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const runActions = vi.hoisted(() => ({
  cancelRun: vi.fn(async () => undefined),
  hardCancelRun: vi.fn(async () => undefined),
  reactivateRun: vi.fn(async () => undefined),
}));
const mode = vi.hoisted(() => ({ isPreviewOnly: false, runId: 'run-1' as string | null }));
/** Workspace role the canvas is rendered under. */
const orgRole = vi.hoisted(() => ({ canMutate: true }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => orgRole.canMutate,
}));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/app/workflows/builder/components/WorkflowBuilder', () => ({
  WorkflowBuilder: () => <div data-testid="builder" />,
}));
/** Captures what the canvas hands its pill, so the pill's state is assertable. */
const toggleProps = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));
vi.mock('@/components/workflow/WorkflowModeToggle', () => ({
  WorkflowModeToggle: (props: Record<string, unknown>) => { toggleProps.current = props; return null; },
}));
// The canvas fetches run info, versions and the plan on mount; none of that is
// what this suite is about, so every call answers empty.
vi.mock('@/lib/api', () => ({
  orchestratorApi: new Proxy({}, {
    get: () => vi.fn(async () => null),
  }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({
    mode: 'run',
    isPreviewOnly: mode.isPreviewOnly,
    viewingEpoch: null,
    setViewingEpoch: vi.fn(),
    runId: mode.runId,
  }),
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useWorkflowRunContext: () => runActions,
}));
vi.mock('@/components/views/workflow/hooks', () => ({ useWorkflowEventBridge: () => undefined }));
vi.mock('@/components/workflow/run-panel/useDefaultEpochSelection', () => ({
  useDefaultEpochSelection: () => undefined,
}));
vi.mock('@/contexts/workflow-run/streamingDebug', () => ({ streamDebug: { log: () => undefined } }));

import { WorkflowRunCanvas } from '@/components/workflow/WorkflowRunCanvas';
import { requestRunAction, clearRunPanelCache } from '@/components/workflow/run-panel/runPanelBus';

beforeEach(() => {
  mode.isPreviewOnly = false;
  mode.runId = 'run-1';
  orgRole.canMutate = true;
  runActions.cancelRun.mockClear();
  runActions.hardCancelRun.mockClear();
  runActions.reactivateRun.mockClear();
  clearRunPanelCache();
  toggleProps.current = null;
});
afterEach(() => { clearRunPanelCache(); cleanup(); });

describe('WorkflowRunCanvas - what the canvas pill is told', () => {
  it('spins the pill while its own action is in flight, and clears it after', async () => {
    // The pill is the most-used stop in the product; before this it never showed
    // that anything was happening.
    let release!: () => void;
    runActions.cancelRun.mockImplementationOnce(() => new Promise((resolve) => {
      release = () => resolve(undefined);
    }));
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);

    await act(async () => { (toggleProps.current!.onStop as () => void)(); });
    expect(toggleProps.current!.actionPending).toBe('stop');

    await act(async () => { release(); });
    expect(toggleProps.current!.actionPending).toBeNull();
    expect(toggleProps.current!.actionFailed).toBe(false);
  });

  it('marks the pill and raises a TRANSLATED toast when its action fails', async () => {
    runActions.cancelRun.mockRejectedValueOnce(new Error('backend refused'));
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const toasts: string[] = [];
    const onToast = (e: Event) => toasts.push(String((e as CustomEvent).detail?.message ?? ''));
    window.addEventListener('workflowToast', onToast);
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);

    try {
      await act(async () => { (toggleProps.current!.onStop as () => void)(); });
    } finally {
      window.removeEventListener('workflowToast', onToast);
      errors.mockRestore();
    }

    expect(toggleProps.current!.actionFailed).toBe(true);
    // Through next-intl, not a hardcoded English sentence.
    expect(toasts).toEqual(['workflow.runAction.failed']);
  });

  it('hides the pill actions from a VIEWER, like every other run control', () => {
    orgRole.canMutate = false;
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);

    expect(toggleProps.current!.onStop).toBeUndefined();
    expect(toggleProps.current!.onCancel).toBeUndefined();
    expect(toggleProps.current!.onReactivate).toBeUndefined();
  });

  it('offers them to a member', () => {
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);

    expect(toggleProps.current!.onStop).toBeTypeOf('function');
    expect(toggleProps.current!.onCancel).toBeTypeOf('function');
    expect(toggleProps.current!.onReactivate).toBeTypeOf('function');
  });

  it('does not stop the spinner of a run it has since rebound to', async () => {
    // A late settle must not clear the CURRENT run's pending: the spinner would
    // stop and the stop re-enable while that run's own request is in flight.
    let releaseFirst!: () => void;
    // BOTH calls hang: the second must still be in flight when the first settles,
    // or the assertion cannot tell a run-keyed clear from an unconditional one.
    runActions.cancelRun
      .mockImplementationOnce(() => new Promise((resolve) => { releaseFirst = () => resolve(undefined); }))
      .mockImplementationOnce(() => new Promise(() => { /* never settles */ }));
    const view = render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);
    await act(async () => { (toggleProps.current!.onStop as () => void)(); });
    expect(toggleProps.current!.actionPending).toBe('stop');

    view.rerender(<WorkflowRunCanvas workflowId="wf-1" runId="run-2" />);
    await act(async () => { (toggleProps.current!.onStop as () => void)(); });
    expect(toggleProps.current!.actionPending).toBe('stop');

    await act(async () => { releaseFirst(); });

    // run-1's promise settled; run-2's has not.
    expect(toggleProps.current!.actionPending).toBe('stop');
  });

  it('does not carry the pill failure onto another run the canvas rebinds to', async () => {
    // Same rule as the hook: a failure describes ONE run. Rebinding the canvas
    // must not leave a healthy run wearing the previous one's red ring.
    runActions.cancelRun.mockRejectedValueOnce(new Error('backend refused'));
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const view = render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);
    await act(async () => { (toggleProps.current!.onStop as () => void)(); });
    expect(toggleProps.current!.actionFailed).toBe(true);

    view.rerender(<WorkflowRunCanvas workflowId="wf-1" runId="run-2" />);

    expect(toggleProps.current!.actionFailed).toBe(false);
    errors.mockRestore();
  });

  it('reports rather than swallows a stop pressed with no run bound', async () => {
    // This used to return in silence: the click did nothing and said nothing.
    mode.runId = null;
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const toasts: string[] = [];
    const onToast = (e: Event) => toasts.push(String((e as CustomEvent).detail?.message ?? ''));
    window.addEventListener('workflowToast', onToast);
    render(<WorkflowRunCanvas workflowId="wf-1" />);

    try {
      await act(async () => { (toggleProps.current!.onStop as () => void)(); });
    } finally {
      window.removeEventListener('workflowToast', onToast);
      errors.mockRestore();
    }

    expect(toasts).toHaveLength(1);
    expect(toggleProps.current!.actionFailed).toBe(true);
  });
});

describe('WorkflowRunCanvas - run action acknowledgement', () => {
  it('claims a request for its own workflow, so the caller does not call REST behind it', () => {
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);
    expect(requestRunAction({ action: 'stop', workflowId: 'wf-1', runId: 'run-1' }).handled).toBe(true);
  });

  it('actually performs the three actions it claims', () => {
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);

    requestRunAction({ action: 'stop', workflowId: 'wf-1', runId: 'run-1' });
    requestRunAction({ action: 'cancel', workflowId: 'wf-1', runId: 'run-1' });
    requestRunAction({ action: 'reactivate', workflowId: 'wf-1', runId: 'run-1' });

    expect(runActions.cancelRun).toHaveBeenCalledWith('run-1');
    expect(runActions.hardCancelRun).toHaveBeenCalledWith('run-1');
    expect(runActions.reactivateRun).toHaveBeenCalledWith('run-1');
  });

  it('claims but refuses in preview - a declined action must never be retried over REST', () => {
    mode.isPreviewOnly = true;
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);

    expect(requestRunAction({ action: 'stop', workflowId: 'wf-1', runId: 'run-1' }).handled).toBe(true);
    expect(runActions.cancelRun).not.toHaveBeenCalled();
  });

  it('acts on the run the REQUEST names, not the one it is bound to', () => {
    // The panel binds the run the user just picked and only then asks the page to
    // rebind this canvas. In that window the canvas is still on the previous run,
    // and acting on it would stop a different run from the bar that was clicked.
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);

    requestRunAction({ action: 'stop', workflowId: 'wf-1', runId: 'run-JUST-PICKED' });

    expect(runActions.cancelRun).toHaveBeenCalledWith('run-JUST-PICKED');
  });

  it('falls back to its own run when the request names none', () => {
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);

    requestRunAction({ action: 'stop', workflowId: 'wf-1' });

    expect(runActions.cancelRun).toHaveBeenCalledWith('run-1');
  });

  it('leaves a request addressed to another workflow unclaimed', () => {
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);

    expect(requestRunAction({ action: 'stop', workflowId: 'wf-OTHER', runId: 'run-9' }).handled).toBe(false);
    expect(runActions.cancelRun).not.toHaveBeenCalled();
  });

  it('only claims actions addressed to its own side-panel surface', () => {
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" surfaceId="surface-a" />);

    expect(requestRunAction({
      action: 'stop',
      workflowId: 'wf-1',
      runId: 'run-1',
      surfaceId: 'surface-b',
    }).handled).toBe(false);
    expect(requestRunAction({
      action: 'stop',
      workflowId: 'wf-1',
      runId: 'run-1',
      surfaceId: 'surface-a',
    }).handled).toBe(true);
    expect(runActions.cancelRun).toHaveBeenCalledTimes(1);
  });

  it('hands its promise back, so the caller can wait for the work and see it fail', async () => {
    runActions.cancelRun.mockRejectedValueOnce(new Error('backend refused'));
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);

    const claim = requestRunAction({ action: 'stop', workflowId: 'wf-1', runId: 'run-1' });

    expect(claim.result).toBeInstanceOf(Promise);
    await expect(claim.result).rejects.toThrow('backend refused');
  });

  it('claims a run bound only through the mode context, which is the one it publishes', () => {
    // Publish and claim must agree on the same id, or an action on a
    // context-bound run silently took the REST path instead of the run manager.
    render(<WorkflowRunCanvas workflowId="wf-1" />);

    expect(requestRunAction({ action: 'stop', workflowId: 'wf-1' }).handled).toBe(true);
    expect(runActions.cancelRun).toHaveBeenCalledWith('run-1');
  });

  it('does NOT claim a request it could not carry out, so the caller falls back', async () => {
    // A canvas with no run bound used to claim and then return in silence: the
    // click did nothing, said nothing, and the fallback was suppressed. Exactly
    // the failure this whole protocol exists to end.
    mode.runId = null;
    render(<WorkflowRunCanvas workflowId="wf-1" />);

    const claim = requestRunAction({ action: 'stop', workflowId: 'wf-1' });

    expect(claim.handled).toBe(false);
    expect(runActions.cancelRun).not.toHaveBeenCalled();
  });

  it('is taken ONCE when two canvases of the same workflow are mounted', () => {
    // A self-referencing sub-workflow mounts two. Two identical REST calls for
    // one click is not what the user asked for.
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);
    render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);

    requestRunAction({ action: 'stop', workflowId: 'wf-1', runId: 'run-1' });

    expect(runActions.cancelRun).toHaveBeenCalledTimes(1);
  });

  it('stops claiming once it unmounts, so the caller takes over', () => {
    const view = render(<WorkflowRunCanvas workflowId="wf-1" runId="run-1" />);
    expect(requestRunAction({ action: 'stop', workflowId: 'wf-1' }).handled).toBe(true);

    view.unmount();

    expect(requestRunAction({ action: 'stop', workflowId: 'wf-1' }).handled).toBe(false);
  });
});

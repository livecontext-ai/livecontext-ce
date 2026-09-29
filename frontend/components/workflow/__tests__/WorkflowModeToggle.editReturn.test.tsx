/**
 * @vitest-environment jsdom
 *
 * Leaving run mode has to work from all three ways a run can be bound, and one of
 * them used to dead-end:
 *
 *  - a /run/ URL (classic),
 *  - an in-place binding with no /run/ URL (agent-launched run),
 *  - an in-place binding WHILE on a /run/ URL - new with the run history in the
 *    side panel, since picking a sibling run rebinds the canvas without
 *    navigating.
 *
 * The third case latches the mode provider's "programmatic" flag, which makes it
 * ignore pathname changes from then on. Pushing the edit URL and trusting the URL
 * effect therefore left the canvas in run mode with the toggle snapping back to
 * Run. Clearing the binding is what actually returns to edit; the navigation only
 * cleans up the URL.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

const push = vi.hoisted(() => vi.fn());
const setRunId = vi.hoisted(() => vi.fn());
const setViewingEpoch = vi.hoisted(() => vi.fn());
const pathnameRef = vi.hoisted(() => ({ value: '/app/workflow/wf-1/run/run-1' }));
const modeRef = vi.hoisted(() => ({ value: 'run' as 'run' | 'edit' }));
const isEmbeddedRef = vi.hoisted(() => ({ value: false }));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push }),
  usePathname: () => pathnameRef.value,
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getLatestWorkflowRun: vi.fn() } }));
vi.mock('@/components/Toast', () => ({ useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }) }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({
    runId: 'run-1',
    setRunId,
    viewingEpoch: null,
    setViewingEpoch,
    pinnedVersion: null,
  }),
}));
vi.mock('@/lib/workflow/canvasEmbedding', () => ({
  isEmbeddedWorkflowCanvas: () => isEmbeddedRef.value,
}));
vi.mock('@/components/workflow/run-panel/RunSummaryBar', () => ({ RunSummaryBar: () => null }));

// jsdom has no ResizeObserver; the toggle measures its container with one.
class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = NoopResizeObserver;

import { WorkflowModeToggle } from '@/components/workflow/WorkflowModeToggle';

/** The Edit control is icon-only; its intent lives in the title. */
const clickEdit = () => fireEvent.click(screen.getByTitle('workflow.mode.edit'));

beforeEach(() => {
  pathnameRef.value = '/app/workflow/wf-1/run/run-1';
  modeRef.value = 'run';
  isEmbeddedRef.value = false;
});
afterEach(() => {
  push.mockReset();
  setRunId.mockReset();
  setViewingEpoch.mockReset();
  cleanup();
});

describe('WorkflowModeToggle - returning to edit mode', () => {
  it('clears the run binding AND leaves the /run/ URL', () => {
    render(<WorkflowModeToggle workflowId="wf-1" mode={modeRef.value} />);

    clickEdit();

    // Both, in that order: the push alone is ignored once a run was bound in
    // place, and clearing alone would leave a /run/ URL that re-binds on reload.
    expect(setRunId).toHaveBeenCalledWith(null);
    expect(push).toHaveBeenCalledWith('/app/workflow/wf-1');
  });

  it('clears the binding without navigating when there is no /run/ URL', () => {
    // Agent-launched run: the canvas shows run mode over the edit URL, so a push
    // would be a no-op.
    pathnameRef.value = '/app/workflow/wf-1';
    render(<WorkflowModeToggle workflowId="wf-1" mode={modeRef.value} />);

    clickEdit();

    expect(setRunId).toHaveBeenCalledWith(null);
    expect(push).not.toHaveBeenCalled();
  });

  it('never navigates an embedded canvas out of its host page', () => {
    // The application panel hosts the canvas; pushing would take the whole page
    // to the workflow editor.
    isEmbeddedRef.value = true;
    render(<WorkflowModeToggle workflowId="wf-1" mode={modeRef.value} />);

    clickEdit();

    expect(setRunId).toHaveBeenCalledWith(null);
    expect(push).not.toHaveBeenCalled();
  });

  it('does nothing when already in edit mode', () => {
    modeRef.value = 'edit';
    render(<WorkflowModeToggle workflowId="wf-1" mode={modeRef.value} />);

    clickEdit();

    expect(setRunId).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });
});

describe('WorkflowModeToggle - marks its own clicks for the workflow page', () => {
  // The page keeps the panel agent's chat in front only for a TOGGLE click; it knows one by this mark.
  it('marks an Edit click with the edit target (null)', async () => {
    const { consumeUserModeToggle } = await import('@/lib/workflow/workflowPanelChat');
    render(<WorkflowModeToggle workflowId="wf-1" mode={modeRef.value} />);

    clickEdit();

    expect(consumeUserModeToggle('wf-1', null)).toBe(true);
  });

  it('marks a Run click with the run it is about to show, and leaves no mark when that run is already shown', async () => {
    const { orchestratorApi } = await import('@/lib/api');
    const { consumeUserModeToggle } = await import('@/lib/workflow/workflowPanelChat');
    const latest = vi.mocked(orchestratorApi.getLatestWorkflowRun);
    const clickRun = async () => {
      fireEvent.click(screen.getByTitle('workflow.mode.run'));
      await vi.waitFor(() => expect(latest).toHaveBeenCalled());
      await Promise.resolve();
    };

    latest.mockResolvedValueOnce({ runId: 'run-2' } as never);
    const first = render(<WorkflowModeToggle workflowId="wf-1" mode="run" />);
    await clickRun();
    await vi.waitFor(() => expect(push).toHaveBeenCalledWith('/app/workflow/wf-1/run/run-2'));
    expect(consumeUserModeToggle('wf-1', 'run-2')).toBe(true);
    first.unmount();
    latest.mockReset();

    // Re-click Run on the run already on screen (the context shows run-1): no change will follow,
    // so a mark would linger and mislabel the next, unrelated run change as a toggle.
    latest.mockResolvedValueOnce({ runId: 'run-1' } as never);
    render(<WorkflowModeToggle workflowId="wf-1" mode="run" />);
    await clickRun();
    await vi.waitFor(() => expect(push).toHaveBeenCalledWith('/app/workflow/wf-1/run/run-1'));
    expect(consumeUserModeToggle('wf-1', 'run-1')).toBe(false);
  });
});

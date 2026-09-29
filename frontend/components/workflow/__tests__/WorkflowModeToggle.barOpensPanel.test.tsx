/**
 * @vitest-environment jsdom
 *
 * The canvas run bar used to carry a dedicated panel-toggle icon next to the
 * chips. On a narrow canvas that glyph competed for room with the very chips it
 * sat beside, and it was the only pixel that opened the run - clicking the bar
 * itself did nothing.
 *
 * The bar IS the control now, so what has to hold is:
 *  - clicking anywhere on it opens the CURRENT run in the panel;
 *  - the controls inside it keep their own meaning - the version chip still
 *    opens the run HISTORY, and stop/cancel still stops the run - without ALSO
 *    opening the run level behind them;
 *  - it is reachable from the keyboard, since it is no longer a real <button>.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';

const onStop = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({
  useTranslations: () => (k: string) => k,
  useLocale: () => 'en',
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/app/workflow/wf-1/run/run-1',
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getLatestWorkflowRun: vi.fn() } }));
vi.mock('@/components/Toast', () => ({ useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }) }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ runId: 'run-1', setRunId: vi.fn(), viewingEpoch: null, setViewingEpoch: vi.fn() }),
}));
vi.mock('@/lib/workflow/canvasEmbedding', () => ({ isEmbeddedWorkflowCanvas: () => false }));

// jsdom has no ResizeObserver; the toggle measures its container with one and
// the chip track watches itself with another.
class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = NoopResizeObserver;

import { WorkflowModeToggle } from '@/components/workflow/WorkflowModeToggle';
import { OPEN_RUN_PANEL_EVENT } from '@/components/workflow/run-panel/runPanelBus';

/** Levels requested on the run panel bus, in click order. */
let opened: Array<string | undefined>;
const record = (e: Event) => { opened.push((e as CustomEvent).detail?.view); };

const RUN = { runId: 'run-1', id: 'run-1', status: 'RUNNING', planVersion: 3 } as never;

function renderToggle(run: unknown = RUN) {
  return render(
    <WorkflowModeToggle
      workflowId="wf-1"
      mode="run"
      currentRunInfo={run as never}
      epochCount={1}
      onStop={onStop}
    />,
  );
}

/** The clickable row of the pill (the surface around it also holds the epoch navigator). */
const bar = () => document.querySelector('[data-run-pill-row]') as HTMLElement;

beforeEach(() => {
  opened = [];
  window.addEventListener(OPEN_RUN_PANEL_EVENT, record);
});
afterEach(() => {
  window.removeEventListener(OPEN_RUN_PANEL_EVENT, record);
  onStop.mockReset();
  cleanup();
});

describe('WorkflowModeToggle - the run bar is the way into the panel', () => {
  it('opens the current run when the bar itself is clicked', () => {
    renderToggle();
    fireEvent.click(bar());
    expect(opened).toEqual(['run']);
  });

  it('opens the current run from the keyboard', () => {
    // The bar is a div with role=button: without this it is unreachable for
    // anyone not using a pointer.
    renderToggle();
    fireEvent.keyDown(bar(), { key: 'Enter' });
    fireEvent.keyDown(bar(), { key: ' ' });
    expect(opened).toEqual(['run', 'run']);
  });

  it('ignores keys that are not Enter or Space', () => {
    renderToggle();
    fireEvent.keyDown(bar(), { key: 'a' });
    fireEvent.keyDown(bar(), { key: 'Tab' });
    expect(opened).toEqual([]);
  });

  it('opens the HISTORY - and only the history - from the version chip', () => {
    // The chip sits inside the bar, so without stopPropagation the click would
    // ALSO bubble and immediately drag the panel to the run level.
    renderToggle();
    fireEvent.click(document.querySelector('[data-run-version-chip]') as HTMLElement);
    expect(opened).toEqual(['history']);
  });

  it('stops the run without opening the panel', () => {
    renderToggle();
    fireEvent.click(document.querySelector('[data-run-action="stop"]') as HTMLElement);
    expect(onStop).toHaveBeenCalledTimes(1);
    expect(opened).toEqual([]);
  });

  it('leaves the inner controls focusable and in reading order', () => {
    // Turning the bar into the click target must not bury the two controls it
    // contains: both stay real <button>s, so Tab reaches them.
    renderToggle();
    const focusables = Array.from(
      bar().querySelectorAll<HTMLElement>('button:not([disabled]), [tabindex]:not([tabindex="-1"])'),
    );
    expect(focusables.map(el => el.getAttribute('data-run-version-chip') !== null
      ? 'version'
      : el.getAttribute('data-run-action'))).toEqual(['version', 'stop']);
    focusables.forEach(el => {
      el.focus();
      expect(document.activeElement, `${el.outerHTML.slice(0, 60)} must be focusable`).toBe(el);
    });
  });

  it('activates the version chip from the keyboard WITHOUT also opening the run', () => {
    // Enter/Space bubble - the inner buttons only stop the CLICK. Unguarded, the
    // bar's key handler fired too and the panel landed on the run level, one
    // frame after the history the user actually asked for.
    renderToggle();
    const chip = document.querySelector('[data-run-version-chip]') as HTMLElement;
    chip.focus();
    fireEvent.keyDown(chip, { key: 'Enter' });
    fireEvent.keyDown(chip, { key: ' ' });
    // jsdom does not synthesise the button's own click from a key event; what
    // matters here is that the BAR stayed out of it.
    expect(opened).toEqual([]);
    fireEvent.click(chip);
    expect(opened).toEqual(['history']);
  });

  it('lets Space reach the stop button instead of being swallowed by the bar', () => {
    // The bar preventDefault()s Space to stop the page scrolling. Applied to a
    // key pressed ON the stop button, that also cancels the browser's own
    // "activate on Space" - the stop button would look focusable and do nothing.
    renderToggle();
    const stop = document.querySelector('[data-run-action="stop"]') as HTMLElement;
    stop.focus();
    const space = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true });
    stop.dispatchEvent(space);

    expect(space.defaultPrevented, 'the bar must not preventDefault a key aimed at the stop button').toBe(false);
    expect(opened).toEqual([]);
  });

  it('keeps the cancel dialog from opening the panel behind itself', () => {
    // The dialog is a portal, but React bubbles synthetic events through the
    // REACT tree: dismissing it used to reach the bar's handler and open the run
    // panel behind a modal the user was busy closing.
    render(
      <WorkflowModeToggle
        workflowId="wf-1"
        mode="run"
        currentRunInfo={{ runId: 'run-1', status: 'WAITING_TRIGGER', planVersion: 3 } as never}
        onCancel={vi.fn()}
      />,
    );
    fireEvent.click(document.querySelector('[data-run-action="cancel"]') as HTMLElement);
    const backdrop = document.querySelector('[data-run-cancel-backdrop]') as HTMLElement;
    expect(backdrop).toBeTruthy();

    fireEvent.click(backdrop);

    expect(opened).toEqual([]);
    expect(document.querySelector('[data-run-cancel-backdrop]')).toBeNull();
  });

  it('no longer renders a separate panel-toggle icon', () => {
    // The icon is gone on purpose - the bar replaced it. A stray one would be a
    // second, redundant click target inside a pill that is short on room.
    renderToggle();
    expect(bar().querySelector('button[title="workflow.runInfo.openInPanel"]')).toBeNull();
    expect(bar().getAttribute('title')).toBe('workflow.runInfo.openInPanel');
  });

  it('renders no bar at all - and nothing to click - without run info', () => {
    render(<WorkflowModeToggle workflowId="wf-1" mode="run" />);
    expect(document.querySelector('[data-run-info-panel]')).toBeNull();
    // The history fallback chip is the only route left, and it must not be
    // swallowed by a bar-level handler that no longer exists.
    fireEvent.click(document.querySelector('[data-run-history-fallback]') as HTMLElement);
    expect(opened).toEqual(['history']);
  });
});
describe('WorkflowModeToggle - what the pill forwards to the run control', () => {
  // The canvas pill is the most-used stop in the product, and the only thing
  // making it show that anything is happening is this hand-off. Dropping either
  // prop is invisible in every other suite.
  it('forwards the action in flight, so the pill spins', () => {
    render(
      <WorkflowModeToggle
        workflowId="wf-1"
        mode="run"
        currentRunInfo={RUN}
        epochCount={1}
        onStop={onStop}
        actionPending="stop"
      />,
    );
    expect(document.querySelector('[data-run-action="stop"]')?.getAttribute('aria-busy')).toBe('true');
  });

  it('forwards the failure, so the pill says the click did not work', () => {
    render(
      <WorkflowModeToggle
        workflowId="wf-1"
        mode="run"
        currentRunInfo={RUN}
        epochCount={1}
        onStop={onStop}
        actionFailed
      />,
    );
    expect(document.querySelector('[data-run-action="stop"]')?.getAttribute('data-run-action-failed')).toBe('true');
  });

  it('marks neither on a resting control', () => {
    renderToggle();
    const button = document.querySelector('[data-run-action="stop"]');
    expect(button?.getAttribute('aria-busy')).toBeNull();
    expect(button?.getAttribute('data-run-action-failed')).toBeNull();
  });
});

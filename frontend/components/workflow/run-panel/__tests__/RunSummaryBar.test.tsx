/**
 * @vitest-environment jsdom
 *
 * The run identity bar is the ONE component behind both run surfaces: the
 * compact pill on the canvas and the header of the side-panel Run tab. Whatever
 * it decides - which action a run is offered, whether the history is reachable -
 * is decided for both at once, so these are the rules worth pinning:
 *
 *  - a live run can be stopped, a resting reusable run cancelled, a finished one
 *    reactivated, and never two of those at the same time;
 *  - the version chip is the canvas's route into the run history, so it must
 *    survive a run whose version is unknown.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({
  // Echo the key, but interpolate the values: a chip whose whole point is the
  // number it carries must be asserted with that number.
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}(${Object.values(values).join(',')})` : key,
  useLocale: () => 'en',
}));

import { canvasChromeChipRadiusClass } from '@/components/ui/canvas-chrome';

// jsdom has neither ResizeObserver nor scroll metrics; the chip track watches
// itself with one to decide whether an edge still hides a chip.
class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = NoopResizeObserver;

import { RunSummaryBar } from '@/components/workflow/run-panel/RunSummaryBar';

const NOOP = () => undefined;

function renderBar(status: string, props: Record<string, unknown> = {}) {
  return render(
    <RunSummaryBar
      currentRunInfo={{ runId: 'run-1', status, planVersion: 3 } as never}
      pinnedVersion={null}
      epochCount={1}
      selectedEpoch={null}
      onStop={NOOP}
      onCancel={NOOP}
      onReactivate={NOOP}
      {...props}
    />,
  );
}

/** The action button carries its intent in the title (an icon-only control). */
const actionTitles = () =>
  Array.from(document.querySelectorAll('button[title]')).map(b => b.getAttribute('title'));

afterEach(cleanup);

describe('RunSummaryBar - which action a run is offered', () => {
  it('offers stop while the run is executing', () => {
    renderBar('RUNNING');
    expect(actionTitles()).toContain('workflow.mode.stopWorkflow');
    expect(actionTitles()).not.toContain('workflow.reactivateRun.title');
  });

  it('offers cancel - not stop - for a reusable run resting between fires', () => {
    // WAITING_TRIGGER is alive but idle: stopping it is not the same act as
    // cancelling it, and the confirm exists because cancelling ends the run.
    renderBar('WAITING_TRIGGER');
    expect(actionTitles()).toContain('workflow.cancelRun.title');
    expect(actionTitles()).not.toContain('workflow.mode.stopWorkflow');
  });

  it('offers reactivate on every terminal status, including SKIPPED', () => {
    // The dispatcher refuses to fire into a terminal run, so the only way back is
    // this button. `skipped` was missing from the terminal set once, and the run
    // was then offered a stop the backend would refuse instead.
    for (const status of ['COMPLETED', 'FAILED', 'PARTIAL_SUCCESS', 'CANCELLED', 'TIMEOUT', 'SKIPPED']) {
      cleanup();
      renderBar(status);
      expect(actionTitles(), `${status} should be reactivatable`).toContain('workflow.reactivateRun.title');
      expect(actionTitles(), `${status} is finished - nothing to stop`).not.toContain('workflow.mode.stopWorkflow');
    }
  });

  it('offers nothing at all when the surface passes no handlers (preview)', () => {
    renderBar('RUNNING', { onStop: undefined, onCancel: undefined, onReactivate: undefined });
    expect(actionTitles()).not.toContain('workflow.mode.stopWorkflow');
    expect(actionTitles()).not.toContain('workflow.reactivateRun.title');
  });
});

describe('RunSummaryBar - reaching the run history', () => {
  it('renders a pending badge while the run status is still loading', () => {
    render(<RunSummaryBar currentRunInfo={{ runId: 'run-1' }} />);

    expect(screen.getByTestId('run-status-badge').textContent).toBe('status.pending');
  });

  it('makes the version chip the way in', () => {
    const onVersionClick = vi.fn();
    renderBar('RUNNING', { onVersionClick });
    const chip = document.querySelector('[data-run-version-chip]') as HTMLElement;
    expect(chip.textContent).toContain('v3');
    fireEvent.click(chip);
    expect(onVersionClick).toHaveBeenCalled();
  });

  it('keeps a way in when the run has no version to show', () => {
    // An older run, or one whose info has not loaded: without this the chip is
    // absent and the canvas has NO route into the history at all.
    const onVersionClick = vi.fn();
    renderBar('RUNNING', { onVersionClick, currentRunInfo: { runId: 'run-1', status: 'RUNNING' } });
    const chip = document.querySelector('[data-run-version-chip]') as HTMLElement;
    expect(chip).toBeTruthy();
    fireEvent.click(chip);
    expect(onVersionClick).toHaveBeenCalled();
  });

  it('shows a plain version with no way in when the surface forbids history', () => {
    // Marketplace preview / embedded canvas: the run is frozen, so the version is
    // information, not navigation.
    renderBar('RUNNING');
    expect(document.querySelector('[data-run-version-chip]')).toBeNull();
    expect(screen.getByText('v3')).toBeTruthy();
  });
});

describe('RunSummaryBar - the run info labels are square, like the rest of the chrome', () => {
  it('shapes the status badge with the shared chip radius, and gives it a stable handle', () => {
    // The badge used to be the only `div.rounded-full` on the canvas, which is
    // what e2e keyed on. The shape is styling; the testid is the identity.
    renderBar('RUNNING');
    const badge = screen.getByTestId('run-status-badge');
    expect(badge.className).toContain(canvasChromeChipRadiusClass);
    expect(badge.className).not.toContain('rounded-full');
  });

  it('leaves nothing round in the bar itself, on either surface size', () => {
    for (const size of ['compact', 'panel'] as const) {
      cleanup();
      // Every optional chip at once: action button, version, epoch, step-by-step.
      const { container } = renderBar('RUNNING', {
        size,
        isStepByStep: true,
        pinnedVersion: 3,
        onVersionClick: () => undefined,
      });
      // The bar is the first child; the cancel modal (portalled, and only shown
      // on confirm) keeps its own circular icon badge by design.
      expect(container.innerHTML, `${size} bar still has a pill`).not.toContain('rounded-full');
    }
  });

  it('squares the action button too - it sits inline with the labels', () => {
    renderBar('RUNNING');
    const stop = screen.getByTitle('workflow.mode.stopWorkflow');
    expect(stop.className).toContain(canvasChromeChipRadiusClass);
  });

  it('squares the scroll arrows too - they sit in the same row as the chips', () => {
    renderBar('RUNNING');
    const track = document.querySelector('[data-run-summary-chips]') as HTMLElement;
    Object.defineProperty(track, 'scrollWidth', { value: 500, configurable: true });
    Object.defineProperty(track, 'clientWidth', { value: 200, configurable: true });
    track.scrollLeft = 100;
    fireEvent.scroll(track);
    for (const side of ['left', 'right']) {
      const arrow = document.querySelector(`[data-run-summary-scroll="${side}"]`) as HTMLElement;
      expect(arrow.className, `${side} arrow`).toContain(canvasChromeChipRadiusClass);
      expect(arrow.className, `${side} arrow`).not.toContain('rounded-full');
    }
  });
});

describe('RunSummaryBar - which epoch the bar says you are on', () => {
  it('says "All epochs" in words, with the number of fires, when no epoch is picked', () => {
    // A bare number can only be read as "epoch N": showing the epoch COUNT the
    // same way made the default cumulative view look like a specific fire, and
    // contradicted the panel next to it. The count still has to survive the
    // rewording, it is the only place the bar says how often the run fired.
    renderBar('COMPLETED', { epochCount: 3, selectedEpoch: null });
    const chip = document.querySelector('[data-run-epoch-chip]') as HTMLElement;
    expect(chip.getAttribute('data-all-epochs')).toBe('true');
    expect(chip.textContent).toBe('workflow.runSteps.allEpochsCount(3)');
  });

  it('shows the epoch number once one is picked', () => {
    renderBar('COMPLETED', { epochCount: 3, selectedEpoch: 2 });
    const chip = document.querySelector('[data-run-epoch-chip]') as HTMLElement;
    expect(chip.getAttribute('data-all-epochs')).toBeNull();
    expect(chip.textContent).toContain('2');
  });

  it('shows no epoch chip at all before the run has ever fired', () => {
    renderBar('WAITING_TRIGGER', { epochCount: 0, selectedEpoch: null });
    expect(document.querySelector('[data-run-epoch-chip]')).toBeNull();
  });

});

describe('RunSummaryBar - where the action button sits', () => {
  /** The chip track and the action button are siblings; DOM order IS the layout. */
  const trackThenAction = () => {
    const track = document.querySelector('[data-run-summary-chips]')!;
    const action = document.querySelector('[data-run-action]')!;
    // Node.DOCUMENT_POSITION_FOLLOWING = the action comes AFTER the track.
    return !!(track.compareDocumentPosition(action) & Node.DOCUMENT_POSITION_FOLLOWING);
  };

  it('puts stop at the far right, after every chip', () => {
    // It used to be leftmost, where it was the first thing a click landed on and
    // the first thing an overflowing bar pushed around.
    renderBar('RUNNING');
    expect(document.querySelector('[data-run-action]')?.getAttribute('data-run-action')).toBe('stop');
    expect(trackThenAction(), 'stop must render after the chip track').toBe(true);
  });

  it('puts cancel at the far right too', () => {
    renderBar('WAITING_TRIGGER');
    expect(document.querySelector('[data-run-action]')?.getAttribute('data-run-action')).toBe('cancel');
    expect(trackThenAction()).toBe(true);
  });

  it('puts reactivate at the far right too', () => {
    renderBar('COMPLETED');
    expect(document.querySelector('[data-run-action]')?.getAttribute('data-run-action')).toBe('reactivate');
    expect(trackThenAction()).toBe(true);
  });

  it('keeps the action out of the scrolling track so it can never scroll away', () => {
    renderBar('RUNNING');
    const track = document.querySelector('[data-run-summary-chips]')!;
    expect(track.querySelector('[data-run-action]')).toBeNull();
  });
});

describe('RunSummaryBar - staying readable when there is no room', () => {
  it('keeps every chip mounted instead of hiding them at breakpoints', () => {
    // Chips used to be dropped below sm/md on the canvas pill. The version chip
    // is the ONLY route into the run history, so hiding it stranded the user on
    // a narrow canvas - with nothing on screen saying it existed. They scroll now.
    renderBar('RUNNING', {
      onVersionClick: NOOP,
      isStepByStep: true,
      currentRunInfo: { runId: 'run-1', status: 'RUNNING', planVersion: 3, startedAt: '2026-01-01T00:00:00Z' },
    });
    const track = document.querySelector('[data-run-summary-chips]') as HTMLElement;
    expect(track).toBeTruthy();
    expect(track.className).toContain('overflow-x-auto');
    expect(track.querySelector('[data-run-version-chip]')).toBeTruthy();
    expect(track.textContent).toContain('workflow.mode.stepByStep');
    // No chip may carry a breakpoint-hiding class - that is the bug being fixed.
    const hidden = Array.from(track.querySelectorAll('*'))
      .filter(el => el.className && typeof el.className === 'string' && el.className.split(/\s+/).includes('hidden'));
    expect(hidden, 'no chip may be hidden by a breakpoint').toEqual([]);
  });

  it('shows no scroll arrow while everything fits', () => {
    // jsdom reports scrollWidth === clientWidth === 0, i.e. "nothing cut off".
    renderBar('RUNNING');
    expect(document.querySelector('[data-run-summary-scroll]')).toBeNull();
  });

  it('grows an arrow on the side that still hides a chip', () => {
    renderBar('RUNNING');
    const track = document.querySelector('[data-run-summary-chips]') as HTMLElement;
    // Simulate an overflowing, mid-scrolled track: content cut off on BOTH sides.
    Object.defineProperty(track, 'scrollWidth', { value: 500, configurable: true });
    Object.defineProperty(track, 'clientWidth', { value: 200, configurable: true });
    track.scrollLeft = 100;
    fireEvent.scroll(track);

    expect(document.querySelector('[data-run-summary-scroll="left"]')).toBeTruthy();
    expect(document.querySelector('[data-run-summary-scroll="right"]')).toBeTruthy();
  });

  it('scrolls the track when an arrow is pressed, without bubbling the click', () => {
    const onBarClick = vi.fn();
    render(
      <div onClick={onBarClick}>
        <RunSummaryBar currentRunInfo={{ runId: 'run-1', status: 'RUNNING', planVersion: 3 } as never} />
      </div>,
    );
    const track = document.querySelector('[data-run-summary-chips]') as HTMLElement;
    Object.defineProperty(track, 'scrollWidth', { value: 500, configurable: true });
    Object.defineProperty(track, 'clientWidth', { value: 200, configurable: true });
    track.scrollBy = vi.fn();
    track.scrollLeft = 100;
    fireEvent.scroll(track);

    fireEvent.click(document.querySelector('[data-run-summary-scroll="right"]') as HTMLElement);

    expect(track.scrollBy).toHaveBeenCalledWith({ left: 120, behavior: 'smooth' });
    // The bar around it opens the run panel: nudging the chips must not.
    expect(onBarClick).not.toHaveBeenCalled();
  });
});
describe('RunSummaryBar - what it forwards to the shared control', () => {
  // The bar no longer draws the control itself, so the only thing keeping the
  // canvas pill and the Run tab from looking dead while they work is this
  // hand-off. Dropping either prop is invisible everywhere else.
  it('forwards the action in flight, so the control spins', () => {
    render(
      <RunSummaryBar
        currentRunInfo={{ runId: 'run-1', status: 'RUNNING' }}
        onStop={vi.fn()}
        actionPending="stop"
      />,
    );
    const button = screen.getByRole('button', { name: /stopWorkflow/i });
    expect(button.getAttribute('aria-busy')).toBe('true');
  });

  it('forwards the failure, so the control says the click did not work', () => {
    render(
      <RunSummaryBar
        currentRunInfo={{ runId: 'run-1', status: 'RUNNING' }}
        onStop={vi.fn()}
        actionFailed
      />,
    );
    expect(
      screen.getByRole('button', { name: /runAction.failed/i }).getAttribute('data-run-action-failed'),
    ).toBe('true');
  });

  it('forwards neither by default, so a resting control is not marked', () => {
    render(
      <RunSummaryBar currentRunInfo={{ runId: 'run-1', status: 'RUNNING' }} onStop={vi.fn()} />,
    );
    const button = screen.getByRole('button', { name: /stopWorkflow/i });
    expect(button.getAttribute('aria-busy')).toBeNull();
    expect(button.getAttribute('data-run-action-failed')).toBeNull();
  });
});

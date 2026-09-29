/**
 * @vitest-environment jsdom
 *
 * The canvas run pill unfolds an epoch navigator from its epoch chip. It must:
 *  - unfold and fold from the chip, without opening the Run panel (the rest of the
 *    pill still does);
 *  - move the canvas epoch as a USER choice (recorded, so the Run panel reopens on it);
 *  - fold back when the canvas moves to another run or leaves run mode;
 *  - leave the pill exactly as it was when the run has no epoch to browse.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render } from '@testing-library/react';

const mode = vi.hoisted(() => ({ runId: 'run-1' as string | null, setViewingEpoch: vi.fn() }));

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
  useWorkflowMode: () => ({ runId: mode.runId, setRunId: vi.fn(), viewingEpoch: null, setViewingEpoch: mode.setViewingEpoch }),
}));
vi.mock('@/lib/workflow/canvasEmbedding', () => ({ isEmbeddedWorkflowCanvas: () => false }));

class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = NoopResizeObserver;

import { WorkflowModeToggle } from '@/components/workflow/WorkflowModeToggle';
import { OPEN_RUN_PANEL_EVENT } from '@/components/workflow/run-panel/runPanelBus';
import { getPickedEpoch, resetEpochSelectionState } from '@/components/workflow/run-panel/useDefaultEpochSelection';
import { VIEWING_EPOCH_EVENT } from '@/lib/workflow/epochEventScope';
import type { EpochTimestamp } from '@/components/workflow/run-panel/runFormatting';
import { EPOCH_NAV_AUTO_OPEN_FROM } from '@/components/workflow/run-panel/RunEpochNavigator';

const RUN = { runId: 'run-1', id: 'run-1', status: 'COMPLETED', planVersion: 3 } as never;
const EPOCHS: EpochTimestamp[] = [
  { epoch: 1, startedAt: '2026-09-27T10:00:00Z', endedAt: '2026-09-27T10:00:05Z', workDurationMs: 2_000, status: 'COMPLETED' },
  { epoch: 2, startedAt: '2026-09-27T10:01:00Z', endedAt: '2026-09-27T10:01:05Z', workDurationMs: 3_000, status: 'FAILED' },
];

let opened: Array<string | undefined>;
const record = (e: Event) => { opened.push((e as CustomEvent).detail?.view); };

function toggle(props: Partial<React.ComponentProps<typeof WorkflowModeToggle>> = {}) {
  return (
    <WorkflowModeToggle
      workflowId="wf-1"
      mode="run"
      currentRunInfo={RUN}
      epochCount={EPOCHS.length}
      epochTimestamps={EPOCHS}
      {...props}
    />
  );
}

const chip = () => document.querySelector('[data-run-epoch-chip]') as HTMLElement;
const navigator = () => document.querySelector('[data-run-epoch-navigator]');

beforeEach(() => {
  opened = [];
  mode.runId = 'run-1';
  mode.setViewingEpoch.mockReset();
  resetEpochSelectionState();
  window.addEventListener(OPEN_RUN_PANEL_EVENT, record);
});
afterEach(() => {
  window.removeEventListener(OPEN_RUN_PANEL_EVENT, record);
  cleanup();
});

describe('WorkflowModeToggle - the epoch navigator in the run pill', () => {
  it('starts folded: the pill looks as it always did', () => {
    render(toggle());

    expect(navigator()).toBeNull();
    expect(chip().getAttribute('aria-expanded')).toBe('false');
  });

  it('unfolds and folds from the epoch chip without opening the Run panel', () => {
    render(toggle());

    fireEvent.click(chip());
    expect(navigator()).not.toBeNull();
    expect(chip().getAttribute('aria-expanded')).toBe('true');

    fireEvent.click(chip());
    expect(navigator()).toBeNull();
    expect(opened).toEqual([]);
  });

  it('still opens the Run panel from the rest of the pill while unfolded', () => {
    render(toggle());
    fireEvent.click(chip());

    fireEvent.click(document.querySelector('[data-run-pill-row]') as HTMLElement);

    expect(opened).toEqual(['run']);
  });

  it('moves the canvas epoch as a user choice, without opening the Run panel', () => {
    render(toggle());
    fireEvent.click(chip());

    fireEvent.click(document.querySelector('[data-epoch-nav="previous"]') as HTMLElement);

    expect(mode.setViewingEpoch).toHaveBeenCalledWith(2);
    // Recorded like a pick in the Run panel, so the panel reopens on the same epoch.
    expect(getPickedEpoch('run-1')).toBe(2);
    expect(opened).toEqual([]);
  });

  it('follows the epoch picked elsewhere (the Run panel) in the chip and the navigator', () => {
    render(toggle());
    fireEvent.click(chip());

    act(() => {
      window.dispatchEvent(new CustomEvent(VIEWING_EPOCH_EVENT, { detail: { epoch: 1, runId: 'run-1' } }));
    });

    expect(chip().textContent).toContain('1');
    expect(document.querySelector('[data-epoch-bar="1"]')!.getAttribute('data-active')).toBe('true');
  });

  it('folds back when the canvas switches to another run', () => {
    const { rerender } = render(toggle());
    fireEvent.click(chip());
    expect(navigator()).not.toBeNull();

    mode.runId = 'run-2';
    rerender(toggle({ currentRunInfo: { runId: 'run-2', id: 'run-2', status: 'COMPLETED', planVersion: 3 } as never }));

    expect(navigator()).toBeNull();
  });

  it('stays folded when the canvas comes back to the run it was unfolded on', () => {
    const { rerender } = render(toggle());
    fireEvent.click(chip());

    mode.runId = 'run-2';
    rerender(toggle({ currentRunInfo: { runId: 'run-2', id: 'run-2', status: 'COMPLETED', planVersion: 3 } as never }));
    mode.runId = 'run-1';
    rerender(toggle());

    expect(navigator()).toBeNull();
  });

  it('folds back when the canvas leaves run mode, and stays folded on return', () => {
    const { rerender } = render(toggle());
    fireEvent.click(chip());

    rerender(toggle({ mode: 'edit' }));
    rerender(toggle({ mode: 'run' }));

    expect(navigator()).toBeNull();
  });

  it('folds back on Escape and hands focus back to the chip', () => {
    render(toggle());
    fireEvent.click(chip());

    fireEvent.keyDown(document.querySelector('[data-epoch-timeline]') as HTMLElement, { key: 'Escape' });

    expect(navigator()).toBeNull();
    expect(document.activeElement).toBe(chip());
  });

  it('points the chip at the navigator it unfolds', () => {
    render(toggle());
    fireEvent.click(chip());

    const controls = chip().getAttribute('aria-controls');
    expect(controls).toBeTruthy();
    expect(navigator()!.id).toBe(controls);
  });

  it('unfolds the navigator beside the "open the panel" row, not inside it', () => {
    // Inside a role=button, assistive tech flattens the widget, and hovering it
    // would light up the row that opens the Run panel.
    render(toggle());
    fireEvent.click(chip());

    const row = document.querySelector('[data-run-pill-row]') as HTMLElement;
    expect(row.getAttribute('role')).toBe('button');
    expect(row.contains(navigator())).toBe(false);
    expect(document.querySelector('[data-run-info-panel]')!.contains(navigator())).toBe(true);
  });

  describe('opening unfolded on a run worth browsing', () => {
    const many = (count: number): EpochTimestamp[] => Array.from({ length: count }, (_, i) => ({
      epoch: i + 1,
      startedAt: '2026-09-27T10:00:00Z',
      endedAt: '2026-09-27T10:00:05Z',
      workDurationMs: 1_000,
      status: 'COMPLETED',
    }));

    it('opens unfolded from EPOCH_NAV_AUTO_OPEN_FROM epochs, folded below', () => {
      const { rerender } = render(toggle({ epochTimestamps: many(EPOCH_NAV_AUTO_OPEN_FROM - 1) }));
      expect(navigator()).toBeNull();

      rerender(toggle({ epochTimestamps: many(EPOCH_NAV_AUTO_OPEN_FROM) }));
      expect(navigator()).not.toBeNull();
      expect(chip().getAttribute('aria-expanded')).toBe('true');
    });

    it('stays folded once the user folds it, even as epochs keep coming', () => {
      const { rerender } = render(toggle({ epochTimestamps: many(6) }));
      fireEvent.click(chip());
      expect(navigator()).toBeNull();

      rerender(toggle({ epochTimestamps: many(7) }));
      expect(navigator()).toBeNull();
    });

    it('opens the next run on its own default again', () => {
      const { rerender } = render(toggle({ epochTimestamps: many(6) }));
      fireEvent.click(chip());
      expect(navigator()).toBeNull();

      mode.runId = 'run-2';
      rerender(toggle({
        epochTimestamps: many(6),
        currentRunInfo: { runId: 'run-2', id: 'run-2', status: 'COMPLETED', planVersion: 3 } as never,
      }));

      expect(navigator()).not.toBeNull();
    });
  });

  it('keeps the chip a plain label when there is no epoch to browse', () => {
    render(toggle({ epochTimestamps: [] }));

    expect(chip().tagName).toBe('SPAN');
    fireEvent.click(chip());
    expect(navigator()).toBeNull();
  });
});

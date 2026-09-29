/**
 * @vitest-environment jsdom
 *
 * Every epoch row carries its OWN status badge.
 *
 * One run accumulates many epochs and the run-level badge can only describe the
 * last one, so a run that shows "completed" can still hide a failed epoch 2. The
 * selector used to render a single blue dot for "open" and nothing at all
 * otherwise, which made a failed epoch indistinguishable from a successful one.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
// Flattened so both the row badge and the tooltip body render inline; radix would
// mount the content only on hover.
vi.mock('@/components/ui/tooltip', () => ({
  Tooltip: ({ children }: any) => <>{children}</>,
  TooltipTrigger: ({ children }: any) => <>{children}</>,
  TooltipContent: ({ children }: any) => <div data-tooltip>{children}</div>,
}));
// react-window virtualizes: render every row so all three epochs are asserted.
vi.mock('react-window', () => ({
  List: ({ rowCount, rowComponent: Row, rowProps }: any) => (
    <div>
      {Array.from({ length: rowCount }).map((_, i) => (
        <Row key={i} index={i} style={{}} {...rowProps} />
      ))}
    </div>
  ),
  useListRef: () => ({ current: null }),
}));

import { EpochSelector } from '@/components/workflow/run-panel/EpochSelector';

// Two settled fires and one still open. The open one carries NO status: the backend
// attaches an outcome only to an epoch it can speak for, and an active epoch's stored
// state is the one written when it opened.
const EPOCHS = [
  { epoch: 1, startedAt: '2026-08-02T09:00:00Z', endedAt: '2026-08-02T09:00:30Z', status: 'COMPLETED' },
  { epoch: 2, startedAt: '2026-08-02T09:01:00Z', endedAt: '2026-08-02T09:01:30Z', status: 'FAILED' },
  { epoch: 3, startedAt: '2026-08-02T09:02:00Z', endedAt: null, status: null },
];

function renderSelector(runStatus: string) {
  return render(
    <EpochSelector
      epochTimestamps={EPOCHS as any}
      selectedEpoch={null}
      onSelectEpoch={() => {}}
      viewMode="list"
      runStatus={runStatus}
    />,
  );
}

/** The badge is an icon, so it is identified by the classes that colour it. */
function badgeOf(epoch: number): string {
  const row = document.querySelector(`[data-epoch-option="${epoch}"]`);
  expect(row).not.toBeNull();
  const svg = row!.querySelector('svg');
  if (svg) return svg.getAttribute('class') || '';
  // Running: an animated dot, not an icon.
  return row!.querySelector('.animate-ping') ? 'running' : '';
}

describe('EpochSelector - per-epoch status badge', () => {
  afterEach(cleanup);

  it('badges each epoch with its own outcome, side by side', () => {
    renderSelector('running');

    expect(badgeOf(1)).toContain('emerald');
    // The whole point: epoch 2 stays red while its neighbours are green, on a run
    // whose own status is "running".
    expect(badgeOf(2)).toContain('red');
    // Epoch 3 is open and the run is executing.
    expect(badgeOf(3)).toBe('running');
  });

  it('drops the live pulse once the run stops executing, keeping the settled verdicts', () => {
    // A stopped run abandons whatever epoch was open: epoch 3 is not executing, so no
    // pulse and no claim about it. The epochs that DID finish keep their own verdict -
    // the run status describes only the epoch it was executing.
    renderSelector('stopped');

    expect(document.querySelectorAll('.animate-ping')).toHaveLength(0);
    expect(badgeOf(3)).toContain('gray');
    expect(badgeOf(2)).toContain('red');
    expect(badgeOf(1)).toContain('emerald');
  });

  it('renders the reserved slot with no badge when an epoch has no outcome yet', () => {
    render(
      <EpochSelector
        epochTimestamps={[{ epoch: 1, startedAt: '2026-08-02T09:00:00Z', endedAt: '2026-08-02T09:00:30Z' }] as any}
        selectedEpoch={null}
        onSelectEpoch={() => {}}
        viewMode="list"
        runStatus="waiting_trigger"
      />,
    );

    const row = document.querySelector('[data-epoch-option="1"]')!;
    expect(row.querySelector('svg')).toBeNull();
    // The slot itself is still there, so the columns to its right do not shift
    // when the status arrives. Sized for this row's text-sm scale.
    expect(row.querySelector('[aria-hidden="true"].w-3\\.5')).not.toBeNull();
  });

  it('keeps rendering the "all epochs" entry alongside the badged rows', () => {
    renderSelector('running');
    expect(screen.getByText('workflow.runSteps.allEpochs')).toBeTruthy();
  });

  it('names the epoch outcome in the tooltip instead of guessing from the end timestamp', () => {
    // The tooltip used to read "Completed" for any epoch carrying an end timestamp, so
    // a failed epoch announced success in the one place that spells the status out.
    renderSelector('running');

    // Rows are newest-first: epoch 3 (live), epoch 2 (failed), epoch 1 (completed).
    const tooltips = Array.from(document.querySelectorAll('[data-tooltip]'));
    expect(tooltips[0].textContent).toContain('status.running');
    expect(tooltips[1].textContent).toContain('status.failed');
    expect(tooltips[1].textContent).not.toContain('status.completed');
    expect(tooltips[2].textContent).toContain('status.completed');
  });

  it('shows a dash, not a status word, for an epoch payload that carries none', () => {
    // A showcase snapshot frozen before the field existed, or an epoch that ran nothing
    // but its trigger. This rendered "Pending" - a confident claim about an epoch that
    // had long since finished.
    render(
      <EpochSelector
        epochTimestamps={[{ epoch: 1, startedAt: '2026-08-02T09:00:00Z', endedAt: '2026-08-02T09:00:30Z' }] as any}
        selectedEpoch={null}
        onSelectEpoch={() => {}}
        viewMode="list"
        runStatus="completed"
      />,
    );

    const tooltip = document.querySelector('[data-tooltip]')!;
    expect(tooltip.textContent).not.toMatch(/status\.(pending|completed|failed|running)/);
    expect(tooltip.textContent).toContain('-');
  });

  it('names the status for a screen reader, not colour alone', () => {
    // The badge is an icon: without this the outcome is unreachable without a mouse,
    // since the tooltip that spells it out is hover-only.
    renderSelector('running');

    const label = document.querySelector('[data-epoch-option="2"] .sr-only');
    expect(label?.textContent).toBe('status.failed');
    expect(label?.getAttribute('data-epoch-status')).toBe('FAILED');
  });

  it('paints the gauge with the epoch outcome, so it cannot contradict the badge', () => {
    // Waterfall view: the bar used to be emerald for every settled epoch, which put a
    // green gauge next to a red badge on the same row.
    render(
      <EpochSelector
        epochTimestamps={EPOCHS as any}
        selectedEpoch={null}
        onSelectEpoch={() => {}}
        viewMode="waterfall"
        runStatus="cancelled"
      />,
    );

    const barOf = (epoch: number) =>
      document.querySelector(`[data-epoch-option="${epoch}"] .rounded-full > div`)?.getAttribute('class') || '';
    expect(barOf(1)).toContain('emerald');
    expect(barOf(2)).toContain('red');
    // Abandoned: grey, the same family as its badge.
    expect(barOf(3)).toContain('gray');
  });

  it('stops the 1s ticker when no epoch is executing', () => {
    // The ticker exists to make a live duration advance. Keyed off "has no end
    // timestamp" it never stopped, so a run parked for hours kept re-rendering the
    // whole list every second behind a figure that no longer moved.
    const spy = vi.spyOn(global, 'setInterval');
    try {
      renderSelector('stopped');
      expect(spy).not.toHaveBeenCalled();

      cleanup();
      // Same rows, run executing: the ticker must still arm, or a live duration freezes.
      renderSelector('running');
      expect(spy).toHaveBeenCalled();
    } finally {
      spy.mockRestore();
    }
  });
});

/**
 * The reported bug, at the level it was seen.
 *
 * <p>Each row prints "start -> end" in two slots of a FIXED 88px, sized for `HH:mm:ss` and
 * marked `whitespace-nowrap`. The shared time formatter used to append the display zone's label,
 * so a slot received `15:30:07 GMT+2` and the overflow rendered on top of the arrow between the
 * two times. Nothing failed and no test noticed: the layout simply lied.
 *
 * <p>Asserted on the group's whole text rather than on a width, because a width is a fact about
 * the stylesheet while this is a fact about the content: whatever else changes, only a clock
 * goes in there.
 */
describe('EpochSelector - the two time slots hold a clock and nothing else', () => {
  afterEach(cleanup);

  it('renders start and end as bare clocks around the arrow', () => {
    renderSelector('completed');

    const groups = [1, 2, 3].map((epoch) => {
      const row = document.querySelector(`[data-epoch-option="${epoch}"]`);
      expect(row).not.toBeNull();
      // Document order, so the flex wrapper is found before the slots it contains.
      const group = Array.from(row!.querySelectorAll('span'))
        .find((el) => el.textContent?.includes('\u2192'));
      expect(group, `epoch ${epoch} shows no start-to-end group`).toBeDefined();
      return group!.textContent ?? '';
    });

    // 1 and 2 are settled; 3 is still open and its end slot holds the ellipsis that keeps the
    // width stable while the run advances.
    expect(groups[0]).toMatch(/^\d{2}:\d{2}:\d{2}\u2192\d{2}:\d{2}:\d{2}$/);
    expect(groups[1]).toMatch(/^\d{2}:\d{2}:\d{2}\u2192\d{2}:\d{2}:\d{2}$/);
    expect(groups[2]).toMatch(/^\d{2}:\d{2}:\d{2}\u2192\.\.\.$/);
  });
});

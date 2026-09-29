/**
 * @vitest-environment jsdom
 *
 * The epoch navigator the canvas run pill unfolds. What matters:
 *  - it moves the canvas epoch only on a deliberate step (click, release, key let go):
 *    every change reloads that epoch's node states, so hovering, dragging and holding
 *    an arrow key must not fire one load per epoch crossed;
 *  - it stays cheap on a run fired thousands of times (bounded bar count);
 *  - it never widens the pill it sits in, and nothing clicked in it opens the Run panel.
 */
import React from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}(${Object.values(values).join(',')})` : key,
}));

import {
  EPOCH_NAV_GAPLESS_FROM,
  EPOCH_NAV_KEY_COMMIT_MS,
  EPOCH_NAV_MAX_BARS,
  RunEpochNavigator,
  buildEpochBars,
} from '@/components/workflow/run-panel/RunEpochNavigator';
import type { EpochTimestamp } from '@/components/workflow/run-panel/runFormatting';

beforeAll(() => {
  // jsdom has no PointerEvent: without one, fireEvent.pointerX drops clientX.
  if (!('PointerEvent' in window)) {
    class PointerEventPolyfill extends MouseEvent {
      pointerId: number;
      constructor(type: string, init: PointerEventInit = {}) {
        super(type, init);
        this.pointerId = init.pointerId ?? 1;
      }
    }
    (window as unknown as { PointerEvent: unknown }).PointerEvent = PointerEventPolyfill;
  }
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const START = Date.parse('2026-09-27T10:00:00Z');

function epoch(n: number, status: string | null, workMs: number | null, open = false): EpochTimestamp {
  const startedAt = new Date(START + n * 60_000).toISOString();
  return {
    epoch: n,
    startedAt,
    endedAt: open ? null : new Date(START + n * 60_000 + 30_000).toISOString(),
    workDurationMs: workMs,
    status,
  };
}

/** Epochs 1..5: epoch 3 failed, epoch 5 is still executing on a RUNNING run. */
const FIVE = [
  epoch(1, 'COMPLETED', 1_000),
  epoch(2, 'COMPLETED', 2_000),
  epoch(3, 'FAILED', 500),
  epoch(4, 'COMPLETED', 4_000),
  epoch(5, null, null, true),
];

function renderNav(props: Partial<React.ComponentProps<typeof RunEpochNavigator>> = {}) {
  const onSelectEpoch = vi.fn();
  const onClose = vi.fn();
  const utils = render(
    <RunEpochNavigator
      epochTimestamps={FIVE}
      selectedEpoch={null}
      runStatus="RUNNING"
      onSelectEpoch={onSelectEpoch}
      onClose={onClose}
      {...props}
    />,
  );
  return { ...utils, onSelectEpoch, onClose };
}

const nav = (name: string) => document.querySelector(`[data-epoch-nav="${name}"]`) as HTMLButtonElement | null;
const timeline = () => document.querySelector('[data-epoch-timeline]') as HTMLElement;
const detail = () => document.querySelector('[data-epoch-nav-detail]')!.textContent;

/** The timeline is 100 px wide, so with 5 epochs each one owns 20 px. */
function sizeTimeline() {
  vi.spyOn(timeline(), 'getBoundingClientRect').mockReturnValue({
    left: 0, top: 0, right: 100, bottom: 36, width: 100, height: 36, x: 0, y: 0, toJSON: () => ({}),
  } as DOMRect);
}

describe('buildEpochBars', () => {
  const point = (n: number, tone: 'ok' | 'failed' | 'running' | 'stopped' | 'none', durationMs: number | null) =>
    ({ epoch: n, startedAt: '', status: null, tone, durationMs });

  it('draws one bar per epoch, as tall as its duration against the longest', () => {
    const bars = buildEpochBars([point(1, 'ok', 500), point(2, 'ok', 1_000)]);

    expect(bars.map(b => [b.from, b.to, b.heightPct])).toEqual([[0, 0, 50], [1, 1, 100]]);
  });

  it('keeps a floor height so an instant epoch is still visible and clickable', () => {
    const [short] = buildEpochBars([point(1, 'ok', 1), point(2, 'ok', 10_000)]);

    expect(short.heightPct).toBe(14);
  });

  it('bounds the bar count on a very long run, each group showing its worst outcome and longest epoch', () => {
    const points = Array.from({ length: 1_000 }, (_, i) => point(i + 1, 'ok', 100));
    points[3] = point(4, 'failed', 100);
    points[5] = point(6, 'ok', 1_000);

    const bars = buildEpochBars(points);

    expect(bars.length).toBeLessThanOrEqual(EPOCH_NAV_MAX_BARS);
    expect(bars[0].from).toBe(0);
    expect(bars[bars.length - 1].to).toBe(999);
    expect(bars[0].tone).toBe('failed');
    expect(bars[0].heightPct).toBe(100);
  });

  it('draws a live epoch at a fixed height, since it has no final duration', () => {
    const bars = buildEpochBars([point(1, 'ok', 1_000), point(2, 'running', null)]);

    expect(bars[1].heightPct).toBe(40);
  });
});

describe('RunEpochNavigator', () => {
  it('draws one bar per epoch and a run summary in the all-epochs view', () => {
    renderNav();

    expect(document.querySelectorAll('[data-epoch-bar]')).toHaveLength(5);
    expect(detail()).toBe('workflow.runInfo.epochNav.summary(3,1,1,0)');
  });

  it('describes the selected epoch: status, start time and duration', () => {
    renderNav({ selectedEpoch: 2 });

    expect(detail()).toContain('status.completed');
    expect(detail()).toContain('2.0s');
    expect(document.querySelector('[data-epoch-bar="2"]')!.getAttribute('data-active')).toBe('true');
  });

  it('says a live epoch is still running instead of showing a duration that would go stale', () => {
    renderNav({ selectedEpoch: 5 });

    expect(detail()).toContain('workflow.runSteps.epochTooltip.stillRunning');
  });

  it('steps back from the all-epochs view onto the latest epoch', () => {
    const { onSelectEpoch } = renderNav();

    fireEvent.click(nav('previous')!);

    expect(onSelectEpoch).toHaveBeenCalledWith(5);
  });

  it('steps to the neighbouring epochs and stops at both ends', () => {
    const first = renderNav({ selectedEpoch: 1 });
    expect(nav('previous')!.disabled).toBe(true);
    fireEvent.click(nav('next')!);
    expect(first.onSelectEpoch).toHaveBeenCalledWith(2);
    cleanup();

    const lastOne = renderNav({ selectedEpoch: 5 });
    expect(nav('next')!.disabled).toBe(true);
    fireEvent.click(nav('previous')!);
    expect(lastOne.onSelectEpoch).toHaveBeenCalledWith(4);
  });

  it('has nothing to step forward to from the all-epochs view', () => {
    renderNav();

    expect(nav('next')!.disabled).toBe(true);
  });

  it('jumps to the next failed epoch, wrapping around to the first one', () => {
    const before = renderNav({ selectedEpoch: 1 });
    fireEvent.click(nav('next-failure')!);
    expect(before.onSelectEpoch).toHaveBeenCalledWith(3);
    cleanup();

    const after = renderNav({ selectedEpoch: 4 });
    fireEvent.click(nav('next-failure')!);
    expect(after.onSelectEpoch).toHaveBeenCalledWith(3);
  });

  it('offers no failure jump on a run without failures', () => {
    renderNav({ epochTimestamps: [epoch(1, 'COMPLETED', 1_000), epoch(2, 'COMPLETED', 1_000)] });

    expect(nav('next-failure')).toBeNull();
  });

  it('returns to all epochs, and marks that button pressed when already there', () => {
    const picked = renderNav({ selectedEpoch: 2 });
    expect(nav('all')!.getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(nav('all')!);
    expect(picked.onSelectEpoch).toHaveBeenCalledWith(null);
    cleanup();

    const all = renderNav();
    expect(nav('all')!.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(nav('all')!);
    // Already there: no pointless reload of the canvas.
    expect(all.onSelectEpoch).not.toHaveBeenCalled();
  });

  it('only previews while dragging across the timeline, and commits the epoch under the pointer on release', () => {
    const { onSelectEpoch } = renderNav();
    sizeTimeline();

    fireEvent.pointerDown(timeline(), { clientX: 5, pointerId: 1 });
    fireEvent.pointerMove(timeline(), { clientX: 45, pointerId: 1 });
    fireEvent.pointerMove(timeline(), { clientX: 65, pointerId: 1 });

    // Three epochs crossed, none loaded yet: the preview follows the pointer.
    expect(onSelectEpoch).not.toHaveBeenCalled();
    expect(detail()).toContain('workflow.runSteps.epochTooltip.epoch(4)');

    fireEvent.pointerUp(timeline(), { clientX: 65, pointerId: 1 });

    expect(onSelectEpoch).toHaveBeenCalledTimes(1);
    expect(onSelectEpoch).toHaveBeenCalledWith(4);
  });

  it('shows the hovered epoch without selecting it, and forgets it when the pointer leaves', () => {
    const { onSelectEpoch } = renderNav({ selectedEpoch: 1 });
    sizeTimeline();

    fireEvent.pointerMove(timeline(), { clientX: 50 });
    expect(detail()).toContain('workflow.runSteps.epochTooltip.epoch(3)');
    expect(detail()).toContain('status.failed');

    fireEvent.pointerLeave(timeline());
    expect(detail()).not.toContain('epochTooltip.epoch(3)');
    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('commits a single epoch after a burst of arrow keys, once the keys are let go', () => {
    vi.useFakeTimers();
    const { onSelectEpoch } = renderNav({ selectedEpoch: 1 });

    fireEvent.keyDown(timeline(), { key: 'ArrowRight' });
    fireEvent.keyDown(timeline(), { key: 'ArrowRight' });
    fireEvent.keyDown(timeline(), { key: 'ArrowRight' });
    expect(onSelectEpoch).not.toHaveBeenCalled();
    expect(timeline().getAttribute('aria-valuenow')).toBe('4');

    act(() => { vi.advanceTimersByTime(EPOCH_NAV_KEY_COMMIT_MS); });

    expect(onSelectEpoch).toHaveBeenCalledTimes(1);
    expect(onSelectEpoch).toHaveBeenCalledWith(4);
  });

  it('jumps to the first and last epochs with Home and End', () => {
    vi.useFakeTimers();
    const { onSelectEpoch } = renderNav({ selectedEpoch: 3 });

    fireEvent.keyDown(timeline(), { key: 'End' });
    act(() => { vi.advanceTimersByTime(EPOCH_NAV_KEY_COMMIT_MS); });
    fireEvent.keyDown(timeline(), { key: 'Home' });
    act(() => { vi.advanceTimersByTime(EPOCH_NAV_KEY_COMMIT_MS); });

    expect(onSelectEpoch.mock.calls).toEqual([[5], [1]]);
  });

  it('drops a pending key commit when the navigator goes away', () => {
    vi.useFakeTimers();
    const { onSelectEpoch, unmount } = renderNav({ selectedEpoch: 1 });

    fireEvent.keyDown(timeline(), { key: 'ArrowRight' });
    unmount();
    act(() => { vi.advanceTimersByTime(EPOCH_NAV_KEY_COMMIT_MS * 2); });

    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('folds back on Escape from the timeline', () => {
    const { onClose } = renderNav();

    fireEvent.keyDown(timeline(), { key: 'Escape' });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('folds back on Escape from any of its buttons too', () => {
    const { onClose } = renderNav({ selectedEpoch: 2 });

    fireEvent.keyDown(nav('next')!, { key: 'Escape' });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('drops a pending key step when Escape folds it', () => {
    vi.useFakeTimers();
    const { onSelectEpoch, onClose } = renderNav({ selectedEpoch: 1 });

    fireEvent.keyDown(timeline(), { key: 'ArrowRight' });
    fireEvent.keyDown(timeline(), { key: 'Escape' });
    act(() => { vi.advanceTimersByTime(EPOCH_NAV_KEY_COMMIT_MS * 2); });

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('steps back with ArrowLeft and ArrowDown, forward with ArrowUp', () => {
    vi.useFakeTimers();
    const { onSelectEpoch } = renderNav({ selectedEpoch: 3 });

    fireEvent.keyDown(timeline(), { key: 'ArrowLeft' });
    fireEvent.keyDown(timeline(), { key: 'ArrowDown' });
    expect(timeline().getAttribute('aria-valuenow')).toBe('1');
    fireEvent.keyDown(timeline(), { key: 'ArrowUp' });
    act(() => { vi.advanceTimersByTime(EPOCH_NAV_KEY_COMMIT_MS); });

    expect(onSelectEpoch.mock.calls).toEqual([[2]]);
  });

  it('enters the timeline on the latest epoch when stepping from the all-epochs view', () => {
    vi.useFakeTimers();
    const { onSelectEpoch } = renderNav();

    fireEvent.keyDown(timeline(), { key: 'ArrowRight' });
    act(() => { vi.advanceTimersByTime(EPOCH_NAV_KEY_COMMIT_MS); });

    expect(onSelectEpoch).toHaveBeenCalledWith(5);
  });

  it('moves by a tenth of the run with PageUp and PageDown', () => {
    vi.useFakeTimers();
    const thirty = Array.from({ length: 30 }, (_, i) => epoch(i + 1, 'COMPLETED', 1_000));
    const { onSelectEpoch } = renderNav({ epochTimestamps: thirty, selectedEpoch: 10, runStatus: 'COMPLETED' });

    fireEvent.keyDown(timeline(), { key: 'PageUp' });
    act(() => { vi.advanceTimersByTime(EPOCH_NAV_KEY_COMMIT_MS); });
    expect(onSelectEpoch).toHaveBeenLastCalledWith(13);
  });

  it('continues a key step from the epoch being hovered', () => {
    vi.useFakeTimers();
    const { onSelectEpoch } = renderNav({ selectedEpoch: 1 });
    sizeTimeline();

    fireEvent.pointerMove(timeline(), { clientX: 50 });
    fireEvent.keyDown(timeline(), { key: 'ArrowRight' });
    act(() => { vi.advanceTimersByTime(EPOCH_NAV_KEY_COMMIT_MS); });

    expect(onSelectEpoch).toHaveBeenCalledWith(4);
  });

  it('forgets a drag cancelled by the browser, without picking anything', () => {
    const { onSelectEpoch } = renderNav({ selectedEpoch: 1 });
    sizeTimeline();

    fireEvent.pointerDown(timeline(), { clientX: 90, button: 0 });
    fireEvent.pointerCancel(timeline());
    fireEvent.pointerUp(timeline(), { clientX: 90 });

    expect(onSelectEpoch).not.toHaveBeenCalled();
    expect(detail()).not.toContain('epochTooltip.epoch(5)');
  });

  it('ignores a release that no press on the timeline started', () => {
    const { onSelectEpoch } = renderNav({ selectedEpoch: 1 });
    sizeTimeline();

    fireEvent.pointerUp(timeline(), { clientX: 90 });

    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('does not reload the epoch already on screen when released on it', () => {
    const { onSelectEpoch } = renderNav({ selectedEpoch: 3 });
    sizeTimeline();

    fireEvent.pointerDown(timeline(), { clientX: 50, button: 0 });
    fireEvent.pointerUp(timeline(), { clientX: 50 });

    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('ignores a right click on the timeline', () => {
    const { onSelectEpoch } = renderNav({ selectedEpoch: 1 });
    sizeTimeline();

    fireEvent.pointerDown(timeline(), { clientX: 90, button: 2 });
    fireEvent.pointerUp(timeline(), { clientX: 90, button: 2 });

    expect(onSelectEpoch).not.toHaveBeenCalled();
  });

  it('captures the pointer so a drag keeps working past the edges, which pick the first and last epochs', () => {
    const { onSelectEpoch } = renderNav({ selectedEpoch: 3 });
    sizeTimeline();
    const capture = vi.fn();
    (timeline() as unknown as { setPointerCapture: unknown }).setPointerCapture = capture;

    fireEvent.pointerDown(timeline(), { clientX: 50, button: 0, pointerId: 7 });
    fireEvent.pointerUp(timeline(), { clientX: -40 });
    expect(capture).toHaveBeenCalledWith(7);
    expect(onSelectEpoch).toHaveBeenLastCalledWith(1);

    fireEvent.pointerDown(timeline(), { clientX: 50, button: 0 });
    fireEvent.pointerUp(timeline(), { clientX: 400 });
    expect(onSelectEpoch).toHaveBeenLastCalledWith(5);
  });

  it('on a grouped timeline, picks an epoch inside the bar under the pointer and lights that bar', () => {
    // 250 epochs -> groups of 3 -> 84 bars, the last one holding a single epoch (250).
    const many = Array.from({ length: 250 }, (_, i) => epoch(i + 1, 'COMPLETED', 1_000));
    const { onSelectEpoch } = renderNav({ epochTimestamps: many, runStatus: 'COMPLETED' });
    sizeTimeline();

    const bars = document.querySelectorAll('[data-epoch-bar]');
    expect(bars).toHaveLength(84);

    // At 99% of the width the pointer is over the last bar (83.16 of 84). Mapping the
    // pointer onto the epoch LIST instead would pick epoch 248, which sits in bar 82.
    fireEvent.pointerDown(timeline(), { clientX: 99, button: 0 });
    expect(bars[83].getAttribute('data-active')).toBe('true');
    expect(bars[82].getAttribute('data-active')).toBeNull();
    fireEvent.pointerUp(timeline(), { clientX: 99 });

    expect(onSelectEpoch).toHaveBeenCalledWith(250);
  });

  it('drops the gaps between bars once there are many, so they fit a narrow pill', () => {
    const many = Array.from({ length: EPOCH_NAV_GAPLESS_FROM + 1 }, (_, i) => epoch(i + 1, 'COMPLETED', 1_000));
    renderNav({ epochTimestamps: many, runStatus: 'COMPLETED' });

    expect(timeline().className).toMatch(/(^|\s)gap-0(\s|$)/);
    cleanup();

    renderNav();
    expect(timeline().className).toMatch(/(^|\s)gap-px(\s|$)/);
  });

  it('orders unsorted epochs and paints stopped and outcome-less ones gray, counting only real outcomes', () => {
    renderNav({
      runStatus: 'COMPLETED',
      epochTimestamps: [epoch(3, null, 100), epoch(1, 'COMPLETED', 1_000), epoch(2, 'CANCELLED', 500)],
    });

    const bars = [...document.querySelectorAll<HTMLElement>('[data-epoch-bar]')];
    expect(bars.map(b => b.getAttribute('data-epoch-bar'))).toEqual(['1', '2', '3']);
    expect(bars[1].className).toContain('bg-gray-400');
    expect(bars[2].className).toContain('bg-gray-300');
    // ok, failed, running, stopped: the outcome-less epoch is in none of them.
    expect(detail()).toBe('workflow.runInfo.epochNav.summary(1,0,0,1)');
  });

  it('rests the slider on the latest epoch in the all-epochs view, since a slider always has a value', () => {
    renderNav();

    expect(timeline().getAttribute('aria-valuenow')).toBe('5');
    expect(timeline().getAttribute('aria-valuetext')).toBe('workflow.runSteps.allEpochs');
  });

  it('keeps every click and key to itself, so the pill around it does not open the Run panel', () => {
    const onPillClick = vi.fn();
    const onPillKey = vi.fn();
    render(
      <div onClick={onPillClick} onKeyDown={onPillKey}>
        <RunEpochNavigator epochTimestamps={FIVE} selectedEpoch={2} runStatus="RUNNING" onSelectEpoch={vi.fn()} />
      </div>,
    );

    fireEvent.click(nav('next')!);
    fireEvent.click(timeline());
    fireEvent.keyDown(timeline(), { key: 'Enter' });

    expect(onPillClick).not.toHaveBeenCalled();
    expect(onPillKey).not.toHaveBeenCalled();
  });

  it('takes the width of the pill instead of setting its own, so unfolding only adds height', () => {
    renderNav();
    const root = document.querySelector('[data-run-epoch-navigator]')!;

    expect(root.className).toMatch(/(^|\s)w-0(\s|$)/);
    expect(root.className).toMatch(/(^|\s)min-w-full(\s|$)/);
  });

  it('is a labelled slider for assistive tech', () => {
    renderNav({ selectedEpoch: 3 });

    const slider = screen.getByRole('slider');
    expect(slider.getAttribute('aria-valuemin')).toBe('1');
    expect(slider.getAttribute('aria-valuemax')).toBe('5');
    expect(slider.getAttribute('aria-valuenow')).toBe('3');
  });
});

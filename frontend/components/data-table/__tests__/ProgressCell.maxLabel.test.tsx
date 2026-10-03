/**
 * @vitest-environment jsdom
 *
 * What a progress cell WRITES under its bar.
 *
 * The cell stores its value in the column's own unit (0..max) and the label used to print that
 * raw number followed by "%". On the default max of 100 the two coincide, so nobody saw it. On a
 * column configured with max 10, a finished row showed a full bar captioned "10%".
 */
import React from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, cleanup, screen } from '@testing-library/react';
import { ProgressCell, progressTier } from '../cells/ProgressCell';

afterEach(cleanup);

function renderCell(value: unknown, max?: unknown, tempValue?: number) {
  return render(
    <ProgressCell
      value={value}
      rowKey="r1"
      field="progress"
      displayConfig={max === undefined ? undefined : ({ max } as never)}
      isEditing={false}
      onSaveAndExit={vi.fn()}
      onStartEditing={vi.fn()}
      onExitEditing={vi.fn()}
      cellKey="r1:progress"
      tempValue={tempValue}
      onTempChange={vi.fn()}
      onProgressSave={vi.fn()}
    />,
  );
}

const fill = (container: HTMLElement) =>
  container.querySelector('[data-progress-tier]') as HTMLElement;
const fillWidth = (container: HTMLElement) => fill(container).style.width;

describe('ProgressCell label follows the column max', () => {
  it('a full bar on max 10 reads "10 / 10", never "10%"', () => {
    const { container } = renderCell(10, 10);
    expect(screen.getByText('10 / 10')).toBeTruthy();
    expect(screen.queryByText('10%')).toBeNull();
    expect(fillWidth(container)).toBe('100%');
  });

  it('a partial value on max 10 reads as value over max', () => {
    const { container } = renderCell(7, 10);
    expect(screen.getByText('7 / 10')).toBeTruthy();
    expect(fillWidth(container)).toBe('70%');
  });

  it('max 100 keeps the percentage label', () => {
    renderCell(67, 100);
    expect(screen.getByText('67%')).toBeTruthy();
  });

  it('no configured max defaults to 100 and a percentage', () => {
    renderCell(40);
    expect(screen.getByText('40%')).toBeTruthy();
  });

  it('a value above the max is clamped in the label too', () => {
    renderCell(25, 10);
    expect(screen.getByText('10 / 10')).toBeTruthy();
  });

  it('the label follows the slider while it is being dragged', () => {
    renderCell(2, 10, 6);
    expect(screen.getByText('6 / 10')).toBeTruthy();
  });
});

describe('ProgressCell colour follows how full the bar is', () => {
  it.each([
    [0, 100, 'low', 'bg-red-500'],
    [33, 100, 'low', 'bg-red-500'],
    [34, 100, 'mid', 'bg-amber-400'],
    [66, 100, 'mid', 'bg-amber-400'],
    [67, 100, 'high', 'bg-lime-500'],
    [100, 100, 'high', 'bg-lime-500'],
  ])('value %i on max %i is %s', (value, max, tierName, cls) => {
    const { container } = renderCell(value, max);
    expect(fill(container).dataset.progressTier).toBe(tierName);
    expect(fill(container).className).toContain(cls);
  });

  it('the tier is relative to the column max, not to 100', () => {
    // 9 of 10 is nearly done: green, even though "9" alone would be red on a 0..100 scale.
    expect(fill(renderCell(9, 10).container).className).toContain('bg-lime-500');
    cleanup();
    expect(fill(renderCell(2, 10).container).className).toContain('bg-red-500');
  });

  it('the colour changes while the slider is dragged', () => {
    const { container } = renderCell(1, 10, 5);
    expect(fill(container).className).toContain('bg-amber-400');
  });

  it.each([
    [1, 'red-600'],
    [5, 'amber-500'],
    [9, 'lime-600'],
  ])('the slider thumb at %i of 10 is %s in both engines', (value, colour) => {
    const { container } = renderCell(value, 10);
    const cls = (container.querySelector('input[type="range"]') as HTMLElement).className;
    expect(cls).toContain(`[&::-webkit-slider-thumb]:bg-${colour}`);
    expect(cls).toContain(`[&::-moz-range-thumb]:bg-${colour}`);
    // exactly one tier: no leftover colour from another one
    expect(cls.match(/webkit-slider-thumb\]:bg-(red|amber|lime)-/g)).toHaveLength(1);
    expect(cls.match(/moz-range-thumb\]:bg-(red|amber|lime)-/g)).toHaveLength(1);
  });

  it('boundaries: a third and two thirds open the next tier', () => {
    expect(progressTier(1 / 3)).toBe('mid');
    expect(progressTier(2 / 3)).toBe('high');
  });

  it('an unknown ratio is never painted green', () => {
    expect(progressTier(NaN)).toBe('low');
  });
});

describe('ProgressCell survives values and maxes nobody configured on purpose', () => {
  it.each<[string, unknown, unknown, string, string]>([
    ['an empty cell (the add-row form)', null, 10, '0 / 10', 'low'],
    ['an undefined value', undefined, undefined, '0%', 'low'],
    ['text that is not a number', 'abc', 10, '0 / 10', 'low'],
    ['a negative value', -4, 10, '0 / 10', 'low'],
    ['a numeric string value', '7', 10, '7 / 10', 'high'],
    ['a max stored as a string', 5, '10', '5 / 10', 'mid'],
    ['a max stored as decimal text is the default scale, as on the server', 50, '2.5', '50%', 'mid'],
    ['a max below 1 is the default scale', 50, 0.5, '50%', 'mid'],
    ['a max of 0 falls back to 100', 50, 0, '50%', 'mid'],
    ['a negative max falls back to 100', 50, -5, '50%', 'mid'],
    ['a max that is not a number falls back to 100', 50, 'abc', '50%', 'mid'],
    ['a decimal value is rounded to one digit', 6.6667, 10, '6.7 / 10', 'high'],
    ['a decimal max is truncated like the server does', 2, 2.5, '2 / 2', 'high'],
    ['a decimal percentage is rounded too', 33.333, 100, '33.3%', 'low'],
    ['an infinite max falls back to 100', 50, Infinity, '50%', 'mid'],
    ['large numbers are grouped in the app locale', 1500, 2000, '1,500 / 2,000', 'high'],
  ])('%s', (_name, value, max, label, tierName) => {
    const { container } = renderCell(value, max);
    expect(screen.getByText(label)).toBeTruthy();
    expect(fill(container).dataset.progressTier).toBe(tierName);
  });

  it('the track has a dark-mode colour, like the column-type preview', () => {
    const { container } = renderCell(5, 10);
    expect(fill(container).parentElement!.className).toContain('dark:bg-slate-700');
  });
});

describe('ProgressCell while the slider is in flight', () => {
  it('a drag value above the max is clamped: no overflowing bar, no "25 / 10"', () => {
    const { container } = renderCell(2, 10, 25);
    expect(screen.getByText('10 / 10')).toBeTruthy();
    expect(fillWidth(container)).toBe('100%');
    expect((container.querySelector('input[type="range"]') as HTMLInputElement).value).toBe('10');
  });

  it('a drag down to 0 wins over a stored value above 0', () => {
    const { container } = renderCell(8, 10, 0);
    expect(screen.getByText('0 / 10')).toBeTruthy();
    expect(fillWidth(container)).toBe('0%');
    expect(fill(container).dataset.progressTier).toBe('low');
  });

  it('a drag value that is not a number never prints "NaN"', () => {
    renderCell(8, 10, NaN);
    expect(screen.getByText('0 / 10')).toBeTruthy();
  });
});

describe('ProgressCell numbers follow the APP locale, not the machine', () => {
  const originalPath = window.location.pathname;
  afterEach(() => window.history.pushState({}, '', originalPath));

  it('on /fr the decimal separator is a comma', () => {
    window.history.pushState({}, '', '/fr/app/tables');
    renderCell(6.6667, 10);
    expect(screen.getByText('6,7 / 10')).toBeTruthy();
  });

  it('on /fr thousands are grouped with a space, on /en with a comma', () => {
    window.history.pushState({}, '', '/fr/app/tables');
    const fr = renderCell(1500, 2000).container.textContent ?? '';
    expect(fr).toMatch(/^1\s500 \/ 2\s000$/);
    cleanup();
    window.history.pushState({}, '', '/en/app/tables');
    renderCell(1500, 2000);
    expect(screen.getByText('1,500 / 2,000')).toBeTruthy();
  });
});

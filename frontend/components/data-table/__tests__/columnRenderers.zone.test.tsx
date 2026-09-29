/**
 * @vitest-environment jsdom
 *
 * The OTHER table-cell date path, and why it needed its own file.
 *
 * <p>`DateCell` and `RelativeTimeRenderer` render dates in the same tables, and the calendar-day
 * rule was applied to the first and missed in the second: it parsed its value into a `Date` before
 * formatting, which destroys the only evidence that the value named a day rather than a moment. A
 * bare `2026-01-15` then read "Jan 14" for every reader west of Greenwich, so two columns of one
 * table could name different days for the same value - under a comment claiming the formatter
 * recognised the case on its own, which it cannot from a `Date`.
 *
 * <p>Nothing exercised this renderer at all. The source scan that guards the chart axes reads three
 * files and never opens this one, and `DateCell.zone.test.tsx` covers only its sibling: the defect
 * was invisible to every test in the change that introduced it.
 */
import React from 'react';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, cleanup, screen } from '@testing-library/react';

import { RelativeTimeRenderer } from '../columnRenderers';
import { DateCell } from '../cells/DateCell';
import type { ColumnDisplayConfig } from '@/types/data-sources';
import { applyDisplayTimeZone, clearDisplayTimeZone } from '@/lib/utils/timezone';

/** Los Angeles is UTC-8: far enough west that a leak to the reader's zone moves the day. */
const WEST = 'America/Los_Angeles';

function show(value: unknown) {
  const { container } = render(
    <RelativeTimeRenderer value={value} field="updated_at" />
  );
  return container.textContent ?? '';
}

beforeEach(() => clearDisplayTimeZone());
afterEach(() => {
  cleanup();
  clearDisplayTimeZone();
});

describe('a value that names a DAY', () => {
  it('keeps its day for a reader eight hours west', () => {
    applyDisplayTimeZone(WEST);

    const text = show('2026-01-15');

    expect(text).toContain('15');
    expect(text).not.toContain('14');
  });

  it('agrees with DateCell on the same value, which is the property that was broken', () => {
    // Two columns of one table naming different days for one value. The previous version of this
    // test rendered only this renderer and asserted `toContain("15")`: the same call and a weaker
    // assertion than the test above it, so the cross-component agreement it is named for was
    // checked nowhere. Both components now, one value, compared against each other.
    applyDisplayTimeZone(WEST);

    const rendered = show('2026-01-15');
    cleanup();

    const { container } = render(
      <DateCell
        value="2026-01-15"
        rowKey="r"
        field="due"
        displayConfig={{ dateFormat: 'datetime' } as ColumnDisplayConfig}
        isEditing={false}
        onSaveAndExit={() => {}}
        onStartEditing={() => {}}
        onExitEditing={() => {}}
      />
    );
    const cell = container.textContent ?? '';

    expect(cell, 'the cell must name the same day this renderer does').toContain('15');
    expect(cell).not.toContain('14');
    expect(rendered).toContain('15');
    expect(rendered).not.toContain('14');
  });
});

describe('a real timestamp', () => {
  it('is read in the zone the person chose, to the second', () => {
    applyDisplayTimeZone('Asia/Tokyo');

    // 23:30:05 UTC is 08:30:05 the NEXT day in Tokyo.
    const text = show('2026-01-15T23:30:05Z');

    expect(text).toContain('16');
    expect(text).toContain('08:30:05');
  });

  it('follows a change of preference', () => {
    applyDisplayTimeZone('Asia/Tokyo');
    expect(show('2026-01-15T23:30:05Z')).toContain('08:30:05');
    cleanup();

    applyDisplayTimeZone(WEST);

    expect(show('2026-01-15T23:30:05Z')).toContain('15:30:05');
  });
});

describe('input it cannot read', () => {
  it('shows a dash for an empty value', () => {
    render(<RelativeTimeRenderer value="" field="updated_at" />);

    expect(screen.getByText('-')).toBeTruthy();
  });

  it('shows a dash for a NON-STRING, which is the input class that actually throws', () => {
    // `value` is `any` and the renderer is picked by column type, so an epoch-millis number or a
    // JSONB object reaches it. `parseUtcAware` passes a non-string through unchanged, so the
    // formatter calls `.getTime()` on it and raises a TypeError - inside a table row, which takes the
    // whole grid down. A try/catch used to absorb that and was removed in this branch on the grounds
    // that junk strings are Invalid Dates: true, and the only input class the reasoning covered.
    applyDisplayTimeZone(WEST);

    expect(show(1_767_225_600_000 as unknown as string).trim()).toBe('-');
    expect(show({ due: '2026-01-15' } as unknown as string).trim()).toBe('-');
    expect(show(true as unknown as string).trim()).toBe('-');
  });

  it('shows a dash for a NON-STRING, which is the input class that actually throws', () => {
    // `value` is `any` and the renderer is picked by column type, so an epoch-millis number or a
    // JSONB object reaches it. `parseUtcAware` passes a non-string through unchanged, so the
    // formatter calls `.getTime()` on it and raises a TypeError - inside a table row, which takes the
    // whole grid down. A try/catch used to absorb that and was removed in this branch on the grounds
    // that junk strings are Invalid Dates: true, and the only input class the reasoning covered.
    applyDisplayTimeZone(WEST);

    expect(show(1_767_225_600_000 as unknown as string).trim()).toBe('-');
    expect(show({ due: '2026-01-15' } as unknown as string).trim()).toBe('-');
    expect(show(true as unknown as string).trim()).toBe('-');
  });

  it('shows a dash for a value that is not a date, rather than throwing or printing it', () => {
    applyDisplayTimeZone(WEST);

    // Asserted on what is RENDERED. The previous version was `expect(() => show(...))
    // .not.toThrow()`, which passed before and after every change to this renderer: nothing here
    // throws for any string, because `new Date(<junk>)` is an Invalid Date rather than an error.
    // It was written to cover a try/catch that could never run, and it was the reason nobody
    // noticed the catch was dead - so the dash it claims to show was never checked at all.
    //
    // The renderer is inside a table row, so the no-throw part still matters; it is implied by
    // getting a rendered string back at all.
    expect(show('not a date').trim()).toBe('-');
  });
});

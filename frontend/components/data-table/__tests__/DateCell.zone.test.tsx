/**
 * @vitest-environment jsdom
 *
 * Which zone a table's date column is READ in.
 *
 * Table cells were the last place still formatting in UTC unconditionally, so a row saying a task
 * is due "14:00" meant 14:00 UTC while every other surface in the product had moved to the
 * reader's zone. Two things have to hold at once, and they pull in opposite directions:
 *
 *   - a TIMESTAMP follows the reader (that is the whole point of the preference);
 *   - a bare `YYYY-MM-DD` does NOT, because it names a day, and translating a day into a zone west
 *     of Greenwich turns it into the previous one. A `date` column holds exactly those.
 *
 * The second is the regression this file exists for: the cell used to parse its value into a
 * `Date` before formatting, which destroys the only evidence that the value was a day rather than
 * a moment, and every reader in the Americas saw dates off by one.
 */
import React from 'react';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, cleanup, screen, fireEvent } from '@testing-library/react';

import { DateCell } from '../cells/DateCell';
import type { VisualCellProps } from '../cells/types';
import type { ColumnDisplayConfig } from '@/types/data-sources';
import { applyDisplayTimeZone, clearDisplayTimeZone } from '@/lib/utils/timezone';
import { isCalendarDateOnly, wallClockIn } from '@/lib/utils/dateFormatters';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/** Los Angeles is UTC-8: far enough west that any leak to the reader's zone moves the day. */
const WEST = 'America/Los_Angeles';

function cellProps(value: unknown, dateFormat: string): VisualCellProps {
  return {
    value,
    rowKey: 'row-1',
    field: 'due',
    displayConfig: { dateFormat } as ColumnDisplayConfig,
    isEditing: false,
    onSaveAndExit: () => {},
    onStartEditing: () => {},
    onExitEditing: () => {},
  };
}

function show(value: unknown, dateFormat: string) {
  render(<DateCell {...cellProps(value, dateFormat)} />);
  return screen.getByText((_, node) => node?.tagName === 'SPAN' && !!node.textContent?.trim())
    .textContent as string;
}

beforeEach(() => clearDisplayTimeZone());
afterEach(() => {
  cleanup();
  clearDisplayTimeZone();
});

describe('what counts as a day rather than a moment', () => {
  // The cell carried its own copy of this predicate, byte-identical, and the whole feature turns
  // on it: the display side asks the shared one whether to translate a value into the reader zone,
  // and the edit widget asked its own. Nothing made them agree. Tightening the shared rule - which
  // is what made a legacy `HH:mm` recoverable - would have left the widget on the old one and
  // reopened "same value, two days on one screen".
  //
  // A source assertion, because the sharing itself is the fact. A behavioural test of it would
  // only restate the repair case below: the first version of this WAS behavioural, asserted
  // `show('09:00')`, and passed identically with the cell's own copy back in place - it could not
  // fail for the thing it was named after. This one fails the moment somebody writes the rule out
  // a second time here.
  it('asks the formatter module what a calendar day is, instead of keeping its own copy', () => {
    const source = readFileSync(
      join(process.cwd(), 'components', 'data-table', 'cells', 'DateCell.tsx'),
      'utf8',
    );

    expect(source).toContain('isCalendarDateOnly');
    expect(
      source,
      'the cell must not re-implement the rule the display side decides with',
    ).not.toContain("!value.includes('T')");
  });

  it('and the shared rule reads a bare time as a MOMENT that failed to parse, not as a day', () => {
    // The value the two rules disagree about, and the reason the tightening happened: classifying
    // "09:00" as a day is what made the old write path unrepairable.
    expect(isCalendarDateOnly('2026-01-15')).toBe(true);
    expect(isCalendarDateOnly('09:00')).toBe(false);
    expect(isCalendarDateOnly('not a date')).toBe(false);
  });

  it('and a day that does not EXIST is not a calendar day either', () => {
    // Shape alone accepted "2026-02-30", and the three functions that ask this question then gave
    // three answers for it: the formatters rendered 02 Mar 2026 (V8 rolls the ISO string over),
    // the day-bounds helper answered null by design, and the wall-clock solver resolved it to 2
    // March. So editing the TIME of such a row moved it into March while the cell kept displaying
    // the same text, and nothing looked wrong.
    expect(isCalendarDateOnly('2026-02-30')).toBe(false);
    expect(isCalendarDateOnly('2026-13-01')).toBe(false);
    expect(isCalendarDateOnly('2026-00-10')).toBe(false);
    // A real leap day is still a day.
    expect(isCalendarDateOnly('2028-02-29')).toBe(true);
    expect(isCalendarDateOnly('2026-02-28')).toBe(true);
  });
});

describe('a day-only value', () => {
  it('keeps its day for a reader eight hours west', () => {
    applyDisplayTimeZone(WEST);

    const text = show('2026-01-15', 'date');

    expect(text).toContain('15');
    expect(text).toContain('Jan');
    expect(text).not.toContain('14');
  });

  it('keeps its day even when the column is typed datetime', () => {
    // A `datetime` column over day-only rows is a real shape, and the worst reading of it: the
    // cell would invent an hour that was never stored, on the wrong day.
    applyDisplayTimeZone(WEST);

    const text = show('2026-01-15', 'datetime');

    expect(text).toContain('15');
    expect(text).not.toContain('14');
  });
});

describe('a real timestamp', () => {
  it('is read in the zone the person chose, not in UTC', () => {
    applyDisplayTimeZone('Asia/Tokyo');

    // 23:30 UTC is 08:30 the NEXT day in Tokyo: the date and the time both have to move, which no
    // amount of UTC formatting can produce.
    const text = show('2026-01-15T23:30:00Z', 'datetime');

    expect(text).toContain('16');
    expect(text).toContain('08:30');
  });

  it('shows its time in that zone for a time-typed column', () => {
    applyDisplayTimeZone('Asia/Tokyo');

    const text = show('2026-01-15T23:30:00Z', 'time');

    expect(text).toContain('08:30');
  });

  it('follows a later change of preference', () => {
    applyDisplayTimeZone('Asia/Tokyo');
    expect(show('2026-01-15T23:30:00Z', 'time')).toContain('08:30');
    cleanup();

    applyDisplayTimeZone(WEST);

    expect(show('2026-01-15T23:30:00Z', 'time')).toContain('15:30');
  });
});

describe('what it does with input it cannot read', () => {
  it('says "No date" for an empty value rather than printing a placeholder date', () => {
    render(<DateCell {...cellProps('', 'date')} />);

    expect(screen.getByText('No date')).toBeTruthy();
  });

  it('shows a non-date string unchanged instead of a dash', () => {
    // The raw value is more use to someone fixing their data than "-" is.
    applyDisplayTimeZone(WEST);

    expect(show('not a date', 'date')).toBe('not a date');
  });
});

describe('the EDIT widget, which a reader retypes from what the cell showed', () => {
  /** The input a click on the cell puts up. */
  function edit(value: unknown, dateFormat: string, onSave: (v: unknown) => void) {
    // Queried off the container: a date / time / datetime-local input has no textbox role.
    const { container } = render(
      <DateCell {...{ ...cellProps(value, dateFormat), isEditing: true, onSaveAndExit: onSave }} />
    );
    const input = container.querySelector('input');
    if (!input) throw new Error('the editing cell rendered no input');
    return input as HTMLInputElement;
  }

  it('opens showing the same wall clock the cell displayed, not UTC', () => {
    // The hazard this closes: the cell said "Jan 16, 08:30" and the input said
    // 2026-01-15T23:30. Nothing labelled the input, so the obvious correction was to retype 08:30 -
    // which stored an instant nine hours off, from an edit that looked like a no-op.
    applyDisplayTimeZone('Asia/Tokyo');

    const input = edit('2026-01-15T23:30:00Z', 'datetime', () => {});

    expect(input.value).toBe('2026-01-16T08:30');
  });

  it('stores the instant that wall clock means in the reader zone', () => {
    applyDisplayTimeZone('Asia/Tokyo');
    const saved: unknown[] = [];

    const input = edit('2026-01-15T23:30:00Z', 'datetime', (v) => saved.push(v));
    fireEvent.change(input, { target: { value: '2026-01-16T09:00' } });

    // 09:00 in Tokyo is 00:00 UTC the same day, NOT 09:00 UTC.
    expect(saved).toHaveLength(1);
    expect(String(saved[0])).toBe('2026-01-16T00:00:00.000Z');
  });

  it('round-trips: what it opens with, saved unchanged, is the instant it started from', () => {
    // The property that makes the widget safe to touch at all.
    applyDisplayTimeZone('America/Los_Angeles');
    const saved: unknown[] = [];

    const input = edit('2026-01-15T23:30:00Z', 'datetime', (v) => saved.push(v));
    const opened = input.value;

    // Away and back: jsdom does not fire change for an identical value, so the return trip has to
    // be a real change to be observable at all.
    fireEvent.change(input, { target: { value: '2026-01-16T09:00' } });
    fireEvent.change(input, { target: { value: opened } });

    expect(String(saved.at(-1))).toBe('2026-01-15T23:30:00.000Z');
  });

  it('leaves a day-only value as the day it is, in any zone', () => {
    // A `date` column holds a day. Translating it into a zone is what moves it, and the input must
    // not do to it what the display side is careful not to do either.
    applyDisplayTimeZone(WEST);
    const saved: unknown[] = [];

    const input = edit('2026-01-15', 'date', (v) => saved.push(v));
    expect(input.value).toBe('2026-01-15');

    fireEvent.change(input, { target: { value: '2026-01-16' } });
    expect(String(saved[0])).toBe('2026-01-16');
  });

  it('opens a datetime column over a day-only value AT that day, not blank', () => {
    // A `datetime-local` accepts exactly one spelling and silently drops anything else, so handing
    // it the bare "2026-01-15" the column holds left the widget EMPTY. Nothing said why: the person
    // clicked a cell reading 15 Jan, got a blank field, retyped the whole date from memory, and a
    // mistyped year replaced a value that had been right. The display side of this exact shape is
    // already covered above ("keeps its day even when the column is typed datetime"); the edit side
    // was not, which is how the two disagreed.
    applyDisplayTimeZone(WEST);

    const input = edit('2026-01-15', 'datetime', () => {});

    expect(input.value).toBe('2026-01-15T00:00');
  });

  it('stores a typed time as a moment, so the cell can still read it afterwards', () => {
    // The `time` path stored the typed "09:00" verbatim. `new Date("09:00")` is an Invalid Date, so
    // from then on the cell printed the raw string, the widget opened blank on every later click (a
    // value with no `T` reads as a calendar day, whose time part is nothing), and nothing else could
    // read the column either. One edit made the cell permanently uneditable.
    //
    // 09:00 in Tokyo on the day the cell already holds is 00:00 UTC that same day.
    applyDisplayTimeZone('Asia/Tokyo');
    const saved: unknown[] = [];

    const input = edit('2026-01-15T23:30:00Z', 'time', (v) => saved.push(v));
    expect(input.value).toBe('08:30');

    fireEvent.change(input, { target: { value: '09:00' } });

    expect(String(saved[0])).toBe('2026-01-16T00:00:00.000Z');
  });

  it('round-trips a time column: reopening it shows the time that was typed', () => {
    // The property the verbatim store destroyed, asserted end to end rather than on the stored
    // string: save a time, feed what was saved back into a fresh widget, and read it again.
    applyDisplayTimeZone('Asia/Tokyo');
    const saved: unknown[] = [];

    const first = edit('2026-01-15T23:30:00Z', 'time', (v) => saved.push(v));
    fireEvent.change(first, { target: { value: '09:00' } });
    cleanup();

    const reopened = edit(saved[0], 'time', () => {});

    expect(reopened.value).toBe('09:00');
  });

  it('repairs a row the OLD write path corrupted, instead of re-corrupting it', () => {
    // The one value that could not be fixed through the UI was the one this code created. A bare
    // "09:00" is an Invalid Date, and the first version of the fix classified it as a calendar DAY
    // (its test was "no T and no space"): `anchorDay` sliced it for a date, built the nonsense
    // "09:00T10:15", got null back, and fell through to storing the typed string verbatim - the
    // exact corruption again, in the code written to end it. The widget also opened blank, so the
    // person could not even see what they were replacing.
    applyDisplayTimeZone('Asia/Tokyo');
    const saved: unknown[] = [];

    const input = edit('09:00', 'time', (v) => saved.push(v));

    // Shown, so the value being repaired is visible.
    expect(input.value).toBe('09:00');

    fireEvent.change(input, { target: { value: '10:15' } });

    // Stored as a real instant, anchored on today in the reader zone, so the row is readable again.
    expect(String(saved[0])).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00\.000Z$/);
    expect(wallClockIn(new Date(String(saved[0])), undefined, 'time')).toBe('10:15');
  });

  it('keeps the time of day when only the DATE is edited', () => {
    // A `date`-configured column can hold a real instant. Saving the bare day threw the clock away
    // for good, from an edit the person believes touched only the date. The mirror case (a day-only
    // value in a `datetime` column) was fixed and tested in the same pass; this direction was not,
    // which is how this branch keeps shipping half a pair.
    applyDisplayTimeZone('Asia/Tokyo');
    const saved: unknown[] = [];

    // 22:00Z on 15 Jan is 07:00 on the 16th in Tokyo.
    const input = edit('2026-01-15T22:00:00Z', 'date', (v) => saved.push(v));
    expect(input.value).toBe('2026-01-16');

    fireEvent.change(input, { target: { value: '2026-01-20' } });

    // The 20th at 07:00 Tokyo, which is 22:00Z on the 19th. The clock the reader saw is intact.
    expect(String(saved[0])).toBe('2026-01-19T22:00:00.000Z');
  });

  it('leaves a day-only value a bare day when the DATE is edited', () => {
    // And the other half of that rule: with no time to keep, there is no instant to invent.
    applyDisplayTimeZone('Asia/Tokyo');
    const saved: unknown[] = [];

    const input = edit('2026-01-15', 'date', (v) => saved.push(v));
    fireEvent.change(input, { target: { value: '2026-01-20' } });

    expect(String(saved[0])).toBe('2026-01-20');
  });

  it('opens a time column over a day-only value at the midnight the cell already shows', () => {
    // The display renders midnight for such a value. The widget used to open BLANK
    // here, on the reasoning that "midnight would be a value nobody entered" - true, and beside
    // the point: the cell had already shown them 00:00. Two answers about one value, on one screen,
    // is the defect this whole branch exists to close.
    applyDisplayTimeZone('Asia/Tokyo');

    const input = edit('2026-01-15', 'time', () => {});

    expect(input.value).toBe('00:00');

    // And the CELL says the same thing, which is the point: they used to disagree.
    cleanup();
    expect(show('2026-01-15', 'time')).toContain('00:00');
  });

  it.each([
    ['date', '2026-01-20', '2026-01-19T22:00:45.500Z'],
    ['datetime', '2026-01-20T07:00', '2026-01-19T22:00:45.500Z'],
    // A DIFFERENT time for this one: the widget already opens at 07:00, and jsdom does not fire
    // change for an identical value, so typing 07:00 would assert nothing.
    ['time', '08:15', '2026-01-15T23:15:45.500Z'],
  ])(
    'keeps the seconds and milliseconds of a timestamp when a %s cell is edited',
    (shape, typed, expected) => {
      // No input in this cell can express sub-minute precision: `wallClockIn` renders to the minute
      // and the `datetime-local` has no `step`. So every solved instant lands at :00.000, and an
      // edit that touched only the day - or only the minutes - rewrote two fields nobody looked at.
      //
      // All THREE shapes. This was fixed for `date` one round earlier, with a test whose comment
      // said the mirror case "was fixed and tested in the same pass; this direction was not". It was
      // then left half-applied in the other direction, which is the same shape of mistake, pointing
      // the other way.
      //
      // 22:00:45.500Z is 07:00:45.500 on the 16th in Tokyo, so each case types the same 07:00 and
      // the seconds have to survive it.
      applyDisplayTimeZone('Asia/Tokyo');
      const saved: unknown[] = [];

      const input = edit('2026-01-15T22:00:45.500Z', shape, (v) => saved.push(v));
      fireEvent.change(input, { target: { value: typed } });

      expect(String(saved[0])).toBe(expected);
    },
  );

  it('adds no sub-minute precision to a value that had none', () => {
    // The carry must be a no-op on a whole-minute instant, or every edit would start inventing
    // seconds that were never stored.
    applyDisplayTimeZone('Asia/Tokyo');
    const saved: unknown[] = [];

    const input = edit('2026-01-15T22:00:00.000Z', 'datetime', (v) => saved.push(v));
    fireEvent.change(input, { target: { value: '2026-01-16T09:00' } });

    expect(String(saved[0])).toBe('2026-01-16T00:00:00.000Z');
  });

  it('keeps the SECONDS of a timestamp when only the date is edited', () => {
    // The truncation the round before this one shipped under a comment saying the clock was left
    // alone: the anchor rendered `HH:mm` and the solver defaulted seconds to 0, so a stored instant
    // lost its seconds and milliseconds on an edit that touched neither. The test that was supposed
    // to cover it used a value whose seconds were already :00, so it could not see it.
    applyDisplayTimeZone('Asia/Tokyo');
    const saved: unknown[] = [];

    const input = edit('2026-01-15T22:00:45.500Z', 'date', (v) => saved.push(v));
    fireEvent.change(input, { target: { value: '2026-01-20' } });

    // 07:00:45.500 on the 20th in Tokyo, which is 22:00:45.500Z on the 19th.
    expect(String(saved[0])).toBe('2026-01-19T22:00:45.500Z');
  });

  it('reads a value with stray whitespace as the day it names', () => {
    // " 2026-01-15" is routine in imported data, and it used to be read as two different days one
    // line apart: the predicate trimmed and said "calendar day", so the display rendered it in UTC,
    // while the parser did not trim, fell out of V8's ISO fast path into the LOCAL-time parser, and
    // produced the 14th at 23:00Z on a +01:00 host. The widget then sliced ten characters off an
    // eleven-character string.
    //
    // Asserted on the WIDGET, not on the rendered day, and the reason is worth stating rather than
    // papering over: this suite runs with the process in UTC, where the padded form parses to the
    // same instant as the clean one, so the DISPLAY half of the defect cannot fail any assertion in
    // this file. Confirmed by hand in a +01:00 process instead, and fixed by trimming in the parser.
    // What is guarded here is every part of it the widget can see.
    applyDisplayTimeZone(WEST);

    const input = edit(' 2026-01-15', 'date', () => {});

    expect(input.value).toBe('2026-01-15');
  });

  it('does not turn a padded value back into an unreadable one on save', () => {
    // The failure that made the padding worth fixing rather than tolerating: the `time` path built
    // " 2026-01-1T09:00" from the sliced string, the solver returned null, and the fallback stored
    // the bare "09:00" - the exact unreadable row this file exists to stop producing, written by the
    // code that repairs it.
    applyDisplayTimeZone('Asia/Tokyo');
    const saved: unknown[] = [];

    const input = edit(' 2026-01-15', 'time', (v) => saved.push(v));
    fireEvent.change(input, { target: { value: '09:00' } });

    expect(String(saved[0])).toBe('2026-01-15T00:00:00.000Z');
  });

  it('offers the legacy time for repair in a datetime column too, not only a time one', () => {
    // The recovery was guarded on `shape === 'time'` while its docblock claimed the whole class, so
    // the same broken value in a `datetime` column still opened a blank widget - the "retypes from
    // memory over a value that was right" failure, in the two shapes the fix did not reach.
    applyDisplayTimeZone('Asia/Tokyo');
    const saved: unknown[] = [];

    const input = edit('09:00', 'datetime', (v) => saved.push(v));

    // Today in the reader zone, at the time that was stored: the day is invented because the value
    // carries none, and it is shown rather than guessed at silently.
    expect(input.value).toMatch(/^\d{4}-\d{2}-\d{2}T09:00$/);

    fireEvent.change(input, { target: { value: `${input.value.slice(0, 11)}10:15` } });
    expect(String(saved[0])).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00\.000Z$/);
  });

  it('opens a widget for a value that arrived already parsed, not a blank one', () => {
    // A row can arrive with a `Date` in it: `value` is `any`. The display branch renders it fine,
    // because the formatters accept one - and the edit widget opened EMPTY, because the helper added
    // to trim strings answered the empty string for everything else. That is the "retypes the whole
    // date from memory over a value that was right" failure this file exists to close, reintroduced
    // for one input class by a fix for another.
    applyDisplayTimeZone('Asia/Tokyo');

    const input = edit(new Date('2026-01-15T23:30:00Z'), 'datetime', () => {});

    expect(input.value).toBe('2026-01-16T08:30');
  });

  it('anchors a typed time on the day the value already has, not on today', () => {
    // The date is invisible in a `time` column, which is exactly why it must not be invented: the
    // row keeps whatever day it had, so a time edit stays an edit to the time.
    applyDisplayTimeZone('Asia/Tokyo');
    const saved: unknown[] = [];

    const input = edit('2026-03-20T23:30:00Z', 'time', (v) => saved.push(v));
    fireEvent.change(input, { target: { value: '10:15' } });

    // 10:15 on 21 March in Tokyo, the day the stored instant falls on there, is 01:15 UTC.
    expect(String(saved[0])).toBe('2026-03-21T01:15:00.000Z');
  });
});

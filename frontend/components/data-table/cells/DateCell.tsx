'use client';

import React from 'react';
import { Calendar } from 'lucide-react';
import type { VisualCellProps } from './types';
import {
  formatUtcDate,
  formatUtcDateTime,
  formatUtcTime,
  instantOfWallClock,
  isCalendarDateOnly,
  parseUtcAware,
  wallClockIn,
} from '@/lib/utils/dateFormatters';

/**
 * True when the stored value names a DAY rather than a moment.
 *
 * <p>The shared predicate, not a local copy. This cell used to carry its own byte-identical
 * "no T and no space" version, and the whole feature turns on this one question: the display side
 * asks the shared one to decide whether to translate a value into the reader's zone, and the edit
 * widget asked its own. Two answers to that question on one screen is the defect the branch
 * exists to close, so they must be the same function.
 */
const isCalendarDay = (value: unknown): boolean =>
  isCalendarDateOnly(typeof value === 'string' ? value : null);

/**
 * The stored value as a trimmed string, which is the form every rule below is written for.
 *
 * <p>`isCalendarDateOnly` trims and the slices here did not, so " 2026-01-15" was classified as a
 * day and then sliced to " 2026-01-1": a `date` input rejects that and opens blank, and the `time`
 * path built " 2026-01-1T09:00", got null from the solver, and fell through to storing the bare
 * "09:00" - recreating the unreadable legacy row this file was changed to stop producing.
 */
const asText = (value: unknown): string => {
  if (typeof value === 'string') return value.trim();
  // A `Date` becomes its ISO form rather than nothing.
  //
  // `value` is `any`, and a row that arrives already parsed displayed correctly (the formatters take
  // a Date) while the edit widget opened EMPTY, because this returned the empty string for it. That
  // is the "retypes the whole date from memory over a value that was right" failure this file exists
  // to remove, reintroduced for one input class by the helper added to fix a different one.
  if (value instanceof Date && !isNaN(value.getTime())) return value.toISOString();
  return '';
};

/**
 * A bare `HH:mm` left behind by the pre-fix write path, which stored what was typed.
 *
 * <p>Those rows exist. `new Date("09:00")` is an Invalid Date, so the cell prints the raw string
 * and the widget has nothing to parse; recognising the shape lets it open showing the time that is
 * there, and a save then anchors it on a real day and the row stops being a dead end. Without
 * this, the only value that could not be repaired through the UI was the one this code created.
 */
const LEGACY_TIME = /^\d{2}:\d{2}(:\d{2})?$/;

/**
 * The day a typed time belongs to, read in the same zone the cell displays.
 *
 * <p>A `time` input hands back "08:30" and nothing else, so the date has to come from somewhere.
 * It comes from the value already in the cell: the day that value falls on for this reader, which
 * is the day they are looking at while they type. A cell with nothing usable in it - empty, or one
 * of the bare `HH:mm` rows the old write path left - falls back to today in that same zone, which
 * is the only day a person typing a time can mean.
 */
function anchorDay(existing: unknown): string {
  // A bare YYYY-MM-DD is taken as written rather than parsed and re-rendered: parsing it yields
  // UTC midnight, which is the PREVIOUS day for every reader west of Greenwich.
  const text = asText(existing);
  if (isCalendarDay(text)) return text;
  if (text !== '') {
    const d = parseUtcAware(text);
    if (!isNaN(d.getTime())) return wallClockIn(d, undefined, 'date');
  }
  return wallClockIn(new Date(), undefined, 'date');
}

/**
 * The time of day to keep when only the DATE was edited, read in the reader's zone.
 *
 * <p>The mirror of {@link anchorDay}, and it was missing. A `date`-configured column can hold a
 * real instant, and saving the day alone threw the time away for good - from an edit the person
 * believes touched only the date. The opposite case (a day-only value in a `datetime` column) got
 * both a fix and a test in the same pass; this direction got neither, which is the shape this
 * branch keeps producing. Null when there is no time to keep, and the bare day is then correct.
 */
function anchorTime(existing: unknown): string | null {
  const text = asText(existing);
  if (text === '' || isCalendarDay(text)) return null;
  const d = parseUtcAware(text);
  if (isNaN(d.getTime())) return null;
  // Seconds included, and the milliseconds are added back by the caller. `wallClockIn` renders to
  // the minute, so returning it alone truncated a stored instant to :00.000 on an edit that only
  // touched the date - while the docblock above said the clock was left alone. A quarter of a
  // minute is not the offset-sized loss this file was written for, but it is still a silent write.
  const clock = wallClockIn(d, undefined, 'time');
  return `${clock}:${String(secondsIn(d)).padStart(2, '0')}`;
}

/**
 * The seconds the instant reads at, which are the same in every zone: no offset in the database is
 * a fraction of a minute (the last one, Liberia, ended in 1972).
 */
function secondsIn(instant: Date): number {
  return instant.getUTCSeconds();
}

/**
 * What to store for a wall clock the person just typed.
 *
 * <p>A `date` input names a day. When the cell held nothing more than a day, that day is stored as
 * itself, with no zone applied: there is no instant to translate. When it held a real instant, the
 * typed day is recombined with the time that instant reads at for this reader, so editing the date
 * of a timestamp moves the date and leaves the clock alone. A `datetime-local` input names a moment
 * in the READER's zone, so it is converted to the instant that wall clock corresponds to there.
 * Storing it verbatim would be read back as UTC and shift by the whole offset.
 *
 * <p>A `time` input is converted TOO, anchored on {@link anchorDay}. Storing it as typed was a
 * dead end in both directions: `new Date("08:30")` is an Invalid Date, so the cell fell through
 * to printing the raw string, the edit widget then opened EMPTY every time (a value with no `T`
 * reads as a calendar day, whose time part is nothing), and no sort, filter or other renderer
 * could read the column again. One edit made the cell permanently uneditable. The date does not
 * appear anywhere in a `time` column, so anchoring it is invisible to the reader; what it buys is
 * a value that is still a moment, and a time that reads back as the one that was typed.
 */
function toStoredValue(typed: string, shape: 'date' | 'time' | 'datetime', existing: unknown): string {
  if (typed === '') return typed;

  // The wall clock to solve: the typed value, plus whatever part of the old one the person did not
  // touch. A `date` edit keeps the time; a `time` edit keeps the day; a `datetime` edit keeps
  // neither, because the input carries both.
  const wallClock = shape === 'date'
    ? withKeptTime(typed, existing)
    : shape === 'time' ? `${anchorDay(existing)}T${typed}` : typed;
  if (wallClock === null) return typed;

  const solved = instantOfWallClock(wallClock);
  if (solved === null) return typed;

  // SECONDS AND MILLISECONDS of the old value, for every shape.
  //
  // No input and no wall-clock string carries sub-minute precision: `wallClockIn` renders to the
  // minute and `datetime-local` without a `step` emits nothing finer. So the solved instant is
  // always at :00.000, and an edit that touched only the day (or only the minutes) silently
  // rewrote two fields nobody looked at.
  //
  // This was fixed for the `date` shape one round earlier, with a test whose own comment says the
  // mirror case "was fixed and tested in the same pass; this direction was not". It was then left
  // half-applied in the other direction: `datetime` and `time` still zeroed both. Carried here,
  // once, for all three.
  return withSubMinute(solved, existing, shape);
}

/** The typed day, plus the time the existing value reads at, or null when there is no time to keep. */
function withKeptTime(typedDay: string, existing: unknown): string | null {
  const keep = anchorTime(existing);
  return keep === null ? null : `${typedDay}T${keep}`;
}

/**
 * Put back the seconds and milliseconds of the value being edited, which no input can express.
 *
 * <p>Only for a shape that did not ASK about them. A `time` input shows `HH:mm`, so its seconds are
 * not the reader's to change either; the same holds for the minute-precision `datetime-local` this
 * cell renders. If one of these ever gains a `step` that exposes seconds, this has to stop for that
 * shape, or it would overwrite what somebody typed.
 */
function withSubMinute(solvedIso: string, existing: unknown, shape: 'date' | 'time' | 'datetime'): string {
  const text = asText(existing);
  if (text === '' || isCalendarDay(text)) return solvedIso;
  const previous = parseUtcAware(text);
  if (isNaN(previous.getTime())) return solvedIso;
  const carry = previous.getUTCSeconds() * 1000 + previous.getUTCMilliseconds();
  if (carry === 0) return solvedIso;
  // `anchorTime` already carried the seconds into the wall clock for the `date` shape, so only the
  // milliseconds are still missing there; the other two shapes are missing both.
  const alreadyCarried = shape === 'date' ? previous.getUTCSeconds() * 1000 : 0;
  return new Date(new Date(solvedIso).getTime() + carry - alreadyCarried).toISOString();
}

/**
 * What to put in the input for a stored value that names a DAY and nothing more.
 *
 * <p>Each input type accepts exactly one spelling and silently drops anything else, which is how
 * this went unnoticed: a `datetime-local` handed "2026-01-15" sanitises it to the empty string,
 * so a `datetime` column over a day-only value opened a BLANK widget, the person typed the date
 * again from scratch, and a mistyped year replaced a value that had been correct.
 */
function dayOnlyInputValue(day: string, shape: 'date' | 'time' | 'datetime'): string {
  if (shape === 'date') return day;
  // Midnight in both remaining shapes, which is what the CELL shows for such a value
  // (`formatUtcTime('2026-01-15')` renders 00:00). The widget used to open blank for the `time`
  // shape on the grounds that "midnight would be a value nobody entered" - true, and beside the
  // point: the cell had already shown them 00:00, so refusing to put it in the input made the
  // display and the editor disagree about the same value, on the same screen.
  return `${day}T00:00`.slice(shape === 'datetime' ? 0 : 11);
}

export function DateCell({ value, displayConfig, isEditing, onSaveAndExit }: VisualCellProps) {
  const dateFormat = (displayConfig?.dateFormat as string) || 'date';

  if (isEditing) {
    const inputType = dateFormat === 'time' ? 'time' : dateFormat === 'datetime' ? 'datetime-local' : 'date';

    // The widget shows the SAME zone the cell displays, and the value it hands back is read as
    // that zone too.
    //
    // Both used to be UTC, which agreed with a UTC display. Once the display followed the
    // account, a Tokyo reader saw "Jan 16, 08:30", clicked, and got an unlabelled input
    // reading 2026-01-15T23:30. Correcting it toward the 08:30 they had just been shown sent a
    // bare wall clock that `parseUtcAware` reads as UTC, moving the stored instant by the whole
    // offset: nine hours, silently, from an edit that looked like a no-op. A round-trip that is
    // only lossless for somebody who does not touch the field is not a round-trip.
    const shape = inputType === 'time' ? 'time' : inputType === 'date' ? 'date' : 'datetime';
    let inputValue = '';
    const text = asText(value);
    if (text !== '') {
      try {
        const d = parseUtcAware(text);
        if (!isNaN(d.getTime())) {
          // A bare YYYY-MM-DD names a day, so it is shown as itself rather than translated - the
          // same rule the display side follows.
          inputValue = isCalendarDay(text)
            ? dayOnlyInputValue(text, shape)
            : wallClockIn(d, undefined, shape);
        } else if (LEGACY_TIME.test(text)) {
          // Not parseable as an instant, and the reason is us: see LEGACY_TIME. Showing what is
          // stored is the only way the person can see the value they are about to repair.
          //
          // All three shapes, not just `time`. The guard used to read `shape === 'time'`, so the
          // same broken value in a `datetime` column still opened a blank widget - and the
          // docblock claimed "the only value that could not be repaired through the UI was the one
          // this code created", which was then still true for two shapes out of three.
          const clock = text.slice(0, 5);
          inputValue = shape === 'date'
            // A time names no day, so there is nothing to put in a date input. Blank is honest here
            // and a save still repairs the row, because the typed day is all that is stored.
            ? ''
            : shape === 'time' ? clock : `${wallClockIn(new Date(), undefined, 'date')}T${clock}`;
        }
      } catch {
        inputValue = text;
      }
    }

    return (
      <input
        type={inputType}
        defaultValue={inputValue}
        autoFocus
        className="w-full rounded-md border border-theme bg-theme-primary px-2 py-1 text-sm text-theme-primary"
        onChange={(event) => onSaveAndExit(toStoredValue(event.currentTarget.value, shape, value))}
        onClick={(e) => e.stopPropagation()}
      />
    );
  }

  if (!value) {
    return <span className="text-xs text-theme-secondary">No date</span>;
  }

  let formatted = String(value);
  try {
    const d = parseUtcAware(value as string);
    if (!isNaN(d.getTime())) {
      // The formatters are handed the ORIGINAL value, not `d`. They render a timestamp in the
      // reader's zone but keep a bare `YYYY-MM-DD` in UTC, because a day is the same day
      // everywhere - and that decision is made by looking at the STRING, which `d` has thrown
      // away. A `date` column holds exactly those bare days, so passing the parsed value read
      // one day early for every reader west of Greenwich: "2026-01-15" showed as Jan 14.
      // `d` stays as the validity check, which is all it was ever good for here.
      //
      // The edit widget reads and writes the SAME zone now (see the isEditing branch above), so a
      // value retyped as it was displayed stores the instant it looks like. It used to be UTC while
      // this side followed the account, which meant correcting 23:30 to the 08:30 just shown moved
      // the stored instant by the whole offset.
      if (dateFormat === 'time') {
        formatted = formatUtcTime(value as string);
      } else if (dateFormat === 'datetime') {
        formatted = formatUtcDateTime(value as string);
      } else {
        formatted = formatUtcDate(value as string);
      }
    }
  } catch {
    // keep raw string
  }

  return (
    <div className="flex items-center justify-center gap-1.5 text-sm text-theme-primary">
      <Calendar className="h-3.5 w-3.5 text-theme-secondary" />
      <span>{formatted}</span>
    </div>
  );
}

/**
 * @vitest-environment jsdom
 *
 * The absolute formatters render in the READER's display zone, and print the clock alone.
 *
 * A separate file from dateFormatters.test.ts because this one needs a `window` (the display
 * zone comes from the applied preference / cookie / browser, none of which exist in the suite's
 * default `node` environment). That environment is also why the older file kept passing
 * untouched when the formatters stopped forcing UTC - it only ever saw the SSR fallback, which
 * is still UTC. Both facts are worth pinning, so both files stay.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
  dayEdgeInstant,
  formatCalendarDate,
  formatUtcDate,
  formatUtcDateOrNull,
  formatUtcDateTime,
  formatUtcTime,
  instantOfWallClock,
  wallClockIn,
} from '../dateFormatters';
import { applyDisplayTimeZone, clearDisplayTimeZone } from '../timezone';

/** 14:30 UTC in mid-January: 15:30 in Paris (standard time), 23:30 in Tokyo. */
const WINTER = '2026-01-21T14:30:00Z';
/** 14:30 UTC in July: 16:30 in Paris (daylight saving). */
const SUMMER = '2026-07-21T14:30:00Z';

beforeEach(() => clearDisplayTimeZone());
afterEach(() => clearDisplayTimeZone());

describe('the display zone decides what a timestamp reads', () => {
  it('renders the wall clock of the applied zone, not UTC', () => {
    applyDisplayTimeZone('Europe/Paris');

    const out = formatUtcDateTime(WINTER, { locale: 'en' });

    expect(out).toContain('15:30');
    expect(out).not.toContain('14:30');
  });

  it('appends nothing after the time, which is what let a label overflow a fixed slot', () => {
    applyDisplayTimeZone('Europe/Paris');

    // The regression this pins: these helpers used to append the zone label to every instant,
    // and the run panel's epoch rows size their two time slots for `HH:mm:ss` exactly. A
    // `15:30:07 GMT+2` therefore rendered over the arrow between the start and end times. The
    // guard is on the SHAPE rather than on one string, so any future addition trips it.
    expect(formatUtcTime(WINTER, { locale: 'en' })).toMatch(/^\d{2}:\d{2}$/);
    expect(formatUtcTime(WINTER, { locale: 'en', withSeconds: true })).toMatch(/^\d{2}:\d{2}:\d{2}$/);
    expect(formatUtcDateTime(WINTER, { locale: 'en' })).toMatch(/^Jan 21, 2026, \d{2}:\d{2}$/);
    // The shape a data-table cell renders, which the guard first missed.
    expect(formatUtcDateTime(WINTER, { locale: 'en', withSeconds: true }))
      .toMatch(/^Jan 21, 2026, \d{2}:\d{2}:\d{2}$/);
    expect(formatUtcDate(WINTER, { locale: 'en' })).toMatch(/^Jan 21, 2026$/);
    // formatCalendarDate too: the assertion that used to pin its lack of a label was removed as
    // vacuous once the others stopped carrying one, which left the only helper that must NEVER
    // name a zone as the only one nothing checked.
    expect(formatCalendarDate('2026-01-15', { locale: 'en' })).toMatch(/^Jan 15, 2026$/);
  });

  it('follows daylight saving within the same zone, in the clock itself', () => {
    applyDisplayTimeZone('Europe/Paris');

    // Both instants are 14:30 UTC. The hour shown differs because the offset differs, which is
    // the whole of what the reader needs: the shift is IN the time, it never needed a label
    // beside it saying which offset produced it.
    expect(formatUtcTime(WINTER, { locale: 'en' })).toBe('15:30');
    expect(formatUtcTime(SUMMER, { locale: 'en' })).toBe('16:30');
  });

  it('shows a reader on UTC the stored instant itself, unadorned', () => {
    applyDisplayTimeZone('UTC');

    expect(formatUtcDateTime(WINTER, { locale: 'en' })).toBe('Jan 21, 2026, 14:30');
    expect(formatUtcTime(WINTER, { locale: 'en' })).toBe('14:30');
  });

  it('can put a date-only value on another calendar day, which is the point of the zone', () => {
    applyDisplayTimeZone('Pacific/Auckland');

    // 2026-01-21T14:30Z is already the 22nd in Auckland (+13 in January).
    expect(formatUtcDate(WINTER, { locale: 'en' })).toContain('22');
  });
});

describe('a CALENDAR DATE is not a moment, and is never moved into a zone', () => {
  // The regression this class of test exists for: flipping ~250 call sites from UTC to the
  // reader's zone also moved values that name a DAY. A daily bucket from an analytics query, or a
  // release date, arrives as a bare `YYYY-MM-DD`, becomes UTC midnight, and reads as the PREVIOUS
  // day anywhere west of Greenwich. Every chart axis in the Americas would have been off by one,
  // silently.
  const DAY = '2026-01-15';

  it('a bare YYYY-MM-DD keeps its day for a reader west of UTC', () => {
    applyDisplayTimeZone('America/Los_Angeles');

    expect(formatUtcDate(DAY, { locale: 'en' })).toContain('15');
    expect(formatUtcDate(DAY, { locale: 'en' })).not.toContain('14');
  });

  it('and for a reader far east of it, where the naive answer moves the other way', () => {
    applyDisplayTimeZone('Pacific/Auckland');

    expect(formatUtcDate(DAY, { locale: 'en' })).toContain('15');
    expect(formatUtcDate(DAY, { locale: 'en' })).not.toContain('16');
  });

  it('formatCalendarDate says the same for a caller that already made it midnight', () => {
    // Auto-detection cannot see this one: the caller turned its day into an instant first.
    applyDisplayTimeZone('America/Los_Angeles');

    expect(formatCalendarDate(`${DAY}T00:00:00Z`, { locale: 'en' })).toContain('15');
    expect(formatCalendarDate(new Date(`${DAY}T00:00:00Z`), { locale: 'en' })).toContain('15');
  });

  it("does not let the reader's zone move the day, which is the whole contract", () => {
    applyDisplayTimeZone('America/Los_Angeles');

    // This asserted the ABSENCE of a zone label, which every helper now satisfies, so it had
    // stopped distinguishing anything. What a bare `YYYY-MM-DD` owes is that no zone touches it:
    // eight hours west of Greenwich is where a day-only value would slide to the day before,
    // thirteen hours east is where it would slide to the day after. Both named, because a
    // positive-only assertion passes on a formatter that prints the whole month.
    expect(formatCalendarDate(DAY, { locale: 'en' })).toContain('15');
    expect(formatCalendarDate(DAY, { locale: 'en' })).not.toContain('14');
    applyDisplayTimeZone('Pacific/Auckland');
    expect(formatCalendarDate(DAY, { locale: 'en' })).toContain('15');
    expect(formatCalendarDate(DAY, { locale: 'en' })).not.toContain('16');
  });

  it('still answers the fallback for a nullish or unreadable value', () => {
    expect(formatCalendarDate(null)).toBe('-');
    expect(formatCalendarDate('garbage', { fallback: 'n/a' })).toBe('n/a');
  });

  it('a value WITH a time of day is still a moment, and still follows the reader', () => {
    // The distinction has to cut both ways, or the fix would freeze every timestamp at UTC.
    applyDisplayTimeZone('Asia/Tokyo');

    expect(formatUtcDate('2026-01-15T22:30:00Z', { locale: 'en' })).toContain('16');
  });
});

describe('a pinned zone, which is the one case that still names itself', () => {
  it("moves the clock AND names the zone, because the reader did not choose it", () => {
    applyDisplayTimeZone('Europe/Paris');

    // A schedule trigger fires in the zone stored ON the trigger, so its next run is drawn
    // there. 14:30 UTC is 23:30 in Tokyo - and a reader in Paris seeing a bare 23:30 has no way
    // to tell it from 23:30 of their own evening. The label is what stops them checking it
    // against their own clock and concluding the schedule is broken.
    expect(formatUtcTime(WINTER, { locale: 'en', timeZone: 'Asia/Tokyo' })).toBe('23:30 GMT+9');
    expect(formatUtcDateTime(WINTER, { locale: 'en', timeZone: 'Asia/Tokyo' }))
      .toBe('Jan 21, 2026, 23:30 GMT+9');
  });

  it('says nothing when the pin names the zone the reader is already on', () => {
    applyDisplayTimeZone('Europe/Paris');

    // The common case for a schedule created where its owner sits. The pin is real and in
    // effect, but the zone IS their setting, so labelling it is the restatement this avoids.
    expect(formatUtcTime(WINTER, { locale: 'en', timeZone: 'Europe/Paris' })).toBe('15:30');
  });

  it('says nothing when the pin is a legacy ALIAS of the reader zone', () => {
    applyDisplayTimeZone('Asia/Kolkata');

    // Stored zone ids outlive their names. Compared canonically, or an alias would read as a
    // foreign zone and print an offset the reader set themselves - by the back door.
    expect(formatUtcTime(WINTER, { locale: 'en', timeZone: 'Asia/Calcutta' }))
      .toMatch(/^\d{2}:\d{2}$/);
  });

  it('falls back to the display zone when the pinned value is unusable, and names nothing', () => {
    applyDisplayTimeZone('Europe/Paris');

    // A zone id from stored data can be a legacy alias or plain wrong. Throwing inside a
    // render would take the page down over one row's metadata. And naming the pin here would
    // name a zone nothing was rendered in, which is worse than naming none.
    expect(formatUtcTime(WINTER, { locale: 'en', timeZone: 'Mars/Base' })).toBe('15:30');
  });

  it('treats the UTC spellings as one zone, which is the likelier stored alias', () => {
    applyDisplayTimeZone('UTC');

    // `UTC`, `Etc/UTC` and `GMT` are links: a reader on one and a pin on another is the same
    // zone spelled twice, and this is far commoner in stored data than a city alias.
    expect(formatUtcTime(WINTER, { locale: 'en', timeZone: 'Etc/UTC' })).toBe('14:30');
    expect(formatUtcTime(WINTER, { locale: 'en', timeZone: 'GMT' })).toBe('14:30');
  });

  it('still labels an OFFSET pin that matches the reader zone, and that is accepted', () => {
    applyDisplayTimeZone('Asia/Kolkata');

    // `+05:30` is not a link to `Asia/Kolkata`, so it does not compare equal and a label prints.
    // Recorded rather than fixed: the offset genuinely is a different thing from the zone (it
    // carries no rules, so it cannot follow a future change), and the failure is one redundant
    // label rather than a missing or wrong one. A test says so, so nobody reads it as a bug.
    expect(formatUtcTime(WINTER, { locale: 'en', timeZone: '+05:30' })).toMatch(/^\d{2}:\d{2} .+/);
  });

  it('puts the label where the LOCALE puts it, which is why it is not a suffix', () => {
    applyDisplayTimeZone('Europe/Paris');

    // The reason the label is read off `formatToParts` rather than appended: zh renders the zone
    // BEFORE the time. Appending would have printed it twice over, or in the wrong place, in the
    // one locale nobody on this team reads. English is the only locale the other assertions use.
    const zh = formatUtcTime(WINTER, { locale: 'zh', timeZone: 'Asia/Tokyo' });

    expect(zh).toContain('23:30');
    expect(zh).toMatch(/GMT|UTC/);
    // Once, not twice, wherever the locale decided to put it.
    expect(zh.match(/GMT|UTC/g)).toHaveLength(1);
  });

  it('names nothing on a bare calendar day, pin or no pin', () => {
    applyDisplayTimeZone('Europe/Paris');

    // `displayZoneFor` ignores a pin for a day-only value on purpose: pinning asks how one
    // INSTANT reads somewhere, and the 15th is the 15th. A label would advertise a conversion
    // that did not happen.
    expect(formatUtcDate('2026-01-15', { locale: 'en', timeZone: 'Asia/Tokyo' }))
      .toBe('Jan 15, 2026');
  });
});

describe('the locale still decides the words', () => {
  it('formats the same instant and zone in the app locale', () => {
    applyDisplayTimeZone('Europe/Paris');

    expect(formatUtcDate(WINTER, { locale: 'fr' })).toContain('janv.');
    expect(formatUtcDate(WINTER, { locale: 'en' })).toContain('Jan');
  });
});

describe('the fallbacks are unchanged by the zone work', () => {
  it('still answers the fallback for a nullish or unreadable value', () => {
    applyDisplayTimeZone('Europe/Paris');

    expect(formatUtcDateTime(null)).toBe('-');
    expect(formatUtcDate(undefined, { fallback: 'n/a' })).toBe('n/a');
    expect(formatUtcTime('garbage', { fallback: 'n/a' })).toBe('n/a');
  });
});

describe('a value that names a DAY rather than a moment', () => {
  // A bare `YYYY-MM-DD` has no time of day to translate, so translating it can only move it to
  // another day. Los Angeles is the case that shows it: UTC midnight is 16:00 the day BEFORE.
  const WEST = 'America/Los_Angeles';

  it('keeps its day in every absolute formatter, not only the date one', () => {
    applyDisplayTimeZone(WEST);

    // The rule used to live in formatUtcDate alone, so the same value read Jan 15 in one column
    // and Jan 14 in the next - on the same screen, out of the same string.
    expect(formatUtcDate('2026-01-15')).toContain('15');
    expect(formatUtcDate('2026-01-15')).not.toContain('14');
    expect(formatUtcDateTime('2026-01-15')).toContain('15');
    expect(formatUtcDateTime('2026-01-15')).not.toContain('14');
    expect(formatUtcTime('2026-01-15')).toContain('00:00');
  });

  it('keeps its day even when a caller pins a zone', () => {
    // Pinning answers "how does this INSTANT read over there", which a day is not. Honouring it
    // could only produce a different day, never a better answer.
    expect(formatUtcDate('2026-01-15', { timeZone: WEST })).toContain('15');
    expect(formatUtcDate('2026-01-15', { timeZone: WEST })).not.toContain('14');
  });

  it('does NOT apply to a value that carries a time, which is a real instant', () => {
    applyDisplayTimeZone(WEST);

    // Same calendar date in the string, but this one is a moment: 02:00 UTC IS the previous
    // evening in Los Angeles, and hiding that would be the opposite error.
    expect(formatUtcDate('2026-01-15T02:00:00Z')).toContain('14');
  });
});

describe('dayEdgeInstant', () => {
  it('gives the reader their own midnight, not UTC midnight', () => {
    applyDisplayTimeZone('America/Los_Angeles');

    // Los Angeles is UTC-8 in January, so their day starts at 08:00 UTC and ends just before
    // 08:00 the next morning. Filtering on UTC bounds instead drops the last 8 hours of their
    // day - the rows most likely to be the ones they are looking for.
    expect(dayEdgeInstant('2026-01-15', 'start')).toBe('2026-01-15T08:00:00.000Z');
    expect(dayEdgeInstant('2026-01-15', 'end')).toBe('2026-01-16T07:59:59.999Z');
  });

  it('follows that zone into daylight saving, where the offset is different', () => {
    applyDisplayTimeZone('America/Los_Angeles');

    // July: UTC-7, one hour less than January. A hardcoded offset would be wrong for half the year.
    expect(dayEdgeInstant('2026-07-15', 'start')).toBe('2026-07-15T07:00:00.000Z');
  });

  it('handles a zone AHEAD of UTC, where the day starts the previous UTC evening', () => {
    applyDisplayTimeZone('Asia/Tokyo');

    expect(dayEdgeInstant('2026-01-15', 'start')).toBe('2026-01-14T15:00:00.000Z');
    expect(dayEdgeInstant('2026-01-15', 'end')).toBe('2026-01-15T14:59:59.999Z');
  });

  it('handles a zone on a half-hour offset', () => {
    applyDisplayTimeZone('Asia/Kolkata');

    expect(dayEdgeInstant('2026-01-15', 'start')).toBe('2026-01-14T18:30:00.000Z');
  });

  it('gives UTC bounds to a reader on UTC, unchanged from the old behaviour', () => {
    applyDisplayTimeZone('UTC');

    expect(dayEdgeInstant('2026-01-15', 'start')).toBe('2026-01-15T00:00:00.000Z');
    expect(dayEdgeInstant('2026-01-15', 'end')).toBe('2026-01-15T23:59:59.999Z');
  });

  it('is exact in a zone whose spring-forward leaves midnight alone', () => {
    // 2026-03-08 in Los Angeles: 02:00 never happens, but midnight does, so the two-pass solve is
    // already right. Renamed from a name claiming to cover "the morning a zone skips an hour",
    // which this case does not exercise: the hour it skips is not the one being solved for.
    applyDisplayTimeZone('America/Los_Angeles');

    expect(dayEdgeInstant('2026-03-08', 'start')).toBe('2026-03-08T08:00:00.000Z');
  });

  it('does not fall into the previous day where MIDNIGHT itself is skipped', () => {
    // Santiago springs forward at 24:00, so 2026-09-06 00:00 local does not exist. The two-pass
    // solve answers 23:00 on the FIFTH, which puts an hour of the wrong day inside a filter asked
    // for "from the 6th" - the reader gets rows they did not ask for, once a year, with nothing on
    // screen to explain them. Asserted as "the bound is IN the requested day", not as a fixed
    // instant, because the correct instant is the transition and that is the zone's business.
    for (const [zone, day] of [
      ['America/Santiago', '2026-09-06'],
      ['America/Havana', '2026-03-08'],
    ] as const) {
      const bound = dayEdgeInstant(day, 'start', zone);

      expect(bound, `${zone} ${day}`).not.toBeNull();
      const local = new Intl.DateTimeFormat('en-CA', {
        timeZone: zone,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
      }).format(new Date(bound as string));
      expect(local, `${zone}: the start bound must be in ${day}`).toBe(day);
    }
  });

  it('still starts a normal day at that zone midnight exactly', () => {
    // The walk-forward must not nudge a day that was already right: it runs only while the local
    // date is still behind, and for every ordinary day that is false on the first check.
    expect(dayEdgeInstant('2026-09-10', 'start', 'America/Santiago'))
      .toBe('2026-09-10T03:00:00.000Z');
  });

  it('answers null for anything that is not a bare day, so a caller sends no bound at all', () => {
    applyDisplayTimeZone('Asia/Tokyo');

    expect(dayEdgeInstant('', 'start')).toBeNull();
    expect(dayEdgeInstant(null, 'start')).toBeNull();
    expect(dayEdgeInstant(undefined, 'end')).toBeNull();
    expect(dayEdgeInstant('15/01/2026', 'start')).toBeNull();
    expect(dayEdgeInstant('2026-01-15T10:00:00Z', 'start')).toBeNull();
  });

  it('answers null for a day that does not exist rather than rolling into the next month', () => {
    // `Date.UTC(2026, 1, 30)` is 2 March, silently. A filter asked for a day that never happened
    // would then return a range starting two days later, and the reader would see rows they did
    // not ask for with nothing on screen to explain them.
    //
    // Asserted as null, not as "does not contain '2026-02-30'": that spelling passed BECAUSE the
    // value had rolled over, so it certified the bug it was written to catch.
    expect(dayEdgeInstant('2026-02-30', 'start')).toBeNull();
    expect(dayEdgeInstant('2026-13-01', 'start')).toBeNull();
    expect(dayEdgeInstant('2026-00-10', 'start')).toBeNull();
    expect(dayEdgeInstant('2027-02-29', 'end')).toBeNull();
  });

  it('accepts 29 February in a leap year, which is a real day', () => {
    // The guard has to reject an impossible day without rejecting an unusual one.
    applyDisplayTimeZone('UTC');

    expect(dayEdgeInstant('2028-02-29', 'start')).toBe('2028-02-29T00:00:00.000Z');
  });

  it('honours an explicitly pinned zone over the reader\'s', () => {
    applyDisplayTimeZone('Asia/Tokyo');

    expect(dayEdgeInstant('2026-01-15', 'start', 'UTC')).toBe('2026-01-15T00:00:00.000Z');
  });
});

describe('formatUtcDateOrNull', () => {
  const WEST = 'America/Los_Angeles';

  it('keeps a bare day on its day, like its non-null twin', () => {
    applyDisplayTimeZone(WEST);

    // It delegates to formatUtcDate, and the only thing that can break that is handing over a
    // PARSED value: the calendar-date rule reads the string, so a Date arrives indistinguishable
    // from an instant at midnight. That is what it used to do, which made this the one helper
    // where a model's release date still read a day early west of Greenwich.
    expect(formatUtcDateOrNull('2026-01-15')).toContain('15');
    expect(formatUtcDateOrNull('2026-01-15')).not.toContain('14');
    expect(formatUtcDateOrNull('2026-01-15')).toBe(formatUtcDate('2026-01-15'));
  });

  it('reads a real timestamp in the reader\'s zone', () => {
    applyDisplayTimeZone('Asia/Tokyo');

    // 23:30 UTC is already the next day in Tokyo.
    expect(formatUtcDateOrNull('2026-01-15T23:30:00Z')).toContain('16');
  });

  it('answers null - not a dash - for nothing, so a caller can omit a whole sentence', () => {
    expect(formatUtcDateOrNull(null)).toBeNull();
    expect(formatUtcDateOrNull(undefined)).toBeNull();
    expect(formatUtcDateOrNull('')).toBeNull();
    expect(formatUtcDateOrNull('garbage')).toBeNull();
  });

  it('answers null instead of throwing on a value that is neither string nor Date', () => {
    // parseUtcAware hands back a non-string unchanged, so a mis-shaped payload would reach
    // .getTime() and take the page down inside a helper whose name promises a null.
    expect(formatUtcDateOrNull([2026, 1, 15] as unknown as string)).toBeNull();
  });
});

describe('wallClockIn and instantOfWallClock, which an edit widget round-trips through', () => {
  /**
   * Offsets that a naive conversion gets wrong, each at a real instant.
   *
   * <p>Every case here starts from an INSTANT and derives the wall clock from it, so the clock it
   * round-trips always exists. Three of these rows used to be named for the DST gap - "the hour a
   * zone skips", "a zone that deletes midnight" - which they cannot reach from this direction and
   * so could not fail for it. They are named for what they prove: that the solve is exact at the
   * minute either side of a transition and at offsets that are not whole hours. The gap itself is
   * covered below, from the direction a person types.
   */
  const EXACT: ReadonlyArray<readonly [string, string, string]> = [
    ['Europe/Paris', '2026-01-15T12:00:00.000Z', 'winter'],
    ['Europe/Paris', '2026-07-15T12:00:00.000Z', 'summer'],
    ['America/Los_Angeles', '2026-03-08T09:59:00.000Z', 'the last minute before a spring-forward'],
    ['America/Santiago', '2026-09-06T04:00:00.000Z', 'the first hour after a skipped midnight'],
    ['Asia/Kolkata', '2026-01-15T12:00:00.000Z', 'a half-hour offset'],
    ['Asia/Kathmandu', '2026-01-15T12:00:00.000Z', 'a 45-minute offset'],
    ['Australia/Lord_Howe', '2026-04-05T15:30:00.000Z', 'a 30-minute DST offset'],
  ];

  for (const [zone, iso, what] of EXACT) {
    it(`round-trips exactly in ${zone} (${what})`, () => {
      // The property the edit widget depends on: open a cell, save it untouched, and the stored
      // instant is the one it started from. Anything less means a no-op edit moves data.
      applyDisplayTimeZone(zone);

      const wall = wallClockIn(new Date(iso), undefined, 'datetime');
      expect(instantOfWallClock(wall)).toBe(iso);
    });
  }

  it('lands on ONE of the two instants of a repeated hour, and which one depends on the zone', () => {
    // 01:30 happens twice on a fall-back morning, an hour apart, and a `datetime-local` cannot say
    // which - so no conversion can recover it and the error is one hour, in the same wall clock
    // the person is reading. That part is inherent to the input.
    //
    // What is NOT a property: WHICH of the two. This test used to be called "picks the FIRST,
    // which is a decision not an accident" and asserted it in Los Angeles - the one sign of
    // offset where the claim is true. Measured across every zone's 2026 fall-back transition it
    // is the earlier instant in 58 and the later one in 71. Both are asserted here, in the two
    // zones that disagree, so the next reader cannot take either for a rule.
    applyDisplayTimeZone('America/Los_Angeles');
    const firstInLa = '2026-11-01T08:30:00.000Z';
    const secondInLa = '2026-11-01T09:30:00.000Z';

    // Both instants read as the same wall clock, which is what makes it ambiguous at all.
    expect(wallClockIn(new Date(firstInLa), undefined, 'datetime')).toBe('2026-11-01T01:30');
    expect(wallClockIn(new Date(secondInLa), undefined, 'datetime')).toBe('2026-11-01T01:30');
    expect(instantOfWallClock('2026-11-01T01:30')).toBe(firstInLa);

    // Paris, same ambiguity, the OTHER answer: 02:30 on 2026-10-25 happens at 00:30Z and again at
    // 01:30Z, and the solve lands on the later one.
    applyDisplayTimeZone('Europe/Paris');
    expect(wallClockIn(new Date('2026-10-25T00:30:00.000Z'), undefined, 'datetime'))
      .toBe('2026-10-25T02:30');
    expect(wallClockIn(new Date('2026-10-25T01:30:00.000Z'), undefined, 'datetime'))
      .toBe('2026-10-25T02:30');
    expect(instantOfWallClock('2026-10-25T02:30')).toBe('2026-10-25T01:30:00.000Z');
  });

  it('answers the day alone, or the time alone, when that is the shape asked for', () => {
    applyDisplayTimeZone('Asia/Tokyo');
    const instant = new Date('2026-01-15T23:30:00Z');

    expect(wallClockIn(instant, undefined, 'date')).toBe('2026-01-16');
    expect(wallClockIn(instant, undefined, 'time')).toBe('08:30');
  });

  it('ends a 23-hour day that has NO 23:59 at its real last instant, not nowhere', () => {
    // Greenland moves its clocks at 23:00, so on a spring-forward day the local clock jumps from
    // 22:59 straight to 00:00 and 23:59 never happens. Solving for 23:59:59.999 landed in the NEXT
    // day, the day check rejected it, and the upper bound VANISHED - a filter "up to that day" then
    // had no upper bound at all and showed everything after it.
    //
    // Found by sweeping every IANA zone across three years rather than by reasoning: the hour-step
    // that fixed the opposite case (a day with two 23:59s) could not have fixed this one, and three
    // zones in the review before this had been checked by hand.
    expect(dayEdgeInstant('2026-03-28', 'end', 'America/Godthab'))
      .toBe('2026-03-29T00:59:59.999Z');
    expect(dayEdgeInstant('2026-03-28', 'end', 'America/Scoresbysund'))
      .toBe('2026-03-29T00:59:59.999Z');

    // The end above is 22:59:59.999 local, the last moment of that day there, and the START is
    // ordinary midnight: only the top of the day is missing, so the two edges are 23 hours apart.
    expect(dayEdgeInstant('2026-03-28', 'start', 'America/Godthab'))
      .toBe('2026-03-28T02:00:00.000Z');
  });

  it('ends a 25-hour day at its LAST 23:59, not the first one', () => {
    // Where the clocks go back AT midnight, 23:59 happens twice. The solver resolves an ambiguous
    // wall clock to its earliest instant - right for a day START, an hour short for the end - so a
    // filter "up to the 4th" excluded everything in the repeated 23:00 hour while those rows
    // displayed that very day. The day check cannot see it: the early instant is still inside the
    // right day.
    expect(dayEdgeInstant('2026-04-04', 'end', 'America/Santiago'))
      .toBe('2026-04-05T03:59:59.999Z');
    expect(dayEdgeInstant('2026-10-24', 'end', 'America/Nuuk'))
      .toBe('2026-10-25T01:59:59.999Z');

    // And the START of such a day is still its FIRST midnight, which is the case the same rule gets
    // right: the two edges want opposite ends of an ambiguous clock.
    expect(dayEdgeInstant('2026-04-04', 'start', 'America/Santiago'))
      .toBe('2026-04-04T03:00:00.000Z');
  });

  it('leaves an ordinary 24-hour day alone at both edges', () => {
    // The hour-forward step must run only on the ambiguous case.
    expect(dayEdgeInstant('2026-06-15', 'end', 'Europe/Paris')).toBe('2026-06-15T21:59:59.999Z');
    expect(dayEdgeInstant('2026-10-25', 'end', 'Europe/Paris')).toBe('2026-10-25T22:59:59.999Z');
  });

  it('answers null for an explicit zone this runtime cannot use, rather than the reader\'s', () => {
    // These two values bound a QUERY, so falling back to the reader zone would scope it to a
    // different 24 hours with nothing on screen to say so. Null is what the callers treat as
    // "no bound". The only branch that distinguishes this helper from a render-safe fallback, and it
    // had no test.
    expect(dayEdgeInstant('2026-01-15', 'start', 'Mars/Base')).toBeNull();
    expect(dayEdgeInstant('2026-01-15', 'end', 'Mars/Base')).toBeNull();
    expect(dayEdgeInstant('2026-01-15', 'start', 'Europe/Paris')).not.toBeNull();
  });

  it('answers null for a day a ZONE skipped entirely, not a bound in the day before it', () => {
    // Two IANA days never happened: Pacific/Apia skipped 2011-12-30 and Pacific/Kiritimati skipped
    // 1994-12-31, both for a dateline change. The walk-forward this function used to run was capped
    // at eight 15-minute steps, under a comment claiming that covered every real gap - so it
    // exhausted its steps and answered an instant on the PREVIOUS day, and a filter asked for a day
    // that never happened was silently scoped to the 24 hours before it. Null is what this function
    // already answered for 30 February.
    expect(dayEdgeInstant('2011-12-30', 'start', 'Pacific/Apia')).toBeNull();
    expect(dayEdgeInstant('2011-12-30', 'end', 'Pacific/Apia')).toBeNull();
    expect(dayEdgeInstant('1994-12-31', 'start', 'Pacific/Kiritimati')).toBeNull();

    // The days either side of the missing one are unaffected.
    expect(dayEdgeInstant('2011-12-29', 'start', 'Pacific/Apia')).not.toBeNull();
    expect(dayEdgeInstant('2011-12-31', 'start', 'Pacific/Apia')).not.toBeNull();
  });

  it('answers null for anything that is not a wall clock, so a caller stores what was typed', () => {
    expect(instantOfWallClock('')).toBeNull();
    expect(instantOfWallClock('2026-01-15')).toBeNull();
    expect(instantOfWallClock('tomorrow')).toBeNull();
  });
});

/**
 * A wall clock that never happened, typed into the input.
 *
 * <p>The only direction the gap is reachable from. An input accepts 02:30 on a spring-forward
 * morning because the browser knows nothing about zones, and a person setting a deadline has no
 * reason to know either. What must never happen is the answer moving BACKWARDS past the gap, to
 * before the moment they asked for.
 */
describe('a wall clock the zone skipped', () => {
  /** The local reading of an instant, which is what the person sees after saving. */
  const localOf = (iso: string) => wallClockIn(new Date(iso), undefined, 'datetime');

  it('shifts a nonexistent clock forward BY THE GAP, as ZonedDateTime.ofLocal does', () => {
    // Los Angeles, 2026-03-08: 02:00 to 03:00 does not exist. The two-pass solve on its own answers
    // 01:30 local, an HOUR BEFORE what was typed, which is the one direction a deadline must not
    // move. Shifting by the gap gives 03:30 and keeps the minutes somebody typed.
    //
    // 03:30, not 03:00. An earlier version of this walked forward on a 15-minute grid to "the first
    // clock that exists" and answered 03:00 here, silently discarding the :30 - and only in zones
    // west of Greenwich, because east of it the same walk was a no-op. Two rules for one question,
    // split by the sign of the offset, under a comment claiming the behaviour every calendar has.
    applyDisplayTimeZone('America/Los_Angeles');

    const stored = instantOfWallClock('2026-03-08T02:30');

    expect(stored).toBe('2026-03-08T10:30:00.000Z');
    expect(localOf(stored!)).toBe('2026-03-08T03:30');
  });

  it('shifts the top of the gap forward too, not back to the hour before it', () => {
    applyDisplayTimeZone('America/Los_Angeles');

    const stored = instantOfWallClock('2026-03-08T02:00');

    expect(localOf(stored!)).toBe('2026-03-08T03:00');
  });

  it('does not cross a DAY boundary backwards in a zone that deletes midnight', () => {
    // Santiago on 2026-09-06 springs forward at 24:00, so 00:00-01:00 of the 6th is missing. The
    // solve alone answers 23:30 on the FIFTH: not merely an early hour, the wrong DAY, which is
    // what a person reads back off the cell. Shifted by the gap it is 01:30 on the 6th.
    applyDisplayTimeZone('America/Santiago');

    const stored = instantOfWallClock('2026-09-06T00:30');

    expect(localOf(stored!)).toBe('2026-09-06T01:30');
  });

  it('answers the same shifted clock east of Greenwich, where the old walk was a no-op', () => {
    // Paris springs forward at 02:00 on 2026-03-29. This case is why the split went unnoticed: the
    // two-pass solve lands at 03:30 here on its own, so the walk-forward never ran, and anybody
    // testing in Europe saw the documented behaviour. The Americas got the other one.
    applyDisplayTimeZone('Europe/Paris');

    const stored = instantOfWallClock('2026-03-29T02:30');

    expect(stored).toBe('2026-03-29T01:30:00.000Z');
    expect(localOf(stored!)).toBe('2026-03-29T03:30');
  });

  it('leaves every clock that does exist untouched, which is all of them but one hour a year', () => {
    // The guard against a walk that runs when it should not: an ordinary afternoon, and the
    // ambiguous hour, must both answer exactly what they answered before the walk existed.
    applyDisplayTimeZone('Europe/Paris');
    expect(instantOfWallClock('2026-06-15T14:30')).toBe('2026-06-15T12:30:00.000Z');

    applyDisplayTimeZone('America/Los_Angeles');
    expect(instantOfWallClock('2026-11-01T01:30')).toBe('2026-11-01T08:30:00.000Z');
  });
});

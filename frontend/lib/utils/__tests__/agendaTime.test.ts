import { describe, expect, it } from 'vitest';
import {
  addDays,
  formatZoneAbbreviation,
  addMonths,
  dayKey,
  formatCompactDateRange,
  isSameDay,
  monthGridDays,
  startOfDay,
  startOfWeek,
  weekGridDays,
  weekdayHeaders,
  zonedParts,
  zonedTimeToInstant,
} from '../agendaTime';

/**
 * The calendar's arithmetic.
 *
 * The app ships no date library, so every day boundary the agenda draws is computed here.
 * The cases that carry real risk are the DST transitions: a naive "add 24 hours" produces
 * a grid where one day appears twice and another is missing, and the bug is invisible for
 * ten months of the year.
 */
describe('agendaTime', () => {
  describe('formatCompactDateRange', () => {
    it('collapses a week in one month into a short localized title', () => {
      const from = zonedTimeToInstant('Europe/Paris', 2026, 9, 7, 0, 0);
      const to = zonedTimeToInstant('Europe/Paris', 2026, 9, 13, 23, 59);

      expect(formatCompactDateRange(from, to, 'Europe/Paris', 'fr'))
        .toBe('7 - 13 sept. 2026');
    });

    it('keeps both month names when the week crosses a month boundary', () => {
      const from = zonedTimeToInstant('Europe/Paris', 2026, 8, 31, 0, 0);
      const to = zonedTimeToInstant('Europe/Paris', 2026, 9, 6, 23, 59);

      expect(formatCompactDateRange(from, to, 'Europe/Paris', 'fr'))
        .toBe('31 août - 6 sept. 2026');
    });

    it('never exposes typographic dash characters returned by Intl', () => {
      const from = new Date('2026-09-07T00:00:00.000Z');
      const to = new Date('2026-09-13T23:59:00.000Z');

      expect(formatCompactDateRange(from, to, 'UTC', 'en'))
        .not.toMatch(/[\u2013\u2014]/u);
    });
  });

  describe('zonedTimeToInstant', () => {
    it('resolves a wall-clock time to the instant that zone reads it at', () => {
      // 09:00 Paris in September is 07:00 UTC (CEST, UTC+2).
      expect(zonedTimeToInstant('Europe/Paris', 2026, 9, 3, 9, 0).toISOString())
        .toBe('2026-09-03T07:00:00.000Z');
      // The same wall clock in January is 08:00 UTC (CET, UTC+1).
      expect(zonedTimeToInstant('Europe/Paris', 2026, 1, 15, 9, 0).toISOString())
        .toBe('2026-01-15T08:00:00.000Z');
    });

    it('is exact on both sides of a DST transition', () => {
      // Europe/Paris falls back on 2026-10-25. Midnight before and after the change
      // sits at a different UTC offset, and a single-offset implementation gets one
      // of the two wrong.
      expect(zonedTimeToInstant('Europe/Paris', 2026, 10, 24, 0, 0).toISOString())
        .toBe('2026-10-23T22:00:00.000Z');
      expect(zonedTimeToInstant('Europe/Paris', 2026, 10, 26, 0, 0).toISOString())
        .toBe('2026-10-25T23:00:00.000Z');
    });

    it('handles a zone with a half-hour offset', () => {
      // A whole-hour assumption anywhere in the maths shows up here first.
      expect(zonedTimeToInstant('Asia/Kolkata', 2026, 9, 3, 9, 0).toISOString())
        .toBe('2026-09-03T03:30:00.000Z');
    });
  });

  describe('startOfDay and addDays', () => {
    it('walks calendar days, not 24-hour blocks, across a DST change', () => {
      // 25 October 2026 is 25 hours long in Paris. Adding 24h to its midnight lands at
      // 23:00 the SAME day, so the grid would draw the 25th twice and skip the 26th.
      const oct25 = zonedTimeToInstant('Europe/Paris', 2026, 10, 25, 0, 0);
      const next = addDays(oct25, 1, 'Europe/Paris');

      expect(zonedParts(next, 'Europe/Paris').day).toBe(26);
      expect(zonedParts(next, 'Europe/Paris').hour).toBe(0);
      expect(next.getTime() - oct25.getTime()).toBe(25 * 3600 * 1000);
    });

    it('truncates to midnight in the display zone, not the host zone', () => {
      const instant = new Date('2026-09-03T23:30:00.000Z');
      // 23:30 UTC is already the 4th in Tokyo, so its day starts on the 4th there.
      expect(zonedParts(startOfDay(instant, 'Asia/Tokyo'), 'Asia/Tokyo').day).toBe(4);
      expect(zonedParts(startOfDay(instant, 'UTC'), 'UTC').day).toBe(3);
    });

    it('steps backwards as well as forwards', () => {
      const day = zonedTimeToInstant('UTC', 2026, 3, 1, 0, 0);
      expect(zonedParts(addDays(day, -1, 'UTC'), 'UTC')).toMatchObject({ year: 2026, month: 2, day: 28 });
    });
  });

  describe('addMonths', () => {
    it('clamps rather than overflowing into the next month', () => {
      // 31 January + 1 month is February, not 3 March.
      const jan31 = zonedTimeToInstant('UTC', 2026, 1, 31, 0, 0);
      expect(zonedParts(addMonths(jan31, 1, 'UTC'), 'UTC')).toMatchObject({ month: 2, day: 28 });
    });

    it('crosses a year boundary in both directions', () => {
      const dec = zonedTimeToInstant('UTC', 2026, 12, 15, 0, 0);
      expect(zonedParts(addMonths(dec, 1, 'UTC'), 'UTC')).toMatchObject({ year: 2027, month: 1 });
      const jan = zonedTimeToInstant('UTC', 2026, 1, 15, 0, 0);
      expect(zonedParts(addMonths(jan, -1, 'UTC'), 'UTC')).toMatchObject({ year: 2025, month: 12 });
    });
  });

  describe('startOfWeek', () => {
    it('honours the configured first day of the week', () => {
      // 2026-09-03 is a Thursday.
      const thursday = zonedTimeToInstant('UTC', 2026, 9, 3, 12, 0);
      expect(zonedParts(startOfWeek(thursday, 'UTC', 1), 'UTC').day).toBe(31);  // Mon 31 Aug
      expect(zonedParts(startOfWeek(thursday, 'UTC', 0), 'UTC').day).toBe(30);  // Sun 30 Aug
    });

    it('does not move a day that is already the start of its week', () => {
      const monday = zonedTimeToInstant('UTC', 2026, 8, 31, 0, 0);
      expect(startOfWeek(monday, 'UTC', 1).toISOString()).toBe(monday.toISOString());
    });
  });

  describe('DST transitions', () => {
    /*
     * The two-pass offset correction GUESSES, and a guess has to be checked. Taking the
     * second pass on faith resolved a wall-clock time that does not exist - the
     * spring-forward gap - BACKWARD, past the requested day. Where a zone's gap is at 00:00
     * that moved midnight into the previous day and deleted a whole calendar day: the month
     * grid drew one day twice and never asked for another, so every run scheduled on the
     * missing day appeared nowhere at all. Silent, and only in three zones a year.
     *
     * These assert the REQUIREMENT - the day that comes back is the day that was asked for.
     * The previous suite tested Paris on 24 and 26 October, stepping around the transition
     * itself and using the one DST direction the code handled, and asserted the grid's
     * LENGTH. Every one of those passed throughout the bug.
     */
    const MIDNIGHT_GAP: Array<[string, number, number, number]> = [
      ['America/Havana', 2026, 3, 8],
      ['America/Santiago', 2026, 9, 6],
      ['Atlantic/Azores', 2026, 3, 29],
    ];

    it.each(MIDNIGHT_GAP)('startOfDay stays on the requested day in %s', (zone, y, m, d) => {
      // Midnight does not exist on these dates: the clock jumps from 23:59:59 straight to
      // 01:00. It has to resolve FORWARD, into the day that was asked for.
      const noon = zonedTimeToInstant(zone as string, y as number, m as number, d as number, 12, 0);

      expect(zonedParts(startOfDay(noon, zone as string), zone as string))
        .toMatchObject({ year: y, month: m, day: d });
    });

    it.each(MIDNIGHT_GAP)('the month grid keeps 42 DISTINCT days around %s', (zone, y, m) => {
      // `toHaveLength(42)` stayed green throughout: the count was right and one day was
      // simply drawn twice. Distinctness is the property that actually matters.
      const days = monthGridDays(
        zonedTimeToInstant(zone as string, y as number, m as number, 15, 12, 0), zone as string, 1);

      expect(new Set(days.map((day) => dayKey(day, zone as string))).size).toBe(42);
    });

    it.each(MIDNIGHT_GAP)('the day the gap falls on is present in the %s grid', (zone, y, m, d) => {
      // The other half: not merely distinct, but containing the day whose runs were lost.
      const days = monthGridDays(
        zonedTimeToInstant(zone as string, y as number, m as number, 15, 12, 0), zone as string, 1);
      const target = dayKey(
        zonedTimeToInstant(zone as string, y as number, m as number, d as number, 12, 0),
        zone as string);

      expect(days.map((day) => dayKey(day, zone as string))).toContain(target);
    });

    it('a spring-forward gap at 02:00 still resolves forward, to 03:00', () => {
      // The behaviour the docstring already promised, pinned so the fix does not trade one
      // direction for the other.
      expect(zonedParts(zonedTimeToInstant('Europe/Paris', 2026, 3, 29, 2, 30), 'Europe/Paris'))
        .toMatchObject({ day: 29, hour: 3, minute: 30 });
    });

    it('every day of 2026 resolves to itself in a zone that transitions at midnight', () => {
      // The sweep, one zone deep enough to be fast. Its 418-zone version found exactly three
      // failures before the fix and finds none after.
      for (let month = 1; month <= 12; month += 1) {
        for (let day = 1; day <= 28; day += 1) {
          const noon = zonedTimeToInstant('America/Santiago', 2026, month, day, 12, 0);

          expect(zonedParts(startOfDay(noon, 'America/Santiago'), 'America/Santiago'))
            .toMatchObject({ year: 2026, month, day });
        }
      }
    });

    it('a fall-back day is still 25 hours long and starts at its FIRST midnight', () => {
      const start = startOfDay(
        zonedTimeToInstant('Europe/Paris', 2026, 10, 25, 12, 0), 'Europe/Paris');

      expect(addDays(start, 1, 'Europe/Paris').getTime() - start.getTime())
        .toBe(25 * 3600 * 1000);
      expect(zonedParts(start, 'Europe/Paris')).toMatchObject({ day: 25, hour: 0 });
    });
  });

  describe('grids', () => {
    it('always draws 42 month cells, so the grid height never jumps', () => {
      for (const month of [1, 2, 5, 9, 12]) {
        const anchor = zonedTimeToInstant('UTC', 2026, month, 15, 0, 0);
        expect(monthGridDays(anchor, 'UTC', 1)).toHaveLength(42);
      }
    });

    it('starts the month grid on the configured weekday and covers the month', () => {
      const anchor = zonedTimeToInstant('UTC', 2026, 9, 15, 0, 0);
      const days = monthGridDays(anchor, 'UTC', 1);

      expect(zonedParts(days[0], 'UTC').weekday).toBe(1);           // Monday
      expect(days.map((d) => dayKey(d, 'UTC'))).toContain('2026-09-01');
      expect(days.map((d) => dayKey(d, 'UTC'))).toContain('2026-09-30');
    });

    it('produces seven consecutive week days', () => {
      const days = weekGridDays(zonedTimeToInstant('UTC', 2026, 9, 3, 0, 0), 'UTC', 1);
      expect(days).toHaveLength(7);
      expect(days.map((d) => dayKey(d, 'UTC'))).toEqual([
        '2026-08-31', '2026-09-01', '2026-09-02', '2026-09-03',
        '2026-09-04', '2026-09-05', '2026-09-06',
      ]);
    });

    it('produces seven weekday headers in the configured order', () => {
      const monday = weekdayHeaders('UTC', 1, 'en');
      const sunday = weekdayHeaders('UTC', 0, 'en');
      expect(monday).toHaveLength(7);
      expect(sunday).toHaveLength(7);
      // Same set of days, rotated by one.
      expect(sunday[0]).toBe(monday[6]);
    });

    it('names the weekdays correctly WEST of Greenwich, not one day early', () => {
      // The reference week is built from a fixed instant and then formatted in the display
      // zone. Anchored at midnight UTC it is still the previous day for every negative
      // offset, so every American viewer read every column under the wrong name - a silent
      // wrong result on the most-read text on the page. A UTC-only test cannot see it.
      for (const zone of ['America/New_York', 'America/Los_Angeles', 'Pacific/Honolulu']) {
        expect(weekdayHeaders(zone, 0, 'en'), zone).toEqual(weekdayHeaders('UTC', 0, 'en'));
        expect(weekdayHeaders(zone, 1, 'en'), zone).toEqual(weekdayHeaders('UTC', 1, 'en'));
      }
    });

    it('names the weekdays correctly far EAST as well', () => {
      for (const zone of ['Asia/Tokyo', 'Pacific/Kiritimati']) {
        expect(weekdayHeaders(zone, 1, 'en'), zone).toEqual(weekdayHeaders('UTC', 1, 'en'));
      }
    });

    it('the first header matches the weekday the grid actually starts on', () => {
      // The columns come from startOfWeek and the labels from weekdayHeaders; they are
      // computed by different code and must agree, or every date sits under a wrong name.
      for (const zone of ['UTC', 'America/Los_Angeles', 'Asia/Kolkata']) {
        for (const weekStart of [0, 1] as const) {
          const firstCell = weekGridDays(
            zonedTimeToInstant(zone, 2026, 9, 3, 12, 0), zone, weekStart)[0];
          const expected = new Intl.DateTimeFormat('en', { timeZone: zone, weekday: 'short' })
            .format(firstCell);
          expect(weekdayHeaders(zone, weekStart, 'en')[0], `${zone} weekStart=${weekStart}`)
            .toBe(expected);
        }
      }
    });
  });

  describe('dayKey and isSameDay', () => {
    it('buckets by the DISPLAY zone, so a chip lands on the day the user sees', () => {
      // 23:30 UTC is the 4th in Tokyo. Bucketing in UTC would draw the run on the wrong
      // day for every Tokyo viewer.
      const instant = new Date('2026-09-03T23:30:00.000Z');
      expect(dayKey(instant, 'UTC')).toBe('2026-09-03');
      expect(dayKey(instant, 'Asia/Tokyo')).toBe('2026-09-04');
    });

    it('pads month and day so keys sort chronologically as strings', () => {
      expect(dayKey(new Date('2026-01-05T12:00:00Z'), 'UTC')).toBe('2026-01-05');
      expect(dayKey(new Date('2026-01-05T12:00:00Z'), 'UTC')
        < dayKey(new Date('2026-01-12T12:00:00Z'), 'UTC')).toBe(true);
    });

    it('compares days in the display zone', () => {
      const a = new Date('2026-09-03T23:30:00.000Z');
      const b = new Date('2026-09-04T00:30:00.000Z');
      expect(isSameDay(a, b, 'UTC')).toBe(false);
      expect(isSameDay(a, b, 'Asia/Tokyo')).toBe(true);
    });
  });

  describe('formatZoneAbbreviation', () => {
    it('reads the offset in force AT THAT INSTANT, not a fixed one per zone', () => {
      // The whole reason this takes an instant. Paris is GMT+1 in winter and GMT+2 in
      // summer; a value resolved once and reused would be an hour wrong for half the year,
      // on the one label whose job is to say which offset the calendar is being read in.
      const winter = new Date('2026-01-15T12:00:00Z');
      const summer = new Date('2026-07-15T12:00:00Z');

      expect(formatZoneAbbreviation(winter, 'Europe/Paris', 'en')).toBe('GMT+1');
      expect(formatZoneAbbreviation(summer, 'Europe/Paris', 'en')).toBe('GMT+2');
    });

    it('names UTC as UTC rather than as an offset of itself', () => {
      expect(formatZoneAbbreviation(new Date('2026-09-03T09:30:00Z'), 'UTC', 'en')).toBe('UTC');
    });

    it('takes the zone part wherever the locale puts it, not off the end of the string', () => {
      // Slicing a suffix would work in English and put the wrong characters on the clock
      // in the languages that do not print the zone last.
      const instant = new Date('2026-07-15T12:00:00Z');

      for (const locale of ['en', 'fr', 'de', 'es', 'pt', 'zh']) {
        const value = formatZoneAbbreviation(instant, 'Europe/Paris', locale);
        expect(value).not.toBe('');
        expect(value).not.toMatch(/\d{2}:\d{2}/);
      }
    });
  });

  describe('zonedParts', () => {
    it('reports midnight as hour 0, not 24', () => {
      // Some engines render midnight as "24" under hour12:false; an unnormalised hour
      // would sort the first slot of the day to the very end of the grid.
      const midnight = zonedTimeToInstant('UTC', 2026, 9, 3, 0, 0);
      expect(zonedParts(midnight, 'UTC').hour).toBe(0);
    });

    it('numbers weekdays the way cron does, with Sunday at 0', () => {
      expect(zonedParts(zonedTimeToInstant('UTC', 2026, 9, 6, 12, 0), 'UTC').weekday).toBe(0);
      expect(zonedParts(zonedTimeToInstant('UTC', 2026, 9, 3, 12, 0), 'UTC').weekday).toBe(4);
    });
  });
});

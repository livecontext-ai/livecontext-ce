/**
 * Calendar arithmetic in an explicit timezone.
 *
 * The app ships no date library, and the agenda cannot use the browser's local time
 * blindly: a schedule fires in ITS OWN timezone, and a user in Paris looking at a
 * UTC-scheduled job has to be able to see it in either. So every function here takes
 * the display timezone explicitly and nothing reads the host offset implicitly.
 *
 * Two rules the rest of the agenda depends on:
 *  - Instants are the only thing passed around (`Date`, i.e. a point in time). Wall-clock
 *    values exist only inside this module, where they are always paired with a zone.
 *  - Display strings go through the APP locale (`getClientLocale()`), never the browser
 *    language. The `en-US` formatters below are NOT display: they are parsers, used to
 *    read back numeric parts whose names must not shift with the user's language.
 */

import { getClientLocale } from '@/lib/utils/locale';

/** Wall-clock parts of an instant, as read in a specific zone. */
export interface ZonedParts {
  year: number;
  month: number;   // 1-12
  day: number;     // 1-31
  hour: number;    // 0-23
  minute: number;
  /** 0 = Sunday .. 6 = Saturday, matching cron numbering. */
  weekday: number;
}

const PART_READER_CACHE = new Map<string, Intl.DateTimeFormat>();

/**
 * A fixed-locale formatter used to READ an instant's parts in a zone. Locale-invariant
 * on purpose: `formatToParts` keys ('year', 'hour', ...) are stable, but only if the
 * locale does not swap in a non-Gregorian calendar.
 */
function partReader(timeZone: string): Intl.DateTimeFormat {
  let reader = PART_READER_CACHE.get(timeZone);
  if (!reader) {
    reader = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hour12: false,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      weekday: 'short',
    });
    PART_READER_CACHE.set(timeZone, reader);
  }
  return reader;
}

const WEEKDAY_INDEX: Record<string, number> = {
  Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6,
};

/** Read an instant's wall-clock parts in a zone. */
export function zonedParts(instant: Date, timeZone: string): ZonedParts {
  const parts = partReader(timeZone).formatToParts(instant);
  const read = (type: string) => parts.find((p) => p.type === type)?.value ?? '0';
  // `hour: '2-digit'` with hour12:false renders midnight as "24" in some engines.
  const hour = Number(read('hour')) % 24;
  return {
    year: Number(read('year')),
    month: Number(read('month')),
    day: Number(read('day')),
    hour,
    minute: Number(read('minute')),
    weekday: WEEKDAY_INDEX[read('weekday')] ?? 0,
  };
}

/** Milliseconds a zone is ahead of UTC at a given instant. */
function zoneOffsetMs(instant: Date, timeZone: string): number {
  const p = zonedParts(instant, timeZone);
  const seconds = Number(
    partReader(timeZone).formatToParts(instant).find((x) => x.type === 'second')?.value ?? '0',
  );
  const asIfUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, seconds);
  // Round to the second: the source instant's own milliseconds are not in the parts.
  return asIfUtc - Math.floor(instant.getTime() / 1000) * 1000;
}

/**
 * The instant at which a zone's wall clock reads the given date and time.
 *
 * Two guesses, because the offset depends on the very instant being computed: one using
 * the offset at the naive UTC point, one using the offset actually in force there. That is
 * what makes day boundaries survive a DST change - the day the clocks go back is 25 hours
 * long, and a one-pass version silently produces 23:00 the previous day for every cell
 * after the transition.
 *
 * <p><b>Then the guess is CHECKED, which the two passes alone do not do.</b> A candidate is
 * only right if the zone's wall clock at that instant reads back exactly what was asked
 * for. Taking the second pass on faith resolved a wall-clock time that does not exist - the
 * spring-forward gap - BACKWARD, past the requested day. Where the gap is at 00:00 that
 * moved midnight into the previous day, and a whole calendar day disappeared: the month
 * grid produced 42 cells with 41 distinct days, one day drawn twice and one never asked
 * for, so every run scheduled on it was drawn nowhere. Real for America/Havana
 * (2026-03-08), America/Santiago (2026-09-06) and Atlantic/Azores (2026-03-29); verified
 * against all 418 IANA zones, where no other date resolves wrong.
 *
 * <p>A time that does not exist resolves FORWARD, to the instant the clock jumps to, which
 * is what a calendar should show and never leaves the requested day. An ambiguous time (the
 * hour that happens twice in autumn) takes its first occurrence, so a day starts at its
 * first midnight.
 */
export function zonedTimeToInstant(
  timeZone: string,
  year: number,
  month: number,
  day: number,
  hour = 0,
  minute = 0,
): Date {
  const naive = Date.UTC(year, month - 1, day, hour, minute);
  const firstGuess = naive - zoneOffsetMs(new Date(naive), timeZone);
  const corrected = naive - zoneOffsetMs(new Date(firstGuess), timeZone);

  // A candidate is valid when reading the zone's clock at it gives back the naive time.
  const valid = [firstGuess, corrected].filter(
    (candidate) => candidate + zoneOffsetMs(new Date(candidate), timeZone) === naive,
  );
  if (valid.length > 0) return new Date(Math.min(...valid));

  // Neither reads back: the requested wall-clock time is inside a spring-forward gap and
  // never happens. The later candidate is the instant the clock jumps to.
  return new Date(Math.max(firstGuess, corrected));
}

/** Midnight, in the given zone, of the day the instant falls on. */
export function startOfDay(instant: Date, timeZone: string): Date {
  const p = zonedParts(instant, timeZone);
  return zonedTimeToInstant(timeZone, p.year, p.month, p.day);
}

/** Midnight of the day after. Adding 24h would be wrong on DST days. */
export function addDays(instant: Date, days: number, timeZone: string): Date {
  const p = zonedParts(instant, timeZone);
  return zonedTimeToInstant(timeZone, p.year, p.month, p.day + days, p.hour, p.minute);
}

/** Same-month arithmetic that clamps rather than overflowing (31 Jan + 1 month = 28 Feb). */
export function addMonths(instant: Date, months: number, timeZone: string): Date {
  const p = zonedParts(instant, timeZone);
  const targetMonthStart = zonedTimeToInstant(timeZone, p.year, p.month + months, 1);
  const target = zonedParts(targetMonthStart, timeZone);
  const lastDay = daysInMonth(target.year, target.month);
  return zonedTimeToInstant(timeZone, target.year, target.month, Math.min(p.day, lastDay));
}

export function daysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

/** Start of the week containing the instant. `weekStartsOn`: 0 = Sunday, 1 = Monday. */
export function startOfWeek(instant: Date, timeZone: string, weekStartsOn: 0 | 1): Date {
  const dayStart = startOfDay(instant, timeZone);
  const { weekday } = zonedParts(dayStart, timeZone);
  const back = (weekday - weekStartsOn + 7) % 7;
  return addDays(dayStart, -back, timeZone);
}

/** True when both instants fall on the same calendar day in the zone. */
export function isSameDay(a: Date, b: Date, timeZone: string): boolean {
  const pa = zonedParts(a, timeZone);
  const pb = zonedParts(b, timeZone);
  return pa.year === pb.year && pa.month === pb.month && pa.day === pb.day;
}

/** A key usable to bucket occurrences per day cell: `YYYY-MM-DD` in the zone. */
export function dayKey(instant: Date, timeZone: string): string {
  const p = zonedParts(instant, timeZone);
  return `${p.year}-${String(p.month).padStart(2, '0')}-${String(p.day).padStart(2, '0')}`;
}

/**
 * The 6-week grid a month view draws: always 42 cells, so the grid height never jumps
 * between months.
 */
export function monthGridDays(anchor: Date, timeZone: string, weekStartsOn: 0 | 1): Date[] {
  const p = zonedParts(anchor, timeZone);
  const monthStart = zonedTimeToInstant(timeZone, p.year, p.month, 1);
  const gridStart = startOfWeek(monthStart, timeZone, weekStartsOn);
  return Array.from({ length: 42 }, (_, i) => addDays(gridStart, i, timeZone));
}

/** The 7 days of the week containing the anchor. */
export function weekGridDays(anchor: Date, timeZone: string, weekStartsOn: 0 | 1): Date[] {
  const weekStart = startOfWeek(anchor, timeZone, weekStartsOn);
  return Array.from({ length: 7 }, (_, i) => addDays(weekStart, i, timeZone));
}

/* ------------------------------------------------------------------ *
 *  Display formatting - APP locale, explicit zone.
 * ------------------------------------------------------------------ */

function displayLocale(locale?: string): string {
  return locale || getClientLocale();
}

/**
 * Formatters are cached, like the parser above.
 *
 * <p>Constructing an `Intl.DateTimeFormat` is not free, and these are called in render by
 * every chip on screen. A month view can carry three hundred of them, each also subscribed
 * to the drag context, so a single pointer move during a drag rebuilt three hundred
 * formatters. The key includes every option that changes the output, so two callers asking
 * for different shapes never share one.
 */
const DISPLAY_FORMATTER_CACHE = new Map<string, Intl.DateTimeFormat>();

function displayFormatter(
  locale: string | undefined,
  timeZone: string,
  options: Intl.DateTimeFormatOptions,
): Intl.DateTimeFormat {
  const resolved = displayLocale(locale);
  const key = `${resolved}|${timeZone}|${JSON.stringify(options)}`;
  let formatter = DISPLAY_FORMATTER_CACHE.get(key);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(resolved, { timeZone, ...options });
    DISPLAY_FORMATTER_CACHE.set(key, formatter);
  }
  return formatter;
}

/** `14:30` in the display zone. */
export function formatTimeInZone(instant: Date, timeZone: string, locale?: string): string {
  return displayFormatter(locale, timeZone, {
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(instant);
}

/**
 * The zone's short name at that instant: `GMT+2` for most zones, a letter abbreviation
 * (`EST`, `PDT`) for the few the CLDR gives one under `timeZoneName: 'short'`, which in
 * practice means North America. European zones render as offsets, not as `CEST`.
 *
 * <p>Read off `formatToParts` rather than sliced out of a formatted string: the position
 * of the zone name inside the time varies by locale, and taking it as a suffix would put
 * the wrong characters on the clock in the languages that do not put it last.
 *
 * <p>It is read AT AN INSTANT because it is not a property of the zone: `Europe/Paris` is
 * GMT+1 in January and GMT+2 in July, so a clock that resolved it once would be an hour
 * wrong for half the year on the one label whose job is to say which offset is being read.
 */
export function formatZoneAbbreviation(instant: Date, timeZone: string, locale?: string): string {
  const parts = displayFormatter(locale, timeZone, {
    hour: '2-digit', minute: '2-digit', hour12: false, timeZoneName: 'short',
  }).formatToParts(instant);
  return parts.find((part) => part.type === 'timeZoneName')?.value ?? '';
}

/** `Thu 3 Sep` in the display zone. */
export function formatDayInZone(instant: Date, timeZone: string, locale?: string): string {
  return displayFormatter(locale, timeZone, {
    weekday: 'short', day: 'numeric', month: 'short',
  }).format(instant);
}

/** `T 3` - the narrowest a day header can be and still say which weekday and which date. */
export function formatDayNarrowInZone(instant: Date, timeZone: string, locale?: string): string {
  return displayFormatter(locale, timeZone, {
    weekday: 'narrow', day: 'numeric',
  }).format(instant);
}

/** `Thu 3` - `formatDayInZone` without the month, for a column that cannot hold it. */
export function formatDayShortInZone(instant: Date, timeZone: string, locale?: string): string {
  return displayFormatter(locale, timeZone, {
    weekday: 'short', day: 'numeric',
  }).format(instant);
}

/** `September 2026` - the month view's title. */
export function formatMonthTitle(instant: Date, timeZone: string, locale?: string): string {
  return displayFormatter(locale, timeZone, {
    month: 'long', year: 'numeric',
  }).format(instant);
}

/** `7 - 13 Sep 2026` - a compact, locale-aware period title without repeated weekdays. */
export function formatCompactDateRange(
  from: Date,
  to: Date,
  timeZone: string,
  locale?: string,
): string {
  const range = displayFormatter(locale, timeZone, {
    day: 'numeric', month: 'short', year: 'numeric',
  }).formatRange(from, to);

  // Intl uses typographic dash characters for ranges. Product copy uses the ordinary
  // hyphen consistently, and normalising the surrounding spacing keeps every locale
  // compact without joining the two dates together.
  return range.replace(/\s*[\u2013\u2014]\s*/gu, ' - ');
}

/** `Thursday 3 September 2026` - the day view's title. */
export function formatFullDate(instant: Date, timeZone: string, locale?: string): string {
  return displayFormatter(locale, timeZone, {
    weekday: 'long', day: 'numeric', month: 'long', year: 'numeric',
  }).format(instant);
}

/** Short weekday headers for a grid, in the app locale and the chosen week start. */
export function weekdayHeaders(timeZone: string, weekStartsOn: 0 | 1, locale?: string): string[] {
  const formatter = displayFormatter(locale, timeZone, { weekday: 'short' });
  // 2026-03-01 is a Sunday, so index 0 of this reference week is Sunday.
  //
  // The anchor is resolved IN `timeZone`, not as a fixed UTC instant. A fixed instant
  // cannot work: real offsets span UTC-12 to UTC+14, i.e. 26 hours, so for any instant
  // there is a zone in which it falls on a different calendar day - midnight UTC is the
  // previous day across the Americas, and noon UTC is already the next day at UTC+14.
  // Either way the labels shift by one and every date sits under the wrong weekday name,
  // silently. Asking for "the instant at which this zone's clock reads 1 March, noon"
  // makes the reference correct in every zone by construction.
  const anchor = zonedTimeToInstant(timeZone, 2026, 3, 1, 12, 0);
  return Array.from({ length: 7 }, (_, i) =>
    formatter.format(addDays(anchor, (i + weekStartsOn) % 7, timeZone)),
  );
}

/**
 * The twelve month names, in the app locale, for a picker that jumps to a month by name.
 *
 * <p>Built from instants resolved IN the zone, for the same reason `weekdayHeaders` is: a
 * fixed UTC instant lands on a different calendar date across a 26-hour span of real
 * offsets, and at the edges of a month that silently shifts every label by one. Noon on the
 * 15th is far from every boundary in every zone.
 */
export function monthNames(timeZone: string, locale?: string): string[] {
  const formatter = displayFormatter(locale, timeZone, { month: 'long' });
  return Array.from({ length: 12 }, (_, i) =>
    formatter.format(zonedTimeToInstant(timeZone, 2026, i + 1, 15, 12, 0)),
  );
}

/**
 * The timezones offered in the picker: the viewer's own first, then UTC, then the zones
 * the workspace's schedules actually use. Deduplicated, order preserved.
 *
 * Reading the browser zone here is correct and is not the i18n violation the locale rule
 * targets: it is the user's physical location, not their language.
 */
export function buildTimezoneOptions(scheduleTimezones: string[]): string[] {
  const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  const seen = new Set<string>();
  const out: string[] = [];
  for (const zone of [browserZone, 'UTC', ...scheduleTimezones]) {
    if (!zone || seen.has(zone)) continue;
    seen.add(zone);
    out.push(zone);
  }
  return out;
}

/** The viewer's own timezone, falling back to UTC where the browser will not say. */
export function browserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

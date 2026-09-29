import { getClientLocale } from './locale';
import { getClientTimeZone, isValidTimeZone } from './timezone';
import { zonedTimeToInstant } from './agendaTime';
/**
 * Centralized Date Formatting Utilities
 *
 * Single source of truth for date formatting across the application.
 *
 * The server stores and serves UTC; this module decides how that instant is SHOWN. Every
 * absolute timestamp is rendered in the person's display time zone (`getClientTimeZone()`:
 * their stored preference, else their browser's zone, else UTC).
 *
 * <p>THE ZONE IS NAMED ONLY WHEN IT IS NOT THE READER'S OWN. A date they chose the zone for reads
 * "Jan 21, 2026, 15:30"; a date a caller pinned to somebody else's zone reads "Jan 21, 2026,
 * 23:30 GMT+9". See {@link pinnedZoneSuffix} for the four cases that count as "their own".
 *
 * <p>These helpers used to label EVERY instant. On a date read in the reader's own zone that
 * label restated, on every row, a preference they had set themselves - and an OFFSET is not that
 * preference: `Europe/Paris` shows GMT+1 in January and GMT+2 in July, so the one piece of text
 * that looked like the setting was the one piece that changed twice a year without anybody
 * touching it. It also cost a layout bug: the epoch selector sizes its two time slots for
 * `HH:mm:ss`, and the extra label rendered over the arrow between them.
 *
 * <p>Dropping it everywhere was the first attempt and it went too far. THREE surfaces draw a
 * timestamp in a zone the reader did NOT choose - the builder's schedule panel, the agent
 * schedule card, and the public-access schedule list, enumerated in
 * `lib/schedule/__tests__/scheduleDefaultsFollowTheAccount.test.ts` - and there the label is what
 * stops the reader checking a fire time against their own clock and concluding the schedule is
 * broken. A surface may ALSO name the zone in full ("0 9 * * * (Asia/Tokyo)", "Next runs
 * ({timezone})"), which is better because a zone id is something a reader can act on; the label
 * is the floor, not the ceiling.
 *
 * <p>Two labels live outside this rule. `lib/status/format.ts` hardcodes " UTC" for the public
 * status page, which has no reader preference to consult and is right to name it. And the
 * agenda's clock (`formatZoneAbbreviation`) prints an offset, and
 * NOT for the reason it is tempting to give: `useAgendaPreferences` defaults that zone to the
 * reader's own. It is kept because that chip is a LIVE CLOCK whose job is to say which zone an
 * entire grid is drawn in, and the switch that changes it sits behind a settings popover.
 *
 * <p>Relative-time branches ("5m ago") are zone-neutral and carry nothing.
 *
 * A caller that must pin one specific zone - the "next runs" of a schedule trigger, which
 * fires in the zone stored ON the trigger, not the reader's - passes `timeZone` explicitly
 * instead of formatting the date by hand.
 *
 * Historical note on the names: `formatUtcDateTime` / `formatUtcDate` / `formatUtcTime` kept
 * their names through this change (250+ call sites) and no longer force UTC. Read `Utc` as
 * "from a UTC instant", not "displayed in UTC".
 */

/**
 * Every `Intl.DateTimeFormat` these helpers build, memoized by locale + zone + shape.
 *
 * Constructing one is by far the expensive part of formatting a date, and these helpers run once
 * per timestamp per row: a 500-row table with two dates a row builds a thousand of them. The
 * instances are date-INDEPENDENT (the date decides standard vs daylight time, and it is passed to
 * `format`/`formatToParts`), so one per key is correct for the life of the page.
 *
 * Unbounded on purpose: the key space is (6 locales) x (zones actually displayed) x (a dozen
 * shapes at most), and a page displays one or two zones.
 */
const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(key: string, locale: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat | null {
  const cacheKey = `${key}|${locale}|${options.timeZone}`;
  const cached = formatters.get(cacheKey);
  if (cached) return cached;
  try {
    const built = new Intl.DateTimeFormat(locale, options);
    formatters.set(cacheKey, built);
    return built;
  } catch {
    // An unusable zone or locale must not throw inside a render.
    return null;
  }
}

/**
 * Canonical id for a zone, so an alias and its modern name compare equal: a caller pinning
 * `Asia/Calcutta` for a reader on `Asia/Kolkata` has pinned their own zone, and labelling it
 * would be the repetition this module exists to avoid. Memoized through the same map as the
 * formatters.
 *
 * <p>ICU collapses only IANA LINKS, which by definition share their rules, so this can never
 * suppress a label where the wall clock actually differs; the only way it can be wrong is to keep
 * a redundant one. A runtime implementing the newer identity-preserving rule would stop collapsing
 * aliases, which degrades to exactly that. The `?? zone` arm is unreachable today (both arguments
 * are already validated ids) and is there so a future caller cannot trip on it.
 */
function canonicalZone(zone: string): string {
  return formatter('canon', 'en', { timeZone: zone })?.resolvedOptions().timeZone ?? zone;
}

/**
 * The zone's own short label ("GMT+9", "EST"), prefixed with a space - but ONLY when the value is
 * being drawn in a zone the CALLER pinned AND that zone is not the reader's own.
 *
 * <p>Four cases mean "print nothing", and only three of them are about the reader's own zone:
 * <ul>
   <li>no {@code timeZone} was given, so the display preference is in effect;</li>
   <li>one was given that {@link resolveTimeZone} rejects, so the display preference is in effect
       anyway and naming the pin would name a zone nothing is rendered in;</li>
   <li>the value is a bare calendar day, where {@link displayZoneFor} ignores the pin on purpose;</li>
   <li>the pin names the reader's own zone, which is the common case for a schedule created where
       its owner sits, and is exactly the restatement this module stopped making.</li>
 * </ul>
 *
 * <p>Read off {@code formatToParts} rather than sliced off a formatted string: where the zone name
 * sits inside a formatted time varies by locale, so taking it as a suffix would cut the wrong
 * characters in the languages that do not put it last.
 */
function pinnedZoneSuffix(
  date: Date,
  locale: string,
  input: string | Date,
  explicit?: string,
): string {
  if (!explicit || !isValidTimeZone(explicit)) return '';
  if (isCalendarDateOnly(input)) return '';
  if (canonicalZone(explicit) === canonicalZone(getClientTimeZone())) return '';
  const intl = formatter('zone', locale, { timeZone: explicit, timeZoneName: 'short' });
  if (!intl) return '';
  const label = intl.formatToParts(date).find((part) => part.type === 'timeZoneName')?.value;
  return label ? ` ${label}` : '';
}

/**
 * The zone to format in: the one the caller pinned, else the person's display zone. An
 * unusable pinned value falls back rather than throwing inside a render.
 */
function resolveTimeZone(explicit?: string): string {
  if (explicit && isValidTimeZone(explicit)) return explicit;
  return getClientTimeZone();
}

function resolveLocale(explicit?: string): string {
  if (explicit) return explicit;
  // Follow the APP locale (next-intl, from the URL), defaulting to 'en'. Never
  // navigator.language: that is the browser language, so a French-browser user
  // on the /en app would otherwise see French dates (e.g. "15 juin 2026").
  return getClientLocale();
}

/**
 * Pattern matching strings that already declare a timezone:
 *   - "...Z"          (Zulu / UTC)
 *   - "...+02:00"     (offset with colon)
 *   - "...+0200"      (offset without colon)
 *   - "...-08:00"     (negative offset variants)
 *
 * If a string omits the designator, we MUST interpret it as UTC, not as the
 * browser's local time. The backend serializes `LocalDateTime` as
 * `"2026-05-11T14:00:00"` with no `Z` (Jackson's JSR-310 default), but
 * Hibernate (`jdbc.time_zone=UTC`) reads/writes those columns as UTC
 * wall-clock. Without this guard, `new Date("2026-05-11T14:00:00")` in a
 * Paris browser would parse as Paris-local => 12:00 UTC instant - shifting
 * every legacy timestamp backward by the user's offset.
 */
const TZ_DESIGNATOR_RE = /(?:Z|[+-]\d{2}:?\d{2})$/;

function toDate(input: string | Date): Date {
  return parseUtcAware(input);
}

/**
 * True when the input is a CALENDAR DATE rather than a moment: a bare `YYYY-MM-DD`, with no time
 * of day at all.
 *
 * <p>The distinction decides which zone such a value may be rendered in, and getting it wrong is
 * silent. A day bucket from an analytics query, or a model's release date, names a DAY; it is
 * stored as UTC midnight only because a `Date` has nowhere else to put it. Render that instant in
 * any zone west of UTC and it becomes the PREVIOUS day: `2026-01-15` reads "Jan 14" in Los
 * Angeles. A chart axis, or a release date, would be wrong by one for every reader in the
 * Americas, with nothing on screen to suggest it.
 *
 * <p>So a value with no time of day keeps UTC even when the reader has a zone: there is no
 * instant to translate, only a date that is the same date everywhere.
 *
 * <p>Matched by SHAPE, not by the absence of a separator. The old test was "no T and no space",
 * which answered true for a bare `09:00` and for any junk string: a time of day was typed as a
 * DAY, which is how a legacy `HH:mm` row became unrecoverable in the editor (it was sliced for a
 * date, produced `09:00T10:15`, and was stored verbatim again). Exported so the table cell decides
 * the same way this module does, rather than keeping its own copy of the rule the whole feature
 * turns on.
 */
export function isCalendarDateOnly(input: string | Date | null | undefined): boolean {
  if (typeof input !== 'string') return false;
  const parts = /^(\d{4})-(\d{2})-(\d{2})$/.exec(input.trim());
  if (!parts) return false;

  // And the day has to EXIST. Shape alone accepted "2026-02-30", and the three functions that ask
  // this question then gave three answers for it: the formatters rendered "02 Mar 2026" (V8 rolls
  // the ISO string over), `dayEdgeInstant` answered null by design, and `instantOfWallClock`
  // resolved it to 2 March. Reachable from a table cell: editing the TIME of such a row moved it to
  // March while the cell went on displaying the same text, so nothing looked wrong.
  const [year, month, day] = parts.slice(1).map(Number);
  const probe = new Date(Date.UTC(year, month - 1, day));
  return probe.getUTCFullYear() === year
      && probe.getUTCMonth() === month - 1
      && probe.getUTCDate() === day;
}

/**
 * The zone to render {@code input} in: the reader's, unless the value names a calendar DAY with no
 * time of day, in which case UTC (see {@link isCalendarDateOnly}).
 *
 * <p>Shared by all three display formatters on purpose. The rule lived in {@link formatUtcDate}
 * alone, which left `formatUtcDateTime('2026-01-15')` rendering "Jan 14, 2026, 16:00" for a
 * reader in Los Angeles: a day that is not in the data, at an hour that was never stored, out of a
 * value carrying no time at all. A table column typed `datetime` over day-only rows is that call.
 *
 * <p>An explicit {@code timeZone} loses to the rule, deliberately: pinning a zone asks how one
 * INSTANT reads somewhere, and a bare day is not an instant. Pinning cannot make `2026-01-15`
 * anything but the 15th, so honouring it could only move the day.
 */
/**
 * Exported for the handful of places that need a shape none of the formatters produce (a
 * "Month YYYY" join date, for one) and therefore build their own `Intl` call. They still have to
 * answer the calendar-day question, and answering it with {@link getClientTimeZone} instead
 * silently moves a bare `YYYY-MM-DD` a day west of Greenwich - the exact defect the formatters
 * exist to prevent. Prefer a formatter; when you cannot, take the zone from here.
 */
export function displayZoneFor(input: string | Date, explicit?: string): string {
  return isCalendarDateOnly(input) ? 'UTC' : resolveTimeZone(explicit);
}

/**
 * Parse a backend-supplied date string into a JS Date, treating any string
 * without an explicit timezone designator as UTC.
 *
 * EXPORT this and use everywhere the frontend does `new Date(apiResponse)`
 * - including relative-time math (`Date.now() - parseUtcAware(x).getTime()`).
 * Otherwise relative-time helpers shift by the user's browser offset.
 */
export function parseUtcAware(input: string | Date): Date {
  if (typeof input !== 'string') return input;
  // Trimmed, because {@link isCalendarDateOnly} trims and these two have to agree.
  //
  // They did not. For " 2026-01-15" (routine in imported data) the predicate said "calendar day",
  // so the formatters rendered it in UTC, while this parser fell out of V8's ISO fast path into the
  // legacy LOCAL-time parser and produced the 14th at 23:00Z on a +01:00 host. One value, read as
  // two different days by two functions one line apart, which is the whole defect this module
  // exists to prevent.
  input = input.trim();
  const hasTime = input.includes('T') || input.includes(' ');
  if (!hasTime) return new Date(input);
  if (TZ_DESIGNATOR_RE.test(input)) return new Date(input);
  return new Date(input + 'Z');
}

/**
 * Format a date as a relative time string (e.g., "Just now", "5m ago", "2h ago").
 * Falls back to an absolute date, in the display zone, for entries older than 7 days.
 */
export function formatRelativeDate(
  dateString: string | Date | null | undefined,
  options?: {
    /** Custom label for "Never" when date is null/undefined */
    neverLabel?: string;
    /** Locale used for the absolute fallback past the relative window */
    locale?: string;
  }
): string {
  if (!dateString) {
    return options?.neverLabel || 'Never';
  }

  const date = toDate(dateString);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return 'Just now';
  if (diffMins < 60) return `${diffMins}m ago`;
  if (diffHours < 24) return `${diffHours}h ago`;
  if (diffDays < 7) return `${diffDays}d ago`;

  // The ORIGINAL value, not `date`: the absolute formatter decides whether this is a calendar day
  // or a moment by looking at the STRING, and a Date has already thrown that away. Latent today
  // (no caller passes a bare day) and the same mistake `formatUtcDateOrNull` was fixed for, so it
  // costs nothing to not repeat it.
  return formatUtcDateTime(dateString, { locale: options?.locale });
}

/**
 * Format a date as a relative time string with i18n support.
 * Used with next-intl translations. UTC-aware fallback past 7 days.
 *
 * The `t` translator must expose the keys `never`, `justNow`, `minutesAgo`,
 * `hoursAgo`, `daysAgo` (the last three take a `{count}` param).
 *
 * `locale` is the APP locale (from next-intl `useLocale()` in a React
 * component). It is forwarded to the >7-day absolute fallback so the month
 * name matches the visible UI language. When omitted, the fallback defaults
 * through `getClientLocale()` (URL `[locale]` prefix, else the `NEXT_LOCALE`
 * cookie, else 'en') - which resolves to the same value as the provider on
 * every route, so callers without hook access stay coherent too.
 */
export function formatRelativeDateI18n(
  dateString: string | Date | null | undefined,
  t: (key: string, params?: Record<string, string | number | Date>) => string,
  locale?: string
): string {
  if (!dateString) {
    return t('never');
  }

  const date = toDate(dateString);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return t('justNow');
  if (diffMins < 60) return t('minutesAgo', { count: diffMins });
  if (diffHours < 24) return t('hoursAgo', { count: diffHours });
  if (diffDays < 7) return t('daysAgo', { count: diffDays });

  // The ORIGINAL value, not `date`: the absolute formatter decides whether this is a calendar day
  // or a moment by looking at the STRING, and a Date has already thrown that away. Latent today
  // (no caller passes a bare day) and the same mistake `formatUtcDateOrNull` was fixed for, so it
  // costs nothing to not repeat it.
  return formatUtcDateTime(dateString, { locale });
}

/**
 * Format a date/time in the display zone, with a caller-supplied "Never" fallback.
 * Output: "Jan 21, 2026, 15:30"
 */
export function formatDateTime(
  dateString: string | Date | null | undefined,
  options?: {
    fallback?: string;
    locale?: string;
  }
): string {
  if (!dateString) {
    return options?.fallback || 'Never';
  }
  // The ORIGINAL value, not `date`: the absolute formatter decides whether this is a calendar day
  // or a moment by looking at the STRING, and a Date has already thrown that away. Latent today
  // (no caller passes a bare day) and the same mistake `formatUtcDateOrNull` was fixed for, so it
  // costs nothing to not repeat it.
  return formatUtcDateTime(dateString, { locale: options?.locale });
}

/**
 * Format a duration in milliseconds to a human-readable string.
 */
export function formatDuration(ms: number | undefined | null): string {
  if (!ms) return '-';

  const mins = Math.floor(ms / 60000);
  const secs = Math.floor((ms % 60000) / 1000);

  if (mins < 1) {
    const millis = ms % 1000;
    if (secs === 0) return `${millis}ms`;
    return `${secs}s`;
  }
  if (secs === 0) return `${mins}min`;
  return `${mins}m ${secs}s`;
}

/**
 * Calculate and format duration from start/end times.
 */
export function formatDurationFromTimes(
  startedAt: string | undefined | null,
  endedAt: string | undefined | null
): string {
  if (!startedAt || !endedAt) return '-';

  const duration = parseUtcAware(endedAt).getTime() - parseUtcAware(startedAt).getTime();
  return formatDuration(duration);
}

/* ------------------------------------------------------------------ *
 *  Absolute formatters - the canonical helpers other modules use.
 *  All public absolute formatting in the app flows through these: they
 *  resolve the display zone, once, in one place.
 * ------------------------------------------------------------------ */

/**
 * Full date+time in the display zone, which is the reader's unless the caller pins one.
 * Example: "Jan 21, 2026, 15:30". The zone is not named: see the note at the top of the file.
 */
export function formatUtcDateTime(
  dateString: string | Date | null | undefined,
  options?: {
    fallback?: string;
    locale?: string;
    /** Include seconds (default false). */
    withSeconds?: boolean;
    /** Pin a zone (a schedule trigger's own, say) instead of the reader's. */
    timeZone?: string;
  }
): string {
  if (!dateString) return options?.fallback || '-';
  const date = toDate(dateString);
  if (Number.isNaN(date.getTime())) return options?.fallback || '-';

  const locale = resolveLocale(options?.locale);
  const timeZone = displayZoneFor(dateString, options?.timeZone);
  const intl = formatter(options?.withSeconds ? 'datetime+s' : 'datetime', locale, {
    timeZone,
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: options?.withSeconds ? '2-digit' : undefined,
    hour12: false,
  });
  if (!intl) return options?.fallback || '-';
  return intl.format(date) + pinnedZoneSuffix(date, locale, dateString, options?.timeZone);
}

/**
 * A formatted date in the display zone, or {@code null} when the input cannot be read as one.
 *
 * <p>The difference from {@link formatUtcDate} is the ability to say NOTHING, which its
 * {@code fallback} cannot express: that option is read as {@code options?.fallback || '-'}, so
 * an empty string is falsy and comes back as a literal "-". A caller that wants to omit a whole
 * sentence rather than print a placeholder inside it therefore has to parse first, and two
 * callers had started doing that by hand. "+10,000 credits on -" is the sentence this exists to
 * prevent.
 */
export function formatUtcDateOrNull(
  dateString: string | Date | null | undefined,
  options?: { locale?: string; timeZone?: string }
): string | null {
  if (!dateString) return null;
  // `parseUtcAware` hands back anything that is not a string UNCHANGED, so a truthy non-Date
  // (the [y,M,d] array a mis-configured mapper serves, say) would reach `.getTime()` and throw,
  // taking the page down instead of rendering nothing. A helper whose whole contract is "or
  // null when the input cannot be read as one" must not crash on precisely that input, and a
  // caller who trusts the name would not add a guard of their own.
  const date = toDate(dateString);
  if (!(date instanceof Date) || Number.isNaN(date.getTime())) return null;
  // Delegate the ORIGINAL value, not the parsed Date. `formatUtcDate` decides whether this is a
  // calendar date or a moment by looking at the STRING (a bare `YYYY-MM-DD` names a day and must
  // keep UTC), and a Date has already thrown that away. Passing `date` here made this the one
  // helper where a model's release date still read a day early west of Greenwich, while its two
  // neighbours were correct.
  return formatUtcDate(dateString, options);
}

/**
 * Date only, in the display zone.
 * Example: "Jan 21, 2026". The zone still DECIDES the answer for a TIMESTAMP of which only the
 * date gets shown: one instant is a different calendar day in Auckland and in Los Angeles. It
 * is simply not printed, since the reader set it.
 *
 * <p>A bare `YYYY-MM-DD` is the other case and keeps UTC, including over an explicit
 * {@code timeZone} - see {@link displayZoneFor}. Use {@link formatCalendarDate} when the day has
 * already been turned into a `Date` and this can no longer tell.
 */
export function formatUtcDate(
  dateString: string | Date | null | undefined,
  options?: { fallback?: string; locale?: string; timeZone?: string }
): string {
  if (!dateString) return options?.fallback || '-';
  const date = toDate(dateString);
  if (Number.isNaN(date.getTime())) return options?.fallback || '-';

  const locale = resolveLocale(options?.locale);
  const timeZone = displayZoneFor(dateString, options?.timeZone);
  const intl = formatter('date', locale, {
    timeZone,
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });
  if (!intl) return options?.fallback || '-';
  return intl.format(date) + pinnedZoneSuffix(date, locale, dateString, options?.timeZone);
}

/**
 * The INSTANT a given calendar day starts (or ends) in a zone, as an ISO string.
 *
 * <p>For turning a `YYYY-MM-DD` a person typed into a date filter into the bounds a server-side
 * query needs. The day they typed is the day THEY are reading in, so the bounds have to be their
 * midnight, not UTC midnight: a file uploaded at 22:00 in Los Angeles is stored at 06:00 the next
 * UTC day, so "from the 15th to the 15th" against UTC bounds hides it while the row it would have
 * matched shows "Jan 15" on screen. Eight hours of a reader's day land in the wrong bucket, and
 * the filter looks simply broken rather than off by a zone.
 *
 * <p>Two passes because a zone's offset depends on the instant, and the instant is what we are
 * solving for: the first guess is corrected with the offset in force at that guess. That settles
 * every zone whose transition is not at midnight. Where midnight itself is skipped the two passes
 * land on the PREVIOUS day, which is not "the same real boundary" - it is an hour of the wrong day
 * inside the bound - so the walk-forward below fixes it.
 *
 * @param day a bare `YYYY-MM-DD`; anything else answers null
 * @param edge `start` for 00:00:00.000, `end` for 23:59:59.999
 * @param timeZone defaults to the reader's display zone
 */
/** `YYYY-MM-DD` from numeric parts, zero-padded, which is how calendar days compare. */
function dayKey(year: number, month: number, day: number): string {
  const pad = (n: number, w = 2) => String(n).padStart(w, '0');
  return `${pad(year, 4)}-${pad(month)}-${pad(day)}`;
}

export function dayEdgeInstant(
  day: string | null | undefined,
  edge: 'start' | 'end',
  timeZone?: string
): string | null {
  if (!day) return null;
  const parts = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day.trim());
  if (!parts) return null;
  const [year, month, dayOfMonth] = parts.slice(1).map(Number);

  // A day that does not exist answers null, which is what the contract above says and what the
  // arithmetic below does NOT do on its own: `Date.UTC(2026, 1, 30)` rolls "30 February" forward
  // to 2 March without complaint, so a filter asked for a day that never happened would silently
  // return a range starting two days later. Null means "no bound", which is the honest answer to
  // an unreadable date and the one the caller already handles.
  const probe = new Date(Date.UTC(year, month - 1, dayOfMonth));
  if (
    probe.getUTCFullYear() !== year
    || probe.getUTCMonth() !== month - 1
    || probe.getUTCDate() !== dayOfMonth
  ) {
    return null;
  }

  // An EXPLICIT zone this runtime cannot use answers null, rather than quietly falling back to the
  // reader's.
  //
  // `resolveTimeZone` dropping a bad id is right for a render - a date in the wrong zone still
  // reads as a date - and wrong here, because these two values bound a QUERY. A stored id the
  // runtime has never heard of would have scoped it to a different 24 hours with nothing on screen
  // to say so. Null is what the callers already treat as "no bound".
  if (timeZone && !isValidTimeZone(timeZone)) return null;

  const zone = resolveTimeZone(timeZone);

  // ONE solver, the same one `instantOfWallClock` uses.
  //
  // This ran its own two-pass solve plus a walk-forward on a 15-minute grid, capped at eight steps
  // under a comment claiming that "covers every real gap (one hour, or the half hour Lord Howe
  // uses)". Two IANA days are 24-hour gaps: Pacific/Apia skipped 2011-12-30 entirely and
  // Pacific/Kiritimati skipped 1994-12-31, both for a dateline change. The walk exhausted its
  // steps and answered an instant on the PREVIOUS day, so a filter asked for a day that never
  // happened was silently scoped to the 24 hours before it.
  const startOf = (y: number, m: number, d: number): number | null => {
    const solved = zonedTimeToInstant(zone, y, m, d, 0, 0);
    if (Number.isNaN(solved.getTime())) return null;
    // A day the zone skipped entirely resolves forward into the NEXT one, which is not a bound for
    // the day that was asked for. Null is the honest answer, the same one 30 February gets.
    return calendarDayIn(solved, zone) === dayKey(y, m, d) ? solved.getTime() : null;
  };

  const start = startOf(year, month, dayOfMonth);
  if (edge === 'start') return start === null ? null : new Date(start).toISOString();
  if (start === null) return null;

  // The END bound is the first instant of the NEXT day, minus a millisecond.
  //
  // Not "solve 23:59:59.999", which is wrong in both directions and was wrong in both:
  //   - where the clocks go back AT midnight the day has TWO 23:59s and the solver answers the
  //     earlier one, so a filter "up to the 4th" dropped everything in the repeated 23:00 hour
  //     (America/Santiago 2026-04-04, America/Nuuk and America/Scoresbysund 2026-10-24);
  //   - where the clocks go forward AT 23:00 there is NO 23:59 at all - the local clock jumps from
  //     22:59 to 00:00 - so the solve landed in the next day, the day check rejected it, and the
  //     upper bound vanished entirely (America/Godthab and America/Scoresbysund, every spring
  //     transition: 2025-03-29, 2026-03-28, 2027-03-27). A filter "up to that day" then had no upper
  //     bound and showed everything after it.
  // An hour-forward step fixed the first and created nothing for the second. Taking the next day's
  // first moment needs no case analysis: it is the last instant of THIS day by construction, for a
  // 23-hour day, a 25-hour day and an ordinary one alike. Verified over every IANA zone across
  // 2025-2027 (457,710 day-edges): no bound leaks into a neighbouring day and none is missing.
  //
  // The next day can itself be missing - Pacific/Apia skipped 2011-12-30 - so this walks forward
  // until it finds one that exists. Three days is enough for every transition in the database and
  // the loop is bounded so a runtime that disagrees with itself cannot hang.
  for (let ahead = 1; ahead <= 3; ahead += 1) {
    const next = new Date(Date.UTC(year, month - 1, dayOfMonth + ahead));
    const nextStart = startOf(next.getUTCFullYear(), next.getUTCMonth() + 1, next.getUTCDate());
    if (nextStart !== null) return new Date(nextStart - 1).toISOString();
  }
  return null;
}

/**
 * `YYYY-MM-DD` for the calendar day `instant` falls on in `timeZone`.
 *
 * <p>Exported because `filesGrouping` needs exactly this to bucket by the reader's day, and had
 * its own copy that built a fresh `Intl.DateTimeFormat` per entry - the ~292 microseconds a call
 * that the formatter cache exists to remove, paid once per row of a file list.
 */
export function calendarDayIn(instant: Date, timeZone: string): string {
  const intl = formatter('calendar-day-probe', 'en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  // `en-CA` renders `YYYY-MM-DD`, which compares lexicographically the way dates should.
  return intl ? intl.format(instant) : instant.toISOString().slice(0, 10);
}

/**
 * The INSTANT at which a given wall clock reads `wallClock` in `timeZone`.
 *
 * <p>The inverse of displaying a date: a `datetime-local` input hands back what the person typed,
 * with no zone, and that has to be read as their wall clock rather than as UTC.
 *
 * <p>Delegates to {@link zonedTimeToInstant}, which is the same problem the agenda solved first.
 * There were two solvers: that one, and a private two-pass-plus-walk-forward here. They agreed on
 * an ordinary clock and disagreed on the two hours a year that matter. Typing 02:30 on a
 * spring-forward morning stored 03:00 through a table cell and 03:30 through the agenda's own
 * dialog, in the same product, for the same reason neither file mentioned the other.
 *
 * <p>The one that survives is the one that CHECKS its answer: it reads the zone clock back at each
 * candidate instant and keeps only the candidates that agree with what was asked for. That is what
 * makes a nonexistent wall clock resolve FORWARD by the length of the gap (02:30 becomes 03:30,
 * which is `ZonedDateTime.ofLocal` and every calendar) instead of backwards past it, and it is
 * already pinned across all 418 IANA zones by `agendaTime`.
 *
 * <p>ONE wall clock stays ambiguous, and no conversion can fix it: on the morning a zone falls
 * back, 01:30 happens twice, an hour apart, and `datetime-local` has no way to say which is meant.
 * The answer is the occurrence under the offset in force at the naive point, which is the later one
 * in a zone east of Greenwich and the earlier one west of it. Worth knowing the error's shape: one
 * hour, in the same wall clock the person is reading, not the offset-sized jump this helper exists
 * for, where a Tokyo reader retyping what the cell showed moved the stored instant by nine hours. A
 * caller that cannot tolerate even an hour should not be using a `datetime-local`; it should ask
 * for the zone alongside the time.
 *
 * @param wallClock `YYYY-MM-DDTHH:mm` (seconds optional), exactly what the input produces
 * @returns the ISO instant, or null when the input is not a wall clock
 */
export function instantOfWallClock(wallClock: string, timeZone?: string): string | null {
  const parts = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/.exec(wallClock.trim());
  if (!parts) return null;
  const [year, month, day, hour, minute] = parts.slice(1, 6).map(Number);
  const second = parts[6] ? Number(parts[6]) : 0;

  const zone = resolveTimeZone(timeZone);
  const resolved = zonedTimeToInstant(zone, year, month, day, hour, minute);
  if (Number.isNaN(resolved.getTime())) return null;
  // Seconds are carried separately: `zonedTimeToInstant` takes a wall clock to the minute, which is
  // all a calendar grid needs and all a `datetime-local` without a step emits. They matter here
  // because this is also the write path for a cell that HELD seconds, and dropping them would
  // silently truncate a stored instant on an edit that only touched the date.
  return new Date(resolved.getTime() + second * 1000).toISOString();
}

/**
 * The wall clock `instant` reads at in `timeZone`, as `YYYY-MM-DDTHH:mm`.
 *
 * <p>What a `datetime-local` input wants: the value a person would read off a clock there, with
 * no zone in it. Built from parts rather than from `toISOString`, which is always UTC - handing an
 * input a UTC string while the cell beside it displays another zone is how somebody retypes what
 * they were shown and moves the stored instant by the whole offset.
 */
export function wallClockIn(instant: Date, timeZone?: string, shape: 'date' | 'time' | 'datetime' = 'datetime'): string {
  const zone = resolveTimeZone(timeZone);
  const intl = formatter(`wall-${shape}`, 'en-CA', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
  if (!intl) return '';
  const read: Record<string, string> = {};
  for (const part of intl.formatToParts(instant)) {
    if (part.type !== 'literal') read[part.type] = part.value;
  }
  // Some runtimes render midnight as hour 24.
  const hour = read.hour === '24' ? '00' : read.hour;
  const day = `${read.year}-${read.month}-${read.day}`;
  const time = `${hour}:${read.minute}`;
  if (shape === 'date') return day;
  if (shape === 'time') return time;
  return `${day}T${time}`;
}


/**
 * A CALENDAR DATE: a day that is the same day for everyone, never translated into a zone.
 *
 * <p>Use this for a value that names a DAY rather than a moment: a daily bucket on a chart axis,
 * a release date, a billing period boundary. {@link formatUtcDate} recognises a bare
 * `YYYY-MM-DD` on its own, but a caller that has already turned its day into UTC midnight (or
 * into a `Date`) has to say so, because by then nothing distinguishes it from an instant that
 * happens to fall at midnight.
 *
 * <p>Carries no zone label: there is no zone to name, and "15 Jan 2026 UTC" on a chart axis
 * invites the reader to do a conversion that would make it wrong.
 */
export function formatCalendarDate(
  dateString: string | Date | null | undefined,
  options?: { fallback?: string; locale?: string }
): string {
  if (!dateString) return options?.fallback || '-';
  const date = toDate(dateString);
  if (Number.isNaN(date.getTime())) return options?.fallback || '-';

  const intl = formatter('calendar', resolveLocale(options?.locale), {
    timeZone: 'UTC',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });
  return intl ? intl.format(date) : options?.fallback || '-';
}

/**
 * Time only (HH:mm) in the display zone.
 * Example: "15:30".
 */
export function formatUtcTime(
  dateString: string | Date | null | undefined,
  options?: { fallback?: string; locale?: string; withSeconds?: boolean; timeZone?: string }
): string {
  if (!dateString) return options?.fallback || '-';
  const date = toDate(dateString);
  if (Number.isNaN(date.getTime())) return options?.fallback || '-';

  const locale = resolveLocale(options?.locale);
  const timeZone = displayZoneFor(dateString, options?.timeZone);
  const intl = formatter(options?.withSeconds ? 'time+s' : 'time', locale, {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
    second: options?.withSeconds ? '2-digit' : undefined,
    hour12: false,
  });
  if (!intl) return options?.fallback || '-';
  return intl.format(date) + pinnedZoneSuffix(date, locale, dateString, options?.timeZone);
}

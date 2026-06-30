import { getClientLocale } from './locale';
/**
 * Centralized Date Formatting Utilities
 *
 * Single source of truth for date formatting across the application.
 *
 * Project rule: all absolute timestamps are displayed in UTC (server stores
 * everything in UTC). Functions in this module pass `timeZone: 'UTC'` to every
 * Intl call and append a literal " UTC" suffix so the user can tell at a glance
 * that the timestamp is not local. Relative-time branches ("5m ago") are
 * timezone-neutral and intentionally do not carry the suffix.
 *
 * If a caller genuinely needs the browser's local timezone (e.g. user picked
 * a recurring trigger zone in the workflow builder), it must format the date
 * itself and NOT route through these helpers.
 */

const UTC_SUFFIX = ' UTC';

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
 * Parse a backend-supplied date string into a JS Date, treating any string
 * without an explicit timezone designator as UTC.
 *
 * EXPORT this and use everywhere the frontend does `new Date(apiResponse)`
 * - including relative-time math (`Date.now() - parseUtcAware(x).getTime()`).
 * Otherwise relative-time helpers shift by the user's browser offset.
 */
export function parseUtcAware(input: string | Date): Date {
  if (typeof input !== 'string') return input;
  const hasTime = input.includes('T') || input.includes(' ');
  if (!hasTime) return new Date(input);
  if (TZ_DESIGNATOR_RE.test(input)) return new Date(input);
  return new Date(input + 'Z');
}

/**
 * Format a date as a relative time string (e.g., "Just now", "5m ago", "2h ago").
 * Falls back to a UTC absolute date for entries older than 7 days.
 */
export function formatRelativeDate(
  dateString: string | Date | null | undefined,
  options?: {
    /** Custom label for "Never" when date is null/undefined */
    neverLabel?: string;
    /** Locale used for the UTC-suffixed fallback */
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

  return formatUtcDateTime(date, { locale: options?.locale });
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

  return formatUtcDateTime(date, { locale });
}

/**
 * Format a date/time in UTC.
 * Output: "21 Jan, 14:30 UTC"
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
  return formatUtcDateTime(toDate(dateString), { locale: options?.locale });
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
 *  UTC absolute formatters - the canonical helpers other modules use.
 *  All public absolute formatting in the app should flow through these.
 * ------------------------------------------------------------------ */

/**
 * Full date+time in UTC, suffixed " UTC". Example: "21 Jan 2026, 14:30 UTC".
 */
export function formatUtcDateTime(
  dateString: string | Date | null | undefined,
  options?: {
    fallback?: string;
    locale?: string;
    /** Include seconds (default false). */
    withSeconds?: boolean;
  }
): string {
  if (!dateString) return options?.fallback || '-';
  const date = toDate(dateString);
  if (Number.isNaN(date.getTime())) return options?.fallback || '-';

  const intl = new Intl.DateTimeFormat(resolveLocale(options?.locale), {
    timeZone: 'UTC',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: options?.withSeconds ? '2-digit' : undefined,
    hour12: false,
  });
  return intl.format(date) + UTC_SUFFIX;
}

/**
 * Date only in UTC. Example: "21 Jan 2026 UTC".
 */
export function formatUtcDate(
  dateString: string | Date | null | undefined,
  options?: { fallback?: string; locale?: string }
): string {
  if (!dateString) return options?.fallback || '-';
  const date = toDate(dateString);
  if (Number.isNaN(date.getTime())) return options?.fallback || '-';

  const intl = new Intl.DateTimeFormat(resolveLocale(options?.locale), {
    timeZone: 'UTC',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });
  return intl.format(date) + UTC_SUFFIX;
}

/**
 * Time only in UTC (HH:mm). Example: "14:30 UTC".
 */
export function formatUtcTime(
  dateString: string | Date | null | undefined,
  options?: { fallback?: string; locale?: string; withSeconds?: boolean }
): string {
  if (!dateString) return options?.fallback || '-';
  const date = toDate(dateString);
  if (Number.isNaN(date.getTime())) return options?.fallback || '-';

  const intl = new Intl.DateTimeFormat(resolveLocale(options?.locale), {
    timeZone: 'UTC',
    hour: '2-digit',
    minute: '2-digit',
    second: options?.withSeconds ? '2-digit' : undefined,
    hour12: false,
  });
  return intl.format(date) + UTC_SUFFIX;
}

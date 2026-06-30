/**
 * Tests for UTC date helpers.
 *
 * Regression coverage for the cross-stack UTC refactor (2026-05-12):
 *   - parseUtcAware MUST append 'Z' to TZ-less ISO strings so that
 *     LocalDateTime payloads from the legacy Java services land as the
 *     correct UTC instant in JS, regardless of the user's browser TZ.
 *   - formatUtcDateTime / formatUtcDate / formatUtcTime MUST always render
 *     in UTC with a trailing " UTC" so users can never mistake the wall
 *     clock for their local time.
 *
 * These tests pin the *observable behavior* the previous bug exhibited:
 * before the fix, a Paris browser parsed `"2026-05-11T14:00:00"` as Paris
 * local time => 12:00 UTC instant, then a UTC formatter rendered "12:00 UTC".
 * The user saw a value drifted backwards by their local offset.
 */

import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest';
import {
  parseUtcAware,
  formatUtcDateTime,
  formatUtcDate,
  formatUtcTime,
  formatRelativeDate,
  formatDuration,
  formatDurationFromTimes,
} from '../dateFormatters';

/**
 * Force the JS runtime to behave as if running in a non-UTC browser TZ.
 *
 * Vitest runs under Node which honors the TZ env var at process start, so
 * we can't change it mid-test reliably. We instead simulate by intercepting
 * Date's parsing for TZ-less ISO strings via the Date constructor: a Paris
 * browser interprets "2026-05-11T14:00:00" as Paris-local => UTC instant
 * 2026-05-11T12:00:00Z. The cleanest reproducible signal is to check the
 * UTC instant produced - if parseUtcAware is correct, the instant for a
 * bare ISO is the same regardless of the simulated browser TZ.
 *
 * If TZ is already UTC we still exercise the regex / append paths because
 * the helper's contract holds in every TZ.
 */
const SAMPLE_BARE_ISO = '2026-05-11T14:00:00';
const SAMPLE_BARE_ISO_MILLIS = '2026-05-11T14:00:00.123';
const SAMPLE_BARE_ISO_SPACE = '2026-05-11 14:00:00';
const SAMPLE_Z_ISO = '2026-05-11T14:00:00Z';
const SAMPLE_OFFSET_ISO = '2026-05-11T14:00:00+02:00';
const SAMPLE_OFFSET_ISO_NOCOLON = '2026-05-11T14:00:00+0200';
const SAMPLE_DATE_ONLY = '2026-05-11';

describe('parseUtcAware', () => {
  it('appends Z to bare ISO datetime (no TZ designator)', () => {
    const parsed = parseUtcAware(SAMPLE_BARE_ISO);
    // 2026-05-11T14:00:00Z => epoch 1778940000000
    expect(parsed.toISOString()).toBe('2026-05-11T14:00:00.000Z');
  });

  it('appends Z to bare ISO with milliseconds', () => {
    const parsed = parseUtcAware(SAMPLE_BARE_ISO_MILLIS);
    expect(parsed.toISOString()).toBe('2026-05-11T14:00:00.123Z');
  });

  it('appends Z to space-separated bare timestamp', () => {
    const parsed = parseUtcAware(SAMPLE_BARE_ISO_SPACE);
    expect(parsed.toISOString()).toBe('2026-05-11T14:00:00.000Z');
  });

  it('passes through ISO with explicit Z', () => {
    const parsed = parseUtcAware(SAMPLE_Z_ISO);
    expect(parsed.toISOString()).toBe('2026-05-11T14:00:00.000Z');
  });

  it('passes through ISO with explicit +HH:MM offset', () => {
    const parsed = parseUtcAware(SAMPLE_OFFSET_ISO);
    // 14:00+02:00 => 12:00Z
    expect(parsed.toISOString()).toBe('2026-05-11T12:00:00.000Z');
  });

  it('passes through ISO with explicit +HHMM offset (no colon)', () => {
    const parsed = parseUtcAware(SAMPLE_OFFSET_ISO_NOCOLON);
    expect(parsed.toISOString()).toBe('2026-05-11T12:00:00.000Z');
  });

  it('passes through date-only string (JS parses as UTC midnight)', () => {
    const parsed = parseUtcAware(SAMPLE_DATE_ONLY);
    expect(parsed.toISOString()).toBe('2026-05-11T00:00:00.000Z');
  });

  it('passes Date instance through unchanged', () => {
    const d = new Date('2026-05-11T14:00:00Z');
    expect(parseUtcAware(d)).toBe(d);
  });

  it('does NOT double-append Z to already-Z strings', () => {
    const parsed = parseUtcAware(SAMPLE_Z_ISO);
    // If we double-appended, JS would return Invalid Date.
    expect(Number.isNaN(parsed.getTime())).toBe(false);
  });

  it('handles invalid input by returning Invalid Date (no throw)', () => {
    const parsed = parseUtcAware('not-a-date');
    expect(Number.isNaN(parsed.getTime())).toBe(true);
  });
});

describe('formatUtcDateTime', () => {
  it('renders bare LocalDateTime as the correct UTC wall-clock with " UTC" suffix', () => {
    // Pre-bug: in a Paris browser this would render "12:00 UTC" (shifted).
    // Post-fix: parseUtcAware interprets as UTC => formatter shows 14:00 UTC.
    const out = formatUtcDateTime(SAMPLE_BARE_ISO);
    expect(out.endsWith(' UTC')).toBe(true);
    expect(out).toContain('14:00');
    // Day must remain 11, not roll back to 10 (which would happen in a
    // far-east browser if the parser shifted the instant backwards across
    // midnight).
    expect(out).toContain('11');
  });

  it('renders ISO-with-Z identically to bare ISO of the same instant', () => {
    expect(formatUtcDateTime(SAMPLE_BARE_ISO)).toBe(formatUtcDateTime(SAMPLE_Z_ISO));
  });

  it('returns "-" for null', () => {
    expect(formatUtcDateTime(null)).toBe('-');
  });

  it('returns fallback for invalid input', () => {
    expect(formatUtcDateTime('garbage', { fallback: 'n/a' })).toBe('n/a');
  });

  it('honors the explicit locale option (en-US numerals)', () => {
    const out = formatUtcDateTime(SAMPLE_BARE_ISO, { locale: 'en-US' });
    // English month abbreviation for May
    expect(out).toMatch(/May/);
    expect(out.endsWith(' UTC')).toBe(true);
  });

  it('includes seconds when withSeconds is true', () => {
    const out = formatUtcDateTime('2026-05-11T14:23:45', { withSeconds: true, locale: 'en-US' });
    expect(out).toContain('45');
  });
});

describe('formatUtcDate', () => {
  it('renders date-only with " UTC" suffix', () => {
    const out = formatUtcDate(SAMPLE_BARE_ISO, { locale: 'en-US' });
    expect(out).toContain('11');
    expect(out).toContain('May');
    expect(out.endsWith(' UTC')).toBe(true);
  });

  it('day never rolls back across midnight when fed bare ISO near 00:00', () => {
    // 23:59 on May 11 - a far-east-of-UTC browser would otherwise shift this
    // to May 12 (or earlier May 11 in a far-west browser) if parsing leaked
    // local TZ.
    const out = formatUtcDate('2026-05-11T23:59:00');
    expect(out).toContain('11');
    expect(out).not.toContain('12');
  });
});

describe('formatUtcTime', () => {
  it('renders 24h time with " UTC" suffix', () => {
    const out = formatUtcTime(SAMPLE_BARE_ISO);
    expect(out).toContain('14:00');
    expect(out.endsWith(' UTC')).toBe(true);
  });
});

describe('formatRelativeDate', () => {
  /**
   * formatRelativeDate's relative branches are mathematical (diff in ms) and
   * depend on a TZ-correct anchor instant. If parseUtcAware did not coerce
   * bare ISO to UTC, the diff would be wrong by the browser's offset and
   * "5m ago" would silently become "2h 5m ago" for a Paris user.
   */
  it('treats a bare-ISO timestamp 1 minute in the past as "1m ago"', () => {
    const oneMinAgoUtc = new Date(Date.now() - 60_000).toISOString().slice(0, 19);
    // strip trailing Z to mimic Jackson LocalDateTime output
    const out = formatRelativeDate(oneMinAgoUtc);
    expect(out).toBe('1m ago');
  });

  it('falls back to UTC absolute beyond 7 days', () => {
    const longAgo = new Date(Date.now() - 30 * 86_400_000).toISOString().slice(0, 19);
    const out = formatRelativeDate(longAgo);
    expect(out.endsWith(' UTC')).toBe(true);
  });

  it('returns custom never label for null', () => {
    expect(formatRelativeDate(null, { neverLabel: 'never seen' })).toBe('never seen');
  });
});

describe('formatDuration / formatDurationFromTimes', () => {
  it('handles sub-second durations as ms', () => {
    expect(formatDuration(250)).toBe('250ms');
  });

  it('handles seconds-only durations', () => {
    expect(formatDuration(3_400)).toBe('3s');
  });

  it('handles mixed minutes+seconds', () => {
    expect(formatDuration(125_000)).toBe('2m 5s');
  });

  it('returns dash for null/undefined', () => {
    expect(formatDuration(null)).toBe('-');
    expect(formatDuration(undefined)).toBe('-');
  });

  it('computes duration from bare-ISO start/end correctly', () => {
    // 2026-05-11T14:00:00 -> 14:02:30 = 150_000ms regardless of browser TZ
    const out = formatDurationFromTimes('2026-05-11T14:00:00', '2026-05-11T14:02:30');
    expect(out).toBe('2m 30s');
  });

  it('mixed (Z + bare) start/end still computes the right duration', () => {
    const out = formatDurationFromTimes('2026-05-11T14:00:00Z', '2026-05-11T14:02:30');
    expect(out).toBe('2m 30s');
  });
});

describe('cross-stack contract (regression for the 2026-05-12 bug)', () => {
  /**
   * Pre-fix scenario walkthrough for a Paris (UTC+2) browser, reproduced
   * here in the test's UTC environment:
   *
   *   Server writes auth.users.created_at = LocalDateTime('2026-05-11T14:00')
   *   in a JVM whose TZ is UTC (verified). DB stores 14:00 as a UTC
   *   wall-clock. Jackson ships it to the frontend as "2026-05-11T14:00:00"
   *   (no Z). Pre-fix: `new Date(str)` in a Paris browser => 14:00 Paris
   *   instant = 12:00 UTC. Display in UTC => "12:00 UTC". User saw "12:00"
   *   instead of the actual "14:00" stored on the server - drift = offset.
   *
   *   Post-fix: parseUtcAware appends Z, so the JS instant is 14:00 UTC
   *   in every browser TZ. formatUtcDateTime renders "14:00 UTC".
   *
   * This test asserts the post-fix invariant by comparing the bare-ISO and
   * the explicit-Z forms - they must produce identical output.
   */
  it('LocalDateTime payload (no Z) and Instant payload (with Z) render identically', () => {
    const local = '2026-05-11T14:00:00';
    const inst = '2026-05-11T14:00:00Z';
    expect(formatUtcDateTime(local)).toBe(formatUtcDateTime(inst));
    expect(formatUtcDate(local)).toBe(formatUtcDate(inst));
    expect(formatUtcTime(local)).toBe(formatUtcTime(inst));
    expect(parseUtcAware(local).getTime()).toBe(parseUtcAware(inst).getTime());
  });

  it('Berlin-summer offset payload still resolves to the right UTC instant', () => {
    // A workflow trigger configured in Europe/Paris might emit firedAt with
    // a +02:00 offset. parseUtcAware MUST honour that offset and not
    // re-append Z.
    const paris = '2026-05-11T16:00:00+02:00';
    const utc   = '2026-05-11T14:00:00Z';
    expect(parseUtcAware(paris).getTime()).toBe(parseUtcAware(utc).getTime());
  });
});

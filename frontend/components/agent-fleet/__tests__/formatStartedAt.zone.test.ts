/**
 * @vitest-environment jsdom
 *
 * "Today" in the fleet dashboard's run list means today FOR THE READER.
 *
 * <p>The list drops the date from a run that started today and keeps it otherwise, so the two
 * halves have to agree on which day "today" is. They both used to be UTC. When the rendering moved
 * to the reader's display zone and the comparison did not, the list started omitting the date from
 * runs that are not today at all: for a reader in Tokyo at 08:00, a run from YESTERDAY 09:30 JST is
 * still the same UTC day, so it rendered as a bare "09:30" and reads as this morning, while a
 * run from 03:00 this morning fell on a different UTC day and got a full date. The list is wrong in
 * both directions at once, and nothing on screen suggests it.
 *
 * <p>Two things make this worth its own file rather than an assertion somewhere else. The component
 * is 1900 lines and mounting it needs its data, its charts and its container measurements; and the
 * change that introduced the bug shipped with a source scanner (`lib/utils/__tests__/calendarAxes`)
 * that reads the dashboard and walked straight past the defect, because it only inspects windows around
 * the axis formatters. A scan that opens a file and cannot see the bug in it is the reason to test
 * behaviour instead.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

import { formatStartedAt } from '../agentRunTime';
import { applyDisplayTimeZone, clearDisplayTimeZone } from '@/lib/utils/timezone';

/** 2026-01-15 23:00 UTC, which is already the 16th in Tokyo and still the 15th in Paris. */
const NOW = new Date('2026-01-15T23:00:00Z');

beforeEach(() => {
  clearDisplayTimeZone();
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
  clearDisplayTimeZone();
});

describe('a reader whose day is ahead of UTC', () => {
  beforeEach(() => applyDisplayTimeZone('Asia/Tokyo'));

  it('keeps the date on a run from YESTERDAY that shares the UTC day', () => {
    // 2026-01-15 00:30 UTC is 09:30 on the 15th in Tokyo, and "now" is the 16th there. Same UTC
    // day as now, different day for the reader: the date has to stay, or the row claims this
    // morning.
    const shown = formatStartedAt('2026-01-15T00:30:00Z');

    expect(shown).toContain('15');
    expect(shown).toContain('Jan');
  });

  it('drops the date on a run from TODAY that falls on another UTC day', () => {
    // 2026-01-15 23:30 UTC is 08:30 on the 16th in Tokyo, which IS today for them, even though it
    // is a different UTC day from... itself an hour ago. Short form, no date.
    const shown = formatStartedAt('2026-01-15T23:30:00Z');

    expect(shown).toContain('08:30');
    expect(shown).not.toContain('Jan');
  });
});

describe('a reader on UTC, where the old behaviour was already right', () => {
  beforeEach(() => applyDisplayTimeZone('UTC'));

  it('drops the date for today', () => {
    const shown = formatStartedAt('2026-01-15T09:30:00Z');

    expect(shown).toContain('09:30');
    expect(shown).not.toContain('Jan');
  });

  it('keeps it for another day', () => {
    const shown = formatStartedAt('2026-01-14T09:30:00Z');

    expect(shown).toContain('14');
    expect(shown).toContain('Jan');
  });
});

describe('a reader whose day is behind UTC', () => {
  beforeEach(() => applyDisplayTimeZone('America/Los_Angeles'));

  it('drops the date for a run that is still today where they are', () => {
    // "Now" is 23:00 UTC on the 15th = 15:00 on the 15th in Los Angeles. A run at 17:00 UTC is
    // 09:00 the same local day.
    const shown = formatStartedAt('2026-01-15T17:00:00Z');

    expect(shown).toContain('09:00');
    expect(shown).not.toContain('Jan');
  });

  it('keeps it for a run that was yesterday where they are', () => {
    const shown = formatStartedAt('2026-01-15T01:00:00Z');

    expect(shown).toContain('14');
    expect(shown).toContain('Jan');
  });
});

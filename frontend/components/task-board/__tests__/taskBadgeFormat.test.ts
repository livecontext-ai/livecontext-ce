import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * TC-002 regression: the task-card due-date badge must follow the APP locale (getClientLocale),
 * not the browser locale. Pre-fix it called toLocaleDateString(undefined, ...), which formats in
 * the browser/runner language - so a French-browser user on the /en app saw "19 juin" not "Jun 19".
 * We mock getClientLocale and assert the rendered month switches with the APP locale; the pre-fix
 * code ignored this mock (undefined arg) and these assertions fail against it.
 */
const h = vi.hoisted(() => ({ locale: 'en', zone: 'UTC' }));
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => h.locale }));
// `isValidTimeZone` is named because `dateFormatters` imports it: the factory replaces the whole
// module, so omitting a member leaves it undefined and the only reason that has not thrown is that
// `resolveTimeZone` short-circuits on a falsy explicit zone before calling it.
vi.mock('@/lib/utils/timezone', () => ({
  getClientTimeZone: () => h.zone,
  isValidTimeZone: (zone: string) => {
    try {
      new Intl.DateTimeFormat('en', { timeZone: zone });
      return true;
    } catch {
      return false;
    }
  },
}));

import { formatDueShort } from '../taskBadgeFormat';

describe('formatDueShort - app-locale due-date badge (TC-002)', () => {
  // An explicit UTC instant, read in an explicit zone. `new Date(2026, 5, 19, 12)` is noon in the
  // RUNNER's zone, which stopped being safe the day this badge began formatting in the READER's:
  // noon local is the previous UTC day east of +12, so the suite would go red on a machine in
  // Auckland and nowhere else. Midday UTC is unambiguous for a UTC reader on any machine.
  const jun19 = new Date('2026-06-19T12:00:00Z');

  beforeEach(() => {
    // Both are module-level mock state shared by the whole file, so a test that changes the zone
    // would otherwise decide what the next one reads.
    h.locale = 'en';
    h.zone = 'UTC';
  });

  it('reads a day-only STRING as that day, in any zone, like the tooltip beside it', () => {
    // The case the signature was widened for, and the one every test here missed by passing a Date.
    // A parsed Date stringifies to an instant, so `displayZoneFor` can no longer tell the value named
    // a DAY and translates it into the reader's zone: the badge said 18 June while its own tooltip,
    // built from the same field on the same element, said the 19th.
    h.zone = 'America/Los_Angeles';

    expect(formatDueShort('2026-06-19')).toBe('Jun 19');
  });

  it('still reads a real timestamp in the reader zone, so not everything became a day', () => {
    h.zone = 'Asia/Tokyo';

    // 23:00Z on the 18th is already the 19th in Tokyo.
    expect(formatDueShort('2026-06-18T23:00:00Z')).toBe('Jun 19');
  });

  it('uses the APP locale (en) -> "Jun 19"', () => {
    expect(formatDueShort(jun19)).toBe('Jun 19');
  });

  it('follows a switch to the French APP locale -> "19 juin" (fails pre-fix, which used the browser locale)', () => {
    h.locale = 'fr';
    expect(formatDueShort(jun19)).toBe('19 juin');
  });

  it('names the day in the READER\'s zone, which is the whole point near midnight', () => {
    // 22:30 UTC on the 19th is already the 20th in Tokyo. The badge used to format in the
    // browser's zone with no zone argument at all, so it could name a different day than the
    // full date in its own tooltip, and than the same due date on the task's page.
    const lateOnJun19Utc = new Date('2026-06-19T22:30:00Z');

    h.zone = 'UTC';
    expect(formatDueShort(lateOnJun19Utc)).toBe('Jun 19');

    h.zone = 'Asia/Tokyo';
    expect(formatDueShort(lateOnJun19Utc)).toBe('Jun 20');
  });
});

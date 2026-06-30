import { describe, it, expect, vi } from 'vitest';

/**
 * TC-002 regression: the task-card due-date badge must follow the APP locale (getClientLocale),
 * not the browser locale. Pre-fix it called toLocaleDateString(undefined, ...), which formats in
 * the browser/runner language - so a French-browser user on the /en app saw "19 juin" not "Jun 19".
 * We mock getClientLocale and assert the rendered month switches with the APP locale; the pre-fix
 * code ignored this mock (undefined arg) and these assertions fail against it.
 */
const h = vi.hoisted(() => ({ locale: 'en' }));
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => h.locale }));

import { formatDueShort } from '../taskBadgeFormat';

describe('formatDueShort - app-locale due-date badge (TC-002)', () => {
  // Local noon so the day never crosses a boundary regardless of the test runner's timezone.
  const jun19 = new Date(2026, 5, 19, 12, 0, 0);

  it('uses the APP locale (en) -> "Jun 19"', () => {
    h.locale = 'en';
    expect(formatDueShort(jun19)).toBe('Jun 19');
  });

  it('follows a switch to the French APP locale -> "19 juin" (fails pre-fix, which used the browser locale)', () => {
    h.locale = 'fr';
    expect(formatDueShort(jun19)).toBe('19 juin');
  });
});

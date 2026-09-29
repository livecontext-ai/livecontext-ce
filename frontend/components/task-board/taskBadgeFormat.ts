import { getClientLocale } from '@/lib/utils/locale';
import { displayZoneFor, parseUtcAware } from '@/lib/utils/dateFormatters';

/**
 * Compact short due date for the task-card badge, e.g. "Jun 19" (F5 due-date badge).
 *
 * Follows the APP locale (next-intl, via getClientLocale) - NOT the browser locale. Passing
 * `undefined` to toLocaleDateString defaults to the browser language, so a French-browser user
 * on the /en app would see "19 juin" instead of "Jun 19" (and vice-versa).
 *
 * The zone is the reader's DISPLAY zone, passed explicitly for the same class of reason:
 * omitting it formats in the browser's zone, so this badge could name a different day than the
 * full date in its own tooltip, or than the same due date on the task's page. A due date near
 * midnight is exactly where that shows.
 *
 * <p>Takes the STORED VALUE, not a parsed `Date`. The zone comes from `displayZoneFor`, which
 * answers UTC for a value that names a calendar DAY: a parsed `Date` has thrown that evidence away,
 * so a day-only due date was translated into the reader's zone and read one day early west of
 * Greenwich - in the badge, while the tooltip beside it (which was changed to pass the string) read
 * the right day. The contradiction this function was written to remove, between a badge and its own
 * tooltip.
 */
export function formatDueShort(due: string | Date): string {
  const value = typeof due === 'string' ? due : due.toISOString();
  return parseUtcAware(value).toLocaleDateString(getClientLocale(), {
    month: 'short',
    day: 'numeric',
    timeZone: displayZoneFor(value),
  });
}

import { getClientLocale } from '@/lib/utils/locale';

/**
 * Compact short due date for the task-card badge, e.g. "Jun 19" (F5 due-date badge).
 *
 * Follows the APP locale (next-intl, via getClientLocale) - NOT the browser locale. Passing
 * `undefined` to toLocaleDateString defaults to the browser language, so a French-browser user
 * on the /en app would see "19 juin" instead of "Jun 19" (and vice-versa). The day/month are
 * rendered in the user's LOCAL timezone, matching the sibling full-date tooltip on the same badge.
 */
export function formatDueShort(d: Date): string {
  return d.toLocaleDateString(getClientLocale(), { month: 'short', day: 'numeric' });
}

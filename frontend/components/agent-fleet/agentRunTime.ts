import {
  calendarDayIn,
  formatUtcDateTime,
  formatUtcTime,
  parseUtcAware,
} from '@/lib/utils/dateFormatters';
import { getClientTimeZone } from '@/lib/utils/timezone';

/**
 * When a run started, as the fleet dashboard's list shows it: the time alone for today, the full
 * date otherwise.
 *
 * <p>Its own module, following `taskBadgeFormat` next door, because the alternative is exporting it
 * from a 1900-line client component whose import pulls in next-intl navigation and every chart:
 * the rule that decides whether a row shows a date at all then costs a mocked module tree to
 * assert, which is how it came to have no test.
 */
export function formatStartedAt(dateStr: string): string {
  // "Today" means today FOR THE READER, because that is the zone the time is printed in.
  //
  // Both halves used to be UTC, so they agreed. When the rendering moved to the reader's display
  // zone and the comparison did not, the list started omitting the date from runs that are not
  // today at all: at 08:00 in Tokyo, a run from YESTERDAY 09:30 JST is still the same UTC day, so
  // it showed as a bare "09:30" and read as this morning, while a run from 03:00 today fell
  // on a different UTC day and got a full date. Wrong in both directions, with nothing on screen
  // to suggest it.
  // The formatters get the ORIGINAL string; `date` is only the day comparison.
  //
  // Parsing first throws away the one thing `displayZoneFor` reads, so a value naming a calendar
  // DAY could never be recognised as one here and would be translated into the reader zone - a day
  // early for anybody west of Greenwich. That is the defect this branch fixed in five other
  // helpers, under a comment naming it, and then reintroduced in this one.
  const date = parseUtcAware(dateStr);
  if (sameDisplayDay(date, new Date())) {
    return formatUtcTime(dateStr);
  }
  return formatUtcDateTime(dateStr);
}

/**
 * True when both instants fall on the same calendar day in the reader's display zone.
 *
 * <p>Compares the DAY, not the rendered string, and that is the whole point of the helper. It
 * was written when `formatUtcDate` appended the zone label, which on a daylight-saving day made
 * two instants on the same local day render different text and read as different days. That
 * particular trap is gone with the label, but the rule stays: the question asked here is which
 * calendar day an instant falls on, and a formatted string is an answer to a different one.
 */
function sameDisplayDay(a: Date, b: Date): boolean {
  const zone = getClientTimeZone();
  return calendarDayIn(a, zone) === calendarDayIn(b, zone);
}

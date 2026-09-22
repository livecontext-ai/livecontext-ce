import { formatFullDate, formatTimeInZone } from '@/lib/utils/agendaTime';

/**
 * How the truncated-history banner names the point its coverage starts at.
 *
 * <p>A pure function rather than an expression inside the page, because the thing that
 * can be wrong here is invisible: the boundary is an exact INSTANT, and printing it as a
 * bare date re-creates, one day narrower, the very bug the boundary was added to remove.
 * "History is complete from Monday 14 September" beside a grid missing everything before
 * 18:32 that Monday is the same false reading as "complete from September" beside a
 * missing first week. Extracted so a test can hold that, instead of a test re-writing the
 * expression and agreeing with itself.
 *
 * @param isoInstant the server's `pastCoveredFrom`
 * @param timezone   the calendar's display zone, so the banner and the chips beside it
 *                   name the same moment
 */
export function coverageBoundaryLabel(isoInstant: string, timezone: string): string {
  const at = new Date(isoInstant);
  return `${formatFullDate(at, timezone)} ${formatTimeInZone(at, timezone)}`;
}

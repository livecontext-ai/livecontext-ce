/**
 * The timezones a schedule control offers, always including the one it is showing.
 *
 * <p>Every schedule control in the app offers the same seven zones plus the machine's own.
 * That list is fine as an OFFER and wrong as a constraint, because a zone reaches these
 * controls from outside it: an agent whose schedule was stored in `Australia/Sydney`, a
 * workflow trigger created while the agenda was being read in another workspace member's
 * zone, an import. A `Select` whose value matches none of its items renders an EMPTY
 * trigger, so the control read as "no timezone" over a schedule that had one, and picking
 * anything from the list to fill the blank silently moved the fire time.
 *
 * <p>Shared rather than copied because the two surfaces are one flow: the agenda's
 * empty-slot dialog creates a workflow in the calendar's display zone and then opens the
 * builder inspector on it, and creates an agent in that same zone through the agent form.
 * Fixing one and leaving the other is the same bug reachable by the other door.
 */

import { browserTimezone } from '@/lib/utils/agendaTime';

/** The offered zones, in the order every schedule control has always shown them. */
export const TIMEZONE_PRESETS: readonly string[] = [
  'UTC',
  'Europe/Paris',
  'Europe/London',
  'America/New_York',
  'America/Los_Angeles',
  'Asia/Tokyo',
  'Asia/Shanghai',
];

/**
 * The machine's own zone, re-exported rather than resolved again.
 *
 * <p>`agendaTime` already owns that try/catch, and a second copy is a second place for the
 * fallback to drift. Its neighbour `buildTimezoneOptions` is deliberately NOT reused: it
 * seeds from the zones a workspace's schedules actually use and puts the viewer's first,
 * which is right for a calendar's display picker and wrong for a control that has to offer
 * the same seven zones everywhere.
 */
export { browserTimezone as localTimezone };

/**
 * The presets, the viewer's own zone, and `current`, in that order and without repeats.
 *
 * @param current the value the control is currently showing. Blank or absent adds nothing:
 *   a control with no value renders its placeholder, which is correct, and an empty item
 *   would be a selectable row that means nothing.
 */
export function timezoneOptionsFor(current?: string | null): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const zone of [...TIMEZONE_PRESETS, browserTimezone(), (current ?? '').trim()]) {
    if (!zone || seen.has(zone)) continue;
    seen.add(zone);
    out.push(zone);
  }
  return out;
}

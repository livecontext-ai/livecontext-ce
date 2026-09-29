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
import { getClientTimeZone } from '@/lib/utils/timezone';
import { getClientLocale } from '@/lib/utils/locale';

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
  // The viewer's DISPLAY zone AND this device's. They are the same until somebody picks a zone in
  // Settings, so on almost every machine this adds one row, not two.
  //
  // Both, because they answer different questions. The display zone is how this person reads
  // times, so it belongs in a control that will be read back next to other timestamps. The
  // device's zone is where they physically are, and "run this at 9am where I am" is a real intent
  // a schedule has to be able to express. An earlier version offered only the display zone, on the
  // reasoning that re-offering the device's puts back the zone they moved away from; that reasoning
  // is wrong for a SCHEDULE, which is not about reading a date but about when work happens, and it
  // silently removed an option that had always been there.
  for (const zone of [
    ...TIMEZONE_PRESETS,
    getClientTimeZone(),
    browserTimezone(),
    (current ?? '').trim(),
  ]) {
    if (!zone || seen.has(zone)) continue;
    seen.add(zone);
    out.push(zone);
  }
  return out;
}

/**
 * EVERY zone the runtime knows, sorted, for the account-wide display-zone setting.
 *
 * <p>Deliberately not the same list as {@link timezoneOptionsFor}: the seven presets are an
 * offer for a control that mostly repeats a common choice, while the account setting decides
 * how every date in the product reads, so limiting it to seven plus this device would leave
 * someone who works in a zone that is neither with no way to say so.
 *
 * <p>Falls back to {@link timezoneOptionsFor} where `Intl.supportedValuesOf` is missing: a
 * shorter list still lets the person keep or confirm their current zone, which an empty one
 * would not.
 */
export function allTimezoneOptions(current?: string | null): string[] {
  let zones: string[] = [];
  try {
    const supported = (Intl as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
    if (typeof supported === 'function') zones = supported.call(Intl, 'timeZone') ?? [];
  } catch {
    zones = [];
  }
  if (zones.length === 0) return timezoneOptionsFor(current);

  // `current` may be a zone this runtime does not list (a legacy alias, a value set on another
  // device): keep it, or the Select would render an empty trigger over a real setting.
  const value = (current ?? '').trim();
  // Collated in the APP locale, not the host's. A bare `localeCompare` orders by whatever locale the
  // machine is in, which is the same class of host dependence the i18n rule bans for formatting:
  // here it only reorders a list, but it reorders it differently for two people reading the same
  // `/en` page, and a picker whose order changes per machine is hard to give directions about.
  const out = [...zones].sort((a, b) => a.localeCompare(b, getClientLocale()));
  if (value && !out.includes(value)) out.unshift(value);
  return out;
}

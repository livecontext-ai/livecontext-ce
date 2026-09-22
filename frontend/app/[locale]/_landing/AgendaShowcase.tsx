'use client';

/**
 * Landing "Agenda" showcase: a LIVE, interactive replica of the /app/agenda
 * calendar, in the same bare app window as the agents showcase (shared icon
 * rail, no browser chrome) and wearing the SAME visual vocabulary as the
 * product: chip and dot colours come from `agendaVisuals`, the module the real
 * calendar reads, so a workflow is blue here for the reason it is blue in the
 * app, and the chip's width tiers are the app's own container queries.
 *
 * Drawn WHOLE and full width rather than cropped like the agents roster. A
 * calendar is a wide object, and here the toolbar is the interactive part: a
 * crop would have pushed the filters and the view switch off screen, leaving
 * controls the visitor could neither see nor reach.
 *
 * Not a screenshot, and not a still one either. Four things are real:
 *  - the month is projected from RECURRENCE RULES, the way the agenda projects a
 *    cron, so paging to any month draws a full, coherent calendar instead of a
 *    handful of pinned chips;
 *  - past fires wear their OUTCOME colour and future ones their resource colour,
 *    which is the rule the real calendar follows;
 *  - a planned chip can be DRAGGED onto a later day and comes back marked
 *    "moved off its schedule", under the app's own rule for which fires may be
 *    moved at all. Native HTML5 drag, so this one is a POINTER bonus: the
 *    product gives dnd-kit a touch sensor, a landing page cannot, so the
 *    section's copy sells only what a phone can also do;
 *  - the resource-kind filters and the Month/List switch actually filter and
 *    actually switch.
 *
 * Two deliberate deviations, both because the landing has nowhere to send the
 * visitor: only Month and List are offered (the app also has Week and Day, which
 * are hour grids), and a chip has no menu, so nothing here opens a run or a
 * resource.
 *
 * The homepage keeps its original English demo. Persona pages supply translated
 * schedules and reuse the app's agenda labels with dates in the app locale.
 */

import { useMemo, useState, useSyncExternalStore } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { ChevronLeft, ChevronRight, MoveRight } from 'lucide-react';
import { RESOURCE_ACCENT, STATUS_ACCENT, resourceIcon } from '@/components/agenda/agendaVisuals';
import type { ResourceType } from '@/lib/api/orchestrator/agenda.service';
import type { PersonaKey } from '@/components/landing/personas/personas';
import LandingSidebarRail from './LandingSidebarRail';

// ---------------------------------------------------------------------------
// Demo schedules. Recurrence rules rather than fixed dates: the agenda draws a
// cron projection, so anything that only held a week of pinned chips would fall
// apart the moment a visitor pressed the next-month arrow.
// ---------------------------------------------------------------------------

type Recurrence =
  | { kind: 'weekdays' }
  | { kind: 'weekly'; weekday: number }
  | { kind: 'monthDays'; days: number[] }
  | { kind: 'everyNDays'; n: number };

interface DemoSchedule {
  id: string;
  name: string;
  resourceType: ResourceType;
  /** Fire time, 24h, exactly as the app's tabular time column prints it. */
  time: string;
  recurrence: Recurrence;
  /** What the list view prints under the name for a planned fire. */
  cadence: string;
  /**
   * Over its spending cap for this period: the backend says these fires will not
   * happen, so they are drawn faded rather than hidden, like in the app.
   */
  budgetBlocked?: boolean;
  /**
   * Whether "move them all" can be expressed by rewriting this schedule. False
   * for an interval schedule, where the app offers the move on the next fire
   * only. Defaults to true, the simple weekly/daily cron case.
   */
  moveAllSupported?: boolean;
}

// Names are kept short on purpose: a month cell gives a chip about twelve
// characters before the width tiers truncate it, and a calendar a visitor
// cannot read sells nothing. The list view prints them in full.
/**
 * The calendar the showcase draws when it is not given one.
 *
 * <p>Exported for its TEST only, for the same reason as `DEMO_AGENTS`: the ids a server
 * component reads live in `demoRosterIds.ts`, and the suite pins that this array still
 * matches them.
 */
export const SCHEDULES: DemoSchedule[] = [
  { id: 'triage', name: 'Inbox triage', resourceType: 'AGENT', time: '06:45', recurrence: { kind: 'weekdays' }, cadence: 'Every weekday at 06:45' },
  { id: 'leads', name: 'New leads', resourceType: 'WORKFLOW', time: '07:30', recurrence: { kind: 'everyNDays', n: 2 }, cadence: 'Every 2 days at 07:30', moveAllSupported: false },
  { id: 'standup', name: 'Standup', resourceType: 'AGENT', time: '08:00', recurrence: { kind: 'weekdays' }, cadence: 'Every weekday at 08:00' },
  { id: 'digest', name: 'Team digest', resourceType: 'WORKFLOW', time: '09:15', recurrence: { kind: 'weekly', weekday: 1 }, cadence: 'Every Monday at 09:15' },
  { id: 'dashboard', name: 'Client app', resourceType: 'APPLICATION', time: '10:00', recurrence: { kind: 'weekly', weekday: 3 }, cadence: 'Every Wednesday at 10:00' },
  { id: 'research', name: 'Market scan', resourceType: 'AGENT', time: '11:00', recurrence: { kind: 'weekly', weekday: 5 }, cadence: 'Every Friday at 11:00', budgetBlocked: true },
  { id: 'social', name: 'Social posts', resourceType: 'APPLICATION', time: '16:00', recurrence: { kind: 'weekly', weekday: 4 }, cadence: 'Every Thursday at 16:00' },
  { id: 'community', name: 'Forum digest', resourceType: 'AGENT', time: '09:00', recurrence: { kind: 'weekly', weekday: 6 }, cadence: 'Every Saturday at 09:00' },
  { id: 'backup', name: 'Backups', resourceType: 'WORKFLOW', time: '03:00', recurrence: { kind: 'weekly', weekday: 0 }, cadence: 'Every Sunday at 03:00' },
  { id: 'invoices', name: 'Invoices', resourceType: 'WORKFLOW', time: '18:30', recurrence: { kind: 'monthDays', days: [1, 15] }, cadence: 'On the 1st and 15th at 18:30' },
];

const KIND_FILTERS: { type: ResourceType; label: string }[] = [
  { type: 'WORKFLOW', label: 'Workflows' },
  { type: 'APPLICATION', label: 'Applications' },
  { type: 'AGENT', label: 'Agents' },
];

const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

/** Monday-first, the app's default week start. */
const WEEKDAY_HEADERS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

const STATUS_LABELS: Record<string, string> = {
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
};

const DEFAULT_COPY = {
  previousPeriod: 'Previous period', nextPeriod: 'Next period', today: 'Today',
  resourceTypes: 'Resource types', view: 'View', month: 'Month', list: 'List',
  emptyTitle: 'No resource kind selected',
  emptyDescription: 'Turn a resource kind back on to see what is scheduled.',
  moved: 'Moved off its schedule', blocked: 'Will not run: spending cap reached',
  showLess: 'Show less', more: (count: number) => `+${count} more`,
  status: STATUS_LABELS,
  kindFilters: KIND_FILTERS,
};
type AgendaCopy = typeof DEFAULT_COPY;

// Only schedule data varies. All personas use the same recurrence projection,
// status colours, filters and rescheduling rules as the homepage demo.
const PERSONA_SCHEDULES: Record<PersonaKey, Omit<DemoSchedule, 'name' | 'cadence'>[]> = {
  // Operations is the one calendar where the week has a shape: the Monday report, the
  // daily checks, the month close on the last working day.
  ops: [
    { id: 'first', resourceType: 'WORKFLOW', time: '08:00', recurrence: { kind: 'weekly', weekday: 1 } },
    { id: 'second', resourceType: 'WORKFLOW', time: '09:30', recurrence: { kind: 'weekdays' } },
    { id: 'third', resourceType: 'AGENT', time: '07:30', recurrence: { kind: 'weekdays' } },
    { id: 'fourth', resourceType: 'WORKFLOW', time: '18:30', recurrence: { kind: 'weekdays' } },
    { id: 'fifth', resourceType: 'APPLICATION', time: '17:00', recurrence: { kind: 'weekdays' } },
    { id: 'sixth', resourceType: 'WORKFLOW', time: '23:00', recurrence: { kind: 'everyNDays', n: 2 }, moveAllSupported: false },
    // Seventh on Saturday at 09:00 and eighth on Sunday, like every other persona: the
    // calendar's point is that the weekend runs too, and a test holds that shape.
    { id: 'seventh', resourceType: 'AGENT', time: '09:00', recurrence: { kind: 'weekly', weekday: 6 } },
    { id: 'eighth', resourceType: 'WORKFLOW', time: '17:00', recurrence: { kind: 'weekly', weekday: 0 } },
  ],
  creator: [
    { id: 'first', resourceType: 'AGENT', time: '08:00', recurrence: { kind: 'weekdays' } },
    { id: 'second', resourceType: 'WORKFLOW', time: '09:00', recurrence: { kind: 'weekdays' } },
    { id: 'third', resourceType: 'WORKFLOW', time: '10:00', recurrence: { kind: 'weekdays' } },
    { id: 'fourth', resourceType: 'APPLICATION', time: '11:30', recurrence: { kind: 'weekdays' } },
    { id: 'fifth', resourceType: 'WORKFLOW', time: '18:00', recurrence: { kind: 'weekdays' } },
    { id: 'sixth', resourceType: 'AGENT', time: '16:00', recurrence: { kind: 'weekly', weekday: 5 } },
    { id: 'seventh', resourceType: 'WORKFLOW', time: '09:00', recurrence: { kind: 'weekly', weekday: 6 } },
    { id: 'eighth', resourceType: 'APPLICATION', time: '17:00', recurrence: { kind: 'weekly', weekday: 0 } },
  ],
  support: [
    { id: 'first', resourceType: 'AGENT', time: '07:00', recurrence: { kind: 'weekdays' } },
    { id: 'second', resourceType: 'AGENT', time: '08:30', recurrence: { kind: 'weekdays' } },
    { id: 'third', resourceType: 'APPLICATION', time: '09:00', recurrence: { kind: 'weekdays' } },
    { id: 'fourth', resourceType: 'WORKFLOW', time: '11:00', recurrence: { kind: 'weekdays' } },
    { id: 'fifth', resourceType: 'WORKFLOW', time: '15:00', recurrence: { kind: 'everyNDays', n: 2 }, moveAllSupported: false },
    { id: 'sixth', resourceType: 'AGENT', time: '17:00', recurrence: { kind: 'weekly', weekday: 5 } },
    { id: 'seventh', resourceType: 'AGENT', time: '09:00', recurrence: { kind: 'weekly', weekday: 6 } },
    { id: 'eighth', resourceType: 'WORKFLOW', time: '17:00', recurrence: { kind: 'weekly', weekday: 0 } },
  ],
  sales: [
    { id: 'first', resourceType: 'WORKFLOW', time: '07:30', recurrence: { kind: 'weekdays' } },
    { id: 'second', resourceType: 'AGENT', time: '08:00', recurrence: { kind: 'weekdays' } },
    { id: 'third', resourceType: 'AGENT', time: '09:00', recurrence: { kind: 'weekdays' } },
    { id: 'fourth', resourceType: 'APPLICATION', time: '10:00', recurrence: { kind: 'weekly', weekday: 1 } },
    { id: 'fifth', resourceType: 'WORKFLOW', time: '14:00', recurrence: { kind: 'everyNDays', n: 2 }, moveAllSupported: false },
    { id: 'sixth', resourceType: 'WORKFLOW', time: '17:30', recurrence: { kind: 'weekdays' } },
    { id: 'seventh', resourceType: 'WORKFLOW', time: '09:00', recurrence: { kind: 'weekly', weekday: 6 } },
    { id: 'eighth', resourceType: 'AGENT', time: '17:00', recurrence: { kind: 'weekly', weekday: 0 } },
  ],
  marketing: [
    { id: 'first', resourceType: 'AGENT', time: '09:00', recurrence: { kind: 'weekly', weekday: 1 } },
    { id: 'second', resourceType: 'WORKFLOW', time: '10:00', recurrence: { kind: 'weekly', weekday: 2 } },
    { id: 'third', resourceType: 'APPLICATION', time: '14:00', recurrence: { kind: 'weekly', weekday: 3 } },
    { id: 'fourth', resourceType: 'WORKFLOW', time: '09:30', recurrence: { kind: 'weekly', weekday: 4 } },
    { id: 'fifth', resourceType: 'WORKFLOW', time: '16:00', recurrence: { kind: 'weekdays' } },
    { id: 'sixth', resourceType: 'AGENT', time: '17:00', recurrence: { kind: 'weekly', weekday: 5 } },
    { id: 'seventh', resourceType: 'AGENT', time: '09:00', recurrence: { kind: 'weekly', weekday: 6 } },
    { id: 'eighth', resourceType: 'WORKFLOW', time: '17:00', recurrence: { kind: 'weekly', weekday: 0 } },
  ],
  recruiting: [
    { id: 'first', resourceType: 'WORKFLOW', time: '08:00', recurrence: { kind: 'weekdays' } },
    { id: 'second', resourceType: 'AGENT', time: '09:00', recurrence: { kind: 'weekdays' } },
    { id: 'third', resourceType: 'APPLICATION', time: '10:00', recurrence: { kind: 'weekly', weekday: 2 } },
    { id: 'fourth', resourceType: 'WORKFLOW', time: '11:00', recurrence: { kind: 'weekly', weekday: 3 } },
    { id: 'fifth', resourceType: 'WORKFLOW', time: '15:00', recurrence: { kind: 'weekdays' } },
    { id: 'sixth', resourceType: 'AGENT', time: '16:00', recurrence: { kind: 'monthDays', days: [1, 15] } },
    { id: 'seventh', resourceType: 'WORKFLOW', time: '09:00', recurrence: { kind: 'weekly', weekday: 6 } },
    { id: 'eighth', resourceType: 'AGENT', time: '17:00', recurrence: { kind: 'weekly', weekday: 0 } },
  ],
};

const DAY_MS = 86_400_000;

// ---------------------------------------------------------------------------
// Projection. Everything below is pure: a day is a UTC midnight timestamp, so
// no arithmetic here can be bitten by a daylight-saving jump.
// ---------------------------------------------------------------------------

interface GridDay {
  /** Whole days since the epoch: the identity every helper keys on, and what
   *  recurrence and the past/future split compare on. */
  index: number;
  iso: string;
  dayOfMonth: number;
  /** 0 = Sunday, as `Date#getUTCDay` returns it. */
  weekday: number;
  /** Belongs to a neighbouring month: drawn dimmed, like in the app. */
  outside: boolean;
}

/** Six weeks, always, so paging months never makes the page jump. */
function buildMonthGrid(year: number, month: number): GridDay[] {
  const first = Date.UTC(year, month, 1);
  // `month` may sit outside 0..11 (the arrows just add and subtract), and
  // Date.UTC normalises it, so the month to compare against is the one the
  // first-of-month timestamp actually landed on.
  const anchorMonth = new Date(first).getUTCMonth();
  const leading = (new Date(first).getUTCDay() + 6) % 7; // Monday-first
  const days: GridDay[] = [];
  for (let i = 0; i < 42; i += 1) {
    const ts = first + (i - leading) * DAY_MS;
    const date = new Date(ts);
    days.push({
      index: Math.round(ts / DAY_MS),
      iso: date.toISOString().slice(0, 10),
      dayOfMonth: date.getUTCDate(),
      weekday: date.getUTCDay(),
      outside: date.getUTCMonth() !== anchorMonth,
    });
  }
  return days;
}

function fires(schedule: DemoSchedule, day: GridDay): boolean {
  const rule = schedule.recurrence;
  switch (rule.kind) {
    case 'weekdays':
      return day.weekday >= 1 && day.weekday <= 5;
    case 'weekly':
      return day.weekday === rule.weekday;
    case 'monthDays':
      return rule.days.includes(day.dayOfMonth);
    case 'everyNDays':
    default:
      return day.index % rule.n === 0;
  }
}

/**
 * A stable outcome per (schedule, day), so the same past day reads the same on
 * every render and for every visitor: a calendar whose failures moved around
 * under the cursor would be theatre rather than a replica.
 */
function pastOutcome(id: string): 'COMPLETED' | 'FAILED' {
  let hash = 2166136261;
  for (let i = 0; i < id.length; i += 1) {
    hash ^= id.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0) % 23 === 0 ? 'FAILED' : 'COMPLETED';
}

function minutesOf(time: string): number {
  const [hours, minutes] = time.split(':');
  return Number(hours) * 60 + Number(minutes);
}

interface DemoOccurrence {
  id: string;
  schedule: DemoSchedule;
  /** Day index it is DRAWN on, which is the target day once it has been moved. */
  dayIndex: number;
  /** Day index the schedule actually points at, which a move does not change. */
  sourceIndex: number;
  minutes: number;
  past: boolean;
  status: 'PLANNED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  /** Dragged off its schedule by the visitor: carries the app's moved badge. */
  moved: boolean;
  /** Planned, but the spending cap will refuse it. */
  blocked: boolean;
  /**
   * Whether the platform could express this move, `isMovable` in agendaVisuals:
   * a schedule points at exactly one pending fire, so "move this one only" is
   * available on the next fire and nowhere else, and "move them all" needs a
   * cron simple enough to rewrite. Leaving every planned chip draggable is a
   * bug the product already fixed once; the replica must not reintroduce it.
   */
  movable: boolean;
}

/**
 * The month, as the calendar draws it: one bucket per day, each sorted by time.
 *
 * `overrides` holds what the visitor dragged (occurrence id to target day) and
 * is applied after projection, so a moved chip keeps its identity and can be
 * moved again.
 */
function projectMonth(
  days: GridDay[],
  kinds: Set<ResourceType>,
  todayIndex: number,
  nowMinutes: number,
  overrides: Map<string, string>,
  schedules: DemoSchedule[],
): Map<string, DemoOccurrence[]> {
  const byDay = new Map<string, DemoOccurrence[]>();
  for (const day of days) byDay.set(day.iso, []);

  const indexByIso = new Map(days.map((day) => [day.iso, day.index]));

  for (const day of days) {
    for (const schedule of schedules) {
      if (!kinds.has(schedule.resourceType) || !fires(schedule, day)) continue;

      const id = `${schedule.id}:${day.iso}`;
      const targetIso = overrides.get(id) ?? day.iso;
      const bucket = byDay.get(targetIso);
      // Moved to a day the six weeks on screen do not cover. It is not missing:
      // it is on that day, in another month, and paging there shows it. Drawing
      // it back on the day it left would be the lie.
      if (!bucket) continue;
      const targetIndex = indexByIso.get(targetIso) ?? day.index;

      const minutes = minutesOf(schedule.time);
      const past =
        targetIndex < todayIndex || (targetIndex === todayIndex && minutes <= nowMinutes);

      bucket.push({
        id,
        schedule,
        dayIndex: targetIndex,
        sourceIndex: day.index,
        minutes,
        past,
        status: past ? pastOutcome(id) : 'PLANNED',
        moved: targetIso !== day.iso,
        blocked: !past && Boolean(schedule.budgetBlocked),
        // Filled in below: it is a fact about the schedule, not about one day.
        movable: false,
      });
    }
  }

  for (const [iso, bucket] of byDay) {
    bucket.sort((a, b) => a.minutes - b.minutes);
    // The most recent fire of today is still going. It is the one detail that
    // makes the calendar read as "now" rather than "some Tuesday".
    if (indexByIso.get(iso) === todayIndex) {
      const running = [...bucket].reverse().find((occurrence) => occurrence.past);
      if (running) running.status = 'RUNNING';
    }
  }

  markMovable([...byDay.values()].flat(), todayIndex, nowMinutes);
  return byDay;
}

/**
 * Which chips the drag is offered on, following the app's `isMovable` rule.
 *
 * The next fire of a schedule can always be moved on its own. Every other fire
 * can only be moved by rewriting the whole schedule, which the app allows only
 * when the cron is simple enough for it, so an interval schedule offers the
 * gesture on its next fire and nowhere else.
 *
 * "Next" is asked of the SCHEDULE, not of the six weeks on screen: paging to
 * March must not turn March's first interval fire into a next fire, because the
 * schedule's real one is still tomorrow. And it is asked of the day the schedule
 * points at, not the day a chip was dragged to, since moving a fire does not
 * make it a different fire.
 */
function markMovable(
  occurrences: DemoOccurrence[],
  todayIndex: number,
  nowMinutes: number,
): void {
  const nextFire = new Map<string, number>();
  for (const occurrence of occurrences) {
    const { schedule } = occurrence;
    if (!nextFire.has(schedule.id)) {
      nextFire.set(schedule.id, nextFireIndex(schedule, todayIndex, nowMinutes));
    }
    occurrence.movable =
      !occurrence.past
      && (schedule.moveAllSupported !== false
        || occurrence.sourceIndex === nextFire.get(schedule.id));
  }
}

/**
 * The next day this schedule fires, counted forward from today.
 *
 * A year is the bound: every recurrence here repeats inside a month, so a walk
 * that gets that far is a rule that never fires, and `-1` matches nothing rather
 * than looping.
 */
function nextFireIndex(schedule: DemoSchedule, todayIndex: number, nowMinutes: number): number {
  const startsToday = minutesOf(schedule.time) > nowMinutes;
  for (let index = startsToday ? todayIndex : todayIndex + 1; index <= todayIndex + 366; index += 1) {
    const date = new Date(index * DAY_MS);
    const day: GridDay = {
      index,
      iso: date.toISOString().slice(0, 10),
      dayOfMonth: date.getUTCDate(),
      weekday: date.getUTCDay(),
      outside: false,
    };
    if (fires(schedule, day)) return index;
  }
  return -1;
}

function accentOf(occurrence: DemoOccurrence): { chip: string; dot: string } {
  if (occurrence.past) {
    const accent = STATUS_ACCENT[occurrence.status as keyof typeof STATUS_ACCENT];
    if (accent) return accent;
  }
  return RESOURCE_ACCENT[occurrence.schedule.resourceType];
}

function describe(occurrence: DemoOccurrence, copy: AgendaCopy): string {
  const when = `${occurrence.schedule.time} ${occurrence.schedule.name}`;
  // The moved badge is drawn whatever else is true of the chip, so it has to be
  // said whatever else is true of the chip: a moved run whose cap is also
  // reached showed the arrow and explained nothing.
  const moved = occurrence.moved ? ` - ${copy.moved}` : '';
  if (occurrence.blocked) return `${when} - ${copy.blocked}${moved}`;
  if (occurrence.past) return `${when} - ${copy.status[occurrence.status]}${moved}`;
  if (occurrence.moved) return `${when}${moved}`;
  return `${when} - ${occurrence.schedule.cadence}`;
}

/**
 * The visitor's clock, read ONCE per browser session (the cache is module
 * scoped, so a client-side navigation back to the landing keeps the first
 * reading, which is close enough for a calendar that is not the source of
 * truth for anything).
 *
 * A landing is rendered ahead of time, so the server's date can be weeks old by
 * the time someone reads it, and a calendar opening on the wrong month is the
 * one thing that would give the replica away. `useSyncExternalStore` is what
 * makes correcting it safe: the server's value is used on the server and through
 * hydration, then React swaps in the visitor's, with no mismatch and no
 * setState in an effect. The client snapshot is cached at module level because a
 * getter returning a fresh string on every call would re-render forever.
 */
let clientLoadIso: string | null = null;
const subscribeToNothing = () => () => {};
function readClientNow(): string {
  if (clientLoadIso === null) clientLoadIso = new Date().toISOString();
  return clientLoadIso;
}

/**
 * Whether the window is too narrow for a month grid to say anything.
 *
 * Seven columns inside this frame give a 31px cell on a phone and 72px on a
 * tablet, and a cell has to reach 4.5rem before the dot appears and 6.5rem
 * before the name does. So below this width the grid was drawing 42 boxes of
 * clipped times: not dense, empty. The replica answers it the way every phone
 * calendar does, by showing the list instead and keeping the grid as dots.
 *
 * Read through `useSyncExternalStore` for the same reason as the clock above:
 * the server has no window, so it renders the desktop layout and React swaps in
 * the real answer after hydration, with no mismatch and no setState in an
 * effect. A media query, not a container query, because the choice is which
 * VIEW to show, and CSS cannot make that one.
 */
const NARROW_CALENDAR = '(max-width: 900px)';
function subscribeToWidth(onChange: () => void) {
  if (typeof window === 'undefined' || !window.matchMedia) return () => {};
  const mql = window.matchMedia(NARROW_CALENDAR);
  // `change` needs the modern listener; Safari before 14 only has addListener,
  // and a landing is exactly where that still turns up.
  if (mql.addEventListener) {
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }
  mql.addListener(onChange);
  return () => mql.removeListener(onChange);
}
function readNarrow(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) return false;
  return window.matchMedia(NARROW_CALENDAR).matches;
}
function useNarrowCalendar(): boolean {
  return useSyncExternalStore(subscribeToWidth, readNarrow, () => false);
}

/** The day index of a date, read in the VISITOR's zone, not in UTC. */
function dayIndexOf(date: Date): number {
  return Math.round(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / DAY_MS);
}

/**
 * The month a (year, month) pair lands on. `month` may sit outside 0..11 because
 * the arrows just add and subtract, and `Date.UTC` normalises it.
 */
function periodOf(year: number, month: number, locale?: string): { name: string; title: string } {
  const shown = new Date(Date.UTC(year, month, 1));
  if (locale) return {
    name: new Intl.DateTimeFormat(locale, { month: 'long', timeZone: 'UTC' }).format(shown),
    title: new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(shown),
  };
  const name = MONTH_NAMES[shown.getUTCMonth()];
  return { name, title: `${name} ${shown.getUTCFullYear()}` };
}

// ---------------------------------------------------------------------------
// Chips and cells
// ---------------------------------------------------------------------------

function Chip({
  occurrence,
  copy,
  draggable,
  onDragStart,
  onDragEnd,
}: {
  occurrence: DemoOccurrence;
  copy: AgendaCopy;
  draggable: boolean;
  onDragStart: () => void;
  onDragEnd: () => void;
}) {
  const accent = accentOf(occurrence);
  return (
    <div
      draggable={draggable}
      onDragStart={(event) => {
        // Firefox starts no drag at all unless the transfer carries a payload.
        event.dataTransfer.setData('text/plain', occurrence.id);
        event.dataTransfer.effectAllowed = 'move';
        onDragStart();
      }}
      onDragEnd={onDragEnd}
      // The name is `display:none` below 6.5rem, so a reader on a phone would
      // otherwise get a grid of bare times. The app answers this the same way:
      // the full sentence lives on the element at every width. No list/listitem
      // roles to go with it: a `list` may own only `listitem`, and this cell
      // also holds the two overflow buttons, so the roles would have put the
      // controls at risk of being dropped to gain nothing the label does not
      // already give. MonthView carries neither role either.
      aria-label={describe(occurrence, copy)}
      title={describe(occurrence, copy)}
      className={`flex items-center gap-1 rounded-md px-1 py-0.5 @[6.5rem]:gap-1.5 @[6.5rem]:px-1.5 ${accent.chip}
                  ${occurrence.blocked ? 'opacity-50' : ''}
                  ${occurrence.past ? 'border border-dashed border-current/25' : ''}
                  ${draggable ? 'cursor-grab active:cursor-grabbing' : ''}`}
    >
      {/* What a chip shows depends on the width of the CELL it sits in, not the
          window: seven columns on a phone give a 45px cell, the same grid on a
          laptop gives 150px. These are the app's own tiers, kept identical so
          the replica breaks down the way the product does: the time survives at
          every width (a size smaller until 4.5rem, where a phone's 48px column
          would otherwise clip it mid-digit), then the dot at 4.5rem, then the
          name at 6.5rem. Nothing is lost, the full text stays in the label. The app adds the kind icon at
          8rem too; it is dropped here because it only repeats what the accent
          colour already says, and those pixels are the difference between a
          name being read and being elided. */}
      <span className={`hidden h-1.5 w-1.5 shrink-0 rounded-full @[4.5rem]:block ${accent.dot}`} aria-hidden="true" />
      <span className="shrink-0 text-[10px] tabular-nums opacity-80 @[4.5rem]:text-xs">{occurrence.schedule.time}</span>
      <span className="hidden min-w-0 flex-1 truncate text-xs @[6.5rem]:block">{occurrence.schedule.name}</span>
      {occurrence.moved && (
        <MoveRight className="hidden h-3 w-3 shrink-0 opacity-70 @[6.5rem]:block" aria-hidden="true" />
      )}
    </div>
  );
}

// Three fits the row height below, and the demo schedules are tuned so a busy
// weekday overflows by one: the "+N more" the real calendar shows is part of
// what a month of automation looks like, so it has to be reachable here.
const VISIBLE_PER_DAY = 3;

// A phone cell is 31px wide. Nothing textual survives that, so the compact cell
// shows what a phone calendar shows: the day number, and one dot per run in the
// colour of its kind. Four, because a fifth would wrap the row and the month grid
// is fixed height; the rest are counted. Every dot still carries the full
// sentence as its label, so nothing is lost to a reader who hovers or uses a
// screen reader, exactly as the chips do at full width.
const DOTS_PER_DAY = 4;

function CompactDay({
  day,
  copy,
  occurrences,
  isToday,
}: {
  day: GridDay;
  copy: AgendaCopy;
  occurrences: DemoOccurrence[];
  isToday: boolean;
}) {
  const shown = occurrences.slice(0, DOTS_PER_DAY);
  const hidden = occurrences.length - shown.length;
  return (
    <div
      data-day={day.iso}
      className="flex min-h-0 flex-col items-center gap-1 border-b border-r px-0.5 py-1"
      style={{
        borderColor: 'var(--border-color)',
        background: day.outside ? 'var(--bg-secondary)' : undefined,
      }}
    >
      <span
        className="text-[11px] tabular-nums"
        style={
          isToday
            ? {
                display: 'inline-flex',
                height: 18,
                width: 18,
                alignItems: 'center',
                justifyContent: 'center',
                borderRadius: 999,
                background: 'var(--accent-primary)',
                color: 'var(--accent-foreground)',
              }
            : { color: day.outside ? 'var(--text-muted)' : 'var(--text-secondary)' }
        }
      >
        {day.dayOfMonth}
      </span>
      <div className="flex flex-wrap items-center justify-center gap-[3px]">
        {shown.map((occurrence) => (
          <span
            key={occurrence.id}
            className={`h-1.5 w-1.5 rounded-full ${accentOf(occurrence).dot}
                        ${occurrence.blocked ? 'opacity-40' : ''}`}
            aria-label={describe(occurrence, copy)}
            title={describe(occurrence, copy)}
          />
        ))}
        {hidden > 0 && (
          <span className="text-[9px] leading-none tabular-nums" style={{ color: 'var(--text-muted)' }}>
            +{hidden}
          </span>
        )}
      </div>
    </div>
  );
}

function DayCell({
  day,
  copy,
  occurrences,
  isToday,
  droppable,
  isOver,
  onDragStart,
  onDragEnd,
  onDragOverDay,
  onDropOnDay,
}: {
  day: GridDay;
  copy: AgendaCopy;
  occurrences: DemoOccurrence[];
  isToday: boolean;
  droppable: boolean;
  isOver: boolean;
  onDragStart: (id: string) => void;
  onDragEnd: () => void;
  onDragOverDay: (iso: string | null) => void;
  onDropOnDay: (iso: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const visible = expanded ? occurrences : occurrences.slice(0, VISIBLE_PER_DAY);
  const hidden = occurrences.length - visible.length;

  return (
    <div
      data-day={day.iso}
      onDragOver={(event) => {
        if (!droppable) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
        onDragOverDay(day.iso);
      }}
      onDragLeave={() => onDragOverDay(null)}
      onDrop={(event) => {
        if (!droppable) return;
        event.preventDefault();
        // Show what just happened: dropping onto a day that is already at its
        // visible limit would otherwise file the run behind "+N more", which
        // reads as "the drag deleted it".
        if (occurrences.length >= VISIBLE_PER_DAY) setExpanded(true);
        onDropOnDay(day.iso);
      }}
      className={`@container flex min-h-0 flex-col gap-0.5 border-b border-r p-1
                  ${isOver ? 'ring-2 ring-inset ring-[var(--accent-primary)]' : ''}`}
      style={{
        borderColor: 'var(--border-color)',
        background: day.outside ? 'var(--bg-secondary)' : undefined,
      }}
    >
      <div className="px-1">
        <span
          className="text-xs tabular-nums"
          style={
            isToday
              ? {
                  display: 'inline-flex',
                  height: 18,
                  width: 18,
                  alignItems: 'center',
                  justifyContent: 'center',
                  borderRadius: 999,
                  background: 'var(--accent-primary)',
                  color: 'var(--accent-foreground)',
                }
              : { color: day.outside ? 'var(--text-muted)' : 'var(--text-secondary)' }
          }
        >
          {day.dayOfMonth}
        </span>
      </div>
      {/* Expanding SCROLLS rather than growing, as it does in the app: the row
          height is fixed by the grid, so a cell that only grew would swap a
          truncated list for a clipped one. `overscroll-y-auto`, not `contain`,
          hands the rest of the gesture back to the calendar once the cell is at
          its end. Both are what MonthView settled on. */}
      <div
        className={`flex min-h-0 flex-col gap-0.5
                    ${expanded ? 'overflow-y-auto overscroll-y-auto' : 'overflow-hidden'}`}
      >
        {visible.map((occurrence) => (
          <Chip
            key={occurrence.id}
            occurrence={occurrence}
            copy={copy}
            // The app's rule, not "anything not in the past": see `movable`.
            draggable={occurrence.movable}
            onDragStart={() => onDragStart(occurrence.id)}
            onDragEnd={onDragEnd}
          />
        ))}
        {hidden > 0 && (
          <button
            type="button"
            onClick={() => setExpanded(true)}
            className="px-1.5 text-left text-[11px] hover:underline"
            style={{ color: 'var(--text-muted)' }}
          >
            {copy.more(hidden)}
          </button>
        )}
        {/* The way back. Without it the cell stays expanded for the rest of the
            visit and the day that was three lines tall is now a scrollbox the
            visitor cannot undo. */}
        {expanded && occurrences.length > VISIBLE_PER_DAY && (
          <button
            type="button"
            onClick={() => setExpanded(false)}
            className="px-1.5 text-left text-[11px] hover:underline"
            style={{ color: 'var(--text-muted)' }}
          >
            {copy.showLess}
          </button>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// The window
// ---------------------------------------------------------------------------

const BAR_BUTTON = 'inline-flex h-7 items-center gap-1 rounded-lg border px-2 text-xs transition-colors';


/**
 * @param persona a persona page: its own schedules, named from its own messages.
 * @param locale the HOME page: the generic schedules, named from `LandingHome`. Without it
 *   the window renders its English defaults, which is what left this calendar in English on
 *   the five translated locales while everything around it had been translated.
 */
export default function AgendaShowcase({ nowIso, persona, locale }: { nowIso: string; persona?: PersonaKey; locale?: string }) {
  if (persona) return <PersonaAgendaWindow key={`${persona}:${locale ?? ''}`} nowIso={nowIso} persona={persona} locale={locale} />;
  if (locale) return <HomeAgendaWindow key={locale} nowIso={nowIso} locale={locale} />;
  return <AgendaAppWindow nowIso={nowIso} />;
}

function PersonaAgendaWindow({ nowIso, persona, locale }: { nowIso: string; persona: PersonaKey; locale?: string }) {
  const names = useTranslations(`PersonaLanding.personas.${persona}.agendaSchedules`);
  return <LocalizedAgendaWindow nowIso={nowIso} locale={locale} source={PERSONA_SCHEDULES[persona]} idPrefix={persona} name={names} />;
}

function HomeAgendaWindow({ nowIso, locale }: { nowIso: string; locale: string }) {
  const names = useTranslations('LandingHome.agendaSchedules');
  return <LocalizedAgendaWindow nowIso={nowIso} locale={locale} source={SCHEDULES} name={names} />;
}

/**
 * The window with every label translated: the chrome from the app's own `agenda` messages,
 * the cadences from the shared cadence patterns, and each entry's name from whichever
 * namespace the caller owns. It is one component rather than two because the persona pages
 * and the home page differ ONLY in which schedules they show and where the names come from.
 */
function LocalizedAgendaWindow({ nowIso, locale, source, idPrefix, name }: {
  // The persona sets carry no name or cadence (they are translated here); the home set is a
  // full DemoSchedule and is assignable to the same narrower shape.
  nowIso: string; locale?: string; source: readonly Omit<DemoSchedule, 'name' | 'cadence'>[]; idPrefix?: string; name: (id: string) => string;
}) {
  const appLocale = useLocale();
  const displayLocale = locale ?? appLocale;
  const t = useTranslations('agenda');
  const cadence = useTranslations('PersonaLanding.agenda.common.cadence');
  const schedules = useMemo(() => source.map((schedule) => {
    const rule = schedule.recurrence;
    let cadenceLabel: string;
    switch (rule.kind) {
      case 'weekly':
        cadenceLabel = cadence('weekly', {
          day: new Intl.DateTimeFormat(displayLocale, { weekday: 'long', timeZone: 'UTC' }).format(new Date(Date.UTC(2026, 0, 4 + rule.weekday))),
          time: schedule.time,
        });
        break;
      case 'monthDays':
        cadenceLabel = cadence('monthDays', { days: rule.days.map((day) => day.toLocaleString(displayLocale)).join(', '), time: schedule.time });
        break;
      case 'everyNDays':
        cadenceLabel = cadence('everyNDays', { count: rule.n, time: schedule.time });
        break;
      default:
        cadenceLabel = cadence('weekdays', { time: schedule.time });
    }
    return { ...schedule, id: idPrefix ? `${idPrefix}-${schedule.id}` : schedule.id, name: name(schedule.id), cadence: cadenceLabel };
  }), [source, idPrefix, displayLocale, name, cadence]);
  const copy: AgendaCopy = {
    previousPeriod: t('nav.previous'), nextPeriod: t('nav.next'), today: t('nav.today'),
    resourceTypes: t('filters.resourceTypes'), view: t('filters.view'), month: t('view.month'), list: t('view.list'),
    emptyTitle: t('empty.allFilteredTitle'), emptyDescription: t('empty.allFilteredDescription'),
    moved: t('movedBadge'), blocked: t('status.budgetBlocked'), showLess: t('showLess'),
    more: (count) => t('moreCount', { n: count }),
    status: { RUNNING: t('status.running'), COMPLETED: t('status.completed'), FAILED: t('status.failed') },
    kindFilters: KIND_FILTERS.map(({ type }) => ({ type, label: t(`resource.${type.toLowerCase()}`) })),
  };
  return <AgendaAppWindow nowIso={nowIso} schedules={schedules} copy={copy} locale={displayLocale} />;
}

function AgendaAppWindow({ nowIso, schedules = SCHEDULES, copy = DEFAULT_COPY, locale }: {
  nowIso: string; schedules?: DemoSchedule[]; copy?: AgendaCopy; locale?: string;
}) {
  const resolvedIso = useSyncExternalStore(subscribeToNothing, readClientNow, () => nowIso);
  const now = useMemo(() => new Date(resolvedIso), [resolvedIso]);

  const [monthOffset, setMonthOffset] = useState(0);
  // The view FOLLOWS the width until somebody chooses for themselves, and then
  // their choice wins: a visitor who taps Month on a phone meant it, and having
  // the layout take it back on the next resize would be the page arguing.
  const narrow = useNarrowCalendar();
  const [picked, setPicked] = useState<'month' | 'list' | null>(null);
  const view: 'month' | 'list' = picked ?? (narrow ? 'list' : 'month');
  const setView = setPicked;
  const [kinds, setKinds] = useState<Set<ResourceType>>(
    () => new Set<ResourceType>(['WORKFLOW', 'APPLICATION', 'AGENT']),
  );
  const [overrides, setOverrides] = useState<Map<string, string>>(() => new Map());
  const [dragging, setDragging] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState<string | null>(null);

  const todayIndex = dayIndexOf(now);
  const nowMinutes = now.getHours() * 60 + now.getMinutes();

  // `month` is deliberately allowed outside 0..11 so the arrows can just add and
  // subtract; buildMonthGrid and periodOf both normalise it.
  const anchor = useMemo(
    () => ({ year: now.getFullYear(), month: now.getMonth() + monthOffset }),
    [now, monthOffset],
  );
  const days = useMemo(() => buildMonthGrid(anchor.year, anchor.month), [anchor]);
  const byDay = useMemo(
    () => projectMonth(days, kinds, todayIndex, nowMinutes, overrides, schedules),
    [days, kinds, todayIndex, nowMinutes, overrides, schedules],
  );

  const period = useMemo(() => periodOf(anchor.year, anchor.month, locale), [anchor, locale]);
  const weekdayHeaders = useMemo(() => locale
    ? WEEKDAY_HEADERS.map((_, index) => new Intl.DateTimeFormat(locale, { weekday: 'short', timeZone: 'UTC' }).format(new Date(Date.UTC(2026, 0, 5 + index))))
    : WEEKDAY_HEADERS, [locale]);

  const toggleKind = (type: ResourceType) => {
    setKinds((previous) => {
      const next = new Set(previous);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return next;
    });
  };

  const dropOn = (iso: string) => {
    const id = dragging;
    setDragging(null);
    setDragOver(null);
    if (!id) return;
    setOverrides((previous) => new Map(previous).set(id, iso));
  };

  const listed = days
    .filter((day) => !day.outside)
    .map((day) => ({ day, items: byDay.get(day.iso) ?? [] }))
    .filter((group) => group.items.length > 0);

  return (
    // Override the default .browser-frame shadow: its wide blur reads as a halo
    // behind the floating window.
    <figure className="browser-frame" style={{ boxShadow: '0 2px 12px rgba(28, 26, 23, 0.06)' }}>
      <div className="browser-body flex">
        <LandingSidebarRail activeView="agenda" hideOnMobile />

        <div className="min-w-0 flex-1">
          {/* The agenda toolbar: period navigation, the period on screen, the
              resource-kind filters and the view switch, in the app's order. */}
          <div
            className="flex flex-wrap items-center gap-2 border-b px-3 py-2"
            style={{ borderColor: 'var(--border-color)' }}
          >
            <div className="flex items-center gap-1">
              <button
                type="button"
                aria-label={copy.previousPeriod}
                onClick={() => setMonthOffset((offset) => offset - 1)}
                className={`${BAR_BUTTON} w-7 justify-center px-0`}
                style={{ borderColor: 'var(--border-color)', color: 'var(--text-secondary)' }}
              >
                <ChevronLeft className="h-3.5 w-3.5" />
              </button>
              <button
                type="button"
                aria-label={copy.nextPeriod}
                onClick={() => setMonthOffset((offset) => offset + 1)}
                className={`${BAR_BUTTON} w-7 justify-center px-0`}
                style={{ borderColor: 'var(--border-color)', color: 'var(--text-secondary)' }}
              >
                <ChevronRight className="h-3.5 w-3.5" />
              </button>
              <button
                type="button"
                onClick={() => setMonthOffset(0)}
                className={BAR_BUTTON}
                style={{ borderColor: 'var(--border-color)', color: 'var(--text-secondary)' }}
              >
                {copy.today}
              </button>
            </div>

            <span className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
              {period.title}
            </span>

            <div className="ml-auto flex items-center gap-1" role="group" aria-label={copy.resourceTypes}>
              {copy.kindFilters.map(({ type, label }) => {
                const Icon = resourceIcon(type);
                const active = kinds.has(type);
                return (
                  <button
                    key={type}
                    type="button"
                    aria-pressed={active}
                    aria-label={label}
                    title={label}
                    onClick={() => toggleKind(type)}
                    className={`${BAR_BUTTON} w-7 justify-center px-0 ${active ? '' : 'opacity-40'}`}
                    style={{ borderColor: 'var(--border-color)', color: 'var(--text-secondary)' }}
                  >
                    <Icon className="h-3.5 w-3.5" />
                  </button>
                );
              })}
            </div>

            <div className="flex items-center gap-1" role="group" aria-label={copy.view}>
              {(['month', 'list'] as const).map((mode) => (
                <button
                  key={mode}
                  type="button"
                  aria-pressed={view === mode}
                  onClick={() => setView(mode)}
                  className={BAR_BUTTON}
                  style={
                    view === mode
                      ? {
                          borderColor: 'var(--accent-primary)',
                          background: 'var(--accent-primary)',
                          color: 'var(--accent-foreground)',
                        }
                      : { borderColor: 'var(--border-color)', color: 'var(--text-secondary)' }
                  }
                >
                  {mode === 'month' ? copy.month : copy.list}
                </button>
              ))}
            </div>
          </div>

          {/* One height for all three views, and a CROP rather than a scrollbox: a
              list of a whole month is several thousand pixels tall, so letting it
              set the height would push the rest of the page down every time
              someone pressed List. Scrolling it in place was the previous answer
              and it made the landing carry a second scroll surface, which a thumb
              lands in by accident on a phone. Cut off, the calendar reads as a
              glimpse of a real one, the way the agents window is cropped. */}
          <div className="h-[35rem] overflow-hidden">
          {kinds.size === 0 ? (
            // The app's own words for this state, not a landing paraphrase: the
            // visitor turned every kind off, and the calendar says so instead of
            // looking broken.
            <div className="flex h-full flex-col items-center justify-center gap-1 px-6 text-center">
              <p className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                {copy.emptyTitle}
              </p>
              <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                {copy.emptyDescription}
              </p>
            </div>
          ) : view === 'month' ? (
            <div>
              <div
                className="grid border-b"
                style={{
                  gridTemplateColumns: 'repeat(7, minmax(0, 1fr))',
                  borderColor: 'var(--border-color)',
                  background: 'var(--bg-secondary)',
                }}
              >
                {weekdayHeaders.map((header) => (
                  <div
                    key={header}
                    className={`py-1.5 text-[11px] font-medium ${narrow ? 'text-center px-0' : 'px-2'}`}
                    style={{ color: 'var(--text-muted)' }}
                  >
                    {/* One letter is all a 31px column can hold, and a clipped
                        "Wed" is worse than a "W" nobody has to decode. */}
                    {narrow ? header.slice(0, 1) : header}
                  </div>
                ))}
              </div>
              <div
                className="grid"
                style={{
                  gridTemplateColumns: 'repeat(7, minmax(0, 1fr))',
                  // A dotted cell needs a fifth of the height a stack of chips
                  // does, and the whole month still has to fit the fixed frame.
                  gridAutoRows: narrow ? 'minmax(2.75rem, 1fr)' : 'minmax(5.25rem, 1fr)',
                }}
              >
                {days.map((day) =>
                  narrow ? (
                    <CompactDay
                      key={day.iso}
                      day={day}
                      copy={copy}
                      occurrences={byDay.get(day.iso) ?? []}
                      isToday={day.index === todayIndex}
                    />
                  ) : (
                    <DayCell
                      key={day.iso}
                      day={day}
                      copy={copy}
                      occurrences={byDay.get(day.iso) ?? []}
                      isToday={day.index === todayIndex}
                      // A run can only be moved onto a day that has not happened
                      // yet, the only move the platform can honour. Offering the
                      // past would teach a gesture and then refuse it.
                      droppable={Boolean(dragging) && day.index > todayIndex}
                      isOver={dragOver === day.iso}
                      onDragStart={setDragging}
                      onDragEnd={() => {
                        setDragging(null);
                        setDragOver(null);
                      }}
                      onDragOverDay={setDragOver}
                      onDropOnDay={dropOn}
                    />
                  ),
                )}
              </div>
            </div>
          ) : (
            <div>
              {listed.map(({ day, items }) => (
                <section key={day.iso}>
                  <h3
                    className="border-b px-3 py-1.5 text-[11px] font-medium"
                    style={{
                      borderColor: 'var(--border-color)',
                      background: 'var(--bg-secondary)',
                      color: day.index === todayIndex ? 'var(--accent-primary)' : 'var(--text-muted)',
                    }}
                  >
                    {locale
                      ? new Intl.DateTimeFormat(locale, { weekday: 'short', day: 'numeric', month: 'long', timeZone: 'UTC' }).format(new Date(`${day.iso}T00:00:00Z`))
                      : `${weekdayHeaders[(day.weekday + 6) % 7]} ${day.dayOfMonth} ${period.name}`}
                  </h3>
                  <ul>
                    {items.map((occurrence) => {
                      const accent = accentOf(occurrence);
                      const Icon = resourceIcon(occurrence.schedule.resourceType);
                      return (
                        <li
                          key={occurrence.id}
                          className={`flex items-center gap-3 border-b px-3 py-2 ${occurrence.blocked ? 'opacity-50' : ''}`}
                          style={{ borderColor: 'var(--border-color)' }}
                        >
                          <span
                            className="w-10 shrink-0 text-[11px] tabular-nums"
                            style={{ color: 'var(--text-muted)' }}
                          >
                            {occurrence.schedule.time}
                          </span>
                          <span
                            className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full ${accent.chip}`}
                          >
                            <Icon className="h-3.5 w-3.5" aria-hidden="true" />
                          </span>
                          <span className="min-w-0 flex-1">
                            <span className="block truncate text-sm" style={{ color: 'var(--text-primary)' }}>
                              {occurrence.schedule.name}
                            </span>
                            <span className="block truncate text-xs" style={{ color: 'var(--text-muted)' }}>
                              {occurrence.blocked
                                ? copy.blocked
                                : occurrence.past
                                  ? copy.status[occurrence.status]
                                  : occurrence.schedule.cadence}
                            </span>
                          </span>
                          {occurrence.moved && (
                            <MoveRight
                              className="h-3.5 w-3.5 shrink-0"
                              style={{ color: 'var(--text-muted)' }}
                              aria-label={copy.moved}
                            />
                          )}
                          <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${accent.dot}`} aria-hidden="true" />
                        </li>
                      );
                    })}
                  </ul>
                </section>
              ))}
            </div>
          )}
          </div>
        </div>
      </div>
    </figure>
  );
}

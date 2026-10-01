'use client';

import { useMemo, useRef } from 'react';
import { useTranslations } from 'next-intl';
import { useDroppable } from '@dnd-kit/core';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';
import {
  dayKey,
  formatDayInZone,
  formatDayNarrowInZone,
  formatDayShortInZone,
  formatTimeInZone,
  zonedParts,
} from '@/lib/utils/agendaTime';
import { Plus } from 'lucide-react';
import { OccurrenceChip } from './OccurrenceChip';
import { useDayKey, useNow } from '@/hooks/useNow';
import { useIsomorphicLayoutEffect } from '@/lib/hooks/useIsomorphicLayoutEffect';

interface TimeGridViewProps {
  /** One column per day: seven for the week view, one for the day view. */
  days: Date[];
  timezone: string;
  startHour: number;
  endHour: number;
  compact: boolean;
  occurrencesByDay: Map<string, AgendaOccurrence[]>;
  /**
   * Bumped whenever the user asks to be taken back to the present ("Today"), which is the
   * one re-centring the visible period cannot ask for: pressing Today while already on
   * today changes nothing about the period, so without this the gesture would do nothing
   * for a user who had scrolled away.
   */
  recenterSignal?: number;
  /** Schedule the user navigated here to find; its chips are ringed. */
  focusScheduleId?: string | null;
  /** False for an org VIEWER: chips are not draggable, matching the row menu. */
  canMutate: boolean;
  onSelect: (occurrence: AgendaOccurrence, event: React.MouseEvent<HTMLButtonElement>) => void;
  /** Clicking an EMPTY hour offers to schedule something there. Absent for a VIEWER. */
  onCreate?: (day: Date, hour: number) => void;
}

/**
 * The hour grid behind both the week and the day view - they differ only in how many
 * columns they draw, so they are one component rather than two that drift apart.
 *
 * <p>Every hour cell is its own drop target, which is what makes a drag here more precise
 * than in the month grid: dropping on 14:00 proposes 14:00 in the dialog. The user can
 * still adjust the minutes there, so the grid does not have to be sliced finer.
 *
 * <p>The visible hour range is a preference: a user whose jobs all run in the afternoon can
 * crop the grid to 12:00-20:00 rather than scrolling past twelve empty rows. The range is
 * a forward one (start before end); it does not wrap around midnight, which the settings
 * popover and the stored-preference validation both enforce. Occurrences outside the crop
 * are NOT dropped silently: they are collected into a strip above the grid, because a
 * calendar that hides runs is worse than one that scrolls.
 */
export function TimeGridView({
  days,
  timezone,
  startHour,
  endHour,
  compact,
  occurrencesByDay,
  recenterSignal = 0,
  focusScheduleId,
  canMutate,
  onSelect,
  onCreate,
}: TimeGridViewProps) {
  const t = useTranslations('agenda');
  const hours = useMemo(
    () => Array.from({ length: Math.max(1, endHour - startHour) }, (_, i) => startHour + i),
    [startHour, endHour],
  );

  /* ---------------------------------------------------------------- *
   *  Where "now" falls on this grid
   * ---------------------------------------------------------------- */

  // Which day it is costs a re-render once a day; what MINUTE it is costs one a minute, and
  // this grid is 168 drop targets plus their chips. So the minute is only asked for when
  // something on screen depends on it: the day key decides whether today is among the
  // columns, and if it is not - every period but one - the clock is frozen and the grid
  // stops re-rendering entirely. Starting it again is answered on the same render, so
  // paging back onto today does not draw a stale minute.
  const todayKey = useDayKey(timezone);
  const showsToday = days.some((day) => dayKey(day, timezone) === todayKey);
  const now = useNow(showsToday ? 60_000 : 0);
  const nowParts = zonedParts(now, timezone);
  // A percentage of the hour row, not a pixel offset: rows grow with the chips they hold
  // (an hour with four fires is taller than an empty one), so anything measured in pixels
  // would point at the wrong minute on exactly the days that are busy enough to matter.
  const nowOffsetPercent = (nowParts.minute / 60) * 100;
  // Cropped hours hide the line rather than clamping it to the edge of the grid: a line
  // pinned to 08:00 because the day starts there would claim it is 08:00 all night.
  const nowInRange = nowParts.hour >= startHour && nowParts.hour < endHour;
  const nowLabel = formatTimeInZone(now, timezone);
  const nowVisible = showsToday && nowInRange;

  const scrollRef = useRef<HTMLDivElement>(null);

  // Open on the present moment when it is on screen. A day is 24 rows tall and the grid
  // opens at midnight, so on a default range the now-line - the whole point of drawing it
  // - sits below the fold for anyone whose runs are not at 01:00.
  //
  // Keyed on the VISIBLE PERIOD, never on the clock. An earlier version keyed it on today's
  // day key, which looks equivalent and is not: that key changes at midnight, so a user
  // reading tomorrow's afternoon at 23:59 had the grid yanked back under them on the stroke
  // of twelve. The period is what makes a scroll position meaningless, so the period is
  // what may replace it - plus `recenterSignal`, which is the user asking in so many words.
  const periodKey = `${days[0]?.getTime() ?? 0}:${days.length}:${startHour}:${endHour}`;

  // Layout effect, so the centring happens before the browser paints. As a plain effect the
  // grid painted at midnight and then jumped, once per navigation.
  //
  // "Is the present moment on screen?" is asked of the DOM rather than of the clock: the
  // marker is rendered under exactly that condition, so its absence IS the answer, and
  // reading it here keeps the clock out of this effect's dependencies without a ref written
  // during render to smuggle it in.
  useIsomorphicLayoutEffect(() => {
    const container = scrollRef.current;
    if (!container) return;
    const marker = container.querySelector<HTMLElement>('[data-agenda-now-line]');
    if (!marker) return;
    const top =
      marker.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop;
    container.scrollTop = Math.max(0, top - container.clientHeight / 2);
  }, [periodKey, recenterSignal]);

  return (
    <div className="flex min-h-0 flex-initial flex-col overflow-hidden rounded-xl border border-theme">
      <div
        className="grid shrink-0 border-b border-theme bg-theme-secondary"
        style={{ gridTemplateColumns: gridColumns(days.length) }}
      >
        <div />
        {days.map((day) => (
          <div
            key={dayKey(day, timezone)}
            // The day key, not `isSameDay(day, now)`: the header asks the same question the
            // cells below it ask, and asking it of the clock would both cost two Intl reads
            // per column per tick and go stale whenever the clock is frozen.
            className={`@container overflow-hidden px-1 py-1.5 text-xs font-medium @[5rem]:px-2 ${
              dayKey(day, timezone) === todayKey ? 'text-[var(--accent-primary)]' : 'text-theme-muted'
            }`}
          >
            {/* "Thu, Sep 3" wraps onto three lines in a 40px column and pushes the grid two
                rows down the screen. Three tiers, each sized to the room actually there: a
                weekday initial and the date at 40px, the short weekday and the date when
                there is more, and the full weekday, date and MONTH once the column can hold
                it. The month is the fact the two narrow tiers give up - the period title
                above the grid is still naming it, which is why they can. */}
            <span className="block truncate @[4.5rem]:hidden">{formatDayNarrowInZone(day, timezone)}</span>
            <span className="hidden truncate @[4.5rem]:block @[6.5rem]:hidden">{formatDayShortInZone(day, timezone)}</span>
            <span className="hidden truncate @[6.5rem]:block">{formatDayInZone(day, timezone)}</span>
          </div>
        ))}
      </div>

      <OutOfRangeStrip
        days={days}
        timezone={timezone}
        startHour={startHour}
        endHour={endHour}
        compact={compact}
        occurrencesByDay={occurrencesByDay}
        focusScheduleId={focusScheduleId}
        canMutate={canMutate}
        onSelect={onSelect}
      />

      <div ref={scrollRef} className="min-h-0 flex-1 overflow-y-auto">
        <div
          className="grid"
          style={{ gridTemplateColumns: gridColumns(days.length) }}
        >
          {hours.map((hour) => (
            <HourRow
              key={hour}
              hour={hour}
              days={days}
              timezone={timezone}
              compact={compact}
              nowDayKey={nowVisible ? todayKey : null}
              nowOffsetPercent={nowVisible && nowParts.hour === hour ? nowOffsetPercent : null}
              // Only the row that draws the label is given it. Broadcast to all 24 it
              // changes every minute, which would keep every row re-rendering even once
              // they are memoised - the opposite of the point.
              nowLabel={nowVisible && nowParts.hour === hour ? nowLabel : null}
              occurrencesByDay={occurrencesByDay}
              focusScheduleId={focusScheduleId}
            canMutate={canMutate}
              onSelect={onSelect}
              onCreate={onCreate}
              createLabel={(day, cellHour) => t('create.slotActionAt', {
                date: formatDayInZone(day, timezone),
                time: `${String(cellHour).padStart(2, '0')}:00`,
              })}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function HourRow({
  hour,
  days,
  timezone,
  compact,
  occurrencesByDay,
  focusScheduleId,
  canMutate,
  nowDayKey,
  nowOffsetPercent,
  nowLabel,
  onSelect,
  onCreate,
  createLabel,
}: {
  hour: number;
  days: Date[];
  timezone: string;
  compact: boolean;
  occurrencesByDay: Map<string, AgendaOccurrence[]>;
  focusScheduleId?: string | null;
  /** False for an org VIEWER: chips are not draggable, matching the row menu. */
  canMutate: boolean;
  /** The column the now-line belongs in, or null when today is not on screen. */
  nowDayKey: string | null;
  /** How far into THIS row the present moment falls, or null when it is another hour. */
  nowOffsetPercent: number | null;
  /** The time to print in the gutter, or null on every row but the current hour's. */
  nowLabel: string | null;
  onSelect: (occurrence: AgendaOccurrence, event: React.MouseEvent<HTMLButtonElement>) => void;
  onCreate?: (day: Date, hour: number) => void;
  /**
   * Names ONE slot. A week grid renders up to 168 of these buttons, and a single shared
   * label ("Schedule something here") makes every one of them announce the same six words:
   * a screen-reader user tabs through 168 identical controls and cannot tell which hour
   * they are about to schedule. Built by the grid, which already holds the translator.
   */
  createLabel: (day: Date, hour: number) => string;
}) {
  return (
    <>
      <div className="relative border-b border-r border-theme px-2 py-1 text-right text-[11px] tabular-nums text-theme-muted">
        {String(hour).padStart(2, '0')}:00
        {/* The time itself sits in the gutter, on the hour labels' own column, so the line
            across the day stays a line: a label riding on it would cover whatever runs at
            that minute, which is the one thing a user looks at the present moment for. */}
        {nowOffsetPercent !== null && (
          <span
            // Hidden from assistive tech, like the line it labels. It is positioned INSIDE
            // the hour cell, so a screen reader reads the cell as one run of text and
            // "09:00" followed by "09:30" becomes "09:0009:30" - two times glued into a
            // number that is neither. The present moment is announced properly by the
            // clock on the bar, which is a named control and says its zone.
            aria-hidden="true"
            // `--accent-foreground`, never a literal white: `--accent-primary` is near-black
            // in the light theme and near-WHITE in the dark one, so a hardcoded white label
            // is invisible on its own pill for half the users. The token is the pair.
            className="pointer-events-none absolute right-1 z-20 -translate-y-1/2 rounded px-1 py-px
                       text-[10px] font-medium leading-none tabular-nums shadow-sm"
            style={{
              top: `${nowOffsetPercent}%`,
              backgroundColor: 'var(--accent-primary)',
              color: 'var(--accent-foreground)',
            }}
          >
            {nowLabel}
          </span>
        )}
      </div>
      {days.map((day) => (
        <HourCell
          key={`${dayKey(day, timezone)}:${hour}`}
          day={day}
          hour={hour}
          timezone={timezone}
          compact={compact}
          occurrences={(occurrencesByDay.get(dayKey(day, timezone)) ?? []).filter(
            (o) => zonedParts(new Date(o.startAt), timezone).hour === hour,
          )}
          focusScheduleId={focusScheduleId}
          canMutate={canMutate}
          nowOffsetPercent={dayKey(day, timezone) === nowDayKey ? nowOffsetPercent : null}
          onSelect={onSelect}
          onCreate={onCreate}
          createLabel={createLabel}
        />
      ))}
    </>
  );
}

/**
 * The hour gutter, and then the days. All three grids here must line up on it.
 *
 * <p>`minmax(0, 1fr)`, so seven days always FIT: a phone gets 40px columns and shows the
 * whole week, which is what a week view is for. Scrolling it sideways instead was tried and
 * abandoned - the hour gutter cannot be pinned while it does. `position: sticky` on a grid
 * item is constrained to its own grid area, so the times slid off the left edge with the
 * days and left a calendar with no hours on it; and taking the gutter out of the grid to
 * pin it is worse, because these rows have no fixed height (a busy hour grows) and a
 * separate column has no way to match them.
 *
 * <p>What makes 40px workable instead is that everything inside adapts to the width it
 * actually gets: the chips through container queries (dot, then kind icon, then name, as
 * room appears) and the day headers below.
 */
function gridColumns(dayCount: number): string {
  return `4rem repeat(${dayCount}, minmax(0, 1fr))`;
}

/**
 * How many chips an hour cell holds before it becomes a scroll container.
 *
 * <p>The cap is `max-h-40`, 160px, and a chip with its gap is around 26px comfortable, so
 * six fill it. Four is deliberately under that: the cost of being wrong high is a cell
 * that clips silently, the cost of being wrong low is a scroll container with nothing to
 * scroll, which chains to the grid anyway and is invisible.
 */
const SCROLLS_ABOVE = 4;

function HourCell({
  day,
  hour,
  timezone,
  compact,
  occurrences,
  focusScheduleId,
  canMutate,
  nowOffsetPercent,
  onSelect,
  onCreate,
  createLabel,
}: {
  day: Date;
  hour: number;
  timezone: string;
  compact: boolean;
  occurrences: AgendaOccurrence[];
  focusScheduleId?: string | null;
  /** False for an org VIEWER: chips are not draggable, matching the row menu. */
  canMutate: boolean;
  /** Where the present moment falls in this cell, or null for every cell but one. */
  nowOffsetPercent: number | null;
  onSelect: (occurrence: AgendaOccurrence, event: React.MouseEvent<HTMLButtonElement>) => void;
  onCreate?: (day: Date, hour: number) => void;
  createLabel: (day: Date, hour: number) => string;
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: `slot:${dayKey(day, timezone)}:${hour}`,
    data: { day: day.toISOString(), hour },
  });
  const scrollable = occurrences.length > SCROLLS_ABOVE;

  return (
    <div
      ref={setNodeRef}
      // Capped and scrollable, but only when there is something to scroll. An hour holding
      // 84 fires - one `*/1` schedule over a busy workspace does it - stretched its row to
      // thousands of pixels, and because every other day shares that row height the whole
      // grid became one unreadable column of whitespace with the rest of the week pushed
      // off screen. Hence the cap.
      //
      // What the cap must NOT do is take the wheel away from the grid. Applying
      // `overflow-y-auto` to every cell made all 168 of them scroll containers, empty ones
      // included, and the wheel then belonged to whichever cell the pointer happened to be
      // over - which is nearly the whole surface of the week. So the class is conditional:
      // below the threshold the cell is a plain div and is never offered the event at all.
      //
      // And the cells that DO scroll chain: `overscroll-contain` was here to stop a cell
      // running away with the grid behind it, but that is the same mechanism as "the grid
      // never scrolls", and the grid is what the user is trying to move. A full cell now
      // scrolls to its end and then hands the rest of the gesture to the hours.
      // `@container`: the chips inside decide what they can show from THIS cell's width,
      // which is the only thing that actually constrains them.
      className={`@container flex min-h-[2.25rem] flex-col gap-0.5 border-b border-r border-theme p-0.5
                  ${scrollable ? 'max-h-40 overflow-y-auto overscroll-y-auto' : ''}
                  ${nowOffsetPercent !== null ? 'relative' : ''}
                  ${isOver ? 'ring-2 ring-inset ring-[var(--accent-primary)]' : ''}`}
    >
      {occurrences.map((occurrence) => (
        <OccurrenceChip
          key={occurrence.id}
          occurrence={occurrence}
          timezone={timezone}
          compact={compact}
          highlighted={Boolean(focusScheduleId) && occurrence.scheduleId === focusScheduleId}
          canMutate={canMutate}
          onSelect={onSelect}
        />
      ))}
      {/* The offer to schedule something here, and it is the cell's LEFTOVER SPACE rather
          than the cell. An hour that already holds a run has white space under it, and an
          hour that holds nothing is white space all the way down; both are the same thing
          to the user pointing at them, and restricting this to empty hours meant a day
          with one 09:00 run could not be scheduled into at all from the calendar.

          A flow child, not an `inset-0` overlay: `flex-1` takes exactly what the chips did
          not, so it can never sit on top of one and swallow its click.

          Not rendered at all once the cell is full (`scrollable`, i.e. past SCROLLS_ABOVE):
          there is no white space left to point at, a `flex-1` button with no room resolves
          to zero height and cannot be clicked anyway, and rendering it regardless would
          still cost the cell a 2px `gap` after the last chip - real pixels, on every busy
          cell, for a control nobody can reach. */}
      {onCreate && !scrollable && (
        <button
          type="button"
          onClick={() => onCreate(day, hour)}
          aria-label={createLabel(day, hour)}
          title={createLabel(day, hour)}
          // The focus ring is the app's own (`Button` stamps this pair on every control);
          // without it a keyboard user gets a plus icon fading in and no indication of
          // which of the 168 slots holds the focus.
          className="flex min-h-0 flex-1 items-center justify-center rounded-sm opacity-0
                     transition-opacity hover:bg-surface-hover hover:opacity-100
                     focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-2
                     focus-visible:ring-[var(--accent-primary)]"
        >
          <Plus className="h-3.5 w-3.5 text-theme-muted" aria-hidden="true" />
        </button>
      )}
      {/* The now-line. Drawn INSIDE the one cell it crosses rather than over the grid,
          because the rows have no fixed height to position against - the same reason the
          offset is a percentage. `pointer-events-none` keeps the cell a drop target
          underneath it, so a chip can still be dropped on the current hour.

          LAST, not first, and it stays that way: the cell was a `space-y-0.5` stack when
          this was written, so as the first child the marker gave the current hour's first
          chip a 2px top margin no other cell had - a misalignment on exactly one cell,
          which is the hardest kind to notice. The stack is `flex ... gap-0.5` now and an
          absolutely positioned child is not a flex item, so `gap` no longer reaches it
          either way; the order is kept because the marker belongs on top of the chips.

          Known limit of living in the cell: past SCROLLS_ABOVE chips the cell becomes its
          own scroll container and the line scrolls with them. That is the current hour of
          today holding five or more fires; the alternative is an overlay measured in
          pixels against rows whose height is decided by their content, which is wrong on
          every busy day rather than on that one. */}
      {nowOffsetPercent !== null && (
        <span
          data-agenda-now-line=""
          aria-hidden="true"
          className="pointer-events-none absolute inset-x-0 z-20 flex -translate-y-1/2 items-center"
          style={{ top: `${nowOffsetPercent}%` }}
        >
          {/* 2px, not 1: the grid's own hour borders are 1px, and a now-line the same
              weight as them reads as one more border rather than as the present moment.
              The dot at the head is what a calendar reader looks for first - and it sits
              INSIDE the cell: hung over the edge with a negative margin it made the one
              cell that can scroll (5+ fires in the current hour) overflow horizontally. */}
          <span
            className="h-2 w-2 shrink-0 rounded-full"
            style={{ backgroundColor: 'var(--accent-primary)' }}
          />
          <span className="h-0.5 flex-1" style={{ backgroundColor: 'var(--accent-primary)' }} />
        </span>
      )}
    </div>
  );
}

/**
 * Occurrences that fall outside the cropped hour range, kept visible above the grid.
 * Renders nothing when the range is the full day or nothing falls outside it.
 */
function OutOfRangeStrip({
  days,
  timezone,
  startHour,
  endHour,
  compact,
  occurrencesByDay,
  focusScheduleId,
  canMutate,
  onSelect,
}: TimeGridViewProps) {
  const outside = useMemo(() => {
    if (startHour === 0 && endHour === 24) return new Map<string, AgendaOccurrence[]>();
    const map = new Map<string, AgendaOccurrence[]>();
    for (const day of days) {
      const key = dayKey(day, timezone);
      const hidden = (occurrencesByDay.get(key) ?? []).filter((o) => {
        const hour = zonedParts(new Date(o.startAt), timezone).hour;
        return hour < startHour || hour >= endHour;
      });
      if (hidden.length > 0) map.set(key, hidden);
    }
    return map;
  }, [days, timezone, startHour, endHour, occurrencesByDay]);

  if (outside.size === 0) return null;

  return (
    <div
      className="grid shrink-0 border-b border-theme bg-theme-secondary/60"
      style={{ gridTemplateColumns: gridColumns(days.length) }}
    >
      <div className="px-2 py-1 text-right text-[11px] text-theme-muted">·</div>
      {/* `@container` on these columns too. A container query with no container evaluates
          FALSE, so the chips in this strip were silently pinned to their narrowest tier -
          time only, no name, at every width - the moment the chip started asking about its
          cell. The strip is a different column from the hour cells and had to be told
          separately. */}
      {days.map((day) => (
        <div key={dayKey(day, timezone)} className="@container space-y-0.5 border-r border-theme p-0.5">
          {(outside.get(dayKey(day, timezone)) ?? []).map((occurrence) => (
            <OccurrenceChip
              key={occurrence.id}
              occurrence={occurrence}
              timezone={timezone}
              compact={compact}
              highlighted={Boolean(focusScheduleId) && occurrence.scheduleId === focusScheduleId}
              canMutate={canMutate}
              onSelect={onSelect}
            />
          ))}
        </div>
      ))}
    </div>
  );
}

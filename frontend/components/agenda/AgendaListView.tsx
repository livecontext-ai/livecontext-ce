'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { MoveRight } from 'lucide-react';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';
import { dayKey, formatFullDate, formatTimeInZone } from '@/lib/utils/agendaTime';
import { useDayKey } from '@/hooks/useNow';
import { AgendaKindIcon } from './AgendaKindIcon';
import { occurrenceKind } from './agendaLaunchKinds';
import { occurrenceAccent, resourceIcon } from './agendaVisuals';

interface AgendaListViewProps {
  occurrences: AgendaOccurrence[];
  timezone: string;
  /** Schedule the user navigated here to find; its rows are ringed. */
  focusScheduleId?: string | null;
  onSelect: (occurrence: AgendaOccurrence, event: React.MouseEvent<HTMLButtonElement>) => void;
}

/**
 * The same window as a flat chronological list, grouped by day.
 *
 * <p>Not a lesser view: it is the one that scales. A month grid with two hundred fires a
 * day is a wall of "+37 more", while the list stays readable, searchable by eye, and
 * shows each run's full name instead of a truncated chip. Days with nothing on them are
 * skipped entirely rather than padded, so scrolling covers real activity only.
 *
 * <p>Dragging is deliberately absent here - there is no spatial target to drop onto. Every
 * action stays reachable through the same row menu the grids use.
 */
export function AgendaListView({ occurrences, timezone, focusScheduleId, onSelect }: AgendaListViewProps) {
  const t = useTranslations('agenda');
  // Day granularity, like the month grid: the only thing the clock decides here is which
  // heading is today, so this list re-renders at midnight and not every minute.
  const todayKey = useDayKey(timezone);
  const groups = useMemo(() => {
    const byDay = new Map<string, { day: Date; items: AgendaOccurrence[] }>();
    for (const occurrence of occurrences) {
      const start = new Date(occurrence.startAt);
      const key = dayKey(start, timezone);
      const group = byDay.get(key);
      if (group) group.items.push(occurrence);
      else byDay.set(key, { day: start, items: [occurrence] });
    }
    return [...byDay.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [occurrences, timezone]);

  if (groups.length === 0) return null;

  return (
    <div className="min-h-0 flex-1 overflow-y-auto rounded-xl border border-theme">
      {groups.map(([key, group]) => (
        <section key={key}>
          <h3
            className={`sticky top-0 z-10 border-b border-theme bg-theme-secondary px-3 py-1.5 text-xs font-medium ${
              // `key` IS this group's day key (the map is bucketed by it), so today costs
              // a string compare and not two Intl reads per heading.
              key === todayKey ? 'text-[var(--accent-primary)]' : 'text-theme-muted'
            }`}
          >
            {formatFullDate(group.day, timezone)}
          </h3>
          <ul>
            {group.items.map((occurrence) => {
              const accent = occurrenceAccent(occurrence);
              const ResourceIcon = resourceIcon(occurrence.resourceType);
              const kind = occurrenceKind(occurrence);
              // A planned fire the backend says will NOT happen: the workflow is
              // over its spending cap for now. Drawn faded rather than hidden,
              // because it comes back on its own and a calendar that silently
              // dropped it would read as "the automation is gone".
              const willNotFire = occurrence.kind === 'PLANNED' && occurrence.armed === false;
              return (
                <li key={occurrence.id}>
                  <button
                    type="button"
                    onClick={(event) => onSelect(occurrence, event)}
                    className={`flex w-full items-center gap-3 border-b border-theme px-3 py-2 text-left
                               transition-colors hover:bg-theme-secondary
                               ${willNotFire ? 'opacity-50' : ''}
                               ${focusScheduleId && occurrence.scheduleId === focusScheduleId
                                  ? 'ring-2 ring-inset ring-[var(--accent-primary)]' : ''}`}
                  >
                    <span className="w-12 shrink-0 text-xs tabular-nums text-theme-muted">
                      {formatTimeInZone(new Date(occurrence.startAt), timezone)}
                    </span>
                    <span className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full ${accent.chip}`}>
                      {kind
                        ? <AgendaKindIcon kind={kind} />
                        : <ResourceIcon className="h-3.5 w-3.5" aria-hidden="true" />}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm text-theme-primary">{occurrence.name}</span>
                      <span className="block truncate text-xs text-theme-muted">
                        {occurrence.kind === 'PAST'
                          ? t(`status.${occurrence.status.toLowerCase()}`)
                          : willNotFire
                            ? t('status.budgetBlocked')
                            : occurrence.cronExpression ?? t('status.planned')}
                      </span>
                    </span>
                    {occurrence.overridden && (
                      <MoveRight className="h-3.5 w-3.5 shrink-0 text-theme-muted" aria-label={t('movedBadge')} />
                    )}
                    <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${accent.dot}`} aria-hidden="true" />
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}

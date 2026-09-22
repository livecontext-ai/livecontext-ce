'use client';

import { useEffect, useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { AlertTriangle, CalendarClock, Repeat } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { ChoiceCard } from '@/components/agenda/ChoiceCard';
import type { AgendaOccurrence, MoveScope } from '@/lib/api/orchestrator/agenda.service';
import { formatFullDate, zonedParts, zonedTimeToInstant } from '@/lib/utils/agendaTime';

interface MoveOccurrenceDialogProps {
  occurrence: AgendaOccurrence | null;
  /** Where the drag dropped it, or the occurrence's own time when opened from the menu. */
  proposedStart: Date | null;
  timezone: string;
  submitting: boolean;
  /** Set when the platform refused, so the user is told why the choice was withdrawn. */
  error: string | null;
  onCancel: () => void;
  onConfirm: (startAt: Date, scope: MoveScope) => void;
}

/**
 * Asks the one question a calendar drag cannot answer by itself: does this move THIS run,
 * or the schedule?
 *
 * <p>The "all occurrences" choice is offered only when the platform can express it.
 * A cron like "every 15 minutes" has no single time of day, so rewriting it would have to
 * invent a frequency - the server refuses, and rather than let the user pick an option
 * that will bounce, the choice is disabled here with the reason spelled out.
 *
 * <p>The time input exists because a drop only carries a DAY (or an hour slot). Landing
 * on a day and then adjusting to 14:30 in one dialog beats a drag precise to the pixel.
 */
export function MoveOccurrenceDialog({
  occurrence,
  proposedStart,
  timezone,
  submitting,
  error,
  onCancel,
  onConfirm,
}: MoveOccurrenceDialogProps) {
  const t = useTranslations('agenda');
  const [scope, setScope] = useState<MoveScope>('NEXT');
  const [timeValue, setTimeValue] = useState('09:00');

  // Seed the time field from the proposed drop each time the dialog opens, so a drag onto
  // a 14:00 slot proposes 14:00 rather than whatever the previous move used.
  useEffect(() => {
    if (!proposedStart) return;
    const parts = zonedParts(proposedStart, timezone);
    setTimeValue(
      `${String(parts.hour).padStart(2, '0')}:${String(parts.minute).padStart(2, '0')}`,
    );
    // Default to whichever scope this occurrence can actually use, so the dialog never
    // opens pre-set to a choice the confirm button will bounce.
    setScope(occurrence?.isNextFire ? 'NEXT' : 'ALL');
  }, [proposedStart, timezone, occurrence?.isNextFire]);

  const resolvedStart = useMemo(() => {
    if (!proposedStart) return null;
    const [hour, minute] = timeValue.split(':').map((v) => Number(v));
    if (!Number.isFinite(hour) || !Number.isFinite(minute)) return proposedStart;
    const day = zonedParts(proposedStart, timezone);
    return zonedTimeToInstant(timezone, day.year, day.month, day.day, hour, minute);
  }, [proposedStart, timeValue, timezone]);

  if (!occurrence || !proposedStart || !resolvedStart) return null;

  const movingToThePast = resolvedStart.getTime() < Date.now();
  const canMoveAll = occurrence.moveAllSupported;
  // A schedule points at ONE pending fire. Moving a later occurrence "on its own" would
  // write its new time into that pointer and silently cancel every run in between, so the
  // choice is offered only where it is truthful.
  const canMoveNext = occurrence.isNextFire;

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onCancel(); }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t('move.title', { name: occurrence.name })}</DialogTitle>
          <DialogDescription>
            {t('move.description', { date: formatFullDate(resolvedStart, timezone) })}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <label className="flex items-center justify-between gap-3 text-sm">
            <span className="text-theme-secondary">{t('move.timeLabel')}</span>
            <input
              type="time"
              value={timeValue}
              onChange={(event) => setTimeValue(event.target.value)}
              className="rounded-lg border border-theme bg-theme-primary px-2 py-1 text-sm tabular-nums"
            />
          </label>

          <div className="space-y-2">
            <ChoiceCard
              active={scope === 'NEXT'}
              icon={CalendarClock}
              disabled={!canMoveNext}
              title={t('move.scopeNext')}
              description={
                canMoveNext ? t('move.scopeNextHint') : t('move.scopeNextUnavailable')
              }
              onSelect={() => setScope('NEXT')}
            />
            <ChoiceCard
              active={scope === 'ALL'}
              icon={Repeat}
              disabled={!canMoveAll}
              title={t('move.scopeAll')}
              description={
                canMoveAll
                  ? t('move.scopeAllHint')
                  : t('move.scopeAllUnavailable', { cron: occurrence.cronExpression ?? '' })
              }
              onSelect={() => setScope('ALL')}
            />
          </div>

          {movingToThePast && (
            // Honest rather than forbidden: the daemon treats an overdue fire as due, so
            // this genuinely means "run at the next tick" - the user should know that is
            // what they are asking for.
            <p className="flex items-start gap-2 rounded-lg bg-amber-50 p-2 text-xs text-amber-900 dark:bg-amber-950/40 dark:text-amber-100">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {t('move.pastWarning')}
            </p>
          )}

          {error && (
            <p className="rounded-lg bg-red-50 p-2 text-xs text-red-800 dark:bg-red-950/40 dark:text-red-100">
              {error}
            </p>
          )}
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={onCancel} disabled={submitting}>
            {t('common.cancel')}
          </Button>
          <Button
            onClick={() => onConfirm(resolvedStart, scope)}
            disabled={submitting || (scope === 'NEXT' && !canMoveNext) || (scope === 'ALL' && !canMoveAll)}
          >
            {submitting ? t('common.working') : t('move.confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

'use client';

import { useDraggable } from '@dnd-kit/core';
import { MoveRight } from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';
import { formatTimeInZone } from '@/lib/utils/agendaTime';
import { AgendaKindIcon } from './AgendaKindIcon';
import { occurrenceKind } from './agendaLaunchKinds';
import { isMovable, occurrenceAccent, resourceIcon } from './agendaVisuals';

interface OccurrenceChipProps {
  occurrence: AgendaOccurrence;
  timezone: string;
  compact?: boolean;
  /**
   * Ringed because the user arrived here looking for this specific schedule (from the
   * notification bell). Without it they land on the right month and still have to hunt.
   */
  highlighted?: boolean;
  /** False for an org VIEWER: the drag is not offered, matching the menu. */
  canMutate: boolean;
  /**
   * Opens the action menu. Receives the click event because the menu anchors to the
   * chip's on-screen rect, and the chips live inside scrollable grids where a stored
   * coordinate would be wrong as soon as the grid scrolls.
   */
  onSelect: (occurrence: AgendaOccurrence, event: React.MouseEvent<HTMLButtonElement>) => void;
}

/**
 * One occurrence on the calendar.
 *
 * <p>There is deliberately no "paused" badge here: a paused schedule projects no
 * occurrence at all (it will not fire), so it reaches the user as a greyed marker on the
 * unscheduled rail instead. A badge on this component could never render.
 *
 * <p>Draggable only when the platform can actually honour a move: a past fire is history
 * and a schedule-less entry has nothing to address. Making them drag and then refusing on
 * drop would teach the user the gesture works and then take it away.
 *
 * <p>The drag listeners sit on the chip while the click handler stays a real button
 * `onClick`. The shared sensors (`useDragSensors`) ask a mouse for a few pixels of travel
 * and a finger for a quarter-second hold, so neither a plain click nor a scroll is a drag,
 * and one chip can both open a menu and be dragged without a modifier key.
 */
export function OccurrenceChip({
  occurrence,
  timezone,
  compact,
  highlighted,
  canMutate,
  onSelect,
}: OccurrenceChipProps) {
  const t = useTranslations('agenda');
  // Movable AND allowed. isMovable answers whether the PLATFORM can express the move; a
  // read-only member fails the other half, and the menu already gates on it. Leaving the
  // drag open meant the one surface that still taught the gesture was the one with no
  // menu to explain why it would be refused - the server holds, so this is honesty rather
  // than security.
  const draggable = isMovable(occurrence) && canMutate;
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: occurrence.id,
    data: occurrence,
    disabled: !draggable,
  });

  const accent = occurrenceAccent(occurrence);
  const ResourceIcon = resourceIcon(occurrence.resourceType);
  const kind = occurrenceKind(occurrence);
  const time = formatTimeInZone(new Date(occurrence.startAt), timezone);
  const isPast = occurrence.kind === 'PAST';

  // A planned fire the backend says will NOT happen: the workflow is over its
  // spending cap for now. Faded rather than hidden, because it comes back on
  // its own and a calendar that dropped it would read as "the automation is
  // gone" - which is exactly the wrong thing to tell someone whose automation
  // is merely resting.
  const willNotFire = occurrence.kind === 'PLANNED' && occurrence.armed === false;

  // The launch kind is drawn as a GLYPH, which is aria-hidden and therefore says nothing
  // to a screen reader. On a past chip it is the one fact that distinguishes two runs of
  // the same agent at the same hour, so it belongs in the accessible name too - where it
  // also survives every width the glyph is hidden at.
  const suffixes: string[] = [];
  if (isPast && kind) suffixes.push(t(`kind.${kind.toLowerCase()}`));
  if (willNotFire) suffixes.push(t('status.budgetBlocked'));
  const label = [`${time} ${occurrence.name}`, ...suffixes].join(' - ');

  return (
    <button
      ref={setNodeRef}
      type="button"
      {...listeners}
      {...attributes}
      onClick={(event) => onSelect(occurrence, event)}
      title={label}
      aria-label={label}
      // `touch-manipulation` is what dnd-kit asks for beside a hold-to-drag touch sensor:
      // it takes the double-tap gesture away from the browser, so the quarter-second hold
      // is not competing with a zoom the browser might still claim.
      className={`group flex w-full touch-manipulation items-center gap-1 rounded-md px-1 text-left @[6.5rem]:gap-1.5 @[6.5rem]:px-1.5
                  ${compact ? 'py-0.5' : 'py-1'}
                  ${accent.chip}
                  ${isDragging ? 'opacity-40' : willNotFire ? 'opacity-50' : 'opacity-100'}
                  ${draggable ? 'cursor-grab active:cursor-grabbing' : 'cursor-pointer'}
                  ${isPast ? 'border border-dashed border-current/25' : ''}
                  ${highlighted ? 'ring-2 ring-[var(--accent-primary)]' : ''}
                  transition-colors hover:brightness-95 dark:hover:brightness-110`}
    >
      {/* What a chip shows depends on the width of the CELL it is in, not the width of the
          window: seven days in a phone gives a 40px month cell, while the same phone in day
          view gives that chip the whole screen. Only the cell knows, so the cell is the
          container and these are container queries.

          The time is what survives at every width - it is the fact a calendar is scanned
          for. Then, as room appears: the dot at 4.5rem, the name at 6.5rem (where about
          eight characters fit, below which it would be noise), the kind icon at 8rem, since
          it only repeats what the chip's accent colour already says.

          Nothing is lost at any width: the full "09:15 Weekly digest" stays in `title` and
          `aria-label`, and tapping opens a menu that names it. Before this every part was
          `shrink-0`, so a narrow cell clipped the time mid-digit ("08:1") and showed no
          name at all. */}
      {/* The dot is the resource kind as a colour, which the accent of the chip already
          carries; the time is the fact you came for. In a month cell on a phone (~42px of
          content) they do not both fit, and keeping the dot cost the last digits of the
          time. */}
      <span className={`hidden h-1.5 w-1.5 shrink-0 rounded-full @[4.5rem]:block ${accent.dot}`} aria-hidden="true" />
      <span className="shrink-0 text-xs tabular-nums opacity-80">{time}</span>

      <span className="hidden min-w-0 flex-1 truncate text-xs @[6.5rem]:block">{occurrence.name}</span>
      <span className="hidden shrink-0 opacity-70 @[8rem]:block" aria-hidden="true">
        {/* How it started, in one glyph: the trigger node's icon for a workflow, the
            launch kind for an agent run. Both come through one component because the two
            vocabularies overlap and the chip must not care which one answered. Unknown
            falls back to the RESOURCE icon, which says what ran without claiming to know
            what started it. */}
        {kind
          ? <AgendaKindIcon kind={kind} />
          : <ResourceIcon className="h-3 w-3" />}
      </span>
      {occurrence.overridden && (
        // This one run was moved off its schedule; without a mark the calendar looks
        // simply wrong to anyone who knows the cron. Width-gated like the name: unguarded
        // and `shrink-0`, it took 16px of a 39px cell and clipped the time mid-digit -
        // exactly the breakage the tiers exist to remove, on the one chip that most needs
        // its time read. It stays in the accessible name at every width.
        <MoveRight className="hidden h-3 w-3 shrink-0 opacity-70 @[6.5rem]:block" aria-label={t('movedBadge')} />
      )}
    </button>
  );
}

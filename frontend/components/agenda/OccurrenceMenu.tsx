'use client';

import { useTranslations } from 'next-intl';
import { CalendarClock, ExternalLink, Pause, Play, Zap } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { menuItemClass, menuSurfaceClass } from '@/components/ui/menu';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';
import { formatFullDate, formatTimeInZone } from '@/lib/utils/agendaTime';
import { AgendaKindIcon } from './AgendaKindIcon';
import { occurrenceKind } from './agendaLaunchKinds';
import { isActionable, isMovable, occurrenceAccent, resourceIcon } from './agendaVisuals';
import {
  canControlProductionResource,
  productionResourceKind,
} from '@/lib/api/orchestrator/resource-control';

interface OccurrenceMenuProps {
  occurrence: AgendaOccurrence | null;
  timezone: string;
  /** Anchor rect of the chip that was clicked, so the popover opens where the user looked. */
  anchor: { x: number; y: number } | null;
  busy: boolean;
  canMutate: boolean;
  onClose: () => void;
  onRunNow: (occurrence: AgendaOccurrence) => void;
  onMove: (occurrence: AgendaOccurrence) => void;
  onTogglePause: (occurrence: AgendaOccurrence) => void;
  onToggleResourcePause?: (occurrence: AgendaOccurrence) => void;
  onOpenResource: (occurrence: AgendaOccurrence) => void;
}

/**
 * What a user can do with one occurrence, opened by clicking its chip.
 *
 * <p>The action set changes with what the entry IS, rather than showing everything and
 * failing on click: a past fire can only be opened, and a viewer with no mutate rights in
 * this workspace gets the same read-only treatment as everywhere else in the app.
 *
 * <p>"Run now" runs the job early and KEEPS the scheduled occurrence: on a calendar,
 * running something ahead of time does not mean cancelling the run you can see sitting
 * there. The label says so, because the opposite behaviour is equally defensible and the
 * user should not have to test it to find out which one they got.
 */
export function OccurrenceMenu({
  occurrence,
  timezone,
  anchor,
  busy,
  canMutate,
  onClose,
  onRunNow,
  onMove,
  onTogglePause,
  onToggleResourcePause = () => {},
  onOpenResource,
}: OccurrenceMenuProps) {
  const t = useTranslations('agenda');
  if (!occurrence || !anchor) return null;

  const Icon = resourceIcon(occurrence.resourceType);
  const accent = occurrenceAccent(occurrence);
  const kind = occurrenceKind(occurrence);
  const start = new Date(occurrence.startAt);
  const actionable = isActionable(occurrence) && canMutate;
  const resourceKind = productionResourceKind(occurrence.resourceType);
  // Running early acts on the SCHEDULE, so it is offered on every planned chip. Moving
  // acts on the OCCURRENCE and needs a scope that can express it, which chips 2..n of an
  // interval schedule have neither of. Offering it anyway opened a dialog whose only
  // working control was Cancel - the same dead end the drag gesture used to have, reached
  // by the other door.
  const movable = isMovable(occurrence) && canMutate;
  // The RESOURCE-level verdict, not this occurrence's `armed`. Run early acts on the
  // SCHEDULE at the moment of the click, so what matters is whether the cap holds now, not
  // whether it holds at the fire being looked at: an occurrence after the reset date is
  // armed and would still be refused today. Read once, because the label and the disabled
  // state are one claim and a second derivation is how they drift.
  const budgetBlocked = occurrence.kind === 'PLANNED' && occurrence.budgetBlocked === true;

  return (
    <Popover open onOpenChange={(open) => { if (!open) onClose(); }}>
      {/* An invisible anchor pinned to the chip's position: the chips live inside
          scrollable grids, so a portalled popover needs a real element to align to. */}
      <PopoverTrigger asChild>
        <span
          aria-hidden="true"
          style={{ position: 'fixed', left: anchor.x, top: anchor.y, width: 1, height: 1 }}
        />
      </PopoverTrigger>
      <PopoverContent align="start" className={`w-72 ${menuSurfaceClass}`}>
        <div className="mb-2 flex items-start gap-2 px-1.5 pt-1">
          <span className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full ${accent.chip}`}>
            <Icon className="h-3.5 w-3.5" aria-hidden="true" />
          </span>
          <span className="min-w-0">
            <span className="block truncate text-sm text-theme-primary">{occurrence.name}</span>
            <span className="block text-xs text-theme-muted">
              {formatFullDate(start, timezone)} · {formatTimeInZone(start, timezone)}
            </span>
            {occurrence.kind === 'PAST' && (
              <span className="mt-0.5 block text-xs text-theme-muted">
                {t(`status.${occurrence.status.toLowerCase()}`)}
              </span>
            )}
            {/* How this run started, spelled out. The chip carries the same fact as one
                small glyph, which is enough to scan a month by and not enough to answer
                "why did my agent run at 03:12" - the question this menu exists for. Only
                on a PAST entry: a projection's launch is its schedule, which the cron
                line below already states. */}
            {occurrence.kind === 'PAST' && kind && (
              <span className="mt-0.5 flex items-center gap-1 text-xs text-theme-muted">
                <AgendaKindIcon kind={kind} className="shrink-0" />
                <span className="truncate">{t('launchedBy', { kind: t(`kind.${kind.toLowerCase()}`) })}</span>
              </span>
            )}
            {occurrence.kind === 'PLANNED' && occurrence.cronExpression && (
              <span className="mt-0.5 block truncate font-mono text-[11px] text-theme-muted">
                {occurrence.cronExpression} · {occurrence.timezone}
              </span>
            )}
          </span>
        </div>

        <div className="space-y-0.5">
          {actionable && (
            <>
              {/* A spending cap is refusing this resource right now, so running early would
                  only produce a failure toast: the server gate asks the same question at the
                  same moment. Offered but disabled rather than hidden, so the reason is on
                  screen instead of the action simply going missing. */}
              <MenuItem
                icon={Zap}
                label={t('menu.runNow')}
                hint={budgetBlocked ? t('menu.runNowBudgetBlocked') : t('menu.runNowHint')}
                disabled={busy || budgetBlocked}
                onClick={() => onRunNow(occurrence)}
              />
              {movable && (
                <MenuItem
                  icon={CalendarClock}
                  label={t('menu.move')}
                  disabled={busy}
                  onClick={() => onMove(occurrence)}
                />
              )}
              {/* Pause only. An occurrence exists because its schedule is armed, so the
                  resume branch is unreachable from here - a paused schedule projects no
                  occurrence to click. Resuming is done from the resource's own page. */}
              <MenuItem
                icon={Pause}
                label={t('menu.pause')}
                disabled={busy}
                onClick={() => onTogglePause(occurrence)}
              />
            </>
          )}
          {canMutate && occurrence.kind === 'PLANNED' && canControlProductionResource(occurrence) && (
            <MenuItem
              icon={occurrence.resourcePaused ? Play : Pause}
              label={occurrence.resourcePaused
                ? t('menu.resumeResource', { type: resourceKind })
                : t('menu.pauseResource', { type: resourceKind })}
              disabled={busy}
              onClick={() => onToggleResourcePause(occurrence)}
            />
          )}
          {/* A past fire opens the RUN, on the epoch that was clicked; a planned one opens
              the resource, because the run does not exist yet. One label for both said
              "Open resource" and was wrong half the time - and now that this menu is the
              only way through, the label is the last thing the user reads before leaving
              the page. */}
          <MenuItem
            icon={ExternalLink}
            label={occurrence.kind === 'PAST' && occurrence.conversationId
              ? t('menu.openConversation')
              : occurrence.kind === 'PAST' && occurrence.runIdPublic
                ? t('menu.openRun')
                : t('menu.open')}
            onClick={() => onOpenResource(occurrence)}
          />
        </div>
      </PopoverContent>
    </Popover>
  );
}

function MenuItem({
  icon: Icon,
  label,
  hint,
  disabled,
  onClick,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  hint?: string;
  disabled?: boolean;
  onClick: () => void;
}) {
  // menuItemClass, not a private copy. The app draws this menu in several places and every
  // one that wrote its own row ended up with a different radius or hover; the module says so
  // in its own docstring, and this feature wrote one anyway.
  return (
    <button type="button" onClick={onClick} disabled={disabled} className={menuItemClass}>
      <Icon className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
      <span className="min-w-0">
        <span className="block">{label}</span>
        {hint && <span className="block text-xs text-theme-muted">{hint}</span>}
      </span>
    </button>
  );
}

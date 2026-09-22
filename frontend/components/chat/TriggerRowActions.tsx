'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { CalendarClock, MoreVertical, Pause, Play, Zap } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { menuItemClass, menuSurfaceClass } from '@/components/ui/menu';
import { agendaService } from '@/lib/api/orchestrator/agenda.service';
import { useRefreshHomeStatus } from '@/hooks/useHomeStatus';
import { agendaErrorText } from '@/components/agenda/agendaErrors';
import type { ActiveAutomation } from '@/lib/api/orchestrator/dashboard.service';
import {
  canControlProductionResource,
  productionResourceKind,
  setProductionResourcePaused,
} from '@/lib/api/orchestrator/resource-control';

/**
 * What a row must do with the content the menu lands on, applied by the row itself.
 *
 * <p>The three dots are revealed on top of the fire-time column ("in 4 min" over "Last ran
 * 2h ago"), and two pieces of text sharing twenty pixels is why the control was hard to
 * pick out. Masking it from the button's side was tried and is wrong: the mask has to be
 * given a width, the text it covers is `whitespace-nowrap` and grows with the language (a
 * German "Zuletzt: vor 3 Stunden", or a full date once a run is older than a week), and a
 * mask painted in the row's hover colour is a grey patch on any row revealed WITHOUT hover -
 * by keyboard focus, or by its own menu being open while the pointer sits in the popover.
 *
 * <p>So the content yields instead. It fades where it stands, keeping its width, so nothing
 * reflows and nothing shows through, at any length and in any theme. The three conditions
 * mirror the button's own reveal exactly; a test pins that they stay in step.
 */
export const TRIGGER_ROW_ACTIONS_YIELD =
  'transition-opacity group-hover:opacity-0 group-focus-within:opacity-0 '
  + 'group-has-[[data-state=open]]:opacity-0';

/**
 * Whether a row has a menu at all, which is also whether its content should yield.
 *
 * <p>Exported because the two facts have to be ONE fact. A webhook, chat or form row has no
 * schedule to act on and renders no button (see the guard below), so applying the rule to
 * every row made hovering those rows blank their own label - "Live", or "2m ago" - and put
 * nothing in its place. The row asks this before it yields; the component asks the same
 * thing before it renders.
 */
export function hasTriggerRowActions(automation: ActiveAutomation): boolean {
  return Boolean(automation.schedule?.scheduleId) || canControlProductionResource(automation);
}

interface TriggerRowActionsProps {
  automation: ActiveAutomation;
  onNavigate: (href: string) => void;
  onResult: (kind: 'success' | 'error', message: string) => void;
}

/**
 * Row actions for a scheduled automation in the notification bell's Triggers tab.
 *
 * <p>The bell is where a user notices "this fires in 4 minutes", so it is where they want
 * to act - not two navigations away. The two run choices offered here are the agenda's
 * own, spelled out rather than hidden behind one ambiguous "Run":
 * <ul>
 *   <li><b>Run now</b> keeps the scheduled run. Running something early is not the same
 *       as cancelling the run you can see coming.</li>
 *   <li><b>Run instead</b> consumes it, for when the early run REPLACES the scheduled one.</li>
 * </ul>
 * Anything involving a date (moving an occurrence, seeing the week) hands off to the
 * agenda, which is the surface built for it - the bell does not grow a calendar.
 */
export function TriggerRowActions({ automation, onNavigate, onResult }: TriggerRowActionsProps) {
  const t = useTranslations('chat.home.live.triggerActions');
  // The agenda's refusal sentences, reused verbatim: the bell is the surface where a user
  // notices a fire is imminent, so a bare "could not run it" there is the least useful
  // place to lose the reason.
  // Rooted at `agenda`, not `agenda.errors`: agendaErrorText returns full keys such
  // as `errors.notArmed`, so the calendar and this menu resolve the same strings.
  const tAgenda = useTranslations('agenda');
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  // The row is a countdown to a fire time these actions MOVE, and the list behind it polls
  // once a minute. Without asking again the user runs the occurrence, is told it worked,
  // and watches the same "in 4 min" for up to sixty seconds - which is indistinguishable
  // from the action having done nothing, and is how "run instead" was reported broken.
  const refreshAutomations = useRefreshHomeStatus();
  const scheduleId = automation.schedule?.scheduleId;
  const resourceKind = productionResourceKind(automation.resourceType);
  // A spending cap refuses every fire of this schedule, the ones asked for from here
  // included. Read straight from the server's verdict rather than re-derived: the cap can
  // be a workflow's period budget or an agent's own credit budget, and this row does not
  // need to know which.
  const budgetBlocked = Boolean(automation.schedule?.budgetBlocked);

  // Same condition as `hasTriggerRowActions`, read through it so the row and the menu can
  // never disagree about whether this row has one.
  if (!hasTriggerRowActions(automation)) return null;

  const run = async (keepNextOccurrence: boolean) => {
    if (!scheduleId) return;
    setBusy(true);
    try {
      const outcome = await agendaService.runNow(scheduleId, keepNextOccurrence);
      setOpen(false);
      // "in place of the scheduled run" is claimed only when the platform says it gave the
      // occurrence up. It refuses in several ordinary situations - the daemon claimed the
      // fire first, the cron has no later slot - and in every one of them the scheduled run
      // still happens, which is exactly what `ranKeeping` says. Announcing a replacement
      // that did not occur is the defect this work exists to remove, moved into the copy.
      const replaced = !keepNextOccurrence && outcome?.occurrenceConsumed === true;
      onResult('success', replaced ? t('ranReplacing') : t('ranKeeping'));
      // After the sentence, not before it: the refetch re-renders this row, and on the
      // consuming branch the fire time it carries is the one that just changed.
      refreshAutomations();
    } catch (error) {
      // Same mapping the calendar uses, not a second copy of three of its nine branches.
      // The inline version answered a generic "could not run it" for EXECUTION_REFUSED
      // without detail, SCHEDULE_REJECTED, and every move-specific refusal - so the bell
      // and the agenda gave different answers to the same failure. agendaErrors exists to
      // make these branches testable; a private copy here is untested by construction.
      const { key, detail } = agendaErrorText(error);
      onResult('error', key ? tAgenda(key) : (detail ?? t('runFailed')));
    } finally {
      setBusy(false);
    }
  };

  const toggleResourcePause = async () => {
    setBusy(true);
    try {
      const paused = !automation.resourcePaused;
      await setProductionResourcePaused(automation, paused);
      setOpen(false);
      onResult('success', paused
        ? t('resourcePaused', { type: resourceKind })
        : t('resourceResumed', { type: resourceKind }));
      refreshAutomations();
    } catch {
      onResult('error', t('resourceToggleFailed', { type: resourceKind }));
    } finally {
      setBusy(false);
    }
  };

  const openInAgenda = () => {
    setOpen(false);
    // Hand the agenda the day the fire lands on plus the schedule to highlight, so the
    // user arrives looking at the occurrence rather than at today.
    const params = new URLSearchParams();
    if (scheduleId) params.set('focus', scheduleId);
    if (automation.schedule?.nextFireAt) params.set('date', automation.schedule.nextFireAt);
    const query = params.toString();
    onNavigate(query ? `/app/agenda?${query}` : '/app/agenda');
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghostGray"
          aria-label={t('label')}
          onClick={(event) => event.stopPropagation()}
          // Out of the flow, so it costs the row NO width. In the flow it held a column
          // open on EVERY row for a control that is invisible almost all the time, and the
          // workflow name and the fire-time label paid for it on the narrowest surface in
          // the app.
          //
          // What it does NOT carry is a MASK. It used to: floating it put the dots on top
          // of the fire-time label and the two rendered through each other, so a patch was
          // painted behind the button. That never worked - the label is wider than the
          // button, by a different amount in every language, so its ends kept showing on
          // both sides, and the patch was painted in the row's HOVER colour, which is wrong
          // on a row revealed by keyboard focus or by its own open menu. The row hides that
          // text instead (TRIGGER_ROW_ACTIONS_YIELD, applied in NotificationBell), which is
          // exact at any width, in any locale and in every reveal state.
          //
          // The hover ground it does have is a neutral one, overriding the variant's
          // inversion (see the className below). That is the button's affordance, not a
          // mask: it appears under the pointer, on a control the user is pointing at.
          //
          // `data-[state=open]` matters here: the pointer leaves the row the instant it
          // travels into the open popover, so without it the button faded out from under
          // the menu it had just opened. Radix stamps that attribute on the trigger, so the
          // popover's own state drives it instead of a second flag that could disagree.
          // Same override as the bell above it: `ghostGray` inverts on hover, so the three
          // dots became a black tile the moment the pointer reached them - on a row that is
          // already tinted by its own hover. Neutral ground, glyph keeps its colour.
          //
          // `hover:text-[var(--text-primary)]`, not `hover:text-theme-primary`: the latter
          // is a hand-written rule in `@layer components`, not a Tailwind utility, so
          // Tailwind generates no `hover:` variant for it and the class emits NOTHING. The
          // glyph colour would then be surviving only because twMerge deleted the variant's
          // own hover text - true today, and a trap for whoever edits this next.
          className="absolute right-1 top-1/2 z-10 h-6 w-6 -translate-y-1/2 rounded-lg p-0
                     text-theme-secondary opacity-0 transition-opacity
                     hover:bg-surface-hover hover:text-[var(--text-primary)]
                     group-hover:opacity-100 group-focus-within:opacity-100
                     focus:opacity-100 data-[state=open]:opacity-100"
        >
          {/* Vertical, like every other row menu in the app: a horizontal ellipsis reads
              as "truncated text" beside a label, a vertical one as "there is a menu here". */}
          <MoreVertical className="h-3.5 w-3.5" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className={`w-64 ${menuSurfaceClass}`}>
        {scheduleId && (
          <>
            {/* Both of these ask the schedule to fire NOW, and a spending cap refuses a
                fire whoever asked for it - so offering them on a capped row would only
                produce a failure toast. Disabled rather than hidden, so the row states the
                reason instead of quietly losing its actions. */}
            <ActionItem
              icon={Zap}
              label={t('runNow')}
              hint={budgetBlocked ? t('runNowBudgetBlocked') : t('runNowHint')}
              disabled={busy || budgetBlocked}
              onClick={() => void run(true)}
            />
            <ActionItem
              icon={Zap}
              label={t('runInstead')}
              hint={budgetBlocked ? t('runNowBudgetBlocked') : t('runInsteadHint')}
              disabled={busy || budgetBlocked}
              onClick={() => void run(false)}
            />
          </>
        )}
        {canControlProductionResource(automation) && (
          <ActionItem
            icon={automation.resourcePaused ? Play : Pause}
            label={automation.resourcePaused
              ? t('resumeResource', { type: resourceKind })
              : t('pauseResource', { type: resourceKind })}
            disabled={busy}
            onClick={() => void toggleResourcePause()}
          />
        )}
        <ActionItem icon={CalendarClock} label={t('openAgenda')} onClick={openInAgenda} />
      </PopoverContent>
    </Popover>
  );
}

function ActionItem({
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
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={(event) => {
        event.stopPropagation();
        onClick();
      }}
      className={menuItemClass}
    >
      <Icon className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
      <span className="min-w-0">
        <span className="block">{label}</span>
        {hint && <span className="block text-xs text-theme-muted">{hint}</span>}
      </span>
    </button>
  );
}

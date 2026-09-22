'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Coins } from 'lucide-react';
import { formatCost, formatCostCompact, isCeMode } from '@/lib/format-cost';
import { budgetChipHasContent, budgetPeriodLabelKey, type BudgetPeriodMode } from './budgetPeriod';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { BudgetPopoverContent } from './BudgetPopover';

export type BudgetChipProps = {
  /**
   * What has been spent in the period currently open, in CREDITS. Undefined or
   * null means "not tracked here" and renders nothing at all.
   */
  spent?: number | null;
  /**
   * The cap, in CREDITS, or null/undefined when none is set. Leaving it empty
   * is the default and means unlimited, exactly like an agent's budget: the
   * chip then shows the running spend on its own rather than a scary badge.
   */
  cap?: number | null;
  /** monthly | weekly | cumulative. Drives the "this month" / "this week" wording. */
  periodMode?: string | null;
  /**
   * What an EMPTY `periodMode` means for this owner, because the two owners
   * disagree: a workflow with no mode set resets monthly, an agent with no mode
   * set never resets. Defaults to the workflow rule; agent call sites pass
   * `cumulative`.
   */
  fallbackPeriod?: BudgetPeriodMode;
  /**
   * When the allowance starts again, as sent by the server. Null means the cap
   * never resets. Only used by the popover; the chip itself stays a figure.
   */
  resetsAt?: string | null;
  /**
   * False when the figure may be stale, which is the case for an agent: its
   * counter is reset lazily, only when the agent next executes. The popover then
   * withholds any claim that the automation is stopped.
   */
  spendIsCurrent?: boolean;
  /**
   * The server's verdict that this cap is refusing runs right now. Overrides the
   * figure-based guess in the popover; see BudgetPopoverContentProps.blocked.
   */
  blocked?: boolean;
  /** Rendered small and muted inside a card meta row (default), or standalone. */
  className?: string;
  /**
   * Draw the coin glyph before the figure. Default true.
   *
   * <p>Pass false where the row ALREADY opens with one, which is the case
   * wherever a run's own cost is printed just before this chip (the run panel's
   * bottom row, the board card's meta row). The glyph labels the money cluster,
   * not each figure in it, so a second one mid-row is noise.
   */
  showIcon?: boolean;
};

/**
 * "Spent / cap" at a glance, for a workflow, an application or an agent.
 *
 * <p>This is the number the whole spending-cap feature exists to make visible.
 * Before it, a cost was only readable by opening a run or the agent metrics
 * tab, so nobody could tell what an automation was costing them until the bill
 * arrived. It therefore belongs on the card itself, in the meta row, next to
 * the things people already read there.
 *
 * <p>Renders nothing when there is neither a spend nor a cap: a workflow that
 * has never run in production must not carry a "0" that means nothing.
 */
export function BudgetChip({
  spent,
  cap,
  periodMode,
  fallbackPeriod = 'monthly',
  resetsAt,
  spendIsCurrent = true,
  blocked,
  className,
  showIcon = true,
}: BudgetChipProps) {
  const t = useTranslations();
  // Controlled only so a TAP can open it; hover and focus still go through
  // Radix's own onOpenChange.
  const [open, setOpen] = React.useState(false);
  const hasCap = cap != null && cap > 0;
  const spentValue = spent ?? 0;

  // Same predicate the call sites gate their separator on, so a chip that
  // renders nothing can never leave a dangling one behind.
  if (!budgetChipHasContent(spent, cap)) return null;

  const periodLabel = t(budgetPeriodLabelKey(periodMode, fallbackPeriod));

  // Only a capped workflow can be "over": an uncapped one is just reporting.
  const ratio = hasCap ? spentValue / (cap as number) : 0;
  // The server verdict colours the chip too, not just the popover. Otherwise a row whose
  // hover says the automation is stopped is drawn in the calm grey, which is the case for
  // an agent held back by credits an in-flight sub-agent is holding: the spend alone is
  // well under the cap and the ratio below knows nothing about it.
  const overCap = blocked ?? (hasCap && ratio >= 1);
  const tone = !hasCap
    ? 'text-theme-muted'
    : overCap
      ? 'text-red-600 dark:text-red-400'
      : ratio >= 0.8
        ? 'text-amber-600 dark:text-amber-400'
        : 'text-theme-muted';

  // No `title`: the detail lives in the popover now. A native tooltip on top of
  // it would double every hover with a second, poorer copy of the same thing.
  //
  // The full sentence survives as visually-hidden TEXT, not as an aria-label:
  // this is a plain element whose implicit role is `generic`, and aria-label on
  // a generic element is prohibited by ARIA-in-HTML and dropped from the
  // accessibility tree by real browsers. Text is not. It carries the FIGURE, in
  // full precision; the popover carries the rest, and Radix wires that to the
  // trigger with aria-describedby.
  const label = hasCap
    ? t('budget.chipTitleCapped', {
        period: periodLabel,
        unit: t(isCeMode ? 'budget.unitDollars' : 'budget.unitCredits'),
        values: `${formatCost(spentValue, 4)} / ${formatCost(cap as number, 4)}`,
      })
    : t('budget.chipTitleUncapped', { period: periodLabel, values: formatCost(spentValue, 4) });

  return (
    // Radix, not a hand-rolled hover: the repo already ships this wrapper (used
    // in 21 files, including RunStepsPanel, which renders a multi-paragraph body
    // behind a hover exactly like this one). Writing it again bought a
    // mouse-only, >=1024px-only subset - no keyboard, no aria-describedby - of
    // what the primitive gives, plus 200 lines of placement and listener code.
    //
    // The trigger is a BUTTON so it can take focus: that is what makes the
    // detail reachable without a mouse. It sits in a card meta row and never
    // inside another button or link, so it adds a tab stop and no invalid
    // nesting.
    //
    // TOUCH is the one thing Radix's Tooltip does NOT give: its open-on-hover
    // returns early on `pointerType === 'touch'` by design, because a tooltip
    // has no dismiss gesture on a phone. Left alone, tapping the figure would
    // open nothing AND bubble to the card, so the only visible effect of
    // tapping a spending figure would be being navigated somewhere else. Hence
    // the two handlers below: a tap toggles it, and no activation of this
    // control ever reaches the card underneath.
    //
    // `preventDefault()` on the tap is load-bearing, for one specific reason.
    // Radix also closes on `onClick` (its trigger composes `onClose` into it),
    // and a real touch tap emits a compatibility `click` right after
    // `pointerdown`. Without it, that click closes the popover in the same
    // gesture that opened it - visibly nothing happens. Preventing the default
    // on `pointerdown` is what suppresses the compatibility click. It is NOT
    // needed against Radix's own pointerdown handler, which closes only when
    // the popover is ALREADY open and so leaves the opening tap alone.
    <TooltipProvider delayDuration={400}>
      <Tooltip open={open} onOpenChange={setOpen}>
        <TooltipTrigger asChild>
          <button
            type="button"
            data-testid="budget-chip"
            onPointerDown={(e) => {
              if (e.pointerType !== 'touch') return;
              e.preventDefault();
              setOpen((wasOpen) => !wasOpen);
            }}
            onClick={(e) => e.stopPropagation()}
            className={`inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded tabular-nums text-left
                        focus-visible:outline-none focus-visible:ring-2
                        focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-1
                        ${tone} ${className ?? ''}`}
          >
            <span className="sr-only">{label}</span>
            {/* A coin, not a word.
                The chip used to end with the PERIOD ("1,234 total", "1,234 this
                month"), which is the one thing about the figure a reader does
                not need at a glance and the longest thing in it: in a card's
                meta row, next to a date and a run count, it was what pushed the
                line onto a second row. The period is stated in a full sentence
                in the popover, where there is room for it to mean something.
                The glyph says what the number IS, in one 12px box, and stays
                the same width in every locale.
                `whitespace-nowrap` + `shrink-0` above are the other half of
                that: the figure is short now, and it must never be the thing
                that wraps. */}
            {showIcon && <Coins className="h-3 w-3 shrink-0 opacity-70" aria-hidden="true" />}
            <span aria-hidden="true">{formatCostCompact(spentValue)}</span>
            {hasCap && (
              <>
                <span aria-hidden="true">/</span>
                <span aria-hidden="true">{formatCostCompact(cap as number)}</span>
              </>
            )}
          </button>
        </TooltipTrigger>
        {/* The "Add node" palette card's own wrapper, class for class
            (`px-3 py-2.5` over the shared Tooltip base), instead of the wider,
            rounder, translucent card this used to draw. Two hover cards that
            explain a thing should not be two different objects: the palette
            card and the run panel's step card already agree, and this was the
            outlier. The body sets its own 260-320px width, as they do. */}
        <TooltipContent side="bottom" align="start" collisionPadding={8} className="px-3 py-2.5">
          <BudgetPopoverContent
            spent={spent}
            cap={cap}
            periodMode={periodMode}
            fallbackPeriod={fallbackPeriod}
            resetsAt={resetsAt}
            spendIsCurrent={spendIsCurrent}
            blocked={blocked}
          />
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

export default BudgetChip;

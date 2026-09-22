'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { formatCost, isCeMode } from '@/lib/format-cost';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import {
  budgetCapNeverResets,
  budgetPeriodLabelKey,
  budgetPeriodLabelName,
  resolveBudgetPeriod,
  type BudgetPeriodMode,
} from './budgetPeriod';

export interface BudgetPopoverContentProps {
  spent?: number | null;
  cap?: number | null;
  periodMode?: string | null;
  fallbackPeriod?: BudgetPeriodMode;
  /**
   * When the allowance starts again, computed server-side. ABSENT means "we do
   * not know", never "never" - the reset paragraph below explains why that
   * distinction had to be made load-bearing.
   */
  resetsAt?: string | null;
  /**
   * Is the spend figure a LIVE reading, or possibly a stale one?
   *
   * <p>A workflow's period counter is rolled over server-side on every read, so
   * its figure is always current. An agent's is reset lazily, by the resolver
   * that enforces its budget, and that only runs when the agent EXECUTES - so an
   * agent that hit its cap in September and has not run since still reads at the
   * cap today, and its next run will reset it and proceed.
   *
   * <p>Only a current figure may be used to assert that something is STOPPED.
   * Saying "no new run starts" about a stale one tells an owner their automation
   * is dead when it is not.
   */
  spendIsCurrent?: boolean;
  /**
   * The server's own verdict: is this cap refusing runs right now?
   *
   * <p>Supersedes the `over && spendIsCurrent` guess when supplied, and it is supplied
   * wherever the server can answer. The guess exists because a spend figure alone cannot
   * decide it for an agent, whose counter is reset lazily; the verdict is resolved with
   * that pending reset applied, so it can say STOPPED where the figure may not.
   */
  blocked?: boolean;
}

/**
 * The detail behind a spending figure: what was spent, against what, over which
 * period, when it starts again, and what is NOT counted.
 *
 * <p>It replaces a native {@code title}, which could only ever be one grey line.
 * That was the wrong shape: the figure is a number with three pieces of context
 * attached, and the most important of them, "your automation stops until
 * October", cannot be said in a fragment.
 *
 * <p>Body only. The hover behaviour, the delay, the portal, the placement and
 * the ARIA relationship all come from Radix through
 * {@code components/ui/tooltip}; see {@code BudgetChip} for why none of that is
 * hand-rolled here.
 *
 * <p>Visual parity with the "Add node" palette card
 * ({@code PaletteItemTooltipContent}), which is the shape this repo already
 * uses for "hover a thing, read what it is": a bordered header carrying a name
 * and a right-aligned badge, then separated label/value rows, all at
 * {@code text-xs} in a 260-320px box. This card used to be its own object, a
 * wider translucent {@code rounded-[24px]} panel; two hover cards explaining a
 * thing should not be two different objects.
 */
export function BudgetPopoverContent({
  spent,
  cap,
  periodMode,
  fallbackPeriod = 'monthly',
  resetsAt,
  spendIsCurrent = true,
  blocked,
}: BudgetPopoverContentProps) {
  const t = useTranslations();

  const hasCap = cap != null && cap > 0;
  const spentValue = spent ?? 0;
  const periodLabel = t(budgetPeriodLabelKey(periodMode, fallbackPeriod));
  const ratio = hasCap ? Math.min(1, spentValue / (cap as number)) : 0;
  const over = hasCap && spentValue >= (cap as number);
  // RESOLVED, not raw. Read straight off `periodMode` this disagreed with the
  // badge two lines below on any stored mode nobody implements: the badge fell
  // back and said "total", this did not and offered a reset date beside it.
  const neverResets = budgetCapNeverResets(resolveBudgetPeriod(periodMode, fallbackPeriod));

  const barTone = over
    ? 'bg-red-500 dark:bg-red-400'
    : ratio >= 0.8
      ? 'bg-amber-500 dark:bg-amber-400'
      : 'bg-slate-400 dark:bg-slate-500';

  /**
   * A figure with its unit spelled out, which is the whole point of the detail
   * view: the chip outside now says "1,234" beside a coin, and this is where a
   * reader finds out that the coin means CREDITS.
   *
   * <p>Not in CE, where `formatCost` already renders "$1.23" and appending
   * "dollars" would name the unit twice.
   */
  const amount = (value: number) =>
    isCeMode
      ? formatCost(value, 2)
      : t('budget.amountWithUnit', { amount: formatCost(value, 2), unit: t('budget.unitCredits') });

  return (
    <div
      data-testid="budget-popover"
      className="flex flex-col gap-2 text-xs min-w-[260px] max-w-[320px]"
    >
      {/* Header: what this is, and the period it covers as the right-hand badge.
          Styled as a heading, not marked up as one: Radix flattens this body
          into a visually-hidden description node, so an <h3> would put a second
          heading in the document outline for a transient tooltip.

          The PERIOD lives here now rather than glued to the figure on the chip.
          "1,234 total" spent a card's scarcest resource, its line width, on the
          least surprising part of the figure; in a labelled badge the same word
          answers a question the reader is actually asking by the time they have
          opened this. */}
      <div className="flex items-start justify-between gap-3 border-b border-gray-100 dark:border-gray-700 pb-1.5">
        <span className="font-semibold text-gray-900 dark:text-gray-100">
          {t('budget.popoverTitle')}
        </span>
        {/* Carries the RESOLVED period as data as well as translated text, so a
            test can tell "total" the period from "total" the word without
            depending on a locale. Resolved, not raw: the label falls back on a
            cadence nobody defined, so reading `periodMode` here would annotate
            a badge saying "total" with `data-budget-period="quarterly"` - wrong
            in exactly the case a test would need it. */}
        <span
          data-budget-period={budgetPeriodLabelName(periodMode, fallbackPeriod)}
          className="font-medium shrink-0 text-gray-500 dark:text-gray-400"
        >
          {periodLabel}
        </span>
      </div>

      {/* The two figures, as label/value rows - the same shape the palette card
          uses for its own details, so the number sits in a value column instead
          of being a sentence the reader has to parse. */}
      <div className="flex items-center justify-between gap-3">
        <span className="text-gray-500 dark:text-gray-400">{t('budget.popoverSpent')}</span>
        <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
          {amount(spentValue)}
        </span>
      </div>

      <div className="flex items-center justify-between gap-3">
        <span className="text-gray-500 dark:text-gray-400">{t('budget.popoverCap')}</span>
        {hasCap ? (
          <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
            {amount(cap as number)}
          </span>
        ) : (
          /* Said as a value, not as a clause appended to the spend. An uncapped
             figure is not "1,234, no cap set"; it is a spend, and a cap that is
             not there. */
          <span className="text-gray-500 dark:text-gray-400">{t('budget.popoverNoCap')}</span>
        )}
      </div>

      {hasCap && (
        <div
          className="h-1.5 w-full overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700"
          role="presentation"
        >
          <div className={`h-full rounded-full ${barTone}`} style={{ width: `${Math.round(ratio * 100)}%` }} />
        </div>
      )}

      {/* No "Counted this month." sentence any more: the badge above already
          says the period, and repeating it in prose was the card telling the
          reader something they had just read. What survives is what the badge
          CANNOT say: when the allowance comes back, and whether anything is
          currently stopped.

          Two gates on the reset line, and the second one is the interesting one.
          hasCap, because ungated an uncapped figure reads "no cap ... this cap
          never resets": two contradictory statements in one card.
          A DATE, because we only promise one the server actually sent. Falling
          back to "starts again every Monday" is tempting and FALSE: that is the
          WORKFLOW rule (ISO weeks, UTC). An agent's budget is a different
          subsystem whose weekly reset is a rolling seven days from its own last
          reset, on whatever weekday that fell. Telling an owner blocked on a
          Saturday to wait for Monday, when it resumes on Thursday, is worse than
          telling them nothing - so when we do not know, the badge says WHAT the
          period is and we never say when it ends. */}
      {hasCap && (neverResets || resetsAt) && (
        <p className="border-t border-gray-100 dark:border-gray-700 pt-1.5 text-gray-600 dark:text-gray-300 leading-relaxed">
          {neverResets
            ? t('budget.popoverNeverResets')
            : t('budget.popoverResetsOn', { date: formatUtcDate(resetsAt) })}
        </p>
      )}

      {/* The server's verdict when there is one, the figure-based guess otherwise -
          and the guess stays silent on a figure that may be stale, because telling an
          owner their automation is dead when it is not is the worse error. */}
      {(blocked ?? (over && spendIsCurrent)) && (
        <p className="font-medium text-red-600 dark:text-red-400 leading-relaxed">
          {neverResets ? t('budget.popoverStoppedForGood') : t('budget.popoverStopped')}
        </p>
      )}

      {/* Said here rather than nowhere: the cap counts agent spend, and someone
          reading a figure has a right to know what it leaves out before they
          trust it as their bill. */}
      <p className="border-t border-gray-100 dark:border-gray-700 pt-1.5 text-gray-500 dark:text-gray-400 leading-relaxed">
        {t('budget.popoverScope', {
          unit: t(isCeMode ? 'budget.unitDollars' : 'budget.unitCredits'),
        })}
      </p>
    </div>
  );
}

export default BudgetPopoverContent;

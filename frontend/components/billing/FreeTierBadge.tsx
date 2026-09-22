'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { useFreeAiCreditsAnswer } from '@/lib/hooks/useFreeAiCredits';
import { cn } from '@/lib/utils';

/**
 * The other half of {@link UpgradeRequiredBadge}: which model this account can
 * run without paying anything.
 *
 * <p><b>Why it exists.</b> The Free plan carries a monthly AI allowance that
 * funds chat and agent turns, but only on the models a cloud admin opened to the
 * free tier (V490/V494). Until now the reader was only told the negative - a
 * lock on the models the allowance does not cover - which reads as "everything
 * is locked" on a menu whose first rows are precisely the ones that are not. A
 * fresh signup arrives on a covered model (`usePreferFreeTierModel` primes it)
 * and has no way to know that the one it is already on costs nothing.
 *
 * <p><b>The verdict is the caller's, and must be
 * {@code useMonthlyCreditsCannotPay.freeTierForModel}</b>, which weighs the
 * allowance BALANCE as well as the plan and the model's flag. That is what keeps
 * this chip and the lock from landing on one row, and the reasoning lives with
 * the verdict rather than being restated on every surface.
 *
 * <p><b>A label, never a control.</b> It sits inside Radix `Select` options on
 * the picker surfaces, where ARIA gives an option's children no role of their
 * own, the listbox owns the arrow keys, and the selected option is re-rendered
 * inside the trigger `<button>` - where a `<span>` is the only valid shape. So
 * spans only: it carries the pointer handlers Radix needs for its tooltip and
 * nothing of its own.
 *
 * <p><b>Three audiences, and a hover only reaches one of them.</b> A pointer
 * gets the full sentence in the tooltip, with the allowance figure where a caller
 * has one. A screen reader gets the few words in the hidden label, because
 * nothing here can take focus and so the tooltip never opens for it. A touch
 * reader gets the sentence from the ⓘ card, which opens on tap: in the composer
 * menu that card sits on every row (shown unconditionally on a coarse pointer),
 * and on the pickers it sits beside the chosen model, so there the sentence
 * follows the selection rather than the row. Those are three different strings by
 * design, narrowest first; only the card has width for the whole thing. A
 * keyboard-only reader gets the hidden label and no more: the tooltip needs a
 * trigger that can take focus, which neither host allows.
 *
 * <p><b>It appears silently, and deliberately.</b> The balance lands after the
 * first paint, so the chip grows into the composer on a later render. Its sibling
 * notice carries {@code role="status"} for that exact reason, but the notice is a
 * paragraph under a list, whereas this sits INSIDE the composer's trigger button:
 * a live region there re-announces the button, and the news is "this costs you
 * nothing", which nobody is waiting on. The lock badge next door is silent too,
 * for the same reason.
 *
 * <p><b>One more thing a caller should know:</b> the welcome view renders the
 * composer twice (a visible copy and a `display:none` mobile one, see
 * {@code ModelSelectorDropdown}'s header), so on that route the badge's test id
 * exists twice in the DOM. A test that drives it there must select all and take
 * the visible one.
 */

export interface FreeTierBadgeProps {
  /**
   * True when the reader's free-tier allowance pays for this model right now,
   * asked once by the caller for its whole list. Taken as a prop rather than
   * resolved here so a presentational row stays renderable without a query
   * client behind it - the same contract the sibling badge keeps.
   */
  covered: boolean;
  /**
   * The monthly allowance to quote in the tooltip, when the caller has it.
   *
   * <p>Omitted by the list rows on purpose: the figure comes from the live FREE
   * plan row (`useFreeAiCredits`), and asking for it per row would put a query
   * observer behind every option in the menu to re-read the same cached answer.
   * The composer's trigger renders ONE badge, so it quotes the number; the rows
   * say the same thing without it.
   */
  credits?: number;
  className?: string;
}

/**
 * The verdict is checked by a component that calls NO hook, so a surface where
 * nothing is covered mounts nothing at all: no translation lookup, and no
 * requirement that a picker used in a bare test be wrapped in an intl provider
 * to render a badge it was never going to show.
 */
export function FreeTierBadge({ covered, ...rest }: FreeTierBadgeProps) {
  if (!covered) return null;
  return <FreeTierBadgeBody {...rest} />;
}

function FreeTierBadgeBody({ credits, className }: Omit<FreeTierBadgeProps, 'covered'>) {
  const t = useTranslations('billing.freeTier');
  const tooltip = credits === undefined ? t('tooltip') : t('tooltipWithCredits', { credits });

  return (
    <TooltipProvider delayDuration={150}>
      <Tooltip>
        <TooltipTrigger asChild>
          {/* A span rather than the shared Badge, which renders a <div>: this sits
              inside a Radix Select trigger button on the picker surfaces. The row
              around it is a <div> already, so this is about not making that worse,
              not a constraint the row itself honours. Sky and `rounded-md` is
              exactly the admin's own free-tier chip in the Models panel: one
              concept, one shape, one colour, whichever side of the product is
              looking at it. A capsule would also be the one round thing on a row of
              square badges, which `components/ui/badge.tsx` calls out by name. */}
          <span
            data-testid="free-tier-badge"
            className={cn(
              'inline-flex items-center rounded-md border px-1.5 py-0 text-[10px] font-medium leading-tight',
              'border-sky-300 bg-sky-50 text-sky-700 dark:border-sky-700 dark:bg-sky-900/20 dark:text-sky-400',
              className,
            )}
          >
            {t('label')}
            {/* Short on purpose. Nothing here can take focus (a Radix option, or
                the inside of a button), so the tooltip is pointer-only and a
                screen reader needs its own path - but the full sentence here
                would join the enclosing BUTTON's accessible name on two
                surfaces, and be read out again on every covered row of an open
                menu. So this carries what the lock beside it carries: a few
                words, as part of the row's own name. */}
            <span className="sr-only">{` ${t('srLabel')}`}</span>
          </span>
        </TooltipTrigger>
        {/* No z-index of its own: `TooltipContent` already defaults to z-[100002],
            above the composer menu, the Options popover and the pickers'
            SelectContent, precisely so a surface like this one does not have to
            rediscover the problem. */}
        <TooltipContent>
          <div className="max-w-[15rem] text-xs">{tooltip}</div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

/**
 * The composer's own badge: the same chip, quoting the allowance.
 *
 * <p>ONE instance per composer, which is what makes the figure affordable here
 * and not on a row - see {@link FreeTierBadgeProps.credits}. It always renders
 * as covered, because the composer only mounts the node when the model in hand
 * is: `ModelSelectorDropdown` owns that decision (it is the one holding the
 * catalogue and the verdict), so a caller hands this down unconditionally and
 * the hook below runs only on the accounts the badge is for.
 */
export function ComposerFreeTierBadge({ className }: { className?: string }) {
  const answer = useFreeAiCreditsAnswer();
  return <FreeTierBadge covered credits={quotableCredits(answer)} className={className} />;
}

/**
 * The allowance figure if it is worth quoting, or nothing.
 *
 * <p>Quoted only once the live plan row has answered: unresolved means either a
 * request in flight or one that has exhausted its retries, and both hand back the
 * seeded stand-in, a figure this account's plan may never grant. Quoting it after
 * a failed fetch would leave a wrong number on screen for good, since nothing
 * revisits it.
 *
 * <p>The zero is belt and braces rather than a state to expect: a plan row that
 * grants nothing leaves the allowance empty, so the verdict upstream never marks
 * a model free in the first place and this component is not mounted. If it ever
 * is, saying "0 credits a month" beside the word Free would be the worst of both.
 */
export function quotableCredits({ credits, resolved }: { credits: number, resolved: boolean }): number | undefined {
  return resolved && credits > 0 ? credits : undefined;
}

export default FreeTierBadge;

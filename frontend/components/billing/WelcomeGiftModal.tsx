'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Gift, Coins, ArrowRight } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { useMonthlyCreditsCannotPay } from '@/lib/hooks/useMonthlyCreditsCannotPay';
import { FREE_MONTHLY_CREDITS } from '@/lib/billing/credit-allowance';
import {
  isWelcomeGiftPending,
  notifyWelcomeGiftDone,
} from '@/lib/onboarding/welcomeGiftHandoff';
import { track } from '@/lib/analytics/analytics';

/**
 * What a brand-new account reads first: the monthly credits it already has.
 *
 * <p>Shown once, right after onboarding, on the Free plan only. It states the one
 * monthly pool and what it pays for (workflows, plus chat and agent turns on the
 * models marked Free), because that is what the account is about to spend.
 *
 * <p><b>Why not the plan comparison.</b> That table is a five-column
 * Free-to-Enterprise matrix, i.e. a screen for CHOOSING a plan. A reader who
 * just signed up is not choosing one; they are finding out what the plan they
 * already have grants. The comparison stays where choosing is the task (the
 * pricing page and the landing) and is not an entry point here.
 *
 * <p><b>It states a GRANT, not a balance.</b> The figure is what the plan
 * hands out every month. An earlier version of this modal keyed on the wallet
 * BALANCE and hid itself whenever that balance read zero or failed to load, so
 * the one screen explaining the free credits went missing exactly when it was
 * most needed. A grant does not have that failure mode.
 *
 * <p><b>Every path resolves the hand-off.</b> The suggested-applications modal
 * queues behind {@link notifyWelcomeGiftDone}, so deciding not to open still
 * has to release it - a paid account, a self-hosted install, and the bounded
 * wait below. Skipping the release would turn one skipped modal into two.
 */

/**
 * How long the plan verdict is waited for before the gift is given up on.
 *
 * <p>It is not guaranteed to arrive: a query that exhausts its retries settles
 * into "not loading, no data" and stays there. Something ELSE is queued behind
 * this modal, so an unbounded wait is not a missing nicety, it is a second modal
 * that never opens. Past this the gift is skipped and the queue is released:
 * showing nothing is recoverable, stranding the next modal is not.
 *
 * <p><b>It is a BACKSTOP, not a race, and the number has to be read that way.</b>
 * The clock starts when this mounts, which onboarding reaches through a full page
 * load - so bootstrap, hydration and the auth handshake are all inside the
 * budget, and the balance query has not been ENABLED yet when it starts. It then
 * retries twice on react-query's exponential backoff. A budget under about ten seconds would
 * therefore lose a race against one transient failure, and lose it in silence,
 * for exactly the brand-new account this exists for. The only cost of a generous
 * one is that the suggested-applications modal stays parked longer in a case
 * where it was going to be the reader's second screen anyway.
 */
export const ANSWER_TIMEOUT_MS = 20000;

/**
 * @see the module docblock above for why this screen exists and what it waits on.
 */
export default function WelcomeGiftModal() {
  const t = useTranslations('modals.welcomeGift');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  // The plan rule, from the one place that owns it: true for a CLOUD account on
  // the Free plan's scoped monthly grant, which is exactly the account
  // this gift is addressed to. `verdictReady` means the payload ARRIVED, not
  // merely "not loading" - react-query reports isLoading=false for a query
  // disabled for want of a session, and the flag then reads as its own default,
  // which is indistinguishable from a paid account.
  const { prefersFreeTierModels: isFreePlanAccount, verdictReady } = useMonthlyCreditsCannotPay();

  const [pending, setPending] = useState(false);
  const [open, setOpen] = useState(false);
  const settled = useRef(false);

  const settle = useCallback((shouldOpen: boolean) => {
    if (settled.current) return;
    settled.current = true;
    setPending(false);
    if (shouldOpen) {
      // Reported here, not in a render or an effect: `settled` makes this run at most once per
      // mount, StrictMode included. Only a Free-plan account is ever shown the gift, so the
      // flag reads true today; it is sent so the event keeps its meaning if that rule widens.
      track('welcome_plan_shown', { is_free_plan: true });
      setOpen(true);
      return;
    }
    notifyWelcomeGiftDone();
  }, []);

  // Arm on the flag onboarding wrote. Mounted in the app layout, so this runs
  // on every page: without the flag it must do nothing at all.
  //
  // It only ever sets state here, and that is load-bearing rather than a style
  // choice: SuggestedAppsModal subscribes to the release event in ITS mount
  // effect, and this component is mounted BEFORE it. Settling inside this effect
  // would dispatch the release before the waiter had subscribed, and the
  // suggestions would never open. Deciding on a later render pass is what keeps
  // the two independent of mount order.
  useEffect(() => {
    if (!isWelcomeGiftPending()) return;
    setPending(true);
    const timer = window.setTimeout(() => settle(false), ANSWER_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [settle]);

  // Decide once the answer is in. Self-hosted needs no branch of its own: the
  // verdict is answered from the start there and reads "not a free-tier
  // account", which is the right outcome - a CE install bills against its linked
  // cloud account, and an unlinked one has no plan to state.
  useEffect(() => {
    if (!pending || !verdictReady) return;
    settle(isFreePlanAccount);
  }, [pending, verdictReady, isFreePlanAccount, settle]);

  const close = useCallback(() => {
    track('welcome_plan_dismissed', { is_free_plan: isFreePlanAccount });
    setOpen(false);
    notifyWelcomeGiftDone();
  }, [isFreePlanAccount]);

  if (!open) return null;

  const credits = FREE_MONTHLY_CREDITS.toLocaleString(locale);

  return (
    <Dialog open={open} onOpenChange={(next) => !next && close()}>
      <DialogContent
        className="max-w-md gap-0 overflow-hidden border-theme bg-theme-primary p-0"
        closeLabel={tCommon('close')}
        data-testid="welcome-gift-modal"
      >
        <div className="border-b border-theme p-6 pb-5 pr-14">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-theme-tertiary">
              <Gift className="h-5 w-5 text-theme-primary" />
            </div>
            <div className="min-w-0">
              <DialogTitle className="text-lg font-semibold leading-6 text-theme-primary">
                {t('title')}
              </DialogTitle>
              <DialogDescription className="mt-1 text-sm leading-5 text-theme-secondary">
                {t('subtitle')}
              </DialogDescription>
            </div>
          </div>
        </div>

        <div className="space-y-3 p-6">
          <GiftRow
            icon={<Coins className="h-3.5 w-3.5 text-theme-secondary" />}
            label={t('features.freeCredits')}
            detail={t('features.freeCreditsDetail')}
            amount={credits}
            testId="welcome-gift-credits"
          />
          <p className="pt-1 text-sm leading-6 text-theme-secondary">
            {t('renewal')}
          </p>

          <Button onClick={close} variant="default" className="w-full">
            {t('cta')}
            <ArrowRight className="h-3.5 w-3.5" />
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function GiftRow({
  icon,
  label,
  detail,
  amount,
  testId,
}: {
  icon: React.ReactNode;
  label: string;
  detail: string;
  amount: string;
  testId: string;
}) {
  return (
    <div className="rounded-lg border border-theme bg-theme-secondary p-4" data-testid={testId}>
      <div className="flex items-start justify-between gap-4">
        <div className="flex min-w-0 items-start gap-3">
          <div className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-theme-tertiary">
            {icon}
          </div>
          <div className="min-w-0">
            <p className="text-sm font-medium text-theme-primary">{label}</p>
            <p className="mt-0.5 text-sm leading-5 text-theme-secondary">{detail}</p>
          </div>
        </div>
        <span className="text-2xl font-semibold tabular-nums text-theme-primary">{amount}</span>
      </div>
    </div>
  );
}

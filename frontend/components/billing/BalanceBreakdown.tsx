'use client';

/**
 * V250 - Wallet breakdown display utilities.
 *
 * Two surfaces, one source of truth for the formatting:
 *
 * - {@link BalanceBreakdownTooltip} wraps any trigger (the existing sidebar
 *   coin badge, an inline link, etc.) and surfaces the sub vs PAYG breakdown
 *   on hover. Renders nothing extra when only one bucket is populated - the
 *   tooltip would just say the same number twice.
 *
 * - {@link BalanceBreakdownCard} is the wallet card on the Quota & Usage page
 *   (and any future wallet page). Shows the total, the share of the plan it
 *   represents, both buckets stacked, and a "Top up" CTA.
 *
 * Style matches the existing billing modals (BillingCycleChangeModal,
 * InsufficientCreditsModal): `text-sm` default, lucide icons at `h-3.5
 * w-3.5`, gray surface tokens, theme-aware dark mode.
 */

import React from 'react';
import { Coins, Info, Plus } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { formatUtcDateOrNull } from '@/lib/utils/dateFormatters';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { formatCreditsCompact } from '@/lib/format-cost';
import { computeCreditGauge } from '@/lib/billing/credit-allowance';

interface BalanceBreakdownProps {
  /** Total balance (sub + payg). null while loading or when no subscription. */
  balance: number | null;
  /** Subscription bucket. null when V250 endpoint not yet hit. */
  subBalance: number | null;
  /** PAYG bucket. null when V250 endpoint not yet hit. */
  paygBalance: number | null;
  /**
   * V494 - the monthly AI allowance, a THIRD bucket that is deliberately not part
   * of {@code balance}: it can only pay for agent and chat turns on the models
   * opened to the free tier. Rendered as its own row so a Free account can see
   * what it has left; null or zero on every plan without one, which is every paid
   * plan, and the row is then omitted entirely.
   */
  aiBalance?: number | null;
  /**
   * V494 - whether the plan HAS an allowance, independent of what is left of it. A
   * spent pot and a plan with no pot both read zero, and they are not the same thing
   * to a reader whose chat has just stopped working.
   */
  hasAiAllowance?: boolean;
}

/** Optional monthly-cycle counter rendered when the user is on a paid plan. */
export interface MonthlyPlanInfo {
  /** Total credits granted at the start of each billing cycle (e.g. CREDIT_TIERS[creditTierIndex]). */
  allowance: number;
  /**
   * When those credits next land, as the ISO string `/billing/me` carries, or null/absent when
   * no date can be named (a cancelled or past-due subscription is owed no further grant).
   *
   * <p>It comes from the SAME resolution as `allowance` (see `useCreditWallet`), because the
   * card states the two in one sentence and a date belonging to another account beside an
   * amount belonging to this one would be worse than saying nothing.
   */
  renewsAt?: string | null;
  /**
   * When the card is next charged, used ONLY to explain why the credit date is not it. When
   * the two differ the subscription is billed annually and its credit pack arrives monthly,
   * which is the single fact a yearly subscriber could not get anywhere in the product.
   */
  periodEndsAt?: string | null;
  /**
   * True when a plan or credit-tier change is already scheduled for the end of this period,
   * in which case the renewal line is not drawn at all.
   *
   * <p>{@link allowance} is the tier in force TODAY, and a scheduled tier change means a
   * different amount lands on the date this card would name: a PRO wallet on tier 4 with a
   * downgrade to tier 1 pending would read "+100,000 credits on 14 Oct" and receive 10,000.
   * The date is right and the number is wrong, which is worse than saying nothing, and the
   * Billing page stands down under the same condition. Nothing here can price the target
   * tier, so nothing here should try.
   */
  hasScheduledChange?: boolean;
}

/**
 * Wrap a balance display (icon + number) with a hover tooltip that breaks
 * the total down into sub vs PAYG. Renders the children as-is and adds the
 * tooltip only when BOTH buckets are known + at least one is non-zero (no
 * point splitting "0 / 0" or showing the same number twice).
 */
export function BalanceBreakdownTooltip({
  children,
  subBalance,
  paygBalance,
}: {
  children: React.ReactNode;
  subBalance: number | null;
  paygBalance: number | null;
}) {
  const t = useTranslations('billing.payg');
  const locale = useLocale();

  // V494: no AI-allowance row here, unlike the card below. This tooltip's only
  // mounts are the sidebar's CE arm, which is dead (its caller passes a null
  // balance in CE, so the guard never opens - see AppSidebar), and wiring a third
  // bucket through a path that cannot render would be plumbing nobody can see.
  // The wallet card on the quota page is where the allowance is shown.
  const hasBreakdown =
    subBalance !== null &&
    paygBalance !== null &&
    (subBalance > 0 || paygBalance > 0);

  if (!hasBreakdown) return <>{children}</>;

  return (
    <TooltipProvider delayDuration={150}>
      <Tooltip>
        <TooltipTrigger asChild>
          <span>{children}</span>
        </TooltipTrigger>
        <TooltipContent side="top" className="text-sm">
          <div className="flex flex-col gap-1">
            <div className="flex items-center justify-between gap-3">
              <span className="text-gray-600 dark:text-gray-300">
                {t('breakdown.sub')}
              </span>
              <span className="font-medium text-gray-900 dark:text-white">
                {formatCreditsCompact(subBalance, locale)}
              </span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <span className="text-gray-600 dark:text-gray-300">
                {t('breakdown.payg')}
              </span>
              <span className="font-medium text-gray-900 dark:text-white">
                {formatCreditsCompact(paygBalance, locale)}
              </span>
            </div>
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

/**
 * Full breakdown card for the Quota & Usage page. Two gauges total:
 *   1. Subscription - `balance / cycleAllowance`. When carryover pushes the
 *      bucket above the cycle allowance the bar saturates at full while the
 *      figures keep saying "12.0K / 10.0K", so the extra from a previous cycle
 *      is visible in the numbers. No percentage of its own: the plan share is
 *      stated once, on the total, where the numerator matches the sidebar ring.
 *   2. PAYG top-up - persistent bucket, neutral white fill (matches dark UI
 *      contrast pattern via `bg-theme-primary`). No percentage: a bucket that
 *      renews against nothing has no denominator to state one against.
 *
 * The header (icon in circle + title + subtitle) matches the Information
 * page sections so the settings surfaces feel coherent. Theme tokens
 * everywhere (no raw gray-X00 / white pairs).
 */
export function BalanceBreakdownCard({
  balance,
  subBalance,
  paygBalance,
  aiBalance,
  hasAiAllowance = false,
  onTopUp,
  topUpEnabled = true,
  monthlyPlan,
}: BalanceBreakdownProps & {
  onTopUp?: () => void;
  /** When false (e.g. PAYG_PRICE_UNCONFIGURED), the button is disabled. */
  topUpEnabled?: boolean;
  /**
   * Plan-cycle counter, resolved by `useCreditWallet` and therefore present for
   * any CLOUD account with a knowable grant - a FREE one included, since its
   * monthly reset is a real allowance. It is absent in CE (which bills in
   * dollars against no grant) and for a guest reading the owner's wallet, where
   * our own tier would be the wrong denominator; the gauge then falls back to a
   * plain balance display.
   */
  monthlyPlan?: MonthlyPlanInfo;
}) {
  const t = useTranslations('billing.payg');
  const locale = useLocale();

  if (balance === null) return null;

  const total = balance;
  const subPart = subBalance ?? 0;
  const paygPart = paygBalance ?? 0;

  return (
    <div className="rounded-xl border border-theme p-6">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center">
            <Coins className="w-5 h-5 text-theme-primary" />
          </div>
          <div>
            <h2 className="text-lg font-semibold text-theme-primary">{t('walletTitle')}</h2>
            <p className="text-sm text-theme-secondary">{t('walletSubtitle')}</p>
          </div>
        </div>
        {onTopUp && (
          <Button
            onClick={onTopUp}
            disabled={!topUpEnabled}
            variant="default"
            size="sm"
            className="gap-1"
          >
            <Plus className="h-3.5 w-3.5" />
            {t('topUpCta')}
          </Button>
        )}
      </div>

      <div className="text-2xl font-semibold text-theme-primary mb-1">
        {formatCreditsCompact(total, locale)}
      </div>
      <div className="text-sm text-theme-secondary">
        {t('totalAvailable')}
      </div>
      <PlanShare balance={total} allowance={monthlyPlan?.allowance ?? null} />

      <div className="space-y-4 mt-5">
        <SubscriptionGauge
          balance={subPart}
          allowance={monthlyPlan?.allowance ?? 0}
          renewsAt={monthlyPlan?.hasScheduledChange ? null : monthlyPlan?.renewsAt ?? null}
          periodEndsAt={monthlyPlan?.periodEndsAt ?? null}
        />
        <PaygGauge balance={paygPart} />
        {/* V494: drawn for any account whose plan grants an allowance, INCLUDING one
            that has spent it to zero - that reader is the one who needs to see it. A
            paid wallet has no allowance and keeps exactly the two rows it had. */}
        {aiBalance != null && (aiBalance > 0 || hasAiAllowance) && (
          <AiAllowanceGauge balance={aiBalance} />
        )}
      </div>
    </div>
  );
}

/**
 * The Free plan's monthly AI allowance, as its own row.
 *
 * <p>Separate from the two wallet gauges on purpose: this pot is NOT part of the
 * headline balance and cannot pay for anything but agent and chat turns on the
 * models opened to the free tier, so showing it inside the balance would promise
 * spending power the wallet does not have. Same shape as {@link PaygGauge} (full
 * bar when funded) because it renews against a grant whose size the wallet card
 * does not know.
 */
function AiAllowanceGauge({ balance }: { balance: number }) {
  const t = useTranslations('billing.payg');
  const locale = useLocale();

  return (
    <div>
      <div className="flex items-center justify-between text-sm mb-1">
        <span className="text-theme-secondary">{t('breakdown.ai')}</span>
        <span className="font-medium text-theme-primary">
          {formatCreditsCompact(balance, locale)}
        </span>
      </div>
      <div className="h-1.5 rounded-full bg-theme-tertiary overflow-hidden">
        {/* Full while funded, empty once spent. No denominator: the card is not told
            what the pot refills to, and inventing one would put a number on screen
            that no endpoint answered for. */}
        <div
          className="h-full bg-sky-500 dark:bg-sky-400 transition-all"
          style={{ width: balance > 0 ? '100%' : '0%' }}
        />
      </div>
      <div className="text-xs text-theme-muted mt-1">
        {t('breakdown.aiHint')}
      </div>
    </div>
  );
}

/**
 * How much of the plan is still there, said in words, under the total it is
 * measured from.
 *
 * THIS IS THE SENTENCE THAT MOVED. It used to sit in the sidebar user menu,
 * under a bar repeating the ring drawn around the avatar three feet away. A
 * menu is a place people pass through; this card is what they open to read
 * their plan.
 *
 * It hangs off the TOTAL, and it has to. The number directly above it is the
 * whole wallet, and the whole wallet is exactly what the ring measures too
 * (`useCreditWallet` hands both surfaces `computeCreditGauge(balance,
 * allowance)`), so the two agree by construction rather than by a promise in a
 * comment. Attaching it to the Subscription bar below instead - which was the
 * first attempt - silently changed the numerator to that BUCKET: a wallet
 * holding 10,000 of grant plus a 5,000 top-up then showed a gold "+50% over
 * your plan" in the sidebar and "100% of your plan remaining" here, for the
 * same account, on the same screen.
 *
 * REMAINING, not consumed, because the figure it sits under is a remaining
 * count. A reader pairs a sentence with the number beside it, and "2% of your
 * plan used" under "9.8K credits available" reads as almost nothing left.
 *
 * The over-allowance case is stated here in FULL, in gold, and this is the only
 * place any of it is now written down for a sighted reader: carry-over and
 * top-ups can push a wallet above its cycle grant, the ring turns gold and
 * shows a bare "+", and this is what that "+" means. Uncapped, unlike any bar:
 * a bar cannot draw past its end, a sentence can say 3,400%.
 */
function PlanShare({ balance, allowance }: { balance: number; allowance: number | null }) {
  const t = useTranslations('billing.balance');
  const locale = useLocale();

  // No denominator, no claim. CE, an unknown plan, or a guest whose payer is
  // someone else: "0% remaining" would be a statement about an account we could
  // not read, which is the opposite of what we know.
  if (allowance === null || allowance <= 0) return null;

  const gauge = computeCreditGauge(balance, allowance);
  // Floored at 0: a delinquent wallet can go negative, and "-20% remaining" is
  // not a quantity anyone can hold.
  const remainingPct = Math.max(0, Math.round((balance / allowance) * 100));

  return (
    <div
      data-testid="wallet-plan-share"
      // An EXPLICIT colour rather than inheritance, the lesson the bar this
      // replaces left behind: the component is only as safe as the container it
      // lands in, and a container without one drew dark-on-dark. The gold
      // state's inline colour still wins over the class.
      className="mt-1 text-sm font-medium text-theme-secondary"
      style={gauge.isOver ? { color: 'var(--credit-gold-ink)' } : undefined}
    >
      {gauge.isOver
        ? gauge.overPct === 0
          // Genuinely over, but the rounded figure is 0. "+0% over your plan"
          // above a gold dial reads as a contradiction.
          ? t('overAllowanceTiny')
          : t('overAllowance', { percent: gauge.overPct.toLocaleString(locale) })
        : t('remainingPercent', { percent: remainingPct.toLocaleString(locale) })}
    </div>
  );
}

/**
 * Single subscription gauge. Bar fill = MIN(MAX(balance, 0), allowance) /
 * allowance, saturating at 100% when carry-over or top-ups push the bucket
 * above the monthly grant and flooring at 0 when a debit drives it negative.
 * The right-side label shows `balance / allowance` so the overflow is visible
 * in the number even when the bar is capped.
 *
 * Falls back to a plain balance display when no allowance is known: CE, or a
 * guest whose payer is someone else.
 *
 * DELIBERATELY UNLABELLED WITH A PERCENTAGE. The plan share is stated once on
 * this card, up on the total, because that is the figure the sidebar ring
 * measures too. This bar's numerator is the SUBSCRIPTION BUCKET, which is a
 * different number the moment a top-up exists: a wallet holding its whole
 * 10,000 grant plus 5,000 of PAYG is "+50% over your plan" and "100% of the
 * grant bucket" at the same time. Both true, and putting them on one card as
 * two "% of your plan" sentences is how a reader concludes the page is broken.
 */
function SubscriptionGauge({
  balance,
  allowance,
  renewsAt,
  periodEndsAt,
}: {
  balance: number;
  allowance: number;
  renewsAt?: string | null;
  periodEndsAt?: string | null;
}) {
  const t = useTranslations('billing.payg');
  const locale = useLocale();
  const hasAllowance = allowance > 0;
  // With an allowance: gauge against it (capped at 100%). Without one, fall
  // back to "funded vs empty" so the bar still renders and the row stays
  // visually balanced with the PAYG gauge below.
  //
  // "Without one" is CE, or a guest whose payer is someone else. It is NOT
  // FREE: a FREE cloud account has a real 1,000-credit monthly reset and now
  // gets a real denominator here, which is the whole point of routing this
  // page through useCreditWallet.
  // Clamped at BOTH ends. Only the high end used to be held, and a debit can
  // drive a bucket negative (`CreditService.applyDebit` takes the whole cost
  // from PAYG when the plan's monthly grant is workflow-only), which yields
  // `width: "-20%"` - an invalid declaration, so the bar silently disappears
  // rather than reading empty. Giving FREE accounts a denominator here widened
  // that exposure, and FREE is exactly the workflow-only plan.
  const fillPct = hasAllowance
    ? Math.max(0, Math.min(100, Math.round((balance / allowance) * 100)))
    : balance > 0
      ? 100
      : 0;

  return (
    <div>
      <div className="flex items-center justify-between text-sm mb-1">
        <span className="text-theme-secondary">{t('breakdown.sub')}</span>
        <span className="font-medium text-theme-primary">
          {formatCreditsCompact(balance, locale)}
          {hasAllowance && (
            <span className="text-theme-muted"> / {formatCreditsCompact(allowance, locale)}</span>
          )}
        </span>
      </div>
      <div className="h-1.5 rounded-full bg-theme-tertiary overflow-hidden">
        <div
          data-testid="subscription-gauge-fill"
          className="h-full bg-gray-900 dark:bg-white transition-all"
          style={{ width: `${fillPct}%` }}
        />
      </div>
      {/* Both halves or neither: an amount with no date is the state this card was already
          in, and a date with no amount answers half the question. */}
      {renewsAt && hasAllowance && (
        <RenewalLine renewsAt={renewsAt} allowance={allowance} periodEndsAt={periodEndsAt} />
      )}
    </div>
  );
}

/**
 * When the subscription credits come back, and how many.
 *
 * <p>THE MISSING SENTENCE. The card could say how much of the plan was left and nothing at
 * all about when it refills, so the one question a wallet cannot answer from its own balance
 * had no answer anywhere in the product. It is worse than it sounds on a YEARLY plan: the
 * Billing page's "next billing" is eleven months away from the next credit grant for most of
 * the year, so the only date on screen was the wrong one.
 *
 * <p>The date is served by the backend ({@code CreditAttributionService.nextCreditGrantAt},
 * which sits beside the method that decides the grant is owed) and never derived here. This
 * component formats it and says nothing the payload did not.
 *
 * <p>The rules behind the date live in the "i" rather than in the line, on purpose: the line
 * has to be readable at a glance by everyone, while "your unused credits are not carried
 * over" is read once and remembered. It is in the popover and not omitted because it is the
 * other half of the same question - a reader who believes the balance accumulates is
 * budgeting against a number that will not exist next month.
 */
function RenewalLine({
  renewsAt,
  allowance,
  periodEndsAt,
}: {
  renewsAt: string;
  allowance: number;
  periodEndsAt?: string | null;
}) {
  const t = useTranslations('billing.payg.renewal');
  const locale = useLocale();

  // An unparseable date is not worth a sentence: the backend sends an ISO instant or null, so
  // this is the same posture as the null case. formatUtcDate cannot express "render nothing"
  // (its fallback is read as `|| '-'`, so an empty string yields a literal "-", which put
  // "+10,000 credits on -" on screen), which is why the -OrNull variant exists.
  const date = formatUtcDateOrNull(renewsAt, { locale });
  if (!date) return null;

  // EXACT, where the gauge one row above renders the same number compact ("10.0K / 10.0K").
  // Deliberate, and the two are not interchangeable: the gauge is a proportion, read at a
  // glance, where a rounding costs nothing; this is a promise about a quantity somebody will
  // plan a month of work against, and "+10.0K credits" rounds an answer they asked for
  // precisely. Do not "tidy" these into one format without deciding which reading loses.
  const amount = allowance.toLocaleString(locale);
  // The two dates differ only when the credits arrive on the monthly cycle of a yearly
  // subscription, so this asks exactly what the sentence below explains. Comparing the raw
  // strings is the point: both come from the same row the backend computed the credit date
  // from, so a difference here is a real difference, and an equal pair needs no explanation.
  const invoiceDate =
    periodEndsAt && periodEndsAt !== renewsAt ? formatUtcDateOrNull(periodEndsAt, { locale }) : null;

  return (
    <div className="text-xs text-theme-muted mt-1 flex items-center gap-1">
      <span data-testid="subscription-renewal-line">{t('line', { amount, date })}</span>
      <Popover>
        <PopoverTrigger asChild>
          <button
            type="button"
            aria-label={t('explainLabel')}
            data-testid="subscription-renewal-info"
            className="shrink-0 text-theme-muted hover:text-theme-primary transition-colors"
          >
            <Info className="h-3 w-3" />
          </button>
        </PopoverTrigger>
        <PopoverContent
          side="top"
          align="start"
          sideOffset={6}
          className="w-[280px] p-3 bg-theme-primary rounded-xl border border-gray-300/70 dark:border-gray-600/70"
          data-testid="subscription-renewal-popover"
        >
          <p className="text-sm font-medium text-theme-primary mb-2">{t('title')}</p>
          <ul className="space-y-1.5 text-xs text-theme-secondary">
            <li>{t('grant', { amount, date })}</li>
            {/* Stated as a consequence, not as a warning: the balance is REPLACED, which is
                also why the gauge above can read full the day after it read empty. */}
            <li>{t('noCarryOver')}</li>
            <li>{t('paygKept')}</li>
            {/* Only when the two dates genuinely differ. On a monthly plan they are one event
                and this sentence would invent a distinction the reader does not have. */}
            {invoiceDate && (
              <li className="text-theme-primary">{t('yearlyBilling', { date: invoiceDate })}</li>
            )}
          </ul>
        </PopoverContent>
      </Popover>
    </div>
  );
}

/**
 * PAYG bucket - persistent across renewals, so there's no "allowance" to
 * gauge against. The bar is purely a visual presence of credits, neutral
 * white-on-dark (via `bg-theme-primary`) so it matches the subscription
 * gauge instead of competing with an emerald accent.
 */
function PaygGauge({ balance }: { balance: number }) {
  const t = useTranslations('billing.payg');
  const locale = useLocale();
  // Full bar when funded, empty when zero - keeps the row visually balanced
  // with the subscription gauge above whether or not the user has topped up.
  const fillPct = balance > 0 ? 100 : 0;

  return (
    <div>
      <div className="flex items-center justify-between text-sm mb-1">
        <span className="text-theme-secondary">{t('breakdown.payg')}</span>
        <span className="font-medium text-theme-primary">
          {formatCreditsCompact(balance, locale)}
        </span>
      </div>
      <div className="h-1.5 rounded-full bg-theme-tertiary overflow-hidden">
        <div
          className="h-full bg-gray-900 dark:bg-white transition-all"
          style={{ width: `${fillPct}%` }}
        />
      </div>
      <div className="text-xs text-theme-muted mt-1">
        {t('breakdown.paygHintPersists')}
      </div>
    </div>
  );
}

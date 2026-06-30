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
 * - {@link BalanceBreakdownCard} is the dedicated card for /settings (and
 *   any future wallet page). Shows both buckets stacked + a "Top up" CTA.
 *
 * Style matches the existing billing modals (BillingCycleChangeModal,
 * InsufficientCreditsModal): `text-sm` default, lucide icons at `h-3.5
 * w-3.5`, gray surface tokens, theme-aware dark mode.
 */

import React from 'react';
import { Coins, Plus } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { formatCreditsCompact } from '@/lib/format-cost';

interface BalanceBreakdownProps {
  /** Total balance (sub + payg). null while loading or when no subscription. */
  balance: number | null;
  /** Subscription bucket. null when V250 endpoint not yet hit. */
  subBalance: number | null;
  /** PAYG bucket. null when V250 endpoint not yet hit. */
  paygBalance: number | null;
}

/** Optional monthly-cycle counter rendered when the user is on a paid plan. */
export interface MonthlyPlanInfo {
  /** Total credits granted at the start of each billing cycle (e.g. CREDIT_TIERS[creditTierIndex]). */
  allowance: number;
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
                {formatCreditsCompact(subBalance)}
              </span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <span className="text-gray-600 dark:text-gray-300">
                {t('breakdown.payg')}
              </span>
              <span className="font-medium text-gray-900 dark:text-white">
                {formatCreditsCompact(paygBalance)}
              </span>
            </div>
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

/**
 * Full breakdown card for /settings/overview. Two gauges total:
 *   1. Subscription - `balance / cycleAllowance` with overflow handling. When
 *      carryover makes the balance exceed the cycle allowance (e.g. 12K on a
 *      10K plan), the bar saturates at 100% and an "+X over allowance" hint
 *      is shown so the user understands the extra came from a previous cycle.
 *   2. PAYG top-up - persistent bucket, neutral white fill (matches dark UI
 *      contrast pattern via `bg-theme-primary`).
 *
 * The header (icon in circle + title + subtitle) matches the Information
 * page sections so the settings surfaces feel coherent. Theme tokens
 * everywhere (no raw gray-X00 / white pairs).
 */
export function BalanceBreakdownCard({
  balance,
  subBalance,
  paygBalance,
  onTopUp,
  topUpEnabled = true,
  monthlyPlan,
}: BalanceBreakdownProps & {
  onTopUp?: () => void;
  /** When false (e.g. PAYG_PRICE_UNCONFIGURED), the button is disabled. */
  topUpEnabled?: boolean;
  /**
   * Plan-cycle counter. Provided only for paid subscribers; FREE / CE callers
   * omit it and the subscription gauge falls back to a plain balance display.
   */
  monthlyPlan?: MonthlyPlanInfo;
}) {
  const t = useTranslations('billing.payg');

  if (balance === null) return null;

  const total = balance;
  const subPart = subBalance ?? 0;
  const paygPart = paygBalance ?? 0;

  return (
    <div className="rounded-xl border border-theme p-6">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
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
            variant="contrast"
            size="sm"
            className="gap-1"
          >
            <Plus className="h-3.5 w-3.5" />
            {t('topUpCta')}
          </Button>
        )}
      </div>

      <div className="text-2xl font-semibold text-theme-primary mb-1">
        {formatCreditsCompact(total)}
      </div>
      <div className="text-sm text-theme-secondary mb-5">
        {t('totalAvailable')}
      </div>

      <div className="space-y-4">
        <SubscriptionGauge
          balance={subPart}
          allowance={monthlyPlan?.allowance ?? 0}
        />
        <PaygGauge balance={paygPart} />
      </div>
    </div>
  );
}

/**
 * Single subscription gauge. Bar fill = MIN(balance, allowance) / allowance,
 * saturating at 100% when carry-over or top-ups push the bucket above the
 * monthly grant. The right-side label shows `balance / allowance` so the
 * overflow is visible in the number even when the bar is capped.
 *
 * Falls back to a plain balance display when no allowance is known (FREE / CE).
 */
function SubscriptionGauge({
  balance,
  allowance,
}: {
  balance: number;
  allowance: number;
}) {
  const t = useTranslations('billing.payg');
  const hasAllowance = allowance > 0;
  // With an allowance: gauge against it (capped at 100%). Without one
  // (FREE / CE): fall back to "funded vs empty" so the bar still renders and
  // the row stays visually balanced with the PAYG gauge below.
  const fillPct = hasAllowance
    ? Math.min(100, Math.round((balance / allowance) * 100))
    : balance > 0
      ? 100
      : 0;

  return (
    <div>
      <div className="flex items-center justify-between text-sm mb-1">
        <span className="text-theme-secondary">{t('breakdown.sub')}</span>
        <span className="font-medium text-theme-primary">
          {formatCreditsCompact(balance)}
          {hasAllowance && (
            <span className="text-theme-muted"> / {formatCreditsCompact(allowance)}</span>
          )}
        </span>
      </div>
      <div className="h-1.5 rounded-full bg-theme-tertiary overflow-hidden">
        <div
          className="h-full bg-gray-900 dark:bg-white transition-all"
          style={{ width: `${fillPct}%` }}
        />
      </div>
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
  // Full bar when funded, empty when zero - keeps the row visually balanced
  // with the subscription gauge above whether or not the user has topped up.
  const fillPct = balance > 0 ? 100 : 0;

  return (
    <div>
      <div className="flex items-center justify-between text-sm mb-1">
        <span className="text-theme-secondary">{t('breakdown.payg')}</span>
        <span className="font-medium text-theme-primary">
          {formatCreditsCompact(balance)}
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

'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { AlertTriangle, Check, ArrowRight, Coins, Info } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Slider } from '@/components/ui/slider';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';
import { useRouter } from 'next/navigation';
import { CREDIT_TIERS, STARTER_MAX_CREDITS, calcPrice as calcPriceBase, formatTierLabel } from '@/lib/billing/pricing-constants';
import { useSubscription, usePaygTiers } from '@/lib/hooks/smart-hooks-complete';
import { isCeMode } from '@/lib/format-cost';
import DeploymentBadge from '@/components/pricing/DeploymentBadge';
import FeatureLabel from '@/components/pricing/FeatureLabel';
import TopUpModal from './TopUpModal';

/**
 * Custom event name for triggering the insufficient credits modal.
 * Dispatch `new CustomEvent('insufficientCredits')` from anywhere to open it.
 */
export const INSUFFICIENT_CREDITS_EVENT = 'insufficientCredits';

interface PlanCard {
  id: string;
  name: string;
  monthlyPrice: number;
  credits: string;
  features: string[];
  popular?: boolean;
}

const PLAN_MAPPING: Record<string, string> = {
  starter: 'STARTER',
  pro: 'PRO',
  team: 'TEAM',
  free: 'FREE',
};

export default function InsufficientCreditsModal() {
  const t = useTranslations('modals.insufficientCredits');
  const tPayg = useTranslations('billing.payg');
  const tBilling = useTranslations('pricing.billing');
  const router = useRouter();
  const { createSubscription, subscription } = useSubscription();
  // V250 - surface a "Top up instead" CTA when at least one PAYG tier is
  // wired by ops. The hook is cheap (5min cache) and the flag drives a
  // single button render, not a full second flow.
  const { configured: paygConfigured } = usePaygTiers();
  const [open, setOpen] = useState(false);
  const [topUpOpen, setTopUpOpen] = useState(false);
  const [billingCycle, setBillingCycle] = useState<'monthly' | 'yearly'>('yearly');
  const [creditTierIndex, setCreditTierIndex] = useState(0);
  const [processingPlanId, setProcessingPlanId] = useState<string | null>(null);

  const creditAmount = CREDIT_TIERS[creditTierIndex];

  // Free-plan scoping note: monthly credits run workflows for free; chat and
  // agents need a paid plan or a PAYG top-up. Only the Free plan sees the note -
  // a paid user who ran out just needs more credits, not the plan explanation.
  const planCode = (((subscription as any)?.subscription?.planCode as string) || 'FREE').toUpperCase();
  const isFreePlan = planCode === 'FREE';

  useEffect(() => {
    // CE never surfaces this modal - credits are unlimited and Stripe is
    // disabled. Skip the listener entirely so a stray dispatch from any
    // future code path is a no-op in CE.
    if (isCeMode) return;
    const handler = () => setOpen(true);
    window.addEventListener(INSUFFICIENT_CREDITS_EVENT, handler);
    return () => window.removeEventListener(INSUFFICIENT_CREDITS_EVENT, handler);
  }, []);

  const handleClose = useCallback(() => setOpen(false), []);

  const handleSelectPlan = useCallback(async (planId: string) => {
    if (planId === 'free') {
      setOpen(false);
      return;
    }

    setProcessingPlanId(planId);
    try {
      const backendPlanCode = PLAN_MAPPING[planId] || planId.toUpperCase();
      const result = await createSubscription({
        planCode: backendPlanCode,
        billingCycle,
        creditTierIndex: String(creditTierIndex),
      });

      if (result === 'FREE_PLAN_SELECTED' || result === 'SWAP_IMMEDIAT') {
        setOpen(false);
        return;
      }

      // Stripe checkout URL - redirect
      const checkoutUrl = (result as any)?.url;
      if (checkoutUrl && typeof checkoutUrl === 'string') {
        window.location.href = checkoutUrl;
      } else {
        // Immediate swap (no URL) - success
        setOpen(false);
      }
    } catch (error) {
      console.error('Error creating subscription from modal:', error);
      // Fallback: redirect to pricing page
      setOpen(false);
      router.push('/app/settings/pricing');
    } finally {
      setProcessingPlanId(null);
    }
  }, [createSubscription, billingCycle, creditTierIndex, router]);

  // Defense-in-depth: never render the Stripe-pricing modal in CE.
  if (isCeMode) return null;

  const calcPrice = (planId: string) => calcPriceBase(planId, billingCycle, creditTierIndex);

  const plans: PlanCard[] = [
    {
      id: 'free',
      name: 'Free',
      monthlyPrice: 0,
      credits: '1,000',
      features: [
        // Info "i" tooltip: Free credits run workflows only (chat/agents are paid),
        // rendered by FeatureLabel via the "label||tooltip" convention.
        `${t('features.freeCredits')}||${t('features.freeCreditsTooltip')}`,
        t('features.freeConcurrent'),
        t('features.freeStorage'),
      ],
    },
    ...(creditAmount <= STARTER_MAX_CREDITS ? [{
      id: 'starter',
      name: 'Starter',
      monthlyPrice: calcPrice('starter'),
      credits: creditAmount.toLocaleString(getClientLocale()),
      features: [
        `${isCeMode ? `$${creditAmount.toLocaleString(getClientLocale())}` : `${creditAmount.toLocaleString(getClientLocale())} ${t('features.creditsPerMonth')}`}`,
        t('features.starterConcurrent'),
        t('features.starterStorage'),
      ],
      popular: true,
    }] : []),
    {
      id: 'pro',
      name: 'Pro',
      monthlyPrice: calcPrice('pro'),
      credits: creditAmount.toLocaleString(getClientLocale()),
      features: [
        `${isCeMode ? `$${creditAmount.toLocaleString(getClientLocale())}` : `${creditAmount.toLocaleString(getClientLocale())} ${t('features.creditsPerMonth')}`}`,
        t('features.proConcurrent'),
        t('features.proStorage'),
      ],
      popular: creditAmount > STARTER_MAX_CREDITS,
    },
    {
      id: 'team',
      name: 'Team',
      monthlyPrice: calcPrice('team'),
      credits: creditAmount.toLocaleString(getClientLocale()),
      features: [
        `${isCeMode ? `$${creditAmount.toLocaleString(getClientLocale())}` : `${creditAmount.toLocaleString(getClientLocale())} ${t('features.creditsPerMonth')}`}`,
        t('features.teamConcurrent'),
        t('features.teamStorage'),
      ],
    },
  ];

  return (
    <>
      <Dialog open={open} onOpenChange={(o) => !o && handleClose()}>
        <DialogContent className="max-w-3xl w-full max-h-[95vh] overflow-y-auto p-0 gap-0">
          {/* Header */}
          <div className="p-4 sm:p-6 pb-3 sm:pb-4 pr-12 text-center border-b border-theme">
            <div className="w-12 h-12 sm:w-14 sm:h-14 bg-amber-100 dark:bg-amber-900/30 rounded-full flex items-center justify-center mx-auto mb-3 sm:mb-4">
              <AlertTriangle className="h-6 w-6 sm:h-7 sm:w-7 text-amber-600 dark:text-amber-400" />
            </div>
            <h2 className="text-lg sm:text-xl font-semibold text-theme-primary mb-1">
              {t('title')}
            </h2>
            <p className="text-xs sm:text-sm text-theme-secondary">
              {t('description')}
            </p>

            {/* Free-plan scoping: workflows are free, chat/agents are paid. */}
            {isFreePlan && (
              <div className="mt-3 flex items-start gap-2 rounded-lg border border-theme bg-theme-tertiary px-3 py-2 text-left">
                <Info className="h-3.5 w-3.5 text-theme-muted flex-shrink-0 mt-0.5" />
                <p className="text-xs sm:text-sm text-theme-secondary">
                  {t('freeScopeNote')}
                </p>
              </div>
            )}

            {/* V250 - PAYG escape hatch: when ops have wired the tier prices,
                the user can top up without committing to a recurring plan
                upgrade. Rendered only when at least one tier is configured
                so the button never points into a 503. */}
            {paygConfigured && (
              <Button
                onClick={() => setTopUpOpen(true)}
                variant="outline"
                size="sm"
                className="mt-3 gap-1.5"
              >
                <Coins className="h-3.5 w-3.5" />
                {tPayg('topUpInstead')}
              </Button>
            )}
          </div>

          {/* Billing cycle toggle */}
          <div className="flex justify-center pt-5 pb-2">
            <div className="inline-flex items-center bg-theme-tertiary rounded-full p-0.5">
              <button
                onClick={() => setBillingCycle('monthly')}
                className={`px-4 py-1.5 text-xs font-medium rounded-full transition-all duration-200 ${
                  billingCycle === 'monthly'
                    ? 'bg-theme-primary text-theme-primary shadow-sm'
                    : 'text-theme-muted hover:text-theme-primary'
                }`}
              >
                {t('monthly')}
              </button>
              <button
                onClick={() => setBillingCycle('yearly')}
                className={`px-4 py-1.5 text-xs font-medium rounded-full transition-all duration-200 ${
                  billingCycle === 'yearly'
                    ? 'bg-theme-primary text-theme-primary shadow-sm'
                    : 'text-theme-muted hover:text-theme-primary'
                }`}
              >
                {t('yearly')}
                {billingCycle !== 'yearly' && (
                  <span className="ml-1 text-green-600 dark:text-green-400 font-semibold">{t('yearlyBadge')}</span>
                )}
              </button>
            </div>
          </div>

          {/* Credit Slider */}
          <div className="w-full flex flex-col items-center mt-2 mb-2 px-4 sm:px-6">
            <p className="text-xs text-theme-muted mb-1">{t('creditSlider.label')}</p>
            <p className="text-sm font-bold text-theme-primary mb-2">
              {isCeMode ? `$${creditAmount.toLocaleString(getClientLocale())}` : `${creditAmount.toLocaleString(getClientLocale())} ${t('creditSlider.creditsPerMonth')}`}
            </p>
            <div className="w-full max-w-md">
              <Slider
                value={[creditTierIndex]}
                onValueChange={(v) => setCreditTierIndex(v[0])}
                min={0}
                max={CREDIT_TIERS.length - 1}
                step={1}
              />
              <div className="flex justify-between mt-1.5 text-[10px] text-theme-muted">
                {CREDIT_TIERS.map((tier, i) => {
                  const isEndpoint = i === 0 || i === CREDIT_TIERS.length - 1;
                  const isEvenIndex = i % 2 === 0;
                  let visibilityClass = '';
                  if (!isEndpoint && !isEvenIndex) {
                    visibilityClass = 'invisible md:visible';
                  } else if (!isEndpoint) {
                    visibilityClass = 'invisible sm:visible';
                  }
                  return (
                    <span key={tier} className={visibilityClass}>
                      {formatTierLabel(tier)}
                    </span>
                  );
                })}
              </div>
            </div>
          </div>

          {/* Plans grid */}
          <div className="p-4 sm:p-6 pt-3">
            <div className={`grid grid-cols-1 sm:grid-cols-2 ${plans.length <= 3 ? 'lg:grid-cols-3' : 'lg:grid-cols-4'} gap-3`}>
              {plans.map((plan) => (
                <div
                  key={plan.id}
                  className={`relative p-4 border rounded-xl transition-all duration-200 ${
                    plan.popular
                      ? 'border-2 border-[var(--text-primary)]'
                      : 'border-theme hover:border-[var(--text-secondary)]'
                  }`}
                >
                  {plan.popular && (
                    <div className="absolute -top-2.5 left-1/2 transform -translate-x-1/2">
                      <Badge variant="secondary" className="bg-[var(--text-primary)] text-[var(--bg-primary)] border-transparent px-2 py-0.5 text-xs">
                        {t('recommended')}
                      </Badge>
                    </div>
                  )}

                  <div className="text-center mb-3">
                    <h3 className="text-sm font-bold text-theme-primary mb-1">
                      {plan.name}
                    </h3>
                    <div className="text-2xl font-bold text-theme-primary">
                      {plan.monthlyPrice === 0 ? '$0' : `$${plan.monthlyPrice}`}
                      {plan.monthlyPrice > 0 && (
                        <span className="text-xs text-theme-muted font-normal">
                          /{t('mo')}
                        </span>
                      )}
                    </div>
                  </div>

                  <ul className="space-y-1.5 mb-4">
                    <DeploymentBadge size="xs" />
                    {plan.features.map((feature, i) => (
                      <li key={i} className="flex items-start gap-1.5 text-xs text-theme-secondary">
                        <Check className="w-3 h-3 text-green-500 flex-shrink-0 mt-0.5" />
                        <FeatureLabel feature={feature} />
                      </li>
                    ))}
                  </ul>

                  <Button
                    onClick={() => handleSelectPlan(plan.id)}
                    variant={plan.popular ? 'default' : 'outline'}
                    size="sm"
                    className="w-full text-xs"
                    disabled={processingPlanId !== null}
                  >
                    {processingPlanId === plan.id ? (
                      <LoadingSpinner size="xs" />
                    ) : (
                      <>
                        {plan.id === 'free' ? t('currentPlan') : t('upgrade')}
                        {plan.id !== 'free' && <ArrowRight className="w-3 h-3 ml-1" />}
                      </>
                    )}
                  </Button>
                </div>
              ))}
            </div>

            {/* Tax note */}
            <p className="mt-3 text-center text-sm text-theme-muted">
              {tBilling('taxNote')}
            </p>

            {/* Footer link */}
            <div className="mt-4 text-center">
              <button
                onClick={() => { setOpen(false); router.push('/app/settings/pricing'); }}
                className="text-xs text-theme-muted hover:text-theme-primary underline"
              >
                {t('viewAllPlans')}
              </button>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      {/* V250 - nested TopUpModal opened from the "Top up instead" button. */}
      <TopUpModal isOpen={topUpOpen} onClose={() => setTopUpOpen(false)} />
    </>
  );
}

/**
 * Helper to dispatch the insufficient credits event from anywhere.
 */
export function showInsufficientCreditsModal() {
  window.dispatchEvent(new CustomEvent(INSUFFICIENT_CREDITS_EVENT));
}

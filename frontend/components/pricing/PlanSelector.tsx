'use client';
import React, { useState } from 'react';
import { useTheme } from '../ThemeProvider';
import { usePlans } from '@/lib/hooks/smart-hooks-complete';
import { Check, ArrowRight, AlertCircle } from 'lucide-react';
import LoadingSpinner from '../LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { useLocale, useTranslations } from 'next-intl';
import { getClientLocale } from '@/lib/utils/locale';
import DeploymentBadge from '@/components/pricing/DeploymentBadge';
import FeatureLabel from '@/components/pricing/FeatureLabel';
import FoundingPriceNote from '@/components/pricing/FoundingPriceNote';
import ReferencePrice from '@/components/pricing/ReferencePrice';
import type { ResolvedPricingEvent } from '@/lib/billing/pricing-events';
import type { PersonalOfferPlanPreview } from '@/lib/api/services/reward-api.service';

interface Plan {
  id: string;
  name: string;
  description: string;
  price: string;
  period: string;
  credits: string;
  storage: string;
  features: string[];
  cta: string;
  popular?: boolean;
  badge?: string;
  creditPrice: string;
  disabled?: boolean;
}

interface PlanSelectorProps {
  plan: Plan;
  billingCycle: 'monthly' | 'yearly';
  onPlanSelect: (planId: string, billingCycle: 'monthly' | 'yearly') => Promise<{ success: boolean; message?: string; error?: string }>;
  isLoading?: boolean;
  isProcessingCheckout?: boolean;
  currentPlan?: string;
  currentCadence?: 'monthly' | 'yearly';
  onPlanChanged?: (newPlan: string) => void;
  onBillingCycleChange?: (cycle: 'monthly' | 'yearly') => void;
  /** Open pricing event, or null. Drives the founding-price note under the price. */
  pricingEvent?: ResolvedPricingEvent | null;
  /** Credit tier the displayed price was composed with, so the announced price matches it. */
  creditTierIndex?: number;
  personalOffer?: PersonalOfferPlanPreview | null;
  personalOfferActive?: boolean;
  nextEligibleMonthlyCredits?: number | null;
  selectedPlanCode?: string | null;
  /** The plan a partner's link recommends (upper case): its selected card names the partner. */
  partnerRecommendedPlanCode?: string | null;
  /** "Recommended by your partner", translated by the page. */
  partnerRecommendedLabel?: string;
}

const PlanSelector = React.memo(function PlanSelector({
  plan,
  billingCycle,
  onPlanSelect,
  isLoading = false,
  isProcessingCheckout = false,
  currentPlan = 'FREE',
  currentCadence = 'yearly',
  onPlanChanged,
  onBillingCycleChange,
  pricingEvent = null,
  creditTierIndex = 0,
  personalOffer = null,
  personalOfferActive = false,
  nextEligibleMonthlyCredits = null,
  selectedPlanCode = null,
  partnerRecommendedPlanCode = null,
  partnerRecommendedLabel,
}: PlanSelectorProps) {
  const { theme } = useTheme();
  const { isLoading: isLoadingPlans } = usePlans();
  const t = useTranslations('pricing.planCards');
  const tOffer = useTranslations('reward.personalOffer');
  const locale = useLocale();
  const [isProcessing, setIsProcessing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // The parent component now computes the correct price for all plans
  const formatPriceLocal = (planCode: string) => {
    if (planCode === 'FREE') {
      return t('price.free');
    }
    if (plan.disabled) {
      return plan.price;
    }
    // Group thousands per the APP locale (e.g. "$4,000" / "$4 000") instead of a
    // bare "$4000"; keep the raw value if it is not a plain number.
    const numeric = Number(plan.price);
    return `$${Number.isFinite(numeric) ? numeric.toLocaleString(getClientLocale()) : plan.price}`;
  };

  // Fonction simplifiee pour gerer la selection des plans
  const handlePlanSelect = React.useCallback(async () => {
    setIsProcessing(true);
    setError(null);

    try {
      console.log(`Plan selection: ${plan.id} (${billingCycle})`);

      // Appeler la fonction onPlanSelect passee en prop
      const result = await onPlanSelect(plan.id, billingCycle);

      if (!result.success && result.error && result.error !== 'Downgrade not allowed' && result.error !== 'Billing cycle change not allowed') {
        setError(result.error);
      }
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Error selecting plan';
      setError(errorMessage);
    } finally {
      setIsProcessing(false);
    }
  }, [plan.id, billingCycle, onPlanSelect]);

  const isDisabled = isLoading || isProcessing || isProcessingCheckout ||
    (currentPlan === plan.id.toUpperCase() && currentCadence === billingCycle && plan.id !== 'free' && plan.id !== 'enterprise');
  const showAction = !(currentPlan === plan.id.toUpperCase() && currentCadence === billingCycle && plan.id !== 'free' && plan.id !== 'enterprise');
  const earlyAction = personalOfferActive && currentPlan === 'FREE' && plan.id !== 'free' && !plan.disabled;
  const annualTotal = Number(plan.price) * 12;
  const planAction = showAction ? (
    <Button onClick={handlePlanSelect} disabled={isDisabled || isProcessing} variant="default" size="default" className="w-full">
      {isProcessing ? (
        <div className="flex items-center justify-center gap-2">
          <LoadingSpinner size="sm" className="text-current" />
          {t('actions.processing')}
        </div>
      ) : (
        <>{plan.id === 'free' ? t('actions.startFree') : plan.cta}<ArrowRight className="inline-block w-4 h-4 ml-2" /></>
      )}
    </Button>
  ) : null;

  return (
    <div className={`relative p-4 border border-black/10 dark:border-white/20 rounded-2xl transition-all duration-200 ${plan.disabled ? 'pointer-events-none' : ''} ${(currentPlan === 'FREE' && plan.popular) ||
      (currentPlan === plan.id.toUpperCase() && currentCadence === billingCycle && plan.id !== 'free') ||
      ((currentPlan.startsWith('ENTERPRISE_') || currentPlan === 'ENTERPRISE') && plan.id === 'enterprise' && currentCadence === billingCycle) ||
      (selectedPlanCode === plan.id.toUpperCase() && currentPlan !== plan.id.toUpperCase())
      ? '!border-2 !border-black dark:!border-white bg-transparent'
      : 'hover:border-theme/30'
      }`}>
      {/* Badge: Coming soon (disabled), Recommended (popular), or Current plan */}
      {plan.disabled ? null
      : (currentPlan === plan.id.toUpperCase() && currentCadence === billingCycle && plan.id !== 'free') ||
        ((currentPlan.startsWith('ENTERPRISE_') || currentPlan === 'ENTERPRISE') && plan.id === 'enterprise' && currentCadence === billingCycle) ? (
          <div className="absolute -top-3 left-1/2 transform -translate-x-1/2">
            <Badge variant="secondary" className="bg-black text-white dark:bg-white dark:text-black border-transparent px-3 py-1">
              {t('badges.current')}
            </Badge>
          </div>
        ) : selectedPlanCode === plan.id.toUpperCase() && partnerRecommendedLabel && partnerRecommendedPlanCode === plan.id.toUpperCase() ? (
          // Still the plan the partner's link chose: say who recommended it, in the partner gold.
          <div className="absolute -top-3 left-1/2 transform -translate-x-1/2">
            <Badge variant="secondary" className="whitespace-nowrap border-transparent px-3 py-1 text-[#2a1a00]" style={{ background: 'linear-gradient(135deg, #fde68a, #f2b640 55%, #d99a1e)' }} data-testid="partner-recommended-badge">
              {partnerRecommendedLabel}
            </Badge>
          </div>
        ) : selectedPlanCode === plan.id.toUpperCase() ? (
          <div className="absolute -top-3 left-1/2 transform -translate-x-1/2">
            <Badge variant="secondary" className="bg-black text-white dark:bg-white dark:text-black border-transparent px-3 py-1">
              {tOffer('selectedPlan')}
            </Badge>
          </div>
        ) : (currentPlan === 'FREE' && plan.popular) ? (
          <div className="absolute -top-3 left-1/2 transform -translate-x-1/2">
            <Badge variant="secondary" className="bg-black text-white dark:bg-white dark:text-black border-transparent px-3 py-1">
              {t('badges.recommended')}
            </Badge>
          </div>
        ) : null}

      <div className="text-center mb-6">
        <h3 className="text-xl font-bold text-theme-primary mb-2 transition-colors duration-300">
          {plan.name}
        </h3>
        <p className="text-sm text-theme-secondary mb-4 transition-colors duration-300">
          {plan.description}
        </p>

        {/* Prix */}
        <div className="text-3xl font-bold text-theme-primary transition-colors duration-300 mb-2 text-center">
          {isLoadingPlans ? (
            <div className="animate-pulse bg-gray-200 h-8 w-24 rounded mx-auto"></div>
          ) : (
            <div className="flex items-baseline justify-center gap-2">
              {!plan.disabled && (
                <ReferencePrice
                  planId={plan.id}
                  cycle={billingCycle}
                  creditTierIndex={creditTierIndex}
                  event={pricingEvent}
                />
              )}
              <span className="flex items-baseline gap-1.5">
                <span>{formatPriceLocal(plan.id.toUpperCase())}</span>
                {plan.id !== 'free' && !plan.disabled && (
                  <span className="text-sm text-theme-secondary">{t('period')}</span>
                )}
              </span>
            </div>
          )}
        </div>
        {/* Gated on isLoadingPlans as well: while the price is still a skeleton there is
            no figure for the caption to qualify, and it would sit under an empty box. */}
        {!plan.disabled && !isLoadingPlans && (
          <FoundingPriceNote
            planId={plan.id}
            cycle={billingCycle}
            creditTierIndex={creditTierIndex}
            event={pricingEvent}
          />
        )}
        {billingCycle === 'yearly' && !plan.disabled && plan.id !== 'free' && Number.isFinite(annualTotal) && (
          <p className="mt-3 text-sm text-theme-primary">{tOffer('annualTotal', { amount: new Intl.NumberFormat(locale, { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(annualTotal) })}</p>
        )}
        {personalOfferActive && !plan.disabled && plan.id !== 'free' && (
          <div className="mt-3 space-y-1 text-sm text-theme-primary">
            {!personalOffer ? <p>{tOffer('checking')}</p>
              : personalOffer.status === 'ELIGIBLE' && personalOffer.bonusCredits > 0 ? (
                <p>{tOffer('bonus', {
                  credits: personalOffer.bonusCredits.toLocaleString(locale),
                  value: new Intl.NumberFormat(locale, { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(personalOffer.paygFaceValueUsd),
                })}</p>
              ) : personalOffer.status === 'NO_BONUS' ? (
                <>
                  <p>{tOffer('noBonus')}</p>
                  {nextEligibleMonthlyCredits != null && <p>{tOffer('nextEligible', { credits: nextEligibleMonthlyCredits.toLocaleString(locale) })}</p>}
                  <p className="text-theme-secondary">{tOffer('firstPurchaseUsed')}</p>
                </>
              ) : <p>{tOffer('planUnavailable')}</p>}
          </div>
        )}
      </div>

      {earlyAction && <div className="mb-6">{planAction}</div>}

      <div className="flex justify-center mb-6">
        <ul className="space-y-3 text-sm inline-flex flex-col">
          <DeploymentBadge />
          {plan.features.map((feature, featureIndex) => (
            <li key={featureIndex} className="flex items-start gap-2">
              <Check className="w-4 h-4 text-green-500 flex-shrink-0 mt-0.5" />
              {/* `flex-1` so FeatureLabel owns the rest of the row and can pin
                  its "i" to the right edge; `items-start` so a label that wraps
                  keeps the check and the "i" on its first line. */}
              <span className="flex flex-1 min-w-0 text-theme-secondary transition-colors duration-300">
                <FeatureLabel feature={feature} />
              </span>
            </li>
          ))}
        </ul>
      </div>

      {error && (
        <div className="mb-4 p-3 bg-red-100 border border-red-300 rounded-lg flex items-center gap-2">
          <AlertCircle className="w-4 h-4 text-red-500" />
          <span className="text-sm text-red-700">{error}</span>
        </div>
      )}

      {!earlyAction && planAction}
    </div>
  );
});

export default PlanSelector;

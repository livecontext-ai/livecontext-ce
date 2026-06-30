'use client';

import React from 'react';
import { ArrowUp, ArrowRight, Loader2, CreditCard, Calendar, CheckCircle, AlertTriangle, MessageSquare } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { usePlans } from '@/lib/hooks/smart-hooks-complete';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';

export type UpgradeModalState = 'confirm' | 'processing' | 'success' | 'error';

interface UpgradeModalProps {
  open: boolean;
  state: UpgradeModalState;
  currentPlan: string;
  targetPlan: string;
  billingCycle?: 'monthly' | 'yearly';
  onConfirm: () => void;
  onClose: () => void;
  loading?: boolean;
  errorMessage?: string;
  /** Total price including credits for current plan (overrides internal calculation) */
  currentTotalPrice?: number;
  /** Total price including credits for target plan (overrides internal calculation) */
  targetTotalPrice?: number;
}

export default function UpgradeModal({
  open,
  state,
  currentPlan,
  targetPlan,
  billingCycle = 'monthly',
  onConfirm,
  onClose,
  loading = false,
  errorMessage,
  currentTotalPrice,
  targetTotalPrice
}: UpgradeModalProps) {
  const t = useTranslations('modals.upgradeConfirm');
  const tBilling = useTranslations('pricing.billing');
  const { getPlan } = usePlans();

  const fallbackData: Record<string, { price: string; yearlyPrice: string }> = {
    FREE: { price: '0', yearlyPrice: '0' },
    STARTER: { price: '20', yearlyPrice: '16' },
    PRO: { price: '200', yearlyPrice: '160' },
    ENTERPRISE: { price: '500', yearlyPrice: '400' },
    ENTERPRISE_BASIC: { price: '500', yearlyPrice: '400' },
    ENTERPRISE_STANDARD: { price: '1000', yearlyPrice: '800' },
    ENTERPRISE_PREMIUM: { price: '2000', yearlyPrice: '1600' },
    ENTERPRISE_ULTIMATE: { price: '5000', yearlyPrice: '4000' }
  };

  const getPrice = (planCode: string, cycle: 'monthly' | 'yearly') => {
    const plan = getPlan(planCode) as any;
    if (plan?.prices?.[cycle]?.amount_dollars) {
      if (cycle === 'yearly') {
        return Math.round(plan.prices.yearly.amount_dollars / 12);
      }
      return plan.prices[cycle].amount_dollars;
    }
    const fallback = fallbackData[planCode];
    return cycle === 'yearly' ? fallback?.yearlyPrice : fallback?.price;
  };

  const currentPrice = currentTotalPrice !== undefined ? currentTotalPrice : getPrice(currentPlan, billingCycle);
  const targetPrice = targetTotalPrice !== undefined ? targetTotalPrice : getPrice(targetPlan, billingCycle);

  const formatPlanName = (plan: string) => {
    if (!plan || plan === 'UNKNOWN') return t('yourNew');
    return plan.replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase());
  };

  const isYearly = billingCycle === 'yearly';
  const isProcessing = state === 'processing';

  // Confirm state - uses header layout like UpgradeExplainerModal
  if (state === 'confirm') {
    return (
      <Dialog open={open} onOpenChange={(o) => !o && !loading && onClose()}>
        <DialogContent className="max-w-md p-0 gap-0 overflow-hidden">
          {/* Header */}
          <div className="p-6 pb-4">
            <div className="flex items-center gap-3">
              <div className="p-2 rounded-full bg-theme-tertiary">
                <ArrowUp className="h-5 w-5 text-theme-secondary" />
              </div>
              <h2 className="text-lg font-semibold text-theme-primary">
                {t('title')}
              </h2>
            </div>
          </div>

          {/* Content */}
          <div className="p-6 pt-0">
            {/* Plan transition */}
            <div className="flex items-center justify-center gap-4 mb-6">
              <div className="text-center">
                <div className="text-sm text-theme-secondary mb-1">{t('current')}</div>
                <div className="font-semibold text-theme-primary">
                  {formatPlanName(currentPlan)}
                </div>
                <div className="text-sm text-theme-secondary">
                  ${currentPrice}/{t('mo')}
                </div>
                {isYearly && (
                  <div className="text-xs text-theme-muted">
                    (${Number(currentPrice) * 12}/{t('yr')})
                  </div>
                )}
              </div>

              <ArrowRight className="h-6 w-6 text-theme-secondary" />

              <div className="text-center">
                <div className="text-sm text-theme-secondary mb-1">{t('new')}</div>
                <div className="font-semibold text-theme-primary">
                  {formatPlanName(targetPlan)}
                </div>
                <div className="text-sm text-theme-secondary">
                  ${targetPrice}/{t('mo')}
                </div>
                {isYearly && (
                  <div className="text-xs text-theme-muted">
                    (${Number(targetPrice) * 12}/{t('yr')})
                  </div>
                )}
              </div>
            </div>

            <p className="text-center text-sm text-theme-muted -mt-4 mb-6">
              {tBilling('taxNote')}
            </p>

            {/* Info box */}
            <div className="rounded-lg p-4 mb-6 bg-theme-secondary border border-theme space-y-4">
              <div className="flex items-start gap-3">
                <Calendar className="h-5 w-5 flex-shrink-0 mt-0.5 text-theme-secondary" />
                <div>
                  <p className="text-sm font-medium mb-1 text-theme-primary">
                    {t('effectiveImmediately')}
                  </p>
                  <p className="text-sm text-theme-secondary">
                    {t('upgradeActivated', { plan: formatPlanName(targetPlan) })}
                  </p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <CreditCard className="h-5 w-5 flex-shrink-0 mt-0.5 text-theme-secondary" />
                <div>
                  <p className="text-sm text-theme-secondary">
                    {t('proratedExplanation')}
                  </p>
                </div>
              </div>
            </div>
          </div>

          {/* Footer */}
          <div className="p-6 pt-0 flex gap-3">
            <Button
              onClick={onClose}
              disabled={loading}
              variant="outline"
              className="flex-1"
            >
              {t('cancel')}
            </Button>
            <Button
              onClick={onConfirm}
              disabled={loading}
              variant="contrast"
              className="flex-1"
            >
              {loading ? (
                <>
                  <LoadingSpinner size="xs" />
                  {t('processing')}
                </>
              ) : (
                t('confirmUpgrade')
              )}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    );
  }

  // Processing, Success, Error states - use centered layout like UpgradeSuccessModal
  const stateConfig = {
    processing: {
      icon: Loader2,
      iconBg: 'bg-theme-tertiary',
      iconColor: 'text-theme-secondary animate-spin',
      title: t('processingTitle')
    },
    success: {
      icon: CheckCircle,
      iconBg: 'bg-green-100 dark:bg-green-900/30',
      iconColor: 'text-green-600 dark:text-green-400',
      title: t('successTitle')
    },
    error: {
      icon: AlertTriangle,
      iconBg: 'bg-red-100 dark:bg-red-900/30',
      iconColor: 'text-red-600 dark:text-red-400',
      title: t('errorTitle')
    }
  };

  const config = stateConfig[state];
  const Icon = config.icon;

  return (
    <Dialog open={open} onOpenChange={(o) => !o && !isProcessing && onClose()}>
      <DialogContent className="max-w-sm p-0 gap-0 overflow-hidden">
        <div className="p-8 text-center">
          {/* Icon */}
          <div className={`w-16 h-16 ${config.iconBg} rounded-full flex items-center justify-center mx-auto mb-5`}>
            <Icon className={`h-8 w-8 ${config.iconColor}`} />
          </div>

          {/* Title */}
          <h2 className="text-xl font-semibold text-theme-primary mb-2">
            {config.title}
          </h2>

          {/* State: Processing */}
          {state === 'processing' && (
            <>
              <p className="text-sm text-theme-secondary mb-6">
                {t('finalizingSubscription')}
              </p>
              <p className="text-xs text-theme-muted">
                {t('dontClosePage')}
              </p>
            </>
          )}

          {/* State: Success */}
          {state === 'success' && (
            <>
              <p className="text-sm text-theme-secondary mb-6">
                {t('nowOnPlan', { plan: formatPlanName(targetPlan) })}
              </p>
              <Button
                onClick={() => window.location.href = '/app/chat'}
                variant="contrast"
                className="w-full"
              >
                <MessageSquare className="w-4 h-4" />
                {t('chat')}
              </Button>
            </>
          )}

          {/* State: Error */}
          {state === 'error' && (
            <>
              <p className="text-sm text-theme-secondary mb-6">
                {errorMessage || t('tryAgainOrContact')}
              </p>
              <div className="flex gap-3">
                <Button
                  onClick={onClose}
                  variant="outline"
                  className="flex-1"
                >
                  {t('close')}
                </Button>
                <Button
                  onClick={onConfirm}
                  variant="contrast"
                  className="flex-1"
                >
                  {t('retry')}
                </Button>
              </div>
            </>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

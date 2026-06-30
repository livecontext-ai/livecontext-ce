'use client';

import React, { useMemo } from 'react';
import { ArrowUp, ArrowRight, CreditCard, Calendar } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { usePlans } from '@/lib/hooks/smart-hooks-complete';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';

interface UpgradeExplainerModalProps {
  open: boolean;
  currentPlan: string;
  targetPlan: string;
  billingCycle?: "monthly" | "yearly";
  onConfirm: () => void;
  onCancel: () => void;
  loading?: boolean;
}

const UpgradeExplainerModal = React.memo(function UpgradeExplainerModal({
  open,
  currentPlan,
  targetPlan,
  billingCycle = "monthly",
  onConfirm,
  onCancel,
  loading = false
}: UpgradeExplainerModalProps) {
  const t = useTranslations('modals.upgrade');
  const { getPlan } = usePlans();

  const actualCurrentPlan = currentPlan;

  // Fallback data if plans are not yet loaded
  const fallbackData: Record<string, { price: string; credits: string; storage: string }> = {
    FREE: { price: '0', credits: '100', storage: '1GB' },
    STARTER: { price: '20', credits: '1000', storage: '10GB' },
    PRO: { price: '200', credits: '10000', storage: '100GB' },
    ENTERPRISE: { price: '500', credits: 'Unlimited', storage: 'Unlimited' },
    ENTERPRISE_BASIC: { price: '500', credits: 'Unlimited', storage: 'Unlimited' },
    ENTERPRISE_STANDARD: { price: '1000', credits: 'Unlimited', storage: 'Unlimited' },
    ENTERPRISE_PREMIUM: { price: '2000', credits: 'Unlimited', storage: 'Unlimited' },
    ENTERPRISE_ULTIMATE: { price: '5000', credits: 'Unlimited', storage: 'Unlimited' }
  };

  const currentPlanData = useMemo(() => getPlan(actualCurrentPlan), [getPlan, actualCurrentPlan]);
  const targetPlanData = useMemo(() => getPlan(targetPlan), [getPlan, targetPlan]);

  const currentPrice = useMemo(() => {
    if ((currentPlanData as any)?.prices?.[billingCycle]) {
      return (currentPlanData as any).prices[billingCycle].amount_dollars;
    }
    return parseFloat(fallbackData[actualCurrentPlan]?.price || '0');
  }, [currentPlanData, actualCurrentPlan, billingCycle]);

  const targetPrice = useMemo(() => {
    if ((targetPlanData as any)?.prices?.[billingCycle]) {
      return (targetPlanData as any).prices[billingCycle].amount_dollars;
    }
    return parseFloat(fallbackData[targetPlan]?.price || '0');
  }, [targetPlanData, targetPlan, billingCycle]);

  const prorataAmount = useMemo(() => Math.round(targetPrice - currentPrice), [targetPrice, currentPrice]);

  const formatPlanName = (plan: string) => {
    return plan.replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase());
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && !loading && onCancel()}>
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
                {formatPlanName(actualCurrentPlan)}
              </div>
              <div className="text-sm text-theme-secondary">
                {t('perMonth', { price: currentPrice })}
              </div>
            </div>

            <ArrowRight className="h-6 w-6 text-theme-secondary" />

            <div className="text-center">
              <div className="text-sm text-theme-secondary mb-1">{t('new')}</div>
              <div className="font-semibold text-theme-primary">
                {formatPlanName(targetPlan)}
              </div>
              <div className="text-sm text-theme-secondary">
                {t('perMonth', { price: targetPrice })}
              </div>
            </div>
          </div>

          {/* Info box */}
          <div className="rounded-lg p-4 mb-6 bg-theme-secondary border border-theme space-y-4">
            {prorataAmount > 0 && (
              <div className="flex items-start gap-3">
                <CreditCard className="h-5 w-5 flex-shrink-0 mt-0.5 text-theme-secondary" />
                <div>
                  <p className="text-sm font-medium mb-1 text-theme-primary">
                    {t('paymentToday', { amount: prorataAmount })}
                  </p>
                  <p className="text-sm text-theme-secondary">
                    {t('proratedExplanation')}
                  </p>
                </div>
              </div>
            )}
            <div className="flex items-start gap-3">
              <Calendar className="h-5 w-5 flex-shrink-0 mt-0.5 text-theme-secondary" />
              <div>
                <p className="text-sm font-medium mb-1 text-theme-primary">
                  {t('effectiveImmediately')}
                </p>
                <p className="text-sm text-theme-secondary">
                  {t('upgradeDescription', { plan: formatPlanName(targetPlan) })}
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="p-6 pt-0 flex gap-3">
          <Button
            onClick={onCancel}
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
              prorataAmount > 0 ? t('confirmAndPay', { amount: prorataAmount }) : t('confirmUpgrade')
            )}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
});

export default UpgradeExplainerModal;

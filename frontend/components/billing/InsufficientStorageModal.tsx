'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { HardDrive, Check, ArrowRight } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';
import { useRouter } from 'next/navigation';
import { calcPrice as calcPriceBase } from '@/lib/billing/pricing-constants';
import { useSubscription } from '@/lib/hooks/smart-hooks-complete';
import { storageApi, type StorageQuota, type StorageBreakdown, type StorageCategory, STORAGE_CATEGORY_COLORS } from '@/lib/api/storage-api';

/**
 * Custom event name for triggering the insufficient storage modal.
 * Dispatch `new CustomEvent('insufficientStorage')` from anywhere to open it.
 */
export const INSUFFICIENT_STORAGE_EVENT = 'insufficientStorage';

interface PlanCard {
  id: string;
  name: string;
  monthlyPrice: number;
  features: string[];
  popular?: boolean;
}

const PLAN_MAPPING: Record<string, string> = {
  starter: 'STARTER',
  pro: 'PRO',
  team: 'TEAM',
  free: 'FREE',
};

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  const value = bytes / Math.pow(1024, i);
  return `${value.toFixed(value < 10 && i > 0 ? 1 : 0)} ${units[i]}`;
}

export default function InsufficientStorageModal() {
  const t = useTranslations('modals.insufficientStorage');
  const tBilling = useTranslations('pricing.billing');
  const router = useRouter();
  const { createSubscription } = useSubscription();
  const [open, setOpen] = useState(false);
  const [billingCycle, setBillingCycle] = useState<'monthly' | 'yearly'>('yearly');
  const [processingPlanId, setProcessingPlanId] = useState<string | null>(null);
  const [quota, setQuota] = useState<StorageQuota | null>(null);
  const [breakdown, setBreakdown] = useState<StorageBreakdown[]>([]);

  useEffect(() => {
    const handler = () => setOpen(true);
    window.addEventListener(INSUFFICIENT_STORAGE_EVENT, handler);
    return () => window.removeEventListener(INSUFFICIENT_STORAGE_EVENT, handler);
  }, []);

  // Fetch quota and breakdown when modal opens
  useEffect(() => {
    if (!open) return;
    storageApi.getQuota().then(setQuota).catch(() => {});
    storageApi.getBreakdown().then(setBreakdown).catch(() => {});
  }, [open]);

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
        creditTierIndex: '0',
      });

      if (result === 'FREE_PLAN_SELECTED' || result === 'SWAP_IMMEDIAT') {
        setOpen(false);
        return;
      }

      const checkoutUrl = (result as any)?.url;
      if (checkoutUrl && typeof checkoutUrl === 'string') {
        window.location.href = checkoutUrl;
      } else {
        setOpen(false);
      }
    } catch (error) {
      console.error('Error creating subscription from storage modal:', error);
      setOpen(false);
      router.push('/app/settings/pricing');
    } finally {
      setProcessingPlanId(null);
    }
  }, [createSubscription, billingCycle, router]);

  const calcPrice = (planId: string) => calcPriceBase(planId, billingCycle, 0);

  const usagePercent = quota ? Math.min(quota.usagePercentage, 100) : 0;

  const plans: PlanCard[] = [
    {
      id: 'free',
      name: 'Free',
      monthlyPrice: 0,
      features: [
        t('features.freeStorage'),
        t('features.freeCredits'),
      ],
    },
    {
      id: 'starter',
      name: 'Starter',
      monthlyPrice: calcPrice('starter'),
      features: [
        t('features.starterStorage'),
        t('features.starterCredits'),
      ],
      popular: true,
    },
    {
      id: 'pro',
      name: 'Pro',
      monthlyPrice: calcPrice('pro'),
      features: [
        t('features.proStorage'),
        t('features.proCredits'),
      ],
    },
    {
      id: 'team',
      name: 'Team',
      monthlyPrice: calcPrice('team'),
      features: [
        t('features.teamStorage'),
        t('features.teamCredits'),
      ],
    },
  ];

  return (
    <Dialog open={open} onOpenChange={(o) => !o && handleClose()}>
      <DialogContent className="max-w-3xl w-full p-0 gap-0 overflow-hidden">
        {/* Header */}
        <div className="p-6 pb-4 text-center border-b border-theme">
          <div className="w-14 h-14 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto mb-4">
            <HardDrive className="h-7 w-7 text-red-600 dark:text-red-400" />
          </div>
          <h2 className="text-xl font-semibold text-theme-primary mb-1">
            {t('title')}
          </h2>
          <p className="text-sm text-theme-secondary">
            {t('description')}
          </p>
        </div>

        {/* Storage usage bar */}
        {quota && (
          <div className="px-6 pt-4 pb-2">
            <div className="flex justify-between text-xs text-theme-secondary mb-1.5">
              <span>{t('currentUsage')}</span>
              <span>
                {formatBytes(quota.usedBytes)} {t('of')} {formatBytes(quota.maxBytes)}
              </span>
            </div>
            <div className="w-full h-2.5 bg-theme-tertiary rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full transition-all duration-500 ${
                  usagePercent >= 90
                    ? 'bg-red-500'
                    : usagePercent >= 70
                      ? 'bg-amber-500'
                      : 'bg-blue-500'
                }`}
                style={{ width: `${usagePercent}%` }}
              />
            </div>
          </div>
        )}

        {/* Compact breakdown bar */}
        {breakdown.length > 0 && (() => {
          const total = breakdown.reduce((s, b) => s + b.usedBytes, 0);
          return total > 0 ? (
            <div className="px-6 pb-2">
              <p className="text-xs text-theme-muted mb-1.5">{t('breakdownTitle')}</p>
              <div className="h-2 bg-theme-tertiary rounded-full overflow-hidden flex">
                {breakdown.filter(b => b.usedBytes > 0).map((b) => {
                  const pct = (b.usedBytes / total) * 100;
                  const colorClass = STORAGE_CATEGORY_COLORS[b.category as StorageCategory] || 'bg-gray-400';
                  return (
                    <div
                      key={b.category}
                      className={`h-full ${colorClass}`}
                      style={{ width: `${pct}%` }}
                      title={`${b.category}: ${formatBytes(b.usedBytes)}`}
                    />
                  );
                })}
              </div>
              <div className="flex flex-wrap gap-x-3 gap-y-0.5 mt-1.5">
                {breakdown.filter(b => b.usedBytes > 0).map((b) => {
                  const colorClass = STORAGE_CATEGORY_COLORS[b.category as StorageCategory] || 'bg-gray-400';
                  return (
                    <div key={b.category} className="flex items-center gap-1">
                      <div className={`w-2 h-2 rounded-full ${colorClass}`} />
                      <span className="text-xs text-theme-muted">{formatBytes(b.usedBytes)}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          ) : null;
        })()}

        {/* Billing cycle toggle */}
        <div className="flex justify-center pt-4 pb-2">
          <div className="inline-flex items-center bg-theme-tertiary rounded-lg p-0.5">
            <button
              onClick={() => setBillingCycle('monthly')}
              className={`px-4 py-1.5 text-xs font-medium rounded-lg transition-all duration-200 ${
                billingCycle === 'monthly'
                  ? 'bg-theme-primary text-theme-primary shadow-sm'
                  : 'text-theme-muted hover:text-theme-primary'
              }`}
            >
              {t('monthly')}
            </button>
            <button
              onClick={() => setBillingCycle('yearly')}
              className={`px-4 py-1.5 text-xs font-medium rounded-lg transition-all duration-200 ${
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

        {/* Plans grid */}
        <div className="p-6 pt-3">
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
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
                  {plan.features.map((feature, i) => (
                    <li key={i} className="flex items-start gap-1.5 text-xs text-theme-secondary">
                      <Check className="w-3 h-3 text-green-500 flex-shrink-0 mt-0.5" />
                      <span>{feature}</span>
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

          {/* Footer links */}
          <div className="mt-4 flex justify-center gap-4">
            <button
              onClick={() => { setOpen(false); router.push('/app/settings/storage'); }}
              className="text-xs text-theme-muted hover:text-theme-primary underline"
            >
              {t('manageStorage')}
            </button>
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
  );
}

/**
 * Helper to dispatch the insufficient storage event from anywhere.
 */
export function showInsufficientStorageModal() {
  window.dispatchEvent(new CustomEvent(INSUFFICIENT_STORAGE_EVENT));
}

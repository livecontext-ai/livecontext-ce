'use client';

import React from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { ArrowUp, ArrowRight, ArrowDown, CreditCard, Coins, AlertTriangle, Sparkles, RefreshCcw } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';
import { isCeMode } from '@/lib/format-cost';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

interface CreditChangeInfo {
  planName: string;
  currentCredits: number;
  currentCost: number;
  newCredits: number;
  newCost: number;
  /**
   * Monthly USD cost of the NEW credit pack alone (excluding plan).
   * Used to compute the one-shot charge in upgradeChargeTitle.
   * Required by the upgrade view; not used on downgrade.
   */
  newCreditCost?: number;
  billingCycle: 'monthly' | 'yearly';
  isTeam: boolean;
  currentPeriodEnd?: string;
}

interface CreditChangeModalProps {
  isOpen: boolean;
  info: CreditChangeInfo | null;
  loading?: boolean;
  mode?: 'upgrade' | 'downgrade';
  success?: boolean;
  successDate?: string | null;
  errorMessage?: string | null;
  onConfirm: () => void;
  onClose: () => void;
}

export default function CreditChangeModal({
  isOpen,
  info,
  loading = false,
  mode,
  success = false,
  successDate,
  errorMessage,
  onConfirm,
  onClose
}: CreditChangeModalProps) {
  const t = useTranslations('modals.creditChange');
  const tBilling = useTranslations('pricing.billing');

  if (!info) return null;

  const isUpgrade = mode ? mode === 'upgrade' : info.newCost > info.currentCost;
  const isDowngrade = mode ? mode === 'downgrade' : info.newCost < info.currentCost;
  const isYearly = info.billingCycle === 'yearly';
  const displayCurrentCost = info.currentCost;
  const displayNewCost = info.newCost;

  // One-shot charge on upgrade = the FULL new credit pack price (not a delta),
  // priced for the current billing cycle. Matches the backend's Option A flow,
  // which charges the new tier in full and grants the full pack credits:
  //   monthly: newCreditCost x 1
  //   yearly:  newCreditCost x 12
  // Falls back to the (newCost - currentCost) delta if newCreditCost was not
  // wired by the caller.
  const newCreditCostMonthly = info.newCreditCost ?? Math.max(0, info.newCost - info.currentCost);
  const chargeNowAmount = isYearly
    ? newCreditCostMonthly * 12
    : newCreditCostMonthly;

  const formattedPeriodEnd = info.currentPeriodEnd ? (() => {
    try {
      return formatUtcDate(info.currentPeriodEnd!);
    } catch {
      return info.currentPeriodEnd;
    }
  })() : null;

  const formattedSuccessDate = successDate ? (() => {
    try {
      return formatUtcDate(successDate);
    } catch {
      return successDate;
    }
  })() : null;

  // Success state
  if (success) {
    return (
      <Dialog open={isOpen} onOpenChange={(o) => !o && onClose()}>
        <DialogContent className="max-w-md p-0 gap-0 overflow-hidden">
          <div className="p-6">
            <div className="text-center">
              <div className="w-16 h-16 bg-green-100 dark:bg-green-900/30 rounded-full flex items-center justify-center mx-auto mb-4">
                <Coins className="h-8 w-8 text-green-600 dark:text-green-400" />
              </div>
              <h2 className="text-lg font-semibold text-theme-primary mb-2">
                {isDowngrade ? t('successDowngradeTitle') : t('successTitle')}
              </h2>
              <p className="text-sm text-theme-secondary mb-6">
                {isDowngrade
                  ? (formattedSuccessDate
                    ? t('successDowngradeDescription', { credits: info.newCredits.toLocaleString(getClientLocale()), date: formattedSuccessDate })
                    : t('successDowngradeDescriptionGeneric', { credits: info.newCredits.toLocaleString(getClientLocale()) }))
                  : t('successDescription', { credits: info.newCredits.toLocaleString(getClientLocale()) })
                }
              </p>
              <Button onClick={onClose} variant="contrast" className="w-full">
                {t('done')}
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    );
  }

  // Downgrade mode: amber warning style (like DowngradeConfirmModal)
  if (isDowngrade) {
    return (
      <Dialog open={isOpen} onOpenChange={(o) => !o && !loading && onClose()}>
        <DialogContent className="max-w-md p-0 gap-0 overflow-hidden">
          {/* Header */}
          <div className="p-6 pb-4">
            <div className="flex items-center gap-3">
              <div className="p-2 bg-amber-100 dark:bg-amber-900/50 rounded-full">
                <ArrowDown className="h-5 w-5 text-amber-600 dark:text-amber-400" />
              </div>
              <h2 className="text-lg font-semibold text-theme-primary">
                {t('downgradeTitle')}
              </h2>
            </div>
          </div>

          {/* Content */}
          <div className="p-6 pt-0">
            {/* Credit transition */}
            <div className="flex items-center justify-center gap-4 mb-6">
              <div className="text-center">
                <div className="text-sm text-theme-secondary mb-1">{t('current')}</div>
                <div className="font-semibold text-theme-primary">
                  {isCeMode ? `$${info.currentCredits.toLocaleString(getClientLocale())}` : `${info.currentCredits.toLocaleString(getClientLocale())} ${t('credits')}`}
                </div>
                <div className="text-sm text-theme-secondary">
                  ${displayCurrentCost}/{t('mo')}
                </div>
                {isYearly && (
                  <div className="text-xs text-theme-muted">
                    (${displayCurrentCost * 12}/{t('yr')})
                  </div>
                )}
              </div>

              <ArrowDown className="h-6 w-6 text-amber-600 dark:text-amber-400 -rotate-90" />

              <div className="text-center">
                <div className="text-sm text-theme-secondary mb-1">{t('new')}</div>
                <div className="font-semibold text-amber-600 dark:text-amber-400">
                  {isCeMode ? `$${info.newCredits.toLocaleString(getClientLocale())}` : `${info.newCredits.toLocaleString(getClientLocale())} ${t('credits')}`}
                </div>
                <div className="text-sm text-theme-secondary">
                  ${displayNewCost}/{t('mo')}
                </div>
                {isYearly && (
                  <div className="text-xs text-theme-muted">
                    (${displayNewCost * 12}/{t('yr')})
                  </div>
                )}
              </div>
            </div>

            {/* Tax note */}
            <p className="text-center text-sm text-theme-muted -mt-4 mb-6">
              {tBilling('taxNote')}
            </p>

            {/* Warning: scheduled change */}
            <div className="bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700 rounded-lg p-4 mb-6">
              <div className="flex items-start gap-3">
                <AlertTriangle className="h-5 w-5 text-amber-600 dark:text-amber-400 flex-shrink-0 mt-0.5" />
                <div>
                  <p className="text-sm text-amber-900 dark:text-amber-100 font-medium mb-1">
                    {t('effectiveAtRenewal')}
                  </p>
                  <p className="text-sm text-amber-800 dark:text-amber-200">
                    {formattedPeriodEnd
                      ? t('downgradeDescription', { credits: info.newCredits.toLocaleString(getClientLocale()), date: formattedPeriodEnd })
                      : t('downgradeDescriptionGeneric', { credits: info.newCredits.toLocaleString(getClientLocale()) })
                    }
                  </p>
                </div>
              </div>
            </div>

            {/* Keep current credits notice */}
            <div className="bg-theme-secondary border border-theme rounded-lg p-4 mb-6">
              <p className="text-sm font-medium text-theme-primary mb-2">
                {t('keepCreditsTitle')}
              </p>
              <ul className="space-y-1 text-sm text-theme-secondary">
                <li>• {t('keepCreditsUntilRenewal', { credits: info.currentCredits.toLocaleString(getClientLocale()) })}</li>
                <li>• {t('cancelAnytime')}</li>
              </ul>
            </div>
          </div>

          {errorMessage && (
            <div className="px-6 pb-4">
              <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 rounded-lg p-3">
                <p className="text-sm text-red-800 dark:text-red-200">{errorMessage}</p>
              </div>
            </div>
          )}

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
              className="flex-1 bg-amber-600 text-white shadow-[0_10px_28px_rgba(217,119,6,0.3)] hover:bg-amber-700 hover:text-white hover:shadow-[0_12px_32px_rgba(217,119,6,0.35)]"
            >
              {loading ? (
                <>
                  <LoadingSpinner size="xs" />
                  {t('processing')}
                </>
              ) : (
                t('confirmDowngrade')
              )}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    );
  }

  // Upgrade mode: neutral/sober style
  return (
    <Dialog open={isOpen} onOpenChange={(o) => !o && !loading && onClose()}>
      <DialogContent className="max-w-md p-0 gap-0 overflow-hidden">
        {/* Header */}
        <div className="p-6 pb-4">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-full bg-theme-tertiary">
              <ArrowUp className="h-5 w-5 text-theme-secondary" />
            </div>
            <h2 className="text-lg font-semibold text-theme-primary">
              {t('upgradeTitle')}
            </h2>
          </div>
        </div>

        {/* Content */}
        <div className="p-6 pt-0">
          {/* Credit transition */}
          <div className="flex items-center justify-center gap-4 mb-6">
            <div className="text-center">
              <div className="text-sm text-theme-secondary mb-1">{t('current')}</div>
              <div className="font-semibold text-theme-primary">
                {isCeMode ? `$${info.currentCredits.toLocaleString(getClientLocale())}` : `${info.currentCredits.toLocaleString(getClientLocale())} ${t('credits')}`}
              </div>
              <div className="text-sm text-theme-secondary">
                ${displayCurrentCost}/{t('mo')}
              </div>
              {isYearly && (
                <div className="text-xs text-theme-muted">
                  (${displayCurrentCost * 12}/{t('yr')})
                </div>
              )}
            </div>

            <ArrowRight className="h-6 w-6 text-theme-muted" />

            <div className="text-center">
              <div className="text-sm text-theme-secondary mb-1">{t('new')}</div>
              <div className="font-semibold text-theme-primary">
                {isCeMode ? `$${info.newCredits.toLocaleString(getClientLocale())}` : `${info.newCredits.toLocaleString(getClientLocale())} ${t('credits')}`}
              </div>
              <div className="text-sm text-theme-secondary">
                ${displayNewCost}/{t('mo')}
              </div>
              {isYearly && (
                <div className="text-xs text-theme-muted">
                  (${displayNewCost * 12}/{t('yr')})
                </div>
              )}
            </div>
          </div>

          {/* Info box - 4 sober rows, no alert/amber styling. Renewal reset is the
              standard prepaid-pack behaviour, surfaced informatively (not as a warning). */}
          <div className="rounded-lg p-4 mb-6 bg-theme-secondary border border-theme space-y-4">
            <div className="flex items-start gap-3">
              <Sparkles className="h-5 w-5 flex-shrink-0 mt-0.5 text-theme-muted" />
              <div>
                <p className="text-sm font-medium mb-1 text-theme-primary">
                  {t('upgradeAvailableNowTitle')}
                </p>
                <p className="text-sm text-theme-secondary">
                  {t('upgradeAvailableNowDescription', { credits: info.newCredits.toLocaleString(getClientLocale()) })}
                </p>
              </div>
            </div>
            <div className="flex items-start gap-3">
              <Coins className="h-5 w-5 flex-shrink-0 mt-0.5 text-theme-muted" />
              <div>
                <p className="text-sm text-theme-secondary">
                  {t('upgradeKeepCredits')}
                </p>
              </div>
            </div>
            <div className="flex items-start gap-3">
              <CreditCard className="h-5 w-5 flex-shrink-0 mt-0.5 text-theme-muted" />
              <div>
                <p className="text-sm font-medium mb-1 text-theme-primary">
                  {t('upgradeChargeTitle', { amount: chargeNowAmount })}
                </p>
                <p className="text-sm text-theme-secondary">
                  {t('upgradeChargeDescription')}
                </p>
                <p className="text-sm text-theme-muted mt-1">
                  {tBilling('taxNote')}
                </p>
              </div>
            </div>
            <div className="flex items-start gap-3">
              <RefreshCcw className="h-5 w-5 flex-shrink-0 mt-0.5 text-theme-muted" />
              <div>
                <p className="text-sm font-medium mb-1 text-theme-primary">
                  {t('upgradeRenewalResetTitle')}
                </p>
                <p className="text-sm text-theme-secondary">
                  {formattedPeriodEnd
                    ? t('upgradeRenewalResetDescription', { date: formattedPeriodEnd, credits: info.newCredits.toLocaleString(getClientLocale()) })
                    : t('upgradeRenewalResetDescriptionGeneric', { credits: info.newCredits.toLocaleString(getClientLocale()) })}
                </p>
              </div>
            </div>
          </div>
        </div>

        {errorMessage && (
          <div className="px-6 pb-4">
            <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 rounded-lg p-3">
              <p className="text-sm text-red-800 dark:text-red-200">{errorMessage}</p>
            </div>
          </div>
        )}

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

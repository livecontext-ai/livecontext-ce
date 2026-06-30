'use client';

import React, { useState } from 'react';
import { RefreshCw, Calendar, CreditCard, CheckCircle2, ArrowRight } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

interface BillingCycleChangeModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSuccess?: (effectiveDate: string) => void;
    currentCycle: 'monthly' | 'yearly';
    planName: string;
    monthlyPrice: number;
    yearlyPrice: number;
    currentPeriodEnd?: string;
}

export default function BillingCycleChangeModal({
    isOpen,
    onClose,
    onSuccess,
    currentCycle,
    planName,
    monthlyPrice,
    yearlyPrice,
    currentPeriodEnd
}: BillingCycleChangeModalProps) {
    const t = useTranslations('modals.billingCycle');
    const tBilling = useTranslations('pricing.billing');
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState(false);
    const [effectiveDate, setEffectiveDate] = useState<string | null>(null);

    // Reset internal state every time the modal opens
    React.useEffect(() => {
        if (isOpen) {
            setSuccess(false);
            setEffectiveDate(null);
            setError(null);
            setIsLoading(false);
        }
    }, [isOpen]);

    const targetCycle = currentCycle === 'monthly' ? 'yearly' : 'monthly';
    const isUpgradingToYearly = targetCycle === 'yearly';
    const yearlyPricePerMonth = Math.round(yearlyPrice / 12);

    // Prices displayed per month
    const currentDisplayPrice = currentCycle === 'monthly' ? monthlyPrice : yearlyPricePerMonth;
    const targetDisplayPrice = isUpgradingToYearly ? yearlyPricePerMonth : monthlyPrice;

    const handleConfirm = async () => {
        try {
            setIsLoading(true);
            setError(null);

            const result = await unifiedApiService.changeBillingCycle(targetCycle);

            if (result.success) {
                setSuccess(true);
                setEffectiveDate(result.effectiveDate || null);
            } else {
                setError(result.message || t('scheduleFailed'));
            }
        } catch (err: any) {
            setError(err.message || t('error'));
        } finally {
            setIsLoading(false);
        }
    };

    const handleClose = () => {
        if (success) {
            onSuccess?.(effectiveDate || new Date().toISOString());
        }
        setError(null);
        setSuccess(false);
        setEffectiveDate(null);
        onClose();
    };

    const formatDate = (dateStr: string | undefined | null) => {
        if (!dateStr) return t('endOfBillingPeriod');
        try {
            return formatUtcDate(dateStr);
        } catch {
            return dateStr;
        }
    };

    return (
        <Dialog open={isOpen} onOpenChange={(o) => !o && !isLoading && handleClose()}>
            <DialogContent className="max-w-md p-0 gap-0 overflow-hidden">
                {/* Header */}
                <div className="p-6 pb-4">
                    <div className="flex items-center gap-3">
                        <div className="p-2 rounded-full bg-theme-tertiary">
                            <RefreshCw className="h-5 w-5 text-theme-secondary" />
                        </div>
                        <h2 className="text-lg font-semibold text-theme-primary">
                            {success
                                ? (isUpgradingToYearly ? t('changeApplied') : t('changeScheduled'))
                                : t('title')
                            }
                        </h2>
                    </div>
                </div>

                {/* Content */}
                <div className="p-6">
                    {success ? (
                        // Success state
                        <div className="text-center">
                            <div className="w-16 h-16 bg-green-100 dark:bg-green-900/30 rounded-full flex items-center justify-center mx-auto mb-4">
                                <CheckCircle2 className="h-8 w-8 text-green-600 dark:text-green-400" />
                            </div>
                            <p className="text-theme-primary mb-4">
                                {isUpgradingToYearly ? t('successImmediateMessage') : t('successMessage')}
                            </p>
                            {!isUpgradingToYearly && effectiveDate && (
                                <p className="text-xl font-semibold text-theme-primary mb-4">
                                    {t('effective', { date: formatDate(effectiveDate) })}
                                </p>
                            )}
                            <p className="text-sm text-theme-secondary">
                                {t('planWillSwitch', { plan: planName, cycle: t(targetCycle) })}
                            </p>
                        </div>
                    ) : (
                        // Confirmation state
                        <>
                            {/* Cycle comparison */}
                            <div className="flex items-center justify-center gap-4 mb-6">
                                <div className="text-center">
                                    <div className="text-sm text-theme-secondary mb-1">{t('current')}</div>
                                    <div className="font-semibold text-theme-primary">
                                        {t(currentCycle)}
                                    </div>
                                    <div className="text-sm text-theme-secondary">
                                        ${currentDisplayPrice}/{t('mo')}
                                    </div>
                                </div>

                                <ArrowRight className="h-6 w-6 text-theme-secondary" />

                                <div className="text-center">
                                    <div className="text-sm text-theme-secondary mb-1 flex items-center gap-1 justify-center">
                                        {t('new')}
                                        {isUpgradingToYearly && (
                                            <span className="text-theme-primary font-semibold">
                                                {t('save20')}
                                            </span>
                                        )}
                                    </div>
                                    <div className="font-semibold text-theme-primary">
                                        {t(targetCycle)}
                                    </div>
                                    <div className="text-sm text-theme-secondary">
                                        ${targetDisplayPrice}/{t('mo')}
                                    </div>
                                </div>
                            </div>

                            <p className="text-center text-sm text-theme-secondary -mt-4 mb-6">
                                {tBilling('taxNote')}
                            </p>

                            {/* Info box */}
                            <div className="rounded-lg p-4 mb-6 bg-theme-secondary border border-theme">
                                {isUpgradingToYearly ? (
                                    <>
                                        <div className="flex items-start gap-3 mb-3">
                                            <Calendar className="h-5 w-5 flex-shrink-0 mt-0.5 text-theme-secondary" />
                                            <div>
                                                <p className="text-sm font-medium mb-1 text-theme-primary">
                                                    {t('effectiveImmediately')}
                                                </p>
                                            </div>
                                        </div>
                                        <div className="flex items-start gap-3">
                                            <CreditCard className="h-5 w-5 flex-shrink-0 mt-0.5 text-theme-secondary" />
                                            <div>
                                                <p className="text-sm text-theme-secondary">
                                                    {t('yearlyImmediateInfo')}
                                                </p>
                                            </div>
                                        </div>
                                    </>
                                ) : (
                                    <div className="flex items-start gap-3">
                                        <Calendar className="h-5 w-5 flex-shrink-0 mt-0.5 text-theme-secondary" />
                                        <div>
                                            <p className="text-sm font-medium mb-1 text-theme-primary">
                                                {t('scheduledFor', { date: formatDate(currentPeriodEnd) })}
                                            </p>
                                            <p className="text-sm text-theme-secondary">
                                                {t('monthlyBillingInfo')}
                                            </p>
                                        </div>
                                    </div>
                                )}
                            </div>

                            {error && (
                                <div className="mb-4 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 rounded-lg">
                                    <p className="text-sm text-red-700 dark:text-red-300">
                                        {error}
                                    </p>
                                </div>
                            )}
                        </>
                    )}
                </div>

                {/* Footer */}
                <div className="p-6 pt-0 flex gap-3">
                    {success ? (
                        <Button
                            onClick={handleClose}
                            variant="contrast"
                            className="flex-1"
                        >
                            {t('done')}
                        </Button>
                    ) : (
                        <>
                            <Button
                                onClick={handleClose}
                                disabled={isLoading}
                                variant="outline"
                                className="flex-1"
                            >
                                {t('cancel')}
                            </Button>
                            <Button
                                onClick={handleConfirm}
                                disabled={isLoading}
                                variant="contrast"
                                className="flex-1"
                            >
                                {isLoading ? (
                                    <>
                                        <LoadingSpinner size="xs" />
                                        {isUpgradingToYearly ? t('confirming') : t('scheduling')}
                                    </>
                                ) : (
                                    isUpgradingToYearly
                                        ? t('confirm')
                                        : t('switchTo', { cycle: t(targetCycle) })
                                )}
                            </Button>
                        </>
                    )}
                </div>
            </DialogContent>
        </Dialog>
    );
}

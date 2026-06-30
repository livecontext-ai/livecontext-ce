'use client';

import React, { useState } from 'react';
import { AlertTriangle, ArrowDown, Calendar } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

interface DowngradeConfirmModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSuccess?: (effectiveDate: string) => void;
    currentPlan: {
        code: string;
        name: string;
        price?: number;
    };
    targetPlan: {
        code: string;
        name: string;
        price?: number;
    };
    currentPeriodEnd?: string;
    billingCycle?: 'monthly' | 'yearly';
}

export default function DowngradeConfirmModal({
    isOpen,
    onClose,
    onSuccess,
    currentPlan,
    targetPlan,
    currentPeriodEnd,
    billingCycle = 'monthly'
}: DowngradeConfirmModalProps) {
    const t = useTranslations('modals.downgrade');
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

    const handleConfirm = async () => {
        try {
            setIsLoading(true);
            setError(null);

            const result = await unifiedApiService.scheduleDowngrade(targetPlan.code);

            if (result.success) {
                setSuccess(true);
                setEffectiveDate(result.effectiveDate || null);
                // Don't call onSuccess here - let user see success state and click "Got it"
            } else {
                setError(result.message || t('errorTitle'));
            }
        } catch (err: any) {
            setError(err.message || t('errorMessage'));
        } finally {
            setIsLoading(false);
        }
    };

    const handleClose = () => {
        // If closing after a successful downgrade, notify parent
        if (success && effectiveDate) {
            onSuccess?.(effectiveDate);
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
                        <div className="p-2 bg-amber-100 dark:bg-amber-900/50 rounded-full">
                            <ArrowDown className="h-5 w-5 text-amber-600 dark:text-amber-400" />
                        </div>
                        <h2 className="text-lg font-semibold text-theme-primary">
                            {success ? t('titleScheduled') : t('title')}
                        </h2>
                    </div>
                </div>

                {/* Content */}
                <div className="p-6">
                    {success ? (
                        // Success state
                        <div className="text-center">
                            <div className="w-16 h-16 bg-green-100 dark:bg-green-900/30 rounded-full flex items-center justify-center mx-auto mb-4">
                                <Calendar className="h-8 w-8 text-green-600 dark:text-green-400" />
                            </div>
                            <p className="text-theme-primary mb-4">
                                {t('successMessage', { plan: currentPlan.name })}
                            </p>
                            <p className="text-xl font-semibold text-theme-primary mb-4">
                                {formatDate(effectiveDate)}
                            </p>
                            <p className="text-sm text-theme-secondary">
                                {t('afterDate', { plan: targetPlan.name })}
                            </p>
                        </div>
                    ) : (
                        // Confirmation state
                        <>
                            {/* Plan transition */}
                            <div className="flex items-center justify-center gap-4 mb-6">
                                <div className="text-center">
                                    <div className="text-sm text-theme-secondary mb-1">{t('current')}</div>
                                    <div className="font-semibold text-theme-primary">{currentPlan.name}</div>
                                    {currentPlan.price !== undefined && (
                                        <>
                                            <div className="text-sm text-theme-secondary">
                                                {t('perMonth', { price: currentPlan.price })}
                                            </div>
                                        </>
                                    )}
                                </div>

                                <ArrowDown className="h-6 w-6 text-amber-600 dark:text-amber-400 -rotate-90" />

                                <div className="text-center">
                                    <div className="text-sm text-theme-secondary mb-1">{t('new')}</div>
                                    <div className="font-semibold text-amber-600 dark:text-amber-400">{targetPlan.name}</div>
                                    {targetPlan.price !== undefined && (
                                        <>
                                            <div className="text-sm text-theme-secondary">
                                                {t('perMonth', { price: targetPlan.price })}
                                            </div>
                                        </>
                                    )}
                                </div>
                            </div>

                            <p className="text-center text-sm text-theme-secondary -mt-4 mb-6">
                                {tBilling('taxNote')}
                            </p>

                            {/* Warning box */}
                            <div className="bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700 rounded-lg p-4 mb-6">
                                <div className="flex items-start gap-3">
                                    <AlertTriangle className="h-5 w-5 text-amber-600 dark:text-amber-400 flex-shrink-0 mt-0.5" />
                                    <div>
                                        <p className="text-sm text-amber-900 dark:text-amber-100 font-medium mb-1">
                                            {t('scheduledFor')}
                                        </p>
                                        <p className="text-sm text-amber-800 dark:text-amber-200">
                                            {t('scheduledForDate', { date: formatDate(currentPeriodEnd) })}
                                        </p>
                                    </div>
                                </div>
                            </div>

                            {/* Features you'll lose */}
                            <div className="bg-theme-secondary border border-theme rounded-lg p-4 mb-6">
                                <p className="text-sm font-medium text-theme-primary mb-2">
                                    {t('whatChanges')}
                                </p>
                                <ul className="space-y-1 text-sm text-theme-secondary">
                                    <li>• {t('featuresRemoved', { plan: currentPlan.name })}</li>
                                    <li>• {t('dataArchived')}</li>
                                    <li>• {t('upgradeAgain')}</li>
                                </ul>
                            </div>

                            {error && (
                                <div className="mb-4 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 rounded-lg">
                                    <p className="text-sm text-red-700 dark:text-red-300 flex items-center gap-2">
                                        <AlertTriangle className="h-4 w-4" />
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
                            {t('gotIt')}
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
                                className="flex-1 bg-amber-600 text-white shadow-[0_10px_28px_rgba(217,119,6,0.3)] hover:bg-amber-700 hover:text-white hover:shadow-[0_12px_32px_rgba(217,119,6,0.35)]"
                            >
                                {isLoading ? (
                                    <>
                                        <LoadingSpinner size="xs" />
                                        {t('scheduling')}
                                    </>
                                ) : (
                                    t('confirm')
                                )}
                            </Button>
                        </>
                    )}
                </div>
            </DialogContent>
        </Dialog>
    );
}

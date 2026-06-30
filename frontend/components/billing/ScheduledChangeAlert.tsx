'use client';

import React, { useState, useEffect } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { Calendar, AlertTriangle, Clock, ArrowDown } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import { isCeMode } from '@/lib/format-cost';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { formatTierLabel } from '@/lib/billing/pricing-constants';

interface ScheduledChangeInfo {
    scheduleId: string;
    currentPlanCode: string;
    currentPlanName: string;
    targetPlanCode: string;
    targetPlanName: string;
    effectiveDate: string;
    changeType: 'downgrade' | 'billing_cycle_change' | 'credit_tier_change';
    currentBillingCycle?: string;
    targetBillingCycle?: string;
    status: string;
    cancellable: boolean;
    userMessage: string;
    currentCreditQty?: number;
    targetCreditQty?: number;
}

interface ScheduledChangeAlertProps {
    onCancel?: () => void;
    onRefresh?: () => void;
    className?: string;
    /** When the subscription has cancelAtPeriodEnd=true, show cancellation banner */
    cancelAtPeriodEnd?: boolean;
    /** The date the subscription will be cancelled */
    cancellationDate?: string;
    /** Called when user clicks "Keep my plan" to reactivate */
    onReactivate?: () => void;
}

export default function ScheduledChangeAlert({
    onCancel,
    onRefresh,
    className = '',
    cancelAtPeriodEnd,
    cancellationDate,
    onReactivate
}: ScheduledChangeAlertProps) {
    const t = useTranslations('modals.scheduledChange');
    const [scheduledChange, setScheduledChange] = useState<ScheduledChangeInfo | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [isCancelling, setIsCancelling] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [isReactivating, setIsReactivating] = useState(false);

    useEffect(() => {
        fetchScheduledChange();
    }, []);

    const fetchScheduledChange = async () => {
        try {
            setIsLoading(true);
            console.log('[ScheduledChangeAlert] Fetching scheduled change...');
            const response = await unifiedApiService.getScheduledChange();
            console.log('[ScheduledChangeAlert] Response:', response);
            if (response.hasScheduledChange && response.scheduledChange) {
                console.log('[ScheduledChangeAlert] Found scheduled change:', response.scheduledChange);
                setScheduledChange(response.scheduledChange);
            } else {
                console.log('[ScheduledChangeAlert] No scheduled change found');
                setScheduledChange(null);
            }
        } catch (err) {
            console.error('[ScheduledChangeAlert] Error fetching scheduled change:', err);
            setScheduledChange(null);
        } finally {
            setIsLoading(false);
        }
    };

    const handleCancelChange = async () => {
        if (!scheduledChange?.cancellable) return;

        try {
            setIsCancelling(true);
            setError(null);
            await unifiedApiService.cancelScheduledChange();
            setScheduledChange(null);
            onCancel?.();
            onRefresh?.();
        } catch (err: any) {
            setError(err.message || t('errorCancelling'));
        } finally {
            setIsCancelling(false);
        }
    };

    const formatDate = (dateStr: string) => {
        try {
            return formatUtcDate(dateStr);
        } catch {
            return dateStr;
        }
    };

    const handleReactivate = async () => {
        try {
            setIsReactivating(true);
            setError(null);
            await unifiedApiService.reactivateSubscription();
            onReactivate?.();
            onRefresh?.();
        } catch (err: any) {
            setError(err.message || t('errorCancelling'));
        } finally {
            setIsReactivating(false);
        }
    };

    // Show cancellation banner if subscription is scheduled for cancellation
    if (cancelAtPeriodEnd && cancellationDate) {
        return (
            <div className={`rounded-xl p-4 bg-red-50 border-red-200 dark:bg-red-900/20 dark:border-red-700 ${className}`}>
                <div className="flex items-start gap-3">
                    <div className="flex-shrink-0 p-2 rounded-full bg-red-100 dark:bg-red-800">
                        <AlertTriangle className="h-5 w-5 text-red-600 dark:text-red-400" />
                    </div>
                    <div className="flex-1 min-w-0">
                        <h3 className="text-sm font-semibold text-red-800 dark:text-red-200">
                            {t('cancellationScheduled')}
                        </h3>
                        <p className="mt-1 text-sm text-red-700 dark:text-red-300">
                            {t('cancellationMessage', { date: formatDate(cancellationDate) })}
                        </p>
                        {error && (
                            <p className="mt-2 text-xs text-red-600 dark:text-red-400 flex items-center gap-1">
                                <AlertTriangle className="h-3 w-3" />
                                {error}
                            </p>
                        )}
                    </div>
                    <Button
                        onClick={handleReactivate}
                        disabled={isReactivating}
                        size="sm"
                        className="flex-shrink-0 bg-red-600 text-white hover:bg-red-700 hover:text-white"
                    >
                        {isReactivating ? (
                            <>
                                <LoadingSpinner size="xs" />
                                {t('cancelling')}
                            </>
                        ) : (
                            t('keepMyPlan')
                        )}
                    </Button>
                </div>
            </div>
        );
    }

    if (isLoading) {
        return null; // Don't show anything while loading
    }

    if (!scheduledChange) {
        return null;
    }

    const isDowngrade = scheduledChange.changeType === 'downgrade';
    const isBillingCycleChange = scheduledChange.changeType === 'billing_cycle_change';
    const isCreditTierChange = scheduledChange.changeType === 'credit_tier_change';
    const isWarningStyle = isDowngrade || isCreditTierChange;

    console.log('[ScheduledChangeAlert] Rendering:', {
        changeType: scheduledChange.changeType,
        userMessage: scheduledChange.userMessage,
        currentPlanName: scheduledChange.currentPlanName,
        targetPlanName: scheduledChange.targetPlanName
    });

    // Determine button text based on change type
    const getButtonText = () => {
        if (isDowngrade) return t('cancelDowngrade');
        if (isCreditTierChange) return t('cancelChange');
        if (isBillingCycleChange) return t('cancelChange');
        return t('cancel');
    };

    // Determine title based on change type
    const getTitle = () => {
        if (isDowngrade) return t('downgradeScheduled');
        if (isCreditTierChange) return t('creditTierChangeScheduled');
        if (isBillingCycleChange) return t('billingCycleChange');
        return t('planChangeScheduled');
    };

    // Hide from/to for credit tier change if credit quantities are not available (same plan name is not useful)
    const showFromTo = !(isCreditTierChange && scheduledChange.currentCreditQty == null);

    // For billing cycle change, extract cycles from userMessage if not provided directly
    const extractBillingCycles = () => {
        // Try to extract from userMessage: "Your billing cycle will change from monthly to yearly on..."
        const match = scheduledChange.userMessage.match(/from\s+(\w+)\s+to\s+(\w+)/i);
        if (match) {
            return {
                from: match[1].charAt(0).toUpperCase() + match[1].slice(1),
                to: match[2].charAt(0).toUpperCase() + match[2].slice(1)
            };
        }
        return null;
    };

    const billingCycles = isBillingCycleChange ? extractBillingCycles() : null;

    // Format credit quantity for display
    const formatCredits = (qty: number) => {
        if (isCeMode) return `$${qty.toLocaleString(getClientLocale())}`;
        return formatTierLabel(qty) + ' ' + t('credits');
    };

    // Determine what to display for From/To labels
    const getFromLabel = () => {
        if (isCreditTierChange && scheduledChange.currentCreditQty != null) {
            return formatCredits(scheduledChange.currentCreditQty);
        }
        if (isBillingCycleChange) {
            if (scheduledChange.currentBillingCycle) {
                return scheduledChange.currentBillingCycle.charAt(0).toUpperCase() + scheduledChange.currentBillingCycle.slice(1);
            }
            if (billingCycles) {
                return billingCycles.from;
            }
        }
        return scheduledChange.currentPlanName;
    };

    const getToLabel = () => {
        if (isCreditTierChange && scheduledChange.targetCreditQty != null) {
            return formatCredits(scheduledChange.targetCreditQty);
        }
        if (isBillingCycleChange) {
            if (scheduledChange.targetBillingCycle) {
                return scheduledChange.targetBillingCycle.charAt(0).toUpperCase() + scheduledChange.targetBillingCycle.slice(1);
            }
            if (billingCycles) {
                return billingCycles.to;
            }
        }
        return scheduledChange.targetPlanName;
    };

    return (
        <div className={`rounded-xl p-4 ${isWarningStyle
            ? 'bg-amber-50 border-amber-200 dark:bg-amber-900/20 dark:border-amber-700'
            : 'bg-gray-50 border-gray-200 dark:bg-gray-900/50 dark:border-gray-700'
            } ${className}`}>
            <div className="flex items-start gap-3">
                {/* Icon */}
                <div className={`flex-shrink-0 p-2 rounded-full ${isWarningStyle
                    ? 'bg-amber-100 dark:bg-amber-800'
                    : 'bg-gray-100 dark:bg-gray-700'
                    }`}>
                    {isWarningStyle ? (
                        <ArrowDown className="h-5 w-5 text-amber-600 dark:text-amber-400" />
                    ) : (
                        <Calendar className="h-5 w-5 text-gray-600 dark:text-gray-300" />
                    )}
                </div>

                {/* Content */}
                <div className="flex-1 min-w-0">
                    <h3 className={`text-sm font-semibold ${isWarningStyle
                        ? 'text-amber-800 dark:text-amber-200'
                        : 'text-gray-900 dark:text-white'
                        }`}>
                        {getTitle()}
                    </h3>

                    <p className={`mt-1 text-sm ${isWarningStyle
                        ? 'text-amber-700 dark:text-amber-300'
                        : 'text-gray-700 dark:text-gray-300'
                        }`}>
                        {scheduledChange.userMessage}
                    </p>

                    <div className="mt-3 flex flex-wrap items-center gap-4 text-xs">
                        {showFromTo && (
                            <>
                                {/* From */}
                                <div className="flex items-center gap-1.5">
                                    <span className="text-gray-500 dark:text-gray-400">{t('from')}</span>
                                    <span className="font-medium text-gray-700 dark:text-gray-300">
                                        {getFromLabel()}
                                    </span>
                                </div>

                                {/* Arrow */}
                                <ArrowDown className="h-3 w-3 text-gray-400 -rotate-90" />

                                {/* To */}
                                <div className="flex items-center gap-1.5">
                                    <span className="text-gray-500 dark:text-gray-400">{t('to')}</span>
                                    <span className={`font-medium ${isWarningStyle
                                        ? 'text-amber-700 dark:text-amber-300'
                                        : 'text-gray-900 dark:text-white'
                                        }`}>
                                        {getToLabel()}
                                    </span>
                                </div>
                            </>
                        )}

                        {/* Date */}
                        <div className="flex items-center gap-1.5">
                            <Clock className="h-3 w-3 text-gray-400" />
                            <span className="text-gray-600 dark:text-gray-400">
                                {formatDate(scheduledChange.effectiveDate)}
                            </span>
                        </div>
                    </div>

                    {error && (
                        <p className="mt-2 text-xs text-red-600 dark:text-red-400 flex items-center gap-1">
                            <AlertTriangle className="h-3 w-3" />
                            {error}
                        </p>
                    )}
                </div>

                {/* Cancel Button */}
                {scheduledChange.cancellable && (
                    <Button
                        onClick={handleCancelChange}
                        disabled={isCancelling}
                        size="sm"
                        className={`flex-shrink-0 ${isWarningStyle
                            ? 'bg-amber-600 text-white hover:bg-amber-700 hover:text-white'
                            : ''
                        }`}
                        variant={isWarningStyle ? undefined : 'default'}
                    >
                        {isCancelling ? (
                            <>
                                <LoadingSpinner size="xs" />
                                {t('cancelling')}
                            </>
                        ) : (
                            getButtonText()
                        )}
                    </Button>
                )}
            </div>
        </div>
    );
}

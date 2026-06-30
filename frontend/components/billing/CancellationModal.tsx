'use client';

import React, { useState } from 'react';
import { AlertTriangle, Calendar, Heart } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

type CancellationReason = 'too_expensive' | 'not_using' | 'missing_features' | 'switching' | 'other';
type Step = 'reason' | 'retention' | 'confirm' | 'success';

interface CancellationModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSuccess?: () => void;
    planName: string;
    currentPeriodEnd?: string;
}

export default function CancellationModal({
    isOpen,
    onClose,
    onSuccess,
    planName,
    currentPeriodEnd
}: CancellationModalProps) {
    const t = useTranslations('modals.cancellation');
    const [step, setStep] = useState<Step>('reason');
    const [reason, setReason] = useState<CancellationReason | null>(null);
    const [feedback, setFeedback] = useState('');
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [effectiveDate, setEffectiveDate] = useState<string | null>(null);

    // Reset internal state every time the modal opens
    React.useEffect(() => {
        if (isOpen) {
            setStep('reason');
            setReason(null);
            setFeedback('');
            setIsLoading(false);
            setError(null);
            setEffectiveDate(null);
        }
    }, [isOpen]);

    const reasons: { key: CancellationReason; label: string }[] = [
        { key: 'too_expensive', label: t('reasons.tooExpensive') },
        { key: 'not_using', label: t('reasons.notUsing') },
        { key: 'missing_features', label: t('reasons.missingFeatures') },
        { key: 'switching', label: t('reasons.switching') },
        { key: 'other', label: t('reasons.other') },
    ];

    const formatDate = (dateStr: string | undefined | null) => {
        if (!dateStr) return t('endOfBillingPeriod');
        try {
            return formatUtcDate(dateStr);
        } catch {
            return dateStr;
        }
    };

    const handleConfirmCancellation = async () => {
        if (!reason) return;
        try {
            setIsLoading(true);
            setError(null);
            const result = await unifiedApiService.cancelSubscription(reason, feedback || undefined);
            if (result.success) {
                setEffectiveDate(result.effectiveDate || currentPeriodEnd || null);
                setStep('success');
            } else {
                setError(result.message || t('errorGeneric'));
            }
        } catch (err: any) {
            setError(err.message || t('errorGeneric'));
        } finally {
            setIsLoading(false);
        }
    };

    const handleClose = () => {
        if (step === 'success') {
            onSuccess?.();
        }
        onClose();
    };

    const getRetentionContent = () => {
        switch (reason) {
            case 'too_expensive':
                return {
                    title: t('retention.tooExpensive.title'),
                    message: t('retention.tooExpensive.message'),
                    actionLabel: t('retention.tooExpensive.action'),
                    hasAction: true,
                };
            case 'not_using':
                return {
                    title: t('retention.notUsing.title'),
                    message: t('retention.notUsing.message'),
                    actionLabel: t('retention.notUsing.action'),
                    hasAction: false,
                };
            case 'missing_features':
                return {
                    title: t('retention.missingFeatures.title'),
                    message: t('retention.missingFeatures.message'),
                    actionLabel: t('retention.missingFeatures.action'),
                    hasAction: false,
                };
            default:
                return {
                    title: t('retention.default.title'),
                    message: t('retention.default.message'),
                    actionLabel: null,
                    hasAction: false,
                };
        }
    };

    return (
        <Dialog open={isOpen} onOpenChange={(o) => !o && !isLoading && handleClose()}>
            <DialogContent className="max-w-sm p-0 gap-0 overflow-hidden">
                <div className="p-8 text-center">
                    {step === 'success' ? (
                        <>
                            {/* Success icon */}
                            <div className="w-16 h-16 bg-green-100 dark:bg-green-900/30 rounded-full flex items-center justify-center mx-auto mb-5">
                                <Calendar className="h-8 w-8 text-green-600 dark:text-green-400" />
                            </div>

                            <h2 className="text-xl font-semibold text-theme-primary mb-2">
                                {t('titleSuccess')}
                            </h2>

                            <p className="text-sm text-theme-secondary mb-2">
                                {t('successMessage', { date: formatDate(effectiveDate) })}
                            </p>

                            <p className="text-sm text-theme-muted mb-6">
                                {t('successReactivate')}
                            </p>

                            <Button
                                onClick={handleClose}
                                variant="contrast"
                                className="w-full"
                            >
                                {t('done')}
                            </Button>
                        </>
                    ) : step === 'reason' ? (
                        <>
                            {/* Red icon */}
                            <div className="w-16 h-16 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto mb-5">
                                <AlertTriangle className="h-8 w-8 text-red-600 dark:text-red-400" />
                            </div>

                            <h2 className="text-xl font-semibold text-theme-primary mb-2">
                                {t('titleReason')}
                            </h2>

                            <p className="text-sm text-theme-secondary mb-4">
                                {t('reasonSubtitle')}
                            </p>

                            <div className="space-y-2 mb-4 text-left">
                                {reasons.map((r) => (
                                    <button
                                        key={r.key}
                                        onClick={() => setReason(r.key)}
                                        className={`w-full text-left px-4 py-3 rounded-lg border text-sm transition-colors ${
                                            reason === r.key
                                                ? 'border-theme bg-theme-secondary text-theme-primary'
                                                : 'border-theme hover:bg-theme-secondary text-theme-secondary'
                                        }`}
                                    >
                                        {r.label}
                                    </button>
                                ))}
                            </div>

                            <div className="mb-6 text-left">
                                <label className="text-sm text-theme-secondary mb-1 block">
                                    {t('feedbackLabel')}
                                </label>
                                <textarea
                                    value={feedback}
                                    onChange={(e) => setFeedback(e.target.value)}
                                    placeholder={t('feedbackPlaceholder')}
                                    className="w-full px-3 py-2 text-sm rounded-lg border border-theme bg-theme-primary text-theme-primary placeholder-[var(--text-muted)] resize-none focus:outline-none focus:ring-2 focus:ring-[var(--text-secondary)]"
                                    rows={3}
                                />
                            </div>

                            <div className="flex gap-3">
                                <Button
                                    onClick={handleClose}
                                    variant="outline"
                                    className="flex-1"
                                >
                                    {t('cancel')}
                                </Button>
                                <Button
                                    onClick={() => setStep('retention')}
                                    disabled={!reason}
                                    variant="destructive"
                                    className="flex-1"
                                >
                                    {t('continue')}
                                </Button>
                            </div>
                        </>
                    ) : step === 'retention' ? (
                        <>
                            {/* Red icon */}
                            <div className="w-16 h-16 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto mb-5">
                                <Heart className="h-8 w-8 text-red-600 dark:text-red-400" />
                            </div>

                            <h2 className="text-xl font-semibold text-theme-primary mb-4">
                                {t('titleRetention')}
                            </h2>

                            {(() => {
                                const content = getRetentionContent();
                                return (
                                    <>
                                        <div className="bg-theme-secondary border border-theme rounded-lg p-4 mb-4 text-left">
                                            <p className="text-sm font-medium text-theme-primary mb-1">
                                                {content.title}
                                            </p>
                                            <p className="text-sm text-theme-secondary">
                                                {content.message}
                                            </p>
                                        </div>

                                        {content.hasAction && (
                                            <Button
                                                onClick={handleClose}
                                                variant="outline"
                                                className="w-full mb-3"
                                            >
                                                {content.actionLabel}
                                            </Button>
                                        )}
                                    </>
                                );
                            })()}

                            <div className="flex gap-3">
                                <Button
                                    onClick={() => setStep('reason')}
                                    variant="outline"
                                    className="flex-1"
                                >
                                    {t('back')}
                                </Button>
                                <button
                                    onClick={() => setStep('confirm')}
                                    className="flex-1 text-sm text-theme-muted hover:text-theme-primary underline underline-offset-2"
                                >
                                    {t('continueWithCancellation')}
                                </button>
                            </div>
                        </>
                    ) : (
                        <>
                            {/* Red icon */}
                            <div className="w-16 h-16 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto mb-5">
                                <AlertTriangle className="h-8 w-8 text-red-600 dark:text-red-400" />
                            </div>

                            <h2 className="text-xl font-semibold text-theme-primary mb-2">
                                {t('titleConfirm')}
                            </h2>

                            <p className="text-sm text-theme-secondary mb-4">
                                {t('effectiveDate', { date: formatDate(currentPeriodEnd) })}
                            </p>

                            {/* What happens */}
                            <div className="bg-theme-secondary border border-theme rounded-lg p-4 mb-6 text-left">
                                <p className="text-sm font-medium text-theme-primary mb-2">
                                    {t('whatHappens')}
                                </p>
                                <ul className="space-y-1 text-sm text-theme-secondary">
                                    <li>• {t('changes.accessRemoved')}</li>
                                    <li>• {t('changes.dataRetained')}</li>
                                    <li>• {t('changes.reactivateAnytime')}</li>
                                </ul>
                            </div>

                            {error && (
                                <p className="text-sm text-red-600 dark:text-red-400 mb-4 flex items-center justify-center gap-2">
                                    <AlertTriangle className="h-4 w-4" />
                                    {error}
                                </p>
                            )}

                            <div className="flex gap-3">
                                <Button
                                    onClick={handleClose}
                                    disabled={isLoading}
                                    variant="outline"
                                    className="flex-1"
                                >
                                    {t('keepMyPlan')}
                                </Button>
                                <Button
                                    onClick={handleConfirmCancellation}
                                    disabled={isLoading}
                                    variant="destructive"
                                    className="flex-1"
                                >
                                    {isLoading ? (
                                        <>
                                            <LoadingSpinner size="xs" />
                                            {t('processing')}
                                        </>
                                    ) : (
                                        t('confirmCancellation')
                                    )}
                                </Button>
                            </div>
                        </>
                    )}
                </div>
            </DialogContent>
        </Dialog>
    );
}

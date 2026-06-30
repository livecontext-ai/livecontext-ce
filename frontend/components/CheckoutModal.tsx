'use client';

import React from 'react';
import { CheckCircle, AlertTriangle, Clock, MessageSquare, Zap, RefreshCw, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

export type CheckoutModalType = 'success' | 'processing' | 'timeout' | 'error';

interface CheckoutModalProps {
  open: boolean;
  type: CheckoutModalType;
  onClose: () => void;
  onRetry?: () => void;
  onChat?: () => void;
  onTools?: () => void;
  onDashboard?: () => void;
  onPricing?: () => void;
  planName?: string;
  subscriptionDetails?: {
    status?: string;
    currentPeriodStart?: string;
    currentPeriodEnd?: string;
  };
  pollingAttempts?: number;
  lastCheck?: Date | null;
  message?: string;
  showChatButton?: boolean;
  showToolsButton?: boolean;
  showDashboardButton?: boolean;
  showPricingButton?: boolean;
  showRetryButton?: boolean;
}

export default function CheckoutModal({
  open,
  type,
  onClose,
  onRetry,
  onChat,
  onTools,
  onDashboard,
  onPricing,
  planName = 'PRO',
  subscriptionDetails,
  pollingAttempts = 0,
  lastCheck,
  message = '',
  showChatButton = true,
  showToolsButton = true,
  showDashboardButton = false,
  showPricingButton = false,
  showRetryButton = false
}: CheckoutModalProps) {
  const t = useTranslations('modals.checkout');

  const config = {
    success: {
      icon: CheckCircle,
      iconBg: 'bg-green-100 dark:bg-green-900/30',
      iconColor: 'text-green-600 dark:text-green-400',
      title: t('subscriptionActivated'),
      description: message || t('planActive', { plan: planName })
    },
    processing: {
      icon: Loader2,
      iconBg: 'bg-theme-tertiary',
      iconColor: 'text-theme-secondary',
      title: t('activating'),
      description: message || t('finalizingSubscription')
    },
    timeout: {
      icon: Clock,
      iconBg: 'bg-amber-100 dark:bg-amber-900/30',
      iconColor: 'text-amber-600 dark:text-amber-400',
      title: t('takingLonger'),
      description: message || t('confirmationEmail')
    },
    error: {
      icon: AlertTriangle,
      iconBg: 'bg-red-100 dark:bg-red-900/30',
      iconColor: 'text-red-600 dark:text-red-400',
      title: t('somethingWentWrong'),
      description: message || t('tryAgainOrContact')
    }
  };

  const { icon: Icon, iconBg, iconColor, title, description } = config[type];

  // During processing, prevent backdrop close so users don't accidentally
  // dismiss the modal mid-checkout.
  const isProcessing = type === 'processing';

  return (
    <Dialog open={open} onOpenChange={(o) => !o && !isProcessing && onClose()}>
      <DialogContent className="max-w-sm p-0 gap-0 overflow-hidden">
        <div className="p-8 text-center">
          {/* Icon */}
          <div className={`w-16 h-16 ${iconBg} rounded-full flex items-center justify-center mx-auto mb-5`}>
            <Icon className={`h-8 w-8 ${iconColor} ${type === 'processing' ? 'animate-spin' : ''}`} />
          </div>

          {/* Title */}
          <DialogTitle className="text-xl font-semibold text-theme-primary mb-2">
            {title}
          </DialogTitle>

          {/* Description */}
          <p className="text-sm text-theme-secondary mb-6">
            {description}
          </p>

          {/* Processing info */}
          {type === 'processing' && (
            <p className="text-xs text-theme-muted mb-6">
              {t('attempt', { current: pollingAttempts, total: 30 })}
            </p>
          )}

          {/* Success details */}
          {type === 'success' && subscriptionDetails?.currentPeriodEnd && (
            <p className="text-xs text-theme-muted mb-6">
              {t('activeUntil', { date: formatUtcDate(subscriptionDetails.currentPeriodEnd) })}
            </p>
          )}

          {/* Buttons */}
          <div className="flex gap-3">
            {type === 'success' && (
              <>
                <Button
                  onClick={() => window.location.href = '/app/chat'}
                  variant="contrast"
                  className="flex-1"
                >
                  <MessageSquare className="w-4 h-4" />
                  {t('chat')}
                </Button>
                {showToolsButton && onTools && (
                  <Button onClick={onTools} variant="outline" className="flex-1">
                    <Zap className="w-4 h-4" />
                    {t('tools')}
                  </Button>
                )}
              </>
            )}

            {type === 'processing' && (
              <p className="text-xs text-theme-muted w-full">
                {t('safeToClose')}
              </p>
            )}

            {(type === 'timeout' || type === 'error') && (
              <>
                <Button onClick={onClose} variant="outline" className="flex-1">
                  {t('close')}
                </Button>
                {showRetryButton && onRetry && (
                  <Button onClick={onRetry} variant="contrast" className="flex-1">
                    <RefreshCw className="w-4 h-4" />
                    {t('retry')}
                  </Button>
                )}
              </>
            )}

            {showDashboardButton && onDashboard && (
              <Button onClick={onDashboard} variant="contrast" className="flex-1">
                {t('dashboard')}
              </Button>
            )}

            {showPricingButton && onPricing && (
              <Button onClick={onPricing} variant="outline" className="flex-1">
                {t('pricing')}
              </Button>
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

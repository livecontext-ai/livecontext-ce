'use client';

import React from 'react';
import { CheckCircle, MessageSquare } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';

interface UpgradeSuccessModalProps {
  open: boolean;
  currentPlan: string;
  newPlan: string;
  onClose: () => void;
  disableRedirects?: boolean;
}

export default function UpgradeSuccessModal({
  open,
  currentPlan,
  newPlan,
  onClose,
  disableRedirects = false
}: UpgradeSuccessModalProps) {
  const t = useTranslations('modals.upgradeSuccess');

  const formatPlanName = (plan: string) => {
    return plan.replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase());
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-sm p-0 gap-0 overflow-hidden">
        <div className="p-8 text-center">
          <div className="w-16 h-16 bg-green-100 dark:bg-green-900/30 rounded-full flex items-center justify-center mx-auto mb-5">
            <CheckCircle className="h-8 w-8 text-green-600 dark:text-green-400" />
          </div>

          <h2 className="text-xl font-semibold text-theme-primary mb-2">
            {t('title')}
          </h2>

          <p className="text-sm text-theme-secondary mb-6">
            {t('nowOnPlan', { plan: formatPlanName(newPlan) })}
          </p>

          <Button
            onClick={() => {
              onClose();
              if (!disableRedirects) {
                window.location.href = '/app/chat';
              }
            }}
            variant="contrast"
            className="w-full"
          >
            <MessageSquare className="w-4 h-4" />
            {t('chat')}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

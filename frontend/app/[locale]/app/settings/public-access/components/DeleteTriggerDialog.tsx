'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, Trash2, ExternalLink } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import Link from 'next/link';

interface DeleteTriggerDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: () => void;
  isLoading?: boolean;
  triggerName: string;
  triggerType: 'webhook' | 'chat' | 'form' | 'schedule';
  triggerDetail?: string;
  workflowId?: string | null;
  workflowName?: string | null;
}

export function DeleteTriggerDialog({
  open,
  onOpenChange,
  onConfirm,
  isLoading,
  triggerName,
  triggerType,
  triggerDetail,
  workflowId,
  workflowName,
}: DeleteTriggerDialogProps) {
  const t = useTranslations('triggerSettings');
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  const handleClose = () => onOpenChange(false);

  if (!open || !mounted) return null;

  const titleKey = {
    webhook: 'deleteWebhookTitle',
    chat: 'deleteChatTitle',
    form: 'deleteFormTitle',
    schedule: 'deleteScheduleTitle',
  }[triggerType] as 'deleteWebhookTitle' | 'deleteChatTitle' | 'deleteFormTitle' | 'deleteScheduleTitle';
  const titleId = `delete-trigger-${triggerType}-title`;

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={handleClose}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-6 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        {/* Warning icon */}
        <div className="flex justify-center mb-4">
          <div className="w-14 h-14 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center">
            <AlertTriangle className="w-7 h-7 text-red-600 dark:text-red-400" />
          </div>
        </div>

        {/* Title */}
        <h3 id={titleId} className="text-lg font-semibold text-theme-primary text-center mb-3">
          {t(titleKey)}
        </h3>

        {/* Trigger info card */}
        <div className="p-3 bg-theme-secondary rounded-xl border border-theme mb-4">
          <p className="text-sm font-medium text-theme-primary">{triggerName}</p>
          {triggerDetail && (
            <p className="text-xs text-theme-secondary font-mono mt-1 truncate">{triggerDetail}</p>
          )}
        </div>

        {/* Workflow warning */}
        {workflowId ? (
          <div className="p-3 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800/50 rounded-xl mb-4">
            <p className="text-sm text-amber-800 dark:text-amber-300">
              {t('deleteWorkflowWarning', { name: workflowName || 'Workflow' })}
            </p>
            <Link
              href={`/app/workflow/${workflowId}`}
              className="inline-flex items-center gap-1 text-xs text-amber-700 dark:text-amber-400 hover:underline mt-1.5"
            >
              <ExternalLink className="h-3 w-3" />
              {t('viewWorkflow')}
            </Link>
          </div>
        ) : (
          <p className="text-sm text-theme-secondary text-center mb-4">
            {t('deleteOrphanMessage')}
          </p>
        )}

        {/* Actions */}
        <div className="flex gap-3">
          <Button
            variant="outline"
            onClick={handleClose}
            disabled={isLoading}
            className="flex-1"
          >
            {t('cancel')}
          </Button>
          <Button
            variant="destructive"
            onClick={onConfirm}
            disabled={isLoading}
            className="flex-1"
          >
            <Trash2 className="w-4 h-4 mr-2" />
            {t('delete')}
          </Button>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}

'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';

interface DeleteSharedLinkDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: () => void;
  isLoading?: boolean;
  linkTitle: string;
  resourceType: string;
}

export function DeleteSharedLinkDialog({
  open,
  onOpenChange,
  onConfirm,
  isLoading,
  linkTitle,
  resourceType,
}: DeleteSharedLinkDialogProps) {
  const t = useTranslations('triggerSettings');
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  const handleClose = () => onOpenChange(false);

  if (!open || !mounted) return null;
  const titleId = 'delete-shared-link-title';

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
          {t('deleteSharedLinkTitle')}
        </h3>

        {/* Link info card */}
        <div className="p-3 bg-theme-secondary rounded-xl border border-theme mb-4">
          <p className="text-sm font-medium text-theme-primary">{linkTitle}</p>
          <p className="text-xs text-theme-secondary mt-1">{resourceType}</p>
        </div>

        {/* Warning text */}
        <p className="text-sm text-theme-secondary text-center mb-4">
          {t('deleteSharedLinkMessage')}
        </p>

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

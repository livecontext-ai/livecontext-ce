'use client';

import React from 'react';
import { createPortal } from 'react-dom';
import { Trash2 } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';

interface ConfirmDeleteModalProps {
  isOpen: boolean;
  title: string;
  message: string;
  onConfirm: () => void;
  onCancel: () => void;
  isLoading?: boolean;
  /** Confirm-button label (default: common.delete). e.g. "Remove" for a disconnect. */
  confirmLabel?: string;
}

export function ConfirmDeleteModal({
  isOpen,
  title,
  message,
  onConfirm,
  onCancel,
  isLoading = false,
  confirmLabel,
}: ConfirmDeleteModalProps) {
  const t = useTranslations('common');

  if (!isOpen) return null;
  const titleId = 'confirm-delete-modal-title';

  return createPortal(
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onCancel}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        {/* Icon */}
        <div className="w-16 h-16 bg-red-100 dark:bg-red-950/40 rounded-full flex items-center justify-center mx-auto mb-5">
          <Trash2 className="h-8 w-8 text-red-600 dark:text-red-400" />
        </div>

        {/* Title */}
        <h2 id={titleId} className="text-2xl font-semibold text-theme-primary text-center mb-2">
          {title}
        </h2>

        {/* Message */}
        <p className="text-theme-secondary text-center mb-8">
          {message}
        </p>

        {/* Actions */}
        <div className="flex gap-3">
          <Button
            onClick={onCancel}
            disabled={isLoading}
            variant="outline"
            className="flex-1"
          >
            {t('cancel')}
          </Button>
          <Button
            onClick={onConfirm}
            disabled={isLoading}
            variant="destructive"
            className="flex-1"
          >
            {isLoading ? (
              <>
                <LoadingSpinner size="xs" />
                {t('deleting')}
              </>
            ) : (
              confirmLabel ?? t('delete')
            )}
          </Button>
        </div>
      </div>
    </div>,
    document.body
  );
}

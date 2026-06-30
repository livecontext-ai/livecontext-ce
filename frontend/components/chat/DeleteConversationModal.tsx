'use client';

import React from 'react';
import { createPortal } from 'react-dom';
import { Trash2, Eraser } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';

interface DeleteConversationModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  conversationTitle?: string;
  isLoading?: boolean;
  clearMode?: boolean;
}

export function DeleteConversationModal({
  isOpen,
  onClose,
  onConfirm,
  conversationTitle = 'this conversation',
  isLoading = false,
  clearMode = false
}: DeleteConversationModalProps) {
  const t = useTranslations('modals.deleteChat');

  if (!isOpen) return null;

  const Icon = clearMode ? Eraser : Trash2;
  const title = clearMode ? t('clearTitle') : t('title');
  const message = clearMode
    ? t('clearConfirmMessage', { title: conversationTitle })
    : t('confirmMessage', { title: conversationTitle });
  const confirmLabel = clearMode ? t('clear') : t('delete');
  const loadingLabel = clearMode ? t('clearing') : t('deleting');

  return createPortal(
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-conversation-title"
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Icon */}
        <div className={`w-16 h-16 ${clearMode ? 'bg-orange-100 dark:bg-orange-950/40' : 'bg-red-100 dark:bg-red-950/40'} rounded-full flex items-center justify-center mx-auto mb-5`}>
          <Icon className={`h-8 w-8 ${clearMode ? 'text-orange-600 dark:text-orange-400' : 'text-red-600 dark:text-red-400'}`} />
        </div>

        {/* Title */}
        <h2 id="delete-conversation-title" className="text-2xl font-semibold text-theme-primary text-center mb-2">
          {title}
        </h2>

        {/* Message */}
        <p className="text-theme-secondary text-center mb-8">
          {message}
        </p>

        {/* Actions */}
        <div className="flex gap-3">
          <Button
            onClick={onClose}
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
                {loadingLabel}
              </>
            ) : (
              confirmLabel
            )}
          </Button>
        </div>
      </div>
    </div>,
    document.body
  );
}

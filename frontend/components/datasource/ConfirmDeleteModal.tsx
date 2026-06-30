'use client';

import React from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { Trash2 } from 'lucide-react';
import { useTranslations } from 'next-intl';

interface ConfirmDeleteModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  /**
   * ICU-plural arguments for the default `common.deleteConfirmMessage` body.
   * REQUIRED unless `customBody` is supplied - then they're ignored.
   */
  itemCount?: number;
  itemType?: string;
  /**
   * Phase 2 additive extension. When supplied, replaces the default
   * `common.deleteConfirmMessage` body with a fully custom React node - used
   * by callers (e.g. the BYOK delete flow) that need to render a skeleton +
   * cascade-impact count + a "couldn't fetch impact" fallback in the same
   * slot. Mutually exclusive with `itemCount`/`itemType`.
   */
  customBody?: React.ReactNode;
  /**
   * Phase 2 additive: disable both buttons + show a dim state on the confirm
   * action while the parent's onConfirm is in flight. Default false keeps
   * existing callers' behavior unchanged.
   */
  isLoading?: boolean;
  /**
   * Phase 2 additive: override the confirm button label. Defaults to
   * `common.delete`. Used by the BYOK flow to show
   * "Delete and disconnect N accounts" inside the same component.
   */
  confirmLabel?: string;
}

export function ConfirmDeleteModal({
  isOpen,
  onClose,
  onConfirm,
  title,
  itemCount,
  itemType,
  customBody,
  isLoading = false,
  confirmLabel,
}: ConfirmDeleteModalProps) {
  const t = useTranslations('common');

  if (!isOpen) return null;

  return createPortal(
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={isLoading ? undefined : onClose}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Icon */}
        <div className="w-16 h-16 bg-red-100 dark:bg-red-950/40 rounded-full flex items-center justify-center mx-auto mb-5">
          <Trash2 className="h-8 w-8 text-red-600 dark:text-red-400" />
        </div>

        {/* Title */}
        <h2 className="text-2xl font-semibold text-theme-primary text-center mb-2">
          {title}
        </h2>

        {/* Body - custom takes precedence over the default ICU message */}
        {customBody !== undefined ? (
          <div className="text-theme-secondary text-center mb-8">{customBody}</div>
        ) : (
          <p className="text-theme-secondary text-center mb-8">
            {t('deleteConfirmMessage', {
              count: itemCount ?? 1,
              type: itemType ?? '',
            })}
          </p>
        )}

        {/* Actions */}
        <div className="flex gap-3">
          <Button onClick={onClose} variant="outline" className="flex-1" disabled={isLoading}>
            {t('cancel')}
          </Button>
          <Button onClick={onConfirm} variant="destructive" className="flex-1" disabled={isLoading}>
            {confirmLabel ?? t('delete')}
          </Button>
        </div>
      </div>
    </div>,
    document.body
  );
}

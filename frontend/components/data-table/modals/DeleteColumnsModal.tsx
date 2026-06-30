'use client';

import React from 'react';
import { Button } from '@/components/ui/button';
import { Trash2 } from 'lucide-react';
import { useTranslations } from 'next-intl';

interface DeleteColumnsModalProps {
  isOpen: boolean;
  columnsToDelete: string[];
  onClose: () => void;
  onConfirm: () => void;
}

export function DeleteColumnsModal({ isOpen, columnsToDelete, onClose, onConfirm }: DeleteColumnsModalProps) {
  const t = useTranslations('dataTable');
  const tCommon = useTranslations('common');

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={(e) => {
        if (e.target === e.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        className="max-w-sm w-full bg-theme-primary rounded-3xl shadow-2xl p-8 text-center animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Icon */}
        <div className="w-16 h-16 bg-red-100 dark:bg-red-950/40 rounded-full flex items-center justify-center mx-auto mb-5">
          <Trash2 className="h-8 w-8 text-red-600 dark:text-red-400" />
        </div>

        {/* Title */}
        <h2 className="text-2xl font-semibold text-theme-primary mb-2">
          {t('deleteColumns')}
        </h2>

        {/* Message */}
        <p className="text-sm text-theme-secondary mb-6">
          {t('deleteColumnsConfirm', { count: columnsToDelete.length })}
        </p>

        {/* Actions */}
        <div className="flex gap-3 mt-8">
          <Button onClick={onClose} variant="outline" className="flex-1">
            {tCommon('cancel')}
          </Button>
          <Button onClick={onConfirm} variant="destructive" className="flex-1">
            {tCommon('delete')}
          </Button>
        </div>
      </div>
    </div>
  );
}

'use client';

import * as React from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, Save } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';

interface UnsavedChangesModalProps {
  isOpen: boolean;
  onSave: () => Promise<void>;
  onDiscard: () => void;
  onCancel: () => void;
  isSaving?: boolean;
}

export function UnsavedChangesModal({
  isOpen,
  onSave,
  onDiscard,
  onCancel,
  isSaving = false,
}: UnsavedChangesModalProps) {
  const t = useTranslations('modals.unsavedChanges');
  const [isProcessing, setIsProcessing] = React.useState(false);
  const [mounted, setMounted] = React.useState(false);

  React.useEffect(() => {
    setMounted(true);
  }, []);

  const handleSave = async () => {
    setIsProcessing(true);
    try {
      await onSave();
    } finally {
      setIsProcessing(false);
    }
  };

  if (!isOpen || !mounted) return null;

  const isDisabled = isProcessing || isSaving;

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onCancel}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Icon */}
        <div className="w-16 h-16 bg-amber-100 dark:bg-amber-950/40 rounded-full flex items-center justify-center mx-auto mb-5">
          <AlertTriangle className="h-8 w-8 text-amber-600 dark:text-amber-400" />
        </div>

        {/* Title */}
        <h2 className="text-2xl font-semibold text-theme-primary text-center mb-2">
          {t('title')}
        </h2>

        {/* Description */}
        <p className="text-theme-secondary text-center mb-8">
          {t('message')}
        </p>

        {/* Buttons */}
        <div className="flex flex-col gap-3">
          <Button
            onClick={handleSave}
            disabled={isDisabled}
            className="w-full"
          >
            {isDisabled ? (
              <>
                <LoadingSpinner size="xs" />
                {t('saving')}
              </>
            ) : (
              <>
                <Save className="h-4 w-4" />
                {t('save')}
              </>
            )}
          </Button>
          <Button
            onClick={onDiscard}
            disabled={isDisabled}
            variant="outline"
            className="w-full"
          >
            {t('discard')}
          </Button>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}

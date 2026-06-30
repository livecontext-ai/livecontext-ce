'use client';

import React from 'react';
import { CheckCircle, RotateCcw, SquarePen } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';

interface SuccessModalProps {
  isOpen: boolean;
  apiName: string;
  toolsCount: number;
  firstToolName?: string;
  onCreateNew: () => void;
  onModifyCurrent: () => void;
}

export function SuccessModal({
  isOpen,
  apiName,
  toolsCount,
  firstToolName,
  onCreateNew,
  onModifyCurrent
}: SuccessModalProps) {
  const t = useTranslations('developers.confirmation');

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center p-4 z-[9999]"
      onClick={onCreateNew}
    >
      <div
        className="max-w-lg w-full max-h-[90vh] bg-theme-primary rounded-3xl shadow-2xl p-8 text-center animate-in fade-in-0 zoom-in-95 duration-300 border border-theme overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Success icon */}
        <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-6">
          <CheckCircle className="w-8 h-8 text-green-600" />
        </div>

        {/* Title */}
        <h1 className="text-2xl font-semibold text-theme-primary mb-4">
          {t('successModal.title')}
        </h1>

        {/* Success message */}
        <p className="text-lg text-theme-secondary mb-8">
          {t('successModal.message')}
        </p>

        {/* Configuration details */}
        <div className="bg-theme-tertiary rounded-2xl p-6 mb-8 border border-theme">
          <h2 className="text-2xl font-semibold text-theme-primary mb-4">
            {t('successModal.summaryTitle')}
          </h2>
          <div className="text-lg text-theme-secondary">
            <div className="flex items-center justify-center gap-4 mb-3">
              <span className="px-3 py-1 bg-theme-secondary rounded-full text-sm font-medium text-theme-primary">
                {apiName || t('common.unnamedApi')}
              </span>
              <span className="px-3 py-1 bg-blue-100 dark:bg-blue-900 rounded-full text-sm font-medium text-blue-700 dark:text-blue-300">
                {toolsCount === 1
                  ? t('tools.singleConfigured', { name: firstToolName })
                  : t('tools.multipleConfigured', { count: toolsCount })
                }
              </span>
            </div>
            <p className="text-center">
              {t('successModal.validating')}
            </p>
          </div>
        </div>

        {/* Action buttons */}
        <div className="flex flex-col sm:flex-row gap-4 justify-center">
          <Button
            onClick={onCreateNew}
            variant="outline"
            size="default"
            className="flex-1 flex items-center justify-center gap-2"
          >
            <RotateCcw className="w-5 h-5" />
            {t('successModal.createNew')}
          </Button>
          <Button
            onClick={onModifyCurrent}
            variant="contrast"
            size="default"
            className="flex-1 flex items-center justify-center gap-2"
          >
            <SquarePen className="w-5 h-5" />
            {t('successModal.modifyCurrent')}
          </Button>
        </div>
      </div>
    </div>
  );
}

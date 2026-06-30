'use client';

import React from 'react';
import { AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';

interface ErrorModalProps {
  isOpen: boolean;
  errorMessage: string;
  onClose: () => void;
}

export function ErrorModal({ isOpen, errorMessage, onClose }: ErrorModalProps) {
  const t = useTranslations('developers.confirmation');

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center p-4 z-[9999]"
      onClick={onClose}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 text-center animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Error icon */}
        <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <AlertCircle className="w-8 h-8 text-red-600" />
        </div>

        {/* Title */}
        <h2 className="text-2xl font-semibold text-theme-primary mb-2">
          {t('errorModal.title')}
        </h2>

        {/* Error message */}
        <p className="text-theme-secondary mb-6">
          {errorMessage || t('errorModal.defaultMessage')}
        </p>

        {/* Action button */}
        <Button
          onClick={onClose}
          variant="outline"
          size="default"
          className="w-full"
        >
          {t('errorModal.tryAgain')}
        </Button>
      </div>
    </div>
  );
}

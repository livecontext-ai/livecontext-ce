'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { Sparkles } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { useTranslations } from 'next-intl';

interface AskApiHelpDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (description: string) => void;
}

export function AskApiHelpDialog({ open, onOpenChange, onSubmit }: AskApiHelpDialogProps) {
  const t = useTranslations('customApis');
  const [description, setDescription] = useState('');

  useEffect(() => {
    if (!open) setDescription('');
  }, [open]);

  const handleSubmit = useCallback(() => {
    const value = description.trim();
    if (!value) return;
    onSubmit(value);
  }, [description, onSubmit]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        handleSubmit();
      }
    },
    [handleSubmit]
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Sparkles className="h-4 w-4 text-[var(--accent-primary)]" />
            {t('aiHelpModal.title')}
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-3">
          <p className="text-sm text-theme-secondary">{t('aiHelpModal.description')}</p>
          <Textarea
            autoFocus
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={t('aiHelpModal.placeholder')}
            className="min-h-[140px]"
          />
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t('aiHelpModal.cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={!description.trim()}>
            <Sparkles className="h-3.5 w-3.5 mr-1" />
            {t('aiHelpModal.submit')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

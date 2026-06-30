'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { FolderPlus, Pencil, Check } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTranslations } from 'next-intl';

interface CreateFolderDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onCreate: (name: string) => Promise<void>;
  /** When set, dialog opens in rename mode with this value pre-filled */
  initialName?: string;
}

export function CreateFolderDialog({ isOpen, onClose, onCreate, initialName }: CreateFolderDialogProps) {
  const t = useTranslations('emptyState.skill');
  const [name, setName] = useState('');
  const [saving, setSaving] = useState(false);
  const [mounted, setMounted] = useState(false);
  const isRename = initialName !== undefined;

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // Sync name when dialog opens or initialName changes
  useEffect(() => {
    if (isOpen) setName(initialName ?? '');
  }, [isOpen, initialName]);

  if (!mounted || !isOpen) return null;

  const handleSubmit = async () => {
    if (!name.trim()) return;
    setSaving(true);
    try {
      await onCreate(name.trim());
      setName('');
      onClose();
    } catch (err) {
      console.error(isRename ? 'Error renaming folder:' : 'Error creating folder:', err);
    } finally {
      setSaving(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && name.trim()) {
      handleSubmit();
    }
    if (e.key === 'Escape') {
      onClose();
    }
  };

  const Icon = isRename ? Pencil : FolderPlus;
  const title = isRename ? t('renameFolder') : t('createFolder');
  const titleId = isRename ? 'rename-skill-folder-dialog-title' : 'create-skill-folder-dialog-title';

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-theme flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-8 pt-8 pb-4">
          <div className="text-center mb-2">
            <div className="flex justify-center mb-4">
              <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center">
                <Icon className="w-8 h-8 text-theme-primary" />
              </div>
            </div>
            <h3 id={titleId} className="text-2xl font-semibold text-theme-primary">{title}</h3>
          </div>
        </div>

        {/* Content */}
        <div className="px-8 pb-4">
          <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">{t('folderName')}</label>
              <Input
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={t('folderName')}
                className="w-full"
                autoFocus
              />
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="px-8 py-4 border-t border-theme flex justify-end gap-2">
          <Button variant="outline" onClick={onClose} disabled={saving}>
            {t('cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={saving || !name.trim()}>
            {saving ? (
              <>
                <LoadingSpinner size="xs" className="mr-2" />
                {title}
              </>
            ) : (
              <>
                <Check className="h-4 w-4 mr-2" />
                {title}
              </>
            )}
          </Button>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}

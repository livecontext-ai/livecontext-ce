'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Workflow, Table, Monitor, File as FileIcon } from 'lucide-react';
import { useTranslations } from 'next-intl';

export type EditMetadataResourceType = 'workflow' | 'interface' | 'datasource' | 'file';

export interface EditMetadataModalProps {
  resourceType: EditMetadataResourceType;
  initialName: string;
  initialDescription?: string;
  isSaving?: boolean;
  onClose: () => void;
  onSave: (values: { name: string; description: string }) => void | Promise<void>;
}

const ICON_BY_TYPE = {
  workflow: Workflow,
  interface: Monitor,
  datasource: Table,
  file: FileIcon,
} as const;

export const EditMetadataModal: React.FC<EditMetadataModalProps> = ({
  resourceType,
  initialName,
  initialDescription = '',
  isSaving = false,
  onClose,
  onSave,
}) => {
  const t = useTranslations('modals.editMetadata');
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState(initialDescription);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  const Icon = ICON_BY_TYPE[resourceType];

  const handleSave = async () => {
    if (!name.trim()) return;
    await onSave({ name: name.trim(), description: description.trim() });
  };

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="max-w-lg w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="text-center mb-6">
          <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
            <Icon className="w-8 h-8 text-theme-primary" />
          </div>
          <h3 className="text-2xl font-semibold text-theme-primary">{t('title')}</h3>
        </div>

        <div className="space-y-5">
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">{t('nameLabel')}</label>
            <Input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('namePlaceholder')}
              className="w-full"
              autoFocus
            />
          </div>

          {resourceType !== 'file' && (
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">{t('descriptionLabel')}</label>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={t('descriptionPlaceholder')}
                className="w-full min-h-[100px] px-4 py-3 text-sm rounded-xl border border-theme bg-theme-primary text-theme-primary placeholder:text-theme-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-2"
                rows={3}
              />
            </div>
          )}
        </div>

        <div className="flex gap-3 mt-8">
          <Button variant="outline" onClick={onClose} disabled={isSaving} className="flex-1">
            {t('cancel')}
          </Button>
          <Button onClick={handleSave} disabled={!name.trim() || isSaving} className="flex-1">
            {isSaving ? t('saving') : t('save')}
          </Button>
        </div>
      </div>
    </div>
  );

  if (!mounted) return null;
  return createPortal(modalContent, document.body);
};

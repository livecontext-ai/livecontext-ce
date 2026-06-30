'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { FileText } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTranslations } from 'next-intl';
import type { StandaloneFormEndpoint } from '@/lib/api/orchestrator/form-endpoint-settings.service';

interface FormEndpointFormDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (data: Record<string, unknown>) => void;
  endpoint: StandaloneFormEndpoint | null;
  isLoading: boolean;
}

export function FormEndpointFormDialog({ open, onOpenChange, onSubmit, endpoint, isLoading }: FormEndpointFormDialogProps) {
  const t = useTranslations('triggerSettings');

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [workflowId, setWorkflowId] = useState('');
  const [workflowName, setWorkflowName] = useState('');
  const [successMessage, setSuccessMessage] = useState('');

  useEffect(() => {
    if (endpoint) {
      setName(endpoint.name || '');
      setDescription(endpoint.description || '');
      setWorkflowId(endpoint.workflowId || '');
      setWorkflowName(endpoint.workflowName || '');
      setSuccessMessage(endpoint.successMessage || '');
    } else {
      setName('');
      setDescription('');
      setWorkflowId('');
      setWorkflowName('');
      setSuccessMessage('');
    }
  }, [endpoint, open]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit({
      name,
      description: description || undefined,
      workflowId,
      workflowName: workflowName || undefined,
      successMessage: successMessage || undefined,
    });
  };

  if (!open) return null;

  const content = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={() => onOpenChange(false)}
    >
      <div
        className="max-w-lg w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header icon */}
        <div className="flex justify-center mb-4">
          <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center">
            <FileText className="w-8 h-8 text-theme-primary" />
          </div>
        </div>

        {/* Title */}
        <h2 className="text-2xl font-semibold text-theme-primary text-center mb-6">
          {endpoint ? t('editForm') : t('createForm')}
        </h2>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-1">{t('name')}</label>
            <Input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('formNamePlaceholder')}
              required
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-theme-primary mb-1">{t('description')}</label>
            <Input
              type="text"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={t('descriptionPlaceholder')}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-theme-primary mb-1">{t('workflowId')}</label>
            <Input
              type="text"
              value={workflowId}
              onChange={(e) => setWorkflowId(e.target.value)}
              placeholder={t('workflowIdPlaceholder')}
              required
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-theme-primary mb-1">{t('successMessageLabel')}</label>
            <textarea
              value={successMessage}
              onChange={(e) => setSuccessMessage(e.target.value)}
              className="w-full px-3 py-2 text-sm border border-theme rounded-md bg-theme-primary text-theme-primary"
              placeholder={t('successMessagePlaceholder')}
              rows={3}
            />
          </div>

          <div className="flex gap-3 mt-8">
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} className="flex-1">
              {t('cancel')}
            </Button>
            <Button type="submit" disabled={isLoading || !name || !workflowId} className="flex-1">
              {isLoading ? t('saving') : t('save')}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );

  return createPortal(content, document.body);
}

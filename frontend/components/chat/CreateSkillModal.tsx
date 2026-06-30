'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import {
  Zap, Check, Info, RotateCcw, Globe
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { orchestratorApi } from '@/lib/api/orchestrator';
import type { Skill } from '@/lib/api';
import { useQueryClient } from '@tanstack/react-query';
import { useToast } from '@/components/Toast';
import Toast from '@/components/Toast';
import { useTranslations } from 'next-intl';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useAuth } from '@/lib/providers/smart-providers';

// ============== Props ==============

interface CreateSkillModalProps {
  onClose: () => void;
  onSkillCreated: () => void;
  skill?: Skill;
  folderId?: string | null;
}

// ============== Main Component ==============

export function CreateSkillModal({ onClose, onSkillCreated, skill, folderId }: CreateSkillModalProps) {
  const t = useTranslations('modals.createSkill');
  const { toasts, addToast, removeToast } = useToast();
  const queryClient = useQueryClient();
  const { hasRole } = useAuth();
  const isAdmin = hasRole('ADMIN');
  const isEditMode = !!skill?.id;
  const isDefault = !!skill?.defaultKey;
  const isExistingGlobal = !!skill?.isGlobal;
  // Non-admins may view global skills but cannot edit them.
  const readOnly = isExistingGlobal && !isAdmin;

  const [saving, setSaving] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [mounted, setMounted] = useState(false);

  // Fields
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [icon, setIcon] = useState('');
  const [instructions, setInstructions] = useState('');
  const [isGlobal, setIsGlobal] = useState(false);

  // Mount effect
  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // Initialize from skill on edit
  useEffect(() => {
    if (skill) {
      setName(skill.name || '');
      setDescription(skill.description || '');
      setIcon(skill.icon || '');
      setInstructions(skill.instructions || '');
      setIsGlobal(!!skill.isGlobal);
    }
  }, [skill]);

  const canSave = !readOnly
    && name.trim().length > 0
    && description.trim().length > 0
    && instructions.trim().length > 0;

  // Save
  const handleSave = async () => {
    if (!canSave) return;

    setSaving(true);
    try {
      const payload: Partial<Skill> = {
        name: name.trim(),
        description: description.trim(),
        icon: icon || undefined,
        instructions: instructions.trim(),
        folderId: folderId ?? undefined,
      };
      // Only admins may send isGlobal - backend also enforces this.
      if (isAdmin) {
        payload.isGlobal = isGlobal;
      }

      if (isEditMode && skill?.id) {
        await orchestratorApi.updateSkill(skill.id, payload);
        addToast({ title: t('success'), message: t('skillUpdated'), type: 'success' });
      } else {
        await orchestratorApi.createSkill(payload);
        addToast({ title: t('success'), message: t('skillCreated'), type: 'success' });
      }

      await queryClient.invalidateQueries({ queryKey: ['skills'] });
      onSkillCreated();
      onClose();
    } catch (err) {
      console.error('Error saving skill:', err);
      addToast({ title: t('error'), message: t('createFailed'), type: 'error' });
    } finally {
      setSaving(false);
    }
  };

  const handleReset = async () => {
    if (!skill?.id) return;
    setResetting(true);
    try {
      const reset = await orchestratorApi.resetSkill(skill.id);
      setName(reset.name || '');
      setDescription(reset.description || '');
      setIcon(reset.icon || '');
      setInstructions(reset.instructions || '');
      await queryClient.invalidateQueries({ queryKey: ['skills'] });
      addToast({ title: t('success'), message: t('skillReset'), type: 'success' });
    } catch (err) {
      console.error('Error resetting skill:', err);
      addToast({ title: t('error'), message: t('resetFailed'), type: 'error' });
    } finally {
      setResetting(false);
    }
  };

  if (!mounted) return null;

  const titleId = isEditMode ? 'edit-skill-modal-title' : 'create-skill-modal-title';

  const modalContent = (
    <>
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
        onClick={onClose}
      >
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
          className="max-w-2xl w-full bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] flex flex-col"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="px-8 pt-8 pb-4">
            <div className="text-center mb-2">
              <div className="flex justify-center mb-4">
                <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center">
                  <Zap className="w-8 h-8 text-theme-primary" />
                </div>
              </div>
              <h3 id={titleId} className="text-2xl font-semibold text-theme-primary">
                {isEditMode ? t('editTitle') : t('title')}
              </h3>
            </div>
          </div>

          {/* Content */}
          <div className="flex-1 overflow-y-auto px-8 pb-4">
            <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
              {/* Read-only banner for non-admins viewing a global skill */}
              {readOnly && (
                <div className="flex items-center gap-2 rounded-xl border border-theme bg-[var(--bg-secondary)] px-4 py-3 text-sm text-theme-secondary">
                  <Globe className="h-4 w-4 text-[var(--accent-primary)] flex-shrink-0" />
                  <span>{t('globalReadOnly')}</span>
                </div>
              )}
              {/* Name */}
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">{t('nameLabel')} *</label>
                <Input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={t('namePlaceholder')}
                  className="w-full"
                  disabled={readOnly}
                />
              </div>

              {/* Description */}
              <div>
                <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                  {t('descriptionLabel')} *
                  <TooltipProvider delayDuration={0}>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <Info className="h-3.5 w-3.5 text-theme-secondary cursor-help" />
                      </TooltipTrigger>
                      <TooltipContent side="top" className="max-w-xs">
                        <p className="text-xs">{t('descriptionInfo')}</p>
                      </TooltipContent>
                    </Tooltip>
                  </TooltipProvider>
                </label>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder={t('descriptionPlaceholder')}
                  rows={3}
                  maxLength={300}
                  disabled={readOnly}
                  className="w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0 resize-none"
                />
                <p className="text-xs text-theme-muted text-right mt-1">{description.length}/300</p>
              </div>

              {/* Admin-only: make global (available to every tenant, Deep-Research-like) */}
              {isAdmin && !isDefault && (
                <div className="rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3">
                  <label className="flex items-start gap-3 cursor-pointer select-none">
                    <input
                      type="checkbox"
                      checked={isGlobal}
                      onChange={(e) => setIsGlobal(e.target.checked)}
                      className="mt-1 h-4 w-4 rounded border-theme text-[var(--accent-primary)] focus:ring-[var(--accent-primary)]"
                    />
                    <div className="flex-1">
                      <div className="flex items-center gap-1.5 text-sm font-medium text-theme-primary">
                        <Globe className="h-4 w-4 text-[var(--accent-primary)]" />
                        {t('makeGlobalLabel')}
                      </div>
                      <p className="text-xs text-theme-secondary mt-1">
                        {t('makeGlobalHelp')}
                      </p>
                    </div>
                  </label>
                </div>
              )}

              {/* Instructions */}
              <div>
                <label className="flex items-center gap-1.5 text-sm font-medium text-theme-primary mb-2">
                  {t('instructionsLabel')} *
                  <TooltipProvider delayDuration={0}>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <Info className="h-3.5 w-3.5 text-theme-secondary cursor-help" />
                      </TooltipTrigger>
                      <TooltipContent side="top" className="max-w-xs">
                        <p className="text-xs">{t('instructionsInfo')}</p>
                      </TooltipContent>
                    </Tooltip>
                  </TooltipProvider>
                </label>
                <textarea
                  value={instructions}
                  onChange={(e) => setInstructions(e.target.value)}
                  placeholder={t('instructionsPlaceholder')}
                  rows={12}
                  disabled={readOnly}
                  className="w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0 resize-none"
                />
              </div>
            </div>
          </div>

          {/* Footer */}
          <div className="px-8 py-4 border-t border-theme flex justify-between">
            <div>
              {isDefault && (
                <Button variant="outline" onClick={handleReset} disabled={resetting || saving}>
                  {resetting ? (
                    <>
                      <LoadingSpinner size="xs" className="mr-2" />
                      {t('resetting')}
                    </>
                  ) : (
                    <>
                      <RotateCcw className="h-4 w-4 mr-2" />
                      {t('resetToDefault')}
                    </>
                  )}
                </Button>
              )}
            </div>
            <div className="flex gap-2">
              <Button variant="outline" onClick={onClose} disabled={saving}>
                {t('cancel')}
              </Button>
              <Button onClick={handleSave} disabled={saving || !canSave}>
                {saving ? (
                  <>
                    <LoadingSpinner size="xs" className="mr-2" />
                    {t('saving')}
                  </>
                ) : (
                  <>
                    <Check className="h-4 w-4 mr-2" />
                    {isEditMode ? t('update') : t('save')}
                  </>
                )}
              </Button>
            </div>
          </div>
        </div>
      </div>

      {/* Toast notifications */}
      <div className="fixed top-4 right-4 z-[10000] space-y-2">
        {toasts.map((toast) => (
          <Toast
            key={toast.id}
            id={toast.id}
            type={toast.type}
            title={toast.title}
            message={toast.message}
            duration={toast.duration}
            onClose={removeToast}
          />
        ))}
      </div>
    </>
  );

  return createPortal(modalContent, document.body);
}

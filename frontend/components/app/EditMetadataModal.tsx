'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Workflow, Table, Monitor, File as FileIcon, ChevronDown, ChevronRight } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { IS_CE } from '@/lib/edition';
import { CREDIT_LIST_USD } from '@/lib/billing/pricing-constants';

export type EditMetadataResourceType = 'workflow' | 'interface' | 'datasource' | 'file';

export interface EditMetadataModalProps {
  resourceType: EditMetadataResourceType;
  initialName: string;
  initialDescription?: string;
  /**
   * Workflow/application cost budget in CREDITS (1 credit = $0.001), or
   * null/undefined when none is set. Only surfaced for workflow resources.
   */
  initialBudgetCredits?: number | null;
  isSaving?: boolean;
  onClose: () => void;
  onSave: (values: { name: string; description: string; budgetCredits?: number | null }) => void | Promise<void>;
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
  initialBudgetCredits = null,
  isSaving = false,
  onClose,
  onSave,
}) => {
  const t = useTranslations('modals.editMetadata');
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState(initialDescription);
  const [mounted, setMounted] = useState(false);
  // Budget is edited in the edition's unit: dollars in CE, credits in cloud.
  // Seed the field by converting the stored credits into that unit. Convert via
  // an integer credits-per-dollar factor (1 / CREDIT_LIST_USD = 1000) so whole
  // amounts render cleanly - multiplying credits by 0.001 would surface IEEE-754
  // artifacts in the input (e.g. 350 -> "0.35000000000000003").
  const CREDITS_PER_DOLLAR = Math.round(1 / CREDIT_LIST_USD);
  const showBudget = resourceType === 'workflow';
  const creditsToDisplay = (credits: number | null | undefined): string => {
    if (credits == null || credits <= 0) return '';
    return IS_CE ? String(credits / CREDITS_PER_DOLLAR) : String(credits);
  };
  const [budgetInput, setBudgetInput] = useState<string>(creditsToDisplay(initialBudgetCredits));
  const [advancedOpen, setAdvancedOpen] = useState<boolean>(!!(initialBudgetCredits && initialBudgetCredits > 0));

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // The budget is often fetched asynchronously after the modal opens (the
  // breadcrumb opener doesn't carry it). Re-seed the field when it arrives, but
  // don't clobber a value the user is already editing.
  useEffect(() => {
    if (!showBudget) return;
    if (initialBudgetCredits != null && initialBudgetCredits > 0) {
      setBudgetInput(creditsToDisplay(initialBudgetCredits));
      setAdvancedOpen(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialBudgetCredits]);

  const Icon = ICON_BY_TYPE[resourceType];

  const handleSave = async () => {
    if (!name.trim()) return;
    // Convert the typed value (dollars in CE, credits in cloud) back to credits.
    // Blank / non-positive clears the budget.
    let budgetCredits: number | null | undefined = undefined;
    if (showBudget) {
      const raw = budgetInput.trim();
      if (raw === '') {
        budgetCredits = null;
      } else {
        const parsed = Number(raw);
        budgetCredits = Number.isFinite(parsed) && parsed > 0
          ? (IS_CE ? parsed * CREDITS_PER_DOLLAR : parsed)
          : null;
      }
    }
    await onSave({ name: name.trim(), description: description.trim(), budgetCredits });
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

          {/* Advanced: workflow / application cost budget. */}
          {showBudget && (
            <div className="pt-1 border-t border-theme">
              <button
                type="button"
                onClick={() => setAdvancedOpen((prev) => !prev)}
                className="flex items-center gap-1 text-sm font-medium text-theme-primary hover:text-[var(--accent-primary)] transition-colors w-full pt-3"
              >
                {advancedOpen ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
                <span>{t('advancedSection')}</span>
              </button>
              {advancedOpen && (
                <div className="mt-3">
                  <label className="block text-sm font-medium text-theme-primary mb-2">
                    {t('budgetLabel')}
                  </label>
                  <div className="relative">
                    {IS_CE && (
                      <span className="absolute left-3.5 top-1/2 -translate-y-1/2 text-sm text-theme-secondary pointer-events-none">$</span>
                    )}
                    <Input
                      type="number"
                      min="0"
                      step="any"
                      value={budgetInput}
                      onChange={(e) => setBudgetInput(e.target.value)}
                      placeholder={t('budgetPlaceholder')}
                      className={`w-full ${IS_CE ? 'pl-7' : ''}`}
                    />
                  </div>
                  <p className="mt-1.5 text-xs text-theme-secondary">
                    {IS_CE ? t('budgetHelpDollars') : t('budgetHelpCredits')}
                  </p>
                </div>
              )}
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

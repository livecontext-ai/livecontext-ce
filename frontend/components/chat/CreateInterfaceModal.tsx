'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { InterfaceThumbnail } from '@/app/workflows/builder/components/interface/InterfaceThumbnail';
import LoadingSpinner from '@/components/LoadingSpinner';
import { orchestratorApi } from '@/lib/api/orchestrator';
import { useTranslations } from 'next-intl';
import {
  Layout, ArrowRight, ArrowLeft, Check, Code, FileText
} from 'lucide-react';

const TOTAL_STEPS = 2;

// ============== Step Indicator ==============

interface StepIndicatorProps {
  currentStep: number;
  totalSteps: number;
  onStepClick?: (step: number) => void;
}

const StepIndicator: React.FC<StepIndicatorProps> = ({ currentStep, totalSteps, onStepClick }) => {
  const steps = [
    { number: 1, icon: FileText, label: 'Basic Info' },
    { number: 2, icon: Code, label: 'Code' },
  ];

  return (
    <div className="flex items-center justify-center gap-2 mb-6">
      {steps.slice(0, totalSteps).map((step, index) => {
        const isActive = step.number === currentStep;
        const isCompleted = step.number < currentStep;
        const Icon = step.icon;

        return (
          <React.Fragment key={step.number}>
            <button
              type="button"
              onClick={() => onStepClick?.(step.number)}
              className={`flex items-center gap-2 px-3 py-1.5 rounded-full transition-all ${
                isActive
                  ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
                  : isCompleted
                  ? 'bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 cursor-pointer hover:bg-emerald-500/30'
                  : 'bg-theme-tertiary text-theme-secondary cursor-pointer hover:bg-theme-secondary'
              }`}
            >
              {isCompleted ? (
                <Check className="h-4 w-4" />
              ) : (
                <Icon className="h-4 w-4" />
              )}
              <span className="text-sm font-medium hidden sm:inline">{step.label}</span>
            </button>
            {index < totalSteps - 1 && (
              <div className={`w-8 h-0.5 rounded-full ${
                step.number < currentStep ? 'bg-emerald-500' : 'bg-theme-tertiary'
              }`} />
            )}
          </React.Fragment>
        );
      })}
    </div>
  );
};

// ============== Main Component ==============

interface InterfaceData {
  id?: string;
  name?: string;
  description?: string;
  htmlTemplate?: string;
  cssTemplate?: string;
  jsTemplate?: string;
  isPublic?: boolean;
  isActive?: boolean;
}

interface CreateInterfaceModalProps {
  onClose: () => void;
  onInterfaceCreated: () => void;
  interfaceData?: InterfaceData;
}

export const CreateInterfaceModal: React.FC<CreateInterfaceModalProps> = ({
  onClose,
  onInterfaceCreated,
  interfaceData,
}) => {
  const t = useTranslations('modals.createInterface');
  const isEditMode = !!interfaceData?.id;

  // In edit mode, start at step 2 (code) since user clicks "Edit" to modify code
  const [currentStep, setCurrentStep] = useState(isEditMode ? 2 : 1);

  // Step 1: Basic Info
  const [name, setName] = useState(interfaceData?.name || '');
  const [description, setDescription] = useState(interfaceData?.description || '');

  // Step 2: Code
  const [htmlTemplate, setHtmlTemplate] = useState(interfaceData?.htmlTemplate || '');
  const [cssTemplate, setCssTemplate] = useState(interfaceData?.cssTemplate || '');
  const [jsTemplate, setJsTemplate] = useState(interfaceData?.jsTemplate || '');

  // UI state
  const [isSaving, setIsSaving] = useState(false);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // Navigation - in edit mode, all steps are freely navigable
  const canProceedFromStep = (step: number): boolean => {
    switch (step) {
      case 1: return name.trim().length > 0;
      case 2: return true;
      default: return false;
    }
  };

  const nextStep = () => {
    if (currentStep < TOTAL_STEPS && canProceedFromStep(currentStep)) {
      setCurrentStep(prev => prev + 1);
    }
  };

  const prevStep = () => {
    if (currentStep > 1) {
      setCurrentStep(prev => prev - 1);
    }
  };

  const goToStep = (step: number) => {
    if (isEditMode || step <= currentStep || canProceedFromStep(step - 1)) {
      setCurrentStep(step);
    }
  };

  // Save handler
  const handleSave = async () => {
    if (!name.trim()) return;

    try {
      setIsSaving(true);

      const payload: any = {
        name: name.trim(),
        description: description.trim(),
        isPublic: interfaceData?.isPublic ?? false,
        isActive: interfaceData?.isActive ?? true,
      };

      if (htmlTemplate.trim()) {
        payload.htmlTemplate = htmlTemplate.trim();
      }
      if (cssTemplate.trim()) {
        payload.cssTemplate = cssTemplate.trim();
      }
      if (jsTemplate.trim()) {
        payload.jsTemplate = jsTemplate.trim();
      }

      if (isEditMode && interfaceData?.id) {
        await orchestratorApi.updateInterface(interfaceData.id, payload);
      } else {
        await orchestratorApi.createInterface(payload);
      }

      onInterfaceCreated();
      onClose();
    } catch (err) {
      console.error('Error saving interface:', err);
    } finally {
      setIsSaving(false);
    }
  };

  if (!mounted) return null;

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className={`w-full bg-theme-primary rounded-3xl shadow-2xl animate-in fade-in-0 zoom-in-95 duration-300 border border-theme flex flex-col overflow-hidden ${
          currentStep === 2 ? 'max-w-5xl h-[85vh]' : 'max-w-2xl max-h-[90vh]'
        }`}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-8 pt-8 pb-4 flex-shrink-0">
          <div className="text-center mb-4">
            {currentStep === 1 && (
              <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
                <Layout className="w-8 h-8 text-theme-primary" />
              </div>
            )}
            <h3 className="text-2xl font-semibold text-theme-primary">
              {isEditMode ? t('editTitle') : t('title')}
            </h3>
            <p className="text-sm text-theme-secondary mt-1">
              {currentStep === 1 && t('stepBasicInfoSubtitle')}
              {currentStep === 2 && t('stepCodeSubtitle')}
            </p>
          </div>
          <StepIndicator
            currentStep={currentStep}
            totalSteps={TOTAL_STEPS}
            onStepClick={goToStep}
          />
        </div>

        {/* Content */}
        <div className="flex-1 overflow-hidden min-h-0">
          {/* Step 1: Basic Info */}
          {currentStep === 1 && (
            <div className="px-8 pb-4 space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">
                  {t('nameLabel')}
                </label>
                <Input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={t('namePlaceholder')}
                  className="w-full"
                  autoFocus
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">
                  {t('descriptionLabel')}
                </label>
                <Input
                  type="text"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder={t('descriptionPlaceholder')}
                  className="w-full"
                />
              </div>
            </div>
          )}

          {/* Step 2: Code - two-column layout */}
          {currentStep === 2 && (
            <div className="flex flex-1 h-full animate-in fade-in-0 slide-in-from-right-4 duration-300">
              {/* Left column: Editors (scrollable) */}
              <div className="w-1/2 overflow-y-auto px-6 pb-4 border-r border-theme">
                <div className="flex flex-col gap-4">
                  {/* HTML Template */}
                  <div>
                    <label className="block text-sm font-medium text-theme-primary mb-2">
                      {t('htmlTemplateLabel')}
                    </label>
                    <ExpressionEditor
                      value={htmlTemplate}
                      onChange={setHtmlTemplate}
                      placeholder={t('htmlTemplatePlaceholder')}
                      className="w-full min-h-[200px]"
                    />
                  </div>

                  {/* CSS Template */}
                  <div>
                    <label className="block text-sm font-medium text-theme-primary mb-2">
                      {t('cssTemplateLabel')}
                    </label>
                    <ExpressionEditor
                      value={cssTemplate}
                      onChange={setCssTemplate}
                      placeholder={t('cssTemplatePlaceholder')}
                      className="w-full min-h-[120px]"
                    />
                  </div>

                  {/* JS Template */}
                  <div>
                    <label className="block text-sm font-medium text-theme-primary mb-2">
                      {t('jsTemplateLabel')}
                    </label>
                    <ExpressionEditor
                      value={jsTemplate}
                      onChange={setJsTemplate}
                      placeholder={t('jsTemplatePlaceholder')}
                      className="w-full min-h-[120px]"
                    />
                  </div>
                </div>
              </div>

              {/* Right column: Preview (scaled virtual viewport thumbnail) */}
              <div className="w-1/2 flex flex-col overflow-hidden p-4">
                <div className="w-full rounded-xl border border-theme overflow-hidden">
                  {htmlTemplate?.trim() ? (
                    <InterfaceThumbnail
                      htmlTemplate={htmlTemplate}
                      mode="edit"
                      customCss={cssTemplate || undefined}
                      jsTemplate={jsTemplate || undefined}
                    />
                  ) : (
                    <div className="flex items-center justify-center text-theme-secondary text-sm" style={{ aspectRatio: '16 / 10' }}>
                      No HTML template available
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-8 py-4 border-t border-theme flex justify-between flex-shrink-0">
          <Button
            variant="ghost"
            onClick={prevStep}
            disabled={currentStep === 1 || isSaving}
          >
            <ArrowLeft className="h-4 w-4 mr-2" />
            {t('back')}
          </Button>

          <div className="flex gap-2">
            <Button variant="outline" onClick={onClose} disabled={isSaving}>
              {t('cancel')}
            </Button>
            {currentStep < TOTAL_STEPS ? (
              <Button onClick={nextStep} disabled={!canProceedFromStep(currentStep)}>
                {t('next')}
                <ArrowRight className="h-4 w-4 ml-2" />
              </Button>
            ) : (
              <Button onClick={handleSave} disabled={!name.trim() || isSaving}>
                {isSaving ? (
                  <>
                    <LoadingSpinner size="xs" className="mr-2" />
                    {t('saving')}
                  </>
                ) : (
                  <>
                    <Check className="h-4 w-4 mr-2" />
                    {isEditMode ? t('update') : t('create')}
                  </>
                )}
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
};

'use client';

import React, { useState } from 'react';
import { CheckCircle, AlertTriangle, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ApiTemplate } from '../types';

interface ApiTemplatesSectionProps {
  templates: ApiTemplate[];
  selectedTemplate: string | null;
  onTemplateSelect: (templateId: string) => void;
  showSection: boolean;
  // Props pour verifier si le formulaire est rempli
  apiName?: string;
  apiDescription?: string;
  selectedCategory?: string;
  selectedSubcategory?: string;
  apiConfig?: any;
  mcpTools?: any[];
}

const ApiTemplatesSection: React.FC<ApiTemplatesSectionProps> = ({
  templates,
  selectedTemplate,
  onTemplateSelect,
  showSection,
  apiName,
  apiDescription,
  selectedCategory,
  selectedSubcategory,
  apiConfig,
  mcpTools
}) => {
  const [showChangeTemplateModal, setShowChangeTemplateModal] = useState(false);
  const [pendingTemplateId, setPendingTemplateId] = useState<string | null>(null);

  if (!showSection) return null;

  // Filtrer uniquement les templates externes
  const filteredTemplates = templates.filter(template => template.type === 'external');

  // Verifier si le formulaire est deja rempli (etat actuel + localStorage)
  const isFormFilled = () => {
    // Verifier l'etat actuel
    const hasCurrentState = (
      (apiName && apiName.trim() !== '') ||
      (apiDescription && apiDescription.trim() !== '') ||
      (selectedCategory && selectedCategory.trim() !== '') ||
      (selectedSubcategory && selectedSubcategory.trim() !== '') ||
      (apiConfig && apiConfig.baseUrl && apiConfig.baseUrl.trim() !== '') ||
      (mcpTools && mcpTools.length > 0)
    );

    // Verifier le localStorage
    const hasLocalStorageData = (
      localStorage.getItem('livecontext_apiName') ||
      localStorage.getItem('livecontext_apiDescription') ||
      localStorage.getItem('livecontext_selectedCategory') ||
      localStorage.getItem('livecontext_selectedSubcategory') ||
      localStorage.getItem('livecontext_mcpTools') ||
      localStorage.getItem('livecontext_apiConfig') ||
      localStorage.getItem('livecontext_monetizationConfig')
    );

    return hasCurrentState || hasLocalStorageData;
  };

  // Gestionnaire de selection de template avec verification du formulaire
  const handleTemplateSelect = (templateId: string) => {
    // Si un template est deja selectionne et le formulaire est rempli, afficher la modal
    if (selectedTemplate && selectedTemplate !== templateId && isFormFilled()) {
      setPendingTemplateId(templateId);
      setShowChangeTemplateModal(true);
    } else {
      onTemplateSelect(templateId);
    }
  };

  // Confirmer le changement de template
  const confirmTemplateChange = () => {
    if (pendingTemplateId) {
      onTemplateSelect(pendingTemplateId);
      setShowChangeTemplateModal(false);
      setPendingTemplateId(null);
    }
  };

  // Annuler le changement de template
  const cancelTemplateChange = () => {
    setShowChangeTemplateModal(false);
    setPendingTemplateId(null);
  };

  return (
    <div className="mb-6">
      {/* Header with icon */}
      <div className="flex items-center gap-3 mb-4">
        <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
          <Sparkles className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-theme-primary">Quick Start Templates</h2>
          <p className="text-sm text-theme-secondary">Select a template to get started quickly</p>
        </div>
      </div>

      {/* Templates as wrapping grid */}
      <div className="flex flex-wrap gap-2">
        {filteredTemplates.map((template) => {
          const isSelected = selectedTemplate === template.id;

          return (
            <button
              key={template.id}
              onClick={() => handleTemplateSelect(template.id)}
              className={`inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm transition-colors ${
                isSelected
                  ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
                  : 'bg-theme-tertiary hover:bg-theme-secondary text-theme-secondary hover:text-theme-primary'
              }`}
            >
              <span>{template.icon}</span>
              <span className="font-medium">{template.name}</span>
              {isSelected && (
                <CheckCircle className="w-3.5 h-3.5" />
              )}
            </button>
          );
        })}
      </div>


      {/* Modal de confirmation de changement de template */}
      {showChangeTemplateModal && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="max-w-sm w-full bg-theme-primary rounded-xl shadow-xl p-5 border border-theme">
            {/* Icon */}
            <div className="w-10 h-10 bg-amber-100 dark:bg-amber-900/30 rounded-lg flex items-center justify-center mx-auto mb-3">
              <AlertTriangle className="w-5 h-5 text-amber-600 dark:text-amber-500" />
            </div>

            {/* Title */}
            <h2 className="text-base font-semibold text-theme-primary text-center mb-2">
              Change Template?
            </h2>

            {/* Message */}
            <p className="text-sm text-theme-secondary text-center mb-5">
              This will reset your current progress.
            </p>

            {/* Action buttons */}
            <div className="flex gap-2">
              <Button
                onClick={cancelTemplateChange}
                variant="outline"
                size="sm"
                className="flex-1 h-8"
              >
                Cancel
              </Button>
              <Button
                onClick={confirmTemplateChange}
                variant="default"
                size="sm"
                className="flex-1 h-8"
              >
                Change
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ApiTemplatesSection;

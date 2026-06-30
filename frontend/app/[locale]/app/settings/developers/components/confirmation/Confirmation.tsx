'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { ApiConfig, McpTool, MonetizationConfig } from '../../types';
import { TYPOGRAPHY } from '../common';

// Hooks
import { useConfirmationState } from './hooks/useConfirmationState';
import { useExpansionState } from './hooks/useExpansionState';

// Utils
import { canSubmit } from './utils/validation';

// Components
import {
  SuccessModal,
  ErrorModal,
  Step1Summary,
  Step2Summary,
  Step3Summary,
  Step4Summary,
  ValidationSummary,
  SubmitSection
} from './components';

interface ConfirmationProps {
  apiConfig: ApiConfig;
  monetizationConfig: MonetizationConfig;
  mcpTools: McpTool[];
  apiName: string;
  apiDescription: string;
  selectedCategory: string;
  selectedSubcategory: string;
  onEditStep: (step: number) => void;
  onSubmit: (success: boolean) => void;
  isSubmitting: boolean;
}

const Confirmation: React.FC<ConfirmationProps> = ({
  apiConfig,
  monetizationConfig,
  mcpTools,
  apiName,
  apiDescription,
  selectedCategory,
  selectedSubcategory,
  onEditStep,
  onSubmit,
  isSubmitting
}) => {
  const t = useTranslations('developers.confirmation');

  // State management hook
  const {
    isAuthenticated,
    showSuccessModal,
    showErrorModal,
    errorMessage,
    isSubmittingLocal,
    responsesProgress,
    isApiLoading,
    apiError,
    apiResult,
    handleResetForm,
    handleModifyCurrentApi,
    handleCloseErrorModal,
    handleSubmitToBackend
  } = useConfirmationState({
    apiConfig,
    monetizationConfig,
    mcpTools,
    apiName,
    apiDescription,
    selectedCategory,
    selectedSubcategory,
    onSubmit,
    onEditStep
  });

  // Expansion state hook
  const {
    sectionsExpanded,
    toolsExpanded,
    categoriesExpanded,
    toggleSection,
    toggleTool,
    toggleCategory
  } = useExpansionState({ mcpTools });

  // Check if can submit
  const canSubmitForm = canSubmit({
    apiName,
    apiDescription,
    selectedCategory,
    selectedSubcategory,
    apiConfig,
    mcpTools,
    isAuthenticated
  });

  return (
    <>
      {/* Success Modal */}
      <SuccessModal
        isOpen={showSuccessModal}
        apiName={apiName}
        toolsCount={mcpTools.length}
        firstToolName={mcpTools[0]?.name}
        onCreateNew={handleResetForm}
        onModifyCurrent={handleModifyCurrentApi}
      />

      {/* Error Modal */}
      <ErrorModal
        isOpen={showErrorModal}
        errorMessage={errorMessage}
        onClose={handleCloseErrorModal}
      />

      <div className="space-y-6">
        {/* Main title */}
        <div className="text-center mb-8">
          <h1 className={TYPOGRAPHY.title}>{t('title')}</h1>
          <p className={`${TYPOGRAPHY.description} mt-2`}>{t('subtitle')}</p>
        </div>

        {/* Step 1: Basic Configuration */}
        <Step1Summary
          apiName={apiName}
          apiDescription={apiDescription}
          selectedCategory={selectedCategory}
          selectedSubcategory={selectedSubcategory}
          isExpanded={sectionsExpanded.step1}
          onToggle={() => toggleSection('step1')}
          onEdit={() => onEditStep(1)}
        />

        {/* Step 2: API Configuration */}
        <Step2Summary
          apiConfig={apiConfig}
          isExpanded={sectionsExpanded.step2}
          onToggle={() => toggleSection('step2')}
          onEdit={() => onEditStep(2)}
        />

        {/* Step 3: MCP Tools Configuration */}
        <Step3Summary
          mcpTools={mcpTools}
          isExpanded={sectionsExpanded.step3}
          onToggle={() => toggleSection('step3')}
          onEdit={() => onEditStep(3)}
          toolsExpanded={toolsExpanded}
          categoriesExpanded={categoriesExpanded}
          onToggleTool={toggleTool}
          onToggleCategory={toggleCategory}
        />

        {/* Step 4: Monetization Configuration */}
        <Step4Summary
          monetizationConfig={monetizationConfig}
          mcpTools={mcpTools}
          isExpanded={sectionsExpanded.step4}
          onToggle={() => toggleSection('step4')}
          onEdit={() => onEditStep(4)}
        />

        {/* Final Validation Section */}
        <ValidationSummary
          apiName={apiName}
          apiDescription={apiDescription}
          selectedCategory={selectedCategory}
          selectedSubcategory={selectedSubcategory}
          apiConfig={apiConfig}
          mcpTools={mcpTools}
          isExpanded={sectionsExpanded.validation}
          onToggle={() => toggleSection('validation')}
        />

        {/* Submit Section */}
        <SubmitSection
          canSubmit={canSubmitForm}
          isSubmitting={isSubmitting}
          isSubmittingLocal={isSubmittingLocal}
          isApiLoading={isApiLoading}
          isAuthenticated={isAuthenticated}
          apiError={apiError}
          apiResult={apiResult}
          responsesProgress={responsesProgress}
          onSubmit={handleSubmitToBackend}
        />
      </div>
    </>
  );
};

export default Confirmation;

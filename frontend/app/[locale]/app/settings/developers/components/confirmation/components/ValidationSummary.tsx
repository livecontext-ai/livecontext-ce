'use client';

import React from 'react';
import { CheckCircle, AlertCircle, Settings, Code, TestTube, DollarSign } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { FormSection } from '../../common';
import { ApiConfig, McpTool } from '../../../types';
import { hasBasicInfo as checkBasicInfo, hasApiConfig as checkApiConfig, hasTools as checkTools } from '../utils/validation';

interface ValidationSummaryProps {
  apiName: string;
  apiDescription: string;
  selectedCategory: string;
  selectedSubcategory: string;
  apiConfig: ApiConfig;
  mcpTools: McpTool[];
  isExpanded: boolean;
  onToggle: () => void;
}

export function ValidationSummary({
  apiName,
  apiDescription,
  selectedCategory,
  selectedSubcategory,
  apiConfig,
  mcpTools,
  isExpanded,
  onToggle
}: ValidationSummaryProps) {
  const t = useTranslations('developers.confirmation');

  const hasBasicInfoValid = checkBasicInfo(apiName, apiDescription, selectedCategory, selectedSubcategory);
  const hasApiConfigValid = checkApiConfig(apiConfig);
  const hasToolsValid = checkTools(mcpTools);

  return (
    <FormSection
      title={t('finalValidation.title')}
      icon={CheckCircle}
      iconColor="text-green-500"
      collapsible
      isExpanded={isExpanded}
      onToggle={onToggle}
    >
      <div className="space-y-4">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          {/* Step 1: Basic Info */}
          <ValidationCard
            icon={Settings}
            title={t('finalValidation.step1Base')}
            isValid={hasBasicInfoValid}
            validMessage={t('finalValidation.validated')}
            invalidMessage={t('finalValidation.incomplete')}
          />

          {/* Step 2: API Config */}
          <ValidationCard
            icon={Code}
            title={t('finalValidation.step2Api')}
            isValid={hasApiConfigValid}
            validMessage={t('finalValidation.validated')}
            invalidMessage={t('finalValidation.incomplete')}
          />

          {/* Step 3: Tools */}
          <ValidationCard
            icon={TestTube}
            title={t('finalValidation.step3Tools')}
            isValid={hasToolsValid}
            validMessage={t('finalValidation.toolCount', { count: mcpTools.length })}
            invalidMessage={t('finalValidation.noTool')}
          />

          {/* Step 4: Monetization */}
          <div className="p-4 border border-green-200 rounded-lg bg-green-50">
            <div className="flex items-center space-x-2 mb-2">
              <DollarSign className="w-5 h-5 text-green-500" />
              <span className="font-medium">{t('finalValidation.step4Monetization')}</span>
            </div>
            <div className="text-sm">
              <div className="flex items-center space-x-2 text-green-600">
                <CheckCircle className="w-4 h-4" />
                <span>{t('finalValidation.configured')}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </FormSection>
  );
}

interface ValidationCardProps {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  isValid: boolean;
  validMessage: string;
  invalidMessage: string;
}

function ValidationCard({ icon: Icon, title, isValid, validMessage, invalidMessage }: ValidationCardProps) {
  return (
    <div className={`p-4 border rounded-lg ${
      isValid ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'
    }`}>
      <div className="flex items-center space-x-2 mb-2">
        <Icon className={`w-5 h-5 ${isValid ? 'text-green-500' : 'text-red-500'}`} />
        <span className="font-medium">{title}</span>
      </div>
      <div className="text-sm">
        {isValid ? (
          <div className="flex items-center space-x-2 text-green-600">
            <CheckCircle className="w-4 h-4" />
            <span>{validMessage}</span>
          </div>
        ) : (
          <div className="flex items-center space-x-2 text-red-600">
            <AlertCircle className="w-4 h-4" />
            <span>{invalidMessage}</span>
          </div>
        )}
      </div>
    </div>
  );
}

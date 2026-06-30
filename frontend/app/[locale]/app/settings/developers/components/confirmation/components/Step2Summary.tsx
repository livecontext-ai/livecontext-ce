'use client';

import React from 'react';
import { Code, Edit } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import { FormSection, TYPOGRAPHY } from '../../common';
import { ApiConfig } from '../../../types';
import { getAuthorizationTypeTextKey } from '../utils/textHelpers';

interface Step2SummaryProps {
  apiConfig: ApiConfig;
  isExpanded: boolean;
  onToggle: () => void;
  onEdit: () => void;
}

export function Step2Summary({
  apiConfig,
  isExpanded,
  onToggle,
  onEdit
}: Step2SummaryProps) {
  const t = useTranslations('developers.confirmation');

  const getAuthorizationTypeText = () => {
    const key = getAuthorizationTypeTextKey(apiConfig);
    return t(key);
  };

  return (
    <FormSection
      title={t('step2.title')}
      icon={Code}
      iconColor="text-purple-500"
      collapsible
      isExpanded={isExpanded}
      onToggle={onToggle}
      actionButton={
        <Button
          onClick={onEdit}
          variant="ghost"
          size="icon"
          className="h-8 w-8"
          title={t('step2.edit')}
        >
          <Edit className="w-4 h-4" />
        </Button>
      }
    >
      <div className="space-y-4">
        <div className="p-4 border border-theme rounded-xl bg-theme-tertiary">
          <h4 className={`${TYPOGRAPHY.sectionTitle} mb-3`}>{t('step2.technicalConfig')}</h4>
          <div className="space-y-3">
            <div>
              <span className={TYPOGRAPHY.description}>{t('step2.baseUrl')}:</span>
              <div className={`${TYPOGRAPHY.code} bg-theme-background p-2 rounded mt-1`}>
                {apiConfig.baseUrl || t('common.notDefined')}
              </div>
            </div>
            <div>
              <span className={TYPOGRAPHY.description}>{t('step2.healthEndpoint')}:</span>
              <div className={`${TYPOGRAPHY.code} bg-theme-background p-2 rounded mt-1`}>
                {apiConfig.healthcheckEndpoint}
              </div>
            </div>
            <div>
              <span className={TYPOGRAPHY.description}>{t('step2.authentication')}:</span>
              <div className={`${TYPOGRAPHY.code} bg-theme-background p-1 rounded mt-1`}>
                {getAuthorizationTypeText()}
              </div>
            </div>
            {apiConfig.authorization.type === 'basic' ? (
              <>
                <div>
                  <span className="text-theme-muted">Username:</span>
                  <div className="font-mono bg-theme-background p-1 rounded mt-1 text-xs">
                    {apiConfig.authorization.username || 'Not configured'}
                  </div>
                </div>
                <div>
                  <span className="text-theme-muted">Password:</span>
                  <div className="font-mono bg-theme-background p-1 rounded mt-1 text-xs">
                    {apiConfig.authorization.password ? '••••••••' : 'Not configured'}
                  </div>
                </div>
              </>
            ) : apiConfig.authorization.type !== 'none' ? (
              <>
                <div>
                  <span className="text-theme-muted">Auth header:</span>
                  <div className="font-mono bg-theme-background p-1 rounded mt-1 text-xs">
                    {apiConfig.authorization.headerName || 'Not configured'}
                  </div>
                </div>
                <div>
                  <span className="text-theme-muted">Header value:</span>
                  <div className="font-mono bg-theme-background p-1 rounded mt-1 text-xs">
                    {apiConfig.authorization.headerValue || 'Not configured'}
                  </div>
                </div>
              </>
            ) : null}
            <div>
              <span className="text-theme-muted">Visibility:</span>
              <div className="text-theme-primary capitalize mt-1">{apiConfig.visibility}</div>
            </div>
          </div>
        </div>
      </div>
    </FormSection>
  );
}

'use client';

import React from 'react';
import { Settings, Edit } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import { FormSection, RichTextarea, TYPOGRAPHY } from '../../common';

interface Step1SummaryProps {
  apiName: string;
  apiDescription: string;
  selectedCategory: string;
  selectedSubcategory: string;
  isExpanded: boolean;
  onToggle: () => void;
  onEdit: () => void;
}

export function Step1Summary({
  apiName,
  apiDescription,
  selectedCategory,
  selectedSubcategory,
  isExpanded,
  onToggle,
  onEdit
}: Step1SummaryProps) {
  const t = useTranslations('developers.confirmation');

  return (
    <FormSection
      title={t('step1.title')}
      icon={Settings}
      iconColor="text-blue-500"
      collapsible
      isExpanded={isExpanded}
      onToggle={onToggle}
      actionButton={
        <Button
          onClick={onEdit}
          variant="ghost"
          size="icon"
          className="h-8 w-8"
          title={t('step1.edit')}
        >
          <Edit className="w-4 h-4" />
        </Button>
      }
    >
      <div className="space-y-4">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div className="p-4 border border-theme rounded-xl bg-theme-tertiary">
            <h4 className={`${TYPOGRAPHY.sectionTitle} mb-3`}>{t('step1.generalInfo')}</h4>
            <div className="space-y-3">
              <div>
                <span className={TYPOGRAPHY.description}>{t('step1.apiName')}:</span>
                <div className={`${TYPOGRAPHY.valueEmphasized} mt-1`}>{apiName || t('common.notDefined')}</div>
              </div>
              <div>
                <span className={TYPOGRAPHY.description}>{t('step1.category')}:</span>
                <div className={`${TYPOGRAPHY.value} mt-1`}>{selectedCategory || t('common.notDefined')}</div>
              </div>
              <div>
                <span className={TYPOGRAPHY.description}>{t('step1.subcategory')}:</span>
                <div className={`${TYPOGRAPHY.value} mt-1`}>{selectedSubcategory || t('common.notDefined')}</div>
              </div>
            </div>
          </div>

          <div className="p-4 border border-theme rounded-xl bg-theme-tertiary">
            <h4 className={`${TYPOGRAPHY.sectionTitle} mb-3`}>{t('step1.description')}</h4>
            <div className={TYPOGRAPHY.value}>
              <RichTextarea
                value={apiDescription || t('step1.noDescription')}
                onChange={() => {}}
                forcePreview={true}
                resizable={false}
                rows={4}
              />
            </div>
          </div>
        </div>
      </div>
    </FormSection>
  );
}

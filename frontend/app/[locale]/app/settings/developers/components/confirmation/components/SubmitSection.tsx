'use client';

import React from 'react';
import { CheckCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';
import { InfoBox } from '../../common';

interface ResponsesProgress {
  current: number;
  total: number;
  currentTool?: string;
}

interface SubmitSectionProps {
  canSubmit: boolean;
  isSubmitting: boolean;
  isSubmittingLocal: boolean;
  isApiLoading: boolean;
  isAuthenticated: boolean;
  apiError: string | null;
  apiResult: any;
  responsesProgress: ResponsesProgress | null;
  onSubmit: () => Promise<void>;
}

export function SubmitSection({
  canSubmit,
  isSubmitting,
  isSubmittingLocal,
  isApiLoading,
  isAuthenticated,
  apiError,
  apiResult,
  responsesProgress,
  onSubmit
}: SubmitSectionProps) {
  const t = useTranslations('developers.confirmation');

  return (
    <>
      {/* API Error Display */}
      {apiError && (
        <div className="mb-6">
          <InfoBox type="error" title={t('apiResult.errorTitle')}>
            <p>{apiError}</p>
          </InfoBox>
        </div>
      )}

      {/* API Success Display */}
      {apiResult && (
        <div className="mb-6">
          <InfoBox type="success" title={t('apiResult.successTitle')}>
            <div className="space-y-2 text-sm">
              <p><strong>{t('apiResult.apiId')}:</strong> {apiResult.data?.id}</p>
              <p><strong>{t('apiResult.apiName')}:</strong> {apiResult.data?.apiName}</p>
              <p><strong>{t('apiResult.category')}:</strong> {apiResult.data?.categoryName}</p>
              <p><strong>{t('apiResult.subcategory')}:</strong> {apiResult.data?.subcategoryName}</p>
              <p><strong>{t('apiResult.tools')}:</strong> {apiResult.data?.tools?.length || 0}</p>
            </div>
          </InfoBox>
        </div>
      )}

      {/* Submission button */}
      <div className="flex justify-center pt-6 pb-6">
        <Button
          onClick={async () => {
            try {
              await onSubmit();
            } catch (error) {
              // Error is handled in the hook
            }
          }}
          disabled={!canSubmit || isSubmitting || isApiLoading || isSubmittingLocal}
          size="lg"
          className="min-w-[200px]"
        >
          {isSubmittingLocal ? (
            <div className="flex items-center justify-center">
              <LoadingSpinner size="sm" className="mr-2" />
              {responsesProgress ? t('submit.processing', { current: responsesProgress.current, total: responsesProgress.total }) :
               isApiLoading ? t('submit.saving') : t('submit.submitting')}
            </div>
          ) : (
            <div className="flex items-center justify-center">
              {!isSubmittingLocal && !isApiLoading && !responsesProgress && <CheckCircle className="w-4 h-4 mr-2" />}
              {isApiLoading ? t('submit.saving') :
               responsesProgress ? t('submit.savingResponses', { current: responsesProgress.current, total: responsesProgress.total }) :
               isSubmitting ? t('submit.submitting') : t('submit.button')}
            </div>
          )}
        </Button>
      </div>

      {!canSubmit && (
        <div className="text-center">
          <p className="text-sm text-theme-muted">
            {!isAuthenticated
              ? t('submit.authenticateRequired')
              : t('submit.completeSteps')
            }
          </p>
        </div>
      )}
    </>
  );
}

'use client';

import { useState, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { useApiConfiguration } from '@/hooks/useApiConfiguration';
import { useCategoriesContext } from '@/lib/hooks/smart-hooks-complete';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { ApiConfig, McpTool, MonetizationConfig } from '../../../types';
import { buildApiConfigurationRequest } from '../utils/configBuilders';

interface ResponsesProgress {
  current: number;
  total: number;
  currentTool?: string;
}

interface UseConfirmationStateParams {
  apiConfig: ApiConfig;
  monetizationConfig: MonetizationConfig;
  mcpTools: McpTool[];
  apiName: string;
  apiDescription: string;
  selectedCategory: string;
  selectedSubcategory: string;
  onSubmit: (success: boolean) => void;
  onEditStep: (step: number) => void;
}

export function useConfirmationState(params: UseConfirmationStateParams) {
  const {
    apiConfig,
    monetizationConfig,
    mcpTools,
    apiName,
    apiDescription,
    selectedCategory,
    selectedSubcategory,
    onSubmit,
    onEditStep
  } = params;

  const router = useRouter();
  const { categories, subcategories } = useCategoriesContext();
  const { isAuthenticated } = useAuthGuard();

  // Modal states
  const [showSuccessModal, setShowSuccessModal] = useState(false);
  const [showErrorModal, setShowErrorModal] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [isSubmittingLocal, setIsSubmittingLocal] = useState(false);

  // Progress state
  const [responsesProgress, setResponsesProgress] = useState<ResponsesProgress | null>(null);

  // API configuration hook
  const {
    isLoading: isApiLoading,
    error: apiError,
    result: apiResult,
    processApiConfigurationWithProgress,
    clearError
  } = useApiConfiguration();

  // Handle success modal actions
  const handleResetForm = useCallback(() => {
    setShowSuccessModal(false);
    setIsSubmittingLocal(false);
    onSubmit(true);
  }, [onSubmit]);

  const handleGoToStep1 = useCallback(() => {
    setShowSuccessModal(false);
    setIsSubmittingLocal(false);
    onEditStep(1);
  }, [onEditStep]);

  const handleModifyCurrentApi = useCallback(() => {
    setShowSuccessModal(false);
    setIsSubmittingLocal(false);

    if (apiResult?.data?.id) {
      router.push(`/app/settings/mcp/${apiResult.data.id}`);
    } else {
      router.push('/app/settings/mcp');
    }
  }, [apiResult, router]);

  const handleCloseErrorModal = useCallback(() => {
    setShowErrorModal(false);
    setIsSubmittingLocal(false);
  }, []);

  // Submit to backend
  const handleSubmitToBackend = useCallback(async () => {
    try {
      setIsSubmittingLocal(true);
      clearError();
      setResponsesProgress(null);

      const configuration = buildApiConfigurationRequest({
        apiName,
        apiDescription,
        selectedCategory,
        selectedSubcategory,
        apiConfig,
        monetizationConfig,
        mcpTools,
        categories,
        subcategories
      });

      const onProgress = (current: number, total: number, currentTool?: string) => {
        setResponsesProgress({ current, total, currentTool });
      };

      // TODO: Get real user ID from auth context
      await processApiConfigurationWithProgress(configuration, 'user-123', onProgress);

      setResponsesProgress(null);
      setShowSuccessModal(true);

    } catch (error) {
      setResponsesProgress(null);
      const errorMsg = error instanceof Error ? error.message : 'An unexpected error occurred';
      setErrorMessage(errorMsg);
      setShowErrorModal(true);
    } finally {
      setIsSubmittingLocal(false);
    }
  }, [
    apiName,
    apiDescription,
    selectedCategory,
    selectedSubcategory,
    apiConfig,
    monetizationConfig,
    mcpTools,
    categories,
    subcategories,
    clearError,
    processApiConfigurationWithProgress
  ]);

  return {
    // Auth
    isAuthenticated,

    // Categories context
    categories,
    subcategories,

    // Modal states
    showSuccessModal,
    showErrorModal,
    errorMessage,
    isSubmittingLocal,

    // Progress
    responsesProgress,

    // API states
    isApiLoading,
    apiError,
    apiResult,

    // Handlers
    handleResetForm,
    handleGoToStep1,
    handleModifyCurrentApi,
    handleCloseErrorModal,
    handleSubmitToBackend
  };
}

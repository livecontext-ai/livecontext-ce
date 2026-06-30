'use client';

import { useCallback } from 'react';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { useErrorHandler } from './useErrorHandler';
import { useAuthGuard } from './useAuthGuard';
import { useUserApi } from '@/lib/hooks/useStandardApi';

/**
 * Hook unifie pour les APIs utilisateur
 * Utilise le pattern standardise pour eviter les erreurs de cache
 */
export function useUserApis() {
  const { isAuthenticated, isReady, user, isLoading: authLoading } = useAuthGuard();
  const { handleError, clearError } = useErrorHandler();
  
  // Utilisation du hook standardise - evite le melange de patterns
  const { data: apis = [], isLoading, error, refetch } = useUserApi(
    'apis',
    () => unifiedApiService.getUserApis()
  );

  const getApiById = useCallback(async (apiId: string) => {
    if (!isAuthenticated || !user?.sub) {
      throw new Error('User not authenticated');
    }

    try {
      return await unifiedApiService.getApiById(apiId);
    } catch (err) {
      handleError(err, 'getApiById');
      throw err;
    }
  }, [isAuthenticated, user?.sub, handleError]);

  return {
    apis,
    isLoading: isLoading || authLoading,
    error: error?.message || null,
    hasError: !!error,
    fetchUserApis: refetch,
    getApiById,
    clearError: () => clearError('userApis'),
  };
}

export default useUserApis;

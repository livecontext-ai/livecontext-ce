'use client';

import { useCallback } from 'react';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { useStandardApi } from '@/lib/hooks/useStandardApi';

export interface TestResult {
  status: number;
  statusText: string;
  data: any;
  responseTime: number;
  success: boolean;
  error?: string;
}

/**
 * Hook unifie pour les tests d'endpoints externes
 */
export function useExternalTesting() {
  const { isAuthenticated, user } = useAuthGuard();

  const testEndpoint = useCallback(async (testData: {
    url: string;
    method: string;
    headers?: Record<string, string>;
    body?: string;
    timeout?: number;
  }): Promise<TestResult> => {
    if (!isAuthenticated || !user?.sub) {
      throw new Error('User not authenticated');
    }

    const startTime = Date.now();

    try {
      const response = await unifiedApiService.testExternalEndpoint(
        testData.url,
        testData.method,
        testData.headers || {},
        testData.body || ''
      );
      const responseTime = Date.now() - startTime;

      const isSuccess = response.status >= 200 && response.status < 300;

      return {
        status: response.status,
        statusText: response.statusText || 'OK',
        data: response.data,
        responseTime,
        success: isSuccess,
        error: isSuccess ? undefined : `HTTP ${response.status}: ${response.statusText}`,
      };
    } catch (err) {
      const responseTime = Date.now() - startTime;
      const errorMessage = err instanceof Error ? err.message : 'Test failed';

      return {
        status: 0,
        statusText: 'Error',
        data: null,
        responseTime,
        success: false,
        error: errorMessage,
      };
    }
  }, [isAuthenticated, user?.sub]);

  return {
    testEndpoint,
    isLoading: false, // Pas de chargement pour les tests externes
    error: null, // Pas d'erreur persistante
  };
}

export default useExternalTesting;

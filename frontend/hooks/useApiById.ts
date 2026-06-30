'use client';

import { useResourceQuery } from '@/lib/hooks/useResourceQuery';
import { unifiedApiService, extractApiData } from '@/lib/api/unified-api-service';
import { queryKeys } from '@/lib/api/query-keys';
import { QUERY_CONFIG } from '@/lib/api/constants';

/**
 * Hook optimise pour recuperer une API specifique par ID
 * Utilise useResourceQuery pour eviter les boucles infinies sur 404
 */
export function useApiById(apiId: string) {
  const { data: apiResponse, isLoading, error, refetch, notFound, resetNotFound } = useResourceQuery({
    queryKey: queryKeys.api.byId(apiId),
    queryFn: () => unifiedApiService.getApiById(apiId),
    enabled: !!apiId,
    resourceId: apiId,
    staleTime: QUERY_CONFIG.STALE_TIME.SHORT,
    gcTime: QUERY_CONFIG.CACHE_TIME.SHORT,
  });

  // Extraire les donnees de l'API de la reponse de maniere uniforme
  const api = extractApiData(apiResponse);

  return {
    api,
    isLoading,
    error: error?.message || null,
    hasError: !!error,
    notFound,
    resetNotFound,
    refetch,
  };
}

export default useApiById;

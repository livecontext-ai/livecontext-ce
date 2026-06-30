/**
 * Hook factory standardise pour les appels API
 * evite les erreurs de melange de patterns de cache
 */

import { useQuery, UseQueryOptions } from '@tanstack/react-query';
import { useMemo } from 'react';
import { useAuthGuard } from '../../hooks/useAuthGuard';
import { useErrorHandler } from '../../hooks/useErrorHandler';
import { STANDARD_QUERY_CONFIG, USER_DATA_CONFIG, CATALOG_DATA_CONFIG } from '../api/constants';
import { queryKeys } from '../api/query-keys';

export type QueryConfigType = 'standard' | 'user' | 'catalog';

export interface StandardApiOptions<T> {
  queryKey: (string | number | undefined)[];
  queryFn: () => Promise<T>;
  configType?: QueryConfigType;
  enabled?: boolean;
  customConfig?: Partial<UseQueryOptions<T>>;
}

/**
 * Hook standardise pour les appels API
 * Utilise UNIQUEMENT useQuery (jamais queryClient.fetchQuery)
 */
export function useStandardApi<T>({
  queryKey,
  queryFn,
  configType = 'standard',
  enabled = true,
  customConfig = {}
}: StandardApiOptions<T>) {
  const { isAuthenticated, isReady, user } = useAuthGuard();
  const { handleError } = useErrorHandler();

  // Stabiliser la condition enabled pour eviter les requetes multiples
  const isEnabled = useMemo(() => {
    if (!enabled) return false;
    if (configType === 'user') {
      return isAuthenticated && isReady && !!user?.sub;
    }
    return isAuthenticated && isReady;
  }, [enabled, configType, isAuthenticated, isReady, user?.sub]);

  // Selectionner la configuration selon le type
  const getConfig = () => {
    switch (configType) {
      case 'user':
        return USER_DATA_CONFIG;
      case 'catalog':
        return CATALOG_DATA_CONFIG;
      default:
        return STANDARD_QUERY_CONFIG;
    }
  };

  const baseConfig = getConfig();

  return useQuery({
    queryKey,
    queryFn: async () => {
      try {
        return await queryFn();
      } catch (error) {
        // Log l'erreur avec plus de contexte
        console.error(`[useStandardApi] Error in query ${queryKey[0]}:`, error);
        handleError(error, queryKey[0] as string);
        throw error;
      }
    },
    enabled: isEnabled,
    ...baseConfig,
    ...customConfig,
  });
}

/**
 * Hook specialise pour les donnees utilisateur
 */
export function useUserApi<T>(
  resourceName: string,
  queryFn: () => Promise<T>,
  options?: Partial<StandardApiOptions<T>>
) {
  const { user } = useAuthGuard();
  
  // Utiliser les query keys centralisees pour eviter les requetes multiples
  const queryKey = useMemo(() => {
    const userId = user?.sub || 'anonymous';
    switch (resourceName) {
      case 'status':
        return queryKeys.user.status(userId);
      case 'profile':
        return queryKeys.user.profile(userId);
      case 'monetization':
        return queryKeys.user.monetization(userId);
      case 'apis':
        return queryKeys.user.apis(userId);
      case 'subscription':
        return queryKeys.user.subscription(userId);
      case 'creditBalance':
        return queryKeys.user.creditBalance(userId);
      default:
        return ['user', resourceName, userId];
    }
  }, [resourceName, user?.sub]);
  
  return useStandardApi({
    queryKey,
    queryFn,
    configType: 'user',
    ...options,
  });
}

/**
 * Hook specialise pour les donnees de catalog
 */
export function useCatalogApi<T>(
  resourceName: string,
  queryFn: () => Promise<T>,
  options?: Partial<StandardApiOptions<T>>
) {
  return useStandardApi({
    queryKey: ['catalog', resourceName],
    queryFn,
    configType: 'catalog',
    ...options,
  });
}

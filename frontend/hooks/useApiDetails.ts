'use client';

import { useApiById } from './useApiById';
import { enrichTools } from '@/lib/api/unified-api-service';

/**
 * Hook standardise pour les details d'API
 * Utilise useApiById pour eviter la duplication de logique
 */
export function useApiDetails(apiId: string) {
  return useApiById(apiId);
}

/**
 * Hook pour les outils d'une API
 * Utilise useApiById pour eviter la duplication et enrichit les outils
 */
export function useApiTools(apiId: string) {
  const { api, isLoading, error, hasError } = useApiById(apiId);
  
  // Enrichir les outils avec les donnees de l'API de maniere uniforme
  const tools = enrichTools(api?.tools || [], api);

  return {
    tools,
    isLoading,
    error,
    hasError,
  };
}

export default useApiDetails;

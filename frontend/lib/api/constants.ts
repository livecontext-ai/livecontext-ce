/**
 * Constantes de configuration pour React Query
 * Centralise toutes les configurations pour assurer la coherence
 */

import { is404Error, isClientError, isAuthError } from './error-utils';

export const QUERY_CONFIG = {
  STALE_TIME: {
    SHORT: 2 * 60 * 1000,    // 2 minutes - donnees qui changent souvent
    MEDIUM: 5 * 60 * 1000,   // 5 minutes - donnees moderement stables
    LONG: 15 * 60 * 1000,    // 15 minutes - donnees stables (categories, etc.)
  },
  CACHE_TIME: {
    SHORT: 5 * 60 * 1000,    // 5 minutes
    MEDIUM: 10 * 60 * 1000,  // 10 minutes
    LONG: 30 * 60 * 1000,    // 30 minutes
  },
  RETRY: {
    STANDARD: (failureCount: number, error: any) => {
      // Ne pas retry sur les erreurs d'authentification
      if (error?.message?.includes('401') || error?.message?.includes('Authentication')) {
        return false;
      }
      return failureCount < 3;
    },
    // Ne retry sur aucune erreur client (4xx) - utile pour les requetes par ID
    NO_CLIENT_ERRORS: (failureCount: number, error: any) => {
      if (is404Error(error) || isClientError(error) || isAuthError(error)) {
        return false;
      }
      return failureCount < 3;
    },
    NEVER: () => false,
    ALWAYS: (failureCount: number) => failureCount < 3,
  }
};

export const STANDARD_QUERY_CONFIG = {
  staleTime: QUERY_CONFIG.STALE_TIME.MEDIUM,
  gcTime: QUERY_CONFIG.CACHE_TIME.MEDIUM,
  retry: QUERY_CONFIG.RETRY.STANDARD,
};

export const USER_DATA_CONFIG = {
  staleTime: QUERY_CONFIG.STALE_TIME.SHORT,
  gcTime: QUERY_CONFIG.CACHE_TIME.SHORT,
  retry: QUERY_CONFIG.RETRY.STANDARD,
};

export const CATALOG_DATA_CONFIG = {
  staleTime: QUERY_CONFIG.STALE_TIME.LONG,
  gcTime: QUERY_CONFIG.CACHE_TIME.LONG,
  retry: QUERY_CONFIG.RETRY.STANDARD,
};

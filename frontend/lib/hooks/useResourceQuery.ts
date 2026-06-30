/**
 * Hook for fetching resources by ID with 404 protection
 * Prevents infinite API loops when a resource is not found
 */

import { useState, useCallback, useMemo } from 'react';
import { useQuery, UseQueryResult, QueryObserverResult } from '@tanstack/react-query';
import { useAuthGuard } from '../../hooks/useAuthGuard';
import { useErrorHandler } from '../../hooks/useErrorHandler';
import { QUERY_CONFIG } from '../api/constants';
import { is404Error } from '../api/error-utils';

export interface UseResourceQueryOptions<T, TSelected = T> {
  queryKey: (string | number | undefined)[];
  queryFn: () => Promise<T>;
  enabled?: boolean;
  resourceId?: string | number;  // For logging/debug
  staleTime?: number;
  gcTime?: number;
  select?: (data: T) => TSelected;  // Transform the data
}

export interface UseResourceQueryResult<TSelected> extends Omit<UseQueryResult<TSelected, Error>, 'refetch'> {
  /** True if the resource was not found (404) */
  notFound: boolean;
  /** Reset the notFound state to allow refetching */
  resetNotFound: () => void;
  /** Refetch the resource (automatically resets notFound) */
  refetch: () => Promise<QueryObserverResult<TSelected, Error>>;
}

/**
 * Hook for fetching resources by ID with automatic 404 handling
 *
 * Features:
 * - Detects 404 errors and sets notFound=true
 * - Automatically disables query when notFound=true (prevents infinite loops)
 * - Never retries on 404 errors
 * - Provides resetNotFound() for manual retry
 *
 * @example
 * ```tsx
 * const { data, isLoading, notFound, refetch } = useResourceQuery({
 *   queryKey: ['api', apiId],
 *   queryFn: () => fetchApiById(apiId),
 *   resourceId: apiId,
 * });
 *
 * if (notFound) return <NotFoundPage />;
 * ```
 */
export function useResourceQuery<T, TSelected = T>({
  queryKey,
  queryFn,
  enabled = true,
  resourceId,
  staleTime = QUERY_CONFIG.STALE_TIME.MEDIUM,
  gcTime = QUERY_CONFIG.CACHE_TIME.MEDIUM,
  select,
}: UseResourceQueryOptions<T, TSelected>): UseResourceQueryResult<TSelected> {
  const { isAuthenticated, isReady } = useAuthGuard();
  const { handleError } = useErrorHandler();
  const [notFound, setNotFound] = useState(false);

  // Disable query if not authenticated, not ready, disabled, or resource not found
  const isEnabled = useMemo(() => {
    if (!enabled) return false;
    if (notFound) return false;  // Block if 404 detected
    return isAuthenticated && isReady;
  }, [enabled, notFound, isAuthenticated, isReady]);

  const query = useQuery<T, Error, TSelected>({
    queryKey,
    queryFn: async () => {
      try {
        return await queryFn();
      } catch (error) {
        if (is404Error(error)) {
          console.warn(`[useResourceQuery] Resource not found: ${resourceId || queryKey.join('/')}`);
          setNotFound(true);
        } else {
          handleError(error, queryKey[0] as string);
        }
        throw error;
      }
    },
    enabled: isEnabled,
    staleTime,
    gcTime,
    select,
    retry: (failureCount, error) => {
      // Never retry on 404
      if (is404Error(error)) return false;
      return failureCount < 3;
    },
  });

  const resetNotFound = useCallback(() => {
    setNotFound(false);
  }, []);

  const refetch = useCallback(async () => {
    setNotFound(false);  // Reset before refetch
    return query.refetch();
  }, [query]);

  return {
    ...query,
    notFound,
    resetNotFound,
    refetch,
  };
}

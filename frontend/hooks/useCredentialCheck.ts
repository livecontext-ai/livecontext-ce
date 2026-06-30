/**
 * Hook for checking if user has credentials for a specific integration.
 * Loads all user credentials once and checks locally for fast lookups.
 */

import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { orchestratorApi } from '@/lib/api';
import { hasExactIntegrationMatch } from '@/lib/credentials/credentialMatching';

interface UseCredentialCheckReturn {
  /** Check if user has credential for this integration (iconSlug) */
  hasCredential: (iconSlug: string | undefined) => boolean;
  /** Loading state - true while fetching credentials */
  isLoading: boolean;
  /** Error if credentials fetch failed */
  error: Error | null;
  /** Refetch credentials (e.g., after user adds a new one) */
  refetch: () => Promise<{ data: any[] | undefined }>;
  /** All user credentials (for debugging) */
  credentials: any[] | undefined;
}

/**
 * Hook that loads all user credentials once and provides fast local lookups.
 * Uses React Query for caching with 30s stale time.
 *
 * @example
 * const { hasCredential, isLoading } = useCredentialCheck();
 * if (!isLoading && !hasCredential('gmail')) {
 *   // Show credential card
 * }
 */
export function useCredentialCheck(): UseCredentialCheckReturn {
  // Phase 4 (2026-05-18) - org-scoped: user credentials carry an active
  // workspace pointer (org-tagged platform credentials vs personal).
  const { data: credentials, isLoading, error, refetch } = useOrgScopedQuery({
    queryKey: ['user-credentials-all'] as const,
    queryFn: async () => {
      const result = await orchestratorApi.getAllCredentials();
      // Guard against non-array responses (e.g., HTML error pages returned as text)
      return Array.isArray(result) ? result : [];
    },
    staleTime: 0, // Always refetch (no cache)
    gcTime: 0, // Don't keep in garbage collection cache
    refetchOnWindowFocus: true, // Refetch when user comes back to tab
  });

  const hasCredential = (iconSlug: string | undefined): boolean => {
    // Strict exact match on iconSlug. Chat "Connect" prompts and service-
    // approval cards must not treat a generic `google` credential as
    // satisfying a specific `googlecloud` / `gmail` iconSlug, which is what
    // the looser 5-strategy inspector match would do.
    return hasExactIntegrationMatch(credentials, iconSlug);
  };

  return {
    hasCredential,
    isLoading,
    error: error as Error | null,
    refetch,
    credentials,
  };
}

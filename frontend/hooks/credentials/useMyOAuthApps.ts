/**
 * Hook: list the current tenant's BYOK custom OAuth connections.
 *
 * Backed by GET /api/platform-credentials/my (Phase 2 endpoint). Cached for
 * 30s; invalidate via the returned refetch or by querying React Query for
 * `['my-oauth-apps']` after a save/delete elsewhere.
 */

import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { orchestratorApi } from '@/lib/api';
import type { MyOAuthApp } from '@/lib/api/orchestrator/types';

export const MY_OAUTH_APPS_QUERY_KEY = ['my-oauth-apps'] as const;

export function useMyOAuthApps() {
  // Phase 4 (2026-05-18) - org-scoped: BYOK OAuth apps are workspace-bound
  // (different orgs may have distinct platform credentials). Each workspace
  // gets its own cache slice via the ['org', orgId, ...] prefix.
  return useOrgScopedQuery<MyOAuthApp[]>({
    queryKey: MY_OAUTH_APPS_QUERY_KEY,
    queryFn: () => orchestratorApi.getMyOAuthApps(),
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });
}

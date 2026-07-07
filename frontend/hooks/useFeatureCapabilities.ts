'use client';

import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { workflowService, type FeatureCapabilities } from '@/lib/api/orchestrator/workflow.service';
import { useAuth } from '@/lib/providers/smart-providers';

export type { FeatureCapabilities };

export interface FeatureCapabilitiesState {
  /**
   * Availability snapshot - null while loading, on error, or for anonymous
   * visitors. Consumers must treat null as "unknown" and show NO warning
   * (never flash a false "component missing" banner during load / on a
   * transient fetch failure).
   */
  capabilities: FeatureCapabilities | null;
  isLoading: boolean;
}

/**
 * Availability of the deployment's optional components (screenshot/PDF renderer,
 * browser agent + web search). Cloud reports everything available, so the
 * warning banners this hook powers only ever appear on self-hosted installs
 * that did not opt in. Cached 5 min - availability only changes on a stack
 * restart (or a cloud link/unlink).
 */
export function useFeatureCapabilities(): FeatureCapabilitiesState {
  const { isLoading: isAuthLoading, isAuthenticated } = useAuth();
  const enabled = !isAuthLoading && isAuthenticated;
  // Workspace-scoped: the browsing verdict is per-tenant (cloud-link CLOUD-source
  // selection), so a workspace switch must not serve the previous workspace's verdict.
  const { data, isPending } = useOrgScopedQuery({
    queryKey: ['feature-capabilities'] as const,
    queryFn: () => workflowService.getFeatureCapabilities(),
    enabled,
    staleTime: 5 * 60 * 1000,
    retry: false,
  });

  return {
    capabilities: data ?? null,
    isLoading: isAuthLoading || (enabled && isPending),
  };
}

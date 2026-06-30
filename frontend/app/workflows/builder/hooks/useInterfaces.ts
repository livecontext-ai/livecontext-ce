import { useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { orchestratorApi } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import { useCallback } from 'react';

export interface Interface {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  isPublic: boolean;
  createdAt?: string;
  updatedAt?: string;
  dataSourceId?: number | null;
  targetTable?: string | null;
  cssTemplate?: string;
  templateVariables?: string[];
  interfaceType?: string;
}

const fetchInterfaces = async (_excludeTableAttached?: boolean): Promise<Interface[]> => {
  const data = await orchestratorApi.getInterfaces();
  // Handle both array and object with interfaces property
  return Array.isArray(data) ? data as unknown as Interface[] : [];
};

export const useInterfaces = (enabled: boolean = true, excludeTableAttached?: boolean) => {
  const { isLoading: authLoading } = useAuthGuard();

  // Phase 4 (2026-05-18) - org-scoped: interfaces are workspace-bound.
  return useOrgScopedQuery({
    queryKey: ['interfaces', excludeTableAttached] as const,
    queryFn: () => fetchInterfaces(excludeTableAttached),
    enabled: enabled && !authLoading,
    staleTime: 5 * 60 * 1000, // 5 minutes - same as tools/API
    gcTime: 10 * 60 * 1000, // 10 minutes
  });
};

const fetchInterfaceById = async (interfaceId: string): Promise<Interface & { htmlTemplate?: string; editorExpression?: string; cssTemplate?: string; jsTemplate?: string; templateVariables?: string[] }> => {
  const data = await orchestratorApi.getInterface(interfaceId);
  return data as unknown as Interface & { htmlTemplate?: string; editorExpression?: string; cssTemplate?: string; jsTemplate?: string; templateVariables?: string[] };
};

export const useInterfaceById = (interfaceId: string | null) => {
  const { isLoading: authLoading, isAuthenticated } = useAuthGuard();
  // /api/interfaces/{id} requires auth. In public marketplace preview we skip
  // the metadata fetch entirely - the render endpoint returns the templates
  // we need, and callers already treat missing interface details as a no-op.
  const isPublicPreview = !!getActivePublicPreview();

  return useQuery({
    queryKey: ['interface', interfaceId],
    queryFn: () => fetchInterfaceById(interfaceId!),
    enabled: !!interfaceId && !authLoading && isAuthenticated && !isPublicPreview,
    staleTime: 5 * 60 * 1000, // 5 minutes
    gcTime: 10 * 60 * 1000, // 10 minutes
  });
};

/**
 * Result of rendering an interface for a workflow run
 */
export interface InterfaceRenderResult {
  htmlTemplate: string;
  cssTemplate?: string;
  jsTemplate?: string;
  items: Array<{
    epoch: number;
    itemIndex: number;
    spawn: number;
    data: Record<string, any>;
    triggerData?: Record<string, Record<string, unknown>>;
  }>;
  pagination: {
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
  };
  actionMappings?: Record<string, string>;
}

/**
 * Hook to fetch rendered interface data for a specific workflow run
 * @param interfaceId Interface UUID
 * @param runId Workflow run UUID
 * @param page Page number (0-based)
 * @param size Page size
 */
export const useInterfaceRender = (
  interfaceId: string | null,
  runId: string | null,
  page: number = 0,
  size: number = 1,
  epoch?: number,
  variablePages?: Record<string, number>
) => {
  const { isLoading: authLoading, isAuthenticated } = useAuthGuard();
  // Marketplace preview page: when an anonymous visitor browses a publication,
  // the PublicationSnapshotProvider publishes { publicationId, showcaseRunId }
  // to a module-level store. If our runId matches that frozen clone, swap to
  // the public /showcase-render endpoint - /api/interfaces/*/render requires a
  // JWT and would fail with "No authentication token available".
  const publicCtx = getActivePublicPreview();
  const usePublic = !!(publicCtx && runId && publicCtx.showcaseRunId === runId);
  // Acquirer/owner preview of a non-public (publisher-deleted INACTIVE / PRIVATE)
  // publication: the showcase render must hit the receipt-gated AUTH'D twin, which
  // needs a token. The plain anonymous public path needs none.
  const ctxAuthenticated = !!(usePublic && publicCtx?.authenticated);

  // Showcase-render (public) doesn't support variablePages - exclude from queryKey
  // to avoid silent no-op refetches when variablePages changes on a frozen clone.
  const varPagesKey = !usePublic && variablePages && Object.keys(variablePages).length > 0
    ? JSON.stringify(variablePages) : undefined;

  return useQuery({
    queryKey: ['interface-render', interfaceId, runId, page, size, epoch, varPagesKey, usePublic ? (publicCtx?.remote ? 'public-remote' : (ctxAuthenticated ? 'public-auth' : 'public')) : 'auth'],
    queryFn: async (): Promise<InterfaceRenderResult> => {
      if (usePublic && publicCtx) {
        const data = await publicationService.getShowcaseRender(publicCtx.publicationId, {
          interfaceId: interfaceId!,
          page,
          size,
          epoch,
          authenticated: publicCtx.authenticated,
        }, publicCtx.remote);
        return data as InterfaceRenderResult;
      }
      const data = await orchestratorApi.renderInterface(interfaceId!, runId!, { page, size, epoch, variablePages });
      return data as InterfaceRenderResult;
    },
    // Anonymous public path needs no token; the auth'd per-run path AND the
    // receipt-gated acquirer showcase path both wait for OIDC + authenticated user.
    enabled: !!interfaceId && !!runId && ((usePublic && !ctxAuthenticated) || (!authLoading && isAuthenticated)),
    placeholderData: keepPreviousData, // Keep old content visible while loading new epoch/page
    refetchOnMount: 'always', // Always refetch on mount (carousel tab switches remount the component)
    staleTime: 30 * 1000, // 30 seconds - fresher data for run mode
    gcTime: 5 * 60 * 1000, // 5 minutes
  });
};

/**
 * Hook to fetch the total count of items for an interface in a run
 */
export const useInterfaceItemsCount = (
  interfaceId: string | null,
  runId: string | null
) => {
  const { isLoading: authLoading, isAuthenticated } = useAuthGuard();
  const publicCtx = getActivePublicPreview();
  // /api/interfaces/{id}/items-count is auth-only. In public marketplace
  // preview we fall back to the render endpoint's pagination.totalItems (the
  // carousel uses it to cap the page index, not for a separate count).
  const isPublicPreview = !!(publicCtx && runId && publicCtx.showcaseRunId === runId);
  // Acquirer/owner preview: the showcase render twin is auth'd (needs a token).
  const ctxAuthenticated = !!(isPublicPreview && publicCtx?.authenticated);

  return useQuery({
    queryKey: ['interface-items-count', interfaceId, runId, ctxAuthenticated ? 'auth' : 'anon'],
    queryFn: async (): Promise<number> => {
      if (isPublicPreview && publicCtx) {
        const res = await publicationService.getShowcaseRender(publicCtx.publicationId, {
          interfaceId: interfaceId!,
          page: 0,
          size: 1,
          authenticated: publicCtx.authenticated,
        }, publicCtx.remote);
        return res?.pagination?.totalItems ?? 0;
      }
      return orchestratorApi.getInterfaceItemsCount(interfaceId!, runId!);
    },
    enabled: !!interfaceId && !!runId && ((isPublicPreview && !ctxAuthenticated) || (!authLoading && isAuthenticated)),
    staleTime: 30 * 1000,
    gcTime: 5 * 60 * 1000,
  });
};

/**
 * Hook to fetch epoch timestamps from run-info (page-index-aligned array).
 * Cached with 5 min staleTime - call invalidate() when new epochs appear.
 */
export const useEpochTimestamps = (
  interfaceId: string | null,
  runId: string | null
) => {
  const { isLoading: authLoading, isAuthenticated } = useAuthGuard();
  const queryClient = useQueryClient();
  // /api/interfaces/{id}/run-info is auth-only. Public previews can't reach
  // it; epoch-timestamp labels in the toolbar just fall back to numeric
  // "Epoch N" indexing when this returns an empty array.
  const isPublicPreview = !!(getActivePublicPreview() && runId && getActivePublicPreview()!.showcaseRunId === runId);

  const query = useQuery({
    queryKey: ['epoch-timestamps', interfaceId, runId],
    queryFn: async (): Promise<(string | null)[]> => {
      if (isPublicPreview) return [];
      const info = await orchestratorApi.getInterfaceRunInfo(interfaceId!, runId!);
      return info.epochTimestamps ?? [];
    },
    enabled: !!interfaceId && !!runId && (isPublicPreview || (!authLoading && isAuthenticated)),
    staleTime: 5 * 60 * 1000,
    gcTime: 10 * 60 * 1000,
  });

  const invalidate = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['epoch-timestamps', interfaceId, runId] });
  }, [queryClient, interfaceId, runId]);

  return { ...query, invalidate };
};


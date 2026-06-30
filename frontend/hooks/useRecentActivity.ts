'use client';

import { useQuery } from '@tanstack/react-query';
import { useAuth } from '@/lib/providers/smart-providers';
import { useCurrentOrg } from '@/lib/stores/current-org-store';
import {
  recentActivityService,
  type RecentActivityResponse,
} from '@/lib/api/orchestrator/recent-activity.service';

const STALE_TIME_MS = 30_000;

const EMPTY: RecentActivityResponse = { items: [], peerScopeCount: 0 };

export interface UseRecentActivityResult {
  items: RecentActivityResponse['items'];
  peerScopeCount: number;
  peerScopeLabel?: string;
  isLoading: boolean;
  error: unknown;
}

/**
 * Bell's 3rd-tab (Activity) data hook. Visit-only by default: callers pass
 * {@code enabled} to gate the fetch on the tab being open - keeps zero
 * polling cost when the user is on Inbox or Triggers.
 *
 * <p>Cache shape: keyed on the active workspace ({@code currentOrgId}) so a
 * workspace switch invalidates without a manual refetch. The backend's
 * scope routing reads X-Organization-ID, gateway-injected from the active-org
 * claim; the hook only needs the org id for cache-key purposes.
 *
 * <p>{@code staleTime} = 30 s matches the backend Redis cache TTL - the
 * frontend won't re-fire a request that the backend wouldn't serve fresh
 * anyway. {@code refetchOnWindowFocus} surfaces backend updates without
 * polling.
 *
 * <p>WS invalidation is intentionally NOT wired in v3.3.1 - see
 * {@code RecentActivityAggregatorService} javadoc for the deferred-decision
 * note. The 30 s stale window is acceptable for the observational nature of
 * the Activity tab.
 */
export function useRecentActivity(enabled: boolean): UseRecentActivityResult {
  const { isLoading: authLoading, isAuthenticated } = useAuth();
  const { currentOrgId } = useCurrentOrg();

  const query = useQuery({
    queryKey: ['recent-activity', currentOrgId ?? 'personal'] as const,
    queryFn: () => recentActivityService.getRecentActivity(),
    enabled: enabled && !authLoading && isAuthenticated,
    staleTime: STALE_TIME_MS,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
  });

  const data = query.data ?? EMPTY;
  return {
    items: data.items ?? [],
    peerScopeCount: data.peerScopeCount ?? 0,
    peerScopeLabel: data.peerScopeLabel,
    isLoading: authLoading || query.isLoading,
    error: query.error,
  };
}

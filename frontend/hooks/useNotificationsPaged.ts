'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { useAuth } from '@/lib/providers/smart-providers';
import { useChannel } from '@/lib/websocket/use-channel';
import { useCurrentOrg, useCurrentOrgStore } from '@/lib/stores/current-org-store';
import {
  homeStatusService,
  type NotificationsPage,
  type NotificationBucketRef,
  type NotificationItem,
} from '@/lib/api/orchestrator/home-status.service';

const QUERY_KEY_BASE = ['notifications-paged'] as const;
const HOME_STATUS_KEY_BASE = ['home-status'] as const;
const POLL_INTERVAL_MS = 60_000;
const STALE_TIME_MS = 30_000;

const EMPTY_PAGE: NotificationsPage = {
  items: [],
  unreadCount: 0,
  page: 0,
  size: 15,
  hasMore: false,
};

export interface UseNotificationsPagedResult {
  items: NotificationItem[];
  unreadCount: number;
  page: number;
  size: number;
  hasMore: boolean;
  isLoading: boolean;
  error: unknown;
  /** Bulk-delete a list of buckets. Single-row delete passes a 1-element array. */
  deleteBuckets: (buckets: NotificationBucketRef[]) => Promise<void>;
}

/**
 * Paginated bell-Inbox hook. Distinct from {@code useHomeStatus}:
 * <ul>
 *   <li>Inbox-only - does not fetch the Activity-tab automations list.</li>
 *   <li>Page-aware: caller passes {@code page} + {@code size}; server slices.</li>
 *   <li>Optimistic delete: {@code deleteBuckets} removes items from cache
 *       immediately, calls the bulk-delete endpoint, rolls back on failure.</li>
 *   <li>Shares the same WS channel as {@code useHomeStatus} for invalidation,
 *       and also invalidates the home-status cache so the Activity tab + any
 *       other consumer of the unread badge sees the new state.</li>
 * </ul>
 */
export function useNotificationsPaged(page: number, size: number): UseNotificationsPagedResult {
  const { numericUserId, isLoading: authLoading, isAuthenticated } = useAuth();
  const queryClient = useQueryClient();

  // useOrgScopedQuery transparently prefixes the queryKey with
  // `['org', currentOrgId ?? '__personal__', ...]`. Mutations below need the
  // same effective key to hit the cache - otherwise getQueryData/setQueryData
  // return undefined and the optimistic update is silently skipped, leaving
  // the row visible until the next refetch.
  const orgKeySegment = useCurrentOrgStore((s) => s.currentOrgId) ?? '__personal__';
  const queryKey = [...QUERY_KEY_BASE, page, size] as const;
  const effectiveQueryKey = ['org', orgKeySegment, ...QUERY_KEY_BASE, page, size] as const;
  const effectiveQueryKeyBase = ['org', orgKeySegment, ...QUERY_KEY_BASE] as const;
  const effectiveHomeStatusKey = ['org', orgKeySegment, ...HOME_STATUS_KEY_BASE] as const;

  // Phase 4 (2026-05-18) - org-scoped: notifications are gated by the
  // active workspace at request time (X-Active-Organization-ID), so the
  // bell inbox shows the org's events. Each workspace gets its own cache
  // slice; switching back to a workspace doesn't pull events from another.
  const query = useOrgScopedQuery({
    queryKey,
    queryFn: () => homeStatusService.getNotificationsPage(page, size),
    enabled: !authLoading && isAuthenticated,
    refetchInterval: POLL_INTERVAL_MS,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
    staleTime: STALE_TIME_MS,
  });

  // Match the backend publisher channel: `ws:user:{tenantId}:notifications`.
  // notification.created + notification.removed both invalidate the cache.
  const userChannel =
    !authLoading && numericUserId != null
      ? `user:${numericUserId}:notifications`
      : null;

  // PR25 - when the user is in an org workspace, also subscribe to the
  // org-scoped notification channel so notifications fanned out by
  // NotificationEmitter.publishOrgNotification reach all teammates.
  // The gateway authorizer enforces session.organizationId == channel orgId.
  const { currentOrgId } = useCurrentOrg();
  const orgChannel =
    !authLoading && currentOrgId != null && currentOrgId.length > 0
      ? `org:${currentOrgId}:notifications`
      : null;

  const invalidate = () => {
    // Invalidate ALL pages for the active workspace - any may have changed.
    queryClient.invalidateQueries({ queryKey: effectiveQueryKeyBase });
    queryClient.invalidateQueries({ queryKey: effectiveHomeStatusKey });
  };

  useChannel(userChannel, invalidate);
  useChannel(orgChannel, invalidate);

  const deleteMutation = useMutation({
    mutationFn: (buckets: NotificationBucketRef[]) =>
      homeStatusService.deleteNotificationBuckets(buckets),
    onMutate: async (buckets) => {
      await queryClient.cancelQueries({ queryKey: effectiveQueryKey });
      const previous = queryClient.getQueryData<NotificationsPage>(effectiveQueryKey);
      if (previous) {
        // Optimistic: drop the matching buckets from the visible page so the
        // UI removes the rows immediately. Server-truth refetch on settle
        // reconciles unreadCount + hasMore. Defensive `?? []` on previous.items
        // - the cache row is the raw server payload (not coerced) so a stale
        // partial response from a prior fetch could still hold undefined.
        const previousItems = previous.items ?? [];
        const targetSet = new Set(
          buckets.map((b) => `${b.subjectId}:${b.category}`),
        );
        const filtered = previousItems.filter(
          (i) => !targetSet.has(`${i.subjectId}:${i.category}`),
        );
        const removedUnread = previousItems.filter(
          (i) => targetSet.has(`${i.subjectId}:${i.category}`) && i.unread,
        ).length;
        queryClient.setQueryData<NotificationsPage>(effectiveQueryKey, {
          ...previous,
          items: filtered,
          unreadCount: Math.max(0, (previous.unreadCount ?? 0) - removedUnread),
        });
      }
      return { previous };
    },
    onError: (_err, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData(effectiveQueryKey, context.previous);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: effectiveQueryKeyBase });
      queryClient.invalidateQueries({ queryKey: effectiveHomeStatusKey });
    },
  });

  const data = query.data ?? { ...EMPTY_PAGE, page, size };

  // Per-field defaults: same defense as `useHomeStatus`. The wire payload is
  // untyped - a backend that omits an empty `items` (or any field) would crash
  // every consumer calling `.length`/`.map()`. The hook is the HTTP→UI boundary;
  // centralize the defense here so consumers can trust the contract.
  return {
    items: data.items ?? [],
    unreadCount: data.unreadCount ?? 0,
    page: data.page ?? page,
    size: data.size ?? size,
    hasMore: data.hasMore ?? false,
    isLoading: authLoading || query.isLoading,
    error: query.error,
    deleteBuckets: async (buckets) => {
      if (!buckets || buckets.length === 0) return;
      await deleteMutation.mutateAsync(buckets);
    },
  };
}

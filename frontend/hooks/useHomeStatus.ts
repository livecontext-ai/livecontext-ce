'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { useAuth } from '@/lib/providers/smart-providers';
import { useChannel } from '@/lib/websocket/use-channel';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import {
  homeStatusService,
  type HomeStatus,
  type NotificationItem,
  type NotificationsPage,
} from '@/lib/api/orchestrator/home-status.service';
import type { ActiveAutomation } from '@/lib/api/orchestrator/dashboard.service';

const QUERY_KEY = ['home-status'] as const;
/**
 * Query-key prefix for {@code useNotificationsPaged} - defined here too so the
 * mark-all-read mutation can flip the {@code unread} flag in every paged cache
 * entry without a circular import on that hook. The two hooks share a single
 * source of truth on the wire (the same {@code lastSeenAt} cursor in
 * {@code notification_read_state}), so their caches MUST stay in lock-step or
 * the bell rows keep their blue background until the next 60s poll/refetch.
 */
const PAGED_QUERY_KEY_BASE = ['notifications-paged'] as const;
const POLL_INTERVAL_MS = 60_000;
const STALE_TIME_MS = 30_000;

const EMPTY_STATUS: HomeStatus = {
  automations: [],
  items: [],
  unreadCount: 0,
  lastSeenAt: null,
};

export interface UseHomeStatusResult {
  automations: ActiveAutomation[];
  items: NotificationItem[];
  unreadCount: number;
  lastSeenAt: string | null;
  isLoading: boolean;
  error: unknown;
  /** Optimistically zeroes unreadCount, calls POST /notifications/read. */
  markAllRead: () => Promise<void>;
}

/**
 * Single source of truth for the unified {@code NotificationBell}
 * (Inbox tab = items + unreadCount, Activity tab = automations).
 *
 * <ul>
 *   <li>Polls {@code /api/dashboard/home-status} every 60s, refetches on
 *       window focus and on network reconnect.</li>
 *   <li>Subscribes to the user's notification WS channel - backend pushes
 *       {@code notification.created} events on row insert; we use those as
 *       a cache-invalidation signal (the refetch goes through the same HTTP
 *       path so we never need to duplicate the payload contract on the wire).</li>
 *   <li>Skips the WS subscribe while auth is loading
 *       ({@code numericUserId} is null) - the gateway would reject the
 *       channel string {@code user:undefined:notifications} otherwise.</li>
 * </ul>
 */
export function useHomeStatus(): UseHomeStatusResult {
  const { numericUserId, isLoading: authLoading, isAuthenticated } = useAuth();
  const queryClient = useQueryClient();

  // useOrgScopedQuery transparently prefixes the queryKey with
  // `['org', currentOrgId ?? '__personal__', ...]`. Mutations below must hit
  // the same effective key - otherwise getQueryData / setQueryData / cancelQueries
  // all miss the cache and the optimistic update for markAllRead is silently
  // skipped (UI keeps the blue-bg unread rows until the next refetch).
  const orgKeySegment = useCurrentOrgStore((s) => s.currentOrgId) ?? '__personal__';
  const effectiveHomeKey = ['org', orgKeySegment, ...QUERY_KEY] as const;
  const effectivePagedKeyBase = ['org', orgKeySegment, ...PAGED_QUERY_KEY_BASE] as const;

  // Phase 4 (2026-05-18) - org-scoped: home status (Activity tab + bell
  // badge) is gated by active workspace.
  const query = useOrgScopedQuery({
    queryKey: QUERY_KEY,
    queryFn: () => homeStatusService.getHomeStatus(),
    enabled: !authLoading && isAuthenticated,
    refetchInterval: POLL_INTERVAL_MS,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
    staleTime: STALE_TIME_MS,
  });

  // Channel must match the backend publisher exactly:
  // `WorkflowRedisPublisher.publishNotification(tenantId, ...)` writes to
  // `ws:user:{tenantId}:notifications`. The gateway bridge maps that to the
  // client-side channel `user:{tenantId}:notifications`. Tenant id ==
  // numericUserId == X-User-ID per the V1 invariant.
  const channel =
    !authLoading && numericUserId != null
      ? `user:${numericUserId}:notifications`
      : null;

  useChannel(channel, () => {
    queryClient.invalidateQueries({ queryKey: effectiveHomeKey });
  });

  const markMutation = useMutation({
    mutationFn: () => homeStatusService.markAllNotificationsRead(),
    onMutate: async () => {
      // Cancel BOTH caches under the org-prefixed key - `useNotificationsPaged`
      // keeps its own `['org', orgId, 'notifications-paged', page, size]` slice.
      // Without this, an in-flight paged refetch could land mid-mutation and
      // overwrite the optimistic unread=false flip we're about to apply.
      await queryClient.cancelQueries({ queryKey: effectiveHomeKey });
      await queryClient.cancelQueries({ queryKey: effectivePagedKeyBase });

      const previous = queryClient.getQueryData<HomeStatus>(effectiveHomeKey);
      if (previous) {
        queryClient.setQueryData<HomeStatus>(effectiveHomeKey, {
          ...previous,
          unreadCount: 0,
          // Defensive `?? []` - the cache row is the raw server payload (not
          // coerced) so a stale partial fetch could leave items undefined.
          items: (previous.items ?? []).map((i) => ({ ...i, unread: false })),
          lastSeenAt: new Date().toISOString(),
        });
      }

      // Mirror the optimistic update across every `notifications-paged` cache
      // slice for THIS workspace (one per (page, size) combo the bell has ever
      // rendered). Without this the bell rows keep their `bg-blue-50/50` focus
      // + the unread badge stays non-zero until the next 60s poll. Snapshot
      // the prior contents so onError can roll all of them back atomically.
      const previousPaged: Array<[readonly unknown[], NotificationsPage]> = [];
      const pagedEntries = queryClient.getQueriesData<NotificationsPage>({
        queryKey: effectivePagedKeyBase,
      });
      for (const [key, data] of pagedEntries) {
        if (!data) continue;
        previousPaged.push([key, data]);
        queryClient.setQueryData<NotificationsPage>(key, {
          ...data,
          unreadCount: 0,
          items: (data.items ?? []).map((i) => ({ ...i, unread: false })),
        });
      }

      return { previous, previousPaged };
    },
    onError: (_err, _vars, context) => {
      // Rollback optimistic updates if the POST fails - restore home-status
      // first, then every paged slice exactly as it was.
      if (context?.previous) {
        queryClient.setQueryData(effectiveHomeKey, context.previous);
      }
      if (context?.previousPaged) {
        for (const [key, data] of context.previousPaged) {
          queryClient.setQueryData(key, data);
        }
      }
    },
    onSettled: () => {
      // Reconcile with server truth on both caches: the cursor advanced server-
      // side and additional events may have landed mid-flight.
      queryClient.invalidateQueries({ queryKey: effectiveHomeKey });
      queryClient.invalidateQueries({ queryKey: effectivePagedKeyBase });
    },
  });

  const data = query.data ?? EMPTY_STATUS;

  // Per-field defaults: the TS interface promises non-nullable arrays/numbers,
  // but the wire payload is untyped - a backend that omits an empty `automations`
  // field would crash any consumer calling `.some()` / `.length` / `.map()`. The
  // hook is the HTTP→UI boundary; centralize the defense here so every consumer
  // can trust the contract.
  return {
    automations: data.automations ?? [],
    items: data.items ?? [],
    unreadCount: data.unreadCount ?? 0,
    lastSeenAt: data.lastSeenAt ?? null,
    isLoading: authLoading || query.isLoading,
    error: query.error,
    markAllRead: () => markMutation.mutateAsync(),
  };
}

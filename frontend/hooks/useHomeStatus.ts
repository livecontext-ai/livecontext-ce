'use client';

import { useCallback } from 'react';
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
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
 * The key the home-status cache actually answers to.
 *
 * <p>`useOrgScopedQuery` prefixes every key with the active workspace, so a plain
 * `['home-status']` matches no cached query: it invalidates nothing, throws nothing, and
 * leaves the surface frozen. Three call sites in this file need that prefix and it is built
 * here once, because the failure mode of them drifting is silence.
 */
function homeStatusKeyFor(orgKeySegment: string) {
  return ['org', orgKeySegment, ...QUERY_KEY] as const;
}

/**
 * When each (client, workspace) pair was last ASKED for its rows.
 *
 * <p>Deliberately not a ref inside the hook. The two forms of this refresh are called from
 * DIFFERENT components: a pin site asks, and the bell visit that follows is a second component
 * entirely. A per-component ref cannot see the other's ask, so a visit landing inside the bound
 * would cancel and re-issue the request the pin had just put on the wire - two requests for one
 * action, and the second one starting from scratch.
 *
 * <p>Keyed on the QueryClient, so the scope is exactly one app instance: a client that goes away
 * takes its stamps with it, and a test holding a fresh client starts from "never asked" without
 * needing a reset hook.
 */
const lastAskedByClient = new WeakMap<QueryClient, Map<string, number>>();

function lastAskedAtFor(client: QueryClient, orgKeySegment: string): number {
  return lastAskedByClient.get(client)?.get(orgKeySegment) ?? 0;
}

function stampAsked(client: QueryClient, orgKeySegment: string, at: number): void {
  const stamps = lastAskedByClient.get(client) ?? new Map<string, number>();
  stamps.set(orgKeySegment, at);
  lastAskedByClient.set(client, stamps);
}
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
  const effectiveHomeKey = homeStatusKeyFor(orgKeySegment);
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

/** Options for the refresh returned by {@link useRefreshHomeStatus}. */
export interface RefreshHomeStatusOptions {
  /**
   * Stay silent when the rows were already asked for, or answered, within this many
   * milliseconds. Omit to ask unconditionally.
   */
  freshForMs?: number;
}

/**
 * Whether a bounded ask should actually go out. Pure, and exported, because it is the whole
 * rule: branches that stop being visible from the outside the moment they are inlined in a hook.
 *
 * <p>"Fresh" counts an ASK as well as an ANSWER, and both halves are load-bearing:
 * <ul>
 *   <li><b>Answered</b> - a payload that recent cannot be missing an action the user has only
 *       just performed, so asking again would buy nothing.</li>
 *   <li><b>Asked</b> - invalidating cancels a fetch already on the wire and starts another, so
 *       without this half a user flipping tabs quickly would restart the same request over and
 *       over and never see a row land. It is also what makes it safe to ask WHILE a fetch is in
 *       flight, and asking then is the point: a poll issued BEFORE the user's action is about
 *       to answer with pre-action rows and clear the staleness for another full poll, which is
 *       the reported symptom wearing a different hat. It counts only the asks made THROUGH this
 *       hook: the 60s poll, a focus refetch and the WS invalidation start fetches it does not
 *       see, so a visit can still restart one of those. Bounded and cheap - the restart costs
 *       one request and answers with rows that include the user's action, which is the point.</li>
 * </ul>
 *
 * <p>A failed load counts as an answer. {@code dataUpdatedAt} never moves on failure, so
 * reading it alone would leave a query whose load failed unaskable for good, and a failed load
 * is exactly when a visit most wants to retry.
 *
 * <p>"Answered" is the cache's last UPDATE, not strictly the last thing the server said: an
 * optimistic local write bumps {@code dataUpdatedAt} too, and this cache takes exactly one
 * ({@code markAllRead}). So marking the inbox read and stepping onto Triggers within the bound
 * skips that visit's ask. Known and accepted: mark-all-read changes no automation row, the next
 * visit past the bound asks, and the alternative is the hook keeping its own settle clock to
 * win back two seconds on one path.
 *
 * <p>Never settled at all is the one case never worth asking: nothing older is on screen to be
 * stale, so there is nothing to correct.
 *
 * <p>One residual, stated because it is the same shape as the bug and its window is small rather
 * than zero. React Query only cancels and restarts a fetch when the query already HAS data, so
 * an ask made while the FIRST load is on the wire cannot restart it: that load lands with
 * pre-action rows, and being brand new they then read as fresh for the length of the bound. It
 * takes acting inside the first home-status load of a session or of a workspace switch, and it
 * costs one bound, after which the next visit asks.
 */
export function shouldAskForHomeStatus(
  state: { dataUpdatedAt: number; errorUpdatedAt: number } | undefined,
  lastAskedAt: number,
  freshForMs: number,
  now: number,
): boolean {
  if (!state) return false;
  const settledAt = Math.max(state.dataUpdatedAt, state.errorUpdatedAt);
  if (settledAt === 0) return false;
  return now - Math.max(settledAt, lastAskedAt) >= freshForMs;
}

/**
 * Ask for the bell's automation rows again, now.
 *
 * <p>The list polls on a 60 second interval, which is right for a passive countdown and
 * wrong immediately after the user has just CHANGED one: acting on a row from its own menu
 * and watching the fire time sit unmoved for up to a minute reads as "the action did
 * nothing", which is exactly how the run-instead defect was reported.
 *
 * <p>Exported from here rather than rebuilt at the call site because the key is
 * org-scoped: {@code useOrgScopedQuery} prefixes it with the active workspace, and a
 * hand-written {@code ['home-status']} would invalidate nothing while looking correct.
 *
 * <p>Called with no options it always asks. That is what an ACTION wants: the caller just
 * changed the data, so how old the cached copy is says nothing about whether it is still
 * right. Called with {@code freshForMs} it asks only when the rows are not already that
 * fresh, which is what a SURFACE wants: the bell's Triggers tab tops its rows up every time
 * the user lands on it, without turning a tab click into a request.
 */
export function useRefreshHomeStatus(): (options?: RefreshHomeStatusOptions) => void {
  const queryClient = useQueryClient();
  const orgKeySegment = useCurrentOrgStore((s) => s.currentOrgId) ?? '__personal__';
  return useCallback((options?: RefreshHomeStatusOptions) => {
    const key = homeStatusKeyFor(orgKeySegment);
    const freshForMs = options?.freshForMs;
    // Read once: the decision below and the stamp that follows it must be the same instant.
    const now = Date.now();
    // Exact, matching the complete key. `invalidateQueries` matches by prefix, and with a key
    // this long the two select the same single query - but reading exactly is what guarantees
    // the state consulted is that query's and not some future sibling's.
    const state = queryClient.getQueryState(key);
    if (freshForMs !== undefined
      && !shouldAskForHomeStatus(state, lastAskedAtFor(queryClient, orgKeySegment), freshForMs, now)) {
      return;
    }
    // Stamped per workspace: an ask against the workspace the user just left says nothing about
    // the rows of the one now on screen, and silencing the first visit after a switch is exactly
    // the staleness this exists to remove.
    stampAsked(queryClient, orgKeySegment, now);
    queryClient.invalidateQueries({ queryKey: key });
  }, [queryClient, orgKeySegment]);
}

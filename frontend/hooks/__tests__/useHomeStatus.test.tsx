/**
 * @vitest-environment jsdom
 *
 * Tests for {@code useHomeStatus}. Focus on:
 * - WS channel subscription is null while auth is loading (gateway would
 *   reject `user:undefined:notifications` otherwise)
 * - WS event triggers React Query cache invalidation
 * - markAllRead optimistically zeroes unreadCount
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';
import { useHomeStatus } from '../useHomeStatus';

// Mock the auth provider - vary numericUserId / isLoading per test.
const authMock = vi.hoisted(() => ({
  current: { numericUserId: 42, isLoading: false, isAuthenticated: true },
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => authMock.current,
}));

// Capture useChannel calls so we can assert on the channel arg + invoke the
// handler to simulate a WS event.
const channelCalls: Array<{ channel: string | null; handler: () => void }> = [];
vi.mock('@/lib/websocket/use-channel', () => ({
  useChannel: (channel: string | null, handler: () => void) => {
    channelCalls.push({ channel, handler });
  },
}));

vi.mock('@/lib/api/orchestrator/home-status.service', () => ({
  homeStatusService: {
    getHomeStatus: vi.fn(async () => ({
      automations: [],
      items: [
        {
          subjectId: 'wf-1',
          subjectName: 'WF',
          subjectType: 'WORKFLOW',
          runIdPublic: 'run_1',
          category: 'RUN_FAILED',
          severity: 'error',
          count: 1,
          firstEventAt: '2026-05-08T08:00:00Z',
          lastEventAt: '2026-05-08T09:00:00Z',
          unread: true,
        },
      ],
      unreadCount: 1,
      lastSeenAt: null,
    })),
    markAllNotificationsRead: vi.fn(async () => undefined),
  },
}));

import { homeStatusService } from '@/lib/api/orchestrator/home-status.service';

function wrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

describe('useHomeStatus', () => {
  beforeEach(() => {
    channelCalls.length = 0;
    vi.clearAllMocks();
    authMock.current = { numericUserId: 42, isLoading: false, isAuthenticated: true };
  });
  afterEach(() => vi.restoreAllMocks());

  it('Subscribes to user:{numericUserId}:notifications when auth is ready', async () => {
    renderHook(() => useHomeStatus(), { wrapper: wrapper() });

    await waitFor(() => expect(channelCalls.length).toBeGreaterThan(0));
    expect(channelCalls[channelCalls.length - 1].channel).toBe('user:42:notifications');
  });

  it('Passes a NULL channel while auth is loading (no premature subscribe)', async () => {
    authMock.current = { numericUserId: null, isLoading: true, isAuthenticated: false };

    renderHook(() => useHomeStatus(), { wrapper: wrapper() });

    expect(channelCalls.length).toBeGreaterThan(0);
    expect(channelCalls[channelCalls.length - 1].channel).toBeNull();
  });

  it('WS event triggers a cache invalidation → second fetch fires', async () => {
    const { result } = renderHook(() => useHomeStatus(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.unreadCount).toBe(1));
    expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1);

    await act(async () => {
      // Simulate the backend emitting a notification.created push.
      channelCalls[channelCalls.length - 1].handler();
    });

    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2));
  });

  it('Coerces missing array fields to [] so consumers can call .some()/.map() safely (regression for NotificationBell crash 2026-05-11)', async () => {
    // The wire payload is untyped - a backend that omits an empty `automations`
    // (or any other array/scalar) would crash NotificationBell's hasImminentFire,
    // which calls `automations.some(...)`. The hook must coerce on its return
    // boundary so consumers can trust the contract.
    (homeStatusService.getHomeStatus as any).mockResolvedValueOnce({
      // automations omitted entirely - pre-fix this returned `undefined`
      items: undefined,
      unreadCount: undefined,
      lastSeenAt: undefined,
    });

    const { result } = renderHook(() => useHomeStatus(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(Array.isArray(result.current.automations)).toBe(true);
    expect(result.current.automations).toHaveLength(0);
    expect(Array.isArray(result.current.items)).toBe(true);
    expect(result.current.items).toHaveLength(0);
    expect(result.current.unreadCount).toBe(0);
    expect(result.current.lastSeenAt).toBeNull();

    // The contract a consumer would rely on - must not throw.
    expect(() => result.current.automations.some(() => true)).not.toThrow();
  });

  it('markAllRead optimistically zeroes unreadCount before the POST settles', async () => {
    const { result } = renderHook(() => useHomeStatus(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.unreadCount).toBe(1));

    // Slow the mutation so we can observe the optimistic state.
    let resolveMutation!: () => void;
    (homeStatusService.markAllNotificationsRead as any).mockImplementationOnce(
      () => new Promise<void>((r) => { resolveMutation = r; }),
    );

    act(() => { void result.current.markAllRead(); });

    // Optimistic update is synchronous - unreadCount should be 0 already.
    await waitFor(() => expect(result.current.unreadCount).toBe(0));

    resolveMutation();
    await waitFor(() => expect(homeStatusService.markAllNotificationsRead).toHaveBeenCalledTimes(1));
  });

  it('markAllRead also flips every notifications-paged cache slice (regression: bell rows stayed blue until refresh)', async () => {
    // Repro: pre-fix, useHomeStatus.markAllRead only touched the `home-status`
    // cache. The bell's items list comes from useNotificationsPaged
    // (`['notifications-paged', page, size]`), a SEPARATE cache, so its rows
    // kept `unread: true` (blue background) and the badge stayed non-zero until
    // the next 60s poll or a manual refresh. This test pins the cross-cache
    // optimistic flip so the bug doesn't return.
    //
    // We share a single QueryClient between the hook and the manually-seeded
    // paged caches, so we build the wrapper inline (the default `wrapper()`
    // factory spins up a fresh client per call).
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const sharedWrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );

    // Seed two paged cache slices (the bell may have rendered page 0 then
    // paginated to page 1). Both must be flipped.
    const seedItems = [
      {
        subjectId: 'wf-1',
        subjectName: 'WF',
        subjectType: 'WORKFLOW',
        runIdPublic: 'run_1',
        category: 'RUN_FAILED',
        severity: 'error',
        count: 1,
        firstEventAt: '2026-05-08T08:00:00Z',
        lastEventAt: '2026-05-08T09:00:00Z',
        unread: true,
      },
    ];
    // useCurrentOrgStore returns undefined in test → orgKeySegment='__personal__'
    // → effective paged key = ['org','__personal__','notifications-paged', page, size]
    const ORG_PREFIX = ['org', '__personal__', 'notifications-paged'] as const;
    client.setQueryData([...ORG_PREFIX, 0, 15], {
      items: seedItems,
      unreadCount: 3,
      page: 0,
      size: 15,
      hasMore: true,
    });
    client.setQueryData([...ORG_PREFIX, 1, 15], {
      items: [{ ...seedItems[0], subjectId: 'wf-2', subjectName: 'WF2' }],
      unreadCount: 3,
      page: 1,
      size: 15,
      hasMore: false,
    });

    const { result } = renderHook(() => useHomeStatus(), { wrapper: sharedWrapper });

    await waitFor(() => expect(result.current.unreadCount).toBe(1));

    await act(async () => { await result.current.markAllRead(); });

    // BOTH paged slices must be flipped - unread=false on every item +
    // unreadCount=0. Without the cross-cache fix, items still had unread=true
    // and the bell would render the blue focus background until refresh.
    const page0 = client.getQueryData<any>([...ORG_PREFIX, 0, 15]);
    const page1 = client.getQueryData<any>([...ORG_PREFIX, 1, 15]);
    expect(page0.items.every((i: any) => i.unread === false)).toBe(true);
    expect(page0.unreadCount).toBe(0);
    expect(page1.items.every((i: any) => i.unread === false)).toBe(true);
    expect(page1.unreadCount).toBe(0);
  });

  it('markAllRead rolls back BOTH caches when the POST fails', async () => {
    // Failure path: server rejected the mark-read POST. We must restore the
    // pre-mutation state on every cache we touched, not just home-status.
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const sharedWrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );

    const seedPage = {
      items: [
        {
          subjectId: 'wf-1',
          subjectName: 'WF',
          subjectType: 'WORKFLOW',
          runIdPublic: 'run_1',
          category: 'RUN_FAILED',
          severity: 'error',
          count: 1,
          firstEventAt: '2026-05-08T08:00:00Z',
          lastEventAt: '2026-05-08T09:00:00Z',
          unread: true,
        },
      ],
      unreadCount: 5,
      page: 0,
      size: 15,
      hasMore: false,
    };
    const ORG_PREFIX = ['org', '__personal__', 'notifications-paged'] as const;
    client.setQueryData([...ORG_PREFIX, 0, 15], seedPage);

    (homeStatusService.markAllNotificationsRead as any).mockRejectedValueOnce(
      new Error('boom'),
    );

    const { result } = renderHook(() => useHomeStatus(), { wrapper: sharedWrapper });
    await waitFor(() => expect(result.current.unreadCount).toBe(1));

    await act(async () => {
      try { await result.current.markAllRead(); } catch { /* expected */ }
    });

    // After rollback: paged cache MUST be byte-identical to the seed (items
    // still unread, unreadCount back to 5). Without per-cache rollback in
    // onError we'd leak the optimistic flip to the user despite the failure.
    const restored = client.getQueryData<any>([...ORG_PREFIX, 0, 15]);
    expect(restored.items[0].unread).toBe(true);
    expect(restored.unreadCount).toBe(5);
  });
});

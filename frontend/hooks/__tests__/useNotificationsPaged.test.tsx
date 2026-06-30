/**
 * @vitest-environment jsdom
 *
 * Regression test for the NotificationBell crash family (2026-05-11).
 * Same pattern as useHomeStatus: the wire payload is untyped, so a backend
 * that omits an empty `items` (or any other field) would crash every consumer
 * calling `.length`/`.map()`. The hook coerces per-field at the HTTP→UI
 * boundary so consumers can trust the contract.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';
import { useNotificationsPaged } from '../useNotificationsPaged';

const authMock = vi.hoisted(() => ({
  current: { numericUserId: 42, isLoading: false, isAuthenticated: true },
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => authMock.current,
}));

vi.mock('@/lib/websocket/use-channel', () => ({
  useChannel: () => { /* no-op for these tests */ },
}));

vi.mock('@/lib/api/orchestrator/home-status.service', () => ({
  homeStatusService: {
    getNotificationsPage: vi.fn(),
    deleteNotificationBuckets: vi.fn(async () => undefined),
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

describe('useNotificationsPaged - regression for NotificationBell undefined-field crash', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authMock.current = { numericUserId: 42, isLoading: false, isAuthenticated: true };
  });
  afterEach(() => vi.restoreAllMocks());

  it('Coerces missing fields so consumers can call .length/.map() safely', async () => {
    // Pre-fix: backend omitting any field made the hook return undefined for it,
    // crashing NotificationBell's `items.length === 0` (line 78), `items.map()`
    // (line 109), and the optimistic `previous.items.filter()` in onMutate.
    (homeStatusService.getNotificationsPage as any).mockResolvedValueOnce({
      // items, unreadCount, hasMore all omitted
      page: 0,
      size: 15,
    });

    const { result } = renderHook(() => useNotificationsPaged(0, 15), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(Array.isArray(result.current.items)).toBe(true);
    expect(result.current.items).toHaveLength(0);
    expect(result.current.unreadCount).toBe(0);
    expect(result.current.hasMore).toBe(false);
    expect(result.current.page).toBe(0);
    expect(result.current.size).toBe(15);

    // The contracts a consumer relies on - must not throw.
    expect(() => result.current.items.length === 0).not.toThrow();
    expect(() => result.current.items.map((i) => i)).not.toThrow();
  });

  it('Falls back to caller page/size when payload omits them', async () => {
    (homeStatusService.getNotificationsPage as any).mockResolvedValueOnce({
      items: [],
      unreadCount: 0,
      hasMore: false,
      // page + size omitted - fall back to the args we passed in
    });

    const { result } = renderHook(() => useNotificationsPaged(3, 25), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.page).toBe(3);
    expect(result.current.size).toBe(25);
  });
});

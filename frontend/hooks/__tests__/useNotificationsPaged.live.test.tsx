/**
 * @vitest-environment jsdom
 *
 * LIVE-PATH coverage for useNotificationsPaged (the notification bell).
 * The sibling test file stubs useChannel to a no-op, so the WS-driven
 * unread-bump path had zero coverage. Here the useChannel mock CAPTURES the
 * handler the hook registers, and the tests drive it like the gateway would:
 * a notification event arriving on `user:{id}:notifications` (or
 * `org:{id}:notifications` in a workspace) must invalidate the react-query
 * cache and surface the new unreadCount/items to the bell.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';
import { useNotificationsPaged } from '../useNotificationsPaged';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

const authMock = vi.hoisted(() => ({
  current: { numericUserId: 42, isLoading: false, isAuthenticated: true },
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => authMock.current,
}));

// Capture the handler(s) the hook passes to useChannel, keyed by channel name,
// so tests can push events the way the real WS client would.
const channelMock = vi.hoisted(() => ({
  handlers: new Map<string, (event: unknown) => void>(),
}));
vi.mock('@/lib/websocket/use-channel', () => ({
  useChannel: (channel: string | null, handler: (event: unknown) => void) => {
    if (channel) channelMock.handlers.set(channel, handler);
  },
}));

vi.mock('@/lib/api/orchestrator/home-status.service', () => ({
  homeStatusService: {
    getNotificationsPage: vi.fn(),
    deleteNotificationBuckets: vi.fn(async () => undefined),
  },
}));

import { homeStatusService } from '@/lib/api/orchestrator/home-status.service';

const EMPTY_PAGE = { items: [], unreadCount: 0, page: 0, size: 15, hasMore: false };

const NOTIFICATION_ITEM = {
  subjectId: 'wf-1',
  category: 'workflow',
  title: 'Run finished',
  unread: true,
};

const BUMPED_PAGE = {
  items: [NOTIFICATION_ITEM],
  unreadCount: 1,
  page: 0,
  size: 15,
  hasMore: false,
};

/** First fetch returns the empty page; every refetch returns the bumped page. */
function primeFetchSequence() {
  let calls = 0;
  (homeStatusService.getNotificationsPage as ReturnType<typeof vi.fn>).mockImplementation(
    async () => (calls++ === 0 ? EMPTY_PAGE : BUMPED_PAGE),
  );
}

function wrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

describe('useNotificationsPaged - live WS unread-bump path', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    channelMock.handlers.clear();
    authMock.current = { numericUserId: 42, isLoading: false, isAuthenticated: true };
    useCurrentOrgStore.setState({ currentOrgId: null, currentOrgRole: null });
  });
  afterEach(() => vi.restoreAllMocks());

  it('a user-channel notification event invalidates the cache and bumps unreadCount + items', async () => {
    primeFetchSequence();

    const { result } = renderHook(() => useNotificationsPaged(0, 15), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.unreadCount).toBe(0);
    expect(result.current.items).toHaveLength(0);

    // The hook must have subscribed to the backend publisher channel.
    const handler = channelMock.handlers.get('user:42:notifications');
    expect(handler, 'hook should subscribe to user:{id}:notifications').toBeTypeOf('function');

    // Gateway delivers a notification.created event - payload content is
    // irrelevant to the hook (it re-fetches server truth), shape is realistic.
    act(() => {
      handler!({ event: 'notification.created', subjectId: 'wf-1', category: 'workflow' });
    });

    await waitFor(() => expect(result.current.unreadCount).toBe(1));
    expect(result.current.items).toHaveLength(1);
    expect(result.current.items[0]).toMatchObject({ subjectId: 'wf-1', unread: true });
    // The bump came from a refetch, not a locally-fabricated item.
    expect(homeStatusService.getNotificationsPage).toHaveBeenCalledTimes(2);
  });

  it('does NOT subscribe to an org channel in personal scope', async () => {
    primeFetchSequence();

    const { result } = renderHook(() => useNotificationsPaged(0, 15), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    const orgChannels = [...channelMock.handlers.keys()].filter((c) => c.startsWith('org:'));
    expect(orgChannels).toHaveLength(0);
  });

  it('in an org workspace, an org-channel event also invalidates and bumps the bell', async () => {
    useCurrentOrgStore.setState({ currentOrgId: 'org-77', currentOrgRole: 'MEMBER' });
    primeFetchSequence();

    const { result } = renderHook(() => useNotificationsPaged(0, 15), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.unreadCount).toBe(0);

    // Both channels are live in a workspace: personal + org fan-out.
    expect(channelMock.handlers.has('user:42:notifications')).toBe(true);
    const orgHandler = channelMock.handlers.get('org:org-77:notifications');
    expect(orgHandler, 'hook should subscribe to org:{id}:notifications').toBeTypeOf('function');

    act(() => {
      orgHandler!({ event: 'notification.created', subjectId: 'wf-1', category: 'workflow' });
    });

    await waitFor(() => expect(result.current.unreadCount).toBe(1));
    expect(result.current.items).toHaveLength(1);
  });

  it('registers no notification channel while auth is still loading', async () => {
    authMock.current = { numericUserId: 42, isLoading: true, isAuthenticated: false };
    primeFetchSequence();

    renderHook(() => useNotificationsPaged(0, 15), { wrapper: wrapper() });

    expect(channelMock.handlers.size).toBe(0);
  });
});

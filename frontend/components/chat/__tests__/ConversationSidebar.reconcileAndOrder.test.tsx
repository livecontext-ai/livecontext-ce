// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Regression for the "sidebar conversation bar is not up to date after navigation"
// bug: the list lives in the persistent /app layout and its React Query cache is
// sticky (staleTime: Infinity, no refetch on mount/focus). While on the chat surface
// it is kept current by live streaming updates from the chat page; off that surface
// those updates stop. So leaving the chat for another surface (e.g. Settings) right
// after starting a conversation could leave the sidebar stale: missing conversation,
// placeholder title, or wrong order, until a full page reload.
//
// The fix is two-fold and both behaviours are pinned here:
//  1. When the user LEAVES a conversation surface, the sidebar forces a full refresh
//     from the server (rewind to page 0 + refetch), self-healing any missed update
//     even when the list had been paginated.
//  2. The merged list is sorted by most-recent activity so the displayed order follows
//     authoritative timestamps instead of the in-memory context's drift-prone order.

// Mutated per test to drive the mocked hooks.
let mockConversations: Array<Record<string, unknown>> = [];
let mockSharedConversations: Array<Record<string, unknown>> = [];
let mockPathname = '/app/chat';
let mockCurrentView = 'chat';
let mockUrlConversationId: string | null = null;
let mockIsAuthenticated = true;
const mockForceRefreshConversations = vi.fn(() => Promise.resolve());

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({
  usePathname: () => mockPathname,
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('next/navigation', () => ({ useSearchParams: () => ({ get: () => null }) }));
vi.mock('@/hooks/useConversationHistory', () => ({
  useConversationHistory: () => ({
    conversations: mockConversations,
    loading: false,
    error: null,
    hasMore: false,
    selectConversation: vi.fn(),
    loadMessages: vi.fn(),
    deleteConversation: vi.fn(),
    loadMoreConversations: vi.fn(),
    loadConversationById: vi.fn(),
    clearMessages: vi.fn(),
    forceRefreshConversations: mockForceRefreshConversations,
  }),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({
  useUnifiedApp: () => ({ state: { conversations: mockSharedConversations, hasMore: false } }),
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: mockIsAuthenticated, isLoading: false, user: { sub: 'u1', email: 'u@e.com' } }),
}));
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => ({ view: mockCurrentView, conversationId: mockUrlConversationId, isDetailPage: false }),
}));
vi.mock('@/hooks/useIsStreaming', () => ({ useIsStreaming: () => false }));
vi.mock('@/lib/hooks/useOrgScopedQuery', () => ({ useOrgScopedQuery: () => ({ data: undefined }) }));
vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: () => ({ data: [] }),
}));
vi.mock('@/hooks/useProjects', () => ({
  useProjects: () => ({ projects: [], loading: false }),
  useProjectMutations: () => ({ deleteProject: vi.fn() }),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getAgentAvatars: vi.fn(() => Promise.resolve([])) } }));
vi.mock('@/components/project/ProjectMultiStepModal', () => ({
  getProjectIcon: () => null,
  ProjectMultiStepModal: () => null,
}));
vi.mock('@/components/dm/DmSidebarList', () => ({ DmSidebarList: () => <div data-testid="dm-sidebar-list" /> }));
vi.mock('@/components/sharing/ShareLinkDialog', () => ({ ShareLinkDialog: () => null }));

import { ConversationSidebar } from '../ConversationSidebar';

const renderSidebar = () =>
  render(<ConversationSidebar onConversationSelect={vi.fn()} onNewChat={vi.fn()} onNavigate={vi.fn()} />);
const rerenderSidebar = (rerender: (ui: React.ReactElement) => void) =>
  rerender(<ConversationSidebar onConversationSelect={vi.fn()} onNewChat={vi.fn()} onNavigate={vi.fn()} />);

// Put the sidebar on the chat (conversation) surface.
const onChat = () => {
  mockCurrentView = 'chat';
  mockPathname = '/app/chat';
  mockUrlConversationId = null;
};
// Put the sidebar on a non-conversation surface (Settings).
const onSettings = () => {
  mockCurrentView = 'settings';
  mockPathname = '/app/settings';
  mockUrlConversationId = null;
};
// Another non-conversation surface (Data/Tables).
const onData = () => {
  mockCurrentView = 'data';
  mockPathname = '/app/tables';
  mockUrlConversationId = null;
};

function resetState() {
  mockConversations = [{ id: 'c1', title: 'Existing chat', updatedAt: '2026-06-01T10:00:00Z' }];
  mockSharedConversations = [];
  mockIsAuthenticated = true;
  onChat();
}

describe('ConversationSidebar - reconcile on leaving a conversation surface', () => {
  beforeEach(resetState);
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('does NOT refresh on the initial mount (the query autoload handles that)', () => {
    renderSidebar();
    expect(mockForceRefreshConversations).not.toHaveBeenCalled();
  });

  it('forces a full refresh when leaving the chat surface (chat -> settings)', () => {
    const { rerender } = renderSidebar();
    expect(mockForceRefreshConversations).not.toHaveBeenCalled();

    onSettings();
    rerenderSidebar(rerender);

    expect(mockForceRefreshConversations).toHaveBeenCalledTimes(1);
  });

  it('does NOT refresh while staying on a conversation surface (switching conversations)', () => {
    const { rerender } = renderSidebar();

    // Still a conversation surface: a different open conversation.
    mockUrlConversationId = 'other-conv';
    rerenderSidebar(rerender);

    expect(mockForceRefreshConversations).not.toHaveBeenCalled();
  });

  it('refreshes only once per chat exit, not on each subsequent non-chat hop', () => {
    const { rerender } = renderSidebar();

    onSettings();
    rerenderSidebar(rerender);
    expect(mockForceRefreshConversations).toHaveBeenCalledTimes(1);

    // settings -> data: both non-conversation surfaces, no new chat exit.
    onData();
    rerenderSidebar(rerender);
    expect(mockForceRefreshConversations).toHaveBeenCalledTimes(1);
  });

  it('does NOT refresh when leaving chat while unauthenticated', () => {
    mockIsAuthenticated = false;
    const { rerender } = renderSidebar();

    onSettings();
    rerenderSidebar(rerender);

    expect(mockForceRefreshConversations).not.toHaveBeenCalled();
  });
});

describe('ConversationSidebar - order by most-recent activity', () => {
  beforeEach(resetState);
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  // True when `later` appears after `earlier` in document order.
  const isAfter = (earlier: HTMLElement, later: HTMLElement) =>
    Boolean(earlier.compareDocumentPosition(later) & Node.DOCUMENT_POSITION_FOLLOWING);

  it('renders conversations newest-first regardless of input order', () => {
    // Supplied out of order on purpose; expected render order is by updatedAt desc.
    mockConversations = [
      { id: 'a', title: 'Conv Alpha', updatedAt: '2026-06-01T10:00:00Z' },
      { id: 'b', title: 'Conv Beta', updatedAt: '2026-06-03T10:00:00Z' },
      { id: 'c', title: 'Conv Gamma', updatedAt: '2026-06-02T10:00:00Z' },
    ];
    renderSidebar();

    const alpha = screen.getByText('Conv Alpha');
    const beta = screen.getByText('Conv Beta');
    const gamma = screen.getByText('Conv Gamma');

    // Expected order: Beta (06-03) -> Gamma (06-02) -> Alpha (06-01).
    expect(isAfter(beta, gamma)).toBe(true);
    expect(isAfter(gamma, alpha)).toBe(true);
  });

  it('orders a context-only (optimistic) conversation above an older server row', () => {
    // The merge path: an entry present only in the shared context (no server DTO)
    // is rendered from the synthetic placeholder and must still sort by its
    // timestamp. This is the just-created-conversation case.
    mockConversations = [{ id: 'r1', title: 'Server One', updatedAt: '2026-06-02T10:00:00Z' }];
    mockSharedConversations = [
      { id: 'r1', title: 'Server One' },
      { id: 's1', title: 'Optimistic New', updatedAt: '2026-06-05T10:00:00Z' },
    ];
    renderSidebar();

    expect(isAfter(screen.getByText('Optimistic New'), screen.getByText('Server One'))).toBe(true);
  });

  it('sorts a conversation with a missing/invalid timestamp last', () => {
    // "Conv Bad" supplied first with an unparseable timestamp; it must fall to
    // the bottom rather than poison the comparator.
    mockConversations = [
      { id: 'b', title: 'Conv Bad', updatedAt: 'not-a-date' },
      { id: 'a', title: 'Conv Valid', updatedAt: '2026-06-04T10:00:00Z' },
    ];
    renderSidebar();

    expect(isAfter(screen.getByText('Conv Valid'), screen.getByText('Conv Bad'))).toBe(true);
  });

  it('falls back to createdAt when updatedAt is missing', () => {
    // Beta supplied first on purpose: a no-op (insertion-order) render would
    // leave it on top; the sort must lift Alpha (more recent) above it.
    mockConversations = [
      { id: 'b', title: 'Conv Beta', updatedAt: '2026-06-04T10:00:00Z' },
      { id: 'a', title: 'Conv Alpha', createdAt: '2026-06-05T10:00:00Z' },
    ];
    renderSidebar();

    // Alpha (createdAt 06-05) is more recent than Beta (updatedAt 06-04).
    expect(isAfter(screen.getByText('Conv Alpha'), screen.getByText('Conv Beta'))).toBe(true);
  });
});

// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Regression: the Chats⇄Messages toggle used to navigate to a standalone /app/messages page
// (router.push / onNavigate('/app/messages')). Messages is now a PURE sidebar view - toggling
// only flips the list and returns the main panel to Home via onNewChat; it must NEVER navigate
// to /app/messages. (Opening a specific thread still navigates, but that is DmSidebarList, mocked
// out here.) This test fails on the pre-fix handler and passes on the new one.

const push = vi.fn();
// Mutable so each test can place the sidebar on Home vs. inside a DM thread route.
let pathname = '/app/chat';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => pathname, useRouter: () => ({ push }) }));
vi.mock('next/navigation', () => ({ useSearchParams: () => ({ get: () => null }) }));
vi.mock('@/hooks/useConversationHistory', () => ({
  useConversationHistory: () => ({
    conversations: [],
    loading: false,
    error: null,
    hasMore: false,
    selectConversation: vi.fn(),
    loadMessages: vi.fn(),
    deleteConversation: vi.fn(),
    loadMoreConversations: vi.fn(),
    loadConversationById: vi.fn(),
    clearMessages: vi.fn(),
  }),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({
  useUnifiedApp: () => ({ state: { conversations: [], hasMore: false } }),
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isLoading: false, user: { sub: 'u1', email: 'u@e.com' } }),
}));
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => ({ view: 'chat', conversationId: undefined, isDetailPage: false }),
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

describe('ConversationSidebar - Messages toggle is a pure view', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    pathname = '/app/chat';
  });

  it('entering from Home: flips to Messages mode via onNewChat and never navigates to /app/messages', () => {
    pathname = '/app/chat';
    const onNewChat = vi.fn();
    const onNavigate = vi.fn();
    render(<ConversationSidebar onConversationSelect={vi.fn()} onNewChat={onNewChat} onNavigate={onNavigate} />);

    fireEvent.click(screen.getByTestId('dm-mode-toggle'));

    // Returns the main panel to Home (new chat) - no standalone /app/messages route in either layer.
    expect(onNewChat).toHaveBeenCalledTimes(1);
    expect(push).not.toHaveBeenCalled();
    expect(onNavigate).not.toHaveBeenCalledWith('/app/messages');
    // Entering Messages mode swaps the sidebar list to the DM list.
    expect(screen.getByTestId('dm-sidebar-list')).toBeInTheDocument();
    // Home stays focused in Messages mode - the main panel is still Home (/app/chat).
    expect(screen.getByText('sidebar.home').closest('button')).toHaveClass('bg-surface-hover');
  });

  it('leaving from a thread page: flips back to Chats via onNewChat, still no /app/messages navigation', () => {
    // On a DM thread route the effect pins the toggle to Messages mode, so the click here EXITS it.
    pathname = '/app/messages/t1';
    const onNewChat = vi.fn();
    const onNavigate = vi.fn();
    render(<ConversationSidebar onConversationSelect={vi.fn()} onNewChat={onNewChat} onNavigate={onNavigate} />);

    // Pinned to Messages on mount (the DM list is shown).
    expect(screen.getByTestId('dm-sidebar-list')).toBeInTheDocument();
    // On a real DM thread route the main panel is NOT Home, so Home is un-highlighted.
    expect(screen.getByText('sidebar.home').closest('button')).not.toHaveClass('bg-surface-hover');

    fireEvent.click(screen.getByTestId('dm-mode-toggle'));

    // Leaving returns to Home via onNewChat (the effect does not re-pin: pathname is unchanged in
    // this unit, and in the app onNewChat navigates to /app/chat where the guard is false).
    expect(onNewChat).toHaveBeenCalledTimes(1);
    expect(push).not.toHaveBeenCalled();
    expect(onNavigate).not.toHaveBeenCalledWith('/app/messages');
    // Back to the Chats list - the DM list is gone.
    expect(screen.queryByTestId('dm-sidebar-list')).not.toBeInTheDocument();
  });
});

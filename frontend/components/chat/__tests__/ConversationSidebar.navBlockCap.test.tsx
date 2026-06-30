// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Regression: in expanded mode the navigation entries (Projects, Board, Agents,
// Applications, Workflows, Interface, Tables, Files) were all flex-shrink-0 siblings,
// so with enough projects/items they filled the column and pushed the Chats list out
// of view (clipped by the sidebar's overflow-hidden root). The fix wraps exactly that
// group (Projects ... Files) in a single height-capped, self-scrolling container
// (max-h-[40%] overflow-y-auto) while keeping Home + Marketplace fixed ABOVE it, so the
// Chats list below always stays visible. These tests fail on the pre-fix tree (no such
// wrapper existed) and pass on the new one.

let pathname = '/app/chat';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => pathname, useRouter: () => ({ push: vi.fn() }) }));
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
// Several projects so the group has real content to cap.
vi.mock('@/hooks/useProjects', () => ({
  useProjects: () => ({
    projects: Array.from({ length: 12 }).map((_, i) => ({
      id: `p${i}`,
      name: `Project ${i}`,
      icon: 'folder',
      color: '#888',
      currentUserRole: 'OWNER',
    })),
    loading: false,
  }),
  useProjectMutations: () => ({ deleteProject: vi.fn() }),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getAgentAvatars: vi.fn(() => Promise.resolve([])) } }));
vi.mock('@/components/project/ProjectMultiStepModal', () => ({
  getProjectIcon: () => () => null,
  ProjectMultiStepModal: () => null,
}));
vi.mock('@/components/dm/DmSidebarList', () => ({ DmSidebarList: () => <div data-testid="dm-sidebar-list" /> }));
vi.mock('@/components/sharing/ShareLinkDialog', () => ({ ShareLinkDialog: () => null }));

import { ConversationSidebar } from '../ConversationSidebar';

// Find the navigation-block wrapper by its height cap class. Targets max-h-[40%]
// specifically, distinct from the Projects list's own inner max-h-[200px].
const findNavBlock = (container: HTMLElement): HTMLElement | undefined =>
  Array.from(container.querySelectorAll('div')).find((el) => el.className.includes('max-h-[40%]')) as
    | HTMLElement
    | undefined;

describe('ConversationSidebar - navigation block is height-capped so Chats stays visible', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    pathname = '/app/chat';
  });

  it('wraps Projects ... Files in a single max-h-[40%], self-scrolling container', () => {
    const { container } = render(<ConversationSidebar onConversationSelect={vi.fn()} onNewChat={vi.fn()} onNavigate={vi.fn()} />);

    const navBlock = findNavBlock(container);
    expect(navBlock).toBeTruthy();
    // Height-capped at 40% of the sidebar and scrolls its own overflow.
    expect(navBlock!).toHaveClass('max-h-[40%]');
    expect(navBlock!).toHaveClass('overflow-y-auto');

    // The capped block contains the full nav group: the first entry (Projects) and the
    // last entry (Files) both live inside it.
    expect(within(navBlock!).getByText('sidebar.projects')).toBeInTheDocument();
    expect(within(navBlock!).getByText('sidebar.nav.files')).toBeInTheDocument();
  });

  it('keeps Home and Marketplace FIXED above the capped block (not inside it)', () => {
    const { container } = render(<ConversationSidebar onConversationSelect={vi.fn()} onNewChat={vi.fn()} onNavigate={vi.fn()} />);

    const navBlock = findNavBlock(container)!;
    const home = screen.getByText('sidebar.home');
    const marketplace = screen.getByText('sidebar.marketplace');

    // Both render, but OUTSIDE the scrollable/height-capped group so they never scroll away.
    expect(home).toBeInTheDocument();
    expect(marketplace).toBeInTheDocument();
    expect(navBlock.contains(home)).toBe(false);
    expect(navBlock.contains(marketplace)).toBe(false);
  });
});

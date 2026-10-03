// @vitest-environment jsdom
/**
 * CASA round 7: a delegated task holding Gmail / Google Drive data runs its turns in a conversation
 * of its own (taskId set), which also carries the agent id. The sidebar used to show it as a second
 * chat of the agent: agent trigger badges, "Go to agent", and "clear messages" (the agent-chat
 * mode). It is now labelled a task conversation and handled like an ordinary conversation.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

let mockConversations: Array<Record<string, unknown>> = [];
const routerPush = vi.fn();

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key, useLocale: () => 'en' }));
vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/app/chat',
  useRouter: () => ({ push: routerPush }),
}));
vi.mock('next/navigation', () => ({ useSearchParams: () => ({ get: () => null }) }));
vi.mock('@/hooks/conversation/useConversationList', () => ({
  useConversationList: () => ({
    conversations: mockConversations,
    loading: false,
    error: null,
    hasMore: false,
    loadMoreConversations: vi.fn(),
    loadConversationById: vi.fn(),
    forceRefreshConversations: vi.fn(),
    setConversations: vi.fn(),
  }),
}));
vi.mock('@/hooks/conversation/useConversationMutations', () => ({
  useConversationMutations: () => ({
    loading: false,
    error: null,
    createConversation: vi.fn(),
    updateConversation: vi.fn(),
    deleteConversation: vi.fn(),
    clearError: vi.fn(),
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
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: () => ({ data: [] }),
}));
vi.mock('@/hooks/useProjects', () => ({
  useProjects: () => ({ projects: [], loading: false }),
  useProjectMutations: () => ({ deleteProject: { mutate: vi.fn() } }),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getAgentAvatars: vi.fn(() => Promise.resolve([])) } }));
vi.mock('@/components/project/ProjectMultiStepModal', () => ({
  getProjectIcon: () => (props: Record<string, unknown>) => <span {...props} />,
  ProjectMultiStepModal: () => null,
}));
vi.mock('@/components/dm/DmSidebarList', () => ({ DmSidebarList: () => <div data-testid="dm-sidebar-list" /> }));
vi.mock('@/components/sharing/ShareLinkDialog', () => ({ ShareLinkDialog: () => null }));

import { ConversationSidebar } from '../ConversationSidebar';

function renderSidebar(onNavigate?: (path: string) => void) {
  return render(
    <ConversationSidebar
      onConversationSelect={vi.fn()}
      onNewChat={vi.fn()}
      {...(onNavigate ? { onNavigate } : {})}
    />
  );
}

function openConversationMenu() {
  fireEvent.click(screen.getByTitle('sidebar.conversationMenu'));
}

beforeEach(() => {
  routerPush.mockClear();
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ConversationSidebar - task conversations are not the agent main chat', () => {
  it('a task conversation is labelled as such, offers no "Go to agent" and is deleted, not cleared', () => {
    mockConversations = [{ id: 'c-task', title: 'Worker - task 1a2b3c4d', agentId: 'agent-42', taskId: '1a2b3c4d-0000-0000-0000-000000000000' }];
    renderSidebar(vi.fn());

    expect(screen.getByTestId('task-conversation-badge')).toHaveAttribute('aria-label', 'sidebar.taskConversation');
    openConversationMenu();
    expect(screen.queryByText('sidebar.navigateToAgent')).toBeNull();
    expect(screen.getByText('sidebar.deleteConversation')).toBeInTheDocument();
    expect(screen.queryByText('sidebar.clearMessages')).toBeNull();
  });

  it('the agent main conversation keeps its agent entries and no task label', () => {
    mockConversations = [{ id: 'c-main', title: 'Worker', agentId: 'agent-42', taskId: null }];
    renderSidebar(vi.fn());

    expect(screen.queryByTestId('task-conversation-badge')).toBeNull();
    openConversationMenu();
    expect(screen.getByText('sidebar.navigateToAgent')).toBeInTheDocument();
    expect(screen.getByText('sidebar.clearMessages')).toBeInTheDocument();
  });
});

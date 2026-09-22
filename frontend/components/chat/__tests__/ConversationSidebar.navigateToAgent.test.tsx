// @vitest-environment jsdom
/**
 * Regression (2026-09-16): the row menu's "Go to agent" pushed
 * `/app/agent/<agentId>`, a route that does not exist - there is no per-agent
 * page, so the click 404'd. The agent opens in the right-side panel through the
 * `?openAgent=<id>` deep link that AgentTable handles (the same entry point the
 * notification rows use). This suite pins the emitted path for both the
 * `onNavigate` branch and the `router.push` fallback, and keeps the sibling
 * "Go to workflow" item on its real `/app/workflow/<id>` route.
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
  mockConversations = [{ id: 'c1', title: 'Agent chat', agentId: 'agent-42' }];
  routerPush.mockClear();
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ConversationSidebar - "Go to agent" targets the agent-panel deep link', () => {
  it('onNavigate receives /app/agent?openAgent=<id>, never the non-existent /app/agent/<id>', () => {
    const onNavigate = vi.fn();
    renderSidebar(onNavigate);
    openConversationMenu();
    fireEvent.click(screen.getByText('sidebar.navigateToAgent'));
    expect(onNavigate).toHaveBeenCalledWith('/app/agent?openAgent=agent-42');
    expect(onNavigate).not.toHaveBeenCalledWith('/app/agent/agent-42');
  });

  it('falls back to router.push with the same deep link when no onNavigate is provided', () => {
    renderSidebar();
    openConversationMenu();
    fireEvent.click(screen.getByText('sidebar.navigateToAgent'));
    expect(routerPush).toHaveBeenCalledWith('/app/agent?openAgent=agent-42');
  });

  it('the workflow sibling keeps its real per-workflow route', () => {
    mockConversations = [{ id: 'c2', title: 'Flow chat', workflowId: 'wf-7' }];
    const onNavigate = vi.fn();
    renderSidebar(onNavigate);
    openConversationMenu();
    fireEvent.click(screen.getByText('sidebar.navigateToWorkflow'));
    expect(onNavigate).toHaveBeenCalledWith('/app/workflow/wf-7');
  });
});

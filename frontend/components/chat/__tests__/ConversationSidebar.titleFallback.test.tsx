// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Regression: a GENERAL chat (no agentId/workflowId) whose title never got generated
// used to stay stuck showing the raw "Generating Title..." placeholder in the sidebar,
// because getDisplayTitle only fell back to the first user message for agent/workflow
// conversations. The fix makes the first-message preview the fallback for EVERY
// conversation type. This also covers the "user stopped the request early" case: on
// abort the row keeps the placeholder title but the user message was already persisted,
// so firstMessagePreview is present and must be shown. Pre-fix these assertions fail
// (row renders "Generating Title..."); post-fix they pass.

// Mutated per render to feed the sidebar a specific conversation set.
let mockConversations: Array<Record<string, unknown>> = [];

// useTranslations returns the key verbatim, so the "no real title and no preview"
// fallback renders as the literal key 'sidebar.generatingTitle'.
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/chat', useRouter: () => ({ push: vi.fn() }) }));
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

describe('ConversationSidebar - title fallback to the first user message', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    mockConversations = [];
  });

  it('general chat with the generating placeholder shows the first user message, never the placeholder', () => {
    mockConversations = [
      // General chat: no agent/workflow, title is the backend placeholder, but the
      // first user message is present (the post-"stop early" state).
      { id: 'c1', title: 'Generating Title...', firstMessagePreview: 'Deploy my service to k8s please' },
    ];
    render(<ConversationSidebar onConversationSelect={vi.fn()} onNewChat={vi.fn()} onNavigate={vi.fn()} />);

    expect(screen.getByText('Deploy my service to k8s please')).toBeInTheDocument();
    // The raw placeholder must NOT be rendered for this row (pre-fix it was).
    expect(screen.queryByText('Generating Title...')).not.toBeInTheDocument();
  });

  it('a real title still wins over the first-message preview', () => {
    mockConversations = [
      { id: 'c2', title: 'Weekend trip plan', firstMessagePreview: 'where should I go this weekend' },
    ];
    render(<ConversationSidebar onConversationSelect={vi.fn()} onNewChat={vi.fn()} onNavigate={vi.fn()} />);

    expect(screen.getByText('Weekend trip plan')).toBeInTheDocument();
    expect(screen.queryByText('where should I go this weekend')).not.toBeInTheDocument();
  });

  it('only shows the generating label when there is neither a real title nor a first message', () => {
    mockConversations = [
      { id: 'c3', title: 'Generating title...', firstMessagePreview: null },
    ];
    render(<ConversationSidebar onConversationSelect={vi.fn()} onNewChat={vi.fn()} onNavigate={vi.fn()} />);

    // No preview to fall back to -> the (translated) generating label, never the raw placeholder.
    expect(screen.getByText('sidebar.generatingTitle')).toBeInTheDocument();
    expect(screen.queryByText('Generating title...')).not.toBeInTheDocument();
  });
});

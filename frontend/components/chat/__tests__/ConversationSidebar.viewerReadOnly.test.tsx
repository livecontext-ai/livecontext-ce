// @vitest-environment jsdom
/**
 * RBAC audit follow-up (2026-07-02), refined by the delta re-audit: the
 * sidebar gates SPLIT by backend reality.
 * - CONVERSATION actions (delete/clear) are deliberately NOT gated on the org
 *   VIEWER role: conversation-service has no org-role write gate, chat history
 *   is the caller's own. Same for project CREATE (ungated backend-side).
 * - PROJECT edit/delete ARE gated: sidebar projects are orchestrator
 *   ProjectService rows whose update/delete run the central canWrite gate,
 *   which 403s a VIEWER - showing the menu would be a dead-end affordance.
 * This suite pins both halves.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

let mockConversations: Array<Record<string, unknown>> = [];
let mockProjects: Array<Record<string, unknown>> = [];
// Simulates the active-workspace role: false = org VIEWER. The sidebar must
// render identically for both values.
const orgMutationGate = vi.hoisted(() => ({ canMutate: true }));

// useLocale: clicking the 3-dot menu focuses the row's TooltipTrigger, which
// mounts ConversationInfoPill (it reads the locale for date formatting).
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key, useLocale: () => 'en' }));
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
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => orgMutationGate.canMutate,
}));
vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: () => ({ data: [] }),
}));
vi.mock('@/hooks/useProjects', () => ({
  useProjects: () => ({ projects: mockProjects, loading: false }),
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

function renderSidebar() {
  return render(
    <ConversationSidebar onConversationSelect={vi.fn()} onNewChat={vi.fn()} onNavigate={vi.fn()} />
  );
}

function openConversationMenu() {
  fireEvent.click(screen.getByTitle('sidebar.conversationMenu'));
}

beforeEach(() => {
  mockConversations = [{ id: 'c1', title: 'Roadmap chat' }];
  mockProjects = [{ id: 'p1', name: 'Apollo', icon: 'briefcase', color: '#123456', currentUserRole: 'OWNER' }];
  orgMutationGate.canMutate = true;
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ConversationSidebar - personal actions are NOT org-VIEWER gated', () => {
  it('MEMBER: the row menu offers share AND delete', () => {
    renderSidebar();
    openConversationMenu();
    expect(screen.getByText('sidebar.shareConversation')).toBeInTheDocument();
    expect(screen.getByText('sidebar.deleteConversation')).toBeInTheDocument();
  });

  it('VIEWER: delete conversation STAYS - the backend permits deleting your own conversations', () => {
    orgMutationGate.canMutate = false;
    renderSidebar();
    openConversationMenu();
    expect(screen.getByText('sidebar.shareConversation')).toBeInTheDocument();
    expect(screen.getByText('sidebar.deleteConversation')).toBeInTheDocument();
  });

  it('VIEWER: clear messages STAYS for agent conversations (own chat history)', () => {
    orgMutationGate.canMutate = false;
    mockConversations = [{ id: 'c2', title: 'Agent chat', agentId: 'a1' }];
    renderSidebar();
    openConversationMenu();
    expect(screen.getByText('sidebar.clearMessages')).toBeInTheDocument();
  });

  it('VIEWER: new-project (+) STAYS (project CREATE is ungated backend-side)', () => {
    orgMutationGate.canMutate = false;
    renderSidebar();
    expect(screen.getByTitle('sidebar.newProject')).toBeInTheDocument();
  });

  it('VIEWER: the project edit/delete menu is HIDDEN (ProjectService canWrite 403s a VIEWER)', () => {
    orgMutationGate.canMutate = false;
    renderSidebar();
    const row = screen.getByText('Apollo').closest('div.group') as HTMLElement;
    // The 3-dot trigger itself is gone: no dead-end affordance.
    expect(within(row).queryByRole('button')).not.toBeInTheDocument();
  });

  it('MEMBER: the project edit/delete menu is available', () => {
    renderSidebar();
    const row = screen.getByText('Apollo').closest('div.group') as HTMLElement;
    fireEvent.click(within(row).getByRole('button'));
    expect(screen.getByText('project.editProject')).toBeInTheDocument();
    expect(screen.getByText('project.deleteProject')).toBeInTheDocument();
  });
});

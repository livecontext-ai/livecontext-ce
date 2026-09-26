/**
 * @vitest-environment jsdom
 *
 * The workflow panel's AI chat is Orbi: its conversation comes from useWorkflowChat, which
 * never carries an agent. The same panel hosts the application view's AI chat tab. Orbi was
 * missing from both because this ChatCore never received showOrbi (only the home chat and the
 * side-panel chat passed it).
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

// The composer fetches the verdict once for its model menu. Stubbed:
// these suites are about layout, not billing.
// The canvas action cluster (Share / Save / Run) has its own suite; here it is a
// marker so these tests keep exercising the tab bar rather than the publish
// wizard and the version-history fetch it pulls in.
vi.mock('@/components/app/WorkflowPanelActions', () => ({
  WorkflowPanelActions: () => null,
}));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: false, isLoading: false }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/chat' }));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false, workflowId: 'wf-1', runId: 'run-1' }),
}));
vi.mock('@/hooks/useWorkflowChat', () => ({
  useWorkflowChat: () => ({
    conversationId: 'c-1', messages: [], isLoading: false,
    sendMessage: vi.fn(), loadConversation: vi.fn(), stopStream: vi.fn(),
  }),
}));
const TOP_MODEL = { id: 'm-top', provider: 'p', tier: 'top' };
vi.mock('@/hooks/useModels', () => ({
  useModels: () => ({ models: [TOP_MODEL], defaultModel: undefined }),
  useVisibleModels: () => ({ models: [TOP_MODEL], defaultModel: undefined }),
  EMPTY_SELECTED_MODEL: {},
  modelMatches: () => true,
  selectedModelFromAIModel: () => ({}),
  selectedModelEquals: () => true,
  getEffectiveDefaultSelectedModel: () => ({}),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => ({ isStreamingConversation: () => false }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  // Not a VIEWER: these suites are about the panel, not about role gating.
  useCanMutateInCurrentOrg: () => true,
  useCurrentOrgStore: Object.assign(
    (sel: (s: any) => any) => sel({ currentOrgId: 'org-1' }),
    { subscribe: () => () => {} },
  ),
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: Object.assign(
    () => ({}),
    { getState: () => ({ setCarouselIndex: vi.fn() }) },
  ),
  carouselKeyFor: (workflowId?: string | null, runId?: string | null) => `${workflowId ?? ''}:${runId ?? ''}`,
}));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({ normalizeLabel: (s: string) => s }));
// Stubbed to keep the real store (a stateful class with a module-level
// registry) out of a composer test, and EMPTY so that no status reads as
// terminal here. It has to name every constant this tree REACHES, not just
// the ones this file reads: run-panel/runFormatting pulls UNREVIVABLE_STATUSES
// in through WorkflowPanelContent, and a stub missing one does not fail an
// assertion, it fails the whole FILE at import.
vi.mock('@/contexts/workflow-run/RunStateStore', () => ({
  TERMINAL_STATUSES: new Set<string>(),
  UNREVIVABLE_STATUSES: new Set<string>(),
}));

const h = vi.hoisted(() => ({ chatCoreProps: [] as Array<Record<string, unknown>> }));
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: (props: Record<string, unknown>) => {
    h.chatCoreProps.push(props);
    return <div data-testid="chat-core" />;
  },
}));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: () => <div data-testid="model-selector" />,
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/components/chat/TriggerTabContent', () => ({ TriggerTabContent: () => <div data-testid="trigger-content" /> }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({
  ApplicationCarousel: () => <div data-testid="app-carousel" />,
}));

import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';

afterEach(() => {
  cleanup();
  h.chatCoreProps.length = 0;
});

describe('WorkflowPanelContent perches Orbi on its AI chat', () => {
  it('passes the compact mascot to the chat, never the model tier', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div data-testid="canvas-slot" />} />);
    const aiChatTab = screen.queryByText('sidePanel.aiChat');
    if (aiChatTab) fireEvent.click(aiChatTab);

    const last = h.chatCoreProps[h.chatCoreProps.length - 1];
    expect(last).toBeDefined();
    expect(last.showOrbi).toBe('compact');
    expect(last).not.toHaveProperty('orbiTier');
  });
});

/**
 * @vitest-environment jsdom
 *
 * The workflow page's panel after a reload that left its AI chat on screen (an OAuth connect
 * started from a credential card in that chat, an F5): it lands on the chat and stays there,
 * even when the Application or a trigger would take the front on load; and it hands ChatCore its
 * conversation whole, so the card the agent left waiting comes back and can resume it.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

const h = vi.hoisted(() => ({ chatCoreProps: [] as Array<Record<string, unknown>> }));
const waitingForSlack = vi.hoisted(() => ({ id: 'c-1', pendingActions: [{ waiting_for: 'service_approval', services: [] }] }));

vi.mock('@/components/app/WorkflowPanelActions', () => ({ WorkflowPanelActions: () => null }));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: false, isLoading: false }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));
vi.mock('next/navigation', () => ({ usePathname: () => '/fr/app/workflow/wf-1' }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false, workflowId: 'wf-1', runId: 'run-1' }),
}));
vi.mock('@/hooks/useWorkflowChat', () => ({
  useWorkflowChat: () => ({
    conversationId: 'c-1', conversation: waitingForSlack, messages: [], isLoading: false,
    sendMessage: vi.fn(), loadConversation: vi.fn(), stopStream: vi.fn(),
  }),
}));
vi.mock('@/hooks/useModels', () => ({
  useModels: () => ({ models: [], defaultModel: undefined }),
  useVisibleModels: () => ({ models: [], defaultModel: undefined }),
  EMPTY_SELECTED_MODEL: {},
  modelMatches: () => false,
  selectedModelFromAIModel: () => ({}),
  selectedModelEquals: () => true,
  getEffectiveDefaultSelectedModel: () => ({}),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
vi.mock('@/contexts/StreamingContext', () => ({ useStreaming: () => ({ isStreamingConversation: () => false }) }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => true,
  useCurrentOrgStore: Object.assign(
    (sel: (s: any) => any) => sel({ currentOrgId: 'org-1' }),
    { subscribe: () => () => {} },
  ),
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: Object.assign(() => ({}), { getState: () => ({ setCarouselIndex: vi.fn() }) }),
  carouselKeyFor: (workflowId?: string | null, runId?: string | null) => `${workflowId ?? ''}:${runId ?? ''}`,
}));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({ normalizeLabel: (s: string) => s }));
vi.mock('@/contexts/workflow-run/RunStateStore', () => ({
  TERMINAL_STATUSES: new Set<string>(),
  UNREVIVABLE_STATUSES: new Set<string>(),
}));
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: (props: Record<string, unknown>) => {
    h.chatCoreProps.push(props);
    return <div data-testid="chat-core" />;
  },
}));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({ ModelSelectorDropdown: () => null, PROVIDER_ICON_MAP: {} }));
vi.mock('@/components/chat/TriggerTabContent', () => ({ TriggerTabContent: () => <div data-testid="trigger-content" /> }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({ ApplicationCarousel: () => <div data-testid="app-carousel" /> }));

import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';
import { WORKFLOW_PANEL_TAB_ID } from '@/lib/sidePanel/tabResource';
import { isSidePanelConversation, resetSidePanelConversationsForTest } from '@/lib/sidePanel/sidePanelConversations';
import { wasOnScreenBeforeReload } from '@/lib/sidePanel/onScreenAcrossReload';

const ON_SCREEN_KEY = 'lc.sidePanel.onScreen:/app/workflow/wf-1';

function renderPagePanel() {
  const utils = render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" hostTabId={WORKFLOW_PANEL_TAB_ID} />);
  // The run's interfaces arrive after mount: without a canvas slot, the Application takes the front.
  act(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
      detail: { workflowId: 'wf-1', configs: [{ interfaceId: 'iface-1', label: 'Search Page', actionMapping: {} }] },
    }));
  });
  return utils;
}

const activeSubTab = () =>
  screen.getAllByTestId('panel-sub-tab').filter(t => t.getAttribute('aria-pressed') === 'true').map(t => t.textContent);

beforeEach(() => {
  h.chatCoreProps = [];
  sessionStorage.clear();
  resetSidePanelConversationsForTest();
});
afterEach(cleanup);

describe('WorkflowPanelContent after a reload', () => {
  it('an ordinary load lets the run\'s Application take the front (unchanged)', () => {
    renderPagePanel();

    expect(activeSubTab()).toEqual(['common.application']);
  });

  it('regression: a reload that left the chat on screen lands on the chat and keeps it in front', () => {
    sessionStorage.setItem(ON_SCREEN_KEY, '__chat_ia__');

    renderPagePanel();

    expect(activeSubTab()).toEqual(['sidePanel.aiChat']);
    expect(screen.getByTestId('chat-core')).toBeTruthy();
  });

  it('the reader picking another sub-tab ends the hold', () => {
    sessionStorage.setItem(ON_SCREEN_KEY, '__chat_ia__');
    renderPagePanel();

    fireEvent.click(screen.getByText('common.application').closest('button')!);

    expect(activeSubTab()).toEqual(['common.application']);
  });

  it('hands ChatCore the conversation whole, and marks it as a side-panel one', () => {
    sessionStorage.setItem(ON_SCREEN_KEY, '__chat_ia__');

    renderPagePanel();

    expect(h.chatCoreProps[h.chatCoreProps.length - 1]?.conversation).toBe(waitingForSlack);
    expect(isSidePanelConversation('c-1')).toBe(true);
  });

  it('marks the chat on screen only while it is the sub-tab shown', () => {
    sessionStorage.setItem(ON_SCREEN_KEY, '__chat_ia__');
    renderPagePanel();
    expect(wasOnScreenBeforeReload('__chat_ia__', '/app/workflow/wf-1')).toBe(true);

    fireEvent.click(screen.getByText('common.application').closest('button')!);

    expect(wasOnScreenBeforeReload('__chat_ia__', '/app/workflow/wf-1')).toBe(false);
  });

  it('another host (a builder tab on a chat page) never restores nor marks', () => {
    sessionStorage.setItem(ON_SCREEN_KEY, '__chat_ia__');

    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" hostTabId="workflow-wf-1" />);
    act(() => {
      window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
        detail: { workflowId: 'wf-1', configs: [{ interfaceId: 'iface-1', label: 'Search Page', actionMapping: {} }] },
      }));
    });

    expect(activeSubTab()).toEqual(['common.application']);
  });
});

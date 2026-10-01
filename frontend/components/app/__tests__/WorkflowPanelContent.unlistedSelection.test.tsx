/**
 * @vitest-environment jsdom
 *
 * The workflow panel's composer and UNLISTED models (V554): a user who picked a hidden model in
 * the composer keeps it. The panel replaces a stored selection it cannot find with the default,
 * so before V554 it would silently have moved that user back to the default model.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

const h = vi.hoisted(() => ({
  models: [{ id: 'sonnet', name: 'Sonnet', provider: 'anthropic', freeTierEnabled: true }],
  unlistedModels: [{ id: 'claude-3-opus', name: 'Claude 3 Opus', provider: 'anthropic', unlisted: true }],
  selected: { provider: 'anthropic', id: 'claude-3-opus' },
  setSelectedModel: vi.fn(),
  dropdownProps: null as null | Record<string, unknown>,
  opening: vi.fn(),
}));

vi.mock('@/lib/hooks/usePreferFreeTierModel', () => ({
  usePreferFreeTierModel: h.opening,
}));
vi.mock('@/components/app/WorkflowPanelActions', () => ({
  WorkflowPanelActions: () => null,
}));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  // verdictReady: the panel only WRITES a default once the plan verdict is in.
  useMonthlyCreditsCannotPay: () => ({ blocked: false, isLoading: false, verdictReady: true, prefersFreeTierModels: false }),
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
// The real selection helpers: the decision under test is which model survives them.
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useModels: () => ({ models: h.models, defaultModel: 'sonnet' }),
    useVisibleModels: () => ({
      models: h.models, unlistedModels: h.unlistedModels, defaultModel: 'sonnet', isLoading: false, error: null,
    }),
  };
});
vi.mock('@/contexts/UnifiedAppContext', () => ({
  useUnifiedAppSafe: () => ({
    state: { selectedModel: h.selected, selectionRestored: false },
    setSelectedModel: h.setSelectedModel,
  }),
}));
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

// Renders the composer's leading control, which is where the model menu lives.
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: ({ leadingControl }: { leadingControl?: React.ReactNode }) => <div data-testid="chat-core">{leadingControl}</div>,
}));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: (props: Record<string, unknown>) => {
    h.dropdownProps = props;
    return <div data-testid="model-selector" />;
  },
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/components/chat/TriggerTabContent', () => ({ TriggerTabContent: () => <div data-testid="trigger-content" /> }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({
  ApplicationCarousel: () => <div data-testid="app-carousel" />,
}));

import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';

beforeEach(() => {
  h.selected = { provider: 'anthropic', id: 'claude-3-opus' };
  h.setSelectedModel = vi.fn();
  h.dropdownProps = null;
});

afterEach(cleanup);

describe('WorkflowPanelContent - a selection on an unlisted model', () => {
  it('is kept, named in the trigger, and the hidden group reaches the menu', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div />} />);
    // The composer lives on the AI chat sub-tab.
    const aiChatTab = screen.queryByText('sidePanel.aiChat');
    if (aiChatTab) fireEvent.click(aiChatTab);

    expect(h.setSelectedModel).not.toHaveBeenCalled();
    expect((h.dropdownProps?.selectedModelData as { name: string } | undefined)?.name).toBe('Claude 3 Opus');
    expect((h.dropdownProps?.unlistedModels as Array<{ id: string }>).map((m) => m.id)).toEqual(['claude-3-opus']);
  });

  it('a model gone from the catalogue altogether is still replaced by the default', () => {
    h.selected = { provider: 'anthropic', id: 'claude-2' };

    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div />} />);

    expect(h.setSelectedModel).toHaveBeenCalledWith(expect.objectContaining({ id: 'sonnet' }));
  });
});

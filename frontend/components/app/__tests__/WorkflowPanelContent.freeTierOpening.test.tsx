/**
 * @vitest-environment jsdom
 *
 * The workflow panel's composer must take part in the Free plan's opening model: the
 * stored selection is browser-wide, so without this call a Free account landing on a
 * workflow panel first keeps whatever model the previous account left in the browser.
 * The rule itself is tested on the hook; this pins the WIRING, which nothing else
 * would notice being removed.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

const h = vi.hoisted(() => ({
  models: [{ id: 'sonnet', name: 'Sonnet', provider: 'anthropic', freeTierEnabled: true }],
  opening: vi.fn(),
}));

vi.mock('@/lib/hooks/usePreferFreeTierModel', () => ({
  usePreferFreeTierModel: h.opening,
}));
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
vi.mock('@/hooks/useModels', () => ({
  useModels: () => ({ models: h.models, defaultModel: undefined }),
  useVisibleModels: () => ({ models: h.models, defaultModel: undefined }),
  EMPTY_SELECTED_MODEL: {},
  modelMatches: () => false,
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

vi.mock('@/components/chat/ChatCore', () => ({ ChatCore: () => <div data-testid="chat-core" /> }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: () => <div data-testid="model-selector" />,
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/components/chat/TriggerTabContent', () => ({ TriggerTabContent: () => <div data-testid="trigger-content" /> }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({
  ApplicationCarousel: () => <div data-testid="app-carousel" />,
}));

import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';

afterEach(cleanup);

describe('WorkflowPanelContent - Free plan opening model', () => {
  it('hands the visible catalogue to the opening-model hook', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div />} />);

    expect(h.opening).toHaveBeenCalledWith(h.models);
  });
});

/**
 * @vitest-environment jsdom
 *
 * Commit-2 regression: in the side-panel workflow (a workflow canvas slot is
 * present), the Application sub-tab must (1) appear automatically once the run
 * exposes an interface, (2) be PERMANENT - a plain button with no close X, like
 * the Trigger sub-tabs, and (3) NOT steal focus from the Workflow canvas tab
 * (the user clicks it to focus it). Mirrors the trigger auto-select skip.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

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
  useModels: () => ({ models: [], defaultModel: undefined }),
  useVisibleModels: () => ({ models: [], defaultModel: undefined }),
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
}));
vi.mock('@/app/workflows/builder/utils/labelNormalizer', () => ({ normalizeLabel: (s: string) => s }));
vi.mock('@/contexts/workflow-run/RunStateStore', () => ({ TERMINAL_STATUSES: new Set<string>() }));

// Child components → simple identifiable stubs.
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

function dispatchAppConfigs() {
  act(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
      detail: { workflowId: 'wf-1', configs: [{ interfaceId: 'iface-1', label: 'Search Page', actionMapping: {} }] },
    }));
  });
}

describe('WorkflowPanelContent - Application sub-tab (side-panel workflow)', () => {
  afterEach(cleanup);

  it('shows the Application sub-tab automatically once an interface is available', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div data-testid="canvas-slot" />} />);
    expect(screen.queryByText('common.application')).toBeNull(); // none before configs arrive
    dispatchAppConfigs();
    expect(screen.queryByText('common.application')).not.toBeNull();
  });

  it('renders the Application sub-tab as a permanent button with NO close (X) control', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div data-testid="canvas-slot" />} />);
    dispatchAppConfigs();
    const appTab = screen.getByText('common.application').closest('button');
    expect(appTab).not.toBeNull();
    // Permanent like the Trigger tabs: no nested close button.
    expect(appTab!.querySelector('button')).toBeNull();
    expect(appTab!.querySelector('[aria-label="common.close"]')).toBeNull();
  });

  it('does NOT steal focus to the Application tab when a workflow canvas slot is present', () => {
    render(<WorkflowPanelContent workflowId="wf-1" runId="run-1" workflowCanvasSlot={<div data-testid="canvas-slot" />} />);
    dispatchAppConfigs();
    // Tab is available...
    expect(screen.queryByText('common.application')).not.toBeNull();
    // ...but the Application carousel content is NOT rendered (focus stayed on the
    // Workflow canvas tab - ApplicationCarousel only mounts when APP_TAB is active).
    expect(screen.queryByTestId('app-carousel')).toBeNull();
    expect(screen.queryByTestId('canvas-slot')).not.toBeNull();
  });
});

/**
 * @vitest-environment jsdom
 *
 * Composer no-provider gate - the side-panel composers (ChatPanelContent,
 * WorkflowPanelContent) pass the NoProviderCta emptyState + the "No models"
 * trigger label into ModelSelectorDropdown ONLY once the model catalog has
 * RESOLVED empty. While the catalog is loading, and after a fetch error, both
 * props must stay undefined - showing "no provider is configured" on a
 * transient network failure would be a false claim, and a label flash on cold
 * load would be noise. Same contract as ModelPicker's own empty-state gate.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

const h = vi.hoisted(() => ({
  models: {
    models: [] as unknown[],
    defaultModel: undefined as string | undefined,
    isLoading: false,
    error: null as string | null,
  },
  dropdownProps: [] as Array<Record<string, unknown>>,
}));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ usePathname: () => '/en/app/chat' }));
vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/chat' }));

vi.mock('@/hooks/useModels', () => ({
  useModels: () => h.models,
  useVisibleModels: () => h.models,
  EMPTY_SELECTED_MODEL: { provider: '', id: '' },
  modelMatches: () => false,
  selectedModelFromAIModel: (m: { provider?: string; id?: string }) => ({ provider: m.provider ?? '', id: m.id ?? '' }),
  selectedModelEquals: () => true,
  getEffectiveDefaultSelectedModel: () => ({ provider: '', id: '' }),
}));
vi.mock('@/components/ai/NoProviderCta', () => ({
  NoProviderCta: () => <div data-testid="no-provider-cta" />,
}));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: (props: Record<string, unknown>) => {
    h.dropdownProps.push(props);
    return <div data-testid="model-selector" />;
  },
  PROVIDER_ICON_MAP: {},
}));

// Shared heavy deps of both composers -> inert stubs.
vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => ({ isStreamingConversation: () => false }),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
// ChatCore hosts the composer: render its leadingControl slot so the
// ModelSelectorDropdown (and the props under test) actually mount.
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: ({ leadingControl }: { leadingControl?: React.ReactNode }) => (
    <div data-testid="chat-core">{leadingControl}</div>
  ),
}));

// ChatPanelContent-specific deps.
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: { getMessages: vi.fn().mockResolvedValue([]) } }));
vi.mock('@/hooks/useChatConfig', () => ({
  consumeDraftChatConfig: () => null,
  usePrimeUserChatDefaults: () => {},
}));
vi.mock('@/lib/sidePanelChat', () => ({ subscribeAiChatMessages: () => () => {} }));

// WorkflowPanelContent-specific deps.
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
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: Object.assign(
    (sel: (s: unknown) => unknown) => sel({ currentOrgId: 'org-1' }),
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
vi.mock('@/components/chat/TriggerTabContent', () => ({ TriggerTabContent: () => <div data-testid="trigger-content" /> }));
vi.mock('@/components/chat/ApplicationCarousel', () => ({ ApplicationCarousel: () => <div data-testid="app-carousel" /> }));

import { ChatPanelContent } from '@/components/app/ChatPanelContent';
import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';

function lastDropdownProps(): Record<string, unknown> {
  expect(h.dropdownProps.length).toBeGreaterThan(0);
  return h.dropdownProps[h.dropdownProps.length - 1];
}

const COMPOSERS: Array<[string, () => React.ReactElement]> = [
  ['ChatPanelContent', () => <ChatPanelContent />],
  ['WorkflowPanelContent', () => <WorkflowPanelContent workflowId="wf-1" runId="run-1" />],
];

describe.each(COMPOSERS)('%s - no-provider gate on the composer model selector', (_name, mount) => {
  beforeEach(() => {
    h.models = { models: [], defaultModel: undefined, isLoading: false, error: null };
    h.dropdownProps = [];
  });
  afterEach(cleanup);

  it('passes the label and the emptyState once the catalog RESOLVED empty', () => {
    render(mount());
    const props = lastDropdownProps();
    expect(props.noModelsLabel).toBe('aiProviders.noProviderCta.noModels');
    expect(props.emptyState).toBeTruthy();
  });

  it('passes NEITHER while the catalog is still loading (no label flash)', () => {
    h.models = { ...h.models, isLoading: true };
    render(mount());
    const props = lastDropdownProps();
    expect(props.noModelsLabel).toBeUndefined();
    expect(props.emptyState).toBeUndefined();
  });

  it('passes NEITHER on a fetch error (a transient failure is not an onboarding state)', () => {
    h.models = { ...h.models, error: 'network down' };
    render(mount());
    const props = lastDropdownProps();
    expect(props.noModelsLabel).toBeUndefined();
    expect(props.emptyState).toBeUndefined();
  });
});

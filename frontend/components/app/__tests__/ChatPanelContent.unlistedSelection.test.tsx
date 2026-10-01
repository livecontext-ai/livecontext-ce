/**
 * @vitest-environment jsdom
 *
 * The side-panel composer and UNLISTED models (V554). A model the admin unlisted is still
 * available, and the composer offers it in a collapsed group: a user who picks one there must
 * KEEP it. The panel replaces a stored selection it cannot find in the catalogue with the
 * default, so before this it would have silently moved that user back to the default model.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

const h = vi.hoisted(() => ({
  verdict: { prefersFreeTierModels: false, verdictReady: false },
  models: [] as Array<Record<string, unknown>>,
  unlistedModels: [] as Array<Record<string, unknown>>,
  dropdownProps: null as null | Record<string, unknown>,
  selected: { provider: '', id: '' },
  // False keeps the opening hook quiet, so the panel's OWN default effect is what the
  // older specs measure; the restore-aware specs below set it.
  selectionRestored: false,
  defaultModel: 'opus',
  setSelectedModel: vi.fn(),
}));

vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({
    blocked: false,
    blockedForModel: () => false,
    freeTierForModel: () => false,
    prefersFreeTierModels: h.verdict.prefersFreeTierModels,
    verdictReady: h.verdict.verdictReady,
  }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ usePathname: () => '/en/app/chat' }));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/en/app/chat',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
// The real helpers: the decision under test is which model comes out of them, so
// stubbing them would test the stub.
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useVisibleModels: () => ({
      models: h.models,
      unlistedModels: h.unlistedModels,
      defaultModel: h.defaultModel,
      isLoading: false,
      error: null,
    }),
  };
});
vi.mock('@/contexts/UnifiedAppContext', () => ({
  useUnifiedAppSafe: () => ({
    state: { selectedModel: h.selected, selectionRestored: h.selectionRestored },
    setSelectedModel: h.setSelectedModel,
  }),
}));
vi.mock('@/components/ai/NoProviderCta', () => ({ NoProviderCta: () => <div /> }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: (props: Record<string, unknown>) => {
    h.dropdownProps = props;
    return <div data-testid="model-selector" />;
  },
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => ({ isStreamingConversation: () => false }),
}));
// Renders the composer's leading control, which is where the model menu lives.
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: ({ leadingControl }: { leadingControl?: React.ReactNode }) => <div data-testid="chat-core">{leadingControl}</div>,
}));
vi.mock('@/components/chat/GenerateEntryButton', () => ({ GenerateEntryButton: () => null }));
vi.mock('@/components/chat/CreateGenerationModal', () => ({ CreateGenerationModal: () => null }));
vi.mock('@/app/shared/components', () => ({ WelcomeTitle: () => null }));
vi.mock('@/components/billing/UpgradeRequiredBadge', () => ({ UpgradeRequiredNotice: () => null }));
vi.mock('@/hooks/useChatConfig', () => ({
  consumeDraftChatConfig: () => null,
  usePrimeUserChatDefaults: () => {},
}));
vi.mock('@/lib/sidePanelChat', () => ({ subscribeAiChatMessages: () => () => {} }));
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: {} }));

import { ChatPanelContent } from '../ChatPanelContent';
import { resetFreeTierOpeningForTests } from '@/lib/hooks/usePreferFreeTierModel';

const OPUS = { id: 'opus', name: 'Opus', provider: 'anthropic', freeTierEnabled: false };
const LEGACY = { id: 'claude-3-opus', name: 'Claude 3 Opus', provider: 'anthropic', unlisted: true };

beforeEach(() => {
  h.verdict = { prefersFreeTierModels: false, verdictReady: true };
  h.models = [OPUS];
  h.unlistedModels = [LEGACY];
  h.selected = { provider: 'anthropic', id: 'claude-3-opus' };
  h.setSelectedModel = vi.fn();
  h.selectionRestored = false;
  h.defaultModel = 'opus';
  h.dropdownProps = null;
  resetFreeTierOpeningForTests();
});

afterEach(cleanup);

describe('ChatPanelContent - a selection on an unlisted model', () => {
  it('is kept, not reset to the default', () => {
    render(<ChatPanelContent />);

    expect(h.setSelectedModel).not.toHaveBeenCalled();
    expect(h.dropdownProps?.selectedModel).toEqual({ provider: 'anthropic', id: 'claude-3-opus' });
  });

  it('is named in the trigger, and the hidden group is handed to the menu', () => {
    render(<ChatPanelContent />);

    expect((h.dropdownProps?.selectedModelData as { name: string } | undefined)?.name).toBe('Claude 3 Opus');
    expect((h.dropdownProps?.unlistedModels as Array<{ id: string }>).map((m) => m.id)).toEqual(['claude-3-opus']);
  });

  it('a model gone from the catalogue altogether is still replaced by the default', () => {
    h.selected = { provider: 'anthropic', id: 'claude-2' };

    render(<ChatPanelContent />);

    expect(h.setSelectedModel).toHaveBeenCalledWith(expect.objectContaining({ id: 'opus' }));
  });
});

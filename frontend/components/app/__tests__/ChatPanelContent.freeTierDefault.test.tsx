/**
 * @vitest-environment jsdom
 *
 * Which model the side panel WRITES as its default, and when (V494).
 *
 * <p>The panel does not merely display a default: it pushes one into app state, and
 * that selection is persisted. So the timing matters in a way it does not for a list
 * that only orders or badges itself. `prefersFreeTierModels` is false while the
 * balance request is in flight, which is indistinguishable from "this is a paid
 * account" - and if the model catalogue resolves first, the effect pins the catalogue
 * default, the selection becomes valid, and the free-tier answer arriving a tick later
 * never applies. The reader is then stuck on a model their allowance cannot pay for,
 * permanently, with no error anywhere.
 *
 * <p>Every other spec in this change mocks the verdict as already-resolved, which is
 * exactly the fixture that hides this. Here it resolves late, on purpose.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

const h = vi.hoisted(() => ({
  verdict: { prefersFreeTierModels: false, verdictReady: false },
  models: [] as Array<Record<string, unknown>>,
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
  ModelSelectorDropdown: () => <div data-testid="model-selector" />,
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => ({ isStreamingConversation: () => false }),
}));
vi.mock('@/components/chat/ChatCore', () => ({ ChatCore: () => <div data-testid="chat-core" /> }));
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
const HAIKU = { id: 'haiku', name: 'Haiku', provider: 'anthropic', freeTierEnabled: true };
const DEEPSEEK_FLASH = { id: 'deepseek-flash', name: 'DeepSeek Flash', provider: 'deepseek', freeTierEnabled: true };

beforeEach(() => {
  h.verdict = { prefersFreeTierModels: false, verdictReady: false };
  h.models = [OPUS, HAIKU];
  h.selected = { provider: '', id: '' };
  h.setSelectedModel = vi.fn();
  h.selectionRestored = false;
  h.defaultModel = 'opus';
  resetFreeTierOpeningForTests();
});

afterEach(cleanup);

describe('ChatPanelContent - the default it writes', () => {
  it('writes NOTHING while the plan verdict is still in flight', () => {
    // The models have landed and the balance has not. Writing here is the bug: the
    // catalogue default becomes the stored selection and the free-tier answer that
    // arrives a moment later can no longer change it.
    render(<ChatPanelContent />);

    expect(h.setSelectedModel).not.toHaveBeenCalled();
  });

  it('writes the covered model once the verdict says free tier', () => {
    h.verdict = { prefersFreeTierModels: true, verdictReady: true };

    render(<ChatPanelContent />);

    expect(h.setSelectedModel).toHaveBeenCalledWith({ provider: 'anthropic', id: 'haiku' });
  });

  it('writes the catalogue default once the verdict says paid', () => {
    h.verdict = { prefersFreeTierModels: false, verdictReady: true };

    render(<ChatPanelContent />);

    expect(h.setSelectedModel).toHaveBeenCalledWith({ provider: 'anthropic', id: 'opus' });
  });

  it('still writes the covered model when the verdict arrives AFTER the first render', () => {
    // The actual ordering in production: two independent queries, and the catalogue is
    // usually cached while the balance is not. A guard that only worked on a warm
    // verdict would pass the two cases above and fail every real page load.
    const view = render(<ChatPanelContent />);
    expect(h.setSelectedModel).not.toHaveBeenCalled();

    h.verdict = { prefersFreeTierModels: true, verdictReady: true };
    view.rerender(<ChatPanelContent />);

    expect(h.setSelectedModel).toHaveBeenCalledWith({ provider: 'anthropic', id: 'haiku' });
  });

  it('moves a RESTORED lower-ranked free model to the free #1 on a Free account', () => {
    // The reported bug: the stored selection is browser-wide, so a Free account opened
    // on the previous account's DeepSeek. It is valid and free-tier, so the panel's own
    // "fix an invalid selection" effect never touched it.
    h.models = [OPUS, HAIKU, DEEPSEEK_FLASH];
    h.selected = { provider: 'deepseek', id: 'deepseek-flash' };
    h.selectionRestored = true;
    h.verdict = { prefersFreeTierModels: true, verdictReady: true };

    render(<ChatPanelContent />);

    expect(h.setSelectedModel).toHaveBeenCalledWith({ provider: 'anthropic', id: 'haiku' });
  });

  it('keeps a restored model on a paid account', () => {
    h.models = [OPUS, HAIKU, DEEPSEEK_FLASH];
    h.selected = { provider: 'deepseek', id: 'deepseek-flash' };
    h.selectionRestored = true;
    h.verdict = { prefersFreeTierModels: false, verdictReady: true };

    render(<ChatPanelContent />);

    expect(h.setSelectedModel).not.toHaveBeenCalled();
  });

  it('its own fallback default is the free #1, not a lower-ranked covered catalogue default', () => {
    // The opening hook stays quiet here (restore not done), so this measures the panel
    // effect alone: an invalid selection on a Free account falls back to the free #1
    // even when the admin default is itself covered but ranked lower.
    h.models = [OPUS, HAIKU, DEEPSEEK_FLASH];
    h.defaultModel = 'deepseek-flash';
    h.selected = { provider: 'gone', id: 'removed-model' };
    h.verdict = { prefersFreeTierModels: true, verdictReady: true };

    render(<ChatPanelContent />);

    // The mocked selection never updates, so the effect may write more than once:
    // what matters is that every write is the free #1, never the admin default.
    expect(h.setSelectedModel).toHaveBeenCalled();
    for (const [written] of h.setSelectedModel.mock.calls) {
      expect(written).toEqual({ provider: 'anthropic', id: 'haiku' });
    }
  });
});

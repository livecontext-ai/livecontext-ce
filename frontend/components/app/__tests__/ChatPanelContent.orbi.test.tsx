/**
 * @vitest-environment jsdom
 *
 * The side-panel chat is Orbi too, so it perches the mascot (compact, the panel is narrow) -
 * but only while its conversation is a general chat. A stored conversation id can point at a
 * conversation the panel did not create; an agent or studio one must not get the mascot.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

const h = vi.hoisted(() => ({ chatCoreProps: [] as Array<Record<string, unknown>> }));

// The composer fetches the verdict for its model menu and hands it to the
// dropdown, which is deliberately free of translations and data hooks. Driven
// from here rather than stubbed to a constant, because those two props are
// hand-passed and nothing else in the suite would notice one going missing.
const credits = vi.hoisted(() => ({ blocked: false }));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: credits.blocked, isLoading: false }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ usePathname: () => '/en/app/chat' }));
// The panel composer now carries the chat/studio switch, which reaches next-intl's navigation
// helpers - and those import a bare 'next/navigation' that vitest cannot resolve out of next-intl's
// ESM build. Unmocked, the whole file fails to load rather than any assertion failing.
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/en/app/chat',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
const catalog = vi.hoisted(() => ({ models: [] as Array<{ id: string; provider: string; tier?: string }> }));
vi.mock('@/hooks/useModels', () => ({
  useVisibleModels: () => ({ models: catalog.models, defaultModel: undefined, isLoading: false, error: null }),
  EMPTY_SELECTED_MODEL: { provider: '', id: '' },
  modelMatches: (m: { id: string }, sel: { id: string }) => m.id === sel.id,
  selectedModelFromAIModel: (m: { provider?: string; id?: string }) => ({ provider: m.provider ?? '', id: m.id ?? '' }),
  selectedModelEquals: () => true,
  getEffectiveDefaultSelectedModel: () => ({ provider: '', id: '' }),
}));
vi.mock('@/components/ai/NoProviderCta', () => ({ NoProviderCta: () => <div data-testid="no-provider-cta" /> }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({
  ModelSelectorDropdown: () => <div data-testid="model-selector" />,
  PROVIDER_ICON_MAP: {},
}));
vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => ({ isStreamingConversation: () => false, checkAndReconnect: () => {} }),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
// Capture the props ChatPanelContent hands to ChatCore.
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: (props: Record<string, unknown>) => {
    h.chatCoreProps.push(props);
    return <div data-testid="chat-core" />;
  },
}));
// Lightweight WelcomeTitle so the barrel's heavy deps stay out of this unit test.
vi.mock('@/app/shared/components', () => ({
  WelcomeTitle: ({ children }: { children: React.ReactNode }) => <div data-testid="welcome-title">{children}</div>,
}));
const api = vi.hoisted(() => ({ stored: null as null | Record<string, unknown> }));
vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: {
    getMessages: vi.fn().mockResolvedValue([]),
    getConversation: vi.fn(async () => api.stored),
    getRecentMessagesAsc: vi.fn().mockResolvedValue([]),
  },
}));
vi.mock('@/hooks/useChatConfig', () => ({
  consumeDraftChatConfig: () => null,
  usePrimeUserChatDefaults: () => {},
}));
vi.mock('@/lib/sidePanelChat', () => ({ subscribeAiChatMessages: () => () => {} }));

import { ChatPanelContent } from '@/components/app/ChatPanelContent';

const STORAGE_KEY = 'livecontext_side_panel_conversation_id:chat';
const lastShowOrbi = () => h.chatCoreProps[h.chatCoreProps.length - 1]?.showOrbi;

describe('ChatPanelContent - Orbi gate', () => {
  beforeEach(() => {
    h.chatCoreProps = [];
    api.stored = null;
    catalog.models = [];
    sessionStorage.clear();
  });
  afterEach(cleanup);

  it('a fresh panel chat is Orbi, drawn compact', () => {
    render(<ChatPanelContent />);
    expect(lastShowOrbi()).toBe('compact');
  });

  it('does not dress Orbi for the selected model tier', () => {
    catalog.models = [{ id: 'm-top', provider: 'openai', tier: 'top' }];
    render(<ChatPanelContent />);
    expect(h.chatCoreProps[h.chatCoreProps.length - 1]).not.toHaveProperty('orbiTier');
  });

  it('a stored general conversation is Orbi', async () => {
    sessionStorage.setItem(STORAGE_KEY, 'c1');
    api.stored = { id: 'c1' };
    render(<ChatPanelContent />);
    await waitFor(() => expect(h.chatCoreProps.some(p => p.conversationId === 'c1')).toBe(true));
    expect(lastShowOrbi()).toBe('compact');
  });

  it('a stored agent conversation is not Orbi', async () => {
    sessionStorage.setItem(STORAGE_KEY, 'c2');
    api.stored = { id: 'c2', agentId: 'agent-7' };
    render(<ChatPanelContent />);
    await waitFor(() => expect(h.chatCoreProps.some(p => p.conversationId === 'c2')).toBe(true));
    expect(lastShowOrbi()).toBe(false);
  });

  it('a stored studio conversation is not Orbi', async () => {
    sessionStorage.setItem(STORAGE_KEY, 'c3');
    api.stored = { id: 'c3', kind: 'studio' };
    render(<ChatPanelContent />);
    await waitFor(() => expect(h.chatCoreProps.some(p => p.conversationId === 'c3')).toBe(true));
    expect(lastShowOrbi()).toBe(false);
  });
});

/**
 * @vitest-environment jsdom
 *
 * The unified side-panel "AI Chat" (ChatPanelContent) opens with the CENTERED
 * welcome composer, matching the workflow panel: it passes `welcomeLayout` + a
 * `welcomeTitle` to ChatCore. ChatCore then centers the composer with the title
 * above it while the conversation is empty (a single shared component drives both
 * the right and bottom dock, so this covers both). Fails on the pre-change code,
 * where ChatPanelContent passed no welcomeLayout and the composer stayed docked at
 * the bottom on an empty chat.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

const h = vi.hoisted(() => ({ chatCoreProps: [] as Array<Record<string, unknown>> }));

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ usePathname: () => '/en/app/chat' }));
vi.mock('@/hooks/useModels', () => ({
  useVisibleModels: () => ({ models: [], defaultModel: undefined, isLoading: false, error: null }),
  EMPTY_SELECTED_MODEL: { provider: '', id: '' },
  modelMatches: () => false,
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
  useStreaming: () => ({ isStreamingConversation: () => false }),
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
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: { getMessages: vi.fn().mockResolvedValue([]) } }));
vi.mock('@/hooks/useChatConfig', () => ({
  consumeDraftChatConfig: () => null,
  usePrimeUserChatDefaults: () => {},
}));
vi.mock('@/lib/sidePanelChat', () => ({ subscribeAiChatMessages: () => () => {} }));

import { ChatPanelContent } from '@/components/app/ChatPanelContent';

function lastChatCoreProps(): Record<string, unknown> {
  expect(h.chatCoreProps.length).toBeGreaterThan(0);
  return h.chatCoreProps[h.chatCoreProps.length - 1];
}

describe('ChatPanelContent centered welcome composer', () => {
  beforeEach(() => {
    h.chatCoreProps = [];
  });
  afterEach(cleanup);

  it('enables welcomeLayout on the side-panel ChatCore', () => {
    render(<ChatPanelContent />);
    expect(lastChatCoreProps().welcomeLayout).toBe(true);
  });

  it('passes a welcomeTitle element carrying the sidePanel.welcomeTitle key', () => {
    render(<ChatPanelContent />);
    const welcomeTitle = lastChatCoreProps().welcomeTitle;
    expect(welcomeTitle).toBeTruthy();
    expect(React.isValidElement(welcomeTitle)).toBe(true);
    // useTranslations is mocked to echo the key, so the WelcomeTitle child IS the
    // resolved key - pins that the correct i18n key flows in (guards a wrong-key
    // regression, e.g. reusing the workflow namespace).
    const el = welcomeTitle as React.ReactElement<{ children?: React.ReactNode }>;
    expect(el.props.children).toBe('sidePanel.welcomeTitle');
  });
});

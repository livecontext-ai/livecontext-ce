/**
 * @vitest-environment jsdom
 *
 * The AI Chat tab after a reload: the conversation the agent left waiting on a credential is
 * handed to ChatCore WHOLE, so its card comes back (and approves itself once the account exists,
 * which is what resumes the agent); the chat marks itself on screen, so the next reload brings it
 * back open; and its conversation is a side-panel one, whose builds open beside it.
 *
 * Before: the panel passed ChatCore only an id. After the OAuth round trip of a card started
 * here, the card was gone and nothing could resume the conversation.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

const h = vi.hoisted(() => ({
  chatCoreProps: [] as Array<Record<string, unknown>>,
  onStreamComplete: null as null | ((cid: string) => Promise<void> | void),
}));

vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: false, isLoading: false }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ usePathname: () => '/fr/app/tables/5' }));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/app/tables/5',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock('@/hooks/useModels', () => ({
  useVisibleModels: () => ({ models: [], defaultModel: undefined, isLoading: false, error: null }),
  EMPTY_SELECTED_MODEL: { provider: '', id: '' },
  modelMatches: () => false,
  selectedModelFromAIModel: () => ({ provider: '', id: '' }),
  selectedModelEquals: () => true,
  getEffectiveDefaultSelectedModel: () => ({ provider: '', id: '' }),
}));
vi.mock('@/components/ai/NoProviderCta', () => ({ NoProviderCta: () => null }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({ ModelSelectorDropdown: () => null, PROVIDER_ICON_MAP: {} }));
const streaming = vi.hoisted(() => ({
  isStreamingConversation: () => false,
  checkAndReconnect: (_cid: string, callbacks?: { onStreamComplete?: (cid: string) => Promise<void> | void }) => {
    h.onStreamComplete = callbacks?.onStreamComplete ?? null;
  },
}));
vi.mock('@/contexts/StreamingContext', () => ({ useStreaming: () => streaming }));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: (props: Record<string, unknown>) => {
    h.chatCoreProps.push(props);
    return <div data-testid="chat-core" />;
  },
}));
vi.mock('@/app/shared/components', () => ({ WelcomeTitle: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
const waitingForSlack = {
  id: 'c1',
  pendingActions: [{ waiting_for: 'service_approval', services: [{ serviceType: 'slack', iconSlug: 'slack' }], reason: 'post' }],
};
const api = vi.hoisted(() => ({ getConversation: vi.fn(), getRecentMessagesAsc: vi.fn() }));
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: api }));
vi.mock('@/hooks/useChatConfig', () => ({ consumeDraftChatConfig: () => null, usePrimeUserChatDefaults: () => {} }));
vi.mock('@/lib/sidePanelChat', () => ({ subscribeAiChatMessages: () => () => {} }));

import { ChatPanelContent } from '@/components/app/ChatPanelContent';
import { isSidePanelConversation, resetSidePanelConversationsForTest } from '@/lib/sidePanel/sidePanelConversations';
import { wasOnScreenBeforeReload } from '@/lib/sidePanel/onScreenAcrossReload';

const STORAGE_KEY = 'livecontext_side_panel_conversation_id:tables:5';

beforeEach(() => {
  h.chatCoreProps = [];
  h.onStreamComplete = null;
  api.getConversation.mockReset().mockResolvedValue(waitingForSlack);
  api.getRecentMessagesAsc.mockReset().mockResolvedValue([]);
  sessionStorage.clear();
  resetSidePanelConversationsForTest();
});
afterEach(cleanup);

describe('ChatPanelContent after a reload', () => {
  it('regression: hands ChatCore the conversation whole, so the credential card comes back', async () => {
    sessionStorage.setItem(STORAGE_KEY, 'c1');

    render(<ChatPanelContent />);

    await waitFor(() => {
      const last = h.chatCoreProps[h.chatCoreProps.length - 1];
      expect((last?.conversation as typeof waitingForSlack | null)?.pendingActions).toHaveLength(1);
    });
  });

  it('marks its conversation as a side-panel one', async () => {
    sessionStorage.setItem(STORAGE_KEY, 'c1');

    render(<ChatPanelContent />);

    await waitFor(() => expect(isSidePanelConversation('c1')).toBe(true));
  });

  it('marks the AI Chat on screen while mounted, and unmarks it when it leaves', () => {
    const view = render(<ChatPanelContent />);
    expect(wasOnScreenBeforeReload('ai-chat', '/app/tables/5')).toBe(true);

    view.unmount();

    expect(wasOnScreenBeforeReload('ai-chat', '/app/tables/5')).toBe(false);
  });

  it('reads the conversation again when a turn ends, which is when its waiting cards are saved', async () => {
    sessionStorage.setItem(STORAGE_KEY, 'c1');
    api.getConversation.mockReset()
      .mockResolvedValueOnce({ id: 'c1', pendingActions: [] })
      .mockResolvedValueOnce(waitingForSlack);
    render(<ChatPanelContent />);
    await waitFor(() => expect(h.onStreamComplete).not.toBeNull());

    await h.onStreamComplete!('c1');

    await waitFor(() => {
      const last = h.chatCoreProps[h.chatCoreProps.length - 1];
      expect((last?.conversation as typeof waitingForSlack | null)?.pendingActions).toHaveLength(1);
    });
  });
});

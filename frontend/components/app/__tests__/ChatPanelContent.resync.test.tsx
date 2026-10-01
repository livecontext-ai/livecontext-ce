/**
 * @vitest-environment jsdom
 *
 * The side-panel chat re-reads its conversation at every moment a reply can have been saved
 * without this tab hearing it: a WebSocket reconnect (even with no stream live), a stream of it
 * reporting an error (a fallback can finish the turn, a `done` can be lost), and a stream of it
 * completing. Before, it re-read only on completion, so an agent reply saved after an error, or
 * finished while the socket was down, stayed invisible until the panel was reopened.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, waitFor } from '@testing-library/react';

type Callbacks = {
  onStreamComplete?: (conversationId: string, content: string, model: string) => void;
  onError?: (error: { message: string; retryable: boolean }, conversationId?: string) => void;
};

const h = vi.hoisted(() => ({
  chatCoreProps: [] as Array<Record<string, unknown>>,
  reconnectListener: null as null | (() => void),
  reconnectCallbacks: null as null | Callbacks,
  sendCallbacks: null as null | Callbacks,
  stored: null as null | Record<string, unknown>,
  created: null as null | Record<string, unknown>,
  getRecentMessagesAsc: vi.fn(async (..._args: unknown[]) => [] as unknown[]),
}));

vi.mock('@/lib/websocket', () => ({
  useWsReconnected: (callback: () => void) => { h.reconnectListener = callback; },
}));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: false, isLoading: false }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ usePathname: () => '/en/app/chat' }));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/en/app/chat',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock('@/hooks/useModels', () => ({
  useVisibleModels: () => ({ models: [], defaultModel: undefined, isLoading: false, error: null }),
  EMPTY_SELECTED_MODEL: { provider: '', id: '' },
  modelMatches: () => false,
  selectedModelFromAIModel: () => ({ provider: '', id: '' }),
  selectedModelEquals: () => true,
  getEffectiveDefaultSelectedModel: () => ({ provider: 'openai', id: 'gpt-4' }),
}));
vi.mock('@/components/ai/NoProviderCta', () => ({ NoProviderCta: () => null }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({ ModelSelectorDropdown: () => null, PROVIDER_ICON_MAP: {} }));
vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => ({
    isStreamingConversation: () => false,
    checkAndReconnect: (_id: string, callbacks: Callbacks) => { h.reconnectCallbacks = callbacks; },
    sendMessage: async (_params: unknown, callbacks: Callbacks) => { h.sendCallbacks = callbacks; return 'c-new'; },
  }),
}));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
vi.mock('@/components/chat/ChatCore', () => ({
  ChatCore: (props: Record<string, unknown>) => {
    h.chatCoreProps.push(props);
    return null;
  },
}));
vi.mock('@/app/shared/components', () => ({ WelcomeTitle: () => null }));
vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: {
    getConversation: vi.fn(async () => h.stored),
    createConversation: vi.fn(async () => h.created),
    getRecentMessagesAsc: (...args: unknown[]) => h.getRecentMessagesAsc(...args),
  },
}));
vi.mock('@/hooks/useChatConfig', () => ({ consumeDraftChatConfig: () => null, usePrimeUserChatDefaults: () => {} }));
vi.mock('@/lib/sidePanelChat', () => ({ subscribeAiChatMessages: () => () => {} }));

import { ChatPanelContent } from '@/components/app/ChatPanelContent';

const STORAGE_KEY = 'livecontext_side_panel_conversation_id:chat';
const lastProps = () => h.chatCoreProps[h.chatCoreProps.length - 1];
const reply = { id: 'r1', role: 'assistant', content: 'Your order shipped.' };

async function openStoredConversation() {
  sessionStorage.setItem(STORAGE_KEY, 'c1');
  h.stored = { id: 'c1' };
  render(<ChatPanelContent />);
  await waitFor(() => expect(h.reconnectCallbacks).not.toBeNull());
  h.getRecentMessagesAsc.mockClear();
}

describe('ChatPanelContent - re-reading a reply the tab did not hear', () => {
  beforeEach(() => {
    h.chatCoreProps = [];
    h.reconnectListener = null;
    h.reconnectCallbacks = null;
    h.sendCallbacks = null;
    h.stored = null;
    h.created = null;
    h.getRecentMessagesAsc.mockReset();
    h.getRecentMessagesAsc.mockResolvedValue([]);
    sessionStorage.clear();
  });
  afterEach(cleanup);

  it('re-reads its conversation when the WebSocket session comes back, even with no stream live', async () => {
    await openStoredConversation();
    h.getRecentMessagesAsc.mockResolvedValueOnce([reply]);

    await act(async () => { h.reconnectListener!(); });

    expect(h.getRecentMessagesAsc).toHaveBeenCalledWith('c1');
    await waitFor(() => expect(lastProps().messages).toEqual([reply]));
  });

  it('re-reads when a stream it reconnected to reports an error (the reply may be saved)', async () => {
    await openStoredConversation();

    await act(async () => { h.reconnectCallbacks!.onError?.({ message: 'bridge link failed', retryable: true }, 'c1'); });

    expect(h.getRecentMessagesAsc).toHaveBeenCalledWith('c1');
  });

  it('keeps the conversation of a FIRST message whose turn errors, and re-reads it', async () => {
    // The panel has no URL: its conversation lives in state and sessionStorage, created BEFORE
    // the send, so an error on the first turn cannot lose it.
    h.created = { id: 'c-new' };
    render(<ChatPanelContent />);
    await act(async () => {
      await (lastProps().onSendMessage as (content: string) => Promise<void>)('Where is my order?');
    });
    h.getRecentMessagesAsc.mockResolvedValueOnce([reply]);

    await act(async () => { h.sendCallbacks!.onError?.({ message: 'bridge link failed', retryable: true }, 'c-new'); });

    expect(lastProps().conversationId).toBe('c-new');
    expect(sessionStorage.getItem(STORAGE_KEY)).toBe('c-new');
    expect(h.getRecentMessagesAsc).toHaveBeenCalledWith('c-new');
    await waitFor(() => expect(lastProps().messages).toEqual([reply]));

    // And a later reconnect finds the same conversation.
    h.getRecentMessagesAsc.mockClear();
    await act(async () => { h.reconnectListener!(); });
    expect(h.getRecentMessagesAsc).toHaveBeenCalledWith('c-new');
  });

  it('does not re-read on a refused send (no conversation id: nothing was saved)', async () => {
    await openStoredConversation();
    await act(async () => {
      await (lastProps().onSendMessage as (content: string) => Promise<void>)('hello');
    });
    h.getRecentMessagesAsc.mockClear();

    await act(async () => { h.sendCallbacks!.onError?.({ message: 'Failed to send message', retryable: true }); });

    expect(h.getRecentMessagesAsc).not.toHaveBeenCalled();
  });

  it('still re-reads when a stream of it completes', async () => {
    await openStoredConversation();

    await act(async () => { h.reconnectCallbacks!.onStreamComplete?.('c1', 'Your order shipped.', 'gpt-4'); });

    expect(h.getRecentMessagesAsc).toHaveBeenCalledWith('c1');
  });
});

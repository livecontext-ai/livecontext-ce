/**
 * @vitest-environment jsdom
 *
 * The chat page re-reads its conversation when the WebSocket session comes back.
 *
 * Anything published while the tab had no session is gone (the gateway keeps no backlog), and a
 * reply can finish entirely in that window: StreamingContext settles the stream it followed, but
 * the thread itself is only re-read here, whether or not a stream was live. And a turn reached
 * through the page's own reconnection (a reload mid-reply) re-reads on `error` too, because an
 * error does not prove the reply was not saved.
 *
 * Everything around the page is stubbed; what is real is ChatPageV2's own wiring.
 */
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render } from '@testing-library/react';

const h = vi.hoisted(() => ({
  reconnectListener: null as null | (() => void),
  loadConversationAndMessages: vi.fn(async () => {}),
  checkAndReconnect: vi.fn(async () => true),
  pageState: { currentConversationId: null as string | null },
  streaming: {
    serverStreamsLoaded: false,
    serverReportsActive: false,
  },
}));

vi.mock('@/lib/websocket', () => ({
  useWsReconnected: (callback: () => void) => { h.reconnectListener = callback; },
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ user: {}, isAuthenticated: true, isReady: true, isLoading: false }),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: { updateConversation: vi.fn() } }));
vi.mock('@/hooks/useConversationSurfaceRedirect', () => ({ useConversationSurfaceRedirect: () => {} }));
vi.mock('@/components/Toast', () => ({ useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }) }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/hooks/useChatConfig', () => ({ usePrimeUserChatDefaults: () => {} }));
vi.mock('@/hooks/useModels', () => ({
  useVisibleModels: () => ({ models: [], defaultModel: null, isLoading: false, error: null }),
  modelMatches: () => false,
}));
vi.mock('@/lib/hooks/useAnchorScrollToBottom', () => ({ useAnchorScrollToBottom: () => {} }));
vi.mock('@/app/shared/components/ChatPageLayout', () => ({ ChatPageLayout: () => null }));
vi.mock('@/components/chat/ServiceApprovalCard', () => ({ ServiceApprovalCard: () => null }));
vi.mock('@/components/chat/ModelSelectorDropdown', () => ({ ModelSelectorDropdown: () => null }));
vi.mock('@/components/chat/modelFilterLabels', () => ({ modelFilterLabelsFrom: () => ({}) }));
vi.mock('@/components/ai/NoProviderCta', () => ({ NoProviderCta: () => null }));
vi.mock('@/components/billing/UpgradeRequiredBadge', () => ({ UpgradeRequiredNotice: () => null }));
vi.mock('@/components/billing/FreeTierBadge', () => ({ FreeTierBadge: () => null }));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({
    blocked: false, blockedForModel: () => false, freeTierForModel: () => false, prefersFreeTierModels: false,
  }),
}));
vi.mock('@/lib/hooks/usePreferFreeTierModel', () => ({ usePreferFreeTierModel: () => {} }));
vi.mock('@/components/chat/ComposerLeadingControl', () => ({ ComposerLeadingControl: () => null }));
vi.mock('@/lib/chat/linkedAgent', () => ({ resolveConversationAgentId: () => null }));
vi.mock('@/hooks/chat/useLinkedAgentLoader', () => ({ useLinkedAgentLoader: () => {} }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/lib/sidePanel/agentConfigPanelTab', () => ({ buildAgentConfigPanelTab: () => ({}) }));
vi.mock('@/lib/sidePanel/togglePanelFromHeader', () => ({ togglePanelFromHeader: () => {} }));
vi.mock('@/lib/ai-providers/providerIcons', () => ({ PROVIDER_ICON_MAP: {} }));
vi.mock('@/components/chat/orbi/isOrbiChat', () => ({ isOrbiChat: () => false }));

vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => ({
    serverStreamsLoaded: h.streaming.serverStreamsLoaded,
    getStreamState: () => null,
    isStreamingConversation: () => h.streaming.serverReportsActive,
    getStreamContent: () => '',
    getToolActivities: () => [],
    checkAndReconnect: h.checkAndReconnect,
  }),
}));

vi.mock('@/hooks/chat/useMessageHandlersV2', () => ({
  useMessageHandlersV2: () => ({
    handleSendMessage: vi.fn(), handleStopStream: vi.fn(), handleKeyPress: vi.fn(),
    isStartingStream: false, setStartingStream: vi.fn(),
  }),
}));

vi.mock('@/hooks/useChatPageStateV3', () => ({
  useChatPageStateV3: () => ({
    selectedModel: { id: 'm', provider: 'p' }, setSelectedModel: vi.fn(),
    showModelSelector: false, setShowModelSelector: vi.fn(),
    reasoningEffort: '', setReasoningEffort: vi.fn(),
    mode: 'auto',
    currentConversationId: h.pageState.currentConversationId, setCurrentConversationId: vi.fn(),
    conversationHistory: {
      messages: [], loading: false, error: null, hasMoreMessages: false, loadingOlderMessages: false,
      loadOlderMessages: vi.fn(), loadMessages: vi.fn(),
      loadConversationAndMessages: h.loadConversationAndMessages,
      conversations: [], currentConversation: null, loadConversations: vi.fn(), addMessageLocal: vi.fn(),
    },
    inputValue: '', setInputValue: vi.fn(),
    analyzeBadges: [], setAnalyzeBadges: vi.fn(),
    showScrollToBottom: false, setShowScrollToBottom: vi.fn(),
    messagesContainerRef: { current: null },
    sendError: null, setSendError: vi.fn(),
    pendingUserMessage: null, setPendingUserMessage: vi.fn(),
    attachments: [], showAttachmentMenu: false, setShowAttachmentMenu: vi.fn(),
    showWorkflowSuggestions: false,
    agentName: null, setAgentName: vi.fn(), agentAvatarUrl: null, setAgentAvatarUrl: vi.fn(),
    agentIdFromConversation: null, setAgentIdFromConversation: vi.fn(), setIsLoadingAgent: vi.fn(),
    showAgentConfigPanel: false, setShowAgentConfigPanel: vi.fn(),
    agentConfigPanelWidth: 0, setAgentConfigPanelWidth: vi.fn(),
  }),
}));

import { ChatPageV2 } from '../ChatPageV2';

const silentReads = () =>
  h.loadConversationAndMessages.mock.calls.filter((call) => (call as unknown[])[1] && ((call as unknown[])[1] as { silent?: boolean }).silent);

describe('ChatPageV2 - re-read after the WebSocket session comes back', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    h.reconnectListener = null;
    h.pageState.currentConversationId = null;
    h.streaming.serverStreamsLoaded = false;
    h.streaming.serverReportsActive = false;
  });

  it('re-reads the conversation just started here (still on /app/chat) silently', async () => {
    h.pageState.currentConversationId = 'conv-new';
    render(<ChatPageV2 />);

    expect(h.reconnectListener).toBeTypeOf('function');
    await act(async () => { h.reconnectListener!(); });

    expect(h.loadConversationAndMessages).toHaveBeenCalledWith('conv-new', { silent: true });
  });

  it('re-reads the conversation opened by URL, even with no stream live', async () => {
    render(<ChatPageV2 conversationIdFromParams="conv-url" />);
    h.loadConversationAndMessages.mockClear(); // the mount's explicit load is not the point here

    await act(async () => { h.reconnectListener!(); });

    expect(h.loadConversationAndMessages).toHaveBeenCalledWith('conv-url', { silent: true });
  });

  it('has nothing to re-read on an empty new chat', async () => {
    render(<ChatPageV2 />);

    await act(async () => { h.reconnectListener!(); });

    expect(h.loadConversationAndMessages).not.toHaveBeenCalled();
  });

  it('re-reads when a stream reached through a reload reports an error (the reply may be saved)', async () => {
    h.streaming.serverStreamsLoaded = true;
    h.streaming.serverReportsActive = true;
    render(<ChatPageV2 conversationIdFromParams="conv-url" />);
    expect(h.checkAndReconnect).toHaveBeenCalledTimes(1);
    const callbacks = (h.checkAndReconnect.mock.calls[0] as unknown[])[1] as {
      onError?: (error: unknown, conversationId?: string) => void;
    };

    await act(async () => { callbacks.onError?.({ message: 'bridge link failed', retryable: true }, 'conv-url'); });

    expect(silentReads()).toEqual([['conv-url', { silent: true }]]);
  });
});

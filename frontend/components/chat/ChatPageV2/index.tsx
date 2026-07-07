/**
 * ChatPage V2 - Refactored with StreamingContext
 *
 * Uses:
 * - StreamingContext for all streaming state and operations
 * - useChatPageStateV3 for UI state (no streaming state)
 * - useMessageHandlersV2 for message sending
 *
 * Removed:
 * - useStreamReconnection (replaced by StreamingContext.checkAndReconnect)
 * - Local streaming state (moved to StreamingContext)
 * - streamingStore direct usage (handled by StreamingContext)
 */

'use client';

import React, { useEffect, useMemo, useRef, useCallback, useState } from 'react';
import { useTranslations } from 'next-intl';
import { useRouter } from '@/i18n/navigation';
import { useSearchParams } from 'next/navigation';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { orchestratorApi } from '@/lib/api';
import { conversationApi } from '@/lib/api/conversationApi';
import type { ChatConfig } from '@/hooks/useChatConfig';
import { useToast } from '@/components/Toast';
import ToastContainer from '@/components/ToastContainer';

// New hooks
import { useChatPageStateV3 } from '@/hooks/useChatPageStateV3';
import { useMessageHandlersV2 } from '@/hooks/chat/useMessageHandlersV2';
import { usePrimeUserChatDefaults } from '@/hooks/useChatConfig';
import { useStreaming } from '@/contexts/StreamingContext';
import { useVisibleModels, AIModel, modelMatches } from '@/hooks/useModels';
import { useAnchorScrollToBottom } from '@/lib/hooks/useAnchorScrollToBottom';

// Components
import { ChatPageLayout } from '@/app/shared/components/ChatPageLayout';
import { ServiceApprovalCard } from '../ServiceApprovalCard';
import { ModelSelectorDropdown } from '@/components/chat/ModelSelectorDropdown';
import { NoProviderCta } from '@/components/ai/NoProviderCta';
import { ComposerLeadingControl } from '@/components/chat/ComposerLeadingControl';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { buildAgentConfigPanelTab } from '@/lib/sidePanel/agentConfigPanelTab';
import { PROVIDER_ICON_MAP } from '@/lib/ai-providers/providerIcons';

export interface ChatPageV2Props {
  conversationIdFromParams?: string;
  enableDataSource?: boolean;
}

export function ChatPageV2({ conversationIdFromParams, enableDataSource = false }: ChatPageV2Props) {
  const { user, isAuthenticated, isReady, isLoading: authLoading } = useAuthGuard();
  const router = useRouter();
  const searchParams = useSearchParams();
  const agentId = searchParams?.get('agentId') || undefined;
  const t = useTranslations();
  const { toasts, addToast, removeToast } = useToast();

  // AI Models from backend (role-filtered: non-admins don't see CLI-bridge models)
  const { models, defaultModel, isLoading: modelsLoading, error: modelsError } = useVisibleModels();
  // Only surface the no-provider empty state once the catalog has RESOLVED
  // empty - never while loading (flash) or on a fetch error (a transient
  // failure is not an onboarding state). Same contract as ModelPicker's gate.
  const modelsResolvedEmpty = !modelsLoading && !modelsError;

  // Models for the composer model selector. Spread the full AIModel (capability
  // flags, context window, rate limits, …) so ModelOptionDisplay renders the
  // enriched metadata, and overlay only `iconSlug`. Keep `provider` as the
  // canonical lowercase routing slug - the backend pricing lookup is
  // case-sensitive on (provider, model), and selectedModelFromAIModel(model)
  // reads `model.provider` into the persisted selection; capitalising it leaked
  // "Claude-code" as a routing key and tripped the 402 gate.
  const availableModels = useMemo(() => {
    return models.map((model: AIModel) => ({
      ...model,
      iconSlug: PROVIDER_ICON_MAP[model.provider.toLowerCase()] || model.provider.toLowerCase(),
    }));
  }, [models]);

  // Streaming context (single source of truth for streaming)
  const streaming = useStreaming();

  // Page state (no streaming state - it's in StreamingContext)
  const state = useChatPageStateV3({ conversationIdFromParams, enableDataSource });


  // Effective conversation ID for various operations
  const effectiveConvId = conversationIdFromParams || state.currentConversationId || '';

  // Refs to avoid recreating handlers on every render (t / addToast / conversationHistory change).
  const compactionRefreshRef = useRef<((id: string) => Promise<void>) | null>(null);
  const addToastRef = useRef(addToast);
  const tRef = useRef(t);
  useEffect(() => { addToastRef.current = addToast; }, [addToast]);
  useEffect(() => { tRef.current = t; }, [t]);

  const handleCompactionDone = useCallback(
    (conversationId: string, turnsCoveredCount: number) => {
      addToastRef.current({
        type: 'info',
        title: tRef.current('chat.compactionToastTitle'),
        message: tRef.current('chat.compactionToastMessage', { count: turnsCoveredCount }),
        duration: 6000,
      });
      compactionRefreshRef.current?.(conversationId).catch(() => {});
    },
    [],
  );

  // Prime the per-(user, workspace) chat defaults so a new conversation inherits them
  // even if the composer Options panel is never opened (V312).
  usePrimeUserChatDefaults();

  // Message handlers using StreamingContext
  const handlers = useMessageHandlersV2({
    currentConversationId: state.currentConversationId,
    setCurrentConversationId: state.setCurrentConversationId,
    selectedModel: state.selectedModel,
    reasoningEffort: state.reasoningEffort,
    inputValue: state.inputValue,
    setInputValue: state.setInputValue,
    setSendError: state.setSendError,
    addMessageLocal: state.conversationHistory.addMessageLocal,
    setPendingUserMessage: state.setPendingUserMessage,
    loadMessages: state.conversationHistory.loadMessages,
    agentId: state.agentIdFromConversation || agentId,
    onCompactionDone: handleCompactionDone,
  });

  // Destructure for easier access
  const {
    sidebarOpen,
    setSidebarOpen,
    sidebarCollapsed,
    selectedModel,
    setSelectedModel,
    showModelSelector,
    setShowModelSelector,
    mode,
    currentConversationId,
    conversationHistory,
    inputValue,
    setInputValue,
    analyzeBadges,
    showScrollToBottom,
    setShowScrollToBottom,
    messagesContainerRef,
    sendError,
    pendingUserMessage,
    attachments,
    showAttachmentMenu,
    setShowAttachmentMenu,
    showWorkflowSuggestions,
    agentName,
    agentIdFromConversation,
    showAgentConfigPanel,
    setShowAgentConfigPanel,
    agentConfigPanelWidth,
    setAgentConfigPanelWidth,
  } = state;

  const {
    messages: conversationMessages,
    loading: conversationLoading,
    error: conversationError,
    hasMoreMessages,
    loadingOlderMessages,
    loadOlderMessages: handleLoadOlderMessages,
    loadMessages,
    loadConversationAndMessages,
    conversations,
    currentConversation,
    loadConversations,
  } = conversationHistory;

  // Keep the ref pointing at the latest reloader so handleCompactionDone can call it
  // without depending on conversationHistory identity changes.
  useEffect(() => {
    compactionRefreshRef.current = loadConversationAndMessages;
  }, [loadConversationAndMessages]);

  // ===== Chat Config state (per-conversation, general chat only) =====
  const [chatConfig, setChatConfig] = useState<ChatConfig>({});
  const chatConfigSaveRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  // Load chatConfig from conversation when it changes
  useEffect(() => {
    if (currentConversation?.chatConfig) {
      setChatConfig(currentConversation.chatConfig as ChatConfig);
    } else {
      setChatConfig({});
    }
  }, [currentConversation?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleChatConfigChange = useCallback((newConfig: ChatConfig) => {
    setChatConfig(newConfig);
    // Debounced save to backend
    if (chatConfigSaveRef.current) clearTimeout(chatConfigSaveRef.current);
    const convId = conversationIdFromParams || currentConversationId;
    console.log('[ChatConfig] Config changed:', newConfig, 'convId:', convId);
    if (convId) {
      chatConfigSaveRef.current = setTimeout(() => {
        console.log('[ChatConfig] Saving to backend:', convId, newConfig);
        conversationApi.updateConversation(convId, { chatConfig: newConfig })
          .then(() => console.log('[ChatConfig] Saved successfully'))
          .catch((err) => {
            console.warn('[ChatConfig] Failed to save chat config:', err);
          });
      }, 1000);
    } else {
      console.warn('[ChatConfig] No conversationId - config will not be saved until conversation is created');
    }
  }, [conversationIdFromParams, currentConversationId]);

  // Cleanup timeout on unmount
  useEffect(() => {
    return () => {
      if (chatConfigSaveRef.current) clearTimeout(chatConfigSaveRef.current);
    };
  }, []);

  // Combine messages with pending user message
  const messages = useMemo(() => {
    if (pendingUserMessage && conversationMessages.length === 0) {
      return [pendingUserMessage];
    }
    return conversationMessages;
  }, [conversationMessages, pendingUserMessage]);

  // Clear pending message when real messages arrive
  useEffect(() => {
    if (pendingUserMessage && conversationMessages.length > 0) {
      state.setPendingUserMessage(null);
    }
  }, [conversationMessages.length, pendingUserMessage, state]);

  // Conversation title
  const conversationTitle = useMemo(() => {
    if (!currentConversationId) return null;
    if (currentConversation?.id === currentConversationId) {
      return currentConversation.title;
    }
    const conv = conversations?.find(c => c.id === currentConversationId);
    return conv?.title || null;
  }, [currentConversationId, currentConversation, conversations]);

  // Check for stream reconnection when navigating to existing conversation
  const isExistingConversation = Boolean(conversationIdFromParams && conversationIdFromParams !== 'new');
  const reconnectionAttemptedRef = useRef<string | null>(null);

  // Use refs for streaming methods to avoid effect re-runs on every streaming update
  const streamingRef = useRef(streaming);
  streamingRef.current = streaming;

  useEffect(() => {
    if (!isExistingConversation || !conversationIdFromParams) return;
    // Wait until server active streams have been fetched before checking
    if (!streaming.serverStreamsLoaded) return;
    if (reconnectionAttemptedRef.current === conversationIdFromParams) return;

    const currentStreaming = streamingRef.current;
    const convStreamState = currentStreaming.getStreamState(conversationIdFromParams);

    // Check if there's an actual LOCAL stream running
    const hasLocalActiveStream = convStreamState?.status === 'streaming';

    // Check if server reported this conversation as streaming (needs verification)
    const isServerReportedStreaming = currentStreaming.isStreamingConversation(conversationIdFromParams) && !hasLocalActiveStream;

    // Skip if already streaming locally
    if (hasLocalActiveStream) return;

    // Only call checkAndReconnect if server reported this as streaming
    // This avoids unnecessary API calls for conversations that aren't streaming
    if (!isServerReportedStreaming) return;

    reconnectionAttemptedRef.current = conversationIdFromParams;

    currentStreaming.checkAndReconnect(conversationIdFromParams, {
      onStreamComplete: (convId) => {
        // Load conversation and messages to get fresh pendingAction state
        loadConversationAndMessages(convId);
      },
    }).catch(err => {
      console.warn('[ChatPageV2] Reconnection check failed:', err);
    });
  }, [isExistingConversation, conversationIdFromParams, streaming.serverStreamsLoaded, loadConversationAndMessages]);

  // Load messages when navigating to conversation
  const loadedConversationRef = useRef<string | null>(null);
  const isLoadingConversation = conversationIdFromParams && loadedConversationRef.current !== conversationIdFromParams;

  console.log('[ChatPageV2] RENDER:', {
    conversationIdFromParams,
    isLoadingConversation: !!isLoadingConversation,
    conversationLoading,
    messagesCount: messages.length,
    isAuthenticated,
    isReady,
    loadedRef: loadedConversationRef.current,
  });

  useEffect(() => {
    // Wait for isReady (token provider configured) to avoid failed API calls
    // before auth is fully initialized. isAuthenticated alone is not enough -
    // it becomes true from OIDC storage before the token provider is set.
    if (conversationIdFromParams && isAuthenticated && isReady) {
      if (loadedConversationRef.current !== conversationIdFromParams) {
        loadedConversationRef.current = conversationIdFromParams;
        loadConversationAndMessages(conversationIdFromParams).catch((err) => {
          // Do NOT reset ref here - resetting causes infinite retry loops on transient errors (429, 5xx).
          // The ref will naturally reset when the user navigates to a different conversation.
          console.warn('[ChatPageV2] Failed to load conversation:', err);
        });
      }
    }
  }, [conversationIdFromParams, isAuthenticated, isReady, loadConversationAndMessages]);

  // Load linked agent data when conversation loads (to show avatar in header)
  const loadedAgentForConversationRef = useRef<string | null>(null);
  useEffect(() => {
    // Clear agent state when navigating to new chat (no conversationIdFromParams)
    if (!conversationIdFromParams) {
      if (loadedAgentForConversationRef.current !== null) {
        loadedAgentForConversationRef.current = null;
        state.setAgentName(null);
        state.setAgentAvatarUrl(null);
        state.setAgentIdFromConversation(null);
      }
      return;
    }

    // Wait for isReady to ensure token is available for API call
    if (!isAuthenticated || !isReady) return;
    if (loadedAgentForConversationRef.current === conversationIdFromParams) return;

    loadedAgentForConversationRef.current = conversationIdFromParams;
    state.setIsLoadingAgent(true);

    orchestratorApi.getAgentByConversationId(conversationIdFromParams)
      .then((agent) => {
        if (agent) {
          state.setAgentName(agent.name);
          state.setAgentAvatarUrl(agent.avatarUrl || null);
          state.setAgentIdFromConversation(agent.id);
        }
      })
      .catch((err: any) => {
        // 404 means no agent linked - this is normal
        if (err?.status !== 404 && !err?.message?.includes('404')) {
          console.error('Error loading linked agent:', err);
        }
        // Clear agent state if no agent linked
        state.setAgentName(null);
        state.setAgentAvatarUrl(null);
        state.setAgentIdFromConversation(null);
        // Do NOT reset ref here - resetting causes infinite retry loops on transient errors (429, 5xx).
        // The ref will naturally reset when the user navigates to a different conversation.
      })
      .finally(() => {
        state.setIsLoadingAgent(false);
      });
  }, [conversationIdFromParams, isAuthenticated, isReady, state]);

  // Auto-scroll refs
  const previousConversationIdRef = useRef<string | null>(null);
  const previousLastUserPendingIdRef = useRef<string | null>(null);
  const userScrolledUpRef = useRef(false);

  // Scroll position tracking (for showScrollToBottom button + streaming gate).
  // Threshold scales with viewport: clamp(clientHeight * 0.1, 80, 200) - robust on mobile
  // momentum scroll where 50px is not enough overshoot tolerance.
  useEffect(() => {
    const container = messagesContainerRef.current;
    if (!container) return;

    const handleScroll = () => {
      const { scrollTop, scrollHeight, clientHeight } = container;
      const threshold = Math.min(200, Math.max(80, clientHeight * 0.1));
      const isAtBottom = scrollTop + clientHeight >= scrollHeight - threshold;
      setShowScrollToBottom(!isAtBottom);
      userScrolledUpRef.current = !isAtBottom;
    };

    container.addEventListener('scroll', handleScroll);
    handleScroll();

    return () => container.removeEventListener('scroll', handleScroll);
  }, [currentConversationId, setShowScrollToBottom]);

  // Handle scroll to bottom
  const handleScrollToBottom = useCallback(() => {
    const container = messagesContainerRef.current;
    if (container) {
      container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' });
    }
  }, [messagesContainerRef]);

  // Handle file selection (placeholder)
  const handleFileSelect = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      console.log('File selected:', file.name);
    }
  }, []);

  // Handle attachment removal (placeholder)
  const removeAttachment = useCallback((id: string) => {
    console.log('Remove attachment:', id);
  }, []);

  // Handle analyze badge removal
  const handleRemoveAnalyzeBadge = useCallback((id: string) => {
    state.setAnalyzeBadges(prev => prev.filter(badge => badge.id !== id));
  }, [state]);

  // Service approval handlers are now handled by ChatCore

  // No auth check here - we allow non-authenticated users to see the chat UI
  // Authentication is required only when they try to send a message

  // Build props
  // Check if streaming is for the current conversation
  const effectiveConversationId = conversationIdFromParams || currentConversationId || '';
  const isStreamingThisConversation = streaming.isStreamingConversation(effectiveConversationId);

  // Bridge-flag latch. `handlers.isStartingStream` covers the gap between "send POST
  // resolved" and "the real stream is observed active for this conversation". Drop it
  // deterministically on the isStreaming rising edge: turning it off earlier (when the
  // POST resolved) left a 1-frame window where neither isStreaming nor isStreamStarting
  // was true, flashing a greyed-out Send button mid-send. Idempotent + guarded, so it
  // is a no-op once the bridge is down.
  useEffect(() => {
    if (handlers.isStartingStream && isStreamingThisConversation) {
      handlers.setStartingStream(false);
    }
  }, [handlers.isStartingStream, handlers.setStartingStream, isStreamingThisConversation]);

  // Get stream state for current conversation
  const currentStreamState = streaming.getStreamState(effectiveConversationId);

  // Get streaming content - show even after streaming ends (status 'completed')
  // This avoids the message disappearing before being added to messages[]
  const streamContent = streaming.getStreamContent(effectiveConversationId);
  const streamStatus = currentStreamState?.status;

  // Get tool activities for current conversation
  const streamingToolActivities = streaming.getToolActivities(effectiveConversationId);

  // Note: pendingServiceApproval is now handled internally by ChatCore

  // Keep streaming tool activities visible - they already have visualizations from streaming
  // No need to transition to historical mode (which would require DB fetch)
  const toolActivities = streamingToolActivities;

  // Show streaming section if we have a stream with data
  // The stream data (with visualizations) stays visible until cleared
  const hasStreamingData = streamContent || toolActivities.length > 0;
  const shouldShowStreamingContent = hasStreamingData &&
    (streamStatus === 'streaming' || streamStatus === 'completed' || streamStatus === 'stopped');

  // Check if streaming content matches the last message (to avoid duplicates)
  const lastMessage = messages[messages.length - 1];
  const isStreamContentDuplicate = lastMessage?.role === 'assistant' &&
    lastMessage?.content === streamContent;

  // Filter messages to exclude duplicate when stream section is visible
  const filteredMessages = useMemo(() => {
    if (shouldShowStreamingContent && isStreamContentDuplicate) {
      // Don't show the last message in history - it's already shown in streaming section
      return messages.slice(0, -1);
    }
    return messages;
  }, [messages, shouldShowStreamingContent, isStreamContentDuplicate]);

  // Initial anchor: pin to bottom on conversation switch with stabilization against
  // async images/markdown layout. The hook is keyed on conversationId and fires once
  // per id; it self-cancels on user input. Gated on `!conversationLoading` so we only
  // anchor after the first message page has been hydrated.
  const conversationKey = currentConversationId || conversationIdFromParams || null;
  const anchorReady = !!conversationKey && !conversationLoading && !loadingOlderMessages;
  useAnchorScrollToBottom(messagesContainerRef, conversationKey, anchorReady);

  // Streaming + user-send auto-scroll. Distinct from the initial anchor:
  //  - User-send: when the latest message is a freshly-sent local optimistic user
  //    message (pendingLocal=true), force scroll-to-bottom regardless of position.
  //  - Streaming chunk: only scroll if the user is already near the bottom; never
  //    yank a reader who has scrolled up.
  useEffect(() => {
    if (loadingOlderMessages) return;
    const container = messagesContainerRef.current;
    if (!container) return;

    const conversationChanged = previousConversationIdRef.current !== currentConversationId;
    if (conversationChanged) {
      previousLastUserPendingIdRef.current = null;
    }

    const lastMessage = messages[messages.length - 1] as { id?: string; role?: string; pendingLocal?: boolean } | undefined;
    const isFreshUserSend =
      !!lastMessage &&
      lastMessage.role === 'user' &&
      lastMessage.pendingLocal === true &&
      lastMessage.id !== previousLastUserPendingIdRef.current;

    if (isFreshUserSend) {
      container.scrollTop = container.scrollHeight;
      userScrolledUpRef.current = false;
      previousLastUserPendingIdRef.current = lastMessage?.id || null;
    } else if (isStreamingThisConversation && !userScrolledUpRef.current) {
      container.scrollTop = container.scrollHeight;
    }

    previousConversationIdRef.current = currentConversationId;
  }, [currentConversationId, messages, loadingOlderMessages, messagesContainerRef, isStreamingThisConversation, streamContent, toolActivities.length]);

  const toolSelectorProps = {
    isOpen: false,
    onClose: () => {},
    mode,
    viewMode: 'categories' as const,
    onViewModeChange: () => {},
    selectedCategory: 'all',
    onCategoryChange: () => {},
    toolSearchQuery: '',
    onSearchChange: () => {},
    availableTools: [],
    selectedTools: [],
    onToolSelect: () => {},
    onDeselectAll: () => {},
    toolGroups: [],
  };

  const messageHistoryProps = {
    messages: filteredMessages,
    loading: conversationLoading || !!isLoadingConversation,
    error: conversationError,
    hasMoreMessages,
    loadingOlderMessages,
    onLoadOlderMessages: () => {
      if (currentConversationId) {
        handleLoadOlderMessages(currentConversationId);
      }
    },
    scrollContainerRef: messagesContainerRef,
    // Show streaming content even after 'completed' status, but not if already in messages
    streamingMessage: shouldShowStreamingContent ? streamContent : undefined,
    // isStreaming controls loading indicators - only true when ACTIVELY streaming
    isStreaming: isStreamingThisConversation,
    // Tool activities for the activity feed (streaming only)
    toolActivities,
    showWorkflowSuggestions,
    onReplaceWorkflow: (messageId: string, template: any) => {
      console.log('Replace workflow:', messageId, template);
    },
    onReplaceDataSource: (messageId: string, dataSourceId: number) => {
      console.log('Replace datasource:', messageId, dataSourceId);
    },
    onManualWorkflowMode: () => {
      console.log('Manual workflow mode');
    },
    onDeleteVisualization: async (type: 'workflow' | 'application' | 'agent' | 'datasource' | 'interface', id: string) => {
      try {
        if (type === 'workflow') {
          await orchestratorApi.deleteWorkflow(id);
        } else if (type === 'datasource') {
          await orchestratorApi.deleteDataSource(id);
        } else if (type === 'interface') {
          await orchestratorApi.deleteInterface(id);
        } else if (type === 'application' || type === 'agent') {
          await orchestratorApi.deleteAgent(id);
        }
        console.log(`✅ Deleted ${type}:`, id);
      } catch (error) {
        console.error(`❌ Failed to delete ${type}:`, error);
      }
    },
    onRunWorkflow: (workflowId: string) => {
      console.log('Run workflow:', workflowId);
      // TODO: Implement workflow execution
    },
  };

  // ============== COMPOSER LEADING CONTROL (model selector / agent avatar) ==============
  // The model selector / agent avatar lives in the composer (left of the mic),
  // not in the header. Built here once and threaded through composerProps.
  const sidePanel = useSidePanelSafe();
  const resolvedAgentId = agentIdFromConversation || agentId || null;
  const agentAvatarUrl = state.agentAvatarUrl;

  const selectedModelData = useMemo(
    () => availableModels.find((m) => modelMatches(m, selectedModel)),
    [availableModels, selectedModel],
  );

  // Open the agent config in the right side panel, exactly like the header
  // toggle does (same tab id => merges, no duplicate). Toggle when already open.
  const handleOpenAgentPanel = useCallback(() => {
    if (!sidePanel || !resolvedAgentId) return;
    if (sidePanel.isOpen) {
      sidePanel.close();
    } else {
      sidePanel.openTab(buildAgentConfigPanelTab({ agentId: resolvedAgentId, agentName, agentAvatarUrl }));
    }
  }, [sidePanel, resolvedAgentId, agentName, agentAvatarUrl]);

  // Agent conversation -> avatar (click opens the agent config panel); otherwise
  // -> the model selector (with reasoning-effort). ComposerLeadingControl encodes
  // the decision; the model-selector node is built here since it owns model state.
  const leadingControl = useMemo(() => (
    <ComposerLeadingControl
      agentId={resolvedAgentId}
      agentName={agentName}
      agentAvatarUrl={agentAvatarUrl}
      onOpenAgentPanel={handleOpenAgentPanel}
      modelSelector={
        <ModelSelectorDropdown
          showModelSelector={showModelSelector}
          setShowModelSelector={setShowModelSelector}
          selectedModel={selectedModel}
          selectedModelData={selectedModelData}
          availableModels={availableModels}
          setSelectedModel={setSelectedModel}
          changeModelTitle={t('actions.changeModel')}
          noModelsLabel={modelsResolvedEmpty ? t('aiProviders.noProviderCta.noModels') : undefined}
          emptyState={modelsResolvedEmpty ? <NoProviderCta variant="menu" /> : undefined}
          reasoningEffort={state.reasoningEffort}
          onReasoningEffortChange={state.setReasoningEffort}
          reasoningEffortLabel={t('actions.reasoningEffort')}
          effortAutoLabel={t('actions.effortAuto')}
        />
      }
    />
  ), [agentAvatarUrl, resolvedAgentId, handleOpenAgentPanel, agentName, showModelSelector, setShowModelSelector, selectedModel, selectedModelData, availableModels, setSelectedModel, t, state.reasoningEffort, state.setReasoningEffort, modelsResolvedEmpty]);

  const composerProps = {
    inputValue,
    onInputChange: setInputValue,
    leadingControl,
    onSendMessage: handlers.handleSendMessage,
    onKeyPress: handlers.handleKeyPress,
    isChatLoading: isStreamingThisConversation || handlers.isStartingStream,
    isStreaming: isStreamingThisConversation,
    isStreamStarting: handlers.isStartingStream,
    onStopStream: handlers.handleStopStream,
    attachments,
    onFileSelect: handleFileSelect,
    onRemoveAttachment: removeAttachment,
    showAttachmentMenu,
    onShowAttachmentMenu: setShowAttachmentMenu,
    analyzeBadges,
    onRemoveAnalyzeBadge: handleRemoveAnalyzeBadge,
    showScrollToBottom,
    onScrollToBottom: handleScrollToBottom,
    chatConfig,
    onChatConfigChange: handleChatConfigChange,
    isAgentConversation: !!(agentIdFromConversation || agentId),
  };

  const showWelcomeMessage = !conversationIdFromParams && !currentConversationId && messages.length === 0 && !isLoadingConversation && !isStreamingThisConversation && !handlers.isStartingStream;
  const shouldRenderHistory = messages.length > 0 || isStreamingThisConversation || !!isLoadingConversation || conversationLoading;
  const isConversationActive = !!currentConversationId || !!conversationIdFromParams;

  const layoutState = {
    showWelcomeMessage,
    shouldRenderHistory,
    isConversationActive,
    isLoadingConversation: !!isLoadingConversation,
    messagesContainerRef,
    streamLastError: currentStreamState?.error ? {
      message: currentStreamState.error.message,
      retryable: currentStreamState.error.retryable
    } : null,
    attemptStreamReconnection: () => {
      if (conversationIdFromParams) {
        streaming.checkAndReconnect(conversationIdFromParams);
      }
    },
    // No longer have separate 'connecting' state - streaming starts immediately
    isStreamReconnecting: false,
    streamReconnectAttempts: 0,
  };

  return (
    <React.Fragment key="chat-page-v2">
      <ChatPageLayout
        key="chat-layout"
        toolSelectorProps={toolSelectorProps}
        messageHistoryProps={messageHistoryProps}
        composerProps={composerProps}
        layoutState={layoutState}
        conversationId={currentConversationId}
        conversation={currentConversation}
        conversationTitle={conversationTitle}
        enableDataSource={enableDataSource}
      />
      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />

    </React.Fragment>
  );
}

export default ChatPageV2;

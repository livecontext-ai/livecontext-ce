'use client';

/**
 * useMessageHandlersV2 - Simplified message handlers using StreamingContext
 *
 * Single Responsibility: Orchestrate message sending via StreamingContext
 * and handle side effects (URL update, sidebar update, message history).
 *
 * All streaming state is managed by StreamingContext.
 * This hook only provides:
 * - handleSendMessage: Send a message
 * - handleStopStream: Stop current stream
 * - handleKeyPress: Keyboard handler
 *
 * Auth handling:
 * - If user is not authenticated, prompts login and stores pending message
 * - After login, pending message is automatically sent
 */

import { useCallback, useRef, useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useStreaming } from '@/contexts/StreamingContext';
import { useUnifiedApp } from '@/contexts/UnifiedAppContext';
import { useAuth } from '@/lib/providers/smart-providers';
import { conversationLogger } from '@/lib/logger';
import { SelectedModel, formatSelectedModel } from '@/hooks/useModels';
import { consumeDraftChatConfig } from '@/hooks/useChatConfig';
import { clearDraft } from '@/lib/chat/draftStorage';
import type { AttachmentRef } from '@/lib/api/attachmentApi';

// Storage key for pending message before login
const PENDING_MESSAGE_KEY = 'livecontext_pending_chat_message';

interface SendMessageOptions {
  keepPendingActions?: boolean;
}

interface UseMessageHandlersV2Options {
  currentConversationId: string | null;
  setCurrentConversationId: (id: string | null) => void;
  selectedModel: SelectedModel;
  /** Per-conversation reasoning-effort override for CLI/bridge models. */
  reasoningEffort?: string;
  inputValue: string;
  setInputValue: (value: string) => void;
  setSendError: (error: { message: string; retryable: boolean; onRetry?: () => void } | null) => void;
  addMessageLocal: (conversationId: string, message: { role: string; content: string; model: string; timestamp: string; toolCalls?: string; attachments?: { storageId: string; type: string; fileName: string; mimeType: string }[]; pendingLocal?: boolean }) => void;
  setPendingUserMessage: (message: { id: string; conversationId: string; role: 'user'; content: string; model: string; timestamp: string; attachments?: { storageId: string; type: string; fileName: string; mimeType: string }[] } | null) => void;
  loadMessages: (conversationId: string) => Promise<void>;
  agentId?: string | null;
  onCompactionDone?: (
    conversationId: string,
    turnsCoveredCount: number,
    summarizerModel: string,
    generatedAt: string,
  ) => void;
}

export function useMessageHandlersV2(options: UseMessageHandlersV2Options) {
  const {
    currentConversationId,
    setCurrentConversationId,
    selectedModel,
    reasoningEffort,
    inputValue,
    setInputValue,
    setSendError,
    addMessageLocal,
    setPendingUserMessage,
    loadMessages,
    agentId,
    onCompactionDone,
  } = options;

  const pathname = usePathname();
  const router = useRouter();
  const { sendMessage, stopStream, isStreamingConversation } = useStreaming();
  const { addConversations, updateConversation, setCurrentConversationId: setContextConversationId } = useUnifiedApp();
  const { isAuthenticated, isLoading: authLoading, loginWithRedirect, isReady: authReady } = useAuth();

  // Refs for stable access in callbacks
  const inputValueRef = useRef(inputValue);
  const conversationIdRef = useRef(currentConversationId);
  const userMessageRef = useRef<{ role: 'user'; content: string; model: string; timestamp: string; attachments?: { storageId: string; type: string; fileName: string; mimeType: string }[]; pendingLocal?: boolean } | null>(null);
  const pendingMessageSentRef = useRef(false);
  const [isStartingStream, setIsStartingStream] = useState(false);
  const isStartingStreamRef = useRef(false);
  const stopRequestedBeforeConversationRef = useRef(false);

  const setStartingStream = useCallback((value: boolean) => {
    isStartingStreamRef.current = value;
    setIsStartingStream(value);
  }, []);

  useEffect(() => {
    inputValueRef.current = inputValue;
  }, [inputValue]);

  useEffect(() => {
    conversationIdRef.current = currentConversationId;
  }, [currentConversationId]);

  // Store pending message helper
  const storePendingMessage = useCallback((message: string, model: string) => {
    try {
      const pendingData = {
        message,
        model,
        timestamp: Date.now(),
        returnPath: pathname,
      };
      sessionStorage.setItem(PENDING_MESSAGE_KEY, JSON.stringify(pendingData));
    } catch (e) {
      console.warn('Failed to store pending message:', e);
    }
  }, [pathname]);

  // Get and clear pending message helper
  const getPendingMessage = useCallback(() => {
    try {
      const stored = sessionStorage.getItem(PENDING_MESSAGE_KEY);
      if (stored) {
        sessionStorage.removeItem(PENDING_MESSAGE_KEY);
        const data = JSON.parse(stored);
        // Only use if stored within last 5 minutes
        if (Date.now() - data.timestamp < 5 * 60 * 1000) {
          return data;
        }
      }
    } catch (e) {
      console.warn('Failed to get pending message:', e);
    }
    return null;
  }, []);

  /**
   * Internal send function - only called when authenticated
   */
  const doSendMessage = useCallback(async (
    content: string,
    attachments?: AttachmentRef[],
    defaultSkillIds?: string[],
    opts?: SendMessageOptions,
  ) => {
    const currentInput = content.trim();
    const convId = conversationIdRef.current;

    // Check if THIS conversation is streaming (not any conversation)
    // Allow sending if there are attachments even without content
    if ((!currentInput && (!attachments || attachments.length === 0)) || (convId && isStreamingConversation(convId))) {
      return;
    }

    // Clear input immediately for better UX
    setStartingStream(true);
    stopRequestedBeforeConversationRef.current = false;
    setInputValue('');
    setSendError(null);

    // CRITICAL: If this is an existing conversation, ensure previous messages are loaded
    if (convId) {
      try {
        await loadMessages(convId);
      } catch (err) {
        conversationLogger.warn('Failed to load messages before send, continuing anyway', { error: err });
      }
    }

    // Convert attachments to display format
    const displayAttachments = attachments?.map(a => ({
      storageId: a.storageId,
      type: a.type,
      fileName: a.fileName,
      mimeType: a.mimeType,
    }));

    // Create user message for display
    // Messages store the bare model id (for display + DB replay). The
    // provider is tracked separately on the conversation record.
    const displayModelId = selectedModel.id;
    const userMessage = {
      role: 'user' as const,
      content: currentInput,
      model: displayModelId,
      timestamp: new Date().toISOString(),
      attachments: displayAttachments,
      // Frontend-only flag: lets the auto-scroll effect distinguish a user-initiated
      // optimistic append (always scroll to bottom) from a streamed assistant chunk
      // (gated by "near bottom" so a scrolled-up reader is not yanked).
      pendingLocal: true,
    };
    userMessageRef.current = userMessage;

    // Display user message immediately
    if (currentConversationId) {
      addMessageLocal(currentConversationId, userMessage);
    } else {
      // New chat - show pending message until conversation is created
      setPendingUserMessage({
        id: `pending-${Date.now()}`,
        conversationId: 'pending',
        role: 'user',
        content: currentInput,
        model: displayModelId,
        timestamp: new Date().toISOString(),
        attachments: displayAttachments,
      });
    }

    conversationLogger.info('Sending message via StreamingContext', {
      hasConversation: !!currentConversationId
    });

    try {
      // selectedModel is a typed SelectedModel - read id + provider as two
      // fields. Sending the bare id (never the "provider:" qualified form)
      // is what the backend's pricing lookup + SDK routing both require.
      const draftChatConfig = currentConversationId ? undefined : consumeDraftChatConfig();
      const resultConversationId = await sendMessage(
        {
          message: currentInput,
          model: selectedModel.id,
          provider: selectedModel.provider,
          conversationId: currentConversationId,
          attachments,
          agentId: agentId || undefined,
          defaultSkillIds,
          chatConfig: draftChatConfig,
          reasoningEffort: reasoningEffort || undefined,
          keepPendingActions: opts?.keepPendingActions,
        },
        {
          onConversationCreated: (conversationId: string) => {
            conversationLogger.info('New conversation created', { conversationId });

            conversationIdRef.current = conversationId;
            setCurrentConversationId(conversationId);
            setContextConversationId(conversationId);

            if (userMessageRef.current) {
              addMessageLocal(conversationId, userMessageRef.current);
            }

            setPendingUserMessage(null);
            // The new-chat composer's `:new` draft produced this conversation -
            // purge it so it can never be restored and silently re-sent on a
            // later /app/chat visit (duplicate-conversation guard, defence in
            // depth for send paths that bypass MessageComposer.handleSend, e.g.
            // the post-login pending-message replay).
            clearDraft(null);

            // NOTE: the URL is intentionally NOT updated to /app/c/{id} here (mid-stream). Changing the
            // route segment while the stream is live - via window.history.replaceState (Next 14.1+ patches
            // it) OR router.replace - re-renders the route tree and REMOUNTS the whole app layout, tearing
            // down the StreamingProvider + its live conversation subscription (the "new conversation from
            // Home shows empty" bug). We defer the URL sync to onStreamComplete, once the reply is fully
            // streamed and persisted, so a remount there harmlessly reloads the finished conversation.

            addConversations([{
              id: conversationId,
              title: 'New conversation',
              createdAt: new Date().toISOString(),
              updatedAt: new Date().toISOString(),
            }]);
          },

          onStreamComplete: (completedConvId: string) => {
            conversationLogger.info('Stream completed', { conversationId: completedConvId });

            // CRITICAL: Only load messages if this conversation is still the
            // active one.  When the user stops a stream and immediately starts
            // a new conversation, stopStream fires onStreamComplete for the OLD
            // conversation AFTER the new one has already begun streaming.  The
            // loadMessages call uses a shared singleton (useMessages) whose
            // abort-controller was already reset by clearMessages during the
            // "new chat" navigation, so the stale request runs to completion
            // and overwrites the message list with the old conversation's
            // messages - making the new response "disappear."
            //
            // conversationIdRef is a ref (not a closure value) so it always
            // reflects the latest currentConversationId, even inside stale
            // callbacks stored in StreamingContext's streamRefsMap.
            if (conversationIdRef.current !== completedConvId) {
              conversationLogger.info('Skipping loadMessages - conversation changed since stream started', {
                completedConversation: completedConvId,
                currentConversation: conversationIdRef.current,
              });
              return;
            }

            // Terminal safety net (only for the CURRENT conversation - guarded by
            // the stale check above so a delayed completion for a previous
            // conversation can't drop the bridge a fresh send just raised): if the
            // stream began AND ended within a single render batch the consumer may
            // never have observed isStreaming=true to drop the starting bridge, so
            // clear it here too.
            setStartingStream(false);

            loadMessages(completedConvId).catch(err => {
              conversationLogger.error('Failed to load messages after stream complete', { error: err });
            });

            // Sync the URL to /app/c/{id} now that the reply is fully streamed AND persisted - deferred
            // from onConversationCreated because a cross-segment route change mid-stream remounts the app
            // layout and drops the live subscription (see the note there). router.replace (not raw
            // replaceState) updates the route params so the page resolves this conversation on refresh /
            // share / breadcrumb. Only when still on the new-chat URL (we created this conversation here).
            if (pathname?.endsWith('/app/chat') || pathname?.endsWith('/app')) {
              const localePrefix = pathname.replace(/\/(app\/chat|app)$/, '');
              router.replace(`${localePrefix}/app/c/${completedConvId}`, { scroll: false });
            }
          },

          onTitleUpdated: (conversationId: string, title: string) => {
            conversationLogger.info('Title updated', { conversationId, title });
            updateConversation(conversationId, { title });
          },

          onCompactionDone: (conversationId, turnsCoveredCount, summarizerModel, generatedAt) => {
            conversationLogger.info('Compaction done', {
              conversationId,
              turnsCoveredCount,
              summarizerModel,
            });
            onCompactionDone?.(conversationId, turnsCoveredCount, summarizerModel, generatedAt);
          },

          onError: (error) => {
            conversationLogger.error('Stream error', { error });
            // Terminal safety net: the stream errored and will never go active,
            // so release the starting bridge so the composer leaves the Stop state.
            setStartingStream(false);
            setSendError({
              message: error.message,
              retryable: error.retryable,
              onRetry: error.retryable
                ? () => doSendMessage(currentInput, attachments, defaultSkillIds, opts)
                : undefined,
            });
          },
        }
      );

      if (!resultConversationId) {
        conversationLogger.warn('sendMessage returned no conversation ID');
        // Terminal safety net for the null-return failure paths. Some failures
        // (insufficient credits / storage quota / missing API key) surface a modal,
        // dispatch an internal ERROR, and return null WITHOUT invoking onError or
        // throwing - so neither the onError net nor the outer catch fires and no
        // stream goes active for the latch to observe. Release the starting bridge
        // here too, otherwise the composer stays stuck showing Stop with no way to
        // recover (clicking Stop while starting only defers). Idempotent: a no-op
        // when onError already cleared it (generic-error path).
        setStartingStream(false);
      } else if (stopRequestedBeforeConversationRef.current) {
        stopRequestedBeforeConversationRef.current = false;
        try {
          await stopStream(resultConversationId);
        } catch (error) {
          conversationLogger.warn('Deferred stop failed after stream creation', { error });
        }
      }
    } catch (error) {
      conversationLogger.error('Error sending message', { error });
      // The send POST itself threw - the stream will never start, so release the
      // starting bridge here. On the SUCCESS path we deliberately do NOT reset it
      // in `finally`: it stays on until the real stream is observed active by the
      // consumer (ChatPageV2 latch) or a terminal stream event fires
      // (onStreamComplete/onError). Resetting on POST-resolve was the bug - it left
      // a 1-frame window where both isStreaming and isStreamStarting were false,
      // flashing a greyed-out Send button mid-send.
      setStartingStream(false);
      setSendError({
        message: error instanceof Error ? error.message : 'Failed to send message',
        retryable: true,
        onRetry: () => doSendMessage(currentInput, attachments, defaultSkillIds, opts),
      });
    } finally {
      stopRequestedBeforeConversationRef.current = false;
    }
  }, [
    isStreamingConversation,
    selectedModel,
    reasoningEffort,
    currentConversationId,
    pathname,
    sendMessage,
    stopStream,
    setStartingStream,
    setInputValue,
    setSendError,
    addMessageLocal,
    setPendingUserMessage,
    setCurrentConversationId,
    setContextConversationId,
    addConversations,
    updateConversation,
    loadMessages,
    agentId,
    onCompactionDone,
  ]);

  /**
   * Send a chat message using StreamingContext.
   * If not authenticated, prompts login and stores message for later.
   * @param content - Optional message content. If provided, uses this instead of inputValue.
   * @param attachments - Optional file attachments to include with the message.
   */
  const handleSendMessage = useCallback(async (
    content?: string,
    attachments?: AttachmentRef[],
    defaultSkillIds?: string[],
    opts?: SendMessageOptions,
  ) => {
    // Use provided content or fall back to inputValueRef
    const currentInput = (content || inputValueRef.current).trim();

    // Allow sending if there's content OR attachments
    if (!currentInput && (!attachments || attachments.length === 0)) {
      return;
    }

    // Check authentication - if not authenticated, store message and prompt login
    if (!isAuthenticated && !authLoading) {
      conversationLogger.info('User not authenticated, storing message and prompting login');
      // Serialise as "provider:id" so the post-login replay can
      // reconstruct the typed pair via toSelectedModel if needed.
      storePendingMessage(currentInput, formatSelectedModel(selectedModel));
      setInputValue(''); // Clear input before redirect
      loginWithRedirect({
        appState: { returnTo: pathname || '/app/chat' }
      });
      return;
    }

    // If auth is still loading, wait
    if (authLoading) {
      conversationLogger.info('Auth still loading, waiting...');
      return;
    }

    // User is authenticated, proceed with sending
    await doSendMessage(currentInput, attachments, defaultSkillIds, opts);
  }, [isAuthenticated, authLoading, storePendingMessage, selectedModel, setInputValue, loginWithRedirect, pathname, doSendMessage]);

  // Effect to send pending message after login
  useEffect(() => {
    // Only run when authenticated and ready, and not already sent
    if (!isAuthenticated || !authReady || pendingMessageSentRef.current) {
      return;
    }

    const pendingData = getPendingMessage();
    if (pendingData) {
      conversationLogger.info('Found pending message after login, sending...', { message: pendingData.message.substring(0, 50) });
      pendingMessageSentRef.current = true;

      // Small delay to ensure UI is ready
      setTimeout(() => {
        doSendMessage(pendingData.message);
      }, 100);
    }
  }, [isAuthenticated, authReady, getPendingMessage, doSendMessage]);

  /**
   * Stop the current stream.
   */
  const handleStopStream = useCallback(async () => {
    const convId = conversationIdRef.current;
    if (isStartingStreamRef.current) {
      conversationLogger.info('Stop requested while stream is starting; deferring until stream ID is available');
      stopRequestedBeforeConversationRef.current = true;
      return;
    }

    if (!convId) {
      conversationLogger.warn('Cannot stop stream: no conversation ID');
      return;
    }
    conversationLogger.info('Stopping stream for conversation:', convId);
    await stopStream(convId);
  }, [stopStream]);

  /**
   * Handle keypress (Enter to send).
   */
  const handleKeyPress = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  }, [handleSendMessage]);

  return {
    handleSendMessage,
    handleStopStream,
    handleKeyPress,
    isStartingStream,
    // Exposed so the consumer (ChatPageV2) can drop the starting bridge on the
    // isStreaming rising edge - see the latch effect there.
    setStartingStream,
  };
}

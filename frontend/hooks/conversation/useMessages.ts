'use client';

import { useState, useCallback, useRef, useEffect } from 'react';
import { conversationApi, Message } from '@/lib/api/conversationApi';
import { toWireMessage } from '@/lib/api/messageWire';
import { mergeMessages, areMessageArraysEqual, sortMessagesByTime, areMessagesDuplicate } from '@/lib/utils/messageUtils';
import { deduplicateById } from '@/lib/utils/deduplication';
import { useErrorHandler } from '@/hooks/utils/useErrorHandler';
import { useAbortController } from '@/hooks/utils/useAbortController';
import { useTimeoutWarning } from '@/hooks/utils/useTimeoutWarning';

export interface UseMessagesOptions {
  /**
   * If set, scopes message fetches to a single execution. Use "latest" to resolve to
   * the most recent execution of the conversation server-side.
   */
  executionId?: string;
}

export interface UseMessagesReturn {
  // State
  messages: Message[];
  hasMoreMessages: boolean;
  loadingOlderMessages: boolean;
  messagesLoading: boolean;
  sendingMessage: boolean;
  loadingTimeout: boolean;
  error: string | null;

  // Actions
  loadMessages: (conversationId: string, limit?: number) => Promise<void>;
  loadOlderMessages: (conversationId: string, limit?: number) => Promise<void>;
  addMessageLocal: (conversationId: string, message: Omit<Message, 'id' | 'conversationId'>, messageId?: string) => void;
  updateMessageLocal: (messageId: string, updates: Partial<Message>) => void;
  removeMessageLocal: (messageId: string) => void;
  sendMessage: (conversationId: string, message: Omit<Message, 'id' | 'conversationId' | 'createdAt'>) => Promise<void>;
  clearMessages: () => void;
  clearError: () => void;
  clearLoadingTimeout: () => void;
  setMessages: React.Dispatch<React.SetStateAction<Message[]>>;
}

/**
 * Hook for managing messages within a conversation
 * Handles loading, pagination, local operations, and sending
 */
export function useMessages(options?: UseMessagesOptions): UseMessagesReturn {
  const executionId = options?.executionId;
  // Utility hooks (DRY - eliminates duplication)
  const { error, handleError, clearError } = useErrorHandler();
  const abortController = useAbortController();
  const loadingTimeout = useTimeoutWarning(
    60000,
    () => setLoadingTimeout(true),
    '⚠️ Loading messages timeout reached'
  );
  const sendingTimeout = useTimeoutWarning(
    60000,
    () => setLoadingTimeout(true),
    '⚠️ Sending message timeout reached'
  );

  // State
  const [messages, setMessages] = useState<Message[]>([]);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [sendingMessage, setSendingMessage] = useState(false);
  const [loadingTimeoutState, setLoadingTimeout] = useState(false);

  // Message pagination state
  const [messagePage, setMessagePage] = useState(0);
  const [hasMoreMessages, setHasMoreMessages] = useState(false);
  const [loadingOlderMessages, setLoadingOlderMessages] = useState(false);
  const [conversationHasMoreMessages, setConversationHasMoreMessages] = useState<Record<string, boolean>>({});

  // Refs for stable access
  const conversationHasMoreMessagesRef = useRef<Record<string, boolean>>({});
  const messagePageRef = useRef(0);
  const hasMoreMessagesRef = useRef(false);
  const loadingOlderMessagesRef = useRef(false);
  const currentLoadingConversationRef = useRef<string | null>(null);
  const lastLoadedConversationRef = useRef<string | null>(null);

  // CRITICAL: Keep a ref to setMessages to ensure callbacks always use the latest setter
  // This fixes the issue where stale closures in streaming callbacks would not update the correct state
  const setMessagesRef = useRef(setMessages);
  setMessagesRef.current = setMessages;

  // Also keep a ref to current messages for duplicate detection
  const messagesRef = useRef(messages);
  messagesRef.current = messages;

  // Sync refs with state
  conversationHasMoreMessagesRef.current = conversationHasMoreMessages;
  messagePageRef.current = messagePage;
  hasMoreMessagesRef.current = hasMoreMessages;
  loadingOlderMessagesRef.current = loadingOlderMessages;

  // Load messages for a conversation.
  // Default 10 = first paint of the chat history; lazy-load (loadOlderMessages)
  // fetches earlier batches when the user scrolls to the top of the panel.
  const loadMessages = useCallback(async (conversationId: string, limit: number = 10) => {
    // DRY: Use utility hooks instead of manual management
    const controller = abortController.renew(); // Aborts previous + creates new
    loadingTimeout.start();
    setMessagesLoading(true);
    console.log('📥 [loadMessages] START messagesLoading=true for:', conversationId);

    currentLoadingConversationRef.current = conversationId;
    clearError();
    setMessagePage(0);
    setLoadingTimeout(false);

    const existingHasMore = conversationHasMoreMessagesRef.current[conversationId];
    if (existingHasMore !== undefined) {
      setHasMoreMessages(existingHasMore);
    } else {
      setHasMoreMessages(false);
    }

    try {
      console.log('📥 [loadMessages] Fetching messages for conversation:', conversationId);
      const response = await conversationApi.getPaginatedMessages(conversationId, 0, limit, { executionId });

      // Check if this request was aborted (DRY: use isAborted from hook)
      if (controller.signal.aborted) {
        console.log('⏭️ [loadMessages] Request was aborted for:', conversationId);
        setMessagesLoading(false);
        return;
      }

      if (currentLoadingConversationRef.current !== conversationId) {
        console.log('⏭️ [loadMessages] Ignoring stale result for conversation:', conversationId);
        setMessagesLoading(false);
        return;
      }

      const totalElements = (response as any).totalElements || 0;
      const totalPages = Math.ceil(totalElements / limit);
      const loadedMessages = (response as any).content || response;

      // Sort messages by timestamp (ascending for chat display)
      const sortedMessages = sortMessagesByTime(loadedMessages as Message[]);

      // Check if we're loading a different conversation
      const isNewConversation = lastLoadedConversationRef.current !== null &&
        lastLoadedConversationRef.current !== conversationId;

      // Update the last loaded conversation ref
      lastLoadedConversationRef.current = conversationId;

      // Merge with local messages, avoiding duplicates
      // IMPORTANT: If loading a new conversation, start fresh (don't merge with prev)
      setMessages(prev => {
        if (isNewConversation) {
          console.log('🔄 [loadMessages] Switching conversation - clearing old messages');
        }

        // Use utility function to merge messages (DRY principle)
        const merged = mergeMessages(sortedMessages, prev, isNewConversation);

        // Avoid unnecessary state update if nothing changed
        if (areMessageArraysEqual(prev, merged)) {
          return prev;
        }

        return merged;
      });

      setMessagePage(0);

      const hasMore = totalPages > 1;
      setHasMoreMessages(hasMore);
      setConversationHasMoreMessages(prev => ({
        ...prev,
        [conversationId]: hasMore
      }));

      loadingTimeout.clear(); // DRY: Use utility hook
      setMessagesLoading(false);
      console.log('✅ [loadMessages] DONE messagesLoading=false, messages:', sortedMessages.length, 'for:', conversationId);
    } catch (err) {
      // Ignore AbortError (request was intentionally cancelled)
      if (err instanceof Error && err.name === 'AbortError') {
        console.log('📭 [loadMessages] Request aborted (expected behavior)');
        setMessagesLoading(false);
        return;
      }

      // DRY: Use error handler hook
      handleError(err, 'Failed to load messages', 'loadMessages');
      setMessages([]);
      setHasMoreMessages(false);
      loadingTimeout.clear();
      setMessagesLoading(false);
    }
  }, [abortController, loadingTimeout, clearError, handleError, executionId]);

  // Load older messages (for lazy loading)
  const loadOlderMessages = useCallback(async (conversationId: string, limit: number = 10) => {
    if (!hasMoreMessagesRef.current || loadingOlderMessagesRef.current) return;

    setLoadingOlderMessages(true);
    clearError();

    try {
      const nextPage = messagePageRef.current + 1;
      const response = await conversationApi.getPaginatedMessages(conversationId, nextPage, limit, { executionId });
      const newMessages = (response as any).content || response;

      if (newMessages && Array.isArray(newMessages) && newMessages.length > 0) {
        const sortedNewMessages = sortMessagesByTime(newMessages);

        setMessages(prev => {
          // DRY: Use deduplicateById utility
          const uniqueNewMessages = deduplicateById(sortedNewMessages, prev);
          return sortMessagesByTime([...uniqueNewMessages, ...prev]);
        });

        setMessagePage(nextPage);

        // Use server truth (totalPages or hasNext) instead of length === limit. The legacy heuristic
        // misfires when the next page returns *exactly* `limit` rows - caller keeps thinking more
        // exists and fires empty fetches forever on scroll-up.
        const totalElements: number | undefined = (response as any).totalElements;
        const hasMoreSignal: boolean | undefined = (response as any).hasNext;
        const hasMore = typeof hasMoreSignal === 'boolean'
          ? hasMoreSignal
          : (typeof totalElements === 'number'
              ? (nextPage + 1) * limit < totalElements
              : newMessages.length === limit);
        setHasMoreMessages(hasMore);
        setConversationHasMoreMessages(prev => ({
          ...prev,
          [conversationId]: hasMore
        }));
      } else {
        setHasMoreMessages(false);
        setConversationHasMoreMessages(prev => ({
          ...prev,
          [conversationId]: false
        }));
      }
    } catch (err) {
      // DRY: Use error handler hook
      handleError(err, 'Failed to load older messages', 'loadOlderMessages');
    } finally {
      setLoadingOlderMessages(false);
    }
  }, [clearError, handleError, executionId]);

  // Add a message locally without API call
  // CRITICAL: Uses setMessagesRef.current to always get the latest setter
  // This prevents stale closure issues when called from streaming callbacks
  const addMessageLocal = useCallback((
    conversationId: string,
    message: Omit<Message, 'id' | 'conversationId'>,
    messageId?: string
  ) => {
    const localMessage: Message = {
      id: messageId || `temp-${Date.now()}-${Math.random()}`,
      conversationId,
      ...message
    };

    // Use the ref to get the latest setMessages function
    // This is critical for streaming callbacks that may have stale closures
    const currentSetMessages = setMessagesRef.current;

    currentSetMessages(prev => {
      if (messageId) {
        const existingIndex = prev.findIndex(msg => msg.id === messageId);
        if (existingIndex >= 0) {
          const updated = [...prev];
          updated[existingIndex] = localMessage;
          return sortMessagesByTime(updated);
        }
      }

      // Check for duplicate (use utility function - DRY)
      if (prev.some(existingMsg => areMessagesDuplicate(existingMsg, localMessage, 5000))) {
        return prev;
      }

      return sortMessagesByTime([...prev, localMessage]);
    });
  }, []);

  // Update a message locally
  const updateMessageLocal = useCallback((messageId: string, updates: Partial<Message>) => {
    setMessages(prev => {
      const updated = prev.map(msg => msg.id === messageId ? { ...msg, ...updates } : msg);
      return sortMessagesByTime(updated);
    });
  }, []);

  // Remove a message locally
  const removeMessageLocal = useCallback((messageId: string) => {
    setMessages(prev => prev.filter(msg => msg.id !== messageId));
  }, []);

  // Send message with thinking indicator
  const sendMessage = useCallback(async (
    conversationId: string,
    message: Omit<Message, 'id' | 'conversationId' | 'createdAt'>
  ) => {
    setSendingMessage(true);
    clearError();
    setLoadingTimeout(false);

    // DRY: Use timeout warning hook
    sendingTimeout.start();

    try {
      const newMessage = await conversationApi.addMessage(conversationId, toWireMessage(message));
      setMessages(prev => [...prev, newMessage as Message]);
      sendingTimeout.clear();
    } catch (err) {
      // DRY: Use error handler hook
      handleError(err, 'Failed to send message', 'sendMessage');
      sendingTimeout.clear();
    } finally {
      setSendingMessage(false);
    }
  }, [clearError, handleError, sendingTimeout]);

  // Clear messages
  const clearMessages = useCallback(() => {
    console.log('🔄 Clearing messages');

    // DRY: Use abort controller hook
    abortController.abort();

    setMessages([]);
    currentLoadingConversationRef.current = null;
    lastLoadedConversationRef.current = null;
  }, [abortController]);

  // Clear loading timeout (kept for backwards compatibility)
  const clearLoadingTimeout = useCallback(() => {
    loadingTimeout.clear();
    sendingTimeout.clear();
  }, [loadingTimeout, sendingTimeout]);

  return {
    messages,
    hasMoreMessages,
    loadingOlderMessages,
    messagesLoading,
    sendingMessage,
    loadingTimeout: loadingTimeoutState,
    error,
    loadMessages,
    loadOlderMessages,
    addMessageLocal,
    updateMessageLocal,
    removeMessageLocal,
    sendMessage,
    clearMessages,
    clearError,
    clearLoadingTimeout,
    setMessages,
  };
}

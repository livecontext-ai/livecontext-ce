'use client';

import { useState, useEffect, useCallback, useRef } from 'react';
import { Conversation, Message } from '@/lib/api/conversationApi';
import { useUnifiedApp } from '@/contexts/UnifiedAppContext';
import {
  useConversationList,
  useMessages,
  useConversationMutations,
} from './conversation';
import { useDeletedConversationsSync } from './conversation/useDeletedConversationsSync';

export interface UseConversationHistoryOptions {
  autoLoad?: boolean;
  pageSize?: number;
}

export interface UseConversationHistoryReturn {
  // State
  conversations: Conversation[];
  currentConversation: Conversation | null;
  messages: Message[];
  loading: boolean;
  error: string | null;
  hasMore: boolean;
  currentPage: number;

  // Message pagination state
  hasMoreMessages: boolean;
  loadingOlderMessages: boolean;
  sendingMessage: boolean;
  loadingTimeout: boolean;

  // Actions
  loadConversations: (page?: number, forceRefresh?: boolean) => Promise<void>;
  loadMoreConversations: () => Promise<void>;
  searchConversations: (searchTerm: string, searchType?: 'title' | 'content') => Promise<void>;
  clearSearch: () => Promise<void>;
  selectConversation: (conversation: Conversation | null) => void;
  loadMessages: (conversationId: string) => Promise<void>;
  loadConversationAndMessages: (conversationId: string) => Promise<void>;
  loadOlderMessages: (conversationId: string) => Promise<void>;
  createConversation: (title: string, model: string, provider: string) => Promise<Conversation | null>;
  updateConversation: (conversationId: string, updates: Partial<Conversation>) => Promise<void>;
  deleteConversation: (conversationId: string) => Promise<void>;
  addMessageLocal: (conversationId: string, message: Omit<Message, 'id' | 'conversationId'>, messageId?: string) => void;
  updateMessageLocal: (messageId: string, updates: Partial<Message>) => void;
  removeMessageLocal: (messageId: string) => void;
  sendMessage: (conversationId: string, message: Omit<Message, 'id' | 'conversationId' | 'createdAt'>) => Promise<void>;
  refreshCurrentConversation: () => Promise<void>;
  clearError: () => void;
  clearLoadingTimeout: () => void;
  refetchConversations: () => void;
  forceRefreshConversations: () => Promise<void>;
  loadConversationById: (conversationId: string) => Promise<Conversation | null>;
  clearMessages: () => void;
}

/**
 * Hook for managing conversation history
 *
 * This is an orchestrator hook that combines:
 * - useConversationList: Conversation list with React Query, pagination, search
 * - useMessages: Message loading, pagination, local operations
 * - useConversationMutations: Create, update, delete operations
 *
 * For simpler use cases, you can import the individual hooks directly.
 */
export function useConversationHistory({
  autoLoad = true,
  pageSize = 50
}: UseConversationHistoryOptions = {}): UseConversationHistoryReturn {
  // Get state from shared context for sync
  const { state: appState, removeConversation: removeSharedConversation } = useUnifiedApp();
  const sharedConversations = appState.conversations;

  // Current conversation state
  const [currentConversation, setCurrentConversation] = useState<Conversation | null>(null);
  const [conversationHasMoreMessages, setConversationHasMoreMessages] = useState<Record<string, boolean>>({});

  // Use the split hooks
  const conversationList = useConversationList({ autoLoad, pageSize });
  const messagesHook = useMessages();

  // Refs for sub-hook functions to avoid unstable callback dependencies
  const loadConversationByIdRef = useRef(conversationList.loadConversationById);
  loadConversationByIdRef.current = conversationList.loadConversationById;

  const loadMessagesRef = useRef(messagesHook.loadMessages);
  loadMessagesRef.current = messagesHook.loadMessages;

  const clearMessagesRef = useRef(messagesHook.clearMessages);
  clearMessagesRef.current = messagesHook.clearMessages;

  const currentConversationRef = useRef(currentConversation);
  currentConversationRef.current = currentConversation;

  const mutations = useConversationMutations({
    onConversationCreated: (conv) => {
      conversationList.setConversations(prev => [conv, ...prev]);
    },
    onConversationUpdated: (conv) => {
      conversationList.setConversations(prev =>
        prev.map(c => c.id === conv.id ? conv : c)
      );
      if (currentConversation?.id === conv.id) {
        setCurrentConversation(conv);
      }
    },
    onConversationDeleted: (convId) => {
      conversationList.setConversations(prev =>
        prev.filter(c => c.id !== convId)
      );
      if (currentConversation?.id === convId) {
        setCurrentConversation(null);
        messagesHook.clearMessages();
      }
    },
  });

  // Stable reference for the conversation list - only consumed by the
  // `deleteConversation` wrapper below, which needs to read the latest list
  // without re-creating the callback identity on every render.
  const conversationListRef = useRef(conversationList);
  conversationListRef.current = conversationList;

  // Sync deletions from shared context. The actual logic lives in
  // useDeletedConversationsSync - kept as a separate hook so the
  // disappearance heuristic can be unit-tested without mocking the entire
  // chat stack.
  const setListConversations = conversationList.setConversations;
  useDeletedConversationsSync({
    sharedConversations,
    listConversations: conversationList.conversations,
    removeFromList: (disappearedIds) => {
      setListConversations(prev => prev.filter(conv => !disappearedIds.has(conv.id)));
    },
    currentConversationId: currentConversation?.id ?? null,
    onCurrentDeleted: () => {
      setCurrentConversation(null);
      clearMessagesRef.current();
    },
  });

  // Select a conversation (without auto-loading messages)
  const selectConversation = useCallback((conversation: Conversation | null) => {
    setCurrentConversation(conversation);
    if (!conversation) {
      clearMessagesRef.current();
    }
  }, []);

  // Load conversation and its messages
  const loadConversationAndMessages = useCallback(async (conversationId: string) => {
    // If switching to a different conversation, clear old messages first
    if (currentConversationRef.current && currentConversationRef.current.id !== conversationId) {
      clearMessagesRef.current();
    }

    // Load conversation from API to get fresh data (including pendingAction)
    const conversation = await loadConversationByIdRef.current(conversationId);
    if (conversation) {
      setCurrentConversation(conversation);
    }

    // Load messages
    await loadMessagesRef.current(conversationId);
  }, []); // Stable - never recreated

  // Refresh current conversation
  const refreshCurrentConversation = useCallback(async () => {
    if (currentConversationRef.current) {
      await loadConversationAndMessages(currentConversationRef.current.id);
    }
  }, [loadConversationAndMessages]);

  // Delete conversation wrapper
  const mutationsRef = useRef(mutations);
  mutationsRef.current = mutations;

  const deleteConversation = useCallback(async (conversationId: string) => {
    await mutationsRef.current.deleteConversation(
      conversationId,
      conversationListRef.current.conversations,
      currentConversationRef.current?.id
    );
  }, []);

  // Update conversation wrapper
  const updateConversation = useCallback(async (
    conversationId: string,
    updates: Partial<Conversation>
  ) => {
    await mutations.updateConversation(conversationId, updates);
  }, [mutations]);

  // Combined error from all hooks
  const error = conversationList.error || messagesHook.error || mutations.error;

  // Clear error from all hooks
  const clearError = useCallback(() => {
    messagesHook.clearError();
    mutationsRef.current.clearError();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messagesHook.clearError]);

  return {
    // State
    conversations: conversationList.conversations,
    currentConversation,
    messages: messagesHook.messages,
    loading: conversationList.loading || mutations.loading || messagesHook.messagesLoading,
    error,
    hasMore: conversationList.hasMore,
    currentPage: conversationList.currentPage,

    // Message pagination state
    hasMoreMessages: messagesHook.hasMoreMessages,
    loadingOlderMessages: messagesHook.loadingOlderMessages,
    sendingMessage: messagesHook.sendingMessage,
    loadingTimeout: messagesHook.loadingTimeout,

    // Actions
    loadConversations: conversationList.loadConversations,
    loadMoreConversations: conversationList.loadMoreConversations,
    searchConversations: conversationList.searchConversations,
    clearSearch: conversationList.clearSearch,
    selectConversation,
    loadMessages: messagesHook.loadMessages,
    loadConversationAndMessages,
    loadOlderMessages: messagesHook.loadOlderMessages,
    createConversation: mutations.createConversation,
    updateConversation,
    deleteConversation,
    addMessageLocal: messagesHook.addMessageLocal,
    updateMessageLocal: messagesHook.updateMessageLocal,
    removeMessageLocal: messagesHook.removeMessageLocal,
    sendMessage: messagesHook.sendMessage,
    refreshCurrentConversation,
    clearError,
    clearLoadingTimeout: messagesHook.clearLoadingTimeout,
    refetchConversations: conversationList.refetchConversations,
    forceRefreshConversations: conversationList.forceRefreshConversations,
    loadConversationById: conversationList.loadConversationById,
    clearMessages: messagesHook.clearMessages,
  };
}

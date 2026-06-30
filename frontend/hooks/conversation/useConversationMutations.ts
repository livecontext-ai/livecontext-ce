'use client';

import { useState, useCallback } from 'react';
import { conversationApi, Conversation, Message } from '@/lib/api/conversationApi';
import { useUnifiedApp } from '@/contexts/UnifiedAppContext';
import { useErrorHandler } from '@/hooks/utils/useErrorHandler';
import { consumeDraftChatConfig } from '@/hooks/useChatConfig';

export interface UseConversationMutationsOptions {
  onConversationCreated?: (conversation: Conversation) => void;
  onConversationUpdated?: (conversation: Conversation) => void;
  onConversationDeleted?: (conversationId: string) => void;
}

export interface UseConversationMutationsReturn {
  // State
  loading: boolean;
  error: string | null;

  // Actions
  createConversation: (title: string, model: string, provider: string) => Promise<Conversation | null>;
  updateConversation: (conversationId: string, updates: Partial<Conversation>) => Promise<void>;
  deleteConversation: (conversationId: string, conversations: Conversation[], currentConversationId?: string | null) => Promise<void>;
  clearError: () => void;
}

/**
 * Hook for conversation CRUD mutations
 * Handles create, update, delete operations with optimistic updates
 */
export function useConversationMutations(
  options: UseConversationMutationsOptions = {}
): UseConversationMutationsReturn {
  const { onConversationCreated, onConversationUpdated, onConversationDeleted } = options;
  const { addConversations, removeConversation: removeSharedConversation } = useUnifiedApp();

  // DRY: Use error handler hook
  const { error, handleError, clearError } = useErrorHandler();
  const [loading, setLoading] = useState(false);

  // Create a new conversation
  const createConversation = useCallback(async (
    title: string,
    model: string,
    provider: string
  ): Promise<Conversation | null> => {
    setLoading(true);
    clearError();

    try {
      // Seed the draft chat-config (edited in Options before any conversation existed)
      // into the new conversation. One-shot: consume clears the draft so the next
      // fresh chat starts blank. Returns undefined when empty → field is omitted.
      const draftChatConfig = consumeDraftChatConfig();
      const conversation = await conversationApi.createConversation({
        title,
        model,
        provider,
        ...(draftChatConfig ? { chatConfig: draftChatConfig } : {}),
      });

      // Sync with shared context
      const compactConversation = {
        id: (conversation as any).id,
        title: (conversation as any).title,
        updatedAt: (conversation as any).updatedAt
      };
      addConversations([compactConversation]);

      onConversationCreated?.(conversation as Conversation);
      return conversation as Conversation;
    } catch (err) {
      // DRY: Use error handler hook
      handleError(err, 'Failed to create conversation', 'createConversation');
      return null;
    } finally {
      setLoading(false);
    }
  }, [addConversations, onConversationCreated]);

  // Update a conversation
  const updateConversation = useCallback(async (
    conversationId: string,
    updates: Partial<Conversation>
  ) => {
    setLoading(true);
    clearError();

    try {
      const updatedConversation = await conversationApi.updateConversation(conversationId, updates);
      onConversationUpdated?.(updatedConversation as Conversation);
    } catch (err) {
      // DRY: Use error handler hook
      handleError(err, 'Failed to update conversation', 'updateConversation');
    } finally {
      setLoading(false);
    }
  }, [onConversationUpdated]);

  // Delete a conversation
  const deleteConversation = useCallback(async (
    conversationId: string,
    conversations: Conversation[],
    currentConversationId?: string | null
  ) => {
    setLoading(true);
    clearError();

    try {
      await conversationApi.deleteConversation(conversationId);
      removeSharedConversation(conversationId);
      onConversationDeleted?.(conversationId);
    } catch (err) {
      console.error('❌ [DELETE] Error deleting conversation:', err);

      // Even on error, try to remove locally
      // Sometimes the server returns an error but the deletion happened
      const stillExists = conversations.some(conv => conv.id === conversationId);
      if (!stillExists) {
        removeSharedConversation(conversationId);
        onConversationDeleted?.(conversationId);
      } else {
        // DRY: Use error handler hook
        handleError(err, 'Failed to delete conversation', 'deleteConversation');
      }
    } finally {
      setLoading(false);
    }
  }, [removeSharedConversation, onConversationDeleted]);

  return {
    loading,
    error,
    createConversation,
    updateConversation,
    deleteConversation,
    clearError,
  };
}

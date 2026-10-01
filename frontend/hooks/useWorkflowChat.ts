/**
 * Hook for managing workflow-specific chat conversations
 * Each workflow has its own persistent conversation via Redis Pub/Sub
 *
 * Note: Streaming state (isStreaming, streamingContent, toolActivities) is now
 * handled directly by ChatCore from StreamingContext for consistency with ChatPageV2.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { conversationApi, Message, type Conversation } from '@/lib/api/conversationApi';
import { reconcileMessageIdentity } from '@/lib/utils/messageUtils';
import { orchestratorApi } from '@/lib/api';
import { useStreaming } from '@/contexts/StreamingContext';
import { SelectedModel, getEffectiveDefaultSelectedModel } from '@/hooks/useModels';
import { useConversationResync } from '@/hooks/chat/useConversationResync';

interface UseWorkflowChatOptions {
  workflowId: string | undefined;
  workflowTitle?: string;
  /**
   * Typed { provider, id } selection. Callers holding a legacy string MUST
   * normalise via {@link toSelectedModel} before passing in - the hook no
   * longer accepts unnormalised strings, so a qualified id with a colon
   * cannot leak to the backend through this entry point.
   */
  model?: SelectedModel;
  autoLoad?: boolean;
}

interface UseWorkflowChatReturn {
  // Conversation state
  conversationId: string | null;
  /**
   * The conversation as read, whole, so ChatCore can rebuild the cards the agent left waiting
   * (a credential to connect...) from its pendingActions after a reload. Read again when a turn
   * ends, which is when those cards are saved.
   */
  conversation: Conversation | null;
  messages: Message[];
  isLoading: boolean;
  error: string | null;

  // Actions
  sendMessage: (content: string) => Promise<void>;
  loadConversation: (force?: boolean) => Promise<void>;
  clearMessages: () => void;
  stopStream: () => void;
}

export function useWorkflowChat({
  workflowId,
  workflowTitle,
  model,
  autoLoad = true,
}: UseWorkflowChatOptions): UseWorkflowChatReturn {
  // Default to the effective default when the caller omits a selection -
  // the value is already well-typed so no parsing / normalisation is needed.
  const resolvedModel: SelectedModel = model ?? getEffectiveDefaultSelectedModel();
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [conversation, setConversation] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const streaming = useStreaming();
  const hasLoadedRef = useRef<string | null>(null);
  const conversationIdRef = useRef<string | null>(null);

  // Keep ref in sync with state
  useEffect(() => {
    conversationIdRef.current = conversationId;
  }, [conversationId]);

  const refreshConversation = useCallback(async (convId: string) => {
    try {
      const reread = await conversationApi.getConversation(convId) as Conversation | null;
      if (reread?.id === convId && conversationIdRef.current === convId) setConversation(reread);
    } catch {
      // Keeps what it had: the messages reload reports its own failure.
    }
  }, []);

  // The chat's one silent re-read of its conversation, from the DB so the reply carries its tool
  // calls. Identity-preserving: only the bubbles that actually changed re-render, so picking up the
  // persisted reply costs no visible refresh. Dropped when the workflow (hence the conversation)
  // changed meanwhile. The conversation itself is read again too: its waiting cards (a credential
  // to connect...) are saved when a turn ends.
  const rereadMessages = useCallback(async (convId: string) => {
    void refreshConversation(convId);
    try {
      const messagesData = await conversationApi.getRecentMessagesAsc(convId);
      if (conversationIdRef.current !== convId) return;
      if (Array.isArray(messagesData)) {
        setMessages(prev => reconcileMessageIdentity(prev, messagesData));
      }
    } catch (err) {
      console.error('[useWorkflowChat] Failed to reload messages:', err);
    }
  }, [refreshConversation]);
  // Re-read on a WebSocket reconnect, and when a stream of this conversation ends or errors.
  const resync = useConversationResync(conversationId, rereadMessages);

  /**
   * Load existing conversation for this workflow (does NOT create)
   * @param force - If true, bypasses the guard and forces reload
   */
  const loadConversation = useCallback(async (force = false) => {
    if (!workflowId) return;
    if (!force && hasLoadedRef.current === workflowId) return;

    try {
      setIsLoading(true);
      setError(null);
      hasLoadedRef.current = workflowId;

      // Find existing conversation for this workflow (does NOT create)
      const conversation = await conversationApi.findWorkflowConversation(workflowId);

      if (conversation?.id) {
        setConversationId(conversation.id);
        // The endpoint answers the full ConversationDto (pendingActions included); only its
        // client type is narrowed to what the id lookup needs.
        setConversation(conversation as Conversation);
        conversationIdRef.current = conversation.id;

        // Load existing messages
        const messagesData = await conversationApi.getRecentMessagesAsc(conversation.id);
        if (Array.isArray(messagesData)) {
          setMessages(messagesData);
        }

        // Check if there's an active stream for this conversation (reconnection)
        streaming.checkAndReconnect(conversation.id, {
          onStreamComplete: resync.onStreamComplete,
          onError: resync.onError,
        });
      }
      // If no conversation exists, that's fine - it will be created on first message
    } catch (err) {
      console.error('[useWorkflowChat] Error loading conversation:', err);
      setError(err instanceof Error ? err.message : 'Failed to load conversation');
      hasLoadedRef.current = null;
    } finally {
      setIsLoading(false);
    }
  }, [workflowId, streaming, resync]);

  // Auto-load conversation when workflowId changes
  useEffect(() => {
    if (autoLoad && workflowId && hasLoadedRef.current !== workflowId) {
      loadConversation();
    }
  }, [autoLoad, workflowId, loadConversation]);

  // Reset when workflowId changes
  useEffect(() => {
    if (workflowId !== hasLoadedRef.current) {
      setConversationId(null);
      setConversation(null);
      conversationIdRef.current = null;
      setMessages([]);
      setError(null);
    }
  }, [workflowId]);

  /**
   * Send a message in the workflow conversation (via Redis Pub/Sub streaming)
   * Creates the conversation on first message if it doesn't exist
   */
  const sendMessage = useCallback(async (content: string, _attachments?: unknown, _defaultSkillIds?: string[], opts?: { keepPendingActions?: boolean }) => {
    if (!content.trim()) return;
    if (!workflowId) {
      setError('No workflow ID available');
      return;
    }

    // The caller passes the typed SelectedModel pair; we just read id + provider
    // as two fields. Sending the bare id (never the "provider:" qualified form)
    // is essential - the backend's pricing lookup keys on (provider, id) and
    // the qualified form would miss every row, tripping the 402 budget gate.
    const rawModelId = resolvedModel.id;
    const currentProvider = resolvedModel.provider;

    // Get or create conversation (ensure only ONE conversation per workflow)
    let currentConversationId = conversationIdRef.current;
    if (!currentConversationId) {
      console.log('[useWorkflowChat] No conversation in ref, checking if one exists...');
      try {
        // First, try to find existing conversation for this workflow
        const existingConversation = await conversationApi.findWorkflowConversation(workflowId);
        if (existingConversation?.id) {
          console.log('[useWorkflowChat] Found existing conversation:', existingConversation.id);
          setConversationId(existingConversation.id);
          setConversation(existingConversation as Conversation);
          conversationIdRef.current = existingConversation.id;
          currentConversationId = existingConversation.id;
        } else {
          // No existing conversation, create one with workflow title
          console.log('[useWorkflowChat] No existing conversation, creating new one...');

          // Get workflow title if not provided
          let title = workflowTitle;
          if (!title) {
            try {
              const workflow = await orchestratorApi.getWorkflow(workflowId);
              title = workflow?.name;
              console.log('[useWorkflowChat] Fetched workflow title:', title);
            } catch (err) {
              console.warn('[useWorkflowChat] Could not fetch workflow title:', err);
            }
          }

          const newConversation = await conversationApi.createWorkflowConversation(workflowId, rawModelId, currentProvider, title);
          if (newConversation?.id) {
            setConversationId(newConversation.id);
            setConversation(newConversation as Conversation);
            conversationIdRef.current = newConversation.id;
            currentConversationId = newConversation.id;
            console.log('[useWorkflowChat] Created new conversation:', currentConversationId);
          }
        }
      } catch (err) {
        console.error('[useWorkflowChat] Failed to get/create conversation:', err);
        setError('Failed to get or create conversation');
        return;
      }
    }

    if (!currentConversationId) {
      console.error('[useWorkflowChat] No conversation available after creation');
      setError('No conversation available');
      return;
    }

    console.log('[useWorkflowChat] Sending message to conversation:', currentConversationId, 'with model:', resolvedModel);

    const userMessage: Message = {
      id: `temp-${Date.now()}`,
      conversationId: currentConversationId,
      role: 'user',
      content: content.trim(),
      model: rawModelId,
      timestamp: new Date().toISOString(),
    };

    // Add user message optimistically
    setMessages((prev) => [...prev, userMessage]);

    try {
      // Send via streaming context (uses Redis Pub/Sub backend)
      // IMPORTANT: Always use current model from UI, not the one stored in DB
      console.log('[useWorkflowChat] 📤 Calling streaming.sendMessage with conversationId:', currentConversationId, 'model:', model);
      await streaming.sendMessage(
        {
          message: content.trim(),
          model: rawModelId,  // raw id, provider passed separately below
          provider: currentProvider,  // Derive provider from current model
          conversationId: currentConversationId,
          history: messages.map(m => ({ role: m.role, content: m.content })),
          keepPendingActions: opts?.keepPendingActions,
        },
        {
          // Reload from DB so the reply keeps its tool activities once streaming ends.
          onStreamComplete: resync.onStreamComplete,
          onError: (err, erroredConvId) => {
            console.error('[useWorkflowChat] ❌ Stream error:', err);
            setError(err?.message || 'Stream error');
            resync.onError(err, erroredConvId);
          },
        }
      );
      console.log('[useWorkflowChat] 📤 streaming.sendMessage returned');
    } catch (err) {
      console.error('[useWorkflowChat] Error sending message:', err);
      setError(err instanceof Error ? err.message : 'Failed to send message');
    }
  }, [workflowId, resolvedModel.id, resolvedModel.provider, workflowTitle, messages, streaming, resync]);

  /**
   * Clear all messages (local only, doesn't delete from server)
   */
  const clearMessages = useCallback(() => {
    setMessages([]);
  }, []);

  /**
   * Stop current stream
   */
  const stopStream = useCallback(() => {
    if (conversationIdRef.current) {
      streaming.stopStream(conversationIdRef.current);
    }
  }, [streaming]);

  return {
    conversationId,
    conversation,
    messages,
    isLoading,
    error,
    sendMessage,
    loadConversation,
    clearMessages,
    stopStream,
  };
}

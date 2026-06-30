/**
 * Hook for managing workflow-specific chat conversations
 * Each workflow has its own persistent conversation via Redis Pub/Sub
 *
 * Note: Streaming state (isStreaming, streamingContent, toolActivities) is now
 * handled directly by ChatCore from StreamingContext for consistency with ChatPageV2.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { conversationApi, Message } from '@/lib/api/conversationApi';
import { orchestratorApi } from '@/lib/api';
import { useStreaming } from '@/contexts/StreamingContext';
import { SelectedModel, getEffectiveDefaultSelectedModel } from '@/hooks/useModels';

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
        conversationIdRef.current = conversation.id;

        // Load existing messages
        const messagesData = await conversationApi.getRecentMessagesAsc(conversation.id);
        if (Array.isArray(messagesData)) {
          setMessages(messagesData);
        }

        // Check if there's an active stream for this conversation (reconnection)
        streaming.checkAndReconnect(conversation.id, {
          onStreamComplete: async (convId) => {
            // Reload messages from DB to get the full message with tool calls
            try {
              const messagesData = await conversationApi.getRecentMessagesAsc(convId);
              if (Array.isArray(messagesData)) {
                setMessages(messagesData);
              }
            } catch (err) {
              console.error('[useWorkflowChat] Failed to reload messages on reconnect:', err);
            }
          },
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
  }, [workflowId, streaming]);

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
          onStreamComplete: async (convId) => {
            console.log('[useWorkflowChat] ✅ onStreamComplete - reloading messages from DB for tool calls');
            // Reload messages from DB to get the full message with tool calls
            // This ensures tool activities are preserved after streaming ends
            try {
              const messagesData = await conversationApi.getRecentMessagesAsc(convId);
              if (Array.isArray(messagesData)) {
                setMessages(messagesData);
                console.log('[useWorkflowChat] 📝 Messages reloaded from DB, count:', messagesData.length);
              }
            } catch (err) {
              console.error('[useWorkflowChat] Failed to reload messages:', err);
            }
          },
          onError: (err) => {
            console.error('[useWorkflowChat] ❌ Stream error:', err);
            setError(err?.message || 'Stream error');
          },
        }
      );
      console.log('[useWorkflowChat] 📤 streaming.sendMessage returned');
    } catch (err) {
      console.error('[useWorkflowChat] Error sending message:', err);
      setError(err instanceof Error ? err.message : 'Failed to send message');
    }
  }, [workflowId, resolvedModel.id, resolvedModel.provider, workflowTitle, messages, streaming]);

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
    messages,
    isLoading,
    error,
    sendMessage,
    loadConversation,
    clearMessages,
    stopStream,
  };
}

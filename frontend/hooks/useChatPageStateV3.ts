/**
 * Chat Page State Hook V3
 *
 * Simplified version that removes streaming state (now in StreamingContext).
 *
 * ARCHITECTURE:
 * - Synchronized state (useChatSync): sidebar, model, tools, conversations
 * - Messages (useConversationHistory): messages, loading, pagination
 * - Streaming (StreamingContext): isStreaming, content, status
 * - Local state (this hook): input, attachments, agent, UI only
 */

'use client';

import { useState, useRef, useEffect, useMemo, useCallback } from 'react';
import { useSearchParams } from 'next/navigation';
import { useUnifiedApp } from '@/contexts/UnifiedAppContext';
import { useStreaming } from '@/contexts/StreamingContext';
import { useConversationHistory, UseConversationHistoryReturn } from '@/hooks/useConversationHistory';
import { conversationLogger } from '@/lib/logger';
import { SelectedModel } from '@/hooks/useModels';

// Types
export interface AnalyzeBadge {
  id: string;
  type: 'data' | 'workflow';
  label: string;
}

export interface Attachment {
  id: string;
  file: File;
  type: 'image' | 'document' | 'other';
  preview?: string;
}

export interface SendError {
  message: string;
  retryable: boolean;
  onRetry?: () => void;
}

export interface ChatPageStateV3 {
  // ============== SYNCHRONIZED STATE (from useChatSync) ==============
  sidebarOpen: boolean;
  setSidebarOpen: (open: boolean) => void;
  sidebarCollapsed: boolean;
  setSidebarCollapsed: (collapsed: boolean) => void;
  selectedModel: SelectedModel;
  setSelectedModel: (model: SelectedModel) => void;
  reasoningEffort: string;
  setReasoningEffort: (effort: string) => void;
  selectedTools: string[];
  setSelectedTools: (tools: string[]) => void;
  mode: 'auto' | 'manual';
  setMode: (mode: 'auto' | 'manual') => void;
  showToolSelector: boolean;
  setShowToolSelector: (show: boolean) => void;
  showModelSelector: boolean;
  setShowModelSelector: (show: boolean) => void;
  toolSearchQuery: string;
  setToolSearchQuery: (query: string) => void;
  selectedCategory: string;
  setSelectedCategory: (category: string) => void;
  currentConversationId: string | null;
  setCurrentConversationId: (id: string | null) => void;

  // ============== CONVERSATION HISTORY ==============
  conversationHistory: UseConversationHistoryReturn;

  // ============== STREAMING (from StreamingContext) ==============
  isStreaming: boolean;
  streamingContent: string;
  streamingStatus: 'streaming' | 'completed' | 'stopped' | 'error' | 'idle';

  // ============== LOCAL UI STATE ==============
  showProfileView: boolean;
  setShowProfileView: (show: boolean) => void;
  showAgentConfigPanel: boolean;
  setShowAgentConfigPanel: (show: boolean) => void;
  agentConfigPanelWidth: number;
  setAgentConfigPanelWidth: (width: number) => void;

  // Input State
  inputValue: string;
  setInputValue: (value: string) => void;
  analyzeBadges: AnalyzeBadge[];
  setAnalyzeBadges: React.Dispatch<React.SetStateAction<AnalyzeBadge[]>>;

  // Scroll State
  showScrollToBottom: boolean;
  setShowScrollToBottom: (show: boolean) => void;
  messagesContainerRef: React.RefObject<HTMLDivElement>;

  // Error State
  sendError: SendError | null;
  setSendError: (error: SendError | null) => void;

  // Pending User Message (for new conversations)
  pendingUserMessage: { id: string; conversationId: string; role: 'user'; content: string; model: string; timestamp: string; attachments?: { storageId: string; type: string; fileName: string; mimeType: string }[] } | null;
  setPendingUserMessage: (message: { id: string; conversationId: string; role: 'user'; content: string; model: string; timestamp: string; attachments?: { storageId: string; type: string; fileName: string; mimeType: string }[] } | null) => void;

  // Attachment State
  attachments: Attachment[];
  setAttachments: React.Dispatch<React.SetStateAction<Attachment[]>>;
  showAttachmentMenu: boolean;
  setShowAttachmentMenu: (show: boolean) => void;

  // Workflow State
  showWorkflowSuggestions: boolean;
  setShowWorkflowSuggestions: (show: boolean) => void;

  // Agent State
  agentName: string | null;
  setAgentName: (name: string | null) => void;
  agentAvatarUrl: string | null;
  setAgentAvatarUrl: (url: string | null) => void;
  agentIdFromConversation: string | null;
  setAgentIdFromConversation: (id: string | null) => void;
  isLoadingAgent: boolean;
  setIsLoadingAgent: (loading: boolean) => void;

  // Computed State
  isConversationPage: boolean;
  conversationIdFromParams: string | null;
}

export interface UseChatPageStateOptions {
  conversationIdFromParams?: string;
  enableDataSource?: boolean;
}

/**
 * Unified hook for ChatPage state (V3 - uses StreamingContext).
 */
export function useChatPageStateV3(options: UseChatPageStateOptions = {}): ChatPageStateV3 {
  const { conversationIdFromParams } = options;
  const searchParams = useSearchParams();

  // ============== SYNCHRONIZED STATE ==============
  const chatSync = useUnifiedApp();
  const { currentConversationId: contextConversationId, isNavigatingToNewChat } = chatSync.state;
  const { setCurrentConversationId } = chatSync;

  // ============== STREAMING STATE (from StreamingContext) ==============
  const streaming = useStreaming();

  // ============== CONVERSATION HISTORY ==============
  const conversationHistory = useConversationHistory({ autoLoad: true });

  // ============== LOCAL UI STATE ==============
  const [showProfileView, setShowProfileView] = useState(false);
  const [showAgentConfigPanel, setShowAgentConfigPanel] = useState(false);
  const [agentConfigPanelWidth, setAgentConfigPanelWidth] = useState(0);

  // ============== INPUT STATE ==============
  const [inputValue, setInputValue] = useState('');
  const [analyzeBadges, setAnalyzeBadges] = useState<AnalyzeBadge[]>([]);

  // ============== SCROLL STATE ==============
  const [showScrollToBottom, setShowScrollToBottom] = useState(false);
  const messagesContainerRef = useRef<HTMLDivElement>(null);

  // ============== ERROR STATE ==============
  const [sendError, setSendError] = useState<SendError | null>(null);

  // ============== PENDING USER MESSAGE ==============
  const [pendingUserMessage, setPendingUserMessage] = useState<{
    id: string;
    conversationId: string;
    role: 'user';
    content: string;
    model: string;
    timestamp: string;
  } | null>(null);

  // ============== ATTACHMENT STATE ==============
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [showAttachmentMenu, setShowAttachmentMenu] = useState(false);

  // ============== WORKFLOW STATE ==============
  const [showWorkflowSuggestions, setShowWorkflowSuggestions] = useState(false);

  // ============== AGENT STATE ==============
  const [agentName, setAgentName] = useState<string | null>(null);
  const [agentAvatarUrl, setAgentAvatarUrl] = useState<string | null>(null);
  const [agentIdFromConversation, setAgentIdFromConversation] = useState<string | null>(null);
  const [isLoadingAgent, setIsLoadingAgent] = useState(false);

  // ============== COMPUTED STATE ==============
  const isConversationPage = useMemo(() => !!conversationIdFromParams, [conversationIdFromParams]);

  // ============== EFFECTS ==============

  // Sync conversation ID from params to context
  useEffect(() => {
    if (isNavigatingToNewChat) return;
    if (conversationIdFromParams && conversationIdFromParams !== contextConversationId) {
      setCurrentConversationId(conversationIdFromParams);
    }
  }, [conversationIdFromParams, contextConversationId, isNavigatingToNewChat, setCurrentConversationId]);

  // Track previous conversation ID to detect navigation transitions
  const previousConversationIdRef = useRef<string | null>(contextConversationId);

  // Ref for clearMessages to avoid unstable dep on conversationHistory object
  const clearMessagesRef = useRef(conversationHistory.clearMessages);
  clearMessagesRef.current = conversationHistory.clearMessages;

  // Clear messages when navigating from a conversation to new chat
  // IMPORTANT: Only clear when TRANSITIONING from an existing conversation to "new chat"
  // NOT when streaming ends (which was causing the bug where assistant messages disappeared)
  useEffect(() => {
    const previousId = previousConversationIdRef.current;

    // Only clear when we had a conversation ID and now we don't (user navigated to new chat)
    // Don't clear when:
    // - Going from null → ID (creating new conversation)
    // - Going from ID → same ID (no change)
    // - Going from ID → different ID (switching conversations)
    const shouldClear = previousId !== null &&
      contextConversationId === null;

    // Update ref for next comparison
    previousConversationIdRef.current = contextConversationId;

    if (shouldClear) {
      conversationLogger.info('Clearing messages - navigated from conversation to new chat');
      clearMessagesRef.current();
    }
  }, [contextConversationId]);

  // Parse query params for analyze badges
  useEffect(() => {
    const dataIds = searchParams?.get('dataIds');
    const workflowIds = searchParams?.get('workflowIds');

    if (dataIds || workflowIds) {
      const newBadges: AnalyzeBadge[] = [];

      if (dataIds) {
        dataIds.split(',').forEach((id, index) => {
          newBadges.push({
            id: `data-${id}-${index}`,
            type: 'data',
            label: `Data ${id}`
          });
        });
      }

      if (workflowIds) {
        workflowIds.split(',').forEach((id, index) => {
          newBadges.push({
            id: `workflow-${id}-${index}`,
            type: 'workflow',
            label: `Workflow ${id}`
          });
        });
      }

      if (newBadges.length > 0) {
        setAnalyzeBadges(newBadges);
      }
    }
  }, [searchParams]);

  // ============== RETURN UNIFIED STATE ==============
  return {
    // Synchronized State
    sidebarOpen: chatSync.state.sidebarOpen,
    setSidebarOpen: chatSync.setSidebarOpen,
    sidebarCollapsed: chatSync.state.sidebarCollapsed,
    setSidebarCollapsed: chatSync.setSidebarCollapsed,
    selectedModel: chatSync.state.selectedModel,
    setSelectedModel: chatSync.setSelectedModel,
    reasoningEffort: chatSync.state.reasoningEffort,
    setReasoningEffort: chatSync.setReasoningEffort,
    selectedTools: chatSync.state.selectedTools,
    setSelectedTools: chatSync.setSelectedTools,
    mode: chatSync.state.mode,
    setMode: chatSync.setMode,
    showToolSelector: chatSync.state.showToolSelector,
    setShowToolSelector: chatSync.setShowToolSelector,
    showModelSelector: chatSync.state.showModelSelector,
    setShowModelSelector: chatSync.setShowModelSelector,
    toolSearchQuery: chatSync.state.toolSearchQuery,
    setToolSearchQuery: chatSync.setToolSearchQuery,
    selectedCategory: chatSync.state.selectedCategory,
    setSelectedCategory: chatSync.setSelectedCategory,
    currentConversationId: chatSync.state.currentConversationId,
    setCurrentConversationId: chatSync.setCurrentConversationId,

    // Conversation History
    conversationHistory,

    // Streaming (from StreamingContext)
    isStreaming: streaming.isStreaming,
    streamingContent: streaming.state.content,
    streamingStatus: streaming.state.status,

    // Local UI State
    showProfileView,
    setShowProfileView,
    showAgentConfigPanel,
    setShowAgentConfigPanel,
    agentConfigPanelWidth,
    setAgentConfigPanelWidth,

    // Input State
    inputValue,
    setInputValue,
    analyzeBadges,
    setAnalyzeBadges,

    // Scroll State
    showScrollToBottom,
    setShowScrollToBottom,
    messagesContainerRef,

    // Error State
    sendError,
    setSendError,

    // Pending User Message
    pendingUserMessage,
    setPendingUserMessage,

    // Attachment State
    attachments,
    setAttachments,
    showAttachmentMenu,
    setShowAttachmentMenu,

    // Workflow State
    showWorkflowSuggestions,
    setShowWorkflowSuggestions,

    // Agent State
    agentName,
    setAgentName,
    agentAvatarUrl,
    setAgentAvatarUrl,
    agentIdFromConversation,
    setAgentIdFromConversation,
    isLoadingAgent,
    setIsLoadingAgent,

    // Computed State
    isConversationPage,
    conversationIdFromParams: conversationIdFromParams || null,
  };
}

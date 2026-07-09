'use client';

/**
 * ChatCore - Core chat component used by ChatPageV2 and WorkflowMessagesPanel
 *
 * This component contains the exact same chat logic as ChatPageV2:
 * - Streaming state management with shouldShowStreamingContent
 * - Message filtering to avoid duplicates
 * - Auto-scroll with user scroll detection
 * - MessageHistory and MessageComposer rendering
 */

import React, { useRef, useEffect, useCallback, useState, useMemo } from 'react';
import { Sparkles } from 'lucide-react';
import { MessageHistory } from '@/components/chat/MessageHistory';
import { MessageComposer } from '@/components/chat/MessageComposer';
import { ServiceApprovalCard } from '@/components/chat/ServiceApprovalCard';
import { ToolAuthorizationCard } from '@/components/chat/ToolAuthorizationCard';
import { useStreaming, serviceApprovalKey, toolAuthorizationKey, mergePendingServiceApprovals, type ToolActivity } from '@/contexts/StreamingContext';
import { orchestratorApi } from '@/lib/api';
import { conversationApi } from '@/lib/api/conversationApi';
import { useTranslations } from 'next-intl';
import { useToast } from '@/components/Toast';
import ToastContainer from '@/components/ToastContainer';
import type { Message, Conversation } from '@/lib/api/conversationApi';
import type { PendingServiceApproval, PendingToolAuthorization } from '@/contexts/StreamingContext';
import { parseUtcAware } from '@/lib/utils/dateFormatters';
import { useAnchorScrollToBottom } from '@/lib/hooks/useAnchorScrollToBottom';
import { useMessageQueueStore, type QueuedMessage } from '@/lib/stores/message-queue-store';
import { attachmentApi, type PendingAttachment } from '@/lib/api/attachmentApi';
import { useChatConfig } from '@/hooks/useChatConfig';
import { useAppRunAutoOpenStore } from '@/lib/stores/app-run-autoopen-store';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import AcquirePublicationModal from '@/components/marketplace/AcquirePublicationModal';

const EMPTY_QUEUE: QueuedMessage[] = [];

// Match MessageHistory's ChatMessage type for compatibility
interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
  toolsUsed?: string[];
}

// Re-export AttachmentRef for convenience
export type { AttachmentRef } from '@/lib/api/attachmentApi';
import type { AttachmentRef } from '@/lib/api/attachmentApi';

export interface ChatCoreProps {
  // Conversation state
  conversationId: string | null;
  conversation?: Conversation | null;
  messages: Message[] | ChatMessage[];
  isLoading?: boolean;

  // Message handling. `opts.keepPendingActions` marks a RESUME (after resolving ONE parallel
  // card) so the backend keeps the OTHER pending cards instead of wiping them at turn start.
  onSendMessage: (content?: string, attachments?: AttachmentRef[], defaultSkillIds?: string[], opts?: { keepPendingActions?: boolean }) => Promise<void> | void;
  onStopStream?: () => void;
  isStreamStarting?: boolean;

  // Optional customization
  className?: string;
  hideWorkflowToggle?: boolean;
  hideDataSourceToggle?: boolean;
  emptyStateMessage?: string;

  // Custom empty state content (e.g., WelcomeTitle + ActivityFeed)
  // When provided, replaces the default empty state
  emptyStateContent?: React.ReactNode;

  // Welcome-hero empty state (like the main /app/chat page): when true AND the
  // conversation is empty, the composer is centered in the messages area with an
  // optional title above it, instead of the small placeholder + bottom composer.
  // Once a conversation is active the composer docks at the bottom as usual.
  welcomeLayout?: boolean;
  // Title node rendered above the centered composer in the welcome layout.
  welcomeTitle?: React.ReactNode;

  // Workflow-specific (optional)
  workflowId?: string;

  // External input control (for suggestion prompts)
  externalInputValue?: string;
  onExternalInputConsumed?: () => void;

  // Additional tool activities (for manual executions)
  additionalToolActivities?: ToolActivity[];

  // Pagination (optional - for ChatPageV2)
  hasMoreMessages?: boolean;
  loadingOlderMessages?: boolean;
  onLoadOlderMessages?: () => void;

  // Workflow/DataSource actions (optional - for ChatPageV2)
  showWorkflowSuggestions?: boolean;
  onReplaceWorkflow?: (messageId: string, template: unknown) => void;
  onReplaceDataSource?: (messageId: string, dataSourceId: number) => void;
  onManualWorkflowMode?: () => void;
  onRunWorkflow?: (workflowId: string) => void;
  onDeleteVisualization?: (type: 'workflow' | 'application' | 'agent' | 'datasource' | 'interface', id: string) => void;

  // Control rendered in the composer, left of the mic (model selector / agent avatar).
  leadingControl?: React.ReactNode;
  // Agent linked to this conversation (conversations.agent_id), resolved by the caller.
  // Preferred over deriving it from `conversation` so the composer matches the header even
  // before the full conversation object has loaded (the caller can read a sidebar cache).
  linkedAgentId?: string | null;
}

export function ChatCore({
  conversationId,
  conversation,
  messages,
  isLoading = false,
  onSendMessage,
  onStopStream,
  isStreamStarting = false,
  className = '',
  hideWorkflowToggle = false,
  hideDataSourceToggle = false,
  emptyStateMessage,
  emptyStateContent,
  welcomeLayout = false,
  welcomeTitle,
  workflowId,
  externalInputValue,
  onExternalInputConsumed,
  additionalToolActivities = [],
  // Pagination props
  hasMoreMessages = false,
  loadingOlderMessages = false,
  onLoadOlderMessages,
  // Workflow/DataSource action props
  showWorkflowSuggestions = false,
  onReplaceWorkflow,
  onReplaceDataSource,
  onManualWorkflowMode,
  onRunWorkflow,
  onDeleteVisualization: onDeleteVisualizationProp,
  leadingControl,
  linkedAgentId,
}: ChatCoreProps) {
  const t = useTranslations();
  const streaming = useStreaming();
  const { toasts, addToast, removeToast } = useToast();
  const messagesContainerRef = useRef<HTMLDivElement>(null);

  // Input state
  const [inputValue, setInputValue] = useState('');
  const [showAttachmentMenu, setShowAttachmentMenu] = useState(false);

  // Handle external input (for suggestion prompts from canvas)
  useEffect(() => {
    if (externalInputValue) {
      setInputValue(externalInputValue);
      onExternalInputConsumed?.();
    }
  }, [externalInputValue, onExternalInputConsumed]);

  // Auto-scroll tracking (same as ChatPageV2 - see that file for the full rationale).
  const previousConversationIdRef = useRef<string | null>(null);
  const previousLastUserPendingIdRef = useRef<string | null>(null);
  const userScrolledUpRef = useRef(false);

  // Initial anchor - pin to the bottom on conversation switch with stabilization
  // against async-loading images / markdown / code blocks. Self-cancels on user input.
  //
  // Gated on `!isLoading && !loadingOlderMessages`: this matters for callers that
  // fetch messages asynchronously after mounting (e.g. ChatPanelContent calls
  // getMessages(cid) in a useEffect). Without this gate the hook would fire once
  // on the still-empty container, mark the conversationId as "seen" in the LRU,
  // and never re-fire when the messages eventually arrive - leaving the user at
  // the top of the conversation. Passing the messages length is what re-triggers
  // the hook's effect closure once content actually lands.
  const anchorReady = !!conversationId && !isLoading && !loadingOlderMessages && messages.length > 0;
  useAnchorScrollToBottom(messagesContainerRef, conversationId || null, anchorReady);

  // ============================================
  // STREAMING STATE - Exact same logic as ChatPageV2
  // ============================================

  // Check if streaming is for the current conversation
  const isStreamingThisConversation = conversationId
    ? streaming.isStreamingConversation(conversationId)
    : false;

  // Mirror the live streaming flag into a ref so async flows (Send-Now) can read the
  // CURRENT value instead of the one captured when the callback was created - see
  // handleSendNow, where polling this ref is what lets the resend wait for the stop to
  // land before going through doSendMessage's "already streaming" guard.
  const isStreamingThisConversationRef = useRef(isStreamingThisConversation);
  isStreamingThisConversationRef.current = isStreamingThisConversation;

  // Get stream state for current conversation
  const currentStreamState = conversationId
    ? streaming.getStreamState(conversationId)
    : null;

  // Get streaming content - show even after streaming ends (status 'completed')
  // This avoids the message disappearing before being added to messages[]
  const streamContent = conversationId
    ? streaming.getStreamContent(conversationId)
    : '';
  const streamStatus = currentStreamState?.status;

  // Get tool activities for current conversation
  const streamingToolActivities = conversationId
    ? streaming.getToolActivities(conversationId)
    : [];

  // Approval/authorization cards are ASYNC: the agent raises several in parallel without
  // pausing the run. Each layer below is a LIST. The merged lists prefer the live streaming
  // entries and fall back to the persisted conversation.pendingActions (after a refresh).

  // Track locally dismissed cards by canonical key (cleared on approve/deny so the card
  // disappears instantly without waiting for the async DB clear). Reset on conversation change.
  const [dismissedKeys, setDismissedKeys] = useState<Set<string>>(new Set());
  React.useEffect(() => { setDismissedKeys(new Set()); }, [conversationId]);

  // The persisted list, falling back to the legacy single pendingAction for older rows.
  const conversationPendingActions = useMemo(() => {
    if (conversation?.pendingActions && conversation.pendingActions.length > 0) {
      return conversation.pendingActions;
    }
    return conversation?.pendingAction ? [conversation.pendingAction] : [];
  }, [conversation]);

  // Streaming (realtime) lists
  const streamingServiceApprovals = conversationId
    ? streaming.getPendingServiceApprovals(conversationId)
    : [];
  const streamingToolAuthorizations = conversationId
    ? streaming.getPendingToolAuthorizations(conversationId)
    : [];

  // Service-approval cards: streaming ∪ persisted, deduped by key, dismissed dropped.
  const pendingServiceApprovals = useMemo((): PendingServiceApproval[] => {
    const byKey = new Map<string, PendingServiceApproval>();
    const add = (a: PendingServiceApproval) => {
      const key = serviceApprovalKey(a.services, a.needsAttention);
      if (dismissedKeys.has(key)) return;
      const existing = byKey.get(key);
      byKey.set(key, existing ? mergePendingServiceApprovals(existing, a) : a);
    };
    // Streaming first → wins on key collisions.
    streamingServiceApprovals.forEach(add);
    conversationPendingActions
      .filter(pa => pa.waiting_for === 'service_approval')
      .forEach(pa => add({
        services: (pa.services || [])
          .filter(s => s.iconSlug)
          .map(s => ({
            serviceType: s.serviceType,
            serviceName: s.serviceName,
            iconSlug: s.iconSlug!,
            toolName: s.toolName,
            description: s.description,
          })),
        reason: pa.reason,
        needsAttention: pa.needs_attention === true,
        timestamp: pa.created_at ? parseUtcAware(pa.created_at).getTime() : Date.now(),
      }));
    return Array.from(byKey.values());
  }, [streamingServiceApprovals, conversationPendingActions, dismissedKeys]);

  // Tool-authorization cards: streaming ∪ persisted, deduped by rule, dismissed dropped.
  const pendingToolAuthorizations = useMemo((): PendingToolAuthorization[] => {
    const byKey = new Map<string, PendingToolAuthorization>();
    const add = (a: PendingToolAuthorization) => {
      // Key by (rule, toolCallId) so a second card of the same rule isn't
      // suppressed by the first one's dismissal (F16). Falls back to rule-only
      // when toolCallId is absent (legacy/persisted rows).
      const key = toolAuthorizationKey(a.rule, a.toolCallId);
      if (!dismissedKeys.has(key) && !byKey.has(key)) byKey.set(key, a);
    };
    streamingToolAuthorizations.forEach(add);
    conversationPendingActions
      .filter(pa => pa.waiting_for === 'tool_authorization' && pa.rule)
      .forEach(pa => add({
        rule: pa.rule!,
        toolName: pa.tool_name,
        action: pa.action,
        toolCallId: pa.tool_call_id,
        argsSummary: pa.args_summary,
        applicationId: pa.application_id,
        timestamp: pa.created_at ? parseUtcAware(pa.created_at).getTime() : Date.now(),
      }));
    return Array.from(byKey.values());
  }, [streamingToolAuthorizations, conversationPendingActions, dismissedKeys]);


  // Keep streaming tool activities visible - they already have visualizations from streaming
  // No need to transition to historical mode (which would require DB fetch)
  // Also merge with additional tool activities (for manual executions)
  const toolActivities = [...streamingToolActivities, ...additionalToolActivities];

  // Show streaming section if we have a stream with data or additional tool activities
  // The stream data (with visualizations) stays visible until cleared
  const hasStreamingData = streamContent || streamingToolActivities.length > 0;
  const hasAdditionalActivities = additionalToolActivities.length > 0;
  const shouldShowStreamingContent = (hasStreamingData &&
    (streamStatus === 'streaming' || streamStatus === 'completed' || streamStatus === 'stopped'))
    || hasAdditionalActivities;

  // Get stream error for error banner
  const streamError = currentStreamState?.error;

  // Check if streaming content matches the last message (to avoid duplicates)
  // Use robust comparison that handles whitespace and thinking marker differences
  const lastMessage = messages[messages.length - 1];
  const isStreamContentDuplicate = useMemo(() => {
    if (lastMessage?.role !== 'assistant') return false;

    // Normalize content for comparison (trim and remove thinking markers)
    const normalizeContent = (content: string | undefined): string => {
      if (!content) return '';
      // Remove thinking markers like <thinking>...</thinking> and [thinking...]
      return content
        .replace(/<thinking>[\s\S]*?<\/thinking>/g, '')
        .replace(/\[thinking[^\]]*\]/g, '')
        .trim();
    };

    const normalizedStream = normalizeContent(streamContent);
    const normalizedMessage = normalizeContent(lastMessage.content);

    // Exact match after normalization
    if (normalizedStream === normalizedMessage) return true;

    // Also consider as duplicate if both have same tool activities (tools-only messages)
    // This catches cases where content is empty but tools are the same
    if (!normalizedStream && !normalizedMessage && streamingToolActivities.length > 0) {
      // Check if last message has tools (via toolCalls field)
      const messageWithToolCalls = lastMessage as { toolCalls?: string };
      if (messageWithToolCalls.toolCalls && messageWithToolCalls.toolCalls !== '[]') {
        return true;
      }
    }

    return false;
  }, [lastMessage, streamContent, streamingToolActivities.length]);

  // Filter messages to exclude duplicate when stream section is visible
  const filteredMessages = useMemo(() => {
    if (shouldShowStreamingContent && isStreamContentDuplicate) {
      // Don't show the last message in history - it's already shown in streaming section
      return messages.slice(0, -1);
    }
    return messages;
  }, [messages, shouldShowStreamingContent, isStreamContentDuplicate]);

  // ============================================
  // SCROLL MANAGEMENT - Exact same logic as ChatPageV2
  // ============================================

  // Scroll position tracking - threshold scales with viewport.
  // clamp(clientHeight * 0.1, 80, 200) is robust on mobile momentum scroll where
  // the previous hardcoded 50px was too tight and false-flagged a near-bottom user
  // as "scrolled up" after a single-finger flick.
  useEffect(() => {
    const container = messagesContainerRef.current;
    if (!container) return;

    const handleScroll = () => {
      const { scrollTop, scrollHeight, clientHeight } = container;
      const threshold = Math.min(200, Math.max(80, clientHeight * 0.1));
      const isAtBottom = scrollTop + clientHeight >= scrollHeight - threshold;
      userScrolledUpRef.current = !isAtBottom;
    };

    container.addEventListener('scroll', handleScroll);
    handleScroll();

    return () => container.removeEventListener('scroll', handleScroll);
  }, [conversationId, messages.length]);

  // Streaming + user-send auto-scroll. The initial anchor is handled by
  // useAnchorScrollToBottom above; this effect is only for new chunks/messages.
  //  - User-send: latest message has role==='user' AND pendingLocal===true
  //    AND a different id than the previous pending one → force scroll regardless
  //    of position. Distinguishes the optimistic local append from any other
  //    message that happens to have role==='user'.
  //  - Streaming chunk: only scroll if the user is already near the bottom.
  useEffect(() => {
    const container = messagesContainerRef.current;
    if (!container) return;

    const conversationChanged = previousConversationIdRef.current !== conversationId;
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

    previousConversationIdRef.current = conversationId;
  }, [conversationId, messages, isStreamingThisConversation, streamContent, toolActivities.length]);

  // ============================================
  // HANDLERS
  // ============================================

  // Stop stream handler
  const handleStopStream = useCallback(() => {
    if (onStopStream) {
      onStopStream();
    } else if (conversationId) {
      streaming.stopStream(conversationId);
    }
  }, [conversationId, onStopStream, streaming]);

  useEffect(() => {
    const canStopWithEscape = isStreamStarting || isStreamingThisConversation;
    if (!canStopWithEscape) return;

    const handleEscapeStop = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.repeat || event.defaultPrevented) return;
      event.preventDefault();
      handleStopStream();
    };

    document.addEventListener('keydown', handleEscapeStop);
    return () => document.removeEventListener('keydown', handleEscapeStop);
  }, [isStreamStarting, isStreamingThisConversation, handleStopStream]);

  // Mark a single card key as locally dismissed so it disappears instantly (before the async
  // DB clear lands) without touching the other parallel cards.
  const dismissKey = useCallback((key: string) => {
    setDismissedKeys(prev => {
      const next = new Set(prev);
      next.add(key);
      return next;
    });
  }, []);

  // Approval resume messages always enter the MessageComposer queue. If the
  // stream is still running, the queue waits; if it is idle, the existing
  // auto-drain sends it after the normal delay.
  const enqueueApprovalResume = useCallback((message: string) => {
    if (!conversationId) return;
    const queued = useMessageQueueStore.getState().enqueue(conversationId, {
      content: message,
      attachments: [],
      keepPendingActions: true, // resume - keep any other still-pending cards
    }, {
      bypassLimit: true,
      position: 'front',
    });
    if (!queued) {
      addToast({ type: 'error', title: t('chat.queue.full'), message, duration: 5000 });
    }
  }, [conversationId, addToast, t]);

  // Service approval handlers - non-blocking, dismiss THIS card + clear its DB row + queue resume.
  const handleServiceApproved = useCallback((serviceNames: string[], key: string) => {
    if (conversationId) {
      dismissKey(key);
      streaming.clearServiceApproval(conversationId, key);
      conversationApi.clearPendingAction(conversationId, key).catch(() => {});

      // Show success toast
      addToast({
        type: 'success',
        title: t('credentials.connected'),
        message: t('credentials.toasts.credentialConfigured'),
        duration: 5000,
      });

      // Resend a message so the LLM continues with the newly configured credentials.
      // Always queue it through the composer so approvals do not interrupt an
      // in-flight turn; the queue auto-drains when the conversation is idle.
      const names = serviceNames.join(', ');
      const resumeMessage = t('credentials.toasts.credentialConfiguredResume', { services: names });
      enqueueApprovalResume(resumeMessage);
    }
  }, [conversationId, streaming, dismissKey, addToast, t, enqueueApprovalResume]);

  const handleServiceDenied = useCallback((serviceNames: string[], key: string) => {
    if (conversationId) {
      dismissKey(key);
      streaming.clearServiceApproval(conversationId, key);
      conversationApi.clearPendingAction(conversationId, key).catch(() => {});
    }
  }, [conversationId, streaming, dismissKey]);

  // The marketplace install modal opened from an `application:acquire` authorization:
  // the USER installs the app directly (not the agent). `installSucceededRef` distinguishes
  // a successful install (agent continues, no deny) from a plain cancel (deny).
  const [installState, setInstallState] = useState<{ publication: WorkflowPublication; rule: string } | null>(null);
  const installSucceededRef = useRef(false);

  // Conversation-scoped chat config - used to flip the "ne plus demander" blanket toggle.
  // Shares the react-query cache with the composer's panel (same conversation key), so this
  // does not add a second fetch.
  const { updateConfig: updateChatConfig } = useChatConfig({ conversationId: conversationId ?? null });

  // Resume the agent turn with a contextual message in the composer queue so it
  // continues knowing the action was authorized / done without interrupting an in-flight turn.
  const resumeAgent = useCallback((message: string) => {
    enqueueApprovalResume(message);
  }, [enqueueApprovalResume]);

  // Tool authorization - approve. execute/agent/catalog resume the agent (it performs the
  // now-authorized action server-side); application:acquire instead lets the USER install via
  // the marketplace modal. `blanket` (card checkbox) flips the per-conversation auto-authorize.
  const handleToolAuthorized = useCallback(async (rule: string, blanket: boolean, toolCallId?: string) => {
    if (!conversationId) return;
    // Dismiss/clear THIS specific card by its (rule, toolCallId) key so a sibling
    // card of the same rule keeps showing (F16).
    const key = toolAuthorizationKey(rule, toolCallId);
    const applicationId = pendingToolAuthorizations.find(
      a => a.rule === rule && a.toolCallId === toolCallId)?.applicationId;
    dismissKey(key);
    streaming.clearToolAuthorization(conversationId, key);

    if (blanket) {
      // "Ne plus demander dans cette conversation" → persist; backend turns this into a
      // "*" wildcard so the gate stops firing for the rest of the conversation.
      updateChatConfig({ autoAuthorizeTools: true });
    }

    // application:acquire → open the marketplace install modal; resume happens on success.
    if (rule === 'application:acquire' && applicationId) {
      try {
        const publication = await publicationService.getPublicationById(applicationId);
        installSucceededRef.current = false;
        setInstallState({ publication: publication as WorkflowPublication, rule });
        return;
      } catch {
        // Couldn't load the publication - fall through and let the agent acquire it itself.
      }
    }

    // Grant once so the resume turn's now-authorized call passes the gate without re-prompting.
    try {
      await conversationApi.approveToolAuthorization(conversationId, rule, false);
    } catch {
      // Non-fatal: the backend still has the pending action; the user can retry.
    }
    if (rule === 'application:execute') {
      // The user asked that approving an execute opens the right side panel - arm the
      // one-shot consumed by the freshly-executed application card.
      useAppRunAutoOpenStore.getState().arm();
    }
    resumeAgent(t('toolAuthorization.resumeContinue'));
  }, [conversationId, pendingToolAuthorizations, streaming, dismissKey, updateChatConfig, resumeAgent, t]);

  const handleToolDenied = useCallback((rule: string, toolCallId?: string) => {
    if (!conversationId) return;
    const key = toolAuthorizationKey(rule, toolCallId);
    dismissKey(key);
    streaming.clearToolAuthorization(conversationId, key);
    // Disarm any pending auto-open so a declined turn can't open a later card.
    useAppRunAutoOpenStore.getState().clear();
    // No resume - the agent stops and the user takes over.
    conversationApi.denyToolAuthorization(conversationId, rule).catch(() => {});
  }, [conversationId, streaming, dismissKey]);

  // Install modal completed → grant once (a stray agent re-acquire is a benign 409) and
  // resume the agent telling it the app is installed.
  const handleInstallSuccess = useCallback(async () => {
    installSucceededRef.current = true;
    const rule = installState?.rule;
    if (!conversationId || !rule) return;
    try {
      await conversationApi.approveToolAuthorization(conversationId, rule, false);
    } catch {
      // Non-fatal.
    }
    resumeAgent(t('toolAuthorization.resumeInstalled'));
  }, [conversationId, installState, resumeAgent, t]);

  // Install modal closed. If the install already succeeded, just dismiss; otherwise the user
  // cancelled before installing → treat as a decline so the agent stops.
  const handleInstallClose = useCallback(() => {
    const rule = installState?.rule;
    const succeeded = installSucceededRef.current;
    installSucceededRef.current = false;
    setInstallState(null);
    if (!conversationId || !rule) return;
    if (!succeeded) {
      conversationApi.denyToolAuthorization(conversationId, rule).catch(() => {});
    }
  }, [conversationId, installState]);

  // Send message handler. `opts.keepPendingActions` (a RESUME after resolving ONE parallel
  // card) skips the dismiss-all so the OTHER still-pending cards survive, and is forwarded so
  // the backend skips its start-of-turn wipe too.
  const handleSendMessage = useCallback(async (content?: string, attachments?: AttachmentRef[], defaultSkillIds?: string[], opts?: { keepPendingActions?: boolean }) => {
    const messageContent = (content || inputValue).trim();
    // Allow sending if there's content OR attachments
    if (!messageContent && (!attachments || attachments.length === 0)) return;

    // If any approval/authorization cards are displayed and the user types a FRESH message,
    // treat that as an implicit dismissal: clear ALL pending cards (both kinds) so they
    // disappear and the agent isn't confused on the next turn. SKIP this for a resume -
    // the user is resolving one card, the others must stay.
    // AWAIT the DELETE before sending - otherwise the DELETE can race past the
    // next turn's pendingAction write and wipe a freshly-persisted approval.
    if (!opts?.keepPendingActions
        && (pendingServiceApprovals.length > 0 || pendingToolAuthorizations.length > 0) && conversationId) {
      pendingServiceApprovals.forEach(a => dismissKey(serviceApprovalKey(a.services, a.needsAttention)));
      pendingToolAuthorizations.forEach(a => dismissKey(toolAuthorizationKey(a.rule, a.toolCallId)));
      streaming.clearServiceApproval(conversationId);
      streaming.clearToolAuthorization(conversationId);
      try {
        await conversationApi.clearPendingAction(conversationId);
      } catch {
        // Non-fatal: stale pendingAction in DB will be reconciled by the next turn.
      }
    }

    setInputValue('');
    await onSendMessage(messageContent || undefined, attachments, defaultSkillIds, opts);
  }, [inputValue, onSendMessage, pendingServiceApprovals, pendingToolAuthorizations, conversationId, streaming, dismissKey]);

  // Key press handler
  const handleKeyPress = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  }, [handleSendMessage]);

  // Delete visualization handler - use prop if provided, otherwise default implementation
  const handleDeleteVisualization = useCallback(async (type: 'workflow' | 'application' | 'agent' | 'datasource' | 'interface', id: string) => {
    if (onDeleteVisualizationProp) {
      return onDeleteVisualizationProp(type, id);
    }
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
    } catch (error) {
      // Silently fail - user will see the item is still there
    }
  }, [onDeleteVisualizationProp]);

  // Run workflow handler - use prop if provided
  const handleRunWorkflow = useCallback((wfId: string) => {
    if (onRunWorkflow) {
      onRunWorkflow(wfId);
    }
    // If no handler provided, do nothing (workflow ID is captured in component state if needed)
  }, [onRunWorkflow]);

  // ============================================
  // MESSAGE QUEUE
  // ============================================

  const convIdForQueue = conversationId ?? '';
  const queue = useMessageQueueStore((s) => s.queues[convIdForQueue]?.items) ?? EMPTY_QUEUE;
  const shouldEnqueue = isStreamingThisConversation && !!conversationId;
  const prevStreamingRef = useRef(false);
  const autoSendingRef = useRef(false);

  // A completed/stopped stream whose assistant reply is still shown live (streamContent) but
  // has NOT yet landed in messages[] (isStreamContentDuplicate === false). The client 'completed'
  // event flips isStreamingConversation to false BEFORE onStreamComplete's loadMessages commits
  // the reply, so draining the queue in this window would append the queued user message -
  // timestamped now - ahead of the prior reply, rendering/persisting them out of order (the
  // reported bug: queued user message, then the previous answer, then the new answer). Mirrored
  // into a ref so the async drain below can poll the LIVE value, mirroring how handleSendNow
  // polls isStreamingThisConversationRef for the stop to land. Gated on hasStreamingData so a
  // stopped turn with no assistant output (nothing to commit) is never blocked.
  // NOTE: this gate governs WHEN to drain (don't dequeue before the reply is even visible).
  // Final ORDER is now decided by sortMessagesByTime, which keys on the server-monotonic
  // `createdAt` and anchors the optimistic (no-createdAt) message last - so a drained message
  // can no longer sort ahead of the prior reply under client/server clock skew (the residual
  // reorder this gate alone could only shrink, not erase).
  const priorReplyPendingCommit =
    (streamStatus === 'completed' || streamStatus === 'stopped') &&
    !!hasStreamingData && !isStreamContentDuplicate;
  const priorReplyPendingCommitRef = useRef(priorReplyPendingCommit);
  priorReplyPendingCommitRef.current = priorReplyPendingCommit;

  const handleEnqueueMessage = useCallback((content: string, attachments: PendingAttachment[], defaultSkillIds?: string[]) => {
    if (!conversationId) return;
    useMessageQueueStore.getState().enqueue(conversationId, { content, attachments, defaultSkillIds });
  }, [conversationId]);

  const handleRemoveFromQueue = useCallback((messageId: string) => {
    if (!conversationId) return;
    const store = useMessageQueueStore.getState();
    const queue = store.getQueue(conversationId);
    const msg = queue.find((m) => m.id === messageId);
    if (msg) {
      msg.attachments.forEach((a) => {
        if (a.preview) attachmentApi.revokePreviewUrl(a.preview);
      });
    }
    store.remove(conversationId, messageId);
  }, [conversationId]);

  const handleEditQueuedMessage = useCallback((messageId: string, content: string) => {
    if (!conversationId) return;
    useMessageQueueStore.getState().updateContent(conversationId, messageId, content);
  }, [conversationId]);

  const handleReorderQueue = useCallback((fromIndex: number, toIndex: number) => {
    if (!conversationId) return;
    useMessageQueueStore.getState().reorder(conversationId, fromIndex, toIndex);
  }, [conversationId]);

  const uploadQueuedAttachments = useCallback(async (attachments: PendingAttachment[]): Promise<AttachmentRef[]> => {
    const refs: AttachmentRef[] = [];
    for (const att of attachments) {
      const response = await attachmentApi.uploadAttachment(att.file);
      refs.push({
        storageId: response.storageId,
        type: att.type,
        fileName: att.file.name,
        mimeType: att.mimeType,
      });
    }
    return refs;
  }, []);

  const autoSendQueuedMessage = useCallback(async (msg: { content: string; attachments: PendingAttachment[]; defaultSkillIds?: string[]; keepPendingActions?: boolean }) => {
    try {
      const refs = await uploadQueuedAttachments(msg.attachments);
      await handleSendMessage(msg.content, refs.length > 0 ? refs : undefined, msg.defaultSkillIds,
        msg.keepPendingActions ? { keepPendingActions: true } : undefined);
    } catch (error) {
      console.error('Failed to auto-send queued message:', error);
      addToast({
        type: 'error',
        title: t('chat.genericError'),
        message: error instanceof Error ? error.message : String(error),
        duration: 5000,
      });
    }
  }, [uploadQueuedAttachments, handleSendMessage, addToast, t]);

  // Always points at the LATEST autoSendQueuedMessage (and therefore the latest
  // handleSendMessage → onSendMessage → doSendMessage chain). Send-Now must resend
  // through this ref, never through a directly-captured handleSendMessage: that capture
  // would still see the pre-stop "streaming" state and trip doSendMessage's silent
  // "already streaming" guard, dropping the message (stream stopped, nothing relaunched).
  const autoSendQueuedMessageRef = useRef(autoSendQueuedMessage);
  autoSendQueuedMessageRef.current = autoSendQueuedMessage;

  const handleSendNow = useCallback(async (messageId: string) => {
    if (!conversationId) return;
    // Hold off the background auto-drain so it can't also fire while we stop + wait. The
    // message stays IN the queue (visible) until we're actually ready to send it.
    autoSendingRef.current = true;
    try {
      if (isStreamingThisConversationRef.current) {
        // Stop the in-flight turn, then WAIT until the stream is actually no longer active
        // before resending. A fixed delay is unreliable (the bridge backend stop lags by
        // up to its cancel-poll interval); poll the LIVE ref with a bounded timeout.
        // Reading the ref - not the value captured when this callback was built - is what
        // lets us observe the stop landing.
        handleStopStream();
        const deadline = Date.now() + 2000;
        while (isStreamingThisConversationRef.current && Date.now() < deadline) {
          await new Promise((r) => setTimeout(r, 50));
        }
        // Stop still hasn't landed within the window - do NOT pull the message out into a
        // send that doSendMessage would silently bail (its "already streaming" guard),
        // which would lose it. Leave it queued; the background auto-drain relaunches it
        // once the stream actually ends.
        if (isStreamingThisConversationRef.current) return;
      }
      // Extract only now that the stream is idle, and resend through the always-fresh ref
      // chain (see autoSendQueuedMessageRef above) so doSendMessage sees the stopped state.
      const msg = useMessageQueueStore.getState().extractForSendNow(conversationId, messageId);
      if (!msg) return;
      await autoSendQueuedMessageRef.current(msg);
    } finally {
      autoSendingRef.current = false;
    }
  }, [conversationId, handleStopStream]);

  const prevConvIdForQueueRef = useRef(conversationId);
  const autoSendTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    const conversationChanged = prevConvIdForQueueRef.current !== conversationId;
    if (conversationChanged) {
      prevConvIdForQueueRef.current = conversationId;
    }
    const streamHadError = currentStreamState?.status === 'error';
    const streamJustStopped = !conversationChanged && prevStreamingRef.current && !isStreamingThisConversation;
    const hasQueuedMessages = !!conversationId && queue.length > 0;
    if ((streamJustStopped || hasQueuedMessages) && !isStreamingThisConversation && !autoSendingRef.current && !streamHadError) {
      if (autoSendTimerRef.current) clearTimeout(autoSendTimerRef.current);
      const convId = conversationId;
      autoSendTimerRef.current = setTimeout(async () => {
        if (!convId || autoSendingRef.current) return;
        if (streaming.isStreamingConversation(convId)) return;
        // Claim the drain up-front so a re-run of this effect can't start a second overlapping
        // drain while we await the commit below (the effect's `!autoSendingRef.current` guard).
        autoSendingRef.current = true;
        try {
          // The ordering gate applies ONLY to a regular queued user message: wait - bounded -
          // for the prior turn's assistant reply to land in messages[] before dequeuing, so the
          // message can never render/persist ahead of it. A credential-resume (keepPendingActions)
          // continues the SAME turn and must drain promptly, so it skips the wait - otherwise the
          // leftover stream content would block it. The 3s fallback guarantees a reply that never
          // commits (e.g. a stopped partial that is never persisted) can't strand the queue. Poll
          // cadence mirrors handleSendNow.
          const head = useMessageQueueStore.getState().getQueue(convId)[0];
          if (head && !head.keepPendingActions) {
            const deadline = Date.now() + 3000;
            while (priorReplyPendingCommitRef.current && Date.now() < deadline) {
              await new Promise((r) => setTimeout(r, 100));
            }
          }
          // A fresh turn must not have started while we waited (e.g. Send-Now relaunch).
          if (streaming.isStreamingConversation(convId)) return;
          const next = useMessageQueueStore.getState().dequeue(convId);
          if (next) {
            await autoSendQueuedMessageRef.current(next);
          }
        } finally {
          autoSendingRef.current = false;
        }
      }, 300);
    }
    prevStreamingRef.current = isStreamingThisConversation;
  }, [isStreamingThisConversation, conversationId, currentStreamState?.status, streaming, queue.length]);

  // ============================================
  // RENDER CONDITIONS - Same logic as ChatPageV2
  // ============================================

  // When we have a conversationId, we're loading an existing conversation
  // Don't show empty state during the race condition between isLoading=false and messages arriving
  const showEmptyState = !conversationId && messages.length === 0 && !isStreamingThisConversation && !isLoading;
  const shouldRenderHistory = conversationId || messages.length > 0 || isStreamingThisConversation || isLoading;

  // Welcome-hero layout is active only on the empty state; when it is, the composer
  // is centered in the messages area (below) and NOT docked at the bottom. An
  // explicit custom `emptyStateContent` takes precedence over the welcome hero.
  const showWelcomeLayout = welcomeLayout && showEmptyState && !emptyStateContent;

  // Composer is built once and placed either centered (welcome layout) or docked
  // at the bottom (normal layout). Only one instance is mounted at a time.
  const composer = (
    <MessageComposer
      inputValue={inputValue}
      onInputChange={setInputValue}
      onSendMessage={handleSendMessage}
      onKeyPress={handleKeyPress}
      isStreaming={isStreamingThisConversation}
      isStreamStarting={isStreamStarting}
      onStopStream={handleStopStream}
      showAttachmentMenu={showAttachmentMenu}
      onShowAttachmentMenu={setShowAttachmentMenu}
      fullWidth={true}
      conversationId={conversationId ?? undefined}
      queuedMessages={queue}
      shouldEnqueue={shouldEnqueue}
      onEnqueueMessage={handleEnqueueMessage}
      onRemoveQueuedMessage={handleRemoveFromQueue}
      onEditQueuedMessage={handleEditQueuedMessage}
      onSendNow={handleSendNow}
      onReorderQueue={handleReorderQueue}
      leadingControl={leadingControl}
      linkedAgentId={linkedAgentId ?? conversation?.agentId ?? null}
    />
  );

  return (
    <div className={`flex flex-col h-full min-h-0 overflow-hidden ${className}`}>
      {/* Messages area */}
      <div
        ref={messagesContainerRef}
        className="flex-1 overflow-y-auto py-4 space-y-4 min-h-0 chat-messages-container relative"
      >
        {/* Stream error banner */}
        {streamError && (
          <div className="sticky top-0 z-20 mx-auto max-w-4xl px-2 w-full">
            <div className="rounded-[18px] flex items-center justify-center py-2 px-4 mb-4 bg-red-50 dark:bg-red-900/20 shadow-sm">
              <div className="flex items-center space-x-2 text-red-700 dark:text-red-300">
                <div className="w-2 h-2 bg-red-500 rounded-full animate-pulse"></div>
                <span className="text-sm">
                  Stream error: {streamError.message}
                </span>
                {streamError.retryable && (
                  <button
                    onClick={() => conversationId && streaming.checkAndReconnect(conversationId)}
                    className="text-xs px-2 py-1 bg-red-100 dark:bg-red-800 text-red-700 dark:text-red-200 rounded hover:bg-red-200 dark:hover:bg-red-700 transition-colors"
                  >
                    Retry
                  </button>
                )}
              </div>
            </div>
          </div>
        )}

        <div className={`mx-auto max-w-4xl w-full px-2${showWelcomeLayout ? ' h-full' : ''}`}>
          {showWelcomeLayout ? (
            // Welcome hero: centered composer with an optional title above it,
            // mirroring the main /app/chat empty-state layout.
            <div className="flex flex-col justify-center h-full">
              {welcomeTitle && (
                <div className="text-center max-w-md mx-auto mb-8">{welcomeTitle}</div>
              )}
              <div className="w-full relative">
                <div className="absolute inset-x-4 -top-4 h-4 rounded-t-[32px] bg-gradient-to-r from-transparent via-white/80 to-transparent blur-xl opacity-40 dark:from-transparent dark:via-white/10 dark:to-transparent" />
                <div className="relative flex items-end justify-center">
                  <div className="max-w-3xl w-full">
                    {composer}
                  </div>
                </div>
              </div>
            </div>
          ) : showEmptyState ? (
            emptyStateContent ? (
              // Custom empty state content (e.g., welcome message with ActivityFeed)
              <div className="h-full">{emptyStateContent}</div>
            ) : (
              // Default empty state
              <div className="flex flex-col items-center justify-center h-full text-center text-theme-secondary px-4 pt-20">
                <Sparkles className="w-12 h-12 mb-4 opacity-50" />
                <p className="text-sm">{emptyStateMessage || t('chat.placeholder')}</p>
              </div>
            )
          ) : shouldRenderHistory ? (
            <>
              <MessageHistory
                messages={filteredMessages}
                loading={isLoading}
                scrollContainerRef={messagesContainerRef}
                // Show streaming content even after 'completed' status, but not if already in messages
                streamingMessage={shouldShowStreamingContent ? streamContent : undefined}
                // isStreaming controls loading indicators - only true when ACTIVELY streaming
                isStreaming={isStreamingThisConversation}
                // Tool activities for the activity feed (streaming only)
                toolActivities={shouldShowStreamingContent ? toolActivities : []}
                // Show "Awaiting approval" on tool cards when a ServiceApprovalCard is visible
                awaitingApproval={pendingServiceApprovals.length > 0}
                hideWorkflowToggle={hideWorkflowToggle}
                hideDataSourceToggle={hideDataSourceToggle}
                onDeleteVisualization={handleDeleteVisualization}
                onRunWorkflow={handleRunWorkflow}
                // Pagination props
                hasMoreMessages={hasMoreMessages}
                loadingOlderMessages={loadingOlderMessages}
                onLoadOlderMessages={onLoadOlderMessages}
                // Workflow/DataSource action props
                showWorkflowSuggestions={showWorkflowSuggestions}
                onReplaceWorkflow={onReplaceWorkflow}
                onReplaceDataSource={onReplaceDataSource}
                onManualWorkflowMode={onManualWorkflowMode}
                compactionMarker={conversation?.compactionMarker ?? null}
              />
              {/* Service approval cards - one per pending credential request (async, parallel). */}
              {conversationId && pendingServiceApprovals.map((approval) => {
                const key = serviceApprovalKey(approval.services, approval.needsAttention);
                return (
                  <ServiceApprovalCard
                    key={key}
                    conversationId={conversationId}
                    pendingApproval={approval}
                    onApproved={(names) => handleServiceApproved(names, key)}
                    onDenied={(names) => handleServiceDenied(names, key)}
                  />
                );
              })}
              {/* Tool authorization cards - one per gated sensitive action (async, parallel). */}
              {conversationId && pendingToolAuthorizations.map((authz) => (
                <ToolAuthorizationCard
                  key={toolAuthorizationKey(authz.rule, authz.toolCallId)}
                  conversationId={conversationId}
                  pendingAuthorization={authz}
                  onApproved={handleToolAuthorized}
                  onDenied={handleToolDenied}
                />
              ))}
              {/* Marketplace install modal - opened when the user approves an application:acquire */}
              {installState && (
                <AcquirePublicationModal
                  isOpen={true}
                  publication={installState.publication}
                  onClose={handleInstallClose}
                  onSuccess={handleInstallSuccess}
                />
              )}
            </>
          ) : null}
        </div>
      </div>

      {/* Message composer - centered with max width to match messages. Hidden in
          the welcome layout, where the composer is rendered centered above. */}
      {!showWelcomeLayout && (
        <div className="flex-shrink-0 mx-auto max-w-4xl w-full">
          {composer}
        </div>
      )}

      {/* Toast notifications - lives at ChatCore level so toasts survive ServiceApprovalCard unmount */}
      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
  );
}

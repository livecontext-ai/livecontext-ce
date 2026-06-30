'use client';

import React, { useEffect, useRef, useState, useCallback } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { useTranslations } from 'next-intl';
import { useIsomorphicLayoutEffect } from '@/lib/hooks/useIsomorphicLayoutEffect';
import { Message, CompactionMarker } from '@/lib/api/conversationApi';
import LoadingSpinner from '@/components/LoadingSpinner';
import MarkdownRender from '@/components/MarkdownRender';
import { useCurrentView } from '@/hooks/useCurrentView';
import { getLastThinkingMessage, parseThinkingMarker } from '@/components/chat/ThinkingDots';
import { isWorkflowMessage } from '@/components/chat/workflowUtils';
import { WorkflowSuggestions } from '@/components/chat/WorkflowSuggestions';
import { MessageSkeleton } from '@/components/chat/MessageSkeleton';
import DataSourceDisplayMode from '@/components/chat/DataSourceDisplayMode';
import { isDataSourceMessage } from '@/components/chat/DataSourceMessage';
import { ActivityFeed, type ToolActivity } from '@/components/chat/ActivityFeed';
import { parseToolActivitiesFromMessage } from '@/lib/chat/messageActivity';
import { attachmentApi } from '@/lib/api/attachmentApi';
import { AuthenticatedImage } from '@/components/chat/AuthenticatedImage';
import { ImageLightbox } from '@/components/chat/ImageLightbox';
import { AvatarDisplay } from '@/components/agents/AvatarPicker';
import { apiClient } from '@/lib/api/api-client';
import {
  User,
  Bot,
  AlertTriangle,
  Image as ImageIcon,
  FileText,
  Paperclip,
  Copy,
  Check,
  Archive,
  Maximize2,
} from 'lucide-react';
import { MessageActions } from '@/components/chat/MessageActions';
import { formatUtcDateTime, formatUtcTime, parseUtcAware } from '@/lib/utils/dateFormatters';

/**
 * Header-authenticated download of a non-image attachment: fetch with the bearer
 * token, then trigger a save from an in-memory blob URL. The session token is never
 * placed in a URL (same posture as AuthenticatedImage).
 */
async function downloadAuthenticatedAttachment(url: string, fileName: string): Promise<void> {
  try {
    const tokenProvider = apiClient.getTokenProvider();
    const token = tokenProvider ? await tokenProvider() : null;
    const res = await fetch(url, {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    });
    if (!res.ok) return;
    const blob = await res.blob();
    const objectUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = objectUrl;
    a.download = fileName;
    a.click();
    // Revoke on the next tick - revoking synchronously can race the click-initiated load.
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
  } catch {
    // Silent - a failed download leaves the chat untouched.
  }
}

/** Minimal copy button shown on hover under user messages, with send time next to it */
function UserCopyButton({ content, timeLabel }: { content: string; timeLabel?: string }) {
  const [copied, setCopied] = useState(false);
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(content);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = content;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="flex justify-end items-center gap-1.5 mt-1 opacity-0 group-hover:opacity-100 transition-opacity duration-150">
      <button
        type="button"
        onClick={handleCopy}
        className="p-1 rounded-md text-theme-secondary hover:text-theme-primary hover:bg-theme-secondary/40 transition-colors"
        title={copied ? 'Copied' : 'Copy'}
      >
        {copied ? <Check className="h-3.5 w-3.5 text-green-500" /> : <Copy className="h-3.5 w-3.5" />}
      </button>
      {timeLabel && (
        <span className="text-xs text-theme-secondary tabular-nums">{timeLabel}</span>
      )}
    </div>
  );
}

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
  toolsUsed?: string[];
}

interface MessageHistoryProps {
  messages: Message[] | ChatMessage[];
  loading?: boolean;
  error?: string | null;
  className?: string;
  hasMoreMessages?: boolean;
  loadingOlderMessages?: boolean;
  onLoadOlderMessages?: () => void;
  showThinkingMessage?: boolean; // Nouveau prop pour contrôler l'affichage du message "Thinking"
  scrollContainerRef?: React.RefObject<HTMLDivElement>; // Reference au conteneur de scroll parent
  streamingMessage?: string; // Message en cours de streaming
  isStreaming?: boolean; // Indique si un message est en cours de streaming
  streamingCounter?: number; // Compteur pour forcer le re-render
  toolActivities?: ToolActivity[]; // Tool call activities during streaming
  awaitingApproval?: boolean; // Show "Awaiting approval" status on tool cards instead of "Done"
  onReplaceWorkflow?: (messageId: string, template: any) => void; // Callback pour remplacer un workflow
  hideWorkflowToggle?: boolean; // Hide toggle when in panel
  onReplaceDataSource?: (messageId: string, dataSourceId: number) => void; // Callback pour remplacer un datasource
  onManualDataSourceMode?: () => void; // Callback pour passer en mode manuel
  hideDataSourceToggle?: boolean; // Hide toggle when in panel
  showWorkflowSuggestions?: boolean; // Show workflow suggestions without creating a message
  onManualWorkflowMode?: () => void; // Callback to create workflow message and redirect when manual mode is selected
  onDeleteVisualization?: (type: 'workflow' | 'application' | 'agent' | 'datasource' | 'interface', id: string) => void; // Callback to delete a visualization
  onRunWorkflow?: (workflowId: string) => void; // Callback to run a workflow
  onRetryMessage?: (messageContent: string) => void; // Callback to retry/regenerate an assistant message
  compactionMarker?: CompactionMarker | null; // Cold-compaction summary marker (divider placement)
  /** DM mode: resolve attachment URLs through the DM-scoped download endpoint instead of
   *  the (tenant-scoped) chat one - the recipient can't read the sender's file there. */
  attachmentUrlResolver?: (storageId: string) => string;
  /** DM mode: render the sender's avatar in front of EVERY message bubble (mine on the
   *  right, the other participant on the left). Absent → chat behaviour unchanged. */
  messageAvatars?: {
    user?: { avatarUrl?: string | null; name?: string };
    assistant?: { avatarUrl?: string | null; name?: string };
  };
}

// Component to handle workflow display - shows workflow embed or suggestions
interface WorkflowDisplayModeProps {
  content: string;
  messageId: string;
  onReplaceWorkflow?: (messageId: string, template: any) => void;
  hideToggle?: boolean;
  onManualWorkflowMode?: () => void;
}

export const WorkflowDisplayMode: React.FC<WorkflowDisplayModeProps> = ({
  content,
  messageId,
  onReplaceWorkflow,
}) => {
  // Show workflow suggestions (templates)
  return (
    <div className="w-full">
      <div className="message-content w-full flex justify-center">
        {onReplaceWorkflow ? (
          <WorkflowSuggestions
            messageId={messageId}
            onSelect={onReplaceWorkflow}
          />
        ) : (
          <div className="text-sm text-theme-secondary p-4">
            Workflow suggestions will be displayed here
          </div>
        )}
      </div>
    </div>
  );
};

export function MessageHistory({
  messages,
  loading = false,
  error = null,
  className = '',
  hasMoreMessages = false,
  loadingOlderMessages = false,
  onLoadOlderMessages,
  showThinkingMessage = true, // Par defaut true pour maintenir la compatibilite
  scrollContainerRef,
  streamingMessage,
  isStreaming = false,
  streamingCounter = 0,
  toolActivities = [],
  awaitingApproval = false,
  onReplaceWorkflow,
  hideWorkflowToggle = false,
  onReplaceDataSource,
  onManualDataSourceMode,
  hideDataSourceToggle = false,
  showWorkflowSuggestions = false,
  onManualWorkflowMode,
  onDeleteVisualization,
  onRunWorkflow,
  onRetryMessage,
  compactionMarker = null,
  attachmentUrlResolver,
  messageAvatars,
}: MessageHistoryProps) {
  const t = useTranslations();
  const locale = getClientLocale();

  // All hooks must be called before any conditional returns
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  const [isLoadingOlder, setIsLoadingOlder] = useState(false);
  // Image attachment shown enlarged in the lightbox (null = closed). Lifted to the
  // component scope so a single overlay serves every message's image attachments.
  const [lightboxImage, setLightboxImage] = useState<{ src: string; fileName: string } | null>(null);
  const [previousMessageCount, setPreviousMessageCount] = useState(0);
  const scrollPositionRef = useRef<number>(0);
  const previousScrollHeightRef = useRef<number>(0);
  const { view: currentView } = useCurrentView();
  const isWorkflowView = currentView === 'workflow';

  // Cooldown between consecutive lazy-load fetches. With a tall viewport and
  // a short older-page, a single user gesture can leave scrollTop < 100 even
  // after the previous fetch has resolved, which would otherwise cascade
  // page N → N+1 → N+2 immediately. 250ms gives enough time for the user to
  // either keep scrolling (legitimate cascade) or stop (no further fetch).
  const lastFetchAtRef = useRef<number>(0);
  const COOLDOWN_MS = 250;

  // Handle scroll-based lazy loading
  const handleScroll = useCallback(() => {
    const container = scrollContainerRef?.current || messagesContainerRef.current;
    if (!container || !onLoadOlderMessages || loadingOlderMessages || isLoadingOlder) return;

    // Cooldown: ignore re-triggers within COOLDOWN_MS of the last fetch.
    // Prevents a tall viewport + short older-page from cascading N fetches
    // in a single user gesture.
    if (performance.now() - lastFetchAtRef.current < COOLDOWN_MS) return;

    // Check if user is near the top of the scroll
    const scrollTop = container.scrollTop;
    const threshold = 100; // Load when within 100px of top

    if (scrollTop <= threshold && hasMoreMessages) {
      // Save current scroll position and height - the layout effect below
      // uses these to restore position synchronously after the prepend.
      scrollPositionRef.current = container.scrollTop;
      previousScrollHeightRef.current = container.scrollHeight;
      lastFetchAtRef.current = performance.now();

      setIsLoadingOlder(true);
      onLoadOlderMessages();

      // Reset loading state after a delay
      setTimeout(() => {
        setIsLoadingOlder(false);
      }, 1000);
    }
  }, [onLoadOlderMessages, loadingOlderMessages, isLoadingOlder, hasMoreMessages, scrollContainerRef]);

  // Add scroll event listener for lazy loading
  useEffect(() => {
    const container = scrollContainerRef?.current || messagesContainerRef.current;
    if (container) {
      container.addEventListener('scroll', handleScroll);
      return () => container.removeEventListener('scroll', handleScroll);
    }
  }, [handleScroll]);

  // Track message count and restore scroll position after loading older messages.
  //
  // useLayoutEffect (isomorphic) runs synchronously after DOM mutation but BEFORE
  // paint, so the user never sees a flash from "anchored at top of new prepended
  // page" to "anchored at original message". The previous setTimeout(100) version
  // produced a visible jump on slow hardware. try/finally guarantees the
  // isLoadingOlder flag is cleared even if scrollTop assignment throws (e.g. if
  // the container has been detached during the fetch).
  useIsomorphicLayoutEffect(() => {
    const container = scrollContainerRef?.current || messagesContainerRef.current;
    try {
      if (messages.length > previousMessageCount && isLoadingOlder && container) {
        const heightDifference = container.scrollHeight - previousScrollHeightRef.current;
        container.scrollTop = scrollPositionRef.current + heightDifference;
      }
    } finally {
      setPreviousMessageCount(messages.length);
    }
  }, [messages.length, previousMessageCount, isLoadingOlder, scrollContainerRef]);


  const formatTimestamp = (timestamp?: string) => {
    if (!timestamp) return '';

    try {
      const date = parseUtcAware(timestamp);
      const now = new Date();
      const diffInHours = (now.getTime() - date.getTime()) / (1000 * 60 * 60);

      // If more than 24 hours ago, show full UTC date
      if (diffInHours > 24) {
        return formatUtcDateTime(date, { locale });
      }

      // If within 24 hours, show only UTC time
      return formatUtcTime(date, { locale });
    } catch {
      return timestamp;
    }
  };

  const formatSendTime = (timestamp?: string) => {
    if (!timestamp) return '';
    try {
      return formatUtcTime(timestamp, { locale });
    } catch {
      return '';
    }
  };

  const getRoleIcon = (role: string) => {
    // Pas d'icônes pour user et assistant
    switch (role) {
      case 'user':
        return null;
      case 'assistant':
        return null;
      case 'system':
        return <Bot className="w-5 h-5 text-gray-600" />;
      default:
        return null;
    }
  };

  const getRoleColor = (role: string) => {
    switch (role) {
      case 'user':
        return 'bg-theme-tertiary';
      case 'assistant':
        return ''; // Pas de background pour l'assistant, laisser le markdown gerer
      case 'system':
        return 'bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800';
      default:
        return 'bg-theme-tertiary';
    }
  };

  const getRoleLabel = (role: string) => {
    switch (role) {
      case 'user':
        return 'You';
      case 'assistant':
        return 'Assistant';
      case 'system':
        return 'System Error';
      default:
        return role;
    }
  };

  if (error) {
    return (
      <div className={`flex items-center justify-center p-8 ${className}`}>
        <div className="text-center">
          <AlertTriangle className="w-12 h-12 text-red-400 mx-auto mb-4" />
          <h3 className="text-lg font-medium text-theme-primary mb-2">{t('chat.messageHistory.loadError')}</h3>
          <p className="text-theme-secondary">{error}</p>
        </div>
      </div>
    );
  }

  console.log('[MessageHistory] RENDER:', { loading, messagesCount: messages.length, isStreaming });

  // Don't show skeleton when streaming (sending new message) - the streaming indicator will show
  // Only show skeleton when loading an EXISTING conversation (loading=true, not streaming, no messages yet)
  if (loading && messages.length === 0 && !isStreaming) {
    return (
      <div className={`space-y-4 mx-auto ${isWorkflowView ? 'w-full' : 'max-w-4xl md:px-4'} ${className}`}>
        <MessageSkeleton />
      </div>
    );
  }

  // No messages, not loading, not streaming = new chat, return null to show welcome message
  if (messages.length === 0 && !isStreaming) {
    return null;
  }

  return (
    <div ref={messagesContainerRef} className={`space-y-4 mx-auto ${isWorkflowView ? 'w-full' : 'max-w-4xl'} ${className}`}>
      {/* Loading indicator for older messages */}
      {isLoadingOlder && (
        <div className="flex justify-center py-4">
          <LoadingSpinner size="sm" text="Loading older messages..." />
        </div>
      )}

      {(() => {
        // Simple filtering - no accumulation needed since backend saves ONE message per response
        const filteredMessages = messages.filter(message => {
          // Hide internal system messages
          if (message.role === 'system' && message.content?.startsWith('[INTERNAL:')) {
            return false;
          }
          // Hide TOOL result messages (results are stored but not displayed)
          if (message.role === 'tool') {
            return false;
          }
          return true;
        });

        // Compute the compaction divider position. `turnsCovered` is a list of message indices
        // folded into the cold summary; the divider appears AFTER the last covered one, i.e.
        // immediately BEFORE the first uncovered message.
        const coveredIndices = compactionMarker?.turnsCovered ?? [];
        const compactionDividerBeforeIndex =
          coveredIndices.length > 0 ? Math.max(...coveredIndices) + 1 : -1;
        const coveredCount = coveredIndices.length;

        // Render all messages
        return filteredMessages.map((message, index) => {
        // Handle both Message and ChatMessage types
        const messageId = 'id' in message ? message.id : `msg-${index}`;
        const messageToolCalls = 'toolCalls' in message ? message.toolCalls : undefined;
        const messageAttachments = 'attachments' in message ? message.attachments : undefined;

        const isUser = message.role === 'user';
        const isAssistant = message.role === 'assistant';

        // Create unique key combining messageId and index to avoid duplicates
        const uniqueKey = `${messageId}-${index}`;

        // Content to display
        const displayContent = message.content || '';

        const isWorkflow = isWorkflowMessage(displayContent);
        const isDataSource = isDataSourceMessage(displayContent);

        // Parse tools + reasoning duration from this message's persisted toolCalls.
        // Shared with the Conversation Activity card so both hydrate identically.
        const { tools: deduplicatedTools, reasoningDurationMs } =
          parseToolActivitiesFromMessage(messageToolCalls, index);

        const hasContent = displayContent && displayContent.trim().length > 0;

        // All tools are shown together in ActivityFeed (including workflow_run)
        const isToolsOnly = !hasContent && deduplicatedTools.length > 0;

        const showDividerBefore = index === compactionDividerBeforeIndex;

        return (
          <React.Fragment key={uniqueKey}>
            {showDividerBefore && (
              <div className="flex items-center gap-2 my-6 text-theme-secondary" aria-label={t('chat.compactionDivider')}>
                <div className="flex-1 h-px bg-theme-tertiary" />
                <div
                  className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-theme-tertiary/60 text-xs"
                  title={compactionMarker?.model ? t('chat.compactionTooltip', { model: compactionMarker.model }) : undefined}
                >
                  <Archive className="h-3 w-3" />
                  <span>{t('chat.compactionDivider')}</span>
                  <span className="opacity-70">·</span>
                  <span className="opacity-70">{t('chat.compactionDividerDetail', { count: coveredCount })}</span>
                </div>
                <div className="flex-1 h-px bg-theme-tertiary" />
              </div>
            )}
          <div
            id={`message-${messageId}`}
            className={`group flex items-start gap-3 ${isUser && !isWorkflow && !isDataSource ? 'justify-end mb-15' : (isWorkflow || isDataSource) ? 'justify-center' : 'justify-start mb-15'}`}
          >
            {/* DM mode: the sender's avatar in front of every bubble (peer on the left). */}
            {messageAvatars && isAssistant && !isWorkflow && !isDataSource && (
              <AvatarDisplay
                avatarUrl={messageAvatars.assistant?.avatarUrl ?? undefined}
                name={messageAvatars.assistant?.name}
                size="sm"
                className="!h-7 !w-7 mt-1 flex-shrink-0"
              />
            )}
            {/* Message content */}
            <div className={`${isUser && !isWorkflow && !isDataSource ? 'max-w-[85%] sm:max-w-[70%]' : (isWorkflow || isDataSource) ? 'w-full' : 'flex-1'} min-w-0 relative`}>
              {/* Tool calls - Display BEFORE content using ActivityFeed */}
              {deduplicatedTools.length > 0 && (
                <ActivityFeed
                  activities={deduplicatedTools}
                  className={hasContent ? 'mb-3' : ''}
                  isStreaming={false}
                  storedReasoningDurationMs={reasoningDurationMs}
                  awaitingApproval={awaitingApproval && index === filteredMessages.length - 1 && isAssistant}
                />
              )}

              <div className={`${isAssistant ? '' : (isWorkflow || isDataSource) ? '' : 'rounded-[18px]'} ${(isWorkflow || isDataSource) ? 'bg-transparent transition-all duration-300 overflow-hidden relative' : isAssistant ? '' : `p-4 ${getRoleColor(message.role)}`}`}>
                {/* Message text, Workflow, or DataSource - skip if this is a tools-only entry */}
                {isToolsOnly ? null : isWorkflow ? (
                  <WorkflowDisplayMode
                    content={displayContent}
                    messageId={messageId}
                    onReplaceWorkflow={onReplaceWorkflow}
                    hideToggle={hideWorkflowToggle}
                    onManualWorkflowMode={onManualWorkflowMode}
                  />
                ) : isDataSource ? (
                  <DataSourceDisplayMode
                    content={displayContent}
                    messageId={messageId}
                    onReplaceDataSource={onReplaceDataSource}
                    onManualMode={onManualDataSourceMode}
                    hideToggle={hideDataSourceToggle}
                  />
                ) : (hasContent || (messageAttachments && messageAttachments.length > 0)) ? (
                  <div className="message-content break-words overflow-wrap-anywhere">
                    {/* Attachment previews. Historically user-side only; DM messages from the
                        other participant (mapped to 'assistant') carry them too - a no-op for
                        agent chats, whose assistant messages never have attachments. */}
                    {messageAttachments && messageAttachments.length > 0 && (
                      <div className={`flex flex-wrap gap-2 ${hasContent ? 'mb-3' : ''}`}>
                        {messageAttachments.map((attachment, idx) => (
                          <div
                            key={`${attachment.storageId}-${idx}`}
                            className="relative group"
                          >
                            {attachment.type === 'IMAGE' ? (
                              // Image preview - click (or the hover "enlarge" button) opens the lightbox.
                              <button
                                type="button"
                                onClick={() => setLightboxImage({
                                  src: (attachmentUrlResolver ?? attachmentApi.getDownloadUrl)(attachment.storageId),
                                  fileName: attachment.fileName,
                                })}
                                aria-label={t('chat.imageViewer.enlarge')}
                                className="relative overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700 block cursor-zoom-in"
                              >
                                <AuthenticatedImage
                                  src={(attachmentUrlResolver ?? attachmentApi.getDownloadUrl)(attachment.storageId)}
                                  alt={attachment.fileName}
                                  className="max-w-[200px] max-h-[150px] object-cover"
                                  fallbackClassName="w-[100px] h-[75px]"
                                />
                                {/* Hover "enlarge" affordance - top-right. */}
                                <span className="absolute top-1.5 right-1.5 opacity-0 group-hover:opacity-100 transition-opacity p-1 rounded-md bg-black/50 text-white">
                                  <Maximize2 className="h-3.5 w-3.5" />
                                </span>
                                <span className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/60 to-transparent p-1.5">
                                  <span className="text-[10px] text-white truncate block">{attachment.fileName}</span>
                                </span>
                              </button>
                            ) : (
                              // Non-image attachments - header-authenticated blob download
                              // (the token never goes in a URL, same posture as AuthenticatedImage).
                              <button
                                type="button"
                                onClick={() => void downloadAuthenticatedAttachment(
                                  (attachmentUrlResolver ?? attachmentApi.getDownloadUrl)(attachment.storageId),
                                  attachment.fileName,
                                )}
                                className="flex items-center gap-1.5 px-2 py-1.5 bg-black/5 dark:bg-white/10 rounded-lg text-xs text-theme-secondary hover:bg-black/10 dark:hover:bg-white/20 transition-colors cursor-pointer"
                                title={attachment.fileName}
                              >
                                {attachment.type === 'PDF' ? (
                                  <FileText className="h-4 w-4 text-red-500" />
                                ) : (
                                  <Paperclip className="h-4 w-4 text-gray-500" />
                                )}
                                <span className="truncate max-w-[120px]">{attachment.fileName}</span>
                              </button>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                    {/* Clean thinking markers from stored messages */}
                    {hasContent && (() => {
                      const parsed = parseThinkingMarker(displayContent);
                      const contentToRender = parsed ? parsed.cleanContent : displayContent;
                      return <MarkdownRender text={contentToRender} onDeleteVisualization={onDeleteVisualization} onRunWorkflow={onRunWorkflow} />;
                    })()}
                  </div>
                ) : null}
              </div>

              {/* Copy button for user messages */}
              {isUser && hasContent && !isWorkflow && !isDataSource && (
                <UserCopyButton
                  content={displayContent}
                  timeLabel={formatSendTime(message.timestamp)}
                />
              )}

              {/* Action buttons for assistant messages */}
              {isAssistant && hasContent && !isWorkflow && !isDataSource && (
                <MessageActions
                  content={displayContent}
                  messageId={messageId}
                  timeLabel={formatSendTime(message.timestamp)}
                  initialFeedback={'feedback' in message ? (message as Message).feedback : undefined}
                  showFeedback={!messageAvatars}
                  onRetry={onRetryMessage ? () => {
                    // Find the last user message before this assistant message
                    const userMessages = filteredMessages.slice(0, index).filter(m => m.role === 'user');
                    const lastUserMessage = userMessages[userMessages.length - 1];
                    if (lastUserMessage?.content) {
                      onRetryMessage(lastUserMessage.content);
                    }
                  } : undefined}
                />
              )}
            </div>
            {/* DM mode: my own avatar on the right of my (right-aligned) bubbles. */}
            {messageAvatars && isUser && !isWorkflow && !isDataSource && (
              <AvatarDisplay
                avatarUrl={messageAvatars.user?.avatarUrl ?? undefined}
                name={messageAvatars.user?.name}
                size="sm"
                className="!h-7 !w-7 mt-1 flex-shrink-0"
              />
            )}
          </div>
          </React.Fragment>
        );
      });
      })()}

      {/* Workflow suggestions without message - show when showWorkflowSuggestions is true */}
      {showWorkflowSuggestions && (
        <div className="group flex justify-center mb-15">
          <div className="w-full min-w-0 relative">
            <div className="bg-transparent transition-all duration-300 overflow-hidden relative">
              <WorkflowDisplayMode
                content=""
                messageId="temp-suggestions"
                onReplaceWorkflow={onReplaceWorkflow}
                hideToggle={false}
                onManualWorkflowMode={onManualWorkflowMode}
              />
            </div>
          </div>
        </div>
      )}

       {/* Streaming message - show when there's content OR tool activities */}
       {(streamingMessage || toolActivities.length > 0) && (() => {
         const thinkingMessage = isStreaming && streamingMessage ? getLastThinkingMessage(streamingMessage) : null;
         const parsed = streamingMessage ? parseThinkingMarker(streamingMessage) : null;
         const cleanContent = parsed ? parsed.cleanContent : (streamingMessage || '');
         const hasVisibleContent = cleanContent.trim().length > 0;

         return (
           <div className="group flex items-start gap-3 justify-start mb-15">
             {/* Agent avatar removed - no per-message avatars */}
             <div className="flex-1 min-w-0 relative">
               <div className="flex flex-col">
                 {/* Show tool activities with integrated thinking message - MUST be first (order-1) */}
                 {(toolActivities.length > 0 || isStreaming) && (
                   <ActivityFeed
                     activities={toolActivities}
                     thinkingMessage={isStreaming ? (thinkingMessage || 'Thinking...') : undefined}
                     className={`order-1 ${hasVisibleContent ? 'mb-4' : ''}`}
                     isStreaming={isStreaming}
                     awaitingApproval={awaitingApproval}
                   />
                 )}
                 {/* Show cleaned content if any - MUST be after tools (order-2) */}
                 {hasVisibleContent && (
                   <div className="order-2 message-content break-words overflow-wrap-anywhere">
                     <MarkdownRender text={cleanContent} isStreaming={isStreaming} onDeleteVisualization={onDeleteVisualization} onRunWorkflow={onRunWorkflow} />
                   </div>
                 )}
               </div>
             </div>
           </div>
         );
       })()}


      {/* Loading indicator when streaming but no content yet AND no tools - show Thinking immediately */}
      {/* Note: If we have toolActivities, they're already shown in the section above */}
      {isStreaming && !streamingMessage && toolActivities.length === 0 && (
        <div className="group flex items-start gap-3 justify-start mb-4">
          {/* Agent avatar removed - no per-message avatars */}
          <div className="flex-1">
            {/* Show thinking indicator only (no tools - they're shown above when present) */}
            <ActivityFeed
              activities={[]}
              thinkingMessage="Thinking..."
              className="mb-4"
              isStreaming={true}
            />
          </div>
        </div>
      )}

      {/* Single image lightbox for every message's image attachments. */}
      <ImageLightbox
        open={lightboxImage !== null}
        onClose={() => setLightboxImage(null)}
        src={lightboxImage?.src ?? ''}
        alt={lightboxImage?.fileName ?? ''}
        fileName={lightboxImage?.fileName}
        authenticated
      />
    </div>
  );
}

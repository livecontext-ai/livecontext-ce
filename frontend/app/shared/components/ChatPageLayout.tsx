'use client';

import type { ComponentProps, RefObject } from 'react';
import { ToolSelector } from '@/components/chat/ToolSelector';
import { MessageComposer } from '@/components/chat/MessageComposer';
import { MessageHistory } from '@/components/chat/MessageHistory';
import { ChatCore } from '@/components/chat/ChatCore';
import { useCurrentView } from '@/hooks/useCurrentView';
import { DataSourceMessage, isDataSourceMessage } from '@/components/chat/DataSourceMessage';
import { useRouter } from 'next/navigation';
import { useEffect, useMemo, useState } from 'react';
import { DashboardContent } from '@/components/chat/DashboardContent';
import { ConversationActivityCard } from '@/components/chat/ConversationActivityCard';
import { useConversationActivity } from '@/contexts/ConversationActivityContext';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { useSidePanelLayoutSafe } from '@/contexts/SidePanelLayoutContext';
import { useMobileDetection } from '@/hooks/useMobileDetection';
import { scrollToAndHighlightMessage } from '@/lib/chat/messageActivity';
import { HighlightedApps } from '@/components/chat/HighlightedApps';
import { HomeDynamicTitle } from '@/components/chat/HomeDynamicTitle';
import { HomeSuggestionChips } from '@/components/chat/HomeSuggestionChips';
import { HomeModeSwitch } from '@/components/chat/HomeModeSwitch';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { HomeQuickOpenButton } from '@/components/chat/HomeQuickOpenButton';

type StreamError = {
  message: string;
  retryable?: boolean;
} | null;

type ToolSelectorProps = ComponentProps<typeof ToolSelector>;
type HistoryProps = ComponentProps<typeof MessageHistory>;
type ComposerProps = ComponentProps<typeof MessageComposer>;

import type { Conversation, Message } from '@/lib/api/conversationApi';

export interface ChatPageLayoutProps {
  toolSelectorProps: ToolSelectorProps;
  messageHistoryProps: HistoryProps;
  composerProps: ComposerProps;
  layoutState: {
    showWelcomeMessage: boolean;
    shouldRenderHistory: boolean;
    isConversationActive: boolean;
    isLoadingConversation: boolean;
    messagesContainerRef: RefObject<HTMLDivElement>;
    streamLastError: StreamError;
    attemptStreamReconnection: () => void;
  };
  conversationId?: string | null;
  conversation?: Conversation | null;
  conversationTitle?: string | null;
  dashboardPath?: string | null;
  enableDataSource?: boolean; // Feature flag to enable datasource support
}

export function ChatPageLayout({
  toolSelectorProps,
  messageHistoryProps,
  composerProps,
  layoutState,
  conversationId,
  conversation,
  conversationTitle,
  dashboardPath,
  enableDataSource = false,
}: ChatPageLayoutProps) {
  const {
    showWelcomeMessage,
    // shouldRenderHistory and isConversationActive are handled by ChatCore now
    isLoadingConversation,
    messagesContainerRef,
    streamLastError,
    attemptStreamReconnection
  } = layoutState;

  const router = useRouter();

  // Conversation Activity card - shared open state with the header toggle.
  // Closing is done from the focused AppHeader toggle (desktop) or the
  // tablet/mobile focus backdrop (onClose).
  const { isOpen: activityOpen, setOpen: setActivityOpen } = useConversationActivity();
  // When the right side panel SHRINKS the conversation area, center the activity
  // card in what is left instead of docking it top-right over the panel.
  //
  // "Shrinks" is the question, not "is open". Two ways a panel can be open and take
  // no layout space, and centering for either moves the card away from its corner
  // for nothing: a DETACHED panel is a fixed card out of flow, and below the mobile
  // breakpoint EVERY dock renders as a fixed full-screen overlay, so no dock value
  // reshapes the area there. (A collapsed panel is always detached, so the first
  // test covers it too.)
  const sidePanelDock = useSidePanelLayoutSafe().position;
  const isMobile = useMobileDetection();
  const sidePanelOpen = (useSidePanelSafe()?.isOpen ?? false) && sidePanelDock !== 'floating' && !isMobile;
  // Scroll to the sent message and flash a ring on its bubble (shared helper, unit-tested).
  const handleActivityJump = (messageId: string) => scrollToAndHighlightMessage(messageId);

  // Stop the home title rotation + chip cycling once the user starts interacting:
  // either typing in the composer, clicking the composer area, or clicking a
  // suggestion chip. Sticky - once paused stays paused for the rest of this
  // welcome view, since "blinking" would feel disruptive after engagement.
  const [interactionPaused, setInteractionPaused] = useState(false);
  useEffect(() => {
    if (!interactionPaused && composerProps.inputValue.trim().length > 0) {
      setInteractionPaused(true);
    }
  }, [composerProps.inputValue, interactionPaused]);
  const handleComposerInteract = () => {
    if (!interactionPaused) setInteractionPaused(true);
  };

  // Use native Next.js routing via useCurrentView hook
  const { view: currentView, dataSourceId } = useCurrentView();

  // A generation writes a file into the workspace and spends credits, so a read-only VIEWER is not
  // offered the studio at all - the same gate the Generate control applies. Offering the switch
  // would lead them to a surface where every control is inert and nothing says why.
  const canUseStudio = useCanMutateInCurrentOrg();

  // Built once and handed to whichever composer is on screen. Two composers render on the home
  // page - the desktop one anchored high, the mobile one pinned to the bottom - and only one is
  // ever visible, so this is the same control in both rather than two of them.
  const homeModeSwitch = canUseStudio
    ? <HomeModeSwitch mode="chat" compact onInteract={handleComposerInteract} />
    : undefined;

  // Derive view states from URL
  const expandedDataSourceId = enableDataSource ? (dataSourceId || null) : null;
  const isDataView = enableDataSource && currentView === 'data';
  
  // Find datasource content by ID from messages (only if datasource is enabled)
  const expandedDataSourceContent = useMemo(() => {
    if (!enableDataSource || !expandedDataSourceId) return null;
    
    if (expandedDataSourceId === 'new') {
      if (messageHistoryProps.messages) {
        const message = messageHistoryProps.messages.find((msg) => {
          const msgId = 'id' in msg ? msg.id : undefined;
          return msgId === 'new' && isDataSourceMessage(msg.content);
        });
        if (message) {
          return message.content;
        }
      }
      return JSON.stringify({
        type: '__DATASOURCE__',
        dataSourceId: null,
      });
    }
    
    if (messageHistoryProps.messages) {
      const message = messageHistoryProps.messages.find((msg) => {
        if (!isDataSourceMessage(msg.content)) return false;
        
        try {
          const parsed = JSON.parse(msg.content);
          return String(parsed.dataSourceId) === String(expandedDataSourceId);
        } catch {
          return false;
        }
      });
      if (message) {
        return message.content;
      }
    }
    
    return JSON.stringify({
      type: '__DATASOURCE__',
      dataSourceId: expandedDataSourceId,
    });
  }, [expandedDataSourceId, messageHistoryProps.messages, enableDataSource]);

  // Helper function to minimize datasource - navigate back to chat
  const handleMinimizeDataSource = () => {
    if (!enableDataSource) return;

    const dataSourceIdToScroll = expandedDataSourceId;

    // Navigate back to chat using native routing
    if (conversationId) {
      router.push(`/app/c/${conversationId}`);
    } else {
      router.push('/app/chat');
    }

    setTimeout(() => {
      if (dataSourceIdToScroll && messageHistoryProps.messages) {
        const message = messageHistoryProps.messages.find((msg) => {
          const msgId = 'id' in msg ? msg.id : undefined;
          if (!isDataSourceMessage(msg.content)) return false;
          try {
            const parsed = JSON.parse(msg.content);
            return String(parsed.dataSourceId) === String(dataSourceIdToScroll);
          } catch {
            return false;
          }
        });
        
        if (message && 'id' in message) {
          const messageElement = document.getElementById(`message-${message.id}`);
          if (messageElement) {
            messageElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
          } else if (messagesContainerRef.current) {
            messagesContainerRef.current.scrollTop = messagesContainerRef.current.scrollHeight;
          }
        } else if (messagesContainerRef.current) {
          messagesContainerRef.current.scrollTop = messagesContainerRef.current.scrollHeight;
        }
      } else if (messagesContainerRef.current) {
        messagesContainerRef.current.scrollTop = messagesContainerRef.current.scrollHeight;
      }
    }, 300);
  };

  // Note: AppHeader is now rendered in /app/layout.tsx (provides ChatHeader)
  return (
    <div className="flex-1 flex flex-col bg-transparent min-h-0 min-w-0 overflow-hidden relative">
          {!(expandedDataSourceContent && isDataView) && (
            <div data-tool-selector>
              <ToolSelector {...toolSelectorProps} />
            </div>
          )}

          {(expandedDataSourceContent && isDataView) ? (
            <>
              <div
                className="flex-1 min-h-0 relative overflow-hidden transition-all duration-300"
              >
                <div className="absolute inset-0 z-10 overflow-y-auto px-4 pt-6 sm:px-6 lg:px-5 xl:px-7">
                  <div className="space-y-6 pb-10 w-full">
                    <DataSourceMessage
                      content={expandedDataSourceContent}
                      hideExpandButton={true}
                      hideBreadcrumb={true}
                    />
                  </div>
                </div>
              </div>
            </>
          ) : dashboardPath ? (
            <>
              <div 
                ref={messagesContainerRef}
                className="flex-1 min-h-0 overflow-y-auto bg-theme-primary transition-colors duration-300"
              >
                <DashboardContent />
              </div>
            </>
          ) : showWelcomeMessage ? (
            /* Welcome view - special layout with MessageComposer at 25% from top */
            <>
              <div ref={messagesContainerRef} className="flex-1 overflow-y-auto py-4 space-y-4 min-h-0 chat-messages-container relative">
                {/* Stream error banner - sticky at top */}
                {streamLastError && (
                  <div className="sticky top-0 z-20 mx-auto max-w-4xl px-2 w-full">
                    <div className="rounded-[18px] flex items-center justify-center py-2 px-4 mb-4 bg-red-50 dark:bg-red-900/20 shadow-sm">
                      <div className="flex items-center space-x-2 text-red-700 dark:text-red-300">
                        <div className="w-2 h-2 bg-red-500 rounded-full animate-pulse"></div>
                        <span className="text-sm">
                          Stream error: {streamLastError.message}
                        </span>
                        {streamLastError.retryable && (
                          <button
                            onClick={attemptStreamReconnection}
                            className="text-xs px-2 py-1 bg-red-100 text-red-700 rounded hover:bg-red-200 transition-colors"
                          >
                            Retry
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                )}

                <div className="mx-auto w-full flex flex-col">
                  <div className="flex flex-col w-full">
                    {/* Desktop layout */}
                    <div className="hidden sm:flex sm:flex-col w-full">
                      {/* Title + composer anchored at ~22vh so the tools popup has room above */}
                      <div className="pt-[22vh] shrink-0 mx-auto max-w-4xl px-2 w-full">
                        <div className="text-center max-w-md mx-auto mb-8">
                          <HomeDynamicTitle paused={interactionPaused} />
                        </div>

                        <div className="w-full relative" onMouseDown={handleComposerInteract}>
                          <div className="absolute inset-x-4 -top-4 h-4 rounded-t-[32px] bg-gradient-to-r from-transparent via-white/80 to-transparent blur-xl opacity-40 dark:from-transparent dark:via-white/10 dark:to-transparent"></div>
                          <div className="relative flex items-end justify-center">
                            <div className="max-w-3xl w-full">
                              <div className="p-4">
                                <MessageComposer {...composerProps} modeSwitch={homeModeSwitch} />
                              </div>
                            </div>
                          </div>
                        </div>

                        {/* Quick-start automation chips - single-row pool, cycles every ~5s until interaction */}
                        <HomeSuggestionChips
                          onPick={composerProps.onInputChange}
                          onInteract={handleComposerInteract}
                          paused={interactionPaused}
                        />
                      </div>

                      {/* Highlighted this week - curated marketplace row */}
                      <HighlightedApps />
                      <div className="h-8" />
                    </div>

                    {/* Mobile layout */}
                    <div className="flex flex-col w-full sm:hidden">
                      <div className="pt-8 shrink-0 px-2">
                        <div className="text-center max-w-md mx-auto mb-4">
                          <HomeDynamicTitle paused={interactionPaused} />
                        </div>
                        <HomeSuggestionChips
                          onPick={composerProps.onInputChange}
                          onInteract={handleComposerInteract}
                          paused={interactionPaused}
                        />
                      </div>
                      <HighlightedApps />
                      <div className="h-6" />
                    </div>
                  </div>
                </div>
              </div>

              {/* Mobile bottom composer (hidden on desktop where it's at 25% from top) */}
              <div className="block sm:hidden" onMouseDown={handleComposerInteract}>
                <MessageComposer {...composerProps} modeSwitch={homeModeSwitch} />
              </div>

              {/* Quick-open shortcut to the page the user picked in the sidebar's
                  customize menu (Agenda unless they changed it). */}
              <HomeQuickOpenButton />
            </>
          ) : (
            /* Active conversation - ChatCore handles streaming, service approval, auto-scroll */
            <>
              <ChatCore
                conversationId={conversationId ?? null}
                conversation={conversation}
                messages={messageHistoryProps.messages ?? []}
                isLoading={isLoadingConversation || messageHistoryProps.loading}
                onSendMessage={composerProps.onSendMessage}
                onStopStream={composerProps.onStopStream}
                isStreamStarting={composerProps.isStreamStarting}
                hasMoreMessages={messageHistoryProps.hasMoreMessages}
                loadingOlderMessages={messageHistoryProps.loadingOlderMessages}
                onLoadOlderMessages={messageHistoryProps.onLoadOlderMessages}
                showWorkflowSuggestions={messageHistoryProps.showWorkflowSuggestions}
                onReplaceWorkflow={messageHistoryProps.onReplaceWorkflow}
                onReplaceDataSource={messageHistoryProps.onReplaceDataSource}
                onRunWorkflow={messageHistoryProps.onRunWorkflow}
                onDeleteVisualization={messageHistoryProps.onDeleteVisualization}
                hideWorkflowToggle={messageHistoryProps.hideWorkflowToggle}
                hideDataSourceToggle={messageHistoryProps.hideDataSourceToggle}
                leadingControl={composerProps.leadingControl}
                linkedAgentId={composerProps.linkedAgentId}
                showOrbi={composerProps.showOrbi}
                className="flex-1 min-h-0"
              />
              {activityOpen && conversationId && (
                <ConversationActivityCard
                  messages={(messageHistoryProps.messages ?? []) as Message[]}
                  liveToolActivities={messageHistoryProps.toolActivities ?? []}
                  isStreaming={!!messageHistoryProps.isStreaming}
                  hasMoreMessages={!!messageHistoryProps.hasMoreMessages}
                  loadingOlderMessages={!!messageHistoryProps.loadingOlderMessages}
                  onLoadOlderMessages={messageHistoryProps.onLoadOlderMessages ?? (() => {})}
                  onJumpToMessage={handleActivityJump}
                  onClose={() => setActivityOpen(false)}
                  centered={sidePanelOpen}
                />
              )}
            </>
          )}
    </div>
  );
}


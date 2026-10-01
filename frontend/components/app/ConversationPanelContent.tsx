'use client';

import React, { useState, useEffect, useCallback, useRef, useReducer } from 'react';
import { MessageHistory } from '@/components/chat/MessageHistory';
import { type Message, conversationApi } from '@/lib/api/conversationApi';
import { useMessages } from '@/hooks/conversation/useMessages';
import { sortMessagesByTime } from '@/lib/utils/messageUtils';
import { useConversationChannel } from '@/lib/websocket/use-conversation-channel';
import { useConversationResync } from '@/hooks/chat/useConversationResync';
import { onConversationMessagesCleared } from '@/lib/chat/conversationMessagesBus';
import { detectStreamEventType, isServerStreamLive, mapV2EventToV1, threadEndsWithReply } from '@/lib/streaming/streamHelpers';
import { unifiedApiService } from '@/lib/api';
import type { ToolActivity } from '@/components/chat/ActivityFeed';
import LoadingSpinner from '@/components/LoadingSpinner';
import { MessageSquare } from 'lucide-react';

interface ConversationPanelContentProps {
  conversationId: string;
  /** Filter messages by executionId. Use "latest" to auto-resolve the most recent execution. */
  executionId?: string;
}

// ── Lightweight streaming state (mirrors StreamingContext's shape) ──

export interface StreamingState {
  isStreaming: boolean;
  content: string;
  toolActivities: ToolActivity[];
  streamId: string | null;
}

type SubAgentMeta = { name: string; avatarUrl?: string; agentId: string };

type StreamingAction =
  | { type: 'STREAM_STARTED'; streamId: string }
  | { type: 'CONTENT'; chunk: string; replay?: boolean }
  | { type: 'TOOL_CALL'; toolName: string; toolId: string; arguments?: string; thinkingMessage?: string }
  | { type: 'TOOL_RESULT'; toolId: string; toolName?: string; success: boolean; durationMs?: number; error?: string; resultId?: string }
  | { type: 'COMPLETED' }
  | { type: 'ERROR' }
  | { type: 'RESET' }
  | { type: 'SUB_AGENT_STARTED'; subAgent: SubAgentMeta }
  | { type: 'SUB_AGENT_TOOL_CALL'; subAgent: SubAgentMeta; toolName: string; toolId: string }
  | { type: 'SUB_AGENT_TOOL_RESULT'; subAgent: SubAgentMeta; toolId: string; toolName?: string; success: boolean; durationMs?: number }
  | { type: 'SUB_AGENT_COMPLETED'; subAgent: SubAgentMeta; success: boolean }
  | { type: 'SUB_AGENT_CONTENT'; subAgent: SubAgentMeta; content: string }
  | { type: 'SUB_AGENT_THINKING'; subAgent: SubAgentMeta; thinking: string };

export const initialStreamingState: StreamingState = {
  isStreaming: false,
  content: '',
  toolActivities: [],
  streamId: null,
};

export function streamingReducer(state: StreamingState, action: StreamingAction): StreamingState {
  switch (action.type) {
    case 'STREAM_STARTED':
      // If already streaming with the same streamId, skip reset (snapshot replay overlap).
      if (state.isStreaming && state.streamId && state.streamId === action.streamId) {
        return state;
      }
      return { ...initialStreamingState, isStreaming: true, streamId: action.streamId };

    case 'CONTENT': {
      // Replay content (tagged by backend) replaces current content instead of appending.
      // This is the full accumulated snapshot - always more complete than partial real-time chunks.
      if (action.replay) {
        // Use whichever is longer: replay snapshot or current real-time accumulation.
        // Replay may be slightly behind if new chunks arrived after the snapshot was taken.
        const content = action.chunk.length >= state.content.length ? action.chunk : state.content;
        return { ...state, isStreaming: true, content };
      }
      return { ...state, isStreaming: true, content: state.content + action.chunk };
    }

    case 'TOOL_CALL': {
      // Deduplicate by toolId
      if (state.toolActivities.some(a => a.toolId === action.toolId)) {
        return state;
      }
      const newActivity: ToolActivity = {
        id: action.toolId,
        toolName: action.toolName,
        toolId: action.toolId,
        arguments: action.arguments,
        thinkingMessage: action.thinkingMessage,
        status: 'pending',
        timestamp: Date.now(),
      };
      // Auto-recover isStreaming if stream_started was missed (WS subscription race)
      return { ...state, isStreaming: true, toolActivities: [...state.toolActivities, newActivity] };
    }

    case 'TOOL_RESULT': {
      const updated = state.toolActivities.map(a =>
        a.toolId === action.toolId
          ? {
              ...a,
              status: (action.success ? 'success' : 'error') as ToolActivity['status'],
              durationMs: action.durationMs,
              error: action.error,
              resultId: action.resultId,
            }
          : a
      );
      return { ...state, toolActivities: updated };
    }

    case 'COMPLETED':
    case 'ERROR':
      return { ...state, isStreaming: false };

    case 'RESET':
      return initialStreamingState;

    // Sub-agent forwarded events: attach to the pending "agent" tool call
    case 'SUB_AGENT_STARTED': {
      // Find last pending "agent" tool call and attach sub-agent metadata
      const activities = state.toolActivities.map(a => {
        if (a.toolName === 'agent' && a.status === 'pending' && !a.subAgent) {
          return { ...a, subAgent: action.subAgent, subActivities: [], subAgentStatus: 'running' as const };
        }
        return a;
      });
      return { ...state, toolActivities: activities };
    }

    case 'SUB_AGENT_TOOL_CALL': {
      const activities = state.toolActivities.map(a => {
        if (a.subAgent?.agentId === action.subAgent.agentId && a.subAgentStatus === 'running') {
          const subActivity: ToolActivity = {
            id: action.toolId,
            toolName: action.toolName,
            toolId: action.toolId,
            status: 'pending',
            timestamp: Date.now(),
          };
          return { ...a, subActivities: [...(a.subActivities || []), subActivity] };
        }
        return a;
      });
      return { ...state, toolActivities: activities };
    }

    case 'SUB_AGENT_TOOL_RESULT': {
      const activities = state.toolActivities.map(a => {
        if (a.subAgent?.agentId === action.subAgent.agentId && a.subActivities) {
          const updatedSubs = a.subActivities.map(sub =>
            sub.toolId === action.toolId
              ? { ...sub, status: (action.success ? 'success' : 'error') as ToolActivity['status'], durationMs: action.durationMs }
              : sub
          );
          return { ...a, subActivities: updatedSubs };
        }
        return a;
      });
      return { ...state, toolActivities: activities };
    }

    case 'SUB_AGENT_COMPLETED': {
      const activities = state.toolActivities.map(a => {
        if (a.subAgent?.agentId === action.subAgent.agentId) {
          return { ...a, subAgentStatus: (action.success ? 'completed' : 'error') as ToolActivity['subAgentStatus'] };
        }
        return a;
      });
      return { ...state, toolActivities: activities };
    }

    case 'SUB_AGENT_CONTENT': {
      const activities = state.toolActivities.map(a => {
        if (a.subAgent?.agentId === action.subAgent.agentId && a.subAgentStatus === 'running') {
          return { ...a, subAgentContent: (a.subAgentContent || '') + action.content };
        }
        return a;
      });
      return { ...state, toolActivities: activities };
    }

    case 'SUB_AGENT_THINKING': {
      const activities = state.toolActivities.map(a => {
        if (a.subAgent?.agentId === action.subAgent.agentId && a.subAgentStatus === 'running') {
          return { ...a, subAgentThinking: (a.subAgentThinking || '') + action.thinking };
        }
        return a;
      });
      return { ...state, toolActivities: activities };
    }

    default:
      return state;
  }
}

/**
 * Read-only conversation viewer for the side panel.
 * Loads messages on mount and receives real-time updates via WebSocket.
 * Supports live streaming display when the agent is executing in a workflow.
 */
export function ConversationPanelContent({ conversationId, executionId }: ConversationPanelContentProps) {
  // Paginated message loading - same hook the main chat uses (DRY).
  // First paint = 10 most recent; scroll up triggers loadOlderMessages.
  const {
    messages,
    messagesLoading,
    hasMoreMessages,
    loadingOlderMessages,
    error,
    loadMessages,
    loadOlderMessages,
    setMessages,
    clearMessages,
  } = useMessages({ executionId });
  const scrollRef = useRef<HTMLDivElement>(null);
  const [streaming, dispatchStreaming] = useReducer(streamingReducer, initialStreamingState);
  const [streamingCounter, setStreamingCounter] = useState(0);
  const hasAutoScrolledRef = useRef(false);

  // This panel is the SECOND surface that renders a transcript, off its own
  // `useMessages` store. When the sidebar wipes a conversation's history, the
  // server is emptied but no store hears about it, so the messages sit here
  // until the panel is closed and reopened - the same "green button, nothing
  // cleared" the chat page had. Every surface that draws a transcript has to
  // listen; that is the invariant, not a per-surface nicety.
  useEffect(
    () =>
      onConversationMessagesCleared((clearedId) => {
        if (clearedId !== conversationId) return;
        // clearMessages(), not setMessages([]): it also aborts the fetch that
        // may be in flight. Emptying the array alone leaves that request to
        // land afterwards and re-fill the panel with messages the server no
        // longer has - the wipe undone by its own initial load. It additionally
        // nulls `currentLoadingConversationRef`, so anything that survives the
        // abort is dropped by the hook's own stale-result guard.
        //
        // NOT unit-covered, and the attempt is worth recording: a test that
        // held a load open, cleared, then resolved it passed with
        // `setMessages([])` too, so it did not distinguish the two and was
        // removed rather than kept as false assurance. Proving this needs a
        // harness that can observe the abort itself.
        clearMessages();
        // And the live bubble, which is drawn OUTSIDE the message list: without
        // this, wiping a conversation while it is answering leaves a streaming
        // reply hanging over an empty transcript.
        //
        // Not unit-covered, deliberately: driving this reducer from a test means
        // driving the whole websocket -> detectStreamEventType -> mapV2EventToV1
        // stack, and a test that mocks all three would be asserting its own
        // mocks. The reducer's RESET case is the same one `message_added`
        // already uses a few lines below.
        dispatchStreaming({ type: 'RESET' });
      }),
    [conversationId, clearMessages],
  );

  const handleLoadOlderMessages = useCallback(() => {
    loadOlderMessages(conversationId);
  }, [conversationId, loadOlderMessages]);

  // Auto-scroll helper
  const scrollToBottom = useCallback((smooth = true) => {
    requestAnimationFrame(() => {
      scrollRef.current?.scrollTo({
        top: scrollRef.current.scrollHeight,
        behavior: smooth ? 'smooth' : 'auto',
      });
    });
  }, []);

  // Scroll once on open - skips subsequent calls until reset
  const scrollToBottomOnce = useCallback((smooth = true) => {
    if (hasAutoScrolledRef.current) return;
    hasAutoScrolledRef.current = true;
    scrollToBottom(smooth);
  }, [scrollToBottom]);

  // Load initial messages (page 0 = 10 most recent, DESC server-side, sorted ASC by the hook).
  useEffect(() => {
    dispatchStreaming({ type: 'RESET' });
    hasAutoScrolledRef.current = false;
    loadMessages(conversationId).then(() => scrollToBottom(false));
  }, [conversationId, executionId, loadMessages, scrollToBottom]);

  // Re-read the thread from the DB without disturbing it. Five callers, all reconciliations of
  // a panel that is already showing this conversation: the stream finishing, the stream being
  // stopped or erroring, a workflow agent completing, the delayed catch-up that covers a
  // missed subscription, and a WebSocket reconnect. SILENT for all: none of them may raise the spinner, reset the
  // pagination or - worst of all - clear the transcript when the fetch fails. An unchanged
  // thread reconciles to the same array, so the only visible effect left is the persisted
  // message appearing.
  const reloadMessages = useCallback(async () => {
    try {
      await loadMessages(conversationId, undefined, { silent: true });
    } catch (err) {
      // Nothing to show: the transcript on screen is untouched and the next reload reconciles.
      console.warn('[ConversationPanelContent] Reconciliation failed:', err);
      return;
    }
    scrollToBottom();
  }, [conversationId, loadMessages, scrollToBottom]);

  // WebSocket event handler - handles both streaming events and message_added
  const onWsEvent = useCallback((eventType: string, data: unknown) => {
    const payload = data as Record<string, unknown>;

    // ── Handle persisted message events (existing behavior) ──
    if (eventType === 'message_added') {
      const msg = (payload as any)?.message;
      if (!msg?.id || !msg?.role) return;

      // When a message is persisted, clear streaming state and add the message.
      // Sort defensively - WS delivery is typically in send-order, but out-of-order
      // arrival (network reorder, replay) would otherwise leave the display
      // non-chronological until the next reloadMessages().
      dispatchStreaming({ type: 'RESET' });
      setMessages(prev => {
        if (prev.some(m => m.id === msg.id)) return prev;
        return sortMessagesByTime([...prev, msg as Message]);
      });
      scrollToBottom();
      return;
    }

    // ── Handle streaming events (from ConversationEventPublisher) ──
    const detectedType = detectStreamEventType(payload);
    const mapped = mapV2EventToV1(payload, detectedType, null);

    switch (mapped.type) {
      case 'stream_id': {
        // stream_started - reset scroll flag and scroll to bottom once
        const streamId = (mapped as any).streamId || (payload.streamId as string) || '';
        hasAutoScrolledRef.current = false;
        dispatchStreaming({ type: 'STREAM_STARTED', streamId });
        scrollToBottom(false);
        break;
      }

      case 'content': {
        if (mapped.content) {
          const isReplay = !!(payload as any).replay;
          dispatchStreaming({ type: 'CONTENT', chunk: mapped.content, replay: isReplay });
          setStreamingCounter(c => c + 1);
          scrollToBottomOnce();
        }
        break;
      }

      case 'tool_call': {
        const toolId = mapped.toolId || `tool-${Date.now()}`;
        const toolName = mapped.toolName || 'unknown';

        // Extract arguments
        let rawArgs: string | undefined;
        let thinkingMsg: string | undefined;
        if (mapped.arguments) {
          if (typeof mapped.arguments === 'object') {
            const argObj = mapped.arguments as Record<string, unknown>;
            rawArgs = typeof argObj.raw === 'string' ? argObj.raw
              : JSON.stringify(mapped.arguments);
            thinkingMsg = typeof argObj.thinking === 'string' ? argObj.thinking : undefined;
          } else if (typeof mapped.arguments === 'string') {
            rawArgs = mapped.arguments;
          }
        }

        dispatchStreaming({ type: 'TOOL_CALL', toolName, toolId, arguments: rawArgs, thinkingMessage: thinkingMsg });
        scrollToBottomOnce();
        break;
      }

      case 'tool_result': {
        const toolId = mapped.toolId || '';
        dispatchStreaming({
          type: 'TOOL_RESULT',
          toolId,
          toolName: mapped.toolName,
          success: mapped.success ?? true,
          durationMs: mapped.durationMs,
          error: mapped.error,
          resultId: mapped.resultId,
        });
        scrollToBottomOnce();
        break;
      }

      case 'done': {
        dispatchStreaming({ type: 'COMPLETED' });
        // Reload from DB to get the persisted assistant message
        reloadMessages();
        break;
      }

      case 'stopped':
      case 'error': {
        dispatchStreaming({ type: 'ERROR' });
        reloadMessages();
        break;
      }

      case 'thinking': {
        // Thinking event - scroll once
        scrollToBottomOnce(false);
        break;
      }

      // Sub-agent forwarded events
      default: {
        if (detectedType === 'sub_agent_started') {
          const subAgent = payload.subAgent as SubAgentMeta | undefined;
          if (subAgent) {
            dispatchStreaming({ type: 'SUB_AGENT_STARTED', subAgent });
            scrollToBottomOnce();
          }
        } else if (detectedType === 'sub_agent_tool_call') {
          const subAgent = payload.subAgent as SubAgentMeta | undefined;
          if (subAgent) {
            dispatchStreaming({
              type: 'SUB_AGENT_TOOL_CALL',
              subAgent,
              toolName: (payload.toolName as string) || 'unknown',
              toolId: (payload.toolId as string) || `sub-${Date.now()}`,
            });
            scrollToBottomOnce();
          }
        } else if (detectedType === 'sub_agent_tool_result') {
          const subAgent = payload.subAgent as SubAgentMeta | undefined;
          if (subAgent) {
            dispatchStreaming({
              type: 'SUB_AGENT_TOOL_RESULT',
              subAgent,
              toolId: (payload.toolId as string) || '',
              toolName: payload.toolName as string | undefined,
              success: (payload.success as boolean) ?? true,
              durationMs: payload.durationMs as number | undefined,
            });
            scrollToBottomOnce();
          }
        } else if (detectedType === 'sub_agent_completed') {
          const subAgent = payload.subAgent as SubAgentMeta | undefined;
          if (subAgent) {
            dispatchStreaming({
              type: 'SUB_AGENT_COMPLETED',
              subAgent,
              success: (payload.success as boolean) ?? true,
            });
          }
        } else if (detectedType === 'sub_agent_content') {
          const subAgent = payload.subAgent as SubAgentMeta | undefined;
          if (subAgent && payload.content) {
            dispatchStreaming({
              type: 'SUB_AGENT_CONTENT',
              subAgent,
              content: payload.content as string,
            });
            scrollToBottomOnce();
          }
        } else if (detectedType === 'sub_agent_thinking') {
          const subAgent = payload.subAgent as SubAgentMeta | undefined;
          if (subAgent && payload.thinking) {
            dispatchStreaming({
              type: 'SUB_AGENT_THINKING',
              subAgent,
              thinking: payload.thinking as string,
            });
            scrollToBottomOnce();
          }
        }
        break;
      }
    }
  }, [scrollToBottom, scrollToBottomOnce, reloadMessages]);

  useConversationChannel(conversationId, onWsEvent);

  // A WebSocket reconnect. The thread is re-read silently, which shows whatever was saved while
  // the socket was down. The live bubble is another matter: it was built from events the dead
  // session may have lost, the `done` above all, and the resubscribe's snapshot cannot always
  // replay that (the server drops a stream's state 30 s after it ends). But clearing it on every
  // reconnect emptied turns still running: the snapshot replays text, not thinking, sub-agent
  // activity or a card the turn is waiting on, and reconnects come every 20-60 s on a busy
  // socket. So the bubble goes only on proof the turn is over, and is still the one on screen
  // when the proof lands:
  //  - the server names THIS stream, in a finished state;
  //  - or it names no stream (or another one) AND the saved thread ends with the reply. "No
  //    stream" alone proves nothing: every run through the bridge (workflow agent nodes,
  //    sub-agents, CLI models) never registers its stream, so the server answers that while
  //    the run is very much alive. Same rule as StreamingContext's resync.
  // An unreadable answer is not proof either: the bubble stays, the channel may still deliver.
  // Only the reconnect half of the shared hook is used: this panel follows its channel itself,
  // and its handler above already re-reads on done, stopped and error.
  const streamingRef = useRef(streaming);
  streamingRef.current = streaming;
  useConversationResync(conversationId, async () => {
    void reloadMessages();
    const shown = streamingRef.current;
    if (!shown.isStreaming && !shown.content && shown.toolActivities.length === 0) return;
    let over: boolean;
    try {
      const server = await unifiedApiService.getStreamReconnectionState(conversationId);
      if (shown.streamId && server?.streamId === shown.streamId) {
        over = !isServerStreamLive(server, shown.streamId);
      } else {
        over = threadEndsWithReply(await conversationApi.getRecentMessagesAsc(conversationId, 5));
      }
    } catch (err) {
      console.warn('[ConversationPanelContent] Could not tell whether the turn is over after a reconnect:', err);
      return;
    }
    if (!over || streamingRef.current.streamId !== shown.streamId) return;
    dispatchStreaming({ type: 'RESET' });
  });

  // ── Fallback: reload messages when workflow agent completes ──
  // Handles race condition where WS subscription was established after
  // streaming events were already published to Redis.
  const hasReceivedStreamingRef = useRef(false);

  // Track whether we've received any streaming events
  useEffect(() => {
    if (streaming.isStreaming || streaming.content) {
      hasReceivedStreamingRef.current = true;
    }
  }, [streaming.isStreaming, streaming.content]);

  // Listen for workflowAgentCompleted → reload messages from DB
  useEffect(() => {
    const handler = () => {
      reloadMessages();
    };
    window.addEventListener('workflowAgentCompleted', handler);
    return () => window.removeEventListener('workflowAgentCompleted', handler);
  }, [reloadMessages]);

  // Delayed auto-reload: if no streaming events arrive within 5s, reload
  // to catch messages persisted during the subscription gap
  useEffect(() => {
    const timer = setTimeout(() => {
      if (!hasReceivedStreamingRef.current) {
        reloadMessages();
      }
    }, 5000);
    return () => clearTimeout(timer);
  }, [conversationId, reloadMessages]);

  if (messagesLoading && messages.length === 0) {
    return (
      <div className="flex items-center justify-center h-full">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-theme-secondary gap-2">
        <MessageSquare className="w-8 h-8 opacity-50" />
        <span className="text-sm">{error}</span>
      </div>
    );
  }

  if (messages.length === 0 && !streaming.isStreaming) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-theme-secondary gap-2">
        <MessageSquare className="w-8 h-8 opacity-50" />
        <span className="text-sm">No messages yet</span>
      </div>
    );
  }

  return (
    <div ref={scrollRef} className="flex flex-col h-full overflow-y-auto py-4 space-y-4">
      <div className="mx-auto max-w-4xl w-full px-2">
        <MessageHistory
          messages={messages}
          hideWorkflowToggle
          hideDataSourceToggle
          isStreaming={streaming.isStreaming}
          streamingMessage={streaming.content || undefined}
          streamingCounter={streamingCounter}
          toolActivities={streaming.toolActivities}
          hasMoreMessages={hasMoreMessages}
          loadingOlderMessages={loadingOlderMessages}
          onLoadOlderMessages={handleLoadOlderMessages}
          scrollContainerRef={scrollRef}
        />
      </div>
    </div>
  );
}

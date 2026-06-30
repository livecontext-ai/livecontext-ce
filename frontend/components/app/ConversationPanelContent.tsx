'use client';

import React, { useState, useEffect, useCallback, useRef, useReducer } from 'react';
import { MessageHistory } from '@/components/chat/MessageHistory';
import { type Message } from '@/lib/api/conversationApi';
import { useMessages } from '@/hooks/conversation/useMessages';
import { sortMessagesByTime } from '@/lib/utils/messageUtils';
import { useConversationChannel } from '@/lib/websocket/use-conversation-channel';
import { detectStreamEventType, mapV2EventToV1 } from '@/lib/streaming/streamHelpers';
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
  } = useMessages({ executionId });
  const scrollRef = useRef<HTMLDivElement>(null);
  const [streaming, dispatchStreaming] = useReducer(streamingReducer, initialStreamingState);
  const [streamingCounter, setStreamingCounter] = useState(0);
  const hasAutoScrolledRef = useRef(false);

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

  // Reload messages from DB (after stream completes, to get the persisted assistant message).
  // Resets to page 0 - the persisted message is always in the newest batch.
  const reloadMessages = useCallback(async () => {
    await loadMessages(conversationId);
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

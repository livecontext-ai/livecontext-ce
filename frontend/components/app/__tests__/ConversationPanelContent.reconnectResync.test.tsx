// @vitest-environment jsdom
/**
 * The agent conversation panel after the WebSocket session comes back.
 *
 * The panel draws a live bubble from its own conversation channel. Anything published while the
 * tab had no session is gone, the `done` included, and the server drops a stream's state 30 s
 * after it ends, so the resubscribe's snapshot cannot always replay it: the panel then showed a
 * reply "still streaming" forever, above a transcript that never got the saved answer. On a
 * reconnect it now re-reads the thread silently, and drops the bubble only on proof the turn is
 * over: the server names THIS stream in a finished state, or names none and the saved thread
 * ends with the reply. Clearing on anything less emptied turns still running: the snapshot
 * replays text, not thinking, sub-agent activity or a card being waited on, and a run through
 * the bridge (workflow agent nodes, sub-agents, CLI models) never registers its stream at all.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';

const h = vi.hoisted(() => ({
  loadMessages: vi.fn((..._args: unknown[]) => Promise.resolve()),
  channelHandler: null as null | ((eventType: string, data: unknown) => void),
  reconnectListener: null as null | (() => void),
  getStreamReconnectionState: vi.fn(async (..._args: unknown[]) => ({} as Record<string, unknown>)),
  getRecentMessagesAsc: vi.fn(async (..._args: unknown[]) => [] as unknown[]),
}));

vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: { getRecentMessagesAsc: (...args: unknown[]) => h.getRecentMessagesAsc(...args) },
}));

vi.mock('@/lib/api', () => ({
  unifiedApiService: { getStreamReconnectionState: (...args: unknown[]) => h.getStreamReconnectionState(...args) },
}));

vi.mock('@/hooks/conversation/useMessages', () => ({
  useMessages: () => ({
    messages: [{ id: 'm1', role: 'user', content: 'Where is my order?' }],
    messagesLoading: false,
    hasMoreMessages: false,
    loadingOlderMessages: false,
    error: null,
    loadMessages: h.loadMessages,
    loadOlderMessages: vi.fn(() => Promise.resolve()),
    setMessages: vi.fn(),
    clearMessages: vi.fn(),
  }),
}));
vi.mock('@/lib/websocket/use-conversation-channel', () => ({
  useConversationChannel: (_id: string, onEvent: (eventType: string, data: unknown) => void) => {
    h.channelHandler = onEvent;
  },
}));
vi.mock('@/lib/websocket', () => ({
  useWsReconnected: (callback: () => void) => { h.reconnectListener = callback; },
}));
// The history reports the live bubble it is handed.
vi.mock('@/components/chat/MessageHistory', () => ({
  MessageHistory: ({ streamingMessage, isStreaming, toolActivities }: { streamingMessage?: string; isStreaming?: boolean; toolActivities?: unknown[] }) => (
    <div data-testid="messages" data-streaming={String(!!isStreaming)} data-tools={toolActivities?.length ?? 0}>
      {streamingMessage && <div data-testid="live-bubble">{streamingMessage}</div>}
    </div>
  ),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { ConversationPanelContent } from '../ConversationPanelContent';

beforeEach(() => {
  h.loadMessages.mockClear();
  h.getStreamReconnectionState.mockReset();
  // What the server answers for a run it does not track (every run through the bridge).
  h.getStreamReconnectionState.mockResolvedValue({ hasActiveStream: false, conversationId: 'conv-agent' });
  h.getRecentMessagesAsc.mockReset();
  h.getRecentMessagesAsc.mockResolvedValue([{ id: 'm1', role: 'user', content: 'Where is my order?' }]);
  h.channelHandler = null;
  h.reconnectListener = null;
  // jsdom has no scrollTo; the panel scrolls to the bottom after every load and chunk.
  Element.prototype.scrollTo = vi.fn() as unknown as typeof Element.prototype.scrollTo;
});
afterEach(cleanup);

describe('the agent conversation panel on a WebSocket reconnect', () => {
  async function showLiveTurn() {
    render(<ConversationPanelContent conversationId="conv-agent" />);
    await act(async () => {
      h.channelHandler!('stream_started', { streamId: 'sid-1', conversationId: 'conv-agent', model: 'm' });
      h.channelHandler!('content', { streamId: 'sid-1', content: 'Your order ' });
      h.channelHandler!('tool_call', { streamId: 'sid-1', toolName: 'agent', toolId: 't-1', arguments: '{}' });
    });
    expect(screen.getByTestId('live-bubble')).toHaveTextContent('Your order');
    h.loadMessages.mockClear();
  }

  it('drops a bubble whose stream the server says is over, and re-reads the saved thread silently', async () => {
    await showLiveTurn();
    h.getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: false, state: 'COMPLETED', streamId: 'sid-1' });

    await act(async () => { h.reconnectListener!(); });

    expect(h.getStreamReconnectionState).toHaveBeenCalledWith('conv-agent');
    expect(screen.queryByTestId('live-bubble')).not.toBeInTheDocument();
    expect(screen.getByTestId('messages')).toHaveAttribute('data-streaming', 'false');
    expect(h.loadMessages).toHaveBeenCalledWith('conv-agent', undefined, { silent: true });
  });

  it('keeps the bubble of a turn the server still runs (its thinking and sub-agent work are not replayed)', async () => {
    await showLiveTurn();
    h.getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: true, state: 'STREAMING', streamId: 'sid-1' });

    await act(async () => { h.reconnectListener!(); });

    expect(screen.getByTestId('live-bubble')).toHaveTextContent('Your order');
    expect(screen.getByTestId('messages')).toHaveAttribute('data-streaming', 'true');
    expect(screen.getByTestId('messages')).toHaveAttribute('data-tools', '1');
    expect(h.loadMessages).toHaveBeenCalledWith('conv-agent', undefined, { silent: true });
  });

  it('keeps the bubble when the stream state cannot be read', async () => {
    await showLiveTurn();
    h.getStreamReconnectionState.mockRejectedValueOnce(new Error('503'));

    await act(async () => { h.reconnectListener!(); });

    expect(screen.getByTestId('live-bubble')).toHaveTextContent('Your order');
  });

  it('re-reads even when no stream was live (a whole reply can land while disconnected), without asking for a stream state', async () => {
    render(<ConversationPanelContent conversationId="conv-agent" />);
    await act(async () => {});
    h.loadMessages.mockClear();

    await act(async () => { h.reconnectListener!(); });

    expect(h.loadMessages).toHaveBeenCalledWith('conv-agent', undefined, { silent: true });
    expect(h.getStreamReconnectionState).not.toHaveBeenCalled();
  });

  it('keeps the bubble and tool cards of a bridge or CLI run the server does not track', async () => {
    // The server answers "no stream" for the whole life of such a run; the saved thread has no
    // reply yet. Pre-fix the panel wiped the bubble and its tool cards on every reconnect.
    await showLiveTurn();

    await act(async () => { h.reconnectListener!(); });

    expect(h.getRecentMessagesAsc).toHaveBeenCalledWith('conv-agent', 5);
    expect(screen.getByTestId('live-bubble')).toHaveTextContent('Your order');
    expect(screen.getByTestId('messages')).toHaveAttribute('data-streaming', 'true');
    expect(screen.getByTestId('messages')).toHaveAttribute('data-tools', '1');
  });

  it('drops the bubble on "no stream" once the saved thread ends with the reply', async () => {
    await showLiveTurn();
    h.getRecentMessagesAsc.mockResolvedValueOnce([
      { id: 'm1', role: 'user', content: 'Where is my order?' },
      { id: 'm2', role: 'assistant', content: 'Your order shipped on Monday.' },
    ]);

    await act(async () => { h.reconnectListener!(); });

    expect(screen.queryByTestId('live-bubble')).not.toBeInTheDocument();
  });

  it('lets the snapshot of a still-running turn update the bubble it kept', async () => {
    await showLiveTurn();
    h.getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: true, state: 'STREAMING', streamId: 'sid-1' });
    await act(async () => { h.reconnectListener!(); });

    // What the resubscribe's snapshot sends for a stream still running: start (same stream, so
    // no reset), then the whole text so far as a replay.
    await act(async () => {
      h.channelHandler!('stream_started', { streamId: 'sid-1', conversationId: 'conv-agent', model: 'm' });
      h.channelHandler!('content', { streamId: 'sid-1', content: 'Your order shipped', replay: true });
    });

    expect(screen.getByTestId('live-bubble')).toHaveTextContent('Your order shipped');
    expect(screen.getByTestId('messages')).toHaveAttribute('data-tools', '1');
  });

  it('keeps re-reading on a live error, and keeps listening after it', async () => {
    // Already the panel's behaviour; pinned here because the reported bug is exactly an error
    // on an agent turn whose reply was saved anyway.
    render(<ConversationPanelContent conversationId="conv-agent" />);
    await act(async () => {
      h.channelHandler!('stream_started', { streamId: 'sid-1', conversationId: 'conv-agent', model: 'm' });
    });
    h.loadMessages.mockClear();

    await act(async () => {
      h.channelHandler!('error', { streamId: 'sid-1', error: 'bridge link failed', errorCode: 'STREAM_ERROR' });
    });
    expect(h.loadMessages).toHaveBeenCalledWith('conv-agent', undefined, { silent: true });

    await act(async () => {
      h.channelHandler!('content', { streamId: 'sid-1', content: 'Retried on the API' });
    });
    expect(screen.getByTestId('live-bubble')).toHaveTextContent('Retried on the API');
  });
});

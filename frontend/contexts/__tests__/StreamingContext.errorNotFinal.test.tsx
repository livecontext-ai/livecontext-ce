/**
 * @vitest-environment jsdom
 *
 * Regression tests for "the agent finished answering but the reply is not in the chat".
 *
 * Production had the replies (190 of 191 completed chat streams in a week had their assistant
 * row saved), so the LIVE view lost them, two ways:
 *
 *  1. An `error` event was treated as the end of the turn. It flipped the entry to 'error',
 *     which dropped the conversation from the live channel set: the content and the `done`
 *     that followed under the SAME stream (an execution-link fallback publishes `error`, then
 *     retries the turn on the direct API with that stream id) were never heard, and nothing
 *     re-read the saved reply.
 *  2. A `done` published while the tab had no WebSocket session is gone for good (the gateway
 *     keeps no backlog), and nothing re-read the stream's state when the session came back,
 *     so the page streamed forever over a reply saved long ago.
 *
 * The subscription harness here is REAL about teardown: an unsubscribe removes the handler, so
 * an event published after the channel was dropped is genuinely never delivered.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React, { ReactNode } from 'react';
import { renderHook, act } from '@testing-library/react';

const sendChatMessageWs = vi.fn<(...args: unknown[]) => Promise<{ conversationId: string; streamId: string }>>();
const getStreamReconnectionState = vi.fn<(...args: unknown[]) => Promise<unknown>>();

vi.mock('@/lib/api', () => ({
  unifiedApiService: {
    sendChatMessageWs: (...args: unknown[]) => sendChatMessageWs(...args),
    stopStream: vi.fn(async () => {}),
    getActiveStreamingConversations: vi.fn(async () => []),
    getStreamStatus: vi.fn(async () => ({ hasActiveStream: false })),
    getStreamReconnectionState: (...args: unknown[]) => getStreamReconnectionState(...args),
  },
}));
const getRecentMessagesAsc = vi.fn<(...args: unknown[]) => Promise<unknown[]>>();
vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: { getRecentMessagesAsc: (...args: unknown[]) => getRecentMessagesAsc(...args) },
}));
vi.mock('@/lib/api/error-utils', () => ({ is402Error: () => false, is413StorageError: () => false, isRestrictedDataRefusal: () => false }));
vi.mock('@/lib/billing/ceRelayErrorModals', () => ({ handleCeRelayError: () => false }));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ showInsufficientCreditsModal: vi.fn() }));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({ showInsufficientStorageModal: vi.fn() }));
vi.mock('@/components/billing/MissingApiKeyModal', () => ({ showMissingApiKeyModal: vi.fn() }));
const { showAgentErrorModal } = vi.hoisted(() => ({ showAgentErrorModal: vi.fn() }));
vi.mock('@/components/billing/AgentErrorModal', () => ({ showAgentErrorModal }));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isReady: true, isAuthenticated: true }) }));
vi.mock('@/hooks/useModels', () => ({
  getModelsCache: () => [],
  getEffectiveDefaultModel: () => 'gpt-4',
  getEffectiveDefaultProvider: () => 'openai',
}));

// Live subscriptions: a handler is reachable only while its subscription is mounted.
const liveHandlers = new Map<string, Set<(raw: unknown) => void>>();
const subscribe = vi.fn((channel: string, handler: (raw: unknown) => void) => {
  const set = liveHandlers.get(channel) ?? new Set();
  set.add(handler);
  liveHandlers.set(channel, set);
  return () => { set.delete(handler); };
});
// wsClient.onReconnected: the tests fire these to simulate a session coming back.
const reconnectListeners = new Set<() => void>();
const onReconnected = (listener: () => void) => {
  reconnectListeners.add(listener);
  return () => { reconnectListeners.delete(listener); };
};
// Factories are hoisted above this module's own code, so they reach the spies lazily.
vi.mock('@/lib/websocket', () => ({
  wsClient: {
    subscribe: (c: string, h: (raw: unknown) => void) => subscribe(c, h),
    onReconnected: (l: () => void) => onReconnected(l),
  },
}));
vi.mock('@/lib/websocket/ws-client', () => ({
  wsClient: {
    subscribe: (c: string, h: (raw: unknown) => void) => subscribe(c, h),
    onReconnected: (l: () => void) => onReconnected(l),
  },
}));

import { StreamingProvider, useStreaming, ERRORED_STREAM_RECHECK_MS } from '../StreamingContext';

const wrapper = ({ children }: { children: ReactNode }) => <StreamingProvider>{children}</StreamingProvider>;

const publish = (conversationId: string, payload: Record<string, unknown>) => {
  Array.from(liveHandlers.get(`conversation:${conversationId}`) ?? []).forEach((h) => h(payload));
};
const isSubscribed = (conversationId: string) => (liveHandlers.get(`conversation:${conversationId}`)?.size ?? 0) > 0;
const reconnect = () => Array.from(reconnectListeners).forEach((l) => l());

async function mountAndSend(callbacks: Record<string, unknown> = {}) {
  const { result } = renderHook(() => useStreaming(), { wrapper });
  await act(async () => {});
  sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-1' });
  await act(async () => {
    await result.current.sendMessage(
      { message: 'hi', model: 'gpt-4', provider: 'openai', conversationId: 'conv-a' },
      callbacks,
    );
  });
  return result;
}

const markers = (s: ReturnType<typeof useStreaming>) =>
  (s.getStreamState('conv-a')?.toolActivities ?? []).filter((a) => a.toolName === '_system_error');

describe('StreamingContext - an error is not the end of the turn', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    liveHandlers.clear();
    reconnectListeners.clear();
    getStreamReconnectionState.mockReset();
    getRecentMessagesAsc.mockReset();
  });

  it('keeps listening after an error, so the fallback reply and its done still land (execution-link fallback)', async () => {
    const onStreamComplete = vi.fn();
    const streaming = await mountAndSend({ onStreamComplete });
    await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'Checking' }); });

    await act(async () => {
      publish('conv-a', { streamId: 'sid-1', error: 'bridge execution link failed', errorCode: 'STREAM_ERROR', retryable: true });
    });
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('error');
    // The streamed text is kept, and so is the channel (pre-fix: dropped right here).
    expect(streaming.current.getStreamContent('conv-a')).toBe('Checking');
    expect(isSubscribed('conv-a')).toBe(true);

    // The direct-API retry goes on under the same stream.
    await act(async () => { publish('conv-a', { streamId: 'sid-1', content: ' the order.' }); });
    const revived = streaming.current.getStreamState('conv-a');
    expect(revived?.status).toBe('streaming');
    expect(revived?.error).toBeNull();
    expect(markers(streaming.current)).toHaveLength(0);

    await act(async () => {
      publish('conv-a', { streamId: 'sid-1', fullContent: 'The order shipped.', totalTokens: 12 });
    });
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('completed');
    expect(streaming.current.getStreamContent('conv-a')).toBe('The order shipped.');
    expect(onStreamComplete).toHaveBeenCalledWith('conv-a', 'The order shipped.', 'gpt-4');
  });

  it('hands the conversation id to onError so the page re-reads a reply saved despite the error', async () => {
    const onError = vi.fn();
    await mountAndSend({ onError });

    await act(async () => {
      publish('conv-a', { streamId: 'sid-1', error: 'adapter hiccup', errorCode: 'STREAM_ERROR', retryable: true });
    });

    expect(onError).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'adapter hiccup' }),
      'conv-a',
    );
    // The failure is still announced: the modal stays the error surface.
    expect(showAgentErrorModal).toHaveBeenCalledWith({ message: 'adapter hiccup', code: 'STREAM_ERROR' });
  });

  it('does not stack a second Error marker when the kept channel replays the failure', async () => {
    const streaming = await mountAndSend();

    await act(async () => {
      publish('conv-a', { streamId: 'sid-1', error: 'HTTP 400', errorCode: 'STREAM_ERROR' });
    });
    await act(async () => {
      publish('conv-a', { streamId: 'sid-1', error: 'Stream ended with error', errorCode: 'SNAPSHOT_REPLAY' });
    });

    expect(markers(streaming.current)).toHaveLength(1);
    expect(showAgentErrorModal).toHaveBeenCalledTimes(1);
  });

  it('is not revived by a stray tool or thinking event after a real error', async () => {
    // agent-service's shutdown interruption publishes `error` while the loop may still emit
    // one more event: reviving on it left the chat "streaming" forever.
    const streaming = await mountAndSend();
    await act(async () => {
      publish('conv-a', { streamId: 'sid-1', error: 'Interrupted by shutdown', errorCode: 'STREAM_ERROR' });
    });

    await act(async () => {
      publish('conv-a', { streamId: 'sid-1', toolName: 'catalog', toolId: 't-9', arguments: '{}' });
      publish('conv-a', { streamId: 'sid-1', thinking: 'wrapping up' });
    });

    expect(streaming.current.getStreamState('conv-a')?.status).toBe('error');
    expect(streaming.current.isStreamingConversation('conv-a')).toBe(false);
  });

  it('re-checks a revived stream with the server and settles it back to error when the error was real', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const streaming = await mountAndSend();
      await act(async () => {
        publish('conv-a', { streamId: 'sid-1', error: 'Interrupted by shutdown', errorCode: 'STREAM_ERROR' });
      });
      // A stray chunk of the dying loop revives the entry...
      await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'one last chunk' }); });
      expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
      getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: false, state: 'ERROR', streamId: 'sid-1', content: '' });

      // ...and the re-check a few seconds later settles it.
      await act(async () => { await vi.advanceTimersByTimeAsync(ERRORED_STREAM_RECHECK_MS); });

      expect(getStreamReconnectionState).toHaveBeenCalledWith('conv-a');
      expect(streaming.current.getStreamState('conv-a')?.status).toBe('error');
      // The failure was already announced once: the re-check does not open the modal again.
      expect(showAgentErrorModal).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps a revived stream streaming when the re-check finds it really running', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const streaming = await mountAndSend();
      await act(async () => {
        publish('conv-a', { streamId: 'sid-1', error: 'bridge link failed', errorCode: 'STREAM_ERROR' });
      });
      await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'Retried' }); });
      getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: true, state: 'STREAMING', streamId: 'sid-1', content: 'Retried on the API' });

      await act(async () => { await vi.advanceTimersByTimeAsync(ERRORED_STREAM_RECHECK_MS); });

      expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
      expect(streaming.current.getStreamContent('conv-a')).toBe('Retried on the API');
    } finally {
      vi.useRealTimers();
    }
  });

  it('still ignores an error from ANOTHER stream of the conversation (cross-stream guard intact)', async () => {
    const onError = vi.fn();
    const streaming = await mountAndSend({ onError });

    await act(async () => {
      publish('conv-a', { streamId: 'sid-old', error: 'late failure of a previous turn', errorCode: 'STREAM_ERROR' });
    });

    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
    expect(onError).not.toHaveBeenCalled();
  });
});

describe('StreamingContext - resync of live streams after a WebSocket reconnect', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    liveHandlers.clear();
    reconnectListeners.clear();
    getStreamReconnectionState.mockReset();
    getRecentMessagesAsc.mockReset();
  });

  it('recovers a done missed while the socket was down: completes with the saved text and tells the page', async () => {
    const onStreamComplete = vi.fn();
    const streaming = await mountAndSend({ onStreamComplete });
    await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'Half an ans' }); });
    getStreamReconnectionState.mockResolvedValueOnce({
      hasActiveStream: false, state: 'COMPLETED', streamId: 'sid-1', content: 'Half an answer, then the rest.',
    });

    await act(async () => { reconnect(); });

    expect(getStreamReconnectionState).toHaveBeenCalledWith('conv-a');
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('completed');
    expect(streaming.current.getStreamContent('conv-a')).toBe('Half an answer, then the rest.');
    expect(onStreamComplete).toHaveBeenCalledWith('conv-a', 'Half an answer, then the rest.', 'gpt-4');
  });

  it('keeps a still-running stream streaming, with the text published while disconnected', async () => {
    const onStreamComplete = vi.fn();
    const streaming = await mountAndSend({ onStreamComplete });
    await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'Step 1' }); });
    getStreamReconnectionState.mockResolvedValueOnce({
      hasActiveStream: true, state: 'STREAMING', streamId: 'sid-1', content: 'Step 1, step 2',
    });

    await act(async () => { reconnect(); });

    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
    // Replaced, not appended: the buffer is the whole reply so far.
    expect(streaming.current.getStreamContent('conv-a')).toBe('Step 1, step 2');
    expect(onStreamComplete).not.toHaveBeenCalled();
  });

  it('brings an errored-but-listening entry back when the server says the turn is still running', async () => {
    const streaming = await mountAndSend();
    await act(async () => {
      publish('conv-a', { streamId: 'sid-1', error: 'bridge link failed', errorCode: 'STREAM_ERROR' });
    });
    getStreamReconnectionState.mockResolvedValueOnce({
      hasActiveStream: true, state: 'STREAMING', streamId: 'sid-1', content: 'Retried on the API',
    });

    await act(async () => { reconnect(); });

    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
    expect(streaming.current.getStreamContent('conv-a')).toBe('Retried on the API');
  });

  it('drops a stream the server no longer knows once the saved thread holds its reply', async () => {
    // Its final text is unknown, so the partial must not pose as the reply beside the saved one.
    const onStreamComplete = vi.fn();
    const streaming = await mountAndSend({ onStreamComplete });
    await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'partial' }); });
    getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: false, conversationId: 'conv-a' });
    getRecentMessagesAsc.mockResolvedValueOnce([
      { role: 'user', content: 'Where is my order?' },
      { role: 'assistant', content: 'It shipped on Monday.' },
    ]);

    await act(async () => { reconnect(); });

    expect(getRecentMessagesAsc).toHaveBeenCalledWith('conv-a', 5);
    expect(streaming.current.getStreamState('conv-a')).toBeNull();
    expect(onStreamComplete).toHaveBeenCalledWith('conv-a', 'partial', 'gpt-4');
  });

  it('leaves the stream alone when "no stream" is not backed by a saved reply (a scope answer, not an end)', async () => {
    // The endpoint also answers "no stream" for a conversation outside the strict workspace
    // scope. Settling on that dropped a turn that was still running, with its partial.
    const onStreamComplete = vi.fn();
    const streaming = await mountAndSend({ onStreamComplete });
    await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'Half' }); });
    getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: false, conversationId: 'conv-a' });
    getRecentMessagesAsc.mockResolvedValueOnce([{ role: 'user', content: 'Where is my order?' }]);

    await act(async () => { reconnect(); });

    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
    expect(streaming.current.getStreamContent('conv-a')).toBe('Half');
    expect(onStreamComplete).not.toHaveBeenCalled();
    // The channel is kept, so the turn's own end still lands.
    await act(async () => { publish('conv-a', { streamId: 'sid-1', fullContent: 'Half and whole.', totalTokens: 4 }); });
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('completed');
    expect(onStreamComplete).toHaveBeenCalledWith('conv-a', 'Half and whole.', 'gpt-4');
  });

  it('leaves the stream alone when the thread cannot be read either', async () => {
    const onStreamComplete = vi.fn();
    const streaming = await mountAndSend({ onStreamComplete });
    getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: false, conversationId: 'conv-a' });
    getRecentMessagesAsc.mockRejectedValueOnce(new Error('503'));

    await act(async () => { reconnect(); });

    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
    expect(onStreamComplete).not.toHaveBeenCalled();
  });

  it('announces a failure that happened while disconnected, once', async () => {
    const onError = vi.fn();
    const streaming = await mountAndSend({ onError });
    getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: false, state: 'ERROR', streamId: 'sid-1', content: '' });

    await act(async () => { reconnect(); });

    expect(streaming.current.getStreamState('conv-a')?.status).toBe('error');
    expect(showAgentErrorModal).toHaveBeenCalledTimes(1);
    expect(onError).toHaveBeenCalledWith(expect.anything(), 'conv-a');
  });

  it('stands down when a new send takes the conversation over while the resync request is out', async () => {
    const streaming = await mountAndSend();
    let answer!: (v: unknown) => void;
    getStreamReconnectionState.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));

    await act(async () => { reconnect(); });
    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-2' });
    await act(async () => {
      await streaming.current.sendMessage({ message: 'next', model: 'gpt-4', provider: 'openai', conversationId: 'conv-a' });
    });
    await act(async () => {
      answer({ hasActiveStream: false, state: 'COMPLETED', streamId: 'sid-1', content: 'old reply' });
    });

    const entry = streaming.current.getStreamState('conv-a');
    expect(entry?.streamId).toBe('sid-2');
    expect(entry?.status).toBe('streaming');
    expect(entry?.content).toBe('');
  });

  it('does not resync an entry whose send is still in flight (no stream id yet)', async () => {
    const { result } = renderHook(() => useStreaming(), { wrapper });
    await act(async () => {});
    sendChatMessageWs.mockImplementationOnce(() => new Promise(() => {}));
    act(() => {
      void result.current.sendMessage({ message: 'hi', model: 'gpt-4', provider: 'openai', conversationId: 'conv-a' });
    });

    await act(async () => { reconnect(); });

    expect(getStreamReconnectionState).not.toHaveBeenCalled();
    expect(result.current.getStreamState('conv-a')?.status).toBe('streaming');
  });
});

describe('StreamingContext - round trips and the end of an error', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    liveHandlers.clear();
    reconnectListeners.clear();
    getStreamReconnectionState.mockReset();
    getRecentMessagesAsc.mockReset();
  });

  it('keeps chunks that arrived while the reconnect read was in flight (the older REST text does not cut them out)', async () => {
    const streaming = await mountAndSend();
    await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'A' }); });
    let answer!: (v: unknown) => void;
    getStreamReconnectionState.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));

    await act(async () => { reconnect(); });
    await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'B' }); });
    // The server read was taken before chunk B.
    await act(async () => { answer({ hasActiveStream: true, state: 'STREAMING', streamId: 'sid-1', content: 'A' }); });
    await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'C' }); });

    expect(streaming.current.getStreamContent('conv-a')).toBe('ABC');
  });

  it('takes the REST text when it extends what is on screen (chunks lost while disconnected)', async () => {
    const streaming = await mountAndSend();
    await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'Step 1' }); });
    getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: true, state: 'STREAMING', streamId: 'sid-1', content: 'Step 1, step 2' });

    await act(async () => { reconnect(); });
    await act(async () => { publish('conv-a', { streamId: 'sid-1', content: ', step 3' }); });

    expect(streaming.current.getStreamContent('conv-a')).toBe('Step 1, step 2, step 3');
  });

  it('settles an error the server confirms: channel released, no more reconnect reads, turn reported over', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const onStreamComplete = vi.fn();
      const streaming = await mountAndSend({ onStreamComplete });
      await act(async () => { publish('conv-a', { streamId: 'sid-1', content: 'Checking' }); });
      await act(async () => {
        publish('conv-a', { streamId: 'sid-1', error: 'HTTP 400', errorCode: 'STREAM_ERROR' });
      });
      expect(isSubscribed('conv-a')).toBe(true);
      getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: false, state: 'ERROR', streamId: 'sid-1', content: '' });

      await act(async () => { await vi.advanceTimersByTimeAsync(ERRORED_STREAM_RECHECK_MS); });

      expect(streaming.current.getStreamState('conv-a')?.status).toBe('error');
      expect(streaming.current.getStreamState('conv-a')?.errorSettled).toBe(true);
      expect(isSubscribed('conv-a')).toBe(false);
      // Reported over like a stopped turn: the page re-reads, and a first message gets its route.
      expect(onStreamComplete).toHaveBeenCalledTimes(1);
      expect(onStreamComplete).toHaveBeenCalledWith('conv-a', 'Checking', 'gpt-4');
      expect(showAgentErrorModal).toHaveBeenCalledTimes(1);

      getStreamReconnectionState.mockClear();
      await act(async () => { reconnect(); });
      expect(getStreamReconnectionState).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps listening after an error while the server says the turn goes on', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const onStreamComplete = vi.fn();
      const streaming = await mountAndSend({ onStreamComplete });
      await act(async () => {
        publish('conv-a', { streamId: 'sid-1', error: 'bridge link failed', errorCode: 'STREAM_ERROR' });
      });
      getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: true, state: 'STREAMING', streamId: 'sid-1', content: '' });

      await act(async () => { await vi.advanceTimersByTimeAsync(ERRORED_STREAM_RECHECK_MS); });

      expect(streaming.current.getStreamState('conv-a')?.errorSettled).toBeUndefined();
      expect(isSubscribed('conv-a')).toBe(true);
      expect(onStreamComplete).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('settles an errored entry whose stream the server has forgotten ("no stream", no saved reply)', async () => {
    // This tab heard it fail; the server no longer knows the stream: nothing more will come.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const onStreamComplete = vi.fn();
      const streaming = await mountAndSend({ onStreamComplete });
      await act(async () => {
        publish('conv-a', { streamId: 'sid-1', error: 'HTTP 400', errorCode: 'STREAM_ERROR' });
      });
      getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: false, conversationId: 'conv-a' });
      getRecentMessagesAsc.mockResolvedValueOnce([{ role: 'user', content: 'Where is my order?' }]);

      await act(async () => { await vi.advanceTimersByTimeAsync(ERRORED_STREAM_RECHECK_MS); });

      expect(streaming.current.getStreamState('conv-a')?.errorSettled).toBe(true);
      expect(isSubscribed('conv-a')).toBe(false);
      expect(onStreamComplete).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('re-arms the re-check on a second error, and settles when the real failure lands after a "still running" answer', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const onStreamComplete = vi.fn();
      const streaming = await mountAndSend({ onStreamComplete });
      await act(async () => {
        publish('conv-a', { streamId: 'sid-1', error: 'bridge link failed', errorCode: 'STREAM_ERROR' });
      });
      // First re-check: the fallback is still running the turn.
      getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: true, state: 'STREAMING', streamId: 'sid-1', content: '' });
      await act(async () => { await vi.advanceTimersByTimeAsync(ERRORED_STREAM_RECHECK_MS); });
      expect(streaming.current.getStreamState('conv-a')?.errorSettled).toBeUndefined();

      // The real failure lands on the entry already in error.
      await act(async () => {
        publish('conv-a', { streamId: 'sid-1', error: 'direct API failed too', errorCode: 'STREAM_ERROR' });
      });
      getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: false, state: 'ERROR', streamId: 'sid-1', content: '' });
      await act(async () => { await vi.advanceTimersByTimeAsync(ERRORED_STREAM_RECHECK_MS); });

      expect(getStreamReconnectionState).toHaveBeenCalledTimes(2);
      expect(streaming.current.getStreamState('conv-a')?.errorSettled).toBe(true);
      expect(isSubscribed('conv-a')).toBe(false);
      expect(onStreamComplete).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('clears pending re-checks on unmount', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const { result, unmount } = renderHook(() => useStreaming(), { wrapper });
      await act(async () => {});
      sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-1' });
      await act(async () => {
        await result.current.sendMessage({ message: 'hi', model: 'gpt-4', provider: 'openai', conversationId: 'conv-a' }, {});
      });
      await act(async () => {
        publish('conv-a', { streamId: 'sid-1', error: 'HTTP 400', errorCode: 'STREAM_ERROR' });
      });

      unmount();
      await vi.advanceTimersByTimeAsync(ERRORED_STREAM_RECHECK_MS * 2);

      expect(getStreamReconnectionState).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('StreamingContext - resync branches for a turn that ended while the socket was down', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    liveHandlers.clear();
    reconnectListeners.clear();
    getStreamReconnectionState.mockReset();
    getRecentMessagesAsc.mockReset();
  });

  const approvalCard = JSON.stringify({
    streamId: 'sid-1',
    services: [{ serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail' }],
    reason: 'The agent needs Gmail to read the order mail.',
  });

  async function turnWithHeldTool(callbacks: Record<string, unknown> = {}) {
    const streaming = await mountAndSend(callbacks);
    await act(async () => {
      publish('conv-a', { streamId: 'sid-1', content: 'Reading your mail' });
      publish('conv-a', { streamId: 'sid-1', toolName: 'catalog', toolId: 't-held', arguments: '{}' });
    });
    return streaming;
  }
  const heldTool = (s: ReturnType<typeof useStreaming>) =>
    s.getStreamState('conv-a')?.toolActivities.find((a) => a.toolId === 't-held');

  it('settles a turn parked on an approval as stopped: the held tool stays pending and its card comes back', async () => {
    const onStreamComplete = vi.fn();
    const streaming = await turnWithHeldTool({ onStreamComplete });
    getStreamReconnectionState.mockResolvedValueOnce({
      hasActiveStream: false, state: 'AWAITING_APPROVAL', streamId: 'sid-1', content: 'Reading your mail', toolEvents: [approvalCard],
    });

    await act(async () => { reconnect(); });

    const entry = streaming.current.getStreamState('conv-a');
    expect(entry?.status).toBe('stopped');
    // Settled as `done`, the tool held for approval was drawn as succeeded.
    expect(heldTool(streaming.current)?.status).toBe('pending');
    expect(streaming.current.getPendingServiceApprovals('conv-a')).toHaveLength(1);
    expect(streaming.current.getPendingServiceApprovals('conv-a')[0].services[0].serviceType).toBe('gmail');
    expect(onStreamComplete).toHaveBeenCalledWith('conv-a', 'Reading your mail', 'gpt-4');
  });

  it.each(['STOPPED_BY_USER', 'INTERRUPTED'])('settles a %s turn as stopped, with its partial and its unfinished tools as they were', async (serverState) => {
    const onStreamComplete = vi.fn();
    const streaming = await turnWithHeldTool({ onStreamComplete });
    getStreamReconnectionState.mockResolvedValueOnce({
      hasActiveStream: false, state: serverState, streamId: 'sid-1', content: 'Reading your mail', toolEvents: [approvalCard],
    });

    await act(async () => { reconnect(); });

    expect(streaming.current.getStreamState('conv-a')?.status).toBe('stopped');
    expect(heldTool(streaming.current)?.status).toBe('pending');
    // Only a parked turn brings its cards back.
    expect(streaming.current.getPendingServiceApprovals('conv-a')).toHaveLength(0);
    expect(onStreamComplete).toHaveBeenCalledWith('conv-a', 'Reading your mail', 'gpt-4');
  });

  it('keeps a CREATED stream (started, nothing sent yet) streaming, untouched', async () => {
    const onStreamComplete = vi.fn();
    const streaming = await mountAndSend({ onStreamComplete });
    getStreamReconnectionState.mockResolvedValueOnce({ hasActiveStream: false, state: 'CREATED', streamId: 'sid-1', content: '' });

    await act(async () => { reconnect(); });

    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
    expect(streaming.current.getStreamContent('conv-a')).toBe('');
    expect(onStreamComplete).not.toHaveBeenCalled();
  });
});

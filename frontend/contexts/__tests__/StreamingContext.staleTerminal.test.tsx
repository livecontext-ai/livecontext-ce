/**
 * @vitest-environment jsdom
 *
 * Regression tests for the "Stop button disappears while the response is still
 * streaming" bug (reported on bridge conversations).
 *
 * Mechanism: MessageComposer shows Stop iff the conversation's stream entry has
 * status 'streaming' (isStreamingConversation). That status is one-way: once a
 * terminal action (COMPLETED / STOPPED / ERROR) lands, nothing re-raises it while
 * content chunks keep rendering (APPEND_CONTENT never touches the status). So any
 * STALE terminal - typically a slow checkAndReconnect resolving the PREVIOUS
 * stream's state after a fresh send already put a NEW stream live - flipped the
 * live entry to a terminal status: the Stop button vanished mid-stream.
 *
 * The fix is two-layered:
 *  1. A `generation` ownership token on the per-conversation StreamRefs:
 *     sendMessage bumps it on entry; checkAndReconnect bumps it on entry and
 *     re-validates after each await, aborting (no refs mutation, no dispatch)
 *     when a newer operation took over - including in its catch block.
 *  2. Terminal actions carry the streamId they belong to; the reducer ignores a
 *     terminal whose streamId differs from the live entry's (defense-in-depth,
 *     `isStaleTerminal`).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React, { ReactNode } from 'react';
import { renderHook, act } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks - declared before the provider import (vitest hoists vi.mock)
// ---------------------------------------------------------------------------

const sendChatMessageWs = vi.fn<(...args: unknown[]) => Promise<{ conversationId: string; streamId: string }>>();
const stopStreamApi = vi.fn<(...args: unknown[]) => Promise<void>>(async () => {});
const getStreamStatus = vi.fn<(...args: unknown[]) => Promise<unknown>>(async () => ({ hasActiveStream: false }));
const getStreamReconnectionState = vi.fn<(...args: unknown[]) => Promise<unknown>>(async () => ({ hasActiveStream: false }));

vi.mock('@/lib/api', () => ({
  unifiedApiService: {
    sendChatMessageWs: (...args: unknown[]) => sendChatMessageWs(...args),
    stopStream: (...args: unknown[]) => stopStreamApi(...args),
    getActiveStreamingConversations: vi.fn(async () => []),
    getStreamStatus: (...args: unknown[]) => getStreamStatus(...args),
    getStreamReconnectionState: (...args: unknown[]) => getStreamReconnectionState(...args),
  },
}));

vi.mock('@/lib/api/error-utils', () => ({
  is402Error: () => false,
  is413StorageError: () => false,
}));

vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ showInsufficientCreditsModal: vi.fn() }));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({ showInsufficientStorageModal: vi.fn() }));
vi.mock('@/components/billing/MissingApiKeyModal', () => ({ showMissingApiKeyModal: vi.fn() }));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isReady: true, isAuthenticated: true }),
}));

vi.mock('@/hooks/useModels', () => ({
  getModelsCache: () => [],
  getEffectiveDefaultModel: () => 'gpt-4',
  getEffectiveDefaultProvider: () => 'openai',
}));

// The declarative ConversationStreamSubscriber subscribes through useChannel →
// wsClient from '@/lib/websocket/ws-client'; both module specifiers carry the
// same inert spy so no real socket is opened. Handlers are captured per channel
// so tests can inject raw WS payloads (snapshot replays, late terminals).
const channelHandlers = new Map<string, Array<(raw: unknown) => void>>();
const subscribe = vi.fn<(...args: unknown[]) => () => void>((channel: unknown, handler: unknown) => {
  const list = channelHandlers.get(channel as string) ?? [];
  list.push(handler as (raw: unknown) => void);
  channelHandlers.set(channel as string, list);
  return vi.fn();
});
vi.mock('@/lib/websocket', () => ({
  wsClient: { subscribe: (...args: unknown[]) => subscribe(...args) },
}));
vi.mock('@/lib/websocket/ws-client', () => ({
  wsClient: { subscribe: (...args: unknown[]) => subscribe(...args) },
}));

// NOTE: streamHelpers is NOT mocked - isStaleTerminal tests use the real reducer
// helpers, and no WS events are injected in these tests.

// ---------------------------------------------------------------------------
// Under test (imported after mocks)
// ---------------------------------------------------------------------------
import { StreamingProvider, useStreaming, isStaleTerminal } from '../StreamingContext';
import type { SingleStreamState } from '../StreamingContext';

const wrapper = ({ children }: { children: ReactNode }) => (
  <StreamingProvider>{children}</StreamingProvider>
);

const baseParams = (message: string) => ({
  message,
  model: 'gpt-4',
  provider: 'openai',
  conversationId: 'conv-a',
});

/** Hand-controlled deferred. */
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

async function mountStreaming() {
  const { result } = renderHook(() => useStreaming(), { wrapper });
  // Flush the provider's mount effect (fetchActiveStreams).
  await act(async () => {});
  return result;
}

describe('StreamingContext - stale terminal must not hide the Stop button of a live stream', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // mockReset (not just clear): a guard-aborted reconnect legitimately leaves its
    // queued mockResolvedValueOnce unconsumed - without a reset it would leak into
    // the next test's FIFO once-queue.
    getStreamStatus.mockReset();
    getStreamReconnectionState.mockReset();
    sendChatMessageWs.mockReset();
    getStreamStatus.mockResolvedValue({ hasActiveStream: false });
    getStreamReconnectionState.mockResolvedValue({ hasActiveStream: false });
    channelHandlers.clear();
  });

  /** Inject a raw WS payload into every handler ever subscribed for the conversation. */
  const deliverWsEvent = (conversationId: string, payload: Record<string, unknown>) => {
    (channelHandlers.get(`conversation:${conversationId}`) ?? []).forEach((h) => h(payload));
  };

  it('keeps the new stream streaming when a slow reconnect resolves the PREVIOUS stream as COMPLETED after a send took over', async () => {
    const streaming = await mountStreaming();

    // The reconnect's first fetch is held open to create the race window.
    const statusFetch = deferred<unknown>();
    getStreamStatus.mockReturnValueOnce(statusFetch.promise);
    // If the reconnect (incorrectly) keeps going, it would resolve the PREVIOUS
    // stream's terminal state - the exact payload that used to clobber the UI.
    getStreamReconnectionState.mockResolvedValueOnce({
      hasActiveStream: true,
      streamId: 'sid-old',
      state: 'COMPLETED',
      content: 'previous turn content',
      toolEvents: [],
      model: 'gpt-4',
    });

    let reconnectPromise!: Promise<boolean>;
    act(() => {
      reconnectPromise = streaming.current.checkAndReconnect('conv-a');
    });

    // While the reconnect awaits the status fetch, the user sends a new message
    // in the same conversation → a NEW stream goes live.
    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-new' });
    await act(async () => {
      await streaming.current.sendMessage(baseParams('new message'), {});
    });
    expect(streaming.current.isStreamingConversation('conv-a')).toBe(true);

    // The held fetch resolves → the reconnect tail runs AFTER the takeover.
    await act(async () => {
      statusFetch.resolve({ hasActiveStream: true });
      await reconnectPromise;
    });

    // THE FIX: the reconnect stood down - the live stream is untouched, so the
    // composer still shows Stop. Pre-fix the entry was flipped to 'completed'.
    expect(await reconnectPromise).toBe(true);
    expect(streaming.current.isStreamingConversation('conv-a')).toBe(true);
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
    expect(streaming.current.getStreamState('conv-a')?.streamId).toBe('sid-new');
  });

  it('does not clobber the new stream when the reconnect FAILS after a send took over (catch path)', async () => {
    const streaming = await mountStreaming();

    const statusFetch = deferred<unknown>();
    getStreamStatus.mockReturnValueOnce(statusFetch.promise);

    let reconnectPromise!: Promise<boolean>;
    act(() => {
      reconnectPromise = streaming.current.checkAndReconnect('conv-a');
    });

    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-new' });
    await act(async () => {
      await streaming.current.sendMessage(baseParams('new message'), {});
    });

    await act(async () => {
      statusFetch.reject(new Error('network blip'));
      await reconnectPromise;
    });

    // Pre-fix the catch dispatched a blanket COMPLETED for the conversation,
    // flipping the live entry. Post-fix it stands down.
    expect(await reconnectPromise).toBe(true);
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
    expect(streaming.current.isStreamingConversation('conv-a')).toBe(true);
  });

  it('aborts a reconnect whose second fetch loses the race (takeover between step 1 and step 2)', async () => {
    const streaming = await mountStreaming();

    getStreamStatus.mockResolvedValueOnce({ hasActiveStream: true });
    const reconnFetch = deferred<unknown>();
    getStreamReconnectionState.mockReturnValueOnce(reconnFetch.promise);

    let reconnectPromise!: Promise<boolean>;
    await act(async () => {
      reconnectPromise = streaming.current.checkAndReconnect('conv-a');
      // Let step 1 resolve; step 2 is now pending.
      await Promise.resolve();
    });

    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-new' });
    await act(async () => {
      await streaming.current.sendMessage(baseParams('new message'), {});
    });

    await act(async () => {
      reconnFetch.resolve({
        hasActiveStream: true,
        streamId: 'sid-old',
        state: 'STOPPED_BY_USER',
        content: '',
        toolEvents: [],
        model: 'gpt-4',
      });
      await reconnectPromise;
    });

    expect(await reconnectPromise).toBe(true);
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
    expect(streaming.current.getStreamState('conv-a')?.streamId).toBe('sid-new');
  });

  it('still applies a legitimate reconnect terminal when nothing took over (happy path, guard must not over-block)', async () => {
    const streaming = await mountStreaming();

    getStreamStatus.mockResolvedValueOnce({ hasActiveStream: true });
    getStreamReconnectionState.mockResolvedValueOnce({
      hasActiveStream: true,
      streamId: 'sid-old',
      state: 'COMPLETED',
      content: 'finished while away',
      toolEvents: [],
      model: 'gpt-4',
    });
    const cb = { onStreamComplete: vi.fn() };

    let returned = false;
    await act(async () => {
      returned = await streaming.current.checkAndReconnect('conv-a', cb);
    });

    expect(returned).toBe(true);
    // The terminal carries the SAME streamId as the entry the reconnect created,
    // so the reducer applies it: status is 'completed' and the callback fired.
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('completed');
    expect(cb.onStreamComplete).toHaveBeenCalledWith('conv-a', 'finished while away', 'gpt-4');
  });

  it('still reattaches to an actively STREAMING server stream when nothing took over (happy path)', async () => {
    const streaming = await mountStreaming();

    getStreamStatus.mockResolvedValueOnce({ hasActiveStream: true });
    getStreamReconnectionState.mockResolvedValueOnce({
      hasActiveStream: true,
      streamId: 'sid-live',
      state: 'STREAMING',
      content: 'partial so far',
      toolEvents: [],
      model: 'gpt-4',
    });

    await act(async () => {
      await streaming.current.checkAndReconnect('conv-a');
    });

    expect(streaming.current.isStreamingConversation('conv-a')).toBe(true);
    expect(streaming.current.getStreamState('conv-a')?.streamId).toBe('sid-live');
    expect(streaming.current.getStreamContent('conv-a')).toBe('partial so far');
  });

  it('a stopStream captured from a stale render does not flip a newer stream to stopped (reducer STOPPED guard via dispatch)', async () => {
    const streaming = await mountStreaming();

    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-1' });
    await act(async () => {
      await streaming.current.sendMessage(baseParams('one'), {});
    });

    // A component holding a callback from THIS render (e.g. the composer's Stop
    // handler) captures a closure whose state still maps conv-a → sid-1.
    const staleStop = streaming.current.stopStream;

    // A newer stream takes over the conversation (e.g. queue drain "send now").
    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-2' });
    const cb2 = { onStreamComplete: vi.fn() };
    await act(async () => {
      await streaming.current.sendMessage(baseParams('two'), cb2);
    });

    await act(async () => {
      await staleStop('conv-a');
    });

    // The stale stop targeted sid-1 on the backend (correct), but its STOPPED
    // dispatch must NOT mark the live sid-2 stream as stopped - pre-fix it did,
    // hiding the Stop button while sid-2 kept streaming.
    expect(stopStreamApi).toHaveBeenCalledWith('sid-1');
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
    expect(streaming.current.isStreamingConversation('conv-a')).toBe(true);
    expect(cb2.onStreamComplete).not.toHaveBeenCalled();
  });

  it("a late WS 'completed' for a DIFFERENT stream does not flip the entry (reducer COMPLETED guard via dispatch)", async () => {
    const streaming = await mountStreaming();

    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-1' });
    await act(async () => {
      await streaming.current.sendMessage(baseParams('one'), {});
    });

    // User stops sid-1: entry keeps streamId 'sid-1' with status 'stopped', and
    // stopStream nulls refs.streamId - so the WS handler's cross-stream filter
    // (which needs BOTH ids) no longer applies and the event reaches the reducer.
    await act(async () => {
      await streaming.current.stopStream('conv-a');
    });
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('stopped');

    // A snapshot replay / late terminal of ANOTHER stream lands on the shared
    // conversation channel.
    await act(async () => {
      deliverWsEvent('conv-a', { streamId: 'sid-ghost', fullContent: 'ghost content', totalTokens: 3 });
    });

    // Pre-fix the entry flipped to 'completed' and its content was replaced.
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('stopped');
    expect(streaming.current.getStreamContent('conv-a')).not.toBe('ghost content');
  });

  it("a late WS 'error' for a DIFFERENT stream does not flip the entry (reducer ERROR guard via dispatch)", async () => {
    const streaming = await mountStreaming();

    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-1' });
    await act(async () => {
      await streaming.current.sendMessage(baseParams('one'), {});
    });
    await act(async () => {
      await streaming.current.stopStream('conv-a');
    });

    await act(async () => {
      deliverWsEvent('conv-a', { streamId: 'sid-ghost', error: 'ghost failure', errorCode: 'STREAM_ERROR' });
    });

    // Pre-fix the entry flipped to 'error' (error banner over a cleanly stopped turn).
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('stopped');
    expect(streaming.current.getStreamState('conv-a')?.error).toBeNull();
  });

  it('a reconnect that entered DURING the send POST round-trip cannot resolve the previous stream over the new one (mirror race)', async () => {
    const streaming = await mountStreaming();

    // Captured from a render where conv-a has no live entry: this closure's
    // already-streaming early-exit cannot see the send below, so the reconnect
    // really enters the POST window (a fresh checkAndReconnect would early-exit
    // and mask the race).
    const staleReconnect = streaming.current.checkAndReconnect;

    // The send's POST is held open.
    const post = deferred<{ conversationId: string; streamId: string }>();
    sendChatMessageWs.mockReturnValueOnce(post.promise);
    let sendPromise!: Promise<string | null>;
    act(() => {
      sendPromise = streaming.current.sendMessage(baseParams('new message'), {});
    });

    // The reconnect enters during the POST window and gets past its status fetches,
    // resolving the PREVIOUS stream's terminal state.
    const statusFetch = deferred<unknown>();
    getStreamStatus.mockReturnValueOnce(statusFetch.promise);
    getStreamReconnectionState.mockResolvedValueOnce({
      hasActiveStream: true,
      streamId: 'sid-old',
      state: 'COMPLETED',
      content: 'previous turn content',
      toolEvents: [],
      model: 'gpt-4',
    });
    let reconnectPromise!: Promise<boolean>;
    act(() => {
      reconnectPromise = staleReconnect('conv-a');
    });

    // The POST resolves first (stream sid-new is live), then the reconnect's
    // fetches complete.
    await act(async () => {
      post.resolve({ conversationId: 'conv-a', streamId: 'sid-new' });
      await sendPromise;
    });
    await act(async () => {
      statusFetch.resolve({ hasActiveStream: true });
      await reconnectPromise;
    });

    expect(await reconnectPromise).toBe(true);
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');
    expect(streaming.current.getStreamState('conv-a')?.streamId).toBe('sid-new');
    expect(streaming.current.isStreamingConversation('conv-a')).toBe(true);
  });

  it('stopStream still stops the live stream (terminal with MATCHING streamId applies)', async () => {
    const streaming = await mountStreaming();

    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-1' });
    await act(async () => {
      await streaming.current.sendMessage(baseParams('one'), {});
    });
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('streaming');

    await act(async () => {
      await streaming.current.stopStream('conv-a');
    });

    expect(streaming.current.getStreamState('conv-a')?.status).toBe('stopped');
    expect(stopStreamApi).toHaveBeenCalledWith('sid-1');
  });
});

describe('isStaleTerminal - reducer-level safety net', () => {
  const entry = (streamId: string | null): SingleStreamState => ({
    status: 'streaming',
    streamId,
    content: '',
    error: null,
    toolActivities: [],
    pendingServiceApprovals: [],
    pendingToolAuthorizations: [],
  });

  it('is stale when both ids are known and differ', () => {
    expect(isStaleTerminal(entry('sid-new'), 'sid-old')).toBe(true);
  });

  it('applies when the ids match', () => {
    expect(isStaleTerminal(entry('sid-1'), 'sid-1')).toBe(false);
  });

  it('applies when the action carries no streamId (legacy dispatch paths)', () => {
    expect(isStaleTerminal(entry('sid-1'), undefined)).toBe(false);
  });

  it('applies when the live entry has no streamId yet (temp/POST-in-flight phase)', () => {
    expect(isStaleTerminal(entry(null), 'sid-1')).toBe(false);
  });

  it('applies when there is no live entry at all', () => {
    expect(isStaleTerminal(undefined, 'sid-1')).toBe(false);
  });
});

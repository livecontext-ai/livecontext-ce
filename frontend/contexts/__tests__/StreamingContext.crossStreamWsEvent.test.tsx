/**
 * @vitest-environment jsdom
 *
 * Regression test for the cross-stream WebSocket-event leak in
 * StreamingContext.createWsEventHandler.
 *
 * Scenario reported in prod: the user sends a message, STOPS it, then
 * immediately sends a NEW message in the SAME conversation. The new
 * response renders its thinking phase and then "disappears", only to
 * reappear on a page refresh.
 *
 * Root cause (the part the earlier in-process stopStream guard did NOT
 * cover): the WebSocket channel is keyed by CONVERSATION
 * (`conversation:{id}`), so every stream of a conversation shares it. When
 * the user stops message 1, conversation-service publishes a `stopped` event
 * carrying message 1's streamId onto that shared channel. By then the user
 * has already started message 2, whose handler is subscribed to the SAME
 * channel - so message 2's handler consumes message 1's late `stopped`
 * event: it dispatches STOPPED (flips the live UI), fires message 2's
 * onStreamComplete (loadMessages then clobbers the in-flight response), and
 * tears down message 2's own WebSocket.
 *
 * The fix binds each WS handler to its stream's streamId and discards events
 * whose streamId belongs to a different stream of the conversation. This test
 * uses the REAL streamHelpers (detectStreamEventType / mapV2EventToV1) so the
 * handler genuinely maps and filters synthetic backend payloads, and proves:
 *   1. a stale `stopped` event for the previous stream does NOT tear down the
 *      current stream (the actual bug);
 *   2. the current stream's OWN `done` event still completes it (no
 *      over-filtering);
 *   3. conversation-scoped `title` events pass through even with a stale
 *      streamId (they are keyed by conversationId, not stream-bound).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React, { ReactNode } from 'react';
import { renderHook, act } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks - declared before the provider import (vitest hoists vi.mock).
// NOTE: streamHelpers is deliberately NOT mocked - this test needs the real
// event detection/mapping so the streamId discrimination is genuinely exercised.
// ---------------------------------------------------------------------------

const sendChatMessageWs = vi.fn<(...args: unknown[]) => Promise<{ conversationId: string; streamId: string }>>();
const stopStreamApi = vi.fn<(...args: unknown[]) => Promise<void>>(async () => {});

vi.mock('@/lib/api', () => ({
  unifiedApiService: {
    sendChatMessageWs: (...args: unknown[]) => sendChatMessageWs(...args),
    stopStream: (...args: unknown[]) => stopStreamApi(...args),
    getActiveStreamingConversations: vi.fn(async () => []),
    getStreamStatus: vi.fn(async () => ({ hasActiveStream: false })),
    getStreamReconnectionState: vi.fn(async () => ({ hasActiveStream: false })),
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

// wsClient.subscribe records the (channel, handler) pair and returns a UNIQUE
// unsubscribe spy per call so the test can both drive each stream's handler and
// tell each subscription apart. The chat subscription is DECLARATIVE since
// 334d213c7: a ConversationStreamSubscriber mounted per active conversation goes
// through useChannel → wsClient from '@/lib/websocket/ws-client' (relative import
// inside use-channel), so THAT module must carry the spy; '@/lib/websocket' is
// mocked too so the provider's index import stays inert.
interface Sub { channel: string; handler: (p: unknown) => void; unsub: ReturnType<typeof vi.fn>; }
const subs: Sub[] = [];
const subscribe = vi.fn<(...args: unknown[]) => () => void>((...args: unknown[]) => {
  const unsub = vi.fn();
  subs.push({
    channel: args[0] as string,
    handler: args[1] as (p: unknown) => void,
    unsub,
  });
  return unsub;
});
vi.mock('@/lib/websocket', () => ({
  wsClient: { subscribe: (...args: unknown[]) => subscribe(...args) },
}));
vi.mock('@/lib/websocket/ws-client', () => ({
  wsClient: { subscribe: (...args: unknown[]) => subscribe(...args) },
}));

// ---------------------------------------------------------------------------
// Provider under test (imported after mocks)
// ---------------------------------------------------------------------------
import { StreamingProvider, useStreaming } from '../StreamingContext';

const wrapper = ({ children }: { children: ReactNode }) => (
  <StreamingProvider>{children}</StreamingProvider>
);

const baseParams = (message: string) => ({
  message,
  model: 'gpt-4',
  provider: 'openai',
  conversationId: 'conv-a',
});

describe('StreamingContext.createWsEventHandler - cross-stream WS event leak', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    subs.length = 0;
  });

  async function mountStreaming() {
    const { result } = renderHook(() => useStreaming(), { wrapper });
    await act(async () => {}); // flush the mount effect (fetchActiveStreams)
    return result;
  }

  /** Send msg1 (sid1), stop it, then resend as msg2 (sid2) in the same conversation. */
  async function sendStopResend(streaming: ReturnType<typeof renderHook<unknown, unknown>>['result']) {
    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid1' });
    const cb1 = { onStreamComplete: vi.fn(), onTitleUpdated: vi.fn() };
    await act(async () => {
      await (streaming.current as ReturnType<typeof useStreaming>).sendMessage(baseParams('one'), cb1);
    });

    await act(async () => {
      await (streaming.current as ReturnType<typeof useStreaming>).stopStream('conv-a');
    });

    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid2' });
    const cb2 = { onStreamComplete: vi.fn(), onTitleUpdated: vi.fn() };
    await act(async () => {
      await (streaming.current as ReturnType<typeof useStreaming>).sendMessage(baseParams('two'), cb2);
    });

    // Declarative path: subs[0] = the conversation channel mounted for msg1 (torn
    // down when STOPPED removed conv-a from the active-stream set), subs[1] = the
    // remounted channel for msg2 (the live stream).
    return { cb2, handler2: subs[1].handler, unsub2: subs[1].unsub };
  }

  it('discards a stale stopped event for the PREVIOUS stream instead of tearing down the new one', async () => {
    const streaming = await mountStreaming();
    const { cb2, handler2, unsub2 } = await sendStopResend(streaming);

    // Message 2 streams a chunk - it is actively rendering.
    await act(async () => {
      handler2({ streamId: 'sid2', content: 'Hello' });
    });
    const s = streaming.current as ReturnType<typeof useStreaming>;
    expect(s.getStreamContent('conv-a')).toBe('Hello');
    expect(s.getStreamState('conv-a')?.status).toBe('streaming');

    // The backend's late `stopped` event for message 1 (sid1) arrives on the
    // SHARED conversation channel - i.e. on message 2's handler.
    await act(async () => {
      handler2({ streamId: 'sid1', partialContent: 'old partial' });
    });

    // THE FIX: the stale stopped event is ignored - message 2 is untouched.
    expect(cb2.onStreamComplete).not.toHaveBeenCalled(); // no loadMessages clobber
    expect(unsub2).not.toHaveBeenCalled();               // WS not torn down
    expect(s.getStreamState('conv-a')?.status).toBe('streaming'); // still live
    expect(s.getStreamContent('conv-a')).toBe('Hello');  // content intact
  });

  it('still completes the current stream on its OWN done event (no over-filtering)', async () => {
    const streaming = await mountStreaming();
    const { cb2, handler2, unsub2 } = await sendStopResend(streaming);

    await act(async () => {
      handler2({ streamId: 'sid2', content: 'Hi there' });
    });

    // Message 2's own terminal event (matching streamId) must be processed.
    await act(async () => {
      handler2({ streamId: 'sid2', fullContent: 'Hi there', totalTokens: 3 });
    });

    const s = streaming.current as ReturnType<typeof useStreaming>;
    expect(cb2.onStreamComplete).toHaveBeenCalledWith('conv-a', 'Hi there', 'gpt-4');
    expect(s.getStreamState('conv-a')?.status).toBe('completed');
    // Declarative subscription: a just-completed conversation KEEPS its channel
    // mounted (so a late terminal snapshot can still land); it is torn down once
    // the stream leaves the active set (clearStream).
    expect(unsub2).not.toHaveBeenCalled();
    await act(async () => {
      (streaming.current as ReturnType<typeof useStreaming>).clearStream('conv-a');
    });
    expect(unsub2).toHaveBeenCalled();
  });

  it('lets a conversation-scoped title event through even with a stale streamId', async () => {
    const streaming = await mountStreaming();
    const { cb2, handler2 } = await sendStopResend(streaming);

    // Title generated during message 1 (sid1) can land while message 2 (sid2) is
    // active. It is keyed by conversationId and non-destructive - must pass.
    await act(async () => {
      handler2({ streamId: 'sid1', title: 'My Title', conversationId: 'conv-a' });
    });

    expect(cb2.onTitleUpdated).toHaveBeenCalledWith('conv-a', 'My Title');
  });
});

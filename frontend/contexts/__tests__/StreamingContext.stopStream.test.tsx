/**
 * @vitest-environment jsdom
 *
 * Regression test for the same-conversation stop+resend race in
 * StreamingContext.stopStream.
 *
 * Scenario reported in prod: the user sends a message, STOPS it, then
 * immediately sends a NEW message in the SAME conversation. The new
 * response renders the thinking phase and then "disappears", only to
 * reappear on a page refresh.
 *
 * Root cause: `streamRefsMap` stores ONE StreamRefs object per conversation
 * (wsUnsubscribe / callbacks / content / model). stopStream awaits the
 * backend stop, then reads `refs.*` AFTER the await. If the user starts a
 * new stream in the same conversation during that await window, sendMessage
 * has already repurposed the SAME refs object for the new stream - so the
 * stopped stream's tail would (A) unsubscribe the NEW stream's WebSocket
 * (its response freezes) and (B) fire the NEW stream's onStreamComplete
 * prematurely (loadMessages overwrites the in-flight response; it only
 * comes back on refresh).
 *
 * The cross-conversation variant is guarded separately in
 * useMessageHandlersV2.onStreamComplete (see useMessageHandlersV2.test.tsx).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React, { ReactNode } from 'react';
import { renderHook, act } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks - declared before the provider import (vitest hoists vi.mock)
// ---------------------------------------------------------------------------

// Backend stop resolves immediately by default; test 1 overrides it once with
// a hand-controlled deferred to hold the await window open across a resend.
let resolveBackendStop: (() => void) | null = null;
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

// wsClient.subscribe returns a UNIQUE unsubscribe spy per call so the test can
// tell each subscription apart. The chat subscription is DECLARATIVE since
// 334d213c7: a ConversationStreamSubscriber mounted per active conversation goes
// through useChannel → wsClient from '@/lib/websocket/ws-client' (relative import
// inside use-channel), so THAT module must carry the spy; '@/lib/websocket' is
// mocked too so the provider's index import stays inert.
const unsubscribeSpies: Array<ReturnType<typeof vi.fn>> = [];
const subscribe = vi.fn<(...args: unknown[]) => () => void>(() => {
  const unsub = vi.fn();
  unsubscribeSpies.push(unsub);
  return unsub;
});
vi.mock('@/lib/websocket', () => ({
  wsClient: { subscribe: (...args: unknown[]) => subscribe(...args) },
}));
vi.mock('@/lib/websocket/ws-client', () => ({
  wsClient: { subscribe: (...args: unknown[]) => subscribe(...args) },
}));

vi.mock('@/lib/streaming/streamHelpers', () => ({
  markPendingToolsAsSuccess: (t: unknown) => t,
  markThinkingAsSuccess: (t: unknown) => t,
  detectStreamEventType: () => 'unknown',
  mapV2EventToV1: () => ({ type: 'unknown' }),
  streamLogger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() },
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

describe('StreamingContext.stopStream - same-conversation stop+resend race', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    unsubscribeSpies.length = 0;
    resolveBackendStop = null;
  });

  async function mountStreaming() {
    const { result } = renderHook(() => useStreaming(), { wrapper });
    // Flush the provider's mount effect (fetchActiveStreams) so its async
    // state update doesn't leak out of act.
    await act(async () => {});
    return result;
  }

  it('does not tear down or complete a newer stream started in the same conversation while the backend-stop await is pending', async () => {
    const streaming = await mountStreaming();

    // 1. Send message 1 → subscribes WS channel #1 (unsub1).
    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid1' });
    const cb1 = { onStreamComplete: vi.fn() };
    await act(async () => {
      await streaming.current.sendMessage(baseParams('one'), cb1);
    });
    expect(subscribe).toHaveBeenCalledTimes(1);
    const unsub1 = unsubscribeSpies[0];

    // 2. Begin stopping message 1, but hold the backend stop open so the
    //    stopStream tail cannot run yet - exactly the prod race window.
    stopStreamApi.mockImplementationOnce(
      () => new Promise<void>((res) => { resolveBackendStop = () => res(); }),
    );
    let stopPromise!: Promise<void>;
    await act(async () => {
      stopPromise = streaming.current.stopStream('conv-a');
    });
    expect(stopStreamApi).toHaveBeenCalledWith('sid1');

    // 3. While the stop is still awaiting the backend, the user sends message 2
    //    in the SAME conversation → subscribes WS channel #2 (unsub2).
    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid2' });
    const cb2 = { onStreamComplete: vi.fn() };
    await act(async () => {
      await streaming.current.sendMessage(baseParams('two'), cb2);
    });
    expect(subscribe).toHaveBeenCalledTimes(2);
    const unsub2 = unsubscribeSpies[1];

    // 4. Now let the stopped stream's backend call resolve → stopStream tail runs.
    await act(async () => {
      resolveBackendStop?.();
      await stopPromise;
    });

    // THE FIX - the stopped stream's tail must operate ONLY on the stream it was
    // asked to stop, never on the newer one that repurposed the shared refs:
    // (A) message 2's WebSocket channel is intact (not unsubscribed).
    expect(unsub2).not.toHaveBeenCalled();
    // (B) message 2's onStreamComplete was NOT fired prematurely (no loadMessages
    //     clobber - that is what made the response "disappear").
    expect(cb2.onStreamComplete).not.toHaveBeenCalled();
    // message 1's own channel WAS cleaned up by the stop.
    expect(unsub1).toHaveBeenCalled();
  });

  it('still fires onStreamComplete for the stopped stream when no newer message has taken over (happy path)', async () => {
    const streaming = await mountStreaming();

    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid1' });
    const cb1 = { onStreamComplete: vi.fn() };
    await act(async () => {
      await streaming.current.sendMessage(baseParams('one'), cb1);
    });

    // Plain stop (backend resolves immediately, no resend during the await).
    await act(async () => {
      await streaming.current.stopStream('conv-a');
    });

    // The stopped stream refreshes its own messages - content is empty (no chunks
    // streamed in this test), model is the one the stream was started with.
    expect(cb1.onStreamComplete).toHaveBeenCalledWith('conv-a', '', 'gpt-4');
    expect(unsubscribeSpies[0]).toHaveBeenCalled();
  });

  it('staleStopCallbackStillUsesRefStreamIdAfterFirstConversationCreation', async () => {
    const streaming = await mountStreaming();

    // Capture stopStream before sendMessage updates React state. This mirrors
    // the first-conversation startup handoff where the composer can still hold
    // a callback from the pre-stream render.
    const stopFromPreStreamRender = streaming.current.stopStream;

    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid1' });
    await act(async () => {
      await streaming.current.sendMessage(baseParams('one'), { onStreamComplete: vi.fn() });
    });

    await act(async () => {
      await stopFromPreStreamRender('conv-a');
    });

    expect(stopStreamApi).toHaveBeenCalledWith('sid1');
  });

  it('does not fire the stale onStreamComplete when the conversation is cleared during the backend-stop await (live map-entry takeover)', async () => {
    // Guards the second half of the takeover check (`liveRefs !== refs`): the
    // captured refs object still has its original callbacks, so the callbacks-
    // identity clause alone would NOT detect this. Only the live map-entry
    // comparison catches a clearStream/migration that drops the entry mid-await.
    const streaming = await mountStreaming();

    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid1' });
    const cb1 = { onStreamComplete: vi.fn() };
    await act(async () => {
      await streaming.current.sendMessage(baseParams('one'), cb1);
    });

    // Hold the backend stop open so the stopStream tail runs only after the clear.
    stopStreamApi.mockImplementationOnce(
      () => new Promise<void>((res) => { resolveBackendStop = () => res(); }),
    );
    let stopPromise!: Promise<void>;
    await act(async () => {
      stopPromise = streaming.current.stopStream('conv-a');
    });

    // Conversation is cleared (e.g. user deletes it / navigates away) → the map
    // entry stopStream captured is deleted out from under it.
    await act(async () => {
      streaming.current.clearStream('conv-a');
    });

    await act(async () => {
      resolveBackendStop?.();
      await stopPromise;
    });

    // The stopped tail must NOT fire a stale completion for a conversation whose
    // stream state no longer exists.
    expect(cb1.onStreamComplete).not.toHaveBeenCalled();
  });
});

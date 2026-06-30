/**
 * @vitest-environment jsdom
 *
 * Regression test for the "visualize card only shows after a manual refresh"
 * bug on the bridge (Claude Code CLI) streaming path.
 *
 * Reported in prod: the agent acquires/executes an application (or a workflow),
 * the chat streams the reply text, but the inline `[visualize:application:<id>]`
 * card does NOT appear during streaming - only after the user refreshes the page.
 *
 * Root cause: the per-token content chunks the bridge publishes
 * (`publishContent`) can OMIT the trailing `[visualize:...]` marker line - the
 * Claude adapter does not re-publish an assistant *snapshot* text block once
 * deltas were seen earlier in the run (claude-adapter.mjs `streamedContentViaDeltas`
 * guard). Yet that marker IS present in the `done` event's `fullContent` and in
 * the persisted message. Before the fix, StreamingContext's COMPLETED reducer
 * kept the marker-less accumulated chunks, so the live bubble never rendered the
 * card; it only appeared once a manual refresh re-fetched the DB message.
 *
 * The fix reconciles the live bubble content with the authoritative `fullContent`
 * carried by the `done` event. This test drives the REAL StreamingProvider +
 * real streamHelpers (detectStreamEventType / mapV2EventToV1) and proves:
 *   1. a `done` whose `fullContent` carries a marker the streamed chunks lacked
 *      reconciles the bubble content so the marker becomes visible live;
 *   2. a `done` with NO `fullContent` does NOT wipe the accumulated content
 *      (guard - native / older-backend paths keep their streamed content).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React, { ReactNode } from 'react';
import { renderHook, act } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks - declared before the provider import (vitest hoists vi.mock).
// streamHelpers is deliberately NOT mocked: the test needs the real event
// detection/mapping so `done` → fullContent flows through genuinely.
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
// The chat subscription is DECLARATIVE since 334d213c7: ConversationStreamSubscriber →
// useChannel → wsClient from '@/lib/websocket/ws-client' (relative import inside
// use-channel) - that module must carry the same spy for `subs` to capture handlers.
vi.mock('@/lib/websocket/ws-client', () => ({
  wsClient: { subscribe: (...args: unknown[]) => subscribe(...args) },
}));

// ---------------------------------------------------------------------------
// Provider under test (imported after mocks)
// ---------------------------------------------------------------------------
import { StreamingProvider, useStreaming } from '../StreamingContext';
// Resolves to the mocked module above - lets individual tests queue one-shot
// responses for the reconnection path.
import { unifiedApiService } from '@/lib/api';

const wrapper = ({ children }: { children: ReactNode }) => (
  <StreamingProvider>{children}</StreamingProvider>
);

const baseParams = (message: string) => ({
  message,
  model: 'gpt-4',
  provider: 'openai',
  conversationId: 'conv-a',
});

const MARKER = '[visualize:application:app-123]';

describe('StreamingContext - done reconciles bubble content with fullContent (visualize marker)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    subs.length = 0;
  });

  async function mountStreaming() {
    const { result } = renderHook(() => useStreaming(), { wrapper });
    await act(async () => {}); // flush the mount effect (fetchActiveStreams)
    return result;
  }

  /** Start a single stream (streamId 'sid1') and return its WS handler. */
  async function startStream(streaming: ReturnType<typeof renderHook<unknown, unknown>>['result']) {
    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid1' });
    const cb = { onStreamComplete: vi.fn(), onTitleUpdated: vi.fn() };
    await act(async () => {
      await (streaming.current as ReturnType<typeof useStreaming>).sendMessage(baseParams('book me a flight'), cb);
    });
    return { cb, handler: subs[0].handler };
  }

  // Read the latest hook value: getStreamContent/getStreamState are useCallbacks
  // re-created per render, so a value captured BEFORE an act() closes over stale
  // state. Always go through `streaming.current` AFTER the act.
  const live = (streaming: ReturnType<typeof renderHook<unknown, unknown>>['result']) =>
    streaming.current as ReturnType<typeof useStreaming>;

  it('reconciles the live bubble to fullContent when the streamed chunks omitted the marker', async () => {
    const streaming = await mountStreaming();
    const { cb, handler } = await startStream(streaming);

    // The bridge streamed only the prose - the trailing marker line was NEVER
    // published as a content chunk (the bug). The bubble has no card yet.
    await act(async () => {
      handler({ streamId: 'sid1', content: "Voici votre application de recherche de vols." });
    });
    expect(live(streaming).getStreamContent('conv-a')).toBe('Voici votre application de recherche de vols.');
    expect(live(streaming).getStreamContent('conv-a')).not.toContain(MARKER);

    // The `done` event carries the authoritative full content INCLUDING the marker
    // (= what was persisted to the DB, what a refresh would have shown).
    const fullContent = `Voici votre application de recherche de vols.\n${MARKER}`;
    await act(async () => {
      handler({ streamId: 'sid1', fullContent, totalTokens: 12 });
    });

    // THE FIX: the completed bubble is reconciled to fullContent → the marker is
    // now in the live content, so MarkdownRender renders the card without a refresh.
    expect(live(streaming).getStreamState('conv-a')?.status).toBe('completed');
    expect(live(streaming).getStreamContent('conv-a')).toBe(fullContent);
    expect(live(streaming).getStreamContent('conv-a')).toContain(MARKER);
    // onStreamComplete still fires with the authoritative content.
    expect(cb.onStreamComplete).toHaveBeenCalledWith('conv-a', fullContent, 'gpt-4');
  });

  it('does NOT wipe accumulated content when the done event carries no fullContent', async () => {
    const streaming = await mountStreaming();
    const { handler } = await startStream(streaming);

    await act(async () => {
      handler({ streamId: 'sid1', content: 'Reply with no marker.' });
    });
    expect(live(streaming).getStreamContent('conv-a')).toBe('Reply with no marker.');

    // A done event without fullContent (e.g. native path / older backend). The
    // guard must fall back to the accumulated content, never blank the bubble.
    await act(async () => {
      handler({ streamId: 'sid1', fullContent: '', totalTokens: 4 });
    });

    expect(live(streaming).getStreamState('conv-a')?.status).toBe('completed');
    expect(live(streaming).getStreamContent('conv-a')).toBe('Reply with no marker.');
  });

  it('preserves buffered content when COMPLETED carries no content (reconnect terminal path)', async () => {
    const streaming = await mountStreaming();

    // Reconnecting to an already-COMPLETED stream replays its buffered content,
    // then dispatches COMPLETED *without* a content payload (StreamingContext
    // reconnect-terminal branch). This is the real caller that exercises the
    // reducer's `else → currentStream.content` fallback directly - the marker-
    // carrying buffered content must survive the content-less COMPLETED.
    const buffered = 'Reconnected reply with [visualize:application:app-9].';
    vi.mocked(unifiedApiService.getStreamStatus).mockResolvedValueOnce({ hasActiveStream: true });
    vi.mocked(unifiedApiService.getStreamReconnectionState).mockResolvedValueOnce({
      hasActiveStream: true,
      streamId: 'sidR',
      conversationId: 'conv-a',
      model: 'gpt-4',
      content: buffered,
      state: 'COMPLETED',
      toolEvents: [],
    });

    await act(async () => {
      await live(streaming).checkAndReconnect('conv-a', { onStreamComplete: vi.fn() });
    });

    expect(live(streaming).getStreamState('conv-a')?.status).toBe('completed');
    expect(live(streaming).getStreamContent('conv-a')).toBe(buffered);
  });
});

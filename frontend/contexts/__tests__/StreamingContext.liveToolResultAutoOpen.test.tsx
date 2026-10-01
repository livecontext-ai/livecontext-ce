/**
 * @vitest-environment jsdom
 *
 * A LIVE tool result reaches the side panel, through the real WebSocket handler.
 *
 * The backend sends a live result with `resultId: null`, and the self-hosted monolith's shared
 * ObjectMapper drops null map values, so the frame arrives with no `resultId` at all. Detection
 * keyed on that key read every live tool result as a heartbeat there: the agent's tool rows
 * stayed pending until the turn ended, the builder never followed the agent, and no side-panel
 * tab opened (found on a CE e2e slot, 2026-09-30). The live browser step, for its part, carried
 * no conversation, so a side-panel chat's browser could never open beside the chat.
 *
 * Real streamHelpers (detection + mapping) and the real debounced auto-open queue.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
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
  wsClient: { onReconnected: () => () => {}, subscribe: (...args: unknown[]) => subscribe(...args) },
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

describe('StreamingContext - live events that open side-panel tabs', () => {
  const opened: Array<Record<string, unknown>> = [];
  const planModified: number[] = [];
  const onOpen = (e: Event) => opened.push((e as CustomEvent).detail);
  const onPlan = () => planModified.push(Date.now());

  beforeEach(() => {
    vi.clearAllMocks();
    subs.length = 0;
    opened.length = 0;
    planModified.length = 0;
    window.addEventListener('sidePanelAutoOpen', onOpen);
    window.addEventListener('workflowPlanModified', onPlan);
  });
  afterEach(() => {
    window.removeEventListener('sidePanelAutoOpen', onOpen);
    window.removeEventListener('workflowPlanModified', onPlan);
    vi.useRealTimers();
  });

  async function liveHandler() {
    const { result } = renderHook(() => useStreaming(), { wrapper });
    await act(async () => {});
    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid1' });
    await act(async () => {
      await result.current.sendMessage(baseParams('build it'), { onStreamComplete: vi.fn() });
    });
    return subs[subs.length - 1].handler;
  }

  it('regression: a live tool result with no resultId opens its tab, stamped with the conversation', async () => {
    const handler = await liveHandler();
    vi.useFakeTimers();

    act(() => {
      handler({
        streamId: 'sid1', toolId: 'call_init_workflow_1', toolName: 'workflow', success: true, durationMs: 31,
        result: '{}', visualization: { type: 'workflow', id: 'wf-1', title: 'SidePanelFlow' }, draftId: 'wf-1',
        timestamp: 't',
      });
      vi.advanceTimersByTime(1000);
    });

    expect(opened).toEqual([expect.objectContaining({ type: 'workflow', id: 'wf-1', conversationId: 'conv-a' })]);
    expect(planModified.length, 'an open builder follows the agent').toBe(1);
  });

  it('the live browser step names its conversation, so a side-panel chat can keep the reader on it', async () => {
    const handler = await liveHandler();
    vi.useFakeTimers();

    act(() => {
      handler({
        streamId: 'sid1', toolId: 'call_browse_1', sessionId: 's-1', cdpToken: 'tok', cdpWsUrl: 'wss://cdp',
        currentUrl: 'https://example.com', runId: 'r-1', nodeId: 'n-1', stepIndex: 1,
      });
      vi.advanceTimersByTime(1000);
    });

    expect(opened).toEqual([expect.objectContaining({ type: 'agent_browse', conversationId: 'conv-a' })]);
  });
});

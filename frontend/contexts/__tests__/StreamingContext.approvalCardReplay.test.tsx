/**
 * @vitest-environment jsdom
 *
 * A card raised while the agent is HOLDING a tool call has to survive a page reload.
 *
 * The card only ever travelled on live channels, which was survivable while the database
 * row landed a second later: the turn ended immediately. A held call delays the end of the
 * turn by minutes, so a user who reloads inside that window used to come back to a tool
 * spinning with nothing to click - and the held call could then only time out.
 *
 * These drive the REAL provider + real streamHelpers through the reconnect path, so the
 * detection/mapping of a buffered card is genuinely exercised.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React, { ReactNode } from 'react';
import { renderHook, act } from '@testing-library/react';

const sendChatMessageWs = vi.fn<(...args: unknown[]) => Promise<{ conversationId: string; streamId: string }>>();

vi.mock('@/lib/api', () => ({
  unifiedApiService: {
    sendChatMessageWs: (...args: unknown[]) => sendChatMessageWs(...args),
    stopStream: vi.fn(async () => {}),
    getActiveStreamingConversations: vi.fn(async () => []),
    getStreamStatus: vi.fn(async () => ({ hasActiveStream: false })),
    getStreamReconnectionState: vi.fn(async () => ({ hasActiveStream: false })),
  },
}));

vi.mock('@/lib/api/error-utils', () => ({ is402Error: () => false, is413StorageError: () => false }));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ showInsufficientCreditsModal: vi.fn() }));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({ showInsufficientStorageModal: vi.fn() }));
vi.mock('@/components/billing/MissingApiKeyModal', () => ({ showMissingApiKeyModal: vi.fn() }));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isReady: true, isAuthenticated: true }) }));
vi.mock('@/hooks/useModels', () => ({
  getModelsCache: () => [],
  getEffectiveDefaultModel: () => 'gpt-4',
  getEffectiveDefaultProvider: () => 'openai',
}));

interface Sub { channel: string; handler: (p: unknown) => void; unsub: ReturnType<typeof vi.fn>; }
const subs: Sub[] = [];
const subscribe = vi.fn<(...args: unknown[]) => () => void>((...args: unknown[]) => {
  const unsub = vi.fn();
  subs.push({ channel: args[0] as string, handler: args[1] as (p: unknown) => void, unsub });
  return unsub;
});
vi.mock('@/lib/websocket', () => ({ wsClient: { subscribe: (...args: unknown[]) => subscribe(...args) } }));
vi.mock('@/lib/websocket/ws-client', () => ({ wsClient: { onReconnected: () => () => {}, subscribe: (...args: unknown[]) => subscribe(...args) } }));

import { StreamingProvider, useStreaming } from '../StreamingContext';
import { unifiedApiService } from '@/lib/api';

const wrapper = ({ children }: { children: ReactNode }) => (
  <StreamingProvider>{children}</StreamingProvider>
);

/** What agent-service buffers when it parks a call on an authorization card. */
const heldAuthorizationEvent = JSON.stringify({
  streamId: 'sid1',
  toolAuthorization: {
    rule: 'workflow:execute',
    toolName: 'workflow',
    action: 'execute',
    toolCallId: 'call-9',
    argsSummary: 'run workflow 9',
    blocking: true,
    gateKey: 'call-9',
  },
  timestamp: '2026-08-18T09:00:00Z',
});

/** And when it parks on a connect-a-service card. */
const heldServiceEvent = JSON.stringify({
  streamId: 'sid1',
  services: [{ serviceType: 'gmail', serviceName: 'Gmail', iconSlug: 'gmail' }],
  reason: 'Connect Gmail to continue',
  needsAttention: false,
  blocking: true,
  gateKey: 'call-7',
  timestamp: '2026-08-18T09:00:00Z',
});

describe('StreamingContext - a held approval card comes back after a reload', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    subs.length = 0;
  });

  async function reconnectWith(toolEvents: string[]) {
    const { result } = renderHook(() => useStreaming(), { wrapper });
    await act(async () => {});
    vi.mocked(unifiedApiService.getStreamStatus).mockResolvedValueOnce({ hasActiveStream: true });
    vi.mocked(unifiedApiService.getStreamReconnectionState).mockResolvedValueOnce({
      hasActiveStream: true,
      streamId: 'sid1',
      conversationId: 'conv-a',
      model: 'gpt-4',
      content: 'Working on it.',
      state: 'STREAMING',
      toolEvents,
    });
    await act(async () => {
      await (result.current as ReturnType<typeof useStreaming>).checkAndReconnect('conv-a');
    });
    return result.current as ReturnType<typeof useStreaming>;
  }

  it('restores an authorization card, WITH the key that releases the held call', async () => {
    const streaming = await reconnectWith([heldAuthorizationEvent]);

    const [card] = streaming.getPendingToolAuthorizations('conv-a');
    expect(card).toBeDefined();
    expect(card.rule).toBe('workflow:execute');
    // Losing the key would put back a card that can no longer release anything: the user
    // clicks, the held call keeps waiting, and the turn dies at its deadline.
    expect(card.blocking).toBe(true);
    expect(card.gateKey).toBe('call-9');
  });

  it('restores a connect-a-service card, with its held call', async () => {
    const streaming = await reconnectWith([heldServiceEvent]);

    const [card] = streaming.getPendingServiceApprovals('conv-a');
    expect(card).toBeDefined();
    expect(card.services[0].serviceType).toBe('gmail');
    expect(card.blocking).toBe(true);
    expect(card.gateKeys).toEqual(['call-7']);
  });

  it('does not double a card that arrives twice in the buffer', async () => {
    const streaming = await reconnectWith([heldAuthorizationEvent, heldAuthorizationEvent]);

    // Same (rule, toolCallId) - one call, one card. The buffer can legitimately hold a
    // retransmit, and two identical cards on screen would be indistinguishable to the user.
    expect(streaming.getPendingToolAuthorizations('conv-a')).toHaveLength(1);
  });

  it('leaves an ordinary reconnect with no cards at all', async () => {
    const streaming = await reconnectWith([JSON.stringify({
      streamId: 'sid1', toolCall: { id: 'call-1', name: 'files', arguments: '{}' },
    })]);

    expect(streaming.getPendingToolAuthorizations('conv-a')).toHaveLength(0);
    expect(streaming.getPendingServiceApprovals('conv-a')).toHaveLength(0);
  });
});

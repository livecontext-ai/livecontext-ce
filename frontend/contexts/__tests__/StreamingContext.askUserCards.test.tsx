/**
 * @vitest-environment jsdom
 *
 * The question card's state in the streaming context: restored from the replay buffer after
 * a reload WITH the key that releases the held call, never doubled, cleared one key at a
 * time, and dropped when the turn that raised it is stopped (the backend settles that park
 * as dismissed, so a card left on screen would be a card nobody is holding).
 *
 * Drives the REAL provider + real streamHelpers through the reconnect path.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React, { ReactNode } from 'react';
import { renderHook, act } from '@testing-library/react';

const stopStreamApi = vi.fn(async () => {});

vi.mock('@/lib/api', () => ({
  unifiedApiService: {
    sendChatMessageWs: vi.fn(),
    stopStream: (...args: unknown[]) => stopStreamApi(...(args as [])),
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

const subscribe = vi.fn(() => vi.fn());
vi.mock('@/lib/websocket', () => ({ wsClient: { subscribe: (...args: unknown[]) => subscribe(...(args as [])) } }));
vi.mock('@/lib/websocket/ws-client', () => ({ wsClient: { onReconnected: () => () => {}, subscribe: (...args: unknown[]) => subscribe(...(args as [])) } }));

import { StreamingProvider, useStreaming, askUserKey, askUserCardsAfterStop } from '../StreamingContext';
import { unifiedApiService } from '@/lib/api';

const wrapper = ({ children }: { children: ReactNode }) => (
  <StreamingProvider>{children}</StreamingProvider>
);

/** What agent-service buffers when ask_user parks on its card. */
const heldQuestionEvent = JSON.stringify({
  streamId: 'sid1',
  askUser: {
    toolCallId: 'call-7',
    questions: [{ header: 'Tone', question: 'Which?', options: [{ label: 'A' }, { label: 'B' }], multiSelect: false }],
    blocking: true,
    gateKey: 'call-7:ask',
  },
  timestamp: '2026-09-05T09:00:00Z',
});

const secondQuestionEvent = JSON.stringify({
  streamId: 'sid1',
  askUser: { toolCallId: 'call-8', questions: [{ header: 'Size', question: '?', options: [{ label: 'S' }, { label: 'L' }] }], blocking: true, gateKey: 'call-8:ask' },
  timestamp: '2026-09-05T09:00:01Z',
});

describe('StreamingContext - question cards', () => {
  beforeEach(() => {
    vi.clearAllMocks();
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
    return result;
  }

  it('restores a held question card after a reload, with the key that releases the held call', async () => {
    const result = await reconnectWith([heldQuestionEvent]);

    const [card] = result.current.getPendingAskUserQuestions('conv-a');
    expect(card).toBeDefined();
    expect(card.toolCallId).toBe('call-7');
    expect(card.questions[0].header).toBe('Tone');
    expect(card.blocking).toBe(true);
    expect(card.gateKey).toBe('call-7:ask');
    expect(card.streamId).toBe('sid1');
  });

  it('does not double a card that arrives twice, but keeps two distinct questions', async () => {
    const result = await reconnectWith([heldQuestionEvent, heldQuestionEvent, secondQuestionEvent]);

    const cards = result.current.getPendingAskUserQuestions('conv-a');
    expect(cards.map(c => c.toolCallId)).toEqual(['call-7', 'call-8']);
  });

  it('clears ONE card by key and leaves the other', async () => {
    const result = await reconnectWith([heldQuestionEvent, secondQuestionEvent]);

    act(() => result.current.clearAskUserQuestion('conv-a', askUserKey('call-7')));

    expect(result.current.getPendingAskUserQuestions('conv-a').map(c => c.toolCallId)).toEqual(['call-8']);

    act(() => result.current.clearAskUserQuestion('conv-a'));
    expect(result.current.getPendingAskUserQuestions('conv-a')).toHaveLength(0);
  });

  it('stopping the turn drops the cards that turn raised', async () => {
    const result = await reconnectWith([heldQuestionEvent]);
    expect(result.current.getPendingAskUserQuestions('conv-a')).toHaveLength(1);

    await act(async () => {
      await result.current.stopStream('conv-a');
    });

    expect(stopStreamApi).toHaveBeenCalledWith('sid1');
    expect(result.current.getStreamState('conv-a')?.status).toBe('stopped');
    expect(result.current.getPendingAskUserQuestions('conv-a')).toHaveLength(0);
  });

  it('a Stop that names no stream, or a card that recorded none, keeps the cards (the regression the guard fixes)', () => {
    const own = { toolCallId: 'a', questions: [], streamId: 'sid1', timestamp: 1 };
    const other = { toolCallId: 'b', questions: [], streamId: 'sid2', timestamp: 1 };
    const unknown = { toolCallId: 'c', questions: [], streamId: null, timestamp: 1 };

    expect(askUserCardsAfterStop([own, other, unknown], undefined)).toEqual([own, other, unknown]);
    expect(askUserCardsAfterStop([own, other, unknown], null)).toEqual([own, other, unknown]);
    expect(askUserCardsAfterStop([own, other, unknown], 'sid1')).toEqual([other, unknown]);
  });

  it('an ordinary reconnect raises no question card', async () => {
    const result = await reconnectWith([JSON.stringify({
      streamId: 'sid1', toolCall: { id: 'call-1', name: 'files', arguments: '{}' },
    })]);

    expect(result.current.getPendingAskUserQuestions('conv-a')).toHaveLength(0);
  });
});

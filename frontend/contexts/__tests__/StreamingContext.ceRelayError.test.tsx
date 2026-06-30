/**
 * @vitest-environment jsdom
 *
 * Wiring test for the chat stream `error` case in StreamingContext: a CE cloud-relay
 * error (INSUFFICIENT_CREDITS / MODEL_NOT_SUPPORTED) must route to handleCeRelayError
 * FIRST and mark the error non-retryable, taking precedence over the API-key heuristic;
 * a non-relay error must still fall through to the API-key modal. handleCeRelayError
 * itself is unit-tested separately - here it is a spy so we assert the call-site wiring.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import React, { ReactNode } from 'react';
import { renderHook, act } from '@testing-library/react';

const sendChatMessageWs = vi.fn<(...args: unknown[]) => Promise<{ conversationId: string; streamId: string }>>();

const { handleCeRelayError, showMissingApiKeyModal, showAgentErrorModal } = vi.hoisted(() => ({
  handleCeRelayError: vi.fn<(e: unknown) => boolean>(),
  showMissingApiKeyModal: vi.fn(),
  showAgentErrorModal: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  unifiedApiService: {
    sendChatMessageWs: (...args: unknown[]) => sendChatMessageWs(...args),
    stopStream: vi.fn(async () => {}),
    getActiveStreamingConversations: vi.fn(async () => []),
    getStreamStatus: vi.fn(async () => ({ hasActiveStream: false })),
    getStreamReconnectionState: vi.fn(async () => ({ hasActiveStream: false })),
  },
}));

vi.mock('@/lib/api/error-utils', () => ({
  is402Error: () => false,
  is413StorageError: () => false,
}));

vi.mock('@/lib/billing/ceRelayErrorModals', () => ({ handleCeRelayError }));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ showInsufficientCreditsModal: vi.fn() }));
vi.mock('@/components/billing/InsufficientStorageModal', () => ({ showInsufficientStorageModal: vi.fn() }));
vi.mock('@/components/billing/MissingApiKeyModal', () => ({ showMissingApiKeyModal }));
vi.mock('@/components/billing/AgentErrorModal', () => ({ showAgentErrorModal }));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isReady: true, isAuthenticated: true }),
}));
vi.mock('@/hooks/useModels', () => ({
  getModelsCache: () => [],
  getEffectiveDefaultModel: () => 'gpt-4',
  getEffectiveDefaultProvider: () => 'openai',
}));

const channelHandlers = new Map<string, Array<(raw: unknown) => void>>();
const subscribe = vi.fn<(...args: unknown[]) => () => void>((channel: unknown, handler: unknown) => {
  const list = channelHandlers.get(channel as string) ?? [];
  list.push(handler as (raw: unknown) => void);
  channelHandlers.set(channel as string, list);
  return vi.fn();
});
vi.mock('@/lib/websocket', () => ({ wsClient: { subscribe: (...a: unknown[]) => subscribe(...a) } }));
vi.mock('@/lib/websocket/ws-client', () => ({ wsClient: { subscribe: (...a: unknown[]) => subscribe(...a) } }));

import { StreamingProvider, useStreaming } from '../StreamingContext';

const wrapper = ({ children }: { children: ReactNode }) => (
  <StreamingProvider>{children}</StreamingProvider>
);
const baseParams = (message: string) => ({ message, model: 'gpt-4', provider: 'openai', conversationId: 'conv-a' });

const deliverWsEvent = (conversationId: string, payload: Record<string, unknown>) => {
  (channelHandlers.get(`conversation:${conversationId}`) ?? []).forEach((h) => h(payload));
};

async function mountAndSend() {
  const { result } = renderHook(() => useStreaming(), { wrapper });
  await act(async () => {});
  sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid-1' });
  await act(async () => {
    await result.current.sendMessage(baseParams('hi'), {});
  });
  return result;
}

describe('StreamingContext chat error -> CE cloud-relay modal routing', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    channelHandlers.clear();
  });

  it('routes a CE relay error to handleCeRelayError, takes precedence over the API-key modal, and marks it non-retryable', async () => {
    // handleCeRelayError claims the error even though the message would also trip the
    // API-key heuristic - proving the `if (handleCeRelayError) ... else if (isApiKeyError)` order.
    handleCeRelayError.mockReturnValue(true);
    const streaming = await mountAndSend();

    await act(async () => {
      deliverWsEvent('conv-a', {
        streamId: 'sid-1',
        error: 'Cloud LLM relay returned 402: {"error":"INSUFFICIENT_CREDITS"} invalid api key',
        errorCode: 'STREAM_ERROR',
      });
    });

    expect(handleCeRelayError).toHaveBeenCalledWith(
      expect.stringContaining('INSUFFICIENT_CREDITS'));
    expect(showMissingApiKeyModal).not.toHaveBeenCalled();
    expect(showAgentErrorModal).not.toHaveBeenCalled();
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('error');
    expect(streaming.current.getStreamState('conv-a')?.error?.retryable).toBe(false);
  });

  it('falls through to the API-key modal when the error is not a CE relay error', async () => {
    handleCeRelayError.mockReturnValue(false);
    const streaming = await mountAndSend();

    await act(async () => {
      deliverWsEvent('conv-a', {
        streamId: 'sid-1',
        error: 'Provider is not configured: invalid api key',
        errorCode: 'STREAM_ERROR',
      });
    });

    expect(handleCeRelayError).toHaveBeenCalled();
    expect(showMissingApiKeyModal).toHaveBeenCalledTimes(1);
    expect(showAgentErrorModal).not.toHaveBeenCalled();
    expect(streaming.current.getStreamState('conv-a')?.error?.retryable).toBe(false);
  });

  it('shows the generic agent-error modal when the error is neither a CE relay nor an API-key error', async () => {
    // The catch-all `else` branch: not a credit/model relay error and not an API-key
    // error, so the user still gets a friendly "something went wrong" surface.
    handleCeRelayError.mockReturnValue(false);
    const streaming = await mountAndSend();

    await act(async () => {
      deliverWsEvent('conv-a', {
        streamId: 'sid-1',
        error: 'Bridge returned null response',
        errorCode: 'STREAM_ERROR',
      });
    });

    expect(handleCeRelayError).toHaveBeenCalled();
    expect(showMissingApiKeyModal).not.toHaveBeenCalled();
    expect(showAgentErrorModal).toHaveBeenCalledTimes(1);
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('error');
  });
});

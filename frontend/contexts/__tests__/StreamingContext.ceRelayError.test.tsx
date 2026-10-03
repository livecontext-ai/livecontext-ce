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

vi.mock('@/lib/api/error-utils', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/error-utils')>()),
  is402Error: (e: { status?: number }) => e?.status === 402,
  is413StorageError: () => false,
  isAuthError: (e: { status?: number }) => e?.status === 401,
  isPlanLimitError: (e: { status?: number; code?: string }) => e?.status === 409 && e?.code === 'PLAN_RESOURCE_LIMIT_EXCEEDED',
}));

vi.mock('@/lib/billing/ceRelayErrorModals', () => ({ handleCeRelayError }));
const { showInsufficientCreditsModal } = vi.hoisted(() => ({ showInsufficientCreditsModal: vi.fn() }));
vi.mock('@/components/billing/InsufficientCreditsModal', () => ({ showInsufficientCreditsModal }));
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
vi.mock('@/lib/websocket/ws-client', () => ({ wsClient: { onReconnected: () => () => {}, subscribe: (...a: unknown[]) => subscribe(...a) } }));

import { StreamingProvider, useStreaming } from '../StreamingContext';
import { ApiError } from '@/lib/api/api-client';

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
    // The modal is the only error surface (no chat banner), so it must receive the
    // verbatim failure text and code to explain the failure to the user.
    expect(showAgentErrorModal).toHaveBeenCalledWith({ message: 'Bridge returned null response', code: 'STREAM_ERROR' });
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('error');
  });

  it('opens the error modal once for a restricted-data refusal on the stream, and keeps it non-retryable', async () => {
    // Retrying the same provider can never succeed. The chat has no error banner any more, so the
    // modal is the only surface: it classifies the token as its own kind (switch provider).
    handleCeRelayError.mockReturnValue(false);
    const streaming = await mountAndSend();

    await act(async () => {
      deliverWsEvent('conv-a', {
        streamId: 'sid-1',
        error: "Agent execution error: RESTRICTED_DATA_PROVIDER_NOT_ALLOWED: Data from Gmail or Google Drive cannot be sent to the model provider 'deepseek'.",
        errorCode: 'STREAM_ERROR',
      });
    });

    expect(showAgentErrorModal).toHaveBeenCalledTimes(1);
    expect(showAgentErrorModal.mock.calls[0][0].message).toContain('RESTRICTED_DATA_PROVIDER_NOT_ALLOWED');
    expect(showMissingApiKeyModal).not.toHaveBeenCalled();
    const state = streaming.current.getStreamState('conv-a');
    expect(state?.status).toBe('error');
    expect(state?.error?.retryable).toBe(false);
    expect(state?.error?.message).toContain('RESTRICTED_DATA_PROVIDER_NOT_ALLOWED');
  });

  it('stores the refusal token when the chat request itself is refused with a 403 code body', async () => {
    handleCeRelayError.mockReturnValue(false);
    const { result } = renderHook(() => useStreaming(), { wrapper });
    await act(async () => {});
    sendChatMessageWs.mockRejectedValueOnce(
      Object.assign(new Error('Forbidden'), { status: 403, code: 'RESTRICTED_DATA_PROVIDER_NOT_ALLOWED' }),
    );

    await act(async () => {
      await result.current.sendMessage(baseParams('hi'), {});
    });

    const state = result.current.getStreamState('conv-a');
    expect(state?.status).toBe('error');
    expect(state?.error?.retryable).toBe(false);
    expect(state?.error?.message).toBe('RESTRICTED_DATA_PROVIDER_NOT_ALLOWED');
    // Without the banner, the refused send must still tell the user why (modal, own kind).
    expect(showAgentErrorModal).toHaveBeenCalledTimes(1);
    expect(showAgentErrorModal.mock.calls[0][0].message).toBe('RESTRICTED_DATA_PROVIDER_NOT_ALLOWED');
  });

  it('opens the agent-error modal with the failure text when sending the message itself fails', async () => {
    // Before the banner was removed, this path only set the stream error state, which
    // only the banner displayed. Without the modal the user would see nothing at all.
    handleCeRelayError.mockReturnValue(false);
    const { result } = renderHook(() => useStreaming(), { wrapper });
    await act(async () => {});
    sendChatMessageWs.mockRejectedValueOnce(new Error('Failed to fetch'));

    await act(async () => {
      await result.current.sendMessage(baseParams('hi'), {});
    });

    expect(showMissingApiKeyModal).not.toHaveBeenCalled();
    expect(showAgentErrorModal).toHaveBeenCalledTimes(1);
    expect(showAgentErrorModal).toHaveBeenCalledWith({ message: 'Failed to fetch', code: 'SEND_FAILED' });
    expect(result.current.getStreamState('conv-a')?.status).toBe('error');
  });

  it('a send failure that is an API-key problem keeps its own modal, not the agent-error modal', async () => {
    handleCeRelayError.mockReturnValue(false);
    const { result } = renderHook(() => useStreaming(), { wrapper });
    await act(async () => {});
    sendChatMessageWs.mockRejectedValueOnce(new Error('Provider is not configured: invalid api key'));

    await act(async () => {
      await result.current.sendMessage(baseParams('hi'), {});
    });

    expect(showMissingApiKeyModal).toHaveBeenCalledTimes(1);
    expect(showAgentErrorModal).not.toHaveBeenCalled();
  });
  it('does not re-open the modal when the backend replays a failure this client already showed', async () => {
    // A socket resubscribe replays the failed turn (SNAPSHOT_REPLAY, generic placeholder text):
    // it is the SAME failure, so it must not announce itself a second time.
    handleCeRelayError.mockReturnValue(false);
    const streaming = await mountAndSend();

    await act(async () => {
      deliverWsEvent('conv-a', { streamId: 'sid-1', error: 'HTTP 400: bad request', errorCode: 'STREAM_ERROR' });
    });
    await act(async () => {
      deliverWsEvent('conv-a', { streamId: 'sid-1', error: 'Stream ended with error', errorCode: 'SNAPSHOT_REPLAY' });
    });

    expect(showAgentErrorModal).toHaveBeenCalledTimes(1);
    expect(showAgentErrorModal).toHaveBeenCalledWith({ message: 'HTTP 400: bad request', code: 'STREAM_ERROR' });
    expect(streaming.current.getStreamState('conv-a')?.status).toBe('error');
  });

  it('shows a replayed failure the client never saw (it failed while the socket was down)', async () => {
    // Without the old red strip, the replay after reconnecting is the only way the user can
    // learn that the turn failed while disconnected, so the modal must open for it.
    handleCeRelayError.mockReturnValue(false);
    await mountAndSend();

    await act(async () => {
      deliverWsEvent('conv-a', { streamId: 'sid-1', error: 'Stream ended with error', errorCode: 'SNAPSHOT_REPLAY' });
    });

    expect(showAgentErrorModal).toHaveBeenCalledTimes(1);
    expect(showAgentErrorModal).toHaveBeenCalledWith({ message: 'Stream ended with error', code: 'SNAPSHOT_REPLAY' });
  });

  it.each([
    ['a 409 plan-limit refusal (apiClient shows its own upgrade surface)', new ApiError('Plan limit reached', 409, 'PLAN_RESOURCE_LIMIT_EXCEEDED')],
    ['a 429 inactive-account refusal (apiClient shows the restore screen)', new ApiError('Inactive account', 429, 'HTTP_429', { message: 'Inactive account' })],
    ['a 401 (apiClient sends the user back to sign in)', new ApiError('Unauthorized', 401, 'HTTP_401')],
  ])('a send refused with %s opens no second, generic modal', async (_label, refusal) => {
    handleCeRelayError.mockReturnValue(false);
    const { result } = renderHook(() => useStreaming(), { wrapper });
    await act(async () => {});
    sendChatMessageWs.mockRejectedValueOnce(refusal);

    await act(async () => {
      await result.current.sendMessage(baseParams('hi'), {});
    });

    expect(showAgentErrorModal).not.toHaveBeenCalled();
    expect(result.current.getStreamState('conv-a')?.status).toBe('error');
  });

  it('a 402 send refusal keeps the credits modal and opens no agent-error modal', async () => {
    handleCeRelayError.mockReturnValue(false);
    const { result } = renderHook(() => useStreaming(), { wrapper });
    await act(async () => {});
    sendChatMessageWs.mockRejectedValueOnce(new ApiError('Payment required', 402, 'HTTP_402'));

    await act(async () => {
      await result.current.sendMessage(baseParams('hi'), {});
    });

    expect(showInsufficientCreditsModal).toHaveBeenCalledTimes(1);
    expect(showAgentErrorModal).not.toHaveBeenCalled();
  });
});

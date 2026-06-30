/**
 * @vitest-environment jsdom
 *
 * StreamingContext now holds LISTS of pending approval/authorization cards (the agent raises
 * them asynchronously without pausing the run). This drives the real WS handler + reducer with
 * synthetic backend events and proves: parallel accumulation, dedup by canonical key, and
 * per-key clearing. Uses the REAL streamHelpers so detection/mapping is genuinely exercised.
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
// ConversationStreamSubscriber imports wsClient through the lower-level module on origin/dev.
vi.mock('@/lib/websocket/ws-client', () => ({ wsClient: { subscribe: (...args: unknown[]) => subscribe(...args) } }));

import { StreamingProvider, useStreaming } from '../StreamingContext';

const wrapper = ({ children }: { children: ReactNode }) => <StreamingProvider>{children}</StreamingProvider>;

const svcEvent = (type: string, name: string) => ({
  streamId: 'sid1',
  services: [{ serviceType: type, serviceName: name, iconSlug: type }],
  reason: `Connect ${name}`,
});
const authEvent = (rule: string) => ({ streamId: 'sid1', toolAuthorization: { rule, toolName: 'application' } });

describe('StreamingContext - parallel pending approval/authorization cards', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    subs.length = 0;
  });

  async function mountAndSend() {
    const { result } = renderHook(() => useStreaming(), { wrapper });
    await act(async () => {});
    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid1' });
    await act(async () => {
      await (result.current as ReturnType<typeof useStreaming>).sendMessage({
        message: 'go', model: 'gpt-4', provider: 'openai', conversationId: 'conv-a',
      });
    });
    return { s: () => result.current as ReturnType<typeof useStreaming>, handler: subs[0].handler };
  }

  it('accumulates several distinct cards in parallel and dedups identical ones', async () => {
    const { s, handler } = await mountAndSend();

    await act(async () => {
      handler(svcEvent('gmail', 'Gmail'));
      handler(svcEvent('slack', 'Slack'));
      handler(svcEvent('gmail', 'Gmail')); // duplicate → deduped
      handler(authEvent('application:acquire'));
      handler(authEvent('application:acquire')); // duplicate → deduped
    });

    const approvals = s().getPendingServiceApprovals('conv-a');
    const auths = s().getPendingToolAuthorizations('conv-a');
    expect(approvals).toHaveLength(1);
    expect(approvals[0].services.map(s => s.serviceType).sort()).toEqual(['gmail', 'slack']);
    expect(auths.map(a => a.rule)).toEqual(['application:acquire']);
  });

  it('clears ONE card by its canonical key, leaving the others pending', async () => {
    const { s, handler } = await mountAndSend();
    await act(async () => {
      handler(svcEvent('gmail', 'Gmail'));
      handler(svcEvent('slack', 'Slack'));
      handler(authEvent('application:acquire'));
    });

    act(() => { s().clearServiceApproval('conv-a', 'svc:connect'); });
    expect(s().getPendingServiceApprovals('conv-a')).toEqual([]);
    // The tool authorization is untouched.
    expect(s().getPendingToolAuthorizations('conv-a').map(a => a.rule)).toEqual(['application:acquire']);

    act(() => { s().clearToolAuthorization('conv-a', 'auth:application:acquire'); });
    expect(s().getPendingToolAuthorizations('conv-a')).toEqual([]);
    expect(s().getPendingServiceApprovals('conv-a')).toEqual([]);
  });

  it('clears ALL cards of a kind when no key is given', async () => {
    const { s, handler } = await mountAndSend();
    await act(async () => {
      handler(svcEvent('gmail', 'Gmail'));
      handler(svcEvent('slack', 'Slack'));
    });

    act(() => { s().clearServiceApproval('conv-a'); });
    expect(s().getPendingServiceApprovals('conv-a')).toEqual([]);
  });

  it('RESUME (keepPendingActions) PRESERVES the live sibling cards across START_STREAM', async () => {
    const { s, handler } = await mountAndSend();
    await act(async () => {
      handler(svcEvent('gmail', 'Gmail'));
      handler(svcEvent('slack', 'Slack'));
      handler(authEvent('application:acquire'));
    });
    expect(s().getPendingServiceApprovals('conv-a')[0].services.map(svc => svc.serviceType).sort())
      .toEqual(['gmail', 'slack']);

    // A resume send (after resolving one card elsewhere) must NOT wipe the live siblings.
    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid2' });
    await act(async () => {
      await s().sendMessage({
        message: 'I connected Gmail, continue', model: 'gpt-4', provider: 'openai',
        conversationId: 'conv-a', keepPendingActions: true,
      });
    });

    // The sibling cards are still live after the resume's START_STREAM.
    expect(s().getPendingServiceApprovals('conv-a')[0].services.map(svc => svc.serviceType).sort())
      .toEqual(['gmail', 'slack']);
    expect(s().getPendingToolAuthorizations('conv-a').map(a => a.rule)).toEqual(['application:acquire']);
  });

  it('FRESH message (no keepPendingActions) wipes the live cards on START_STREAM', async () => {
    const { s, handler } = await mountAndSend();
    await act(async () => {
      handler(svcEvent('gmail', 'Gmail'));
      handler(authEvent('application:acquire'));
    });

    sendChatMessageWs.mockResolvedValueOnce({ conversationId: 'conv-a', streamId: 'sid2' });
    await act(async () => {
      await s().sendMessage({
        message: 'do something else', model: 'gpt-4', provider: 'openai', conversationId: 'conv-a',
      });
    });

    expect(s().getPendingServiceApprovals('conv-a')).toEqual([]);
    expect(s().getPendingToolAuthorizations('conv-a')).toEqual([]);
  });
});

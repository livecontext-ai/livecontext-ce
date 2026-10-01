/**
 * @vitest-environment jsdom
 *
 * The workflow panel's chat exposes its conversation WHOLE, as found, so ChatCore can rebuild
 * the cards the agent left waiting (a credential to connect...) after a reload, and reads it
 * again when a turn ends, which is when those cards are saved. Before, the hook exposed only an
 * id and the credential card of an OAuth round trip never came back.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, renderHook, waitFor } from '@testing-library/react';

const h = vi.hoisted(() => ({ onStreamComplete: null as null | ((cid: string) => Promise<void> | void) }));
vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => ({
    checkAndReconnect: (_cid: string, callbacks?: { onStreamComplete?: (cid: string) => Promise<void> | void }) => {
      h.onStreamComplete = callbacks?.onStreamComplete ?? null;
    },
    sendMessage: vi.fn(),
    stopStream: vi.fn(),
    isStreamingConversation: () => false,
  }),
}));
const api = vi.hoisted(() => ({
  findWorkflowConversation: vi.fn(),
  getConversation: vi.fn(),
  getRecentMessagesAsc: vi.fn(),
}));
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: api }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getWorkflow: vi.fn() } }));
vi.mock('@/hooks/useModels', () => ({ getEffectiveDefaultSelectedModel: () => ({ provider: 'p', id: 'm' }) }));

import { useWorkflowChat } from '@/hooks/useWorkflowChat';

const waitingForGmail = { id: 'c1', pendingActions: [{ waiting_for: 'service_approval', services: [] }] };

beforeEach(() => {
  h.onStreamComplete = null;
  api.findWorkflowConversation.mockReset();
  api.getConversation.mockReset();
  api.getRecentMessagesAsc.mockReset().mockResolvedValue([]);
});
afterEach(cleanup);

describe('useWorkflowChat - the conversation, whole', () => {
  it('regression: exposes the conversation it found, pending cards included', async () => {
    api.findWorkflowConversation.mockResolvedValue(waitingForGmail);

    const { result } = renderHook(() => useWorkflowChat({ workflowId: 'wf-1' }));

    await waitFor(() => expect(result.current.conversation?.pendingActions).toHaveLength(1));
    expect(result.current.conversationId).toBe('c1');
  });

  it('reads it again when a turn ends', async () => {
    api.findWorkflowConversation.mockResolvedValue({ id: 'c1', pendingActions: [] });
    api.getConversation.mockResolvedValue(waitingForGmail);
    const { result } = renderHook(() => useWorkflowChat({ workflowId: 'wf-1' }));
    await waitFor(() => expect(h.onStreamComplete).not.toBeNull());

    await h.onStreamComplete!('c1');

    await waitFor(() => expect(result.current.conversation?.pendingActions).toHaveLength(1));
    expect(api.getConversation).toHaveBeenCalledWith('c1');
  });

  it('a refresh that fails keeps what it had', async () => {
    api.findWorkflowConversation.mockResolvedValue(waitingForGmail);
    api.getConversation.mockRejectedValue(new Error('gone'));
    const { result } = renderHook(() => useWorkflowChat({ workflowId: 'wf-1' }));
    await waitFor(() => expect(h.onStreamComplete).not.toBeNull());

    await h.onStreamComplete!('c1');

    expect(result.current.conversation?.pendingActions).toHaveLength(1);
  });

  it('no conversation for the workflow yet: none is exposed', async () => {
    api.findWorkflowConversation.mockResolvedValue(null);

    const { result } = renderHook(() => useWorkflowChat({ workflowId: 'wf-1' }));

    await waitFor(() => expect(api.findWorkflowConversation).toHaveBeenCalled());
    expect(result.current.conversation).toBeNull();
  });
});

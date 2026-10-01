/**
 * @vitest-environment jsdom
 *
 * The workflow panel's chat (useWorkflowChat) re-reads its conversation at every moment a reply
 * can have been saved without this tab hearing it: a WebSocket reconnect (even with no stream
 * live), a stream of it reporting an error, and a stream of it completing. Before, it re-read only
 * on completion.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';

type Callbacks = {
  onStreamComplete?: (conversationId: string, content: string, model: string) => void;
  onError?: (error: { message: string; retryable: boolean }, conversationId?: string) => void;
};

const h = vi.hoisted(() => ({
  reconnectListener: null as null | (() => void),
  reconnectCallbacks: null as null | Callbacks,
  sendCallbacks: null as null | Callbacks,
  existing: null as null | { id: string },
  getRecentMessagesAsc: vi.fn(async (..._args: unknown[]) => [] as unknown[]),
}));

vi.mock('@/lib/websocket', () => ({
  useWsReconnected: (callback: () => void) => { h.reconnectListener = callback; },
}));
vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: {
    findWorkflowConversation: vi.fn(async () => h.existing),
    createWorkflowConversation: vi.fn(async () => ({ id: 'wf-conv-new' })),
    getRecentMessagesAsc: (...args: unknown[]) => h.getRecentMessagesAsc(...args),
  },
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getWorkflow: vi.fn(async () => ({ name: 'Invoices' })) } }));
const streaming = {
  checkAndReconnect: (_id: string, callbacks: Callbacks) => { h.reconnectCallbacks = callbacks; return Promise.resolve(false); },
  sendMessage: async (_params: unknown, callbacks: Callbacks) => { h.sendCallbacks = callbacks; return 'wf-conv-new'; },
  stopStream: vi.fn(),
};
vi.mock('@/contexts/StreamingContext', () => ({ useStreaming: () => streaming }));
vi.mock('@/hooks/useModels', () => ({ getEffectiveDefaultSelectedModel: () => ({ provider: 'openai', id: 'gpt-4' }) }));

import { useWorkflowChat } from '../useWorkflowChat';

const reply = { id: 'r1', role: 'assistant', content: 'Workflow updated.' };

describe('useWorkflowChat - re-reading a reply the tab did not hear', () => {
  beforeEach(() => {
    h.reconnectListener = null;
    h.reconnectCallbacks = null;
    h.sendCallbacks = null;
    h.existing = null;
    h.getRecentMessagesAsc.mockReset();
    h.getRecentMessagesAsc.mockResolvedValue([]);
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => { vi.restoreAllMocks(); });

  async function openExistingConversation() {
    h.existing = { id: 'wf-conv' };
    const hook = renderHook(() => useWorkflowChat({ workflowId: 'wf-1' }));
    await waitFor(() => expect(hook.result.current.conversationId).toBe('wf-conv'));
    await waitFor(() => expect(h.reconnectCallbacks).not.toBeNull());
    h.getRecentMessagesAsc.mockClear();
    return hook;
  }

  it('re-reads its conversation when the WebSocket session comes back, even with no stream live', async () => {
    const hook = await openExistingConversation();
    h.getRecentMessagesAsc.mockResolvedValueOnce([reply]);

    await act(async () => { h.reconnectListener!(); });

    expect(h.getRecentMessagesAsc).toHaveBeenCalledWith('wf-conv');
    await waitFor(() => expect(hook.result.current.messages).toEqual([reply]));
  });

  it('re-reads when a stream it reconnected to reports an error', async () => {
    await openExistingConversation();

    await act(async () => { h.reconnectCallbacks!.onError?.({ message: 'bridge link failed', retryable: true }, 'wf-conv'); });

    expect(h.getRecentMessagesAsc).toHaveBeenCalledWith('wf-conv');
  });

  it('keeps the conversation of a first message whose turn errors, shows the error, and re-reads', async () => {
    // The conversation is created BEFORE the send, so an error on the first turn cannot lose it.
    const hook = renderHook(() => useWorkflowChat({ workflowId: 'wf-1' }));
    await act(async () => { await hook.result.current.sendMessage('Add a Slack step'); });
    h.getRecentMessagesAsc.mockResolvedValueOnce([reply]);

    await act(async () => { h.sendCallbacks!.onError?.({ message: 'bridge link failed', retryable: true }, 'wf-conv-new'); });

    expect(hook.result.current.conversationId).toBe('wf-conv-new');
    expect(hook.result.current.error).toBe('bridge link failed');
    expect(h.getRecentMessagesAsc).toHaveBeenCalledWith('wf-conv-new');
    await waitFor(() => expect(hook.result.current.messages).toEqual([reply]));
  });

  it('drops a late re-read for a conversation the chat has left (another workflow)', async () => {
    h.existing = { id: 'wf-conv' };
    const hook = renderHook(({ workflowId }) => useWorkflowChat({ workflowId }), { initialProps: { workflowId: 'wf-1' } });
    await waitFor(() => expect(hook.result.current.conversationId).toBe('wf-conv'));
    let answer!: (rows: unknown[]) => void;
    h.getRecentMessagesAsc.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));

    await act(async () => { h.reconnectListener!(); });
    // The panel moves to another workflow, with its own conversation, while the read is out.
    h.existing = { id: 'wf-conv-2' };
    hook.rerender({ workflowId: 'wf-2' });
    await waitFor(() => expect(hook.result.current.conversationId).toBe('wf-conv-2'));
    await act(async () => { answer([reply]); });

    // The first workflow's reply never lands in the second workflow's chat.
    expect(hook.result.current.messages).toEqual([]);
  });
});

/**
 * @vitest-environment jsdom
 *
 * `loadConversationAndMessages` is the reconnect path's entry point, and it takes the silent
 * flag on behalf of its caller. Drop the third argument where it forwards to loadMessages and
 * the end-of-stream reconciliation on that path silently becomes visible again: the flag is
 * still written at the call site, the call-site guard test still passes, every behavioural test
 * still passes, and the transcript flashes. One character, zero coverage. Hence this file.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

const loadMessagesMock = vi.fn(async () => {});
const clearMessagesMock = vi.fn();
const loadConversationByIdMock = vi.fn(async () => null);

vi.mock('@/contexts/UnifiedAppContext', () => ({
  useUnifiedApp: () => ({
    state: { conversations: [], currentConversationId: null },
    removeConversation: vi.fn(),
  }),
}));

vi.mock('../useConversationList', () => ({
  useConversationList: () => ({
    conversations: [],
    loading: false,
    error: null,
    hasMore: false,
    currentPage: 0,
    loadConversations: vi.fn(),
    loadMoreConversations: vi.fn(),
    searchConversations: vi.fn(),
    clearSearch: vi.fn(),
    loadConversationById: loadConversationByIdMock,
    setConversations: vi.fn(),
  }),
}));

vi.mock('../useMessages', () => ({
  useMessages: () => ({
    messages: [],
    hasMoreMessages: false,
    loadingOlderMessages: false,
    messagesLoading: false,
    sendingMessage: false,
    loadingTimeout: false,
    error: null,
    loadMessages: loadMessagesMock,
    loadOlderMessages: vi.fn(),
    addMessageLocal: vi.fn(),
    updateMessageLocal: vi.fn(),
    removeMessageLocal: vi.fn(),
    sendMessage: vi.fn(),
    clearMessages: clearMessagesMock,
    clearError: vi.fn(),
    clearLoadingTimeout: vi.fn(),
    setMessages: vi.fn(),
  }),
}));

let mutationCallbacks: { onConversationDeleted?: (id: string) => void } = {};
vi.mock('../useConversationMutations', () => ({
  useConversationMutations: (options: { onConversationDeleted?: (id: string) => void }) => {
    mutationCallbacks = options;
    return {
      loading: false,
      error: null,
      createConversation: vi.fn(),
      updateConversation: vi.fn(),
      deleteConversation: vi.fn(),
      clearError: vi.fn(),
    };
  },
}));

vi.mock('../useDeletedConversationsSync', () => ({ useDeletedConversationsSync: () => {} }));

import { useConversationHistory } from '@/hooks/useConversationHistory';
import {
  rememberMessages,
  recallMessages,
  resetMessageSnapshots,
} from '@/lib/chat/messageSnapshotCache';
import type { Message } from '@/lib/api/conversationApi';

beforeEach(() => {
  loadMessagesMock.mockClear();
  clearMessagesMock.mockClear();
  loadConversationByIdMock.mockClear();
  resetMessageSnapshots();
  mutationCallbacks = {};
});

describe('useConversationHistory - cross-remount snapshot wiring', () => {
  it('drops a deleted conversation snapshot, so it cannot paint from memory again', () => {
    // The cache is keyed by conversation id and outlives every React tree, so without this the
    // transcript of a conversation the reader just deleted would still be sitting there ready
    // to seed the next mount that happens to ask for that id.
    renderHook(() => useConversationHistory({ autoLoad: false }));
    rememberMessages('conv-doomed', [{ id: 'm-1', content: 'x' } as Message], { complete: true });
    expect(recallMessages('conv-doomed')).toBeDefined();

    act(() => { mutationCallbacks.onConversationDeleted?.('conv-doomed'); });

    expect(recallMessages('conv-doomed')).toBeUndefined();
  });

  it(`leaves the snapshots of other conversations alone`, () => {
    renderHook(() => useConversationHistory({ autoLoad: false }));
    rememberMessages('conv-doomed', [{ id: 'm-1', content: 'x' } as Message], { complete: true });
    rememberMessages('conv-kept', [{ id: 'm-2', content: 'y' } as Message], { complete: true });

    act(() => { mutationCallbacks.onConversationDeleted?.('conv-doomed'); });

    expect(recallMessages('conv-kept')).toBeDefined();
  });
});

describe('useConversationHistory - silent flag forwarding', () => {
  it('forwards { silent: true } through loadConversationAndMessages to loadMessages', async () => {
    const { result } = renderHook(() => useConversationHistory({ autoLoad: false }));

    await act(async () => {
      await result.current.loadConversationAndMessages('conv-1', { silent: true });
    });

    expect(loadMessagesMock).toHaveBeenCalledWith('conv-1', undefined, { silent: true });
  });

  it('does nothing at all when a silent reconcile targets a conversation the reader has left', async () => {
    // The blanking this avoids is not hypothetical: the first statement of
    // loadConversationAndMessages clears the message list whenever the id differs from the one
    // on screen. Reached from the reconnect path - where the callback closes over the
    // conversation that was streaming - that would wipe the conversation the reader moved TO
    // and repopulate it with the previous one's rows. A background reconciliation is only ever
    // about what is on screen, so it stands down instead.
    const { result } = renderHook(() => useConversationHistory({ autoLoad: false }));

    act(() => {
      result.current.selectConversation({ id: 'conv-on-screen' } as never);
    });

    await act(async () => {
      await result.current.loadConversationAndMessages('conv-left-behind', { silent: true });
    });

    expect(clearMessagesMock).not.toHaveBeenCalled();
    expect(loadMessagesMock).not.toHaveBeenCalled();
    expect(loadConversationByIdMock).not.toHaveBeenCalled();
  });

  it('an EXPLICIT switch still clears the previous transcript', async () => {
    // The other half: a navigation-driven load must keep blanking, or the previous
    // conversation's messages would linger under the new one while it fetches.
    const { result } = renderHook(() => useConversationHistory({ autoLoad: false }));

    act(() => {
      result.current.selectConversation({ id: 'conv-on-screen' } as never);
    });

    await act(async () => {
      await result.current.loadConversationAndMessages('conv-other');
    });

    expect(clearMessagesMock).toHaveBeenCalled();
    expect(loadMessagesMock).toHaveBeenCalledWith('conv-other', undefined, undefined);
  });

  it('leaves the load visible when no options are given', async () => {
    const { result } = renderHook(() => useConversationHistory({ autoLoad: false }));

    await act(async () => {
      await result.current.loadConversationAndMessages('conv-1');
    });

    expect(loadMessagesMock).toHaveBeenCalledWith('conv-1', undefined, undefined);
  });
});

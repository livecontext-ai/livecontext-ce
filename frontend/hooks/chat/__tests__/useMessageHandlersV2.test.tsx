/**
 * @vitest-environment jsdom
 *
 * Regression test for the streaming race condition in useMessageHandlersV2.
 *
 * Scenario: user stops a stream on conversation A and immediately starts a new
 * conversation B. The old stream's onStreamComplete fires for A AFTER
 * conversationIdRef has already been updated to B. Without the guard at line 250,
 * loadMessages would be called with A's ID, overwriting B's messages.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks - must be declared before the hook import (vitest hoists vi.mock)
// ---------------------------------------------------------------------------

// sendMessage captures the callbacks object so the test can invoke
// onStreamComplete manually, simulating the async race.
let capturedCallbacks: any = null;
const stopStreamMock = vi.fn(async (_conversationId: string) => {});
const sendMessageMock = vi.fn(async (_payload: any, callbacks: any) => {
  capturedCallbacks = callbacks;
  // Simulate: conversation created immediately
  callbacks.onConversationCreated?.('conv-a');
  return 'conv-a';
});

vi.mock('@/contexts/StreamingContext', () => ({
  useStreaming: () => ({
    sendMessage: sendMessageMock,
    stopStream: stopStreamMock,
    isStreamingConversation: () => false,
  }),
}));

vi.mock('@/contexts/UnifiedAppContext', () => ({
  useUnifiedApp: () => ({
    addConversations: vi.fn(),
    updateConversation: vi.fn(),
    setCurrentConversationId: vi.fn(),
  }),
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({
    isAuthenticated: true,
    isLoading: false,
    isReady: true,
    loginWithRedirect: vi.fn(),
  }),
}));

// router.replace: since 2c8524b39 the new-conversation URL sync is deferred to
// onStreamComplete (mid-stream route changes remounted the app layout and
// dropped the live stream subscription).
const routerReplaceMock = vi.fn();
// Mutable: the URL sync is now deferred until the end-of-stream reconciliation settles, so a
// test has to be able to move the user in the meantime.
let currentPathname = '/app/chat';
vi.mock('next/navigation', () => ({
  usePathname: () => currentPathname,
  useRouter: () => ({ replace: routerReplaceMock }),
}));

vi.mock('@/lib/logger', () => ({
  conversationLogger: {
    info: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock('@/hooks/useChatConfig', () => ({
  consumeDraftChatConfig: () => undefined,
}));

// ---------------------------------------------------------------------------
// Import the hook under test (after mocks are declared)
// ---------------------------------------------------------------------------
import { useMessageHandlersV2 } from '../useMessageHandlersV2';

describe('useMessageHandlersV2 - onStreamComplete stale-conversation guard', () => {
  const loadMessages = vi.fn(async () => {});
  const setCurrentConversationId = vi.fn();
  const addMessageLocal = vi.fn();
  const setPendingUserMessage = vi.fn();
  const setInputValue = vi.fn();
  const setSendError = vi.fn();

  function defaultOptions(overrides: Record<string, any> = {}) {
    return {
      currentConversationId: null as string | null,
      setCurrentConversationId,
      selectedModel: { id: 'gpt-4', provider: 'openai' },
      inputValue: '',
      setInputValue,
      setSendError,
      addMessageLocal,
      setPendingUserMessage,
      loadMessages,
      agentId: null,
      ...overrides,
    };
  }

  beforeEach(() => {
    vi.clearAllMocks();
    setSendError.mockReset();
    // clearAllMocks wipes CALLS but keeps implementations, so a test that stubs one of these
    // would otherwise leak its stub into every test after it.
    loadMessages.mockReset();
    loadMessages.mockImplementation(async () => {});
    routerReplaceMock.mockReset();
    currentPathname = '/app/chat';
    capturedCallbacks = null;
    stopStreamMock.mockResolvedValue(undefined);
    sendMessageMock.mockImplementation(async (_payload: any, callbacks: any) => {
      capturedCallbacks = callbacks;
      callbacks.onConversationCreated?.('conv-a');
      return 'conv-a';
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('does NOT call loadMessages when onStreamComplete fires for a stale conversation (race condition regression)', async () => {
    // 1. Render hook and send a message - sendMessage captures callbacks and
    //    onConversationCreated fires synchronously, setting conversationIdRef
    //    to 'conv-a'.
    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );

    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });

    expect(sendMessageMock).toHaveBeenCalledTimes(1);
    expect(capturedCallbacks).not.toBeNull();

    // 3. Simulate: user navigates to conversation B. The parent updates
    //    currentConversationId, which triggers the useEffect that sets
    //    conversationIdRef.current = 'conv-b'.
    hookResult.rerender(defaultOptions({ currentConversationId: 'conv-b' }));

    // 4. Now fire onStreamComplete for the OLD conversation A.
    //    The guard should detect conversationIdRef.current ('conv-b') !==
    //    completedConvId ('conv-a') and skip loadMessages.
    act(() => {
      capturedCallbacks.onStreamComplete('conv-a');
    });

    // 5. Assert: loadMessages was called once during doSendMessage (the
    //    pre-send load for existing conversations), but NOT by onStreamComplete.
    //    Since currentConversationId was null at send time, the pre-send load
    //    is skipped too, so loadMessages should have 0 calls total.
    expect(loadMessages).not.toHaveBeenCalled();
    // The deferred URL sync is behind the same stale guard - a stale completion
    // must not yank the user back to the old conversation's URL either.
    expect(routerReplaceMock).not.toHaveBeenCalled();
  });

  it('DOES call loadMessages when onStreamComplete fires for the current conversation (happy path)', async () => {
    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );

    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });

    expect(capturedCallbacks).not.toBeNull();

    // Rerender with conv-a as current (simulating onConversationCreated
    // having set it).
    hookResult.rerender(defaultOptions({ currentConversationId: 'conv-a' }));

    // Fire onStreamComplete for conv-a - same as current.
    await act(async () => {
      capturedCallbacks.onStreamComplete('conv-a');
    });

    // loadMessages SHOULD be called for the matching conversation, and SILENTLY: this is a
    // background reconciliation of the thread the user is reading, so it must not raise the
    // loading flag, reset pagination or clear the list on failure (the end-of-stream "refresh").
    expect(loadMessages).toHaveBeenCalledWith('conv-a', undefined, { silent: true });
    // URL sync is deferred to stream-complete (2c8524b39): still on the
    // new-chat URL, so the route is replaced to the created conversation.
    expect(routerReplaceMock).toHaveBeenCalledWith('/app/c/conv-a', { scroll: false });
  });

  it('syncs the URL only AFTER the silent reconciliation has landed', async () => {
    // The route change swaps the chat page for a fresh instance, which paints from the
    // snapshot the outgoing one left behind. Replacing the URL first would snapshot a thread
    // still missing the persisted reply, and the answer would visibly appear a second time
    // after the remount.
    const order: string[] = [];
    let releaseLoad!: () => void;
    loadMessages.mockImplementation(() => new Promise<void>((resolve) => {
      order.push('load:start');
      releaseLoad = () => { order.push('load:end'); resolve(); };
    }));
    routerReplaceMock.mockImplementation(() => { order.push('router.replace'); });

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );
    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });
    hookResult.rerender(defaultOptions({ currentConversationId: 'conv-a' }));

    await act(async () => {
      capturedCallbacks.onStreamComplete('conv-a');
    });
    expect(order).toEqual(['load:start']);

    await act(async () => {
      releaseLoad();
    });
    await waitFor(() => expect(routerReplaceMock).toHaveBeenCalled());
    expect(order).toEqual(['load:start', 'load:end', 'router.replace']);
  });

  it('still syncs the URL when the silent reconciliation FAILS', async () => {
    // A transient 5xx on the background reload must not strand the conversation on the
    // new-chat URL - it would not resolve on refresh, share or breadcrumb.
    loadMessages.mockRejectedValue(new Error('503'));

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );
    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });
    hookResult.rerender(defaultOptions({ currentConversationId: 'conv-a' }));

    await act(async () => {
      capturedCallbacks.onStreamComplete('conv-a');
    });

    await waitFor(() =>
      expect(routerReplaceMock).toHaveBeenCalledWith('/app/c/conv-a', { scroll: false }));
  });

  it('does not sync the URL when the user moved on while the reconciliation was in flight', async () => {
    // Deferring the route change opens a window the synchronous version did not have: the
    // user can start another conversation before the reload resolves. Yanking them back to
    // the finished conversation's URL would be worse than the flash this change removes.
    let releaseLoad!: () => void;
    loadMessages.mockImplementation(() => new Promise<void>((resolve) => {
      releaseLoad = resolve;
    }));

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );
    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });
    hookResult.rerender(defaultOptions({ currentConversationId: 'conv-a' }));

    await act(async () => {
      capturedCallbacks.onStreamComplete('conv-a');
    });

    // User navigates to another conversation before the reload lands.
    hookResult.rerender(defaultOptions({ currentConversationId: 'conv-b' }));
    await act(async () => {
      releaseLoad();
    });

    expect(routerReplaceMock).not.toHaveBeenCalled();
  });

  it('loads the existing thread SILENTLY before sending (a hiccup must not wipe it)', async () => {
    // The explicit path clears the message list when the fetch fails, so a network blip at the
    // moment the user pressed Enter used to empty the conversation they were writing into.
    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello', currentConversationId: 'conv-a' }) },
    );

    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });

    expect(loadMessages).toHaveBeenCalledWith('conv-a', undefined, { silent: true });
  });

  it('does not yank the user back when they leave the new-chat URL while the reply lands', async () => {
    // The callbacks object handed to StreamingContext is frozen at send time, so a pathname
    // read inside it describes where the user was when they pressed Enter. Deferring the sync
    // widens that gap to the whole reconciliation, so the guard has to be re-read at navigation
    // time - which is why it lives in an effect.
    let releaseLoad!: () => void;
    loadMessages.mockImplementation(() => new Promise<void>((resolve) => { releaseLoad = resolve; }));

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );
    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });
    hookResult.rerender(defaultOptions({ currentConversationId: 'conv-a' }));

    await act(async () => {
      capturedCallbacks.onStreamComplete('conv-a');
    });

    // The user walks off to a route that has nothing to do with conversations.
    currentPathname = '/app/workflows';
    hookResult.rerender(defaultOptions({ currentConversationId: 'conv-a' }));

    await act(async () => {
      releaseLoad();
    });

    expect(routerReplaceMock).not.toHaveBeenCalled();
  });

  it('retries the post-stream reconciliation once before giving up', async () => {
    // The reconciliation is silent, not optional: until the persisted reply lands in messages[]
    // ChatCore holds the queued-message drain on it, and there is no banner to explain the
    // wait. A silent load REJECTS on failure precisely so this retry is possible - swallowing
    // the error in useMessages would make this code unreachable.
    loadMessages
      .mockRejectedValueOnce(new Error('503'))
      .mockResolvedValueOnce(undefined);

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );
    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });
    hookResult.rerender(defaultOptions({ currentConversationId: 'conv-a' }));
    loadMessages.mockClear();
    loadMessages
      .mockRejectedValueOnce(new Error('503'))
      .mockResolvedValueOnce(undefined);

    await act(async () => {
      capturedCallbacks.onStreamComplete('conv-a');
    });

    await waitFor(() => expect(loadMessages).toHaveBeenCalledTimes(2));
    expect(loadMessages).toHaveBeenNthCalledWith(2, 'conv-a', undefined, { silent: true });
  });

  it('gives up after the retry rather than looping', async () => {
    loadMessages.mockRejectedValue(new Error('503'));

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );
    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });
    hookResult.rerender(defaultOptions({ currentConversationId: 'conv-a' }));
    loadMessages.mockClear();

    await act(async () => {
      capturedCallbacks.onStreamComplete('conv-a');
    });

    // Two attempts, and the URL still syncs: a conversation stranded on the new-chat URL would
    // not resolve on refresh, share or breadcrumb.
    await waitFor(() => expect(routerReplaceMock).toHaveBeenCalled());
    expect(loadMessages).toHaveBeenCalledTimes(2);
  });

  it('navigates once per request, not once per effect run', async () => {
    // The request lives in a ref that the effect CONSUMES. Without that, any later re-run of
    // the effect - it depends on pathname and router, both of which change on navigation -
    // would replay the same request and yank the reader back to a conversation they had
    // already left once.
    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );
    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });
    hookResult.rerender(defaultOptions({ currentConversationId: 'conv-a' }));

    await act(async () => {
      capturedCallbacks.onStreamComplete('conv-a');
    });
    await waitFor(() => expect(routerReplaceMock).toHaveBeenCalledTimes(1));

    // A dep of the effect changes - here the locale prefix of the very URL it just left.
    currentPathname = '/fr/app/chat';
    await act(async () => {
      hookResult.rerender(defaultOptions({ currentConversationId: 'conv-a' }));
    });

    expect(routerReplaceMock).toHaveBeenCalledTimes(1);
  });

  it('does not touch the URL when the send came from an existing conversation', async () => {
    // The guard that matters is in the effect and reads the LIVE pathname, so a send from
    // /app/c/{id} resolves to "nothing to do" wherever the reader ends up.
    currentPathname = '/app/c/conv-a';

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello', currentConversationId: 'conv-a' }) },
    );
    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });

    await act(async () => {
      capturedCallbacks.onStreamComplete('conv-a');
    });

    expect(routerReplaceMock).not.toHaveBeenCalled();
  });

  it('keeps firstConversationStopVisibleWhileStreamCreationIsPending', async () => {
    let resolveSend!: () => void;
    sendMessageMock.mockImplementationOnce((_payload: any, callbacks: any) => {
      capturedCallbacks = callbacks;
      return new Promise<string>((resolve) => {
        resolveSend = () => {
          callbacks.onConversationCreated?.('conv-a');
          resolve('conv-a');
        };
      });
    });

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );

    let sendPromise!: Promise<void>;
    act(() => {
      sendPromise = hookResult.result.current.handleSendMessage('hello');
    });

    await waitFor(() => expect(hookResult.result.current.isStartingStream).toBe(true));

    await act(async () => {
      resolveSend();
      await sendPromise;
    });

    // The starting bridge stays UP after the send POST resolves. It is now released
    // only when the real stream is observed active (the ChatPageV2 latch) or on a
    // terminal stream event - NOT on POST-resolve. Dropping it on POST-resolve was
    // the bug that flashed a greyed-out Send button between "POST done" and
    // "stream active", so Stop must remain visible here.
    expect(hookResult.result.current.isStartingStream).toBe(true);

    // Terminal safety net: onStreamComplete clears the bridge even if the consumer
    // never observed isStreaming=true (e.g. stream began + ended in one batch).
    act(() => {
      capturedCallbacks.onStreamComplete('conv-a');
    });

    expect(hookResult.result.current.isStartingStream).toBe(false);
  });

  it('deferredStopBeforeConversationIdStopsCreatedConversation', async () => {
    let resolveSend!: () => void;
    sendMessageMock.mockImplementationOnce((_payload: any, callbacks: any) => {
      capturedCallbacks = callbacks;
      return new Promise<string>((resolve) => {
        resolveSend = () => {
          callbacks.onConversationCreated?.('conv-a');
          resolve('conv-a');
        };
      });
    });

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );

    let sendPromise!: Promise<void>;
    act(() => {
      sendPromise = hookResult.result.current.handleSendMessage('hello');
    });

    await waitFor(() => expect(hookResult.result.current.isStartingStream).toBe(true));

    await act(async () => {
      await hookResult.result.current.handleStopStream();
    });
    expect(stopStreamMock).not.toHaveBeenCalled();

    await act(async () => {
      resolveSend();
      await sendPromise;
    });

    expect(stopStreamMock).toHaveBeenCalledWith('conv-a');
  });

  it('releases the starting bridge when sendMessage returns null WITHOUT onError (insufficient-credits / storage-quota / missing-api-key path)', async () => {
    // 402 / 413 / missing-api-key: StreamingContext.sendMessage surfaces a modal,
    // dispatches an internal ERROR, and returns null WITHOUT invoking onError or
    // throwing. No stream goes active, so the ChatPageV2 latch never fires and
    // neither the onError nor onStreamComplete safety net runs. The composer must
    // still leave the Stop state - otherwise it is stuck on "Stop" with no way to
    // recover (clicking Stop while starting only defers). Pre-fix (the clear lived
    // in `finally`, which this change removed) this stranded isStartingStream=true.
    sendMessageMock.mockImplementationOnce(async (_payload: any, _callbacks: any) => {
      // Deliberately does NOT call onError / onConversationCreated - mirrors the
      // swallowed-error branches in StreamingContext.sendMessage.
      return null;
    });

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );

    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });

    expect(sendMessageMock).toHaveBeenCalledTimes(1);
    // Bridge was raised on send, then released on the null return.
    expect(hookResult.result.current.isStartingStream).toBe(false);
  });

  it('releases the starting bridge via the onError safety net (stream errored, never goes active)', async () => {
    sendMessageMock.mockImplementationOnce(async (_payload: any, callbacks: any) => {
      callbacks.onError?.({ message: 'boom', retryable: true });
      return null;
    });

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );

    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });

    expect(setSendError).toHaveBeenCalled();
    expect(hookResult.result.current.isStartingStream).toBe(false);
  });

  it('forwards keepPendingActions for credential-card resume sends', async () => {
    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ currentConversationId: 'conv-a' }) },
    );

    await act(async () => {
      await hookResult.result.current.handleSendMessage(
        'Configured credentials for Instagram Login',
        undefined,
        undefined,
        { keepPendingActions: true },
      );
    });

    expect(sendMessageMock).toHaveBeenCalledTimes(1);
    expect(sendMessageMock.mock.calls[0][0]).toMatchObject({
      message: 'Configured credentials for Instagram Login',
      conversationId: 'conv-a',
      keepPendingActions: true,
    });
  });

  it('retry keeps keepPendingActions for failed credential-card resume sends', async () => {
    sendMessageMock
      .mockImplementationOnce(async (_payload: any, callbacks: any) => {
        callbacks.onError?.({ message: 'Streaming is temporarily unavailable. Please retry.', retryable: true });
        return null;
      })
      .mockImplementationOnce(async (_payload: any, callbacks: any) => {
        callbacks.onConversationCreated?.('conv-a');
        return 'conv-a';
      });
    let retry: (() => unknown) | undefined;
    setSendError.mockImplementation((error) => {
      retry = error?.onRetry;
    });

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ currentConversationId: 'conv-a' }) },
    );

    await act(async () => {
      await hookResult.result.current.handleSendMessage(
        'Configured credentials for Instagram Login',
        undefined,
        undefined,
        { keepPendingActions: true },
      );
    });

    expect(retry).toBeTypeOf('function');
    await act(async () => {
      await retry?.();
    });

    expect(sendMessageMock).toHaveBeenCalledTimes(2);
    expect(sendMessageMock.mock.calls[1][0]).toMatchObject({
      message: 'Configured credentials for Instagram Login',
      conversationId: 'conv-a',
      keepPendingActions: true,
    });
  });

  it('releases the starting bridge when the send POST itself throws', async () => {
    sendMessageMock.mockImplementationOnce(async () => {
      throw new Error('network down');
    });

    const hookResult = renderHook(
      (props: ReturnType<typeof defaultOptions>) => useMessageHandlersV2(props),
      { initialProps: defaultOptions({ inputValue: 'hello' }) },
    );

    await act(async () => {
      await hookResult.result.current.handleSendMessage('hello');
    });

    // Outer catch set a retryable error and released the bridge.
    expect(setSendError).toHaveBeenCalled();
    expect(hookResult.result.current.isStartingStream).toBe(false);
  });
});

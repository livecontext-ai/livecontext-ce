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
vi.mock('next/navigation', () => ({
  usePathname: () => '/app/chat',
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

    // loadMessages SHOULD be called for the matching conversation.
    expect(loadMessages).toHaveBeenCalledWith('conv-a');
    // URL sync is deferred to stream-complete (2c8524b39): still on the
    // new-chat URL, so the route is replaced to the created conversation.
    expect(routerReplaceMock).toHaveBeenCalledWith('/app/c/conv-a', { scroll: false });
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

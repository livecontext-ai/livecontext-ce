/**
 * @vitest-environment jsdom
 *
 * Regression tests for {@link useMessages} executionId threading.
 *
 * The bug: the right-side conversation panel (`ConversationPanelContent`) used to
 * call `conversationApi.getMessages` (un-paginated) instead of the paginated
 * `getPaginatedMessages` endpoint. For an Email Digest agent with 200+ messages
 * (each carrying MB-sized `tool_calls` payload), this loaded the entire history
 * + Java subList-200 truncation, blocking the panel for seconds.
 *
 * After the fix, `useMessages({ executionId })` calls `getPaginatedMessages` with
 * the executionId scoped server-side, so the right panel paginates the same way
 * the main chat does. These tests pin:
 *   1. `loadMessages` threads the executionId option into the API call.
 *   2. `loadOlderMessages` reuses the same executionId on subsequent pages.
 *   3. No executionId → option must be falsy (server falls back to un-scoped).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

// Mock the API client BEFORE importing the hook.
vi.mock('@/lib/api/conversationApi', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api/conversationApi')>(
    '@/lib/api/conversationApi'
  );
  return {
    ...actual,
    conversationApi: {
      getPaginatedMessages: vi.fn(),
      addMessage: vi.fn(),
    },
  };
});

import { conversationApi } from '@/lib/api/conversationApi';
import { useMessages } from '../useMessages';

const getPaginatedMessagesMock = conversationApi.getPaginatedMessages as ReturnType<typeof vi.fn>;

beforeEach(() => {
  getPaginatedMessagesMock.mockReset();
  getPaginatedMessagesMock.mockResolvedValue({ content: [], totalElements: 0 });
});

describe('useMessages - executionId threading', () => {
  it('threads executionId into loadMessages → getPaginatedMessages options', async () => {
    const { result } = renderHook(() => useMessages({ executionId: 'exec-42' }));

    await act(async () => {
      await result.current.loadMessages('conv-1');
    });

    expect(getPaginatedMessagesMock).toHaveBeenCalledTimes(1);
    expect(getPaginatedMessagesMock).toHaveBeenCalledWith(
      'conv-1',
      0,
      10,
      { executionId: 'exec-42' },
    );
  });

  it('threads "latest" sentinel for executionId so the server resolves the most recent execution', async () => {
    const { result } = renderHook(() => useMessages({ executionId: 'latest' }));

    await act(async () => {
      await result.current.loadMessages('conv-2');
    });

    expect(getPaginatedMessagesMock).toHaveBeenCalledWith(
      'conv-2',
      0,
      10,
      { executionId: 'latest' },
    );
  });

  it('omits executionId from options when the hook is invoked without it (regression - the right panel must not silently scope)', async () => {
    const { result } = renderHook(() => useMessages());

    await act(async () => {
      await result.current.loadMessages('conv-3');
    });

    expect(getPaginatedMessagesMock).toHaveBeenCalledWith(
      'conv-3',
      0,
      10,
      { executionId: undefined },
    );
  });

  it('loadOlderMessages reuses the same executionId as the initial load (pagination must stay scoped)', async () => {
    // Seed totalElements > size so hasMoreMessages becomes true.
    getPaginatedMessagesMock
      .mockResolvedValueOnce({ content: [], totalElements: 25 })
      .mockResolvedValueOnce({ content: [], totalElements: 25 });

    const { result } = renderHook(() => useMessages({ executionId: 'exec-99' }));

    await act(async () => {
      await result.current.loadMessages('conv-4');
    });
    await act(async () => {
      await result.current.loadOlderMessages('conv-4');
    });

    expect(getPaginatedMessagesMock).toHaveBeenNthCalledWith(
      1,
      'conv-4',
      0,
      10,
      { executionId: 'exec-99' },
    );
    expect(getPaginatedMessagesMock).toHaveBeenNthCalledWith(
      2,
      'conv-4',
      1,
      10,
      { executionId: 'exec-99' },
    );
  });

  it('loadMessages keeps a stable identity across re-renders - pins the infinite-loop regression in the right-side panel', async () => {
    // Bug class: after ce78e7e09 (pagination), the right-side
    // `ConversationPanelContent` put `loadMessages` in a useEffect dep array.
    // The hook's `loadMessages` useCallback depends on `loadingTimeout`, which
    // came from `useTimeoutWarning(60000, () => setLoadingTimeout(true), '…')`
    // - the inline lambda was a fresh identity on every render, retriggering
    // the inner useCallback / useMemo chain and producing a fresh
    // `loadMessages` on each render. Effect re-fired → fetch → setState →
    // re-render → infinite loop. Prod surfaced as ~30 GET /messages/page calls
    // per second on a single conversation.
    //
    // The fix lives in `useTimeoutWarning`: the onTimeout callback is now
    // stored in a ref so a fresh inline lambda from the caller no longer
    // invalidates the returned bundle's identity. This test pins the
    // post-fix contract from the call-site perspective: re-rendering the
    // hook with no input change MUST return the same `loadMessages` ref.
    const { result, rerender } = renderHook(() => useMessages());

    const firstRef = result.current.loadMessages;
    rerender();
    const secondRef = result.current.loadMessages;
    rerender();
    const thirdRef = result.current.loadMessages;

    expect(secondRef).toBe(firstRef);
    expect(thirdRef).toBe(firstRef);
  });

  it('loadOlderMessages does NOT depend on loadingTimeout - structural pin against future dep widening', async () => {
    // Asymmetric defense: today `loadOlderMessages` happens to be stable
    // on pre-fix code too because its useCallback deps don't include
    // `loadingTimeout` (only [clearError, handleError, executionId]).
    // It does NOT reproduce the loop the way `loadMessages` does.
    //
    // We keep the test as a STRUCTURAL guard: if a future refactor adds
    // loadingTimeout to its deps (e.g. someone notices it should also
    // start the loading-timeout warning on scroll-up), this test starts
    // failing on any inline-lambda caller and forces a fix at
    // `useTimeoutWarning` rather than band-aid at `loadOlderMessages`.
    const { result, rerender } = renderHook(() => useMessages());

    const firstRef = result.current.loadOlderMessages;
    rerender();
    expect(result.current.loadOlderMessages).toBe(firstRef);
  });

  it('hasMoreMessages flips to false after the last page even when the page returns exactly `limit` rows (audit must-fix - broken length-heuristic)', async () => {
    // Conversation has exactly 20 messages, limit 10. First page returns 10
    // (totalElements=20 → hasMore=true). Second page returns 10 more (page 1),
    // page index 1 is the last (2 * 10 == 20). Legacy `length === limit` heuristic
    // would keep hasMore=true forever; server-truth pivot must report false.
    const tenMessages = (offset: number) =>
      Array.from({ length: 10 }, (_, i) => ({
        id: `m-${offset + i}`,
        role: 'user',
        content: `msg ${offset + i}`,
        // sortMessagesByTime requires a parseable timestamp; spread one minute apart.
        timestamp: new Date(2026, 0, 1, 0, offset + i).toISOString(),
      }));
    getPaginatedMessagesMock
      .mockResolvedValueOnce({ content: tenMessages(10), totalElements: 20, totalPages: 2 })
      .mockResolvedValueOnce({ content: tenMessages(0), totalElements: 20, totalPages: 2, hasNext: false });

    const { result } = renderHook(() => useMessages());

    await act(async () => {
      await result.current.loadMessages('conv-boundary');
    });
    expect(result.current.hasMoreMessages).toBe(true);

    await act(async () => {
      await result.current.loadOlderMessages('conv-boundary');
    });
    expect(result.current.hasMoreMessages).toBe(false);
  });
});

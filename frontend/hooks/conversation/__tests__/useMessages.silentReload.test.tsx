/**
 * @vitest-environment jsdom
 *
 * Regression tests for the SILENT end-of-stream reconciliation in {@link useMessages}.
 *
 * THE BUG: when a stream finished, `onStreamComplete` reloaded the thread from the server to
 * pick up the persisted row (tool calls, execution id). That reload ran as a full, VISIBLE load:
 * it raised `messagesLoading`, started the slow-load warning, reset pagination, and on failure
 * wiped the list with `setMessages([])`. Together with the all-new objects the fetch returns,
 * the transcript the user had just finished reading repainted itself, which on screen reads as
 * the page refreshing.
 *
 * `loadMessages(id, limit, { silent: true })` is that same reconciliation with no visible side
 * effect. These tests pin both directions: silent changes nothing on screen, and the explicit
 * (navigation) load keeps its loading state and its error handling.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

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
import { resetMessageSnapshots } from '@/lib/chat/messageSnapshotCache';

const getPaginatedMessagesMock = conversationApi.getPaginatedMessages as ReturnType<typeof vi.fn>;

const ROWS: Array<Record<string, unknown>> = [
  {
    id: 'm-1',
    conversationId: 'conv-1',
    role: 'user',
    content: 'hello',
    model: 'deepseek-chat',
    timestamp: '2026-09-12T10:00:00Z',
    createdAt: '2026-09-12T10:00:00Z',
  },
  {
    id: 'm-2',
    conversationId: 'conv-1',
    role: 'assistant',
    content: 'hi there',
    model: 'deepseek-chat',
    timestamp: '2026-09-12T10:00:01Z',
    createdAt: '2026-09-12T10:00:01Z',
  },
];

/** A fresh page payload. Every call deep-clones, exactly like a real fetch deserialising. */
const page = (rows = ROWS, totalElements = rows.length) => ({
  content: JSON.parse(JSON.stringify(rows)),
  totalElements,
  totalPages: Math.max(1, Math.ceil(totalElements / 10)),
});

/**
 * Always resolve with FRESH objects. `mockResolvedValue(page())` would evaluate `page()` once
 * and hand back the same instances every call, so `areMessagesIdentical` would short-circuit on
 * reference equality and the identity contract would never actually be exercised.
 */
const respondWith = (rows = ROWS, totalElements = rows.length) =>
  getPaginatedMessagesMock.mockImplementation(async () => page(rows, totalElements));

/**
 * Hold the fetch open so the state DURING the load can be observed. Awaiting the whole load
 * inside a single `act` would only ever show the final state, and the question these tests
 * answer is what the user sees in between.
 */
function deferFetch() {
  let release!: (payload: unknown) => void;
  getPaginatedMessagesMock.mockImplementation(
    () => new Promise(resolve => { release = resolve; }),
  );
  return { release: (payload: unknown = page()) => release(payload) };
}

/** The main chat: the one instance that owns the cross-remount snapshot. */
const ownerOptions = (conversationId?: string) => ({ conversationId, retainAcrossRemount: true });

beforeEach(() => {
  resetMessageSnapshots();
  getPaginatedMessagesMock.mockReset();
  respondWith();
});

describe('useMessages - silent end-of-stream reconciliation', () => {
  it('never raises messagesLoading during a silent reload', async () => {
    const { result } = renderHook(() => useMessages());
    await act(async () => { await result.current.loadMessages('conv-1'); });

    const { release } = deferFetch();
    let pending!: Promise<void>;
    await act(async () => {
      pending = result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    // Mid-flight: nothing on screen can tell a reconciliation is running.
    expect(result.current.messagesLoading).toBe(false);

    await act(async () => { release(); await pending; });
    expect(result.current.messagesLoading).toBe(false);
  });

  it('an EXPLICIT load still shows its loading state (the silent flag must not leak)', async () => {
    const { result } = renderHook(() => useMessages());
    await act(async () => { await result.current.loadMessages('conv-1'); });

    const { release } = deferFetch();
    let pending!: Promise<void>;
    await act(async () => {
      pending = result.current.loadMessages('conv-1');
    });

    // Same in-flight moment, opposite expectation: navigation-driven loads still report.
    expect(result.current.messagesLoading).toBe(true);

    await act(async () => { release(); await pending; });
    expect(result.current.messagesLoading).toBe(false);
  });

  it('keeps the exact same message objects when the server returns an unchanged thread', async () => {
    const { result } = renderHook(() => useMessages());

    await act(async () => { await result.current.loadMessages('conv-1'); });
    const painted = result.current.messages;

    await act(async () => {
      await result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    // Same array AND same elements, so the reconciliation commits nothing: the tree reconciles
    // to itself and the reader sees no refresh. (See the COMMITS NOTHING test for the full set
    // of state that has to stay put for that to hold.)
    expect(result.current.messages).toBe(painted);
  });

  it('COMMITS NOTHING when the server returns an unchanged thread, start to finish', async () => {
    // The headline property, asserted across the WHOLE reconciliation rather than at its end.
    // The end alone proves little: a plainly visible reload settles back to the same values too.
    // What distinguishes silent is that nothing moves in BETWEEN either - so the assertions run
    // once mid-flight, with the fetch held open, and again after it lands.
    const { result } = renderHook(() => useMessages());

    await act(async () => { await result.current.loadMessages('conv-1'); });
    const snapshot = () => ({
      messages: result.current.messages,
      messagesLoading: result.current.messagesLoading,
      hasMoreMessages: result.current.hasMoreMessages,
      loadingOlderMessages: result.current.loadingOlderMessages,
      loadingTimeout: result.current.loadingTimeout,
      error: result.current.error,
    });
    const before = snapshot();

    const { release } = deferFetch();
    let pending!: Promise<void>;
    await act(async () => {
      pending = result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    // Mid-flight. This is the assertion an explicit load cannot satisfy: it has raised
    // messagesLoading by now, and reset hasMoreMessages on its way in.
    expect(snapshot()).toEqual(before);
    expect(result.current.messages).toBe(before.messages);

    await act(async () => { release(); await pending; });

    // And after, with the server rows merged: same array, same flags, nothing committed.
    expect(snapshot()).toEqual(before);
    expect(result.current.messages).toBe(before.messages);
  });

  it('never arms the slow-load warning', async () => {
    // loadingTimeout.start() schedules a 60s warning that flips a flag the caller renders. A
    // reconciliation that hangs behind a wedged connection would have raised it over a
    // transcript the reader is happily reading.
    vi.useFakeTimers();
    try {
      const { result } = renderHook(() => useMessages());
      await act(async () => { await result.current.loadMessages('conv-1'); });

      const { release } = deferFetch();
      let pending!: Promise<void>;
      await act(async () => {
        pending = result.current.loadMessages('conv-1', undefined, { silent: true });
      });

      await act(async () => { await vi.advanceTimersByTimeAsync(61_000); });
      expect(result.current.loadingTimeout).toBe(false);

      await act(async () => { release(); await pending; });
      expect(result.current.loadingTimeout).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('stops handing a thread over when the thread SHRINKS under the rows still held', async () => {
    // The other direction of the same trap. Compaction (or a deletion) can take a thread back to
    // one page while the merge keeps the older rows already on screen, so "the server says one
    // page" is not enough to call the held list complete: seeding 20 rows that the confirming
    // page-0 fetch then replaces with 10 is the transcript collapse, arriving from the far side.
    respondWith(ROWS, 25);
    const view = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await view.result.current.loadMessages('conv-1'); });
    getPaginatedMessagesMock.mockImplementationOnce(async () => ({
      content: [
        { ...ROWS[0], id: 'old-1', timestamp: '2026-09-12T09:00:00Z', createdAt: '2026-09-12T09:00:00Z' },
        { ...ROWS[1], id: 'old-2', timestamp: '2026-09-12T09:00:01Z', createdAt: '2026-09-12T09:00:01Z' },
      ],
      totalElements: 25,
      totalPages: 3,
      hasNext: true,
    }));
    await act(async () => { await view.result.current.loadOlderMessages('conv-1'); });
    expect(view.result.current.messages).toHaveLength(4);

    // The thread is compacted server-side: one page now, but four rows are still held.
    respondWith(ROWS, 2);
    await act(async () => {
      await view.result.current.loadMessages('conv-1', undefined, { silent: true });
    });
    expect(view.result.current.messages.length).toBeGreaterThan(2);
    view.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toEqual([]);
  });

  it('keeps every existing bubble when the reconciliation brings a NEW message', async () => {
    // The everyday end-of-stream shape, and the case the pre-fix code repainted wholesale.
    const { result } = renderHook(() => useMessages());
    await act(async () => { await result.current.loadMessages('conv-1'); });
    const painted = result.current.messages;

    const withReply = [...ROWS, {
      ...ROWS[1],
      id: 'm-3',
      content: 'follow-up',
      timestamp: '2026-09-12T10:00:02Z',
      createdAt: '2026-09-12T10:00:02Z',
    }];
    respondWith(withReply);

    await act(async () => {
      await result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    expect(result.current.messages).toHaveLength(3);
    expect(result.current.messages[0]).toBe(painted[0]);
    expect(result.current.messages[1]).toBe(painted[1]);
  });

  it('still adopts the server change, and only for the message that changed', async () => {
    const { result } = renderHook(() => useMessages());

    await act(async () => { await result.current.loadMessages('conv-1'); });
    const painted = result.current.messages;

    // The reply comes back persisted, now carrying its tool calls.
    respondWith([ROWS[0], { ...ROWS[1], toolCalls: '[{"name":"search"}]' }]);

    await act(async () => {
      await result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    expect(result.current.messages[1].toolCalls).toBe('[{"name":"search"}]');
    expect(result.current.messages[0]).toBe(painted[0]);
    expect(result.current.messages[1]).not.toBe(painted[1]);
  });

  it('keeps the conversation on screen when the silent reload FAILS (it used to blank it)', async () => {
    const { result } = renderHook(() => useMessages());

    await act(async () => { await result.current.loadMessages('conv-1'); });
    const painted = result.current.messages;
    expect(painted).toHaveLength(2);

    getPaginatedMessagesMock.mockRejectedValue(new Error('503 Service Unavailable'));

    await act(async () => {
      await expect(
        result.current.loadMessages('conv-1', undefined, { silent: true }),
      ).rejects.toThrow('503');
    });

    // Pre-fix this path ran setMessages([]) + handleError: a transient 5xx right after the
    // answer emptied the transcript and raised a banner. Silent is about the SCREEN, so the
    // transcript is untouched and no banner appears - but the caller is still told, which is
    // what lets it retry instead of leaving the persisted reply permanently missing.
    expect(result.current.messages).toBe(painted);
    expect(result.current.error).toBeNull();
  });

  it('an EXPLICIT load still clears the list and surfaces the error on failure', async () => {
    const { result } = renderHook(() => useMessages());
    await act(async () => { await result.current.loadMessages('conv-1'); });
    expect(result.current.messages).toHaveLength(2);

    getPaginatedMessagesMock.mockReset();
    getPaginatedMessagesMock.mockRejectedValue(new Error('boom'));
    await act(async () => { await result.current.loadMessages('conv-1'); });

    expect(result.current.messages).toEqual([]);
    expect(result.current.error).toBeTruthy();
  });

  it('does not rewind the page counter, so scroll-up still walks forward afterwards', async () => {
    // The explicit path resets messagePage to 0 on its way in. Doing that during a
    // reconciliation makes the next scroll-up refetch a page the reader already has: every row
    // dedupes away, the list does not move, and the affordance looks broken.
    respondWith(ROWS, 25);
    const { result } = renderHook(() => useMessages());
    await act(async () => { await result.current.loadMessages('conv-1'); });
    await act(async () => { await result.current.loadOlderMessages('conv-1'); });
    expect(getPaginatedMessagesMock).toHaveBeenNthCalledWith(2, 'conv-1', 1, 10, expect.anything());

    await act(async () => {
      await result.current.loadMessages('conv-1', undefined, { silent: true });
    });
    await act(async () => { await result.current.loadOlderMessages('conv-1'); });

    // Page 2, not page 1 again.
    expect(getPaginatedMessagesMock).toHaveBeenNthCalledWith(4, 'conv-1', 2, 10, expect.anything());
  });

  it('ignores a silent result that arrived for a conversation the reader has left', async () => {
    // Contract guard rather than a regression test: the pre-change code reached the same
    // outcome through the abort branch. What it pins is that the SILENT early return added
    // here keeps that outcome - it drops the stale rows and leaves the visible state alone
    // instead of clearing a loading flag the second load had just raised.
    const { result } = renderHook(() => useMessages());
    await act(async () => { await result.current.loadMessages('conv-1'); });

    const { release } = deferFetch();
    let stale!: Promise<void>;
    await act(async () => {
      stale = result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    const other = [{ ...ROWS[0], id: 'other-1', content: 'another conversation' }];
    respondWith(other);
    await act(async () => { await result.current.loadMessages('conv-2'); });
    const painted = result.current.messages;
    expect(painted.map(m => m.id)).toEqual(['other-1']);

    await act(async () => { release(); await stale; });

    expect(result.current.messages).toBe(painted);
    expect(result.current.messagesLoading).toBe(false);
  });

  it('clears a stale error banner once a silent reconciliation succeeds', async () => {
    // Not a pre-change regression (the explicit path happened to clear on the way in); this
    // pins the replacement. A silent load cannot clear on the way IN without blanking a real
    // banner before knowing its own outcome, so it clears on success instead. Delete that and
    // "Failed to load messages" would survive every later successful reconciliation.
    const { result } = renderHook(() => useMessages());
    getPaginatedMessagesMock.mockRejectedValueOnce(new Error('blip'));
    await act(async () => { await result.current.loadMessages('conv-1'); });
    expect(result.current.error).toBeTruthy();

    respondWith();
    await act(async () => {
      await result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    expect(result.current.error).toBeNull();
  });

  it('turns the "load older" affordance ON when the turn grew the thread past one page', async () => {
    // The regression the first cut of the silent flag shipped: skipping setHasMoreMessages left
    // a conversation that had just grown past the page size reporting "nothing older". The
    // scroll-up affordance never appeared and the older rows were unreachable, with no spinner
    // and no error, until the reader navigated away and back.
    respondWith(ROWS, 10);
    const { result } = renderHook(() => useMessages());
    await act(async () => { await result.current.loadMessages('conv-1'); });
    expect(result.current.hasMoreMessages).toBe(false);

    // The turn persists an assistant reply plus tool messages: 26 rows, three pages.
    respondWith(ROWS, 26);
    await act(async () => {
      await result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    expect(result.current.hasMoreMessages).toBe(true);
  });

  it('does not re-offer "load older" to a reader who already scrolled to the top', async () => {
    // The other direction, and why the answer is page-relative rather than "totalPages > 1":
    // a silent reconcile keeps the page the reader had scrolled back to, so asking the page-0
    // question there would re-enable an affordance that has nothing left to fetch.
    respondWith(ROWS, 25);
    const { result } = renderHook(() => useMessages());
    await act(async () => { await result.current.loadMessages('conv-1'); });

    // 25 rows over a page size of 10 is three pages (0, 1, 2), so page 1 still has a next
    // one and page 2 does not. Walk to the top.
    const olderPage = (hasNext) => ({
      content: JSON.parse(JSON.stringify(ROWS)),
      totalElements: 25,
      totalPages: 3,
      hasNext,
    });
    getPaginatedMessagesMock.mockImplementationOnce(async () => olderPage(true));
    await act(async () => { await result.current.loadOlderMessages('conv-1'); });
    getPaginatedMessagesMock.mockImplementationOnce(async () => olderPage(false));
    await act(async () => { await result.current.loadOlderMessages('conv-1'); });
    expect(result.current.hasMoreMessages).toBe(false);

    respondWith(ROWS, 25);
    await act(async () => {
      await result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    expect(result.current.hasMoreMessages).toBe(false);
  });

  it('keeps a message sent into the NEW conversation while a foreign seed is dropped', async () => {
    // Dropping the whole local list would take the optimistic bubble of the conversation being
    // opened with it - the "new conversation shows empty" clobber, arriving by another door.
    const first = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await first.result.current.loadMessages('conv-1'); });
    first.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toHaveLength(2);

    act(() => {
      remounted.result.current.addMessageLocal('conv-2', {
        role: 'user',
        content: 'first message of the new conversation',
        model: 'deepseek-chat',
        timestamp: '2026-09-12T12:00:00Z',
      } as never);
    });

    respondWith([], 0);
    await act(async () => { await remounted.result.current.loadMessages('conv-2'); });

    expect(remounted.result.current.messages.map(m => m.content))
      .toEqual(['first message of the new conversation']);
  });

  it('leaves the loading flag alone when a silent reload is superseded mid-flight', async () => {
    // Stream completes, the reconciliation starts, the user immediately sends again and the
    // pre-send load supersedes it. The abandoned silent load must not clear a flag it never
    // set, or the second load's spinner would be cancelled by the first load's cleanup.
    const { result } = renderHook(() => useMessages());
    await act(async () => { await result.current.loadMessages('conv-1'); });

    const { release: releaseSilent } = deferFetch();
    let silentLoad!: Promise<void>;
    await act(async () => {
      silentLoad = result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    const { release: releaseExplicit } = deferFetch();
    let explicitLoad!: Promise<void>;
    await act(async () => {
      explicitLoad = result.current.loadMessages('conv-1');
    });
    expect(result.current.messagesLoading).toBe(true);

    // The superseded silent load settles first and must not touch the visible state.
    await act(async () => { releaseSilent(); await silentLoad; });
    expect(result.current.messagesLoading).toBe(true);

    await act(async () => { releaseExplicit(); await explicitLoad; });
    expect(result.current.messagesLoading).toBe(false);
  });
});

describe('useMessages - remount seeding', () => {
  it('paints the previous thread on the first frame after a remount (no skeleton)', async () => {
    const first = renderHook(() => useMessages(ownerOptions('conv-1')));

    await act(async () => { await first.result.current.loadMessages('conv-1'); });
    expect(first.result.current.messages).toHaveLength(2);
    first.unmount();

    // The end-of-stream URL sync swaps /app/chat for /app/c/{id}: a brand-new hook instance.
    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));

    // Before any fetch resolves, the transcript is already there.
    expect(remounted.result.current.messages.map(m => m.id)).toEqual(['m-1', 'm-2']);
    expect(remounted.result.current.messagesLoading).toBe(false);
  });

  it('does not seed a different conversation', async () => {
    const first = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await first.result.current.loadMessages('conv-1'); });
    first.unmount();

    const other = renderHook(() => useMessages(ownerOptions('conv-2')));
    expect(other.result.current.messages).toEqual([]);
  });

  it('records the snapshot under the conversation the messages belong to, not the route param', async () => {
    // Send-from-Home: the page mounts with NO route conversation id, the conversation is
    // created mid-stream and the optimistic user message is added under its real id. That is
    // the id the next mount (/app/c/{id}) will ask for, so it must be the key.
    const fromHome = renderHook(() => useMessages(ownerOptions(undefined)));
    act(() => {
      fromHome.result.current.addMessageLocal('conv-created', {
        role: 'user',
        content: 'hello',
        model: 'deepseek-chat',
        timestamp: '2026-09-12T10:00:00Z',
      } as never);
    });
    fromHome.unmount();

    const afterUrlSync = renderHook(() => useMessages(ownerOptions('conv-created')));
    expect(afterUrlSync.result.current.messages).toHaveLength(1);
    expect(afterUrlSync.result.current.messages[0].content).toBe('hello');
  });

  it('lets the first fetch REPLACE a seed, so a phantom local row cannot survive a remount', async () => {
    // A send that errors leaves its optimistic bubble in the list, and the server will never
    // produce a matching row. Merging the seed with the server response would re-adopt that
    // phantom on every mount, forever: only a full page reload could clear it.
    const fromHome = renderHook(() => useMessages(ownerOptions(undefined)));
    act(() => {
      fromHome.result.current.addMessageLocal('conv-1', {
        role: 'user',
        content: 'never persisted',
        model: 'deepseek-chat',
        timestamp: '2026-09-12T10:00:00Z',
      } as never);
    });
    fromHome.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toHaveLength(1);

    await act(async () => { await remounted.result.current.loadMessages('conv-1'); });

    expect(remounted.result.current.messages.map(m => m.content)).toEqual(['hello', 'hi there']);
  });

  it('still protects the race where the server has not persisted anything yet', async () => {
    // Same replace path, opposite input: an empty response right after a conversation is
    // created must not wipe the optimistic bubble (the "new conversation shows empty" clobber).
    const fromHome = renderHook(() => useMessages(ownerOptions(undefined)));
    act(() => {
      fromHome.result.current.addMessageLocal('conv-1', {
        role: 'user',
        content: 'just sent',
        model: 'deepseek-chat',
        timestamp: '2026-09-12T10:00:00Z',
      } as never);
    });
    fromHome.unmount();

    respondWith([], 0);
    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await remounted.result.current.loadMessages('conv-1'); });

    expect(remounted.result.current.messages).toHaveLength(1);
  });

  it('does not hand over a partially paginated thread', async () => {
    // Seeding half a thread next to a page counter that says "page 0" makes scroll-up walk
    // pages the reader already has. Better no seed (one skeleton) than a truncated transcript.
    respondWith(ROWS, 25);
    const first = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await first.result.current.loadMessages('conv-1'); });
    expect(first.result.current.hasMoreMessages).toBe(true);
    first.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toEqual([]);
  });

  it('a NON-owner instance neither seeds nor writes the shared snapshot', async () => {
    // A conversation can be open in more than one surface at once (the main chat and a side
    // panel). Only the main chat renders the whole thread, so only it may hand a snapshot over.
    // A secondary view fetching its own page would otherwise overwrite the main thread's
    // snapshot with its slice, and the next remount would paint a truncated conversation.
    const owner = renderHook(() => useMessages(ownerOptions('conv-1')));
    const full = [...ROWS, {
      ...ROWS[0], id: 'm-3', content: 'third', timestamp: '2026-09-12T10:00:02Z', createdAt: '2026-09-12T10:00:02Z',
    }];
    respondWith(full);
    await act(async () => { await owner.result.current.loadMessages('conv-1'); });
    expect(owner.result.current.messages).toHaveLength(3);

    // A second view of the same conversation loads a narrower list.
    respondWith([ROWS[0]]);
    const secondary = renderHook(() => useMessages({ conversationId: 'conv-1' }));
    await act(async () => { await secondary.result.current.loadMessages('conv-1'); });
    expect(secondary.result.current.messages).toHaveLength(1);

    // It did not seed itself from the owner's snapshot, and it did not overwrite it either.
    owner.unmount();
    secondary.unmount();
    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toHaveLength(3);
  });

  it('an execution-scoped instance can never own the snapshot, even if asked to', async () => {
    // It holds one execution of a conversation, never the whole thread.
    const scoped = renderHook(() =>
      useMessages({ conversationId: 'conv-1', executionId: 'exec-1', retainAcrossRemount: true }));
    await act(async () => { await scoped.result.current.loadMessages('conv-1'); });
    scoped.unmount();

    const wholeThread = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(wholeThread.result.current.messages).toEqual([]);
  });

  it('stops attributing messages to a conversation once it is cleared', async () => {
    // The exposed setMessages is the streaming path (and the panel's WS message_added): it
    // names no conversation, so whatever it writes is attributed to whichever one the hook
    // believes it is holding. After a clear, that must be none - otherwise the next
    // conversation's rows land under the previous conversation's snapshot key, and reopening it
    // paints someone else's thread.
    const view = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await view.result.current.loadMessages('conv-1'); });

    act(() => { view.result.current.clearMessages(); });
    act(() => {
      view.result.current.setMessages([{
        id: 'from-another-conversation',
        conversationId: 'conv-2',
        role: 'user',
        content: 'a different conversation',
        model: 'deepseek-chat',
        timestamp: '2026-09-12T11:00:00Z',
      } as never]);
    });
    view.unmount();

    // conv-1 still has the snapshot it had before the clear, not the stray rows.
    const back = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(back.result.current.messages.map(m => m.id)).toEqual(['m-1', 'm-2']);
  });

  it('forgets an unconfirmed seed when the conversation is cleared', async () => {
    // Otherwise the NEXT conversation's first fetch would consume the stale seed flag and
    // adopt server truth for the wrong reason (harmless here, but the flag is a claim about
    // the list currently held, and after a clear no list is held).
    const view = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await view.result.current.loadMessages('conv-1'); });
    view.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toHaveLength(2);

    act(() => { remounted.result.current.clearMessages(); });
    act(() => {
      remounted.result.current.addMessageLocal('conv-1', {
        role: 'user',
        content: 'typed after the clear',
        model: 'deepseek-chat',
        timestamp: '2026-09-12T12:00:00Z',
      } as never);
    });

    // The seed is gone, so this fetch merges with what is on screen instead of replacing it
    // the way an unconfirmed seed would.
    respondWith();
    await act(async () => { await remounted.result.current.loadMessages('conv-1'); });

    expect(remounted.result.current.messages.map(m => m.content)).toContain('typed after the clear');
  });

  it('does not blend a seeded conversation into a different one that loads first', async () => {
    // Reachable by opening /app/c/A with a snapshot and moving to /app/c/B before A's own fetch
    // resolves: same route segment, so React re-renders instead of remounting and the hook keeps
    // its state. If the seed is only dropped when the fetch is FOR the seeded conversation, A's
    // rows merge into B's thread and stay there: different ids, absent from the backend, so
    // nothing ever filters them out.
    const first = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await first.result.current.loadMessages('conv-1'); });
    first.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toHaveLength(2);

    // The reader moves on; conv-2's fetch is the first to land.
    respondWith([{ ...ROWS[0], id: 'b-1', content: 'conversation two' }]);
    await act(async () => { await remounted.result.current.loadMessages('conv-2'); });

    expect(remounted.result.current.messages.map(m => m.id)).toEqual(['b-1']);
  });

  it('drops a foreign seed even when the conversation it switched to is empty', async () => {
    // The narrow hole left by adopting server truth alone: mergeMessages keeps local rows when
    // the backend returns nothing (the just-created-conversation race), so a seed belonging to
    // conversation A would be rendered under an empty conversation B - and, the seed flag now
    // consumed, every later fetch would merge it back in. A never sees a way out but a reload.
    const first = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await first.result.current.loadMessages('conv-1'); });
    first.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toHaveLength(2);

    respondWith([], 0);
    await act(async () => { await remounted.result.current.loadMessages('conv-2'); });

    expect(remounted.result.current.messages).toEqual([]);
  });

  it('lets an explicit confirming load clear a seed it could not verify', async () => {
    // Where the seed meets the pre-existing failure path: an EXPLICIT load that fails still
    // clears the list, seed included. That is the honest outcome - the seed was never server
    // truth, and the banner tells the reader why the transcript went.
    const first = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await first.result.current.loadMessages('conv-1'); });
    first.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toHaveLength(2);

    getPaginatedMessagesMock.mockRejectedValue(new Error('500'));
    await act(async () => { await remounted.result.current.loadMessages('conv-1'); });

    expect(remounted.result.current.messages).toEqual([]);
    expect(remounted.result.current.error).toBeTruthy();
  });

  it('revokes a snapshot when only the THREAD SIZE changed, not the list', async () => {
    // Why the page-0 size is state and not a ref. The snapshot effect watches `messages`; a
    // reconciliation can hand back the very same array (identical rows) while telling us the
    // thread is now far bigger than one page. Held in a ref, that fact would never wake the
    // effect, and the stale "complete" snapshot would go on being handed to the next mount.
    respondWith(ROWS, 2);
    const view = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await view.result.current.loadMessages('conv-1'); });
    const painted = view.result.current.messages;

    // Same two rows, but the server now reports 26: three pages.
    respondWith(ROWS, 26);
    await act(async () => {
      await view.result.current.loadMessages('conv-1', undefined, { silent: true });
    });
    // The list itself did not move, which is exactly what makes this case interesting.
    expect(view.result.current.messages).toBe(painted);
    view.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toEqual([]);
  });

  it('does not let a superseded first load burn the seed flag', async () => {
    // The flag says "the list on screen has never been checked against the server", and only
    // the fetch that actually lands may clear it. A first load that gets superseded and returns
    // early must leave it alone: consume it there and the real confirming fetch merges instead
    // of replacing, which is how a never-persisted bubble survives forever.
    const first = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await first.result.current.loadMessages('conv-1'); });
    first.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    act(() => {
      remounted.result.current.addMessageLocal('conv-1', {
        role: 'user',
        content: 'never persisted',
        model: 'deepseek-chat',
        timestamp: '2026-09-12T12:00:00Z',
      } as never);
    });
    expect(remounted.result.current.messages).toHaveLength(3);

    // A first load is started and then superseded before it can land.
    const { release } = deferFetch();
    let superseded!: Promise<void>;
    await act(async () => {
      superseded = remounted.result.current.loadMessages('conv-1');
    });
    respondWith();
    await act(async () => { await remounted.result.current.loadMessages('conv-1'); });
    await act(async () => { release(); await superseded.catch(() => {}); });

    // The confirming load replaced the seed, phantom included.
    expect(remounted.result.current.messages.map(m => m.id)).toEqual(['m-1', 'm-2']);
  });

  it('forgets how big the thread was once the conversation is cleared', async () => {
    // Otherwise the NEXT conversation inherits the previous one's page-0 size. Landing on a
    // thread that needed several pages, that is a standing -1: the new conversation could never
    // hand a snapshot over, and every remount of it would blank.
    respondWith(ROWS, 25);
    const view = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await view.result.current.loadMessages('conv-1'); });

    act(() => { view.result.current.clearMessages(); });
    act(() => {
      view.result.current.addMessageLocal('conv-2', {
        role: 'user',
        content: 'first message',
        model: 'deepseek-chat',
        timestamp: '2026-09-12T13:00:00Z',
      } as never);
    });
    view.unmount();

    const opened = renderHook(() => useMessages(ownerOptions('conv-2')));
    expect(opened.result.current.messages.map(m => m.content)).toEqual(['first message']);
  });

  it('applies a silent reconciliation when no explicit load ever ran (send-from-Home)', async () => {
    // The headline flow. Sending from /app/chat creates the conversation mid-stream and adds the
    // optimistic bubble locally, so NOTHING has ever claimed the hook by the time the reply is
    // persisted. A staleness guard keyed on "which conversation is being loaded" reads null here
    // and, taken strictly, drops the only reconciliation that turn will ever get: the persisted
    // reply with its tool calls never reaches the screen, and the run stays green.
    const { result } = renderHook(() => useMessages());
    act(() => {
      result.current.addMessageLocal('conv-new', {
        role: 'user',
        content: 'hello',
        model: 'deepseek-chat',
        timestamp: '2026-09-12T10:00:00Z',
      } as never);
    });

    respondWith([ROWS[0], { ...ROWS[1], toolCalls: '[{"name":"search"}]' }]);
    await act(async () => {
      await result.current.loadMessages('conv-new', undefined, { silent: true });
    });

    expect(result.current.messages.some(m => m.toolCalls === '[{"name":"search"}]')).toBe(true);
  });

  it('drops a silent reconciliation that lands after the transcript was CLEARED', async () => {
    // The sidebar can wipe a conversation's history while the panel it is open in has a
    // post-stream reconciliation in flight. If that result still applies, the rows the server
    // was just told to forget are painted back: the wipe undone by its own request, and the
    // reader has to reload to make them go away.
    const { result } = renderHook(() => useMessages());
    await act(async () => { await result.current.loadMessages('conv-1'); });
    expect(result.current.messages).toHaveLength(2);

    const { release } = deferFetch();
    let pending!: Promise<void>;
    await act(async () => {
      pending = result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    act(() => { result.current.clearMessages(); });
    expect(result.current.messages).toEqual([]);

    await act(async () => { release(); await pending; });

    expect(result.current.messages).toEqual([]);
  });

  it('drops a silent reconciliation superseded by an explicit load of another conversation', async () => {
    // The silent load must not claim the in-flight-conversation ref either. If it did, the
    // explicit load of the conversation the reader actually opened would look stale on arrival
    // and be dropped, leaving them on an empty thread with no spinner to explain it.
    const { result } = renderHook(() => useMessages());
    await act(async () => { await result.current.loadMessages('conv-1'); });

    const { release: releaseSilent } = deferFetch();
    let silentLoad!: Promise<void>;
    await act(async () => {
      silentLoad = result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    respondWith([{ ...ROWS[0], id: 'b-1', content: 'the conversation the reader opened' }]);
    await act(async () => { await result.current.loadMessages('conv-2'); });
    expect(result.current.messages.map(m => m.id)).toEqual(['b-1']);

    await act(async () => { releaseSilent(); await silentLoad; });

    expect(result.current.messages.map(m => m.id)).toEqual(['b-1']);
  });

  it('a silent reconciliation does not throw away an explicit load that is still in flight', async () => {
    // A cold mount is fetching with its skeleton up when a reconnected stream completes. If the
    // reconciliation renewed the abort controller, the mount's own rows would be discarded on
    // arrival and the reader would sit on an empty list until the silent fetch landed.
    const { result } = renderHook(() => useMessages());

    const { release: releaseExplicit } = deferFetch();
    let explicitLoad!: Promise<void>;
    await act(async () => {
      explicitLoad = result.current.loadMessages('conv-1');
    });

    const { release: releaseSilent } = deferFetch();
    let silentLoad!: Promise<void>;
    await act(async () => {
      silentLoad = result.current.loadMessages('conv-1', undefined, { silent: true });
    });

    await act(async () => { releaseExplicit(page()); await explicitLoad; });
    expect(result.current.messages).toHaveLength(2);
    expect(result.current.messagesLoading).toBe(false);

    await act(async () => { releaseSilent(page()); await silentLoad; });
    expect(result.current.messages).toHaveLength(2);
  });

  it('does not hand over a thread that was paged to the end', async () => {
    // "Nothing left to page in" is NOT "fits in one page". Scroll to the top of a 25-message
    // thread and the list is complete, but a page-0 fetch still returns 10 - and since a seed
    // is confirmed by replacement, seeding 25 would show the transcript collapse to 10.
    respondWith(ROWS, 25);
    const view = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await view.result.current.loadMessages('conv-1'); });
    getPaginatedMessagesMock.mockImplementation(async () => ({
      content: JSON.parse(JSON.stringify(ROWS)),
      totalElements: 25,
      totalPages: 3,
      hasNext: false,
    }));
    await act(async () => { await view.result.current.loadOlderMessages('conv-1'); });
    expect(view.result.current.hasMoreMessages).toBe(false);
    view.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toEqual([]);
  });

  it('stops handing a thread over once it grows past the first page', async () => {
    // A thread at 9 messages fits; the turn that takes it to 11 does not. The snapshot decision
    // comes from what the FETCH reported about the whole thread, not from the list on screen:
    // page 0 still hands back ten rows either way.
    const nine = Array.from({ length: 9 }, (_, i) => ({
      ...ROWS[0],
      id: 'n-' + i,
      timestamp: '2026-09-12T10:0' + i + ':00Z',
      createdAt: '2026-09-12T10:0' + i + ':00Z',
    }));
    respondWith(nine, 9);
    const view = renderHook(() => useMessages(ownerOptions('conv-1')));
    await act(async () => { await view.result.current.loadMessages('conv-1'); });

    // The turn lands: page 0 now returns ten of eleven rows.
    const ten = [...nine, { ...ROWS[1], id: 'n-9', timestamp: '2026-09-12T10:09:00Z', createdAt: '2026-09-12T10:09:00Z' }];
    respondWith(ten, 11);
    await act(async () => {
      await view.result.current.loadMessages('conv-1', undefined, { silent: true });
    });
    view.unmount();

    const remounted = renderHook(() => useMessages(ownerOptions('conv-1')));
    expect(remounted.result.current.messages).toEqual([]);
  });
});

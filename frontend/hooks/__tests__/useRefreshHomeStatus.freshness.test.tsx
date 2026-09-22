/**
 * @vitest-environment jsdom
 *
 * Tests for the freshness bound on {@code useRefreshHomeStatus} - the form the bell's Triggers
 * tab uses when the user lands on it.
 *
 * <p>Why the bound exists: the automation rows ride the always-on home-status query (60s poll,
 * 30s staleTime). Every producer of a row now asks for them again after acting, and the visit
 * is the net underneath. Asking on every visit is the fix; asking UNCONDITIONALLY on every
 * visit would turn a tab click into a request.
 *
 * <p>Two layers on purpose. The rule is a pure function, tested as a truth table, because its
 * branches stop being observable once they are inlined in a hook. The hook is then tested
 * against a REAL QueryClient with a real queryFn and a mounted observer, counting fetches
 * rather than spy calls: a suite that stubs `invalidateQueries` can only prove that a function
 * was called, never that rows actually come back.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';
import { shouldAskForHomeStatus, useHomeStatus, useRefreshHomeStatus } from '../useHomeStatus';
import { TRIGGERS_ROWS_FRESH_FOR_MS } from '@/components/chat/NotificationBell';

const authMock = vi.hoisted(() => ({
  current: { numericUserId: 42, isLoading: false, isAuthenticated: true },
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => authMock.current,
}));

vi.mock('@/lib/websocket/use-channel', () => ({
  useChannel: () => {},
}));

const orgMock = vi.hoisted(() => ({ currentOrgId: null as string | null }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (selector: (s: { currentOrgId: string | null }) => unknown) =>
    selector({ currentOrgId: orgMock.currentOrgId }),
}));

vi.mock('@/lib/api/orchestrator/home-status.service', () => ({
  homeStatusService: {
    getHomeStatus: vi.fn(async () => ({
      automations: [],
      items: [],
      unreadCount: 0,
      lastSeenAt: null,
    })),
    markAllNotificationsRead: vi.fn(async () => undefined),
  },
}));

import { homeStatusService } from '@/lib/api/orchestrator/home-status.service';

const HOME_KEY = ['org', '__personal__', 'home-status'] as const;
const BOUND_MS = 2_000;
const NOW = 1_800_000_000_000;

function payload() {
  return { automations: [], items: [], unreadCount: 0, lastSeenAt: null };
}

describe('shouldAskForHomeStatus - the rule behind the bound', () => {
  it('Says no when the cache holds no such query at all', () => {
    // Invalidating would match nothing anyway, and no rows are on screen to be stale.
    expect(shouldAskForHomeStatus(undefined, 0, BOUND_MS, NOW)).toBe(false);
  });

  it('Says no while the query exists but has never settled (its first load owns that moment)', () => {
    // The reachable half of the empty-cache case, and the one a test seeding nothing at all
    // misses: the bell calls useHomeStatus() in the same component, so the query object is
    // always there and only its timestamps are still zero. Cancelling that first load would
    // delay the rows and replace nothing.
    expect(shouldAskForHomeStatus({ dataUpdatedAt: 0, errorUpdatedAt: 0 }, 0, BOUND_MS, NOW)).toBe(false);
  });

  it('Says yes when the last answer is older than the bound', () => {
    expect(shouldAskForHomeStatus({ dataUpdatedAt: NOW - 10_000, errorUpdatedAt: 0 }, 0, BOUND_MS, NOW)).toBe(true);
  });

  it('Says no when the last answer is inside the bound (a tab flip is not a request)', () => {
    expect(shouldAskForHomeStatus({ dataUpdatedAt: NOW - 200, errorUpdatedAt: 0 }, 0, BOUND_MS, NOW)).toBe(false);
  });

  it('Says yes exactly ON the bound, so the window is closed at one end only', () => {
    expect(shouldAskForHomeStatus({ dataUpdatedAt: NOW - BOUND_MS, errorUpdatedAt: 0 }, 0, BOUND_MS, NOW)).toBe(true);
  });

  it('Counts a FAILED load as an answer, so a query whose load failed is retried on a later visit', () => {
    // dataUpdatedAt never moves on failure. Reading it alone would leave a failed query
    // unaskable for good, and a failed load is exactly when a visit most wants to retry.
    expect(shouldAskForHomeStatus({ dataUpdatedAt: 0, errorUpdatedAt: NOW - 10_000 }, 0, BOUND_MS, NOW)).toBe(true);
  });

  it('Counts a FAILED load as an answer on the silent side too, so a failing query is not hammered', () => {
    expect(shouldAskForHomeStatus({ dataUpdatedAt: 0, errorUpdatedAt: NOW - 200 }, 0, BOUND_MS, NOW)).toBe(false);
  });

  it('Silences a second tab click half a second later AT THE SHIPPED BOUND, not just at a test value', () => {
    // Everything else here runs a local BOUND_MS. That leaves the value the product actually
    // ships free to drift to 0 - which silently turns every tab click back into a request -
    // with the whole suite green. This is the one assertion tied to the real constant.
    expect(
      shouldAskForHomeStatus({ dataUpdatedAt: NOW - 500, errorUpdatedAt: 0 }, 0, TRIGGERS_ROWS_FRESH_FOR_MS, NOW),
    ).toBe(false);
  });

  it('Says no when WE asked inside the bound, however old the last answer still reads', () => {
    // The half that makes it safe to ask while a fetch is on the wire: invalidating cancels
    // and restarts it, so without this a fast tab-flipper would restart the same request
    // forever and never see a row land.
    expect(shouldAskForHomeStatus({ dataUpdatedAt: NOW - 60_000, errorUpdatedAt: 0 }, NOW - 200, BOUND_MS, NOW)).toBe(false);
  });
});

describe('useRefreshHomeStatus - against a real query cache', () => {
  let queryClient: QueryClient;

  function wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  }

  /** Mounts the bell's pair: the query that owns the rows, and the refresh that tops it up. */
  function renderBellPair() {
    return renderHook(
      () => ({ status: useHomeStatus(), refresh: useRefreshHomeStatus() }),
      { wrapper },
    );
  }

  /** Moves the wall clock without touching timers: "the rows got older" means exactly this. */
  function advanceClockBy(ms: number) {
    const base = Date.now();
    vi.spyOn(Date, 'now').mockImplementation(() => base + ms);
  }

  /** Replaces the next answer with one that never arrives, and hands back its release. */
  function holdNextAnswer(): () => void {
    let release!: () => void;
    (homeStatusService.getHomeStatus as ReturnType<typeof vi.fn>).mockImplementationOnce(
      () => new Promise((resolve) => { release = () => resolve(payload()); }),
    );
    return () => release();
  }

  beforeEach(() => {
    vi.clearAllMocks();
    authMock.current = { numericUserId: 42, isLoading: false, isAuthenticated: true };
    orgMock.currentOrgId = null;
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  });
  afterEach(() => {
    vi.restoreAllMocks();
    queryClient.clear();
  });

  it('A bounded ask on stale rows really refetches them (regression: the tab read one step behind a just-pinned workflow)', async () => {
    const { result } = renderBellPair();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1));

    advanceClockBy(10_000);
    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });

    // Real fetches, not spy calls: the invalidation has to reach a mounted observer and come
    // back with rows, which is the whole claim the bell depends on.
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2));
  });

  it('A bounded ask on fresh rows costs nothing at all', async () => {
    const { result } = renderBellPair();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1));

    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });
    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });
    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });

    expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1);
  });

  it('Asks even while a fetch is on the wire, because that fetch can predate the user action', async () => {
    const { result } = renderBellPair();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1));

    // A poll issued BEFORE the user pinned anything: its answer describes the world as it was,
    // and letting it land unchallenged would clear the staleness for another full interval.
    const releasePoll = holdNextAnswer();
    await act(async () => { void queryClient.refetchQueries({ queryKey: HOME_KEY }); });
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2));

    advanceClockBy(10_000);
    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });

    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(3));
    await act(async () => { releasePoll(); });
  });

  it('Does not restart the request it just asked for, however stale the last answer still reads', async () => {
    // The starvation the previous test opens up: while our own request is in flight the last
    // ANSWER stays old, so age alone would let every further visit cancel and restart it.
    const { result } = renderBellPair();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1));

    const releaseOurs = holdNextAnswer();
    advanceClockBy(10_000);
    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2));

    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });
    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });

    expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2);
    await act(async () => { releaseOurs(); });
  });

  it('Retries a query whose load FAILED, once the bound has passed (end to end, not just the rule)', async () => {
    // `dataUpdatedAt` never moves on failure, so a rule reading it alone would leave a failed
    // query unaskable for good - and a failed load is exactly when a visit wants to retry. The
    // truth table pins the rule; this pins that the hook and a real cache agree with it.
    (homeStatusService.getHomeStatus as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('offline'));
    const { result } = renderBellPair();
    await waitFor(() => expect(result.current.status.error).toBeTruthy());
    expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1);

    advanceClockBy(10_000);
    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });

    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2));
  });

  it('Asks every time when called with no options, however fresh the rows are (the post-action form)', async () => {
    // What every pin site and the schedule row menu use: the caller just changed the data, so
    // how old the cached copy is says nothing about whether it is still right.
    const { result } = renderBellPair();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1));

    await act(async () => { result.current.refresh(); });
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2));
    await act(async () => { result.current.refresh(); });
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(3));
  });

  it('Starts out having never asked, so the very first visit after mount is not silenced', async () => {
    // A ref seeded with the mount time instead of "never" would swallow exactly the visit the
    // user makes right after acting, which is the whole case. Rows already stale at mount, and
    // the mount refetch held open so the answer cannot refresh them either.
    queryClient.setQueryData(HOME_KEY, payload());
    const seeded = queryClient.getQueryCache().find({ queryKey: HOME_KEY })!;
    seeded.state = { ...seeded.state, dataUpdatedAt: Date.now() - 60_000 };

    const releaseMount = holdNextAnswer();
    const { result } = renderBellPair();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1));

    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });

    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2));
    await act(async () => { releaseMount(); });
  });

  it('An unconditional ask counts as an ask, so a bounded one right behind it stays silent', async () => {
    const { result } = renderBellPair();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1));

    const release = holdNextAnswer();
    advanceClockBy(10_000);
    await act(async () => { result.current.refresh(); });
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2));

    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });

    expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2);
    await act(async () => { release(); });
  });

  it('Re-arms after a workspace switch: an ask against the previous workspace silences nothing here', async () => {
    // The stamp is per workspace. Shared, the first visit to the Triggers tab after switching
    // would read the ask made against the workspace the user just left, and show its rows.
    // Both fetches are held open so neither workspace's rows can refresh themselves and make
    // this pass for the wrong reason: what is being measured is the STAMP, not the data.
    const releaseFirst = holdNextAnswer();
    const { result, rerender } = renderBellPair();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1));
    await act(async () => { releaseFirst(); });

    const releaseOurs = holdNextAnswer();
    advanceClockBy(10_000);
    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2));

    // Switch to a workspace whose rows are already stale, as a cache visited earlier in the
    // session would be. Its own mount fetch is held too, so those rows stay stale.
    const otherKey = ['org', 'org-2', 'home-status'] as const;
    queryClient.setQueryData(otherKey, payload());
    const other = queryClient.getQueryCache().find({ queryKey: otherKey })!;
    other.state = { ...other.state, dataUpdatedAt: Date.now() - 60_000 };
    const releaseMount = holdNextAnswer();
    orgMock.currentOrgId = 'org-2';
    rerender();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(3));

    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });

    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(4));
    await act(async () => { releaseOurs(); releaseMount(); });
  });

  it('Shares the ask between SURFACES: a pin here silences the bell visit that follows it', async () => {
    // This is the production shape, and the reason the stamp cannot live in a component ref.
    // The pin sites and the bell are different components: with a per-instance ref the visit
    // sees no ask, cancels the request the pin just put on the wire, and starts another - two
    // requests for one action, the second one from scratch.
    const pinSite = renderHook(() => useRefreshHomeStatus(), { wrapper });
    const { result: bell } = renderBellPair();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1));

    const release = holdNextAnswer();
    advanceClockBy(10_000);
    await act(async () => { pinSite.result.current(); });
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2));

    await act(async () => { bell.current.refresh({ freshForMs: BOUND_MS }); });

    expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2);
    await act(async () => { release(); });
  });

  it('A silenced visit does not extend the silence (the window does not slide)', async () => {
    // Stamping on the skipped path would make a user flipping onto Triggers just inside the
    // bound silence themselves for as long as they keep flipping.
    const { result } = renderBellPair();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1));

    advanceClockBy(3_000);
    const release = holdNextAnswer();
    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2));

    // 1s later: inside the bound, so silent - and it must leave the stamp where it was.
    advanceClockBy(1_000);
    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });
    expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(2);

    // 1.5s further: 2.5s since the REAL ask, so this goes out - but only 1.5s since the silenced
    // one, so it stays silent if the skipped path stamps.
    advanceClockBy(1_500);
    await act(async () => { result.current.refresh({ freshForMs: BOUND_MS }); });

    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(3));
    await act(async () => { release(); });
  });

  it('Keeps a stable identity across renders so a consumer visit effect does not re-fire on every render', async () => {
    const { result, rerender } = renderBellPair();
    await waitFor(() => expect(homeStatusService.getHomeStatus).toHaveBeenCalledTimes(1));

    const first = result.current.refresh;
    rerender();

    expect(result.current.refresh).toBe(first);
  });
});

// @vitest-environment jsdom
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mockGet = vi.hoisted(() => vi.fn());
const mockPost = vi.hoisted(() => vi.fn());
const mockAuth = vi.hoisted(() => vi.fn());
const mockEntry = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { get: mockGet, post: mockPost },
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useOptionalAuth: () => mockAuth(),
}));

vi.mock('@/lib/changelog/latestEntry', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/changelog/latestEntry')>()),
  currentEntry: () => mockEntry(),
}));

import { useChangelog, CHANGELOG_STATE_QUERY_KEY } from '../useChangelog';

const ENTRY = {
  key: '2026-09-whats-new', publishedAt: '2026-09-07', media: null, learnMoreUrl: '/changelog', copy: 'latest', action: null,
};
const ONBOARDED = { needsOnboarding: false, firstLogin: false, profileIncomplete: false, emailVerified: true };
const STATE_PATH = '/changelog/state';
const ONBOARDING_PATH = '/auth-service/api/onboarding/status';

/** Answers each endpoint the hook may call, so the two queries can be driven independently. */
function respond(overrides: { onboarding?: unknown; state?: unknown } = {}) {
  mockGet.mockImplementation((path: string) => {
    if (path === ONBOARDING_PATH) {
      return overrides.onboarding instanceof Error
        ? Promise.reject(overrides.onboarding)
        : Promise.resolve(overrides.onboarding ?? ONBOARDED);
    }
    if (path === STATE_PATH) {
      return overrides.state instanceof Error
        ? Promise.reject(overrides.state)
        : Promise.resolve(overrides.state ?? { enabled: true, seenKey: null, seal: false });
    }
    return Promise.reject(new Error(`unexpected call to ${path}`));
  });
}

const stateCalls = () => mockGet.mock.calls.filter(([path]) => path === STATE_PATH);
const onboardingCalls = () => mockGet.mock.calls.filter(([path]) => path === ONBOARDING_PATH);

function wrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe('useChangelog', () => {
  let client: QueryClient;

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    mockGet.mockReset();
    mockPost.mockReset().mockResolvedValue({ enabled: true });
    mockAuth.mockReset().mockReturnValue({ isLoading: false, isAuthenticated: true, user: { sub: 'user-1' } });
    mockEntry.mockReset().mockReturnValue(ENTRY);
    respond();
  });

  afterEach(() => client.clear());

  it('asks the server for the acknowledgement and announces when there is none', async () => {
    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.decision).toBe('announce'));
    // The key travels with the request: the server owns the seal decision, and receiving the key
    // is also what stamps this install's first sight of the entry.
    expect(stateCalls()[0]).toEqual([STATE_PATH, { params: { entry: '2026-09-whats-new' } }]);
    expect(result.current.isAvailable).toBe(true);
  });

  it('says NOTHING while the user still has onboarding to do', async () => {
    // A first run is a guided sequence, and a release note has no business interrupting it. The
    // state call is not even made: nothing to announce means nothing to ask.
    respond({ onboarding: { needsOnboarding: true } });

    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });

    await waitFor(() => expect(onboardingCalls().length).toBeGreaterThan(0));
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(stateCalls()).toHaveLength(0);
    expect(result.current.decision).toBe('hidden');
  });

  it('stays quiet for every shape of unfinished onboarding, not just the obvious flag', async () => {
    for (const status of [
      { firstLogin: true },
      { profileIncomplete: true },
      { emailVerified: false },
    ]) {
      mockGet.mockReset();
      respond({ onboarding: status });
      const local = new QueryClient({ defaultOptions: { queries: { retry: false } } });

      const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(local) });

      await waitFor(() => expect(result.current.isLoading).toBe(false));
      expect(stateCalls(), `announced despite ${JSON.stringify(status)}`).toHaveLength(0);
      local.clear();
    }
  });

  it('fails CLOSED when the onboarding status cannot be read', async () => {
    // Unlike FirstLoginGuard, which must not lock a user out of the app: here an unknown answer
    // means staying silent. A missed announcement costs nothing; one landing on an onboarding
    // step costs the first impression.
    respond({ onboarding: new Error('gateway down') });

    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(stateCalls()).toHaveLength(0);
    expect(result.current.decision).toBe('hidden');
  });

  it('reads the onboarding cache FirstLoginGuard already filled, instead of asking again', async () => {
    client.setQueryData(['user', 'onboarding-status', 'user-1'], ONBOARDED);

    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.decision).toBe('announce'));
    // Same key, same endpoint as the guard: the app must not pay for this twice on every load.
    expect(onboardingCalls()).toHaveLength(0);
  });

  it('makes NO request at all when this build ships no entry', async () => {
    mockEntry.mockReturnValue(null);

    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    // A build that announces nothing should cost nothing: neither call could change the outcome.
    expect(mockGet).not.toHaveBeenCalled();
    expect(result.current.decision).toBe('hidden');
  });

  it('makes no request before the user is authenticated', async () => {
    mockAuth.mockReturnValue({ isLoading: true, isAuthenticated: false, user: null });

    renderHook(() => useChangelog(), { wrapper: wrapper(client) });

    await waitFor(() => expect(mockGet).not.toHaveBeenCalled());
  });

  it('stays idle rather than throwing when there is no auth provider at all', async () => {
    mockAuth.mockReturnValue(undefined);

    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.decision).toBe('hidden'));
    expect(mockGet).not.toHaveBeenCalled();
  });

  it('acknowledges the entry and writes the cache immediately, before the request settles', async () => {
    let resolvePost: (value: unknown) => void = () => {};
    mockPost.mockImplementation(() => new Promise((resolve) => { resolvePost = resolve; }));

    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });
    await waitFor(() => expect(result.current.decision).toBe('announce'));

    result.current.markSeen();

    // Optimistic on purpose: the panel closes now, and the decision must already read 'hidden' so
    // a re-render (or another navigation) does not reopen it while the POST is in flight.
    await waitFor(() => expect(result.current.decision).toBe('hidden'));
    expect(mockPost).toHaveBeenCalledWith('/changelog/seen', { key: '2026-09-whats-new' });
    expect(client.getQueryData(CHANGELOG_STATE_QUERY_KEY)).toMatchObject({ seenKey: '2026-09-whats-new' });
    resolvePost({ enabled: true });
  });

  it('does not re-acknowledge an entry the user already acknowledged', async () => {
    respond({ state: { enabled: true, seenKey: '2026-09-whats-new', seal: false } });

    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });
    await waitFor(() => expect(result.current.decision).toBe('hidden'));

    result.current.markSeen();

    // The panel acknowledges on open, so a re-render must not write on every pass.
    expect(mockPost).not.toHaveBeenCalled();
  });

  it('keeps the acknowledgement locally when the request fails, instead of reopening the panel', async () => {
    mockPost.mockRejectedValue(new Error('gateway down'));

    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });
    await waitFor(() => expect(result.current.decision).toBe('announce'));

    result.current.markSeen();

    // The optimistic value is deliberately NOT rolled back: a lost acknowledgement would reopen
    // the panel on the next navigation of this same session, which is far more annoying than an
    // announcement missed once. A later session refetches and reconciles.
    await waitFor(() => expect(result.current.decision).toBe('hidden'));
    expect(mockPost).toHaveBeenCalledTimes(1);
  });

  it('reports the feature as unavailable when the deployment switched it off', async () => {
    respond({ state: { enabled: false, seenKey: null, seal: false } });

    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.isAvailable).toBe(false));
    expect(result.current.decision).toBe('hidden');
  });

  it('seals silently when the server says the account never lacked the change', async () => {
    respond({ state: { enabled: true, seenKey: null, seal: true } });

    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.decision).toBe('seal'));
  });

  it('says nothing when the state request fails, rather than announcing blindly', async () => {
    respond({ state: new Error('gateway down') });

    const { result } = renderHook(() => useChangelog(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.decision).toBe('hidden');
    expect(result.current.isAvailable).toBe(false);
  });
});

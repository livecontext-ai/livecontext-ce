// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { CLOCK_OFFSET_KEY, EmbeddedAuthProvider, LEASE_TTL_MS, REFRESH_LEASE_KEY, REFRESH_LOCK_NAME, RELOAD_CANCELLED_AFTER_MS, crossTabSessionSwitch, embeddedChangePassword, embeddedLogin, useEmbeddedAuth } from './embedded-auth-provider';

const ACCESS_TOKEN_KEY = 'ce_access_token';
const REFRESH_TOKEN_KEY = 'ce_refresh_token';
const TOKEN_EXPIRY_KEY = 'ce_token_expiry';
const USER_DATA_KEY = 'ce_user_data';

describe('embeddedChangePassword', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('posts to the change-password endpoint with the bearer token and body, then returns success', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'tok-123');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ success: true }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await embeddedChangePassword('oldpass', 'newpassword1');

    expect(result).toEqual({ success: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, opts] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/proxy/auth/change-password');
    expect(opts.method).toBe('POST');
    expect(opts.headers.Authorization).toBe('Bearer tok-123');
    expect(opts.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(opts.body)).toEqual({ currentPassword: 'oldpass', newPassword: 'newpassword1' });
  });

  it('fails without a status (generic error, not a 401) and skips the network when no token is stored', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const result = await embeddedChangePassword('whatever', 'newpassword1');

    expect(result.success).toBe(false);
    // A missing local token must NOT be mislabeled as "wrong current password" (401).
    expect(result.status).toBeUndefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces the backend status and message when the current password is wrong (401)', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'tok-123');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ message: 'Current password is incorrect' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await embeddedChangePassword('wrong', 'newpassword1');

    expect(result.success).toBe(false);
    expect(result.status).toBe(401);
    expect(result.error).toBe('Current password is incorrect');
  });

  it('surfaces a 400 (e.g. password too short) from the backend', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'tok-123');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({ message: 'New password must be at least 12 characters' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await embeddedChangePassword('oldpass', 'short');

    expect(result.success).toBe(false);
    expect(result.status).toBe(400);
    expect(result.error).toBe('New password must be at least 12 characters');
  });

  it('returns a failure (never throws) on a network error', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'tok-123');
    const fetchMock = vi.fn().mockRejectedValue(new Error('boom'));
    vi.stubGlobal('fetch', fetchMock);

    const result = await embeddedChangePassword('a', 'newpassword1');

    expect(result.success).toBe(false);
    expect(result.error).toBe('boom');
  });
});

// ---------------------------------------------------------------------------
// EmbeddedAuthProvider.signinSilent - silent token refresh
//
// signinSilent is the embedded-mode equivalent of OIDC's silent renew. It is
// invoked concurrently (proactive timer, tab-visibility handler, AND the
// api-client's 401 retry all call the same context method), so the provider
// dedups in-flight refreshes via refreshPromiseRef. These tests exercise that
// dedup ref and the failed-refresh teardown, neither of which had coverage.
// ---------------------------------------------------------------------------

const USER = { id: 1, email: 'u@example.io', firstName: 'U', lastName: 'X', roles: ['user'] };

/** A JWT-shaped access token carrying the claims the provider reads (userId, exp). */
function accessToken(claims: { userId: number; exp: number; n?: string }): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${b64({ alg: 'HS256' })}.${b64(claims)}.sig`;
}

const nowSeconds = () => Math.floor(Date.now() / 1000);

/** Seed a VALID (non-expired) stored session so the provider mounts with a user. */
function seedValidSession(token = 'at1') {
  const expiresAt = nowSeconds() + 3600;
  localStorage.setItem(ACCESS_TOKEN_KEY, token);
  localStorage.setItem(REFRESH_TOKEN_KEY, 'rt1');
  localStorage.setItem(TOKEN_EXPIRY_KEY, String(expiresAt));
  localStorage.setItem(USER_DATA_KEY, JSON.stringify(USER));
}

/**
 * A Web Locks manager that behaves like a browser's: the lock is granted asynchronously and one
 * holder at a time, the next request waiting for the previous holder's promise to settle. A mock
 * that runs the callback on the spot is what hid the defects this suite now pins.
 */
function installWebLocks() {
  let tail: Promise<unknown> = Promise.resolve();
  const request = vi.fn((_name: string, callback: () => Promise<unknown>) => {
    const granted = tail.then(() => callback());
    tail = granted.catch(() => undefined);
    return granted;
  });
  Object.defineProperty(navigator, 'locks', { value: { request }, configurable: true });
  return request;
}

function removeWebLocks() {
  delete (navigator as unknown as { locks?: unknown }).locks;
}

/** Lets pending promise callbacks run (lock grants, fetch continuations). */
async function flush() {
  await act(async () => {
    for (let i = 0; i < 10; i++) await Promise.resolve();
  });
}

function refreshResponse(token: string, refreshToken = 'rt2') {
  return {
    ok: true,
    json: async () => ({ accessToken: token, refreshToken, expiresIn: 3600, user: USER }),
  };
}

describe('EmbeddedAuthProvider signinSilent token refresh', () => {
  beforeEach(() => {
    localStorage.clear();
    // scheduleRefresh() arms a setTimeout on mount and after every refresh - fake
    // timers keep it from firing a stray refresh during these tests.
    vi.useFakeTimers();
    installWebLocks();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    removeWebLocks();
  });

  it('dedups two concurrent signinSilent calls into ONE /auth/refresh request and resolves both callers to the same user', async () => {
    seedValidSession();

    // Controlled refresh response: stays pending until we resolve it, so BOTH
    // signinSilent calls are genuinely in-flight at the same time.
    let resolveRefresh!: (resp: any) => void;
    const fetchMock = vi.fn().mockImplementation(
      () => new Promise((resolve) => { resolveRefresh = resolve; }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

    // Act: fire two refreshes while the first network call is still pending.
    const p1 = result.current.signinSilent();
    const p2 = result.current.signinSilent();
    await flush();

    // The dedup ref means only the FIRST call reached the network.
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/proxy/auth/refresh',
      expect.objectContaining({ method: 'POST' }),
    );

    let u1: any, u2: any;
    await act(async () => {
      resolveRefresh(refreshResponse('at2'));
      [u1, u2] = await Promise.all([p1, p2]);
    });

    // Still exactly one network refresh, and both callers share the SAME user
    // instance (they awaited the one deduped promise, not two separate refreshes).
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(u1).toBe(u2);
    expect(u1?.profile.sub).toBe('1');
    expect(u1?.access_token).toBe('at2');
  });

  it('clears the stored tokens and drops the user to null when the refresh is rejected by the server (res.ok false)', async () => {
    seedValidSession();
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 401, json: async () => ({}) });
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    // The valid stored session is loaded synchronously on mount.
    expect(result.current.user?.profile.sub).toBe('1');

    let refreshed: any = 'unset';
    await act(async () => {
      refreshed = await result.current.signinSilent();
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(refreshed).toBeNull();
    // A failed refresh wipes every token from storage and resets the context user.
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(TOKEN_EXPIRY_KEY)).toBeNull();
    expect(localStorage.getItem(USER_DATA_KEY)).toBeNull();
    expect(result.current.user).toBeNull();
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('clears the stored tokens and drops the user to null when the refresh request throws (network error)', async () => {
    seedValidSession();
    // Distinct from the res.ok=false path: here fetch itself rejects, exercising
    // the signinSilent catch{} teardown rather than the !res.ok branch.
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    expect(result.current.user?.profile.sub).toBe('1');

    let refreshed: any = 'unset';
    await act(async () => {
      // Must resolve to null, NOT throw out to the caller.
      refreshed = await result.current.signinSilent();
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(refreshed).toBeNull();
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(TOKEN_EXPIRY_KEY)).toBeNull();
    expect(localStorage.getItem(USER_DATA_KEY)).toBeNull();
    expect(result.current.user).toBeNull();
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('returns null without any network call when there is no stored refresh token', async () => {
    // No seedValidSession(): storage is empty, so signinSilent has nothing to refresh.
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

    let refreshed: any = 'unset';
    await act(async () => {
      refreshed = await result.current.signinSilent();
    });

    expect(refreshed).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('regression: a slow refresh is awaited to the end, never abandoned: the refresh token is presented once', async () => {
    // Abandoning it (a 15 s abort did) kept a refresh token the server may already have rotated;
    // presenting it again is read as token theft and revokes every session of the user.
    seedValidSession();
    let answer: (value: unknown) => void = () => {};
    const fetchMock = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      expect(init.signal).toBeUndefined();
      return new Promise((resolve) => { answer = resolve; });
    });
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

    let first: any = 'pending';
    const pending = result.current.signinSilent().then((u) => { first = u; });
    await flush();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });
    expect(first).toBe('pending');
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('rt1');

    await act(async () => {
      answer(refreshResponse('at2'));
      await pending;
    });
    expect(first?.access_token).toBe('at2');
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('rt2');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

// ---------------------------------------------------------------------------
// Regression review 2026-09-29: several CE tabs signed each other out.
//
// Each tab holds its access token in memory while all tabs share the refresh token in
// localStorage. An access token is bound to the refresh-token row it was minted with (sid), so a
// refresh in one tab withdraws the others' tokens; each of them then refreshed in turn, and two
// tabs refreshing at once presented the same spent refresh token, which the server reads as token
// theft and answers by revoking every session. Two providers rendered side by side share one
// jsdom localStorage: they are two tabs of one browser.
// ---------------------------------------------------------------------------

describe('EmbeddedAuthProvider across tabs', () => {
  const current = () => accessToken({ userId: 1, exp: nowSeconds() + 3600, n: 'a' });

  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    removeWebLocks();
  });

  /** What another tab leaves in localStorage after its own refresh. */
  function otherTabRefreshed(token: string, userData: object = USER) {
    localStorage.setItem(ACCESS_TOKEN_KEY, token);
    localStorage.setItem(REFRESH_TOKEN_KEY, 'rt-other-tab');
    localStorage.setItem(TOKEN_EXPIRY_KEY, String(nowSeconds() + 3600));
    localStorage.setItem(USER_DATA_KEY, JSON.stringify(userData));
  }

  it('two tabs that refresh at once present the refresh token ONCE: the second takes the first one\'s token', async () => {
    installWebLocks();
    seedValidSession(current());
    const fetchMock = vi.fn().mockResolvedValue(
      refreshResponse(accessToken({ userId: 1, exp: nowSeconds() + 7200, n: 'fresh' })));
    vi.stubGlobal('fetch', fetchMock);
    const tabA = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    const tabB = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

    let a: any, b: any;
    await act(async () => {
      [a, b] = await Promise.all([tabA.result.current.signinSilent(), tabB.result.current.signinSilent()]);
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(a?.access_token).toBe(b?.access_token);
    expect(tabB.result.current.user?.access_token).toBe(a?.access_token);
  });

  it('a tab that takes the new token WHILE it waits for the lock does not refresh it away once granted', async () => {
    // A lock the test grants by hand, so the other tab's token can arrive (and the storage
    // listener move this tab on) between the request and the grant, which a real browser allows.
    const queue: Array<() => void> = [];
    Object.defineProperty(navigator, 'locks', {
      value: {
        request: vi.fn((_n: string, callback: () => Promise<unknown>) =>
          new Promise((resolve, reject) => queue.push(() => { callback().then(resolve, reject); }))),
      },
      configurable: true,
    });
    const original = current();
    seedValidSession(original);
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

    let settled: any = 'pending';
    const pending = result.current.signinSilent().then((u) => { settled = u; });
    const fromOtherTab = accessToken({ userId: 1, exp: nowSeconds() + 7200, n: 'b' });
    otherTabRefreshed(fromOtherTab);
    await act(async () => {
      window.dispatchEvent(new StorageEvent('storage', { key: ACCESS_TOKEN_KEY, newValue: fromOtherTab }));
    });
    expect(result.current.user?.access_token).toBe(fromOtherTab);

    await act(async () => {
      queue.shift()?.();
      await pending;
    });

    expect(fetchMock).not.toHaveBeenCalled();
    expect(settled?.access_token).toBe(fromOtherTab);
  });

  it('a signed-out tab does not pick up a session from a storage event', async () => {
    installWebLocks();
    vi.stubGlobal('fetch', vi.fn());
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    const fromOtherTab = accessToken({ userId: 1, exp: nowSeconds() + 7200, n: 'b' });
    otherTabRefreshed(fromOtherTab);

    await act(async () => {
      window.dispatchEvent(new StorageEvent('storage', { key: ACCESS_TOKEN_KEY, newValue: fromOtherTab }));
    });

    expect(result.current.user).toBeNull();
  });

  it('without Web Locks (plain-HTTP install) the localStorage lease still lets only one tab refresh', async () => {
    removeWebLocks();
    seedValidSession(current());
    const fetchMock = vi.fn().mockResolvedValue(
      refreshResponse(accessToken({ userId: 1, exp: nowSeconds() + 7200, n: 'fresh' })));
    vi.stubGlobal('fetch', fetchMock);
    const tabA = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    const tabB = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

    let a: any, b: any;
    await act(async () => {
      const both = Promise.all([tabA.result.current.signinSilent(), tabB.result.current.signinSilent()]);
      await vi.advanceTimersByTimeAsync(2_000);
      [a, b] = await both;
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(a?.access_token).toBe(b?.access_token);
    expect(localStorage.getItem(REFRESH_LEASE_KEY)).toBeNull();
  });

  it('regression: after taking another tab\'s token, the next refresh of this tab still reaches the server', async () => {
    // A lock manager that runs the callback on the spot: the adoption then completes
    // synchronously, which used to leave a settled promise in the dedup ref for good, so every
    // later signinSilent() of the tab returned that stale adoption and never refreshed again.
    Object.defineProperty(navigator, 'locks', {
      value: { request: vi.fn((_n: string, callback: () => Promise<unknown>) => callback()) },
      configurable: true,
    });
    seedValidSession(current());
    const fetchMock = vi.fn().mockResolvedValue(refreshResponse(accessToken({ userId: 1, exp: nowSeconds() + 9000, n: 'z' })));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    const fromOtherTab = accessToken({ userId: 1, exp: nowSeconds() + 7200, n: 'b' });
    otherTabRefreshed(fromOtherTab);

    let adopted: any;
    await act(async () => {
      adopted = await result.current.signinSilent();
    });
    expect(adopted?.access_token).toBe(fromOtherTab);
    expect(fetchMock).not.toHaveBeenCalled();

    let refreshed: any;
    await act(async () => {
      refreshed = await result.current.signinSilent();
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(refreshed?.access_token).not.toBe(fromOtherTab);
  });

  it('takes the other tab\'s token as soon as it is stored, with the expiry from the token itself', async () => {
    installWebLocks();
    seedValidSession(current());
    vi.stubGlobal('fetch', vi.fn());
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    const exp = nowSeconds() + 5000;
    const fromOtherTab = accessToken({ userId: 1, exp, n: 'b' });

    // Only the access token has landed yet: the other keys still hold the previous values.
    localStorage.setItem(ACCESS_TOKEN_KEY, fromOtherTab);
    await act(async () => {
      window.dispatchEvent(new StorageEvent('storage', { key: ACCESS_TOKEN_KEY, newValue: fromOtherTab }));
    });

    expect(result.current.user?.access_token).toBe(fromOtherTab);
    expect(result.current.user?.expires_at).toBe(exp);
  });

  it('another user signed in in another tab: this tab reloads instead of silently running as them', async () => {
    installWebLocks();
    seedValidSession(current());
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const reload = vi.spyOn(crossTabSessionSwitch, 'reload').mockImplementation(() => undefined);
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    const bob = accessToken({ userId: 2, exp: nowSeconds() + 3600, n: 'bob' });
    otherTabRefreshed(bob, { ...USER, id: 2, email: 'bob@example.io' });

    await act(async () => {
      window.dispatchEvent(new StorageEvent('storage', { key: ACCESS_TOKEN_KEY, newValue: bob }));
    });
    let refreshed: any = 'unset';
    result.current.signinSilent().then((u) => { refreshed = u; });
    await flush();

    expect(reload).toHaveBeenCalledTimes(2);
    expect(result.current.user?.profile.sub).toBe('1');
    expect(result.current.user?.access_token).not.toBe(bob);
    // Never answered: a null would send the api client to the login page, racing the reload.
    expect(refreshed).toBe('unset');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('a refresh that comes back with ANOTHER user keeps their rotated session stored and reloads this tab', async () => {
    installWebLocks();
    seedValidSession(current());
    // The other user's token is unreadable, so it was not recognised before refreshing.
    localStorage.setItem(ACCESS_TOKEN_KEY, 'opaque-token-of-bob');
    const bobData = { ...USER, id: 2, email: 'bob@example.io' };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ accessToken: 'bob-at2', refreshToken: 'bob-rt2', expiresIn: 3600, user: bobData }),
    }));
    const reload = vi.spyOn(crossTabSessionSwitch, 'reload').mockImplementation(() => undefined);
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

    let refreshed: any = 'unset';
    result.current.signinSilent().then((u) => { refreshed = u; });
    await flush();

    expect(reload).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('bob-rt2');
    expect(result.current.user?.profile.sub).toBe('1');
    expect(refreshed).toBe('unset');
  });

  it('a token another tab left with a minute or less is not taken: this tab refreshes', async () => {
    installWebLocks();
    seedValidSession(current());
    const fetchMock = vi.fn().mockResolvedValue(refreshResponse(accessToken({ userId: 1, exp: nowSeconds() + 3600, n: 'r' })));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    otherTabRefreshed(accessToken({ userId: 1, exp: nowSeconds() + 60, n: 'dying' }));

    await act(async () => {
      await result.current.signinSilent();
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('a refused lock request (document not fully active) falls back to the lease instead of failing the refresh', async () => {
    Object.defineProperty(navigator, 'locks', {
      value: { request: vi.fn(() => Promise.reject(new DOMException('not active', 'InvalidStateError'))) },
      configurable: true,
    });
    seedValidSession(current());
    const fetchMock = vi.fn().mockResolvedValue(refreshResponse(accessToken({ userId: 1, exp: nowSeconds() + 7200, n: 'r' })));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

    let refreshed: any;
    await act(async () => {
      const pending = result.current.signinSilent();
      await vi.advanceTimersByTimeAsync(1_000);
      refreshed = await pending;
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(refreshed?.access_token).toBeTruthy();
  });

  it('a token valid for longer than setTimeout can wait is not refreshed early (the timer re-arms)', async () => {
    installWebLocks();
    const fortyDays = 40 * 24 * 3600;
    localStorage.setItem(ACCESS_TOKEN_KEY, accessToken({ userId: 1, exp: nowSeconds() + fortyDays }));
    localStorage.setItem(REFRESH_TOKEN_KEY, 'rt1');
    localStorage.setItem(TOKEN_EXPIRY_KEY, String(nowSeconds() + fortyDays));
    localStorage.setItem(USER_DATA_KEY, JSON.stringify(USER));
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    // A real setTimeout fires a delay above 2^31 - 1 ms at once, so the contract is pinned where it
    // lives: no delay above that is ever asked for.
    const timers = vi.spyOn(globalThis, 'setTimeout');
    renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_147_483_647 + 1_000);
    });

    const delays = timers.mock.calls.map((call) => Number(call[1] ?? 0));
    expect(Math.max(...delays)).toBeLessThanOrEqual(2_147_483_647);
    // ... and the early wake-up re-arms rather than refreshing a token with days left.
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('regression: switching to another user releases the cross-tab lock first, so a tab that stays open blocks no other tab', async () => {
    const request = installWebLocks();
    seedValidSession(current());
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const reload = vi.spyOn(crossTabSessionSwitch, 'reload').mockImplementation(() => undefined);
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    // No storage event reached this tab: the refresh itself finds the other user's session.
    otherTabRefreshed(accessToken({ userId: 2, exp: nowSeconds() + 3600, n: 'bob' }), { ...USER, id: 2 });

    let refreshed: any = 'unset';
    result.current.signinSilent().then((u) => { refreshed = u; });
    await flush();
    // The reload can be cancelled (unsaved changes ask first): another tab must still get the lock.
    let otherTabGranted = false;
    request(REFRESH_LOCK_NAME, async () => { otherTabGranted = true; });
    await flush();

    expect(reload).toHaveBeenCalledTimes(1);
    expect(otherTabGranted).toBe(true);
    expect(refreshed).toBe('unset');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('regression: a reload the page cancelled does not hang the tab: its waiting requests get null and the next refresh runs', async () => {
    installWebLocks();
    seedValidSession(current());
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    vi.spyOn(crossTabSessionSwitch, 'reload').mockImplementation(() => undefined);
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    otherTabRefreshed(accessToken({ userId: 2, exp: nowSeconds() + 3600, n: 'bob' }), { ...USER, id: 2 });

    let refreshed: any = 'unset';
    result.current.signinSilent().then((u) => { refreshed = u; });
    await flush();
    expect(refreshed).toBe('unset');

    // The page is still here after the reload window: the user chose to stay.
    await act(async () => { await vi.advanceTimersByTimeAsync(RELOAD_CANCELLED_AFTER_MS + 10); });
    expect(refreshed).toBeNull();
    // And the tab is not stuck on that promise: a new call starts a new attempt.
    let again: any = 'unset';
    result.current.signinSilent().then((u) => { again = u; });
    await flush();
    await act(async () => { await vi.advanceTimersByTimeAsync(RELOAD_CANCELLED_AFTER_MS + 10); });
    expect(again).toBeNull();
    expect(crossTabSessionSwitch.reload).toHaveBeenCalledTimes(2);
  });

  it("regression: sign-out waits for another tab's refresh in flight, so it revokes the token that refresh produced", async () => {
    const request = installWebLocks();
    seedValidSession(current());
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    Object.defineProperty(window, 'location', { value: { href: '', pathname: '/app' }, writable: true, configurable: true });
    // Another tab holds the lock: its refresh is still out.
    let finishOtherRefresh!: () => void;
    request(REFRESH_LOCK_NAME, () => new Promise<void>((resolve) => { finishOtherRefresh = resolve; }));
    await flush();

    let signedOut = false;
    result.current.signoutRedirect().then(() => { signedOut = true; });
    await flush();
    expect(fetchMock).not.toHaveBeenCalled();

    // The other tab stored its rotated token, then released the lock.
    localStorage.setItem(REFRESH_TOKEN_KEY, 'rt-rotated');
    finishOtherRefresh();
    await flush();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).refreshToken).toBe('rt-rotated');
    expect(signedOut).toBe(true);
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  });

  it('regression: a sign-in waits for another tab refresh in flight, so that refresh cannot store the previous user over it', async () => {
    const request = installWebLocks();
    let finishOtherRefresh!: () => void;
    request(REFRESH_LOCK_NAME, () => new Promise<void>((resolve) => { finishOtherRefresh = resolve; }));
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ accessToken: 'y-at', refreshToken: 'y-rt', expiresIn: 900, user: { ...USER, id: 2 } }),
    }));

    let done = false;
    embeddedLogin('y@example.io', 'pw').then(() => { done = true; });
    await flush();
    expect(done).toBe(false);
    // The other tab finishes its refresh of the previous user, then releases the lock.
    localStorage.setItem(REFRESH_TOKEN_KEY, 'x-rotated');
    finishOtherRefresh();
    await flush();

    expect(done).toBe(true);
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('y-rt');
  });
  it("regression: a browser clock 20 minutes ahead still takes another tab's token, on the offset its own refresh measured", async () => {
    installWebLocks();
    seedValidSession(current());
    // The server's clock is 20 minutes behind this browser's: a 15-minute token it issues now
    // already reads as expired on this clock.
    const serverNow = nowSeconds() - 1200;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ accessToken: accessToken({ userId: 1, exp: serverNow + 900, n: 'own' }),
        refreshToken: 'rt2', expiresIn: 900, user: USER }),
    }));
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });
    await act(async () => { await result.current.signinSilent(); });
    expect(localStorage.getItem(CLOCK_OFFSET_KEY)).toBe('1200');

    const fromOtherTab = accessToken({ userId: 1, exp: serverNow + 900, n: 'other' });
    localStorage.setItem(ACCESS_TOKEN_KEY, fromOtherTab);
    await act(async () => {
      window.dispatchEvent(new StorageEvent('storage', { key: ACCESS_TOKEN_KEY, newValue: fromOtherTab }));
    });

    expect(result.current.user?.access_token).toBe(fromOtherTab);
    // Read on this browser's clock: 15 minutes from now, not 5 minutes ago.
    expect(result.current.user?.expires_at).toBe(nowSeconds() + 900);
  });

  it('a storage event carrying a token about to expire, or an unreadable one, is not taken', async () => {
    installWebLocks();
    const own = current();
    seedValidSession(own);
    vi.stubGlobal('fetch', vi.fn());
    const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

    for (const stale of [accessToken({ userId: 1, exp: nowSeconds() + 30, n: 'x' }), 'not-a-jwt']) {
      localStorage.setItem(ACCESS_TOKEN_KEY, stale);
      await act(async () => {
        window.dispatchEvent(new StorageEvent('storage', { key: ACCESS_TOKEN_KEY, newValue: stale }));
      });
      expect(result.current.user?.access_token).toBe(own);
    }
  });

  describe('the localStorage lease (no Web Locks: plain-HTTP installs)', () => {
    it('regression: a page that goes away mid-refresh releases its lease, so the next page does not wait for it', async () => {
      removeWebLocks();
      seedValidSession(current());
      vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})));
      const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

      await act(async () => {
        result.current.signinSilent();
        await vi.advanceTimersByTimeAsync(200);
      });
      expect(localStorage.getItem(REFRESH_LEASE_KEY)).not.toBeNull();

      await act(async () => { window.dispatchEvent(new Event('pagehide')); });

      expect(localStorage.getItem(REFRESH_LEASE_KEY)).toBeNull();
    });

    it('storage refusing the lease claim (private mode) runs the refresh without the lease rather than failing it', async () => {
      removeWebLocks();
      seedValidSession(current());
      const setItem = Storage.prototype.setItem;
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
        if (key === REFRESH_LEASE_KEY) throw new DOMException('quota', 'QuotaExceededError');
        return setItem.call(this, key, value);
      });
      const fetchMock = vi.fn().mockResolvedValue(refreshResponse(accessToken({ userId: 1, exp: nowSeconds() + 7200, n: 'p' })));
      vi.stubGlobal('fetch', fetchMock);
      const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

      let refreshed: any;
      await act(async () => { refreshed = await result.current.signinSilent(); });

      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(refreshed?.access_token).toBeTruthy();
    });

    it('storage refusing a lease renewal does not break a slow refresh: the lease just runs out on its own', async () => {
      removeWebLocks();
      seedValidSession(current());
      let claims = 0;
      const setItem = Storage.prototype.setItem;
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
        if (key === REFRESH_LEASE_KEY && ++claims > 1) throw new DOMException('quota', 'QuotaExceededError');
        return setItem.call(this, key, value);
      });
      let answer!: (value: unknown) => void;
      vi.stubGlobal('fetch', vi.fn(() => new Promise((resolve) => { answer = resolve; })));
      const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

      let refreshed: any = 'pending';
      await act(async () => {
        result.current.signinSilent().then((u) => { refreshed = u; });
        await vi.advanceTimersByTimeAsync(35_000);
      });
      expect(refreshed).toBe('pending');
      await act(async () => {
        answer(refreshResponse(accessToken({ userId: 1, exp: nowSeconds() + 7200, n: 's' })));
        await vi.advanceTimersByTimeAsync(10);
      });

      expect(refreshed?.access_token).toBeTruthy();
    });

    const lease = () => JSON.parse(localStorage.getItem(REFRESH_LEASE_KEY) || 'null');

    it('a lease left by a tab that died (expired) is taken over', async () => {
      removeWebLocks();
      seedValidSession(current());
      localStorage.setItem(REFRESH_LEASE_KEY, JSON.stringify({ owner: 'dead-tab', until: Date.now() - 1 }));
      const fetchMock = vi.fn().mockResolvedValue(refreshResponse(accessToken({ userId: 1, exp: nowSeconds() + 7200, n: 'f' })));
      vi.stubGlobal('fetch', fetchMock);
      const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

      await act(async () => {
        const done = result.current.signinSilent();
        await vi.advanceTimersByTimeAsync(1_000);
        await done;
      });

      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(localStorage.getItem(REFRESH_LEASE_KEY)).toBeNull();
    });

    it('regression: a live lease held by another tab is waited for, however long, and never run beside', async () => {
      removeWebLocks();
      seedValidSession(current());
      localStorage.setItem(REFRESH_LEASE_KEY, JSON.stringify({ owner: 'other-tab', until: Date.now() + LEASE_TTL_MS }));
      const fetchMock = vi.fn().mockResolvedValue(refreshResponse(accessToken({ userId: 1, exp: nowSeconds() + 7200, n: 'f' })));
      vi.stubGlobal('fetch', fetchMock);
      const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

      const done = result.current.signinSilent();
      // The other tab keeps renewing its lease while its refresh runs: 60 s and still no fetch here.
      for (let i = 0; i < 6; i++) {
        localStorage.setItem(REFRESH_LEASE_KEY, JSON.stringify({ owner: 'other-tab', until: Date.now() + LEASE_TTL_MS }));
        await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
      }
      expect(fetchMock).not.toHaveBeenCalled();

      localStorage.removeItem(REFRESH_LEASE_KEY);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1_000);
        await done;
      });
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('a claim overwritten by another tab during the confirm step is not acted on', async () => {
      removeWebLocks();
      seedValidSession(current());
      const fetchMock = vi.fn().mockResolvedValue(refreshResponse('at2'));
      vi.stubGlobal('fetch', fetchMock);
      const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

      result.current.signinSilent();
      await flush();
      expect(lease()?.owner).toBeTruthy();
      // Another tab's write lands inside this tab's confirm window: that tab won.
      localStorage.setItem(REFRESH_LEASE_KEY, JSON.stringify({ owner: 'other-tab', until: Date.now() + LEASE_TTL_MS }));
      await act(async () => { await vi.advanceTimersByTimeAsync(2_000); });

      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('the holder renews its lease through a slow refresh, and never removes a lease another tab took', async () => {
      removeWebLocks();
      seedValidSession(current());
      let answer: (value: unknown) => void = () => {};
      vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise((resolve) => { answer = resolve; })));
      const { result } = renderHook(() => useEmbeddedAuth(), { wrapper: EmbeddedAuthProvider });

      const done = result.current.signinSilent();
      await act(async () => { await vi.advanceTimersByTimeAsync(1_000); });
      const mine = lease();
      await act(async () => { await vi.advanceTimersByTimeAsync(LEASE_TTL_MS + 30_000); });
      // Past its first expiry and still ours: renewed, so no other tab could have taken it.
      expect(lease()?.owner).toBe(mine.owner);
      expect(lease()?.until).toBeGreaterThan(Date.now());

      localStorage.setItem(REFRESH_LEASE_KEY, JSON.stringify({ owner: 'other-tab', until: Date.now() + LEASE_TTL_MS }));
      await act(async () => {
        answer(refreshResponse(accessToken({ userId: 1, exp: nowSeconds() + 7200, n: 'f' })));
        await done;
        await vi.advanceTimersByTimeAsync(20_000);
      });
      expect(lease()?.owner).toBe('other-tab');
    });
  });
});

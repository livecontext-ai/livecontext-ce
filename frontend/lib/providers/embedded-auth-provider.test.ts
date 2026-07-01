// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { EmbeddedAuthProvider, embeddedChangePassword, useEmbeddedAuth } from './embedded-auth-provider';

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
      json: async () => ({ message: 'New password must be at least 8 characters' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await embeddedChangePassword('oldpass', 'short');

    expect(result.success).toBe(false);
    expect(result.status).toBe(400);
    expect(result.error).toBe('New password must be at least 8 characters');
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

/** Seed a VALID (non-expired) stored session so the provider mounts with a user. */
function seedValidSession() {
  const expiresAt = Math.floor(Date.now() / 1000) + 3600;
  localStorage.setItem(ACCESS_TOKEN_KEY, 'at1');
  localStorage.setItem(REFRESH_TOKEN_KEY, 'rt1');
  localStorage.setItem(TOKEN_EXPIRY_KEY, String(expiresAt));
  localStorage.setItem(
    USER_DATA_KEY,
    JSON.stringify({ id: 1, email: 'u@example.io', firstName: 'U', lastName: 'X', roles: ['user'] }),
  );
}

describe('EmbeddedAuthProvider signinSilent token refresh', () => {
  beforeEach(() => {
    localStorage.clear();
    // scheduleRefresh() arms a setTimeout on mount and after every refresh - fake
    // timers keep it from firing a stray refresh during these tests.
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
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

    // The dedup ref means only the FIRST call reached the network.
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/proxy/auth/refresh',
      expect.objectContaining({ method: 'POST' }),
    );

    let u1: any, u2: any;
    await act(async () => {
      resolveRefresh({
        ok: true,
        json: async () => ({
          accessToken: 'at2',
          refreshToken: 'rt2',
          expiresIn: 3600,
          user: { id: 7, email: 'u@example.io', firstName: 'U', lastName: 'X', roles: ['user'] },
        }),
      });
      [u1, u2] = await Promise.all([p1, p2]);
    });

    // Still exactly one network refresh, and both callers share the SAME user
    // instance (they awaited the one deduped promise, not two separate refreshes).
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(u1).toBe(u2);
    expect(u1?.profile.sub).toBe('7');
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
});

/**
 * @vitest-environment jsdom
 *
 * Regression (CASA round 8): the round-7 refresh retry never ran. getAccessToken refreshed through
 * react-oidc-context's useAuth().signinSilent, which catches EVERY failure and resolves null, so
 * refreshWithTransientRetry never saw a rejection: a refused refresh token (invalid_grant) became
 * an empty token (apiClient NO_TOKEN) instead of the login page, and a 10 s request timeout was
 * never retried. The cloud refresh now calls the oidc-client-ts UserManager itself
 * (refreshCloudSession), which rejects with the real error.
 *
 * Driven end to end through the REAL UserManager, the real cross-tab lock and the real
 * refreshWithTransientRetry, exactly as getAccessToken composes them; only the Keycloak token
 * endpoint (fetch) is stubbed.
 */
import React from 'react';
import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from 'react-oidc-context';
import { InMemoryWebStorage, User, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { oidcConfig } from '../../../app/providers';
import { refreshWithTransientRetry, REFRESH_RETRY_BACKOFF_MS } from '../../auth/refreshRetry';
import { OIDC_REQUEST_TIMEOUT_SECONDS } from '../../auth/crossTabLock';
import { refreshCloudSession } from '../smart-providers';

const ISSUER = 'https://kc.example.test/realms/livecontext';
const TOKEN_ENDPOINT = `${ISSUER}/protocol/openid-connect/token`;

type TokenAnswer = 'hang' | 'network-down' | { status: number; body: Record<string, unknown> };

const fresh: TokenAnswer = {
  status: 200,
  body: { access_token: 'fresh-access', refresh_token: 'refresh-2', token_type: 'Bearer', expires_in: 900 },
};
const invalidGrant: TokenAnswer = {
  status: 400,
  body: { error: 'invalid_grant', error_description: 'Token is not active' },
};

/** The token endpoint answers each call with the next scripted answer (the last one repeats). */
function tokenEndpoint(...answers: TokenAnswer[]) {
  let call = 0;
  return vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    expect(String(input)).toBe(TOKEN_ENDPOINT);
    const answer = answers[Math.min(call, answers.length - 1)];
    call += 1;
    if (answer === 'hang') {
      return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      });
    }
    if (answer === 'network-down') return Promise.reject(new TypeError('Failed to fetch'));
    return Promise.resolve(new Response(JSON.stringify(answer.body), {
      status: answer.status,
      headers: { 'Content-Type': 'application/json' },
    }));
  });
}

/** A UserManager built from the app's settings, holding a user whose access token is expired or not. */
async function managerWithUser(expired: boolean): Promise<UserManager> {
  const manager = new UserManager({
    ...oidcConfig,
    authority: ISSUER,
    client_id: 'livecontext-frontend',
    redirect_uri: 'https://app.example.test/app/',
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    metadata: {
      issuer: ISSUER,
      authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
      token_endpoint: TOKEN_ENDPOINT,
    },
  });
  const now = Math.floor(Date.now() / 1000);
  await manager.storeUser(new User({
    access_token: 'old-access',
    refresh_token: 'refresh-1',
    token_type: 'Bearer',
    scope: 'openid profile email',
    profile: { sub: 'user-1', iss: ISSUER, aud: 'livecontext-frontend', exp: 0, iat: 0 },
    expires_at: expired ? now - 60 : now + 300,
  }));
  return manager;
}

/** getAccessToken's composition: the retry around the provider's cloud refresh. */
async function refreshLikeGetAccessToken(manager: UserManager) {
  const user = await manager.getUser();
  return refreshWithTransientRetry({
    refresh: () => refreshCloudSession(manager),
    current: () => (user?.access_token ? { token: user.access_token, expired: user.expired === true } : null),
    forceRefresh: false,
  });
}

describe('cloud token refresh through the real UserManager', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    localStorage.clear();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('a /token timeout is retried once, and the retry\'s new tokens replace the user', async () => {
    const fetchMock = tokenEndpoint('hang', fresh);
    vi.stubGlobal('fetch', fetchMock);
    const manager = await managerWithUser(true);

    const decision = refreshLikeGetAccessToken(manager);
    await vi.advanceTimersByTimeAsync(OIDC_REQUEST_TIMEOUT_SECONDS * 1000 + REFRESH_RETRY_BACKOFF_MS + 1_000);

    await expect(decision).resolves.toEqual({ kind: 'token', token: 'fresh-access' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const stored = await manager.getUser();
    expect(stored?.access_token).toBe('fresh-access');
    expect(stored?.refresh_token).toBe('refresh-2');
  });

  it('invalid_grant sends the user to the login page at once, without a retry', async () => {
    const fetchMock = tokenEndpoint(invalidGrant);
    vi.stubGlobal('fetch', fetchMock);
    const manager = await managerWithUser(false);

    const decision = refreshLikeGetAccessToken(manager);
    await vi.advanceTimersByTimeAsync(5_000);

    // Pre-fix this was { kind: 'token', token: '' }: the wrapper resolved null and the app ran on.
    await expect(decision).resolves.toMatchObject({ kind: 'redirect' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('network down with an expired token: one retry, then the login page', async () => {
    const fetchMock = tokenEndpoint('network-down');
    vi.stubGlobal('fetch', fetchMock);
    const manager = await managerWithUser(true);

    const decision = refreshLikeGetAccessToken(manager);
    await vi.advanceTimersByTimeAsync(5_000);

    await expect(decision).resolves.toMatchObject({ kind: 'redirect' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('network down while the token is still valid: one retry, then the token in hand is kept', async () => {
    const fetchMock = tokenEndpoint('network-down');
    vi.stubGlobal('fetch', fetchMock);
    const manager = await managerWithUser(false);

    const decision = refreshLikeGetAccessToken(manager);
    await vi.advanceTimersByTimeAsync(5_000);

    await expect(decision).resolves.toEqual({ kind: 'token', token: 'old-access' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('a timeout, then a refusal on the retry: the login page', async () => {
    const fetchMock = tokenEndpoint('hang', invalidGrant);
    vi.stubGlobal('fetch', fetchMock);
    const manager = await managerWithUser(false);

    const decision = refreshLikeGetAccessToken(manager);
    await vi.advanceTimersByTimeAsync(OIDC_REQUEST_TIMEOUT_SECONDS * 1000 + REFRESH_RETRY_BACKOFF_MS + 1_000);

    await expect(decision).resolves.toMatchObject({ kind: 'redirect' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('after an apiClient 401 (forceRefresh) the refused token is never handed back, nor is the user signed out by a dead network', async () => {
    vi.stubGlobal('fetch', tokenEndpoint('network-down'));
    const manager = await managerWithUser(false);

    const decision = refreshWithTransientRetry({
      refresh: () => refreshCloudSession(manager),
      current: () => ({ token: 'old-access', expired: false }),
      forceRefresh: true,
    });
    await vi.advanceTimersByTimeAsync(5_000);

    await expect(decision).resolves.toEqual({ kind: 'token', token: '' });
  });

  it('a missing UserManager fails the refresh instead of answering with no token', async () => {
    const decision = refreshWithTransientRetry({
      refresh: () => refreshCloudSession(null),
      current: () => ({ token: 'old-access', expired: true }),
      forceRefresh: false,
    });
    await vi.advanceTimersByTimeAsync(5_000);

    await expect(decision).resolves.toMatchObject({ kind: 'redirect' });
  });
});

describe('the auth context around the same UserManager', () => {
  let seen: ReturnType<typeof useAuth> | undefined;
  function Probe() {
    seen = useAuth();
    return null;
  }

  beforeEach(() => {
    seen = undefined;
    localStorage.clear();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  async function renderAround(manager: UserManager) {
    await act(async () => {
      render(<AuthProvider userManager={manager}><Probe /></AuthProvider>);
    });
    await vi.waitFor(() => expect(seen?.isLoading).toBe(false));
  }

  it('a refresh made on the manager updates the user react-oidc-context exposes (userLoaded)', async () => {
    vi.stubGlobal('fetch', tokenEndpoint(fresh));
    const manager = await managerWithUser(true);
    await renderAround(manager);
    expect(seen?.user?.access_token).toBe('old-access');
    expect(seen?.isAuthenticated).toBe(false); // expired

    await act(async () => {
      await refreshCloudSession(manager);
    });

    expect(seen?.user?.access_token).toBe('fresh-access');
    expect(seen?.isAuthenticated).toBe(true);
  });

  it('why the refresh bypasses useAuth().signinSilent: it resolves null on invalid_grant', async () => {
    vi.stubGlobal('fetch', tokenEndpoint(invalidGrant));
    const manager = await managerWithUser(false);
    await renderAround(manager);

    let viaContext: unknown = 'unset';
    await act(async () => {
      viaContext = await seen!.signinSilent();
    });
    // The wrapper swallowed the refusal: nothing to retry on, nothing to sign out on.
    expect(viaContext).toBeNull();

    await expect(manager.signinSilent()).rejects.toMatchObject({ name: 'ErrorResponse', error: 'invalid_grant' });
  });
});

/**
 * @vitest-environment jsdom
 *
 * Regression (CASA round 5): oidc-client-ts 3.x sets no request timeout by default, and the cloud
 * refresh runs under the cross-tab lock, so a /token call that never answered held the lock (and
 * every other tab) until Cloudflare's 524 about 100 s later. These tests drive the REAL
 * oidc-client-ts UserManager with the app's oidcConfig over a fetch that never answers:
 *   - the refresh call as refreshSession makes it (with silentRequestTimeoutInSeconds) fails with
 *     a timeout after OIDC_REQUEST_TIMEOUT_SECONDS;
 *   - a bare signinSilent() does NOT time out even with the setting configured (the library
 *     overrides it with `undefined` for the refresh-token request), which is why refreshSession
 *     passes the argument (pinned in lib/providers/__tests__/refreshLock.callSites.test.ts).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InMemoryWebStorage, User, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { oidcConfig } from '../providers';
import { OIDC_REFRESH_MAX_HOLD_MS, OIDC_REQUEST_TIMEOUT_SECONDS } from '../../lib/auth/crossTabLock';

const ISSUER = 'https://kc.example.test/realms/livecontext';

/** A UserManager built from the app's settings, holding a user whose access token expired. */
async function managerWithExpiredUser(): Promise<UserManager> {
  const manager = new UserManager({
    ...oidcConfig,
    authority: ISSUER,
    client_id: 'livecontext-frontend',
    redirect_uri: 'https://app.example.test/app/',
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    metadata: {
      issuer: ISSUER,
      authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
      token_endpoint: `${ISSUER}/protocol/openid-connect/token`,
    },
  });
  await manager.storeUser(new User({
    access_token: 'expired-access',
    refresh_token: 'refresh-1',
    token_type: 'Bearer',
    scope: 'openid profile email',
    profile: { sub: 'user-1', iss: ISSUER, aud: 'livecontext-frontend', exp: 0, iat: 0 },
    expires_at: Math.floor(Date.now() / 1000) - 60,
  }));
  return manager;
}

/** fetch that never answers; it only rejects (AbortError) when its request is aborted. */
function hungFetch() {
  return vi.fn((_input: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
  }));
}

describe('cloud OIDC refresh request timeout', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('configures a request timeout shorter than the lock hold bound', () => {
    expect(oidcConfig.requestTimeoutInSeconds).toBe(OIDC_REQUEST_TIMEOUT_SECONDS);
    expect(oidcConfig.silentRequestTimeoutInSeconds).toBe(OIDC_REQUEST_TIMEOUT_SECONDS);
    // Discovery then /token, each given the request timeout, fit inside the lock hold bound.
    expect(OIDC_REFRESH_MAX_HOLD_MS).toBeGreaterThan(2 * OIDC_REQUEST_TIMEOUT_SECONDS * 1000);
  });

  it('a refresh made like refreshSession fails with a timeout when /token never answers', async () => {
    const fetchMock = hungFetch();
    vi.stubGlobal('fetch', fetchMock);
    const manager = await managerWithExpiredUser();

    const refresh = manager.signinSilent({ silentRequestTimeoutInSeconds: OIDC_REQUEST_TIMEOUT_SECONDS });
    const outcome = vi.fn();
    refresh.catch(outcome);

    await vi.advanceTimersByTimeAsync(OIDC_REQUEST_TIMEOUT_SECONDS * 1000 - 1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toContain('/protocol/openid-connect/token');
    expect(outcome).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(2);
    expect(outcome).toHaveBeenCalledTimes(1);
    expect((outcome.mock.calls[0][0] as Error).message).toMatch(/timed out/i);
  });

  it('a bare signinSilent() is not bounded by the configured timeout (why refreshSession passes it)', async () => {
    vi.stubGlobal('fetch', hungFetch());
    const manager = await managerWithExpiredUser();

    const outcome = vi.fn();
    manager.signinSilent().then(outcome, outcome);

    await vi.advanceTimersByTimeAsync(10 * OIDC_REQUEST_TIMEOUT_SECONDS * 1000);
    expect(outcome).not.toHaveBeenCalled();
  });
});

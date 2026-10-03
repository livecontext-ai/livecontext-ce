/**
 * @vitest-environment jsdom
 *
 * Regression (CASA round 9): a browser reopened after the 15-minute access token died was sent to
 * the Keycloak password form even though its refresh token could still recover the session.
 *
 * Round 8 made the cloud refresh call the oidc-client-ts UserManager directly. react-oidc-context's
 * own signinSilent used to flip its `isLoading` for the duration, which kept the full-screen
 * spinner up and the app unmounted while the page-load recovery ran; the UserManager does not. So
 * with an expired stored user the provider reported "not loading, not authenticated": the
 * FirstLoginGuard redirected /app/* to /login, and the login page started the Keycloak redirect,
 * while the refresh that would have kept the person signed in was still in flight.
 *
 * Rendered through the REAL provider stack: react-oidc-context's AuthProvider on a real UserManager
 * (as app/providers.tsx wires it), AppDataProvider, the real FirstLoginGuard and the real cloud
 * login page. Only the Keycloak token endpoint (fetch), the Next.js router, the backend API client
 * and two app-shell widgets are stubbed; the redirect to Keycloak is a spy that never settles, as a
 * real one does while the page unloads.
 */
import React, { useEffect } from 'react';
import { act, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from 'react-oidc-context';
import { User, UserManager, WebStorageStateStore } from 'oidc-client-ts';

const router = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn(), back: vi.fn() }));
const nav = vi.hoisted(() => ({ pathname: '/en/app/chat', search: '' }));
const api = vi.hoisted(() => ({
  tokenProvider: null as null | ((options?: { forceRefresh?: boolean }) => Promise<string | null>),
}));

vi.mock('next/navigation', () => ({
  usePathname: () => nav.pathname,
  useRouter: () => router,
  useSearchParams: () => new URLSearchParams(nav.search),
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/lib/api/api-client', () => {
  const apiClient = {
    get: vi.fn(() => Promise.resolve(null)),
    post: vi.fn(() => Promise.resolve(null)),
    setTokenProvider: vi.fn((provider: typeof api.tokenProvider) => { api.tokenProvider = provider; }),
    getTokenProvider: vi.fn(() => api.tokenProvider),
    setOnAuthFailure: vi.fn(),
    setActiveOrgProvider: vi.fn(),
  };
  return { apiClient, ApiClient: class {}, ApiError: class extends Error {} };
});
vi.mock('@/lib/api', async () => ({ apiClient: (await import('@/lib/api/api-client')).apiClient }));
vi.mock('@/lib/websocket/ws-provider', () => ({
  WebSocketProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/components/PlanLimitToastListener', () => ({ default: () => null }));
// The Session-expired card's chrome (brand bar, theme): only the card itself matters here.
vi.mock('@/components/auth/AuthLayout', () => ({
  AuthLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import {
  AppDataProvider,
  isRecoveringStoredSession,
  LOGIN_REDIRECT_LOG_KEY,
  LOGIN_SIGNIN_AT_KEY,
  loginReturnPath,
} from '../smart-providers';
import { apiClient } from '@/lib/api/api-client';
import { OidcUserManagerContext } from '../../auth/oidcUserManager';
import { oidcConfig } from '../../../app/providers';
import FirstLoginGuard from '../../../components/security/FirstLoginGuard';
import LoginPage from '../../../app/[locale]/login/page';

const ISSUER = 'https://kc.example.test/realms/livecontext';
const TOKEN_ENDPOINT = `${ISSUER}/protocol/openid-connect/token`;

type TokenReply = { status: number; body: Record<string, unknown> };
const fresh: TokenReply = {
  status: 200,
  body: { access_token: 'fresh-access', refresh_token: 'refresh-2', token_type: 'Bearer', expires_in: 900 },
};
const invalidGrant: TokenReply = {
  status: 400,
  body: { error: 'invalid_grant', error_description: 'Token is not active' },
};

/** A token endpoint that answers only when the test says so (a slow /token). */
function slowTokenEndpoint() {
  const pending: Array<(reply: TokenReply) => void> = [];
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    expect(String(input)).toBe(TOKEN_ENDPOINT);
    return new Promise<Response>((resolve) => {
      pending.push((reply) => resolve(new Response(JSON.stringify(reply.body), {
        status: reply.status,
        headers: { 'Content-Type': 'application/json' },
      })));
    });
  });
  return {
    fetchMock,
    async answer(reply: TokenReply) {
      await vi.waitFor(() => expect(pending.length).toBeGreaterThan(0));
      await act(async () => {
        pending.shift()!(reply);
      });
    },
  };
}

/** The app's UserManager settings, a stored session in localStorage (as in production). */
async function managerWithStoredUser(expiresInSeconds: number): Promise<UserManager> {
  const manager = new UserManager({
    ...oidcConfig,
    authority: ISSUER,
    client_id: 'livecontext-frontend',
    redirect_uri: 'https://app.example.test/app/',
    userStore: new WebStorageStateStore({ store: window.localStorage }),
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
    expires_at: now + expiresInSeconds,
  }));
  return manager;
}

const lifecycle = { mounts: 0, unmounts: 0 };
function AppContent() {
  useEffect(() => {
    lifecycle.mounts += 1;
    return () => { lifecycle.unmounts += 1; };
  }, []);
  return <div data-testid="app-content">app</div>;
}

const SPINNER_SELECTOR = '.fixed.inset-0.z-\\[9999\\]';

function renderApp(manager: UserManager, page: React.ReactNode) {
  return render(
    <OidcUserManagerContext.Provider value={manager}>
      <AuthProvider {...oidcConfig} userManager={manager}>
        <AppDataProvider>
          <FirstLoginGuard>{page}</FirstLoginGuard>
        </AppDataProvider>
      </AuthProvider>
    </OidcUserManagerContext.Provider>,
  );
}

/** One browser tab: its own provider stack on its own UserManager, sharing localStorage. */
function renderTab(manager: UserManager, tab: string) {
  const container = document.body.appendChild(document.createElement('div'));
  return render(
    <OidcUserManagerContext.Provider value={manager}>
      <AuthProvider {...oidcConfig} userManager={manager}>
        <AppDataProvider>
          <div data-testid={`app-${tab}`}>app {tab}</div>
        </AppDataProvider>
      </AuthProvider>
    </OidcUserManagerContext.Provider>,
    { container },
  );
}

/** Puts the browser on `pathname?search` (what the provider reads) and the router mock with it. */
function goTo(pathname: string, search = '') {
  window.history.replaceState({}, '', `${pathname}${search ? `?${search}` : ''}`);
  nav.pathname = pathname;
  nav.search = search;
}

const SESSION_EXPIRED_TEXT = 'Your session has expired.';

/** The redirect to Keycloak: started, and never settling, as while the browser leaves the page. */
function spyKeycloakRedirect(manager: UserManager) {
  return vi.spyOn(manager, 'signinRedirect').mockImplementation(() => new Promise<void>(() => {}));
}

/** Lets the provider's effects and the refresh start (the token endpoint keeps them waiting). */
async function settleEffects() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 50));
  });
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  router.replace.mockReset();
  router.push.mockReset();
  goTo('/en/app/chat');
  vi.mocked(apiClient.setOnAuthFailure).mockClear();
  api.tokenProvider = null;
  lifecycle.mounts = 0;
  lifecycle.unmounts = 0;
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(console, 'log').mockImplementation(() => {});
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('page-load recovery of an expired stored session', () => {
  it('on /app: spinner, no redirect and no Keycloak sign-in while a slow /token decides', async () => {
    const tokenEndpoint = slowTokenEndpoint();
    vi.stubGlobal('fetch', tokenEndpoint.fetchMock);
    const manager = await managerWithStoredUser(-60);
    const keycloakRedirect = spyKeycloakRedirect(manager);

    const { container } = renderApp(manager, <AppContent />);
    await settleEffects();
    await vi.waitFor(() => expect(tokenEndpoint.fetchMock).toHaveBeenCalledTimes(1));
    await settleEffects();

    // Pre-fix: FirstLoginGuard replaced the route with /en/login?returnTo=... right here.
    expect(router.replace).not.toHaveBeenCalled();
    expect(keycloakRedirect).not.toHaveBeenCalled();
    expect(container.querySelector(SPINNER_SELECTOR)).not.toBeNull();
    expect(screen.queryByTestId('app-content')).toBeNull();
  });

  it('on /login: the login page waits for the recovery instead of starting the Keycloak password form', async () => {
    nav.pathname = '/en/login';
    nav.search = 'returnTo=%2Fen%2Fapp%2Fchat';
    const tokenEndpoint = slowTokenEndpoint();
    vi.stubGlobal('fetch', tokenEndpoint.fetchMock);
    const manager = await managerWithStoredUser(-60);
    const keycloakRedirect = spyKeycloakRedirect(manager);

    renderApp(manager, <LoginPage />);
    await vi.waitFor(() => expect(tokenEndpoint.fetchMock).toHaveBeenCalledTimes(1));
    await settleEffects();

    // Pre-fix: the login page's loginWithRedirect had already called signinRedirect.
    expect(keycloakRedirect).not.toHaveBeenCalled();

    await tokenEndpoint.answer(fresh);

    // Recovered: the page sends the person back where they were going, still no Keycloak form.
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalledWith('/en/app/chat'));
    expect(keycloakRedirect).not.toHaveBeenCalled();
  });

  it('recovery success: the app renders signed in, with no redirect at any point', async () => {
    const tokenEndpoint = slowTokenEndpoint();
    vi.stubGlobal('fetch', tokenEndpoint.fetchMock);
    const manager = await managerWithStoredUser(-60);
    const keycloakRedirect = spyKeycloakRedirect(manager);

    renderApp(manager, <AppContent />);
    await tokenEndpoint.answer(fresh);

    await screen.findByTestId('app-content');
    expect((await manager.getUser())?.access_token).toBe('fresh-access');
    expect(router.replace).not.toHaveBeenCalled();
    expect(keycloakRedirect).not.toHaveBeenCalled();
  });

  it('recovery refused (invalid_grant): exactly one redirect to the login page, and nothing else starts one', async () => {
    const tokenEndpoint = slowTokenEndpoint();
    vi.stubGlobal('fetch', tokenEndpoint.fetchMock);
    const manager = await managerWithStoredUser(-60);
    const keycloakRedirect = spyKeycloakRedirect(manager);

    const { container } = renderApp(manager, <AppContent />);
    await tokenEndpoint.answer(invalidGrant);

    await vi.waitFor(() => expect(keycloakRedirect).toHaveBeenCalledTimes(1));
    await settleEffects();
    await settleEffects();

    // The browser is leaving for Keycloak: the spinner stays and no guard starts a second sign-in.
    expect(keycloakRedirect).toHaveBeenCalledTimes(1);
    expect(router.replace).not.toHaveBeenCalled();
    expect(tokenEndpoint.fetchMock).toHaveBeenCalledTimes(1); // a refusal is not retried
    expect(container.querySelector(SPINNER_SELECTOR)).not.toBeNull();
  });

  it('recovery refused and the loop guard refuses the redirect: loading ends on the Session-expired card, no sign-in starts', async () => {
    // A sign-in moments ago: an automatic redirect now would be the login loop, so it is refused.
    sessionStorage.setItem(LOGIN_SIGNIN_AT_KEY, Date.now().toString());
    const tokenEndpoint = slowTokenEndpoint();
    vi.stubGlobal('fetch', tokenEndpoint.fetchMock);
    const manager = await managerWithStoredUser(-60);
    const keycloakRedirect = spyKeycloakRedirect(manager);

    renderApp(manager, <AppContent />);
    await tokenEndpoint.answer(invalidGrant);

    await screen.findByText(SESSION_EXPIRED_TEXT, { exact: false });
    expect(keycloakRedirect).not.toHaveBeenCalled();

    // The card's explicit "Sign in" goes, and brings the person back to this page.
    await act(async () => {
      screen.getByRole('button', { name: /Sign in/ }).click();
    });
    await vi.waitFor(() => expect(keycloakRedirect).toHaveBeenCalledTimes(1));
    expect(keycloakRedirect.mock.calls[0][0]).toMatchObject({
      redirect_uri: `${window.location.origin}/en/app/chat`,
    });
  });
});

describe('where signing in again brings the person back (the recovery redirect)', () => {
  it('a deep link survives the round trip through Keycloak', async () => {
    goTo('/en/app/workflows/123', 'tab=runs');
    const tokenEndpoint = slowTokenEndpoint();
    vi.stubGlobal('fetch', tokenEndpoint.fetchMock);
    const manager = await managerWithStoredUser(-60);
    const keycloakRedirect = spyKeycloakRedirect(manager);

    renderApp(manager, <AppContent />);
    await tokenEndpoint.answer(invalidGrant);

    // Pre-fix: redirect_uri was always `${origin}/app/` and the link was lost.
    await vi.waitFor(() => expect(keycloakRedirect).toHaveBeenCalledTimes(1));
    expect(keycloakRedirect.mock.calls[0][0]).toMatchObject({
      redirect_uri: `${window.location.origin}/en/app/workflows/123?tab=runs`,
    });
  });

  it('on /login, the returnTo the person was carrying is kept', async () => {
    goTo('/en/login', `returnTo=${encodeURIComponent('/en/app/workflows/9?run=4')}`);
    const tokenEndpoint = slowTokenEndpoint();
    vi.stubGlobal('fetch', tokenEndpoint.fetchMock);
    const manager = await managerWithStoredUser(-60);
    const keycloakRedirect = spyKeycloakRedirect(manager);

    renderApp(manager, <LoginPage />);
    await tokenEndpoint.answer(invalidGrant);

    await vi.waitFor(() => expect(keycloakRedirect).toHaveBeenCalledTimes(1));
    expect(keycloakRedirect.mock.calls[0][0]).toMatchObject({
      redirect_uri: `${window.location.origin}/en/app/workflows/9?run=4`,
    });
  });

  it.each([
    ['protocol-relative', '//evil.example/steal'],
    ['absolute, another origin', 'https://evil.example/steal'],
    ['backslash', '/\\evil.example'],
    ['javascript:', 'javascript:alert(1)'],
  ])('a hostile returnTo on /login (%s) is refused and falls back to /app/', async (_label, hostile) => {
    goTo('/en/login', `returnTo=${encodeURIComponent(hostile)}`);
    const tokenEndpoint = slowTokenEndpoint();
    vi.stubGlobal('fetch', tokenEndpoint.fetchMock);
    const manager = await managerWithStoredUser(-60);
    const keycloakRedirect = spyKeycloakRedirect(manager);

    renderApp(manager, <LoginPage />);
    await tokenEndpoint.answer(invalidGrant);

    await vi.waitFor(() => expect(keycloakRedirect).toHaveBeenCalledTimes(1));
    expect(keycloakRedirect.mock.calls[0][0]).toMatchObject({ redirect_uri: `${window.location.origin}/app/` });
  });
});

describe('loginReturnPath', () => {
  it('is the current page with its query, without the fragment', () => {
    expect(loginReturnPath({ pathname: '/en/app/workflows/1', search: '?tab=a' })).toBe('/en/app/workflows/1?tab=a');
  });

  it('is the returnTo on the login and register pages, with or without a locale', () => {
    expect(loginReturnPath({ pathname: '/fr/login', search: '?returnTo=%2Ffr%2Fapp%2Fx' })).toBe('/fr/app/x');
    expect(loginReturnPath({ pathname: '/login', search: '?returnTo=%2Fapp%2Fy' })).toBe('/app/y');
    expect(loginReturnPath({ pathname: '/en/register', search: '?returnTo=%2Fen%2Finvitations%2Faccept' }))
      .toBe('/en/invitations/accept');
  });

  it('falls back when the login page has no returnTo or a hostile one', () => {
    expect(loginReturnPath({ pathname: '/en/login', search: '' })).toBe('/app/');
    expect(loginReturnPath({ pathname: '/en/login', search: '?returnTo=%2F%2Fevil.example' })).toBe('/app/');
  });
});

describe('offline page load (NIT 1)', () => {
  it('refresh and Keycloak both unreachable: the login page shows the error and a retry instead of an endless spinner', async () => {
    goTo('/en/login', `returnTo=${encodeURIComponent('/en/app/chat')}`);
    const offline = vi.fn(() => Promise.reject(new TypeError('Failed to fetch')));
    vi.stubGlobal('fetch', offline);
    const manager = await managerWithStoredUser(-60);
    // The redirect fails before leaving (discovery unreachable); react-oidc-context swallows the
    // error and resolves null.
    const keycloakRedirect = vi.spyOn(manager, 'signinRedirect')
      .mockRejectedValue(new TypeError('Failed to fetch'));

    renderApp(manager, <LoginPage />);

    // Pre-fix: the spinner stayed forever (the page only listened for a rejection).
    const retry = await screen.findByRole('button', { name: 'retry' }, { timeout: 5_000 });
    expect(screen.getByText('signInUnreachable')).toBeTruthy();
    const callsBeforeRetry = keycloakRedirect.mock.calls.length;
    expect(callsBeforeRetry).toBeGreaterThanOrEqual(1);

    // Back online: the explicit retry starts the sign-in again (and leaves the page).
    keycloakRedirect.mockImplementation(() => new Promise<void>(() => {}));
    await act(async () => {
      retry.click();
    });
    await vi.waitFor(() => expect(keycloakRedirect.mock.calls.length).toBe(callsBeforeRetry + 1));
    expect(keycloakRedirect.mock.calls[callsBeforeRetry][0]).toMatchObject({
      redirect_uri: `${window.location.origin}/en/app/chat`,
    });
    expect(screen.queryByRole('button', { name: 'retry' })).toBeNull();
  }, 15_000);
});

describe('the 3-in-60s loop guard across the recovery -> /login path (NIT 2)', () => {
  it('a recovery refused by the exhausted budget does not hand the login page a fresh one', async () => {
    goTo('/en/login', `returnTo=${encodeURIComponent('/en/app/chat')}`);
    const now = Date.now();
    sessionStorage.setItem(LOGIN_REDIRECT_LOG_KEY, JSON.stringify([now - 3_000, now - 2_000, now - 1_000]));
    const tokenEndpoint = slowTokenEndpoint();
    vi.stubGlobal('fetch', tokenEndpoint.fetchMock);
    const manager = await managerWithStoredUser(-60);
    const keycloakRedirect = spyKeycloakRedirect(manager);

    renderApp(manager, <LoginPage />);
    await tokenEndpoint.answer(invalidGrant);
    await screen.findByText(SESSION_EXPIRED_TEXT, { exact: false });
    await settleEffects();

    // Pre-fix: the refusal wiped the log, the recovery settled, and the login page redirected.
    expect(keycloakRedirect).not.toHaveBeenCalled();
    expect(JSON.parse(sessionStorage.getItem(LOGIN_REDIRECT_LOG_KEY) || '[]')).toHaveLength(3);
  });
});

describe('a loop-guard refusal in one tab (NIT 3)', () => {
  it('ends the session in that tab only: the stored session stays and the other tab stays signed in', async () => {
    // The browser delivers a localStorage removal to the OTHER tabs as a storage event.
    const realRemoveItem = Storage.prototype.removeItem;
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(function (this: Storage, key: string) {
      const oldValue = this.getItem(key);
      realRemoveItem.call(this, key);
      if (this === window.localStorage && oldValue !== null) {
        window.dispatchEvent(new StorageEvent('storage', { key, oldValue, newValue: null, storageArea: this }));
      }
    });
    const managerA = await managerWithStoredUser(600);
    const managerB = await managerWithStoredUser(600);
    spyKeycloakRedirect(managerA);
    spyKeycloakRedirect(managerB);
    const storedKey = `oidc.user:${ISSUER}:livecontext-frontend`;
    expect(localStorage.getItem(storedKey)).not.toBeNull();

    const tabA = renderTab(managerA, 'a');
    await within(tabA.container).findByTestId('app-a');
    const onAuthFailure = vi.mocked(apiClient.setOnAuthFailure).mock.calls.at(-1)![0] as () => void;
    const tabB = renderTab(managerB, 'b');
    await within(tabB.container).findByTestId('app-b');

    // One transient 401 right after signing in (layer 1 of the loop guard refuses the redirect).
    sessionStorage.setItem(LOGIN_SIGNIN_AT_KEY, Date.now().toString());
    await act(async () => {
      onAuthFailure();
    });
    await within(tabA.container).findByText(SESSION_EXPIRED_TEXT, { exact: false });
    // Longer than the 500 ms persisted-user check, so a removal would have been seen by tab B.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 700));
    });

    // Pre-fix: removeUser deleted the shared entry, and tab B was signed out with tab A.
    expect(localStorage.getItem(storedKey)).not.toBeNull();
    expect(within(tabB.container).getByTestId('app-b')).toBeTruthy();
    expect(within(tabB.container).queryByText(SESSION_EXPIRED_TEXT, { exact: false })).toBeNull();
  });
});

describe('the access-token expired event during the page-load recovery (NIT 5)', () => {
  it('does not start a second refresh (one refresh token spent, not two)', async () => {
    const tokenEndpoint = slowTokenEndpoint();
    vi.stubGlobal('fetch', tokenEndpoint.fetchMock);
    const manager = await managerWithStoredUser(-60);
    spyKeycloakRedirect(manager);

    renderApp(manager, <AppContent />);
    await vi.waitFor(() => expect(tokenEndpoint.fetchMock).toHaveBeenCalledTimes(1));

    // The UserManager raises "expired" for the stored token while the recovery refreshes it.
    await act(async () => {
      (manager.events as unknown as { _expiredTimer: { raise: () => Promise<void> } })._expiredTimer.raise();
    });
    await tokenEndpoint.answer(fresh);
    await screen.findByTestId('app-content');
    // Several lease polls: a queued second refresh would have reached /token by now.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 700));
    });

    expect(tokenEndpoint.fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('a refresh in the middle of a session', () => {
  it('never shows the spinner nor unmounts the app, even with a slow /token', async () => {
    const tokenEndpoint = slowTokenEndpoint();
    vi.stubGlobal('fetch', tokenEndpoint.fetchMock);
    const manager = await managerWithStoredUser(600);
    const keycloakRedirect = spyKeycloakRedirect(manager);

    const { container } = renderApp(manager, <AppContent />);
    await screen.findByTestId('app-content');
    // The mount effect runs after the element shows up (passive effect, late under a loaded run).
    await vi.waitFor(() => expect(lifecycle.mounts).toBe(1));
    expect(tokenEndpoint.fetchMock).not.toHaveBeenCalled();

    // The apiClient path after a 401: the provider's token provider forces a refresh.
    expect(api.tokenProvider).not.toBeNull();
    let refreshed: Promise<string | null> | undefined;
    await act(async () => {
      refreshed = api.tokenProvider!({ forceRefresh: true });
    });
    await vi.waitFor(() => expect(tokenEndpoint.fetchMock).toHaveBeenCalledTimes(1));
    await settleEffects();

    expect(container.querySelector(SPINNER_SELECTOR)).toBeNull();
    expect(screen.getByTestId('app-content')).toBeTruthy();

    await tokenEndpoint.answer(fresh);
    await expect(refreshed).resolves.toBe('fresh-access');
    await settleEffects();

    expect(screen.getByTestId('app-content')).toBeTruthy();
    expect(lifecycle).toEqual({ mounts: 1, unmounts: 0 });
    expect(router.replace).not.toHaveBeenCalled();
    expect(keycloakRedirect).not.toHaveBeenCalled();
  });
});

describe('isRecoveringStoredSession', () => {
  const recovering = {
    embeddedAuth: false,
    oidcLoading: false,
    authenticated: false,
    sessionExpired: false,
    storedUserExpired: true,
    recoverySettled: false,
  };

  it('is loading while an expired stored cloud session has not been recovered yet', () => {
    expect(isRecoveringStoredSession(recovering)).toBe(true);
  });

  it('leaves CE embedded auth alone (it recovers inside its own loading phase)', () => {
    expect(isRecoveringStoredSession({ ...recovering, embeddedAuth: true })).toBe(false);
  });

  it('ends once the recovery settled, the person is signed in, or the session is known to be over', () => {
    expect(isRecoveringStoredSession({ ...recovering, recoverySettled: true })).toBe(false);
    expect(isRecoveringStoredSession({ ...recovering, authenticated: true })).toBe(false);
    expect(isRecoveringStoredSession({ ...recovering, sessionExpired: true })).toBe(false);
  });

  it('does not apply without an expired stored user (no session, or a valid one)', () => {
    expect(isRecoveringStoredSession({ ...recovering, storedUserExpired: false })).toBe(false);
  });

  it('defers to OIDC while OIDC is still loading', () => {
    expect(isRecoveringStoredSession({ ...recovering, oidcLoading: true })).toBe(false);
  });
});

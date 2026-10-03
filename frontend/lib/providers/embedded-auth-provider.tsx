/**
 * Embedded Auth Provider - CE (Community Edition) alternative to react-oidc-context.
 *
 * Manages JWT access/refresh tokens against the embedded auth endpoints
 * (POST /api/auth/login, /api/auth/refresh, etc.) instead of Keycloak OIDC.
 *
 * Exposes the same useAuth() hook shape as react-oidc-context so that
 * smart-providers.tsx can work with either provider transparently.
 */

'use client';

import React, { createContext, useContext, useCallback, useEffect, useRef, useState, useMemo, type ReactNode } from 'react';
import { markOrbiGreeting } from '@/components/chat/orbi/orbiGreeting';
import { LEASE_TTL_MS, withCrossTabLock } from '@/lib/auth/crossTabLock';
import { CE_ACCESS_TOKEN_KEY } from '@/lib/auth/sessionKeys';

// ── Storage keys ────────────────────────────────────────────────
// Shared with the public header, which only needs to know a session is stored.
const ACCESS_TOKEN_KEY = CE_ACCESS_TOKEN_KEY;
const REFRESH_TOKEN_KEY = 'ce_refresh_token';
const TOKEN_EXPIRY_KEY = 'ce_token_expiry';
const USER_DATA_KEY = 'ce_user_data';
const LOCALE_PREFIX_PATTERN = /^\/(en|fr|es|de|pt|zh)(?=\/|$)/;

// ── Types matching react-oidc-context's useAuth() shape ─────────
interface EmbeddedUser {
  access_token: string;
  expired: boolean;
  expires_at: number;
  profile: {
    sub: string;
    email?: string;
    given_name?: string;
    family_name?: string;
    name?: string;
    email_verified?: boolean;
    roles?: string[];
    picture?: string;
  };
}

interface EmbeddedAuthContextType {
  user: EmbeddedUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  events: null;
  signinRedirect: (opts?: { redirect_uri?: string; extraQueryParams?: Record<string, string> }) => Promise<void>;
  signinSilent: () => Promise<EmbeddedUser | null>;
  signoutRedirect: (opts?: { post_logout_redirect_uri?: string }) => Promise<void>;
  removeUser: () => Promise<void>;
  stopSilentRenew: () => void;
}

const EmbeddedAuthContext = createContext<EmbeddedAuthContextType | null>(null);

/**
 * Drop-in replacement for react-oidc-context's useAuth().
 * Only active when NEXT_PUBLIC_AUTH_MODE=embedded.
 */
export function useEmbeddedAuth(): EmbeddedAuthContextType {
  const ctx = useContext(EmbeddedAuthContext);
  if (!ctx) throw new Error('useEmbeddedAuth must be used within EmbeddedAuthProvider');
  return ctx;
}

// ── Token helpers ───────────────────────────────────────────────

function saveTokens(accessToken: string, refreshToken: string, expiresIn: number, userData: any) {
  const expiresAt = Math.floor(Date.now() / 1000) + expiresIn;
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
  localStorage.setItem(TOKEN_EXPIRY_KEY, String(expiresAt));
  localStorage.setItem(USER_DATA_KEY, JSON.stringify(userData));
}

function clearTokens() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRY_KEY);
  localStorage.removeItem(USER_DATA_KEY);
}

function loadStoredUser(): EmbeddedUser | null {
  try {
    const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY);
    const expiresAt = Number(localStorage.getItem(TOKEN_EXPIRY_KEY) || '0');
    const userData = JSON.parse(localStorage.getItem(USER_DATA_KEY) || 'null');
    if (!accessToken || !userData) return null;

    const now = Math.floor(Date.now() / 1000);
    return {
      access_token: accessToken,
      expired: now >= expiresAt,
      expires_at: expiresAt,
      profile: {
        sub: String(userData.id),
        email: userData.email,
        given_name: userData.firstName,
        family_name: userData.lastName,
        name: [userData.firstName, userData.lastName].filter(Boolean).join(' ') || userData.username,
        email_verified: userData.emailVerified,
        roles: userData.roles || [],
      },
    };
  } catch {
    return null;
  }
}

// ── Sharing one session across the tabs of this browser (CASA LC-015) ────────
//
// Tabs share one refresh token (localStorage) but each holds its access token in memory. An
// access token is bound to the refresh-token row it was minted with (sid), so a refresh in one
// tab withdraws the other tabs' tokens 30s later. Each of those then refreshed in turn,
// withdrawing the first tab's, and two tabs refreshing at once presented the same, already spent,
// refresh token: the server reads that as token theft and signs every tab out. So a tab takes
// the token another tab obtained instead of refreshing, and refreshes run one tab at a time.

/** Validity another tab's token must still have to be worth taking: the refresh lead below. */
const ADOPT_MIN_SECONDS_LEFT = 60;
/**
 * Seconds to add to a token's {@code exp} claim (server clock) to read it on this browser's clock.
 * Learned from this browser's own refreshes, where the answer carries both the token and its
 * lifetime, and shared with the other tabs: a LAN machine whose clock is off by minutes would
 * otherwise judge every token another tab obtained already expired (or fresh when it is not).
 */
export const CLOCK_OFFSET_KEY = 'ce_clock_offset';

function readClockOffsetSeconds(): number {
  try {
    const offset = Number(localStorage.getItem(CLOCK_OFFSET_KEY));
    return Number.isFinite(offset) ? offset : 0;
  } catch {
    return 0;
  }
}

/** Longest delay setTimeout honours (2^31 - 1 ms, about 24.8 days); a longer one fires at once. */
const MAX_TIMER_DELAY_MS = 2_147_483_647;

/** The claims this provider reads from a CE access token; null when it is not a readable JWT. */
function accessTokenClaims(token: string | null | undefined): { userId?: string; exp?: number } | null {
  const payload = token?.split('.')[1];
  if (!payload) return null;
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const claims = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')));
    return {
      userId: claims.userId != null ? String(claims.userId) : undefined,
      exp: typeof claims.exp === 'number' ? claims.exp : undefined,
    };
  } catch {
    return null;
  }
}

type AnotherTabsSession = { kind: 'same-user'; user: EmbeddedUser } | { kind: 'other-user' };

/**
 * What another tab stored since this tab took {@code tokenAtCall}, when it is worth acting on:
 * a token of the same user with more than a minute left (take it), or a token of ANOTHER user
 * (someone signed in as someone else in another tab: this tab's screens and caches belong to the
 * previous user, so it must not silently run as the new one). Null otherwise.
 *
 * <p>Identity and expiry come from the token's own claims, not from the other keys: the four keys
 * are written one by one, and another tab can read the new token before the rest has landed.
 */
function anotherTabsSession(tokenAtCall: string | undefined, userIdAtCall: string | undefined): AnotherTabsSession | null {
  let accessToken: string | null;
  try {
    accessToken = localStorage.getItem(ACCESS_TOKEN_KEY);
  } catch {
    return null;
  }
  if (!accessToken || accessToken === tokenAtCall) return null;
  const claims = accessTokenClaims(accessToken);
  if (!claims?.exp || !claims.userId) return null;
  const expiresAt = claims.exp + readClockOffsetSeconds();
  if (expiresAt - Math.floor(Date.now() / 1000) <= ADOPT_MIN_SECONDS_LEFT) return null;
  if (userIdAtCall && claims.userId !== userIdAtCall) return { kind: 'other-user' };
  const stored = loadStoredUser();
  if (!stored || stored.profile.sub !== claims.userId) return null;
  return { kind: 'same-user', user: { ...stored, access_token: accessToken, expires_at: expiresAt, expired: false } };
}

/**
 * What a refresh run under the cross-tab lock answers when another user now holds the session. The
 * switch itself happens once the lock is released: the reload can be cancelled (a page with unsaved
 * changes asks first), and a tab that stays open must not keep every other tab from refreshing.
 */
const OTHER_USER = Symbol('other-user');

/**
 * How long the requests waiting on a switch to another user stay unanswered: long enough for the
 * reload to take the page away. A page still alive after it cancelled the reload (unsaved
 * changes, and the user chose to stay).
 */
export const RELOAD_CANCELLED_AFTER_MS = 10_000;

/**
 * Another user now holds this browser's session: reload this tab into it. The callers are
 * requests of the previous user, and answering them early (a null sends the api client to the
 * login page) would race the reload, so they wait. If the page is still there after
 * RELOAD_CANCELLED_AFTER_MS, the reload was cancelled: they get null then, which sends this tab to
 * sign in, instead of every later call of the tab hanging on a promise that never settles.
 */
function switchToTheOtherUser(): Promise<null> {
  crossTabSessionSwitch.reload();
  return new Promise<null>((resolve) => setTimeout(() => resolve(null), RELOAD_CANCELLED_AFTER_MS));
}

/** How this tab reloads when another tab signed in as someone else; a seam for the tests. */
export const crossTabSessionSwitch = {
  reload: () => {
    if (typeof window !== 'undefined') window.location.reload();
  },
};

/** Name of the lock that keeps two tabs of this origin from refreshing at the same time. */
export const REFRESH_LOCK_NAME = 'ce-auth-refresh';
/** localStorage lease used instead of the lock where the browser has none (plain-HTTP installs). */
export const REFRESH_LEASE_KEY = 'ce_refresh_lease';
/** Re-exported: the lease lifetime lives with the shared lock (lib/auth/crossTabLock.ts). */
export { LEASE_TTL_MS };

/** Runs fn while holding this origin's CE refresh lock, shared by every tab (lib/auth/crossTabLock.ts). */
function withCrossTabRefreshLock<T>(fn: () => Promise<T>): Promise<T> {
  return withCrossTabLock(REFRESH_LOCK_NAME, REFRESH_LEASE_KEY, fn);
}

function loginPathForCurrentRoute(): string {
  if (typeof window === 'undefined') return '/login';
  const locale = window.location.pathname.match(LOCALE_PREFIX_PATTERN)?.[1];
  return locale ? `/${locale}/login` : '/login';
}

// ── Provider ────────────────────────────────────────────────────

interface EmbeddedAuthProviderProps {
  children: ReactNode;
}

export function EmbeddedAuthProvider({ children }: EmbeddedAuthProviderProps) {
  const [user, setUser] = useState<EmbeddedUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const refreshPromiseRef = useRef<Promise<EmbeddedUser | null> | null>(null);
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // The token this tab holds right now, readable synchronously (state lags a render).
  const userRef = useRef<EmbeddedUser | null>(null);
  const applyUser = useCallback((next: EmbeddedUser | null) => {
    userRef.current = next;
    setUser(next);
  }, []);

  // Build EmbeddedUser from API response
  const buildUser = useCallback((accessToken: string, expiresIn: number, userData: any): EmbeddedUser => {
    const expiresAt = Math.floor(Date.now() / 1000) + expiresIn;
    return {
      access_token: accessToken,
      expired: false,
      expires_at: expiresAt,
      profile: {
        sub: String(userData.id),
        email: userData.email,
        given_name: userData.firstName,
        family_name: userData.lastName,
        name: [userData.firstName, userData.lastName].filter(Boolean).join(' ') || userData.username,
        email_verified: userData.emailVerified,
        roles: userData.roles || [],
      },
    };
  }, []);

  // Schedule proactive refresh 60s before expiry
  const scheduleRefresh = useCallback((expiresAt: number) => {
    if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
    const refreshAtMs = (expiresAt - ADOPT_MIN_SECONDS_LEFT) * 1000;
    // Spread over up to 5s: tabs that took the same token would otherwise all wake at the same
    // instant and queue behind the one that refreshes.
    const delay = Math.max(refreshAtMs + Math.random() * 5000 - Date.now(), 5000); // At least 5s
    refreshTimerRef.current = setTimeout(() => {
      // setTimeout cannot wait longer than MAX_TIMER_DELAY_MS: an early wake-up re-arms instead
      // of refreshing a token that still has days to live.
      if (Date.now() < refreshAtMs) {
        scheduleRefresh(expiresAt);
        return;
      }
      signinSilent();
    }, Math.min(delay, MAX_TIMER_DELAY_MS));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * One refresh, run while holding the cross-tab lock. {@code tokenAtCall} / {@code userIdAtCall}
   * are what the caller held when it asked: by the time the lock is granted the storage listener
   * may already have moved this tab on, and the check must compare with the caller's token.
   */
  const refreshOnce = useCallback(async (tokenAtCall: string | undefined,
                                         userIdAtCall: string | undefined): Promise<EmbeddedUser | null | typeof OTHER_USER> => {
    try {
      // Another tab refreshed first (or while this one waited for the lock): take its token
      // rather than presenting a refresh token that tab has already spent.
      const session = anotherTabsSession(tokenAtCall, userIdAtCall);
      if (session?.kind === 'other-user') {
        return OTHER_USER;
      }
      if (session?.kind === 'same-user') {
        applyUser(session.user);
        scheduleRefresh(session.user.expires_at);
        return session.user;
      }

      const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
      if (!refreshToken) return null;

      // Awaited to the end, never aborted. The server may already have rotated the refresh token when
      // the answer is late, and presenting the old one again (this tab's next attempt, or the tab
      // waiting for the lock) is what the server reads as token theft: it revokes every session of
      // the user, on every device. So the lock is held until the answer lands, however slow.
      const res = await fetch('/api/proxy/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });

      if (!res.ok) {
        clearTokens();
        applyUser(null);
        return null;
      }

      const data = await res.json();
      // Written before the token, so a tab that reads the new token reads its offset too.
      const issuedExp = accessTokenClaims(data.accessToken)?.exp;
      if (issuedExp && typeof data.expiresIn === 'number') {
        try {
          localStorage.setItem(CLOCK_OFFSET_KEY, String(Math.floor(Date.now() / 1000) + data.expiresIn - issuedExp));
        } catch {
          // Storage refused the write: other tabs keep reading exp on their own clock.
        }
      }
      saveTokens(data.accessToken, data.refreshToken, data.expiresIn, data.user);
      // The stored refresh token was another user's (their token could not be read or was about
      // to expire, so it was not taken above): their session is now the stored one, and this tab
      // belongs to the previous user.
      if (userIdAtCall && data.user?.id != null && String(data.user.id) !== userIdAtCall) {
        return OTHER_USER;
      }
      const newUser = buildUser(data.accessToken, data.expiresIn, data.user);
      applyUser(newUser);
      scheduleRefresh(newUser.expires_at);
      return newUser;
    } catch {
      clearTokens();
      applyUser(null);
      return null;
    }
  }, [applyUser, buildUser, scheduleRefresh]);

  // Refresh token
  const signinSilent = useCallback((): Promise<EmbeddedUser | null> => {
    // Deduplicate concurrent refresh calls
    if (refreshPromiseRef.current) return refreshPromiseRef.current;

    const tokenAtCall = userRef.current?.access_token;
    const userIdAtCall = userRef.current?.profile.sub;
    const promise = withCrossTabRefreshLock(() => refreshOnce(tokenAtCall, userIdAtCall))
      .catch(() => null)
      .then((result) => (result === OTHER_USER ? switchToTheOtherUser() : result));
    refreshPromiseRef.current = promise;
    // Released only if it is still the pending one: set BEFORE this runs, so a refresh that
    // settles synchronously (an adoption without the lock) cannot leave a settled promise behind
    // that every later call of this tab would return.
    const release = () => {
      if (refreshPromiseRef.current === promise) refreshPromiseRef.current = null;
    };
    promise.then(release, release);
    return promise;
  }, [refreshOnce]);

  // Another tab refreshed: take its token now, before this tab's own is withdrawn.
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      // The access token is written first and changes on every refresh; its own claims carry
      // the identity and expiry, so the keys written after it need not have landed yet.
      if (event.key !== ACCESS_TOKEN_KEY || !event.newValue) return;
      const current = userRef.current;
      if (!current) return;
      const session = anotherTabsSession(current.access_token, current.profile.sub);
      if (session?.kind === 'other-user') {
        crossTabSessionSwitch.reload();
      } else if (session?.kind === 'same-user') {
        applyUser(session.user);
        scheduleRefresh(session.user.expires_at);
      }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [applyUser, scheduleRefresh]);

  // On mount, load stored tokens and refresh if expired
  useEffect(() => {
    const init = async () => {
      const stored = loadStoredUser();
      if (!stored) {
        setIsLoading(false);
        return;
      }

      if (stored.expired) {
        // Try refresh
        const refreshed = await signinSilent();
        if (!refreshed) {
          setIsLoading(false);
          return;
        }
      } else {
        applyUser(stored);
        scheduleRefresh(stored.expires_at);
      }
      setIsLoading(false);
    };
    init();

    return () => {
      if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Refresh token on tab visibility (same as OIDC provider behavior)
  useEffect(() => {
    const handleVisibility = () => {
      if (document.visibilityState !== 'visible') return;
      const stored = loadStoredUser();
      if (!stored) return;
      const expiresIn = stored.expires_at - Math.floor(Date.now() / 1000);
      if (expiresIn <= ADOPT_MIN_SECONDS_LEFT) {
        signinSilent();
      }
    };
    document.addEventListener('visibilitychange', handleVisibility);
    return () => document.removeEventListener('visibilitychange', handleVisibility);
  }, [signinSilent]);

  const signinRedirect = useCallback(async (opts?: { redirect_uri?: string; extraQueryParams?: Record<string, string> }) => {
    // In embedded mode, redirect to login page
    const returnTo = opts?.redirect_uri || '/app/';
    window.location.href = `${loginPathForCurrentRoute()}?returnTo=${encodeURIComponent(returnTo)}`;
  }, []);

  const signoutRedirect = useCallback(async (opts?: { post_logout_redirect_uri?: string }) => {
    try {
      // Under the refresh lock: a sign-out racing another tab's refresh would revoke the token
      // that refresh is spending, and the refresh would then store a live session again.
      await withCrossTabRefreshLock(async () => {
        try {
          const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
          const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY);
          if (refreshToken && accessToken) {
            await fetch('/api/proxy/auth/logout', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${accessToken}`,
              },
              body: JSON.stringify({ refreshToken }),
            }).catch(() => {});
          }
        } finally {
          clearTokens();
        }
      });
    } catch {
      // The lock itself failed: sign out anyway, this tab's user asked for it.
      clearTokens();
    } finally {
      applyUser(null);
      if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
      window.location.href = opts?.post_logout_redirect_uri || '/login';
    }
  }, [applyUser]);

  const removeUser = useCallback(async () => {
    clearTokens();
    applyUser(null);
  }, [applyUser]);

  const stopSilentRenew = useCallback(() => {
    if (refreshTimerRef.current) {
      clearTimeout(refreshTimerRef.current);
      refreshTimerRef.current = null;
    }
  }, []);

  const value = useMemo<EmbeddedAuthContextType>(() => ({
    user,
    isAuthenticated: !!user && !user.expired,
    isLoading,
    events: null, // Not used for embedded auth
    signinRedirect,
    signinSilent,
    signoutRedirect,
    removeUser,
    stopSilentRenew,
  }), [user, isLoading, signinRedirect, signinSilent, signoutRedirect, removeUser, stopSilentRenew]);

  return (
    <EmbeddedAuthContext.Provider value={value}>
      {children}
    </EmbeddedAuthContext.Provider>
  );
}

/**
 * Login helper - calls backend and saves tokens.
 * Used by the login page component.
 */
export async function embeddedLogin(email: string, password: string): Promise<{ success: boolean; error?: string; user?: any }> {
  try {
    const res = await fetch('/api/proxy/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      return { success: false, error: err.message || err.error || 'Login failed' };
    }

    const data = await res.json();
    // Under the refresh lock: another tab's refresh still in flight would otherwise store the
    // previous user's rotated session over this one once it answers.
    await withCrossTabRefreshLock(async () => {
      saveTokens(data.accessToken, data.refreshToken, data.expiresIn, data.user);
    });
    markOrbiGreeting();
    return { success: true, user: data.user };
  } catch (e: any) {
    return { success: false, error: e.message || 'Network error' };
  }
}

/**
 * Asks for a password reset link.
 *
 * <p>Deliberately reports success for a NON-EXISTENT address too, because the
 * backend answers identically on purpose: any difference here would turn the
 * form into an account enumeration oracle. So this helper cannot tell the caller
 * whether a mail was actually sent, and the page must not pretend otherwise.
 *
 * <p>There is no "this install has no mail server" answer to surface, and there
 * cannot be: the backend answers 200 for every outcome, because a delivery
 * failure is only ever observable for an address that HAS an account. An error
 * from here therefore means the request itself failed (offline, proxy, 500), not
 * that the address was rejected.
 */
export async function embeddedForgotPassword(email: string): Promise<{ success: boolean; error?: string }> {
  try {
    const res = await fetch('/api/proxy/auth/forgot-password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email }),
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      return { success: false, error: err.message || err.error || 'Request failed' };
    }
    return { success: true };
  } catch (e: any) {
    return { success: false, error: e.message || 'Network error' };
  }
}

/**
 * Redeems a reset token and sets the new password.
 *
 * <p>No tokens are saved on success: the backend has just revoked every refresh
 * token for that user, which is the point of a reset. The caller sends the
 * person to the login page to authenticate with the new password.
 */
export async function embeddedResetPassword(
  token: string,
  newPassword: string,
): Promise<{ success: boolean; error?: string }> {
  try {
    const res = await fetch('/api/proxy/auth/reset-password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, newPassword }),
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      return { success: false, error: err.message || err.error || 'Reset failed' };
    }
    return { success: true };
  } catch (e: any) {
    return { success: false, error: e.message || 'Network error' };
  }
}

/**
 * Register helper - calls backend and saves tokens.
 */
export async function embeddedRegister(
  email: string,
  password: string,
  firstName: string,
  lastName: string,
  invitationToken?: string
): Promise<{ success: boolean; error?: string; user?: any }> {
  try {
    const res = await fetch('/api/proxy/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // invitationToken (when present) lets a brand-new invitee register past a
      // closed public-registration door + auto-join the org. The backend only
      // honours it for a valid, email-matching PENDING invitation.
      body: JSON.stringify({ email, password, firstName, lastName, ...(invitationToken ? { invitationToken } : {}) }),
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      return { success: false, error: err.message || err.error || 'Registration failed' };
    }

    const data = await res.json();
    // Under the refresh lock: another tab's refresh still in flight would otherwise store the
    // previous user's rotated session over this one once it answers.
    await withCrossTabRefreshLock(async () => {
      saveTokens(data.accessToken, data.refreshToken, data.expiresIn, data.user);
    });
    markOrbiGreeting();
    return { success: true, user: data.user };
  } catch (e: any) {
    return { success: false, error: e.message || 'Network error' };
  }
}
/**
 * Change-password helper - CE only.
 *
 * Calls POST /api/auth/change-password with the stored access token. The CE
 * MonolithSecurityFilter validates the Bearer token and injects X-User-ID, which
 * the embedded controller reads to identify the account. On success the backend
 * revokes all refresh tokens, so the caller MUST sign the user out afterwards.
 *
 * Cloud (Keycloak) has no such endpoint - password changes go through Keycloak via
 * the kc_action=UPDATE_PASSWORD redirect, not this helper.
 */
export async function embeddedChangePassword(
  currentPassword: string,
  newPassword: string,
): Promise<{ success: boolean; status?: number; error?: string }> {
  try {
    const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY);
    if (!accessToken) {
      // No status: a missing local token is not a "wrong current password" (401)
      // - callers map a status-less failure to a generic error. Effectively
      // unreachable from the Security tab (it only renders for signed-in users).
      return { success: false, error: 'Not authenticated' };
    }

    const res = await fetch('/api/proxy/auth/change-password', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${accessToken}`,
      },
      body: JSON.stringify({ currentPassword, newPassword }),
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      return { success: false, status: res.status, error: err.message || err.error || 'Password change failed' };
    }

    return { success: true };
  } catch (e: any) {
    return { success: false, error: e.message || 'Network error' };
  }
}

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

// ── Storage keys ────────────────────────────────────────────────
const ACCESS_TOKEN_KEY = 'ce_access_token';
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
    const now = Math.floor(Date.now() / 1000);
    const delay = Math.max((expiresAt - now - 60) * 1000, 5000); // At least 5s
    refreshTimerRef.current = setTimeout(() => {
      signinSilent();
    }, delay);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Refresh token
  const signinSilent = useCallback(async (): Promise<EmbeddedUser | null> => {
    // Deduplicate concurrent refresh calls
    if (refreshPromiseRef.current) return refreshPromiseRef.current;

    refreshPromiseRef.current = (async () => {
      try {
        const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
        if (!refreshToken) return null;

        const res = await fetch('/api/proxy/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken }),
        });

        if (!res.ok) {
          clearTokens();
          setUser(null);
          return null;
        }

        const data = await res.json();
        saveTokens(data.accessToken, data.refreshToken, data.expiresIn, data.user);
        const newUser = buildUser(data.accessToken, data.expiresIn, data.user);
        setUser(newUser);
        scheduleRefresh(newUser.expires_at);
        return newUser;
      } catch {
        clearTokens();
        setUser(null);
        return null;
      } finally {
        refreshPromiseRef.current = null;
      }
    })();

    return refreshPromiseRef.current;
  }, [buildUser, scheduleRefresh]);

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
        setUser(stored);
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
      if (expiresIn < 60) {
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
      setUser(null);
      if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
      window.location.href = opts?.post_logout_redirect_uri || '/login';
    }
  }, []);

  const removeUser = useCallback(async () => {
    clearTokens();
    setUser(null);
  }, []);

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
    saveTokens(data.accessToken, data.refreshToken, data.expiresIn, data.user);
    return { success: true, user: data.user };
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
    saveTokens(data.accessToken, data.refreshToken, data.expiresIn, data.user);
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

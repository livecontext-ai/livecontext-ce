/**
 * AppDataProvider - Main provider for application data
 * Replaces the old multi-provider system
 * Manages: Auth (merged), React Query, Resource Managers
 */

'use client';

import React, { useEffect, useMemo, useCallback, ReactNode, useState, useRef, createContext, useContext } from 'react';
import { usePathname } from 'next/navigation';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuth as useOidcAuth } from 'react-oidc-context';
import { useEmbeddedAuth } from './embedded-auth-provider';
import { accessTokenAction } from '../auth/tokenFreshness';
import {
  OIDC_REFRESH_LEASE_KEY,
  OIDC_REFRESH_LOCK_NAME,
  OIDC_REFRESH_MAX_HOLD_MS,
  OIDC_REQUEST_TIMEOUT_SECONDS,
  withCrossTabLock,
} from '../auth/crossTabLock';
import { refreshWithTransientRetry } from '../auth/refreshRetry';
import { OidcUserManagerContext } from '../auth/oidcUserManager';
import type { User, UserManager } from 'oidc-client-ts';

import { useSetCurrentRoute } from '../stores/app-store';
import {
  getActiveOrgIdForRequest,
  reconcileCurrentOrgFromMemberships,
  useCurrentOrgStore,
  type CurrentOrgMembershipSnapshot,
} from '../stores/current-org-store';
// Resource Managers deprecated - use standardized hooks
// unifiedApiService import removed - all services now use apiClient internally
import { getClientLocale, toIdpUiLocale } from '../utils/locale';
import { clearDisplayTimeZone } from '../utils/timezone';
import { clearStoredAgendaTimezone } from '@/hooks/useAgendaPreferences';
import { clearSiteSessionHint } from '@/lib/auth/siteSessionHint';
import { apiClient } from '../api/api-client';
import { WebSocketProvider } from '../websocket/ws-provider';
import LoadingSpinner from '../../components/LoadingSpinner';
import PlanLimitToastListener from '../../components/PlanLimitToastListener';
import { AuthLayout } from '../../components/auth/AuthLayout';
import { SessionGate } from '../../components/auth/SessionGate';
// UnifiedApiProvider removed - replaced by React Query

// No need to import old providers - everything is managed by Resource Managers
import { IS_CE } from '@/lib/edition';
import { resetAnalytics, track } from '@/lib/analytics/analytics';
import { isPublicMarketingPath } from './publicMarketingPath';
import { safeReturnPath } from '@/lib/security/safeReturnPath';
import { stripAppLocale } from '../../components/security/appRouteAuth';

// CE deployments use embedded auth; cloud uses OIDC. Single source of truth from
// `lib/edition` (built-in build-time resolution + dual-read shim, see edition.ts).
const IS_EMBEDDED_AUTH = IS_CE;
const SSO_ORG_ID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function getConfiguredOidcStorageKey(): string {
  const realm = process.env.NEXT_PUBLIC_KEYCLOAK_REALM;
  const clientId = process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID;
  const url = process.env.NEXT_PUBLIC_KEYCLOAK_URL;
  return `oidc.user:${url}/realms/${realm}:${clientId}`;
}

export function isRemovedOidcUserStorageEvent(
  eventKey: string | null,
  oldValue: string | null,
  newValue: string | null,
  configuredOidcKey: string,
): boolean {
  if (eventKey === null) {
    return newValue === null;
  }

  const isOidcKeyChange = eventKey === configuredOidcKey
    || eventKey.startsWith('oidc.user:');
  return isOidcKeyChange && newValue === null && oldValue !== null;
}

export function hasPersistedOidcUser(
  storage: Pick<Storage, 'getItem' | 'key' | 'length'>,
  configuredOidcKey: string,
): boolean {
  try {
    if (storage.getItem(configuredOidcKey)) {
      return true;
    }

    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index);
      if (key?.startsWith('oidc.user:') && storage.getItem(key)) {
        return true;
      }
    }
  } catch {
    return true;
  }

  return false;
}

export function isPersistedOidcUserMissing(
  isEmbeddedAuth: boolean,
  oidcUser: unknown,
  storage: Pick<Storage, 'getItem' | 'key' | 'length'> | null | undefined,
  configuredOidcKey: string,
): boolean {
  return !isEmbeddedAuth
    && Boolean(oidcUser)
    && Boolean(storage)
    && !hasPersistedOidcUser(storage!, configuredOidcKey);
}

// --- Login-redirect loop breaker -------------------------------------------
// All automatic "redirect to login" paths share ONE budget so a bounce between
// them still trips the breaker:
//   - getAccessToken -> safeRedirectToLogin (401 / refresh failure)
//   - the cloud login page / FirstLoginGuard -> loginWithRedirect (no token yet)
// Without this, logging in then quickly logging out can leave a stale OIDC user
// in memory while localStorage is already cleared; the app force-expires the
// session, FirstLoginGuard sends the user to /login, the cloud login page
// auto-redirects to Keycloak, the still-live SSO cookie silently re-authenticates,
// and the cycle repeats: /app/ -> /login -> Keycloak -> /app/ forever, behind a
// permanent spinner. The breaker stops the bounce after MAX_LOGIN_REDIRECTS and
// the caller shows the Session-expired UI instead. A genuine user click ("Sign in"
// on that card) passes resetLoopGuards and starts from a clean budget.
const LOGIN_REDIRECT_LOOP_WINDOW_MS = 60_000;
const MAX_LOGIN_REDIRECTS = 3;
// Exported so the PRODUCER (app/providers.tsx `onSigninCallback`, which stamps
// auth_signin_at and clears auth_redirect_log on every Keycloak callback) and these
// CONSUMERS stay on one key definition. A rename on one side would otherwise silently
// disable the loop breaker; sharing the constant keeps producer and consumer in lockstep.
export const LOGIN_REDIRECT_LOG_KEY = 'auth_redirect_log';
export const LOGIN_SIGNIN_AT_KEY = 'auth_signin_at';
const JUST_SIGNED_IN_WINDOW_MS = 30_000;

/**
 * True when a SUCCESSFUL Keycloak signin happened within the last `windowMs`.
 * `auth_signin_at` is stamped by `onSigninCallback` (app/providers.tsx) on every
 * Keycloak redirect callback. Used as a loop signal: a redirect-to-login moments
 * after a successful signin means we signed in and were immediately force-expired,
 * so re-authenticating just repeats. Fails closed (returns false) on bad/absent
 * data so it never spuriously blocks a legitimate sign-in.
 */
export function signedInWithin(
  storage: Pick<Storage, 'getItem'> | null | undefined,
  now: number,
  windowMs: number = JUST_SIGNED_IN_WINDOW_MS,
): boolean {
  if (!storage) return false;
  try {
    const at = storage.getItem(LOGIN_SIGNIN_AT_KEY);
    if (!at) return false;
    const ts = Number(at);
    return Number.isFinite(ts) && now - ts < windowMs;
  } catch {
    return false;
  }
}

/**
 * Record an automatic redirect-to-login and report whether it may proceed.
 * Returns false once {@link MAX_LOGIN_REDIRECTS} have happened inside
 * {@link LOGIN_REDIRECT_LOOP_WINDOW_MS} - the caller MUST then stop redirecting
 * and surface the Session-expired UI. Stale entries outside the window are
 * pruned. Fails OPEN (returns true) when storage is unavailable so a storage
 * error never blocks a real sign-in.
 *
 * A refusal KEEPS the log: the next automatic redirect inside the window is refused too. Clearing
 * it handed the very next path a fresh budget (the page-load recovery was refused, the guard sent
 * the person to /login, and the login page redirected anyway). Only an explicit user sign-in
 * ({@link decideLoginRedirect} with resetLoopGuards) or a successful Keycloak callback clears it.
 */
export function recordLoginRedirect(
  storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null | undefined,
  now: number,
): boolean {
  if (!storage) return true;
  try {
    const log = JSON.parse(storage.getItem(LOGIN_REDIRECT_LOG_KEY) || '[]') as number[];
    const recent = log.filter((ts) => now - ts < LOGIN_REDIRECT_LOOP_WINDOW_MS);
    if (recent.length >= MAX_LOGIN_REDIRECTS) {
      return false;
    }
    recent.push(now);
    storage.setItem(LOGIN_REDIRECT_LOG_KEY, JSON.stringify(recent));
    return true;
  } catch {
    return true;
  }
}

function clearLoginRedirectLog(
  storage: Pick<Storage, 'removeItem'> | null | undefined,
): void {
  if (!storage) return;
  try {
    storage.removeItem(LOGIN_REDIRECT_LOG_KEY);
  } catch {
    /* ignore - storage unavailable */
  }
}

/**
 * Where an automatic redirect to the sign-in page brings the person back once signed in: the
 * page they are on (a deep link, a bookmark, an email link), or, on the login and register pages,
 * the `returnTo` they were already carrying. Keycloak sends the browser back to the redirect_uri
 * itself, so this path IS the callback page; it goes through the shared open-redirect validator
 * (only a same-origin relative path survives) and falls back to `fallback`. The fragment is
 * dropped: a redirect_uri must not carry one (OIDC core).
 */
export function loginReturnPath(
  location: Pick<Location, 'pathname' | 'search'>,
  fallback = '/app/',
): string {
  const page = stripAppLocale(location.pathname);
  if (page === '/login' || page === '/register') {
    return safeReturnPath(new URLSearchParams(location.search).get('returnTo'), fallback);
  }
  return safeReturnPath(`${location.pathname}${location.search}`, fallback);
}

export type LoginRedirectAction = 'redirect' | 'stop';

/**
 * Policy for loginWithRedirect. `resetLoopGuards` marks an EXPLICIT user action
 * (the Session-expired "Sign in" button): clear the loop budget and always
 * 'redirect'. Otherwise two loop signals can return 'stop' so the caller shows the
 * Session-expired UI instead of looping:
 *   1. A redirect-to-login moments after a SUCCESSFUL Keycloak signin
 *      ({@link signedInWithin}). This is the decisive signal for the reported loop:
 *      the silent re-auth re-stamps `auth_signin_at` every cycle, even though that
 *      same `onSigninCallback` clears `auth_redirect_log` (which would otherwise
 *      defeat signal 2).
 *   2. The 3-in-60s redirect-count circuit breaker ({@link recordLoginRedirect}),
 *      which covers loops that never reach a successful callback (e.g. a dead SSO
 *      cookie bouncing through the login form).
 * Pure: the only side effects are confined to `storage`.
 */
export function decideLoginRedirect(
  resetLoopGuards: boolean,
  storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null | undefined,
  now: number,
): LoginRedirectAction {
  if (resetLoopGuards) {
    clearLoginRedirectLog(storage);
    return 'redirect';
  }
  if (signedInWithin(storage, now)) {
    return 'stop';
  }
  return recordLoginRedirect(storage, now) ? 'redirect' : 'stop';
}

/**
 * How long the page-load recovery of an expired session gives its refresh, counted from the moment
 * this tab holds the cross-tab refresh lock. At least the lock's own hold bound: a refresh may
 * legitimately use all of it (discovery, then /token, each given the request timeout), and a
 * shorter recovery clock signed the user out while that refresh could still succeed.
 */
export const AUTO_RECOVERY_TIMEOUT_MS = OIDC_REFRESH_MAX_HOLD_MS;

/**
 * Whether the page-load recovery of an expired stored cloud session is still deciding, which the
 * provider reports as auth LOADING (spinner, `isLoading`, `isAuthChecking`).
 *
 * A browser reopened after the 15-minute access token died finds an expired stored user whose
 * refresh token may still be good: OIDC is done loading, nobody is authenticated yet, and the
 * recovery refresh is in flight. Reported as "not loading", that state let FirstLoginGuard send
 * the person to /login, whose page then started the Keycloak password form while the refresh that
 * would have kept them signed in was still running. (react-oidc-context's own signinSilent used to
 * flip its isLoading for the duration; the refresh now calls the UserManager directly, which does
 * not.) Read during render, not set by the recovery effect: children's effects (the guard's
 * redirect) run before the provider's.
 *
 * Cloud only: CE's embedded auth refreshes an expired stored session inside its own loading
 * phase. A refresh in the middle of a session never matches: the user is still authenticated
 * there (react-oidc-context only re-evaluates expiry when a user is loaded), so the app stays
 * mounted.
 */
export function isRecoveringStoredSession(state: {
  embeddedAuth: boolean;
  oidcLoading: boolean;
  authenticated: boolean;
  sessionExpired: boolean;
  storedUserExpired: boolean;
  recoverySettled: boolean;
}): boolean {
  return !state.embeddedAuth
    && !state.oidcLoading
    && !state.authenticated
    && !state.sessionExpired
    && state.storedUserExpired
    && !state.recoverySettled;
}

/**
 * One cloud token refresh, the only place the cloud session's refresh token is spent.
 *
 * Keycloak rotates refresh tokens (configure-keycloak.sh): each one is spent on use, and past its
 * one tolerated reuse Keycloak ends the session's tokens in EVERY tab. All tabs share the stored
 * session (localStorage), so tabs refreshing together (several tabs restored at once with an
 * expired token) would present the same token. The cross-tab lock makes them take turns, and
 * oidc-client-ts reads the stored refresh token when the refresh starts, so each tab presents the
 * one the previous tab stored. `onLockGranted` runs once this tab holds the lock, right before the
 * refresh starts (callers that time the refresh start their clock there).
 *
 * Bounded twice: the refresh request times out (oidc-client-ts has no timeout of its own, and
 * signinSilent() without this argument drops the configured one for the refresh-token request),
 * and the lock is released after OIDC_REFRESH_MAX_HOLD_MS even if the refresh never settles, so
 * a hung /token never keeps the other tabs waiting.
 *
 * It calls the UserManager itself, never useAuth().signinSilent: react-oidc-context turns every
 * failure of that one into a resolved null, so a refused refresh token (invalid_grant) read as
 * "no token" instead of ending the session, and a timeout was never retried. The manager rejects
 * with the real error (refreshWithTransientRetry tells a refusal from a slow network), and a
 * success still reaches the auth context through the manager's userLoaded event.
 */
export function refreshCloudSession(
  userManager: Pick<UserManager, 'signinSilent'> | null,
  onLockGranted?: () => void,
): Promise<User | null> {
  return withCrossTabLock<User | null>(OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LEASE_KEY, () => {
    onLockGranted?.();
    if (!userManager) {
      return Promise.reject(new Error('The OIDC UserManager is not available'));
    }
    return userManager.signinSilent({ silentRequestTimeoutInSeconds: OIDC_REQUEST_TIMEOUT_SECONDS });
  }, { maxHoldMs: OIDC_REFRESH_MAX_HOLD_MS });
}

/**
 * Runs `run` and rejects with `message` when it has not settled `timeoutMs` after it called
 * `started`. The clock starts at `started`, not at the call: a refresh first waits its turn for the
 * cross-tab refresh lock (another tab's refresh, a dead tab's lease), and that wait must not eat
 * the time the refresh itself is given. Until `started` is called nothing times out.
 */
export function withTimeoutFromStart<T>(
  run: (started: () => void) => Promise<T>,
  timeoutMs: number,
  message: string,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const started = () => {
      if (timer === undefined) timer = setTimeout(() => reject(new Error(message)), timeoutMs);
    };
    const settle = <V,>(done: (value: V) => void) => (value: V) => {
      clearTimeout(timer);
      done(value);
    };
    try {
      run(started).then(settle(resolve), settle(reject));
    } catch (error) {
      settle(reject)(error);
    }
  });
}

function consumeSsoRequestedOrgId(): string | null {
  if (typeof window === 'undefined') return null;

  const url = new URL(window.location.href);
  const requestedOrgId = url.searchParams.get('ssoOrg')?.trim() ?? '';
  if (!requestedOrgId) return null;

  url.searchParams.delete('ssoOrg');
  const nextSearch = url.searchParams.toString();
  const nextUrl = `${url.pathname}${nextSearch ? `?${nextSearch}` : ''}${url.hash}`;
  window.history.replaceState(window.history.state, '', nextUrl);

  return SSO_ORG_ID_PATTERN.test(requestedOrgId) ? requestedOrgId : null;
}

function applySsoRequestedOrg(memberships: CurrentOrgMembershipSnapshot[]): boolean {
  const requestedOrgId = consumeSsoRequestedOrgId();
  if (!requestedOrgId) return false;

  const membership = memberships.find(
    (candidate): candidate is CurrentOrgMembershipSnapshot & { currentUserRole: NonNullable<CurrentOrgMembershipSnapshot['currentUserRole']> } =>
      candidate.id === requestedOrgId && Boolean(candidate.currentUserRole),
  );
  if (!membership) return false;

  useCurrentOrgStore.getState().setCurrentOrg(membership.id, membership.currentUserRole);
  return true;
}

/**
 * Unified auth hook - returns the same shape regardless of OIDC or embedded mode.
 * In embedded mode, useEmbeddedAuth() provides a compatible interface.
 */
function useUnifiedAuth() {
  // Both hooks are always called (Rules of Hooks), but only one is used.
  // In OIDC mode, EmbeddedAuthProvider is not mounted, so useEmbeddedAuth() would throw.
  // We use a try-catch wrapper to handle this safely.
  if (IS_EMBEDDED_AUTH) {
    // eslint-disable-next-line react-hooks/rules-of-hooks
    return useEmbeddedAuth();
  }
  // eslint-disable-next-line react-hooks/rules-of-hooks
  return useOidcAuth();
}

interface SmartProvidersProps {
  children: ReactNode;
}

// AuthContext to expose useAppAuth() (merged with AuthProvider)
interface AuthContextType {
  isAuthenticated: boolean;
  isReady: boolean;
  user: any;
  token: string | null;
  /**
   * Internal numeric DB user id, resolved once via `/users/status` during init.
   * Matches the value the gateway injects as `X-User-ID` into backend HTTP requests
   * (AuthenticationFilter uses `userInfo.getUserId().toString()`), so anything keyed
   * by the backend-side tenant id - e.g. the `task:board:{tenantId}` WS channel, whose
   * authorizer compares against `session.userId` (also the Long) - must use this
   * value, NOT `user.sub` (Keycloak UUID).
   */
  numericUserId: number | null;
  isLoading: boolean;
  /** OIDC native loading state - true only while checking auth status, not waiting for token */
  isAuthChecking: boolean;
  /** Stored avatar URL (served from backend storage, null if none) */
  avatarUrl: string | null;
  // Auth methods exposed for convenience
  loginWithRedirect: (options?: { appState?: { returnTo?: string }; authorizationParams?: Record<string, string>; resetLoopGuards?: boolean }) => Promise<void>;
  logout: (options?: { logoutParams?: { returnTo?: string } }) => Promise<void>;
  getAccessToken: () => Promise<string>;
  // Keep backward-compatible alias
  getAccessTokenSilently: () => Promise<string>;
  /** Update the stored avatar URL (triggers re-render everywhere avatar is used) */
  updateAvatarUrl: (url: string) => void;
  // Utility methods
  requireAuth: () => boolean;
  hasRole: (role: string) => boolean;
  hasPermission: (permission: string) => boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export { AuthContext };

export function useAppAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAppAuth must be used within AppDataProvider');
  }
  return context;
}

/**
 * Non-throwing variant of {@link useAppAuth}: returns the auth context, or
 * {@code undefined} when called outside {@code AppDataProvider}. Use from a
 * hook that may render on a surface without the provider (or in a test that
 * doesn't wrap it) so it degrades gracefully instead of crashing.
 */
export function useOptionalAuth(): AuthContextType | undefined {
  return useContext(AuthContext);
}

// Re-export as useAuth for backward compatibility with existing imports
export { useAppAuth as useAuth };

/**
 * The JWT-based token provider that was active before a share context took
 * over. `apiClient` is a process-wide singleton and `SmartProviders` only
 * installs the JWT provider ONCE (guarded by initializationRef). So when a
 * share page overwrites the provider with a ShareToken and then unmounts, the
 * singleton stays stuck emitting `ShareToken …` for the rest of the
 * authenticated session - every later request is treated as a read-only shared
 * request and POSTs (e.g. `workflow-inspector/tools/batch`) get 403. We capture
 * the prior provider here so {@link clearShareApiClient} can restore it.
 */
let savedTokenProviderBeforeShare: (() => Promise<string | null>) | null = null;

/**
 * Initializes apiClient with a share token for use on public share pages.
 * Must be called before any panel components make API requests.
 * The gateway resolves ShareToken → X-User-ID (owner) transparently.
 */
export function initShareApiClient(shareToken: string): void {
  // Capture the pre-share (JWT) provider once, so leaving the share page can
  // restore normal authenticated requests instead of staying on ShareToken.
  if (savedTokenProviderBeforeShare === null) {
    const existing = apiClient.getTokenProvider();
    if (existing) savedTokenProviderBeforeShare = existing;
  }
  apiClient.setTokenProvider(() => Promise.resolve(`ShareToken ${shareToken}`));
}

/**
 * Restore the pre-share (JWT) token provider. MUST be called when a share
 * context unmounts (e.g. ShareProviders cleanup) so the singleton apiClient
 * does not keep emitting ShareToken for the rest of the authenticated session.
 * No-op when no JWT provider was captured (e.g. a pure public share session).
 */
export function clearShareApiClient(): void {
  if (savedTokenProviderBeforeShare) {
    apiClient.setTokenProvider(savedTokenProviderBeforeShare);
    savedTokenProviderBeforeShare = null;
  }
}

/**
 * Query Client configured to avoid multiple requests
 */
const createQueryClient = () => {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 5 * 60 * 1000, // 5 minutes
        gcTime: 10 * 60 * 1000, // 10 minutes (formerly cacheTime)
        retry: (failureCount, error: any) => {
          // Don't retry 4xx errors
          if (error?.status >= 400 && error?.status < 500) {
            return false;
          }
          return failureCount < 3;
        },
        retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 30000),
        // Configuration to avoid multiple requests
        refetchOnMount: false, // avoid automatic refetch
        refetchOnWindowFocus: false, // avoid refetch on focus
        refetchOnReconnect: true, // Only on network reconnection
      },
      mutations: {
        retry: 1,
      },
    },
  });
};

/**
 * Determine which providers are needed for the current route
 * Optimized: only load data needed for specific routes
 */
function getRequiredProviders(pathname: string): string[] {
  const providers: string[] = [];

  // Always include base providers
  providers.push('user');

  // Route-specific providers - granular loading
  // Only load quotas/plans for settings home page
  if (pathname === '/app/settings' || pathname.startsWith('/app/settings/')) {
    providers.push('quotas', 'plans');
  }

  // Workflows pages only need workflows data (loaded on-demand via hooks)
  if (pathname.startsWith('/app/workflows') || pathname.startsWith('/workflows')) {
    // No additional providers needed - workflows loaded via orchestratorApi hooks
  }

  // API pages only need APIs data (loaded on-demand via hooks)
  if (pathname.startsWith('/app/api') || pathname.startsWith('/api')) {
    // No additional providers needed - APIs loaded via hooks
  }

  // Catalog pages need catalog data
  if (pathname.startsWith('/catalog') || pathname.startsWith('/developers') || pathname.startsWith('/app/catalog')) {
    providers.push('catalog');
  }

  // Billing pages need subscription data
  if (pathname.startsWith('/billing') || pathname.includes('subscription') || pathname.startsWith('/app/billing')) {
    providers.push('subscriptions', 'quotas', 'plans');
  }

  return providers;
}

/**
 * Provider for Resource Managers with centralized initialization
 * Merges AuthProvider and ResourceManagerProvider
 */
const ResourceManagerProvider: React.FC<{ children: ReactNode; queryClient: QueryClient }> = ({
  children,
  queryClient,
}) => {
  const pathname = usePathname();
  // Use unified auth (OIDC or embedded depending on NEXT_PUBLIC_AUTH_MODE)
  const oidc = useUnifiedAuth();
  const oidcUserManager = useContext(OidcUserManagerContext);
  const [initializationComplete, setInitializationComplete] = useState(false);
  const [sessionExpired, setSessionExpired] = useState(false);
  const [isReady, setIsReady] = useState(false);
  const [token, setToken] = useState<string | null>(null);
  const [numericUserId, setNumericUserId] = useState<number | null>(null);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  // Roles fetched from auth-service /users/status. Single source of truth -
  // we no longer read from JWT realm_access.roles, since CE has no Keycloak
  // and Keycloak DB / auth.user_roles can drift out of sync.
  const [serverRoles, setServerRoles] = useState<string[] | null>(null);

  // Refs to avoid multiple initializations
  const initializationRef = useRef(false);
  const lastUserSubRef = useRef<string | undefined>(undefined);
  const avatarInitRef = useRef(false);

  // Deduplicate silent refresh: single in-flight promise shared by all callers
  const refreshPromiseRef = useRef<Promise<string> | null>(null);
  const refreshFailedRef = useRef(false);
  // True while the page-load recovery of an expired stored session is refreshing: the access-token
  // expired event (raised for that same expired token) must not start a second refresh then.
  const storedSessionRecoveryInFlightRef = useRef(false);

  const configuredOidcStorageKey = getConfiguredOidcStorageKey();
  const persistedOidcUserMissing = isPersistedOidcUserMissing(
    IS_EMBEDDED_AUTH,
    oidc.user,
    typeof window !== 'undefined' ? window.localStorage : null,
    configuredOidcStorageKey,
  );
  const effectiveSessionExpired = sessionExpired || persistedOidcUserMissing;

  // Derive user profile from OIDC
  const user = effectiveSessionExpired ? null : oidc.user?.profile;
  const isAuthenticated = oidc.isAuthenticated && !effectiveSessionExpired;
  const isLoading = oidc.isLoading;
  // Set once the page-load recovery of an expired stored session has decided (it never is when
  // that recovery is leaving for the login page: the redirect keeps OIDC loading until the page
  // unloads). Until then the provider reports auth as loading (isRecoveringStoredSession).
  const [storedSessionRecoverySettled, setStoredSessionRecoverySettled] = useState(false);
  const recoveringStoredSession = isRecoveringStoredSession({
    embeddedAuth: IS_EMBEDDED_AUTH,
    oidcLoading: isLoading,
    authenticated: isAuthenticated,
    sessionExpired: effectiveSessionExpired,
    storedUserExpired: oidc.user?.expired === true,
    recoverySettled: storedSessionRecoverySettled,
  });
  // What guards and pages see as "auth still loading": OIDC's own loading, or that recovery.
  const authLoading = isLoading || recoveringStoredSession;

  // Stable ref for oidc - avoids recreating getAccessToken on every render
  // (oidc from useAuth() is a new object reference each render)
  const oidcRef = useRef(oidc);
  useEffect(() => { oidcRef.current = oidc; }, [oidc]);
  const oidcUserManagerRef = useRef(oidcUserManager);
  useEffect(() => { oidcUserManagerRef.current = oidcUserManager; }, [oidcUserManager]);

  // Every silent refresh goes through here: the cloud one under the cross-tab lock, on the
  // UserManager itself (refreshCloudSession says why both). CE's embedded auth takes its own lock
  // inside its signinSilent. A refresh that fails or is cut short rejects: the caller handles it
  // like any failed refresh.
  const refreshSession = useCallback((onLockGranted?: () => void): Promise<{ access_token?: string } | null> => {
    if (IS_EMBEDDED_AUTH) return oidcRef.current.signinSilent();
    return refreshCloudSession(oidcUserManagerRef.current, onLockGranted);
  }, []);

  const markSessionExpired = useCallback(() => {
    setSessionExpired(true);
    setInitializationComplete(true);
    setIsReady(false);
    setToken(null);
    setNumericUserId(null);
    setServerRoles(null);
    setAvatarUrl(null);
    refreshFailedRef.current = true;
    initializationRef.current = false;
    avatarInitRef.current = false;
    lastUserSubRef.current = undefined;
    // The display zone goes here too, not only in `logout`. A session can end with nobody pressing
    // sign out (a missing persisted user, a failed silent renew, a redirect to login), and on a
    // shared browser the next person's first paint - and every route until their own profile lands -
    // would otherwise be drawn from the previous person's year-long LC_TZ cookie.
    clearDisplayTimeZone();
    // The agenda keeps its own copy of the zone in localStorage, which outlives the cookie.
    clearStoredAgendaTimezone();
    // And the docs subdomain's hint that someone is signed in here (lib/auth/siteSessionHint).
    clearSiteSessionHint();
  }, []);

  const expireMissingPersistedOidcUser = useCallback((reason: string) => {
    markSessionExpired();
    console.warn(reason);
    (async () => {
      try {
        await oidcRef.current.removeUser();
      } catch (err) {
        console.warn('[Auth] Missing persisted OIDC user: oidc.removeUser() threw', err);
      }
    })();
  }, [markSessionExpired]);

  // Cross-tab logout sync (LOAD-BEARING - ships to prod).
  // Since the OIDC user is now persisted in localStorage (see providers.tsx), a logout
  // in tab A removes the key - other tabs would otherwise keep rendering authed UI on
  // cached React Query data (staleTime 5 min) and only discover the issue on the next
  // backend call returning 401. This listener mirrors the removal locally so all open
  // tabs converge to the Session-expired UI immediately.
  // Note: writes (silent renew in another tab) are intentionally ignored - this tab's
  // in-memory user stays valid until *its* token expires; the next getAccessToken()
  // call will re-read storage via signinSilent() if needed.
  useEffect(() => {
    if (IS_EMBEDDED_AUTH) return;
    if (typeof window === 'undefined') return;

    const onStorage = (e: StorageEvent) => {
      // e.key === null happens on storage.clear() - broad clear, treat as logout
      if (!isRemovedOidcUserStorageEvent(e.key, e.oldValue, e.newValue, configuredOidcStorageKey)) return;
      markSessionExpired();

      console.warn('[Auth] Cross-tab logout detected - OIDC key removed in another tab');
      (async () => {
        try {
          await oidcRef.current.removeUser();
        } catch (err) {
          console.warn('[Auth] Cross-tab logout: oidc.removeUser() threw', err);
        }
        // Lock this tab so a concurrent getAccessToken() does not race signinSilent
        // against a removed user (would 400 from Keycloak → safeRedirectToLogin →
        // could trip the loop guard if N tabs react simultaneously).
        refreshFailedRef.current = true;
        // Reset per-tab loop counters so the user's explicit Sign-In click later
        // starts from a clean slate.
        try {
          sessionStorage.removeItem(LOGIN_REDIRECT_LOG_KEY);
          sessionStorage.removeItem(LOGIN_SIGNIN_AT_KEY);
        } catch { /* ignore */ }
        // No redirect here - let the `!isAuthenticated && initializationComplete`
        // path render the Session-expired UI with its Sign-in button.
      })();
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [configuredOidcStorageKey, markSessionExpired]);

  useEffect(() => {
    if (IS_EMBEDDED_AUTH || typeof window === 'undefined') return;
    if (isLoading || sessionExpired || !oidc.user || !persistedOidcUserMissing) return;

    expireMissingPersistedOidcUser('[Auth] OIDC user is loaded in memory but no persisted OIDC user exists');
  }, [expireMissingPersistedOidcUser, isLoading, oidc.user, persistedOidcUserMissing, sessionExpired]);

  useEffect(() => {
    if (IS_EMBEDDED_AUTH || typeof window === 'undefined') return;
    if (isLoading || sessionExpired || !oidc.user) return;

    const checkPersistedUser = () => {
      if (hasPersistedOidcUser(window.localStorage, configuredOidcStorageKey)) return;
      expireMissingPersistedOidcUser('[Auth] OIDC user disappeared from storage before the cross-tab listener observed it');
    };

    const intervalId = window.setInterval(checkPersistedUser, 500);
    return () => window.clearInterval(intervalId);
  }, [configuredOidcStorageKey, expireMissingPersistedOidcUser, isLoading, oidc.user, sessionExpired]);

  // Audit 2026-05-17 round-3 - cross-tab workspace-switch broadcast.
  // Listen for `lc.activeOrg` storage events and mirror them locally so a
  // workspace switch in tab A is reflected immediately in tab B/C/…
  // Decoupled from the OIDC effect above so the two storage handlers
  // run independently.
  useEffect(() => {
    if (typeof window === 'undefined') return;
    let unsubscribe: (() => void) | null = null;
    (async () => {
      const { subscribeCrossTabOrgSwitch } = await import('../stores/current-org-store');
      unsubscribe = subscribeCrossTabOrgSwitch();
    })();
    return () => {
      if (unsubscribe) unsubscribe();
    };
  }, []);

  // Centralized redirect-to-login with TWO layers of loop protection:
  //
  // Layer 1 (root cause): If we JUST signed in (within 30s), a 401 means the
  //   backend is rejecting valid tokens. Re-authenticating won't help - stop.
  //   `auth_signin_at` is set in onSigninCallback (providers.tsx).
  //
  // Layer 2 (circuit breaker): Count redirects in a 60s window via sessionStorage.
  //   If 3+ happen, something unexpected is looping - stop.
  //
  // ALL redirect-to-login paths MUST go through this function. No raw signinRedirect().
  //
  // A refusal by either layer ends the session in THIS tab only (markSessionExpired: the
  // Session-expired UI, whose explicit "Sign in" starts from a clean budget). It never removes the
  // stored user: that entry is shared by every tab (localStorage), so removing it signed out all of
  // them, e.g. after one transient 401 right after a sign-in. Marking the tab expired also keeps a
  // refused page-load recovery from settling into the guard's /login redirect, which would start
  // the very sign-in the breaker just refused.
  //
  // `sessionInvalid`: the session itself is over (the server refused the refresh token, or the
  // refresh failed with the token in hand already expired). Only then may a failed redirect remove
  // the stored user; a 401 from the API alone says nothing about the other tabs' session.
  //
  // `returnTo`: where the person lands after signing in (loginReturnPath: the current page, or the
  // login page's own returnTo), so a deep link survives the round trip through Keycloak.
  const safeRedirectToLogin = useCallback(async (
    reason: string,
    options?: { sessionInvalid?: boolean; returnTo?: string },
  ) => {
    if (refreshFailedRef.current) return;

    // Layer 1: just signed in - backend is the problem, not auth
    if (signedInWithin(typeof window !== 'undefined' ? window.sessionStorage : null, Date.now())) {
      console.error(`[Auth] 401 within 30s of successful signin (reason: ${reason}) - not redirecting (backend issue)`);
      // Show the Session-expired UI instead of a broken app shell, in this tab only.
      markSessionExpired();
      return;
    }

    // Layer 2: redirect count circuit breaker - shared with loginWithRedirect so
    // every "redirect to login" path draws from the same 3-in-60s budget.
    if (!recordLoginRedirect(typeof window !== 'undefined' ? window.sessionStorage : null, Date.now())) {
      console.error(`[Auth] Redirect loop detected (reason: ${reason}) - stopping`);
      markSessionExpired();
      return;
    }

    refreshFailedRef.current = true;
    console.warn(`[Auth] ${reason} - redirecting to login`);

    try {
      await oidcRef.current.signinRedirect({
        // Validated again here: the one place that turns the path into the host-relative URL.
        redirect_uri: `${window.location.origin}${safeReturnPath(options?.returnTo ?? loginReturnPath(window.location), '/app/')}`,
      });
    } catch {
      if (options?.sessionInvalid) {
        try { await oidcRef.current.removeUser(); } catch { /* ignore */ }
      }
      window.location.href = '/app/';
    }
  }, [markSessionExpired]);

  // Stable ref for safeRedirectToLogin - used in getAccessToken closure
  const safeRedirectToLoginRef = useRef(safeRedirectToLogin);
  useEffect(() => { safeRedirectToLoginRef.current = safeRedirectToLogin; }, [safeRedirectToLogin]);

  // Get access token - handles refresh if expired
  // Deduplicates: all concurrent callers share one signinSilent() call.
  // If refresh token is also expired (400 from Keycloak), redirect to login once.
  // Uses oidcRef so the function identity is STABLE (no [oidc] dependency).
  const getAccessToken = useCallback(async (options?: { forceRefresh?: boolean }): Promise<string> => {
    const currentOidc = oidcRef.current;

    // If refresh already failed in this tab, don't retry - redirect is in progress
    if (refreshFailedRef.current) {
      return '';
    }

    // With 15-minute access tokens the old "strictly expired" test sent tokens that died in
    // flight; accessTokenAction refreshes in the last 60 s. forceRefresh is the apiClient 401
    // path: the server refused this token, so hand back a NEW one or none, never the same one.
    const action = accessTokenAction(currentOidc.user, options?.forceRefresh === true);
    if (action === 'none') {
      return '';
    }
    if (action === 'cached') {
      return currentOidc.user?.access_token || '';
    }

    // Near expiry, expired or refused - deduplicate the refresh call (within this tab).
    // A slow network is not a dead session: a timeout or network error is retried once, then the
    // token in hand is kept while still valid; only a refusal by the server (invalid_grant, 4xx)
    // or a failed retry with an expired token signs the user out (lib/auth/refreshRetry.ts).
    if (!refreshPromiseRef.current) {
      const forceRefresh = options?.forceRefresh === true;
      refreshPromiseRef.current = (async () => {
        try {
          const decision = await refreshWithTransientRetry({
            refresh: () => refreshSession(),
            current: () => {
              const user = oidcRef.current.user;
              return user?.access_token ? { token: user.access_token, expired: user.expired === true } : null;
            },
            forceRefresh,
          });
          if (decision.kind === 'redirect') {
            // Use safeRedirectToLogin via ref to avoid stale closure
            await safeRedirectToLoginRef.current(decision.reason, { sessionInvalid: true });
            return '';
          }
          return decision.token;
        } finally {
          refreshPromiseRef.current = null;
        }
      })();
    }

    return refreshPromiseRef.current;
  }, [refreshSession]); // Stable - always reads latest oidc via oidcRef (refreshSession is stable too)

  // Fetch avatar binary with auth and create a blob URL for <img> tags
  const fetchAvatarAsBlob = useCallback(async (userId: string | number, accessToken: string): Promise<string | null> => {
    try {
      const res = await fetch(`/api/proxy/users/${userId}/avatar`, {
        headers: { 'Authorization': `Bearer ${accessToken}` },
      });
      if (!res.ok) return null;
      const blob = await res.blob();
      return URL.createObjectURL(blob);
    } catch {
      return null;
    }
  }, []);

  // Initialize stored avatar using status data already fetched during init
  // Avoids a duplicate /users/status request
  const initializeStoredAvatar = useCallback(async (accessToken: string, userStatus: { userId: number; avatarUrl?: string | null }, oidcPicture?: string) => {
    try {
      const userId = userStatus.userId;

      if (userStatus.avatarUrl) {
        // User already has a stored avatar - fetch as blob to avoid <img> auth issues
        const blobUrl = await fetchAvatarAsBlob(userId, accessToken);
        if (blobUrl) setAvatarUrl(blobUrl);
        return;
      }

      // No stored avatar - ask backend to import from OAuth provider
      try {
        const importRes = await fetch('/api/proxy/users/avatar/import-provider', {
          method: 'POST',
          headers: { 'Authorization': `Bearer ${accessToken}` },
        });

        if (importRes.ok) {
          const result = await importRes.json();
          if (result.imported) {
            const blobUrl = await fetchAvatarAsBlob(userId, accessToken);
            if (blobUrl) setAvatarUrl(blobUrl);
          }
        }
      } catch {
        // Silent fail - avatar will show fallback icon
      }
    } catch {
      // Silent fail - avatar will show fallback icon
    }
  }, [fetchAvatarAsBlob]);

  // Stable ref for getAccessToken - used in tokenProvider closure to avoid stale captures
  const getAccessTokenRef = useRef(getAccessToken);
  useEffect(() => { getAccessTokenRef.current = getAccessToken; }, [getAccessToken]);

  // Proactive token refresh when tab becomes visible again
  // Browser throttles timers in background tabs, so the access-token expired event may not fire.
  // This ensures the token is refreshed as soon as the user returns.
  useEffect(() => {
    if (!isAuthenticated) return;

    const handleVisibilityChange = async () => {
      if (document.visibilityState !== 'visible') return;
      const currentOidc = oidcRef.current;
      if (!currentOidc.user || refreshFailedRef.current) return;

      // Token expired or expiring within 60s - refresh now
      const expiresIn = (currentOidc.user.expires_at ?? 0) - Math.floor(Date.now() / 1000);
      if (expiresIn < 60) {
        try {
          await refreshSession();
        } catch {
          // signinSilent failed - getAccessToken will handle redirect on next API call
        }
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange);
  }, [isAuthenticated, refreshSession]);

  // Refresh once when the access token expires. There is no silent-renew-error handler: the
  // UserManager runs with automaticSilentRenew off (app/providers.tsx), so it never raises that
  // event; a failed refresh is handled where a token is next needed (getAccessToken).
  useEffect(() => {
    if (!oidc.events) return;

    const handleAccessTokenExpired = async () => {
      // During the page-load recovery the event fires for the very token that recovery is
      // refreshing: a second refresh would only wait for the lock and spend one more refresh token.
      if (refreshFailedRef.current || storedSessionRecoveryInFlightRef.current) return;
      try {
        await refreshSession();
      } catch {
        // getAccessToken handles a failed refresh on the next API call
      }
    };

    return oidc.events.addAccessTokenExpired(handleAccessTokenExpired);
  }, [oidc.events, refreshSession]);

  // Auto-recover expired sessions: when OIDC finishes loading and finds an expired
  // user (access token expired but refresh token may still be valid), attempt a
  // silent refresh ONCE. This avoids forcing a full redirect to Keycloak when the
  // user already has a valid session cookie / refresh token.
  const autoRecoverAttemptedRef = useRef(false);
  useEffect(() => {
    if (isLoading || isAuthenticated || autoRecoverAttemptedRef.current) return;

    // oidc.user exists but expired → refresh token may still work
    const oidcUser = oidcRef.current.user;
    if (!oidcUser || !oidcUser.expired) return;

    autoRecoverAttemptedRef.current = true;
    storedSessionRecoveryInFlightRef.current = true;

    (async () => {
      // Same rule as getAccessToken: the server refusing the refresh token ends the session at
      // once, a slow network gets one retry first (the token in hand has expired, so a second
      // failure ends it too).
      const decision = await refreshWithTransientRetry({
        // The timeout covers the refresh, not the wait for the cross-tab lock (several tabs
        // restored at once take turns), and is never shorter than the lock's own hold bound.
        // CE's signinSilent takes its own lock out of sight, so its clock starts at once.
        refresh: () => withTimeoutFromStart((started) => {
          if (IS_EMBEDDED_AUTH) started();
          return refreshSession(started);
        }, AUTO_RECOVERY_TIMEOUT_MS, 'signinSilent timeout'),
        current: () => {
          const user = oidcRef.current.user;
          return user?.access_token ? { token: user.access_token, expired: user.expired === true } : null;
        },
        forceRefresh: false,
      });
      // The refresh is over either way: the access-token expired event may refresh again from here.
      storedSessionRecoveryInFlightRef.current = false;
      // A redirect to the login page that starts never settles (oidc-client-ts resolves it only
      // when the page comes back), and OIDC reports loading meanwhile, so the spinner stays while
      // the browser leaves and no guard starts a second sign-in. One refused by the loop guards
      // marks the session expired (the Session-expired UI); one that failed settles, and the
      // guards take it from there. Signing in again brings the person back to this page (a deep
      // link, or the login page's own returnTo), not to the app's home.
      if (decision.kind === 'redirect') {
        await safeRedirectToLoginRef.current(`Auto-recovery failed: ${decision.reason}`, {
          sessionInvalid: true,
          returnTo: loginReturnPath(window.location),
        });
        setStoredSessionRecoverySettled(true);
        return;
      }
      if (decision.token) {
        console.log('[Auth] Auto-recovered expired session via silent refresh');
      }
      // On success, oidc state updates → isAuthenticated becomes true → normal init runs (the
      // manager stored the user and raised userLoaded before this refresh resolved). Without a
      // token the person is still signed out, and the guards send them to sign in.
      setStoredSessionRecoverySettled(true);
    })();
  }, [isLoading, isAuthenticated, refreshSession]);

  // Centralized initialization of all data (fusion AuthProvider + ResourceManagerProvider)
  useEffect(() => {
    // If not authenticated or loading, reset
    if (!isAuthenticated || isLoading || !user?.sub) {
      if (isReady || token) {
        setIsReady(false);
        setToken(null);
        setAvatarUrl(null);
        setServerRoles(null);
        initializationRef.current = false;
        avatarInitRef.current = false;
        lastUserSubRef.current = undefined;
        // Keep initializationComplete=true when auth failed after init
        // (loop detected / session expired) so the error UI renders.
        // Only reset if we never finished init (e.g., user switch).
        if (!refreshFailedRef.current) {
          setInitializationComplete(false);
        }
      }
      return;
    }

    // If user hasn't changed and we're already ready, do nothing
    if (lastUserSubRef.current === user.sub && isReady && token && initializationComplete) {
      return;
    }

    // If already initializing for this user, do nothing
    if (initializationRef.current && lastUserSubRef.current === user.sub) {
      return;
    }

    // New user or first initialization
    if (lastUserSubRef.current !== user.sub) {
      initializationRef.current = false;
      avatarInitRef.current = false;
      autoRecoverAttemptedRef.current = false;
      lastUserSubRef.current = user.sub;
    }

    // Initialize once
    if (!initializationRef.current) {
      initializationRef.current = true;

      const initializeAuth = async () => {
        try {
          // Get the token
          const accessToken = await getAccessToken();

          if (accessToken) {
            // Configure token provider for API services
            // Uses getAccessTokenRef to always call the latest version (avoids stale closure)
            // forceRefresh comes from apiClient after a 401: never answer with the refused token.
            const tokenProvider = async (options?: { forceRefresh?: boolean }) => {
              if (refreshFailedRef.current) return null;
              try {
                const token = await getAccessTokenRef.current(options);
                return token || null;
              } catch (error) {
                return null;
              }
            };

            // apiClient is the SINGLE source of truth for auth
            apiClient.setTokenProvider(tokenProvider);

            // Auto-logout on unrecoverable 401 (e.g. Keycloak restart invalidated all tokens)
            // Routes through safeRedirectToLogin for loop protection.
            apiClient.setOnAuthFailure(() => {
              safeRedirectToLoginRef.current('Unrecoverable 401 from apiClient');
            });

            // PR0.5c: bridge the active-org Zustand store with apiClient
            // (apiClient lives outside the React tree, store is read by ref).
            // Every request now sends X-Active-Organization-ID; gateway
            // validates against memberships and injects X-Organization-ID
            // downstream from the matched membership.
            apiClient.setActiveOrgProvider(getActiveOrgIdForRequest);

            // Hydrate and validate useCurrentOrgStore from the user's fresh
            // membership list on first login. The persisted store can contain
            // a prior user's workspace, a removed membership, or an old role.
            // Gateway validation also protects backend services, but the UI
            // must be reconciled so it shows the same workspace that requests
            // are actually scoped to.
            const hydrateCurrentOrg = async () => {
              try {
                const memberships = await apiClient.get<CurrentOrgMembershipSnapshot[]>(
                  '/organizations/me',
                ).catch(() => null);

                if (Array.isArray(memberships) && memberships.length > 0) {
                  if (applySsoRequestedOrg(memberships)) {
                    return;
                  }
                  reconcileCurrentOrgFromMemberships(memberships);
                  return;
                }
                const current = await apiClient.get<CurrentOrgMembershipSnapshot>(
                  '/organizations/current',
                ).catch(() => null);
                reconcileCurrentOrgFromMemberships(current ? [current] : [], current);
              } catch {
                // Best-effort - store will just stay empty, apiClient sends
                // no X-Active-Organization-ID, gateway falls back to default.
              }
            };

            // Parallel prefetch: onboarding status + user status + active-org hydration
            // Saves ~100-300ms vs sequential awaits
            const needsAvatarInit = !avatarInitRef.current;
            if (needsAvatarInit) avatarInitRef.current = true;

            const [onboardingStatus, userStatus] = await Promise.all([
              apiClient.get('/auth-service/api/onboarding/status').catch(() => null),
              needsAvatarInit
                ? apiClient.get<{ userId: number; avatarUrl?: string | null; roles?: string[] }>('/users/status').catch(() => null)
                : Promise.resolve(null),
              hydrateCurrentOrg(),
            ]);

            // Populate onboarding cache so FirstLoginGuard doesn't re-fetch
            if (onboardingStatus) {
              queryClient.setQueryData(['user', 'onboarding-status', user?.sub], onboardingStatus);
            }

            // If auth failed during the parallel fetch (e.g. 401 triggered onAuthFailure
            // → safeRedirectToLogin, which redirects or marks the session expired), don't
            // mark as ready - the error UI will render once isAuthenticated flips to false.
            if (refreshFailedRef.current) {
              setInitializationComplete(true);
              return;
            }

            // Set roles from user status (or fall back to empty array)
            if (needsAvatarInit) {
              if (userStatus) {
                setServerRoles(Array.isArray(userStatus.roles) ? userStatus.roles : []);
                if (typeof userStatus.userId === 'number') {
                  setNumericUserId(userStatus.userId);
                }
                // Avatar init is fire-and-forget - don't block isReady on cosmetic fetch
                initializeStoredAvatar(accessToken, userStatus, user?.picture);
              } else {
                setServerRoles([]);
              }
            }

            // Batch terminal state updates to minimize re-renders
            setToken(accessToken);
            setIsReady(true);
            setInitializationComplete(true);
          } else {
            setIsReady(false);
            setToken(null);
            setInitializationComplete(false);
          }
        } catch (error) {
          console.error('[ResourceManagerProvider] Authentication initialization failed:', error);
          setIsReady(false);
          setToken(null);
          setInitializationComplete(true); // Continue even on error
        }
      };

      initializeAuth();
    }
  // Only external triggers - isReady/token/initializationComplete are outputs, not inputs
  // getAccessToken is now stable (empty deps) so no need in dep array
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated, isLoading, user?.sub, initializeStoredAvatar]);

  // Login/Logout wrappers
  const loginWithRedirect = useCallback(async (opts?: { appState?: { returnTo?: string }; authorizationParams?: Record<string, string>; resetLoopGuards?: boolean }) => {
    // `resetLoopGuards` marks an EXPLICIT user action (the Session-expired "Sign in"
    // button): start from a clean loop budget. Every other caller is an AUTOMATIC
    // redirect (cloud login page, FirstLoginGuard, recovery buttons) and must respect
    // the shared circuit breaker - otherwise a logout-race that keeps re-authenticating
    // against a live Keycloak SSO cookie loops forever behind a spinner.
    const sessionStore = typeof window !== 'undefined' ? window.sessionStorage : null;
    if (decideLoginRedirect(Boolean(opts?.resetLoopGuards), sessionStore, Date.now()) === 'stop') {
      console.error('[Auth] Automatic sign-in redirect loop detected - showing the session-expired UI instead of redirecting again');
      markSessionExpired();
      return;
    }
    if (opts?.resetLoopGuards) {
      setSessionExpired(false);
      refreshFailedRef.current = false;
    }
    await oidc.signinRedirect({
      // The return path is appended to the origin, so it must be a same-origin relative path:
      // `@evil.example` or `.evil.example` would otherwise extend the HOST (open-redirect guard).
      redirect_uri: `${window.location.origin}${safeReturnPath(opts?.appState?.returnTo, '/app/')}`,
      // `ui_locales` (OIDC core) asks the identity provider to render its pages in the language
      // the app is being read in. It is what the login and password-reset forms have to go on
      // BEFORE there is an account: the person's stored language reaches Keycloak as a user
      // attribute, which by definition does not exist yet on a first sign-in or a sign-up. An
      // explicit caller parameter still wins, so nothing here overrides a deliberate one.
      extraQueryParams: { ui_locales: toIdpUiLocale(getClientLocale()), ...opts?.authorizationParams },
    });
  }, [oidc, markSessionExpired]);

  const logout = useCallback(async (opts?: { logoutParams?: { returnTo?: string } }) => {
    // Count the deliberate logout while the identity is still attached, then
    // clear it so post-logout events are not attributed to the previous user
    // (both no-ops when analytics is off).
    track('auth_logged_out', {});
    resetAnalytics();
    // Deliberate logout: clear loop-detection state so the post-logout redirect to the
    // Keycloak login form is never mistaken for the login/logout redirect loop. (A real
    // loop re-stamps auth_signin_at via onSigninCallback on each silent re-auth, so the
    // breaker still fires there.)
    try {
      sessionStorage.removeItem(LOGIN_SIGNIN_AT_KEY);
      sessionStorage.removeItem(LOGIN_REDIRECT_LOG_KEY);
    } catch { /* ignore - storage unavailable */ }
    // The display zone belongs to the ACCOUNT, but its cookie mirror lives a year on the BROWSER.
    // Leaving it behind hands the next person to sign in here the previous one's zone for their
    // whole first session. `useDisplayPreferences` also overrides it as soon as a profile lands,
    // so this is the second of two guards, not the only one.
    clearDisplayTimeZone();
    // And the agenda's copy, which lives in localStorage and outlives the cookie. This call was
    // missing here while the expiry path above had it TWICE, and the duplicate is what hid it: the
    // test that was supposed to keep the two paths in step counted occurrences across the whole
    // file, so two-in-one-place read the same as one-in-each. Sign out is the path people take.
    clearStoredAgendaTimezone();
    // The docs header would otherwise keep showing the account (lib/auth/siteSessionHint).
    clearSiteSessionHint();
    await oidc.signoutRedirect({
      post_logout_redirect_uri: opts?.logoutParams?.returnTo || `${window.location.origin}/app/`,
    });
  }, [oidc]);

  // Utility methods
  const requireAuth = useCallback((): boolean => {
    if (!isAuthenticated && !authLoading) {
      loginWithRedirect();
      return false;
    }
    return true;
  }, [isAuthenticated, authLoading, loginWithRedirect]);

  const hasRole = useCallback((role: string): boolean => {
    // Single source of truth = auth-service /users/status. JWT claims (Keycloak
    // realm_access or embedded) are intentionally ignored - they can drift and
    // CE has no Keycloak at all. While serverRoles is loading we conservatively
    // return false so admin UI is not flashed before the check completes.
    if (serverRoles === null) return false;
    return serverRoles.includes(role);
  }, [serverRoles]);

  const hasPermission = useCallback((permission: string): boolean => {
    if (!oidc.user?.profile) return false;
    const resourceAccess = (oidc.user.profile as any)?.resource_access;
    const permissions = resourceAccess?.['livecontext-frontend']?.roles || [];
    return permissions.includes(permission);
  }, [oidc.user?.profile]);

  // Expose Auth context (merged with AuthProvider)
  // isLoading must be true while OIDC checks auth OR while token isn't ready
  // isAuthChecking is only the auth check itself (for faster UI rendering): OIDC's own loading,
  // plus the page-load recovery of an expired stored session (isRecoveringStoredSession)
  const updateAvatarUrl = useCallback((url: string) => setAvatarUrl(url), []);

  const authValue = useMemo<AuthContextType>(() => ({
    isAuthenticated,
    isReady,
    user,
    token,
    numericUserId,
    isLoading: authLoading || (isAuthenticated && !isReady),
    isAuthChecking: authLoading,
    avatarUrl,
    updateAvatarUrl,
    // Auth methods
    loginWithRedirect,
    logout,
    getAccessToken,
    getAccessTokenSilently: getAccessToken, // backward-compatible alias
    // Utility methods
    requireAuth,
    hasRole,
    hasPermission,
  }), [
    isAuthenticated, isReady, user, token, numericUserId, authLoading, avatarUrl,
    updateAvatarUrl, loginWithRedirect, logout, getAccessToken,
    requireAuth, hasRole, hasPermission,
  ]);

  // Session expired / loop detected / not signed in: show a clear gate instead of a
  // broken app shell. "Sign in" passes resetLoopGuards so this explicit retry starts
  // from a clean loop budget (an automatic redirect would instead respect the
  // circuit breaker).
  // Public marketing/docs pages are never replaced by the blocking auth UI:
  // they must server-render their real content (see isPublicMarketingPath).
  // The host matters on the docs subdomain (clean paths, see isPublicMarketingPath).
  // Server and client agree: the server render sees the `/docs/...` route (public by
  // prefix), the browser sees the docs host (public by host).
  const publicMarketingPage = isPublicMarketingPath(
    pathname,
    typeof window === 'undefined' ? null : window.location.host,
  );

  if (!publicMarketingPage && (effectiveSessionExpired || (!isAuthenticated && initializationComplete))) {
    // `effectiveSessionExpired` is the ONLY signal that a previously-valid session
    // ended (cross-tab logout, the persisted OIDC user vanished, or a
    // login-redirect loop guard refused an automatic redirect) - so it alone drives
    // the "session expired" wording. The other path that opens this gate,
    // `!isAuthenticated && initializationComplete`, fires for a cold first visit
    // (fresh e2e slot / CE with no prior login), which is not an expiry, so
    // SessionGate shows the neutral "sign in to continue" copy there instead of
    // falsely claiming a session expired.
    return (
      <AuthContext.Provider value={authValue}>
        <div className="fixed inset-0 z-[9999]">
          <AuthLayout>
            <SessionGate
              sessionExpired={effectiveSessionExpired}
              onSignIn={() => loginWithRedirect({
                resetLoopGuards: true,
                // Back to this page (or the login page's own returnTo) once signed in.
                appState: { returnTo: loginReturnPath(window.location) },
              })}
            />
          </AuthLayout>
        </div>
      </AuthContext.Provider>
    );
  }

  // Show a full-screen spinner while OIDC is loading (e.g. redirect back from Keycloak) or while
  // an expired stored session is being recovered (the app stays unmounted until it is decided)
  if (!publicMarketingPage && authLoading) {
    return (
      <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-[var(--bg-primary)]">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  return (
    <AuthContext.Provider value={authValue}>
      {children}
    </AuthContext.Provider>
  );
};

/**
 * Provider for global hooks initialization
 * Replaces all old providers with Resource Manager logic
 */
const GlobalHooksProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  // Can add any global initialization logic here if needed
  // For now, Resource Managers handle everything

  return <>{children}</>;
};

/**
 * AppDataProvider - Main provider for application data
 * Replaces the old multi-provider system
 * Manages: Auth, React Query, Resource Managers
 */
export const AppDataProvider: React.FC<SmartProvidersProps> = ({ children }) => {
  const pathname = usePathname();
  const setCurrentRoute = useSetCurrentRoute();

  // QueryClient with optimized configuration
  const queryClient = useMemo(() => createQueryClient(), []);

  // Workspace-switch react-query invalidation - same predicate for cross-tab
  // (storage event) and same-tab (Zustand subscribe with selector). Skip keys
  // already prefixed with `['org', ...]` - those are managed by
  // {@link useOrgScopedQuery}, segregated per-workspace by construction, so
  // re-fetching them on switch defeats the cache-isolation benefit (the hot
  // org B keys stay fresh, and switching back to A hits A's cached slice
  // instead of round-tripping). Non-prefixed keys (~50 legacy hooks) still
  // get the blanket invalidate so they refetch on switch.
  const invalidateNonOrgScopedQueries = () => {
    queryClient.invalidateQueries({
      predicate: (q) => !(Array.isArray(q.queryKey) && q.queryKey[0] === 'org'),
    });
  };

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const handler = (e: StorageEvent) => {
      if (e.key !== 'lc.activeOrg' || e.newValue === e.oldValue) return;
      invalidateNonOrgScopedQueries();
    };
    window.addEventListener('storage', handler);
    return () => window.removeEventListener('storage', handler);
  }, [queryClient]);

  // F20 - same-tab switch. `subscribeWithSelector` (store middleware) calls
  // the listener only when the selected slice (`currentOrgId`) changes - no
  // need to track `prevOrgId` manually, no spurious fire on unrelated state
  // mutations (role refresh, etc.), and StrictMode-safe (the listener is
  // unsubscribed on unmount and re-bound to the live store on re-mount).
  useEffect(() => {
    return useCurrentOrgStore.subscribe(
      (s) => s.currentOrgId,
      invalidateNonOrgScopedQueries,
    );
  }, [queryClient]);

  // Determine required providers
  const requiredProviders = useMemo(() => getRequiredProviders(pathname), [pathname]);

  // Update route in store
  useEffect(() => {
    setCurrentRoute(pathname);
  }, [pathname, setCurrentRoute]);

  // Debug: log active providers
  useEffect(() => {
  }, [pathname, requiredProviders]);

  return (
    <QueryClientProvider client={queryClient}>
      <ResourceManagerProvider queryClient={queryClient}>
        <WebSocketProvider>
          <GlobalHooksProvider>
            {children}
            <PlanLimitToastListener />
          </GlobalHooksProvider>
        </WebSocketProvider>
      </ResourceManagerProvider>
    </QueryClientProvider>
  );
};

/**
 * Hook to check if a provider is active
 */
export function useIsProviderActive(providerName: string): boolean {
  const pathname = usePathname();
  const requiredProviders = useMemo(() => getRequiredProviders(pathname), [pathname]);
  return requiredProviders.includes(providerName);
}

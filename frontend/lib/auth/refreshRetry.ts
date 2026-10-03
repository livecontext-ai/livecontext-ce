import { CrossTabLockHoldTimeoutError } from './crossTabLock';

/**
 * A silent refresh that failed is not always a dead session. The refresh request has a 10 s
 * timeout (OIDC_REQUEST_TIMEOUT_SECONDS) and the cross-tab lock a hold bound, so a slow network
 * now rejects where it used to wait: sending every rejection to the login page signed out users
 * whose session was fine. Only the authorization server saying no (invalid_grant and the other
 * OAuth errors, a 4xx) ends the session at once. A timeout or a network error is retried once;
 * if that fails too, the token in hand is kept while it is still valid, and the session ends
 * only when it has actually expired.
 *
 * One retry, not more: Keycloak rotates refresh tokens and tolerates ONE reuse. A refresh that
 * timed out on our side may already have rotated the token on the server, and the retry may
 * present the old one again, which is exactly the tolerated reuse.
 */

/** Pause before the single retry. */
export const REFRESH_RETRY_BACKOFF_MS = 1_000;

/** OAuth error codes that say "try again later", not "this session is over". */
const TRANSIENT_OAUTH_ERRORS = new Set(['temporarily_unavailable', 'server_error']);

/** HTTP status embedded in oidc-client-ts's generic errors: "Bad Gateway (502)". */
const HTTP_STATUS_IN_MESSAGE = /\((\d{3})\)/;

/**
 * False only when the authorization server refused the refresh: an OAuth error such as
 * invalid_grant (oidc-client-ts ErrorResponse), or a 4xx other than 408 / 429. Everything else
 * says nothing about the session itself and is transient: a timeout (the request's, or the
 * cross-tab lock hold bound), a network failure, a 5xx, an OAuth error the server marks
 * temporary, or a gateway's HTML error page ("Invalid response Content-Type").
 */
export function isTransientRefreshError(error: unknown): boolean {
  if (error instanceof CrossTabLockHoldTimeoutError) return true;
  if (!(error instanceof Error)) return true;
  // oidc-client-ts marks its classes by name (instanceof breaks across bundles).
  if (error.name === 'ErrorResponse') {
    const code = (error as Error & { error?: string | null }).error;
    return typeof code === 'string' && TRANSIENT_OAUTH_ERRORS.has(code);
  }
  const status = HTTP_STATUS_IN_MESSAGE.exec(error.message);
  if (status) {
    const code = Number(status[1]);
    if (code >= 400 && code < 500) return code === 408 || code === 429;
  }
  return true;
}

/** What getAccessToken does after a refresh: hand a token over (possibly empty), or sign out. */
export type RefreshDecision =
  | { kind: 'token'; token: string }
  | { kind: 'redirect'; reason: string };

export interface RefreshWithRetryOptions {
  /** One refresh (the provider's refreshSession, which takes the cross-tab lock). */
  refresh: () => Promise<{ access_token?: string } | null | undefined>;
  /** The token in hand now, and whether it has expired; null when there is none. */
  current: () => { token: string; expired: boolean } | null;
  /** The server refused the current token (apiClient 401): never hand the same one back. */
  forceRefresh: boolean;
  sleep?: (ms: number) => Promise<void>;
  backoffMs?: number;
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Refreshes, retrying a transient failure once (see the module comment). Never throws.
 */
export async function refreshWithTransientRetry(options: RefreshWithRetryOptions): Promise<RefreshDecision> {
  const sleep = options.sleep ?? defaultSleep;
  let lastError: unknown;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    if (attempt > 0) await sleep(options.backoffMs ?? REFRESH_RETRY_BACKOFF_MS);
    try {
      const refreshed = await options.refresh();
      return { kind: 'token', token: refreshed?.access_token || '' };
    } catch (error) {
      lastError = error;
      if (!isTransientRefreshError(error)) {
        return { kind: 'redirect', reason: 'Silent refresh refused, session expired' };
      }
    }
  }
  const current = options.current();
  if (current && !current.expired) {
    console.warn('[Auth] Silent refresh failed twice on a slow or unreachable network; keeping the current token',
      lastError);
    // A token the server already refused is not handed back; the caller simply has none for now.
    return { kind: 'token', token: options.forceRefresh ? '' : current.token };
  }
  return { kind: 'redirect', reason: 'Silent refresh failed twice and the session token has expired' };
}

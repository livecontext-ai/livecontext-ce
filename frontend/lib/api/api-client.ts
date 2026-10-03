/**
 * API Client - Centralized HTTP client for all backend requests
 *
 * This is the single source of truth for making API requests.
 * All requests go through the Next.js proxy which routes to the Spring Gateway.
 * The Gateway handles authentication headers (X-User-ID, X-Tenant-ID, etc.)
 *
 * Usage:
 *   import { apiClient } from '@/lib/api/api-client';
 *
 *   // Initialize once with OIDC token provider
 *   apiClient.setTokenProvider(getAccessTokenSilently);
 *
 *   // Make requests
 *   const data = await apiClient.get('/workflows');
 *   const result = await apiClient.post('/data-sources', { name: 'test' });
 */

import { track } from '@/lib/analytics/analytics';

export interface ApiClientConfig {
  baseUrl?: string;
  timeout?: number;
  retries?: number;
}

/**
 * Phase G (archi-refoundation 2026-05-04) - sentinel returned by `apiClient.get<T>()`
 * when the server responds 304 Not Modified to a conditional request (If-None-Match).
 *
 * <p>Callers that send ETag/If-None-Match must check `result === NOT_MODIFIED` and
 * respond by keeping their previous value. Comparing with `===` is type-safe (the
 * sentinel is a `Symbol` exposed via `unique symbol` typing).
 *
 * <p>Today the only producer is `WorkflowRunController.getRunState` (Phase G
 * backend ETag composite seq+full+md5). Other endpoints return normal data.
 */
export const NOT_MODIFIED: unique symbol = Symbol('NOT_MODIFIED');
export type NotModified = typeof NOT_MODIFIED;

export interface RequestOptions {
  params?: Record<string, string | number | boolean | undefined>;
  headers?: Record<string, string>;
  timeout?: number;
  retries?: number;
  signal?: AbortSignal;
  /** Skip auth token (for public endpoints). Avoids waiting for token provider. */
  skipAuth?: boolean;
  /**
   * Optional auth: send the token WHEN available, but fall back to an anonymous request
   * (no throw) when there is none - instead of failing with NO_TOKEN like a normal call.
   * For routes that are public yet personalise the response for a logged-in caller (e.g. the
   * marketplace, where the gateway injects the active workspace so the server can mark apps
   * "Installed"). Mirrors the gateway's optional-auth on the same routes.
   */
  optionalAuth?: boolean;
}

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public code?: string,
    public details?: any
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

const UUID_SEGMENT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Reduces a request path to a bounded shape for analytics: the query string is
 * dropped and every UUID or all-digit segment becomes `:id`, so the emitted
 * value never carries a resource identifier.
 */
export function normalizeApiPath(url: string): string {
  const path = url.split(/[?#]/, 1)[0];
  // Ids are masked anywhere; past the first two static segments only plain
  // route words (lowercase letters, digits, hyphens) survive, so a name-bearing
  // segment (a credential name, a file name, a table slug) is masked too.
  const ROUTE_WORD = /^[a-z0-9-]+$/;
  let seen = 0;
  return path
    .split('/')
    .map((segment) => {
      if (segment === '') return segment;
      seen += 1;
      if (UUID_SEGMENT.test(segment) || /^\d+$/.test(segment)) return ':id';
      if (seen > 2 && !ROUTE_WORD.test(segment)) return ':id';
      return segment;
    })
    .join('/');
}

/**
 * Counts a request that failed for good (after every retry). Wrapped so that
 * analytics can never mask the original error.
 */
function reportRequestFailure(error: unknown, method: string, path: string): void {
  try {
    if (!(error instanceof ApiError)) return;
    // 401s arrive in bursts (pre-bootstrap, deactivated account) and are
    // session state, not product friction: they would only drown the signal.
    if (error.status === 401) return;
    track('api_request_failed', {
      status: error.status,
      error_code: error.code ?? null,
      method,
      path: normalizeApiPath(path),
    });
  } catch {
    // Analytics must never interfere with the request outcome.
  }
}

/**
 * Fired on `window` on EVERY call refused because the account is deactivated (such an account has
 * every call refused, so this arrives in bursts). AccountRestoreModal listens for it and offers to
 * cancel the pending deletion.
 */
export const ACCOUNT_INACTIVE_EVENT = 'account-inactive';

/**
 * Latch for the same signal, because the event alone loses the race that matters. On /app/* the
 * first blocked call is issued by FirstLoginGuard, which renders only a spinner until it resolves,
 * so the modal is not mounted yet and the event reaches no listener. If nothing else happens to be
 * refused afterwards (cached queries, `enabled:false` hooks), the person is left in an app where
 * nothing loads and no path leads anywhere, which is the exact state the modal exists to prevent.
 * A listener mounting later reads this instead of waiting for the next event.
 */
let accountInactiveSeen = false;

export function hasBlockedCallBeenSeen(): boolean {
  return accountInactiveSeen;
}

/** Cleared once the account is usable again, so a later session starts from a clean slate. */
export function clearBlockedCallLatch(): void {
  accountInactiveSeen = false;
}

/**
 * The gateway rejects a deactivated account with HTTP 429 and message "Inactive account",
 * reusing its quota response. Matching on the message is what separates it from a real
 * rate limit, which must still be retried. Kept next to ApiError so both the response
 * handler and the retry loop read the same definition.
 */
function isInactiveAccountResponse(status: number, details: any): boolean {
  return status === 429 && details?.message === 'Inactive account';
}

/**
 * True for the error apiClient throws when the gateway refused a call because the account is
 * deactivated. Exported so every retry policy in the app reads the same definition: React Query
 * wraps apiClient calls and applies its OWN ladder on 429, so without this the "fails on the
 * first response" behaviour would only hold for direct callers.
 */
export function isInactiveAccountError(error: unknown): boolean {
  return error instanceof ApiError && isInactiveAccountResponse(error.status, error.details);
}

/**
 * Returns the current access token. `forceRefresh` asks for a NEW token (used after a 401);
 * without it the provider may return a cached token that is not about to expire.
 */
export type TokenProvider = (options?: { forceRefresh?: boolean }) => Promise<string | null>;

class ApiClient {
  private baseUrl: string;
  private timeout: number;
  private retries: number;
  private tokenProvider?: TokenProvider;
  private onAuthFailure?: () => void;
  private authFailureFired = false;
  /**
   * Returns the active workspace's orgId on every request. Set by
   * `smart-providers.tsx` from the `useCurrentOrgStore`. The value is
   * sent as `X-Active-Organization-ID` and validated by the gateway against
   * the user's memberships (PR0.5b). Returning null = no claim → gateway
   * falls back to defaultOrganizationId.
   */
  private activeOrgProvider?: () => string | null;

  constructor(config: ApiClientConfig = {}) {
    // All requests go through the Next.js proxy at /api/proxy
    // The proxy forwards to the Spring Gateway
    this.baseUrl = config.baseUrl || '/api/proxy';
    this.timeout = config.timeout || 30000;
    this.retries = config.retries || 1;
  }

  /**
   * Set the OIDC token provider
   * This should be called once when the app initializes
   */
  setTokenProvider(provider: TokenProvider): void {
    this.tokenProvider = provider;
  }

  /**
   * Get the current token provider.
   *
   * <p><strong>Almost always the wrong method.</strong> Use {@link getAuthToken}: reading the
   * provider skips its wait, and during the async auth bootstrap it is `undefined`, so the caller
   * silently goes out anonymous. In prod that was the gateway's most frequent error in the week to
   * 2026-08-25, 98 x 401 on `GET /api/files/by-id/<id>/raw`. Two call sites remain, for two
   * different reasons:
   *   1. `smart-providers.tsx` does not want a token at all - it saves and restores the provider
   *      FUNCTION around a share-token session.
   *   2. `useModels` does want one, to decide `skipAuth`, but must not pay the WAIT: that endpoint
   *      is public and is called signed out, where waiting would only delay a request that was
   *      always going to be anonymous.
   */
  getTokenProvider(): TokenProvider | undefined {
    return this.tokenProvider;
  }

  /**
   * Resolve the current access token the same way every apiClient request does, including
   * the startup wait.
   *
   * <p>For the raw `fetch` call sites that cannot go through apiClient (media blobs, uploads,
   * streaming) but must still send `Authorization`. The provider is installed inside an async
   * bootstrap in `smart-providers.tsx`, so a component mounting first sees
   * `getTokenProvider() === undefined`. Reading the provider directly turns that window into an
   * anonymous request, the gateway answers 401, and a one-shot effect never retries: the media
   * stays broken until a reload. This waits for the provider to appear and for it to return a
   * token instead of giving up on the first null.
   *
   * <p><strong>The cost of the wait, since this now runs behind every media element on a page:</strong>
   * when a provider is installed and returns a token (every signed-in call) it resolves on the
   * first try and adds nothing.
   *
   * <p>It blocks when there is no token to be had: about 1 s while no provider has appeared
   * (10 polls of 100 ms), then up to 1 s more if the provider keeps answering null, after which it
   * returns null. Read that as the NORMAL signed-out path, not a rare one: `smart-providers.tsx`
   * installs a provider only once it holds an access token, so a genuinely signed-out session
   * never installs one at all and every call here pays the full first loop, plus one
   * `console.warn`, where reading the provider used to return instantly. Concurrent callers wait
   * in parallel, so a gallery pays it once in wall-clock, not once per image. On a surface that is
   * EXPECTED to be anonymous, prefer an explicitly anonymous request over paying the wait.
   *
   * <p><strong>It is a bound, not a guarantee.</strong> If the bootstrap takes longer than the
   * wait above, the request still goes out anonymous and a one-shot effect still never retries -
   * the original symptom, just rarer. The wait is sized against a healthy bootstrap (a token read
   * plus three parallel calls), not against an arbitrarily slow one.
   *
   * <p><strong>Never rejects.</strong> A provider that throws is caught and becomes null, so
   * callers need no try/catch around it. `authenticatedFetch` dropped one on that basis, which
   * makes this a load-bearing guarantee rather than a convenience: it is pinned in
   * `api-client.getAuthToken.test.ts` by making a REAL client's provider throw. Pinning it by
   * mocking `getAuthToken` itself to reject would be worthless, since that is a shape the real
   * object cannot produce.
   *
   * <p>One caveat the wait cannot cover: `await` on the provider has no timeout of its own (same
   * as every `apiClient` request), so a provider that never settles hangs the caller rather than
   * resolving null after ~2 s.
   */
  async getAuthToken(): Promise<string | null> {
    return this.getToken(true);
  }

  /**
   * Set callback for unrecoverable auth failures (401 + refresh failed).
   * Called once - triggers redirect to login.
   */
  setOnAuthFailure(callback: () => void): void {
    this.onAuthFailure = callback;
    this.authFailureFired = false;
  }

  /**
   * Set the active-org provider - called by `smart-providers.tsx` to bridge
   * the apiClient (outside React) with `useCurrentOrgStore` (Zustand). The
   * value is read on every request and sent as `X-Active-Organization-ID`.
   *
   * <p>The provider is a synchronous function (Zustand `getState()` is cheap).
   * Passing null/undefined disables active-org transport - useful for tests
   * and signed-out flows.
   */
  setActiveOrgProvider(provider: (() => string | null) | undefined): void {
    this.activeOrgProvider = provider;
  }

  /**
   * Get authentication token
   * Waits for token if provider is not yet configured (race condition with OIDC)
   */
  private async getToken(waitForToken: boolean = true): Promise<string | null> {
    // If no token provider yet and we should wait, retry a few times
    if (!this.tokenProvider && waitForToken) {
      for (let i = 0; i < 10; i++) {
        await new Promise(resolve => setTimeout(resolve, 100));
        if (this.tokenProvider) break;
      }
    }

    if (!this.tokenProvider) {
      console.warn('[ApiClient] No token provider configured');
      return null;
    }

    try {
      const token = await this.tokenProvider();
      if (!token && waitForToken) {
        // Token provider exists but returned null, retry a few times
        for (let i = 0; i < 5; i++) {
          await new Promise(resolve => setTimeout(resolve, 200));
          const retryToken = await this.tokenProvider();
          if (retryToken) return retryToken;
        }
      }
      return token;
    } catch (error) {
      console.warn('[ApiClient] Failed to get token:', error);
      return null;
    }
  }

  /**
   * Build URL with query parameters
   */
  private buildUrl(path: string, params?: Record<string, string | number | boolean | undefined>): string {
    const cleanPath = path.startsWith('/') ? path : `/${path}`;
    let url = `${this.baseUrl}${cleanPath}`;

    if (params) {
      const searchParams = new URLSearchParams();
      Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null) {
          searchParams.set(key, String(value));
        }
      });
      const queryString = searchParams.toString();
      if (queryString) {
        url += `?${queryString}`;
      }
    }

    return url;
  }

  /**
   * Execute a single fetch with the given token and timeout.
   * Returns the parsed response or throws ApiError.
   */
  private async executeFetch<T>(
    method: string,
    url: string,
    token: string | null,
    body?: any,
    options: RequestOptions = {},
    timeout?: number,
  ): Promise<T> {
    const effectiveTimeout = timeout ?? this.timeout;
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...options.headers,
    };

    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }

    // PR0.5c: send the active workspace's orgId so the gateway can switch
    // org context per request. Caller-supplied header in `options.headers`
    // wins so test overrides remain explicit.
    if (this.activeOrgProvider && !headers['X-Active-Organization-ID']) {
      try {
        const activeOrgId = this.activeOrgProvider();
        if (activeOrgId) {
          headers['X-Active-Organization-ID'] = activeOrgId;
        }
      } catch {
        // Provider read should never throw, but defend against store
        // hydration races during hot-reload - silent fallback to default org.
      }
    }

    const controller = new AbortController();
    // timeout=0 means no timeout (let the request run indefinitely)
    const timeoutId = effectiveTimeout > 0
      ? setTimeout(() => controller.abort(), effectiveTimeout)
      : null;

    const response = await fetch(url, {
      method,
      headers,
      body: body && method !== 'GET' ? JSON.stringify(body) : undefined,
      credentials: 'include',
      signal: options.signal || controller.signal,
    });

    if (timeoutId) clearTimeout(timeoutId);

    // Phase G (archi-refoundation 2026-05-04) - handle 304 Not Modified BEFORE
    // the response.ok check (304 has ok=false in `fetch` semantics - audit B v5).
    // Callers that pass If-None-Match get back NOT_MODIFIED sentinel; they
    // typically respond by keeping their last-known value.
    if (response.status === 304) {
      return NOT_MODIFIED as unknown as T;
    }

    // Handle success responses
    if (response.ok) {
      if (response.status === 204 || response.status === 205) {
        return null as T;
      }

      const contentType = response.headers.get('content-type');
      if (contentType?.includes('application/json')) {
        return await response.json();
      }
      return await response.text() as T;
    }

    // Handle error responses
    let errorData: any = {};
    try {
      errorData = await response.json();
    } catch {
      // Ignore JSON parse errors
    }

    // Plan limit exceeded - emit a global event so the toast listener can react.
    // We still throw the ApiError so callers' own catch blocks can suppress their
    // local error UI if desired.
    if (
      response.status === 409 &&
      errorData?.error === 'PLAN_RESOURCE_LIMIT_EXCEEDED' &&
      typeof window !== 'undefined'
    ) {
      window.dispatchEvent(
        new CustomEvent('plan-limit-exceeded', { detail: errorData })
      );
    }

    // Capture Retry-After header for 429 responses so the retry logic can honour it
    const retryAfterHeader = response.headers.get('Retry-After');
    if (retryAfterHeader) {
      errorData.retryAfter = retryAfterHeader;
    }

    // The gateway blocks a deactivated account with the same 429 it uses for quota, so
    // this is a rate limit in shape only: waiting changes nothing until the person
    // reactivates. Emit a global event so the restore interstitial can offer that, and
    // let the retry loop below recognise it and give up immediately.
    if (isInactiveAccountResponse(response.status, errorData) && typeof window !== 'undefined') {
      accountInactiveSeen = true;
      window.dispatchEvent(new CustomEvent(ACCOUNT_INACTIVE_EVENT));
    }

    throw new ApiError(
      errorData.message || errorData.error || `HTTP ${response.status}: ${response.statusText}`,
      response.status,
      errorData.code || errorData.error || `HTTP_${response.status}`,
      errorData
    );
  }

  /**
   * Make HTTP request with retry logic. Every terminal failure (after retries,
   * or a client error that is never retried) is counted for analytics before
   * it is rethrown unchanged.
   */
  private async request<T>(
    method: string,
    path: string,
    body?: any,
    options: RequestOptions = {}
  ): Promise<T> {
    try {
      return await this.requestWithRetry<T>(method, path, body, options);
    } catch (error) {
      reportRequestFailure(error, method, path);
      throw error;
    }
  }

  /**
   * On 401, attempts a single token refresh + retry before giving up.
   */
  private async requestWithRetry<T>(
    method: string,
    path: string,
    body?: any,
    options: RequestOptions = {}
  ): Promise<T> {
    const url = this.buildUrl(path, options.params);
    const maxRetries = options.retries ?? this.retries;
    const timeout = options.timeout ?? this.timeout;

    const token = options.skipAuth ? null : await this.getToken();

    // Fail fast if no token available instead of sending an unauthenticated request.
    // optionalAuth opts out: a missing token is fine (the request goes out anonymously).
    if (!token && !options.skipAuth && !options.optionalAuth) {
      throw new ApiError('No authentication token available', 401, 'NO_TOKEN');
    }

    let lastError: Error | null = null;

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return await this.executeFetch<T>(method, url, token, body, options, timeout);
      } catch (error) {
        lastError = error as Error;

        // On 401: force-refresh the token and retry once
        if (error instanceof ApiError && error.status === 401 && !options.skipAuth && this.tokenProvider) {
          try {
            // Force a refresh: the server refused THIS token, so the cached one (even if its
            // exp is still ahead, e.g. clock skew or a revoked session) must not be re-sent.
            const freshToken = await this.tokenProvider({ forceRefresh: true });
            if (freshToken && freshToken !== token) {
              return await this.executeFetch<T>(method, url, freshToken, body, options, timeout);
            }
          } catch {
            // Refresh failed - trigger auth failure redirect
          }
          // Token refresh failed or returned same/null token - session is dead
          if (this.onAuthFailure && !this.authFailureFired) {
            this.authFailureFired = true;
            this.onAuthFailure();
          }
          throw error;
        }

        // An inactive account is not a rate limit: every retry gets the same answer, so
        // backing off only delays the interstitial that lets the person do something
        // about it. Fail on the first response instead of after the full backoff ladder.
        if (isInactiveAccountError(error)) {
          throw error;
        }

        // Retry on 429 (rate limited) with exponential backoff + jitter.
        // Honours the gateway's Retry-After header (seconds) when present.
        if (error instanceof ApiError && error.status === 429 && attempt < maxRetries) {
          const retryAfterSec = Number(error.details?.retryAfter) || 0;
          const delay = retryAfterSec > 0
            ? retryAfterSec * 1000 + Math.random() * 500
            : Math.pow(2, attempt) * 1000 + Math.random() * 500;
          await new Promise(resolve => setTimeout(resolve, delay));
          continue;
        }

        // Don't retry on other client errors (4xx) or abort
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
          throw error;
        }

        if (error instanceof DOMException && error.name === 'AbortError') {
          throw new ApiError('Request timeout', 408, 'TIMEOUT');
        }

        // Retry on network/server errors
        if (attempt < maxRetries) {
          const delay = Math.pow(2, attempt) * 1000;
          await new Promise(resolve => setTimeout(resolve, delay));
          continue;
        }
      }
    }

    throw lastError || new ApiError('Request failed', 500, 'UNKNOWN');
  }

  /**
   * GET request
   */
  async get<T>(path: string, options?: RequestOptions): Promise<T> {
    return this.request<T>('GET', path, undefined, options);
  }

  /**
   * POST request
   */
  async post<T>(path: string, body?: any, options?: RequestOptions): Promise<T> {
    return this.request<T>('POST', path, body, options);
  }

  /**
   * PUT request
   */
  async put<T>(path: string, body?: any, options?: RequestOptions): Promise<T> {
    return this.request<T>('PUT', path, body, options);
  }

  /**
   * PATCH request
   */
  async patch<T>(path: string, body?: any, options?: RequestOptions): Promise<T> {
    return this.request<T>('PATCH', path, body, options);
  }

  /**
   * DELETE request. Body is optional - most DELETE endpoints have no
   * body, but a few (PR-cascade: DELETE /organizations/{id} with
   * {confirmName: "..."}) require one. Stays compliant with RFC 7231 §4.3.5
   * which allows a DELETE body even though most middleware ignores it.
   */
  async delete<T>(path: string, body?: any, options?: RequestOptions): Promise<T> {
    return this.request<T>('DELETE', path, body, options);
  }

}

// HMR-safe singleton: survives Next.js hot-reload by persisting on globalThis
const GLOBAL_KEY = Symbol.for('__apiClient_singleton__');

function getOrCreateApiClient(): ApiClient {
  const g = globalThis as any;
  if (!g[GLOBAL_KEY]) {
    g[GLOBAL_KEY] = new ApiClient();
  }
  return g[GLOBAL_KEY];
}

export const apiClient = getOrCreateApiClient();

// Export class for testing
export { ApiClient };

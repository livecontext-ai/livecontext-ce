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

class ApiClient {
  private baseUrl: string;
  private timeout: number;
  private retries: number;
  private tokenProvider?: () => Promise<string | null>;
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
  setTokenProvider(provider: () => Promise<string | null>): void {
    this.tokenProvider = provider;
  }

  /**
   * Get the current token provider
   */
  getTokenProvider(): (() => Promise<string | null>) | undefined {
    return this.tokenProvider;
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

    throw new ApiError(
      errorData.message || errorData.error || `HTTP ${response.status}: ${response.statusText}`,
      response.status,
      errorData.code || errorData.error || `HTTP_${response.status}`,
      errorData
    );
  }

  /**
   * Make HTTP request with retry logic.
   * On 401, attempts a single token refresh + retry before giving up.
   */
  private async request<T>(
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
            const freshToken = await this.tokenProvider();
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

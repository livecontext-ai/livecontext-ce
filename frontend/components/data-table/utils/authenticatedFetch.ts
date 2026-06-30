import { apiClient } from '@/lib/api';

// Local token getter that can be set from the controller
let localTokenGetter: (() => Promise<string>) | null = null;

/**
 * Set the auth token getter function.
 * This is called from useDataTableController to ensure the token is available
 * before any authenticated fetch calls are made.
 */
export function setAuthTokenGetter(getter: () => Promise<string>): void {
  localTokenGetter = getter;
}

/**
 * Make authenticated fetch calls using the local token getter or apiClient's token provider.
 * This is a thin wrapper for cases where we need the raw Response object.
 *
 * For most API calls, prefer using apiClient methods directly (get, post, put, delete).
 * Use this helper when you need access to response headers or status codes.
 */
export async function authenticatedFetch(url: string, options: RequestInit = {}): Promise<Response> {
  // Prefer local token getter (set from OIDC), fall back to apiClient
  const tokenProvider = localTokenGetter || apiClient.getTokenProvider();
  let token: string | null = null;

  if (tokenProvider) {
    try {
      token = await tokenProvider();
    } catch (e) {
      console.warn('[authenticatedFetch] Failed to get token:', e);
    }
  }

  const headers: HeadersInit = {
    'Content-Type': 'application/json',
    ...(options.headers || {}),
  };

  if (token) {
    (headers as Record<string, string>)['Authorization'] = `Bearer ${token}`;
  }

  return fetch(url, {
    ...options,
    headers,
  });
}

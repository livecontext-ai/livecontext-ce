/**
 * Edge-route authentication by delegation to the backend.
 *
 * The Next.js frontend has no JWT-verification infrastructure (no signing key, no `jose`); the
 * backend gateway/MonolithSecurityFilter is the auth authority. For an edge route that does NOT
 * forward to the backend (e.g. the external-proxy "test an API" tool), we validate the caller's
 * Bearer token by probing a lightweight authenticated backend endpoint with that token: the token
 * is accepted only if the backend accepts it. Fail-closed - a malformed token, a backend
 * rejection, a timeout, or an unreachable backend all deny.
 */

const AUTH_PROBE_PATH = '/api/organizations/me';
const PROBE_TIMEOUT_MS = 5_000;

export async function isBackendAuthenticated(
  authHeader: string | null,
  baseUrl: string,
  fetcher: typeof fetch = fetch,
): Promise<boolean> {
  if (
    !authHeader ||
    !authHeader.startsWith('Bearer ') ||
    authHeader.slice('Bearer '.length).trim() === ''
  ) {
    return false;
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
  try {
    const response = await fetcher(`${baseUrl}${AUTH_PROBE_PATH}`, {
      method: 'GET',
      headers: { Authorization: authHeader },
      signal: controller.signal,
    });
    return response.ok; // backend validated the token (filter injects identity → 2xx)
  } catch {
    return false; // fail-closed: timeout / network error / abort
  } finally {
    clearTimeout(timeoutId);
  }
}

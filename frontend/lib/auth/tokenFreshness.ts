/**
 * When the SPA must refresh its access token BEFORE sending it.
 *
 * Keycloak access tokens live 15 minutes (CASA LC-014). Waiting until a token is strictly expired
 * (the previous rule) sends tokens that expire in flight or a few seconds later on a server whose
 * clock runs slightly ahead, and the request comes back 401. Refreshing inside the last
 * {@link TOKEN_REFRESH_LEEWAY_SECONDS} removes that window; the gateway additionally accepts
 * {@code exp} up to 30 s in the past for clock skew.
 */
export const TOKEN_REFRESH_LEEWAY_SECONDS = 60;

/**
 * @param expiresAt token expiry, epoch SECONDS (oidc-client-ts `User.expires_at`); undefined
 *                  means unknown, which is treated as "refresh"
 * @param nowSeconds current time, epoch seconds (injectable for tests)
 */
export function tokenNeedsRefresh(
  expiresAt: number | undefined | null,
  nowSeconds: number = Math.floor(Date.now() / 1000),
): boolean {
  if (expiresAt === undefined || expiresAt === null || !Number.isFinite(expiresAt)) {
    return true;
  }
  return expiresAt - nowSeconds < TOKEN_REFRESH_LEEWAY_SECONDS;
}

export type AccessTokenAction = 'none' | 'cached' | 'refresh';

/**
 * What `getAccessToken` does for the current session: no session, return the cached token, or
 * refresh first. `forceRefresh` is the apiClient 401 path: the server refused the cached token,
 * so it is never handed back again, whatever its `expires_at` says.
 */
export function accessTokenAction(
  user: { expires_at?: number | null } | null | undefined,
  forceRefresh = false,
  nowSeconds: number = Math.floor(Date.now() / 1000),
): AccessTokenAction {
  if (!user) return 'none';
  if (forceRefresh || tokenNeedsRefresh(user.expires_at, nowSeconds)) return 'refresh';
  return 'cached';
}

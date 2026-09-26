/**
 * Query parameters Keycloak appends to the redirect URI when it sends the user back:
 * the OIDC authorization response, and the status of an application-initiated action.
 */
const OIDC_CALLBACK_PARAMS = [
  'code',
  'state',
  'session_state',
  'iss',
  'error',
  'error_description',
  'error_uri',
  'kc_action_status',
  'kc_action',
] as const;

/**
 * The address to leave in the bar once the sign-in callback has been processed: the same
 * path and the caller's OWN query parameters, without the one-shot callback parameters.
 *
 * Dropping the whole query (the previous behaviour) also threw away what the redirect was
 * made to preserve: a settings page sent to Keycloak with `?tab=security` came back on its
 * default tab, so the user did not see the authenticator app they had just added.
 */
export function stripOidcCallbackParams(pathname: string, search: string): string {
  const params = new URLSearchParams(search);
  for (const name of OIDC_CALLBACK_PARAMS) params.delete(name);
  const rest = params.toString();
  return rest ? `${pathname}?${rest}` : pathname;
}

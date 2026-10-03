/**
 * The browser storage keys a signed-in session lives under, shared by the code that writes them
 * and the code that only needs to know a session is there (the public header's pre-paint hint).
 * A plain module, not a client one: the hint is inlined by a server-rendered header.
 */

/** Prefix of the OIDC user oidc-client-ts stores for the cloud (`oidc.user:<authority>:<client>`). */
export const OIDC_USER_KEY_PREFIX = 'oidc.user:';

/** The self-hosted (CE) embedded session's access token. */
export const CE_ACCESS_TOKEN_KEY = 'ce_access_token';

'use client';

import { createContext } from 'react';
import type { UserManager } from 'oidc-client-ts';

/**
 * The cloud OIDC session's oidc-client-ts UserManager, the same instance react-oidc-context's
 * AuthProvider runs on (app/providers.tsx creates it and hands it to both).
 *
 * The token refresh (smart-providers.tsx refreshCloudSession) calls THIS manager's signinSilent,
 * not the one useAuth() returns: react-oidc-context wraps every navigator method in a try/catch
 * that turns any failure into a resolved `null`, so a refused refresh token (invalid_grant) would
 * look like "refreshed, but no token" and a request timeout could never be retried. The manager
 * itself rejects with the real error, and still updates the auth context: a refresh that succeeds
 * stores the user and raises userLoaded, which AuthProvider listens to.
 *
 * Null during server rendering and in the CE edition (embedded auth has no UserManager).
 */
export const OidcUserManagerContext = createContext<UserManager | null>(null);

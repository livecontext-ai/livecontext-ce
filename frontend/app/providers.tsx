"use client";
import { useState } from 'react';
import { AuthProvider } from 'react-oidc-context';
import { UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { ThemeProvider } from '../components/ThemeProvider';
import FirstLoginGuard from '../components/security/FirstLoginGuard';
import { AppDataProvider, LOGIN_SIGNIN_AT_KEY, LOGIN_REDIRECT_LOG_KEY } from '../lib/providers/smart-providers';
import { EmbeddedAuthProvider } from '../lib/providers/embedded-auth-provider';
import AnalyticsProvider from '../components/analytics/AnalyticsProvider';
import AcquisitionCapture from '../components/lifecycle/AcquisitionCapture';
import PendingRewardCodeCapture from '../components/reward/PendingRewardCodeCapture';
import SiteSessionHintWriter from '../components/auth/SiteSessionHintWriter';
import { IS_CE } from '../lib/edition';
import { markOrbiGreeting } from '../components/chat/orbi/orbiGreeting';
import { stripOidcCallbackParams } from '../lib/auth/oidcCallbackUrl';
import { OIDC_REQUEST_TIMEOUT_SECONDS } from '../lib/auth/crossTabLock';
import { OidcUserManagerContext } from '../lib/auth/oidcUserManager';

// Persist OIDC user in localStorage (vs default sessionStorage) so that opening the app
// in a new tab - or closing/reopening the tab - finds the previously stored user and can
// silently refresh the access token via the auto-recovery effect in smart-providers.tsx
// (which only runs when oidc.user exists and is `expired`). Trade-off: the refresh token
// lives in localStorage (XSS surface). It is NOT short-lived: it lasts as long as the
// Keycloak session (7 days without use, 14 days at most, configure-keycloak.sh). What
// limits a stolen copy is rotation: every refresh spends the token presented and stores
// its successor, an older copy is refused once its successor has been used, and Keycloak
// ends the session's tokens when one is replayed past its one tolerated reuse. Plus the
// cross-tab logout listener in smart-providers.tsx.
// Exported for the regression test that pins the userStore choice.
export const oidcConfig = {
  authority: `${process.env.NEXT_PUBLIC_KEYCLOAK_URL}/realms/${process.env.NEXT_PUBLIC_KEYCLOAK_REALM}`,
  client_id: process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID || '',
  redirect_uri: typeof window !== 'undefined' ? `${window.location.origin}/app/` : '',
  scope: 'openid profile email',
  automaticSilentRenew: false,
  // oidc-client-ts sets no request timeout by default: a hung /token held the cross-tab refresh
  // lock (and every tab waiting on it) until Cloudflare gave up after ~100 s. The silent one is
  // also passed by every refresh call (smart-providers.tsx refreshSession): signinSilent()
  // without arguments overrides this setting with `undefined` for the refresh-token request.
  requestTimeoutInSeconds: OIDC_REQUEST_TIMEOUT_SECONDS,
  silentRequestTimeoutInSeconds: OIDC_REQUEST_TIMEOUT_SECONDS,
  userStore: typeof window !== 'undefined'
    ? new WebStorageStateStore({ store: window.localStorage })
    : undefined,
  onSigninCallback: () => {
    window.history.replaceState({}, document.title,
      stripOidcCallbackParams(window.location.pathname, window.location.search));
    // Mark successful signin time. Two consumers read this (via the shared keys):
    // safeRedirectToLogin detects "401 right after signin" (backend issue, not auth),
    // and decideLoginRedirect treats "automatic redirect right after a signin" as the
    // login/logout loop signal (this stamp re-arms it every silent re-auth cycle).
    sessionStorage.setItem(LOGIN_SIGNIN_AT_KEY, Date.now().toString());
    // Clear redirect loop counter - this signin proves prior redirects were legitimate.
    sessionStorage.removeItem(LOGIN_REDIRECT_LOG_KEY);
    // Orbi waves hello the first time it appears after this sign-in.
    markOrbiGreeting();
  },
};

/**
 * The UserManager AuthProvider runs on, created here so the token refresh can call it directly
 * (lib/auth/oidcUserManager.ts says why). None during server rendering (AuthProvider makes no
 * calls there) nor in CE (embedded auth).
 */
export function createOidcUserManager(): UserManager | null {
  if (IS_CE || typeof window === 'undefined') return null;
  // onSigninCallback is an AuthProvider prop, not a UserManager setting.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { onSigninCallback, ...userManagerSettings } = oidcConfig;
  return new UserManager(userManagerSettings);
}

export default function Providers({ children }: { children: React.ReactNode }) {
  const [oidcUserManager] = useState(createOidcUserManager);
  const inner = (
    <ThemeProvider>
      <AppDataProvider>
        {/* CE (self-hosted) ships no product analytics/tracking. */}
        {!IS_CE && <AnalyticsProvider />}
        {/* First-touch attribution for the cloud lifecycle e-mails; landing included. */}
        {!IS_CE && <AcquisitionCapture />}
        {/* A partner / creator code from the landing link, applied after sign-up. */}
        {!IS_CE && <PendingRewardCodeCapture />}
        {/* Tells the docs subdomain's header that someone is signed in here (initials only). */}
        {!IS_CE && <SiteSessionHintWriter />}
        <FirstLoginGuard>
          {children}
        </FirstLoginGuard>
      </AppDataProvider>
    </ThemeProvider>
  );

  if (IS_CE) {
    return (
      <EmbeddedAuthProvider>
        {inner}
      </EmbeddedAuthProvider>
    );
  }

  return (
    <OidcUserManagerContext.Provider value={oidcUserManager}>
      <AuthProvider {...oidcConfig} userManager={oidcUserManager ?? undefined}>
        {inner}
      </AuthProvider>
    </OidcUserManagerContext.Provider>
  );
}

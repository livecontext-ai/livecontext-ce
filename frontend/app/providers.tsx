"use client";
import { AuthProvider } from 'react-oidc-context';
import { WebStorageStateStore } from 'oidc-client-ts';
import { ThemeProvider } from '../components/ThemeProvider';
import FirstLoginGuard from '../components/security/FirstLoginGuard';
import { AppDataProvider, LOGIN_SIGNIN_AT_KEY, LOGIN_REDIRECT_LOG_KEY } from '../lib/providers/smart-providers';
import { EmbeddedAuthProvider } from '../lib/providers/embedded-auth-provider';
import AnalyticsProvider from '../components/analytics/AnalyticsProvider';
import { IS_CE } from '../lib/edition';

// Persist OIDC user in localStorage (vs default sessionStorage) so that opening the app
// in a new tab - or closing/reopening the tab - finds the previously stored user and can
// silently refresh the access token via the auto-recovery effect in smart-providers.tsx
// (which only runs when oidc.user exists and is `expired`). Trade-off: refresh token
// lives in localStorage (XSS surface). Mitigated by short Keycloak refresh-token TTL +
// cross-tab logout listener in smart-providers.tsx.
// Exported for the regression test that pins the userStore choice.
export const oidcConfig = {
  authority: `${process.env.NEXT_PUBLIC_KEYCLOAK_URL}/realms/${process.env.NEXT_PUBLIC_KEYCLOAK_REALM}`,
  client_id: process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID || '',
  redirect_uri: typeof window !== 'undefined' ? `${window.location.origin}/app/` : '',
  scope: 'openid profile email',
  automaticSilentRenew: false,
  userStore: typeof window !== 'undefined'
    ? new WebStorageStateStore({ store: window.localStorage })
    : undefined,
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname);
    // Mark successful signin time. Two consumers read this (via the shared keys):
    // safeRedirectToLogin detects "401 right after signin" (backend issue, not auth),
    // and decideLoginRedirect treats "automatic redirect right after a signin" as the
    // login/logout loop signal (this stamp re-arms it every silent re-auth cycle).
    sessionStorage.setItem(LOGIN_SIGNIN_AT_KEY, Date.now().toString());
    // Clear redirect loop counter - this signin proves prior redirects were legitimate.
    sessionStorage.removeItem(LOGIN_REDIRECT_LOG_KEY);
  },
};

export default function Providers({ children }: { children: React.ReactNode }) {
  const inner = (
    <ThemeProvider>
      <AppDataProvider>
        {/* CE (self-hosted) ships no product analytics/tracking. */}
        {!IS_CE && <AnalyticsProvider />}
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
    <AuthProvider {...oidcConfig}>
      {inner}
    </AuthProvider>
  );
}

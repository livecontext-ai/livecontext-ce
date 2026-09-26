'use client';

import { useQuery } from '@tanstack/react-query';
import { useLocale } from 'next-intl';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import type { ProfileContextPayload } from '@/lib/api/services/user-api.service';
import { readAcquisition } from '@/lib/lifecycle/acquisition';
import { isAnalyticsConsentGranted } from '@/lib/analytics/consent';

/** sessionStorage key holding the last report this tab already delivered. */
export const PROFILE_CONTEXT_SENT_KEY = 'lc_ctx_sent_v1';

function browserTimeZone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined;
  } catch {
    return undefined;
  }
}

/**
 * The context report. First-touch acquisition (UTM, referrer, landing path) is marketing
 * attribution, so it is included only when the person accepted analytics cookies; locale
 * and time zone are needed to address the service emails and are always sent.
 */
export function buildProfileContextPayload(locale: string): ProfileContextPayload {
  const payload: ProfileContextPayload = { locale, localeExplicit: false };
  const timeZone = browserTimeZone();
  if (timeZone) payload.timeZone = timeZone;
  const acquisition =
    typeof window !== 'undefined' && isAnalyticsConsentGranted() ? readAcquisition(window) : null;
  if (acquisition) payload.acquisition = acquisition;
  return payload;
}

function alreadySent(key: string): boolean {
  try {
    return sessionStorage.getItem(PROFILE_CONTEXT_SENT_KEY) === key;
  } catch {
    // No session store: report anyway, the endpoint is idempotent.
    return false;
  }
}

function markSent(key: string): void {
  try {
    sessionStorage.setItem(PROFILE_CONTEXT_SENT_KEY, key);
  } catch {
    // Nothing to remember in; the react-query cache still dedupes within this page load.
  }
}

/**
 * Reports the signed-in person's context (displayed locale, browser time zone, first-touch
 * acquisition) to PUT /users/profile/context, once per tab session per distinct payload.
 *
 * <p>A query rather than an effect: react-query dedupes by key, never loops, and `retry: false`
 * keeps a failing endpoint at one request per payload per page load. The key carries the user
 * id, so a different account signing in on the same tab reports its own context.
 *
 * <p>Inert until auth is ready, and when signed out. Mounted only in the /app and /onboarding shells, so share,
 * embed and public pages never send it.
 */
export function useProfileContextReport(enabled = true): void {
  const auth = useOptionalAuth();
  const locale = useLocale();
  const ready = !!auth && auth.isAuthenticated && auth.isReady && !auth.isLoading && auth.numericUserId != null;

  const payload = buildProfileContextPayload(locale);
  const guardKey = `${auth?.numericUserId ?? ''}:${JSON.stringify(payload)}`;

  useQuery({
    queryKey: ['user', 'profile-context', guardKey],
    queryFn: async () => {
      await unifiedApiService.reportProfileContext(payload);
      markSent(guardKey);
      return true;
    },
    enabled: enabled && ready && !alreadySent(guardKey),
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchOnMount: false,
  });
}

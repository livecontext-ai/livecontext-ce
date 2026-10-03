import type { ReactNode } from 'react';
import NonceLocaleLayoutBody from '@/components/security/NonceLocaleLayoutBody';
import ProfileContextReporter from '@/components/lifecycle/ProfileContextReporter';
import { IS_CE } from '@/lib/edition';

// LC-027 CASA E3 (round 2): this area now gets the per-request nonce script-src
// (lib/security/securityHeaders.mjs's NONCE_CSP_PREFIXES, proxy.ts) instead of the static
// 'unsafe-inline' one. A nonce is only meaningful on a per-request render - see
// NonceLocaleLayoutBody for why reading headers() here is safe.
export const dynamic = 'force-dynamic';

/**
 * Onboarding shell. Mounts the lifecycle context reporter (cloud only) so the locale the
 * person reads the onboarding in reaches their contact BEFORE the welcome email is sent,
 * not only once they land in /app. The reporter itself waits for a signed-in, ready auth.
 */
export default function OnboardingLayout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ locale: string }>;
}) {
  return (
    <NonceLocaleLayoutBody params={params}>
      {!IS_CE && <ProfileContextReporter />}
      {children}
    </NonceLocaleLayoutBody>
  );
}

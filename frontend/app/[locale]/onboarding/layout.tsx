import type { ReactNode } from 'react';
import ProfileContextReporter from '@/components/lifecycle/ProfileContextReporter';
import { IS_CE } from '@/lib/edition';

/**
 * Onboarding shell. Mounts the lifecycle context reporter (cloud only) so the locale the
 * person reads the onboarding in reaches their contact BEFORE the welcome email is sent,
 * not only once they land in /app. The reporter itself waits for a signed-in, ready auth.
 */
export default function OnboardingLayout({ children }: { children: ReactNode }) {
  return (
    <>
      {!IS_CE && <ProfileContextReporter />}
      {children}
    </>
  );
}

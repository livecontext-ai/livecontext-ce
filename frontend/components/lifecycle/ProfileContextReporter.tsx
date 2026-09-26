'use client';

import { useProfileContextReport } from '@/hooks/useProfileContextReport';

/**
 * Sends the signed-in person's context once per session (see useProfileContextReport).
 * Renders nothing. Mounted in the /app and /onboarding layouts only, cloud only.
 */
export default function ProfileContextReporter() {
  useProfileContextReport();
  return null;
}

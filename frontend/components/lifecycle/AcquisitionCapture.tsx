'use client';

import { useEffect } from 'react';
import { captureFirstTouch, snapshotLanding } from '@/lib/lifecycle/acquisition';
import { CONSENT_CHANGE_EVENT, isAnalyticsConsentGranted } from '@/lib/analytics/consent';

/**
 * Records the first-touch acquisition (utm, external referrer, landing path). Renders nothing.
 * Mounted once in the root providers (cloud only) so the landing counts.
 *
 * Acquisition is marketing tracking, so it follows the cookie banner exactly like PostHog: the
 * landing is only snapshotted in memory, and written to storage once analytics consent is
 * granted (now, or later in the same page session). A visitor who refuses is never recorded.
 */
export default function AcquisitionCapture() {
  useEffect(() => {
    const pending = snapshotLanding();
    if (isAnalyticsConsentGranted()) {
      captureFirstTouch(window, new Date(), pending);
      return;
    }
    const onConsent = () => {
      if (isAnalyticsConsentGranted()) {
        captureFirstTouch(window, new Date(), pending);
        window.removeEventListener(CONSENT_CHANGE_EVENT, onConsent);
      }
    };
    window.addEventListener(CONSENT_CHANGE_EVENT, onConsent);
    return () => window.removeEventListener(CONSENT_CHANGE_EVENT, onConsent);
  }, []);
  return null;
}

'use client';

/**
 * Wires the analytics facade (`lib/analytics`) into the React tree:
 *  - initializes PostHog once cookie consent is granted (and reacts to a live
 *    consent change via the {@link CONSENT_CHANGE_EVENT} window event),
 *  - identifies the authenticated user (stable UUID) once auth is ready,
 *  - keeps the `organization_id` super-property in sync on workspace switch.
 *
 * Renders nothing. Mounted inside {@link AppDataProvider} so it can read auth
 * context. Entirely inert when no PostHog key is configured (every effect
 * short-circuits on `isAnalyticsConfigured()`, a build-time constant).
 */

import { useEffect, useState } from 'react';
import { useAuth } from '@/lib/providers/smart-providers';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { CONSENT_CHANGE_EVENT, isAnalyticsConsentGranted } from '@/lib/analytics/consent';
import {
  disableAnalytics,
  identifyUser,
  initAnalytics,
  isAnalyticsConfigured,
  setAnalyticsOrganization,
} from '@/lib/analytics/analytics';

export default function AnalyticsProvider() {
  const { isAuthenticated, isReady, numericUserId } = useAuth();
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);

  // Lazily read the persisted consent once at mount (no sync setState in an
  // effect); afterwards only the banner's change event flips it.
  const [consentGranted, setConsentGranted] = useState<boolean>(
    () => typeof window !== 'undefined' && isAnalyticsConsentGranted(),
  );

  // Subscribe to live consent changes (Accept/Reject without a reload).
  useEffect(() => {
    if (!isAnalyticsConfigured()) return;
    const onChange = () => setConsentGranted(isAnalyticsConsentGranted());
    window.addEventListener(CONSENT_CHANGE_EVENT, onChange);
    return () => window.removeEventListener(CONSENT_CHANGE_EVENT, onChange);
  }, []);

  // Start analytics on consent grant; stop capturing if it is withdrawn.
  useEffect(() => {
    if (!isAnalyticsConfigured()) return;
    if (consentGranted) initAnalytics();
    else disableAnalytics();
  }, [consentGranted]);

  // Identify the user once auth is ready AND consent is granted.
  // Use `numericUserId` (the backend DB user id) as distinct_id - NOT the
  // Keycloak `sub` - because every server-side event uses this same id as its
  // distinct_id (it is the value the gateway injects as X-User-ID). Keying both
  // surfaces on the same id is what lets a user's frontend funnels and backend
  // agent/tool/workflow outcomes resolve to ONE PostHog person. No PII (opaque
  // internal id). Re-runs when consent flips so a mid-session opt-in still links.
  useEffect(() => {
    if (!isAnalyticsConfigured() || !consentGranted) return;
    if (!isAuthenticated || !isReady) return;
    if (numericUserId == null) return;
    identifyUser(String(numericUserId), currentOrgId ?? null);
  }, [consentGranted, isAuthenticated, isReady, numericUserId, currentOrgId]);

  // Keep the workspace super-property fresh on switch.
  useEffect(() => {
    if (!isAnalyticsConfigured() || !consentGranted) return;
    setAnalyticsOrganization(currentOrgId ?? null);
  }, [consentGranted, currentOrgId]);

  return null;
}

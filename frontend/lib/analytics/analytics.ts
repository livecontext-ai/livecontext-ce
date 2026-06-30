/**
 * Product-analytics facade (PostHog) for LiveContext.
 *
 * Design guarantees:
 *  - **No-op unless configured**: if `NEXT_PUBLIC_POSTHOG_KEY` is unset, every
 *    function is a permanent no-op (safe to ship before a key exists).
 *  - **Consent-gated**: never initializes / captures unless the cookie-consent
 *    contract (`isAnalyticsConsentGranted`) is satisfied. See `consent.ts`.
 *  - **No PII**: only UUIDs / enums / counts leave the browser. `organization_id`
 *    (workspace UUID) is allowed; `tenant_id` is NEVER emitted from the frontend
 *    (CLAUDE.md rule). Callers must not pass emails, names, or user content.
 *
 * Taxonomy: `the project docs`.
 */

import { EDITION } from '@/lib/edition';
import { isAnalyticsConsentGranted } from './consent';
import { loadPosthog, type PosthogClient } from './posthogLoader';
import type { AnalyticsEvent, AnalyticsProps } from './events';

const POSTHOG_KEY = process.env.NEXT_PUBLIC_POSTHOG_KEY;
// Default to the EU region (GDPR data residency). Prod sets this explicitly via
// the deploy config; the default only guards against a missing override.
const POSTHOG_HOST = process.env.NEXT_PUBLIC_POSTHOG_HOST || 'https://eu.i.posthog.com';

let client: PosthogClient | null = null;
let initialized = false;
let currentOrgId: string | null = null;

/** True when a PostHog project key is present at build time. */
export function isAnalyticsConfigured(): boolean {
  return Boolean(POSTHOG_KEY);
}

/** Props attached to every captured event. */
function commonProps(): AnalyticsProps {
  return { app_edition: EDITION, surface: 'frontend', organization_id: currentOrgId };
}

/**
 * Initializes PostHog. Idempotent. No-op when not configured, on the server, or
 * when consent has not been granted. Safe to call repeatedly (e.g. when consent
 * flips from rejected → accepted at runtime).
 */
export function initAnalytics(): void {
  if (typeof window === 'undefined') return;
  if (!POSTHOG_KEY) return;
  if (!isAnalyticsConsentGranted()) return;

  // Already running: re-enable capture if it was opted out by a prior
  // consent withdrawal (Accept → Reject → Accept within one session).
  if (initialized) {
    if (client) {
      try { client.opt_in_capturing(); } catch { /* ignore */ }
    }
    return;
  }

  const ph = loadPosthog(POSTHOG_HOST);
  if (!ph) return;

  ph.init(POSTHOG_KEY, {
    api_host: POSTHOG_HOST,
    capture_pageview: true,
    // ── Performance budget: keep PostHog as light as possible ──────────────
    // Explicit, named events only - no global click/input listeners.
    autocapture: false,
    // No session replay: it is the single heaviest PostHog cost (DOM mutation
    // observer + payload upload). We never want it for product analytics.
    disable_session_recording: true,
    // No PerformanceObserver / web-vitals collection.
    capture_performance: false,
    // We use neither feature flags nor remote config, so skip the extra
    // `/decide` network round-trip on load.
    advanced_disable_decide: true,
    // Only build person profiles for identified users (fewer writes).
    person_profiles: 'identified_only',
  });
  client = ph;
  initialized = true;
  ph.register({ app_edition: EDITION });
  if (currentOrgId) ph.register({ organization_id: currentOrgId });
}

/** Associates subsequent events with a stable user id (UUID). Inits lazily. */
export function identifyUser(userId: string, orgId: string | null): void {
  currentOrgId = orgId;
  if (!initialized) initAnalytics();
  if (!initialized || !client) return;
  client.identify(userId, { organization_id: orgId });
}

/** Updates the workspace super-property (e.g. on workspace switch). */
export function setAnalyticsOrganization(orgId: string | null): void {
  currentOrgId = orgId;
  if (initialized && client) client.register({ organization_id: orgId });
}

/** Captures a typed product event. No-op when analytics is off. */
export function track(event: AnalyticsEvent, props: AnalyticsProps = {}): void {
  if (!initialized || !client) return;
  client.capture(event, { ...commonProps(), ...props });
}

/** Clears the identified user (call on logout). Never throws - a failure here
 * must not be able to abort the logout redirect. */
export function resetAnalytics(): void {
  if (initialized && client) {
    try { client.reset(); } catch { /* ignore */ }
  }
}

/** Stops capturing when consent is withdrawn at runtime. */
export function disableAnalytics(): void {
  if (initialized && client) {
    try { client.opt_out_capturing(); } catch { /* ignore */ }
  }
}

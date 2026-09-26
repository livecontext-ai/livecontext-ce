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
import { currentPosthog, loadPosthog, type PosthogClient } from './posthogLoader';
import type { AnalyticsEvent, AnalyticsProps } from './events';

const POSTHOG_KEY = process.env.NEXT_PUBLIC_POSTHOG_KEY;
// Default to the EU region (GDPR data residency). Prod sets this explicitly via
// the deploy config; the default only guards against a missing override.
const POSTHOG_HOST = process.env.NEXT_PUBLIC_POSTHOG_HOST || 'https://eu.i.posthog.com';

/** The stub handed to `init()`. Kept ONLY as a fallback for environments where
 * `window.posthog` is not the live client (tests); see {@link client}. */
let initialStub: PosthogClient | null = null;
let initialized = false;
let currentOrgId: string | null = null;

/**
 * The LIVE client. Resolved on every call, never cached: after `array.js` loads,
 * PostHog swaps `window.posthog` from the queue stub to the real instance, and a
 * cached stub silently swallows every later event (regression: 0 product events
 * reached PostHog for two months while `$pageview`, captured by the SDK itself,
 * kept flowing).
 */
function client(): PosthogClient | null {
  return currentPosthog() ?? initialStub;
}

/** True when a PostHog project key is present at build time. */
export function isAnalyticsConfigured(): boolean {
  return Boolean(POSTHOG_KEY);
}

/** Props attached to every captured event. Their keys are RESERVED: {@link track}
 * applies them after the caller's props, so an event cannot overwrite them (a caller
 * that needs "where was this started from" sends `entry_point`, never `surface`). */
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
    const ph = client();
    if (ph) {
      try { ph.opt_in_capturing(); } catch { /* ignore */ }
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
  initialStub = ph;
  initialized = true;
  ph.register({ app_edition: EDITION });
  if (currentOrgId) ph.register({ organization_id: currentOrgId });
}

/**
 * Associates subsequent events with a stable user id (UUID). Inits lazily.
 *
 * The `$set_once` block carries the acquisition context captured while the
 * visitor was still anonymous (which landing persona / CTA / plan they showed
 * interest in, see {@link setLandingIntent}) onto the person the FIRST time it
 * is identified, so "why did this user come" survives the signup redirect and is
 * never overwritten by a later session.
 */
export function identifyUser(userId: string, orgId: string | null): void {
  currentOrgId = orgId;
  if (!initialized) initAnalytics();
  const ph = client();
  if (!initialized || !ph) return;
  ph.identify(userId, { organization_id: orgId }, readLandingIntent());
}

/**
 * Landing intent super-properties: bounded keys describing what the visitor
 * engaged with before signing up (`landing_persona`, `landing_cta`,
 * `landing_plan`). Registered as super-properties (persisted by PostHog across
 * pages and the auth redirect) so every later event carries them, and folded
 * into `$set_once` on identify. Values must be enum-like: never free text.
 */
export type LandingIntentKey = 'landing_persona' | 'landing_cta' | 'landing_plan';
const LANDING_INTENT_KEYS: readonly LandingIntentKey[] = ['landing_persona', 'landing_cta', 'landing_plan'];
/** Our own copy of the FIRST value per key. The SDK's `get_property` is not on
 * the queue stub (it returns a value, it cannot be queued), so before `array.js`
 * has loaded it does not exist; `identifyUser` runs as soon as auth is ready and
 * would race it. Persisted in localStorage so it survives the auth redirect. */
const LANDING_INTENT_STORAGE_KEY = 'lc.landingIntent';
let landingIntentFirst: Record<string, string> | null = null;

function loadLandingIntentFirst(): Record<string, string> {
  if (landingIntentFirst) return landingIntentFirst;
  landingIntentFirst = {};
  try {
    const raw = window.localStorage.getItem(LANDING_INTENT_STORAGE_KEY);
    const parsed = raw ? (JSON.parse(raw) as Record<string, unknown>) : null;
    if (parsed && typeof parsed === 'object') {
      for (const key of LANDING_INTENT_KEYS) {
        const v = parsed[key];
        if (typeof v === 'string' && v) landingIntentFirst[key] = v;
      }
    }
  } catch { /* storage blocked: session-only memory */ }
  return landingIntentFirst;
}

export function setLandingIntent(key: LandingIntentKey, value: string): void {
  const ph = client();
  if (!initialized || !ph || !value) return;
  const first = loadLandingIntentFirst();
  if (!first[key]) {
    first[key] = value;
    try { window.localStorage.setItem(LANDING_INTENT_STORAGE_KEY, JSON.stringify(first)); } catch { /* ignore */ }
  }
  try {
    // First interest wins for attribution ("what brought them"), but the latest
    // is kept too so a funnel can be cut on either.
    ph.register_once?.({ [`${key}_first`]: first[key] });
    ph.register({ [key]: value });
  } catch { /* ignore */ }
}

function readLandingIntent(): Record<string, unknown> {
  if (typeof window === 'undefined') return {};
  return { ...loadLandingIntentFirst() };
}

/** Forgets the first-touch intent. Called on logout: a `$set_once` is permanent
 * on the person, so the previous visitor's intent must never reach the next
 * account identified on the same browser. */
function clearLandingIntent(): void {
  landingIntentFirst = {};
  try { window.localStorage.removeItem(LANDING_INTENT_STORAGE_KEY); } catch { /* ignore */ }
}

/** A catalog-shaped slug passes; anything else (a user-typed credential or
 * integration name) is dropped. Used where a field is a slug for catalog rows
 * but free text for user-created ones. */
export function slugOrNull(value: string | null | undefined): string | null {
  if (!value) return null;
  return /^[a-z0-9][a-z0-9_.-]{0,80}$/.test(value) ? value : null;
}

/**
 * Registers the current in-app view as a super-property so every event (and
 * PostHog's own `$pageview`) can be broken down by product surface without
 * sending the raw pathname (which embeds conversation / workflow / publication
 * ids). `app_view` is a bounded enum owned by `useCurrentView`.
 */
export function setAppView(view: string | null, isDetailPage: boolean): void {
  const ph = client();
  if (!initialized || !ph) return;
  try {
    if (view) {
      ph.register({ app_view: view, is_detail_page: isDetailPage });
    } else {
      ph.unregister?.('app_view');
      ph.unregister?.('is_detail_page');
    }
  } catch { /* ignore */ }
}

/** Updates the workspace super-property (e.g. on workspace switch). */
export function setAnalyticsOrganization(orgId: string | null): void {
  currentOrgId = orgId;
  const ph = client();
  if (initialized && ph) ph.register({ organization_id: orgId });
}

/** Captures a typed product event. No-op when analytics is off. Returns true
 * when the event was handed to the SDK, so a caller that must not lose a
 * one-shot signal (a landing section already on screen when the banner is
 * accepted) can retry after consent instead of discarding it.
 *
 * Never throws, for the same reason {@link resetAnalytics} does not: measuring
 * an action must not be able to break the action. Call sites sit in the middle
 * of flows that have already succeeded (the onboarding completion writes its
 * event after the server has accepted the profile), so a throw here would
 * surface as a failure of something that in fact worked. A capture that threw
 * returns false, which is also the honest answer for a retrying caller: the
 * event was not handed over. */
export function track(event: AnalyticsEvent, props: AnalyticsProps = {}): boolean {
  const ph = client();
  if (!initialized || !ph) return false;
  try {
    // Common props LAST: `surface`, `app_edition` and `organization_id` are reserved
    // and must read the same on every event, whatever a caller passes.
    ph.capture(event, { ...props, ...commonProps() });
    return true;
  } catch {
    return false;
  }
}

/** Clears the identified user (call on logout). Never throws - a failure here
 * must not be able to abort the logout redirect. */
export function resetAnalytics(): void {
  clearLandingIntent();
  const ph = client();
  if (initialized && ph) {
    try { ph.reset(); } catch { /* ignore */ }
  }
}

/** Stops capturing when consent is withdrawn at runtime. */
export function disableAnalytics(): void {
  const ph = client();
  if (initialized && ph) {
    try { ph.opt_out_capturing(); } catch { /* ignore */ }
  }
}

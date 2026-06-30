/**
 * Analytics consent gate.
 *
 * Single source of truth = the cookie-consent choice persisted by
 * {@link ../../components/CookieConsentBanner}. That component's own contract
 * (see its header comment) is: the stored value is "the gate for any future
 * non-essential cookie/tracker", granted when `status === 'accepted'`.
 *
 * We re-read the SAME `localStorage` key here instead of duplicating the
 * consent model - analytics is exactly the "non-essential tracker" the banner
 * was built to gate.
 */

/** Must match {@link CookieConsentBanner} STORAGE_KEY / VERSION. */
const CONSENT_STORAGE_KEY = 'lc.cookieConsent';
const CONSENT_VERSION = 1;

/**
 * Window event the banner dispatches when the user makes (or changes) a choice,
 * so a live session can start/stop analytics without a page reload. `detail` is
 * the new {@link ConsentStatus}.
 */
export const CONSENT_CHANGE_EVENT = 'lc:cookie-consent';

export type ConsentStatus = 'accepted' | 'rejected';

/**
 * True when the user has actively accepted the current consent version.
 * Conservative by default: any missing/stale/malformed value → not granted
 * (analytics stays off until an explicit, current "accept").
 */
export function isAnalyticsConsentGranted(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    const raw = window.localStorage.getItem(CONSENT_STORAGE_KEY);
    if (!raw) return false;
    const parsed = JSON.parse(raw) as { status?: string; version?: number } | null;
    return parsed?.status === 'accepted' && parsed?.version === CONSENT_VERSION;
  } catch {
    return false;
  }
}

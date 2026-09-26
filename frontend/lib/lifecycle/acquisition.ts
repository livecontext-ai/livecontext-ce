/**
 * First-touch acquisition capture for the lifecycle e-mails (cloud only).
 *
 * <p>On the very first page a browser loads (the landing included), we remember where the visitor
 * came from: the `utm_*` campaign parameters, the external referrer (origin and path only), the
 * landing path and when it happened. It is written ONCE (first touch wins) under
 * {@link ACQUISITION_STORAGE_KEY} and later sent with the signed-in profile context report, which
 * the backend also stores write-once.
 *
 * <p>No PII: no query string other than the five `utm_*` values, no referrer query or hash, and
 * any path segment that can carry a capability token (share links, embeds, invitations) is cut
 * back to its prefix. Every storage access is guarded: a blocked store (private mode, site data
 * disabled) must never break a page.
 */

export const ACQUISITION_STORAGE_KEY = 'lc_acq_v1';

/** Per-field cap for utm values, matching the backend column width. */
const UTM_MAX = 255;
/** Cap for the referrer and the landing path, matching the backend column width. */
const URL_MAX = 1024;

export interface AcquisitionData {
  utmSource?: string;
  utmMedium?: string;
  utmCampaign?: string;
  utmContent?: string;
  utmTerm?: string;
  /** External referrer as `origin + pathname`, absent when there is none or it is this site. */
  referrer?: string;
  /** Path of the first page loaded, token-bearing segments redacted. */
  landingPath: string;
  /** ISO-8601 timestamp of the first visit. */
  firstSeenAt: string;
}

const UTM_PARAMS: Array<[string, keyof AcquisitionData]> = [
  ['utm_source', 'utmSource'],
  ['utm_medium', 'utmMedium'],
  ['utm_campaign', 'utmCampaign'],
  ['utm_content', 'utmContent'],
  ['utm_term', 'utmTerm'],
];

const LOCALE_SEGMENTS = new Set(['en', 'fr', 'es', 'de', 'pt', 'zh']);

/**
 * First path segments whose NEXT segment is a capability token or an opaque id that must not
 * leave the browser: public share links (`/s/<token>`, `/f/<token>`), embeds (`/w/...`),
 * invitation and redeem flows.
 */
const TOKEN_PREFIXES = new Set(['s', 'f', 'w', 'invitations', 'redeem', 'shared']);

function clip(value: string, max: number): string {
  return value.length > max ? value.slice(0, max) : value;
}

/** Keeps the path up to (and including) a token-bearing prefix, drops everything after it. */
export function redactLandingPath(pathname: string): string {
  const segments = pathname.split('/').filter(Boolean);
  const kept: string[] = [];
  for (let i = 0; i < segments.length; i += 1) {
    const segment = segments[i];
    kept.push(segment);
    const isLocale = i === 0 && LOCALE_SEGMENTS.has(segment);
    if (!isLocale && TOKEN_PREFIXES.has(segment)) break;
  }
  return clip(`/${kept.join('/')}`, URL_MAX);
}

/** Last two labels of a hostname (`auth.livecontext.ai` -> `livecontext.ai`), port dropped. */
function siteOf(host: string): string {
  const hostname = host.split(':')[0].toLowerCase();
  return hostname.split('.').slice(-2).join('.');
}

/**
 * `origin + pathname` of an EXTERNAL referrer, or undefined (none, unparseable, or same site).
 * Same SITE rather than same host: the sign-in callback comes back from the identity provider on
 * a sibling subdomain, and that is not where the visitor came from.
 */
export function externalReferrer(referrer: string, currentHost: string): string | undefined {
  if (!referrer) return undefined;
  try {
    const url = new URL(referrer);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return undefined;
    if (siteOf(url.host) === siteOf(currentHost)) return undefined;
    return clip(`${url.origin}${url.pathname}`, URL_MAX);
  } catch {
    return undefined;
  }
}

/** Pure builder, kept apart from the storage access so it can be tested on its own. */
export function buildAcquisition(
  location: { pathname: string; search: string; host: string },
  referrer: string,
  now: Date,
): AcquisitionData {
  const params = new URLSearchParams(location.search);
  const data: AcquisitionData = {
    landingPath: redactLandingPath(location.pathname),
    firstSeenAt: now.toISOString(),
  };
  for (const [param, field] of UTM_PARAMS) {
    const value = params.get(param)?.trim();
    if (value) (data as unknown as Record<string, string>)[field] = clip(value, UTM_MAX);
  }
  const ref = externalReferrer(referrer, location.host);
  if (ref) data.referrer = ref;
  return data;
}

/**
 * Stores the first-touch record when none exists yet. Returns what is stored after the call
 * (the existing record, the new one, or null when storage is unavailable).
 *
 * `pending` is the record snapshotted when the browser first landed (see
 * {@link snapshotLanding}): acquisition tracking is only persisted after cookie consent, which
 * often arrives a few client-side navigations later, when `location` no longer shows the utm.
 */
export function captureFirstTouch(
  win: Window = window,
  now: Date = new Date(),
  pending: AcquisitionData | null = null,
): AcquisitionData | null {
  try {
    const existing = readAcquisition(win);
    if (existing) return existing;
    const data = pending ?? buildAcquisition(win.location, win.document?.referrer ?? '', now);
    win.localStorage.setItem(ACQUISITION_STORAGE_KEY, JSON.stringify(data));
    return data;
  } catch {
    return null;
  }
}

let landingSnapshot: AcquisitionData | null = null;

/**
 * Keeps the landing record IN MEMORY only (no storage, so nothing is written before consent).
 * The first call of the page load wins; later calls return that same snapshot.
 */
export function snapshotLanding(win: Window = window, now: Date = new Date()): AcquisitionData {
  if (!landingSnapshot) {
    landingSnapshot = buildAcquisition(win.location, win.document?.referrer ?? '', now);
  }
  return landingSnapshot;
}

/** Test seam: forget the in-memory landing snapshot. */
export function resetLandingSnapshotForTests(): void {
  landingSnapshot = null;
}

/** The stored first-touch record, or null when absent, malformed or unreadable. */
export function readAcquisition(win: Window = window): AcquisitionData | null {
  try {
    const raw = win.localStorage.getItem(ACQUISITION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || typeof parsed.landingPath !== 'string') return null;
    return parsed as AcquisitionData;
  } catch {
    return null;
  }
}

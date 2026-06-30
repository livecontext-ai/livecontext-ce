import { IS_CE } from './edition';

/**
 * Canonical cloud web origin (no trailing slash).
 *
 * A cloud-linked CE renders CLOUD-hosted marketplace publications through the
 * remote proxy, so user-facing report / notice-and-takedown / terms links must
 * reach the cloud operator at this origin - never the local self-hosted install
 * (localhost / the CE's own domain), whose contact form lands in the wrong
 * mailbox. Mirrors the `CE_API_BASE` constant used for cloud category reads in
 * `CategoryFilter`.
 */
export const CLOUD_WEB_BASE_URL = 'https://livecontext.ai';

/**
 * In CE, rewrite an app-relative path or a same-origin URL onto
 * {@link CLOUD_WEB_BASE_URL}, preserving the path, query string and hash.
 *
 * No-op in cloud builds (the value is already same-origin) and a safe fallback
 * to the input when the URL cannot be parsed or is empty.
 *
 * @param pathOrUrl an app-relative path (`"/contact?category=abuse&message=…"`)
 *                  or an absolute same-origin URL (e.g. `window.location.href`).
 */
export function cloudWebUrl(pathOrUrl: string): string {
  if (!IS_CE || !pathOrUrl) return pathOrUrl;
  try {
    // Resolve against the current origin so a relative path becomes absolute,
    // then swap the origin for the cloud base while keeping path + query + hash.
    // An already-absolute input keeps its own path (its origin is discarded).
    const base = typeof window !== 'undefined' ? window.location.origin : CLOUD_WEB_BASE_URL;
    const resolved = new URL(pathOrUrl, base);
    // Only rewrite web URLs. Opaque schemes (mailto:, tel:, javascript:) have an
    // empty/garbled pathname and must pass through untouched.
    if (resolved.protocol !== 'http:' && resolved.protocol !== 'https:') return pathOrUrl;
    return `${CLOUD_WEB_BASE_URL}${resolved.pathname}${resolved.search}${resolved.hash}`;
  } catch {
    return pathOrUrl;
  }
}

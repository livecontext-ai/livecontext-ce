import { apiClient } from '@/lib/api/api-client';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';
import { mimeEssence } from '@/lib/files/filePreview';

/** Check if a URL is an internal proxy URL that needs an auth token. */
export function isInternalUrl(url: string): boolean {
  return url.startsWith('/api/');
}

/**
 * Types a browser EXECUTES when it opens them as a top-level document. A blob URL inherits this
 * app's origin, so opening one of these in a tab runs its script with the session in reach - an
 * uploaded web page becomes same-origin code. Neutralised (served as plain text) on the view
 * path, so the tab shows the source instead of running it, which is what a forge does with a raw
 * file. Downloading is untouched: the bytes and the file name are unchanged either way.
 */
const EXECUTABLE_TYPES = new Set([
  'text/html', 'application/xhtml+xml', 'image/svg+xml', 'application/xml', 'text/xml',
]);

/**
 * True when a browser would EXECUTE these bytes rather than merely display them, given a
 * top-level document to do it in.
 *
 * <p>Exported because the neutralisation below only covers the one path that goes through this
 * helper. A plain {@code <a href={blobUrl} target="_blank">} re-types nothing, so any card that
 * hands the user such an anchor has to make the same decision - from the same list, or the two
 * drift and one of them becomes the way in.
 */
export function executesWhenOpened(mimeType: string | null | undefined): boolean {
  return EXECUTABLE_TYPES.has(mimeEssence(mimeType));
}

/** What an executable type is re-stamped as, so the tab renders it as source. */
const NEUTRAL_TYPE = 'text/plain';

/**
 * Fetch an internal proxy file with the {@code Authorization: Bearer} header
 * (+ the active-org header) and return an in-memory {@code blob:} object URL.
 * External / data: URLs are returned unchanged (no fetch). The active-org
 * header travels on the request so a file in a non-default workspace - or one
 * shared into an org the caller belongs to - resolves cross-org, which an
 * {@code <img>}/{@code window.open} could never do (it can't send headers).
 *
 * <p><strong>Security:</strong> this replaces the old {@code buildAuthUrl} that
 * appended the full OIDC session token as {@code ?token=}. That token is a
 * long-lived (14-day realm lifespan), full-scope bearer - putting it in a URL
 * leaked the whole session to anyone the URL reached (copy/paste, CDN /
 * reverse-proxy / analytics logs, browser history). Auth now lives only in the
 * request header; nothing user-visible carries a credential.
 *
 * <p>The caller owns the returned blob URL's lifetime: revoke it after a
 * one-shot download; for a new-tab view leave it for the page to reclaim on
 * unload. For React rendering use {@code useAuthedObjectUrl} instead - it
 * revokes automatically.
 */
export async function fetchAuthedBlobUrl(
  url: string,
  options?: {
    /**
     * Re-stamp an {@link EXECUTABLE_TYPES} blob as plain text before handing back its URL. Set by
     * every caller that puts the result in a top-level browsing context; a download does not need
     * it, and a caller that wants the file rendered (an image, a PDF) is unaffected either way.
     */
    neutralizeExecutable?: boolean;
  },
): Promise<string> {
  if (!isInternalUrl(url)) return url;

  // Normalize legacy /api/files/ → /api/proxy/files/.
  const fetchUrl = url.startsWith('/api/files/')
    ? '/api/proxy' + url.substring('/api'.length)
    : url;

  // getAuthToken so a click landing during the auth bootstrap downloads the file instead of a
  // 401 body. Same reason as useAuthedObjectUrl.
  const token = await apiClient.getAuthToken();
  const headers: Record<string, string> = { ...getActiveOrgHeaderForRequest() };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const response = await fetch(fetchUrl, { headers });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  let blob = await response.blob();
  // slice(0, size, type) is a zero-copy re-type: same bytes, a type the browser renders instead
  // of executing. Decided on the SERVED type, which is the only one that matters here - the type
  // a caller holds in a row or a cell is written by whoever wrote the file.
  // Essence, not the raw string: `text/html;charset=utf-8` is what a server actually sends, it
  // executes exactly like a bare `text/html`, and an exact match on the raw value would let it
  // straight through - the guard would be gone for the most common spelling of the thing it
  // exists to stop. An empty type is left alone: it executes nothing, and our raw serve always
  // sends one.
  if (options?.neutralizeExecutable && executesWhenOpened(blob.type)) {
    blob = blob.slice(0, blob.size, NEUTRAL_TYPE);
  }
  return URL.createObjectURL(blob);
}

/**
 * Open a file in a new tab via an authenticated fetch - no token in the URL, and nothing that
 * would EXECUTE there: this is a top-level document on the app's origin, which is a stronger
 * position than any preview iframe, so an executable type is served as its own source instead.
 */
export async function openAuthedFileInNewTab(url: string): Promise<void> {
  const objectUrl = await fetchAuthedBlobUrl(url, { neutralizeExecutable: true });
  window.open(objectUrl, '_blank', 'noopener,noreferrer');
}

/** Save a file via an authenticated fetch - no token in the URL. */
export async function downloadAuthedFile(url: string, filename: string): Promise<void> {
  const objectUrl = await fetchAuthedBlobUrl(url);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  // Release the blob shortly after the browser has picked up the download.
  if (objectUrl.startsWith('blob:')) {
    setTimeout(() => URL.revokeObjectURL(objectUrl), 10_000);
  }
}

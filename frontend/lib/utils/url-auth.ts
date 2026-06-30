import { apiClient } from '@/lib/api/api-client';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';

/** Check if a URL is an internal proxy URL that needs an auth token. */
export function isInternalUrl(url: string): boolean {
  return url.startsWith('/api/');
}

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
export async function fetchAuthedBlobUrl(url: string): Promise<string> {
  if (!isInternalUrl(url)) return url;

  // Normalize legacy /api/files/ → /api/proxy/files/.
  const fetchUrl = url.startsWith('/api/files/')
    ? '/api/proxy' + url.substring('/api'.length)
    : url;

  const token = await apiClient.getTokenProvider()?.();
  const headers: Record<string, string> = { ...getActiveOrgHeaderForRequest() };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const response = await fetch(fetchUrl, { headers });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const blob = await response.blob();
  return URL.createObjectURL(blob);
}

/** Open a file in a new tab via an authenticated fetch - no token in the URL. */
export async function openAuthedFileInNewTab(url: string): Promise<void> {
  const objectUrl = await fetchAuthedBlobUrl(url);
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

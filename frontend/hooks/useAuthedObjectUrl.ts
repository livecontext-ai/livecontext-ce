'use client';

import { useEffect, useState } from 'react';
import { apiClient } from '@/lib/api/api-client';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';

export interface AuthedObjectUrl {
  /**
   * Blob object URL ready for `<img>`/`<video>`/`<audio>`/`<iframe>` `src`, or
   * `null` while loading, on error, or when no source was given. External
   * (non-proxy) sources pass straight through unchanged.
   */
  url: string | null;
  loading: boolean;
  error: boolean;
}

/**
 * Fetches an internal file URL (e.g. {@code getFileUrlById(id, {inline:true})})
 * with the {@code Authorization: Bearer} header + the active-org header, and
 * returns a revocable blob object URL suitable for any media `src`
 * (`<img>`/`<video>`/`<audio>`/`<iframe>` pdf).
 *
 * <p><strong>Why this exists (security):</strong> an `<img>`/`<video>` element
 * cannot send an `Authorization` header, so the previous pattern appended the
 * full OIDC session token as `?token=` on the URL. That token is a long-lived
 * (14-day realm lifespan), full-scope bearer - copying or sharing the rendered
 * URL, or any CDN / reverse-proxy / analytics log that captures it, leaks the
 * entire session (ADMIN included). Keeping auth in the request header and
 * rendering from an in-memory `blob:` URL means nothing user-visible or
 * shareable ever carries a credential. This generalises the fetch→blob pattern
 * already used by {@code AuthenticatedImage} and the file text preview.
 *
 * <p>The object URL is revoked on unmount and whenever {@code src} changes.
 * A falsy {@code src} yields {@code {url:null, loading:false}}. An external
 * source (anything not under {@code /api/}) is returned verbatim with no fetch
 * - the browser loads it directly. Legacy {@code /api/files/…} sources are
 * normalised to the {@code /api/proxy/files/…} proxy path, mirroring the old
 * {@code buildAuthUrl} helper this replaces.
 */
export function useAuthedObjectUrl(
  src: string | null | undefined,
  /**
   * Concrete media MIME to stamp on the blob when the server's Content-Type is generic
   * (the by-id raw serve falls back to {@code application/octet-stream} for a row whose stored
   * mime_type is missing - that type can't drive a {@code <video>}/{@code <iframe>}(pdf)). A
   * specific server type is left untouched. Typically {@code resolveMediaMimeType(mime, fileName)}.
   */
  mimeTypeHint?: string | null,
): AuthedObjectUrl {
  const [url, setUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!src) {
      setUrl(null);
      setLoading(false);
      setError(false);
      return;
    }

    // External / data: / blob: sources are not behind our auth proxy - render
    // them directly. Only internal proxy paths need the header-authenticated fetch.
    if (!isInternalProxyUrl(src)) {
      setUrl(src);
      setLoading(false);
      setError(false);
      return;
    }

    // Normalise legacy /api/files/ → /api/proxy/files/ (mirrors the removed buildAuthUrl).
    const fetchUrl = src.startsWith('/api/files/')
      ? '/api/proxy' + src.substring('/api'.length)
      : src;

    let mounted = true;
    let objectUrl: string | null = null;
    setLoading(true);
    setError(false);

    (async () => {
      try {
        const tokenProvider = apiClient.getTokenProvider();
        const token = tokenProvider ? await tokenProvider() : null;
        const headers: Record<string, string> = { ...getActiveOrgHeaderForRequest() };
        if (token) headers['Authorization'] = `Bearer ${token}`;

        const response = await fetch(fetchUrl, { headers });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        let blob = await response.blob();
        // Re-type a generic blob (octet-stream) from the caller's media hint so a PDF/video whose
        // stored mime_type was missing still renders instead of a broken iframe / undecodable video.
        // slice(0, size, type) is a zero-copy re-type; a specific server type is kept as-is.
        if (mimeTypeHint && (!blob.type || blob.type === 'application/octet-stream' || blob.type === 'binary/octet-stream')) {
          blob = blob.slice(0, blob.size, mimeTypeHint);
        }
        objectUrl = URL.createObjectURL(blob);
        if (mounted) {
          setUrl(objectUrl);
          setLoading(false);
        } else {
          URL.revokeObjectURL(objectUrl);
        }
      } catch {
        if (mounted) {
          setError(true);
          setLoading(false);
        }
      }
    })();

    return () => {
      mounted = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [src, mimeTypeHint]);

  return { url, loading, error };
}

/** Internal Next.js proxy URL that needs header auth (not an external/data/blob source). */
function isInternalProxyUrl(url: string): boolean {
  return url.startsWith('/api/');
}

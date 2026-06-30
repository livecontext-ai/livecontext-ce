'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { apiClient } from '@/lib/api/api-client';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';
import { findFileRefs, normalizeFileRef, fileRefToUrl } from '@/lib/api/orchestrator/file.service';

/**
 * Pre-fetches every {@link FileRef} found in {@code resolvedData} as a base64 {@code data:} URI -
 * auth travels in the request header, NEVER in the URL - so an interface iframe can render files
 * without the session token ever appearing in its HTML.
 *
 * <p><strong>Why {@code data:} and not {@code blob:}:</strong> interface iframes are sandboxed
 * {@code allow-scripts} only (NO {@code allow-same-origin}) so untrusted publisher JS can't reach the
 * parent - see {@code InterfaceIframe} / {@code ShowcasePreview}. A {@code blob:} URL is bound to its
 * creator's origin and is unreadable from such an opaque-origin iframe, but a {@code data:} URI is
 * self-contained and renders in any sandbox. <strong>Why this matters:</strong> the previous approach
 * baked the full OIDC session token into the iframe HTML as {@code ?token=} on every {@code <img src>};
 * those requests carried a long-lived, full-scope bearer to the network (CDN / proxy / analytics logs).
 *
 * <p>The active-org header travels on every fetch, so a file in a non-default workspace resolves
 * cross-org (an {@code <img>} could never send it). Returns a stable {@code resolveFileUrl} that maps
 * the opaque by-id URL ({@link fileRefToUrl}) → its {@code data:} URI (falling back to the raw URL
 * until it resolves - a brief unauthenticated 404 at worst, never a token leak). {@code data:} URIs
 * need no revocation (unlike object URLs), so there is no cleanup to leak.
 *
 * @param resolvedData the run-mode data the interface renders from
 * @param enabled gate the work to run mode only (skip in edit/preview where data isn't rendered)
 */
export function useInterfaceFileUrls(
  resolvedData: Record<string, unknown> | undefined,
  enabled: boolean,
): { resolveFileUrl: (rawUrl: string) => string } {
  // Unique opaque by-id URLs to fetch, derived from the FileRefs in the data.
  const rawUrls = useMemo(() => {
    if (!enabled || !resolvedData) return [] as string[];
    const urls = new Set<string>();
    for (const { fileRef } of findFileRefs(resolvedData)) {
      const raw = fileRefToUrl(normalizeFileRef(fileRef), { inline: true });
      if (raw) urls.add(raw);
    }
    return Array.from(urls).sort();
  }, [enabled, resolvedData]);

  // Key the fetch effect on the URL SET (not the data identity) so volatile
  // resolvedData re-renders don't trigger a refetch storm.
  const rawUrlsKey = rawUrls.join('\n');

  const [dataUrls, setDataUrls] = useState<Map<string, string>>(new Map());

  useEffect(() => {
    if (rawUrls.length === 0) {
      setDataUrls(new Map());
      return;
    }
    let cancelled = false;

    (async () => {
      const token = await apiClient.getTokenProvider()?.();
      const headers: Record<string, string> = { ...getActiveOrgHeaderForRequest() };
      if (token) headers['Authorization'] = `Bearer ${token}`;

      const next = new Map<string, string>();
      await Promise.all(
        rawUrls.map(async (raw) => {
          try {
            const res = await fetch(raw, { headers });
            if (!res.ok) return;
            const blob = await res.blob();
            const dataUrl = await blobToDataUrl(blob);
            if (cancelled) return;
            next.set(raw, dataUrl);
          } catch {
            /* leave unresolved → resolver falls back to the raw URL (no token in it) */
          }
        }),
      );
      if (!cancelled) setDataUrls(next);
    })();

    return () => {
      cancelled = true;
    };
    // rawUrlsKey captures the meaningful change; rawUrls is derived from it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rawUrlsKey]);

  // Identity changes when the resolved set changes, so the iframe HTML memo recomputes.
  const resolveFileUrl = useCallback((raw: string) => dataUrls.get(raw) ?? raw, [dataUrls]);

  return { resolveFileUrl };
}

/** Read a Blob into a base64 {@code data:} URI (self-contained - renders in any iframe sandbox). */
function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => resolve(reader.result as string);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

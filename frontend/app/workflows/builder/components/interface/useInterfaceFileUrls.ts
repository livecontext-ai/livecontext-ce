'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { apiClient } from '@/lib/api/api-client';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';
import { findFileRefs, normalizeFileRef, fileRefToUrl } from '@/lib/api/orchestrator/file.service';
import { isTableAsset, parseAsset } from '@/lib/datatable/assetValue';

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
 * <p><strong>Video and audio are NOT inlined, and nothing is inlined past a budget.</strong> A
 * {@code data:} URI is the whole file as base64 (4/3 of its size), and it is then copied into the
 * iframe HTML AND into {@code window.__RESOLVED_DATA__}. Fine for an icon; for six 20 MB videos it
 * was several hundred MB of string and the whole run page died on
 * {@code RangeError: Invalid string length}. The decision is taken from the file reference itself
 * ({@code mimeType}, {@code size}) BEFORE any download - the Next proxy strips
 * {@code Content-Length} and buffers the body, so response headers arrive too late to save
 * anything - and re-checked against the real bytes, since the declared size is data.
 * <ul>
 *   <li>Video/audio resolve to a short-lived {@code /api/files/proxy-signed} link minted by
 *       {@code GET /files/by-id/{id}/signed-url} (same authorisation as the raw serve; one file,
 *       4 hours, no session token, byte ranges so it plays and seeks everywhere), never fetched
 *       here.</li>
 *   <li>Everything else is inlined as before - an interface's own JS can {@code fetch()} a data:
 *       URI, where the signed proxy would need CORS - within {@link INLINE_BUDGET_BYTES} for the
 *       whole interface. A file that does not fit gets a link if one can be had, else stays
 *       unresolved: one element that does not load is the right failure, a crashed page is not.</li>
 *   <li>No link at all (an install with no signing secret): media is inlined as before, within the
 *       same budget; the first 503 is remembered so the install is not asked again.</li>
 * </ul>
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

/**
 * Walk the run data for table media assets, which {@link findFileRefs} does not report: it keys on
 * the stricter FileRef shape (path + mimeType + size), and an asset picked from Files or pasted as
 * a link may carry none of those.
 */
function collectAssets(
  value: unknown,
  depth = 0,
  out: Array<{ url: string; meta: FileMeta }> = [],
): Array<{ url: string; meta: FileMeta }> {
  if (depth > 12 || value === null || typeof value !== 'object') return out;
  const asset = isTableAsset(value) ? parseAsset(value) : null;
  if (asset?.url) {
    // The cell's own type and size decide streaming before any download, as for a FileRef.
    out.push({ url: asset.url, meta: { mimeType: asset.mimeType, size: knownSize(asset.size) ?? undefined } });
    return out;
  }
  for (const child of Object.values(value as Record<string, unknown>)) {
    collectAssets(child, depth + 1, out);
  }
  return out;
}

/**
 * Total bytes inlined per interface. A data: URI is copied into the iframe HTML and into
 * {@code window.__RESOLVED_DATA__}, so the page holds it about three times over as base64: this
 * keeps the whole set far below the browser's string ceiling whatever the files are. Anything past
 * it is streamed or, failing that, left to the iframe unresolved.
 */
export const INLINE_BUDGET_BYTES = 48 * 1024 * 1024;

/** What the data says about a file before anything is downloaded. */
interface FileMeta {
  mimeType?: string;
  size?: number;
}

/**
 * A link that is already signed (a marketplace or showcase file) is anonymous and streamable as it
 * stands: it is passed through to the iframe untouched, never downloaded with the session token.
 */
function isSignedLink(url: string): boolean {
  return url.startsWith('/api/files/proxy-signed?');
}

function isMedia(mimeType: string | null | undefined): boolean {
  return !!mimeType && /^(video|audio)\//i.test(mimeType.trim());
}

function knownSize(size: unknown): number | null {
  const n = typeof size === 'string' ? Number(size) : size;
  return typeof n === 'number' && Number.isFinite(n) && n >= 0 ? n : null;
}

const BY_ID_RAW = /\/files\/by-id\/([0-9a-fA-F-]{36})\/raw(?:\?|$)/;

/**
 * When this install last answered that it cannot sign (503: no signing secret, the CE default).
 * For a few minutes after, files go straight to the inline path instead of paying one refused
 * request each per page view. Time-boxed, because a 503 can also be a restart in progress.
 * Module-level on purpose: the answer is a property of the install.
 */
let signingUnavailableAt = 0;
const SIGNING_RETRY_MS = 5 * 60 * 1000;

/** Test seam: forget a remembered 503 between cases. */
export function resetSigningAvailabilityForTests(): void {
  signingUnavailableAt = 0;
}

/**
 * A short-lived signed link for the by-id URL {@code raw}, or null when none can be had (not a
 * by-id URL, not readable, not in object storage, no signing secret on this install). Never
 * throws: every refusal simply leaves the file unresolved.
 */
async function mintSignedUrl(raw: string): Promise<string | null> {
  const match = BY_ID_RAW.exec(raw);
  if (!match || Date.now() - signingUnavailableAt < SIGNING_RETRY_MS) return null;
  const disposition = /[?&]disposition=attachment(?:&|$)/.test(raw) ? 'attachment' : 'inline';
  try {
    const res = await apiClient.get<{ url?: unknown }>(`/files/by-id/${match[1]}/signed-url`, {
      params: { disposition },
      retries: 0,
    });
    const url = res && typeof res === 'object' ? (res as { url?: unknown }).url : undefined;
    // Only our own relative signed route is accepted: this string is written into the iframe.
    return typeof url === 'string' && url.startsWith('/api/files/proxy-signed?') ? url : null;
  } catch (error) {
    if ((error as { status?: number } | null)?.status === 503) signingUnavailableAt = Date.now();
    return null;
  }
}

export function useInterfaceFileUrls(
  resolvedData: Record<string, unknown> | undefined,
  enabled: boolean,
): { resolveFileUrl: (rawUrl: string) => string } {
  // Unique opaque by-id URLs to resolve, derived from the FileRefs in the data, with what each
  // reference says about its type and size (decided on before any byte is downloaded).
  const { rawUrls, metaByUrl } = useMemo(() => {
    const meta = new Map<string, FileMeta>();
    if (!enabled || !resolvedData) return { rawUrls: [] as string[], metaByUrl: meta };
    for (const { fileRef } of findFileRefs(resolvedData)) {
      const normalized = normalizeFileRef(fileRef);
      const raw = fileRefToUrl(normalized, { inline: true });
      // ONLY same-origin URLs may be fetched below, because that fetch carries the session
      // bearer. A file reference can name any origin (its `url` is data, and a table row can be
      // written by an agent, a workflow, or an acquired marketplace application), so without this
      // filter a hostile row would make the browser hand a long-lived full-scope token to a
      // server of the attacker's choosing. Same guard as fetchAuthedBlobUrl in lib/utils/url-auth.
      // An external URL needs neither the token nor the data: conversion: resolveFileUrl falls
      // back to the raw URL, which the iframe loads anonymously, which is correct for it.
      if (raw && raw.startsWith('/api/') && !isSignedLink(raw) && !meta.has(raw)) {
        meta.set(raw, { mimeType: normalized.mimeType, size: knownSize(normalized.size) ?? undefined });
      }
    }
    // A table media cell is an asset map: it always carries a URL but not always the path/mimeType/
    // size that isFileRef demands, so findFileRefs alone would skip it and the sandboxed iframe
    // would receive the authenticated URL and load it anonymously - the 401 pair described below.
    for (const { url, meta: assetMeta } of collectAssets(resolvedData)) {
      if (url.startsWith('/api/') && !isSignedLink(url) && !meta.has(url)) meta.set(url, assetMeta);
    }
    return { rawUrls: Array.from(meta.keys()).sort(), metaByUrl: meta };
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
      // getAuthToken, rather than reading the provider, so this waits for the async auth bootstrap in
      // smart-providers.tsx. Reading the provider directly made every file in the interface
      // resolve anonymously during that window: the fetch 401s, the entry stays unresolved, and
      // resolveFileUrl hands the iframe the RAW by-id URL - which a sandboxed iframe (no
      // same-origin, no header) can only load anonymously too, for a second 401 and a
      // permanently broken image. That pair of 401s per file was the top gateway error in prod.
      const token = await apiClient.getAuthToken();
      const headers: Record<string, string> = { ...getActiveOrgHeaderForRequest() };
      if (token) headers['Authorization'] = `Bearer ${token}`;

      const next = new Map<string, string>();
      // Shared across the parallel resolutions below. A resolution RESERVES its bytes before it
      // awaits anything (JS runs each check-and-reserve without a gap), otherwise every parallel
      // download would pass the check while the budget still read zero.
      let reserved = 0;
      const reserve = (bytes: number): boolean => {
        if (reserved + bytes > INLINE_BUDGET_BYTES) return false;
        reserved += bytes;
        return true;
      };
      const inline = async (raw: string, blob: Blob): Promise<void> => {
        const dataUrl = await blobToDataUrl(blob);
        if (!cancelled) next.set(raw, dataUrl);
      };
      const stream = async (raw: string): Promise<boolean> => {
        const signed = await mintSignedUrl(raw);
        if (signed && !cancelled) next.set(raw, signed);
        return !!signed;
      };
      const fetchBlob = async (raw: string): Promise<Blob | null> => {
        const res = await fetch(raw, { headers });
        return res.ok ? res.blob() : null;
      };

      await Promise.all(
        rawUrls.map(async (raw) => {
          try {
            const meta = metaByUrl.get(raw) ?? {};
            const declared = knownSize(meta.size);
            // One link request per file at most: a refusal is not retried under another reason.
            let asked = false;
            const streamOnce = async (): Promise<boolean> => {
              if (asked) return false;
              asked = true;
              return stream(raw);
            };
            // Video and audio are streamed without being downloaded here whenever a link can be had.
            // Other types stay on the data: path (an interface's own JS can fetch() a data: URI; a
            // link to the signed proxy would need CORS it does not send), within the budget.
            if (isMedia(meta.mimeType) && (await streamOnce())) return;
            // A size known to overflow the budget is not even downloaded; it gets a link if one can
            // be had (last resort, whatever its type), or stays unresolved.
            if (declared !== null && !reserve(declared)) {
              await streamOnce();
              return;
            }
            let held = declared ?? 0;
            // Whatever ends this file's resolution without inlining it gives the bytes back.
            const release = () => {
              reserved -= held;
              held = 0;
            };
            try {
              const blob = await fetchBlob(raw);
              // The declared size is data (written by an agent, a workflow, a table cell) and can be
              // wrong or stale: the bytes decide, and the reservation follows them.
              release();
              if (!blob) return;
              if (isMedia(blob.type) && (await streamOnce())) return;
              if (!reserve(blob.size)) {
                await streamOnce();
                return;
              }
              held = blob.size;
              await inline(raw, blob);
            } catch {
              // Left unresolved, like any failed file below; its bytes go back to the others.
              release();
            }
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

  // Identity changes when the resolved set changes, so the iframe HTML memo recomputes. A value
  // is a data: URI (small files) or a relative signed link (media and large files).
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

'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { FileText, Download } from 'lucide-react';
import {
  type FileRef,
  findFileRefs,
  isFileRef,
  isImageFile,
  isAudioFile,
  isVideoFile,
  fileRefToUrl,
} from '@/lib/api/orchestrator/file.service';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

/**
 * Renders previews for any {@link FileRef} found in a tool result.
 *
 * <p>Used by chat tool cards to surface binary outputs that the catalog
 * dehydrates into FileRefs (Gemini image-gen, OpenAI image-gen, ElevenLabs
 * audio, PDF generators, …). The agent never sees the megabytes of base64
 * - it sees the FileRef metadata; this component is what actually shows
 * the user the image / audio / video / file produced.
 *
 * <p>Detection walks the parsed JSON result via {@link findFileRefs} so
 * deeply-nested FileRefs are picked up the same way as top-level
 * `metadata.attachments[]` entries. We dedupe by storage path so an asset
 * carried both inline and in `attachments[]` only renders once.
 */
export function ToolResultFileRefPreviews({ rawResult }: { rawResult: unknown }) {
  const refs = React.useMemo(() => collectFileRefs(rawResult), [rawResult]);

  if (refs.length === 0) return null;

  return (
    <div className="mt-2 flex flex-wrap gap-2">
      {refs.map((ref, idx) => (
        <FileRefCard key={`${ref.path || 'no-path'}-${idx}`} fileRef={ref} />
      ))}
    </div>
  );
}

function FileRefCard({ fileRef }: { fileRef: FileRef }) {
  const t = useTranslations('fileChip');
  const displayName = fileRef.name || t('defaultName');
  // Source URL strategy: prefer the opaque, id-based URL
  // (`/api/proxy/files/by-id/{id}/raw`) - no tenant id / s3 key, and the
  // workflow inspector + chat converge on the same network path. The
  // dehydrator's stamped `url` (also opaque post-cutover) is the fallback
  // for a FileRef without an id (e.g. tests, legacy results).
  //
  // The byte fetch is header-authenticated and rendered from an in-memory
  // blob: URL (no session token in any URL - see useAuthedObjectUrl). The
  // single inline blob serves the <img>/<audio>/<video> preview, the
  // open-in-new-tab anchor, AND the download anchor (the `download` attribute
  // forces a save regardless of the blob's disposition).
  const stampedUrl = (fileRef as FileRef & { url?: string }).url;
  const { url: blobUrl } = useAuthedObjectUrl(fileRefToUrl(fileRef, { inline: true }) || stampedUrl);
  const previewUrl = blobUrl || undefined;
  const downloadUrl = blobUrl || undefined;

  // SVG security gate: SVGs from third-party catalog APIs (Gemini, OpenAI,
  // any future image-gen API) can carry inline JavaScript via
  // <script>/<foreignObject>/onload attributes. Rendering one as <img> is
  // safe (the browser blocks script execution there), but a click that
  // opens the SVG document directly would let the browser parse it AS A
  // DOCUMENT and execute embedded JS in the storage origin. Force route
  // SVGs through a download chip with `download` attribute so the click
  // saves the file rather than opening it.
  const isSvg = fileRef.mimeType === 'image/svg+xml' || fileRef.mimeType === 'image/svg';

  // mime-driven renderer choice keeps the same visual contract for all
  // catalog binary outputs - image preview, audio player, video player,
  // generic download chip otherwise. No bespoke per-type card.
  if (isImageFile(fileRef) && !isSvg) {
    // The image preview <a> opens in a new tab. The bottom download chip is a
    // SECOND anchor over the same card with the `download` attribute so the
    // browser saves instead of opens - common pattern for chat-card binaries
    // where users typically want to keep the asset, not just glance at it.
    return (
      <div
        className="group relative overflow-hidden rounded-lg border border-slate-200 dark:border-slate-700 hover:border-blue-400 dark:hover:border-blue-500 transition-colors"
        title={`${displayName} (${formatBytes(fileRef.size)})`}
      >
        <a
          href={previewUrl || '#'}
          target="_blank"
          rel="noopener noreferrer"
          className="block"
        >
          {previewUrl ? (
            <img
              src={previewUrl}
              alt={displayName}
              className="max-w-full sm:max-w-[240px] max-h-[200px] object-cover"
              loading="lazy"
            />
          ) : (
            <div className="w-[160px] h-[120px] flex items-center justify-center bg-slate-100 dark:bg-slate-800 text-xs text-slate-500">
              (no preview url)
            </div>
          )}
        </a>
        {/* Bottom CTA - filename + download chip on the same gradient strip.
            Download anchor stops propagation so the surrounding preview <a> doesn't
            open the image in a new tab when the user only wanted to save it. */}
        <div className="absolute bottom-0 left-0 right-0 flex items-center justify-between gap-2 bg-gradient-to-t from-black/80 to-transparent px-2 py-1.5">
          <span className="text-[10px] text-white truncate flex-1">{displayName}</span>
          {downloadUrl && (
            <a
              href={downloadUrl}
              download={fileRef.name || ''}
              onClick={(e) => e.stopPropagation()}
              aria-label={t('downloadAria', { name: displayName })}
              className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-white/15 hover:bg-white/30 text-white text-[10px] flex-shrink-0 transition-colors"
            >
              <Download className="h-3 w-3" aria-hidden="true" />
              <span>{t('download')}</span>
            </a>
          )}
        </div>
      </div>
    );
  }

  if (isAudioFile(fileRef)) {
    return (
      <div className="flex flex-col gap-1 p-2 rounded-lg border border-slate-200 dark:border-slate-700">
        <span className="text-xs text-theme-secondary truncate max-w-full sm:max-w-[280px]">{fileRef.name || 'audio'}</span>
        {previewUrl ? (
          <audio controls className="max-w-full sm:max-w-[280px]" src={previewUrl} preload="metadata" />
        ) : (
          <span className="text-xs text-slate-400 italic">no preview url</span>
        )}
      </div>
    );
  }

  if (isVideoFile(fileRef)) {
    return (
      <div className="flex flex-col gap-1 p-2 rounded-lg border border-slate-200 dark:border-slate-700">
        <span className="text-xs text-theme-secondary truncate max-w-full sm:max-w-[280px]">{fileRef.name || 'video'}</span>
        {previewUrl ? (
          <video controls className="max-w-full sm:max-w-[280px] max-h-[200px]" src={previewUrl} preload="metadata" />
        ) : (
          <span className="text-xs text-slate-400 italic">no preview url</span>
        )}
      </div>
    );
  }

  // Generic download chip - covers PDF, ZIP, SVG (forced through here for
  // XSS reasons), anything unknown.
  //
  // The `download` attribute is applied SVG-only: SVG must be saved (not
  // opened) because parsing one as a document executes embedded JS in the
  // storage origin. For PDF / ZIP / other types, omitting `download`
  // restores the friendlier "open in new tab" behaviour (browser previews
  // the PDF, the OS preview dialog opens the ZIP, etc.). Same-origin
  // proxy URL means the `download` attribute is honoured by the browser
  // when present.
  return (
    <a
      href={downloadUrl || '#'}
      target="_blank"
      rel="noopener noreferrer"
      {...(isSvg ? { download: fileRef.name || '' } : {})}
      aria-disabled={downloadUrl ? undefined : true}
      aria-label={`${isSvg ? 'Download' : 'Open'} ${fileRef.name || 'file'} (${fileRef.mimeType}, ${formatBytes(fileRef.size)})`}
      className={`flex items-center gap-2 px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 ${downloadUrl ? 'hover:border-blue-400 dark:hover:border-blue-500' : 'opacity-60 cursor-not-allowed'} transition-colors`}
      onClick={downloadUrl ? undefined : (e) => e.preventDefault()}
    >
      <FileText className="h-4 w-4 text-slate-500 flex-shrink-0" />
      <div className="flex flex-col min-w-0">
        <span className="text-xs font-medium truncate max-w-full sm:max-w-[200px]">{fileRef.name || 'file'}</span>
        <span className="text-[10px] text-slate-500">{fileRef.mimeType} · {formatBytes(fileRef.size)}</span>
      </div>
      {downloadUrl && <Download className="h-3.5 w-3.5 text-slate-400 ml-1 flex-shrink-0" aria-hidden="true" />}
    </a>
  );
}

/**
 * Find all FileRefs in the tool result. The current GroupedToolCard query
 * unwraps {@code data.content || ''} via react-query {@code select}, so
 * both live-stream and persisted-load reach this function with a JSON
 * string of the catalog response. We parse + walk; the walk covers both
 * top-level {@code metadata.attachments[]} (after the controller spreads
 * metadata) and nested {@code result.candidates[…].inlineData.data}
 * FileRef Maps in a single pass.
 *
 * <p>The {@code parsed.content} re-parse below is defensive: if a future
 * caller hands the WHOLE {@code FullToolResult} object directly (no
 * {@code select} unwrap), this branch still finds the nested FileRefs.
 * Today's call sites don't trigger it; it's pure resilience against
 * future shape changes.
 *
 * <p>Dedupes by storage {@code path} so an asset carried both inline and
 * in {@code metadata.attachments[]} renders once.
 */
function collectFileRefs(rawResult: unknown): FileRef[] {
  if (!rawResult) return [];
  let parsed: unknown = rawResult;
  if (typeof rawResult === 'string') {
    try { parsed = JSON.parse(rawResult); } catch { return []; }
  }
  // Direct FileRef shortcut
  if (isFileRef(parsed)) return [parsed as FileRef];

  const found = findFileRefs(parsed).map((entry) => entry.fileRef);

  // Persisted-load shape: the catalog payload sits as a JSON string under
  // `content`. Re-parse and walk, then merge with what we found at the
  // outer level (e.g. `attachments[]` already extracted).
  if (parsed && typeof parsed === 'object') {
    const innerContent = (parsed as { content?: unknown }).content;
    if (typeof innerContent === 'string') {
      try {
        const innerParsed = JSON.parse(innerContent);
        for (const entry of findFileRefs(innerParsed)) {
          found.push(entry.fileRef);
        }
      } catch {
        // Not JSON - leave alone (could be plain markdown text).
      }
    }
  }

  // Dedupe by path (each asset is uploaded once → unique path per asset)
  const seen = new Set<string>();
  const unique: FileRef[] = [];
  for (const ref of found) {
    const key = ref.path || JSON.stringify(ref);
    if (seen.has(key)) continue;
    seen.add(key);
    unique.push(ref);
  }
  return unique;
}

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  let value = bytes;
  while (value >= 1024 && i < units.length - 1) { value /= 1024; i++; }
  return `${value.toFixed(value < 10 && i > 0 ? 1 : 0)} ${units[i]}`;
}

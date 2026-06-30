'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronLeft, ChevronRight, Download, File as FileIcon } from 'lucide-react';
import { getFileUrlById } from '@/lib/api/orchestrator/file.service';
import { apiClient } from '@/lib/api/api-client';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';
import LoadingSpinner from '@/components/LoadingSpinner';
import MarkdownRender from '@/components/MarkdownRender';
import { CodePreview } from '@/components/files/CodePreview';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import {
  detectPreviewKind,
  resolveMediaMimeType,
  isTextualKind,
  parseDelimited,
  selectTextRenderMode,
  MAX_INLINE_TEXT_BYTES,
  MAX_TEXT_PREVIEW_CHARS,
  MAX_CSV_PREVIEW_ROWS,
  type PreviewKind,
} from '@/lib/files/filePreview';

interface FileDetailViewProps {
  /**
   * S3 key (= FileRef.path = storage.storage.s3_key). Display-only (shown in the "Path"
   * metadata row + used as a filename fallback). The media itself always loads via the
   * opaque {@link entryId}-based {@code /api/proxy/files/by-id/{id}/raw} endpoint, never by
   * key - a caller with no {@link entryId} renders the placeholder, not the media.
   */
  s3Key?: string;
  /**
   * Storage row UUID - the preferred, opaque handle. The preview + download serve through
   * the org-scoped {@code /api/files/by-id/{id}/raw} endpoint, which streams object-storage
   * files AND inline BINARY/TEXT/JSON rows alike (no tenant id / no s3 key in the URL).
   */
  entryId?: string;
  fileName?: string;
  mimeType?: string;
  sizeBytes?: number;
  createdAt?: string;
  /**
   * Called when the user clicks the back chevron. The parent should swap
   * the side-panel tab content back to the Files list (typically focused on
   * this same file so the row is highlighted). Side-panel "tab navigation"
   * pattern - the tab id stays {@code 'files-panel'}, only its content
   * changes, so we never end up with parallel duplicate tabs.
   */
  onBack: () => void;
  /**
   * Optional prev/next navigation between sibling files. When provided, ‹ ›
   * buttons appear in the header; the parent owns the list and re-opens the
   * detail view for the adjacent file. {@code undefined} hides/disables the
   * respective button (e.g. single-file contexts like the visualize card pass
   * neither, so no arrows show).
   */
  onPrev?: () => void;
  onNext?: () => void;
  /**
   * Chromeless mode for the full-page Files viewer: drop the internal header bar
   * (back / prev-next / download) and the bottom download CTA - those actions
   * live in the app header instead - and render the media full-bleed (no
   * bordered, fixed-height box) so it shows large. Defaults to {@code false},
   * preserving the boxed, self-chromed layout used by the side panel and the
   * chat visualize cards.
   */
  chromeless?: boolean;
  /**
   * Whether the size / type / created / path metadata grid is shown. Defaults to
   * {@code true}. In chromeless mode the app-header Info toggle drives this so the
   * media can occupy the full viewport when details are hidden.
   */
  showMetadata?: boolean;
}

/**
 * Side-panel detail view for a single stored file. Auto-opened by
 * {@link ImageGenerationVisualizeCard} / {@code FileVisualizeCard} after a
 * visualize, and navigated to from {@code StorageExplorerTab} on row click.
 *
 * <p>The media loads via the opaque, org-scoped {@code /api/proxy/files/by-id/{id}/raw}
 * endpoint addressed by the storage row UUID - never by the s3 key, so the URL leaks no
 * tenant id. The endpoint streams object-storage files AND inline BINARY/TEXT/JSON rows,
 * so a single URL covers every media type. Callers must pass {@link entryId} (the storage
 * row id); the {@link s3Key} is display-only.
 */
export function FileDetailView({
  s3Key,
  entryId,
  fileName,
  mimeType,
  sizeBytes,
  createdAt,
  onBack,
  onPrev,
  onNext,
  chromeless = false,
  showMetadata = true,
}: FileDetailViewProps) {
  const t = useTranslations('fileDetail');
  const [downloading, setDownloading] = React.useState(false);
  const [downloadError, setDownloadError] = React.useState<string | null>(null);

  // Preview kind drives what renders inline: image/video/audio/pdf load via the
  // opaque media URL; json/csv/text fetch their content and render client-side;
  // everything else falls back to the metadata + download placeholder.
  const kind = detectPreviewKind(mimeType, fileName);
  const needsMediaUrl = kind === 'image' || kind === 'video' || kind === 'audio' || kind === 'pdf';
  const displayName = fileName ?? s3Key?.split('/').pop() ?? t('defaultName');

  // Serve the media via the opaque, org-scoped /api/files/by-id/{id}/raw endpoint addressed by the
  // storage row UUID - never by the s3 key, so the URL leaks no tenant id. The endpoint streams BOTH
  // object-storage files AND inline BINARY/TEXT/JSON rows, so a single opaque URL covers every media
  // type. A caller that only holds the s3 key (no entryId) cannot render - every producer carries the
  // id post-cutover; the s3Key is display-only here. The bytes are fetched with a Bearer header and
  // rendered from an in-memory blob: URL - the session token is NEVER placed in the URL (the leak the
  // opaque-URL cutover did not close); see useAuthedObjectUrl.
  const { url: mediaUrl } = useAuthedObjectUrl(
    entryId && needsMediaUrl ? getFileUrlById(entryId, { inline: true }) : null,
    // Re-type a generic (octet-stream) blob from the filename so a PDF/video whose stored
    // mime_type is missing renders in the iframe/<video> instead of a broken link.
    resolveMediaMimeType(mimeType, fileName),
  );

  const handleDownload = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      // Download through the opaque id-based endpoint (no s3 key in the URL). Auth travels in the
      // Authorization header, never the URL - resolved fresh at click time.
      const url = entryId ? getFileUrlById(entryId, { inline: false }) : null;
      if (!url) { setDownloadError(t('downloadFailed')); return; }
      const tok = await apiClient.getTokenProvider()?.();
      const headers: Record<string, string> = { ...getActiveOrgHeaderForRequest() };
      if (tok) headers['Authorization'] = `Bearer ${tok}`;
      const res = await fetch(url, { headers });
      if (!res.ok) {
        setDownloadError(`HTTP ${res.status}`);
        return;
      }
      const blob = await res.blob();
      const objectUrl = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = objectUrl;
      a.download = fileName ?? displayName ?? 'download';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(objectUrl);
    } catch (err) {
      // Surface 401 (token expired), 403 (cross-tenant), network errors. Without this, the spinner
      // just vanishes and the user has no signal.
      const message = err instanceof Error ? err.message : t('downloadFailed');
      setDownloadError(message);
    } finally {
      setDownloading(false);
    }
  };

  // Preview body - shared by both the boxed (side-panel/visualize) layout and
  // the chromeless full-page Files layout. In chromeless mode the media fills its
  // flex region (max-h-full) so it shrinks to leave the metadata visible under it
  // without page scroll; the boxed layout keeps its fixed viewport-relative caps.
  const imageMaxH = chromeless ? 'max-h-full' : 'max-h-[82vh]';
  const previewBody = (
    <>
      {needsMediaUrl && !mediaUrl && entryId ? (
          // Brief spinner while the authenticated blob fetch resolves (mediaUrl is null until then).
          // A call with no entryId falls through to the placeholder instead of spinning forever.
          <div className="py-12"><LoadingSpinner size="sm" /></div>
        ) : kind === 'image' && mediaUrl ? (
          /* eslint-disable-next-line @next/next/no-img-element */
          <img
            src={mediaUrl}
            alt={displayName}
            className={`max-w-full ${imageMaxH} object-contain rounded shadow-md bg-black/5 dark:bg-white/5`}
          />
        ) : kind === 'video' && mediaUrl ? (
          <video
            controls
            preload="metadata"
            src={mediaUrl}
            className={`max-w-full ${chromeless ? 'max-h-full' : 'max-h-[70vh]'} rounded shadow-md bg-black/5 dark:bg-white/5`}
          />
        ) : kind === 'audio' && mediaUrl ? (
          <audio
            controls
            preload="metadata"
            src={mediaUrl}
            className="w-full mt-4"
          />
        ) : kind === 'pdf' && mediaUrl ? (
          <iframe
            src={mediaUrl}
            title={displayName}
            className={`w-full ${chromeless ? 'h-full' : 'h-[75vh]'} rounded border border-theme bg-white`}
          />
        ) : isTextualKind(kind) && entryId ? (
          <DocumentTextPreview
            entryId={entryId}
            kind={kind}
            fileName={fileName}
            mimeType={mimeType}
            sizeBytes={sizeBytes}
          />
        ) : (
          <div className="flex flex-col items-center gap-2 text-[var(--text-secondary)] py-12">
            <FileIcon className="h-12 w-12" />
            <span className="text-sm">{displayName}</span>
            <span className="text-xs">{t('noInlinePreview')}</span>
          </div>
        )}
    </>
  );

  // Metadata grid - size / type / created / path. Toggleable via {@link showMetadata}
  // (the app-header Info button drives it in chromeless mode).
  const metadataGrid = showMetadata ? (
    <div className="w-full max-w-md mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-xs">
      {sizeBytes != null && (
        <>
          <span className="text-[var(--text-secondary)]">{t('metadataSize')}</span>
          <span className="font-mono text-[var(--text-primary)]">{formatBytes(sizeBytes)}</span>
        </>
      )}
      {mimeType && (
        <>
          <span className="text-[var(--text-secondary)]">{t('metadataType')}</span>
          <span className="font-mono text-[var(--text-primary)]">{mimeType}</span>
        </>
      )}
      {createdAt && (
        <>
          <span className="text-[var(--text-secondary)]">{t('metadataCreated')}</span>
          <span className="text-[var(--text-primary)]">{formatUtcDateTime(createdAt)}</span>
        </>
      )}
      {s3Key && (
        <>
          <span className="text-[var(--text-secondary)]">{t('metadataPath')}</span>
          <span className="font-mono text-[var(--text-primary)] break-all" title={s3Key}>
            {s3Key}
          </span>
        </>
      )}
    </div>
  ) : null;

  const errorNotice = downloadError ? (
    <p
      role="alert"
      className="w-full max-w-md text-xs text-[var(--danger,#dc2626)] text-center"
    >
      {downloadError}
    </p>
  ) : null;

  // Chromeless: full-page Files viewer. No internal header bar, no bottom CTA
  // (those actions are in the app header) - just the media + the metadata.
  if (chromeless) {
    // Visual media (image / video / pdf / audio): bound the viewer to the space
    // left under the app header + page padding (≈ 100vh − 8.5rem) and let the
    // media flex to fill it, so it shrinks just enough to keep the size / type /
    // created / path metadata visible right under it WITHOUT page scroll. The
    // media itself is capped with max-h-full / h-full above.
    if (needsMediaUrl) {
      return (
        <div className="w-full flex flex-col items-center gap-3 h-[calc(100vh-8.5rem)]">
          <div className="flex-1 min-h-0 w-full flex items-center justify-center overflow-hidden">
            {previewBody}
          </div>
          {metadataGrid}
          {errorNotice}
        </div>
      );
    }
    // Textual previews / no-preview placeholder: those carry their own internal
    // scroll (or are short), so they flow in the page scroll with the metadata
    // under them.
    return (
      <div className="w-full flex flex-col items-center gap-4 py-2">
        {previewBody}
        {metadataGrid}
        {errorNotice}
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      {/* Header - back nav + filename + download */}
      <div className="flex-shrink-0 flex items-center gap-2 px-3 py-2 border-b border-theme bg-[var(--bg-secondary)]">
        <button
          type="button"
          onClick={onBack}
          className="flex items-center gap-1 px-2 py-1 rounded text-sm text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-tertiary)] transition-colors"
          title={t('backToFiles')}
        >
          <ChevronLeft className="h-4 w-4" />
          {t('filesBackLabel')}
        </button>
        <span className="text-sm font-medium truncate flex-1" title={displayName}>
          {displayName}
        </span>
        {(onPrev || onNext) && (
          <div className="flex items-center gap-0.5">
            <button
              type="button"
              onClick={onPrev}
              disabled={!onPrev}
              aria-label={t('previousFile')}
              title={t('previousFile')}
              className="p-1.5 rounded hover:bg-[var(--bg-tertiary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            >
              <ChevronLeft className="h-4 w-4" />
            </button>
            <button
              type="button"
              onClick={onNext}
              disabled={!onNext}
              aria-label={t('nextFile')}
              title={t('nextFile')}
              className="p-1.5 rounded hover:bg-[var(--bg-tertiary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        )}
        <button
          type="button"
          onClick={handleDownload}
          disabled={downloading}
          aria-label={t('download', { name: displayName })}
          aria-busy={downloading}
          className="p-1.5 rounded hover:bg-[var(--bg-tertiary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] disabled:opacity-50 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]"
          title={t('download', { name: displayName })}
        >
          {downloading ? <LoadingSpinner size="xs" /> : <Download className="h-4 w-4" />}
        </button>
      </div>

      {/* Body - preview + metadata + bottom CTA */}
      <div className="flex-1 overflow-auto p-4 flex flex-col items-center gap-4">
        {previewBody}
        {metadataGrid}
        {/* Bottom CTA - duplicates the header icon as a full-width action so it's
            obvious on non-image previews and reachable without scrolling back up. */}
        <button
          type="button"
          onClick={handleDownload}
          disabled={downloading}
          aria-label={t('download', { name: displayName })}
          aria-busy={downloading}
          className="mt-2 w-full max-w-md inline-flex items-center justify-center gap-2 px-4 py-2 rounded-md bg-[var(--accent)] text-white text-sm font-medium hover:opacity-90 disabled:opacity-50 transition-opacity focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)]/40"
        >
          {downloading ? (
            <LoadingSpinner size="xs" />
          ) : (
            <Download className="h-4 w-4" aria-hidden="true" />
          )}
          <span className="truncate min-w-0">
            {downloading ? t('downloading') : t('download', { name: displayName })}
          </span>
        </button>
        {errorNotice}
      </div>
    </div>
  );
}

/**
 * Inline preview for textual files (JSON / CSV / Markdown / plain-text & code).
 * Fetches the raw content through the opaque, org-scoped by-id endpoint (header
 * auth, no token in the URL) and renders it richly: JSON pretty-printed AND
 * syntax-highlighted, code/markup syntax-highlighted by language, Markdown
 * rendered to formatted HTML, CSV/TSV as a table, flat text verbatim. Bails to a
 * "too large" notice above {@link MAX_INLINE_TEXT_BYTES} and truncates very long
 * content to protect the DOM.
 */
function DocumentTextPreview({
  entryId,
  kind,
  fileName,
  mimeType,
  sizeBytes,
}: {
  entryId: string;
  kind: PreviewKind;
  fileName?: string;
  mimeType?: string;
  sizeBytes?: number;
}) {
  const t = useTranslations('fileDetail');
  const [text, setText] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState(false);
  const tooLarge = sizeBytes != null && sizeBytes > MAX_INLINE_TEXT_BYTES;

  React.useEffect(() => {
    if (tooLarge) { setLoading(false); return; }
    let cancelled = false;
    setLoading(true);
    setError(false);
    (async () => {
      try {
        const tokenProvider = apiClient.getTokenProvider();
        const tok = tokenProvider ? await tokenProvider() : null;
        const headers: Record<string, string> = { ...getActiveOrgHeaderForRequest() };
        if (tok) headers['Authorization'] = `Bearer ${tok}`;
        const res = await fetch(getFileUrlById(entryId, { inline: true }), { headers });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const raw = await res.text();
        if (!cancelled) setText(raw);
      } catch {
        if (!cancelled) setError(true);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [entryId, tooLarge]);

  if (tooLarge) return <PreviewNotice text={t('previewTooLarge')} />;
  if (loading) return <div className="py-12"><LoadingSpinner size="sm" /></div>;
  if (error || text == null) return <PreviewNotice text={t('previewError')} />;

  // CSV/TSV → table (own row-based cap on the raw text, no char truncation).
  if (kind === 'csv') {
    return <CsvTable text={text} truncatedLabel={t('csvRowsTruncated', { max: MAX_CSV_PREVIEW_ROWS })} />;
  }

  // JSON → pretty-printed when parseable, raw otherwise; everything else verbatim.
  let body = text;
  if (kind === 'json') {
    try {
      body = JSON.stringify(JSON.parse(text), null, 2);
    } catch {
      // Malformed JSON - show the raw bytes as-is.
    }
  }
  const truncated = body.length > MAX_TEXT_PREVIEW_CHARS;
  const shown = truncated ? body.slice(0, MAX_TEXT_PREVIEW_CHARS) : body;
  const truncatedNotice = truncated
    ? <p className="mt-1 text-xs text-[var(--text-secondary)]">{t('previewTruncated')}</p>
    : null;

  // Pure decision (tested in filePreview.test): markdown rendered, JSON/code
  // syntax-highlighted, big/flat content as a plain monospace block.
  const render = selectTextRenderMode(kind, fileName, mimeType, shown.length);

  if (render.mode === 'markdown') {
    return (
      <div className="w-full">
        <div className="w-full max-h-[70vh] overflow-auto rounded-lg border border-theme bg-[var(--bg-secondary)] px-4 py-3 text-left">
          <MarkdownRender text={shown} />
        </div>
        {truncatedNotice}
      </div>
    );
  }

  if (render.mode === 'highlight') {
    return (
      <div className="w-full">
        <CodePreview code={shown} language={render.language} wrap={render.wrap} showLineNumbers={render.lineNumbers} />
        {truncatedNotice}
      </div>
    );
  }

  return (
    <div className="w-full">
      <pre className="w-full max-h-[70vh] overflow-auto rounded-lg border border-theme bg-[var(--bg-secondary)] p-3 text-xs font-mono whitespace-pre-wrap break-words text-[var(--text-primary)]">
        {shown}
      </pre>
      {truncatedNotice}
    </div>
  );
}

function PreviewNotice({ text }: { text: string }) {
  return (
    <div className="flex flex-col items-center gap-2 text-[var(--text-secondary)] py-12">
      <FileIcon className="h-12 w-12" />
      <span className="text-xs">{text}</span>
    </div>
  );
}

/** Renders parsed CSV/TSV as a bordered, scrollable table (first row = header). */
function CsvTable({ text, truncatedLabel }: { text: string; truncatedLabel: string }) {
  const rows = React.useMemo(() => parseDelimited(text), [text]);
  if (rows.length === 0) return null;
  const header = rows[0];
  const bodyRows = rows.slice(1, 1 + MAX_CSV_PREVIEW_ROWS);
  const truncated = rows.length - 1 > MAX_CSV_PREVIEW_ROWS;

  return (
    <div className="w-full">
      <div className="w-full max-h-[70vh] overflow-auto rounded border border-theme">
        <table className="w-full text-xs border-collapse">
          <thead className="bg-[var(--bg-secondary)] sticky top-0">
            <tr>
              {header.map((cell, i) => (
                <th key={i} className="px-2 py-1.5 text-left font-medium text-[var(--text-secondary)] border-b border-theme whitespace-nowrap">
                  {cell}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {bodyRows.map((row, ri) => (
              <tr key={ri} className="even:bg-[var(--bg-secondary)]/40">
                {header.map((_, ci) => (
                  <td key={ci} className="px-2 py-1 text-[var(--text-primary)] border-b border-theme align-top">
                    {row[ci] ?? ''}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {truncated && (
        <p className="mt-1 text-xs text-[var(--text-secondary)]">{truncatedLabel}</p>
      )}
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

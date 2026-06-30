'use client';

import * as React from 'react';
import { Download } from 'lucide-react';
import { useDraggable } from '@dnd-kit/core';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { getFileUrlById } from '@/lib/api/orchestrator/file.service';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';
import { getFileTypeIcon } from '@/lib/files/fileTypes';
import { detectPreviewKind, resolveMediaMimeType } from '@/lib/files/filePreview';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

interface FileCardProps {
  entry: StorageExplorerEntry;
  selected: boolean;
  onToggleSelect: (id: string) => void;
  onOpen: (entry: StorageExplorerEntry) => void;
  onDownload: (entry: StorageExplorerEntry) => void;
  downloadLabel: string;
  /**
   * V313: enables drag-to-move (dnd-kit draggable) inside the folder-aware Files
   * browser. The side-panel explorer leaves it off so the card stays a plain tile.
   */
  draggable?: boolean;
}

/**
 * Thumbnail tile in the grid view. Images render an inline preview via the
 * opaque, org-scoped {@code /files/by-id/{id}/raw} endpoint (addressed by the
 * storage row id - never by s3 key); everything else shows a large themed
 * file-type icon. Click opens the detail view; hover reveals the download
 * button; the checkbox (top-left) survives pagination via the parent's map.
 */
export const FileCard = React.memo(function FileCard({
  entry,
  selected,
  onToggleSelect,
  onOpen,
  onDownload,
  downloadLabel,
  draggable = false,
}: FileCardProps) {
  const displayName = entry.fileName || entry.contentType || 'Unnamed';

  // dnd-kit draggable (V313 folder-aware browser only). When disabled the hook
  // still runs (hooks can't be conditional) but we don't spread its listeners, so
  // the card behaves exactly as before for the side-panel explorer.
  const { setNodeRef, attributes, listeners, isDragging } = useDraggable({
    id: entry.id,
    data: { type: 'file', entry },
  });

  return (
    <div
      ref={draggable ? setNodeRef : undefined}
      {...(draggable ? attributes : {})}
      {...(draggable ? listeners : {})}
      className={`group relative rounded-xl border bg-theme-secondary overflow-hidden cursor-pointer transition-colors ${
        selected
          ? 'border-[var(--accent-primary)] ring-2 ring-[var(--accent-primary)]'
          : 'border-theme hover:border-[var(--accent-primary)]'
      } ${draggable && isDragging ? 'opacity-50' : ''}`}
      onClick={() => onOpen(entry)}
      title={displayName}
    >
      {/* Selection checkbox - always visible when selected, on hover otherwise */}
      <div
        className={`absolute top-2 left-2 z-10 transition-opacity ${selected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}
        onClick={(e) => e.stopPropagation()}
        onPointerDown={(e) => e.stopPropagation()}
      >
        <input
          type="checkbox"
          checked={selected}
          onChange={() => onToggleSelect(entry.id)}
          className="rounded border-slate-300 dark:border-slate-600 bg-white/80 dark:bg-slate-900/80"
        />
      </div>

      {/* Download - top-right on hover */}
      <button
        type="button"
        onClick={(e) => { e.stopPropagation(); onDownload(entry); }}
        onPointerDown={(e) => e.stopPropagation()}
        aria-label={downloadLabel}
        title={downloadLabel}
        className="absolute top-2 right-2 z-10 opacity-0 group-hover:opacity-100 transition-opacity p-1.5 rounded-lg bg-white/85 dark:bg-slate-900/85 text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
      >
        <Download className="h-3.5 w-3.5" />
      </button>

      {/* Thumbnail / icon tile */}
      <div className="aspect-[4/3] bg-theme-tertiary flex items-center justify-center overflow-hidden">
        <FileThumb entry={entry} />
      </div>

      {/* Footer - type icon + name + size · date */}
      <div className="flex items-center gap-2 px-2.5 py-2 border-t border-theme">
        <span className="flex-shrink-0">{getFileTypeIcon(entry, 'h-4 w-4')}</span>
        <div className="min-w-0 flex-1">
          <div className="text-sm text-theme-primary truncate">{displayName}</div>
          <div className="text-xs text-theme-muted truncate">
            {entry.formattedSize}
            {entry.createdAt ? ` · ${formatUtcDate(entry.createdAt)}` : ''}
          </div>
        </div>
      </div>
    </div>
  );
});

/**
 * Mount the child only once the tile scrolls within {@code rootMargin} of the
 * viewport, then keep it mounted (no remount flicker on scroll-back). Keeps the
 * grid light: 50 PDFs/videos don't all spin up iframes/video elements at once.
 */
function useInView<T extends Element>(rootMargin = '300px'): [React.RefObject<T>, boolean] {
  const ref = React.useRef<T>(null);
  // Start eagerly visible when there's no IntersectionObserver (jsdom / very old
  // browsers) so previews still render; otherwise wait for the tile to scroll in.
  const [inView, setInView] = React.useState(() => typeof IntersectionObserver === 'undefined');
  React.useEffect(() => {
    const el = ref.current;
    if (!el || inView) return;
    const obs = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          setInView(true);
          obs.disconnect();
        }
      },
      { rootMargin },
    );
    obs.observe(el);
    return () => obs.disconnect();
  }, [inView, rootMargin]);
  return [ref, inView];
}

/**
 * Thumbnail preview. Images load inline; videos show their first frame; PDFs
 * render their first page (toolbar-less iframe). Video/PDF mount lazily when the
 * tile nears the viewport. Everything else (audio, docs, archives, …) shows a
 * large themed file-type icon. The media is {@code pointer-events-none} so the
 * whole tile stays clickable.
 *
 * <p>The bytes are fetched with a Bearer header and rendered from an in-memory
 * blob: URL - the session token is NEVER placed in the URL (see
 * {@link useAuthedObjectUrl}). The media-fragment hashes ({@code #t=0.1},
 * {@code #toolbar=0…}) ride on the blob URL just as they did on the proxy URL.
 */
export function FileThumb({ entry }: { entry: StorageExplorerEntry }) {
  const [containerRef, inView] = useInView<HTMLDivElement>();
  // MIME wins; filename extension is the fallback (same rule as the detail view).
  const kind = detectPreviewKind(entry.mimeType, entry.fileName);
  const isImage = kind === 'image';
  const isVideo = kind === 'video';
  const isPdf = kind === 'pdf';
  const isMedia = isImage || isVideo || isPdf;
  // Image fetches eagerly; video/PDF only once the tile nears the viewport (lazy).
  const wantBytes = !!entry.id && isMedia && (isImage || inView);
  const { url: blobUrl, error } = useAuthedObjectUrl(
    wantBytes ? getFileUrlById(entry.id, { inline: true }) : null,
    // Re-type a generic blob from the filename so a PDF/video stored as octet-stream still
    // renders its first frame/page instead of a broken tile.
    resolveMediaMimeType(entry.mimeType, entry.fileName),
  );

  const iconFallback = (
    <div className="flex items-center justify-center">{getFileTypeIcon(entry, 'h-10 w-10')}</div>
  );

  if (!entry.id || error || !isMedia) return iconFallback;

  if (isImage) {
    return blobUrl ? (
      /* eslint-disable-next-line @next/next/no-img-element */
      <img
        src={blobUrl}
        alt={entry.fileName ?? 'File'}
        loading="lazy"
        className="w-full h-full object-cover"
      />
    ) : iconFallback;
  }

  if (isVideo) {
    return (
      <div ref={containerRef} className="w-full h-full">
        {inView && blobUrl ? (
          <video
            src={`${blobUrl}#t=0.1`}
            muted
            playsInline
            preload="metadata"
            className="w-full h-full object-cover pointer-events-none"
          />
        ) : iconFallback}
      </div>
    );
  }

  // pdf
  return (
    <div ref={containerRef} className="w-full h-full">
      {inView && blobUrl ? (
        <iframe
          // Toolbar-less first page; pointer-events-none keeps the tile clickable.
          src={`${blobUrl}#toolbar=0&navpanes=0&scrollbar=0&view=FitH`}
          title={entry.fileName ?? 'PDF'}
          className="w-full h-full pointer-events-none bg-white"
          tabIndex={-1}
        />
      ) : iconFallback}
    </div>
  );
}

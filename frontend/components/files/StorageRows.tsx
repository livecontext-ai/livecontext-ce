'use client';

import * as React from 'react';
import {
  ChevronDown,
  ChevronRight,
  Check,
  Download,
  FileImage,
  Folder,
} from 'lucide-react';
import { useTranslations } from 'next-intl';
import { storageApi } from '@/lib/api/storage-api';
import type { StorageExplorerEntry, StoragePreview } from '@/lib/api/storage-api';
import { getFileTypeIcon, STORAGE_SOURCE_STYLES, STORAGE_SOURCE_LABELS } from '@/lib/files/fileTypes';
import { fileService, getFileUrlById } from '@/lib/api/orchestrator/file.service';
import { openAuthedFileInNewTab } from '@/lib/utils/url-auth';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';
import LoadingSpinner from '@/components/LoadingSpinner';

/**
 * Compact, narrow-friendly file + folder ROWS for the {@code variant="compact"}
 * density of the shared {@link FilesExplorerBody} (the right side-panel storage
 * explorer + the form-field picker). Extracted out of {@code StorageExplorerTab} so
 * the side panel and any other compact surface render byte-identically - the single
 * source of truth for the row presentation, mirroring the grid's {@code FileCard} /
 * {@code FolderCard} tiles.
 */

interface StorageEntryRowProps {
  entry: StorageExplorerEntry;
  /** Picker mode: row click selects the entry (shows an inline expand-preview chevron). */
  onSelect?: (entry: StorageExplorerEntry) => void;
  /** Explorer mode: row click opens the per-file detail. Mutually exclusive with {@link onSelect}. */
  onNavigate?: (entry: StorageExplorerEntry) => void;
  selectable: boolean;
  selected: boolean;
  onToggleSelect: (id: string) => void;
  expanded: boolean;
  onToggleExpand: (id: string) => void;
  /** Set on the row matching the deep-link focus key so the parent can scroll it into view. */
  rowRef?: React.RefObject<HTMLDivElement | null>;
  /** Visual highlight when this row is the focus target. */
  highlight?: boolean;
}

export const StorageEntryRow = React.memo(function StorageEntryRow({
  entry,
  onSelect,
  onNavigate,
  selectable,
  selected,
  onToggleSelect,
  expanded,
  onToggleExpand,
  rowRef,
  highlight,
}: StorageEntryRowProps) {
  const displayName = entry.fileName || entry.stepKey || entry.contentType || 'Unnamed';

  const handleRowClick = () => {
    // Picker mode keeps the inline-preview UX via the expand chevron; row click selects.
    // Explorer mode navigates to the per-file detail view.
    if (onSelect) {
      onSelect(entry);
    } else if (onNavigate) {
      onNavigate(entry);
    } else if (selectable) {
      onToggleSelect(entry.id);
    }
  };

  return (
    <div ref={rowRef}>
      <div
        className={`px-3 py-2 hover:bg-[var(--bg-tertiary)]/50 transition-colors cursor-pointer ${selected ? 'bg-[var(--bg-tertiary)]/30' : ''} ${highlight ? 'ring-2 ring-blue-400/60 ring-inset bg-blue-50/30 dark:bg-blue-900/15' : ''}`}
        onClick={handleRowClick}
      >
        <div className="flex items-center gap-2">
          {selectable && (
            <input
              type="checkbox"
              checked={selected}
              onChange={() => onToggleSelect(entry.id)}
              onClick={(e) => e.stopPropagation()}
              className="rounded border-slate-300 dark:border-slate-600 flex-shrink-0"
            />
          )}
          {/* Inline expand-preview chevron - only in picker mode (form fields). Explorer
              mode navigates to the FileDetailView on row click instead. */}
          {!onNavigate && (
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); onToggleExpand(entry.id); }}
              className="p-0.5 rounded hover:bg-[var(--bg-tertiary)] transition-colors flex-shrink-0"
            >
              <ChevronDown className={`h-3 w-3 text-[var(--text-secondary)] transition-transform ${expanded ? '' : '-rotate-90'}`} />
            </button>
          )}
          {getFileTypeIcon(entry)}
          <span className="text-sm text-[var(--text-primary)] truncate flex-1" title={displayName}>
            {displayName}
          </span>
          <span className="text-xs text-[var(--text-secondary)] flex-shrink-0">
            {entry.formattedSize}
          </span>
          {entry.sourceType && (
            <span className={`text-[10px] leading-tight px-1.5 py-0.5 rounded-full font-medium flex-shrink-0 ${STORAGE_SOURCE_STYLES[entry.sourceType] ?? 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400'}`}>
              {STORAGE_SOURCE_LABELS[entry.sourceType] ?? entry.sourceType}
            </span>
          )}
          {onSelect && (
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); onSelect(entry); }}
              className="p-1 rounded hover:bg-[var(--bg-tertiary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors flex-shrink-0"
            >
              <Check className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
        {entry.workflowName && (
          <div className="flex items-center gap-1 mt-0.5 pl-[22px]">
            <span className="text-xs text-[var(--text-secondary)] truncate">
              {entry.workflowName}
            </span>
          </div>
        )}
      </div>
      {/* Inline preview only in picker mode - explorer mode uses FileDetailView. */}
      {expanded && !onNavigate && (
        <StorageEntryPreview entryId={entry.id} mimeType={entry.mimeType} />
      )}
    </div>
  );
});

interface StorageFolderRowProps {
  entry: StorageExplorerEntry;
  /** Localised label (manual folder name, or the virtual Epoch/Run/Item/workflow). */
  label: string;
  /** Localised child-count ("3 items"). */
  countLabel: string;
  onOpen: (entry: StorageExplorerEntry) => void;
}

/**
 * Compact, narrow-friendly folder row. Mirrors {@link StorageEntryRow}'s classes but
 * is navigation-only: no checkbox, no drag (both manual and virtual folders just
 * navigate). Clicking anywhere on the row enters the folder. Used in place of the
 * wide mosaic FolderCard the grid density shows.
 */
export const StorageFolderRow = React.memo(function StorageFolderRow({
  entry,
  label,
  countLabel,
  onOpen,
}: StorageFolderRowProps) {
  return (
    <div
      className="px-3 py-2 hover:bg-[var(--bg-tertiary)]/50 transition-colors cursor-pointer flex items-center gap-2"
      onClick={() => onOpen(entry)}
    >
      <Folder className="h-4 w-4 text-[var(--accent-primary)] flex-shrink-0" />
      <span className="text-sm text-[var(--text-primary)] truncate flex-1" title={label}>
        {label}
      </span>
      <span className="text-xs text-[var(--text-secondary)] flex-shrink-0">
        {countLabel}
      </span>
      <ChevronRight className="h-4 w-4 text-[var(--text-muted)] flex-shrink-0" />
    </div>
  );
});

interface StorageEntryPreviewProps {
  entryId: string;
  mimeType: string | null;
}

function StorageEntryPreview({ entryId, mimeType }: StorageEntryPreviewProps) {
  const t = useTranslations('storageExplorer');
  const [preview, setPreview] = React.useState<StoragePreview | null>(null);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState(false);
  const [downloading, setDownloading] = React.useState(false);

  React.useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    storageApi.getEntryPreview(entryId)
      .then((data) => {
        if (!cancelled) setPreview(data);
      })
      .catch(() => {
        if (!cancelled) setError(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [entryId]);

  // Download via file proxy (S3) or storage explorer endpoint (BINARY)
  const handleDownload = React.useCallback(async () => {
    if (!preview) return;
    setDownloading(true);
    try {
      if (preview.s3Key) {
        await fileService.downloadAndSave(
          { id: preview.id ?? entryId, path: preview.s3Key, name: preview.fileName ?? undefined },
          preview.fileName ?? undefined,
        );
      } else if (preview.storageType === 'BINARY') {
        // Open via an authenticated fetch (no token in the URL).
        await openAuthedFileInNewTab(`/api/proxy/storage/explorer/${preview.id}/download`);
      }
    } catch (err) {
      console.error('Download failed:', err);
    } finally {
      setDownloading(false);
    }
  }, [preview, entryId]);

  if (loading) {
    return (
      <div className="px-6 py-3 bg-[var(--bg-secondary)]/50 border-t border-theme">
        <div className="flex items-center gap-2 text-xs text-[var(--text-secondary)]">
          <LoadingSpinner size="xs" />
          {t('previewLoading')}
        </div>
      </div>
    );
  }

  if (error || !preview) {
    return (
      <div className="px-6 py-3 bg-[var(--bg-secondary)]/50 border-t border-theme">
        <span className="text-xs text-[var(--text-secondary)]">{t('previewUnavailable')}</span>
      </div>
    );
  }

  const mime = preview.mimeType ?? mimeType ?? '';
  const looksLikeImage = mime.startsWith('image/');
  // Can display image if we have s3Key (file proxy) or storageType is BINARY/S3_FILE (download endpoint)
  const canDisplayImage = looksLikeImage && (
    preview.s3Key || preview.storageType === 'BINARY' || preview.storageType === 'S3_FILE'
  );
  const canDownload = preview.s3Key || preview.storageType === 'BINARY';

  return (
    <div className="px-6 py-3 bg-[var(--bg-secondary)]/50 border-t border-theme space-y-2">
      {/* Image preview: S3 via file proxy, or BINARY/S3_FILE via download endpoint */}
      {canDisplayImage && (
        <AuthenticatedImage
          s3Key={preview.s3Key}
          entryId={preview.id}
          alt={preview.fileName ?? 'Preview'}
        />
      )}

      {/* Image entry without displayable data (orphan reference) */}
      {looksLikeImage && !canDisplayImage && preview.fileName && (
        <div className="flex items-center gap-2 text-xs text-[var(--text-secondary)]">
          <FileImage className="h-4 w-4 text-purple-500" />
          <span>{preview.fileName}</span>
          <span className="opacity-60">({t('noPreview')})</span>
        </div>
      )}

      {/* JSON skeleton - discriminate the {"_skeletonError":"..."} sentinel so the user
          sees a graceful fallback instead of the raw sentinel JSON. */}
      {preview.skeleton && preview.skeleton.startsWith('{"_skeletonError"') && (
        <span className="text-xs text-[var(--text-secondary)]">{t('skeletonUnavailable')}</span>
      )}
      {preview.skeleton && !preview.skeleton.startsWith('{"_skeletonError"') && (
        <pre className="text-xs text-[var(--text-secondary)] bg-[var(--bg-tertiary)] rounded p-2 overflow-x-auto max-h-40 whitespace-pre-wrap">
          {preview.skeleton}
        </pre>
      )}

      {/* Text preview */}
      {preview.text && !preview.skeleton && (
        <pre className="text-xs text-[var(--text-secondary)] bg-[var(--bg-tertiary)] rounded p-2 overflow-x-auto max-h-40 whitespace-pre-wrap">
          {preview.text.length > 500 ? preview.text.slice(0, 500) + '...' : preview.text}
        </pre>
      )}

      {/* Download button for S3 files and BINARY (all file types including images) */}
      {canDownload && (
        <button
          type="button"
          onClick={handleDownload}
          disabled={downloading}
          className="inline-flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400 hover:underline disabled:opacity-50"
        >
          {downloading ? <LoadingSpinner size="xs" /> : <Download className="h-3 w-3" />}
          {preview.fileName ?? t('download')}
        </button>
      )}

      {/* Fallback: no preview available */}
      {!looksLikeImage && !preview.skeleton && !preview.text && !canDownload && (
        <span className="text-xs text-[var(--text-secondary)]">{t('noPreview')}</span>
      )}
    </div>
  );
}

function AuthenticatedImage({ s3Key, entryId, alt }: { s3Key?: string; entryId: string; alt: string }) {
  // Both the S3_FILE (opaque by-id) and BINARY (storage download) endpoints are fetched
  // with the Bearer + active-org header and rendered from an in-memory blob: URL - the
  // session token is NEVER placed in the URL. The active-org header travels on the fetch
  // so a binary in a non-default workspace resolves cross-org (an <img> could not send it).
  const rawUrl = s3Key
    ? getFileUrlById(entryId, { inline: true })
    : `/api/proxy/storage/explorer/${entryId}/download`;
  const { url: imageUrl, error } = useAuthedObjectUrl(rawUrl);

  if (error) return <span className="text-xs text-[var(--text-secondary)]">Image failed to load</span>;
  if (!imageUrl) return <LoadingSpinner size="xs" />;

  return (
    <img
      src={imageUrl}
      alt={alt}
      className="max-w-full max-h-48 rounded object-contain"
    />
  );
}

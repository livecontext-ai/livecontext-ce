'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import {
  File as FileIcon,
  FileImage,
  FileText,
  FileVideo,
  FileAudio,
  FolderOpen,
  X,
} from 'lucide-react';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { StorageExplorerTab } from '@/app/workflows/builder/components/inspector/StorageExplorerTab';
import { FileDetailView } from '@/components/app/FileDetailView';
import { fileService, getFileUrlById } from '@/lib/api/orchestrator/file.service';
import { storageApi, type StoragePreview } from '@/lib/api/storage-api';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

interface FileVisualizeCardProps {
  /** Storage row id (UUID) - the same file_id the agent's files tool addresses. */
  fileId: string;
  /** Optional display title from the marker (the file's own name is preferred). */
  title?: string;
}

const FILES_TAB_ID = 'files-panel';

/** Lucide icon for a MIME type (display only - mirrors the storage explorer). */
function iconFor(mime: string | undefined) {
  const m = (mime ?? '').toLowerCase();
  if (m.startsWith('image/')) return <FileImage className="h-5 w-5 text-purple-500" />;
  if (m.startsWith('video/')) return <FileVideo className="h-5 w-5 text-pink-500" />;
  if (m.startsWith('audio/')) return <FileAudio className="h-5 w-5 text-teal-500" />;
  if (m.includes('pdf') || m.includes('word') || m.includes('document') || m.includes('text/'))
    return <FileText className="h-5 w-5 text-blue-500" />;
  return <FileIcon className="h-5 w-5 text-slate-400" />;
}

/**
 * Inline chat card for a single stored file, emitted when the agent calls
 * {@code files(action='visualize', file_id=…)} (which returns a
 * {@code [visualize:file:<id>]} marker). Same side-panel mechanism as
 * {@link ImageGenerationVisualizeCard}: click the card to swap the side-panel
 * "Files" tab to {@link FileDetailView} for THIS file (image preview +
 * download); the detail view's back chevron returns to the
 * {@link StorageExplorerTab} list focused on the same file.
 *
 * <p>Addressed by storage {@code fileId}, so we resolve the file's
 * metadata (name / mime / size / s3Key) via the same preview endpoint the
 * explorer uses - the marker only carries the id (the s3 key is never put on
 * the wire by the agent tool).
 */
export function FileVisualizeCard({ fileId, title }: FileVisualizeCardProps) {
  const t = useTranslations('fileVisualize');
  const [preview, setPreview] = useState<StoragePreview | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const sidePanel = useSidePanelSafe();
  // Inline media (image/video/audio with object-storage bytes) loads via a
  // header-authenticated fetch and renders from an in-memory blob: URL - the
  // session token is NEVER placed in the file URL (see useAuthedObjectUrl).
  // null for non-media / inline (no-key) files, which render the chip instead.
  // Computed before the early returns so the hook runs on every render.
  const showsMedia = !!preview?.s3Key && /^(image|video|audio)\//.test(preview?.mimeType ?? '');
  const { url: mediaUrl } = useAuthedObjectUrl(showsMedia ? getFileUrlById(fileId, { inline: true }) : null);

  useEffect(() => {
    // No synchronous setState in the effect body (initial state already covers
    // loading=true / error=null) - all updates happen in the async callbacks,
    // matching StorageEntryPreview's fetch pattern.
    let cancelled = false;
    storageApi.getEntryPreview(fileId)
      .then((data) => { if (!cancelled) setPreview(data); })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : t('failedToLoad'));
      })
      .finally(() => { if (!cancelled) setIsLoading(false); });
    return () => { cancelled = true; };
  }, [fileId, t]);

  const isTabActive = sidePanel?.isOpen && sidePanel?.activeTabId === FILES_TAB_ID;

  /** Switch the panel to the Files list (optionally focused on a file). */
  const openFilesList = useCallback((focusS3Key?: string) => {
    if (!sidePanel) return;
    sidePanel.openTab({
      id: FILES_TAB_ID,
      label: t('filesLabel'),
      icon: <FolderOpen className="w-4 h-4" />,
      preferredWidth: 0.4,
      content: <StorageExplorerTab focusS3Key={focusS3Key} />,
    });
  }, [sidePanel, t]);

  /** Switch the panel to the per-file detail view. */
  const openDetail = useCallback(() => {
    if (!sidePanel || !preview) return;
    // S3 files load via the path-keyed proxy; inline files (BINARY, no s3Key)
    // stream through the authenticated download endpoint - FileDetailView handles
    // both. Always open the file the user clicked (don't bounce to the list).
    const s3Key = preview.s3Key ?? undefined;
    sidePanel.openTab({
      id: FILES_TAB_ID,
      label: preview.fileName ?? t('filesLabel'),
      icon: <FileImage className="w-4 h-4" />,
      preferredWidth: 0.4,
      content: (
        <FileDetailView
          s3Key={s3Key}
          entryId={fileId}
          fileName={preview.fileName}
          mimeType={preview.mimeType}
          sizeBytes={preview.sizeBytes ?? undefined}
          onBack={() => openFilesList(s3Key)}
        />
      ),
    });
  }, [sidePanel, preview, openFilesList, fileId, t]);

  // Auto-open the detail view once per file - only when the panel is already
  // open (non-intrusive: don't pop a panel the user closed). Matches the
  // image-generation card's policy.
  const autoOpenedRef = useRef(false);
  useEffect(() => {
    if (autoOpenedRef.current || !sidePanel || !preview) return;
    if (!sidePanel.isOpen) return;
    const seenKey = `lc_filecard_seen:${fileId}`;
    try {
      if (sessionStorage.getItem(seenKey)) { autoOpenedRef.current = true; return; }
      sessionStorage.setItem(seenKey, '1');
    } catch { /* sessionStorage unavailable → still auto-open once */ }
    autoOpenedRef.current = true;
    openDetail();
  }, [sidePanel, preview, fileId, openDetail]);

  // Skeleton - keeps the chat from reflowing when the preview arrives.
  if (isLoading) {
    return (
      <div className="relative my-6 isolate">
        <div className="rounded-xl overflow-hidden">
          <div className="w-full animate-pulse bg-theme-secondary" style={{ aspectRatio: '16 / 9' }} />
        </div>
        <div className="flex items-center gap-2 px-1 pt-2">
          <FileIcon className="w-4 h-4 text-theme-muted shrink-0" />
          <div className="h-4 w-32 bg-theme-tertiary rounded animate-pulse" />
        </div>
      </div>
    );
  }

  if (error || !preview) {
    return (
      <div className="relative my-6 isolate">
        <div className="rounded-xl bg-theme-secondary p-4 text-sm text-theme-muted flex items-center gap-2">
          <FileIcon className="w-4 h-4 shrink-0" />
          {t('failedToLoad')}{error ? `: ${error}` : ''}
        </div>
      </div>
    );
  }

  const mime = preview.mimeType ?? '';
  const displayName = preview.fileName ?? title ?? t('filesLabel');
  const sizeLabel = preview.sizeBytes != null ? fileService.formatFileSize(preview.sizeBytes) : null;
  const hasKey = !!preview.s3Key;
  const isImage = mime.startsWith('image/') && hasKey;
  const isVideo = mime.startsWith('video/') && hasKey;
  const isAudio = mime.startsWith('audio/') && hasKey;
  // Image + video get an inline media preview in the rounded frame; audio + other
  // files render the compact chip (audio adds an inline player).
  const showInlineMedia = isImage || isVideo;

  const handleCardClick = () => {
    if (!sidePanel) return;
    if (isTabActive) {
      sidePanel.removeTab(FILES_TAB_ID);
      sidePanel.close();
      return;
    }
    openDetail();
  };

  // Image file → thumbnail preview (object-contain). Non-image → compact chip.
  return (
    <div className="relative my-6 isolate cursor-pointer" onClick={handleCardClick}>
      {/* Active-tab overlay - visual parity with the other visualize cards. */}
      {isTabActive && (
        <div className="absolute inset-0 z-20 bg-black/5 backdrop-blur-[3px] flex items-center justify-center rounded-xl">
          <div className="flex items-center gap-2 px-4 py-2 bg-white/90 dark:bg-slate-800/90 rounded-full">
            <X className="w-4 h-4 text-theme-primary" />
            <span className="text-sm font-medium text-theme-primary">{t('clickToClose')}</span>
          </div>
        </div>
      )}

      {showInlineMedia ? (
        <>
          <div className="rounded-xl overflow-hidden bg-black/5 dark:bg-white/5 flex items-center justify-center">
            {!mediaUrl ? (
              <div className="w-full animate-pulse bg-theme-secondary" style={{ aspectRatio: '16 / 9' }} />
            ) : isImage ? (
              /* eslint-disable-next-line @next/next/no-img-element */
              <img
                src={mediaUrl}
                alt={displayName}
                className="w-full max-h-[60vh] object-contain"
                loading="lazy"
              />
            ) : (
              <video
                controls
                preload="metadata"
                src={mediaUrl}
                className="w-full max-h-[60vh] object-contain"
                onClick={(e) => e.stopPropagation()}
              />
            )}
          </div>
          <div className="flex items-center gap-2 px-1 pt-2">
            {isVideo ? (
              <FileVideo className="w-4 h-4 text-theme-muted shrink-0" />
            ) : (
              <FileImage className="w-4 h-4 text-theme-muted shrink-0" />
            )}
            <span className="text-sm font-medium text-theme-primary truncate flex-1" title={displayName}>
              {displayName}
            </span>
            {sizeLabel && <span className="text-xs text-theme-muted shrink-0">{sizeLabel}</span>}
          </div>
        </>
      ) : (
        <div className="rounded-xl border border-theme bg-theme-secondary px-3 py-2.5 hover:bg-theme-tertiary/50 transition-colors">
          <div className="flex items-center gap-3">
            <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-theme-tertiary shrink-0">
              {iconFor(mime)}
            </div>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium text-theme-primary truncate" title={displayName}>
                {displayName}
              </div>
              <div className="flex items-center gap-2 text-xs text-theme-muted">
                {sizeLabel && <span>{sizeLabel}</span>}
                {mime && <span className="truncate">{mime}</span>}
              </div>
            </div>
          </div>
          {isAudio && mediaUrl && (
            <audio
              controls
              preload="metadata"
              src={mediaUrl}
              className="mt-2 w-full"
              onClick={(e) => e.stopPropagation()}
            />
          )}
        </div>
      )}
    </div>
  );
}

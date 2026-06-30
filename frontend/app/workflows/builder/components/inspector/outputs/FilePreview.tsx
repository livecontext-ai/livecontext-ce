'use client';

/**
 * FilePreview - Component for displaying and downloading FileRef objects
 *
 * Renders a file card with:
 * - File icon based on MIME type
 * - Filename and size
 * - Download button
 *
 * Used in RunOutputPreview to enable file downloads from workflow outputs.
 */

import * as React from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import clsx from 'clsx';
import {
  Download,
  FileText,
  Image as ImageIcon,
  Video,
  Music,
  File,
  ChevronDown,
  ChevronUp,
} from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';
import { fileService, getFilePath, fileRefToUrl, isImageFile, isAudioFile, isVideoFile, type FileRef } from '@/lib/api/orchestrator/file.service';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

interface FilePreviewProps {
  fileRef: FileRef;
  className?: string;
}

export function FilePreview({ fileRef, className }: FilePreviewProps) {
  const t = useTranslations('workflowBuilder.inspector');
  const [isDownloading, setIsDownloading] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  const isImage = isImageFile(fileRef);
  const isAudio = isAudioFile(fileRef);
  const isVideo = isVideoFile(fileRef);
  const hasInlineMedia = isImage || isAudio || isVideo;

  // Inline preview (image/audio/video) - fetched with a Bearer header and
  // rendered from an in-memory blob: URL (no session token in the URL). See
  // useAuthedObjectUrl. A FileRef with no opaque id yields '' → null → no preview.
  const { url: imageUrl, error: imageError } = useAuthedObjectUrl(
    hasInlineMedia ? (fileRefToUrl(fileRef, { inline: true }) || null) : null,
  );

  const handleDownload = async () => {
    setIsDownloading(true);
    setError(null);

    try {
      await fileService.downloadAndSave(fileRef, fileRef.name);
    } catch (err) {
      console.error('Download failed:', err);
      setError('Download failed');
    } finally {
      setIsDownloading(false);
    }
  };

  const FileIcon = getFileIcon(fileRef.mimeType);
  const formattedSize = fileService.formatFileSize(fileRef.size);

  return (
    <div
      className={clsx(
        'rounded-lg border overflow-hidden',
        'bg-slate-50 dark:bg-slate-800/50',
        'border-slate-200 dark:border-slate-700',
        className
      )}
    >
      {/* Image thumbnail */}
      {isImage && imageUrl && !imageError && (
        <div className="w-full bg-slate-100 dark:bg-slate-900/50 flex items-center justify-center">
          <img
            src={imageUrl}
            alt={fileRef.name}
            className="max-h-40 w-full object-contain"
          />
        </div>
      )}

      {/* Audio player */}
      {isAudio && imageUrl && !imageError && (
        <div className="w-full bg-slate-100 dark:bg-slate-900/50 p-3">
          <audio
            controls
            preload="metadata"
            className="w-full"
            src={imageUrl}
          >
            {t('audioUnsupported')}
          </audio>
        </div>
      )}

      {/* Video player - preload metadata only (no auto-load full file) */}
      {isVideo && imageUrl && !imageError && (
        <div className="w-full bg-black flex items-center justify-center">
          <video
            controls
            preload="metadata"
            className="max-h-60 w-full"
            src={imageUrl}
          >
            {t('videoUnsupported')}
          </video>
        </div>
      )}

      <div className="flex items-center gap-3 p-3">
        {/* File Icon */}
        <div
          className={clsx(
            'flex-shrink-0 w-10 h-10 rounded-lg flex items-center justify-center',
            getIconBackground(fileRef.mimeType)
          )}
        >
          <FileIcon className="h-5 w-5 text-white" />
        </div>

        {/* File Info */}
        <div className="flex-1 min-w-0">
          <p
            className="text-sm font-medium text-slate-900 dark:text-slate-100 truncate"
            title={fileRef.name}
          >
            {fileRef.name}
          </p>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            {formattedSize} - {t(getFileTypeLabelKey(fileRef.mimeType))}
          </p>
          {error && (
            <p className="text-xs text-red-500 mt-1">{error}</p>
          )}
        </div>

        {/* Download Button */}
        <button
          onClick={handleDownload}
          disabled={isDownloading}
          className={clsx(
            'flex-shrink-0 p-2 rounded-md transition-colors',
            'text-slate-600 dark:text-slate-400',
            'hover:bg-slate-200 dark:hover:bg-slate-700',
            'disabled:opacity-50 disabled:cursor-not-allowed'
          )}
          title={t('downloadFile')}
        >
          {isDownloading ? (
            <LoadingSpinner size="xs" />
          ) : (
            <Download className="h-4 w-4" />
          )}
        </button>
      </div>
    </div>
  );
}

/**
 * Compact file preview for inline display in JSON trees
 * Can be expanded to show full details
 */
export function FilePreviewCompact({ fileRef }: FilePreviewProps) {
  const t = useTranslations('workflowBuilder.inspector');
  const [isDownloading, setIsDownloading] = React.useState(false);
  const [isExpanded, setIsExpanded] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  const handleDownload = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsDownloading(true);
    setError(null);

    try {
      await fileService.downloadAndSave(fileRef, fileRef.name);
    } catch (err) {
      console.error('Download failed:', err);
      setError('Download failed');
    } finally {
      setIsDownloading(false);
    }
  };

  const handleToggleExpand = (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsExpanded(!isExpanded);
  };

  const FileIcon = getFileIcon(fileRef.mimeType);
  const formattedSize = fileService.formatFileSize(fileRef.size);

  if (isExpanded) {
    // Expanded view with full details
    return (
      <div
        className={clsx(
          'flex flex-col gap-2 p-3 rounded-lg border',
          'bg-blue-50 dark:bg-blue-900/20',
          'border-blue-200 dark:border-blue-800',
          'text-sm'
        )}
      >
        {/* Header with collapse button */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div
              className={clsx(
                'w-8 h-8 rounded-md flex items-center justify-center',
                getIconBackground(fileRef.mimeType)
              )}
            >
              <FileIcon className="h-4 w-4 text-white" />
            </div>
            <div>
              <p className="font-medium text-slate-900 dark:text-slate-100">{fileRef.name}</p>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                {formattedSize} · {t(getFileTypeLabelKey(fileRef.mimeType))}
              </p>
            </div>
          </div>
          <button
            onClick={handleToggleExpand}
            className="p-1 rounded hover:bg-blue-100 dark:hover:bg-blue-800/50 text-slate-500"
            title={t('collapse')}
          >
            <ChevronUp className="h-4 w-4" />
          </button>
        </div>

        {/* Details */}
        <div className="space-y-1 text-xs font-mono text-slate-600 dark:text-slate-400 bg-white/50 dark:bg-slate-900/50 p-2 rounded">
          <div className="flex">
            <span className="text-slate-500 w-16">Path:</span>
            <span className="break-all">{getFilePath(fileRef)}</span>
          </div>
          <div className="flex">
            <span className="text-slate-500 w-16">MIME:</span>
            <span>{fileRef.mimeType}</span>
          </div>
          <div className="flex">
            <span className="text-slate-500 w-16">Size:</span>
            <span>{fileRef.size.toLocaleString(getClientLocale())} bytes</span>
          </div>
        </div>

        {/* Error message */}
        {error && (
          <p className="text-xs text-red-500">{error}</p>
        )}

        {/* Download button */}
        <button
          onClick={handleDownload}
          disabled={isDownloading}
          className={clsx(
            'flex items-center justify-center gap-2 px-3 py-1.5 rounded-md',
            'bg-blue-500 hover:bg-blue-600 text-white',
            'disabled:opacity-50 disabled:cursor-not-allowed',
            'transition-colors'
          )}
        >
          {isDownloading ? (
            <>
              <LoadingSpinner size="xs" />
              <span>{t('downloading')}</span>
            </>
          ) : (
            <>
              <Download className="h-4 w-4" />
              <span>{t('download')}</span>
            </>
          )}
        </button>
      </div>
    );
  }

  // Compact view
  return (
    <span
      className={clsx(
        'inline-flex items-center gap-1.5 px-2 py-1 rounded-md cursor-pointer',
        'bg-blue-50 dark:bg-blue-900/30',
        'text-blue-700 dark:text-blue-300',
        'text-sm font-mono',
        'hover:bg-blue-100 dark:hover:bg-blue-900/50 transition-colors'
      )}
      onClick={handleToggleExpand}
      title={t('clickToExpand')}
    >
      <FileIcon className="h-3.5 w-3.5" />
      <span className="truncate max-w-[150px]" title={fileRef.name}>
        {fileRef.name}
      </span>
      <span className="text-xs opacity-70">({formattedSize})</span>
      <ChevronDown className="h-3 w-3 opacity-50" />
      <button
        onClick={handleDownload}
        disabled={isDownloading}
        className={clsx(
          'ml-1 p-0.5 rounded hover:bg-blue-100 dark:hover:bg-blue-800/50',
          'disabled:opacity-50'
        )}
        title={t('download')}
      >
        {isDownloading ? (
          <LoadingSpinner size="xs" />
        ) : (
          <Download className="h-3 w-3" />
        )}
      </button>
    </span>
  );
}

// Helper functions

function getFileIcon(mimeType: string) {
  if (mimeType.startsWith('image/')) return ImageIcon;
  if (mimeType.startsWith('video/')) return Video;
  if (mimeType.startsWith('audio/')) return Music;
  if (
    mimeType.includes('pdf') ||
    mimeType.includes('document') ||
    mimeType.includes('text')
  ) {
    return FileText;
  }
  return File;
}

function getIconBackground(mimeType: string): string {
  if (mimeType.startsWith('image/')) return 'bg-purple-500';
  if (mimeType.startsWith('video/')) return 'bg-red-500';
  if (mimeType.startsWith('audio/')) return 'bg-orange-500';
  if (mimeType.includes('pdf')) return 'bg-red-600';
  if (mimeType.includes('spreadsheet') || mimeType.includes('csv')) return 'bg-green-500';
  if (mimeType.includes('document') || mimeType.includes('word')) return 'bg-blue-500';
  return 'bg-slate-500';
}

function getFileTypeLabelKey(mimeType: string): string {
  if (mimeType.startsWith('image/')) return 'fileTypeImage';
  if (mimeType.startsWith('video/')) return 'fileTypeVideo';
  if (mimeType.startsWith('audio/')) return 'fileTypeAudio';
  if (mimeType.includes('pdf')) return 'fileTypePdf';
  if (mimeType.includes('spreadsheet') || mimeType.includes('csv')) return 'fileTypeSpreadsheet';
  if (mimeType.includes('document') || mimeType.includes('word')) return 'fileTypeDocument';
  if (mimeType.includes('json')) return 'fileTypeJson';
  if (mimeType.includes('text')) return 'fileTypeText';
  return 'fileTypeFile';
}
